package com.shangrila.world;

/**
 * Pure-Java geometry for Shangri-La region selection and chamber bounds.
 *
 * <p>The world is divided into a square grid of {@link #GRID_SIZE}-block cells.
 * Each cell is independently asked: "do you host a Shangri-La region?" The answer
 * is a deterministic hash of the cell coordinates and a "seed" parameter.
 *
 * <p>The seed passed in is a salt — it can be the world seed (varies per world) or a
 * fixed value (regions appear at the same coordinates in every world). The BiomeSource
 * uses a constant salt for predictable region locations; the carver mixes the world
 * seed in if a per-world variation is desired.
 *
 * <p>If a cell hosts a region, the region's center is placed at a fixed jittered
 * offset within the cell. The region is a vertical cylinder of radius
 * {@link #REGION_RADIUS_BLOCKS}, with a chamber occupying Y from
 * {@link #CHAMBER_Y_MIN} to {@link #CHAMBER_Y_MAX}.
 *
 * <p>All methods on this class are pure — no Minecraft API dependencies. This makes
 * the geometry trivially testable. Anything that needs an MC type (e.g. BlockPos,
 * Holder&lt;Biome&gt;) calls into this class via primitive coordinates.
 */
public final class ShangriLaRegion {

    /** Size of the deterministic region grid, in blocks. */
    public static final int GRID_SIZE = 5_000;

    /** Probability that any given grid cell hosts a region. */
    public static final double REGION_PROBABILITY = 0.5;

    /** Horizontal radius of the chamber, in blocks. */
    public static final int REGION_RADIUS_BLOCKS = 192;

    /** Lowest Y of the chamber (chamber floor). */
    public static final int CHAMBER_Y_MIN = -30;

    /** Highest Y of the chamber (chamber ceiling). */
    public static final int CHAMBER_Y_MAX = 30;

    /** Vertical center of the chamber for shaping (mid of MIN/MAX). */
    public static final int CHAMBER_Y_CENTER = (CHAMBER_Y_MIN + CHAMBER_Y_MAX) / 2;

    /** Half-height of the chamber for shaping. */
    public static final int CHAMBER_Y_HALF_HEIGHT = (CHAMBER_Y_MAX - CHAMBER_Y_MIN) / 2;

    /**
     * Distance the chamber center may be jittered from the grid cell center.
     * Keeps the chamber comfortably inside the cell so adjacent regions never
     * touch each other (cell spacing minus 2 * radius = guaranteed gap).
     */
    private static final int CENTER_JITTER = (GRID_SIZE / 2) - REGION_RADIUS_BLOCKS - 256;

    /** Fixed salt for biome-source-side region placement (same regions in every world). */
    public static final long DEFAULT_SALT = 0x51A_C_CAFE_FEEDL;

    private ShangriLaRegion() {}

    /** Hash that mixes three longs into one. Splitmix-style, deterministic. */
    private static long hash(long a, long b, long c) {
        long h = a * 6364136223846793005L + 1442695040888963407L;
        h ^= b;
        h = (h ^ (h >>> 32)) * 0x9E3779B97F4A7C15L;
        h ^= c;
        h = (h ^ (h >>> 33)) * 0xC2B2AE3D27D4EB4FL;
        h ^= (h >>> 29);
        return h;
    }

    /** Floor division of a / b, working correctly for negative a. */
    private static int floorDiv(int a, int b) {
        int q = a / b;
        if ((a ^ b) < 0 && q * b != a) q--;
        return q;
    }

    /** Decide whether the grid cell at (gx, gz) hosts a region. */
    public static boolean cellHostsRegion(int gx, int gz, long seed) {
        // Stable unsigned-style probability draw in [0, 1).
        long h = hash(seed, gx, gz);
        double u = (h >>> 11) * (1.0 / (1L << 53));
        return u < REGION_PROBABILITY;
    }

    /**
     * Get the X center of the region in the cell at (gx, gz). Only meaningful when
     * {@link #cellHostsRegion(int, int, long)} is true.
     */
    public static int regionCenterX(int gx, int gz, long seed) {
        long h = hash(seed ^ 0xA5A5A5A5L, gx, gz);
        int jitter = (int) ((h & 0xFFFFFFFFL) % (CENTER_JITTER * 2 + 1)) - CENTER_JITTER;
        return gx * GRID_SIZE + GRID_SIZE / 2 + jitter;
    }

    /** Z center counterpart. */
    public static int regionCenterZ(int gx, int gz, long seed) {
        long h = hash(seed ^ 0x5A5A5A5AL, gx, gz);
        int jitter = (int) ((h & 0xFFFFFFFFL) % (CENTER_JITTER * 2 + 1)) - CENTER_JITTER;
        return gz * GRID_SIZE + GRID_SIZE / 2 + jitter;
    }

