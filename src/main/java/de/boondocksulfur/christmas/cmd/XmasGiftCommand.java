package de.boondocksulfur.christmas.cmd;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.util.LanguageManager;

/** {@code /xmasgift} - spawns a gift chest at the player's position. */
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
            lang.send(sender, "no-permission");
            return true;
        }
        if (!(sender instanceof Player p)) {
            lang.send(sender, "command.players-only");
            return true;
        }

        Location loc = p.getLocation();
        // Block operations must run on the region owning the location (Folia)
        plugin.getFoliaScheduler().runAtLocation(loc, () -> {
            if (plugin.getGiftManager().spawnGift(loc.getWorld(), loc)) {
                lang.send(sender, "gift.spawned");
            } else {
                lang.send(sender, "gift.spawn-failed");
            }
        });
        return true;
    }
}
