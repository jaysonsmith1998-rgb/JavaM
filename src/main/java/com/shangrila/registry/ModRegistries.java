package com.shangrila.registry;

import com.shangrila.ShangriLaMod;
import com.shangrila.world.ChamberCarver;
import com.shangrila.world.ShangriLaBiomeSource;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.levelgen.carver.CarverConfiguration;
import net.minecraft.world.level.levelgen.carver.WorldCarver;

/**
 * Static registration of the mod's worldgen components.
 *
 * <p>Both registrations target {@link BuiltInRegistries} (static, loaded at JVM
 * startup) rather than dynamic registries. This is correct because:
 * <ul>
 *   <li>{@link BuiltInRegistries#BIOME_SOURCE} stores the <em>codec type</em>, not
 *       a specific biome source instance — same as how {@code minecraft:multi_noise}
 *       is registered.</li>
 *   <li>{@link BuiltInRegistries#CARVER} stores the carver implementation type;
 *       configured carver instances live in the dynamic registry and are loaded
 *       from JSON files in {@code data/.../worldgen/configured_carver/}.</li>
 * </ul>
 *
 * <p>This is called once from {@link ShangriLaMod#onInitialize()}.
 */
public final class ModRegistries {

    public static final WorldCarver<CarverConfiguration> CHAMBER_CARVER = new ChamberCarver();

    private ModRegistries() {}

    public static void bootstrap() {
        // Custom biome source: registered as the MapCodec, keyed by ID. Datapack
        // dimension JSON references this via "type": "shangri_la:shangri_la".
        Registry.register(
                BuiltInRegistries.BIOME_SOURCE,
                ShangriLaMod.id("shangri_la"),
                ShangriLaBiomeSource.CODEC
        );

        // Custom carver: registered as the WorldCarver instance. Configured carver
        // JSON references this via "type": "shangri_la:chamber".
        Registry.register(
                BuiltInRegistries.CARVER,
                ShangriLaMod.id("chamber"),
                CHAMBER_CARVER
        );
    }
}
