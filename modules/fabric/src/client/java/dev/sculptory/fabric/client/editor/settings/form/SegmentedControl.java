package dev.sculptory.fabric.client.editor.settings.form;

import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.lwjgl.glfw.GLFW;

/**
 * A row of mutually exclusive options, all visible at once (better than a dropdown for two to four
 * choices). Click an option to pick it; Left/Right step while focused. The options share one line in equal segments
 * when the widest label fits that way; otherwise they go onto as few lines as fit, shared as evenly as fits (four
 * options two and two, five three and two; never a full line and one option stretched across the next), so no
 * label is cut short (only one wider than the whole control is). A line's segments are equal where its widest label
 * fits that way, else in proportion to their labels.
 */
public class SegmentedControl<T> extends Node {
    private final List<T> options;
    private final Function<T, String> labeler;
    private final Consumer<T> onChange;
    private T selected;
    private Function<T, String> optionTooltips = option -> null;
    /** Which options can be chosen: the others are greyed out (their tooltip says why) and skipped by the arrows. */
    private Predicate<T> optionEnabled = option -> true;
    /** The segment under the pointer at its last move, or -1. */
    private int hoveredIndex = -1;
    /** Each option's segment from the last layout; empty before it. */
    private List<Rect> segments = List.of();

    public SegmentedControl(List<T> options, T selected, Function<T, String> labeler, Consumer<T> onChange) {
        if (options.isEmpty()) {
            throw new IllegalArgumentException("SegmentedControl needs at least one option");
        }
        this.options = List.copyOf(options);
        this.labeler = Objects.requireNonNull(labeler);
        this.onChange = onChange;
        this.selected = this.options.contains(selected) ? selected : this.options.get(0);
    }

    public List<T> options() {
        return options;
    }

    public T selected() {
        return selected;
    }

    /**
     * Each option's own tooltip, shown while the pointer is over that segment (null for none: the control's tooltip
     * shows instead).
     */
    public SegmentedControl<T> setOptionTooltips(Function<T, String> optionTooltips) {
        this.optionTooltips = Objects.requireNonNull(optionTooltips);
        return this;
    }

    /**
     * Which options can be chosen: the others are drawn greyed out, a click on one does nothing and Left/Right step past
     * it (give such an option a tooltip saying why).
     */
    public SegmentedControl<T> setOptionEnabled(Predicate<T> optionEnabled) {
        this.optionEnabled = Objects.requireNonNull(optionEnabled);
        return this;
    }

    /** Whether {@code option} can be chosen ({@link #setOptionEnabled}). */
    public boolean isOptionEnabled(T option) {
        return optionEnabled.test(option);
    }

    /** The tooltip of the option under the pointer, else the control's. */
    @Override
    public String tooltip() {
        if (hoveredIndex >= 0 && hoveredIndex < options.size()) {
            String own = optionTooltips.apply(options.get(hoveredIndex));
            if (own != null) {
                return own;
            }
        }
        return super.tooltip();
    }

