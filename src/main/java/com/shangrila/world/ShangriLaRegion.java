package com.shangrila.world;

/**
 * Pure-Java geometry and noise for Shangri-La regions.
 *
 * <h2>Region selection</h2>
 * The world is divided into a square grid of {@link #GRID_SIZE}-block cells.
 * Each cell deterministically chooses whether to host a region, and where the
 * region's center sits within the cell. Two regions are guaranteed not to touch
 * because center jitter is bounded to keep regions inside their cells.
 *
 * <h2>Cavern definition</h2>
 * A block at (x, y, z) is "cavern air" iff:
 * <ol>
 *   <li>It is inside a region's vertical cylinder (horizontal radius
 *       {@link #REGION_RADIUS_BLOCKS}, Y in
 *       [{@link #CHAMBER_Y_BOTTOM}, {@link #CHAMBER_Y_TOP}]).</li>
 *   <li>The 3D density function {@link #density} returns a value above
 *       {@link #CARVE_THRESHOLD}.</li>
 * </ol>
 *
 * <p>Density is the sum of multi-octave value noise and a radial bias that
 * peaks at the region center and falls off toward the rim and Y extremes.
 * The bias guarantees a connected, mostly-open volume around the center; the
 * noise gives the volume an organic boundary — overhangs, alcoves, varied
 * ceiling height, occasional pillars.
 *
 * <p>All methods are pure — no Minecraft API dependencies.
 */
public final class ShangriLaRegion {

    // ----- Region grid -----

    /** Size of the deterministic region grid, in blocks. */
    public static final int GRID_SIZE = 5_000;

    /** Probability that any given grid cell hosts a region. */
    public static final double REGION_PROBABILITY = 0.5;

    /** Horizontal radius of the region cylinder. */
    public static final int REGION_RADIUS_BLOCKS = 192;

    /** Lowest possible Y of the chamber interior. Extended below the valley to leave headroom for water features without intruding into the valley space. */
    public static final int CHAMBER_Y_BOTTOM = -52;

    /** Highest possible Y of the chamber interior. */
    public static final int CHAMBER_Y_TOP = 40;

    /** Y where the valley itself bottoms out (max-bias point). Below this is water headroom. */
    public static final int VALLEY_FLOOR_Y = -40;

    /** Vertical center of the valley's bias — biased so the valley shape sits where the old version was. */
    public static final int CHAMBER_Y_CENTER = 0;

    /** Distance from {@link #CHAMBER_Y_CENTER} to the top edge of the bias (valley top). */
    public static final int Y_HALF_UP = CHAMBER_Y_TOP - CHAMBER_Y_CENTER; // 40

    /** Distance from {@link #CHAMBER_Y_CENTER} down to the bottom edge of the bias. */
    public static final int Y_HALF_DOWN = CHAMBER_Y_CENTER - CHAMBER_Y_BOTTOM; // 52

    /**
     * Jitter for region center placement within a cell. Bounded so that any
     * region center is at least {@code REGION_RADIUS + 256} from any cell edge,
     * guaranteeing regions in adjacent cells never touch.
     */
    private static final int CENTER_JITTER =
            (GRID_SIZE / 2) - REGION_RADIUS_BLOCKS - 256;

    /** Fixed salt so regions appear at the same coordinates in every world. */
    public static final long DEFAULT_SALT = 0x51A_C_CAFE_FEEDL;

    // ----- Carving noise -----

    /** Cell size for the lowest-frequency noise octave, in blocks. */
    private static final int NOISE_CELL_SIZE_XZ = 48;
    private static final int NOISE_CELL_SIZE_Y = 24;
    private static final int NOISE_OCTAVES = 3;

    /** Amplitude scale for noise contribution to density. */
    private static final double NOISE_AMPLITUDE = 0.55;

    /**
     * Density threshold for a block to be considered cavern air. Higher = less
     * open volume; lower = more open. 0.0 gives a moderately open cavern.
     */
    public static final double CARVE_THRESHOLD = 0.0;

    private ShangriLaRegion() {}

    // ----- Hashing & math primitives -----

    private static long hash(long a, long b, long c) {
        long h = a * 6364136223846793005L + 1442695040888963407L;
        h ^= b;
        h = (h ^ (h >>> 32)) * 0x9E3779B97F4A7C15L;
        h ^= c;
        h = (h ^ (h >>> 33)) * 0xC2B2AE3D27D4EB4FL;
        h ^= (h >>> 29);
        return h;
    }

