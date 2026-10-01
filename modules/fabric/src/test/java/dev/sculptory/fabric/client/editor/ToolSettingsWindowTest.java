package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.hud.HelpSheet;
import dev.sculptory.fabric.client.editor.hud.ToastStack;
import dev.sculptory.fabric.client.editor.presets.PresetBar;
import dev.sculptory.fabric.client.editor.presets.PresetStore;
import dev.sculptory.fabric.client.editor.presets.Presets;
import dev.sculptory.fabric.client.editor.settings.form.SettingsForm;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tools.select.SelectSettings;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.CollapsibleSection;
import dev.sculptory.fabric.client.editor.ui.widget.ScrollPane;
import dev.sculptory.fabric.client.editor.ui.widget.Slider;
import dev.sculptory.fabric.client.editor.ui.window.Corner;
import dev.sculptory.fabric.client.editor.ui.window.LayoutState;
import dev.sculptory.fabric.client.editor.ui.window.Window;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import dev.sculptory.fabric.client.editor.windows.SectionStateStore;
import dev.sculptory.fabric.client.util.AtomicFileStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;

/** The Tool Settings window across tools: remembered sections and scroll, reset, reveal, the preset row's wrapping. */
class ToolSettingsWindowTest {
    private static final String MASK = "sculptory.setting.brush.mask";
    private static final String SYMMETRY = "sculptory.setting.brush.symmetry_section";
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
    /** English for the preset buttons, so their widths are real; every other key stays a key. */
    private static final Map<String, String> ENGLISH = Map.of(
            "sculptory.preset.save_as", "Save as…",
            "sculptory.preset.rename", "Rename",
            "sculptory.preset.delete", "Delete",
            "sculptory.preset.save", "Save");
    private static final Translator TRANSLATOR = new Translator() {
        @Override
        public String translate(String key, Object... args) {
            return ENGLISH.getOrDefault(key, Translator.KEYS.translate(key, args));
        }

        @Override
        public boolean has(String key) {
            return ENGLISH.containsKey(key);
        }
    };

    @TempDir
    Path dir;

    private final EditorTestRig rig = new EditorTestRig();
    private EditorUi ui;
    private Presets presets;

    /** The editor UI with sections remembered in the temporary directory's file, entered with Select active. */
    private void open() {
        ui = new EditorUi(rig.ctx, rig.controller, rig.platform, rig.actions, TEXT, new ToastStack(() -> 0L),
                new EditorUi.Services(TRANSLATOR, BlockCatalog.EMPTY, icon -> null,
                        () -> new HelpSheet.VanillaKeys("W A S D", "Space", "Shift", "B", "T", "/", "E"),
                        "config/sculptory/editor-keys.json", keymap -> true, new UiScale()));
        rig.controller.setUi(ui);
        presets = new Presets(new PresetStore(new ConfigFile(dir.resolve(PresetStore.FILE_NAME),
                new AtomicFileStore(PresetStore.MAX_BYTES), p -> {})), rig.ctx, TRANSLATOR, block -> true);
        presets.load();
        ui.toolSettings().setPresets(presets);
        ui.toolSettings().setSectionStates(sections());
        ui.setEditing(true);
        rig.mode.enter();
        frame();
    }

    private SectionStateStore sections() {
        SectionStateStore store = SectionStateStore.of(new ConfigFile(dir.resolve(SectionStateStore.FILE_NAME),
                new AtomicFileStore(), p -> {}));
        store.load();
        return store;
    }

    private void frame() {
        ui.layout(640, 800);
        ui.toolSettings().refresh();
        ui.layout(640, 800);
    }

    private void select(ToolId tool) {
        assertTrue(rig.controller.selectTool(tool));
        frame();
    }

    private SettingsForm form() {
        return ui.toolSettings().form().orElseThrow();
    }

    private ScrollPane pane() {
        return ui.toolSettings().pane().orElseThrow();
    }

    private Rect content() {
        Window window = ui.windows().window(EditorWindows.TOOL_SETTINGS).orElseThrow();
        return window.contentRect(Theme.DARK);
    }

    private void resize(int width, int height) {
        ui.windows().restore(new LayoutState(List.of(new LayoutState.WindowState(EditorWindows.TOOL_SETTINGS, true,
                false, Corner.TOP_RIGHT, 4, 20, width, height))));
        frame();
    }

