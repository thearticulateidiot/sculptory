package dev.sculptory.core.scatter;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockEntityData;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Arrays;
import java.util.Objects;

/**
 * What one vanilla tree or feature grew at one spot ({@link ScatterSource.Feature}): the world cells it writes, each with the state it writes (air included: a cell it clears), the block
 * entity it gives that cell (a bee nest's bees), and the state the cell held when it grew ({@link #before}, what a
 * commit expects to find there still). Cells are in world coordinates, each once, in the order they were first
 * written. Immutable once built.
 */
public final class GrownFeature {
    /** Packed coordinates reach ±2²⁵ horizontally (the planner's range) and ±2¹¹ vertically. */
    static final int MAX_XZ = 1 << 25;
    static final int MAX_Y = 1 << 11;

    private final long[] cells;
    private final int[] after;
    private final int[] before;
    private final Int2ObjectOpenHashMap<BlockEntityData> tiles;
    private final Box bounds;

    private GrownFeature(long[] cells, int[] after, int[] before, Int2ObjectOpenHashMap<BlockEntityData> tiles,
                         Box bounds) {
        this.cells = cells;
        this.after = after;
        this.before = before;
        this.tiles = tiles;
        this.bounds = bounds;
    }

    /** A builder; a cell set again keeps its first before state and takes the new state and tile. */
    public static Builder builder() {
        return new Builder();
    }

    /** Packs a world cell: x and z in [-2²⁵, 2²⁵), y in [-2¹¹, 2¹¹). */
    public static long pack(int x, int y, int z) {
        if (x < -MAX_XZ || x >= MAX_XZ || z < -MAX_XZ || z >= MAX_XZ || y < -MAX_Y || y >= MAX_Y) {
            throw new IllegalArgumentException("Cell out of range: " + x + ", " + y + ", " + z);
        }
        return ((long) (x + MAX_XZ) << 38) | ((long) (z + MAX_XZ) << 12) | (y + MAX_Y);
    }

    public static int unpackX(long packed) {
        return (int) (packed >>> 38) - MAX_XZ;
    }

    public static int unpackZ(long packed) {
        return (int) ((packed >>> 12) & ((1L << 26) - 1)) - MAX_XZ;
    }

    public static int unpackY(long packed) {
        return (int) (packed & 0xFFF) - MAX_Y;
    }

    /** The cells written; never 0 for a built one. */
    public int size() {
        return cells.length;
    }

    /** Cell {@code i}, packed ({@link #pack}). */
    public long cell(int i) {
        return cells[i];
    }

    public int x(int i) {
        return unpackX(cells[i]);
    }

    public int y(int i) {
        return unpackY(cells[i]);
    }

    public int z(int i) {
        return unpackZ(cells[i]);
    }

    /** The state cell {@code i} is given. */
    public int after(int i) {
        return after[i];
    }

    /** The state cell {@code i} held when the feature grew (the world's, or an earlier placement's). */
    public int before(int i) {
        return before[i];
    }

    /** The block entity cell {@code i} is given, or {@code null} (then a block entity state gets its default one). */
    public BlockEntityData tile(int i) {
        return tiles.get(i);
    }

    /** Cells with a block entity of their own. */
    public int tileCount() {
        return tiles.size();
    }

    /** The exact bounds of the cells. */
    public Box bounds() {
        return bounds;
    }

    /** About how many heap bytes this holds. */
    public long estimatedBytes() {
        long bytes = 64 + 16L * cells.length;
        for (BlockEntityData tile : tiles.values()) bytes += 32 + tile.estimatedBytes();
        return bytes;
    }

    @Override
    public String toString() {
        return "GrownFeature[" + cells.length + " cells in " + bounds + "]";
    }

    /** Collects a growth's cells. Not thread-safe. */
    public static final class Builder {
        private final Long2IntOpenHashMap index = new Long2IntOpenHashMap();
        private long[] cells = new long[64];
        private int[] after = new int[64];
        private int[] before = new int[64];
        private final Int2ObjectOpenHashMap<BlockEntityData> tiles = new Int2ObjectOpenHashMap<>();
        private int size;

