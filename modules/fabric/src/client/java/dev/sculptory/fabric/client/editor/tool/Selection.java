package dev.sculptory.fabric.client.editor.tool;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.region.Region;
import java.util.Objects;

/**
 * The editor's selection: a region, possibly moved by an offset not yet applied to it. Moving a box or a shape is
 * cheap, so it is applied at once; moving a cell set rebuilds every bitmap, so nudges and drags of a magic selection
 * only add to the offset, and {@link #region()} applies it once, when something needs the cells themselves (an op, a
 * copy, a move). Everything shown each frame ({@link #bounds()}, the kind, the outline at an offset) works without
 * it. Immutable (the applied region is cached); client thread only.
 *
 * <p>Moves are clamped so the bounds stay within {@link #HORIZONTAL_LIMIT} and {@link #VERTICAL_LIMIT}: far beyond
 * the world border, and inside the range a cell set can hold.
 */
public final class Selection {
    /** The farthest x or z a moved selection may reach (the world border is at 29,999,984). */
    public static final int HORIZONTAL_LIMIT = 30_000_000;
    /** The farthest y a moved selection may reach (a cell set holds sections up to ±2^19, about 8.4 million). */
    public static final int VERTICAL_LIMIT = 8_000_000;

    private final Region base;
    private final int dx;
    private final int dy;
    private final int dz;
    private Region moved;

    private Selection(Region base, int dx, int dy, int dz) {
        this.base = base;
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        this.moved = dx == 0 && dy == 0 && dz == 0 ? base : null;
    }

    /**
     * A selection of {@code region}.
     *
     * @throws IllegalArgumentException for an {@link Region.Uploaded}, which is only a wire reference
     */
    public static Selection of(Region region) {
        Objects.requireNonNull(region);
        if (region instanceof Region.Uploaded) {
            throw new IllegalArgumentException("An uploaded region is only a wire reference: " + region);
        }
        return new Selection(region, 0, 0, 0);
    }

    /** The region before the pending offset (the one the outline is built for). */
    public Region base() {
        return base;
    }

    /** The offset still to apply to {@link #base()}: {x, y, z}. */
    public int[] offset() {
        return new int[] {dx, dy, dz};
    }

    /** The bounds of the selection where it is now. Cheap. */
    public Box bounds() {
        return base.bounds().offset(dx, dy, dz);
    }

    /** Whether it is a box or a shape (resizable), not a cell set. */
    public boolean resizable() {
        return base instanceof Region.Cuboid || base instanceof Region.Shape;
    }

    /** The region where the selection is now. Applies a pending offset once (for a cell set, costs a rebuild). */
    public Region region() {
        if (moved == null) {
            moved = base.translate(dx, dy, dz);
        }
        return moved;
    }

    /**
     * The selection moved by (dx, dy, dz), clamped so its bounds stay within the limits. A box or shape moves at once;
     * a cell set only adds the move to its offset.
     */
    public Selection translate(int moveX, int moveY, int moveZ) {
        Box bounds = bounds();
        int[] move = clamp(bounds, moveX, moveY, moveZ);
        if (move[0] == 0 && move[1] == 0 && move[2] == 0) {
            return this;
        }
        if (base instanceof Region.Cells) {
            return new Selection(base, dx + move[0], dy + move[1], dz + move[2]);
        }
        return new Selection(region().translate(move[0], move[1], move[2]), 0, 0, 0);
    }

    /** The selection moved so its bounds start at {@code min} (clamped like {@link #translate}). */
    public Selection movedTo(BlockPos min) {
        Box bounds = bounds();
        long x = (long) min.x() - bounds.min().x();
        long y = (long) min.y() - bounds.min().y();
        long z = (long) min.z() - bounds.min().z();
        return translate(saturate(x), saturate(y), saturate(z));
    }

    /** The move, cut so {@code bounds} moved by it stays within the limits (an axis that can't: no move). */
    static int[] clamp(Box bounds, int moveX, int moveY, int moveZ) {
        return new int[] {
            clampAxis(bounds.min().x(), bounds.max().x(), moveX, HORIZONTAL_LIMIT),
            clampAxis(bounds.min().y(), bounds.max().y(), moveY, VERTICAL_LIMIT),
            clampAxis(bounds.min().z(), bounds.max().z(), moveZ, HORIZONTAL_LIMIT)};
    }

    private static int clampAxis(int min, int max, int move, int limit) {
        long low = -limit - (long) min;
        long high = limit - (long) max;
        if (low > high) {
            return 0; // wider than the range: it stays where it is
        }
        // An already out-of-range selection may still move back towards the range.
        long wanted = move;
        long clamped = Math.max(Math.min(low, 0), Math.min(Math.max(high, 0), wanted));
        return (int) clamped;
    }

    private static int saturate(long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }

    @Override
    public String toString() {
        return "Selection[" + base + (dx == 0 && dy == 0 && dz == 0 ? "" : " moved " + dx + "," + dy + "," + dz) + "]";
    }
}
