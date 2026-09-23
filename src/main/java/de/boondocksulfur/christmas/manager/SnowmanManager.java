package de.boondocksulfur.christmas.manager;

import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Snowman;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.util.LanguageManager;
import de.boondocksulfur.christmas.util.SpawnUtil;
import de.boondocksulfur.christmas.util.FoliaSchedulerHelper;

import java.util.Random;
import java.util.UUID;

/**
 * Spawns snow golems that throw (harmless) snowballs at nearby players.
 * Golems never despawn on their own, so they are tracked by tag and adopted after
 * restarts; {@code snowmen.lifetimeSeconds} removes them eventually.
 */
public class SnowmanManager {

    public static final String TAG = "XMAS_SNOWMAN";
    public static final String SNOWBALL_TAG = "XMAS_SNOWBALL";

    private final ChristmasSeason plugin;
    private final LanguageManager lang;
    private final FoliaSchedulerHelper scheduler;
    private final Random random = new Random();

    private final java.util.Map<UUID, WrappedTask> playerSpawnTasks = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<UUID, WrappedTask> entityAttackTasks = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<UUID> trackedSnowmen = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public SnowmanManager(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.scheduler = plugin.getFoliaScheduler();
    }

    public void start() {
        stop();
        if (!plugin.getConfig().getBoolean("snowmen.enabled", true)) return;
        plugin.debug("SnowmanManager started (per-player spawns, per-entity AI)");
    }

    /** Stops spawn and attack timers; tracking is kept and re-armed by {@link #adoptLoaded()}. */
    public void stop() {
        for (WrappedTask task : playerSpawnTasks.values()) {
            if (task != null) task.cancel();
        }
        playerSpawnTasks.clear();

        for (WrappedTask task : entityAttackTasks.values()) {
            if (task != null) task.cancel();
        }
        entityAttackTasks.clear();
    }

    /** Starts snowman spawning for a player on the player's entity scheduler. */
    public void startPlayerSpawning(Player player) {
        if (!plugin.getConfig().getBoolean("snowmen.enabled", true)) return;

        UUID uuid = player.getUniqueId();
        WrappedTask oldTask = playerSpawnTasks.remove(uuid);
        if (oldTask != null) oldTask.cancel();

        int interval = Math.max(5, plugin.getConfig().getInt("snowmen.spawnIntervalSeconds", 30));
        WrappedTask task = scheduler.runForEntityTimer(player, () -> {
            if (!player.isOnline() || !player.isValid()) {
                stopPlayerSpawning(player);
                return;
            }
            spawnSnowmanNearPlayer(player);
        }, () -> playerSpawnTasks.remove(uuid), 40L, interval * 20L);

        if (task != null) {
            playerSpawnTasks.put(uuid, task);
            plugin.debug("Snowman spawning started for " + player.getName());
        }
    }

