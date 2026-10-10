package dev.sculptory.core.brush;

import dev.sculptory.core.Box;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Arrays;

/**
 * The cells one brush step plans to write, in the order they are planned, and the fluid refill of the cells it
 * removes: shared by the column kernels ({@link TerrainKernel}, {@link HeightmapKernel}, {@link WeatherKernel}) and the
 * Surface mode ({@link SurfaceKernel}).
 *
 * <p>A planned cell becomes a state, or {@link #FILL}: removed, it becomes the {@link StateSpace#fluidSource} supplied
 * by itself, the cell above or a horizontal neighbour (in that order: above, north, south, west, east), else air. Only
 * fluid source blocks, waterlogged blocks and blocks that always hold source fluid (seagrass) supply fluid; flowing and
 * falling fluid do not. Removed cells next to or below a filled cell fill the same way. Cells outside the clip box are
 * never planned, so they keep their state and the refill sees them as they are. {@link #emit} sends each planned cell
 * whose state changes, in plan order; every read happens before the first cell is sent.
 *
 * <p>A large step (a Heightmap stamp plans millions of cells) is finished in pieces: {@link #resolve} works out the
 * refill a piece at a time until a deadline, and {@link #compact} then keeps the changed cells in place for a
 * {@link PlannedStep}. The cells are held in pages ({@value #PAGE} cells, 20 bytes each), so the plan grows without
 * copying, and a plan may be given a limit past which {@link #plan} throws {@link StepTooLargeException}.
 */
final class CellPlan {
    /** A removed cell: fluid or air, decided by {@link #emit}. */
    static final int FILL = -2;

    /** How a step's cells are keyed, and which columns it saw loaded. */
    interface Layout {
        /**
         * A key for cell (x, y, z), unique among the cells the step may plan; cells it never plans may share
         * {@link Long#MIN_VALUE}.
         */
        long key(int x, int y, int z);

        /** Whether column (x, z)'s chunk is loaded, as the step saw it ({@code false} for columns it never read). */
        boolean loaded(int x, int z);
    }

    /** Cells per page of the plan's storage. */
    static final int PAGE_BITS = 14;
    static final int PAGE = 1 << PAGE_BITS;
    static final int PAGE_MASK = PAGE - 1;
    /** Each cell's ints in its page: x, y, z, what it becomes, and its state now. */
    static final int FIELDS = 5;
    static final int X = 0, Y = 1, Z = 2, NEXT = 3, ORIGINAL = 4;
    /** Cells a piece of {@link #resolve} or {@link #compact} handles between looks at the clock. */
    private static final int CHECK_EVERY = 4_096;

    private final WorldReader world;
    private final StateSpace states;
    private final Box clip;
    private final Layout layout;
    /** The most cells this plan may hold ({@link StepTooLargeException} past it). */
    private long limit = Long.MAX_VALUE;

    private int count;
    private int[][] pages = new int[4][];
    /** The planned cells' bounds. */
    private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
    private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
    /**
     * Planned cells by key, the latest plan of a key winning; built lazily, only for {@link #planned}, up to
     * {@link #indexed} cells.
     */
    private final Long2IntOpenHashMap index = new Long2IntOpenHashMap();
    private int indexed;
    /** Whether any cell is planned as {@link #FILL}. */
    private boolean anyFill;

    /** How far {@link #resolve} has come. */
    private Stage stage = Stage.PLANNING;
    private int cursor;
    /** Per planned cell, the fluid it fills with plus one (0: none yet); then the cells filled, in the order found. */
    private Ints fluid;
    private Ints queue;
    private int head, tail;
    /** Cells {@link #compact} has kept so far. */
    private int kept;
    /** The fluid refill's dense lookup over the planned cells' bounds ({@link #startLookup}), or {@code null}. */
    private int[] dense;
    private int denseX, denseY, denseZ, denseW, denseH, denseD;
    /** Otherwise: per chunk column, its y range (packed) and then a dense block over it. */
    private Long2LongOpenHashMap columnRanges;
    /** Per chunk column, its planned cells (for the choice of a dense block or the sparse index). */
    private Long2IntOpenHashMap columnCounts;
    /** The cells of sparse chunk columns, by {@link #exactKey}: one more than the plan index. */
    private Long2IntOpenHashMap sparse;
    /** Ints the lookup took at most: dense grid or blocks, and eight a sparse cell (tests bound it). */
    private long lookupInts;
    private Long2ObjectOpenHashMap<Block> blocks;
    private long lastBlockKey = Long.MIN_VALUE;
    private Block lastBlock;
    /** The largest dense lookup over the bounds: 2 M cells (8 MB). */
    private static final int MAX_DENSE_CELLS = 1 << 21;

