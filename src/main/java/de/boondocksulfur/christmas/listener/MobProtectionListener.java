package de.boondocksulfur.christmas.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import de.boondocksulfur.christmas.manager.SnowmanManager;
import de.boondocksulfur.christmas.manager.WichtelManager;

import java.util.Set;

/**
 * Event mobs (baby zombies in particular) must not burn in daylight. Fire from players
 * (fire aspect, flame arrows) and from blocks (fire, lava) still works.
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
}
