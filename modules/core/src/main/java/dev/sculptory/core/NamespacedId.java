package dev.sculptory.core;

import java.util.regex.Pattern;

/** Stable ASCII identity, independent of any platform registry. */
public record NamespacedId(String value) implements Comparable<NamespacedId> {
    /** Compiled once: ids are parsed on the network path. */
    private static final Pattern FORMAT = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    public NamespacedId {
        Checks.text(value, 256);
        if (!FORMAT.matcher(value).matches())
            throw new IllegalArgumentException("Invalid namespaced ID");
    }
    @Override public int compareTo(NamespacedId other) { return value.compareTo(other.value); }
}
