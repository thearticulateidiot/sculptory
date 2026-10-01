package dev.sculptory.fabric.client.editor.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.input.KeyChord;
import dev.sculptory.fabric.client.editor.input.KeyListing;
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.RecordingGraphics;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.widget.KeyCaptureButton;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.SectionHeading;
import dev.sculptory.fabric.client.editor.ui.widget.TextInput;
import dev.sculptory.fabric.client.editor.ui.window.Corner;
import dev.sculptory.fabric.client.editor.ui.window.WindowManager;
import dev.sculptory.fabric.client.editor.ui.window.WindowSpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/** The Keys window's rebinding, driven through the window manager as the editor drives it. */
class KeysWindowTest {
    private static final String FILE = "config/sculptory/editor-keys.json";
    private static final String LISTENING = "sculptory.keys.listening";
    private static final int LEFT = GLFW.GLFW_MOUSE_BUTTON_LEFT;
    private static final int MIDDLE = GLFW.GLFW_MOUSE_BUTTON_MIDDLE;
    /** A tall screen, so every row of the window is laid out and clickable. */
    private static final int W = 1200;
    private static final int H = 4000;
    /** A point over the world, far from the window. */
    private static final double FAR = 1100;
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

    /** Records saves, toasts and confirmations; a confirmation waits until the test answers it. */
    private class FakeHost implements KeysWindow.Host {
        boolean saveOk = true;
        final List<String> saved = new ArrayList<>();
        final List<String> toasts = new ArrayList<>();
        final List<String> questions = new ArrayList<>();
        Runnable pending;
        int changes;

        @Override
        public boolean save(EditorKeymap keymap) {
            if (saveOk) {
                saved.add(keymap.toJson());
            }
            return saveOk;
        }

        @Override
        public void toast(String message) {
            toasts.add(message);
        }

        @Override
        public void confirm(String message, Runnable onConfirm) {
            questions.add(message);
            pending = onConfirm;
        }

        @Override
        public void changed() {
            changes++;
        }
    }

    private final EditorKeymap keymap = EditorKeymap.defaults();
    private final FakeHost host = new FakeHost();
    private final WindowManager windows = new WindowManager(TEXT, Theme.DARK, UnaryOperator.identity());
    private KeysWindow window;

    @BeforeEach
    void open() {
        window = new KeysWindow(keymap, Translator.KEYS, FILE, host);
        windows.register(WindowSpec.builder("keys", "title.keys", window::node)
                .anchor(Corner.TOP_LEFT, 10, 10).size(500, H - 40).minSize(100, 100).build());
        windows.layout(W, H);
    }

    private void click(Node node) {
        Rect bounds = node.bounds();
        assertTrue(bounds.width() > 0 && bounds.height() > 0, "laid out: " + node);
        double x = bounds.x() + bounds.width() / 2.0;
        double y = bounds.y() + bounds.height() / 2.0;
        assertTrue(windows.mouseDown(x, y, LEFT, 0), "the press lands on the window");
        windows.mouseUp(x, y, LEFT);
    }

    private boolean key(int code, int modifiers) {
        return windows.keyPressed(code, 0, modifiers);
    }

    private static List<KeyChord> chords(String... texts) {
        List<KeyChord> chords = new ArrayList<>();
        for (String text : texts) {
            chords.add(KeyChord.parse(text));
        }
        return chords;
    }

    private KeyCaptureButton chordButton(KeyAction action, int index) {
        return window.controls(action).chords().get(index);
    }

    private String lastSaved() {
        assertFalse(host.saved.isEmpty(), "saved");
        return host.saved.get(host.saved.size() - 1);
    }

    // ---- Search ----

    private static List<Node> subtree(Node node) {
        List<Node> all = new ArrayList<>();
        all.add(node);
        for (Node child : node.children()) {
            all.addAll(subtree(child));
        }
        return all;
    }

    /** The texts of the labels shown in the window. */
    private List<String> labels() {
        return subtree(window.node()).stream().filter(Label.class::isInstance).map(node -> ((Label) node).text())
                .toList();
    }

