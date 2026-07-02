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
                plugin.getConfig().set("active", true);
                plugin.saveConfig();
                plugin.startFeatures();
                sender.sendMessage(lang.get("command.on.success"));
            }

            case "off" -> {
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
                            target = org.bukkit.Registry.BIOME.get(org.bukkit.NamespacedKey.minecraft(args[2].toLowerCase()));
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

            default -> sender.sendMessage(lang.get("command.usage"));
        }

        return true;
    }
}