    /** The option whose segment contains {@code x}, from the last layout, or -1. */
    public int indexAt(double x, double y) {
        for (int i = 0; i < options.size(); i++) {
            if (segment(i).contains(x, y)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void mouseMove(UiContext ctx, double x, double y) {
        hoveredIndex = indexAt(x, y);
    }

    /** Sets the value without notifying the listener. */
    public SegmentedControl<T> setSelected(T value) {
        if (options.contains(value)) {
            selected = value;
        }
        return this;
    }

    /** Picks an option and notifies the listener if it changed. */
    public void choose(T value) {
        if (!isEffectivelyEnabled() || !options.contains(value) || value.equals(selected) || !optionEnabled.test(value)) {
            return;
        }
        selected = value;
        if (onChange != null) {
            onChange.accept(value);
        }
    }

    private Rect segment(int index) {
        if (index < segments.size()) {
            return segments.get(index);
        }
        int count = options.size();
        int left = bounds.x() + bounds.width() * index / count;
        int right = bounds.x() + bounds.width() * (index + 1) / count;
        return new Rect(left, bounds.y(), right - left, bounds.height());
    }

    /** Each option's segment in {@code area} (its top left and width; the height follows the lines). */
    private List<Rect> arrange(UiContext ctx, Rect area) {
        int height = ctx.theme().controlHeight;
        int count = options.size();
        int[] natural = new int[count];
        for (int i = 0; i < count; i++) {
            natural[i] = ctx.text().width(labeler.apply(options.get(i))) + 2 * ctx.theme().controlPaddingX;
        }
        List<Rect> rects = new ArrayList<>(count);
        int y = area.y();
        int first = 0;
        for (int size : lineSizes(natural, area.width())) {
            int end = first + size;
            int used = 0;
            int widestHere = 0;
            for (int i = first; i < end; i++) {
                used += natural[i];
                widestHere = Math.max(widestHere, natural[i]);
            }
            // Equal segments where the line's widest label fits that way (a two-by-two grid lines up), else in
            // proportion to the labels.
            boolean equal = widestHere * size <= area.width();
            int x = area.x();
            int before = 0;
            for (int i = first; i < end; i++) {
                before += natural[i];
                int right = i == end - 1 ? area.right()
                        : equal ? area.x() + area.width() * (i - first + 1) / size
                        : area.x() + (int) ((long) area.width() * before / used);
                rects.add(new Rect(x, y, right - x, height));
                x = right;
            }
            y += height;
            first = end;
        }
        return rects;
    }

    /**
     * How many options go on each line: all on one when the widest fits that way; otherwise on as few lines as fit
     * (an option wider than the control alone), shared as evenly as fits, earlier lines taking the extra one: four
     * options two and two, five three and two, rather than a full line and one option stretched across the next.
     */
    static int[] lineSizes(int[] natural, int width) {
        int count = natural.length;
        int widest = 0;
        for (int size : natural) {
            widest = Math.max(widest, size);
        }
        if (widest * count <= width) {
            return new int[] {count};
        }
        int lines = 0;
        for (int first = 0; first < count; lines++) {
            int end = first + 1;
            int used = natural[first];
            while (end < count && used + natural[end] <= width) {
                used += natural[end];
                end++;
            }
            first = end;
        }
        int[] best = new int[lines];
        int[] sizes = new int[lines];
        bestSplit(natural, width, 0, 0, sizes, best, new int[] {Integer.MAX_VALUE});
        return best;
    }

    /**
     * Fills {@code sizes} from line {@code line} on (options from {@code first}) with every split that fits and keeps
     * the most even one in {@code best}: the smallest difference between the fullest and the emptiest line, then
     * the one that puts more on earlier lines.
     */
    private static void bestSplit(int[] natural, int width, int first, int line, int[] sizes, int[] best,
            int[] bestSpread) {
        int lines = sizes.length;
        int left = natural.length - first;
        if (line == lines - 1) {
            if (left < 1 || !fits(natural, first, natural.length, width)) {
                return;
            }
            sizes[line] = left;
            int most = 0;
            int least = Integer.MAX_VALUE;
            for (int size : sizes) {
                most = Math.max(most, size);
                least = Math.min(least, size);
            }
            int spread = most - least;
            if (spread < bestSpread[0] || spread == bestSpread[0] && Arrays.compare(sizes, best) > 0) {
                bestSpread[0] = spread;
                System.arraycopy(sizes, 0, best, 0, lines);
            }
            return;
        }
        for (int size = left - (lines - 1 - line); size >= 1; size--) {
            if (fits(natural, first, first + size, width)) {
                sizes[line] = size;
                bestSplit(natural, width, first + size, line + 1, sizes, best, bestSpread);
            }
        }
    }

    /** Whether options {@code from} to {@code to} (exclusive) share one line: one option always does. */
    private static boolean fits(int[] natural, int from, int to, int width) {
        if (to - from <= 1) {
            return true;
        }
        int used = 0;
        for (int i = from; i < to; i++) {
            used += natural[i];
        }
        return used <= width;
    }

    /** How many options each line holds, from the last layout (all on one line before it). */
    public List<Integer> optionsPerLine() {
        if (segments.isEmpty()) {
            return List.of(options.size());
        }
        List<Integer> counts = new ArrayList<>();
        int lineY = Integer.MIN_VALUE;
        for (Rect segment : segments) {
            if (segment.y() != lineY) {
                counts.add(0);
                lineY = segment.y();
            }
            counts.set(counts.size() - 1, counts.get(counts.size() - 1) + 1);
        }
        return List.copyOf(counts);
    }

    /** How many lines the options take in a control {@code width} wide. */
    public int lineCount(UiContext ctx, int width) {
        return (int) arrange(ctx, new Rect(0, 0, width, 0)).stream().mapToInt(Rect::y).distinct().count();
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        Theme theme = ctx.theme();
        int widest = 0;
        for (T option : options) {
            widest = Math.max(widest, ctx.text().width(labeler.apply(option)));
        }
        int width = (widest + 2 * theme.controlPaddingX) * options.size();
        if (width <= maxWidth) {
            return new Size(width, theme.controlHeight);
        }
        return new Size(maxWidth, lineCount(ctx, maxWidth) * theme.controlHeight);
    }

    @Override
    public void layout(UiContext ctx, Rect bounds) {
        super.layout(ctx, bounds);
        segments = arrange(ctx, bounds);
    }

    @Override
    public void render(UiGraphics g, UiContext ctx) {
        Theme theme = ctx.theme();
        boolean enabled = isEffectivelyEnabled();
        for (int i = 0; i < options.size(); i++) {
            T option = options.get(i);
            Rect rect = segment(i);
            boolean chosen = option.equals(selected);
            boolean available = optionEnabled.test(option);
            boolean hovered = enabled && available && ctx.isHovered(this) && rect.contains(ctx.mouseX(), ctx.mouseY());
            int background = !enabled || !available && !chosen ? theme.controlDisabled
                    : chosen ? theme.accentDim
                    : hovered ? theme.controlHover
                    : theme.control;
            g.fill(rect, background);
            if (rect.x() > bounds.x()) {
                g.fill(rect.x(), rect.y() + 2, 1, rect.height() - 4, theme.controlBorder);
            }
            if (rect.y() > bounds.y()) {
                g.fill(rect.x(), rect.y(), rect.width(), 1, theme.controlBorder);
            }
            String text = TextLayout.ellipsize(ctx.text(), labeler.apply(option), rect.width() - 4);
            int x = rect.x() + (rect.width() - ctx.text().width(text)) / 2;
            int y = rect.y() + (rect.height() - ctx.text().lineHeight() + 1) / 2;
            int color = !enabled || !available ? theme.textDisabled : chosen ? theme.textOnAccent : theme.text;
            g.text(text, x, y, color, theme.textShadow);
        }
        g.outline(bounds, ctx.isFocused(this) ? theme.focusRing : chosenOutline(theme, enabled));
    }

    private static int chosenOutline(Theme theme, boolean enabled) {
        return enabled ? theme.controlBorder : theme.controlDisabled;
    }

    @Override
    public boolean mouseDown(UiContext ctx, double x, double y, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        for (int i = 0; i < options.size(); i++) {
            if (segment(i).contains(x, y)) {
                choose(options.get(i));
                break;
            }
        }
        return true;
    }

    @Override
    public boolean keyPressed(UiContext ctx, int keyCode, int scanCode, int modifiers) {
        int step = switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT -> -1;
            case GLFW.GLFW_KEY_RIGHT -> 1;
            default -> 0;
        };
        if (step == 0) {
            return false;
        }
        // The next option that can be chosen that way, if any.
        for (int i = options.indexOf(selected) + step; i >= 0 && i < options.size(); i += step) {
            if (optionEnabled.test(options.get(i))) {
                choose(options.get(i));
                break;
            }
        }
        return true;
    }

    @Override
    public boolean isFocusable() {
        return true;
    }
}
