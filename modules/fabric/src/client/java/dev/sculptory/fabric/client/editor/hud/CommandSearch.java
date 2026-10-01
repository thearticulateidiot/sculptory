package dev.sculptory.fabric.client.editor.hud;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.commands.Availability;
import dev.sculptory.fabric.client.editor.commands.CommandRanking;
import dev.sculptory.fabric.client.editor.commands.SearchEntry;
import dev.sculptory.fabric.client.editor.ui.Insets;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.PopupLayer;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.Padding;
import dev.sculptory.fabric.client.editor.ui.widget.TextInput;
import dev.sculptory.fabric.client.session.Notice;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.lwjgl.glfw.GLFW;

/**
 * Find a command (Ctrl+K): a popup at the top centre with a search box (it has the keyboard) and up to
 * {@link Theme#commandSearchRows} results, best first ({@link CommandRanking}). Each row shows the name, where it lives
 * (dim) and its key on the right; one that can't run now is dimmed with the reason as its tooltip. Up/Down, Page
 * Up/Page Down move through the results; Enter (or a click) runs the selected one and closes the search, or, for a
 * dimmed one, toasts why and stays open; Esc closes it. Typing keeps the first row selected. With nothing typed the
 * list starts with the recently run entries. The empty box shows what it searches (its placeholder stays until
 * something is typed), and when more entries match than are shown, a dim last line says how many more ("+12 more,
 * keep typing").
 */
public final class CommandSearch {
    private final Supplier<UiContext> popups;
    private final Supplier<List<SearchEntry>> source;
    private final Supplier<List<String>> recent;
    private final Consumer<Notice> notify;
    private final Translator tr;
    private List<SearchEntry> entries = List.of();
    private List<SearchEntry> shown = List.of();
    /** How many matches there are beyond the shown ones. */
    private int more;
    private String query = "";
    private int selected = -1;
    private PopupLayer.Popup popup;
    private SearchField field;

    /**
     * @param popups the context whose popup layer holds the search (the window manager's)
     * @param source everything the search can find, read each time it opens
     * @param recent ids run recently, newest first
     * @param notify toasts why a dimmed entry can't run
     */
    public CommandSearch(Supplier<UiContext> popups, Supplier<List<SearchEntry>> source, Supplier<List<String>> recent,
            Consumer<Notice> notify, Translator translator) {
        this.popups = Objects.requireNonNull(popups);
        this.source = Objects.requireNonNull(source);
        this.recent = Objects.requireNonNull(recent);
        this.notify = Objects.requireNonNull(notify);
        this.tr = Objects.requireNonNull(translator);
    }

    /** Opens the search {@code top} units below the screen's top edge, centred, with an empty query. */
    public void open(int top) {
        UiContext ctx = popups.get();
        if (isOpen()) {
            ctx.setFocus(field);
            return;
        }
        Theme theme = ctx.theme();
        entries = List.copyOf(source.get());
        query = "";
        field = new SearchField();
        field.setPlaceholder(tr.translate("sculptory.search.placeholder"));
        field.setMaxLength(64);
        Column column = Column.of(field, new Results());
        column.setGap(theme.gap);
        int width = Math.min(theme.commandSearchWidth, Math.max(0, ctx.screenWidth()));
        column.setFixedWidth(Math.max(0, width - 2 * theme.padding - 2));
        Rect anchor = new Rect(Math.max(0, (ctx.screenWidth() - width) / 2), top, width, 0);
        popup = ctx.popups().open(null, new Padding(Insets.all(theme.padding), column), anchor, width, () -> {
            if (field != null && ctx.focused() == field) {
                ctx.clearFocus();
            }
            popup = null;
            field = null;
        });
        ctx.setFocus(field);
        update();
    }

    public boolean isOpen() {
        return popup != null && popups.get().popups().popups().contains(popup);
    }

    public void close() {
        if (popup != null) {
            popups.get().popups().close(popup);
        }
    }

