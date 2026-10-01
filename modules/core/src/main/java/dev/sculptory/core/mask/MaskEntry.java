package dev.sculptory.core.mask;

import java.util.Objects;

/** One rule of an {@link EditMask}, or its opposite when {@code not} ("Not" in the rule list). */
public record MaskEntry(MaskRule rule, boolean not) {
    public MaskEntry {
        Objects.requireNonNull(rule);
    }

    /** The rule as it is. */
    public static MaskEntry of(MaskRule rule) {
        return new MaskEntry(rule, false);
    }
}
