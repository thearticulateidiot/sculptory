package dev.sculptory.fabric.client.editor.input;

import dev.sculptory.fabric.client.editor.tool.Modifiers;
import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * One input combination: a key, a mouse button or the scroll wheel, with the Ctrl/Shift/Alt
 * modifiers held. Text form (for {@code editor-keys.json}): {@code ctrl+shift+z}, {@code delete},
 * {@code ctrl+scroll}, {@code mouse.middle}.
 *
 * @param code GLFW key code, GLFW mouse button, or 0 for the scroll wheel
 * @param modifiers {@link Modifiers} bits; only Ctrl, Shift and Alt are kept
 */
public record KeyChord(Input input, int code, int modifiers) {
    /** What kind of input the chord is. */
    public enum Input { KEY, MOUSE, SCROLL }

    private static final int MASK = Modifiers.CONTROL | Modifiers.SHIFT | Modifiers.ALT;
    private static final String[] MOUSE_NAMES = {"left", "right", "middle"};
    private static final String[] MOUSE_DISPLAY = {"Left-click", "Right-click", "Middle-click"};

    public KeyChord {
        Objects.requireNonNull(input);
        modifiers &= MASK;
        if (input == Input.SCROLL) {
            code = 0;
        }
        if (input == Input.KEY && code <= 0) {
            throw new IllegalArgumentException("Invalid key code: " + code);
        }
        if (input == Input.MOUSE && (code < 0 || code > 7)) {
            throw new IllegalArgumentException("Invalid mouse button: " + code);
        }
    }

    public static KeyChord key(int code, int modifiers) {
        return new KeyChord(Input.KEY, code, modifiers);
    }

    public static KeyChord key(int code) {
        return key(code, 0);
    }

    public static KeyChord mouse(int button, int modifiers) {
        return new KeyChord(Input.MOUSE, button, modifiers);
    }

    public static KeyChord scroll(int modifiers) {
        return new KeyChord(Input.SCROLL, 0, modifiers);
    }

    public boolean shift() {
        return Modifiers.shift(modifiers);
    }

    /** True if Ctrl or Alt is part of the chord. */
    public boolean hasCommandModifier() {
        return (modifiers & (Modifiers.CONTROL | Modifiers.ALT)) != 0;
    }

    public KeyChord withoutShift() {
        return new KeyChord(input, code, modifiers & ~Modifiers.SHIFT);
    }

    /** The file form, e.g. {@code ctrl+shift+z}. */
    public String format() {
        StringJoiner parts = new StringJoiner("+");
        if (Modifiers.control(modifiers)) {
            parts.add("ctrl");
        }
        if (Modifiers.shift(modifiers)) {
            parts.add("shift");
        }
        if (Modifiers.alt(modifiers)) {
            parts.add("alt");
        }
        parts.add(switch (input) {
            case KEY -> KeyNames.name(code);
            case MOUSE -> "mouse." + (code < MOUSE_NAMES.length ? MOUSE_NAMES[code] : Integer.toString(code + 1));
            case SCROLL -> "scroll";
        });
        return parts.toString();
    }

    /** How the chord is shown to the player, e.g. {@code Ctrl+Shift+Z}. */
    public String display() {
        StringJoiner parts = new StringJoiner("+");
        if (Modifiers.control(modifiers)) {
            parts.add("Ctrl");
        }
        if (Modifiers.shift(modifiers)) {
            parts.add("Shift");
        }
        if (Modifiers.alt(modifiers)) {
            parts.add("Alt");
        }
        parts.add(switch (input) {
            case KEY -> KeyNames.display(code);
            case MOUSE -> code < MOUSE_DISPLAY.length ? MOUSE_DISPLAY[code] : "Mouse " + (code + 1);
            case SCROLL -> "Scroll";
        });
        return parts.toString();
    }

    /**
     * Parses {@link #format()} text. Modifiers may come in any order; case is ignored.
     *
     * @throws IllegalArgumentException if the text is not a chord
     */
    public static KeyChord parse(String text) {
        Objects.requireNonNull(text);
        String[] parts = text.trim().toLowerCase(Locale.ROOT).split("\\+", -1);
        int modifiers = 0;
        for (int i = 0; i < parts.length - 1; i++) {
            int bit = switch (parts[i]) {
                case "ctrl", "control" -> Modifiers.CONTROL;
                case "shift" -> Modifiers.SHIFT;
                case "alt" -> Modifiers.ALT;
                default -> throw new IllegalArgumentException("Unknown modifier '" + parts[i] + "' in " + text);
            };
            if ((modifiers & bit) != 0) {
                throw new IllegalArgumentException("Repeated modifier in " + text);
            }
            modifiers |= bit;
        }
        String last = parts[parts.length - 1];
        if (last.equals("scroll")) {
            return scroll(modifiers);
        }
        if (last.startsWith("mouse.")) {
            String button = last.substring(6);
            for (int i = 0; i < MOUSE_NAMES.length; i++) {
                if (MOUSE_NAMES[i].equals(button)) {
                    return mouse(i, modifiers);
                }
            }
            try {
                return mouse(Integer.parseInt(button) - 1, modifiers);
            } catch (NumberFormatException malformed) {
                throw new IllegalArgumentException("Unknown mouse button in " + text, malformed);
            }
        }
        int code = KeyNames.code(last);
        if (code < 0 || KeyNames.isModifierKey(code)) {
            throw new IllegalArgumentException("Unknown key '" + last + "' in " + text);
        }
        return key(code, modifiers);
    }

    @Override
    public String toString() {
        return format();
    }
}
