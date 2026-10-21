package dev.sculptory.fabric;

import dev.sculptory.mc.Sculptory;
import dev.sculptory.mc.command.SculptoryCommands;
import dev.sculptory.mc.engine.impl.EditServiceHost;
import dev.sculptory.mc.engine.impl.EngineRuntime;
import dev.sculptory.mc.net.NetEditEvents;
import dev.sculptory.mc.net.ServerNet;
import dev.sculptory.protocol.v2.ProtocolV2;
import net.fabricmc.api.ModInitializer;

/**
 * Common/server entrypoint. Runs on dedicated servers and on the integrated (singleplayer) server.
 *
 * <p>Wiring: the Fabric seam ({@link FabricServerLoader}) is installed first, so everything after it registers its
 * hooks through it. The engine ({@link EngineRuntime}: executor, permissions, config) and the edit service
 * ({@link EditServiceHost}) are created per server; the network layer ({@link ServerNet}) is installed once and
 * talks to them through the host's facades. Undo/redo job events go to {@link ServerNet#jobListener}, dab
 * acknowledgements to {@link ServerNet#predictionApplied} (ordered cumulative acks), and history changes the
 * dispatcher does not already send (a stroke committed by its idle timeout, evictions) to
 * {@link ServerNet#sendHistoryState} ({@link NetEditEvents}). The facade also implements {@code net.HistoryView}, so
 * the dispatcher sends the history after the handshake, jobs, strokes and refused undo/redo.
 */
public final class SculptoryMod implements ModInitializer {
    @Override
    public void onInitialize() {
        FabricServerLoader.install();
        Sculptory.LOG.info("Sculptory for Fabric {} initializing (engine {}, protocol {})", Sculptory.productBuildId(),
                Sculptory.buildId(), ProtocolV2.VERSION);
        EngineRuntime.install();
        EditServiceHost.install(ServerNet::jobListener, ServerNet::predictionApplied, new NetEditEvents());
        ServerNet.install(EditServiceHost.service(), EditServiceHost.clipboards(), EditServiceHost.scatter(),
                EditServiceHost.tinker(), EditServiceHost.permissions(), EditServiceHost::limits, EditServiceHost::states);
        SculptoryCommands.register();
    }
}
