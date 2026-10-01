package dev.sculptory.fabric.engine.impl;

import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Receives the brush lane's prediction acknowledgements. {@link EngineEditService} calls it exactly once per
 * dab batch it admitted, after the batch's dabs were written or dropped (shutdown, a kernel failure, a refusal
 * found when the lane reached the dab), in admission order per player. Batches refused in the {@code DabOutcome}
 * are never reported here; the protocol dispatcher acknowledges those itself.
 *
 * <p>In production this is {@code ServerNet::predictionApplied}, which keeps vanilla's cumulative acks in
 * sequence order. {@link #VANILLA} acknowledges directly and is meant for GameTests and servers without the
 * network layer.
 */
@FunctionalInterface
public interface AckSink {
    /** {@code player.networkHandler.updateSequence(seq)} (negative sequences are ignored). */
    AckSink VANILLA = (player, seq) -> {
        if (seq >= 0 && player.networkHandler != null) player.networkHandler.updateSequence(seq);
    };

    /** The batch predicted under {@code seq} has been applied (or dropped). Server thread. */
    void ack(ServerPlayerEntity player, int seq);
}
