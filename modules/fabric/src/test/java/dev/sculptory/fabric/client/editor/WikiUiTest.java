package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.commands.CommandMenu;
import dev.sculptory.fabric.client.editor.commands.EditorCommands;
import dev.sculptory.fabric.client.editor.commands.SearchEntry;
import dev.sculptory.fabric.client.editor.hud.CommandSearch;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.hud.HelpSheet;
import dev.sculptory.fabric.client.editor.hud.ToastStack;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.RecordingGraphics;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.MenuItem;
import dev.sculptory.fabric.client.editor.ui.window.Window;
import dev.sculptory.fabric.client.editor.wiki.DirectoryWikiSource;
import dev.sculptory.fabric.client.editor.wiki.FakePictures;
import dev.sculptory.fabric.client.editor.wiki.PictureOverlay;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import dev.sculptory.fabric.client.editor.windows.WikiWindow;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/** The wiki in the editor: Help > Wiki, View > Wiki, Ctrl+K's pages, the ? in Tool Settings and {@code openWiki}. */
class WikiUiTest {
    private final EditorTestRig rig = new EditorTestRig();
    private final FakePictures pictures = new FakePictures().with("images/constructs-settings.png", 300, 100);
    private final List<String> websites = new ArrayList<>();
    private EditorUi ui;

    @BeforeEach
    void build() {
        ui = new EditorUi(rig.ctx, rig.controller, rig.platform, rig.actions, EditorUiTest.TEXT, new ToastStack(() -> 0L),
                new EditorUi.Services(English.INSTANCE, BlockCatalog.EMPTY, icon -> null,
                        () -> new HelpSheet.VanillaKeys("W A S D", "Space", "Shift", "B", "T", "/", "E"),
                        "config/sculptory/editor-keys.json", keymap -> true, new UiScale()));
        rig.controller.setUi(ui);
        rig.confirmer = ui;
        ui.setWiki(new DirectoryWikiSource(DirectoryWikiSource.sample()), pictures, websites::add);
        ui.setEditing(true);
        rig.mode.enter();
        ui.layout(853, 494);
    }

    private void frame() {
        ui.layout(853, 494);
        ui.render(new RecordingGraphics(), 0, 0, 0, false);
    }

    private Window wikiWindow() {
        return ui.windows().window(EditorWindows.WIKI).orElseThrow();
    }

    private Window top() {
        List<Window> all = ui.windows().windows();
        return all.get(all.size() - 1);
    }

    @Test
    void helpWikiOpensTheWindowAtHomeAndViewWikiTogglesIt() {
        assertFalse(ui.windows().isOpen(EditorWindows.WIKI), "closed by default");
        List<String> help = ui.commands().menuItems(CommandMenu.HELP).stream().map(MenuItem::label).toList();
        assertTrue(help.contains("Wiki"), help.toString());
        assertTrue(ui.commands().run(EditorCommands.WIKI).enabled());
        frame();
        assertTrue(ui.windows().isOpen(EditorWindows.WIKI));
        assertEquals(Optional.of("home"), ui.wikiWindow().currentPage());
        assertEquals("Wiki", English.INSTANCE.translate(EditorWindows.titleKey(EditorWindows.WIKI)));
        MenuItem view = ui.commands().menuItems(CommandMenu.VIEW).stream().filter(item -> item.label().equals("Wiki"))
                .findFirst().orElseThrow();
        assertTrue(view.isChecked(), "View > Wiki is checked while the window shows");

        ui.commands().run(EditorCommands.VIEW_WIKI);
        assertFalse(ui.windows().isOpen(EditorWindows.WIKI), "View > Wiki closes it again");
        ui.commands().run(EditorCommands.VIEW_WIKI);
        frame();
        assertEquals(Optional.of("home"), ui.wikiWindow().currentPage(), "the page shown last");
    }