    @Test
    void actionsAreListedGroupByGroupEachGroupOnce() {
        List<KeyAction> order = KeyListing.inGroupOrder();
        assertEquals(KeyAction.values().length, order.size());
        for (int i = 1; i < order.size(); i++) {
            assertTrue(order.get(i - 1).group().ordinal() <= order.get(i).group().ordinal(),
                    order.get(i - 1) + " before " + order.get(i));
        }
        List<String> headings = labels().stream().filter(text -> text.startsWith("sculptory.keys.group.")).toList();
        assertEquals(KeyAction.Group.values().length, headings.size(), "one heading per group: " + headings);
    }

    @Test
    void groupHeadingsAreBandsWithSpaceAboveAllButTheFirst() {
        List<SectionHeading> headings = subtree(window.node()).stream().filter(SectionHeading.class::isInstance)
                .map(SectionHeading.class::cast).toList();
        assertEquals(KeyAction.Group.values().length, headings.size());
        assertEquals(0, headings.get(0).spaceAbove(), "the first heading starts the list");
        for (SectionHeading heading : headings.subList(1, headings.size())) {
            assertEquals(Theme.DARK.headingSpaceAbove, heading.spaceAbove(), heading.text());
        }
        SectionHeading second = headings.get(1);
        RecordingGraphics g = new RecordingGraphics();
        second.render(g, windows.context());
        Rect band = second.band(windows.context());
        assertEquals(List.of(band, new Rect(band.x(), band.y(), Theme.DARK.headingStripWidth, band.height())),
                g.filledRects(), "a band as wide as the list, an accent strip at its left end");
        assertEquals(second.bounds().width(), band.width());
        assertEquals(second.bounds().y() + Theme.DARK.headingSpaceAbove, band.y(), "below the space");
        Rect title = g.textAnchor(second.text());
        assertTrue(band.contains(title.x(), title.y()), "the title on the band");
        List<Node> list = second.parent().children();
        Node rowAbove = list.get(list.indexOf(second) - 1);
        assertTrue(band.y() - rowAbove.bounds().bottom() >= Theme.DARK.headingSpaceAbove, "space above the band");
    }

    @Test
    void headingBandsStandOutFromTheWindowAndTheKeySheetMoreThanAToolSettingsHeaderFromItsWindow() {
        Theme theme = Theme.DARK;
        int toolSettings = lighter(theme.sectionHeader, theme.windowBackground);
        for (int background : new int[] {theme.windowBackground, theme.popupBackground}) {
            int heading = lighter(theme.headingBand, background);
            assertTrue(heading > toolSettings, "a heading band (" + heading + " lighter) stands out more than a"
                    + " Tool Settings header on its window (" + toolSettings + ")");
        }
    }

