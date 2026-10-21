package dev.sculptory.neoforge;

import dev.sculptory.mc.Sculptory;
import dev.sculptory.mc.command.SculptoryCommands;
import dev.sculptory.mc.engine.impl.EditServiceHost;
import dev.sculptory.mc.engine.impl.EngineRuntime;
import dev.sculptory.mc.loader.DevClasses;
import dev.sculptory.mc.net.NetEditEvents;
import dev.sculptory.mc.net.ServerNet;
import dev.sculptory.mc.playtest.PlaytestSetup;
import dev.sculptory.neoforge.client.SculptoryNeoForgeClient;
import dev.sculptory.protocol.v2.ProtocolV2;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * The mod's entrypoint on NeoForge, on dedicated servers and clients alike: the same wiring as the Fabric mod's
 * {@code SculptoryMod} (the NeoForge seam first, then the engine, the edit service, the network layer and the commands),
 * then, on a client, the client side ({@link SculptoryNeoForgeClient}), which waits for {@code RegisterKeyMappingsEvent}
 * on the render thread (see there). One entrypoint calls the other so the client side always starts after the server
 * side, as Fabric's {@code main} and {@code client} entrypoints run; the client class is only loaded when that call
 * runs, so a dedicated server never loads it.
 */
@Mod(Sculptory.MOD_ID)
public final class SculptoryNeoForge {
    /** The dev-only playtest setup, by name: the jar leaves its package out (NeoForgeJarClassesTest). */
    static final String PLAYTEST_SETUP = "dev.sculptory.mc.playtest.PlaytestSetup";

    public SculptoryNeoForge(IEventBus modBus, Dist dist) {
        NeoForgeServerLoader.install(modBus);
        Sculptory.LOG.info("Sculptory for NeoForge {} initializing (engine {}, protocol {})", Sculptory.productBuildId(),
                Sculptory.buildId(), ProtocolV2.VERSION);
        EngineRuntime.install();
        EditServiceHost.install(ServerNet::jobListener, ServerNet::predictionApplied, new NetEditEvents());
        ServerNet.install(EditServiceHost.service(), EditServiceHost.clipboards(), EditServiceHost.scatter(),
                EditServiceHost.tinker(), EditServiceHost.permissions(), EditServiceHost::limits, EditServiceHost::states);
        SculptoryCommands.register();
        // The dev-only playtest setup (the tester is op, day is locked, mobs are off) with -Dsculptory.playtest=true,
        // from a dev checkout only. The property constant is inlined, so without it no class of the setup is loaded.
        if (DevClasses.wanted(SculptoryNeoForge.class.getClassLoader(), PlaytestSetup.PROPERTY, PLAYTEST_SETUP)) {
            PlaytestSetup.install();
        }
        if (dist.isClient()) SculptoryNeoForgeClient.init(modBus);
    }
}
