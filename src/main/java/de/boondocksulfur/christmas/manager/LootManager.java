package de.boondocksulfur.christmas.manager;

import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.util.LanguageManager;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Parses loot lists from the config and builds item stacks.
 *
 * <p>Two entry formats are accepted in every loot list:
 * <pre>
 *   - DIAMOND:2                 # simple: MATERIAL[:amount]
 *   - material: DIAMOND         # extended
 *     amount: 1-3               # fixed number or range
 *     weight: 5                 # relative pick chance (default 1)
 *     name: "&amp;bFrozen Diamond"
 *     lore: ["&amp;7Found under the tree"]
 *     enchantments: {sharpness: 2, unbreaking: 1}
 *     glow: true
 *     commands: ["give %player% minecraft:emerald 4"]   # advent rewards only
 * </pre>
 */
public class LootManager {

    /** One parsed loot entry. */
    public static class LootEntry {
        public final Material material;
        public final int minAmount, maxAmount;
        public final double weight;
        public final String name;
        public final List<String> lore;
        public final Map<Enchantment, Integer> enchantments;
        public final boolean glow;
        public final List<String> commands;

        LootEntry(Material material, int minAmount, int maxAmount, double weight, String name, List<String> lore,
                  Map<Enchantment, Integer> enchantments, boolean glow, List<String> commands) {
            this.material = material;
            this.minAmount = minAmount;
            this.maxAmount = maxAmount;
            this.weight = weight;
            this.name = name;
            this.lore = lore;
            this.enchantments = enchantments;
            this.glow = glow;
            this.commands = commands;
        }

        /** @return {@code true} if this entry only runs commands (no item) */
        public boolean isCommandOnly() { return material == null; }
    }

    private final ChristmasSeason plugin;
    private final LanguageManager lang;
    private final Map<String, List<LootEntry>> cache = new java.util.concurrent.ConcurrentHashMap<>();

    public LootManager(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
    }

    /** Drops the parsed cache (config reload). */
    public void reload() {
        cache.clear();
    }

    /** @return parsed entries of the loot list at the config path (cached) */
    public List<LootEntry> getList(String path) {
        return cache.computeIfAbsent(path, p -> parse(plugin.getConfig().getList(p), p));
    }

