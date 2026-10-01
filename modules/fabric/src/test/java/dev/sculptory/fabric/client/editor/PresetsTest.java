package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.ShapeSpec;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.scatter.ScatterSource;
import dev.sculptory.fabric.client.editor.presets.PresetBook;
import dev.sculptory.fabric.client.editor.presets.PresetNames;
import dev.sculptory.fabric.client.editor.presets.PresetStore;
import dev.sculptory.fabric.client.editor.presets.Presets;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tools.brush.BrushSettings;
import dev.sculptory.fabric.client.editor.tools.brush.ShapeSettings;
import dev.sculptory.fabric.client.editor.tools.brush.TerrainBrushTool;
import dev.sculptory.fabric.client.editor.tools.place.PlaceSettings;
import dev.sculptory.fabric.client.editor.tools.scatter.ScatterMix;
import dev.sculptory.fabric.client.editor.tools.scatter.ScatterMixPreset;
import dev.sculptory.fabric.client.editor.tools.scatter.ScatterTool;
import dev.sculptory.fabric.client.editor.tools.scatter.ScatterToolSettings;
import dev.sculptory.fabric.client.editor.tools.select.SelectSettings;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Permissions;
import dev.sculptory.fabric.client.util.AtomicFileStore;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.protocol.v2.Limits;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Presets per tool against the editor on the mock session: each tool family, applying leniently, the file. */
class PresetsTest {
    private static final Limits RADIUS_16 = new Limits(2_097_152L, 2_097_152L, 16, 20, 32L << 20, 2);
    private static final String OAK = "ab".repeat(32);
    private static final String BIRCH = "cd".repeat(32);
    private static final BlockDescriptor DIRT = block("minecraft:dirt");
    private static final BlockDescriptor GRASS = block("minecraft:grass_block");

    @TempDir
    Path dir;

    private final EditorTestRig rig = new EditorTestRig();
    private final List<String> fileProblems = new ArrayList<>();
    private Predicate<BlockDescriptor> blocks = block -> true;
    private Presets presets;

    private static BlockDescriptor block(String id) {
        return BlockDescriptor.of(new NamespacedId(id));
    }

    @BeforeEach
    void load() {
        presets = open();
    }

    /** A fresh presets service on the same file (a game restart). */
    private Presets open() {
        PresetStore store = new PresetStore(new ConfigFile(file(), new AtomicFileStore(PresetStore.MAX_BYTES),
                fileProblems::add));
        Presets opened = new Presets(store, rig.ctx, Translator.KEYS, block -> blocks.test(block));
        opened.addExtra(ToolId.SCATTER, ScatterMixPreset.KEY, new ScatterMixPreset(scatter()));
        opened.load();
        return opened;
    }

    private Path file() {
        return dir.resolve(PresetStore.FILE_NAME);
    }

    private BrushSettings brush(ToolId id) {
        return ((TerrainBrushTool) rig.ctx.tools().get(id).orElseThrow()).settings();
    }

    private ScatterTool scatter() {
        return (ScatterTool) rig.ctx.tools().get(ToolId.SCATTER).orElseThrow();
    }

    private SettingsValues settings(ToolId id) {
        return rig.ctx.settings(id);
    }

    private void set(ToolId id, SettingsValues values) {
        rig.ctx.updateSettings(id, values);
    }

    private PresetBook onDisk() throws IOException {
        return PresetBook.fromJson(Files.readString(file())).book();
    }

    // ---- Each tool family ----

    @Test
    void aBrushPresetSavesAndLoadsItsSettings() throws IOException {
        BrushSettings raise = brush(ToolId.RAISE);
        SettingsValues soft = settings(ToolId.RAISE).with(raise.radius, 12).with(raise.strength, 0.3)
                .with(raise.falloff, Falloff.LINEAR);
        set(ToolId.RAISE, soft);
        assertTrue(presets.isModified(ToolId.RAISE), "not the defaults any more");
        assertTrue(presets.saveAs(ToolId.RAISE, "  Soft hills "));
        assertEquals("Soft hills", presets.selected(ToolId.RAISE));
        assertFalse(presets.isModified(ToolId.RAISE));
        assertTrue(rig.notices.contains(Notice.of(Notice.Level.SUCCESS, "sculptory.preset.notice.saved", "Soft hills")));
        assertEquals(soft.encode(), onDisk().tool(ToolId.RAISE).find("Soft hills").orElseThrow().values());

        set(ToolId.RAISE, soft.with(raise.radius, 20));
        assertTrue(presets.isModified(ToolId.RAISE));
        presets.select(ToolId.RAISE, "Soft hills");
        assertEquals(soft, settings(ToolId.RAISE), "selecting the preset again drops the changes");
        assertFalse(presets.isModified(ToolId.RAISE));

        presets.select(ToolId.RAISE, "");
        assertEquals(SettingsValues.defaults(raise.schema()), settings(ToolId.RAISE), "Default is the built-in defaults");
        assertEquals("", onDisk().tool(ToolId.RAISE).selected(), "the selection is saved");
        assertEquals(List.of(), presets.names(ToolId.LOWER), "each tool has its own list");
    }

