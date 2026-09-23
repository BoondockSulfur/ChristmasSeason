package de.boondocksulfur.christmas.listener;

import org.bukkit.Material;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.InventoryHolder;
import de.boondocksulfur.christmas.ChristmasSeason;

/**
 * Protects gift chests from hoppers and explosions. Players can still open and break
 * them - only automated emptying and creeper/TNT damage are prevented.
 * Can be disabled via {@code gifts.protectChests}.
 */
public class GiftProtectionListener implements Listener {

    private final ChristmasSeason plugin;

    public GiftProtectionListener(ChristmasSeason plugin) {
        this.plugin = plugin;
    }

    private boolean protectionEnabled() {
        return plugin.getConfig().getBoolean("gifts.protectChests", true);
    }

    private boolean isGiftHolder(InventoryHolder holder) {
        if (holder instanceof Chest chest) {
            return plugin.getGiftManager().isGiftChest(chest.getLocation());
        }
        // Gift chests are never placed next to another chest, but check both halves anyway
        if (holder instanceof DoubleChest dc) {
            return isGiftHolder(dc.getLeftSide()) || isGiftHolder(dc.getRightSide());
        }
        return false;
    }

    /** Hoppers, hopper minecarts etc. must not empty gift chests. */
    @EventHandler(ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent e) {
        if (!protectionEnabled()) return;
        if (isGiftHolder(e.getSource().getHolder())) {
            e.setCancelled(true);
        }
    }

    /** Creepers, TNT etc. do not destroy gift chests. */
    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (!protectionEnabled()) return;
        e.blockList().removeIf(b -> b.getType() == Material.CHEST
                && plugin.getGiftManager().isGiftChest(b.getLocation()));
    }

    /** Block explosions (beds in the Nether, respawn anchors) as well. */
    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (!protectionEnabled()) return;
        e.blockList().removeIf(b -> b.getType() == Material.CHEST
                && plugin.getGiftManager().isGiftChest(b.getLocation()));
    }
}