    @Test
    void sectionsStayAsTheyWereLeftPerToolAlsoAfterARestart() {
        open();
        select(ToolId.RAISE);
        CollapsibleSection mask = form().section(MASK).orElseThrow();
        assertFalse(mask.isExpanded(), "closed by default");
        mask.toggle();
        select(ToolId.LOWER);
        assertFalse(form().section(MASK).orElseThrow().isExpanded(), "another tool keeps its own");
        select(ToolId.RAISE);
        assertTrue(form().section(MASK).orElseThrow().isExpanded(), "Raise's Mask is still open");

        // A restart: the store is read again from the file, and the form built from it opens the section.
        SectionStateStore restarted = sections();
        assertEquals(java.util.Optional.of(true), restarted.expanded(ToolId.RAISE, MASK), "saved in the file");
        ui.toolSettings().setSectionStates(restarted);
        select(ToolId.LOWER);
        select(ToolId.RAISE);
        assertTrue(form().section(MASK).orElseThrow().isExpanded(), "remembered across the restart");
        assertFalse(form().section(SYMMETRY).orElseThrow().isExpanded());
    }

    @Test
    void aSettingRevealedBeforeTheFormIsBuiltIsRevealedWhenItIs() {
        open();
        rig.controller.selectTool(ToolId.RAISE);
        assertTrue(ui.toolSettings().revealSetting("symmetry"), "Raise has it; its form comes at the next frame");
        frame();
        assertTrue(form().section(SYMMETRY).orElseThrow().isExpanded());
        assertSame(form().control("symmetry").orElseThrow(), ui.windows().context().focused());
    }

    @Test
    void theScrollPositionIsKeptPerToolWhileYouPlay() {
        open();
        resize(170, 120);
        select(ToolId.RAISE);
        form().section(MASK).orElseThrow().toggle();
        frame();
        assertTrue(pane().scroll().isScrollable(), "the open Mask section is taller than the window");
        pane().scroll().setOffset(40);
        select(ToolId.SMOOTH);
        assertEquals(0, pane().scroll().offset(), "a tool not scrolled yet starts at the top");
        select(ToolId.RAISE);
        assertEquals(40, pane().scroll().offset(), "Raise is where it was left");
    }

    @Test
    void revealingASettingOpensItsSectionScrollsToItAndFocusesIt() {
        open();
        resize(170, 120);
        select(ToolId.RAISE);
        assertFalse(form().section(SYMMETRY).orElseThrow().isExpanded());
        assertTrue(ui.toolSettings().revealSetting("symmetry"));
        frame();
        assertTrue(form().section(SYMMETRY).orElseThrow().isExpanded());
        assertSame(form().control("symmetry").orElseThrow(), ui.windows().context().focused());
        Rect control = form().control("symmetry").orElseThrow().bounds();
        Rect view = content();
        assertTrue(control.y() >= view.y() && control.bottom() <= view.bottom(), control + " in view " + view);

        assertFalse(ui.toolSettings().revealSetting("nope"));
        assertFalse(ui.toolSettings().revealSetting("mask.exact"), "hidden until a mask block is listed");
    }

    @Test
    void resettingASettingGoesThroughTheNormalChangeSoPresetsFollow() {
        open();
        PresetBar bar = ui.toolSettings().presetBar().orElseThrow();
        Slider thickness = (Slider) form().control(SelectSettings.THICKNESS.key()).orElseThrow();
        thickness.keyPressed(ui.windows().context(), GLFW.GLFW_KEY_RIGHT, 0, 0);
        frame();
        assertEquals(2, rig.ctx.settings(ToolId.SELECT).get(SelectSettings.THICKNESS));
        assertEquals("sculptory.preset.modified", bar.dropdown().suffix());
        Button reset = form().resetButton(SelectSettings.THICKNESS.key()).orElseThrow();
        assertTrue(reset.isVisible());

        reset.click();
        frame();
        assertEquals(1, rig.ctx.settings(ToolId.SELECT).get(SelectSettings.THICKNESS), "the tool has the default");
        assertEquals("", bar.dropdown().suffix(), "no longer modified");
        assertFalse(reset.isVisible());
    }

    @Test
    void aNarrowWindowWrapsThePresetButtonsInsteadOfCuttingThem() {
        open();
        resize(130, 300);
        PresetBar bar = ui.toolSettings().presetBar().orElseThrow();
        for (Button button : List.of(bar.saveAsButton(), bar.renameButton(), bar.deleteButton())) {
            int needed = TEXT.width(button.text()) + 2 * Theme.DARK.controlPaddingX;
            assertTrue(button.bounds().width() >= needed, button.text() + " is whole: " + button.bounds());
            assertTrue(button.bounds().right() <= content().right(), button.text() + " inside the window");
        }
        assertTrue(bar.deleteButton().bounds().y() > bar.saveAsButton().bounds().y(), "Delete went to a second line");
    }
}
