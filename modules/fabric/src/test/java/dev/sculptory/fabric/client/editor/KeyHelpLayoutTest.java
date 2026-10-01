package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.hud.HelpSheet;
import dev.sculptory.fabric.client.editor.hud.KeyLineRow;
import dev.sculptory.fabric.client.editor.hud.KeyLineRow.KeyLine;
import dev.sculptory.fabric.client.editor.hud.KeySheet;
import dev.sculptory.fabric.client.editor.hud.ToastStack;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.ui.McFontText;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.RecordingGraphics;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import dev.sculptory.fabric.client.editor.ui.widget.SectionHeading;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The quick start card and the F1 key sheet on the reference screen (2560x1482 at GUI scale 6: 426x247 GUI pixels) at
 * UI 50% and 100%, in English, measured like Minecraft's font: the card's lines each fit on one line, and the sheet
 * never breaks a key's name, names each palette key's tool once, and starts every description with a capital.
 */
class KeyHelpLayoutTest {
    private static final int[] OWNERS_SIZES = {50, 100};

    private final EditorTestRig rig = new EditorTestRig();
    private final UiScale scale = new UiScale();
    private EditorUi ui;

    @BeforeEach
    void build() {
        ui = new EditorUi(rig.ctx, rig.controller, rig.platform, rig.actions, McFontText.INSTANCE,
                new ToastStack(() -> 0L), new EditorUi.Services(English.INSTANCE, BlockCatalog.EMPTY, icon -> null,
                        () -> new HelpSheet.VanillaKeys("W A S D", "Space", "Caps Lock", "B", "T", "/", "E"),
                        "config/sculptory/editor-keys.json", keymap -> true, scale));
        rig.controller.setUi(ui);
        ui.setEditing(true);
        rig.mode.enter();
    }

    private void frame() {
        ui.layout(426, 247);
        ui.render(new RecordingGraphics(), 0, 0, 0, false);
    }

    private static List<KeyLineRow> rows(Node node) {
        List<KeyLineRow> rows = new ArrayList<>();
        if (node instanceof KeyLineRow row) {
            rows.add(row);
        }
        for (Node child : node.children()) {
            rows.addAll(rows(child));
        }
        return rows;
    }

    @Test
    void theQuickStartCardFitsEachLineOnOneLineWithAGapAfterItsWidestKeys() {
        McFontText font = McFontText.INSTANCE;
        int oneLine = font.lineHeight() + 1;
        for (int percent : OWNERS_SIZES) {
            scale.set(percent);
            ui.showQuickStart();
            frame();
            String at = " at " + percent + "%";
            Rect card = ui.quickStart().node().bounds();
            int margin = Theme.DARK.sheetMargin;
            assertTrue(card.x() >= margin && card.right() <= ui.uiWidth() - margin, "on screen: " + card + at);
            List<KeyLineRow> lines = rows(ui.quickStart().node());
            assertEquals(6, lines.size());
            int widestKeys = lines.stream().mapToInt(line -> font.width(line.keys())).max().orElseThrow();
            RecordingGraphics g = new RecordingGraphics();
            ui.quickStart().node().render(g, ui.windows().context());
            for (KeyLineRow line : lines) {
                assertEquals(oneLine, line.bounds().height(), "\"" + line.keys() + "\" · \"" + line.text() + "\"" + at);
                assertTrue(line.bounds().right() <= card.right() - margin, line.text() + " inside the card" + at);
                assertEquals(widestKeys, line.keyWidth(), "the key column is the widest keys (\"1–9, 0, -, =, [\")");
                int keysEnd = g.textAnchor(line.keys()).x() + widestKeys;
                int textStart = g.textAnchor(line.text()).x();
                assertTrue(textStart - keysEnd >= 8, "a clear gap after the widest keys, not a space's width: "
                        + line.text() + " at " + (textStart - keysEnd) + at);
            }
            assertTrue(Character.isUpperCase(lines.get(0).text().charAt(0)), lines.get(0).text());
        }
    }

