package dev.sculptory.core.edit;

import dev.sculptory.core.SplitMix64;

/** The noise behind {@link MixLayout}: pure functions of a seed and a position. */
final class MixNoise {
    /** How far the smooth noise pushes a block's position in a Patches layout, in cells. */
    static final double WARP = 0.45;
    // Seeds of the independent streams, mixed into the pattern's seed.
    private static final long WARP_X = 0x5DEECE66DL;
    private static final long WARP_Y = 0x9E3779B97F4A7C15L;
    private static final long WARP_Z = 0xC2B2AE3D27D4EB4FL;
    private static final long POINTS = 0x165667B19E3779F9L;
    private static final long PICK = 0x27D4EB2F165667C5L;
    private static final long DITHER = 0x85EBCA77C2B2AE63L;

    private MixNoise() {}

    /**
     * The hash of the patch block (x, y, z) belongs to for cells of {@code size} blocks: the nearest point, among its
     * cell's and the 26 around it, to its position pushed by the smooth noise (ties: the first in x, y, z order).
     */
    static long patch(long seed, int size, int x, int y, int z) {
        double s = size;
        double px = (x + 0.5) / s, py = (y + 0.5) / s, pz = (z + 0.5) / s;
        double qx = px + WARP * value(seed ^ WARP_X, px, py, pz);
        double qy = py + WARP * value(seed ^ WARP_Y, px, py, pz);
        double qz = pz + WARP * value(seed ^ WARP_Z, px, py, pz);
        long cx = (long) Math.floor(qx), cy = (long) Math.floor(qy), cz = (long) Math.floor(qz);
        double best = Double.MAX_VALUE;
        long bestHash = 0;
        for (long dx = -1; dx <= 1; dx++) {
            for (long dy = -1; dy <= 1; dy++) {
                for (long dz = -1; dz <= 1; dz++) {
                    long hash = SplitMix64.hash(seed ^ POINTS, (int) (cx + dx), (int) (cy + dy), (int) (cz + dz));
                    double fx = cx + dx + unit21(hash >>> 43) - qx;
                    double fy = cy + dy + unit21(hash >>> 22) - qy;
                    double fz = cz + dz + unit21(hash >>> 1) - qz;
                    double d2 = fx * fx + fy * fy + fz * fz;
                    if (d2 < best) {
                        best = d2;
                        bestHash = hash;
                    }
                }
            }
        }
        return SplitMix64.mix(bestHash ^ PICK);
    }

    /** A value in {@code [-0.5, 0.5)} that depends only on the seed and the block: the dither of a border. */
    static double dither(long seed, int x, int y, int z) {
        return (SplitMix64.hash(seed ^ DITHER, x, y, z) >>> 11) * 0x1.0p-53 - 0.5;
    }

    /** The 21 low bits of {@code bits} as a fraction in {@code [0, 1)}. */
    private static double unit21(long bits) {
        return (bits & 0x1FFFFF) * 0x1.0p-21;
    }

    /** Smooth value noise in {@code [-1, 1]} at (x, y, z): random values at integer points, blended between them. */
    static double value(long seed, double x, double y, double z) {
        double fx = Math.floor(x), fy = Math.floor(y), fz = Math.floor(z);
        int ix = (int) fx, iy = (int) fy, iz = (int) fz;
        double tx = fade(x - fx), ty = fade(y - fy), tz = fade(z - fz);
        double c000 = corner(seed, ix, iy, iz), c100 = corner(seed, ix + 1, iy, iz);
        double c010 = corner(seed, ix, iy + 1, iz), c110 = corner(seed, ix + 1, iy + 1, iz);
        double c001 = corner(seed, ix, iy, iz + 1), c101 = corner(seed, ix + 1, iy, iz + 1);
        double c011 = corner(seed, ix, iy + 1, iz + 1), c111 = corner(seed, ix + 1, iy + 1, iz + 1);
        double x00 = c000 + (c100 - c000) * tx, x10 = c010 + (c110 - c010) * tx;
        double x01 = c001 + (c101 - c001) * tx, x11 = c011 + (c111 - c011) * tx;
        double y0 = x00 + (x10 - x00) * ty, y1 = x01 + (x11 - x01) * ty;
        return y0 + (y1 - y0) * tz;
    }

    private static double corner(long seed, int x, int y, int z) {
        return (SplitMix64.hash(seed, x, y, z) >>> 11) * 0x1.0p-52 - 1.0;
    }

    private static double fade(double t) {
        return t * t * (3 - 2 * t);
    }
}
