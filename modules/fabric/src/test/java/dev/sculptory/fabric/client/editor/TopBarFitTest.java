package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.hud.HelpSheet;
import dev.sculptory.fabric.client.editor.hud.HintLine;
import dev.sculptory.fabric.client.editor.hud.ToastStack;
import dev.sculptory.fabric.client.editor.tool.Tool;
import dev.sculptory.fabric.client.editor.ui.McFontText;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.RecordingGraphics;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import java.util.List;
import java.util.Map;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.Test;

/**
 * The top bar and the hint line in English, measured like Minecraft's font: at the reference UI 50% (853 units wide)
 * everything shows whole; at UI 100% (512 and 426 units) whole items give way in their order instead of the block's
 * name and the connection turning into "...", and the hint line drops whole hints, keeping "F1: help".
 */
class TopBarFitTest {
    /** The widest status text the bar shows in game ("Connected"; the test session is a mock one). */
    private static final Translator ENGLISH_CONNECTED = new Translator() {
        @Override
        public String translate(String key, Object... args) {
            return English.INSTANCE.translate(key.equals("sculptory.status.mock") ? "sculptory.status.connected"
                    : key, args);
        }

        @Override
        public boolean has(String key) {
            return English.INSTANCE.has(key);
        }
    };

    private final EditorTestRig rig = new EditorTestRig();
    private final UiScale scale = new UiScale();
    private String blockName = "Stone";
    private final EditorUi ui = new EditorUi(rig.ctx, rig.controller, rig.platform, rig.actions, McFontText.INSTANCE,
            new ToastStack(() -> 0L), new EditorUi.Services(ENGLISH_CONNECTED, catalog(), icon -> null,
                    () -> new HelpSheet.VanillaKeys("W A S D", "Space", "Caps Lock", "B", "T", "/", "E"),
                    "config/sculptory/editor-keys.json", keymap -> true, scale));

    {
        rig.controller.setUi(ui);
        ui.setEditing(true);
        rig.mode.enter();
    }

    private BlockCatalog catalog() {
        return new BlockCatalog() {
            @Override
            public List<Entry> entries() {
                return List.of();
            }

            @Override
            public ItemStack icon(BlockDescriptor block) {
                return null;
            }

            @Override
            public String name(BlockDescriptor block) {
                return blockName;
            }
        };
    }

    /** The top bar's texts as drawn at this UI size on a screen this many GUI pixels wide. */
    private List<String> topBar(int percent, int screenWidth) {
        scale.set(percent);
        ui.layout(screenWidth, 247);
        ui.render(new RecordingGraphics(), 0, 0, 0, false);
        RecordingGraphics g = new RecordingGraphics();
        ui.topBarRow().render(g, ui.windows().context());
        return g.drawnTexts();
    }

    @Test
    void atTheOwnersSizeEverythingShowsWhole() {
        List<String> texts = topBar(50, 426);
        assertEquals(List.of(), ui.topBarRow().dropped());
        assertTrue(texts.containsAll(List.of("File", "Edit", "Selection", "Tools", "View", "Help", "Stone", "Undo",
                "Redo", "Connected", "UI 50%")), texts.toString());
        assertTrue(texts.stream().anyMatch(text -> text.startsWith("Fly ")), texts.toString());
        assertTrue(texts.stream().noneMatch(text -> text.endsWith(TextLayout.ELLIPSIS)), texts.toString());
    }

    @Test
    void atUiSize100WholeItemsGiveWayInOrderAndTheBlockAndConnectionStayWhole() {
        // The Mask chip (beside the block) takes room too: at 512 the UI size, fly speed and water-aim toggle go, and with
        // a long block name Undo and Redo as well.
        Map<String, Integer> droppedAt = Map.of("Stone 512", 3, "Oak Planks 512", 4, "Stone 426", 5, "Oak Planks 426", 5);
        for (String name : List.of("Stone", "Oak Planks")) {
            blockName = name;
            for (int width : List.of(512, 426)) {
                String at = " at 100% on " + width + " units, " + name;
                List<String> texts = topBar(100, width);
                assertTrue(texts.containsAll(List.of("File", "Edit", "Selection", "Tools", "View", "Help", name,
                        "Connected")), texts + at);
                assertTrue(texts.stream().noneMatch(text -> text.endsWith(TextLayout.ELLIPSIS)), texts + at);
                List<Node> dropped = ui.topBarRow().dropped();
                assertTrue(dropped.size() <= droppedAt.get(name + " " + width), "only what has to go: " + dropped.size() + at);
                assertFalse(texts.contains("UI 100%"), "the UI size (View > UI size) goes first" + at);
                if (width == 426 || dropped.size() == 4) {
                    assertFalse(texts.contains("Undo"), "Undo and Redo go together, last" + at);
                    assertFalse(texts.contains("Redo"), at);
                } else {
                    assertTrue(texts.containsAll(List.of("Undo", "Redo")), texts + at);
                }
            }
        }
        topBar(50, 426);
        assertEquals(List.of(), ui.topBarRow().dropped(), "all back at 50%");
    }

    @Test
    void theHintLineDropsWholeHintsKeepingTheHelpKey() {
        for (Tool tool : rig.ctx.tools().paletteOrder()) {
            rig.controller.selectTool(tool.descriptor().id());
            for (int width : List.of(512, 426)) {
                String at = " (" + tool.descriptor().id() + " at 100% on " + width + ")";
                scale.set(100);
                ui.layout(width, 247);
                ui.render(new RecordingGraphics(), 0, 0, 0, false);
                HintLine hint = ui.hintLine();
                RecordingGraphics g = new RecordingGraphics();
                hint.render(g, ui.windows().context());
                String drawn = String.join("", g.drawnTexts());
                assertFalse(drawn.endsWith(TextLayout.ELLIPSIS), drawn + at);
                assertEquals(McFontText.INSTANCE.width(drawn), hint.bounds().width(),
                        "its plate as wide as what it shows: " + drawn + at);
                if (hint.shownHints().equals(hint.hints())) {
                    assertNull(hint.tooltip(), at);
                } else {
                    assertTrue(hint.shownHints().endsWith("F1: help"), hint.shownHints() + at);
                    assertEquals(hint.text(), hint.tooltip(), "the whole line as the tooltip" + at);
                    for (String entry : hint.shownHints().split(HintLine.SEPARATOR)) {
                        assertTrue(hint.hints().contains(entry), "whole hints only: " + entry + at);
                    }
                }
            }
        }
    }
}
