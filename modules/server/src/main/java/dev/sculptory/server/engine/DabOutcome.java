package dev.sculptory.server.engine;

import dev.sculptory.protocol.v2.RejectReason;

/**
 * The immediate answer to a batch of dabs. Rejected dabs are still acknowledged so the client's prediction
 * reverts.
 *
 * @param lastIndex the last dab index covered by this answer
 * @param reason {@code null} when accepted
 */
public record DabOutcome(boolean accepted, int lastIndex, RejectReason reason) {
    public DabOutcome {
        if (accepted != (reason == null)) throw new IllegalArgumentException("A reason is required exactly when rejected");
    }

    public static DabOutcome accepted(int lastIndex) {
        return new DabOutcome(true, lastIndex, null);
    }

    public static DabOutcome rejected(int lastIndex, RejectReason reason) {
        return new DabOutcome(false, lastIndex, reason);
    }
}
