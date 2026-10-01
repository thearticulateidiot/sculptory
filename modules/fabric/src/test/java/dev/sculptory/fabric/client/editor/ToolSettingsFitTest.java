package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.hud.HelpSheet;
import dev.sculptory.fabric.client.editor.hud.ToastStack;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.form.SegmentedControl;
import dev.sculptory.fabric.client.editor.settings.form.SettingsForm;
import dev.sculptory.fabric.client.editor.tool.Tool;
import dev.sculptory.fabric.client.editor.ui.McFontText;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.RecordingGraphics;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import dev.sculptory.fabric.client.editor.ui.widget.CollapsibleSection;
import dev.sculptory.fabric.client.editor.ui.window.Corner;
import dev.sculptory.fabric.client.editor.ui.window.LayoutState;
import dev.sculptory.fabric.client.editor.ui.window.Window;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Every tool's settings in the Tool Settings window at the reference size (UI 50% on 2560x1482 at GUI scale 6, the
 * window 170 units wide as in their layout), in English and measured like Minecraft's font, every section open: no
 * setting's name, option or toggle label is cut short ("Everyt...", "Only exi...", "Only on these surface b..."), at
 * its default or with its reset button showing.
 */
class ToolSettingsFitTest {
    @Test
    void atTheOwnersSizeNothingInToolSettingsIsCutShort() {
        EditorTestRig rig = new EditorTestRig();
        UiScale scale = new UiScale();
        EditorUi ui = new EditorUi(rig.ctx, rig.controller, rig.platform, rig.actions, McFontText.INSTANCE,
                new ToastStack(() -> 0L), new EditorUi.Services(English.INSTANCE, BlockCatalog.EMPTY, icon -> null,
                        () -> new HelpSheet.VanillaKeys("W A S D", "Space", "Caps Lock", "B", "T", "/", "E"),
                        "config/sculptory/editor-keys.json", keymap -> true, scale));
        rig.controller.setUi(ui);
        ui.setEditing(true);
        rig.mode.enter();
        scale.set(50);
        ui.windows().restore(new LayoutState(List.of(new LayoutState.WindowState(EditorWindows.TOOL_SETTINGS, true,
                false, Corner.TOP_RIGHT, 4, 26, 170, 303))));
        Window window = ui.windows().window(EditorWindows.TOOL_SETTINGS).orElseThrow();
        List<String> cut = new ArrayList<>();
        List<String> unevenOptions = new ArrayList<>();
        int tools = 0;
        for (Tool tool : rig.ctx.tools().paletteOrder()) {
            assertTrue(rig.controller.selectTool(tool.descriptor().id()));
            ui.layout(426, 247);
            ui.toolSettings().refresh();
            ui.layout(426, 247);
            assertEquals(160, window.contentRect(Theme.DARK).width(), "the reference window");
            expand(window.content());
            cut.addAll(cutTexts(ui, window, tool.descriptor().id() + " "));
            unevenOptions.addAll(unevenOptionLines(window.content(), tool.descriptor().id() + " "));
            // A tool without settings (Tinker) has no form.
            Optional<SettingsForm> form = ui.toolSettings().form();
            assertEquals(tool.schema().defs().isEmpty(), form.isEmpty(), tool.descriptor().id() + " has a form");
            for (SettingDef<?> def : tool.schema().defs()) {
                form.orElseThrow().resetButton(def.key()).ifPresent(reset -> reset.setVisible(true));
            }
            cut.addAll(cutTexts(ui, window, tool.descriptor().id() + " with reset buttons "));
            tools++;
        }
        assertEquals(15, tools);
        assertEquals(List.of(), cut);
        assertEquals(List.of(), unevenOptions, "options that wrap share their lines evenly (no \"Sphere\" alone)");
    }

    /** Option rows whose lines hold more than one option more than another (as laid out now). */
    private static List<String> unevenOptionLines(Node node, String where) {
        List<String> uneven = new ArrayList<>();
        if (node instanceof SegmentedControl<?> options) {
            List<Integer> lines = options.optionsPerLine();
            int most = lines.stream().mapToInt(Integer::intValue).max().orElse(0);
            int least = lines.stream().mapToInt(Integer::intValue).min().orElse(0);
            if (most - least > 1) {
                uneven.add(where + options.options() + " " + lines);
            }
        }
        for (Node child : node.children()) {
            uneven.addAll(unevenOptionLines(child, where));
        }
        return uneven;
    }

    private static List<String> cutTexts(EditorUi ui, Window window, String where) {
        ui.layout(426, 247);
        RecordingGraphics g = new RecordingGraphics();
        window.content().render(g, ui.windows().context());
        // Block chips show the block's id without a running game (its name in game); those may be cut.
        return g.drawnTexts().stream()
                .filter(text -> text.endsWith(TextLayout.ELLIPSIS) && !text.startsWith("minecraft:"))
                .map(text -> where + text).toList();
    }

    private static void expand(Node node) {
        if (node instanceof CollapsibleSection section) {
            section.setExpanded(true);
        }
        for (Node child : node.children()) {
            expand(child);
        }
    }
}
