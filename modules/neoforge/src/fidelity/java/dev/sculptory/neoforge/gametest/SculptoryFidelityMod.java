package dev.sculptory.neoforge.gametest;

import dev.sculptory.mc.gametest.FidelitySuite;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * The mod-fidelity GameTest mod on NeoForge (runFidelityGameTest): the shared fidelity tests (modules/common,
 * src/fidelity) against the NeoForge builds of Chipped and Farmer's Delight. It loads after Sculptory and those mods.
 */
@Mod("sculptory_fidelity")
public final class SculptoryFidelityMod {
    public SculptoryFidelityMod(IEventBus modBus) {
        NeoForgeGameTests.install(modBus, FidelitySuite.CLASSES);
        FidelitySuite.start();
    }
}
