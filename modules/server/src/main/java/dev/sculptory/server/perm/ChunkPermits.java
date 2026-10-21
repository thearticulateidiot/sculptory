package dev.sculptory.server.perm;

import dev.sculptory.core.Box;
import dev.sculptory.server.engine.ChunkPermit;
import java.util.Arrays;

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
 *
 * <p>Protection that may differ with height (a {@link CellTest}) has no sampling rule: corners and focus points miss
 * nested regions with priorities and thin shapes. A producer either knows the part's answer is uniform
 * ({@link #uniform}) or asks every cell ({@link #rasterize}).
 */
public final class ChunkPermits {
    private ChunkPermits() {}

    /** Whether the player may modify column (x, z). */
    @FunctionalInterface
    public interface ColumnTest {
        boolean allowed(int x, int z);
    }

    /** Whether the player may modify cell (x, y, z). */
    @FunctionalInterface
    public interface CellTest {
        boolean allowed(int x, int y, int z);
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

    /**
     * The permit of a producer that knows the answer is the same for every cell of the part of {@code bounds} inside
     * chunk (cx, cz) (for example, no protected region intersects that part): one cell of the part is asked and its
     * answer given as {@link ChunkPermit#ALLOW} or {@link ChunkPermit#DENY}. Nothing here checks the claim; a producer
     * that cannot vouch for it uses {@link #rasterize}. A chunk the box does not touch gets {@link ChunkPermit#ALLOW}:
     * nothing there is written.
     */
    public static ChunkPermit uniform(int cx, int cz, Box bounds, CellTest test) {
        int[] rect = rectangle(cx, cz, bounds);
        if (rect == null) return ChunkPermit.ALLOW;
        return test.allowed(rect[0], bounds.min().y(), rect[1]) ? ChunkPermit.ALLOW : ChunkPermit.DENY;
    }

    /**
     * The exact permit for the part of {@code bounds} inside chunk (cx, cz): every cell of the part is asked once (up to
     * 16 × 16 × its height), and the answers are kept as compactly as they allow: {@link ChunkPermit#ALLOW} or
     * {@link ChunkPermit#DENY} when all agree, {@link ChunkPermit.Columns} when no column changes with height, else
     * {@link ChunkPermit.Cells} over the part's heights. Sound whatever the protection's shape (nested regions with
     * priorities, polygons, thin strips), unlike any sampling. The compact forms answer the same at every height, which
     * is right because consumers ask only within the bounds they asked the permit for (see
     * {@code PermissionService.chunk}). A chunk the box does not touch gets {@link ChunkPermit#ALLOW}.
     */
    public static ChunkPermit rasterize(int cx, int cz, Box bounds, CellTest test) {
        int[] rect = rectangle(cx, cz, bounds);
        if (rect == null) return ChunkPermit.ALLOW;
        int x0 = rect[0], z0 = rect[1], x1 = rect[2], z1 = rect[3];
        int y0 = bounds.min().y(), y1 = bounds.max().y();
        long[] bits = new long[(y1 - y0 + 1) * 4];
        boolean anyAllowed = false, anyDenied = false;
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    if (test.allowed(x, y, z)) {
                        int bit = ((z & 15) << 4) | (x & 15);
                        bits[ChunkPermit.Cells.word(y0, y, bit >>> 6)] |= 1L << bit;
                        anyAllowed = true;
                    } else {
                        anyDenied = true;
                    }
                }
            }
        }
        if (!anyDenied) return ChunkPermit.ALLOW;
        if (!anyAllowed) return ChunkPermit.DENY;
        // The same answer at every height of every column (protection that is not limited in height): 256 bits do.
        for (int i = 4; i < bits.length; i++) {
            if (bits[i] != bits[i & 3]) return new ChunkPermit.Cells(y0, y1, bits);
        }
        return new ChunkPermit.Columns(Arrays.copyOf(bits, 4));
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
