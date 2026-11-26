package de.boondocksulfur.christmas.listener;

import de.boondocksulfur.christmas.ChristmasSeason;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.List;

/**
 * Lets the biome manager re-check chunks where blocks above a cave biome were removed,
 * so a cave biome that becomes the new surface during the event turns wintry too.
 */
public class SurfaceDigListener implements Listener {

    private final ChristmasSeason plugin;

    public SurfaceDigListener(ChristmasSeason plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        handle(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        handleAll(e.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        handleAll(e.blockList());
    }

    private void handleAll(List<Block> blocks) {
        if (blocks.isEmpty()) return;
        for (Block b : blocks) handle(b);
    }

    private void handle(Block block) {
        if (!plugin.isActive()) return;
        if (!plugin.isSnowWorld(block.getWorld())) return;
        plugin.getBiomeSnowManager().requestSurfaceRecheck(block);
    }
}
