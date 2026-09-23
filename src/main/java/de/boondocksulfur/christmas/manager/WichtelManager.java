package de.boondocksulfur.christmas.manager;

import org.bukkit.*;
import org.bukkit.entity.*;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.util.LanguageManager;
import de.boondocksulfur.christmas.util.SpawnUtil;
import de.boondocksulfur.christmas.util.FoliaSchedulerHelper;

import java.util.*;

/**
 * Spawns "Wichtel" (baby zombies) and "Elves" (allays) that collect decoration items
 * and hop around. Mobs are tagged with scoreboard tags so they can be recognised and
 * adopted after a restart or reload.
 */
public class WichtelManager {

    public static final String TAG_WICHTEL = "XMAS_WICHTEL";
    public static final String TAG_ELF     = "XMAS_ELF";

    private final ChristmasSeason plugin;
    private final LanguageManager lang;
    private final FoliaSchedulerHelper scheduler;
    private final Random random = new Random();

    private final Map<UUID, WrappedTask> playerWichtelTasks = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, WrappedTask> playerElfTasks = new java.util.concurrent.ConcurrentHashMap<>();

    /** Per-mob AI tasks (entity scheduler). */
    private final Map<UUID, WrappedTask> entityStealTasks = new java.util.concurrent.ConcurrentHashMap<>();

    /** Tracked mobs; mutated from several region threads. */
    private final Set<UUID> trackedWichtel = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<UUID> trackedElfen   = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public WichtelManager(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.scheduler = plugin.getFoliaScheduler();
    }

    // -------------------------------------------------------------- lifecycle

    public void start() {
        stop();
        plugin.debug("WichtelManager started (per-player spawns, per-entity AI)");
    }

    /**
     * Stops spawn and AI timers. Tracking sets are kept so that {@code /xmas reload}
     * keeps the spawn caps intact; AI tasks are re-armed by {@link #adoptLoaded()}.
     */
    public void stop() {
        for (WrappedTask task : playerWichtelTasks.values()) {
            if (task != null) task.cancel();
        }
        for (WrappedTask task : playerElfTasks.values()) {
            if (task != null) task.cancel();
        }
        playerWichtelTasks.clear();
        playerElfTasks.clear();

        for (WrappedTask task : entityStealTasks.values()) {
            if (task != null) task.cancel();
        }
        entityStealTasks.clear();
    }

    /** Starts the Wichtel and elf spawn timers for a player. */
    public void startPlayerSpawning(Player player) {
        UUID uuid = player.getUniqueId();

        WrappedTask oldWichtel = playerWichtelTasks.remove(uuid);
        if (oldWichtel != null) oldWichtel.cancel();
        WrappedTask oldElf = playerElfTasks.remove(uuid);
        if (oldElf != null) oldElf.cancel();

        if (plugin.getConfig().getBoolean("wichtel.enabled", true)) {
            int interval = Math.max(5, plugin.getConfig().getInt("wichtel.spawnIntervalSeconds", 45));
            WrappedTask task = scheduler.runForEntityTimer(player, () -> {
                if (!player.isOnline() || !player.isValid()) {
                    stopPlayerSpawning(player);
                    return;
                }
                spawnWichtelNearPlayer(player);
            }, () -> playerWichtelTasks.remove(uuid), 40L, interval * 20L);
            if (task != null) playerWichtelTasks.put(uuid, task);
        }

        if (plugin.getConfig().getBoolean("elves.enabled", true)) {
            int interval = Math.max(5, plugin.getConfig().getInt("elves.spawnIntervalSeconds", 60));
            WrappedTask task = scheduler.runForEntityTimer(player, () -> {
                if (!player.isOnline() || !player.isValid()) {
                    stopPlayerSpawning(player);
                    return;
                }
                spawnElfNearPlayer(player);
            }, () -> playerElfTasks.remove(uuid), 60L, interval * 20L);
            if (task != null) playerElfTasks.put(uuid, task);
        }

        plugin.debug("Wichtel/elf spawning started for " + player.getName());
    }

    /** Stops the spawn timers for a player. */
    public void stopPlayerSpawning(Player player) {
        UUID uuid = player.getUniqueId();
        WrappedTask wichtelTask = playerWichtelTasks.remove(uuid);
        if (wichtelTask != null) wichtelTask.cancel();
        WrappedTask elfTask = playerElfTasks.remove(uuid);
        if (elfTask != null) elfTask.cancel();
        plugin.debug("Wichtel/elf spawning stopped for " + player.getName());
    }

    // --------------------------------------------------------------- tracking

    /** @return number of tracked Wichtel and elves */
    public int getTrackedCount() {
        return trackedWichtel.size() + trackedElfen.size();
    }

    /** @return {@code true} if the entity carries one of our tags */
    public boolean isEventMob(Entity entity) {
        Set<String> tags = entity.getScoreboardTags();
        return tags.contains(TAG_WICHTEL) || tags.contains(TAG_ELF);
    }

