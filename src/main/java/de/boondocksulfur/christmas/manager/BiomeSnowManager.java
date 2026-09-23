package de.boondocksulfur.christmas.manager;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.util.FoliaSchedulerHelper;
import de.boondocksulfur.christmas.util.LanguageManager;
import de.boondocksulfur.christmas.util.Registries;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Turns the surface biomes around players into the configured winter biome while
 * the event is active and restores the originals from the SQLite snapshot on
 * {@code /xmas off}.
 *
 * <p>Threading model:
 * <ul>
 *   <li>Paper/Purpur: one global timer collects chunks around players into a queue
 *       and processes {@code perTickBudget} of them per run.</li>
 *   <li>Folia: one entity-scheduler timer per player schedules up to
 *       {@code perTickBudget} chunks per run onto their owning regions.</li>
 * </ul>
 *
 * <p>Biomes are stored in 4x4x4 cells, so every write and every sample uses one
 * call per cell instead of one per block.
 */
public class BiomeSnowManager {

    /** Biome cell size in blocks (Minecraft 1.18+). */
    private static final int CELL = 4;
    /** Clean-up looks this many blocks beyond the changed Y range (biome blur at the range border). */
    private static final int CLEANUP_MARGIN = 8;
    /** Default Y range of surface biomes that are changed and restored ({@code biome.changeMinY/MaxY}). */
    private static final int DEFAULT_MIN_CHANGE_Y = 50;
    private static final int DEFAULT_MAX_CHANGE_Y = 200;

    private final ChristmasSeason plugin;
    private final LanguageManager lang;
    private final FoliaSchedulerHelper scheduler;
    private BiomeSnapshotDatabase db;

    public BiomeSnowManager(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.scheduler = plugin.getFoliaScheduler();
    }

    // ------------------------------------------------------------------ state

    private final Map<UUID, WrappedTask> playerBubbleTasks = new ConcurrentHashMap<>();
    private WrappedTask globalBubbleTask; // Paper/Purpur only

    /** Folia: chunks seen around a player that are still waiting for their turn (survive fast movement). */
    private final Map<UUID, Set<ChunkKey>> playerPendingChunks = new ConcurrentHashMap<>();

    /** Paper/Purpur: chunks waiting to be processed by the global timer. */
    private final Queue<ChunkCoords> chunkProcessQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private static class ChunkCoords {
        final World world;
        final int x, z;
        ChunkCoords(World w, int x, int z) { this.world = w; this.x = x; this.z = z; }
    }

    private static final class ChunkKey {
        final String world; final int x; final int z;
        ChunkKey(String w, int x, int z){ this.world=w; this.x=x; this.z=z; }
        @Override public boolean equals(Object o){ if(this==o) return true; if(!(o instanceof ChunkKey k)) return false; return x==k.x && z==k.z && Objects.equals(world,k.world); }
        @Override public int hashCode(){ return Objects.hash(world,x,z); }
    }

    /** Chunks already converted; bounded, entries may be evicted and re-checked later. */
    private final Set<ChunkKey> processedChunks = ConcurrentHashMap.newKeySet();
    private static final int MAX_PROCESSED_CACHE = 5000;

    /** Chunks set manually via {@code /xmas biome set}; never evicted, never overwritten by the bubble. */
    private final Set<ChunkKey> manualChunks = ConcurrentHashMap.newKeySet();

    /** Chunks known to have a snapshot in the database (saves a query per visit). */
    private final Set<ChunkKey> knownSnapshotChunks = ConcurrentHashMap.newKeySet();

    /** Retry counter for chunks that were not loaded when their turn came. */
    private final Map<ChunkKey, Integer> chunkRetryCount = new ConcurrentHashMap<>();
    private static final int MAX_CHUNK_RETRIES = 3;

    /** Guards against parallel restores and tells the completion check whether {@code db} is still ours. */
    private final AtomicBoolean restoreRunning = new AtomicBoolean(false);

    /** Resolved once per start; unknown names fall back to SNOWY_PLAINS with a single warning. */
    private volatile Biome targetBiome = Biome.SNOWY_PLAINS;

    // -------------------------------------------------------------- lifecycle

    public void start() {
        stop();
        if (!plugin.isActive()) return;
        if (!plugin.getConfig().getBoolean("biome.enabled", true)) return;

        plugin.debug("BiomeSnowManager.start()");

        String biomeName = plugin.getConfig().getString("biome.target", "SNOWY_PLAINS");
        Biome resolved = Registries.biomeByName(biomeName);
        if (resolved == null) {
            lang.logWarning("log.config.unknown-target-biome", biomeName);
            resolved = Biome.SNOWY_PLAINS;
        }
        targetBiome = resolved;

        if (plugin.getConfig().getBoolean("biome.enableSnapshot", true)) {
            db = new BiomeSnapshotDatabase(plugin);
            try {
                db.open();
                lang.logInfo("log.biome.database-ready");
                plugin.debug("Database opened: " + db.getDatabaseSize() + " bytes, " + db.getChunkCount() + " chunks");
            } catch (SQLException e) {
                lang.logSevere("log.biome.error-opening-database", e.getMessage());
                lang.logSevere("log.biome.snapshot-system-disabled");
                e.printStackTrace();
                db = null;
            }
        } else {
            lang.logInfo("log.biome.system-disabled-config");
            db = null;
        }

        if (scheduler.isFolia()) {
            plugin.debug("BiomeSnowManager ready (Folia: per-player schedulers)");
        } else {
            startGlobalBubbleTask();
            plugin.debug("BiomeSnowManager ready (Paper: global timer)");
        }
    }

    public void stop() {
        stop(true);
    }

    /**
     * Stops all tasks and clears the caches.
     *
     * @param closeDatabase {@code false} keeps the database open ({@code /xmas off} still needs it for the restore)
     */
    public void stop(boolean closeDatabase) {
        plugin.debug("BiomeSnowManager.stop(closeDatabase=" + closeDatabase + ")");

        if (globalBubbleTask != null) {
            globalBubbleTask.cancel();
            globalBubbleTask = null;
        }
        cancelConvertAll();
        for (WrappedTask task : playerBubbleTasks.values()) {
            if (task != null) task.cancel();
        }
        playerBubbleTasks.clear();
        playerPendingChunks.clear();

        chunkProcessQueue.clear();
        processedChunks.clear();
        manualChunks.clear();
        knownSnapshotChunks.clear();
        chunkRetryCount.clear();

        if (closeDatabase && db != null) {
            db.printStats();
            db.close();
            plugin.debug("Database closed");
            db = null;
        } else if (db != null) {
            plugin.debug("Database stays open for the restore");
        }
    }

    // ---------------------------------------------------------- bubble tasks

