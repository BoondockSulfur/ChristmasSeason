package de.boondocksulfur.christmas.manager;

import com.tcoded.folialib.wrapper.task.WrappedTask;
import de.boondocksulfur.christmas.ChristmasSeason;
import org.bukkit.Chunk;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;

/**
 * Keeps event objects (mobs, decoration items, gift chests) in loaded chunks in line with
 * the event state: they are adopted while the event is active and removed otherwise.
 *
 * <p>One budgeted scan replaces a full pass per manager. The state is read when a chunk's
 * turn comes, so a scan that overlaps an on/off switch cannot act on the old state. Chunks
 * that unload before their turn are handled by {@code TrackedObjectListener} on their next load.
 */
public class LoadedObjectSync {

    private static final int CHUNKS_PER_TICK = 64;

    private final ChristmasSeason plugin;
    private WrappedTask scan;

    public LoadedObjectSync(ChristmasSeason plugin) {
        this.plugin = plugin;
    }

    /** Starts a scan of all event worlds; a scan still running is replaced. */
    public void start() {
        cancel();
        scan = plugin.getFoliaScheduler().forEachLoadedChunk(
                plugin.getWichtelManager().mobWorlds(), CHUNKS_PER_TICK, this::syncChunk);
    }

    public void cancel() {
        if (scan != null) {
            scan.cancel();
            scan = null;
        }
    }

    /** Must run on the chunk's thread. */
    private void syncChunk(Chunk chunk) {
        boolean active = plugin.isActive();
        if (active) {
            plugin.getGiftManager().adoptInChunk(chunk);
        } else {
            plugin.getGiftManager().removeInChunk(chunk);
        }
        for (Entity entity : chunk.getEntities()) {
            syncEntity(entity, active);
        }
    }

    /** Adopts or removes one event entity, other entities are ignored. Must run on the entity's thread. */
    public void syncEntity(Entity entity, boolean active) {
        if (plugin.getWichtelManager().isEventMob(entity)) {
            if (active) plugin.getWichtelManager().adopt(entity);
            else remove(entity);
        } else if (plugin.getSnowmanManager().isEventSnowman(entity)) {
            if (active) plugin.getSnowmanManager().adopt(entity);
            else remove(entity);
        } else if (plugin.getDecorationManager().isDecoration(entity)) {
            if (active) plugin.getDecorationManager().adopt((Item) entity);
            else remove(entity);
        }
    }

    private void remove(Entity entity) {
        entity.remove();
        plugin.debug("Removed orphaned event object: " + entity.getType()
                + " @ " + entity.getLocation().getBlockX() + "," + entity.getLocation().getBlockZ());
    }
}
