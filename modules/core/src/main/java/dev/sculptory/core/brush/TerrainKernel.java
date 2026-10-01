package dev.sculptory.core.brush;

import dev.sculptory.core.Box;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The terrain brush kernel, shared by all six tools. A spec in the Surface mode ({@link SculptMode#SURFACE}: Raise,
 * Lower, Smooth and Flatten on any surface) is handed to {@link SurfaceKernel} after the checks below; everything else
 * here is the Terrain mode ("Terrain (from above)"), whose output is pinned by {@code TerrainModeGoldenTest}. One step
 * (a dab and its symmetric copies, see {@link Symmetry}) runs in four stages:
 *
 * <ol>
 *   <li><b>Read.</b> For every column of each dab's footprint plus a one-column ring, find the surface with
 *       {@link SurfaceScan} (the definition scatter shares): scan down
 *       from {@code dabY + radius + }{@value #SCAN_MARGIN} (or from the reader's
 *       {@link WorldReader#heightHint} when that is lower; the cells skipped are air, so the result is the
 *       same, and a hint whose cell is not air is ignored) to {@code dabY - radius - }{@value #SCAN_MARGIN}
 *       through open cells (air, replaceable, vegetation, fluid) to the first ground cell
 *       ({@link StateFlags#TERRAIN_SOLID} without a block entity). A column has no surface, and is skipped,
 *       if its chunk is not loaded, the scan starts inside a non-open cell (the surface is above the window),
 *       it meets a structure cell first, or it finds nothing. Structures are cells that are neither ground
 *       nor open: non-full blocks such as stairs and fences, and every block with a block entity (chests,
 *       barrels, furnaces). Brushes never overwrite them.</li>
 *   <li><b>Decide.</b> Per footprint column: weight {@code k = strength × pressure/255 × falloff(t)}, where
 *       {@code t} is the column centre's distance from the dab centre over the radius (Euclidean for
 *       circles, Chebyshev for squares). A column inside several footprints of the step is decided once, by the
 *       first of those dabs (in step order) whose own scan found a surface there, with the largest weight among the
 *       dabs whose scans found that same surface; a dab whose window misses it (a copy standing at another height)
 *       takes no part. Skip it if {@code k} rounds to zero or the {@link SurfaceMask}
 *       rejects it. Otherwise add to the column's fixed-point accumulator in {@link StrokeState} and move the
 *       column by the whole blocks it holds:
 *       <ul>
 *         <li>RAISE {@code +k} and LOWER {@code -k} blocks per dab;</li>
 *         <li>FLATTEN and SMOOTH {@code k × target}, never past the target and at most
 *             {@code radius + }{@value #SCAN_MARGIN} blocks per dab: {@code flattenY - y} for FLATTEN (with
 *             {@code flattenY} clamped to the build height), and for SMOOTH the mean surface height of the 3×3
 *             neighbourhood minus {@code y}, rounded to the nearest block with ties toward the current height;</li>
 *         <li>PAINT and PALETTE repaint the column once its accumulator reaches one block: the top
 *             {@code max(1, depth)} ground cells take {@code material.apply(states, x, y, z, before)}. A mix laid out
 *             in space ({@link Pattern.Arranged}: Patches, Gradient, Steepness) is read at the column's pre-image
 *             under the symmetry image that maps the step's first dab onto the dab deciding the column (so a copy
 *             mirrors or turns the pattern), and a Steepness layout on the ground's steepness at the column
 *             ({@link ColumnSteepness}, read with the snapshot). A Random mix ({@link Pattern.Weighted}) is read at the
 *             cell itself, as always.</li>
 *       </ul></li>
 *   <li><b>Write.</b> Raising copies the surface state upward into open cells. Lowering removes cells from
 *       the top down, stopping at a structure cell. When a column moves, the plants standing on its old
 *       surface (up to {@value #MAX_PLANT_RUN} vegetation cells) are removed rather than left floating or
 *       half-buried.</li>
 *   <li><b>Fluids.</b> A removed cell becomes the {@link StateSpace#fluidSource} supplied by itself, the cell
 *       above or a horizontal neighbour (in that order: above, north, south, west, east). Only fluid source
 *       blocks, waterlogged blocks and blocks that always hold source fluid (seagrass) supply fluid; flowing
 *       and falling fluid do not. Removed cells next to or below a filled cell fill the same way. So lowering
 *       below a water surface refills with water; everything else becomes air.</li>
 * </ol>
 *
 * <p><b>Steps.</b> Every dab of a step reads the world as it was before the step, so overlapping copies (mirrored
 * Smooth dabs near the plane) give the same result whatever order they are listed in; a copy never sees another
 * copy's writes. The dabs are sorted (x, then z, height, pressure, index) and repeats dropped before anything is
 * read, so the cells also reach the sink in the same order. The one thing the listing order decides is which dab is
 * the stroke's own for a laid-out mix: the first listed, as {@link SymmetricStep#of} lists it. {@link #applyStep} takes each dab at the height it is
 * given; {@link BrushKernel#apply} first stands each copy on the ground where it lands ({@link SymmetricStep}, reading
 * the same pre-step world), and leaves out a copy that finds none.
 *
 * <p><b>Clip box.</b> With a {@link BrushSpec#clip()}, only cells inside the box are written: columns outside
 * its x/z range are skipped before they accumulate anything, and every planned cell outside it (raised, lowered,
 * painted, a plant removed or a fluid refill) is dropped before the fluid step. A dropped cell keeps its state,
 * so the fluid step sees it as it is in the world. A column that would move but has plants reaching outside the
 * box does not move at all, so no half or floating plant is left behind. Reads are not clipped: the surface
 * scan, slopes and Smooth's neighbourhood still look outside the box. A dab whose footprint misses the box
 * reads nothing. The box and the mask apply to every copy alike.
 *
 * <p><b>Work per dab</b> is bounded: a column moves at most {@code radius + }{@value #SCAN_MARGIN} blocks and
 * paints at most {@value BrushSpec#MAX_DEPTH} cells, plus {@value #MAX_PLANT_RUN} plant cells. At radius 32 a
 * dab plans at most 3,209 × 42 ≈ 135k cells. Surface scans read at most {@code 2 × (radius + 8) + 1} cells per
 * column of the grid ({@code (2 × radius + 3)²} columns). A step does this for each of its (at most
 * {@value Symmetry#MAX_COPIES}) dabs; locating a copy reads one more column of at most
 * {@code 2 × }{@value SymmetricStep#GROUND_SEARCH}{@code  + 1} cells.
 *
 * <p>All reads happen before any write, so the result doesn't depend on whether the sink writes through to
 * the world. The caller must make a step's writes visible before the next step. Cells reach the sink in a
 * fixed order (dab by dab in the sorted order; within a dab, column x, then z, then y ascending), and only when
 * their state changes. The arithmetic uses only {@code + - * /} and {@code Math.sqrt}, and accumulators are
 * integers, so client and server agree.
 */
final class TerrainKernel implements BrushKernel {
    static final int SCAN_MARGIN = 8;
    static final int MAX_PLANT_RUN = 2;
    /** Dab block coordinates must stay within this of the origin (the vanilla world is ±30M). */
    static final int MAX_HORIZONTAL = 1 << 25;

    private static final int ONE = StrokeState.ONE;
    private static final int NO_SURFACE = SurfaceScan.NONE;
    private static final int FILL = CellPlan.FILL;
    /** The order a step's dabs are handled in: fixed, so the listing order makes no difference. */
    private static final Comparator<Dab> STEP_ORDER = Comparator.comparingInt(Dab::x16).thenComparingInt(Dab::z16)
            .thenComparingInt(Dab::y16).thenComparingInt(Dab::pressure).thenComparingInt(Dab::index);

    private final BrushTool tool;

    TerrainKernel(BrushTool tool) {
        if (!Objects.requireNonNull(tool).terrain()) throw new IllegalArgumentException(tool + " has its own kernel");
        this.tool = tool;
    }

    @Override
    public void applyStep(BrushSpec s, List<Dab> dabs, StrokeState st, WorldReader w, CellSink out) {
        Objects.requireNonNull(s);
        Objects.requireNonNull(dabs);
        Objects.requireNonNull(st);
        Objects.requireNonNull(w);
        Objects.requireNonNull(out);
        if (s.tool() != tool) throw new IllegalArgumentException("The " + tool + " kernel cannot apply a " + s.tool() + " brush");
        if (dabs.isEmpty() || dabs.size() > Symmetry.MAX_COPIES) {
            throw new IllegalArgumentException("A step holds 1-" + Symmetry.MAX_COPIES + " dabs, not " + dabs.size());
        }
        for (Dab d : dabs) {
            Objects.requireNonNull(d);
            if (!insideLimit(d)) throw new IllegalArgumentException("Dab outside the world: " + d);
        }
        st.checkMaterial(s.material(), w.states());
        if (s.surface()) {
            // "Surface (any direction)": the ball kernel. The Terrain mode below is unchanged.
            SurfaceKernel.apply(s, dabs.get(0), sorted(dabs), st, w, out);
            return;
        }
        new StepPass(s, dabs.get(0), sorted(dabs), st, w).run(out);
    }

    /** Whether the dab's block lies within {@link #MAX_HORIZONTAL} (else a step holding it fails). */
    static boolean insideLimit(Dab d) {
        return Math.abs(d.blockX()) <= MAX_HORIZONTAL && Math.abs(d.blockZ()) <= MAX_HORIZONTAL;
    }

    /**
     * Whether the dab's footprint box meets the spec's clip box's x/z range (always without one). A dab that misses it
     * reads and writes nothing.
     */
    static boolean reachesClip(BrushSpec spec, Dab dab) {
        Box clip = spec.clip();
        if (clip == null) return true;
        int r16 = spec.radius() * 16;
        return !(footprintHigh(dab.x16(), r16) < clip.min().x() || footprintLow(dab.x16(), r16) > clip.max().x()
                || footprintHigh(dab.z16(), r16) < clip.min().z() || footprintLow(dab.z16(), r16) > clip.max().z());
    }

    /** The first column whose centre ({@code 16c + 8}) is within {@code r16} of {@code c16} (1/16 block). */
    static int footprintLow(int c16, int r16) {
        return Math.floorDiv(c16 - r16 - 8 + 15, 16);
    }

    /** The last column whose centre is within {@code r16} of {@code c16}. */
    static int footprintHigh(int c16, int r16) {
        return Math.floorDiv(c16 + r16 - 8, 16);
    }

    /** The step's dabs in {@link #STEP_ORDER}, each once. */
    static Dab[] sorted(List<Dab> dabs) {
        Dab[] order = dabs.toArray(new Dab[0]);
        Arrays.sort(order, STEP_ORDER);
        int count = 0;
        for (Dab d : order) {
            if (count == 0 || !order[count - 1].equals(d)) order[count++] = d;
        }
        return Arrays.copyOf(order, count);
    }

    /** Falloff weight in [0, 1] at normalized distance {@code t} in [0, 1]; non-increasing in {@code t}. */
    static double falloff(Falloff falloff, double t) {
        return switch (falloff) {
            case CONSTANT -> 1.0;
            case LINEAR -> 1.0 - t;
            case SMOOTH -> 1.0 - t * t * (3.0 - 2.0 * t);
            case SPHERE -> Math.sqrt(Math.max(0.0, 1.0 - t * t));
        };
    }

    /** One dab of a step: its footprint, and the snapshot of the columns it reads. */
    private static final class Area {
        private final Dab dab;
        private final BrushSpec spec;
        private final long r16;
        /** {@code strength × pressure / 255}. */
        private final double base;
        /** Footprint bounding box, in columns. */
        private final int fx0, fx1, fz0, fz1;
        /** The snapshot grid: the footprint box plus a ring of one column. */
        private final int gx0, gz0, gw, gd;
        /** The scan window. */
        private final int yTop, yBottom;
        private int[] height;
        private int[] surface;
        private int[] plants;
        /** Loaded flags of the chunks the grid touches. */
        private int cx0, cz0, cd;
        private boolean[] loaded;
        /**
         * The symmetry image that maps the step's first dab onto this one ({@link Symmetry#imageOf}): a laid-out mix is
         * read at a column's pre-image under it, so a copy mirrors or turns the pattern.
         */
        private final Symmetry.Image image;
        /** The ground's steepness around the footprint, for a mix laid out by steepness; else {@code null}. */
        private ColumnSteepness steepness;

        Area(BrushSpec spec, Dab dab, Dab first, int worldBottom, int worldTop) {
            this.dab = dab;
            this.spec = spec;
            this.image = spec.symmetry().imageOf(first, dab);
            // Columns whose centre (16c + 8 in 1/16 blocks) is within the radius, per axis.
            int radius16 = spec.radius() * 16;
            r16 = radius16;
            base = (double) spec.strength() * dab.pressure() / Dab.FULL_PRESSURE;
            fx0 = footprintLow(dab.x16(), radius16);
            fx1 = footprintHigh(dab.x16(), radius16);
            fz0 = footprintLow(dab.z16(), radius16);
            fz1 = footprintHigh(dab.z16(), radius16);
            gx0 = fx0 - 1;
            gz0 = fz0 - 1;
            gw = fx1 - fx0 + 3;
            gd = fz1 - fz0 + 3;
            long reach = (long) spec.radius() + SCAN_MARGIN;
            yTop = (int) Math.max(worldBottom, Math.min(worldTop, dab.blockY() + reach));
            yBottom = (int) Math.max(worldBottom, Math.min(worldTop, dab.blockY() - reach));
        }

        /** Whether the footprint box meets the clip box's x/z range (always, without one): {@link #reachesClip}. */
        boolean reaches(Box clip) {
            return clip == null || !(fx1 < clip.min().x() || fx0 > clip.max().x() || fz1 < clip.min().z() || fz0 > clip.max().z());
        }

        /** Reads the grid: the loaded chunks, then each column's surface. */
        void snapshot(WorldReader world, StateSpace states, int[] found) {
            cx0 = gx0 >> 4;
            cz0 = gz0 >> 4;
            int cw = ((gx0 + gw - 1) >> 4) - cx0 + 1;
            cd = ((gz0 + gd - 1) >> 4) - cz0 + 1;
            loaded = new boolean[cw * cd];
            for (int cx = 0; cx < cw; cx++) {
                for (int cz = 0; cz < cd; cz++) loaded[cx * cd + cz] = world.isLoaded(cx0 + cx, cz0 + cz);
            }
            height = new int[gw * gd];
            surface = new int[gw * gd];
            plants = new int[gw * gd];
            for (int x = gx0; x < gx0 + gw; x++) {
                for (int z = gz0; z < gz0 + gd; z++) {
                    int i = grid(x, z);
                    height[i] = NO_SURFACE;
                    if (!loadedAt(x, z)) continue;
                    int y = SurfaceScan.scan(world, states, x, z, yTop, yBottom, found);
                    if (y == SurfaceScan.NONE) continue;
                    height[i] = y;
                    surface[i] = found[0];
                    plants[i] = found[1];
                }
            }
            if (spec.tool() == BrushTool.PALETTE && Pattern.needsSteepness(spec.material())) {
                steepness = new ColumnSteepness(world, states, fx0, fx1, fz0, fz1, yTop, yBottom);
            }
        }

        /** The column's distance from the dab centre over the radius, or -1 outside the footprint. */
        double distance(int x, int z) {
            long dx = 16L * x + 8 - dab.x16(), dz = 16L * z + 8 - dab.z16();
            if (spec.shape() == Shape.CIRCLE) {
                long d2 = dx * dx + dz * dz;
                if (d2 > r16 * r16) return -1;
                return Math.sqrt((double) d2) / r16;
            }
            long d = Math.max(Math.abs(dx), Math.abs(dz));
            if (d > r16) return -1;
            return (double) d / r16;
        }

        /** The fixed-point weight at distance {@code t}. */
        int weight(double t) {
            return (int) (base * falloff(spec.falloff(), t) * ONE + 0.5);
        }

        boolean inGrid(int x, int z) {
            return x >= gx0 && x < gx0 + gw && z >= gz0 && z < gz0 + gd;
        }

        int grid(int x, int z) {
            return (x - gx0) * gd + (z - gz0);
        }

        /** The surface this area's scan found in column (x, z) of its grid, or {@code NO_SURFACE}. */
        int heightAt(int x, int z) {
            return height[grid(x, z)];
        }

        boolean loadedAt(int x, int z) {
            return loaded[((x >> 4) - cx0) * cd + ((z >> 4) - cz0)];
        }

        int slope(int x, int z, int h) {
            int step = 0;
            step = Math.max(step, stepTo(x, z - 1, h));
            step = Math.max(step, stepTo(x, z + 1, h));
            step = Math.max(step, stepTo(x - 1, z, h));
            return Math.max(step, stepTo(x + 1, z, h));
        }

        private int stepTo(int x, int z, int h) {
            int other = height[grid(x, z)];
            return other == NO_SURFACE ? 0 : Math.abs(other - h);
        }
    }

    /** One step: a snapshot of the columns its dabs read, then the cells it writes. */
    private final class StepPass {
        private final BrushSpec spec;
        private final StrokeState stroke;
        private final WorldReader world;
        private final StateSpace states;
        private final int worldTop;
        private final int worldBottom;
        private final Area[] areas;
        /** {@link SurfaceScan#scan} output: surface state and plant count. */
        private final int[] found = new int[2];
        /** {@link BrushSpec#flattenY()} clamped to the build height. */
        private final int flattenY;
        /** The most blocks one dab moves a column: {@code radius + SCAN_MARGIN}. */
        private final int maxMove;
        /** {@link BrushSpec#clip()}: the only cells written, or {@code null}. */
        private final Box clip;
        /** Planned writes, in emission order, and the fluid refill of removed cells. */
        private final CellPlan cells;

        StepPass(BrushSpec spec, Dab first, Dab[] dabs, StrokeState stroke, WorldReader world) {
            this.spec = spec;
            this.stroke = stroke;
            this.world = world;
            this.states = world.states();
            this.worldTop = world.topYExclusive() - 1;
            this.worldBottom = world.bottomY();
            this.clip = spec.clip();
            this.cells = new CellPlan(world, clip, new CellPlan.Layout() {
                @Override
                public long key(int x, int y, int z) {
                    return cellKey(x, y, z);
                }

                @Override
                public boolean loaded(int x, int z) {
                    return loadedAt(x, z);
                }
            });
            flattenY = Math.max(worldBottom, Math.min(worldTop, spec.flattenY()));
            maxMove = spec.radius() + SCAN_MARGIN;
            // Dabs whose footprint misses the clip box read nothing.
            Area[] reaching = new Area[dabs.length];
            int n = 0;
            for (Dab dab : dabs) {
                Area area = new Area(spec, dab, first, worldBottom, worldTop);
                if (area.reaches(clip)) reaching[n++] = area;
            }
            areas = Arrays.copyOf(reaching, n);
        }

        void run(CellSink out) {
            // Bound first, so a bad mask fails the same way whether or not this step reaches the clip box.
            ColumnFilter filter = stroke.filter(spec.mask(), states, world);
            if (areas.length == 0) return;
            for (Area area : areas) area.snapshot(world, states, found);
            decide(filter);
            cells.emit(out);
        }

        // ---- Decide ----

        /**
         * Visits each footprint column once, from the first dab (in step order) whose footprint holds it and whose own
         * scan found a surface there, with the largest weight among the dabs whose footprint holds it and whose scan
         * found that same surface. A dab whose window misses the column's surface (a copy standing much higher or
         * lower) neither shapes it nor adds its weight. When every dab of the step stands at one height, every scan of
         * a column finds the same surface or none, so this is the first footprint holding it with the largest weight.
         */
        private void decide(ColumnFilter filter) {
            for (int a = 0; a < areas.length; a++) {
                Area area = areas[a];
                for (int x = area.fx0; x <= area.fx1; x++) {
                    for (int z = area.fz0; z <= area.fz1; z++) {
                        double t = area.distance(x, z);
                        if (t < 0) continue;
                        int i = area.grid(x, z);
                        int h = area.height[i];
                        if (h == NO_SURFACE || decidedBefore(a, x, z) || !insideClip(x, z)) continue;
                        int k = area.weight(t);
                        for (int b = a + 1; b < areas.length; b++) {
                            double other = areas[b].distance(x, z);
                            if (other >= 0 && areas[b].heightAt(x, z) == h) k = Math.max(k, areas[b].weight(other));
                        }
                        if (k <= 0) continue;
                        if (!filter.test(x, h, z, area.surface[i], area.slope(x, z, h))) continue;
                        column(area, x, z, i, k);
                    }
                }
            }
        }

        /**
         * Whether an area before {@code a} has column (x, z) in its footprint and found a surface there (and so decided
         * it).
         */
        private boolean decidedBefore(int a, int x, int z) {
            for (int b = 0; b < a; b++) {
                if (areas[b].distance(x, z) >= 0 && areas[b].heightAt(x, z) != NO_SURFACE) return true;
            }
            return false;
        }

        private void column(Area area, int x, int z, int i, int k) {
            int h = area.height[i];
            int acc = stroke.accumulator(x, z);
            switch (tool) {
                case RAISE -> {
                    int sum = acc + k;
                    int move = sum / ONE;
                    stroke.setAccumulator(x, z, sum - move * ONE);
                    if (move > 0) raise(area, x, z, i, move);
                }
                case LOWER -> {
                    int sum = acc - k;
                    int move = sum / ONE;
                    stroke.setAccumulator(x, z, sum - move * ONE);
                    if (move < 0) lower(area, x, z, i, -move);
                }
                case FLATTEN -> relax(area, x, z, i, k, acc, (long) flattenY - h);
                case SMOOTH -> relax(area, x, z, i, k, acc, smoothTarget(area, x, z, h));
                case PAINT, PALETTE -> {
                    int sum = acc + k;
                    if (sum >= ONE) {
                        sum -= ONE;
                        paint(area, x, z, i);
                    }
                    stroke.setAccumulator(x, z, sum);
                }
                case SHAPE, WEATHER -> throw new IllegalStateException(tool + " has its own kernel");
            }
        }

        /**
         * Moves the column {@code k × target} toward {@code target} blocks away, never past it and at most
         * {@link #maxMove} blocks. A capped move drops its fraction; the next dab continues from the new height.
         */
        private void relax(Area area, int x, int z, int i, int k, int acc, long target) {
            if (target == 0) {
                stroke.setAccumulator(x, z, 0);
                return;
            }
            long sum = acc + (long) k * target;
            long move = sum / ONE;
            move = target > 0 ? Math.min(move, target) : Math.max(move, target);
            boolean capped = Math.abs(move) > maxMove;
            if (capped) move = Long.signum(move) * maxMove;
            stroke.setAccumulator(x, z, move == target || capped ? 0 : (int) (sum - move * ONE));
            if (move > 0) raise(area, x, z, i, (int) move);
            if (move < 0) lower(area, x, z, i, (int) -move);
        }

        /** The 3×3 mean surface height minus {@code h}, rounded to the nearest block, ties toward zero. */
        private long smoothTarget(Area area, int x, int z, int h) {
            long sum = 0;
            int n = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int other = area.height[area.grid(x + dx, z + dz)];
                    if (other == NO_SURFACE) continue;
                    sum += other - h;
                    n++;
                }
            }
            long magnitude = (2 * Math.abs(sum) + n - 1) / (2L * n);
            return Long.signum(sum) * magnitude;
        }

        // ---- Write ----

        private void raise(Area area, int x, int z, int i, int move) {
            if (move <= 0 || !plantsInsideClip(area, i)) return;
            int h = area.height[i];
            int top = h;
            long end = Math.min((long) h + move, worldTop);
            for (int y = h + 1; y <= end; y++) {
                int current = world.get(x, y, z);
                if (!open(states.flags(current))) break;
                plan(x, y, z, area.surface[i], current);
                top = y;
            }
            for (int y = top + 1; y <= h + area.plants[i]; y++) plan(x, y, z, FILL, world.get(x, y, z));
        }

        private void lower(Area area, int x, int z, int i, int move) {
            if (move <= 0 || !plantsInsideClip(area, i)) return;
            int h = area.height[i];
            int limit = (int) Math.max(worldBottom, (long) h - move + 1);
            int low = h + 1;
            while (low - 1 >= limit && !structure(states.flags(world.get(x, low - 1, z)))) low--;
            for (int y = low; y <= h + area.plants[i]; y++) plan(x, y, z, FILL, world.get(x, y, z));
        }

        private void paint(Area area, int x, int z, int i) {
            int h = area.height[i];
            int limit = (int) Math.max(worldBottom, (long) h - Math.max(1, spec.depth()) + 1);
            int low = h;
            while (low - 1 >= limit && ground(states.flags(world.get(x, low - 1, z)))) low--;
            Pattern material = spec.material();
            if (material instanceof Pattern.Arranged arranged) {
                // A laid-out mix: at the column's pre-image (a copy mirrors or turns it), on the column's steepness.
                int px = x, pz = z;
                if (area.image != Symmetry.Image.IDENTITY) {
                    Symmetry.Image inverse = area.image.inverse();
                    px = (int) spec.symmetry().cellX(inverse, x, z);
                    pz = (int) spec.symmetry().cellZ(inverse, x, z);
                }
                double degrees = area.steepness == null ? 0 : area.steepness.degrees(x, z);
                for (int y = low; y <= h; y++) {
                    plan(x, y, z, arranged.apply(px, y, pz, degrees), world.get(x, y, z));
                }
                return;
            }
            for (int y = low; y <= h; y++) {
                int current = world.get(x, y, z);
                plan(x, y, z, material.apply(states, x, y, z, current), current);
            }
        }

        private void plan(int x, int y, int z, int next, int current) {
            cells.plan(x, y, z, next, current);
        }

        // ---- Helpers ----

        /** Whether column (x, z) lies within the clip box's x/z range (always, without one). */
        private boolean insideClip(int x, int z) {
            return clip == null || (x >= clip.min().x() && x <= clip.max().x() && z >= clip.min().z() && z <= clip.max().z());
        }

        /**
         * Whether the plants standing on column {@code i} are all inside the clip box (always, without one or without
         * plants). A column moves only then: moving it removes its plants, and a plant cell the box keeps would be
         * left floating or cut in half.
         */
        private boolean plantsInsideClip(Area area, int i) {
            if (clip == null || area.plants[i] == 0) return true;
            int h = area.height[i];
            return (long) h + 1 >= clip.min().y() && (long) h + area.plants[i] <= clip.max().y();
        }

        /** Whether column (x, z)'s chunk is loaded, as the first grid holding it saw it (not loaded outside every grid). */
        private boolean loadedAt(int x, int z) {
            for (Area area : areas) {
                if (area.inGrid(x, z)) return area.loadedAt(x, z);
            }
            return false;
        }

        /**
         * A key for cells of this step's grids: y in the high bits, then the first grid holding the column, then
         * grid-relative x and z. Cells outside every grid get a key nothing is planned under.
         */
        private long cellKey(int x, int y, int z) {
            for (int a = 0; a < areas.length; a++) {
                Area area = areas[a];
                if (area.inGrid(x, z)) {
                    return ((long) y << 34) | ((long) a << 32) | ((long) ((x - area.gx0 + 1) & 0xFFFF) << 16)
                            | ((z - area.gz0 + 1) & 0xFFFF);
                }
            }
            return Long.MIN_VALUE;
        }
    }

    private static boolean ground(int flags) {
        return SurfaceScan.ground(flags);
    }

    private static boolean open(int flags) {
        return SurfaceScan.open(flags);
    }

    private static boolean structure(int flags) {
        return SurfaceScan.structure(flags);
    }
}
