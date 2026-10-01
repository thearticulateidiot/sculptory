package dev.sculptory.core.brush;

import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Per-stroke mutable state shared by the dabs of one stroke: per-column fractional displacement
 * accumulators keyed by packed (x, z), and for the Surface mode ({@link SculptMode#SURFACE}) per-line accumulators
 * keyed by the line's direction and position, and per-cell accumulators keyed by packed (x, y, z). Create one per
 * stroke, on client and server alike.
 *
 * <p>Accumulators are fixed point, {@link #ONE} per block, so the client and server agree exactly. The maps
 * are only ever read and written by key, never iterated, so their hash order cannot leak into kernel output.
 * The state also caches the stroke's bound {@link SurfaceMask} and validated material. Not thread-safe.
 */
public final class StrokeState {
    /** Fixed-point scale of the accumulators: one block. */
    static final int ONE = 1 << 16;

    private final Long2IntOpenHashMap accumulators = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap lines = new Long2IntOpenHashMap();
    /** The Surface-mode lines Flatten has moved (or found level) so far in the stroke. */
    private final LongOpenHashSet startedLines = new LongOpenHashSet();
    private final Long2IntOpenHashMap cells = new Long2IntOpenHashMap();

    private SurfaceMask maskSource;
    private StateSpace maskStates;
    private ColumnFilter.Bound maskFilter;

    private Pattern checkedMaterial;
    private StateSpace materialStates;

    /** The Surface kernel's arrays, reused from step to step (never read across steps). */
    private SurfaceKernel.Scratch surfaceScratch;

    public StrokeState() {}

    SurfaceKernel.Scratch surfaceScratch() {
        if (surfaceScratch == null) surfaceScratch = new SurfaceKernel.Scratch();
        return surfaceScratch;
    }

    /** Forgets all accumulated state. */
    public void reset() {
        accumulators.clear();
        accumulators.trim();
        lines.clear();
        lines.trim();
        startedLines.clear();
        startedLines.trim();
        cells.clear();
        cells.trim();
        maskSource = null;
        maskStates = null;
        maskFilter = null;
        checkedMaterial = null;
        materialStates = null;
        surfaceScratch = null;
    }

    /** Number of columns holding a non-zero accumulator. */
    public int trackedColumns() {
        return accumulators.size();
    }

    /** Number of Surface-mode lines and cells holding a non-zero accumulator. */
    public int trackedSurface() {
        return lines.size() + cells.size();
    }

    /** The accumulator of column (x, z), in 1/{@link #ONE} blocks. */
    int accumulator(int x, int z) {
        return accumulators.get(column(x, z));
    }

    void setAccumulator(int x, int z, int value) {
        if (value == 0) {
            accumulators.remove(column(x, z));
        } else {
            accumulators.put(column(x, z), value);
        }
    }

    /**
     * The accumulator of the Surface-mode line along {@code axis} through lateral position (a, b) (the two other
     * coordinates, in x, y, z order), in 1/{@link #ONE} blocks.
     */
    int lineAccumulator(Facing axis, int a, int b) {
        return lines.get(line(axis, a, b));
    }

    void setLineAccumulator(Facing axis, int a, int b, int value) {
        if (value == 0) {
            lines.remove(line(axis, a, b));
        } else {
            lines.put(line(axis, a, b), value);
        }
    }

    /**
     * Notes that the stroke has reached the Surface-mode line along {@code axis} through (a, b), and says whether this
     * is the first time: Flatten moves a line at least one block on the stroke's first dab there.
     */
    boolean startLine(Facing axis, int a, int b) {
        return startedLines.add(line(axis, a, b));
    }

    /** The accumulator of cell (x, y, z) in the Surface mode, in 1/{@link #ONE}. */
    int cellAccumulator(int x, int y, int z) {
        return cells.get(cell(x, y, z));
    }

    /** Whether any Surface-mode cell holds an accumulator. */
    boolean anyCellAccumulator() {
        return !cells.isEmpty();
    }

    void setCellAccumulator(int x, int y, int z, int value) {
        if (value == 0) {
            // Smooth resets most cells of its ball every dab: nothing to do while no cell holds anything.
            if (!cells.isEmpty()) cells.remove(cell(x, y, z));
        } else {
            cells.put(cell(x, y, z), value);
        }
    }

    /**
     * The mask bound to {@code states} (compiled once per stroke), reading {@code world} (the step's, before it
     * writes) for its rule-list parts.
     */
    ColumnFilter filter(SurfaceMask mask, StateSpace states, WorldReader world) {
        if (maskFilter == null || maskStates != states || !mask.equals(maskSource)) {
            try {
                maskFilter = ColumnFilter.bind(mask, states);
            } catch (IndexOutOfBoundsException unknownState) {
                throw new IllegalArgumentException("Brush mask names a state outside the state space", unknownState);
            }
            maskSource = mask;
            maskStates = states;
        }
        return maskFilter.on(world);
    }

    /**
     * Checks once per stroke that the material only writes states of {@code states} (and that a {@link Pattern.Waterlog}
     * carries a fluid source).
     */
    void checkMaterial(Pattern material, StateSpace states) {
        if (material == null || (materialStates == states && material.equals(checkedMaterial))) return;
        int[] handles = switch (material) {
            case Pattern.Single single -> new int[] {single.state()};
            case Pattern.Weighted weighted -> weighted.states();
            case Pattern.Arranged arranged -> arranged.mix().states();
            case Pattern.Waterlog waterlog -> new int[] {waterlog.fluidSource()};
            case Pattern.Dry dry -> new int[0];
            case Pattern.SetProperty set -> throw new IllegalArgumentException(
                    "A property pattern (Tinker) is not a brush material");
            case Pattern.KeepShape keep -> throw new IllegalArgumentException("A Keep shape is not a brush material");
            case Pattern.Remap remap -> throw new IllegalArgumentException("A remap pattern is not a brush material");
        };
        for (int handle : handles) {
            if (handle < 0 || handle >= states.size()) {
                throw new IllegalArgumentException("Brush material state " + handle + " is outside the state space");
            }
        }
        if (material instanceof Pattern.Waterlog waterlog && !Pattern.isFluidSource(states, waterlog.fluidSource())) {
            throw new IllegalArgumentException("Brush material Waterlog needs a fluid source state, not "
                    + states.format(waterlog.fluidSource()));
        }
        checkedMaterial = material;
        materialStates = states;
    }

    private static long column(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** The facing (3 bits), then the lateral coordinates, 29 bits each: exact within the kernel's limits. */
    private static long line(Facing axis, int a, int b) {
        return ((long) axis.ordinal() << 58) | ((a & 0x1FFFFFFFL) << 29) | (b & 0x1FFFFFFFL);
    }

    /**
     * x and z modulo 2²⁶, y modulo 2¹²: exact for any world height (at most 4,064 layers) and for cells less than 2²⁶
     * blocks apart, which every stroke inside the world border is.
     */
    private static long cell(int x, int y, int z) {
        return ((x & 0x3FFFFFFL) << 38) | ((z & 0x3FFFFFFL) << 12) | (y & 0xFFFL);
    }
}
