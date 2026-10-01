package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.hud.HelpSheet;
import dev.sculptory.fabric.client.editor.hud.ToastStack;
import dev.sculptory.fabric.client.editor.tool.ToolRegistry;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The small hooks the screenshot tour drives the editor UI through. */
class EditorUiTourHooksTest {
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

    @BeforeEach
    void build() {
        ui = new EditorUi(rig.ctx, rig.controller, rig.platform, rig.actions, TEXT,
                new ToastStack(() -> 0L), new EditorUi.Services(Translator.KEYS, BlockCatalog.EMPTY, icon -> null,
                        () -> new HelpSheet.VanillaKeys("W A S D", "Space", "Shift", "B", "T", "/", "E"),
                        "config/sculptory/editor-keys.json", keymap -> true, new UiScale()));
        rig.controller.setUi(ui);
        ui.setEditing(true);
        rig.mode.enter();
        ui.layout(640, 360);
    }

    @Test
    void eachTopBarMenuOpensAsAPopup() {
        for (String menu : new String[] {EditorUi.MENU_FILE, EditorUi.MENU_EDIT, EditorUi.MENU_SELECTION,
                EditorUi.MENU_TOOLS, EditorUi.MENU_VIEW, EditorUi.MENU_HELP, EditorUi.MENU_VIEW_UI_SIZE,
                EditorUi.MENU_UI_SIZE, EditorUi.MENU_BLOCK}) {
            ui.windows().context().popups().closeAll();
            ui.openMenu(menu);
            assertTrue(ui.windows().context().popups().isOpen(), menu);
        }
        assertThrows(IllegalArgumentException.class, () -> ui.openMenu("windows"));
        ui.windows().context().popups().closeAll();
        ui.openMenu(EditorUi.MENU_VIEW_UI_SIZE);
        assertEquals(2, ui.windows().context().popups().popups().size(), "View and its UI size submenu");
    }

    @Test
    void paletteSlotsReportWhereTheyAreLaidOut() {
        Rect first = ui.paletteSlotBounds(1).orElseThrow();
        Rect second = ui.paletteSlotBounds(2).orElseThrow();
        assertFalse(first.isEmpty());
        assertTrue(second.x() > first.x());
        assertTrue(first.bottom() <= ui.uiHeight() && first.y() > ui.uiHeight() / 2, "along the bottom");
        assertTrue(ui.paletteSlotBounds(ToolRegistry.PALETTE_SLOTS).isPresent());
        assertEquals(Optional.empty(), ui.paletteSlotBounds(ToolRegistry.PALETTE_SLOTS + 1));
    }
}
