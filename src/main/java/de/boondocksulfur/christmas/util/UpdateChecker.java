package de.boondocksulfur.christmas.util;

import de.boondocksulfur.christmas.ChristmasSeason;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CompletableFuture;

/**
 * Checks Modrinth (with GitHub releases as fallback) for a newer release.
 *
 * <p>Runs asynchronously, once after startup and on demand via {@code /xmas update check}.
 * Operators are notified on join with clickable download links. Can be disabled with
 * {@code updateChecker.enabled: false}.
 */
public class UpdateChecker {

    private final ChristmasSeason plugin;
    private final LanguageManager lang;
    private final String currentVersion;

    private static final String MODRINTH_SLUG = "christmas-season";
    private static final String GITHUB_REPO = "BoondockSulfur/ChristmasSeason";

    private static final String MODRINTH_API = "https://api.modrinth.com/v2/project/" + MODRINTH_SLUG + "/version";
    private static final String GITHUB_API = "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";
    private static final String MODRINTH_URL = "https://modrinth.com/plugin/" + MODRINTH_SLUG;
    private static final String GITHUB_URL = "https://github.com/" + GITHUB_REPO;

    private volatile String latestVersion = null;
    private volatile boolean updateAvailable = false;
    private volatile long lastCheck = 0;
    private static final long CHECK_COOLDOWN = 30 * 60 * 1000; // 30 minutes

    private final FoliaSchedulerHelper scheduler;

    public UpdateChecker(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.currentVersion = plugin.getPluginMeta().getVersion();
        this.scheduler = plugin.getFoliaScheduler();
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("updateChecker.enabled", true);
    }

    public boolean isNotifyOps() {
        return isEnabled() && plugin.getConfig().getBoolean("updateChecker.notifyOps", true);
    }

    /**
     * Runs an asynchronous update check (cached for 30 minutes).
     *
     * @return future with the result; never fails exceptionally
     */
    public CompletableFuture<UpdateResult> checkForUpdates() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                long now = System.currentTimeMillis();
                if (now - lastCheck < CHECK_COOLDOWN && latestVersion != null) {
                    plugin.debug("Update check: using cached result (cooldown)");
                    return new UpdateResult(updateAvailable, latestVersion, currentVersion);
                }
                lastCheck = now;

                String version = checkModrinth();
                if (version == null) {
                    plugin.debug("Update check: Modrinth unavailable, trying GitHub");
                    version = checkGitHub();
                }
                if (version == null) {
                    lang.logWarning("log.update.check-failed");
                    return new UpdateResult(false, null, currentVersion);
                }

