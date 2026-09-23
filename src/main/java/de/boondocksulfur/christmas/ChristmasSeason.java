package de.boondocksulfur.christmas;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import de.boondocksulfur.christmas.cmd.AdventCommand;
import de.boondocksulfur.christmas.cmd.XmasCommand;
import de.boondocksulfur.christmas.cmd.XmasGiftCommand;
import de.boondocksulfur.christmas.cmd.XmasTabCompleter;
import de.boondocksulfur.christmas.integration.PlaceholderIntegration;
import de.boondocksulfur.christmas.integration.RegionIntegration;
import de.boondocksulfur.christmas.listener.*;
import de.boondocksulfur.christmas.manager.*;
import de.boondocksulfur.christmas.util.LanguageManager;
import de.boondocksulfur.christmas.util.FoliaSchedulerHelper;
import de.boondocksulfur.christmas.util.Registries;
import de.boondocksulfur.christmas.util.UpdateChecker;

import java.io.File;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Predicate;

/**
 * ChristmasSeason - winter biomes, snowstorms, gifts, decorations and festive mobs
 * for Paper, Purpur and Folia.
 */
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
    private UpdateChecker updateChecker;
    private BiomeCompare biomeCompare;
    private RegionIntegration regionIntegration;
    private EventController eventController;
    private LootManager lootManager;
    private StatsManager statsManager;
    private AdventManager adventManager;
    private PlaceholderIntegration placeholderIntegration;

    private volatile boolean debugMode = false;
    private volatile boolean verboseDebugMode = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        saveResourceIfAbsent("messages_de.yml");
        saveResourceIfAbsent("messages_en.yml");

        this.foliaScheduler    = new FoliaSchedulerHelper(this);
        this.languageManager   = new LanguageManager(this);
        this.backupManager     = new BiomeSnapshotBackup(this);
        this.updateChecker     = new UpdateChecker(this);
        this.biomeCompare      = new BiomeCompare(this);
        this.snowstormManager  = new SnowstormManager(this);
        this.biomeSnowManager  = new BiomeSnowManager(this);
        this.decorationManager = new DecorationManager(this);
        this.giftManager       = new GiftManager(this);
        this.wichtelManager    = new WichtelManager(this);
        this.snowmanManager    = new SnowmanManager(this);
        this.regionIntegration = new RegionIntegration(this);
        this.eventController   = new EventController(this);
        this.lootManager       = new LootManager(this);
        this.statsManager      = new StatsManager(this);
        this.adventManager     = new AdventManager(this);
        validateConfig();

        getCommand("xmas").setExecutor(new XmasCommand(this));
        getCommand("xmas").setTabCompleter(new XmasTabCompleter(this));
        getCommand("xmasgift").setExecutor(new XmasGiftCommand(this));
        AdventCommand adventCommand = new AdventCommand(this);
        getCommand("advent").setExecutor(adventCommand);
        getCommand("advent").setTabCompleter(adventCommand);

        Bukkit.getPluginManager().registerEvents(new GiftProtectionListener(this), this);
        Bukkit.getPluginManager().registerEvents(new GiftOpenListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerPlacementListener(this), this);
        Bukkit.getPluginManager().registerEvents(new TrackedObjectListener(this), this);
        Bukkit.getPluginManager().registerEvents(new WichtelTargetBlocker(), this);
        Bukkit.getPluginManager().registerEvents(new SnowmanDamageListener(), this);
        Bukkit.getPluginManager().registerEvents(new MobProtectionListener(), this);
        Bukkit.getPluginManager().registerEvents(new ChunkSnowListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerSnowBubbleListener(this), this);
        Bukkit.getPluginManager().registerEvents(new UpdateNotificationListener(this), this);

        new org.bstats.bukkit.Metrics(this, 30930);

        performStartupSafetyChecks();

        if (isActive()) startFeatures();

        eventController.startSchedule();
        updateChecker.startAutoCheck();
        hookPlaceholderApi();

        getLogger().info("ChristmasSeason " + getPluginMeta().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        boolean wasActive = isActive();

        if (eventController != null) eventController.stopSchedule();
        if (statsManager != null) statsManager.save();
        if (adventManager != null) adventManager.save();
        if (placeholderIntegration != null) {
            try { placeholderIntegration.unregister(); } catch (Throwable ignored) {}
        }

        // Close the database first so the emergency copy below is complete (WAL flushed)
        stopFeatures();

        if (wasActive && backupManager != null) {
            languageManager.logWarning("log.startup.stopped-while-active");
            backupManager.createEmergencyBackup();
        }
    }

    // ------------------------------------------------------------ lifecycle

    public boolean isActive() { return getConfig().getBoolean("active", false); }

    /** Persists the active flag ({@code /xmas on|off}). */
    public void setActive(boolean active) {
        getConfig().set("active", active);
        saveConfig();
    }

    /** Starts every manager, the per-player tasks of online players and adopts existing event objects. */
    public void startFeatures() {
        snowstormManager.start();
        biomeSnowManager.start();
        decorationManager.start();
        giftManager.start();
        wichtelManager.start();
        snowmanManager.start();

        // PlayerJoinEvent only fires for new joins - cover players who are already online
        for (Player player : Bukkit.getOnlinePlayers()) {
            startPlayerTasks(player);
        }

        // Objects that survived a restart or reload get their tracking, caps and lifetimes back
        giftManager.adoptLoaded();
        decorationManager.adoptLoaded();
        wichtelManager.adoptLoaded();
        snowmanManager.adoptLoaded();
    }

    /** Starts the per-player timers (biome bubble, spawns) for one player. */
    public void startPlayerTasks(Player player) {
        biomeSnowManager.startPlayerTracking(player);
        wichtelManager.startPlayerSpawning(player);
        snowmanManager.startPlayerSpawning(player);
        giftManager.startPlayerSpawning(player);
        decorationManager.startPlayerSpawning(player);
    }

    /** Stops the per-player timers for one player. */
    public void stopPlayerTasks(Player player) {
        biomeSnowManager.stopPlayerTracking(player);
        wichtelManager.stopPlayerSpawning(player);
        snowmanManager.stopPlayerSpawning(player);
        giftManager.stopPlayerSpawning(player);
        decorationManager.stopPlayerSpawning(player);
    }

    public void stopFeatures() {
        stopFeatures(true);
    }

    /**
     * Stops every manager.
     *
     * @param closeBiomeDatabase {@code false} keeps the snapshot database open for a following restore
     */
    public void stopFeatures(boolean closeBiomeDatabase) {
        // Null checks: onEnable() may have failed half-way
        if (snowstormManager != null) snowstormManager.stop();
        if (biomeSnowManager != null) biomeSnowManager.stop(closeBiomeDatabase);
        if (decorationManager != null) decorationManager.stop();
        if (giftManager != null) giftManager.stop();
        if (wichtelManager != null) wichtelManager.stop();
        if (snowmanManager != null) snowmanManager.stop();
    }

    /** {@code /xmas reload}: reloads config and language, restarts all managers. */
    public void reloadAll() {
        reloadConfig();
        languageManager.reload();
        lootManager.reload();
        validateConfig();
        stopFeatures();
        if (isActive()) startFeatures();
        eventController.startSchedule();
    }

    // ------------------------------------------------------------ worlds

    /**
     * Names of all snow worlds: {@code snowWorlds} list plus the legacy {@code snowWorld}
     * string, duplicates removed, order preserved (first = primary).
     */
    public List<String> getSnowWorldNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        String single = getConfig().getString("snowWorld");
        if (single != null && !single.isBlank()) names.add(single);
        names.addAll(getConfig().getStringList("snowWorlds"));
        if (names.isEmpty()) names.add("world");
        return new ArrayList<>(names);
    }

    /** @return the first configured snow world name (used for backups and placeholders) */
    public String getPrimarySnowWorld() {
        return getSnowWorldNames().get(0);
    }

    /** @return loaded snow worlds */
    public List<World> getSnowWorlds() {
        List<World> worlds = new ArrayList<>();
        for (String name : getSnowWorldNames()) {
            World w = Bukkit.getWorld(name);
            if (w != null) worlds.add(w);
        }
        return worlds;
    }

    public boolean isSnowWorld(World world) {
        return world != null && getSnowWorldNames().contains(world.getName());
    }

    // ------------------------------------------------------------ helpers

    /** Time zone for the schedule and the advent calendar ({@code schedule.timezone}, default: server). */
    public ZoneId getZoneId() {
        String tz = getConfig().getString("schedule.timezone", "");
        if (tz == null || tz.isBlank()) return ZoneId.systemDefault();
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            languageManager.logWarning("log.config.invalid-timezone", tz);
            return ZoneId.systemDefault();
        }
    }

    /** Resolves a sound key such as {@code block.bell.use}; empty/unknown -> {@code null}. */
    public Sound resolveSound(String key) {
        if (key == null || key.isBlank() || key.equalsIgnoreCase("none")) return null;
        try {
            NamespacedKey k = key.contains(":") ? NamespacedKey.fromString(key.toLowerCase()) : NamespacedKey.minecraft(key.toLowerCase());
            if (k == null) return null;
            Sound sound = RegistryAccess.registryAccess().getRegistry(RegistryKey.SOUND_EVENT).get(k);
            if (sound == null) languageManager.logWarning("log.config.unknown-sound", key);
            return sound;
        } catch (IllegalArgumentException e) {
            languageManager.logWarning("log.config.unknown-sound", key);
            return null;
        }
    }

    /**
     * Per-player spawn cap: {@code true} if at least {@code configKey} matching entities are
     * already within {@code spawning.nearRadius} blocks of the player. A value of 0 disables
     * the check (only the world cap applies). Must run on the player's thread.
     */
    public boolean isNearCapReached(Player player, String configKey, Predicate<Entity> matcher) {
        int max = getConfig().getInt(configKey, 0);
        if (max <= 0) return false;
        double r = Math.max(8, getConfig().getInt("spawning.nearRadius", 48));
        int count = 0;
        for (Entity e : player.getNearbyEntities(r, r, r)) {
            if (matcher.test(e) && ++count >= max) return true;
        }
        return false;
    }

    private void hookPlaceholderApi() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return;
        try {
            placeholderIntegration = new PlaceholderIntegration(this);
            placeholderIntegration.register();
            getLogger().info("PlaceholderAPI expansion registered (%xmas_...%)");
        } catch (Throwable t) {
            getLogger().warning("PlaceholderAPI detected but the expansion could not be registered: " + t.getMessage());
            placeholderIntegration = null;
        }
    }

    // ------------------------------------------------------------ validation

    /**
     * Warns about typical config mistakes. Invalid entries are skipped at runtime anyway,
     * but without a warning nobody knows why an item never drops.
     */
    private void validateConfig() {
        validateMaterialList("decoration.drops");
        for (String path : new String[]{"gifts.lootTables.common", "gifts.lootTables.extra", "gifts.lootTables.rare",
                "advent.default.items", "advent.default.randomPool"}) {
            if (lootManager != null) lootManager.getList(path); // parses and warns
        }
        for (String name : getSnowWorldNames()) {
            if (Bukkit.getWorld(name) == null) {
                languageManager.logWarning("log.config.world-not-found", name);
            }
        }

        String biomeName = getConfig().getString("biome.target", "SNOWY_PLAINS");
        if (Registries.biomeByName(biomeName) == null) {
            languageManager.logWarning("log.config.unknown-target-biome", biomeName);
        }
    }

    private void validateMaterialList(String path) {
        for (String entry : getConfig().getStringList(path)) {
            String matName = entry.split(":")[0];
            if (org.bukkit.Material.matchMaterial(matName) == null) {
                languageManager.logWarning("log.config.unknown-material", matName, path);
            }
        }
    }

    /**
     * Startup checks: warns when the event is active without a database, runs an SQLite
     * integrity check and points at emergency backups from a previous stop.
     */
    private void performStartupSafetyChecks() {
        File dbFile = new File(getDataFolder(), "biome-snapshot.db");

        if (isActive() && !dbFile.exists() && getConfig().getBoolean("biome.enableSnapshot", true)) {
            languageManager.logWarning("log.startup.separator");
            languageManager.logWarning("log.startup.active-without-db-1");
            languageManager.logWarning("log.startup.active-without-db-2");
            languageManager.logWarning("log.startup.active-without-db-3");
            languageManager.logWarning("log.startup.separator");
        }

        if (dbFile.exists()) {
            try {
                Class.forName("org.sqlite.JDBC");
                try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
                     java.sql.Statement stmt = conn.createStatement();
                     java.sql.ResultSet rs = stmt.executeQuery("PRAGMA integrity_check")) {
                    if (rs.next()) {
                        String result = rs.getString(1);
                        if (!"ok".equalsIgnoreCase(result)) {
                            languageManager.logSevere("log.startup.separator");
                            languageManager.logSevere("log.startup.db-corrupt-1");
                            languageManager.logSevere("log.startup.db-corrupt-2", result);
                            languageManager.logSevere("log.startup.db-corrupt-3");
                            languageManager.logSevere("log.startup.separator");
                        } else {
                            debug("Database integrity check: OK");
                        }
                    }
                }
            } catch (Exception e) {
                languageManager.logSevere("log.startup.db-check-failed", e.getMessage());
            }
        }

        if (backupManager != null) {
            int emergencyCount = backupManager.listEmergencyBackups().size();
            if (emergencyCount > 0) {
                languageManager.logWarning("log.startup.separator");
                languageManager.logWarning("log.startup.emergency-found-1", emergencyCount);
                languageManager.logWarning("log.startup.emergency-found-2");
                languageManager.logWarning("log.startup.separator");
            }
        }
    }

    private void saveResourceIfAbsent(String fileName) {
        File file = new File(getDataFolder(), fileName);
        if (file.exists()) return;
        try {
            if (getResource(fileName) == null) {
                getLogger().severe("Resource not found in JAR: " + fileName);
                return;
            }
            saveResource(fileName, false);
            getLogger().info("Extracted " + fileName);
        } catch (Exception e) {
            getLogger().severe("Could not extract " + fileName + ": " + e.getMessage());
        }
    }

    // -------------------------------------------------------------- getters

    public FoliaSchedulerHelper getFoliaScheduler() { return foliaScheduler; }
    public LanguageManager getLanguageManager() { return languageManager; }
    public BiomeSnapshotBackup getBackupManager() { return backupManager; }
    public UpdateChecker getUpdateChecker() { return updateChecker; }
    public BiomeCompare getBiomeCompare() { return biomeCompare; }
    public RegionIntegration getRegionIntegration() { return regionIntegration; }
    public GiftManager getGiftManager() { return giftManager; }
    public BiomeSnowManager getBiomeSnowManager() { return biomeSnowManager; }
    public WichtelManager getWichtelManager() { return wichtelManager; }
    public SnowmanManager getSnowmanManager() { return snowmanManager; }
    public SnowstormManager getSnowstormManager() { return snowstormManager; }
    public DecorationManager getDecorationManager() { return decorationManager; }
    public EventController getEventController() { return eventController; }
    public LootManager getLootManager() { return lootManager; }
    public StatsManager getStatsManager() { return statsManager; }
    public AdventManager getAdventManager() { return adventManager; }

    // ---------------------------------------------------------------- debug

    public boolean isDebugMode() { return debugMode; }
    public void setDebugMode(boolean enabled) { this.debugMode = enabled; }

    public boolean isVerboseDebugMode() { return verboseDebugMode; }
    public void setVerboseDebugMode(boolean enabled) {
        this.verboseDebugMode = enabled;
        if (enabled) this.debugMode = true; // verbose implies debug
    }

    /** Debug log, only printed while debug mode is on. */
    public void debug(String message) {
        if (debugMode) {
            getLogger().info("[DEBUG] " + LanguageManager.stripColors(message));
        }
    }

    /** Debug log from a language key. */
    public void debugLang(String key, Object... replacements) {
        if (debugMode) {
            getLogger().info("[DEBUG] " + LanguageManager.stripColors(languageManager.getMessage(key, replacements)));
        }
    }

    /** Verbose debug log, only printed while verbose mode is on. */
    public void verboseDebug(String message) {
        if (verboseDebugMode) {
            getLogger().info("[VERBOSE] " + LanguageManager.stripColors(message));
        }
    }

    /** Verbose debug log from a language key. */
    public void verboseDebugLang(String key, Object... replacements) {
        if (verboseDebugMode) {
            getLogger().info("[VERBOSE] " + LanguageManager.stripColors(languageManager.getMessage(key, replacements)));
        }
    }
}
