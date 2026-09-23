package de.boondocksulfur.christmas.manager;

import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.api.AdventClaimEvent;
import de.boondocksulfur.christmas.util.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Advent calendar: one reward per player and calendar day ({@code /advent}).
 *
 * <p>Config ({@code advent.*}): month, first/last day, whether missed days can be
 * claimed later, default rewards (items given every day plus one weighted random pick)
 * and optional per-day overrides. Claims are stored in {@code data/advent.yml} and
 * reset automatically when the year changes.
 */
public class AdventManager {

    private final ChristmasSeason plugin;
    private final LanguageManager lang;
    private final File file;
    /** player -> claimed days of the stored year */
    private final Map<UUID, Set<Integer>> claims = new ConcurrentHashMap<>();
    private volatile int storedYear;

    public AdventManager(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.file = new File(new File(plugin.getDataFolder(), "data"), "advent.yml");
        load();
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("advent.enabled", true);
    }

    private int month() { return Math.min(12, Math.max(1, plugin.getConfig().getInt("advent.month", 12))); }
    public int firstDay() { return Math.max(1, plugin.getConfig().getInt("advent.firstDay", 1)); }
    public int lastDay() { return Math.max(firstDay(), plugin.getConfig().getInt("advent.lastDay", 24)); }

    private LocalDate today() {
        return LocalDate.now(plugin.getZoneId());
    }

    /** @return today's calendar day, or -1 outside the calendar window */
    public int currentDay() {
        LocalDate now = today();
        if (now.getMonthValue() != month()) return -1;
        int d = now.getDayOfMonth();
        return (d >= firstDay() && d <= lastDay()) ? d : -1;
    }

