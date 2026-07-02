package de.boondocksulfur.christmas.manager;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.util.FoliaSchedulerHelper;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Setzt Biome winterlich (Event ON), SQLite-Snapshot für OFF-Restore,
 * optionales Seed-Reset, sanfte Player-Bubble.
 *
 * v1.3.1: Komplett überarbeitet mit SQLite-Datenbank:
 * - Unbegrenzte Chunk-Anzahl (kein 2000-Limit mehr!)
 * - Komprimierte Speicherung (~5-10 MB statt 156 MB für 10k Chunks)
 * - Schnelles Laden/Speichern (Millisekunden)
 * - Kein Memory-Overhead
 */
public class BiomeSnowManager {

    private final ChristmasSeason plugin;
    private final FoliaSchedulerHelper scheduler;
    private BiomeSnapshotDatabase db;

    public BiomeSnowManager(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.scheduler = plugin.getFoliaScheduler();
    }

    // ---------- Scheduler ----------
    // MULTI-PLATFORM: Unterschiedliche Scheduler-Strategien
    // FOLIA: Player-basierte Entity Scheduler (wegen regionalisiertem Threading)
    // PAPER/SPIGOT/PURPUR: Globaler Timer (wie v1.4.1 - bewährte Performance!)
    private final Map<java.util.UUID, WrappedTask> playerBubbleTasks = new ConcurrentHashMap<>();
    private WrappedTask globalBubbleTask; // Für Paper/Spigot/Purpur

    // PERFORMANCE FIX: Chunk-Queue für verteilte Verarbeitung (verhindert TPS-Spikes)
    private final java.util.Queue<ChunkCoords> chunkProcessQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private static class ChunkCoords {
        final World world;
        final int x, z;
        ChunkCoords(World w, int x, int z) { this.world = w; this.x = x; this.z = z; }
    }

    // ---------- Snapshot ----------
    private static final class ChunkKey {
        final String world; final int x; final int z;
        ChunkKey(String w, int x, int z){ this.world=w; this.x=x; this.z=z; }
        @Override public boolean equals(Object o){ if(this==o) return true; if(!(o instanceof ChunkKey k)) return false; return x==k.x && z==k.z && Objects.equals(world,k.world); }
        @Override public int hashCode(){ return Objects.hash(world,x,z); }
    }

    // PERFORMANCE: Cache für bereits verarbeitete Chunks - verhindert Re-Processing!
    private final Set<ChunkKey> processedChunks = ConcurrentHashMap.newKeySet();
    private static final int MAX_PROCESSED_CACHE = 5000; // Max Cache-Größe

    // RETRY MECHANISM: Tracking für nicht-geladene Chunks (versuche sie später nochmal)
    private final java.util.Map<ChunkKey, Integer> chunkRetryCount = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_CHUNK_RETRIES = 3; // Nach 3 Versuchen aufgeben

    // ===================== Lifecycle ======================
    public void start() {
        stop();
        if (!plugin.isActive()) return;
        if (!plugin.getConfig().getBoolean("biome.enabled", true)) return;

        plugin.debug("BiomeSnowManager.start() - Starte System...");

        // SQLite-Datenbank öffnen
        if (plugin.getConfig().getBoolean("biome.enableSnapshot", true)) {
            db = new BiomeSnapshotDatabase(plugin);
            try {
                db.open();
                plugin.getLogger().info(plugin.getLanguageManager().get("log.biome.database-ready"));
                plugin.debug("Datenbank geöffnet: " + db.getDatabaseSize() + " bytes, " + db.getChunkCount() + " chunks");
            } catch (SQLException e) {
                plugin.getLogger().severe(plugin.getLanguageManager().getMessage("log.biome.error-opening-database", e.getMessage()));
                plugin.getLogger().severe(plugin.getLanguageManager().get("log.biome.snapshot-system-disabled"));
                e.printStackTrace();
                db = null;
            }
        } else {
            plugin.getLogger().info(plugin.getLanguageManager().get("log.biome.system-disabled-config"));
            plugin.debug("enableSnapshot = false in config.yml");
            db = null;
        }

        // MULTI-PLATFORM: Unterschiedliche Scheduler-Strategien
        if (scheduler.isFolia()) {
            // FOLIA: Player-basierte Scheduler (werden bei Player Join gestartet)
            plugin.debug("BiomeSnowManager bereit (Folia: Player-basierte Scheduler)");
        } else {
            // PAPER/SPIGOT/PURPUR: Globaler Timer (wie v1.4.1)
            startGlobalBubbleTask();
            plugin.debug("BiomeSnowManager bereit (Paper: Globaler Timer)");
        }
    }

    public void stop() {
        stop(true);
    }

    /** Stop mit optionalem DB-Close (für xmas off brauchen wir die DB noch!) */
    public void stop(boolean closeDatabase) {
        plugin.debug("BiomeSnowManager.stop(closeDatabase=" + closeDatabase + ")");

        // MULTI-PLATFORM: Stoppe beide Scheduler-Typen
        // Stoppe globalen Timer (Paper/Spigot/Purpur)
        if (globalBubbleTask != null) {
            globalBubbleTask.cancel();
            globalBubbleTask = null;
        }

        // Stoppe Player-basierte Tasks (Folia)
        for (WrappedTask task : playerBubbleTasks.values()) {
            if (task != null) task.cancel();
        }
        playerBubbleTasks.clear();

        chunkProcessQueue.clear(); // Queue leeren
        processedChunks.clear(); // Cache leeren
        knownSnapshotChunks.clear(); // PERFORMANCE FIX: DB-Lookup-Cache leeren
        chunkRetryCount.clear(); // RETRY MECHANISM: Retry-Counter leeren

        // Datenbank nur schließen wenn gewünscht
        if (closeDatabase && db != null) {
            db.printStats(); // Statistiken ausgeben
            db.close();
            plugin.debug("Datenbank geschlossen");
            db = null;
        } else if (db != null) {
            plugin.debug("Datenbank bleibt offen für Restore");
        }
    }

    // ===================== Public Controls ======================

