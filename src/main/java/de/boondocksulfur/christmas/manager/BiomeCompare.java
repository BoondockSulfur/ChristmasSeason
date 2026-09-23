package de.boondocksulfur.christmas.manager;

import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.util.FoliaSchedulerHelper;
import de.boondocksulfur.christmas.util.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Biome;

import java.io.File;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Compares the current world biomes with a backup database and can write the
 * backup state back for chunks that differ.
 *
 * <p>Threading: the backup database is read on an async thread; every chunk
 * comparison/restore is scheduled onto the region (Folia) or main thread (Paper)
 * that owns the chunk. At most {@code biome.restore.perTick} chunks are in flight
 * at any time so the server never stalls.
 */
public class BiomeCompare {

    /** How long the async worker waits for a single region task before giving up. */
    private static final long CHUNK_TASK_TIMEOUT_SECONDS = 60;

    private final ChristmasSeason plugin;
    private final LanguageManager lang;
    private final FoliaSchedulerHelper scheduler;

    public BiomeCompare(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.scheduler = plugin.getFoliaScheduler();
    }

    /**
     * Compares the world with a backup. Must be called from an async thread.
     *
     * @return the result, or {@code null} on error (details in the console)
     */
    public CompareResult compareWithBackup(File backupFile) {
        if (!backupFile.exists()) {
            lang.logWarning("log.compare.backup-not-found", backupFile.getName());
            return null;
        }

        String worldName = plugin.getConfig().getString("snowWorld", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            lang.logWarning("log.biome.world-not-found", worldName);
            return null;
        }

        BiomeSnapshotDatabase backupDb = new BiomeSnapshotDatabase(plugin, backupFile);
        try {
            backupDb.open();
            List<BiomeSnapshotDatabase.ChunkCoords> backupChunks = backupDb.getAllChunkCoordinates();
            lang.logInfo("log.compare.start", backupChunks.size());

            List<ChunkDifference> differences = Collections.synchronizedList(new ArrayList<>());
            java.util.concurrent.atomic.AtomicInteger compared = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger identical = new java.util.concurrent.atomic.AtomicInteger();

            boolean completed = forEachChunk(backupDb, backupChunks, worldName, (job) -> {
                ChunkDifference diff = compareChunk(world, job.x, job.z, job.snapshot);
                if (diff.hasDifferences()) {
                    differences.add(diff);
                } else {
                    identical.incrementAndGet();
                }
                int done = compared.incrementAndGet();
                if (done % 100 == 0) {
                    lang.logInfo("log.compare.progress", done, backupChunks.size(), differences.size());
                }
            });
            if (!completed) return null;

            lang.logInfo("log.compare.complete", compared.get(), identical.get(), differences.size());
            return new CompareResult(backupFile, compared.get(), identical.get(), new ArrayList<>(differences));

        } catch (SQLException e) {
            lang.logSevere("log.compare.error", e.getMessage());
            if (plugin.isDebugMode()) e.printStackTrace();
            return null;
        } finally {
            backupDb.close();
        }
    }

    /**
     * Writes the backup state back for every differing chunk. Must be called from an
     * async thread.
     *
     * @param result comparison result; recomputed when {@code null}
     * @return number of restored chunks, or -1 on error
     */
    public int fixDifferences(File backupFile, CompareResult result) {
        if (result == null) {
            lang.logInfo("log.compare.recomputing");
            result = compareWithBackup(backupFile);
            if (result == null) return -1;
        }
        if (result.differences.isEmpty()) {
            lang.logInfo("log.compare.nothing-to-fix");
            return 0;
        }

        String worldName = plugin.getConfig().getString("snowWorld", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            lang.logWarning("log.biome.world-not-found", worldName);
            return -1;
        }

        lang.logInfo("log.compare.fix-start", result.differences.size());

        List<BiomeSnapshotDatabase.ChunkCoords> coords = new ArrayList<>();
        for (ChunkDifference diff : result.differences) {
            coords.add(new BiomeSnapshotDatabase.ChunkCoords(worldName, diff.chunkX, diff.chunkZ));
        }

        BiomeSnapshotDatabase backupDb = new BiomeSnapshotDatabase(plugin, backupFile);
        try {
            backupDb.open();
            java.util.concurrent.atomic.AtomicInteger fixed = new java.util.concurrent.atomic.AtomicInteger();
            int total = coords.size();

            boolean completed = forEachChunk(backupDb, coords, worldName, (job) -> {
                restoreChunkFromSnapshot(world, job.x, job.z, job.snapshot);
                int done = fixed.incrementAndGet();
                if (done % 50 == 0) {
                    lang.logInfo("log.compare.fix-progress", done, total);
                }
            });
            if (!completed) return -1;

            lang.logInfo("log.compare.fix-complete", fixed.get());
            return fixed.get();

        } catch (SQLException e) {
            lang.logSevere("log.compare.fix-error", e.getMessage());
            if (plugin.isDebugMode()) e.printStackTrace();
            return -1;
        } finally {
            backupDb.close();
        }
    }

    /** One chunk handed from the async loader to the region task. */
    private static final class ChunkJob {
        final int x, z;
        final BiomeSnapshotDatabase.BiomeSnapshot3D snapshot;
        ChunkJob(int x, int z, BiomeSnapshotDatabase.BiomeSnapshot3D snapshot) { this.x = x; this.z = z; this.snapshot = snapshot; }
    }

