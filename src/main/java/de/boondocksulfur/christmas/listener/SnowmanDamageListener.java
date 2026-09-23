package de.boondocksulfur.christmas.listener;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowman;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;
import de.boondocksulfur.christmas.manager.SnowmanManager;

/** Snowballs (and melee hits) from event snow golems never deal damage. */
public class SnowmanDamageListener implements Listener {

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        Entity damager = e.getDamager();

        if (damager instanceof Snowman sm && sm.getScoreboardTags().contains(SnowmanManager.TAG)) {
            e.setCancelled(true);
            return;
        }

        if (damager instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Snowman sm && sm.getScoreboardTags().contains(SnowmanManager.TAG)) {
                e.setCancelled(true);
            }
        }
    }
}
