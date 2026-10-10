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
import java.util.function.Supplier;

/**
 * One brush step worked out at once and written in parts, so the server can spread a large step over several ticks: the
 * Heightmap brush's ({@link HeightmapKernel}), whose one dab may move millions of blocks. {@link #of} runs the kernel
 * against the world as it is (every read happens there, before any write, as always) and keeps the cells it writes, in
 * the order it writes them, with each cell's state at that moment; the parts then hand them on, {@value #PART_CELLS} at
 * a time. {@link #planner} works a Heightmap step out the same way, a piece at a time, so the working out spreads over
 * several ticks too.
 *
 * <p>Running every part with nothing else changing the world writes exactly what the kernel writes in one go, cell for
 * cell and in the same order, so the client's prediction (which runs the kernel whole) still equals the server's result.
 * Between parts the world may change: a column any of whose cells no longer holds the state the step saw when it was
 * worked out is left as it is, whole ({@link #skipped}), so a part never writes over someone else's change with a
 * stale plan, nor leaves a column half raised or half cut by it. That check compares with the state before the step,
 * so it is meant for kernels that write each cell at most once in a step and a column's cells one after another, as
 * the Heightmap kernel does; a part never splits a column.
 *
 * <p>Memory: 20 bytes a cell (its position, its new and old states), in pages, so a step of millions of cells is never
 * copied whole. Not thread-safe.
 */
public final class PlannedStep {
    /**
     * Cells a part writes, then on to the end of its last column: a few milliseconds of writes, a dedicated server's
     * default brush budget.
     */
    public static final int PART_CELLS = 4_096;

    private static final int PAGE_BITS = CellPlan.PAGE_BITS;
    private static final int PAGE_MASK = CellPlan.PAGE_MASK;
    private static final int FIELDS = CellPlan.FIELDS;

    private final int count;
    /** Each cell's x, y, z, new state and state before the step ({@link CellPlan#FIELDS} ints), in pages. */
    private final int[][] pages;
    private final BlockEntityData[] tiles;
    private int next;
    private long skipped;

