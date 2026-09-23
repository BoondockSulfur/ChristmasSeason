package de.boondocksulfur.christmas.util;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Finds safe surface positions for gifts, decorations and event mobs.
 * Must be called on the thread that owns the surrounding chunks.
 */
public final class SpawnUtil {

    /** A candidate is rejected when it lies more than this many blocks above the player (roofs, tree tops). */
    private static final int MAX_HEIGHT_ABOVE_CENTER = 24;

    private SpawnUtil() {}

    /**
     * Finds a safe spawn location near {@code center}.
     *
     * @param w        world
     * @param center   search centre (usually the player position)
     * @param radius   maximum horizontal offset in blocks
     * @param attempts number of random candidates to test
     * @return a safe location, or {@code null} if none of the candidates qualified
     */
    public static Location findSafeSpawnLocation(World w, Location center, int radius, int attempts) {
        return findSafeSpawnLocation(w, center, radius, attempts, false);
    }

    /**
     * Finds a safe spawn location near {@code center}.
     *
     * @param noWater {@code true} = reject candidates with water/lava within 3 blocks (snow golems)
     * @return a safe location, or {@code null} if none of the candidates qualified
     *         (callers must skip the spawn in that case - never fall back to the player position)
     */
    public static Location findSafeSpawnLocation(World w, Location center, int radius, int attempts, boolean noWater) {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int i = 0; i < attempts; i++) {
            int offsetX = random.nextInt(radius * 2 + 1) - radius;
            int offsetZ = random.nextInt(radius * 2 + 1) - radius;
            Location testLoc = center.clone().add(offsetX, 0, offsetZ);

            Location surface = findSurface(w, testLoc);
            if (surface == null) continue;

            if (isSafeSpawnLocation(surface, center, noWater)) {
                return surface;
            }
        }
        return null;
    }

    /**
     * Checks whether a location is safe for spawning:
     * <ul>
     *   <li>two blocks of air</li>
     *   <li>solid, non-hazardous ground</li>
     *   <li>not walled in (at most two solid sides)</li>
     *   <li>not far above the player (roofs, tree tops, cliffs)</li>
     *   <li>open sky (not under a roof or canopy)</li>
     *   <li>optionally no water/lava nearby</li>
     * </ul>
     */
    private static boolean isSafeSpawnLocation(Location loc, Location center, boolean noWater) {
        World w = loc.getWorld();
        if (w == null) return false;

        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        if (y > center.getBlockY() + MAX_HEIGHT_ABOVE_CENTER) {
            return false;
        }

        Block air1 = w.getBlockAt(x, y, z);
        Block air2 = w.getBlockAt(x, y + 1, z);
        if (!air1.getType().isAir() || !air2.getType().isAir()) {
            return false;
        }

        if (!isGoodGround(w.getBlockAt(x, y - 1, z).getType())) {
            return false;
        }

        int solidSides = 0;
        Block[] sides = {
            w.getBlockAt(x + 1, y, z),
            w.getBlockAt(x - 1, y, z),
            w.getBlockAt(x, y, z + 1),
            w.getBlockAt(x, y, z - 1)
        };
        for (Block side : sides) {
            if (side.getType().isSolid()) solidSides++;
        }
        if (solidSides >= 3) {
            return false;
        }

        if (noWater && hasLiquidNearby(w, x, y, z, 3)) {
            return false;
        }

        // 15 = full daylight, 12+ = most likely outdoors, below = under a roof or tree
        return air1.getLightFromSky() >= 12;
    }

    /** @return {@code true} if water or lava exists within the given radius (one block up/down included). */
    private static boolean hasLiquidNearby(World w, int centerX, int centerY, int centerZ, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    Material type = w.getBlockAt(centerX + dx, centerY + dy, centerZ + dz).getType();
                    if (type == Material.WATER || type == Material.LAVA) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Walks down from the highest block until solid ground is found and returns the
     * first air block above it.
     *
     * @return surface location, or {@code null} if no usable surface exists in this column
     */
    public static Location findSurface(World w, Location around) {
        int x = around.getBlockX();
        int z = around.getBlockZ();

        Block ground = w.getHighestBlockAt(x, z);

        // Walk down until we hit "good" ground (no water, lava, leaves, fire, powder snow)
        while (ground.getY() > w.getMinHeight() && !isGoodGround(ground.getType())) {
            ground = ground.getRelative(0, -1, 0);
        }
        if (!isGoodGround(ground.getType())) {
            return null;
        }

        // Need two blocks of air for entities/chests
        Block a = ground.getRelative(0, 1, 0);
        Block a2 = ground.getRelative(0, 2, 0);
        int maxY = w.getMaxHeight() - 2;

        while ((!a.getType().isAir() || !a2.getType().isAir()) && a.getY() < maxY) {
            a = a.getRelative(0, 1, 0);
            a2 = a.getRelative(0, 1, 0);
        }
        if (!a.getType().isAir() || !a2.getType().isAir()) {
            return null;
        }

        return new Location(w, x + 0.5, a.getY(), z + 0.5);
    }

    private static boolean isGoodGround(Material m) {
        if (!m.isSolid()) return false;
        if (m == Material.WATER || m == Material.LAVA) return false;
        if (m == Material.CACTUS || m == Material.FIRE || m == Material.MAGMA_BLOCK) return false;
        if (m == Material.POWDER_SNOW) return false;
        String n = m.name();
        if (n.endsWith("_LEAVES")) return false;
        if (n.endsWith("_LOG")) return false;
        return true;
    }
}
