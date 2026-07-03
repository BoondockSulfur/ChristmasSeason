package de.boondocksulfur.christmas.integration;

import de.boondocksulfur.christmas.ChristmasSeason;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Region Integration - WorldGuard & GriefPrevention support
 * Features:
 * - Check if spawning is allowed in region
 * - Respect region flags and claims
 * - Soft dependency (works without plugins)
 */
public class RegionIntegration {

    private final ChristmasSeason plugin;
    private boolean worldGuardEnabled = false;
    private boolean griefPreventionEnabled = false;

    // WorldGuard integration (cached via reflection)
    private Object regionContainer;

    // GriefPrevention integration (cached via reflection)
    private Object gpDataStore;

    public RegionIntegration(ChristmasSeason plugin) {
        this.plugin = plugin;
        detectPlugins();
    }

    /**
     * Detect installed region plugins
     */
    private void detectPlugins() {
        // WorldGuard detection
        Plugin wg = plugin.getServer().getPluginManager().getPlugin("WorldGuard");
        if (wg != null && wg.isEnabled()) {
            try {
                Class<?> wgClass = Class.forName("com.sk89q.worldguard.WorldGuard");
                Object wgInstance = wgClass.getMethod("getInstance").invoke(null);
                Object platform = wgClass.getMethod("getPlatform").invoke(wgInstance);
                regionContainer = platform.getClass().getMethod("getRegionContainer").invoke(platform);

                worldGuardEnabled = true;
                plugin.getLogger().info("WorldGuard integration enabled");
            } catch (Exception e) {
                plugin.getLogger().warning("WorldGuard detected but integration failed: " + e.getMessage());
                worldGuardEnabled = false;
            }
        }

        // GriefPrevention detection
        Plugin gp = plugin.getServer().getPluginManager().getPlugin("GriefPrevention");
        if (gp != null && gp.isEnabled()) {
            try {
                Class<?> gpClass = Class.forName("me.ryanhamshire.GriefPrevention.GriefPrevention");
                Object gpInstance = gpClass.getMethod("instance").invoke(null);
                gpDataStore = gpClass.getMethod("dataStore").invoke(gpInstance);

                griefPreventionEnabled = true;
                plugin.getLogger().info("GriefPrevention integration enabled");
            } catch (Exception e) {
                plugin.getLogger().warning("GriefPrevention detected but integration failed: " + e.getMessage());
                griefPreventionEnabled = false;
            }
        }

        if (!worldGuardEnabled && !griefPreventionEnabled) {
            plugin.debug("No region plugins detected - spawning everywhere allowed");
        }
    }

    /**
     * Check if spawning is allowed at location (for gifts, mobs, decorations)
     */
    public boolean canSpawnAt(Location location) {
        if (!plugin.getConfig().getBoolean("regionIntegration.enabled", true)) {
            return true;
        }

        // Check WorldGuard
        if (worldGuardEnabled) {
            boolean wgResult = checkWorldGuard(location);
            plugin.debug("WorldGuard check at " + formatLoc(location) + " - Result: " + wgResult);
            if (!wgResult) {
                return false;
            }
        }

        // Check GriefPrevention
        if (griefPreventionEnabled) {
            boolean gpResult = checkGriefPrevention(location);
            plugin.debug("GriefPrevention check at " + formatLoc(location) + " - Result: " + gpResult);
            if (!gpResult) {
                return false;
            }
        }

        return true;
    }

    /**
     * Check if a player can interact at a specific location (for gift pickup etc.)
     */
    public boolean canInteract(Player player, Location location) {
        if (!plugin.getConfig().getBoolean("regionIntegration.enabled", true)) {
            return true;
        }

        // Check WorldGuard
        if (worldGuardEnabled && !checkWorldGuardPlayer(player, location)) {
            return false;
        }

        // Check GriefPrevention
        if (griefPreventionEnabled && !checkGriefPreventionPlayer(player, location)) {
            return false;
        }

        return true;
    }

    // ==================== WorldGuard ====================

