package de.boondocksulfur.christmas.manager;

import de.boondocksulfur.christmas.ChristmasSeason;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Manager für Backup/Restore der Biome-Snapshot-Datenbank
 *
 * Features:
 * - Automatisches SAFE-Backup beim /xmas on (vor Snapshot-Erstellung)
 * - Automatisches Timestamp-Backup beim /xmas off (nach erfolgreichem Restore)
 * - Backup-Rotation (max 5 Backups)
 * - Backups werden außerhalb des plugins/-Ordners gespeichert (world-Folder)
 * - Wiederherstellung aus Backup möglich
 *
 * Schutz vor:
 * - Versehentlichem Löschen der Datenbank
 * - Datenbank-Korruption
 * - Server-Crash während /xmas on/off
 * - Plugin-Löschung während aktiv
 */
public class BiomeSnapshotBackup {

    private final ChristmasSeason plugin;
    private final File backupDir;
    private final File dbFile;
    private final File safeBackupFile;

    private static final int MAX_BACKUPS = 5; // Maximale Anzahl rotierender Backups
    private static final String SAFE_BACKUP_NAME = "biome_snapshot_SAFE.db";
    private static final SimpleDateFormat TIMESTAMP_FORMAT = new SimpleDateFormat("yyyyMMdd_HHmmss");

    public BiomeSnapshotBackup(ChristmasSeason plugin) {
        this.plugin = plugin;

        // Backup-Verzeichnis: world/christmas_backups/ (außerhalb plugins/!)
        String worldName = plugin.getConfig().getString("snowWorld", "world");
        org.bukkit.World world = Bukkit.getWorld(worldName);
        File worldFolder = world != null ? world.getWorldFolder() : new File(worldName);
        this.backupDir = new File(worldFolder, "christmas_backups");

        // Datenbank-Datei im Plugin-Ordner
        this.dbFile = new File(plugin.getDataFolder(), "biome-snapshot.db");

        // SAFE-Backup Datei
        this.safeBackupFile = new File(backupDir, SAFE_BACKUP_NAME);

        // Erstelle Backup-Verzeichnis falls nötig
        if (!backupDir.exists()) {
            backupDir.mkdirs();
            plugin.getLogger().info(plugin.getLanguageManager().getMessage("log.backup.directory-created", backupDir.getAbsolutePath()));
        }
    }

