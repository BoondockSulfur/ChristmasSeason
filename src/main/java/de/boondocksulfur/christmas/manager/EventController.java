package de.boondocksulfur.christmas.manager;

import com.tcoded.folialib.wrapper.task.WrappedTask;
import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.api.XmasStateChangeEvent;
import de.boondocksulfur.christmas.util.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Activation and deactivation of the event (shared by {@code /xmas on|off}, the
 * schedule and the API), the date-based schedule and runtime feature toggles.
 */
public class EventController {

    /** Config keys that {@code /xmas feature} may toggle. */
    public static final Map<String, String> FEATURES = Map.of(
            "biome", "biome.enabled",
            "snowstorm", "snowstorm.enabled",
            "decoration", "decoration.enabled",
            "gifts", "gifts.enabled",
            "wichtel", "wichtel.enabled",
            "elves", "elves.enabled",
            "snowmen", "snowmen.enabled",
            "advent", "advent.enabled");

    private final ChristmasSeason plugin;
    private final LanguageManager lang;
    private WrappedTask scheduleTask;
    /**
     * Last state the schedule applied; {@code null} if it never ran. Persisted in
     * {@code data/schedule.yml}, so a manual override survives restarts and reloads.
     */
    private volatile Boolean lastScheduledState;
    private final File scheduleFile;
    /** Set while {@code /xmas on} writes the SAFE backup off the main thread. */
    private final AtomicBoolean activating = new AtomicBoolean(false);

    public EventController(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.scheduleFile = new File(new File(plugin.getDataFolder(), "data"), "schedule.yml");
        loadScheduleState();
    }

    // ---------------------------------------------------------- on / off

    /**
     * Activates the event: SAFE backup (off the main thread), config flag, managers, API event.
     *
     * @param feedback receives the user-facing messages (player or console)
     * @return {@code true} if the activation was started; {@code false} if it was refused
     *         (already active, restore or activation running)
     */
    public boolean activate(CommandSender feedback, XmasStateChangeEvent.Cause cause) {
        if (plugin.getBiomeSnowManager().isRestoring()) {
            lang.send(feedback, "command.on.restore-running");
            return false;
        }
        if (!activating.compareAndSet(false, true)) {
            lang.send(feedback, "command.on.activation-running");
            return false;
        }
        if (plugin.isActive()) {
            activating.set(false);
            lang.send(feedback, "command.on.already-active");
            return false;
        }

        boolean snapshots = plugin.getConfig().getBoolean("biome.enableSnapshot", true);
        if (!snapshots) lang.send(feedback, "command.on.snapshot-disabled");

        try {
            // The SAFE copy must be complete before the first chunk changes
            plugin.getFoliaScheduler().runAsync(() -> {
                boolean backupFailed;
                try {
                    backupFailed = snapshots && !plugin.getBackupManager().createSafeBackup()
                            && plugin.getBackupManager().hasDatabaseFile();
                } catch (RuntimeException e) {
                    lang.logWarning("log.backup.error-creating-safe", e.getMessage());
                    backupFailed = true;
                }
                final boolean failed = backupFailed;
                plugin.getFoliaScheduler().runGlobalTask(() -> {
                    try {
                        if (failed) lang.send(feedback, "command.on.backup-failed");
                        finishActivation(feedback, cause);
                    } finally {
                        activating.set(false);
                    }
                });
            });
        } catch (RuntimeException e) {
            activating.set(false);
            throw e;
        }
        return true;
    }

    private void finishActivation(CommandSender feedback, XmasStateChangeEvent.Cause cause) {
        plugin.setActive(true);
        plugin.startFeatures();
        lang.send(feedback, "command.on.success");
        if (plugin.getConfig().getBoolean("announcements.onActivate", true)) {
            Bukkit.broadcast(lang.getComponent("broadcast.event-activated"));
        }
        Bukkit.getPluginManager().callEvent(new XmasStateChangeEvent(true, cause));
    }

