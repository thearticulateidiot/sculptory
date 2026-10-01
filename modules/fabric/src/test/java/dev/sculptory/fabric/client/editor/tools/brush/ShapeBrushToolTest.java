package dev.sculptory.fabric.client.editor.tools.brush;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.ShapeSpec;
import dev.sculptory.core.brush.ShapeStamp;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.MixLayout;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.palette.PalettePattern;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.EditorBackend;
import dev.sculptory.fabric.client.editor.EditorContext;
import dev.sculptory.fabric.client.editor.brush.BrushPredictor;
import dev.sculptory.fabric.client.editor.brush.BrushTerrain;
import dev.sculptory.fabric.client.editor.brush.StrokeController;
import dev.sculptory.fabric.client.editor.hud.HelpSheet;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.input.KeyChord;
import dev.sculptory.fabric.client.editor.mock.MockEditorSession;
import dev.sculptory.fabric.client.editor.render.BrushCursor;
import dev.sculptory.fabric.client.editor.render.OverlayColors;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.FrameInfo;
import dev.sculptory.fabric.client.editor.tool.KeyHint;
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.RayOverlay;
import dev.sculptory.fabric.client.editor.tool.ScrollEvent;
import dev.sculptory.fabric.client.editor.tool.Tool;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.ToolDescriptor;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.ToolRegistry;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.tool.WorldDraw;
import dev.sculptory.fabric.client.editor.tools.EditorToolSet;
import dev.sculptory.fabric.client.editor.tools.PlaceholderTool;
import dev.sculptory.fabric.client.editor.world.Ray;
import dev.sculptory.fabric.client.session.Capabilities;
import dev.sculptory.fabric.client.session.ClipboardCache;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.FabricEditorSession;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** The Shape brush tool: slot and key, settings to spec, anchors, drags, keys, the cursor outline and prediction. */
class ShapeBrushToolTest {
    private static final long MS = 1_000_000L;
    private static final long SEED = 42L;
    private static final BlockDescriptor ACTIVE = BlockDescriptor.parse("minecraft:stone");

    private final FakeStateSpace states = new FakeStateSpace();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");

    /** A hit on top of terrain column (x, z). */
    private static WorldCursor top(int x, int z) {
        int y = BrushTerrain.height(x, z);
        return new WorldCursor(new BlockPos(x, y, z), WorldCursor.Face.UP, x + 0.5, y + 1, z + 0.5, false);
    }

    /** A hit on face {@code face} of block (x, y, z). */
    private static WorldCursor on(int x, int y, int z, WorldCursor.Face face) {
        return new WorldCursor(new BlockPos(x, y, z), face, x + 0.5 + face.dx() * 0.5, y + 0.5 + face.dy() * 0.5,
                z + 0.5 + face.dz() * 0.5, false);
    }

    // ---------------------------------------------------------------- registration

    @Test
    void theToolIsPaletteSlotTenOnKeyZero() {
        ToolRegistry registry = new ToolRegistry();
        EditorToolSet.register(registry, new PlaceholderTool(new ToolDescriptor(ToolId.SELECT, "sculptory.tool.select",
                "minecraft:stone", Perm.REGION), "sculptory.hint.select.drag"));
        assertEquals(15, registry.paletteOrder().size(), "Generate, Extrude, Fluid, Tinker and Weather follow in slots 11-15");
        Tool shape = registry.slot(10).orElseThrow();
        assertInstanceOf(ShapeBrushTool.class, shape);
        assertEquals(ToolId.SHAPE, shape.descriptor().id());
        assertEquals(Perm.BRUSH, shape.descriptor().permission());
        assertEquals("sculptory.tool.shape", shape.descriptor().nameKey());
        assertEquals(KeyAction.TOOL_10, KeyAction.toolSlot(10));
        assertEquals(10, KeyAction.TOOL_10.toolSlot());
        assertEquals(List.of(KeyChord.parse("0")), KeyAction.TOOL_10.defaultChords());
        assertEquals("1–9, 0, -, =, [", HelpSheet.toolKeys(EditorKeymap.defaults()), "the help sheet lists every slot key");
    }

    // ---------------------------------------------------------------- settings to spec

    @Test
    void aClickPlacesTheDefaultSphereOnTheSurfaceAsOneUndoStep() {
        Rig rig = new Rig();
        rig.press(top(0, 0));
        rig.frame(top(0, 0), 16);
        rig.release(top(0, 0));
        assertEquals(1, rig.session.begins.size());
        RecordingSession.Begin begin = rig.session.begins.get(0);
        assertEquals(ToolId.SHAPE, begin.tool());
        assertEquals(BrushSpec.shape(4, new ShapeSpec(ShapeSpec.Kind.SPHERE, 9, Facing.UP, ShapeSpec.Mode.PLACE, 0),
                new Pattern.Single(stone), SEED, null, Symmetry.NONE), begin.spec());
        assertTrue(begin.params().predict(), "a radius-4 sphere is predicted");
        // Resting on the top face of (0, 61, 0): its box is y 62-70, centred on the column.
        int y = BrushTerrain.height(0, 0);
        assertEquals(List.of(new Dab(0, 8, 16 * (y + 1) + 72, 8, Dab.FULL_PRESSURE)), begin.handle().dabs);
        assertEquals(new Box(new BlockPos(-4, y + 1, -4), new BlockPos(4, y + 9, 4)),
                ShapeStamp.placement(begin.spec(), begin.handle().dabs.get(0)).box());
        assertTrue(begin.handle().ended);
        assertEquals("Shape", rig.mock.history().undoLabel(), "one undo step, labelled Shape");
    }

    @Test
    void theSettingsGoIntoTheSpec() {
        Rig rig = new Rig();
        rig.set(ShapeSettings.KIND, ShapeSpec.Kind.CONE);
        rig.set(ShapeSettings.RADIUS, 3);
        rig.set(ShapeSettings.HEIGHT_AUTO, false);
        rig.set(ShapeSettings.HEIGHT, 6);
        rig.set(ShapeSettings.FACING, ShapeSettings.FacingChoice.EAST);
        rig.set(ShapeSettings.ANCHOR, ShapeSettings.Anchor.CENTRE);
        rig.set(ShapeSettings.HOLLOW, true);
        rig.set(ShapeSettings.THICKNESS, 2);
        rig.set(ShapeSettings.BLOCKS, ShapeSettings.Blocks.PALETTE);
        rig.set(ShapeSettings.PALETTE, List.of(new SettingDef.WeightedBlock(ACTIVE, 3),
                new SettingDef.WeightedBlock(BlockDescriptor.parse("minecraft:dirt"), 1)));
        rig.clickAt(top(2, 2));
        BrushSpec spec = rig.session.begins.get(0).spec();
        assertEquals(new ShapeSpec(ShapeSpec.Kind.CONE, 6, Facing.EAST, ShapeSpec.Mode.PLACE, 2), spec.shapeSpec());
        assertEquals(3, spec.radius());
        assertEquals(new Pattern.Weighted(new int[] {stone, dirt}, new int[] {3, 1}, SEED), spec.material());
        // Centred on block (2, h, 2): the even length runs one more cell east (x 0-5).
        Dab dab = rig.session.begins.get(0).handle().dabs.get(0);
        int y = BrushTerrain.height(2, 2);
        assertEquals(new Box(new BlockPos(0, y - 3, -1), new BlockPos(5, y + 3, 5)), ShapeStamp.placement(spec, dab).box());

        Rig carve = new Rig();
        carve.set(ShapeSettings.MODE, ShapeSpec.Mode.CARVE);
        carve.clickAt(top(0, 0));
        assertNull(carve.session.begins.get(0).spec().material(), "carving sends no blocks");
        assertFalse(carve.values().isVisible(ShapeSettings.BLOCKS), "nor shows them");
        assertTrue(carve.values().isVisible(ShapeSettings.MODE));

        Rig sphere = new Rig();
        assertFalse(sphere.values().isVisible(ShapeSettings.HEIGHT_AUTO), "a sphere has no height");
        assertFalse(sphere.values().isVisible(ShapeSettings.FACING));
        sphere.set(ShapeSettings.KIND, ShapeSpec.Kind.CUBE);
        assertTrue(sphere.values().isVisible(ShapeSettings.HEIGHT_AUTO));
        assertFalse(sphere.values().isVisible(ShapeSettings.HEIGHT), "the height follows the diameter");
        sphere.set(ShapeSettings.HEIGHT_AUTO, false);
        assertTrue(sphere.values().isVisible(ShapeSettings.HEIGHT));
    }

