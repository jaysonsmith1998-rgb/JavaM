package com.shangrila.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.carver.CarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.WorldCarver;

import java.util.function.Function;
import net.minecraft.world.level.ChunkPos;

/**
 * A bounded chamber carver. For each column inside a Shangri-La region's
 * footprint, carves air from the floor (from {@link ShangriLaRegion#floorHeight})
 * up to the ceiling (from {@link ShangriLaRegion#ceilingHeight}).
 *
 * <p>The carving is strictly contained: it never extends outside the region's
 * circular footprint, never carves above the ceiling Y (so the natural overburden
 * stays put and seals aquifers), and never carves below the dynamic floor (the
 * bowl-with-noise terrain stays solid).
 *
 * <p>Where the floor dips below {@link ShangriLaRegion#WATER_LEVEL}, the column
 * is filled with water from floor up to water level — producing ponds and lakes
 * at the deepest spots of each chamber.
 */
public class ChamberCarver extends WorldCarver<CarverConfiguration> {

    private static final BlockState AIR = Blocks.CAVE_AIR.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();

    public ChamberCarver() {
        super(CarverConfiguration.CODEC.codec());
    }

    @Override
    public boolean isStartChunk(CarverConfiguration config, RandomSource random) {
        // Every chunk gets a chance to evaluate. The per-chunk overlap test
        // inside carve() short-circuits fast for non-region chunks.
        return true;
    }

    @Override
    public boolean carve(
            CarvingContext ctx,
            CarverConfiguration config,
            ChunkAccess chunk,
            Function<BlockPos, Holder<Biome>> posToBiome,
            RandomSource random,
            Aquifer aquifer,
            ChunkPos chunkPos,
            CarvingMask mask
    ) {
        int chunkMinX = chunkPos.getMinBlockX();
        int chunkMinZ = chunkPos.getMinBlockZ();
        int chunkMaxX = chunkMinX + 15;
        int chunkMaxZ = chunkMinZ + 15;

        if (!chunkOverlapsAnyRegion(chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ)) {
            return false;
        }

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        long salt = ShangriLaRegion.DEFAULT_SALT;
        boolean carved = false;

        for (int x = chunkMinX; x <= chunkMaxX; x++) {
            for (int z = chunkMinZ; z <= chunkMaxZ; z++) {
                if (!ShangriLaRegion.horizontalContains(x, z, salt)) continue;

                int floorY = ShangriLaRegion.floorHeight(x, z, salt);
                int ceilingY = ShangriLaRegion.ceilingHeight(x, z, salt);
                if (floorY >= ceilingY) continue;

                // Air column from (floor + 1) to ceiling.
                for (int y = floorY + 1; y <= ceilingY; y++) {
                    pos.set(x, y, z);
                    BlockState current = chunk.getBlockState(pos);
                    if (current.isAir()) continue;
                    if (current.getBlock() == Blocks.BEDROCK) continue;
                    chunk.setBlockState(pos, AIR, 0);
                    carved = true;
                }

                // Fill below water level with water (creates ponds where the
                // floor dips deep). Water replaces only the air we just placed,
                // not the solid floor block.
                if (floorY < ShangriLaRegion.WATER_LEVEL) {
                    for (int y = floorY + 1; y <= ShangriLaRegion.WATER_LEVEL; y++) {
                        pos.set(x, y, z);
                        if (chunk.getBlockState(pos).isAir()) {
                            chunk.setBlockState(pos, WATER, 0);
                        }
                    }
                }
            }
        }
        return carved;
    }

    /** Cheap test: does any sample point in this chunk lie in a region's footprint? */
    private static boolean chunkOverlapsAnyRegion(int minX, int minZ, int maxX, int maxZ) {
        long salt = ShangriLaRegion.DEFAULT_SALT;
        if (ShangriLaRegion.horizontalContains(minX, minZ, salt)) return true;
        if (ShangriLaRegion.horizontalContains(maxX, minZ, salt)) return true;
        if (ShangriLaRegion.horizontalContains(minX, maxZ, salt)) return true;
        if (ShangriLaRegion.horizontalContains(maxX, maxZ, salt)) return true;
        int cx = (minX + maxX) / 2;
        int cz = (minZ + maxZ) / 2;
        return ShangriLaRegion.horizontalContains(cx, cz, salt);
    }
}
