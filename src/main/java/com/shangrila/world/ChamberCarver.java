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
 * A bounded chamber carver. Carves the chamber shape of any Shangri-La region
 * that overlaps the current chunk, and does nothing for chunks outside any region.
 *
 * <p>Unlike {@code minecraft:cave}, this carver produces a single contained void —
 * the carving never extends outside the region's circular footprint, regardless of
 * how the carver is otherwise configured. This is the entire reason for moving to
 * a mod.
 *
 * <p>The configuration is just the base {@link CarverConfiguration}; only the
 * {@code probability} field matters (set to 1.0 in the configured carver JSON) and
 * other fields are ignored.
 */
public class ChamberCarver extends WorldCarver<CarverConfiguration> {

    private static final BlockState AIR = Blocks.CAVE_AIR.defaultBlockState();
    private static final BlockState FLOOR_FILL = Blocks.STONE.defaultBlockState();

    public ChamberCarver() {
        super(CarverConfiguration.CODEC.codec());
    }

    @Override
    public boolean isStartChunk(CarverConfiguration config, RandomSource random) {
        // We do per-chunk geometry checks inside carve() — let every chunk be a
        // potential start chunk so we get a chance to evaluate. This is cheap
        // because the per-chunk overlap test in carve() short-circuits fast.
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

        // Quick reject: if no part of this chunk overlaps a region footprint,
        // bail without touching any blocks.
        if (!chunkOverlapsAnyRegion(chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ)) {
            return false;
        }

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean carved = false;

        for (int x = chunkMinX; x <= chunkMaxX; x++) {
            for (int z = chunkMinZ; z <= chunkMaxZ; z++) {
                if (!ShangriLaRegion.horizontalContains(x, z, ShangriLaRegion.DEFAULT_SALT)) {
                    continue;
                }

                // Chamber column at this (x, z): everything from floorShape to
                // ceilingShape becomes air. The floor below is left as solid stone
                // for vegetation_patch to grass over.
                int floor = ShangriLaRegion.chamberFloorShape(
                        x, z, ShangriLaRegion.DEFAULT_SALT);
                int ceiling = ShangriLaRegion.chamberCeilingShape(
                        x, z, ShangriLaRegion.DEFAULT_SALT);
                if (floor >= ceiling) continue;

                // Make sure the block directly below the floor is solid stone (no
                // chasms below the chamber floor — village pieces need this).
                int floorY = floor;
                pos.set(x, floorY, z);
                chunk.setBlockState(pos, FLOOR_FILL, 0);

                for (int y = floorY + 1; y <= ceiling; y++) {
                    pos.set(x, y, z);
                    BlockState current = chunk.getBlockState(pos);
                    if (current.isAir()) continue;
                    if (current.getBlock() == Blocks.BEDROCK) continue;
                    chunk.setBlockState(pos, AIR, 0);
                    carved = true;
                }
            }
        }
        return carved;
    }

    /** Cheap test: is any corner of the chunk in a region's footprint? */
    private static boolean chunkOverlapsAnyRegion(int minX, int minZ, int maxX, int maxZ) {
        long salt = ShangriLaRegion.DEFAULT_SALT;
        if (ShangriLaRegion.horizontalContains(minX, minZ, salt)) return true;
        if (ShangriLaRegion.horizontalContains(maxX, minZ, salt)) return true;
        if (ShangriLaRegion.horizontalContains(minX, maxZ, salt)) return true;
        if (ShangriLaRegion.horizontalContains(maxX, maxZ, salt)) return true;
        // Also check center to catch the case where the chunk sits entirely
        // inside one region (all 4 corners outside but center inside, only happens
        // for impossibly small regions, but cheap).
        int cx = (minX + maxX) / 2;
        int cz = (minZ + maxZ) / 2;
        return ShangriLaRegion.horizontalContains(cx, cz, salt);
    }
}
