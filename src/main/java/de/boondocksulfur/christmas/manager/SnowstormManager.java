package de.boondocksulfur.christmas.manager;

import org.bukkit.Bukkit;
import org.bukkit.World;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.util.FoliaSchedulerHelper;

/**
 * Keeps the snow world stormy (snow falls in snowy biomes during rain weather).
 * Modes: {@code manual} (always storm), {@code auto} (alternating phases) and
 * {@code none} (weather untouched; {@code /xmas storm} still works on demand).
 * All weather calls run on the global region scheduler (required on Folia).
 */
public class SnowstormManager {

    private final ChristmasSeason plugin;
    private final FoliaSchedulerHelper scheduler;
    private WrappedTask enforceTask, autoTask;
    private volatile boolean desiredStorm = true;
    /** Stops the recursive auto-toggle chain reliably after stop(). */
    private volatile boolean autoRunning = false;

    public SnowstormManager(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.scheduler = plugin.getFoliaScheduler();
    }

    public void start() {
        stop();

        if (!plugin.getConfig().getBoolean("snowstorm.enabled", true)) {
            plugin.debug("Snowstorm disabled in config (snowstorm.enabled: false)");
            return;
        }

        String mode = plugin.getConfig().getString("snowstorm.mode", "manual");
        if ("none".equalsIgnoreCase(mode)) {
            desiredStorm = false;
            plugin.debug("Snowstorm mode 'none': weather is left alone");
            return;
        }

        desiredStorm = true;
        int interval = Math.max(20, plugin.getConfig().getInt("snowstorm.forceWeatherTicks", 200));
        enforceTask = scheduler.runGlobalTaskTimer(this::enforce, 20L, interval);

        if ("auto".equalsIgnoreCase(mode)) startAuto();
    }

    public void stop() {
        autoRunning = false;
        if (enforceTask != null) { enforceTask.cancel(); enforceTask = null; }
        if (autoTask != null)     { autoTask.cancel();     autoTask = null; }
    }

    private void startAuto() {
        final int onSec  = Math.max(5, plugin.getConfig().getInt("snowstorm.auto.onSeconds", 150));
        final int offSec = Math.max(5, plugin.getConfig().getInt("snowstorm.auto.offSeconds", 45));
        autoRunning = true;
        scheduleAutoToggle(true, onSec, offSec);
    }

    /** Applies the current phase and schedules the next toggle (one delayed task instead of a per-tick timer). */
    private void scheduleAutoToggle(boolean currentState, int onSec, int offSec) {
        if (!autoRunning) return;

        desiredStorm = currentState;
        scheduler.runGlobalTask(this::enforce);

        long delay = (currentState ? onSec : offSec) * 20L;
        autoTask = scheduler.runGlobalTaskLater(() -> scheduleAutoToggle(!currentState, onSec, offSec), delay);
    }

    /** Pushes the world weather towards the desired state; global scheduler only. */
    private void enforce() {
        for (String name : plugin.getSnowWorldNames()) {
            World w = Bukkit.getWorld(name);
            if (w == null) continue;
            if (desiredStorm) {
                if (!w.hasStorm()) { w.setStorm(true); w.setThundering(false); }
                try { w.setClearWeatherDuration(0); } catch (Throwable ignored) {}
                w.setWeatherDuration(20 * 60 * 10);
            } else {
                // Unconditional: the server-side flag may already be false while clients still
                // render snow; setting it again resends the weather state
                forceClearShort(w);
            }
        }
    }

    // ----------------------------------------------------------- public API

    /**
     * Manual override. {@code false} also pauses the auto phases until {@code /xmas storm on}
     * or the next start/reload, otherwise the next phase would switch the storm back on.
     */
    public void setStorm(boolean on) {
        if (!on && autoRunning) {
            autoRunning = false;
            if (autoTask != null) { autoTask.cancel(); autoTask = null; }
            plugin.debug("Snowstorm auto mode paused by manual off");
        }
        desiredStorm = on;
        scheduler.runGlobalTask(this::enforce);
    }
    public boolean toggleStorm() { setStorm(!desiredStorm); return desiredStorm; }
    public boolean isStorm() { return desiredStorm; }

    /** Storm for {@code seconds}, then clear. */
    public void pulse(int seconds) {
        setStorm(true);
        scheduler.runGlobalTaskLater(() -> setStorm(false), seconds * 20L);
    }

    /** Clears the weather for five minutes; global scheduler only. */
    public void forceClearShort(World w) {
        w.setStorm(false);
        w.setThundering(false);
        try { w.setClearWeatherDuration(20 * 60 * 5); } catch (Throwable ignored) { w.setWeatherDuration(20 * 60 * 5); }
    }

    /** Clears the weather for an hour; global scheduler only. */
    public void forceClearLong(World w) {
        w.setStorm(false);
        w.setThundering(false);
        try { w.setClearWeatherDuration(20 * 60 * 60); } catch (Throwable ignored) { w.setWeatherDuration(20 * 60 * 60); }
    }
}
