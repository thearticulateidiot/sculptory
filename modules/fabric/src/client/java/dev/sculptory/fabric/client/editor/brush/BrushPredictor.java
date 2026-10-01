package dev.sculptory.fabric.client.editor.brush;

import dev.sculptory.core.brush.BrushKernel;
import dev.sculptory.core.brush.BrushKernels;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.SculptMode;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.ShapeStamp;
import dev.sculptory.core.brush.StrokeState;
import dev.sculptory.core.mask.BoundMask;
import dev.sculptory.core.mask.MaskedKernel;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.SculptoryMod;
import dev.sculptory.fabric.client.editor.render.ghost.GhostMasking;
import java.util.List;
import java.util.Objects;

/**
 * Client-side prediction of one server stroke. It runs the same
 * {@link BrushKernel} as the server, with its own {@link StrokeState} that mirrors the server's: one state
 * per server stroke, and every dab applied once, in the order it is sent. Each batch is applied inside one
 * vanilla prediction sequence ({@link Target#open}), writing every cell through to the client world before
 * the next dab, so each dab reads the world the server will see when it replays the same dabs. The
 * returned sequence travels with the batch; the server acknowledges it after applying the dabs, and
 * vanilla then keeps or rolls back what was predicted. The kernel replicates each dab under the spec's symmetry and
 * stands each copy on the ground where it lands in the world before the dab
 * ({@link dev.sculptory.core.brush.SymmetricStep}), exactly as the server does, so the copies are predicted too; a
 * copy that finds no ground is left out on both sides.
 *
 * <p>Wired into {@code FabricStrokeHandle.setBatchHook}, which calls {@link #predict} with exactly the dabs
 * it is about to send. Pure Java: the Minecraft side is a {@link Target}. Render thread only.
 */
public final class BrushPredictor {
    /** Where predicted cells go: the client world, under a vanilla prediction sequence. */
    @FunctionalInterface
    public interface Target {
        /**
         * Opens a prediction sequence ({@code PendingUpdateManager.incrementSequence()}), or returns
         * {@code null} when there is no world to predict into.
         */
        Scope open();
    }

    /** One open prediction sequence. Closing it ends the sequence ({@code PendingUpdateManager.close()}). */
    public interface Scope extends AutoCloseable {
        /** The sequence the server must acknowledge. */
        int sequence();

        /** Writes one predicted cell to the client world; later reads must see it. */
        void set(int x, int y, int z, int handle);

        @Override
        void close();
    }

    /** The kernel's scan margin and plant run (core {@code TerrainKernel}), for {@link #estimatedCells}. */
    private static final int SCAN_MARGIN = 8;
    private static final int PLANT_CELLS = 2;
    /** {@link #ballCells} of a round ball per radius, 1 to {@link BrushSpec#MAX_RADIUS}. */
    private static final long[] BALL_CELLS = new long[BrushSpec.MAX_RADIUS + 1];

    static {
        for (int r = 1; r <= BrushSpec.MAX_RADIUS; r++) {
            // A cell centre within r of a point inside a block lies within r + 1 of that block's centre.
            long count = 0;
            long reach2 = (long) (r + 1) * (r + 1);
            for (long dx = -r - 1; dx <= r + 1; dx++) {
                for (long dy = -r - 1; dy <= r + 1; dy++) {
                    long rest = reach2 - dx * dx - dy * dy;
                    if (rest >= 0) {
                        count += 2 * (long) Math.floor(Math.sqrt(rest)) + 1;
                    }
                }
            }
            BALL_CELLS[r] = count;
        }
    }

    private final BrushSpec spec;
    private final BrushKernel kernel;
    private final StrokeState state = new StrokeState();
    private final WorldReader world;
    private final Target target;
    private boolean failed;
    private long dabsApplied;

    /**
     * Predicts under the player's global mask as it is now, the mask the stroke's {@code StrokeBegin} carries to the
     * server.
     *
     * @param world the client world as the kernel reads it; it must show {@code target}'s writes at once
     */
    public BrushPredictor(BrushSpec spec, WorldReader world, Target target) {
        this(spec, world, target, GhostMasking.current(world.states()));
    }

    /** Predicts under {@code mask} (the server wraps its kernel the same way: {@link MaskedKernel}). */
    public BrushPredictor(BrushSpec spec, WorldReader world, Target target, BoundMask mask) {
        this.spec = Objects.requireNonNull(spec);
        this.kernel = MaskedKernel.wrap(BrushKernels.forTool(spec.tool()), mask);
        this.world = Objects.requireNonNull(world);
        this.target = Objects.requireNonNull(target);
    }

    public BrushSpec spec() {
        return spec;
    }

    /** False once a dab failed to apply: the rest of the stroke is left to the server. */
    public boolean predicting() {
        return !failed;
    }

    /** Dabs applied so far. */
    public long dabsApplied() {
        return dabsApplied;
    }

