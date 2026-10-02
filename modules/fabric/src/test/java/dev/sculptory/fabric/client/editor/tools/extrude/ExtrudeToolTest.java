package dev.sculptory.fabric.client.editor.tools.extrude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.EditorBackend;
import dev.sculptory.fabric.client.editor.EditorContext;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.hud.HelpSheet;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.input.KeyChord;
import dev.sculptory.fabric.client.editor.mock.MockEditorSession;
import dev.sculptory.fabric.client.editor.render.ghost.GhostPlacement;
import dev.sculptory.fabric.client.editor.render.ghost.GhostVolume;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.FrameInfo;
import dev.sculptory.fabric.client.editor.tool.KeyHint;
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.Tool;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.ToolDescriptor;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.ToolRegistry;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.tool.WorldDraw;
import dev.sculptory.fabric.client.editor.tools.EditorToolSet;
import dev.sculptory.fabric.client.editor.tools.PlaceholderTool;
import dev.sculptory.fabric.client.editor.tools.brush.SymmetryCentre;
import dev.sculptory.fabric.client.editor.tools.select.MagicSelect;
import dev.sculptory.fabric.client.editor.tools.select.SelectionActions;
import dev.sculptory.fabric.client.editor.world.Ray;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Permissions;
import dev.sculptory.fabric.client.session.ToolAction;
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
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

/**
 * The Extrude tool: slot and key, the hovered face, drags out (a stack) and in (an erase), a selection's side, smears
 * (a move leaving air), Esc, sizes, confirms and limits, symmetry, presets and text.
 */
class ExtrudeToolTest {
    private static final long MS = 1_000_000L;
    /** The stone floor: x and z 0-9 at y 60, air above and below. */
    private static final Box FLOOR = new Box(new BlockPos(0, 60, 0), new BlockPos(9, 60, 9));

    private final FakeStateSpace states = new FakeStateSpace();
    private final int stone = states.state("minecraft:stone");

    /** A hit on the top face of the floor block at (x, z). */
    private static WorldCursor top(int x, int z) {
        return new WorldCursor(new BlockPos(x, 60, z), WorldCursor.Face.UP, x + 0.5, 61, z + 0.5, false);
    }

    /** The ray from {@code from} through {@code at}. */
    private static Ray aimed(double fromX, double fromY, double fromZ, double atX, double atY, double atZ) {
        return new Ray(fromX, fromY, fromZ, atX - fromX, atY - fromY, atZ - fromZ);
    }

    /** A ray from a camera south-west and above, aimed at a point on the floor's centre column. */
    private static Ray atHeight(double y) {
        return aimed(5.5, 75, -12, 5.5, y, 5.5);
    }

    // ---------------------------------------------------------------- registration

    @Test
    void theToolIsPaletteSlotTwelveOnKeyEqual() {
        ToolRegistry registry = new ToolRegistry();
        EditorToolSet.register(registry, new PlaceholderTool(new ToolDescriptor(ToolId.SELECT, "sculptory.tool.select",
                "minecraft:stone", Perm.REGION), "sculptory.hint.select.drag"));
        assertEquals(15, registry.paletteOrder().size(), "the Fluid tool follows in slot 13, Tinker in 14, Weather in 15");
        assertEquals(15, ToolRegistry.PALETTE_SLOTS);
        Tool extrude = registry.slot(12).orElseThrow();
        assertInstanceOf(ExtrudeTool.class, extrude);
        assertEquals(ToolId.EXTRUDE, extrude.descriptor().id());
        assertEquals(Perm.REGION, extrude.descriptor().permission(), "greyed out without the region node");
        assertEquals("sculptory.tool.extrude", extrude.descriptor().nameKey());
        assertEquals(KeyAction.TOOL_12, KeyAction.toolSlot(12));
        assertEquals(12, KeyAction.TOOL_12.toolSlot());
        assertEquals(0, KeyAction.TOOL_SIZE.toolSlot());
        assertEquals(List.of(KeyChord.parse("equal")), KeyAction.TOOL_12.defaultChords());
        assertEquals("=", EditorKeymap.defaults().display(KeyAction.TOOL_12));
        assertEquals("1–9, 0, -, =, [", HelpSheet.toolKeys(EditorKeymap.defaults()));
        assertEquals(Optional.of(KeyAction.TOOL_12), EditorKeymap.defaults().match(KeyChord.parse("equal")));
    }

    // ---------------------------------------------------------------- hover