    private enum Stage { PLANNING, RANGES, LOOKUP, SEED, SPREAD, RESOLVED, COMPACTED }

    /** Ints by index, 0 until set, held in pages made as they are first written (no large array to clear at once). */
    private static final class Ints {
        private int[][] pages = new int[16][];

        int get(int i) {
            int page = i >>> PAGE_BITS;
            return page < pages.length && pages[page] != null ? pages[page][i & PAGE_MASK] : 0;
        }

        void set(int i, int value) {
            int page = i >>> PAGE_BITS;
            if (page >= pages.length) pages = Arrays.copyOf(pages, Math.max(page + 1, pages.length * 2));
            if (pages[page] == null) pages[page] = new int[PAGE];
            pages[page][i & PAGE_MASK] = value;
        }
    }

    /**
     * One chunk column's lookup: dense over the y range its cells take when that is at most 16 ints for each of its cells,
     * else ({@code cells == null}) its cells are in the shared {@link #sparse} index, so a few cells far apart in height
     * never take a column-wide block.
     */
    private static final class Block {
        final int y0, height;
        final int[] cells;

        Block(int y0, int y1, int count) {
            this.y0 = y0;
            this.height = y1 - y0 + 1;
            this.cells = 256L * height <= 16L * count ? new int[256 * height] : null;
        }
    }

    /** @param clip the only cells written, or {@code null} */
    CellPlan(WorldReader world, Box clip, Layout layout) {
        this.world = world;
        this.states = world.states();
        this.clip = clip;
        this.layout = layout;
        index.defaultReturnValue(-1);
    }

    /** Refuses ({@link StepTooLargeException}) to plan more than {@code limit} cells. */
    void limit(long limit) {
        this.limit = limit;
    }

    /** Plans cell (x, y, z) to become {@code next} ({@link #FILL}: removed); dropped outside the clip box. */
    void plan(int x, int y, int z, int next, int current) {
        if (clip != null && !clip.contains(x, y, z)) return;
        if (stage != Stage.PLANNING) throw new IllegalStateException("The plan is being finished");
        if (count >= limit) throw new StepTooLargeException(limit);
        int page = count >>> PAGE_BITS;
        if (page == pages.length) pages = Arrays.copyOf(pages, pages.length * 2);
        if (pages[page] == null) pages[page] = new int[PAGE * FIELDS];
        int at = (count & PAGE_MASK) * FIELDS;
        int[] p = pages[page];
        p[at + X] = x;
        p[at + Y] = y;
        p[at + Z] = z;
        p[at + NEXT] = next;
        p[at + ORIGINAL] = current;
        if (next == FILL) anyFill = true;
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        minZ = Math.min(minZ, z);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
        maxZ = Math.max(maxZ, z);
        count++;
    }

    /** Whether cell (x, y, z) is planned already. */
    boolean planned(int x, int y, int z) {
        catchUpIndex();
        long key = layout.key(x, y, z);
        return key != Long.MIN_VALUE && index.get(key) >= 0;
    }

    /** Brings the key index up to the cells planned so far, in plan order (a later plan of a key replaces the earlier). */
    private void catchUpIndex() {
        for (; indexed < count; indexed++) index.put(layout.key(x(indexed), y(indexed), z(indexed)), indexed);
    }

    /** Cells planned so far. */
    int size() {
        return count;
    }