    private PlannedStep(int count, int[][] pages, BlockEntityData[] tiles) {
        this.count = count;
        this.pages = pages;
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

    /**
     * Starts working out a step a piece at a time ({@link Planner#advance}); when done, {@link Planner#step()} equals what
     * {@link #of} gives for {@code kernel} wrapped in the global mask whose test is {@code mask}, if the world did not
     * change meanwhile. Only the Heightmap kernel is worked out in pieces; any other kernel is worked out whole on the
     * first {@link Planner#advance}, and then takes no {@code mask} (wrap it instead).
     *
     * @param kernel the brush's own kernel ({@code BrushKernels.forTool}), not wrapped in the global mask
     * @param mask the global mask's test against {@code w} before the step ({@code MaskedKernel.judge}), or {@code null}
     *     when it is off
     */
    public static Planner planner(BrushKernel kernel, BrushSpec s, List<Dab> dabs, StrokeState st, WorldReader w,
                                  CellFilter mask) {
        return planner(kernel, s, dabs, st, w, mask, HeightmapSpec.MAX_STEP_CELLS);
    }

    /**
     * {@link #planner(BrushKernel, BrushSpec, List, StrokeState, WorldReader, CellFilter)}, refusing a Heightmap step of
     * more than {@code maxCells} planned cells ({@link StepTooLargeException}; at most
     * {@link HeightmapSpec#MAX_STEP_CELLS} whatever is asked).
     */
    public static Planner planner(BrushKernel kernel, BrushSpec s, List<Dab> dabs, StrokeState st, WorldReader w,
                                  CellFilter mask, long maxCells) {
        Objects.requireNonNull(kernel);
        if (kernel instanceof HeightmapKernel) {
            HeightmapKernel.Pass pass = HeightmapKernel.start(s, dabs, st, w);
            pass.limit(maxCells);
            return new Planner(pass, mask, null);
        }
        if (mask != null) throw new IllegalArgumentException("Only the Heightmap kernel takes the global mask's test here");
        Objects.requireNonNull(w);
        return new Planner(null, null, () -> of(kernel, s, dabs, st, w));
    }

    /**
     * A step being worked out a piece at a time: the Heightmap kernel's columns, then the fluid refill of the cells it
     * removes, then the cells that change (and that the global mask lets change) kept in plan order.
     */
    public static final class Planner {
        private final HeightmapKernel.Pass pass;
        private final CellFilter mask;
        private final Supplier<PlannedStep> whole;
        private int stage;
        private PlannedStep step;

        private Planner(HeightmapKernel.Pass pass, CellFilter mask, Supplier<PlannedStep> whole) {
            this.pass = pass;
            this.mask = mask;
            this.whole = whole;
        }

        /**
         * Works on until {@code deadline} ({@code System.nanoTime}), at least a small piece; returns whether the step is
         * worked out ({@link #step()}).
         *
         * @throws StepTooLargeException when the step writes more than its kernel allows (nothing of it is kept)
         */
        public boolean advance(long deadline) {
            if (step != null) return true;
            if (pass == null) {
                step = whole.get();
                return true;
            }
            if (stage == 0) {
                if (!pass.advance(deadline)) return false;
                stage = 1;
                if (System.nanoTime() >= deadline) return false;
            }
            CellPlan cells = pass.cells();
            if (stage == 1) {
                if (!cells.resolve(deadline)) return false;
                stage = 2;
                if (System.nanoTime() >= deadline) return false;
            }
            if (!cells.compact(mask, deadline)) return false;
            step = new PlannedStep(cells.size(), cells.pages(), null);
            return true;
        }

        /** Whether {@link #advance} has worked the whole step out. */
        public boolean done() {
            return step != null;
        }

        /** The step worked out. */
        public PlannedStep step() {
            if (step == null) throw new IllegalStateException("The step is not worked out yet");
            return step;
        }
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

    /**
     * How many parts are left: a part runs on to the end of its last column, so there may be fewer than the cells over
     * {@value #PART_CELLS}.
     */
    public int parts() {
        int parts = 0;
        int saved = next;
        while (next < count) {
            next = partEnd();
            parts++;
        }
        next = saved;
        return parts;
    }

    /** The cells the next part writes. */
    public long nextCells() {
        requireNext();
        return partEnd() - next;
    }

    /** The box holding the next part's cells. */
    public Box nextArea() {
        requireNext();
        int[] b = null;
        int end = partEnd();
        for (int i = next; i < end; i++) {
            int x = field(i, CellPlan.X), y = field(i, CellPlan.Y), z = field(i, CellPlan.Z);
            if (b == null) b = new int[] {x, y, z, x, y, z};
            else grow(b, x, y, z);
        }
        return new Box(new BlockPos(b[0], b[1], b[2]), new BlockPos(b[3], b[4], b[5]));
    }

    /**
     * The next part's cells as one box per chunk column they lie in, for the caller's checks (loaded chunks, locks)
     * before it runs: a part that crosses from one symmetric copy's area to another's covers nothing between them.
     */
    public List<Box> nextAreas() {
        requireNext();
        int end = partEnd();
        Long2ObjectLinkedOpenHashMap<int[]> columns = new Long2ObjectLinkedOpenHashMap<>();
        for (int i = next; i < end; i++) {
            int x = field(i, CellPlan.X), y = field(i, CellPlan.Y), z = field(i, CellPlan.Z);
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
     * Writes the next part's cells to {@code out}, in the kernel's order. A column whose cells {@code now} shows holding
     * any other state than the step saw is left out whole (its cells counted {@link #skipped}): a column raised or cut
     * only in part would leave a block hanging over the change, or a hole under it.
     */
    public void runNext(WorldReader now, CellSink out) {
        Objects.requireNonNull(now);
        Objects.requireNonNull(out);
        requireNext();
        int end = partEnd();
        int i = next;
        while (i < end) {
            int x = field(i, CellPlan.X), z = field(i, CellPlan.Z);
            int run = i + 1;
            while (run < end && field(run, CellPlan.X) == x && field(run, CellPlan.Z) == z) run++;
            boolean unchanged = true;
            for (int k = i; k < run && unchanged; k++) {
                unchanged = now.get(x, field(k, CellPlan.Y), z) == field(k, CellPlan.ORIGINAL);
            }
            if (!unchanged) {
                skipped += run - i;
                i = run;
                continue;
            }
            for (int k = i; k < run; k++) {
                int y = field(k, CellPlan.Y), state = field(k, CellPlan.NEXT);
                BlockEntityData tile = tiles == null ? null : tiles[k];
                if (tile != null) {
                    out.set(x, y, z, state, tile);
                } else {
                    out.set(x, y, z, state);
                }
            }
            i = run;
        }
        next = end;
        // Each page the parts have passed goes at once (and every one with the last part).
        if (next == count) {
            Arrays.fill(pages, null);
        } else {
            for (int p = (next >>> PAGE_BITS) - 1; p >= 0 && pages[p] != null; p--) pages[p] = null;
        }
    }

    /** Pages still held (tests: the parts let go of what they have written). */
    int heldPages() {
        int held = 0;
        for (int[] page : pages) {
            if (page != null) held++;
        }
        return held;
    }

    /** Runs every part left. */
    public void runAll(WorldReader now, CellSink out) {
        while (!done()) runNext(now, out);
    }

    /** The cells left out so far because the world changed under them between parts. */
    public long skipped() {
        return skipped;
    }


    /**
     * Where the next part ends: {@value #PART_CELLS} cells on, then on to the end of the column it stops in (a column's
     * cells are planned together, at most a few hundred), so a part never splits a column.
     */
    private int partEnd() {
        int end = Math.min(count, next + PART_CELLS);
        if (end == count) return end;
        int x = field(end - 1, CellPlan.X), z = field(end - 1, CellPlan.Z);
        while (end < count && field(end, CellPlan.X) == x && field(end, CellPlan.Z) == z) end++;
        return end;
    }
    private int field(int i, int k) {
        return pages[i >>> PAGE_BITS][(i & PAGE_MASK) * FIELDS + k];
    }

    private static void grow(int[] b, int x, int y, int z) {
        b[0] = Math.min(b[0], x);
        b[1] = Math.min(b[1], y);
        b[2] = Math.min(b[2], z);
        b[3] = Math.max(b[3], x);
        b[4] = Math.max(b[4], y);
        b[5] = Math.max(b[5], z);
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
        private int[][] pages = new int[4][];
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
            int page = count >>> PAGE_BITS;
            if (page == pages.length) pages = Arrays.copyOf(pages, pages.length * 2);
            if (pages[page] == null) pages[page] = new int[CellPlan.PAGE * FIELDS];
            if (tile != null && tiles == null) {
                tiles = new ArrayList<>(count + 1);
                for (int i = 0; i < count; i++) tiles.add(null);
            }
            int at = (count & PAGE_MASK) * FIELDS;
            int[] p = pages[page];
            p[at + CellPlan.X] = x;
            p[at + CellPlan.Y] = y;
            p[at + CellPlan.Z] = z;
            p[at + CellPlan.NEXT] = handle;
            p[at + CellPlan.ORIGINAL] = was;
            if (tiles != null) tiles.add(tile);
            count++;
        }

        PlannedStep build() {
            return new PlannedStep(count, pages, tiles == null ? null : tiles.toArray(new BlockEntityData[0]));
        }
    }
}
