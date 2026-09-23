package de.boondocksulfur.christmas.listener;

import de.boondocksulfur.christmas.ChristmasSeason;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.InventoryHolder;

/** Detects the first opening of a gift chest (stats, effects, broadcast, API event). */
public class GiftOpenListener implements Listener {

    private final ChristmasSeason plugin;

    public GiftOpenListener(ChristmasSeason plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        InventoryHolder holder = e.getInventory().getHolder();
        if (!(holder instanceof Chest chest)) return;
        if (!(e.getPlayer() instanceof Player player)) return;
        if (!plugin.getGiftManager().isMarkedGiftChest(chest)) return;
        plugin.getGiftManager().onFirstOpen(player, chest);
    }
}
