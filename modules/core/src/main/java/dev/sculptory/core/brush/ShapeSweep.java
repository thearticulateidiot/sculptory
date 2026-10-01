package dev.sculptory.core.brush;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.GeneratedTooLargeException;
import dev.sculptory.core.path.CellBits;
import dev.sculptory.core.path.PathSample;
import dev.sculptory.core.path.PathSampler;
import dev.sculptory.core.path.PathSpec;
import dev.sculptory.core.state.StateSpace;
import java.util.List;
import java.util.Objects;

/**
 * The Shape brush's Line mode: the current shape swept along a
 * {@link PathSpec}, as a generated clipboard pasted with the {@code SHAPE_LINE} label. The shape's mode picks the paste's
 * Into: Place pastes everything, Place in air only into air, Paint only over existing blocks, Carve writes air cells
 * (pasted with air included).
 *
 * <p><b>Cells.</b> The path is sampled every {@value #SPACING} blocks; at each sample the shape's box is placed as a dab
 * of the brush would place it there ({@link ShapeStamp#box}), keeping its facing, and the sweep is every cell of every
 * such shape (solid). With a hollow thickness it is the sweep's shell instead: a cell of the sweep with a cell outside it
 * within that many cells along one of the six axis directions, the regions' shell rule. An open line's shell is a tube
 * open at both ends (the sweep is judged as if it went on past them); a closed curve's is a closed ring.
 */
public final class ShapeSweep {
    /** The path is sampled this far apart (blocks): consecutive shapes are at most a block apart, so they touch. */
    static final double SPACING = 0.5;
    /** A hollow sweep holds its solid while it finds the shell: at most this many times the cap, and this many cells. */
    static final long SOLID_FACTOR = 16;
    static final long MAX_SOLID_CELLS = 1L << 28;

    private ShapeSweep() {}