    int x(int e) {
        return field(e, X);
    }

    int y(int e) {
        return field(e, Y);
    }

    int z(int e) {
        return field(e, Z);
    }

    /** What cell {@code e} becomes ({@link #FILL}: removed). */
    int next(int e) {
        return field(e, NEXT);
    }

    /** The state cell {@code e} holds now. */
    int current(int e) {
        return field(e, ORIGINAL);
    }

    private int field(int e, int k) {
        return pages[e >>> PAGE_BITS][(e & PAGE_MASK) * FIELDS + k];
    }

    /** Resolves the removed cells' fluid, then sends every planned cell whose state changes, in plan order. */
    void emit(CellSink out) {
        resolve(Long.MAX_VALUE);
        int air = states.air();
        for (int e = 0; e < count; e++) {
            int next = resolved(e, air);
            int original = current(e);
            if (next != original) out.changed(x(e), y(e), z(e), next, original);
        }
    }

    /**
     * Works out the removed cells' fluid, a piece at a time: at least {@value #CHECK_EVERY} cells' worth of work, then
     * more until {@code deadline} ({@code System.nanoTime}). Returns whether it is done; nothing may be planned after the
     * first call. The result is the same however it is cut into pieces.
     */
    boolean resolve(long deadline) {
        if (stage == Stage.PLANNING) {
            if (!anyFill) {
                stage = Stage.RESOLVED;
                return true;
            }
            fluid = new Ints();
            queue = new Ints();
            startLookup();
        }
        int work = 0;
        while (stage != Stage.RESOLVED && stage != Stage.COMPACTED) {
            if (work >= CHECK_EVERY) {
                if (System.nanoTime() >= deadline) return false;
                work = 0;
            }
            switch (stage) {
                case RANGES -> {
                    int end = Math.min(count, cursor + CHECK_EVERY);
                    for (int e = cursor; e < end; e++) {
                        long key = columnKey(x(e), z(e));
                        int y = y(e);
                        long range = columnRanges.get(key);
                        columnCounts.addTo(key, 1);
                        columnRanges.put(key, range == Long.MIN_VALUE ? pack(y, y)
                                : pack(Math.min(y, (int) (range >> 32)), Math.max(y, (int) range)));
                    }
                    work += end - cursor;
                    cursor = end;
                    if (cursor == count) {
                        cursor = 0;
                        stage = Stage.LOOKUP;
                    }
                }
                case LOOKUP -> {
                    // One more than the cell's plan index (0: none); a later plan of a cell replaces the earlier.
                    int end = Math.min(count, cursor + CHECK_EVERY);
                    for (int e = cursor; e < end; e++) {
                        int x = x(e), y = y(e), z = z(e);
                        if (dense != null) {
                            dense[((x - denseX) * denseD + (z - denseZ)) * denseH + (y - denseY)] = e + 1;
                        } else {
                            Block block = block(x, z, true);
                            if (block.cells == null) {
                                sparse.put(exactKey(x, y, z), e + 1);
                                continue;
                            }
                            block.cells[(((x & 15) << 4) | (z & 15)) * block.height + (y - block.y0)] = e + 1;
                        }
                    }
                    work += end - cursor;
                    cursor = end;
                    if (cursor == count) {
                        cursor = 0;
                        columnRanges = null;
                        columnCounts = null;
                        // A hash entry is a long and an int, in a table at most three quarters full that doubles: up to 8 ints.
                        if (sparse != null) lookupInts += 8L * sparse.size();
                        stage = Stage.SEED;
                    }
                }
                case SEED -> {
                    int end = Math.min(count, cursor + CHECK_EVERY);
                    for (int e = cursor; e < end; e++) {
                        if (next(e) != FILL) continue;
                        int source = seed(e);
                        if (source >= 0) {
                            fluid.set(e, source + 1);
                            queue.set(tail++, e);
                        }
                    }
                    work += end - cursor;
                    cursor = end;
                    if (cursor == count) stage = Stage.SPREAD;
                }
                case SPREAD -> {
                    // A filled cell fills the removed cells it is above or level with.
                    int end = Math.min(tail, head + CHECK_EVERY);
                    while (head < tail && head < end) {
                        int e = queue.get(head++);
                        int source = fluid.get(e) - 1;
                        int x = x(e), y = y(e), z = z(e);
                        tail = spread(source, x, y - 1, z, tail);
                        tail = spread(source, x, y, z - 1, tail);
                        tail = spread(source, x, y, z + 1, tail);
                        tail = spread(source, x - 1, y, z, tail);
                        tail = spread(source, x + 1, y, z, tail);
                    }
                    work += CHECK_EVERY;
                    if (head == tail) {
                        queue = null;
                        dense = null;
                        blocks = null;
                        sparse = null;
                        lastBlock = null;
                        cursor = 0;
                        stage = Stage.RESOLVED;
                    }
                }
                default -> throw new IllegalStateException(stage.toString());
            }
        }
        return true;
    }

