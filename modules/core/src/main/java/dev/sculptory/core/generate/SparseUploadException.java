package dev.sculptory.core.generate;

import java.util.Objects;

/** A sparse upload payload the decoder refuses; nothing of it is kept. */
public final class SparseUploadException extends Exception {
    /** Why. */
    public enum Kind {
        /** Bytes that are not a payload, or a payload contradicting its announcement. */
        MALFORMED,
        /** Over a cap (cells, sections, bytes). */
        TOO_LARGE,
        /** A palette state the state space does not know. */
        UNKNOWN_STATE,
        /** A palette state whose block has a block entity: never accepted. */
        BLOCK_ENTITY
    }

    private final Kind kind;

    public SparseUploadException(Kind kind, String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind);
    }

    public Kind kind() {
        return kind;
    }
}