    private synchronized void load() {
        storedYear = today().getYear();
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        int year = yml.getInt("year", storedYear);
        if (year != storedYear) {
            plugin.debug("Advent claims from " + year + " discarded (new year)");
            return;
        }
        ConfigurationSection players = yml.getConfigurationSection("players");
        if (players == null) return;
        for (String key : players.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                claims.put(id, ConcurrentHashMap.newKeySet());
                claims.get(id).addAll(players.getIntegerList(key));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    /** Saves synchronously. */
    public synchronized void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("year", storedYear);
        for (Map.Entry<UUID, Set<Integer>> e : claims.entrySet()) {
            List<Integer> days = new ArrayList<>(e.getValue());
            Collections.sort(days);
            yml.set("players." + e.getKey(), days);
        }
        try {
            file.getParentFile().mkdirs();
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save advent.yml: " + ex.getMessage());
        }
    }

    private void rolloverIfNeeded() {
        int year = today().getYear();
        if (year != storedYear) {
            synchronized (this) {
                if (year != storedYear) {
                    claims.clear();
                    storedYear = year;
                    save();
                }
            }
        }
    }

    public Set<Integer> getClaimedDays(UUID player) {
        rolloverIfNeeded();
        return Collections.unmodifiableSet(claims.getOrDefault(player, Set.of()));
    }

    public boolean hasClaimed(UUID player, int day) {
        return getClaimedDays(player).contains(day);
    }

    /** @return {@code true} if the player has already claimed today's door (or there is none) */
    public boolean hasClaimedToday(UUID player) {
        int day = currentDay();
        return day < 0 || hasClaimed(player, day);
    }

    /**
     * Claims a door for the player. Must run on the player's thread.
     *
     * @param day the day to claim; today's day when {@code <= 0}
     */
    public void claim(Player player, int day) {
        if (!isEnabled()) {
            lang.send(player, "advent.disabled");
            return;
        }
        if (plugin.getConfig().getBoolean("advent.requireActive", false) && !plugin.isActive()) {
            lang.send(player, "advent.event-inactive");
            return;
        }
        rolloverIfNeeded();
        int todayDay = currentDay();
        if (todayDay < 0) {
            lang.send(player, "advent.not-in-season", firstDay(), month(), lastDay(), month());
            return;
        }
        if (day <= 0) day = todayDay;
        if (day < firstDay() || day > lastDay()) {
            lang.send(player, "advent.invalid-day", firstDay(), lastDay());
            return;
        }
        if (day > todayDay) {
            lang.send(player, "advent.future-day", day);
            return;
        }
        if (day < todayDay && !plugin.getConfig().getBoolean("advent.allowCatchUp", false)) {
            lang.send(player, "advent.no-catch-up");
            return;
        }
        Set<Integer> mine = claims.computeIfAbsent(player.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        if (mine.contains(day)) {
            lang.send(player, "advent.already-claimed", day);
            return;
        }

        AdventClaimEvent event = new AdventClaimEvent(player, day);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;

        mine.add(day);
        int finalDay = day;
        plugin.getFoliaScheduler().runAsync(this::save);

        giveRewards(player, day);
        lang.send(player, "advent.claimed", day);
        if (plugin.getConfig().getBoolean("advent.broadcastClaims", false)) {
            Bukkit.broadcast(lang.getComponent("advent.broadcast", player.getName(), finalDay));
        }
        playEffect(player);
    }

    /** Hands out the rewards configured for the day (per-day override or defaults). */
    private void giveRewards(Player player, int day) {
        LootManager loot = plugin.getLootManager();
        Map<String, String> ph = Map.of("%day%", String.valueOf(day));

        String dayPath = "advent.days." + day;
        if (plugin.getConfig().isConfigurationSection(dayPath)) {
            for (LootManager.LootEntry e : loot.getList(dayPath + ".items")) {
                LootManager.give(player, loot.build(e));
                loot.runCommands(e, player, ph);
            }
            LootManager.LootEntry picked = loot.pick(loot.getList(dayPath + ".randomPool"));
            if (picked != null) {
                LootManager.give(player, loot.build(picked));
                loot.runCommands(picked, player, ph);
            }
            runCommandList(plugin.getConfig().getStringList(dayPath + ".commands"), player, day);
            return;
        }

        for (LootManager.LootEntry e : loot.getList("advent.default.items")) {
            LootManager.give(player, loot.build(e));
            loot.runCommands(e, player, ph);
        }
        LootManager.LootEntry picked = loot.pick(loot.getList("advent.default.randomPool"));
        if (picked != null) {
            LootManager.give(player, loot.build(picked));
            loot.runCommands(picked, player, ph);
        }
        runCommandList(plugin.getConfig().getStringList("advent.default.commands"), player, day);
    }

    private void runCommandList(List<String> commands, Player player, int day) {
        for (String cmd : commands) {
            String c = cmd.replace("%player%", player.getName()).replace("%day%", String.valueOf(day));
            plugin.getFoliaScheduler().runGlobalTask(() ->
                    plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), c));
        }
    }

    private void playEffect(Player player) {
        org.bukkit.Sound sound = plugin.resolveSound(plugin.getConfig().getString("advent.sound", "entity.player.levelup"));
        if (sound != null) player.playSound(player.getLocation(), sound, 1f, 1.2f);
        if (plugin.getConfig().getBoolean("advent.particles", true)) {
            player.getWorld().spawnParticle(org.bukkit.Particle.SNOWFLAKE, player.getLocation().add(0, 1, 0), 30, 0.5, 0.7, 0.5, 0.02);
        }
    }

    /** Sends the player's calendar overview. */
    public void sendStatus(Player player) {
        rolloverIfNeeded();
        int todayDay = currentDay();
        Set<Integer> mine = getClaimedDays(player.getUniqueId());
        StringBuilder sb = new StringBuilder();
        for (int d = firstDay(); d <= lastDay(); d++) {
            String color = mine.contains(d) ? "&a" : (todayDay >= 0 && d <= todayDay ? "&e" : "&8");
            sb.append(color).append(d).append(" ");
        }
        lang.send(player, "advent.status-header", mine.size(), lastDay() - firstDay() + 1);
        player.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(sb.toString().trim()));
        if (todayDay < 0) {
            lang.send(player, "advent.not-in-season", firstDay(), month(), lastDay(), month());
        } else if (mine.contains(todayDay)) {
            lang.send(player, "advent.today-claimed", todayDay);
        } else {
            lang.send(player, "advent.today-open", todayDay);
        }
    }
}
