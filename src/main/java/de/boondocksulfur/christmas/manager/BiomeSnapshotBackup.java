package de.boondocksulfur.christmas.manager;

import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.util.LanguageManager;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Backup and restore of the biome snapshot database.
 *
 * <p>Backup types (all stored in {@code <world>/christmas_backups/}, outside the plugin folder):
 * <ul>
 *   <li>SAFE - written by {@code /xmas on} before any chunk is modified; always overwritten</li>
 *   <li>timestamp - written by {@code /xmas off} before the restore and by {@code /xmas backup create}; rotated</li>
 *   <li>EMERGENCY - written on plugin disable while the event is still active; rotated</li>
 * </ul>
 *
 * <p>The database runs in WAL mode, so every copy first forces a checkpoint - otherwise
 * the copy would miss all chunks still sitting in the write-ahead log.
 */
public class BiomeSnapshotBackup {

    private final ChristmasSeason plugin;
    private final LanguageManager lang;
    private final File backupDir;
    private final File dbFile;
    private final File safeBackupFile;

    private static final int MAX_BACKUPS = 5;
    private static final int MAX_EMERGENCY_BACKUPS = 3;
    private static final String SAFE_BACKUP_NAME = "biome_snapshot_SAFE.db";
    private static final String TIMESTAMP_PREFIX = "biome_snapshot_backup_";
    private static final String EMERGENCY_PREFIX = "biome_snapshot_EMERGENCY_";
    private static final String REPLACED_PREFIX = "biome_snapshot_REPLACED_";
    private static final SimpleDateFormat TIMESTAMP_FORMAT = new SimpleDateFormat("yyyyMMdd_HHmmss");

    public BiomeSnapshotBackup(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();

        String worldName = plugin.getConfig().getString("snowWorld", "world");
        org.bukkit.World world = Bukkit.getWorld(worldName);
        File worldFolder = world != null ? world.getWorldFolder() : new File(worldName);
        this.backupDir = new File(worldFolder, "christmas_backups");

        this.dbFile = new File(plugin.getDataFolder(), "biome-snapshot.db");
        this.safeBackupFile = new File(backupDir, SAFE_BACKUP_NAME);

        if (!backupDir.exists() && backupDir.mkdirs()) {
            lang.logInfo("log.backup.directory-created", backupDir.getAbsolutePath());
        }
    }

    // ---------------------------------------------------------------- copying