    @Test
    void anUnknownActiveBlockOrAnEmptyMixRefusesThePress() {
        Rig rig = new Rig(BlockDescriptor.parse("minecraft:no_such_block"));
        rig.clickAt(top(0, 0));
        assertTrue(rig.session.begins.isEmpty());
        assertEquals(List.of("sculptory.notice.unknown_block"), rig.noticeKeys());
    }

    @Test
    void clickedFaceTurnsTheShapeAwayFromTheFaceForTheWholePress() {
        Rig rig = new Rig();
        rig.set(ShapeSettings.KIND, ShapeSpec.Kind.CYLINDER);
        rig.set(ShapeSettings.RADIUS, 2);
        rig.set(ShapeSettings.HEIGHT_AUTO, false);
        rig.set(ShapeSettings.HEIGHT, 4);
        rig.set(ShapeSettings.FACING, ShapeSettings.FacingChoice.CLICKED_FACE);
        WorldCursor wall = on(5, 70, 0, WorldCursor.Face.EAST);
        rig.press(wall);
        rig.frame(wall, 16);
        // Dragging onto the top of the ground keeps the east facing.
        rig.drag(top(10, 0));
        rig.frame(top(10, 0), 60);
        rig.release(top(10, 0));
        RecordingSession.Begin begin = rig.session.begins.get(0);
        assertEquals(Facing.EAST, begin.spec().shapeSpec().facing());
        // Out of the wall: x 6-9, centred on the block's y and z.
        assertEquals(new Box(new BlockPos(6, 68, -2), new BlockPos(9, 72, 2)),
                ShapeStamp.placement(begin.spec(), begin.handle().dabs.get(0)).box());
        assertTrue(begin.handle().dabs.size() > 1, "the drag painted more");
        for (Dab dab : begin.handle().dabs) {
            assertEquals(Facing.EAST, begin.spec().shapeSpec().facing());
            assertEquals(0, Math.floorMod(dab.x16(), 16), "an even length is centred on a block edge");
            assertEquals(8, Math.floorMod(dab.y16(), 16), "an odd side on a block centre");
        }
    }

    // ---------------------------------------------------------------- anchors

    @Test
    void anchorsCentreTheShapeOrRestItOnTheFace() {
        ShapeSpec tall = new ShapeSpec(ShapeSpec.Kind.CYLINDER, 6, Facing.UP, ShapeSpec.Mode.PLACE, 0);
        BlockPos block = new BlockPos(10, 64, -3);
        // Centre: odd sides on the block's centre, the even height one more cell toward the facing end.
        assertArrayEquals(new double[] {10.5, 65, -2.5}, ShapeAnchor.point(tall, 2, Facing.UP,
                ShapeSettings.Anchor.CENTRE, block, WorldCursor.Face.UP));
        assertArrayEquals(new double[] {10.5, 64, -2.5}, ShapeAnchor.point(tall, 2, Facing.DOWN,
                ShapeSettings.Anchor.CENTRE, block, WorldCursor.Face.UP));
        // On surface, on each face: the box starts at the next cell out.
        for (WorldCursor.Face face : WorldCursor.Face.values()) {
            for (Facing facing : Facing.values()) {
                double[] point = ShapeAnchor.point(tall, 2, facing, ShapeSettings.Anchor.SURFACE, block, face);
                Box box = ShapeStamp.box(tall, 2, facing, Math.round(point[0] * 16), Math.round(point[1] * 16),
                        Math.round(point[2] * 16));
                int[] min = {box.min().x(), box.min().y(), box.min().z()};
                int[] max = {box.max().x(), box.max().y(), box.max().z()};
                int[] at = {block.x(), block.y(), block.z()};
                int[] normal = {face.dx(), face.dy(), face.dz()};
                for (int axis = 0; axis < 3; axis++) {
                    if (normal[axis] > 0) assertEquals(at[axis] + 1, min[axis], face + " " + facing);
                    if (normal[axis] < 0) assertEquals(at[axis] - 1, max[axis], face + " " + facing);
                    if (normal[axis] == 0) {
                        assertTrue(min[axis] <= at[axis] && max[axis] >= at[axis], face + " " + facing + " across");
                        assertTrue(Math.abs((at[axis] - min[axis]) - (max[axis] - at[axis])) <= 1, "centred");
                    }
                }
            }
        }
        // The clicked face picks the facing.
        assertEquals(Facing.WEST, ShapeAnchor.facing(ShapeSettings.FacingChoice.CLICKED_FACE, WorldCursor.Face.WEST));
        assertEquals(Facing.DOWN, ShapeAnchor.facing(ShapeSettings.FacingChoice.DOWN, WorldCursor.Face.WEST));
    }

    @Test
    void dragsLaySnappedShapesAQuarterOfTheShortestSideApart() {
        Rig rig = new Rig();
        rig.set(ShapeSettings.RADIUS, 6); // 13 across: every 3.25 blocks
        rig.press(top(-12, 0));
        rig.frame(top(-12, 0), 16);
        for (int x = -11; x <= 12; x++) {
            rig.drag(top(x, 0));
            rig.frame(top(x, 0), 30);
        }
        rig.release(top(12, 0));
        assertEquals(1, rig.session.begins.size(), "one stroke, one undo step");
        List<Dab> dabs = rig.session.begins.get(0).handle().dabs;
        assertTrue(dabs.size() >= 6, "dabs: " + dabs.size());
        Set<String> points = new HashSet<>();
        for (int i = 0; i < dabs.size(); i++) {
            Dab dab = dabs.get(i);
            assertEquals(i, dab.index());
            assertEquals(8, Math.floorMod(dab.x16(), 16), "on a block centre: " + dab);
            assertEquals(8, Math.floorMod(dab.z16(), 16));
            assertTrue(points.add(dab.x16() + "," + dab.y16() + "," + dab.z16()), "no shape twice: " + dab);
            if (i > 0) {
                double dx = (dab.x16() - dabs.get(i - 1).x16()) / 16.0;
                assertTrue(Math.abs(dx) <= 5, "spaced about 3.25 blocks: " + dx);
            }
        }
        assertEquals(3.25, ShapeAnchor.spacing(ShapeSettings.shape(rig.values(), Limits.DEFAULTS, Facing.UP), 6));
        assertEquals(1.0, ShapeAnchor.spacing(new ShapeSpec(ShapeSpec.Kind.CYLINDER, 1, Facing.UP, ShapeSpec.Mode.PLACE, 0),
                20), "a disc one block thick is laid every block");
    }

    @Test
    void dabsCostTheirWorkUnits() {
        Rig small = new Rig();
        small.press(top(0, 0));
        assertEquals(1, small.tool.controller().dabCost(), "a radius-4 sphere");
        small.release(top(0, 0));
        Rig big = new Rig();
        big.set(ShapeSettings.KIND, ShapeSpec.Kind.CUBE);
        big.set(ShapeSettings.RADIUS, 32);
        big.set(ShapeSettings.SYMMETRY, Symmetry.Mode.ROTATE_4);
        big.centre.set(1, 1);
        big.press(top(0, 0));
        assertEquals(StrokeController.MAX_IN_FLIGHT, big.tool.controller().dabCost(),
                "four radius-32 cubes of 17 units each, at most the whole window");
        big.release(top(0, 0));
    }

