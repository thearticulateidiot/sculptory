package dev.sculptory.core.brush;

import dev.sculptory.core.Box;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Raise, Lower, Smooth and Flatten in the Surface mode ({@link SculptMode#SURFACE}): they
 * work on whatever surface a dab touches, floor, wall, ceiling or the underside of an overhang. Smooth works only inside
 * the dab's <b>ball</b>: the cells whose centres lie within the radius of the dab's point (a cube for
 * {@link Shape#SQUARE}). Raise, Lower and Flatten work inside the dab's <b>cylinder</b>: the disc of the radius
 * across the direction they work along (the footprint) and {@code radius + }{@value TerrainKernel#SCAN_MARGIN}
 * blocks each way along it from the dab's point, the Terrain mode's scan window turned to face the surface (a ball
 * narrowed the footprint as a held Raise pushed the surface, and the cursor with it, away from the lines at its edge:
 * a pillar instead of a mound). Nothing outside the ball or cylinder changes, except plants standing on a changed
 * block, at most {@value TerrainKernel#MAX_PLANT_RUN} cells of them. Ground, open cells and structures are
 * {@link SurfaceScan}'s:
 * structures (stairs, fences, torches, block entities) never change, and a ground block a structure touches (standing on
 * it, fixed to its side, hanging under it) is not removed.
 *
 * <p><b>Raise and Lower</b> work along the direction the surface faces around the dab, {@link SurfaceNormal}'s estimate
 * snapped to one of the six directions (up on a floor, sideways on a wall, down under a ceiling). The cylinder is cut
 * into lines along that direction, one per cell of the footprint (as the Terrain mode's columns: weight by the distance
 * across, Circle or Square). On each line the surface is the ground cell with an open cell in front of it (toward the
 * direction) nearest the dab, searched outward from the dab's point; a line without one inside the cylinder is left
 * alone. Each line keeps a fixed-point accumulator in {@link StrokeState}, as a column does in the Terrain mode: Raise
 * adds the weight and, per whole block, fills the open cell in front of the surface with the surface block (at most one
 * block per dab); Lower subtracts it and removes the surface block. On a floor this is the Terrain mode's Raise and
 * Lower, and a held Raise grows a mound at the Terrain mode's rate whichever way the surface faces.
 *
 * <p><b>Flatten</b> levels against {@link BrushSpec#plane()}, fixed when the press began: the face of the layer the
 * press point was on, facing the way the surface faced there (up for ground, down for a ceiling, sideways for a wall).
 * With symmetry each copy uses the plane's image under the symmetry image that made it. Lines run along the plane's
 * facing, and each line's surface moves toward the plane as a Terrain-mode column moves toward flattenY:
 * {@code weight × distance} per dab, carried in the line's accumulator, never past the plane: filling open cells in
 * front of the surface with its block, or removing cells from the surface down to the plane (stopping at a structure),
 * inside the cylinder. The first dab of a stroke on a line moves it at least one block toward the plane, whatever the
 * weight (one click flattens visibly everywhere it touches; the block is charged to the accumulator, so a held stroke
 * goes on at the weight's rate).
 *
 * <p><b>Smooth</b> is a volumetric smoothing: a cell of the ball becomes solid when at least {@value #MAJORITY} of the
 * 27 cells of its 3×3×3 block (itself included) are solid, and open otherwise (a majority vote, so a lone bump or dent
 * goes, edges round off and flat surfaces stay). Only cells on the dab's side of the surface take part: open cells
 * joined to the open cells around the dab through open cells inside the ball (and one block beyond), and ground cells
 * touching those, so a cave or cellar the ball reaches behind a wall is left alone. Each cell keeps an accumulator: a
 * cell that should flip adds the weight (by its distance from the dab's point) and flips at one whole block; a cell that
 * shouldn't flip is reset. A cell that fills takes the ground block most common around it (faces count 3, edges 2,
 * corners 1; a tie goes to the first in x, then y, then z order).
 *
 * <p><b>Masks and the clip box</b> behave as in the Terrain mode, per changed cell instead of per column: the mask sees
 * the line's surface block (Raise, Lower, Flatten) or the cell with the block it becomes or loses (Smooth), its y, and
 * its slope, {@link SurfaceNormal#slope} of the estimate within {@value SurfaceNormal#SLOPE_RADIUS} blocks of it (walls
 * count as {@value SurfaceNormal#MAX_SLOPE}). Only cells inside the clip box are written; a change whose plants reach
 * outside the box is not made. Removed cells refill with fluid as in the Terrain mode ({@link CellPlan}).
 *
 * <p><b>Steps.</b> Every dab of a step reads the world as it was before the step. A line (by direction and position)
 * or a Smooth cell inside several dabs' balls is decided once, by the first dab in step order that found it (a surface
 * on the line, or the cell on its side), with the largest weight among the dabs that found the same. Where two dabs
 * would write one cell (lines of different directions), the first write planned stands. Flatten alone reads the order
 * of the step's list: its first dab is the stroke's own ({@link SymmetricStep#of} lists it first), and the others are
 * its copies.
 *
 * <p><b>Cost</b>: Smooth takes each cell's 27-cell count from running counts
 * ({@code Pass.sumSolids}) and finds the joined cells by flooding runs of open cells up the columns, so a radius-32
 * dab costs a few milliseconds; the results are those of counting and flooding cell by cell
 * ({@code SurfaceKernelDifferentialTest} checks them against the kernel as it was before).
 *
 * <p><b>Reads</b> stay within the box, {@code radius + 2} of the dab's block on every axis for Smooth and
 * {@code radius + }{@value TerrainKernel#SCAN_MARGIN}{@code  + 2} for the line tools (the cylinder may face any of the
 * six directions), into a flat snapshot of the box read as cells are first asked for; Smooth reads the cells within two
 * blocks of its ball at once, a column at a time ({@link WorldReader#getColumn}). The fluid refill of a plant cleared
 * at the box's top looks one cell above it. {@code EngineEditService.dabBox} covers all of that. A stroke's steps reuse
 * the snapshots' arrays ({@link Scratch}). Writes: Raise and Lower at most one cell per line of the footprint plus
 * plants; Flatten at most {@code radius + 8} per line plus plants; Smooth at most the ball's cells plus plants. The
 * arithmetic is integer except the falloff (as the Terrain mode's), and nothing depends on hash order or on how states
 * are numbered, so the client's prediction and the server agree.
 */
final class SurfaceKernel {
    /** Solid cells of a 3×3×3 block, itself included, that make its centre solid under Smooth. */
    static final int MAJORITY = 14;

    private static final int ONE = StrokeState.ONE;
    private static final int FILL = CellPlan.FILL;
    private static final int NONE = Integer.MIN_VALUE;

    // Snapshot kinds (WeatherKernel reads them too). The ones Smooth's vote counts as solid (ground, structures, below
    // the world) are 4 to 6, so {@link #solid} is a shift.
    static final byte UNREAD = 0;
    static final byte OPEN = 1;
    /** Above the build height: open, never written. */
    static final byte ABOVE = 2;
    static final byte UNLOADED = 3;
    static final byte GROUND = 4;
    static final byte STRUCTURE = 5;
    /** Below the build height: solid, never written. */
    static final byte BELOW = 6;

    /** A column span without cells ({@link Ball#span}). */
    private static final long EMPTY_SPAN = 1L << 32;

    private SurfaceKernel() {}

    /** 1 for a kind Smooth's vote counts as solid, else 0. */
    private static int solid(byte kind) {
        return kind >>> 2;
    }

    static int spanLow(long span) {
        return (int) (span >> 32);
    }

    static int spanHigh(long span) {
        return (int) span;
    }

    /**
     * Marks the ball's open cells on the dab's side ({@link Ball#joined}), as Smooth does: those joined to the open cells
     * around the dab through open cells within a block of the ball. Its cells within two blocks must be read first
     * ({@link Ball#readAround}). The Weather brush's Surface mode shares it ({@code WeatherKernel}).
     */
    static void joinSide(Ball ball) {
        Pass.join(ball);
    }

    /**
     * Applies one step: {@code dabs} sorted and without repeats, each within the kernel's limits; {@code first} is the
     * dab the step's list began with (the stroke's own dab, as {@link SymmetricStep#of} lists it), from which Flatten
     * tells which symmetry image made each copy.
     */
    static void apply(BrushSpec spec, Dab first, Dab[] dabs, StrokeState stroke, WorldReader world, CellSink out) {
        new Pass(spec, first, dabs, stroke, world).run(out);
    }

    /** Whether the mask looks at slopes anywhere in its tree. */
    static boolean usesSlope(SurfaceMask mask) {
        return ColumnFilter.usesSlope(mask);
    }

    // ------------------------------------------------------------------ arrays a stroke reuses

    /**
     * The arrays a stroke's steps reuse ({@link StrokeState#surfaceScratch}): at radius 32 a ball's snapshot takes about
     * 1.6 MB, and a stroke makes one step after another, each done before the next begins. Slot k serves the step's k-th
     * ball. What is handed out is reset where it must be: a snapshot's kinds all {@link #UNREAD} (its states are read
     * only where a kind is), joined cells all false; Smooth's sums are written before they are read. Each state's kind
     * is kept too (it never changes).
     */
    static final class Scratch {
        private final byte[][] kinds = new byte[Symmetry.MAX_COPIES][];
        private final int[][] states = new int[Symmetry.MAX_COPIES][];
        private final boolean[][] joined = new boolean[Symmetry.MAX_COPIES][];
        private byte[] sums;
        private StateSpace kindStates;
        private byte[] stateKinds;

        /** Each state's kind ({@link #UNREAD} until first met) for {@code states}. */
        byte[] stateKinds(StateSpace states) {
            if (kindStates != states) {
                kindStates = states;
                stateKinds = new byte[states.size()];
            }
            return stateKinds;
        }

        byte[] kinds(int slot, int cells) {
            byte[] array = kinds[slot];
            if (array == null || array.length != cells) return kinds[slot] = new byte[cells];
            java.util.Arrays.fill(array, UNREAD);
            return array;
        }

        int[] states(int slot, int cells) {
            int[] array = states[slot];
            if (array == null || array.length != cells) array = states[slot] = new int[cells];
            return array;
        }

        boolean[] joined(int slot, int cells) {
            boolean[] array = joined[slot];
            if (array == null || array.length != cells) return joined[slot] = new boolean[cells];
            java.util.Arrays.fill(array, false);
            return array;
        }

        byte[] sums(int cells) {
            if (sums == null || sums.length != cells) sums = new byte[cells];
            return sums;
        }
    }

    // ------------------------------------------------------------------ one dab's ball

    /**
     * One dab of a step: its ball (Smooth) or cylinder (Raise, Lower, Flatten), the box it may read ({@code radius + 2}
     * around its block, {@code radius + 10} for the line tools), and what it found.
     */
    static final class Ball {
        final Dab dab;
        final BrushSpec spec;
        final WorldReader world;
        final StateSpace states;
        final long r16;
        final boolean round;
        /** A line tool: the dab works in the cylinder along {@link #axis}, not the ball. */
        final boolean lines;
        /** How far the cylinder reaches each way along the axis from the dab's point, in 1/16 block. */
        final long reach16;
        /** {@code strength × pressure / 255}. */
        final double base;
        final int bx, by, bz;
        /** The box: origin and edge. */
        final int x0, y0, z0, size;
        /** Index steps between neighbouring cells of the box along z and x ({@link #index}; along y it is 1). */
        final int strideZ, strideX;
        final int bottom, top;
        /**
         * The snapshot of the box, indexed by {@link #index}: each cell's kind ({@link #UNREAD} until read) and state,
         * read from the world the first time a cell is asked for (Smooth reads what it needs at once,
         * {@link #readAround}).
         */
        final byte[] kinds;
        final int[] cellStates;
        /** Loaded flags of the box's chunks: 0 not asked, 1 loaded, 2 not. */
        final int cx0, cz0, cd;
        final byte[] chunks;
        /** Whether a chunk the box reaches was found not loaded. */
        boolean anyUnloaded;

        // Lines (Raise, Lower, Flatten).
        Facing axis;
        SurfacePlane plane;
        /** The footprint across the axis: lateral cell ranges, and each line's surface cell on the axis or NONE. */
        int fa0, fa1, fb0, fb1;
        int[] crossing;

        // Smooth.
        boolean[] joined;
        /** Where the arrays come from: the stroke's reused ones (slot {@link #slot}), or new ones when null. */
        final Scratch scratch;
        final int slot;

        Ball(BrushSpec spec, Dab dab, WorldReader world) {
            this(spec, dab, world, null, 0);
        }

        Ball(BrushSpec spec, Dab dab, WorldReader world, Scratch scratch, int slot) {
            this(spec, dab, world, scratch, slot, spec.tool() != BrushTool.SMOOTH);
        }

        /**
         * @param lines whether the box is the line tools' ({@code radius + 10} around the dab's block) rather than
         *     Smooth's ({@code radius + 2}); the Weather brush picks one per mode ({@code WeatherKernel})
         */
        Ball(BrushSpec spec, Dab dab, WorldReader world, Scratch scratch, int slot, boolean lines) {
            this.scratch = scratch;
            this.slot = slot;
            this.dab = dab;
            this.spec = spec;
            this.world = world;
            this.states = world.states();
            this.r16 = 16L * spec.radius();
            this.round = spec.shape() == Shape.CIRCLE;
            this.lines = lines;
            this.reach16 = 16L * (spec.radius() + TerrainKernel.SCAN_MARGIN);
            this.base = (double) spec.strength() * dab.pressure() / Dab.FULL_PRESSURE;
            this.bx = dab.blockX();
            this.by = dab.blockY();
            this.bz = dab.blockZ();
            // The cylinder may face any way, so the line tools' box is its reach (plus the open cell in front) all round.
            int reach = spec.radius() + 2 + (lines ? TerrainKernel.SCAN_MARGIN : 0);
            this.x0 = bx - reach;
            this.y0 = by - reach;
            this.z0 = bz - reach;
            this.size = 2 * reach + 1;
            this.strideZ = size;
            this.strideX = size * size;
            this.bottom = world.bottomY();
            this.top = world.topYExclusive();
            int cells = strideX * size;
            this.kinds = scratch == null ? new byte[cells] : scratch.kinds(slot, cells);
            this.cellStates = scratch == null ? new int[cells] : scratch.states(slot, cells);
            this.cx0 = x0 >> 4;
            this.cz0 = z0 >> 4;
            int cw = ((x0 + size - 1) >> 4) - cx0 + 1;
            this.cd = ((z0 + size - 1) >> 4) - cz0 + 1;
            this.chunks = new byte[cw * cd];
        }

        boolean inBox(int x, int y, int z) {
            return x >= x0 && x < x0 + size && y >= y0 && y < y0 + size && z >= z0 && z < z0 + size;
        }

        boolean inBoxColumn(int x, int z) {
            return x >= x0 && x < x0 + size && z >= z0 && z < z0 + size;
        }

        /**
         * A cell's index in the box, x, then z, then y (the snapshot, the step's cell keys and Smooth's joined cells):
         * neighbours along y are 1 apart, along z {@link #strideZ}, along x {@link #strideX}.
         */
        int index(int x, int y, int z) {
            return ((x - x0) * size + (z - z0)) * size + (y - y0);
        }

        boolean loadedColumn(int x, int z) {
            int i = ((x >> 4) - cx0) * cd + ((z >> 4) - cz0);
            if (chunks[i] == 0) {
                boolean loaded = world.isLoaded(x >> 4, z >> 4);
                chunks[i] = loaded ? (byte) 1 : (byte) 2;
                if (!loaded) anyUnloaded = true;
            }
            return chunks[i] == 1;
        }

        /** The cell's kind; read from the world the first time. */
        byte kind(int x, int y, int z) {
            int i = index(x, y, z);
            byte kind = kinds[i];
            return kind != UNREAD ? kind : read(x, y, z, i);
        }

        /** The kind of the cell at index {@code i}; read from the world the first time. */
        byte kindAt(int i) {
            byte kind = kinds[i];
            if (kind != UNREAD) return kind;
            int column = i / size;
            return read(x0 + column / size, y0 + i - column * size, z0 + column % size, i);
        }

        int state(int x, int y, int z) {
            int i = index(x, y, z);
            if (kinds[i] == UNREAD) read(x, y, z, i);
            return cellStates[i];
        }

        /** Reads cell (x, y, z), index {@code i}, into the snapshot. */
        private byte read(int x, int y, int z, int i) {
            int state = states.air();
            byte kind;
            if (y < bottom) {
                kind = BELOW;
            } else if (y >= top) {
                kind = ABOVE;
            } else if (!loadedColumn(x, z)) {
                kind = UNLOADED;
            } else {
                state = world.get(x, y, z);
                kind = kindOf(state);
            }
            cellStates[i] = state;
            kinds[i] = kind;
            return kind;
        }

        private byte kindOf(int state) {
            if (scratch == null) return classify(state);
            byte[] known = scratch.stateKinds(states);
            byte kind = known[state];
            return kind != UNREAD ? kind : (known[state] = classify(state));
        }

        private byte classify(int state) {
            int flags = states.flags(state);
            return SurfaceScan.ground(flags) ? GROUND : SurfaceScan.open(flags) ? OPEN : STRUCTURE;
        }

        /**
         * Reads every cell whose centre lies within {@code reach16} of the dab's point (the ball's metric) into the
         * snapshot, a column's cells in one {@link WorldReader#getColumn} call; the same as reading each one.
         */
        void readAround(long reach16) {
            int air = states.air();
            for (int x = x0; x < x0 + size; x++) {
                for (int z = z0; z < z0 + size; z++) {
                    long span = span(x, z, reach16);
                    int lo = Math.max(spanLow(span), y0), hi = Math.min(spanHigh(span), y0 + size - 1);
                    if (lo > hi) continue;
                    int i = index(x, lo, z);
                    int y = lo;
                    for (; y <= hi && y < bottom; y++, i++) {
                        cellStates[i] = air;
                        kinds[i] = BELOW;
                    }
                    int count = Math.min(hi, top - 1) - y + 1;
                    if (count > 0) {
                        if (loadedColumn(x, z)) {
                            world.getColumn(x, z, y, count, cellStates, i);
                            // Runs of one state are common along a column: its kind is looked up once a run.
                            int last = -1;
                            byte lastKind = UNREAD;
                            for (int end = i + count; i < end; i++) {
                                int state = cellStates[i];
                                if (state != last) {
                                    last = state;
                                    lastKind = kindOf(state);
                                }
                                kinds[i] = lastKind;
                            }
                        } else {
                            for (int end = i + count; i < end; i++) {
                                cellStates[i] = air;
                                kinds[i] = UNLOADED;
                            }
                        }
                        y += count;
                    }
                    for (; y <= hi; y++, i++) {
                        cellStates[i] = air;
                        kinds[i] = ABOVE;
                    }
                }
            }
        }

        /**
         * The cells of column (x, z) whose centres lie within {@code reach16} of the dab's point, exactly those
         * {@link #within} accepts: their lowest and highest y, packed ({@link #spanLow}, {@link #spanHigh}; the low one
         * above the high one when there are none).
         */
        long span(int x, int z, long reach16) {
            long dx = 16L * x + 8 - dab.x16(), dz = 16L * z + 8 - dab.z16();
            long most;
            if (round) {
                long rest = reach16 * reach16 - dx * dx - dz * dz;
                if (rest < 0) return EMPTY_SPAN;
                // The largest |dy| with dy² <= rest, exactly.
                most = (long) Math.sqrt((double) rest);
                while (most * most > rest) most--;
                while ((most + 1) * (most + 1) <= rest) most++;
            } else {
                if (Math.max(Math.abs(dx), Math.abs(dz)) > reach16) return EMPTY_SPAN;
                most = reach16;
            }
            // |16y + 8 - p| <= most, for the dab's point p on y.
            long p = dab.y16();
            long lo = Math.floorDiv(p - 8 - most + 15, 16), hi = Math.floorDiv(p - 8 + most, 16);
            return (lo << 32) | (hi & 0xFFFFFFFFL);
        }

        /** The kind {@link SurfaceNormal#moment} sees. */
        int normalKind(int x, int y, int z) {
            return switch (kind(x, y, z)) {
                case OPEN -> SurfaceNormal.OPEN;
                case GROUND, STRUCTURE -> SurfaceNormal.SOLID;
                default -> SurfaceNormal.SKIP;
            };
        }

        /** The cell centre's distance from the dab's point over the radius, or -1 outside the ball. */
        double distance(int x, int y, int z) {
            long dx = 16L * x + 8 - dab.x16(), dy = 16L * y + 8 - dab.y16(), dz = 16L * z + 8 - dab.z16();
            if (round) {
                long d2 = dx * dx + dy * dy + dz * dz;
                if (d2 > r16 * r16) return -1;
                return Math.sqrt((double) d2) / r16;
            }
            long d = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
            if (d > r16) return -1;
            return (double) d / r16;
        }

        /**
         * Whether the cell may change: its centre within the ball for Smooth; for the line tools, within the cylinder,
         * the footprint across {@link #axis} (set first) and {@link #reach16} along it from the dab's point.
         */
        boolean inside(int x, int y, int z) {
            if (!lines) return within(x, y, z, r16);
            int ax = axis.axis();
            long dc = 16L * (ax == 0 ? x : ax == 1 ? y : z) + 8 - point16(ax);
            if (Math.abs(dc) > reach16) return false;
            return lateralInside(ax == 0 ? y : x, ax == 2 ? y : z);
        }

        /** Whether the cell centre lies within {@code reach16} of the dab's point (the ball's metric). */
        boolean within(int x, int y, int z, long reach16) {
            long dx = 16L * x + 8 - dab.x16(), dy = 16L * y + 8 - dab.y16(), dz = 16L * z + 8 - dab.z16();
            if (round) return dx * dx + dy * dy + dz * dz <= reach16 * reach16;
            return Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) <= reach16;
        }

        /** The fixed-point weight at distance {@code t}. */
        int weight(double t) {
            return (int) (base * TerrainKernel.falloff(spec.falloff(), t) * ONE + 0.5);
        }

        /** The dab's point on axis {@code ax} (0 x, 1 y, 2 z), in 1/16 block. */
        int point16(int ax) {
            return ax == 0 ? dab.x16() : ax == 1 ? dab.y16() : dab.z16();
        }

        /** Whether the ball's box meets the clip box (always without one). */
        boolean reaches(Box clip) {
            return clip == null || !(x0 + size - 1 < clip.min().x() || x0 > clip.max().x()
                    || y0 + size - 1 < clip.min().y() || y0 > clip.max().y()
                    || z0 + size - 1 < clip.min().z() || z0 > clip.max().z());
        }

        // ---- lines ----

        /** The line through lateral cell (a, b) across the axis: its distance from the dab's point over the radius, or -1. */
        double lateralDistance(int a, int b) {
            int ax = axis.axis();
            int pa = point16(ax == 0 ? 1 : 0), pb = point16(ax == 2 ? 1 : 2);
            long da = 16L * a + 8 - pa, db = 16L * b + 8 - pb;
            if (round) {
                long d2 = da * da + db * db;
                if (d2 > r16 * r16) return -1;
                return Math.sqrt((double) d2) / r16;
            }
            long d = Math.max(Math.abs(da), Math.abs(db));
            if (d > r16) return -1;
            return (double) d / r16;
        }

        /** Whether the line through lateral cell (a, b) lies in the footprint: {@link #lateralDistance} {@code >= 0}. */
        boolean lateralInside(int a, int b) {
            int ax = axis.axis();
            int pa = point16(ax == 0 ? 1 : 0), pb = point16(ax == 2 ? 1 : 2);
            long da = 16L * a + 8 - pa, db = 16L * b + 8 - pb;
            if (round) return da * da + db * db <= r16 * r16;
            return Math.max(Math.abs(da), Math.abs(db)) <= r16;
        }

        boolean inFootprint(int a, int b) {
            return a >= fa0 && a <= fa1 && b >= fb0 && b <= fb1;
        }

        int crossingAt(int a, int b) {
            return crossing[(a - fa0) * (fb1 - fb0 + 1) + (b - fb0)];
        }
    }

    /** Cell (x, y, z) from a coordinate {@code c} on {@code axis}'s axis and lateral (a, b): x, y, z order. */
    private static int cx(Facing axis, int c, int a) {
        return axis.axis() == 0 ? c : a;
    }

    private static int cy(Facing axis, int c, int a, int b) {
        return switch (axis.axis()) {
            case 0 -> a;
            case 1 -> c;
            default -> b;
        };
    }

    private static int cz(Facing axis, int c, int b) {
        return axis.axis() == 2 ? c : b;
    }

    // ------------------------------------------------------------------ one step

    private static final class Pass {
        final BrushSpec spec;
        final BrushTool tool;
        /** The step's first-listed dab: the stroke's own. */
        final Dab first;
        final StrokeState stroke;
        final WorldReader world;
        final StateSpace states;
        final Box clip;
        final Ball[] balls;
        final CellPlan cells;
        ColumnFilter filter;
        boolean slopes;
        /** The step's main changes, in plan order: each one's ball, cell, line direction (null for Smooth). */
        final List<int[]> changes = new ArrayList<>();
        /** {@link #vote}'s states found around a cell, and their scores. */
        private final int[] voteFound = new int[26], voteScore = new int[26];

        Pass(BrushSpec spec, Dab first, Dab[] dabs, StrokeState stroke, WorldReader world) {
            this.spec = spec;
            this.first = first;
            this.tool = spec.tool();
            this.stroke = stroke;
            this.world = world;
            this.states = world.states();
            this.clip = spec.clip();
            List<Ball> reaching = new ArrayList<>(dabs.length);
            for (Dab dab : dabs) {
                // The k-th ball of the step takes the stroke's k-th arrays (one left out gives its slot to the next).
                Ball ball = new Ball(spec, dab, world, stroke.surfaceScratch(), reaching.size());
                // A ball whose box misses the clip box reads nothing.
                if (ball.reaches(clip)) reaching.add(ball);
            }
            this.balls = reaching.toArray(new Ball[0]);
            this.cells = new CellPlan(world, clip, new CellPlan.Layout() {
                @Override
                public long key(int x, int y, int z) {
                    for (int a = 0; a < balls.length; a++) {
                        if (balls[a].inBox(x, y, z)) return ((long) a << 40) | balls[a].index(x, y, z);
                    }
                    return Long.MIN_VALUE;
                }

                @Override
                public boolean loaded(int x, int z) {
                    for (Ball ball : balls) {
                        if (ball.inBoxColumn(x, z)) return ball.loadedColumn(x, z);
                    }
                    return false;
                }
            });
        }

        void run(CellSink out) {
            // Bound first, so a bad mask fails the same way whether or not this step reaches the clip box.
            filter = stroke.filter(spec.mask(), states, world);
            if (balls.length == 0) return;
            slopes = usesSlope(spec.mask());
            if (tool == BrushTool.SMOOTH) {
                smooth();
            } else {
                lines();
            }
            clearPlants();
            cells.emit(out);
        }

        // ---- lines: Raise, Lower, Flatten ----

        private void lines() {
            for (Ball ball : balls) {
                if (tool == BrushTool.FLATTEN) {
                    ball.plane = planeFor(ball);
                    ball.axis = ball.plane.facing();
                } else {
                    long[] n = SurfaceNormal.moment(ball::normalKind, ball.bx, ball.by, ball.bz,
                            SurfaceNormal.radiusFor(spec.radius()));
                    ball.axis = SurfaceNormal.facing(n);
                }
                findCrossings(ball);
            }
            for (int a = 0; a < balls.length; a++) {
                Ball ball = balls[a];
                Facing axis = ball.axis;
                for (int la = ball.fa0; la <= ball.fa1; la++) {
                    for (int lb = ball.fb0; lb <= ball.fb1; lb++) {
                        double t = ball.lateralDistance(la, lb);
                        if (t < 0) continue;
                        int c = ball.crossingAt(la, lb);
                        if (c == NONE || decidedBefore(a, axis, la, lb) || !lineInsideClip(axis, la, lb)) continue;
                        int k = ball.weight(t);
                        for (int b = a + 1; b < balls.length; b++) {
                            Ball other = balls[b];
                            if (other.axis != axis || !other.inFootprint(la, lb)) continue;
                            double ot = other.lateralDistance(la, lb);
                            if (ot >= 0 && other.crossingAt(la, lb) == c) k = Math.max(k, other.weight(ot));
                        }
                        if (k <= 0) continue;
                        int x = cx(axis, c, la), y = cy(axis, c, la, lb), z = cz(axis, c, lb);
                        if (!filter.test(x, y, z, ball.state(x, y, z), slopes ? slope(ball, x, y, z) : 0)) continue;
                        line(a, ball, la, lb, c, k);
                    }
                }
            }
        }

        /** Each footprint line's surface cell: the ground cell with an open cell in front, nearest the dab's point. */
        private void findCrossings(Ball ball) {
            Facing axis = ball.axis;
            int ax = axis.axis();
            int aAxis = ax == 0 ? 1 : 0, bAxis = ax == 2 ? 1 : 2;
            int pa = ball.point16(aAxis), pb = ball.point16(bAxis);
            ball.fa0 = TerrainKernel.footprintLow(pa, (int) ball.r16);
            ball.fa1 = TerrainKernel.footprintHigh(pa, (int) ball.r16);
            ball.fb0 = TerrainKernel.footprintLow(pb, (int) ball.r16);
            ball.fb1 = TerrainKernel.footprintHigh(pb, (int) ball.r16);
            int width = ball.fb1 - ball.fb0 + 1;
            ball.crossing = new int[(ball.fa1 - ball.fa0 + 1) * width];
            for (int la = ball.fa0; la <= ball.fa1; la++) {
                for (int lb = ball.fb0; lb <= ball.fb1; lb++) {
                    int i = (la - ball.fa0) * width + (lb - ball.fb0);
                    ball.crossing[i] = ball.lateralDistance(la, lb) < 0 ? NONE : search(ball, la, lb);
                }
            }
        }

        /**
         * The line's surface cell's coordinate on the axis, or NONE: faces between neighbouring cells of the line are
         * tried in order of their distance from the dab's point (a tie goes to the face further along the direction),
         * and the first with ground inside the cylinder behind it and an open cell in front of it wins. The search
         * stops each way where the line leaves the cylinder.
         */
        private int search(Ball ball, int la, int lb) {
            Facing axis = ball.axis;
            int s = axis.sign();
            long p = ball.point16(axis.axis());
            // Face m lies at 16m, between cells m - 1 and m. Walk faces down from floor(p / 16) and up from the next.
            long down = Math.floorDiv(p, 16), up = down + 1;
            boolean downOpen = true, upOpen = true;
            while (downOpen || upOpen) {
                long dDown = p - 16 * down, dUp = 16 * up - p;
                boolean takeUp;
                if (!downOpen) {
                    takeUp = true;
                } else if (!upOpen) {
                    takeUp = false;
                } else {
                    // Nearer first; a tie goes further along the direction.
                    takeUp = dUp < dDown || (dUp == dDown && s > 0);
                }
                long m = takeUp ? up : down;
                // The ground cell behind face m and the open cell in front of it, along the direction.
                long ground = s > 0 ? m - 1 : m, open = s > 0 ? m : m - 1;
                int gx = cx(axis, (int) ground, la), gy = cy(axis, (int) ground, la, lb), gz = cz(axis, (int) ground, lb);
                if (!ball.inside(gx, gy, gz)) {
                    if (takeUp) upOpen = false; else downOpen = false;
                } else {
                    int ox = cx(axis, (int) open, la), oy = cy(axis, (int) open, la, lb), oz = cz(axis, (int) open, lb);
                    if (ball.kind(gx, gy, gz) == GROUND && ball.kind(ox, oy, oz) == OPEN) return (int) ground;
                }
                if (takeUp) up++; else down--;
            }
            return NONE;
        }

        /** Whether an earlier ball of the same direction has this line in its footprint and found a surface on it. */
        private boolean decidedBefore(int a, Facing axis, int la, int lb) {
            for (int b = 0; b < a; b++) {
                Ball other = balls[b];
                if (other.axis == axis && other.inFootprint(la, lb) && other.lateralDistance(la, lb) >= 0
                        && other.crossingAt(la, lb) != NONE) {
                    return true;
                }
            }
            return false;
        }

        /** Whether the line's lateral coordinates lie within the clip box's range on those axes (always without one). */
        private boolean lineInsideClip(Facing axis, int la, int lb) {
            if (clip == null) return true;
            return switch (axis.axis()) {
                case 0 -> la >= clip.min().y() && la <= clip.max().y() && lb >= clip.min().z() && lb <= clip.max().z();
                case 1 -> la >= clip.min().x() && la <= clip.max().x() && lb >= clip.min().z() && lb <= clip.max().z();
                default -> la >= clip.min().x() && la <= clip.max().x() && lb >= clip.min().y() && lb <= clip.max().y();
            };
        }

        private void line(int a, Ball ball, int la, int lb, int c, int k) {
            Facing axis = ball.axis;
            int acc = stroke.lineAccumulator(axis, la, lb);
            switch (tool) {
                case RAISE -> {
                    int sum = acc + k;
                    int move = sum / ONE;
                    stroke.setLineAccumulator(axis, la, lb, sum - move * ONE);
                    if (move > 0) raise(a, ball, la, lb, c, move);
                }
                case LOWER -> {
                    int sum = acc - k;
                    int move = sum / ONE;
                    stroke.setLineAccumulator(axis, la, lb, sum - move * ONE);
                    if (move < 0) lower(a, ball, la, lb, c, -move);
                }
                case FLATTEN -> {
                    int s = axis.sign();
                    long target = (long) s * ball.plane.target() - (long) s * c;
                    relax(a, ball, la, lb, c, k, acc, target);
                }
                default -> throw new IllegalStateException(tool + " has no lines");
            }
        }

        /**
         * Moves the line's surface {@code k × target} blocks toward the plane ({@code target} blocks away along the
         * direction), never past it and at most {@code radius + 8} blocks, as the Terrain mode's relax does. The
         * stroke's first dab on the line moves it at least one block, charged to the accumulator (it then holds a debt
         * the next dabs pay off, so a held stroke goes on at {@code k × target} a dab).
         */
        private void relax(int a, Ball ball, int la, int lb, int c, int k, int acc, long target) {
            Facing axis = ball.axis;
            boolean first = stroke.startLine(axis, la, lb);
            if (target == 0) {
                stroke.setLineAccumulator(axis, la, lb, 0);
                return;
            }
            int maxMove = spec.radius() + TerrainKernel.SCAN_MARGIN;
            long sum = acc + (long) k * target;
            long move = sum / ONE;
            move = target > 0 ? Math.min(move, target) : Math.max(move, target);
            if (move == 0 && first) move = Long.signum(target);
            boolean capped = Math.abs(move) > maxMove;
            if (capped) move = Long.signum(move) * maxMove;
            stroke.setLineAccumulator(axis, la, lb, move == target || capped ? 0 : (int) (sum - move * ONE));
            if (move > 0) raise(a, ball, la, lb, c, (int) move);
            if (move < 0) lower(a, ball, la, lb, c, (int) -move);
        }

        /** Fills up to {@code move} open cells in front of surface cell {@code c} with its block, inside the cylinder. */
        private void raise(int a, Ball ball, int la, int lb, int c, int move) {
            Facing axis = ball.axis;
            int s = axis.sign();
            int surface = ball.state(cx(axis, c, la), cy(axis, c, la, lb), cz(axis, c, lb));
            List<int[]> run = new ArrayList<>(move);
            for (int i = 1; i <= move; i++) {
                int u = c + s * i;
                int x = cx(axis, u, la), y = cy(axis, u, la, lb), z = cz(axis, u, lb);
                if (!ball.inside(x, y, z) || ball.kind(x, y, z) != OPEN) break;
                run.add(new int[] {x, y, z});
            }
            commit(a, ball, run, true, surface);
        }

        /**
         * Removes up to {@code move} cells from surface cell {@code c} inward, inside the cylinder, stopping at a
         * structure.
         */
        private void lower(int a, Ball ball, int la, int lb, int c, int move) {
            Facing axis = ball.axis;
            int s = axis.sign();
            List<int[]> run = new ArrayList<>(move);
            for (int i = 0; i < move; i++) {
                int u = c - s * i;
                int x = cx(axis, u, la), y = cy(axis, u, la, lb), z = cz(axis, u, lb);
                if (!ball.inside(x, y, z)) break;
                byte kind = ball.kind(x, y, z);
                if (kind != GROUND && kind != OPEN) break;
                if (kind == GROUND && supports(ball, x, y, z)) break;
                run.add(new int[] {x, y, z});
            }
            commit(a, ball, run, false, FILL);
        }

        /** Plans a line's run of changes, unless a plant it would clear lies outside the clip box. */
        private void commit(int a, Ball ball, List<int[]> run, boolean filling, int material) {
            for (int[] cell : run) {
                if (!plantsInsideClip(ball, cell[0], cell[1], cell[2], filling)) return;
            }
            for (int[] cell : run) planMain(a, ball, cell[0], cell[1], cell[2], filling ? material : FILL);
        }

        /**
         * Flatten's plane for a dab: its image under the symmetry image that maps the step's first dab onto this one
         * (the plane itself for the first dab); when none does (a step not listed as {@link SymmetricStep#of} lists it),
         * the image nearest the dab.
         */
        private SurfacePlane planeFor(Ball ball) {
            Symmetry symmetry = spec.symmetry();
            for (Symmetry.Image image : symmetry.images()) {
                if (symmetry.imageX(image, first.x16(), first.z16()) == ball.dab.x16()
                        && symmetry.imageZ(image, first.x16(), first.z16()) == ball.dab.z16()) {
                    SurfacePlane plane = spec.plane().image(symmetry, image);
                    if (plane != null) return plane;
                }
            }
            return nearestPlane(ball);
        }

        /** The image of Flatten's plane under the brush's symmetry nearest to the dab's point (ties: the first image). */
        private SurfacePlane nearestPlane(Ball ball) {
            SurfacePlane best = spec.plane();
            long bestDistance = Long.MAX_VALUE;
            for (Symmetry.Image image : spec.symmetry().images()) {
                SurfacePlane plane = spec.plane().image(spec.symmetry(), image);
                if (plane == null) continue;
                long distance = Math.abs(plane.face16() - ball.point16(plane.facing().axis()));
                if (distance < bestDistance) {
                    best = plane;
                    bestDistance = distance;
                }
            }
            return best;
        }

        // ---- Smooth ----

        /**
         * Smooth over flat arrays of each ball's box: the cells within two blocks of the ball are read at once, the
         * joined cells found by a flood over runs of open cells, and the 27-cell counts come from running counts along
         * y and z and a sum of three along x ({@link #sumSolids}), so a cell costs a few array reads. The cells are
         * visited in the same order, and decided by the same rules, as one cell at a time: x, then z, then y over the
         * ball.
         */
        private void smooth() {
            for (Ball ball : balls) {
                // Everything Smooth reads lies within two blocks of the ball: the flood (one block beyond it) and the
                // 3×3×3 block around each cell. Slopes and plants near the box's edge are read when asked for.
                ball.readAround(ball.r16 + 32);
                join(ball);
            }
            // The step's balls share a radius, so one buffer serves them all.
            byte[] sums = stroke.surfaceScratch().sums(balls[0].kinds.length);
            int reach = spec.radius();
            for (int a = 0; a < balls.length; a++) {
                Ball ball = balls[a];
                sumSolids(ball, sums);
                for (int x = ball.bx - reach; x <= ball.bx + reach; x++) {
                    for (int z = ball.bz - reach; z <= ball.bz + reach; z++) {
                        // The column's cells inside the ball (all within the dab's block ± the radius).
                        long span = ball.span(x, z, ball.r16);
                        int lo = spanLow(span), hi = spanHigh(span);
                        if (lo > hi) continue;
                        int i = ball.index(x, lo, z);
                        for (int y = lo; y <= hi; y++, i++) smoothCell(a, ball, x, y, z, i, sums);
                    }
                }
            }
        }

        /**
         * Counts into {@code sums}, for the cells the ball's cells' 3×3×3 blocks need, the solid cells ({@link #solid})
         * of the 3×3 square around each across y and z: a cell's 27-cell count is then its square's and those of its two
         * neighbours along x ({@link #solidAround}). Each column keeps a running count of three along y for itself and
         * the columns beside it along z. The neighbours along x lie within a block of the ball, and every cell their
         * squares take in within two blocks, all read ({@link Ball#readAround}); {@code sums} holds stale counts
         * elsewhere, never read.
         */
        private static void sumSolids(Ball ball, byte[] sums) {
            int size = ball.size, sz = ball.strideZ;
            byte[] kinds = ball.kinds;
            // The ball's cells lie 2 to size - 3 from the box's low corner on every axis.
            int yLow = ball.y0 + 2, yHigh = ball.y0 + size - 3;
            for (int x = ball.x0 + 1; x <= ball.x0 + size - 2; x++) {
                for (int z = ball.z0 + 2; z <= ball.z0 + size - 3; z++) {
                    long span = ball.span(x, z, ball.r16 + 16);
                    int lo = Math.max(spanLow(span), yLow), hi = Math.min(spanHigh(span), yHigh);
                    if (lo > hi) continue;
                    int i = ball.index(x, lo, z);
                    // The columns at z - 1, z and z + 1: their cells below and at the current y.
                    int belowN = solid(kinds[i - sz - 1]), hereN = solid(kinds[i - sz]);
                    int below = solid(kinds[i - 1]), here = solid(kinds[i]);
                    int belowS = solid(kinds[i + sz - 1]), hereS = solid(kinds[i + sz]);
                    for (int y = lo; y <= hi; y++, i++) {
                        int aboveN = solid(kinds[i - sz + 1]), above = solid(kinds[i + 1]);
                        int aboveS = solid(kinds[i + sz + 1]);
                        sums[i] = (byte) (belowN + hereN + aboveN + below + here + above + belowS + hereS + aboveS);
                        belowN = hereN;
                        hereN = aboveN;
                        below = here;
                        here = above;
                        belowS = hereS;
                        hereS = aboveS;
                    }
                }
            }
        }

        /** Smooth at cell (x, y, z) of ball {@code a}, index {@code i} in its box. */
        private void smoothCell(int a, Ball ball, int x, int y, int z, int i, byte[] sums) {
            byte kind = ball.kindAt(i);
            if (kind != OPEN && kind != GROUND) return;
            if (clip != null && !clip.contains(x, y, z)) return;
            if (!onSide(ball, i, kind)) return;
            // Neither a cell an earlier ball decides nor one next to an unloaded chunk changes; the count comes first
            // here, as it is cheaper than asking the earlier balls.
            int solid = solidAround(ball, x, z, i, sums);
            if (solid < 0) return;
            boolean isSolid = kind == GROUND;
            boolean wantSolid = solid >= MAJORITY;
            if (wantSolid == isSolid) {
                // The vote agrees with the cell: its accumulator is reset, unless an earlier ball decides it (while no
                // cell holds anything, there is nothing to reset: most of the ball's cells end here).
                if (stroke.anyCellAccumulator() && !cellDecidedBefore(a, x, y, z)) stroke.setCellAccumulator(x, y, z, 0);
                return;
            }
            flip(a, ball, x, y, z, i, wantSolid);
        }

        /** The rest of {@link #smoothCell} for a cell whose vote says it should flip ({@code wantSolid}: fill). */
        private void flip(int a, Ball ball, int x, int y, int z, int i, boolean wantSolid) {
            if (cellDecidedBefore(a, x, y, z)) return;
            if (!wantSolid && supports(ball, x, y, z)) return;
            int k = ball.weight(ball.distance(x, y, z));
            for (int b = a + 1; b < balls.length; b++) {
                Ball other = balls[b];
                if (!other.inBox(x, y, z)) continue;
                double ot = other.distance(x, y, z);
                if (ot >= 0 && onSide(other, x, y, z)) k = Math.max(k, other.weight(ot));
            }
            if (k <= 0) return;
            int material = wantSolid ? vote(ball, i) : FILL;
            if (wantSolid && material < 0) return;
            int seen = wantSolid ? material : ball.state(x, y, z);
            if (!filter.test(x, y, z, seen, slopes ? slope(ball, x, y, z) : 0)) return;
            if (!plantsInsideClip(ball, x, y, z, wantSolid)) return;
            int acc = stroke.cellAccumulator(x, y, z) + k;
            if (acc >= ONE) {
                stroke.setCellAccumulator(x, y, z, 0);
                planMain(a, ball, x, y, z, material);
            } else {
                stroke.setCellAccumulator(x, y, z, acc);
            }
        }

        /** Whether an earlier ball of the step has cell (x, y, z) inside it and on its side: that ball decides it. */
        private boolean cellDecidedBefore(int a, int x, int y, int z) {
            for (int b = 0; b < a; b++) {
                if (balls[b].inBox(x, y, z) && balls[b].inside(x, y, z) && onSide(balls[b], x, y, z)) return true;
            }
            return false;
        }

        /**
         * Marks the open cells joined to those around the dab (its block and the 26 around it) through open cells
         * within a block of the ball. The flood runs over <em>runs</em>: the stretches of open cells within reach up
         * each column. Cells of a run are joined to each other, and a run to those of the four columns beside it whose
         * stretches overlap it, so joining runs joins exactly the cells a flood from cell to cell would.
         */
        private static void join(Ball ball) {
            int size = ball.size;
            int columns = size * size;
            long reach16 = ball.r16 + 16;
            // Each column's runs, bottom up: run k lies in column runColumn[k] from y index runLow[k] to runHigh[k]
            // (from the box's bottom); the runs of column c are firstRun[c] to firstRun[c + 1] - 1.
            int[] firstRun = new int[columns + 1];
            int[] runColumn = new int[256], runLow = new int[256], runHigh = new int[256];
            int runs = 0;
            for (int c = 0; c < columns; c++) {
                firstRun[c] = runs;
                long span = ball.span(ball.x0 + c / size, ball.z0 + c % size, reach16);
                int lo = Math.max(spanLow(span) - ball.y0, 0), hi = Math.min(spanHigh(span) - ball.y0, size - 1);
                int base = c * size;
                int start = -1;
                for (int y = lo; y <= hi + 1; y++) {
                    boolean open = y <= hi && ball.kindAt(base + y) == OPEN;
                    if (open && start < 0) {
                        start = y;
                    } else if (!open && start >= 0) {
                        if (runs == runColumn.length) {
                            runColumn = java.util.Arrays.copyOf(runColumn, runs * 2);
                            runLow = java.util.Arrays.copyOf(runLow, runs * 2);
                            runHigh = java.util.Arrays.copyOf(runHigh, runs * 2);
                        }
                        runColumn[runs] = c;
                        runLow[runs] = start;
                        runHigh[runs] = y - 1;
                        runs++;
                        start = -1;
                    }
                }
            }
            firstRun[columns] = runs;
            boolean[] reached = new boolean[runs];
            int[] queue = new int[runs];
            int tail = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int c = (ball.bx + dx - ball.x0) * size + (ball.bz + dz - ball.z0), y = ball.by + dy - ball.y0;
                        for (int k = firstRun[c]; k < firstRun[c + 1]; k++) {
                            if (runLow[k] <= y && y <= runHigh[k] && !reached[k]) {
                                reached[k] = true;
                                queue[tail++] = k;
                            }
                        }
                    }
                }
            }
            // Cells within reach lie inside the box with a cell to spare on every side, so a run's column has four
            // columns beside it.
            for (int head = 0; head < tail; head++) {
                int k = queue[head];
                int c = runColumn[k], lo = runLow[k], hi = runHigh[k];
                for (int side = 0; side < 4; side++) {
                    int next = switch (side) {
                        case 0 -> c - size;
                        case 1 -> c + size;
                        case 2 -> c - 1;
                        default -> c + 1;
                    };
                    for (int m = firstRun[next]; m < firstRun[next + 1]; m++) {
                        if (runHigh[m] < lo) continue;
                        if (runLow[m] > hi) break;
                        if (!reached[m]) {
                            reached[m] = true;
                            queue[tail++] = m;
                        }
                    }
                }
            }
            boolean[] joined = ball.joined = ball.scratch == null ? new boolean[ball.kinds.length]
                    : ball.scratch.joined(ball.slot, ball.kinds.length);
            for (int head = 0; head < tail; head++) {
                int k = queue[head];
                int base = runColumn[k] * size;
                java.util.Arrays.fill(joined, base + runLow[k], base + runHigh[k] + 1, true);
            }
        }

        /** Whether a cell of the ball, index {@code i} and kind {@code kind}, is on the dab's side. */
        private static boolean onSide(Ball ball, int i, byte kind) {
            if (kind == OPEN) return ball.joined[i];
            if (kind != GROUND) return false;
            // A cell inside the ball has all six neighbours in the box.
            boolean[] joined = ball.joined;
            return joined[i - ball.strideX] || joined[i + ball.strideX] || joined[i - 1] || joined[i + 1]
                    || joined[i - ball.strideZ] || joined[i + ball.strideZ];
        }

        /** Whether a cell inside the ball is on the dab's side: a joined open cell, or ground touching one. */
        private static boolean onSide(Ball ball, int x, int y, int z) {
            int i = ball.index(x, y, z);
            return onSide(ball, i, ball.kindAt(i));
        }

        /**
         * Solid cells (ground, structures, below the world) of the 3×3×3 block around a cell of the ball, index
         * {@code i}, from {@link #sumSolids}; -1 if a chunk it reaches is not loaded.
         */
        private static int solidAround(Ball ball, int x, int z, int i, byte[] sums) {
            if (ball.anyUnloaded) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (!ball.loadedColumn(x + dx, z + dz)) return -1;
                    }
                }
            }
            return sums[i - ball.strideX] + sums[i] + sums[i + ball.strideX];
        }

        /**
         * The ground block most common around a cell (faces count 3, edges 2, corners 1; a tie goes to the first found
         * in x, then y, then z order), or -1 when no ground touches it.
         */
        private int vote(Ball ball, int cell) {
            int[] found = voteFound, score = voteScore;
            int n = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int away = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                        int next = cell + dx * ball.strideX + dy + dz * ball.strideZ;
                        if (away == 0 || ball.kindAt(next) != GROUND) continue;
                        int state = ball.cellStates[next];
                        int i = 0;
                        while (i < n && found[i] != state) i++;
                        if (i == n) {
                            found[n++] = state;
                            score[i] = 0;
                        }
                        score[i] += 4 - away;
                    }
                }
            }
            int best = -1;
            for (int i = 0; i < n; i++) {
                if (best < 0 || score[i] > score[best]) best = i;
            }
            return best < 0 ? -1 : found[best];
        }

        // ---- shared ----

        /** The Slope mask's value at a cell: the steepness of the estimate within two blocks of it. */
        private static int slope(Ball ball, int x, int y, int z) {
            return SurfaceNormal.slope(SurfaceNormal.moment(ball::normalKind, x, y, z, SurfaceNormal.SLOPE_RADIUS));
        }

        /**
         * Whether a ground cell may hold up a structure: one touches one of its six faces (a fence post standing on it,
         * a torch, ladder or sign on its side, a lantern hanging under it). It is then not removed.
         */
        private static boolean supports(Ball ball, int x, int y, int z) {
            return structure(ball, x, y + 1, z) || structure(ball, x, y - 1, z) || structure(ball, x - 1, y, z)
                    || structure(ball, x + 1, y, z) || structure(ball, x, y, z - 1) || structure(ball, x, y, z + 1);
        }

        private static boolean structure(Ball ball, int x, int y, int z) {
            return ball.inBox(x, y, z) && ball.kind(x, y, z) == STRUCTURE;
        }

        private void planMain(int a, Ball ball, int x, int y, int z, int next) {
            if (cells.planned(x, y, z)) return;
            cells.plan(x, y, z, next, ball.state(x, y, z));
            changes.add(new int[] {a, x, y, z, next == FILL ? 0 : 1});
        }

        /**
         * Plants the main changes leave floating or cut: the run of plant cells standing on each changed cell, at most
         * {@value TerrainKernel#MAX_PLANT_RUN} (plants hanging from a ceiling are left), and the lower half of a
         * two-block plant whose upper half filled.
         * Cells planned already are kept as planned.
         */
        private void clearPlants() {
            for (int m = 0; m < changes.size(); m++) {
                int[] change = changes.get(m);
                Ball ball = balls[change[0]];
                int x = change[1], y = change[2], z = change[3];
                boolean filled = change[4] == 1;
                forEachPlant(ball, x, y, z, filled, (px, py, pz) -> {
                    if (!cells.planned(px, py, pz)) cells.plan(px, py, pz, FILL, ball.state(px, py, pz));
                });
            }
        }

        /** Whether every plant a change at (x, y, z) would clear lies inside the clip box (always without one). */
        private boolean plantsInsideClip(Ball ball, int x, int y, int z, boolean filling) {
            if (clip == null) return true;
            boolean[] inside = {true};
            forEachPlant(ball, x, y, z, filling, (px, py, pz) -> {
                if (!clip.contains(px, py, pz)) inside[0] = false;
            });
            return inside[0];
        }

        @FunctionalInterface
        private interface CellVisitor {
            void visit(int x, int y, int z);
        }

        private void forEachPlant(Ball ball, int x, int y, int z, boolean filling, CellVisitor visitor) {
            plantRun(ball, x, y, z, Facing.UP, visitor);
            if (filling && StateFlags.has(states.flags(ball.state(x, y, z)), StateFlags.UPPER_HALF)
                    && ball.inBox(x, y - 1, z) && plant(ball, x, y - 1, z)) {
                visitor.visit(x, y - 1, z);
            }
        }

        private void plantRun(Ball ball, int x, int y, int z, Facing direction, CellVisitor visitor) {
            int dx = direction.axis() == 0 ? direction.sign() : 0;
            int dy = direction.axis() == 1 ? direction.sign() : 0;
            int dz = direction.axis() == 2 ? direction.sign() : 0;
            for (int i = 1; i <= TerrainKernel.MAX_PLANT_RUN; i++) {
                int px = x + i * dx, py = y + i * dy, pz = z + i * dz;
                if (!ball.inBox(px, py, pz) || !plant(ball, px, py, pz)) return;
                visitor.visit(px, py, pz);
            }
        }

        private boolean plant(Ball ball, int x, int y, int z) {
            return ball.kind(x, y, z) == OPEN && SurfaceScan.plant(states.flags(ball.state(x, y, z)));
        }
    }
}