    /**
     * Copies the database file to {@code target}, flushing the WAL first.
     *
     * <p>If the manager currently holds the database open, the checkpoint runs through
     * that connection. Otherwise a short-lived connection is opened so that a WAL file
     * left behind by a crash is merged into the main file before copying.
     */
    private void copyDatabase(File target) throws IOException {
        flushWriteAheadLog();
        Files.copy(dbFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /** Ensures the main database file contains everything (see class Javadoc). */
    private void flushWriteAheadLog() {
        BiomeSnowManager manager = plugin.getBiomeSnowManager();
        BiomeSnapshotDatabase open = manager != null ? manager.getDatabase() : null;
        if (open != null && open.isOpen()) {
            open.checkpoint();
            return;
        }

        File wal = new File(dbFile.getParentFile(), dbFile.getName() + "-wal");
        if (!wal.exists()) return;

        // Closed database with a leftover WAL (crash): open + close merges and deletes it
        BiomeSnapshotDatabase temp = new BiomeSnapshotDatabase(plugin, dbFile);
        try {
            temp.open();
            temp.checkpoint();
        } catch (Exception e) {
            lang.logWarning("log.database.checkpoint-failed", e.getMessage());
        } finally {
            temp.close();
        }
    }

    // ---------------------------------------------------------------- creating

    /**
     * Writes the SAFE backup (called by {@code /xmas on} before chunks are modified).
     * Always overwrites the previous SAFE backup - it is the last known good state.
     *
     * @return {@code true} on success, {@code false} if there is no database or the copy failed
     */
    public boolean createSafeBackup() {
        if (!dbFile.exists()) {
            plugin.debug("No SAFE backup created - database does not exist yet");
            return false;
        }
        try {
            copyDatabase(safeBackupFile);
            lang.logInfo("log.backup.safe-created", backupDir.getName(), safeBackupFile.length() / 1024);
            plugin.debug("SAFE backup: " + safeBackupFile.getAbsolutePath());
            return true;
        } catch (IOException e) {
            lang.logWarning("log.backup.error-creating-safe", e.getMessage());
            if (plugin.isDebugMode()) e.printStackTrace();
            return false;
        }
    }

    /**
     * Writes a timestamped backup ({@code biome_snapshot_backup_YYYYMMDD_HHMMSS.db}) and rotates.
     *
     * @return {@code true} on success
     */
    public boolean createTimestampBackup() {
        if (!dbFile.exists()) {
            plugin.debug("No timestamp backup created - database does not exist");
            return false;
        }
        try {
            String timestamp = TIMESTAMP_FORMAT.format(new Date());
            File backupFile = new File(backupDir, TIMESTAMP_PREFIX + timestamp + ".db");
            copyDatabase(backupFile);
            lang.logInfo("log.backup.timestamp-created", timestamp, backupFile.length() / 1024);
            plugin.debug("Timestamp backup: " + backupFile.getAbsolutePath());
            rotateBackups();
            return true;
        } catch (IOException e) {
            lang.logWarning("log.backup.error-creating-timestamp", e.getMessage());
            if (plugin.isDebugMode()) e.printStackTrace();
            return false;
        }
    }

    /**
     * Writes an EMERGENCY backup. Called from {@code onDisable()} when the server stops
     * while the event is active - <em>after</em> the database has been closed, so the
     * copy is complete. Only the newest {@value #MAX_EMERGENCY_BACKUPS} are kept.
     *
     * @return {@code true} on success
     */
    public boolean createEmergencyBackup() {
        if (!dbFile.exists()) {
            return false;
        }
        try {
            String timestamp = TIMESTAMP_FORMAT.format(new Date());
            File emergencyFile = new File(backupDir, EMERGENCY_PREFIX + timestamp + ".db");
            copyDatabase(emergencyFile);
            lang.logWarning("log.backup.emergency-created", emergencyFile.getName(), emergencyFile.length() / 1024);
            rotateEmergencyBackups();
            return true;
        } catch (IOException e) {
            lang.logSevere("log.backup.error-creating-emergency", e.getMessage());
            if (plugin.isDebugMode()) e.printStackTrace();
            return false;
        }
    }

    // ---------------------------------------------------------------- rotation

    /**
     * Keeps only the newest {@value #MAX_BACKUPS} timestamp backups.
     * The largest backup is never deleted - it is most likely the most complete state.
     */
    private void rotateBackups() {
        List<File> backups = listTimestampBackups();
        if (backups.size() <= MAX_BACKUPS) return;

        int toDelete = backups.size() - MAX_BACKUPS;
        plugin.debug("Backup rotation: deleting " + toDelete + " old backup(s)");

        File largestBackup = null;
        long largestSize = 0;
        for (File backup : backups) {
            if (backup.length() > largestSize) {
                largestSize = backup.length();
                largestBackup = backup;
            }
        }

        int deleted = 0;
        for (int i = 0; i < backups.size() && deleted < toDelete; i++) {
            File oldBackup = backups.get(i);
            if (oldBackup.equals(largestBackup)) {
                plugin.debug("Keeping largest backup: " + oldBackup.getName());
                continue;
            }
            if (oldBackup.delete()) {
                plugin.debug("Deleted: " + oldBackup.getName());
                deleted++;
            } else {
                lang.logWarning("log.backup.error-deleting", oldBackup.getName());
            }
        }
    }

    /** Keeps only the newest {@value #MAX_EMERGENCY_BACKUPS} emergency backups. */
    private void rotateEmergencyBackups() {
        List<File> emergencies = listFiles(EMERGENCY_PREFIX);
        int toDelete = emergencies.size() - MAX_EMERGENCY_BACKUPS;
        for (int i = 0; i < toDelete; i++) {
            File old = emergencies.get(i);
            if (!old.delete()) {
                lang.logWarning("log.backup.error-deleting", old.getName());
            }
        }
    }

    // ---------------------------------------------------------------- listing

    private List<File> listFiles(String prefix) {
        if (!backupDir.exists()) return new ArrayList<>();
        File[] files = backupDir.listFiles((dir, name) -> name.startsWith(prefix) && name.endsWith(".db"));
        if (files == null || files.length == 0) return new ArrayList<>();
        List<File> list = new ArrayList<>(Arrays.asList(files));
        list.sort(Comparator.comparing(File::getName)); // name contains the timestamp: oldest first
        return list;
    }

    /** @return timestamp backups, oldest first */
    public List<File> listTimestampBackups() {
        return listFiles(TIMESTAMP_PREFIX);
    }

    /** @return emergency backups, oldest first */
    public List<File> listEmergencyBackups() {
        return listFiles(EMERGENCY_PREFIX);
    }

    /**
     * @return all backups keyed by their ID as shown in {@code /xmas backup list}
     *         (SAFE, the timestamp, or EMERGENCY_&lt;timestamp&gt;)
     */
    public Map<String, File> listAllBackups() {
        Map<String, File> allBackups = new LinkedHashMap<>();
        if (safeBackupFile.exists()) {
            allBackups.put("SAFE", safeBackupFile);
        }
        for (File backup : listTimestampBackups()) {
            allBackups.put(backup.getName().replace(TIMESTAMP_PREFIX, "").replace(".db", ""), backup);
        }
        for (File emergency : listEmergencyBackups()) {
            allBackups.put("EMERGENCY_" + emergency.getName().replace(EMERGENCY_PREFIX, "").replace(".db", ""), emergency);
        }
        return allBackups;
    }

    // ---------------------------------------------------------------- restoring

    /**
     * Replaces the active database with a backup. The current database is kept as
     * {@code biome_snapshot_REPLACED_<timestamp>.db}.
     *
     * <p>Should only be used while the event is inactive; while active, the snapshot
     * manager is stopped and restarted around the copy.
     *
     * @return {@code true} on success
     */
    public boolean restoreBackup(File backupFile) {
        if (!backupFile.exists()) {
            lang.logWarning("log.backup.file-not-found", backupFile.getName());
            return false;
        }

        if (plugin.isActive()) {
            lang.logWarning("log.backup.restore-while-active");
        }

        try {
            BiomeSnapshotDatabase db = plugin.getBiomeSnowManager().getDatabase();
            if (db != null) {
                lang.logInfo("log.backup.closing-database");
                plugin.getBiomeSnowManager().stop(true);
            }

            // Merge and remove any leftover WAL so it cannot be replayed onto the new file
            flushWriteAheadLog();

            if (dbFile.exists()) {
                File oldDbBackup = new File(backupDir, REPLACED_PREFIX + TIMESTAMP_FORMAT.format(new Date()) + ".db");
                Files.copy(dbFile.toPath(), oldDbBackup.toPath(), StandardCopyOption.REPLACE_EXISTING);
                plugin.debug("Current database kept as: " + oldDbBackup.getName());
            }

            Files.copy(backupFile.toPath(), dbFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            lang.logInfo("log.backup.restored", backupFile.getName(), dbFile.length() / 1024);

            if (plugin.isActive() && plugin.getConfig().getBoolean("biome.enableSnapshot", true)) {
                plugin.getBiomeSnowManager().start();
                lang.logInfo("log.backup.database-reopened");
            }
            return true;
        } catch (IOException e) {
            lang.logSevere("log.backup.error-restoring", e.getMessage());
            if (plugin.isDebugMode()) e.printStackTrace();
            return false;
        }
    }

    /**
     * Deletes all timestamp and emergency backups (the SAFE backup is kept).
     *
     * @return number of deleted files
     */
    public int clearAllBackups() {
        int deleted = 0;
        for (File backup : listTimestampBackups()) {
            if (backup.delete()) deleted++;
        }
        for (File emergency : listEmergencyBackups()) {
            if (emergency.delete()) deleted++;
        }
        lang.logInfo("log.backup.cleared", deleted);
        return deleted;
    }

    public boolean hasSafeBackup() {
        return safeBackupFile.exists();
    }

    public boolean hasDatabaseFile() {
        return dbFile.exists();
    }

    public File getBackupDirectory() {
        return backupDir;
    }
}
