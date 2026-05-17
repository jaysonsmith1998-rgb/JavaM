package com.shangrila.world;

/**
 * Pure-Java geometry for Shangri-La region selection, chamber bounds, and
 * floor/ceiling shape.
 *
 * <h2>Region grid</h2>
 * The world is divided into a square grid of {@link #GRID_SIZE}-block cells.
 * Each cell is independently asked: "do you host a Shangri-La region?" The
 * answer is a deterministic hash of the cell coordinates and a salt. If yes,
 * the region's center is placed at a fixed jittered offset within the cell.
 *
 * <h2>Chamber profile</h2>
 * Each chamber is a vertical cylinder of horizontal radius
 * {@link #REGION_RADIUS_BLOCKS}, with these vertical structures:
 *
 * <ul>
 *   <li>Floor: bowl-shaped, deepest at the region center, rising toward the rim
 *       so that at the very edge the floor meets the ceiling and seals the
 *       chamber. Modulated by value noise to produce hills, dips, and an
 *       overall organic feel rather than a perfect mathematical bowl.</li>
 *   <li>Air space above the floor up to the ceiling.</li>
 *   <li>Ceiling: flat at {@link #CHAMBER_CEILING_Y}.</li>
 *   <li>Stone roof above the ceiling left untouched (vanilla terrain
 *       overburden), so any aquifers above the chamber are sealed off.</li>
 * </ul>
 *
 * <h2>Water</h2>
 * Wherever the floor dips below {@link #WATER_LEVEL}, the gap is filled with
 * water, producing ponds/lakes at the deepest spots near the chamber center.
 *
 * <p>All methods on this class are pure — no Minecraft API dependencies. The
 * carver and biome source call into this class via primitive coordinates.
 */
public final class ShangriLaRegion {

    /** Size of the deterministic region grid, in blocks. */
    public static final int GRID_SIZE = 5_000;

    /** Probability that any given grid cell hosts a region. */
    public static final double REGION_PROBABILITY = 0.5;

    /** Horizontal radius of the chamber, in blocks. */
    public static final int REGION_RADIUS_BLOCKS = 192;

    /** Y of the chamber ceiling (flat stone roof). */
    public static final int CHAMBER_CEILING_Y = 30;

    /** Lowest possible Y of the chamber floor (anywhere). */
    public static final int CHAMBER_FLOOR_MIN_Y = -30;

    /** Y of the flat central plaza floor. Above {@link #WATER_LEVEL} so plaza doesn't flood. */
    public static final int PLAZA_FLOOR_Y = -22;

    /** Water surface Y. Floor depressions below this Y fill with water. */
    public static final int WATER_LEVEL = -25;

    /**
     * Radius of the central flat plaza, in blocks. Inside this radius, the floor
     * is a perfectly flat pad at {@link #CHAMBER_FLOOR_MIN_Y} — no bowl gradient
     * and no noise. This guarantees the village structure always has level ground
     * to build on at the region center.
     *
     * <p>Plaza area is π × 80² ≈ 20,000 blocks², roughly 1/6 the total chamber
     * area, comfortably accommodating a large jigsaw village.
     */
    public static final int PLAZA_RADIUS = 80;

    /** Transition width between plaza and bowl, in blocks. Smooths the floor profile. */
    public static final int PLAZA_TRANSITION = 32;

    /**
     * Distance the chamber center may be jittered from the grid cell center.
     * Keeps the chamber comfortably inside the cell so adjacent regions never
     * touch each other (cell spacing minus 2 * radius = guaranteed gap).
     */
    private static final int CENTER_JITTER =
            (GRID_SIZE / 2) - REGION_RADIUS_BLOCKS - 256;

    /** Fixed salt for biome-source-side region placement (same regions in every world). */
    public static final long DEFAULT_SALT = 0x51A_C_CAFE_FEEDL;

    /** Cell size for the value-noise grid (in blocks). Larger = smoother hills. */
    private static final int NOISE_CELL_SIZE = 32;

