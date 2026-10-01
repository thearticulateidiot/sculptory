package dev.sculptory.fabric.client.editor.hud;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.tool.KeyHint;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * The hint line above the palette: the active tool's name in the accent colour, then what can be done with it now
 * ({@link #format}: "Raise  ·  LMB drag: raise  ·  Ctrl+Scroll: radius  ·  F1: help"). When the screen is too narrow
 * for all of it, whole hints give way from the end, the last one (the help key) kept while it fits: "Raise  ·  LMB
 * drag: raise  ·  F1: help" rather than "... Alt+Scrol...". The whole line is then the tooltip. Only when not even
 * the first hint fits does the line end in "...".
 */
public final class HintLine extends Node {
    public static final String SEPARATOR = "  ·  ";
    private static final Pattern SPLIT = Pattern.compile(Pattern.quote(SEPARATOR));

    private String toolName = "";
    private String hints = "";
    /** The hints drawn after the name at the last layout. */
    private String shownHints = "";
    private boolean truncated;

    /** "keys: what" entries joined with {@link #SEPARATOR}. */
    public static String format(List<KeyHint> hints, Translator translator) {
        StringJoiner line = new StringJoiner(SEPARATOR);
        for (KeyHint hint : hints) {
            String text = translator.translate(hint.descriptionKey(), hint.args());
            line.add(hint.keys().isEmpty() ? text : hint.keys() + ": " + text);
        }
        return line.toString();
    }

    public void set(String toolName, String hints) {
        this.toolName = Objects.requireNonNull(toolName);
        this.hints = Objects.requireNonNull(hints);
        this.shownHints = hints;
    }

    /** The name shown first ("" with no tool active). */
    public String toolName() {
        return toolName;
    }

    /** The hints after the name. */
    public String hints() {
        return hints;
    }

    /** The hints drawn after the name at the last layout: all of them, or the whole ones that fit. */
    public String shownHints() {
        return shownHints;
    }

    /** The whole line as text. */
    public String text() {
        return line(hints);
    }

    public boolean isEmpty() {
        return toolName.isEmpty() && hints.isEmpty();
    }

    private String line(String someHints) {
        if (toolName.isEmpty()) {
            return someHints;
        }
        return someHints.isEmpty() ? toolName : toolName + SEPARATOR + someHints;
    }

    @Override
    public String tooltip() {
        return truncated ? text() : null;
    }

    /** As wide as the whole line, or within {@code maxWidth} as wide as the whole hints that fit it. */
    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        int width = ctx.text().width(text());
        if (maxWidth != Integer.MAX_VALUE && width > maxWidth) {
            width = Math.min(maxWidth, ctx.text().width(line(fitting(ctx.text(), maxWidth))));
        }
        return new Size(width, ctx.text().lineHeight());
    }

    @Override
    public void layout(UiContext ctx, Rect bounds) {
        super.layout(ctx, bounds);
        shownHints = fitting(ctx.text(), bounds.width());
        truncated = !shownHints.equals(hints) || ctx.text().width(line(shownHints)) > bounds.width();
    }

    /**
     * The hints that fit {@code width} after the name, whole: all of them; else the first ones and the last (the help
     * key), dropping from the one before the last backwards; else the first ones alone; else all of them (cut with
     * "..." when drawn).
     */
    private String fitting(TextMeasure text, int width) {
        if (hints.isEmpty() || text.width(text()) <= width) {
            return hints;
        }
        List<String> entries = List.of(SPLIT.split(hints));
        int count = entries.size();
        for (int keep = count - 2; keep >= 0; keep--) {
            List<String> shown = new ArrayList<>(entries.subList(0, keep));
            shown.add(entries.get(count - 1));
            String joined = String.join(SEPARATOR, shown);
            if (text.width(line(joined)) <= width) {
                return joined;
            }
        }
        for (int keep = count - 1; keep >= 1; keep--) {
            String joined = String.join(SEPARATOR, entries.subList(0, keep));
            if (text.width(line(joined)) <= width) {
                return joined;
            }
        }
        return hints;
    }

    @Override
    public void render(UiGraphics g, UiContext ctx) {
        Theme theme = ctx.theme();
        TextMeasure text = ctx.text();
        int x = bounds.x();
        int y = bounds.y() + Math.max(0, (bounds.height() - text.lineHeight() + 1) / 2);
        String name = TextLayout.ellipsize(text, toolName, bounds.width());
        if (!name.isEmpty()) {
            g.text(name, x, y, theme.accentHover, theme.textShadow);
            x += text.width(name);
        }
        String rest = toolName.isEmpty() ? shownHints : shownHints.isEmpty() ? "" : SEPARATOR + shownHints;
        int room = bounds.right() - x;
        if (!rest.isEmpty() && room > 0) {
            g.text(TextLayout.ellipsize(text, rest, room), x, y, theme.text, theme.textShadow);
        }
    }
}
