package dev.sculptory.core.region;

import dev.sculptory.core.Box;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.edit.SectionOrder;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.Arrays;
import java.util.Objects;

/**
 * The cells a Select operation applies to: a box, a shape
 * inscribed in a box, an explicit set of cells (magic select), or, on the wire, a cell set the server already holds.
 * Every operation of the Select tool, and copies, take a region.
 */
public sealed interface Region permits Region.Cuboid, Region.Shape, Region.Cells, Region.Uploaded {
    /** A box holding every cell: the box of a cuboid or shape (a shape need not reach its edges), a cell set's bounds. */
    Box bounds();

    /** Whether the cell is in the region; false outside {@link #bounds()}. */
    boolean contains(int x, int y, int z);

    /** The number of cells, exact, saturating at {@link Long#MAX_VALUE}. */
    long cellCount();

    /**
     * The {@link BlockBuffer#key} of every 16³ section holding at least one cell, in {@link Box#forEachSectionKey}
     * order (section x, then z, then y).
     */
    long[] sectionKeys();

    Region translate(int dx, int dy, int dz);

    /** Every cell of the box. */
    record Cuboid(Box box) implements Region {
        public Cuboid {
            Objects.requireNonNull(box);
        }

        @Override
        public Box bounds() {
            return box;
        }

        @Override
        public boolean contains(int x, int y, int z) {
            return box.contains(x, y, z);
        }

        @Override
        public long cellCount() {
            return box.volume();
        }

        @Override
        public long[] sectionKeys() {
            LongArrayList keys = new LongArrayList();
            box.forEachSectionKey(keys::add);
            return keys.toLongArray();
        }

        @Override
        public Cuboid translate(int dx, int dy, int dz) {
            return new Cuboid(box.offset(dx, dy, dz));
        }
    }

    /**
     * The shape inscribed in {@code box}: the cells whose centres pass the shape's test, evaluated exactly in integers
     * (see {@code ShapeMath}), so client and server always agree and shapes are exactly symmetric. For
     * {@link ShapeKind#ELLIPSOID} the facing is ignored. A shape may have no cells at all (a 2×1×2 cone), which is legal:
     * operations on it change nothing.
     *
     * <p>A value with the equality of a record over (box, kind, facing); it is a class only so it can cache
     * {@link #cellCount()}, which costs one {@link #rowSpan} per row (sizeY × sizeZ rows) the first time. Callers
     * holding untrusted input bound the box before asking.
     */
    final class Shape implements Region {
        /** {@link #rowSpan} of a row without cells. Its minimum exceeds its maximum, so a loop over it does nothing. */
        public static final long EMPTY_ROW = ShapeMath.EMPTY;

        private final Box box;
        private final ShapeKind kind;
        private final Facing facing;
        private final ShapeMath math;
        /** -1 until counted. */
        private volatile long cellCount;

        public Shape(Box box, ShapeKind kind, Facing facing) {
            this(box, kind, facing, -1);
        }

        private Shape(Box box, ShapeKind kind, Facing facing, long cellCount) {
            this.box = Objects.requireNonNull(box);
            this.kind = Objects.requireNonNull(kind);
            this.facing = Objects.requireNonNull(facing);
            this.math = new ShapeMath(kind, facing, box.sizeX(), box.sizeY(), box.sizeZ(), false);
            this.cellCount = cellCount;
        }

        public Box box() {
            return box;
        }

        public ShapeKind kind() {
            return kind;
        }

        public Facing facing() {
            return facing;
        }

        @Override
        public Box bounds() {
            return box;
        }

        @Override
        public boolean contains(int x, int y, int z) {
            return box.contains(x, y, z)
                    && math.contains((long) x - box.min().x(), (long) y - box.min().y(), (long) z - box.min().z());
        }

        /**
         * The cells of row (y, z) along x: one interval, since every shape is convex. Packed as
         * {@code (long) minX << 32 | maxX} (read it with {@link #rowMin} and {@link #rowMax}), or {@link #EMPTY_ROW}
         * for a row without cells or outside the box. Agrees exactly with {@link #contains}.
         */
        public long rowSpan(int y, int z) {
            if (y < box.min().y() || y > box.max().y() || z < box.min().z() || z > box.max().z()) return EMPTY_ROW;
            long span = math.rowSpan((long) y - box.min().y(), (long) z - box.min().z());
            if (span == EMPTY_ROW) return EMPTY_ROW;
            int x0 = box.min().x();
            return ShapeMath.pack(x0 + ShapeMath.min(span), x0 + ShapeMath.max(span));
        }

        /** The first x of a {@link #rowSpan}. */
        public static int rowMin(long span) {
            return ShapeMath.min(span);
        }

        /** The last x of a {@link #rowSpan}. */
        public static int rowMax(long span) {
            return ShapeMath.max(span);
        }

