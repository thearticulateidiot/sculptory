package dev.sculptory.server.engine.impl;

/**
 * Receives the brush lane's prediction acknowledgements. {@link EngineEditService} calls it exactly once per
 * dab batch it admitted, after the batch's dabs were written or dropped (shutdown, a kernel failure, a refusal
 * found when the lane reached the dab), in admission order per player. Batches refused in the {@code DabOutcome}
 * are never reported here; the protocol dispatcher acknowledges those itself.
 *
 * <p>In production this is the network layer's (on Fabric, {@code ServerNet::predictionApplied}), which keeps the
 * game's cumulative acks in sequence order. Without one the edit service acknowledges the game's own way
 * ({@code Platform.acknowledge}), for GameTests and servers without the network layer.
 *
 * @param <P> the platform's player type
 */
@FunctionalInterface
public interface AckSink<P> {
    /** The batch predicted under {@code seq} has been applied (or dropped). Server thread. */
    void ack(P player, int seq);
}
