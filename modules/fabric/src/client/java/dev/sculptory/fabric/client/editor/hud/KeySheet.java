package dev.sculptory.fabric.client.editor.hud;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.input.KeyChord;
import dev.sculptory.fabric.client.editor.ui.Align;
import dev.sculptory.fabric.client.editor.ui.Insets;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.ScrollModel;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.FlexContainer;
import dev.sculptory.fabric.client.editor.ui.layout.Row;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.ScrollPane;
import dev.sculptory.fabric.client.editor.ui.widget.SectionHeading;
import dev.sculptory.fabric.client.editor.ui.widget.TextInput;
import dev.sculptory.fabric.client.editor.ui.widget.Tooltip;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.lwjgl.glfw.GLFW;

/**
 * The F1 key sheet, over a dimmed screen: every editor key under the Keys window's headings ({@link HelpSheet}), in
 * the fewest columns (up to {@link #MAX_COLUMNS}) in which the whole list fits the screen's height, of about the same
 * height each (a group may go on at the top of the next column, under its heading again; only whole rows show when
 * the list scrolls), a filter box at the top that has the keyboard (typing narrows the list by what an action does or
 * by a key; the sheet then gets shorter from the bottom, its top edge staying put), and a <b>Change keys…</b> button
 * that closes the sheet and opens the Keys window.
 *
 * <p>Up/Down scroll by a row, Page Up/Page Down by a page, Home/End to the top and bottom (also while the filter box
 * has the keyboard, which gives up its caret's Home/End for it); the mouse wheel scrolls too. F1 (the Help key as
 * bound) or Esc closes the sheet, and so does a click on the dimmed screen outside it; a click inside never does.
 *
 * <p>Laid out and drawn in UI units, with its own {@link UiContext} (focus, hover, pointer capture, tooltips).
 */
public final class KeySheet {
    /** Space between two rows of the sheet: none, a row being a line of text and a unit below it. */
    private static final int ROW_GAP = 0;
    /** Space between a heading's band and its first row. */
    private static final int HEADING_SPACE_BELOW = 1;
    /** A group needs this many rows on each side to go on in the next column (no row left alone under a heading). */
    private static final int MIN_ROWS_EACH_SIDE = 2;
    /** Between the editor's top bar and the sheet, where the sheet fits under it. */
    private static final int TOP_BAR_GAP = 4;
    /** The most columns the rows go in (where the screen is wide enough for each to be readable). */
    public static final int MAX_COLUMNS = 3;

    private final UiContext ctx;
    private final EditorKeymap keymap;
    private final Translator tr;
    private final Supplier<HelpSheet.VanillaKeys> vanillaKeys;
    private final IntFunction<Optional<String>> toolName;
    private final Runnable changeKeys;

    private boolean open;
    private List<HelpSheet.Group> groups = List.of();
    private List<HelpSheet.Group> shown = List.of();
    private String query = "";
    private Node root;
    private TextInput filter;
    private ScrollPane scroll;
    private Column body;
    private Button changeKeysButton;
    /** How many columns the rows are in (1 to {@link #MAX_COLUMNS}) since the last layout; 0 before. */
    private int columns;
    /** How wide each column is. */
    private int columnWidth;
    /** What the body holds now: which list, in how many columns of what width (the body is rebuilt when they change). */
    private List<HelpSheet.Group> bodyList;
    private int bodyColumns;
    private int bodyColumnWidth;
    /** The columns the whole list is in on the screen it was measured for: a filtered list never has more. */
    private int fullColumns;
    /** The key column of every row: the widest key name of the sheet (see {@link KeyLineRow#keyColumnWidth}). */
    private int keyWidth;
    /** The widest description of the sheet. */
    private int widestText;
    /**
     * The whole list's size (nothing typed) on the screen it was measured for: while a filter narrows the list the
     * sheet keeps its top edge, left edge and width, getting shorter from the bottom, instead of centring anew.
     */
    private Size fullSize;
    private int fullSizeWidth;
    private int fullSizeHeight;
    /**
     * Whether the columns of the rows shown were chosen for this filter and screen: a filtered list takes the fewest
     * columns (never more than the whole list's) in which it fits the whole list's height, so a short one reads down
     * one column.
     */
    private boolean columnsDecided;
    /** The line under the rows: how to close the sheet, and while it scrolls, that there is more. */
    private Label footer;
    private int width;
    private int height;

