package de.boondocksulfur.christmas.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import de.boondocksulfur.christmas.ChristmasSeason;

/**
 * Hands freshly loaded chunks of the snow world to the biome manager, which queues
 * them only when a player is nearby (budgeted processing, see
 * {@link de.boondocksulfur.christmas.manager.BiomeSnowManager#onChunkLoad}).
 */
public class ChunkSnowListener implements Listener {

    private final ChristmasSeason plugin;

    public ChunkSnowListener(ChristmasSeason plugin) { this.plugin = plugin; }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!plugin.isActive()) return;
        if (!e.getWorld().getName().equals(plugin.getConfig().getString("snowWorld", "world"))) return;
        plugin.getBiomeSnowManager().onChunkLoad(e.getChunk());
    }
}
