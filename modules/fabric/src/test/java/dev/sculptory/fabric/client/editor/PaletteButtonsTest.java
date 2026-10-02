package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.palette.BlockPalette;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.hud.HelpSheet;
import dev.sculptory.fabric.client.editor.hud.ToastStack;
import dev.sculptory.fabric.client.editor.palettes.PaletteActions;
import dev.sculptory.fabric.client.editor.palettes.PaletteButtons;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tools.brush.BrushSettings;
import dev.sculptory.fabric.client.editor.tools.brush.TerrainBrushTool;
import dev.sculptory.fabric.client.editor.tools.scatter.ScatterTool;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import dev.sculptory.fabric.client.editor.ui.widget.TextInput;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Permissions;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.server.engine.Perm;
import java.util.EnumSet;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/** Save palette… and Load palette… driven through the editor UI on the mock session, as a player would. */
class PaletteButtonsTest {
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

    private final EditorTestRig rig = new EditorTestRig();
    private EditorUi ui;
    private PaletteButtons buttons;

    private BrushSettings brush(ToolId id) {
        return ((TerrainBrushTool) rig.ctx.tools().get(id).orElseThrow()).settings();
    }

    @BeforeEach
    void open() {
        ui = new EditorUi(rig.ctx, rig.controller, rig.platform, rig.actions, TEXT, new ToastStack(() -> 0L),
                new EditorUi.Services(Translator.KEYS, BlockCatalog.EMPTY, icon -> null,
                        () -> new HelpSheet.VanillaKeys("W A S D", "Space", "Shift", "B", "T", "/", "E"),
                        "config/sculptory/editor-keys.json", keymap -> true, new UiScale()));
        rig.controller.setUi(ui);
        ui.setEditing(true);
        rig.mode.enter();
        PaletteActions actions = new PaletteActions(new PaletteActions.Host() {
            @Override
            public Optional<EditorSession> session() {
                return Optional.of(rig.session);
            }

            @Override
            public SettingsValues settings(ToolId id) {
                return rig.ctx.settings(id);
            }

            @Override
            public void updateSettings(ToolId id, SettingsValues values) {
                rig.ctx.updateSettings(id, values);
            }

            @Override
            public boolean selectTool(ToolId id) {
                return rig.controller.selectTool(id);
            }

            @Override
            public void notify(Notice notice) {
                rig.notices.add(notice);
            }

            @Override
            public Optional<StateSpace> states() {
                return Optional.of(rig.states);
            }
        }, brush(ToolId.PAINT), brush(ToolId.PALETTE),
                (ScatterTool) rig.ctx.tools().get(ToolId.SCATTER).orElseThrow());
        buttons = new PaletteButtons(actions, () -> Optional.of(rig.session), ui.windows()::context, Translator.KEYS);
        frame();
    }

    private void frame() {
        ui.layout(640, 360);
    }

    /** Types {@code text} into the open prompt and presses Enter; returns whether the prompt closed. */
    private boolean typeAndSubmit(String text) {
        assertTrue(ui.hasPopup(), "the prompt is open");
        TextInput input = assertInstanceOf(TextInput.class, ui.windows().context().focused(), "the field has focus");
        input.setText(text);
        assertTrue(ui.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0));
        frame();
        return !ui.hasPopup();
    }

    @Test
    void saveAsksForAPathAndSavesTheToolsBlocks() {
        rig.ctx.updateSettings(ToolId.PAINT, rig.ctx.settings(ToolId.PAINT).with(brush(ToolId.PAINT).material,
                BlockDescriptor.parse("minecraft:oak_log[axis=z]")));
        PaletteButtons.Buttons paint = buttons.row(ToolId.PAINT);
        paint.refresh();
        assertTrue(paint.saveButton().isEnabled() && paint.loadButton().isEnabled());
        paint.saveButton().click();
        frame();
        assertFalse(typeAndSubmit("../escape"), "a path the server would refuse keeps the prompt open");
        assertTrue(typeAndSubmit("palettes/logs"));
        assertEquals(BlockPalette.of("minecraft:oak_log[axis=z]", 1),
                rig.session.palettes().get("palettes/logs.palette.json"));
        assertTrue(rig.notices.contains(Notice.of(Notice.Level.SUCCESS, "sculptory.palette.saved",
                "palettes/logs.palette.json", "1")), rig.notices.toString());

        // The Load picker opens in the folder just used.
        paint.loadButton().click();
        frame();
        assertTrue(ui.hasPopup(), "the picker is open");
    }

    @Test
    void theButtonsAreOffWithoutTheLibrary() {
        rig.session.setPermissions(new Permissions(Perm.mask(EnumSet.of(Perm.USE, Perm.BRUSH)), Limits.DEFAULTS));
        PaletteButtons.Buttons palette = buttons.row(ToolId.PALETTE);
        palette.refresh();
        assertFalse(palette.saveButton().isEnabled());
        assertFalse(palette.loadButton().isEnabled());
    }

    @Test
    void theToolSettingsWindowShowsTheRowAboveThePaintSettings() {
        ui.toolSettings().addPanel(ToolId.PAINT, buttons.panel(ToolId.PAINT));
        assertTrue(rig.controller.selectTool(ToolId.PAINT));
        frame();
        assertTrue(ui.toolSettings().form().isPresent(), "the generated settings are still there");
    }
}