                latestVersion = version;
                updateAvailable = isNewerVersion(version, currentVersion);
                plugin.debug("Update check: current=" + currentVersion + ", latest=" + version);
                return new UpdateResult(updateAvailable, latestVersion, currentVersion);

            } catch (Exception e) {
                plugin.debug("Update check error: " + e.getMessage());
                return new UpdateResult(false, null, currentVersion);
            }
        });
    }

    private String fetch(String apiUrl) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = java.net.URI.create(apiUrl).toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "ChristmasSeason/" + currentVersion);
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                plugin.debug("Update check: " + apiUrl + " responded with " + responseCode);
                return null;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                return response.toString();
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Extracts the string value following {@code "field":} at or after {@code from}, or {@code null}. */
    private static String stringField(String json, String field, int from) {
        int idx = json.indexOf("\"" + field + "\":", from);
        if (idx == -1) return null;
        int startQuote = json.indexOf('"', idx + field.length() + 3);
        if (startQuote == -1) return null;
        int endQuote = json.indexOf('"', startQuote + 1);
        if (endQuote == -1) return null;
        return json.substring(startQuote + 1, endQuote);
    }

    /**
     * Newest <em>release</em> version on Modrinth (versions are sorted newest first;
     * alpha/beta builds are skipped).
     */
    private String checkModrinth() {
        try {
            String json = fetch(MODRINTH_API);
            if (json == null) return null;

            int pos = 0;
            while (true) {
                int idx = json.indexOf("\"version_number\":", pos);
                if (idx == -1) break;
                String number = stringField(json, "version_number", idx);
                int next = json.indexOf("\"version_number\":", idx + 1);
                int typeIdx = json.indexOf("\"version_type\":", idx);
                String type = (typeIdx != -1 && (next == -1 || typeIdx < next)) ? stringField(json, "version_type", typeIdx) : "release";
                if (number != null && "release".equalsIgnoreCase(type)) {
                    plugin.debug("Modrinth: latest release = " + number);
                    return number;
                }
                if (next == -1) break;
                pos = next;
            }
            plugin.debug("Modrinth: no release version found");
            return null;
        } catch (Exception e) {
            plugin.debug("Modrinth API error: " + e.getMessage());
            return null;
        }
    }

    /** Latest GitHub release tag (leading "v" stripped). */
    private String checkGitHub() {
        try {
            String json = fetch(GITHUB_API);
            if (json == null) return null;
            String tag = stringField(json, "tag_name", 0);
            if (tag == null) {
                plugin.debug("GitHub: tag_name not found");
                return null;
            }
            if (tag.startsWith("v") || tag.startsWith("V")) tag = tag.substring(1);
            plugin.debug("GitHub: latest release = " + tag);
            return tag;
        } catch (Exception e) {
            plugin.debug("GitHub API error: " + e.getMessage());
            return null;
        }
    }

    /** Semantic comparison of MAJOR.MINOR.PATCH strings. */
    private boolean isNewerVersion(String newVersion, String currentVersion) {
        try {
            if (newVersion.startsWith("v") || newVersion.startsWith("V")) newVersion = newVersion.substring(1);
            if (currentVersion.startsWith("v") || currentVersion.startsWith("V")) currentVersion = currentVersion.substring(1);

            String[] newParts = newVersion.split("\\.");
            String[] currentParts = currentVersion.split("\\.");
            int maxLength = Math.max(newParts.length, currentParts.length);

            for (int i = 0; i < maxLength; i++) {
                int newPart = i < newParts.length ? parseVersionPart(newParts[i]) : 0;
                int currentPart = i < currentParts.length ? parseVersionPart(currentParts[i]) : 0;
                if (newPart > currentPart) return true;
                if (newPart < currentPart) return false;
            }
            return false;
        } catch (Exception e) {
            plugin.debug("Version comparison failed: " + e.getMessage());
            return false;
        }
    }

    /** "2" from "2", also "2" from "2-SNAPSHOT". */
    private int parseVersionPart(String part) {
        if (part.contains("-")) {
            part = part.substring(0, part.indexOf("-"));
        }
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Clickable link line: {@code ▸ [Label]} opening the URL. */
    private Component link(String label, String url) {
        return Component.text("  ▸ ", NamedTextColor.AQUA)
                .append(Component.text("[" + label + "]", NamedTextColor.WHITE, TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(url))
                        .hoverEvent(HoverEvent.showText(Component.text(url, NamedTextColor.GRAY))));
    }

    /** Sends the update notice with clickable download links to a player. */
    public void sendUpdateNotification(Player player) {
        if (!updateAvailable || latestVersion == null) return;

        player.sendMessage(lang.getComponent("update.header"));
        player.sendMessage(lang.getComponent("update.available"));
        player.sendMessage(lang.getComponent("update.current", currentVersion));
        player.sendMessage(lang.getComponent("update.latest", latestVersion));
        player.sendMessage(lang.getComponent("update.download"));
        player.sendMessage(link("Modrinth", MODRINTH_URL));
        player.sendMessage(link("GitHub", GITHUB_URL + "/releases"));
        player.sendMessage(lang.getComponent("update.footer"));
    }

    /** Logs the update notice to the console. */
    public void sendConsoleNotification() {
        if (!updateAvailable || latestVersion == null) return;
        lang.logInfo("log.update.available", currentVersion, latestVersion);
        lang.logInfo("log.update.download", MODRINTH_URL, GITHUB_URL + "/releases");
    }

    /** Schedules the startup check (five seconds after enable) if enabled. */
    public void startAutoCheck() {
        if (!isEnabled()) {
            plugin.debug("Update checker disabled in config");
            return;
        }
        scheduler.runAsyncLater(() -> checkForUpdates().thenAccept(result -> {
            if (result.isUpdateAvailable()) {
                scheduler.runGlobalTask(this::sendConsoleNotification);
            }
        }), 100L);
    }

    public boolean isUpdateAvailable() { return updateAvailable; }
    public String getLatestVersion() { return latestVersion; }
    public String getCurrentVersion() { return currentVersion; }
    public String getModrinthUrl() { return MODRINTH_URL; }
    public String getGitHubUrl() { return GITHUB_URL; }

    /** Result of one update check. */
    public static class UpdateResult {
        private final boolean updateAvailable;
        private final String latestVersion;
        private final String currentVersion;

        public UpdateResult(boolean updateAvailable, String latestVersion, String currentVersion) {
            this.updateAvailable = updateAvailable;
            this.latestVersion = latestVersion;
            this.currentVersion = currentVersion;
        }

        public boolean isUpdateAvailable() { return updateAvailable; }
        public String getLatestVersion() { return latestVersion; }
        public String getCurrentVersion() { return currentVersion; }
    }
}
