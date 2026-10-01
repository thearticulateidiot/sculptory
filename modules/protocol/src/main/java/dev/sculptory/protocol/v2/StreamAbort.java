package dev.sculptory.protocol.v2;

import java.util.Objects;

/** Abandons stream {@code id}. {@code reason} is a short machine-readable key. Both directions. */
public record StreamAbort(int id, String reason) implements C2S, S2C {
    public StreamAbort {
        Objects.requireNonNull(reason);
    }
}
