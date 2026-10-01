package dev.sculptory.core.scatter;

/**
 * Why an area column did not become a placement. Every column of the area ends with exactly one outcome or a
 * placement, so the counts of a plan plus its placements add up to its column count.
 *
 * <p>Column outcomes (survey), in check order: {@link #DENSITY}, {@link #UNLOADED} (the column's chunk),
 * {@link #NO_SURFACE}, {@link #WATER} (the column's variant is a water plant and the column has no water for it),
 * {@link #ELEVATION}, {@link #SLOPE}, {@link #SUBSTRATE}, {@link #FILTER}. Columns that pass become candidates.
 *
 * <p>Candidate outcomes (acceptance), in check order: {@link #COUNT_LIMIT} and {@link #WORK_LIMIT} (these two end
 * acceptance: every remaining candidate gets the outcome), {@link #SPACING}, for a column plant {@link #UNLOADED},
 * {@link #WATER} or {@link #COLLISION} (no room for the column height's minimum), {@link #BUDGET}, {@link #COLLISION}
 * (the footprint leaves the build height or the coordinate range), {@link #UNLOADED} (the anchor's chunk or a
 * chunk the footprint reaches), {@link #COLLISION} (an earlier placement's footprint), {@link #SUPPORT} (for a
 * variant that goes on water: {@link #WATER} when the cell below is not still water), {@link #COLLISION} or
 * {@link #WATER} (a world cell that may not be replaced, or that is not still water under an underwater variant),
 * {@link #SURVIVAL} (block variants). A tree or feature candidate is checked instead, after {@link #SPACING}:
 * {@link #UNLOADED} (its anchor's chunk), what its growth says ({@link #FEATURE_FAILED}, {@link #SURVIVAL},
 * {@link #UNLOADED}), {@link #BUDGET}, {@link #UNLOADED} or {@link #PROTECTED} (a column its cells reach), and
 * {@link #COLLISION} (a held or block placement's cell). Whether a candidate is accepted does not depend on this order
 * (each check depends only on the world and the placements accepted before it); only the reported reason does.
 *
 * <p>Names are stable: the server reports them in {@code ScatterPlan.rejectedCounts}. Append only.
 */
public enum Outcome {
    /** Dropped by the density prefilter. */
    DENSITY,
    /** The column's chunk, the anchor's chunk or a chunk the candidate's footprint reaches is not loaded. */
    UNLOADED,
    /** No ground in the scan window, or a structure (stairs, chests...) above it. */
    NO_SURFACE,
    /** The surface y is outside the elevation range. */
    ELEVATION,
    /** The steepest cardinal step is outside the slope range. */
    SLOPE,
    /** The substrate mask rejects the surface block. */
    SUBSTRATE,
    /** The extra surface mask rejects the column. */
    FILTER,
    /** An earlier placement's anchor is closer than the spacing. */
    SPACING,
    /**
     * The transformed footprint overlaps an earlier placement or a world cell it may not replace (only air,
     * replaceable and vegetation cells may be; fluid blocks and waterlogged cells only with
     * {@link ScatterSettings.Fit#allowInFluid}), or leaves the build height.
     */
    COLLISION,
    /**
     * The anchor column has no ground directly below the footprint's lowest layer, or fewer than
     * {@link ScatterSettings.Fit#minSupportFraction} of that layer's cells have ground directly beneath them.
     */
    SUPPORT,
    /** The placement count was reached. */
    COUNT_LIMIT,
    /** The footprint would take the plan past its cell budget. */
    BUDGET,
    /** Acceptance used up its work budget ({@link ScatterPlanner#DEFAULT_MAX_WORK}) before reaching the candidate. */
    WORK_LIMIT,
    /**
     * A footprint cell lies in a column the planner's {@link ScatterPlanner.ColumnGuard} denies (the requester's
     * protection: spawn protection, claims, the world border). Checked after {@link #UNLOADED}.
     */
    PROTECTED,
    /**
     * A block variant that could not stay there in the game (a flower on stone, a cactus away from sand), with
     * {@link ScatterSettings.Fit#survive}. Checked last.
     */
    SURVIVAL,
    /**
     * A water plant's rule failed ({@link BlockVariants.Medium}): an underwater variant over a column whose cell above
     * the ground is not still water, or a footprint (a tall seagrass, a kelp column shorter than the column height's
     * minimum) reaching a cell that is not still water; a variant that goes on water over a column without a still
     * water surface. A column outcome (after {@link #NO_SURFACE}) and a candidate outcome (with the world cells).
     */
    WATER,
    /**
     * A vanilla tree or feature that did not grow on its spot ({@link ScatterSource.Feature}: an ice spike away from snow
     * blocks, a tree with no room, a fungus off nylium), or whose growth failed. A candidate outcome, right after
     * {@link #SPACING} and the anchor's chunk. (Trees whose sapling could not stand there end as {@link #SURVIVAL}.)
     */
    FEATURE_FAILED,
    /**
     * A cell the placement would write fails the global mask (the requester's {@link ScatterPlanner.CellGuard}): the
     * placement, or the tree or feature, is skipped whole, never cut. Checked after the world cells (a tree: after
     * {@link #PROTECTED}).
     */
    MASKED
}
