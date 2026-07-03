package de.boondocksulfur.christmas.listener;

import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.util.FoliaSchedulerHelper;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Listener der OPs beim Join über verfügbare Updates informiert
 */
public class UpdateNotificationListener implements Listener {

    private final ChristmasSeason plugin;
    private final FoliaSchedulerHelper scheduler;

    public UpdateNotificationListener(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.scheduler = plugin.getFoliaScheduler();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Nur OPs benachrichtigen
        if (!player.isOp()) {
            return;
        }

        // Verzögerte Benachrichtigung (3 Sekunden nach Join)
        // FIX: FoliaSchedulerHelper statt Bukkit.getScheduler() (Folia-kompatibel)
        scheduler.runForEntityLater(player, () -> {
            if (player.isOnline() && plugin.getUpdateChecker().isUpdateAvailable()) {
                plugin.getUpdateChecker().sendUpdateNotification(player);
            }
        }, 60L); // 3 Sekunden
    }
}
