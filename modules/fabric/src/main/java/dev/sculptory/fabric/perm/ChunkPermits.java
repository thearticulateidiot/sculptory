package dev.sculptory.fabric.perm;

import dev.sculptory.core.Box;
import dev.sculptory.fabric.engine.ChunkPermit;

/**
 * The per-chunk protection rule, independent of Minecraft: test the four corner columns
 * of the part of the edit box inside the chunk, plus (optionally) the column of that rectangle nearest a focus
 * point. All allowed gives {@link ChunkPermit#ALLOW}, all denied {@link ChunkPermit#DENY}; anything mixed falls
 * back to a check of every column.
 *
 * <p>The focus sample exists for spawn protection: a protected square around spawn that is smaller than a chunk can
 * sit inside the rectangle (or straddle one edge) without covering any corner. The rectangle column nearest the
 * spawn lies inside the square whenever the square and the rectangle intersect, so sampling it catches every such
 * case. Other protected areas (claims) that touch neither a corner nor the focus are still not detected.
 */
public final class ChunkPermits {
    private ChunkPermits() {}

    /** Whether the player may modify column (x, z). */
    @FunctionalInterface
    public interface ColumnTest {
        boolean allowed(int x, int z);
    }

    /** Four-corner rule without a focus point. */
    public static ChunkPermit forChunk(int cx, int cz, Box bounds, ColumnTest test) {
        int[] rect = rectangle(cx, cz, bounds);
        if (rect == null) return ChunkPermit.ALLOW;
        return evaluate(rect[0], rect[1], rect[2], rect[3], test);
    }

    /**
     * The permit for chunk (cx, cz) and the part of {@code bounds} inside it, also sampling the column nearest
     * (focusX, focusZ). A chunk the box does not touch gets {@link ChunkPermit#ALLOW}: nothing there is written.
     */
    public static ChunkPermit forChunk(int cx, int cz, Box bounds, ColumnTest test, int focusX, int focusZ) {
        int[] rect = rectangle(cx, cz, bounds);
        if (rect == null) return ChunkPermit.ALLOW;
        return evaluate(rect[0], rect[1], rect[2], rect[3], test, focusX, focusZ);
    }

    /** The permit for the inclusive column rectangle, which must lie inside one chunk; four corners only. */
    public static ChunkPermit evaluate(int x0, int z0, int x1, int z1, ColumnTest test) {
        return evaluate(x0, z0, x1, z1, test, false, 0, 0);
    }

    /** As {@link #evaluate(int, int, int, int, ColumnTest)}, also sampling the column nearest the focus. */
    public static ChunkPermit evaluate(int x0, int z0, int x1, int z1, ColumnTest test, int focusX, int focusZ) {
        return evaluate(x0, z0, x1, z1, test, true, focusX, focusZ);
    }

    private static ChunkPermit evaluate(int x0, int z0, int x1, int z1, ColumnTest test, boolean focused,
                                        int focusX, int focusZ) {
        if (x0 > x1 || z0 > z1 || (x0 >> 4) != (x1 >> 4) || (z0 >> 4) != (z1 >> 4)) {
            throw new IllegalArgumentException("Rectangle must be non-empty and inside one chunk");
        }
        boolean a = test.allowed(x0, z0);
        boolean b = test.allowed(x1, z0);
        boolean c = test.allowed(x0, z1);
        boolean d = test.allowed(x1, z1);
        boolean e = a;
        if (focused) {
            int fx = Math.max(x0, Math.min(x1, focusX)), fz = Math.max(z0, Math.min(z1, focusZ));
            boolean corner = (fx == x0 || fx == x1) && (fz == z0 || fz == z1);
            e = corner ? (fx == x0 ? (fz == z0 ? a : c) : (fz == z0 ? b : d)) : test.allowed(fx, fz);
        }
        if (a && b && c && d && e) return ChunkPermit.ALLOW;
        if (!a && !b && !c && !d && !e) return ChunkPermit.DENY;
        long[] bits = new long[4];
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                if (test.allowed(x, z)) {
                    int bit = ((z & 15) << 4) | (x & 15);
                    bits[bit >>> 6] |= 1L << bit;
                }
            }
        }
        return new ChunkPermit.Columns(bits);
    }

    /** {x0, z0, x1, z1} of the part of {@code bounds} inside chunk (cx, cz), or {@code null}. */
    private static int[] rectangle(int cx, int cz, Box bounds) {
        int x0 = Math.max(bounds.min().x(), cx << 4);
        int x1 = Math.min(bounds.max().x(), (cx << 4) + 15);
        int z0 = Math.max(bounds.min().z(), cz << 4);
        int z1 = Math.min(bounds.max().z(), (cz << 4) + 15);
        if (x0 > x1 || z0 > z1) return null;
        return new int[] {x0, z0, x1, z1};
    }
}