    /**
     * @param toolName   the name of the tool in palette slot 1-13 (the sheet names its key after it)
     * @param changeKeys the Change keys… button: the sheet is closed first
     */
    public KeySheet(TextMeasure text, Theme theme, EditorKeymap keymap, Translator translator,
            Supplier<HelpSheet.VanillaKeys> vanillaKeys, IntFunction<Optional<String>> toolName, Runnable changeKeys) {
        this.ctx = new UiContext(text, theme);
        this.keymap = Objects.requireNonNull(keymap);
        this.tr = Objects.requireNonNull(translator);
        this.vanillaKeys = Objects.requireNonNull(vanillaKeys);
        this.toolName = Objects.requireNonNull(toolName);
        this.changeKeys = Objects.requireNonNull(changeKeys);
    }

    // ---- State ----

    /** Opens the sheet with an empty filter, reading the keys as bound now; the filter box has the keyboard. */
    public void open() {
        groups = HelpSheet.build(keymap, tr, vanillaKeys.get(), toolName);
        List<HelpSheet.Line> lines = groups.stream().flatMap(group -> group.lines().stream()).toList();
        // The key column is what the keys need: the widest name. The rest goes to what they do.
        keyWidth = KeyLineRow.keyColumnWidth(ctx.text(), lines.stream().map(HelpSheet.Line::keys).toList(),
                tr.translate("sculptory.keys.unbound"), 0);
        widestText = lines.stream().mapToInt(line -> ctx.text().width(line.text())).max().orElse(0);
        fullSize = null;
        build();
        filterChanged("");
        open = true;
        ctx.setFocus(filter);
    }

    public void close() {
        open = false;
        ctx.clearFocus();
        ctx.releaseCapture();
        ctx.setHovered(null);
    }

    public boolean isOpen() {
        return open;
    }

    public UiContext context() {
        return ctx;
    }

    /** What the filter box holds. */
    public String filterText() {
        return query;
    }

    /** Types {@code text} into the filter box (as typing does: the list narrows and scrolls to the top). */
    public void setFilter(String text) {
        if (filter != null) {
            filter.setText(text);
        }
        filterChanged(text);
    }

    /** The groups and rows shown now (all of them while the filter is empty). */
    public List<HelpSheet.Group> shownGroups() {
        return shown;
    }

    /** The scroll state of the rows. */
    public ScrollModel scroll() {
        return scroll.scroll();
    }

    /** Where the rows scroll, from the last layout ({@link Rect#EMPTY} before). */
    public Rect scrollViewport() {
        return scroll == null ? Rect.EMPTY : scroll.bounds();
    }

    public TextInput filterInput() {
        return filter;
    }

    public Button changeKeysButton() {
        return changeKeysButton;
    }

    /** How many columns the rows are in (1 to {@link #MAX_COLUMNS}) since the last layout; 0 before. */
    public int columns() {
        return columns;
    }

    /** How wide each row's description is since the last layout (a wider one wraps). */
    public int descriptionWidth() {
        return columnWidth - (keyWidth + ctx.theme().keyTextGap);
    }

    /** Where the sheet was last laid out, in UI units ({@link Rect#EMPTY} before). */
    public Rect bounds() {
        return root == null ? Rect.EMPTY : root.bounds();
    }

    // ---- Building ----

    private void build() {
        Theme theme = ctx.theme();
        Label title = Label.heading(tr.translate("sculptory.help.title"));
        changeKeysButton = new Button(tr.translate("sculptory.help.change_keys"), () -> {
            close();
            changeKeys.run();
        });
        changeKeysButton.setTooltip(tr.translate("sculptory.help.change_keys.tooltip"));
        filter = new TextInput("", this::filterChanged);
        filter.setPlaceholder(tr.translate("sculptory.help.filter"));
        filter.setTooltip(tr.translate("sculptory.help.filter.tooltip"));
        filter.setMaxLength(64);
        filter.setMinSize(30, theme.controlHeight);
        filter.setGrow(1);

        // One line for the title, the filter box and the button: the rows get the height.
        Row header = Row.of(title, filter, changeKeysButton);
        header.setGap(theme.gap + 2);
        header.setCrossAlign(Align.CENTER);
        header.setMinSize(0, theme.controlHeight);

        body = new Column();
        body.setGap(theme.gap);
        scroll = new ScrollPane(body);
        scroll.setGrow(1);
        scroll.setMinSize(0, 3 * theme.rowHeight);

        footer = Label.dim(footerText());
        footer.setMinSize(0, ctx.text().lineHeight());

        // The box has the keyboard from the start; its placeholder says what it is for until something is typed.
        Column content = Column.of(header, scroll, footer);
        content.setGap(theme.gap + 2);
        root = new Panel(content, Insets.all(theme.sheetMargin), theme.popupBackground, theme.popupBorder);
        columns = 0;
        bodyList = null;
        columnsDecided = false;
    }

