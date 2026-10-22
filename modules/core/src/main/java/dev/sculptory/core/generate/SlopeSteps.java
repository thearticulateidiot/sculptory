package dev.sculptory.core.generate;

import dev.sculptory.core.path.PathSample;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Use Stairs and Slabs (Generate's Line and Road): on the sloping stretches of a generated volume, the step cells take
 * the stair (or, without one, the slab) of their own block's family instead of the full block, so the climb reads as a
 * ramp. Pure: it changes states only, never adds or removes a cell.
 *
 * <p><b>Step cells.</b> The climb runs along the path's horizontal axis at each cell ({@link Axis}: X or Z, the larger
 * part of the path's level direction there; none where the path runs straight up or down). Along it:
 * <ul>
 *   <li>a <b>top</b> cell (nothing over it) whose neighbour one way along the axis is one lower (its cell beside this one
 *   is empty, the one under that is not), while the other neighbour is level or higher, is the first cell of a step up:
 *   a stair facing up the climb on its bottom half ({@code half=bottom}), else a bottom slab;</li>
 *   <li>a <b>bottom</b> cell (nothing under it) whose neighbour one way is one higher underneath (its cell beside this one
 *   is empty, the one over that is not), while the other neighbour is level or lower, is the last cell before the
 *   underside steps up: an upside-down stair facing down the climb ({@code half=top}), else a top slab.</li>
 * </ul>
 * A cell that is both keeps its top role. Air cells (a road's cleared space) are not part of the volume. Cells whose
 * block has neither a stair nor a slab stay full blocks (counted in {@link Result#plain}).
 */
public final class SlopeSteps {
    /** No climb axis (the path runs straight up or down here, or no path is near). */
    public static final int NONE = -1;
    public static final int AXIS_X = 0;
    public static final int AXIS_Z = 2;
    /** A level direction shorter than this part of the tangent counts as straight up or down. */
    static final double STEEP = 0.1;
    /** The bucket of the samples' index (blocks): a cell finds its nearest sample within one bucket around. */
    private static final int BUCKET = 8;

    private SlopeSteps() {}

    /** The climb's horizontal axis at a cell: {@link #AXIS_X}, {@link #AXIS_Z} or {@link #NONE}. */
    @FunctionalInterface
    public interface Axis {
        int at(int x, int y, int z);
    }

    /** A block family's stair and slab ({@code core.state.StairSlabVariants} in the game). */
    public interface Variants {
        /**
         * The stair of {@code state}'s family turned to {@code facing} (north, east, south, west) on {@code half} (bottom,
         * top), straight, not waterlogged; -1 when it has none.
         */
        int stairs(int state, String facing, String half);

        /** The slab of {@code state}'s family of {@code type} (bottom, top), not waterlogged; -1 when it has none. */
        int slab(int state, String type);
    }

    /** The volume with its step cells turned, how many were, and how many step cells had no stair or slab. */
    public record Result(GeneratedSource source, long stepped, long plain) {
        public Result {
            Objects.requireNonNull(source);
        }
    }

    /** Turns {@code source}'s step cells (see the class); {@code air} is the air state, never part of the volume. */
    public static Result apply(GeneratedSource source, Axis axis, Variants variants, int air) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(axis);
        Objects.requireNonNull(variants);
        if (source.isEmpty()) return new Result(source, 0, 0);
        GeneratedSource.Builder out = GeneratedSource.builder(source.cells());
        long[] counts = new long[2];
        source.forEach((x, y, z, state) -> {
            out.set(x, y, z, state);
            if (state == air) return;
            int a = axis.at(x, y, z);
            if (a == NONE) return;
            int turned = -1;
            boolean step = false;
            if (!solid(source, x, y + 1, z, air)) {
                int d = stepSign(source, x, y, z, a, air, true);
                if (d != 0) {
                    step = true;
                    turned = variants.stairs(state, facing(a, d), "bottom");
                    if (turned < 0) turned = variants.slab(state, "bottom");
                }
            } else if (!solid(source, x, y - 1, z, air)) {
                int d = stepSign(source, x, y, z, a, air, false);
                if (d != 0) {
                    step = true;
                    turned = variants.stairs(state, facing(a, -d), "top");
                    if (turned < 0) turned = variants.slab(state, "top");
                }
            }
            if (!step) return;
            if (turned >= 0) {
                out.set(x, y, z, turned);
                counts[0]++;
            } else {
                counts[1]++;
            }
        });
        return new Result(out.build(), counts[0], counts[1]);
    }

    /**
     * The way up the climb (+1 or -1 along axis {@code a}) when the cell is a step cell, else 0. {@code top}: the
     * neighbour down the climb is one lower; otherwise (the underside) the neighbour up the climb is one higher.
     */
    private static int stepSign(GeneratedSource s, int x, int y, int z, int a, int air, boolean top) {
        int found = 0;
        for (int d = -1; d <= 1; d += 2) {
            int ux = a == AXIS_X ? x + d : x, uz = a == AXIS_Z ? z + d : z;
            int lx = a == AXIS_X ? x - d : x, lz = a == AXIS_Z ? z - d : z;
            boolean step;
            if (top) {
                step = !solid(s, lx, y, lz, air) && solid(s, lx, y - 1, lz, air)
                        && (solid(s, ux, y, uz, air) || solid(s, ux, y + 1, uz, air));
            } else {
                step = !solid(s, ux, y, uz, air) && solid(s, ux, y + 1, uz, air)
                        && (solid(s, lx, y, lz, air) || solid(s, lx, y - 1, lz, air));
            }
            if (step) {
                if (found != 0) return 0; // both ways: a crest or a notch, no climb
                found = d;
            }
        }
        return found;
    }

    private static boolean solid(GeneratedSource s, int x, int y, int z, int air) {
        int state = s.get(x, y, z);
        return state >= 0 && state != air;
    }

    /** The facing of going {@code d} (+1, -1) along axis {@code a}. */
    static String facing(int a, int d) {
        if (a == AXIS_X) return d > 0 ? "east" : "west";
        return d > 0 ? "south" : "north";
    }

    /**
     * The axis of a level direction (dx, dz) against its tangent's full length, or {@link #NONE} when nearly upright:
     * the axis of the larger part, and {@link #AXIS_X} when the two are equal, so stairs on a 45-degree line or road
     * always step along x, whichever way it runs.
     */
    public static int axisOf(double dx, double dy, double dz) {
        double level = Math.sqrt(dx * dx + dz * dz), length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(length > 0) || level < STEEP * length) return NONE;
        return Math.abs(dx) >= Math.abs(dz) ? AXIS_X : AXIS_Z;
    }

    /**
     * The climb axis along a line's samples (in order along it): each cell takes the axis of its nearest sample's
     * direction (from the sample before it to the one after), found among the samples within {@value #BUCKET} blocks or
     * so; {@link #NONE} farther away.
     */
    public static Axis along(List<PathSample> samples) {
        Objects.requireNonNull(samples);
        int n = samples.size();
        int[] axes = new int[n];
        Long2ObjectOpenHashMap<IntArrayList> buckets = new Long2ObjectOpenHashMap<>();
        for (int i = 0; i < n; i++) {
            PathSample from = samples.get(Math.max(0, i - 1)), to = samples.get(Math.min(n - 1, i + 1));
            axes[i] = axisOf(to.x() - from.x(), to.y() - from.y(), to.z() - from.z());
            PathSample s = samples.get(i);
            buckets.computeIfAbsent(bucket(floorDiv(s.x()), floorDiv(s.y()), floorDiv(s.z())), k -> new IntArrayList()).add(i);
        }
        return (x, y, z) -> {
            double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
            int bx = Math.floorDiv(x, BUCKET), by = Math.floorDiv(y, BUCKET), bz = Math.floorDiv(z, BUCKET);
            int best = -1;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (int i = -1; i <= 1; i++) {
                for (int j = -1; j <= 1; j++) {
                    for (int k = -1; k <= 1; k++) {
                        IntArrayList in = buckets.get(bucket(bx + i, by + j, bz + k));
                        if (in == null) continue;
                        for (int t = 0; t < in.size(); t++) {
                            PathSample s = samples.get(in.getInt(t));
                            double dx = s.x() - cx, dy = s.y() - cy, dz = s.z() - cz;
                            double distance = dx * dx + dy * dy + dz * dz;
                            if (distance < bestDistance) {
                                bestDistance = distance;
                                best = in.getInt(t);
                            }
                        }
                    }
                }
            }
            return best < 0 ? NONE : axes[best];
        };
    }

    /** The climb axis per column ({@link GroundMap#column} keys), {@link #NONE} for the others: a road's. */
    public static Axis byColumn(Long2IntMap axes) {
        Objects.requireNonNull(axes);
        return (x, y, z) -> axes.containsKey(GroundMap.column(x, z)) ? axes.get(GroundMap.column(x, z)) : NONE;
    }

    private static int floorDiv(double v) {
        return Math.floorDiv((int) Math.floor(v), BUCKET);
    }

    private static long bucket(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }
}
