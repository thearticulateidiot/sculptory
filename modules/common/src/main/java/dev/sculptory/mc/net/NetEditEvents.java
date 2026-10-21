package dev.sculptory.mc.net;

import dev.sculptory.core.brush.SymmetricStep;
import dev.sculptory.mc.engine.impl.EditServiceHost;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.server.engine.impl.EditEvents;
import dev.sculptory.server.engine.impl.HistorySnapshot;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;

/** Edit-service pushes sent to the player's client ({@link ServerNet}); the mod initializer hands it to the host. */
public final class NetEditEvents implements EditEvents<ServerPlayer> {
    @Override
    public void historyChanged(ServerPlayer player, HistorySnapshot snapshot) {
        ServerNet.sendHistoryState(player, EditServiceHost.toHistoryState(snapshot));
    }

    @Override
    public void historyEvicted(ServerPlayer player, int steps, boolean includesNewest) {
        String key = includesNewest ? "sculptory.notice.history_too_large" : "sculptory.notice.history_evicted";
        ServerNet.sendNotice(player, new S2C.Notice(S2C.Notice.Level.WARN, key, List.of(Integer.toString(steps))));
    }

    @Override
    public void dabRejected(ServerPlayer player, int strokeId, int dabIndex, RejectReason reason) {
        // Ends the stroke on the client; the engine refuses its later dabs until a new stroke.
        // The client toasts the refusal with a message for its reason, so no separate notice is sent.
        ServerNet.sendStrokeStatus(player, new S2C.StrokeStatus(strokeId, Math.max(-1, dabIndex),
                S2C.StrokeStatus.Status.REJECTED, reason));
    }

    @Override
    public void dabsApplied(ServerPlayer player, int strokeId, int lastIndex) {
        ServerNet.dabsApplied(player, strokeId, lastIndex);
    }

    @Override
    public void scatterSkipped(ServerPlayer player, long placements) {
        ServerNet.sendNotice(player, new S2C.Notice(S2C.Notice.Level.WARN, "sculptory.notice.scatter_skipped",
                List.of(Long.toString(placements))));
    }

    @Override
    public void symmetryNoGround(ServerPlayer player, int strokeId, int dabIndex, int copies) {
        ServerNet.sendNotice(player, new S2C.Notice(S2C.Notice.Level.WARN, SYMMETRY_NO_GROUND,
                List.of(Integer.toString(copies), Integer.toString(SymmetricStep.GROUND_SEARCH))));
    }
}
