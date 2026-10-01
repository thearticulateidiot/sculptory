package dev.sculptory.fabric.client.editor.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.hud.HistoryOfferToast;
import dev.sculptory.fabric.client.editor.mock.MockEditorSession;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.ListView;
import dev.sculptory.fabric.client.editor.ui.widget.ScrollPane;
import dev.sculptory.fabric.client.editor.windows.HistoryWindow.Entry;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.HistoryJump;
import dev.sculptory.fabric.client.session.HistoryOffer;
import dev.sculptory.fabric.client.session.SessionNotices;
import dev.sculptory.fabric.client.session.StrokeHandle;
import dev.sculptory.fabric.client.session.StrokeParams;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The History window's timeline, jumps and Undo anyway offer, and the Undo anyway toast, on the mock session. */
class HistoryWindowTest {
    private static final BrushSpec SPEC = new BrushSpec(BrushTool.RAISE, 4, 1f, Falloff.SMOOTH, Shape.CIRCLE, null,
            SurfaceMask.ANY, 0, 0, 1L);

    private final MockEditorSession session = new MockEditorSession();
    private final Optional<EditorSession> present = Optional.of(session);
    private HistoryWindow window;

    @BeforeEach
    void open() {
        for (ToolId tool : List.of(ToolId.RAISE, ToolId.LOWER, ToolId.SMOOTH)) {
            StrokeHandle stroke = session.beginStroke(tool, SPEC, StrokeParams.DEFAULT);
            stroke.dab(new Dab(0, 0, 1024, 0, 255));
            stroke.end();
        }
        window = new HistoryWindow(() -> present, () -> { }, () -> { }, "Ctrl+Z", "Ctrl+Y",
                Translator.KEYS);
    }

