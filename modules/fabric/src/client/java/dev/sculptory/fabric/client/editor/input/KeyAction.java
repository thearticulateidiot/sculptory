package dev.sculptory.fabric.client.editor.input;

import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.ToolRegistry;
import java.util.List;
import java.util.Optional;

/**
 * Everything the editor keymap can trigger, with its default chords.
 * {@link #id()} is the key in {@code editor-keys.json}; the display name is the translation of
 * {@link #labelKey()}.
 */
public enum KeyAction {
    TOOL_1("tool_1", Group.TOOLS, false, null, "1"),
    TOOL_2("tool_2", Group.TOOLS, false, null, "2"),
    TOOL_3("tool_3", Group.TOOLS, false, null, "3"),
    TOOL_4("tool_4", Group.TOOLS, false, null, "4"),
    TOOL_5("tool_5", Group.TOOLS, false, null, "5"),
    TOOL_6("tool_6", Group.TOOLS, false, null, "6"),
    TOOL_7("tool_7", Group.TOOLS, false, null, "7"),
    TOOL_8("tool_8", Group.TOOLS, false, null, "8"),
    TOOL_9("tool_9", Group.TOOLS, false, null, "9"),
    TOOL_10("tool_10", Group.TOOLS, false, null, "0"),
    TOOL_11("tool_11", Group.TOOLS, false, null, "minus"),
    TOOL_12("tool_12", Group.TOOLS, false, null, "equal"),
    TOOL_13("tool_13", Group.TOOLS, false, null, "left_bracket"),
    TOOL_14("tool_14", Group.TOOLS, false, null, "right_bracket"),
    TOOL_15("tool_15", Group.TOOLS, false, null, "backslash"),
    TOOL_SIZE("tool_size", Group.TOOLS, true, null, "ctrl+scroll"),
    TOOL_STRENGTH("tool_strength", Group.TOOLS, false, null, "alt+scroll"),
    EYEDROPPER("eyedropper", Group.TOOLS, false, EditorAction.EYEDROPPER, "mouse.middle"),
    // Unbound by default: no free key suggests it (W walks), and a stray press would move where every tool aims.
    AIM_AT_FLUIDS("aim_at_fluids", Group.TOOLS, false, null),
    FLY_SPEED("fly_speed", Group.MOVEMENT, false, null, "scroll"),
    UNDO("undo", Group.HISTORY, false, EditorAction.UNDO, "ctrl+z"),
    REDO("redo", Group.HISTORY, false, null, "ctrl+y", "ctrl+shift+z"),
    ERASE_SELECTION("erase_selection", Group.SELECTION, false, EditorAction.ERASE_SELECTION, "delete"),
    DESELECT("deselect", Group.SELECTION, false, EditorAction.DESELECT, "ctrl+d"),
    NUDGE_FORWARD("nudge_forward", Group.SELECTION, true, EditorAction.NUDGE_FORWARD, "up"),
    NUDGE_BACK("nudge_back", Group.SELECTION, true, EditorAction.NUDGE_BACK, "down"),
    NUDGE_LEFT("nudge_left", Group.SELECTION, true, EditorAction.NUDGE_LEFT, "left"),
    NUDGE_RIGHT("nudge_right", Group.SELECTION, true, EditorAction.NUDGE_RIGHT, "right"),
    NUDGE_UP("nudge_up", Group.SELECTION, true, EditorAction.NUDGE_UP, "page_up"),
    NUDGE_DOWN("nudge_down", Group.SELECTION, true, EditorAction.NUDGE_DOWN, "page_down"),
    COPY("copy", Group.CLIPBOARD, false, EditorAction.COPY, "ctrl+c"),
    CUT("cut", Group.CLIPBOARD, false, EditorAction.CUT, "ctrl+x"),
    PASTE("paste", Group.CLIPBOARD, false, EditorAction.PASTE, "ctrl+v"),
    COMMIT("commit", Group.CLIPBOARD, false, EditorAction.COMMIT, "enter"),
    ROTATE_CW("rotate_cw", Group.CLIPBOARD, false, EditorAction.ROTATE_CW, "r"),
    ROTATE_CCW("rotate_ccw", Group.CLIPBOARD, false, EditorAction.ROTATE_CCW, "shift+r"),
    FLIP_LEFT_RIGHT("flip_left_right", Group.CLIPBOARD, false, EditorAction.FLIP_LEFT_RIGHT, "f"),
    FLIP_FRONT_BACK("flip_front_back", Group.CLIPBOARD, false, EditorAction.FLIP_FRONT_BACK, "shift+f"),
    FLIP_UPSIDE_DOWN("flip_upside_down", Group.CLIPBOARD, false, EditorAction.FLIP_UPSIDE_DOWN, "v"),
    HIDE_WINDOWS("hide_windows", Group.WINDOWS, false, null, "tab"),
    HELP("help", Group.WINDOWS, false, null, "f1"),
    LIBRARY("library", Group.WINDOWS, false, null, "l"),
    HISTORY("history", Group.WINDOWS, false, null, "h"),
    UI_SMALLER("ui_smaller", Group.WINDOWS, false, null, "ctrl+minus", "ctrl+kp_subtract"),
    // Ctrl+Shift+= is "Ctrl++" on a US layout.
    UI_LARGER("ui_larger", Group.WINDOWS, false, null, "ctrl+equal", "ctrl+shift+equal", "ctrl+kp_add"),
    UI_RESET("ui_reset", Group.WINDOWS, false, null, "ctrl+0", "ctrl+kp_0"),
    // Brushes: the cursor's block centre; with Shift, the nearest block corner.
    SET_SYMMETRY_CENTRE("symmetry_centre", Group.TOOLS, true, EditorAction.SET_SYMMETRY_CENTRE, "m"),
    // Generators: removes the Path generator's selected (or last) node; Delete does the same while the path has nodes.
    REMOVE_NODE("remove_node", Group.TOOLS, false, EditorAction.REMOVE_NODE, "backspace"),
    // Find a command: the search box over every menu item, tool, window, setting and preset.
    COMMAND_SEARCH("command_search", Group.WINDOWS, false, null, "ctrl+k"),
    // The keyboard into the next open window's first control; Shift goes back (a shift variant, like the nudges).
    FOCUS_NEXT_WINDOW("focus_next_window", Group.WINDOWS, true, null, "f6"),
    // Jump and Through: onto the block looked at, or through the wall.
    JUMP("jump", Group.MOVEMENT, false, null, "j"),
    JUMP_THROUGH("jump_through", Group.MOVEMENT, false, null, "shift+j"),
    // The global mask on every edit: on and off, keeping its rules.
    TOGGLE_MASK("toggle_mask", Group.TOOLS, false, null, "ctrl+m");

