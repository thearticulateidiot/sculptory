package dev.sculptory.fabric.client.editor.tool;

import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import java.util.List;

/**
 * An editor tool. Input handlers return true when they consumed the event. All calls happen on the client
 * thread, between {@link #activate} and {@link #deactivate}.
 */
public interface Tool {
    ToolDescriptor descriptor();

    SettingsSchema schema();

    RaycastMode raycastMode(SettingsValues s);

    /**
     * How this tool's cursor ray sees the world now: {@code null} (the default) for the world as it is, else cells
     * to see as other states (a Shape brush press looks through the shapes it placed). Asked before every pick for the
     * tool.
     */
    default RayOverlay rayOverlay() {
        return null;
    }

    default void activate(ToolContext c) {}

    default void deactivate(ToolContext c, DeactivateReason r) {}

    default boolean onPointer(ToolContext c, PointerEvent e) {
        return false;
    }

    default boolean onScroll(ToolContext c, ScrollEvent e) {
        return false;
    }

    /**
     * Whether the tool takes a plain Scroll (bound to fly speed) now, with {@code modifiers} held: the Tinker tool does
     * while it points at something Scroll changes. The editor then gives the Scroll to {@link #onScroll} instead of
     * changing the fly speed. False by default.
     */
    default boolean takesScroll(ToolContext c, int modifiers) {
        return false;
    }

    default boolean onAction(ToolContext c, EditorAction a) {
        return false;
    }

    default void onSettingsChanged(ToolContext c, SettingsValues before, SettingsValues after) {}

    default void frame(ToolContext c, FrameInfo f) {}

    default void renderWorld(ToolContext c, WorldDraw d) {}

    default void renderHud(ToolContext c, HudDraw d) {}

    default List<KeyHint> hints(ToolContext c) {
        return List.of();
    }
}