    /**
     * End to end on the client: a fast drag and quick presses of the largest shape send one shape at a time, each once
     * the server has reported the last written (not merely admitted), so the server's queue is empty whenever one
     * arrives and nothing is refused RATE_LIMITED; meanwhile the unwritten shape's boxes are drawn faded.
     */
    @Test
    void largeShapesWaitForTheServerToWriteTheLastOne() throws ProtocolException {
        Rig rig = new Rig();
        FakeTransport transport = new FakeTransport(states);
        FabricEditorSession fabric = new FabricEditorSession(transport, () -> states, new AtomicLong()::get, "test");
        fabric.onJoin();
        fabric.onFrame(Codec.encodeS2C(new S2C.Welcome(ProtocolV2.VERSION, Features.of(Features.STROKES),
                Limits.DEFAULTS, Perm.mask(EnumSet.allOf(Perm.class)), 1L), states));
        rig.current = fabric;
        rig.set(ShapeSettings.KIND, ShapeSpec.Kind.CUBE);
        rig.set(ShapeSettings.RADIUS, 32);
        rig.set(ShapeSettings.SYMMETRY, Symmetry.Mode.ROTATE_4);
        rig.centre.set(1, 1);

        rig.press(top(0, 0));
        int x = 0;
        for (int i = 0; i < 30; i++) {
            x += 4;
            rig.drag(top(x, 0));
            rig.frame(top(x, 0), 100);
            fabric.tick();
        }
        List<C2S.Dabs> sent = sentDabs(transport);
        assertEquals(1, sent.size(), "one large shape at a time");
        C2S.Dabs first = sent.get(0);
        int strokeId = first.strokeId();
        int firstIndex = first.dabs().get(0).index();
        // Admitted is not enough: the server has not written it.
        fabric.onFrame(Codec.encodeS2C(new S2C.StrokeStatus(strokeId, firstIndex, S2C.StrokeStatus.Status.OK, null),
                states));
        RecordingDraw pending = new RecordingDraw();
        rig.tool.renderWorld(rig.view, pending);
        for (ShapeStamp.Placement placement : ShapeStamp.placements(rig.tool.strokeSpec(), first.dabs().get(0))) {
            assertTrue(pending.boxes.contains(placement.box()), "the unwritten shape is drawn: " + placement.box());
        }
        for (int i = 0; i < 10; i++) {
            x += 4;
            rig.drag(top(x, 0));
            rig.frame(top(x, 0), 100);
            fabric.tick();
        }
        assertEquals(1, sentDabs(transport).size(), "still waiting for the first to be written");
        // Written: the next goes out, and the first's boxes are no longer drawn.
        fabric.onFrame(Codec.encodeS2C(new S2C.StrokeStatus(strokeId, firstIndex, S2C.StrokeStatus.Status.OK, null,
                firstIndex), states));
        RecordingDraw written = new RecordingDraw();
        rig.tool.renderWorld(rig.view, written);
        assertFalse(written.boxes.contains(ShapeStamp.placements(rig.tool.strokeSpec(), first.dabs().get(0)).get(0).box()));
        for (int i = 0; i < 10; i++) {
            x += 4;
            rig.drag(top(x, 0));
            rig.frame(top(x, 0), 100);
            fabric.tick();
        }
        sent = sentDabs(transport);
        assertEquals(2, sent.size(), "the next shape once the first is written");
        int secondIndex = sent.get(1).dabs().get(0).index();

        // Let go while the second is unwritten; the stroke ends. A new press waits for it too.
        rig.release(top(x, 0));
        fabric.tick();
        fabric.onFrame(Codec.encodeS2C(new S2C.StrokeStatus(strokeId, secondIndex, S2C.StrokeStatus.Status.ENDED, null),
                states));
        rig.press(top(-40, 0));
        for (int i = 0; i < 10; i++) {
            rig.frame(top(-40, 0), 100);
            fabric.tick();
        }
        assertEquals(2, sentDabs(transport).size(), "the new press waits for the last press's shape");
        fabric.onFrame(Codec.encodeS2C(new S2C.StrokeStatus(strokeId, secondIndex, S2C.StrokeStatus.Status.OK, null,
                secondIndex), states));
        for (int i = 0; i < 10; i++) {
            rig.frame(top(-40, 0), 100);
            fabric.tick();
        }
        sent = sentDabs(transport);
        assertEquals(3, sent.size(), "then its first shape goes out");
        assertTrue(sent.get(2).strokeId() != strokeId, "on the new stroke");
        rig.release(top(-40, 0));
    }

    private static List<C2S.Dabs> sentDabs(FakeTransport transport) {
        return transport.sent.stream().filter(C2S.Dabs.class::isInstance).map(C2S.Dabs.class::cast).toList();
    }

    @Test
    void holdingStillPlacesOneShape() {
        Rig rig = new Rig();
        rig.press(top(3, 3));
        for (int i = 0; i < 20; i++) rig.frame(top(3, 3), 200);
        rig.release(top(3, 3));
        assertEquals(1, rig.session.begins.get(0).handle().dabs.size());
    }

    // ---------------------------------------------------------------- the cursor ray over the press's own shapes

    /**
     * A reported bug: a click held still placed shape after shape. The cursor ray, cast every frame as the editor
     * casts it, met the shape the press had just placed, so the aim jumped toward the camera by about the shape's size
     * (more than the spacing) and another shape went on top of it, and so on while the button was held.
     */
    @Test
    void aClickHeldStillPlacesOneShape() throws ProtocolException {
        Live live = new Live();
        Ray ray = Live.ray(0.5, 80, -12.5, 0.5, 62, 0.5);
        WorldCursor pressed = live.cast(ray);
        live.press(ray);
        for (int i = 0; i < 40; i++) {
            live.frame(ray, 50);
            assertEquals(pressed, live.cast(ray), "the ray looks through the shape it placed");
        }
        assertNotNull(live.rig.tool.rayOverlay());
        live.release(ray);
        assertEquals(1, live.dabs().size(), "one shape: " + live.dabs());
        assertEquals(1, live.begins(), "one stroke, one undo step");
        assertFalse(live.target().cells.isEmpty(), "the shape was predicted into the world");
        assertNull(live.rig.tool.rayOverlay(), "after the press the cursor sees the shape");
        assertNotEquals(pressed, live.cast(ray), "and lands on it");
    }

    /**
     * Centred on the cursor, a shape fills the ground under it (Place) or carves it away (Carve): looking through the
     * press's shapes means seeing the world as it was, not skipping their cells, or a drag would climb onto its spheres
     * or sink into its holes and bore down.
     */
    @Test
    void centredOrCarvingAClickPlacesOneShapeAndADragStaysOnTheGroundAsItWas() throws ProtocolException {
        for (ShapeSpec.Mode mode : List.of(ShapeSpec.Mode.PLACE, ShapeSpec.Mode.CARVE, ShapeSpec.Mode.PLACE_IN_AIR)) {
            Live live = new Live();
            live.flatten();
            live.rig.set(ShapeSettings.ANCHOR, ShapeSettings.Anchor.CENTRE);
            live.rig.set(ShapeSettings.MODE, mode);
            live.press(Live.down(-8));
            for (int i = 0; i < 20; i++) live.frame(Live.down(-8), 50);
            assertEquals(1, live.dabs().size(), mode + ": one shape: " + live.dabs());
            for (int x = -7; x <= 8; x++) live.dragTo(Live.down(x));
            live.release(Live.down(8));
            assertEquals(8, live.dabs().size(), mode + ": 16 blocks at 2.25 apart: " + live.dabs());
            for (Dab dab : live.dabs()) {
                assertEquals(16 * (Live.GROUND - 1) + 8, dab.y16(), mode + ": centred on the ground's top: " + dab);
            }
            assertFalse(live.target().cells.isEmpty(), mode + ": the shapes changed the world");
        }
    }

