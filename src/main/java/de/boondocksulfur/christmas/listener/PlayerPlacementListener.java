package de.boondocksulfur.christmas.listener;

import de.boondocksulfur.christmas.ChristmasSeason;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Remembers snow layers and ice that players place while the event is active, so the
 * restore does not treat them as event snow. Snow blocks, packed and blue ice are never
 * removed anyway and need no bookkeeping.
 */
public class PlayerPlacementListener implements Listener {

    private final ChristmasSeason plugin;

    public PlayerPlacementListener(ChristmasSeason plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!plugin.isActive()) return;
        Material type = e.getBlockPlaced().getType();
        if (type != Material.SNOW && type != Material.ICE) return;
        if (!plugin.isSnowWorld(e.getBlock().getWorld())) return;
        plugin.getBiomeSnowManager().markPlayerPlaced(e.getBlock().getLocation());
    }
}
