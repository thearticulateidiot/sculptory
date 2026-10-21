package dev.sculptory.mc.engine.impl;

import dev.sculptory.core.scatter.ScatterPlanner;
import dev.sculptory.mc.world.McStateSpace;
import net.minecraft.server.level.ServerLevel;

/**
 * The package-private {@link ScatterSurvival} for the Paper engine host (dev.sculptory.paper.engine.PaperEngine); on
 * Fabric and NeoForge the loaders' EngineRuntime reaches it from inside this package.
 */
public final class PaperScatterSurvival {
    private PaperScatterSurvival() {
    }

    public static ScatterPlanner.SurvivalCheck survivalCheck(McStateSpace states, ServerLevel world, int[] blockStates) {
        return ScatterSurvival.survivalCheck(states, world, blockStates);
    }
}
