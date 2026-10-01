package dev.sculptory.core.scatter;

import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * What to scatter and where: validated, immutable input of {@link ScatterPlanner}.
 *
 * @param area the columns to consider
 * @param density which share of the eligible columns become candidates, or how many placements to aim for
 * @param spacing the minimum horizontal (Euclidean) distance between two placement anchors, 0-{@value #MAX_SPACING}
 * @param filters which surfaces may host a placement
 * @param fit how a placement's footprint must sit in the world (fluids, support)
 * @param variants the weighted mix, 1-{@value #MAX_VARIANTS} entries; each names a planner source by index
 * @param transforms the rotations and mirroring placements may use
 * @param seed fixes every random choice: the same settings, world and seed give the same plan
 * @param columnHeight how tall column plants (sugar cane, cactus, bamboo, kelp block variants) grow
 */
public record ScatterSettings(ScatterArea area, Density density, int spacing, Filters filters, Fit fit,
                              List<Variant> variants, Transforms transforms, long seed, ColumnHeight columnHeight) {
    public static final int MAX_SPACING = 64;
    public static final int MAX_VARIANTS = 64;
    public static final int MAX_WEIGHT = 1000;
    /** The most placements one plan holds, whatever the density. */
    public static final int MAX_PLACEMENTS = 1 << 17;
    /** The tallest column a column plant is given. */
    public static final int MAX_COLUMN_HEIGHT = 32;

    public ScatterSettings {
        Objects.requireNonNull(area);
        Objects.requireNonNull(density);
        Objects.requireNonNull(filters);
        Objects.requireNonNull(fit);
        Objects.requireNonNull(transforms);
        Objects.requireNonNull(columnHeight);
        if (spacing < 0 || spacing > MAX_SPACING) throw new IllegalArgumentException("Spacing must be 0-" + MAX_SPACING);
        variants = List.copyOf(variants);
        if (variants.isEmpty() || variants.size() > MAX_VARIANTS) {
            throw new IllegalArgumentException("A scatter needs 1-" + MAX_VARIANTS + " variants");
        }
    }

    /** Settings whose column plants are one block tall ({@link ColumnHeight#ONE}). */
    public ScatterSettings(ScatterArea area, Density density, int spacing, Filters filters, Fit fit,
                           List<Variant> variants, Transforms transforms, long seed) {
        this(area, density, spacing, filters, fit, variants, transforms, seed, ColumnHeight.ONE);
    }

    /**
     * How tall a column plant grows: each placement of one is given a height in {@code [min, max]} from its own hash
     * domain of (seed, x, z), then capped by the room above its anchor (open cells; for kelp, still water up to the
     * surface). A placement whose room is below {@code min} is not made. Other variants ignore it.
     */
    public record ColumnHeight(int min, int max) {
        public static final ColumnHeight ONE = new ColumnHeight(1, 1);

        public ColumnHeight {
            if (min < 1 || max > MAX_COLUMN_HEIGHT || min > max) {
                throw new IllegalArgumentException("Column height must be a range within 1-" + MAX_COLUMN_HEIGHT);
            }
        }
    }

    /** The sum of the variant weights. */
    public int totalWeight() {
        int total = 0;
        for (Variant variant : variants) total += variant.weight();
        return total;
    }

    /** The placement count at which acceptance stops ({@link Outcome#COUNT_LIMIT}). */
    public int countLimit() {
        return density instanceof Density.Count count ? count.target() : MAX_PLACEMENTS;
    }

    /** How many candidates a scatter considers. */
    public sealed interface Density {
        /**
         * Each eligible column becomes a candidate with probability {@code value}, decided by its rank: kept when
         * {@code rank < value × 2⁶⁴} (compared on the top 53 bits). Placements are capped at
         * {@value ScatterSettings#MAX_PLACEMENTS}.
         */
        record Fraction(double value) implements Density {
            public Fraction {
                if (!(value >= 0 && value <= 1)) throw new IllegalArgumentException("Density must be 0-1");
            }
        }

        /** Every eligible column is a candidate; acceptance stops after {@code target} placements. */
        record Count(int target) implements Density {
            public Count {
                if (target < 1 || target > MAX_PLACEMENTS) {
                    throw new IllegalArgumentException("Target count must be 1-" + MAX_PLACEMENTS);
                }
            }
        }
    }

    /**
     * Which surface columns may host a placement. The surface is the ground cell {@code SurfaceScan} finds (the
     * brush definition); a placement's anchor lands on the cell above it. Checked in this order, each with its
     * {@link Outcome}: elevation ({@code [minY, maxY]} on the surface y), slope ({@code [minSlope, maxSlope]} on the
     * steepest step, in blocks, to a cardinal neighbour that has a surface), substrate (the surface state) and
     * finally {@code extra}, any other {@link SurfaceMask} (evaluated as the brushes do).
     */
    public record Filters(int minY, int maxY, int minSlope, int maxSlope, CellMask substrate, SurfaceMask extra) {
        public static final Filters NONE = new Filters(Integer.MIN_VALUE, Integer.MAX_VALUE, 0, Integer.MAX_VALUE,
                CellMask.ANY, SurfaceMask.ANY);

        public Filters {
            Objects.requireNonNull(substrate);
            Objects.requireNonNull(extra);
            if (minY > maxY) throw new IllegalArgumentException("Inverted elevation range");
            if (minSlope < 0 || minSlope > maxSlope) throw new IllegalArgumentException("Invalid slope range");
        }

        public Filters withElevation(int min, int max) {
            return new Filters(min, max, minSlope, maxSlope, substrate, extra);
        }

        public Filters withSlope(int min, int max) {
            return new Filters(minY, maxY, min, max, substrate, extra);
        }

        public Filters withSubstrate(CellMask mask) {
            return new Filters(minY, maxY, minSlope, maxSlope, mask, extra);
        }

        public Filters withExtra(SurfaceMask mask) {
            return new Filters(minY, maxY, minSlope, maxSlope, substrate, mask);
        }

        /** Whether evaluating these filters needs the column's slope. */
        public boolean needsSlope() {
            return minSlope > 0 || maxSlope != Integer.MAX_VALUE || !(extra instanceof SurfaceMask.Any);
        }

        /**
         * The same test as {@code mask}, split for reporting: the top-level conjuncts that are elevation ranges,
         * slope ranges and surface-block masks become the elevation, slope and substrate filters (ranges
         * intersected, masks and-ed); everything else (and a range whose intersection would be empty) goes into
         * {@code extra}.
         */
        public static Filters of(SurfaceMask mask) {
            Objects.requireNonNull(mask);
            List<SurfaceMask> conjuncts = new ArrayList<>();
            flatten(mask, conjuncts);
            int minY = Integer.MIN_VALUE, maxY = Integer.MAX_VALUE, minSlope = 0, maxSlope = Integer.MAX_VALUE;
            List<CellMask> substrate = new ArrayList<>();
            List<SurfaceMask> extra = new ArrayList<>();
            for (SurfaceMask conjunct : conjuncts) {
                switch (conjunct) {
                    case SurfaceMask.Any any -> {}
                    case SurfaceMask.Elevation range -> {
                        int min = Math.max(minY, range.minY()), max = Math.min(maxY, range.maxY());
                        if (min > max) {
                            extra.add(range);
                        } else {
                            minY = min;
                            maxY = max;
                        }
                    }
                    case SurfaceMask.Slope range -> {
                        int min = Math.max(minSlope, range.minStep()), max = Math.min(maxSlope, range.maxStep());
                        if (min > max) {
                            extra.add(range);
                        } else {
                            minSlope = min;
                            maxSlope = max;
                        }
                    }
                    case SurfaceMask.SurfaceBlocks blocks -> substrate.add(blocks.mask());
                    // A rule-list mask is tested as a whole (ColumnFilter), like any other extra part.
                    case SurfaceMask.Rules rules -> extra.add(rules);
                    default -> extra.add(conjunct);
                }
            }
            CellMask substrateMask = switch (substrate.size()) {
                case 0 -> CellMask.ANY;
                case 1 -> substrate.get(0);
                default -> {
                    try {
                        yield new CellMask.And(substrate);
                    } catch (IllegalArgumentException tooDeep) {
                        // Surface-block masks may each be as deep as a cell mask can be: keep the rest as they came.
                        for (CellMask rest : substrate.subList(1, substrate.size())) {
                            extra.add(new SurfaceMask.SurfaceBlocks(rest));
                        }
                        yield substrate.get(0);
                    }
                }
            };
            SurfaceMask extraMask = switch (extra.size()) {
                case 0 -> SurfaceMask.ANY;
                case 1 -> extra.get(0);
                default -> new SurfaceMask.And(extra);
            };
            return new Filters(minY, maxY, minSlope, maxSlope, substrateMask, extraMask);
        }

        private static void flatten(SurfaceMask mask, List<SurfaceMask> into) {
            if (mask instanceof SurfaceMask.And and) {
                for (SurfaceMask child : and.masks()) flatten(child, into);
            } else {
                into.add(mask);
            }
        }
    }

    /**
     * How a placement's footprint (its present, non-air source cells) must sit in the world. Its <i>base</i> is the
     * footprint's lowest layer.
     *
     * <ul>
     *   <li><b>Collision:</b> every footprint cell must replace an open world cell (air, replaceable, vegetation)
     *       that is not a fluid block or waterlogged, unless {@code allowInFluid}. So by default no asset lands on a
     *       lake bed or in a river; aquatic assets (a reef schematic) set {@code allowInFluid}. Base cells buried in
     *       ground collide like any other cell. Underwater block variants ({@link BlockVariants.Medium#UNDERWATER}:
     *       seagrass, kelp, coral) instead need still water in every cell (the water source block; with
     *       {@code allowInFluid}, also an open cell holding still water, so existing seagrass may be replaced).</li>
     *   <li><b>Support:</b> the anchor column must have ground directly below the base's level (for an asset
     *       whose base is at its anchor, that is the surface the candidate stands on, so it holds; an asset
     *       anchored below its base would float), and at least {@code minSupportFraction} of the base cells must
     *       have ground directly beneath (still water, for a block variant that goes on water: a lily pad). The other
     *       base cells may hang over open cells.</li>
     *   <li><b>Survival:</b> with {@code survive}, a block variant ({@link ScatterSource.Block}) goes only where the
     *       block could stay in the game (the planner's {@link ScatterPlanner.SurvivalCheck}: vanilla's
     *       {@code canPlaceAt} on a server). Other variants are unaffected.</li>
     * </ul>
     *
     * @param allowInFluid whether footprint cells may replace fluid blocks and waterlogged cells
     * @param minSupportFraction the share of base cells, 0-1, that need ground directly beneath
     * @param survive whether block variants go only where they can survive
     */
    public record Fit(boolean allowInFluid, double minSupportFraction, boolean survive) {
        public static final Fit DEFAULT = new Fit(false, 0.5, true);

        /** Block variants only where they can survive. */
        public Fit(boolean allowInFluid, double minSupportFraction) {
            this(allowInFluid, minSupportFraction, true);
        }

        public Fit {
            if (!(minSupportFraction >= 0 && minSupportFraction <= 1)) {
                throw new IllegalArgumentException("Support fraction must be 0-1");
            }
        }

        public Fit withAllowInFluid(boolean allow) {
            return new Fit(allow, minSupportFraction, survive);
        }

        public Fit withMinSupportFraction(double fraction) {
            return new Fit(allowInFluid, fraction, survive);
        }

        public Fit withSurvive(boolean survives) {
            return new Fit(allowInFluid, minSupportFraction, survives);
        }
    }

    /**
     * One entry of the mix.
     *
     * @param source the index of the variant's content in the planner's source list
     * @param weight the relative frequency, 1-{@value ScatterSettings#MAX_WEIGHT}
     * @param turns the quarter turns this variant allows, as a bit mask (bit k: k clockwise turns); for example a
     *     library asset's {@code AssetInfo.rotations}. See {@link Transforms#forVariant}.
     */
    public record Variant(int source, int weight, int turns) {
        public static final int ALL_TURNS = 0b1111;

        public Variant {
            if (source < 0) throw new IllegalArgumentException("Negative source index");
            if (weight < 1 || weight > MAX_WEIGHT) throw new IllegalArgumentException("Weight must be 1-" + MAX_WEIGHT);
            if (turns < 1 || turns > ALL_TURNS) throw new IllegalArgumentException("A variant must allow a rotation");
        }

        /** A variant that allows every rotation. */
        public Variant(int source, int weight) {
            this(source, weight, ALL_TURNS);
        }

        /** The turn mask of a list of clockwise quarter turns (each 0-3), such as {@code AssetInfo.rotations}. */
        public static int turnMask(Collection<Integer> quarterTurns) {
            int mask = 0;
            for (int turn : quarterTurns) {
                if (turn < 0 || turn > 3) throw new IllegalArgumentException("Rotation must be 0-3 quarter turns: " + turn);
                mask |= 1 << turn;
            }
            return mask;
        }
    }

    /**
     * The transforms placements may use: the clockwise quarter turns in the {@code turns} bit mask (bit k: k
     * turns), each unmirrored and, when {@code mirror} is set, also mirrored along x. (A z mirror is an x mirror
     * plus a half turn, so these cover every orientation.)
     */
    public record Transforms(int turns, boolean mirror) {
        public static final Transforms NONE = new Transforms(1, false);
        public static final Transforms ALL = new Transforms(Variant.ALL_TURNS, true);

        public Transforms {
            if (turns < 1 || turns > Variant.ALL_TURNS) throw new IllegalArgumentException("Allow at least one rotation");
        }

        /** The allowed transforms in a fixed order: unmirrored first, turns ascending. */
        public List<Transform> list() {
            return list(turns);
        }

        /**
         * The transforms a variant may use: these, restricted to the variant's own turns. When the two share no
         * turn, the variant's turns win (an asset's own constraint is a hard one), still with this mirroring.
         */
        public List<Transform> forVariant(Variant variant) {
            int shared = turns & variant.turns();
            return list(shared != 0 ? shared : variant.turns());
        }

        /** The smallest turn set and mirror flag that cover {@code transforms} (empty: no transform). */
        public static Transforms covering(Collection<Transform> transforms) {
            int turns = 0;
            boolean mirror = false;
            for (Transform t : transforms) {
                // (k, Z) is geometrically (k + 2, X).
                int k = t.mirror() == Mirror.Z ? (t.quarterTurnsCw() + 2) & 3 : t.quarterTurnsCw();
                turns |= 1 << k;
                mirror |= t.mirror() != Mirror.NONE;
            }
            return turns == 0 ? NONE : new Transforms(turns, mirror);
        }

        private List<Transform> list(int mask) {
            List<Transform> list = new ArrayList<>(8);
            for (Mirror m : mirror ? List.of(Mirror.NONE, Mirror.X) : List.of(Mirror.NONE)) {
                for (int k = 0; k < 4; k++) {
                    if ((mask & (1 << k)) != 0) list.add(new Transform(k, m));
                }
            }
            return List.copyOf(list);
        }
    }
}
