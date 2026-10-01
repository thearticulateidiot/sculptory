package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.palette.BlockPalette;
import dev.sculptory.core.palette.PalettePattern;
import dev.sculptory.core.scatter.ScatterSource;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.fabric.client.editor.palettes.PaletteActions;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tools.brush.BrushSettings;
import dev.sculptory.fabric.client.editor.tools.brush.ShapeSettings;
import dev.sculptory.fabric.client.editor.tools.brush.TerrainBrushTool;
import dev.sculptory.fabric.client.editor.tools.scatter.ScatterMix;
import dev.sculptory.fabric.client.editor.tools.scatter.ScatterTool;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.LoadedPalette;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Permissions;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.protocol.v2.Limits;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Palettes in the Paint, Palette Paint and Scatter tools on the mock session: what each tool saves, what a load does to
 * it (and to nothing else), and the toasts about what was left out.
 */
class PaletteActionsTest {
    private static final String OAK = "ab".repeat(32);

    private final EditorTestRig rig = new EditorTestRig();
    private final List<ToolId> selected = new ArrayList<>();
    private PaletteActions actions;

    private static BlockDescriptor block(String text) {
        return BlockDescriptor.parse(text);
    }

    private BrushSettings brush(ToolId id) {
        return ((TerrainBrushTool) rig.ctx.tools().get(id).orElseThrow()).settings();
    }

    private ScatterTool scatter() {
        return (ScatterTool) rig.ctx.tools().get(ToolId.SCATTER).orElseThrow();
    }

