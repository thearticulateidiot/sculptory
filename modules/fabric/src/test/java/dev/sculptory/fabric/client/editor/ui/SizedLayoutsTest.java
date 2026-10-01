package dev.sculptory.fabric.client.editor.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.ui.window.Corner;
import dev.sculptory.fabric.client.editor.ui.window.LayoutState;
import dev.sculptory.fabric.client.editor.ui.window.LayoutState.WindowState;
import dev.sculptory.fabric.client.editor.ui.window.SizedLayouts;
import dev.sculptory.fabric.client.editor.ui.window.SizedLayouts.Shown;
import dev.sculptory.fabric.client.editor.ui.window.WindowPlacement;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** The {@code editor-layout.json} format: one arrangement per UI size, the open and collapsed flags shared. */
class SizedLayoutsTest {
    private static final Predicate<WindowState> NO_OLD_DEFAULTS = state -> false;
    private static final WindowPlacement HISTORY_AT_50 = new WindowPlacement(Corner.BOTTOM_LEFT, 4, 4, 160, 162, false);
    private static final WindowPlacement HISTORY_AT_100 = new WindowPlacement(Corner.TOP_RIGHT, 30, 40, 150, 120, true);

    private static SizedLayouts parse(String json) {
        return SizedLayouts.fromJson(json, 50, NO_OLD_DEFAULTS);
    }

    /** Two sizes arranged: History moved at both, Selection at 50% only; Keys open and collapsed, never moved. */
    private static SizedLayouts twoSizes() {
        return new SizedLayouts(
                List.of(new Shown("selection", true, false), new Shown("history", true, false),
                        new Shown("keys", true, true)),
                Map.of("100", Map.of("history", HISTORY_AT_100),
                        "50", Map.of("history", HISTORY_AT_50,
                                "selection", new WindowPlacement(Corner.TOP_LEFT, 4, 26, 190, 250, false))));
    }

    @Test
    void theFileIsReadableJsonThatRoundTrips() {
        SizedLayouts layouts = twoSizes();
        String json = layouts.toJson();
        assertTrue(json.startsWith("{\n  \"version\": 2,\n  \"windows\": [\n    {\n      \"id\": \"selection\""), json);
        assertTrue(json.indexOf("\"50\"") < json.indexOf("\"100\""), "sizes in increasing order: " + json);
        assertEquals(layouts, parse(json));
        assertEquals(json, parse(json).toJson(), "written the same way again");
    }

    @Test
    void eachSizeIsItsOwnArrangementAndASizeNeverArrangedHasNone() {
        SizedLayouts layouts = twoSizes();
        assertEquals(HISTORY_AT_50, layouts.arrangement(50).get("history"));
        assertEquals(HISTORY_AT_100, layouts.arrangement(100).get("history"));
        assertEquals(Map.of(), layouts.arrangement(75));

        LayoutState at100 = layouts.at(100);
        assertEquals(List.of("selection", "history", "keys"), at100.windows().stream().map(WindowState::id).toList(),
                "the shared order");
        assertFalse(at100.window("selection").orElseThrow().placed(), "not moved at 100%: its default place");
        assertEquals(HISTORY_AT_100, at100.window("history").orElseThrow().placement().orElseThrow());
        WindowState keys = layouts.at(75).window("keys").orElseThrow();
        assertTrue(keys.open() && keys.collapsed() && !keys.placed(), "open and collapsed at every size");
    }

    @Test
    void captureReplacesOnlyThatSizesArrangementAndTakesTheFlags() {
        SizedLayouts layouts = twoSizes();
        WindowPlacement moved = new WindowPlacement(Corner.TOP_LEFT, 200, 60, 150, 120, false);
        LayoutState live = new LayoutState(List.of(
                WindowState.unplaced("keys", false, false),
                WindowState.unplaced("selection", true, true),
                WindowState.unplaced("history", true, false).withPlacement(moved)));
        SizedLayouts captured = layouts.capture(100, live);
        assertEquals(Map.of("history", moved), captured.arrangement(100));
        assertEquals(layouts.arrangement(50), captured.arrangement(50), "50% untouched");
        assertEquals(List.of(new Shown("keys", false, false), new Shown("selection", true, true),
                new Shown("history", true, false)), captured.windows(), "flags and order from the live layout");

        // Nothing placed at 100% any more: the size is dropped and shows the default layout.
        SizedLayouts unplaced = captured.capture(100, new LayoutState(List.of(WindowState.unplaced("history", true,
                false))));
        assertFalse(unplaced.uiSizes().containsKey("100"));
        assertEquals(layouts.arrangement(50), unplaced.arrangement(50));

        SizedLayouts reset = layouts.without(50);
        assertEquals(Map.of(), reset.arrangement(50));
        assertEquals(layouts.arrangement(100), reset.arrangement(100), "another size's reset leaves 100% alone");
        assertEquals(layouts.windows(), reset.windows());
    }