    /**
     * True if (x, y, z) is inside the chamber of any region.
     *
     * <p>Only the current cell and its 8 neighbors are checked, because a region
     * center is always at least {@code REGION_RADIUS_BLOCKS + 256} blocks away from
     * any cell boundary. A point at (x, z) can only be inside the chamber of a
     * region whose center lies within {@code REGION_RADIUS_BLOCKS} blocks — so the
     * 3x3 cell neighborhood is sufficient.
     */
    public static boolean contains(int x, int y, int z, long seed) {
        if (y < CHAMBER_Y_MIN || y > CHAMBER_Y_MAX) return false;
        return horizontalContains(x, z, seed);
    }

    /**
     * 2D containment check: true if (x, z) is inside the horizontal footprint of
     * any region's chamber. The chamber is shaped vertically by
     * {@link #chamberCeilingShape(int, int, long)} et al., but the horizontal
     * footprint is a simple disc.
     */
    public static boolean horizontalContains(int x, int z, long seed) {
        int gx = floorDiv(x, GRID_SIZE);
        int gz = floorDiv(z, GRID_SIZE);
        double r2 = (double) REGION_RADIUS_BLOCKS * REGION_RADIUS_BLOCKS;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = gx + dx;
                int cz = gz + dz;
                if (!cellHostsRegion(cx, cz, seed)) continue;
                int rcx = regionCenterX(cx, cz, seed);
                int rcz = regionCenterZ(cx, cz, seed);
                double ddx = x - rcx;
                double ddz = z - rcz;
                if (ddx * ddx + ddz * ddz <= r2) return true;
            }
        }
        return false;
    }

    /**
     * Returns the squared horizontal distance from (x, z) to the nearest region
     * center, or -1 if no region center is within {@code GRID_SIZE} blocks.
     * Useful for biome blending or smooth chamber shaping.
     */
    public static long nearestRegionDistanceSq(int x, int z, long seed) {
        int gx = floorDiv(x, GRID_SIZE);
        int gz = floorDiv(z, GRID_SIZE);
        long best = -1;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = gx + dx;
                int cz = gz + dz;
                if (!cellHostsRegion(cx, cz, seed)) continue;
                int rcx = regionCenterX(cx, cz, seed);
                int rcz = regionCenterZ(cx, cz, seed);
                long ddx = x - rcx;
                long ddz = z - rcz;
                long d2 = ddx * ddx + ddz * ddz;
                if (best < 0 || d2 < best) best = d2;
            }
        }
        return best;
    }

    /**
     * Chamber ceiling Y at (x, z): the highest Y that should be air inside the
     * chamber. Returns {@link Integer#MIN_VALUE} if outside the chamber footprint.
     *
     * <p>The ceiling tapers down toward the chamber edge so the chamber has a
     * dome-like profile instead of a sharp vertical cutoff. The floor is flat —
     * only the ceiling domes — so the chamber interior reads as one big valley
     * with a vaulted roof, not as concentric stepped bowls.
     */
    public static int chamberCeilingShape(int x, int z, long seed) {
        long d2 = nearestRegionDistanceSq(x, z, seed);
        if (d2 < 0) return Integer.MIN_VALUE;
        double r = Math.sqrt(d2);
        if (r > REGION_RADIUS_BLOCKS) return Integer.MIN_VALUE;
        // Smooth taper: full ceiling height at center, taper to floor near edge.
        double t = r / REGION_RADIUS_BLOCKS;          // 0..1
        double profile = Math.sqrt(Math.max(0.0, 1.0 - t * t));   // half-ellipse
        // Full vertical span (floor → top) at center; 0 at rim.
        int span = (int) Math.round(2 * CHAMBER_Y_HALF_HEIGHT * profile);
        return CHAMBER_Y_MIN + span;
    }

    /**
     * Chamber floor Y at (x, z): the chamber floor is FLAT at {@link #CHAMBER_Y_MIN}
     * across the entire footprint. This avoids the concentric stepped-bowl artifact
     * that arises when the floor follows the same dome profile as the ceiling, and
     * gives the village structure a predictable, level surface to build on.
     */
    public static int chamberFloorShape(int x, int z, long seed) {
        long d2 = nearestRegionDistanceSq(x, z, seed);
        if (d2 < 0) return Integer.MAX_VALUE;
        double r = Math.sqrt(d2);
        if (r > REGION_RADIUS_BLOCKS) return Integer.MAX_VALUE;
        return CHAMBER_Y_MIN;
    }
}