    private int indexOf(Entry.Kind kind, int index) {
        List<Entry> shown = window.shown();
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i).kind() == kind && shown.get(i).index() == index) return i;
        }
        throw new AssertionError(kind + " " + index + " not in " + shown);
    }

    /** Fixed-width text: 6 px per character, 9 px lines. */
    private static final TextMeasure TEXT = new TextMeasure() {
        @Override
        public int width(String text) {
            return text.length() * 6;
        }

        @Override
        public int lineHeight() {
            return 9;
        }

        @Override
        public String trimToWidth(String text, int maxWidth) {
            return text.substring(0, Math.max(0, Math.min(text.length(), maxWidth / 6)));
        }
    };

    private static <T extends Node> T find(Node node, Class<T> type) {
        if (type.isInstance(node)) return type.cast(node);
        for (Node child : node.children()) {
            T found = find(child, type);
            if (found != null) return found;
        }
        return null;
    }

    @Test
    void aShortWindowScrollsAndATallOneGivesTheListTheRoom() {
        UiContext ctx = new UiContext(TEXT, Theme.DARK);
        ScrollPane pane = (ScrollPane) window.node();
        ListView<?> list = find(pane, ListView.class);
        pane.layout(ctx, new Rect(0, 0, 180, 60));
        assertTrue(pane.scroll().isScrollable(), "the bottom is reached by scrolling, not cut off");
        pane.scroll().setOffset(pane.scroll().maxOffset());
        pane.layout(ctx, new Rect(0, 0, 180, 60));
        Node last = pane.content().children().get(pane.content().children().size() - 1);
        assertTrue(last.bounds().bottom() <= 60, "the memory line can be scrolled into view: " + last.bounds());

        int shortList = list.bounds().height();
        pane.layout(ctx, new Rect(0, 0, 180, 600));
        assertFalse(pane.scroll().isScrollable());
        assertTrue(list.bounds().height() > shortList + 300, "the list takes the tall window's room");
    }

    @Test
    void undoAndRedoWrapInANarrowWindow() {
        UiContext ctx = new UiContext(TEXT, Theme.DARK);
        ScrollPane pane = (ScrollPane) window.node();
        pane.layout(ctx, new Rect(0, 0, 400, 300));
        Button undo = find(pane, Button.class);
        Node row = undo.parent();
        Button redo = (Button) row.children().get(1);
        assertEquals(undo.bounds().y(), redo.bounds().y(), "side by side when there is room");
        int needed = TEXT.width(undo.text()) + TEXT.width(redo.text()) + 4 * Theme.DARK.controlPaddingX;
        pane.layout(ctx, new Rect(0, 0, needed, 300));
        assertTrue(redo.bounds().y() > undo.bounds().y(), "Redo goes below Undo rather than both being cut");
    }

    @Test
    void theTimelineShowsRedoableEntriesAboveNowAndUndoableOnesBelow() {
        List<Entry> entries = HistoryWindow.entries(List.of("C", "B", "A"), List.of("D", "E"));
        assertEquals(List.of(
                new Entry(Entry.Kind.REDO, 1, "E"),
                new Entry(Entry.Kind.REDO, 0, "D"),
                new Entry(Entry.Kind.NOW, 0, ""),
                new Entry(Entry.Kind.UNDO, 0, "C"),
                new Entry(Entry.Kind.UNDO, 1, "B"),
                new Entry(Entry.Kind.UNDO, 2, "A")), entries);
        assertEquals(Optional.of(2L), HistoryWindow.target(entries.get(0)), "redo D and E");
        assertEquals(Optional.of(1L), HistoryWindow.target(entries.get(1)));
        assertEquals(Optional.empty(), HistoryWindow.target(entries.get(2)));
        assertEquals(Optional.of(-1L), HistoryWindow.target(entries.get(3)), "undo C");
        assertEquals(Optional.of(-3L), HistoryWindow.target(entries.get(5)), "undo C, B and A");
        assertEquals(List.of(new Entry(Entry.Kind.NOW, 0, "")), HistoryWindow.entries(List.of(), List.of()));
    }

    @Test
    void clickingAnEntryJumpsThereAndTheListFollows() {
        assertEquals(HistoryWindow.entries(List.of("Smooth stroke", "Lower stroke", "Raise stroke"), List.of()),
                window.shown());
        window.activate(indexOf(Entry.Kind.UNDO, 1));
        assertEquals(List.of(-2L), session.jumps(), "Lower stroke is undone with everything newer");
        window.refresh();
        assertEquals(HistoryWindow.entries(List.of("Raise stroke"), List.of("Lower stroke", "Smooth stroke")),
                window.shown());

        window.activate(indexOf(Entry.Kind.REDO, 1));
        assertEquals(List.of(-2L, 2L), session.jumps());
        window.activate(indexOf(Entry.Kind.NOW, 0));
        assertEquals(2, session.jumps().size(), "the current position is not a jump");
        window.refresh();
        assertEquals(3, window.shown().stream().filter(e -> e.kind() == Entry.Kind.UNDO).count());
    }

    @Test
    void whileStepsRunTheListTakesNoClicks() {
        session.setHistoryBusy(true);
        window.refresh();
        assertFalse(window.acceptsClicks());
        window.activate(indexOf(Entry.Kind.UNDO, 0));
        assertTrue(session.jumps().isEmpty());
        session.setHistoryBusy(false);
        window.refresh();
        assertTrue(window.acceptsClicks());
        window.activate(indexOf(Entry.Kind.UNDO, 0));
        assertEquals(List.of(-1L), session.jumps());
    }

    @Test
    void aJumpShowsItsProgressAndStopDropsTheRest() {
        window.refresh();
        assertEquals(Optional.empty(), window.jumpShown());
        session.setHistoryBusy(true);
        session.setHistoryJump(new HistoryJump(true, 1, 4));
        window.refresh();
        assertEquals(Optional.of("sculptory.history.jump.undo[2,4]"), window.jumpShown(), "step 2 of 4 is running");
        window.stopButton().click();
        assertFalse(session.historyBusy(), "Stop drops the steps not sent yet");
        session.setHistoryJump(null);
        window.refresh();
        assertEquals(Optional.empty(), window.jumpShown());
    }

    @Test
    void theOfferAppearsWhenStepsKeptBlocksAndItsButtonAcceptsIt() {
        window.refresh();
        assertFalse(window.offerShown());
        session.undo();
        window.refresh();
        assertFalse(window.offerShown(), "nothing kept");
        session.setStepConflicts(3);
        session.undo();
        window.refresh();
        assertTrue(window.offerShown());
        HistoryOffer offer = session.historyOffer().orElseThrow();
        assertEquals("sculptory.history.offer.undo.steps[2,3]", HistoryWindow.offerText(offer, Translator.KEYS));
        assertEquals("sculptory.history.undo_anyway", window.offerButton().text());
        window.offerButton().click();
        assertEquals(List.of(offer), session.overwrites());
        window.refresh();
        assertFalse(window.offerShown(), "accepted");

        session.redo();
        window.refresh();
        assertEquals("sculptory.history.offer.redo[3]",
                HistoryWindow.offerText(session.historyOffer().orElseThrow(), Translator.KEYS));
        assertEquals("sculptory.history.redo_anyway", window.offerButton().text());
        session.setHistoryBusy(true);
        window.refresh();
        assertFalse(window.offerButton().isEnabled(), "not while steps run");
        window.offerButton().click();
        assertEquals(1, session.overwrites().size());
    }

    @Test
    void theUndoAnywayToastFollowsTheOfferAndCanBeClosed() {
        HistoryOfferToast toast = new HistoryOfferToast(Translator.KEYS, () -> present);
        assertFalse(toast.isShown());
        session.setStepConflicts(2);
        session.undo();
        toast.refresh();
        assertTrue(toast.isShown());
        assertEquals("sculptory.history.undo_anyway", toast.button().text());
        toast.dismiss();
        assertFalse(toast.isShown(), "closed");
        session.undo();
        toast.refresh();
        assertTrue(toast.isShown(), "another step: a new offer shows the toast again");
        toast.button().click();
        assertEquals(1, session.overwrites().size());
        assertEquals(2, session.overwrites().get(0).steps());
        toast.refresh();
        assertFalse(toast.isShown());
        assertEquals(0, toast.height());
    }

    /** Every key the history UI and the Undo anyway toasts use has English text. */
    @Test
    void everyHistoryKeyIsInEnUs() throws IOException {
        Pattern pattern = Pattern.compile("\"(sculptory\\.history\\.[a-z_.]+[a-z_])\"");
        Set<String> used = new TreeSet<>();
        for (String file : List.of("windows/HistoryWindow.java", "hud/HistoryOfferToast.java")) {
            Matcher matcher = pattern.matcher(Files.readString(Path.of("src/client/java/dev/sculptory/fabric/client/editor",
                    file)));
            while (matcher.find()) used.add(matcher.group(1));
        }
        for (String side : List.of("undo", "redo")) {
            used.add("sculptory.history.offer." + side);
            used.add("sculptory.history.offer." + side + ".steps");
            used.add("sculptory.notice.overwrite_refused." + side);
        }
        used.addAll(List.of(SessionNotices.OVERWRITE_DONE, SessionNotices.OVERWRITE_PROTECTED,
                SessionNotices.OVERWRITE_CANCELLED, SessionNotices.OVERWRITE_FAILED,
                "sculptory.notice.history_overwrite_stalled"));
        assertTrue(used.contains("sculptory.history.jump.stop"), "the window was scanned: " + used);
        JsonObject lang;
        try (InputStream in = getClass().getResourceAsStream("/assets/sculptory/lang/en_us.json")) {
            assertNotNull(in, "en_us.json is on the test classpath");
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        Set<String> missing = new TreeSet<>(used);
        missing.removeIf(lang::has);
        assertEquals(Set.of(), missing);
    }
}
