package de.boondocksulfur.christmas.util;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataHolder;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Lifetimes that survive restarts: the end of the lifetime is stored on the object, so an
 * adopted mob or item keeps its remaining time instead of starting over.
 */
public final class Lifetime {

    private static volatile NamespacedKey key;

    private Lifetime() {
    }

    /**
     * Ticks until the end of the object's lifetime. The first call stores the end
     * ({@code fullTicks} from now); later calls, e.g. after a restart, return the remaining time.
     *
     * @return at least one tick
     */
    public static long ticksLeft(Plugin plugin, PersistentDataHolder holder, long fullTicks) {
        if (key == null) key = new NamespacedKey(plugin, "expires");
        PersistentDataContainer data = holder.getPersistentDataContainer();
        long now = System.currentTimeMillis();
        Long end = data.get(key, PersistentDataType.LONG);
        if (end == null) {
            data.set(key, PersistentDataType.LONG, now + fullTicks * 50L);
            return Math.max(1L, fullTicks);
        }
        return Math.max(1L, (end - now) / 50L);
    }
}
