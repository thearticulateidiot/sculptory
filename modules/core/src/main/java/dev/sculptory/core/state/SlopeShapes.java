package dev.sculptory.core.state;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.transform.CellResampler;
import java.util.List;
import java.util.Objects;

/**
 * Stairs and slabs on turned slopes ("smooth slopes"): after a free orientation is resampled, a full block on the top
 * or the underside of a slope becomes the stair or slab of its family ({@link StairSlabVariants}) when that follows the
 * turned shape more closely.
 *
 * <p><b>How a cell is judged.</b> The turned shape's occupancy of each eighth of the result cell is measured: 8 points
 * per eighth (a 4×4×4 grid) are turned back into the source ({@link CellResampler#back}) and counted when they fall on
 * a block that has collision ({@link CellResampler.SourceCells#solid}, minus {@link StateFlags#NO_COLLISION}). A cell
 * is on a top surface when its four lower eighths are each at least half full and its upper ones are not all; on an
 * underside the other way round; any other cell keeps its block (insides, walls, edges seen from the side). On a
 * surface the upper (or lower) four eighths are matched against the full block, the slab ({@code bottom} or
 * {@code top}), and every stair standing on that half (four facings, five shapes; {@link StateTips#stairEighths}), and
 * the closest fit wins: the full block on a tie, then the slab, then the stairs in a fixed order. So a stair's tall side
 * is towards the higher ground: it faces up the slope, and its open step down it. Only full blocks of a family with a
 * stair or slab change; a cell the resampler leaves empty stays empty, so the block count is kept.
 *
 * <p>A quick test comes first: when all eight eighths' centres fall on blocks the cell keeps its block, so insides cost
 * 8 points, not 64. Deterministic; thread-safe like {@link StairSlabVariants}.
 */
public final class SlopeShapes {
    /** Keep the block. */
    public static final int KEEP = -1;
    static final List<String> FACINGS = List.of("north", "east", "south", "west");
    static final List<String> SHAPES = List.of("straight", "inner_left", "inner_right", "outer_left", "outer_right");
    private static final double[] STEPS = {-0.375, -0.125, 0.125, 0.375};

    private final StairSlabVariants variants;
    private final StateSpace states;
    /** Per (facing, half, shape) code: the eighths the stair fills. */
    private static final int[] STAIR_EIGHTHS = stairEighths();

    public SlopeShapes(StairSlabVariants variants) {
        this.variants = Objects.requireNonNull(variants);
        this.states = variants.states();
    }

    public StairSlabVariants variants() {
        return variants;
    }

    /**
     * The state result cell (x, y, z) of {@code r} should hold instead of {@code written} (the turned state the copy
     * writes there), or {@link #KEEP}.
     */
    public int shape(CellResampler r, CellResampler.SourceCells cells, int x, int y, int z, int written) {
        boolean stairs = variants.stairs(written) >= 0;
        boolean slab = variants.slab(written) >= 0;
        if (!stairs && !slab) return KEEP;
        double[] eighths = new double[8];
        if (!measure(r, cells, x, y, z, eighths)) return KEEP;
        int code = choose(eighths, stairs, slab);
        return state(written, code);
    }

    /**
     * The occupancy of each eighth of result cell (x, y, z), into {@code out} (bit {@code (x << 2) | (y << 1) | z} for
     * the eighth on the positive side of each axis where the coordinate is 1); false, with {@code out} unspecified, when
     * the quick test finds the cell full.
     */
    boolean measure(CellResampler r, CellResampler.SourceCells cells, int x, int y, int z, double[] out) {
        BlockPos size = r.sourceSize();
        double[] p = new double[3];
        boolean full = true;
        for (int e = 0; e < 8 && full; e++) {
            full = covered(r, cells, size, x + 0.5 + ((e >> 2) & 1) * 0.5 - 0.25, y + 0.5 + ((e >> 1) & 1) * 0.5 - 0.25,
                    z + 0.5 + (e & 1) * 0.5 - 0.25, p);
        }
        if (full) return false;
        java.util.Arrays.fill(out, 0);
        for (int iy = 0; iy < 4; iy++) {
            for (int iz = 0; iz < 4; iz++) {
                for (int ix = 0; ix < 4; ix++) {
                    if (!covered(r, cells, size, x + 0.5 + STEPS[ix], y + 0.5 + STEPS[iy], z + 0.5 + STEPS[iz], p)) {
                        continue;
                    }
                    out[((ix >> 1) << 2) | ((iy >> 1) << 1) | (iz >> 1)] += 1.0 / 8;
                }
            }
        }
        return true;
    }