    @Test
    void theKeySheetNeverBreaksAKeysNameAndNamesEachToolOnce() {
        for (int percent : OWNERS_SIZES) {
            scale.set(percent);
            ui.toggleHelp();
            frame();
            String at = " at " + percent + "%";
            KeySheet sheet = ui.keySheet();
            assertEquals(percent == 50 ? KeySheet.MAX_COLUMNS : 1, sheet.columns(), "columns" + at);
            List<KeyLineRow> rows = rows(sheet.filterInput().root());
            assertTrue(rows.size() > 60, "every row: " + rows.size());
            for (KeyLineRow row : rows) {
                List<KeyLine> lines = row.keyLines(McFontText.INSTANCE);
                if (!row.keys().isEmpty()) {
                    assertEquals(row.keys(), lines.stream().map(KeyLine::keys)
                            .collect(Collectors.joining(KeyLineRow.SEPARATOR)), "split between keys only" + at);
                }
                for (int i = 0; i < lines.size(); i++) {
                    KeyLine line = lines.get(i);
                    assertEquals(i < lines.size() - 1, line.continues(),
                            "\"" + line.text() + "\": a line that goes on ends in \"/\"" + at);
                    assertEquals(i == 0 ? 0 : KeyLineRow.CONTINUATION_INDENT, line.indent(),
                            "\"" + line.text() + "\": the lines after the first start further in" + at);
                    assertTrue(line.indent() + McFontText.INSTANCE.width(line.text()) <= row.keyWidth(),
                            line + " inside the key column" + at);
                }
                char first = row.text().charAt(0);
                assertTrue(!Character.isLetter(first) || Character.isUpperCase(first),
                        "\"" + row.text() + "\" starts with a capital, as in the Keys window" + at);
            }
            int indent = KeyLineRow.CONTINUATION_INDENT;
            Map<String, List<KeyLine>> wrapped = Map.of(
                    "Space / Caps Lock", List.of(new KeyLine("Space /", 0), new KeyLine("Caps Lock", indent)),
                    "Ctrl+Y / Ctrl+Shift+Z", List.of(new KeyLine("Ctrl+Y /", 0), new KeyLine("Ctrl+Shift+Z", indent)),
                    "Shift+click / Alt+click",
                    List.of(new KeyLine("Shift+click /", 0), new KeyLine("Alt+click", indent)));
            wrapped.forEach((keys, lines) -> {
                KeyLineRow row = rows.stream().filter(line -> line.keys().equals(keys)).findFirst().orElseThrow();
                assertEquals(lines, row.keyLines(McFontText.INSTANCE),
                        "a line per key where both don't fit the column, the first ending in \"/\"" + at);
            });

            HelpSheet.Group tools = sheet.shownGroups().stream().filter(group -> group.group() == KeyAction.Group.TOOLS)
                    .findFirst().orElseThrow();
            assertTrue(tools.lines().contains(new HelpSheet.Line("0", "Shape", KeyAction.TOOL_10)),
                    "slot 10's key and its tool, no second number: " + tools.lines());
            assertTrue(tools.lines().contains(new HelpSheet.Line("2", "Raise", KeyAction.TOOL_2)));
            assertFalse(tools.lines().stream().anyMatch(line -> line.text().matches("\\d.*")), "no slot numbers" + at);
            ui.toggleHelp();
        }
    }

