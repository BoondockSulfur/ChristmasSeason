package de.boondocksulfur.christmas.listener;

import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import de.boondocksulfur.christmas.ChristmasSeason;

/**
 * Keeps the in-memory tracking of event objects (mobs, decoration items, gift chests)
 * in sync with the world.
 *
 * <ul>
 *   <li>Event active: objects found in loading chunks are adopted (they were spawned
 *       before a restart/reload and would otherwise live forever without a cap or lifetime).</li>
 *   <li>Event inactive: they are removed - {@code /xmas off} only reaches loaded chunks.</li>
 * </ul>
 *
 * Both events fire on the thread that owns the chunk, so the managers may touch the
 * objects directly.
 */
public class TrackedObjectListener implements Listener {

    private final ChristmasSeason plugin;

    public TrackedObjectListener(ChristmasSeason plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        boolean active = plugin.isActive();
        for (Entity entity : e.getEntities()) {
            plugin.getLoadedObjectSync().syncEntity(entity, active);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        // A freshly generated chunk cannot contain gifts
        if (e.isNewChunk() || !plugin.getConfig().getBoolean("gifts.enabled", true)) return;
        if (plugin.isActive()) {
            plugin.getGiftManager().adoptInChunk(e.getChunk());
        } else {
            plugin.getGiftManager().removeInChunk(e.getChunk());
        }
    }
}
