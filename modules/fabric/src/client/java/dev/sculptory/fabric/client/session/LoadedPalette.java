package dev.sculptory.fabric.client.session;

import dev.sculptory.core.palette.BlockPalette;
import java.util.List;
import java.util.Objects;

/**
 * A library palette as the server knows it ({@code PaletteData}): its path, the entries whose states the server knows
 * (its own state text), and how many entries it left out, the first few named.
 */
public record LoadedPalette(String path, BlockPalette palette, int dropped, List<String> droppedStates) {
    public LoadedPalette {
        Objects.requireNonNull(path);
        Objects.requireNonNull(palette);
        droppedStates = List.copyOf(droppedStates);
        if (dropped < droppedStates.size()) throw new IllegalArgumentException("More names than dropped entries");
    }

    /** Nothing left out. */
    public LoadedPalette(String path, BlockPalette palette) {
        this(path, palette, 0, List.of());
    }
}
