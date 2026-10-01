package dev.sculptory.fabric.client.editor.presets;

import dev.sculptory.fabric.client.session.Notice;
import java.util.List;
import java.util.Objects;

/**
 * State a tool keeps outside its settings schema that its presets hold too (the Scatter tool's variant mix). Each
 * extra has its own text form, saved under its key in the preset's {@code extra} object; {@code ""} stands for the
 * tool's default state (what "Default" and a preset saved without it give). Client thread only.
 */
public interface PresetExtra {
    /**
     * The current state as preset text.
     *
     * @param note shown to the player when part of the state can't be kept in a preset, or null
     */
    record Capture(String text, Notice note) {
        public Capture {
            Objects.requireNonNull(text);
        }
    }

    /** What applying left out, named for the player: parts that couldn't be read, and parts this game doesn't have. */
    record Applied(List<String> skipped, List<String> unavailable) {
        public static final Applied CLEAN = new Applied(List.of(), List.of());

        public Applied {
            skipped = List.copyOf(skipped);
            unavailable = List.copyOf(unavailable);
        }
    }

    Capture capture();

    /** Replaces the state with what {@code text} describes, using what it can. */
    Applied apply(String text);

    /** Whether the current state is what {@link #apply} of {@code text} gives. */
    boolean matches(String text);
}
