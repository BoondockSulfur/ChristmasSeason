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
 *   <li>Paper/Purpur: one global timer collects chunks around players into per-player
 *       pending sets every interval and works off {@code perTickBudget} chunks per
 *       interval, spread evenly over its ticks, nearest first, players in turn.</li>
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
    private volatile BiomeSnapshotDatabase db;

    public BiomeSnowManager(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.scheduler = plugin.getFoliaScheduler();
    }

    // ------------------------------------------------------------------ state

    private final Map<UUID, WrappedTask> playerBubbleTasks = new ConcurrentHashMap<>();
    private WrappedTask globalBubbleTask; // Paper/Purpur only

    /** Chunks seen around a player that are still waiting for their turn (survive fast movement). */
    private final Map<UUID, Set<ChunkKey>> playerPendingChunks = new ConcurrentHashMap<>();

    /** Paper/Purpur: rotating start index so every player gets chunks in turn. */
    private int paperPlayerTurn;

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

    /** Y layout {yStart, yStep, layers} of chunks known to have a snapshot (saves a query per visit). */
    private final Map<ChunkKey, int[]> knownLayouts = new ConcurrentHashMap<>();

    /** Neighbour chunks whose pre-event surface masks are already stored. */
    private final Set<ChunkKey> borderRecorded = ConcurrentHashMap.newKeySet();
    private static final int MAX_BORDER_CACHE = 20000;

    /** Retry counter for chunks that were not loaded when their turn came. */
    private final Map<ChunkKey, Integer> chunkRetryCount = new ConcurrentHashMap<>();
    private static final int MAX_CHUNK_RETRIES = 3;

    /** Guards against parallel restores and tells the completion check whether {@code db} is still ours. */
    private final AtomicBoolean restoreRunning = new AtomicBoolean(false);

    /** Resolved once per start; unknown names fall back to SNOWY_PLAINS with a single warning. */
    private volatile Biome targetBiome = Biome.SNOWY_PLAINS;

    /** The snapshot database should be available but could not be opened: nothing may be converted. */
    private volatile boolean snapshotUnavailable = false;

    /** Minimum time between two warnings about failed snapshot writes. */
    private static final long SNAPSHOT_WARNING_INTERVAL_MS = 30_000L;
    private volatile long lastSnapshotWarning = 0L;

    /** Asynchronous chunk loads kept in flight, as a multiple of the chunks handled per tick. */
    private static final int LOADS_PER_BUDGET = 8;

    /** Pending chunks further than this beyond the view distance move to the missed queue. */
    private static final int PENDING_RANGE_MARGIN = 2;

    /** Chunks a player left behind unconverted; they move to {@link #missedReady} once loaded. */
    private final Set<ChunkKey> missedChunks = Collections.synchronizedSet(new LinkedHashSet<>());
    /** Loaded missed chunks, converted with the budget the players leave unused. */
    private final Set<ChunkKey> missedReady = Collections.synchronizedSet(new LinkedHashSet<>());
    /** Upper bound of each missed queue; the oldest entries are dropped first. */
    private static final int MAX_MISSED = 20000;

    /** Chunks with a pending re-check after blocks above a cave biome were removed. */
    private final Set<ChunkKey> recheckScheduled = ConcurrentHashMap.newKeySet();
    private static final long RECHECK_DELAY_TICKS = 40L;
    /** Cave biome cells reaching this close below the surface are converted like surface cells. */
    private static final int CAVE_SURFACE_MARGIN = CELL;

    /** Multiplier of {@code perTickBudget} set by the adaptive budget (1 = config value). */
    private volatile int budgetMultiplier = 1;
    /** Ticks between two adaptive budget updates. */
    private static final int ADAPTIVE_INTERVAL_TICKS = 40;

    // -------------------------------------------------------------- lifecycle

    public void start() {
        stop();
        snapshotUnavailable = false;
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
                snapshotUnavailable = true;
                return;
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
     * @return {@code true} if chunks may be converted right now: event active, biome feature
     *         enabled, no restore running and, if snapshots are enabled, the database usable
     */
    public boolean isConversionAllowed() {
        return plugin.isActive()
                && !restoreRunning.get()
                && !snapshotUnavailable
                && plugin.getConfig().getBoolean("biome.enabled", true);
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
        clearMissed();
        budgetMultiplier = 1;

        processedChunks.clear();
        manualChunks.clear();
        knownLayouts.clear();
        borderRecorded.clear();
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

    /**
     * Paper/Purpur: one global timer, running every tick. Every {@code tickIntervalTicks}
     * it collects the chunks around each player; in between it works off
     * {@code perTickBudget} chunks per interval, spread evenly over the ticks, so the main
     * thread never converts a whole batch in a single tick.
     */
    private void startGlobalBubbleTask() {
        if (!plugin.getConfig().getBoolean("biome.playerBubble.enabled", true)) return;

        final int period = Math.max(5, plugin.getConfig().getInt("biome.playerBubble.tickIntervalTicks", 10));
        final int baseBudget = Math.max(1, plugin.getConfig().getInt("biome.playerBubble.perTickBudget", 8));
        final int[] tick = {0};
        final double[] credit = {0.0};

        globalBubbleTask = scheduler.runGlobalTaskTimer(() -> {
            if (tick[0] % ADAPTIVE_INTERVAL_TICKS == 0) updateBudgetMultiplier();
            final int budget = baseBudget * budgetMultiplier;
            final double creditPerTick = (double) budget / period;
            if (tick[0]++ % period == 0) {
                for (World w : plugin.getSnowWorlds()) {
                    for (Player p : w.getPlayers()) {
                        if (p.isOnline() && p.isValid()) {
                            collectAroundPlayer(p, w);
                        }
                    }
                }
            }

            credit[0] = Math.min(budget, credit[0] + creditPerTick);
            int allowed = (int) credit[0];
            if (allowed == 0) return;
            int processed = processPendingChunks(allowed);
            // Nothing left to do: do not save up credit for a burst later
            credit[0] = processed < allowed ? 0.0 : credit[0] - processed;
        }, 40L, 1L);
    }

    /**
     * Folia: starts the per-player biome timer on the player's entity scheduler.
     * No-op on Paper/Purpur (the global timer covers all players).
     */
    public void startPlayerTracking(Player player) {
        if (!isConversionAllowed()) return;
        if (!plugin.getConfig().getBoolean("biome.playerBubble.enabled", true)) return;
        if (!scheduler.isFolia()) return;

        UUID uuid = player.getUniqueId();
        WrappedTask oldTask = playerBubbleTasks.remove(uuid);
        if (oldTask != null) oldTask.cancel();

        int period = Math.max(5, plugin.getConfig().getInt("biome.playerBubble.tickIntervalTicks", 10));
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
        moveToMissed(playerPendingChunks.remove(player.getUniqueId()));
        WrappedTask task = playerBubbleTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
            plugin.debug("Biome tracking stopped for " + player.getName());
        }
    }

    /**
     * Called from {@code ChunkLoadEvent}. Only chunks near a player are added to that
     * player's pending set so that
     * pre-generation, spawn chunks and other plugins loading far-away chunks neither
     * bloat the snapshot database nor bypass the per-tick budget.
     *
     * <p>Chunks a player left behind unconverted move to the ready queue as soon as they
     * load again. Apart from that this is a no-op on Folia: the per-player timer already
     * covers the chunks around every player within one interval.
     */
    public void onChunkLoad(Chunk chunk) {
        if (!isConversionAllowed()) return;
        if (!plugin.getConfig().getBoolean("biome.playerBubble.enabled", true)) return;

        World w = chunk.getWorld();
        ChunkKey key = new ChunkKey(w.getName(), chunk.getX(), chunk.getZ());
        if (processedChunks.contains(key) || manualChunks.contains(key)) return;

        if (missedChunks.remove(key)) {
            addBounded(missedReady, key);
            return;
        }
        if (scheduler.isFolia()) return;

        int r = Math.max(0, plugin.getConfig().getInt("biome.playerBubble.radiusChunks", 2));
        for (Player p : w.getPlayers()) {
            Location loc = p.getLocation();
            if (Math.abs((loc.getBlockX() >> 4) - chunk.getX()) <= r
                    && Math.abs((loc.getBlockZ() >> 4) - chunk.getZ()) <= r) {
                playerPendingChunks.computeIfAbsent(p.getUniqueId(), k -> ConcurrentHashMap.newKeySet()).add(key);
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
     * Closes the database once all chunks have been processed. A closed database is opened
     * first, so snapshots left by a failed or interrupted restore are picked up again.
     *
     * @param perTick       number of chunks scheduled per tick
     * @param beforeRestore runs off the main thread before the first chunk changes (may be {@code null})
     */
    public void restoreALLAsync(int perTick, Runnable beforeRestore) {
        restoreALLAsync(perTick, beforeRestore, false);
    }

    /**
     * @param quietIfNothing {@code true} = no warnings when there is nothing to restore (retry after an
     *                       earlier, partly failed restore while the event is already off)
     */
    public void restoreALLAsync(int perTick, Runnable beforeRestore, boolean quietIfNothing) {
        // Two parallel restores would delete each other's chunks and close the database twice
        if (!restoreRunning.compareAndSet(false, true)) {
            lang.logWarning("log.biome.restore-already-running");
            return;
        }

        if (db == null) {
            db = openExistingDatabase();
            if (db == null) {
                restoreRunning.set(false);
                if (quietIfNothing) return;
                lang.logWarning("log.biome.restore-error-header");
                lang.logWarning("log.biome.no-snapshot-available");
                lang.logWarning("log.biome.snapshot-disabled-or-error");
                lang.logWarning("log.biome.solution-enable-snapshot");
                return;
            }
        }

        plugin.debug("Clearing caches before restore");
        processedChunks.clear();
        manualChunks.clear();
        knownLayouts.clear();
        borderRecorded.clear();
        playerPendingChunks.clear();
        clearMissed();
        chunkRetryCount.clear();

        final BiomeSnapshotDatabase database = db;

        try {
            scheduler.runAsync(() -> {
                try {
                    if (beforeRestore != null) beforeRestore.run();
                    List<BiomeSnapshotDatabase.ChunkCoords> allChunks = database.getAllChunkCoordinates();
                    Set<ChunkKey> restoreSet = new HashSet<>();
                    for (BiomeSnapshotDatabase.ChunkCoords c : allChunks) restoreSet.add(new ChunkKey(c.world, c.x, c.z));
                    scheduler.runGlobalTask(() -> runRestore(database, allChunks, restoreSet, perTick, quietIfNothing));
                } catch (SQLException | RuntimeException e) {
                    restoreNotStarted(e);
                }
            });
        } catch (RuntimeException e) {
            restoreNotStarted(e);
        }
    }

    private void restoreNotStarted(Exception e) {
        lang.logSevere("log.biome.error-retrieving-data", e.getMessage());
        e.printStackTrace();
        restoreRunning.set(false);
    }

    /** Works off the snapshot list in budgeted batches; runs on the global scheduler. */
    private void runRestore(BiomeSnapshotDatabase database, List<BiomeSnapshotDatabase.ChunkCoords> allChunks,
                            Set<ChunkKey> restoreSet, int perTick, boolean quietIfNothing) {
        final int totalChunks = allChunks.size();
        if (totalChunks == 0 && quietIfNothing) {
            database.close();
            if (db == database) db = null;
            restoreRunning.set(false);
            return;
        }
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
        // Incremented by the global timer, decremented by the chunk load callbacks
        final AtomicInteger loading = new AtomicInteger(0);
        final int maxLoading = budget * LOADS_PER_BUDGET;

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
            knownLayouts.clear();
            borderRecorded.clear();
            playerPendingChunks.clear();
            clearMissed();
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
        final java.util.function.BiConsumer<BiomeSnapshotDatabase.ChunkCoords, Exception> schedulingFailed = (failed, error) -> {
            lang.logWarning("log.biome.chunk-error", failed.x, failed.z, error.getMessage());
            errors.incrementAndGet();
            finished.incrementAndGet();
            completionCheck.run();
        };
        restoreTask[0] = scheduler.runGlobalTaskTimer(() -> {
            try {
                int plannedThisTick = 0;
                while (plannedThisTick < budget && scheduled[0] < totalChunks && loading.get() < maxLoading) {
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

                    // Every chunk runs on its own region (batching is not allowed on Folia)
                    final Runnable restoreOnOwner = () -> scheduler.runAtLocation(FoliaSchedulerHelper.chunkCenter(finalWorld, chunkX, chunkZ), () -> {
                        boolean success = false;
                        try {
                            Chunk chunk = finalWorld.getChunkAt(chunkX, chunkZ);
                            if (!chunk.isLoaded() && !finalWorld.loadChunk(chunkX, chunkZ, true)) {
                                lang.logWarning("log.biome.chunk-not-loaded-db", chunkX, chunkZ);
                            } else {
                                chunk = finalWorld.getChunkAt(chunkX, chunkZ);
                                restoreChunkBiomes3D(finalWorld, chunk, snapshot);
                                removeWinterBlocks3D(finalWorld, chunk, snapshot);
                                cleanNeighbourBorders(database, finalWorld, chunkX, chunkZ, restoreSet, snapshot);
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

                    // If scheduling itself throws (world unloading, plugin disabling), the
                    // chunk must still be counted
                    try {
                        if (finalWorld.isChunkLoaded(chunkX, chunkZ)) {
                            restoreOnOwner.run();
                        } else {
                            // Load off-thread first, so the restore never blocks the server on
                            // disk I/O. The task itself still runs on the owning thread.
                            loading.incrementAndGet();
                            finalWorld.getChunkAtAsync(chunkX, chunkZ, true).whenComplete((loaded, loadError) -> {
                                loading.decrementAndGet();
                                try {
                                    restoreOnOwner.run();
                                } catch (Exception schedulingError) {
                                    schedulingFailed.accept(coords, schedulingError);
                                }
                            });
                        }
                    } catch (Exception schedulingError) {
                        schedulingFailed.accept(coords, schedulingError);
                    }
                }

                if (plannedThisTick > 0 && scheduled[0] % 50 == 0 && scheduled[0] < totalChunks) {
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

    }

    /** @return the opened snapshot database, or {@code null} if there is none or it cannot be opened */
    private BiomeSnapshotDatabase openExistingDatabase() {
        if (!new java.io.File(plugin.getDataFolder(), "biome-snapshot.db").exists()) return null;
        BiomeSnapshotDatabase database = new BiomeSnapshotDatabase(plugin);
        try {
            database.open();
            return database;
        } catch (SQLException e) {
            lang.logSevere("log.biome.error-opening-database", e.getMessage());
            return null;
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
        // Border masks are taken when the first neighbour converts, i.e. earlier than the
        // chunk's own snapshot: a column only counts as snowy/icy before the event if both agree
        byte[][] earlier = borderMasks(db, world, chunk.getX(), chunk.getZ());
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
                boolean hadSnow = snapshot.hadSnow(x, z)
                        && (earlier == null || BiomeSnapshotDatabase.BiomeSnapshot3D.isBitSet(earlier[0], x, z));
                boolean hadIce = snapshot.hadIce(x, z)
                        && (earlier == null || BiomeSnapshotDatabase.BiomeSnapshot3D.isBitSet(earlier[1], x, z));
                cleanColumnTop(top, removeSnow && !isNaturallySnowyBiome(originalBiome) && !hadSnow,
                        removeIce && !isNaturallyIcyBiome(originalBiome) && !hadIce, removed);
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
     * limited to the restored chunk's Y range. Columns that carried snow/ice before the event
     * (border masks taken at conversion time) or got some from a player are kept. Neighbours
     * that are not loaded are skipped. Must run on the restored chunk's thread; the database
     * is read here, before the neighbour tasks run, because the restore may close it meanwhile.
     */
    private void cleanNeighbourBorders(BiomeSnapshotDatabase database, World world, int chunkX, int chunkZ,
                                       Set<ChunkKey> restoreSet, BiomeSnapshotDatabase.BiomeSnapshot3D snapshot) {
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
                final byte[] placed = placedMask(database, world, nx, nz);
                final byte[][] before = borderMasks(database, world, nx, nz);

                scheduler.runAtLocation(FoliaSchedulerHelper.chunkCenter(world, nx, nz), () -> {
                    if (!world.isChunkLoaded(nx, nz)) return;
                    if (plugin.getRegionIntegration() != null && plugin.getRegionIntegration().isBiomeExcluded(world, nx, nz)) return;
                    int bx = nx << 4, bz = nz << 4;
                    int[] removed = new int[2];
                    for (int x = fromX; x < toX; x++) {
                        for (int z = fromZ; z < toZ; z++) {
                            if (BiomeSnapshotDatabase.BiomeSnapshot3D.isBitSet(placed, x, z)) continue;
                            Block top = world.getHighestBlockAt(bx + x, bz + z, org.bukkit.HeightMap.WORLD_SURFACE);
                            if (top.getY() < minY || top.getY() > maxY) continue;
                            Biome current = world.getBiome(bx + x, top.getY(), bz + z);
                            boolean hadSnow = before != null && BiomeSnapshotDatabase.BiomeSnapshot3D.isBitSet(before[0], x, z);
                            boolean hadIce = before != null && BiomeSnapshotDatabase.BiomeSnapshot3D.isBitSet(before[1], x, z);
                            cleanColumnTop(top, removeSnow && !hadSnow && !isNaturallySnowyBiome(current),
                                    removeIce && !hadIce && !isNaturallyIcyBiome(current), removed);
                        }
                    }
                    if (plugin.isDebugMode() && (removed[0] > 0 || removed[1] > 0)) {
                        plugin.debug("Border of chunk " + nx + "," + nz + " (next to " + chunkX + "," + chunkZ + ", dx=" + fdx + " dz=" + fdz + "): removed " + removed[0] + " snow layers, " + removed[1] + " ice");
                    }
                });
            }
        }
    }

    /** @return pre-event {snow, ice} masks of an unconverted neighbour, or {@code null} if none were taken */
    private byte[][] borderMasks(BiomeSnapshotDatabase database, World world, int chunkX, int chunkZ) {
        if (database == null || !database.isOpen()) return null;
        try {
            return database.getBorderMasks(world.getName(), chunkX, chunkZ);
        } catch (SQLException e) {
            return null;
        }
    }

    /**
     * Stores the surface masks of the loaded, not yet converted neighbours of a chunk that is
     * converted for the first time, before its winter biome can spill snow over the border. Only
     * neighbours owned by the current thread are read (Folia regions). Existing records win.
     */
    private void recordNeighbourMasks(BiomeSnapshotDatabase database, World w, int chunkX, int chunkZ) {
        if (database == null || !database.isOpen()) return;
        if (borderRecorded.size() > MAX_BORDER_CACHE) borderRecorded.clear(); // the database ignores duplicates
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int nx = chunkX + dx, nz = chunkZ + dz;
                ChunkKey nk = new ChunkKey(w.getName(), nx, nz);
                if (borderRecorded.contains(nk) || knownLayouts.containsKey(nk)) continue;
                if (!w.isChunkLoaded(nx, nz) || !Bukkit.isOwnedByCurrentRegion(w, nx, nz)) continue;
                try {
                    if (!database.hasChunk(nk.world, nx, nz)) {
                        byte[][] masks = surfaceMasks(w, nx, nz);
                        // The chunk converted right now has not spilled anything yet
                        clearSpillStrips(database, w, nx, nz, new ChunkKey(w.getName(), chunkX, chunkZ), masks);
                        database.saveBorderMasks(nk.world, nx, nz, masks[0], masks[1]);
                    }
                    borderRecorded.add(nk);
                } catch (SQLException e) {
                    plugin.debug("Could not store border masks of " + nx + "," + nz + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Columns of a chunk that carry a snow layer / ice on the surface right now.
     * Must run on the chunk's thread.
     *
     * @return {snowMask, iceMask}, 32 bytes each
     */
    private byte[][] surfaceMasks(World w, int chunkX, int chunkZ) {
        byte[] snowMask = new byte[BiomeSnapshotDatabase.MASK_BYTES];
        byte[] iceMask = new byte[BiomeSnapshotDatabase.MASK_BYTES];
        int bx = chunkX << 4, bz = chunkZ << 4;
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
        return new byte[][]{snowMask, iceMask};
    }

    /**
     * Winter biomes spill snow up to a few blocks into neighbouring chunks (biome lookups
     * are blurred across cell borders). Snow or ice in the strip next to a neighbour that is
     * already converted may therefore come from the event, so it is not recorded as
     * pre-existing. {@code ignore} is a neighbour that must not count as converted yet.
     */
    private void clearSpillStrips(BiomeSnapshotDatabase database, World w, int chunkX, int chunkZ,
                                  ChunkKey ignore, byte[][] masks) {
        final int strip = 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                ChunkKey nk = new ChunkKey(w.getName(), chunkX + dx, chunkZ + dz);
                if (nk.equals(ignore) || !isConverted(database, nk)) continue;
                int fromX = dx < 0 ? 0 : (dx > 0 ? 16 - strip : 0), toX = dx < 0 ? strip : 16;
                int fromZ = dz < 0 ? 0 : (dz > 0 ? 16 - strip : 0), toZ = dz < 0 ? strip : 16;
                for (int x = fromX; x < toX; x++) {
                    for (int z = fromZ; z < toZ; z++) {
                        BiomeSnapshotDatabase.BiomeSnapshot3D.clearBit(masks[0], x, z);
                        BiomeSnapshotDatabase.BiomeSnapshot3D.clearBit(masks[1], x, z);
                    }
                }
            }
        }
    }

    private boolean isConverted(BiomeSnapshotDatabase database, ChunkKey key) {
        if (knownLayouts.containsKey(key)) return true;
        try {
            return database.hasChunk(key.world, key.x, key.z);
        } catch (SQLException e) {
            return true; // unknown: rather clean up a natural snow layer than keep event snow
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

    /**
     * Deletes all snapshots (database only, world untouched). A closed database is opened
     * for the call and closed again.
     *
     * @return {@code false} if the snapshots could not be deleted
     */
    public boolean clearSnapshot() {
        boolean temporary = db == null;
        BiomeSnapshotDatabase database = temporary ? openExistingDatabase() : db;
        if (database == null) {
            return !new java.io.File(plugin.getDataFolder(), "biome-snapshot.db").exists();
        }
        try {
            database.clearAll();
            lang.logInfo("log.biome.snapshot-deleted");
            return true;
        } catch (SQLException e) {
            lang.logSevere("log.biome.error-deleting-snapshot", e.getMessage());
            return false;
        } finally {
            if (temporary) database.close();
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
            moveToMissed(playerPendingChunks.remove(p.getUniqueId()));
            return;
        }

        int budget = Math.max(1, plugin.getConfig().getInt("biome.playerBubble.perTickBudget", 8));
        Location loc = p.getLocation();
        int baseCX = loc.getBlockX() >> 4;
        int baseCZ = loc.getBlockZ() >> 4;

        Set<ChunkKey> pending = collectAroundPlayer(p, w);
        final int keepRange = w.getViewDistance() + PENDING_RANGE_MARGIN;

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
                if (!w.isChunkLoaded(chunkX, chunkZ)) {
                    // Near the player it is still loading and gets another turn. Further away it
                    // waits in the missed queue until it is loaded again.
                    if (!plugin.isActive()) return;
                    boolean near = Math.max(Math.abs(chunkX - baseCX), Math.abs(chunkZ - baseCZ)) <= keepRange;
                    Set<ChunkKey> mine = playerPendingChunks.get(p.getUniqueId());
                    if (near && mine != null) mine.add(key);
                    else markMissed(w, key);
                    return;
                }
                processChunkAt(w, chunkX, chunkZ);
            });
            scheduled++;
        }

        // Budget left over: chunks other players left behind that are loaded again
        while (scheduled < budget) {
            ChunkKey key = pollMissedReady();
            if (key == null) break;
            World mw = Bukkit.getWorld(key.world);
            if (mw == null || !plugin.isSnowWorld(mw) || processedChunks.contains(key) || manualChunks.contains(key)) continue;
            scheduler.runAtLocation(FoliaSchedulerHelper.chunkCenter(mw, key.x, key.z), () -> {
                if (!mw.isChunkLoaded(key.x, key.z)) {
                    if (plugin.isActive()) markMissed(mw, key);
                    return;
                }
                processChunkAt(mw, key.x, key.z);
            });
            scheduled++;
        }
    }

    /**
     * Adds the unprocessed chunks in the radius around a player to their pending set.
     * A set, so revisiting the same area never queues a chunk twice.
     *
     * @return the player's pending set
     */
    private Set<ChunkKey> collectAroundPlayer(Player p, World w) {
        int r = Math.max(0, plugin.getConfig().getInt("biome.playerBubble.radiusChunks", 2));
        Location loc = p.getLocation();
        int baseCX = loc.getBlockX() >> 4;
        int baseCZ = loc.getBlockZ() >> 4;

        Set<ChunkKey> pending = playerPendingChunks.computeIfAbsent(p.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        // Moving players add chunks faster than the budget converts them. Whatever they left
        // behind goes to the missed queue and is converted once it is loaded and budget is free.
        final int keepRange = w.getViewDistance() + PENDING_RANGE_MARGIN;
        pending.removeIf(k -> {
            boolean drop = !k.world.equals(w.getName())
                    || Math.max(Math.abs(k.x - baseCX), Math.abs(k.z - baseCZ)) > keepRange;
            if (drop) markMissed(Bukkit.getWorld(k.world), k);
            return drop;
        });

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkKey key = new ChunkKey(w.getName(), baseCX + dx, baseCZ + dz);
                if (!processedChunks.contains(key) && !manualChunks.contains(key)) {
                    pending.add(key);
                }
            }
        }
        return pending;
    }

    /**
     * Paper/Purpur: converts up to {@code allowed} pending chunks on the main thread.
     * Players take turns, each turn takes the chunk nearest to that player. Chunks that
     * are no longer loaded go to the missed queue; budget the players leave unused works
     * off missed chunks that are loaded again.
     *
     * @return number of chunks actually converted
     */
    private int processPendingChunks(int allowed) {
        List<Player> players = new ArrayList<>();
        for (World w : plugin.getSnowWorlds()) players.addAll(w.getPlayers());

        int processed = 0;
        int emptyTurns = 0;
        while (processed < allowed && emptyTurns < players.size()) {
            Player p = players.get(Math.floorMod(paperPlayerTurn++, players.size()));
            ChunkKey key = pollNearest(p);
            if (key == null) {
                emptyTurns++;
                continue;
            }
            emptyTurns = 0;

            World w = p.getWorld();
            if (processedChunks.contains(key) || manualChunks.contains(key)) continue;
            if (!key.world.equals(w.getName()) || !w.isChunkLoaded(key.x, key.z)) {
                markMissed(Bukkit.getWorld(key.world), key);
                continue;
            }
            processChunkAt(w, key.x, key.z);
            processed++;
        }

        while (processed < allowed) {
            ChunkKey key = pollMissedReady();
            if (key == null) break;
            World w = Bukkit.getWorld(key.world);
            if (w == null || !plugin.isSnowWorld(w) || processedChunks.contains(key) || manualChunks.contains(key)) continue;
            if (!w.isChunkLoaded(key.x, key.z)) {
                markMissed(w, key);
                continue;
            }
            processChunkAt(w, key.x, key.z);
            processed++;
        }
        return processed;
    }

    // ------------------------------------------------------------ missed chunks

    /**
     * Remembers a chunk that left a pending set unconverted: loaded chunks go straight to
     * the ready queue, unloaded ones wait for {@link #onChunkLoad}.
     */
    private void markMissed(World w, ChunkKey key) {
        if (w == null || !plugin.isSnowWorld(w)) return;
        if (processedChunks.contains(key) || manualChunks.contains(key)) return;
        if (w.isChunkLoaded(key.x, key.z)) {
            missedChunks.remove(key);
            addBounded(missedReady, key);
        } else {
            addBounded(missedChunks, key);
        }
    }

    private void moveToMissed(Set<ChunkKey> keys) {
        if (keys == null || !plugin.isActive()) return;
        for (ChunkKey k : keys) markMissed(Bukkit.getWorld(k.world), k);
    }

    private static void addBounded(Set<ChunkKey> queue, ChunkKey key) {
        synchronized (queue) {
            if (!queue.contains(key) && queue.size() >= MAX_MISSED) {
                Iterator<ChunkKey> it = queue.iterator();
                it.next();
                it.remove();
            }
            queue.add(key);
        }
    }

    /** Removes and returns the oldest loaded missed chunk, or {@code null}. */
    private ChunkKey pollMissedReady() {
        synchronized (missedReady) {
            Iterator<ChunkKey> it = missedReady.iterator();
            if (!it.hasNext()) return null;
            ChunkKey key = it.next();
            it.remove();
            return key;
        }
    }

    private void clearMissed() {
        missedChunks.clear();
        missedReady.clear();
        recheckScheduled.clear();
    }

    // ------------------------------------------------------------ adaptive budget

    /**
     * {@code biome.playerBubble.adaptiveBudget}: raises the budget multiplier step by step
     * while the average tick time stays well below the target and falls back to the config
     * value as soon as it exceeds the target. Paper/Purpur only; Folia has no global tick
     * time and keeps the configured budget.
     */
    private void updateBudgetMultiplier() {
        if (!plugin.getConfig().getBoolean("biome.playerBubble.adaptiveBudget.enabled", false)) {
            budgetMultiplier = 1;
            return;
        }
        int max = Math.max(1, Math.min(10, plugin.getConfig().getInt("biome.playerBubble.adaptiveBudget.maxMultiplier", 3)));
        double target = Math.max(5.0, plugin.getConfig().getDouble("biome.playerBubble.adaptiveBudget.targetMspt", 30.0));
        double mspt;
        try {
            mspt = Bukkit.getAverageTickTime();
        } catch (Throwable t) {
            budgetMultiplier = 1;
            return;
        }
        int current = Math.min(budgetMultiplier, max);
        if (mspt > target) current = 1;
        else if (mspt < target * 0.75 && current < max) current++;
        if (current != budgetMultiplier) {
            plugin.verboseDebug("Adaptive budget: x" + current + " (mspt " + String.format(Locale.ROOT, "%.1f", mspt) + ")");
        }
        budgetMultiplier = current;
    }

    /** Removes and returns the pending chunk nearest to the player, or {@code null} if none is left. */
    private ChunkKey pollNearest(Player p) {
        Set<ChunkKey> pending = playerPendingChunks.get(p.getUniqueId());
        if (pending == null || pending.isEmpty()) return null;

        Location loc = p.getLocation();
        int baseCX = loc.getBlockX() >> 4;
        int baseCZ = loc.getBlockZ() >> 4;
        ChunkKey nearest = null;
        int best = Integer.MAX_VALUE;
        for (ChunkKey k : pending) {
            int d = (k.x - baseCX) * (k.x - baseCX) + (k.z - baseCZ) * (k.z - baseCZ);
            if (d < best) {
                best = d;
                nearest = k;
            }
        }
        if (nearest != null) pending.remove(nearest);
        return nearest;
    }

    /**
     * Snapshots and converts one chunk. Must run on the thread owning the chunk
     * (main thread on Paper, region thread on Folia).
     */
    private void processChunkAt(World w, int chunkX, int chunkZ) {
        // Region tasks scheduled before '/xmas off' may still arrive here after the stop.
        // Without this guard they would re-convert chunks the restore has just reset and
        // write a fresh (already snowy) snapshot that the restore never sees.
        if (!isConversionAllowed()) return;

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
        // Without a snapshot the original biome would be lost; the chunk stays unprocessed and
        // is tried again the next time it is collected
        int[] layout = snapshotIfAbsent(w, chunk);
        if (layout == null) return;
        int[] range = applicableRange(w, layout);
        if (range != null && applyUniformBiomeColumn(w, chunk, target, layout, range, true)) {
            placeInstantSnow(w, chunk, range);
            refreshChunkSafe(w, chunk);
        }

        processedChunks.add(key);
        chunkRetryCount.remove(key);
        missedChunks.remove(key);
        missedReady.remove(key);

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
    private void placeInstantSnow(World w, Chunk chunk, int[] range) {
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
                // Only inside the range the snapshot covers, so the restore finds these blocks again
                if (y < range[0] || y >= range[1]) continue;
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

    /** Largest accepted {@code convert-all} radius (2001 x 2001 chunks). */
    public static final int MAX_CONVERT_RADIUS = 1000;

    private volatile WrappedTask convertTask;
    private volatile int convertTotal, convertScheduled, convertDone;
    /** Identifies the current conversion; chunks of a cancelled run finishing late are ignored. */
    private volatile long convertRunId;

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
     * {@code biome.convertAll.perTick} chunks per tick. Chunks are loaded off-thread,
     * then snapshotted and converted on their owning thread and left to the server's
     * normal unloading. Used for maps and servers that want the whole world white.
     *
     * @return {@code false} if the event is inactive or a conversion is already running
     */
    public boolean startConvertAll(World w, int radiusChunks, java.util.function.Consumer<String> progress) {
        if (!isConversionAllowed() || convertTask != null) return false;
        final int r = Math.max(1, Math.min(MAX_CONVERT_RADIUS, radiusChunks));
        Location spawn = w.getSpawnLocation();
        final int cx0 = spawn.getBlockX() >> 4, cz0 = spawn.getBlockZ() >> 4;
        // Coordinates are derived from the index: no list of millions of entries on the main thread
        final int side = 2 * r + 1;
        final int total = side * side;
        final long run = ++convertRunId;
        convertTotal = total;
        convertScheduled = 0;
        convertDone = 0;
        final int budget = Math.max(1, plugin.getConfig().getInt("biome.convertAll.perTick", 2));
        final int maxLoading = budget * LOADS_PER_BUDGET;
        final AtomicInteger loading = new AtomicInteger();
        final AtomicInteger done = new AtomicInteger();
        final long start = System.currentTimeMillis();

        final Runnable chunkFinished = () -> {
            int d = done.incrementAndGet();
            if (run != convertRunId) return;
            convertDone = d;
            if (d % 200 == 0 || d == total) {
                progress.accept(lang.getMessage("log.convert.progress", d, total));
            }
            if (d == total) {
                progress.accept(lang.getMessage("log.convert.complete", d, (System.currentTimeMillis() - start) / 1000.0));
                cancelConvertAll();
            }
        };

        convertTask = scheduler.runGlobalTaskTimer(() -> {
            int planned = 0;
            while (planned < budget && convertScheduled < total && loading.get() < maxLoading) {
                int index = convertScheduled++;
                planned++;
                final int chunkX = cx0 + index / side - r, chunkZ = cz0 + index % side - r;
                final Runnable convertOnOwner = () -> scheduler.runAtLocation(FoliaSchedulerHelper.chunkCenter(w, chunkX, chunkZ), () -> {
                    try {
                        processChunkAt(w, chunkX, chunkZ);
                    } catch (Exception e) {
                        lang.logWarning("log.biome.chunk-error", chunkX, chunkZ, e.getMessage());
                    } finally {
                        chunkFinished.run();
                    }
                });

                if (w.isChunkLoaded(chunkX, chunkZ)) {
                    convertOnOwner.run();
                } else {
                    // Ungenerated chunks complete with null and are skipped
                    loading.incrementAndGet();
                    w.getChunkAtAsync(chunkX, chunkZ, false).whenComplete((chunk, loadError) -> {
                        loading.decrementAndGet();
                        if (chunk == null) chunkFinished.run();
                        else convertOnOwner.run();
                    });
                }
            }
        }, 1L, 1L);
        return true;
    }

    /** Stops a running conversion (already scheduled chunks still finish). */
    public void cancelConvertAll() {
        convertRunId++;
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
            } catch (SQLException | RuntimeException e) {
                // Unreadable snapshot: reported as "no snapshot", details only in debug mode
                plugin.debug("Snapshot of chunk " + cx + "," + cz + " could not be read: " + e.getMessage());
            }
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
        // Stored as one byte in the snapshot header; more than 64 would skip most cells anyway
        return Math.max(1, Math.min(64, plugin.getConfig().getInt("biome.verticalStep", CELL)));
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

    /** @return Y layout {yStart, yStep, layers} the current config would snapshot for this world */
    private int[] configLayout(World w) {
        int minY = minChangeY(w);
        int maxY = maxChangeY(w);
        int yStep = getVerticalStep();
        int worldMaxY = w.getMaxHeight() - 1;
        // One layer per step, the last one at or below maxY and never above the world
        int yLayers = (Math.min(maxY, worldMaxY) - minY) / yStep + 1;
        return new int[]{minY, yStep, Math.max(1, yLayers)};
    }

    /**
     * Y range that may be converted in a chunk: the configured range, limited to the layers
     * the chunk's snapshot covers. A config change during the event therefore never converts
     * cells the restore could not reset.
     *
     * @return {fromY, toYExclusive}, or {@code null} if the ranges do not overlap
     */
    private int[] applicableRange(World w, int[] layout) {
        int lo = Math.max(layout[0], minChangeY(w));
        int hi = Math.min(layout[0] + (layout[2] - 1) * layout[1] + 1, maxChangeY(w));
        return lo < hi ? new int[]{lo, hi} : null;
    }

    /**
     * Stores the original biomes of a chunk (configured Y range) unless a snapshot already
     * exists. Samples one value per 4x4x4 cell.
     *
     * @return the Y layout {yStart, yStep, layers} of the chunk's snapshot (the existing one if
     *         there is one), the config layout when snapshots are disabled, or {@code null} if
     *         no snapshot could be stored (the chunk must not be converted then)
     */
    private int[] snapshotIfAbsent(World w, Chunk c) {
        BiomeSnapshotDatabase database = db;
        if (database == null) {
            return plugin.getConfig().getBoolean("biome.enableSnapshot", true) ? null : configLayout(w);
        }
        if (!database.isOpen()) return null;

        ChunkKey key = new ChunkKey(w.getName(), c.getX(), c.getZ());
        int[] known = knownLayouts.get(key);
        if (known != null) return known;

        try {
            int[] stored = database.loadChunkLayout(key.world, key.x, key.z);
            if (stored != null) {
                knownLayouts.put(key, stored);
                return stored;
            }

            plugin.verboseDebugLang("log.debug.snapshot.creating", key.x, key.z);

            int[] layout = configLayout(w);
            int minY = layout[0], yStep = layout[1], yLayers = layout[2];
            Biome[][][] biomes3D = new Biome[yLayers][16][16];

            int bx = c.getX() << 4;
            int bz = c.getZ() << 4;

            for (int layer = 0; layer < yLayers; layer++) {
                int y = minY + (layer * yStep);
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
            byte[][] masks = surfaceMasks(w, c.getX(), c.getZ());
            clearSpillStrips(database, w, c.getX(), c.getZ(), null, masks);

            database.saveChunk3D(key.world, key.x, key.z, biomes3D, minY, yStep, masks[0], masks[1]);
            knownLayouts.put(key, layout);
            // Only on the first conversion: later visits could already see snow that spilled over
            recordNeighbourMasks(database, w, key.x, key.z);
            plugin.verboseDebugLang("log.debug.snapshot.saved", key.x, key.z, yLayers);
            return layout;

        } catch (SQLException e) {
            long now = System.currentTimeMillis();
            if (now - lastSnapshotWarning >= SNAPSHOT_WARNING_INTERVAL_MS) {
                lastSnapshotWarning = now;
                lang.logWarning("log.biome.error-saving-snapshot", e.getMessage());
            }
            if (plugin.isDebugMode()) e.printStackTrace();
            return null;
        }
    }

    /**
     * Sets the target biome on every allowed surface cell of the chunk, on the snapshot's
     * layers inside {@code range}. Cave biomes are only changed where they reach the
     * surface (see {@link #caveFloorY}).
     *
     * @param quickCheck skip the chunk when nine surface samples already show the target biome
     * @return {@code true} if anything changed
     */
    private boolean applyUniformBiomeColumn(World world, Chunk chunk, Biome target, int[] layout, int[] range, boolean quickCheck) {
        boolean modified = false;
        int bx = chunk.getX() << 4, bz = chunk.getZ() << 4;
        int yStart = layout[0], step = layout[1], layers = layout[2];

        if (quickCheck) {
            // Nine samples at the surface: corners, centre and edge midpoints
            int correctSamples = 0;
            int[][] samples = {{0,0},{15,0},{0,15},{15,15},{8,8},{0,8},{15,8},{8,0},{8,15}};
            for (int[] s : samples) {
                int surface = world.getHighestBlockYAt(bx + s[0], bz + s[1], org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
                int sampleY = Math.max(range[0], Math.min(surface, range[1] - 1));
                if (world.getBiome(bx + s[0], sampleY, bz + s[1]) == target) correctSamples++;
            }
            if (correctSamples == samples.length) {
                return false;
            }
        }

        for (int x = 0; x < 16; x += CELL) {
            for (int z = 0; z < 16; z += CELL) {
                int caveFloor = Integer.MIN_VALUE; // computed on the first cave cell of this column
                for (int layer = 0; layer < layers; layer++) {
                    int y = yStart + layer * step;
                    if (y < range[0] || y >= range[1]) continue;
                    Biome currentBiome = world.getBiome(bx + x, y, bz + z);
                    if (currentBiome == target) continue;
                    boolean allowed;
                    if (isCaveBiome(currentBiome)) {
                        if (caveFloor == Integer.MIN_VALUE) caveFloor = caveFloorY(world, bx + x, bz + z);
                        allowed = (y & ~(CELL - 1)) + CELL - 1 >= caveFloor;
                    } else {
                        allowed = isBiomeAllowedToChange(currentBiome);
                    }
                    if (allowed) {
                        world.setBiome(bx + x, y, bz + z, target);
                        modified = true;
                    }
                }
            }
        }
        return modified;
    }

    /**
     * Lowest Y a cave biome cell must reach to count as surface: the lowest terrain surface
     * (leaves ignored) of the cell's 4x4 columns minus {@link #CAVE_SURFACE_MARGIN}. A pit or
     * a removed hill therefore lowers it.
     */
    private int caveFloorY(World world, int cellX, int cellZ) {
        int lowest = Integer.MAX_VALUE;
        for (int dx = 0; dx < CELL; dx++) {
            for (int dz = 0; dz < CELL; dz++) {
                lowest = Math.min(lowest, world.getHighestBlockYAt(cellX + dx, cellZ + dz, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES));
            }
        }
        return lowest - CAVE_SURFACE_MARGIN;
    }

    /** @return {@code true} for cave biomes (dripstone, lush caves, deep dark and custom ones) */
    private static boolean isCaveBiome(Biome biome) {
        if (biome == null) return false;
        String name = biome.getKey().getKey().toUpperCase(Locale.ROOT);
        return name.contains("CAVE") || name.contains("DEEP_DARK");
    }

    /**
     * Called when players break or blow up blocks: if a cave biome sits right there, the chunk
     * is checked again a moment later, so a cave biome that is now open to the sky turns into
     * the winter biome too. Edits by WorldEdit or Axiom fire no such event and are not seen.
     */
    public void requestSurfaceRecheck(Block block) {
        if (!isConversionAllowed()) return;
        World w = block.getWorld();
        int y = block.getY();
        if (y < minChangeY(w) - CELL || y >= maxChangeY(w)) return;
        if (!isCaveBiome(w.getBiome(block.getX(), y, block.getZ()))
                && !isCaveBiome(w.getBiome(block.getX(), y - CELL, block.getZ()))) return;

        ChunkKey key = new ChunkKey(w.getName(), block.getX() >> 4, block.getZ() >> 4);
        if (manualChunks.contains(key) || !recheckScheduled.add(key)) return;
        scheduler.runAtLocationLater(FoliaSchedulerHelper.chunkCenter(w, key.x, key.z), () -> {
            recheckScheduled.remove(key);
            recheckChunk(w, key);
        }, RECHECK_DELAY_TICKS);
    }

    /** Re-applies the winter biome to an already converted chunk (no quick check). */
    private void recheckChunk(World w, ChunkKey key) {
        if (!isConversionAllowed() || manualChunks.contains(key) || !w.isChunkLoaded(key.x, key.z)) return;
        if (plugin.getRegionIntegration() != null && plugin.getRegionIntegration().isBiomeExcluded(w, key.x, key.z)) return;

        int[] layout = knownLayouts.get(key);
        if (layout == null) {
            BiomeSnapshotDatabase database = db;
            if (database == null) {
                if (plugin.getConfig().getBoolean("biome.enableSnapshot", true)) return;
                layout = configLayout(w);
            } else {
                if (!database.isOpen()) return;
                try {
                    layout = database.loadChunkLayout(key.world, key.x, key.z);
                } catch (SQLException e) {
                    return;
                }
                // Not converted yet: the bubble takes care of it with a fresh snapshot
                if (layout == null) return;
                knownLayouts.put(key, layout);
            }
        }
        int[] range = applicableRange(w, layout);
        if (range == null) return;
        Chunk chunk = w.getChunkAt(key.x, key.z);
        if (applyUniformBiomeColumn(w, chunk, getTargetBiome(), layout, range, false)) {
            plugin.verboseDebug("Chunk " + key.x + "," + key.z + " re-checked after digging: cave biome converted");
            refreshChunkSafe(w, chunk);
        }
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

    /** Largest accepted {@code /xmas biome set} radius (65 x 65 chunks). */
    public static final int MAX_SET_RADIUS = 32;

    /**
     * Manual override for problem spots ({@code /xmas biome set}). Chunks set this way
     * are excluded from the automatic bubble until the next stop/restore. Excluded areas
     * ({@code biome.exclude}) are skipped like everywhere else.
     *
     * @return number of chunks scheduled (not the number actually changed)
     */
    public int setBiomeAroundPlayer(Player p, Biome target, int radiusChunks) {
        if (!isConversionAllowed()) {
            return -1; // caller has reported the reason already, or reports "only while active"
        }

        World w = p.getWorld();
        if (!plugin.isSnowWorld(w)) return 0;

        Location loc = p.getLocation();
        int baseCX = loc.getBlockX() >> 4;
        int baseCZ = loc.getBlockZ() >> 4;
        int radius = Math.max(0, Math.min(MAX_SET_RADIUS, radiusChunks));

        int totalChunks = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                final int chunkX = baseCX + dx;
                final int chunkZ = baseCZ + dz;
                if (plugin.getRegionIntegration() != null && plugin.getRegionIntegration().isBiomeExcluded(w, chunkX, chunkZ)) {
                    continue;
                }

                // Mark synchronously BEFORE the region tasks start, otherwise the bubble
                // timer could overwrite the manual change
                manualChunks.add(new ChunkKey(w.getName(), chunkX, chunkZ));

                scheduler.runAtLocation(FoliaSchedulerHelper.chunkCenter(w, chunkX, chunkZ), () -> {
                    if (!isConversionAllowed()) return;
                    if (!w.isChunkLoaded(chunkX, chunkZ)) return;
                    Chunk chunk = w.getChunkAt(chunkX, chunkZ);
                    int[] layout = snapshotIfAbsent(w, chunk);
                    if (layout == null) return;
                    int[] range = applicableRange(w, layout);
                    if (range != null && applyUniformBiomeColumn(w, chunk, target, layout, range, true)) {
                        refreshChunkSafe(w, chunk);
                    }
                });
                totalChunks++;
            }
        }
        return totalChunks;
    }
}
