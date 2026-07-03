package de.boondocksulfur.christmas.manager;

import de.boondocksulfur.christmas.ChristmasSeason;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Biome;

import java.io.File;
import java.sql.SQLException;
import java.util.*;

/**
 * Vergleicht aktuelle Biome mit Backup-Datenbank
 *
 * Use-Cases:
 * - Prüfen welche Chunks seit Backup geändert wurden
 * - Einzelne Chunks aus Backup wiederherstellen
 * - Unterschiede korrigieren nach versehentlichem DB-Verlust
 *
 * Workflow:
 * 1. /xmas biome compare <backup-ID> - Zeigt Unterschiede
 * 2. /xmas biome fix-diff <backup-ID> - Korrigiert alle Unterschiede
 */
public class BiomeCompare {

    private final ChristmasSeason plugin;

    public BiomeCompare(ChristmasSeason plugin) {
        this.plugin = plugin;
    }

    /**
     * Vergleicht aktuelle Welt-Biome mit Backup
     *
     * @param backupFile Backup-Datei zum Vergleichen
     * @return Vergleichs-Ergebnis mit Unterschieden
     */
    public CompareResult compareWithBackup(File backupFile) {
        if (!backupFile.exists()) {
            plugin.getLogger().warning("Backup-Datei nicht gefunden: " + backupFile.getName());
            return null;
        }

        try {
            // Öffne Backup-Datenbank (temporär, nur lesen)
            BiomeSnapshotDatabase backupDb = new BiomeSnapshotDatabase(plugin, backupFile);
            backupDb.open();

            String worldName = plugin.getConfig().getString("snowWorld", "world");
            World world = Bukkit.getWorld(worldName);

            if (world == null) {
                plugin.getLogger().warning("Welt nicht gefunden: " + worldName);
                backupDb.close();
                return null;
            }

            // Hole alle Chunks aus Backup
            List<BiomeSnapshotDatabase.ChunkCoords> backupChunks = backupDb.getAllChunkCoordinates();
            plugin.getLogger().info("Vergleiche " + backupChunks.size() + " Chunks mit aktueller Welt...");

            List<ChunkDifference> differences = new ArrayList<>();
            int compared = 0;
            int identical = 0;

            for (BiomeSnapshotDatabase.ChunkCoords coords : backupChunks) {
                if (!coords.world.equals(worldName)) continue;

                // Lade Chunk aus Backup
                BiomeSnapshotDatabase.BiomeSnapshot3D backupSnapshot = backupDb.loadChunk3D(coords.world, coords.x, coords.z);
                if (backupSnapshot == null) continue;

                // FIX: Merke ob Chunk bereits geladen war für späteres Cleanup
                Chunk currentChunk = world.getChunkAt(coords.x, coords.z);
                boolean wasLoaded = currentChunk.isLoaded();
                if (!wasLoaded) {
                    world.loadChunk(coords.x, coords.z, false);
                }

                try {
                    // Vergleiche Biome
                    ChunkDifference diff = compareChunk(world, coords.x, coords.z, backupSnapshot);
                    if (diff.hasDifferences()) {
                        differences.add(diff);
                    } else {
                        identical++;
                    }

                    compared++;

                    // Fortschritt alle 100 Chunks
                    if (compared % 100 == 0) {
                        plugin.getLogger().info("Fortschritt: " + compared + "/" + backupChunks.size() + " (" + differences.size() + " Unterschiede)");
                    }
                } finally {
                    // FIX: Entlade Chunk wieder wenn er vorher nicht geladen war
                    if (!wasLoaded && currentChunk.isLoaded()) {
                        world.unloadChunk(coords.x, coords.z, false);
                    }
                }
            }

            backupDb.close();

            plugin.getLogger().info("Vergleich abgeschlossen: " + compared + " Chunks verglichen");
            plugin.getLogger().info("Identisch: " + identical + ", Unterschiede: " + differences.size());

            return new CompareResult(backupFile, compared, identical, differences);

        } catch (SQLException e) {
            plugin.getLogger().severe("Fehler beim Vergleich: " + e.getMessage());
            if (plugin.isDebugMode()) e.printStackTrace();
            return null;
        }
    }

