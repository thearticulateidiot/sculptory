package dev.sculptory.fabric.client.editor.hud;

import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.window.WindowManager;
import dev.sculptory.fabric.client.editor.ui.window.WindowSpec;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Where the editor's windows open by default, inside the work area (between the top bar and the hint line and
 * palette), {@link #GAP} in from its edges and from each other:
 * <ul>
 *   <li>Selection at the top left and Tool Settings at the top right, the two windows open from the start. They never
 *       overlap: when both preferred widths don't fit, both get narrower in proportion (not below their minimums).
 *       Tool Settings, which holds long forms, is as tall as the work area up to {@link #TOOL_SETTINGS_MAX_HEIGHT}
 *       rather than its preferred height.</li>
 *   <li>The other windows go in two columns in the space between those two: Keys, Tutorial, Wiki and History (top to
 *       bottom) on its left, Library and Clipboard on its right. Only the windows shown at their default place count
 *       (open, not moved by the user): a column shares its height among them, and the two columns share the width in
 *       proportion to their preferred widths when those don't fit. When the space between is narrower than the shown
 *       columns need, Selection and Tool Settings get narrower (down to their minimums) to make room, and take the
 *       room back when those windows close; where even that isn't enough (UI 100% on a small screen), the columns
 *       span the whole work area, over Selection and Tool Settings.</li>
 *   <li>Notifications under Selection, as wide as Selection, in the space between it and the bottom of the work area:
 *       clear of the other windows and of the toast column (at the top right). When that space is lower than its
 *       minimum height, at the bottom of the left column instead. Where that covers a window the user put there, it
 *       opens somewhere clear instead ({@link #clearPlace}).</li>
 * </ul>
 * A window not shown gets the place it would take if it opened now. Every window gets its spec's preferred size where
 * there is room, and is never taller than the work area (unless its minimum height is). Windows this class doesn't
 * know use their spec's anchor (see {@link WindowManager}).
 */
public final class EditorWindowLayout implements WindowManager.DefaultLayout {
    /** Space between windows, and between a window and the work area's edges. */
    public static final int GAP = 4;
    /** Tool Settings opens as tall as the work area allows, up to this. */
    public static final int TOOL_SETTINGS_MAX_HEIGHT = 480;

    /** The left column of the space between Selection and Tool Settings, top to bottom. */
    private static final List<String> MIDDLE_LEFT = List.of(EditorWindows.KEYS, EditorWindows.TUTORIAL,
            EditorWindows.WIKI, EditorWindows.HISTORY);
    /** Its right column, top to bottom. */
    private static final List<String> MIDDLE_RIGHT = List.of(EditorWindows.LIBRARY, EditorWindows.CLIPBOARD);

    /** Where the toast column is now (the Undo anyway offer and the toasts), or null; {@link #clearPlace} avoids it. */
    private final Supplier<Rect> toastColumn;

    /** A layout that knows of no toast column. */
    public EditorWindowLayout() {
        this(() -> null);
    }

    public EditorWindowLayout(Supplier<Rect> toastColumn) {
        this.toastColumn = Objects.requireNonNull(toastColumn);
    }

    @Override
    public Map<String, Rect> place(Rect workArea, Map<String, WindowSpec> specs, Set<String> shown) {
        Map<String, Rect> places = arrange(workArea, specs, shown);
        // A window not shown: where it would open now, beside the windows shown.
        for (String id : specs.keySet()) {
            if (!shown.contains(id) && isArranged(id)) {
                Set<String> opening = new HashSet<>(shown);
                opening.add(id);
                places.put(id, arrange(workArea, specs, opening).get(id));
            }
        }
        return places;
    }

    private static boolean isArranged(String id) {
        return MIDDLE_LEFT.contains(id) || MIDDLE_RIGHT.contains(id) || id.equals(EditorWindows.NOTIFICATIONS);
    }

    /** The places with the windows {@code shown} at their default place (the others' as if they weren't there). */
    private static Map<String, Rect> arrange(Rect workArea, Map<String, WindowSpec> specs, Set<String> shown) {
        Map<String, Rect> places = new LinkedHashMap<>();
        Rect inner = workArea.inset(GAP);
        WindowSpec left = specs.get(EditorWindows.SELECTION);
        WindowSpec right = specs.get(EditorWindows.TOOL_SETTINGS);
        WindowSpec notifications = specs.get(EditorWindows.NOTIFICATIONS);
        int sides = (left == null ? 0 : GAP) + (right == null ? 0 : GAP);
        int[] widths = share(inner.width() - GAP, preferredWidth(left), minWidth(left), preferredWidth(right),
                minWidth(right));
        Rect selection = left == null ? null
                : new Rect(inner.x(), inner.y(), widths[0], height(left, inner.height()));
        boolean notificationsBelow = notifications != null && selection != null
                && inner.bottom() - selection.bottom() - GAP >= notifications.minSize().height();

        List<String> leftIds = new ArrayList<>(MIDDLE_LEFT);
        if (!notificationsBelow) {
            leftIds.add(EditorWindows.NOTIFICATIONS);
        }
        List<WindowSpec> leftColumn = shownOf(leftIds, specs, shown);
        List<WindowSpec> rightColumn = shownOf(MIDDLE_RIGHT, specs, shown);
        int needed = minWidth(leftColumn) + minWidth(rightColumn)
                + (leftColumn.isEmpty() || rightColumn.isEmpty() ? 0 : GAP);
        if (needed > 0 && inner.width() - widths[0] - widths[1] - sides < needed) {
            // Make room between them for the windows shown there, if their minimums allow it.
            int room = inner.width() - needed - sides;
            if (minWidth(left) + minWidth(right) <= room) {
                widths = share(room, preferredWidth(left), minWidth(left), preferredWidth(right), minWidth(right));
            }
        }

        int middleLeft = inner.x();
        int middleRight = inner.right();
        if (left != null) {
            selection = new Rect(inner.x(), inner.y(), widths[0], height(left, inner.height()));
            places.put(left.id(), selection);
            middleLeft = selection.right() + GAP;
        }
        if (right != null) {
            int x = inner.right() - widths[1];
            // Tool Settings holds long forms: it takes the height there is, up to a limit.
            int height = Math.max(right.minSize().height(), Math.min(TOOL_SETTINGS_MAX_HEIGHT, inner.height()));
            places.put(right.id(), new Rect(x, inner.y(), widths[1], height));
            middleRight = x - GAP;
        }
        Rect middle = middleRight > middleLeft ? Rect.ofEdges(middleLeft, inner.y(), middleRight, inner.bottom())
                : new Rect(middleLeft, inner.y(), 0, inner.height());
        if (middle.width() < needed) {
            middle = inner;
        }
        int[] columns = leftColumn.isEmpty() || rightColumn.isEmpty()
                ? new int[] {middle.width(), middle.width()}
                : share(middle.width() - GAP, preferredWidth(leftColumn), minWidth(leftColumn),
                        preferredWidth(rightColumn), minWidth(rightColumn));
        placeColumn(places, leftColumn, middle, columns[0], false);
        placeColumn(places, rightColumn, middle, columns[1], true);
        if (notificationsBelow) {
            Rect below = Rect.ofEdges(selection.x(), selection.bottom() + GAP, selection.right(), inner.bottom());
            places.put(notifications.id(), cell(notifications, below, below.width(),
                    height(notifications, below.height()), false, false));
        }
        return places;
    }

    /**
     * Notifications, opening where its default place covers an open window (one the user put there): the first of
     * these that covers none, else the one that covers the least, counting the toast column too: its default place,
     * the bottom left and bottom right of the space between Selection and Tool Settings (where they are drawn, and
     * where they open by default), the bottom left and bottom right of the work area. The bottom keeps it clear of
     * the toast column at the top where it can. Other windows keep their default place.
     */
    @Override
    public Optional<Rect> clearPlace(Rect workArea, Map<String, WindowSpec> specs, Set<String> shown, String id,
            Map<String, Rect> occupied) {
        WindowSpec spec = specs.get(id);
        if (spec == null || !id.equals(EditorWindows.NOTIFICATIONS)) {
            return Optional.empty();
        }
        Map<String, Rect> places = place(workArea, specs, shown);
        Rect inner = workArea.inset(GAP);
        List<Rect> areas = new ArrayList<>();
        areas.add(between(inner, occupied.get(EditorWindows.SELECTION), occupied.get(EditorWindows.TOOL_SETTINGS)));
        areas.add(between(inner, places.get(EditorWindows.SELECTION), places.get(EditorWindows.TOOL_SETTINGS)));
        areas.add(inner);
        List<Rect> candidates = new ArrayList<>();
        candidates.add(places.get(id));
        for (Rect area : areas) {
            if (area.width() < spec.minSize().width()) {
                continue;
            }
            int width = Math.min(spec.size().width(), area.width());
            int height = height(spec, area.height());
            candidates.add(cell(spec, area, width, height, false, true));
            candidates.add(cell(spec, area, width, height, true, true));
        }
        List<Rect> taken = new ArrayList<>(occupied.values());
        Rect toasts = toastColumn.get();
        if (toasts != null && !toasts.isEmpty()) {
            taken.add(toasts);
        }
        Rect best = null;
        long bestCovered = Long.MAX_VALUE;
        for (Rect candidate : candidates) {
            long covered = 0;
            for (Rect other : taken) {
                Rect overlap = candidate.intersect(other);
                covered += (long) overlap.width() * overlap.height();
            }
            if (covered < bestCovered && inner.intersect(candidate).equals(candidate)) {
                best = candidate;
                bestCovered = covered;
            }
        }
        return Optional.ofNullable(best);
    }

    /** The work area between Selection and Tool Settings (the whole width without them), full height. */
    private static Rect between(Rect inner, Rect selection, Rect toolSettings) {
        int left = selection == null ? inner.x() : Math.max(inner.x(), selection.right() + GAP);
        int right = toolSettings == null ? inner.right() : Math.min(inner.right(), toolSettings.x() - GAP);
        return right > left ? Rect.ofEdges(left, inner.y(), right, inner.bottom()) : Rect.EMPTY;
    }

    /**
     * One column: its first window at the top of {@code area}, its last at the bottom and the others under the one
     * before, the height shared among them ({@link #shareAll}).
     */
    private static void placeColumn(Map<String, Rect> places, List<WindowSpec> column, Rect area, int width,
            boolean alignRight) {
        if (column.isEmpty()) {
            return;
        }
        if (column.size() == 1) {
            // Alone, at the top; Notifications at the bottom, clear of the toasts at the top right where it can be.
            WindowSpec only = column.get(0);
            boolean low = only.id().equals(EditorWindows.NOTIFICATIONS);
            places.put(only.id(), cell(only, area, width, height(only, area.height()), alignRight, low));
            return;
        }
        int count = column.size();
        int[] preferred = new int[count];
        int[] minimum = new int[count];
        for (int i = 0; i < count; i++) {
            preferred[i] = column.get(i).size().height();
            minimum[i] = column.get(i).minSize().height();
        }
        int[] heights = shareAll(area.height() - (count - 1) * GAP, preferred, minimum);
        int y = area.y();
        for (int i = 0; i < count; i++) {
            WindowSpec spec = column.get(i);
            boolean last = i == count - 1;
            Rect rect = cell(spec, area, width, heights[i], alignRight, last);
            if (!last) {
                rect = rect.withPosition(rect.x(), y);
            }
            places.put(spec.id(), rect);
            y += heights[i] + GAP;
        }
    }

    private static Rect cell(WindowSpec spec, Rect area, int columnWidth, int height, boolean alignRight,
            boolean alignBottom) {
        int width = Math.max(spec.minSize().width(), Math.min(spec.size().width(), columnWidth));
        int x = alignRight ? area.right() - width : area.x();
        int y = alignBottom ? area.bottom() - height : area.y();
        return new Rect(x, y, width, height);
    }

    /**
     * Splits {@code total} between two sizes: their preferred sizes when both fit, otherwise in proportion to them,
     * each at least its minimum (so the two may then add up to more than {@code total}).
     */
    static int[] share(int total, int preferredA, int minA, int preferredB, int minB) {
        if (preferredA + preferredB <= total) {
            return new int[] {preferredA, preferredB};
        }
        int a = (int) ((long) Math.max(0, total) * preferredA / Math.max(1, preferredA + preferredB));
        a = Math.max(minA, a);
        int b = Math.max(minB, total - a);
        a = Math.max(minA, Math.min(a, total - b));
        return new int[] {a, b};
    }

    /**
     * Splits {@code total} among several sizes: their preferred sizes when all fit, their minimums when those don't
     * (they then add up to more than {@code total}), otherwise in proportion to the preferred sizes with each at least
     * its minimum, adding up to {@code total}.
     */
    static int[] shareAll(int total, int[] preferred, int[] minimum) {
        int count = preferred.length;
        long preferredSum = 0;
        long minimumSum = 0;
        for (int i = 0; i < count; i++) {
            preferredSum += preferred[i];
            minimumSum += minimum[i];
        }
        if (preferredSum <= total) {
            return preferred.clone();
        }
        if (minimumSum >= total) {
            return minimum.clone();
        }
        // Proportional shares; one below its minimum gets the minimum and the others share what is left, again.
        boolean[] atMinimum = new boolean[count];
        boolean changed = true;
        while (changed) {
            changed = false;
            long left = total;
            long weight = 0;
            for (int i = 0; i < count; i++) {
                if (atMinimum[i]) {
                    left -= minimum[i];
                } else {
                    weight += preferred[i];
                }
            }
            for (int i = 0; i < count; i++) {
                if (!atMinimum[i] && left * preferred[i] / Math.max(1, weight) < minimum[i]) {
                    atMinimum[i] = true;
                    changed = true;
                }
            }
        }
        int[] shares = new int[count];
        long left = total;
        long weight = 0;
        for (int i = 0; i < count; i++) {
            if (atMinimum[i]) {
                shares[i] = minimum[i];
                left -= minimum[i];
            } else {
                weight += preferred[i];
            }
        }
        long given = 0;
        int lastShared = -1;
        for (int i = 0; i < count; i++) {
            if (!atMinimum[i]) {
                shares[i] = (int) (left * preferred[i] / Math.max(1, weight));
                given += shares[i];
                lastShared = i;
            }
        }
        if (lastShared >= 0) {
            // What rounding down left over goes to the last one shared.
            shares[lastShared] += (int) (left - given);
        }
        return shares;
    }

    /** The preferred height, but no taller than {@code available} (nor shorter than the minimum). */
    private static int height(WindowSpec spec, int available) {
        return Math.max(spec.minSize().height(), Math.min(spec.size().height(), available));
    }

    private static List<WindowSpec> shownOf(List<String> ids, Map<String, WindowSpec> specs, Set<String> shown) {
        return ids.stream().filter(shown::contains).map(specs::get).filter(Objects::nonNull).toList();
    }

    private static int preferredWidth(WindowSpec spec) {
        return spec == null ? 0 : spec.size().width();
    }

    private static int minWidth(WindowSpec spec) {
        return spec == null ? 0 : spec.minSize().width();
    }

    private static int preferredWidth(List<WindowSpec> column) {
        return column.stream().map(WindowSpec::size).mapToInt(Size::width).max().orElse(0);
    }

    private static int minWidth(List<WindowSpec> column) {
        return column.stream().map(WindowSpec::minSize).mapToInt(Size::width).max().orElse(0);
    }
}
