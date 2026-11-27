package de.boondocksulfur.christmas.listener;

import de.boondocksulfur.christmas.ChristmasSeason;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;

/**
 * Hands snow layers and ice formed by the weather (or snow golems) while the event is active
 * to the biome manager, so the restore removes them even under blocks built on top later.
 */
public class FormedSnowListener implements Listener {

    private final ChristmasSeason plugin;

    public FormedSnowListener(ChristmasSeason plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent e) {
        if (!plugin.isActive()) return;
        Material type = e.getNewState().getType();
        if (type != Material.SNOW && type != Material.ICE) return;
        if (!plugin.isSnowWorld(e.getBlock().getWorld())) return;
        plugin.getBiomeSnowManager().recordFormed(e.getBlock(), type == Material.ICE);
    }
}
