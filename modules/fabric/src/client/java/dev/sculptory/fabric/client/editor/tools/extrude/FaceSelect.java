package dev.sculptory.fabric.client.editor.tools.extrude;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.tools.select.MagicSelect;
import dev.sculptory.fabric.client.editor.world.BoxFace;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.Objects;

/**
 * The flat face the Extrude tool works on: the blocks whose exposed face lies in one plane, found over the client's
 * world. Pure (a {@link WorldReader}, no Minecraft types) and time-sliced like {@link MagicSelect}: {@link #step}
 * examines a bounded number of cells, so a large face spreads over several frames. The world is read on the caller's
 * thread and not copied.
 *
 * <p>Two kinds of face:
 * <ul>
 *   <li>{@link #connected}: from the block under the cursor, the blocks that match it ({@link MagicSelect.Match}), lie
 *       in the same plane (the same coordinate along the face's axis), are exposed on that side (the cell beyond the
 *       face is air or replaceable: water, grass) and connect to it within the plane through sides (or corners too);</li>
 *   <li>{@link #layer}: the outermost layer of a selection in a direction: the region's cells on its bounds' face
 *       plane that aren't air (exposed or not; a selection's side is extruded as it is).</li>
 * </ul>
 * A face never leaves the build height and never reads a chunk the client doesn't have ({@link #hitUnloaded}; the
 * cell beyond a block counts too, so a face along a chunk border stops there); once {@code limit} cells are in and
 * another is found it stops ({@link #hitLimit}). Each cell's state is kept as read, for the ghost.
 */
