package de.boondocksulfur.christmas.manager;

import org.bukkit.NamespacedKey;
import org.bukkit.block.Biome;
import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.util.Registries;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.sql.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * SQLite-backed store for the original biomes of every chunk touched by the event.
 *
 * <p>All public methods are synchronised: the store is written from region threads
 * (snapshots) and read from the global thread (restore) at the same time.
 *
 * <p>Snapshot formats (first byte of the GZIP payload):
 * <ul>
 *   <li>{@code 0x3D} - legacy, biome ordinals (read-only)</li>
 *   <li>{@code 0x3E} - biome key without namespace (read-only, assumed {@code minecraft:})</li>
 *   <li>{@code 0x3F} - fully namespaced biome key per block (read-only)</li>
 *   <li>{@code 0x40} - namespaced key per 4x4 cell: 16 entries per layer instead of 256</li>
 *   <li>{@code 0x41} - like 0x40, preceded by two 256-bit masks of columns that already had a
 *       snow layer / ice on the surface before the conversion (those are kept on restore) (current)</li>
 * </ul>
 */
public class BiomeSnapshotDatabase {

    private static final int FORMAT_ORDINAL     = 0x3D;
    private static final int FORMAT_KEY_ONLY    = 0x3E;
    private static final int FORMAT_NAMESPACED  = 0x3F;
    private static final int FORMAT_CELLS       = 0x40;
    private static final int FORMAT_CELLS_MASKS = 0x41;
    /** Bytes of one 16x16 column mask. */
    public static final int MASK_BYTES = 32;
    private static final int CELL = 4;
    /** Longest biome key we accept; anything longer means the stream is misaligned. */
    private static final int MAX_KEY_LENGTH = 200;

    private final ChristmasSeason plugin;
    private final File dbFile;
    private Connection connection;

    /** Resolved biome keys; a snapshot holds ~10k entries, so registry lookups are cached. */
    private final Map<String, Biome> biomeCache = new ConcurrentHashMap<>();

    public BiomeSnapshotDatabase(ChristmasSeason plugin) {
        this(plugin, new File(plugin.getDataFolder(), "biome-snapshot.db"));
    }

    /** Opens a specific database file (used for reading backups). */
    public BiomeSnapshotDatabase(ChristmasSeason plugin, File customDbFile) {
        this.plugin = plugin;
        this.dbFile = customDbFile;
    }

