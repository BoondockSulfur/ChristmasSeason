package de.boondocksulfur.christmas.listener;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowman;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;
import de.boondocksulfur.christmas.manager.SnowmanManager;

public class SnowmanDamageListener implements Listener {

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        Entity damager = e.getDamager();

        // Direkter Schlag vom Schneemann
        if (damager instanceof Snowman sm && sm.getScoreboardTags().contains(SnowmanManager.TAG)) {
            e.setCancelled(true);
            e.setDamage(0);
            return;
        }

        // Projektil vom Schneemann
        if (damager instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Snowman sm && sm.getScoreboardTags().contains(SnowmanManager.TAG)) {
                e.setCancelled(true);
                e.setDamage(0);
            }
        }
    }
}