    /** How much lighter {@code color} is than {@code background}: the mean of the channel differences. */
    private static int lighter(int color, int background) {
        int sum = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            sum += (color >> shift & 0xFF) - (background >> shift & 0xFF);
        }
        return sum / 3;
    }

    @Test
    void typingAKeyShowsOnlyTheActionsBoundToItAndTheirGroups() {
        window.search("ctrl+z");
        windows.layout(W, H);
        assertTrue(window.shownActions().contains(KeyAction.UNDO));
        assertFalse(window.shownActions().contains(KeyAction.REDO), "Ctrl+Y and Ctrl+Shift+Z don't match");
        assertFalse(window.shownActions().contains(KeyAction.DESELECT));
        List<String> groups = labels().stream().filter(text -> text.startsWith("sculptory.keys.group.")).toList();
        assertEquals(List.of("sculptory.keys.group.history"), groups, "groups with no match are hidden");

        window.search("f1");
        assertEquals(List.of(KeyAction.HELP), window.shownActions());
        window.search("");
        assertEquals(KeyAction.values().length, window.shownActions().size(), "an empty box shows every action");
    }

    @Test
    void typingANameShowsTheActionsWhoseNameMatches() {
        window.search("deselect");
        assertEquals(List.of(KeyAction.DESELECT), window.shownActions());
    }

    @Test
    void anEmptyResultSaysSo() {
        window.search("zzzz");
        assertEquals(List.of(), window.shownActions());
        assertTrue(labels().contains("sculptory.keys.no_match"));
        assertTrue(window.resetAllButton() != null, "Reset all keys stays");
    }

    @Test
    void rebindingWorksWhileFilteredAndTheFilterStays() {
        window.search("ctrl+z");
        windows.layout(W, H);
        KeyCaptureButton undo = chordButton(KeyAction.UNDO, 0);
        click(undo);
        assertTrue(key(GLFW.GLFW_KEY_U, Modifiers.CONTROL));
        assertEquals(chords("ctrl+u"), keymap.chords(KeyAction.UNDO));
        assertEquals("ctrl+z", window.searchInput().text(), "the search box keeps its text");
        assertFalse(window.shownActions().contains(KeyAction.UNDO), "Undo no longer matches Ctrl+Z");
    }

    @Test
    void paletteSlotsAreNamedAfterTheirToolsAndFoundByThem() {
        FakeHost named = new FakeHost() {
            @Override
            public java.util.Optional<String> toolName(int slot) {
                return slot == 2 ? java.util.Optional.of("Raise") : java.util.Optional.empty();
            }
        };
        KeysWindow withTools = new KeysWindow(keymap, Translator.KEYS, FILE, named);
        assertEquals("sculptory.keys.slot[2,Raise]", withTools.actionName(KeyAction.TOOL_2));
        assertEquals("sculptory.key.tool_3", withTools.actionName(KeyAction.TOOL_3), "no tool known: the label");
        assertEquals("sculptory.key.undo", withTools.actionName(KeyAction.UNDO));
        withTools.search("raise");
        assertEquals(List.of(KeyAction.TOOL_2), withTools.shownActions());
    }

    @Test
    void eachActionIsOneLineWithResetOnlyWhenItDiffersFromItsDefaults() {
        KeysWindow.Controls undo = window.controls(KeyAction.UNDO);
        assertFalse(undo.reset().isVisible(), "at its defaults: no Reset");
        int line = undo.chords().get(0).bounds().y();
        assertEquals(line, undo.add().bounds().y(), "the chords and + on the same line");
        Node name = undo.chords().get(0).parent().children().get(0);
        assertTrue(name.bounds().y() >= line && name.bounds().bottom() <= undo.chords().get(0).bounds().bottom(),
                "the name too, centred on the line");

        click(undo.chords().get(0));
        assertTrue(key(GLFW.GLFW_KEY_U, Modifiers.CONTROL));
        windows.layout(W, H);
        KeysWindow.Controls changed = window.controls(KeyAction.UNDO);
        assertTrue(changed.reset().isVisible());
        Node row = changed.chords().get(0).parent();
        assertSame(row, changed.reset().parent(), "Reset is on the action's line...");
        assertSame(changed.reset(), row.children().get(row.children().size() - 1), "...at its end");
    }

    @Test
    void aNarrowWindowWrapsAnActionsLine() {
        windows.restore(new dev.sculptory.fabric.client.editor.ui.window.LayoutState(List.of(
                new dev.sculptory.fabric.client.editor.ui.window.LayoutState.WindowState("keys", true, false,
                        Corner.TOP_LEFT, 10, 10, 150, H - 40))));
        windows.layout(W, H);
        KeysWindow.Controls redo = window.controls(KeyAction.REDO);
        Node name = redo.chords().get(0).parent().children().get(0);
        assertTrue(redo.chords().get(0).bounds().y() > name.bounds().y(), "the chords go below the name");
        Rect content = window.node().bounds();
        assertTrue(redo.add().bounds().right() <= content.right(), "nothing runs past the window");
    }

    @Test
    void theSearchBoxSitsAboveTheScrollingRows() {
        TextInput search = window.searchInput();
        assertTrue(search.bounds().width() > 0, "laid out");
        KeyCaptureButton undo = chordButton(KeyAction.UNDO, 0);
        assertTrue(search.bounds().bottom() <= undo.bounds().y() || undo.bounds().y() < 0, "above the rows");

        windows.restore(new dev.sculptory.fabric.client.editor.ui.window.LayoutState(List.of(
                new dev.sculptory.fabric.client.editor.ui.window.LayoutState.WindowState("keys", true, false,
                        Corner.TOP_LEFT, 10, 10, 250, 200))));
        windows.layout(W, H);
        assertEquals(Theme.DARK.controlHeight, search.bounds().height(), "a short window doesn't squeeze it");
    }

    // ---- Listening ----

    @Test
    void clickingAChordListensAndTheNextKeyPressBecomesIt() {
        KeyCaptureButton undo = chordButton(KeyAction.UNDO, 0);
        assertEquals("Ctrl+Z", undo.text());
        click(undo);
        assertTrue(undo.isListening());
        assertEquals(LISTENING, undo.text());
        assertTrue(windows.isCapturingInput(), "the router sends every press here now");

        assertTrue(key(GLFW.GLFW_KEY_U, Modifiers.CONTROL));
        assertEquals(chords("ctrl+u"), keymap.chords(KeyAction.UNDO), "applied to the live keymap");
        assertEquals(keymap.toJson(), lastSaved(), "and saved");
        assertEquals(1, host.changes);
        assertFalse(windows.isCapturingInput());
        assertEquals("Ctrl+U", chordButton(KeyAction.UNDO, 0).text(), "the rows show the new key");
        assertTrue(host.toasts.isEmpty());
    }

    @Test
    void aModifierKeyAloneIsWaitedThrough() {
        click(chordButton(KeyAction.UNDO, 0));
        assertTrue(key(GLFW.GLFW_KEY_LEFT_CONTROL, Modifiers.CONTROL));
        assertTrue(windows.isCapturingInput(), "still listening");
        assertTrue(key(GLFW.GLFW_KEY_LEFT_SHIFT, Modifiers.CONTROL | Modifiers.SHIFT));
        assertTrue(key(GLFW.GLFW_KEY_Z, Modifiers.CONTROL | Modifiers.SHIFT));
        assertEquals(chords("ctrl+shift+z"), keymap.chords(KeyAction.UNDO));
    }

    @Test
    void escKeepsTheOldChord() {
        KeyCaptureButton undo = chordButton(KeyAction.UNDO, 0);
        click(undo);
        assertTrue(key(GLFW.GLFW_KEY_ESCAPE, 0), "Esc is consumed by the window");
        assertFalse(undo.isListening());
        assertEquals("Ctrl+Z", undo.text());
        assertEquals(KeyAction.UNDO.defaultChords(), keymap.chords(KeyAction.UNDO));
        assertTrue(host.saved.isEmpty(), "nothing changed, nothing saved");
        assertFalse(windows.isCapturingInput());
    }

    @Test
    void backspaceOrDeleteRemovesTheChord() {
        click(chordButton(KeyAction.REDO, 0));
        assertTrue(key(GLFW.GLFW_KEY_BACKSPACE, 0));
        assertEquals(chords("ctrl+shift+z"), keymap.chords(KeyAction.REDO));
        click(chordButton(KeyAction.REDO, 0));
        assertTrue(key(GLFW.GLFW_KEY_DELETE, 0));
        assertEquals(List.of(), keymap.chords(KeyAction.REDO), "the last chord gone: unbound");
        assertEquals(0, window.controls(KeyAction.REDO).chords().size());
        assertNotNull(window.controls(KeyAction.REDO).add(), "a + offers a new key");
        assertTrue(lastSaved().contains("\"redo\": []"), "an empty list unbinds, as a hand edit would");
    }

    @Test
    void aLeftClickAnywhereCancelsAndIsNotATool() {
        KeyCaptureButton undo = chordButton(KeyAction.UNDO, 0);
        click(undo);
        assertTrue(windows.mouseDown(FAR, FAR, LEFT, 0), "consumed by the listening control, not the world");
        windows.mouseUp(FAR, FAR, LEFT);
        assertFalse(undo.isListening());
        assertEquals(KeyAction.UNDO.defaultChords(), keymap.chords(KeyAction.UNDO));
        assertFalse(windows.isCapturingInput());
    }

    @Test
    void losingFocusCancels() {
        KeyCaptureButton undo = chordButton(KeyAction.UNDO, 0);
        click(undo);
        windows.context().clearFocus();
        assertFalse(undo.isListening());
        assertEquals("Ctrl+Z", undo.text());
    }

    @Test
    void enterOnAFocusedChordListensToo() {
        KeyCaptureButton undo = chordButton(KeyAction.UNDO, 0);
        windows.context().setFocus(undo);
        assertTrue(key(GLFW.GLFW_KEY_ENTER, 0));
        assertTrue(undo.isListening());
        assertTrue(key(GLFW.GLFW_KEY_ENTER, 0), "the next Enter is the chord, not a click");
        assertEquals(chords("enter"), keymap.chords(KeyAction.UNDO));
    }

    // ---- Mouse and scroll chords ----

    @Test
    void mouseButtonsBindOnlyWhereTheDefaultsUseThem() {
        assertTrue(KeysWindow.accepts(KeyAction.EYEDROPPER, KeyChord.Input.MOUSE));
        assertFalse(KeysWindow.accepts(KeyAction.UNDO, KeyChord.Input.MOUSE));

        click(chordButton(KeyAction.EYEDROPPER, 0));
        assertTrue(windows.mouseDown(FAR, FAR, MIDDLE, Modifiers.ALT), "the press anywhere goes to the window");
        windows.mouseUp(FAR, FAR, MIDDLE);
        assertEquals(chords("alt+mouse.middle"), keymap.chords(KeyAction.EYEDROPPER));

        KeyCaptureButton undo = chordButton(KeyAction.UNDO, 0);
        click(undo);
        assertTrue(windows.mouseDown(FAR, FAR, MIDDLE, 0));
        windows.mouseUp(FAR, FAR, MIDDLE);
        assertTrue(undo.isListening(), "a mouse button is not a key for undo: still listening");
        assertTrue(key(GLFW.GLFW_KEY_U, Modifiers.CONTROL));
        assertEquals(chords("ctrl+u"), keymap.chords(KeyAction.UNDO));
    }

    @Test
    void scrollBindsOnlyWhereTheDefaultsUseIt() {
        assertTrue(KeysWindow.accepts(KeyAction.FLY_SPEED, KeyChord.Input.SCROLL));
        assertTrue(KeysWindow.accepts(KeyAction.TOOL_SIZE, KeyChord.Input.SCROLL));

        click(chordButton(KeyAction.FLY_SPEED, 0));
        assertTrue(windows.mouseScrolled(FAR, FAR, 1, Modifiers.CONTROL));
        assertEquals(chords("ctrl+scroll"), keymap.chords(KeyAction.FLY_SPEED));

        KeyCaptureButton undo = chordButton(KeyAction.UNDO, 0);
        click(undo);
        assertTrue(windows.mouseScrolled(FAR, FAR, -1, 0), "consumed all the same");
        assertTrue(undo.isListening());
    }

    // ---- Add, remove, reset ----

    @Test
    void plusAddsAChordUpToTheMaximum() {
        assertEquals(3, KeysWindow.MAX_CHORDS, "the largest default set (Ctrl+= has three)");
        click(window.controls(KeyAction.UNDO).add());
        assertTrue(key(GLFW.GLFW_KEY_F5, 0));
        assertEquals(chords("ctrl+z", "f5"), keymap.chords(KeyAction.UNDO));
        click(window.controls(KeyAction.UNDO).add());
        assertTrue(key(GLFW.GLFW_KEY_F6, Modifiers.SHIFT));
        assertEquals(chords("ctrl+z", "f5", "shift+f6"), keymap.chords(KeyAction.UNDO));
        assertNull(window.controls(KeyAction.UNDO).add(), "full: no more +");
        assertEquals(3, window.controls(KeyAction.UNDO).chords().size());
    }

    @Test
    void plusWithBackspaceOrEscAddsNothing() {
        click(window.controls(KeyAction.UNDO).add());
        assertTrue(key(GLFW.GLFW_KEY_BACKSPACE, 0));
        click(window.controls(KeyAction.UNDO).add());
        assertTrue(key(GLFW.GLFW_KEY_ESCAPE, 0));
        assertEquals(KeyAction.UNDO.defaultChords(), keymap.chords(KeyAction.UNDO));
        assertTrue(host.saved.isEmpty());
    }

    @Test
    void addingAChordTheActionAlreadyHasChangesNothing() {
        click(window.controls(KeyAction.REDO).add());
        assertTrue(key(GLFW.GLFW_KEY_Y, Modifiers.CONTROL));
        assertEquals(KeyAction.REDO.defaultChords(), keymap.chords(KeyAction.REDO));
    }

    @Test
    void crossRemovesAChord() {
        click(window.controls(KeyAction.REDO).removes().get(1));
        assertEquals(chords("ctrl+y"), keymap.chords(KeyAction.REDO));
        assertEquals(keymap.toJson(), lastSaved());
        assertEquals(1, window.controls(KeyAction.REDO).chords().size());
    }

    @Test
    void resetRestoresOneActionsDefaults() {
        assertFalse(window.controls(KeyAction.UNDO).reset().isEnabled(), "already the default");
        click(chordButton(KeyAction.UNDO, 0));
        assertTrue(key(GLFW.GLFW_KEY_U, Modifiers.CONTROL));
        click(window.controls(KeyAction.REDO).removes().get(0));
        assertTrue(window.controls(KeyAction.UNDO).reset().isEnabled());
        click(window.controls(KeyAction.UNDO).reset());
        assertEquals(KeyAction.UNDO.defaultChords(), keymap.chords(KeyAction.UNDO));
        assertEquals(chords("ctrl+shift+z"), keymap.chords(KeyAction.REDO), "other actions untouched");
        assertEquals(keymap.toJson(), lastSaved());
        assertFalse(window.controls(KeyAction.UNDO).reset().isEnabled());
    }

    @Test
    void resetAllAsksFirstThenRestoresEveryDefault() {
        click(chordButton(KeyAction.UNDO, 0));
        assertTrue(key(GLFW.GLFW_KEY_U, Modifiers.CONTROL));
        click(window.controls(KeyAction.REDO).removes().get(0));
        int saves = host.saved.size();

        click(window.resetAllButton());
        assertEquals(List.of("sculptory.keys.reset_all.confirm"), host.questions);
        assertEquals(chords("ctrl+u"), keymap.chords(KeyAction.UNDO), "nothing until the player agrees");
        assertEquals(saves, host.saved.size());

        host.pending.run();
        assertEquals(EditorKeymap.defaults().toJson(), keymap.toJson());
        assertEquals(keymap.toJson(), lastSaved());
        assertEquals(saves + 1, host.saved.size());
    }

    @Test
    void resetAlsoRestoresAYieldedDefault() {
        EditorKeymap loaded = EditorKeymap.fromJson("{\"version\": 1, \"bindings\": {\"help\": [\"0\"]}}");
        KeysWindow other = new KeysWindow(loaded, Translator.KEYS, FILE, host);
        assertEquals(List.of(), loaded.chords(KeyAction.TOOL_10), "0 stays with help");
        assertEquals(0, other.controls(KeyAction.TOOL_10).chords().size());
        assertTrue(other.controls(KeyAction.TOOL_10).reset().isEnabled());
        other.controls(KeyAction.TOOL_10).reset().click();
        assertEquals(chords("0"), loaded.chords(KeyAction.TOOL_10));
        assertEquals(List.of(), loaded.yielded(KeyAction.TOOL_10));
        assertEquals(loaded.toJson(), lastSaved());
    }

    // ---- Conflicts and problems ----

    @Test
    void conflictsShowAtOnceAndAreSavedAllTheSame() {
        click(chordButton(KeyAction.UNDO, 0));
        assertTrue(key(GLFW.GLFW_KEY_C, Modifiers.CONTROL));
        assertEquals(chords("ctrl+c"), keymap.chords(KeyAction.UNDO));
        assertTrue(lastSaved().contains("\"ctrl+c\""), "saved with the conflict");

        KeyCaptureButton undo = chordButton(KeyAction.UNDO, 0);
        assertEquals(KeysWindow.PROBLEM_COLOR, undo.textColor());
        assertEquals("sculptory.keys.conflict_wins[Ctrl+C,sculptory.key.copy]", undo.tooltip(),
                "undo is listed first, so it wins");
        KeyCaptureButton copy = chordButton(KeyAction.COPY, 0);
        assertEquals(KeysWindow.PROBLEM_COLOR, copy.textColor());
        assertEquals("sculptory.keys.conflict_loses[Ctrl+C,sculptory.key.undo]", copy.tooltip());
        assertEquals(0, chordButton(KeyAction.PASTE, 0).textColor(), "other keys are fine");

        click(chordButton(KeyAction.UNDO, 0));
        assertTrue(key(GLFW.GLFW_KEY_Z, Modifiers.CONTROL));
        assertEquals(0, chordButton(KeyAction.COPY, 0).textColor(), "and the conflict clears at once");
    }

    @Test
    void aReservedKeyIsShownInRed() {
        keymap.setReservedKeys(List.of(new EditorKeymap.ReservedKey(KeyChord.Input.KEY, GLFW.GLFW_KEY_W, "Walk Forwards")));
        click(chordButton(KeyAction.UNDO, 0));
        assertTrue(key(GLFW.GLFW_KEY_W, 0));
        assertEquals(chords("w"), keymap.chords(KeyAction.UNDO), "bound, and shown as not working");
        assertEquals(KeysWindow.PROBLEM_COLOR, chordButton(KeyAction.UNDO, 0).textColor());
        assertEquals("sculptory.keys.reserved[W,Walk Forwards]", chordButton(KeyAction.UNDO, 0).tooltip());
    }

    // ---- Saving ----

    @Test
    void aFailedSaveToastsAndKeepsTheKeysInUse() {
        host.saveOk = false;
        click(chordButton(KeyAction.UNDO, 0));
        assertTrue(key(GLFW.GLFW_KEY_U, Modifiers.CONTROL));
        assertEquals(chords("ctrl+u"), keymap.chords(KeyAction.UNDO));
        assertEquals(List.of("sculptory.keys.save_failed[" + FILE + "]"), host.toasts);
        assertEquals("Ctrl+U", chordButton(KeyAction.UNDO, 0).text());
        assertEquals(1, host.changes, "the editor still uses the new key");
    }

    @Test
    void anUnboundActionShowsNoneAndAPlus() {
        assertEquals(0, window.controls(KeyAction.AIM_AT_FLUIDS).chords().size());
        assertNotNull(window.controls(KeyAction.AIM_AT_FLUIDS).add());
        click(window.controls(KeyAction.AIM_AT_FLUIDS).add());
        assertTrue(key(GLFW.GLFW_KEY_V, Modifiers.ALT));
        assertEquals(chords("alt+v"), keymap.chords(KeyAction.AIM_AT_FLUIDS));
    }

    @Test
    void everyKeysLabelHasEnglishText() throws IOException {
        // Whole keys only: the group heading key is built from a prefix and the group name.
        Pattern pattern = Pattern.compile("\"(sculptory\\.keys\\.[a-z_.]*[a-z_])\"");
        Path source = Path.of("src/client/java/dev/sculptory/fabric/client/editor/windows/KeysWindow.java");
        assertTrue(Files.isRegularFile(source), "tests run from the fabric module: " + source.toAbsolutePath());
        Set<String> used = new TreeSet<>();
        Matcher matcher = pattern.matcher(Files.readString(source));
        while (matcher.find()) {
            used.add(matcher.group(1));
        }
        for (KeyAction.Group group : KeyAction.Group.values()) {
            used.add("sculptory.keys.group." + group.name().toLowerCase(java.util.Locale.ROOT));
        }
        assertTrue(used.contains("sculptory.keys.listening") && used.contains("sculptory.keys.save_failed"), used.toString());
        JsonObject lang;
        try (InputStream in = getClass().getResourceAsStream("/assets/sculptory/lang/en_us.json")) {
            assertNotNull(in, "en_us.json is on the test classpath");
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        Set<String> missing = new TreeSet<>(used);
        missing.removeIf(lang::has);
        assertEquals(Set.of(), missing);
        assertEquals("Press keys…", lang.get("sculptory.keys.listening").getAsString());
    }
}
