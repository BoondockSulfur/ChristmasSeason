package de.boondocksulfur.christmas.cmd;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.manager.BiomeCompare;
import de.boondocksulfur.christmas.manager.BiomeSnapshotBackup;
import de.boondocksulfur.christmas.manager.BiomeSnapshotDatabase;
import de.boondocksulfur.christmas.manager.BiomeSnowManager;
import de.boondocksulfur.christmas.util.LanguageManager;
import de.boondocksulfur.christmas.util.FoliaSchedulerHelper;
import de.boondocksulfur.christmas.util.Registries;
import de.boondocksulfur.christmas.util.UpdateChecker;

import java.io.File;
import java.util.Map;

/** {@code /xmas} - administration of the event. All texts come from the language files. */
public class XmasCommand implements CommandExecutor {

    private final ChristmasSeason plugin;
    private final LanguageManager lang;
    private final FoliaSchedulerHelper scheduler;

    public XmasCommand(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.scheduler = plugin.getFoliaScheduler();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("xmas.admin")) {
            lang.send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            lang.send(sender, "command.usage");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "on" -> handleOn(sender);
            case "off" -> handleOff(sender);
            case "status" -> handleStatus(sender);
            case "reload" -> handleReload(sender);
            case "biome" -> handleBiome(sender, args);
            case "debug" -> handleDebug(sender, args);
            case "storm" -> handleStorm(sender, args);
            case "update" -> handleUpdate(sender, args);
            case "backup" -> handleBackup(sender, args);
            case "feature" -> handleFeature(sender, args);
            case "stats" -> handleStats(sender, args);
            default -> lang.send(sender, "command.usage");
        }
        return true;
    }

    // ------------------------------------------------------------ on / off

    private void handleOn(CommandSender sender) {
        plugin.getEventController().activate(sender, de.boondocksulfur.christmas.api.XmasStateChangeEvent.Cause.COMMAND);
    }

    private void handleOff(CommandSender sender) {
        plugin.getEventController().deactivate(sender, de.boondocksulfur.christmas.api.XmasStateChangeEvent.Cause.COMMAND);
    }

    // ------------------------------------------------------- status / reload

    private void handleStatus(CommandSender sender) {
        World w = Bukkit.getWorld(plugin.getPrimarySnowWorld());
        boolean storm = (w != null && w.hasStorm());
        String activeStr = plugin.isActive() ? lang.get("command.status.active") : lang.get("command.status.inactive");
        String stormStr = storm ? lang.get("command.status.active") : lang.get("command.status.inactive");
        lang.send(sender, "command.status.message", activeStr, stormStr);
        lang.send(sender, "command.status.worlds", String.join(", ", plugin.getSnowWorldNames()));

        if (plugin.getRegionIntegration() != null) {
            lang.send(sender, "command.status.region", plugin.getRegionIntegration().getStatus());
        }
        lang.send(sender, "command.status.objects",
                plugin.getGiftManager().getTrackedCount(),
                plugin.getDecorationManager().getTrackedCount(),
                plugin.getWichtelManager().getTrackedCount(),
                plugin.getSnowmanManager().getTrackedCount());
        int chunks = plugin.getBiomeSnowManager().getSnapshotChunkCount();
        if (chunks >= 0) lang.send(sender, "command.status.snapshot", chunks);
        if (plugin.getBiomeSnowManager().isRestoring()) {
            lang.send(sender, "command.status.restoring", plugin.getBiomeSnowManager().getRestoreProgress());
        }
        if (plugin.getBiomeSnowManager().isConvertRunning()) {
            lang.send(sender, "command.status.converting", plugin.getBiomeSnowManager().getConvertProgress());
        }
        if (plugin.getConfig().getBoolean("schedule.enabled", false)) {
            de.boondocksulfur.christmas.manager.EventController ec = plugin.getEventController();
            lang.send(sender, "command.status.schedule", ec.daysUntilStart(), ec.daysLeft());
        }
        lang.send(sender, "command.status.gifts-opened", plugin.getStatsManager().getTotalGiftsOpened());
    }

    private void handleReload(CommandSender sender) {
        try {
            plugin.reloadAll();
            lang.send(sender, "command.reload.success");
        } catch (Throwable t) {
            lang.logSevere("log.reload-error", t.getMessage());
            t.printStackTrace();
            lang.send(sender, "command.reload.error");
        }
    }

    // ------------------------------------------------------------- biome

    private void handleBiome(CommandSender sender, String[] args) {
        if (args.length < 2) {
            lang.send(sender, "command.biome.usage");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "set" -> handleBiomeSet(sender, args);
            case "restore" -> {
                // Seed based restore was removed (it froze the server); '/xmas off' restores from the snapshot
                lang.send(sender, "command.biome.restore.disabled");
            }
            case "clearsnap" -> {
                if (plugin.isActive()) {
                    lang.send(sender, "command.biome.clearsnap.blocked-active");
                    return;
                }
                if (!plugin.getBackupManager().hasSafeBackup() && plugin.getBackupManager().listTimestampBackups().isEmpty()) {
                    lang.send(sender, "command.biome.clearsnap.no-backup");
                    return;
                }
                plugin.getBiomeSnowManager().clearSnapshot();
                lang.send(sender, "command.biome.clearsnap.success");
            }
            case "status" -> handleBiomeStatus(sender);
            case "compare" -> handleCompare(sender, args);
            case "fix-diff" -> handleFixDiff(sender, args);
            case "convert-all" -> handleConvertAll(sender, args);
            case "info" -> handleBiomeInfo(sender);
            default -> lang.send(sender, "command.biome.usage");
        }
    }

    private void handleBiomeSet(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            lang.send(sender, "command.players-only");
            return;
        }
        if (args.length < 3) {
            lang.send(sender, "command.biome.set.usage");
            return;
        }
        Biome target = Registries.biomeByName(args[2]);
        if (target == null) {
            lang.send(sender, "command.biome.set.unknown-biome");
            return;
        }

        int r = 0;
        if (args.length >= 4) {
            try { r = Math.max(0, Integer.parseInt(args[3])); }
            catch (NumberFormatException ex) { lang.send(sender, "command.biome.set.invalid-radius"); return; }
        }

        int scheduled = plugin.getBiomeSnowManager().setBiomeAroundPlayer(player, target, r);
        if (scheduled < 0) {
            lang.send(sender, "command.biome.set.only-active");
            return;
        }
        lang.send(sender, "command.biome.set.success", scheduled);
    }

    private void handleBiomeStatus(CommandSender sender) {
        BiomeSnowManager m = plugin.getBiomeSnowManager();
        lang.send(sender, "command.biome.status.header");
        try {
            BiomeSnapshotDatabase db = m.getDatabase();
            if (db == null) {
                lang.send(sender, "command.biome.status.db-inactive");
            } else {
                lang.send(sender, "command.biome.status.db-active");
                lang.send(sender, "command.biome.status.db-chunks", db.getChunkCount());
                lang.send(sender, "command.biome.status.db-size", String.format(java.util.Locale.ROOT, "%.2f", db.getDatabaseSize() / (1024.0 * 1024.0)));
            }
        } catch (Exception e) {
            lang.send(sender, "command.biome.status.db-error", e.getMessage());
        }
    }

    private void handleCompare(CommandSender sender, String[] args) {
        if (args.length < 3) {
            lang.send(sender, "command.biome.compare.usage");
            return;
        }
        File backupFile = resolveBackupFile(args[2]);
        if (backupFile == null) {
            lang.send(sender, "log.backup.invalid-id");
            return;
        }

        lang.send(sender, "command.biome.compare.running");
        final String backupId = args[2];

        // Database access on an async thread; chunk work is scheduled per region inside BiomeCompare
        scheduler.runAsync(() -> {
            BiomeCompare.CompareResult result = plugin.getBiomeCompare().compareWithBackup(backupFile);
            scheduler.runGlobalTask(() -> {
                if (result == null) {
                    lang.send(sender, "command.biome.compare.failed");
                    return;
                }
                lang.send(sender, "command.biome.compare.header");
                lang.send(sender, "command.biome.compare.backup", backupFile.getName());
                lang.send(sender, "command.biome.compare.compared", result.totalChunks);
                lang.send(sender, "command.biome.compare.identical", result.identicalChunks, String.format(java.util.Locale.ROOT, "%.1f", result.getMatchPercentage()));
                lang.send(sender, "command.biome.compare.differences", result.differences.size());

                if (!result.differences.isEmpty()) {
                    lang.send(sender, "command.biome.compare.top-header");
                    int shown = Math.min(5, result.differences.size());
                    for (int i = 0; i < shown; i++) {
                        BiomeCompare.ChunkDifference diff = result.differences.get(i);
                        lang.send(sender, "command.biome.compare.top-entry", i + 1, diff.chunkX, diff.chunkZ, diff.differenceCount);
                    }
                    lang.send(sender, "command.biome.compare.fix-hint", backupId);
                }
                lang.send(sender, "command.biome.compare.footer");
            });
        });
    }

    private void handleFixDiff(CommandSender sender, String[] args) {
        if (args.length < 3) {
            lang.send(sender, "command.biome.fix-diff.usage");
            return;
        }
        File backupFile = resolveBackupFile(args[2]);
        if (backupFile == null) {
            lang.send(sender, "log.backup.invalid-id");
            return;
        }

        boolean confirmFix = args.length >= 4 && args[3].equalsIgnoreCase("confirm");
        if (!confirmFix) {
            lang.send(sender, "command.biome.fix-diff.warning");
            lang.send(sender, "command.biome.fix-diff.confirm", args[2]);
            return;
        }

        lang.send(sender, "command.biome.fix-diff.running");
        scheduler.runAsync(() -> {
            int fixed = plugin.getBiomeCompare().fixDifferences(backupFile, null);
            scheduler.runGlobalTask(() -> {
                if (fixed >= 0) {
                    lang.send(sender, "command.biome.fix-diff.done", fixed);
                } else {
                    lang.send(sender, "command.biome.fix-diff.failed");
                }
            });
        });
    }

    /** Explains why a spot is (or is not) snowy: current and original biome, snapshot, exclusion. */
    private void handleBiomeInfo(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            lang.send(sender, "command.players-only");
            return;
        }
        BiomeSnowManager.ColumnInfo info = plugin.getBiomeSnowManager().inspect(player.getLocation());
        String yes = lang.get("command.biome.info.yes"), no = lang.get("command.biome.info.no");
        lang.send(sender, "command.biome.info.header", player.getLocation().getBlockX(), player.getLocation().getBlockY(), player.getLocation().getBlockZ());
        lang.send(sender, "command.biome.info.current", info.current.getKey().toString(),
                info.currentNaturallySnowy ? yes : no, info.currentNaturallyIcy ? yes : no);
        if (info.hasSnapshot) {
            lang.send(sender, "command.biome.info.original", info.original.getKey().toString(),
                    info.originalNaturallySnowy ? yes : no, info.originalNaturallyIcy ? yes : no);
        } else {
            lang.send(sender, "command.biome.info.no-snapshot");
        }
        lang.send(sender, "command.biome.info.flags", info.excluded ? yes : no, info.processed ? yes : no, info.manual ? yes : no);
        lang.send(sender, "command.biome.info.range", info.minY, info.maxY);
    }

    private void handleConvertAll(CommandSender sender, String[] args) {
        BiomeSnowManager m = plugin.getBiomeSnowManager();
        if (args.length >= 3 && args[2].equalsIgnoreCase("cancel")) {
            m.cancelConvertAll();
            lang.send(sender, "command.biome.convert-all.cancelled");
            return;
        }
        if (!plugin.isActive()) {
            lang.send(sender, "command.biome.convert-all.only-active");
            return;
        }
        if (m.isConvertRunning()) {
            lang.send(sender, "command.biome.convert-all.running", m.getConvertProgress());
            return;
        }
        int radius = plugin.getConfig().getInt("biome.convertAll.radiusChunks", 64);
        World world = sender instanceof Player p && plugin.isSnowWorld(p.getWorld()) ? p.getWorld() : Bukkit.getWorld(plugin.getPrimarySnowWorld());
        if (args.length >= 3) {
            try { radius = Math.max(1, Integer.parseInt(args[2])); }
            catch (NumberFormatException e) { lang.send(sender, "command.biome.convert-all.usage"); return; }
        }
        if (args.length >= 4) {
            world = Bukkit.getWorld(args[3]);
        }
        if (world == null || !plugin.isSnowWorld(world)) {
            lang.send(sender, "command.biome.convert-all.not-snow-world");
            return;
        }
        int total = (2 * radius + 1) * (2 * radius + 1);
        boolean confirmed = args.length >= 5 && args[4].equalsIgnoreCase("confirm");
        if (!confirmed) {
            lang.send(sender, "command.biome.convert-all.warning", total, world.getName());
            lang.send(sender, "command.biome.convert-all.confirm", radius, world.getName());
            return;
        }
        final CommandSender feedback = sender;
        if (m.startConvertAll(world, radius, msg -> scheduler.runGlobalTask(() -> {
            feedback.sendMessage(msg);
            if (!(feedback instanceof org.bukkit.command.ConsoleCommandSender)) plugin.getLogger().info(msg);
        }))) {
            lang.send(sender, "command.biome.convert-all.started", total, world.getName());
        } else {
            lang.send(sender, "command.biome.convert-all.only-active");
        }
    }

    // ------------------------------------------------------------- feature / stats

    private void handleFeature(CommandSender sender, String[] args) {
        de.boondocksulfur.christmas.manager.EventController ec = plugin.getEventController();
        if (args.length < 2) {
            StringBuilder sb = new StringBuilder();
            for (String f : new java.util.TreeSet<>(de.boondocksulfur.christmas.manager.EventController.FEATURES.keySet())) {
                sb.append(ec.isFeatureEnabled(f) ? "&a" : "&c").append(f).append("&7, ");
            }
            lang.send(sender, "command.feature.usage");
            sender.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(sb.length() > 2 ? sb.substring(0, sb.length() - 2) : ""));
            return;
        }
        String feature = args[1].toLowerCase();
        if (!de.boondocksulfur.christmas.manager.EventController.FEATURES.containsKey(feature)) {
            lang.send(sender, "command.feature.unknown", feature);
            return;
        }
        if (args.length < 3) {
            lang.send(sender, ec.isFeatureEnabled(feature) ? "command.feature.status-on" : "command.feature.status-off", feature);
            return;
        }
        boolean on = args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("true");
        boolean off = args[2].equalsIgnoreCase("off") || args[2].equalsIgnoreCase("false");
        if (!on && !off) {
            lang.send(sender, "command.feature.usage");
            return;
        }
        ec.setFeatureEnabled(feature, on);
        lang.send(sender, on ? "command.feature.enabled" : "command.feature.disabled", feature);
    }

    private void handleStats(CommandSender sender, String[] args) {
        de.boondocksulfur.christmas.manager.StatsManager stats = plugin.getStatsManager();
        lang.send(sender, "command.stats.header", stats.getTotalGiftsOpened());
        int i = 1;
        for (java.util.Map.Entry<String, Integer> e : stats.getTop(10)) {
            lang.send(sender, "command.stats.entry", i++, e.getKey(), e.getValue());
        }
        if (args.length >= 2) {
            org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
            if (target == null) {
                lang.send(sender, "command.stats.unknown-player", args[1]);
            } else {
                lang.send(sender, "command.stats.player", target.getName(), stats.getGiftsOpened(target.getUniqueId()),
                        plugin.getAdventManager().getClaimedDays(target.getUniqueId()).size());
            }
        } else if (sender instanceof Player p) {
            lang.send(sender, "command.stats.player", p.getName(), stats.getGiftsOpened(p.getUniqueId()),
                    plugin.getAdventManager().getClaimedDays(p.getUniqueId()).size());
        }
    }

    // ------------------------------------------------------------- debug

    private void handleDebug(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("verbose")) {
            boolean newState = !plugin.isVerboseDebugMode();
            plugin.setVerboseDebugMode(newState);
            lang.send(sender, newState ? "command.debug.verbose-on" : "command.debug.verbose-off");
        } else {
            boolean newState = !plugin.isDebugMode();
            plugin.setDebugMode(newState);
            plugin.setVerboseDebugMode(false);
            lang.send(sender, newState ? "command.debug.on" : "command.debug.off");
        }
    }

    // ------------------------------------------------------------- storm

    private void handleStorm(CommandSender sender, String[] args) {
        if (args.length < 2) {
            lang.send(sender, "command.storm.usage");
            return;
        }
        World w = Bukkit.getWorld(plugin.getPrimarySnowWorld());

        switch (args[1].toLowerCase()) {
            case "on" -> {
                plugin.getSnowstormManager().setStorm(true);
                lang.send(sender, "command.storm.on");
            }
            case "off" -> {
                plugin.getSnowstormManager().setStorm(false);
                lang.send(sender, "command.storm.off");
            }
            case "toggle" -> {
                boolean newState = w != null ? !w.hasStorm() : true;
                plugin.getSnowstormManager().setStorm(newState);
                String stateStr = newState ? lang.get("command.status.active") : lang.get("command.status.inactive");
                lang.send(sender, "command.storm.toggle", stateStr);
            }
            case "status" -> {
                boolean storm = (w != null && w.hasStorm());
                String stormStr = storm ? lang.get("command.status.active") : lang.get("command.status.inactive");
                lang.send(sender, "command.storm.status", stormStr);
            }
            case "pulse" -> {
                int sec = 5;
                if (args.length >= 3) {
                    try { sec = Math.max(1, Integer.parseInt(args[2])); }
                    catch (NumberFormatException ex) { lang.send(sender, "command.storm.pulse.invalid-duration"); return; }
                }
                if (w == null) {
                    lang.send(sender, "command.storm.pulse.world-not-found");
                    return;
                }
                plugin.getSnowstormManager().pulse(sec);
                lang.send(sender, "command.storm.pulse.success", sec);
            }
            default -> lang.send(sender, "command.storm.usage");
        }
    }

    // ------------------------------------------------------------- update

    private void handleUpdate(CommandSender sender, String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("check")) {
            lang.send(sender, "command.update.usage");
            return;
        }
        lang.send(sender, "command.update.checking");
        UpdateChecker checker = plugin.getUpdateChecker();

        checker.checkForUpdates().thenAccept(result -> scheduler.runGlobalTask(() -> {
            if (result.isUpdateAvailable()) {
                if (sender instanceof Player player) {
                    checker.sendUpdateNotification(player);
                } else {
                    lang.send(sender, "command.update.available", result.getCurrentVersion(), result.getLatestVersion());
                    lang.send(sender, "command.update.links", checker.getModrinthUrl(), checker.getGitHubUrl());
                }
            } else if (result.getLatestVersion() != null) {
                lang.send(sender, "command.update.up-to-date", result.getCurrentVersion());
            } else {
                lang.send(sender, "command.update.failed");
            }
        }));
    }

    // ------------------------------------------------------------- backup

    private void handleBackup(CommandSender sender, String[] args) {
        if (args.length < 2) {
            lang.send(sender, "command.backup.usage");
            return;
        }
        BiomeSnapshotBackup backup = plugin.getBackupManager();

        switch (args[1].toLowerCase()) {
            case "list" -> {
                Map<String, File> backups = backup.listAllBackups();
                lang.send(sender, "log.backup.list-header");
                if (backups.isEmpty()) {
                    lang.send(sender, "log.backup.list-empty");
                } else {
                    int i = 1;
                    for (Map.Entry<String, File> entry : backups.entrySet()) {
                        lang.send(sender, "log.backup.list-entry", i, entry.getKey(), entry.getValue().length() / 1024);
                        i++;
                    }
                }
                lang.send(sender, "log.backup.list-footer");
            }
            case "restore" -> {
                if (args.length < 3) {
                    lang.send(sender, "log.backup.restore-usage");
                    return;
                }
                File backupFile = resolveBackupFile(args[2]);
                if (backupFile == null) {
                    lang.send(sender, "log.backup.invalid-id");
                    return;
                }
                boolean confirmRestore = args.length >= 4 && args[3].equalsIgnoreCase("confirm");
                if (!confirmRestore) {
                    lang.send(sender, "log.backup.restore-confirm");
                    lang.send(sender, "log.backup.restore-warning");
                    lang.send(sender, "log.backup.restore-command", args[2]);
                    return;
                }
                lang.send(sender, "command.backup.restoring");
                if (backup.restoreBackup(backupFile)) {
                    lang.send(sender, "command.backup.restored");
                } else {
                    lang.send(sender, "command.backup.restore-failed");
                }
            }
            case "create" -> {
                lang.send(sender, "command.backup.creating");
                if (backup.createTimestampBackup()) {
                    lang.send(sender, "command.backup.created");
                } else {
                    lang.send(sender, "command.backup.create-failed");
                }
            }
            case "clear" -> {
                int deleted = backup.clearAllBackups();
                lang.send(sender, "log.backup.cleared", deleted);
            }
            default -> lang.send(sender, "command.backup.usage");
        }
    }

    /**
     * Resolves a backup ID: either the 1-based index from {@code /xmas backup list}
     * or the name (SAFE, EMERGENCY_..., timestamp).
     */
    private File resolveBackupFile(String backupId) {
        Map<String, File> backups = plugin.getBackupManager().listAllBackups();
        try {
            int index = Integer.parseInt(backupId) - 1;
            if (index >= 0 && index < backups.size()) {
                return new java.util.ArrayList<>(backups.values()).get(index);
            }
            return null;
        } catch (NumberFormatException e) {
            return backups.get(backupId);
        }
    }
}