    @Test
    void theKeyColumnIsTheWidestKeyNameAndTypicalDescriptionsFitOnOneLine() {
        McFontText font = McFontText.INSTANCE;
        int oneLine = font.lineHeight() + 1;
        for (int percent : OWNERS_SIZES) {
            scale.set(percent);
            ui.toggleHelp();
            frame();
            String at = " at " + percent + "%";
            KeySheet sheet = ui.keySheet();
            Rect bounds = sheet.bounds();
            int margin = Theme.DARK.sheetMargin;
            assertTrue(bounds.x() >= margin && bounds.right() <= ui.uiWidth() - margin, "on screen: " + bounds + at);
            List<KeyLineRow> rows = rows(sheet.filterInput().root());
            int widestLine = 0;
            for (KeyLineRow row : rows) {
                String[] names = row.keys().split(KeyLineRow.SEPARATOR);
                for (int i = 0; i < names.length; i++) {
                    String line = i < names.length - 1 ? names[i] + KeyLineRow.CONTINUES : names[i];
                    widestLine = Math.max(widestLine, (i > 0 ? KeyLineRow.CONTINUATION_INDENT : 0) + font.width(line));
                }
            }
            // Each column gives its descriptions up to the theme's most (one column) or what the screen leaves each
            // of three columns, never less than the theme's least: a description that fits that is on one line.
            int textWidth = sheet.descriptionWidth();
            assertTrue(textWidth >= Theme.DARK.keySheetTextMinWidth, "readable columns: " + textWidth + at);
            assertEquals(sheet.columns() == 1, textWidth == Theme.DARK.keySheetTextMaxWidth, textWidth + at);
            List<String> wrapped = new ArrayList<>();
            for (KeyLineRow row : rows) {
                assertEquals(widestLine, row.keyWidth(), "the key column is the widest key name on a line of its own,"
                        + " its \"/\" or indent included (\"Ctrl+Keypad -\" under \"Ctrl+- /\")");
                boolean typical = font.width(row.text()) <= textWidth;
                if (typical && row.keyLines(font).size() == 1 && row.bounds().height() != oneLine) {
                    wrapped.add(row.text());
                }
            }
            assertEquals(List.of(), wrapped, "descriptions up to " + textWidth + " units on one line" + at);
            for (String text : List.of("Draw a box (or the shape set in Tool Settings)",
                    "Step back: close, cancel, then leave", "Rotate counter-clockwise")) {
                KeyLineRow row = rows.stream().filter(line -> line.text().equals(text)).findFirst().orElseThrow();
                assertEquals(font.width(text) <= textWidth, row.bounds().height() == oneLine,
                        text + " on one line where it fits the column" + at);
            }
            ui.toggleHelp();
        }
    }

    /** The key sheet's columns (the nodes holding its rows), left to right. */
    private static List<Node> columns(Node node) {
        List<Node> columns = new ArrayList<>();
        if (node.children().stream().anyMatch(child -> child instanceof KeyLineRow)) {
            columns.add(node);
        }
        for (Node child : node.children()) {
            columns.addAll(columns(child));
        }
        return columns;
    }

    /**
     * At the reference size the whole list fits without scrolling in the fewest columns that hold it (three, since the
     * batch of 2026-09-29 added Tinker, Flip upside down and more: two no longer do), of about the same height, with
     * room for about three more rows (builder mode's G ring and Ctrl+Z outside the editor are coming). This fails
     * again once the list outgrows three columns.
     */
    @Test
    void atTheOwnersSizeTheKeySheetFitsInTheFewestColumnsThatHoldItWithRoomForMoreRows() {
        scale.set(50);
        ui.toggleHelp();
        frame();
        KeySheet sheet = ui.keySheet();
        assertEquals(KeySheet.MAX_COLUMNS, sheet.columns(), "the fewest columns the list fits in");
        assertFalse(sheet.scroll().isScrollable(), "every row in view without scrolling: "
                + sheet.scroll().contentSize() + " units of rows in " + sheet.scroll().viewportSize());
        int oneLine = McFontText.INSTANCE.lineHeight() + 1;
        int most = ui.uiHeight() - 2 * Theme.DARK.sheetMargin;
        assertTrue(sheet.bounds().height() + 3 * oneLine <= most, "room for three more rows: the sheet is "
                + sheet.bounds().height() + " of " + most + " units tall");
        List<Node> columns = columns(sheet.filterInput().root());
        assertEquals(KeySheet.MAX_COLUMNS, columns.size());
        List<Integer> heights = columns.stream().map(column -> column.bounds().height()).toList();
        int tallest = heights.stream().mapToInt(Integer::intValue).max().orElseThrow();
        int shortest = heights.stream().mapToInt(Integer::intValue).min().orElseThrow();
        // The split can't be finer than a row, a heading repeated where a group goes on, or two groups' space.
        assertTrue(tallest - shortest <= 3 * Theme.DARK.rowHeight, "about as tall: " + heights);
        for (int i = 1; i < columns.size(); i++) {
            String lastBefore = columns.get(i - 1).children().stream().filter(SectionHeading.class::isInstance)
                    .map(node -> ((SectionHeading) node).text()).reduce((first, second) -> second).orElseThrow();
            Node top = columns.get(i).children().get(0);
            assertTrue(top instanceof SectionHeading, "column " + (i + 1) + " starts under a heading: " + top);
            String title = ((SectionHeading) top).text();
            assertTrue(title.equals(lastBefore + " (continued)") || !title.endsWith("(continued)"),
                    "a group going on in the next column starts it under its heading again: " + title);
        }
    }

