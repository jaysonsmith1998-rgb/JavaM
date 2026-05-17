package com.shangrila.village;

import com.shangrila.pipeline.ChamberPipeline.RegionContext;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-region village placement.
 *
 * <p><b>Current status: stub.</b> The pipeline architecture is in place and
 * wired up correctly — when this is filled in, it will:
 * <ol>
 *   <li>On first contact with a region, invoke vanilla jigsaw at the region
 *       center using the {@code shangri_la:cherry_cavern/village_start} pool
 *       to produce a deterministic piece list (cached per region).</li>
 *   <li>For each piece, resolve a Y by sampling the cavern's pure
 *       {@code density()} function over the piece's XZ footprint and picking
 *       the modal floor Y.</li>
 *   <li>Apply acceptance rules: each footprint cell must be within
 *       [-1, +2] of the piece's chosen Y, and pillaring (cells needing fill
 *       below the piece) must be {@literal <=} 10% of the footprint.</li>
 *   <li>Per chunk pass, write the slices of accepted pieces whose bounding
 *       boxes overlap this chunk, plus 1–2 block stone pillars under
 *       pillared cells.</li>
 * </ol>
 *
 * <p>The trickiest bit — invoking vanilla jigsaw programmatically without
 * going through the structure system — has a wide API surface in 26.1
 * (ChunkGenerator, RandomState, StructureTemplateManager, etc.) that I'm
 * deferring to the next iteration so the pipeline architecture can be
 * validated independently.
 */
public final class VillagePlanner {

    /** Per-region cached village plan (keyed by region cell). Empty until impl lands. */
    private static final ConcurrentMap<Long, Object> CACHE = new ConcurrentHashMap<>();

    private VillagePlanner() {}

    public static void placeForChunk(ServerLevel level, LevelChunk chunk, RegionContext region) {
        // TODO: implement per the docstring above.
        // For now: no village pieces are placed.
        //
        // The pipeline still runs every other step around this stub — the
        // cavern carves, insulates, surfaces, gets water, and is biome-tagged.
        // Use /shangrila locate to find a cavern and verify the shape, then
        // we'll fill in this method.
    }

    /** Clear the village plan cache. Useful for /reload-style debugging. */
    public static void invalidateCache() {
        CACHE.clear();
    }
}
