package dev.sculptory.core.tinker;

import java.util.List;

/**
 * What {@link SignText} and its {@link SignText.Side} both need, held apart from them so neither class's
 * initialization waits on the other's (a side made while {@code SignText} is still being set up, or the other way
 * round, on two threads at once, would otherwise find a null or deadlock).
 */
final class SignLines {
    /** The sixteen dye colours in vanilla's order ({@code DyeColor}), as sign NBT names them. */
    static final List<String> COLORS = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime",
            "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");

    private SignLines() {}

    /** {@link SignText#clean}. */
    static String clean(String line) {
        int max = SignText.MAX_LINE_CHARS;
        StringBuilder out = new StringBuilder(Math.min(line.length(), max));
        for (int i = 0; i < line.length() && out.length() < max; i++) {
            char c = line.charAt(i);
            if (c == '§') {
                i++; // the code's letter goes with it
                continue;
            }
            if (Character.isISOControl(c) || c == '\u007f') continue;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < line.length() && Character.isLowSurrogate(line.charAt(i + 1))) {
                    if (out.length() + 2 > max) break;
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
