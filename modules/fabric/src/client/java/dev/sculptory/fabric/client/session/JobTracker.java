package dev.sculptory.fabric.client.session;

import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Phase;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** The player's server jobs, for the HUD job bars. Cancel through {@link ToolAction.Cancel}. */
public interface JobTracker {
    /**
     * A job snapshot; {@code outcome} is {@code null} while it runs. The result counts come from the server's
     * {@code JobFinished} and are 0 until the job has finished.
     *
     * @param changed blocks the job changed
     * @param skippedProtected blocks left alone because they are protected (spawn protection, claims)
     * @param skippedConflicts blocks left alone because they changed since the edit (undo/redo)
     * @param strippedNbt blocks placed without their operator-only block-entity data
     */
    record Job(UUID jobId, String label, long done, long total, Phase phase, JobOutcome outcome,
               long changed, long skippedProtected, long skippedConflicts, long strippedNbt) {
        public Job {
            Objects.requireNonNull(jobId);
            Objects.requireNonNull(label);
            Objects.requireNonNull(phase);
        }

        /** A job without result counts (all 0). */
        public Job(UUID jobId, String label, long done, long total, Phase phase, JobOutcome outcome) {
            this(jobId, label, done, total, phase, outcome, 0, 0, 0, 0);
        }

        public boolean finished() {
            return outcome != null;
        }

        /** Blocks the job did not change: protected plus conflicting. */
        public long skipped() {
            return skippedProtected + skippedConflicts;
        }
    }

    /** Running and recently finished jobs, oldest first. */
    List<Job> jobs();

    Optional<Job> job(UUID jobId);

    Subscription onChange(Runnable listener);
}
