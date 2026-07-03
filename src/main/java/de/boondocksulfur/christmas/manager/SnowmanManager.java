package de.boondocksulfur.christmas.manager;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Snowman;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.util.LanguageManager;
import de.boondocksulfur.christmas.util.SpawnUtil;
import de.boondocksulfur.christmas.util.FoliaSchedulerHelper;

import java.util.List;
import java.util.Random;

public class SnowmanManager {

    public static final String TAG = "XMAS_SNOWMAN";

    private final ChristmasSeason plugin;
    private final LanguageManager lang;
    private final FoliaSchedulerHelper scheduler;
    private final Random random = new Random();

    // FOLIA FIX: Player-basierte Spawn-Timer (Entity Scheduler)
    private final java.util.Map<java.util.UUID, WrappedTask> playerSpawnTasks = new java.util.concurrent.ConcurrentHashMap<>();

    // FOLIA FIX: Entity-basierte Attack-Tasks (Entity Scheduler pro Schneemann)
    private final java.util.Map<java.util.UUID, WrappedTask> entityAttackTasks = new java.util.concurrent.ConcurrentHashMap<>();

    // FOLIA FIX: Track spawned snowmen by UUID for safe cleanup and counting
    private final java.util.Set<java.util.UUID> trackedSnowmen = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public SnowmanManager(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.scheduler = plugin.getFoliaScheduler();
    }

    public void start() {
        stop();
        if (!plugin.getConfig().getBoolean("snowmen.enabled", true)) return;

        // FOLIA FIX: Spawn-Timer sind jetzt Player-basiert (siehe startPlayerSpawning)
        // FOLIA FIX: Attack-Tasks sind jetzt Entity-basiert (siehe startEntityAttackTask)
        plugin.debug("SnowmanManager gestartet (Folia-kompatibel: Player-basierte Spawns + Entity-basierte AI)");
    }

    public void stop() {
        // FOLIA FIX: Stoppe alle Player-basierten Tasks
        for (WrappedTask task : playerSpawnTasks.values()) {
            if (task != null) task.cancel();
        }
        playerSpawnTasks.clear();

        // FOLIA FIX: Stoppe alle Entity-basierten Attack-Tasks
        for (WrappedTask task : entityAttackTasks.values()) {
            if (task != null) task.cancel();
        }
        entityAttackTasks.clear();
        // Note: trackedSnowmen wird NICHT geleert - bleibt für cleanup() erhalten
    }

    /**
     * Startet Schneemann-Spawning für einen Spieler (Entity Scheduler)
     * FOLIA-KOMPATIBEL: Läuft auf Entity Scheduler des Players
     */
    public void startPlayerSpawning(Player player) {
        if (!plugin.getConfig().getBoolean("snowmen.enabled", true)) return;

        java.util.UUID uuid = player.getUniqueId();
        WrappedTask oldTask = playerSpawnTasks.remove(uuid);
        if (oldTask != null) oldTask.cancel();

        int interval = plugin.getConfig().getInt("snowmen.spawnIntervalSeconds", 30);
        WrappedTask task = scheduler.runForEntityTimer(player, () -> {
            if (!player.isOnline() || !player.isValid()) {
                stopPlayerSpawning(player);
                return;
            }
            spawnSnowmanNearPlayer(player);
        }, 40L, interval * 20L);

        if (task != null) {
            playerSpawnTasks.put(uuid, task);
            plugin.debug("Schneemann-Spawning gestartet für " + player.getName());
        }
    }