    /**
     * Startet globalen Biome-Timer (für Paper/Spigot/Purpur)
     * PERFORMANCE-OPTIMIERT: Queue-System verhindert TPS-Spikes bei schneller Bewegung
     */
    private void startGlobalBubbleTask() {
        if (!plugin.getConfig().getBoolean("biome.playerBubble.enabled", true)) return;

        int period = Math.max(5, plugin.getConfig().getInt("biome.playerBubble.tickIntervalTicks", 40));

        globalBubbleTask = scheduler.runGlobalTaskTimer(() -> {
            String snowWorld = plugin.getConfig().getString("snowWorld", "world");
            World w = Bukkit.getWorld(snowWorld);
            if (w == null) return;

            // PHASE 1: Sammle alle zu verarbeitenden Chunks in Queue
            for (Player p : w.getPlayers()) {
                if (p.isOnline() && p.isValid()) {
                    queueChunksAroundPlayer(p, w);
                }
            }

            // PHASE 2: Verarbeite Budget aus Queue (verhindert TPS-Spikes!)
            int budget = Math.max(1, plugin.getConfig().getInt("biome.playerBubble.perTickBudget", 6));
            processChunksFromQueue(w, budget);

        }, 40L, period);
    }

    /**
     * Startet Biome-Tracking für einen Spieler (Entity Scheduler)
     * NUR FÜR FOLIA! Auf Paper/Spigot/Purpur nutzen wir den globalen Timer
     */
    public void startPlayerTracking(org.bukkit.entity.Player player) {
        if (!plugin.isActive()) return;
        if (!plugin.getConfig().getBoolean("biome.playerBubble.enabled", true)) return;

        // Auf Paper/Spigot/Purpur läuft der globale Timer - nichts zu tun
        if (!scheduler.isFolia()) return;

        java.util.UUID uuid = player.getUniqueId();

        // Stoppe alten Task falls vorhanden
        WrappedTask oldTask = playerBubbleTasks.remove(uuid);
        if (oldTask != null) oldTask.cancel();

        // Starte neuen Task auf Entity Scheduler
        int period = Math.max(5, plugin.getConfig().getInt("biome.playerBubble.tickIntervalTicks", 40));
        WrappedTask task = scheduler.runForEntityTimer(player, () -> {
            if (!player.isOnline() || !player.isValid()) {
                stopPlayerTracking(player);
                return;
            }
            ensureAroundPlayer(player);
        }, 40L, period);

        if (task != null) {
            playerBubbleTasks.put(uuid, task);
            plugin.debug("Player-Tracking gestartet für " + player.getName());
        }
    }