        private Builder() {
            index.defaultReturnValue(-1);
        }

        /**
         * Writes {@code state} (with {@code tile}, or none) to world cell (x, y, z), which held {@code previous} before
         * this growth first wrote it.
         */
        public Builder set(int x, int y, int z, int state, BlockEntityData tile, int previous) {
            if (state < 0 || previous < 0) throw new IllegalArgumentException("Negative state handle");
            long packed = pack(x, y, z);
            int i = index.get(packed);
            if (i < 0) {
                i = size++;
                if (i == cells.length) {
                    int grown = cells.length * 2;
                    cells = Arrays.copyOf(cells, grown);
                    after = Arrays.copyOf(after, grown);
                    before = Arrays.copyOf(before, grown);
                }
                index.put(packed, i);
                cells[i] = packed;
                before[i] = previous;
            }
            after[i] = state;
            if (tile != null) {
                tiles.put(i, tile);
            } else {
                tiles.remove(i);
            }
            return this;
        }

        /** Whether the cell was written so far. */
        public boolean has(int x, int y, int z) {
            return index.get(pack(x, y, z)) >= 0;
        }

        /** The state written to the cell so far, or -1. */
        public int get(int x, int y, int z) {
            int i = index.get(pack(x, y, z));
            return i < 0 ? -1 : after[i];
        }

        /** The block entity written to the cell so far, or {@code null}. */
        public BlockEntityData tile(int x, int y, int z) {
            int i = index.get(pack(x, y, z));
            return i < 0 ? null : tiles.get(i);
        }

        /** Cells written so far. */
        public int size() {
            return size;
        }

        /**
         * The growth, without the cells whose state ends as it was (a write that changed nothing); {@code null} when no
         * cell is left.
         */
        public GrownFeature build() {
            int kept = 0;
            long[] keptCells = new long[size];
            int[] keptAfter = new int[size], keptBefore = new int[size];
            Int2ObjectOpenHashMap<BlockEntityData> keptTiles = new Int2ObjectOpenHashMap<>();
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
            int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            for (int i = 0; i < size; i++) {
                BlockEntityData tile = tiles.get(i);
                if (after[i] == before[i] && tile == null) continue;
                keptCells[kept] = cells[i];
                keptAfter[kept] = after[i];
                keptBefore[kept] = before[i];
                if (tile != null) keptTiles.put(kept, tile);
                int x = unpackX(cells[i]), y = unpackY(cells[i]), z = unpackZ(cells[i]);
                x0 = Math.min(x0, x);
                y0 = Math.min(y0, y);
                z0 = Math.min(z0, z);
                x1 = Math.max(x1, x);
                y1 = Math.max(y1, y);
                z1 = Math.max(z1, z);
                kept++;
            }
            if (kept == 0) return null;
            return new GrownFeature(Arrays.copyOf(keptCells, kept), Arrays.copyOf(keptAfter, kept),
                    Arrays.copyOf(keptBefore, kept), keptTiles,
                    new Box(new BlockPos(x0, y0, z0), new BlockPos(x1, y1, z1)));
        }
    }

    /** Test and planner access: equality of content (cells, states and tiles by content). */
    boolean sameContent(GrownFeature other) {
        Objects.requireNonNull(other);
        if (!Arrays.equals(cells, other.cells) || !Arrays.equals(after, other.after)
                || !Arrays.equals(before, other.before) || tiles.size() != other.tiles.size()) {
            return false;
        }
        for (Int2ObjectOpenHashMap.Entry<BlockEntityData> entry : tiles.int2ObjectEntrySet()) {
            BlockEntityData mine = entry.getValue();
            if (!mine.sameContent(other.tiles.get(entry.getIntKey()))) return false;
        }
        return true;
    }
}
