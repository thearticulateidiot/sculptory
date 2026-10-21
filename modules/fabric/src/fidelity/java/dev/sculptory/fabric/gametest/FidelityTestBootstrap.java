package dev.sculptory.fabric.gametest;

import dev.sculptory.fabric.FabricServerLoader;
import dev.sculptory.mc.engine.impl.EngineRuntime;
import dev.sculptory.mc.gametest.FidelitySuite;
import dev.sculptory.mc.gametest.GameTestLoaders;
import net.fabricmc.api.ModInitializer;

/**
 * Starts the shared mod-fidelity GameTests (modules/common, src/fidelity) on Fabric, like {@link EngineTestBootstrap}
 * for the other GameTests, and {@link FidelitySuite#start} (random ticks off).
 */
public final class FidelityTestBootstrap implements ModInitializer {
    @Override
    public void onInitialize() {
        FabricServerLoader.install(); // entrypoints run in no set order: this one may run before the mod's
        EngineRuntime.install();
        GameTestLoaders.install(FabricGameTestLoader.INSTANCE);
        FidelitySuite.start();
        EngineTestBootstrap.checkEntrypoints("sculptory-fidelity", FidelitySuite.CLASSES);
    }
}