    @Test
    void windowsAndSizesThisVersionDoesntKnowAreKept() {
        String json = """
                {"version": 2,
                 "windows": [
                   {"id": "selection", "open": true, "collapsed": false},
                   {"id": "future_window", "open": true, "collapsed": true}
                 ],
                 "uiSizes": {
                   "65": [{"id": "selection", "anchor": "TOP_LEFT", "x": 1, "y": 2, "width": 100, "height": 80}],
                   "huge": [],
                   "50": [
                     {"id": "future_window", "anchor": "BOTTOM_RIGHT", "x": 9, "y": 9, "width": 99, "height": 99},
                     {"id": "placed_only", "anchor": "TOP_RIGHT", "x": 3, "y": 4, "width": 120, "height": 90}
                   ]
                 }}
                """;
        SizedLayouts layouts = parse(json);
        assertEquals(List.of("65", "huge", "50"), List.copyOf(layouts.uiSizes().keySet()));
        assertEquals(List.of("future_window", "placed_only"), List.copyOf(layouts.arrangement(50).keySet()));
        assertEquals(layouts, parse(layouts.toJson()), "kept on a round trip");

        // The live layout (the editor's windows at 50%) has no future_window nor placed_only: both keep their flags and
        // places, and so do the sizes this version doesn't offer.
        LayoutState live = new LayoutState(List.of(WindowState.unplaced("selection", false, false)));
        SizedLayouts saved = layouts.capture(50, live);
        assertEquals(List.of(new Shown("selection", false, false), new Shown("future_window", true, true)),
                saved.windows());
        assertEquals(layouts.arrangement(50), saved.arrangement(50));
        assertEquals(layouts.uiSizes().get("65"), saved.uiSizes().get("65"));
        assertEquals(Map.of(), saved.uiSizes().get("huge"));
    }

    @Test
    void entriesThatCantBeReadAreSkipped() {
        String good = "{\"id\": \"ok\", \"anchor\": \"TOP_LEFT\", \"x\": 1, \"y\": 2, \"width\": 100, \"height\": 80}";
        String json = "{\"version\": 2, \"windows\": ["
                + "{\"id\": \"ok\", \"open\": true, \"collapsed\": false},"
                + "{\"id\": \"ok\", \"open\": false, \"collapsed\": false},"
                + "{\"id\": \"no_flags\"},"
                + "{\"id\": \"\", \"open\": true, \"collapsed\": false},"
                + "{\"id\": \"text_flag\", \"open\": \"yes\", \"collapsed\": false},"
                + "7],"
                + " \"uiSizes\": {\"50\": [" + good + ","
                + good.replace("\"x\": 1", "\"x\": 99") + ","
                + good.replace("\"ok\"", "\"corner\"").replace("TOP_LEFT", "MIDDLE") + ","
                + good.replace("\"ok\"", "\"tiny\"").replace("100", "-5") + ","
                + good.replace("\"ok\"", "\"huge\"").replace("100", "99999") + ","
                + good.replace("\"ok\"", "\"fraction\"").replace("\"x\": 1", "\"x\": 1.5") + ","
                + good.replace("\"ok\"", "\"bad_flag\"").replace("\"height\": 80", "\"height\": 80, \"overReserved\": 1")
                + ",\"entry\", {\"id\": \"missing\"}],"
                + " \"60\": {\"not\": \"a list\"}, \"70\": 4}}";
        SizedLayouts layouts = parse(json);
        assertEquals(List.of(new Shown("ok", true, false)), layouts.windows(), "the first of two with an id wins");
        assertEquals(Map.of("ok", new WindowPlacement(Corner.TOP_LEFT, 1, 2, 100, 80, false)), layouts.arrangement(50));
        assertEquals(List.of("50"), List.copyOf(layouts.uiSizes().keySet()), "a size that isn't a list is dropped");
    }