    /**
     * The swept cells, with states of {@code states}: the shape of {@code radius} placed at every sample of the path,
     * of {@code material}, or air when the shape carves ({@code material} may then be {@code null}).
     *
     * @throws GeneratedTooLargeException when the sweep would have more than {@code maxCells} cells
     */
    public static GeneratedSource generate(PathSpec path, int radius, ShapeSpec shape, Pattern material,
                                           StateSpace states, long maxCells) throws GeneratedTooLargeException {
        return generate(path, radius, shape, material, states, maxCells, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /**
     * {@link #generate(PathSpec, int, ShapeSpec, Pattern, StateSpace, long)} within {@code [bottomY, topYExclusive)}: the
     * cells above or below are left out (and not counted).
     */
    public static GeneratedSource generate(PathSpec path, int radius, ShapeSpec shape, Pattern material,
                                           StateSpace states, long maxCells, int bottomY, int topYExclusive)
            throws GeneratedTooLargeException {
        Objects.requireNonNull(path);
        Objects.requireNonNull(shape);
        Objects.requireNonNull(states);
        if (radius < BrushSpec.MIN_RADIUS || radius > BrushSpec.MAX_RADIUS) {
            throw new IllegalArgumentException("Radius must be " + BrushSpec.MIN_RADIUS + "-" + BrushSpec.MAX_RADIUS);
        }
        if (shape.usesMaterial()) {
            Objects.requireNonNull(material, "A shape that places blocks needs a material");
            if (!(material instanceof Pattern.Single || material instanceof Pattern.Weighted
                    || material instanceof Pattern.Arranged)) {
                throw new IllegalArgumentException("A shape line's material is a block or a mix: " + material);
            }
        }
        // Every block of the path's length adds a cell at least every √3 blocks: refuse a hopeless one at once.
        if (PathSampler.chordLength(path) / StrictMath.sqrt(3) > (double) maxCells + 1) {
            throw new GeneratedTooLargeException(maxCells);
        }
        List<PathSample> samples;
        try {
            samples = PathSampler.sample(path, SPACING);
        } catch (IllegalArgumentException tooManySamples) {
            throw new GeneratedTooLargeException(maxCells);
        }
        // A hollow sweep's shell is found over the whole sweep, then clipped: the build height does not close the tube.
        Sweeper sweeper = shape.hollow() == 0 ? new Sweeper(shape, radius, bottomY, topYExclusive)
                : new Sweeper(shape, radius, Integer.MIN_VALUE, Integer.MAX_VALUE);
        GeneratedSource.Builder out = GeneratedSource.builder(maxCells);
        int air = states.air();
        try {
            if (shape.hollow() == 0) {
                CellBits solid = new CellBits(maxCells);
                sweeper.sweep(samples, solid);
                solid.forEach((x, y, z) -> out.set(x, y, z, stateAt(shape, material, states, air, x, y, z)));
                return out.build();
            }
            long solidCap = StrictMath.min(MAX_SOLID_CELLS, maxCells > MAX_SOLID_CELLS / SOLID_FACTOR
                    ? MAX_SOLID_CELLS : maxCells * SOLID_FACTOR);
            CellBits solid = new CellBits(solidCap);
            sweeper.sweep(samples, solid);
            // Past an open line's ends the sweep goes on (for the shell only): the tube stays open there.
            CellBits beyond = new CellBits(solidCap);
            if (!PathSampler.closed(path)) sweeper.beyondEnds(samples, shape.hollow(), beyond);
            int t = shape.hollow();
            solid.forEach((x, y, z) -> {
                if (y >= bottomY && y < topYExclusive && shell(solid, beyond, x, y, z, t)) {
                    out.set(x, y, z, stateAt(shape, material, states, air, x, y, z));
                }
            });
            return out.build();
        } catch (CellBits.FullException full) {
            throw new GeneratedTooLargeException(maxCells);
        }
    }

    private static int stateAt(ShapeSpec shape, Pattern material, StateSpace states, int air, int x, int y, int z) {
        return shape.usesMaterial() ? material.apply(states, x, y, z, -1) : air;
    }

    /** Whether a cell outside the sweep (and past its ends) lies within {@code t} cells of (x, y, z) along an axis. */
    private static boolean shell(CellBits solid, CellBits beyond, int x, int y, int z, int t) {
        for (int k = 1; k <= t; k++) {
            if (outside(solid, beyond, x + k, y, z) || outside(solid, beyond, x - k, y, z)
                    || outside(solid, beyond, x, y + k, z) || outside(solid, beyond, x, y - k, z)
                    || outside(solid, beyond, x, y, z + k) || outside(solid, beyond, x, y, z - k)) {
                return true;
            }
        }
        return false;
    }

    private static boolean outside(CellBits solid, CellBits beyond, int x, int y, int z) {
        return !solid.contains(x, y, z) && !beyond.contains(x, y, z);
    }

    /** Places the shape along samples: each distinct box once, its rows as runs. */
    private static final class Sweeper {
        private final ShapeSpec shape;
        private final int radius;
        private final int bottomY, topYExclusive;
        /** The shape's rows relative to its box's minimum corner: x from rowLo to rowHi of row (y, z); empty rows lo > hi. */
        private final int[] rowLo, rowHi;
        private final int sy, sz;
        private final int reach;

        Sweeper(ShapeSpec shape, int radius, int bottomY, int topYExclusive) {
            this.shape = shape;
            this.radius = radius;
            this.bottomY = bottomY;
            this.topYExclusive = topYExclusive;
            // A shape's cells depend on its box alone: work them out once, at the origin, and move them.
            Box box = ShapeStamp.box(shape, radius, shape.facing(), 8, 8, 8);
            ShapeKernel.Stamp stamp = new ShapeKernel.Stamp(new ShapeStamp.Placement(box, shape.kind(), shape.facing()), 0);
            sy = stamp.sy;
            sz = stamp.sz;
            rowLo = new int[sy * sz];
            rowHi = new int[sy * sz];
            for (int i = 0; i < rowLo.length; i++) {
                rowLo[i] = stamp.rowLo[i] - stamp.x0;
                rowHi[i] = stamp.rowHi[i] - stamp.x0;
            }
            reach = StrictMath.max(stamp.sx, StrictMath.max(sy, sz));
        }

        void sweep(List<PathSample> samples, CellBits into) {
            BlockPos last = null;
            for (PathSample s : samples) last = place(s.x(), s.y(), s.z(), last, into);
        }

        /**
         * The shapes the sweep would place past an open line's ends, straight on along its first and last direction, as
         * far as a shape and the shell thickness reach.
         */
        void beyondEnds(List<PathSample> samples, int thickness, CellBits into) {
            int n = samples.size();
            if (n < 2) return;
            double distance = reach + thickness + 1;
            extend(samples.get(0), away(samples, 0, 1), distance, into);
            extend(samples.get(n - 1), away(samples, n - 1, -1), distance, into);
        }

        /** The first sample from {@code from} (stepping by {@code step}) at another place, or null. */
        private static PathSample away(List<PathSample> samples, int from, int step) {
            PathSample origin = samples.get(from);
            for (int i = from + step; i >= 0 && i < samples.size(); i += step) {
                PathSample s = samples.get(i);
                if (s.x() != origin.x() || s.y() != origin.y() || s.z() != origin.z()) return s;
            }
            return null;
        }

        /** Shapes from {@code end} on away from {@code inner}, every {@link #SPACING} blocks for {@code distance}. */
        private void extend(PathSample end, PathSample inner, double distance, CellBits into) {
            if (inner == null) return;
            double dx = end.x() - inner.x(), dy = end.y() - inner.y(), dz = end.z() - inner.z();
            double length = StrictMath.sqrt(dx * dx + dy * dy + dz * dz);
            dx /= length;
            dy /= length;
            dz /= length;
            BlockPos last = null;
            int steps = (int) StrictMath.ceil(distance / SPACING);
            for (int k = 1; k <= steps; k++) {
                double e = k * SPACING;
                last = place(end.x() + e * dx, end.y() + e * dy, end.z() + e * dz, last, into);
            }
        }

        /** Places the shape whose box a dab at (x, y, z) gets, unless its box is {@code last}'s; returns its corner. */
        private BlockPos place(double x, double y, double z, BlockPos last, CellBits into) {
            Box box = ShapeStamp.box(shape, radius, shape.facing(), sixteenths(x), sixteenths(y), sixteenths(z));
            BlockPos corner = box.min();
            if (corner.equals(last)) return last;
            for (int ky = 0; ky < sy; ky++) {
                int y0 = corner.y() + ky;
                if (y0 < bottomY || y0 >= topYExclusive) continue;
                for (int kz = 0; kz < sz; kz++) {
                    int row = ky * sz + kz;
                    if (rowLo[row] > rowHi[row]) continue;
                    into.addRun(y0, corner.z() + kz, corner.x() + rowLo[row], corner.x() + rowHi[row]);
                }
            }
            return corner;
        }

        private static long sixteenths(double v) {
            return (long) StrictMath.floor(v * 16 + 0.5);
        }
    }
}