    private void filterChanged(String text) {
        query = text;
        shown = HelpSheet.filter(groups, text);
        columnsDecided = false; // chosen, and the body rebuilt, at the next layout
        if (scroll != null) {
            scroll.scroll().setOffset(0);
        }
    }

    /**
     * The footer: how to close the sheet, and while the rows scroll, that more are below (or, at the end, how to get
     * back to the top).
     */
    private String footerText() {
        String help = keymap.displayFirst(KeyAction.HELP);
        if (scroll == null || scroll.scroll().maxOffset() <= 0) {
            return tr.translate("sculptory.help.footer", help);
        }
        boolean atEnd = scroll.scroll().offset() >= scroll.scroll().maxOffset();
        return tr.translate(atEnd ? "sculptory.help.footer.at_end" : "sculptory.help.footer.more_below", help);
    }

    /** The footer's text as shown (tests). */
    public String footerShown() {
        return footer == null ? "" : footer.text();
    }

    /**
     * The rows of {@code list} in {@code count} columns, each group's heading above its rows, split where the columns
     * come out most even in height ({@link #balancedSplits}): a group may go on at the top of the next column, under
     * its heading again ("Selection (continued)"). Nothing is done when the body holds that already.
     */
    private void buildBody(List<HelpSheet.Group> list, int count, int columnWidth) {
        if (bodyList == list && bodyColumns == count && bodyColumnWidth == columnWidth) {
            return;
        }
        bodyList = list;
        bodyColumns = count;
        bodyColumnWidth = columnWidth;
        Theme theme = ctx.theme();
        body.clear();
        if (list.isEmpty()) {
            body.add(Label.dim(tr.translate("sculptory.help.no_match")));
            return;
        }
        String unbound = tr.translate("sculptory.keys.unbound");
        List<Entry> entries = new ArrayList<>();
        List<Node> nodes = new ArrayList<>();
        for (int group = 0; group < list.size(); group++) {
            entries.add(new Entry(group, true, headingHeight(theme)));
            nodes.add(null);
            for (HelpSheet.Line line : list.get(group).lines()) {
                KeyLineRow row = new KeyLineRow(line.keys(), line.text(), unbound, keyWidth);
                entries.add(new Entry(group, false, row.measure(ctx, columnWidth).height()));
                nodes.add(row);
            }
        }
        List<Integer> edges = new ArrayList<>();
        edges.add(0);
        edges.addAll(balancedSplits(entries, count, ROW_GAP, headingSpace(theme), headingHeight(theme)));
        edges.add(entries.size());
        Row row = new Row();
        row.setGap(theme.keySheetColumnGap);
        row.setCrossAlign(Align.START);
        for (int i = 1; i < edges.size(); i++) {
            if (edges.get(i) > edges.get(i - 1)) {
                row.add(column(list, entries, nodes, edges.get(i - 1), edges.get(i), columnWidth));
            }
        }
        body.add(row);
    }

    /** One column: entries {@code [from, to)}, under their group's heading again when they start inside a group. */
    private Node column(List<HelpSheet.Group> list, List<Entry> entries, List<Node> nodes, int from, int to,
            int columnWidth) {
        Theme theme = ctx.theme();
        SheetColumn column = new SheetColumn(() -> scroll.bounds());
        column.setGap(ROW_GAP);
        column.setFixedWidth(columnWidth);
        for (int i = from; i < to; i++) {
            Entry entry = entries.get(i);
            String title = list.get(entry.group()).title();
            if (i == from && !entry.heading()) {
                column.add(new SectionHeading(tr.translate("sculptory.help.continued", title))
                        .setSpaceBelow(HEADING_SPACE_BELOW));
            }
            column.add(entry.heading()
                    ? new SectionHeading(title).setSpaceAbove(i == from ? 0 : headingSpace(theme))
                            .setSpaceBelow(HEADING_SPACE_BELOW)
                    : nodes.get(i));
        }
        return column;
    }

