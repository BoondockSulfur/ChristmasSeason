package de.boondocksulfur.christmas;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import de.boondocksulfur.christmas.cmd.XmasCommand;
import de.boondocksulfur.christmas.cmd.XmasGiftCommand;
import de.boondocksulfur.christmas.cmd.XmasTabCompleter;
import de.boondocksulfur.christmas.listener.*;
import de.boondocksulfur.christmas.manager.*;
import de.boondocksulfur.christmas.util.LanguageManager;
import de.boondocksulfur.christmas.util.FoliaSchedulerHelper;

public class ChristmasSeason extends JavaPlugin {

    private FoliaSchedulerHelper foliaScheduler;
    private LanguageManager languageManager;
    private SnowstormManager snowstormManager;
    private BiomeSnowManager biomeSnowManager;
    private DecorationManager decorationManager;
    private GiftManager giftManager;
    private WichtelManager wichtelManager;
    private SnowmanManager snowmanManager;
    private BiomeSnapshotBackup backupManager;
    private de.boondocksulfur.christmas.util.UpdateChecker updateChecker;
    private BiomeCompare biomeCompare;
    private de.boondocksulfur.christmas.integration.RegionIntegration regionIntegration;

    // Debug-Modus für ausführliche Logs
    private boolean debugMode = false;
    private boolean verboseDebugMode = false; // Noch ausführlichere Logs (Biome-Snapshot Details)

    @Override
    public void onEnable() {
        saveDefaultConfig();
        validateConfig();

        // Sprachdateien extrahieren falls nicht vorhanden
        saveResourceIfAbsent("messages_de.yml");
        saveResourceIfAbsent("messages_en.yml");

        // Eine geteilte FoliaLib-Instanz für alle Manager
        this.foliaScheduler    = new FoliaSchedulerHelper(this);
        this.languageManager   = new LanguageManager(this);
        this.backupManager     = new BiomeSnapshotBackup(this);
        this.updateChecker     = new de.boondocksulfur.christmas.util.UpdateChecker(this);
        this.biomeCompare      = new BiomeCompare(this);
        this.snowstormManager  = new SnowstormManager(this);
        this.biomeSnowManager  = new BiomeSnowManager(this);
        this.decorationManager = new DecorationManager(this);
        this.giftManager       = new GiftManager(this);
        this.wichtelManager    = new WichtelManager(this);
        this.snowmanManager    = new SnowmanManager(this);
        this.regionIntegration = new de.boondocksulfur.christmas.integration.RegionIntegration(this);

        getCommand("xmas").setExecutor(new XmasCommand(this));
        getCommand("xmas").setTabCompleter(new XmasTabCompleter(this));
        getCommand("xmasgift").setExecutor(new XmasGiftCommand(this));

        Bukkit.getPluginManager().registerEvents(new GiftOpenListener(this), this);
        Bukkit.getPluginManager().registerEvents(new GiftProtectionListener(this), this);
        Bukkit.getPluginManager().registerEvents(new OrphanedMobCleanupListener(this), this);
        Bukkit.getPluginManager().registerEvents(new WichtelTargetBlocker(), this);
        Bukkit.getPluginManager().registerEvents(new SnowmanDamageListener(), this);
        Bukkit.getPluginManager().registerEvents(new MobProtectionListener(), this);
        Bukkit.getPluginManager().registerEvents(new ChunkSnowListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerSnowBubbleListener(this), this);
        Bukkit.getPluginManager().registerEvents(new UpdateNotificationListener(this), this);

        // bStats Metrics
        new org.bstats.bukkit.Metrics(this, 30930);

        // Startup-Sicherheitsprüfungen (DB-Integrität, Emergency-Backups)
        performStartupSafetyChecks();

        if (isActive()) startFeatures();

        // Auto-Update-Check beim Server-Start
        updateChecker.startAutoCheck();

        getLogger().info("ChristmasSeason enabled.");
    }

    @Override
    public void onDisable() {
        // NOTFALL-BACKUP: Wenn Server stoppt während xmas ON aktiv ist!
        if (isActive() && backupManager != null) {
            getLogger().warning("Server wird gestoppt während ChristmasSeason AKTIV ist!");
            getLogger().warning("Erstelle Notfall-Backup der Biome-Datenbank...");
            backupManager.createEmergencyBackup();
        }

        stopFeatures();
    }

    public boolean isActive() { return getConfig().getBoolean("active", false); }