    /**
     * After {@link #resolve} is done: keeps, in plan order and in place, each cell whose state changes and that
     * {@code filter} accepts ({@code null}: every one), with what it becomes and its state now, a piece at a time until
     * {@code deadline}. Returns whether it is done; the kept cells are then the first {@link #size()} of
     * {@link #pages()}, and the plan is good for nothing else.
     */
    boolean compact(CellFilter filter, long deadline) {
        if (stage != Stage.RESOLVED && stage != Stage.COMPACTED) throw new IllegalStateException("Not resolved: " + stage);
        if (stage == Stage.COMPACTED) return true;
        int air = states.air();
        while (true) {
            int end = Math.min(count, cursor + CHECK_EVERY);
            for (int e = cursor; e < end; e++) {
                int next = resolved(e, air);
                int original = current(e);
                if (next == original) continue;
                int x = x(e), y = y(e), z = z(e);
                if (filter != null && !filter.accepts(x, y, z)) continue;
                int[] p = pages[kept >>> PAGE_BITS];
                int at = (kept & PAGE_MASK) * FIELDS;
                p[at + X] = x;
                p[at + Y] = y;
                p[at + Z] = z;
                p[at + NEXT] = next;
                p[at + ORIGINAL] = original;
                kept++;
            }
            cursor = end;
            if (cursor == count) {
                count = kept;
                // The pages past the cells kept go now.
                for (int p = (kept + PAGE_MASK) >>> PAGE_BITS; p < pages.length; p++) pages[p] = null;
                fluid = null;
                stage = Stage.COMPACTED;
                return true;
            }
            if (System.nanoTime() >= deadline) return false;
        }
    }

    /** The plan's pages: {@value #FIELDS} ints a cell ({@link #X} to {@link #ORIGINAL}), {@value #PAGE} cells a page. */
    int[][] pages() {
        return pages;
    }

    /** What planned cell {@code e} becomes once the refill is worked out. */
    private int resolved(int e, int air) {
        int next = next(e);
        if (next != FILL) return next;
        return fluid != null && fluid.get(e) > 0 ? fluid.get(e) - 1 : air;
    }

    /**
     * Readies {@link #lookup} for the fluid refill: a dense grid over the planned cells' bounds when that is at most
     * {@value #MAX_DENSE_CELLS} cells and at most 16 for each planned cell; else per chunk column a dense block over the
     * y range its cells take (a deep Heightmap cut plans millions of cells, each looked up six times), or, where that would
     * be more than 16 ints a cell, a shared hash of its cells: the lookup never takes more than about 16 ints a cell.
     */
    private void startLookup() {
        cursor = 0;
        long volume = ((long) maxX - minX + 1) * ((long) maxY - minY + 1) * ((long) maxZ - minZ + 1);
        if (volume <= MAX_DENSE_CELLS && volume <= 16L * count) {
            denseX = minX;
            denseY = minY;
            denseZ = minZ;
            denseW = maxX - minX + 1;
            denseH = maxY - minY + 1;
            denseD = maxZ - minZ + 1;
            dense = new int[(int) volume];
            lookupInts = volume;
            stage = Stage.LOOKUP;
            return;
        }
        columnRanges = new Long2LongOpenHashMap();
        columnRanges.defaultReturnValue(Long.MIN_VALUE);
        blocks = new Long2ObjectOpenHashMap<>();
        columnCounts = new Long2IntOpenHashMap();
        sparse = new Long2IntOpenHashMap();
        stage = Stage.RANGES;
    }

