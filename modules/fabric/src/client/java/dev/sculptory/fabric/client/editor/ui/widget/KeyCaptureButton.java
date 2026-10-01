package dev.sculptory.fabric.client.editor.ui.widget;

import dev.sculptory.fabric.client.editor.ui.UiContext;
import java.util.Objects;
import org.lwjgl.glfw.GLFW;

/**
 * A button that shows a key binding and, once clicked (or on Enter/Space while focused), listens for a new one: it
 * shows its listening text, keeps keyboard focus and takes every key, mouse button and scroll event in the editor
 * ({@link #capturesAllInput}) until one of these ends the listening:
 * <ul>
 *   <li>a key press, with the Ctrl/Shift/Alt held at that moment, is reported as a {@link Capture} (a modifier key
 *       on its own is waited through);</li>
 *   <li>a mouse button other than the left one, or a scroll turn, is reported when the button accepts that kind of
 *       input ({@link #setAcceptsMouse}, {@link #setAcceptsScroll}), and ignored otherwise;</li>
 *   <li>Backspace or Delete reports a clear;</li>
 *   <li>Esc (the window manager clears focus), a left click anywhere or losing focus cancels.</li>
 * </ul>
 * Modifier bits are GLFW's ({@code GLFW_MOD_SHIFT}, {@code GLFW_MOD_CONTROL}, {@code GLFW_MOD_ALT}).
 */
public class KeyCaptureButton extends Button {
    public enum Kind { KEY, MOUSE, SCROLL }

    /** An input the button caught: a GLFW key code, a GLFW mouse button, or 0 for the scroll wheel. */
    public record Capture(Kind kind, int code, int modifiers) {
        public Capture {
            Objects.requireNonNull(kind);
        }
    }

    public interface Listener {
        void captured(Capture capture);

        /** Backspace or Delete while listening: the binding should go. */
        void cleared();

        /** Listening ended without a result. */
        default void cancelled() {
        }
    }

    private final String idleText;
    private final String listeningText;
    private final Listener listener;
    private Style idleStyle = Style.DEFAULT;
    private boolean acceptsMouse;
    private boolean acceptsScroll;
    private boolean listening;

    public KeyCaptureButton(String text, String listeningText, Listener listener) {
        super(text, null);
        this.idleText = text;
        this.listeningText = Objects.requireNonNull(listeningText);
        this.listener = Objects.requireNonNull(listener);
        setOnClick(this::startListening);
    }

    /** Whether a mouse button (other than the left one) can be the new binding. */
    public KeyCaptureButton setAcceptsMouse(boolean acceptsMouse) {
        this.acceptsMouse = acceptsMouse;
        return this;
    }

    /** Whether a scroll turn (with its modifiers) can be the new binding. */
    public KeyCaptureButton setAcceptsScroll(boolean acceptsScroll) {
        this.acceptsScroll = acceptsScroll;
        return this;
    }

    @Override
    public Button setStyle(Style style) {
        idleStyle = style;
        return listening ? this : super.setStyle(style);
    }

    public boolean isListening() {
        return listening;
    }

    /** Shows the listening text and takes the next input. Focus comes with the click that called this. */
    public void startListening() {
        if (listening) {
            return;
        }
        listening = true;
        setText(listeningText);
        super.setStyle(Style.PRIMARY);
    }

    private void stopListening() {
        listening = false;
        setText(idleText);
        super.setStyle(idleStyle);
    }

    /** Ends the listening without a result, as Esc does. */
    public void cancel() {
        if (listening) {
            stopListening();
            listener.cancelled();
        }
    }

    @Override
    public boolean capturesAllInput() {
        return listening;
    }

    @Override
    public boolean focusOnClick() {
        return true;
    }

    @Override
    protected void onFocusChanged(UiContext ctx, boolean focused) {
        if (!focused) {
            cancel();
        }
    }

    @Override
    public boolean keyPressed(UiContext ctx, int keyCode, int scanCode, int modifiers) {
        if (!listening) {
            return super.keyPressed(ctx, keyCode, scanCode, modifiers);
        }
        if (isModifierKey(keyCode)) {
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            cancel();
            return true;
        }
        stopListening();
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE || keyCode == GLFW.GLFW_KEY_DELETE) {
            listener.cleared();
        } else {
            listener.captured(new Capture(Kind.KEY, keyCode, modifiers));
        }
        return true;
    }

    @Override
    public boolean charTyped(UiContext ctx, char chr, int modifiers) {
        return listening;
    }

    @Override
    public boolean mouseDown(UiContext ctx, double x, double y, int button) {
        if (!listening) {
            return super.mouseDown(ctx, x, y, button);
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            cancel();
        } else if (acceptsMouse) {
            stopListening();
            listener.captured(new Capture(Kind.MOUSE, button, ctx.modifiers()));
        }
        return true;
    }

    @Override
    public boolean mouseScroll(UiContext ctx, double x, double y, double amount) {
        if (!listening) {
            return false;
        }
        if (acceptsScroll && amount != 0) {
            stopListening();
            listener.captured(new Capture(Kind.SCROLL, 0, ctx.modifiers()));
        }
        return true;
    }

    private static boolean isModifierKey(int code) {
        return code == GLFW.GLFW_KEY_LEFT_SHIFT || code == GLFW.GLFW_KEY_RIGHT_SHIFT
                || code == GLFW.GLFW_KEY_LEFT_CONTROL || code == GLFW.GLFW_KEY_RIGHT_CONTROL
                || code == GLFW.GLFW_KEY_LEFT_ALT || code == GLFW.GLFW_KEY_RIGHT_ALT
                || code == GLFW.GLFW_KEY_LEFT_SUPER || code == GLFW.GLFW_KEY_RIGHT_SUPER;
    }
}
