package dev.sculptory.server.engine;

import dev.sculptory.protocol.v2.JobOutcome;
import java.util.Objects;
import java.util.UUID;

/**
 * How a job ended; mirrors the {@code JobFinished} message.
 *
 * @param skippedProtected cells skipped by protection
 * @param skippedConflicts undo/redo cells skipped because they changed since the edit
 * @param strippedNbt operator-only block-entity NBT removed for a player without the right
 */
public record JobResult(UUID jobId, JobOutcome outcome, long changed, long skippedProtected, long skippedConflicts,
                        long strippedNbt) {
    public JobResult {
        Objects.requireNonNull(jobId);
        Objects.requireNonNull(outcome);
    }
}
