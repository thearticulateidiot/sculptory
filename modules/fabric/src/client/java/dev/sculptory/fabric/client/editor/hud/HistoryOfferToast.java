package dev.sculptory.fabric.client.editor.hud;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.ui.Insets;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.Row;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.windows.HistoryWindow;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.HistoryOffer;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The Undo anyway toast: at the top of the toast column while the last undo (or redo) steps kept blocks that changed
 * since ({@link EditorSession#historyOffer}), with an Undo anyway (Redo anyway) button and a close button. It stays
 * until the offer goes (the history changed otherwise, or the overwrite completed) or the player closes it; a new
 * offer (another step) shows it again. The History window offers the same while the offer lasts. Editor only: over
 * normal gameplay the mouse is not free.
 */
public final class HistoryOfferToast {
    public static final int WIDTH = ToastStack.WIDTH;
    private static final int WARNING = 0xFFE0B040;

    private final Supplier<Optional<EditorSession>> session;
    private final Translator tr;
    private final Label text = Label.of("");
    private final Button anyway;
    private final Plate plate;
    private long dismissed = Long.MIN_VALUE;
    private HistoryOffer offer;

    public HistoryOfferToast(Translator translator, Supplier<Optional<EditorSession>> session) {
        this.tr = Objects.requireNonNull(translator);
        this.session = Objects.requireNonNull(session);
        text.setWrap(true);
        anyway = new Button("", this::accept);
        anyway.setStyle(Button.Style.PRIMARY);
        anyway.setGrow(1);
        Button close = new Button("×", this::dismiss);
        close.setStyle(Button.Style.FLAT);
        close.setTooltip(tr.translate("sculptory.history.offer.dismiss"));
        Row buttons = Row.of(anyway, close);
        buttons.setGap(4);
        Column column = Column.of(text, buttons);
        column.setGap(4);
        column.setFixedWidth(WIDTH - 13);
        plate = new Plate(column);
        refresh();
    }

    public Node node() {
        return plate;
    }

    /** Follows the session's offer; call every frame. */
    public void refresh() {
        Optional<EditorSession> current = session.get();
        offer = current.flatMap(EditorSession::historyOffer).orElse(null);
        if (offer == null) return;
        text.setText(HistoryWindow.offerText(offer, tr));
        anyway.setText(offer.running() ? tr.translate("sculptory.history.offer.running")
                : HistoryWindow.anywayText(offer, tr));
        anyway.setTooltip(tr.translate("sculptory.history.anyway.tooltip"));
        anyway.setEnabled(!offer.running() && !current.get().historyBusy());
    }

    /** Whether the toast is up: an offer the player has not closed. */
    public boolean isShown() {
        return offer != null && offer.id() != dismissed;
    }

    /** The height the toast takes in the toast column, spacing included (0 when it is not up). */
    public int height() {
        return isShown() && plate.isVisible() ? plate.bounds().height() + 3 : 0;
    }

    /** The button, for tests and the router. */
    public Button button() {
        return anyway;
    }

    /** Hides this offer (a new one shows the toast again); the History window still offers it. */
    public void dismiss() {
        if (offer != null) dismissed = offer.id();
    }

    private void accept() {
        session.get().ifPresent(EditorSession::acceptHistoryOffer);
    }

    /** The toast's plate, drawn like {@link ToastStack}'s: background, warning bar, border. */
    private static final class Plate extends Node {
        private final Node child;
        private final Insets padding = new Insets(8, 5, 5, 5);

        Plate(Node child) {
            this.child = adopt(child);
        }

        @Override
        public List<Node> children() {
            return List.of(child);
        }

        @Override
        protected Size measureContent(UiContext ctx, int maxWidth) {
            Size size = child.measure(ctx, WIDTH - padding.horizontal());
            return new Size(size.width() + padding.horizontal(),
                    size.height() + padding.vertical());
        }

        @Override
        public void layout(UiContext ctx, Rect bounds) {
            super.layout(ctx, bounds);
            child.layout(ctx, bounds.inset(padding));
        }

        @Override
        public void render(UiGraphics g, UiContext ctx) {
            g.dropShadow(bounds, ctx.theme().windowShadow);
            g.fill(bounds, ctx.theme().popupBackground);
            g.fill(bounds.x(), bounds.y(), 3, bounds.height(), WARNING);
            g.outline(bounds, ctx.theme().popupBorder);
            renderChildren(g, ctx);
        }
    }
}