    @Test
    void hoveringLightsUpTheConnectedFlatFaceUnderTheCursor() {
        Rig rig = new Rig();
        rig.frame(top(3, 3));
        ExtrudeTool.Face face = rig.tool.hoveredFace().orElseThrow();
        assertEquals(100, face.count());
        assertEquals(FLOOR, face.bounds());
        assertFalse(face.cutAtLimit());
        RecordingDraw draw = new RecordingDraw();
        rig.tool.renderWorld(rig.view, draw);
        assertEquals(List.of(face.region()), draw.regions, "the face's exact outline");
        assertEquals(ExtrudeTool.FACE_COLOUR, draw.outlineArgb);
        rig.frame(top(4, 4));
        assertEquals(face.region(), rig.tool.hoveredFace().orElseThrow().region(), "another block of the same face: found again");
        List<KeyHint> hints = rig.tool.hints(rig.view);
        assertEquals(KeyHint.text("sculptory.hint.extrude.face", "100"), hints.get(0));
        assertTrue(hints.contains(new KeyHint("LMB drag", "sculptory.hint.extrude.drag")));
        assertTrue(hints.contains(new KeyHint("Alt+drag", "sculptory.hint.extrude.smear")));
        assertFalse(hints.stream().anyMatch(hint -> hint.keys().equals("Ctrl+drag")), "no selection to extrude a side of");

        // Air, a miss, or a covered block: no face.
        rig.frame(new WorldCursor(new BlockPos(3, 61, 3), WorldCursor.Face.UP, 3.5, 62, 3.5, false));
        assertTrue(rig.tool.hoveredFace().isEmpty());
        assertEquals(KeyHint.text("sculptory.hint.extrude.aim"), rig.tool.hints(rig.view).get(0));
        rig.frame(WorldCursor.miss(0, 0, 0));
        assertTrue(rig.tool.hoveredFace().isEmpty());
        RecordingDraw nothing = new RecordingDraw();
        rig.tool.renderWorld(rig.view, nothing);
        assertTrue(nothing.regions.isEmpty());
    }

    @Test
    void theSettingsShapeTheFaceAndALargeOneIsFoundOverFrames() {
        Rig rig = new Rig();
        rig.world.fill(new Box(new BlockPos(3, 60, 3), new BlockPos(5, 60, 5)), states.state("minecraft:dirt"));
        rig.frame(top(0, 0));
        assertEquals(91, rig.tool.hoveredFace().orElseThrow().count());
        rig.set(ExtrudeSettings.MATCH, MagicSelect.Match.ANY_BLOCK);
        rig.frame(top(0, 0));
        assertEquals(100, rig.tool.hoveredFace().orElseThrow().count(), "the settings change finds the face again");
        rig.set(ExtrudeSettings.SIZE, 10);
        rig.frame(top(0, 0));
        ExtrudeTool.Face cut = rig.tool.hoveredFace().orElseThrow();
        assertEquals(10, cut.count());
        assertTrue(cut.cutAtLimit());
        assertEquals(List.of(ExtrudeTool.FACE_LIMIT), rig.noticeKeys());
        assertEquals(KeyHint.text("sculptory.hint.extrude.face_cut", "10"), rig.tool.hints(rig.view).get(0));
        rig.frame(top(1, 1));
        assertEquals(List.of(ExtrudeTool.FACE_LIMIT), rig.noticeKeys(), "the toast comes once");

        // A large face spreads over frames: 2 ms of a fake clock ticking 1 ms per read runs two batches per frame.
        Rig big = new Rig();
        big.world.fill(new Box(new BlockPos(-100, 60, -100), new BlockPos(99, 60, 99)), stone);
        big.set(ExtrudeSettings.SIZE, 262_144);
        big.frame(top(0, 0));
        assertTrue(big.tool.searching(), "40,000 cells take more than one frame");
        assertTrue(big.tool.hoveredFace().isEmpty());
        assertEquals(KeyHint.text("sculptory.hint.extrude.finding"), big.tool.hints(big.view).get(0));
        int frames = 0;
        while (big.tool.searching()) {
            big.frame(top(0, 0));
            if (++frames > 10_000) throw new AssertionError("never found");
        }
        assertTrue(frames > 2, "frames: " + frames);
        assertEquals(40_000, big.tool.hoveredFace().orElseThrow().count());
    }

    @Test
    void unloadedChunksStopTheFaceWithOneToast() {
        Rig rig = new Rig();
        rig.world.fill(new Box(new BlockPos(0, 60, 0), new BlockPos(31, 60, 15)), stone);
        rig.world.setLoaded(1, 0, false);
        rig.frame(top(3, 3));
        assertEquals(256, rig.tool.hoveredFace().orElseThrow().count());
        assertTrue(rig.tool.hoveredFace().orElseThrow().hitUnloaded());
        rig.frame(top(4, 4));
        rig.frame(top(3, 3));
        assertEquals(List.of(ExtrudeTool.FACE_UNLOADED), rig.noticeKeys());
    }

    // ---------------------------------------------------------------- drags

