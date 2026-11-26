package de.boondocksulfur.christmas.manager;

import org.bukkit.*;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Snowman;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.util.Lifetime;
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

    private final ChristmasSeason plugin;
    private final LanguageManager lang;
    private final FoliaSchedulerHelper scheduler;
    private final Random random = new Random();

    private final java.util.Map<UUID, WrappedTask> playerSpawnTasks = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<UUID, WrappedTask> entityAttackTasks = new java.util.concurrent.ConcurrentHashMap<>();
    /** Tracked golems (UUID -> world name, for the per-world cap). */
    private final java.util.Map<UUID, String> trackedSnowmen = new java.util.concurrent.ConcurrentHashMap<>();

    public SnowmanManager(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.scheduler = plugin.getFoliaScheduler();
    }

    public void start() {
        stop();
        if (!plugin.getMobConfig().getBoolean("snowmen.enabled", true)) return;
        plugin.debug("SnowmanManager started (per-player spawns, per-entity AI)");
    }

    /** Stops spawn and attack timers; tracking is kept and re-armed by {@link LoadedObjectSync}. */
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
        if (!plugin.getMobConfig().getBoolean("snowmen.enabled", true)) return;

        UUID uuid = player.getUniqueId();
        WrappedTask oldTask = playerSpawnTasks.remove(uuid);
        if (oldTask != null) oldTask.cancel();

        int interval = Math.max(5, plugin.getMobConfig().getInt("snowmen.spawnIntervalSeconds", 30));
        WrappedTask task = scheduler.runForEntityTimer(player, () -> {
            if (!player.isOnline() || !player.isValid()) {
                stopPlayerSpawning(player);
                return;
            }
            if (plugin.getOptOutManager().isOptedOut(player, OptOutManager.Category.SNOWMEN)) return;
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

    /** @return tracked golems in the world */
    private int countIn(World w) {
        int n = 0;
        for (String world : trackedSnowmen.values()) if (world.equals(w.getName())) n++;
        return n;
    }

    /** @return {@code true} if the entity is one of our snow golems */
    public boolean isEventSnowman(Entity entity) {
        return entity instanceof Snowman && entity.getScoreboardTags().contains(TAG);
    }

    /** Adopts a tagged snow golem: tracks it and starts its attack task and lifetime. */
    public void adopt(Entity entity) {
        if (!isEventSnowman(entity) || !entity.isValid()) return;
        Snowman sm = (Snowman) entity;
        boolean newlyTracked = trackedSnowmen.put(sm.getUniqueId(), sm.getWorld().getName()) == null;
        // After a chunk reload the old task holds a stale handle - always re-arm with the live one
        WrappedTask old = entityAttackTasks.remove(sm.getUniqueId());
        if (old != null) old.cancel();
        startEntityAttackTask(sm);
        if (newlyTracked) {
            scheduleLifetime(sm);
            plugin.debug("Adopted snowman " + sm.getUniqueId());
        }
    }

    /** Removes all tracked snow golems ({@code /xmas off}); untracked ones are found by {@link LoadedObjectSync}. */
    public void cleanup() {
        int removed = 0;
        int tracked = trackedSnowmen.size();

        java.util.Iterator<UUID> it = trackedSnowmen.keySet().iterator();
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

        lang.logInfo("log.cleanup.snowmen", removed);
        if (tracked > removed && plugin.isDebugMode()) {
            plugin.debug("Snowmen: " + removed + " removed, " + (tracked - removed) + " already gone");
        }
    }

    /** Spawns a snow golem near the player (on the region thread). */
    private void spawnSnowmanNearPlayer(Player player) {
        World w = player.getWorld();
        if (!plugin.isSnowWorld(w)) return;

        final int max = plugin.getMobConfig().getInt("snowmen.maxPerWorld", 6);
        if (countIn(w) >= max) return;
        if (plugin.isNearCapReached(player, "snowmen.maxNearPlayer", e -> isEventSnowman(e))) return;

        Location playerLoc = player.getLocation();
        scheduler.runAtLocation(playerLoc, () -> {
            if (countIn(w) >= max) return;

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

            trackedSnowmen.put(sm.getUniqueId(), w.getName());
            startEntityAttackTask(sm);
            scheduleLifetime(sm);
        });
    }

    /** Removes the golem after {@code snowmen.lifetimeSeconds} (0 = never). */
    private void scheduleLifetime(Snowman snowman) {
        int lifetime = plugin.getMobConfig().getInt("snowmen.lifetimeSeconds", 600);
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
        }, cleanup, Lifetime.ticksLeft(plugin, snowman, lifetime * 20L));
    }

    /**
     * Per-golem AI on the entity scheduler: throws a snowball at the nearest player or hostile
     * mob in range and sight. The vanilla targeting is switched off (see
     * {@code SnowmanDamageListener}), so this task alone sets the pace. Hits deal no damage.
     */
    private void startEntityAttackTask(Snowman snowman) {
        UUID snowmanId = snowman.getUniqueId();
        double range = plugin.getMobConfig().getDouble("snowmen.range", 12.0);
        double chance = plugin.getMobConfig().getDouble("snowmen.attackChance", 0.5);
        int attackInterval = Math.max(1, plugin.getMobConfig().getInt("snowmen.attackIntervalSeconds", 3));
        boolean attackPlayers = plugin.getMobConfig().getBoolean("snowmen.attackPlayers", true);
        boolean attackMobs = plugin.getMobConfig().getBoolean("snowmen.attackMobs", true);

        WrappedTask task = scheduler.runForEntityTimer(snowman, () -> {
            if (!snowman.isValid() || snowman.isDead()) {
                WrappedTask oldTask = entityAttackTasks.remove(snowmanId);
                if (oldTask != null) oldTask.cancel();
                trackedSnowmen.remove(snowmanId);
                return;
            }

            if (random.nextDouble() > chance) return;

            LivingEntity target = null;
            double bestDistSq = range * range;
            for (Entity e : snowman.getNearbyEntities(range, range, range)) {
                if (!(e instanceof LivingEntity le) || le.isDead()) continue;
                if (e instanceof Player p) {
                    // Spectators, players with the bypass permission and opted-out players are never targeted
                    if (!attackPlayers || p.getGameMode() == org.bukkit.GameMode.SPECTATOR || p.hasPermission("xmas.bypass.snowmen")
                            || plugin.getOptOutManager().isOptedOut(p, OptOutManager.Category.SNOWMEN)) continue;
                } else if (!attackMobs || !(e instanceof Enemy)) {
                    continue;
                }
                double distSq = le.getLocation().distanceSquared(snowman.getLocation());
                if (distSq < bestDistSq && snowman.hasLineOfSight(le)) {
                    bestDistSq = distSq;
                    target = le;
                }
            }
            if (target == null) return;

            org.bukkit.util.Vector direction = target.getEyeLocation().toVector()
                    .subtract(snowman.getEyeLocation().toVector());
            if (direction.lengthSquared() < 0.01) return; // same position: normalising would give NaN
            double distance = direction.length();
            direction.normalize().multiply(1.1);
            direction.setY(direction.getY() + 0.02 * distance); // slight arc

            snowman.launchProjectile(Snowball.class, direction);
            snowman.getWorld().playSound(snowman.getLocation(), org.bukkit.Sound.ENTITY_SNOW_GOLEM_SHOOT, 0.8f, 1f);

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
