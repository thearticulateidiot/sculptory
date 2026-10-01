package dev.sculptory.core.palette;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The content of a named block palette (Palettes): 1 to {@value #MAX_ENTRIES} block states,
 * each given by its exact state text ({@code StateSpace.format}, for example
 * {@code minecraft:sea_pickle[pickles=2,waterlogged=true]}) with a weight of 1 to {@value #MAX_WEIGHT}, in order, and
 * the Pattern setting that lays them out ({@link PalettePattern}: the order drives a Gradient and
 * Steepness). A state text appears once. Blocks only: no clipboards or library assets.
 *
 * <p>The texts are not resolved here: whoever uses a palette resolves them against its own state space (the server
 * refuses a save naming a state it doesn't know, and leaves such states out of a load).
 */
public record BlockPalette(List<Entry> entries, PalettePattern pattern) {
    public static final int MAX_ENTRIES = 64;
    public static final int MAX_WEIGHT = 1000;
    /**
     * The most UTF-8 bytes of one state text (as for a scatter block variant), so a palette of {@value #MAX_ENTRIES}
     * entries always fits one client frame. Vanilla's longest state texts are under 100 bytes.
     */
    public static final int MAX_STATE_BYTES = 256;

    /** One block state and its weight. */
    public record Entry(String state, int weight) {
        public Entry {
            Objects.requireNonNull(state);
            if (state.isBlank()) throw new IllegalArgumentException("An empty block state");
            if (state.getBytes(StandardCharsets.UTF_8).length > MAX_STATE_BYTES) {
                throw new IllegalArgumentException("A block state over " + MAX_STATE_BYTES + " bytes");
            }
            if (weight < 1 || weight > MAX_WEIGHT) throw new IllegalArgumentException("Weight must be 1-" + MAX_WEIGHT);
        }
    }

    public BlockPalette {
        entries = List.copyOf(entries);
        Objects.requireNonNull(pattern);
        if (entries.isEmpty() || entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("A palette holds 1-" + MAX_ENTRIES + " blocks, not " + entries.size());
        }
        Set<String> seen = new HashSet<>();
        for (Entry entry : entries) {
            if (!seen.add(entry.state())) throw new IllegalArgumentException("Listed twice: " + entry.state());
        }
    }

    /** A palette laid out at random ({@link PalettePattern#RANDOM}), as every palette was before patterns existed. */
    public BlockPalette(List<Entry> entries) {
        this(entries, PalettePattern.RANDOM);
    }

    /** A palette of one block. */
    public static BlockPalette of(String state, int weight) {
        return new BlockPalette(List.of(new Entry(state, weight)));
    }

    /** This palette's blocks with another pattern. */
    public BlockPalette withPattern(PalettePattern pattern) {
        return new BlockPalette(entries, pattern);
    }

    public int size() {
        return entries.size();
    }
}
