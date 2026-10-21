package dev.sculptory.mc.client.editor.tools.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.HeightImage;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.HeightmapAreaKernel;
import dev.sculptory.core.generate.RoadKernel;
import dev.sculptory.core.generate.RoofKernel;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.mc.client.editor.EditorBackend;
import dev.sculptory.mc.client.editor.EditorContext;
import dev.sculptory.mc.client.editor.heightmap.HeightmapLibrary;
import dev.sculptory.mc.client.editor.heightmap.HeightmapRef;
import dev.sculptory.mc.client.editor.hud.HelpSheet;
import dev.sculptory.mc.client.editor.input.EditorKeymap;
import dev.sculptory.mc.client.editor.input.KeyAction;
import dev.sculptory.mc.client.editor.input.KeyChord;
import dev.sculptory.mc.client.editor.mock.MockEditorSession;
import dev.sculptory.mc.client.editor.render.ghost.GhostPlacement;
import dev.sculptory.mc.client.editor.render.ghost.GhostVolume;
import dev.sculptory.mc.client.editor.settings.SettingDef;
import dev.sculptory.mc.client.editor.settings.SettingsValues;
import dev.sculptory.mc.client.editor.tool.EditorAction;
import dev.sculptory.mc.client.editor.tool.FrameInfo;
import dev.sculptory.mc.client.editor.tool.KeyHint;
import dev.sculptory.mc.client.editor.tool.Modifiers;
import dev.sculptory.mc.client.editor.tool.PointerEvent;
import dev.sculptory.mc.client.editor.tool.ScrollEvent;
import dev.sculptory.mc.client.editor.tool.Tool;
import dev.sculptory.mc.client.editor.tool.ToolContext;
import dev.sculptory.mc.client.editor.tool.ToolDescriptor;
import dev.sculptory.mc.client.editor.tool.ToolId;
import dev.sculptory.mc.client.editor.tool.ToolRegistry;
import dev.sculptory.mc.client.editor.tool.WorldCursor;
import dev.sculptory.mc.client.editor.tool.WorldDraw;
import dev.sculptory.mc.client.editor.tools.EditorToolSet;
import dev.sculptory.mc.client.editor.tools.PlaceholderTool;
import dev.sculptory.mc.client.editor.world.Ray;
import dev.sculptory.mc.client.session.EditorSession;
import dev.sculptory.mc.client.session.Notice;
import dev.sculptory.mc.client.session.Permissions;
import dev.sculptory.mc.client.session.ToolAction;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.OpLabel;
import dev.sculptory.server.engine.Perm;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

/** The Generate tool: slot and key, the path's nodes, previews, Enter building through an upload and a paste, roofs. */
class GenerateToolTest {
    private static final long MS = 1_000_000L;
    /** The flat world's ground. */
    private static final int GROUND = 63;

    private final FakeStateSpace states = new FakeStateSpace();

    /** A hit on top of the flat ground at column (x, z). */
    private static WorldCursor top(int x, int z) {
        return new WorldCursor(new BlockPos(x, GROUND, z), WorldCursor.Face.UP, x + 0.5, GROUND + 1, z + 0.5, false);
    }

    // ---------------------------------------------------------------- registration

    @Test
    void theToolIsPaletteSlotElevenOnKeyMinus() {
        ToolRegistry registry = new ToolRegistry();
        EditorToolSet.register(registry, new PlaceholderTool(new ToolDescriptor(ToolId.SELECT, "sculptory.tool.select",
                "minecraft:stone", Perm.REGION), "sculptory.hint.select.drag"));
        assertEquals(17, registry.paletteOrder().size(), "Extrude is slot 12, the Fluid tool 13, Tinker 14, Weather 15, Heightmap 16, Biome 17");
        assertEquals(17, ToolRegistry.PALETTE_SLOTS);
        Tool generate = registry.slot(11).orElseThrow();
        assertInstanceOf(GenerateTool.class, generate);
        assertEquals(ToolId.GENERATE, generate.descriptor().id());
        assertEquals(Perm.REGION, generate.descriptor().permission(), "greyed out without region: it places any block");
        assertEquals("sculptory.tool.generate", generate.descriptor().nameKey());
        assertEquals(KeyAction.TOOL_11, KeyAction.toolSlot(11));
        assertEquals(11, KeyAction.TOOL_11.toolSlot());
        assertEquals(0, KeyAction.TOOL_SIZE.toolSlot());
        assertEquals(List.of(KeyChord.parse("minus")), KeyAction.TOOL_11.defaultChords());
        assertEquals("1–9, 0, -, =, [", HelpSheet.toolKeys(EditorKeymap.defaults()));
        assertEquals(List.of(KeyChord.parse("backspace")), KeyAction.REMOVE_NODE.defaultChords());
        assertEquals(Optional.of(EditorAction.REMOVE_NODE), KeyAction.REMOVE_NODE.editorAction());
        assertEquals("-", EditorKeymap.defaults().display(KeyAction.TOOL_11));
    }

    // ---------------------------------------------------------------- nodes

    @Test
    void aClickOnTheCentreLineInsertsANodeOnTheGroundNotOnTheLineDrawnOverIt() {
        Rig rig = new Rig();
        rig.click(top(0, 0));
        rig.click(top(10, 0));
        rig.frame(top(10, 0));
        assertTrue(rig.tool.preview().isPresent());
        // An oblique view: every axis shows as a line of its own.
        rig.services.projector = (x, y, z, out) -> {
            out[0] = 20 * x + 8 * z;
            out[1] = -20 * y + 8 * z;
            return true;
        };
        rig.services.eye = new double[] {5.5, GROUND + 20.5, 20.5};
        rig.tool.onAction(rig.view, EditorAction.CANCEL); // nothing selected, so no gizmo is in the way
        // The centre line is drawn on the ground, 1.05 over the nodes' blocks: aim at it halfway along.
        double sx = 20 * 6.5 + 8 * 0.5, sy = -20 * (GROUND + 1.05) + 8 * 0.5;
        WorldCursor ground = top(6, 3);
        rig.tool.frame(rig.view, new FrameInfo(rig.now += 16 * MS, 0f, sx, sy, ground));
        rig.tool.onPointer(rig.view, new PointerEvent(PointerEvent.Kind.PRESS, PointerEvent.LEFT, sx, sy, 0, ground));
        assertEquals(List.of(new BlockPos(0, GROUND, 0), new BlockPos(6, GROUND, 0), new BlockPos(10, GROUND, 0)),
                rig.tool.path().nodes(), "on the ground block, as a clicked node is");
        assertEquals(1, rig.tool.path().selected());
        assertEquals(RoadKernel.HeightMode.FOLLOW_TERRAIN, rig.values().get(GenerateSettings.HEIGHT),
                "a node on the ground leaves the Height as it is");
    }

