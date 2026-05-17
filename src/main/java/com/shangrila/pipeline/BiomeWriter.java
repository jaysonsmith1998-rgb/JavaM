package com.shangrila.pipeline;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Tiny helper for writing a single biome at quart resolution into a chunk's
 * biome storage. Wraps the {@link LevelChunkSection#setBiome} call and handles
 * section-index arithmetic.
 *
 * <p>Biomes are stored per-section in {@code PalettedContainer<Holder<Biome>>}
 * at 4-block (quart) resolution. A section spans 16 blocks of Y, so it spans 4
 * quart Y indices. Quart coordinates within the section are computed mod 4.
 */
final class BiomeWriter {
    private BiomeWriter() {}

    /** Set biome at the given quart coords (x, y, z) on this chunk. */
    static void setBiomeAtQuart(LevelChunk chunk, int qx, int qy, int qz, Holder<Biome> biome) {
        int blockY = qy << 2;
        // Section index conversion: each section is 16 blocks tall.
        int sectionIndex = chunk.getSectionIndex(blockY);
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) return;
        LevelChunkSection section = chunk.getSection(sectionIndex);
        // Local quart coords within the section: (qx % 4, qy % 4, qz % 4)
        // using Java's modulo, which can be negative — guard with & 3.
        section.setBiome(qx & 3, qy & 3, qz & 3, biome);
    }
}
