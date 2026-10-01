package dev.sculptory.fabric.client.editor.tools.select;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import java.util.Objects;

/**
 * Magic select: the blocks connected to a clicked block that match it, found by a flood fill over the client's world.
 * Pure (a {@link WorldReader}, no Minecraft types) and time-sliced: {@link #step} examines a bounded number of cells,
 * so the Select tool spreads a large fill over several frames. The world is read on the caller's thread (the render
 * thread for the client world) and is not copied, so blocks that change during a fill are seen as they are when read.
 *
 * <p>The fill never crosses the build height or enters a chunk the client doesn't have ({@link #hitUnloaded()}; a
 * chunk unloaded between slices counts too), and stops once {@code limit} blocks are selected and another matching one
 * is found ({@link #hitLimit()}). Air never matches, so a fill started on air selects nothing.
 */
public final class MagicSelect {
    /** Which blocks count as the clicked one. */
    public enum Match {
        /** The same block, in any state (every oak stair, whatever its facing). */
        SAME_BLOCK,
        /** Exactly the clicked block state. */
        EXACT_STATE,
        /** Any block that isn't air. */
        ANY_BLOCK
    }

    /** Which neighbours are connected. */
    public enum Connect {
        /** The six blocks sharing a face. */
        FACES,
        /** All 26 blocks around: sharing a face, an edge or a corner. */
        DIAGONALS
    }

    private static final int[][] FACE_OFFSETS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final int[][] ALL_OFFSETS = allOffsets();

    private final WorldReader world;
    private final StateSpace states;
    private final Match match;
    private final int[][] offsets;
    private final long limit;
    private final int bottomY;
    private final int topY;
    private final int seedState;
    private final NamespacedId seedBlock;
    /** Per state handle: 0 not asked yet, 1 matches, 2 doesn't. */
    private final byte[] matches;
    /** Loaded state per chunk column, for the current slice only. */
    private final Long2ByteOpenHashMap loadedChunks = new Long2ByteOpenHashMap();
    private final Bits visited = new Bits();
    private final CellSet.Builder selected = CellSet.builder();
    private final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
    private long count;
    private long examined;
    private boolean hitLimit;
    private boolean hitUnloaded;
    private boolean done;

    /**
     * Starts a fill at {@code seed}. Nothing is read beyond the seed until {@link #step} runs.
     *
     * @param limit the most blocks selected (at least 1)
     */
    public MagicSelect(WorldReader world, BlockPos seed, Match match, Connect connect, long limit) {
        this.world = Objects.requireNonNull(world);
        Objects.requireNonNull(seed);
        this.match = Objects.requireNonNull(match);
        this.offsets = Objects.requireNonNull(connect) == Connect.FACES ? FACE_OFFSETS : ALL_OFFSETS;
        if (limit < 1) throw new IllegalArgumentException("The limit must be at least 1: " + limit);
        this.limit = limit;
        this.states = world.states();
        this.bottomY = world.bottomY();
        this.topY = world.topYExclusive();
        this.matches = new byte[states.size()];
        int x = seed.x();
        int y = seed.y();
        int z = seed.z();
        if (y < bottomY || y >= topY || !loaded(x, z)) {
            seedState = states.air();
            seedBlock = null;
            done = true;
            return;
        }
        seedState = world.get(x, y, z);
        seedBlock = states.blockId(seedState);
        visited.add(x, y, z);
        if (matches(seedState)) {
            select(x, y, z);
        } else {
            done = true;
        }
    }

    /**
     * Examines up to {@code maxCells} more neighbouring cells (at least one step is taken); returns whether the fill is
     * finished.
     */
    public boolean step(int maxCells) {
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
            for (int[] offset : offsets) {
                budget--;
                examined++;
                int nx = x + offset[0];
                int ny = y + offset[1];
                int nz = z + offset[2];
                if (ny < bottomY || ny >= topY || !visited.add(nx, ny, nz)) continue;
                if (!loaded(nx, nz)) {
                    hitUnloaded = true;
                    continue;
                }
                if (!matches(world.get(nx, ny, nz))) continue;
                if (count >= limit) {
                    hitLimit = true;
                    done = true;
                    queue.clear();
                    break;
                }
                select(nx, ny, nz);
            }
        }
        return done;
    }

    /** Whether every connected matching block was found (or a limit stopped the fill). */
    public boolean done() {
        return done;
    }

    /** Blocks selected so far. */
    public long count() {
        return count;
    }

    /** Neighbouring cells examined so far, for progress and tests. */
    public long examined() {
        return examined;
    }

    /** Whether the limit stopped the fill with more matching blocks connected. */
    public boolean hitLimit() {
        return hitLimit;
    }

    /** Whether the fill reached a chunk this client doesn't have (the selection may continue there). */
    public boolean hitUnloaded() {
        return hitUnloaded;
    }

    /** Whether the selection holds {@code (x, y, z)}. */
    public boolean contains(int x, int y, int z) {
        return selected.contains(x, y, z);
    }

    /** The blocks selected so far, as a cell set (empty when none). */
    public CellSet cells() {
        return selected.build();
    }

    private void select(int x, int y, int z) {
        selected.add(x, y, z);
        count++;
        queue.enqueue(pack(x, y, z));
    }

    private boolean matches(int state) {
        if (state < 0 || state >= matches.length) return false;
        byte known = matches[state];
        if (known != 0) return known == 1;
        boolean result = !StateFlags.has(states.flags(state), StateFlags.AIR) && switch (match) {
            case EXACT_STATE -> state == seedState;
            case SAME_BLOCK -> states.blockId(state).equals(seedBlock);
            case ANY_BLOCK -> true;
        };
        matches[state] = result ? (byte) 1 : (byte) 2;
        return result;
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

    private static int[][] allOffsets() {
        int[][] offsets = new int[26][];
        int n = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) offsets[n++] = new int[] {dx, dy, dz};
                }
            }
        }
        return offsets;
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
            int index = index(x, y, z);
            long mask = 1L << (index & 63);
            if ((bits[index >>> 6] & mask) != 0) return false;
            bits[index >>> 6] |= mask;
            return true;
        }

        private static int index(int x, int y, int z) {
            return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
        }
    }
}