    private static long hash4(long a, long b, long c, long d) {
        return hash(hash(a, b, c), d, 0x12345678L);
    }

    private static int floorDiv(int a, int b) {
        int q = a / b;
        if ((a ^ b) < 0 && q * b != a) q--;
        return q;
    }

    private static double smooth(double t) { return t * t * (3.0 - 2.0 * t); }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    // ----- Region selection -----

    public static boolean cellHostsRegion(int gx, int gz, long seed) {
        long h = hash(seed, gx, gz);
        double u = (h >>> 11) * (1.0 / (1L << 53));
        return u < REGION_PROBABILITY;
    }

    public static int regionCenterX(int gx, int gz, long seed) {
        long h = hash(seed ^ 0xA5A5A5A5L, gx, gz);
        int jitter = (int) ((h & 0xFFFFFFFFL) % (CENTER_JITTER * 2 + 1)) - CENTER_JITTER;
        return gx * GRID_SIZE + GRID_SIZE / 2 + jitter;
    }

    public static int regionCenterZ(int gx, int gz, long seed) {
        long h = hash(seed ^ 0x5A5A5A5AL, gx, gz);
        int jitter = (int) ((h & 0xFFFFFFFFL) % (CENTER_JITTER * 2 + 1)) - CENTER_JITTER;
        return gz * GRID_SIZE + GRID_SIZE / 2 + jitter;
    }

    /** Packs (gx, gz) into a long. */
    private static long packCell(int gx, int gz) {
        return (((long) gx) << 32) | (gz & 0xFFFFFFFFL);
    }
    private static int unpackGx(long c) { return (int) (c >> 32); }
    private static int unpackGz(long c) { return (int) c; }

