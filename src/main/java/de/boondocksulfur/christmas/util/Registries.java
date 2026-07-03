package de.boondocksulfur.christmas.util;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Registry;
import org.bukkit.block.Biome;

/**
 * Zentraler Zugriff auf Paper-Registries.
 * Ersetzt das deprecated org.bukkit.Registry#BIOME durch die
 * moderne RegistryAccess-API (Paper 1.20.6+).
 */
public final class Registries {

    private Registries() {}

    public static Registry<Biome> biomes() {
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);
    }
}
