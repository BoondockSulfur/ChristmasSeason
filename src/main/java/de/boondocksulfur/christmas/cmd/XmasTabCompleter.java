package de.boondocksulfur.christmas.cmd;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import de.boondocksulfur.christmas.ChristmasSeason;

import java.util.ArrayList;
import java.util.List;

/**
 * Tab completion for {@code /xmas}.
 * The disabled sub-command {@code biome restore} is deliberately not suggested.
 */
public class XmasTabCompleter implements TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("on", "off", "status", "reload", "biome", "storm", "backup", "update", "debug", "feature", "stats");
    private static final List<String> BIOME_SUB   = List.of("set", "clearsnap", "status", "compare", "fix-diff", "convert-all", "info");
    private static final List<String> STORM_SUB   = List.of("on", "off", "toggle", "status", "pulse");
    private static final List<String> BACKUP_SUB  = List.of("list", "restore", "create", "clear");
    private static final List<String> UPDATE_SUB  = List.of("check");
    private static final List<String> DEBUG_SUB   = List.of("verbose");
    private static final List<String> PULSE_SECS  = List.of("5", "10", "30", "60");
    private static final List<String> RADII       = List.of("1", "2", "3", "5");

    private final ChristmasSeason plugin;

    public XmasTabCompleter(ChristmasSeason plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission("xmas.admin")) return List.of();

        if (args.length == 1) return filter(SUBCOMMANDS, args[0]);

        if (args.length == 2) {
            return switch (args[0].toLowerCase()) {
                case "biome" -> filter(BIOME_SUB, args[1]);
                case "storm" -> filter(STORM_SUB, args[1]);
                case "backup" -> filter(BACKUP_SUB, args[1]);
                case "update" -> filter(UPDATE_SUB, args[1]);
                case "debug" -> filter(DEBUG_SUB, args[1]);
                case "feature" -> filter(new ArrayList<>(new java.util.TreeSet<>(de.boondocksulfur.christmas.manager.EventController.FEATURES.keySet())), args[1]);
                case "stats" -> filter(org.bukkit.Bukkit.getOnlinePlayers().stream().map(org.bukkit.entity.Player::getName).toList(), args[1]);
                default -> List.of();
            };
        }

        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("biome")) {
                switch (args[1].toLowerCase()) {
                    case "set" -> {
                        List<String> biomes = de.boondocksulfur.christmas.util.Registries.biomes().stream()
                                .map(b -> b.getKey().getNamespace().equals("minecraft") ? b.getKey().getKey() : b.getKey().toString())
                                .sorted()
                                .toList();
                        return filter(biomes, args[2]);
                    }
                    case "compare", "fix-diff" -> {
                        return filter(backupIds(), args[2]);
                    }
                    case "convert-all" -> {
                        return filter(List.of("16", "32", "64", "128", "cancel"), args[2]);
                    }
                }
            }
            if (args[0].equalsIgnoreCase("backup") && args[1].equalsIgnoreCase("restore")) {
                return filter(backupIds(), args[2]);
            }
            if (args[0].equalsIgnoreCase("storm") && args[1].equalsIgnoreCase("pulse")) {
                return filter(PULSE_SECS, args[2]);
            }
            if (args[0].equalsIgnoreCase("feature")) {
                return filter(List.of("on", "off"), args[2]);
            }
        }

        if (args.length == 4) {
            if (args[0].equalsIgnoreCase("biome") && args[1].equalsIgnoreCase("set")) {
                return filter(RADII, args[3]);
            }
            if (args[0].equalsIgnoreCase("biome") && args[1].equalsIgnoreCase("fix-diff")) {
                return filter(List.of("confirm"), args[3]);
            }
            if (args[0].equalsIgnoreCase("biome") && args[1].equalsIgnoreCase("convert-all")) {
                return filter(plugin.getSnowWorldNames(), args[3]);
            }
            if (args[0].equalsIgnoreCase("backup") && args[1].equalsIgnoreCase("restore")) {
                return filter(List.of("confirm"), args[3]);
            }
        }

        if (args.length == 5 && args[0].equalsIgnoreCase("biome") && args[1].equalsIgnoreCase("convert-all")) {
            return filter(List.of("confirm"), args[4]);
        }

        return List.of();
    }

    private List<String> backupIds() {
        return new ArrayList<>(plugin.getBackupManager().listAllBackups().keySet());
    }

    private List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase();
        return options.stream().filter(s -> s.toLowerCase().startsWith(p)).toList();
    }
}
