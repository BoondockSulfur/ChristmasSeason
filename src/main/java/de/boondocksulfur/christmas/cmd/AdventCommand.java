package de.boondocksulfur.christmas.cmd;

import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.util.LanguageManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

/** {@code /advent [status|<day>]} - advent calendar for players. */
public class AdventCommand implements CommandExecutor, TabCompleter {

    private final ChristmasSeason plugin;
    private final LanguageManager lang;

    public AdventCommand(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            lang.send(sender, "command.players-only");
            return true;
        }
        if (!player.hasPermission("xmas.advent")) {
            lang.send(sender, "no-permission");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("status")) {
            plugin.getAdventManager().sendStatus(player);
            return true;
        }
        int day = 0;
        if (args.length >= 1) {
            try { day = Integer.parseInt(args[0]); }
            catch (NumberFormatException e) { lang.send(sender, "advent.usage"); return true; }
        }
        final int finalDay = day;
        // Inventory and world access must happen on the player's thread (Folia)
        plugin.getFoliaScheduler().runForEntity(player, () -> plugin.getAdventManager().claim(player, finalDay));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            String p = args[0].toLowerCase();
            return List.of("status").stream().filter(s -> s.startsWith(p)).toList();
        }
        return List.of();
    }
}
