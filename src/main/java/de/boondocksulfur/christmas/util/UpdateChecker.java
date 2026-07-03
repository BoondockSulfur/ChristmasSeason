package de.boondocksulfur.christmas.util;

import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.util.FoliaSchedulerHelper;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CompletableFuture;

/**
 * Update-Checker für Modrinth und GitHub
 *
 * Überprüft asynchron ob eine neue Version verfügbar ist und benachrichtigt OPs beim Join.
 *
 * Features:
 * - Modrinth API Integration
 * - GitHub Releases Fallback
 * - Asynchrone Abfrage (blockiert Server nicht)
 * - Auto-Check beim Server-Start
 * - Benachrichtigung für OPs beim Join
 * - Manuelle Prüfung mit /xmas update check
 *
 * Konfiguration:
 * 1. Plugin auf Modrinth veröffentlichen
 * 2. Projekt-Slug (z.B. "christmas-season") in MODRINTH_SLUG eintragen
 * 3. GitHub Repository in GITHUB_REPO eintragen
 */
public class UpdateChecker {

    private final ChristmasSeason plugin;
    private final String currentVersion;

    // WICHTIG: Diese Werte müssen angepasst werden wenn das Plugin veröffentlicht wird!
    private static final String MODRINTH_SLUG = "christmas-season"; // TODO: Ersetze mit echtem Modrinth-Slug
    private static final String GITHUB_REPO = "BoondockSulfur/ChristmasSeason";

    private static final String MODRINTH_API = "https://api.modrinth.com/v2/project/" + MODRINTH_SLUG + "/version";
    private static final String GITHUB_API = "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";
    private static final String MODRINTH_URL = "https://modrinth.com/plugin/" + MODRINTH_SLUG;
    private static final String GITHUB_URL = "https://github.com/" + GITHUB_REPO;

    private volatile String latestVersion = null;
    private volatile boolean updateAvailable = false;
    private volatile long lastCheck = 0;
    private static final long CHECK_COOLDOWN = 30 * 60 * 1000; // 30 Minuten Cooldown

    private final FoliaSchedulerHelper scheduler;

    public UpdateChecker(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.currentVersion = plugin.getPluginMeta().getVersion();
        this.scheduler = plugin.getFoliaScheduler();
    }

    /**
     * Führt einen asynchronen Update-Check durch
     * Speichert Ergebnis in Cache für spätere Abfragen
     *
     * @return CompletableFuture mit Update-Status
     */
    public CompletableFuture<UpdateResult> checkForUpdates() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Cooldown-Check (verhindert zu häufige API-Anfragen)
                long now = System.currentTimeMillis();
                if (now - lastCheck < CHECK_COOLDOWN && latestVersion != null) {
                    plugin.debug("Update-Check: Verwende Cache (Cooldown aktiv)");
                    return new UpdateResult(updateAvailable, latestVersion, currentVersion);
                }

                lastCheck = now;

                // Versuche zuerst Modrinth
                String version = checkModrinth();

                // Fallback zu GitHub wenn Modrinth fehlschlägt
                if (version == null) {
                    plugin.debug("Update-Check: Modrinth fehlgeschlagen, versuche GitHub...");
                    version = checkGitHub();
                }

                if (version == null) {
                    plugin.getLogger().warning("Update-Check fehlgeschlagen: Keine Verbindung zu Modrinth oder GitHub");
                    return new UpdateResult(false, null, currentVersion);
                }

                latestVersion = version;
                updateAvailable = isNewerVersion(version, currentVersion);

                plugin.debug("Update-Check: Aktuelle Version=" + currentVersion + ", Neueste Version=" + version);

