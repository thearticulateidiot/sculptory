package dev.sculptory.protocol.v2;

import java.util.Objects;

/** Malformed, oversized or unexpected protocol input. Never caused by (and never causes) an OOM. */
public final class ProtocolException extends Exception {
    public enum Reason {
        /** Truncated input, bad varint, negative length, invalid field value. */
        MALFORMED,
        /** A frame, string, list or stream over its cap. */
        TOO_LARGE,
        /** An unknown message type code, or a type sent in the wrong direction. */
        UNKNOWN_TYPE,
        /** A block-state string the receiver's {@code StateSpace} cannot resolve. */
        UNKNOWN_STATE,
        /** The message is valid but not allowed now (e.g. before the handshake). */
        UNEXPECTED
    }

    private final Reason reason;

    public ProtocolException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason);
    }

    public ProtocolException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason);
    }

    public Reason reason() {
        return reason;
    }
}