    /** Where an action is listed in the help sheet and the Keys window. */
    public enum Group { MOVEMENT, TOOLS, SELECTION, HISTORY, CLIPBOARD, WINDOWS }

    private final String id;
    private final Group group;
    private final boolean shiftVariant;
    private final EditorAction editorAction;
    private final List<String> defaults;

    KeyAction(String id, Group group, boolean shiftVariant, EditorAction editorAction, String... defaults) {
        this.id = id;
        this.group = group;
        this.shiftVariant = shiftVariant;
        this.editorAction = editorAction;
        this.defaults = List.of(defaults);
    }

    public String id() {
        return id;
    }

    public Group group() {
        return group;
    }

    public String labelKey() {
        return "sculptory.key." + id;
    }

    /**
     * True when Shift changes the action's amount rather than choosing another action (nudge ×10,
     * size ×4), so the chord also matches with Shift held.
     */
    public boolean shiftVariant() {
        return shiftVariant;
    }

    /**
     * True when holding the key must not fire the action again: each undo or redo is a server edit, so the
     * operating system's key repeat would stack up requests, a held "aim at water and lava" would flicker on and
     * off, and the symmetry centre key toggles (a repeat would set and clear the centre by turns), as the mask key
     * does; a held Jump would teleport again and again. Other actions (nudge) repeat while held.
     */
    public boolean ignoresRepeat() {
        return this == UNDO || this == REDO || this == AIM_AT_FLUIDS || this == SET_SYMMETRY_CENTRE || this == JUMP
                || this == JUMP_THROUGH || this == TOGGLE_MASK;
    }

    /** The tool action this key offers to the active tool first, if any. */
    public Optional<EditorAction> editorAction() {
        return Optional.ofNullable(editorAction);
    }

    public List<KeyChord> defaultChords() {
        return defaults.stream().map(KeyChord::parse).toList();
    }

    /** The palette slot (1-15) for TOOL_n actions, else 0. */
    public int toolSlot() {
        return ordinal() <= TOOL_15.ordinal() ? ordinal() - TOOL_1.ordinal() + 1 : 0;
    }

    public static Optional<KeyAction> byId(String id) {
        for (KeyAction action : values()) {
            if (action.id.equals(id)) {
                return Optional.of(action);
            }
        }
        return Optional.empty();
    }

    public static KeyAction toolSlot(int slot) {
        if (slot < 1 || slot > ToolRegistry.PALETTE_SLOTS) {
            throw new IllegalArgumentException("Tool slot must be 1-" + ToolRegistry.PALETTE_SLOTS + ": " + slot);
        }
        return values()[TOOL_1.ordinal() + slot - 1];
    }
}
