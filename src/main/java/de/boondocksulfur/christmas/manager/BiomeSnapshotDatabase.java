package de.boondocksulfur.christmas.manager;

import org.bukkit.block.Biome;
import de.boondocksulfur.christmas.ChristmasSeason;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.sql.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * SQLite-basiertes Snapshot-System für Biome-Daten.
 *
 * Ersetzt das alte YAML-System mit folgenden Vorteilen:
 * - Unbegrenzte Chunk-Anzahl (kein 2000-Limit mehr)
 * - Komprimierte Speicherung (10.000 Chunks = ~5-10 MB statt 156 MB)
 * - Schnelles Laden/Speichern (Millisekunden statt Sekunden)
 * - Kein Memory-Overhead beim Laden
 */
public class BiomeSnapshotDatabase {

    private final ChristmasSeason plugin;
    private final File dbFile;
    private Connection connection;

    public BiomeSnapshotDatabase(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.dbFile = new File(plugin.getDataFolder(), "biome-snapshot.db");
    }

    /**
     * Öffnet die Datenbankverbindung und erstellt die Tabelle falls nötig
     */
    public synchronized void open() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().severe(plugin.getLanguageManager().get("log.database.jdbc-not-found"));
            throw new SQLException("SQLite driver not found", e);
        }

        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        connection = DriverManager.getConnection(url);

        // Optimierungen für Performance
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode = WAL");  // Write-Ahead Logging
            stmt.execute("PRAGMA synchronous = NORMAL"); // Schnellere Writes
            stmt.execute("PRAGMA cache_size = 10000");   // 10MB Cache
            stmt.execute("PRAGMA temp_store = MEMORY");  // Temp-Daten im RAM
        }

        createTable();
        plugin.getLogger().info(plugin.getLanguageManager().getMessage("log.database.opened", dbFile.getName()));
    }

    /**
     * Erstellt die chunks Tabelle falls sie nicht existiert
     */
    private void createTable() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS chunks (" +
                     "world TEXT NOT NULL, " +
                     "x INTEGER NOT NULL, " +
                     "z INTEGER NOT NULL, " +
                     "biomes BLOB NOT NULL, " +
                     "timestamp INTEGER NOT NULL, " +
                     "PRIMARY KEY (world, x, z))";

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);

            // Index für schnelle Abfragen
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_timestamp ON chunks(timestamp)");
        }
    }

    /**
     * Speichert einen 3D Chunk-Snapshot (komprimiert)
     * Format: [Magic 0x3D] [yLayers] [yStart] [yStep] [biomes...]
     */
    public synchronized void saveChunk3D(String world, int x, int z, Biome[][][] biomes3D, int yStart, int yStep) throws SQLException {
        if (biomes3D == null || biomes3D.length == 0) {
            throw new IllegalArgumentException("biomes3D cannot be null or empty");
        }

        // Komprimiere 3D Biome-Daten
        byte[] compressed = compressBiomes3D(biomes3D, yStart, yStep);

        String sql = "INSERT OR REPLACE INTO chunks (world, x, z, biomes, timestamp) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, world);
            pstmt.setInt(2, x);
            pstmt.setInt(3, z);
            pstmt.setBytes(4, compressed);
            pstmt.setLong(5, System.currentTimeMillis());
            pstmt.executeUpdate();
        }
    }

    /**
     * Container für 3D Biome-Snapshot-Daten
     */
    public static class BiomeSnapshot3D {
        public final Biome[][][] biomes; // [y_layer][x][z]
        public final int yStart;
        public final int yStep;

        public BiomeSnapshot3D(Biome[][][] biomes, int yStart, int yStep) {
            this.biomes = biomes;
            this.yStart = yStart;
            this.yStep = yStep;
        }

        /**
         * Hole Biom für spezifische Y-Koordinate
         */
        public Biome getBiomeAtY(int x, int z, int y) {
            int layerIndex = (y - yStart) / yStep;
            if (layerIndex < 0 || layerIndex >= biomes.length) {
                return null; // Y außerhalb gespeichertem Bereich
            }
            return biomes[layerIndex][x & 15][z & 15];
        }
    }

    /**
     * Lädt einen 3D Chunk-Snapshot (dekomprimiert)
     *
     * @return BiomeSnapshot3D oder null wenn nicht gefunden
     */
    public synchronized BiomeSnapshot3D loadChunk3D(String world, int x, int z) throws SQLException {
        String sql = "SELECT biomes FROM chunks WHERE world = ? AND x = ? AND z = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, world);
            pstmt.setInt(2, x);
            pstmt.setInt(3, z);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    byte[] compressed = rs.getBytes("biomes");
                    return decompressBiomes3D(compressed);
                }
            }
        }

        return null; // Chunk nicht im Snapshot
    }

    /**
     * Prüft ob ein Chunk im Snapshot existiert
     */
    public synchronized boolean hasChunk(String world, int x, int z) throws SQLException {
        String sql = "SELECT 1 FROM chunks WHERE world = ? AND x = ? AND z = ? LIMIT 1";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, world);
            pstmt.setInt(2, x);
            pstmt.setInt(3, z);

            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Löscht einen Chunk aus dem Snapshot
     */
    public synchronized void deleteChunk(String world, int x, int z) throws SQLException {
        String sql = "DELETE FROM chunks WHERE world = ? AND x = ? AND z = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, world);
            pstmt.setInt(2, x);
            pstmt.setInt(3, z);
            pstmt.executeUpdate();
        }
    }

    /**
     * Gibt die Anzahl gespeicherter Chunks zurück
     */
    public synchronized int getChunkCount() throws SQLException {
        String sql = "SELECT COUNT(*) FROM chunks";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        }

        return 0;
    }

    /**
     * Gibt die Datenbankgröße in Bytes zurück
     */
    public long getDatabaseSize() {
        return dbFile.exists() ? dbFile.length() : 0;
    }

    /**
     * Löscht alle Snapshots
     */
    public synchronized void clearAll() throws SQLException {
        String sql = "DELETE FROM chunks";

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }

        // Vacuum um Speicherplatz freizugeben
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("VACUUM");
        }

        plugin.getLogger().info(plugin.getLanguageManager().get("log.database.cleared"));
    }

    /**
     * Schließt die Datenbankverbindung
     */
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
                plugin.getLogger().info(plugin.getLanguageManager().get("log.database.closed"));
            } catch (SQLException e) {
                plugin.getLogger().warning(plugin.getLanguageManager().getMessage("log.database.error-closing", e.getMessage()));
            }
        }
    }

    // ENTFERNT: compressBiomes()/decompressBiomes() (altes 2D-Format) -
    // seit dem 3D-Format ungenutzt, nur noch compressBiomes3D/decompressBiomes3D

    /**
     * Komprimiert 3D Biome-Array zu bytes (GZIP)
     * Format: [0x3E magic] [yLayers 2B] [yStart 2B] [yStep 1B] [biomes...]
     * FIX: Speichert Biome-NAMEN statt ordinal() - ordinal() ist nicht stabil!
     */
    private byte[] compressBiomes3D(Biome[][][] biomes3D, int yStart, int yStep) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             GZIPOutputStream gzip = new GZIPOutputStream(baos)) {

            int yLayers = biomes3D.length;

            // Magic byte für Format-Erkennung (0x3E = neues Name-basiertes Format)
            gzip.write(0x3E); // "3E" = 3D mit Namen statt Ordinals

            // Header
            gzip.write((yLayers >> 8) & 0xFF);
            gzip.write(yLayers & 0xFF);

            short yStartShort = (short) yStart;
            gzip.write((yStartShort >> 8) & 0xFF);
            gzip.write(yStartShort & 0xFF);

            gzip.write(yStep & 0xFF);

            // Biome-Daten: Namen statt ordinals
            for (int y = 0; y < yLayers; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        // NULL-CHECK: Fallback zu PLAINS wenn Biome null ist
                        Biome biome = biomes3D[y][x][z];
                        if (biome == null) {
                            plugin.getLogger().warning(plugin.getLanguageManager().getMessage("log.database.null-biome-snapshot", y, x, z));
                            biome = Biome.PLAINS;
                        }

                        // Use getKey() instead of deprecated name()
                        String biomeName = biome.getKey().getKey();
                        byte[] nameBytes = biomeName.getBytes(java.nio.charset.StandardCharsets.UTF_8);

                        // Schreibe Länge (1 byte)
                        gzip.write(nameBytes.length);
                        // Schreibe Name
                        gzip.write(nameBytes);
                    }
                }
            }

            gzip.finish();
            return baos.toByteArray();

        } catch (Exception e) {
            plugin.getLogger().severe(plugin.getLanguageManager().getMessage("log.database.error-compressing-3d", e.getMessage()));
            throw new RuntimeException(e);
        }
    }

    /**
     * Dekomprimiert 3D Biome-Array (GZIP)
     * Unterstützt beide Formate: 0x3D (alt, ordinal) und 0x3E (neu, Namen)
     */
    private BiomeSnapshot3D decompressBiomes3D(byte[] compressed) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
             GZIPInputStream gzip = new GZIPInputStream(bais)) {

            // Magic byte prüfen
            int magic = gzip.read();
            if (magic != 0x3D && magic != 0x3E) {
                plugin.getLogger().severe(plugin.getLanguageManager().getMessage("log.database.invalid-3d-format", magic));
                throw new RuntimeException("Invalid 3D biome format - expected 0x3D or 0x3E, got " + magic);
            }

            boolean isNameBased = (magic == 0x3E);

            // Header lesen
            int yLayersHi = gzip.read();
            int yLayersLo = gzip.read();
            int yLayers = (yLayersHi << 8) | yLayersLo;

            int yStartHi = gzip.read();
            int yStartLo = gzip.read();
            short yStartShort = (short) ((yStartHi << 8) | yStartLo);
            int yStart = yStartShort;

            int yStep = gzip.read();

            plugin.debug("3D-Snapshot: yLayers=" + yLayers + ", yStart=" + yStart + ", yStep=" + yStep + ", format=" + (isNameBased ? "Namen" : "Ordinals"));

            // Biome-Daten lesen
            Biome[][][] biomes = new Biome[yLayers][16][16];

            if (isNameBased) {
                // NEUES FORMAT: Namen-basiert (stabil!)
                for (int y = 0; y < yLayers; y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            int nameLength = gzip.read();
                            if (nameLength == -1) {
                                plugin.getLogger().warning(plugin.getLanguageManager().get("log.database.unexpected-end-3d"));
                                biomes[y][x][z] = Biome.PLAINS;
                                continue;
                            }

                            // Leerer Name? → Fallback zu PLAINS
                            if (nameLength == 0) {
                                plugin.getLogger().warning(plugin.getLanguageManager().getMessage("log.database.empty-3d-name", y, x, z));
                                biomes[y][x][z] = Biome.PLAINS;
                                continue;
                            }

                            // VALIDATION: Biome-Namen sollten maximal 50 Zeichen sein
                            // Wenn länger, ist vermutlich der Stream korrupt (Misalignment!)
                            if (nameLength > 50) {
                                plugin.getLogger().severe(plugin.getLanguageManager().getMessage("log.database.corrupted-3d-name-too-long", nameLength, y, x, z));
                                plugin.getLogger().severe(plugin.getLanguageManager().get("log.database.stream-misalignment-3d"));
                                plugin.getLogger().severe(plugin.getLanguageManager().get("log.database.solution-clearsnap"));
                                throw new RuntimeException("Database corruption detected - stream misalignment");
                            }

                            // CRITICAL FIX: InputStream.read() kann weniger Bytes lesen als angefordert!
                            // Wir müssen in einer Schleife lesen bis wir ALLE Bytes haben
                            byte[] nameBytes = new byte[nameLength];
                            int totalRead = 0;
                            while (totalRead < nameLength) {
                                int bytesRead = gzip.read(nameBytes, totalRead, nameLength - totalRead);
                                if (bytesRead == -1) {
                                    // Stream ended prematurely
                                    plugin.getLogger().warning(plugin.getLanguageManager().getMessage("log.database.stream-premature-end", nameLength, totalRead));
                                    biomes[y][x][z] = Biome.PLAINS;
                                    break; // Abbruch - Stream ist zu Ende
                                }
                                totalRead += bytesRead;
                            }

                            // Wenn wir nicht alle Bytes lesen konnten, überspringe diesen Eintrag
                            if (totalRead != nameLength) {
                                continue;
                            }

                            String biomeName = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);

                            // Prüfe ob Name leer oder null ist
                            if (biomeName == null || biomeName.trim().isEmpty()) {
                                plugin.getLogger().warning(plugin.getLanguageManager().get("log.database.invalid-3d-name-empty"));
                                biomes[y][x][z] = Biome.PLAINS;
                                continue;
                            }

                            try {
                                // Use Registry instead of deprecated valueOf
                                Biome biome = de.boondocksulfur.christmas.util.Registries.biomes().get(org.bukkit.NamespacedKey.minecraft(biomeName.toLowerCase()));
                                if (biome == null) {
                                    plugin.getLogger().warning(plugin.getLanguageManager().getMessage("log.database.unknown-3d-biome", biomeName));
                                    biomes[y][x][z] = Biome.PLAINS;
                                } else {
                                    biomes[y][x][z] = biome;
                                }
                            } catch (Exception e) {
                                // Fange ALLE Exceptions (inkl. NullPointerException bei NamespacedKey)
                                plugin.getLogger().warning(plugin.getLanguageManager().getMessage("log.database.error-parsing-biome", biomeName, e.getClass().getSimpleName()));
                                biomes[y][x][z] = Biome.PLAINS;
                            }
                        }
                    }
                }
            } else {
                // ALTES FORMAT: Ordinal-basiert (instabil - nur für Kompatibilität)
                plugin.getLogger().warning(plugin.getLanguageManager().get("log.database.old-format-warning"));
                // Use Registry stream instead of deprecated values()
                Biome[] allBiomes = de.boondocksulfur.christmas.util.Registries.biomes().stream().toArray(Biome[]::new);

                for (int y = 0; y < yLayers; y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            int ordinalHi = gzip.read();
                            int ordinalLo = gzip.read();

                            if (ordinalHi == -1 || ordinalLo == -1) {
                                plugin.getLogger().warning(plugin.getLanguageManager().get("log.database.unexpected-end-3d"));
                                biomes[y][x][z] = Biome.PLAINS;
                            } else {
                                int ordinal = (ordinalHi << 8) | ordinalLo;
                                if (ordinal < allBiomes.length) {
                                    biomes[y][x][z] = allBiomes[ordinal];
                                } else {
                                    plugin.getLogger().warning(plugin.getLanguageManager().getMessage("log.database.invalid-biome-ordinal", ordinal, allBiomes.length));
                                    biomes[y][x][z] = Biome.PLAINS;
                                }
                            }
                        }
                    }
                }
            }

            return new BiomeSnapshot3D(biomes, yStart, yStep);

        } catch (Exception e) {
            plugin.getLogger().severe(plugin.getLanguageManager().getMessage("log.database.error-decompressing-3d", e.getMessage()));
            throw new RuntimeException(e);
        }
    }

    /**
     * Einfache Klasse für Chunk-Koordinaten
     */
    public static class ChunkCoords {
        public final String world;
        public final int x;
        public final int z;

        public ChunkCoords(String world, int x, int z) {
            this.world = world;
            this.x = x;
            this.z = z;
        }
    }

    /**
     * Gibt alle Chunk-Koordinaten aus der Datenbank zurück
     */
    public synchronized java.util.List<ChunkCoords> getAllChunkCoordinates() throws SQLException {
        String sql = "SELECT world, x, z FROM chunks";
        java.util.List<ChunkCoords> result = new java.util.ArrayList<>();

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.add(new ChunkCoords(
                    rs.getString("world"),
                    rs.getInt("x"),
                    rs.getInt("z")
                ));
            }
        }

        return result;
    }

    /**
     * Gibt Statistiken über die Datenbank aus
     */
    public void printStats() {
        try {
            int count = getChunkCount();
            long sizeBytes = getDatabaseSize();
            double sizeMB = sizeBytes / (1024.0 * 1024.0);

            plugin.getLogger().info(plugin.getLanguageManager().get("log.database.stats-header"));
            plugin.getLogger().info(plugin.getLanguageManager().getMessage("log.database.stored-chunks", count));
            plugin.getLogger().info(plugin.getLanguageManager().getMessage("log.database.database-size", String.format("%.2f", sizeMB)));

            if (count > 0) {
                double avgBytesPerChunk = (double) sizeBytes / count;
                plugin.getLogger().info(plugin.getLanguageManager().getMessage("log.database.average-per-chunk", String.format("%.2f", avgBytesPerChunk / 1024.0)));
            }

            plugin.getLogger().info(plugin.getLanguageManager().get("log.database.stats-footer"));

        } catch (SQLException e) {
            plugin.getLogger().warning(plugin.getLanguageManager().getMessage("log.database.error-retrieving-stats", e.getMessage()));
        }
    }
}
