package dev.sculptory.protocol.v2;

/** Grants the sender of stream {@code id} {@code bytes} more bytes of window. Both directions. */
public record StreamCredit(int id, long bytes) implements C2S, S2C {
    public StreamCredit {
        if (bytes < 0) throw new IllegalArgumentException("Negative credit");
    }
}
