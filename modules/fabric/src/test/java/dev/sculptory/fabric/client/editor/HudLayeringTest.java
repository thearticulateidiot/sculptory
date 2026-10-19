package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.hud.HelpSheet;
import dev.sculptory.fabric.client.editor.hud.HintLine;
import dev.sculptory.fabric.client.editor.hud.JobBars;
import dev.sculptory.fabric.client.editor.hud.ToastStack;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.tool.KeyHint;
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.ui.FadedGraphics;
import dev.sculptory.fabric.client.editor.ui.McFontText;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.RecordingGraphics;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiOpacity;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import dev.sculptory.fabric.client.editor.ui.widget.Tooltip;
import dev.sculptory.fabric.client.editor.ui.window.Window;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import dev.sculptory.fabric.client.session.Notice;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What goes over what at the screen's edges, on the reference screen at UI 50% in English with Minecraft's font
 * widths: the job bars at the bottom right (a burst of jobs never runs up under Tool Settings, and they fade with the
 * panels), the hint line kept clear of them, and tooltips and popups above the toasts.
 */
class HudLayeringTest {
    /** The reference screen in GUI pixels (2560x1600 at 150%, GUI scale 6). */
    private static final int SCREEN_W = 427;
    private static final int SCREEN_H = 249;
    private static final Theme THEME = Theme.DARK;
    private static final String TOAST = "Undo: Erase";

    private final EditorTestRig rig = new EditorTestRig();
    private final UiScale scale = new UiScale();
    private final UiOpacity opacity = new UiOpacity();
    private long now = 1_000;
    private final ToastStack toasts = new ToastStack(() -> now);
    private final EditorUi ui = new EditorUi(rig.ctx, rig.controller, rig.platform, rig.actions, McFontText.INSTANCE,
            toasts, new EditorUi.Services(English.INSTANCE, BlockCatalog.EMPTY, icon -> null,
                    () -> new HelpSheet.VanillaKeys("W A S D", "Space", "Shift", "B", "T", "/", "E"),
                    "config/sculptory/editor-keys.json", keymap -> true, scale, opacity));

    HudLayeringTest() {
        rig.controller.setUi(ui);
        rig.confirmer = ui;
        ui.setEditing(true);
        rig.mode.enter();
        scale.set(50);
        ui.layout(SCREEN_W, SCREEN_H);
    }

    private RecordingGraphics frame(double x, double y) {
        ui.layout(SCREEN_W, SCREEN_H);
        RecordingGraphics g = new RecordingGraphics();
        ui.render(g, x, y, now, false);
        return g;
    }

    /** Cuts a small selection {@code count} times: one job each, some still running. */
    private void jobs(int count) {
        rig.ctx.setSelection(new Box(new BlockPos(0, 60, 0), new BlockPos(1, 61, 1)));
        for (int i = 0; i < count; i++) {
            rig.controller.run(KeyAction.CUT, Modifiers.CONTROL);
            if (i < count - 2) {
                rig.session.finishJobs();
            }
        }
    }

    @Test
    void aBurstOfJobsNeverRunsUpUnderTheWindowsAndTheHintLineKeepsClearOfThem() {
        rig.controller.selectTool(ToolId.GENERATE);
        jobs(10);
        assertTrue(rig.session.jobs().jobs().size() >= 6, "finished jobs stay in the tracker a while");
        frame(0, 0);
        RecordingGraphics g = frame(0, 0);
        JobBars bars = ui.jobBars();
        Rect column = bars.node().bounds();
        assertTrue(bars.rowCount() >= 1 && bars.rowCount() <= JobBars.MAX_ROWS, bars.rowCount() + " bars");
        assertTrue(column.bottom() <= ui.uiHeight() && column.right() <= ui.uiWidth(), column.toString());
        for (Window window : ui.windows().windows()) {
            if (window.isOpen()) {
                assertFalse(window.rect().intersects(column), window.id() + " " + window.rect() + " over the bars at "
                        + column);
            }
        }

        HintLine hint = ui.hintLine();
        Rect line = hint.bounds();
        assertFalse(line.intersects(column), "the hint line " + line + " clear of the bars " + column);
        String drawn = String.join("", g.drawnTexts().stream().filter(text -> text.startsWith(HintLine.SEPARATOR)
                || text.equals(hint.toolName())).toList());
        assertFalse(drawn.endsWith(TextLayout.ELLIPSIS), drawn);
        if (!hint.shownHints().equals(hint.hints())) {
            assertTrue(hint.shownHints().endsWith("F1: help"), hint.shownHints());
        }
    }

