package dev.sculptory.core.brush;

import dev.sculptory.core.Box;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Arrays;

/**
 * The cells one brush step plans to write, in the order they are planned, and the fluid refill of the cells it
 * removes: shared by the column kernel ({@link TerrainKernel}) and the Surface mode ({@link SurfaceKernel}).
 *
 * <p>A planned cell becomes a state, or {@link #FILL}: removed, it becomes the {@link StateSpace#fluidSource} supplied
 * by itself, the cell above or a horizontal neighbour (in that order: above, north, south, west, east), else air. Only
 * fluid source blocks, waterlogged blocks and blocks that always hold source fluid (seagrass) supply fluid; flowing and
 * falling fluid do not. Removed cells next to or below a filled cell fill the same way. Cells outside the clip box are
 * never planned, so they keep their state and the refill sees them as they are. {@link #emit} sends each planned cell
 * whose state changes, in plan order; every read happens before the first cell is sent.
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

    private final WorldReader world;
    private final StateSpace states;
    private final Box clip;
    private final Layout layout;

    private int count;
    private int[] wx = new int[64], wy = new int[64], wz = new int[64], value = new int[64], original = new int[64];
    private final Long2IntOpenHashMap index = new Long2IntOpenHashMap();

    /** @param clip the only cells written, or {@code null} */
    CellPlan(WorldReader world, Box clip, Layout layout) {
        this.world = world;
        this.states = world.states();
        this.clip = clip;
        this.layout = layout;
        index.defaultReturnValue(-1);
    }

    /** Plans cell (x, y, z) to become {@code next} ({@link #FILL}: removed); dropped outside the clip box. */
    void plan(int x, int y, int z, int next, int current) {
        if (clip != null && !clip.contains(x, y, z)) return;
        if (count == wx.length) {
            int size = count * 2;
            wx = Arrays.copyOf(wx, size);
            wy = Arrays.copyOf(wy, size);
            wz = Arrays.copyOf(wz, size);
            value = Arrays.copyOf(value, size);
            original = Arrays.copyOf(original, size);
        }
        wx[count] = x;
        wy[count] = y;
        wz[count] = z;
        value[count] = next;
        original[count] = current;
        index.put(layout.key(x, y, z), count);
        count++;
    }

    /** Whether cell (x, y, z) is planned already. */
    boolean planned(int x, int y, int z) {
        long key = layout.key(x, y, z);
        return key != Long.MIN_VALUE && index.get(key) >= 0;
    }

    /** Cells planned so far. */
    int size() {
        return count;
    }

    int x(int e) {
        return wx[e];
    }

    int y(int e) {
        return wy[e];
    }

    int z(int e) {
        return wz[e];
    }

    /** What cell {@code e} becomes ({@link #FILL}: removed). */
    int next(int e) {
        return value[e];
    }

    /** The state cell {@code e} holds now. */
    int current(int e) {
        return original[e];
    }

    /** Resolves the removed cells' fluid, then sends every planned cell whose state changes, in plan order. */
    void emit(CellSink out) {
        int[] fluid = resolveFluids();
        int air = states.air();
        for (int e = 0; e < count; e++) {
            int next = value[e] != FILL ? value[e] : fluid[e] >= 0 ? fluid[e] : air;
            if (next != original[e]) out.set(wx[e], wy[e], wz[e], next);
        }
    }

    private int[] resolveFluids() {
        int[] fluid = new int[count];
        Arrays.fill(fluid, -1);
        int[] queue = new int[count];
        int head = 0, tail = 0;
        for (int e = 0; e < count; e++) {
            if (value[e] != FILL) continue;
            int source = seed(e);
            if (source >= 0) {
                fluid[e] = source;
                queue[tail++] = e;
            }
        }
        // A filled cell fills the removed cells it is above or level with.
        while (head < tail) {
            int e = queue[head++];
            int x = wx[e], y = wy[e], z = wz[e];
            tail = spread(fluid, queue, tail, fluid[e], x, y - 1, z);
            tail = spread(fluid, queue, tail, fluid[e], x, y, z - 1);
            tail = spread(fluid, queue, tail, fluid[e], x, y, z + 1);
            tail = spread(fluid, queue, tail, fluid[e], x - 1, y, z);
            tail = spread(fluid, queue, tail, fluid[e], x + 1, y, z);
        }
        return fluid;
    }

    private int seed(int e) {
        int own = sourceFluid(original[e]);
        if (own >= 0) return own;
        int x = wx[e], y = wy[e], z = wz[e];
        int source = neighbourFluid(x, y + 1, z);
        if (source < 0) source = neighbourFluid(x, y, z - 1);
        if (source < 0) source = neighbourFluid(x, y, z + 1);
        if (source < 0) source = neighbourFluid(x - 1, y, z);
        if (source < 0) source = neighbourFluid(x + 1, y, z);
        return source;
    }

    /** What a neighbour's state after this step supplies; removed cells are left to the flood fill. */
    private int neighbourFluid(int x, int y, int z) {
        int e = index.get(layout.key(x, y, z));
        if (e >= 0) return value[e] == FILL ? -1 : sourceFluid(value[e]);
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

    private int spread(int[] fluid, int[] queue, int tail, int source, int x, int y, int z) {
        int e = index.get(layout.key(x, y, z));
        if (e < 0 || value[e] != FILL || fluid[e] >= 0) return tail;
        fluid[e] = source;
        queue[tail] = e;
        return tail + 1;
    }
}
