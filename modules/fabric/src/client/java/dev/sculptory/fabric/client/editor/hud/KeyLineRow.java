package dev.sculptory.fabric.client.editor.hud;

import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One "keys · what they do" row of the key sheet and the quick start card: the keys in the accent colour in a column
 * at the left, what they do at the right, {@link Theme#keyTextGap} further on. Every row of a sheet or card gets the
 * same key column, sized by its owner for all of them ({@link #keyColumnWidth}). A key's name never breaks: an
 * action's keys ("Ctrl+Y / Ctrl+Shift+Z") that don't fit the column on one line go on more lines, split between
 * keys; a line that goes on ends in the separator's "/" ({@link #CONTINUES}) and the lines after it start
 * {@link #CONTINUATION_INDENT} further in, so each reads as more keys of the same row, not as a row of its own. What
 * they do starts level with the first line and wraps. Keys that are empty show {@code unbound} greyed out instead.
 */
public final class KeyLineRow extends Node {
    /** Between two keys of one action, as the keymap and the key sheet join them. */
    public static final String SEPARATOR = " / ";
    /** The end of a line of keys that goes on on the next line. */
    public static final String CONTINUES = " /";
    /** How much further in than the first line a line of keys that goes on from the one above starts, in UI units. */
    public static final int CONTINUATION_INDENT = 6;
    private static final Pattern SPLIT = Pattern.compile(Pattern.quote(SEPARATOR));

    /**
     * One line of a row's keys as drawn.
     *
     * @param text   the keys on the line, ending in {@link #CONTINUES} when the row's keys go on on the next line
     * @param indent how far in from the key column's left edge it starts: 0 for the first line
     */
    public record KeyLine(String text, int indent) {
        public KeyLine {
            Objects.requireNonNull(text);
        }

        /** Whether the row's keys go on on the next line. */
        public boolean continues() {
            return text.endsWith(CONTINUES);
        }

        /** The keys on the line without the {@link #CONTINUES} mark. */
        public String keys() {
            return continues() ? text.substring(0, text.length() - CONTINUES.length()) : text;
        }
    }

    private final String keys;
    private final String text;
    private final String unbound;
    private final int keyWidth;

    /** @param keyWidth the key column's width (see {@link #keyColumnWidth}) */
    public KeyLineRow(String keys, String text, String unbound, int keyWidth) {
        this.keys = Objects.requireNonNull(keys);
        this.text = Objects.requireNonNull(text);
        this.unbound = Objects.requireNonNull(unbound);
        this.keyWidth = Math.max(0, keyWidth);
    }

    /**
     * The key column for rows with these keys (and {@code unbound} for rows without): wide enough for the widest
     * row's keys on one line, up to {@code max}, and never narrower than any row needs with one key per line (its
     * "/" and indent included), so no name breaks and no line sticks out.
     */
    public static int keyColumnWidth(TextMeasure measure, Collection<String> keys, String unbound, int max) {
        int whole = measure.width(unbound);
        int needed = whole;
        for (String shown : keys) {
            whole = Math.max(whole, measure.width(shown));
            needed = Math.max(needed, narrowest(measure, shown));
        }
        return Math.max(needed, Math.min(whole, max));
    }

    /** The width these keys need at the least: one key per line, each line but the last ending in "/". */
    private static int narrowest(TextMeasure measure, String keys) {
        List<String> names = names(keys);
        int width = 0;
        for (int i = 0; i < names.size(); i++) {
            boolean last = i == names.size() - 1;
            int indent = i == 0 ? 0 : CONTINUATION_INDENT;
            width = Math.max(width, indent + measure.width(last ? names.get(i) : names.get(i) + CONTINUES));
        }
        return width;
    }

    /** An action's keys one by one ("Ctrl+Y", "Ctrl+Shift+Z"). */
    private static List<String> names(String keys) {
        return List.of(SPLIT.split(keys));
    }

    public String keys() {
        return keys;
    }

    public String text() {
        return text;
    }

    /** The key column's width. */
    public int keyWidth() {
        return keyWidth;
    }

    /**
     * The keys as drawn, a line each: as many keys on a line as fit the key column, a line that goes on ending in
     * {@link #CONTINUES}, the lines after the first {@link #CONTINUATION_INDENT} further in.
     */
    public List<KeyLine> keyLines(TextMeasure measure) {
        if (keys.isEmpty()) {
            return List.of(new KeyLine(unbound, 0));
        }
        List<String> names = names(keys);
        List<KeyLine> lines = new ArrayList<>();
        String line = names.get(0);
        for (int i = 1; i < names.size(); i++) {
            int indent = lines.isEmpty() ? 0 : CONTINUATION_INDENT;
            String joined = line + SEPARATOR + names.get(i);
            // A line that more keys follow needs room for its "/" too.
            boolean last = i == names.size() - 1;
            if (indent + measure.width(last ? joined : joined + CONTINUES) <= keyWidth) {
                line = joined;
            } else {
                lines.add(new KeyLine(line + CONTINUES, indent));
                line = names.get(i);
            }
        }
        lines.add(new KeyLine(line, lines.isEmpty() ? 0 : CONTINUATION_INDENT));
        return lines;
    }

    private int textX(UiContext ctx) {
        return keyWidth + ctx.theme().keyTextGap;
    }

    private List<String> textLines(UiContext ctx, int width) {
        return TextLayout.wrap(ctx.text(), text, Math.max(1, width - textX(ctx)));
    }

    /** As wide as the key column and the whole description on one line, or {@code maxWidth} with it wrapped. */
    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        int width = Math.min(textX(ctx) + ctx.text().width(text), maxWidth);
        int lines = Math.max(keyLines(ctx.text()).size(), textLines(ctx, width).size());
        return new Size(width, lines * ctx.text().lineHeight() + (lines - 1) * ctx.theme().lineSpacing + 1);
    }

    @Override
    public void render(UiGraphics g, UiContext ctx) {
        Theme theme = ctx.theme();
        TextMeasure measure = ctx.text();
        int step = measure.lineHeight() + theme.lineSpacing;
        int color = keys.isEmpty() ? theme.textDisabled : theme.accentHover;
        int y = bounds.y();
        for (KeyLine line : keyLines(measure)) {
            g.text(line.text(), bounds.x() + line.indent(), y, color, theme.textShadow);
            y += step;
        }
        y = bounds.y();
        int x = bounds.x() + textX(ctx);
        for (String line : textLines(ctx, bounds.width())) {
            g.text(line, x, y, theme.text, theme.textShadow);
            y += step;
        }
    }
}