    /** Stops snowman spawning for a player. */
    public void stopPlayerSpawning(Player player) {
        WrappedTask task = playerSpawnTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
            plugin.debug("Snowman spawning stopped for " + player.getName());
        }
    }

    /** @return number of tracked snow golems */
    public int getTrackedCount() {
        return trackedSnowmen.size();
    }

    /** @return {@code true} if the entity is one of our snow golems */
    public boolean isEventSnowman(Entity entity) {
        return entity instanceof Snowman && entity.getScoreboardTags().contains(TAG);
    }

    /** Adopts a tagged snow golem: tracks it and starts its attack task and lifetime. */
    public void adopt(Entity entity) {
        if (!isEventSnowman(entity) || !entity.isValid()) return;
        Snowman sm = (Snowman) entity;
        boolean newlyTracked = trackedSnowmen.add(sm.getUniqueId());
        // After a chunk reload the old task holds a stale handle - always re-arm with the live one
        WrappedTask old = entityAttackTasks.remove(sm.getUniqueId());
        if (old != null) old.cancel();
        startEntityAttackTask(sm);
        if (newlyTracked) {
            scheduleLifetime(sm);
            plugin.debug("Adopted snowman " + sm.getUniqueId());
        }
    }

    /** Scans all loaded chunks of the snow worlds and adopts tagged snow golems. */
    public void adoptLoaded() {
        for (World w : plugin.getSnowWorlds()) {
            scheduler.forEachLoadedChunk(w, chunk -> {
                for (Entity e : chunk.getEntities()) {
                    if (isEventSnowman(e)) adopt(e);
                }
            });
        }
    }

    /** Removes all snow golems ({@code /xmas off}): tracked ones plus untracked ones in loaded chunks. */
    public void cleanup() {
        int removed = 0;
        int tracked = trackedSnowmen.size();

        java.util.Iterator<UUID> it = trackedSnowmen.iterator();
        while (it.hasNext()) {
            UUID uuid = it.next();
            WrappedTask attackTask = entityAttackTasks.remove(uuid);
            if (attackTask != null) attackTask.cancel();

            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null && entity.isValid() && entity instanceof Snowman) {
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
                    if (isEventSnowman(e)) e.remove();
                }
            });
        }

        lang.logInfo("log.cleanup.snowmen", removed);
        if (tracked > removed && plugin.isDebugMode()) {
            plugin.debug("Snowmen: " + removed + " removed, " + (tracked - removed) + " already gone");
        }
    }

    /** Spawns a snow golem near the player (on the region thread). */
    private void spawnSnowmanNearPlayer(Player player) {
        World w = player.getWorld();
        if (!plugin.isSnowWorld(w)) return;

        final int max = plugin.getConfig().getInt("snowmen.maxPerWorld", 6);
        if (trackedSnowmen.size() >= max) return;
        if (plugin.isNearCapReached(player, "snowmen.maxNearPlayer", e -> isEventSnowman(e))) return;

        Location playerLoc = player.getLocation();
        scheduler.runAtLocation(playerLoc, () -> {
            if (trackedSnowmen.size() >= max) return;

            // Strict water check: snow golems melt in water
            Location loc = SpawnUtil.findSafeSpawnLocation(w, playerLoc, 10, 5, true);
            if (loc == null) return;
            if (plugin.getRegionIntegration() != null && !plugin.getRegionIntegration().canSpawnAt(loc)) {
                plugin.debug("Snowman spawn blocked by region protection at " + loc.getBlockX() + "," + loc.getBlockZ());
                return;
            }

            Snowman sm = w.spawn(loc, Snowman.class);
            sm.customName(lang.getComponent("entity.snowman"));
            sm.setCustomNameVisible(true);
            sm.getScoreboardTags().add(TAG);
            sm.setDerp(false);

            trackedSnowmen.add(sm.getUniqueId());
            startEntityAttackTask(sm);
            scheduleLifetime(sm);
        });
    }

    /** Removes the golem after {@code snowmen.lifetimeSeconds} (0 = never). */
    private void scheduleLifetime(Snowman snowman) {
        int lifetime = plugin.getConfig().getInt("snowmen.lifetimeSeconds", 600);
        if (lifetime <= 0) return;
        UUID id = snowman.getUniqueId();
        Runnable cleanup = () -> {
            trackedSnowmen.remove(id);
            WrappedTask t = entityAttackTasks.remove(id);
            if (t != null) t.cancel();
        };
        scheduler.runForEntityLater(snowman, () -> {
            // Resolve by UUID: on Paper the handle may be stale after a chunk reload
            Entity live = Bukkit.getEntity(id);
            if (live != null && live.isValid()) live.remove();
            cleanup.run();
        }, cleanup, lifetime * 20L);
    }

    /** Per-golem AI on the entity scheduler: throws a snowball at the nearest player in range. */
    private void startEntityAttackTask(Snowman snowman) {
        UUID snowmanId = snowman.getUniqueId();
        double range = plugin.getConfig().getDouble("snowmen.range", 12.0);
        double chance = plugin.getConfig().getDouble("snowmen.attackChance", 0.35);
        int attackInterval = Math.max(1, plugin.getConfig().getInt("snowmen.attackIntervalSeconds", 5));

        WrappedTask task = scheduler.runForEntityTimer(snowman, () -> {
            if (!snowman.isValid() || snowman.isDead()) {
                WrappedTask oldTask = entityAttackTasks.remove(snowmanId);
                if (oldTask != null) oldTask.cancel();
                trackedSnowmen.remove(snowmanId);
                return;
            }

            if (random.nextDouble() > chance) return;

            Player target = null;
            double bestDistSq = Double.MAX_VALUE;
            for (Player p : snowman.getWorld().getPlayers()) {
                // Spectators, and players with the bypass permission, are never targeted
                if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR || p.hasPermission("xmas.bypass.snowmen")) continue;
                double distSq = p.getLocation().distanceSquared(snowman.getLocation());
                if (distSq <= range * range && distSq < bestDistSq) {
                    bestDistSq = distSq;
                    target = p;
                }
            }
            if (target == null) return;

            org.bukkit.util.Vector direction = target.getLocation().toVector()
                    .subtract(snowman.getLocation().toVector());
            if (direction.lengthSquared() < 0.01) return; // same position: normalising would give NaN
            direction.normalize().multiply(1.1);

            Snowball ball = snowman.launchProjectile(Snowball.class);
            ball.addScoreboardTag(SNOWBALL_TAG);
            ball.setVelocity(direction);

        }, () -> {
            // Retired: the golem was removed (killed/melted) - keep the spawn cap accurate
            entityAttackTasks.remove(snowmanId);
            trackedSnowmen.remove(snowmanId);
        }, 60L, attackInterval * 20L);

        if (task != null) {
            entityAttackTasks.put(snowmanId, task);
        }
    }
}
