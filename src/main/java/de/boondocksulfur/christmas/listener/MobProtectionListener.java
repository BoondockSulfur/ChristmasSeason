package de.boondocksulfur.christmas.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import de.boondocksulfur.christmas.manager.SnowmanManager;
import de.boondocksulfur.christmas.manager.WichtelManager;

import java.util.Set;

/**
 * Event mobs (baby zombies in particular) must not burn in daylight. Fire from players
 * (fire aspect, flame arrows) and from blocks (fire, lava) still works.
 *
 * <p>Wichtel and elves only hold what the plugin gives them: allays would otherwise collect
 * matching items on their own, and players could hand items over or take carried loot.
 */
public class MobProtectionListener implements Listener {

    @EventHandler
    public void onCombust(EntityCombustEvent e) {
        if (e instanceof EntityCombustByEntityEvent || e instanceof EntityCombustByBlockEvent) return;
        Set<String> tags = e.getEntity().getScoreboardTags();
        if (tags.contains(WichtelManager.TAG_WICHTEL)
                || tags.contains(WichtelManager.TAG_ELF)
                || tags.contains(SnowmanManager.TAG)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        Set<String> tags = e.getEntity().getScoreboardTags();
        if (tags.contains(WichtelManager.TAG_WICHTEL) || tags.contains(WichtelManager.TAG_ELF)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getRightClicked().getScoreboardTags().contains(WichtelManager.TAG_ELF)) {
            e.setCancelled(true);
        }
    }
}
