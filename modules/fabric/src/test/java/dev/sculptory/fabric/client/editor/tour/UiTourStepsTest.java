package dev.sculptory.fabric.client.editor.tour;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.commands.CommandMenu;
import dev.sculptory.fabric.client.editor.tool.ToolRegistry;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The baseline step list: every tool, window and menu of today's UI, each picture named once. */
class UiTourStepsTest {
    private final List<TourStep<TourContext>> steps = UiTour.steps();
    private final List<String> names = steps.stream().map(TourStep::name).toList();

    @Test
    void namesAndPictureFilesAreUnique() {
        assertEquals(names.size(), new HashSet<>(names).size(), names.toString());
        List<String> files = IntStream.range(0, steps.size())
                .mapToObj(i -> TourIndex.fileName(i + 1, steps.size(), names.get(i)))
                .toList();
        assertEquals(files.size(), new HashSet<>(files).size());
        assertEquals("01-editor-opened.png", files.get(0));
    }

    @Test
    void everyPaletteToolHasAStep() {
        long tools = names.stream().filter(name -> name.startsWith("tool-") && !name.equals("tool-select-with-box"))
                .count();
        assertEquals(ToolRegistry.PALETTE_SLOTS, tools);
        assertTrue(names.contains("tool-select-with-box"));
    }

    @Test
    void everyWindowButToolSettingsHasAStepAndEveryMenuAndTheSearchToo() {
        for (String window : EditorWindows.MENU) {
            if (!window.equals(EditorWindows.TOOL_SETTINGS)) {
                assertTrue(names.contains("window-" + window.replace('_', '-')), window);
            }
        }
        for (CommandMenu menu : CommandMenu.values()) {
            assertTrue(names.contains("menu-" + menu.name().toLowerCase(Locale.ROOT)), menu.name());
        }
        assertTrue(names.containsAll(List.of("menu-view-ui-size", "menu-ui-size", "command-search-empty",
                "command-search-hol", "help-sheet", "block-picker",
                "raise-mask-expanded", "ui-size-50", "ui-size-100", "ui-size-150", "tooltip-palette-slot")));
        assertTrue(names.containsAll(List.of("key-sheet-50", "key-sheet-filtered", "quick-start-50", "quick-start-100",
                "window-notifications", "focus-next-window")), "the key sheet, quick start, Notifications and F6");
    }

    /**
     * A tour at UI 100% showed Notifications at 50%: steps set the reference size themselves. Now the run's base size
     * holds for every step but those about another size, which carry it ({@link TourStep#atUiSize}) and say so.
     */
    @Test
    void everyStepShowsTheBaseUiSizeUnlessItIsAboutAnotherAndSaysSo() {
        Pattern named = Pattern.compile("UI (?:size )?(\\d+)%");
        Pattern suffix = Pattern.compile("-(50|100|150)$");
        for (TourStep<TourContext> step : steps) {
            Matcher description = named.matcher(step.description());
            if (step.uiPercent() == TourStep.BASE_UI_SIZE) {
                assertFalse(description.find(), step.name() + " is at the base size; its description names none: "
                        + step.description());
            } else {
                assertTrue(description.find() && Integer.parseInt(description.group(1)) == step.uiPercent(),
                        step.name() + " at " + step.uiPercent() + "% says so: " + step.description());
            }
            Matcher sized = suffix.matcher(step.name());
            if (sized.find()) {
                assertEquals(Integer.parseInt(sized.group(1)), step.uiPercent(), step.name());
            }
        }
        for (String name : List.of("window-notifications", "focus-next-window", "key-sheet-filtered",
                "command-search-empty", "menu-file", "settings-tooltip", "window-keys-search",
                "window-history-short")) {
            assertEquals(TourStep.BASE_UI_SIZE, steps.get(names.indexOf(name)).uiPercent(), name);
        }
        assertEquals(List.of("menu-edit-100", "menu-ui-size", "command-search-100", "help-sheet", "key-sheet-50",
                "quick-start-50", "quick-start-100", "ui-size-50", "ui-size-100", "ui-size-150", "settings-reset-100",
                "window-keys-50", "opacity-panels-30-100"),
                steps.stream().filter(step -> step.uiPercent() != TourStep.BASE_UI_SIZE).map(TourStep::name).toList(),
                "the steps about a UI size");
    }

    @Test
    void theTooltipStepWaitsPastTheHoverDelay() {
        TourStep<TourContext> tooltip = steps.get(names.indexOf("tooltip-palette-slot"));
        assertTrue(tooltip.minMillis() > 500, "the theme's tooltip delay is 500 ms");
    }

    /** The frozen wiki page ids and the other pages with pictures. */
    private static final List<String> WIKI_PAGES = List.of("home", "getting-started", "editor-mode",
            "screen-and-windows", "finding-things", "tutorial", "tool-settings", "presets", "select",
            "selection-operations", "terrain-brushes", "masks", "shape-brush", "generate", "extrude", "fluid", "scatter",
            "clipboard", "place", "library", "palettes", "symmetry", "history", "keys", "server-setup", "permissions",
            "troubleshooting", "faq", "tools", "tinker", "builder-mode", "mix-patterns", "import-export");

    @Test
    void theOrdinaryTourKeepsWholeFrames() {
        for (TourStep<TourContext> step : steps) {
            assertEquals(null, step.crop(), step.name());
            assertEquals(TourStep.FULL_WIDTH, step.maxWidth(), step.name());
        }
    }

    @Test
    void eachWikiPictureIsNamedAfterItsPageAndIsUnique() {
        List<String> wiki = WikiTour.steps().stream().map(TourStep::name).toList();
        assertEquals(wiki.size(), new HashSet<>(wiki).size(), "one step per picture file: " + wiki);
        for (String name : wiki) {
            assertTrue(WIKI_PAGES.stream().anyMatch(page -> name.startsWith(page + "-")),
                    name + ".png starts with a wiki page id");
            assertEquals(name + ".png", TourIndex.fileName(name));
        }
        assertTrue(wiki.size() <= 40, "about 5 MB of pictures at most: " + wiki.size() + " pictures");
    }

    @Test
    void everyWikiPictureIsCroppedAtTheOwnersViewAndAtMost1200PixelsWide() {
        for (TourStep<TourContext> step : WikiTour.steps()) {
            assertTrue(step.crop() != null, step.name() + " keeps only the part that helps the reader");
            assertTrue(step.maxWidth() <= TourCrop.MAX_WIDTH, step.name() + " is at most 1200 px wide");
            assertEquals(TourStep.BASE_UI_SIZE, step.uiPercent(), step.name() + " shows the run's base UI size");
        }
    }

    @Test
    void theWikiTourCoversTheWindowsAndEveryToolsSettings() {
        List<String> wiki = WikiTour.steps().stream().map(TourStep::name).toList();
        assertTrue(wiki.containsAll(List.of("select-settings", "terrain-brushes-settings", "shape-brush-settings",
                "generate-settings", "extrude-settings", "fluid-settings", "scatter-settings", "place-settings")),
                "each tool page shows its Tool Settings");
        assertTrue(wiki.containsAll(List.of("selection-operations-window", "clipboard-window", "library-window",
                "history-window", "keys-window")), "each window with a page of its own");
        assertTrue(wiki.containsAll(List.of("screen-and-windows-overview", "screen-and-windows-top-bar",
                "finding-things-command-search")), "the screen, the top bar, Ctrl+K");
    }
}
