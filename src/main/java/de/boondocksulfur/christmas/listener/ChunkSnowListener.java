package de.boondocksulfur.christmas.listener;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import de.boondocksulfur.christmas.ChristmasSeason;

public class ChunkSnowListener implements Listener {

    private final ChristmasSeason plugin;
    public ChunkSnowListener(ChristmasSeason plugin) { this.plugin = plugin; }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!plugin.isActive()) return;

        String worldName = plugin.getConfig().getString("snowWorld", "world");
        World w = e.getWorld();
        if (!w.getName().equals(worldName)) return;

        // Originale sichern & Schnee setzen über Manager (inkl. Refresh)
        plugin.getBiomeSnowManager().ensureSnow(e.getChunk());
    }
}
