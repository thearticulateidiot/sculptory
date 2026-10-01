package dev.sculptory.core.edit;

import java.util.Objects;

/**
 * Paste behaviour: whether source air overwrites the world, whether block updates run, whether the source's
 * entities are pasted too (with new UUIDs; off pastes blocks only), and which landing cells may be written
 * ({@link Into}).
 */
public record PasteOptions(boolean includeAir, boolean physics, boolean entities, Into into) {
    /**
     * Which landing cells a paste, move or stack writes, judged by the cell's content right before the write: air
     * is a state with {@code StateFlags.AIR}; fluids and replaceable plants count as existing blocks. A cell the
     * filter leaves out is not written, not claimed and not counted as changed. Append-only (the wire sends the
     * ordinal).
     */
    public enum Into {
        /** Every landing cell (the plain paste). */
        EVERYTHING,
        /** Only landing cells holding a block that is not air: the source lands on what is there, air stays air. */
        EXISTING,
        /** Only landing cells holding air: what is there stays. */
        AIR
    }

    public static final PasteOptions DEFAULT = new PasteOptions(false, false, true, Into.EVERYTHING);

    public PasteOptions {
        Objects.requireNonNull(into);
    }

    /** Options that write every landing cell. */
    public PasteOptions(boolean includeAir, boolean physics, boolean entities) {
        this(includeAir, physics, entities, Into.EVERYTHING);
    }

    /** Options that paste entities and write every landing cell. */
    public PasteOptions(boolean includeAir, boolean physics) {
        this(includeAir, physics, true, Into.EVERYTHING);
    }

    /** These options writing {@code into}. */
    public PasteOptions withInto(Into into) {
        return new PasteOptions(includeAir, physics, entities, into);
    }
}
