package de.boondocksulfur.christmas.manager;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import de.boondocksulfur.christmas.ChristmasSeason;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player opt-out of the festive mobs ({@code /xmasoptout}). An opted-out player gets no
 * spawns of that mob type and is never the target of its effects.
 *
 * <p>The choice is stored in the player's persistent data; an in-memory copy lets the mob
 * tasks, which run on other region threads under Folia, read it safely.
 */
public class OptOutManager implements Listener {

    public enum Category {
        WICHTEL, ELVES, SNOWMEN;

        public String id() { return name().toLowerCase(Locale.ROOT); }

        public static Category byId(String id) {
            for (Category c : values()) if (c.id().equalsIgnoreCase(id)) return c;
            return null;
        }
    }

    private final ChristmasSeason plugin;
    private final NamespacedKey key;
    private final Map<UUID, Set<Category>> optedOut = new ConcurrentHashMap<>();

    public OptOutManager(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "optout");
    }

    /** Loads the players that are already online (plugin start or reload). */
    public void loadOnlinePlayers() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            plugin.getFoliaScheduler().runForEntity(p, () -> load(p));
        }
    }

    public boolean isOptedOut(Player player, Category category) {
        Set<Category> set = optedOut.get(player.getUniqueId());
        return set != null && set.contains(category);
    }

    public boolean isOptedOut(UUID player, Category category) {
        Set<Category> set = optedOut.get(player);
        return set != null && set.contains(category);
    }

    /** Changes the opt-out and persists it. Must run on the player's thread. */
    public void setOptedOut(Player player, Category category, boolean out) {
        Set<Category> set = EnumSet.noneOf(Category.class);
        Set<Category> current = optedOut.get(player.getUniqueId());
        if (current != null) set.addAll(current);
        if (out) set.add(category); else set.remove(category);

        if (set.isEmpty()) {
            optedOut.remove(player.getUniqueId());
            player.getPersistentDataContainer().remove(key);
        } else {
            optedOut.put(player.getUniqueId(), Set.copyOf(set));
            StringBuilder sb = new StringBuilder();
            for (Category c : set) sb.append(sb.isEmpty() ? "" : ",").append(c.id());
            player.getPersistentDataContainer().set(key, PersistentDataType.STRING, sb.toString());
        }
    }

    private void load(Player player) {
        String stored = player.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (stored == null || stored.isBlank()) {
            optedOut.remove(player.getUniqueId());
            return;
        }
        Set<Category> set = EnumSet.noneOf(Category.class);
        for (String id : stored.split(",")) {
            Category c = Category.byId(id.trim());
            if (c != null) set.add(c);
        }
        if (set.isEmpty()) optedOut.remove(player.getUniqueId());
        else optedOut.put(player.getUniqueId(), Set.copyOf(set));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        load(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        optedOut.remove(e.getPlayer().getUniqueId());
    }
}
