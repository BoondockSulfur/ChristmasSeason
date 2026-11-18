package de.boondocksulfur.christmas.cmd;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.manager.OptOutManager;
import de.boondocksulfur.christmas.manager.OptOutManager.Category;
import de.boondocksulfur.christmas.util.LanguageManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /xmasoptout [wichtel|elves|snowmen|all]} - players switch the festive mobs off for
 * themselves. Without an argument the current state is shown; a category toggles.
 */
public class XmasOptOutCommand implements TabExecutor {

    private final ChristmasSeason plugin;
    private final LanguageManager lang;

    public XmasOptOutCommand(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            lang.send(sender, "command.players-only");
            return true;
        }
        OptOutManager optOut = plugin.getOptOutManager();

        if (args.length == 0) {
            lang.send(player, "optout.status-header");
            for (Category c : Category.values()) {
                lang.send(player, "optout.status-line", lang.get("optout.category." + c.id()),
                        lang.get(optOut.isOptedOut(player, c) ? "optout.state-off" : "optout.state-on"));
            }
            lang.send(player, "optout.usage");
            return true;
        }

        String arg = args[0].toLowerCase(Locale.ROOT);
        if (arg.equals("all")) {
            boolean anyActive = false;
            for (Category c : Category.values()) anyActive |= !optOut.isOptedOut(player, c);
            for (Category c : Category.values()) optOut.setOptedOut(player, c, anyActive);
            lang.send(player, anyActive ? "optout.opted-out" : "optout.opted-in", lang.get("optout.category.all"));
            return true;
        }

        Category category = Category.byId(arg);
        if (category == null) {
            lang.send(player, "optout.usage");
            return true;
        }
        boolean out = !optOut.isOptedOut(player, category);
        optOut.setOptedOut(player, category, out);
        lang.send(player, out ? "optout.opted-out" : "optout.opted-in", lang.get("optout.category." + category.id()));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length != 1) return out;
        String prefix = args[0].toLowerCase(Locale.ROOT);
        for (Category c : Category.values()) if (c.id().startsWith(prefix)) out.add(c.id());
        if ("all".startsWith(prefix)) out.add("all");
        return out;
    }
}
