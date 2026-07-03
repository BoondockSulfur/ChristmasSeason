package de.boondocksulfur.christmas.util;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

public final class SpawnUtil {

    private SpawnUtil() {}

    /**
     * Findet eine sichere Spawn-Location für Entities (Standard)
     * Versucht mehrere zufällige Positionen und prüft ob sie sicher sind (keine Wände, genug Platz)
     *
     * @param w World
     * @param center Zentrum der Suche
     * @param radius Radius für zufällige Offsets
     * @param attempts Anzahl Versuche
     * @return Sichere Location oder Fallback zu findSurface
     */
    public static Location findSafeSpawnLocation(World w, Location center, int radius, int attempts) {
        return findSafeSpawnLocation(w, center, radius, attempts, false);
    }

    /**
     * Findet eine sichere Spawn-Location für Entities
     * Versucht mehrere zufällige Positionen und prüft ob sie sicher sind
     *
     * @param w World
     * @param center Zentrum der Suche
     * @param radius Radius für zufällige Offsets
     * @param attempts Anzahl Versuche
     * @param noWater true = Schneemänner (kein Wasser in 3x3 Radius), false = Normal
     * @return Sichere Location oder Fallback zu findSurface
     */
    public static Location findSafeSpawnLocation(World w, Location center, int radius, int attempts, boolean noWater) {
        java.util.Random random = new java.util.Random();

        for (int i = 0; i < attempts; i++) {
            int offsetX = random.nextInt(radius * 2 + 1) - radius;
            int offsetZ = random.nextInt(radius * 2 + 1) - radius;
            Location testLoc = center.clone().add(offsetX, 0, offsetZ);

            Location surface = findSurface(w, testLoc);

            // Prüfe ob die Location sicher ist (keine Wände drumherum, genug Platz)
            if (isSafeSpawnLocation(surface, noWater)) {
                return surface;
            }
        }

        // Fallback: Verwende alte Logik
        return findSurface(w, center);
    }

    /**
     * Prüft ob eine Location sicher für Entity-Spawning ist
     * - Genug Luftraum (2 Blöcke hoch)
     * - Keine Wände direkt daneben (mind. 1 Seite offen)
     * - Guter Boden darunter
     * - Kein Wasser/Lava in Nähe
     * - Nicht auf Dächern/Bäumen (Licht-Check)
     * - Nicht zu hoch (Y < 100 = nicht auf Bergen/Baumkronen)
     *
     * @param loc Location zum Prüfen
     * @param noWater true = Strengerer Wasser-Check (3x3 Radius für Schneemänner)
     */
    private static boolean isSafeSpawnLocation(Location loc, boolean noWater) {
        World w = loc.getWorld();
        if (w == null) return false;

        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        // FIX: Nicht zu hoch spawnen (Y > 100 = wahrscheinlich Dach/Baum/Berg)
        if (y > 100) {
            return false;
        }

        // Prüfe Luftraum (2 Blöcke hoch)
        Block air1 = w.getBlockAt(x, y, z);
        Block air2 = w.getBlockAt(x, y + 1, z);
        Material m1 = air1.getType();
        Material m2 = air2.getType();

        // Muss Luft sein (Wasser/Lava sind nicht Air)
        if (!m1.isAir() || !m2.isAir()) {
            return false;
        }

        // Prüfe Boden darunter
        Block ground = w.getBlockAt(x, y - 1, z);
        Material groundType = ground.getType();
        if (!isGoodGround(groundType)) {
            return false;
        }

        // Prüfe horizontale Umgebung (mindestens 2 Seiten müssen frei sein)
        int solidSides = 0;
        Block[] sides = {
            w.getBlockAt(x + 1, y, z),
            w.getBlockAt(x - 1, y, z),
            w.getBlockAt(x, y, z + 1),
            w.getBlockAt(x, y, z - 1)
        };

        for (Block side : sides) {
            if (side.getType().isSolid()) {
                solidSides++;
            }
        }

        // Wenn 3+ Seiten zu sind = eingemauert
        if (solidSides >= 3) {
            return false;
        }

        // FIX: Schneemänner brauchen strengeren Wasser-Check (3x3 Radius)
        if (noWater && hasWaterNearby(w, x, y, z, 3)) {
            return false;
        }

        // FIX: Erhöhter Licht-Check für "komplett draußen" (nicht unter Bäumen/Dächern)
        // 15 = volle Helligkeit, 12+ = wahrscheinlich draußen, < 12 = unter Dach/Baum
        if (air1.getLightFromSky() < 12) {
            return false;
        }

        return true;
    }

    /**
     * Prüft ob Wasser in einem bestimmten Radius vorhanden ist
     * Wichtig für Schneemänner die nicht ins Wasser dürfen
     *
     * @param w World
     * @param centerX Zentrum X
     * @param centerY Zentrum Y
     * @param centerZ Zentrum Z
     * @param radius Radius zum Prüfen (z.B. 3 für 3x3)
     * @return true wenn Wasser gefunden wurde
     */
    private static boolean hasWaterNearby(World w, int centerX, int centerY, int centerZ, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -1; dy <= 1; dy++) { // Prüfe auch 1 Block über/unter
                    Block block = w.getBlockAt(centerX + dx, centerY + dy, centerZ + dz);
                    Material type = block.getType();
                    if (type == Material.WATER || type == Material.LAVA) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static Location findSurface(World w, Location around) {
        int x = around.getBlockX();
        int z = around.getBlockZ();

        Block top = w.getHighestBlockAt(x, z); // oberste sinnvolle Oberfläche
        Block ground = top;

        // runter, bis wir „guten“ Boden haben (kein Wasser/Lava/Laub/Feuer/Pulverschnee)
        while (ground.getY() > w.getMinHeight() && !isGoodGround(ground.getType())) {
            ground = ground.getRelative(0, -1, 0);
        }

        // zwei Blöcke Luft für Entities/Chest etc.
        Block a = ground.getRelative(0, 1, 0);
        Block a2 = ground.getRelative(0, 2, 0);
        int maxY = w.getMaxHeight() - 2;

        while ((!a.getType().isAir() || !a2.getType().isAir()) && a.getY() < maxY) {
            a = a.getRelative(0, 1, 0);
            a2 = a.getRelative(0, 1, 0);
        }

        // kleine Sicherheitskorrektur: lieber Oberfläche als Höhle
        if (a.getLightFromSky() == 0) {
            top = w.getHighestBlockAt(x, z);
            a = top.getLocation().add(0, 1, 0).getBlock();
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