    @Test
    void useStairsAndSlabsTurnsAStraightRoadsStepsIntoStairsUpTheClimb() {
        Rig rig = new Rig();
        rig.set(GenerateSettings.HEIGHT, RoadKernel.HeightMode.STRAIGHT);
        rig.set(GenerateSettings.BLOCK, BlockDescriptor.parse("minecraft:stone_bricks"));
        rig.set(GenerateSettings.PATH_STAIRS_AND_SLABS, true);
        rig.click(top(0, 0));
        rig.click(new WorldCursor(new BlockPos(12, GROUND + 6, 0), WorldCursor.Face.UP, 12.5, GROUND + 7, 0.5, false));
        rig.frame(top(12, 0));
        GeneratedSource road = rig.tool.preview().orElseThrow().source();
        int stairs = states.state("minecraft:stone_brick_stairs[facing=east,half=bottom]");
        int[] count = {0};
        road.forEach((x, y, z, state) -> {
            if (state == stairs) count[0]++;
        });
        assertTrue(count[0] >= 5 * 3, "a stair across the road's width at each step up: " + count[0]);
        assertTrue(rig.noticeKeys().isEmpty());
        // A block without stairs or slabs: full blocks, and a toast says why, once.
        rig.set(GenerateSettings.BLOCK, BlockDescriptor.parse("minecraft:stone"));
        rig.frame(top(12, 0));
        rig.frame(top(12, 0));
        assertEquals(List.of(GenerateTool.NO_STEPS), rig.noticeKeys());
        rig.tool.preview().orElseThrow().source().forEach((x, y, z, state) -> assertTrue(state != stairs));
    }

    @Test
    void clicksAddNodesAndAClickOnANodeSelectsIt() {
        Rig rig = new Rig();
        rig.click(top(0, 0));
        assertEquals(List.of(new BlockPos(0, GROUND, 0)), rig.tool.path().nodes());
        rig.frame(top(0, 0));
        assertTrue(rig.tool.preview().isEmpty(), "one node shows just a marker");
        rig.click(top(6, 0));
        assertEquals(2, rig.tool.path().size());
        assertEquals(1, rig.tool.path().selected(), "the new end node is selected");
        rig.frame(top(6, 0));
        GenerateTool.Built built = rig.tool.preview().orElseThrow();
        assertEquals(7 * 3, built.cells(), "a three-wide road over seven columns");
        assertNotNull(built.ghost());
        assertEquals(1, rig.services.shown.size());
        assertEquals(new BlockPos(0, GROUND, -1), origin(rig.services.shown));

        rig.press(top(6, 0));
        assertEquals(1, rig.tool.path().selected(), "a click on a node's column selects it");
        rig.release(top(6, 0));
        assertEquals(2, rig.tool.path().size(), "no node added");
        rig.press(top(0, 1));
        assertEquals(3, rig.tool.path().size(), "another column adds a node");
        rig.release(top(0, 1));
    }

    @Test
    void draggingMovesANodeAndDeleteRemovesTheSelectedOrLastOne() {
        Rig rig = new Rig();
        rig.click(top(0, 0));
        rig.click(top(6, 0));
        rig.frame(top(6, 0));
        // Grabbed from straight above, 17 blocks up: it keeps that distance as the cursor moves.
        rig.services.ray = new Ray(6.5, GROUND + 17.5, 0.5, 0, -1, 0);
        rig.press(top(6, 0));
        rig.services.ray = new Ray(6.5, GROUND + 17.5, 0.5, 0, -17, 2);
        rig.drag(top(6, 2));
        assertEquals(new BlockPos(6, GROUND, 2), rig.tool.path().node(1), "the node follows the drag");
        assertTrue(rig.ctx.pointerCapture(), "captured while dragging");
        rig.services.ray = new Ray(6.5, GROUND + 17.5, 0.5, 0, -17, 3);
        rig.release(top(6, 3));
        assertEquals(new BlockPos(6, GROUND, 3), rig.tool.path().node(1));
        rig.services.ray = null;
        assertFalse(rig.ctx.pointerCapture());
        rig.frame(top(6, 3));
        assertTrue(rig.tool.preview().orElseThrow().source().contains(6, GROUND, 3), "the preview followed");

        rig.click(top(12, 3));
        rig.press(top(0, 0));
        rig.release(top(0, 0));
        assertEquals(0, rig.tool.path().selected());
        assertTrue(rig.tool.onAction(rig.view, EditorAction.ERASE_SELECTION), "Delete removes the selected node");
        assertEquals(List.of(new BlockPos(6, GROUND, 3), new BlockPos(12, GROUND, 3)), rig.tool.path().nodes());
        assertEquals(0, rig.tool.path().selected(), "the new first node is selected");
        assertTrue(rig.tool.onAction(rig.view, EditorAction.REMOVE_NODE), "Backspace removes the selected one");
        assertEquals(List.of(new BlockPos(12, GROUND, 3)), rig.tool.path().nodes());
        assertEquals(0, rig.tool.path().selected(), "the one left is selected");
        rig.frame(top(6, 3));
        assertTrue(rig.tool.preview().isEmpty(), "one node: no preview");
        assertEquals(List.of(), rig.services.shown, "the ghost is gone");
        assertTrue(rig.tool.onAction(rig.view, EditorAction.ERASE_SELECTION));
        assertFalse(rig.tool.onAction(rig.view, EditorAction.ERASE_SELECTION), "with no nodes Delete erases the selection");
    }

