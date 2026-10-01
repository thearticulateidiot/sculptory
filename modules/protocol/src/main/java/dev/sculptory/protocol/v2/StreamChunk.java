package dev.sculptory.protocol.v2;

import java.util.Arrays;
import java.util.Objects;

/** Data for stream {@code id}; {@code seq} counts from 0. The array is copied in and out. Both directions. */
public record StreamChunk(int id, int seq, byte[] bytes) implements C2S, S2C {
    public StreamChunk {
        Objects.requireNonNull(bytes);
        if (seq < 0) throw new IllegalArgumentException("Negative chunk sequence");
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    public int length() {
        return bytes.length;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StreamChunk other && id == other.id && seq == other.seq && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, seq, Arrays.hashCode(bytes));
    }

    @Override
    public String toString() {
        return "StreamChunk[id=" + id + ", seq=" + seq + ", " + bytes.length + " bytes]";
    }
}
