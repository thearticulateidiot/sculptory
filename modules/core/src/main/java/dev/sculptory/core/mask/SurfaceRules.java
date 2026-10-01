package dev.sculptory.core.mask;

import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import java.util.Objects;

/**
 * A brush's own rule-list mask ({@code SurfaceMask.Rules}) bound to one state space, tested at a column's surface cell
 * as the brush kernels test every surface mask: "is one of these blocks" and the
 * other cell rules look at the state the kernel passes, "height between" at the surface's y, and "slope between" at
 * the steepest cardinal step the kernel measured, exactly as {@code SurfaceMask.SurfaceBlocks}, {@code Elevation} and
 * {@code Slope} do, so a legacy brush mask turned into rules keeps its result. The neighbour rules read the world the
 * kernel reads (before its step). Thread-safe once bound.
 */
public final class SurfaceRules {
    private final BoundRules rules;
    private final boolean off;

    private SurfaceRules(EditMask mask, StateSpace states) {
        this.off = mask.isOff();
        this.rules = off ? null : new BoundRules(mask, states);
    }

    /** {@code mask} bound to {@code states}; bind once per stroke. */
    public static SurfaceRules bind(EditMask mask, StateSpace states) {
        return new SurfaceRules(Objects.requireNonNull(mask), Objects.requireNonNull(states));
    }

    /** Whether a rule looks beyond the cell (needs a world to read). */
    public boolean readsNeighbours() {
        return !off && rules.reachWithoutSlope() > 0;
    }

    /** Whether "slope between" is among the rules (the kernel must measure the slope). */
    public boolean usesSlope() {
        return !off && rules.usesSlope();
    }

    /**
     * Whether the column whose surface cell is (x, y, z), holding {@code state}, with the steepest step {@code slope},
     * passes. {@code world} is read by the neighbour rules only (it may be {@code null} when none reads it).
     */
    public boolean test(int x, int y, int z, int state, int slope, WorldReader world) {
        if (off) return true;
        return rules.test(x, y, z, state, world, Math.max(0, slope), null);
    }
}