    @Test
    void openWikiBringsTheWindowToTheFrontAtThePageAndSection() {
        ui.toggleWindow(EditorWindows.HISTORY);
        frame();
        ui.windows().setCollapsed(EditorWindows.WIKI, true);
        ui.openWiki("constructs", "tables");
        frame();
        assertTrue(ui.windows().isOpen(EditorWindows.WIKI));
        assertFalse(wikiWindow().isCollapsed());
        assertEquals(wikiWindow(), top());
        assertEquals(Optional.of("constructs"), ui.wikiWindow().currentPage());
        assertTrue(ui.wikiWindow().pagePane().scroll().offset() > 0, "scrolled to the tables");

        ui.toggleWindowsHidden();
        ui.openWiki(null, null);
        assertFalse(ui.windows().isAllHidden(), "hidden windows show again");
        assertEquals(Optional.of("constructs"), ui.wikiWindow().currentPage(), "no page: the one shown last");
    }

    @Test
    void theCommandSearchListsEveryPageAndOpensIt() {
        ui.openCommandSearch();
        CommandSearch search = ui.commandSearch();
        search.setQuery("shape brush");
        List<SearchEntry> results = search.results();
        assertEquals("Wiki: Shape brush", results.get(0).name(), results.stream().map(SearchEntry::name).toList()
                .toString());
        assertEquals("Help", results.get(0).category());
        assertTrue(search.runSelected());
        frame();
        assertEquals(Optional.of("shape-brush"), ui.wikiWindow().currentPage());
        assertEquals("wiki:shape-brush", ui.commands().recent().get(0));

        ui.openCommandSearch();
        search.setQuery("wiki");
        List<String> names = search.results().stream().map(SearchEntry::name).toList();
        assertTrue(names.contains("Wiki"), "Help > Wiki and View > Wiki: " + names);
        assertTrue(names.containsAll(List.of("Wiki: Sample wiki", "Wiki: Getting started", "Wiki: Every construct",
                "Wiki: Terrain brushes", "Wiki: An orphan page")) || search.more() > 0, names.toString());
    }

    @Test
    void theQuestionMarkInToolSettingsOpensTheToolsPage() {
        assertTrue(rig.controller.selectTool(ToolId.SHAPE));
        frame();
        Button help = find(ui.windows().window(EditorWindows.TOOL_SETTINGS).orElseThrow().content());
        assertFalse(help.isFocusable(), "Tab and F6 go on to the settings");
        assertEquals("This tool's page in the wiki", help.tooltip());
        help.click();
        frame();
        assertEquals(Optional.of("shape-brush"), ui.wikiWindow().currentPage());

        assertTrue(rig.controller.selectTool(ToolId.PAINT));
        frame();
        find(ui.windows().window(EditorWindows.TOOL_SETTINGS).orElseThrow().content()).click();
        frame();
        assertEquals(Optional.of("terrain-brushes"), ui.wikiWindow().currentPage());
        assertTrue(ui.wikiWindow().pagePane().scroll().offset() > 0
                || ui.wikiWindow().pagePane().scroll().maxOffset() == 0, "at Paint");
    }

    @Test
    void closingTheWindowFreesItsPictures() {
        ui.openWiki("constructs", "pictures");
        frame();
        frame();
        assertEquals(1, pictures.loads);
        ui.toggleWindow(EditorWindows.WIKI);
        frame();
        assertTrue(pictures.loaded.isEmpty());
        ui.openWiki(null, null);
        frame();
        frame();
        assertEquals(2, pictures.loads, "loaded again when the window shows it");
        ui.setEditing(false);
        assertTrue(pictures.loaded.isEmpty(), "and freed when the editor closes");
    }

