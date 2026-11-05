package de.boondocksulfur.christmas.integration;

import de.boondocksulfur.christmas.ChristmasSeason;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * Optional WorldGuard and GriefPrevention support (reflection based, soft dependency).
 * Used to keep gifts, decorations and event mobs out of protected regions and claims.
 *
 * <p>Every reflective member is resolved once on startup, from the API types rather than
 * the implementation classes. An incompatible plugin version therefore shows up as a
 * warning on startup instead of silently failing on each check.
 */
public class RegionIntegration {

    private final ChristmasSeason plugin;
    private boolean worldGuardEnabled = false;
    private boolean griefPreventionEnabled = false;

    // WorldGuard integration (cached via reflection)
    private Object regionContainer;
    private Method wgAdaptWorld;
    private Method wgGetRegionManager;
    private Method wgVectorAt;
    private Method wgGetApplicableRegions;
    private Method wgRegionSetSize;
    private Method wgGetRegion;
    private Method wgIntersectingRegions;
    private java.lang.reflect.Constructor<?> wgCuboidRegion;

    // GriefPrevention integration (cached via reflection)
    private Object gpDataStore;
    private Method gpGetClaimAt;
    private Method gpGetClaimsInChunk;
    private Method gpLesserCorner;
    private Method gpIsAdminClaim;
    private Method gpAllowBuild;

    /** Failed check types already reported, so a broken integration cannot flood the console. */
    private final Set<String> reportedFailures = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
                Class<?> platformClass = Class.forName("com.sk89q.worldguard.internal.platform.WorldGuardPlatform");
                Class<?> containerClass = Class.forName("com.sk89q.worldguard.protection.regions.RegionContainer");
                Class<?> managerClass = Class.forName("com.sk89q.worldguard.protection.managers.RegionManager");
                Class<?> regionSetClass = Class.forName("com.sk89q.worldguard.protection.ApplicableRegionSet");
                Class<?> regionClass = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedRegion");
                Class<?> adapterClass = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
                Class<?> weWorldClass = Class.forName("com.sk89q.worldedit.world.World");
                Class<?> vectorClass = Class.forName("com.sk89q.worldedit.math.BlockVector3");

                wgAdaptWorld = adapterClass.getMethod("adapt", org.bukkit.World.class);
                wgGetRegionManager = containerClass.getMethod("get", weWorldClass);
                wgVectorAt = vectorClass.getMethod("at", double.class, double.class, double.class);
                wgGetApplicableRegions = managerClass.getMethod("getApplicableRegions", vectorClass);
                wgRegionSetSize = regionSetClass.getMethod("size");
                wgGetRegion = managerClass.getMethod("getRegion", String.class);
                wgIntersectingRegions = regionClass.getMethod("getIntersectingRegions", java.util.Collection.class);
                wgCuboidRegion = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion")
                        .getConstructor(String.class, vectorClass, vectorClass);

                Object wgInstance = wgClass.getMethod("getInstance").invoke(null);
                Object platform = wgClass.getMethod("getPlatform").invoke(wgInstance);
                regionContainer = platformClass.getMethod("getRegionContainer").invoke(platform);

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
                Class<?> dataStoreClass = Class.forName("me.ryanhamshire.GriefPrevention.DataStore");
                Class<?> claimClass = Class.forName("me.ryanhamshire.GriefPrevention.Claim");

                gpGetClaimAt = dataStoreClass.getMethod("getClaimAt", Location.class, boolean.class, claimClass);
                gpGetClaimsInChunk = dataStoreClass.getMethod("getClaims", int.class, int.class);
                gpLesserCorner = claimClass.getMethod("getLesserBoundaryCorner");
                gpIsAdminClaim = claimClass.getMethod("isAdminClaim");
                gpAllowBuild = claimClass.getMethod("allowBuild", Player.class, org.bukkit.Material.class);

                Object gpInstance = gpClass.getField("instance").get(null);
                gpDataStore = gpClass.getField("dataStore").get(gpInstance);

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

    /** @return the region set at the location, or {@code null} if the world has no region manager */
    private Object applicableRegions(Location location) throws ReflectiveOperationException {
        Object weWorld = wgAdaptWorld.invoke(null, location.getWorld());
        Object regionManager = wgGetRegionManager.invoke(regionContainer, weWorld);
        if (regionManager == null) {
            return null;
        }
        Object vector = wgVectorAt.invoke(null, location.getX(), location.getY(), location.getZ());
        return wgGetApplicableRegions.invoke(regionManager, vector);
    }

    /**
     * WorldGuard spawn check (reflection-based)
     */
    private boolean checkWorldGuard(Location location) {
        try {
            boolean allowInProtected = plugin.getConfig().getBoolean("regionIntegration.worldGuard.allowInProtected", false);
            if (allowInProtected) {
                return true;
            }

            Object regions = applicableRegions(location);
            return regions == null || (int) wgRegionSetSize.invoke(regions) == 0;

        } catch (ReflectiveOperationException | RuntimeException e) {
            reportFailure("WorldGuard spawn check", e);
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

            Object regions = applicableRegions(location);
            return regions == null || (int) wgRegionSetSize.invoke(regions) == 0;

        } catch (ReflectiveOperationException | RuntimeException e) {
            reportFailure("WorldGuard player check", e);
            return true;
        }
    }

    // ==================== GriefPrevention ====================

    private Object claimAt(Location location) throws ReflectiveOperationException {
        return gpGetClaimAt.invoke(gpDataStore, location, true, null);
    }

