package dev.sculptory.fabric.client.editor.tools.fluid;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.world.WorldReader;

/**
 * The air pocket a flood fills: the air cells connected to
 * the seed through faces that lie at or below the seed's level ({@code seed.y}), as still water poured there would
 * settle. A seed that is not air finds nothing. With {@code waterlogRim} the rim is collected too: every waterloggable,
 * not yet waterlogged neighbour of a pocket cell at or below the level (a stair, slab, fence, sea pickle...), which the
 * {@code Waterlog} pattern then waterlogs; the search never continues through the rim. Limits, bounds and unloaded
 * chunks as {@link FluidSearch}.
 */
public final class FluidFlood extends FluidSearch {
    private final int level;
    private final boolean waterlogRim;

    /**
     * @param seed the air cell the flood starts from; its y is the level nothing rises above
     * @param limit the most cells collected (pocket and rim together), at least 1
     * @param bounds the box the flood stays inside (the selection's), or {@code null}
     * @param waterlogRim whether waterloggable blocks touching the pocket are collected (water only: the tool passes
     *     false for lava)
     */
    public FluidFlood(WorldReader world, BlockPos seed, long limit, Box bounds, boolean waterlogRim) {
        super(world, limit, bounds);
        this.level = seed.y();
        this.waterlogRim = waterlogRim;
        start(seed);
    }

    /** The level the flood stays at or below: the seed's y. */
    public int level() {
        return level;
    }

    @Override
    protected Kind classify(int x, int y, int z, int state) {
        if (y > level) return Kind.NONE;
        int flags = states.flags(state);
        if (StateFlags.has(flags, StateFlags.AIR)) return Kind.EXPAND;
        if (waterlogRim && StateFlags.has(flags, StateFlags.WATERLOGGABLE) && !StateFlags.has(flags, StateFlags.WATERLOGGED)) {
            return Kind.COLLECT;
        }
        return Kind.NONE;
    }
}
