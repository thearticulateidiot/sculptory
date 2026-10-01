package dev.sculptory.core.mask;

import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.SplitMix64;
import dev.sculptory.core.brush.SurfaceScan;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * An {@link EditMask} bound to one state space ({@link EditMask#bind}): each rule turned into a test over state handles
 * (block sets and tags expanded into bit sets once), the cheap rules that read the cell alone tested first.
 *
 * <p><b>Reads.</b> A rule that looks at a neighbour reads the view the caller passes (the world before the edit). A
 * neighbour in a chunk column other than the tested cell's is read only when {@link WorldReader#isLoaded} says so; a
 * cell there that is not loaded matches no rule (not a block of a set, not air). Outside the build height the view
 * gives air.
 *
 * <p><b>Slope</b> at a cell: the surface of its column is the one {@link SurfaceScan} finds scanning down from
 * {@code REACH_LIMIT} blocks above the cell to {@code REACH_LIMIT} below it (inside the build height); a column without
 * one there fails the rule. The step to each cardinal neighbour is the difference to that column's surface in the same
 * window (0 when it has none or is not loaded), and the steepest of the four must lie in the range: what
 * {@code SurfaceMask.Slope} measures, at the cell's own height. A brush's own mask passes the slope its kernel measured
 * instead ({@link SurfaceRules}).
 *
 * <p>Thread-safe: the state is immutable; per-call caches live in a {@link Scratch} the caller owns.
 */
final class BoundRules implements BoundMask {
    /** Passed as {@code slope} when the rule measures it from the view. */
    static final int MEASURE_SLOPE = -1;

    private final StateSpace states;
    private final Rule[] rules;
    private final boolean invertAll;
    private final int reach;
    private final boolean slope;

    BoundRules(EditMask mask, StateSpace states) {
        this.states = states;
        List<Rule> bound = new ArrayList<>(mask.entries().size());
        for (MaskEntry entry : mask.entries()) bound.add(bind(entry, states));
        // The cell-only rules first: an AND fails at its first false part, and they cost the least.
        bound.sort(Comparator.comparingInt(Rule::cost));
        this.rules = bound.toArray(new Rule[0]);
        this.invertAll = mask.invertAll();
        int r = 0;
        boolean s = false;
        for (Rule rule : rules) {
            r = Math.max(r, rule.reach());
            s |= rule instanceof SlopeRule;
        }
        this.reach = r;
        this.slope = s;
    }

    @Override
    public boolean test(int x, int y, int z, int before, WorldReader beforeView) {
        return test(x, y, z, before, beforeView, MEASURE_SLOPE, null);
    }

    @Override
    public int reach() {
        return reach;
    }

    /** The reach of the rules other than a slope (which a brush kernel measures itself). */
    int reachWithoutSlope() {
        int r = 0;
        for (Rule rule : rules) {
            if (!(rule instanceof SlopeRule)) r = Math.max(r, rule.reach());
        }
        return r;
    }

    /** Whether a {@link MaskRule.Slope} is among the rules. */
    boolean usesSlope() {
        return slope;
    }

    /**
     * {@link #test(int, int, int, int, WorldReader)}, with the slope given ({@code >= 0}) or measured from the view
     * ({@link #MEASURE_SLOPE}), and column surfaces cached in {@code scratch} (may be {@code null}).
     */
    boolean test(int x, int y, int z, int before, WorldReader view, int slope, Scratch scratch) {
        boolean all = true;
        for (Rule rule : rules) {
            if (rule.matches(this, x, y, z, before, view, slope, scratch) == rule.not) {
                all = false;
                break;
            }
        }
        return all != invertAll;
    }

    StateSpace states() {
        return states;
    }

    // ------------------------------------------------------------------ binding

    private static Rule bind(MaskEntry entry, StateSpace states) {
        boolean not = entry.not();
        return switch (entry.rule()) {
            case MaskRule.Is is -> new SetRule(not, SetRule.CELL, states(is.blocks(), states));
            case MaskRule.OnTopOf on -> new SetRule(not, SetRule.BELOW, states(on.blocks(), states));
            case MaskRule.Under under -> new SetRule(not, SetRule.ABOVE, states(under.blocks(), states));
            case MaskRule.NextTo next -> new SetRule(not, SetRule.AROUND, states(next.blocks(), states));
            case MaskRule.Exposed exposed -> new ExposedRule(not);
            case MaskRule.NotAir notAir -> new FlagRule(not, StateFlags.AIR, false);
            case MaskRule.Solid solid -> new FlagRule(not, StateFlags.TERRAIN_SOLID, true);
            case MaskRule.Height height -> new HeightRule(not, height.minY(), height.maxY());
            case MaskRule.Slope slope -> new SlopeRule(not, slope.minStep(), slope.maxStep());
            case MaskRule.Inside inside -> {
                if (inside.region() instanceof Region.Uploaded) {
                    throw new IllegalArgumentException("An uploaded region must be resolved before the mask is bound");
                }
                yield new InsideRule(not, inside.region());
            }
            case MaskRule.Chance chance -> new ChanceRule(not, chance.percent(), chance.seed());
        };
    }

    /**
     * The handles of {@code set}'s blocks, tags and exact states in {@code states}; a state it does not know matches
     * nothing.
     */
    static BitSet states(BlockSet set, StateSpace states) {
        Set<NamespacedId> blocks = new HashSet<>();
        List<NamespacedId> tags = new ArrayList<>();
        BitSet handles = new BitSet(states.size());
        for (BlockSet.Entry entry : set.entries()) {
            switch (entry) {
                case BlockSet.Block block -> blocks.add(block.id());
                case BlockSet.Tag tag -> tags.add(tag.tag());
                case BlockSet.State state -> {
                    int handle = states.resolve(state.state());
                    if (handle >= 0 && handle < states.size()) handles.set(handle);
                }
            }
        }
        if (!blocks.isEmpty() || !tags.isEmpty()) {
            for (int h = 0; h < states.size(); h++) {
                if (handles.get(h)) continue;
                if (!blocks.isEmpty() && blocks.contains(states.blockId(h))) {
                    handles.set(h);
                    continue;
                }
                for (NamespacedId tag : tags) {
                    if (states.inTag(h, tag)) {
                        handles.set(h);
                        break;
                    }
                }
            }
        }
        return handles;
    }

    // ------------------------------------------------------------------ reads

    /**
     * The state at (nx, ny, nz), a neighbour of (x, z)'s column, or -1 when its chunk is another one and not loaded.
     */
    static int neighbour(WorldReader view, int x, int z, int nx, int ny, int nz) {
        if (((nx >> 4) != (x >> 4) || (nz >> 4) != (z >> 4)) && !view.isLoaded(nx >> 4, nz >> 4)) return -1;
        return view.get(nx, ny, nz);
    }

    /** Per-call caches: column surfaces for {@link MaskRule.Slope}. Not thread-safe; one per compute call or step. */
    static final class Scratch {
        private Long2IntOpenHashMap surfaces;

        /** The surface of column (x, z) in the window [bottom, top] that cell height {@code y} gives (cached by y). */
        int surface(WorldReader view, StateSpace states, int x, int z, int y, int top, int bottom) {
            if (surfaces == null) {
                surfaces = new Long2IntOpenHashMap();
                surfaces.defaultReturnValue(Integer.MAX_VALUE);
            }
            long key = ((x & 0x3FFFFFFL) << 38) | ((z & 0x3FFFFFFL) << 12) | (y & 0xFFFL);
            int cached = surfaces.get(key);
            if (cached != Integer.MAX_VALUE) return cached;
            int found = SurfaceScan.scan(view, states, x, z, top, bottom, null);
            surfaces.put(key, found);
            return found;
        }
    }

    // ------------------------------------------------------------------ rules

    private abstract static class Rule {
        final boolean not;

        Rule(boolean not) {
            this.not = not;
        }

        abstract boolean matches(BoundRules mask, int x, int y, int z, int before, WorldReader view, int slope,
                                 Scratch scratch);

        /** How far the rule reads: 0 for the cell alone. */
        int reach() {
            return 0;
        }

        /** Evaluation order: cell-only rules first, slope scans last. */
        int cost() {
            return reach();
        }
    }

    private static final class SetRule extends Rule {
        static final int CELL = 0, BELOW = 1, ABOVE = 2, AROUND = 3;
        private final int where;
        private final BitSet set;

        SetRule(boolean not, int where, BitSet set) {
            super(not);
            this.where = where;
            this.set = set;
        }

        @Override
        boolean matches(BoundRules mask, int x, int y, int z, int before, WorldReader view, int slope, Scratch scratch) {
            return switch (where) {
                case CELL -> in(before);
                case BELOW -> in(view.get(x, y - 1, z));
                case ABOVE -> in(view.get(x, y + 1, z));
                default -> in(view.get(x, y - 1, z)) || in(view.get(x, y + 1, z))
                        || in(neighbour(view, x, z, x - 1, y, z)) || in(neighbour(view, x, z, x + 1, y, z))
                        || in(neighbour(view, x, z, x, y, z - 1)) || in(neighbour(view, x, z, x, y, z + 1));
            };
        }

        private boolean in(int state) {
            return state >= 0 && set.get(state);
        }

        @Override
        int reach() {
            return where == CELL ? 0 : 1;
        }
    }

    private static final class ExposedRule extends Rule {
        ExposedRule(boolean not) {
            super(not);
        }

        @Override
        boolean matches(BoundRules mask, int x, int y, int z, int before, WorldReader view, int slope, Scratch scratch) {
            StateSpace states = mask.states;
            return air(states, view.get(x, y - 1, z)) || air(states, view.get(x, y + 1, z))
                    || air(states, neighbour(view, x, z, x - 1, y, z)) || air(states, neighbour(view, x, z, x + 1, y, z))
                    || air(states, neighbour(view, x, z, x, y, z - 1)) || air(states, neighbour(view, x, z, x, y, z + 1));
        }

        private static boolean air(StateSpace states, int state) {
            return state >= 0 && StateFlags.has(states.flags(state), StateFlags.AIR);
        }

        @Override
        int reach() {
            return 1;
        }
    }

    /** The cell's own state has the flag ({@code has}) or lacks it. */
    private static final class FlagRule extends Rule {
        private final int flag;
        private final boolean has;

        FlagRule(boolean not, int flag, boolean has) {
            super(not);
            this.flag = flag;
            this.has = has;
        }

        @Override
        boolean matches(BoundRules mask, int x, int y, int z, int before, WorldReader view, int slope, Scratch scratch) {
            return before >= 0 && StateFlags.has(mask.states.flags(before), flag) == has;
        }
    }

    private static final class HeightRule extends Rule {
        private final int min, max;

        HeightRule(boolean not, int min, int max) {
            super(not);
            this.min = min;
            this.max = max;
        }

        @Override
        boolean matches(BoundRules mask, int x, int y, int z, int before, WorldReader view, int slope, Scratch scratch) {
            return y >= min && y <= max;
        }
    }

    private static final class SlopeRule extends Rule {
        private final int min, max;

        SlopeRule(boolean not, int min, int max) {
            super(not);
            this.min = min;
            this.max = max;
        }

        @Override
        boolean matches(BoundRules mask, int x, int y, int z, int before, WorldReader view, int slope, Scratch scratch) {
            int step = slope >= 0 ? slope : measure(mask.states, x, y, z, view, scratch);
            return step >= min && step <= max;
        }

        /** The steepest cardinal step at (x, y, z)'s column (see the class comment), or -1 without a surface. */
        private static int measure(StateSpace states, int x, int y, int z, WorldReader view, Scratch scratch) {
            int top = (int) Math.min((long) y + REACH_LIMIT, (long) view.topYExclusive() - 1);
            int bottom = (int) Math.max((long) y - REACH_LIMIT, view.bottomY());
            if (bottom > top) return -1;
            Scratch cache = scratch != null ? scratch : new Scratch();
            int h = cache.surface(view, states, x, z, y, top, bottom);
            if (h == SurfaceScan.NONE) return -1;
            int step = stepTo(states, view, cache, x, y, z, x, z - 1, top, bottom, h);
            step = Math.max(step, stepTo(states, view, cache, x, y, z, x, z + 1, top, bottom, h));
            step = Math.max(step, stepTo(states, view, cache, x, y, z, x - 1, z, top, bottom, h));
            return Math.max(step, stepTo(states, view, cache, x, y, z, x + 1, z, top, bottom, h));
        }

        private static int stepTo(StateSpace states, WorldReader view, Scratch cache, int x, int y, int z, int nx, int nz,
                                  int top, int bottom, int h) {
            if (((nx >> 4) != (x >> 4) || (nz >> 4) != (z >> 4)) && !view.isLoaded(nx >> 4, nz >> 4)) return 0;
            int other = cache.surface(view, states, nx, nz, y, top, bottom);
            return other == SurfaceScan.NONE ? 0 : Math.abs(other - h);
        }

        @Override
        int reach() {
            return REACH_LIMIT;
        }

        @Override
        int cost() {
            return REACH_LIMIT + 1;
        }
    }

    private static final class InsideRule extends Rule {
        private final Region region;

        InsideRule(boolean not, Region region) {
            super(not);
            this.region = region;
        }

        @Override
        boolean matches(BoundRules mask, int x, int y, int z, int before, WorldReader view, int slope, Scratch scratch) {
            return region.contains(x, y, z);
        }
    }

    private static final class ChanceRule extends Rule {
        private final int percent;
        private final long seed;

        ChanceRule(boolean not, int percent, long seed) {
            super(not);
            this.percent = percent;
            this.seed = seed;
        }

        @Override
        boolean matches(BoundRules mask, int x, int y, int z, int before, WorldReader view, int slope, Scratch scratch) {
            return Long.remainderUnsigned(SplitMix64.hash(seed, x, y, z), 100) < percent;
        }
    }
}