    /**
     * GriefPrevention spawn check
     */
    private boolean checkGriefPrevention(Location location) {
        try {
            boolean allowInClaims = plugin.getConfig().getBoolean("regionIntegration.griefPrevention.allowInClaims", false);
            if (allowInClaims) {
                return true;
            }

            Object claim = claimAt(location);
            if (claim == null) {
                return true; // No claim → allow
            }

            boolean allowInAdminClaims = plugin.getConfig().getBoolean("regionIntegration.griefPrevention.allowInAdminClaims", false);
            return allowInAdminClaims && (boolean) gpIsAdminClaim.invoke(claim);

        } catch (ReflectiveOperationException | RuntimeException e) {
            reportFailure("GriefPrevention spawn check", e);
            return true;
        }
    }

    /**
     * GriefPrevention player interaction check
     */
    private boolean checkGriefPreventionPlayer(Player player, Location location) {
        try {
            Object claim = claimAt(location);
            if (claim == null) {
                return true; // No claim → allow
            }

            // allowBuild returns null if allowed, error message if denied
            return gpAllowBuild.invoke(claim, player, org.bukkit.Material.GRASS_BLOCK) == null;

        } catch (ReflectiveOperationException | RuntimeException e) {
            reportFailure("GriefPrevention player check", e);
            return true;
        }
    }

    /** Warns once per check type; the check itself falls back to its default. */
    private void reportFailure(String check, Exception e) {
        if (reportedFailures.add(check)) {
            plugin.getLogger().warning(check + " failed, protection is not applied: " + e);
        }
    }

    // ==================== Biome exclusion ====================

    /**
     * Checks whether a chunk must be left alone by the biome bubble.
     *
     * <p>Sources ({@code biome.exclude.*}): rectangular areas ({@code areas}), WorldGuard
     * region IDs ({@code worldGuardRegions}, optionally {@code world:id}) and, when
     * {@code griefPreventionClaims} is set, every GriefPrevention claim. A chunk is excluded
     * as soon as any part of it touches one of them; for WorldGuard only the Y range that
     * biome changes apply to counts.
     */
    public boolean isBiomeExcluded(org.bukkit.World world, int chunkX, int chunkZ) {
        org.bukkit.configuration.ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("biome.exclude");
        if (cfg == null) return false;

        int minX = chunkX << 4, minZ = chunkZ << 4, maxX = minX + 15, maxZ = minZ + 15;

        for (java.util.Map<?, ?> area : cfg.getMapList("areas")) {
            Object w = area.get("world");
            if (w != null && !String.valueOf(w).equals(world.getName())) continue;
            int x1 = toInt(area.get("x1")), z1 = toInt(area.get("z1")), x2 = toInt(area.get("x2")), z2 = toInt(area.get("z2"));
            int ax1 = Math.min(x1, x2), ax2 = Math.max(x1, x2), az1 = Math.min(z1, z2), az2 = Math.max(z1, z2);
            if (maxX >= ax1 && minX <= ax2 && maxZ >= az1 && minZ <= az2) return true;
        }

        java.util.List<String> wgRegions = cfg.getStringList("worldGuardRegions");
        if (worldGuardEnabled && !wgRegions.isEmpty() && touchesWorldGuardRegion(world, minX, minZ, maxX, maxZ, wgRegions)) {
            return true;
        }
        return griefPreventionEnabled && cfg.getBoolean("griefPreventionClaims", false)
                && hasClaim(world, chunkX, chunkZ);
    }

    private static int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return 0; }
    }

    /** @return {@code true} if one of the listed regions intersects the block area */
    private boolean touchesWorldGuardRegion(org.bukkit.World world, int minX, int minZ, int maxX, int maxZ,
                                            java.util.List<String> regionIds) {
        try {
            Object manager = wgGetRegionManager.invoke(regionContainer, wgAdaptWorld.invoke(null, world));
            if (manager == null) return false;

            java.util.List<Object> listed = new java.util.ArrayList<>();
            for (String entry : regionIds) {
                String id = entry;
                if (id.contains(":")) {
                    String[] parts = id.split(":", 2);
                    if (!parts[0].equals(world.getName())) continue;
                    id = parts[1];
                }
                Object region = wgGetRegion.invoke(manager, id);
                if (region != null) listed.add(region);
            }
            if (listed.isEmpty()) return false;

            int minY = plugin.getBiomeSnowManager().minChangeY(world);
            int maxY = plugin.getBiomeSnowManager().maxChangeY(world) - 1;
            Object area = wgCuboidRegion.newInstance("christmas_exclusion_check",
                    wgVectorAt.invoke(null, (double) minX, (double) minY, (double) minZ),
                    wgVectorAt.invoke(null, (double) maxX, (double) maxY, (double) maxZ));
            return !((java.util.Collection<?>) wgIntersectingRegions.invoke(area, listed)).isEmpty();
        } catch (ReflectiveOperationException | RuntimeException e) {
            reportFailure("WorldGuard exclusion check", e);
            return false;
        }
    }

    /** @return {@code true} if any claim of this world touches the chunk, regardless of the spawn settings */
    private boolean hasClaim(org.bukkit.World world, int chunkX, int chunkZ) {
        try {
            // GriefPrevention indexes claims by chunk coordinates across all worlds
            for (Object claim : (java.util.Collection<?>) gpGetClaimsInChunk.invoke(gpDataStore, chunkX, chunkZ)) {
                if (world.equals(((Location) gpLesserCorner.invoke(claim)).getWorld())) return true;
            }
            return false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            reportFailure("GriefPrevention exclusion check", e);
            return false;
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
