package de.boondocksulfur.christmas.manager;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import de.boondocksulfur.christmas.api.GiftOpenEvent;
import de.boondocksulfur.christmas.api.GiftSpawnEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.util.LanguageManager;
import de.boondocksulfur.christmas.util.SpawnUtil;
import de.boondocksulfur.christmas.util.FoliaSchedulerHelper;

import java.util.*;

/**
 * Spawns gift chests near players and removes them after their lifetime.
 *
 * <p>Every gift chest carries a PersistentDataContainer marker, so the plugin can
 * recognise its own chests after a restart or reload (adoption) and never deletes a
 * player's chest that happens to stand at a tracked position.
 */
public class GiftManager {

    private final ChristmasSeason plugin;
    private final LanguageManager lang;
    private final FoliaSchedulerHelper scheduler;
    private final Random random = new Random();

    /** Per-player spawn timers (entity scheduler). */
    private final Map<UUID, WrappedTask> playerSpawnTasks = new java.util.concurrent.ConcurrentHashMap<>();

    /** Positions of live gift chests (mutated from region threads). */
    private final Set<Location> trackedGifts = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Lifetime tasks per chest so cleanup can cancel them. */
    private final Map<Location, WrappedTask> giftLifetimeTasks = new java.util.concurrent.ConcurrentHashMap<>();

    private final NamespacedKey giftChestKey;
    private final NamespacedKey giftOpenedKey;

    public GiftManager(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.scheduler = plugin.getFoliaScheduler();
        this.giftChestKey = new NamespacedKey(plugin, "gift-chest");
        this.giftOpenedKey = new NamespacedKey(plugin, "gift-opened");
    }

    // -------------------------------------------------------------- lifecycle

    /** Starts the manager (spawn timers are per player, see {@link #startPlayerSpawning}). */
    public void start() {
        stop();
        plugin.debug("GiftManager started (per-player spawns)");
    }

    /**
     * Stops the spawn timers. Existing chests, their tracking and their lifetime
     * tasks are kept so that {@code /xmas reload} does not orphan them.
     */
    public void stop() {
        for (WrappedTask task : playerSpawnTasks.values()) {
            if (task != null) task.cancel();
        }
        playerSpawnTasks.clear();
    }

    /** Starts gift spawning for a player on the player's entity scheduler. */
    public void startPlayerSpawning(Player player) {
        if (!plugin.getConfig().getBoolean("gifts.enabled", true)) return;

        UUID uuid = player.getUniqueId();
        WrappedTask oldTask = playerSpawnTasks.remove(uuid);
        if (oldTask != null) oldTask.cancel();

        int interval = Math.max(5, plugin.getConfig().getInt("gifts.globalIntervalSeconds", 160));
        double chance = plugin.getConfig().getDouble("gifts.chancePerInterval", 1.0);

        WrappedTask task = scheduler.runForEntityTimer(player, () -> {
            if (!player.isOnline() || !player.isValid()) {
                stopPlayerSpawning(player);
                return;
            }
            if (random.nextDouble() <= chance) {
                spawnGiftNearPlayer(player);
            }
        }, () -> playerSpawnTasks.remove(uuid), 80L, interval * 20L);

        if (task != null) {
            playerSpawnTasks.put(uuid, task);
            plugin.debug("Gift spawning started for " + player.getName());
        }
    }

