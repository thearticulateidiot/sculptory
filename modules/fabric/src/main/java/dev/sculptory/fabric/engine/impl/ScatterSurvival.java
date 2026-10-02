package dev.sculptory.fabric.engine.impl;

import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.scatter.BlockVariants;
import dev.sculptory.core.scatter.Outcome;
import dev.sculptory.core.scatter.ScatterPlanner;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.world.FabricStateSpace;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The scatter planner's survival check for block variants on Fabric ({@code EngineRuntime.survivalCheck}). */
final class ScatterSurvival {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");

    private ScatterSurvival() {}

    /**
     * The planner's survival check for block variants: the (lower) block, turned as it will be placed, must be one
     * vanilla would keep there ({@code BlockState.canPlaceAt}: a flower on grass or dirt, a cactus on sand, sugar cane
     * next to water). Other sources always pass. {@code canPlaceAt} reads the neighbouring columns: an anchor next to a
     * chunk that is not loaded ends as {@code UNLOADED} (the planner never loads one). It is asked through a
     * {@link LoadedOnlyView}, so a rule that reads farther (a mod's) sees void air there instead of loading the chunk.
     *
     * @param blockStates per source index, the block variant's lower state, or -1 for a clipboard or asset
     */
    static ScatterPlanner.SurvivalCheck survivalCheck(FabricStateSpace states, ServerWorld world, int[] blockStates) {
        boolean anyBlock = false;
        for (int state : blockStates) anyBlock |= state >= 0;
        if (!anyBlock) return ScatterPlanner.SurvivalCheck.ALWAYS;
        return new BlockSurvival(states, new LoadedOnlyView(world), blockStates);
    }

    /**
     * {@link #survivalCheck}'s check. A column plant's column (kelp, sugar cane, cactus) is asked cell by cell, each
     * cell standing on the column's cell below it ({@link LoadedOnlyView#assume}): a cactus column stops being
     * accepted where a block beside one of its cells would break it.
     */
    private static final class BlockSurvival implements ScatterPlanner.SurvivalCheck {
        private final FabricStateSpace states;
        private final LoadedOnlyView view;
        private final int[] blockStates;
        private final net.minecraft.util.math.BlockPos.Mutable pos = new net.minecraft.util.math.BlockPos.Mutable();
        /** A column plant's cell states, bottom first, by source and height. */
        private final Map<Long, int[]> columns = new HashMap<>();

        BlockSurvival(FabricStateSpace states, LoadedOnlyView view, int[] blockStates) {
            this.states = states;
            this.view = view;
            this.blockStates = blockStates;
        }

        @Override
        public Outcome check(int source, Transform transform, int x, int y, int z) {
            return check(source, transform, x, y, z, 1);
        }

        @Override
        public Outcome check(int source, Transform transform, int x, int y, int z, int height) {
            int state = blockStates[source];
            if (state < 0) return null;
            for (int cx = (x - 1) >> 4; cx <= (x + 1) >> 4; cx++) {
                for (int cz = (z - 1) >> 4; cz <= (z + 1) >> 4; cz++) {
                    if (!view.loaded(cx, cz)) return Outcome.UNLOADED;
                }
            }
            if (height == 1) return stays(state, transform.applyToState(states, state), x, y, z) ? null : Outcome.SURVIVAL;
            int[] cells = columns.computeIfAbsent(((long) source << 8) | height, key -> {
                Clipboard column = BlockVariants.column(states, state, height);
                int[] list = new int[height];
                for (int k = 0; k < height; k++) list[k] = column.get(0, k, 0);
                return list;
            });
            try {
                for (int k = 0; k < height; k++) {
                    if (k > 0) {
                        view.assume(pos.set(x, y + k - 1, z),
                                states.state(transform.applyToState(states, cells[k - 1])));
                    }
                    if (!stays(state, transform.applyToState(states, cells[k]), x, y + k, z)) return Outcome.SURVIVAL;
                }
            } finally {
                view.forget();
            }
            return null;
        }

        /** Vanilla's {@code canPlaceAt} of {@code placed} at (x, y, z); {@code variant} names the block in a warning. */
        private boolean stays(int variant, int placed, int x, int y, int z) {
            try {
                return states.state(placed).canPlaceAt(view, pos.set(x, y, z));
            } catch (RuntimeException e) {
                // A mod's rule that expects a real World, say; the preview still fails (INVALID) as any planner failure.
                LOG.warn("Sculptory: the survival check (canPlaceAt) of {} failed in a scatter preview: {}",
                        states.blockId(variant).value(), e.toString());
                throw e;
            }
        }
    }
}
