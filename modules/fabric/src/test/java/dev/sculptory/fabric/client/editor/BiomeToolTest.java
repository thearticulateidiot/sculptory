package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.region.Region;
import dev.sculptory.fabric.client.editor.biomes.BiomeCatalog;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tools.brush.BiomeFillPanel;
import dev.sculptory.fabric.client.editor.tools.brush.BrushSettings;
import dev.sculptory.fabric.client.editor.tools.place.PlaceSettings;
import dev.sculptory.fabric.client.session.Permissions;
import dev.sculptory.fabric.client.session.ToolAction;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.OpLabel;
import dev.sculptory.server.engine.Perm;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Biome tool around the brush (protocol 6): Fill selection sends a Biome fill of the selection with the tool's biome
 * and mode, labelled, and needs the biome node; the biome setting and the picker's search; Place's Biomes toggle.
 */
class BiomeToolTest {
    private final EditorTestRig rig = new EditorTestRig();

    private BiomeFillPanel panel() {
        return new BiomeFillPanel(rig.actions, () -> rig.ctx.settings(ToolId.BIOME), Translator.KEYS);
    }

    @Test
    void fillSelectionSendsABiomeFillOfTheSelectionWithTheToolsBiome() {
        rig.mode.enter();
        Box box = new Box(new BlockPos(0, 60, 0), new BlockPos(9, 62, 9));
        rig.ctx.setSelection(box);
        assertTrue(panel().fill());
        ToolAction.RunOp sent = (ToolAction.RunOp) rig.session.sent().get(rig.session.sent().size() - 1);
        assertEquals(new OpSpec.BiomeFill(new Region.Cuboid(box), BrushSettings.DEFAULT_BIOME, true), sent.op());
        assertEquals(OpLabel.BIOME_FILL, sent.label());

        BrushSettings settings = BrushSettings.forTool(dev.sculptory.core.brush.BrushTool.BIOME);
        rig.ctx.updateSettings(ToolId.BIOME, rig.ctx.settings(ToolId.BIOME).with(settings.biome, "minecraft:desert")
                .with(settings.biomeThreeD, true));
        assertTrue(panel().fill());
        sent = (ToolAction.RunOp) rig.session.sent().get(rig.session.sent().size() - 1);
        assertEquals(new OpSpec.BiomeFill(new Region.Cuboid(box), "minecraft:desert", false), sent.op(), "3D");
    }

    @Test
    void fillSelectionNeedsTheBiomeNodeAndASelection() {
        rig.mode.enter();
        int before = rig.session.sent().size();
        assertFalse(panel().fill(), "no selection");
        assertTrue(rig.noticeKeys().contains("sculptory.notice.select_first"), rig.noticeKeys().toString());
        rig.ctx.setSelection(new Box(new BlockPos(0, 60, 0), new BlockPos(3, 60, 3)));
        EnumSet<Perm> granted = EnumSet.allOf(Perm.class);
        granted.remove(Perm.BIOME);
        rig.session.setPermissions(new Permissions(Perm.mask(granted), Limits.DEFAULTS));
        assertFalse(panel().fill());
        assertTrue(rig.noticeKeys().contains("sculptory.notice.needs_permission"), rig.noticeKeys().toString());
        assertEquals(before, rig.session.sent().size(), "nothing sent");
    }

    @Test
    void theBiomeSettingTakesNamespacedIdsAndThePickerSearchesNamesAndIds() {
        SettingDef.Biome biome = BrushSettings.forTool(dev.sculptory.core.brush.BrushTool.BIOME).biome;
        assertEquals("minecraft:desert", biome.decode(biome.encode("minecraft:desert")));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> biome.canonical("Desert"));
        List<BiomeCatalog.Entry> entries = List.of(new BiomeCatalog.Entry("minecraft:cherry_grove", "Cherry Grove", 1),
                new BiomeCatalog.Entry("minecraft:desert", "Desert", 2), new BiomeCatalog.Entry("mod:glade", "Glade", 3));
        assertEquals(List.of(entries.get(0)), BiomeCatalog.filter(entries, "cherry"));
        assertEquals(List.of(entries.get(2)), BiomeCatalog.filter(entries, "mod:"));
        assertEquals(entries, BiomeCatalog.filter(entries, " "));
        assertEquals("Cherry grove", BiomeCatalog.pretty("minecraft:cherry_grove"));
    }

    @Test
    void placesBiomesToggleIsOffByDefault() {
        PlaceSettings place = new PlaceSettings(() -> true);
        assertFalse(place.biomes().defaultValue(), "like WorldEdit's -b");
        assertTrue(place.schema().defs().contains(place.biomes()));
        assertEquals(PasteOptions.DEFAULT.withBiomes(true), new PasteOptions(false, false, true).withBiomes(true));
    }
}
