package com.shangrila.water;

import com.shangrila.pipeline.ChamberPipeline;
import com.shangrila.pipeline.ChamberPipeline.RegionContext;
import com.shangrila.world.ShangriLaRegion;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Places water features inside the cavern's water-headroom zone (Y below
 * {@link ShangriLaRegion#VALLEY_FLOOR_Y}). Lakes are deterministic per region
 * so different chunks of the same region see the same water layout.
 *
 * <p>This is intentionally minimal for now: water fills any cavern-air block
 * within the headroom zone, up to {@link ShangriLaRegion#VALLEY_FLOOR_Y}. The
 * deeper sub-volume created by the asymmetric Y bias becomes a reservoir of
 * water that the valley sits above. Surface ponds and rivers belong in the
 * terrain-modifications step where they can be designed to interact with the
 * valley floor directly.
 *
 * <p>Future expansion: per-region randomized lake centers with limited blast
 * radii (so not all of the headroom is water), waterfalls connecting to the
 * valley surface, etc. The {@link #placeForChunk} entry point won't change.
 */
public final class WaterPlanner {

    private static final BlockState WATER = Blocks.WATER.defaultBlockState();

    private WaterPlanner() {}

    /** Place water features for the portion of this region that lies in this chunk. */
    public static void placeForChunk(ServerLevel level, LevelChunk chunk, RegionContext region) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        long rSq = (long) ShangriLaRegion.REGION_RADIUS_BLOCKS
                 * ShangriLaRegion.REGION_RADIUS_BLOCKS;

        // Fill cavern-air in the headroom zone (below valley floor) with water,
        // up to one block below the valley floor — that block stays air to give
        // the player a thin air gap before they hit water from above.
        int yMin = ShangriLaRegion.CHAMBER_Y_BOTTOM + 1;     // +1 to leave the carved stone bottom in place
        int yMax = ShangriLaRegion.VALLEY_FLOOR_Y - 1;

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = minX + dx;
                int z = minZ + dz;
                long ddx = x - region.rcx();
                long ddz = z - region.rcz();
                if (ddx * ddx + ddz * ddz > rSq) continue;

                for (int y = yMin; y <= yMax; y++) {
                    pos.set(x, y, z);
                    if (chunk.getBlockState(pos).isAir()) {
                        chunk.setBlockState(pos, WATER, ChamberPipeline.WRITE_FLAGS);
                    }
                }
            }
        }
    }
}