    /**
     * WorldGuard spawn check (reflection-based)
     */
    private boolean checkWorldGuard(Location location) {
        try {
            boolean allowInProtected = plugin.getConfig().getBoolean("regionIntegration.worldGuard.allowInProtected", false);
            if (allowInProtected) {
                return true;
            }

            // Get RegionManager for the world
            Object regionManager = regionContainer.getClass()
                    .getMethod("get", org.bukkit.World.class)
                    .invoke(regionContainer, location.getWorld());

            if (regionManager == null) {
                return true; // No regions in this world
            }

            // Convert Bukkit Location to WorldEdit BlockVector3
            Class<?> vector3Class = Class.forName("com.sk89q.worldedit.math.BlockVector3");
            Object vector = vector3Class.getMethod("at", double.class, double.class, double.class)
                    .invoke(null, location.getX(), location.getY(), location.getZ());

            // Get applicable regions at location
            Object applicableRegions = regionManager.getClass()
                    .getMethod("getApplicableRegions", vector3Class)
                    .invoke(regionManager, vector);

            int regionCount = (int) applicableRegions.getClass().getMethod("size").invoke(applicableRegions);

            // Regions exist and allowInProtected is false → deny
            return regionCount == 0;

        } catch (Exception e) {
            plugin.debug("WorldGuard check failed: " + e.getMessage());
            return true; // Default to allow on error
        }
    }

    /**
     * WorldGuard player interaction check
     */
    private boolean checkWorldGuardPlayer(Player player, Location location) {
        try {
            if (player.hasPermission("worldguard.region.bypass.*") || player.isOp()) {
                return true;
            }

            Object regionManager = regionContainer.getClass()
                    .getMethod("get", org.bukkit.World.class)
                    .invoke(regionContainer, location.getWorld());

            if (regionManager == null) {
                return true;
            }

            Class<?> vector3Class = Class.forName("com.sk89q.worldedit.math.BlockVector3");
            Object vector = vector3Class.getMethod("at", double.class, double.class, double.class)
                    .invoke(null, location.getX(), location.getY(), location.getZ());

            Object applicableRegions = regionManager.getClass()
                    .getMethod("getApplicableRegions", vector3Class)
                    .invoke(regionManager, vector);

            int regionCount = (int) applicableRegions.getClass().getMethod("size").invoke(applicableRegions);
            return regionCount == 0;

        } catch (Exception e) {
            plugin.debug("WorldGuard player check failed: " + e.getMessage());
            return true;
        }
    }

    // ==================== GriefPrevention ====================

    /**
     * GriefPrevention spawn check
     */
    private boolean checkGriefPrevention(Location location) {
        try {
            boolean allowInClaims = plugin.getConfig().getBoolean("regionIntegration.griefPrevention.allowInClaims", false);
            if (allowInClaims) {
                return true;
            }

            // Get claim at location
            Object claim = gpDataStore.getClass()
                    .getMethod("getClaimAt", Location.class)
                    .invoke(gpDataStore, location);

            if (claim == null) {
                return true; // No claim → allow
            }

            // Check if it's an admin claim
            boolean allowInAdminClaims = plugin.getConfig().getBoolean("regionIntegration.griefPrevention.allowInAdminClaims", false);
            if (allowInAdminClaims) {
                try {
                    boolean isAdminClaim = (boolean) claim.getClass().getMethod("isAdminClaim").invoke(claim);
                    if (isAdminClaim) {
                        return true;
                    }
                } catch (Exception ignored) {}
            }

            return false; // In claim → deny

        } catch (Exception e) {
            plugin.debug("GriefPrevention check failed: " + e.getMessage());
            return true;
        }
    }

    /**
     * GriefPrevention player interaction check
     */
    private boolean checkGriefPreventionPlayer(Player player, Location location) {
        try {
            Object claim = gpDataStore.getClass()
                    .getMethod("getClaimAt", Location.class)
                    .invoke(gpDataStore, location);

            if (claim == null) {
                return true; // No claim → allow
            }

            // Check if player has build permission in claim
            String denyReason = (String) claim.getClass()
                    .getMethod("allowBuild", Player.class, org.bukkit.Material.class)
                    .invoke(claim, player, org.bukkit.Material.GRASS_BLOCK);

            // allowBuild returns null if allowed, error message if denied
            return denyReason == null;

        } catch (Exception e) {
            plugin.debug("GriefPrevention player check failed: " + e.getMessage());
            return true;
        }
    }

    // ==================== Status ====================

    public boolean isEnabled() {
        return worldGuardEnabled || griefPreventionEnabled;
    }

    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        if (worldGuardEnabled) sb.append("WorldGuard: Enabled");
        if (griefPreventionEnabled) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("GriefPrevention: Enabled");
        }
        if (sb.length() == 0) sb.append("No region plugins detected");
        return sb.toString();
    }

    private String formatLoc(Location loc) {
        return loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }
}