    private boolean covered(CellResampler r, CellResampler.SourceCells cells, BlockPos size, double x, double y,
                                   double z, double[] p) {
        r.back(x, y, z, p);
        int sx = (int) Math.floor(p[0]), sy = (int) Math.floor(p[1]), sz = (int) Math.floor(p[2]);
        if (sx < 0 || sy < 0 || sz < 0 || sx >= size.x() || sy >= size.y() || sz >= size.z()) return false;
        int state = cells.state(sx, sy, sz);
        return state >= 0 && cells.solid(state) && !StateFlags.has(states.flags(state), StateFlags.NO_COLLISION);
    }


    /**
     * The best fit for a cell's eighths: {@link #KEEP} (the full block), {@code 0} a bottom slab, {@code 1} a top slab,
     * or {@code 2 + stair code} ({@link #stairCode}).
     */
    static int choose(double[] eighths, boolean stairs, boolean slab) {
        boolean bottom = halfFull(eighths, 0), top = halfFull(eighths, 1);
        if (bottom == top) return KEEP;
        int standing = bottom ? 0 : 1;
        int free = 1 - standing;
        double best = 0;
        for (int e = 0; e < 8; e++) if (((e >> 1) & 1) == free) best += 1 - eighths[e];
        int choice = KEEP;
        if (slab) {
            double error = 0;
            for (int e = 0; e < 8; e++) if (((e >> 1) & 1) == free) error += eighths[e];
            if (error < best - 1e-9) {
                best = error;
                choice = standing;
            }
        }
        if (stairs) {
            for (int f = 0; f < FACINGS.size(); f++) {
                for (int s = 0; s < SHAPES.size(); s++) {
                    int code = stairCode(f, standing, s);
                    int bits = STAIR_EIGHTHS[code];
                    double error = 0;
                    for (int e = 0; e < 8; e++) {
                        if (((e >> 1) & 1) != free) continue;
                        error += Math.abs(eighths[e] - ((bits >> e) & 1));
                    }
                    if (error < best - 1e-9) {
                        best = error;
                        choice = 2 + code;
                    }
                }
            }
        }
        return choice;
    }

    /** Whether each eighth of the lower ({@code half} 0) or upper (1) half is at least half full. */
    private static boolean halfFull(double[] eighths, int half) {
        for (int e = 0; e < 8; e++) {
            if (((e >> 1) & 1) == half && eighths[e] < 0.5) return false;
        }
        return true;
    }

    /** A stair's code: facing (north, east, south, west), half (0 bottom, 1 top), shape ({@link #SHAPES}). */
    static int stairCode(int facing, int half, int shape) {
        return (facing * 2 + half) * SHAPES.size() + shape;
    }

    private static int[] stairEighths() {
        int[] out = new int[FACINGS.size() * 2 * SHAPES.size()];
        for (int f = 0; f < FACINGS.size(); f++) {
            for (int h = 0; h < 2; h++) {
                for (int s = 0; s < SHAPES.size(); s++) {
                    out[stairCode(f, h, s)] = StateTips.stairEighths(FACINGS.get(f), h == 0 ? "bottom" : "top",
                            SHAPES.get(s));
                }
            }
        }
        return out;
    }

    /** The state for a {@link #choose} result on {@code written}'s family, or {@link #KEEP}. */
    int state(int written, int code) {
        if (code == KEEP) return KEEP;
        int out;
        if (code < 2) {
            out = variants.slab(written, code == 0 ? "bottom" : "top");
        } else {
            int c = code - 2;
            int shape = c % SHAPES.size();
            int half = (c / SHAPES.size()) % 2;
            int facing = c / SHAPES.size() / 2;
            out = variants.stairs(written, FACINGS.get(facing), half == 0 ? "bottom" : "top", SHAPES.get(shape));
        }
        return out >= 0 ? out : KEEP;
    }
}
