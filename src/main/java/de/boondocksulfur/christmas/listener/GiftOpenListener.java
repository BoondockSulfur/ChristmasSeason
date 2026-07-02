package de.boondocksulfur.christmas.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import de.boondocksulfur.christmas.ChristmasSeason;

public class GiftOpenListener implements Listener {

    private final ChristmasSeason plugin;

    public GiftOpenListener(ChristmasSeason plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onOpen(InventoryOpenEvent e) {
        // Platz für später (Log, Stats, etc.)
    }
}
