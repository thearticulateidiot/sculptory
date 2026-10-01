package dev.sculptory.core.edit;

import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.state.StateSpace;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Better Replace's patterns as one region program runs them:
 * {@link Pattern.KeepShape}'s shared properties cached per (written state, cell state) pair and a
 * {@link Pattern.Remap}'s answer cached per cell state, so a region of a million stairs works each distinct state out
 * once. The answers equal the patterns' own {@code apply} (and {@link CopyFrame#apply} for a symmetric copy). One per
 * program copy; not thread-safe.
 */
final class ReplaceCache {
    private final StateSpace states;
    private final CopyFrame frame;
    /** The Keep shape pattern's inner pattern, or {@code null} for a remap. */
    private final Pattern inner;
    /** The inner pattern's state when it is a {@link Pattern.Single} (turned by the frame), else -1. */
    private final int innerConstant;
    private final Long2IntOpenHashMap kept = new Long2IntOpenHashMap();
    /** A remap's swaps by block, or {@code null}. */
    private final Map<NamespacedId, NamespacedId> swaps;
    private final Pattern.Remap remap;
    /** A remap's answer per cell state, -1 until worked out. */
    private final int[] remapped;
    private final boolean carriesTiles;

    private ReplaceCache(StateSpace states, Pattern pattern, CopyFrame frame) {
        this.states = Objects.requireNonNull(states);
        this.frame = frame;
        kept.defaultReturnValue(-1);
        if (pattern instanceof Pattern.KeepShape keep) {
            inner = keep.inner();
            innerConstant = inner instanceof Pattern.Single single
                    ? (frame == null ? single.state() : frame.mapState(single.state())) : -1;
            swaps = null;
            remap = null;
            remapped = null;
            carriesTiles = true;
        } else {
            remap = (Pattern.Remap) pattern;
            inner = null;
            innerConstant = -1;
            swaps = new HashMap<>();
            for (Pattern.BlockSwap swap : remap.swaps()) swaps.put(swap.from(), swap.to());
            remapped = new int[states.size()];
            Arrays.fill(remapped, -1);
            carriesTiles = remap.keepShape();
        }
    }

    /** A cache for {@code pattern} when it is a Keep shape or a remap, else {@code null}. */
    static ReplaceCache of(Pattern pattern, StateSpace states, CopyFrame frame) {
        return pattern instanceof Pattern.KeepShape || pattern instanceof Pattern.Remap
                ? new ReplaceCache(states, pattern, frame) : null;
    }

    /** The pattern's state at the cell (x, y, z), now {@code current}. */
    int apply(int x, int y, int z, int current) {
        if (remapped != null) return remapped(current);
        int target = innerConstant >= 0 ? innerConstant
                : frame == null ? inner.apply(states, x, y, z, current) : frame.apply(inner, x, y, z, current);
        return kept(target, current);
    }

    /**
     * Whether a changed cell keeps its block entity where the new state can hold it
     * ({@link StateSpace#keepsBlockEntity}): Keep shape, and a remap with its shape kept.
     */
    boolean carriesTiles() {
        return carriesTiles;
    }

    private int kept(int target, int current) {
        long key = ((long) target << 32) | (current & 0xFFFFFFFFL);
        int result = kept.get(key);
        if (result < 0) {
            result = states.withSharedProperties(target, current);
            kept.put(key, result);
        }
        return result;
    }

    private int remapped(int current) {
        int result = remapped[current];
        if (result < 0) {
            NamespacedId to = swaps.get(states.blockId(current));
            result = to == null ? current : remap.target(states, to, current);
            remapped[current] = result;
        }
        return result;
    }
}
