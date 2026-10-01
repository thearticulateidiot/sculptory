package dev.sculptory.fabric.client.editor.world;

/**
 * Finds the terrain surface of every column in a disc around a brush hit, so the brush cursor can
 * follow the ground.
 *
 * <p>Each column is scanned downwards from {@code hitY + radius + SCAN_ABOVE} to
 * {@code hitY - radius - SCAN_BELOW} (clamped to the world's height range) for the first block the
 * {@link TerrainProbe} calls solid. The result is a compact {@code int[]} of surface Y values with
 * {@link #NONE} for columns outside the disc or without a surface in range.
 *
 * <p>{@link #sample} caches its last result keyed by probe, hit block, radius and a caller-supplied
 * block-change stamp: callers bump the stamp when blocks change (or pass the world time as a coarse
 * stand-in). Render thread only; the pure {@link #scan} can run anywhere.
 */
public final class SurfaceSampler {
    public static final int MAX_RADIUS = 32;
    /** Sentinel surface height: no surface found, or the column is outside the disc. */
    public static final int NONE = Integer.MIN_VALUE;
    /** How far above {@code hitY + radius} each column scan starts. */
    public static final int SCAN_ABOVE = 8;
    /** How far below {@code hitY - radius} each column scan gives up. */
    public static final int SCAN_BELOW = 24;

    /** Surface heights for the disc of columns around a centre block. Immutable. */
    public static final class Samples {
        private final int centerX;
        private final int centerY;
        private final int centerZ;
        private final int radius;
        private final int[] heights;

        Samples(int centerX, int centerY, int centerZ, int radius, int[] heights) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.radius = radius;
            this.heights = heights;
        }

        public int centerX() {
            return centerX;
        }

        /** The hit block's Y the scan window was centred on. */
        public int centerY() {
            return centerY;
        }

        public int centerZ() {
            return centerZ;
        }

        public int radius() {
            return radius;
        }

        /** Width of the square the disc is stored in: {@code 2 * radius + 1}. */
        public int side() {
            return 2 * radius + 1;
        }

        /**
         * Y of the topmost terrain block in the column at offset ({@code dx}, {@code dz}) from the
         * centre (its top face is at {@code y + 1}), or {@link #NONE}.
         */
        public int heightAt(int dx, int dz) {
            if (dx < -radius || dx > radius || dz < -radius || dz > radius) {
                return NONE;
            }
            return heights[(dz + radius) * side() + (dx + radius)];
        }

        /** Like {@link #heightAt} but with world column coordinates. */
        public int heightAtWorld(int x, int z) {
            return heightAt(x - centerX, z - centerZ);
        }

        /** Number of columns with a surface. */
        public int surfaceCount() {
            int count = 0;
            for (int height : heights) {
                if (height != NONE) {
                    count++;
                }
            }
            return count;
        }
    }

    private TerrainProbe cachedProbe;
    private int cachedX;
    private int cachedY;
    private int cachedZ;
    private int cachedRadius;
    private long cachedStamp;
    private Samples cached;

    /**
     * Samples the disc, reusing the previous result when probe, hit block, radius and change stamp
     * are unchanged. The radius is clamped to 0..{@value #MAX_RADIUS}.
     */
    public Samples sample(TerrainProbe probe, int hitX, int hitY, int hitZ, int radius, long changeStamp) {
        int r = clampRadius(radius);
        if (cached != null
                && probe.equals(cachedProbe)
                && hitX == cachedX
                && hitY == cachedY
                && hitZ == cachedZ
                && r == cachedRadius
                && changeStamp == cachedStamp) {
            return cached;
        }
        Samples samples = scan(probe, hitX, hitY, hitZ, r);
        cachedProbe = probe;
        cachedX = hitX;
        cachedY = hitY;
        cachedZ = hitZ;
        cachedRadius = r;
        cachedStamp = changeStamp;
        cached = samples;
        return samples;
    }

    /** Forgets the cached samples. */
    public void invalidate() {
        cached = null;
        cachedProbe = null;
    }

    /** Uncached scan of the disc around the hit block. The radius is clamped to 0..{@value #MAX_RADIUS}. */
    public static Samples scan(TerrainProbe probe, int hitX, int hitY, int hitZ, int radius) {
        int r = clampRadius(radius);
        int side = 2 * r + 1;
        int[] heights = new int[side * side];
        int top = (int) Math.min((long) hitY + r + SCAN_ABOVE, (long) probe.topY() - 1);
        int bottom = (int) Math.max((long) hitY - r - SCAN_BELOW, probe.bottomY());
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int index = (dz + r) * side + (dx + r);
                heights[index] = inDisc(dx, dz, r) ? scanColumn(probe, hitX + dx, hitZ + dz, top, bottom) : NONE;
            }
        }
        return new Samples(hitX, hitY, hitZ, r, heights);
    }

    /** Scans one column from {@code topY} down to {@code bottomY} (both inclusive); {@link #NONE} if nothing is solid. */
    public static int scanColumn(TerrainProbe probe, int x, int z, int topY, int bottomY) {
        for (int y = topY; y >= bottomY; y--) {
            if (probe.isTerrainSolid(x, y, z)) {
                return y;
            }
        }
        return NONE;
    }

    /**
     * Whether the column at offset ({@code dx}, {@code dz}) belongs to a disc of the given radius:
     * {@code dx² + dz² <= r² + r}, i.e. within {@code r + 0.5} of the centre column's centre (rounded),
     * which gives rounder small discs than {@code <= r²}.
     */
    public static boolean inDisc(int dx, int dz, int radius) {
        return dx * dx + dz * dz <= radius * radius + radius;
    }

    private static int clampRadius(int radius) {
        return Math.max(0, Math.min(MAX_RADIUS, radius));
    }
}
