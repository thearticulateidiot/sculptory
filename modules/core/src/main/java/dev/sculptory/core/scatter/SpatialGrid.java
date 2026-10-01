package dev.sculptory.core.scatter;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Arrays;

/**
 * Accepted anchors bucketed into square cells as wide as the spacing, so a spacing test only looks at the 3 × 3
 * cells around a point: any anchor closer than the spacing lies in one of them. O(1) per test while the
 * accepted anchors are spaced (a cell then holds at most a handful).
 */
final class SpatialGrid {
    private final int cell;
    private final long minDistanceSq;
    /** Cell key to the last anchor added in that cell; {@link #next} links to the previous one. */
    private final Long2IntOpenHashMap head = new Long2IntOpenHashMap();
    private int[] xs = new int[64], zs = new int[64], next = new int[64];
    private int size;

    /** @param spacing at least 1 */
    SpatialGrid(int spacing) {
        this.cell = spacing;
        this.minDistanceSq = (long) spacing * spacing;
        head.defaultReturnValue(-1);
    }

    /** Whether an added anchor is closer than the spacing to (x, z). */
    boolean tooClose(int x, int z) {
        int cx = Math.floorDiv(x, cell), cz = Math.floorDiv(z, cell);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int i = head.get(key(cx + dx, cz + dz)); i >= 0; i = next[i]) {
                    long ddx = (long) x - xs[i], ddz = (long) z - zs[i];
                    if (ddx * ddx + ddz * ddz < minDistanceSq) return true;
                }
            }
        }
        return false;
    }

    void add(int x, int z) {
        if (size == xs.length) {
            xs = Arrays.copyOf(xs, size * 2);
            zs = Arrays.copyOf(zs, size * 2);
            next = Arrays.copyOf(next, size * 2);
        }
        long key = key(Math.floorDiv(x, cell), Math.floorDiv(z, cell));
        xs[size] = x;
        zs[size] = z;
        next[size] = head.get(key);
        head.put(key, size);
        size++;
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