    @Test
    void atUi100TheKeySheetScrollsDrawingOnlyWholeRows() {
        scale.set(100);
        ui.toggleHelp();
        frame();
        KeySheet sheet = ui.keySheet();
        assertTrue(sheet.scroll().isScrollable());
        List<KeyLineRow> rows = rows(sheet.filterInput().root());
        for (int offset = 0; offset <= sheet.scroll().maxOffset(); offset += 7) {
            sheet.scroll().setOffset(offset);
            frame();
            Rect view = sheet.scrollViewport();
            RecordingGraphics g = new RecordingGraphics();
            sheet.render(g, 0);
            for (KeyLineRow row : rows) {
                Rect at = row.bounds();
                boolean whole = at.y() >= view.y() && at.bottom() <= view.bottom();
                boolean drawn = g.textsAt(at.x(), at.y()).contains(row.keyLines(McFontText.INSTANCE).get(0).text());
                assertEquals(whole, drawn, "\"" + row.text() + "\" " + at
                        + " drawn only when wholly in " + view + " (scrolled " + offset + ")");
            }
        }
    }

    @Test
    void typingInTheKeySheetsFilterKeepsItsTopEdgeLeftEdgeAndWidth() {
        scale.set(50);
        ui.toggleHelp();
        frame();
        KeySheet sheet = ui.keySheet();
        Rect whole = sheet.bounds();
        for (String filter : List.of("sel", "undo", "no key has this name")) {
            sheet.setFilter(filter);
            frame();
            Rect narrowed = sheet.bounds();
            assertEquals(new Rect(whole.x(), whole.y(), whole.width(), narrowed.height()), narrowed,
                    "\"" + filter + "\": only shorter");
            assertTrue(narrowed.height() < whole.height(), filter);
        }

        // The screen changes while filtering: the sheet stands where the whole list would on the new screen.
        scale.set(100);
        frame();
        Rect filtered = sheet.bounds();
        sheet.setFilter("");
        frame();
        Rect all = sheet.bounds();
        assertEquals(new Rect(all.x(), all.y(), all.width(), filtered.height()), filtered);
    }