                return new UpdateResult(updateAvailable, latestVersion, currentVersion);

            } catch (Exception e) {
                plugin.debug("Update-Check Fehler: " + e.getMessage());
                return new UpdateResult(false, null, currentVersion);
            }
        });
    }

    /**
     * Prüft Modrinth API auf neue Version
     *
     * @return Neueste Version oder null bei Fehler
     */
    private String checkModrinth() {
        HttpURLConnection connection = null;
        try {
            URL url = java.net.URI.create(MODRINTH_API).toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "ChristmasSeason/" + currentVersion);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                plugin.debug("Modrinth API Response Code: " + responseCode);
                return null;
            }

            // FIX: BufferedReader in try-with-resources für automatisches Schließen
            String json;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                json = response.toString();
            }

            // Finde erste "version_number" im JSON
            int versionIndex = json.indexOf("\"version_number\":");
            if (versionIndex == -1) {
                plugin.debug("Modrinth: version_number nicht gefunden");
                return null;
            }

            // Extrahiere Version-String
            int startQuote = json.indexOf("\"", versionIndex + 17);
            int endQuote = json.indexOf("\"", startQuote + 1);

            if (startQuote == -1 || endQuote == -1) {
                plugin.debug("Modrinth: Version-String nicht parsebar");
                return null;
            }

            String version = json.substring(startQuote + 1, endQuote);
            plugin.debug("Modrinth: Gefundene Version = " + version);
            return version;

        } catch (Exception e) {
            plugin.debug("Modrinth API Fehler: " + e.getMessage());
            return null;
        } finally {
            // FIX: Connection explizit schließen um Resource Leak zu vermeiden
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Prüft GitHub API auf neue Version (Fallback)
     *
     * @return Neueste Version oder null bei Fehler
     */
    private String checkGitHub() {
        HttpURLConnection connection = null;
        try {
            URL url = java.net.URI.create(GITHUB_API).toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "ChristmasSeason/" + currentVersion);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                plugin.debug("GitHub API Response Code: " + responseCode);
                return null;
            }

            // FIX: BufferedReader in try-with-resources für automatisches Schließen
            String json;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                json = response.toString();
            }

            // Finde "tag_name" im JSON
            int tagIndex = json.indexOf("\"tag_name\":");
            if (tagIndex == -1) {
                plugin.debug("GitHub: tag_name nicht gefunden");
                return null;
            }

            // Extrahiere Tag-String (entferne führendes "v" falls vorhanden)
            int startQuote = json.indexOf("\"", tagIndex + 11);
            int endQuote = json.indexOf("\"", startQuote + 1);

            if (startQuote == -1 || endQuote == -1) {
                plugin.debug("GitHub: Tag-String nicht parsebar");
                return null;
            }

            String tag = json.substring(startQuote + 1, endQuote);
            // Entferne führendes "v" (z.B. "v2.1.0" -> "2.1.0")
            if (tag.startsWith("v") || tag.startsWith("V")) {
                tag = tag.substring(1);
            }

            plugin.debug("GitHub: Gefundene Version = " + tag);
            return tag;

        } catch (Exception e) {
            plugin.debug("GitHub API Fehler: " + e.getMessage());
            return null;
        } finally {
            // FIX: Connection explizit schließen um Resource Leak zu vermeiden
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Vergleicht zwei Versions-Strings (z.B. "2.1.0" vs "2.2.0")
     * Unterstützt Semantic Versioning (MAJOR.MINOR.PATCH)
     *
     * @param newVersion Neue Version
     * @param currentVersion Aktuelle Version
     * @return true wenn newVersion neuer ist als currentVersion
     */
    private boolean isNewerVersion(String newVersion, String currentVersion) {
        try {
            // FIX: Nur führendes "v" entfernen (nicht alle "v" im String)
            if (newVersion.startsWith("v") || newVersion.startsWith("V")) newVersion = newVersion.substring(1);
            if (currentVersion.startsWith("v") || currentVersion.startsWith("V")) currentVersion = currentVersion.substring(1);

            String[] newParts = newVersion.split("\\.");
            String[] currentParts = currentVersion.split("\\.");

            int maxLength = Math.max(newParts.length, currentParts.length);

            for (int i = 0; i < maxLength; i++) {
                int newPart = i < newParts.length ? parseVersionPart(newParts[i]) : 0;
                int currentPart = i < currentParts.length ? parseVersionPart(currentParts[i]) : 0;

                if (newPart > currentPart) {
                    return true;
                } else if (newPart < currentPart) {
                    return false;
                }
            }

            // Versionen sind identisch
            return false;

        } catch (Exception e) {
            plugin.debug("Versions-Vergleich fehlgeschlagen: " + e.getMessage());
            return false;
        }
    }

    /**
     * Parst einen Versions-Teil (z.B. "2" aus "2.1.0")
     * Entfernt nicht-numerische Zeichen (z.B. "2.1.0-SNAPSHOT" -> "2")
     */
    private int parseVersionPart(String part) {
        // Entferne alles nach "-" (z.B. "2.1.0-SNAPSHOT")
        if (part.contains("-")) {
            part = part.substring(0, part.indexOf("-"));
        }

        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Sendet Update-Benachrichtigung an Spieler
     *
     * @param player Spieler der benachrichtigt werden soll
     */
    public void sendUpdateNotification(Player player) {
        if (!updateAvailable || latestVersion == null) {
            return;
        }

        player.sendMessage("§8§l§m                                    ");
        player.sendMessage("§b§lChristmasSeason §7- §aUpdate verfügbar!");
        player.sendMessage("");
        player.sendMessage("§7Aktuelle Version: §c" + currentVersion);
        player.sendMessage("§7Neueste Version:  §a" + latestVersion);
        player.sendMessage("");
        player.sendMessage("§7Download:");
        player.sendMessage("§b  ▸ Modrinth: §f" + MODRINTH_URL);
        player.sendMessage("§b  ▸ GitHub:   §f" + GITHUB_URL);
        player.sendMessage("");
        player.sendMessage("§7Changelog: §f" + GITHUB_URL + "/releases");
        player.sendMessage("§8§l§m                                    ");
    }

    /**
     * Sendet Update-Info in Console
     */
    public void sendConsoleNotification() {
        if (!updateAvailable || latestVersion == null) {
            return;
        }

        plugin.getLogger().info("═══════════════════════════════════════");
        plugin.getLogger().info("ChristmasSeason - Update verfügbar!");
        plugin.getLogger().info("");
        plugin.getLogger().info("Aktuelle Version: " + currentVersion);
        plugin.getLogger().info("Neueste Version:  " + latestVersion);
        plugin.getLogger().info("");
        plugin.getLogger().info("Download:");
        plugin.getLogger().info("  • Modrinth: " + MODRINTH_URL);
        plugin.getLogger().info("  • GitHub:   " + GITHUB_URL);
        plugin.getLogger().info("");
        plugin.getLogger().info("Changelog: " + GITHUB_URL + "/releases");
        plugin.getLogger().info("═══════════════════════════════════════");
    }

    /**
     * Startet automatischen Update-Check beim Server-Start
     * Benachrichtigt Console wenn Update verfügbar
     */
    public void startAutoCheck() {
        // Verzögerter Check (5 Sekunden nach Server-Start)
        // FIX: FoliaSchedulerHelper statt Bukkit.getScheduler() (Folia-kompatibel)
        scheduler.runAsyncLater(() -> {
            checkForUpdates().thenAccept(result -> {
                if (result.isUpdateAvailable()) {
                    // Zeige in Console (Global Scheduler für Folia)
                    scheduler.runGlobalTask(this::sendConsoleNotification);
                }
            });
        }, 100L); // 5 Sekunden
    }

    // Getters
    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    public String getModrinthUrl() {
        return MODRINTH_URL;
    }

    public String getGitHubUrl() {
        return GITHUB_URL;
    }

    /**
     * Ergebnis eines Update-Checks
     */
    public static class UpdateResult {
        private final boolean updateAvailable;
        private final String latestVersion;
        private final String currentVersion;

        public UpdateResult(boolean updateAvailable, String latestVersion, String currentVersion) {
            this.updateAvailable = updateAvailable;
            this.latestVersion = latestVersion;
            this.currentVersion = currentVersion;
        }

        public boolean isUpdateAvailable() {
            return updateAvailable;
        }

        public String getLatestVersion() {
            return latestVersion;
        }

        public String getCurrentVersion() {
            return currentVersion;
        }
    }
}