    /**
     * Erstellt ein SAFE-Backup der aktuellen Datenbank
     * Wird beim /xmas on aufgerufen (BEVOR Chunks geändert werden)
     *
     * WICHTIG: Überschreibt immer das vorherige SAFE-Backup!
     * Dieses Backup ist der letzte bekannte GUTE Zustand.
     *
     * @return true wenn erfolgreich
     */
    public boolean createSafeBackup() {
        if (!dbFile.exists()) {
            plugin.debug("Kein SAFE-Backup erstellt - Datenbank existiert noch nicht");
            return false;
        }

        try {
            Path source = dbFile.toPath();
            Path target = safeBackupFile.toPath();

            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);

            long sizeKB = safeBackupFile.length() / 1024;
            plugin.getLogger().info(plugin.getLanguageManager().getMessage("log.backup.safe-created",
                backupDir.getName(), sizeKB));
            plugin.debug("SAFE-Backup: " + safeBackupFile.getAbsolutePath());

            return true;
        } catch (IOException e) {
            plugin.getLogger().warning(plugin.getLanguageManager().getMessage("log.backup.error-creating-safe", e.getMessage()));
            if (plugin.isDebugMode()) e.printStackTrace();
            return false;
        }
    }

    /**
     * Erstellt ein Timestamp-Backup der aktuellen Datenbank
     * Wird beim /xmas off aufgerufen (NACH erfolgreichem Restore)
     *
     * Backup-Name: biome_snapshot_backup_YYYYMMDD_HHMMSS.db
     * Rotiert automatisch (max 5 Backups)
     *
     * @return true wenn erfolgreich
     */
    public boolean createTimestampBackup() {
        if (!dbFile.exists()) {
            plugin.debug("Kein Timestamp-Backup erstellt - Datenbank existiert nicht");
            return false;
        }

        try {
            String timestamp = TIMESTAMP_FORMAT.format(new Date());
            File backupFile = new File(backupDir, "biome_snapshot_backup_" + timestamp + ".db");

            Path source = dbFile.toPath();
            Path target = backupFile.toPath();

            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);

            long sizeKB = backupFile.length() / 1024;
            plugin.getLogger().info(plugin.getLanguageManager().getMessage("log.backup.timestamp-created",
                timestamp, sizeKB));
            plugin.debug("Timestamp-Backup: " + backupFile.getAbsolutePath());

            // Backup-Rotation durchführen
            rotateBackups();

            return true;
        } catch (IOException e) {
            plugin.getLogger().warning(plugin.getLanguageManager().getMessage("log.backup.error-creating-timestamp", e.getMessage()));
            if (plugin.isDebugMode()) e.printStackTrace();
            return false;
        }
    }

    /**
     * Erstellt ein Notfall-Backup beim onDisable()
     * Wird aufgerufen wenn Server stoppt WÄHREND /xmas on aktiv ist
     *
     * @return true wenn erfolgreich
     */
    public boolean createEmergencyBackup() {
        if (!dbFile.exists()) {
            return false;
        }

        try {
            String timestamp = TIMESTAMP_FORMAT.format(new Date());
            File emergencyFile = new File(backupDir, "biome_snapshot_EMERGENCY_" + timestamp + ".db");

            Path source = dbFile.toPath();
            Path target = emergencyFile.toPath();

            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);

            long sizeKB = emergencyFile.length() / 1024;
            plugin.getLogger().severe(plugin.getLanguageManager().getMessage("log.backup.emergency-created",
                emergencyFile.getName(), sizeKB));
            plugin.getLogger().severe(plugin.getLanguageManager().get("log.backup.emergency-warning"));

            return true;
        } catch (IOException e) {
            plugin.getLogger().severe(plugin.getLanguageManager().getMessage("log.backup.error-creating-emergency", e.getMessage()));
            if (plugin.isDebugMode()) e.printStackTrace();
            return false;
        }
    }

    /**
     * Rotiert Backups (behält nur die neuesten MAX_BACKUPS)
     * SCHUTZ: Löscht nur kleine Backups, behält das größte Backup immer!
     * (Verhindert Verlust des besten Recovery-Points bei wiederholten fehlerhaften Restores)
     */
    private void rotateBackups() {
        List<File> backups = listTimestampBackups();

        if (backups.size() > MAX_BACKUPS) {
            int toDelete = backups.size() - MAX_BACKUPS;
            plugin.debug("Backup-Rotation: " + toDelete + " alte Backups werden gelöscht");

            // SCHUTZ: Finde das größte Backup (wahrscheinlich der vollständigste Zustand)
            File largestBackup = null;
            long largestSize = 0;
            for (File backup : backups) {
                if (backup.length() > largestSize) {
                    largestSize = backup.length();
                    largestBackup = backup;
                }
            }

            // Lösche älteste Backups, aber NICHT das größte!
            int deleted = 0;
            for (int i = 0; i < backups.size() && deleted < toDelete; i++) {
                File oldBackup = backups.get(i);
                if (oldBackup.equals(largestBackup)) {
                    plugin.debug("Überspringe größtes Backup (Schutz): " + oldBackup.getName() + " (" + (oldBackup.length() / 1024) + " KB)");
                    continue;
                }
                if (oldBackup.delete()) {
                    plugin.debug("Gelöscht: " + oldBackup.getName());
                    deleted++;
                } else {
                    plugin.getLogger().warning(plugin.getLanguageManager().getMessage("log.backup.error-deleting", oldBackup.getName()));
                }
            }
        }
    }

    /**
     * Listet alle verfügbaren Timestamp-Backups auf
     * Sortiert nach Datum (älteste zuerst)
     *
     * @return Liste der Backup-Dateien
     */
    public List<File> listTimestampBackups() {
        if (!backupDir.exists()) {
            return new ArrayList<>();
        }

        File[] files = backupDir.listFiles((dir, name) ->
            name.startsWith("biome_snapshot_backup_") && name.endsWith(".db"));

        if (files == null || files.length == 0) {
            return new ArrayList<>();
        }

        // Sortiere nach Datum (Name enthält Timestamp)
        List<File> backups = Arrays.asList(files);
        backups.sort(Comparator.comparing(File::getName));

        return backups;
    }

    /**
     * Listet alle verfügbaren Backups auf (inkl. SAFE und EMERGENCY)
     *
     * @return Map mit Backup-Typ → Datei
     */
    public Map<String, File> listAllBackups() {
        Map<String, File> allBackups = new LinkedHashMap<>();

        // SAFE-Backup
        if (safeBackupFile.exists()) {
            allBackups.put("SAFE", safeBackupFile);
        }

        // Timestamp-Backups
        List<File> timestamps = listTimestampBackups();
        for (File backup : timestamps) {
            String name = backup.getName()
                .replace("biome_snapshot_backup_", "")
                .replace(".db", "");
            allBackups.put(name, backup);
        }

        // Emergency-Backups
        File[] emergencyFiles = backupDir.listFiles((dir, name) ->
            name.startsWith("biome_snapshot_EMERGENCY_") && name.endsWith(".db"));
        if (emergencyFiles != null) {
            for (File emergency : emergencyFiles) {
                String name = "EMERGENCY_" + emergency.getName()
                    .replace("biome_snapshot_EMERGENCY_", "")
                    .replace(".db", "");
                allBackups.put(name, emergency);
            }
        }

        return allBackups;
    }

    /**
     * Stellt ein Backup wieder her
     * WICHTIG: Schließt die Datenbank BEVOR das Backup wiederhergestellt wird!
     * WARNUNG: Sollte nur verwendet werden wenn Plugin INAKTIV ist!
     *
     * @param backupFile Backup-Datei zum Wiederherstellen
     * @return true wenn erfolgreich
     */
    public boolean restoreBackup(File backupFile) {
        if (!backupFile.exists()) {
            plugin.getLogger().warning(plugin.getLanguageManager().getMessage("log.backup.file-not-found", backupFile.getName()));
            return false;
        }

        // FIX: Warnung wenn Plugin aktiv ist (Race Condition Gefahr!)
        if (plugin.isActive()) {
            plugin.getLogger().warning("§c§lWARNUNG: Backup-Restore während Plugin AKTIV ist!");
            plugin.getLogger().warning("§cEs kann zu Race Conditions mit BiomeSnowManager kommen.");
            plugin.getLogger().warning("§cEmpfehlung: Führe erst '/xmas off' aus, dann restore.");
        }

        try {
            // WICHTIG: Datenbank muss geschlossen sein!
            BiomeSnapshotDatabase db = plugin.getBiomeSnowManager().getDatabase();
            if (db != null) {
                plugin.getLogger().info(plugin.getLanguageManager().get("log.backup.closing-database"));
                db.close();
            }

            // Backup der aktuellen DB (falls vorhanden)
            if (dbFile.exists()) {
                String timestamp = TIMESTAMP_FORMAT.format(new Date());
                File oldDbBackup = new File(backupDir, "biome_snapshot_REPLACED_" + timestamp + ".db");
                Files.copy(dbFile.toPath(), oldDbBackup.toPath(), StandardCopyOption.REPLACE_EXISTING);
                plugin.debug("Aktuelle DB gesichert als: " + oldDbBackup.getName());
            }

            // Restore Backup → aktive Datenbank
            Files.copy(backupFile.toPath(), dbFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            long sizeKB = dbFile.length() / 1024;
            plugin.getLogger().info(plugin.getLanguageManager().getMessage("log.backup.restored",
                backupFile.getName(), sizeKB));

            // Datenbank wieder öffnen
            if (plugin.isActive() && plugin.getConfig().getBoolean("biome.enableSnapshot", true)) {
                plugin.getBiomeSnowManager().stop(false); // Ohne DB zu schließen
                plugin.getBiomeSnowManager().start();
                plugin.getLogger().info(plugin.getLanguageManager().get("log.backup.database-reopened"));
            }

            return true;
        } catch (IOException e) {
            plugin.getLogger().severe(plugin.getLanguageManager().getMessage("log.backup.error-restoring", e.getMessage()));
            if (plugin.isDebugMode()) e.printStackTrace();
            return false;
        }
    }

    /**
     * Löscht alle Backups (außer SAFE-Backup)
     *
     * @return Anzahl gelöschter Backups
     */
    public int clearAllBackups() {
        List<File> backups = listTimestampBackups();
        int deleted = 0;

        for (File backup : backups) {
            if (backup.delete()) {
                deleted++;
            }
        }

        // Emergency-Backups auch löschen
        File[] emergencyFiles = backupDir.listFiles((dir, name) ->
            name.startsWith("biome_snapshot_EMERGENCY_") && name.endsWith(".db"));
        if (emergencyFiles != null) {
            for (File emergency : emergencyFiles) {
                if (emergency.delete()) {
                    deleted++;
                }
            }
        }

        plugin.getLogger().info(plugin.getLanguageManager().getMessage("log.backup.cleared", deleted));
        return deleted;
    }

    /**
     * Prüft ob SAFE-Backup existiert
     */
    public boolean hasSafeBackup() {
        return safeBackupFile.exists();
    }

    /**
     * Prüft ob die Datenbank-Datei existiert
     */
    public boolean hasDatabaseFile() {
        return dbFile.exists();
    }

    /**
     * Gibt Backup-Verzeichnis zurück
     */
    public File getBackupDirectory() {
        return backupDir;
    }
}
