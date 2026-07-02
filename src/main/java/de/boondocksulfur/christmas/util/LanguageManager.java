package de.boondocksulfur.christmas.util;

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
    private YamlConfiguration messages;
    // FOLIA FIX: ConcurrentHashMap - getMessage() wird auch aus Region-Tasks aufgerufen
    private final Map<String, String> cache = new java.util.concurrent.ConcurrentHashMap<>();
    private String currentLanguage;

    public LanguageManager(ChristmasSeason plugin) {
        this.plugin = plugin;
        loadLanguage();
    }

    /**
     * Lädt die Sprachdatei basierend auf der Config
     */
    public void loadLanguage() {
        cache.clear();
        currentLanguage = plugin.getConfig().getString("language", "de");

        // Dateiname bestimmen
        String fileName = "messages_" + currentLanguage + ".yml";
        File langFile = new File(plugin.getDataFolder(), fileName);

        plugin.getLogger().info("Loading language: " + currentLanguage);
        plugin.getLogger().info("Language file path: " + langFile.getAbsolutePath());
        plugin.getLogger().info("File exists: " + langFile.exists() + ", Size: " + (langFile.exists() ? langFile.length() : "N/A") + " bytes");

        // Versuche die Datei zu laden
        if (langFile.exists() && langFile.length() > 0) {
            messages = YamlConfiguration.loadConfiguration(langFile);

            // Defaults aus JAR setzen
            InputStream defConfigStream = plugin.getResource(fileName);
            if (defConfigStream != null) {
                YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defConfigStream, StandardCharsets.UTF_8)
                );
                messages.setDefaults(defConfig);
                plugin.getLogger().info("Loaded defaults from JAR resource: " + fileName);
            } else {
                plugin.getLogger().warning("Could not load defaults from JAR for: " + fileName);
            }

            int keyCount = messages.getKeys(true).size();
            plugin.getLogger().info("Language loaded from disk: " + currentLanguage + " (" + keyCount + " keys)");
        } else {
            // Fallback: Lade direkt aus JAR wenn Datei nicht existiert
            plugin.getLogger().warning("Language file not found on disk, loading from JAR: " + fileName);
            InputStream defConfigStream = plugin.getResource(fileName);
            if (defConfigStream != null) {
                messages = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defConfigStream, StandardCharsets.UTF_8)
                );
                int keyCount = messages.getKeys(true).size();
                plugin.getLogger().info("Language loaded from JAR: " + currentLanguage + " (" + keyCount + " keys)");
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
                messages = new YamlConfiguration(); // Leere Config als Notfall
            }
        }
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
            // Farbcodes konvertieren
            message = message.replace('&', '§');
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
