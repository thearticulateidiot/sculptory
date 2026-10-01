package dev.sculptory.fabric.client.editor.windows;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.hud.NotificationLog;
import dev.sculptory.fabric.client.editor.hud.ToastStack;
import dev.sculptory.fabric.client.editor.ui.Align;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.Row;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.ScrollPane;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * The Notifications window (View > Notifications): the last {@value NotificationLog#CAPACITY} toast messages, newest at
 * the top, each with its level's colour bar, the time it was shown (hh:mm:ss) and its whole text (a message repeated
 * straight after itself shows "×n"), and a Clear button. Call {@link #refresh()} every frame while it is open; the rows
 * are rebuilt only when the log changed.
 */
public final class NotificationsWindow {
    public static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final NotificationLog log;
    private final Translator tr;
    private final Label count = Label.dim("");
    private final Button clear;
    private final Column rows = new Column();
    private final Node root;
    private int shownVersion = -1;

    public NotificationsWindow(NotificationLog log, Translator translator) {
        this.log = Objects.requireNonNull(log);
        this.tr = Objects.requireNonNull(translator);
        clear = new Button(tr.translate("sculptory.notifications.clear"), this::clear);
        clear.setTooltip(tr.translate("sculptory.notifications.clear.tooltip"));
        count.setGrow(1);
        count.setTooltip(tr.translate("sculptory.notifications.count.tooltip", Integer.toString(NotificationLog.CAPACITY)));
        Row header = Row.of(count, clear);
        header.setCrossAlign(Align.CENTER);
        rows.setGap(3);
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setGrow(1);
        Column column = Column.of(header, scroll);
        column.setGap(4);
        root = column;
        refresh();
    }

    public Node node() {
        return root;
    }

    public Button clearButton() {
        return clear;
    }

    /** The messages listed now, newest first. */
    public List<NotificationLog.Entry> shown() {
        return rows.children().stream()
                .filter(EntryRow.class::isInstance)
                .map(node -> ((EntryRow) node).entry)
                .toList();
    }

    /** Shows the log again if it changed since the last time. */
    public void refresh() {
        if (shownVersion == log.version()) {
            return;
        }
        shownVersion = log.version();
        List<NotificationLog.Entry> entries = log.entries();
        rows.clear();
        if (entries.isEmpty()) {
            Label empty = Label.dim(tr.translate("sculptory.notifications.empty"));
            empty.setWrap(true);
            rows.add(empty);
        }
        for (NotificationLog.Entry entry : entries) {
            rows.add(new EntryRow(entry));
        }
        count.setText(tr.translate("sculptory.notifications.count", Integer.toString(entries.size())));
        clear.setEnabled(!entries.isEmpty());
    }

    private void clear() {
        log.clear();
        refresh();
    }

    /** One message: the level bar, the time (and "×n") dim, then the text wrapped to the width. */
    private final class EntryRow extends Node {
        private final NotificationLog.Entry entry;
        private final String stamp;

        EntryRow(NotificationLog.Entry entry) {
            this.entry = entry;
            String time = TIME.format(entry.time());
            this.stamp = entry.count() > 1 ? tr.translate("sculptory.notifications.repeated", time,
                    Integer.toString(entry.count())) : time;
        }

        private int textLeft(UiContext ctx) {
            return ctx.theme().noticeBarWidth + ctx.theme().gap;
        }

        private List<String> lines(UiContext ctx, int width) {
            return TextLayout.wrap(ctx.text(), entry.text(), Math.max(1, width - textLeft(ctx)));
        }

        @Override
        protected Size measureContent(UiContext ctx, int maxWidth) {
            int width = maxWidth == Integer.MAX_VALUE ? 200 : maxWidth;
            TextMeasure text = ctx.text();
            int lines = 1 + lines(ctx, width).size();
            return new Size(width, lines * text.lineHeight() + (lines - 1) * ctx.theme().lineSpacing);
        }

        @Override
        public void render(UiGraphics g, UiContext ctx) {
            Theme theme = ctx.theme();
            TextMeasure text = ctx.text();
            g.fill(bounds.x(), bounds.y(), theme.noticeBarWidth, bounds.height(), ToastStack.levelColor(entry.level(), theme));
            int x = bounds.x() + textLeft(ctx);
            int y = bounds.y();
            g.text(stamp, x, y, theme.textDim, theme.textShadow);
            for (String line : lines(ctx, bounds.width())) {
                y += text.lineHeight() + theme.lineSpacing;
                g.text(line, x, y, theme.text, theme.textShadow);
            }
        }
    }
}
