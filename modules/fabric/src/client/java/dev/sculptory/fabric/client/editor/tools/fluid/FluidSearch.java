package dev.sculptory.fabric.client.editor.tools.fluid;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import java.util.Objects;

/**
 * A breadth-first search over the client's world through the six faces, shared by {@link FluidFlood} and
 * {@link FluidDrain}. Pure (a {@link WorldReader}, no
 * Minecraft types) and time-sliced like magic select: {@link #step} examines a bounded number of cells, so the Fluid
 * tool spreads a large search over several frames. The world is read on the caller's thread and not copied.
 *
 * <p>Each neighbour is {@link #classify classified} by the subclass: skipped, collected and searched on from, or
 * collected without searching on (a flood's rim). The search never crosses the build height, never leaves
 * {@code bounds} when one is given (the selection's bounding box), never enters a chunk the client doesn't have
 * ({@link #hitUnloaded()}; a chunk unloaded between slices counts too), and stops once {@code limit} cells are collected
 * and another qualifying one is found ({@link #hitLimit()}).
 */
abstract class FluidSearch {
    /** What a cell is to the search. */
    enum Kind {
        /** Not collected. */
        NONE,
        /** Collected, and its neighbours examined. */
        EXPAND,
        /** Collected; the search does not continue from it. */
        COLLECT
    }

    private static final int[][] FACE_OFFSETS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    protected final WorldReader world;
    protected final StateSpace states;
    private final long limit;
    private final Box bounds;
    private final int bottomY;
    private final int topY;
    /** Loaded state per chunk column, for the current slice only. */
    private final Long2ByteOpenHashMap loadedChunks = new Long2ByteOpenHashMap();
    private final Bits visited = new Bits();
    private final CellSet.Builder found = CellSet.builder();
    private final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
    private long count;
    private long examined;
    private boolean hitLimit;
    private boolean hitUnloaded;
    private boolean done;

    /**
     * @param limit the most cells collected (at least 1)
     * @param bounds the box the search stays inside, or {@code null} for none
     */
    protected FluidSearch(WorldReader world, long limit, Box bounds) {
        this.world = Objects.requireNonNull(world);
        if (limit < 1) throw new IllegalArgumentException("The limit must be at least 1: " + limit);
        this.limit = limit;
        this.bounds = bounds;
        this.states = world.states();
        this.bottomY = world.bottomY();
        this.topY = world.topYExclusive();
    }

    /** The kind of the cell at (x, y, z) holding {@code state}; asked once per cell. */
    protected abstract Kind classify(int x, int y, int z, int state);

    /**
     * Starts at {@code seed}: collected when it qualifies (and the search goes on from it, whatever its kind), else the
     * search is done with nothing. Subclasses call this once their fields are set.
     */
    protected final void start(BlockPos seed) {
        int x = seed.x(), y = seed.y(), z = seed.z();
        if (y < bottomY || y >= topY || outside(x, y, z) || !loaded(x, z)) {
            done = true;
            return;
        }
        visited.add(x, y, z);
        if (classify(x, y, z, world.get(x, y, z)) == Kind.NONE) {
            done = true;
            return;
        }
        collect(x, y, z, true);
    }

    /**
     * Examines up to {@code maxCells} more neighbouring cells (at least one step is taken); returns whether the search
     * is finished.
     */
    public final boolean step(int maxCells) {
        // Chunks can unload between slices (a step runs within one frame, when they can't): ask again each slice, so
        // a chunk gone meanwhile counts as unloaded instead of reading as air.
        loadedChunks.clear();
        long budget = Math.max(1, maxCells);
        while (!done && budget > 0) {
            if (queue.isEmpty()) {
                done = true;
                break;
            }
            long cell = queue.dequeueLong();
            int x = unpackX(cell);
            int y = unpackY(cell);
            int z = unpackZ(cell);
            for (int[] offset : FACE_OFFSETS) {
                budget--;
                examined++;
                int nx = x + offset[0];
                int ny = y + offset[1];
                int nz = z + offset[2];
                if (ny < bottomY || ny >= topY || outside(nx, ny, nz) || !visited.add(nx, ny, nz)) continue;
                if (!loaded(nx, nz)) {
                    hitUnloaded = true;
                    continue;
                }
                Kind kind = classify(nx, ny, nz, world.get(nx, ny, nz));
                if (kind == Kind.NONE) continue;
                if (count >= limit) {
                    hitLimit = true;
                    done = true;
                    queue.clear();
                    break;
                }
                collect(nx, ny, nz, kind == Kind.EXPAND);
            }
        }
        return done;
    }

    /** Whether every connected qualifying cell was found (or a limit stopped the search). */
    public final boolean done() {
        return done;
    }

    /** Cells collected so far. */
    public final long count() {
        return count;
    }

    /** Neighbouring cells examined so far, for progress and tests. */
    public final long examined() {
        return examined;
    }

    /** Whether the limit stopped the search with more qualifying cells connected. */
    public final boolean hitLimit() {
        return hitLimit;
    }

    /** Whether the search reached a chunk this client doesn't have (the body may continue there). */
    public final boolean hitUnloaded() {
        return hitUnloaded;
    }

    /** Whether the cells collected hold {@code (x, y, z)}. */
    public final boolean contains(int x, int y, int z) {
        return found.contains(x, y, z);
    }

    /** The cells collected so far, as a cell set (empty when none). */
    public final CellSet cells() {
        return found.build();
    }

    private void collect(int x, int y, int z, boolean expand) {
        found.add(x, y, z);
        count++;
        if (expand) queue.enqueue(pack(x, y, z));
    }

    private boolean outside(int x, int y, int z) {
        return bounds != null && !bounds.contains(x, y, z);
    }

    private boolean loaded(int x, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        byte known = loadedChunks.get(key);
        if (known == 0) {
            known = world.isLoaded(cx, cz) ? (byte) 1 : (byte) 2;
            loadedChunks.put(key, known);
        }
        return known == 1;
    }

    // ---- Cell packing: 26 bits x, 26 bits z, 12 bits y (as Minecraft packs block positions) ----

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static int unpackX(long packed) {
        return (int) (packed >> 38);
    }

    private static int unpackY(long packed) {
        return (int) (packed << 52 >> 52);
    }

    private static int unpackZ(long packed) {
        return (int) (packed << 26 >> 38);
    }

    /** The cells examined so far, as one 4096-bit bitmap per 16³ section. */
    private static final class Bits {
        private final Long2ObjectOpenHashMap<long[]> sections = new Long2ObjectOpenHashMap<>();

        /** Adds a cell; returns whether it was new. */
        boolean add(int x, int y, int z) {
            long key = BlockBuffer.keyOfBlock(x, y, z);
            long[] bits = sections.get(key);
            if (bits == null) {
                bits = new long[64];
                sections.put(key, bits);
            }
            int index = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
            long mask = 1L << (index & 63);
            if ((bits[index >>> 6] & mask) != 0) return false;
            bits[index >>> 6] |= mask;
            return true;
        }
    }
}
