package dev.sculptory.fabric.client.editor.windows;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.FlowRow;
import dev.sculptory.fabric.client.editor.ui.layout.Row;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.ListView;
import dev.sculptory.fabric.client.editor.ui.widget.ProgressBar;
import dev.sculptory.fabric.client.editor.ui.widget.ScrollPane;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.HistoryJump;
import dev.sculptory.fabric.client.session.HistoryMirror;
import dev.sculptory.fabric.client.session.HistoryOffer;
import dev.sculptory.fabric.client.session.SessionNotices;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The History window: Undo and Redo buttons, then the history as a timeline around the current position ("Now"): the
 * redoable entries above it (the nearest just above), the undoable ones below (the newest just below), as the server's
 * labels come. Clicking an entry jumps there ({@link EditorSession#jumpTo}): an undoable entry is undone together with
 * every newer one, a redoable entry is redone together with every nearer one. While undo or redo steps run the list
 * takes no clicks (a click would retarget a jump the player cannot see yet); a jump shows its progress and can be
 * stopped after the step running. When the last undo (or redo) steps kept blocks that changed since, the window offers
 * Undo anyway (Redo anyway, {@link EditorSession#acceptHistoryOffer}). Last, the memory the history uses. The content
 * scrolls when the window is short; the list takes the room of a tall one. Call {@link #refresh()} every frame while it
 * is open.
 */
public final class HistoryWindow {
    /** One row: a redoable entry (index into the redo labels), the current position, or an undoable entry. */
    public record Entry(Kind kind, int index, String label) {
        public enum Kind { REDO, NOW, UNDO }

        public Entry {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(label);
        }
    }

    private final Supplier<Optional<EditorSession>> session;
    private final Translator tr;
    private final Button undo;
    private final Button redo;
    private final ListView<Entry> list;
    private final Label jumpLabel = Label.dim("");
    private final ProgressBar jumpBar = new ProgressBar();
    private final Button stop;
    private final Column jumpBox;
    private final Label offerLabel = Label.of("");
    private final Button anyway;
    private final Column offerBox;
    private final Label memory = Label.dim("");
    private final Node root;
    private List<Entry> shown = List.of();
    private boolean busy;

    public HistoryWindow(Supplier<Optional<EditorSession>> session, Runnable onUndo, Runnable onRedo,
            String undoKeys, String redoKeys, Translator translator) {
        this.session = Objects.requireNonNull(session);
        this.tr = Objects.requireNonNull(translator);
        undo = new Button(tr.translate("sculptory.history.undo"), onUndo);
        undo.setTooltip(undoKeys);
        redo = new Button(tr.translate("sculptory.history.redo"), onRedo);
        redo.setTooltip(redoKeys);
        undo.setGrow(1);
        redo.setGrow(1);
        list = new ListView<>(List.of(), (entry, index) -> row(entry));
        list.setActivateOnClick(true);
        list.setOnActivate(this::activate);
        list.setEmptyText(tr.translate("sculptory.history.empty"));
        list.setPreferredRows(6);
        list.setGrow(1);
        stop = new Button(tr.translate("sculptory.history.jump.stop"), this::stop);
        stop.setTooltip(tr.translate("sculptory.history.jump.stop.tooltip"));
        jumpBar.setGrow(1);
        Row jumpRow = Row.of(jumpBar, stop);
        jumpRow.setGap(4);
        jumpBox = Column.of(jumpLabel, jumpRow);
        jumpBox.setGap(2);
        offerLabel.setWrap(true);
        offerLabel.setColor(0xFFE0B040);
        anyway = new Button("", this::acceptOffer);
        anyway.setStyle(Button.Style.PRIMARY);
        anyway.setGrow(1);
        offerBox = Column.of(offerLabel, Row.of(anyway));
        offerBox.setGap(3);
        Column column = Column.of(FlowRow.of(undo, redo), list, jumpBox, offerBox, memory);
        column.setGap(4);
        // A short window scrolls; a tall one gives the list the room.
        root = new ScrollPane(column);
        refresh();
    }

    public Node node() {
        return root;
    }

    /**
     * The rows for the given labels (undoable newest first, redoable nearest first): the redoable entries furthest
     * first, the current position, then the undoable entries newest first.
     */
    public static List<Entry> entries(List<String> undoLabels, List<String> redoLabels) {
        List<Entry> entries = new ArrayList<>(undoLabels.size() + redoLabels.size() + 1);
        for (int i = redoLabels.size() - 1; i >= 0; i--) entries.add(new Entry(Entry.Kind.REDO, i, redoLabels.get(i)));
        entries.add(new Entry(Entry.Kind.NOW, 0, ""));
        for (int i = 0; i < undoLabels.size(); i++) entries.add(new Entry(Entry.Kind.UNDO, i, undoLabels.get(i)));
        return List.copyOf(entries);
    }

    /** The {@link EditorSession#jumpTo} target of a row, or empty for the current position. */
    public static Optional<Long> target(Entry entry) {
        return switch (entry.kind()) {
            case REDO -> Optional.of(HistoryMirror.redoTarget(entry.index()));
            case UNDO -> Optional.of(HistoryMirror.undoTarget(entry.index()));
            case NOW -> Optional.empty();
        };
    }

    /** The rows shown. */
    public List<Entry> shown() {
        return shown;
    }

    /** Clicks row {@code index}, as the list does: jumps there, unless steps are running. */
    public void activate(int index) {
        list.setSelectedIndex(-1);
        if (busy || index < 0 || index >= shown.size()) return;
        Optional<Long> target = target(shown.get(index));
        Optional<EditorSession> current = session.get();
        if (target.isEmpty() || current.isEmpty() || current.get().historyBusy()) return;
        current.get().jumpTo(target.get());
        busy = current.get().historyBusy();
        list.setEnabled(!busy);
    }

    /** Whether the list takes clicks now (no undo or redo steps running). */
    public boolean acceptsClicks() {
        return list.isEnabled();
    }

    /** The Undo anyway / Redo anyway button; shown only with an offer. */
    public Button offerButton() {
        return anyway;
    }

    /** Whether the offer is shown. */
    public boolean offerShown() {
        return offerBox.isVisible();
    }

    /** Whether a jump's progress is shown, and its text. */
    public Optional<String> jumpShown() {
        return jumpBox.isVisible() ? Optional.of(jumpLabel.text()) : Optional.empty();
    }

    /** The jump's Stop button. */
    public Button stopButton() {
        return stop;
    }

    public void refresh() {
        Optional<EditorSession> current = session.get();
        Optional<HistoryMirror> history = current.map(EditorSession::history);
        boolean canUndo = history.map(HistoryMirror::canUndo).orElse(false);
        boolean canRedo = history.map(HistoryMirror::canRedo).orElse(false);
        undo.setEnabled(canUndo);
        redo.setEnabled(canRedo);
        undo.setTooltip(canUndo ? tr.translate("sculptory.history.undo_label", history.get().undoLabel())
                : tr.translate("sculptory.history.nothing_to_undo"));
        redo.setTooltip(canRedo ? tr.translate("sculptory.history.redo_label", history.get().redoLabel())
                : tr.translate("sculptory.history.nothing_to_redo"));

        List<Entry> entries = entries(history.map(HistoryMirror::undoLabels).orElse(List.of()),
                history.map(HistoryMirror::redoLabels).orElse(List.of()));
        if (current.isEmpty()) entries = List.of();
        if (!entries.equals(shown)) {
            shown = entries;
            list.setItems(entries);
            int now = nowIndex(entries);
            if (now >= 0) list.scrollToIndex(now);
        }
        busy = current.map(EditorSession::historyBusy).orElse(false);
        list.setEnabled(!busy);

        Optional<HistoryJump> jump = current.flatMap(EditorSession::historyJump);
        jumpBox.setVisible(jump.isPresent());
        jump.ifPresent(j -> {
            jumpLabel.setText(tr.translate(j.undo() ? "sculptory.history.jump.undo" : "sculptory.history.jump.redo",
                    Integer.toString(j.done() + 1 > j.total() ? j.total() : j.done() + 1), Integer.toString(j.total())));
            jumpBar.setProgress(j.done() / (double) j.total());
        });

        Optional<HistoryOffer> offer = current.flatMap(EditorSession::historyOffer);
        offerBox.setVisible(offer.isPresent());
        offer.ifPresent(o -> {
            offerLabel.setText(offerText(o, tr));
            anyway.setText(o.running() ? tr.translate("sculptory.history.offer.running") : anywayText(o, tr));
            anyway.setTooltip(tr.translate("sculptory.history.anyway.tooltip"));
            anyway.setEnabled(!o.running() && !busy);
        });

        long kilobytes = history.map(mirror -> (mirror.bytes() + 1023) / 1024).orElse(0L);
        memory.setText(tr.translate("sculptory.history.memory", Long.toString(kilobytes)));
    }

    /** "Undo kept 12 blocks changed since the edit", "3 undos kept 40 blocks changed since their edits". */
    public static String offerText(HistoryOffer offer, Translator tr) {
        String key = "sculptory.history.offer." + (offer.redo() ? "redo" : "undo");
        String blocks = SessionNotices.count(offer.skipped());
        return offer.steps() == 1 ? tr.translate(key, blocks)
                : tr.translate(key + ".steps", Integer.toString(offer.steps()), blocks);
    }

    /** "Undo anyway" or "Redo anyway". */
    public static String anywayText(HistoryOffer offer, Translator tr) {
        return tr.translate(offer.redo() ? "sculptory.history.redo_anyway" : "sculptory.history.undo_anyway");
    }

    private static int nowIndex(List<Entry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).kind() == Entry.Kind.NOW) return i;
        }
        return -1;
    }

    private Node row(Entry entry) {
        return switch (entry.kind()) {
            case NOW -> {
                Label label = Label.of(tr.translate("sculptory.history.now"));
                label.setColor(Theme.DARK.accentHover);
                yield label;
            }
            case UNDO -> {
                Label label = Label.of(entry.label());
                label.setTooltip(tr.translate(entry.index() == 0 ? "sculptory.history.row.undo.one"
                        : "sculptory.history.row.undo", entry.label(), Integer.toString(entry.index() + 1)));
                yield label;
            }
            case REDO -> {
                Label label = Label.dim(entry.label());
                label.setTooltip(tr.translate(entry.index() == 0 ? "sculptory.history.row.redo.one"
                        : "sculptory.history.row.redo", entry.label(), Integer.toString(entry.index() + 1)));
                yield label;
            }
        };
    }

    private void stop() {
        session.get().ifPresent(EditorSession::dropQueuedHistorySteps);
    }

    private void acceptOffer() {
        session.get().ifPresent(EditorSession::acceptHistoryOffer);
    }
}
