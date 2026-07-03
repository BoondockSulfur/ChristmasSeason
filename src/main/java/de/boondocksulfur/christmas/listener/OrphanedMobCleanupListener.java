package de.boondocksulfur.christmas.listener;

import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.manager.SnowmanManager;
import de.boondocksulfur.christmas.manager.WichtelManager;

import java.util.Set;

/**
 * Räumt Event-Mobs auf, deren Chunks beim '/xmas off'-Cleanup nicht geladen
 * waren: cleanup() erreicht nur geladene Entities - getaggte Mobs in
 * entladenen Chunks blieben sonst dauerhaft in der Welt (haben
 * setRemoveWhenFarAway(false) und keinen Lifetime-Task mehr).
 * FOLIA-SAFE: EntitiesLoadEvent feuert auf dem Region-Thread des Chunks.
 */
public class OrphanedMobCleanupListener implements Listener {

    private final ChristmasSeason plugin;

    public OrphanedMobCleanupListener(ChristmasSeason plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        if (plugin.isActive()) return; // Event läuft - Mobs gehören dahin

        for (Entity entity : e.getEntities()) {
            Set<String> tags = entity.getScoreboardTags();
            if (tags.contains(WichtelManager.TAG_WICHTEL)
                    || tags.contains(WichtelManager.TAG_ELF)
                    || tags.contains(SnowmanManager.TAG)) {
                entity.remove();
                plugin.debug("Verwaisten Event-Mob entfernt: " + entity.getType()
                        + " @ " + entity.getLocation().getBlockX() + "," + entity.getLocation().getBlockZ());
            }
        }
    }
}