    @Test
    void draggingOutStacksTheFaceByTheLayersDragged() {
        Rig rig = new Rig();
        rig.ray = atHeight(61);
        rig.frame(top(5, 5));
        rig.press(top(5, 5), 0);
        assertEquals(Optional.of(ExtrudeTool.DragKind.FACE), rig.tool.dragKind());
        assertTrue(rig.ctx.pointerCapture());
        rig.ray = atHeight(64);
        rig.drag(top(5, 5), 0);
        assertEquals(3, rig.tool.dragLayers());
        assertEquals(new Box(new BlockPos(0, 61, 0), new BlockPos(9, 63, 9)), rig.tool.preview().orElseThrow());
        assertEquals(KeyHint.text("sculptory.hint.extrude.extruding", "3", "300"), rig.tool.hints(rig.view).get(0));
        assertTrue(rig.tool.hints(rig.view).contains(new KeyHint("Esc", "sculptory.hint.cancel_drag")));
        // The ghost: three copies of the floor, placed at the box's corner.
        assertEquals(1, rig.services.shown.size());
        GhostPlacement ghost = rig.services.shown.get(0);
        assertEquals(new BlockPos(0, 61, 0), new BlockPos(ghost.originX(), ghost.originY(), ghost.originZ()));
        assertEquals(300, ghost.volume().blockCount());
        assertEquals(0, ghost.volume().eraseCount());
        RecordingDraw draw = new RecordingDraw();
        rig.tool.renderWorld(rig.view, draw);
        assertTrue(draw.boxes.isEmpty(), "a ghost stands in for the frame");
        assertTrue(rig.session.sent().isEmpty(), "nothing goes out before the release");

        rig.release(top(5, 5), 0);
        Region.Cells face = new Region.Cells(CellSet.of(new Region.Cuboid(FLOOR), 1000));
        assertEquals(List.of(new OpSpec.Stack(face, 0, 1, 0, 3, EntityFilter.NONE)), rig.sentOps());
        assertTrue(rig.tool.dragKind().isEmpty());
        assertFalse(rig.ctx.pointerCapture());
        assertTrue(rig.services.shown.isEmpty(), "the ghost goes with the drag");
        assertEquals(1, rig.services.released.size());
        rig.session.finishJobs();
        assertEquals("Extrude", rig.session.history().undoLabel(), "one undo step, named after the tool");
        assertEquals(Optional.of(SelectionActions.Op.EXTRUDE), rig.actions.lastOp());
        assertEquals(OpLabel.EXTRUDE, rig.lastLabel());
    }

    @Test
    void draggingInErasesTheFaceAndTheLayersBehindIt() {
        Rig rig = new Rig();
        rig.world.fill(new Box(new BlockPos(0, 50, 0), new BlockPos(9, 59, 9)), stone); // solid ground under the floor
        rig.ray = atHeight(61);
        rig.frame(top(5, 5));
        rig.press(top(5, 5), 0);
        rig.ray = atHeight(59);
        rig.drag(top(5, 5), 0);
        assertEquals(-2, rig.tool.dragLayers());
        assertEquals(new Box(new BlockPos(0, 59, 0), new BlockPos(9, 60, 9)), rig.tool.preview().orElseThrow());
        assertEquals(KeyHint.text("sculptory.hint.extrude.carving", "2", "200"), rig.tool.hints(rig.view).get(0));
        GhostPlacement ghost = rig.services.shown.get(0);
        assertEquals(200, ghost.volume().eraseCount(), "carved cells are erase ghosts");
        assertEquals(0, ghost.volume().blockCount());
        assertEquals(new BlockPos(0, 59, 0), new BlockPos(ghost.originX(), ghost.originY(), ghost.originZ()),
                "the face's own layer goes first: the surface moves in by two");
        rig.release(top(5, 5), 0);
        Region.Cells carved = new Region.Cells(CellSet.of(new Region.Cuboid(new Box(new BlockPos(0, 59, 0),
                new BlockPos(9, 60, 9))), 1000));
        assertEquals(List.of(new OpSpec.Erase(carved, CellMask.ANY)), rig.sentOps());
        assertEquals(Optional.of(SelectionActions.Op.CARVE), rig.actions.lastOp());
        assertEquals(OpLabel.CARVE, rig.lastLabel());
    }

    @Test
    void aDragWithoutLayersOrARayAlongTheAxisSendsNothing() {
        Rig rig = new Rig();
        rig.ray = atHeight(61);
        rig.frame(top(5, 5));
        rig.press(top(5, 5), 0);
        rig.ray = atHeight(61.3);
        rig.drag(top(5, 5), 0);
        assertEquals(0, rig.tool.dragLayers());
        assertEquals(KeyHint.text("sculptory.hint.extrude.choose"), rig.tool.hints(rig.view).get(0));
        assertTrue(rig.services.shown.isEmpty());
        rig.ray = new Ray(5.5, 80, 5.5, 0, -1, 0); // straight down the face's axis: the distance is undefined
        rig.drag(top(5, 5), 0);
        assertEquals(0, rig.tool.dragLayers());
        rig.release(top(5, 5), 0);
        assertTrue(rig.session.sent().isEmpty());
        assertTrue(rig.tool.dragKind().isEmpty());
    }

