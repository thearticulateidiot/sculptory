package dev.sculptory.protocol.v2;

import dev.sculptory.core.Sha256;
import java.util.Objects;

/** Completes stream {@code id}; {@code sha256} covers all its bytes. Both directions. */
public record StreamEnd(int id, Sha256 sha256) implements C2S, S2C {
    public StreamEnd {
        Objects.requireNonNull(sha256);
    }
}
