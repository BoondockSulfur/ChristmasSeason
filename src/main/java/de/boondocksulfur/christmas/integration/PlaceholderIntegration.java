package de.boondocksulfur.christmas.integration;

import de.boondocksulfur.christmas.ChristmasSeason;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI expansion ({@code %xmas_...%}). Only loaded when PlaceholderAPI is present.
 *
 * <ul>
 *   <li>{@code %xmas_active%} - true/false</li>
 *   <li>{@code %xmas_snowstorm%} - true/false (primary snow world)</li>
 *   <li>{@code %xmas_days_until_start%} / {@code %xmas_days_left%} - schedule</li>
 *   <li>{@code %xmas_gifts_opened%} - by the player, {@code %xmas_gifts_opened_total%} - server wide</li>
 *   <li>{@code %xmas_advent_claimed%} - doors opened by the player, {@code %xmas_advent_today%} - claimed/open/closed</li>
 *   <li>{@code %xmas_tracked_mobs%} / {@code %xmas_tracked_gifts%}</li>
 * </ul>
 */
public class PlaceholderIntegration extends PlaceholderExpansion {

    private final ChristmasSeason plugin;

    public PlaceholderIntegration(ChristmasSeason plugin) {
        this.plugin = plugin;
    }

    @Override public @NotNull String getIdentifier() { return "xmas"; }
    @Override public @NotNull String getAuthor() { return "BoondockSulfur"; }
    @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        switch (params.toLowerCase()) {
            case "active": return String.valueOf(plugin.isActive());
            case "snowstorm": {
                World w = Bukkit.getWorld(plugin.getPrimarySnowWorld());
                return String.valueOf(w != null && w.hasStorm());
            }
            case "days_until_start": return String.valueOf(plugin.getEventController().daysUntilStart());
            case "days_left": return String.valueOf(plugin.getEventController().daysLeft());
            case "gifts_opened_total": return String.valueOf(plugin.getStatsManager().getTotalGiftsOpened());
            case "tracked_mobs": return String.valueOf(plugin.getWichtelManager().getTrackedCount()
                    + plugin.getSnowmanManager().getTrackedCount());
            case "tracked_gifts": return String.valueOf(plugin.getGiftManager().getTrackedCount());
            default: break;
        }
        if (player == null) return "";
        switch (params.toLowerCase()) {
            case "gifts_opened": return String.valueOf(plugin.getStatsManager().getGiftsOpened(player.getUniqueId()));
            case "advent_claimed": return String.valueOf(plugin.getAdventManager().getClaimedDays(player.getUniqueId()).size());
            case "advent_today": {
                int day = plugin.getAdventManager().currentDay();
                if (day < 0) return "closed";
                return plugin.getAdventManager().hasClaimed(player.getUniqueId(), day) ? "claimed" : "open";
            }
            default: return null;
        }
    }
}
