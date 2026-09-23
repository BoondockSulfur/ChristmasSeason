package de.boondocksulfur.christmas.manager;

import de.boondocksulfur.christmas.ChristmasSeason;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-player statistics (gifts opened), persisted to {@code data/stats.yml}.
 * Writes are batched: the file is saved at most once per minute and on disable.
 */
public class StatsManager {

    private final ChristmasSeason plugin;
    private final File file;
    private final Map<UUID, Integer> giftsOpened = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    public StatsManager(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.file = new File(new File(plugin.getDataFolder(), "data"), "stats.yml");
        load();
        plugin.getFoliaScheduler().runAsyncTimer(this::saveIfDirty, 20L * 60, 20L * 60);
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String key : yml.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                giftsOpened.put(id, yml.getInt(key + ".giftsOpened", 0));
                String name = yml.getString(key + ".name");
                if (name != null) names.put(id, name);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    /** Saves synchronously (used on disable). */
    public synchronized void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Integer> e : giftsOpened.entrySet()) {
            yml.set(e.getKey() + ".giftsOpened", e.getValue());
            String name = names.get(e.getKey());
            if (name != null) yml.set(e.getKey() + ".name", name);
        }
        try {
            file.getParentFile().mkdirs();
            yml.save(file);
            dirty.set(false);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save stats.yml: " + e.getMessage());
        }
    }

    private void saveIfDirty() {
        if (dirty.get()) save();
    }

    /** Records an opened gift for the player. */
    public void incrementGiftsOpened(UUID player, String name) {
        giftsOpened.merge(player, 1, Integer::sum);
        names.put(player, name);
        dirty.set(true);
    }

    public int getGiftsOpened(UUID player) {
        return giftsOpened.getOrDefault(player, 0);
    }

    public int getTotalGiftsOpened() {
        int sum = 0;
        for (int v : giftsOpened.values()) sum += v;
        return sum;
    }

    /** @return top {@code limit} players as (name, count), highest first */
    public List<Map.Entry<String, Integer>> getTop(int limit) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>();
        for (Map.Entry<UUID, Integer> e : giftsOpened.entrySet()) {
            list.add(new AbstractMap.SimpleEntry<>(names.getOrDefault(e.getKey(), e.getKey().toString()), e.getValue()));
        }
        list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return list.size() > limit ? list.subList(0, limit) : list;
    }
}