        @Override
        public long cellCount() {
            long count = cellCount;
            if (count < 0) {
                count = 0;
                for (int z = box.min().z(); z <= box.max().z(); z++) {
                    for (int y = box.min().y(); y <= box.max().y(); y++) {
                        count = add(count, rowSpan(y, z));
                    }
                }
                cellCount = count;
            }
            return count;
        }

        /**
         * One pass over the rows (sizeY × sizeZ): per 16×16 slab of rows, the x sections its rows reach. Also counts
         * the cells.
         */
        @Override
        public long[] sectionKeys() {
            LongArrayList keys = new LongArrayList();
            long count = 0;
            long[] ranges = new long[256];
            for (int sz = box.min().z() >> 4; sz <= box.max().z() >> 4; sz++) {
                int zFrom = Math.max(box.min().z(), sz << 4), zTo = Math.min(box.max().z(), (sz << 4) + 15);
                for (int sy = box.min().y() >> 4; sy <= box.max().y() >> 4; sy++) {
                    int yFrom = Math.max(box.min().y(), sy << 4), yTo = Math.min(box.max().y(), (sy << 4) + 15);
                    int n = 0;
                    for (int z = zFrom; z <= zTo; z++) {
                        for (int y = yFrom; y <= yTo; y++) {
                            long span = rowSpan(y, z);
                            if (span == EMPTY_ROW) continue;
                            count = add(count, span);
                            ranges[n++] = ShapeMath.pack(rowMin(span) >> 4, rowMax(span) >> 4);
                        }
                    }
                    // Merge the rows' section ranges (sorted by their first section) into disjoint runs.
                    Arrays.sort(ranges, 0, n);
                    int i = 0;
                    while (i < n) {
                        int from = ShapeMath.min(ranges[i]), to = ShapeMath.max(ranges[i]);
                        for (i++; i < n && ShapeMath.min(ranges[i]) <= to + 1; i++) {
                            to = Math.max(to, ShapeMath.max(ranges[i]));
                        }
                        for (int sx = from; sx <= to; sx++) keys.add(BlockBuffer.key(sx, sy, sz));
                    }
                }
            }
            cellCount = count;
            long[] sorted = keys.toLongArray();
            SectionOrder.sort(sorted);
            return sorted;
        }

        /** The same shape moved; its cell count carries over. */
        @Override
        public Shape translate(int dx, int dy, int dz) {
            return new Shape(box.offset(dx, dy, dz), kind, facing, cellCount);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Shape other && box.equals(other.box) && kind == other.kind && facing == other.facing;
        }

        @Override
        public int hashCode() {
            return Objects.hash(box, kind, facing);
        }

        @Override
        public String toString() {
            return "Shape[box=" + box + ", kind=" + kind + ", facing=" + facing + "]";
        }

        private static long add(long count, long span) {
            if (span == EMPTY_ROW) return count;
            long cells = (long) rowMax(span) - rowMin(span) + 1;
            return count > Long.MAX_VALUE - cells ? Long.MAX_VALUE : count + cells;
        }
    }

    /** An explicit, non-empty set of cells (magic select). On the wire it travels as an upload ({@link Uploaded}). */
    record Cells(CellSet cells) implements Region {
        public Cells {
            Objects.requireNonNull(cells);
            if (cells.isEmpty()) throw new IllegalArgumentException("A cell region needs at least one cell");
        }

        @Override
        public Box bounds() {
            return cells.bounds();
        }

        @Override
        public boolean contains(int x, int y, int z) {
            return cells.contains(x, y, z);
        }

        @Override
        public long cellCount() {
            return cells.size();
        }

        @Override
        public long[] sectionKeys() {
            return cells.sectionKeys();
        }

        @Override
        public Cells translate(int dx, int dy, int dz) {
            return new Cells(cells.translate(dx, dy, dz));
        }
    }

    /**
     * A wire reference to a {@link CellSet} the server holds for this player, uploaded before ({@code SelectionUpload})
     * and named by its {@link CellSet#hash()}; the server resolves it to {@link Cells}. Only {@link #bounds()} and
     * {@link #cellCount()} answer here: {@link #contains}, {@link #sectionKeys} and {@link #translate} throw
     * {@link IllegalStateException}.
     */
    record Uploaded(Sha256 hash, Box bounds, long cellCount) implements Region {
        public Uploaded {
            Objects.requireNonNull(hash);
            Objects.requireNonNull(bounds);
            if (cellCount < 1 || cellCount > bounds.volume()) {
                throw new IllegalArgumentException("An uploaded region of " + cellCount + " cells in " + bounds);
            }
        }

        @Override
        public boolean contains(int x, int y, int z) {
            throw unresolved();
        }

        @Override
        public long[] sectionKeys() {
            throw unresolved();
        }

        @Override
        public Region translate(int dx, int dy, int dz) {
            throw unresolved();
        }

        private static IllegalStateException unresolved() {
            return new IllegalStateException("An uploaded region must be resolved to its cells first");
        }
    }
}