    /**
     * Loads every chunk snapshot on the calling (async) thread and runs {@code action}
     * for it on the chunk's owning thread, keeping at most {@code perTick} chunks in flight.
     *
     * @return {@code false} if a region task did not complete in time (plugin disabled, world unloaded)
     */
    private boolean forEachChunk(BiomeSnapshotDatabase backupDb,
                                 List<BiomeSnapshotDatabase.ChunkCoords> chunks,
                                 String worldName,
                                 Consumer<ChunkJob> action) throws SQLException {
        int concurrency = Math.max(1, plugin.getConfig().getInt("biome.restore.perTick", 4));
        Semaphore inFlight = new Semaphore(concurrency);
        World world = Bukkit.getWorld(worldName);
        if (world == null) return false;

        for (BiomeSnapshotDatabase.ChunkCoords coords : chunks) {
            if (!coords.world.equals(worldName)) continue;

            BiomeSnapshotDatabase.BiomeSnapshot3D snapshot = backupDb.loadChunk3D(coords.world, coords.x, coords.z);
            if (snapshot == null) continue;

            if (!acquire(inFlight)) return false;

            ChunkJob job = new ChunkJob(coords.x, coords.z, snapshot);
            try {
                scheduler.runAtLocation(FoliaSchedulerHelper.chunkCenter(world, coords.x, coords.z), () -> {
                    try {
                        action.accept(job);
                    } catch (Exception e) {
                        lang.logWarning("log.biome.chunk-error", job.x, job.z, e.getMessage());
                        if (plugin.isDebugMode()) e.printStackTrace();
                    } finally {
                        inFlight.release();
                    }
                });
            } catch (Exception schedulingError) {
                inFlight.release();
                lang.logWarning("log.biome.chunk-error", coords.x, coords.z, schedulingError.getMessage());
                return false;
            }
        }

        // Wait until the last tasks have finished
        for (int i = 0; i < concurrency; i++) {
            if (!acquire(inFlight)) return false;
        }
        return true;
    }

    private boolean acquire(Semaphore semaphore) {
        try {
            if (semaphore.tryAcquire(CHUNK_TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return true;
            lang.logSevere("log.compare.timeout", CHUNK_TASK_TIMEOUT_SECONDS);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Compares one chunk; runs on the chunk's owning thread. Loads the chunk if needed. */
    private ChunkDifference compareChunk(World world, int chunkX, int chunkZ, BiomeSnapshotDatabase.BiomeSnapshot3D backupSnapshot) {
        world.getChunkAt(chunkX, chunkZ); // synchronous load on the owning thread
        int bx = chunkX << 4;
        int bz = chunkZ << 4;

        int differences = 0;
        Map<Biome, Integer> changedBiomes = new HashMap<>();

        for (int layer = 0; layer < backupSnapshot.biomes.length; layer++) {
            int y = backupSnapshot.yStart + (layer * backupSnapshot.yStep);
            for (int x = 0; x < 16; x += 4) {
                for (int z = 0; z < 16; z += 4) {
                    Biome backupBiome = backupSnapshot.biomes[layer][x][z];
                    Biome currentBiome = world.getBiome(bx + x, y, bz + z);
                    if (backupBiome != currentBiome) {
                        differences++;
                        changedBiomes.merge(currentBiome, 1, Integer::sum);
                    }
                }
            }
        }
        return new ChunkDifference(chunkX, chunkZ, differences, changedBiomes);
    }

    /** Writes the snapshot biomes into the chunk; runs on the chunk's owning thread. */
    private void restoreChunkFromSnapshot(World world, int chunkX, int chunkZ, BiomeSnapshotDatabase.BiomeSnapshot3D snapshot) {
        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        int bx = chunkX << 4;
        int bz = chunkZ << 4;

        for (int x = 0; x < 16; x += 4) {
            for (int z = 0; z < 16; z += 4) {
                for (int y = snapshot.yStart; y < snapshot.getYEnd(); y += snapshot.yStep) {
                    Biome originalBiome = snapshot.getBiomeAtY(x, z, y);
                    if (originalBiome != null) {
                        world.setBiome(bx + x, y, bz + z, originalBiome);
                    }
                }
            }
        }

        // Same clean-up as '/xmas off': snow layers and ice that do not belong to the original biome
        plugin.getBiomeSnowManager().removeWinterBlocks3D(world, chunk, snapshot);

        try {
            world.refreshChunk(chunk.getX(), chunk.getZ());
        } catch (Throwable ignored) {}
    }

    /** Differences found in one chunk. */
    public static class ChunkDifference {
        public final int chunkX;
        public final int chunkZ;
        /** Number of differing 4x4x4 cells. */
        public final int differenceCount;
        public final Map<Biome, Integer> changedBiomes;

        public ChunkDifference(int chunkX, int chunkZ, int differenceCount, Map<Biome, Integer> changedBiomes) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.differenceCount = differenceCount;
            this.changedBiomes = changedBiomes;
        }

        public boolean hasDifferences() {
            return differenceCount > 0;
        }
    }

    /** Overall comparison result. */
    public static class CompareResult {
        public final File backupFile;
        public final int totalChunks;
        public final int identicalChunks;
        public final List<ChunkDifference> differences;

        public CompareResult(File backupFile, int totalChunks, int identicalChunks, List<ChunkDifference> differences) {
            this.backupFile = backupFile;
            this.totalChunks = totalChunks;
            this.identicalChunks = identicalChunks;
            this.differences = differences;
        }

        public double getMatchPercentage() {
            if (totalChunks == 0) return 0;
            return (identicalChunks * 100.0) / totalChunks;
        }
    }
}
