package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.hud.HelpSheet;
import dev.sculptory.fabric.client.editor.hud.ToastStack;
import dev.sculptory.fabric.client.editor.presets.PresetBar;
import dev.sculptory.fabric.client.editor.presets.PresetStore;
import dev.sculptory.fabric.client.editor.presets.Presets;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tools.select.SelectSettings;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import dev.sculptory.fabric.client.editor.ui.widget.Dropdown;
import dev.sculptory.fabric.client.editor.ui.widget.TextInput;
import dev.sculptory.fabric.client.editor.ui.window.Window;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import dev.sculptory.fabric.client.util.AtomicFileStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;

/** The Tool Settings window's preset row, driven like a player would through the editor UI on the mock session. */
class PresetBarTest {
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

    @TempDir
    Path dir;

    private final EditorTestRig rig = new EditorTestRig();
    private EditorUi ui;
    private Presets presets;

    /** The editor UI with presets from {@code fileText} (none: no file), entered with the Select tool active. */
    private void open(String fileText) throws IOException {
        Path file = dir.resolve(PresetStore.FILE_NAME);
        if (fileText != null) {
            Files.writeString(file, fileText);
        }
        ui = new EditorUi(rig.ctx, rig.controller, rig.platform, rig.actions, TEXT, new ToastStack(() -> 0L),
                new EditorUi.Services(Translator.KEYS, BlockCatalog.EMPTY, icon -> null,
                        () -> new HelpSheet.VanillaKeys("W A S D", "Space", "Shift", "B", "T", "/", "E"),
                        "config/sculptory/editor-keys.json", keymap -> true, new UiScale()));
        rig.controller.setUi(ui);
        presets = new Presets(new PresetStore(new ConfigFile(file, new AtomicFileStore(PresetStore.MAX_BYTES), p -> {})),
                rig.ctx, Translator.KEYS, block -> true);
        presets.load();
        ui.toolSettings().setPresets(presets);
        ui.setEditing(true);
        rig.mode.enter();
        frame();
    }

    /** What a frame does for the window: lay out, then follow the state. */
    private void frame() {
        ui.layout(640, 360);
        ui.toolSettings().refresh();
        ui.layout(640, 360);
    }

    private PresetBar bar() {
        return ui.toolSettings().presetBar().orElseThrow();
    }

