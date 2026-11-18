package de.boondocksulfur.christmas.listener;

import org.bukkit.HeightMap;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowman;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.projectiles.ProjectileSource;
import de.boondocksulfur.christmas.manager.SnowmanManager;

/**
 * Snowballs from event snow golems hit without damage; melee hits are cancelled. Their snow
 * trail only forms under the open sky, where the biome restore removes it again.
 */
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
                // No damage, but the hit itself (knockback, hurt effect) stays visible
                e.setDamage(0);
            }
        }
    }

    /** Targets are picked by the plugin at its own pace; the vanilla AI would fire every second. */
    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        if (e.getEntity() instanceof Snowman sm && sm.getScoreboardTags().contains(SnowmanManager.TAG)) {
            e.setCancelled(true);
        }
    }

    /** Trail snow under leaves, roofs or overhangs would outlast {@code /xmas off}. */
    @EventHandler(ignoreCancelled = true)
    public void onTrail(EntityBlockFormEvent e) {
        if (!(e.getEntity() instanceof Snowman sm) || !sm.getScoreboardTags().contains(SnowmanManager.TAG)) return;
        Block b = e.getBlock();
        if (b.getY() <= b.getWorld().getHighestBlockYAt(b.getX(), b.getZ(), HeightMap.WORLD_SURFACE)) {
            e.setCancelled(true);
        }
    }
}
