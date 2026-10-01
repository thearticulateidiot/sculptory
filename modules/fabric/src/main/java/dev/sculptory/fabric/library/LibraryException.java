package dev.sculptory.fabric.library;

import dev.sculptory.protocol.v2.RejectReason;
import java.util.Objects;

/** A library request that cannot be carried out; nothing was changed. */
public final class LibraryException extends Exception {
    private final RejectReason reason;

    public LibraryException(RejectReason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason);
    }

    public LibraryException(RejectReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason);
    }

    public RejectReason reason() {
        return reason;
    }
}
