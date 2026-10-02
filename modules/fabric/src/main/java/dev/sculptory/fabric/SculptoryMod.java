package dev.sculptory.fabric;

import dev.sculptory.core.brush.SymmetricStep;
import dev.sculptory.fabric.command.SculptoryCommands;
import dev.sculptory.fabric.engine.impl.EditEvents;
import dev.sculptory.fabric.engine.impl.EditServiceHost;
import dev.sculptory.fabric.engine.impl.EngineRuntime;
import dev.sculptory.fabric.net.ServerNet;
import dev.sculptory.protocol.v2.ProtocolV2;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.server.engine.impl.HistorySnapshot;
import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common/server entrypoint. Runs on dedicated servers and on the integrated (singleplayer) server.
 *
 * <p>Wiring: the engine ({@link EngineRuntime}: executor, permissions, config) and the edit service
 * ({@link EditServiceHost}) are created per server; the network layer ({@link ServerNet}) is installed once and
 * talks to them through the host's facades. Undo/redo job events go to {@link ServerNet#jobListener}, dab
 * acknowledgements to {@link ServerNet#predictionApplied} (ordered cumulative acks), and history changes the
 * dispatcher does not already send (a stroke committed by its idle timeout, evictions) to
 * {@link ServerNet#sendHistoryState}. The facade also implements {@code net.HistoryView}, so the dispatcher sends
 * the history after the handshake, jobs, strokes and refused undo/redo.
 */
public final class SculptoryMod implements ModInitializer {
    public static final String MOD_ID = "sculptory";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);
    private static volatile String buildId;

    /**
     * This build's id: the mod version from {@code fabric.mod.json}. A release build sets it to {@code mod_version}
     * (e.g. {@code 0.3.0-alpha}); any other build to {@code mod_version} plus the
     * commit it was made from (e.g. {@code 0.3.0-alpha+6a043a85}, {@code .dirty} for uncommitted changes; root
     * build.gradle). Client and server exchange it at the handshake. {@code "unknown"} if the loader has no such mod.
     */
    public static String buildId() {
        String id = buildId;
        if (id == null) {
            id = FabricLoader.getInstance().getModContainer(MOD_ID)
                    .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                    .orElse("unknown");
            buildId = id;
        }
        return id;
    }

    @Override
    public void onInitialize() {
        LOG.info("Sculptory {} initializing (protocol {})", buildId(), ProtocolV2.VERSION);
        EngineRuntime.install();
        EditServiceHost.install(ServerNet::jobListener, ServerNet::predictionApplied, new NetEditEvents());
        ServerNet.install(EditServiceHost.service(), EditServiceHost.clipboards(), EditServiceHost.scatter(),
                EditServiceHost.tinker(), EditServiceHost.permissions(), EditServiceHost::limits, EditServiceHost::states);
        SculptoryCommands.register();
    }

    /** Edit-service pushes sent to the player's client. */
    private static final class NetEditEvents implements EditEvents {
        @Override
        public void historyChanged(ServerPlayerEntity player, HistorySnapshot snapshot) {
            ServerNet.sendHistoryState(player, EditServiceHost.toHistoryState(snapshot));
        }

        @Override
        public void historyEvicted(ServerPlayerEntity player, int steps, boolean includesNewest) {
            String key = includesNewest ? "sculptory.notice.history_too_large" : "sculptory.notice.history_evicted";
            ServerNet.sendNotice(player, new S2C.Notice(S2C.Notice.Level.WARN, key, List.of(Integer.toString(steps))));
        }

        @Override
        public void dabRejected(ServerPlayerEntity player, int strokeId, int dabIndex, RejectReason reason) {
            // Ends the stroke on the client; the engine refuses its later dabs until a new stroke.
            // The client toasts the refusal with a message for its reason, so no separate notice is sent.
            ServerNet.sendStrokeStatus(player, new S2C.StrokeStatus(strokeId, Math.max(-1, dabIndex),
                    S2C.StrokeStatus.Status.REJECTED, reason));
        }

        @Override
        public void dabsApplied(ServerPlayerEntity player, int strokeId, int lastIndex) {
            ServerNet.dabsApplied(player, strokeId, lastIndex);
        }

        @Override
        public void scatterSkipped(ServerPlayerEntity player, long placements) {
            ServerNet.sendNotice(player, new S2C.Notice(S2C.Notice.Level.WARN, "sculptory.notice.scatter_skipped",
                    List.of(Long.toString(placements))));
        }

        @Override
        public void symmetryNoGround(ServerPlayerEntity player, int strokeId, int dabIndex, int copies) {
            ServerNet.sendNotice(player, new S2C.Notice(S2C.Notice.Level.WARN, SYMMETRY_NO_GROUND,
                    List.of(Integer.toString(copies), Integer.toString(SymmetricStep.GROUND_SEARCH))));
        }
    }
}
