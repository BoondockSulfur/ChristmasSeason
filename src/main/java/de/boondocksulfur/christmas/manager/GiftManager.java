package de.boondocksulfur.christmas.manager;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.util.LanguageManager;
import de.boondocksulfur.christmas.util.SpawnUtil;
import de.boondocksulfur.christmas.util.FoliaSchedulerHelper;

import java.util.*;

public class GiftManager {

    private final ChristmasSeason plugin;
    private final LanguageManager lang;
    private final FoliaSchedulerHelper scheduler;
    private final Random random = new Random();

    // FOLIA FIX: Player-basierte Spawn-Timer (Entity Scheduler)
    private final Map<UUID, WrappedTask> playerSpawnTasks = new java.util.concurrent.ConcurrentHashMap<>();

    // OPTIMIERUNG: Tracke gespawnte Geschenk-Locations statt alle Chunks zu durchsuchen
    // FOLIA FIX: ConcurrentHashMap.newKeySet() - add/remove laufen auf Location-Scheduler-Threads
    private final Set<Location> trackedGifts = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // SAFETY FIX: Lifetime-Tasks tracken, damit stop() sie canceln kann -
    // verwaiste Tasks (bis 300s) könnten sonst nach /xmas off noch feuern
    private final Map<Location, WrappedTask> giftLifetimeTasks = new java.util.concurrent.ConcurrentHashMap<>();

    // SAFETY FIX: PDC-Marker identifiziert UNSERE Kisten eindeutig - der Lifetime-
    // Task darf niemals eine Spielerkiste löschen, die an derselben Position steht
    private final org.bukkit.NamespacedKey giftChestKey;

    public GiftManager(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.scheduler = plugin.getFoliaScheduler();
        this.giftChestKey = new org.bukkit.NamespacedKey(plugin, "gift-chest");
    }

    public void start() {
        stop();
        // FOLIA FIX: Spawn-Timer sind jetzt Player-basiert (siehe startPlayerSpawning)
        plugin.debug("GiftManager gestartet (Folia-kompatibel: Player-basierte Spawns)");
    }

    public void stop() {
        // FOLIA FIX: Stoppe alle Player-basierten Tasks
        for (WrappedTask task : playerSpawnTasks.values()) {
            if (task != null) task.cancel();
        }
        playerSpawnTasks.clear();

        // SAFETY FIX: Lifetime-Tasks canceln - dürfen nach stop() nicht mehr feuern
        for (WrappedTask task : giftLifetimeTasks.values()) {
            if (task != null) task.cancel();
        }
        giftLifetimeTasks.clear();

        trackedGifts.clear();
    }

    /**
     * Startet Geschenk-Spawning für einen Spieler (Entity Scheduler)
     * FOLIA-KOMPATIBEL: Läuft auf Entity Scheduler des Players
     */
    public void startPlayerSpawning(Player player) {
        if (!plugin.getConfig().getBoolean("gifts.enabled", true)) return;

        UUID uuid = player.getUniqueId();
        WrappedTask oldTask = playerSpawnTasks.remove(uuid);
        if (oldTask != null) oldTask.cancel();

        int interval = plugin.getConfig().getInt("gifts.globalIntervalSeconds", 160);
        // FOLIA FIX: Bei Player-basiertem Spawning Chance auf 1.0 für zuverlässiges Timing
        // (Bei globalem Timer mit mehreren Spielern war die Chance sinnvoll, jetzt nicht mehr)
        double chance = plugin.getConfig().getDouble("gifts.chancePerInterval", 1.0);

        WrappedTask task = scheduler.runForEntityTimer(player, () -> {
            if (!player.isOnline() || !player.isValid()) {
                stopPlayerSpawning(player);
                return;
            }
            if (random.nextDouble() <= chance) {
                spawnGiftNearPlayer(player);
            }
        }, 80L, interval * 20L);

        if (task != null) {
            playerSpawnTasks.put(uuid, task);
            plugin.debug("Geschenk-Spawning gestartet für " + player.getName());
        }
    }