    @Test
    void aShortFilteredListReadsDownOneColumnAndALongOneTakesMoreNeverMoreThanTheWholeList() {
        scale.set(50);
        ui.toggleHelp();
        frame();
        KeySheet sheet = ui.keySheet();
        int whole = sheet.columns();
        int wholeHeight = sheet.bounds().height();
        assertTrue(whole >= 2, "the whole list: " + whole);
        sheet.setFilter("undo");
        frame();
        assertEquals(1, sheet.columns(), "a few rows: one column " + sheet.shownGroups());
        assertEquals(1, columns(sheet.filterInput().root()).size());
        assertFalse(sheet.scroll().isScrollable());
        // "s" matches about 25 rows and "c" (every Ctrl key too) many more: each in the fewest columns in which it is
        // no taller than the whole list (so no scrolling), never more columns than the whole list's.
        for (String filter : List.of("s", "c")) {
            sheet.setFilter(filter);
            frame();
            int rows = sheet.shownGroups().stream().mapToInt(group -> group.lines().size()).sum();
            String at = "\"" + filter + "\": " + rows + " rows in " + sheet.columns() + " columns";
            assertTrue(sheet.columns() >= 2 && sheet.columns() <= whole, at);
            assertFalse(sheet.scroll().isScrollable(), at);
            assertTrue(sheet.bounds().height() <= wholeHeight, at + ", no taller than the whole list");
        }
        sheet.setFilter("");
        frame();
        assertEquals(whole, sheet.columns());
    }

    @Test
    void theKeySheetKeepsClearOfTheTopBarWhereItFitsUnderIt() {
        scale.set(50);
        ui.toggleHelp();
        // A taller game window: the whole sheet fits between the top bar and the screen's bottom margin.
        ui.layout(426, 300);
        ui.render(new RecordingGraphics(), 0, 0, 0, false);
        Rect sheet = ui.keySheet().bounds();
        Rect topBar = ui.topBarRow().bounds();
        assertTrue(sheet.y() >= topBar.bottom() + 4, "under the top bar: " + sheet + " " + topBar);
        // The reference screen now (GUI scale 3, UI 100%: 854x498 units, as 427x249 at 50% here): the sheet sits under
        // the top bar, clear of it.
        ui.layout(427, 249);
        ui.render(new RecordingGraphics(), 0, 0, 0, false);
        sheet = ui.keySheet().bounds();
        assertTrue(sheet.y() >= topBar.bottom() && sheet.bottom() <= ui.uiHeight(), sheet + " " + topBar);
        // A much shorter window (426x160): the sheet scrolls at the screen's height, taller than the room under the
        // bar, so it is centred on the screen over the bar, by more than a sliver.
        ui.layout(426, 160);
        ui.render(new RecordingGraphics(), 0, 0, 0, false);
        sheet = ui.keySheet().bounds();
        assertTrue(ui.keySheet().scroll().isScrollable());
        assertEquals((ui.uiHeight() - sheet.height()) / 2, sheet.y(), sheet.toString());
        assertTrue(topBar.bottom() - sheet.y() >= topBar.height() / 2, "clearly over the bar: " + sheet);
    }

    @Test
    void theFooterSaysWhenMoreRowsAreBelowAndHowToGetBackToTheTop() {
        scale.set(50);
        ui.toggleHelp();
        frame();
        KeySheet sheet = ui.keySheet();
        assertEquals("F1 or Esc closes this sheet", sheet.footerShown(), "nothing to scroll: no scroll hint");
        scale.set(100);
        frame();
        assertTrue(sheet.footerShown().contains("More below"), sheet.footerShown());
        sheet.scroll().setOffset(sheet.scroll().maxOffset());
        frame();
        assertTrue(sheet.footerShown().contains("Home goes back to the top"), sheet.footerShown());
    }

    @Test
    void theSlashKeyAndUnboundActionsReadAsWords() {
        scale.set(50);
        ui.toggleHelp();
        frame();
        List<KeyLineRow> rows = rows(ui.keySheet().filterInput().root());
        KeyLineRow screens = rows.stream().filter(row -> row.keys().startsWith("T / ")).findFirst().orElseThrow();
        assertEquals("T / Slash / E", screens.keys(), "the command key by its name, not a third \"/\"");
        KeyLineRow water = rows.stream().filter(row -> row.text().equals("Aim at water and lava")).findFirst()
                .orElseThrow();
        assertEquals("Not bound", water.keyLines(McFontText.INSTANCE).get(0).text());
    }
}
