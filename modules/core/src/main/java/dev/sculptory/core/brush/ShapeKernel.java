package dev.sculptory.core.brush;

import dev.sculptory.core.Box;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The Shape brush's kernel ({@link BrushTool#SHAPE}): each step places the dab's shape and its symmetric images
 * ({@link ShapeStamp#placements}), writing exactly the cells of each one's voxelized shape.
 *
 * <p><b>Cells.</b> A placement's cells are those of the contract's voxelization of its box: {@code Region.Shape}'s rows
 * for a sphere (an ellipsoid), a cylinder or a cone, every cell of the box ({@code Region.Cuboid}) for a cube. With
 * {@link ShapeSpec#hollow()} {@code t > 0} only its shell is written: a cell whose run of shape cells along x, y or z
 * ends within {@code t} cells of it (a cell outside the shape lies within {@code t} along one of the six axis
 * directions). The shapes are convex, so each such run is one interval, found from the rows. The step writes the union
 * of its placements' cells, each cell once.
 *
 * <p><b>Mode</b> ({@link ShapeSpec#mode()}): Place writes {@code material.apply(states, x, y, z, before)} into every cell;
 * Place in air only into cells that are air or replaceable ({@link StateFlags#AIR}, {@link StateFlags#REPLACEABLE}:
 * short plants, fluids); Paint only into the other cells; Carve writes air. A weighted material picks by
 * position ({@link Pattern.Weighted}), so the copies get the same mix but not the same pattern, and a cell gets the same
 * block whichever placement covers it. A mix laid out in space ({@link Pattern.Arranged}: Patches or Gradient) is read at
 * a copy's cells' pre-images under the copy's image, so the copy mirrors or turns the pattern with the shape (the
 * first placement, the dab's own, writes a cell several cover).
 *
 * <p><b>Limits.</b> Cells outside the build height, outside the {@link BrushSpec#clip()} box or in chunks the reader
 * does not have loaded are neither read nor written. A cell's new state depends only on the state it holds, and each
 * cell of the step is read once, just before it goes to the sink, so the step reads the world as it was before the step
 * whether or not the sink writes through (as the prediction and the server do). A cell reaches the sink only when its
 * state changes, in a fixed order: placement by placement, then y, z and x ascending.
 *
 * <p><b>Work per dab</b> is bounded by the boxes: at most one read and one write per cell of each placement's box, at
 * most {@code 65 × 65 × 65 = 274,625} cells (radius 32, height 65), times at most {@value Symmetry#MAX_COPIES}
 * placements; building a placement's rows and runs costs one pass over its cells. The server runs a large step in
 * parts over several ticks ({@link ShapeStep}); this kernel runs the same parts in one go.
 *
 * <p><b>The step's dabs</b> are exactly the dab the player laid and its {@link Symmetry#copies}, in that order, as
 * {@link SymmetricStep#of} lists them (the one exception to {@link BrushKernel#applyStep}'s order freedom): the kernel
 * places the first dab's shape and images itself ({@link ShapeStamp#placements}), so a dab on a mirror plane also gets
 * its mirrored twin, and the copies listed serve the server's checks.
 */
final class ShapeKernel implements BrushKernel {
    @Override
    public void applyStep(BrushSpec s, List<Dab> dabs, StrokeState st, WorldReader w, CellSink out) {
        Objects.requireNonNull(out);
        ShapeStep.of(s, dabs, st, w).runAll(out);
    }

    /** Whether a placement's box stays within the kernel's horizontal limit (with the reach of the largest shape). */
    static boolean insideLimit(Box box) {
        long limit = (long) TerrainKernel.MAX_HORIZONTAL + ShapeSpec.MAX_HEIGHT;
        return Math.abs((long) box.min().x()) <= limit && Math.abs((long) box.max().x()) <= limit
                && Math.abs((long) box.min().z()) <= limit && Math.abs((long) box.max().z()) <= limit;
    }

    /**
     * The x interval of each row (y, z) of a placement's box that its region holds ({@link Region.Shape#rowSpan}, or
     * the whole row of a {@link Region.Cuboid}), into {@code lo}/{@code hi} at {@code (y - y0) × sizeZ + (z - z0)}; an
     * empty row is {@code (x0, x0 - 1)}.
     */
    static void rows(ShapeStamp.Placement placement, int[] lo, int[] hi) {
        Box box = placement.box();
        if (!(placement.region() instanceof Region.Shape shape)) {
            Arrays.fill(lo, box.min().x());
            Arrays.fill(hi, box.max().x());
            return;
        }
        int sz = box.sizeZ();
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                int row = (y - box.min().y()) * sz + (z - box.min().z());
                long span = shape.rowSpan(y, z);
                if (span == Region.Shape.EMPTY_ROW) {
                    lo[row] = box.min().x();
                    hi[row] = box.min().x() - 1;
                } else {
                    lo[row] = Region.Shape.rowMin(span);
                    hi[row] = Region.Shape.rowMax(span);
                }
            }
        }
    }

    /** One placement's cells: its rows, and with a hollow thickness the runs along y and z for the shell test. */
    static final class Stamp {
        final Box box;
        final int x0, y0, z0, sx, sy, sz, thickness;
        final int[] rowLo, rowHi;
        /** Per (x, z) at {@code (x - x0) × sz + (z - z0)}: the column's y interval (hollow only). */
        private int[] colLo, colHi;
        /** Per (x, y) at {@code (x - x0) × sy + (y - y0)}: the z interval (hollow only). */
        private int[] depLo, depHi;

        Stamp(ShapeStamp.Placement placement, int thickness) {
            box = placement.box();
            x0 = box.min().x();
            y0 = box.min().y();
            z0 = box.min().z();
            sx = box.sizeX();
            sy = box.sizeY();
            sz = box.sizeZ();
            this.thickness = thickness;
            rowLo = new int[sy * sz];
            rowHi = new int[sy * sz];
            rows(placement, rowLo, rowHi);
            if (thickness > 0) runs();
        }

        /** The runs along y and z, from the rows: the shapes are convex, so each run is one interval. */
        private void runs() {
            colLo = new int[sx * sz];
            colHi = new int[sx * sz];
            depLo = new int[sx * sy];
            depHi = new int[sx * sy];
            Arrays.fill(colLo, Integer.MAX_VALUE);
            Arrays.fill(colHi, Integer.MIN_VALUE);
            Arrays.fill(depLo, Integer.MAX_VALUE);
            Arrays.fill(depHi, Integer.MIN_VALUE);
            for (int y = y0; y < y0 + sy; y++) {
                for (int z = z0; z < z0 + sz; z++) {
                    int row = (y - y0) * sz + (z - z0);
                    for (int x = rowLo[row]; x <= rowHi[row]; x++) {
                        int col = (x - x0) * sz + (z - z0);
                        colLo[col] = Math.min(colLo[col], y);
                        colHi[col] = Math.max(colHi[col], y);
                        int dep = (x - x0) * sy + (y - y0);
                        depLo[dep] = Math.min(depLo[dep], z);
                        depHi[dep] = Math.max(depHi[dep], z);
                    }
                }
            }
        }

        /** Whether the dab writes cell (x, y, z) of this placement: in the shape, and with a thickness in its shell. */
        boolean writes(int x, int y, int z) {
            if (!box.contains(x, y, z)) return false;
            int row = (y - y0) * sz + (z - z0);
            if (x < rowLo[row] || x > rowHi[row]) return false;
            return thickness == 0 || shell(x, y, z, row);
        }

        /** For a cell of the shape: whether a cell outside it lies within the thickness along x, y or z. */
        private boolean shell(int x, int y, int z, int row) {
            int t = thickness;
            if (x - rowLo[row] < t || rowHi[row] - x < t) return true;
            int col = (x - x0) * sz + (z - z0);
            if (y - colLo[col] < t || colHi[col] - y < t) return true;
            int dep = (x - x0) * sy + (y - y0);
            return z - depLo[dep] < t || depHi[dep] - z < t;
        }

        /** The number of cells the dab writes of this placement (before mode, clip and the build height). */
        long count() {
            long count = 0;
            for (int y = y0; y < y0 + sy; y++) {
                for (int z = z0; z < z0 + sz; z++) {
                    int row = (y - y0) * sz + (z - z0);
                    if (thickness == 0) {
                        count += Math.max(0, rowHi[row] - rowLo[row] + 1);
                        continue;
                    }
                    for (int x = rowLo[row]; x <= rowHi[row]; x++) {
                        if (shell(x, y, z, row)) count++;
                    }
                }
            }
            return count;
        }
    }

    /**
     * Writes placements' changed cells, a placement's run of y layers at a time ({@link #place}), each cell read once just
     * before it is written.
     */
    static final class Pass {
        private final ShapeSpec.Mode mode;
        private final Pattern material;
        private final Stamp[] stamps;
        /** The symmetry image that made each placement: a laid-out mix is read at a copy's cells' pre-images. */
        private final Symmetry.Image[] images;
        private final Symmetry symmetry;
        private final WorldReader world;
        private final StateSpace states;
        private final int air;
        private final Box clip;
        private final int bottom, top;

        private long loadedColumn = Long.MIN_VALUE;
        private boolean loaded;
        /** The inverse of the image of the placement being written. */
        private Symmetry.Image inverse = Symmetry.Image.IDENTITY;

        Pass(BrushSpec spec, Stamp[] stamps, Symmetry.Image[] images, WorldReader world) {
            this.mode = spec.shapeSpec().mode();
            this.material = spec.material();
            this.stamps = stamps;
            this.images = images;
            this.symmetry = spec.symmetry();
            this.world = world;
            this.states = world.states();
            this.air = states.air();
            this.clip = spec.clip();
            this.bottom = world.bottomY();
            this.top = world.topYExclusive() - 1;
        }

        int placements() {
            return stamps.length;
        }

        Stamp stamp(int i) {
            return stamps[i];
        }

        /** Placement {@code i}'s lowest layer to write: its box within the build height and the clip box. */
        int yLow(int i) {
            int y = Math.max(stamps[i].y0, bottom);
            return clip == null ? y : Math.max(y, clip.min().y());
        }

        /** Placement {@code i}'s highest layer to write (below {@link #yLow} when there is none). */
        int yHigh(int i) {
            int y = Math.min(stamps[i].y0 + stamps[i].sy - 1, top);
            return clip == null ? y : Math.min(y, clip.max().y());
        }

        /**
         * How many cells of layer {@code y} placement {@code i} reads: its shape's (its rows' lengths), or with a hollow
         * thickness its shell's ({@link Stamp#count} per layer).
         */
        long layerCells(int i, int y) {
            Stamp stamp = stamps[i];
            long cells = 0;
            for (int z = stamp.z0, row = (y - stamp.y0) * stamp.sz; z < stamp.z0 + stamp.sz; z++, row++) {
                if (stamp.thickness == 0) {
                    cells += Math.max(0, stamp.rowHi[row] - stamp.rowLo[row] + 1);
                    continue;
                }
                for (int x = stamp.rowLo[row]; x <= stamp.rowHi[row]; x++) {
                    if (stamp.shell(x, y, z, row)) cells++;
                }
            }
            return cells;
        }

        /** Writes placement {@code i}'s layers {@code yFrom} to {@code yTo} (within {@link #yLow}..{@link #yHigh}). */
        void place(int i, int yFrom, int yTo, CellSink out) {
            Stamp stamp = stamps[i];
            // Earlier placements whose boxes meet this one: a cell they write is theirs.
            Stamp[] earlier = new Stamp[i];
            int overlapping = 0;
            for (int j = 0; j < i; j++) {
                if (stamps[j].box.intersects(stamp.box)) earlier[overlapping++] = stamps[j];
            }
            int zLow = stamp.z0, zHigh = stamp.z0 + stamp.sz - 1;
            if (clip != null) {
                zLow = Math.max(zLow, clip.min().z());
                zHigh = Math.min(zHigh, clip.max().z());
            }
            // Chunks may have loaded or unloaded since the last part.
            loadedColumn = Long.MIN_VALUE;
            inverse = images[i].inverse();
            for (int y = yFrom; y <= yTo; y++) {
                for (int z = zLow; z <= zHigh; z++) {
                    int row = (y - stamp.y0) * stamp.sz + (z - stamp.z0);
                    int xLow = stamp.rowLo[row], xHigh = stamp.rowHi[row];
                    if (clip != null) {
                        xLow = Math.max(xLow, clip.min().x());
                        xHigh = Math.min(xHigh, clip.max().x());
                    }
                    for (int x = xLow; x <= xHigh; x++) {
                        if (stamp.thickness > 0 && !stamp.writes(x, y, z)) continue;
                        if (coveredBefore(earlier, overlapping, x, y, z) || !loadedAt(x, z)) continue;
                        int current = world.get(x, y, z);
                        int next = next(x, y, z, current);
                        if (next == current) continue;
                        // A state pattern changing a property of the same block keeps its block entity.
                        if (Pattern.keepsTile(material, states, current, next)) {
                            out.set(x, y, z, next, world.tile(x, y, z));
                        } else {
                            out.set(x, y, z, next);
                        }
                    }
                }
            }
        }

        private static boolean coveredBefore(Stamp[] earlier, int n, int x, int y, int z) {
            for (int k = 0; k < n; k++) {
                if (earlier[k].writes(x, y, z)) return true;
            }
            return false;
        }

        /** The state the mode leaves in a cell holding {@code current} (the same state: unchanged). */
        private int next(int x, int y, int z, int current) {
            boolean open = (states.flags(current) & (StateFlags.AIR | StateFlags.REPLACEABLE)) != 0;
            return switch (mode) {
                case PLACE -> material(x, y, z, current);
                case PLACE_IN_AIR -> open ? material(x, y, z, current) : current;
                case PAINT -> open ? current : material(x, y, z, current);
                case CARVE -> air;
            };
        }

        /**
         * The material at a cell of the placement being written: a laid-out mix ({@link Pattern.Arranged}) at the
         * cell's pre-image under the placement's image, so a copy mirrors or turns the pattern (and a Gradient's line);
         * anything else at the cell itself, as before.
         */
        private int material(int x, int y, int z, int current) {
            if (material instanceof Pattern.Arranged arranged && inverse != Symmetry.Image.IDENTITY) {
                return arranged.apply(states, (int) symmetry.cellX(inverse, x, z), y, (int) symmetry.cellZ(inverse, x, z),
                        current);
            }
            return material.apply(states, x, y, z, current);
        }

        private boolean loadedAt(int x, int z) {
            long column = ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
            if (column != loadedColumn) {
                loadedColumn = column;
                loaded = world.isLoaded(x >> 4, z >> 4);
            }
            return loaded;
        }
    }
}