    /**
     * Returns the cell coords of the nearest region center to (x, z) in the
     * 3x3 neighborhood of cells, or {@link Long#MIN_VALUE} if none. The packed
     * encoding is hi=gx, lo=gz.
     */
    public static long nearestRegionCell(int x, int z, long seed) {
        int gx = floorDiv(x, GRID_SIZE);
        int gz = floorDiv(z, GRID_SIZE);
        long bestCell = Long.MIN_VALUE;
        long bestD2 = -1;
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
                if (bestD2 < 0 || d2 < bestD2) {
                    bestD2 = d2;
                    bestCell = packCell(cx, cz);
                }
            }
        }
        return bestCell;
    }

    public static long nearestRegionDistanceSq(int x, int z, long seed) {
        long cell = nearestRegionCell(x, z, seed);
        if (cell == Long.MIN_VALUE) return -1;
        int gx = unpackGx(cell);
        int gz = unpackGz(cell);
        long ddx = x - regionCenterX(gx, gz, seed);
        long ddz = z - regionCenterZ(gx, gz, seed);
        return ddx * ddx + ddz * ddz;
    }

    /** True if (x, z) is within the horizontal cylinder of any region. */
    public static boolean horizontalContains(int x, int z, long seed) {
        long d2 = nearestRegionDistanceSq(x, z, seed);
        return d2 >= 0 && d2 <= (long) REGION_RADIUS_BLOCKS * REGION_RADIUS_BLOCKS;
    }

    // ----- 3D value noise -----

    private static double cornerValue3(int gx, int gy, int gz, long seed) {
        long h = hash4(seed, gx, gy, gz);
        double u = (h >>> 11) * (1.0 / (1L << 53));
        return u * 2.0 - 1.0;
    }

    /** 3D value noise with separate XZ and Y cell sizes. Result in [-1, 1]. */
    private static double valueNoise3(int x, int y, int z, int cellXZ, int cellY, long seed) {
        int gx = floorDiv(x, cellXZ);
        int gy = floorDiv(y, cellY);
        int gz = floorDiv(z, cellXZ);
        double fx = (double) (x - gx * cellXZ) / cellXZ;
        double fy = (double) (y - gy * cellY) / cellY;
        double fz = (double) (z - gz * cellXZ) / cellXZ;
        double sx = smooth(fx), sy = smooth(fy), sz = smooth(fz);
        double c000 = cornerValue3(gx,     gy,     gz,     seed);
        double c100 = cornerValue3(gx + 1, gy,     gz,     seed);
        double c010 = cornerValue3(gx,     gy + 1, gz,     seed);
        double c110 = cornerValue3(gx + 1, gy + 1, gz,     seed);
        double c001 = cornerValue3(gx,     gy,     gz + 1, seed);
        double c101 = cornerValue3(gx + 1, gy,     gz + 1, seed);
        double c011 = cornerValue3(gx,     gy + 1, gz + 1, seed);
        double c111 = cornerValue3(gx + 1, gy + 1, gz + 1, seed);
        double x00 = lerp(c000, c100, sx);
        double x10 = lerp(c010, c110, sx);
        double x01 = lerp(c001, c101, sx);
        double x11 = lerp(c011, c111, sx);
        double y0 = lerp(x00, x10, sy);
        double y1 = lerp(x01, x11, sy);
        return lerp(y0, y1, sz);
    }

    /** 3D fractional Brownian motion. */
    private static double fbm3(int x, int y, int z, long seed) {
        double total = 0, amp = 1, maxAmp = 0;
        int cellXZ = NOISE_CELL_SIZE_XZ;
        int cellY = NOISE_CELL_SIZE_Y;
        long s = seed;
        for (int o = 0; o < NOISE_OCTAVES; o++) {
            total += amp * valueNoise3(x, y, z, cellXZ, cellY, s);
            maxAmp += amp;
            amp *= 0.5;
            cellXZ = Math.max(4, cellXZ / 2);
            cellY = Math.max(2, cellY / 2);
            s = hash(s, 0x6789ABCDL, o);
        }
        return total / maxAmp;
    }

    // ----- Density / carving query -----

    /**
     * Density at (x, y, z). Positive values are "open" (cavern air), negative
     * values are "solid". The carve threshold compares against
     * {@link #CARVE_THRESHOLD}.
     *
     * <p>Returns a very negative value if (x, z) is outside any region — never
     * "open" outside a region.
     */
    public static double density(int x, int y, int z, long seed) {
        long cell = nearestRegionCell(x, z, seed);
        if (cell == Long.MIN_VALUE) return -10.0;
        int gx = unpackGx(cell);
        int gz = unpackGz(cell);
        int rcx = regionCenterX(gx, gz, seed);
        int rcz = regionCenterZ(gx, gz, seed);
        long ddx = x - rcx;
        long ddz = z - rcz;
        double r = Math.sqrt((double) (ddx * ddx + ddz * ddz));
        if (r > REGION_RADIUS_BLOCKS) return -10.0;
        if (y < CHAMBER_Y_BOTTOM || y > CHAMBER_Y_TOP) return -10.0;

        // Radial bias: 1 at center (full open), 0 at rim. Quartic falloff
        // keeps a wide flat-ish interior with rapid solidification near the rim.
        double rN = r / REGION_RADIUS_BLOCKS;                  // 0..1
        double radialOpen = 1.0 - rN * rN;                     // 1 → 0

        // Vertical bias: 1 at Y_CENTER, 0 at Y_TOP / Y_BOTTOM. Asymmetric so the
        // bottom extends further below Y_CENTER (water headroom) without
        // changing where the valley sits. yN normalized into [-1, 1].
        double yDelta = y - CHAMBER_Y_CENTER;
        double yN = yDelta >= 0
                ? yDelta / Y_HALF_UP
                : yDelta / Y_HALF_DOWN;
        double verticalOpen = 1.0 - yN * yN;                   // 1 → 0

        // Combined bias scales to ~1 inside the chamber, 0 at boundaries.
        double bias = radialOpen * verticalOpen;

        // Noise modulates the boundary contour. NOISE_AMPLITUDE controls how
        // much the noise can push the boundary in/out from the ideal ellipsoid.
        double noise = fbm3(x, y, z, seed ^ 0xCA7E_F0F0L);

        // Final density: bias minus threshold offset, plus noise contribution.
        // When bias is high (deep inside), even very negative noise stays open.
        // When bias is near 0 (near the boundary), noise determines local shape.
        return bias - 0.5 + NOISE_AMPLITUDE * noise;
    }

    /** Convenience: is this block carved out as cavern air? */
    public static boolean isOpen(int x, int y, int z, long seed) {
        return density(x, y, z, seed) > CARVE_THRESHOLD;
    }
}