    /** What is typed. */
    public String query() {
        return query;
    }

    /** Types {@code text} into the search box (as typing does: the first result is selected). */
    public void setQuery(String text) {
        if (field != null) {
            field.setText(text);
        }
        changed(text);
    }

    /** The results shown, best first. */
    public List<SearchEntry> results() {
        return shown;
    }

    /** How many more entries match than are shown. */
    public int more() {
        return more;
    }

    /** The selected result's index, or -1 when there are none. */
    public int selected() {
        return selected;
    }

    /** Moves the selection by {@code rows}, stopping at the first and last result. */
    public void move(int rows) {
        if (!shown.isEmpty()) {
            selected = Math.max(0, Math.min(shown.size() - 1, selected + rows));
        }
    }

    /**
     * Enter: runs the selected result and closes the search; a result that can't run toasts why and the search stays
     * open. Returns whether it ran.
     */
    public boolean runSelected() {
        if (selected < 0 || selected >= shown.size()) {
            return false;
        }
        SearchEntry entry = shown.get(selected);
        Availability availability = entry.availability();
        if (!availability.enabled()) {
            notify.accept(Notice.of(Notice.Level.INFO, availability.reasonKey(),
                    availability.args().toArray(String[]::new)));
            return false;
        }
        close();
        entry.run().run();
        return true;
    }

    private void changed(String text) {
        query = text;
        update();
    }

    private void update() {
        int rows = popups.get().theme().commandSearchRows;
        List<SearchEntry> ranked = CommandRanking.rank(query, entries, SearchEntry::name, SearchEntry::id, recent.get());
        shown = ranked.size() > rows ? List.copyOf(ranked.subList(0, rows)) : ranked;
        more = ranked.size() - shown.size();
        selected = shown.isEmpty() ? -1 : 0;
    }

    /** The search box: the arrow keys, Page Up/Down and Enter act on the results, everything else is typing. */
    private final class SearchField extends TextInput {
        SearchField() {
            super("", text -> changed(text));
        }

        @Override
        public boolean keyPressed(UiContext ctx, int keyCode, int scanCode, int modifiers) {
            int rows = ctx.theme().commandSearchRows;
            switch (keyCode) {
                case GLFW.GLFW_KEY_UP -> move(-1);
                case GLFW.GLFW_KEY_DOWN -> move(1);
                case GLFW.GLFW_KEY_PAGE_UP -> move(-rows);
                case GLFW.GLFW_KEY_PAGE_DOWN -> move(rows);
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> runSelected();
                default -> {
                    return super.keyPressed(ctx, keyCode, scanCode, modifiers);
                }
            }
            return true;
        }
    }

    /** The result rows under the box ("No matches" when there are none), and the count of more under them. */
    private final class Results extends Node {
        private final List<ResultRow> rows = new ArrayList<>();
        private int pressed = -1;

        @Override
        public List<Node> children() {
            syncRows();
            return List.copyOf(rows);
        }

        private void syncRows() {
            while (rows.size() < shown.size()) {
                rows.add(adopt(new ResultRow(rows.size())));
            }
            while (rows.size() > shown.size()) {
                release(rows.remove(rows.size() - 1));
            }
        }

        @Override
        protected Size measureContent(UiContext ctx, int maxWidth) {
            return new Size(0, (Math.max(1, shown.size()) + (more > 0 ? 1 : 0)) * ctx.theme().rowHeight);
        }

        @Override
        public void layout(UiContext ctx, Rect bounds) {
            super.layout(ctx, bounds);
            syncRows();
            int rowHeight = ctx.theme().rowHeight;
            for (int i = 0; i < rows.size(); i++) {
                rows.get(i).layout(ctx, new Rect(bounds.x(), bounds.y() + i * rowHeight, bounds.width(), rowHeight));
            }
        }