    /** Space above a heading that follows rows (between two groups). */
    private static int headingSpace(Theme theme) {
        return theme.gap + 2;
    }

    /** A heading's height, its band and the space below it. */
    private static int headingHeight(Theme theme) {
        return theme.rowHeight + HEADING_SPACE_BELOW;
    }

    /**
     * One heading or row of the sheet, for balancing the columns.
     *
     * @param group   the index of its group
     * @param heading whether it is the group's heading (the first entry of each group)
     * @param height  its height at the column's width, a heading's without the space above it
     */
    record Entry(int group, boolean heading, int height) {}

    /**
     * Where the second column starts (an index into {@code entries}, the headings and rows in order) so that the
     * taller column is as short as can be: at a group's heading, or inside a group with at least
     * {@link #MIN_ROWS_EACH_SIDE} of its rows on each side, the second column then repeating the heading. A heading is
     * never the last entry of the first column. {@code entries.size()} (everything in the first column) when no split
     * is possible.
     */
    static int balancedSplit(List<Entry> entries, int rowGap, int headingSpace, int headingHeight) {
        List<Integer> splits = balancedSplits(entries, 2, rowGap, headingSpace, headingHeight);
        return splits.isEmpty() ? entries.size() : splits.get(0);
    }

    /**
     * Where each column after the first starts, for {@code count} columns, so that the tallest column is as short as
     * can be (as {@link #balancedSplit} chooses for two; between splits as even, those between groups win, then those
     * whose shortest column is tallest, so the columns end about level). Fewer starts than {@code count - 1} when no
     * more splits are possible; none for one column.
     */
    static List<Integer> balancedSplits(List<Entry> entries, int count, int rowGap, int headingSpace,
            int headingHeight) {
        return cut(entries, 0, count, rowGap, headingSpace, headingHeight).starts();
    }

    /**
     * Columns for {@code entries[from, ...)}: where each after the first starts, the tallest's height, whether every
     * start is a heading, and the shortest's height.
     */
    private record Cut(List<Integer> starts, int height, boolean atHeadings, int shortest) {}

    private static Cut cut(List<Entry> entries, int from, int count, int rowGap, int headingSpace,
            int headingHeight) {
        int whole = columnHeight(entries, from, entries.size(), rowGap, headingSpace, headingHeight);
        if (count <= 1) {
            return new Cut(List.of(), whole, true, whole);
        }
        Cut best = new Cut(List.of(), whole, false, whole);
        for (int split = from + 1; split < entries.size(); split++) {
            if (!canSplit(entries, from, split)) {
                continue;
            }
            Cut rest = cut(entries, split, count - 1, rowGap, headingSpace, headingHeight);
            int first = columnHeight(entries, from, split, rowGap, headingSpace, headingHeight);
            int height = Math.max(first, rest.height());
            boolean atHeadings = entries.get(split).heading() && rest.atHeadings();
            int shortest = Math.min(first, rest.shortest());
            // Level with the best so far: splits between groups read better than one inside a group, then columns
            // ending about level (the tallest may be a column no split can shorten).
            if (height < best.height() || height == best.height() && (atHeadings && !best.atHeadings()
                    || atHeadings == best.atHeadings() && shortest > best.shortest())) {
                List<Integer> starts = new ArrayList<>();
                starts.add(split);
                starts.addAll(rest.starts());
                best = new Cut(starts, height, atHeadings, shortest);
            }
        }
        return best;
    }

    /** Whether a column starting at {@code from} may end before {@code split} (see {@link #balancedSplit}). */
    private static boolean canSplit(List<Entry> entries, int from, int split) {
        if (entries.get(split - 1).heading()) {
            return false;
        }
        Entry first = entries.get(split);
        if (first.heading()) {
            return true;
        }
        int before = 0;
        for (int i = split - 1; i >= from && !entries.get(i).heading(); i--) {
            before++;
        }
        int after = 0;
        for (int i = split; i < entries.size() && !entries.get(i).heading(); i++) {
            after++;
        }
        return before >= MIN_ROWS_EACH_SIDE && after >= MIN_ROWS_EACH_SIDE;
    }