    /**
     * Deactivates the event: backup, cleanup, budgeted biome restore, API event. While the
     * event is already off it only retries snapshots an earlier restore could not reset.
     *
     * @return {@code false} if refused (restore or activation running)
     */
    public boolean deactivate(CommandSender feedback, XmasStateChangeEvent.Cause cause) {
        if (plugin.getBiomeSnowManager().isRestoring()) {
            lang.send(feedback, "command.on.restore-running");
            return false;
        }
        if (activating.get()) {
            lang.send(feedback, "command.on.activation-running");
            return false;
        }

        int perTick = Math.max(1, plugin.getConfig().getInt("biome.restore.perTick", 4));

        if (!plugin.isActive()) {
            lang.send(feedback, "command.off.already-inactive");
            plugin.getLoadedObjectSync().start();
            plugin.getBiomeSnowManager().restoreALLAsync(perTick, null, true);
            return true;
        }

        boolean managesWeather = !"none".equalsIgnoreCase(plugin.getConfig().getString("snowstorm.mode", "manual"));

        plugin.setActive(false);
        if (managesWeather) plugin.getSnowstormManager().setStorm(false);
        plugin.getBiomeSnowManager().cancelConvertAll();

        // Cleanup BEFORE stopFeatures() so the trackers are still populated
        lang.send(feedback, "command.off.cleanup");
        plugin.getDecorationManager().cleanup();
        plugin.getGiftManager().cleanup();
        plugin.getWichtelManager().cleanup();
        plugin.getSnowmanManager().cleanup();
        plugin.getLoadedObjectSync().start();

        // Keep the database open - the restore needs it and closes it itself
        plugin.stopFeatures(false);

        Runnable backup = !plugin.getConfig().getBoolean("biome.enableSnapshot", true) ? null : () -> {
            boolean backupOk = plugin.getBackupManager().createTimestampBackup();
            if (!backupOk && plugin.getBackupManager().hasDatabaseFile()) {
                lang.send(feedback, "command.off.backup-failed");
            }
        };
        plugin.getBiomeSnowManager().restoreALLAsync(perTick, backup);

        if (managesWeather) {
            for (String name : plugin.getSnowWorldNames()) {
                World w = Bukkit.getWorld(name);
                if (w == null) continue;
                plugin.getFoliaScheduler().runGlobalTask(() -> {
                    w.setStorm(false);
                    w.setThundering(false);
                    w.setWeatherDuration(12000);
                });
            }
        }

        lang.send(feedback, "command.off.success");
        if (plugin.getConfig().getBoolean("announcements.onDeactivate", true)) {
            Bukkit.broadcast(lang.getComponent("broadcast.event-deactivated"));
        }
        Bukkit.getPluginManager().callEvent(new XmasStateChangeEvent(false, cause));
        return true;
    }

    // ---------------------------------------------------------- schedule

    /** Starts the schedule checker (no-op when {@code schedule.enabled} is false). */
    public void startSchedule() {
        stopSchedule();
        if (!plugin.getConfig().getBoolean("schedule.enabled", false)) return;
        long interval = 20L * 60 * Math.max(1, plugin.getConfig().getInt("schedule.checkIntervalMinutes", 5));
        scheduleTask = plugin.getFoliaScheduler().runGlobalTaskTimer(this::checkSchedule, 20L * 10, interval);
        lang.logInfo("log.schedule.enabled", formatMonthDay(startDay()), formatMonthDay(endDay()), plugin.getZoneId().getId());
    }

    public void stopSchedule() {
        if (scheduleTask != null) { scheduleTask.cancel(); scheduleTask = null; }
    }

