package dev.sculptory.core;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Objects;

final class Checks {
    private Checks() {}
    static final Comparator<String> UTF8 = (a, b) ->
            java.util.Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    static String text(String value, int maxBytes) {
        Objects.requireNonNull(value);
        if (value.isEmpty() || value.length() > maxBytes) throw new IllegalArgumentException("Text length");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw new IllegalArgumentException("Unpaired surrogate");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Unpaired surrogate");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > maxBytes)
            throw new IllegalArgumentException("UTF-8 length");
        return value;
    }
    static int positive(int value) {
        if (value < 1) throw new IllegalArgumentException("Must be positive");
        return value;
    }
}
