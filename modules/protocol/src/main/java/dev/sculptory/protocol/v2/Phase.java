package dev.sculptory.protocol.v2;

/** Job phases, in order. Shared by the engine and the wire. Wire order: append only. */
public enum Phase {
    /** Waiting for an overlapping job or a free slot. */
    QUEUED,
    LOAD_CHUNKS,
    SNAPSHOT_SOURCES,
    /** Per section: capture before, compute, write, record. */
    APPLY,
    /** Pushing the history entry and reporting the result. */
    FINALIZE
}
