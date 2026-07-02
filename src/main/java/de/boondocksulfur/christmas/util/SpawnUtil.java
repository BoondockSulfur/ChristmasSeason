package de.boondocksulfur.christmas.util;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

public final class SpawnUtil {

    private SpawnUtil() {}

    /**
     * Findet eine sichere Spawn-Location für Entities
     * Versucht mehrere zufällige Positionen und prüft ob sie sicher sind (keine Wände, genug Platz)
     *
     * @param w World
     * @param center Zentrum der Suche
     * @param radius Radius für zufällige Offsets
     * @param attempts Anzahl Versuche
     * @return Sichere Location oder Fallback zu findSurface
     */
    public static Location findSafeSpawnLocation(World w, Location center, int radius, int attempts) {
        java.util.Random random = new java.util.Random();

        for (int i = 0; i < attempts; i++) {
            int offsetX = random.nextInt(radius * 2 + 1) - radius;
            int offsetZ = random.nextInt(radius * 2 + 1) - radius;
            Location testLoc = center.clone().add(offsetX, 0, offsetZ);

            Location surface = findSurface(w, testLoc);

            // Prüfe ob die Location sicher ist (keine Wände drumherum, genug Platz)
            if (isSafeSpawnLocation(surface)) {
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
     */
    private static boolean isSafeSpawnLocation(Location loc) {
        World w = loc.getWorld();
        if (w == null) return false;

        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        // Prüfe Luftraum (2 Blöcke hoch) - darf kein Wasser/Lava sein!
        Block air1 = w.getBlockAt(x, y, z);
        Block air2 = w.getBlockAt(x, y + 1, z);
        Material m1 = air1.getType();
        Material m2 = air2.getType();

        // WICHTIG: Wasser und Lava ausschließen!
        if (!m1.isAir() || !m2.isAir()) {
            return false;
        }
        if (m1 == Material.WATER || m1 == Material.LAVA) {
            return false;
        }
        if (m2 == Material.WATER || m2 == Material.LAVA) {
            return false;
        }

        // Prüfe Boden darunter
        Block ground = w.getBlockAt(x, y - 1, z);
        Material groundType = ground.getType();
        if (!isGoodGround(groundType)) {
            return false;
        }
        // Kein Wasser/Lava als Boden!
        if (groundType == Material.WATER || groundType == Material.LAVA) {
            return false;
        }

        // Prüfe horizontale Umgebung (mindestens 2 Seiten müssen frei sein, nicht eingemauert)
        int solidSides = 0;
        int waterSides = 0;
        Block[] sides = {
            w.getBlockAt(x + 1, y, z),
            w.getBlockAt(x - 1, y, z),
            w.getBlockAt(x, y, z + 1),
            w.getBlockAt(x, y, z - 1)
        };

        for (Block side : sides) {
            Material sideType = side.getType();
            if (sideType.isSolid()) {
                solidSides++;
            }
            // Zähle Wasser/Lava als schlecht
            if (sideType == Material.WATER || sideType == Material.LAVA) {
                waterSides++;
            }
        }

        // Wenn 3+ Seiten zu sind oder Wasser in Nähe = unsicher
        if (solidSides >= 3) {
            return false;
        }
        if (waterSides > 0) {
            return false; // Kein Wasser in direkter Nähe!
        }

        // Bevorzuge offene Flächen (nicht auf Dächern/Bäumen/in Höhlen)
        // Wenn Licht von oben kommt, ist es wahrscheinlich draußen
        if (air1.getLightFromSky() < 8) {
            return false; // Zu dunkel = wahrscheinlich drinnen oder unter Baum
        }

        return true;
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