    public void startFeatures() {
        snowstormManager.start();
        biomeSnowManager.start();
        decorationManager.start();
        giftManager.start();
        wichtelManager.start();
        snowmanManager.start();

        // FOLIA FIX: Starte Player-basierte Tasks für bereits online Spieler
        // (PlayerJoinEvent wird nur für neue Joins gefeuert, nicht für bereits online Spieler!)
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            biomeSnowManager.startPlayerTracking(player);
            wichtelManager.startPlayerSpawning(player);
            snowmanManager.startPlayerSpawning(player);
            giftManager.startPlayerSpawning(player);
            decorationManager.startPlayerSpawning(player);
            debug("Player-Tracking für bereits online Spieler gestartet: " + player.getName());
        }
    }
    public void stopFeatures() {
        stopFeatures(true);
    }

    /** Stop Features mit optionalem Biome-DB schließen */
    public void stopFeatures(boolean closeBiomeDatabase) {
        // FIX: Null-Checks für den Fall dass onEnable() fehlgeschlagen ist
        if (snowstormManager != null) snowstormManager.stop();
        if (biomeSnowManager != null) biomeSnowManager.stop(closeBiomeDatabase);
        if (decorationManager != null) decorationManager.stop();
        if (giftManager != null) giftManager.stop();
        if (wichtelManager != null) wichtelManager.stop();
        if (snowmanManager != null) snowmanManager.stop();
    }
    public void reloadAll() {
        reloadConfig();
        validateConfig();
        languageManager.reload();
        stopFeatures();
        if (isActive()) startFeatures();
    }

    /**
     * Prüft die config.yml auf typische Fehler und warnt im Log.
     * Ungültige Einträge werden zur Laufzeit ohnehin übersprungen -
     * ohne Warnung rätselt man aber, warum ein Item nie droppt.
     */
    private void validateConfig() {
        validateMaterialList("decoration.drops");
        validateMaterialList("gifts.lootTables.common");
        validateMaterialList("gifts.lootTables.extra");
        validateMaterialList("gifts.lootTables.rare");

        String biomeName = getConfig().getString("biome.target", "SNOWY_PLAINS");
        try {
            if (de.boondocksulfur.christmas.util.Registries.biomes().get(org.bukkit.NamespacedKey.minecraft(biomeName.toLowerCase())) == null) {
                getLogger().warning("config.yml: Unbekanntes Biom in biome.target: '" + biomeName + "' - es wird SNOWY_PLAINS verwendet.");
            }
        } catch (Exception e) {
            getLogger().warning("config.yml: Ungültiger Biom-Name in biome.target: '" + biomeName + "' - es wird SNOWY_PLAINS verwendet.");
        }
    }

    /**
     * Führt Sicherheitsprüfungen beim Start durch:
     * - DB-Integritätscheck
     * - Warnung bei active:true ohne DB
     * - Erkennung von Emergency-Backups (vorheriger Crash)
     */
    private void performStartupSafetyChecks() {
        java.io.File dbFile = new java.io.File(getDataFolder(), "biome-snapshot.db");

        // Check 1: active:true aber keine DB → Warnung
        if (isActive() && !dbFile.exists() && getConfig().getBoolean("biome.enableSnapshot", true)) {
            getLogger().warning("═══════════════════════════════════════════");
            getLogger().warning(" WARNUNG: ChristmasSeason ist aktiv, aber keine Snapshot-DB vorhanden!");
            getLogger().warning(" Biome wurden möglicherweise geändert und können nicht restored werden.");
            getLogger().warning(" Prüfe: /xmas backup list (für verfügbare Backups)");
            getLogger().warning("═══════════════════════════════════════════");
        }

        // Check 2: DB-Integrität prüfen (falls DB existiert)
        if (dbFile.exists()) {
            try {
                Class.forName("org.sqlite.JDBC");
                try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
                     java.sql.Statement stmt = conn.createStatement();
                     java.sql.ResultSet rs = stmt.executeQuery("PRAGMA integrity_check")) {
                    if (rs.next()) {
                        String result = rs.getString(1);
                        if (!"ok".equalsIgnoreCase(result)) {
                            getLogger().severe("═══════════════════════════════════════════");
                            getLogger().severe(" DATENBANK-KORRUPTION ERKANNT!");
                            getLogger().severe(" Integrity Check: " + result);
                            getLogger().severe(" Empfehlung: /xmas backup restore SAFE confirm");
                            getLogger().severe("═══════════════════════════════════════════");
                        } else {
                            debug("DB-Integritätscheck: OK");
                        }
                    }
                }
            } catch (Exception e) {
                getLogger().severe("DB-Integritätscheck fehlgeschlagen: " + e.getMessage());
                getLogger().severe("Die Datenbank könnte beschädigt sein. Prüfe: /xmas backup list");
            }
        }

        // Check 3: Emergency-Backups erkennen (Hinweis auf vorherigen Crash)
        if (backupManager != null) {
            java.util.Map<String, java.io.File> allBackups = backupManager.listAllBackups();
            long emergencyCount = allBackups.keySet().stream().filter(k -> k.startsWith("EMERGENCY")).count();
            if (emergencyCount > 0) {
                getLogger().warning("═══════════════════════════════════════════");
                getLogger().warning(" " + emergencyCount + " Emergency-Backup(s) gefunden!");
                getLogger().warning(" Der Server wurde zuvor gestoppt während ChristmasSeason aktiv war.");
                getLogger().warning(" Prüfe: /xmas backup list → /xmas backup restore <ID> confirm");
                getLogger().warning("═══════════════════════════════════════════");
            }
        }
    }

    private void validateMaterialList(String path) {
        for (String entry : getConfig().getStringList(path)) {
            String matName = entry.split(":")[0];
            if (org.bukkit.Material.matchMaterial(matName) == null) {
                getLogger().warning("config.yml: Unbekanntes Material '" + matName + "' in " + path + " - Eintrag wird ignoriert.");
            }
        }
    }

    // Helper
    private void saveResourceIfAbsent(String fileName) {
        java.io.File file = new java.io.File(getDataFolder(), fileName);
        if (!file.exists()) {
            try {
                // Prüfe ob Ressource im JAR existiert
                java.io.InputStream resource = getResource(fileName);
                if (resource == null) {
                    getLogger().severe("Resource not found in JAR: " + fileName);
                    return;
                }
                resource.close();

                saveResource(fileName, false);
                getLogger().info("Extracted resource: " + fileName + " (Size: " + file.length() + " bytes)");
            } catch (Exception e) {
                getLogger().severe("Could not extract " + fileName + ": " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            getLogger().info("Resource already exists: " + fileName + " (Size: " + file.length() + " bytes)");
        }
    }

    // Getters
    public FoliaSchedulerHelper getFoliaScheduler() { return foliaScheduler; }
    public LanguageManager getLanguageManager() { return languageManager; }
    public BiomeSnapshotBackup getBackupManager() { return backupManager; }
    public de.boondocksulfur.christmas.util.UpdateChecker getUpdateChecker() { return updateChecker; }
    public BiomeCompare getBiomeCompare() { return biomeCompare; }
    public de.boondocksulfur.christmas.integration.RegionIntegration getRegionIntegration() { return regionIntegration; }
    public GiftManager getGiftManager() { return giftManager; }
    public BiomeSnowManager getBiomeSnowManager() { return biomeSnowManager; }
    public WichtelManager getWichtelManager() { return wichtelManager; }
    public SnowmanManager getSnowmanManager() { return snowmanManager; }
    public SnowstormManager getSnowstormManager() { return snowstormManager; }
    public DecorationManager getDecorationManager() { return decorationManager; }

    // Debug-Modus
    public boolean isDebugMode() { return debugMode; }
    public void setDebugMode(boolean enabled) { this.debugMode = enabled; }

    public boolean isVerboseDebugMode() { return verboseDebugMode; }
    public void setVerboseDebugMode(boolean enabled) {
        this.verboseDebugMode = enabled;
        if (enabled) this.debugMode = true; // Verbose aktiviert automatisch Debug
    }

    /** Debug-Log: Nur ausgeben wenn Debug-Modus aktiv */
    public void debug(String message) {
        if (debugMode) {
            getLogger().info("[DEBUG] " + LanguageManager.stripColors(message));
        }
    }

    /** Debug-Log mit Sprach-Unterstützung */
    public void debugLang(String key, Object... replacements) {
        if (debugMode) {
            String message = languageManager.getMessage(key, replacements);
            getLogger().info("[DEBUG] " + LanguageManager.stripColors(message));
        }
    }

    /** Verbose Debug-Log: Nur ausgeben wenn Verbose-Debug-Modus aktiv */
    public void verboseDebug(String message) {
        if (verboseDebugMode) {
            getLogger().info("[VERBOSE] " + LanguageManager.stripColors(message));
        }
    }

    /** Verbose Debug-Log mit Sprach-Unterstützung */
    public void verboseDebugLang(String key, Object... replacements) {
        if (verboseDebugMode) {
            String message = languageManager.getMessage(key, replacements);
            getLogger().info("[VERBOSE] " + LanguageManager.stripColors(message));
        }
    }
}