    private void loadScheduleState() {
        if (!scheduleFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(scheduleFile);
        if (yml.isBoolean("lastApplied")) lastScheduledState = yml.getBoolean("lastApplied");
    }

    private void saveScheduleState(boolean state) {
        lastScheduledState = state;
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("lastApplied", state);
        try {
            scheduleFile.getParentFile().mkdirs();
            yml.save(scheduleFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save schedule.yml: " + e.getMessage());
        }
    }

    private MonthDay parseMonthDay(String value, MonthDay def) {
        if (value == null) return def;
        String v = value.trim();
        try {
            if (v.length() > 5) return MonthDay.from(LocalDate.parse(v, DateTimeFormatter.ISO_LOCAL_DATE));
            return MonthDay.parse("--" + v);
        } catch (DateTimeParseException e) {
            lang.logWarning("log.schedule.invalid-date", v);
            return def;
        }
    }

    public MonthDay startDay() { return parseMonthDay(plugin.getConfig().getString("schedule.start", "12-01"), MonthDay.of(12, 1)); }
    public MonthDay endDay()   { return parseMonthDay(plugin.getConfig().getString("schedule.end", "01-06"), MonthDay.of(1, 6)); }

    private static String formatMonthDay(MonthDay md) {
        return String.format("%02d-%02d", md.getMonthValue(), md.getDayOfMonth());
    }

    /** @return {@code true} if the given date lies inside the (possibly year-spanning) window */
    public boolean isInsideWindow(LocalDate date) {
        MonthDay start = startDay(), end = endDay(), today = MonthDay.from(date);
        if (!start.isAfter(end)) {
            return !today.isBefore(start) && !today.isAfter(end);
        }
        // Window crosses New Year, e.g. 12-01 .. 01-06
        return !today.isBefore(start) || !today.isAfter(end);
    }

    /** Next start date at or after {@code from}. */
    public LocalDate nextStart(LocalDate from) {
        LocalDate candidate = startDay().atYear(from.getYear());
        return candidate.isBefore(from) ? startDay().atYear(from.getYear() + 1) : candidate;
    }

    /** Next end date at or after {@code from}. */
    public LocalDate nextEnd(LocalDate from) {
        LocalDate candidate = endDay().atYear(from.getYear());
        return candidate.isBefore(from) ? endDay().atYear(from.getYear() + 1) : candidate;
    }

    /** Days until the season starts (0 while inside the window). */
    public long daysUntilStart() {
        LocalDate today = LocalDate.now(plugin.getZoneId());
        if (isInsideWindow(today)) return 0;
        return ChronoUnit.DAYS.between(today, nextStart(today));
    }

    /** Days left until the season ends (0 outside the window). */
    public long daysLeft() {
        LocalDate today = LocalDate.now(plugin.getZoneId());
        if (!isInsideWindow(today)) return 0;
        return ChronoUnit.DAYS.between(today, nextEnd(today));
    }

    /**
     * Applies the schedule on state transitions only, so a manual override survives until the
     * next transition - also across restarts, the last applied state is persisted. A refused
     * switch (restore running) is not recorded and retried on the next check.
     */
    private void checkSchedule() {
        if (!plugin.getConfig().getBoolean("schedule.enabled", false)) return;
        boolean shouldBeActive = isInsideWindow(LocalDate.now(plugin.getZoneId()));
        Boolean last = lastScheduledState;
        if (last != null && last == shouldBeActive) return;

        // No transition recorded yet and outside the window: only remember the state, so
        // enabling the schedule never switches off an event that was started by hand
        if (last == null && !shouldBeActive) {
            saveScheduleState(false);
            return;
        }

        boolean applied = true;
        if (shouldBeActive && !plugin.isActive()) {
            lang.logInfo("log.schedule.activating");
            applied = activate(Bukkit.getConsoleSender(), XmasStateChangeEvent.Cause.SCHEDULE);
        } else if (!shouldBeActive && plugin.isActive() && plugin.getConfig().getBoolean("schedule.autoDeactivate", true)) {
            lang.logInfo("log.schedule.deactivating");
            applied = deactivate(Bukkit.getConsoleSender(), XmasStateChangeEvent.Cause.SCHEDULE);
        }
        if (applied) saveScheduleState(shouldBeActive);
    }

    // ---------------------------------------------------------- features

    /**
     * Enables or disables a feature at runtime (persisted to config.yml).
     *
     * @return {@code false} for an unknown feature name
     */
    public boolean setFeatureEnabled(String feature, boolean enabled) {
        String key = FEATURES.get(feature.toLowerCase());
        if (key == null) return false;
        plugin.getConfig().set(key, enabled);
        plugin.saveConfig();
        if (plugin.isActive()) {
            plugin.stopFeatures();
            plugin.startFeatures();
        }
        return true;
    }

    public boolean isFeatureEnabled(String feature) {
        String key = FEATURES.get(feature.toLowerCase());
        return key != null && plugin.getConfig().getBoolean(key, true);
    }
}