    /**
     * A hand shakes on a click: at a grazing angle a turn of a fifth of a degree moves the hit two blocks, twice a
     * radius-1 sphere's spacing, yet adds nothing. A real move then paints from the click.
     */
    @Test
    void aShakeUnderTheThresholdAddsNothingAndAMovePaints() throws ProtocolException {
        Live live = new Live();
        live.flatten();
        live.rig.set(ShapeSettings.RADIUS, 1);
        Ray still = Live.grazing(2.5);
        live.press(still);
        live.frame(still, 50);
        for (int i = 0; i < 20; i++) {
            Ray shaken = Live.grazing(i % 2 == 0 ? 2.3 : 2.7);
            assertTrue(Math.abs(live.cast(shaken).hitX() - live.cast(still).hitX()) > 2, "the hit moves two blocks");
            live.drag(shaken);
            live.frame(shaken, 50);
        }
        assertEquals(1, live.dabs().size(), "one shape: " + live.dabs());
        Ray moved = Live.grazing(3.5);
        live.drag(moved);
        live.frame(moved, 50);
        live.release(moved);
        assertTrue(live.dabs().size() > 1, "the drag painted: " + live.dabs());
        assertEquals(1, live.begins());
    }

    /**
     * A bigger radius while the button is held still restarts the stroke but places nothing: resting on the ground, the
     * bigger sphere's centre is 8 blocks higher, more than its spacing, and the press keeps its first point instead.
     * Moving then paints with the new radius.
     */
    @Test
    void aRadiusChangeWhileHeldStillPlacesNothingMore() throws ProtocolException {
        Live live = new Live();
        live.flatten();
        live.press(Live.down(0));
        for (int i = 0; i < 5; i++) live.frame(Live.down(0), 50);
        assertTrue(live.rig.scroll(1, Modifiers.CONTROL | Modifiers.SHIFT));
        assertTrue(live.rig.scroll(1, Modifiers.CONTROL | Modifiers.SHIFT));
        assertEquals(12, live.rig.setting(ShapeSettings.RADIUS));
        for (int i = 0; i < 20; i++) live.frame(Live.down(0), 50);
        assertEquals(1, live.dabs().size(), "one shape: " + live.dabs());
        for (int x = 1; x <= 12; x++) live.dragTo(Live.down(x));
        live.release(Live.down(12));
        assertTrue(live.dabs().size() > 1, "the drag painted");
        assertEquals(1, sentBegins(live.transport).stream().filter(b -> b.spec().radius() == 4).count());
        assertEquals(12, sentBegins(live.transport).get(sentBegins(live.transport).size() - 1).spec().radius());
    }

    /**
     * While a press's first shape waits for the last press's large shape to be written, a radius change still moves
     * its point: the shape goes out resting on the ground at the new radius, not where the old radius's centre was.
     */
    @Test
    void aRadiusChangeWhileTheFirstShapeWaitsMovesItsPoint() throws ProtocolException {
        Live live = new Live();
        live.flatten();
        live.rig.set(ShapeSettings.RADIUS, 32);
        live.press(Live.down(0));
        live.frameUnwritten(Live.down(0), 50);
        live.release(Live.down(0));
        assertEquals(1, live.dabs().size(), "a radius-32 sphere the server has not written");
        live.press(Live.down(0));
        for (int i = 0; i < 5; i++) live.frameUnwritten(Live.down(0), 50);
        assertEquals(1, live.dabs().size(), "the next press waits for it");
        for (int i = 0; i < 7; i++) assertTrue(live.rig.scroll(-1, Modifiers.CONTROL | Modifiers.SHIFT));
        assertEquals(4, live.rig.setting(ShapeSettings.RADIUS));
        for (int i = 0; i < 5; i++) live.frame(Live.down(0), 50);
        live.release(Live.down(0));
        List<Dab> dabs = live.dabs();
        assertEquals(2, dabs.size(), "one shape each");
        assertEquals(16 * (Live.GROUND + 32) + 8, dabs.get(0).y16());
        assertEquals(16 * (Live.GROUND + 4) + 8, dabs.get(1).y16(), "the radius-4 sphere rests on the ground");
    }

    /** A pick without a ray (no camera for a frame) is no move: the click stays a click. */
    @Test
    void aPickWithoutARayIsNoMove() throws ProtocolException {
        Live live = new Live();
        live.flatten();
        live.rig.set(ShapeSettings.RADIUS, 1);
        live.press(Live.grazing(2.5));
        live.frame(Live.grazing(2.5), 50);
        live.rig.services.ray = null;
        live.rig.frame(WorldCursor.miss(0, 0, 0), 50);
        for (int i = 0; i < 10; i++) live.frame(Live.grazing(i % 2 == 0 ? 2.3 : 2.7), 50);
        live.release(Live.grazing(2.5));
        assertEquals(1, live.dabs().size(), "one shape: " + live.dabs());
    }

    /** With "Only inside selection" the press keeps only the sections inside the selection its shapes can write. */
    @Test
    void onlyInsideSelectionKeepsOnlyTheSectionsInsideIt() throws ProtocolException {
        Live live = new Live();
        live.flatten();
        live.rig.set(ShapeSettings.INSIDE_SELECTION, true);
        live.rig.ctx.setSelection(new Box(new BlockPos(0, 60, 0), new BlockPos(3, 65, 3)));
        live.press(Live.down(0));
        live.frame(Live.down(0), 50);
        // The sphere's box, x and z -4..4 and y 62..70, spans 8 sections; clipped, x and z 0..3 and y 62..65 span 2.
        assertEquals(2, ((PressSnapshot) live.rig.tool.rayOverlay()).size());
        live.release(Live.down(0));
    }

    private static List<C2S.StrokeBegin> sentBegins(FakeTransport transport) {
        return transport.sent.stream().filter(C2S.StrokeBegin.class::isInstance).map(C2S.StrokeBegin.class::cast)
                .toList();
    }

    /** From above, each shape of a drag would stand on the last one; the ray sees the ground as it was instead. */
    @Test
    void aDragLaysShapesOnTheGroundAsItWasEvenBackOverItsOwnShapes() throws ProtocolException {
        Live live = new Live();
        live.flatten();
        live.press(Live.down(-12));
        live.frame(Live.down(-12), 50);
        for (int x = -11; x <= 12; x++) live.dragTo(Live.down(x));
        assertEquals(11, live.dabs().size(), "24 blocks at 2.25 apart: " + live.dabs());
        for (int x = 11; x >= -12; x--) live.dragTo(Live.down(x));
        live.release(Live.down(-12));
        List<Dab> dabs = live.dabs();
        assertTrue(dabs.size() > 18, "back over them too: " + dabs.size());
        for (Dab dab : dabs) {
            assertEquals(16 * (Live.GROUND + 4) + 8, dab.y16(), "resting on the ground: " + dab);
            assertEquals(8, dab.z16());
        }
        assertEquals(1, live.begins(), "one stroke, one undo step");
    }