    /**
     * The height of a column holding {@code entries[from, to)}: a heading after other entries keeps its space above;
     * a column starting inside a group repeats the group's heading.
     */
    static int columnHeight(List<Entry> entries, int from, int to, int rowGap, int headingSpace, int headingHeight) {
        if (from >= to) {
            return 0;
        }
        int height = entries.get(from).heading() ? 0 : headingHeight + rowGap;
        for (int i = from; i < to; i++) {
            Entry entry = entries.get(i);
            height += entry.height();
            if (i > from) {
                height += rowGap + (entry.heading() ? headingSpace : 0);
            }
        }
        return height;
    }

    /**
     * A column of the sheet: headings and rows one under another, of which it draws only those wholly inside the
     * scrolled view, so a row at the view's edge is left out rather than cut in half.
     */
    private static final class SheetColumn extends FlexContainer {
        private final Supplier<Rect> view;

        SheetColumn(Supplier<Rect> view) {
            super(false, Align.STRETCH);
            this.view = view;
        }

        @Override
        public void render(UiGraphics g, UiContext ctx) {
            Rect shown = view.get();
            for (Node child : children()) {
                Rect at = child.bounds();
                if (child.isVisible() && at.y() >= shown.y() && at.bottom() <= shown.bottom()) {
                    child.render(g, ctx);
                }
            }
        }
    }

    // ---- Layout and drawing (UI units) ----

    /** {@link #layout(int, int, int)} on a screen with nothing kept at its top. */
    public void layout(int screenWidth, int screenHeight) {
        layout(screenWidth, screenHeight, 0);
    }

    /**
     * Lays the sheet out on a screen of this size: each column's key column as wide as the widest key name and its
     * descriptions as wide as the widest (up to {@link Theme#keySheetTextMaxWidth}, or what the screen leaves), in
     * the fewest columns (up to {@link #MAX_COLUMNS}, each giving its descriptions at least
     * {@link Theme#keySheetTextMinWidth}) in which the whole list fits the screen's height, or the most the screen is
     * wide enough for when none does (the rows then scroll). A filtered list takes the fewest columns, never more
     * than the whole list's, in which it fits the whole list's height. The whole list is centred in the room under
     * {@code topBar} (the editor's top bar, a few units under it) where it fits there with the screen's margin; where
     * it only fits without the margins, centred between the bar and the screen's bottom; otherwise centred on the
     * screen, over the bar (never over it by a sliver). While a filter narrows the list, the sheet keeps the whole
     * list's top edge, left edge and width.
     */
    public void layout(int screenWidth, int screenHeight, int topBar) {
        if (!open || root == null) {
            return;
        }
        width = screenWidth;
        height = screenHeight;
        ctx.setScreenSize(screenWidth, screenHeight);
        Theme theme = ctx.theme();
        int margin = theme.sheetMargin;
        int maxWidth = Math.max(0, screenWidth - 2 * margin);
        int maxHeight = Math.max(0, screenHeight - 2 * margin);
        boolean screenChanged = fullSizeWidth != screenWidth || fullSizeHeight != screenHeight;
        if (fullSize == null || screenChanged) {
            // The whole list on this screen: where the sheet stands, and the most columns a filtered list gets.
            fullColumns = fewestColumns(groups, mostColumns(screenWidth), maxHeight, maxWidth);
            fullSize = root.measure(ctx, maxWidth);
            fullSizeWidth = screenWidth;
            fullSizeHeight = screenHeight;
            columnsDecided = false;
        }
        if (!columnsDecided) {
            columnsDecided = true;
            columns = query.isEmpty() ? fullColumns
                    : fewestColumns(shown, fullColumns, Math.min(fullSize.height(), maxHeight), maxWidth);
            columnWidth = columnWidth(screenWidth, columns);
        }
        buildBody(shown, columns, columnWidth);
        Size size = root.measure(ctx, maxWidth);
        int w = Math.min(Math.max(size.width(), fullSize.width()), maxWidth);
        int h = Math.min(size.height(), maxHeight);
        int fullHeight = Math.min(fullSize.height(), maxHeight);
        int below = topBar <= 0 ? margin : topBar + TOP_BAR_GAP;
        int top;
        if (below + fullHeight <= screenHeight - margin) {
            // Centred in the room under the top bar.
            top = below + (screenHeight - margin - below - fullHeight) / 2;
        } else if (topBar > 0 && topBar + fullHeight <= screenHeight) {
            // Just fits under the top bar with less room around it: centred there, never a unit over the bar.
            top = topBar + (screenHeight - topBar - fullHeight) / 2;
        } else {
            // Taller than the room under the top bar: centred on the screen, over the bar, as a modal sheet.
            top = (screenHeight - fullHeight) / 2;
        }
        int y = Math.max(0, Math.min(top, screenHeight - h));
        root.layout(ctx, new Rect((screenWidth - w) / 2, y, w, h));
        updateFooter();
    }

