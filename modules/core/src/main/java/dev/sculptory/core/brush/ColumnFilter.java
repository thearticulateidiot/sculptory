package dev.sculptory.core.brush;

import dev.sculptory.core.edit.CellPredicate;
import dev.sculptory.core.mask.SurfaceRules;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import java.util.List;

/**
 * A {@link SurfaceMask} bound to one state space: decides per column from its surface cell (position and
 * state) and its slope, the largest height step to a cardinal neighbour that has a surface. Public so scatter
 * (M3) evaluates surface masks exactly as the brushes do.
 *
 * <p>A rule-list part ({@link SurfaceMask.Rules}) is tested at the surface cell as the global mask tests a cell
 * ({@link SurfaceRules}); its neighbour rules read the world given to {@link #compile(SurfaceMask, StateSpace,
 * WorldReader)} (the world before the kernel's step).
 */
@FunctionalInterface
public interface ColumnFilter {
    ColumnFilter ALL = (x, y, z, state, slope) -> true;

    boolean test(int x, int y, int z, int state, int slope);

    /**
     * A surface mask bound once (a stroke's), ready to filter the columns of each step against that step's world.
     * Thread-safe.
     */
    @FunctionalInterface
    interface Bound {
        ColumnFilter on(WorldReader world);
    }

    /**
     * {@link #compile(SurfaceMask, StateSpace, WorldReader)} without a world: a rule-list part whose rules read
     * neighbours is refused.
     *
     * @throws IllegalArgumentException for such a part
     */
    static ColumnFilter compile(SurfaceMask mask, StateSpace states) {
        return bind(mask, states, false).on(null);
    }

    /** {@code mask} bound to {@code states}, its rule-list parts reading {@code world}. */
    static ColumnFilter compile(SurfaceMask mask, StateSpace states, WorldReader world) {
        return bind(mask, states).on(world);
    }

    /** {@code mask} bound to {@code states} once, for {@link Bound#on} each step's world. */
    static Bound bind(SurfaceMask mask, StateSpace states) {
        return bind(mask, states, true);
    }

    private static Bound bind(SurfaceMask mask, StateSpace states, boolean withWorld) {
        return switch (mask) {
            case SurfaceMask.Any any -> world -> ALL;
            case SurfaceMask.SurfaceBlocks blocks -> {
                CellPredicate predicate = blocks.mask().bind(states);
                ColumnFilter filter = (x, y, z, state, slope) -> predicate.test(x, y, z, state);
                yield world -> filter;
            }
            case SurfaceMask.Elevation elevation -> {
                int min = elevation.minY(), max = elevation.maxY();
                ColumnFilter filter = (x, y, z, state, slope) -> y >= min && y <= max;
                yield world -> filter;
            }
            case SurfaceMask.Slope range -> {
                int min = range.minStep(), max = range.maxStep();
                ColumnFilter filter = (x, y, z, state, slope) -> slope >= min && slope <= max;
                yield world -> filter;
            }
            case SurfaceMask.And and -> {
                Bound[] children = bindAll(and.masks(), states, withWorld);
                yield world -> {
                    ColumnFilter[] filters = new ColumnFilter[children.length];
                    for (int i = 0; i < filters.length; i++) filters[i] = children[i].on(world);
                    return (x, y, z, state, slope) -> {
                        for (ColumnFilter child : filters) {
                            if (!child.test(x, y, z, state, slope)) return false;
                        }
                        return true;
                    };
                };
            }
            case SurfaceMask.Not not -> {
                Bound child = bind(not.mask(), states, withWorld);
                yield world -> {
                    ColumnFilter filter = child.on(world);
                    return (x, y, z, state, slope) -> !filter.test(x, y, z, state, slope);
                };
            }
            case SurfaceMask.Rules rules -> {
                SurfaceRules bound = SurfaceRules.bind(rules.rules(), states);
                if (!withWorld && bound.readsNeighbours()) {
                    throw new IllegalArgumentException("A rule mask that reads neighbours needs the world");
                }
                yield world -> (x, y, z, state, slope) -> bound.test(x, y, z, state, slope, world);
            }
        };
    }

    private static Bound[] bindAll(List<SurfaceMask> masks, StateSpace states, boolean withWorld) {
        Bound[] bound = new Bound[masks.size()];
        for (int i = 0; i < bound.length; i++) bound[i] = bind(masks.get(i), states, withWorld);
        return bound;
    }

    /** Whether evaluating {@code mask} needs the column's slope anywhere in its tree. */
    static boolean usesSlope(SurfaceMask mask) {
        return switch (mask) {
            case SurfaceMask.Slope slope -> true;
            case SurfaceMask.And and -> and.masks().stream().anyMatch(ColumnFilter::usesSlope);
            case SurfaceMask.Not not -> usesSlope(not.mask());
            case SurfaceMask.Rules rules -> rules.rules().entries().stream()
                    .anyMatch(entry -> entry.rule() instanceof dev.sculptory.core.mask.MaskRule.Slope);
            default -> false;
        };
    }
}
