package de.boondocksulfur.christmas.util;

import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Thin wrapper around FoliaLib that gives every manager the same scheduling
 * vocabulary on Paper, Purpur and Folia.
 *
 * <p>On Paper/Purpur all "global", "location" and "entity" tasks end up on the
 * main thread (next tick). On Folia they are dispatched to the global region,
 * the region owning the location, or the entity's owning region respectively.
 */
public class FoliaSchedulerHelper {

    private final FoliaLib foliaLib;

    public FoliaSchedulerHelper(Plugin plugin) {
        this.foliaLib = new FoliaLib(plugin);
    }

    /** Runs a task on the global region scheduler (world-wide operations such as weather). */
    public void runGlobalTask(Runnable task) {
        foliaLib.getScheduler().runNextTick(wrappedTask -> task.run());
    }

    /**
     * Runs a delayed task on the global region scheduler.
     *
     * @param delayTicks delay in ticks (20 ticks = 1 second)
     * @return task handle that can be cancelled
     */
    public WrappedTask runGlobalTaskLater(Runnable task, long delayTicks) {
        return foliaLib.getScheduler().runLater(task, delayTicks);
    }

    /**
     * Runs a repeating task on the global region scheduler.
     *
     * @param delayTicks  initial delay in ticks
     * @param periodTicks period between executions in ticks
     * @return task handle that can be cancelled
     */
    public WrappedTask runGlobalTaskTimer(Runnable task, long delayTicks, long periodTicks) {
        return foliaLib.getScheduler().runTimer(task, delayTicks, periodTicks);
    }

    /**
     * Runs a task on the region that owns the given location.
     * This is the only safe way to touch blocks, chunks or biomes on Folia.
     */
    public void runAtLocation(Location location, Runnable task) {
        foliaLib.getScheduler().runAtLocation(location, wrappedTask -> task.run());
    }

    /** Delayed variant of {@link #runAtLocation(Location, Runnable)}. */
    public WrappedTask runAtLocationLater(Location location, Runnable task, long delayTicks) {
        return foliaLib.getScheduler().runAtLocationLater(location, task, delayTicks);
    }

    /** Repeating variant of {@link #runAtLocation(Location, Runnable)}. */
    public WrappedTask runAtLocationTimer(Location location, Runnable task, long delayTicks, long periodTicks) {
        return foliaLib.getScheduler().runAtLocationTimer(location, task, delayTicks, periodTicks);
    }

    /** Runs a task on the entity's scheduler (no-op if the entity is already invalid). */
    public void runForEntity(Entity entity, Runnable task) {
        if (entity.isValid()) {
            foliaLib.getScheduler().runAtEntity(entity, wrappedTask -> task.run());
        }
    }

    /**
     * Runs a delayed task on the entity's scheduler.
     *
     * @return task handle, or {@code null} if the entity is invalid
     */
    public WrappedTask runForEntityLater(Entity entity, Runnable task, long delayTicks) {
        if (entity.isValid()) {
            return foliaLib.getScheduler().runAtEntityLater(entity, task, delayTicks);
        }
        return null;
    }

    /**
     * Like {@link #runForEntityLater(Entity, Runnable, long)} but with a retired callback.
     *
     * <p>On Folia, entity-scheduler tasks are <em>retired</em> (dropped) when the
     * entity is removed before the task runs. Any bookkeeping (tracking sets, caps)
     * therefore has to happen in the retired callback, otherwise the entries leak.
     *
     * @param retired runs when the entity was removed before the task executed
     */
    public WrappedTask runForEntityLater(Entity entity, Runnable task, Runnable retired, long delayTicks) {
        if (entity.isValid()) {
            return foliaLib.getScheduler().runAtEntityLater(entity, task, retired, delayTicks);
        }
        // Entity is already gone: run the bookkeeping right away
        if (retired != null) retired.run();
        return null;
    }

    /**
     * Runs a repeating task on the entity's scheduler.
     *
     * @return task handle, or {@code null} if the entity is invalid
     */
    public WrappedTask runForEntityTimer(Entity entity, Runnable task, long delayTicks, long periodTicks) {
        if (entity.isValid()) {
            return foliaLib.getScheduler().runAtEntityTimer(entity, task, null, delayTicks, periodTicks);
        }
        return null;
    }

    /**
     * Like {@link #runForEntityTimer(Entity, Runnable, long, long)} but with a retired
     * callback (see {@link #runForEntityLater(Entity, Runnable, Runnable, long)}).
     */
    public WrappedTask runForEntityTimer(Entity entity, Runnable task, Runnable retired, long delayTicks, long periodTicks) {
        if (entity.isValid()) {
            return foliaLib.getScheduler().runAtEntityTimer(entity, task, retired, delayTicks, periodTicks);
        }
        if (retired != null) retired.run();
        return null;
    }

    /** Runs a task asynchronously (never touch world state from here). */
    public void runAsync(Runnable task) {
        foliaLib.getScheduler().runAsync(wrappedTask -> task.run());
    }

    /** Delayed asynchronous task. */
    public WrappedTask runAsyncLater(Runnable task, long delayTicks) {
        return foliaLib.getScheduler().runLaterAsync(task, delayTicks);
    }

    /** Repeating asynchronous task. */
    public WrappedTask runAsyncTimer(Runnable task, long delayTicks, long periodTicks) {
        return foliaLib.getScheduler().runTimerAsync(task, delayTicks, periodTicks);
    }

    /**
     * Executes a task for every loaded chunk of a world, each on the region that
     * owns the chunk. On Paper this simply runs on the main thread.
     */
    public void forEachLoadedChunk(World world, ChunkTask task) {
        for (Chunk chunk : world.getLoadedChunks()) {
            runAtLocation(chunkCenter(chunk.getWorld(), chunk.getX(), chunk.getZ()), () -> task.accept(chunk));
        }
    }

    /** Location in the middle of a chunk, used to pick the owning region. */
    public static Location chunkCenter(World world, int chunkX, int chunkZ) {
        return new Location(world, (chunkX << 4) + 8, 64, (chunkZ << 4) + 8);
    }

    /** @return {@code true} when running on Folia (regionised threading). */
    public boolean isFolia() {
        return foliaLib.isFolia();
    }

    /** Functional interface for per-chunk tasks. */
    @FunctionalInterface
    public interface ChunkTask {
        void accept(Chunk chunk);
    }
}
