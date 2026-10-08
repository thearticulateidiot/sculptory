package dev.sculptory.core.edit;

import java.util.Objects;

/**
 * Paste behaviour: whether source air overwrites the world, whether block updates run, whether the source's
 * entities are pasted too (with new UUIDs; off pastes blocks only), which landing cells may be written
 * ({@link Into}), and whether the source's biomes are pasted (protocol 6).
 *
 * @param biomes whether the source's biomes are written too (the Paste window's Biomes toggle, WorldEdit's {@code -b}):
 *     each quart of the result takes the biome of the source quart {@code Orientation.inverseCell} gives at quart
 *     resolution, the paste's offset rounded to the nearest quart; a source without biomes writes none. Off by default
 */
public record PasteOptions(boolean includeAir, boolean physics, boolean entities, Into into, boolean biomes) {
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

    /** Options that paste no biomes. */
    public PasteOptions(boolean includeAir, boolean physics, boolean entities, Into into) {
        this(includeAir, physics, entities, into, false);
    }

    /** Options that write every landing cell and paste no biomes. */
    public PasteOptions(boolean includeAir, boolean physics, boolean entities) {
        this(includeAir, physics, entities, Into.EVERYTHING);
    }

    /** Options that paste entities, write every landing cell and paste no biomes. */
    public PasteOptions(boolean includeAir, boolean physics) {
        this(includeAir, physics, true, Into.EVERYTHING);
    }

    /** These options writing {@code into}. */
    public PasteOptions withInto(Into into) {
        return new PasteOptions(includeAir, physics, entities, into, biomes);
    }

    /** These options pasting biomes or not. */
    public PasteOptions withBiomes(boolean biomes) {
        return new PasteOptions(includeAir, physics, entities, into, biomes);
    }
}
