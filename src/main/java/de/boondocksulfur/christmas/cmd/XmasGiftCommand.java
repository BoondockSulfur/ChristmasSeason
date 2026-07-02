package de.boondocksulfur.christmas.cmd;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.util.LanguageManager;

public class XmasGiftCommand implements CommandExecutor {

    private final ChristmasSeason plugin;
    private final LanguageManager lang;

    public XmasGiftCommand(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("xmas.admin")) {
            sender.sendMessage(lang.get("no-permission"));
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cOnly available in-game."); // Hinweis: Wird selten gebraucht
            return true;
        }

        Location loc = p.getLocation();
        plugin.getGiftManager().spawnGift(loc.getWorld(), loc);
        sender.sendMessage(lang.get("gift.spawned"));
        return true;
    }
}
