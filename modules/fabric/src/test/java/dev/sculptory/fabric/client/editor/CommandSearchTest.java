package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.commands.EditorCommands;
import dev.sculptory.fabric.client.editor.commands.SearchEntry;
import dev.sculptory.fabric.client.editor.hud.CommandSearch;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.hud.HelpSheet;
import dev.sculptory.fabric.client.editor.hud.ToastStack;
import dev.sculptory.fabric.client.editor.input.InputRouter;
import dev.sculptory.fabric.client.editor.presets.PresetStore;
import dev.sculptory.fabric.client.editor.presets.Presets;
import dev.sculptory.fabric.client.editor.settings.form.SettingsForm;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.PopupLayer;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.RecordingGraphics;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import dev.sculptory.fabric.client.editor.ui.widget.CollapsibleSection;
import dev.sculptory.fabric.client.editor.ui.widget.TextInput;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import dev.sculptory.fabric.client.session.StrokeHandle;
import dev.sculptory.fabric.client.session.StrokeParams;
import dev.sculptory.fabric.client.util.AtomicFileStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;

/** Find a command (Ctrl+K), in English, on the editor UI and the mock session. */
class CommandSearchTest {
    /** The real English texts, so the ranking is tested on the names players see. */
    private static final Translator ENGLISH = new Translator() {
        private final JsonObject lang = load();

        private static JsonObject load() {
            try (InputStream in = CommandSearchTest.class.getResourceAsStream("/assets/sculptory/lang/en_us.json")) {
                return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public String translate(String key, Object... args) {
            return lang.has(key) ? String.format(Locale.ROOT, lang.get(key).getAsString(), args) : key;
        }

        @Override
        public boolean has(String key) {
            return lang.has(key);
        }
    };

    @TempDir
    Path dir;

    private final EditorTestRig rig = new EditorTestRig();
    private EditorUi ui;
    private InputRouter router;
    private Presets presets;

    @BeforeEach
    void build() {
        ui = new EditorUi(rig.ctx, rig.controller, rig.platform, rig.actions, EditorUiTest.TEXT, new ToastStack(() -> 0L),
                new EditorUi.Services(ENGLISH, BlockCatalog.EMPTY, icon -> null,
                        () -> new HelpSheet.VanillaKeys("W A S D", "Space", "Shift", "B", "T", "/", "E"),
                        "config/sculptory/editor-keys.json", keymap -> true, new UiScale()));
        rig.controller.setUi(ui);
        rig.confirmer = ui;
        presets = new Presets(new PresetStore(new ConfigFile(dir.resolve(PresetStore.FILE_NAME),
                new AtomicFileStore(PresetStore.MAX_BYTES), p -> {})), rig.ctx, ENGLISH, block -> true);
        presets.load();
        ui.toolSettings().setPresets(presets);
        ui.setEditing(true);
        rig.mode.enter();
        ui.layout(640, 360);
        router = new InputRouter(ui, rig.controller, rig.controller, new InputRouter.Look() {
            @Override
            public boolean isLooking() {
                return false;
            }

            @Override
            public void begin() {}

            @Override
            public void end() {}
        }, new InputRouter.Movement() {
            @Override
            public boolean isMovementKey(int key, int scanCode) {
                return false;
            }

            @Override
            public void press(int key, int scanCode) {}
        }, rig.keymap);
    }

    private CommandSearch search() {
        return ui.commandSearch();
    }

    private void press(int key, int modifiers) {
        router.keyPressed(key, 0, modifiers);
        router.keyReleased(key, 0, modifiers);
    }

    private CommandSearch open() {
        press(GLFW.GLFW_KEY_K, GLFW.GLFW_MOD_CONTROL);
        ui.layout(640, 360);
        assertTrue(search().isOpen());
        return search();
    }

    private List<String> names() {
        return search().results().stream().map(SearchEntry::name).toList();
    }

    private String best(String query) {
        search().setQuery(query);
        return names().isEmpty() ? "" : names().get(0);
    }

    @Test
    void ctrlKOpensTheSearchBoxAtTheTopCentreWithTheKeyboard() {
        open();
        assertTrue(ui.hasPopup());
        PopupLayer.Popup popup = ui.windows().context().popups().popups().get(0);
        assertEquals(Theme.DARK.commandSearchWidth, popup.rect().width());
        assertEquals((640 - Theme.DARK.commandSearchWidth) / 2, popup.rect().x());
        assertTrue(popup.rect().y() > 20, "below the top bar");
        assertInstanceOf(TextInput.class, ui.windows().context().focused(), "typing goes into the box");
        assertEquals(Theme.DARK.commandSearchRows, search().results().size(), "ten results at most");

        press(GLFW.GLFW_KEY_ESCAPE, 0);
        assertFalse(search().isOpen(), "Esc closes it");
        assertTrue(rig.mode.isActive(), "and only it");
    }

    @Test
    void helpFindACommandOpensItToo() {
        ui.commands().run(EditorCommands.FIND_COMMAND);
        assertTrue(search().isOpen());
    }

    @Test
    void theDesignsExamplesComeFirst() {
        open();
        assertEquals("Hollow", best("hol"));
        assertEquals("Library…", best("lib"));
        assertEquals("File", search().results().get(0).category());
        assertEquals("L", search().results().get(0).keyText());
        assertEquals("Library", names().get(1), "View's Library window next");
        search().setQuery("undo a");
        assertTrue(names().stream().noneMatch("Undo anyway"::equals), "Undo anyway only while offered");
        search().close();

        for (ToolId tool : List.of(ToolId.RAISE, ToolId.LOWER)) {
            StrokeHandle stroke = rig.session.beginStroke(tool, new BrushSpec(BrushTool.RAISE, 4, 1f, Falloff.SMOOTH,
                    Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L), StrokeParams.DEFAULT);
            stroke.dab(new Dab(0, 0, 1024, 0, 255));
            stroke.end();
        }
        rig.session.setStepConflicts(4);
        rig.controller.undo();
        open();
        assertEquals("Undo anyway", best("undo a"));
        assertEquals("Raise", best("rai"), "tools");
        assertEquals("Tool Settings", best("tool set"), "windows");
    }

    @Test
    void theArrowsMoveTypingResetsAndEnterRunsAndCloses() {
        open();
        search().setQuery("s");
        assertEquals(0, search().selected());
        press(GLFW.GLFW_KEY_DOWN, 0);
        press(GLFW.GLFW_KEY_DOWN, 0);
        assertEquals(2, search().selected());
        press(GLFW.GLFW_KEY_UP, 0);
        assertEquals(1, search().selected());
        press(GLFW.GLFW_KEY_PAGE_DOWN, 0);
        assertEquals(search().results().size() - 1, search().selected());
        press(GLFW.GLFW_KEY_PAGE_UP, 0);
        assertEquals(0, search().selected());
        press(GLFW.GLFW_KEY_DOWN, 0);
        search().setQuery("hide all");
        assertEquals(0, search().selected(), "typing keeps the first row selected");
        press(GLFW.GLFW_KEY_ENTER, 0);
        assertFalse(search().isOpen());
        assertTrue(ui.windows().isAllHidden(), "Hide all windows ran");
        assertEquals(EditorCommands.HIDE_WINDOWS, ui.commands().recent().get(0));
    }

    @Test
    void aDimmedResultSaysWhyAndStaysOpen() {
        open();
        search().setQuery("fill");
        SearchEntry fill = search().results().get(0);
        assertEquals("Fill", fill.name());
        assertFalse(fill.availability().enabled());
        rig.notices.clear();
        press(GLFW.GLFW_KEY_ENTER, 0);
        assertTrue(search().isOpen());
        assertEquals(List.of("sculptory.command.reason.needs_selection"), rig.noticeKeys());
        assertEquals(List.of(), rig.session.sent());

        search().close();
        rig.ctx.setSelection(new Box(new BlockPos(0, 0, 0), new BlockPos(2, 2, 2)));
        open();
        search().setQuery("fill");
        assertTrue(search().results().get(0).availability().enabled());
        press(GLFW.GLFW_KEY_ENTER, 0);
        assertEquals(1, rig.session.sent().size(), "Fill ran");
    }

    @Test
    void aClickRunsAResult() {
        open();
        search().setQuery("key sheet");
        ui.layout(640, 360);
        Rect popup = ui.windows().context().popups().popups().get(0).rect();
        Theme theme = Theme.DARK;
        double y = popup.y() + 1 + theme.padding + theme.controlHeight + theme.gap + theme.rowHeight / 2.0;
        ui.mouseMoved(popup.x() + 30, y);
        ui.mouseDown(popup.x() + 30, y, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
        ui.mouseUp(popup.x() + 30, y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        assertTrue(ui.isHelpOpen());
        assertFalse(search().isOpen());
    }

    @Test
    void theEmptyBoxSaysWhatItSearchesAndTheListSaysWhenMoreMatch() {
        open();
        TextInput box = (TextInput) ui.windows().context().focused();
        assertEquals("Search commands, tools, settings…", box.placeholder());
        List<String> drawn = drawn();
        assertTrue(drawn.contains("Search commands, tools, settings…"), "shown while the box has the keyboard: " + drawn);
        assertTrue(search().more() > 20, "everything matches nothing typed: " + search().more());
        assertTrue(drawn.contains("+" + search().more() + " more, keep typing"), drawn.toString());

        search().setQuery("e");
        assertEquals(Theme.DARK.commandSearchRows, search().results().size());
        assertTrue(drawn().contains("+" + search().more() + " more, keep typing"));

        search().setQuery("hol");
        assertEquals(0, search().more());
        assertTrue(drawn().stream().noneMatch(text -> text.contains("more, keep typing")), "all shown: no cue");
        assertFalse(drawn().contains("Search commands, tools, settings…"), "gone once something is typed");
    }

    private List<String> drawn() {
        ui.layout(640, 360);
        RecordingGraphics g = new RecordingGraphics();
        ui.render(g, 0, 0, 0, false);
        return g.drawnTexts();
    }

    @Test
    void withNothingTypedRecentCommandsComeFirst() {
        ui.commands().run(EditorCommands.RESET_LAYOUT);
        ui.commands().run("view.ui_size.100");
        open();
        assertEquals(List.of("UI size: 100% (default)", "Reset layout", "Library…"), names().subList(0, 3));
        assertEquals("", search().query());
    }

    @Test
    void theActiveToolsSettingsAreFoundAndRevealedInToolSettings() {
        rig.controller.selectSlot(2);
        ui.layout(640, 360);
        ui.windows().close(EditorWindows.TOOL_SETTINGS);
        open();
        search().setQuery("repeat");
        SearchEntry symmetry = search().results().get(0);
        assertEquals("Repeat each dab", symmetry.name());
        assertEquals("Raise setting", symmetry.category());
        assertEquals("", best("zzz"));
        search().setQuery("radi");
        assertEquals("Radius", names().get(0));
        search().setQuery("repeat");

        press(GLFW.GLFW_KEY_ENTER, 0);
        assertFalse(search().isOpen());
        assertTrue(ui.windows().isOpen(EditorWindows.TOOL_SETTINGS), "Tool Settings opened");
        SettingsForm form = ui.toolSettings().form().orElseThrow();
        Node control = form.control("symmetry").orElseThrow();
        assertSame(control, ui.windows().context().focused(), "the setting has the keyboard");
        CollapsibleSection section = null;
        for (Node node = control; node != null; node = node.parent()) {
            if (node instanceof CollapsibleSection collapsible) {
                section = collapsible;
            }
        }
        assertTrue(section != null && section.isExpanded(), "its section (closed by default) opened");
        Rect window = ui.windows().window(EditorWindows.TOOL_SETTINGS).orElseThrow().rect();
        assertTrue(control.bounds().y() >= window.y() && control.bounds().bottom() <= window.bottom(),
                "scrolled into view: " + control.bounds() + " in " + window);
        assertEquals("setting:raise:symmetry", ui.commands().recent().get(0));
    }

    @Test
    void settingsHiddenNowAreNotListed() {
        rig.controller.selectSlot(2);
        open();
        search().setQuery("layer depth");
        assertTrue(names().stream().noneMatch("Layer depth"::equals), "Raise has no layer depth");
        assertFalse(ui.revealSetting("depth"));
    }

    @Test
    void theActiveToolsPresetsAreFoundAndLoaded() {
        rig.controller.selectSlot(2);
        assertTrue(presets.saveAs(ToolId.RAISE, "Big hill"));
        presets.select(ToolId.RAISE, "");
        assertEquals("", presets.selected(ToolId.RAISE));
        open();
        search().setQuery("big");
        SearchEntry preset = search().results().get(0);
        assertEquals("Preset: Big hill", preset.name());
        assertEquals("Raise preset", preset.category());
        press(GLFW.GLFW_KEY_ENTER, 0);
        assertEquals("Big hill", presets.selected(ToolId.RAISE), "loaded through the preset row's path");

        rig.controller.selectSlot(3);
        open();
        search().setQuery("big");
        assertTrue(names().stream().noneMatch("Preset: Big hill"::equals), "only the active tool's presets");
    }
}