    @BeforeEach
    void setUp() {
        // The Scatter tool resolves block variants in the active tool's context, as when its panel is shown.
        assertTrue(rig.ctx.tools().activate(ToolId.SCATTER, rig.ctx.contextFor(ToolId.SCATTER)));
        actions = new PaletteActions(new PaletteActions.Host() {
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
                selected.add(id);
                return true;
            }

            @Override
            public void notify(Notice notice) {
                rig.notices.add(notice);
            }

            @Override
            public Optional<StateSpace> states() {
                return Optional.of(rig.states);
            }
        }, brush(ToolId.PAINT), brush(ToolId.PALETTE), scatter());
    }

    private List<String> noticeKeys() {
        return rig.notices.stream().map(Notice::key).toList();
    }

    private Notice notice(String key) {
        return rig.notices.stream().filter(n -> n.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + key + " in " + rig.notices));
    }

    private SettingsValues settings(ToolId id) {
        return rig.ctx.settings(id);
    }

    private static LoadedPalette loaded(String path, String... states) {
        List<BlockPalette.Entry> entries = new ArrayList<>();
        for (int i = 0; i < states.length; i++) entries.add(new BlockPalette.Entry(states[i], i + 1));
        return new LoadedPalette(path, new BlockPalette(entries));
    }

    // ---------------------------------------------------------------- patterns

    /**
     * Mix patterns: Palette Paint and the Shape brush save their mix in order
     * with their Pattern settings, Paint and Scatter save Random; a load sets the pattern; a Steepness palette loads
     * into the Shape brush as Random, saying so; a palette saved before patterns loads as Random.
     */
    @Test
    void palettesKeepTheOrderAndThePattern() {
        BrushSettings palette = brush(ToolId.PALETTE);
        rig.ctx.updateSettings(ToolId.PALETTE, settings(ToolId.PALETTE).with(palette.palette, List.of(
                        new SettingDef.WeightedBlock(block("minecraft:stone"), 1),
                        new SettingDef.WeightedBlock(block("minecraft:dirt"), 2)))
                .with(palette.mixPattern.pattern, PalettePattern.Kind.GRADIENT)
                .with(palette.mixPattern.edge, 12)
                .with(palette.mixPattern.seed, 99L));
        BlockPalette saved = actions.capture(ToolId.PALETTE).orElseThrow().palette();
        assertEquals(List.of(new BlockPalette.Entry("minecraft:stone", 1), new BlockPalette.Entry("minecraft:dirt", 2)),
                saved.entries(), "in the mix's order");
        assertEquals(new PalettePattern(PalettePattern.Kind.GRADIENT, 6, 12, 10, 99L), saved.pattern());
        rig.ctx.updateSettings(ToolId.PAINT, settings(ToolId.PAINT).with(brush(ToolId.PAINT).material,
                block("minecraft:stone")));
        assertEquals(PalettePattern.RANDOM, actions.capture(ToolId.PAINT).orElseThrow().palette().pattern());

        PalettePattern steep = new PalettePattern(PalettePattern.Kind.STEEPNESS, 4, 2, 20, -5L);
        BlockPalette hills = new BlockPalette(List.of(new BlockPalette.Entry("minecraft:dirt", 3),
                new BlockPalette.Entry("minecraft:stone", 1)), steep);
        assertTrue(actions.apply(ToolId.PALETTE, new LoadedPalette("hills.palette.json", hills)));
        assertEquals(PalettePattern.Kind.STEEPNESS, settings(ToolId.PALETTE).get(palette.mixPattern.pattern));
        assertEquals(20, settings(ToolId.PALETTE).get(palette.mixPattern.steepnessEdge));
        assertEquals(block("minecraft:dirt"), settings(ToolId.PALETTE).get(palette.palette).get(0).block());
        assertEquals(steep, actions.capture(ToolId.PALETTE).orElseThrow().palette().pattern(), "saved back the same");

        assertTrue(actions.apply(ToolId.SHAPE, new LoadedPalette("hills.palette.json", hills)));
        assertEquals(PalettePattern.Kind.RANDOM, settings(ToolId.SHAPE).get(ShapeSettings.PATTERN.pattern),
                "the Shape brush can't lay out by steepness");
        assertEquals(4, settings(ToolId.SHAPE).get(ShapeSettings.PATTERN.patchSize));
        assertTrue(noticeKeys().contains("sculptory.palette.steepness_as_random"));

        assertTrue(actions.apply(ToolId.PALETTE, loaded("old.palette.json", "minecraft:stone", "minecraft:dirt")));
        assertEquals(PalettePattern.Kind.RANDOM, settings(ToolId.PALETTE).get(palette.mixPattern.pattern),
                "a palette from before patterns is Random");
    }

    // ---------------------------------------------------------------- save

    @Test
    void eachToolSavesItsBlocksAsExactStates() {
        rig.ctx.updateSettings(ToolId.PAINT, settings(ToolId.PAINT).with(brush(ToolId.PAINT).material,
                block("minecraft:grass_block")));
        assertEquals(new PaletteActions.Capture(BlockPalette.of("minecraft:grass_block[snowy=false]", 1), 0),
                actions.capture(ToolId.PAINT).orElseThrow(), "Paint: its material, as this game's exact state");

        rig.ctx.updateSettings(ToolId.PALETTE, settings(ToolId.PALETTE).with(brush(ToolId.PALETTE).palette, List.of(
                new SettingDef.WeightedBlock(block("minecraft:stone"), 3),
                new SettingDef.WeightedBlock(block("minecraft:sea_pickle[waterlogged=true]"), 2),
                new SettingDef.WeightedBlock(block("minecraft:grass_block"), 999),
                new SettingDef.WeightedBlock(block("minecraft:grass_block[snowy=false]"), 5))));
        assertEquals(new BlockPalette(List.of(new BlockPalette.Entry("minecraft:stone", 3),
                new BlockPalette.Entry("minecraft:sea_pickle[pickles=1,waterlogged=true]", 2),
                new BlockPalette.Entry("minecraft:grass_block[snowy=false]", 1000))),
                actions.capture(ToolId.PALETTE).orElseThrow().palette(),
                "Palette Paint: its weighted blocks, the same state merged (weights at most 1000)");

        ScatterTool tool = scatter();
        tool.replaceMix(List.of(
                new ScatterMix.Variant(new ScatterSource.Held(new SourceRef.Asset(OAK)), "trees/oak.schem", 7),
                new ScatterMix.Variant(new ScatterSource.Block("minecraft:poppy"), "Poppy", 4),
                new ScatterMix.Variant(new ScatterSource.Held(new SourceRef.Clipboard(new UUID(1, 1))), "copy", 2),
                new ScatterMix.Variant(new ScatterSource.Block("minecraft:seagrass"), "Seagrass", 9)));
        assertEquals(new PaletteActions.Capture(new BlockPalette(List.of(new BlockPalette.Entry("minecraft:poppy", 4),
                new BlockPalette.Entry("minecraft:seagrass", 9))), 2), actions.capture(ToolId.SCATTER).orElseThrow(),
                "Scatter: its block rows only, the others counted");
        assertTrue(rig.notices.isEmpty(), "capturing alone says nothing: " + rig.notices);
    }

    @Test
    void savingSendsThePaletteAndSaysWhereItWentAndWhatWasLeftOut() {
        scatter().replaceMix(List.of(
                new ScatterMix.Variant(new ScatterSource.Held(new SourceRef.Asset(OAK)), "trees/oak.schem", 7),
                new ScatterMix.Variant(new ScatterSource.Block("minecraft:poppy"), "Poppy", 4)));
        assertEquals("palettes/my_palette.palette.json", actions.suggestedPath());
        assertTrue(actions.save(ToolId.SCATTER, "flowers/meadow.palette.json").toCompletableFuture().join());
        assertEquals(BlockPalette.of("minecraft:poppy", 4), rig.session.palettes().get("flowers/meadow.palette.json"));
        assertEquals(Notice.of(Notice.Level.SUCCESS, "sculptory.palette.saved", "flowers/meadow.palette.json", "1"),
                notice("sculptory.palette.saved"));
        assertEquals(Notice.of(Notice.Level.INFO, "sculptory.palette.left_out", "1"),
                notice("sculptory.palette.left_out"));
        assertEquals("flowers/my_palette.palette.json", actions.suggestedPath(), "the folder is remembered");
        assertEquals("flowers", actions.folder());
        assertEquals(List.of(scatter().mix().variants().get(0).source(), scatter().mix().variants().get(1).source()),
                List.of(new ScatterSource.Held(new SourceRef.Asset(OAK)), new ScatterSource.Block("minecraft:poppy")),
                "saving changes nothing in the tool");
    }

    @Test
    void aMixWithoutBlocksHasNothingToSave() {
        scatter().replaceMix(List.of(
                new ScatterMix.Variant(new ScatterSource.Held(new SourceRef.Asset(OAK)), "trees/oak.schem", 7)));
        assertTrue(actions.capture(ToolId.SCATTER).isEmpty());
        assertFalse(actions.save(ToolId.SCATTER, "a.palette.json").toCompletableFuture().join());
        assertTrue(rig.session.palettes().isEmpty());
        assertTrue(noticeKeys().contains("sculptory.palette.nothing_to_save"), noticeKeys().toString());
    }

    @Test
    void aRefusedSaveChangesNothingAndTheSessionSaysWhy() {
        rig.session.setPermissions(new Permissions(Perm.mask(EnumSet.of(Perm.USE, Perm.BRUSH)), Limits.DEFAULTS));
        assertFalse(actions.save(ToolId.PAINT, "a.palette.json").toCompletableFuture().join());
        assertTrue(rig.session.palettes().isEmpty());
        assertFalse(noticeKeys().contains("sculptory.palette.saved"));
        assertEquals("", actions.folder(), "no folder remembered from a refusal");
    }

    // ---------------------------------------------------------------- load into brushes

    /**
     * The Shape brush: Save palette… saves its mix; loading a palette replaces the mix and sets the brush to place it,
     * leaving every other setting as it was.
     */
    @Test
    void theShapeBrushSavesAndLoadsItsMix() {
        assertTrue(actions.supports(ToolId.SHAPE));
        rig.ctx.updateSettings(ToolId.SHAPE, settings(ToolId.SHAPE).with(ShapeSettings.PALETTE, List.of(
                new SettingDef.WeightedBlock(block("minecraft:stone"), 3),
                new SettingDef.WeightedBlock(block("minecraft:grass_block"), 2))));
        assertEquals(new BlockPalette(List.of(new BlockPalette.Entry("minecraft:stone", 3),
                new BlockPalette.Entry("minecraft:grass_block[snowy=false]", 2))),
                actions.capture(ToolId.SHAPE).orElseThrow().palette());

        SettingsValues before = settings(ToolId.SHAPE).with(ShapeSettings.RADIUS, 7);
        rig.ctx.updateSettings(ToolId.SHAPE, before);
        assertEquals(ShapeSettings.Blocks.ACTIVE_BLOCK, before.get(ShapeSettings.BLOCKS));
        rig.session.palettes().put("rock.palette.json", loaded("rock.palette.json", "minecraft:dirt", "minecraft:sand")
                .palette());
        assertTrue(actions.load(ToolId.SHAPE, "rock.palette.json").toCompletableFuture().join());
        SettingsValues after = settings(ToolId.SHAPE);
        List<SettingDef.WeightedBlock> mix = List.of(new SettingDef.WeightedBlock(block("minecraft:dirt"), 1),
                new SettingDef.WeightedBlock(block("minecraft:sand"), 2));
        assertEquals(before.with(ShapeSettings.PALETTE, mix).with(ShapeSettings.BLOCKS, ShapeSettings.Blocks.PALETTE), after);
        assertEquals(Notice.of(Notice.Level.SUCCESS, "sculptory.palette.loaded", "rock", "2"),
                notice("sculptory.palette.loaded"));
        assertTrue(selected.isEmpty(), "no other tool comes up");
    }

    @Test
    void aPaletteReplacesPalettePaintsMixAndNothingElse() {
        SettingsValues before = settings(ToolId.PALETTE).with(brush(ToolId.PALETTE).radius, 9);
        rig.ctx.updateSettings(ToolId.PALETTE, before);
        rig.session.palettes().put("moss.palette.json", loaded("moss.palette.json", "minecraft:stone",
                "minecraft:sea_pickle[pickles=2,waterlogged=true]").palette());
        assertTrue(actions.load(ToolId.PALETTE, "moss.palette.json").toCompletableFuture().join());
        SettingsValues after = settings(ToolId.PALETTE);
        assertEquals(List.of(new SettingDef.WeightedBlock(block("minecraft:stone"), 1),
                new SettingDef.WeightedBlock(block("minecraft:sea_pickle[pickles=2,waterlogged=true]"), 2)),
                after.get(brush(ToolId.PALETTE).palette), "exact states, waterlogged kept");
        assertEquals(before.with(brush(ToolId.PALETTE).palette, after.get(brush(ToolId.PALETTE).palette)), after,
                "every other setting as it was");
        assertEquals(Notice.of(Notice.Level.SUCCESS, "sculptory.palette.loaded", "moss", "2"),
                notice("sculptory.palette.loaded"));
        assertEquals(List.of(), selected);
    }

    /** {@code count} distinct oak stair states (at most 80), as exact state text. */
    private static List<String> stairStates(int count) {
        List<String> states = new ArrayList<>();
        for (String waterlogged : List.of("false", "true")) {
            for (String shape : List.of("straight", "inner_left", "inner_right", "outer_left", "outer_right")) {
                for (String half : List.of("bottom", "top")) {
                    for (String facing : List.of("north", "east", "south", "west")) {
                        if (states.size() == count) return states;
                        states.add("minecraft:oak_stairs[facing=" + facing + ",half=" + half + ",shape=" + shape
                                + ",waterlogged=" + waterlogged + "]");
                    }
                }
            }
        }
        return states;
    }

    @Test
    void aFullPaletteLoadsWholeAndUnknownStatesAreToldAbout() {
        assertEquals(BlockPalette.MAX_ENTRIES, BrushSettings.MAX_PALETTE_ENTRIES, "Palette Paint holds a whole palette");
        rig.notices.clear();
        List<String> states = stairStates(BlockPalette.MAX_ENTRIES);
        assertTrue(actions.apply(ToolId.PALETTE, loaded("full.palette.json", states.toArray(new String[0]))));
        List<SettingDef.WeightedBlock> mix = settings(ToolId.PALETTE).get(brush(ToolId.PALETTE).palette);
        assertEquals(64, mix.size());
        for (int i = 0; i < 64; i++) {
            assertEquals(new SettingDef.WeightedBlock(block(states.get(i)), i + 1), mix.get(i), "entry " + i);
        }
        assertEquals(Notice.of(Notice.Level.SUCCESS, "sculptory.palette.loaded", "full", "64"),
                notice("sculptory.palette.loaded"));
        assertEquals(List.of("sculptory.palette.loaded"), noticeKeys(), "nothing was left out");

        // With a state this game doesn't know and two the server dropped: the rest load, in order.
        rig.notices.clear();
        List<String> all = new ArrayList<>(states.subList(0, 63));
        all.add(3, "modded:unknown_here");
        LoadedPalette big = new LoadedPalette("big.palette.json",
                loaded("big.palette.json", all.toArray(new String[0])).palette(), 2, List.of("modded:a", "modded:b"));
        assertTrue(actions.apply(ToolId.PALETTE, big));
        mix = settings(ToolId.PALETTE).get(brush(ToolId.PALETTE).palette);
        assertEquals(63, mix.size());
        assertEquals(block(states.get(0)), mix.get(0).block());
        assertEquals(block(states.get(3)), mix.get(3).block(), "the unknown one left out, the order kept");
        assertEquals(block(states.get(62)), mix.get(62).block());
        assertEquals(Notice.of(Notice.Level.WARNING, "sculptory.palette.server_dropped", "2", "big",
                "modded:a, modded:b"), notice("sculptory.palette.server_dropped"));
        assertEquals(Notice.of(Notice.Level.WARNING, "sculptory.palette.unknown_here", "1", "modded:unknown_here"),
                notice("sculptory.palette.unknown_here"));
        assertEquals(Notice.of(Notice.Level.SUCCESS, "sculptory.palette.loaded", "big", "63"),
                notice("sculptory.palette.loaded"));

        // Into Paint: a full palette switches to Palette Paint, whole.
        rig.notices.clear();
        assertTrue(actions.apply(ToolId.PAINT, loaded("full.palette.json", states.toArray(new String[0]))));
        assertEquals(64, settings(ToolId.PALETTE).get(brush(ToolId.PALETTE).palette).size());
        assertEquals(List.of(ToolId.PALETTE), selected);
        assertEquals(Notice.of(Notice.Level.SUCCESS, "sculptory.palette.loaded_switched", "full", "64"),
                notice("sculptory.palette.loaded_switched"));
        // Saving it back keeps all 64.
        PaletteActions.Capture saved = actions.capture(ToolId.PALETTE).orElseThrow();
        assertEquals(64, saved.palette().size());
        assertEquals(0, saved.leftOut());
    }

    @Test
    void intoPaintOneBlockIsTheMaterialAndMoreSwitchToPalettePaint() {
        SettingsValues paintBefore = settings(ToolId.PAINT);
        assertTrue(actions.apply(ToolId.PAINT, loaded("one.palette.json", "minecraft:oak_log[axis=x]")));
        assertEquals(paintBefore.with(brush(ToolId.PAINT).material, block("minecraft:oak_log[axis=x]")),
                settings(ToolId.PAINT));
        assertEquals(List.of(), selected, "Paint stays Paint");
        assertEquals(Notice.of(Notice.Level.SUCCESS, "sculptory.palette.loaded_material", "one",
                "minecraft:oak_log[axis=x]"), notice("sculptory.palette.loaded_material"));

        SettingsValues paint = settings(ToolId.PAINT);
        assertTrue(actions.apply(ToolId.PAINT, loaded("two.palette.json", "minecraft:stone", "minecraft:dirt")));
        assertEquals(paint, settings(ToolId.PAINT), "Paint's material stays");
        assertEquals(List.of(new SettingDef.WeightedBlock(block("minecraft:stone"), 1),
                new SettingDef.WeightedBlock(block("minecraft:dirt"), 2)),
                settings(ToolId.PALETTE).get(brush(ToolId.PALETTE).palette));
        assertEquals(List.of(ToolId.PALETTE), selected, "switched to Palette Paint");
        assertTrue(noticeKeys().contains("sculptory.palette.loaded_switched"));

        // A two-block palette with one block unknown here is a one-block palette for Paint.
        selected.clear();
        assertTrue(actions.apply(ToolId.PAINT, loaded("mixed.palette.json", "modded:gone", "minecraft:sand")));
        assertEquals(block("minecraft:sand"), settings(ToolId.PAINT).get(brush(ToolId.PAINT).material));
        assertEquals(List.of(), selected);

        // Nothing usable: nothing changes.
        SettingsValues now = settings(ToolId.PAINT);
        assertFalse(actions.apply(ToolId.PAINT, loaded("gone.palette.json", "modded:gone")));
        assertEquals(now, settings(ToolId.PAINT));
        assertTrue(noticeKeys().contains("sculptory.palette.nothing_usable"));
    }

    // ---------------------------------------------------------------- load into Scatter

    @Test
    void intoScatterThePaletteReplacesTheBlockRowsAndKeepsAssetRows() {
        ScatterTool tool = scatter();
        ScatterMix.Variant oak = new ScatterMix.Variant(new ScatterSource.Held(new SourceRef.Asset(OAK)),
                "trees/oak.schem", 7);
        ScatterMix.Variant copy = new ScatterMix.Variant(
                new ScatterSource.Held(new SourceRef.Clipboard(new UUID(2, 2))), "my copy", 3);
        tool.replaceMix(List.of(new ScatterMix.Variant(new ScatterSource.Block("minecraft:poppy"), "Poppy", 4), oak,
                copy));
        SettingsValues settingsBefore = settings(ToolId.SCATTER);
        rig.session.palettes().put("reef.palette.json", loaded("reef.palette.json", "minecraft:seagrass",
                "minecraft:sea_pickle[pickles=2,waterlogged=true]", "minecraft:air", "minecraft:tall_grass[half=upper]",
                "minecraft:tall_grass[half=lower]").palette());
        assertTrue(actions.load(ToolId.SCATTER, "reef.palette.json").toCompletableFuture().join());
        List<ScatterMix.Variant> mix = tool.mix().variants();
        assertEquals(List.of(oak.source(), copy.source(), new ScatterSource.Block("minecraft:seagrass"),
                new ScatterSource.Block("minecraft:sea_pickle[pickles=2,waterlogged=true]"),
                new ScatterSource.Block("minecraft:tall_grass[half=lower]")),
                mix.stream().map(ScatterMix.Variant::source).toList(),
                "asset and clipboard rows kept, the poppy row replaced, a waterlogged state kept, both halves as one");
        assertEquals(List.of(7, 3, 1, 2, 9), mix.stream().map(ScatterMix.Variant::weight).toList(),
                "the halves' weights added");
        assertEquals(settingsBefore, settings(ToolId.SCATTER), "the Scatter settings are untouched");
        assertEquals(Notice.of(Notice.Level.WARNING, "sculptory.palette.not_scatterable", "1", "minecraft:air"),
                notice("sculptory.palette.not_scatterable"));
        assertEquals(Notice.of(Notice.Level.SUCCESS, "sculptory.palette.loaded_scatter", "reef", "3", "2"),
                notice("sculptory.palette.loaded_scatter"));
    }

    @Test
    void intoAFullScatterMixOnlyWhatFitsIsTaken() {
        List<ScatterMix.Variant> assets = new ArrayList<>();
        for (int i = 0; i < ScatterMix.MAX_VARIANTS - 1; i++) {
            String hash = String.format(java.util.Locale.ROOT, "%064x", i + 1);
            assets.add(new ScatterMix.Variant(new ScatterSource.Held(new SourceRef.Asset(hash)), "a" + i, 1));
        }
        scatter().replaceMix(assets);
        assertTrue(actions.apply(ToolId.SCATTER, loaded("two.palette.json", "minecraft:poppy", "minecraft:seagrass")));
        assertEquals(ScatterMix.MAX_VARIANTS, scatter().mix().size());
        assertEquals(new ScatterSource.Block("minecraft:poppy"), scatter().mix().variants().get(63).source());
        assertEquals(Notice.of(Notice.Level.WARNING, "sculptory.palette.truncated_scatter", "two", "1", "2", "64"),
                notice("sculptory.palette.truncated_scatter"));
    }

    @Test
    void scatterBlocksNeedTheirPermission() {
        rig.session.setPermissions(new Permissions(Perm.mask(EnumSet.of(Perm.USE, Perm.CLIPBOARD, Perm.SCATTER)),
                Limits.DEFAULTS));
        List<ScatterMix.Variant> before = scatter().mix().variants();
        assertFalse(actions.apply(ToolId.SCATTER, loaded("a.palette.json", "minecraft:poppy")));
        assertEquals(before, scatter().mix().variants());
        assertTrue(noticeKeys().contains("sculptory.notice.scatter_block_permission"), noticeKeys().toString());
    }

    @Test
    void theToolsWithPalettesAreTheseThree() {
        assertTrue(actions.supports(ToolId.PAINT));
        assertTrue(actions.supports(ToolId.PALETTE));
        assertTrue(actions.supports(ToolId.SCATTER));
        assertFalse(actions.supports(ToolId.RAISE));
        assertFalse(actions.supports(ToolId.PLACE));
    }
}