    /** Parses a raw config list; invalid entries are skipped with a warning. */
    public List<LootEntry> parse(List<?> raw, String path) {
        List<LootEntry> result = new ArrayList<>();
        if (raw == null) return result;
        for (Object o : raw) {
            LootEntry entry = parseEntry(o, path);
            if (entry != null) result.add(entry);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private LootEntry parseEntry(Object o, String path) {
        if (o instanceof String s) {
            String[] split = s.split(":");
            Material m = Material.matchMaterial(split[0].trim());
            if (m == null) {
                lang.logWarning("log.config.unknown-material", split[0], path);
                return null;
            }
            int amount = 1;
            if (split.length > 1) {
                try { amount = Integer.parseInt(split[1].trim()); } catch (NumberFormatException ignored) {}
            }
            return new LootEntry(m, Math.max(1, amount), Math.max(1, amount), 1.0, null, List.of(), Map.of(), false, List.of());
        }
        if (o instanceof Map<?, ?> map) {
            Object matObj = map.get("material");
            List<String> commands = toStringList(map.get("commands"));
            Material m = null;
            if (matObj != null) {
                m = Material.matchMaterial(String.valueOf(matObj).trim());
                if (m == null) {
                    lang.logWarning("log.config.unknown-material", String.valueOf(matObj), path);
                    return null;
                }
            } else if (commands.isEmpty()) {
                lang.logWarning("log.config.loot-entry-invalid", path);
                return null;
            }

            int[] amount = parseRange(map.get("amount"));
            double weight = 1.0;
            if (map.get("weight") instanceof Number n) weight = Math.max(0.0, n.doubleValue());
            String name = map.get("name") != null ? String.valueOf(map.get("name")) : null;
            List<String> lore = toStringList(map.get("lore"));
            Object glowObj = map.get("glow");
            boolean glow = glowObj != null && Boolean.parseBoolean(String.valueOf(glowObj));

            Map<Enchantment, Integer> enchants = new LinkedHashMap<>();
            if (map.get("enchantments") instanceof Map<?, ?> em) {
                for (Map.Entry<?, ?> e : em.entrySet()) {
                    Enchantment ench = resolveEnchantment(String.valueOf(e.getKey()));
                    if (ench == null) {
                        lang.logWarning("log.config.unknown-enchantment", String.valueOf(e.getKey()), path);
                        continue;
                    }
                    int level = 1;
                    if (e.getValue() instanceof Number n) level = Math.max(1, n.intValue());
                    enchants.put(ench, level);
                }
            }
            return new LootEntry(m, amount[0], amount[1], weight, name, lore, enchants, glow, commands);
        }
        lang.logWarning("log.config.loot-entry-invalid", path);
        return null;
    }

    private static List<String> toStringList(Object o) {
        if (o instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object x : l) out.add(String.valueOf(x));
            return out;
        }
        if (o instanceof String s) return List.of(s);
        return List.of();
    }

    /** "3" -> [3,3], "1-4" -> [1,4]; anything else -> [1,1]. */
    static int[] parseRange(Object o) {
        if (o instanceof Number n) return new int[]{Math.max(1, n.intValue()), Math.max(1, n.intValue())};
        if (o instanceof String s) {
            String[] p = s.trim().split("-");
            try {
                int a = Integer.parseInt(p[0].trim());
                int b = p.length > 1 ? Integer.parseInt(p[1].trim()) : a;
                return new int[]{Math.max(1, Math.min(a, b)), Math.max(1, Math.max(a, b))};
            } catch (NumberFormatException ignored) {}
        }
        return new int[]{1, 1};
    }

    /** Public helper for "min-max" config values (item counts). */
    public static int randomInRange(String value, int defMin, int defMax) {
        int[] r = value == null ? new int[]{defMin, defMax} : parseRange(value);
        return r[0] + ThreadLocalRandom.current().nextInt(r[1] - r[0] + 1);
    }

    private Enchantment resolveEnchantment(String name) {
        try {
            NamespacedKey key = name.contains(":") ? NamespacedKey.fromString(name.toLowerCase()) : NamespacedKey.minecraft(name.toLowerCase());
            if (key == null) return null;
            return RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(key);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Weighted random pick; {@code null} for an empty list. */
    public LootEntry pick(List<LootEntry> entries) {
        if (entries == null || entries.isEmpty()) return null;
        double total = 0;
        for (LootEntry e : entries) total += e.weight;
        if (total <= 0) return entries.get(ThreadLocalRandom.current().nextInt(entries.size()));
        double r = ThreadLocalRandom.current().nextDouble() * total;
        for (LootEntry e : entries) {
            r -= e.weight;
            if (r <= 0) return e;
        }
        return entries.get(entries.size() - 1);
    }

    /** Builds the item stack for an entry (random amount within its range). */
    public ItemStack build(LootEntry entry) {
        if (entry.isCommandOnly()) return null;
        int amount = entry.minAmount + ThreadLocalRandom.current().nextInt(entry.maxAmount - entry.minAmount + 1);
        ItemStack stack = new ItemStack(entry.material, Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (entry.name != null) {
                meta.displayName(legacy(entry.name).decoration(TextDecoration.ITALIC, false));
            }
            if (!entry.lore.isEmpty()) {
                List<Component> lore = new ArrayList<>();
                for (String line : entry.lore) lore.add(legacy(line).decoration(TextDecoration.ITALIC, false));
                meta.lore(lore);
            }
            for (Map.Entry<Enchantment, Integer> e : entry.enchantments.entrySet()) {
                meta.addEnchant(e.getKey(), e.getValue(), true);
            }
            if (entry.glow) {
                meta.setEnchantmentGlintOverride(true);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** Runs the entry's commands from the console with {@code %player%} replaced. Must run on a tick thread. */
    public void runCommands(LootEntry entry, Player player, Map<String, String> extraPlaceholders) {
        for (String cmd : entry.commands) {
            String c = cmd.replace("%player%", player.getName());
            for (Map.Entry<String, String> e : extraPlaceholders.entrySet()) c = c.replace(e.getKey(), e.getValue());
            String finalCmd = c;
            plugin.getFoliaScheduler().runGlobalTask(() ->
                    plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), finalCmd));
        }
    }

    private static Component legacy(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s.replace('§', '&'));
    }

    /** Gives an item to a player, dropping it at their feet if the inventory is full. */
    public static void give(Player player, ItemStack stack) {
        if (stack == null) return;
        Map<Integer, ItemStack> left = player.getInventory().addItem(stack);
        for (ItemStack rest : left.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }
}
