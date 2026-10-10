package dev.sculptory.core.brush;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The Heightmap brush ({@link BrushTool#HEIGHTMAP}, {@link HeightmapSpec}): it stamps a height image on the terrain, as
 * seen from above (the Terrain mode; there is no Surface mode in v1).
 *
 * <p><b>Footprint.</b> The image covers a square of {@code 2 × radius + 1} columns: pixel {@code (u, v)} lies over column
 * {@code (dabX - radius + u, dabZ - radius + v)}. A symmetric copy stamps the image of that square under its symmetry
 * image ({@link Symmetry#imageOf} from the step's first dab, {@link Symmetry#cellX}), so a mirrored copy stamps the
 * mirrored picture; it may lie one column off the copy's own block, as a mirror between blocks puts it. A dab on a mirror
 * plane or the rotation centre is its own image (its coincident copies are left out of the step): it stamps every image
 * that maps onto it at once, each column taking the highest of them, so the result is symmetric there too.
 *
 * <p><b>Targets.</b> Each column is found as the other brushes find it ({@link SurfaceScan}): scanning down through open
 * cells to the first ground cell, in a window of {@code radius + }{@value TerrainKernel#SCAN_MARGIN} around the dab's
 * height, widened by the stamp's height below the dab when raising (a dab on a ridge the stroke raised still finds the
 * ground beside it) and above it when lowering. The first time a stroke meets a column it notes the column's surface,
 * its <i>base</i> ({@link StrokeState}). The column's target is the base plus (or with {@code lower}, minus)
 * {@link #lift}{@code (value, heightBlocks)}: {@code value / 255 × heightBlocks} blocks, rounded to the nearest. The
 * stroke keeps the furthest target any of its dabs asked of the column (the highest when raising, the lowest when
 * lowering) and moves the column there; a dab asking less does nothing. So a drag lays a ridge (or a trench) the height
 * of the image along its path rather than piling stamp on stamp, and a dab repeated in place changes nothing.
 *
 * <p><b>Writes.</b> A column moves at most {@code heightBlocks} blocks in one dab.
 * <ul>
 *   <li><b>Raised</b> into open cells, stopping below the first other cell: its own layers move up. The top block (the
 *       surface's state) lands on the new surface and the cells beneath it, the old surface included, take the block
 *       that lay under the surface (the top block itself when that is not ground): grass over dirt rises as a taller
 *       dirt column under the grass.</li>
 *   <li><b>Lowered</b> by removing cells from the top down, stopping at a structure; the ground the cut stops on takes the
 *       column's top block. Removed cells refill with fluid as the other brushes' do ({@link CellPlan}), so a cut below
 *       a lake's surface fills with water.</li>
 *   <li>The plants standing on a moved column (up to {@value TerrainKernel#MAX_PLANT_RUN}) are removed, never left
 *       floating; a column whose plants reach outside the clip box doesn't move.</li>
 * </ul>
 *
 * <p><b>Masks and clip.</b> The brush's mask ({@link SurfaceMask}) is judged once per column and stroke, when the stroke
 * first meets it: at its surface before the stroke, with the slope to its neighbours' surfaces before the stroke (as the
 * stroke noted them, else as this dab finds them). A column it turns down is left alone for the whole stroke, so a
 * slope or height mask doesn't cut a ridge off half way. The clip box drops every write outside it, as the other brushes'
 * ({@link TerrainKernel}). The global mask wraps the kernel per cell ({@code MaskedKernel}).
 *
 * <p><b>Steps.</b> Every dab of a step reads the world as it was before the step; a column inside several dabs'
 * footprints is decided once, by the first dab (in {@link TerrainKernel#sorted} order) whose scan found its surface,
 * with the largest value among the dabs whose scans found that same surface. Strength, pressure, falloff and shape are
 * not used: the image is the brush.
 *
 * <p><b>Reach.</b> Reads and writes stay within {@code radius + 2} of the dab's block across (the footprint, one column
 * off for a copy, a ring for the slope and the fluid refill) and within {@code radius + 8 + heightBlocks + 3} above and
 * {@code radius + 8 + heightBlocks + 1} below its height. All reads happen before the first write; integer arithmetic
 * only, nothing depends on hash order, so the client's prediction and the server agree.
 */
final class HeightmapKernel implements BrushKernel {
    private static final int NO_SURFACE = SurfaceScan.NONE;
    private static final int FILL = CellPlan.FILL;
    /** A column's goal once the brush's mask has turned it down for the stroke. */
    private static final int REJECTED = Integer.MIN_VALUE;

    @Override
    public void applyStep(BrushSpec s, List<Dab> dabs, StrokeState st, WorldReader w, CellSink out) {
        Objects.requireNonNull(out);
        Pass pass = start(s, dabs, st, w);
        pass.advance(Long.MAX_VALUE);
        pass.cells().emit(out);
    }

    /**
     * Starts working out a step a piece at a time ({@link PlannedStep#planner}): {@link Pass#advance} until it is done,
     * then its {@link Pass#cells()} hold what {@link #applyStep} would write, however the work was cut into pieces, as long
     * as the world did not change in between. The arguments are checked here, and the brush's mask bound.
     */
    static Pass start(BrushSpec s, List<Dab> dabs, StrokeState st, WorldReader w) {
        Objects.requireNonNull(s);
        Objects.requireNonNull(dabs);
        Objects.requireNonNull(st);
        Objects.requireNonNull(w);
        if (s.tool() != BrushTool.HEIGHTMAP) {
            throw new IllegalArgumentException("The Heightmap kernel cannot apply a " + s.tool() + " brush");
        }
        if (dabs.isEmpty() || dabs.size() > Symmetry.MAX_COPIES) {
            throw new IllegalArgumentException("A step holds 1-" + Symmetry.MAX_COPIES + " dabs, not " + dabs.size());
        }
        for (Dab d : dabs) {
            Objects.requireNonNull(d);
            if (!TerrainKernel.insideLimit(d)) throw new IllegalArgumentException("Dab outside the world: " + d);
        }
        return new Pass(s, dabs.get(0), TerrainKernel.sorted(dabs), st, w);
    }

    /** The blocks a pixel of {@code value} (0-255) lifts or cuts a column under a stamp {@code heightBlocks} tall. */
    static int lift(int value, int heightBlocks) {
        return (value * heightBlocks + 127) / 255;
    }

    /** One dab of a step: its footprint and where the image lies on it, and the snapshot of the columns it reads. */
    private static final class Area {
        private final Symmetry symmetry;
        /**
         * Per placement of the picture on this dab: the image that maps the dab's columns back onto the first dab's
         * footprint, where the picture is read ({@code null}: read over the footprint as it lies). A dab on a mirror plane
         * or the rotation centre is the image of the first dab under several images at once (its coincident copies are
         * left out of the step), and takes every one of them.
         */
        private final Symmetry.Image[] inverses;
        /** Per placement: its footprint, {x0, x1, z0, z1}. */
        private final int[][] boxes;
        /** The column pixel (0, 0) lies over: the first dab's corner (the dab's own for a dab that is not its image). */
        private final int ux0, uz0;
        /** The footprint's bounds (the placements' union), in columns. */
        private final int fx0, fx1, fz0, fz1;
        /** The snapshot grid: the footprint plus a ring of one column. */
        private final int gx0, gz0, gw, gd;
        /** The scan window. */
        private final int yTop, yBottom;
        private int[] height;
        private int[] surface;
        private int[] plants;
        private int cx0, cz0, cd;
        private boolean[] loaded;

        Area(BrushSpec spec, Dab dab, Dab first, int worldBottom, int worldTop) {
            int r = spec.radius();
            symmetry = spec.symmetry();
            List<Symmetry.Image> images = new ArrayList<>();
            for (Symmetry.Image image : symmetry.images()) {
                if (symmetry.imageX(image, first.x16(), first.z16()) == dab.x16()
                        && symmetry.imageZ(image, first.x16(), first.z16()) == dab.z16()) {
                    images.add(image);
                }
            }
            Dab origin = images.isEmpty() ? dab : first;
            if (images.isEmpty()) images.add(Symmetry.Image.IDENTITY);
            ux0 = origin.blockX() - r;
            uz0 = origin.blockZ() - r;
            Box own = new Box(new BlockPos(ux0, 0, uz0), new BlockPos(ux0 + 2 * r, 0, uz0 + 2 * r));
            inverses = new Symmetry.Image[images.size()];
            boxes = new int[images.size()][];
            int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
            for (int i = 0; i < inverses.length; i++) {
                Symmetry.Image image = images.get(i);
                Box mapped = own;
                if (image != Symmetry.Image.IDENTITY) {
                    inverses[i] = image.inverse();
                    mapped = symmetry.imageBox(image, own);
                }
                boxes[i] = new int[] {mapped.min().x(), mapped.max().x(), mapped.min().z(), mapped.max().z()};
                x0 = Math.min(x0, boxes[i][0]);
                x1 = Math.max(x1, boxes[i][1]);
                z0 = Math.min(z0, boxes[i][2]);
                z1 = Math.max(z1, boxes[i][3]);
            }
            fx0 = x0;
            fx1 = x1;
            fz0 = z0;
            fz1 = z1;
            gx0 = fx0 - 1;
            gz0 = fz0 - 1;
            gw = fx1 - fx0 + 3;
            gd = fz1 - fz0 + 3;
            HeightmapSpec stamp = spec.heightmap();
            long reach = (long) r + TerrainKernel.SCAN_MARGIN;
            long top = dab.blockY() + reach + (stamp.lower() ? stamp.heightBlocks() : 0);
            long bottom = dab.blockY() - reach - (stamp.lower() ? 0 : stamp.heightBlocks());
            yTop = (int) Math.max(worldBottom, Math.min(worldTop, top));
            yBottom = (int) Math.max(worldBottom, Math.min(worldTop, bottom));
        }

        /** Whether the footprint meets the clip box's x/z range (always, without one). */
        boolean reaches(Box clip) {
            return clip == null || !(fx1 < clip.min().x() || fx0 > clip.max().x() || fz1 < clip.min().z()
                    || fz0 > clip.max().z());
        }

        /** Readies the grid: which chunks are loaded, and room for each column's surface ({@link #snapshotRow}). */
        void prepare(WorldReader world) {
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
        }

        /** Reads the surface of each column of grid row {@code x} (after {@link #prepare}). */
        void snapshotRow(WorldReader world, StateSpace states, int[] found, int x) {
            for (int z = gz0; z < gz0 + gd; z++) {
                int i = grid(x, z);
                height[i] = NO_SURFACE;
                if (!loadedAt(x, z)) continue;
                int y = SurfaceScan.scan(world, states, x, z, yTop, yBottom, found);
                if (y == NO_SURFACE) continue;
                height[i] = y;
                surface[i] = found[0];
                plants[i] = found[1];
            }
        }

        /** Whether column (x, z) lies under one of the picture's placements. */
        boolean inFootprint(int x, int z) {
            for (int[] box : boxes) {
                if (x >= box[0] && x <= box[1] && z >= box[2] && z <= box[3]) return true;
            }
            return false;
        }

        boolean inGrid(int x, int z) {
            return x >= gx0 && x < gx0 + gw && z >= gz0 && z < gz0 + gd;
        }

        int grid(int x, int z) {
            return (x - gx0) * gd + (z - gz0);
        }

        /** The surface this dab's scan found in column (x, z) of its grid, or {@code NO_SURFACE}. */
        int heightAt(int x, int z) {
            return height[grid(x, z)];
        }

        boolean loadedAt(int x, int z) {
            return loaded[((x >> 4) - cx0) * cd + ((z >> 4) - cz0)];
        }

        /** The picture's value (0-255) over footprint column (x, z): the highest of the placements covering it. */
        int value(HeightmapSpec stamp, byte[] heights, int x, int z) {
            int value = 0;
            for (int i = 0; i < boxes.length; i++) {
                int[] box = boxes[i];
                if (x < box[0] || x > box[1] || z < box[2] || z > box[3]) continue;
                int u, v;
                if (inverses[i] == null) {
                    u = x - ux0;
                    v = z - uz0;
                } else {
                    u = (int) (symmetry.cellX(inverses[i], x, z) - ux0);
                    v = (int) (symmetry.cellZ(inverses[i], x, z) - uz0);
                }
                value = Math.max(value, heights[v * stamp.side() + u] & 0xFF);
            }
            return value;
        }
    }

    /**
     * One step: a snapshot of the columns its dabs read, then the cells it writes, worked out a piece at a time
     * ({@link #advance}): the grids' rows are read, then the footprints' columns decided, in the order a whole run takes.
     */
    static final class Pass {
        /** Columns read or decided between looks at the clock. */
        private static final int CHECK_EVERY = 64;

        private final BrushSpec spec;
        private final HeightmapSpec stamp;
        private final byte[] heights;
        private final StrokeState stroke;
        private final WorldReader world;
        private final StateSpace states;
        private final int worldTop;
        private final int worldBottom;
        private final Box clip;
        private final Area[] areas;
        private final CellPlan cells;
        /** The brush's mask, bound when the step starts (so a bad mask fails whether or not it reaches the clip box). */
        private final ColumnFilter filter;
        /** {@link SurfaceScan#scan} output: surface state and plant count. */
        private final int[] found = new int[2];
        /** Where the work is: reading the grids (or deciding the footprints), in area {@link #area}, row {@link #row}. */
        private boolean reading = true;
        private int area;
        private int row;
        private int column;
        private boolean prepared;
        private boolean done;

        Pass(BrushSpec spec, Dab first, Dab[] dabs, StrokeState stroke, WorldReader world) {
            this.spec = spec;
            this.stamp = spec.heightmap();
            this.heights = stamp.heights();
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
            cells.limit(HeightmapSpec.MAX_STEP_CELLS);
            // Dabs whose footprint misses the clip box read nothing.
            Area[] reaching = new Area[dabs.length];
            int n = 0;
            for (Dab dab : dabs) {
                Area area = new Area(spec, dab, first, worldBottom, worldTop);
                if (area.reaches(clip)) reaching[n++] = area;
            }
            areas = Arrays.copyOf(reaching, n);
            filter = stroke.filter(spec.mask(), states, world);
            done = areas.length == 0;
        }

        /** Refuses ({@link StepTooLargeException}) a step of more than {@code maxCells} cells, at most the brush's cap. */
        void limit(long maxCells) {
            cells.limit(Math.min(HeightmapSpec.MAX_STEP_CELLS, Math.max(0, maxCells)));
        }

        /** The cells planned: the whole step's once {@link #advance} is done. */
        CellPlan cells() {
            return cells;
        }

        /**
         * Works on the step until {@code deadline} ({@code System.nanoTime}), at least a few dozen columns; returns whether
         * every column is decided.
         *
         * @throws StepTooLargeException when the step plans more than {@link HeightmapSpec#MAX_STEP_CELLS} cells
         */
        boolean advance(long deadline) {
            int work = 0;
            while (!done) {
                if (work >= CHECK_EVERY) {
                    if (System.nanoTime() >= deadline) return false;
                    work = 0;
                }
                if (reading) {
                    Area a = areas[area];
                    if (!prepared) {
                        a.prepare(world);
                        prepared = true;
                        row = a.gx0;
                    }
                    a.snapshotRow(world, states, found, row);
                    work += a.gd;
                    if (++row < a.gx0 + a.gw) continue;
                    prepared = false;
                    if (++area < areas.length) continue;
                    reading = false;
                    area = 0;
                    row = areas[0].fx0;
                    column = areas[0].fz0;
                    continue;
                }
                Area a = areas[area];
                int x = row;
                int z = column;
                if (a.inFootprint(x, z)) {
                    int i = a.grid(x, z);
                    int h = a.height[i];
                    if (h != NO_SURFACE && !decidedBefore(area, x, z) && insideClip(x, z)) {
                        int value = a.value(stamp, heights, x, z);
                        for (int b = area + 1; b < areas.length; b++) {
                            if (areas[b].inFootprint(x, z) && areas[b].heightAt(x, z) == h) {
                                value = Math.max(value, areas[b].value(stamp, heights, x, z));
                            }
                        }
                        column(a, x, z, i, value, filter);
                    }
                }
                work++;
                if (++column <= a.fz1) continue;
                column = a.fz0;
                if (++row <= a.fx1) continue;
                if (++area < areas.length) {
                    row = areas[area].fx0;
                    column = areas[area].fz0;
                    continue;
                }
                done = true;
            }
            return true;
        }

        /** Whether a dab before {@code a} holds column (x, z) in its footprint and found its surface (and so decided it). */
        private boolean decidedBefore(int a, int x, int z) {
            for (int b = 0; b < a; b++) {
                if (areas[b].inFootprint(x, z) && areas[b].heightAt(x, z) != NO_SURFACE) return true;
            }
            return false;
        }

        /** Moves column (x, z), found at its grid index {@code i}, toward the furthest target the stroke asked of it. */
        private void column(Area area, int x, int z, int i, int value, ColumnFilter filter) {
            int h = area.height[i];
            long record = stroke.heightmapColumn(x, z);
            int base;
            int goal;
            if (record == StrokeState.NO_HEIGHTMAP_COLUMN) {
                // The stroke meets the column: its surface now is its surface before the stroke, judged once by the mask.
                base = h;
                goal = filter.test(x, h, z, area.surface[i], slope(area, x, z, h)) ? h : REJECTED;
                if (goal == REJECTED) {
                    stroke.setHeightmapColumn(x, z, pack(base, REJECTED));
                    return;
                }
            } else {
                base = (int) (record >> 32);
                goal = (int) record;
                if (goal == REJECTED) return;
            }
            int lift = lift(value, stamp.heightBlocks());
            long target = stamp.lower() ? (long) base - lift : (long) base + lift;
            target = Math.max(worldBottom, Math.min(worldTop, target));
            int next = (int) (stamp.lower() ? Math.min(goal, target) : Math.max(goal, target));
            stroke.setHeightmapColumn(x, z, pack(base, next));
            if (!stamp.lower() && next > h) raise(area, x, z, i, Math.min(next - h, stamp.heightBlocks()));
            if (stamp.lower() && next < h) lower(area, x, z, i, Math.min(h - next, stamp.heightBlocks()));
        }

        /**
         * The column's largest height step to a cardinal neighbour, before the stroke: a neighbour's surface as the stroke
         * first found it, else as this dab finds it (neighbours without a surface don't count).
         */
        private int slope(Area area, int x, int z, int h) {
            int step = 0;
            step = Math.max(step, stepTo(area, x, z - 1, h));
            step = Math.max(step, stepTo(area, x, z + 1, h));
            step = Math.max(step, stepTo(area, x - 1, z, h));
            return Math.max(step, stepTo(area, x + 1, z, h));
        }

        private int stepTo(Area area, int x, int z, int h) {
            long record = stroke.heightmapColumn(x, z);
            int other = record != StrokeState.NO_HEIGHTMAP_COLUMN ? (int) (record >> 32) : area.heightAt(x, z);
            return other == NO_SURFACE ? 0 : Math.abs(other - h);
        }

        /**
         * Raises the column {@code move} blocks into open cells: the top block on the new surface, the block that lay
         * under the surface (or the top block itself) beneath it down to the old surface; plants left above go.
         */
        private void raise(Area area, int x, int z, int i, int move) {
            if (!plantsInsideClip(area, i)) return;
            int h = area.height[i];
            int top = area.surface[i];
            int fill = top;
            if (h - 1 >= worldBottom) {
                int below = world.get(x, h - 1, z);
                if (SurfaceScan.ground(states.flags(below))) fill = below;
            }
            int end = h;
            long limit = Math.min((long) h + move, worldTop);
            for (int y = h + 1; y <= limit; y++) {
                if (!SurfaceScan.open(states.flags(world.get(x, y, z)))) break;
                end = y;
            }
            if (end == h) return;
            cells.plan(x, h, z, fill, top);
            for (int y = h + 1; y < end; y++) cells.plan(x, y, z, fill, world.get(x, y, z));
            cells.plan(x, end, z, top, world.get(x, end, z));
            for (int y = end + 1; y <= h + area.plants[i]; y++) cells.plan(x, y, z, FILL, world.get(x, y, z));
        }

        /**
         * Lowers the column {@code move} blocks, removing cells from the top down and stopping at a structure; the ground
         * the cut stops on takes the column's top block. The removed cells and the plants refill with fluid or air.
         */
        private void lower(Area area, int x, int z, int i, int move) {
            if (!plantsInsideClip(area, i)) return;
            int h = area.height[i];
            int limit = (int) Math.max(worldBottom, (long) h - move + 1);
            int low = h + 1;
            while (low - 1 >= limit && !SurfaceScan.structure(states.flags(world.get(x, low - 1, z)))) low--;
            if (low > h) return;
            if (low - 1 >= worldBottom) {
                int floor = world.get(x, low - 1, z);
                if (SurfaceScan.ground(states.flags(floor))) cells.plan(x, low - 1, z, area.surface[i], floor);
            }
            for (int y = low; y <= h + area.plants[i]; y++) cells.plan(x, y, z, FILL, world.get(x, y, z));
        }

        /** Whether column (x, z) lies within the clip box's x/z range (always, without one). */
        private boolean insideClip(int x, int z) {
            return clip == null || (x >= clip.min().x() && x <= clip.max().x() && z >= clip.min().z() && z <= clip.max().z());
        }

        /** Whether the plants standing on column {@code i} are all inside the clip box (always, without one or plants). */
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

        private static long pack(int base, int goal) {
            return ((long) base << 32) | (goal & 0xFFFFFFFFL);
        }
    }
}
