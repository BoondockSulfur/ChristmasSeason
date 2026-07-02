package de.boondocksulfur.christmas.manager;

import org.bukkit.Bukkit;
import org.bukkit.World;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.util.FoliaSchedulerHelper;

public class SnowstormManager {

    private final ChristmasSeason plugin;
    private final FoliaSchedulerHelper scheduler;
    private WrappedTask enforceTask, autoTask;
    private boolean desiredStorm = true;

    public SnowstormManager(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.scheduler = plugin.getFoliaScheduler();
    }

    public void start() {
        stop();

        // Prüfe ob Schneesturm in Config aktiviert ist
        if (!plugin.getConfig().getBoolean("snowstorm.enabled", true)) {
            plugin.debug("Schneesturm ist in der Config deaktiviert (snowstorm.enabled: false)");
            return;
        }

        desiredStorm = true;

        int interval = plugin.getConfig().getInt("snowstorm.forceWeatherTicks", 200);
        enforceTask = scheduler.runGlobalTaskTimer(this::enforce, 20L, interval);

        if ("auto".equalsIgnoreCase(plugin.getConfig().getString("snowstorm.mode", "manual"))) startAuto();
    }

    public void stop() {
        if (enforceTask != null) { enforceTask.cancel(); enforceTask = null; }
        if (autoTask != null)     { autoTask.cancel();     autoTask = null; }
    }

    private void startAuto() {
        final int onSec  = Math.max(5, plugin.getConfig().getInt("snowstorm.auto.onSeconds", 150));
        final int offSec = Math.max(5, plugin.getConfig().getInt("snowstorm.auto.offSeconds", 45));

        // OPTIMIERT: Verwende runTaskLater statt runTaskTimer - vermeidet jeden-Tick-Overhead!
        scheduleAutoToggle(true, onSec, offSec);
    }

    private void scheduleAutoToggle(boolean currentState, int onSec, int offSec) {
        // Setze aktuellen State und enforce (WICHTIG: über Global Scheduler wegen Folia!)
        desiredStorm = currentState;
        scheduler.runGlobalTask(this::enforce);

        // Plane nächsten Toggle (kein Task läuft dauerhaft!)
        long delay = (currentState ? onSec : offSec) * 20L;
        autoTask = scheduler.runGlobalTaskLater(() -> {
            // Toggle State und plane rekursiv nächsten Toggle
            scheduleAutoToggle(!currentState, onSec, offSec);
        }, delay);
    }

    private void enforce() {
        World w = Bukkit.getWorld(plugin.getConfig().getString("snowWorld", "world"));
        if (w == null) return;
        if (desiredStorm) {
            if (!w.hasStorm()) { w.setStorm(true); w.setThundering(false); }
            try { w.setClearWeatherDuration(0); } catch (Throwable ignored) {}
            w.setWeatherDuration(20*60*10);
        } else {
            if (w.hasStorm() || w.isThundering()) forceClearShort(w);
        }
    }

    // ===== Public controls =====
    // WICHTIG: Alle World-Operationen müssen auf Folia über Global Scheduler laufen!
    public void setStorm(boolean on) { desiredStorm = on; scheduler.runGlobalTask(this::enforce); }
    public boolean toggleStorm() { setStorm(!desiredStorm); return desiredStorm; }
    public boolean isStorm() { return desiredStorm; }
    public void pulse(int seconds) { setStorm(true); scheduler.runGlobalTaskLater(() -> setStorm(false), seconds*20L); }

    // ===== Clear helpers =====
    // Diese Methoden sollten NUR aus einem Global Scheduler Kontext aufgerufen werden!
    public void forceClearShort(World w) {
        w.setStorm(false);
        w.setThundering(false);
        try { w.setClearWeatherDuration(20*60*5); } catch (Throwable ignored) { w.setWeatherDuration(20*60*5); }
    }
    public void forceClearLong(World w) {
        w.setStorm(false);
        w.setThundering(false);
        try { w.setClearWeatherDuration(20*60*60); } catch (Throwable ignored) { w.setWeatherDuration(20*60*60); }
    }
}
