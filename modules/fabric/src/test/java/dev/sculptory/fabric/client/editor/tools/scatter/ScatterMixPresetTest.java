package dev.sculptory.fabric.client.editor.tools.scatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.scatter.ScatterSource;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.presets.PresetExtra;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.widget.TextInput;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The variant mix's text form in Scatter presets, and the panel following a mix a preset replaced. */
class ScatterMixPresetTest {
    private static final String OAK = "ab".repeat(32);
    private static final String BIRCH = "cd".repeat(32);

    private final ScatterTool tool = new ScatterTool(ScatterTool.Services.headless());
    private final ScatterMixPreset preset = new ScatterMixPreset(tool);

    private static ScatterMix.Variant asset(String hash, String name, int weight) {
        return new ScatterMix.Variant(new ScatterSource.Held(new SourceRef.Asset(hash)), name, weight);
    }

    @Test
    void theMixRoundTripsInOrder() {
        tool.replaceMix(List.of(asset(BIRCH, "trees/birch.schem", 3), asset(OAK, "trees/oak.schem", 1000)));
        PresetExtra.Capture capture = preset.capture();
        assertEquals("asset:3:" + BIRCH + ":trees/birch.schem;asset:1000:" + OAK + ":trees/oak.schem", capture.text());
        assertNull(capture.note());
        assertTrue(preset.matches(capture.text()));

        tool.replaceMix(List.of());
        assertFalse(preset.matches(capture.text()));
        assertTrue(preset.matches(""), "an empty mix is the default");
        assertEquals(PresetExtra.Applied.CLEAN, preset.apply(capture.text()));
        assertEquals(List.of(asset(BIRCH, "trees/birch.schem", 3), asset(OAK, "trees/oak.schem", 1000)),
                tool.mix().variants());
    }

    @Test
    void anEmptyMixSavesAsNothing() {
        assertEquals("", preset.capture().text());
        assertEquals(PresetExtra.Applied.CLEAN, preset.apply(""));
        assertTrue(tool.mix().isEmpty());
    }

    @Test
    void orderAndWeightsMatterNamesDoNot() {
        String saved = "asset:3:" + BIRCH + ":trees/birch.schem;asset:10:" + OAK + ":trees/oak.schem";
        tool.replaceMix(List.of(asset(BIRCH, "renamed/birch.schem", 3), asset(OAK, "trees/oak.schem", 10)));
        assertTrue(preset.matches(saved), "the name is only what the row shows");
        tool.replaceMix(List.of(asset(OAK, "trees/oak.schem", 10), asset(BIRCH, "trees/birch.schem", 3)));
        assertFalse(preset.matches(saved));
        tool.replaceMix(List.of(asset(BIRCH, "trees/birch.schem", 4), asset(OAK, "trees/oak.schem", 10)));
        assertFalse(preset.matches(saved));
    }

    @Test
    void entriesThatDoNotReadAreReportedAndTheRestApplies() {
        String text = String.join(";",
                "asset:10:" + OAK + ":ok.schem",
                "asset:10:" + OAK + ":again.schem",
                "asset:1001:" + BIRCH + ":heavy.schem",
                "asset:x:" + BIRCH + ":weightless.schem",
                "asset:5:ABCD:short.schem",
                "garbage",
                "tree:3:minecraft:oak_sapling[stage=1]",
                "asset:7:" + BIRCH + ":");
        PresetExtra.Applied applied = preset.apply(text);
        assertEquals(List.of("again.schem", "heavy.schem", "weightless.schem", "short.schem", "garbage",
                "oak_sapling[stage=1]"), applied.skipped(),
                "entries that don't read, and kinds this build doesn't know, are skipped, not called unavailable");
        assertEquals(List.of(), applied.unavailable());
        assertEquals(List.of(asset(OAK, "ok.schem", 10), asset(BIRCH, "?", 7)), tool.mix().variants());
    }

    @Test
    void blockVariantsRoundTripNextToAssets() {
        ScatterMix.Variant petals = new ScatterMix.Variant(
                new ScatterSource.Block("minecraft:pink_petals[facing=east,flower_amount=3]"), "Pink Petals", 7);
        tool.replaceMix(List.of(petals, asset(OAK, "trees/oak.schem", 3)));
        PresetExtra.Capture capture = preset.capture();
        assertEquals("block:7:minecraft:pink_petals[facing=east,flower_amount=3];asset:3:" + OAK + ":trees/oak.schem",
                capture.text());
        assertTrue(preset.matches(capture.text()));

        tool.replaceMix(List.of());
        assertEquals(PresetExtra.Applied.CLEAN, preset.apply(capture.text()));
        assertEquals(List.of(petals.source(), new ScatterSource.Held(new SourceRef.Asset(OAK))),
                tool.mix().variants().stream().map(ScatterMix.Variant::source).toList());
        assertEquals(List.of(7, 3), tool.mix().variants().stream().map(ScatterMix.Variant::weight).toList());
        assertTrue(preset.matches(capture.text()));
    }

    @Test
    void blockEntriesThatCannotBeUsedAreSkipped() {
        String text = String.join(";",
                "block:5:minecraft:poppy",
                "block:5:minecraft:poppy",
                "block:0:minecraft:dandelion",
                "block:5:",
                "block:5:minecraft:" + "a".repeat(300),
                "asset:3:" + OAK + ":trees/oak.schem");
        PresetExtra.Applied applied = preset.apply(text);
        assertEquals(4, applied.skipped().size(), "a repeat, a bad weight, empty text and text over the cap");
        assertEquals(List.of(new ScatterSource.Block("minecraft:poppy"), new ScatterSource.Held(new SourceRef.Asset(OAK))),
                tool.mix().variants().stream().map(ScatterMix.Variant::source).toList());
    }

    @Test
    void clipboardVariantsAreLeftOutWithANote() {
        tool.replaceMix(List.of(new ScatterMix.Variant(
                new ScatterSource.Held(new SourceRef.Clipboard(UUID.randomUUID())), "copy", 10),
                asset(OAK, "a;b:c", 2)));
        PresetExtra.Capture capture = preset.capture();
        assertEquals("asset:2:" + OAK + ":a_b_c", capture.text(), "a name can't break the format");
        assertEquals("sculptory.preset.notice.clipboard_left_out", capture.note().key());
        assertEquals(List.of("1"), capture.note().args());
        assertFalse(preset.matches(capture.text()), "the mix still has the clipboard");
    }

    @Test
    void thePanelShowsTheWeightsOfAMixAPresetReplaced() {
        tool.replaceMix(List.of(asset(OAK, "trees/oak.schem", 10)));
        ScatterMixPanel panel = new ScatterMixPanel(tool, Optional::empty, () -> "clipboard", (anchor, onPick) -> { },
                Translator.KEYS);
        Node root = panel.build();
        assertEquals("10", weight(root));
        preset.apply("asset:25:" + OAK + ":trees/oak.schem");
        panel.refresh();
        assertEquals("25", weight(root), "same variant, new weight: the row shows it");
    }

    /** The weight field of the first variant row. */
    private static String weight(Node panel) {
        Node rows = panel.children().get(3);
        return ((TextInput) rows.children().get(0).children().get(3)).text(); // name, tag, size, weight, remove
    }
}
