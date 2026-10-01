package dev.sculptory.core.generate;

import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.edit.SectionOrder;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.Regions;
import java.util.Objects;

/**
 * What a generator produces: a sparse set of world cells
 * (absent = don't touch), each with a state handle of the state space it was generated in. Air is a state like any
 * other: an air cell is written (it clears what is there). Immutable; built with {@link #builder}, which refuses the
 * cell that would pass its cap.
 */
public final class GeneratedSource {
    private static final GeneratedSource EMPTY = new GeneratedSource(new BlockBuffer(), null, 0);

    private final BlockBuffer blocks;
    /** Null when empty. */
    private final Box bounds;
    private final long cells;
    private CellSet cellSet;

    private GeneratedSource(BlockBuffer blocks, Box bounds, long cells) {
        this.blocks = blocks;
        this.bounds = bounds;
        this.cells = cells;
    }

    public static GeneratedSource empty() {
        return EMPTY;
    }

    /** A builder taking at most {@code maxCells} cells. */
    public static Builder builder(long maxCells) {
        return new Builder(maxCells);
    }

    /** Visits a present cell. */
    @FunctionalInterface
    public interface CellVisitor {
        void visit(int x, int y, int z, int state);
    }

    /** The state at a world cell, or -1 when the cell is absent. */
    public int get(int x, int y, int z) {
        return bounds != null && bounds.contains(x, y, z) ? blocks.get(x, y, z) : -1;
    }

    public boolean contains(int x, int y, int z) {
        return get(x, y, z) >= 0;
    }

    /** Present cells. */
    public long cells() {
        return cells;
    }

    public boolean isEmpty() {
        return cells == 0;
    }

    /**
     * The exact bounds of the present cells.
     *
     * @throws IllegalStateException when empty
     */
    public Box bounds() {
        if (bounds == null) throw new IllegalStateException("An empty source has no bounds");
        return bounds;
    }

    /** The present cells as a set (built once). */
    public CellSet cellSet() {
        CellSet set = cellSet;
        if (set == null) {
            CellSet.Builder builder = CellSet.builder();
            forEach((x, y, z, state) -> builder.add(x, y, z));
            set = builder.build();
            cellSet = set;
        }
        return set;
    }

    /**
     * Visits every present cell in {@link CellSet} order: sections by (sx, sz, sy), cells within a section by
     * {@code (y << 8) | (z << 4) | x}. The order the sparse upload writes and reads cells in.
     */
    public void forEach(CellVisitor visitor) {
        Objects.requireNonNull(visitor);
        if (cells == 0) return;
        long[] keys = blocks.sortedKeys();
        SectionOrder.sort(keys);
        for (long key : keys) {
            SectionBuffer section = blocks.section(key);
            int ox = BlockBuffer.keyX(key) << 4, oy = BlockBuffer.keyY(key) << 4, oz = BlockBuffer.keyZ(key) << 4;
            section.forEachPresent(i -> visitor.visit(ox + SectionBuffer.localX(i), oy + SectionBuffer.localY(i),
                    oz + SectionBuffer.localZ(i), section.get(i)));
        }
    }

    /**
     * Visits the present cells of {@code set} that this source holds, in the set's order (the sparse upload's cell
     * order, which is the same as {@link #forEach} when the set is {@link #cellSet()}).
     */
    public static void forEachInOrder(CellSet set, GeneratedSource source, CellVisitor visitor) {
        Objects.requireNonNull(set);
        Objects.requireNonNull(visitor);
        if (set.isEmpty()) return;
        Region region = new Region.Cells(set);
        int[] rows = new int[Regions.ROWS];
        for (long key : set.sectionKeys()) {
            if (Regions.rows(region, key, rows) == 0) continue;
            int ox = BlockBuffer.keyX(key) << 4, oy = BlockBuffer.keyY(key) << 4, oz = BlockBuffer.keyZ(key) << 4;
            for (int r = 0; r < Regions.ROWS; r++) {
                int y = oy + (r >>> 4), z = oz + (r & 15);
                for (int bits = rows[r]; bits != 0; bits &= bits - 1) {
                    int x = ox + Integer.numberOfTrailingZeros(bits);
                    visitor.visit(x, y, z, source == null ? -1 : source.get(x, y, z));
                }
            }
        }
    }

    /** A deep copy of the cells, in world coordinates. */
    public BlockBuffer copyBlocks() {
        BlockBuffer copy = new BlockBuffer();
        for (long key : blocks.sortedKeys()) copy.putSection(key, blocks.section(key).copy());
        return copy;
    }

    @Override
    public String toString() {
        return "GeneratedSource[" + cells + " cells" + (bounds == null ? "" : " in " + bounds) + "]";
    }

    /** Collects cells; a cell set twice keeps the later state and counts once. Not thread-safe. */
    public static final class Builder {
        private final long maxCells;
        private final BlockBuffer blocks = new BlockBuffer();
        private long cells;

        private Builder(long maxCells) {
            if (maxCells < 0) throw new IllegalArgumentException("Negative cell cap");
            this.maxCells = maxCells;
        }

        /**
         * Sets a cell.
         *
         * @throws GeneratedTooLargeException when this cell would be one over the cap
         * @throws IllegalArgumentException for a negative state or a cell outside the storable range
         */
        public Builder set(int x, int y, int z, int state) {
            if (state < 0) throw new IllegalArgumentException("Negative state handle");
            if (!blocks.has(x, y, z)) {
                if (cells + 1 > maxCells) throw new GeneratedTooLargeException(maxCells);
                cells++;
            }
            blocks.set(x, y, z, state);
            return this;
        }

        /** Whether the cell is set so far. */
        public boolean has(int x, int y, int z) {
            return blocks.has(x, y, z);
        }

        /** The state set so far, or -1. */
        public int get(int x, int y, int z) {
            return blocks.get(x, y, z);
        }

        public long cells() {
            return cells;
        }

        public GeneratedSource build() {
            if (cells == 0) return EMPTY;
            BlockBuffer compact = blocks.compact();
            return new GeneratedSource(compact, compact.bounds(), cells);
        }
    }
}
