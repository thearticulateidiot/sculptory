package dev.sculptory.fabric.client.editor.ui.widget;

import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import org.lwjgl.glfw.GLFW;

/**
 * Press behaviour shared by every button: fires on left-button release over the button, or on
 * Enter/Space while focused, and only when enabled. Subclasses supply measuring and drawing.
 */
public abstract class AbstractButton extends Node {
    private Runnable onClick;
    private boolean pressed;

    protected AbstractButton(Runnable onClick) {
        this.onClick = onClick;
    }

    public AbstractButton setOnClick(Runnable onClick) {
        this.onClick = onClick;
        return this;
    }

    /** True while the left button is held after pressing on this button. */
    protected final boolean isPressed() {
        return pressed;
    }

    /** Runs the action if the button is enabled. */
    public void click() {
        if (isEffectivelyEnabled() && onClick != null) {
            onClick.run();
        }
    }

    @Override
    public boolean mouseDown(UiContext ctx, double x, double y, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        pressed = true;
        return true;
    }

    @Override
    public void mouseUp(UiContext ctx, double x, double y, int button) {
        boolean wasPressed = pressed;
        pressed = false;
        if (wasPressed && bounds.contains(x, y)) {
            click();
        }
    }

    @Override
    public boolean keyPressed(UiContext ctx, int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_SPACE) {
            click();
            return true;
        }
        return false;
    }

    @Override
    public boolean isFocusable() {
        return true;
    }
}
