package de.boondocksulfur.christmas.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import de.boondocksulfur.christmas.manager.SnowmanManager;
import de.boondocksulfur.christmas.manager.WichtelManager;

import java.util.Set;

/** Event mobs (baby zombies in particular) must not burn in daylight. */
public class MobProtectionListener implements Listener {

    @EventHandler
    public void onCombust(EntityCombustEvent e) {
        Set<String> tags = e.getEntity().getScoreboardTags();
        if (tags.contains(WichtelManager.TAG_WICHTEL)
                || tags.contains(WichtelManager.TAG_ELF)
                || tags.contains(SnowmanManager.TAG)) {
            e.setCancelled(true);
        }
    }
}
