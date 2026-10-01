package dev.sculptory.core.brush;

import dev.sculptory.core.Box;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>Test only.</b> The Surface-mode kernel exactly as it was on main 6a043a85, before Smooth was made faster
 * (2026-09-29): {@code SurfaceKernelDifferentialTest} checks that {@link SurfaceKernel} writes the same as this on many
 * random scenes and strokes, and {@code SurfaceKernelCostTest} times both. Not to be changed.
 *
 * <p>Raise, Lower, Smooth and Flatten in the Surface mode ({@link SculptMode#SURFACE}): they
 * work on whatever surface a dab touches, floor, wall, ceiling or the underside of an overhang, and only inside the
 * dab's <b>ball</b>: the cells whose centres lie within the radius of the dab's point (a cube for {@link Shape#SQUARE}).
 * Nothing outside the ball changes, except plants standing on a changed block, at most
 * {@value TerrainKernel#MAX_PLANT_RUN} cells of them. Ground, open cells and structures are {@link SurfaceScan}'s:
 * structures (stairs, fences, torches, block entities) never change, and a ground block a structure touches (standing on
 * it, fixed to its side, hanging under it) is not removed.
 *
 * <p><b>Raise and Lower</b> work along the direction the surface faces around the dab, {@link SurfaceNormal}'s estimate
 * snapped to one of the six directions (up on a floor, sideways on a wall, down under a ceiling). The ball is cut into
 * lines along that direction, one per cell of the disc across it (the footprint, as the Terrain mode's columns: weight
 * by the distance across, Circle or Square). On each line the surface is the ground cell with an open cell in front of
 * it (toward the direction) nearest the dab, searched outward from the dab's point; a line without one inside the ball
 * is left alone. Each line keeps a fixed-point accumulator in {@link StrokeState}, as a column does in the Terrain mode:
 * Raise adds the weight and, per whole block, fills the open cell in front of the surface with the surface block (at
 * most one block per dab); Lower subtracts it and removes the surface block. On a floor this is the Terrain mode's
 * Raise and Lower cut to the ball.
 *
 * <p><b>Flatten</b> levels against {@link BrushSpec#plane()}, fixed when the press began: the face of the layer the
 * press point was on, facing the way the surface faced there (up for ground, down for a ceiling, sideways for a wall).
 * With symmetry each copy uses the plane's image under the symmetry image that made it. Lines run along the plane's facing, and each
 * line's surface moves toward the plane as a Terrain-mode column moves toward flattenY: {@code weight × distance} per
 * dab, carried in the line's accumulator, never past the plane: filling open cells in front of the surface with its
 * block, or removing cells from the surface down to the plane (stopping at a structure), inside the ball.
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
 * <p><b>Reads</b> stay within {@code radius + 2} of the dab's block on every axis, lazily, in 8³ blocks allocated as
 * they are first read; the fluid refill of a plant cleared at the box's top looks one cell above it. {@code
 * EngineEditService.dabBox} covers all of that. Writes: Raise and Lower at most one cell per line of the footprint plus
 * plants; Smooth and Flatten at most the ball's cells plus plants. The arithmetic is integer except the falloff (as
 * the Terrain mode's), and nothing depends on hash order or on how states are numbered, so the client's prediction
 * and the server agree.
 */
final class SurfaceKernelReference {
    /** Solid cells of a 3×3×3 block, itself included, that make its centre solid under Smooth. */
    static final int MAJORITY = 14;

    private static final int ONE = StrokeState.ONE;
    private static final int FILL = CellPlan.FILL;
    private static final int NONE = Integer.MIN_VALUE;

    // Snapshot kinds.
    private static final byte UNREAD = 0;
    private static final byte OPEN = 1;
    private static final byte GROUND = 2;
    private static final byte STRUCTURE = 3;
    /** Below the build height: solid, never written. */
    private static final byte BELOW = 4;
    /** Above the build height: open, never written. */
    private static final byte ABOVE = 5;
    private static final byte UNLOADED = 6;

    private SurfaceKernelReference() {}

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
        return switch (mask) {
            case SurfaceMask.Slope slope -> true;
            case SurfaceMask.And and -> and.masks().stream().anyMatch(SurfaceKernelReference::usesSlope);
            case SurfaceMask.Not not -> usesSlope(not.mask());
            default -> false;
        };
    }

    // ------------------------------------------------------------------ one dab's ball

    /** One dab of a step: its ball, the box it may read ({@code radius + 2} around its block), and what it found. */
    private static final class Ball {
        final Dab dab;
        final BrushSpec spec;
        final WorldReader world;
        final StateSpace states;
        final long r16;
        final boolean round;
        /** {@code strength × pressure / 255}. */
        final double base;
        final int bx, by, bz;
        /** The box: origin and edge. */
        final int x0, y0, z0, size;
        final int bottom, top;
        /**
         * The snapshot, in blocks of 8³ cells allocated on first read (a Raise or Lower reads a small part of the box):
         * each cell's state and kind ({@link #UNREAD} until read).
         */
        final int blocksPerSide;
        final int[][] stateBlocks;
        final byte[][] kindBlocks;
        /** Loaded flags of the box's chunks: 0 not asked, 1 loaded, 2 not. */
        final int cx0, cz0, cw;
        final byte[] chunks;

        // Lines (Raise, Lower, Flatten).
        Facing axis;
        SurfacePlane plane;
        /** The footprint across the axis: lateral cell ranges, and each line's surface cell on the axis or NONE. */
        int fa0, fa1, fb0, fb1;
        int[] crossing;

        // Smooth.
        boolean[] joined;

        Ball(BrushSpec spec, Dab dab, WorldReader world) {
            this.dab = dab;
            this.spec = spec;
            this.world = world;
            this.states = world.states();
            this.r16 = 16L * spec.radius();
            this.round = spec.shape() == Shape.CIRCLE;
            this.base = (double) spec.strength() * dab.pressure() / Dab.FULL_PRESSURE;
            this.bx = dab.blockX();
            this.by = dab.blockY();
            this.bz = dab.blockZ();
            int reach = spec.radius() + 2;
            this.x0 = bx - reach;
            this.y0 = by - reach;
            this.z0 = bz - reach;
            this.size = 2 * reach + 1;
            this.bottom = world.bottomY();
            this.top = world.topYExclusive();
            this.blocksPerSide = (size + 7) >> 3;
            int blocks = blocksPerSide * blocksPerSide * blocksPerSide;
            this.stateBlocks = new int[blocks][];
            this.kindBlocks = new byte[blocks][];
            this.cx0 = x0 >> 4;
            this.cz0 = z0 >> 4;
            this.cw = ((x0 + size - 1) >> 4) - cx0 + 1;
            int cd = ((z0 + size - 1) >> 4) - cz0 + 1;
            this.chunks = new byte[cw * cd];
        }

        boolean inBox(int x, int y, int z) {
            return x >= x0 && x < x0 + size && y >= y0 && y < y0 + size && z >= z0 && z < z0 + size;
        }

        boolean inBoxColumn(int x, int z) {
            return x >= x0 && x < x0 + size && z >= z0 && z < z0 + size;
        }

        /** A cell's index in the box, x, then z, then y (the step's cell keys and Smooth's joined cells). */
        int index(int x, int y, int z) {
            return ((x - x0) * size + (z - z0)) * size + (y - y0);
        }

        /** The snapshot block holding a cell. */
        private int block(int x, int y, int z) {
            return (((x - x0) >> 3) * blocksPerSide + ((z - z0) >> 3)) * blocksPerSide + ((y - y0) >> 3);
        }

        /** A cell's index in its snapshot block. */
        private static int inBlock(int dx, int dy, int dz) {
            return ((dx & 7) << 6) | ((dz & 7) << 3) | (dy & 7);
        }

        boolean loadedColumn(int x, int z) {
            int i = ((x >> 4) - cx0) * (chunks.length / cw) + ((z >> 4) - cz0);
            if (chunks[i] == 0) chunks[i] = world.isLoaded(x >> 4, z >> 4) ? (byte) 1 : (byte) 2;
            return chunks[i] == 1;
        }

        /** The cell's kind; read from the world the first time. */
        byte kind(int x, int y, int z) {
            int b = block(x, y, z);
            byte[] kinds = kindBlocks[b];
            if (kinds == null) {
                kinds = kindBlocks[b] = new byte[512];
                stateBlocks[b] = new int[512];
            }
            int i = inBlock(x - x0, y - y0, z - z0);
            byte kind = kinds[i];
            if (kind != UNREAD) return kind;
            int state = states.air();
            if (y < bottom) {
                kind = BELOW;
            } else if (y >= top) {
                kind = ABOVE;
            } else if (!loadedColumn(x, z)) {
                kind = UNLOADED;
            } else {
                state = world.get(x, y, z);
                int flags = states.flags(state);
                kind = SurfaceScan.ground(flags) ? GROUND : SurfaceScan.open(flags) ? OPEN : STRUCTURE;
            }
            stateBlocks[b][i] = state;
            kinds[i] = kind;
            return kind;
        }

        int state(int x, int y, int z) {
            kind(x, y, z);
            return stateBlocks[block(x, y, z)][inBlock(x - x0, y - y0, z - z0)];
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

        boolean inside(int x, int y, int z) {
            return within(x, y, z, r16);
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
                Ball ball = new Ball(spec, dab, world);
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
         * and the first with ground inside the ball behind it and an open cell in front of it wins. The search stops
         * each way where the line leaves the ball.
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
         * direction), never past it and at most {@code radius + 8} blocks, as the Terrain mode's relax does.
         */
        private void relax(int a, Ball ball, int la, int lb, int c, int k, int acc, long target) {
            Facing axis = ball.axis;
            if (target == 0) {
                stroke.setLineAccumulator(axis, la, lb, 0);
                return;
            }
            int maxMove = spec.radius() + TerrainKernel.SCAN_MARGIN;
            long sum = acc + (long) k * target;
            long move = sum / ONE;
            move = target > 0 ? Math.min(move, target) : Math.max(move, target);
            boolean capped = Math.abs(move) > maxMove;
            if (capped) move = Long.signum(move) * maxMove;
            stroke.setLineAccumulator(axis, la, lb, move == target || capped ? 0 : (int) (sum - move * ONE));
            if (move > 0) raise(a, ball, la, lb, c, (int) move);
            if (move < 0) lower(a, ball, la, lb, c, (int) -move);
        }

        /** Fills up to {@code move} open cells in front of surface cell {@code c} with its block, inside the ball. */
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

        /** Removes up to {@code move} cells from surface cell {@code c} inward, inside the ball, stopping at a structure. */
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

        private void smooth() {
            for (Ball ball : balls) join(ball);
            for (int a = 0; a < balls.length; a++) {
                Ball ball = balls[a];
                int reach = spec.radius();
                for (int x = ball.bx - reach; x <= ball.bx + reach; x++) {
                    for (int z = ball.bz - reach; z <= ball.bz + reach; z++) {
                        for (int y = ball.by - reach; y <= ball.by + reach; y++) {
                            double t = ball.distance(x, y, z);
                            if (t < 0) continue;
                            smoothCell(a, ball, x, y, z, t);
                        }
                    }
                }
            }
        }

        private void smoothCell(int a, Ball ball, int x, int y, int z, double t) {
            byte kind = ball.kind(x, y, z);
            if (kind != OPEN && kind != GROUND) return;
            if (clip != null && !clip.contains(x, y, z)) return;
            if (!onSide(ball, x, y, z)) return;
            for (int b = 0; b < a; b++) {
                if (balls[b].inBox(x, y, z) && balls[b].inside(x, y, z) && onSide(balls[b], x, y, z)) return;
            }
            int solid = solidAround(ball, x, y, z);
            if (solid < 0) return;
            boolean isSolid = kind == GROUND;
            boolean wantSolid = solid >= MAJORITY;
            if (wantSolid == isSolid) {
                stroke.setCellAccumulator(x, y, z, 0);
                return;
            }
            if (!wantSolid && supports(ball, x, y, z)) return;
            int k = ball.weight(t);
            for (int b = a + 1; b < balls.length; b++) {
                Ball other = balls[b];
                if (!other.inBox(x, y, z)) continue;
                double ot = other.distance(x, y, z);
                if (ot >= 0 && onSide(other, x, y, z)) k = Math.max(k, other.weight(ot));
            }
            if (k <= 0) return;
            int material = wantSolid ? vote(ball, x, y, z) : FILL;
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

        /**
         * Marks the open cells joined to those around the dab (its block and the 26 around it) through open cells
         * within a block of the ball.
         */
        private void join(Ball ball) {
            ball.joined = new boolean[ball.size * ball.size * ball.size];
            long reach16 = ball.r16 + 16;
            int[] queue = new int[64];
            int head = 0, tail = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int x = ball.bx + dx, y = ball.by + dy, z = ball.bz + dz;
                        if (!ball.within(x, y, z, reach16) || ball.kind(x, y, z) != OPEN) continue;
                        int i = ball.index(x, y, z);
                        if (ball.joined[i]) continue;
                        ball.joined[i] = true;
                        if (tail + 3 > queue.length) queue = java.util.Arrays.copyOf(queue, queue.length * 2);
                        queue[tail++] = x;
                        queue[tail++] = y;
                        queue[tail++] = z;
                    }
                }
            }
            int[][] steps = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
            while (head < tail) {
                int x = queue[head++], y = queue[head++], z = queue[head++];
                for (int[] step : steps) {
                    int nx = x + step[0], ny = y + step[1], nz = z + step[2];
                    if (!ball.inBox(nx, ny, nz) || !ball.within(nx, ny, nz, reach16)) continue;
                    int i = ball.index(nx, ny, nz);
                    if (ball.joined[i] || ball.kind(nx, ny, nz) != OPEN) continue;
                    ball.joined[i] = true;
                    if (tail + 3 > queue.length) queue = java.util.Arrays.copyOf(queue, queue.length * 2);
                    queue[tail++] = nx;
                    queue[tail++] = ny;
                    queue[tail++] = nz;
                }
            }
        }

        /** Whether a cell is on the dab's side: a joined open cell, or ground touching one. */
        private static boolean onSide(Ball ball, int x, int y, int z) {
            byte kind = ball.kind(x, y, z);
            if (kind == OPEN) return ball.joined[ball.index(x, y, z)];
            if (kind != GROUND) return false;
            return joined(ball, x - 1, y, z) || joined(ball, x + 1, y, z) || joined(ball, x, y - 1, z)
                    || joined(ball, x, y + 1, z) || joined(ball, x, y, z - 1) || joined(ball, x, y, z + 1);
        }

        private static boolean joined(Ball ball, int x, int y, int z) {
            return ball.inBox(x, y, z) && ball.joined[ball.index(x, y, z)];
        }

        /** Solid cells (ground, structures, below the world) of the 3×3×3 block around a cell, or -1 if one is unloaded. */
        private static int solidAround(Ball ball, int x, int y, int z) {
            int solid = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        byte kind = ball.kind(x + dx, y + dy, z + dz);
                        if (kind == UNLOADED) return -1;
                        if (kind == GROUND || kind == STRUCTURE || kind == BELOW) solid++;
                    }
                }
            }
            return solid;
        }

        /**
         * The ground block most common around a cell (faces count 3, edges 2, corners 1; a tie goes to the first found
         * in x, then y, then z order), or -1 when no ground touches it.
         */
        private static int vote(Ball ball, int x, int y, int z) {
            int[] found = new int[26];
            int[] score = new int[26];
            int n = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int away = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                        if (away == 0 || ball.kind(x + dx, y + dy, z + dz) != GROUND) continue;
                        int state = ball.state(x + dx, y + dy, z + dz);
                        int i = 0;
                        while (i < n && found[i] != state) i++;
                        if (i == n) found[n++] = state;
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
