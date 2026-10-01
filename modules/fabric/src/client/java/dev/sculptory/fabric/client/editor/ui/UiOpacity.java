package dev.sculptory.fabric.client.editor.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * How see-through the editor is (View > Opacity…): the <b>Panels</b> opacity of the editor's windows, top bar,
 * palette, hint line and toasts (their backgrounds only: {@link FadedGraphics}), whether panels fade <b>only when not
 * hovered</b> ({@link PanelFade}), and the <b>Tool outlines</b> opacity of everything tools draw in the world
 * ({@code render.OverlayOpacity}). Saved in {@code config/sculptory/editor-ui.json} with the UI size
 * ({@code windows.UiSizeStore}).
 *
 * <p>Client thread only; the listeners run on every change, in the order they were added.
 */
public final class UiOpacity {
    /** The Panels slider's range, in percent. */
    public static final int PANELS_MIN = 20;
    /** The Tool outlines slider's range, in percent: never so low that an outline disappears. */
    public static final int OUTLINES_MIN = 10;
    public static final int MAX = 100;

    /** The three settings at once. Percentages are clamped to their ranges. */
    public record Values(int panels, boolean fadeUnlessHovered, int toolOutlines) {
        /** Everything opaque, panels always: how the editor looked before the setting existed. */
        public static final Values DEFAULT = new Values(MAX, false, MAX);

        public Values {
            panels = clamp(panels, PANELS_MIN);
            toolOutlines = clamp(toolOutlines, OUTLINES_MIN);
        }

        /** The Panels opacity as a fraction, 0.2 to 1. */
        public float panelAlpha() {
            return panels / 100.0F;
        }

        /** The Tool outlines opacity as a fraction, 0.1 to 1. */
        public float outlineAlpha() {
            return toolOutlines / 100.0F;
        }

        public Values withPanels(int percent) {
            return new Values(percent, fadeUnlessHovered, toolOutlines);
        }

        public Values withFadeUnlessHovered(boolean fade) {
            return new Values(panels, fade, toolOutlines);
        }

        public Values withToolOutlines(int percent) {
            return new Values(panels, fadeUnlessHovered, percent);
        }

        private static int clamp(int percent, int min) {
            return Math.max(min, Math.min(MAX, percent));
        }
    }

    private Values values = Values.DEFAULT;
    private final List<Consumer<Values>> listeners = new ArrayList<>();

    public Values values() {
        return values;
    }

    /** Adds a listener, called with the new values after each change: the editor saves them, overlays follow them. */
    public void addListener(Consumer<Values> listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    /** Takes new values. Returns true if they changed (the listeners then ran). */
    public boolean set(Values next) {
        Objects.requireNonNull(next);
        if (next.equals(values)) {
            return false;
        }
        values = next;
        for (Consumer<Values> listener : listeners) {
            listener.accept(next);
        }
        return true;
    }
}
