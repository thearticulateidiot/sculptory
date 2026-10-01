package dev.sculptory.fabric.client.editor.ui;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * How opaque each panel is drawn this frame, from {@link UiOpacity}: the Panels opacity, or, with "Fade only when not
 * hovered" on, fully opaque while the panel is <em>engaged</em> (under the pointer, holding the keyboard, being dragged,
 * or with a popup of its own open: the caller decides) and the Panels opacity otherwise. A panel is a window, or a
 * group drawn as one (the top bar, palette and hint line; the toasts), named by any key object.
 *
 * <p>With the toggle on, a panel changes over {@value #FADE_MS} ms (a full fade from 0 to 1 would take that long)
 * instead of at once; a panel seen for the first time starts at its target. Client thread only.
 */
public final class PanelFade {
    /** How long a fade from fully clear to fully opaque takes. */
    public static final int FADE_MS = 150;

    private static final class State {
        float alpha;
        long atMs;

        State(float alpha, long atMs) {
            this.alpha = alpha;
            this.atMs = atMs;
        }
    }

    private final UiOpacity opacity;
    private final Map<Object, State> states = new IdentityHashMap<>();

    public PanelFade(UiOpacity opacity) {
        this.opacity = Objects.requireNonNull(opacity);
    }

    public UiOpacity opacity() {
        return opacity;
    }

    /** The opacity (0.2 to 1) to draw {@code panel} with at {@code nowMs}, moving towards its target. */
    public float alpha(Object panel, boolean engaged, long nowMs) {
        UiOpacity.Values values = opacity.values();
        float target = values.fadeUnlessHovered() && engaged ? 1.0F : values.panelAlpha();
        State state = states.get(panel);
        if (state == null) {
            states.put(panel, new State(target, nowMs));
            return target;
        }
        if (!values.fadeUnlessHovered()) {
            // The slider applies at once while it is dragged; the fade is only for the pointer coming and going.
            state.alpha = target;
        } else {
            long elapsed = Math.max(0, Math.min(FADE_MS, nowMs - state.atMs));
            float step = elapsed / (float) FADE_MS;
            state.alpha = state.alpha < target ? Math.min(target, state.alpha + step) : Math.max(target, state.alpha - step);
        }
        state.atMs = nowMs;
        return state.alpha;
    }
}