    /**
     * Stoppt Geschenk-Spawning für einen Spieler
     */
    public void stopPlayerSpawning(Player player) {
        WrappedTask task = playerSpawnTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
            plugin.debug("Geschenk-Spawning gestoppt für " + player.getName());
        }
    }

    /**
     * Prüft, ob an dieser Position eine getrackte Geschenk-Kiste steht.
     * Wird vom GiftProtectionListener genutzt (Hopper-/Explosionsschutz).
     */
    public boolean isGiftChest(Location loc) {
        return loc != null && trackedGifts.contains(loc);
    }

    /** Entfernt alle Geschenk-Chests aus der Welt */
    public void cleanup() {
        int removed = 0;

        // OPTIMIERT: Verwende Tracking-System statt alle Chunks zu durchsuchen!
        // FOLIA FIX: Cleanup über Location Scheduler für Block-Operationen
        Iterator<Location> it = trackedGifts.iterator();
        while (it.hasNext()) {
            Location loc = it.next();

            // SAFETY FIX: Zugehörigen Lifetime-Task canceln
            WrappedTask lt = giftLifetimeTasks.remove(loc);
            if (lt != null) lt.cancel();

            scheduler.runAtLocation(loc, () -> {
                Block block = loc.getBlock();
                // SAFETY FIX: PDC-Marker prüfen - nur UNSERE Kisten löschen
                if (block.getType() == Material.CHEST
                        && block.getState() instanceof Chest c
                        && c.getPersistentDataContainer().has(giftChestKey, org.bukkit.persistence.PersistentDataType.BYTE)) {
                    block.setType(Material.AIR);
                }
            });
            removed++;
            it.remove();
        }

        plugin.getLogger().info(lang.getMessage("log.cleanup.gifts", removed));
    }

    /**
     * Spawnt Geschenk in Nähe eines Spielers
     * FOLIA-KOMPATIBEL: Wird von Entity Scheduler des Players aufgerufen
     */
    private void spawnGiftNearPlayer(Player player) {
        World w = player.getWorld();
        String worldName = plugin.getConfig().getString("snowWorld", "world");
        if (!w.getName().equals(worldName)) return;

        // FOLIA FIX: Spawne auf Location Scheduler (für findSurface und Block-Operationen)
        Location playerLoc = player.getLocation();

        scheduler.runAtLocation(playerLoc, () -> {
            // Safe-Spawn: 5 Versuche (Performance-optimiert, strenge Wasser/Wand-Checks)
            Location loc = SpawnUtil.findSafeSpawnLocation(w, playerLoc, 8, 5);
            spawnGift(w, loc);
        });
    }

    public void spawnGift(World w, Location loc) {
        Block b = loc.getBlock();
        try { b.setType(Material.AIR, false); } catch (Throwable ignored) { b.setType(Material.AIR); }
        try { b.setType(Material.CHEST, false); } catch (Throwable ignored) { b.setType(Material.CHEST); }

        BlockState state = b.getState();
        if (!(state instanceof Chest)) return;
        Chest chest = (Chest) state;

        chest.customName(lang.getComponent("entity.gift-chest"));
        // SAFETY FIX: PDC-Marker setzen, damit der Lifetime-Task sicher erkennen
        // kann, ob an der Position noch UNSERE Kiste steht (nicht eine vom Spieler)
        chest.getPersistentDataContainer().set(giftChestKey,
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        chest.update();

        fillGiftInventory(chest.getBlockInventory());

        // OPTIMIERUNG: Tracke die Location für effizientes Cleanup
        Location chestLoc = b.getLocation();
        trackedGifts.add(chestLoc);

        int lifetime = plugin.getConfig().getInt("gifts.lifetimeSeconds", 300);
        WrappedTask lifetimeTask = scheduler.runAtLocationLater(chestLoc, () -> {
            giftLifetimeTasks.remove(chestLoc);
            // LEAK FIX: Immer aus Tracking entfernen - auch wenn die Kiste
            // inzwischen von Spielern abgebaut wurde (sonst wächst das Set endlos)
            trackedGifts.remove(chestLoc);

            // SAFETY FIX: Nur löschen, wenn dort wirklich noch UNSERE Kiste steht
            // (PDC-Marker) - niemals eine Spielerkiste an derselben Position!
            Block current = chestLoc.getBlock();
            if (current.getType() == Material.CHEST
                    && current.getState() instanceof Chest c
                    && c.getPersistentDataContainer().has(giftChestKey, org.bukkit.persistence.PersistentDataType.BYTE)) {
                try { current.setType(Material.AIR, false); } catch (Throwable ignored) { current.setType(Material.AIR); }
            }
        }, lifetime * 20L);
        if (lifetimeTask != null) {
            giftLifetimeTasks.put(chestLoc, lifetimeTask);
        }

        if (plugin.getConfig().getBoolean("gifts.broadcastOnSpawn", true)) {
            Bukkit.broadcast(lang.getComponent("broadcast.gift-spawned",
                    w.getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
        }
    }

    private void fillGiftInventory(Inventory inv) {
        FileConfiguration cfg = plugin.getConfig();
        List<String> common = cfg.getStringList("gifts.lootTables.common");
        List<String> extra  = cfg.getStringList("gifts.lootTables.extra");
        List<String> rare   = cfg.getStringList("gifts.lootTables.rare");

        int base = 4 + random.nextInt(4);
        for (int i = 0; i < base; i++) add(inv, common);
        int deco = 1 + random.nextInt(3);
        for (int i = 0; i < deco; i++) add(inv, extra);
        int rares = 0;
        if (!rare.isEmpty()) {
            if (random.nextDouble() < 0.6) rares = 1;
            if (random.nextDouble() < 0.25) rares = 2;
        }
        for (int i = 0; i < rares; i++) add(inv, rare);
    }

    private void add(Inventory inv, List<String> list) {
        if (list == null || list.isEmpty()) return;
        String entry = list.get(random.nextInt(list.size()));
        String[] split = entry.split(":");
        String matName = split[0];
        int amount = 1;
        if (split.length > 1) {
            try { amount = Integer.parseInt(split[1]); } catch (NumberFormatException ignored) {}
        }
        Material m = Material.matchMaterial(matName);
        if (m == null) return;
        inv.addItem(new ItemStack(m, amount));
    }
}
