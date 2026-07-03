package de.boondocksulfur.christmas.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import de.boondocksulfur.christmas.ChristmasSeason;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Verwaltet mehrsprachige Nachrichten für das Plugin
 */
public class LanguageManager {

    private final ChristmasSeason plugin;
    // FOLIA FIX: volatile - wird bei reload() vom Command-Thread neu zugewiesen,
    // während Region-Threads lesen (sichere Publikation)
    private volatile YamlConfiguration messages;
    // FOLIA FIX: ConcurrentHashMap - getMessage() wird auch aus Region-Tasks aufgerufen
    private final Map<String, String> cache = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile String currentLanguage;

    public LanguageManager(ChristmasSeason plugin) {
        this.plugin = plugin;
        loadLanguage();
    }

    /**
     * Lädt die Sprachdatei basierend auf der Config
     */
    public void loadLanguage() {
        String language = plugin.getConfig().getString("language", "de");

        // Dateiname bestimmen
        String fileName = "messages_" + language + ".yml";
        File langFile = new File(plugin.getDataFolder(), fileName);

        plugin.getLogger().info("Loading language: " + language);
        plugin.getLogger().info("Language file path: " + langFile.getAbsolutePath());
        plugin.getLogger().info("File exists: " + langFile.exists() + ", Size: " + (langFile.exists() ? langFile.length() : "N/A") + " bytes");

        // RELOAD-RACE FIX: Erst die neue Config KOMPLETT in eine lokale Variable
        // bauen, dann atomar zuweisen, dann Cache leeren. Vorher konnte ein
        // Region-Thread zwischen cache.clear() und der Neuzuweisung einen alten
        // String in den frischen Cache schreiben (Key bliebe in falscher Sprache).
        YamlConfiguration loaded;

        // Versuche die Datei zu laden
        if (langFile.exists() && langFile.length() > 0) {
            loaded = YamlConfiguration.loadConfiguration(langFile);

            // Defaults aus JAR setzen
            InputStream defConfigStream = plugin.getResource(fileName);
            if (defConfigStream != null) {
                YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defConfigStream, StandardCharsets.UTF_8)
                );
                loaded.setDefaults(defConfig);
                plugin.getLogger().info("Loaded defaults from JAR resource: " + fileName);
            } else {
                plugin.getLogger().warning("Could not load defaults from JAR for: " + fileName);
            }

            int keyCount = loaded.getKeys(true).size();
            plugin.getLogger().info("Language loaded from disk: " + language + " (" + keyCount + " keys)");
        } else {
            // Fallback: Lade direkt aus JAR wenn Datei nicht existiert
            plugin.getLogger().warning("Language file not found on disk, loading from JAR: " + fileName);
            InputStream defConfigStream = plugin.getResource(fileName);
            if (defConfigStream != null) {
                loaded = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defConfigStream, StandardCharsets.UTF_8)
                );
                int keyCount = loaded.getKeys(true).size();
                plugin.getLogger().info("Language loaded from JAR: " + language + " (" + keyCount + " keys)");
            } else {
                plugin.getLogger().severe("Language file not found anywhere: " + fileName);
                plugin.getLogger().severe("Available resources in plugin JAR:");
                // Versuche verfügbare Ressourcen aufzulisten
                try {
                    java.util.Enumeration<java.net.URL> resources = plugin.getClass().getClassLoader().getResources("");
                    while (resources.hasMoreElements()) {
                        plugin.getLogger().severe("  - " + resources.nextElement().toString());
                    }
                } catch (Exception e) {
                    plugin.getLogger().severe("Could not list resources: " + e.getMessage());
                }
                loaded = new YamlConfiguration(); // Leere Config als Notfall
            }
        }

        // Atomar publizieren, DANN Cache leeren (Reihenfolge wichtig, s.o.)
        this.currentLanguage = language;
        this.messages = loaded;
        cache.clear();
    }

    /**
     * Holt eine Nachricht mit Platzhalter-Ersetzung
     */
    public String getMessage(String key, Object... replacements) {
        // Cache prüfen
        String message = cache.get(key);
        if (message == null) {
            message = messages.getString(key);
            if (message == null) {
                // Versuche mit Defaults
                if (messages.getDefaults() != null) {
                    message = messages.getDefaults().getString(key);
                }
                if (message == null) {
                    plugin.getLogger().warning("Missing translation key: " + key + " (Language: " + currentLanguage + ", Available keys: " + messages.getKeys(false).size() + ")");
                    return "§c[Missing: " + key + "]";
                }
            }
            // Toleranz für alte, bereits extrahierte Sprachdateien auf Servern,
            // die noch rohe '§'-Codes enthalten (Konvention ist '&')
            message = message.replace('§', '&');

            // Farbcodes via Adventure konvertieren - NUR gültige Codes wie '&6',
            // literale '&' (z.B. "Wichtel & Elfen") bleiben erhalten!
            Component parsed = LegacyComponentSerializer.legacyAmpersand().deserialize(message);

            if (key.startsWith("log.")) {
                // 'log.*'-Keys landen ausschließlich im Server-Log, wo Farbcodes
                // nicht gerendert werden - dort direkt als Klartext
                message = PlainTextComponentSerializer.plainText().serialize(parsed);
            } else {
                message = LegacyComponentSerializer.legacySection().serialize(parsed);
            }

            cache.put(key, message);
        }

        // Platzhalter ersetzen (auf einer Kopie, damit Cache nicht verändert wird)
        String result = message;
        for (int i = 0; i < replacements.length; i++) {
            result = result.replace("{" + i + "}", String.valueOf(replacements[i]));
        }

        return result;
    }

    /**
     * Holt eine Nachricht ohne Platzhalter
     */
    public String get(String key) {
        return getMessage(key);
    }

    /**
     * Holt eine Nachricht als Adventure-Component (für customName,
     * displayName, Broadcasts - die modernen Paper-APIs)
     */
    public Component getComponent(String key, Object... replacements) {
        return LegacyComponentSerializer.legacySection().deserialize(getMessage(key, replacements));
    }

    /**
     * Entfernt Legacy-Farbcodes aus einem String (Adventure-Ersatz für
     * das deprecated ChatColor.stripColor)
     */
    public static String stripColors(String legacy) {
        if (legacy == null || legacy.indexOf('§') < 0) return legacy;
        return PlainTextComponentSerializer.plainText().serialize(
                LegacyComponentSerializer.legacySection().deserialize(legacy));
    }

    /**
     * Gibt die aktuelle Sprache zurück
     */
    public String getCurrentLanguage() {
        return currentLanguage;
    }

    /**
     * Reload der Sprachdatei
     */
    public void reload() {
        loadLanguage();
    }
}