    @Test
    void textThatIsntALayoutFileIsRefused() {
        for (String bad : List.of("", "not json", "[]", "{", "{\"windows\": []}", "{\"version\": \"2\", \"windows\": []}",
                "{\"version\": 2.5, \"windows\": []}", "{\"version\": 2}", "{\"version\": 2, \"windows\": {}}",
                "{\"version\": 2, \"windows\": [], \"uiSizes\": []}", "{\"version\": 1}")) {
            assertThrows(IllegalArgumentException.class, () -> parse(bad), bad);
        }
        assertEquals(SizedLayouts.EMPTY, parse("{\"version\": 2, \"windows\": []}"), "no sizes: the defaults");
    }

    @Test
    void aNewerVersionIsRefusedSaying() {
        IllegalArgumentException newer = assertThrows(IllegalArgumentException.class,
                () -> parse("{\"version\": 3, \"windows\": [], \"uiSizes\": {}, \"themes\": {}}"));
        assertTrue(newer.getMessage().contains("version 3"), newer.getMessage());
        assertThrows(IllegalArgumentException.class, () -> parse("{\"version\": 0, \"windows\": []}"));
    }

    @Test
    void aVersionOneFileIsTheArrangementOfTheSizeItWasUsedAt() {
        // main's format with "placed" and "overReserved", and the older one without them (the reference file).
        String json = """
                {"version": 1, "windows": [
                  {"id": "clipboard", "open": false, "collapsed": false, "anchor": "BOTTOM_LEFT",
                   "x": 4, "y": 100, "width": 180, "height": 150},
                  {"id": "selection", "open": true, "collapsed": false, "anchor": "TOP_LEFT",
                   "x": 4, "y": 26, "width": 190, "height": 250},
                  {"id": "keys", "open": false, "collapsed": false, "anchor": "TOP_LEFT",
                   "x": 300, "y": 60, "width": 230, "height": 220},
                  {"id": "history", "open": true, "collapsed": true, "anchor": "BOTTOM_LEFT",
                   "x": 4, "y": 4, "width": 160, "height": 162, "placed": true, "overReserved": true},
                  {"id": "library", "open": true, "collapsed": false, "anchor": "TOP_RIGHT",
                   "x": 180, "y": 26, "width": 220, "height": 260, "placed": false, "overReserved": false}
                ]}""";
        // The clipboard entry sits at an old default place: never moved.
        Predicate<WindowState> oldDefault = state -> state.id().equals("clipboard") && state.offsetY() == 100;
        SizedLayouts layouts = SizedLayouts.fromJson(json, 50, oldDefault);

        assertEquals(List.of(new Shown("clipboard", false, false), new Shown("selection", true, false),
                new Shown("keys", false, false), new Shown("history", true, true), new Shown("library", true, false)),
                layouts.windows(), "the flags and order of the file");
        assertEquals(List.of("50"), List.copyOf(layouts.uiSizes().keySet()), "the other sizes start from the defaults");
        Map<String, WindowPlacement> at50 = layouts.arrangement(50);
        assertEquals(List.of("selection", "keys", "history"), List.copyOf(at50.keySet()),
                "open windows without \"placed\", the closed one moved by the user, and the one saved placed");
        assertEquals(new WindowPlacement(Corner.BOTTOM_LEFT, 4, 4, 160, 162, true), at50.get("history"));
        assertEquals(new WindowPlacement(Corner.TOP_LEFT, 300, 60, 230, 220, false), at50.get("keys"));

        // Each window as the version-1 reader has it, at 50%.
        LayoutState single = LayoutState.fromJson(json, oldDefault);
        for (WindowState state : single.windows()) {
            WindowState at = layouts.at(50).window(state.id()).orElseThrow();
            assertEquals(state.placement(), at.placement(), state.id());
            assertEquals(List.of(state.open(), state.collapsed()), List.of(at.open(), at.collapsed()), state.id());
        }
        assertEquals(Map.of(), SizedLayouts.fromJson(json, 100, oldDefault).arrangement(50),
                "read at another size, the file is that size's");
    }
}
