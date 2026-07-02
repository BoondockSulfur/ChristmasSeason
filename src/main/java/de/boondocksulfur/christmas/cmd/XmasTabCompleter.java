package de.boondocksulfur.christmas.cmd;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;

/**
 * Tab-Completion für /xmas.
 * Der deaktivierte Unterbefehl 'biome restore' wird bewusst nicht vorgeschlagen.
 */
public class XmasTabCompleter implements TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("on", "off", "status", "reload", "biome", "storm", "debug");
    private static final List<String> BIOME_SUB   = List.of("set", "clearsnap", "status");
    private static final List<String> STORM_SUB   = List.of("on", "off", "toggle", "status", "pulse");
    private static final List<String> DEBUG_SUB   = List.of("verbose");
    private static final List<String> PULSE_SECS  = List.of("5", "10", "30", "60");
    private static final List<String> RADII       = List.of("1", "2", "3", "5");

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission("xmas.admin")) return List.of();

        if (args.length == 1) return filter(SUBCOMMANDS, args[0]);

        if (args.length == 2) {
            return switch (args[0].toLowerCase()) {
                case "biome" -> filter(BIOME_SUB, args[1]);
                case "storm" -> filter(STORM_SUB, args[1]);
                case "debug" -> filter(DEBUG_SUB, args[1]);
                default -> List.of();
            };
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("biome") && args[1].equalsIgnoreCase("set")) {
            List<String> biomes = org.bukkit.Registry.BIOME.stream()
                    .map(b -> b.getKey().getKey())
                    .sorted()
                    .toList();
            return filter(biomes, args[2]);
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("storm") && args[1].equalsIgnoreCase("pulse")) {
            return filter(PULSE_SECS, args[2]);
        }

        if (args.length == 4 && args[0].equalsIgnoreCase("biome") && args[1].equalsIgnoreCase("set")) {
            return filter(RADII, args[3]);
        }

        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase();
        return options.stream().filter(s -> s.toLowerCase().startsWith(p)).toList();
    }
}