public final class FaceSelect {
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] SIDES_AND_CORNERS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private final WorldReader world;
    private final StateSpace states;
    private final BoxFace face;
    private final long limit;
    private final int bottomY;
    private final int topY;
    /** The coordinate along the face's axis every cell of the face has. */
    private final int plane;
    private final CellSet.Builder selected = CellSet.builder();
    private final LongArrayList cells = new LongArrayList();
    private final IntArrayList cellStates = new IntArrayList();
    /** Loaded state per chunk column, for the current slice only. */
    private final Long2ByteOpenHashMap loadedChunks = new Long2ByteOpenHashMap();
    private long count;
    private long examined;
    private boolean hitLimit;
    private boolean hitUnloaded;
    private boolean done;

    // Connected faces: the flood.
    private final MagicSelect.Match match;
    private final int[][] offsets;
    private final int seedState;
    private final NamespacedId seedBlock;
    /** Per state handle: 0 not asked yet, 1 matches, 2 doesn't. */
    private final byte[] matches;
    private final Bits visited = new Bits();
    private final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();

    // Layers: the scan over the bounds' face.
    private final Region region;
    private final Box scan;
    /** The next cell of the scan, in the two axes across the face (u along the lower axis, v along the higher). */
    private int scanU;
    private int scanV;

    private FaceSelect(WorldReader world, BoxFace face, long limit, int plane, MagicSelect.Match match, boolean diagonals,
                       int seedState, Region region, Box scan) {
        this.world = world;
        this.states = world.states();
        this.face = face;
        this.limit = limit;
        this.bottomY = world.bottomY();
        this.topY = world.topYExclusive();
        this.plane = plane;
        this.match = match;
        this.offsets = diagonals ? SIDES_AND_CORNERS : SIDES;
        this.seedState = seedState;
        this.seedBlock = seedState < 0 ? null : states.blockId(seedState);
        this.matches = new byte[states.size()];
        this.region = region;
        this.scan = scan;
    }

    /**
     * The face under the cursor: from {@code seed} (the block hit), on its {@code face} side. Nothing is read beyond the
     * seed and the cell in front of it until {@link #step} runs; a seed that is air, unloaded, outside the build height
     * or not exposed on that side gives an empty face at once.
     *
     * @param limit the most cells taken (at least 1)
     */
    public static FaceSelect connected(WorldReader world, BlockPos seed, BoxFace face, MagicSelect.Match match,
                                       boolean diagonals, long limit) {
        Objects.requireNonNull(world);
        Objects.requireNonNull(seed);
        Objects.requireNonNull(face);
        Objects.requireNonNull(match);
        if (limit < 1) throw new IllegalArgumentException("The limit must be at least 1: " + limit);
        int plane = coordinate(seed, face.axis());
        FaceSelect select;
        int x = seed.x();
        int y = seed.y();
        int z = seed.z();
        if (y < world.bottomY() || y >= world.topYExclusive() || !world.isLoaded(x >> 4, z >> 4)) {
            select = new FaceSelect(world, face, limit, plane, match, diagonals, -1, null, null);
            select.done = true;
            return select;
        }
        int state = world.get(x, y, z);
        select = new FaceSelect(world, face, limit, plane, match, diagonals, state, null, null);
        select.visited.add(x, y, z);
        if (select.matches(state) && select.exposed(x, y, z)) {
            select.select(x, y, z, state);
        } else {
            select.done = true;
        }
        return select;
    }

    /**
     * The outermost layer of {@code region} toward {@code face}: its cells on that face of its bounds, air left out.
     * The scan begins with {@link #step}.
     *
     * @param limit the most cells taken (at least 1); the layer is refused, not cut, when it stops the scan
     */
    public static FaceSelect layer(WorldReader world, Region region, BoxFace face, long limit) {
        Objects.requireNonNull(world);
        Objects.requireNonNull(region);
        Objects.requireNonNull(face);
        if (limit < 1) throw new IllegalArgumentException("The limit must be at least 1: " + limit);
        Box bounds = region.bounds();
        int plane = face.sign() < 0 ? coordinate(bounds.min(), face.axis()) : coordinate(bounds.max(), face.axis());
        FaceSelect select = new FaceSelect(world, face, limit, plane, MagicSelect.Match.ANY_BLOCK, false, -1, region,
                bounds);
        select.scanU = coordinate(bounds.min(), select.axisU());
        select.scanV = coordinate(bounds.min(), select.axisV());
        return select;
    }

    /** The side the face is exposed on (its outward normal). */
    public BoxFace face() {
        return face;
    }

    /** The coordinate along the face's axis every cell of the face has. */
    public int plane() {
        return plane;
    }

    /**
     * Examines up to {@code maxCells} more cells (at least one step is taken); returns whether the face is complete.
     */
    public boolean step(int maxCells) {
        // Chunks can unload between slices: ask again each slice, so a chunk gone meanwhile counts as unloaded.
        loadedChunks.clear();
        long budget = Math.max(1, maxCells);
        if (region != null) {
            scanLayer(budget);
        } else {
            flood(budget);
        }
        return done;
    }

    private void flood(long budget) {
        int axisU = axisU();
        int axisV = axisV();
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
                int nx = x + (axisU == 0 ? offset[0] : 0) + (axisV == 0 ? offset[1] : 0);
                int ny = y + (axisU == 1 ? offset[0] : 0) + (axisV == 1 ? offset[1] : 0);
                int nz = z + (axisV == 2 ? offset[1] : 0);
                if (ny < bottomY || ny >= topY || !visited.add(nx, ny, nz)) continue;
                if (!loaded(nx, nz)) {
                    hitUnloaded = true;
                    continue;
                }
                int state = world.get(nx, ny, nz);
                if (!matches(state) || !exposed(nx, ny, nz)) continue;
                if (count >= limit) {
                    hitLimit = true;
                    done = true;
                    queue.clear();
                    break;
                }
                select(nx, ny, nz, state);
            }
        }
    }

    private void scanLayer(long budget) {
        int axisU = axisU();
        int axisV = axisV();
        int minU = coordinate(scan.min(), axisU);
        int maxU = coordinate(scan.max(), axisU);
        int maxV = coordinate(scan.max(), axisV);
        while (!done && budget > 0) {
            if (scanV > maxV) {
                done = true;
                break;
            }
            budget--;
            examined++;
            // Across an X face u runs along y and v along z; a Y face u along x, v along z; a Z face u along x, v along y.
            int x = axisU == 0 ? scanU : plane;
            int y = axisU == 1 ? scanU : axisV == 1 ? scanV : plane;
            int z = axisV == 2 ? scanV : plane;
            if (++scanU > maxU) {
                scanU = minU;
                scanV++;
            }
            if (y < bottomY || y >= topY || !region.contains(x, y, z)) continue;
            if (!loaded(x, z)) {
                hitUnloaded = true;
                continue;
            }
            int state = world.get(x, y, z);
            if (StateFlags.has(states.flags(state), StateFlags.AIR)) continue;
            if (count >= limit) {
                hitLimit = true;
                done = true;
                break;
            }
            select(x, y, z, state);
        }
    }

    /** Whether every cell of the face was found (or a limit stopped the search). */
    public boolean done() {
        return done;
    }

    /** Cells in the face so far. */
    public long count() {
        return count;
    }

    /** Cells examined so far, for progress and tests. */
    public long examined() {
        return examined;
    }

    /** Whether the limit stopped the search with more cells to take. */
    public boolean hitLimit() {
        return hitLimit;
    }

    /** Whether the search reached a chunk this client doesn't have (the face may go on there). */
    public boolean hitUnloaded() {
        return hitUnloaded;
    }

    /** Whether the face holds {@code (x, y, z)}. */
    public boolean contains(int x, int y, int z) {
        return selected.contains(x, y, z);
    }

    /** The face's cells so far, as a cell set (empty when none). */
    public CellSet cells() {
        return selected.build();
    }

    /** The face's cells so far in the order found, packed as {@link BlockBuffer#keyOfBlock} packs sections, but per cell. */
    public long[] cellArray() {
        return cells.toLongArray();
    }

    /** The state of each cell of {@link #cellArray()}, as read when it was found. */
    public int[] stateArray() {
        return cellStates.toIntArray();
    }

    /** The x of a packed cell of {@link #cellArray()}. */
    public static int cellX(long packed) {
        return unpackX(packed);
    }

    public static int cellY(long packed) {
        return unpackY(packed);
    }

    public static int cellZ(long packed) {
        return unpackZ(packed);
    }

    private void select(int x, int y, int z, int state) {
        selected.add(x, y, z);
        long packed = pack(x, y, z);
        cells.add(packed);
        cellStates.add(state);
        count++;
        if (region == null) queue.enqueue(packed);
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

    /** Whether the cell beyond (x, y, z) on the face's side is open (air or replaceable), so the face shows there. */
    private boolean exposed(int x, int y, int z) {
        int ox = x + face.normalX();
        int oy = y + face.normalY();
        int oz = z + face.normalZ();
        if (oy < bottomY || oy >= topY) return false;
        if (!loaded(ox, oz)) {
            hitUnloaded = true;
            return false;
        }
        int flags = states.flags(world.get(ox, oy, oz));
        return StateFlags.has(flags, StateFlags.AIR) || StateFlags.has(flags, StateFlags.REPLACEABLE);
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

    /** The lower of the two axes across the face. */
    private int axisU() {
        return face.axis() == 0 ? 1 : 0;
    }

    /** The higher of the two axes across the face. */
    private int axisV() {
        return face.axis() == 2 ? 1 : 2;
    }

    static int coordinate(BlockPos pos, int axis) {
        return axis == 0 ? pos.x() : axis == 1 ? pos.y() : pos.z();
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
