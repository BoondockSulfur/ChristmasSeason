package de.boondocksulfur.christmas.cmd;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.manager.BiomeSnowManager;
import de.boondocksulfur.christmas.util.LanguageManager;
import de.boondocksulfur.christmas.util.FoliaSchedulerHelper;

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
            sender.sendMessage(lang.get("no-permission"));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(lang.get("command.usage"));
            return true;
        }

        switch (args[0].toLowerCase()) {

            case "on" -> {
                // GUARD: Kein /xmas on während der Restore noch läuft
                if (plugin.getBiomeSnowManager().isRestoring()) {
                    sender.sendMessage("§c§lFehler: Restore läuft noch!");
                    sender.sendMessage("§cBitte warte bis der Biome-Restore abgeschlossen ist.");
                    return true;
                }

                // GUARD: Hinweis wenn bereits aktiv (Schutz vor doppeltem /xmas on)
                if (plugin.isActive()) {
                    sender.sendMessage("§e§lHinweis: ChristmasSeason ist bereits aktiv!");
                    sender.sendMessage("§7Features werden neu gestartet. Bestehende Snapshots bleiben erhalten.");
                }

                // SAFE-BACKUP: Erstelle Backup BEVOR Chunks geändert werden!
                if (plugin.getConfig().getBoolean("biome.enableSnapshot", true)) {
                    boolean backupOk = plugin.getBackupManager().createSafeBackup();
                    if (!backupOk && plugin.getBackupManager().hasDatabaseFile()) {
                        sender.sendMessage("§c§lWARNUNG: SAFE-Backup konnte nicht erstellt werden!");
                        sender.sendMessage("§cBiome-Daten könnten bei Problemen verloren gehen.");
                        sender.sendMessage("§7Prüfe Speicherplatz und Berechtigungen im Backup-Ordner.");
                    }
                } else {
                    sender.sendMessage("§c§lWARNUNG: Snapshot-System ist deaktiviert! (enableSnapshot: false)");
                    sender.sendMessage("§cBiome werden geändert OHNE Backup → kein automatisches Restore möglich!");
                }

                plugin.getConfig().set("active", true);
                plugin.saveConfig();
                plugin.startFeatures();
                sender.sendMessage(lang.get("command.on.success"));
            }

            case "off" -> {
                // TIMESTAMP-BACKUP: Erstelle Backup BEVOR Restore startet!
                if (plugin.getConfig().getBoolean("biome.enableSnapshot", true)) {
                    boolean backupOk = plugin.getBackupManager().createTimestampBackup();
                    if (!backupOk && plugin.getBackupManager().hasDatabaseFile()) {
                        sender.sendMessage("§c§lWARNUNG: Timestamp-Backup fehlgeschlagen!");
                        sender.sendMessage("§cRestore wird trotzdem durchgeführt.");
                    }
                }

                plugin.getConfig().set("active", false);
                plugin.saveConfig();
                plugin.getSnowstormManager().setStorm(false);

                // WICHTIG: Cleanup VOR stopFeatures(), damit die Tracker noch gefüllt sind!
                sender.sendMessage(lang.get("command.off.cleanup"));
                plugin.getDecorationManager().cleanup();
                plugin.getGiftManager().cleanup();
                plugin.getWichtelManager().cleanup();
                plugin.getSnowmanManager().cleanup();

                // Features stoppen OHNE Biome-Datenbank zu schließen (brauchen wir für Restore!)
                plugin.debug("Stoppe Features (DB bleibt offen für Restore)...");
                plugin.stopFeatures(false);  // DB NICHT schließen!

                // Biome asynchron & budgetiert zurücksetzen (schließt DB am Ende selbst)
                int perTick = Math.max(1, plugin.getConfig().getInt("biome.restore.perTick", 4));
                plugin.getBiomeSnowManager().restoreALLAsync(perTick);

                // optional Sonne erzwingen (WICHTIG: über Global Scheduler für Folia!)
                String wn = plugin.getConfig().getString("snowWorld", "world");
                World w = Bukkit.getWorld(wn);
                if (w != null) {
                    scheduler.runGlobalTask(() -> {
                        w.setStorm(false);
                        w.setThundering(false);
                        w.setWeatherDuration(12000);
                    });
                }

                sender.sendMessage(lang.get("command.off.success"));
            }

            case "status" -> {
                boolean active = plugin.isActive();
                String wn = plugin.getConfig().getString("snowWorld", "world");
                World w = Bukkit.getWorld(wn);
                boolean storm = (w != null && w.hasStorm());
                String activeStr = active ? lang.get("command.status.active") : lang.get("command.status.inactive");
                String stormStr = storm ? lang.get("command.status.active") : lang.get("command.status.inactive");
                sender.sendMessage(lang.getMessage("command.status.message", activeStr, stormStr));

                // Region Integration Status
                if (plugin.getRegionIntegration() != null) {
                    sender.sendMessage("§7Region-Schutz: §f" + plugin.getRegionIntegration().getStatus());
                }
                // Restore-Fortschritt anzeigen
                if (plugin.getBiomeSnowManager().isRestoring()) {
                    sender.sendMessage("§eBiome-Restore läuft gerade...");
                }
            }

            case "reload" -> {
                // CONSISTENCY FIX: ALLE Manager neu starten, nicht nur Biome+Snowstorm -
                // sonst behalten Gifts/Wichtel/Snowmen/Decoration alte Settings bis zum
                // nächsten off/on. reloadAll() = reloadConfig + lang.reload + stop + start.
                try {
                    plugin.reloadAll();
                } catch (Throwable t) {
                    plugin.getLogger().severe("Fehler beim Reload: " + t.getMessage());
                    t.printStackTrace();
                }
                sender.sendMessage(lang.get("command.reload.success"));
            }

            case "biome" -> {
                if (args.length < 2) {
                    sender.sendMessage(lang.get("command.biome.usage"));
                    return true;
                }
                switch (args[1].toLowerCase()) {
                    case "set" -> {
                        if (args.length < 3) { sender.sendMessage(lang.get("command.biome.set.usage")); return true; }
                        org.bukkit.block.Biome target;
                        try {
                            // Use Registry instead of deprecated valueOf
                            target = de.boondocksulfur.christmas.util.Registries.biomes().get(org.bukkit.NamespacedKey.minecraft(args[2].toLowerCase()));
                            if (target == null) throw new IllegalArgumentException();
                        }
                        catch (Exception ex) { sender.sendMessage(lang.get("command.biome.set.unknown-biome")); return true; }

                        int r = 0;
                        if (args.length >= 4) {
                            try { r = Math.max(0, Integer.parseInt(args[3])); }
                            catch (NumberFormatException ex) { sender.sendMessage(lang.get("command.biome.set.invalid-radius")); return true; }
                        }
                        int changed = plugin.getBiomeSnowManager().setBiomeAroundPlayer(sender, target, r);
                        sender.sendMessage(lang.getMessage("command.biome.set.success", changed));
                    }
                    case "restore" -> {
                        // Seed-Restore wurde entfernt (Server-Freeze durch Referenzwelt-Laden).
                        // Restore läuft ausschließlich über '/xmas off' (SQLite-Snapshot).
                        sender.sendMessage("§c§lWARNUNG: Dieser Befehl wurde deaktiviert!");
                        sender.sendMessage("§7Er kann den Server zum Absturz bringen.");
                        sender.sendMessage("§7Verwende stattdessen '/xmas off' zum Zurücksetzen.");
                    }
                    case "clearsnap" -> {
                        // SCHUTZ: Löschen während Biome modifiziert sind = permanenter Datenverlust!
                        if (plugin.isActive()) {
                            sender.sendMessage("§c§lFEHLER: ChristmasSeason ist aktiv!");
                            sender.sendMessage("§cDas Löschen der Snapshot-Datenbank während Biome modifiziert sind");
                            sender.sendMessage("§cwürde zu permanentem Datenverlust führen!");
                            sender.sendMessage("§7Führe zuerst '/xmas off' aus, dann '/xmas biome clearsnap'.");
                            return true;
                        }
                        // Prüfe ob Backup existiert, bevor Snapshot gelöscht wird
                        if (!plugin.getBackupManager().hasSafeBackup() && plugin.getBackupManager().listTimestampBackups().isEmpty()) {
                            sender.sendMessage("§c§lWARNUNG: Kein Backup vorhanden!");
                            sender.sendMessage("§cWenn du den Snapshot löschst, gibt es keine Möglichkeit zur Wiederherstellung.");
                            sender.sendMessage("§7Erstelle erst ein Backup: '/xmas backup create'");
                            return true;
                        }
                        plugin.getBiomeSnowManager().clearSnapshot();
                        sender.sendMessage(lang.get("command.biome.clearsnap.success"));
                    }
                    // "migrate" command entfernt - nicht mehr benötigt mit neuem 3D-Format
                    case "status" -> {
                        BiomeSnowManager m = plugin.getBiomeSnowManager();
                        sender.sendMessage(lang.getMessage("command.biome.status.message", m.getClass().getSimpleName()));
                        // Zeige Datenbank-Statistiken
                        sender.sendMessage("§7═══ Snapshot Datenbank ═══");
                        try {
                            de.boondocksulfur.christmas.manager.BiomeSnapshotDatabase db = m.getDatabase();
                            if (db == null) {
                                sender.sendMessage("§c✗ Datenbank: NICHT AKTIV");
                                sender.sendMessage("§7  (enableSnapshot: false in config.yml)");
                            } else {
                                int chunks = db.getChunkCount();
                                long bytes = db.getDatabaseSize();
                                double mb = bytes / (1024.0 * 1024.0);
                                sender.sendMessage("§a✓ Datenbank: AKTIV");
                                sender.sendMessage("§7  Chunks: §f" + chunks);
                                sender.sendMessage("§7  Größe: §f" + String.format("%.2f MB", mb));
                            }
                        } catch (Exception e) {
                            sender.sendMessage("§c✗ Fehler beim Abrufen: " + e.getMessage());
                        }
                    }
                    case "compare" -> {
                        if (args.length < 3) {
                            sender.sendMessage("§b/xmas biome compare §e<backup-ID>");
                            sender.sendMessage("§7Vergleicht aktuelle Biome mit Backup");
                            return true;
                        }

                        java.io.File backupFile = resolveBackupFile(args[2]);
                        if (backupFile == null) {
                            sender.sendMessage("§cUngültige Backup-ID. Verwende §f/xmas backup list");
                            return true;
                        }

                        sender.sendMessage("§7Vergleiche Biome mit Backup...");
                        sender.sendMessage("§7Dies kann einige Sekunden dauern!");

                        final java.io.File finalBackupFile = backupFile;
                        final String backupId = args[2];
                        scheduler.runAsync(() -> {
                            de.boondocksulfur.christmas.manager.BiomeCompare.CompareResult result =
                                plugin.getBiomeCompare().compareWithBackup(finalBackupFile);

                            scheduler.runGlobalTask(() -> {
                                if (result == null) {
                                    sender.sendMessage("§c✗ Vergleich fehlgeschlagen! Siehe Console.");
                                    return;
                                }

                                sender.sendMessage("§6═══ VERGLEICH ERGEBNIS ═══");
                                sender.sendMessage("§7Backup: §f" + finalBackupFile.getName());
                                sender.sendMessage("§7Chunks verglichen: §f" + result.totalChunks);
                                sender.sendMessage("§7Identisch: §a" + result.identicalChunks + " §7(" + String.format("%.1f%%", result.getMatchPercentage()) + ")");
                                sender.sendMessage("§7Unterschiede: §c" + result.differences.size());

                                if (!result.differences.isEmpty()) {
                                    sender.sendMessage("§7Top 5 Chunks mit Unterschieden:");
                                    int shown = Math.min(5, result.differences.size());
                                    for (int i = 0; i < shown; i++) {
                                        de.boondocksulfur.christmas.manager.BiomeCompare.ChunkDifference diff = result.differences.get(i);
                                        sender.sendMessage("§7  " + (i + 1) + ". §fChunk[" + diff.chunkX + ", " + diff.chunkZ + "] §7- §c" + diff.differenceCount + " §7Änderungen");
                                    }
                                    sender.sendMessage("§eKorrektur: §f/xmas biome fix-diff " + backupId);
                                }
                                sender.sendMessage("§6═══════════════════════════");
                            });
                        });
                    }

                    case "fix-diff" -> {
                        if (args.length < 3) {
                            sender.sendMessage("§b/xmas biome fix-diff §e<backup-ID> [confirm]");
                            sender.sendMessage("§7Korrigiert Unterschiede mit Backup");
                            return true;
                        }

                        java.io.File backupFile = resolveBackupFile(args[2]);
                        if (backupFile == null) {
                            sender.sendMessage("§cUngültige Backup-ID. Verwende §f/xmas backup list");
                            return true;
                        }

                        boolean confirmFix = args.length >= 4 && args[3].equalsIgnoreCase("confirm");
                        if (!confirmFix) {
                            sender.sendMessage("§c§lWARNUNG:");
                            sender.sendMessage("§7Dies wird alle unterschiedlichen Chunks aus dem Backup wiederherstellen!");
                            sender.sendMessage("§7Aktuelle Biome werden überschrieben.");
                            sender.sendMessage("§eBestätigung: §f/xmas biome fix-diff " + args[2] + " confirm");
                            return true;
                        }

                        sender.sendMessage("§7Korrigiere Unterschiede...");
                        sender.sendMessage("§7Dies kann einige Minuten dauern!");

                        final java.io.File finalBackupFile = backupFile;
                        scheduler.runAsync(() -> {
                            int fixed = plugin.getBiomeCompare().fixDifferences(finalBackupFile, null);
                            scheduler.runGlobalTask(() -> {
                                if (fixed > 0) {
                                    sender.sendMessage("§a✓ Korrektur abgeschlossen!");
                                    sender.sendMessage("§7Wiederhergestellt: §f" + fixed + " §7Chunks");
                                } else {
                                    sender.sendMessage("§c✗ Korrektur fehlgeschlagen! Siehe Console.");
                                }
                            });
                        });
                    }

                    default -> sender.sendMessage(lang.get("command.biome.usage"));
                }
            }

            case "debug" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("verbose")) {
                    // Toggle Verbose Debug
                    boolean newState = !plugin.isVerboseDebugMode();
                    plugin.setVerboseDebugMode(newState);
                    if (newState) {
                        sender.sendMessage("§a✓ Verbose Debug-Modus aktiviert");
                        sender.sendMessage("§7Sehr ausführliche Logs (inkl. Biome-Snapshot Details) sind jetzt aktiv.");
                    } else {
                        sender.sendMessage("§c✗ Verbose Debug-Modus deaktiviert");
                        sender.sendMessage("§7Normale Debug-Logs bleiben aktiv.");
                    }
                } else {
                    // Toggle Normal Debug
                    boolean newState = !plugin.isDebugMode();
                    plugin.setDebugMode(newState);
                    plugin.setVerboseDebugMode(false); // Verbose ausschalten wenn normaler Debug getoggelt wird
                    if (newState) {
                        sender.sendMessage("§a✓ Debug-Modus aktiviert");
                        sender.sendMessage("§7Ausführliche Logs sind jetzt aktiv.");
                        sender.sendMessage("§7Tipp: Nutze §f/xmas debug verbose§7 für noch mehr Details.");
                    } else {
                        sender.sendMessage("§c✗ Debug-Modus deaktiviert");
                        sender.sendMessage("§7Normale Logs wiederhergestellt.");
                    }
                }
            }

            case "storm" -> {
                if (args.length < 2) {
                    sender.sendMessage(lang.get("command.storm.usage"));
                    return true;
                }
                String wn = plugin.getConfig().getString("snowWorld", "world");
                World w = Bukkit.getWorld(wn);

                switch (args[1].toLowerCase()) {
                    case "on" -> {
                        plugin.getSnowstormManager().setStorm(true);
                        // WICHTIG: World-Operationen über Global Scheduler für Folia!
                        if (w != null) {
                            scheduler.runGlobalTask(() -> {
                                w.setStorm(true);
                                w.setThundering(false);
                            });
                        }
                        sender.sendMessage(lang.get("command.storm.on"));
                    }
                    case "off" -> {
                        plugin.getSnowstormManager().setStorm(false);
                        // WICHTIG: World-Operationen über Global Scheduler für Folia!
                        if (w != null) {
                            scheduler.runGlobalTask(() -> {
                                w.setStorm(false);
                                w.setThundering(false);
                            });
                        }
                        sender.sendMessage(lang.get("command.storm.off"));
                    }
                    case "toggle" -> {
                        boolean newState;
                        if (w != null) {
                            newState = !w.hasStorm();
                            // WICHTIG: World-Operationen über Global Scheduler für Folia!
                            boolean finalNewState = newState;
                            scheduler.runGlobalTask(() -> {
                                w.setStorm(finalNewState);
                                w.setThundering(false);
                            });
                        } else {
                            newState = true;
                        }
                        plugin.getSnowstormManager().setStorm(newState);
                        String stateStr = newState ? lang.get("command.status.active") : lang.get("command.status.inactive");
                        sender.sendMessage(lang.getMessage("command.storm.toggle", stateStr));
                    }
                    case "status" -> {
                        boolean storm = (w != null && w.hasStorm());
                        String stormStr = storm ? lang.get("command.status.active") : lang.get("command.status.inactive");
                        sender.sendMessage(lang.getMessage("command.storm.status", stormStr));
                    }
                    case "pulse" -> {
                        int sec = 5;
                        if (args.length >= 3) {
                            try { sec = Math.max(1, Integer.parseInt(args[2])); }
                            catch (NumberFormatException ex) { sender.sendMessage(lang.get("command.storm.pulse.invalid-duration")); return true; }
                        }
                        if (w != null) {
                            plugin.getSnowstormManager().setStorm(true);
                            // WICHTIG: World-Operationen über Global Scheduler für Folia!
                            scheduler.runGlobalTask(() -> {
                                w.setStorm(true);
                                w.setThundering(false);
                            });
                            scheduler.runGlobalTaskLater(() -> {
                                plugin.getSnowstormManager().setStorm(false);
                                // Diese sind OK weil bereits im Global Scheduler Kontext
                                w.setStorm(false);
                                w.setThundering(false);
                            }, sec * 20L);
                            sender.sendMessage(lang.getMessage("command.storm.pulse.success", sec));
                        } else {
                            sender.sendMessage(lang.get("command.storm.pulse.world-not-found"));
                        }
                    }
                    default -> sender.sendMessage(lang.get("command.storm.usage"));
                }
            }

            case "update" -> {
                if (args.length < 2 || !args[1].equalsIgnoreCase("check")) {
                    sender.sendMessage("§b/xmas update §7<check>");
                    return true;
                }

                sender.sendMessage("§7Prüfe auf Updates...");
                de.boondocksulfur.christmas.util.UpdateChecker checker = plugin.getUpdateChecker();

                checker.checkForUpdates().thenAccept(result -> {
                    scheduler.runGlobalTask(() -> {
                        if (result.isUpdateAvailable()) {
                            sender.sendMessage("§a§lUpdate verfügbar!");
                            sender.sendMessage("§7Aktuelle Version: §c" + result.getCurrentVersion());
                            sender.sendMessage("§7Neueste Version:  §a" + result.getLatestVersion());
                            sender.sendMessage("§b  ▸ Modrinth: §f" + checker.getModrinthUrl());
                            sender.sendMessage("§b  ▸ GitHub:   §f" + checker.getGitHubUrl());
                        } else if (result.getLatestVersion() != null) {
                            sender.sendMessage("§a✓ Du verwendest die neueste Version!");
                            sender.sendMessage("§7Version: §f" + result.getCurrentVersion());
                        } else {
                            sender.sendMessage("§c✗ Update-Check fehlgeschlagen!");
                            sender.sendMessage("§7Keine Verbindung zu Modrinth oder GitHub.");
                        }
                    });
                });
            }

            case "backup" -> {
                if (args.length < 2) {
                    sender.sendMessage("§b/xmas backup §7<list|restore|create|clear>");
                    return true;
                }

                de.boondocksulfur.christmas.manager.BiomeSnapshotBackup backup = plugin.getBackupManager();

                switch (args[1].toLowerCase()) {
                    case "list" -> {
                        java.util.Map<String, java.io.File> backups = backup.listAllBackups();
                        sender.sendMessage(lang.get("log.backup.list-header"));
                        if (backups.isEmpty()) {
                            sender.sendMessage(lang.get("log.backup.list-empty"));
                        } else {
                            int i = 1;
                            for (java.util.Map.Entry<String, java.io.File> entry : backups.entrySet()) {
                                sender.sendMessage(lang.getMessage("log.backup.list-entry", i, entry.getKey(), entry.getValue().length() / 1024));
                                i++;
                            }
                        }
                        sender.sendMessage(lang.get("log.backup.list-footer"));
                    }

                    case "restore" -> {
                        if (args.length < 3) {
                            sender.sendMessage(lang.get("log.backup.restore-usage"));
                            return true;
                        }

                        java.io.File backupFile = resolveBackupFile(args[2]);
                        if (backupFile == null) {
                            sender.sendMessage(lang.get("log.backup.invalid-id"));
                            return true;
                        }

                        boolean confirmRestore = args.length >= 4 && args[3].equalsIgnoreCase("confirm");
                        if (!confirmRestore) {
                            sender.sendMessage(lang.get("log.backup.restore-confirm"));
                            sender.sendMessage(lang.get("log.backup.restore-warning"));
                            sender.sendMessage(lang.getMessage("log.backup.restore-command", args[2]));
                            return true;
                        }

                        sender.sendMessage("§7Restore läuft...");
                        if (backup.restoreBackup(backupFile)) {
                            sender.sendMessage("§a✓ Backup wiederhergestellt!");
                        } else {
                            sender.sendMessage("§c✗ Fehler beim Restore! Siehe Console.");
                        }
                    }

                    case "create" -> {
                        sender.sendMessage("§7Erstelle manuelles Backup...");
                        if (backup.createTimestampBackup()) {
                            sender.sendMessage("§a✓ Backup erstellt!");
                        } else {
                            sender.sendMessage("§c✗ Fehler beim Erstellen! Siehe Console.");
                        }
                    }

                    case "clear" -> {
                        sender.sendMessage("§7Lösche alle Timestamp-Backups...");
                        int deleted = backup.clearAllBackups();
                        sender.sendMessage(lang.getMessage("log.backup.cleared", deleted));
                    }

                    default -> sender.sendMessage("§b/xmas backup §7<list|restore|create|clear>");
                }
            }

            default -> sender.sendMessage(lang.get("command.usage"));
        }

        return true;
    }

    /**
     * Löst eine Backup-ID auf: entweder 1-basierter Index aus '/xmas backup list'
     * oder Name (SAFE, EMERGENCY_..., Timestamp)
     */
    private java.io.File resolveBackupFile(String backupId) {
        java.util.Map<String, java.io.File> backups = plugin.getBackupManager().listAllBackups();
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
