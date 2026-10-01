package dev.sculptory.core;

import dev.sculptory.core.buffer.BlockBuffer;
import java.util.Objects;
import java.util.function.LongConsumer;

/** An axis-aligned box of block cells; both corners are inclusive and {@code min <= max} on every axis. */
public record Box(BlockPos min, BlockPos max) {
    public Box {
        Objects.requireNonNull(min);
        Objects.requireNonNull(max);
        if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
            throw new IllegalArgumentException("Box min must not exceed max: " + min + " " + max);
        }
        if (span(min.x(), max.x()) > Integer.MAX_VALUE
                || span(min.y(), max.y()) > Integer.MAX_VALUE
                || span(min.z(), max.z()) > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Box side too long");
        }
    }

    /** The box spanned by two arbitrary corners. */
    public static Box of(BlockPos a, BlockPos b) {
        return new Box(
                new BlockPos(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z())),
                new BlockPos(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z())));
    }

    /** A single-cell box. */
    public static Box of(BlockPos cell) {
        return new Box(cell, cell);
    }

    public int sizeX() {
        return max.x() - min.x() + 1;
    }

    public int sizeY() {
        return max.y() - min.y() + 1;
    }

    public int sizeZ() {
        return max.z() - min.z() + 1;
    }

    /**
     * This box turned over within {@code pivot}'s height, x and z unchanged: a cell at {@code y} lands at
     * {@code pivot.min().y() + pivot.max().y() - y}. For a flip upside down of a selection cut to the build height:
     * this is the cut part and {@code pivot} the whole selection (the pivot the client shows), so the image may reach
     * outside the build height.
     *
     * @throws IllegalArgumentException if the image leaves the int range
     */
    public Box flippedWithin(Box pivot) {
        long sum = (long) pivot.min().y() + pivot.max().y();
        long minY = sum - max.y(), maxY = sum - min.y();
        if (minY < Integer.MIN_VALUE || maxY > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("The flipped box is outside the coordinate range");
        }
        return new Box(new BlockPos(min.x(), (int) minY, min.z()), new BlockPos(max.x(), (int) maxY, max.z()));
    }

    /** Number of cells, saturating at {@link Long#MAX_VALUE}. */
    public long volume() {
        try {
            return Math.multiplyExact(Math.multiplyExact((long) sizeX(), (long) sizeY()), (long) sizeZ());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    public boolean contains(int x, int y, int z) {
        return x >= min.x() && x <= max.x()
                && y >= min.y() && y <= max.y()
                && z >= min.z() && z <= max.z();
    }

    public boolean contains(BlockPos pos) {
        return contains(pos.x(), pos.y(), pos.z());
    }

    public boolean contains(Box other) {
        return contains(other.min) && contains(other.max);
    }

    public boolean intersects(Box other) {
        return min.x() <= other.max.x() && max.x() >= other.min.x()
                && min.y() <= other.max.y() && max.y() >= other.min.y()
                && min.z() <= other.max.z() && max.z() >= other.min.z();
    }

    public Box offset(int dx, int dy, int dz) {
        return new Box(min.offset(dx, dy, dz), max.offset(dx, dy, dz));
    }

    /**
     * Visits the {@link BlockBuffer#key section key} of every 16³ section the box touches, in a fixed
     * order: section x ascending (outermost), then section z, then section y (innermost). Sections of one
     * chunk column are therefore visited consecutively.
     */
    public void forEachSectionKey(LongConsumer action) {
        Objects.requireNonNull(action);
        int minSx = min.x() >> 4, maxSx = max.x() >> 4;
        int minSy = min.y() >> 4, maxSy = max.y() >> 4;
        int minSz = min.z() >> 4, maxSz = max.z() >> 4;
        for (int sx = minSx; sx <= maxSx; sx++) {
            for (int sz = minSz; sz <= maxSz; sz++) {
                for (int sy = minSy; sy <= maxSy; sy++) {
                    action.accept(BlockBuffer.key(sx, sy, sz));
                }
            }
        }
    }

    private static long span(int from, int to) {
        return (long) to - from + 1;
    }
}