    @Test
    void aBrushPresetKeepsItsSymmetry() throws IOException {
        BrushSettings smooth = brush(ToolId.SMOOTH);
        SettingsValues mirrored = settings(ToolId.SMOOTH).with(smooth.symmetry, Symmetry.Mode.MIRROR_Z);
        set(ToolId.SMOOTH, mirrored);
        assertTrue(presets.saveAs(ToolId.SMOOTH, "Mirrored"));
        assertEquals("MIRROR_Z", onDisk().tool(ToolId.SMOOTH).find("Mirrored").orElseThrow().values().get("symmetry"));
        set(ToolId.SMOOTH, mirrored.with(smooth.symmetry, Symmetry.Mode.OFF));
        assertTrue(presets.isModified(ToolId.SMOOTH));
        presets.select(ToolId.SMOOTH, "Mirrored");
        assertEquals(Symmetry.Mode.MIRROR_Z, settings(ToolId.SMOOTH).get(smooth.symmetry));
    }

    /**
     * A Shape brush preset keeps every setting of the tool (the solid, sizes, facing, anchor, mode, hollow, the mix and
     * symmetry) and brings them back; on a server with a smaller brush radius its radius and height are lowered, with
     * one toast, and the file keeps what was saved.
     */
    @Test
    void aShapePresetSavesAndLoadsEverySetting() throws IOException {
        SettingsValues cones = settings(ToolId.SHAPE)
                .with(ShapeSettings.KIND, ShapeSpec.Kind.CONE)
                .with(ShapeSettings.RADIUS, 30)
                .with(ShapeSettings.HEIGHT_AUTO, false)
                .with(ShapeSettings.HEIGHT, 50)
                .with(ShapeSettings.FACING, ShapeSettings.FacingChoice.CLICKED_FACE)
                .with(ShapeSettings.ANCHOR, ShapeSettings.Anchor.CENTRE)
                .with(ShapeSettings.MODE, ShapeSpec.Mode.PLACE_IN_AIR)
                .with(ShapeSettings.HOLLOW, true)
                .with(ShapeSettings.THICKNESS, 3)
                .with(ShapeSettings.BLOCKS, ShapeSettings.Blocks.PALETTE)
                .with(ShapeSettings.PALETTE, List.of(new SettingDef.WeightedBlock(DIRT, 2), new SettingDef.WeightedBlock(GRASS, 5)))
                .with(ShapeSettings.INSIDE_SELECTION, true)
                .with(ShapeSettings.SYMMETRY, Symmetry.Mode.ROTATE_4);
        set(ToolId.SHAPE, cones);
        assertTrue(presets.saveAs(ToolId.SHAPE, "Tall cones"));
        assertEquals(cones.encode(), onDisk().tool(ToolId.SHAPE).find("Tall cones").orElseThrow().values());
        set(ToolId.SHAPE, SettingsValues.defaults(ShapeSettings.SCHEMA));
        presets.select(ToolId.SHAPE, "Tall cones");
        assertEquals(cones, settings(ToolId.SHAPE), "every setting comes back");

        String saved = Files.readString(file());
        rig.session.setPermissions(new Permissions(Perm.mask(EnumSet.allOf(Perm.class)), RADIUS_16));
        rig.notices.clear();
        presets.select(ToolId.SHAPE, "Tall cones");
        assertEquals(16, settings(ToolId.SHAPE).get(ShapeSettings.RADIUS));
        assertEquals(33, settings(ToolId.SHAPE).get(ShapeSettings.HEIGHT), "the largest diameter there");
        assertEquals(1, rig.notices.size(), "one toast: " + rig.notices);
        assertEquals("sculptory.preset.notice.adjusted", rig.notices.get(0).key());
        assertEquals(saved, Files.readString(file()), "the preset keeps its 30 and 50");
    }

