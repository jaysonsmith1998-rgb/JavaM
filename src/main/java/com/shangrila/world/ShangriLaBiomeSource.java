package com.shangrila.world;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryFixedCodec;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.stream.Stream;

/**
 * A {@link BiomeSource} that wraps an inner vanilla biome source and overrides
 * biome lookups within Shangri-La region chambers to return a configurable cave
 * biome (always {@code shangri_la:cherry_cavern} in this pack).
 *
 * <p>Region placement is determined by {@link ShangriLaRegion}, with a fixed salt
 * so regions live at predictable world coordinates across worlds.
 *
 * <p>The codec for this class is registered in {@code ModRegistries} and the source
 * is referenced from {@code data/minecraft/dimension/overworld.json} as:
 * <pre>
 * "biome_source": {
 *   "type": "shangri_la:shangri_la",
 *   "cave_biome": "shangri_la:cherry_cavern",
 *   "wrapped": {
 *     "type": "minecraft:multi_noise",
 *     "preset": "minecraft:overworld"
 *   }
 * }
 * </pre>
 */
public class ShangriLaBiomeSource extends BiomeSource {

    public static final MapCodec<ShangriLaBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            RegistryFixedCodec.create(Registries.BIOME).fieldOf("cave_biome").forGetter(s -> s.caveBiome),
            BiomeSource.CODEC.fieldOf("wrapped").forGetter(s -> s.wrapped)
    ).apply(instance, ShangriLaBiomeSource::new));

    private final Holder<Biome> caveBiome;
    private final BiomeSource wrapped;

    public ShangriLaBiomeSource(Holder<Biome> caveBiome, BiomeSource wrapped) {
        this.caveBiome = caveBiome;
        this.wrapped = wrapped;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        // Declare every biome the wrapped source can return, plus our cave biome.
        // Without this, feature gen and the structure system don't know they can
        // place features that target shangri_la:cherry_cavern.
        return Stream.concat(wrapped.possibleBiomes().stream(), Stream.of(caveBiome));
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        // Biome sampling happens at 4-block resolution (quart coords): x, y, z are
        // *quart* positions when called by the chunk generator. Convert to block
        // coords by left-shifting by 2 for the region containment check.
        int blockX = x << 2;
        int blockY = y << 2;
        int blockZ = z << 2;
        if (ShangriLaRegion.contains(blockX, blockY, blockZ, ShangriLaRegion.DEFAULT_SALT)) {
            return caveBiome;
        }
        return wrapped.getNoiseBiome(x, y, z, sampler);
    }
}
