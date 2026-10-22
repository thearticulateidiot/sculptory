package dev.sculptory.mc.engine.impl;

import dev.sculptory.core.scatter.ScatterPlanner;
import dev.sculptory.mc.world.McStateSpace;
import dev.sculptory.server.engine.TinkerService;
import dev.sculptory.server.engine.impl.EngineEditService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The package-private parts of the common engine (the scatter survival check, Tinker) for the Paper engine host
 * (dev.sculptory.paper.engine.PaperEngine); on Fabric and NeoForge the loaders' EngineRuntime reaches them from inside
 * this package.
 */
public final class PaperEngineParts {
    private PaperEngineParts() {
    }

    public static ScatterPlanner.SurvivalCheck survivalCheck(McStateSpace states, ServerLevel world, int[] blockStates) {
        return ScatterSurvival.survivalCheck(states, world, blockStates);
    }

    /** {@link TinkerEdits} over {@code host}. */
    public static TinkerService<ServerPlayer> tinker(EngineEditService<ServerPlayer, ServerLevel> edits, McHost host) {
        return new TinkerEdits(edits, host);
    }
}
