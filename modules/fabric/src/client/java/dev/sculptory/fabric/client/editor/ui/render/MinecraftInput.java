package dev.sculptory.fabric.client.editor.ui.render;

import net.minecraft.client.gui.screen.Screen;
import org.lwjgl.glfw.GLFW;

/** Reads live keyboard state that vanilla mouse callbacks don't pass along. */
public final class MinecraftInput {
    private MinecraftInput() {
    }

    /** GLFW modifier bits (Shift, Ctrl, Alt) currently held. */
    public static int modifiers() {
        int modifiers = 0;
        if (Screen.hasShiftDown()) {
            modifiers |= GLFW.GLFW_MOD_SHIFT;
        }
        if (Screen.hasControlDown()) {
            modifiers |= GLFW.GLFW_MOD_CONTROL;
        }
        if (Screen.hasAltDown()) {
            modifiers |= GLFW.GLFW_MOD_ALT;
        }
        return modifiers;
    }
}
