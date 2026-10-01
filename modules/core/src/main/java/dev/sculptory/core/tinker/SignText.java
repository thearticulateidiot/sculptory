package dev.sculptory.core.tinker;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The text of a sign or hanging sign as Tinker edits it: per side four lines of plain
 * text, a dye colour and whether it glows. Plain text only: the server builds each line as a literal text component,
 * so nothing a player types can carry click events, formatting or nesting. Lines are {@linkplain #clean cleaned} when a
 * side is made (formatting codes and control characters removed, at most {@value #MAX_LINE_CHARS} characters).
 */
public record SignText(Side front, Side back) {
    /** Lines per side. */
    public static final int LINES = 4;
    /** Longest line kept, in characters (vanilla's sign packet takes at most this many). */
    public static final int MAX_LINE_CHARS = 384;
    /** The sixteen dye colours in vanilla's order ({@code DyeColor}), as sign NBT names them. */
    public static final List<String> COLORS = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime",
            "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");
    /** A new sign's text colour. */
    public static final String DEFAULT_COLOR = "black";

    public SignText {
        Objects.requireNonNull(front);
        Objects.requireNonNull(back);
    }

    /** One side: its four lines, its colour (one of {@link #COLORS}) and whether the text glows. */
    public record Side(List<String> lines, String color, boolean glowing) {
        public static final Side EMPTY = new Side(List.of("", "", "", ""), DEFAULT_COLOR, false);

        public Side {
            Objects.requireNonNull(lines);
            if (lines.size() != LINES) throw new IllegalArgumentException("A sign side has " + LINES + " lines");
            List<String> cleaned = new ArrayList<>(LINES);
            for (String line : lines) cleaned.add(clean(Objects.requireNonNull(line)));
            lines = List.copyOf(cleaned);
            if (!COLORS.contains(color)) throw new IllegalArgumentException("Unknown sign colour: " + color);
        }

        /** This side with line {@code index} set to {@code text}. */
        public Side withLine(int index, String text) {
            List<String> changed = new ArrayList<>(lines);
            changed.set(index, text);
            return new Side(changed, color, glowing);
        }

        public Side withColor(String color) {
            return new Side(lines, color, glowing);
        }

        public Side withGlowing(boolean glowing) {
            return new Side(lines, color, glowing);
        }
    }

    public static final SignText EMPTY = new SignText(Side.EMPTY, Side.EMPTY);

    /** The side {@code front} names. */
    public Side side(boolean front) {
        return front ? this.front : back;
    }

    /** This text with the side {@code front} names replaced. */
    public SignText withSide(boolean front, Side side) {
        return front ? new SignText(side, back) : new SignText(this.front, side);
    }

    /**
     * {@code line} as a sign keeps it: without vanilla's formatting codes (the section sign and the character after it)
     * and control characters (line breaks included), cut to {@value #MAX_LINE_CHARS} characters, as vanilla cleans what
     * a player types on a sign.
     */
    public static String clean(String line) {
        StringBuilder out = new StringBuilder(Math.min(line.length(), MAX_LINE_CHARS));
        for (int i = 0; i < line.length() && out.length() < MAX_LINE_CHARS; i++) {
            char c = line.charAt(i);
            if (c == '§') {
                i++; // the code's letter goes with it
                continue;
            }
            if (Character.isISOControl(c) || c == '\u007f') continue;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < line.length() && Character.isLowSurrogate(line.charAt(i + 1))) {
                    if (out.length() + 2 > MAX_LINE_CHARS) break;
                    out.append(c).append(line.charAt(++i));
                }
                continue;
            }
            if (Character.isLowSurrogate(c)) continue;
            out.append(c);
        }
        return out.toString();
    }
}
