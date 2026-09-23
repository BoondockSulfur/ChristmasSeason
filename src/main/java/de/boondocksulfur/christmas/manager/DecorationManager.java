package de.boondocksulfur.christmas.manager;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.util.LanguageManager;
import de.boondocksulfur.christmas.util.SpawnUtil;
import de.boondocksulfur.christmas.util.FoliaSchedulerHelper;

import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Drops glowing decoration items near players. Items carry a PersistentDataContainer
 * marker so they can be adopted after a restart and so that elves only steal these.
 */
public class DecorationManager {

    private final ChristmasSeason plugin;
    private final LanguageManager lang;
    private final FoliaSchedulerHelper scheduler;
    private final Random random = new Random();
    private final NamespacedKey decorationKey;

    private final java.util.Map<UUID, WrappedTask> playerSpawnTasks = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<UUID> trackedDecorations = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public DecorationManager(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.scheduler = plugin.getFoliaScheduler();
        this.decorationKey = new NamespacedKey(plugin, "decoration");
    }

    public void start() {
        stop();
        plugin.debug("DecorationManager started (per-player spawns)");
    }

    /** Stops the spawn timers; tracked items keep their lifetime tasks. */
    public void stop() {
        for (WrappedTask task : playerSpawnTasks.values()) {
            if (task != null) task.cancel();
        }
        playerSpawnTasks.clear();
    }

    /** Starts decoration spawning for a player on the player's entity scheduler. */
    public void startPlayerSpawning(Player player) {
        if (!plugin.getConfig().getBoolean("decoration.enabled", true)) return;

        UUID uuid = player.getUniqueId();
        WrappedTask oldTask = playerSpawnTasks.remove(uuid);
        if (oldTask != null) oldTask.cancel();

        int interval = Math.max(5, plugin.getConfig().getInt("decoration.intervalSeconds", 25));
        double spawnChance = plugin.getConfig().getDouble("decoration.spawnChance", 0.9);

        WrappedTask task = scheduler.runForEntityTimer(player, () -> {
            if (!player.isOnline() || !player.isValid()) {
                stopPlayerSpawning(player);
                return;
            }
            if (random.nextDouble() <= spawnChance) {
                spawnDecorationNearPlayer(player);
            }
        }, () -> playerSpawnTasks.remove(uuid), 40L, interval * 20L);

        if (task != null) {
            playerSpawnTasks.put(uuid, task);
            plugin.debug("Decoration spawning started for " + player.getName());
        }
    }

    /** Stops decoration spawning for a player. */
    public void stopPlayerSpawning(Player player) {
        WrappedTask task = playerSpawnTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
            plugin.debug("Decoration spawning stopped for " + player.getName());
        }
    }

    /** @return {@code true} if the entity is one of our decoration items */
    public boolean isDecoration(Entity entity) {
        return entity instanceof Item
                && entity.getPersistentDataContainer().has(decorationKey, PersistentDataType.BYTE);
    }

    /** Adopts a decoration item found after a restart: tracks it and starts a fresh lifetime. */
    public void adopt(Item item) {
        UUID id = item.getUniqueId();
        if (!trackedDecorations.add(id)) return;
        scheduleLifetime(item);
        plugin.debug("Adopted decoration item " + id);
    }

    /** @return number of tracked decoration items */
    public int getTrackedCount() {
        return trackedDecorations.size();
    }

    /** Scans all loaded chunks of the snow worlds for decoration items and adopts them. */
    public void adoptLoaded() {
        for (World w : plugin.getSnowWorlds()) {
            scheduler.forEachLoadedChunk(w, chunk -> {
                for (Entity e : chunk.getEntities()) {
                    if (isDecoration(e)) adopt((Item) e);
                }
            });
        }
    }

    /** Removes all decoration items ({@code /xmas off}): tracked ones plus untracked ones in loaded chunks. */
    public void cleanup() {
        int removed = 0;
        int tracked = trackedDecorations.size();

        java.util.Iterator<UUID> it = trackedDecorations.iterator();
        while (it.hasNext()) {
            UUID uuid = it.next();
            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null && entity.isValid() && entity instanceof Item) {
                scheduler.runForEntity(entity, () -> {
                    if (!entity.isDead() && entity.isValid()) entity.remove();
                });
                removed++;
            }
            it.remove();
        }

        for (World w : plugin.getSnowWorlds()) {
            scheduler.forEachLoadedChunk(w, chunk -> {
                for (Entity e : chunk.getEntities()) {
                    if (isDecoration(e)) e.remove();
                }
            });
        }

        lang.logInfo("log.cleanup.decorations", removed);
        if (tracked > removed && plugin.isDebugMode()) {
            plugin.debug("Decorations: " + removed + " removed, " + (tracked - removed) + " already gone");
        }
    }

    /** Drops one decoration item near the player (on the region thread). */
    private void spawnDecorationNearPlayer(Player player) {
        World w = player.getWorld();
        if (!plugin.isSnowWorld(w)) return;

        List<String> drops = plugin.getConfig().getStringList("decoration.drops");
        if (drops.isEmpty()) return;

        Location playerLoc = player.getLocation();
        scheduler.runAtLocation(playerLoc, () -> {
            Location place = SpawnUtil.findSafeSpawnLocation(w, playerLoc, 7, 5);
            if (place == null) return;
            if (plugin.getRegionIntegration() != null && !plugin.getRegionIntegration().canSpawnAt(place)) {
                plugin.debug("Decoration spawn blocked by region protection at " + place.getBlockX() + "," + place.getBlockZ());
                return;
            }
            place = place.add(0, 0.5, 0);

            String entry = drops.get(random.nextInt(drops.size()));
            String[] split = entry.split(":");
            Material mat = Material.matchMaterial(split[0]);
            if (mat == null) return;
            int amount = 1;
            if (split.length > 1) try { amount = Integer.parseInt(split[1]); } catch (NumberFormatException ignored) {}

            ItemStack stack = new ItemStack(mat, Math.max(1, amount));
            net.kyori.adventure.text.Component name = lang.getComponent("entity.decoration");
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) { meta.displayName(name); stack.setItemMeta(meta); }

            Item item = w.dropItem(place, stack);
            item.customName(name);
            item.setCustomNameVisible(true);
            item.setPickupDelay(plugin.getConfig().getInt("decoration.pickupDelayTicks", 0));
            item.getPersistentDataContainer().set(decorationKey, PersistentDataType.BYTE, (byte) 1);
            try { item.setGlowing(plugin.getConfig().getBoolean("decoration.glow", true)); } catch (Throwable ignored) {}

            trackedDecorations.add(item.getUniqueId());
            scheduleLifetime(item);
        });
    }

    /** Removes the item after {@code decoration.lifetimeSeconds}; retired callback keeps the set clean. */
    private void scheduleLifetime(Item item) {
        int lifetime = Math.max(5, plugin.getConfig().getInt("decoration.lifetimeSeconds", 180));
        UUID itemId = item.getUniqueId();
        scheduler.runForEntityLater(item, () -> {
            // Resolve by UUID: on Paper the handle may be stale after a chunk reload
            Entity live = Bukkit.getEntity(itemId);
            if (live != null && live.isValid()) live.remove();
            trackedDecorations.remove(itemId);
        }, () -> trackedDecorations.remove(itemId), lifetime * 20L);
    }
}