    /**
     * A preset file written before brush symmetry (its brush presets have no "symmetry" value) still loads, and the
     * brush comes out with symmetry Off, whatever it had: silently, and without rewriting the file.
     */
    @Test
    void brushPresetsSavedBeforeSymmetryLoadAsOff() throws IOException {
        String json = """
                {
                  "version": 1,
                  "tools": {
                    "raise": {
                      "selected": "Old hills",
                      "presets": [
                        {"name": "Old hills", "values": {"radius": "9", "strength": "0.4", "falloff": "LINEAR",
                          "shape": "CIRCLE", "mask.blocks": "", "mask.exact": "false", "mask.y": "-64..319",
                          "mask.slope": "0..16", "mask.invert": "false", "mask.selection": "false"}}
                      ]
                    }
                  }
                }
                """;
        Files.writeString(file(), json);
        BrushSettings raise = brush(ToolId.RAISE);
        set(ToolId.RAISE, settings(ToolId.RAISE).with(raise.symmetry, Symmetry.Mode.ROTATE_4));
        presets = open();
        rig.notices.clear();
        presets.restoreSelections();
        assertEquals(9, settings(ToolId.RAISE).get(raise.radius));
        assertEquals(Falloff.LINEAR, settings(ToolId.RAISE).get(raise.falloff));
        assertEquals(Symmetry.Mode.OFF, settings(ToolId.RAISE).get(raise.symmetry), "no symmetry saved: Off");
        assertEquals(List.of(), rig.notices, "nothing was skipped or changed");
        assertFalse(presets.isModified(ToolId.RAISE));
        assertEquals(json, Files.readString(file()), "applying never rewrites the preset");
    }