    @Test
    void theJobBarsFadeWithThePanels() {
        jobs(2);
        opacity.set(UiOpacity.Values.DEFAULT.withPanels(40));
        RecordingGraphics g = frame(0, 0);
        Rect column = ui.jobBars().node().bounds();
        assertFalse(column.isEmpty());
        List<RecordingGraphics.Fill> plates = g.drawnFills().stream()
                .filter(fill -> column.contains(fill.rect().x(), fill.rect().y())
                        && (fill.argb() & 0x00FFFFFF) == (THEME.paletteBackground & 0x00FFFFFF))
                .toList();
        assertFalse(plates.isEmpty(), "a bar's plate");
        assertTrue(plates.stream().allMatch(fill -> fill.argb() == FadedGraphics.fade(THEME.paletteBackground, 0.4F)),
                "faded like the palette: " + plates);
    }

    @Test
    void tooltipsAndPopupsDrawAboveTheToastsHoweverManyWindowsAreOpen() {
        for (String id : List.of(EditorWindows.KEYS, EditorWindows.HISTORY)) {
            ui.toggleWindow(id);
        }
        toasts.show(Notice.Level.INFO, TOAST);
        frame(0, 0);
        Node withTooltip = firstWithTooltip(ui.windows().window(EditorWindows.TOOL_SETTINGS).orElseThrow().content());
        Rect at = withTooltip.bounds();
        double x = (at.x() + at.width() / 2.0) * scale.factor();
        double y = (at.y() + at.height() / 2.0) * scale.factor();
        frame(x, y);
        now += THEME.tooltipDelayMs + 1;
        RecordingGraphics g = frame(x, y);
        String tip = Tooltip.lines(McFontText.INSTANCE, THEME, withTooltip.tooltip()).get(0);
        int toastLayer = g.layerOf(TOAST);
        assertTrue(toastLayer >= 0, "the toast is drawn: " + g.drawnTexts());
        assertTrue(g.layerOf(tip) > toastLayer, "the tooltip \"" + tip + "\" at " + g.layerOf(tip) + ", the toast at "
                + toastLayer);
        for (String title : List.of("Tool Settings", "Keys", "History")) {
            assertTrue(g.layerOf(title) < toastLayer, title + " under the toasts");
        }

        ui.openOpacity();
        g = frame(0, 0);
        assertTrue(g.layerOf("Opacity") > g.layerOf(TOAST), "the popup over the toast");
        assertTrue(g.isBalanced());
    }

    private static Node firstWithTooltip(Node root) {
        List<Node> queue = new ArrayList<>(List.of(root));
        while (!queue.isEmpty()) {
            Node node = queue.remove(0);
            if (node.isShown() && !node.bounds().isEmpty() && node.tooltip() != null && !node.tooltip().isBlank()) {
                return node;
            }
            queue.addAll(node.children());
        }
        throw new AssertionError("no control with a tooltip in Tool Settings");
    }

    @Test
    void notesGiveWayBeforeKeysOnAShortHintLine() {
        HintLine hint = new HintLine();
        hint.set("Place", List.of(KeyHint.text("sculptory.hint.place.paste", "9"),
                KeyHint.text("sculptory.ghost.simplified", "9"), new KeyHint("R", "sculptory.hint.place.rotate"),
                new KeyHint("Esc", "sculptory.hint.cancel"), new KeyHint("F1", "sculptory.hint.help")), English.INSTANCE);
        String note = English.INSTANCE.translate("sculptory.ghost.simplified", "9");
        assertTrue(hint.hints().contains(note), hint.hints());
        UiContext ctx = new UiContext(McFontText.INSTANCE, THEME);
        int width = McFontText.INSTANCE.width(hint.text()) - 1;
        hint.measure(ctx, width);
        hint.layout(ctx, new Rect(0, 0, width, 9));
        assertFalse(hint.shownHints().contains(note), hint.shownHints());
        assertEquals(hint.hints().replace(HintLine.SEPARATOR + note, ""), hint.shownHints(), "only the note gave way");
        assertEquals(hint.text(), hint.tooltip(), "the whole line as the tooltip");
    }
}