package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.hud.HelpSheet;
import dev.sculptory.fabric.client.editor.hud.HistoryOfferToast;
import dev.sculptory.fabric.client.editor.hud.ToastStack;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import dev.sculptory.fabric.client.session.StrokeHandle;
import dev.sculptory.fabric.client.session.StrokeParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The Undo anyway toast in the editor UI: above the docked Tool Settings window, and it gets the click. */
class HistoryOfferUiTest {
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
        ui = new EditorUi(rig.ctx, rig.controller, rig.platform, rig.actions, TEXT, new ToastStack(() -> 0L),
                new EditorUi.Services(Translator.KEYS, BlockCatalog.EMPTY, icon -> null,
                        () -> new HelpSheet.VanillaKeys("W A S D", "Space", "Shift", "B", "T", "/", "E"),
                        "config/sculptory/editor-keys.json", keymap -> true, new UiScale()));
        rig.controller.setUi(ui);
        ui.setEditing(true);
        rig.mode.enter();
        ui.layout(640, 360);
        for (ToolId tool : java.util.List.of(ToolId.RAISE, ToolId.LOWER)) {
            StrokeHandle stroke = rig.session.beginStroke(tool, new BrushSpec(BrushTool.RAISE, 4, 1f, Falloff.SMOOTH,
                    Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L), StrokeParams.DEFAULT);
            stroke.dab(new Dab(0, 0, 1024, 0, 255));
            stroke.end();
        }
    }

    /** What a frame does for the toast: follow the session, then lay out. */
    private void frame() {
        ui.historyOffer().refresh();
        ui.layout(640, 360);
    }

    @Test
    void theToastSitsLeftOfTheToolSettingsWindowAndTakesTheClick() {
        HistoryOfferToast toast = ui.historyOffer();
        frame();
        assertFalse(toast.isShown());
        assertEquals(0, toast.height());

        rig.session.setStepConflicts(4);
        rig.controller.undo();
        frame();
        assertTrue(toast.isShown());
        assertTrue(toast.height() > 0, "the toasts move down under it");
        Rect button = toast.button().bounds();
        double x = button.x() + button.width() / 2.0;
        double y = button.y() + button.height() / 2.0;
        assertTrue(ui.windows().isOpen(EditorWindows.TOOL_SETTINGS));
        Rect toolSettings = ui.windows().window(EditorWindows.TOOL_SETTINGS).orElseThrow().rect();
        assertEquals(toolSettings.x() - 4, toast.node().bounds().right(), "the toast column moves left of it");
        assertFalse(ui.windows().isMouseOverUi(x, y), "no window under the button");
        assertTrue(ui.isOverUi(x, y));

        assertTrue(ui.mouseDown(x, y, 0, 0));
        ui.mouseUp(x, y, 0);
        assertEquals(1, rig.session.overwrites().size(), "the click reached the toast, not the window");
        frame();
        assertFalse(toast.isShown(), "accepted: the offer is gone");
    }

    @Test
    void closingTheToastLeavesTheOfferInTheHistoryWindow() {
        rig.session.setStepConflicts(1);
        rig.controller.undo();
        frame();
        HistoryOfferToast toast = ui.historyOffer();
        toast.dismiss();
        frame();
        assertFalse(toast.isShown());
        ui.historyWindow().refresh();
        assertTrue(ui.historyWindow().offerShown());
    }
}