    /** What the screen leaves for the columns: the width less the margins, the panel's insets and the scrollbar. */
    private int room(int screenWidth) {
        Theme theme = ctx.theme();
        int chrome = 2 * theme.sheetMargin + theme.scrollbarWidth + 1;
        return Math.max(0, screenWidth - 2 * theme.sheetMargin) - chrome;
    }

    /** The widest a description needs: the widest there is, up to the theme's most. */
    private int wantedText() {
        return Math.min(widestText, ctx.theme().keySheetTextMaxWidth);
    }

    /** The width of each of {@code count} columns' descriptions on this screen (what the room leaves them). */
    private int textWidth(int screenWidth, int count) {
        Theme theme = ctx.theme();
        int keyPart = keyWidth + theme.keyTextGap;
        return Math.min(wantedText(), (room(screenWidth) - (count - 1) * theme.keySheetColumnGap) / count - keyPart);
    }

    /** The width of each of {@code count} columns: the key column, the gap and the descriptions. */
    private int columnWidth(int screenWidth, int count) {
        int keyPart = keyWidth + ctx.theme().keyTextGap;
        if (count == 1) {
            return Math.max(120, Math.min(room(screenWidth), keyPart + wantedText()));
        }
        return keyPart + textWidth(screenWidth, count);
    }

    /**
     * The most columns this screen is wide enough for: each must give its descriptions
     * {@link Theme#keySheetTextMinWidth} (or the widest there is, when narrower).
     */
    private int mostColumns(int screenWidth) {
        int most = 1;
        int least = Math.min(wantedText(), ctx.theme().keySheetTextMinWidth);
        for (int count = 2; count <= MAX_COLUMNS; count++) {
            if (textWidth(screenWidth, count) >= least) {
                most = count;
            }
        }
        return most;
    }

    /**
     * Builds {@code list} into the fewest columns, up to {@code most}, in which the sheet is at most {@code limit}
     * tall (no scrolling), or into {@code most} when none does; returns the count, the body then holding it.
     */
    private int fewestColumns(List<HelpSheet.Group> list, int most, int limit, int maxWidth) {
        for (int count = 1; count < most; count++) {
            buildBody(list, count, columnWidth(width, count));
            if (root.measure(ctx, maxWidth).height() <= limit) {
                return count;
            }
        }
        buildBody(list, most, columnWidth(width, most));
        return most;
    }

    /** The footer's text for how the rows are scrolled now; laid out again when it changed. */
    private void updateFooter() {
        String text = footerText();
        if (!text.equals(footer.text())) {
            footer.setText(text);
            root.layout(ctx, root.bounds());
        }
    }

    public void render(UiGraphics g, long nowMs) {
        if (!open || root == null) {
            return;
        }
        ctx.setNow(nowMs);
        g.fill(0, 0, width, height, ctx.theme().backdrop);
        root.render(g, ctx);
    }

    /** The hovered control's tooltip, after the theme's delay. */
    public void renderTooltip(UiGraphics g, double mouseX, double mouseY) {
        Node hovered = ctx.hovered();
        if (!open || hovered == null || ctx.captured() != null || ctx.hoverDurationMs() < ctx.theme().tooltipDelayMs) {
            return;
        }
        String text = null;
        for (Node node = hovered; node != null && text == null; node = node.parent()) {
            text = node.tooltip();
        }
        if (text != null && !text.isBlank()) {
            Tooltip.render(g, ctx, text, (int) mouseX, (int) mouseY);
        }
    }