    /**
     * Adopts a tagged mob (after restart/reload or chunk load): tracks it, starts its
     * AI task and a fresh lifetime timer. Must run on the entity's thread.
     */
    public void adopt(Entity entity) {
        if (!(entity instanceof LivingEntity living) || !entity.isValid()) return;
        Set<String> tags = entity.getScoreboardTags();
        UUID id = entity.getUniqueId();

        Set<UUID> tracker;
        if (tags.contains(TAG_WICHTEL)) tracker = trackedWichtel;
        else if (tags.contains(TAG_ELF)) tracker = trackedElfen;
        else return;

        boolean newlyTracked = tracker.add(id);
        // After a chunk reload the old task holds a stale handle - always re-arm with the live one
        WrappedTask old = entityStealTasks.remove(id);
        if (old != null) old.cancel();
        startEntityStealTask(living);
        if (newlyTracked) {
            scheduleLifetime(living, tracker);
            plugin.debug("Adopted event mob " + entity.getType() + " " + id);
        }
    }

    /** Scans all loaded chunks of the mob worlds and adopts tagged mobs. */
    public void adoptLoaded() {
        for (World w : mobWorlds()) {
            scheduler.forEachLoadedChunk(w, chunk -> {
                for (Entity e : chunk.getEntities()) {
                    if (isEventMob(e)) adopt(e);
                }
            });
        }
    }

    /** Snow worlds plus the optional extra {@code wichtel.world} / {@code elves.world}. */
    private java.util.List<World> mobWorlds() {
        java.util.LinkedHashSet<World> worlds = new java.util.LinkedHashSet<>(plugin.getSnowWorlds());
        for (String key : new String[]{"wichtel.world", "elves.world"}) {
            String extra = plugin.getConfig().getString(key);
            if (extra != null && !extra.isBlank()) {
                World w = Bukkit.getWorld(extra);
                if (w != null) worlds.add(w);
            }
        }
        return new java.util.ArrayList<>(worlds);
    }

    private boolean isMobWorld(World w, String key) {
        String extra = plugin.getConfig().getString(key);
        if (extra != null && !extra.isBlank() && extra.equals(w.getName())) return true;
        return plugin.isSnowWorld(w);
    }

    /** Removes all Wichtel and elves ({@code /xmas off}): tracked ones plus untracked ones in loaded chunks. */
    public void cleanup() {
        int removed = 0;
        int wichtelCount = trackedWichtel.size();
        int elfenCount = trackedElfen.size();

        removed += removeTracked(trackedWichtel);
        removed += removeTracked(trackedElfen);

        for (World w : mobWorlds()) {
            scheduler.forEachLoadedChunk(w, chunk -> {
                for (Entity e : chunk.getEntities()) {
                    if (isEventMob(e)) e.remove();
                }
            });
        }

        lang.logInfo("log.cleanup.wichtel", removed, wichtelCount, elfenCount);
    }

    private int removeTracked(Set<UUID> tracker) {
        int removed = 0;
        Iterator<UUID> it = tracker.iterator();
        while (it.hasNext()) {
            UUID uuid = it.next();
            WrappedTask stealTask = entityStealTasks.remove(uuid);
            if (stealTask != null) stealTask.cancel();

            Entity e = Bukkit.getEntity(uuid);
            if (e != null && e.isValid()) {
                scheduler.runForEntity(e, () -> {
                    if (!e.isDead() && e.isValid()) e.remove();
                });
                removed++;
            }
            it.remove();
        }
        return removed;
    }

    // --------------------------------------------------------------- spawning

    /** Spawns a Wichtel near the player (on the region thread). */
    private void spawnWichtelNearPlayer(Player player) {
        World w = player.getWorld();
        if (!isMobWorld(w, "wichtel.world")) return;

        final int maxWichtel = plugin.getConfig().getInt("wichtel.maxPerWorld", 6);
        if (trackedWichtel.size() >= maxWichtel) return;
        if (plugin.isNearCapReached(player, "wichtel.maxNearPlayer", e -> e.getScoreboardTags().contains(TAG_WICHTEL))) return;

        Location playerLoc = player.getLocation();
        scheduler.runAtLocation(playerLoc, () -> {
            // Re-check: parallel spawns for other players may have filled the cap meanwhile
            if (trackedWichtel.size() >= maxWichtel) return;

            Location spawn = SpawnUtil.findSafeSpawnLocation(w, playerLoc, 10, 5);
            if (spawn == null) return;
            if (plugin.getRegionIntegration() != null && !plugin.getRegionIntegration().canSpawnAt(spawn)) {
                plugin.debug("Wichtel spawn blocked by region protection at " + spawn.getBlockX() + "," + spawn.getBlockZ());
                return;
            }

            Zombie z = (Zombie) w.spawnEntity(spawn, EntityType.ZOMBIE);
            z.setBaby();
            z.customName(lang.getComponent("entity.wichtel"));
            z.setCustomNameVisible(true);
            z.setRemoveWhenFarAway(false);
            z.getScoreboardTags().add(TAG_WICHTEL);
            if (z.getEquipment() != null) z.getEquipment().clear();
            z.setTarget(null);
            trackedWichtel.add(z.getUniqueId());

            startEntityStealTask(z);
            scheduleLifetime(z, trackedWichtel);
        });
    }

