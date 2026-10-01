package dev.sculptory.fabric.client.editor.ui;

import java.util.ArrayList;
import java.util.List;

/** Pure text fitting helpers: ellipsis truncation and word wrapping. */
public final class TextLayout {
    public static final String ELLIPSIS = "...";

    private TextLayout() {
    }

    /** Returns {@code text} if it fits, otherwise the longest prefix that fits followed by "...". */
    public static String ellipsize(TextMeasure measure, String text, int maxWidth) {
        if (text.isEmpty() || measure.width(text) <= maxWidth) {
            return text;
        }
        int room = maxWidth - measure.width(ELLIPSIS);
        if (room <= 0) {
            return measure.trimToWidth(ELLIPSIS, Math.max(0, maxWidth));
        }
        return measure.trimToWidth(text, room).stripTrailing() + ELLIPSIS;
    }

    /**
     * Greedy word wrap. Explicit newlines start new lines; words wider than a line are broken.
     * Always returns at least one line.
     */
    public static List<String> wrap(TextMeasure measure, String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            int linesBefore = lines.size();
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (measure.width(candidate) <= maxWidth) {
                    line.setLength(0);
                    line.append(candidate);
                    continue;
                }
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                String rest = word;
                while (!rest.isEmpty() && measure.width(rest) > maxWidth) {
                    String part = measure.trimToWidth(rest, maxWidth);
                    if (part.isEmpty()) {
                        part = rest.substring(0, rest.offsetByCodePoints(0, 1));
                    }
                    lines.add(part);
                    rest = rest.substring(part.length());
                }
                line.append(rest);
            }
            if (!line.isEmpty() || lines.size() == linesBefore) {
                lines.add(line.toString());
            }
        }
        return lines;
    }
}
