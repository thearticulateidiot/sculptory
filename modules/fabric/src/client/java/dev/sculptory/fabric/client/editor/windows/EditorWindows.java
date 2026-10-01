package dev.sculptory.fabric.client.editor.windows;

import java.util.List;

/** Ids of the editor's windows (keys in {@code editor-layout.json}) and their title keys. */
public final class EditorWindows {
    public static final String TOOL_SETTINGS = "tool_settings";
    public static final String SELECTION = "selection";
    public static final String CLIPBOARD = "clipboard";
    public static final String LIBRARY = "library";
    public static final String HISTORY = "history";
    public static final String KEYS = "keys";
    /** The Notifications window: not in {@link #MENU}; View > Notifications is its own command. */
    public static final String NOTIFICATIONS = "notifications";
    /**
     * The Tutorial window, the lessons: not in {@link #MENU}; Help > Tutorial and View > Tutorial are commands of their
     * own, as View > Notifications is.
     */
    public static final String TUTORIAL = "tutorial";
    /**
     * The Wiki window ({@link WikiWindow}): not in {@link #MENU} (too large for a default place of its own beside the
     * others); View > Wiki and Help > Wiki are their own commands.
     */
    public static final String WIKI = "wiki";
    /**
     * The Tinker panel ({@link TinkerWindow}), opened by the Tinker tool's click: not in {@link #MENU} (it shows what
     * the tool clicked).
     */
    public static final String TINKER = "tinker";
    /**
     * The Mask window ({@code MaskWindow}), opened by the top bar's Mask chip: not in
     * {@link #MENU} (the chip stands for it).
     */
    public static final String MASK = "mask";

    /** The windows listed in the top bar's Windows menu, in order. */
    public static final List<String> MENU = List.of(TOOL_SETTINGS, SELECTION, CLIPBOARD, LIBRARY, HISTORY, KEYS);

    private EditorWindows() {}

    public static String titleKey(String id) {
        return "sculptory.window." + id;
    }
}
