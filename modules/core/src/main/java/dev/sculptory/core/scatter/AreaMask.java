package dev.sculptory.core.scatter;

import dev.sculptory.core.Box;
import java.util.BitSet;

/**
 * A {@link ScatterArea} rasterized over its bounding rectangle, with the surface scan window. Immutable after
 * construction.
 */
final class AreaMask {
    final int x0, z0, x1, z1;
    final int yBottom, yTop;
    final long columns;
    private final int width;
    /** Row-major ({@code (z - z0) * width + (x - x0)}); {@code null} when every column of the rectangle is in. */
    private final BitSet bits;

    private AreaMask(int x0, int z0, int x1, int z1, int yBottom, int yTop, BitSet bits) {
        this.x0 = x0;
        this.z0 = z0;
        this.x1 = x1;
        this.z1 = z1;
        this.yBottom = yBottom;
        this.yTop = yTop;
        this.width = x1 - x0 + 1;
        this.bits = bits;
        this.columns = bits == null ? (long) width * (z1 - z0 + 1) : bits.cardinality();
    }

    /**
     * @throws IllegalArgumentException if a region lies entirely outside the build height
     */
    static AreaMask of(ScatterArea area, int worldBottom, int worldTopExclusive) {
        int worldTop = worldTopExclusive - 1;
        return switch (area) {
            case ScatterArea.Region region -> {
                Box box = region.box();
                int top = Math.min(box.max().y(), worldTop), bottom = Math.max(box.min().y(), worldBottom);
                if (top < bottom) throw new IllegalArgumentException("Scatter box is outside the build height: " + box);
                yield new AreaMask(box.min().x(), box.min().z(), box.max().x(), box.max().z(), bottom, top, null);
            }
            case ScatterArea.Stamps stamps -> rasterize(stamps, worldBottom, worldTop);
        };
    }

    private static AreaMask rasterize(ScatterArea.Stamps stamps, int bottom, int top) {
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (ScatterArea.Stamp stamp : stamps.stamps()) {
            if (stamp.erase()) continue;
            x0 = Math.min(x0, stamp.x() - stamp.radius());
            z0 = Math.min(z0, stamp.z() - stamp.radius());
            x1 = Math.max(x1, stamp.x() + stamp.radius());
            z1 = Math.max(z1, stamp.z() + stamp.radius());
        }
        int width = x1 - x0 + 1;
        BitSet bits = new BitSet(width * (z1 - z0 + 1));
        for (ScatterArea.Stamp stamp : stamps.stamps()) {
            int r = stamp.radius();
            for (int dz = -r; dz <= r; dz++) {
                int z = stamp.z() + dz;
                if (z < z0 || z > z1) continue;
                int half = isqrt(r * r - dz * dz);
                int from = Math.max(x0, stamp.x() - half), to = Math.min(x1, stamp.x() + half);
                if (from > to) continue;
                int row = (z - z0) * width - x0;
                if (stamp.erase()) {
                    bits.clear(row + from, row + to + 1);
                } else {
                    bits.set(row + from, row + to + 1);
                }
            }
        }
        return new AreaMask(x0, z0, x1, z1, bottom, top, bits);
    }

    /** Whether column (x, z) is in the area. */
    boolean contains(int x, int z) {
        if (x < x0 || x > x1 || z < z0 || z > z1) return false;
        return bits == null || bits.get(index(x, z));
    }

    /** A dense index of column (x, z) of the bounding rectangle. */
    int index(int x, int z) {
        return (z - z0) * width + (x - x0);
    }

    /** The largest h with h² <= v, for 0 <= v <= 2³¹ - 1. */
    private static int isqrt(int v) {
        int h = (int) Math.sqrt(v);
        while ((long) h * h > v) h--;
        while ((long) (h + 1) * (h + 1) <= v) h++;
        return h;
    }
}
