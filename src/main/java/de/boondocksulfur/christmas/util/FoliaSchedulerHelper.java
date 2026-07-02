package de.boondocksulfur.christmas.util;

import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Helper class for multi-platform scheduling using FoliaLib.
 * Provides abstraction that works on Spigot, Paper, Purpur, and Folia.
 */
public class FoliaSchedulerHelper {

    private final FoliaLib foliaLib;

    public FoliaSchedulerHelper(Plugin plugin) {
        this.foliaLib = new FoliaLib(plugin);
    }

    /**
     * Runs a task on the global region scheduler (for world-wide operations).
     * On Folia: Uses global region scheduler
     * On Spigot/Paper: Uses Bukkit scheduler
     *
     * @param task The task to run
     */
    public void runGlobalTask(Runnable task) {
        foliaLib.getScheduler().runNextTick(wrappedTask -> task.run());
    }

    /**
     * Runs a delayed task on the global region scheduler.
     *
     * @param task       The task to run
     * @param delayTicks Delay in ticks (20 ticks = 1 second)
     * @return WrappedTask that can be cancelled
     */
    public WrappedTask runGlobalTaskLater(Runnable task, long delayTicks) {
        return foliaLib.getScheduler().runLater(task, delayTicks);
    }

    /**
     * Runs a repeating task on the global region scheduler.
     *
     * @param task         The task to run
     * @param delayTicks   Initial delay in ticks
     * @param periodTicks  Period between executions in ticks
     * @return WrappedTask that can be cancelled
     */
    public WrappedTask runGlobalTaskTimer(Runnable task, long delayTicks, long periodTicks) {
        return foliaLib.getScheduler().runTimer(task, delayTicks, periodTicks);
    }

    /**
     * Runs a task on the region scheduler for a specific location.
     * On Folia: Uses region scheduler
     * On Spigot/Paper: Uses Bukkit scheduler
     *
     * @param location The location whose region should execute the task
     * @param task     The task to run
     */
    public void runAtLocation(Location location, Runnable task) {
        foliaLib.getScheduler().runAtLocation(location, wrappedTask -> task.run());
    }

    /**
     * Runs a delayed task on the region scheduler for a specific location.
     *
     * @param location   The location whose region should execute the task
     * @param task       The task to run
     * @param delayTicks Delay in ticks
     * @return WrappedTask that can be cancelled
     */
    public WrappedTask runAtLocationLater(Location location, Runnable task, long delayTicks) {
        return foliaLib.getScheduler().runAtLocationLater(location, task, delayTicks);
    }

    /**
     * Runs a repeating task on the region scheduler for a specific location.
     *
     * @param location    The location whose region should execute the task
     * @param task        The task to run
     * @param delayTicks  Initial delay in ticks
     * @param periodTicks Period between executions in ticks
     * @return WrappedTask that can be cancelled
     */
    public WrappedTask runAtLocationTimer(Location location, Runnable task, long delayTicks, long periodTicks) {
        return foliaLib.getScheduler().runAtLocationTimer(location, task, delayTicks, periodTicks);
    }

    /**
     * Runs a task on the entity's scheduler.
     * On Folia: Uses entity scheduler
     * On Spigot/Paper: Uses Bukkit scheduler
     *
     * @param entity The entity whose scheduler should execute the task
     * @param task   The task to run
     */
    public void runForEntity(Entity entity, Runnable task) {
        if (entity.isValid()) {
            foliaLib.getScheduler().runAtEntity(entity, wrappedTask -> task.run());
        }
    }

    /**
     * Runs a delayed task on the entity's scheduler.
     *
     * @param entity     The entity whose scheduler should execute the task
     * @param task       The task to run
     * @param delayTicks Delay in ticks
     * @return WrappedTask that can be cancelled, or null if entity is invalid
     */
    public WrappedTask runForEntityLater(Entity entity, Runnable task, long delayTicks) {
        if (entity.isValid()) {
            return foliaLib.getScheduler().runAtEntityLater(entity, task, delayTicks);
        }
        return null;
    }

    /**
     * Runs a repeating task on the entity's scheduler.
     *
     * @param entity      The entity whose scheduler should execute the task
     * @param task        The task to run
     * @param delayTicks  Initial delay in ticks
     * @param periodTicks Period between executions in ticks
     * @return WrappedTask that can be cancelled, or null if entity is invalid
     */
    public WrappedTask runForEntityTimer(Entity entity, Runnable task, long delayTicks, long periodTicks) {
        if (entity.isValid()) {
            return foliaLib.getScheduler().runAtEntityTimer(entity, task, null, delayTicks, periodTicks);
        }
        return null;
    }

    /**
     * Runs an asynchronous task.
     * Works on all platforms.
     *
     * @param task The task to run
     */
    public void runAsync(Runnable task) {
        foliaLib.getScheduler().runAsync(wrappedTask -> task.run());
    }

    /**
     * Runs a delayed asynchronous task.
     *
     * @param task      The task to run
     * @param delayTicks Delay in ticks
     * @return WrappedTask that can be cancelled
     */
    public WrappedTask runAsyncLater(Runnable task, long delayTicks) {
        return foliaLib.getScheduler().runLaterAsync(task, delayTicks);
    }

    /**
     * Runs a repeating asynchronous task.
     *
     * @param task        The task to run
     * @param delayTicks  Initial delay in ticks
     * @param periodTicks Period between executions in ticks
     * @return WrappedTask that can be cancelled
     */
    public WrappedTask runAsyncTimer(Runnable task, long delayTicks, long periodTicks) {
        return foliaLib.getScheduler().runTimerAsync(task, delayTicks, periodTicks);
    }

    /**
     * Executes a task for each loaded chunk in a world.
     * This distributes the work across regions automatically on Folia.
     *
     * @param world The world to iterate chunks in
     * @param task  The task to run for each chunk
     */
    public void forEachLoadedChunk(World world, ChunkTask task) {
        for (Chunk chunk : world.getLoadedChunks()) {
            Location chunkLoc = chunk.getBlock(8, 64, 8).getLocation();
            runAtLocation(chunkLoc, () -> task.accept(chunk));
        }
    }

    /**
     * Checks if the server is running on Folia.
     *
     * @return true if Folia, false if Spigot/Paper/Purpur
     */
    public boolean isFolia() {
        return foliaLib.isFolia();
    }

    /**
     * Functional interface for chunk tasks.
     */
    @FunctionalInterface
    public interface ChunkTask {
        void accept(Chunk chunk);
    }
}