    /** Amplitude of the noise modulation on the floor, in blocks. */
    private static final double NOISE_AMPLITUDE = 4.0;

    /** Number of octaves for the value-noise; each octave is 1/2 cell size and 1/2 amp. */
    private static final int NOISE_OCTAVES = 3;

    private ShangriLaRegion() {}

    // -----------------------------------------------------------------------
    // Region selection
    // -----------------------------------------------------------------------

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
        long h = hash(seed, gx, gz);
        double u = (h >>> 11) * (1.0 / (1L << 53));
        return u < REGION_PROBABILITY;
    }

    /** X center of the region in cell (gx, gz). Only meaningful when cellHostsRegion is true. */
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

    /** Returns the cell coords (packed: hi=gx, lo=gz) of the nearest region center, or null sentinel. */
    private static long nearestRegionCell(int x, int z, long seed) {
        int gx = floorDiv(x, GRID_SIZE);
        int gz = floorDiv(z, GRID_SIZE);
        long bestD2 = -1;
        long bestCell = 0;
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
                    bestCell = (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
                }
            }
        }
        return bestD2 < 0 ? Long.MIN_VALUE : bestCell;
    }

    /** Squared distance from (x, z) to the nearest region center, or -1 if no region within reach. */
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

    /** True if (x, z) is inside the horizontal footprint of any region. */
    public static boolean horizontalContains(int x, int z, long seed) {
        long d2 = nearestRegionDistanceSq(x, z, seed);
        if (d2 < 0) return false;
        return d2 <= (long) REGION_RADIUS_BLOCKS * REGION_RADIUS_BLOCKS;
    }

    /** True if (x, y, z) is inside the air space of any chamber. */
    public static boolean contains(int x, int y, int z, long seed) {
        if (y > CHAMBER_CEILING_Y) return false;
        if (!horizontalContains(x, z, seed)) return false;
        int floor = floorHeight(x, z, seed);
        return y >= floor && y <= CHAMBER_CEILING_Y;
    }

    // -----------------------------------------------------------------------
    // Value noise
    // -----------------------------------------------------------------------

    /** Smoothstep function for value noise interpolation. */
    private static double smooth(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    /** Linear interpolation. */
    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** Per-grid-corner random value in [-1, 1], deterministic from coords + seed. */
    private static double cornerValue(int gx, int gz, long seed) {
        long h = hash(seed, gx, gz);
        // Convert top 53 bits to double in [0,1), then to [-1, 1].
        double u = (h >>> 11) * (1.0 / (1L << 53));
        return u * 2.0 - 1.0;
    }

    /** 2D value noise sampled at (x, z) with the given cell size. Result in [-1, 1]. */
    private static double valueNoise(int x, int z, int cellSize, long seed) {
        int gx = floorDiv(x, cellSize);
        int gz = floorDiv(z, cellSize);
        double fx = (double) (x - gx * cellSize) / cellSize;
        double fz = (double) (z - gz * cellSize) / cellSize;
        double v00 = cornerValue(gx,     gz,     seed);
        double v10 = cornerValue(gx + 1, gz,     seed);
        double v01 = cornerValue(gx,     gz + 1, seed);
        double v11 = cornerValue(gx + 1, gz + 1, seed);
        double sx = smooth(fx);
        double sz = smooth(fz);
        return lerp(lerp(v00, v10, sx), lerp(v01, v11, sx), sz);
    }

    /** Fractional Brownian motion of value noise. */
    private static double fbm(int x, int z, long seed) {
        double total = 0.0;
        double amp = 1.0;
        double maxAmp = 0.0;
        int cell = NOISE_CELL_SIZE;
        long s = seed;
        for (int o = 0; o < NOISE_OCTAVES; o++) {
            total += amp * valueNoise(x, z, cell, s);
            maxAmp += amp;
            amp *= 0.5;
            cell = Math.max(2, cell / 2);
            s = hash(s, 0x6789ABCDL, o);
        }
        return total / maxAmp;
    }

    // -----------------------------------------------------------------------
    // Chamber profile
    // -----------------------------------------------------------------------

    /**
     * Y of the chamber floor at (x, z). Returns {@link Integer#MAX_VALUE} if
     * outside any region footprint (i.e. no carving should occur here).
     *
     * <p>Profile zones (by radial distance r from region center):
     * <ul>
     *   <li>{@code r < PLAZA_RADIUS}: flat plaza at
     *       {@link #PLAZA_FLOOR_Y}, no bowl, no noise. Guarantees the
     *       village always has a large level pad to build on.</li>
     *   <li>{@code PLAZA_RADIUS ≤ r ≤ PLAZA_RADIUS + PLAZA_TRANSITION}: smooth
     *       blend from plaza Y to bowl Y, with noise tapering in.</li>
     *   <li>{@code r > PLAZA_RADIUS + PLAZA_TRANSITION}: full bowl-plus-noise
     *       terrain — gentle hills, dips, ponds, rising to the ceiling at rim.</li>
     * </ul>
     */
    public static int floorHeight(int x, int z, long seed) {
        long d2 = nearestRegionDistanceSq(x, z, seed);
        if (d2 < 0) return Integer.MAX_VALUE;
        double r = Math.sqrt(d2);
        if (r > REGION_RADIUS_BLOCKS) return Integer.MAX_VALUE;

        // Plaza zone: flat at plaza Y (above water level so it doesn't flood).
        if (r <= PLAZA_RADIUS) return PLAZA_FLOOR_Y;

        // Bowl profile beyond the plaza: r' is normalized into [0, 1] where 0 is
        // the plaza edge and 1 is the chamber rim. Quadratic ease-in gives a
        // gentle slope up from the plaza, steepening near the rim. Outside the
        // plaza the floor can dip below the plaza level (especially with noise)
        // forming ponds at WATER_LEVEL in the rings around the plaza.
        double bowlR = (r - PLAZA_RADIUS) / (REGION_RADIUS_BLOCKS - PLAZA_RADIUS);
        double bowl = bowlR * bowlR;
        // Bowl base interpolates from a slight dip just outside the plaza, up to
        // the ceiling at the rim. The "dip" at bowlR=0 is set to a few blocks
        // below the plaza floor so we get a moat / ring of low ground around the
        // plaza — perfect for water features.
        int bowlBaseMin = PLAZA_FLOOR_Y - 6;  // 6 blocks below plaza = 4 below water
        double base = bowlBaseMin + (CHAMBER_CEILING_Y - bowlBaseMin) * bowl;

        // Noise applies in the transition zone and beyond, but is suppressed
        // near the rim so it cannot blow through the seal.
        double noiseMask;
        if (r <= PLAZA_RADIUS + PLAZA_TRANSITION) {
            // Ease noise in across the transition zone.
            noiseMask = (r - PLAZA_RADIUS) / PLAZA_TRANSITION;
        } else {
            // Beyond transition: full noise, tapered down toward the rim.
            noiseMask = 1.0 - bowl;
        }
        double noise = fbm(x, z, seed ^ 0xF0C9F0C9L) * NOISE_AMPLITUDE * noiseMask;

        int y = (int) Math.round(base + noise);
        if (y > CHAMBER_CEILING_Y - 1) y = CHAMBER_CEILING_Y - 1;
        if (y < CHAMBER_FLOOR_MIN_Y) y = CHAMBER_FLOOR_MIN_Y;
        return y;
    }

    /** Y of the chamber ceiling — flat stone roof shared across the whole region. */
    public static int ceilingHeight(int x, int z, long seed) {
        long d2 = nearestRegionDistanceSq(x, z, seed);
        if (d2 < 0) return Integer.MIN_VALUE;
        double r = Math.sqrt(d2);
        if (r > REGION_RADIUS_BLOCKS) return Integer.MIN_VALUE;
        return CHAMBER_CEILING_Y;
    }
}
