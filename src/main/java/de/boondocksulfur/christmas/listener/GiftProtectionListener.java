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
 * Schützt Geschenk-Kisten vor Hoppern und Explosionen.
 * Spieler dürfen sie weiterhin öffnen und abbauen - nur automatisches
 * Leerräumen und Zerstörung durch Creeper/TNT werden verhindert.
 * Abschaltbar über gifts.protectChests in der config.yml.
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
        // Falls ein Spieler eine zweite Kiste daneben stellt, wird daraus eine
        // Doppelkiste - beide Hälften prüfen, sonst wäre der Schutz umgehbar
        if (holder instanceof DoubleChest dc) {
            return isGiftHolder(dc.getLeftSide()) || isGiftHolder(dc.getRightSide());
        }
        return false;
    }

    /** Hopper, Hopper-Loren & Co. dürfen Geschenk-Kisten nicht leerräumen */
    @EventHandler(ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent e) {
        if (!protectionEnabled()) return;
        if (isGiftHolder(e.getSource().getHolder())) {
            e.setCancelled(true);
        }
    }

    /** Creeper, TNT etc. zerstören Geschenk-Kisten nicht */
    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (!protectionEnabled()) return;
        e.blockList().removeIf(b -> b.getType() == Material.CHEST
                && plugin.getGiftManager().isGiftChest(b.getLocation()));
    }

    /** Block-Explosionen (z.B. Betten im Nether) ebenfalls abfangen */
    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (!protectionEnabled()) return;
        e.blockList().removeIf(b -> b.getType() == Material.CHEST
                && plugin.getGiftManager().isGiftChest(b.getLocation()));
    }
}
