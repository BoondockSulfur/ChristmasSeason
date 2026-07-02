package de.boondocksulfur.christmas.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import de.boondocksulfur.christmas.manager.SnowmanManager;
import de.boondocksulfur.christmas.manager.WichtelManager;

import java.util.Set;

public class MobProtectionListener implements Listener {

    @EventHandler
    public void onCombust(EntityCombustEvent e) {
        Set<String> tags = e.getEntity().getScoreboardTags();
        if (tags.contains(WichtelManager.TAG_WICHTEL)
                || tags.contains(WichtelManager.TAG_ELF)
                || tags.contains(SnowmanManager.TAG)) {
            // unsere weihnachtlichen Mobs sollen nicht in der Sonne verbrennen
            e.setCancelled(true);
        }
    }
}
