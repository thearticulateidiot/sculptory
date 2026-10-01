package dev.sculptory.fabric.client.editor.hud;

import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import dev.sculptory.fabric.client.session.Notice;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Short messages at the top right: at most {@link #MAX} at once, newest at the bottom, each shown
 * for a few seconds (longer for warnings and errors). A repeat of a visible message restarts it
 * instead of stacking. Every message also goes into the {@link NotificationLog} (the Notifications
 * window). A click on a toast takes it off the screen ({@link #dismissAt}), using where it was last drawn.
 */
public final class ToastStack {
    public static final int MAX = 4;
    public static final int WIDTH = 210;
    private static final int PADDING = 5;

    /** One message on screen. */
    public record Toast(Notice.Level level, String text, long shownAtMs) {
        public Toast {
            Objects.requireNonNull(level);
            Objects.requireNonNull(text);
        }

        public long durationMs() {
            return switch (level) {
                case INFO, SUCCESS -> 3_500;
                case WARNING -> 5_000;
                case ERROR -> 7_000;
            };
        }
    }

    /** A toast placed for drawing, with its lines. */
    private record Drawn(Toast toast, Rect rect, List<String> lines) {}

    private final LongSupplier clock;
    private final Supplier<LocalTime> timeOfDay;
    private final List<Toast> toasts = new ArrayList<>();
    private final NotificationLog log = new NotificationLog();
    private List<Drawn> drawn = List.of();

    /** @param clock milliseconds, e.g. {@code Util.getMeasuringTimeMs}; the log reads the wall clock */
    public ToastStack(LongSupplier clock) {
        this(clock, LocalTime::now);
    }

    /** @param timeOfDay when a message was shown, for the log */
    public ToastStack(LongSupplier clock, Supplier<LocalTime> timeOfDay) {
        this.clock = Objects.requireNonNull(clock);
        this.timeOfDay = Objects.requireNonNull(timeOfDay);
    }

    public void show(Notice.Level level, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        toasts.removeIf(toast -> toast.text().equals(text));
        toasts.add(new Toast(level, text, clock.getAsLong()));
        while (toasts.size() > MAX) {
            toasts.remove(0);
        }
        log.add(level, text, timeOfDay.get());
    }

    /** Every message shown, newest first (the last {@value NotificationLog#CAPACITY}). */
    public NotificationLog log() {
        return log;
    }

    /** Takes a message off the screen early (a newer one replaces it). */
    public void dismiss(String text) {
        toasts.removeIf(toast -> toast.text().equals(text));
    }

    /** The toasts still on screen, oldest first. */
    public List<Toast> visible() {
        long now = clock.getAsLong();
        toasts.removeIf(toast -> now - toast.shownAtMs() >= toast.durationMs());
        return List.copyOf(toasts);
    }

    public void clear() {
        toasts.clear();
        drawn = List.of();
    }

    /** The toast placed at the point (in the units it was drawn in) at the last {@link #place}, if still shown. */
    public Optional<Toast> toastAt(double x, double y) {
        for (Drawn toast : drawn) {
            if (toast.rect().contains(x, y) && toasts.contains(toast.toast())) {
                return Optional.of(toast.toast());
            }
        }
        return Optional.empty();
    }

    /** Where the toasts went at the last {@link #place} (and so were drawn), top to bottom. */
    public List<Rect> drawnRects() {
        return drawn.stream().map(Drawn::rect).toList();
    }

    /** A click at the point: the toast there goes. Returns false when there was none. */
    public boolean dismissAt(double x, double y) {
        Optional<Toast> toast = toastAt(x, y);
        toast.ifPresent(toasts::remove);
        return toast.isPresent();
    }

    /** Draws the stack with its top-right corner at ({@code right}, {@code top}). */
    public void render(UiGraphics g, TextMeasure text, Theme theme, int right, int top) {
        place(text, theme, right, top, Integer.MAX_VALUE, List.of());
        draw(g, text, theme);
    }

    /** {@link #place} then {@link #draw}. */
    public void render(UiGraphics g, TextMeasure text, Theme theme, int right, int top, int bottom, List<Rect> avoid) {
        place(text, theme, right, top, bottom, avoid);
        draw(g, text, theme);
    }

    /**
     * Works out where this frame's toasts go ({@link #drawnRects}), for {@link #draw}: from ({@code right},
     * {@code top}) down, each under the one before and clear of {@code avoid} (the title bars of the windows under the
     * column): a toast that would cover one goes below it instead, so a window can still be moved, collapsed or closed
     * while toasts show. When not all of them fit above {@code bottom} the oldest are left out (the Notifications
     * window keeps them); when not even the newest fits it goes at {@code top}.
     */
    public void place(TextMeasure text, Theme theme, int right, int top, int bottom, List<Rect> avoid) {
        List<Toast> shown = visible();
        List<List<String>> lines = new ArrayList<>();
        List<Rect> sizes = new ArrayList<>();
        for (Toast toast : shown) {
            List<String> wrapped = TextLayout.wrap(text, toast.text(), WIDTH - 2 * PADDING - 3);
            int widest = 0;
            for (String line : wrapped) {
                widest = Math.max(widest, text.width(line));
            }
            int width = widest + 2 * PADDING + 3;
            int height = wrapped.size() * text.lineHeight() + (wrapped.size() - 1) * theme.lineSpacing + 2 * PADDING;
            lines.add(wrapped);
            sizes.add(new Rect(right - width, 0, width, height));
        }
        // As many as fit, the newest kept.
        List<Rect> places = null;
        int first = 0;
        while (first < shown.size()) {
            places = stack(sizes.subList(first, sizes.size()), top, bottom, avoid);
            if (places != null) {
                break;
            }
            first++;
        }
        if (places == null) {
            // Nothing fits: the newest at the top, where it always was.
            first = shown.size() - 1;
            places = first < 0 ? List.of() : List.of(sizes.get(first).withPosition(sizes.get(first).x(), top));
        }
        List<Drawn> placed = new ArrayList<>();
        for (int i = 0; i < places.size(); i++) {
            placed.add(new Drawn(shown.get(first + i), places.get(i), lines.get(first + i)));
        }
        drawn = List.copyOf(placed);
    }

    /** Draws the toasts where {@link #place} put them. */
    public void draw(UiGraphics g, TextMeasure text, Theme theme) {
        for (Drawn toast : drawn) {
            Rect at = toast.rect();
            g.dropShadow(at, theme.windowShadow);
            g.fill(at, theme.popupBackground);
            g.fill(at.x(), at.y(), 3, at.height(), levelColor(toast.toast().level(), theme));
            g.outline(at, theme.popupBorder);
            int lineY = at.y() + PADDING;
            for (String line : toast.lines()) {
                g.text(line, at.x() + PADDING + 3, lineY, theme.text, theme.textShadow);
                lineY += text.lineHeight() + theme.lineSpacing;
            }
        }
    }

    /**
     * Where toasts of these sizes go, one under another from {@code top} and clear of {@code avoid}; null when they
     * don't all fit above {@code bottom}.
     */
    private static List<Rect> stack(List<Rect> sizes, int top, int bottom, List<Rect> avoid) {
        List<Rect> places = new ArrayList<>();
        int y = top;
        for (Rect size : sizes) {
            y = clearTop(y, size.x(), size.width(), size.height(), avoid);
            if (y + size.height() > bottom) {
                return null;
            }
            places.add(size.withPosition(size.x(), y));
            y += size.height() + 3;
        }
        return places;
    }

    /**
     * The first top from {@code y} down at which a {@code width x height} rectangle at {@code x} covers none of
     * {@code avoid}.
     */
    public static int clearTop(int y, int x, int width, int height, List<Rect> avoid) {
        boolean moved = true;
        while (moved) {
            moved = false;
            Rect at = new Rect(x, y, width, height);
            for (Rect other : avoid) {
                if (at.intersects(other) && other.bottom() > y) {
                    y = other.bottom();
                    moved = true;
                }
            }
        }
        return y;
    }

    /** A message level's colour: a toast's bar at its left, a Notifications row's. */
    public static int levelColor(Notice.Level level, Theme theme) {
        return switch (level) {
            case INFO -> theme.accent;
            case SUCCESS -> theme.noticeSuccess;
            case WARNING -> theme.noticeWarning;
            case ERROR -> theme.danger;
        };
    }
}