    @Test
    void itOpensLargerBetweenSelectionAndToolSettingsAtUi50AndInsideTheWorkAreaAt100() {
        // The reference screen: 427x249 GUI pixels (GUI scale 6), 854x498 units at UI 50%.
        for (int percent : List.of(50, 100)) {
            ui.uiScale().set(percent);
            ui.windows().resetLayout();
            ui.layout(427, 249);
            ui.openWiki(null, null);
            ui.layout(427, 249);
            ui.render(new RecordingGraphics().withGuiScale(6), 0, 0, 0, false);
            Rect wiki = wikiWindow().rect();
            Rect work = ui.windows().workArea();
            String at = " at " + percent + "%: " + wiki + " in " + work;
            assertTrue(wiki.x() >= work.x() && wiki.y() >= work.y() && wiki.right() <= work.right()
                    && wiki.bottom() <= work.bottom(), "inside the work area" + at);
            if (percent == 50) {
                assertEquals(new Rect(wiki.x(), wiki.y(), WikiWindow.WIDTH, WikiWindow.HEIGHT), wiki, "its full size");
                Rect selection = ui.windows().window(EditorWindows.SELECTION).orElseThrow().rect();
                Rect toolSettings = ui.windows().window(EditorWindows.TOOL_SETTINGS).orElseThrow().rect();
                assertFalse(wiki.intersects(selection), "beside Selection " + selection + at);
                assertFalse(wiki.intersects(toolSettings), "beside Tool Settings " + toolSettings + at);
                assertEquals(4.0 / 3, ui.wikiWindow().pageView().scale().scale(), 1e-5,
                        "the page's text a third larger, from the frame's GUI scale and UI size");
            } else {
                assertEquals(1.0, ui.wikiWindow().pageView().scale().scale(), 1e-5);
            }
            ui.windows().close(EditorWindows.WIKI);
        }
    }

    @Test
    void escOrAClickClosesTheFullSizePictureAndThePageList() {
        ui.openWiki("constructs", "pictures");
        frame();
        UiContext windowsCtx = ui.windows().context();
        Rect thumbnail = ui.wikiWindow().pageView().pictureBounds(windowsCtx).get(0);
        double x = thumbnail.x() + thumbnail.width() / 2.0;
        double y = thumbnail.y() + thumbnail.height() / 2.0;
        assertTrue(ui.mouseDown(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
        ui.mouseUp(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        frame();
        assertTrue(windowsCtx.popups().isOpen(), "shown full size");
        assertInstanceOf(PictureOverlay.class, windowsCtx.popups().popups().get(0).content());
        assertTrue(ui.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0));
        assertFalse(windowsCtx.popups().isOpen(), "Esc closes it");
        assertTrue(ui.windows().isOpen(EditorWindows.WIKI), "and only it");

        assertTrue(ui.mouseDown(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
        ui.mouseUp(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        frame();
        assertTrue(windowsCtx.popups().isOpen());
        assertTrue(ui.mouseDown(4, 470, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0), "a click on the dimmed editor is the overlay's");
        ui.mouseUp(4, 470, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        assertFalse(windowsCtx.popups().isOpen(), "and closes it");

        Rect pages = ui.wikiWindow().pagesButton().bounds();
        ui.mouseDown(pages.x() + 2, pages.y() + 2, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
        ui.mouseUp(pages.x() + 2, pages.y() + 2, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        frame();
        assertTrue(ui.wikiWindow().isListShown(), "Pages drops the list down");
        assertTrue(ui.windows().hasKeyboardFocus(), "its search box has the keyboard");
        assertTrue(ui.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0));
        assertFalse(ui.wikiWindow().isListShown(), "Esc closes it");
        assertTrue(ui.windows().isOpen(EditorWindows.WIKI));
    }

    /** The ? button in a Tool Settings tree. */
    private static Button find(Node node) {
        if (node instanceof Button button && button.text().equals("?")) {
            return button;
        }
        for (Node child : node.children()) {
            try {
                return find(child);
            } catch (AssertionError ignored) {
                // not in this branch
            }
        }
        throw new AssertionError("No ? in Tool Settings");
    }
}
