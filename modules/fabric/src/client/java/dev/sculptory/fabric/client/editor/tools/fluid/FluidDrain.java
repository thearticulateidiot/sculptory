package dev.sculptory.fabric.client.editor.tools.fluid;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.world.WorldReader;

/**
 * The body of fluid a drain removes: the cells connected to
 * the seed through faces that hold the seed's fluid: water blocks at any level when the seed holds water (the
 * {@code WATER} flag), lava blocks when it holds lava (a fluid block without it). With {@code waterlogged} (water only)
 * waterlogged states and blocks that hold water without the property (seagrass, kelp, bubble columns) belong to the
 * body too, and the search continues through them, as water does. A seed holding neither finds nothing. Limits, bounds
 * and unloaded chunks as {@link FluidSearch}.
 */
public final class FluidDrain extends FluidSearch {
    private final boolean waterlogged;
    private boolean water;

    /**
     * @param seed the cell under the cursor: a fluid block, or (with {@code waterlogged}) a block holding water
     * @param limit the most cells collected, at least 1
     * @param bounds the box the drain stays inside (the selection's), or {@code null}
     * @param waterlogged whether waterlogged blocks and water plants are drained too (the tool passes false for lava)
     */
    public FluidDrain(WorldReader world, BlockPos seed, long limit, Box bounds, boolean waterlogged) {
        super(world, limit, bounds);
        this.waterlogged = waterlogged;
        int y = seed.y();
        if (y >= world.bottomY() && y < world.topYExclusive() && world.isLoaded(seed.x() >> 4, seed.z() >> 4)) {
            water = StateFlags.has(states.flags(world.get(seed.x(), y, seed.z())), StateFlags.WATER);
        }
        start(seed);
    }

    /** Whether the body drained is water (else lava). Meaningful once something was found. */
    public boolean water() {
        return water;
    }

    @Override
    protected Kind classify(int x, int y, int z, int state) {
        int flags = states.flags(state);
        boolean fluidBlock = StateFlags.has(flags, StateFlags.FLUID_BLOCK);
        boolean holdsWater = StateFlags.has(flags, StateFlags.WATER);
        if (water) {
            if (fluidBlock) return holdsWater ? Kind.EXPAND : Kind.NONE;
            if (waterlogged && holdsWater) return Kind.EXPAND;
            return Kind.NONE;
        }
        return fluidBlock && !holdsWater ? Kind.EXPAND : Kind.NONE;
    }
}
