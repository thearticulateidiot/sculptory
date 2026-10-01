package dev.sculptory.fabric.client.editor.input;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.lwjgl.glfw.GLFW;

/**
 * Stable names for GLFW key codes, used in {@code editor-keys.json} ("page_up") and for display
 * ("Page Up"). Keys without a name use {@code key.<code>}.
 */
public final class KeyNames {
    private static final Map<Integer, String> NAMES = new HashMap<>();
    private static final Map<String, Integer> CODES = new HashMap<>();
    private static final Map<Integer, String> DISPLAY = new HashMap<>();

    static {
        for (int c = GLFW.GLFW_KEY_A; c <= GLFW.GLFW_KEY_Z; c++) {
            String letter = String.valueOf((char) ('a' + c - GLFW.GLFW_KEY_A));
            name(c, letter, letter.toUpperCase(Locale.ROOT));
        }
        for (int c = GLFW.GLFW_KEY_0; c <= GLFW.GLFW_KEY_9; c++) {
            String digit = String.valueOf((char) ('0' + c - GLFW.GLFW_KEY_0));
            name(c, digit, digit);
        }
        for (int i = 1; i <= 25; i++) {
            name(GLFW.GLFW_KEY_F1 + i - 1, "f" + i, "F" + i);
        }
        for (int i = 0; i <= 9; i++) {
            name(GLFW.GLFW_KEY_KP_0 + i, "kp_" + i, "Keypad " + i);
        }
        name(GLFW.GLFW_KEY_UP, "up", "Up");
        name(GLFW.GLFW_KEY_DOWN, "down", "Down");
        name(GLFW.GLFW_KEY_LEFT, "left", "Left");
        name(GLFW.GLFW_KEY_RIGHT, "right", "Right");
        name(GLFW.GLFW_KEY_PAGE_UP, "page_up", "PgUp");
        name(GLFW.GLFW_KEY_PAGE_DOWN, "page_down", "PgDn");
        name(GLFW.GLFW_KEY_HOME, "home", "Home");
        name(GLFW.GLFW_KEY_END, "end", "End");
        name(GLFW.GLFW_KEY_INSERT, "insert", "Insert");
        name(GLFW.GLFW_KEY_DELETE, "delete", "Delete");
        name(GLFW.GLFW_KEY_BACKSPACE, "backspace", "Backspace");
        name(GLFW.GLFW_KEY_ENTER, "enter", "Enter");
        name(GLFW.GLFW_KEY_KP_ENTER, "kp_enter", "Keypad Enter");
        name(GLFW.GLFW_KEY_TAB, "tab", "Tab");
        name(GLFW.GLFW_KEY_ESCAPE, "escape", "Esc");
        name(GLFW.GLFW_KEY_SPACE, "space", "Space");
        name(GLFW.GLFW_KEY_MINUS, "minus", "-");
        name(GLFW.GLFW_KEY_EQUAL, "equal", "=");
        name(GLFW.GLFW_KEY_LEFT_BRACKET, "left_bracket", "[");
        name(GLFW.GLFW_KEY_RIGHT_BRACKET, "right_bracket", "]");
        name(GLFW.GLFW_KEY_BACKSLASH, "backslash", "\\");
        name(GLFW.GLFW_KEY_SEMICOLON, "semicolon", ";");
        name(GLFW.GLFW_KEY_APOSTROPHE, "apostrophe", "'");
        name(GLFW.GLFW_KEY_GRAVE_ACCENT, "grave", "`");
        name(GLFW.GLFW_KEY_COMMA, "comma", ",");
        name(GLFW.GLFW_KEY_PERIOD, "period", ".");
        name(GLFW.GLFW_KEY_SLASH, "slash", "/");
        name(GLFW.GLFW_KEY_KP_ADD, "kp_add", "Keypad +");
        name(GLFW.GLFW_KEY_KP_SUBTRACT, "kp_subtract", "Keypad -");
        name(GLFW.GLFW_KEY_KP_MULTIPLY, "kp_multiply", "Keypad *");
        name(GLFW.GLFW_KEY_KP_DIVIDE, "kp_divide", "Keypad /");
        name(GLFW.GLFW_KEY_KP_DECIMAL, "kp_decimal", "Keypad .");
    }

    private KeyNames() {}

    private static void name(int code, String name, String display) {
        NAMES.put(code, name);
        CODES.put(name, code);
        DISPLAY.put(code, display);
    }

    /** The file name of a key: "a", "f1", "page_up", or "key.&lt;code&gt;" for unnamed keys. */
    public static String name(int code) {
        String name = NAMES.get(code);
        return name != null ? name : "key." + code;
    }

    /** The key code for a file name, or -1 if unknown. */
    public static int code(String name) {
        Integer code = CODES.get(name);
        if (code != null) {
            return code;
        }
        if (name.startsWith("key.")) {
            try {
                int parsed = Integer.parseInt(name.substring(4));
                return parsed > 0 && parsed <= GLFW.GLFW_KEY_LAST ? parsed : -1;
            } catch (NumberFormatException malformed) {
                return -1;
            }
        }
        return -1;
    }

    /** How the key is shown to the player: "A", "F1", "PgUp". */
    public static String display(int code) {
        String display = DISPLAY.get(code);
        return display != null ? display : "Key " + code;
    }

    /** True for Shift, Ctrl, Alt and Super, which only ever act as modifiers. */
    public static boolean isModifierKey(int code) {
        return code == GLFW.GLFW_KEY_LEFT_SHIFT || code == GLFW.GLFW_KEY_RIGHT_SHIFT
                || code == GLFW.GLFW_KEY_LEFT_CONTROL || code == GLFW.GLFW_KEY_RIGHT_CONTROL
                || code == GLFW.GLFW_KEY_LEFT_ALT || code == GLFW.GLFW_KEY_RIGHT_ALT
                || code == GLFW.GLFW_KEY_LEFT_SUPER || code == GLFW.GLFW_KEY_RIGHT_SUPER;
    }
}
