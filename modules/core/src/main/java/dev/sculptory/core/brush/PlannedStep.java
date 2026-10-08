package dev.sculptory.core.brush;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.world.WorldReader;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * One brush step worked out at once and written in parts, so the server can spread a large step over several ticks: the
 * Heightmap brush's ({@link HeightmapKernel}), whose one dab may move a hundred thousand blocks. {@link #of} runs the
 * kernel against the world as it is (every read happens there, before any write, as always) and keeps the cells it
 * writes, in the order it writes them, with each cell's state at that moment; the parts then hand them on,
 * {@value #PART_CELLS} at a time.
 *
 * <p>Running every part with nothing else changing the world writes exactly what the kernel writes in one go, cell for
 * cell and in the same order, so the client's prediction (which runs the kernel whole) still equals the server's result.
 * Between parts the world may change: a cell that no longer holds the state the step saw when it was worked out is left
 * as it is ({@link #skipped}), so a part never writes over someone else's change with a stale plan. That check compares
 * with the state before the step, so it is meant for kernels that write each cell at most once in a step, as the
 * Heightmap kernel does (a cell written twice would be skipped the second time).
 *
 * <p>Memory: 16 bytes a cell (its position packed in one long, its new and old states), trimmed to the cells written.
 * Not thread-safe.
 */
public final class PlannedStep {
    /** Most cells a part writes: a few milliseconds of writes, a dedicated server's default brush budget. */
    public static final int PART_CELLS = 4_096;

    private final int count;
    /** Each cell's position ({@link #pack}), new state and state before the step. */
    private final long[] cells;
    private final int[] states, before;
    private final BlockEntityData[] tiles;
    private int next;
    private long skipped;

    private PlannedStep(int count, long[] cells, int[] states, int[] before, BlockEntityData[] tiles) {
        this.count = count;
        this.cells = cells;
        this.states = states;
        this.before = before;
        this.tiles = tiles;
    }

    /**
     * Works out a step: {@code kernel} applied to {@code dabs} against {@code w}, its cells kept rather than written (so
     * nothing in {@code w} changes). The kernel's checks and failures are its own.
     */
    public static PlannedStep of(BrushKernel kernel, BrushSpec s, List<Dab> dabs, StrokeState st, WorldReader w) {
        Objects.requireNonNull(kernel);
        Objects.requireNonNull(w);
        Collector collector = new Collector(w);
        kernel.applyStep(s, dabs, st, w, collector);
        return collector.build();
    }

    /** The cells the step writes. */
    public long cells() {
        return count;
    }

    /** The cells of the parts not run yet. */
    public long remainingCells() {
        return count - next;
    }

    /** Whether every part has run (a step that writes nothing has none). */
    public boolean done() {
        return next == count;
    }

    /** How many parts the step has. */
    public int parts() {
        return (count + PART_CELLS - 1) / PART_CELLS;
    }

    /** The cells the next part writes. */
    public long nextCells() {
        requireNext();
        return Math.min(PART_CELLS, count - next);
    }

    /** The box holding the next part's cells. */
    public Box nextArea() {
        requireNext();
        int[] b = bounds(next, Math.min(count, next + PART_CELLS), null);
        return new Box(new BlockPos(b[0], b[1], b[2]), new BlockPos(b[3], b[4], b[5]));
    }

    /**
     * The next part's cells as one box per chunk column they lie in, for the caller's checks (loaded chunks, locks)
     * before it runs: a part that crosses from one symmetric copy's area to another's covers nothing between them.
     */
    public List<Box> nextAreas() {
        requireNext();
        int end = Math.min(count, next + PART_CELLS);
        Long2ObjectLinkedOpenHashMap<int[]> columns = new Long2ObjectLinkedOpenHashMap<>();
        for (int i = next; i < end; i++) {
            int x = x(cells[i]), y = y(cells[i]), z = z(cells[i]);
            long key = ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
            int[] b = columns.get(key);
            if (b == null) columns.put(key, new int[] {x, y, z, x, y, z});
            else grow(b, x, y, z);
        }
        List<Box> areas = new ArrayList<>(columns.size());
        for (int[] b : columns.values()) areas.add(new Box(new BlockPos(b[0], b[1], b[2]), new BlockPos(b[3], b[4], b[5])));
        return areas;
    }

    /**
     * Writes the next part's cells to {@code out}, in the kernel's order; a cell that {@code now} shows holding another
     * state than the step saw is left out.
     */
    public void runNext(WorldReader now, CellSink out) {
        Objects.requireNonNull(now);
        Objects.requireNonNull(out);
        requireNext();
        int end = Math.min(count, next + PART_CELLS);
        for (int i = next; i < end; i++) {
            int x = x(cells[i]), y = y(cells[i]), z = z(cells[i]);
            if (now.get(x, y, z) != before[i]) {
                skipped++;
                continue;
            }
            BlockEntityData tile = tiles == null ? null : tiles[i];
            if (tile != null) {
                out.set(x, y, z, states[i], tile);
            } else {
                out.set(x, y, z, states[i]);
            }
        }
        next = end;
    }

    /** Runs every part left. */
    public void runAll(WorldReader now, CellSink out) {
        while (!done()) runNext(now, out);
    }

    /** The cells left out so far because the world changed under them between parts. */
    public long skipped() {
        return skipped;
    }

    private int[] bounds(int from, int to, int[] into) {
        int[] b = into;
        for (int i = from; i < to; i++) {
            int x = x(cells[i]), y = y(cells[i]), z = z(cells[i]);
            if (b == null) b = new int[] {x, y, z, x, y, z};
            else grow(b, x, y, z);
        }
        return b;
    }

    private static void grow(int[] b, int x, int y, int z) {
        b[0] = Math.min(b[0], x);
        b[1] = Math.min(b[1], y);
        b[2] = Math.min(b[2], z);
        b[3] = Math.max(b[3], x);
        b[4] = Math.max(b[4], y);
        b[5] = Math.max(b[5], z);
    }

    /** x and z in 26 bits each, y in 12: exact within the kernels' horizontal limit (2²⁵) and any build height. */
    static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    static int x(long cell) {
        return (int) (cell >> 38);
    }

    static int z(long cell) {
        return (int) ((cell << 26) >> 38);
    }

    static int y(long cell) {
        return (int) ((cell << 52) >> 52);
    }

    private void requireNext() {
        if (next == count) throw new IllegalStateException("The step is done");
    }

    /**
     * The kernel's sink: each cell with the state it held before the step, as the kernel passes it on
     * ({@link CellSink#changed}), else read from the world.
     */
    private static final class Collector implements CellSink {
        private final WorldReader world;
        private int count;
        private long[] cells = new long[256];
        private int[] states = new int[256], before = new int[256];
        private List<BlockEntityData> tiles;

        Collector(WorldReader world) {
            this.world = world;
        }

        @Override
        public void set(int x, int y, int z, int handle) {
            add(x, y, z, handle, world.get(x, y, z), null);
        }

        @Override
        public void set(int x, int y, int z, int handle, BlockEntityData tile) {
            add(x, y, z, handle, world.get(x, y, z), tile);
        }

        @Override
        public void changed(int x, int y, int z, int handle, int was) {
            add(x, y, z, handle, was, null);
        }

        private void add(int x, int y, int z, int handle, int was, BlockEntityData tile) {
            if (count == cells.length) {
                int size = count * 2;
                cells = Arrays.copyOf(cells, size);
                states = Arrays.copyOf(states, size);
                before = Arrays.copyOf(before, size);
            }
            if (tile != null && tiles == null) {
                tiles = new ArrayList<>(count + 1);
                for (int i = 0; i < count; i++) tiles.add(null);
            }
            cells[count] = pack(x, y, z);
            states[count] = handle;
            before[count] = was;
            if (tiles != null) tiles.add(tile);
            count++;
        }

        PlannedStep build() {
            return new PlannedStep(count, Arrays.copyOf(cells, count), Arrays.copyOf(states, count),
                    Arrays.copyOf(before, count), tiles == null ? null : tiles.toArray(new BlockEntityData[0]));
        }
    }
}
