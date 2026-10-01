package dev.sculptory.protocol.v2;

import java.util.Collections;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/** Opens stream {@code id}. {@code meta} is kind-specific (at most {@value #MAX_META} entries). Both directions. */
public record StreamOpen(int id, StreamKind kind, long totalBytes, SortedMap<String, String> meta)
        implements C2S, S2C {
    public static final int MAX_META = 16;

    public StreamOpen {
        Objects.requireNonNull(kind);
        if (totalBytes < 0) throw new IllegalArgumentException("Negative stream size");
        if (meta.size() > MAX_META) throw new IllegalArgumentException("Too many stream meta entries");
        meta = Collections.unmodifiableSortedMap(new TreeMap<>(meta));
    }
}
