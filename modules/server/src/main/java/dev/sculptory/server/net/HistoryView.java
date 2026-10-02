package dev.sculptory.server.net;

import dev.sculptory.protocol.v2.S2C;

/**
 * Optional companion of {@code EditService}: when the edit service also implements this, the dispatcher sends
 * the player's {@link S2C.HistoryState} after the handshake, after every finished job, after a stroke ends and
 * after a refused undo/redo. Without it the client's history mirror only changes through
 * the platform's own history push (on Fabric, {@code ServerNet.sendHistoryState}).
 *
 * @param <P> the platform's player type
 */
public interface HistoryView<P> {
    /** The player's history as the client should see it (labels capped), or {@code null} to send nothing. */
    S2C.HistoryState historyState(P player);
}