    /** Paper/Purpur: one global timer feeds the chunk queue and processes a budget per run. */
    private void startGlobalBubbleTask() {
        if (!plugin.getConfig().getBoolean("biome.playerBubble.enabled", true)) return;

        int period = Math.max(5, plugin.getConfig().getInt("biome.playerBubble.tickIntervalTicks", 40));

        globalBubbleTask = scheduler.runGlobalTaskTimer(() -> {
            for (World w : plugin.getSnowWorlds()) {
                for (Player p : w.getPlayers()) {
                    if (p.isOnline() && p.isValid()) {
                        queueChunksAroundPlayer(p, w);
                    }
                }
            }

            int budget = Math.max(1, plugin.getConfig().getInt("biome.playerBubble.perTickBudget", 12));
            processChunksFromQueue(budget);
        }, 40L, period);
    }

    /**
     * Folia: starts the per-player biome timer on the player's entity scheduler.
     * No-op on Paper/Purpur (the global timer covers all players).
     */
    public void startPlayerTracking(Player player) {
        if (!plugin.isActive()) return;
        if (!plugin.getConfig().getBoolean("biome.playerBubble.enabled", true)) return;
        if (!scheduler.isFolia()) return;

        UUID uuid = player.getUniqueId();
        WrappedTask oldTask = playerBubbleTasks.remove(uuid);
        if (oldTask != null) oldTask.cancel();

        int period = Math.max(5, plugin.getConfig().getInt("biome.playerBubble.tickIntervalTicks", 40));
        WrappedTask task = scheduler.runForEntityTimer(player, () -> {
            if (!player.isOnline() || !player.isValid()) {
                stopPlayerTracking(player);
                return;
            }
            ensureAroundPlayer(player);
        }, () -> playerBubbleTasks.remove(uuid), 40L, period);

        if (task != null) {
            playerBubbleTasks.put(uuid, task);
            plugin.debug("Biome tracking started for " + player.getName());
        }
    }