    @Test
    void theLayersAreCappedByMaxLayersAndAPressOnNothingSaysSo() {
        Rig rig = new Rig();
        rig.set(ExtrudeSettings.MAX_LAYERS, 4);
        rig.ray = atHeight(61);
        rig.frame(top(5, 5));
        rig.press(top(5, 5), 0);
        rig.ray = atHeight(90);
        rig.drag(top(5, 5), 0);
        assertEquals(4, rig.tool.dragLayers());
        rig.ray = atHeight(20);
        rig.drag(top(5, 5), 0);
        assertEquals(-4, rig.tool.dragLayers());
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL));

        assertTrue(rig.press(new WorldCursor(new BlockPos(3, 61, 3), WorldCursor.Face.UP, 3.5, 62, 3.5, false), 0),
                "a press on air is taken");
        assertTrue(rig.tool.dragKind().isEmpty());
        assertEquals(List.of(ExtrudeTool.NOTHING), rig.noticeKeys());
        assertFalse(rig.press(WorldCursor.miss(0, 0, 0), 0), "a miss is not");
    }

    @Test
    void escCancelsTheDragWithNothingSent() {
        Rig rig = new Rig();
        rig.ray = atHeight(61);
        rig.frame(top(5, 5));
        rig.press(top(5, 5), 0);
        rig.ray = atHeight(66);
        rig.drag(top(5, 5), 0);
        assertEquals(5, rig.tool.dragLayers());
        assertFalse(rig.services.shown.isEmpty());
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL));
        assertTrue(rig.tool.dragKind().isEmpty());
        assertFalse(rig.ctx.pointerCapture());
        assertTrue(rig.services.shown.isEmpty());
        rig.release(top(5, 5), 0);
        assertTrue(rig.session.sent().isEmpty());
        assertFalse(rig.tool.onAction(rig.view, EditorAction.CANCEL), "nothing left to cancel: the editor's Esc goes on");

        // Esc while a large face is still being found: the hover starts over on the same block.
        rig.world.fill(new Box(new BlockPos(-100, 60, -100), new BlockPos(99, 60, 99)), stone);
        rig.set(ExtrudeSettings.SIZE, 262_144);
        rig.ray = atHeight(61);
        rig.press(top(5, 5), 0);
        assertTrue(rig.tool.searching());
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL));
        assertTrue(rig.tool.dragKind().isEmpty());
        rig.hoverUntilFound(top(5, 5));
        assertEquals(40_000, rig.tool.hoveredFace().orElseThrow().count());
    }

    @Test
    void aReleaseBeforeALargeFaceIsFoundCommitsOnceItIs() {
        Rig rig = new Rig();
        rig.world.fill(new Box(new BlockPos(-100, 60, -100), new BlockPos(99, 60, 99)), stone);
        rig.set(ExtrudeSettings.SIZE, 262_144);
        rig.ray = atHeight(61);
        rig.press(top(5, 5), 0);
        assertTrue(rig.tool.searching());
        rig.ray = atHeight(63);
        rig.drag(top(5, 5), 0);
        rig.release(top(5, 5), 0);
        assertTrue(rig.session.sent().isEmpty(), "the face is not known yet");
        assertEquals(Optional.of(ExtrudeTool.DragKind.FACE), rig.tool.dragKind(), "the drag waits for it");
        while (rig.tool.searching()) rig.frame(top(5, 5));
        OpSpec.Stack stack = assertInstanceOf(OpSpec.Stack.class, rig.sentOps().get(0));
        assertEquals(40_000, stack.region().cellCount());
        assertEquals(2, stack.count());
        assertTrue(rig.tool.dragKind().isEmpty());
    }

    // ---------------------------------------------------------------- a side of the selection

    @Test
    void ctrlDragOnASideOfTheSelectionExtrudesThatSidesBlocks() {
        Rig rig = new Rig();
        rig.ctx.setSelection(new Box(new BlockPos(2, 58, 2), new BlockPos(7, 60, 7)));
        rig.ray = new Ray(4.5, 80, 4.5, 0, -1, 0); // onto the top face of the selection box
        rig.frame(top(4, 4));
        assertTrue(rig.tool.hints(rig.view).contains(new KeyHint("Ctrl+drag", "sculptory.hint.extrude.side")));
        assertTrue(rig.press(top(4, 4), Modifiers.CONTROL));
        assertEquals(Optional.of(ExtrudeTool.DragKind.SIDE), rig.tool.dragKind());
        rig.ray = aimed(4.5, 75, -12, 4.5, 63, 4.5);
        rig.drag(top(4, 4), Modifiers.CONTROL);
        assertEquals(2, rig.tool.dragLayers());
        assertEquals(new Box(new BlockPos(2, 61, 2), new BlockPos(7, 62, 7)), rig.tool.preview().orElseThrow());
        rig.release(top(4, 4), Modifiers.CONTROL);
        Region.Cells side = new Region.Cells(CellSet.of(new Region.Cuboid(new Box(new BlockPos(2, 60, 2),
                new BlockPos(7, 60, 7))), 1000));
        assertEquals(List.of(new OpSpec.Stack(side, 0, 1, 0, 2, EntityFilter.NONE)), rig.sentOps(),
                "the top layer's 36 stone blocks, not the whole box");

        // Inward on the same side: one ERASE of that layer (the side moves in by one).
        rig.ray = new Ray(4.5, 80, 4.5, 0, -1, 0);
        assertTrue(rig.press(top(4, 4), Modifiers.CONTROL));
        rig.ray = aimed(4.5, 75, -12, 4.5, 60, 4.5);
        rig.drag(top(4, 4), Modifiers.CONTROL);
        assertEquals(-1, rig.tool.dragLayers());
        rig.release(top(4, 4), Modifiers.CONTROL);
        assertEquals(new OpSpec.Erase(side, CellMask.ANY), rig.sentOps().get(1));
        assertEquals(2, rig.sentOps().size());

        // A side with no blocks (the box's underside is air) ends the press with a toast; off the box, Ctrl+drag
        // works on the face under the cursor.
        rig.ray = new Ray(4.5, 40, 4.5, 0, 1, 0);
        assertTrue(rig.press(top(4, 4), Modifiers.CONTROL), "taken, and over");
        assertTrue(rig.tool.dragKind().isEmpty());
        assertEquals(List.of(ExtrudeTool.SIDE_EMPTY), rig.noticeKeys());
        rig.ray = atHeight(61);
        rig.ctx.setSelection(new Box(new BlockPos(20, 60, 20), new BlockPos(25, 62, 25)));
        rig.press(top(5, 5), Modifiers.CONTROL);
        assertEquals(Optional.of(ExtrudeTool.DragKind.FACE), rig.tool.dragKind());
        rig.tool.onAction(rig.view, EditorAction.CANCEL);
    }

    @Test
    void aSideOverTheSelectionCapIsRefused() {
        Rig rig = new Rig();
        rig.session.setPermissions(limits(1_000_000, 50));
        rig.ctx.setSelection(FLOOR);
        rig.ray = new Ray(4.5, 80, 4.5, 0, -1, 0);
        rig.press(top(4, 4), Modifiers.CONTROL);
        assertTrue(rig.tool.dragKind().isEmpty());
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, ExtrudeTool.SIDE_TOO_LARGE, "50")), rig.notices);
    }

    // ---------------------------------------------------------------- smear

    @Test
    void altDragSmearsTheFaceAlongOneAxisLeavingAir() {
        Rig rig = new Rig();
        rig.ray = atHeight(61);
        rig.frame(top(5, 5));
        rig.press(top(5, 5), Modifiers.ALT);
        assertEquals(Optional.of(ExtrudeTool.DragKind.SMEAR), rig.tool.dragKind());
        // Three blocks east on the face's plane, and a little north: the larger wins, snapped to whole blocks.
        rig.ray = aimed(5.5, 75, -12, 8.6, 61, 5.1);
        rig.drag(top(5, 5), Modifiers.ALT);
        assertEquals(List.of(3, 0, 0), List.of(rig.tool.dragOffset()[0], rig.tool.dragOffset()[1], rig.tool.dragOffset()[2]));
        assertEquals(new Box(new BlockPos(0, 60, 0), new BlockPos(12, 60, 9)), rig.tool.preview().orElseThrow());
        assertEquals(KeyHint.text("sculptory.hint.extrude.smearing", "3", "100"), rig.tool.hints(rig.view).get(0));
        GhostPlacement ghost = rig.services.shown.get(0);
        assertEquals(100, ghost.volume().blockCount(), "the face where it lands");
        assertEquals(30, ghost.volume().eraseCount(), "the cells it vacates");
        // With Shift, along the normal instead: up two (the plane's own movement, larger, is ignored).
        rig.ray = atHeight(63);
        rig.drag(top(5, 5), Modifiers.ALT | Modifiers.SHIFT);
        assertEquals(List.of(0, 2, 0), List.of(rig.tool.dragOffset()[0], rig.tool.dragOffset()[1], rig.tool.dragOffset()[2]));
        rig.release(top(5, 5), Modifiers.ALT | Modifiers.SHIFT);
        Region.Cells face = new Region.Cells(CellSet.of(new Region.Cuboid(FLOOR), 1000));
        assertEquals(List.of(new OpSpec.Move(face, new BlockPos(0, 2, 0), Transform.IDENTITY,
                new Pattern.Single(states.air()), EntityFilter.NONE)), rig.sentOps());
        assertEquals(Optional.of(SelectionActions.Op.SMEAR), rig.actions.lastOp());
        assertEquals(OpLabel.SMEAR, rig.lastLabel());
    }

    // ---------------------------------------------------------------- sizes, confirms, limits

    @Test
    void sizesCountCellsTimesLayersAgainstTheConfirmThresholdAndTheServersLimit() {
        Rig rig = new Rig();
        rig.world.fill(new Box(new BlockPos(0, 60, 0), new BlockPos(99, 60, 99)), stone);
        rig.set(ExtrudeSettings.SIZE, 16_384);
        rig.set(ExtrudeSettings.MAX_LAYERS, 64);
        rig.ray = atHeight(61);
        rig.hoverUntilFound(top(5, 5));
        rig.press(top(5, 5), 0);
        rig.ray = atHeight(61 + 51);
        rig.drag(top(5, 5), 0);
        assertEquals(51, rig.tool.dragLayers());
        assertEquals(KeyHint.text("sculptory.hint.extrude.extruding", "51", "510,000"), rig.tool.hints(rig.view).get(0));
        rig.release(top(5, 5), 0);
        assertEquals(List.of("sculptory.confirm.large_op[sculptory.op.extrude,510,000]"), rig.confirmations);
        assertTrue(rig.session.sent().isEmpty(), "waits for the confirmation");
        rig.confirm.run();
        assertEquals(1, rig.sentOps().size());
        assertEquals(51, assertInstanceOf(OpSpec.Stack.class, rig.sentOps().get(0)).count());

        rig.session.setPermissions(limits(150, 1_000_000));
        rig.ray = atHeight(61);
        rig.hoverUntilFound(top(5, 5));
        rig.press(top(5, 5), 0);
        rig.ray = atHeight(63);
        rig.drag(top(5, 5), 0);
        rig.release(top(5, 5), 0);
        assertEquals(1, rig.sentOps().size(), "20,000 blocks over a limit of 150: refused here");
        assertEquals(Notice.of(Notice.Level.WARNING, "sculptory.notice.too_large", "20,000", "150"),
                rig.notices.get(rig.notices.size() - 1));
    }

    @Test
    void aCarveOverTheSelectionCapIsRefusedAndTheRegionNodeIsNeeded() {
        Rig rig = new Rig();
        rig.session.setPermissions(limits(1_000_000, 150));
        rig.ray = atHeight(61);
        rig.frame(top(5, 5));
        rig.press(top(5, 5), 0);
        rig.ray = atHeight(59);
        rig.drag(top(5, 5), 0);
        rig.release(top(5, 5), 0);
        assertTrue(rig.session.sent().isEmpty());
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, ExtrudeTool.CARVE_TOO_LARGE, "150")), rig.notices);

        EnumSet<Perm> granted = EnumSet.allOf(Perm.class);
        granted.remove(Perm.REGION);
        rig.session.setPermissions(new Permissions(Perm.mask(granted), Limits.DEFAULTS));
        rig.notices.clear();
        rig.ray = atHeight(61);
        rig.press(top(5, 5), 0);
        rig.ray = atHeight(63);
        rig.drag(top(5, 5), 0);
        rig.release(top(5, 5), 0);
        assertTrue(rig.session.sent().isEmpty());
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.notice.needs_permission", Perm.REGION.node())),
                rig.notices);
    }

    // ---------------------------------------------------------------- symmetry

    @Test
    void symmetryNeedsASetCentreAndThenTravelsInTheOp() {
        Rig rig = new Rig();
        rig.set(ExtrudeSettings.SYMMETRY, Symmetry.Mode.MIRROR_X);
        assertTrue(rig.tool.hints(rig.view).contains(KeyHint.text("sculptory.hint.symmetry_centre_needed", "M")));
        assertTrue(rig.tool.hints(rig.view).contains(new KeyHint("M", "sculptory.hint.brush.symmetry_centre")));
        rig.ray = atHeight(61);
        rig.frame(top(5, 5));
        rig.press(top(5, 5), 0);
        rig.ray = atHeight(63);
        rig.drag(top(5, 5), 0);
        rig.release(top(5, 5), 0);
        assertTrue(rig.session.sent().isEmpty());
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, SelectionActions.SYMMETRY_CENTRE_NEEDED, "M")), rig.notices);

        // M at the cursor sets the shared centre; the drag then carries the mode and the hint counts the copies.
        rig.frame(top(9, 5));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.SET_SYMMETRY_CENTRE));
        assertTrue(rig.centre.isSet());
        assertEquals(19, rig.centre.x2(), "the block centre of x = 9");
        rig.notices.clear();
        rig.ray = atHeight(61);
        rig.press(top(5, 5), 0);
        rig.ray = atHeight(63);
        rig.drag(top(5, 5), 0);
        assertEquals(KeyHint.text("sculptory.hint.extrude.extruding_copies", "2", "200", "2"),
                rig.tool.hints(rig.view).get(0));
        RecordingDraw draw = new RecordingDraw();
        rig.tool.renderWorld(rig.view, draw);
        assertTrue(draw.boxes.contains(new Box(new BlockPos(9, 61, 0), new BlockPos(18, 62, 9))), "the copy's box: " + draw.boxes);
        rig.release(top(5, 5), 0);
        OpSpec.Stack stack = assertInstanceOf(OpSpec.Stack.class, rig.sentOps().get(0));
        assertEquals(new Symmetry(Symmetry.Mode.MIRROR_X, 19, 11), stack.symmetry());
    }

    // ---------------------------------------------------------------- settings, presets, text

    @Test
    void presetsRoundTripEverySetting() {
        Rig rig = new Rig();
        rig.set(ExtrudeSettings.MATCH, MagicSelect.Match.EXACT_STATE);
        rig.set(ExtrudeSettings.DIAGONALS, true);
        rig.set(ExtrudeSettings.SIZE, 20_000);
        rig.set(ExtrudeSettings.MAX_LAYERS, 100);
        rig.set(ExtrudeSettings.SYMMETRY, Symmetry.Mode.ROTATE_4);
        SettingsValues values = rig.values();
        assertEquals(values, SettingsValues.decode(ExtrudeSettings.SCHEMA, values.encode()));
        assertEquals(List.of("diagonals", "match", "max_layers", "size", "symmetry"), List.copyOf(values.encode().keySet()));
        SettingsValues defaults = SettingsValues.defaults(ExtrudeSettings.SCHEMA);
        assertEquals(defaults, SettingsValues.decode(ExtrudeSettings.SCHEMA, defaults.encode()));
        assertEquals(4_096, defaults.get(ExtrudeSettings.SIZE));
        assertEquals(32, defaults.get(ExtrudeSettings.MAX_LAYERS));
        assertEquals(MagicSelect.Match.SAME_BLOCK, defaults.get(ExtrudeSettings.MATCH));
        assertFalse(defaults.get(ExtrudeSettings.DIAGONALS));
        assertEquals(Symmetry.Mode.OFF, defaults.get(ExtrudeSettings.SYMMETRY));
        assertFalse(values.with(ExtrudeSettings.SIZE, 300_000).isValid(Limits.DEFAULTS), "at most 262,144");
        assertFalse(values.with(ExtrudeSettings.SIZE, 200_000).isValid(limits(1, 100).limits()), "and the server's selection cap");
    }

    @Test
    void theEnglishTextCoversTheTool() throws IOException {
        JsonObject lang;
        try (InputStream in = getClass().getResourceAsStream("/assets/sculptory/lang/en_us.json")) {
            assertNotNull(in);
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        List<String> keys = new ArrayList<>(List.of("sculptory.tool.extrude", "sculptory.tool.extrude.tooltip",
                "sculptory.key.tool_12", SelectionActions.Op.EXTRUDE.nameKey(), SelectionActions.Op.CARVE.nameKey(),
                SelectionActions.Op.SMEAR.nameKey(), ExtrudeTool.FACE_LIMIT, ExtrudeTool.FACE_UNLOADED, ExtrudeTool.NOTHING,
                ExtrudeTool.SIDE_EMPTY, ExtrudeTool.SIDE_TOO_LARGE, ExtrudeTool.CARVE_TOO_LARGE,
                "sculptory.hint.extrude.aim", "sculptory.hint.extrude.finding", "sculptory.hint.extrude.face",
                "sculptory.hint.extrude.face_cut", "sculptory.hint.extrude.drag", "sculptory.hint.extrude.side",
                "sculptory.hint.extrude.smear", "sculptory.hint.extrude.choose", "sculptory.hint.extrude.extruding",
                "sculptory.hint.extrude.extruding_copies", "sculptory.hint.extrude.carving",
                "sculptory.hint.extrude.carving_copies", "sculptory.hint.extrude.smearing",
                "sculptory.hint.extrude.smearing_copies", "sculptory.hint.extrude.no_ghost",
                "sculptory.setting.extrude.symmetry_section"));
        for (SettingDef<?> def : ExtrudeSettings.SCHEMA.defs()) {
            keys.add(def.labelKey());
            if (def instanceof SettingDef.Enum<?> choice) {
                for (Object option : choice.type().getEnumConstants()) {
                    keys.add(def.labelKey() + "." + ((Enum<?>) option).name().toLowerCase(Locale.ROOT));
                }
            }
        }
        for (String key : keys) assertTrue(lang.has(key), key);
    }

    // ---------------------------------------------------------------- fixtures

    private static Permissions limits(long maxOp, long maxSelectionCells) {
        EnumSet<Perm> granted = EnumSet.allOf(Perm.class);
        granted.remove(Perm.LIMIT_BYPASS);
        return new Permissions(Perm.mask(granted), new Limits(maxOp, maxOp, 32, 20, 32L << 20, 2, maxSelectionCells,
                Limits.DEFAULT_SELECTION_SECTIONS));
    }

    /** The Extrude tool, active in an editor context over a stone floor and a mock session. */
    private final class Rig {
        final FakeWorld world = new FakeWorld(states);
        final MockEditorSession session = new MockEditorSession();
        final List<Notice> notices = new ArrayList<>();
        final List<String> confirmations = new ArrayList<>();
        Runnable confirm;
        final FakeServices services = new FakeServices();
        final SymmetryCentre centre = new SymmetryCentre();
        final EditorContext ctx;
        final SelectionActions actions;
        final ExtrudeTool tool;
        final ToolContext view;
        Ray ray;
        long now = 1_000 * MS;

        Rig() {
            world.fill(FLOOR, stone);
            EditorBackend backend = new EditorBackend() {
                @Override
                public Optional<EditorSession> session() {
                    return Optional.of(session);
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
            actions = new SelectionActions(() -> ctx.contextFor(ToolId.SELECT), ctx::activeBlock, (message, onConfirm) -> {
                confirmations.add(message);
                confirm = onConfirm;
            }, Translator.KEYS, () -> 42L, centre, action -> EditorKeymap.defaults().display(action));
            tool = new ExtrudeTool(services, actions);
            ctx.tools().register(tool);
            view = ctx.contextFor(ToolId.EXTRUDE);
            assertTrue(ctx.tools().activate(ToolId.EXTRUDE, view));
        }

        void frame(WorldCursor cursor) {
            now += 16 * MS;
            tool.frame(view, new FrameInfo(now, 0f, 0, 0, cursor));
        }

        /** Frames until the face under {@code cursor} is found. */
        void hoverUntilFound(WorldCursor cursor) {
            frame(cursor);
            int frames = 0;
            while (tool.searching()) {
                frame(cursor);
                if (++frames > 10_000) throw new AssertionError("never found");
            }
        }

        boolean press(WorldCursor cursor, int modifiers) {
            return tool.onPointer(view, new PointerEvent(PointerEvent.Kind.PRESS, PointerEvent.LEFT, 0, 0, modifiers, cursor));
        }

        void drag(WorldCursor cursor, int modifiers) {
            tool.onPointer(view, new PointerEvent(PointerEvent.Kind.DRAG, PointerEvent.LEFT, 0, 0, modifiers, cursor));
        }

        void release(WorldCursor cursor, int modifiers) {
            tool.onPointer(view, new PointerEvent(PointerEvent.Kind.RELEASE, PointerEvent.LEFT, 0, 0, modifiers, cursor));
        }

        <T> void set(SettingDef<T> def, T value) {
            ctx.updateSettings(ToolId.EXTRUDE, ctx.settings(ToolId.EXTRUDE).with(def, value));
        }

        SettingsValues values() {
            return ctx.settings(ToolId.EXTRUDE);
        }

        List<OpSpec> sentOps() {
            return session.sent().stream().map(action -> assertInstanceOf(ToolAction.RunOp.class, action).op()).toList();
        }

        /** The tool label of the last run sent (the server names the job and its history entry by it). */
        OpLabel lastLabel() {
            List<ToolAction> sent = session.sent();
            return assertInstanceOf(ToolAction.RunOp.class, sent.get(sent.size() - 1)).label();
        }

        List<String> noticeKeys() {
            return notices.stream().map(Notice::key).toList();
        }

        /** The tool's services: the rig's ray, ghosts recorded, bakes on this thread, a clock ticking 1 ms per read. */
        private final class FakeServices implements ExtrudeTool.Services {
            List<GhostPlacement> shown = List.of();
            final List<GhostVolume> released = new ArrayList<>();

            @Override
            public Optional<Ray> cursorRay() {
                return Optional.ofNullable(ray);
            }

            @Override
            public String keyLabel(KeyAction action) {
                return EditorKeymap.defaults().display(action);
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
                return Runnable::run;
            }

            @Override
            public long nanoTime() {
                return now += MS;
            }
        }
    }

    private static final class RecordingDraw implements WorldDraw {
        final List<Box> boxes = new ArrayList<>();
        final List<Region> regions = new ArrayList<>();
        int outlineArgb;

        @Override
        public void boxOutline(Box box, int argb) {
            boxes.add(box);
        }

        @Override
        public void boxFill(Box box, int argb) {}

        @Override
        public void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb) {}

        @Override
        public void ring(double centerX, double y, double centerZ, double radius, int argb) {}

        @Override
        public void seeThrough(boolean enabled) {}

        @Override
        public void shapeOutlines(List<? extends Region> regions, int argb, int copyArgb) {
            this.regions.addAll(regions);
            outlineArgb = argb;
        }
    }
}
