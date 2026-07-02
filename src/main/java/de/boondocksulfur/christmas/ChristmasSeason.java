package de.boondocksulfur.christmas;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import de.boondocksulfur.christmas.cmd.XmasCommand;
import de.boondocksulfur.christmas.cmd.XmasGiftCommand;
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

    // Debug-Modus für ausführliche Logs
    private boolean debugMode = false;
    private boolean verboseDebugMode = false; // Noch ausführlichere Logs (Biome-Snapshot Details)

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Sprachdateien extrahieren falls nicht vorhanden
        saveResourceIfAbsent("messages_de.yml");
        saveResourceIfAbsent("messages_en.yml");

        // Eine geteilte FoliaLib-Instanz für alle Manager
        this.foliaScheduler    = new FoliaSchedulerHelper(this);
        this.languageManager   = new LanguageManager(this);
        this.snowstormManager  = new SnowstormManager(this);
        this.biomeSnowManager  = new BiomeSnowManager(this);
        this.decorationManager = new DecorationManager(this);
        this.giftManager       = new GiftManager(this);
        this.wichtelManager    = new WichtelManager(this);
        this.snowmanManager    = new SnowmanManager(this);

        getCommand("xmas").setExecutor(new XmasCommand(this));
        getCommand("xmasgift").setExecutor(new XmasGiftCommand(this));

        Bukkit.getPluginManager().registerEvents(new GiftOpenListener(this), this);
        Bukkit.getPluginManager().registerEvents(new WichtelTargetBlocker(), this);
        Bukkit.getPluginManager().registerEvents(new SnowmanDamageListener(), this);
        Bukkit.getPluginManager().registerEvents(new MobProtectionListener(), this);
        Bukkit.getPluginManager().registerEvents(new ChunkSnowListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerSnowBubbleListener(this), this);

        if (isActive()) startFeatures();
        getLogger().info("ChristmasSeason enabled.");
    }

    @Override
    public void onDisable() { stopFeatures(); }

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
        snowstormManager.stop();
        biomeSnowManager.stop(closeBiomeDatabase);  // DB optional offen lassen
        decorationManager.stop();
        giftManager.stop();
        wichtelManager.stop();
        snowmanManager.stop();
    }
    public void reloadAll() {
        reloadConfig();
        languageManager.reload();
        stopFeatures();
        if (isActive()) startFeatures();
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
            getLogger().info("[DEBUG] " + org.bukkit.ChatColor.stripColor(message));
        }
    }

    /** Debug-Log mit Sprach-Unterstützung */
    public void debugLang(String key, Object... replacements) {
        if (debugMode) {
            String message = languageManager.getMessage(key, replacements);
            getLogger().info("[DEBUG] " + org.bukkit.ChatColor.stripColor(message));
        }
    }

    /** Verbose Debug-Log: Nur ausgeben wenn Verbose-Debug-Modus aktiv */
    public void verboseDebug(String message) {
        if (verboseDebugMode) {
            getLogger().info("[VERBOSE] " + org.bukkit.ChatColor.stripColor(message));
        }
    }

    /** Verbose Debug-Log mit Sprach-Unterstützung */
    public void verboseDebugLang(String key, Object... replacements) {
        if (verboseDebugMode) {
            String message = languageManager.getMessage(key, replacements);
            getLogger().info("[VERBOSE] " + org.bukkit.ChatColor.stripColor(message));
        }
    }
}