    /**
     * Stoppt Schneemann-Spawning für einen Spieler
     */
    public void stopPlayerSpawning(Player player) {
        WrappedTask task = playerSpawnTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
            plugin.debug("Schneemann-Spawning gestoppt für " + player.getName());
        }
    }

    /**
     * Entfernt alle Schneemänner aus der Welt
     * FOLIA-SAFE: Verwendet tracked UUIDs und schedult Entfernung pro Entity
     */
    public void cleanup() {
        int removed = 0;
        int tracked = trackedSnowmen.size();

        // FOLIA FIX: Iteriere über tracked UUIDs statt w.getEntitiesByClass()
        java.util.Iterator<java.util.UUID> it = trackedSnowmen.iterator();
        while (it.hasNext()) {
            java.util.UUID uuid = it.next();
            org.bukkit.entity.Entity entity = Bukkit.getEntity(uuid);

            if (entity != null && entity.isValid() && entity instanceof Snowman) {
                // FOLIA FIX: Cancel attack task if exists
                WrappedTask attackTask = entityAttackTasks.remove(uuid);
                if (attackTask != null) attackTask.cancel();

                // FOLIA FIX: Schedule removal auf Entity Scheduler
                scheduler.runForEntity(entity, () -> {
                    if (!entity.isDead() && entity.isValid()) {
                        entity.remove();
                    }
                });
                removed++;
            }
            it.remove(); // Aus Tracking entfernen
        }

        plugin.getLogger().info(lang.getMessage("log.cleanup.snowmen", removed));
        if (tracked > removed && plugin.isDebugMode()) {
            plugin.debug("Snowmen: " + removed + " removed, " + (tracked - removed) + " already gone");
        }
    }

    /**
     * Spawnt Schneemann in Nähe eines Spielers
     * FOLIA-KOMPATIBEL: Wird von Entity Scheduler des Players aufgerufen
     */
    private void spawnSnowmanNearPlayer(Player player) {
        World w = player.getWorld();
        String worldName = plugin.getConfig().getString("snowWorld", "world");
        if (!w.getName().equals(worldName)) return;

        // FOLIA FIX: Zähle tracked Schneemänner (global limit) - thread-safe!
        final int max = plugin.getConfig().getInt("snowmen.maxPerWorld", 6);
        if (trackedSnowmen.size() >= max) return;

        // FOLIA FIX: Spawne auf Location Scheduler (für getHighestBlockAt)
        Location playerLoc = player.getLocation();

        scheduler.runAtLocation(playerLoc, () -> {
            // OVERSPAWN FIX: Limit erneut prüfen - zwischen Einplanung und Ausführung
            // können parallele Spawns anderer Spieler das Limit schon erreicht haben!
            if (trackedSnowmen.size() >= max) return;

            // Safe-Spawn: 5 Versuche (Performance-optimiert, strenge Wasser/Wand-Checks)
            Location loc = SpawnUtil.findSafeSpawnLocation(w, playerLoc, 10, 5);

            Snowman sm = w.spawn(loc, Snowman.class);
            sm.customName(lang.getComponent("entity.snowman"));
            sm.setCustomNameVisible(true);
            sm.getScoreboardTags().add(TAG);
            sm.setDerp(false);

            // FOLIA FIX: Track spawned snowman
            trackedSnowmen.add(sm.getUniqueId());

            // FOLIA FIX: Starte Entity Scheduler Task für Attack-Logik
            startEntityAttackTask(sm);
        });
    }

    /**
     * Startet einen Entity Scheduler Task für Attack-Logik
     * FOLIA-KOMPATIBEL: Läuft auf Entity Scheduler der Schneemann-Entity
     */
    private void startEntityAttackTask(Snowman snowman) {
        java.util.UUID snowmanId = snowman.getUniqueId();
        double range = plugin.getConfig().getDouble("snowmen.range", 12.0);
        double chance = plugin.getConfig().getDouble("snowmen.attackChance", 0.35);
        int attackInterval = plugin.getConfig().getInt("snowmen.attackIntervalSeconds", 5);

        // FOLIA FIX: Entity Scheduler Task für diesen spezifischen Schneemann
        WrappedTask task = scheduler.runForEntityTimer(snowman, () -> {
            // Entity-State-Zugriff ist sicher, weil wir auf Entity Scheduler laufen!
            if (!snowman.isValid() || snowman.isDead()) {
                WrappedTask oldTask = entityAttackTasks.remove(snowmanId);
                if (oldTask != null) oldTask.cancel();
                trackedSnowmen.remove(snowmanId); // Remove from tracking when dead
                return;
            }

            // Attack-Chance prüfen
            if (random.nextDouble() > chance) return;

            // Finde nächsten Spieler in Range
            Player target = null;
            double bestDistSq = Double.MAX_VALUE;
            World w = snowman.getWorld();

            for (Player p : w.getPlayers()) {
                double distSq = p.getLocation().distanceSquared(snowman.getLocation());
                if (distSq <= range * range && distSq < bestDistSq) {
                    bestDistSq = distSq;
                    target = p;
                }
            }

            if (target == null) return;

            // Richtungsvektor berechnen und auf NaN prüfen (falls Schneemann und Spieler auf gleicher Position)
            org.bukkit.util.Vector direction = target.getLocation().toVector()
                    .subtract(snowman.getLocation().toVector());

            // Wenn die Entfernung zu klein ist, überspringe den Angriff
            if (direction.lengthSquared() < 0.01) return;

            direction.normalize().multiply(1.1);

            // Schneeball abfeuern
            // Marker als Scoreboard-Tag statt deprecated setCustomName -
            // der Name wurde nirgends gelesen, Tags sind die saubere Kennung
            Snowball ball = snowman.launchProjectile(Snowball.class);
            ball.addScoreboardTag("XMAS_SNOWBALL");
            ball.setVelocity(direction);

        }, () -> {
            // FOLIA FIX: retired - Schneemann wurde entfernt (getötet/geschmolzen),
            // bevor der Task lief. Ohne diesen Callback bliebe die UUID im Tracking
            // und das Spawn-Limit wäre irgendwann dauerhaft voll!
            entityAttackTasks.remove(snowmanId);
            trackedSnowmen.remove(snowmanId);
        }, 60L, attackInterval * 20L);

        if (task != null) {
            entityAttackTasks.put(snowmanId, task);
        }
    }
}
