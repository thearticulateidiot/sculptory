package dev.sculptory.fabric.client.editor.tool;

/** Keyboard modifier bits, with GLFW's values ({@code GLFW_MOD_*}). */
public final class Modifiers {
    private Modifiers() {}

    public static final int SHIFT = 0x1;
    public static final int CONTROL = 0x2;
    public static final int ALT = 0x4;
    public static final int SUPER = 0x8;

    public static boolean shift(int modifiers) {
        return (modifiers & SHIFT) != 0;
    }

    public static boolean control(int modifiers) {
        return (modifiers & CONTROL) != 0;
    }

    public static boolean alt(int modifiers) {
        return (modifiers & ALT) != 0;
    }
}
