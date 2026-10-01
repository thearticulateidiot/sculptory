package dev.sculptory.fabric.client.editor;

/**
 * Whether the editor is hiding the vanilla HUD (hotbar, status bars, crosshair), the hand and the
 * block outline. Read by {@code InGameHudMixin} and the block-outline event every frame.
 */
public final class EditorVisuals {
    private static volatile boolean editing;

    private EditorVisuals() {}

    public static boolean editing() {
        return editing;
    }

    public static void setEditing(boolean value) {
        editing = value;
    }
}
