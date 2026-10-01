package dev.sculptory.fabric.client.editor.tool;

/**
 * Keymap actions offered to the active tool before the editor handles them. Nudges are camera-relative;
 * the tool reads Shift from {@link ToolContext#modifiers()} for the ×10 step.
 */
public enum EditorAction {
    COPY,
    CUT,
    PASTE,
    ERASE_SELECTION,
    DESELECT,
    COMMIT,
    /** One rung of the Esc ladder: cancel the drag, stroke or preview. */
    CANCEL,
    ROTATE_CW,
    ROTATE_CCW,
    FLIP_LEFT_RIGHT,
    FLIP_FRONT_BACK,
    /** Place: flip the placement upside down, or back (V). */
    FLIP_UPSIDE_DOWN,
    NUDGE_FORWARD,
    NUDGE_BACK,
    NUDGE_LEFT,
    NUDGE_RIGHT,
    NUDGE_UP,
    NUDGE_DOWN,
    EYEDROPPER,
    /**
     * Undo (Ctrl+Z), offered to the tool before the server history: a tool with local, uncommitted steps (the Scatter
     * tool's painted area) takes it; otherwise the editor undoes the last server edit.
     */
    UNDO,
    /** Brushes: set the symmetry centre at the cursor (Shift: the nearest block corner). */
    SET_SYMMETRY_CENTRE,
    /** Generators: remove the Path generator's selected node, or its last one (Backspace; Delete does it too). */
    REMOVE_NODE
}
