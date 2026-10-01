package dev.sculptory.core.brush;

import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;

/**
 * How steep the ground is under each column of a Palette Paint dab, for a mix laid out by steepness
 * ({@code MixLayout.Steepness}).
 *
 * <p><b>Definition.</b> The angle, 0° (flat) to 90° (vertical), of the plane that best fits the surface heights of the
 * columns within {@value #REACH} blocks (a 5 × 5 window): per axis the least-squares slope from the pairs of columns
 * symmetric about the column ({@code Σ k·(h(+k) − h(−k)) / Σ 2k²} over the pairs where both columns have a surface),
 * then {@code atan} of the combined slope. So a lone one-block step reads as a gentle slope (about 17°), a staircase of
 * one block up per block across as 45°, two up per block as 63°, and the four columns at a 20-block cliff's edge as
 * 76-81°. The surface is the one {@link SurfaceScan} finds in the dab's scan window, as the brush's own; a column whose
 * ground lies above or below the window counts as the window's top or bottom (so a cliff taller than the window still
 * reads as steep), one with none (a chunk not loaded, a structure first) leaves its pairs out, and a column with no
 * pairs at all is flat.
 *
 * <p><b>Reads.</b> The dab's footprint and two columns around it: inside the server's {@code dabBox} (radius + 2), so
 * the server checks them as it checks the dab. Exact integer sums, then {@code /}, {@code Math.sqrt} and
 * {@link StrictMath}, so the client and the server get the same angle.
 */
final class ColumnSteepness {
    /** How far the fitted window reaches from the column, in columns. */
    static final int REACH = 2;

    private final int x0, z0, depth;
    private final int[] height;

    /**
     * Scans the surface of every column in {@code [fx0 - REACH, fx1 + REACH] × [fz0 - REACH, fz1 + REACH]} within the
     * window {@code [yBottom, yTop]} (inside the build height).
     */
    ColumnSteepness(WorldReader world, StateSpace states, int fx0, int fx1, int fz0, int fz1, int yTop, int yBottom) {
        x0 = fx0 - REACH;
        z0 = fz0 - REACH;
        int width = fx1 - fx0 + 1 + 2 * REACH;
        depth = fz1 - fz0 + 1 + 2 * REACH;
        height = new int[width * depth];
        long chunk = Long.MIN_VALUE;
        boolean loaded = false;
        for (int x = x0; x < x0 + width; x++) {
            for (int z = z0; z < z0 + depth; z++) {
                long key = ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
                if (key != chunk) {
                    chunk = key;
                    loaded = world.isLoaded(x >> 4, z >> 4);
                }
                int y = SurfaceScan.NONE;
                if (loaded) {
                    y = SurfaceScan.scan(world, states, x, z, yTop, yBottom, null);
                    if (y == SurfaceScan.NONE) y = beyondWindow(world, states, x, z, yTop, yBottom);
                }
                height[(x - x0) * depth + (z - z0)] = y;
            }
        }
    }

    /**
     * For a column whose scan found no surface: {@code yTop} when ground fills the window's top cell (the ground rises
     * at least that high: a cliff above the dab), {@code yBottom - 1} when every cell of the window is open (it lies
     * lower: a cliff below), else {@link SurfaceScan#NONE} (a structure first). So a cliff taller than the scan window
     * still reads as steep, at least as steep as the window shows.
     */
    private static int beyondWindow(WorldReader world, StateSpace states, int x, int z, int yTop, int yBottom) {
        int top = states.flags(world.get(x, yTop, z));
        if (SurfaceScan.ground(top)) return yTop;
        if (!SurfaceScan.open(top)) return SurfaceScan.NONE;
        for (int y = yTop - 1; y >= yBottom; y--) {
            if (!SurfaceScan.open(states.flags(world.get(x, y, z)))) return SurfaceScan.NONE;
        }
        return yBottom - 1;
    }

    /** The steepness in degrees (0-90) of the ground under column (x, z) of the footprint. */
    double degrees(int x, int z) {
        long sumX = 0, denX = 0, sumZ = 0, denZ = 0;
        for (int across = -REACH; across <= REACH; across++) {
            for (int k = 1; k <= REACH; k++) {
                int east = heightAt(x + k, z + across), west = heightAt(x - k, z + across);
                if (east != SurfaceScan.NONE && west != SurfaceScan.NONE) {
                    sumX += (long) k * (east - west);
                    denX += 2L * k * k;
                }
                int south = heightAt(x + across, z + k), north = heightAt(x + across, z - k);
                if (south != SurfaceScan.NONE && north != SurfaceScan.NONE) {
                    sumZ += (long) k * (south - north);
                    denZ += 2L * k * k;
                }
            }
        }
        double gx = denX == 0 ? 0 : (double) sumX / denX;
        double gz = denZ == 0 ? 0 : (double) sumZ / denZ;
        return StrictMath.toDegrees(StrictMath.atan(Math.sqrt(gx * gx + gz * gz)));
    }

    private int heightAt(int x, int z) {
        return height[(x - x0) * depth + (z - z0)];
    }
}
