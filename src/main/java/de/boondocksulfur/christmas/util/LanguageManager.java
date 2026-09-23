package de.boondocksulfur.christmas.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import de.boondocksulfur.christmas.ChristmasSeason;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Loads {@code messages_<lang>.yml} and resolves message keys with placeholders.
 *
 * <p>Convention: message files use {@code &} colour codes. Keys under {@code log.}
 * are console-only and are stripped of colour codes automatically.
 */
public class LanguageManager {

    private final ChristmasSeason plugin;
    // volatile: reassigned by reload() on the command thread while region threads read it
    private volatile YamlConfiguration messages;
    // ConcurrentHashMap: getMessage() is called from region tasks as well
    private final Map<String, String> cache = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile String currentLanguage;

    public LanguageManager(ChristmasSeason plugin) {
        this.plugin = plugin;
        loadLanguage();
    }

    /** Loads the language file selected in config.yml (falls back to the JAR copy). */
    public void loadLanguage() {
        String language = plugin.getConfig().getString("language", "en");
        String fileName = "messages_" + language + ".yml";
        File langFile = new File(plugin.getDataFolder(), fileName);

        // Build the new configuration completely, then publish it atomically and clear
        // the cache afterwards. Doing it the other way round let a region thread write a
        // stale string into the fresh cache during reload.
        YamlConfiguration loaded;

        if (langFile.exists() && langFile.length() > 0) {
            loaded = YamlConfiguration.loadConfiguration(langFile);

            InputStream defConfigStream = plugin.getResource(fileName);
            YamlConfiguration defConfig = null;
            if (defConfigStream != null) {
                defConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defConfigStream, StandardCharsets.UTF_8));
            }
            // English bundled file is the last resort for every key (custom or partial translations)
            YamlConfiguration english = bundledEnglish();
            if (defConfig != null) {
                if (english != null && !"en".equals(language)) defConfig.setDefaults(english);
                loaded.setDefaults(defConfig);
            } else if (english != null) {
                loaded.setDefaults(english);
            }
            plugin.getLogger().info("Language loaded: " + language + " (" + loaded.getKeys(true).size() + " keys)");
        } else {
            InputStream defConfigStream = plugin.getResource(fileName);
            if (defConfigStream != null) {
                loaded = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defConfigStream, StandardCharsets.UTF_8));
                plugin.getLogger().info("Language loaded from JAR: " + language + " (" + loaded.getKeys(true).size() + " keys)");
            } else {
                plugin.getLogger().severe("Language file not found: " + fileName + " - falling back to English");
                YamlConfiguration english = bundledEnglish();
                loaded = english != null ? english : new YamlConfiguration();
            }
        }

        this.currentLanguage = language;
        this.messages = loaded;
        cache.clear();
    }

    private YamlConfiguration bundledEnglish() {
        InputStream in = plugin.getResource("messages_en.yml");
        if (in == null) return null;
        return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    /**
     * Returns a message with {@code {0}}, {@code {1}}, ... replaced by the given values.
     * Colour codes are converted to section signs, except for {@code log.*} keys which
     * are returned as plain text for the console.
     */
    public String getMessage(String key, Object... replacements) {
        String message = cache.get(key);
        if (message == null) {
            message = messages.getString(key);
            if (message == null && messages.getDefaults() != null) {
                message = messages.getDefaults().getString(key);
            }
            if (message == null) {
                plugin.getLogger().warning("Missing translation key: " + key + " (language: " + currentLanguage + ")");
                return "§c[Missing: " + key + "]";
            }

            // Tolerate old extracted files that still contain raw section signs
            message = message.replace('§', '&');

            // Only valid codes such as '&6' are converted; a literal '&' ("Elves & Helpers") survives
            Component parsed = LegacyComponentSerializer.legacyAmpersand().deserialize(message);

            if (key.startsWith("log.")) {
                message = PlainTextComponentSerializer.plainText().serialize(parsed);
            } else {
                message = LegacyComponentSerializer.legacySection().serialize(parsed);
            }
            cache.put(key, message);
        }

        // Replace placeholders on a copy so the cache stays untouched
        String result = message;
        for (int i = 0; i < replacements.length; i++) {
            result = result.replace("{" + i + "}", String.valueOf(replacements[i]));
        }
        return result;
    }

    /** Message without placeholders. */
    public String get(String key) {
        return getMessage(key);
    }

    /** Message as Adventure component (entity names, item names, broadcasts). */
    public Component getComponent(String key, Object... replacements) {
        return LegacyComponentSerializer.legacySection().deserialize(getMessage(key, replacements));
    }

    /** Sends a translated message to a command sender. */
    public void send(CommandSender sender, String key, Object... replacements) {
        sender.sendMessage(getMessage(key, replacements));
    }

    /** Logs a translated message at INFO level. */
    public void logInfo(String key, Object... replacements) {
        plugin.getLogger().info(getMessage(key, replacements));
    }

    /** Logs a translated message at WARNING level. */
    public void logWarning(String key, Object... replacements) {
        plugin.getLogger().warning(getMessage(key, replacements));
    }

    /** Logs a translated message at SEVERE level. */
    public void logSevere(String key, Object... replacements) {
        plugin.getLogger().severe(getMessage(key, replacements));
    }

    /** Strips legacy colour codes (Adventure replacement for the deprecated ChatColor.stripColor). */
    public static String stripColors(String legacy) {
        if (legacy == null || legacy.indexOf('§') < 0) return legacy;
        return PlainTextComponentSerializer.plainText().serialize(
                LegacyComponentSerializer.legacySection().deserialize(legacy));
    }

    public String getCurrentLanguage() {
        return currentLanguage;
    }

    /** Reloads the language file. */
    public void reload() {
        loadLanguage();
    }
}
