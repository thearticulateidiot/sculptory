package dev.sculptory.fabric.client.editor.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.input.KeyListing;
import dev.sculptory.fabric.client.editor.input.KeyChord;
import dev.sculptory.fabric.client.editor.tool.ToolRegistry;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.RecordingGraphics;
import dev.sculptory.fabric.client.editor.ui.ScrollModel;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.widget.SectionHeading;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/** The F1 key sheet: what it lists (every action, the Keys window's groups), the filter, scrolling and closing. */
class KeySheetTest {
    static final TextMeasure TEXT = new TextMeasure() {
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
    static final HelpSheet.VanillaKeys VANILLA = new HelpSheet.VanillaKeys("W A S D", "Space", "Shift", "B", "T", "/", "E");

    private final EditorKeymap keymap = EditorKeymap.defaults();
    private final List<String> changeKeys = new ArrayList<>();
    private KeySheet sheet;

    @BeforeEach
    void build() {
        sheet = new KeySheet(TEXT, Theme.DARK, keymap, Translator.KEYS, () -> VANILLA, KeySheetTest::toolName,
                () -> changeKeys.add("keys window"));
    }

    private static Optional<String> toolName(int slot) {
        return Optional.of("tool " + slot);
    }

    private List<HelpSheet.Group> groups() {
        return HelpSheet.build(keymap, Translator.KEYS, VANILLA, KeySheetTest::toolName);
    }

    // ---- What it lists ----

    @Test
    void everyActionIsListedOnceUnderItsGroupInTheKeysWindowsOrder() {
        List<HelpSheet.Group> groups = groups();
        assertEquals(List.of(KeyAction.Group.values()), groups.stream().map(HelpSheet.Group::group).toList(),
                "each group once, in group order: Windows is not split by the actions appended later");
        for (HelpSheet.Group group : groups) {
            assertEquals(KeyListing.groupKey(group.group()), group.title());
            assertEquals("sculptory.keys.group." + group.group().name().toLowerCase(java.util.Locale.ROOT),
                    group.title(), "the Keys window's heading");
        }
        Set<KeyAction> listed = EnumSet.noneOf(KeyAction.class);
        for (HelpSheet.Group group : groups) {
            for (HelpSheet.Line line : group.lines()) {
                if (line.action() != null) {
                    assertTrue(listed.add(line.action()), line.action() + " listed twice");
                    assertEquals(group.group(), line.action().group(), line.action() + " under its own group");
                }
            }
        }
        assertEquals(EnumSet.allOf(KeyAction.class), listed, "nothing left out");
        for (KeyAction action : List.of(KeyAction.COMMIT, KeyAction.ROTATE_CCW, KeyAction.FLIP_FRONT_BACK,
                KeyAction.SET_SYMMETRY_CENTRE, KeyAction.REMOVE_NODE, KeyAction.COMMAND_SEARCH,
                KeyAction.FOCUS_NEXT_WINDOW)) {
            assertTrue(listed.contains(action), action.name());
        }
    }

    @Test
    void theWindowsGroupHoldsCtrlKAndF6WithTheirKeys() {
        HelpSheet.Group windows = groups().stream().filter(group -> group.group() == KeyAction.Group.WINDOWS)
                .findFirst().orElseThrow();
        assertTrue(windows.lines().contains(new HelpSheet.Line("Ctrl+K", "sculptory.key.command_search",
                KeyAction.COMMAND_SEARCH)));
        assertTrue(windows.lines().contains(new HelpSheet.Line("F6", "sculptory.key.focus_next_window",
                KeyAction.FOCUS_NEXT_WINDOW)));
    }

    @Test
    void paletteKeysAreNamedAfterTheirToolsAndKeysFollowTheKeymap() {
        HelpSheet.Group tools = groups().stream().filter(group -> group.group() == KeyAction.Group.TOOLS)
                .findFirst().orElseThrow();
        assertEquals(new HelpSheet.Line("1", "tool 1", KeyAction.TOOL_1), tools.lines().get(0));
        assertEquals(new HelpSheet.Line("]", "tool 14", KeyAction.TOOL_14), tools.lines().get(13),
                "the tool's name alone: the key beside it stands for the slot, so its number shows once");
        assertEquals(new HelpSheet.Line("\\", "tool 15", KeyAction.TOOL_15), tools.lines().get(ToolRegistry.PALETTE_SLOTS - 1),
                "the last slot, the Weather brush, on backslash");
        List<HelpSheet.Line> unnamed = HelpSheet.build(keymap, Translator.KEYS, VANILLA, slot -> Optional.empty()).get(1)
                .lines();
        assertEquals(new HelpSheet.Line("1", "sculptory.key.tool_1", KeyAction.TOOL_1), unnamed.get(0),
                "a slot without a known tool: its label");

        keymap.bind(KeyAction.DESELECT, List.of(KeyChord.parse("ctrl+shift+d"), KeyChord.parse("k")));
        keymap.bind(KeyAction.UNDO, List.of());
        List<HelpSheet.Line> lines = groups().stream().flatMap(group -> group.lines().stream()).toList();
        assertTrue(lines.contains(new HelpSheet.Line("Ctrl+Shift+D / K", "sculptory.key.deselect", KeyAction.DESELECT)));
        assertTrue(lines.contains(new HelpSheet.Line("", "sculptory.key.undo", KeyAction.UNDO)), "unbound: no keys");
    }

    @Test
    void theMouseAndVanillaRowsStayInTheirGroups() {
        HelpSheet.Group movement = groups().get(0);
        assertEquals(new HelpSheet.Line("sculptory.help.mouse.right_drag", "sculptory.help.look"),
                movement.lines().get(0));
        assertTrue(movement.lines().contains(new HelpSheet.Line("B", "sculptory.help.toggle")));
        HelpSheet.Group windows = groups().get(groups().size() - 1);
        // The slash key by its name, not a "/" among the separators ("T / / / E").
        assertEquals(new HelpSheet.Line("T / sculptory.help.key.slash / E", "sculptory.help.suspend"),
                windows.lines().get(windows.lines().size() - 1));
    }

    // ---- Filter ----

    @Test
    void theFilterNarrowsByWhatAKeyDoesOrByTheKey() {
        List<HelpSheet.Group> undo = HelpSheet.filter(groups(), "key und");
        assertEquals(List.of(KeyAction.Group.HISTORY), undo.stream().map(HelpSheet.Group::group).toList());
        assertEquals(List.of(KeyAction.UNDO), undo.get(0).lines().stream().map(HelpSheet.Line::action).toList());

        List<HelpSheet.Line> ctrlD = HelpSheet.filter(groups(), "ctrl+d").stream()
                .flatMap(group -> group.lines().stream()).toList();
        assertEquals(List.of(KeyAction.DESELECT), ctrlD.stream().map(HelpSheet.Line::action).toList());
        List<HelpSheet.Line> f6 = HelpSheet.filter(groups(), "F6").stream()
                .flatMap(group -> group.lines().stream()).toList();
        assertEquals(List.of(KeyAction.FOCUS_NEXT_WINDOW), f6.stream().map(HelpSheet.Line::action).toList());
        assertEquals(List.of(), HelpSheet.filter(groups(), "zzz"));
        assertEquals(groups(), HelpSheet.filter(groups(), "  "), "nothing typed keeps everything");
    }

    @Test
    void typingInTheFilterNarrowsTheSheetAndScrollsToTheTop() {
        sheet.open();
        sheet.layout(640, 200);
        sheet.scroll().setOffset(40);
        sheet.setFilter("ctrl+d");
        assertEquals("ctrl+d", sheet.filterText());
        assertEquals(0, sheet.scroll().offset());
        assertEquals(List.of(KeyAction.DESELECT), sheet.shownGroups().stream().flatMap(group -> group.lines().stream())
                .map(HelpSheet.Line::action).toList());
        sheet.setFilter("");
        assertEquals(groups(), sheet.shownGroups());
    }

    // ---- Columns ----

    private static final int ROW = 10;
    private static final int GAP = 1;
    private static final int SPACE = 5;
    private static final int HEADING = 14;

    /** Groups of rows {@link #ROW} high, each under a heading; {@code rowHeights[g]} overrides group g's rows. */
    private static List<KeySheet.Entry> entries(int[] rowsPerGroup, int... rowHeights) {
        List<KeySheet.Entry> entries = new ArrayList<>();
        for (int group = 0; group < rowsPerGroup.length; group++) {
            entries.add(new KeySheet.Entry(group, true, HEADING));
            for (int row = 0; row < rowsPerGroup[group]; row++) {
                entries.add(new KeySheet.Entry(group, false, group < rowHeights.length ? rowHeights[group] : ROW));
            }
        }
        return entries;
    }

    private static int taller(List<KeySheet.Entry> entries, int split) {
        return Math.max(KeySheet.columnHeight(entries, 0, split, GAP, SPACE, HEADING),
                KeySheet.columnHeight(entries, split, entries.size(), GAP, SPACE, HEADING));
    }

    @Test
    void aColumnIsItsRowsGapsAndHeadingsWithTheirSpaceAboveAndARepeatedHeadingWhereAGroupGoesOn() {
        List<KeySheet.Entry> entries = entries(new int[] {2, 3});
        assertEquals(HEADING + GAP + ROW + GAP + ROW + GAP + SPACE + HEADING + GAP + ROW,
                KeySheet.columnHeight(entries, 0, 5, GAP, SPACE, HEADING), "a heading after rows has space above");
        assertEquals(HEADING + GAP + ROW + GAP + ROW, KeySheet.columnHeight(entries, 5, 7, GAP, SPACE, HEADING),
                "a column starting inside a group: its heading again, no space above it");
    }

    @Test
    void theColumnsSplitWhereTheTallerIsShortestByHeightNotByRows() {
        // A short group and a long one: the long one goes on in the second column, under its heading again.
        List<KeySheet.Entry> entries = entries(new int[] {2, 20});
        int split = KeySheet.balancedSplit(entries, GAP, SPACE, HEADING);
        for (int other = 1; other < entries.size(); other++) {
            boolean allowed = !entries.get(other - 1).heading()
                    && (entries.get(other).heading() || !tooFewRows(entries, other));
            if (allowed) {
                assertTrue(taller(entries, split) <= taller(entries, other),
                        "no split leaves a shorter taller column than " + split + ", not " + other);
            }
        }
        assertFalse(entries.get(split).heading(), "the long group goes on in the second column");
        assertEquals(1, entries.get(split).group());
        assertFalse(tooFewRows(entries, split), "two rows or more of it on each side");

        // Four three-line rows, then four one-line rows: as many rows each side of the second heading, but by height
        // the tall group goes on in the second column.
        assertEquals(3, KeySheet.balancedSplit(entries(new int[] {4, 4}, 32), GAP, SPACE, HEADING));
    }

    private static boolean tooFewRows(List<KeySheet.Entry> entries, int split) {
        int before = 0;
        for (int i = split - 1; i >= 0 && !entries.get(i).heading(); i--) {
            before++;
        }
        int after = 0;
        for (int i = split; i < entries.size() && !entries.get(i).heading(); i++) {
            after++;
        }
        return before < 2 || after < 2;
    }

    @Test
    void aSplitBetweenGroupsWinsWhenItIsAsEvenAndAHeadingIsNeverLeftAtTheBottom() {
        assertEquals(5, KeySheet.balancedSplit(entries(new int[] {4, 4}), GAP, SPACE, HEADING),
                "two equal groups: one a column");
        assertEquals(4, KeySheet.balancedSplit(entries(new int[] {3}), GAP, SPACE, HEADING),
                "three rows can't go two and two: all in the first column");
        List<KeySheet.Entry> lone = entries(new int[] {1, 1});
        assertEquals(2, KeySheet.balancedSplit(lone, GAP, SPACE, HEADING));
        for (int split = 1; split < 30; split++) {
            List<KeySheet.Entry> entries = entries(new int[] {split % 5 + 1, 7, split % 3 + 2});
            int at = KeySheet.balancedSplit(entries, GAP, SPACE, HEADING);
            assertFalse(at < entries.size() && entries.get(at - 1).heading(), "a heading is never last: " + at);
        }
    }

    @Test
    void threeColumnsSplitTwiceWhereTheTallestIsShortestBetweenGroupsWhenAsEven() {
        assertEquals(List.of(5, 10), KeySheet.balancedSplits(entries(new int[] {4, 4, 4}), 3, GAP, SPACE, HEADING),
                "three equal groups: one a column");
        assertEquals(List.of(3, 6), KeySheet.balancedSplits(entries(new int[] {8}), 3, GAP, SPACE, HEADING),
                "one group of eight rows: two, three and three, its heading repeated twice");
        assertEquals(List.of(), KeySheet.balancedSplits(entries(new int[] {4, 4}), 1, GAP, SPACE, HEADING));
        assertEquals(List.of(2), KeySheet.balancedSplits(entries(new int[] {1, 1}), 3, GAP, SPACE, HEADING),
                "fewer splits than columns when no more are possible");
        assertEquals(List.of(KeySheet.balancedSplit(entries(new int[] {4, 4}), GAP, SPACE, HEADING)),
                KeySheet.balancedSplits(entries(new int[] {4, 4}), 2, GAP, SPACE, HEADING), "two: as before");
    }

    @Test
    void theSheetsColumnsComeOutAboutAsTallAndAGroupGoingOnRepeatsItsHeading() {
        sheet.open();
        // Wide enough for three columns, tall enough for the list in two but not in one: two, the fewest that fit.
        sheet.layout(900, 600);
        assertEquals(2, sheet.columns());
        assertFalse(sheet.scroll().isScrollable());
        sheet.layout(900, 2000);
        assertEquals(1, sheet.columns(), "one column where the whole list fits the screen's height in one");
        sheet.layout(900, 600);
        assertEquals(2, sheet.columns());
        List<Rect> columns = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        collect(sheet.filterInput().root(), columns, headings);
        assertEquals(2, columns.size(), "two columns: " + columns);
        int first = columns.get(0).height();
        int second = columns.get(1).height();
        assertTrue(Math.abs(first - second) <= 2 * (HEADING + GAP + SPACE) + 2 * 21,
                "about as tall: " + first + " and " + second);
        long continued = headings.stream().filter(title -> title.startsWith("sculptory.help.continued[")).count();
        assertTrue(continued <= 1, "at most one group goes on: " + headings);
    }

    /** The key sheet's columns (the parents of its rows) and every heading title, in order. */
    private static void collect(Node node, List<Rect> columns,
            List<String> headings) {
        if (node instanceof SectionHeading heading) {
            headings.add(heading.text());
        }
        boolean rows = node.children().stream().anyMatch(child -> child instanceof KeyLineRow);
        if (rows) {
            columns.add(node.bounds());
        }
        for (Node child : node.children()) {
            collect(child, columns, headings);
        }
    }

    @Test
    void whenTheSheetScrollsOnlyWholeRowsAreDrawn() {
        sheet.open();
        sheet.layout(640, 200);
        ScrollModel scroll = sheet.scroll();
        assertTrue(scroll.isScrollable(), "a short screen scrolls");
        for (int offset : List.of(0, 5, 17, scroll.maxOffset())) {
            scroll.setOffset(offset);
            sheet.layout(640, 200);
            RecordingGraphics g = new RecordingGraphics();
            sheet.render(g, 0);
            Rect view = sheet.scrollViewport();
            List<KeyLineRow> rows = new ArrayList<>();
            rowsUnder(sheet.filterInput().root(), rows);
            int drawn = 0;
            for (KeyLineRow row : rows) {
                boolean inView = row.bounds().y() >= view.y() && row.bounds().bottom() <= view.bottom();
                boolean shown = g.textsAt(row.bounds().x(), row.bounds().y())
                        .contains(row.keyLines(TEXT).get(0).text());
                assertEquals(inView, shown, "\"" + row.text() + "\" at " + row.bounds() + " in " + view
                        + ", scrolled " + offset);
                drawn += shown ? 1 : 0;
            }
            assertTrue(drawn > 3, "rows in view are drawn: " + drawn);
        }
    }

    private static void rowsUnder(Node node, List<KeyLineRow> rows) {
        if (node instanceof KeyLineRow row) {
            rows.add(row);
        }
        for (Node child : node.children()) {
            rowsUnder(child, rows);
        }
    }

    // ---- Keyboard ----

    @Test
    void upDownPageUpPageDownHomeAndEndScroll() {
        sheet.open();
        sheet.layout(640, 200);
        ScrollModel scroll = sheet.scroll();
        assertTrue(scroll.isScrollable(), "a short screen scrolls");
        int row = Theme.DARK.rowHeight;
        assertTrue(sheet.keyPressed(GLFW.GLFW_KEY_DOWN, 0, 0));
        assertEquals(row, scroll.offset());
        sheet.keyPressed(GLFW.GLFW_KEY_UP, 0, 0);
        assertEquals(0, scroll.offset());
        sheet.keyPressed(GLFW.GLFW_KEY_PAGE_DOWN, 0, 0);
        assertEquals(Math.min(scroll.maxOffset(), scroll.viewportSize() - row), scroll.offset());
        sheet.keyPressed(GLFW.GLFW_KEY_END, 0, 0);
        assertEquals(scroll.maxOffset(), scroll.offset());
        sheet.keyPressed(GLFW.GLFW_KEY_PAGE_UP, 0, 0);
        assertEquals(Math.max(0, scroll.maxOffset() - (scroll.viewportSize() - row)), scroll.offset());
        sheet.keyPressed(GLFW.GLFW_KEY_HOME, 0, 0);
        assertEquals(0, scroll.offset());
        assertTrue(sheet.isOpen());
        assertSame(sheet.filterInput(), sheet.context().focused(), "the filter keeps the keyboard");
    }

    @Test
    void tabMovesBetweenTheFilterAndChangeKeys() {
        sheet.open();
        sheet.layout(640, 360);
        assertTrue(sheet.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0));
        assertSame(sheet.changeKeysButton(), sheet.context().focused());
        sheet.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
        assertFalse(sheet.isOpen(), "Enter on Change keys… closes the sheet");
        assertEquals(List.of("keys window"), changeKeys);
    }