    /** Types {@code name} into the open name prompt and presses Enter. */
    private void typeAndSubmit(String name) {
        assertTrue(ui.hasPopup(), "the name prompt is open");
        TextInput input = assertInstanceOf(TextInput.class, ui.windows().context().focused(), "the field has focus");
        input.setText(name);
        assertTrue(ui.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0));
        frame();
    }

    @Test
    void theRowStartsAtDefaultWithOnlySaveAsAvailable() throws IOException {
        open(null);
        PresetBar bar = bar();
        assertEquals(List.of(""), bar.dropdown().options());
        assertEquals("", bar.dropdown().selected());
        assertFalse(bar.saveButton().isEnabled(), "Default can't be overwritten");
        assertEquals("sculptory.preset.save.default", bar.saveButton().tooltip());
        assertTrue(bar.saveAsButton().isEnabled());
        assertFalse(bar.renameButton().isEnabled());
        assertFalse(bar.deleteButton().isEnabled());
        assertEquals("", bar.dropdown().suffix());
        assertEquals("", bar.notice());
    }

    @Test
    void theRowFitsTheDefaultWindowAboveTheForm() throws IOException {
        open(null);
        Window window = ui.windows().window(EditorWindows.TOOL_SETTINGS).orElseThrow();
        Rect content = window.contentRect(ui.windows().context().theme());
        Rect row = bar().node().bounds();
        assertTrue(row.x() >= content.x() && row.right() <= content.right(), row + " inside " + content);
        assertTrue(bar().dropdown().bounds().width() > bar().saveButton().bounds().width(), "the dropdown takes the room");
        Rect form = ui.toolSettings().form().orElseThrow().node().bounds();
        assertTrue(row.bottom() <= form.y(), "presets come before the settings");
    }

    @Test
    void saveAsNamesANewPresetThatIsThenSelected() throws IOException {
        open(null);
        rig.ctx.updateSettings(ToolId.SELECT, rig.ctx.settings(ToolId.SELECT).with(SelectSettings.THICKNESS, 4));
        frame();
        assertEquals("sculptory.preset.modified", bar().dropdown().suffix(), "the settings differ from Default");

        bar().saveAsButton().click();
        typeAndSubmit("  Thick walls ");
        assertFalse(ui.hasPopup());
        PresetBar bar = bar();
        assertEquals(List.of("", "Thick walls"), bar.dropdown().options());
        assertEquals("Thick walls", bar.dropdown().selected());
        assertEquals("", bar.dropdown().suffix());
        assertTrue(bar.saveButton().isEnabled());
        assertTrue(bar.renameButton().isEnabled());
        assertTrue(bar.deleteButton().isEnabled());
        assertTrue(Files.readString(dir.resolve(PresetStore.FILE_NAME)).contains("Thick walls"));
    }

    @Test
    void theNamePromptRefusesATakenName() throws IOException {
        open(null);
        bar().saveAsButton().click();
        typeAndSubmit("Walls");
        bar().saveAsButton().click();
        typeAndSubmit("walls");
        assertTrue(ui.hasPopup(), "a taken name keeps the prompt open");
        assertTrue(ui.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0));
        assertFalse(ui.hasPopup());
        assertEquals(List.of("Walls"), presets.names(ToolId.SELECT));
    }

    @Test
    void pickingAPresetLoadsItAndPickingItAgainDropsChanges() throws IOException {
        open(null);
        rig.ctx.updateSettings(ToolId.SELECT, rig.ctx.settings(ToolId.SELECT).with(SelectSettings.THICKNESS, 6));
        bar().saveAsButton().click();
        typeAndSubmit("Six");
        bar().dropdown().pick("");
        frame();
        assertEquals(1, ui.toolSettings().form().orElseThrow().values().get(SelectSettings.THICKNESS),
                "Default shows in the form");

        bar().dropdown().pick("Six");
        frame();
        assertEquals(6, ui.toolSettings().form().orElseThrow().values().get(SelectSettings.THICKNESS));
        rig.ctx.updateSettings(ToolId.SELECT, rig.ctx.settings(ToolId.SELECT).with(SelectSettings.THICKNESS, 9));
        frame();
        assertEquals("sculptory.preset.modified", bar().dropdown().suffix());
        bar().dropdown().pick("Six");
        frame();
        assertEquals(6, rig.ctx.settings(ToolId.SELECT).get(SelectSettings.THICKNESS));
        assertEquals("", bar().dropdown().suffix());
    }

    @Test
    void theArrowKeysOpenTheListInsteadOfLoadingAPreset() throws IOException {
        open(null);
        bar().saveAsButton().click();
        typeAndSubmit("Walls");
        rig.ctx.updateSettings(ToolId.SELECT, rig.ctx.settings(ToolId.SELECT).with(SelectSettings.THICKNESS, 7));
        frame();
        Dropdown<String> dropdown = bar().dropdown();
        ui.windows().context().setFocus(dropdown);
        assertTrue(ui.keyPressed(GLFW.GLFW_KEY_UP, 0, 0));
        assertTrue(dropdown.isOpen(), "Up opens the list");
        assertEquals("Walls", presets.selected(ToolId.SELECT), "nothing was loaded");
        assertEquals(7, rig.ctx.settings(ToolId.SELECT).get(SelectSettings.THICKNESS), "the changes are still there");
    }

    @Test
    void aNewSelectionKeepsTheSameRow() throws IOException {
        open(null);
        bar().saveAsButton().click();
        typeAndSubmit("Walls");
        Dropdown<String> dropdown = bar().dropdown();
        ui.windows().context().setFocus(dropdown);
        dropdown.pick("");
        frame();
        assertSame(dropdown, bar().dropdown(), "only the selection changed: the row is updated, not rebuilt");
        assertSame(dropdown, ui.windows().context().focused(), "so it keeps focus");
        assertEquals("", dropdown.selected());
        assertFalse(bar().saveButton().isEnabled());
        assertFalse(bar().deleteButton().isEnabled());
    }

    @Test
    void renameStartsFromTheCurrentName() throws IOException {
        open(null);
        bar().saveAsButton().click();
        typeAndSubmit("Walls");
        bar().renameButton().click();
        TextInput input = assertInstanceOf(TextInput.class, ui.windows().context().focused());
        assertEquals("Walls", input.text());
        typeAndSubmit("Stone walls");
        assertEquals(List.of("", "Stone walls"), bar().dropdown().options());
        assertEquals("Stone walls", bar().dropdown().selected());
    }

    @Test
    void deleteAsksInPlaceBeforeDeleting() throws IOException {
        open(null);
        bar().saveAsButton().click();
        typeAndSubmit("Walls");
        PresetBar bar = bar();

        bar.deleteButton().click();
        assertFalse(ui.hasPopup(), "not a modal");
        assertTrue(bar.confirmDeleteButton().isPresent());
        frame();
        assertEquals(List.of("Walls"), presets.names(ToolId.SELECT), "nothing deleted yet");

        bar.confirmDeleteButton().orElseThrow().click();
        frame();
        assertEquals(List.of(), presets.names(ToolId.SELECT));
        assertEquals(List.of(""), bar().dropdown().options());
        assertTrue(bar().confirmDeleteButton().isEmpty());
    }

    @Test
    void cancellingADeleteBringsTheButtonsBack() throws IOException {
        open(null);
        bar().saveAsButton().click();
        typeAndSubmit("Walls");
        bar().deleteButton().click();
        assertTrue(bar().confirmDeleteButton().isPresent());
        bar().dropdown().pick("");
        frame();
        assertTrue(bar().confirmDeleteButton().isEmpty(), "choosing another preset drops the question");
        assertEquals(List.of("Walls"), presets.names(ToolId.SELECT));
    }

    @Test
    void eachToolHasItsOwnRow() throws IOException {
        open(null);
        bar().saveAsButton().click();
        typeAndSubmit("Walls");
        PresetBar select = bar();
        rig.controller.selectSlot(2);
        frame();
        assertNotSame(select, bar());
        assertEquals(List.of(""), bar().dropdown().options(), "Raise has no presets yet");
        rig.controller.selectSlot(1);
        frame();
        assertEquals(List.of("", "Walls"), bar().dropdown().options());
    }

    @Test
    void anUnusableFileShowsAReadOnlyNotice() throws IOException {
        open("{\"version\": 99, \"tools\": {}}");
        PresetBar bar = bar();
        assertEquals("sculptory.preset.read_only[" + PresetStore.FILE_NAME + "]", bar.notice());
        assertFalse(bar.saveAsButton().isEnabled());
        assertFalse(bar.saveButton().isEnabled());
        assertEquals("{\"version\": 99, \"tools\": {}}", Files.readString(dir.resolve(PresetStore.FILE_NAME)));
    }
}
