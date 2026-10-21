package dev.sculptory.mc.gametest;

import dev.sculptory.mc.loader.Loaders;
import java.util.List;
import net.minecraft.world.level.GameRules;

/**
 * The mod-fidelity GameTests: the engine against Chipped and Farmer's Delight, in their own test mod and run so the
 * third-party mods reach no other run. These sources are shared like the other GameTests but compiled only by the
 * loader modules, each against its own build of the mods (Farmer's Delight's block entities differ between the
 * NeoForge mod and the Fabric port), so the common module does not compile them.
 */
public final class FidelitySuite {
    /** The fidelity GameTest classes, in registration order (Fabric: the fidelity mod's fabric.mod.json, checked). */
    public static final List<Class<?>> CLASSES = List.of(
            FidelityStateGameTest.class,
            FidelityEditGameTest.class,
            FidelitySchematicGameTest.class,
            FidelityBrushGameTest.class,
            FidelityTurnGameTest.class,
            FidelityTinkerGameTest.class,
            FidelityFlipGameTest.class
    );

    private static boolean started;

    private FidelitySuite() {
    }

    /**
     * Turns random ticks off on the fidelity GameTest server once it has started: modded crops, farmland and mushroom
     * colonies would otherwise change between a snapshot and its check. The loader's fidelity test mod calls it when
     * it starts, after the mod installed its loader seam.
     */
    public static synchronized void start() {
        if (started) return;
        started = true;
        Loaders.server().onServerStarted(server ->
                server.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0, server));
    }
}