    /** Opens the connection and creates the schema if needed. */
    public synchronized void open() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            plugin.getLanguageManager().logSevere("log.database.jdbc-not-found");
            throw new SQLException("SQLite driver not found", e);
        }

        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode = WAL");
            stmt.execute("PRAGMA synchronous = NORMAL");
            stmt.execute("PRAGMA cache_size = 10000");
            stmt.execute("PRAGMA temp_store = MEMORY");
        }

        createTable();
        plugin.getLanguageManager().logInfo("log.database.opened", dbFile.getName());
    }

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
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_timestamp ON chunks(timestamp)");
            // Columns where players placed snow layers / ice while the event was active
            stmt.execute("CREATE TABLE IF NOT EXISTS placed_columns (" +
                         "world TEXT NOT NULL, x INTEGER NOT NULL, z INTEGER NOT NULL, " +
                         "PRIMARY KEY (world, x, z))");
        }
    }

    // ------------------------------------------------------------ player placements

    /** Records that a player placed snow or ice in this block column (x/z are block coordinates). */
    public synchronized void markPlaced(String world, int blockX, int blockZ) throws SQLException {
        try (PreparedStatement pstmt = connection.prepareStatement(
                "INSERT OR IGNORE INTO placed_columns (world, x, z) VALUES (?, ?, ?)")) {
            pstmt.setString(1, world);
            pstmt.setInt(2, blockX);
            pstmt.setInt(3, blockZ);
            pstmt.executeUpdate();
        }
    }

    /**
     * @return 32-byte column mask of player placements inside the chunk, or {@code null} if none
     */
    public synchronized byte[] getPlacedMask(String world, int chunkX, int chunkZ) throws SQLException {
        int minX = chunkX << 4, minZ = chunkZ << 4;
        byte[] mask = null;
        try (PreparedStatement pstmt = connection.prepareStatement(
                "SELECT x, z FROM placed_columns WHERE world = ? AND x BETWEEN ? AND ? AND z BETWEEN ? AND ?")) {
            pstmt.setString(1, world);
            pstmt.setInt(2, minX);
            pstmt.setInt(3, minX + 15);
            pstmt.setInt(4, minZ);
            pstmt.setInt(5, minZ + 15);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    if (mask == null) mask = new byte[MASK_BYTES];
                    BiomeSnapshot3D.setBit(mask, rs.getInt(1) & 15, rs.getInt(2) & 15);
                }
            }
        }
        return mask;
    }

    /** Forgets the player placements of a chunk (after its restore). */
    public synchronized void deletePlaced(String world, int chunkX, int chunkZ) throws SQLException {
        int minX = chunkX << 4, minZ = chunkZ << 4;
        try (PreparedStatement pstmt = connection.prepareStatement(
                "DELETE FROM placed_columns WHERE world = ? AND x BETWEEN ? AND ? AND z BETWEEN ? AND ?")) {
            pstmt.setString(1, world);
            pstmt.setInt(2, minX);
            pstmt.setInt(3, minX + 15);
            pstmt.setInt(4, minZ);
            pstmt.setInt(5, minZ + 15);
            pstmt.executeUpdate();
        }
    }

    /** @return {@code true} while the connection is open */
    public synchronized boolean isOpen() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Flushes the write-ahead log into the main database file.
     *
     * <p>In WAL mode recent writes live in {@code biome-snapshot.db-wal} until SQLite
     * checkpoints them. Copying only the main file (as every backup does) would silently
     * lose those chunks, so this must be called before any file copy of an open database.
     *
     * @return {@code true} if the checkpoint ran
     */
    public synchronized boolean checkpoint() {
        if (!isOpen()) return false;
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            return true;
        } catch (SQLException e) {
            plugin.getLanguageManager().logWarning("log.database.checkpoint-failed", e.getMessage());
            return false;
        }
    }

    /** Stores a 3D chunk snapshot without surface masks. */
    public synchronized void saveChunk3D(String world, int x, int z, Biome[][][] biomes3D, int yStart, int yStep) throws SQLException {
        saveChunk3D(world, x, z, biomes3D, yStart, yStep, null, null);
    }

    /**
     * Stores a 3D chunk snapshot (GZIP compressed, namespaced biome keys per cell).
     *
     * @param snowMask 32 bytes, bit set = column already had a snow layer on the surface (may be null)
     * @param iceMask  32 bytes, bit set = column already had ice on the surface (may be null)
     */
    public synchronized void saveChunk3D(String world, int x, int z, Biome[][][] biomes3D, int yStart, int yStep,
                                         byte[] snowMask, byte[] iceMask) throws SQLException {
        if (biomes3D == null || biomes3D.length == 0) {
            throw new IllegalArgumentException("biomes3D must not be null or empty");
        }

        byte[] compressed = compressBiomes3D(biomes3D, yStart, yStep, snowMask, iceMask);

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

    /** Decoded 3D snapshot of one chunk. */
    public static class BiomeSnapshot3D {
        public final Biome[][][] biomes; // [layer][x][z]
        public final int yStart;
        public final int yStep;
        /** Columns that had a snow layer / ice on the surface before conversion; {@code null} for old snapshots. */
        public final byte[] snowMask, iceMask;

        public BiomeSnapshot3D(Biome[][][] biomes, int yStart, int yStep) {
            this(biomes, yStart, yStep, null, null);
        }

        public BiomeSnapshot3D(Biome[][][] biomes, int yStart, int yStep, byte[] snowMask, byte[] iceMask) {
            this.biomes = biomes;
            this.yStart = yStart;
            this.yStep = yStep;
            this.snowMask = snowMask;
            this.iceMask = iceMask;
        }

        private static boolean bit(byte[] mask, int x, int z) {
            if (mask == null) return false;
            int i = ((x & 15) << 4) | (z & 15);
            return (mask[i >> 3] & (1 << (i & 7))) != 0;
        }

        /** @return {@code true} if this column already had a snow layer before the event */
        public boolean hadSnow(int x, int z) { return bit(snowMask, x, z); }

        /** @return {@code true} if this column already had ice before the event */
        public boolean hadIce(int x, int z) { return bit(iceMask, x, z); }

        /** Sets a bit in a 32-byte column mask. */
        public static void setBit(byte[] mask, int x, int z) {
            int i = ((x & 15) << 4) | (z & 15);
            mask[i >> 3] |= (byte) (1 << (i & 7));
        }

        /** @return biome stored for the layer containing {@code y}, or {@code null} outside the stored range */
        public Biome getBiomeAtY(int x, int z, int y) {
            int layerIndex = (y - yStart) / yStep;
            if (layerIndex < 0 || layerIndex >= biomes.length) {
                return null;
            }
            return biomes[layerIndex][x & 15][z & 15];
        }

        /** @return exclusive upper Y bound covered by this snapshot */
        public int getYEnd() {
            return yStart + biomes.length * yStep;
        }
    }

    /** @return the chunk snapshot, or {@code null} if the chunk is not stored */
    public synchronized BiomeSnapshot3D loadChunk3D(String world, int x, int z) throws SQLException {
        String sql = "SELECT biomes FROM chunks WHERE world = ? AND x = ? AND z = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, world);
            pstmt.setInt(2, x);
            pstmt.setInt(3, z);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return decompressBiomes3D(rs.getBytes("biomes"));
                }
            }
        }
        return null;
    }

    /** @return {@code true} if a snapshot exists for the chunk */
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

    /** Deletes a chunk snapshot (after a successful restore). */
    public synchronized void deleteChunk(String world, int x, int z) throws SQLException {
        String sql = "DELETE FROM chunks WHERE world = ? AND x = ? AND z = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, world);
            pstmt.setInt(2, x);
            pstmt.setInt(3, z);
            pstmt.executeUpdate();
        }
    }

    /** @return number of stored chunks */
    public synchronized int getChunkCount() throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM chunks")) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        return 0;
    }

    /** @return size of the database on disk in bytes (main file plus write-ahead log) */
    public long getDatabaseSize() {
        long size = dbFile.exists() ? dbFile.length() : 0;
        File wal = new File(dbFile.getParentFile(), dbFile.getName() + "-wal");
        if (wal.exists()) size += wal.length();
        return size;
    }

    /** Deletes all snapshots and compacts the file. */
    public synchronized void clearAll() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM chunks");
            stmt.execute("DELETE FROM placed_columns");
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("VACUUM");
        }
        plugin.getLanguageManager().logInfo("log.database.cleared");
    }

    /** Closes the connection (SQLite checkpoints and removes the WAL file on close). */
    public synchronized void close() {
        if (connection != null) {
            try {
                if (!connection.isClosed()) {
                    connection.close();
                    plugin.getLanguageManager().logInfo("log.database.closed");
                }
            } catch (SQLException e) {
                plugin.getLanguageManager().logWarning("log.database.error-closing", e.getMessage());
            }
        }
    }

    /**
     * Compresses a 3D biome array at cell resolution.
     * Layout: {@code [0x40] [layers:2] [yStart:2] [yStep:1] then per layer 16 cells (x-major, 4x4 blocks each): [len:1][utf8 key]}.
     * Biomes are stored per 4x4x4 cell by Minecraft anyway, so sampling one block per cell loses nothing.
     */
    private byte[] compressBiomes3D(Biome[][][] biomes3D, int yStart, int yStep, byte[] snowMask, byte[] iceMask) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             GZIPOutputStream gzip = new GZIPOutputStream(baos)) {

            int yLayers = biomes3D.length;

            gzip.write(FORMAT_CELLS_MASKS);
            gzip.write((yLayers >> 8) & 0xFF);
            gzip.write(yLayers & 0xFF);
            short yStartShort = (short) yStart;
            gzip.write((yStartShort >> 8) & 0xFF);
            gzip.write(yStartShort & 0xFF);
            gzip.write(yStep & 0xFF);
            gzip.write(snowMask != null && snowMask.length == MASK_BYTES ? snowMask : new byte[MASK_BYTES]);
            gzip.write(iceMask != null && iceMask.length == MASK_BYTES ? iceMask : new byte[MASK_BYTES]);

            for (int y = 0; y < yLayers; y++) {
                for (int x = 0; x < 16; x += CELL) {
                    for (int z = 0; z < 16; z += CELL) {
                        Biome biome = biomes3D[y][x][z];
                        if (biome == null) {
                            plugin.getLanguageManager().logWarning("log.database.null-biome-snapshot", y, x, z);
                            biome = Biome.PLAINS;
                        }
                        byte[] nameBytes = biome.getKey().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        if (nameBytes.length > MAX_KEY_LENGTH) {
                            throw new IllegalStateException("Biome key too long: " + biome.getKey());
                        }
                        gzip.write(nameBytes.length);
                        gzip.write(nameBytes);
                    }
                }
            }

            gzip.finish();
            return baos.toByteArray();

        } catch (Exception e) {
            plugin.getLanguageManager().logSevere("log.database.error-compressing-3d", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /** Decompresses a snapshot of any supported format. */
    private BiomeSnapshot3D decompressBiomes3D(byte[] compressed) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
             GZIPInputStream gzip = new GZIPInputStream(bais)) {

            int magic = gzip.read();
            if (magic != FORMAT_ORDINAL && magic != FORMAT_KEY_ONLY && magic != FORMAT_NAMESPACED
                    && magic != FORMAT_CELLS && magic != FORMAT_CELLS_MASKS) {
                plugin.getLanguageManager().logSevere("log.database.invalid-3d-format", magic);
                throw new RuntimeException("Invalid 3D biome format - expected 0x3D..0x41, got " + magic);
            }

            int yLayersHi = gzip.read();
            int yLayersLo = gzip.read();
            int yStartHi = gzip.read();
            int yStartLo = gzip.read();
            int yStep = gzip.read();
            if (yLayersHi == -1 || yLayersLo == -1 || yStartHi == -1 || yStartLo == -1 || yStep == -1) {
                throw new RuntimeException("Corrupt 3D biome snapshot - unexpected EOF in header");
            }

            int yLayers = (yLayersHi << 8) | yLayersLo;
            int yStart = (short) ((yStartHi << 8) | yStartLo);
            if (yStep <= 0) {
                throw new RuntimeException("Corrupt 3D biome snapshot - invalid yStep " + yStep);
            }

            Biome[][][] biomes = new Biome[yLayers][16][16];
            byte[] snowMask = null, iceMask = null;

            if (magic == FORMAT_CELLS_MASKS) {
                snowMask = gzip.readNBytes(MASK_BYTES);
                iceMask = gzip.readNBytes(MASK_BYTES);
                if (snowMask.length != MASK_BYTES || iceMask.length != MASK_BYTES) {
                    throw new RuntimeException("Corrupt 3D biome snapshot - unexpected EOF in surface masks");
                }
            }

            if (magic == FORMAT_ORDINAL) {
                readOrdinalFormat(gzip, biomes, yLayers);
            } else if (magic == FORMAT_CELLS || magic == FORMAT_CELLS_MASKS) {
                readCellFormat(gzip, biomes, yLayers);
            } else {
                readKeyFormat(gzip, biomes, yLayers, magic == FORMAT_NAMESPACED);
            }

            return new BiomeSnapshot3D(biomes, yStart, yStep, snowMask, iceMask);

        } catch (Exception e) {
            plugin.getLanguageManager().logSevere("log.database.error-decompressing-3d", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /** Reads the {@code 0x40} cell format and expands every cell to its 4x4 blocks. */
    private void readCellFormat(GZIPInputStream gzip, Biome[][][] biomes, int yLayers) throws java.io.IOException {
        for (int y = 0; y < yLayers; y++) {
            for (int x = 0; x < 16; x += CELL) {
                for (int z = 0; z < 16; z += CELL) {
                    Biome biome = readKey(gzip, y, x, z, true);
                    for (int cx = 0; cx < CELL; cx++) {
                        for (int cz = 0; cz < CELL; cz++) {
                            biomes[y][x + cx][z + cz] = biome;
                        }
                    }
                }
            }
        }
    }

    /** Reads one length-prefixed key entry; corrupt or truncated data yields PLAINS (or throws on misalignment). */
    private Biome readKey(GZIPInputStream gzip, int y, int x, int z, boolean namespaced) throws java.io.IOException {
        int nameLength = gzip.read();
        if (nameLength == -1) {
            plugin.getLanguageManager().logWarning("log.database.unexpected-end-3d");
            return Biome.PLAINS;
        }
        if (nameLength == 0) {
            plugin.getLanguageManager().logWarning("log.database.empty-3d-name", y, x, z);
            return Biome.PLAINS;
        }
        if (nameLength > MAX_KEY_LENGTH) {
            plugin.getLanguageManager().logSevere("log.database.corrupted-3d-name-too-long", nameLength, y, x, z);
            plugin.getLanguageManager().logSevere("log.database.stream-misalignment-3d");
            plugin.getLanguageManager().logSevere("log.database.solution-clearsnap");
            throw new RuntimeException("Database corruption detected - stream misalignment");
        }
        byte[] nameBytes = new byte[nameLength];
        int totalRead = 0;
        while (totalRead < nameLength) {
            int bytesRead = gzip.read(nameBytes, totalRead, nameLength - totalRead);
            if (bytesRead == -1) {
                plugin.getLanguageManager().logWarning("log.database.stream-premature-3d", nameLength, totalRead);
                return Biome.PLAINS;
            }
            totalRead += bytesRead;
        }
        return resolveBiome(new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8), namespaced);
    }

    /** Reads the {@code 0x3E} (key only) and {@code 0x3F} (namespaced) formats. */
    private void readKeyFormat(GZIPInputStream gzip, Biome[][][] biomes, int yLayers, boolean namespaced) throws java.io.IOException {
        for (int y = 0; y < yLayers; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    biomes[y][x][z] = readKey(gzip, y, x, z, namespaced);
                }
            }
        }
    }

    /** Reads the legacy {@code 0x3D} ordinal format (registry order, unstable across versions). */
    private void readOrdinalFormat(GZIPInputStream gzip, Biome[][][] biomes, int yLayers) throws java.io.IOException {
        plugin.getLanguageManager().logWarning("log.database.old-format-warning");
        Biome[] allBiomes = Registries.biomes().stream().toArray(Biome[]::new);

        for (int y = 0; y < yLayers; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int ordinalHi = gzip.read();
                    int ordinalLo = gzip.read();
                    if (ordinalHi == -1 || ordinalLo == -1) {
                        plugin.getLanguageManager().logWarning("log.database.unexpected-end-3d");
                        biomes[y][x][z] = Biome.PLAINS;
                        continue;
                    }
                    int ordinal = (ordinalHi << 8) | ordinalLo;
                    if (ordinal < allBiomes.length) {
                        biomes[y][x][z] = allBiomes[ordinal];
                    } else {
                        plugin.getLanguageManager().logWarning("log.database.invalid-biome-ordinal", ordinal, allBiomes.length);
                        biomes[y][x][z] = Biome.PLAINS;
                    }
                }
            }
        }
    }

    /**
     * Resolves a stored biome name through the registry (cached).
     * Unknown biomes (e.g. a removed data pack) fall back to PLAINS with a warning.
     */
    private Biome resolveBiome(String name, boolean namespaced) {
        String cacheKey = (namespaced ? "" : "minecraft:") + name.toLowerCase();
        Biome cached = biomeCache.get(cacheKey);
        if (cached != null) return cached;

        Biome biome = null;
        try {
            NamespacedKey key = NamespacedKey.fromString(cacheKey);
            if (key != null) {
                biome = Registries.biomes().get(key);
            }
        } catch (Exception e) {
            plugin.getLanguageManager().logWarning("log.database.error-parsing-biome", cacheKey, e.getClass().getSimpleName());
        }
        if (biome == null) {
            plugin.getLanguageManager().logWarning("log.database.unknown-3d-biome", cacheKey);
            biome = Biome.PLAINS;
        }
        biomeCache.put(cacheKey, biome);
        return biome;
    }

    /** Chunk coordinates stored in the database. */
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

    /** @return coordinates of all stored chunks */
    public synchronized java.util.List<ChunkCoords> getAllChunkCoordinates() throws SQLException {
        java.util.List<ChunkCoords> result = new java.util.ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT world, x, z FROM chunks")) {
            while (rs.next()) {
                result.add(new ChunkCoords(rs.getString("world"), rs.getInt("x"), rs.getInt("z")));
            }
        }
        return result;
    }

    /** Logs chunk count and file size. */
    public void printStats() {
        try {
            int count = getChunkCount();
            long sizeBytes = getDatabaseSize();
            double sizeMB = sizeBytes / (1024.0 * 1024.0);

            plugin.getLanguageManager().logInfo("log.database.stats-header");
            plugin.getLanguageManager().logInfo("log.database.stored-chunks", count);
            plugin.getLanguageManager().logInfo("log.database.database-size", String.format(java.util.Locale.ROOT, "%.2f", sizeMB));
            if (count > 0) {
                plugin.getLanguageManager().logInfo("log.database.average-per-chunk",
                        String.format(java.util.Locale.ROOT, "%.2f", ((double) sizeBytes / count) / 1024.0));
            }
            plugin.getLanguageManager().logInfo("log.database.stats-footer");
        } catch (SQLException e) {
            plugin.getLanguageManager().logWarning("log.database.error-retrieving-stats", e.getMessage());
        }
    }
}