        @Override
        public void render(UiGraphics g, UiContext ctx) {
            if (shown.isEmpty()) {
                Theme theme = ctx.theme();
                int y = bounds.y() + (theme.rowHeight - ctx.text().lineHeight() + 1) / 2;
                g.text(tr.translate("sculptory.search.no_matches"), bounds.x() + theme.rowInset, y, theme.textDim,
                        theme.textShadow);
                return;
            }
            syncRows();
            for (ResultRow row : rows) {
                row.render(g, ctx);
            }
            if (more > 0) {
                Theme theme = ctx.theme();
                int y = bounds.y() + shown.size() * theme.rowHeight
                        + (theme.rowHeight - ctx.text().lineHeight() + 1) / 2;
                String cue = TextLayout.ellipsize(ctx.text(), tr.translate("sculptory.search.more", more),
                        bounds.width() - 2 * theme.rowInset);
                g.text(cue, bounds.x() + theme.rowInset, y, theme.textDim, theme.textShadow);
            }
        }

        private int rowAt(double y) {
            int rowHeight = rows.isEmpty() ? 1 : rows.get(0).bounds().height();
            int index = (int) Math.floor((y - bounds.y()) / Math.max(1, rowHeight));
            return index >= 0 && index < shown.size() ? index : -1;
        }

        @Override
        public boolean mouseDown(UiContext ctx, double x, double y, int button) {
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                return true;
            }
            pressed = rowAt(y);
            if (pressed >= 0) {
                selected = pressed;
            }
            return true;
        }

        @Override
        public void mouseUp(UiContext ctx, double x, double y, int button) {
            int index = pressed;
            pressed = -1;
            if (index >= 0 && index == rowAt(y) && bounds.contains(x, y)) {
                selected = index;
                runSelected();
            }
        }
    }

    /** One result: name, menu (dim) and key; its tooltip says why it can't run. */
    private final class ResultRow extends Node {
        private final int index;

        ResultRow(int index) {
            this.index = index;
        }

        private SearchEntry entry() {
            return shown.get(index);
        }

        @Override
        public String tooltip() {
            if (index >= shown.size()) {
                return null;
            }
            Availability availability = entry().availability();
            return availability.enabled() ? null : availability.reason(tr);
        }

        @Override
        protected Size measureContent(UiContext ctx, int maxWidth) {
            return Size.ZERO;
        }

        @Override
        public void mouseMove(UiContext ctx, double x, double y) {
            if (index < shown.size()) {
                selected = index;
            }
        }

        @Override
        public void render(UiGraphics g, UiContext ctx) {
            if (index >= shown.size()) {
                return;
            }
            Theme theme = ctx.theme();
            TextMeasure text = ctx.text();
            SearchEntry entry = entry();
            boolean enabled = entry.availability().enabled();
            if (index == selected) {
                g.fill(bounds, theme.menuHighlight);
            }
            int y = bounds.y() + (bounds.height() - text.lineHeight() + 1) / 2;
            int left = bounds.x() + theme.rowInset;
            int keyWidth = text.width(entry.keyText());
            int keyX = bounds.right() - theme.rowInset - keyWidth;
            if (keyWidth > 0) {
                g.text(entry.keyText(), keyX, y, enabled ? theme.textDim : theme.textDisabled, theme.textShadow);
            }
            int room = keyX - left - (keyWidth > 0 ? theme.commandSearchCategoryGap : 0);
            String name = TextLayout.ellipsize(text, entry.name(), room);
            g.text(name, left, y, enabled ? theme.text : theme.textDisabled, theme.textShadow);
            int categoryX = left + text.width(name) + theme.commandSearchCategoryGap;
            int categoryRoom = keyX - categoryX - (keyWidth > 0 ? theme.commandSearchCategoryGap : 0);
            if (categoryRoom > 0) {
                g.text(TextLayout.ellipsize(text, entry.category(), categoryRoom), categoryX, y,
                        enabled ? theme.textDim : theme.textDisabled, theme.textShadow);
            }
        }
    }
}