    /** Stops gift spawning for a player. */
    public void stopPlayerSpawning(Player player) {
        WrappedTask task = playerSpawnTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
            plugin.debug("Gift spawning stopped for " + player.getName());
        }
    }

    // --------------------------------------------------------------- tracking

    /** @return number of live gift chests */
    public int getTrackedCount() {
        return trackedGifts.size();
    }

    /** @return {@code true} if a tracked gift chest stands at this position (used by the protection listener) */
    public boolean isGiftChest(Location loc) {
        return loc != null && trackedGifts.contains(loc);
    }

    /** @return {@code true} if the block state is one of our chests (marker check) */
    public boolean isMarkedGiftChest(BlockState state) {
        return state instanceof Chest c
                && c.getPersistentDataContainer().has(giftChestKey, PersistentDataType.BYTE);
    }

    /**
     * Adopts a marked chest found in a freshly loaded chunk or after a restart:
     * tracks it and (re)starts its lifetime timer. Must run on the chunk's thread.
     */
    public void adopt(Chest chest) {
        Location loc = chest.getLocation();
        if (trackedGifts.contains(loc)) return;
        trackedGifts.add(loc);
        scheduleLifetime(loc);
        plugin.debug("Adopted gift chest at " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ());
    }

    /** Scans all loaded chunks of the snow worlds for marked chests and adopts them. */
    public void adoptLoaded() {
        for (World w : plugin.getSnowWorlds()) {
            scheduler.forEachLoadedChunk(w, this::adoptInChunk);
        }
    }

    /** Adopts every marked chest in a chunk; must run on the chunk's thread. */
    public void adoptInChunk(Chunk chunk) {
        for (BlockState state : chunk.getTileEntities(false)) {
            if (isMarkedGiftChest(state)) {
                adopt((Chest) state);
            }
        }
    }

    /** Removes every marked chest in a chunk (event inactive); must run on the chunk's thread. */
    public void removeInChunk(Chunk chunk) {
        for (BlockState state : chunk.getTileEntities(false)) {
            if (isMarkedGiftChest(state)) {
                Location loc = state.getLocation();
                trackedGifts.remove(loc);
                WrappedTask lt = giftLifetimeTasks.remove(loc);
                if (lt != null) lt.cancel();
                loc.getBlock().setType(Material.AIR);
                plugin.debug("Removed orphaned gift chest at " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ());
            }
        }
    }

    /**
     * Removes all gift chests ({@code /xmas off}): tracked ones plus any marked chest in a
     * loaded chunk that was never tracked (e.g. placed before a restart).
     */
    public void cleanup() {
        int removed = 0;

        Iterator<Location> it = trackedGifts.iterator();
        while (it.hasNext()) {
            Location loc = it.next();
            WrappedTask lt = giftLifetimeTasks.remove(loc);
            if (lt != null) lt.cancel();

            scheduler.runAtLocation(loc, () -> removeIfMarked(loc));
            removed++;
            it.remove();
        }

        for (World w : plugin.getSnowWorlds()) {
            scheduler.forEachLoadedChunk(w, this::removeInChunk);
        }

        lang.logInfo("log.cleanup.gifts", removed);
    }

    /** Removes the block at {@code loc} if it is still one of our chests; must run on its thread. */
    private void removeIfMarked(Location loc) {
        Block block = loc.getBlock();
        if (block.getType() == Material.CHEST && isMarkedGiftChest(block.getState())) {
            try { block.setType(Material.AIR, false); } catch (Throwable ignored) { block.setType(Material.AIR); }
        }
    }

    // --------------------------------------------------------------- spawning

    /** Picks a safe spot near the player and spawns a gift there (on the region thread). */
    private void spawnGiftNearPlayer(Player player) {
        World w = player.getWorld();
        if (!plugin.isSnowWorld(w)) return;

        Location playerLoc = player.getLocation();
        scheduler.runAtLocation(playerLoc, () -> {
            Location loc = SpawnUtil.findSafeSpawnLocation(w, playerLoc, 8, 5);
            if (loc == null) {
                plugin.debug("No safe gift spot near " + player.getName());
                return;
            }
            if (plugin.getRegionIntegration() != null && !plugin.getRegionIntegration().canSpawnAt(loc)) {
                plugin.debug("Gift spawn blocked by region protection at " + loc.getBlockX() + "," + loc.getBlockZ());
                return;
            }
            spawnGift(w, loc);
        });
    }

    /**
     * Places a gift chest at the location. Must run on the location's thread.
     *
     * @return {@code true} if a chest was placed
     */
    public boolean spawnGift(World w, Location loc) {
        Block b = loc.getBlock();

        // Never merge with a player's chest into a double chest
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
            Material neighbour = b.getRelative(face).getType();
            if (neighbour == Material.CHEST || neighbour == Material.TRAPPED_CHEST) {
                plugin.debug("Gift spawn skipped: chest next to " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ());
                return false;
            }
        }

        GiftSpawnEvent event = new GiftSpawnEvent(b.getLocation());
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return false;

        try { b.setType(Material.CHEST, false); } catch (Throwable ignored) { b.setType(Material.CHEST); }

        BlockState state = b.getState();
        if (!(state instanceof Chest chest)) return false;

        chest.customName(lang.getComponent("entity.gift-chest"));
        chest.getPersistentDataContainer().set(giftChestKey, PersistentDataType.BYTE, (byte) 1);
        chest.update();

        fillGiftInventory(chest.getBlockInventory());

        Location chestLoc = b.getLocation();
        trackedGifts.add(chestLoc);
        scheduleLifetime(chestLoc);

        if (plugin.getConfig().getBoolean("gifts.broadcastOnSpawn", true)) {
            Bukkit.broadcast(lang.getComponent("broadcast.gift-spawned",
                    w.getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
        }
        org.bukkit.Sound sound = plugin.resolveSound(plugin.getConfig().getString("gifts.effects.spawnSound", "block.bell.use"));
        if (sound != null) w.playSound(chestLoc, sound, 1f, 1f);
        if (plugin.getConfig().getBoolean("gifts.effects.spawnParticles", true)) {
            w.spawnParticle(org.bukkit.Particle.SNOWFLAKE, chestLoc.clone().add(0.5, 1.0, 0.5), 25, 0.4, 0.4, 0.4, 0.01);
        }
        return true;
    }

    /**
     * Called by the open listener. The first player to open a chest gets counted,
     * effects play and {@link GiftOpenEvent} is fired. Must run on the chest's thread.
     */
    public void onFirstOpen(Player player, Chest chest) {
        if (chest.getPersistentDataContainer().has(giftOpenedKey, PersistentDataType.BYTE)) return;
        chest.getPersistentDataContainer().set(giftOpenedKey, PersistentDataType.BYTE, (byte) 1);
        chest.update();

        plugin.getStatsManager().incrementGiftsOpened(player.getUniqueId(), player.getName());
        Bukkit.getPluginManager().callEvent(new GiftOpenEvent(player, chest.getLocation()));

        Location loc = chest.getLocation();
        org.bukkit.Sound sound = plugin.resolveSound(plugin.getConfig().getString("gifts.effects.openSound", "entity.player.levelup"));
        if (sound != null) loc.getWorld().playSound(loc, sound, 1f, 1.3f);
        if (plugin.getConfig().getBoolean("gifts.effects.openParticles", true)) {
            loc.getWorld().spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER, loc.clone().add(0.5, 1.2, 0.5), 20, 0.4, 0.4, 0.4, 0.0);
        }
        if (plugin.getConfig().getBoolean("gifts.broadcastOnOpen", false)) {
            Bukkit.broadcast(lang.getComponent("broadcast.gift-opened", player.getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
        } else {
            lang.send(player, "gift.opened", plugin.getStatsManager().getGiftsOpened(player.getUniqueId()));
        }
    }

    /** Schedules removal of the chest after {@code gifts.lifetimeSeconds}. */
    private void scheduleLifetime(Location chestLoc) {
        WrappedTask old = giftLifetimeTasks.remove(chestLoc);
        if (old != null) old.cancel();

        int lifetime = Math.max(10, plugin.getConfig().getInt("gifts.lifetimeSeconds", 300));
        WrappedTask lifetimeTask = scheduler.runAtLocationLater(chestLoc, () -> {
            giftLifetimeTasks.remove(chestLoc);
            // Always untrack, even if players already broke the chest (otherwise the set grows forever)
            trackedGifts.remove(chestLoc);
            removeIfMarked(chestLoc);
        }, lifetime * 20L);
        if (lifetimeTask != null) {
            giftLifetimeTasks.put(chestLoc, lifetimeTask);
        }
    }

    /**
     * Fills the chest from the three loot tables. Counts and rare chances come from
     * {@code gifts.contents.*}; every table entry may use the extended format (see LootManager).
     */
    private void fillGiftInventory(Inventory inv) {
        LootManager loot = plugin.getLootManager();
        List<LootManager.LootEntry> common = loot.getList("gifts.lootTables.common");
        List<LootManager.LootEntry> extra  = loot.getList("gifts.lootTables.extra");
        List<LootManager.LootEntry> rare   = loot.getList("gifts.lootTables.rare");

        int base = LootManager.randomInRange(plugin.getConfig().getString("gifts.contents.commonItems"), 4, 7);
        for (int i = 0; i < base; i++) add(inv, loot, common);
        int deco = LootManager.randomInRange(plugin.getConfig().getString("gifts.contents.extraItems"), 1, 3);
        for (int i = 0; i < deco; i++) add(inv, loot, extra);
        int rares = 0;
        if (!rare.isEmpty()) {
            if (random.nextDouble() < plugin.getConfig().getDouble("gifts.contents.rareChance", 0.6)) rares = 1;
            if (random.nextDouble() < plugin.getConfig().getDouble("gifts.contents.secondRareChance", 0.25)) rares = 2;
        }
        for (int i = 0; i < rares; i++) add(inv, loot, rare);
    }

    /** Adds one weighted random entry of the list to the inventory. */
    private void add(Inventory inv, LootManager loot, List<LootManager.LootEntry> list) {
        LootManager.LootEntry entry = loot.pick(list);
        if (entry == null) return;
        ItemStack stack = loot.build(entry);
        if (stack != null) inv.addItem(stack);
    }
}
