package dev.sculptory.fabric.net;

import dev.sculptory.protocol.v2.S2C;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Optional companion of {@code EditService}: when the edit service also implements this, the dispatcher sends
 * the player's {@link S2C.HistoryState} after the handshake, after every finished job, after a stroke ends and
 * after a refused undo/redo. Without it the client's history mirror only changes through
 * {@link ServerNet#sendHistoryState}.
 */
public interface HistoryView {
    /** The player's history as the client should see it (labels capped), or {@code null} to send nothing. */
    S2C.HistoryState historyState(ServerPlayerEntity player);
}