    @Test
    void escStopsADragThenClearsThePath() {
        Rig rig = new Rig();
        rig.click(top(0, 0));
        rig.click(top(6, 0));
        rig.press(top(6, 0));
        rig.drag(top(6, 4));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL), "the first Esc drops the drag");
        assertEquals(new BlockPos(6, GROUND, 0), rig.tool.path().node(1), "the node is back where it was");
        assertEquals(2, rig.tool.path().size());
        rig.frame(top(6, 0));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL), "the next Esc clears the path");
        assertTrue(rig.tool.path().isEmpty());
        rig.frame(top(6, 0));
        assertTrue(rig.tool.preview().isEmpty());
        assertFalse(rig.tool.onAction(rig.view, EditorAction.CANCEL), "nothing left to cancel");
    }

    @Test
    void aRunningPreviewIsTheFirstRungOfEsc() {
        Rig rig = new Rig();
        rig.services.runAtOnce = false;
        rig.click(top(0, 0));
        rig.click(top(6, 0));
        rig.frame(top(6, 0));
        assertTrue(rig.tool.previewRunning());
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL), "Esc deselects the new end node first");
        assertTrue(rig.tool.previewRunning());
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL), "then drops the preview being made");
        assertFalse(rig.tool.previewRunning());
        assertEquals(2, rig.tool.path().size(), "the nodes stay");
        rig.services.runPending();
        rig.frame(top(6, 0));
        assertTrue(rig.tool.preview().isEmpty(), "the dropped preview's result is not shown");
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL));
        assertTrue(rig.tool.path().isEmpty());
    }

    // ---------------------------------------------------------------- previews

    @Test
    void thePreviewFollowsEverySettingChange() {
        Rig rig = new Rig();
        rig.click(top(0, 0));
        rig.click(top(8, 0));
        rig.frame(top(8, 0));
        assertEquals(27, rig.tool.preview().orElseThrow().cells());
        rig.set(GenerateSettings.WIDTH, 5);
        rig.frame(top(8, 0));
        assertEquals(45, rig.tool.preview().orElseThrow().cells());
        rig.set(GenerateSettings.FILL_BELOW, 1);
        rig.set(GenerateSettings.CLEAR_ABOVE, 2);
        rig.frame(top(8, 0));
        GenerateTool.Built built = rig.tool.preview().orElseThrow();
        assertEquals(45 * 4, built.cells());
        assertEquals(states.air(), built.source().get(4, GROUND + 2, 0), "clear above is air");
        assertEquals(states.state("minecraft:cobblestone"), built.source().get(4, GROUND, 0), "the default material");
        rig.set(GenerateSettings.BORDER, true);
        rig.set(GenerateSettings.BORDER_BLOCK, BlockDescriptor.parse("minecraft:stone"));
        rig.frame(top(8, 0));
        assertEquals(states.state("minecraft:stone"), rig.tool.preview().orElseThrow().source().get(4, GROUND, -2));
        rig.set(GenerateSettings.MATERIAL, GenerateSettings.Material.PALETTE);
        rig.set(GenerateSettings.PALETTE, List.of(new SettingDef.WeightedBlock(BlockDescriptor.parse("minecraft:dirt"), 1)));
        rig.frame(top(8, 0));
        assertEquals(states.state("minecraft:dirt"), rig.tool.preview().orElseThrow().source().get(4, GROUND, 0));
        assertEquals(1, rig.services.shown.size(), "a ghost each time");
        assertTrue(rig.services.released.size() >= 4, "the old ghosts were released: " + rig.services.released.size());
    }

    @Test
    void ctrlScrollChangesTheWidth() {
        Rig rig = new Rig();
        assertTrue(rig.scroll(1, Modifiers.CONTROL));
        assertEquals(4, rig.setting(GenerateSettings.WIDTH));
        assertTrue(rig.scroll(1, Modifiers.CONTROL | Modifiers.SHIFT));
        assertEquals(8, rig.setting(GenerateSettings.WIDTH));
        for (int i = 0; i < 20; i++) rig.scroll(1, Modifiers.CONTROL | Modifiers.SHIFT);
        assertEquals(RoadKernel.MAX_WIDTH, rig.setting(GenerateSettings.WIDTH));
        for (int i = 0; i < 40; i++) rig.scroll(-1, Modifiers.CONTROL);
        assertEquals(RoadKernel.MIN_WIDTH, rig.setting(GenerateSettings.WIDTH));
        assertFalse(rig.scroll(1, 0), "plain scroll is the fly speed");
        rig.set(GenerateSettings.KIND, GenerateSettings.Kind.ROOF);
        assertFalse(rig.scroll(1, Modifiers.CONTROL), "a roof has no width");
    }

    @Test
    void anUnknownBlockRefusesThePreview() {
        Rig rig = new Rig();
        rig.set(GenerateSettings.BLOCK, BlockDescriptor.parse("minecraft:no_such_block"));
        rig.click(top(0, 0));
        rig.click(top(6, 0));
        rig.frame(top(6, 0));
        assertTrue(rig.tool.preview().isEmpty());
        assertEquals(List.of("sculptory.notice.unknown_block"), rig.noticeKeys());
        rig.frame(top(6, 0));
        assertEquals(1, rig.notices.size(), "said once per change");
    }

    // ---------------------------------------------------------------- building

    @Test
    void enterUploadsTheRoadThenPastesItAsOneJob() {
        Rig rig = new Rig();
        rig.click(top(0, 0));
        rig.click(top(8, 0));
        rig.frame(top(8, 0));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
        rig.frame(top(8, 0));
        Box bounds = new Box(new BlockPos(0, GROUND, -1), new BlockPos(8, GROUND, 1));
        assertEquals(List.of(new MockEditorSession.Call("generate", bounds)), rig.mock.calls());
        assertEquals(1, rig.mock.sent().size());
        ToolAction.RunOp run = assertInstanceOf(ToolAction.RunOp.class, rig.mock.sent().get(0));
        assertFalse(run.physics());
        OpSpec.Paste paste = assertInstanceOf(OpSpec.Paste.class, run.op());
        assertEquals(new SourceRef.Clipboard(rig.mock.clipboards().current().orElseThrow().clipboardId()), paste.src());
        assertEquals(bounds.min(), paste.origin());
        assertEquals(Transform.IDENTITY, paste.t());
        assertEquals(new PasteOptions(true, false, false), paste.o(), "air written, no physics, no entities");
        assertEquals(List.of(GenerateTool.SENT_PATH), rig.noticeKeys());
        assertEquals(List.of("27"), rig.notices.get(0).args());
        assertEquals(2, rig.tool.path().size(), "the nodes stay for the next build");
        for (int i = 0; i < MockEditorSession.MIN_JOB_TICKS + 2; i++) rig.mock.tick();
        assertEquals("Road", rig.mock.history().undoLabel(), "one undo step, named after the tool");
        assertEquals(List.of(), rig.services.confirmations, "under the confirm threshold");
    }

    @Test
    void enterWithoutEnoughNodesOrOverUnloadedChunksRefuses() {
        Rig rig = new Rig();
        assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
        assertEquals(List.of(GenerateTool.NEEDS_NODES), rig.noticeKeys());
        rig.notices.clear();

        rig.world.setLoaded(0, 0, false);
        rig.click(top(-4, 3));
        rig.click(top(20, 3));
        rig.frame(top(20, 3));
        GenerateTool.Built built = rig.tool.preview().orElseThrow();
        assertEquals(16 * 3, built.unloaded().size(), "the columns in the unloaded chunk");
        assertTrue(built.source().contains(-4, GROUND, 3) && !built.source().contains(5, GROUND, 3),
                "the loaded part is previewed");
        RecordingDraw draw = new RecordingDraw();
        rig.tool.renderWorld(rig.view, draw);
        assertTrue(draw.boxes.contains(new Box(new BlockPos(0, GROUND, 2), new BlockPos(15, GROUND, 4))),
                "the unloaded chunk's part is outlined: " + draw.boxes);
        assertTrue(rig.tool.hints(rig.view).stream().anyMatch(h -> h.descriptionKey().equals("sculptory.hint.generate.unloaded")));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
        rig.frame(top(20, 3));
        assertEquals(List.of(GenerateTool.UNLOADED), rig.noticeKeys());
        assertTrue(rig.mock.calls().isEmpty(), "nothing was uploaded");

        // Straight between points reads no chunks, so it builds.
        rig.notices.clear();
        rig.set(GenerateSettings.HEIGHT, RoadKernel.HeightMode.STRAIGHT);
        rig.frame(top(20, 3));
        assertTrue(rig.tool.preview().orElseThrow().unloaded().isEmpty());
        assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
        rig.frame(top(20, 3));
        assertEquals(1, rig.mock.calls().size());
        assertEquals(List.of(GenerateTool.SENT_PATH), rig.noticeKeys());
    }

    /** The limits count the road's blocks, not its box: a long diagonal road in a vast box builds. */
    @Test
    void aSparseRoadInAVastBoxBuildsOnItsBlocks() {
        Rig rig = new Rig();
        rig.mock.setPermissions(new Permissions(Perm.mask(EnumSet.of(Perm.USE, Perm.CLIPBOARD, Perm.REGION)),
                new Limits(5_000, 5_000, 32, 20, 32L << 20, 2)));
        rig.set(GenerateSettings.WIDTH, 1);
        rig.set(GenerateSettings.HEIGHT, RoadKernel.HeightMode.STRAIGHT);
        rig.click(top(0, 0));
        rig.click(new WorldCursor(new BlockPos(700, 70, 700), WorldCursor.Face.UP, 700.5, 71, 700.5, false));
        rig.frame(top(0, 0));
        GenerateTool.Built built = rig.tool.preview().orElseThrow();
        assertTrue(built.cells() > 700 && built.cells() < 5_000, "cells " + built.cells());
        assertTrue(built.source().bounds().volume() > 5_000, "a box far over the limit");
        assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
        rig.frame(top(0, 0));
        assertEquals(1, rig.mock.calls().size(), "uploaded");
        assertEquals(List.of(GenerateTool.SENT_PATH), rig.noticeKeys());
    }

    /**
     * Building needs region (a generated clipboard may hold any block state, as a Fill places) and clipboard (the upload
     * and its paste): without either, Enter names the missing node and nothing is sent, and a hint says so meanwhile.
     */
    @Test
    void buildingNeedsRegionAndClipboardAndSaysWhichIsMissing() {
        for (Perm missing : List.of(Perm.REGION, Perm.CLIPBOARD)) {
            Rig rig = new Rig();
            EnumSet<Perm> nodes = EnumSet.of(Perm.USE, Perm.CLIPBOARD, Perm.REGION);
            nodes.remove(missing);
            rig.mock.setPermissions(new Permissions(Perm.mask(nodes), Limits.DEFAULTS));
            rig.click(top(0, 0));
            rig.click(top(6, 0));
            rig.frame(top(6, 0));
            assertTrue(rig.tool.hints(rig.view).stream().anyMatch(h -> h.descriptionKey()
                    .equals(GenerateTool.NEEDS_PERMISSION_HINT) && h.args().equals(List.of(missing.node()))),
                    "without " + missing + ": " + rig.tool.hints(rig.view));
            rig.notices.clear();
            assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
            rig.frame(top(6, 0));
            rig.services.runPending();
            assertEquals(List.of("sculptory.notice.needs_permission"), rig.noticeKeys(), "without " + missing);
            assertEquals(List.of(missing.node()), rig.notices.get(0).args());
            assertTrue(rig.mock.calls().isEmpty(), "nothing sent without " + missing);
        }
        Rig both = new Rig();
        both.mock.setPermissions(new Permissions(Perm.mask(EnumSet.of(Perm.USE, Perm.CLIPBOARD, Perm.REGION)),
                Limits.DEFAULTS));
        both.click(top(0, 0));
        both.click(top(6, 0));
        both.frame(top(6, 0));
        assertTrue(both.tool.hints(both.view).stream().noneMatch(h -> h.descriptionKey()
                .equals(GenerateTool.NEEDS_PERMISSION_HINT)));
        assertTrue(both.tool.onAction(both.view, EditorAction.COMMIT));
        both.frame(top(6, 0));
        assertEquals(1, both.mock.calls().size(), "built with both nodes");
    }

    @Test
    void aBlockWithABlockEntityIsRefusedBeforeAnythingIsGenerated() {
        Rig rig = new Rig();
        rig.set(GenerateSettings.BLOCK, BlockDescriptor.parse("minecraft:chest"));
        rig.click(top(0, 0));
        rig.click(top(6, 0));
        rig.frame(top(6, 0));
        assertTrue(rig.tool.preview().isEmpty());
        assertEquals(List.of(GenerateTool.BLOCK_ENTITY), rig.noticeKeys());
        assertTrue(rig.notices.get(0).args().get(0).startsWith("minecraft:chest"));
        Rig roof = new Rig();
        roof.set(GenerateSettings.KIND, GenerateSettings.Kind.ROOF);
        roof.set(GenerateSettings.FULL, BlockDescriptor.parse("minecraft:chest"));
        roof.ctx.setSelection(new Box(new BlockPos(0, 70, 0), new BlockPos(4, 70, 2)));
        roof.frame(top(0, 0));
        assertTrue(roof.tool.preview().isEmpty());
        assertEquals(List.of(GenerateTool.BLOCK_ENTITY), roof.noticeKeys());
    }

    @Test
    void enterWhileThePreviewIsBeingMadeBuildsWhenItLands() {
        Rig rig = new Rig();
        rig.services.runAtOnce = false;
        rig.click(top(0, 0));
        rig.click(top(6, 0));
        rig.frame(top(6, 0));
        assertTrue(rig.tool.previewRunning());
        assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
        assertTrue(rig.mock.calls().isEmpty(), "nothing sent while the preview is being made");
        assertTrue(rig.tool.previewRunning(), "the client thread did not wait for it");
        rig.services.runPending();
        rig.frame(top(6, 0));
        rig.services.runPending(); // the payload encoding
        rig.frame(top(6, 0));
        assertEquals(1, rig.mock.calls().size(), "built once the preview landed");
        assertEquals(List.of(GenerateTool.SENT_PATH), rig.noticeKeys());
    }

    @Test
    void aLargeBuildAsksFirstAndOneOverTheLimitIsRefused() {
        Rig rig = new Rig();
        rig.mock.setPermissions(new Permissions(Perm.mask(EnumSet.of(Perm.USE, Perm.CLIPBOARD, Perm.REGION)),
                new Limits(100, 100, 32, 20, 32L << 20, 2)));
        rig.click(top(0, 0));
        rig.click(top(40, 0));
        rig.frame(top(40, 0));
        assertTrue(rig.tool.preview().orElseThrow().tooLarge(), "over the clipboard limit the kernel stops");
        assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
        assertEquals(List.of("sculptory.notice.too_large"), rig.noticeKeys());
        assertTrue(rig.mock.calls().isEmpty());

        Rig big = new Rig();
        big.mock.setPermissions(new Permissions(Perm.mask(EnumSet.of(Perm.USE, Perm.CLIPBOARD, Perm.REGION)),
                new Limits(1_000_000, 1_000_000, 32, 20, 32L << 20, 2)));
        big.set(GenerateSettings.WIDTH, 32);
        big.set(GenerateSettings.FILL_BELOW, 8);
        big.set(GenerateSettings.CLEAR_ABOVE, 8);
        // A kilometre of road, Straight, on ground all the way (its fill below reaches down only where it meets ground).
        big.set(GenerateSettings.HEIGHT, RoadKernel.HeightMode.STRAIGHT);
        int grass = states.state("minecraft:grass_block");
        for (int x = -510; x <= 510; x++) {
            for (int z = -20; z <= 20; z++) {
                big.world.set(x, GROUND, z, grass);
                big.world.set(x, GROUND - 1, z, grass); // under the deck (which replaces the top): what the fill meets
            }
        }
        big.click(top(-500, 0));
        big.click(top(500, 0));
        big.frame(top(500, 0));
        GenerateTool.Built built = big.tool.preview().orElseThrow();
        long cells = built.cells();
        assertEquals(1000L * 32 * (1 + 8 + 8), cells, "the deck, its fill and its clearing all along");
        assertTrue(cells > GenerateTool.MAX_GHOST_CELLS || built.ghost() != null);
        assertTrue(big.tool.onAction(big.view, EditorAction.COMMIT));
        assertEquals(List.of(GenerateTool.OP_PATH + " " + cells), big.services.confirmations, "asked first");
        assertTrue(big.mock.calls().isEmpty(), "nothing sent before the confirmation");
        big.services.pendingConfirmation.run();
        big.frame(top(500, 0));
        assertEquals(1, big.mock.calls().size());
    }

    // ---------------------------------------------------------------- roofs

    @Test
    void aRoofNeedsASelectionAndFollowsIt() {
        Rig rig = new Rig();
        rig.set(GenerateSettings.KIND, GenerateSettings.Kind.ROOF);
        rig.frame(top(0, 0));
        assertTrue(rig.tool.preview().isEmpty());
        assertEquals(KeyHint.text("sculptory.hint.generate.roof_needs_selection"), rig.tool.hints(rig.view).get(0));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
        assertEquals(List.of(GenerateTool.NEEDS_SELECTION), rig.noticeKeys());
        rig.notices.clear();
        assertFalse(rig.tool.onPointer(rig.view, new PointerEvent(PointerEvent.Kind.PRESS, PointerEvent.LEFT, 0, 0, 0,
                top(0, 0))), "clicks add no nodes to a roof");

        rig.ctx.setSelection(new Box(new BlockPos(0, 70, 0), new BlockPos(4, 75, 2)));
        rig.frame(top(0, 0));
        GenerateTool.Built built = rig.tool.preview().orElseThrow();
        // A gable with a one-block overhang and gable walls over a 5×3 footprint (see RoofKernelTest).
        assertEquals(35 + 8, built.cells());
        assertEquals(new Box(new BlockPos(-1, 70, -1), new BlockPos(5, 72, 3)), built.source().bounds());
        assertEquals(states.state("minecraft:oak_stairs[facing=south,half=bottom,shape=straight]"),
                built.source().get(2, 70, -1), "the eaves are stairs facing the ridge");
        assertEquals(states.state("minecraft:oak_planks"), built.source().get(2, 72, 1), "the ridge is the full block");
        List<KeyHint> hints = rig.tool.hints(rig.view);
        assertEquals(KeyHint.text("sculptory.hint.generate.roof_eaves"), hints.get(0));
        assertTrue(hints.contains(new KeyHint("Enter", "sculptory.hint.generate.build_roof", List.of("43"))));

        rig.set(GenerateSettings.STYLE, RoofKernel.Style.HIP);
        rig.set(GenerateSettings.OVERHANG, 0);
        rig.set(GenerateSettings.GABLE_WALLS, false);
        rig.frame(top(0, 0));
        assertEquals(15, rig.tool.preview().orElseThrow().cells(), "a hip roof: no walls");
        assertFalse(rig.values().isVisible(GenerateSettings.GABLE_WALLS), "a hip roof has no gable walls");
        assertFalse(rig.values().isVisible(GenerateSettings.RIDGE));
        assertFalse(rig.values().isVisible(GenerateSettings.WIDTH), "the path's settings hide");
        rig.set(GenerateSettings.STYLE, RoofKernel.Style.SHED);
        assertTrue(rig.values().isVisible(GenerateSettings.LOW_SIDE));

        // Moving the selection moves the roof.
        rig.ctx.setSelection(new Box(new BlockPos(10, 70, 10), new BlockPos(14, 70, 12)));
        rig.frame(top(0, 0));
        assertEquals(new BlockPos(10, 70, 10), rig.tool.preview().orElseThrow().source().bounds().min());

        assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
        rig.frame(top(0, 0));
        assertEquals(1, rig.mock.calls().size());
        ToolAction.RunOp run = assertInstanceOf(ToolAction.RunOp.class, rig.mock.sent().get(0));
        assertEquals(new BlockPos(10, 70, 10), ((OpSpec.Paste) run.op()).origin());
        assertEquals(List.of(GenerateTool.SENT_ROOF), rig.noticeKeys());
    }

    @Test
    void pickingTheStairsFillsTheSlabAndFullBlockWhenTheyDerive() {
        Rig rig = new Rig();
        rig.set(GenerateSettings.KIND, GenerateSettings.Kind.ROOF);
        rig.set(GenerateSettings.STAIRS, BlockDescriptor.parse("minecraft:stone_brick_stairs"));
        assertEquals(BlockDescriptor.parse("minecraft:stone_brick_slab"), rig.setting(GenerateSettings.SLAB));
        assertEquals(BlockDescriptor.parse("minecraft:stone_bricks"), rig.setting(GenerateSettings.FULL));
        rig.set(GenerateSettings.STAIRS, BlockDescriptor.parse("minecraft:oak_stairs"));
        assertEquals(BlockDescriptor.parse("minecraft:oak_slab"), rig.setting(GenerateSettings.SLAB));
        assertEquals(BlockDescriptor.parse("minecraft:oak_planks"), rig.setting(GenerateSettings.FULL));
        rig.set(GenerateSettings.STAIRS, BlockDescriptor.parse("testmod:widget"));
        assertEquals(BlockDescriptor.parse("minecraft:oak_slab"), rig.setting(GenerateSettings.SLAB),
                "a stairs block named otherwise derives nothing: the builder picks");
        assertEquals(BlockDescriptor.parse("minecraft:oak_planks"), rig.setting(GenerateSettings.FULL));
        RoofMaterials.Derived none = RoofMaterials.derive(states, BlockDescriptor.parse("modded:fancy_stairs"));
        assertTrue(none.slab().isEmpty() && none.full().isEmpty(), "unknown blocks derive nothing");

        // An unusable material says so, once per change, and previews nothing.
        rig.ctx.setSelection(new Box(new BlockPos(0, 70, 0), new BlockPos(4, 70, 2)));
        rig.frame(top(0, 0));
        assertTrue(rig.tool.preview().isEmpty());
        assertEquals(List.of("sculptory.notice.unknown_block"), rig.noticeKeys());
        assertTrue(rig.notices.get(0).args().get(0).startsWith("testmod:widget"));
    }

    // ---------------------------------------------------------------- settings, hints, text

    @Test
    void presetsRoundTripEverySetting() {
        Rig rig = new Rig();
        rig.set(GenerateSettings.KIND, GenerateSettings.Kind.ROOF);
        rig.set(GenerateSettings.WIDTH, 7);
        rig.set(GenerateSettings.BORDER, true);
        rig.set(GenerateSettings.HEIGHT, RoadKernel.HeightMode.STRAIGHT);
        rig.set(GenerateSettings.STYLE, RoofKernel.Style.SHED);
        rig.set(GenerateSettings.LOW_SIDE, RoofKernel.Side.EAST);
        rig.set(GenerateSettings.PITCH, RoofKernel.Pitch.GENTLE);
        rig.set(GenerateSettings.INSIDE, RoofKernel.Inside.HOLLOW);
        rig.set(GenerateSettings.STAIRS, BlockDescriptor.parse("minecraft:stone_brick_stairs"));
        SettingsValues values = rig.values();
        SettingsValues back = SettingsValues.decode(GenerateSettings.SCHEMA, values.encode());
        assertEquals(values, back);
        assertEquals(BlockDescriptor.parse("minecraft:stone_bricks"), back.get(GenerateSettings.FULL));
        assertEquals(SettingsValues.defaults(GenerateSettings.SCHEMA), SettingsValues.decode(GenerateSettings.SCHEMA,
                SettingsValues.defaults(GenerateSettings.SCHEMA).encode()));
    }

    @Test
    void theHintLineSaysWhatTheKeysDo() {
        Rig rig = new Rig();
        List<KeyHint> empty = rig.tool.hints(rig.view);
        assertEquals(new KeyHint(GenerateTool.CLICK, "sculptory.hint.generate.add_node"), empty.get(0));
        assertTrue(empty.contains(new KeyHint("Ctrl+Scroll", "sculptory.hint.generate.width")));
        rig.click(top(0, 0));
        rig.click(top(4, 0));
        rig.frame(top(4, 0));
        List<KeyHint> hints = rig.tool.hints(rig.view);
        assertEquals(new KeyHint(GenerateTool.CLICK, "sculptory.hint.generate.add_or_select"), hints.get(0));
        assertTrue(hints.contains(new KeyHint("Delete/Backspace", "sculptory.hint.generate.remove_node")), hints.toString());
        assertTrue(hints.contains(new KeyHint("Enter", "sculptory.hint.generate.build_path", List.of("15"))), hints.toString());
        assertTrue(hints.contains(new KeyHint("Esc", "sculptory.hint.generate.clear")));
        rig.services.ray = new Ray(4.5, GROUND + 17.5, 0.5, 0, -1, 0);
        rig.press(top(4, 0));
        rig.drag(top(5, 0));
        assertEquals(List.of(new KeyHint("Esc", "sculptory.hint.cancel_drag"),
                new KeyHint("Alt+Scroll", AirPoints.HINT, List.of("17"))), rig.tool.hints(rig.view),
                "the dragged node's distance");
        rig.release(top(5, 0));
    }

    @Test
    void theCursorDrawsTheNodesAndTheCentreLine() {
        Rig rig = new Rig();
        rig.click(top(0, 0));
        rig.click(top(6, 0));
        rig.frame(top(6, 0));
        rig.press(top(6, 0));
        rig.release(top(6, 0));
        RecordingDraw draw = new RecordingDraw();
        rig.tool.renderWorld(rig.view, draw);
        assertEquals(List.of(Box.of(new BlockPos(0, GROUND, 0)), Box.of(new BlockPos(6, GROUND, 0))), draw.fills,
                "each node is a translucent cube");
        assertTrue(draw.lines.stream().anyMatch(line -> line.color() == GenerateTool.SELECTED_COLOUR),
                "the selected one in its own colour");
        assertTrue(draw.lines.size() >= 10, "the spline is a polyline on the terrain: " + draw.lines.size());
        List<RecordingDraw.Line> centre = draw.lines.stream().filter(line -> line.color() == GenerateTool.PATH_COLOUR
                && line.y1() == GROUND + 1.05).toList();
        assertTrue(centre.size() >= 10, "the spline is a polyline on the terrain: " + centre.size());
    }

    /**
     * The road replaces the ground, so its ghost is inside the terrain: the footprint is drawn on top of it (a tint
     * per column at the road's top face, an outline around the footprint), the centre line above that.
     */
    @Test
    void theRoadsFootprintIsDrawnOnTheGroundUnderTheCentreLine() {
        Rig rig = new Rig();
        rig.click(top(0, 0));
        rig.click(top(6, 0));
        rig.frame(top(6, 0));
        GenerateTool.Built built = rig.tool.preview().orElseThrow();
        RoadFootprint footprint = built.footprint();
        assertNotNull(footprint, "a small road has a footprint");
        assertTrue(footprint.columns() > 6 * 3, "wider than the centre line: " + footprint.columns());
        assertEquals(GROUND, footprint.top(3, 0), "the road's top replaces the grass at ground level");
        RecordingDraw draw = new RecordingDraw();
        rig.tool.renderWorld(rig.view, draw);
        assertEquals(footprint.columns(), draw.quads.size(), "one tint per footprint column");
        assertTrue(draw.quads.stream().allMatch(q -> q.y1() == GROUND + 1 + RoadFootprint.LIFT), "on the top faces");
        long edges = draw.lines.stream().filter(line -> line.y1() == GROUND + 1 + RoadFootprint.LIFT).count();
        assertTrue(edges >= 2 * (2 * 7 + 2 * 3), "the footprint's outline, through terrain and in view: " + edges);
        assertTrue(draw.lines.stream().anyMatch(line -> line.y1() == GROUND + 1.05 && line.color() == GenerateTool.PATH_COLOUR),
                "the centre line stays");
        assertFalse(draw.boxes.contains(built.source().bounds()), "no bounds box while the footprint shows");

        assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
        rig.frame(top(6, 0));
        ToolAction.RunOp run = assertInstanceOf(ToolAction.RunOp.class, rig.mock.sent().get(rig.mock.sent().size() - 1));
        assertEquals(OpLabel.ROAD, run.label(), "the paste is named after the tool");
        rig.mock.finishJobs();
        assertEquals("Road", rig.mock.history().undoLabel());
    }

    /** A roof stands in the air: its bounds are outlined, faintly through the terrain and fully in view. */
    @Test
    void theRoofGhostIsOutlined() {
        Rig rig = new Rig();
        rig.set(GenerateSettings.KIND, GenerateSettings.Kind.ROOF);
        rig.ctx.setSelection(new Box(new BlockPos(0, 70, 0), new BlockPos(4, 75, 2)));
        rig.frame(top(0, 0));
        GenerateTool.Built built = rig.tool.preview().orElseThrow();
        assertNotNull(built.ghost(), "the roof has a ghost");
        assertNull(built.footprint(), "and no footprint");
        RecordingDraw draw = new RecordingDraw();
        rig.tool.renderWorld(rig.view, draw);
        Box bounds = built.source().bounds();
        assertEquals(List.of(bounds, bounds), draw.boxes, "outlined twice: through the terrain, then depth-tested");
        assertEquals(List.of(bounds), draw.seeThroughBoxes);
        assertTrue(draw.quads.isEmpty() && draw.lines.isEmpty(), "no footprint or centre line for a roof");

        assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
        rig.frame(top(0, 0));
        ToolAction.RunOp run = assertInstanceOf(ToolAction.RunOp.class, rig.mock.sent().get(rig.mock.sent().size() - 1));
        assertEquals(OpLabel.ROOF, run.label());
    }

    @Test
    void theEnglishTextCoversTheTool() throws IOException {
        JsonObject lang;
        try (InputStream in = getClass().getResourceAsStream("/assets/sculptory/lang/en_us.json")) {
            assertNotNull(in);
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        List<String> keys = new ArrayList<>(List.of("sculptory.tool.generate", "sculptory.tool.generate.tooltip",
                "sculptory.key.tool_11", "sculptory.key.remove_node", GenerateTool.OP_PATH, GenerateTool.OP_ROOF,
                GenerateTool.NEEDS_NODES, GenerateTool.NEEDS_SELECTION, GenerateTool.UNLOADED, GenerateTool.NOTHING,
                GenerateTool.SENT_PATH, GenerateTool.SENT_ROOF, "sculptory.hint.generate.add_node",
                "sculptory.hint.generate.add_or_select", "sculptory.hint.points.gizmo",
                "sculptory.hint.generate.remove_node", "sculptory.hint.generate.build_path",
                "sculptory.hint.generate.build_roof", "sculptory.hint.generate.width", "sculptory.hint.generate.clear",
                "sculptory.hint.generate.unloaded", "sculptory.hint.generate.too_large",
                "sculptory.hint.generate.roof_needs_selection", "sculptory.hint.generate.roof_eaves",
                "sculptory.setting.generate.path_section", "sculptory.setting.generate.roof_section"));
        for (SettingDef<?> def : GenerateSettings.SCHEMA.defs()) {
            keys.add(def.labelKey());
            if (def instanceof SettingDef.Enum<?> choice) {
                for (Object option : choice.type().getEnumConstants()) {
                    keys.add(def.labelKey() + "." + ((Enum<?>) option).name().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        for (String key : keys) assertTrue(lang.has(key), key);
    }

    // ---------------------------------------------------------------- the Heightmap import

    /**
     * Heightmap: it needs a selection; Min Y and Max Y start at the selection's bottom and top (and keep what the player
     * types until the selection changes); the preview is the kernel's import of the stretched picture; Turn and Invert
     * change it; Enter uploads it and pastes it as one job labelled Heightmap; a missing picture is said in a toast.
     */
    @Test
    void theHeightmapImportStretchesThePictureOverTheSelection() {
        Rig rig = new Rig();
        rig.set(GenerateSettings.KIND, GenerateSettings.Kind.HEIGHTMAP);
        rig.frame(top(0, 0));
        assertTrue(rig.tool.preview().isEmpty());
        assertEquals(KeyHint.text("sculptory.hint.generate.heightmap_needs_selection"), rig.tool.hints(rig.view).get(0));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
        assertEquals(List.of(GenerateTool.NEEDS_SELECTION), rig.noticeKeys());
        rig.notices.clear();
        assertFalse(rig.scroll(1, Modifiers.CONTROL), "Ctrl+Scroll sizes nothing here");

        Box selection = new Box(new BlockPos(0, 60, 0), new BlockPos(11, 72, 7));
        rig.world.set(5, 64, 3, states.state("minecraft:chest"));
        rig.ctx.setSelection(selection);
        rig.frame(top(0, 0));
        rig.frame(top(0, 0));
        assertEquals(60, rig.setting(GenerateSettings.HEIGHTMAP_MIN_Y), "Min Y from the selection's bottom");
        assertEquals(72, rig.setting(GenerateSettings.HEIGHTMAP_MAX_Y), "Max Y from its top");
        GenerateTool.Built built = rig.tool.preview().orElseThrow();
        assertEquals(expected(rig, selection, 60, 72, 0, false), cellsOf(built));
        assertTrue(built.cells() > 0);
        assertEquals(states.air(), built.source().get(0, 61, 0), "a black corner is cut to Min Y");
        assertEquals(states.state("minecraft:grass_block"), built.source().get(0, 60, 0));

        rig.set(GenerateSettings.HEIGHTMAP_MAX_Y, 80);
        rig.set(GenerateSettings.HEIGHTMAP_TURN, GenerateSettings.Turn.QUARTER);
        rig.set(GenerateSettings.HEIGHTMAP_INVERT, true);
        rig.frame(top(0, 0));
        assertEquals(80, rig.setting(GenerateSettings.HEIGHTMAP_MAX_Y), "a typed Max Y stays while the selection does");
        assertEquals(expected(rig, selection, 60, 80, 1, true), cellsOf(rig.tool.preview().orElseThrow()));
        assertTrue(rig.tool.hints(rig.view).stream().anyMatch(h -> h.descriptionKey().equals("sculptory.hint.generate.build_heightmap")));

        assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
        rig.frame(top(0, 0));
        assertEquals(1, rig.mock.calls().size());
        ToolAction.RunOp run = assertInstanceOf(ToolAction.RunOp.class, rig.mock.sent().get(0));
        assertEquals(OpLabel.HEIGHTMAP, run.label());
        OpSpec.Paste paste = assertInstanceOf(OpSpec.Paste.class, run.op());
        assertEquals(rig.tool.preview().orElseThrow().source().bounds().min(), paste.origin());
        assertEquals(new PasteOptions(true, false, false), paste.o());
        assertEquals(List.of(GenerateTool.SENT_HEIGHTMAP, GenerateTool.KEPT_BLOCK_ENTITIES), rig.noticeKeys(),
                "the chest in the hill is left as it is, and the toast says so");
        assertEquals(List.of("1"), rig.notices.get(1).args());
        assertEquals(-1, rig.tool.preview().orElseThrow().source().get(5, 64, 3), "the chest's cell is not in the upload");
        for (int i = 0; i < MockEditorSession.MIN_JOB_TICKS + 2; i++) rig.mock.tick();
        assertEquals("Heightmap", rig.mock.history().undoLabel(), "one undo step named Heightmap");

        // A new selection resets the range; a missing picture says so.
        rig.ctx.setSelection(new Box(new BlockPos(20, 55, 20), new BlockPos(23, 66, 23)));
        rig.frame(top(0, 0));
        assertEquals(55, rig.setting(GenerateSettings.HEIGHTMAP_MIN_Y));
        assertEquals(66, rig.setting(GenerateSettings.HEIGHTMAP_MAX_Y));
        rig.notices.clear();
        rig.set(GenerateSettings.HEIGHTMAP_IMAGE, HeightmapRef.file("not_here.png"));
        rig.frame(top(0, 0));
        assertEquals(List.of("sculptory.notice.heightmap_missing"), rig.noticeKeys());
    }

    /** The cells the kernel makes from the built-in hill over {@code box}, read from the rig's world. */
    private java.util.Map<BlockPos, Integer> expected(Rig rig, Box box, int minY, int maxY, int turns, boolean invert) {
        HeightImage hill = HeightmapLibrary.builtInOnly().image(HeightmapLibrary.DEFAULT).orElseThrow();
        HeightmapAreaKernel.Spec spec = new HeightmapAreaKernel.Spec(box, minY, maxY,
                hill.stretch(box.sizeX(), box.sizeZ(), turns, invert));
        int[] range = HeightmapAreaKernel.range(spec, rig.world.bottomY(), rig.world.topYExclusive());
        GeneratedSource source = HeightmapAreaKernel.generate(spec,
                (x, z) -> HeightmapAreaKernel.capture(rig.world, x, z, range[0], range[1], minY), states,
                rig.world.bottomY(), rig.world.topYExclusive(), 1 << 20).source();
        java.util.Map<BlockPos, Integer> cells = new java.util.HashMap<>();
        source.forEach((x, y, z, state) -> cells.put(new BlockPos(x, y, z), state));
        assertFalse(cells.isEmpty());
        return cells;
    }

    private static java.util.Map<BlockPos, Integer> cellsOf(GenerateTool.Built built) {
        java.util.Map<BlockPos, Integer> cells = new java.util.HashMap<>();
        built.source().forEach((x, y, z, state) -> cells.put(new BlockPos(x, y, z), state));
        return cells;
    }

    // ---------------------------------------------------------------- fixtures

    private static BlockPos origin(List<GhostPlacement> placements) {
        GhostPlacement placement = placements.get(0);
        return new BlockPos(placement.originX(), placement.originY(), placement.originZ());
    }

    /** The Generate tool, active in an editor context over flat ground and a mock session. */
    private final class Rig {
        final FakeWorld world = flatWorld();
        final MockEditorSession mock = new MockEditorSession();
        final List<Notice> notices = new ArrayList<>();
        final FakeServices services = new FakeServices();
        final EditorContext ctx;
        final GenerateTool tool;
        final ToolContext view;
        long now = 1_000 * MS;

        Rig() {
            EditorBackend backend = new EditorBackend() {
                @Override
                public Optional<EditorSession> session() {
                    return Optional.of(mock);
                }

                @Override
                public StateSpace states() {
                    return states;
                }

                @Override
                public WorldReader world() {
                    return world;
                }
            };
            ctx = new EditorContext(() -> backend, notices::add);
            tool = new GenerateTool(services);
            ctx.tools().register(tool);
            view = ctx.contextFor(ToolId.GENERATE);
            assertTrue(ctx.tools().activate(ToolId.GENERATE, view));
        }

        void frame(WorldCursor cursor) {
            now += 16 * MS;
            tool.frame(view, new FrameInfo(now, 0f, 0, 0, cursor));
        }

        void press(WorldCursor cursor) {
            tool.onPointer(view, new PointerEvent(PointerEvent.Kind.PRESS, PointerEvent.LEFT, 0, 0, 0, cursor));
        }

        void drag(WorldCursor cursor) {
            tool.onPointer(view, new PointerEvent(PointerEvent.Kind.DRAG, PointerEvent.LEFT, 0, 0, 0, cursor));
        }

        void release(WorldCursor cursor) {
            tool.onPointer(view, new PointerEvent(PointerEvent.Kind.RELEASE, PointerEvent.LEFT, 0, 0, 0, cursor));
        }

        /** A click: press and release on the same spot. */
        void click(WorldCursor cursor) {
            press(cursor);
            release(cursor);
        }

        boolean scroll(double amount, int modifiers) {
            return tool.onScroll(view, new ScrollEvent(amount, modifiers));
        }

        <T> void set(SettingDef<T> def, T value) {
            ctx.updateSettings(ToolId.GENERATE, ctx.settings(ToolId.GENERATE).with(def, value));
        }

        SettingsValues values() {
            return ctx.settings(ToolId.GENERATE);
        }

        <T> T setting(SettingDef<T> def) {
            return values().get(def);
        }

        List<String> noticeKeys() {
            return notices.stream().map(Notice::key).toList();
        }
    }

    /** Grass on stone at {@link #GROUND}, x and z within ±48. */
    private FakeWorld flatWorld() {
        FakeWorld world = new FakeWorld(states);
        int stone = states.state("minecraft:stone");
        int grass = states.state("minecraft:grass_block");
        for (int x = -48; x <= 48; x++) {
            for (int z = -48; z <= 48; z++) {
                for (int y = 50; y <= GROUND; y++) world.set(x, y, z, y == GROUND ? grass : stone);
            }
        }
        return world;
    }

    private static final class FakeServices implements GenerateTool.Services {
        final EditorKeymap keymap = EditorKeymap.defaults();
        List<GhostPlacement> shown = List.of();
        final List<GhostVolume> released = new ArrayList<>();
        final List<String> confirmations = new ArrayList<>();
        Runnable pendingConfirmation;
        boolean runAtOnce = true;
        final List<Runnable> pending = new ArrayList<>();
        /** The cursor ray (none by default: picks go by the clicked column and nothing drags). */
        Ray ray;
        /** World to screen and the camera (none by default: no gizmo, no drawn path to hover). */
        dev.sculptory.mc.client.editor.world.ScreenProjector projector;
        double[] eye;

        @Override
        public Optional<dev.sculptory.mc.client.editor.world.ScreenProjector> projector() {
            return Optional.ofNullable(projector);
        }

        @Override
        public Optional<double[]> eye() {
            return Optional.ofNullable(eye);
        }

        @Override
        public Optional<Ray> cursorRay() {
            return Optional.ofNullable(ray);
        }

        @Override
        public void showGhosts(List<GhostPlacement> placements) {
            shown = List.copyOf(placements);
        }

        @Override
        public void releaseGhost(GhostVolume volume) {
            released.add(volume);
        }

        @Override
        public Executor background() {
            return task -> {
                if (runAtOnce) task.run();
                else pending.add(task);
            };
        }

        void runPending() {
            List<Runnable> tasks = new ArrayList<>(pending);
            pending.clear();
            tasks.forEach(Runnable::run);
        }

        @Override
        public String keyLabel(KeyAction action) {
            return keymap.display(action);
        }

        @Override
        public void confirm(String opNameKey, long blocks, Runnable onConfirm) {
            confirmations.add(opNameKey + " " + blocks);
            pendingConfirmation = onConfirm;
        }
    }

    private static final class RecordingDraw implements WorldDraw {
        record Line(double x1, double y1, double z1, double x2, double y2, double z2, int color) {}

        final List<Line> lines = new ArrayList<>();
        final List<Box> boxes = new ArrayList<>();
        final List<Box> fills = new ArrayList<>();
        /** The surface tints, as lines from (minX, y, minZ) to (maxX, y, maxZ). */
        final List<Line> quads = new ArrayList<>();
        /** The boxes outlined through the terrain. */
        final List<Box> seeThroughBoxes = new ArrayList<>();
        boolean seeThrough;

        @Override
        public void boxOutline(Box box, int argb) {
            boxes.add(box);
            if (seeThrough) seeThroughBoxes.add(box);
        }

        @Override
        public void boxFill(Box box, int argb) {
            fills.add(box);
        }

        @Override
        public void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb) {
            lines.add(new Line(x1, y1, z1, x2, y2, z2, argb));
        }

        @Override
        public void ring(double centerX, double y, double centerZ, double radius, int argb) {}

        @Override
        public void seeThrough(boolean enabled) {
            seeThrough = enabled;
        }

        @Override
        public void surfaceQuad(double minX, double minZ, double maxX, double maxZ, double y, int argb) {
            quads.add(new Line(minX, y, minZ, maxX, y, maxZ, argb));
        }

        @Override
        public void shapeOutlines(List<? extends Region> regions, int argb, int copyArgb) {}
    }
}