    /**
     * Applies {@code batch} in order under one prediction sequence and returns that sequence, or 0 when
     * nothing was predicted. A kernel failure stops prediction for the rest of the stroke; the cells already
     * written stay under the returned sequence, so the server's acknowledgement reconciles them.
     */
    public int predict(List<Dab> batch) {
        if (failed || batch.isEmpty()) {
            return 0;
        }
        try (Scope scope = target.open()) {
            if (scope == null) {
                return 0;
            }
            int sequence = scope.sequence();
            try {
                for (Dab dab : batch) {
                    kernel.apply(spec, dab, state, world, scope::set);
                    dabsApplied++;
                }
            } catch (RuntimeException e) {
                failed = true;
                SculptoryMod.LOG.warn("Sculptory: brush prediction stopped: {}", e.toString());
            }
            return sequence;
        }
    }

    // ---- Estimates ----

    /**
     * An upper bound on the cells one dab of this brush writes, from the kernel's documented limits: the
     * footprint's columns times the cells a column can change in one dab (Raise and Lower move a column at
     * most one block per dab, Flatten and Smooth at most {@code radius + 8}, Paint and Palette repaint
     * {@code max(1, depth)} cells; up to two plant cells come on top when a column moves). For the Shape brush,
     * which needs its whole spec, the cube of its diameter.
     */
    public static long estimatedCells(BrushTool tool, int radius, Shape shape, int depth) {
        return estimatedCells(tool, radius, shape, depth, SculptMode.TERRAIN);
    }

    /**
     * The bound for a brush in {@code mode}. The Surface mode ({@code SurfaceKernel}): Raise and Lower write at most one
     * cell per line of the footprint, plus the plants standing on it and the lower half of a two-block plant; Flatten at
     * most {@code radius + 8} cells per line plus plants, as a Terrain-mode column; Smooth at most every cell of the
     * dab's ball ({@link #ballCells}), plus plants (three per column of the footprint).
     */
    public static long estimatedCells(BrushTool tool, int radius, Shape shape, int depth, SculptMode mode) {
        if (mode == SculptMode.SURFACE && SculptMode.surfaceTool(tool)) {
            int r = Math.max(1, radius);
            long columns = columns(r, shape);
            return switch (tool) {
                case RAISE, LOWER -> columns * (1 + PLANT_CELLS + 1);
                case FLATTEN -> columns * (r + SCAN_MARGIN + PLANT_CELLS + 1);
                default -> ballCells(r, shape) + columns * (PLANT_CELLS + 1);
            };
        }
        int r = Math.max(1, radius);
        long perColumn = switch (tool) {
            case RAISE, LOWER -> 1 + PLANT_CELLS;
            case SMOOTH, FLATTEN -> r + SCAN_MARGIN + PLANT_CELLS;
            case PAINT, PALETTE -> Math.max(1, depth);
            case SHAPE -> 2L * r + 1;
            // The Weather brush reworks the surface around each column, as Smooth does (a bound, not an exact count).
            case WEATHER -> r + SCAN_MARGIN + PLANT_CELLS;
        };
        return (tool == BrushTool.SHAPE ? columns(r, Shape.SQUARE) : columns(r, shape)) * perColumn;
    }

    /**
     * The bound for one dab of {@code spec} with its symmetric copies (each dab of a step does its own work). The Shape
     * brush's is exact: its shape's (or shell's) cells, {@link ShapeStamp#cellCount}, for each copy.
     */
    public static long estimatedCells(BrushSpec spec) {
        long one = spec.tool() == BrushTool.SHAPE ? ShapeStamp.cellCount(spec)
                : estimatedCells(spec.tool(), spec.radius(), spec.shape(), spec.depth(), spec.mode());
        return one * spec.symmetry().mode().copies();
    }

    /** Whether a stroke with {@code spec} is predicted under a cap of {@code maxCells} per dab. */
    public static boolean predictable(BrushSpec spec, int maxCells) {
        return estimatedCells(spec) <= maxCells;
    }

    /** Cells whose centres can lie inside a Surface-mode ball of {@code radius}: a bound for any point of the dab. */
    static long ballCells(int radius, Shape shape) {
        int r = Math.max(1, Math.min(BrushSpec.MAX_RADIUS, radius));
        if (shape == Shape.SQUARE) {
            long side = 2L * r + 1;
            return side * side * side;
        }
        return BALL_CELLS[r];
    }

    /** Columns in the footprint of a dab centred on a column: within {@code radius} of its centre. */
    static long columns(int radius, Shape shape) {
        long side = 2L * radius + 1;
        if (shape == Shape.SQUARE) {
            return side * side;
        }
        long count = 0;
        long r2 = (long) radius * radius;
        for (long dx = -radius; dx <= radius; dx++) {
            long rest = r2 - dx * dx;
            count += 2 * (long) Math.floor(Math.sqrt(rest)) + 1;
        }
        return count;
    }
}
