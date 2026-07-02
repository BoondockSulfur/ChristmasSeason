package de.boondocksulfur.christmas.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import de.boondocksulfur.christmas.ChristmasSeason;

/**
 * FOLIA-KOMPATIBEL: Startet/stoppt Player-basiertes Biome-Tracking
 * Verwendet Entity Scheduler pro Player statt globalem Timer
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

        // FOLIA FIX: Starte Player-Tracking (Entity Scheduler)
        plugin.getBiomeSnowManager().startPlayerTracking(player);
        plugin.getWichtelManager().startPlayerSpawning(player);
        plugin.getSnowmanManager().startPlayerSpawning(player);
        plugin.getGiftManager().startPlayerSpawning(player);
        plugin.getDecorationManager().startPlayerSpawning(player);

        plugin.debug("Player-Tracking gestartet für " + player.getName() + " (Join Event)");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player player = e.getPlayer();

        // FOLIA FIX: Stoppe Player-Tracking
        plugin.getBiomeSnowManager().stopPlayerTracking(player);
        plugin.getWichtelManager().stopPlayerSpawning(player);
        plugin.getSnowmanManager().stopPlayerSpawning(player);
        plugin.getGiftManager().stopPlayerSpawning(player);
        plugin.getDecorationManager().stopPlayerSpawning(player);

        plugin.debug("Player-Tracking gestoppt für " + player.getName() + " (Quit Event)");
    }
}
