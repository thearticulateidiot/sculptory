package dev.sculptory.core.brush;

import dev.sculptory.core.Box;
import dev.sculptory.core.SplitMix64;
import dev.sculptory.core.mask.MaskedKernel;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The Weather brush ({@link BrushTool#WEATHER}, {@link WeatherSpec}): it works on the
 * cells at the surface, one rule per mode, each cell deciding from its six face neighbours in the world as it was
 * before the step.
 *
 * <ul>
 *   <li><b>Erode</b> (as WorldEdit's and FAWE's erode): a ground cell with at least {@value #ERODE_FACES} open faces
 *       on the dab's side (air, water, plants; above the build height too) is removed; open cells beyond, such as a cave
 *       behind a thin wall, don't count, so it never breaks through into one. The more open faces, the faster: an edge
 *       (2) wears at half the rate, a corner, ridge or overhang lip (3) at the full rate, a spike or fin (4 or more) at
 *       one and a half ({@link #rate}). Removed cells refill with fluid as the other brushes' do ({@link CellPlan}).</li>
 *   <li><b>Fill in</b> (dilate): an open cell with at least {@value #FILL_FACES} solid faces (ground, structures, below
 *       the world) takes the ground block most common around it (faces count 3, edges 2, corners 1). A crack's floor
 *       (3) fills at half the rate, a pit (4) at the full rate, a hole (5 or 6) at one and a half.</li>
 *   <li><b>Roughen</b>: a seeded 3D value noise (lattice {@link #roughenScale} blocks, the stroke's seed) breaks a
 *       smooth surface up: exposed ground cells where the noise is below {@value #ROUGHEN_LOW}/65536 are removed, open
 *       cells touching ground where it is above {@value #ROUGHEN_HIGH}/65536 take the most common ground block around
 *       them. The noise is fixed for the stroke, and each cell changes at most once a stroke and at most
 *       {@link #roughenDepth} cells below or above the surface the stroke began on (a carve reached only through cells
 *       the stroke carved lies one deeper than the shallowest of them, a build likewise; each changed cell keeps its
 *       depth as a negative accumulator, carves and builds apart), so a held Roughen carves and builds its lumps and
 *       stops; the next stroke has a new seed.</li>
 *   <li><b>Melt</b>: a gravity-like slump. A ground cell with an open cell under it falls straight down to the ground
 *       below; else one with open above slides off a drop steeper than 45° (the cell beside it and the one under that
 *       open) to the ground at its foot, down the steepest such side. The block moves (it is removed where it was and
 *       written where it lands): Melt never makes or loses a block.</li>
 * </ul>
 *
 * <p><b>Where it works.</b> In the Surface mode ({@link SculptMode#SURFACE}) inside the dab's ball, on the dab's side
 * only, as Smooth: open cells joined to those around the dab and the ground cells touching them
 * ({@link SurfaceKernel#joinSide}); a cave behind a wall is left alone. In the Terrain mode ({@link SculptMode#TERRAIN},
 * "from above") inside the footprint columns and the Terrain mode's scan window ({@code radius + 8} above and below the
 * dab), on the sky side only: the open cells above each column's surface ({@link SurfaceScan}) and the ground cells
 * touching them (a column's top, a cliff's face above the lower column beside it). The underside of an overhang and
 * caves are left alone. Every read cell outside that region, and every cell outside the clip box, stays as it is.
 *
 * <p><b>Strength</b> is the rate. Each dab adds {@code strength × pressure/255 × falloff(t)} (t: the cell's distance
 * from the dab's point over the radius, in 3D in the Surface mode, across in the Terrain mode) times the mode's rate to
 * a per-cell accumulator of every cell whose rule says it should change, and the cell changes once that reaches one
 * block. A cell whose rule says it should stay is reset. So at full strength and pressure a centre cell of Roughen or
 * Melt changes once per dab, and one iteration of every rule runs about every {@code 1 / strength} dabs (the default
 * 0.6: three every five dabs); the brush flows, a dab every 150 ms while held still.
 *
 * <p><b>Rules everywhere.</b> Structures (stairs, fences, block entities) never change, and a ground cell touching one on
 * any face is never removed or moved. A cell next to an unloaded chunk does not change, and Melt lands nothing there. The brush mask sees each changed
 * cell with the block it loses or gains (Melt: both ends of a move, with the moving block), its y, and its slope (the
 * Surface mode: {@link SurfaceNormal#slope} within two blocks; the Terrain mode: the column's largest height step to a
 * neighbour). A change whose plants reach outside the clip box is not made; the plants standing on a removed or filled
 * cell (up to {@value TerrainKernel#MAX_PLANT_RUN}) and the lower half of a two-block plant whose upper half filled are
 * cleared. Materials only ever come from the neighbouring cells. Under the global mask a Melt move is judged whole
 * ({@link MaskedKernel.Grouped}): both of its ends must pass, or the block stays where it is.
 *
 * <p><b>Steps and symmetry.</b> Every dab of a step reads the world as it was before the step. A cell inside several
 * dabs' regions is decided once, by the first dab in step order ({@link TerrainKernel#sorted}), with the largest weight
 * among them. Each dab visits its cells, breaks the fill vote's ties, picks among Melt's equal drops and reads the
 * Roughen noise in the frame of its symmetry image ({@link Symmetry#imageOf} from the step's first dab), so a mirrored or
 * turned copy does the mirrored or turned thing wherever the copies do not overlap. A Melt move waits for the next dab
 * when a move planned before it in the step lands on its landing cell, takes the block under that cell, or lands on its
 * block, so nothing is left hanging.
 *
 * <p><b>Reads</b> stay within {@code radius + 2} of the dab's block across (the snapshot of {@link SurfaceKernel.Ball}):
 * the Surface mode its ball's box, the Terrain mode the footprint and a ring and the scan window, two more cells above
 * it (plants) and one below. All reads happen before the first write ({@link CellPlan}); integer arithmetic except the
 * falloff (as the other brushes), nothing depends on hash order, so the client's prediction and the server agree.
 */
final class WeatherKernel implements MaskedKernel.Grouped {
    /** Open faces (of six) that make a ground cell erode. */
    static final int ERODE_FACES = 2;
    /** Solid faces (of six) that make an open cell fill. */
    static final int FILL_FACES = 3;
    /** The noise's full scale ({@link #noise} returns 0 to this, exclusive). */
    static final int NOISE_ONE = 1 << 16;
    /** Roughen removes exposed ground where the noise is below this. */
    static final int ROUGHEN_LOW = 22_000;
    /** Roughen fills open cells beside ground where the noise is above this. */
    static final int ROUGHEN_HIGH = NOISE_ONE - ROUGHEN_LOW;

    private static final int ONE = StrokeState.ONE;
    private static final int FILL = CellPlan.FILL;
    private static final int NONE = SurfaceScan.NONE;
    private static final byte OPEN = SurfaceKernel.OPEN;
    private static final byte ABOVE = SurfaceKernel.ABOVE;
    private static final byte GROUND = SurfaceKernel.GROUND;
    private static final byte STRUCTURE = SurfaceKernel.STRUCTURE;
    private static final byte BELOW = SurfaceKernel.BELOW;
    /** Roughen's markers: a carved cell keeps minus its depth (-1 to -4), a built one minus its depth and this. */
    private static final int BUILD_MARK = 8;
    /** The seed's twist for the Roughen noise, so it differs from the stroke's other uses of the seed. */
    private static final long NOISE_SALT = 0x5745415448455231L;

    @Override
    public void applyStep(BrushSpec s, List<Dab> dabs, StrokeState st, WorldReader w, CellSink out) {
        applyStep(s, dabs, st, w, out, null);
    }

    @Override
    public void applyStep(BrushSpec s, List<Dab> dabs, StrokeState st, WorldReader w, CellSink out,
                          MaskedKernel.Judge mask) {
        Objects.requireNonNull(s);
        Objects.requireNonNull(dabs);
        Objects.requireNonNull(st);
        Objects.requireNonNull(w);
        Objects.requireNonNull(out);
        if (s.tool() != BrushTool.WEATHER) throw new IllegalArgumentException("The Weather kernel cannot apply a " + s.tool() + " brush");
        if (dabs.isEmpty() || dabs.size() > Symmetry.MAX_COPIES) {
            throw new IllegalArgumentException("A step holds 1-" + Symmetry.MAX_COPIES + " dabs, not " + dabs.size());
        }
        for (Dab d : dabs) {
            Objects.requireNonNull(d);
            if (!TerrainKernel.insideLimit(d)) throw new IllegalArgumentException("Dab outside the world: " + d);
        }
        st.checkMaterial(s.material(), w.states());
        Pass pass = new Pass(s, dabs.get(0), TerrainKernel.sorted(dabs), st, w);
        pass.editMask = mask;
        pass.run(out);
    }

    /**
     * The rate, in halves of the dab's weight, at which a cell with {@code count} of the faces a rule counts changes
     * under a rule needing {@code threshold}: 0 below it, then 1, 2 and at most 3.
     */
    static int rate(int count, int threshold) {
        return count < threshold ? 0 : Math.min(count - threshold + 1, 3);
    }

    /** How deep Roughen digs and builds in one stroke with a noise lattice of {@code scale}: 1 to 4 cells. */
    static int roughenDepth(int scale) {
        return Math.max(1, (scale + 1) / 2);
    }

    /** Roughen's noise lattice for a brush of {@code radius}: bigger brushes make bigger lumps, 2 to 8 blocks. */
    static int roughenScale(int radius) {
        return Math.max(2, Math.min(8, (radius + 2) / 4));
    }

    /**
     * Roughen's noise at cell (x, y, z): 3D value noise, a {@link SplitMix64} value per lattice point {@code scale} blocks
     * apart, blended trilinearly in integers; 0 to {@link #NOISE_ONE}, exclusive.
     */
    static int noise(int x, int y, int z, int scale, long seed) {
        int ix = Math.floorDiv(x, scale), iy = Math.floorDiv(y, scale), iz = Math.floorDiv(z, scale);
        int[] corners = new int[8];
        for (int corner = 0; corner < 8; corner++) {
            corners[corner] = latticeValue(seed, ix + (corner & 1), iy + ((corner >> 1) & 1), iz + (corner >> 2));
        }
        return blend(x - ix * scale, y - iy * scale, z - iz * scale, scale, corners);
    }

    /**
     * The noise inside one lattice cell at offset (fx, fy, fz) from its low corner: {@code corners} are its eight lattice
     * values, corner {@code c} at (c & 1, c >> 1 & 1, c >> 2).
     */
    private static int blend(int fx, int fy, int fz, int scale, int[] corners) {
        long sum = 0;
        for (int corner = 0; corner < 8; corner++) {
            int cx = corner & 1, cy = (corner >> 1) & 1, cz = corner >> 2;
            long weight = (long) (cx == 1 ? fx : scale - fx) * (cy == 1 ? fy : scale - fy) * (cz == 1 ? fz : scale - fz);
            sum += weight * corners[corner];
        }
        return (int) (sum / ((long) scale * scale * scale));
    }

    /** The noise's value at lattice point (ix, iy, iz): 0 to 65535. */
    static int latticeValue(long seed, int ix, int iy, int iz) {
        return (int) (SplitMix64.hash(seed ^ NOISE_SALT, ix, iy, iz) >>> 48);
    }

    // ------------------------------------------------------------------ one dab

    /**
     * One dab of a step: its snapshot ({@link SurfaceKernel.Ball}: the ball's box in the Surface mode, the line tools'
     * taller box in the Terrain mode), its region, and its symmetry frame.
     */
    private static final class Grid {
        final SurfaceKernel.Ball ball;
        final boolean surface;
        final Symmetry symmetry;
        /** The image that maps the step's first dab onto this one, and back. */
        final Symmetry.Image image, inverse;
        // The Terrain mode: the footprint, the heights of it and a ring around it, and the scan window.
        int fx0, fx1, fz0, fz1;
        int gx0, gz0, gw, gd;
        int yTop, yBottom;
        int[] height;
        /** The 26 neighbours in this dab's frame (the fill vote's order): index steps and scores. */
        final int[] voteStep = new int[26], voteScore = new int[26];
        /** The four sides in this dab's frame (Melt's order among equal drops): north, south, west, east. */
        final int[] sideX = new int[4], sideZ = new int[4];

        Grid(BrushSpec spec, Dab dab, Dab first, WorldReader world, SurfaceKernel.Scratch scratch, int slot) {
            this.surface = spec.surface();
            this.ball = new SurfaceKernel.Ball(spec, dab, world, scratch, slot, !surface);
            // The Terrain mode's columns are lines along y: the ball's lateral distance is then the column's.
            if (!surface) ball.axis = Facing.UP;
            this.symmetry = spec.symmetry();
            this.image = symmetry.imageOf(first, dab);
            this.inverse = image.inverse();
            int n = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int away = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                        if (away == 0) continue;
                        int wx = (int) image.x(dx, dz), wz = (int) image.z(dx, dz);
                        voteStep[n] = wx * ball.strideX + dy + wz * ball.strideZ;
                        voteScore[n] = 4 - away;
                        n++;
                    }
                }
            }
            int[][] sides = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
            for (int i = 0; i < 4; i++) {
                sideX[i] = (int) image.x(sides[i][0], sides[i][1]);
                sideZ[i] = (int) image.z(sides[i][0], sides[i][1]);
            }
        }

        /** Whether the dab may write anything inside the clip box (always without one). */
        boolean reaches(BrushSpec spec, Box clip) {
            return surface ? ball.reaches(clip) : TerrainKernel.reachesClip(spec, ball.dab);
        }

        /** Reads what the region needs: the Surface mode its ball's cells and side, the Terrain mode the heights. */
        void snapshot(WorldReader world, StateSpace states) {
            if (surface) {
                ball.readAround(ball.r16 + 32);
                SurfaceKernel.joinSide(ball);
                return;
            }
            int r16 = (int) ball.r16;
            Dab dab = ball.dab;
            fx0 = TerrainKernel.footprintLow(dab.x16(), r16);
            fx1 = TerrainKernel.footprintHigh(dab.x16(), r16);
            fz0 = TerrainKernel.footprintLow(dab.z16(), r16);
            fz1 = TerrainKernel.footprintHigh(dab.z16(), r16);
            gx0 = fx0 - 1;
            gz0 = fz0 - 1;
            gw = fx1 - fx0 + 3;
            gd = fz1 - fz0 + 3;
            int worldTop = world.topYExclusive() - 1, worldBottom = world.bottomY();
            long reach = (long) ball.spec.radius() + TerrainKernel.SCAN_MARGIN;
            yTop = (int) Math.max(worldBottom, Math.min(worldTop, dab.blockY() + reach));
            yBottom = (int) Math.max(worldBottom, Math.min(worldTop, dab.blockY() - reach));
            height = new int[gw * gd];
            for (int x = gx0; x < gx0 + gw; x++) {
                for (int z = gz0; z < gz0 + gd; z++) {
                    int i = (x - gx0) * gd + (z - gz0);
                    height[i] = ball.loadedColumn(x, z) ? SurfaceScan.scan(world, states, x, z, yTop, yBottom, null) : NONE;
                }
            }
        }

        /** Column (x, z)'s surface height (the Terrain mode), or NONE outside the footprint and its ring. */
        int heightAt(int x, int z) {
            if (x < gx0 || x >= gx0 + gw || z < gz0 || z >= gz0 + gd) return NONE;
            return height[(x - gx0) * gd + (z - gz0)];
        }

        /** Whether column (x, z) lies in the footprint (the Terrain mode). */
        boolean inFootprint(int x, int z) {
            return x >= fx0 && x <= fx1 && z >= fz0 && z <= fz1 && ball.lateralDistance(x, z) >= 0;
        }

        /**
         * Whether open cell (x, y, z) is on the dab's side: joined to the open cells around the dab (the Surface mode, a
         * block beyond the ball at most), or above its column's surface (the Terrain mode, the footprint and its ring,
         * inside the scan window).
         */
        boolean sideOpen(int x, int y, int z) {
            if (!ball.inBox(x, y, z)) return false;
            if (surface) return ball.joined[ball.index(x, y, z)];
            int h = heightAt(x, z);
            return h != NONE && y > h && y <= yTop && ball.kind(x, y, z) == OPEN;
        }

        /** Whether open cell (x, y, z) may be filled: on the dab's side, inside the ball or footprint and window. */
        boolean freeOpen(int x, int y, int z) {
            if (surface) return ball.inBox(x, y, z) && ball.within(x, y, z, ball.r16) && sideOpen(x, y, z);
            return inFootprint(x, z) && y >= yBottom && sideOpen(x, y, z);
        }

        /** Whether ground cell (x, y, z) may change: inside the ball or footprint and window, touching a side cell. */
        boolean freeGround(int x, int y, int z) {
            if (surface) {
                if (!ball.inBox(x, y, z) || !ball.within(x, y, z, ball.r16)) return false;
            } else {
                // Only columns with a surface in the window: those the dab visits (a column rising above the window,
                // or topped by a structure, is left whole).
                if (!inFootprint(x, z) || y < yBottom) return false;
                int h = heightAt(x, z);
                if (h == NONE || y > h) return false;
            }
            if (ball.kind(x, y, z) != GROUND) return false;
            return sideOpen(x, y + 1, z) || sideOpen(x, y - 1, z) || sideOpen(x - 1, y, z) || sideOpen(x + 1, y, z)
                    || sideOpen(x, y, z - 1) || sideOpen(x, y, z + 1);
        }

        /**
         * Whether the ground cell at index {@code i}, inside the ball (the Surface mode), touches an open cell on the dab's
         * side: {@link #freeGround} without the checks its position has passed.
         */
        boolean touchesSide(int i) {
            boolean[] joined = ball.joined;
            return joined[i + 1] || joined[i - 1] || joined[i + ball.strideZ] || joined[i - ball.strideZ]
                    || joined[i + ball.strideX] || joined[i - ball.strideX];
        }

        /** Whether the cell is one this dab may change (for the step's other dabs). */
        boolean inRegion(int x, int y, int z) {
            if (!ball.inBox(x, y, z)) return false;
            byte kind = ball.kind(x, y, z);
            return kind == OPEN ? freeOpen(x, y, z) : kind == GROUND && freeGround(x, y, z);
        }

        /** The dab's fixed-point weight at a cell of its region: by the 3D distance, or the column's across. */
        int weight(int x, int y, int z) {
            double t = surface ? ball.distance(x, y, z) : ball.lateralDistance(x, z);
            return t < 0 ? 0 : ball.weight(t);
        }

        /** The cell's pre-image under this dab's image: x. */
        int preX(int x, int z) {
            return image == Symmetry.Image.IDENTITY ? x : (int) symmetry.cellX(inverse, x, z);
        }

        int preZ(int x, int z) {
            return image == Symmetry.Image.IDENTITY ? z : (int) symmetry.cellZ(inverse, x, z);
        }
    }

    /** What a cell's rule asks: a rate in halves (0: stay), and the block it takes or where it moves to. */
    private static final class Verdict {
        int rate;
        int material;
        /** Melt: where the block lands. */
        int toX, toY, toZ;
        /** Roughen: how deep the change lies, counted from the stroke's first change on that spot. */
        int depth;
    }

    // ------------------------------------------------------------------ one step

    private static final class Pass {
        final BrushSpec spec;
        final WeatherSpec.Mode mode;
        final StrokeState stroke;
        final WorldReader world;
        final StateSpace states;
        final Box clip;
        final Grid[] grids;
        final CellPlan cells;
        final int scale;
        final long seed;
        final Verdict verdict = new Verdict();
        /** {@link #vote}'s states found around a cell, and their scores. */
        private final int[] voteFound = new int[26], voteScore = new int[26];
        /**
         * Roughen's lattice values met in this step, direct-mapped ({@link #lattice}): a cell's eight are mostly its
         * neighbours' too. Keys have the top bit set (0: empty).
         */
        private long[] latticeKeys;
        private int[] latticeValues;
        /** The lattice cell {@link #noiseAt} used last, and its eight values: the next cell up a column is mostly in it. */
        private int cornerX = Integer.MIN_VALUE, cornerY, cornerZ;
        private final int[] corners = new int[8];
        /** The global mask, judging Melt's moves whole; {@code null} when it is off. */
        MaskedKernel.Judge editMask;
        ColumnFilter filter;
        boolean slopes;
        /** The step's main changes, in plan order: grid, cell, and whether it filled (1) or emptied (0). */
        final List<int[]> changes = new ArrayList<>();

        Pass(BrushSpec spec, Dab first, Dab[] dabs, StrokeState stroke, WorldReader world) {
            this.spec = spec;
            this.mode = spec.weather().mode();
            this.stroke = stroke;
            this.world = world;
            this.states = world.states();
            this.clip = spec.clip();
            this.scale = roughenScale(spec.radius());
            this.seed = spec.seed();
            List<Grid> reaching = new ArrayList<>(dabs.length);
            for (Dab dab : dabs) {
                // The k-th dab of the step takes the stroke's k-th arrays; one that misses the clip box reads nothing.
                Grid grid = new Grid(spec, dab, first, world, stroke.surfaceScratch(), reaching.size());
                if (grid.reaches(spec, clip)) reaching.add(grid);
            }
            this.grids = reaching.toArray(new Grid[0]);
            this.cells = new CellPlan(world, clip, new CellPlan.Layout() {
                @Override
                public long key(int x, int y, int z) {
                    for (int a = 0; a < grids.length; a++) {
                        if (grids[a].ball.inBox(x, y, z)) return ((long) a << 40) | grids[a].ball.index(x, y, z);
                    }
                    return Long.MIN_VALUE;
                }

                @Override
                public boolean loaded(int x, int z) {
                    for (Grid grid : grids) {
                        if (grid.ball.inBoxColumn(x, z)) return grid.ball.loadedColumn(x, z);
                    }
                    return false;
                }
            });
        }

        void run(CellSink out) {
            // Bound first, so a bad mask fails the same way whether or not this step reaches the clip box.
            filter = stroke.filter(spec.mask(), states, world);
            if (grids.length == 0) return;
            slopes = SurfaceKernel.usesSlope(spec.mask());
            for (Grid grid : grids) grid.snapshot(world, states);
            for (int a = 0; a < grids.length; a++) visit(a);
            clearPlants();
            cells.emit(out);
        }

        // ---- visiting ----

        /**
         * Visits dab {@code a}'s cells column by column in its own frame (the pre-image's x, then z, ascending), each
         * column bottom up: the Surface mode the ball's columns, the Terrain mode the footprint's, from the lowest cell a
         * side cell can touch to the highest.
         */
        private void visit(int a) {
            Grid grid = grids[a];
            SurfaceKernel.Ball ball = grid.ball;
            int r = spec.radius();
            int xa, xb, za, zb;
            if (grid.surface) {
                xa = ball.bx - r;
                xb = ball.bx + r;
                za = ball.bz - r;
                zb = ball.bz + r;
            } else {
                xa = grid.fx0;
                xb = grid.fx1;
                za = grid.fz0;
                zb = grid.fz1;
            }
            // The frame's x and z axes, in the world.
            int ox = (int) grid.image.x(1, 0), oz = (int) grid.image.z(1, 0);
            int ix = (int) grid.image.x(0, 1), iz = (int) grid.image.z(0, 1);
            if (ox != 0) {
                for (int x = ox > 0 ? xa : xb; x >= xa && x <= xb; x += ox) {
                    for (int z = iz > 0 ? za : zb; z >= za && z <= zb; z += iz) column(a, grid, x, z);
                }
            } else {
                for (int z = oz > 0 ? za : zb; z >= za && z <= zb; z += oz) {
                    for (int x = ix > 0 ? xa : xb; x >= xa && x <= xb; x += ix) column(a, grid, x, z);
                }
            }
        }

        private void column(int a, Grid grid, int x, int z) {
            if (clip != null && (x < clip.min().x() || x > clip.max().x() || z < clip.min().z() || z > clip.max().z())) {
                return;
            }
            int lo, hi;
            if (grid.surface) {
                long span = grid.ball.span(x, z, grid.ball.r16);
                lo = SurfaceKernel.spanLow(span);
                hi = SurfaceKernel.spanHigh(span);
            } else {
                if (!grid.inFootprint(x, z)) return;
                int h = grid.heightAt(x, z);
                if (h == NONE) return;
                lo = h;
                hi = h + 1;
                for (int side = 0; side < 4; side++) {
                    int n = grid.heightAt(x + (side == 0 ? -1 : side == 1 ? 1 : 0), z + (side == 2 ? -1 : side == 3 ? 1 : 0));
                    if (n == NONE) {
                        // A column beside without a surface in the window (a wall rising above it, a structure).
                        hi = grid.yTop;
                    } else {
                        lo = Math.min(lo, n + 1);
                        hi = Math.max(hi, n);
                    }
                }
                lo = Math.max(lo, grid.yBottom);
                hi = Math.min(hi, grid.yTop);
            }
            for (int y = lo; y <= hi; y++) cell(a, grid, x, y, z);
        }

        /** One cell of dab {@code a}'s region: its rule, accumulator, and change. */
        private void cell(int a, Grid grid, int x, int y, int z) {
            SurfaceKernel.Ball ball = grid.ball;
            int i = ball.index(x, y, z);
            byte kind = ball.kindAt(i);
            boolean ground;
            // The cells visited lie inside the ball (the Surface mode) or the footprint and window: in the Surface mode only
            // the side is left to check, from the snapshot's index.
            if (kind == GROUND) {
                if (mode == WeatherSpec.Mode.FILL_IN) return;
                if (grid.surface ? !grid.touchesSide(i) : !grid.freeGround(x, y, z)) return;
                ground = true;
            } else if (kind == OPEN) {
                // Erode and Melt only change ground cells (a Melt landing is decided by the cell that falls).
                if (mode == WeatherSpec.Mode.ERODE || mode == WeatherSpec.Mode.MELT) return;
                if (grid.surface ? !ball.joined[i] : !grid.freeOpen(x, y, z)) return;
                ground = false;
            } else {
                return;
            }
            if (clip != null && !clip.contains(x, y, z)) return;
            Verdict v = verdict;
            // Cells whose rule has nothing to say (no face it counts) are skipped; the others that should stay are reset.
            if (!rule(grid, x, y, z, i, ground, v)) return;
            // A cell Roughen changed in this stroke keeps its new block (its accumulator holds minus the change's depth).
            if (mode == WeatherSpec.Mode.ROUGHEN && stroke.anyCellAccumulator() && stroke.cellAccumulator(x, y, z) < 0) return;
            if (grids.length > 1 && decidedBefore(a, x, y, z)) return;
            if (v.rate == 0) {
                if (stroke.anyCellAccumulator()) stroke.setCellAccumulator(x, y, z, 0);
                return;
            }
            int k = grid.weight(x, y, z);
            for (int b = a + 1; b < grids.length; b++) {
                Grid other = grids[b];
                if (other.inRegion(x, y, z)) k = Math.max(k, other.weight(x, y, z));
            }
            if (k <= 0) return;
            boolean moves = mode == WeatherSpec.Mode.MELT;
            int own = ball.state(x, y, z);
            int seen = ground ? own : v.material;
            if (!filter.test(x, y, z, seen, slopes ? slope(grid, x, y, z) : 0)) return;
            if (!plantsInsideClip(grid, x, y, z, !ground)) return;
            if (moves) {
                if (clip != null && !clip.contains(v.toX, v.toY, v.toZ)) return;
                if (!filter.test(v.toX, v.toY, v.toZ, own, slopes ? slope(grid, v.toX, v.toY, v.toZ) : 0)) return;
                if (!plantsInsideClip(grid, v.toX, v.toY, v.toZ, true)) return;
                // Under the global mask a move is judged whole: both ends pass or the block stays (MaskedKernel.Grouped).
                if (editMask != null && !(editMask.accepts(x, y, z) && editMask.accepts(v.toX, v.toY, v.toZ))) return;
            }
            long credit = stroke.cellAccumulator(x, y, z) + (long) k * v.rate / 2;
            if (credit < ONE) {
                stroke.setCellAccumulator(x, y, z, (int) credit);
                return;
            }
            if (moves) {
                if (cells.planned(x, y, z) || cells.planned(v.toX, v.toY, v.toZ) || cells.planned(v.toX, v.toY - 1, v.toZ)
                        || cells.planned(x, y + 1, z)) {
                    // Another move of the step lands there first, takes the block this one would land on, or lands on
                    // this block: this one waits for the next dab, so nothing is left hanging.
                    stroke.setCellAccumulator(x, y, z, ONE);
                    return;
                }
                stroke.setCellAccumulator(x, y, z, 0);
                planMain(a, grid, x, y, z, FILL);
                planMain(a, grid, v.toX, v.toY, v.toZ, own);
                return;
            }
            int after = mode != WeatherSpec.Mode.ROUGHEN ? 0 : ground ? carveMarker(v.depth) : buildMarker(v.depth);
            stroke.setCellAccumulator(x, y, z, after);
            planMain(a, grid, x, y, z, ground ? FILL : v.material);
        }

        /** Whether an earlier dab of the step has the cell in its region: that dab decides it. */
        private boolean decidedBefore(int a, int x, int y, int z) {
            for (int b = 0; b < a; b++) {
                if (grids[b].inRegion(x, y, z)) return true;
            }
            return false;
        }

        // ---- rules ----

        /**
         * The mode's rule at a region cell, into {@code v}; {@code false} when the rule has nothing to say about the cell
         * (it counts no face there: nothing to change or reset).
         */
        private boolean rule(Grid grid, int x, int y, int z, int i, boolean ground, Verdict v) {
            SurfaceKernel.Ball ball = grid.ball;
            v.rate = 0;
            int open = 0, solid = 0, groundFaces = 0;
            boolean structure = false;
            for (int face = 0; face < 6; face++) {
                int next = i + switch (face) {
                    case 0 -> 1;
                    case 1 -> -1;
                    case 2 -> ball.strideZ;
                    case 3 -> -ball.strideZ;
                    case 4 -> ball.strideX;
                    default -> -ball.strideX;
                };
                byte kind = ball.kindAt(next);
                switch (kind) {
                    // Erode counts only the open faces on the dab's side (and above the world): it wears what the
                    // weather reaches, and never breaks through a thin wall into a cave behind it.
                    case SurfaceKernel.ABOVE -> open++;
                    case SurfaceKernel.OPEN -> {
                        if (mode != WeatherSpec.Mode.ERODE || sideFace(grid, x, y, z, next, face)) open++;
                    }
                    case SurfaceKernel.GROUND -> {
                        solid++;
                        groundFaces++;
                    }
                    case SurfaceKernel.STRUCTURE -> {
                        solid++;
                        structure = true;
                    }
                    case SurfaceKernel.BELOW -> solid++;
                    // Next to an unloaded chunk: the cell doesn't change.
                    default -> {
                        return false;
                    }
                }
            }
            switch (mode) {
                case ERODE -> {
                    if (!ground) return false;
                    if (!structure) v.rate = rate(open, ERODE_FACES);
                }
                case FILL_IN -> {
                    if (ground || solid == 0) return false;
                    int rate = rate(solid, FILL_FACES);
                    if (rate > 0 && (v.material = vote(grid, i)) >= 0) v.rate = rate;
                }
                case ROUGHEN -> {
                    if (!ground && groundFaces == 0) return false;
                    if (ground && structure) return true;
                    int n = noiseAt(grid.preX(x, z), y, grid.preZ(x, z));
                    if (ground) {
                        if (n < ROUGHEN_LOW) v.rate = 2;
                    } else if (n > ROUGHEN_HIGH && (v.material = vote(grid, i)) >= 0) {
                        v.rate = 2;
                    }
                    if (v.rate > 0) {
                        // How far below (a carve) or above (a build) the stroke's first surface the change lies, and no
                        // deeper than the lumps allow.
                        v.depth = depthOf(grid, i, ground);
                        if (v.depth > roughenDepth(scale)) v.rate = 0;
                    }
                }
                case MELT -> {
                    if (!ground) return false;
                    if (!structure && meltTarget(grid, x, y, z, v)) v.rate = 2;
                }
            }
            return true;
        }

        /**
         * Whether face {@code face} (0 up, 1 down, 2 south, 3 north, 4 east, 5 west) of cell (x, y, z), the open cell at
         * index {@code next}, is on the dab's side.
         */
        private static boolean sideFace(Grid grid, int x, int y, int z, int next, int face) {
            if (grid.surface) return grid.ball.joined[next];
            return switch (face) {
                case 0 -> grid.sideOpen(x, y + 1, z);
                case 1 -> grid.sideOpen(x, y - 1, z);
                case 2 -> grid.sideOpen(x, y, z + 1);
                case 3 -> grid.sideOpen(x, y, z - 1);
                case 4 -> grid.sideOpen(x + 1, y, z);
                default -> grid.sideOpen(x - 1, y, z);
            };
        }

        /** {@link #noise} at cell (x, y, z) for this step's scale and seed, from the memoized lattice. */
        private int noiseAt(int x, int y, int z) {
            int ix = Math.floorDiv(x, scale), iy = Math.floorDiv(y, scale), iz = Math.floorDiv(z, scale);
            if (ix != cornerX || iy != cornerY || iz != cornerZ) {
                for (int corner = 0; corner < 8; corner++) {
                    corners[corner] = lattice(ix + (corner & 1), iy + ((corner >> 1) & 1), iz + (corner >> 2));
                }
                cornerX = ix;
                cornerY = iy;
                cornerZ = iz;
            }
            return blend(x - ix * scale, y - iy * scale, z - iz * scale, scale, corners);
        }

        /** {@link #latticeValue} for this step's seed, memoized (the same values, only fewer hashes). */
        private int lattice(int ix, int iy, int iz) {
            if (latticeKeys == null) {
                latticeKeys = new long[4096];
                latticeValues = new int[4096];
            }
            // Lattice x and z lie within ±2^24 (dabs within ±2^25 blocks, lattices at least 2 apart), y within ±2^12.
            long key = Long.MIN_VALUE | ((ix & 0x1FFFFFFL) << 38) | ((iz & 0x1FFFFFFL) << 13) | (iy & 0x1FFFL);
            int slot = ((ix * 73_856_093) ^ (iy * 19_349_663) ^ (iz * 83_492_791)) & 4095;
            if (latticeKeys[slot] == key) return latticeValues[slot];
            int value = latticeValue(seed, ix, iy, iz);
            latticeKeys[slot] = key;
            latticeValues[slot] = value;
            return value;
        }

        /**
         * How deep a Roughen change at the cell at index {@code i} would lie: 1 on the surface the stroke began on, else one
         * more than the shallowest change of the stroke it goes through. A carve (a {@code ground} cell) is reached
         * through its open faces, 1 when one of them was open when the stroke began, else 1 + the least depth among
         * those the stroke carved; a build through its ground faces the same way, with the cells the stroke built.
         */
        private int depthOf(Grid grid, int i, boolean ground) {
            if (!stroke.anyCellAccumulator()) return 1;
            SurfaceKernel.Ball ball = grid.ball;
            int least = Integer.MAX_VALUE;
            for (int face = 0; face < 6; face++) {
                int next = i + switch (face) {
                    case 0 -> 1;
                    case 1 -> -1;
                    case 2 -> ball.strideZ;
                    case 3 -> -ball.strideZ;
                    case 4 -> ball.strideX;
                    default -> -ball.strideX;
                };
                byte kind = ball.kindAt(next);
                if (ground ? kind != OPEN && kind != ABOVE : kind != GROUND) continue;
                int column = next / ball.size;
                int fx = ball.x0 + column / ball.size, fz = ball.z0 + column % ball.size, fy = ball.y0 + next - column * ball.size;
                int marker = kind == ABOVE ? 0 : stroke.cellAccumulator(fx, fy, fz);
                least = Math.min(least, ground ? carveDepth(marker) : buildDepth(marker));
                if (least == 0) break;
            }
            return least == Integer.MAX_VALUE ? 1 : least + 1;
        }

        /** The depth of a cell Roughen carved in this stroke, from its accumulator ({@link #carveMarker}); else 0. */
        private static int carveDepth(int marker) {
            return marker < 0 && marker >= -BUILD_MARK ? -marker : 0;
        }

        /** The depth of a cell Roughen built in this stroke, from its accumulator ({@link #buildMarker}); else 0. */
        private static int buildDepth(int marker) {
            return marker < -BUILD_MARK ? -marker - BUILD_MARK : 0;
        }

        /** What a cell Roughen carved at {@code depth} keeps as its accumulator: -1 to -4. */
        private static int carveMarker(int depth) {
            return -depth;
        }

        /** What a cell Roughen built at {@code depth} keeps as its accumulator: -9 to -12. */
        private static int buildMarker(int depth) {
            return -depth - BUILD_MARK;
        }

        /**
         * Where Melt moves ground cell (x, y, z), into {@code v}: straight down when the cell under it may be filled,
         * else off the steepest side where the cells beside it and under that may be filled, landing on the ground
         * below; {@code false} when it has nowhere to go (a fall that leaves the region or meets an unloaded chunk).
         */
        private boolean meltTarget(Grid grid, int x, int y, int z, Verdict v) {
            if (grid.freeOpen(x, y - 1, z)) {
                // Straight down: the landing's faces lie in the columns of the source's, all loaded.
                int land = fall(grid, x, y - 1, z);
                if (land == NONE) return false;
                v.toX = x;
                v.toY = land;
                v.toZ = z;
                return true;
            }
            // Only a cell with open sky (or the open cells on the dab's side) above it slides.
            boolean openAbove = grid.ball.inBox(x, y + 1, z)
                    && (grid.ball.kind(x, y + 1, z) == ABOVE || grid.sideOpen(x, y + 1, z));
            if (!openAbove) return false;
            int best = NONE;
            for (int side = 0; side < 4; side++) {
                int sx = x + grid.sideX[side], sz = z + grid.sideZ[side];
                if (!grid.freeOpen(sx, y, sz) || !grid.freeOpen(sx, y - 1, sz)) continue;
                int land = fall(grid, sx, y - 1, sz);
                // The steepest drop; among equal ones the first side in the dab's frame. A landing beside an unloaded
                // chunk (the source's faces are all loaded, the landing's far side may not be) is no landing.
                if (land != NONE && (best == NONE || land < best) && !besideUnloaded(grid, sx, land, sz)) {
                    best = land;
                    v.toX = sx;
                    v.toY = land;
                    v.toZ = sz;
                }
            }
            return best != NONE;
        }

        /** Whether a face of cell (x, y, z) lies in a chunk that is not loaded. */
        private static boolean besideUnloaded(Grid grid, int x, int y, int z) {
            SurfaceKernel.Ball ball = grid.ball;
            return !ball.loadedColumn(x - 1, z) || !ball.loadedColumn(x + 1, z) || !ball.loadedColumn(x, z - 1)
                    || !ball.loadedColumn(x, z + 1) || !ball.loadedColumn(x, z);
        }

        /** The lowest cell a block falling from open cell (x, y, z) reaches through cells it may fill, or NONE. */
        private int fall(Grid grid, int x, int y, int z) {
            while (grid.freeOpen(x, y - 1, z)) y--;
            if (!grid.ball.inBox(x, y - 1, z)) return NONE;
            byte below = grid.ball.kind(x, y - 1, z);
            return below == GROUND || below == STRUCTURE || below == BELOW ? y : NONE;
        }

        /**
         * The ground block most common around cell {@code i} (faces count 3, edges 2, corners 1; a tie goes to the first
         * in the dab's frame), or -1 when no ground touches it.
         */
        private int vote(Grid grid, int i) {
            SurfaceKernel.Ball ball = grid.ball;
            int[] found = voteFound, score = voteScore;
            int n = 0;
            for (int k = 0; k < 26; k++) {
                int next = i + grid.voteStep[k];
                if (ball.kindAt(next) != GROUND) continue;
                int state = ball.cellStates[next];
                int j = 0;
                while (j < n && found[j] != state) j++;
                if (j == n) {
                    found[n++] = state;
                    score[j] = 0;
                }
                score[j] += grid.voteScore[k];
            }
            int best = -1;
            for (int j = 0; j < n; j++) {
                if (best < 0 || score[j] > score[best]) best = j;
            }
            return best < 0 ? -1 : found[best];
        }

        /**
         * The Slope mask's value at a cell: the Surface mode the steepness of the estimate within two blocks; the Terrain
         * mode the column's largest height step to a neighbour that has a surface.
         */
        private int slope(Grid grid, int x, int y, int z) {
            if (grid.surface) {
                return SurfaceNormal.slope(SurfaceNormal.moment(grid.ball::normalKind, x, y, z, SurfaceNormal.SLOPE_RADIUS));
            }
            int h = grid.heightAt(x, z);
            if (h == NONE) return 0;
            int step = 0;
            int[][] sides = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
            for (int[] side : sides) {
                int other = grid.heightAt(x + side[0], z + side[1]);
                if (other != NONE) step = Math.max(step, Math.abs(other - h));
            }
            return step;
        }

        // ---- writes ----

        private void planMain(int a, Grid grid, int x, int y, int z, int next) {
            if (cells.planned(x, y, z)) return;
            cells.plan(x, y, z, next, grid.ball.state(x, y, z));
            changes.add(new int[] {a, x, y, z, next == FILL ? 0 : 1});
        }

        /**
         * Plants the main changes leave floating or cut: the run of plant cells standing on each changed cell, and the
         * lower half of a two-block plant whose upper half filled. Cells planned already are kept as planned.
         */
        private void clearPlants() {
            for (int[] change : changes) {
                Grid grid = grids[change[0]];
                int x = change[1], y = change[2], z = change[3];
                forEachPlant(grid, x, y, z, change[4] == 1, (px, py, pz) -> {
                    if (!cells.planned(px, py, pz)) cells.plan(px, py, pz, FILL, grid.ball.state(px, py, pz));
                });
            }
        }

        /** Whether every plant a change at (x, y, z) would clear lies inside the clip box (always without one). */
        private boolean plantsInsideClip(Grid grid, int x, int y, int z, boolean filling) {
            if (clip == null) return true;
            boolean[] inside = {true};
            forEachPlant(grid, x, y, z, filling, (px, py, pz) -> {
                if (!clip.contains(px, py, pz)) inside[0] = false;
            });
            return inside[0];
        }

        @FunctionalInterface
        private interface CellVisitor {
            void visit(int x, int y, int z);
        }

        private void forEachPlant(Grid grid, int x, int y, int z, boolean filling, CellVisitor visitor) {
            SurfaceKernel.Ball ball = grid.ball;
            for (int i = 1; i <= TerrainKernel.MAX_PLANT_RUN; i++) {
                if (!ball.inBox(x, y + i, z) || !plant(ball, x, y + i, z)) break;
                visitor.visit(x, y + i, z);
            }
            if (filling && StateFlags.has(states.flags(ball.state(x, y, z)), StateFlags.UPPER_HALF)
                    && ball.inBox(x, y - 1, z) && plant(ball, x, y - 1, z)) {
                visitor.visit(x, y - 1, z);
            }
        }

        private boolean plant(SurfaceKernel.Ball ball, int x, int y, int z) {
            return ball.kind(x, y, z) == OPEN && SurfaceScan.plant(states.flags(ball.state(x, y, z)));
        }
    }
}
