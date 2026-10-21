package dev.sculptory.fabric.gametest;

import dev.sculptory.fabric.FabricServerLoader;
import dev.sculptory.mc.engine.impl.EngineRuntime;
import dev.sculptory.mc.gametest.GameTestLoaders;
import dev.sculptory.mc.gametest.GameTestSuite;
import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Starts the shared GameTests (modules/common, src/gametest) on Fabric: the loader seams, the engine, the Fabric
 * {@link FabricGameTestLoader} and {@link GameTestSuite#start}. {@link FabricServerLoader#install()} and
 * {@link EngineRuntime#install()} are idempotent, so this stays harmless once the mod initializer installs them too;
 * Fabric Loader runs the entrypoints in no set order, so this may also run first.
 *
 * <p>Fabric API registers the test classes from the {@code fabric-gametest} entrypoints of fabric.mod.json; NeoForge
 * registers {@link GameTestSuite#CLASSES}. The two lists must be the same, so a class added to one and not the other
 * stops the run here.
 */
public final class EngineTestBootstrap implements ModInitializer {
    @Override
    public void onInitialize() {
        FabricServerLoader.install();
        EngineRuntime.install();
        GameTestLoaders.install(FabricGameTestLoader.INSTANCE);
        GameTestSuite.start();
        checkEntrypoints("sculptory-gametest", GameTestSuite.CLASSES);
    }

    /** Fails when mod {@code modId}'s {@code fabric-gametest} entrypoints are not exactly {@code classes}, in order. */
    static void checkEntrypoints(String modId, List<Class<?>> classes) {
        List<Class<?>> listed = FabricLoader.getInstance().getEntrypointContainers("fabric-gametest", Object.class)
                .stream()
                .filter(container -> container.getProvider().getMetadata().getId().equals(modId))
                .<Class<?>>map(container -> container.getEntrypoint().getClass())
                .toList();
        if (!listed.equals(classes)) {
            throw new IllegalStateException("The fabric-gametest entrypoints of " + modId + " (fabric.mod.json) are "
                    + "not the shared test classes: listed " + listed + ", shared " + classes);
        }
    }
}