    /** Stops the per-player biome timer. */
    public void stopPlayerTracking(Player player) {
        playerPendingChunks.remove(player.getUniqueId());
        WrappedTask task = playerBubbleTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
            plugin.debug("Biome tracking stopped for " + player.getName());
        }
    }

    /**
     * Called from {@code ChunkLoadEvent}. Only chunks near a player are queued so that
     * pre-generation, spawn chunks and other plugins loading far-away chunks neither
     * bloat the snapshot database nor bypass the per-tick budget.
     *
     * <p>On Folia this is a no-op: the per-player timer already covers the chunks
     * around every player within one interval.
     */
    public void onChunkLoad(Chunk chunk) {
        if (!plugin.isActive()) return;
        if (scheduler.isFolia()) return;
        if (!plugin.getConfig().getBoolean("biome.playerBubble.enabled", true)) return;

        World w = chunk.getWorld();
        ChunkKey key = new ChunkKey(w.getName(), chunk.getX(), chunk.getZ());
        if (processedChunks.contains(key) || manualChunks.contains(key)) return;

        int r = Math.max(0, plugin.getConfig().getInt("biome.playerBubble.radiusChunks", 2));
        for (Player p : w.getPlayers()) {
            Location loc = p.getLocation();
            if (Math.abs((loc.getBlockX() >> 4) - chunk.getX()) <= r
                    && Math.abs((loc.getBlockZ() >> 4) - chunk.getZ()) <= r) {
                chunkProcessQueue.offer(new ChunkCoords(w, chunk.getX(), chunk.getZ()));
                return;
            }
        }
    }

    // ---------------------------------------------------------------- restore

    /** @return {@code true} while a restore is running (guard for {@code /xmas on}) */
    public boolean isRestoring() {
        return restoreRunning.get();
    }

    /**
     * Restores every snapshotted chunk asynchronously in budgeted batches ({@code /xmas off}).
     * Closes the database once all chunks have been processed.
     *
     * @param perTick number of chunks scheduled per tick
     */
    public void restoreALLAsync(int perTick) {
        if (db == null) {
            lang.logWarning("log.biome.restore-error-header");
            lang.logWarning("log.biome.no-snapshot-available");
            lang.logWarning("log.biome.snapshot-disabled-or-error");
            lang.logWarning("log.biome.solution-enable-snapshot");
            return;
        }

        // Two parallel restores would delete each other's chunks and close the database twice
        if (!restoreRunning.compareAndSet(false, true)) {
            lang.logWarning("log.biome.restore-already-running");
            return;
        }

        plugin.debug("Clearing caches before restore");
        processedChunks.clear();
        manualChunks.clear();
        knownSnapshotChunks.clear();
        chunkProcessQueue.clear();
        chunkRetryCount.clear();

        final BiomeSnapshotDatabase database = db;

        try {
            final List<BiomeSnapshotDatabase.ChunkCoords> allChunks = database.getAllChunkCoordinates();
            final int totalChunks = allChunks.size();
            lang.logInfo("log.biome.restore-start-header");
            lang.logInfo("log.biome.chunks-in-database", totalChunks);

            if (totalChunks == 0) {
                lang.logWarning("log.biome.snapshot-empty");
                lang.logWarning("log.biome.snapshot-timing-question");
                lang.logInfo("log.biome.separator-line");
                database.close();
                if (db == database) db = null;
                restoreRunning.set(false);
                return;
            }

            final int budget = Math.max(1, perTick);
            restoreTotal = totalChunks; restoreFinished = 0; restoreRestored = 0; restoreErrors = 0;
            final Set<ChunkKey> restoreSet = new HashSet<>();
            for (BiomeSnapshotDatabase.ChunkCoords c : allChunks) restoreSet.add(new ChunkKey(c.world, c.x, c.z));
            lang.logInfo("log.biome.starting-restore", totalChunks);
            lang.logInfo("log.biome.budget", budget);
            final long startTime = System.currentTimeMillis();

            // Only touched by the global timer (sequential)
            final int[] scheduled = {0};
            // Incremented from region threads
            final AtomicInteger restored = new AtomicInteger(0);
            final AtomicInteger errors = new AtomicInteger(0);
            final AtomicInteger finished = new AtomicInteger(0);
            final AtomicBoolean completionDone = new AtomicBoolean(false);

            // Runs the wrap-up (stats, caches, close) once ALL region tasks have finished
            final Runnable completionCheck = () -> {
                restoreFinished = finished.get(); restoreRestored = restored.get(); restoreErrors = errors.get();
                if (finished.get() < totalChunks) return;
                if (!completionDone.compareAndSet(false, true)) return;

                long duration = System.currentTimeMillis() - startTime;
                lang.logInfo("log.biome.restore-complete-header");
                lang.logInfo("log.biome.processed", totalChunks);
                lang.logInfo("log.biome.restored-count", restored.get());
                if (errors.get() > 0) {
                    lang.logInfo("log.biome.error-count", errors.get());
                }
                lang.logInfo("log.biome.duration", (duration / 1000.0));
                lang.logInfo("log.biome.separator-footer");

                processedChunks.clear();
                manualChunks.clear();
                knownSnapshotChunks.clear();
                chunkProcessQueue.clear();
                chunkRetryCount.clear();

                // Successfully restored chunks were deleted individually; failed ones stay
                // in the database and are retried on the next '/xmas off'
                try {
                    if (errors.get() > 0) {
                        lang.logWarning("log.biome.chunks-kept-for-retry", errors.get());
                    } else {
                        database.clearAll(); // already empty - just compacts the file
                    }
                    database.close();
                    plugin.debug("Database closed after restore");
                    // Only null out 'db' if it is still our instance - '/xmas on' during the
                    // restore may already have opened a new one
                    if (db == database) db = null;
                } catch (Exception e) {
                    lang.logWarning("log.biome.error-clearing-db", e.getMessage());
                } finally {
                    restoreRunning.set(false);
                }
            };

            final WrappedTask[] restoreTask = new WrappedTask[1];
            restoreTask[0] = scheduler.runGlobalTaskTimer(() -> {
                try {
                    int plannedThisTick = 0;
                    while (plannedThisTick < budget && scheduled[0] < totalChunks) {
                        final BiomeSnapshotDatabase.ChunkCoords coords = allChunks.get(scheduled[0]);
                        scheduled[0]++;
                        plannedThisTick++;

                        BiomeSnapshotDatabase.BiomeSnapshot3D loadedSnapshot = null;
                        World world = Bukkit.getWorld(coords.world);
                        try {
                            if (world == null) {
                                lang.logWarning("log.biome.world-not-found", coords.world);
                            } else {
                                loadedSnapshot = database.loadChunk3D(coords.world, coords.x, coords.z);
                                if (loadedSnapshot == null) {
                                    lang.logWarning("log.biome.chunk-data-not-in-db", coords.x, coords.z);
                                }
                            }
                        } catch (Exception chunkError) {
                            lang.logWarning("log.biome.chunk-error", coords.x, coords.z, chunkError.getMessage());
                            loadedSnapshot = null;
                        }

                        // A chunk that failed to load still counts as finished, otherwise the
                        // completion check never fires
                        if (loadedSnapshot == null) {
                            errors.incrementAndGet();
                            finished.incrementAndGet();
                            completionCheck.run();
                            continue;
                        }

                        final BiomeSnapshotDatabase.BiomeSnapshot3D snapshot = loadedSnapshot;
                        final World finalWorld = world;
                        final int chunkX = coords.x;
                        final int chunkZ = coords.z;

                        // Every chunk runs on its own region (batching is not allowed on Folia).
                        // If scheduling itself throws (world unloading, plugin disabling), the
                        // chunk must still be counted.
                        try {
                            scheduler.runAtLocation(FoliaSchedulerHelper.chunkCenter(finalWorld, chunkX, chunkZ), () -> {
                                boolean success = false;
                                try {
                                    Chunk chunk = finalWorld.getChunkAt(chunkX, chunkZ);
                                    if (!chunk.isLoaded() && !finalWorld.loadChunk(chunkX, chunkZ, true)) {
                                        lang.logWarning("log.biome.chunk-not-loaded-db", chunkX, chunkZ);
                                    } else {
                                        chunk = finalWorld.getChunkAt(chunkX, chunkZ);
                                        restoreChunkBiomes3D(finalWorld, chunk, snapshot);
                                        removeWinterBlocks3D(finalWorld, chunk, snapshot);
                                        cleanNeighbourBorders(finalWorld, chunkX, chunkZ, restoreSet, snapshot);
                                        refreshChunkSafe(finalWorld, chunk);
                                        success = true;
                                    }
                                } catch (Exception e) {
                                    lang.logWarning("log.biome.error-restoring-chunk", chunkX, chunkZ, e.getMessage());
                                    if (plugin.isDebugMode()) e.printStackTrace();
                                } finally {
                                    if (success) {
                                        restored.incrementAndGet();
                                        // Delete only after a successful restore, otherwise the
                                        // chunk would never be retried
                                        try {
                                            database.deleteChunk(coords.world, chunkX, chunkZ);
                                            database.deletePlaced(coords.world, chunkX, chunkZ);
                                        } catch (SQLException e) {
                                            lang.logWarning("log.database.delete-chunk-error", e.getMessage());
                                        }
                                    } else {
                                        errors.incrementAndGet();
                                        lang.logWarning("log.biome.chunk-not-restored", chunkX, chunkZ);
                                    }
                                    finished.incrementAndGet();
                                    completionCheck.run();
                                }
                            });
                        } catch (Exception schedulingError) {
                            lang.logWarning("log.biome.chunk-error", chunkX, chunkZ, schedulingError.getMessage());
                            errors.incrementAndGet();
                            finished.incrementAndGet();
                            completionCheck.run();
                        }
                    }

                    if (scheduled[0] % 50 == 0 && scheduled[0] < totalChunks) {
                        lang.logInfo("log.biome.restore-progress", scheduled[0], totalChunks, restored.get(), errors.get());
                    }

                    // Stop the timer once everything is scheduled; the wrap-up happens in the
                    // completion check of the last region task
                    if (scheduled[0] >= totalChunks && restoreTask[0] != null) {
                        restoreTask[0].cancel();
                    }
                } catch (Exception e) {
                    lang.logSevere("log.biome.fatal-error", e.getMessage());
                    e.printStackTrace();
                    if (restoreTask[0] != null) {
                        restoreTask[0].cancel();
                    }
                    // Count chunks that will never be scheduled, otherwise the database stays open
                    int neverScheduled = totalChunks - scheduled[0];
                    if (neverScheduled > 0) {
                        errors.addAndGet(neverScheduled);
                        finished.addAndGet(neverScheduled);
                    }
                    completionCheck.run();
                }
            }, 1L, 1L);

        } catch (SQLException e) {
            lang.logSevere("log.biome.error-retrieving-data", e.getMessage());
            e.printStackTrace();
            restoreRunning.set(false);
        }
    }

    /**
     * Writes the original biomes back, one call per 4x4x4 cell.
     * Uses the Y layout stored in the snapshot, not the current config.
     */
    private void restoreChunkBiomes3D(World world, Chunk chunk, BiomeSnapshotDatabase.BiomeSnapshot3D snapshot) {
        int step = snapshot.yStep;
        int bx = chunk.getX() << 4;
        int bz = chunk.getZ() << 4;

        plugin.verboseDebugLang("log.debug.restore.chunk", chunk.getX(), chunk.getZ());
        plugin.verboseDebugLang("log.debug.restore.snapshot-info", snapshot.yStart, snapshot.yStep, snapshot.biomes.length);

        int restored = 0;
        for (int x = 0; x < 16; x += CELL) {
            for (int z = 0; z < 16; z += CELL) {
                for (int y = snapshot.yStart; y < snapshot.getYEnd(); y += step) {
                    Biome originalBiome = snapshot.getBiomeAtY(x, z, y);
                    if (originalBiome != null) {
                        world.setBiome(bx + x, y, bz + z, originalBiome);
                        restored++;
                    }
                }
            }
        }
        plugin.verboseDebugLang("log.debug.restore.complete", restored);
    }

    /**
     * Removes snow layers and thin ice that formed while the chunk was a winter biome,
     * but only where the original biome is not naturally snowy/icy and the column did not
     * already carry snow/ice when the snapshot was taken. Snow and ice only
     * appear on the sky-exposed surface, so per column only the highest non-air block
     * (WORLD_SURFACE heightmap - a single snow layer does not block motion and would be
     * missed by MOTION_BLOCKING) and the block below it are checked. Both steps can be
     * disabled in the config because they also affect player-placed blocks. Must run on
     * the chunk's thread.
     */
    public void removeWinterBlocks3D(World world, Chunk chunk, BiomeSnapshotDatabase.BiomeSnapshot3D snapshot) {
        boolean removeSnow = plugin.getConfig().getBoolean("biome.restore.removeSnowLayers", true);
        boolean removeIce = plugin.getConfig().getBoolean("biome.restore.removeIce", true);
        if (!removeSnow && !removeIce) return;

        int bx = chunk.getX() << 4;
        int bz = chunk.getZ() << 4;
        int topLayerY = snapshot.getYEnd() - snapshot.yStep;
        // Only surfaces inside the range the plugin actually changed (plus a margin) - snow
        // above or below it is natural (altitude snow on peaks, for example) and stays
        int minY = snapshot.yStart - CLEANUP_MARGIN;
        int maxY = snapshot.getYEnd() + CLEANUP_MARGIN;
        int[] removed = new int[2];
        // Columns where players placed snow/ice during the event
        byte[] placed = placedMask(db, world, chunk.getX(), chunk.getZ());
        BiomeSnapshotDatabase.BiomeSnapshot3D placedView = placed == null ? null
                : new BiomeSnapshotDatabase.BiomeSnapshot3D(snapshot.biomes, snapshot.yStart, snapshot.yStep, placed, placed);

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                Block top = world.getHighestBlockAt(bx + x, bz + z, org.bukkit.HeightMap.WORLD_SURFACE);
                if (top.getY() < minY || top.getY() > maxY) continue;
                int y = Math.max(snapshot.yStart, Math.min(top.getY(), topLayerY));
                Biome originalBiome = snapshot.getBiomeAtY(x, z, y);
                if (originalBiome == null) continue;
                // Columns that had snow/ice before the event, or got some from a player, keep it
                if (placedView != null && placedView.hadSnow(x, z)) continue;
                cleanColumnTop(top, removeSnow && !isNaturallySnowyBiome(originalBiome) && !snapshot.hadSnow(x, z),
                        removeIce && !isNaturallyIcyBiome(originalBiome) && !snapshot.hadIce(x, z), removed);
            }
        }

        if (plugin.isDebugMode() && (removed[0] > 0 || removed[1] > 0)) {
            plugin.debug("Chunk " + chunk.getX() + "," + chunk.getZ() + ": removed " + removed[0] + " snow layers, " + removed[1] + " ice");
        }
    }

    /**
     * Cleans the top of one column: a snow layer on top (and the ice sheet directly below
     * it or on top itself). {@code removed[0]} counts snow, {@code removed[1]} ice.
     */
    private void cleanColumnTop(Block top, boolean snow, boolean ice, int[] removed) {
        Block candidate = top;
        // Only snow layers, never snow blocks (those are crafted/placed)
        if (snow && candidate.getType() == Material.SNOW) {
            candidate.setType(Material.AIR, false);
            removed[0]++;
            candidate = candidate.getRelative(0, -1, 0);
            // Grass, podzol and mycelium keep a white 'snowy' top until updated - reset it
            if (candidate.getBlockData() instanceof org.bukkit.block.data.Snowable snowable && snowable.isSnowy()) {
                snowable.setSnowy(false);
                candidate.setBlockData(snowable, false);
            }
        }
        // Only plain ice, never packed/blue ice (those are placed)
        if (ice && candidate.getType() == Material.ICE) {
            candidate.setType(Material.WATER, false);
            removed[1]++;
        }
    }

    /**
     * Minecraft blurs biome borders by up to a few blocks, so snow also falls on the outer
     * blocks of chunks that were never converted (and therefore have no snapshot). After a
     * chunk is restored, the four-block strips of every neighbouring chunk that is not part
     * of the restore are cleaned as well, judged by their current (untouched) biome and
     * limited to the restored chunk's Y range. Neighbours that are not loaded are skipped.
     */
    private void cleanNeighbourBorders(World world, int chunkX, int chunkZ, Set<ChunkKey> restoreSet,
                                       BiomeSnapshotDatabase.BiomeSnapshot3D snapshot) {
        boolean removeSnow = plugin.getConfig().getBoolean("biome.restore.removeSnowLayers", true);
        boolean removeIce = plugin.getConfig().getBoolean("biome.restore.removeIce", true);
        if (!removeSnow && !removeIce) return;
        final int strip = 4;
        final int minY = snapshot.yStart - CLEANUP_MARGIN;
        final int maxY = snapshot.getYEnd() + CLEANUP_MARGIN;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                final int nx = chunkX + dx, nz = chunkZ + dz;
                if (restoreSet.contains(new ChunkKey(world.getName(), nx, nz))) continue;
                if (!world.isChunkLoaded(nx, nz)) continue;

                // Block range of the strip inside the neighbour that touches this chunk
                final int fromX = dx == 0 ? 0 : (dx < 0 ? 16 - strip : 0);
                final int toX   = dx == 0 ? 16 : (dx < 0 ? 16 : strip);
                final int fromZ = dz == 0 ? 0 : (dz < 0 ? 16 - strip : 0);
                final int toZ   = dz == 0 ? 16 : (dz < 0 ? 16 : strip);
                final int fdx = dx, fdz = dz;

                scheduler.runAtLocation(FoliaSchedulerHelper.chunkCenter(world, nx, nz), () -> {
                    if (!world.isChunkLoaded(nx, nz)) return;
                    int bx = nx << 4, bz = nz << 4;
                    int[] removed = new int[2];
                    byte[] placed = placedMask(db, world, nx, nz);
                    for (int x = fromX; x < toX; x++) {
                        for (int z = fromZ; z < toZ; z++) {
                            if (placed != null && (placed[(((x & 15) << 4) | (z & 15)) >> 3] & (1 << ((((x & 15) << 4) | (z & 15)) & 7))) != 0) continue;
                            Block top = world.getHighestBlockAt(bx + x, bz + z, org.bukkit.HeightMap.WORLD_SURFACE);
                            if (top.getY() < minY || top.getY() > maxY) continue;
                            Biome current = world.getBiome(bx + x, top.getY(), bz + z);
                            cleanColumnTop(top, removeSnow && !isNaturallySnowyBiome(current),
                                    removeIce && !isNaturallyIcyBiome(current), removed);
                        }
                    }
                    if (plugin.isDebugMode() && (removed[0] > 0 || removed[1] > 0)) {
                        plugin.debug("Border of chunk " + nx + "," + nz + " (next to " + chunkX + "," + chunkZ + ", dx=" + fdx + " dz=" + fdz + "): removed " + removed[0] + " snow layers, " + removed[1] + " ice");
                    }
                });
            }
        }
    }

    /**
     * Records a snow layer or ice block placed by a player while the event is active, so the
     * restore leaves that column alone. Called from the block place listener.
     */
    public void markPlayerPlaced(Location loc) {
        BiomeSnapshotDatabase database = db;
        if (database == null || !database.isOpen()) return;
        try {
            database.markPlaced(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockZ());
            plugin.debug("Player placed snow/ice recorded at " + loc.getBlockX() + "," + loc.getBlockZ());
        } catch (SQLException e) {
            plugin.debug("Could not record player placement: " + e.getMessage());
        }
    }

    /** @return player placement mask for a chunk, or {@code null} (also when the database is closed) */
    private byte[] placedMask(BiomeSnapshotDatabase database, World world, int chunkX, int chunkZ) {
        if (database == null || !database.isOpen()) return null;
        try {
            return database.getPlacedMask(world.getName(), chunkX, chunkZ);
        } catch (SQLException e) {
            return null;
        }
    }

    /** Deletes all snapshots (database only, world untouched). */
    public void clearSnapshot() {
        if (db != null) {
            try {
                db.clearAll();
                lang.logInfo("log.biome.snapshot-deleted");
            } catch (SQLException e) {
                lang.logSevere("log.biome.error-deleting-snapshot", e.getMessage());
            }
        }
    }

    /** @return the open database, or {@code null} while the snapshot system is off */
    public BiomeSnapshotDatabase getDatabase() {
        return db;
    }

    // ------------------------------------------------------------ processing

    /**
     * Folia: schedules chunks around a player onto their regions (called from the player's
     * scheduler). Chunks in the radius are collected into a per-player pending set; each run
     * schedules up to {@code perTickBudget} of them, nearest first. Chunks that did not fit
     * stay pending and are processed later even if the player has already moved on, so fast
     * movement no longer leaves gaps behind.
     */
    public void ensureAroundPlayer(Player p) {
        World w = p.getWorld();
        if (!plugin.isSnowWorld(w)) {
            playerPendingChunks.remove(p.getUniqueId());
            return;
        }

        int r = Math.max(0, plugin.getConfig().getInt("biome.playerBubble.radiusChunks", 2));
        int budget = Math.max(1, plugin.getConfig().getInt("biome.playerBubble.perTickBudget", 12));
        Location loc = p.getLocation();
        int baseCX = loc.getBlockX() >> 4;
        int baseCZ = loc.getBlockZ() >> 4;

        Set<ChunkKey> pending = playerPendingChunks.computeIfAbsent(p.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkKey key = new ChunkKey(w.getName(), baseCX + dx, baseCZ + dz);
                if (!processedChunks.contains(key) && !manualChunks.contains(key)) {
                    pending.add(key);
                }
            }
        }

        // Nearest chunks first so the area around the player closes before the trail behind
        List<ChunkKey> ordered = new ArrayList<>(pending);
        ordered.sort(Comparator.comparingInt(k -> (k.x - baseCX) * (k.x - baseCX) + (k.z - baseCZ) * (k.z - baseCZ)));

        int scheduled = 0;
        for (ChunkKey key : ordered) {
            if (scheduled >= budget) break;
            pending.remove(key);
            if (!key.world.equals(w.getName()) || processedChunks.contains(key) || manualChunks.contains(key)) continue;
            final int chunkX = key.x, chunkZ = key.z;
            scheduler.runAtLocation(FoliaSchedulerHelper.chunkCenter(w, chunkX, chunkZ), () -> {
                // Not loaded yet (typical when flying): put it back, it gets another turn
                if (!w.isChunkLoaded(chunkX, chunkZ)) {
                    Set<ChunkKey> mine = playerPendingChunks.get(p.getUniqueId());
                    if (mine != null && plugin.isActive()) mine.add(key);
                    return;
                }
                processChunkAt(w, chunkX, chunkZ);
            });
            scheduled++;
        }
    }

    /** Paper/Purpur: queues the unprocessed loaded chunks around a player. */
    private void queueChunksAroundPlayer(Player p, World w) {
        int r = Math.max(0, plugin.getConfig().getInt("biome.playerBubble.radiusChunks", 2));
        Location loc = p.getLocation();
        int baseCX = loc.getBlockX() >> 4;
        int baseCZ = loc.getBlockZ() >> 4;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int chunkX = baseCX + dx;
                int chunkZ = baseCZ + dz;
                ChunkKey key = new ChunkKey(w.getName(), chunkX, chunkZ);
                if (processedChunks.contains(key) || manualChunks.contains(key)) continue;
                if (w.isChunkLoaded(chunkX, chunkZ)) {
                    chunkProcessQueue.offer(new ChunkCoords(w, chunkX, chunkZ));
                }
            }
        }
    }

    /** Paper/Purpur: processes up to {@code budget} queued chunks. */
    private void processChunksFromQueue(int budget) {
        int processed = 0;
        while (processed < budget && !chunkProcessQueue.isEmpty()) {
            ChunkCoords coords = chunkProcessQueue.poll();
            if (coords == null) break;

            ChunkKey key = new ChunkKey(coords.world.getName(), coords.x, coords.z);
            if (processedChunks.contains(key) || manualChunks.contains(key)) continue;
            if (coords.world.isChunkLoaded(coords.x, coords.z)) {
                processChunkAt(coords.world, coords.x, coords.z);
                processed++;
            }
        }

        if (plugin.isDebugMode() && chunkProcessQueue.size() > 50) {
            plugin.debug("Chunk queue: " + chunkProcessQueue.size() + " chunks waiting");
        }
    }

    /**
     * Snapshots and converts one chunk. Must run on the thread owning the chunk
     * (main thread on Paper, region thread on Folia).
     */
    private void processChunkAt(World w, int chunkX, int chunkZ) {
        // Region tasks scheduled before '/xmas off' may still arrive here after the stop.
        // Without this guard they would re-convert chunks the restore has just reset and
        // write a fresh (already snowy) snapshot that the restore never sees.
        if (!plugin.isActive() || restoreRunning.get()) return;

        ChunkKey key = new ChunkKey(w.getName(), chunkX, chunkZ);
        if (processedChunks.contains(key) || manualChunks.contains(key)) {
            return;
        }

        if (!w.isChunkLoaded(chunkX, chunkZ)) {
            // Not loaded yet - it is simply queued again the next time a player is near.
            // (Marking it as processed here used to leave permanent gaps behind fast players.)
            int retries = chunkRetryCount.merge(key, 1, Integer::sum);
            if (retries % MAX_CHUNK_RETRIES == 0) {
                plugin.verboseDebug("Chunk " + chunkX + "," + chunkZ + " still not loaded after " + retries + " attempts");
            }
            return;
        }

        // Excluded areas (config rectangles, WorldGuard regions, claims) are never touched
        if (plugin.getRegionIntegration() != null && plugin.getRegionIntegration().isBiomeExcluded(w, chunkX, chunkZ)) {
            processedChunks.add(key);
            chunkRetryCount.remove(key);
            plugin.verboseDebug("Chunk " + chunkX + "," + chunkZ + " excluded from biome changes");
            return;
        }

        Chunk chunk = w.getChunkAt(chunkX, chunkZ);
        Biome target = getTargetBiome();
        snapshotIfAbsent(w, chunk);
        if (applyUniformBiomeColumn(w, chunk, target)) {
            placeInstantSnow(w, chunk);
            refreshChunkSafe(w, chunk);
        }

        processedChunks.add(key);
        chunkRetryCount.remove(key);

        if (processedChunks.size() > MAX_PROCESSED_CACHE) {
            // Drop ~20% of the entries; evicted chunks are simply re-checked (cheap: 9 samples)
            Iterator<ChunkKey> it = processedChunks.iterator();
            int toRemove = MAX_PROCESSED_CACHE / 5;
            int removed = 0;
            while (it.hasNext() && removed < toRemove) {
                it.next();
                it.remove();
                removed++;
            }
        }

        if (chunkRetryCount.size() > 1000) {
            chunkRetryCount.entrySet().removeIf(entry -> entry.getValue() >= MAX_CHUNK_RETRIES);
        }
    }

    // ------------------------------------------------------------ instant snow

    /**
     * Covers the chunk surface with snow layers and freezes still water right away
     * ({@code biome.instantSnow.*}), instead of waiting for the storm to do it over
     * time. Restore removes exactly these blocks again where the original biome is
     * not snowy. Must run on the chunk's thread.
     */
    private void placeInstantSnow(World w, Chunk chunk) {
        if (!plugin.getConfig().getBoolean("biome.instantSnow.enabled", false)) return;
        double coverage = Math.max(0.0, Math.min(1.0, plugin.getConfig().getDouble("biome.instantSnow.coverage", 0.8)));
        boolean freezeWater = plugin.getConfig().getBoolean("biome.instantSnow.freezeWater", true);
        java.util.Random random = java.util.concurrent.ThreadLocalRandom.current();

        int bx = chunk.getX() << 4, bz = chunk.getZ() << 4;
        int placed = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                Block top = w.getHighestBlockAt(bx + x, bz + z); // highest non-air block
                int y = top.getY();
                if (y < minChangeY(w) || y >= maxChangeY(w)) continue;
                Material type = top.getType();

                if (type == Material.WATER) {
                    if (freezeWater && top.getBlockData() instanceof org.bukkit.block.data.Levelled lv && lv.getLevel() == 0) {
                        top.setType(Material.ICE, false);
                        placed++;
                    }
                    continue;
                }
                if (!type.isOccluding() || type == Material.ICE || type == Material.PACKED_ICE || type == Material.BLUE_ICE) continue;
                Block above = top.getRelative(0, 1, 0);
                if (!above.getType().isAir()) continue;
                if (random.nextDouble() > coverage) continue;
                above.setType(Material.SNOW, false);
                placed++;
            }
        }
        if (placed > 0) plugin.verboseDebug("Instant snow: " + placed + " blocks in chunk " + chunk.getX() + "," + chunk.getZ());
    }

    // ------------------------------------------------------------ convert-all

    private WrappedTask convertTask;
    private volatile int convertTotal, convertScheduled, convertDone;

    /** @return {@code true} while {@code /xmas biome convert-all} is running */
    public boolean isConvertRunning() {
        return convertTask != null;
    }

    /** @return "done/total" for status output */
    public String getConvertProgress() {
        return convertDone + "/" + convertTotal;
    }

    /**
     * Converts every generated chunk within {@code radiusChunks} of the world spawn,
     * budgeted by {@code biome.playerBubble.perTickBudget} per tick. Chunks are loaded
     * on their owning thread, snapshotted, converted and left to the server's normal
     * unloading. Used for maps and servers that want the whole world white.
     *
     * @return {@code false} if the event is inactive or a conversion is already running
     */
    public boolean startConvertAll(World w, int radiusChunks, java.util.function.Consumer<String> progress) {
        if (!plugin.isActive() || convertTask != null) return false;
        int r = Math.max(1, radiusChunks);
        Location spawn = w.getSpawnLocation();
        int cx0 = spawn.getBlockX() >> 4, cz0 = spawn.getBlockZ() >> 4;

        final java.util.List<int[]> coords = new java.util.ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                coords.add(new int[]{cx0 + dx, cz0 + dz});
            }
        }
        convertTotal = coords.size();
        convertScheduled = 0;
        convertDone = 0;
        final int budget = Math.max(1, plugin.getConfig().getInt("biome.playerBubble.perTickBudget", 12));
        final AtomicInteger done = new AtomicInteger();
        final long start = System.currentTimeMillis();

        convertTask = scheduler.runGlobalTaskTimer(() -> {
            int planned = 0;
            while (planned < budget && convertScheduled < convertTotal) {
                int[] c = coords.get(convertScheduled++);
                planned++;
                final int chunkX = c[0], chunkZ = c[1];
                scheduler.runAtLocation(FoliaSchedulerHelper.chunkCenter(w, chunkX, chunkZ), () -> {
                    try {
                        if (plugin.isActive() && w.isChunkGenerated(chunkX, chunkZ)) {
                            w.getChunkAt(chunkX, chunkZ); // synchronous load on the owning thread
                            processChunkAt(w, chunkX, chunkZ);
                        }
                    } catch (Exception e) {
                        lang.logWarning("log.biome.chunk-error", chunkX, chunkZ, e.getMessage());
                    } finally {
                        int d = done.incrementAndGet();
                        convertDone = d;
                        if (d % 200 == 0 || d == convertTotal) {
                            progress.accept(lang.getMessage("log.convert.progress", d, convertTotal));
                        }
                        if (d == convertTotal) {
                            progress.accept(lang.getMessage("log.convert.complete", d, (System.currentTimeMillis() - start) / 1000.0));
                            cancelConvertAll();
                        }
                    }
                });
            }
        }, 1L, 1L);
        return true;
    }

    /** Stops a running conversion (already scheduled chunks still finish). */
    public void cancelConvertAll() {
        if (convertTask != null) {
            convertTask.cancel();
            convertTask = null;
        }
    }

    // ------------------------------------------------------------ progress

    private volatile int restoreTotal, restoreFinished, restoreRestored, restoreErrors;

    /** @return "finished/total (errors)" of the running or last restore */
    public String getRestoreProgress() {
        int pct = restoreTotal == 0 ? 100 : (int) (100L * restoreFinished / restoreTotal);
        return restoreFinished + "/" + restoreTotal + " (" + pct + "%, " + restoreErrors + " errors)";
    }

    /** @return chunks currently stored in the snapshot database, -1 if closed */
    public int getSnapshotChunkCount() {
        BiomeSnapshotDatabase database = db;
        if (database == null || !database.isOpen()) return -1;
        try {
            return database.getChunkCount();
        } catch (SQLException e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- info

    /** Diagnostic snapshot of one column for {@code /xmas biome info}. */
    public static class ColumnInfo {
        public Biome current;
        public Biome original;          // null = no snapshot for this chunk
        public boolean hasSnapshot;
        public boolean excluded;
        public boolean processed;
        public boolean manual;
        public boolean originalNaturallySnowy, originalNaturallyIcy;
        public boolean currentNaturallySnowy, currentNaturallyIcy;
        public int minY, maxY;
    }

    /** Collects what the plugin knows about the column at {@code loc}; runs on the caller's thread. */
    public ColumnInfo inspect(Location loc) {
        ColumnInfo info = new ColumnInfo();
        World w = loc.getWorld();
        int cx = loc.getBlockX() >> 4, cz = loc.getBlockZ() >> 4;
        ChunkKey key = new ChunkKey(w.getName(), cx, cz);
        info.current = w.getBiome(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        info.currentNaturallySnowy = isNaturallySnowyBiome(info.current);
        info.currentNaturallyIcy = isNaturallyIcyBiome(info.current);
        info.excluded = plugin.getRegionIntegration() != null && plugin.getRegionIntegration().isBiomeExcluded(w, cx, cz);
        info.processed = processedChunks.contains(key);
        info.manual = manualChunks.contains(key);
        info.minY = minChangeY(w);
        info.maxY = maxChangeY(w);
        BiomeSnapshotDatabase database = db;
        if (database != null && database.isOpen()) {
            try {
                BiomeSnapshotDatabase.BiomeSnapshot3D snap = database.loadChunk3D(w.getName(), cx, cz);
                if (snap != null) {
                    info.hasSnapshot = true;
                    int y = Math.max(snap.yStart, Math.min(loc.getBlockY(), snap.getYEnd() - snap.yStep));
                    info.original = snap.getBiomeAtY(loc.getBlockX() & 15, loc.getBlockZ() & 15, y);
                    info.originalNaturallySnowy = isNaturallySnowyBiome(info.original);
                    info.originalNaturallyIcy = isNaturallyIcyBiome(info.original);
                }
            } catch (SQLException ignored) {}
        }
        return info;
    }

    // ---------------------------------------------------------------- helpers

    private Biome getTargetBiome() {
        return targetBiome;
    }

    /** @return {@code true} if the biome naturally contains ice (so restore must not remove it) */
    private boolean isNaturallyIcyBiome(Biome biome) {
        if (biome == null) return false;
        String name = biome.getKey().getKey().toLowerCase();
        return name.contains("frozen") ||
               name.contains("ice") ||
               name.contains("snowy") ||
               name.equals("grove") ||
               name.equals("jagged_peaks");
    }

    /** @return {@code true} if the biome naturally contains snow layers */
    private boolean isNaturallySnowyBiome(Biome biome) {
        if (biome == null) return false;
        String name = biome.getKey().getKey().toLowerCase();
        return name.contains("snowy") ||
               name.contains("frozen") ||
               name.contains("ice") ||
               name.equals("grove") ||
               name.equals("jagged_peaks") ||
               name.equals("frozen_peaks");
    }

    /** Lowest Y whose biome is changed (config, clamped to the world). */
    public int minChangeY(World w) {
        int v = plugin.getConfig().getInt("biome.changeMinY", DEFAULT_MIN_CHANGE_Y);
        return Math.max(w.getMinHeight(), Math.min(v, w.getMaxHeight() - CELL));
    }

    /** Exclusive upper Y bound of biome changes (config, clamped to the world). */
    public int maxChangeY(World w) {
        int v = plugin.getConfig().getInt("biome.changeMaxY", DEFAULT_MAX_CHANGE_Y);
        return Math.max(minChangeY(w) + CELL, Math.min(v, w.getMaxHeight()));
    }

    private int getVerticalStep() {
        // Biome resolution is 4 blocks; a step of 4 gives full coverage
        return Math.max(1, plugin.getConfig().getInt("biome.verticalStep", CELL));
    }

    /**
     * Blacklist of biomes that are never converted: Nether, End and cave biomes.
     * Works on the key only, so custom biomes containing these words are protected too.
     */
    private boolean isBiomeAllowedToChange(Biome biome) {
        if (biome == null) return false;
        String name = biome.getKey().getKey().toUpperCase();

        if (name.contains("NETHER")) return false;
        if (name.contains("CRIMSON")) return false;
        if (name.contains("WARPED")) return false;
        if (name.contains("BASALT")) return false;
        if (name.contains("SOUL")) return false;

        if (name.contains("END")) return false;

        if (name.contains("CAVE")) return false;
        if (name.contains("DEEP_DARK")) return false;

        return true;
    }

    /**
     * Stores the original biomes of a chunk (configured Y range)
     * unless a snapshot already exists. Samples one value per 4x4x4 cell.
     */
    private void snapshotIfAbsent(World w, Chunk c) {
        if (db == null) return;

        ChunkKey key = new ChunkKey(w.getName(), c.getX(), c.getZ());
        if (knownSnapshotChunks.contains(key)) return;

        try {
            if (db.hasChunk(key.world, key.x, key.z)) {
                knownSnapshotChunks.add(key);
                return;
            }

            plugin.verboseDebugLang("log.debug.snapshot.creating", key.x, key.z);

            int minY = minChangeY(w);
            int maxY = maxChangeY(w);
            int yStep = getVerticalStep();
            int yLayers = ((maxY - minY) / yStep) + 1; // inclusive of maxY
            Biome[][][] biomes3D = new Biome[yLayers][16][16];

            int bx = c.getX() << 4;
            int bz = c.getZ() << 4;
            int worldMaxY = w.getMaxHeight() - 1;

            for (int layer = 0; layer < yLayers; layer++) {
                int y = minY + (layer * yStep);
                if (y > worldMaxY) break;

                for (int x = 0; x < 16; x += CELL) {
                    for (int z = 0; z < 16; z += CELL) {
                        Biome biome = w.getBiome(bx + x, y, bz + z);
                        // Fill the whole cell (storage format is per block for compatibility)
                        for (int cx = 0; cx < CELL; cx++) {
                            for (int cz = 0; cz < CELL; cz++) {
                                biomes3D[layer][x + cx][z + cz] = biome;
                            }
                        }
                    }
                }
            }

            if (plugin.isVerboseDebugMode()) {
                Set<Biome> uniqueBiomes = new HashSet<>();
                for (Biome[][] layer : biomes3D) {
                    for (Biome[] row : layer) {
                        uniqueBiomes.addAll(Arrays.asList(row));
                    }
                }
                plugin.verboseDebugLang("log.debug.snapshot.biomes-found", uniqueBiomes);
                // A chunk that is already 100% target biome was probably converted before the
                // database was lost; the snapshot is still written (restoring SNOWY_PLAINS to
                // SNOWY_PLAINS beats not restoring at all)
                if (uniqueBiomes.size() == 1 && uniqueBiomes.contains(getTargetBiome())) {
                    plugin.verboseDebugLang("log.debug.snapshot.warning-snowy", key.x, key.z);
                }
            }

            // Remember columns that already carry a snow layer or ice on the surface: those
            // existed before the event (natural or player-placed) and are kept on restore
            byte[] snowMask = new byte[BiomeSnapshotDatabase.MASK_BYTES];
            byte[] iceMask = new byte[BiomeSnapshotDatabase.MASK_BYTES];
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    Block top = w.getHighestBlockAt(bx + x, bz + z, org.bukkit.HeightMap.WORLD_SURFACE);
                    Material t = top.getType();
                    if (t == Material.SNOW) {
                        BiomeSnapshotDatabase.BiomeSnapshot3D.setBit(snowMask, x, z);
                        t = top.getRelative(0, -1, 0).getType();
                    }
                    if (t == Material.ICE) BiomeSnapshotDatabase.BiomeSnapshot3D.setBit(iceMask, x, z);
                }
            }

            db.saveChunk3D(key.world, key.x, key.z, biomes3D, minY, yStep, snowMask, iceMask);
            knownSnapshotChunks.add(key);
            plugin.verboseDebugLang("log.debug.snapshot.saved", key.x, key.z, yLayers);

        } catch (SQLException e) {
            lang.logWarning("log.biome.error-saving-snapshot", e.getMessage());
            if (plugin.isDebugMode()) e.printStackTrace();
        }
    }

    /**
     * Sets the target biome on every allowed surface cell of the chunk.
     *
     * @return {@code true} if anything changed
     */
    private boolean applyUniformBiomeColumn(World world, Chunk chunk, Biome target) {
        boolean modified = false;
        int step = getVerticalStep();
        int bx = chunk.getX() << 4, bz = chunk.getZ() << 4;
        int minY = minChangeY(world);
        int maxY = maxChangeY(world);

        // Quick check with nine samples: corners, centre and edge midpoints
        int sampleY = Math.max(minY, Math.min(64, maxY - 1));
        int correctSamples = 0;
        int[][] samples = {{0,0},{15,0},{0,15},{15,15},{8,8},{0,8},{15,8},{8,0},{8,15}};
        for (int[] s : samples) {
            if (world.getBiome(bx + s[0], sampleY, bz + s[1]) == target) correctSamples++;
        }
        if (correctSamples == samples.length) {
            return false;
        }

        for (int x = 0; x < 16; x += CELL) {
            for (int z = 0; z < 16; z += CELL) {
                for (int y = minY; y < maxY; y += step) {
                    Biome currentBiome = world.getBiome(bx + x, y, bz + z);
                    if (currentBiome != target && isBiomeAllowedToChange(currentBiome)) {
                        world.setBiome(bx + x, y, bz + z, target);
                        modified = true;
                    }
                }
            }
        }
        return modified;
    }

    /** Resends the chunk to nearby clients so biome colours update immediately. */
    private void refreshChunkSafe(World w, Chunk c) {
        if (!plugin.getConfig().getBoolean("biome.playerBubble.refreshClient", true)) {
            return;
        }

        int centerX = (c.getX() << 4) + 8;
        int centerZ = (c.getZ() << 4) + 8;

        boolean hasNearbyPlayers = false;
        for (Player p : w.getPlayers()) {
            int px = p.getLocation().getBlockX();
            int pz = p.getLocation().getBlockZ();
            long distSq = (long) (px - centerX) * (px - centerX) + (long) (pz - centerZ) * (pz - centerZ);
            if (distSq < 160L * 160L) { // 10 chunks
                hasNearbyPlayers = true;
                break;
            }
        }
        if (!hasNearbyPlayers) return;

        try {
            w.refreshChunk(c.getX(), c.getZ());
        } catch (Throwable ignored) {}
    }

    /**
     * Manual override for problem spots ({@code /xmas biome set}). Chunks set this way
     * are excluded from the automatic bubble until the next stop/restore.
     *
     * @return number of chunks scheduled (not the number actually changed)
     */
    public int setBiomeAroundPlayer(Player p, Biome target, int radiusChunks) {
        if (!plugin.isActive()) {
            return -1; // caller reports "only while active"
        }

        World w = p.getWorld();
        if (!plugin.isSnowWorld(w)) return 0;

        Location loc = p.getLocation();
        int baseCX = loc.getBlockX() >> 4;
        int baseCZ = loc.getBlockZ() >> 4;

        int totalChunks = 0;
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                final int chunkX = baseCX + dx;
                final int chunkZ = baseCZ + dz;

                // Mark synchronously BEFORE the region tasks start, otherwise the bubble
                // timer could overwrite the manual change
                manualChunks.add(new ChunkKey(w.getName(), chunkX, chunkZ));

                scheduler.runAtLocation(FoliaSchedulerHelper.chunkCenter(w, chunkX, chunkZ), () -> {
                    if (!plugin.isActive() || restoreRunning.get()) return;
                    if (!w.isChunkLoaded(chunkX, chunkZ)) return;
                    Chunk chunk = w.getChunkAt(chunkX, chunkZ);
                    snapshotIfAbsent(w, chunk);
                    if (applyUniformBiomeColumn(w, chunk, target)) {
                        refreshChunkSafe(w, chunk);
                    }
                });
                totalChunks++;
            }
        }
        return totalChunks;
    }
}
