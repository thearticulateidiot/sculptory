package dev.sculptory.fabric.gametest;

import dev.sculptory.fabric.engine.impl.EngineRuntime;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.world.GameRules;

/**
 * Starts the engine on the fidelity GameTest server (like {@link EngineTestBootstrap}) and turns random ticks off
 * there: modded crops, farmland and mushroom colonies would otherwise change between a snapshot and its check.
 */
public final class FidelityTestBootstrap implements ModInitializer {
    @Override
    public void onInitialize() {
        EngineRuntime.install();
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                server.getGameRules().get(GameRules.RANDOM_TICK_SPEED).set(0, server));
    }
}
