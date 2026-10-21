package dev.sculptory.neoforge.gametest;

import dev.sculptory.mc.gametest.GameTestSuite;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * The GameTest mod on NeoForge (runGameTest): the shared GameTests (modules/common, src/gametest). It loads after
 * Sculptory (neoforge.mods.toml), whose constructor installed the loader seams and the engine.
 */
@Mod("sculptory_gametest")
public final class SculptoryGameTestMod {
    public SculptoryGameTestMod(IEventBus modBus) {
        NeoForgeGameTests.install(modBus, GameTestSuite.CLASSES);
        GameTestSuite.start();
    }
}
