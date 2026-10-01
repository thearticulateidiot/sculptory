package dev.sculptory.fabric.client.editor.tools.brush;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.SculptMode;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.brush.SurfacePlane;
import dev.sculptory.core.brush.SymmetricStep;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.brush.WeatherSpec;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.MixLayout;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.mask.BlockSet;
import dev.sculptory.core.mask.EditMask;
import dev.sculptory.core.mask.MaskEntry;
import dev.sculptory.core.mask.MaskRule;
import dev.sculptory.core.palette.PalettePattern;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.EditorBackend;
import dev.sculptory.fabric.client.editor.EditorContext;
import dev.sculptory.fabric.client.editor.brush.BrushPredictor;
import dev.sculptory.fabric.client.editor.brush.BrushTerrain;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.input.KeyChord;
import dev.sculptory.fabric.client.editor.mock.MockEditorSession;
import dev.sculptory.fabric.client.editor.render.BrushCursor;
import dev.sculptory.fabric.client.editor.render.OverlayColors;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.DeactivateReason;
import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.FrameInfo;
import dev.sculptory.fabric.client.editor.tool.KeyHint;
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.ScrollEvent;
import dev.sculptory.fabric.client.editor.tool.ToolDescriptor;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.ToolRegistry;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.tool.WorldDraw;
import dev.sculptory.fabric.client.editor.tools.EditorToolSet;
import dev.sculptory.fabric.client.editor.tools.PlaceholderTool;
import dev.sculptory.fabric.client.session.Capabilities;
import dev.sculptory.fabric.client.session.ClipboardCache;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.FabricEditorSession;
import dev.sculptory.fabric.client.session.FabricStrokeHandle;
import dev.sculptory.fabric.client.session.HistoryMirror;
import dev.sculptory.fabric.client.session.JobTracker;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Permissions;
import dev.sculptory.fabric.client.session.SessionState;
import dev.sculptory.fabric.client.session.StrokeHandle;
import dev.sculptory.fabric.client.session.StrokeParams;
import dev.sculptory.fabric.client.session.Subscription;
import dev.sculptory.fabric.client.session.ToolAction;
import dev.sculptory.fabric.client.session.ToolResult;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.Codec;
import dev.sculptory.protocol.v2.Features;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.ProtocolException;
import dev.sculptory.protocol.v2.ProtocolV2;
import dev.sculptory.protocol.v2.S2C;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class BrushToolTest {
    private static final long MS = 1_000_000L;
    private static final long SEED = 42L;

    private final FakeStateSpace states = new FakeStateSpace();
    private final int dirt = states.state("minecraft:dirt");
    private final int stone = states.state("minecraft:stone");
    private final int grass = states.state("minecraft:grass_block");

    /** A hit on top of terrain column (x, z). */
    private static WorldCursor top(int x, int z) {
        int y = BrushTerrain.height(x, z);
        return new WorldCursor(new BlockPos(x, y, z), WorldCursor.Face.UP, x + 0.5, y + 1, z + 0.5, false);
    }

    private static final WorldCursor SKY = WorldCursor.miss(0, 200, 0);

    private static BlockDescriptor block(String spec) {
        return BlockDescriptor.parse(spec);
    }

    // ---------------------------------------------------------------- settings to spec

    @Test
    void theDefaultSettingsOfEachBrushMapToItsSpec() {
        for (BrushTool kind : BrushTool.TERRAIN) {
            Rig rig = new Rig(kind);
            if (kind == BrushTool.PAINT) {
                rig.set(rig.tool.settings().material, block("minecraft:dirt"));
            }
            rig.press(top(0, 0), 0);
            rig.frame(top(0, 0), 16);
            assertEquals(1, rig.session.begins.size(), kind.name());
            RecordingSession.Begin begin = rig.session.begins.get(0);
            BrushSpec expected = switch (kind) {
                // Raise, Lower, Smooth and Flatten work in the Surface mode by default; Flatten's plane is the ground's top face.
                case RAISE, LOWER -> new BrushSpec(kind, 5, 0.5f, Falloff.SMOOTH, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, SEED)
                        .withSurface(null);
                case SMOOTH -> new BrushSpec(kind, 5, 0.6f, Falloff.SMOOTH, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, SEED)
                        .withSurface(null);
                case FLATTEN -> new BrushSpec(kind, 5, 0.6f, Falloff.SMOOTH, Shape.CIRCLE, null, SurfaceMask.ANY, 0,
                        BrushTerrain.height(0, 0), SEED).withSurface(new SurfacePlane(Facing.UP, BrushTerrain.height(0, 0)));
                case PAINT -> new BrushSpec(kind, 4, 1f, Falloff.CONSTANT, Shape.CIRCLE, new Pattern.Single(dirt),
                        SurfaceMask.ANY, 1, 0, SEED);
                // The default mix's coarse dirt and moss are unknown to the test state space: skipped, with a warning.
                case PALETTE -> new BrushSpec(kind, 4, 1f, Falloff.CONSTANT, Shape.CIRCLE,
                        new Pattern.Weighted(new int[] {grass}, new int[] {4}, SEED), SurfaceMask.ANY, 1, 0, SEED);
                case SHAPE, WEATHER -> throw new AssertionError("not a terrain brush");
            };
            assertEquals(expected, begin.spec(), kind.name());
            assertEquals(TerrainBrushTool.toolId(kind), begin.tool());
            assertEquals(new StrokeParams(true, StrokeParams.DEFAULT.maxPredictedCells()), begin.params());
            assertEquals(1, begin.handle().dabs.size(), "the first dab lands on the hit");
            assertEquals(Dab.of(0, 0.5, BrushTerrain.height(0, 0) + 1, 0.5, Dab.FULL_PRESSURE), begin.handle().dabs.get(0));
        }
    }

    @Test
    void editedSettingsMapToTheSpecAndItsMask() {
        Rig rig = new Rig(BrushTool.RAISE);
        BrushSettings s = rig.tool.settings();
        rig.set(s.radius, 12);
        rig.set(s.strength, 0.3);
        rig.set(s.falloff, Falloff.LINEAR);
        rig.set(s.shape, Shape.SQUARE);
        rig.set(s.maskBlocks, List.of(block("minecraft:grass_block"), block("minecraft:grass_block[snowy=true]")));
        rig.set(s.maskY, new SettingDef.IntSpan(60, 80));
        rig.set(s.maskSlope, new SettingDef.IntSpan(0, 3));
        rig.press(top(0, 0), 0);
        rig.frame(top(0, 0), 16);
        // The stroke carries the mask in the rule form, the legacy keys migrated.
        SurfaceMask mask = new SurfaceMask.Rules(new EditMask(List.of(
                MaskEntry.of(new MaskRule.Is(BlockSet.parse("minecraft:grass_block"))),
                MaskEntry.of(new MaskRule.Height(60, 80)),
                MaskEntry.of(new MaskRule.Slope(0, 3))), false));
        assertEquals(new BrushSpec(BrushTool.RAISE, 12, 0.3f, Falloff.LINEAR, Shape.SQUARE, null, mask, 0, 0, SEED)
                .withSurface(null), rig.session.begins.get(0).spec());

        // A range left at its full span means no limit; one part alone is not wrapped in And.
        rig.set(s.maskBlocks, List.of());
        rig.set(s.maskY, new SettingDef.IntSpan(BrushSettings.MIN_Y, BrushSettings.MAX_Y));
        assertEquals(new SurfaceMask.Slope(0, 3), s.legacyMask(rig.ctx.settings(ToolId.RAISE), states));
        assertEquals(new SurfaceMask.Rules(new EditMask(List.of(MaskEntry.of(new MaskRule.Slope(0, 3))), false)),
                s.mask(rig.ctx.settings(ToolId.RAISE), states));
        rig.set(s.maskSlope, new SettingDef.IntSpan(0, BrushSettings.MAX_SLOPE));
        assertEquals(SurfaceMask.ANY, s.mask(rig.ctx.settings(ToolId.RAISE), states));
    }

    @Test
    void exactStatesAndInvertBuildTheExpectedMaskTrees() {
        Rig rig = new Rig(BrushTool.RAISE);
        BrushSettings s = rig.tool.settings();
        int snowy = states.state("minecraft:grass_block[snowy=true]");
        int log = states.state("minecraft:oak_log[axis=x]");
        rig.set(s.maskBlocks, List.of(block("minecraft:grass_block[snowy=true]"), block("minecraft:oak_log[axis=x]"),
                block("minecraft:grass_block[snowy=true]")));
        SurfaceMask byType = new SurfaceMask.SurfaceBlocks(new CellMask.Blocks(List.of(
                new NamespacedId("minecraft:grass_block"), new NamespacedId("minecraft:oak_log"))));
        assertEquals(byType, s.legacyMask(rig.values(), states), "off: by block type, as before");

        rig.set(s.maskExactStates, true);
        SurfaceMask exact = new SurfaceMask.SurfaceBlocks(new CellMask.States(new int[] {snowy, log}));
        assertEquals(exact, s.legacyMask(rig.values(), states), "on: the listed states, duplicates merged, in list order");

        rig.set(s.maskY, new SettingDef.IntSpan(60, 80));
        rig.set(s.maskInvert, true);
        assertEquals(new SurfaceMask.Not(new SurfaceMask.And(List.of(exact, new SurfaceMask.Elevation(60, 80)))),
                s.legacyMask(rig.values(), states), "invert wraps the whole mask");

        rig.set(s.maskBlocks, List.of());
        rig.set(s.maskY, new SettingDef.IntSpan(BrushSettings.MIN_Y, BrushSettings.MAX_Y));
        assertEquals(SurfaceMask.ANY, s.legacyMask(rig.values(), states), "invert without a mask does nothing");
        assertEquals(SurfaceMask.ANY, s.mask(rig.values(), states), "and so do the rules");
        assertFalse(rig.values().isVisible(s.maskExactStates), "the legacy keys never show");
        rig.set(s.maskSlope, new SettingDef.IntSpan(0, 2));
        assertEquals(new SurfaceMask.Not(new SurfaceMask.Slope(0, 2)), s.legacyMask(rig.values(), states));
        assertEquals(new SurfaceMask.Rules(new EditMask(List.of(MaskEntry.of(new MaskRule.Slope(0, 2))), true)),
                s.mask(rig.values(), states), "invert as invert-all");

        // The pressed stroke carries the mask; an exact state the server does not know is dropped, with a warning.
        rig.set(s.maskSlope, new SettingDef.IntSpan(0, BrushSettings.MAX_SLOPE));
        rig.set(s.maskInvert, false);
        rig.set(s.maskBlocks, List.of(block("minecraft:oak_log[axis=z]"), block("minecraft:coarse_dirt")));
        rig.press(top(0, 0), 0);
        rig.frame(top(0, 0), 16);
        assertEquals(new SurfaceMask.Rules(new EditMask(List.of(MaskEntry.of(new MaskRule.Is(BlockSet.parse(
                "minecraft:oak_log[axis=z];minecraft:coarse_dirt")))), false)), rig.session.begins.get(0).spec().mask(),
                "an unknown block is kept in the rule: it matches nothing, as before");
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.notice.unknown_block", "minecraft:coarse_dirt")),
                rig.notices);
        assertFalse(rig.values().isVisible(s.maskExactStates), "hidden: the rules show instead");
    }

    @Test
    void onlyInsideSelectionRefusesThePressWithoutASelection() {
        Rig rig = new Rig(BrushTool.LOWER);
        rig.set(rig.tool.settings().insideSelection, true);
        assertEquals(KeyHint.text(TerrainBrushTool.NEEDS_SELECTION), rig.tool.hints(rig.view).get(0), "the hint says why");
        rig.press(top(0, 0), 0);
        rig.frame(top(0, 0), 16);
        rig.frame(top(2, 0), 200);
        assertFalse(rig.tool.controller().pressed());
        assertTrue(rig.session.begins.isEmpty(), "nothing is sent");
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, TerrainBrushTool.NEEDS_SELECTION)), rig.notices);
        rig.release(top(2, 0));

        Box selection = Box.of(new BlockPos(-3, 50, -3), new BlockPos(3, 70, 3));
        rig.ctx.setSelection(selection);
        assertFalse(rig.tool.hints(rig.view).contains(KeyHint.text(TerrainBrushTool.NEEDS_SELECTION)));
        rig.press(top(0, 0), 0);
        rig.frame(top(0, 0), 16);
        assertEquals(selection, rig.session.begins.get(0).spec().clip());
        RecordingDraw draw = new RecordingDraw();
        rig.tool.renderWorld(rig.view, draw);
        assertEquals(List.of(selection), draw.boxes, "the clip box is outlined while painting");
        rig.release(top(0, 0));

        // Switched off, the brush writes anywhere again.
        rig.set(rig.tool.settings().insideSelection, false);
        rig.press(top(0, 0), 0);
        rig.frame(top(0, 0), 16);
        assertNull(rig.session.begins.get(1).spec().clip());
    }

    @Test
    void theClipBoxIsCapturedWhenThePressBegins() {
        Rig rig = new Rig(BrushTool.RAISE);
        rig.set(rig.tool.settings().insideSelection, true);
        Box first = Box.of(new BlockPos(-4, 50, -4), new BlockPos(4, 80, 4));
        Box second = Box.of(new BlockPos(10, 50, 10), new BlockPos(12, 80, 12));
        rig.ctx.setSelection(first);
        rig.press(top(0, 0), 0);
        rig.frame(top(0, 0), 16);
        rig.ctx.setSelection(second);
        rig.scroll(1, Modifiers.CONTROL); // a settings change mid-press restarts the server stroke
        rig.frame(top(1, 0), 300);
        assertEquals(2, rig.session.begins.size());
        assertEquals(first, rig.session.begins.get(0).spec().clip());
        assertEquals(first, rig.session.begins.get(1).spec().clip(), "a restart keeps the press's box");
        rig.release(top(1, 0));

        rig.ctx.setSelection(null);
        rig.press(top(0, 0), 0); // refused: nothing selected now
        rig.frame(top(0, 0), 16);
        assertEquals(2, rig.session.begins.size());
        rig.release(top(0, 0));
        rig.ctx.setSelection(second);
        rig.press(top(0, 0), 0);
        rig.frame(top(0, 0), 16);
        assertEquals(second, rig.session.begins.get(2).spec().clip(), "the next press takes the new box");
        rig.release(top(0, 0));

        // Turning the toggle on mid-press uses the selection the press began with, or ends the press without one.
        rig.set(rig.tool.settings().insideSelection, false);
        rig.ctx.setSelection(null);
        rig.press(top(0, 0), 0);
        rig.frame(top(0, 0), 16);
        rig.ctx.setSelection(first);
        rig.set(rig.tool.settings().insideSelection, true);
        rig.frame(top(1, 0), 300);
        assertEquals(4, rig.session.begins.size());
        assertNull(rig.session.begins.get(3).spec().clip());
        assertTrue(rig.session.begins.get(3).handle().ended, "the unclipped stroke ended");
        assertFalse(rig.tool.controller().emitting(), "no stroke without the press's selection");
        assertEquals(Notice.of(Notice.Level.WARNING, TerrainBrushTool.NEEDS_SELECTION), rig.notices.get(rig.notices.size() - 1));
    }

    @Test
    void theClipBoxIsClampedToTheBuildHeight() {
        assertEquals(Box.of(new BlockPos(-5, -64, 2), new BlockPos(5, 319, 3)),
                TerrainBrushTool.clampToWorld(Box.of(new BlockPos(-5, -500, 2), new BlockPos(5, 900, 3)), -64, 320));
        assertEquals(Box.of(new BlockPos(-30_000_000, 0, 0), new BlockPos(30_000_000, 0, 0)),
                TerrainBrushTool.clampToWorld(Box.of(new BlockPos(-40_000_000, 0, 0), new BlockPos(40_000_000, 0, 0)), -64, 320));
        assertNull(TerrainBrushTool.clampToWorld(Box.of(new BlockPos(0, 320, 0), new BlockPos(1, 400, 1)), -64, 320));

        Rig rig = new Rig(BrushTool.SMOOTH);
        rig.set(rig.tool.settings().insideSelection, true);
        rig.ctx.setSelection(Box.of(new BlockPos(-2, -100, -2), new BlockPos(2, 1000, 2)));
        rig.press(top(0, 0), 0);
        rig.frame(top(0, 0), 16);
        assertEquals(Box.of(new BlockPos(-2, -64, -2), new BlockPos(2, 319, 2)), rig.session.begins.get(0).spec().clip());
        rig.release(top(0, 0));
        rig.ctx.setSelection(Box.of(new BlockPos(0, 400, 0), new BlockPos(1, 500, 1)));
        rig.press(top(0, 0), 0);
        rig.frame(top(0, 0), 16);
        assertEquals(1, rig.session.begins.size());
        assertEquals(Notice.of(Notice.Level.WARNING, TerrainBrushTool.SELECTION_OUTSIDE_WORLD), rig.notices.get(rig.notices.size() - 1));
    }

    @Test
    void theServersRadiusLimitCapsTheBrush() {
        Rig rig = new Rig(BrushTool.SMOOTH);
        rig.mock.setPermissions(new Permissions(Perm.mask(EnumSet.allOf(Perm.class)),
                new Limits(2_097_152L, 2_097_152L, 8, 20, 32L << 20, 2)));
        rig.set(rig.tool.settings().radius, 12);
        rig.press(top(0, 0), 0);
        rig.frame(top(0, 0), 16);
        assertEquals(8, rig.session.begins.get(0).spec().radius());
        rig.scroll(1, Modifiers.CONTROL);
        assertEquals(8, rig.setting(rig.tool.settings().radius), "Ctrl+Scroll stops at the server's limit");
    }

    // ---------------------------------------------------------------- Alt, Flatten, materials

    @Test
    void altInvertsRaiseAndLowerOnly() {
        assertEquals(BrushTool.LOWER, firstSpecWithAlt(BrushTool.RAISE).tool());
        assertEquals(BrushTool.RAISE, firstSpecWithAlt(BrushTool.LOWER).tool());
        assertEquals(BrushTool.SMOOTH, firstSpecWithAlt(BrushTool.SMOOTH).tool());
        assertEquals(BrushTool.FLATTEN, firstSpecWithAlt(BrushTool.FLATTEN).tool());
    }

    private BrushSpec firstSpecWithAlt(BrushTool kind) {
        Rig rig = new Rig(kind);
        rig.ctx.setModifiers(Modifiers.ALT);
        rig.press(top(0, 0), Modifiers.ALT);
        rig.frame(top(0, 0), 16);
        return rig.session.begins.get(0).spec();
    }

    @Test
    void pressingAltMidStrokeRestartsTheStrokeInverted() {
        Rig rig = new Rig(BrushTool.RAISE);
        rig.terrainMode();
        rig.press(top(0, 0), 0);
        rig.frame(top(0, 0), 16);
        assertEquals(TerrainBrushTool.color(BrushTool.RAISE), rig.services.current.color());
        rig.ctx.setModifiers(Modifiers.ALT);
        rig.frame(top(0, 0), 100);
        assertEquals(1, rig.session.begins.size(), "restarts wait 250 ms");
        assertEquals(TerrainBrushTool.color(BrushTool.LOWER), rig.services.current.color(), "the cursor shows Lower at once");
        rig.frame(top(1, 0), 200);
        assertEquals(2, rig.session.begins.size());
        assertTrue(rig.session.begins.get(0).handle().ended);
        assertEquals(BrushTool.LOWER, rig.session.begins.get(1).spec().tool());
        assertEquals(SEED, rig.session.begins.get(1).spec().seed(), "one press keeps its seed");
    }

    @Test
    void flattenLocksTheHeightTheStrokeStartedOn() {
        Rig rig = new Rig(BrushTool.FLATTEN);
        int start = BrushTerrain.height(0, 0);
        int ridge = BrushTerrain.height(10, 0);
        assertTrue(ridge > start);
        rig.press(top(0, 0), 0);
        rig.frame(top(0, 0), 16);
        rig.drag(top(10, 0));
        rig.frame(top(10, 0), 50);
        assertEquals(1, rig.session.begins.size());
        assertEquals(start, rig.session.begins.get(0).spec().flattenY());

        rig.scroll(1, Modifiers.CONTROL); // radius 6, mid-stroke
        rig.frame(top(10, 0), 300);
        assertEquals(2, rig.session.begins.size());
        BrushSpec restarted = rig.session.begins.get(1).spec();
        assertEquals(6, restarted.radius());
        assertEquals(start, restarted.flattenY(), "a restart keeps the height");
        rig.release(top(10, 0));

        rig.press(top(10, 0), 0);
        rig.frame(top(10, 0), 16);
        assertEquals(ridge, rig.session.begins.get(2).spec().flattenY(), "a new press takes the new height");
    }

    @Test
    void paintNeedsAMaterialTheServerKnows() {
        Rig rig = new Rig(BrushTool.PAINT);
        rig.press(top(0, 0), 0);
        rig.frame(top(0, 0), 16);
        rig.frame(top(3, 0), 200);
        assertTrue(rig.session.begins.isEmpty(), "coarse dirt is unknown to this state space");
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.notice.unknown_block", "minecraft:coarse_dirt")),
                rig.notices, "said once, not every frame");
        rig.release(top(3, 0));

        rig.set(rig.tool.settings().material, block("minecraft:stone"));
        rig.press(top(0, 0), 0);
        rig.frame(top(0, 0), 16);
        assertEquals(new Pattern.Single(stone), rig.session.begins.get(0).spec().material());
    }

    @Test
    void paletteNeedsAtLeastOneKnownBlock() {
        Rig rig = new Rig(BrushTool.PALETTE);
        rig.set(rig.tool.settings().palette, List.of());
        rig.press(top(0, 0), 0);
        rig.frame(top(0, 0), 16);
        assertTrue(rig.session.begins.isEmpty());
        assertEquals(List.of("sculptory.notice.palette_empty"), rig.noticeKeys());
        rig.release(top(0, 0));

        rig.set(rig.tool.settings().palette, List.of(new SettingDef.WeightedBlock(block("minecraft:dirt"), 2),
                new SettingDef.WeightedBlock(block("minecraft:sand"), 1),
                new SettingDef.WeightedBlock(block("minecraft:dirt"), 3)));
        rig.press(top(0, 0), 0);
        rig.frame(top(0, 0), 16);
        int sand = states.state("minecraft:sand");
        assertEquals(new Pattern.Weighted(new int[] {dirt, sand}, new int[] {5, 1}, SEED),
                rig.session.begins.get(0).spec().material(), "duplicates merge");
    }

    /** 64 distinct oak stair states of the fake space, as blocks for a mix. */
    private List<SettingDef.WeightedBlock> sixtyFourStairs() {
        int first = states.state("minecraft:oak_stairs");
        List<SettingDef.WeightedBlock> mix = new ArrayList<>();
        for (int i = 0; i < 64; i++) mix.add(new SettingDef.WeightedBlock(states.describe(first + i), 1 + i));
        return mix;
    }

    @Test
    void palettePaintTakesAMixOf64Blocks() {
        assertEquals(64, BrushSettings.MAX_PALETTE_ENTRIES);
        assertEquals(Pattern.Weighted.MAX_ENTRIES, BrushSettings.MAX_PALETTE_ENTRIES, "what the wire carries");
        Rig rig = new Rig(BrushTool.PALETTE);
        List<SettingDef.WeightedBlock> mix = sixtyFourStairs();
        rig.set(rig.tool.settings().palette, mix);
        assertTrue(rig.values().isValid(Limits.DEFAULTS));
        rig.press(top(0, 0), 0);
        rig.frame(top(0, 0), 16);
        Pattern.Weighted sent = assertInstanceOf(Pattern.Weighted.class, rig.session.begins.get(0).spec().material());
        assertEquals(64, sent.size());
        assertEquals(states.resolve(mix.get(63).block()), sent.state(63));
        assertEquals(64, sent.weight(63));
        assertEquals(List.of(), rig.noticeKeys());
        rig.release(top(0, 0));

        // Full: middle-click says so instead of adding a 65th block.
        rig.frame(top(3, 3), 16);
        rig.tool.onAction(rig.view, EditorAction.EYEDROPPER);
        assertEquals(64, rig.setting(rig.tool.settings().palette).size());
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.notice.palette_full", "64")), rig.notices);
    }

    /**
     * A StrokeBegin with 64 blocks and 16 exact mask states fits one client frame when every state text is 256 bytes
     * long (a palette's cap, longer than any vanilla state); with far longer (modded) state texts it doesn't, and the
     * press is refused with a toast before anything is sent, instead of failing in the session (never a disconnect).
     */
    /**
     * The largest brush spec: Palette Paint with 64 blocks (oak stair states) and 16 other exact states in an inverted
     * mask, the widest clip box, a Rotate 4 centre at the range's end, extreme seeds.
     */
    private BrushSpec largestSpec() {
        int first = states.state("minecraft:oak_stairs");
        int[] handles = new int[64];
        int[] weights = new int[64];
        for (int i = 0; i < 64; i++) {
            handles[i] = first + i;
            weights[i] = 1000;
        }
        int[] masked = new int[16];
        for (int i = 0; i < 16; i++) masked[i] = first + 64 + i;
        return new BrushSpec(BrushTool.PALETTE, 32, 1f, Falloff.CONSTANT, Shape.SQUARE,
                new Pattern.Weighted(handles, weights, Long.MIN_VALUE),
                new SurfaceMask.Not(new SurfaceMask.SurfaceBlocks(new CellMask.States(masked))), 32, 0, Long.MAX_VALUE,
                new Box(new BlockPos(-30_000_000, -64, -30_000_000), new BlockPos(30_000_000, 319, 30_000_000)),
                new Symmetry(Symmetry.Mode.ROTATE_4, -Symmetry.MAX_CENTRE2 + 1, Symmetry.MAX_CENTRE2 - 1));
    }

    /**
     * At the frame's limit the check counts the longest stroke id (5 bytes): a spec that fills the frame exactly with
     * id 2³¹ - 1 fits, one byte more doesn't, although it would with id 0 (4 bytes shorter). A spec the encoder can't
     * handle (a state outside the space) is left to the session, never thrown at the tool.
     */
    @Test
    void theFrameCheckCountsTheLongestStrokeIdAndNeverThrows() throws ProtocolException {
        BrushSpec spec = largestSpec();
        int first = states.state("minecraft:oak_stairs");
        int base = Codec.encodeC2S(new C2S.StrokeBegin(Integer.MAX_VALUE, spec), new LongStateNames(states, 380)).length;
        int room = ProtocolV2.MAX_C2S_FRAME - base;
        assertTrue(room > 4 && room < 4000, "room left: " + room);
        StateSpace full = new LongStateNames(states, 380, first, room);
        assertEquals(ProtocolV2.MAX_C2S_FRAME, Codec.encodeC2S(new C2S.StrokeBegin(Integer.MAX_VALUE, spec), full).length);
        assertTrue(TerrainBrushTool.fitsOneFrame(spec, full), "exactly full");
        StateSpace over = new LongStateNames(states, 380, first, room + 1);
        assertFalse(TerrainBrushTool.fitsOneFrame(spec, over), "one byte over with the longest id");
        assertEquals(ProtocolV2.MAX_C2S_FRAME - 3, Codec.encodeC2S(new C2S.StrokeBegin(0, spec), over).length,
                "the same spec fits with stroke id 0");
        BrushSpec outside = new BrushSpec(BrushTool.PAINT, 4, 1f, Falloff.CONSTANT, Shape.CIRCLE,
                new Pattern.Single(states.size() + 5), SurfaceMask.ANY, 1, 0, 1L);
        assertTrue(TerrainBrushTool.fitsOneFrame(outside, states), "not a size problem: the session reports it");
    }

    @Test
    void aBrushWhoseBlockNamesDoNotFitOneMessageIsRefusedWithAToast() throws ProtocolException {
        int first = states.state("minecraft:oak_stairs");
        BrushSpec spec = largestSpec();
        StateSpace long256 = new LongStateNames(states, 256);
        assertEquals(256, long256.format(first).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertTrue(TerrainBrushTool.fitsOneFrame(spec, long256));
        int size = Codec.encodeC2S(new C2S.StrokeBegin(Integer.MAX_VALUE, spec), long256).length;
        assertTrue(size > 80 * 256 && size <= ProtocolV2.MAX_C2S_FRAME, "encoded " + size + " bytes");
        assertFalse(TerrainBrushTool.fitsOneFrame(spec, new LongStateNames(states, 500)), "80 × 500 bytes");
        assertTrue(TerrainBrushTool.fitsOneFrame(spec, states), "the fake space's own names");

        Rig rig = new Rig(BrushTool.PALETTE, new LongStateNames(states, 600));
        rig.set(rig.tool.settings().palette, sixtyFourStairs());
        rig.press(top(0, 0), 0);
        rig.frame(top(0, 0), 16);
        rig.drag(top(2, 0));
        rig.frame(top(2, 0), 16);
        assertTrue(rig.session.begins.isEmpty(), "nothing was sent");
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, TerrainBrushTool.SPEC_TOO_LARGE,
                Integer.toString(ProtocolV2.MAX_C2S_FRAME))), rig.notices);
        rig.release(top(2, 0));
        rig.set(rig.tool.settings().palette, sixtyFourStairs().subList(0, 40));
        rig.press(top(0, 0), 0);
        rig.frame(top(0, 0), 16);
        assertEquals(1, rig.session.begins.size(), "40 blocks of 600 bytes fit");
    }

    @Test
    void middleClickPicksPaintsMaterialAndGrowsThePalette() {
        Rig paint = new Rig(BrushTool.PAINT);
        paint.frame(top(3, 3), 16);
        assertTrue(paint.tool.onAction(paint.view, EditorAction.EYEDROPPER));
        assertEquals(states.describe(grass), paint.setting(paint.tool.settings().material));
        assertEquals(List.of(Notice.of(Notice.Level.INFO, "sculptory.notice.paint_material",
                "minecraft:grass_block[snowy=false]")), paint.notices);
        paint.frame(SKY, 16);
        assertTrue(paint.tool.onAction(paint.view, EditorAction.EYEDROPPER), "the sky picks nothing");
        assertEquals(states.describe(grass), paint.setting(paint.tool.settings().material));

        Rig palette = new Rig(BrushTool.PALETTE);
        int y = BrushTerrain.height(3, 3);
        palette.world.set(3, y, 3, stone);
        palette.frame(top(3, 3), 16);
        palette.tool.onAction(palette.view, EditorAction.EYEDROPPER);
        palette.tool.onAction(palette.view, EditorAction.EYEDROPPER);
        List<SettingDef.WeightedBlock> mix = palette.setting(palette.tool.settings().palette);
        assertEquals(new SettingDef.WeightedBlock(states.describe(stone), 1), mix.get(mix.size() - 1));
        assertEquals(List.of("sculptory.notice.palette_added", "sculptory.notice.palette_has"), palette.noticeKeys());

        Rig raise = new Rig(BrushTool.RAISE);
        raise.frame(top(3, 3), 16);
        assertFalse(raise.tool.onAction(raise.view, EditorAction.EYEDROPPER), "the editor's eyedropper handles the rest");
    }

    @Test
    void ctrlScrollSetsTheRadiusAndAltScrollTheStrength() {
        Rig rig = new Rig(BrushTool.RAISE);
        BrushSettings s = rig.tool.settings();
        assertTrue(rig.scroll(1, Modifiers.CONTROL));
        assertEquals(6, rig.setting(s.radius));
        rig.scroll(1, Modifiers.CONTROL | Modifiers.SHIFT);
        assertEquals(10, rig.setting(s.radius), "Shift makes steps of 4");
        for (int i = 0; i < 20; i++) {
            rig.scroll(-1, Modifiers.CONTROL);
        }
        assertEquals(1, rig.setting(s.radius));
        for (int i = 0; i < 20; i++) {
            rig.scroll(1, Modifiers.CONTROL | Modifiers.SHIFT);
        }
        assertEquals(32, rig.setting(s.radius));

        assertTrue(rig.scroll(1, Modifiers.ALT));
        assertEquals(0.55, rig.setting(s.strength));
        for (int i = 0; i < 30; i++) {
            rig.scroll(-1, Modifiers.ALT);
        }
        assertEquals(0.0, rig.setting(s.strength));
        for (int i = 0; i < 30; i++) {
            rig.scroll(1, Modifiers.ALT);
        }
        assertEquals(1.0, rig.setting(s.strength));
        assertFalse(rig.scroll(1, 0), "plain scroll is the editor's fly speed");
    }

    // ---------------------------------------------------------------- stroke lifecycle

    @Test
    void releaseEndsTheStrokeWithTheDabsAlongTheDrag() {
        Rig rig = new Rig(BrushTool.RAISE);
        rig.press(top(0, 0), 0);
        assertTrue(rig.ctx.pointerCapture());
        rig.frame(top(0, 0), 16);
        for (int x = 1; x <= 6; x++) {
            rig.drag(top(x, 0));
            rig.frame(top(x, 0), 60);
        }
        rig.release(top(6, 0));
        RecordingStroke stroke = rig.session.begins.get(0).handle();
        assertTrue(stroke.ended);
        assertFalse(rig.ctx.pointerCapture());
        assertTrue(stroke.dabs.size() >= 5, "dabs every 1.25 blocks along a 6-block drag: " + stroke.dabs.size());
        for (int i = 0; i < stroke.dabs.size(); i++) {
            assertEquals(i, stroke.dabs.get(i).index());
        }
        assertEquals("Raise stroke", rig.mock.history().undoLabel(), "the mock session keeps one history entry");
    }

    @Test
    void escapeCancelsFocusLossAndToolSwitchEnd() {
        Rig esc = new Rig(BrushTool.LOWER);
        esc.press(top(0, 0), 0);
        esc.frame(top(0, 0), 16);
        assertTrue(esc.tool.onAction(esc.view, EditorAction.CANCEL));
        assertTrue(esc.session.begins.get(0).handle().cancelled);
        assertFalse(esc.tool.onAction(esc.view, EditorAction.CANCEL), "nothing left to cancel: Esc goes on up the ladder");
        assertFalse(esc.ctx.pointerCapture());

        Rig focus = new Rig(BrushTool.SMOOTH);
        focus.press(top(0, 0), 0);
        focus.frame(top(0, 0), 16);
        focus.services.focused = false;
        focus.frame(top(2, 0), 16);
        assertTrue(focus.session.begins.get(0).handle().ended);
        assertEquals(1, focus.session.begins.get(0).handle().dabs.size());

        Rig switched = new Rig(BrushTool.FLATTEN);
        switched.press(top(0, 0), 0);
        switched.frame(top(0, 0), 16);
        switched.ctx.tools().deactivate(switched.view, DeactivateReason.SWITCHED_TOOL);
        assertTrue(switched.session.begins.get(0).handle().ended);
        assertNull(switched.services.current, "the cursor goes away with the tool");

        Rig exited = new Rig(BrushTool.PAINT);
        exited.set(exited.tool.settings().material, block("minecraft:dirt"));
        exited.press(top(0, 0), 0);
        exited.frame(top(0, 0), 16);
        exited.ctx.tools().deactivate(exited.view, DeactivateReason.EDITOR_CLOSED);
        assertTrue(exited.session.begins.get(0).handle().ended);
    }

    @Test
    void aPressInTheSkyBeginsNothingUntilTheCursorMeetsTheTerrain() {
        Rig rig = new Rig(BrushTool.RAISE);
        rig.press(SKY, 0);
        rig.frame(SKY, 16);
        rig.frame(SKY, 200);
        assertTrue(rig.session.begins.isEmpty());
        rig.frame(top(2, 2), 16);
        assertEquals(1, rig.session.begins.size());
        rig.release(top(2, 2));
        assertTrue(rig.session.begins.get(0).handle().ended);
    }

    // ---------------------------------------------------------------- cursor and hints

    @Test
    void theCursorHugsTheTerrainInTheBrushColour() {
        Rig rig = new Rig(BrushTool.RAISE);
        rig.terrainMode();
        rig.frame(top(0, 0), 16);
        BrushCursor cursor = rig.services.current;
        assertNotNull(cursor);
        assertEquals(TerrainBrushTool.color(BrushTool.RAISE), cursor.color());
        assertFalse(cursor.serverOnly());
        assertEquals(5, cursor.samples().radius());
        assertEquals(BrushTerrain.height(0, 0), cursor.samples().heightAt(0, 0));
        assertEquals(states.state("minecraft:short_grass"), rig.world.get(2, BrushTerrain.height(2, 2) + 1, 2));
        assertEquals(BrushTerrain.height(2, 2), cursor.samples().heightAt(2, 2), "grass tufts are not the surface");
        assertEquals(TerrainBrushTool.falloffStart(Falloff.SMOOTH), cursor.falloffStart());

        rig.ctx.setModifiers(Modifiers.ALT);
        rig.frame(top(0, 0), 16);
        assertEquals(TerrainBrushTool.color(BrushTool.LOWER), rig.services.current.color(), "Alt previews the inverse");

        rig.frame(SKY, 16);
        assertNull(rig.services.current);
    }

    /** The Mode setting changed in the Tool Settings window: the next frame shows the other mode's cursor at the same aim. */
    @Test
    void switchingTheModeUpdatesTheCursorWithoutReAiming() {
        Rig rig = new Rig(BrushTool.RAISE);
        rig.frame(top(0, 0), 16);
        assertNull(rig.services.current, "the Surface mode has no terrain-hugging cursor");
        RecordingDraw surface = new RecordingDraw();
        rig.tool.renderWorld(rig.view, surface);
        assertTrue(surface.segments.size() >= 48, "the Surface ring: " + surface.segments.size());

        rig.terrainMode();
        rig.frame(top(0, 0), 16);
        assertNotNull(rig.services.current, "the Terrain mode's cursor shows at the same aim");
        assertEquals(TerrainBrushTool.color(BrushTool.RAISE), rig.services.current.color());
        RecordingDraw terrain = new RecordingDraw();
        rig.tool.renderWorld(rig.view, terrain);
        assertEquals(0, terrain.segments.size(), "no Surface ring in the Terrain mode");

        rig.set(rig.tool.settings().mode, SculptMode.SURFACE);
        rig.frame(top(0, 0), 16);
        assertNull(rig.services.current, "back to the Surface mode: the terrain cursor goes");
        RecordingDraw again = new RecordingDraw();
        rig.tool.renderWorld(rig.view, again);
        assertTrue(again.segments.size() >= 48, "the Surface ring again: " + again.segments.size());
    }

    @Test
    void bigFlattensAreServerOnlyWithADashedCursor() {
        Rig rig = new Rig(BrushTool.FLATTEN);
        rig.terrainMode();
        rig.set(rig.tool.settings().radius, 25);
        rig.frame(top(0, 0), 16);
        assertTrue(rig.services.current.serverOnly());
        rig.press(top(0, 0), 0);
        rig.frame(top(0, 0), 16);
        assertEquals(new StrokeParams(false, StrokeParams.DEFAULT.maxPredictedCells()), rig.session.begins.get(0).params());
    }

    @Test
    void squareBrushesAndFlattensTargetAreDrawnAsOutlines() {
        Rig rig = new Rig(BrushTool.FLATTEN);
        rig.terrainMode();
        rig.set(rig.tool.settings().shape, Shape.SQUARE);
        rig.frame(top(0, 0), 16);
        assertNull(rig.services.current, "the round overlay cursor is off for squares");
        RecordingDraw draw = new RecordingDraw();
        rig.tool.renderWorld(rig.view, draw);
        assertTrue(draw.lines >= 4 * 11, "the square's four edges, column by column: " + draw.lines);
        assertTrue(draw.planeYs.isEmpty(), "no target plane before a stroke");

        rig.press(top(0, 0), 0);
        rig.frame(top(0, 0), 16);
        RecordingDraw stroking = new RecordingDraw();
        rig.tool.renderWorld(rig.view, stroking);
        double planeY = BrushTerrain.height(0, 0) + 1 + BrushOutlines.LIFT;
        assertTrue(stroking.planeYs.contains(planeY), "Flatten shows its target plane: " + stroking.planeYs);
    }

    @Test
    void hintsNameTheKeys() {
        Rig raise = new Rig(BrushTool.RAISE);
        assertEquals(List.of(
                new KeyHint("LMB drag", "sculptory.hint.brush.raise"),
                new KeyHint("Alt", "sculptory.hint.brush.invert_raise"),
                new KeyHint("Ctrl+Scroll", "sculptory.hint.brush.radius"),
                new KeyHint("Alt+Scroll", "sculptory.hint.brush.strength")), raise.tool.hints(raise.view));
        raise.press(top(0, 0), 0);
        assertEquals(new KeyHint("Esc", "sculptory.hint.cancel_drag"), raise.tool.hints(raise.view).get(0));

        Rig paint = new Rig(BrushTool.PAINT);
        assertEquals(new KeyHint("Middle-click", "sculptory.hint.brush.pick_material"), paint.tool.hints(paint.view).get(1));
    }

    // ---------------------------------------------------------------- symmetry

    private static Symmetry specSymmetry(Rig rig, int begin) {
        return rig.session.begins.get(begin).spec().symmetry();
    }

    @Test
    void theSymmetryCentreComesFromTheSelectionOrTheKey() {
        Rig rig = new Rig(BrushTool.RAISE);
        rig.set(rig.tool.settings().symmetry, Symmetry.Mode.MIRROR_X);
        // Ten blocks wide (x -6..3: centre on the edge at x -1) and eight deep (z -3..4: the edge at z 1).
        rig.ctx.setSelection(Box.of(new BlockPos(-6, 50, -3), new BlockPos(3, 80, 4)));
        rig.press(top(2, 1), 0);
        rig.frame(top(2, 1), 16);
        assertEquals(new Symmetry(Symmetry.Mode.MIRROR_X, -2, 2), specSymmetry(rig, 0), "the selection's centre");
        rig.release(top(2, 1));

        // The key: the block centre under the cursor.
        rig.frame(top(5, -2), 16);
        assertTrue(rig.tool.onAction(rig.view, EditorAction.SET_SYMMETRY_CENTRE));
        assertEquals(Notice.of(Notice.Level.INFO, TerrainBrushTool.SYMMETRY_CENTRE_SET, "5.5", "-1.5"),
                rig.notices.get(rig.notices.size() - 1));
        rig.press(top(2, 1), 0);
        rig.frame(top(2, 1), 16);
        assertEquals(new Symmetry(Symmetry.Mode.MIRROR_X, 11, -3), specSymmetry(rig, 1), "the set centre wins");
        // A new selection during the press changes nothing until the next press.
        rig.tool.symmetryCentre().clear();
        rig.set(rig.tool.settings().radius, 6);
        rig.frame(top(3, 1), 300);
        assertEquals(3, rig.session.begins.size(), "the settings change restarted the stroke");
        assertEquals(new Symmetry(Symmetry.Mode.MIRROR_X, 11, -3), specSymmetry(rig, 2), "fixed for the press");
        rig.release(top(3, 1));

        // Shift: the block corner nearest the hit (5.5, -1.5 rounds to 6, -1); the same point again clears it.
        rig.frame(top(5, -2), 16);
        rig.ctx.setModifiers(Modifiers.SHIFT);
        rig.tool.onAction(rig.view, EditorAction.SET_SYMMETRY_CENTRE);
        rig.ctx.setModifiers(0);
        assertEquals(12, rig.tool.symmetryCentre().x2());
        assertEquals(-2, rig.tool.symmetryCentre().z2());
        rig.ctx.setModifiers(Modifiers.SHIFT);
        rig.tool.onAction(rig.view, EditorAction.SET_SYMMETRY_CENTRE);
        rig.ctx.setModifiers(0);
        assertFalse(rig.tool.symmetryCentre().isSet());
        assertEquals(Notice.of(Notice.Level.INFO, TerrainBrushTool.SYMMETRY_CENTRE_CLEARED), rig.notices.get(rig.notices.size() - 1));

        // Rotate 4 needs block centres or block edges on both axes: the selection's edge x moves onto a block centre.
        rig.set(rig.tool.settings().symmetry, Symmetry.Mode.ROTATE_4);
        rig.press(top(2, 1), 0);
        rig.frame(top(2, 1), 16);
        assertEquals(new Symmetry(Symmetry.Mode.ROTATE_4, -2, 2), specSymmetry(rig, rig.session.begins.size() - 1),
                "both on edges");
        rig.release(top(2, 1));
        rig.ctx.setSelection(Box.of(new BlockPos(-6, 50, -3), new BlockPos(3, 80, 3)));
        rig.press(top(2, 1), 0);
        rig.frame(top(2, 1), 16);
        assertEquals(new Symmetry(Symmetry.Mode.ROTATE_4, -3, 1), specSymmetry(rig, rig.session.begins.size() - 1),
                "x on an edge, z on a block centre: x moves half a block west");
        rig.release(top(2, 1));

        // With the key's centre set while the brush has no symmetry, the toast says how to use it.
        rig.set(rig.tool.settings().symmetry, Symmetry.Mode.OFF);
        rig.tool.onAction(rig.view, EditorAction.SET_SYMMETRY_CENTRE);
        assertEquals(TerrainBrushTool.SYMMETRY_CENTRE_SET_OFF, rig.notices.get(rig.notices.size() - 1).key());
        rig.press(top(2, 1), 0);
        rig.frame(top(2, 1), 16);
        assertEquals(Symmetry.NONE, specSymmetry(rig, rig.session.begins.size() - 1));
        rig.release(top(2, 1));
        rig.frame(SKY, 16);
        rig.tool.onAction(rig.view, EditorAction.SET_SYMMETRY_CENTRE);
        assertEquals(Notice.of(Notice.Level.INFO, TerrainBrushTool.SYMMETRY_CENTRE_AIM), rig.notices.get(rig.notices.size() - 1));
    }

    @Test
    void withNeitherACentreNorASelectionThePressSetsTheCentreWhereItBegins() {
        Rig rig = new Rig(BrushTool.SMOOTH);
        rig.set(rig.tool.settings().symmetry, Symmetry.Mode.ROTATE_2);
        rig.press(top(4, -3), 0);
        rig.frame(top(4, -3), 16);
        assertEquals(new Symmetry(Symmetry.Mode.ROTATE_2, 9, -5), specSymmetry(rig, 0));
        assertTrue(rig.tool.symmetryCentre().isSet(), "the centre stays for the next presses");
        assertEquals(Notice.of(Notice.Level.INFO, TerrainBrushTool.SYMMETRY_CENTRE_STARTED, "4.5", "-2.5", "M"),
                rig.notices.get(rig.notices.size() - 1));
        rig.release(top(4, -3));
        rig.press(top(-2, 6), 0);
        rig.frame(top(-2, 6), 16);
        assertEquals(new Symmetry(Symmetry.Mode.ROTATE_2, 9, -5), specSymmetry(rig, 1));
    }

    @Test
    void symmetryIsDrawnHintedAndCountedInTheEstimateAndThePacing() {
        Rig rig = new Rig(BrushTool.RAISE);
        rig.terrainMode();
        rig.tool.symmetryCentre().set(1, 1);
        rig.frame(top(3, 2), 16);
        RecordingDraw plain = new RecordingDraw();
        rig.tool.renderWorld(rig.view, plain);
        rig.set(rig.tool.settings().symmetry, Symmetry.Mode.MIRROR_XZ);
        RecordingDraw mirrored = new RecordingDraw();
        rig.tool.renderWorld(rig.view, mirrored);
        assertEquals(0, plain.lines);
        assertTrue(mirrored.lines >= 11, "the centre line and two plane frames: " + mirrored.lines);
        assertEquals(3, mirrored.rings, "a ring for each of the cursor's three copies");
        // The rings are the copies of the dab a press lays at the hit point (in 1/16 block), not of the block's centre:
        // a hit at (3.8, 2.1) is the dab (60, 33)/16, mirrored around (0.5, 0.5) to x -44/16 and z -17/16.
        int y = BrushTerrain.height(3, 2);
        rig.frame(new WorldCursor(new BlockPos(3, y, 2), WorldCursor.Face.UP, 3.8, y + 1, 2.1, false), 16);
        RecordingDraw offCentre = new RecordingDraw();
        rig.tool.renderWorld(rig.view, offCentre);
        assertEquals(List.of("-2.75,2.0625", "3.75,-1.0625", "-2.75,-1.0625"), offCentre.ringCentres);
        assertTrue(rig.tool.hints(rig.view).contains(new KeyHint("M", "sculptory.hint.brush.symmetry_centre")));

        // Radius 32 Raise is predicted alone (9.6k cells a dab) but not with four copies (38.5k).
        rig.set(rig.tool.settings().radius, 32);
        rig.press(top(0, 0), 0);
        assertEquals(4, rig.tool.controller().dabCost(), "each dab costs its four copies");
        rig.frame(top(0, 0), 16);
        assertFalse(rig.session.begins.get(0).params().predict(), "server only with its copies");
        rig.release(top(0, 0));
        rig.set(rig.tool.settings().symmetry, Symmetry.Mode.OFF);
        rig.press(top(0, 0), 0);
        assertEquals(1, rig.tool.controller().dabCost());
        rig.frame(top(0, 0), 16);
        assertTrue(rig.session.begins.get(1).params().predict());
        rig.release(top(0, 0));
    }

    @Test
    void aSymmetricStrokeIsPredictedExactlyAsTheServerReplaysIt() throws ProtocolException {
        Rig rig = new Rig(BrushTool.LOWER);
        FakeTransport transport = new FakeTransport(states);
        FabricEditorSession fabric = new FabricEditorSession(transport, () -> states, new AtomicLong()::get, "test");
        fabric.onJoin();
        fabric.onFrame(Codec.encodeS2C(new S2C.Welcome(ProtocolV2.VERSION, Features.of(Features.STROKES), Limits.DEFAULTS,
                Perm.mask(EnumSet.allOf(Perm.class)), 1L), states));
        rig.current = fabric;
        BrushTerrain.RecordingTarget target = new BrushTerrain.RecordingTarget(rig.world);
        rig.services.target = target;
        rig.set(rig.tool.settings().strength, 1.0);
        rig.set(rig.tool.settings().symmetry, Symmetry.Mode.MIRROR_XZ);
        rig.tool.symmetryCentre().set(-3, 1); // (-1.5, 0.5): the basin (x < -12) mirrors onto the ridge (x > 6)

        rig.press(top(-16, 2), 0);
        for (int step = 0; step <= 20; step++) {
            WorldCursor at = top(-16 + step / 2, 2 + step % 3);
            rig.drag(at);
            rig.frame(at, 60);
            fabric.tick();
            ack(fabric, transport);
        }
        rig.release(top(-6, 3));

        C2S.StrokeBegin begin = transport.sent.stream().filter(C2S.StrokeBegin.class::isInstance)
                .map(C2S.StrokeBegin.class::cast).findFirst().orElseThrow();
        assertEquals(new Symmetry(Symmetry.Mode.MIRROR_XZ, -3, 1), begin.spec().symmetry(), "the symmetry travels");
        List<Dab> sent = transport.sent.stream().filter(C2S.Dabs.class::isInstance).map(C2S.Dabs.class::cast)
                .flatMap(b -> b.dabs().stream()).toList();
        assertTrue(sent.size() >= 5, "dabs sent: " + sent.size());
        for (Dab dab : sent) assertTrue(dab.blockX() < -1, "only the originals travel: " + dab);
        FakeWorld server = BrushTerrain.world(states);
        List<BrushTerrain.Cell> expected = BrushTerrain.serverReplay(begin.spec(), sent, server);
        assertEquals(expected, target.cells, "the client predicted exactly what the server writes");
        assertTrue(expected.stream().anyMatch(cell -> cell.x() > 1 && cell.z() < 0), "the mirrored quadrant changed");
        int water = states.state("minecraft:water");
        assertTrue(expected.stream().anyMatch(cell -> cell.state() == water), "lowering in the basin refilled with water");
        assertArrayEquals(BrushTerrain.snapshot(server), BrushTerrain.snapshot(rig.world));
    }

    /**
     * East of x = 1 the terrain stands on a plateau 20 blocks higher than {@link BrushTerrain} (beyond a radius-5
     * brush's reach of 13 blocks), and columns x 6-8, z 2-4 rise to a pillar up to y 150.
     */
    private void plateau(FakeWorld world) {
        for (int x = 1; x <= BrushTerrain.EXTENT; x++) {
            for (int z = -BrushTerrain.EXTENT; z <= BrushTerrain.EXTENT; z++) {
                int top = BrushTerrain.height(x, z);
                for (int y = top; y < top + 20; y++) world.set(x, y, z, stone);
                world.set(x, top + 20, z, grass);
                world.set(x, top + 21, z, states.air());
                if (x >= 6 && x <= 8 && z >= 2 && z <= 4) {
                    for (int y = top + 20; y <= 150; y++) world.set(x, y, z, stone);
                }
            }
        }
    }

    /** Whether two worlds hold the same cells over the terrain's columns, y 40 to 160. */
    private static boolean sameTerrain(FakeWorld a, FakeWorld b) {
        for (int x = -BrushTerrain.EXTENT; x <= BrushTerrain.EXTENT; x++) {
            for (int z = -BrushTerrain.EXTENT; z <= BrushTerrain.EXTENT; z++) {
                for (int y = 40; y <= 160; y++) {
                    if (a.get(x, y, z) != b.get(x, y, z)) return false;
                }
            }
        }
        return true;
    }

    /**
     * Copies follow the terrain in the prediction exactly as on the server: a Mirror east/west Raise over the lowland
     * lays its copies on the plateau 20 blocks up (they change it), and the copies landing on the pillar find no ground
     * and write nothing, on both sides.
     */
    @Test
    void aSymmetricStrokeOverUnevenGroundIsPredictedExactlyAsTheServerReplaysIt() throws ProtocolException {
        Rig rig = new Rig(BrushTool.RAISE);
        plateau(rig.world);
        FakeTransport transport = new FakeTransport(states);
        FabricEditorSession fabric = new FabricEditorSession(transport, () -> states, new AtomicLong()::get, "test");
        fabric.onJoin();
        fabric.onFrame(Codec.encodeS2C(new S2C.Welcome(ProtocolV2.VERSION, Features.of(Features.STROKES), Limits.DEFAULTS,
                Perm.mask(EnumSet.allOf(Perm.class)), 1L), states));
        rig.current = fabric;
        BrushTerrain.RecordingTarget target = new BrushTerrain.RecordingTarget(rig.world);
        rig.services.target = target;
        rig.set(rig.tool.settings().strength, 1.0);
        rig.set(rig.tool.settings().symmetry, Symmetry.Mode.MIRROR_X);
        rig.tool.symmetryCentre().set(-3, 1); // x = -1.5: column x mirrors onto -4 - x, the lowland onto the plateau

        rig.press(top(-16, 2), 0);
        for (int step = 0; step <= 20; step++) {
            WorldCursor at = top(-16 + step / 2, 2 + step % 3);
            rig.drag(at);
            rig.frame(at, 60);
            fabric.tick();
            ack(fabric, transport);
        }
        rig.release(top(-6, 3));

        C2S.StrokeBegin begin = transport.sent.stream().filter(C2S.StrokeBegin.class::isInstance)
                .map(C2S.StrokeBegin.class::cast).findFirst().orElseThrow();
        assertTrue(begin.spec().symmetry().mode() == Symmetry.Mode.MIRROR_X);
        List<Dab> sent = transport.sent.stream().filter(C2S.Dabs.class::isInstance).map(C2S.Dabs.class::cast)
                .flatMap(b -> b.dabs().stream()).toList();
        assertTrue(sent.size() >= 5, "dabs sent: " + sent.size());
        FakeWorld server = BrushTerrain.world(states);
        plateau(server);
        List<BrushTerrain.Cell> expected = BrushTerrain.serverReplay(begin.spec(), sent, server);
        assertEquals(expected, target.cells, "the client predicted exactly what the server writes");
        assertTrue(sameTerrain(server, rig.world), "both worlds end the same");
        assertTrue(expected.stream().anyMatch(cell -> cell.x() >= 2 && cell.y() > 76), "the copies raised the plateau");
        assertTrue(expected.stream().noneMatch(cell -> cell.x() >= 6 && cell.x() <= 8 && cell.z() >= 2 && cell.z() <= 4),
                "the pillar is never written");
        assertTrue(sent.stream().anyMatch(d -> !SymmetricStep.of(begin.spec(), d, server).noGround().isEmpty()),
                "some copies landed on the pillar and found no ground");
        assertTrue(sent.stream().anyMatch(d -> SymmetricStep.of(begin.spec(), d, server).dabs().size() == 2),
                "others stood on the plateau");
    }

    @Test
    void theCopiesRingsStandOnTheGroundEachCopyFindsAndAreGreyWithoutGround() {
        Rig rig = new Rig(BrushTool.RAISE);
        plateau(rig.world);
        int pillarTop = BrushTerrain.height(4, -3) + 20;
        for (int y = pillarTop; y <= 150; y++) rig.world.set(4, y, -3, stone);
        rig.set(rig.tool.settings().symmetry, Symmetry.Mode.MIRROR_XZ);
        rig.tool.symmetryCentre().set(-3, 1); // (-1.5, 0.5)
        rig.frame(top(-8, 3), 16);
        RecordingDraw draw = new RecordingDraw();
        rig.tool.renderWorld(rig.view, draw);
        assertEquals(List.of("4.5,3.5", "-7.5,-2.5", "4.5,-2.5"), draw.ringCentres);
        double lift = BrushOutlines.LIFT;
        assertEquals(List.of(BrushTerrain.height(4, 3) + 21 + lift, BrushTerrain.height(-8, -3) + 1 + lift,
                BrushTerrain.height(-8, 3) + 1 + lift), draw.ringYs, "on the plateau, on the lowland, at the cursor");
        int color = OverlayColors.scaleAlpha(TerrainBrushTool.color(BrushTool.RAISE), 0.7);
        assertEquals(List.of(color, color, TerrainBrushTool.NO_GROUND_COLOR), draw.ringColors,
                "the copy on the pillar would find no ground: grey");
    }

    // ---------------------------------------------------------------- prediction end to end

    @Test
    void predictionRunsThroughTheFabricStrokeHandleAndMatchesTheServerReplay() throws ProtocolException {
        Rig rig = new Rig(BrushTool.RAISE);
        FakeTransport transport = new FakeTransport(states);
        FabricEditorSession fabric = new FabricEditorSession(transport, () -> states, new AtomicLong()::get, "test");
        fabric.onJoin();
        fabric.onFrame(Codec.encodeS2C(new S2C.Welcome(ProtocolV2.VERSION, Features.of(Features.STROKES), Limits.DEFAULTS,
                Perm.mask(EnumSet.allOf(Perm.class)), 1L), states));
        assertEquals(SessionState.READY, fabric.state());
        rig.current = fabric;
        BrushTerrain.RecordingTarget target = new BrushTerrain.RecordingTarget(rig.world);
        rig.services.target = target;

        rig.press(top(-8, 0), 0);
        for (int step = 0; step <= 24; step++) {
            WorldCursor at = top(-8 + step * 3 / 4, step % 3 - 1);
            rig.drag(at);
            rig.frame(at, 50);
            fabric.tick();
            ack(fabric, transport);
        }
        rig.release(top(10, 1));
        assertInstanceOf(C2S.StrokeEnd.class, transport.sent.get(transport.sent.size() - 1));

        C2S.StrokeBegin begin = assertInstanceOf(C2S.StrokeBegin.class, transport.sent.get(1));
        List<C2S.Dabs> batches = transport.sent.stream().filter(C2S.Dabs.class::isInstance).map(C2S.Dabs.class::cast).toList();
        assertFalse(batches.isEmpty());
        assertEquals(target.opened, batches.stream().map(C2S.Dabs::seq).toList(),
                "each batch carries the sequence it was predicted under");
        assertEquals(target.opened.size(), target.closed);
        List<Dab> sent = batches.stream().flatMap(b -> b.dabs().stream()).toList();
        assertTrue(sent.size() >= 10, "dabs sent: " + sent.size());

        FakeWorld server = BrushTerrain.world(states);
        List<BrushTerrain.Cell> expected = BrushTerrain.serverReplay(begin.spec(), sent, server);
        assertFalse(expected.isEmpty());
        assertEquals(expected, target.cells, "the client predicted exactly what the server writes");
        assertArrayEquals(BrushTerrain.snapshot(server), BrushTerrain.snapshot(rig.world));
    }

    @Test
    void serverOnlyStrokesInstallNoPrediction() throws ProtocolException {
        Rig rig = new Rig(BrushTool.SMOOTH);
        FakeTransport transport = new FakeTransport(states);
        FabricEditorSession fabric = new FabricEditorSession(transport, () -> states, new AtomicLong()::get, "test");
        fabric.onJoin();
        fabric.onFrame(Codec.encodeS2C(new S2C.Welcome(ProtocolV2.VERSION, Features.of(Features.STROKES), Limits.DEFAULTS,
                Perm.mask(EnumSet.allOf(Perm.class)), 1L), states));
        rig.current = fabric;
        rig.services.target = new BrushTerrain.RecordingTarget(rig.world);
        rig.set(rig.tool.settings().radius, 30);
        rig.press(top(0, 0), 0);
        rig.frame(top(0, 0), 16);
        assertInstanceOf(FabricStrokeHandle.class, rig.tool.controller().stroke());
        assertNull(rig.tool.predictor());
        fabric.tick();
        C2S.Dabs dabs = assertInstanceOf(C2S.Dabs.class, transport.sent.get(transport.sent.size() - 1));
        assertEquals(0, dabs.seq(), "nothing predicted, nothing to acknowledge");
    }

    @Test
    void aClippedStrokeIsPredictedExactlyAsTheServerReplaysIt() throws ProtocolException {
        // Lowering across the basin's shore: the removed cells inside the box refill from the water outside it.
        Rig rig = new Rig(BrushTool.LOWER);
        FakeTransport transport = new FakeTransport(states);
        FabricEditorSession fabric = new FabricEditorSession(transport, () -> states, new AtomicLong()::get, "test");
        fabric.onJoin();
        fabric.onFrame(Codec.encodeS2C(new S2C.Welcome(ProtocolV2.VERSION, Features.of(Features.STROKES), Limits.DEFAULTS,
                Perm.mask(EnumSet.allOf(Perm.class)), 1L), states));
        rig.current = fabric;
        BrushTerrain.RecordingTarget target = new BrushTerrain.RecordingTarget(rig.world);
        rig.services.target = target;
        Box selection = Box.of(new BlockPos(-14, 57, -3), new BlockPos(-4, 100, 2));
        rig.ctx.setSelection(selection);
        rig.set(rig.tool.settings().insideSelection, true);
        rig.set(rig.tool.settings().strength, 1.0);

        rig.press(top(-16, 0), 0);
        for (int step = 0; step <= 20; step++) {
            WorldCursor at = top(-16 + step, step % 3 - 1);
            rig.drag(at);
            rig.frame(at, 50);
            fabric.tick();
            ack(fabric, transport);
        }
        rig.release(top(4, 0));

        C2S.StrokeBegin begin = transport.sent.stream().filter(C2S.StrokeBegin.class::isInstance)
                .map(C2S.StrokeBegin.class::cast).findFirst().orElseThrow();
        assertEquals(selection, begin.spec().clip(), "the box travels with the stroke");
        List<Dab> sent = transport.sent.stream().filter(C2S.Dabs.class::isInstance).map(C2S.Dabs.class::cast)
                .flatMap(b -> b.dabs().stream()).toList();
        FakeWorld server = BrushTerrain.world(states);
        List<BrushTerrain.Cell> expected = BrushTerrain.serverReplay(begin.spec(), sent, server);
        assertFalse(expected.isEmpty());
        assertEquals(expected, target.cells, "the client predicted exactly what the server writes");
        for (BrushTerrain.Cell cell : expected) assertTrue(selection.contains(cell.x(), cell.y(), cell.z()), cell.toString());
        int water = states.state("minecraft:water");
        assertTrue(expected.stream().anyMatch(cell -> cell.state() == water), "some lowered cells refilled with water");
        assertArrayEquals(BrushTerrain.snapshot(server), BrushTerrain.snapshot(rig.world));
    }

    @Test
    void serverOnlyStrokesCarryTheMaskAndTheClipToo() throws ProtocolException {
        Rig rig = new Rig(BrushTool.FLATTEN);
        FakeTransport transport = new FakeTransport(states);
        FabricEditorSession fabric = new FabricEditorSession(transport, () -> states, new AtomicLong()::get, "test");
        fabric.onJoin();
        fabric.onFrame(Codec.encodeS2C(new S2C.Welcome(ProtocolV2.VERSION, Features.of(Features.STROKES), Limits.DEFAULTS,
                Perm.mask(EnumSet.allOf(Perm.class)), 1L), states));
        rig.current = fabric;
        rig.services.target = new BrushTerrain.RecordingTarget(rig.world);
        BrushSettings s = rig.tool.settings();
        rig.set(s.radius, 28);
        rig.set(s.maskBlocks, List.of(block("minecraft:grass_block")));
        rig.set(s.maskExactStates, true);
        rig.set(s.maskInvert, true);
        rig.set(s.insideSelection, true);
        Box selection = Box.of(new BlockPos(-20, 40, -20), new BlockPos(0, 90, 20));
        rig.ctx.setSelection(selection);
        rig.press(top(0, 0), 0);
        rig.frame(top(0, 0), 16);
        assertNull(rig.tool.predictor(), "a radius-28 Flatten runs on the server only");
        C2S.StrokeBegin begin = transport.sent.stream().filter(C2S.StrokeBegin.class::isInstance)
                .map(C2S.StrokeBegin.class::cast).findFirst().orElseThrow();
        assertEquals(selection, begin.spec().clip());
        assertEquals(new SurfaceMask.Rules(new EditMask(List.of(MaskEntry.of(new MaskRule.Is(BlockSet.of(
                new BlockSet.State(states.describe(grass)))))), true)), begin.spec().mask());
    }

    // ---------------------------------------------------------------- Surface mode

    /** A stone wall facing west: x 2-6, z -6..6, from the floor up to y 80, with bumps (x 1) and dents (x 2) on its face. */
    private void wall(FakeWorld world) {
        for (int x = 2; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                for (int y = BrushTerrain.FLOOR; y <= 80; y++) world.set(x, y, z, stone);
            }
        }
        world.set(1, 72, -1, stone);
        world.set(1, 73, 2, stone);
        world.set(2, 71, 1, states.air());
        world.set(2, 74, -2, states.air());
    }

    /** A hit on the wall's west face at (2, y + 0.5, z + 0.5). */
    private static WorldCursor wallAt(int y, int z) {
        return new WorldCursor(new BlockPos(2, y, z), WorldCursor.Face.WEST, 2.0, y + 0.5, z + 0.5, false);
    }

    @Test
    void onlyTheSculptingBrushesHaveAModeAndItStartsOnSurface() {
        for (BrushTool kind : BrushTool.TERRAIN) {
            BrushSettings settings = BrushSettings.forTool(kind);
            boolean sculpts = SculptMode.surfaceTool(kind);
            assertEquals(sculpts, settings.mode != null, kind.name());
            if (sculpts) {
                assertEquals(SculptMode.SURFACE, settings.mode.defaultValue(), kind.name());
                assertTrue(settings.schema().defs().contains(settings.mode));
            }
        }
        // "Terrain (from above)" sends the Terrain-mode spec, as before the Surface mode existed.
        Rig rig = new Rig(BrushTool.SMOOTH);
        rig.terrainMode();
        rig.press(top(0, 0), 0);
        rig.frame(top(0, 0), 16);
        assertEquals(new BrushSpec(BrushTool.SMOOTH, 5, 0.6f, Falloff.SMOOTH, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, SEED),
                rig.session.begins.get(0).spec());
    }

    @Test
    void surfaceFlattenFixesItsPlaneWhereThePressBeginsAndKeepsItOverRestarts() {
        Rig rig = new Rig(BrushTool.FLATTEN);
        wall(rig.world);
        rig.press(wallAt(72, 0), 0);
        rig.frame(wallAt(72, 0), 16);
        assertEquals(new SurfacePlane(Facing.WEST, 2), rig.session.begins.get(0).spec().plane(),
                "a wall facing west: its face at x 2");
        rig.drag(wallAt(70, 4));
        rig.frame(wallAt(70, 4), 50);
        rig.scroll(1, Modifiers.CONTROL); // radius 6, mid-stroke
        rig.frame(wallAt(70, 4), 300);
        assertEquals(2, rig.session.begins.size());
        assertEquals(new SurfacePlane(Facing.WEST, 2), rig.session.begins.get(1).spec().plane(), "a restart keeps it");
        RecordingDraw draw = new RecordingDraw();
        rig.tool.renderWorld(rig.view, draw);
        assertTrue(draw.segments.stream().anyMatch(s -> s[0] == 2 - BrushOutlines.LIFT && s[3] == 2 - BrushOutlines.LIFT),
                "the plane is drawn on the wall's face");
        rig.release(wallAt(70, 4));

        rig.press(top(-8, 0), 0);
        rig.frame(top(-8, 0), 16);
        assertEquals(new SurfacePlane(Facing.UP, BrushTerrain.height(-8, 0)), rig.session.begins.get(2).spec().plane(),
                "a new press on the ground: level at its top");
    }

    @Test
    void theSurfaceCursorLiesAcrossTheWayTheSurfaceFaces() {
        Rig rig = new Rig(BrushTool.RAISE);
        wall(rig.world);
        rig.frame(top(-2, 0), 16);
        assertNull(rig.services.current, "no terrain-hugging cursor in the Surface mode");
        RecordingDraw floor = new RecordingDraw();
        rig.tool.renderWorld(rig.view, floor);
        double y = BrushTerrain.height(-2, 0) + 1 + BrushOutlines.LIFT;
        List<double[]> flat = floor.segments.stream().filter(s -> s[1] == y && s[4] == y).toList();
        assertTrue(flat.size() >= 48, "the ring lies flat on the ground: " + flat.size() + " of " + floor.segments.size());
        assertTrue(floor.segments.stream().anyMatch(s -> s[4] - s[1] == 1.5), "Raise's tick points up");

        rig.frame(wallAt(72, 0), 16);
        RecordingDraw wall = new RecordingDraw();
        rig.tool.renderWorld(rig.view, wall);
        double x = 2 - BrushOutlines.LIFT;
        long upright = wall.segments.stream().filter(s -> s[0] == x && s[3] == x).count();
        assertTrue(upright >= 48, "the ring stands on the wall: " + upright);
        assertTrue(wall.segments.stream().anyMatch(s -> s[3] - s[0] == -1.5), "Raise's tick points west, out of the wall");
        rig.ctx.setModifiers(Modifiers.ALT);
        rig.frame(wallAt(72, 0), 16);
        RecordingDraw lower = new RecordingDraw();
        rig.tool.renderWorld(rig.view, lower);
        assertTrue(lower.segments.stream().anyMatch(s -> s[3] - s[0] == 1.5), "Alt: Lower's tick points into the wall");
        rig.ctx.setModifiers(0);

        // The ring is drawn depth tested in the full colour and again through the terrain at the ghost alpha.
        int raise = TerrainBrushTool.color(BrushTool.RAISE);
        int ghost = OverlayColors.scaleAlpha(raise, BrushOutlines.GHOST_ALPHA);
        long bright = wall.segmentColors.stream().filter(c -> c == raise).count();
        long ghosted = wall.segmentColors.stream().filter(c -> c == ghost).count();
        assertTrue(ghosted >= 48 && bright >= ghosted, "ring passes: " + bright + " bright, " + ghosted + " ghost");

        // A brush too big to predict draws its ring dashed: bright and faint segments alternate, none left out, so the
        // ring still reads as a whole circle. A big ring has more segments and a second line.
        rig.set(rig.tool.settings().radius, 10);
        rig.frame(wallAt(72, 0), 16);
        RecordingDraw solid = new RecordingDraw();
        rig.tool.renderWorld(rig.view, solid);
        int gap = OverlayColors.scaleAlpha(raise, BrushOutlines.DASH_GAP_ALPHA);
        assertEquals(0, solid.segmentColors.stream().filter(c -> c == gap).count(), "a predicted brush's ring is solid");
        Rig big = new Rig(BrushTool.SMOOTH);
        wall(big.world);
        big.set(big.tool.settings().radius, 25);
        big.frame(wallAt(72, 0), 16);
        RecordingDraw dashed = new RecordingDraw();
        big.tool.renderWorld(big.view, dashed);
        int smooth = TerrainBrushTool.color(BrushTool.SMOOTH);
        long dashes = dashed.segmentColors.stream().filter(c -> c == smooth).count();
        int smoothGap = OverlayColors.scaleAlpha(smooth, BrushOutlines.DASH_GAP_ALPHA);
        long gaps = dashed.segmentColors.stream().filter(c -> c == smoothGap).count();
        int segments = BrushOutlines.ringSegments(25);
        assertEquals(96, segments);
        assertEquals(2L * segments, dashes + gaps, "two depth-tested outer lines, each half dashes and half gaps");
        assertEquals(dashes, gaps);
        big.press(wallAt(72, 0), 0);
        big.frame(wallAt(72, 0), 16);
        assertFalse(big.session.begins.get(0).params().predict(), "a radius-25 Surface Smooth runs on the server only");
    }

    @Test
    void surfaceCopiesSquaresAndAltWorkOnAWall() {
        Rig rig = new Rig(BrushTool.RAISE);
        wall(rig.world);
        double face = 2 - BrushOutlines.LIFT;
        // Mirror north / south about z 0.5: the copy of a dab on the wall at z 3.5 lies on the same wall at z -2.5.
        rig.set(rig.tool.settings().symmetry, Symmetry.Mode.MIRROR_Z);
        rig.tool.symmetryCentre().set(1, 1);
        rig.frame(wallAt(72, 3), 16);
        RecordingDraw mirrored = new RecordingDraw();
        rig.tool.renderWorld(rig.view, mirrored);
        List<double[]> upright = mirrored.segments.stream().filter(s -> s[0] == face && s[3] == face).toList();
        assertTrue(upright.stream().anyMatch(s -> s[2] < -2), "the copy's ring stands on the wall at z -2.5");
        assertTrue(upright.stream().anyMatch(s -> s[2] > 3), "and the cursor's at z 3.5");
        rig.set(rig.tool.settings().symmetry, Symmetry.Mode.OFF);

        // A square brush draws a square on the wall: four sides 2 × radius + 1 long.
        rig.set(rig.tool.settings().shape, Shape.SQUARE);
        rig.frame(wallAt(72, 0), 16);
        RecordingDraw square = new RecordingDraw();
        rig.tool.renderWorld(rig.view, square);
        long sides = square.segments.stream().filter(s -> s[0] == face && s[3] == face)
                .filter(s -> Math.abs(Math.hypot(s[4] - s[1], s[5] - s[2]) - 11) < 1e-9).count();
        assertEquals(8, sides, "the square's sides on the wall, depth tested and see-through");
        rig.set(rig.tool.settings().shape, Shape.CIRCLE);

        // Alt mid-stroke restarts as a Surface Lower.
        rig.press(wallAt(72, 0), 0);
        rig.frame(wallAt(72, 0), 16);
        rig.ctx.setModifiers(Modifiers.ALT);
        rig.frame(wallAt(72, 0), 300);
        assertEquals(2, rig.session.begins.size());
        BrushSpec inverted = rig.session.begins.get(1).spec();
        assertEquals(BrushTool.LOWER, inverted.tool());
        assertEquals(SculptMode.SURFACE, inverted.mode());
        rig.ctx.setModifiers(0);
        rig.release(wallAt(72, 0));
    }

    /**
     * Raise, Lower, Smooth and Flatten dragged over a wall in the Surface mode are predicted exactly as the server
     * replays them, and change the wall around its face (inside the dabs' cylinders), not the ground below.
     */
    @Test
    void wallStrokesArePredictedExactlyAsTheServerReplaysThem() throws ProtocolException {
        for (BrushTool kind : List.of(BrushTool.RAISE, BrushTool.LOWER, BrushTool.SMOOTH, BrushTool.FLATTEN)) {
            Rig rig = new Rig(kind);
            wall(rig.world);
            FakeTransport transport = new FakeTransport(states);
            FabricEditorSession fabric = new FabricEditorSession(transport, () -> states, new AtomicLong()::get, "test");
            fabric.onJoin();
            fabric.onFrame(Codec.encodeS2C(new S2C.Welcome(ProtocolV2.VERSION, Features.of(Features.STROKES),
                    Limits.DEFAULTS, Perm.mask(EnumSet.allOf(Perm.class)), 1L), states));
            rig.current = fabric;
            BrushTerrain.RecordingTarget target = new BrushTerrain.RecordingTarget(rig.world);
            rig.services.target = target;
            rig.set(rig.tool.settings().radius, 3);
            rig.set(rig.tool.settings().strength, 1.0);

            rig.press(wallAt(72, -3), 0);
            for (int step = 0; step <= 12; step++) {
                WorldCursor at = wallAt(71 + step % 3, -3 + step / 2);
                rig.drag(at);
                rig.frame(at, 60);
                fabric.tick();
                ack(fabric, transport);
            }
            rig.release(wallAt(72, 3));

            C2S.StrokeBegin begin = transport.sent.stream().filter(C2S.StrokeBegin.class::isInstance)
                    .map(C2S.StrokeBegin.class::cast).findFirst().orElseThrow();
            assertEquals(SculptMode.SURFACE, begin.spec().mode(), kind.name());
            List<Dab> sent = transport.sent.stream().filter(C2S.Dabs.class::isInstance).map(C2S.Dabs.class::cast)
                    .flatMap(b -> b.dabs().stream()).toList();
            FakeWorld server = BrushTerrain.world(states);
            wall(server);
            List<BrushTerrain.Cell> expected = BrushTerrain.serverReplay(begin.spec(), sent, server);
            assertFalse(expected.isEmpty(), kind.name());
            assertEquals(expected, target.cells, kind + ": the client predicted exactly what the server writes");
            for (BrushTerrain.Cell cell : expected) {
                // Within the radius-3 cylinders (x 2 - 11 to 2 + 11) around the dabs on the face (x 2), well above the
                // ground (y 61-65): a held Raise grows outward, a Lower digs in.
                assertTrue(cell.x() >= -9 && cell.x() <= 13 && cell.y() >= 67, kind + " changed the wall's face only: " + cell);
            }
            assertArrayEquals(BrushTerrain.snapshot(server), BrushTerrain.snapshot(rig.world), kind.name());
        }
    }

    @Test
    void terrainModeStrokesArePredictedExactlyToo() throws ProtocolException {
        Rig rig = new Rig(BrushTool.SMOOTH);
        rig.terrainMode();
        FakeTransport transport = new FakeTransport(states);
        FabricEditorSession fabric = new FabricEditorSession(transport, () -> states, new AtomicLong()::get, "test");
        fabric.onJoin();
        fabric.onFrame(Codec.encodeS2C(new S2C.Welcome(ProtocolV2.VERSION, Features.of(Features.STROKES), Limits.DEFAULTS,
                Perm.mask(EnumSet.allOf(Perm.class)), 1L), states));
        rig.current = fabric;
        BrushTerrain.RecordingTarget target = new BrushTerrain.RecordingTarget(rig.world);
        rig.services.target = target;
        rig.press(top(-8, 0), 0);
        for (int step = 0; step <= 16; step++) {
            WorldCursor at = top(-8 + step, step % 3 - 1);
            rig.drag(at);
            rig.frame(at, 50);
            fabric.tick();
            ack(fabric, transport);
        }
        rig.release(top(8, 1));
        C2S.StrokeBegin begin = transport.sent.stream().filter(C2S.StrokeBegin.class::isInstance)
                .map(C2S.StrokeBegin.class::cast).findFirst().orElseThrow();
        assertEquals(SculptMode.TERRAIN, begin.spec().mode());
        List<Dab> sent = transport.sent.stream().filter(C2S.Dabs.class::isInstance).map(C2S.Dabs.class::cast)
                .flatMap(b -> b.dabs().stream()).toList();
        FakeWorld server = BrushTerrain.world(states);
        List<BrushTerrain.Cell> expected = BrushTerrain.serverReplay(begin.spec(), sent, server);
        assertFalse(expected.isEmpty());
        assertEquals(expected, target.cells);
    }

    // ---------------------------------------------------------------- the Weather brush

    /**
     * The Weather brush (slot 15, on backslash): its defaults send Erode in the Surface mode, its Weather and Mode rows
     * change the spec, it flows while held still, and it is registered after Tinker.
     */
    @Test
    void theWeatherBrushIsSlotFifteenAndSendsItsModes() {
        Rig rig = new Rig(BrushTool.WEATHER);
        BrushSettings settings = rig.tool.settings();
        assertEquals(WeatherSpec.Mode.ERODE, settings.weatherMode.defaultValue());
        assertEquals(SculptMode.SURFACE, settings.mode.defaultValue());
        assertTrue(settings.schema().defs().contains(settings.weatherMode));
        assertTrue(TerrainBrushTool.flows(BrushTool.WEATHER));
        assertEquals(ToolId.WEATHER, rig.tool.descriptor().id());
        assertEquals("sculptory.tool.weather", rig.tool.descriptor().nameKey());
        rig.press(top(0, 0), 0);
        rig.frame(top(0, 0), 16);
        BrushSpec erode = new BrushSpec(BrushTool.WEATHER, 5, 0.6f, Falloff.SMOOTH, Shape.CIRCLE, null, SurfaceMask.ANY, 0,
                0, SEED, null, Symmetry.NONE, null, SculptMode.TERRAIN, null, new WeatherSpec(WeatherSpec.Mode.ERODE))
                .withSurface(null);
        assertEquals(erode, rig.session.begins.get(0).spec());
        assertEquals(ToolId.WEATHER, rig.session.begins.get(0).tool());
        rig.release(top(0, 0));

        Rig melt = new Rig(BrushTool.WEATHER);
        melt.set(melt.tool.settings().weatherMode, WeatherSpec.Mode.MELT);
        melt.terrainMode();
        melt.press(top(0, 0), 0);
        melt.frame(top(0, 0), 16);
        BrushSpec spec = melt.session.begins.get(0).spec();
        assertEquals(new WeatherSpec(WeatherSpec.Mode.MELT), spec.weather());
        assertEquals(SculptMode.TERRAIN, spec.mode());

        ToolRegistry registry = new ToolRegistry();
        EditorToolSet.register(registry, new PlaceholderTool(new ToolDescriptor(ToolId.SELECT, "sculptory.tool.select",
                "minecraft:stone", Perm.REGION), "sculptory.hint.select.drag"));
        assertEquals(ToolRegistry.PALETTE_SLOTS, registry.paletteOrder().size());
        TerrainBrushTool weather = assertInstanceOf(TerrainBrushTool.class, registry.slot(15).orElseThrow());
        assertEquals(BrushTool.WEATHER, weather.kind());
        assertEquals(KeyAction.TOOL_15, KeyAction.toolSlot(15));
        assertEquals(List.of(KeyChord.parse("backslash")), KeyAction.TOOL_15.defaultChords());
        assertEquals("\\", EditorKeymap.defaults().display(KeyAction.TOOL_15));
    }

    /**
     * Each Weather mode, in both sculpt modes, is predicted through the Fabric stroke handle exactly as the server replays
     * the dabs it was sent: the same kernel, every read before the first write.
     */
    @Test
    void weatherStrokesArePredictedExactlyAsTheServerReplaysThem() throws ProtocolException {
        for (WeatherSpec.Mode mode : WeatherSpec.Mode.values()) {
            for (boolean terrain : new boolean[] {false, true}) {
                String what = mode + (terrain ? " terrain" : " surface");
                Rig rig = new Rig(BrushTool.WEATHER);
                rig.set(rig.tool.settings().weatherMode, mode);
                rig.set(rig.tool.settings().strength, 1.0);
                if (terrain) rig.terrainMode();
                FakeTransport transport = new FakeTransport(states);
                FabricEditorSession fabric = new FabricEditorSession(transport, () -> states, new AtomicLong()::get, "test");
                fabric.onJoin();
                fabric.onFrame(Codec.encodeS2C(new S2C.Welcome(ProtocolV2.VERSION, Features.of(Features.STROKES),
                        Limits.DEFAULTS, Perm.mask(EnumSet.allOf(Perm.class)), 1L), states));
                rig.current = fabric;
                BrushTerrain.RecordingTarget target = new BrushTerrain.RecordingTarget(rig.world);
                rig.services.target = target;
                rig.press(top(-6, 0), 0);
                for (int step = 0; step <= 16; step++) {
                    WorldCursor at = top(-6 + step * 3 / 4, step % 3 - 1);
                    rig.drag(at);
                    rig.frame(at, 50);
                    fabric.tick();
                    ack(fabric, transport);
                }
                rig.release(top(6, 1));
                C2S.StrokeBegin begin = transport.sent.stream().filter(C2S.StrokeBegin.class::isInstance)
                        .map(C2S.StrokeBegin.class::cast).findFirst().orElseThrow();
                assertEquals(new WeatherSpec(mode), begin.spec().weather(), what);
                List<Dab> sent = transport.sent.stream().filter(C2S.Dabs.class::isInstance).map(C2S.Dabs.class::cast)
                        .flatMap(b -> b.dabs().stream()).toList();
                FakeWorld server = BrushTerrain.world(states);
                List<BrushTerrain.Cell> expected = BrushTerrain.serverReplay(begin.spec(), sent, server);
                assertFalse(expected.isEmpty(), what);
                assertEquals(expected, target.cells, what + ": the client predicted exactly what the server writes");
                assertArrayEquals(BrushTerrain.snapshot(server), BrushTerrain.snapshot(rig.world), what);
            }
        }
    }

    /** Acknowledges every dab sent so far, as the server would after applying them. */
    private void ack(FabricEditorSession fabric, FakeTransport transport) throws ProtocolException {
        for (int i = transport.sent.size() - 1; i >= 0; i--) {
            if (transport.sent.get(i) instanceof C2S.Dabs dabs) {
                int last = dabs.dabs().get(dabs.dabs().size() - 1).index();
                fabric.onFrame(Codec.encodeS2C(new S2C.StrokeStatus(dabs.strokeId(), last, S2C.StrokeStatus.Status.OK, null),
                        states));
                return;
            }
        }
    }

    // ---------------------------------------------------------------- mix patterns

    /**
     * Palette Paint's Pattern: Random sends the weighted mix with the stroke's
     * seed, as always; Patches and Steepness lay the mix out, in its order, with the Seed setting; a Gradient needs its
     * line: a press without one paints nothing and says so, Alt+drag draws it (the line the brushes share, with a
     * toast), Esc drops a drag, and a press then paints along it. Without the Gradient, Alt+drag paints as before.
     */
    @Test
    void palettePaintSendsItsPatternAndAltDragDrawsTheGradientLine() {
        Rig rig = new Rig(BrushTool.PALETTE);
        MixPatternSettings pattern = rig.tool.settings().mixPattern;
        rig.set(rig.tool.settings().palette, List.of(new SettingDef.WeightedBlock(block("minecraft:grass_block"), 2),
                new SettingDef.WeightedBlock(block("minecraft:dirt"), 1),
                new SettingDef.WeightedBlock(block("minecraft:stone"), 1)));
        int[] order = {grass, dirt, stone};
        int[] weights = {2, 1, 1};
        assertEquals(PalettePattern.Kind.RANDOM, rig.setting(pattern.pattern), "Random by default");
        assertEquals(new Pattern.Weighted(order, weights, SEED), paint(rig, top(0, 0), 0));

        rig.set(pattern.pattern, PalettePattern.Kind.PATCHES);
        rig.set(pattern.patchSize, 9);
        rig.set(pattern.seed, 77L);
        assertEquals(new Pattern.Arranged(new Pattern.Weighted(order, weights, 77L), new MixLayout.Patches(9)),
                paint(rig, top(0, 0), 0));
        rig.set(pattern.pattern, PalettePattern.Kind.STEEPNESS);
        rig.set(pattern.steepnessEdge, 12);
        assertEquals(new Pattern.Arranged(new Pattern.Weighted(order, weights, 77L), new MixLayout.Steepness(12)),
                paint(rig, top(0, 0), 0));
        // Alt paints as usual without the Gradient.
        int begins = rig.session.begins.size();
        rig.press(top(1, 1), Modifiers.ALT);
        rig.frame(top(1, 1), 16);
        rig.release(top(1, 1));
        assertEquals(begins + 1, rig.session.begins.size(), "Alt+press painted");

        rig.set(pattern.pattern, PalettePattern.Kind.GRADIENT);
        begins = rig.session.begins.size();
        rig.press(top(2, 2), 0);
        rig.frame(top(2, 2), 16);
        rig.release(top(2, 2));
        assertEquals(begins, rig.session.begins.size(), "no line: nothing painted");
        assertTrue(rig.noticeKeys().contains(GradientDrag.NO_LINE));
        assertTrue(rig.tool.hints(rig.view).stream().anyMatch(h -> h.descriptionKey().equals("sculptory.hint.pattern.draw_line")));

        // Esc drops a drag; a release on the start block draws nothing.
        rig.press(top(-6, 0), Modifiers.ALT);
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL));
        rig.release(top(6, 0));
        rig.press(top(-6, 0), Modifiers.ALT);
        rig.release(top(-6, 0));
        GradientLine line = rig.tool.symmetryCentre().gradientLine();
        assertFalse(line.isSet());
        assertTrue(rig.noticeKeys().contains(GradientDrag.LINE_TOO_SHORT));

        rig.press(top(-6, 0), Modifiers.ALT);
        rig.drag(top(0, 0));
        rig.drag(SKY);
        rig.drag(top(6, 1));
        rig.release(SKY);
        assertEquals(begins, rig.session.begins.size(), "the Alt+drag drew, it didn't paint");
        assertEquals(top(-6, 0).pos(), line.from());
        assertEquals(top(6, 1).pos(), line.to(), "the sky keeps the last block");
        assertTrue(rig.noticeKeys().contains(GradientDrag.LINE_SET));
        assertEquals(new Pattern.Arranged(new Pattern.Weighted(order, weights, 77L),
                new MixLayout.Gradient(top(-6, 0).pos(), top(6, 1).pos(), MixLayout.Gradient.DEFAULT_EDGE)),
                paint(rig, top(0, 0), 0));
        RecordingDraw draw = new RecordingDraw();
        rig.tool.renderWorld(rig.view, draw);
        assertTrue(draw.lines >= 5, "the arrow: its shaft and a four-line head, " + draw.lines);
    }

    /** A press at {@code at}: the material of the stroke it began (a stroke must begin). */
    private static Pattern paint(Rig rig, WorldCursor at, int modifiers) {
        int begins = rig.session.begins.size();
        rig.press(at, modifiers);
        rig.frame(at, 16);
        rig.release(at);
        assertEquals(begins + 1, rig.session.begins.size(), "a stroke began");
        return rig.session.begins.get(begins).spec().material();
    }

    // ---------------------------------------------------------------- fixtures

    /** One brush tool, active in an editor context over bumpy terrain and a recording mock session. */
    private final class Rig {
        final FakeWorld world = BrushTerrain.world(states);
        final MockEditorSession mock = new MockEditorSession();
        final RecordingSession session = new RecordingSession(mock);
        EditorSession current = session;
        final List<Notice> notices = new ArrayList<>();
        final FakeServices services = new FakeServices();
        final EditorContext ctx;
        final TerrainBrushTool tool;
        final ToolContext view;
        long now = 1_000 * MS;

        Rig(BrushTool kind) {
            this(kind, states);
        }

        /** A rig whose editor sees {@code space} (over the same handles as the fake world). */
        Rig(BrushTool kind, StateSpace space) {
            EditorBackend backend = new EditorBackend() {
                @Override
                public Optional<EditorSession> session() {
                    return Optional.of(current);
                }

                @Override
                public StateSpace states() {
                    return space;
                }

                @Override
                public WorldReader world() {
                    return world;
                }
            };
            ctx = new EditorContext(() -> backend, notices::add);
            tool = new TerrainBrushTool(kind, services);
            ctx.tools().register(tool);
            view = ctx.contextFor(tool.descriptor().id());
            assertTrue(ctx.tools().activate(tool.descriptor().id(), view));
        }

        void frame(WorldCursor cursor, long advanceMillis) {
            now += advanceMillis * MS;
            tool.frame(view, new FrameInfo(now, 0f, 0, 0, cursor));
        }

        void press(WorldCursor cursor, int modifiers) {
            assertTrue(tool.onPointer(view, new PointerEvent(PointerEvent.Kind.PRESS, PointerEvent.LEFT, 0, 0, modifiers, cursor)));
        }

        void drag(WorldCursor cursor) {
            tool.onPointer(view, new PointerEvent(PointerEvent.Kind.DRAG, PointerEvent.LEFT, 0, 0, 0, cursor));
        }

        void release(WorldCursor cursor) {
            tool.onPointer(view, new PointerEvent(PointerEvent.Kind.RELEASE, PointerEvent.LEFT, 0, 0, 0, cursor));
        }

        boolean scroll(double amount, int modifiers) {
            return tool.onScroll(view, new ScrollEvent(amount, modifiers));
        }

        <T> void set(SettingDef<T> def, T value) {
            ToolId id = tool.descriptor().id();
            ctx.updateSettings(id, ctx.settings(id).with(def, value));
        }

        SettingsValues values() {
            return ctx.settings(tool.descriptor().id());
        }

        <T> T setting(SettingDef<T> def) {
            return ctx.settings(tool.descriptor().id()).get(def);
        }

        /** Sets Mode to "Terrain (from above)" (for the brushes that have it): the column brush as before. */
        void terrainMode() {
            if (tool.settings().mode != null) {
                set(tool.settings().mode, SculptMode.TERRAIN);
            }
        }

        List<String> noticeKeys() {
            return notices.stream().map(Notice::key).toList();
        }
    }

    /**
     * The fake state space with every state's text {@code bytes} long (as a modded state with many long properties
     * might be); only for encoding, the text doesn't parse back.
     */
    private record LongStateNames(FakeStateSpace delegate, int bytes, int longHandle, int extra) implements StateSpace {
        /** Every state's text {@code bytes} long. */
        LongStateNames(FakeStateSpace delegate, int bytes) {
            this(delegate, bytes, -1, 0);
        }

        /** State {@code longHandle}'s text is {@code extra} bytes longer than the others. */
        @Override
        public String format(int h) {
            String start = "testmod:state_" + h + "[name=";
            return start + "a".repeat(bytes + (h == longHandle ? extra : 0) - start.length() - 1) + "]";
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public int air() {
            return delegate.air();
        }

        @Override
        public int flags(int h) {
            return delegate.flags(h);
        }

        @Override
        public int parse(String spec) {
            return delegate.parse(spec);
        }

        @Override
        public BlockDescriptor describe(int h) {
            return delegate.describe(h);
        }

        @Override
        public int resolve(BlockDescriptor d) {
            return delegate.resolve(d);
        }

        @Override
        public NamespacedId blockId(int h) {
            return delegate.blockId(h);
        }

        @Override
        public boolean inTag(int h, NamespacedId tag) {
            return delegate.inTag(h, tag);
        }

        @Override
        public int rotate(int h, int clockwiseQuarterTurns) {
            return delegate.rotate(h, clockwiseQuarterTurns);
        }

        @Override
        public int mirror(int h, dev.sculptory.core.transform.Mirror m) {
            return delegate.mirror(h, m);
        }

        @Override
        public int withWaterlogged(int h, boolean on) {
            return delegate.withWaterlogged(h, on);
        }

        @Override
        public int fluidSource(int h) {
            return delegate.fluidSource(h);
        }
    }

    private static final class FakeServices implements BrushServices {
        final EditorKeymap keymap = EditorKeymap.defaults();
        BrushPredictor.Target target;
        BrushCursor current;
        boolean focused = true;

        @Override
        public BrushPredictor.Target predictionTarget() {
            return target;
        }

        @Override
        public void showCursor(BrushCursor cursor) {
            current = cursor;
        }

        @Override
        public void clearCursor() {
            current = null;
        }

        @Override
        public long changeStamp() {
            return 0;
        }

        @Override
        public boolean windowFocused() {
            return focused;
        }

        @Override
        public Optional<KeyAction> scrollAction(int modifiers) {
            return keymap.match(KeyChord.scroll(modifiers));
        }

        @Override
        public String keyLabel(KeyAction action) {
            return keymap.display(action);
        }

        @Override
        public long nextSeed() {
            return SEED;
        }
    }

    /** The mock session, recording each stroke's tool, spec and params. */
    private static final class RecordingSession implements EditorSession {
        record Begin(ToolId tool, BrushSpec spec, StrokeParams params, RecordingStroke handle) {}

        final MockEditorSession mock;
        final List<Begin> begins = new ArrayList<>();

        RecordingSession(MockEditorSession mock) {
            this.mock = mock;
        }

        @Override
        public StrokeHandle beginStroke(ToolId tool, BrushSpec spec, StrokeParams p) {
            RecordingStroke stroke = new RecordingStroke(mock.beginStroke(tool, spec, p));
            begins.add(new Begin(tool, spec, p, stroke));
            return stroke;
        }

        @Override
        public SessionState state() {
            return mock.state();
        }

        @Override
        public Capabilities capabilities() {
            return mock.capabilities();
        }

        @Override
        public Permissions permissions() {
            return mock.permissions();
        }

        @Override
        public CompletionStage<ToolResult> send(ToolAction a) {
            return mock.send(a);
        }

        @Override
        public void undo() {
            mock.undo();
        }

        @Override
        public void redo() {
            mock.redo();
        }

        @Override
        public void jumpTo(long historyId) {
            mock.jumpTo(historyId);
        }

        @Override
        public JobTracker jobs() {
            return mock.jobs();
        }

        @Override
        public HistoryMirror history() {
            return mock.history();
        }

        @Override
        public ClipboardCache clipboards() {
            return mock.clipboards();
        }

        @Override
        public Subscription onNotice(Consumer<Notice> l) {
            return mock.onNotice(l);
        }
    }

    private static final class RecordingStroke implements StrokeHandle {
        final StrokeHandle delegate;
        final List<Dab> dabs = new ArrayList<>();
        boolean ended;
        boolean cancelled;

        RecordingStroke(StrokeHandle delegate) {
            this.delegate = delegate;
        }

        @Override
        public int strokeId() {
            return delegate.strokeId();
        }

        @Override
        public void dab(Dab d) {
            if (delegate.active()) {
                dabs.add(d);
            }
            delegate.dab(d);
        }

        @Override
        public void end() {
            ended = true;
            delegate.end();
        }

        @Override
        public void cancel() {
            cancelled = true;
            delegate.cancel();
        }

        @Override
        public boolean active() {
            return delegate.active();
        }
    }

    private static final class FakeTransport implements FabricEditorSession.Transport {
        final StateSpace states;
        final List<C2S> sent = new ArrayList<>();

        FakeTransport(StateSpace states) {
            this.states = states;
        }

        @Override
        public boolean canSend() {
            return true;
        }

        @Override
        public void send(byte[] frame) {
            try {
                sent.add(Codec.decodeC2S(frame, states));
            } catch (ProtocolException e) {
                throw new AssertionError("The client sent an undecodable frame", e);
            }
        }
    }

    private static final class RecordingDraw implements WorldDraw {
        int lines;
        int rings;
        final List<String> ringCentres = new ArrayList<>();
        final List<Double> ringYs = new ArrayList<>();
        final List<Integer> ringColors = new ArrayList<>();
        final List<Box> boxes = new ArrayList<>();
        final List<Double> planeYs = new ArrayList<>();
        /** Every line drawn: x1, y1, z1, x2, y2, z2, and its colour. */
        final List<double[]> segments = new ArrayList<>();
        final List<Integer> segmentColors = new ArrayList<>();

        @Override
        public void boxOutline(Box box, int argb) {
            boxes.add(box);
        }

        @Override
        public void boxFill(Box box, int argb) {}

        @Override
        public void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb) {
            lines++;
            segments.add(new double[] {x1, y1, z1, x2, y2, z2});
            segmentColors.add(argb);
            if (y1 == y2 && x1 != x2 && z1 == z2 && Math.abs(x2 - x1) > 2) {
                planeYs.add(y1);
            }
        }

        @Override
        public void ring(double centerX, double y, double centerZ, double radius, int argb) {
            rings++;
            ringCentres.add(centerX + "," + centerZ);
            ringYs.add(y);
            ringColors.add(argb);
            planeYs.add(y);
        }

        @Override
        public void seeThrough(boolean enabled) {}
    }
}
