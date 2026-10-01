package dev.sculptory.core.brush;

import java.util.Objects;

/** What the Weather brush does to the surface ({@link BrushTool#WEATHER}). */
public record WeatherSpec(Mode mode) {
    /** Wire order: append only. */
    public enum Mode {
        /** Wears exposed edges and corners away. */
        ERODE,
        /** Fills small pits and gaps with the blocks around them. */
        FILL_IN,
        /** Breaks a smooth surface up. */
        ROUGHEN,
        /** Slumps edges and overhangs down, as if melting. */
        MELT
    }

    public WeatherSpec {
        Objects.requireNonNull(mode);
    }
}
