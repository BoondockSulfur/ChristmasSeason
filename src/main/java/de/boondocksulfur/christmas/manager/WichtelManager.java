package de.boondocksulfur.christmas.manager;

import org.bukkit.*;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.*;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.util.LanguageManager;
import de.boondocksulfur.christmas.util.SpawnUtil;
import de.boondocksulfur.christmas.util.FoliaSchedulerHelper;

import java.util.*;

public class WichtelManager {

    public static final String TAG_WICHTEL = "XMAS_WICHTEL";
    public static final String TAG_ELF     = "XMAS_ELF";

    private final ChristmasSeason plugin;
    private final LanguageManager lang;
    private final FoliaSchedulerHelper scheduler;
    private final Random random = new Random();

    // FOLIA FIX: Player-basierte Spawn-Timer (Entity Scheduler)
    private final Map<UUID, WrappedTask> playerWichtelTasks = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, WrappedTask> playerElfTasks = new java.util.concurrent.ConcurrentHashMap<>();

    // FOLIA FIX: Entity-basierte Steal-Tasks (Entity Scheduler pro Mob)
    private final Map<UUID, WrappedTask> entityStealTasks = new java.util.concurrent.ConcurrentHashMap<>();

    // Tracker statt globale Scans
    // FOLIA FIX: ConcurrentHashMap.newKeySet() - wird von mehreren Region-Threads
    // gleichzeitig mutiert (add im Location Scheduler, remove im Entity Scheduler, cleanup vom Command)
    private final Set<UUID> trackedWichtel = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<UUID> trackedElfen   = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public WichtelManager(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.scheduler = plugin.getFoliaScheduler();
    }

    public void start() {
        stop();
        // FOLIA FIX: Spawn-Timer sind jetzt Player-basiert (siehe startPlayerSpawning)
        // FOLIA FIX: Steal-Tasks sind jetzt Entity-basiert (siehe startEntityStealTask)
        plugin.debug("WichtelManager gestartet (Folia-kompatibel: Player-basierte Spawns + Entity-basierte AI)");
    }

    public void stop() {
        // FOLIA FIX: Stoppe alle Player-basierten Tasks
        for (WrappedTask task : playerWichtelTasks.values()) {
            if (task != null) task.cancel();
        }
        for (WrappedTask task : playerElfTasks.values()) {
            if (task != null) task.cancel();
        }
        playerWichtelTasks.clear();
        playerElfTasks.clear();

        // FOLIA FIX: Stoppe alle Entity-basierten Steal-Tasks
        for (WrappedTask task : entityStealTasks.values()) {
            if (task != null) task.cancel();
        }
        entityStealTasks.clear();

        trackedWichtel.clear();
        trackedElfen.clear();
    }

    /**
     * Startet Wichtel/Elfen-Spawning für einen Spieler (Entity Scheduler)
     * FOLIA-KOMPATIBEL: Läuft auf Entity Scheduler des Players
     */
    public void startPlayerSpawning(Player player) {
        UUID uuid = player.getUniqueId();

        // Stoppe alte Tasks falls vorhanden
        WrappedTask oldWichtel = playerWichtelTasks.remove(uuid);
        if (oldWichtel != null) oldWichtel.cancel();
        WrappedTask oldElf = playerElfTasks.remove(uuid);
        if (oldElf != null) oldElf.cancel();

        // Starte Wichtel-Spawner auf Entity Scheduler
        if (plugin.getConfig().getBoolean("wichtel.enabled", true)) {
            int interval = plugin.getConfig().getInt("wichtel.spawnIntervalSeconds", 45);
            WrappedTask task = scheduler.runForEntityTimer(player, () -> {
                if (!player.isOnline() || !player.isValid()) {
                    stopPlayerSpawning(player);
                    return;
                }
                spawnWichtelNearPlayer(player);
            }, 40L, interval * 20L);
            if (task != null) playerWichtelTasks.put(uuid, task);
        }

        // Starte Elfen-Spawner auf Entity Scheduler
        if (plugin.getConfig().getBoolean("elves.enabled", true)) {
            int interval = plugin.getConfig().getInt("elves.spawnIntervalSeconds", 60);
            WrappedTask task = scheduler.runForEntityTimer(player, () -> {
                if (!player.isOnline() || !player.isValid()) {
                    stopPlayerSpawning(player);
                    return;
                }
                spawnElfNearPlayer(player);
            }, 60L, interval * 20L);
            if (task != null) playerElfTasks.put(uuid, task);
        }

        plugin.debug("Wichtel/Elfen-Spawning gestartet für " + player.getName());
    }