    /**
     * Vergleicht einen Chunk mit Backup-Snapshot
     *
     * @param world Welt
     * @param chunkX Chunk X
     * @param chunkZ Chunk Z
     * @param backupSnapshot Backup-Snapshot
     * @return Unterschiede zwischen aktuellem Chunk und Backup
     */
    private ChunkDifference compareChunk(World world, int chunkX, int chunkZ, BiomeSnapshotDatabase.BiomeSnapshot3D backupSnapshot) {
        int bx = chunkX << 4;
        int bz = chunkZ << 4;

        int differences = 0;
        Map<Biome, Integer> changedBiomes = new HashMap<>();

        // Vergleiche jeden Biome-Eintrag
        for (int layer = 0; layer < backupSnapshot.biomes.length; layer++) {
            int y = backupSnapshot.yStart + (layer * backupSnapshot.yStep);

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    Biome backupBiome = backupSnapshot.biomes[layer][x][z];
                    Biome currentBiome = world.getBiome(bx + x, y, bz + z);

                    if (backupBiome != currentBiome) {
                        differences++;
                        changedBiomes.put(currentBiome, changedBiomes.getOrDefault(currentBiome, 0) + 1);
                    }
                }
            }
        }

        return new ChunkDifference(chunkX, chunkZ, differences, changedBiomes);
    }

    /**
     * Korrigiert alle Unterschiede zwischen aktueller Welt und Backup
     *
     * @param backupFile Backup-Datei als Quelle
     * @param result Vergleichs-Ergebnis (Optional, wird neu berechnet wenn null)
     * @return Anzahl korrigierter Chunks
     */
    public int fixDifferences(File backupFile, CompareResult result) {
        if (result == null) {
            plugin.getLogger().info("Erstelle Vergleich...");
            result = compareWithBackup(backupFile);
            if (result == null) {
                return 0;
            }
        }

        if (result.differences.isEmpty()) {
            plugin.getLogger().info("Keine Unterschiede gefunden - nichts zu korrigieren!");
            return 0;
        }

        plugin.getLogger().info("Korrigiere " + result.differences.size() + " Chunks...");

        try {
            // Öffne Backup-Datenbank
            BiomeSnapshotDatabase backupDb = new BiomeSnapshotDatabase(plugin, backupFile);
            backupDb.open();

            String worldName = plugin.getConfig().getString("snowWorld", "world");
            World world = Bukkit.getWorld(worldName);

            if (world == null) {
                plugin.getLogger().warning("Welt nicht gefunden: " + worldName);
                backupDb.close();
                return 0;
            }

            int fixed = 0;

            for (ChunkDifference diff : result.differences) {
                // Lade Snapshot aus Backup
                BiomeSnapshotDatabase.BiomeSnapshot3D snapshot = backupDb.loadChunk3D(worldName, diff.chunkX, diff.chunkZ);
                if (snapshot == null) {
                    plugin.debug("Snapshot nicht gefunden für Chunk " + diff.chunkX + "," + diff.chunkZ);
                    continue;
                }

                // FIX: Merke ob Chunk bereits geladen war für späteres Cleanup
                Chunk chunk = world.getChunkAt(diff.chunkX, diff.chunkZ);
                boolean wasLoaded = chunk.isLoaded();
                if (!wasLoaded) {
                    world.loadChunk(diff.chunkX, diff.chunkZ, false);
                }

                try {
                    // Restore Biome aus Backup
                    restoreChunkFromSnapshot(world, diff.chunkX, diff.chunkZ, snapshot);
                    fixed++;

                    // Fortschritt alle 50 Chunks
                    if (fixed % 50 == 0) {
                        plugin.getLogger().info("Fortschritt: " + fixed + "/" + result.differences.size() + " Chunks korrigiert");
                    }
                } finally {
                    // FIX: Entlade Chunk wieder wenn er vorher nicht geladen war
                    if (!wasLoaded && chunk.isLoaded()) {
                        world.unloadChunk(diff.chunkX, diff.chunkZ, false);
                    }
                }
            }

            backupDb.close();

            plugin.getLogger().info("Korrektur abgeschlossen: " + fixed + " Chunks wiederhergestellt");
            return fixed;

        } catch (SQLException e) {
            plugin.getLogger().severe("Fehler bei Korrektur: " + e.getMessage());
            if (plugin.isDebugMode()) e.printStackTrace();
            return 0;
        }
    }

    /**
     * Stellt Chunk-Biome aus Snapshot wieder her
     * (Kopie der Logik aus BiomeSnowManager)
     */
    private void restoreChunkFromSnapshot(World world, int chunkX, int chunkZ, BiomeSnapshotDatabase.BiomeSnapshot3D snapshot) {
        int bx = chunkX << 4;
        int bz = chunkZ << 4;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = snapshot.yStart; y < snapshot.yStart + (snapshot.biomes.length * snapshot.yStep); y += snapshot.yStep) {
                    Biome originalBiome = snapshot.getBiomeAtY(x, z, y);

                    if (originalBiome != null) {
                        world.setBiome(bx + x, y, bz + z, originalBiome);
                    }
                }
            }
        }

        // Refresh Chunk für Clients
        try {
            world.refreshChunk(chunkX, chunkZ);
        } catch (Throwable ignored) {}
    }

    /**
     * Ergebnis eines Chunk-Vergleichs
     */
    public static class ChunkDifference {
        public final int chunkX;
        public final int chunkZ;
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

    /**
     * Gesamt-Ergebnis eines Vergleichs
     */
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
