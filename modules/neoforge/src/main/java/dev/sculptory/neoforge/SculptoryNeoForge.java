package dev.sculptory.neoforge;

import dev.sculptory.mc.Sculptory;
import dev.sculptory.mc.command.SculptoryCommands;
import dev.sculptory.mc.engine.impl.EditServiceHost;
import dev.sculptory.mc.engine.impl.EngineRuntime;
import dev.sculptory.mc.net.NetEditEvents;
import dev.sculptory.mc.net.ServerNet;
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
    public SculptoryNeoForge(IEventBus modBus, Dist dist) {
        NeoForgeServerLoader.install(modBus);
        Sculptory.LOG.info("Sculptory for NeoForge {} initializing (engine {}, protocol {})", Sculptory.productBuildId(),
                Sculptory.buildId(), ProtocolV2.VERSION);
        EngineRuntime.install();
        EditServiceHost.install(ServerNet::jobListener, ServerNet::predictionApplied, new NetEditEvents());
        ServerNet.install(EditServiceHost.service(), EditServiceHost.clipboards(), EditServiceHost.scatter(),
                EditServiceHost.tinker(), EditServiceHost.permissions(), EditServiceHost::limits, EditServiceHost::states);
        SculptoryCommands.register();
        if (dist.isClient()) SculptoryNeoForgeClient.init(modBus);
    }
}
