package de.boondocksulfur.christmas.util;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Biome;

/**
 * Central access to Paper registries. Replaces the deprecated
 * {@code org.bukkit.Registry#BIOME} with the {@link RegistryAccess} API.
 */
public final class Registries {

    private Registries() {}

    public static Registry<Biome> biomes() {
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);
    }

    /**
     * Resolves a biome from a user-supplied name. Accepts plain vanilla names
     * ("snowy_plains", "SNOWY_PLAINS") as well as fully namespaced keys
     * ("terralith:alpine_grove").
     *
     * @return the biome, or {@code null} if the name is unknown or malformed
     */
    public static Biome biomeByName(String name) {
        if (name == null || name.isBlank()) return null;
        String normalized = name.trim().toLowerCase();
        try {
            NamespacedKey key = normalized.contains(":")
                    ? NamespacedKey.fromString(normalized)
                    : NamespacedKey.minecraft(normalized);
            if (key == null) return null;
            return biomes().get(key);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