    /**
     * Stoppt Wichtel/Elfen-Spawning für einen Spieler
     */
    public void stopPlayerSpawning(Player player) {
        UUID uuid = player.getUniqueId();
        WrappedTask wichtelTask = playerWichtelTasks.remove(uuid);
        if (wichtelTask != null) wichtelTask.cancel();
        WrappedTask elfTask = playerElfTasks.remove(uuid);
        if (elfTask != null) elfTask.cancel();
        plugin.debug("Wichtel/Elfen-Spawning gestoppt für " + player.getName());
    }

    /** Entfernt alle Wichtel und Elfen aus der Welt */
    public void cleanup() {
        int removed = 0;
        int wichtelCount = trackedWichtel.size();
        int elfenCount = trackedElfen.size();

        // FOLIA FIX: Wichtel entfernen (Entity Scheduler)
        Iterator<UUID> wit = trackedWichtel.iterator();
        while (wit.hasNext()) {
            UUID uuid = wit.next();
            Entity e = Bukkit.getEntity(uuid);
            if (e != null && e.isValid()) {
                // FOLIA FIX: Cancel steal task if exists
                WrappedTask stealTask = entityStealTasks.remove(uuid);
                if (stealTask != null) stealTask.cancel();

                // FOLIA FIX: Schedule removal on entity's thread
                scheduler.runForEntity(e, () -> {
                    if (!e.isDead() && e.isValid()) {
                        e.remove();
                    }
                });
                removed++;
            }
            wit.remove();
        }

        // FOLIA FIX: Elfen entfernen (Entity Scheduler)
        Iterator<UUID> eit = trackedElfen.iterator();
        while (eit.hasNext()) {
            UUID uuid = eit.next();
            Entity e = Bukkit.getEntity(uuid);
            if (e != null && e.isValid()) {
                // FOLIA FIX: Cancel steal task if exists
                WrappedTask stealTask = entityStealTasks.remove(uuid);
                if (stealTask != null) stealTask.cancel();

                // FOLIA FIX: Schedule removal on entity's thread
                scheduler.runForEntity(e, () -> {
                    if (!e.isDead() && e.isValid()) {
                        e.remove();
                    }
                });
                removed++;
            }
            eit.remove();
        }

        plugin.getLogger().info(lang.getMessage("log.cleanup.wichtel", removed, wichtelCount, elfenCount));
    }

    /**
     * Spawnt Wichtel in Nähe eines Spielers
     * FOLIA-KOMPATIBEL: Wird von Entity Scheduler des Players aufgerufen
     */
    private void spawnWichtelNearPlayer(Player player) {
        World w = player.getWorld();
        String worldName = plugin.getConfig().getString("wichtel.world",
                plugin.getConfig().getString("snowWorld", "world"));
        if (!w.getName().equals(worldName)) return;

        // Verwende getrackte Liste statt Entity-Iteration
        final int maxWichtel = plugin.getConfig().getInt("wichtel.maxPerWorld", 6);
        if (trackedWichtel.size() >= maxWichtel) return;

        // FOLIA FIX: Spawne auf Location Scheduler (für getHighestBlockAt)
        Location playerLoc = player.getLocation();

        scheduler.runAtLocation(playerLoc, () -> {
            // OVERSPAWN FIX: Limit erneut prüfen - zwischen Einplanung und Ausführung
            // können parallele Spawns anderer Spieler das Limit schon erreicht haben!
            if (trackedWichtel.size() >= maxWichtel) return;

            // Safe-Spawn: 5 Versuche (Performance-optimiert, strenge Wasser/Wand-Checks)
            Location spawn = SpawnUtil.findSafeSpawnLocation(w, playerLoc, 10, 5);

            Zombie z = (Zombie) w.spawnEntity(spawn, EntityType.ZOMBIE);
            z.setBaby(true);
            z.setCustomName(lang.get("entity.wichtel"));
            z.setCustomNameVisible(true);
            z.setRemoveWhenFarAway(false);
            z.getScoreboardTags().add(TAG_WICHTEL);
            if (z.getEquipment()!=null) z.getEquipment().clear();
            z.setTarget(null);
            trackedWichtel.add(z.getUniqueId());

            // FOLIA FIX: Starte Entity Scheduler Task für Steal-Logik
            startEntityStealTask(z);

            // FIX: Lifetime Enforcement - entferne nach konfigurierter Zeit
            int lifetime = Math.max(10, plugin.getConfig().getInt("wichtel.lifetimeSeconds", 240));
            UUID wichtelId = z.getUniqueId();
            scheduler.runForEntityLater(z, () -> {
                Entity e = Bukkit.getEntity(wichtelId);
                if (e != null && e.isValid()) {
                    e.remove();
                }
                trackedWichtel.remove(wichtelId);
                entityStealTasks.remove(wichtelId); // Cleanup Task
            }, lifetime * 20L);
        });
    }