    /** Mirrored copies are looked through too: the drag passes over the copies of its first shapes. */
    @Test
    void aDragLooksThroughItsSymmetricCopies() throws ProtocolException {
        Live live = new Live();
        live.flatten();
        live.rig.set(ShapeSettings.SYMMETRY, Symmetry.Mode.MIRROR_X);
        live.rig.centre.set(1, 1);
        live.press(Live.down(12));
        live.frame(Live.down(12), 50);
        for (int x = 11; x >= -12; x--) live.dragTo(Live.down(x));
        live.release(Live.down(-12));
        List<Dab> dabs = live.dabs();
        assertEquals(11, dabs.size());
        for (Dab dab : dabs) assertEquals(16 * (Live.GROUND + 4) + 8, dab.y16(), "on the ground, not a copy: " + dab);
        assertTrue(live.target().cells.stream().anyMatch(cell -> cell.x() < -8), "the copies were placed");
    }

    /** Esc mid-drag stops the stroke; what was placed stays, the cursor sees it again, and nothing more goes out. */
    @Test
    void escMidDragStopsAndTheCursorSeesTheShapesAgain() throws ProtocolException {
        Live live = new Live();
        live.flatten();
        live.press(Live.down(-6));
        live.frame(Live.down(-6), 50);
        for (int x = -5; x <= 0; x++) live.dragTo(Live.down(x));
        int placed = live.dabs().size();
        assertTrue(placed >= 2);
        assertTrue(live.rig.tool.onAction(live.rig.view, EditorAction.CANCEL));
        assertNull(live.rig.tool.rayOverlay());
        assertTrue(live.cast(Live.down(0)).pos().y() >= Live.GROUND, "the ray meets the spheres again");
        for (int x = 1; x <= 6; x++) live.dragTo(Live.down(x));
        live.release(Live.down(6));
        assertEquals(placed, live.dabs().size(), "nothing after Esc");
        assertEquals(1, live.begins());
        assertEquals(1, sentOf(live.transport, C2S.StrokeEnd.class), "the stroke ended once");
    }

    /** Each press looks through its own shapes only: a second click on the first shape lands on top of it. */
    @Test
    void theNextPressBuildsOnTheLastPresssShapes() throws ProtocolException {
        Live live = new Live();
        live.flatten();
        for (int press = 0; press < 2; press++) {
            live.press(Live.down(0));
            for (int i = 0; i < 10; i++) live.frame(Live.down(0), 50);
            live.release(Live.down(0));
        }
        List<Dab> dabs = live.dabs();
        assertEquals(2, dabs.size());
        assertEquals(16 * (Live.GROUND + 4) + 8, dabs.get(0).y16());
        assertEquals(16 * (Live.GROUND + 13) + 8, dabs.get(1).y16(), "on the first sphere's top");
        assertEquals(2, live.begins(), "two presses, two undo steps");
    }

    private static int sentOf(FakeTransport transport, Class<? extends C2S> kind) {
        return (int) transport.sent.stream().filter(kind::isInstance).count();
    }

    /** Predicted strokes over the ray-cast cursor: a Fabric session whose predictions land in the rig's world. */
    private final class Live {
        /** The first air above the ground {@link #flatten} makes. */
        static final int GROUND = 62;

        final Rig rig = new Rig();
        final FakeTransport transport = new FakeTransport(states);
        final FabricEditorSession fabric =
                new FabricEditorSession(transport, () -> states, new AtomicLong()::get, "test");

        Live() throws ProtocolException {
            fabric.onJoin();
            fabric.onFrame(Codec.encodeS2C(new S2C.Welcome(ProtocolV2.VERSION, Features.of(Features.STROKES),
                    Limits.DEFAULTS, Perm.mask(EnumSet.allOf(Perm.class)), 1L), states));
            rig.current = fabric;
            rig.services.target = new BrushTerrain.RecordingTarget(rig.world);
        }

        /** The ray from one point through another. */
        static Ray ray(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
            return new Ray(fromX, fromY, fromZ, toX - fromX, toY - fromY, toZ - fromZ);
        }

        /** From a camera high over the line z = 0 to the middle of the ground's top on column (x, 0). */
        static Ray down(int x) {
            return ray(0.5, 120, 0.5, x + 0.5, GROUND, 0.5);
        }

        /** From a camera 1.3 blocks over the ground at the west end, east and down by {@code degrees}. */
        static Ray grazing(double degrees) {
            double a = Math.toRadians(degrees);
            return new Ray(-30.5, GROUND + 1.3, 0.5, Math.cos(a), -Math.sin(a), 0);
        }

        /** Flat stone up to {@link #GROUND}, air above, over the whole terrain. */
        void flatten() {
            int e = BrushTerrain.EXTENT;
            rig.world.fill(new Box(new BlockPos(-e, BrushTerrain.FLOOR, -e), new BlockPos(e, GROUND - 1, e)), stone);
            rig.world.fill(new Box(new BlockPos(-e, GROUND, -e), new BlockPos(e, 110, e)), states.air());
        }

        BrushTerrain.RecordingTarget target() {
            return (BrushTerrain.RecordingTarget) rig.services.target;
        }

        /** Server strokes begun: one per press, one undo step each. */
        int begins() {
            return sentOf(transport, C2S.StrokeBegin.class);
        }

        /** A drag event to {@code ray}, then a tenth of a second of frames. */
        void dragTo(Ray ray) throws ProtocolException {
            drag(ray);
            frame(ray, 100);
        }

        /** The editor's pick for the tool: the ray over the world as the tool's overlay shows it. */
        WorldCursor cast(Ray ray) {
            rig.services.ray = ray;
            return RayCast.terrain(rig.world, rig.tool.rayOverlay(), ray);
        }

        void press(Ray ray) {
            rig.press(cast(ray));
        }

        void drag(Ray ray) {
            rig.drag(cast(ray));
        }

        void release(Ray ray) {
            rig.release(cast(ray));
            fabric.tick();
        }

        /**
         * One frame: the cursor cast over the world as it is now, then a client tick that sends and predicts, and the
         * server reports what was sent written.
         */
        void frame(Ray ray, long millis) throws ProtocolException {
            frameUnwritten(ray, millis);
            ack(fabric, transport);
        }

        /** One frame and tick without the server reporting anything written. */
        void frameUnwritten(Ray ray, long millis) {
            rig.frame(cast(ray), millis);
            fabric.tick();
        }

        List<Dab> dabs() {
            return sentDabs(transport).stream().flatMap(b -> b.dabs().stream()).toList();
        }
    }

    /**
     * The editor's terrain pick without the game: a voxel walk from the ray's origin to the first cell that is not air,
     * water or a plant, over {@code world} as {@code overlay} shows it.
     */
    static final class RayCast {
        private static final double RANGE = 200;

        static WorldCursor terrain(WorldReader world, RayOverlay overlay, Ray ray) {
            int x = (int) Math.floor(ray.originX());
            int y = (int) Math.floor(ray.originY());
            int z = (int) Math.floor(ray.originZ());
            int stepX = ray.dirX() > 0 ? 1 : -1;
            int stepY = ray.dirY() > 0 ? 1 : -1;
            int stepZ = ray.dirZ() > 0 ? 1 : -1;
            double tMaxX = boundary(ray.originX(), ray.dirX());
            double tMaxY = boundary(ray.originY(), ray.dirY());
            double tMaxZ = boundary(ray.originZ(), ray.dirZ());
            double tDeltaX = ray.dirX() == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(ray.dirX());
            double tDeltaY = ray.dirY() == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(ray.dirY());
            double tDeltaZ = ray.dirZ() == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(ray.dirZ());
            double t = 0;
            WorldCursor.Face face = null;
            while (t <= RANGE) {
                if (face != null && solid(world, overlay, x, y, z)) {
                    return new WorldCursor(new BlockPos(x, y, z), face, ray.pointX(t), ray.pointY(t), ray.pointZ(t),
                            false);
                }
                if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                    x += stepX;
                    t = tMaxX;
                    tMaxX += tDeltaX;
                    face = stepX > 0 ? WorldCursor.Face.WEST : WorldCursor.Face.EAST;
                } else if (tMaxY <= tMaxZ) {
                    y += stepY;
                    t = tMaxY;
                    tMaxY += tDeltaY;
                    face = stepY > 0 ? WorldCursor.Face.DOWN : WorldCursor.Face.UP;
                } else {
                    z += stepZ;
                    t = tMaxZ;
                    tMaxZ += tDeltaZ;
                    face = stepZ > 0 ? WorldCursor.Face.NORTH : WorldCursor.Face.SOUTH;
                }
            }
            return WorldCursor.miss(ray.pointX(RANGE), ray.pointY(RANGE), ray.pointZ(RANGE));
        }

