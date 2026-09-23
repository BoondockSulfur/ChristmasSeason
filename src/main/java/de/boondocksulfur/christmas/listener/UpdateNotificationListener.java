package de.boondocksulfur.christmas.listener;

import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.util.FoliaSchedulerHelper;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Tells operators about an available update a few seconds after they join. */
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
        if (!player.isOp()) return;
        if (!plugin.getUpdateChecker().isNotifyOps()) return;

        // Delayed by three seconds so the message is not lost in the join spam
        scheduler.runForEntityLater(player, () -> {
            if (player.isOnline() && plugin.getUpdateChecker().isUpdateAvailable()) {
                plugin.getUpdateChecker().sendUpdateNotification(player);
            }
        }, 60L);
    }
}
