package dev.sculptory.protocol.v2;

/** How a job ended. Cancelled and failed jobs keep (and can undo) the work already applied. Wire order: append only. */
public enum JobOutcome {
    COMPLETED,
    CANCELLED,
    FAILED
}