    // ---- Input (UI units) ----

    /** A press inside the sheet goes to its controls; one on the dimmed screen outside closes it. Always consumed. */
    public boolean mouseDown(double x, double y, int button, int modifiers) {
        ctx.setMouse(x, y);
        ctx.setModifiers(modifiers);
        if (root == null || !root.bounds().contains(x, y)) {
            close();
            return true;
        }
        for (Node node = root.hitTest(x, y); node != null; node = node.parent()) {
            if (!node.isEffectivelyEnabled()) {
                break;
            }
            if (node.mouseDown(ctx, x, y, button)) {
                ctx.capture(node, button);
                if (node.focusOnClick()) {
                    ctx.setFocus(node);
                }
                break;
            }
        }
        return true;
    }

    public boolean mouseDragged(double x, double y, int button) {
        ctx.setMouse(x, y);
        Node captured = ctx.captured();
        if (captured != null && button == ctx.captureButton()) {
            captured.mouseDrag(ctx, x, y, button);
        }
        return true;
    }

    public boolean mouseUp(double x, double y, int button) {
        ctx.setMouse(x, y);
        Node captured = ctx.captured();
        if (captured != null && button == ctx.captureButton()) {
            ctx.releaseCapture();
            captured.mouseUp(ctx, x, y, button);
        }
        return true;
    }

    public boolean mouseScrolled(double x, double y, double amount, int modifiers) {
        ctx.setMouse(x, y);
        ctx.setModifiers(modifiers);
        Node target = root == null ? null : root.hitTest(x, y);
        for (Node node = target; node != null; node = node.parent()) {
            if (node.mouseScroll(ctx, x, y, amount)) {
                return true;
            }
        }
        if (scroll != null) {
            scroll.mouseScroll(ctx, x, y, amount);
        }
        return true;
    }

    public void mouseMoved(double x, double y) {
        ctx.setMouse(x, y);
        ctx.setHovered(root == null ? null : root.hitTest(x, y));
    }

    /**
     * Esc closes; the scroll keys scroll; other keys go to the control with the keyboard (the filter box types them),
     * Tab moves between the controls, and the Help key closes. Returns false for a key the sheet has no use for.
     */
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        ctx.setModifiers(modifiers);
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        if (scrollKey(key)) {
            return true;
        }
        Node focused = ctx.focused();
        for (Node node = focused; node != null; node = node.parent()) {
            if (node.keyPressed(ctx, key, scanCode, modifiers)) {
                return true;
            }
        }
        if (key == GLFW.GLFW_KEY_TAB && root != null) {
            if ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0) {
                ctx.focusPrevious(root);
            } else {
                ctx.focusNext(root);
            }
            return true;
        }
        if (key > 0 && keymap.match(KeyChord.key(key, modifiers)).filter(action -> action == KeyAction.HELP).isPresent()) {
            close();
            return true;
        }
        return false;
    }

    public boolean charTyped(char chr, int modifiers) {
        for (Node node = ctx.focused(); node != null; node = node.parent()) {
            if (node.charTyped(ctx, chr, modifiers)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasKeyboardFocus() {
        return open && ctx.focused() != null;
    }

    /** Up/Down a row, Page Up/Down a page (less a row), Home/End to the ends. */
    private boolean scrollKey(int key) {
        if (scroll == null) {
            return false;
        }
        ScrollModel model = scroll.scroll();
        int row = ctx.theme().rowHeight;
        int page = Math.max(row, model.viewportSize() - row);
        switch (key) {
            case GLFW.GLFW_KEY_UP -> model.scrollBy(-row);
            case GLFW.GLFW_KEY_DOWN -> model.scrollBy(row);
            case GLFW.GLFW_KEY_PAGE_UP -> model.scrollBy(-page);
            case GLFW.GLFW_KEY_PAGE_DOWN -> model.scrollBy(page);
            case GLFW.GLFW_KEY_HOME -> model.setOffset(0);
            case GLFW.GLFW_KEY_END -> model.setOffset(model.maxOffset());
            default -> {
                return false;
            }
        }
        if (root != null && width > 0) {
            root.layout(ctx, root.bounds());
        }
        return true;
    }
}
