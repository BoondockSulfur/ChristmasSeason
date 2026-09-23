package de.boondocksulfur.christmas.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import de.boondocksulfur.christmas.ChristmasSeason;

/**
 * Starts and stops the per-player timers (biome bubble, spawns) on join and quit.
 * Players already online when the event starts are handled by
 * {@link ChristmasSeason#startFeatures()}.
 */
public class PlayerSnowBubbleListener implements Listener {

    private final ChristmasSeason plugin;

    public PlayerSnowBubbleListener(ChristmasSeason plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!plugin.isActive()) return;
        Player player = e.getPlayer();
        plugin.startPlayerTasks(player);
        plugin.debug("Player tasks started for " + player.getName() + " (join)");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player player = e.getPlayer();
        plugin.stopPlayerTasks(player);
        plugin.debug("Player tasks stopped for " + player.getName() + " (quit)");
    }
}