    /**
     * Spawnt Elfen in Nähe eines Spielers
     * FOLIA-KOMPATIBEL: Wird von Entity Scheduler des Players aufgerufen
     */
    private void spawnElfNearPlayer(Player player) {
        World w = player.getWorld();
        String worldName = plugin.getConfig().getString("wichtel.world",
                plugin.getConfig().getString("snowWorld", "world"));
        if (!w.getName().equals(worldName)) return;

        // Verwende getrackte Liste statt Entity-Iteration
        final int maxElfen = plugin.getConfig().getInt("elves.maxPerWorld", 4);
        if (trackedElfen.size() >= maxElfen) return;

        // FOLIA FIX: Spawne auf Location Scheduler (für getHighestBlockAt)
        Location playerLoc = player.getLocation();

        scheduler.runAtLocation(playerLoc, () -> {
            // OVERSPAWN FIX: Limit erneut prüfen (siehe spawnWichtelNearPlayer)
            if (trackedElfen.size() >= maxElfen) return;

            // Safe-Spawn: 5 Versuche (Performance-optimiert, strenge Wasser/Wand-Checks)
            Location spawn = SpawnUtil.findSafeSpawnLocation(w, playerLoc, 10, 5);

            Allay a = (Allay) w.spawnEntity(spawn, EntityType.ALLAY);
            a.setCustomName(lang.get("entity.elf"));
            a.setCustomNameVisible(true);
            a.setRemoveWhenFarAway(false);
            a.setCanPickupItems(true);
            a.getScoreboardTags().add(TAG_ELF);
            trackedElfen.add(a.getUniqueId());

            // FOLIA FIX: Starte Entity Scheduler Task für Steal-Logik
            startEntityStealTask(a);

            // FIX: Lifetime Enforcement - entferne nach konfigurierter Zeit
            int lifetime = Math.max(10, plugin.getConfig().getInt("wichtel.lifetimeSeconds", 240));
            UUID elfId = a.getUniqueId();
            scheduler.runForEntityLater(a, () -> {
                Entity e = Bukkit.getEntity(elfId);
                if (e != null && e.isValid()) {
                    e.remove();
                }
                trackedElfen.remove(elfId);
                entityStealTasks.remove(elfId); // Cleanup Task
            }, lifetime * 20L);
        });
    }

    /**
     * Startet einen Entity Scheduler Task für Steal/TP-Logik
     * FOLIA-KOMPATIBEL: Läuft auf Entity Scheduler der Mob-Entity
     */
    private void startEntityStealTask(LivingEntity entity) {
        UUID entityId = entity.getUniqueId();
        double radius = plugin.getConfig().getDouble("wichtel.stealRadius", 3.2);
        double tpChance = 0.3;

        // FOLIA FIX: Entity Scheduler Task für diese spezifische Entity
        WrappedTask task = scheduler.runForEntityTimer(entity, () -> {
            // Entity-State-Zugriff ist sicher, weil wir auf Entity Scheduler laufen!
            if (!entity.isValid() || entity.isDead()) {
                WrappedTask oldTask = entityStealTasks.remove(entityId);
                if (oldTask != null) oldTask.cancel();
                return;
            }

            // Steal-Logik: Items in Nähe einsammeln
            for (Entity near : entity.getNearbyEntities(radius, radius, radius)) {
                if (near instanceof Item item) {
                    item.remove();
                    entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.2f);
                }
            }

            // Teleport-Logik: Zufälliges Herumspringen
            if (random.nextDouble() < tpChance) {
                int dx = random.nextInt(3) - 1; // -1, 0, 1
                int dz = random.nextInt(3) - 1;
                Location newLoc = entity.getLocation().add(dx, 0, dz);

                // MULTI-PLATFORM FIX: Unterschiedliche Teleport-APIs
                if (scheduler.isFolia()) {
                    // FOLIA: MUSS teleportAsync verwenden (region threading)
                    entity.teleportAsync(newLoc);
                } else {
                    // SPIGOT/PAPER/PURPUR: Verwendet normales teleport()
                    // (teleportAsync existiert nicht auf Spigot!)
                    entity.teleport(newLoc);
                }
            }
        }, 40L, 40L); // Alle 2 Sekunden (40 Ticks)

        if (task != null) {
            entityStealTasks.put(entityId, task);
        }
    }
}