    /** Spawns an elf near the player (on the region thread). */
    private void spawnElfNearPlayer(Player player) {
        World w = player.getWorld();
        if (!isMobWorld(w, "elves.world")) return;

        final int maxElfen = plugin.getConfig().getInt("elves.maxPerWorld", 4);
        if (trackedElfen.size() >= maxElfen) return;
        if (plugin.isNearCapReached(player, "elves.maxNearPlayer", e -> e.getScoreboardTags().contains(TAG_ELF))) return;

        Location playerLoc = player.getLocation();
        scheduler.runAtLocation(playerLoc, () -> {
            if (trackedElfen.size() >= maxElfen) return;

            Location spawn = SpawnUtil.findSafeSpawnLocation(w, playerLoc, 10, 5);
            if (spawn == null) return;
            if (plugin.getRegionIntegration() != null && !plugin.getRegionIntegration().canSpawnAt(spawn)) {
                plugin.debug("Elf spawn blocked by region protection at " + spawn.getBlockX() + "," + spawn.getBlockZ());
                return;
            }

            Allay a = (Allay) w.spawnEntity(spawn, EntityType.ALLAY);
            a.customName(lang.getComponent("entity.elf"));
            a.setCustomNameVisible(true);
            a.setRemoveWhenFarAway(false);
            a.setCanPickupItems(false);
            a.getScoreboardTags().add(TAG_ELF);
            trackedElfen.add(a.getUniqueId());

            startEntityStealTask(a);
            scheduleLifetime(a, trackedElfen);
        });
    }

    /**
     * Removes the mob after {@code wichtel.lifetimeSeconds}. On Folia the task is retired
     * if the mob dies earlier - the retired callback keeps the tracking set clean, otherwise
     * dead UUIDs would fill the spawn cap.
     */
    private void scheduleLifetime(LivingEntity entity, Set<UUID> tracker) {
        int wichtelLifetime = plugin.getConfig().getInt("wichtel.lifetimeSeconds", 240);
        int lifetime = Math.max(10, tracker == trackedElfen
                ? plugin.getConfig().getInt("elves.lifetimeSeconds", wichtelLifetime)
                : wichtelLifetime);
        UUID id = entity.getUniqueId();
        Runnable cleanup = () -> {
            tracker.remove(id);
            WrappedTask st = entityStealTasks.remove(id);
            if (st != null) st.cancel();
        };
        scheduler.runForEntityLater(entity, () -> {
            // Resolve by UUID: on Paper the handle may be stale after a chunk reload
            Entity live = Bukkit.getEntity(id);
            if (live != null && live.isValid()) live.remove();
            cleanup.run();
        }, cleanup, lifetime * 20L);
    }

    /**
     * Per-mob AI on the entity scheduler: collects decoration items in reach and hops
     * around randomly.
     *
     * <p>By default only decoration items are collected ({@code wichtel.stealOnlyDecorations}).
     * With the option off, any item without a thrower is collected - never items a player
     * dropped or death drops, which always carry the player's UUID.
     */
    private void startEntityStealTask(LivingEntity entity) {
        UUID entityId = entity.getUniqueId();
        double radius = plugin.getConfig().getDouble("wichtel.stealRadius", 3.2);
        boolean onlyDecorations = plugin.getConfig().getBoolean("wichtel.stealOnlyDecorations", true);
        double tpChance = 0.3;

        WrappedTask task = scheduler.runForEntityTimer(entity, () -> {
            if (!entity.isValid() || entity.isDead()) {
                WrappedTask oldTask = entityStealTasks.remove(entityId);
                if (oldTask != null) oldTask.cancel();
                trackedWichtel.remove(entityId);
                trackedElfen.remove(entityId);
                return;
            }

            for (Entity near : entity.getNearbyEntities(radius, radius, radius)) {
                if (!(near instanceof Item item)) continue;
                boolean allowed = onlyDecorations
                        ? plugin.getDecorationManager().isDecoration(item)
                        : item.getThrower() == null;
                if (!allowed) continue;
                item.remove();
                entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.2f);
            }

            if (random.nextDouble() < tpChance) {
                int dx = random.nextInt(3) - 1;
                int dz = random.nextInt(3) - 1;
                if (dx == 0 && dz == 0) return;
                Location newLoc = entity.getLocation().add(dx, 0, dz);
                // Only hop into free space with ground below (never into walls or off ledges)
                if (!newLoc.getBlock().isPassable()
                        || !newLoc.clone().add(0, 1, 0).getBlock().isPassable()
                        || newLoc.clone().add(0, -1, 0).getBlock().isPassable()) {
                    return;
                }
                if (scheduler.isFolia()) {
                    entity.teleportAsync(newLoc);
                } else {
                    entity.teleport(newLoc);
                }
            }
        }, () -> {
            // Retired: the mob was removed - drop it from tracking immediately
            entityStealTasks.remove(entityId);
            trackedWichtel.remove(entityId);
            trackedElfen.remove(entityId);
        }, 40L, 40L);

        if (task != null) {
            entityStealTasks.put(entityId, task);
        }
    }
}