    /** The plan index of cell (x, y, z), or -1 when it is not planned. */
    private int lookup(int x, int y, int z) {
        if (dense != null) {
            int dx = x - denseX, dy = y - denseY, dz = z - denseZ;
            if (dx < 0 || dy < 0 || dz < 0 || dx >= denseW || dy >= denseH || dz >= denseD) return -1;
            return dense[(dx * denseD + dz) * denseH + dy] - 1;
        }
        Block block = block(x, z, false);
        if (block == null) return -1;
        if (block.cells == null) return sparse.get(exactKey(x, y, z)) - 1;
        int dy = y - block.y0;
        if (dy < 0 || dy >= block.height) return -1;
        return block.cells[(((x & 15) << 4) | (z & 15)) * block.height + dy] - 1;
    }

    /** Column (x, z)'s chunk column block, made from its y range when {@code create}; {@code null} when it has none. */
    private Block block(int x, int z, boolean create) {
        long key = columnKey(x, z);
        if (key == lastBlockKey) return lastBlock;
        Block block = blocks.get(key);
        if (block == null && create) {
            long range = columnRanges.get(key);
            block = new Block((int) (range >> 32), (int) range, columnCounts.get(key));
            if (block.cells != null) lookupInts += block.cells.length;
            blocks.put(key, block);
        }
        lastBlockKey = key;
        lastBlock = block;
        return block;
    }

    private static long columnKey(int x, int z) {
        return ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
    }

    /** x and z in 26 bits each, y in 12: exact within the kernels' horizontal limit (2²⁵) and any build height. */
    private static long exactKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** The most ints the fluid refill's lookup took (a dense int, or up to eight a sparse cell): for tests. */
    long lookupInts() {
        return lookupInts;
    }

    private static long pack(int low, int high) {
        return ((long) low << 32) | (high & 0xFFFFFFFFL);
    }

    private int seed(int e) {
        int own = sourceFluid(current(e));
        if (own >= 0) return own;
        int x = x(e), y = y(e), z = z(e);
        int source = neighbourFluid(x, y + 1, z);
        if (source < 0) source = neighbourFluid(x, y, z - 1);
        if (source < 0) source = neighbourFluid(x, y, z + 1);
        if (source < 0) source = neighbourFluid(x - 1, y, z);
        if (source < 0) source = neighbourFluid(x + 1, y, z);
        return source;
    }

    /** What a neighbour's state after this step supplies; removed cells are left to the flood fill. */
    private int neighbourFluid(int x, int y, int z) {
        int e = lookup(x, y, z);
        if (e >= 0) {
            int next = next(e);
            return next == FILL ? -1 : sourceFluid(next);
        }
        if (!layout.loaded(x, z)) return -1;
        return sourceFluid(world.get(x, y, z));
    }

    /**
     * The fluid source a cell holding {@code state} supplies: fluid source blocks, waterlogged blocks and
     * blocks that always hold source fluid (seagrass, kelp). Flowing or falling fluid supplies nothing.
     */
    private int sourceFluid(int state) {
        int source = states.fluidSource(state);
        if (source < 0) return -1;
        if (StateFlags.has(states.flags(state), StateFlags.FLUID_BLOCK) && state != source) return -1;
        return source;
    }

    private int spread(int source, int x, int y, int z, int tail) {
        int e = lookup(x, y, z);
        if (e < 0 || next(e) != FILL || fluid.get(e) > 0) return tail;
        fluid.set(e, source + 1);
        queue.set(tail, e);
        return tail + 1;
    }
}