    /**
     * Palette Paint's mix holds 64 blocks, as a palette does: a preset saves all 64 and brings them back after a
     * restart; a file listing more (edited by hand) gives the first 64, with the usual "adjusted" toast.
     */
    @Test
    void aPalettePaintPresetKeepsAll64BlocksOfItsMix() throws IOException {
        BrushSettings palette = brush(ToolId.PALETTE);
        List<SettingDef.WeightedBlock> mix = new ArrayList<>();
        for (int i = 0; i < 70; i++) mix.add(new SettingDef.WeightedBlock(block("minecraft:block_" + i), 1 + i % 7));
        List<SettingDef.WeightedBlock> full = List.copyOf(mix.subList(0, BrushSettings.MAX_PALETTE_ENTRIES));
        assertEquals(64, full.size());
        set(ToolId.PALETTE, settings(ToolId.PALETTE).with(palette.palette, full));
        assertTrue(presets.saveAs(ToolId.PALETTE, "Big mix"));
        String text = onDisk().tool(ToolId.PALETTE).find("Big mix").orElseThrow().values().get("palette");
        assertEquals(64, text.split(";").length, "64 entries on disk: " + text);

        set(ToolId.PALETTE, SettingsValues.defaults(palette.schema()));
        presets = open();
        presets.select(ToolId.PALETTE, "Big mix");
        assertEquals(full, settings(ToolId.PALETTE).get(palette.palette), "all 64, in order, after a restart");
        assertFalse(presets.isModified(ToolId.PALETTE));

        // 70 entries written by hand: the first 64 are used, and the toast says so; the file keeps its 70.
        String seventy = palette.palette.encode(mix);
        Files.writeString(file(), """
                {"version": 1, "tools": {"palette": {"selected": "", "presets": [
                  {"name": "Too many", "values": {"palette": "%s"}}
                ]}}}
                """.formatted(seventy));
        presets = open();
        rig.notices.clear();
        presets.select(ToolId.PALETTE, "Too many");
        assertEquals(full, settings(ToolId.PALETTE).get(palette.palette));
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.preset.notice.adjusted", "Too many",
                "sculptory.setting.brush.palette 70 → 64")), rig.notices);
        assertTrue(Files.readString(file()).contains(seventy), "the preset keeps its 70 blocks");
    }

    @Test
    void aSelectPresetKeepsThePaletteAndThickness() {
        SettingsValues walls = settings(ToolId.SELECT)
                .with(SelectSettings.FILL_WITH, SelectSettings.FillWith.PALETTE)
                .with(SelectSettings.PALETTE,
                        List.of(new SettingDef.WeightedBlock(DIRT, 3), new SettingDef.WeightedBlock(GRASS, 1)))
                .with(SelectSettings.THICKNESS, 3);
        set(ToolId.SELECT, walls);
        assertTrue(presets.saveAs(ToolId.SELECT, "Dirt walls"));
        presets.select(ToolId.SELECT, "");
        presets.select(ToolId.SELECT, "Dirt walls");
        assertEquals(walls, settings(ToolId.SELECT));
    }

    @Test
    void aPlacePresetKeepsItsToggles() {
        PlaceSettings place = rig.place.settings();
        SettingsValues withAir = settings(ToolId.PLACE).with(place.includeAir(), true).with(place.physics(), true);
        set(ToolId.PLACE, withAir);
        assertTrue(presets.saveAs(ToolId.PLACE, "With air"));
        presets.select(ToolId.PLACE, "");
        assertFalse(settings(ToolId.PLACE).get(place.includeAir()));
        presets.select(ToolId.PLACE, "With air");
        assertEquals(withAir, settings(ToolId.PLACE), "physics is kept too; the permission is checked when placing");
    }

    @Test
    void aScatterPresetKeepsItsSettingsAndItsMix() throws IOException {
        ScatterTool tool = scatter();
        ScatterToolSettings s = tool.settings();
        SettingsValues forest = settings(ToolId.SCATTER).with(s.seed, 42L).with(s.spacing, 6).with(s.mirror, false)
                .with(s.columnHeight, new dev.sculptory.fabric.client.editor.settings.SettingDef.IntSpan(2, 5));
        set(ToolId.SCATTER, forest);
        assertTrue(tool.addAsset("trees/oak.schem", OAK));
        assertTrue(tool.addAsset("trees/birch.schem", BIRCH));
        tool.setWeight(1, 3);
        assertTrue(presets.saveAs(ToolId.SCATTER, "Forest"));
        assertEquals("asset:10:" + OAK + ":trees/oak.schem;asset:3:" + BIRCH + ":trees/birch.schem",
                onDisk().tool(ToolId.SCATTER).find("Forest").orElseThrow().extras().get(ScatterMixPreset.KEY));
        assertFalse(presets.isModified(ToolId.SCATTER));

        tool.setWeight(0, 50);
        assertTrue(presets.isModified(ToolId.SCATTER), "a mix change is a change");
        presets.select(ToolId.SCATTER, "");
        assertTrue(tool.mix().isEmpty(), "Default has no variants");
        assertEquals(SettingsValues.defaults(s.schema()), settings(ToolId.SCATTER));

        presets.select(ToolId.SCATTER, "Forest");
        assertEquals(forest, settings(ToolId.SCATTER));
        assertEquals(List.of(assetVariant(OAK, "trees/oak.schem", 10),
                assetVariant(BIRCH, "trees/birch.schem", 3)), tool.mix().variants());
        assertFalse(presets.isModified(ToolId.SCATTER));
    }

    @Test
    void clipboardVariantsAreLeftOutOfAScatterPresetWithANote() {
        ScatterTool tool = scatter();
        tool.replaceMix(List.of(clipboardVariant("my copy", 10),
                assetVariant(OAK, "trees/oak.schem", 5)));
        assertTrue(presets.saveAs(ToolId.SCATTER, "Mixed"));
        assertTrue(rig.notices.contains(Notice.of(Notice.Level.INFO, "sculptory.preset.notice.clipboard_left_out", "1")));
        assertTrue(presets.isModified(ToolId.SCATTER), "the clipboard variant isn't in the preset");
        presets.select(ToolId.SCATTER, "Mixed");
        assertEquals(List.of(assetVariant(OAK, "trees/oak.schem", 5)), tool.mix().variants());
        assertFalse(presets.isModified(ToolId.SCATTER));
    }

    // ---- Applying leniently ----

    @Test
    void applyingClampsToTheServerLimitAndSaysSoWithoutRewritingThePreset() throws IOException {
        BrushSettings raise = brush(ToolId.RAISE);
        set(ToolId.RAISE, settings(ToolId.RAISE).with(raise.radius, 32));
        assertTrue(presets.saveAs(ToolId.RAISE, "Huge"));
        String saved = Files.readString(file());
        rig.session.setPermissions(new Permissions(Perm.mask(EnumSet.allOf(Perm.class)), RADIUS_16));
        assertTrue(presets.isModified(ToolId.RAISE), "the preset gives 16 here, the tool has 32");
        rig.notices.clear();

        presets.select(ToolId.RAISE, "Huge");
        assertEquals(16, settings(ToolId.RAISE).get(raise.radius));
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.preset.notice.adjusted", "Huge",
                "sculptory.setting.brush.radius 32 → 16")), rig.notices);
        assertFalse(presets.isModified(ToolId.RAISE), "what the preset gives here is what the tool has");
        assertEquals(saved, Files.readString(file()), "the preset keeps its 32");
    }

    @Test
    void unknownKeysAndBadValuesAreSkippedWithOneToastAndTheFileIsKept() throws IOException {
        String json = """
                {
                  "version": 1,
                  "tools": {
                    "raise": {
                      "selected": "From the future",
                      "presets": [
                        {"name": "From the future", "values": {"radius": "7", "falloff": "WOBBLY", "brush.spin": "3"}}
                      ]
                    }
                  }
                }
                """;
        Files.writeString(file(), json);
        presets = open();
        rig.notices.clear();
        presets.restoreSelections();
        assertEquals(7, settings(ToolId.RAISE).get(brush(ToolId.RAISE).radius));
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.preset.notice.skipped", "From the future",
                "sculptory.setting.brush.falloff, brush.spin")), rig.notices);
        assertEquals(json, Files.readString(file()), "applying never rewrites the preset");
        assertFalse(presets.isModified(ToolId.RAISE));

        presets.select(ToolId.RAISE, "");
        assertEquals("3", onDisk().tool(ToolId.RAISE).find("From the future").orElseThrow().values().get("brush.spin"),
                "saving the selection keeps the preset's unknown keys");
    }

    @Test
    void aMaterialThisGameDoesNotHaveIsLeftOutWithAToast() {
        BrushSettings paint = brush(ToolId.PAINT);
        set(ToolId.PAINT, settings(ToolId.PAINT).with(paint.material, block("create:andesite_casing")));
        assertTrue(presets.saveAs(ToolId.PAINT, "Casing"));
        blocks = block -> block.block().value().startsWith("minecraft:");
        rig.notices.clear();
        presets.select(ToolId.PAINT, "Casing");
        assertEquals(SettingsValues.defaults(paint.schema()).get(paint.material), settings(ToolId.PAINT).get(paint.material));
        assertEquals(List.of(
                Notice.of(Notice.Level.WARNING, "sculptory.preset.notice.skipped", "Casing",
                        "sculptory.setting.brush.material"),
                Notice.of(Notice.Level.WARNING, "sculptory.preset.notice.unavailable", "Casing",
                        "create:andesite_casing")), rig.notices);
    }

    @Test
    void aBrushMaskWhoseOnlyBlockIsMissingStillMatchesNothingNotEverything() {
        BrushSettings paint = brush(ToolId.PAINT);
        set(ToolId.PAINT, settings(ToolId.PAINT).with(paint.maskBlocks, List.of(block("create:andesite_casing"))));
        assertTrue(presets.saveAs(ToolId.PAINT, "Only casing"));
        set(ToolId.PAINT, SettingsValues.defaults(paint.schema()));
        assertEquals(SurfaceMask.ANY, paint.mask(settings(ToolId.PAINT), rig.states), "the defaults paint anywhere");
        blocks = block -> block.block().value().startsWith("minecraft:");
        rig.notices.clear();

        presets.select(ToolId.PAINT, "Only casing");
        assertEquals(List.of(block("create:andesite_casing")), settings(ToolId.PAINT).get(paint.maskBlocks));
        assertNotEquals(SurfaceMask.ANY, paint.mask(settings(ToolId.PAINT), rig.states),
                "a missing mask block keeps the mask: it paints nowhere here, never everywhere");
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.preset.notice.unmatched", "Only casing",
                "create:andesite_casing")), rig.notices);
        assertFalse(presets.isModified(ToolId.PAINT));
    }

    @Test
    void anInvertedPresetWith17MaskBlocksDoesNotWidenTheMask() throws IOException {
        String seventeen = String.join(";", java.util.Collections.nCopies(17, "minecraft:stone"));
        Files.writeString(file(), """
                {"version": 1, "tools": {"paint": {"selected": "", "presets": [
                  {"name": "Not stone", "values": {"radius": "7", "mask.blocks": "%s", "mask.invert": "true"}}
                ]}}}
                """.formatted(seventeen));
        presets = open();
        BrushSettings paint = brush(ToolId.PAINT);
        SettingsValues before = settings(ToolId.PAINT).with(paint.maskBlocks, List.of(DIRT));
        set(ToolId.PAINT, before);
        SurfaceMask maskBefore = paint.mask(before, rig.states);
        rig.notices.clear();

        presets.select(ToolId.PAINT, "Not stone");
        assertEquals(7, settings(ToolId.PAINT).get(paint.radius), "the rest of the preset applies");
        assertEquals(List.of(DIRT), settings(ToolId.PAINT).get(paint.maskBlocks), "the mask is left as it was");
        assertFalse(settings(ToolId.PAINT).get(paint.maskInvert), "invert too: shortening an inverted list widens it");
        assertEquals(maskBefore, paint.mask(settings(ToolId.PAINT), rig.states));
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.preset.notice.withheld", "Not stone",
                "sculptory.setting.brush.mask", "sculptory.setting.brush.mask.blocks")), rig.notices);
        assertTrue(presets.isModified(ToolId.PAINT), "the tool doesn't show the preset exactly");
        assertTrue(Files.readString(file()).contains(seventeen), "the preset keeps its 17 blocks");
    }

    @Test
    void aScatterFilterWhoseBlockIsMissingStaysAFilter() {
        ScatterToolSettings s = scatter().settings();
        set(ToolId.SCATTER, settings(ToolId.SCATTER).with(s.substrate, List.of(block("biomesoplenty:mud"))));
        assertTrue(presets.saveAs(ToolId.SCATTER, "Mud only"));
        set(ToolId.SCATTER, SettingsValues.defaults(s.schema()));
        blocks = block -> block.block().value().startsWith("minecraft:");
        presets.select(ToolId.SCATTER, "Mud only");
        assertNotEquals(SurfaceMask.ANY, s.surface(settings(ToolId.SCATTER)), "scatters nowhere here, not everywhere");
    }

    @Test
    void unreadableMixEntriesAndUnknownExtrasAreSkippedWithTheSettings() throws IOException {
        String mix = "asset:10:" + OAK + ":trees/oak.schem;asset:0:" + BIRCH + ":trees/zero.schem;asset:5:nothex:trees/bad.schem";
        Files.writeString(file(), """
                {"version": 1, "tools": {"scatter": {"selected": "", "presets": [
                  {"name": "Broken mix", "values": {"spacing": "lots"}, "extra": {"mix": "%s", "stamp": "3"}}
                ]}}}
                """.formatted(mix));
        presets = open();
        rig.notices.clear();
        presets.select(ToolId.SCATTER, "Broken mix");
        assertEquals(1, scatter().mix().size());
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.preset.notice.skipped", "Broken mix",
                "sculptory.setting.scatter.spacing, trees/zero.schem, trees/bad.schem, stamp")), rig.notices,
                "one toast for everything that couldn't be read");
    }

    // ---- Saving that fails ----

    @Test
    void aSaveThatFailsLeavesTheListsAsTheFileHasThem() throws IOException {
        assertTrue(presets.saveAs(ToolId.RAISE, "Kept"));
        String saved = Files.readString(file());
        Path lock = dir.resolve(PresetStore.FILE_NAME + ".lock");
        Files.deleteIfExists(lock);
        Files.createDirectory(lock); // the store can't take its lock: the save fails, the file is untouched
        fileProblems.clear();

        assertFalse(presets.saveAs(ToolId.RAISE, "Lost"));
        assertEquals(List.of("Kept"), presets.names(ToolId.RAISE), "the new preset isn't shown as if saved");
        assertEquals("Kept", presets.selected(ToolId.RAISE));
        assertFalse(presets.delete(ToolId.RAISE, "Kept"));
        assertEquals(List.of("Kept"), presets.names(ToolId.RAISE), "nor is a delete that wasn't saved");
        assertFalse(presets.rename(ToolId.RAISE, "Kept", "Renamed"));
        assertEquals(List.of("Kept"), presets.names(ToolId.RAISE));
        assertEquals(3, fileProblems.size(), "each failure is reported");
        assertEquals(saved, Files.readString(file()));
        assertFalse(presets.readOnly(), "a failure that can be retried doesn't stop saving");

        Files.delete(lock);
        assertTrue(presets.saveAs(ToolId.RAISE, "Lost"));
        assertEquals(List.of("Kept", "Lost"), onDisk().tool(ToolId.RAISE).names());
    }

    @Test
    void aFileChangedOutsideTheGameStopsSavingAndIsNotOverwritten() throws IOException {
        assertTrue(presets.saveAs(ToolId.RAISE, "Mine"));
        String outside = Files.readString(file()).replace("Mine", "Edited by hand");
        Files.writeString(file(), outside);
        fileProblems.clear();

        assertFalse(presets.saveAs(ToolId.RAISE, "Another"));
        assertEquals(List.of("Mine"), presets.names(ToolId.RAISE));
        assertEquals(Presets.Access.SAVING_STOPPED, presets.access());
        assertEquals(1, fileProblems.size());
        assertTrue(fileProblems.get(0).contains("changed outside"), fileProblems.get(0));
        presets.select(ToolId.RAISE, "");
        assertFalse(presets.save(ToolId.RAISE));
        assertEquals(1, fileProblems.size(), "reported once, not at every change");
        assertEquals(outside, Files.readString(file()));

        Presets restarted = open();
        assertEquals(List.of("Edited by hand"), restarted.names(ToolId.RAISE), "a restart loads the edit");
        assertFalse(restarted.readOnly());
    }

    // ---- The file ----

    @Test
    void aFileOfAnotherVersionMakesPresetsReadOnlyAndIsLeftAlone() throws IOException {
        String newer = "{\"version\": 2, \"tools\": {}}";
        Files.writeString(file(), newer);
        presets = open();
        assertTrue(presets.readOnly());
        assertEquals(1, fileProblems.size());
        set(ToolId.RAISE, settings(ToolId.RAISE).with(brush(ToolId.RAISE).radius, 9));
        assertFalse(presets.saveAs(ToolId.RAISE, "Nope"));
        assertEquals(List.of(), presets.names(ToolId.RAISE));
        presets.select(ToolId.RAISE, "");
        assertEquals(5, settings(ToolId.RAISE).get(brush(ToolId.RAISE).radius), "Default still loads");
        assertEquals(newer, Files.readString(file()));
    }

    @Test
    void unreadablePresetsAreSkippedWithAToast() throws IOException {
        Files.writeString(file(), "{\"version\": 1, \"tools\": {\"raise\": {\"presets\": [{\"name\": \"\"}, "
                + "{\"name\": \"Fine\", \"values\": {}}]}}}");
        rig.notices.clear();
        presets = open();
        assertEquals(List.of("Fine"), presets.names(ToolId.RAISE));
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.preset.notice.unreadable",
                PresetStore.FILE_NAME, "1")), rig.notices);
        assertFalse(presets.readOnly());
        presets.select(ToolId.RAISE, "Fine");
        JsonArray entries = JsonParser.parseString(Files.readString(file())).getAsJsonObject()
                .getAsJsonObject("tools").getAsJsonObject("raise").getAsJsonArray("presets");
        assertEquals(2, entries.size(), "a save (here the new selection) writes the unreadable entry back");
        assertEquals("{\"name\":\"\"}", entries.get(1).toString());
        assertEquals("Fine", onDisk().tool(ToolId.RAISE).selected());
    }

    @Test
    void theRememberedPresetComesBackOnceAfterARestart() {
        BrushSettings smooth = brush(ToolId.SMOOTH);
        SettingsValues wide = settings(ToolId.SMOOTH).with(smooth.radius, 20);
        set(ToolId.SMOOTH, wide);
        assertTrue(presets.saveAs(ToolId.SMOOTH, "Wide"));
        set(ToolId.SMOOTH, SettingsValues.defaults(smooth.schema()));

        Presets restarted = open();
        assertEquals("Wide", restarted.selected(ToolId.SMOOTH));
        restarted.restoreSelections();
        assertEquals(wide, settings(ToolId.SMOOTH));
        set(ToolId.SMOOTH, wide.with(smooth.radius, 3));
        restarted.restoreSelections();
        assertEquals(3, settings(ToolId.SMOOTH).get(smooth.radius), "only the first time the editor opens");
    }

    @Test
    void renameAndDeleteKeepTheFileAndSelectionInStep() throws IOException {
        BrushSettings lower = brush(ToolId.LOWER);
        SettingsValues deep = settings(ToolId.LOWER).with(lower.radius, 8);
        set(ToolId.LOWER, deep);
        assertTrue(presets.saveAs(ToolId.LOWER, "Deep"));
        assertTrue(presets.saveAs(ToolId.LOWER, "Other"));
        presets.select(ToolId.LOWER, "Deep");

        assertFalse(presets.rename(ToolId.LOWER, "Deep", "other"), "names are unique ignoring case");
        assertTrue(presets.rename(ToolId.LOWER, "Deep", "Deeper"));
        assertEquals("Deeper", presets.selected(ToolId.LOWER));
        assertEquals(List.of("Deeper", "Other"), onDisk().tool(ToolId.LOWER).names());
        assertEquals("Deeper", onDisk().tool(ToolId.LOWER).selected());

        assertTrue(presets.delete(ToolId.LOWER, "Deeper"));
        assertEquals("", presets.selected(ToolId.LOWER));
        assertEquals(deep, settings(ToolId.LOWER), "deleting leaves the settings alone");
        assertTrue(presets.isModified(ToolId.LOWER), "which now differ from Default");
        assertEquals(List.of("Other"), onDisk().tool(ToolId.LOWER).names());
    }

    @Test
    void saveOverwritesTheSelectedPresetButNotDefault() throws IOException {
        BrushSettings flatten = brush(ToolId.FLATTEN);
        assertFalse(presets.save(ToolId.FLATTEN), "Default can't be overwritten");
        assertTrue(presets.saveAs(ToolId.FLATTEN, "Mine"));
        set(ToolId.FLATTEN, settings(ToolId.FLATTEN).with(flatten.radius, 11));
        assertTrue(presets.save(ToolId.FLATTEN));
        assertEquals("11", onDisk().tool(ToolId.FLATTEN).find("Mine").orElseThrow().values().get("radius"));
        assertFalse(presets.isModified(ToolId.FLATTEN));
    }

    @Test
    void namesAreCheckedAndEachToolKeepsAtMost100() {
        assertFalse(presets.saveAs(ToolId.PALETTE, "   "));
        assertFalse(presets.saveAs(ToolId.PALETTE, "x".repeat(49)));
        assertFalse(presets.saveAs(ToolId.PALETTE, "sculptory.preset.default"), "the built-in entry's name is taken");
        assertEquals("sculptory.preset.name.taken",
                presets.nameProblem(ToolId.PALETTE, "sculptory.preset.default", null).messageKey());
        for (int i = 0; i < PresetNames.MAX_PER_TOOL; i++) {
            assertTrue(presets.saveAs(ToolId.PALETTE, "p" + i));
        }
        assertTrue(presets.isFull(ToolId.PALETTE));
        assertFalse(presets.saveAs(ToolId.PALETTE, "one more"));
        assertEquals(PresetNames.MAX_PER_TOOL, presets.names(ToolId.PALETTE).size());
        assertTrue(presets.saveAs(ToolId.PAINT, "p0"), "the limit and the names are per tool");
    }

    @Test
    void theVersionMovesWithEveryChange() {
        int start = presets.version();
        assertTrue(presets.saveAs(ToolId.RAISE, "A"));
        int saved = presets.version();
        assertNotEquals(start, saved);
        presets.select(ToolId.RAISE, "A");
        assertEquals(saved, presets.version(), "selecting what is selected changes nothing");
        presets.select(ToolId.RAISE, "");
        assertNotEquals(saved, presets.version());
    }

    private static ScatterMix.Variant assetVariant(String hash, String name, int weight) {
        return new ScatterMix.Variant(new ScatterSource.Held(new SourceRef.Asset(hash)), name, weight);
    }

    private static ScatterMix.Variant clipboardVariant(String name, int weight) {
        return new ScatterMix.Variant(new ScatterSource.Held(new SourceRef.Clipboard(UUID.randomUUID())), name, weight);
    }

    /**
     * Select and Place presets written before their symmetry and Into existed (no "symmetry" or "into" value) still
     * load, with the symmetry Off and Into Everything, silently and without rewriting the file; a saved one keeps
     * its mode and its Into.
     */
    @Test
    void selectAndPlacePresetsSavedBeforeSymmetryLoadAsOff() throws IOException {
        String json = """
                {
                  "version": 1,
                  "tools": {
                    "select": {
                      "selected": "Thick",
                      "presets": [
                        {"name": "Thick", "values": {"fill_with": "ACTIVE_BLOCK", "thickness": "3"}}
                      ]
                    },
                    "place": {
                      "selected": "Airy",
                      "presets": [
                        {"name": "Airy", "values": {"include_air": "true", "physics": "false", "entities": "NONE"}}
                      ]
                    }
                  }
                }
                """;
        Files.writeString(file(), json);
        PlaceSettings place = rig.place.settings();
        set(ToolId.SELECT, settings(ToolId.SELECT).with(SelectSettings.SYMMETRY, Symmetry.Mode.MIRROR_XZ)
                .with(SelectSettings.INTO, PasteOptions.Into.AIR));
        set(ToolId.PLACE, settings(ToolId.PLACE).with(place.symmetry(), Symmetry.Mode.ROTATE_2)
                .with(place.into(), PasteOptions.Into.EXISTING));
        presets = open();
        rig.notices.clear();
        presets.restoreSelections();
        assertEquals(3, settings(ToolId.SELECT).get(SelectSettings.THICKNESS));
        assertEquals(Symmetry.Mode.OFF, settings(ToolId.SELECT).get(SelectSettings.SYMMETRY), "no symmetry saved: Off");
        assertEquals(PasteOptions.Into.EVERYTHING, settings(ToolId.SELECT).get(SelectSettings.INTO), "no into saved: Everything");
        assertTrue(settings(ToolId.PLACE).get(place.includeAir()));
        assertEquals(Symmetry.Mode.OFF, settings(ToolId.PLACE).get(place.symmetry()), "no symmetry saved: Off");
        assertEquals(PasteOptions.Into.EVERYTHING, settings(ToolId.PLACE).get(place.into()), "no into saved: Everything");
        assertEquals(List.of(), rig.notices, "nothing was skipped or changed");
        assertFalse(presets.isModified(ToolId.SELECT));
        assertFalse(presets.isModified(ToolId.PLACE));
        assertEquals(json, Files.readString(file()), "applying never rewrites the preset");

        SettingsValues mirrored = settings(ToolId.SELECT).with(SelectSettings.SYMMETRY, Symmetry.Mode.MIRROR_Z)
                .with(SelectSettings.INTO, PasteOptions.Into.AIR);
        set(ToolId.SELECT, mirrored);
        assertTrue(presets.saveAs(ToolId.SELECT, "Mirrored"));
        assertEquals("MIRROR_Z", onDisk().tool(ToolId.SELECT).find("Mirrored").orElseThrow().values().get("symmetry"));
        assertEquals("AIR", onDisk().tool(ToolId.SELECT).find("Mirrored").orElseThrow().values().get("into"));
        SettingsValues turned = settings(ToolId.PLACE).with(place.symmetry(), Symmetry.Mode.ROTATE_4)
                .with(place.into(), PasteOptions.Into.EXISTING);
        set(ToolId.PLACE, turned);
        assertTrue(presets.saveAs(ToolId.PLACE, "Turned"));
        assertEquals("EXISTING", onDisk().tool(ToolId.PLACE).find("Turned").orElseThrow().values().get("into"));
        presets.select(ToolId.PLACE, "");
        assertEquals(Symmetry.Mode.OFF, settings(ToolId.PLACE).get(place.symmetry()));
        presets.select(ToolId.PLACE, "Turned");
        assertEquals(turned, settings(ToolId.PLACE));
    }
}
