package com.shangrila.pipeline;

import com.shangrila.ShangriLaMod;
import com.shangrila.village.VillagePlanner;
import com.shangrila.water.WaterPlanner;
import com.shangrila.world.ShangriLaRegion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-chunk pipeline that runs after vanilla worldgen completes for a chunk.
 *
 * <p>Steps, in order:
 * <ol>
 *   <li>{@link #regionOverlapCheck} — bail if this chunk doesn't intersect any
 *       Shangri-La region.</li>
 *   <li>{@link #carve} — write air for "open" blocks, stone for "solid" blocks,
 *       overwriting whatever vanilla put there.</li>
 *   <li>{@link #insulate} — guarantee a 1-block stone shell around every air
 *       block at the cavern boundary so the cavern can't leak into vanilla
 *       caves or aquifers.</li>
 *   <li>{@link #surfaceTerrain} — convert the topmost solid block under each
 *       air column to grass, and the two below to dirt. This is the cave floor
 *       surface builder.</li>
 *   <li>{@link #terrainModifications} — extension point for future custom
 *       terrain shaping (rivers, hills, waterfalls, surface variants). No-op
 *       today.</li>
 *   <li>{@link #waterFeatures} — deterministic lakes per region, placed only
 *       in already-carved air blocks below a Y limit so they can't rise into
 *       the valley.</li>
 *   <li>{@link #decorations} — biome-flavored cosmetic features
 *       (cherry trees, ceiling vines, glow lichen). Extension-friendly.</li>
 *   <li>{@link #setBiome} — overwrite chunk biome storage for the carved
 *       volume so the player sees the cherry_cavern biome inside, while
 *       overworld biomes remain unchanged above and around.</li>
 *   <li>{@link #placeVillage} — per region, query the cached deterministic
 *       village piece list and write only the pieces whose bounding box
 *       overlaps THIS chunk. Pieces straddling chunks get partial writes from
 *       each chunk's pass — exactly how vanilla per-chunk structure placement
 *       works.</li>
 * </ol>
 *
 * <p>Every step is deterministic given the chunk's (x, z) and the world seed,
 * so re-running the pipeline on the same chunk produces identical results.
 * That's important for the village step: each chunk independently computes the
 * same village plan and writes its share.
 */
public final class ChamberPipeline {

    /**
     * Block-update flag for in-chunk writes during the pipeline. 0 = no
     * neighbor updates, no client packets (we're still in worldgen-ish phase).
     * Using a non-zero flag here would trigger lighting and physics updates
     * that we don't want during bulk terrain rewrites.
     */
    public static final int WRITE_FLAGS = 0;

    private static final BlockState AIR = Blocks.CAVE_AIR.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();

    /** Y limit below which water features are allowed to fill. */
    public static final int WATER_CEILING_Y = ShangriLaRegion.VALLEY_FLOOR_Y;

    public static final ResourceKey<Biome> CHERRY_CAVERN_KEY =
            ResourceKey.create(Registries.BIOME, ShangriLaMod.id("cherry_cavern"));

    private ChamberPipeline() {}

    /** Entry point: called once per chunk after vanilla worldgen completes. */
    public static void processChunk(ServerLevel level, LevelChunk chunk) {
        ChunkPos cp = chunk.getPos();
        long salt = ShangriLaRegion.DEFAULT_SALT;

        // Step 1: bail fast for the vast majority of chunks (no region nearby).
        List<RegionContext> regions = regionOverlapCheck(cp, salt);
        if (regions.isEmpty()) return;

        // Resolve the cave biome holder once. Biomes are loaded from the
        // server's biome registry; the cherry_cavern definition is in our mod
        // resources.
        HolderLookup<Biome> biomeLookup = level.registryAccess().lookupOrThrow(Registries.BIOME);
        Holder<Biome> caveBiome = biomeLookup.get(CHERRY_CAVERN_KEY).orElse(null);
        if (caveBiome == null) {
            ShangriLaMod.LOG.warn(
                    "Cherry-cavern biome not registered; skipping pipeline for chunk {} {}",
                    cp.x(), cp.z());
            return;
        }

        for (RegionContext region : regions) {
            carve(level, chunk, region);
            insulate(level, chunk, region);
            surfaceTerrain(level, chunk, region);
            terrainModifications(level, chunk, region);
            waterFeatures(level, chunk, region);
            decorations(level, chunk, region);
            setBiome(level, chunk, region, caveBiome);
            placeVillage(level, chunk, region);
        }
    }

    // ---------------------------------------------------------------------
    // Step 1: region overlap
    // ---------------------------------------------------------------------

    /**
     * Which regions' cylinders intersect this chunk's XZ footprint? Most calls
     * return an empty list — only ~5% of chunks within a region's range hit a
     * region, and most chunks aren't anywhere near a region.
     *
     * <p>A region's center is jittered into the middle of a 5000-block cell,
     * with a guaranteed gap from cell boundaries, so at most one region can
     * overlap any chunk-sized footprint. We still return a list because future
     * expansion may allow overlapping regions.
     */
    private static List<RegionContext> regionOverlapCheck(ChunkPos cp, long salt) {
        int minX = cp.getMinBlockX();
        int minZ = cp.getMinBlockZ();
        int maxX = minX + 15;
        int maxZ = minZ + 15;
        List<RegionContext> out = new ArrayList<>(1);

        // 3x3 cell neighborhood is sufficient given the jitter bounds.
        int gx0 = Math.floorDiv(minX, ShangriLaRegion.GRID_SIZE);
        int gz0 = Math.floorDiv(minZ, ShangriLaRegion.GRID_SIZE);
        long rSq = (long) ShangriLaRegion.REGION_RADIUS_BLOCKS
                 * ShangriLaRegion.REGION_RADIUS_BLOCKS;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = gx0 + dx;
                int cz = gz0 + dz;
                if (!ShangriLaRegion.cellHostsRegion(cx, cz, salt)) continue;
                int rcx = ShangriLaRegion.regionCenterX(cx, cz, salt);
                int rcz = ShangriLaRegion.regionCenterZ(cx, cz, salt);
                if (!cylinderIntersectsChunkXZ(rcx, rcz, rSq, minX, minZ, maxX, maxZ)) continue;
                out.add(new RegionContext(cx, cz, rcx, rcz, salt));
            }
        }
        return out;
    }

    /** Cheap conservative test: does the cylinder reach the chunk's footprint? */
    private static boolean cylinderIntersectsChunkXZ(
            int rcx, int rcz, long rSq,
            int minX, int minZ, int maxX, int maxZ) {
        long cx = Math.max(minX, Math.min(rcx, maxX));
        long cz = Math.max(minZ, Math.min(rcz, maxZ));
        long ddx = cx - rcx;
        long ddz = cz - rcz;
        return ddx * ddx + ddz * ddz <= rSq;
    }

    // ---------------------------------------------------------------------
    // Step 2: carve
    // ---------------------------------------------------------------------

    /**
     * Write AIR for blocks the density function says are open, STONE for
     * blocks it says are solid (inside the region cylinder + Y range).
     *
     * <p>Overwrites anything vanilla put there — vanilla cave air becomes stone
     * if our density says solid, vanilla aquifer water becomes air if our
     * density says open. The cavern volume is completely rewritten.
     */
    private static void carve(ServerLevel level, LevelChunk chunk, RegionContext region) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        long rSq = (long) ShangriLaRegion.REGION_RADIUS_BLOCKS
                 * ShangriLaRegion.REGION_RADIUS_BLOCKS;

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = minX + dx;
                int z = minZ + dz;
                long ddx = x - region.rcx();
                long ddz = z - region.rcz();
                if (ddx * ddx + ddz * ddz > rSq) continue;

                for (int y = ShangriLaRegion.CHAMBER_Y_BOTTOM;
                        y <= ShangriLaRegion.CHAMBER_Y_TOP; y++) {
                    pos.set(x, y, z);
                    BlockState current = chunk.getBlockState(pos);
                    if (current.is(Blocks.BEDROCK)) continue;
                    boolean open = ShangriLaRegion.isOpen(x, y, z, region.salt());
                    BlockState target = open ? AIR : STONE;
                    if (current != target) chunk.setBlockState(pos, target, WRITE_FLAGS);
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Step 3: insulate
    // ---------------------------------------------------------------------

    /**
     * For each air block we placed in this chunk, ensure each of its 6
     * neighbors is either our own cavern air, our cavern stone, or bedrock.
     * Anything else (vanilla cave air, dirt, water from outside the cylinder)
     * gets overwritten with stone — guaranteeing the 1-block stone shell.
     *
     * <p>Neighbors at the chunk edge may reach into adjacent chunks; we use
     * {@link ServerLevel#setBlock} for those so they get written to whichever
     * chunk they belong to.
     */
    private static void insulate(ServerLevel level, LevelChunk chunk, RegionContext region) {
        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos nb = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int maxX = minX + 15;
        int maxZ = minZ + 15;
        long rSq = (long) ShangriLaRegion.REGION_RADIUS_BLOCKS
                 * ShangriLaRegion.REGION_RADIUS_BLOCKS;

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = minX + dx;
                int z = minZ + dz;
                long ddx = x - region.rcx();
                long ddz = z - region.rcz();
                if (ddx * ddx + ddz * ddz > rSq) continue;

                for (int y = ShangriLaRegion.CHAMBER_Y_BOTTOM;
                        y <= ShangriLaRegion.CHAMBER_Y_TOP; y++) {
                    cur.set(x, y, z);
                    if (!chunk.getBlockState(cur).isAir()) continue;

                    for (int axis = 0; axis < 6; axis++) {
                        int nx = x + DX[axis];
                        int ny = y + DY[axis];
                        int nz = z + DZ[axis];
                        nb.set(nx, ny, nz);

                        // If the neighbor is itself part of the cavern (an
                        // "open" point per our density), leave it alone.
                        if (ShangriLaRegion.isOpen(nx, ny, nz, region.salt())) continue;

                        BlockState nbState =
                                inChunk(nx, nz, minX, minZ, maxX, maxZ)
                                        ? chunk.getBlockState(nb)
                                        : level.getBlockState(nb);
                        if (nbState.is(Blocks.BEDROCK)) continue;
                        if (nbState.is(Blocks.STONE)) continue;

                        if (inChunk(nx, nz, minX, minZ, maxX, maxZ)) {
                            chunk.setBlockState(nb, STONE, WRITE_FLAGS);
                        } else {
                            level.setBlock(nb, STONE, 2 /* updateNeighbors=false */);
                        }
                    }
                }
            }
        }
    }

    private static final int[] DX = { 1, -1, 0, 0, 0, 0 };
    private static final int[] DY = { 0,  0, 1, -1, 0, 0 };
    private static final int[] DZ = { 0,  0, 0, 0, 1, -1 };

    private static boolean inChunk(int x, int z, int minX, int minZ, int maxX, int maxZ) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    // ---------------------------------------------------------------------
    // Step 4: surface
    // ---------------------------------------------------------------------

    /**
     * For each column inside the region cylinder, walk top-down. Each time the
     * column transitions from air to a solid (stone) block, paint that solid
     * block as grass and the two below as dirt — the cave floor surface
     * builder. Skipped near the very bottom (water headroom).
     */
    private static void surfaceTerrain(ServerLevel level, LevelChunk chunk, RegionContext region) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        long rSq = (long) ShangriLaRegion.REGION_RADIUS_BLOCKS
                 * ShangriLaRegion.REGION_RADIUS_BLOCKS;

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = minX + dx;
                int z = minZ + dz;
                long ddx = x - region.rcx();
                long ddz = z - region.rcz();
                if (ddx * ddx + ddz * ddz > rSq) continue;

                boolean wasAir = false;
                for (int y = ShangriLaRegion.CHAMBER_Y_TOP;
                        y >= ShangriLaRegion.VALLEY_FLOOR_Y; y--) {
                    pos.set(x, y, z);
                    BlockState state = chunk.getBlockState(pos);
                    if (state.isAir()) { wasAir = true; continue; }
                    if (!wasAir) continue;            // not at an air→solid transition
                    if (!state.is(Blocks.STONE)) {     // only convert our stone
                        wasAir = false;
                        continue;
                    }
                    // Top → grass; two below → dirt (only if also stone).
                    chunk.setBlockState(pos, GRASS, WRITE_FLAGS);
                    for (int below = 1; below <= 2; below++) {
                        pos.set(x, y - below, z);
                        BlockState s = chunk.getBlockState(pos);
                        if (s.is(Blocks.STONE)) chunk.setBlockState(pos, DIRT, WRITE_FLAGS);
                    }
                    wasAir = false;
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Step 5: terrain modifications (extension point)
    // ---------------------------------------------------------------------

    /**
     * Extension point for future terrain modifications: river channels, extra
     * hills, surface waterfall sources, stone/dirt variants, ravine carving,
     * etc. Hook code in here without disturbing the rest of the pipeline.
     *
     * <p>Runs after the surface pass so grass/dirt are already present, and
     * before water features so terrain mods can shape water-bearing terrain.
     */
    private static void terrainModifications(ServerLevel level, LevelChunk chunk, RegionContext region) {
        // intentionally empty
    }

    // ---------------------------------------------------------------------
    // Step 6: water features
    // ---------------------------------------------------------------------

    /**
     * Per-region deterministic water features. Lakes are picked per region in
     * {@link WaterPlanner} and written here for whatever portion of each lake
     * falls inside this chunk. Water never rises above {@link #WATER_CEILING_Y}
     * (the valley floor reference Y), so no surface-flooding the chamber.
     */
    private static void waterFeatures(ServerLevel level, LevelChunk chunk, RegionContext region) {
        WaterPlanner.placeForChunk(level, chunk, region);
    }

    // ---------------------------------------------------------------------
    // Step 7: decorations (extension point)
    // ---------------------------------------------------------------------

    /**
     * Cosmetic features that should appear inside the cavern: cherry trees on
     * grass, glow lichen and vines on the ceiling, spore blossoms, mossy
     * stone, etc. Currently empty — to be filled in once the pipeline is
     * stable. Anything placed here should respect the village footprint
     * (don't grow a tree where a village house will go).
     */
    private static void decorations(ServerLevel level, LevelChunk chunk, RegionContext region) {
        // intentionally empty
    }

    // ---------------------------------------------------------------------
    // Step 8: biome overwrite
    // ---------------------------------------------------------------------

    /**
     * Write {@code shangri_la:cherry_cavern} into the chunk's biome storage for
     * the cavern volume.
     *
     * <p><b>Stub.</b> {@link net.minecraft.world.level.chunk.LevelChunkSection#setBiome}
     * is not publicly accessible in 26.1 and biome modification at runtime requires
     * either reflective access to the section's biome PalettedContainer or a mixin
     * accessor. Deferred to a follow-up build. The cavern still functions correctly
     * — only the in-world biome tint, music, and ambient particles will read as
     * the surrounding overworld biome until this is implemented.
     */
    private static void setBiome(ServerLevel level, LevelChunk chunk,
                                  RegionContext region, Holder<Biome> caveBiome) {
        // intentionally empty
    }

    // ---------------------------------------------------------------------
    // Step 9: village
    // ---------------------------------------------------------------------

    /**
     * Place the village pieces for this region whose bounding boxes overlap
     * this chunk. The piece list is cached per-region in {@link VillagePlanner}
     * and is deterministic from the region's center coordinates.
     */
    private static void placeVillage(ServerLevel level, LevelChunk chunk, RegionContext region) {
        VillagePlanner.placeForChunk(level, chunk, region);
    }

    // ---------------------------------------------------------------------
    // Helper types
    // ---------------------------------------------------------------------

    /**
     * Lightweight handle to one Shangri-La region: its grid cell coordinates,
     * its (jittered) center, and the salt under which it was selected. Passed
     * through every pipeline step so each step has the same view of the region.
     */
    public record RegionContext(int gx, int gz, int rcx, int rcz, long salt) {
        public long regionId() {
            return (((long) gx) << 32) | (gz & 0xFFFFFFFFL);
        }
    }
}