    /**
     * Stoppt Biome-Tracking für einen Spieler
     */
    public void stopPlayerTracking(org.bukkit.entity.Player player) {
        WrappedTask task = playerBubbleTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
            plugin.debug("Player-Tracking gestoppt für " + player.getName());
        }
    }

    /**
     * Stellt sicher, dass ein Chunk winterlich ist
     * FOLIA-KOMPATIBEL: Muss auf Location/Region Scheduler aufgerufen werden!
     */
    public void ensureSnow(Chunk c) {
        if (!plugin.isActive()) return;

        World w = c.getWorld();
        processChunkAt(w, c.getX(), c.getZ());
    }

    // ENTFERNT: restoreSeedForLoaded() - der Befehl '/xmas biome restore' wurde
    // deaktiviert (Server-Freeze durch Referenzwelt-Laden); Restore läuft über
    // den SQLite-Snapshot (restoreALLAsync)

    /** Snapshot vollständig und asynchron zurückspielen (für /xmas off) */
    public void restoreALLAsync(int perTick) {
        if (db == null) {
            plugin.getLogger().warning(plugin.getLanguageManager().get("log.biome.restore-error-header"));
            plugin.getLogger().warning(plugin.getLanguageManager().get("log.biome.no-snapshot-available"));
            plugin.getLogger().warning(plugin.getLanguageManager().get("log.biome.snapshot-disabled-or-error"));
            plugin.getLogger().warning(plugin.getLanguageManager().get("log.biome.solution-enable-snapshot"));
            return;
        }

        // WICHTIG: Leere ALLE Caches VOR dem Restore!
        plugin.debug("Leere Caches vor Restore...");
        processedChunks.clear();
        knownSnapshotChunks.clear();
        chunkProcessQueue.clear();
        chunkRetryCount.clear();

        final BiomeSnapshotDatabase database = db;

        try {
            // Alle Chunk-Koordinaten aus der Datenbank abrufen
            final List<BiomeSnapshotDatabase.ChunkCoords> allChunks = database.getAllChunkCoordinates();
            final int totalChunks = allChunks.size();
            plugin.getLogger().info(plugin.getLanguageManager().get("log.biome.restore-start-header"));
            plugin.getLogger().info(plugin.getLanguageManager().getMessage("log.biome.chunks-in-database", totalChunks));

            if (totalChunks == 0) {
                plugin.getLogger().warning(plugin.getLanguageManager().get("log.biome.snapshot-empty"));
                plugin.getLogger().warning(plugin.getLanguageManager().get("log.biome.snapshot-timing-question"));
                plugin.getLogger().info(plugin.getLanguageManager().get("log.biome.separator-line"));
                database.close();
                db = null;
                return;
            }

            plugin.getLogger().info(plugin.getLanguageManager().getMessage("log.biome.starting-restore", totalChunks));
            plugin.getLogger().info(plugin.getLanguageManager().getMessage("log.biome.budget", Math.max(1, perTick) + " Chunks/Tick"));
            final long startTime = System.currentTimeMillis();

            final int budget = Math.max(1, perTick);
            // Nur vom globalen Timer angefasst (sequenziell) - kein Atomic nötig
            final int[] scheduled = {0};
            // COMPLETION FIX: Diese Zähler werden von Region-Threads inkrementiert → Atomics!
            final java.util.concurrent.atomic.AtomicInteger restored = new java.util.concurrent.atomic.AtomicInteger(0);
            final java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger(0);
            final java.util.concurrent.atomic.AtomicInteger finished = new java.util.concurrent.atomic.AtomicInteger(0);
            final java.util.concurrent.atomic.AtomicBoolean completionDone = new java.util.concurrent.atomic.AtomicBoolean(false);

            // COMPLETION FIX: Abschluss (Stats, Caches, DB schließen) erst wenn ALLE
            // Region-Tasks wirklich fertig sind - nicht schon wenn alle eingeplant wurden!
            final Runnable completionCheck = () -> {
                if (finished.get() < totalChunks) return;
                if (!completionDone.compareAndSet(false, true)) return;

                long duration = System.currentTimeMillis() - startTime;
                plugin.getLogger().info(plugin.getLanguageManager().get("log.biome.restore-complete-header"));
                plugin.getLogger().info(plugin.getLanguageManager().getMessage("log.biome.processed", totalChunks));
                plugin.getLogger().info(plugin.getLanguageManager().getMessage("log.biome.restored-count", restored.get()));
                if (errors.get() > 0) {
                    plugin.getLogger().info(plugin.getLanguageManager().getMessage("log.biome.error-count", errors.get()));
                }
                plugin.getLogger().info(plugin.getLanguageManager().getMessage("log.biome.duration", (duration / 1000.0)));
                plugin.getLogger().info(plugin.getLanguageManager().get("log.biome.separator-footer"));

                // WICHTIG: Leere ALLE Caches nach Restore!
                plugin.debug("Leere alle Caches nach Restore...");
                processedChunks.clear();
                knownSnapshotChunks.clear();
                chunkProcessQueue.clear();
                chunkRetryCount.clear();

                // CRITICAL FIX: KEIN clearAll() mehr bei Fehlern!
                // Erfolgreich restaurierte Chunks wurden bereits einzeln gelöscht.
                // Fehlgeschlagene Chunks BLEIBEN in der DB und werden beim
                // nächsten '/xmas off' erneut versucht.
                try {
                    if (errors.get() > 0) {
                        plugin.getLogger().warning(errors.get() + " Chunk(s) nicht restauriert - Snapshots bleiben in der DB für den nächsten Versuch.");
                    } else {
                        database.clearAll(); // DB ist ohnehin leer - räumt nur noch auf (VACUUM)
                    }
                    database.close();
                    plugin.debug("Datenbank geschlossen nach Restore");
                    db = null;
                } catch (Exception e) {
                    plugin.getLogger().warning(plugin.getLanguageManager().getMessage("log.biome.error-clearing-db", e.getMessage()));
                }
            };

            final WrappedTask[] restoreTask = new WrappedTask[1];
            restoreTask[0] = scheduler.runGlobalTaskTimer(() -> {
                try {
                    // Sammle bis zu 'budget' Chunks pro Tick und plane sie ein
                    int plannedThisTick = 0;
                    while (plannedThisTick < budget && scheduled[0] < totalChunks) {
                        final BiomeSnapshotDatabase.ChunkCoords coords = allChunks.get(scheduled[0]);
                        scheduled[0]++;
                        plannedThisTick++;

                        // Snapshot laden (auf globalem Timer-Thread)
                        BiomeSnapshotDatabase.BiomeSnapshot3D loadedSnapshot = null;
                        try {
                            World world = Bukkit.getWorld(coords.world);
                            if (world == null) {
                                plugin.getLogger().warning(plugin.getLanguageManager().getMessage("log.biome.world-not-found", coords.world));
                            } else {
                                loadedSnapshot = database.loadChunk3D(coords.world, coords.x, coords.z);
                                if (loadedSnapshot == null) {
                                    plugin.getLogger().warning(plugin.getLanguageManager().getMessage("log.biome.chunk-data-not-in-db", coords.x, coords.z));
                                }
                            }
                        } catch (Exception chunkError) {
                            plugin.getLogger().warning(plugin.getLanguageManager().getMessage("log.biome.chunk-error", coords.x, coords.z, chunkError.getMessage()));
                            loadedSnapshot = null;
                        }

                        // Fehler in der Lade-Phase: Chunk zählt trotzdem als abgeschlossen,
                        // sonst wird completionCheck nie wahr!
                        if (loadedSnapshot == null) {
                            errors.incrementAndGet();
                            finished.incrementAndGet();
                            completionCheck.run();
                            continue;
                        }

                        // FOLIA FIX: Jeder Chunk muss auf seinem EIGENEN Location Scheduler laufen!
                        // Batch-System funktioniert NICHT auf Folia (Cross-Region-Zugriff verboten)
                        final BiomeSnapshotDatabase.BiomeSnapshot3D snapshot = loadedSnapshot;
                        final int chunkX = coords.x;
                        final int chunkZ = coords.z;
                        org.bukkit.Location schedulerLoc = new org.bukkit.Location(Bukkit.getWorld(coords.world),
                            (chunkX << 4) + 8, 64, (chunkZ << 4) + 8);

                        scheduler.runAtLocation(schedulerLoc, () -> {
                            boolean success = false;
                            try {
                                World finalWorld = Bukkit.getWorld(coords.world);
                                if (finalWorld == null) {
                                    plugin.getLogger().fine("Welt nicht gefunden beim Restore: " + coords.world);
                                } else {
                                    // FOLIA: getChunkAt() ist sicher wenn auf Location Scheduler
                                    Chunk chunk = finalWorld.getChunkAt(chunkX, chunkZ);

                                    // Falls nicht geladen: FORCE load (loadChunk blockiert bis geladen)
                                    if (!chunk.isLoaded() && !finalWorld.loadChunk(chunkX, chunkZ, true)) {
                                        plugin.getLogger().warning(plugin.getLanguageManager().getMessage("log.biome.chunk-not-loaded-db", chunkX, chunkZ));
                                    } else {
                                        chunk = finalWorld.getChunkAt(chunkX, chunkZ);

                                        // Biomes wiederherstellen (3D!)
                                        restoreChunkBiomes3D(finalWorld, chunk, snapshot);

                                        // Schnee und Eis entfernen
                                        removeWinterBlocks3D(finalWorld, chunk, snapshot);

                                        // Chunk refreshen für Client-Update
                                        refreshChunkSafe(finalWorld, chunk);

                                        success = true;
                                    }
                                }
                            } catch (Exception e) {
                                plugin.getLogger().warning(plugin.getLanguageManager().getMessage("log.biome.error-restoring-chunk", chunkX, chunkZ, e.getMessage()));
                                if (plugin.isDebugMode()) e.printStackTrace();
                            } finally {
                                if (success) {
                                    restored.incrementAndGet();
                                    // CRITICAL FIX: Nur aus DB löschen wenn ERFOLGREICH restored!
                                    // Sonst bleiben Chunk-Streifen permanent (werden nie wieder versucht)!
                                    try {
                                        database.deleteChunk(coords.world, chunkX, chunkZ);
                                    } catch (SQLException e) {
                                        plugin.getLogger().fine(plugin.getLanguageManager().getMessage("log.database.delete-chunk-error", e.getMessage()));
                                    }
                                } else {
                                    errors.incrementAndGet();
                                    plugin.getLogger().warning(plugin.getLanguageManager().getMessage("log.biome.chunk-not-restored", chunkX, chunkZ));
                                }
                                finished.incrementAndGet();
                                completionCheck.run();
                            }
                        });
                    }

                    // Fortschritt alle 50 Chunks loggen
                    if (scheduled[0] % 50 == 0 && scheduled[0] < totalChunks) {
                        plugin.getLogger().info(plugin.getLanguageManager().getMessage("log.biome.restore-progress",
                                               scheduled[0], totalChunks, restored.get(), errors.get()));
                    }

                    // Timer beenden sobald alles EINGEPLANT ist - der Abschluss
                    // (Stats + DB schließen) läuft im completionCheck der Region-Tasks!
                    if (scheduled[0] >= totalChunks && restoreTask[0] != null) {
                        restoreTask[0].cancel();
                    }
                } catch (Exception e) {
                    plugin.getLogger().severe(plugin.getLanguageManager().getMessage("log.biome.fatal-error", e.getMessage()));
                    e.printStackTrace();
                    if (restoreTask[0] != null) {
                        restoreTask[0].cancel();
                    }
                }
            }, 1L, 1L);

        } catch (SQLException e) {
            plugin.getLogger().severe(plugin.getLanguageManager().getMessage("log.biome.error-retrieving-data", e.getMessage()));
            e.printStackTrace();
        }
    }

    /**
     * ABSICHERUNG: Stellt Original-Biome mit exakter 3D-Position wieder her
     */
    private void restoreChunkBiomes3D(World world, Chunk chunk, BiomeSnapshotDatabase.BiomeSnapshot3D snapshot) {
        // FIX: Verwende yStep AUS DEM SNAPSHOT, nicht aus Config!
        // Wenn Config zwischenzeitlich geändert wurde, würden wir sonst falsche Y-Levels verwenden
        int step = snapshot.yStep;
        int bx = chunk.getX() << 4;
        int bz = chunk.getZ() << 4;

        plugin.verboseDebugLang("log.debug.restore.chunk", chunk.getX(), chunk.getZ());
        plugin.verboseDebugLang("log.debug.restore.snapshot-info", snapshot.yStart, snapshot.yStep, snapshot.biomes.length);

        // DEBUG: Sammle unique Biomes aus Snapshot (nur für Verbose Debug)
        if (plugin.isVerboseDebugMode()) {
            java.util.Set<Biome> uniqueBiomes = new java.util.HashSet<>();
            for (int layer = 0; layer < snapshot.biomes.length; layer++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        uniqueBiomes.add(snapshot.biomes[layer][x][z]);
                    }
                }
            }
            plugin.verboseDebugLang("log.debug.restore.biomes-found", uniqueBiomes);
        }

        // Stelle jedes Biom an seiner EXAKTEN Position wieder her
        int restored = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = snapshot.yStart; y < snapshot.yStart + (snapshot.biomes.length * snapshot.yStep); y += step) {
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
     * ABSICHERUNG: Entfernt Schnee/Eis basierend auf 3D-Snapshot
     * Nur in Bereichen wo das Original-Biom nicht natürlich verschneit ist
     */
    private void removeWinterBlocks3D(World world, Chunk chunk, BiomeSnapshotDatabase.BiomeSnapshot3D snapshot) {
        int bx = chunk.getX() << 4;
        int bz = chunk.getZ() << 4;

        plugin.debug("Prüfe Winter-Blocks (3D) für Chunk " + chunk.getX() + "," + chunk.getZ());

        // PERFORMANCE: Nur relevanten Y-Bereich prüfen (wo Schnee/Eis sein kann)
        int checkMinY = Math.max(snapshot.yStart, 50);
        int checkMaxY = Math.min(snapshot.yStart + (snapshot.biomes.length * snapshot.yStep), 200);

        int removedSnow = 0;
        int removedIce = 0;

        // Prüfe jeden Block im relevanten Bereich
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = checkMinY; y < checkMaxY; y++) {
                    // Hole Original-Biom für diese EXAKTE Position
                    Biome originalBiome = snapshot.getBiomeAtY(x, z, y);
                    if (originalBiome == null) continue;

                    // Prüfe ob Schnee/Eis hier natürlich ist
                    boolean shouldRemoveSnow = !isNaturallySnowyBiome(originalBiome);
                    boolean shouldRemoveIce = !isNaturallyIcyBiome(originalBiome);

                    if (!shouldRemoveSnow && !shouldRemoveIce) continue;

                    // Prüfe Block
                    Block block = world.getBlockAt(bx + x, y, bz + z);
                    Material type = block.getType();

                    // ABSICHERUNG: Entferne nur Schneeschichten (SNOW), NICHT Schneeblöcke (SNOW_BLOCK)!
                    // Schneeblöcke sind platzierbar/natürlich und sollten bleiben
                    if (shouldRemoveSnow && type == Material.SNOW) {
                        block.setType(Material.AIR);
                        removedSnow++;
                    }
                    // SNOW_BLOCK wird NICHT entfernt - das ist natürlich/gewollt!

                    // ABSICHERUNG: Entferne nur normales Eis (ICE), nicht gepacktes (PACKED_ICE, BLUE_ICE)
                    // Gepacktes/blaues Eis ist meist künstlich platziert
                    if (shouldRemoveIce && type == Material.ICE) {
                        block.setType(Material.WATER);
                        removedIce++;
                    }
                    // PACKED_ICE und BLUE_ICE werden NICHT entfernt!
                }
            }
        }

        if (plugin.isDebugMode() && (removedSnow > 0 || removedIce > 0)) {
            plugin.debug("  Entfernt: " + removedSnow + " Schnee, " + removedIce + " Eis");
        }
    }

    /** Snapshot löschen (nur Datenbank, ohne irgendetwas zu setzen) */
    public void clearSnapshot() {
        if (db != null) {
            try {
                db.clearAll();
                plugin.getLogger().info(plugin.getLanguageManager().get("log.biome.snapshot-deleted"));
            } catch (SQLException e) {
                plugin.getLogger().severe(plugin.getLanguageManager().getMessage("log.biome.error-deleting-snapshot", e.getMessage()));
            }
        }
    }

    /** Gibt die Datenbank zurück (für Status-Abfragen) */
    public BiomeSnapshotDatabase getDatabase() {
        return db;
    }

    // ===================== Tick & Work (FOLIA-KOMPATIBEL) ======================

    /**
     * Stellt sicher, dass Chunks um einen Spieler winterlich sind
     * NUR FÜR FOLIA: Wird vom Entity Scheduler des Players aufgerufen
     */
    public void ensureAroundPlayer(Player p) {
        World w = p.getWorld();
        String snowWorld = plugin.getConfig().getString("snowWorld", "world");
        if (!w.getName().equals(snowWorld)) return;

        // FOLIA: Nutze Location Scheduler für jeden Chunk
        ensureAroundPlayerFolia(p, w);
    }

    /**
     * FOLIA: Chunks mit Location Scheduler verarbeiten (parallel über Regionen)
     * PERFORMANCE: Respektiert perTickBudget um TPS-Spikes zu vermeiden!
     */
    private void ensureAroundPlayerFolia(Player p, World w) {
        int r = Math.max(0, plugin.getConfig().getInt("biome.playerBubble.radiusChunks", 3));
        int budget = Math.max(1, plugin.getConfig().getInt("biome.playerBubble.perTickBudget", 12));
        org.bukkit.Location loc = p.getLocation();
        int baseCX = loc.getBlockX() >> 4;
        int baseCZ = loc.getBlockZ() >> 4;

        int scheduled = 0;

        // Jeder Chunk separat auf Location Scheduler (parallele Verarbeitung)
        // ABER: Nur maximal 'budget' Chunks pro Aufruf!
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (scheduled >= budget) return; // Budget erreicht - fertig für diesen Tick!

                final int chunkX = baseCX + dx;
                final int chunkZ = baseCZ + dz;
                ChunkKey key = new ChunkKey(w.getName(), chunkX, chunkZ);

                // Nur unverarbeitete Chunks
                if (!processedChunks.contains(key)) {
                    org.bukkit.Location chunkLoc = new org.bukkit.Location(w, (chunkX << 4) + 8, 64, (chunkZ << 4) + 8);
                    scheduler.runAtLocation(chunkLoc, () -> {
                        processChunkAt(w, chunkX, chunkZ);
                    });
                    scheduled++;
                }
            }
        }
    }

    /**
     * PAPER/SPIGOT/PURPUR: Füge Chunks um Spieler zur Verarbeitungs-Queue hinzu
     * PERFORMANCE: Nur neue Chunks werden gequeued, bereits verarbeitete übersprungen
     */
    private void queueChunksAroundPlayer(Player p, World w) {
        int r = Math.max(0, plugin.getConfig().getInt("biome.playerBubble.radiusChunks", 3));
        org.bukkit.Location loc = p.getLocation();
        int baseCX = loc.getBlockX() >> 4;
        int baseCZ = loc.getBlockZ() >> 4;

        // Sammle alle unverarbeiteten Chunks um Spieler
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int chunkX = baseCX + dx;
                int chunkZ = baseCZ + dz;
                ChunkKey key = new ChunkKey(w.getName(), chunkX, chunkZ);

                // Nur neue Chunks zur Queue hinzufügen
                if (!processedChunks.contains(key)) {
                    Chunk chunk = w.getChunkAt(chunkX, chunkZ);
                    if (chunk.isLoaded()) {
                        chunkProcessQueue.offer(new ChunkCoords(w, chunkX, chunkZ));
                    }
                }
            }
        }
    }

    /**
     * PAPER/SPIGOT/PURPUR: Verarbeite Budget aus Chunk-Queue
     * PERFORMANCE: Max 'budget' Chunks pro Tick → verhindert TPS-Spikes!
     */
    private void processChunksFromQueue(World w, int budget) {
        int processed = 0;

        while (processed < budget && !chunkProcessQueue.isEmpty()) {
            ChunkCoords coords = chunkProcessQueue.poll();
            if (coords == null) break;

            // Prüfe ob Chunk noch relevant ist (könnte zwischenzeitlich verarbeitet worden sein)
            ChunkKey key = new ChunkKey(coords.world.getName(), coords.x, coords.z);
            if (!processedChunks.contains(key)) {
                if (coords.world.getChunkAt(coords.x, coords.z).isLoaded()) {
                    processChunkAt(coords.world, coords.x, coords.z);
                    processed++;
                }
            }
        }

        // Debug: Queue-Größe loggen wenn groß
        if (plugin.isDebugMode() && chunkProcessQueue.size() > 50) {
            plugin.debug("Chunk-Queue: " + chunkProcessQueue.size() + " chunks wartend");
        }
    }

    /**
     * Verarbeitet einen einzelnen Chunk (auf Location/Region Scheduler)
     * FOLIA-KOMPATIBEL: Darf nur auf Location/Region Scheduler aufgerufen werden!
     * Exakt wie v1.4.1 - minimale Fehlerbehandlung für Stabilität
     */
    private void processChunkAt(World w, int chunkX, int chunkZ) {
        ChunkKey key = new ChunkKey(w.getName(), chunkX, chunkZ);

        // PERFORMANCE: Überspringe bereits verarbeitete Chunks
        if (processedChunks.contains(key)) {
            return;
        }

        // Lade Chunk falls nötig (nur im richtigen Scheduler-Kontext!)
        Chunk chunk = w.getChunkAt(chunkX, chunkZ);
        if (!chunk.isLoaded()) {
            // RETRY MECHANISM: Tracke nicht-geladene Chunks und versuche sie später nochmal
            int retries = chunkRetryCount.getOrDefault(key, 0);
            retries++;
            chunkRetryCount.put(key, retries);

            if (retries >= MAX_CHUNK_RETRIES) {
                // Nach MAX_CHUNK_RETRIES Versuchen: Als "verarbeitet" markieren (aufgeben)
                processedChunks.add(key);
                chunkRetryCount.remove(key); // Cleanup
                plugin.verboseDebug("Chunk " + chunkX + "," + chunkZ + " nach " + retries + " Versuchen übersprungen (nicht geladen)");
            }
            // Sonst: Chunk NICHT zum processedChunks hinzufügen → wird beim nächsten Mal nochmal versucht!
            return;
        }

        // Chunk ist geladen - verarbeite ihn!
        Biome target = getTargetBiome();
        snapshotIfAbsent(w, chunk);
        if (applyUniformBiomeColumn(w, chunk, target)) {
            refreshChunkSafe(w, chunk);
        }

        // ERFOLG: Als verarbeitet markieren und Retry-Counter entfernen
        processedChunks.add(key);
        chunkRetryCount.remove(key);

        // Begrenze Cache-Größe
        if (processedChunks.size() > MAX_PROCESSED_CACHE) {
            // Entferne die ältesten 20% (ca. 1000 Chunks)
            Iterator<ChunkKey> it = processedChunks.iterator();
            int toRemove = MAX_PROCESSED_CACHE / 5;
            int removed = 0;
            while (it.hasNext() && removed < toRemove) {
                it.next();
                it.remove();
                removed++;
            }
        }

        // RETRY MECHANISM: Begrenze Retry-Counter-Map Größe (vermeide Memory Leak)
        if (chunkRetryCount.size() > 1000) {
            // Entferne Chunks die sehr viele Retries haben (wahrscheinlich nie laden werden)
            chunkRetryCount.entrySet().removeIf(entry -> entry.getValue() >= MAX_CHUNK_RETRIES);
        }
    }

    // ENTFERNT: ringEnsureSnow() - Ersetzt durch Location Scheduler in ensureAroundPlayer()

    // ===================== Helpers ======================
    private Biome getTargetBiome() {
        String biomeName = plugin.getConfig().getString("biome.target", "SNOWY_PLAINS");
        try {
            // Use key-based API instead of deprecated valueOf
            // WICHTIG: Registry.get() wirft KEINE Exception bei unbekanntem Namen,
            // sondern gibt null zurück → expliziter Null-Check nötig!
            Biome biome = org.bukkit.Registry.BIOME.get(org.bukkit.NamespacedKey.minecraft(biomeName.toLowerCase()));
            if (biome == null) {
                plugin.getLogger().warning("Unbekanntes Biom in config.yml (biome.target): '" + biomeName + "' - Fallback auf SNOWY_PLAINS");
                return Biome.SNOWY_PLAINS;
            }
            return biome;
        } catch (Exception e) {
            return Biome.SNOWY_PLAINS;
        }
    }

    /** Prüft, ob ein Biome natürlich Eis enthält (damit wir es nicht fälschlicherweise entfernen) */
    private boolean isNaturallyIcyBiome(Biome biome) {
        if (biome == null) return false;
        // Use getKey() instead of deprecated name()
        String name = biome.getKey().getKey().toLowerCase();
        return name.contains("frozen") ||
               name.contains("ice") ||
               name.contains("snowy") ||
               name.equals("grove") ||
               name.equals("jagged_peaks");
    }

    /** Prüft, ob ein Biome natürlich Schnee enthält (damit wir ihn nicht fälschlicherweise entfernen) */
    private boolean isNaturallySnowyBiome(Biome biome) {
        if (biome == null) return false;
        // Use getKey() instead of deprecated name()
        String name = biome.getKey().getKey().toLowerCase();
        return name.contains("snowy") ||
               name.contains("frozen") ||
               name.contains("ice") ||
               name.equals("grove") ||
               name.equals("jagged_peaks") ||
               name.equals("frozen_peaks");
    }

    private int getVerticalStep() {
        // Korrekt für 1.18+: Biome-Auflösung 4 Blöcke hoch → Schrittweite 4 für volle Abdeckung
        return Math.max(1, plugin.getConfig().getInt("biome.verticalStep", 4));
    }

    /**
     * ABSICHERUNG: Prüft ob ein Biom geändert werden darf
     * Blockiert Nether, End und Höhlen-Biome komplett!
     */
    private boolean isBiomeAllowedToChange(Biome biome) {
        if (biome == null) return false;

        // Use getKey() instead of deprecated name()
        String name = biome.getKey().getKey().toUpperCase();

        // BLACKLIST: Diese Biome NIEMALS ändern!
        // Nether-Biome
        if (name.contains("NETHER")) return false;
        if (name.contains("CRIMSON")) return false;
        if (name.contains("WARPED")) return false;
        if (name.contains("BASALT")) return false;
        if (name.contains("SOUL")) return false;

        // End-Biome
        if (name.contains("END")) return false;
        if (name.equals("THE_END")) return false;
        if (name.equals("SMALL_END_ISLANDS")) return false;
        if (name.equals("END_BARRENS")) return false;
        if (name.equals("END_HIGHLANDS")) return false;
        if (name.equals("END_MIDLANDS")) return false;

        // Höhlen-Biome (unterirdisch)
        if (name.contains("CAVE")) return false;
        if (name.contains("DEEP_DARK")) return false;
        if (name.equals("LUSH_CAVES")) return false;
        if (name.equals("DRIPSTONE_CAVES")) return false;

        // Alles andere ist erlaubt
        return true;
    }

    /**
     * ABSICHERUNG: Y-Level Grenzen für Biom-Änderungen
     * Nur Oberflächen-Biome ändern, nichts unterirdisches!
     */
    private int getMinChangeY() {
        // Nur ab Y=50 ändern (nichts tief unterirdisch)
        return 50;
    }

    private int getMaxChangeY() {
        // Maximal bis Y=200 ändern (nichts in extremen Bergen)
        return 200;
    }

    // PERFORMANCE FIX: Cache für DB-Lookups (verhindert wiederholte hasChunk() Aufrufe)
    private final Set<ChunkKey> knownSnapshotChunks = ConcurrentHashMap.newKeySet();

    /**
     * ABSICHERUNG: Erstelle 3D-Snapshot mit exakten Biom-Positionen
     * Nur für erlaubten Y-Bereich (50-200)
     * PERFORMANCE-OPTIMIERT: Cache für DB-Lookups
     */
    private void snapshotIfAbsent(World w, Chunk c) {
        if (db == null) {
            return; // Kein Debug-Spam
        }

        ChunkKey key = new ChunkKey(w.getName(), c.getX(), c.getZ());

        // PERFORMANCE FIX: Prüfe Cache BEVOR wir DB abfragen
        if (knownSnapshotChunks.contains(key)) {
            return; // Bereits gesnapshoted (Cache-Hit)
        }

        try {
            // DB-Abfrage nur wenn nicht im Cache
            if (db.hasChunk(key.world, key.x, key.z)) {
                knownSnapshotChunks.add(key); // Zu Cache hinzufügen
                return;
            }

            plugin.verboseDebugLang("log.debug.snapshot.creating", key.x, key.z);

            // 3D-Snapshot: Sample auf allen Y-Ebenen im erlaubten Bereich
            int minY = getMinChangeY(); // 50
            int maxY = getMaxChangeY(); // 200
            int yStep = getVerticalStep(); // 4

            // FIX: Stelle sicher, dass wir bis EINSCHLIESSLICH maxY abdecken
            // Berechne Anzahl Schichten so, dass die letzte Schicht >= maxY ist
            int yLayers = ((maxY - minY) / yStep) + 1; // +1 um sicherzustellen wir erreichen maxY
            Biome[][][] biomes3D = new Biome[yLayers][16][16];

            int bx = c.getX() << 4;
            int bz = c.getZ() << 4;

            // Sample Biome auf jeder Y-Ebene
            for (int layer = 0; layer < yLayers; layer++) {
                int y = minY + (layer * yStep);
                // Stelle sicher wir gehen nicht über Minecraft's Höhenlimit
                if (y > 319) break; // Minecraft 1.21 max height

                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        Biome biome = w.getBiome(bx + x, y, bz + z);
                        biomes3D[layer][x][z] = biome;
                    }
                }
            }

            // WICHTIG: Prüfe ob Chunk bereits geändert wurde (IMMER, nicht nur im Debug!)
            // Sammle unique Biomes um zu prüfen ob bereits alles SNOWY_PLAINS ist
            java.util.Set<Biome> uniqueBiomes = new java.util.HashSet<>();
            for (int layer = 0; layer < yLayers; layer++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        uniqueBiomes.add(biomes3D[layer][x][z]);
                    }
                }
            }

            // DEBUG: Zeige gefundene Biomes (nur im Verbose Debug)
            if (plugin.isVerboseDebugMode()) {
                plugin.verboseDebugLang("log.debug.snapshot.biomes-found", uniqueBiomes);
            }

            // WARNUNG: Wenn nur SNOWY_PLAINS gefunden wurde, ist der Chunk möglicherweise bereits geändert!
            // Wir speichern trotzdem einen Snapshot, da sonst bei Restore GAR NICHTS restored wird.
            // Besser SNOWY_PLAINS → SNOWY_PLAINS restoren als GAR NICHT restoren!
            if (uniqueBiomes.size() == 1 && uniqueBiomes.contains(Biome.SNOWY_PLAINS)) {
                plugin.debug("WARNUNG: Chunk " + key.x + "," + key.z + " ist bereits 100% SNOWY_PLAINS!");
                plugin.debug("  Snapshot wird trotzdem erstellt (Fallback für Restore).");
                // Fahre fort mit Snapshot-Erstellung
            }

            // Speichere 3D-Snapshot
            db.saveChunk3D(key.world, key.x, key.z, biomes3D, minY, yStep);
            knownSnapshotChunks.add(key); // PERFORMANCE FIX: Zu Cache hinzufügen nach Snapshot
            plugin.verboseDebugLang("log.debug.snapshot.saved", key.x, key.z, yLayers);

        } catch (SQLException e) {
            plugin.getLogger().warning(plugin.getLanguageManager().getMessage("log.biome.error-saving-snapshot", e.getMessage()));
            if (plugin.isDebugMode()) e.printStackTrace();
        }
    }

    /**
     * Setzt nur ERLAUBTE Oberflächen-Biome auf das Zielbiom
     * ABSICHERUNG: Nether/End/Höhlen werden NICHT geändert!
     */
    private boolean applyUniformBiomeColumn(World world, Chunk chunk, Biome target) {
        boolean modified = false;
        int step = getVerticalStep();
        int bx = chunk.getX() << 4, bz = chunk.getZ() << 4;

        // ABSICHERUNG: Nur definierter Y-Bereich!
        int minY = getMinChangeY();  // Y=50
        int maxY = getMaxChangeY();  // Y=200

        // PERFORMANCE FIX: Erweiterte Stichprobe ob Chunk überhaupt geändert werden muss
        // Prüfe 9 Punkte statt 4 für bessere Abdeckung (Ecken + Zentrum + Mitten)
        int sampleY = Math.max(minY, Math.min(64, maxY - 1));
        int correctSamples = 0;
        // Ecken
        if (world.getBiome(bx + 0, sampleY, bz + 0) == target) correctSamples++;
        if (world.getBiome(bx + 15, sampleY, bz + 0) == target) correctSamples++;
        if (world.getBiome(bx + 0, sampleY, bz + 15) == target) correctSamples++;
        if (world.getBiome(bx + 15, sampleY, bz + 15) == target) correctSamples++;
        // Zentrum + Mitten
        if (world.getBiome(bx + 8, sampleY, bz + 8) == target) correctSamples++;
        if (world.getBiome(bx + 0, sampleY, bz + 8) == target) correctSamples++;
        if (world.getBiome(bx + 15, sampleY, bz + 8) == target) correctSamples++;
        if (world.getBiome(bx + 8, sampleY, bz + 0) == target) correctSamples++;
        if (world.getBiome(bx + 8, sampleY, bz + 15) == target) correctSamples++;

        if (correctSamples == 9) {
            return false; // Chunk ist bereits korrekt - überspringe!
        }

        // Chunk muss geändert werden - aber NUR erlaubte Biome!
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y += step) {
                    Biome currentBiome = world.getBiome(bx + x, y, bz + z);

                    // ABSICHERUNG: Nur ändern wenn erlaubt!
                    if (currentBiome != target && isBiomeAllowedToChange(currentBiome)) {
                        world.setBiome(bx + x, y, bz + z, target);
                        modified = true;
                    }
                }
            }
        }
        return modified;
    }

    private void refreshChunkSafe(World w, Chunk c) {
        // PERFORMANCE FIX: Nur refreshen wenn in Config aktiviert
        if (!plugin.getConfig().getBoolean("biome.playerBubble.refreshClient", true)) {
            return; // Client-Refresh deaktiviert - Spieler sehen Updates beim Relog
        }

        // PERFORMANCE FIX: Überspringen wenn kein Spieler in der Nähe
        final int chunkX = c.getX();
        final int chunkZ = c.getZ();
        int centerX = (chunkX << 4) + 8;
        int centerZ = (chunkZ << 4) + 8;

        // Prüfe ob überhaupt ein Spieler in Sichtweite ist
        boolean hasNearbyPlayers = false;
        for (Player p : w.getPlayers()) {
            int px = p.getLocation().getBlockX();
            int pz = p.getLocation().getBlockZ();
            int distSq = (px - centerX) * (px - centerX) + (pz - centerZ) * (pz - centerZ);
            if (distSq < 160 * 160) { // 10 chunks = 160 blocks
                hasNearbyPlayers = true;
                break;
            }
        }

        // Wenn keine Spieler in der Nähe, überspringe Refresh komplett
        if (!hasNearbyPlayers) {
            return;
        }

        // Versuche refreshChunk (deprecated aber manchmal noch funktional)
        try {
            w.refreshChunk(chunkX, chunkZ);
        } catch (Throwable ignored) {}
    }

    // ENTFERNT: restoreChunkFromRef()/getOrCreateRefWorld()/unloadRefWorld() -
    // gehörten zum deaktivierten Seed-Restore ('/xmas biome restore')

    /**
     * Manueller Set-Befehl für problematische Stellen
     * FOLIA-KOMPATIBEL: Verwendet Location Scheduler für Chunk-Operationen
     * Gibt die Anzahl der Chunks zum Verarbeiten zurück (nicht die tatsächlich geänderten!)
     */
    public int setBiomeAroundPlayer(Object sender, Biome target, int radiusChunks) {
        Player p = (sender instanceof Player pl) ? pl : null;
        if (p == null) return 0;

        // WICHTIG: Nur wenn xmas ON ist (sonst kein Snapshot möglich!)
        if (!plugin.isActive()) {
            if (sender instanceof org.bukkit.command.CommandSender cs) {
                cs.sendMessage("§c/xmas biome set funktioniert nur wenn ChristmasSeason aktiv ist!");
                cs.sendMessage("§7Verwende zuerst '/xmas on', dann '/xmas biome set'");
            }
            return 0;
        }

        World w = p.getWorld();
        String worldName = plugin.getConfig().getString("snowWorld", "world");
        if (!w.getName().equals(worldName)) return 0;

        // FOLIA FIX: Berechne Chunk-Koordinaten ohne getChunk()
        org.bukkit.Location loc = p.getLocation();
        int baseCX = loc.getBlockX() >> 4;
        int baseCZ = loc.getBlockZ() >> 4;

        // Berechne Anzahl Chunks die verarbeitet werden
        int totalChunks = 1 + (radiusChunks * 8 * (radiusChunks + 1) / 2);

        // RACE CONDITION FIX: Markiere Chunks SOFORT als verarbeitet (synchron),
        // BEVOR die async Location Scheduler Tasks starten!
        // Sonst überschreibt der globale Timer die manuellen Änderungen!
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                final int chunkX = baseCX + dx;
                final int chunkZ = baseCZ + dz;

                // CRITICAL: Sofort zu Cache hinzufügen um Race Condition zu vermeiden
                ChunkKey key = new ChunkKey(w.getName(), chunkX, chunkZ);
                processedChunks.add(key);
                // BUGFIX: knownSnapshotChunks NICHT hier hinzufügen!
                // snapshotIfAbsent() muss prüfen können ob Snapshot existiert!
            }
        }

        // FOLIA FIX: Verarbeite jeden Chunk auf Location Scheduler
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                final int chunkX = baseCX + dx;
                final int chunkZ = baseCZ + dz;

                org.bukkit.Location chunkLoc = new org.bukkit.Location(w, (chunkX << 4) + 8, 64, (chunkZ << 4) + 8);

                scheduler.runAtLocation(chunkLoc, () -> {
                    Chunk chunk = w.getChunkAt(chunkX, chunkZ);
                    if (!chunk.isLoaded()) return;

                    snapshotIfAbsent(w, chunk);
                    if (applyUniformBiomeColumn(w, chunk, target)) {
                        refreshChunkSafe(w, chunk);
                    }
                });
            }
        }

        return totalChunks; // Rückgabe: Anzahl geplanter Chunks (nicht tatsächlich geänderte!)
    }

    // migrateFromYAML() entfernt - nicht mehr benötigt mit neuem 3D-Format
}
