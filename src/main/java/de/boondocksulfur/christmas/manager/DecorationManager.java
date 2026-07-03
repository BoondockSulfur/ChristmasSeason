package de.boondocksulfur.christmas.manager;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.util.LanguageManager;
import de.boondocksulfur.christmas.util.SpawnUtil;
import de.boondocksulfur.christmas.util.FoliaSchedulerHelper;

import java.util.List;
import java.util.Random;

public class DecorationManager {

    private final ChristmasSeason plugin;
    private final LanguageManager lang;
    private final FoliaSchedulerHelper scheduler;
    private final Random random = new Random();

    // FOLIA FIX: Player-basierte Spawn-Timer (Entity Scheduler)
    private final java.util.Map<java.util.UUID, WrappedTask> playerSpawnTasks = new java.util.concurrent.ConcurrentHashMap<>();

    // FOLIA FIX: Track spawned decorations by UUID for safe cleanup
    private final java.util.Set<java.util.UUID> trackedDecorations = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public DecorationManager(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.scheduler = plugin.getFoliaScheduler();
    }

    public void start() {
        stop();
        // FOLIA FIX: Spawn-Timer sind jetzt Player-basiert (siehe startPlayerSpawning)
        plugin.debug("DecorationManager gestartet (Folia-kompatibel: Player-basierte Spawns)");
    }

    public void stop() {
        // FOLIA FIX: Stoppe alle Player-basierten Tasks
        for (WrappedTask task : playerSpawnTasks.values()) {
            if (task != null) task.cancel();
        }
        playerSpawnTasks.clear();
        // Note: trackedDecorations wird NICHT geleert - bleibt für cleanup() erhalten
    }

    /**
     * Startet Dekorations-Spawning für einen Spieler (Entity Scheduler)
     * FOLIA-KOMPATIBEL: Läuft auf Entity Scheduler des Players
     */
    public void startPlayerSpawning(org.bukkit.entity.Player player) {
        if (!plugin.getConfig().getBoolean("decoration.enabled", true)) return;

        java.util.UUID uuid = player.getUniqueId();
        WrappedTask oldTask = playerSpawnTasks.remove(uuid);
        if (oldTask != null) oldTask.cancel();

        int interval = plugin.getConfig().getInt("decoration.intervalSeconds", 25);
        double spawnChance = plugin.getConfig().getDouble("decoration.spawnChance", 0.9);

        WrappedTask task = scheduler.runForEntityTimer(player, () -> {
            if (!player.isOnline() || !player.isValid()) {
                stopPlayerSpawning(player);
                return;
            }
            if (random.nextDouble() <= spawnChance) {
                spawnDecorationNearPlayer(player);
            }
        }, 40L, interval * 20L);

        if (task != null) {
            playerSpawnTasks.put(uuid, task);
            plugin.debug("Dekorations-Spawning gestartet für " + player.getName());
        }
    }

    /**
     * Stoppt Dekorations-Spawning für einen Spieler
     */
    public void stopPlayerSpawning(org.bukkit.entity.Player player) {
        WrappedTask task = playerSpawnTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
            plugin.debug("Dekorations-Spawning gestoppt für " + player.getName());
        }
    }

    /**
     * Entfernt alle Dekorations-Items aus der Welt
     * FOLIA-SAFE: Verwendet tracked UUIDs und schedult Entfernung pro Entity
     */
    public void cleanup() {
        int removed = 0;
        int tracked = trackedDecorations.size();

        // FOLIA FIX: Iteriere über tracked UUIDs statt w.getEntitiesByClass()
        java.util.Iterator<java.util.UUID> it = trackedDecorations.iterator();
        while (it.hasNext()) {
            java.util.UUID uuid = it.next();
            org.bukkit.entity.Entity entity = Bukkit.getEntity(uuid);

            if (entity != null && entity.isValid() && entity instanceof Item) {
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

        plugin.getLogger().info(lang.getMessage("log.cleanup.decorations", removed));
        if (tracked > removed && plugin.isDebugMode()) {
            plugin.debug("Decorations: " + removed + " removed, " + (tracked - removed) + " already gone");
        }
    }

    /**
     * Spawnt Dekoration in Nähe eines Spielers
     * FOLIA-KOMPATIBEL: Wird von Entity Scheduler des Players aufgerufen
     */
    private void spawnDecorationNearPlayer(Player player) {
        World w = player.getWorld();
        String worldName = plugin.getConfig().getString("snowWorld", "world");
        if (!w.getName().equals(worldName)) return;

        List<String> drops = plugin.getConfig().getStringList("decoration.drops");
        if (drops.isEmpty()) return;

        // FOLIA FIX: Spawne auf Location Scheduler (für findSurface und dropItem)
        Location playerLoc = player.getLocation();

        scheduler.runAtLocation(playerLoc, () -> {
            // Safe-Spawn: 5 Versuche (Performance-optimiert, strenge Wasser/Wand-Checks)
            Location place = SpawnUtil.findSafeSpawnLocation(w, playerLoc, 7, 5);

            // Region-Schutz: Kein Spawn in geschützten Bereichen (WorldGuard/GriefPrevention)
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

            ItemStack stack = new ItemStack(mat, amount);
            net.kyori.adventure.text.Component name = lang.getComponent("entity.decoration");
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) { meta.displayName(name); stack.setItemMeta(meta); }

            Item item = w.dropItem(place, stack);
            item.customName(name);
            item.setCustomNameVisible(true);
            item.setPickupDelay(plugin.getConfig().getInt("decoration.pickupDelayTicks", 0));
            try { item.setGlowing(plugin.getConfig().getBoolean("decoration.glow", true)); } catch (Throwable ignored) {}

            // FOLIA FIX: Track spawned decoration
            trackedDecorations.add(item.getUniqueId());

            int lifetime = plugin.getConfig().getInt("decoration.lifetimeSeconds", 180);
            java.util.UUID itemId = item.getUniqueId();
            scheduler.runForEntityLater(item, () -> {
                if (!item.isDead() && item.isValid()) {
                    item.remove();
                }
                trackedDecorations.remove(itemId);
            }, () -> {
                // FOLIA FIX: retired - Item wurde vorher aufgesammelt/despawnt
                trackedDecorations.remove(itemId);
            }, lifetime * 20L);
        });
    }
}