    @Test
    void itClosesWithTheHelpKeyAsBoundOrEsc() {
        sheet.open();
        assertTrue(sheet.keyPressed(GLFW.GLFW_KEY_F1, 0, 0));
        assertFalse(sheet.isOpen());

        keymap.bind(KeyAction.HELP, List.of(KeyChord.parse("f2")));
        sheet.open();
        assertFalse(sheet.keyPressed(GLFW.GLFW_KEY_F1, 0, 0), "F1 is no longer the Help key");
        assertTrue(sheet.isOpen());
        assertTrue(sheet.keyPressed(GLFW.GLFW_KEY_F2, 0, 0));
        assertFalse(sheet.isOpen());

        sheet.open();
        assertTrue(sheet.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0));
        assertFalse(sheet.isOpen());
    }

    // ---- Mouse ----

    @Test
    void aClickInsideKeepsItOpenAndOneOutsideClosesIt() {
        sheet.open();
        sheet.layout(640, 360);
        Rect bounds = sheet.bounds();
        sheet.mouseDown(bounds.x() + 20, bounds.bottom() - 20, 0, 0);
        sheet.mouseUp(bounds.x() + 20, bounds.bottom() - 20, 0);
        assertTrue(sheet.isOpen());
        assertTrue(sheet.mouseDown(bounds.x() - 2, bounds.y() + 5, 0, 0), "the click is used either way");
        assertFalse(sheet.isOpen());
    }

    @Test
    void theChangeKeysButtonClosesTheSheetFirst() {
        sheet.open();
        sheet.layout(640, 360);
        Rect button = sheet.changeKeysButton().bounds();
        sheet.mouseDown(button.x() + 2, button.y() + 2, 0, 0);
        sheet.mouseUp(button.x() + 2, button.y() + 2, 0);
        assertFalse(sheet.isOpen());
        assertEquals(List.of("keys window"), changeKeys);
    }

    @Test
    void itDrawsWithoutARunningClientFilterFieldIncluded() {
        sheet.open();
        sheet.setFilter("undo");
        sheet.layout(640, 360);
        RecordingGraphics g = new RecordingGraphics();
        sheet.render(g, 0);
        List<String> texts = g.drawnTexts();
        assertEquals(0, g.drawnWidgets(), "no vanilla widget without a client");
        assertTrue(texts.contains("undo"), "the filter text, drawn plainly: " + texts);
        assertTrue(texts.contains("Ctrl+Z"), "the matching row: " + texts);
    }

    @Test
    void aNarrowScreenGetsOneColumn() {
        sheet.open();
        sheet.layout(320, 240);
        assertTrue(sheet.bounds().width() <= 320 - 2 * Theme.DARK.sheetMargin);
        assertEquals(1, sheet.columns());
    }
}
