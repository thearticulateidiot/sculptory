package dev.sculptory.fabric.engine;

import java.util.Objects;
import java.util.UUID;

/** An admitted job. */
public record JobTicket(UUID jobId, String label, long estimatedCells) {
    public JobTicket {
        Objects.requireNonNull(jobId);
        Objects.requireNonNull(label);
    }
}
