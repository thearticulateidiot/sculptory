package dev.sculptory.fabric.client.editor.ui.render;

import dev.sculptory.fabric.client.editor.ui.UiCursor;
import java.util.Arrays;
import java.util.stream.LongStream;
import org.lwjgl.glfw.GLFW;

/**
 * Shows the editor's {@link UiCursor} shapes on the game window with GLFW standard cursors.
 *
 * <p>The cursors are created once, on first use, and destroyed by {@link #close()}. The diagonal
 * resize cursors need GLFW 3.4 (the bundled LWJGL 3.3.3 has it); with an older GLFW, or where the
 * platform lacks them, the corners show the crosshair instead, and a shape that can't be created at
 * all shows the normal arrow. {@link #set} only calls GLFW when the shape changes, and works whether
 * or not the window has focus. Render thread only.
 */
public final class GlfwCursors implements AutoCloseable {
    private final long window;
    private final long[] cursors = new long[UiCursor.values().length];
    private boolean created;
    private UiCursor current = UiCursor.DEFAULT;

    /** @param window the game window's GLFW handle */
    public GlfwCursors(long window) {
        this.window = window;
    }

    /** Shows {@code cursor}; {@link UiCursor#DEFAULT} restores the game's normal cursor. */
    public void set(UiCursor cursor) {
        if (cursor == current) {
            return;
        }
        if (cursor != UiCursor.DEFAULT && !created) {
            create();
        }
        GLFW.glfwSetCursor(window, cursors[cursor.ordinal()]);
        current = cursor;
    }

    private void create() {
        created = true;
        boolean glfw34 = atLeastGlfw34();
        cursors[UiCursor.RESIZE_EW.ordinal()] = GLFW.glfwCreateStandardCursor(GLFW.GLFW_HRESIZE_CURSOR);
        cursors[UiCursor.RESIZE_NS.ordinal()] = GLFW.glfwCreateStandardCursor(GLFW.GLFW_VRESIZE_CURSOR);
        long nwse = glfw34 ? GLFW.glfwCreateStandardCursor(GLFW.GLFW_RESIZE_NWSE_CURSOR) : 0L;
        long nesw = glfw34 ? GLFW.glfwCreateStandardCursor(GLFW.GLFW_RESIZE_NESW_CURSOR) : 0L;
        long crosshair = nwse == 0L || nesw == 0L ? GLFW.glfwCreateStandardCursor(GLFW.GLFW_CROSSHAIR_CURSOR) : 0L;
        cursors[UiCursor.RESIZE_NWSE.ordinal()] = nwse != 0L ? nwse : crosshair;
        cursors[UiCursor.RESIZE_NESW.ordinal()] = nesw != 0L ? nesw : crosshair;
    }

    private static boolean atLeastGlfw34() {
        int[] major = new int[1];
        int[] minor = new int[1];
        int[] revision = new int[1];
        GLFW.glfwGetVersion(major, minor, revision);
        return major[0] > 3 || major[0] == 3 && minor[0] >= 4;
    }

    /** Restores the normal cursor and destroys the created ones (the crosshair fallback may be shared). */
    @Override
    public void close() {
        set(UiCursor.DEFAULT);
        LongStream.of(cursors).filter(cursor -> cursor != 0L).distinct().forEach(GLFW::glfwDestroyCursor);
        Arrays.fill(cursors, 0L);
        created = false;
    }
}
