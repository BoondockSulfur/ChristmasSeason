package de.boondocksulfur.christmas.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import de.boondocksulfur.christmas.manager.WichtelManager;

/** Wichtel are zombies by nature - keep them from attacking anyone. */
public class WichtelTargetBlocker implements Listener {

    @EventHandler
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (e.getEntity().getScoreboardTags().contains(WichtelManager.TAG_WICHTEL)) {
            e.setCancelled(true);
            e.setTarget(null);
        }
    }
}