        private static double boundary(double origin, double dir) {
            if (dir == 0) return Double.POSITIVE_INFINITY;
            double cell = Math.floor(origin);
            return (dir > 0 ? cell + 1 - origin : origin - cell) / Math.abs(dir);
        }

        private static boolean solid(WorldReader world, RayOverlay overlay, int x, int y, int z) {
            int seen = overlay == null ? RayOverlay.WORLD : overlay.stateAt(x, y, z);
            int state = seen == RayOverlay.WORLD ? world.get(x, y, z) : seen;
            String block = world.states().blockId(state).value();
            return !block.equals("minecraft:air") && !block.equals("minecraft:water")
                    && !block.equals("minecraft:short_grass");
        }
    }

    // ---------------------------------------------------------------- keys

    @Test
    void ctrlScrollSetsTheRadiusAndAltScrollTheHeight() {
        Rig rig = new Rig();
        assertTrue(rig.scroll(1, Modifiers.CONTROL));
        assertEquals(5, rig.setting(ShapeSettings.RADIUS));
        assertTrue(rig.scroll(1, Modifiers.CONTROL | Modifiers.SHIFT));
        assertEquals(9, rig.setting(ShapeSettings.RADIUS));
        assertFalse(rig.scroll(1, Modifiers.ALT), "a sphere has no height");
        rig.set(ShapeSettings.KIND, ShapeSpec.Kind.CYLINDER);
        assertTrue(rig.scroll(1, Modifiers.ALT));
        assertFalse(rig.setting(ShapeSettings.HEIGHT_AUTO), "an explicit height");
        assertEquals(20, rig.setting(ShapeSettings.HEIGHT), "the diameter (19) plus one");
        assertTrue(rig.scroll(-1, Modifiers.ALT));
        assertEquals(19, rig.setting(ShapeSettings.HEIGHT));
        for (int i = 0; i < 30; i++) rig.scroll(-1, Modifiers.ALT);
        assertEquals(1, rig.setting(ShapeSettings.HEIGHT), "at least one");
        for (int i = 0; i < 20; i++) rig.scroll(1, Modifiers.CONTROL | Modifiers.SHIFT);
        assertEquals(32, rig.setting(ShapeSettings.RADIUS), "at most the server's radius");
        for (int i = 0; i < 80; i++) rig.scroll(1, Modifiers.ALT);
        assertEquals(65, rig.setting(ShapeSettings.HEIGHT), "at most the largest diameter");
    }

    @Test
    void middleClickPicksTheActiveBlockOrFillsTheMix() {
        Rig rig = new Rig();
        rig.frame(top(1, 1), 16);
        assertFalse(rig.tool.onAction(rig.view, EditorAction.EYEDROPPER), "the editor sets the active block");
        rig.set(ShapeSettings.BLOCKS, ShapeSettings.Blocks.PALETTE);
        assertTrue(rig.tool.onAction(rig.view, EditorAction.EYEDROPPER));
        List<SettingDef.WeightedBlock> mix = rig.setting(ShapeSettings.PALETTE);
        assertEquals(BlockDescriptor.parse("minecraft:grass_block[snowy=false]"), mix.get(mix.size() - 1).block());
        assertEquals("sculptory.notice.palette_added", rig.noticeKeys().get(rig.noticeKeys().size() - 1));
    }

    @Test
    void theHintLineSaysWhatTheKeysDo() {
        Rig rig = new Rig();
        List<KeyHint> hints = rig.tool.hints(rig.view);
        assertEquals(new KeyHint(ShapeBrushTool.CLICK, "sculptory.hint.shape.place"), hints.get(0));
        assertTrue(hints.contains(new KeyHint("Ctrl+Scroll", "sculptory.hint.brush.radius")));
        assertTrue(hints.stream().noneMatch(h -> h.descriptionKey().equals("sculptory.hint.shape.height")));
        rig.set(ShapeSettings.KIND, ShapeSpec.Kind.CONE);
        assertTrue(rig.tool.hints(rig.view).contains(new KeyHint("Alt+Scroll", "sculptory.hint.shape.height")));
        rig.set(ShapeSettings.MODE, ShapeSpec.Mode.CARVE);
        assertEquals("sculptory.hint.shape.carve", rig.tool.hints(rig.view).get(0).descriptionKey());
        rig.set(ShapeSettings.INSIDE_SELECTION, true);
        assertEquals(KeyHint.text(TerrainBrushTool.NEEDS_SELECTION), rig.tool.hints(rig.view).get(0));
        rig.set(ShapeSettings.SYMMETRY, Symmetry.Mode.MIRROR_X);
        assertTrue(rig.tool.hints(rig.view).contains(new KeyHint("M", "sculptory.hint.brush.symmetry_centre")));
    }

    // ---------------------------------------------------------------- selection and symmetry

    @Test
    void onlyInsideSelectionClipsEveryStrokeOfThePress() {
        Rig rig = new Rig();
        rig.set(ShapeSettings.INSIDE_SELECTION, true);
        rig.press(top(0, 0));
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, TerrainBrushTool.NEEDS_SELECTION)), rig.notices);
        assertFalse(rig.tool.controller().pressed(), "refused without a selection");
        Box selection = new Box(new BlockPos(-3, 40, -3), new BlockPos(3, 90, 3));
        rig.ctx.setSelection(selection);
        rig.clickAt(top(0, 0));
        assertEquals(selection, rig.session.begins.get(0).spec().clip());
    }

    @Test
    void symmetryTravelsInTheSpecAndTheCopiesAreOutlined() {
        Rig rig = new Rig();
        rig.set(ShapeSettings.KIND, ShapeSpec.Kind.CONE);
        rig.set(ShapeSettings.FACING, ShapeSettings.FacingChoice.EAST);
        rig.set(ShapeSettings.SYMMETRY, Symmetry.Mode.MIRROR_X);
        rig.centre.set(1, 1);
        RecordingDraw alone = new RecordingDraw();
        rig.frame(top(8, 0), 16);
        rig.tool.renderWorld(rig.view, alone);
        rig.clickAt(top(8, 0));
        assertEquals(new Symmetry(Symmetry.Mode.MIRROR_X, 1, 1), rig.session.begins.get(0).spec().symmetry());

        // The cursor: the cone's outline and its mirrored copy's, fainter.
        List<ShapeStamp.Placement> placements = ShapeStamp.placements(rig.session.begins.get(0).spec(),
                rig.session.begins.get(0).handle().dabs.get(0));
        assertEquals(2, placements.size());
        assertEquals(Facing.WEST, placements.get(1).facing());
        assertEquals(List.of(placements.get(0).region(), placements.get(1).region()), alone.regions);
        assertEquals(ShapeBrushTool.COLOR, alone.outlineArgb);
        assertEquals(OverlayColors.scaleAlpha(ShapeBrushTool.COLOR, 0.7), alone.copyArgb);
        assertTrue(alone.regions.get(1).bounds().max().x() <= 0, "the copy lies west of the plane x = 0.5");
    }

    // ---------------------------------------------------------------- cursor

    @Test
    void theCursorOutlinesTheExactShapeAClickWouldPlace() {
        Rig rig = new Rig();
        rig.frame(top(0, 0), 16);
        RecordingDraw draw = new RecordingDraw();
        rig.tool.renderWorld(rig.view, draw);
        int y = BrushTerrain.height(0, 0);
        Box box = new Box(new BlockPos(-4, y + 1, -4), new BlockPos(4, y + 9, 4));
        assertEquals(List.of(new Region.Shape(box, ShapeKind.ELLIPSOID, Facing.UP)), draw.regions,
                "the sphere's own region, as the Select tool outlines its shapes");
        assertEquals(ShapeBrushTool.COLOR, draw.outlineArgb);
        // Moving the cursor moves the region (the renderer then reuses its mesh at an offset).
        rig.frame(top(5, 3), 16);
        RecordingDraw moved = new RecordingDraw();
        rig.tool.renderWorld(rig.view, moved);
        Region there = moved.regions.get(0);
        assertEquals(box.sizeY(), there.bounds().sizeY());
        assertEquals(draw.regions.get(0).translate(5, BrushTerrain.height(5, 3) - y, 3), there);

        // A pyramid is its own region; a cube is its box.
        rig.set(ShapeSettings.KIND, ShapeSpec.Kind.PYRAMID);
        RecordingDraw pyramid = new RecordingDraw();
        rig.tool.renderWorld(rig.view, pyramid);
        assertEquals(ShapeKind.PYRAMID, ((Region.Shape) pyramid.regions.get(0)).kind());
        rig.set(ShapeSettings.KIND, ShapeSpec.Kind.CUBE);
        RecordingDraw cube = new RecordingDraw();
        rig.tool.renderWorld(rig.view, cube);
        assertEquals(List.of(), cube.regions);
        assertEquals(1, cube.boxes.size());
        assertEquals(9 * 9 * 9, cube.boxes.get(0).volume());
    }

    @Test
    void aShapeTooLargeToPredictRunsOnTheServerAndIsDrawnFaded() {
        Rig rig = new Rig();
        rig.set(ShapeSettings.RADIUS, 32);
        rig.frame(top(0, 0), 16);
        RecordingDraw draw = new RecordingDraw();
        rig.tool.renderWorld(rig.view, draw);
        assertEquals(OverlayColors.scaleAlpha(ShapeBrushTool.COLOR, 0.45), draw.outlineArgb, "a faded outline");
        rig.clickAt(top(0, 0));
        assertFalse(rig.session.begins.get(0).params().predict(), "143,000 cells is over the 32,768 cap");
        Rig small = new Rig();
        small.set(ShapeSettings.RADIUS, 15);
        small.set(ShapeSettings.HOLLOW, true);
        small.clickAt(top(0, 0));
        assertTrue(small.session.begins.get(0).params().predict(), "a hollow radius-15 sphere is under it");
    }

    // ---------------------------------------------------------------- prediction

    @Test
    void theStrokeIsPredictedExactlyAsTheServerReplaysIt() throws ProtocolException {
        for (Symmetry.Mode mode : List.of(Symmetry.Mode.OFF, Symmetry.Mode.ROTATE_4)) {
            Rig rig = new Rig();
            FakeTransport transport = new FakeTransport(states);
            FabricEditorSession fabric = new FabricEditorSession(transport, () -> states, new AtomicLong()::get, "test");
            fabric.onJoin();
            fabric.onFrame(Codec.encodeS2C(new S2C.Welcome(ProtocolV2.VERSION, Features.of(Features.STROKES),
                    Limits.DEFAULTS, Perm.mask(EnumSet.allOf(Perm.class)), 1L), states));
            rig.current = fabric;
            BrushTerrain.RecordingTarget target = new BrushTerrain.RecordingTarget(rig.world);
            rig.services.target = target;
            rig.set(ShapeSettings.KIND, ShapeSpec.Kind.CYLINDER);
            rig.set(ShapeSettings.RADIUS, 2);
            rig.set(ShapeSettings.FACING, ShapeSettings.FacingChoice.CLICKED_FACE);
            rig.set(ShapeSettings.MODE, ShapeSpec.Mode.PLACE_IN_AIR);
            rig.set(ShapeSettings.SYMMETRY, mode);
            rig.centre.set(1, 1);

            rig.press(top(-14, 2));
            for (int step = 0; step <= 16; step++) {
                WorldCursor at = top(-14 + step, 2 + step % 3);
                rig.drag(at);
                rig.frame(at, 60);
                fabric.tick();
                ack(fabric, transport);
            }
            rig.release(top(2, 3));

            C2S.StrokeBegin begin = transport.sent.stream().filter(C2S.StrokeBegin.class::isInstance)
                    .map(C2S.StrokeBegin.class::cast).findFirst().orElseThrow();
            assertEquals(BrushTool.SHAPE, begin.spec().tool(), "the stroke message carries the Shape brush");
            assertEquals(new ShapeSpec(ShapeSpec.Kind.CYLINDER, 5, Facing.UP, ShapeSpec.Mode.PLACE_IN_AIR, 0),
                    begin.spec().shapeSpec());
            List<Dab> sent = transport.sent.stream().filter(C2S.Dabs.class::isInstance).map(C2S.Dabs.class::cast)
                    .flatMap(b -> b.dabs().stream()).toList();
            assertTrue(sent.size() >= 4, "dabs sent: " + sent.size());
            FakeWorld server = BrushTerrain.world(states);
            List<BrushTerrain.Cell> expected = BrushTerrain.serverReplay(begin.spec(), sent, server);
            assertFalse(expected.isEmpty());
            assertEquals(expected, target.cells, mode + ": the client predicted exactly what the server writes");
            assertArrayEquals(BrushTerrain.snapshot(server), BrushTerrain.snapshot(rig.world));
        }
    }

    @Test
    void theEnglishTextCoversTheTool() throws IOException {
        JsonObject lang;
        try (InputStream in = getClass().getResourceAsStream("/assets/sculptory/lang/en_us.json")) {
            assertNotNull(in);
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        List<String> keys = new ArrayList<>(List.of("sculptory.tool.shape", "sculptory.tool.shape.tooltip",
                "sculptory.hint.shape.place", "sculptory.hint.shape.carve", "sculptory.hint.shape.pick_block",
                "sculptory.hint.shape.height", "sculptory.key.tool_10"));
        for (SettingDef<?> def : ShapeSettings.SCHEMA.defs()) {
            keys.add(def.labelKey());
            if (def instanceof SettingDef.Enum<?> choice) {
                for (Object option : choice.type().getEnumConstants()) {
                    keys.add(def.labelKey() + "." + ((Enum<?>) option).name().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        for (String key : keys) assertTrue(lang.has(key), key);
    }

    private void ack(FabricEditorSession fabric, FakeTransport transport) throws ProtocolException {
        for (int i = transport.sent.size() - 1; i >= 0; i--) {
            if (transport.sent.get(i) instanceof C2S.Dabs dabs) {
                int last = dabs.dabs().get(dabs.dabs().size() - 1).index();
                // Admitted and written (the server reports the Shape brush's written batches).
                fabric.onFrame(Codec.encodeS2C(new S2C.StrokeStatus(dabs.strokeId(), last, S2C.StrokeStatus.Status.OK, null,
                        last), states));
                return;
            }
        }
    }

    // ---------------------------------------------------------------- mix patterns

    /**
     * The Shape brush's mix with a Pattern: the settings show with the mix; Patches
     * and a Gradient go into the spec with the Seed setting; Steepness is greyed out with its reason (the Shape brush
     * doesn't measure the ground's slope); a Gradient without a line places nothing and says so; Alt+drag draws the line
     * the brushes share. With the active block, or carving, Alt places as usual.
     */
    @Test
    void theMixsPatternGoesIntoTheSpecAndAltDragDrawsTheGradientLine() {
        Rig rig = new Rig();
        MixPatternSettings pattern = ShapeSettings.PATTERN;
        assertFalse(rig.values().isVisible(pattern.pattern), "hidden while the active block is placed");
        rig.set(ShapeSettings.BLOCKS, ShapeSettings.Blocks.PALETTE);
        rig.set(ShapeSettings.PALETTE, List.of(new SettingDef.WeightedBlock(ACTIVE, 3),
                new SettingDef.WeightedBlock(BlockDescriptor.parse("minecraft:dirt"), 1)));
        assertTrue(rig.values().isVisible(pattern.pattern));
        assertFalse(rig.values().isVisible(pattern.seed), "Random has no seed setting");
        assertFalse(pattern.pattern.available(PalettePattern.Kind.STEEPNESS));
        assertEquals(MixPatternSettings.STEEPNESS_UNAVAILABLE, pattern.pattern.unavailable().get(PalettePattern.Kind.STEEPNESS));
        assertFalse(pattern.pattern.validate(PalettePattern.Kind.STEEPNESS, null).isValid());

        rig.set(pattern.pattern, PalettePattern.Kind.PATCHES);
        rig.set(pattern.patchSize, 5);
        rig.set(pattern.seed, -3L);
        assertTrue(rig.values().isVisible(pattern.patchSize) && rig.values().isVisible(pattern.seed));
        rig.clickAt(top(2, 2));
        Pattern.Weighted mix = new Pattern.Weighted(new int[] {stone, dirt}, new int[] {3, 1}, -3L);
        assertEquals(new Pattern.Arranged(mix, new MixLayout.Patches(5)), rig.session.begins.get(0).spec().material());

        rig.set(pattern.pattern, PalettePattern.Kind.GRADIENT);
        rig.set(pattern.edge, 7);
        rig.clickAt(top(3, 3));
        assertEquals(1, rig.session.begins.size(), "no line: nothing placed");
        assertTrue(rig.noticeKeys().contains(GradientDrag.NO_LINE));
        WorldCursor from = top(-4, 0), to = top(4, 2);
        assertTrue(rig.tool.onPointer(rig.view, new PointerEvent(PointerEvent.Kind.PRESS, PointerEvent.LEFT, 0, 0,
                Modifiers.ALT, from)));
        rig.drag(to);
        rig.release(to);
        assertEquals(1, rig.session.begins.size(), "the Alt+drag drew, it didn't place");
        assertEquals(from.pos(), rig.centre.gradientLine().from());
        assertEquals(to.pos(), rig.centre.gradientLine().to());
        rig.clickAt(top(0, 1));
        assertEquals(new Pattern.Arranged(mix, new MixLayout.Gradient(from.pos(), to.pos(), 7)),
                rig.session.begins.get(1).spec().material());

        // Carving: Alt+press carves as usual.
        rig.set(ShapeSettings.MODE, ShapeSpec.Mode.CARVE);
        assertTrue(rig.tool.onPointer(rig.view, new PointerEvent(PointerEvent.Kind.PRESS, PointerEvent.LEFT, 0, 0,
                Modifiers.ALT, top(1, 1))));
        rig.frame(top(1, 1), 16);
        rig.release(top(1, 1));
        assertEquals(3, rig.session.begins.size(), "Alt+press carved");
        assertNull(rig.session.begins.get(2).spec().material());
    }

    // ---------------------------------------------------------------- fixtures

    /** The Shape tool, active in an editor context over bumpy terrain and a recording mock session. */
    private final class Rig {
        final FakeWorld world = BrushTerrain.world(states);
        final MockEditorSession mock = new MockEditorSession();
        final RecordingSession session = new RecordingSession(mock);
        EditorSession current = session;
        final List<Notice> notices = new ArrayList<>();
        final FakeServices services = new FakeServices();
        final SymmetryCentre centre = new SymmetryCentre();
        final EditorContext ctx;
        final ShapeBrushTool tool;
        final ToolContext view;
        long now = 1_000 * MS;

        Rig() {
            this(ACTIVE);
        }

        Rig(BlockDescriptor active) {
            EditorBackend backend = new EditorBackend() {
                @Override
                public Optional<EditorSession> session() {
                    return Optional.of(current);
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
            tool = new ShapeBrushTool(services, centre, () -> active);
            ctx.tools().register(tool);
            view = ctx.contextFor(ToolId.SHAPE);
            assertTrue(ctx.tools().activate(ToolId.SHAPE, view));
        }

        void frame(WorldCursor cursor, long advanceMillis) {
            now += advanceMillis * MS;
            tool.frame(view, new FrameInfo(now, 0f, 0, 0, cursor));
        }

        void press(WorldCursor cursor) {
            assertTrue(tool.onPointer(view, new PointerEvent(PointerEvent.Kind.PRESS, PointerEvent.LEFT, 0, 0, 0, cursor)));
        }

        void drag(WorldCursor cursor) {
            tool.onPointer(view, new PointerEvent(PointerEvent.Kind.DRAG, PointerEvent.LEFT, 0, 0, 0, cursor));
        }

        void release(WorldCursor cursor) {
            tool.onPointer(view, new PointerEvent(PointerEvent.Kind.RELEASE, PointerEvent.LEFT, 0, 0, 0, cursor));
        }

        /** A click: press, one frame, release. */
        void clickAt(WorldCursor cursor) {
            press(cursor);
            frame(cursor, 16);
            release(cursor);
        }

        boolean scroll(double amount, int modifiers) {
            return tool.onScroll(view, new ScrollEvent(amount, modifiers));
        }

        <T> void set(SettingDef<T> def, T value) {
            ctx.updateSettings(ToolId.SHAPE, ctx.settings(ToolId.SHAPE).with(def, value));
        }

        SettingsValues values() {
            return ctx.settings(ToolId.SHAPE);
        }

        <T> T setting(SettingDef<T> def) {
            return values().get(def);
        }

        List<String> noticeKeys() {
            return notices.stream().map(Notice::key).toList();
        }
    }

    private static final class FakeServices implements BrushServices {
        final EditorKeymap keymap = EditorKeymap.defaults();
        BrushPredictor.Target target;
        /** The cursor ray, when the test casts one. */
        Ray ray;

        @Override
        public BrushPredictor.Target predictionTarget() {
            return target;
        }

        @Override
        public void showCursor(BrushCursor cursor) {}

        @Override
        public void clearCursor() {}

        @Override
        public long changeStamp() {
            return 0;
        }

        @Override
        public boolean windowFocused() {
            return true;
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

        @Override
        public Optional<Ray> cursorRay() {
            return Optional.ofNullable(ray);
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
        record Line(double x1, double y1, double z1, double x2, double y2, double z2, int color) {}

        final List<Line> lines = new ArrayList<>();
        final List<Box> boxes = new ArrayList<>();
        List<Region> regions = List.of();
        int outlineArgb;
        int copyArgb;
        boolean seeThroughOn;

        @Override
        public void shapeOutlines(List<? extends Region> regions, int argb, int copyArgb) {
            this.regions = List.copyOf(regions);
            this.outlineArgb = argb;
            this.copyArgb = copyArgb;
        }

        @Override
        public void boxOutline(Box box, int argb) {
            boxes.add(box);
        }

        @Override
        public void boxFill(Box box, int argb) {}

        @Override
        public void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb) {
            lines.add(new Line(x1, y1, z1, x2, y2, z2, argb));
        }

        @Override
        public void ring(double centerX, double y, double centerZ, double radius, int argb) {}

        @Override
        public void seeThrough(boolean enabled) {
            if (enabled) seeThroughOn = true;
        }
    }
}
