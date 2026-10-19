package dev.sculptory.fabric.client.editor.tools.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.RoadKernel;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.EditorBackend;
import dev.sculptory.fabric.client.editor.EditorContext;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.mock.MockEditorSession;
import dev.sculptory.fabric.client.editor.render.ghost.GhostPlacement;
import dev.sculptory.fabric.client.editor.render.ghost.GhostVolume;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.FrameInfo;
import dev.sculptory.fabric.client.editor.tool.HudDraw;
import dev.sculptory.fabric.client.editor.tool.KeyHint;
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.ScrollEvent;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.tool.WorldDraw;
import dev.sculptory.fabric.client.editor.world.Ray;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Notice;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Points in the air: the marker floats a set distance ahead over open sky, a block under the cursor wins, Alt+Scroll
 * sets the distance, a point dragged over open sky keeps its distance from the camera; for Generate's Road, Line, Sweep
 * and Stamp. A Road node in the air turns the road's height to Straight, and the road is a deck with no fill under it.
 */
class AirPointsTest {
    private static final long MS = 1_000_000L;
    private static final int GROUND = 63;
    /** The camera: over the flat ground, at a block's centre. */
    private static final double EYE_X = 0.5, EYE_Y = 80.5, EYE_Z = 0.5;
    private static final Function<WorldCursor, BlockPos> ON_BLOCK = WorldCursor::pos;
    private static final int ALT = Modifiers.ALT;

    private final FakeStateSpace states = new FakeStateSpace();

    /** A ray from the camera heading (dx, dy, dz). */
    private static Ray look(double dx, double dy, double dz) {
        return new Ray(EYE_X, EYE_Y, EYE_Z, dx, dy, dz);
    }

    /** The ray met nothing (its end, 512 blocks out). */
    private static WorldCursor sky(Ray ray) {
        return WorldCursor.miss(ray.pointX(512), ray.pointY(512), ray.pointZ(512));
    }

    /** A hit on top of the flat ground at column (x, z). */
    private static WorldCursor top(int x, int z) {
        return new WorldCursor(new BlockPos(x, GROUND, z), WorldCursor.Face.UP, x + 0.5, GROUND + 1, z + 0.5, false);
    }

    // ---------------------------------------------------------------- the marker alone

    @Test
    void theMarkerFloatsSixteenBlocksAheadAndAltScrollMovesItWithinTwoTo256() {
        AirPoints air = new AirPoints();
        assertEquals(AirPoints.DEFAULT_DISTANCE, air.distance());
        assertEquals(16, air.distance());
        Ray east = look(1, 0, 0);
        assertEquals(new BlockPos(16, 80, 0), air.place(sky(east), east, ON_BLOCK), "16 blocks ahead, in its cell");
        assertTrue(air.scroll(new ScrollEvent(1, ALT)));
        assertEquals(17, air.distance(), "a block per notch");
        assertTrue(air.scroll(new ScrollEvent(1, ALT | Modifiers.SHIFT)));
        assertEquals(21, air.distance(), "Shift: four");
        assertTrue(air.scroll(new ScrollEvent(-1, ALT)));
        assertEquals(20, air.distance());
        assertFalse(air.scroll(new ScrollEvent(1, 0)), "plain Scroll is the fly speed");
        assertFalse(air.scroll(new ScrollEvent(1, Modifiers.CONTROL)), "Ctrl+Scroll stays the size");
        assertFalse(air.scroll(new ScrollEvent(1, Modifiers.CONTROL | ALT)));
        assertFalse(air.scroll(new ScrollEvent(0, ALT)));
        assertEquals(20, air.distance());
        for (int i = 0; i < 100; i++) air.scroll(new ScrollEvent(-1, ALT | Modifiers.SHIFT));
        assertEquals(AirPoints.MIN_DISTANCE, air.distance());
        assertEquals(2, air.distance());
        for (int i = 0; i < 100; i++) air.scroll(new ScrollEvent(1, ALT | Modifiers.SHIFT));
        assertEquals(AirPoints.MAX_DISTANCE, air.distance());
        assertEquals(256, air.distance());
        air.setDistance(-5);
        assertEquals(2, air.distance());
    }

    @Test
    void aBlockUnderTheCursorWinsAndWithoutARayTheSkyPlacesNothing() {
        AirPoints air = new AirPoints();
        Ray down = look(0, -1, 0);
        assertEquals(new BlockPos(0, GROUND, 0), air.place(top(0, 0), down, ON_BLOCK), "on the block, as before");
        assertNull(air.place(sky(look(1, 0, 0)), null, ON_BLOCK), "no ray: nothing");
        air.frame(top(0, 0), down, List.of());
        assertNull(air.marker(), "no marker over a block");
        Ray east = look(1, 0, 0);
        air.frame(sky(east), east, List.of());
        assertEquals(new BlockPos(16, 80, 0), air.marker());
        assertTrue(air.labelShown(sky(east)));
        air.frame(sky(east), null, List.of());
        assertNull(air.marker(), "no ray, no marker");
    }

    @Test
    void aPressPicksThePointItsRayPassesNearestUnlessABlockIsNearer() {
        Ray east = look(1, 0, 0);
        List<BlockPos> points = List.of(new BlockPos(30, 80, 0), new BlockPos(10, 80, 0), new BlockPos(10, 90, 0));
        assertEquals(1, AirPoints.pick(points, east, sky(east)), "the nearer of the two on the ray");
        WorldCursor wall = new WorldCursor(new BlockPos(5, 80, 0), WorldCursor.Face.WEST, 5, 80.5, 0.5, false);
        assertEquals(-1, AirPoints.pick(points, east, wall), "a block in front hides them");
        // Just past the cell's edge, within the margin, still picks it.
        Ray grazing = new Ray(EYE_X, EYE_Y + 0.7, EYE_Z, 1, 0, 0);
        assertEquals(1, AirPoints.pick(points, grazing, sky(grazing)));
        Ray past = new Ray(EYE_X, EYE_Y + 0.9, EYE_Z, 1, 0, 0);
        assertEquals(-1, AirPoints.pick(points, past, sky(past)));
        // On a block there is no margin: a ray grazing a point's cell on its way to the block does not pick it.
        WorldCursor far = new WorldCursor(new BlockPos(40, 81, 0), WorldCursor.Face.WEST, 40, 81.2, 0.5, false);
        assertEquals(-1, AirPoints.pick(points, grazing, far), "grazing, then a block: the block's");
        assertEquals(1, AirPoints.pick(points, east, new WorldCursor(new BlockPos(40, 80, 0), WorldCursor.Face.WEST, 40,
                80.5, 0.5, false)), "through the cell itself, then a block: the point");
    }

    @Test
    void aPointHoldingTheCameraIsNotPickedByItsOwnRay() {
        Ray east = look(1, 0, 0);
        assertEquals(-1, AirPoints.pick(List.of(new BlockPos(0, 80, 0)), east, sky(east)), "the camera's own cell");
        Ray near = new Ray(-0.1, EYE_Y, EYE_Z, 1, 0, 0); // within the margin of the cell
        assertEquals(-1, AirPoints.pick(List.of(new BlockPos(0, 80, 0)), near, sky(near)));
        assertEquals(1, AirPoints.pick(List.of(new BlockPos(0, 80, 0), new BlockPos(5, 80, 0)), east, sky(east)),
                "the next point along is picked");
        Ray behind = look(-1, 0, 0);
        assertEquals(-1, AirPoints.pick(List.of(new BlockPos(5, 80, 0)), behind, sky(behind)), "nor one behind it");
    }

    @Test
    void overOpenSkyThePointAClickWouldPickIsHighlightedInsteadOfTheMarker() {
        AirPoints air = new AirPoints();
        air.setDistance(32);
        Ray east = look(1, 0, 0);
        List<BlockPos> points = List.of(new BlockPos(16, 80, 0));
        air.frame(sky(east), east, points);
        assertEquals(new BlockPos(16, 80, 0), air.hovered(), "a click straight ahead picks the point at 16");
        assertNull(air.marker(), "so the marker at 32 hides");
        RecordingDraw draw = new RecordingDraw();
        air.render(draw);
        assertEquals(List.of(Box.of(new BlockPos(16, 80, 0)), Box.of(new BlockPos(16, 80, 0))), draw.boxes);
        Ray beside = look(1, 0.1, 0); // aimed beside it: the marker is back, 32 out
        air.frame(sky(beside), beside, points);
        assertNull(air.hovered());
        assertEquals(AirPoints.cellAt(beside, 32), air.marker());
    }

    @Test
    void theMarkerStaysWithinThePickingRangeAndTheBuildHeight() {
        AirPoints air = new AirPoints();
        air.setDistance(100);
        Ray east = look(1, 0, 0);
        // The ray was tested 64 blocks out (a short render distance): the marker goes no farther.
        WorldCursor tested = WorldCursor.miss(east.pointX(64), east.pointY(64), east.pointZ(64));
        air.frame(tested, east, List.of());
        assertEquals(new BlockPos(64, 80, 0), air.marker());
        assertEquals(64, air.shownDistance());
        assertEquals(100, air.distance(), "the distance set stays");
        assertEquals(new BlockPos(64, 80, 0), air.place(tested, east, ON_BLOCK));
        // Looking straight up near the top of the world: the point stays in the build height.
        FakeWorld world = new FakeWorld(states);
        air.useWorld(world);
        air.setDistance(256);
        Ray up = new Ray(0.5, world.topYExclusive() - 20.5, 0.5, 0, 1, 0);
        BlockPos top = air.place(sky(up), up, ON_BLOCK);
        assertEquals(world.topYExclusive() - 1, top.y(), "the top layer of the world");
        air.frame(sky(up), up, List.of());
        assertEquals(top, air.marker(), "shown where it goes");
        air.beginDrag(up, new BlockPos(0, world.topYExclusive() - 10, 0));
        Ray higher = new Ray(0.5, world.topYExclusive() - 5.5, 0.5, 0, 1, 0);
        assertEquals(world.topYExclusive() - 1, air.withinHeight(AirPoints.cellAt(higher, air.dragDistance())).y(),
                "a dragged point stays in the build height too");
        Ray down = new Ray(0.5, world.bottomY() + 3.5, 0.5, 0, -1, 0);
        air.endDrag();
        assertEquals(world.bottomY(), air.place(sky(down), down, ON_BLOCK).y(), "nor below its floor");
    }

    @Test
    void aDraggedPointKeepsItsDistanceOverOpenSkyAndAltScrollChangesItThere() {
        AirPoints air = new AirPoints();
        Ray east = look(1, 0, 0);
        air.beginDrag(east, new BlockPos(10, 80, 0));
        assertTrue(air.dragging());
        assertEquals(10, air.dragDistance(), 1e-9, "the cell's centre is ten blocks out");
        Ray up = look(1, 1, 0); // looking up 45 degrees: the point rises, as far away as it was
        assertEquals(new BlockPos(7, 87, 0), AirPoints.cellAt(up, air.dragDistance()));
        assertTrue(air.scroll(new ScrollEvent(1, ALT | Modifiers.SHIFT)));
        assertEquals(14, air.dragDistance(), 1e-9, "Alt+Scroll moves the dragged point, not the marker");
        assertEquals(16, air.distance());
        assertEquals(14, air.shownDistance());
        assertEquals(new BlockPos(14, 80, 0), AirPoints.cellAt(east, air.dragDistance()));
        air.endDrag();
        assertFalse(air.dragging());
        assertEquals(16, air.shownDistance());
        assertEquals(new KeyHint("Alt+Scroll", AirPoints.HINT, List.of("16")), air.hint());
    }

    // ---------------------------------------------------------------- Generate's Line

    @Test
    void aLinePointGoesAtTheMarkerOverOpenSkyAndOnTheBlockOverGround() {
        Rig rig = new Rig(GenerateSettings.Kind.LINE);
        rig.click(top(0, 0), look(0, -1, 0));
        Ray east = look(1, 0, 0);
        rig.frame(sky(east), east);
        assertEquals(new BlockPos(16, 80, 0), rig.tool.air().marker(), "the marker shows over open sky");
        RecordingDraw draw = new RecordingDraw();
        rig.tool.renderWorld(rig.view, draw);
        assertTrue(draw.boxes.contains(Box.of(new BlockPos(16, 80, 0))), "drawn");
        assertTrue(rig.tool.hints(rig.view).contains(new KeyHint("Alt+Scroll", AirPoints.HINT, List.of("16"))));
        RecordingHud hud = new RecordingHud();
        rig.tool.renderHud(rig.view, hud);
        assertEquals(List.of(AirPoints.LABEL + "[16]"), hud.texts, "its distance beside the cursor");
        rig.click(sky(east), east);
        assertEquals(List.of(new BlockPos(0, GROUND, 0), new BlockPos(16, 80, 0)), rig.tool.line().points().nodes());
        rig.frame(sky(east), east);
        assertTrue(rig.tool.line().preview().isPresent(), "a line from the ground into the sky");
        // Over the ground the marker goes and the point is on the block again.
        rig.frame(top(2, 3), look(0, -1, 0));
        assertNull(rig.tool.air().marker());
        hud.texts.clear();
        rig.tool.renderHud(rig.view, hud);
        assertTrue(hud.texts.isEmpty());
    }

    @Test
    void altScrollSetsTheDistanceAndCtrlScrollStillTheThickness() {
        Rig rig = new Rig(GenerateSettings.Kind.LINE);
        int thickness = rig.values().get(GenerateSettings.LINE_THICKNESS);
        assertTrue(rig.scroll(1, ALT));
        assertTrue(rig.scroll(1, ALT | Modifiers.SHIFT));
        assertEquals(21, rig.tool.air().distance());
        assertEquals(thickness, rig.values().get(GenerateSettings.LINE_THICKNESS), "Alt+Scroll is not a size key here");
        assertTrue(rig.scroll(1, Modifiers.CONTROL));
        assertEquals(thickness + 1, rig.values().get(GenerateSettings.LINE_THICKNESS));
        assertEquals(21, rig.tool.air().distance());
        Ray east = look(1, 0, 0);
        rig.click(sky(east), east);
        assertEquals(List.of(new BlockPos(21, 80, 0)), rig.tool.line().points().nodes(), "placed at the new distance");
        rig.set(GenerateSettings.KIND, GenerateSettings.Kind.ROOF);
        assertFalse(rig.scroll(1, ALT), "a roof has no points");
    }

    @Test
    void aPointDraggedIntoTheSkyKeepsItsDistanceAndEscPutsItBack() {
        Rig rig = new Rig(GenerateSettings.Kind.LINE);
        Ray east = look(1, 0, 0);
        rig.tool.air().setDistance(10);
        rig.click(sky(east), east);
        rig.click(top(0, 5), look(0, -1, 0.3));
        BlockPos air = new BlockPos(10, 80, 0);
        assertEquals(air, rig.tool.line().points().node(0));
        rig.press(sky(east), east); // the ray passes through the point: it is picked, not a new one
        assertTrue(rig.tool.line().dragging());
        assertEquals(2, rig.tool.line().points().size());
        Ray up = look(1, 1, 0);
        rig.drag(sky(up), up);
        assertEquals(new BlockPos(7, 87, 0), rig.tool.line().points().node(0), "looking up lifts it, ten blocks out");
        assertTrue(rig.scroll(1, ALT | Modifiers.SHIFT));
        assertEquals(new BlockPos(10, 90, 0), rig.tool.line().points().node(0), "Alt+Scroll pushes it out at once");
        assertEquals(10, rig.tool.air().distance(), "the marker's distance stays");
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL));
        assertEquals(air, rig.tool.line().points().node(0), "Esc puts it back");
        assertFalse(rig.tool.line().dragging());
        // Dragged over a block, it never snaps onto it: it stays ten blocks out, straight below the camera.
        rig.press(sky(east), east);
        rig.drag(top(0, 0), look(0, -1, 0));
        rig.release(top(0, 0), look(0, -1, 0));
        assertEquals(new BlockPos(0, 70, 0), rig.tool.line().points().node(0));
    }

    // ---------------------------------------------------------------- Road, Sweep and Stamp

    @Test
    void aRoadNodeInTheAirTurnsTheHeightStraightAndTheRoadIsAFloatingDeck() {
        Rig rig = new Rig(GenerateSettings.Kind.PATH);
        rig.set(GenerateSettings.FILL_BELOW, 3);
        rig.set(GenerateSettings.WIDTH, 3);
        assertEquals(RoadKernel.HeightMode.FOLLOW_TERRAIN, rig.values().get(GenerateSettings.HEIGHT));
        Ray east = look(1, 0, 0);
        rig.click(sky(east), east);
        assertEquals(List.of(new BlockPos(16, 80, 0)), rig.tool.path().nodes());
        assertEquals(RoadKernel.HeightMode.STRAIGHT, rig.values().get(GenerateSettings.HEIGHT));
        assertEquals(List.of(GenerateTool.ROAD_STRAIGHT), rig.noticeKeys());
        Ray north = look(0, 0, -1);
        rig.click(sky(north), north);
        assertEquals(List.of(GenerateTool.ROAD_STRAIGHT), rig.noticeKeys(), "told once: it is Straight now");
        rig.frame(sky(north), north);
        GeneratedSource deck = rig.tool.preview().orElseThrow().source();
        assertFalse(deck.isEmpty());
        deck.forEach((x, y, z, state) -> assertEquals(80, y, "a deck at the nodes' height, nothing hung under it"));
        // A ground node then: the ramp down to it is filled only near the ground.
        rig.click(top(0, 0), look(0, -1, 0));
        rig.frame(top(0, 0), look(0, -1, 0));
        GeneratedSource ramp = rig.tool.preview().orElseThrow().source();
        assertTrue(ramp.contains(0, 60, 0), "at the ground node the fill reaches its full depth (63 down to 60)");
        ramp.forEach((x, y, z, state) -> {
            // From z -16 to -13 the deck stands 76 or more over ground at 63: more than its fill above it.
            if (z <= -13) assertTrue(y >= 76, "nothing hangs under the deck near the air node: " + x + "," + y + "," + z);
        });
        // The centre line is drawn at the nodes' heights, not on the ground far below the deck.
        RecordingDraw draw = new RecordingDraw();
        rig.tool.renderWorld(rig.view, draw);
        assertFalse(draw.lines.isEmpty());
        for (double[] line : draw.lines) assertTrue(line[1] >= GROUND + 1 && line[4] >= GROUND + 1, "drawn at the deck");
        assertTrue(draw.lines.stream().anyMatch(line -> line[1] > 79), "up at the air nodes too");
    }

    @Test
    void aRoadNodeDraggedAcrossTheSkyTurnsNothingAndEscPutsTheHeightBack() {
        Rig rig = new Rig(GenerateSettings.Kind.PATH);
        rig.click(top(0, 0), look(0, -1, 0));
        rig.click(top(6, 0), look(6, -17, 0));
        rig.press(top(6, 0), look(6, -17, 0));
        Ray up = look(1, 0.2, 0);
        rig.drag(sky(up), up);
        rig.drag(top(8, 0), look(8, -17, 0));
        rig.release(top(8, 0), look(8, -17, 0));
        // It keeps its distance from the camera (no snap onto the ground): one cell over the grass there.
        assertEquals(new BlockPos(8, GROUND + 1, 0), rig.tool.path().node(1));
        assertEquals(RoadKernel.HeightMode.FOLLOW_TERRAIN, rig.values().get(GenerateSettings.HEIGHT),
                "passing the sky on its way to the ground turns nothing");
        rig.press(top(8, 0), look(8, -17, 0));
        rig.drag(sky(up), up);
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL));
        assertEquals(new BlockPos(8, GROUND + 1, 0), rig.tool.path().node(1), "Esc puts it back");
        assertEquals(RoadKernel.HeightMode.FOLLOW_TERRAIN, rig.values().get(GenerateSettings.HEIGHT));
        assertTrue(rig.noticeKeys().isEmpty());
        // A node added in the air turns it Straight; Esc clearing the road puts Follow terrain back.
        Ray east = look(1, 0, 0);
        rig.click(sky(east), east);
        assertEquals(RoadKernel.HeightMode.STRAIGHT, rig.values().get(GenerateSettings.HEIGHT));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL), "Esc deselects the new node");
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL), "then clears the road");
        assertTrue(rig.tool.path().isEmpty());
        assertEquals(RoadKernel.HeightMode.FOLLOW_TERRAIN, rig.values().get(GenerateSettings.HEIGHT));
        // Straight set by hand stays when the road is cleared.
        rig.set(GenerateSettings.HEIGHT, RoadKernel.HeightMode.STRAIGHT);
        rig.click(top(0, 0), look(0, -1, 0));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL));
        assertTrue(rig.tool.path().isEmpty());
        assertEquals(RoadKernel.HeightMode.STRAIGHT, rig.values().get(GenerateSettings.HEIGHT));
    }

    @Test
    void aStraightRoadOverUnloadedChunksIsABareDeckThereAndSaysSo() {
        Rig rig = new Rig(GenerateSettings.Kind.PATH);
        rig.set(GenerateSettings.HEIGHT, RoadKernel.HeightMode.STRAIGHT);
        rig.set(GenerateSettings.FILL_BELOW, 2);
        rig.set(GenerateSettings.WIDTH, 1);
        rig.world.setLoaded(1, 0, false); // x 16-31
        rig.click(top(0, 0), look(0, -1, 0));
        rig.click(top(20, 0), look(20, -17, 0));
        rig.frame(top(20, 0), look(20, -17, 0));
        GenerateTool.Built built = rig.tool.preview().orElseThrow();
        assertTrue(built.unloaded().isEmpty(), "not refused");
        assertTrue(built.buildable());
        assertEquals(5, built.bareColumns(), "x 16-20 have no fill");
        assertEquals(List.of(GenerateTool.ROAD_BARE), rig.noticeKeys());
        assertTrue(built.source().contains(10, 61, 0), "a loaded column keeps its fill");
        assertFalse(built.source().contains(18, 62, 0), "an unloaded one is the deck alone");
        rig.frame(top(20, 0), look(20, -17, 0));
        assertEquals(1, rig.noticeKeys().size(), "said once");
    }

    @Test
    void aClickOnAPointWithoutMovingMovesNothing() {
        Rig rig = new Rig(GenerateSettings.Kind.LINE);
        Ray east = look(1, 0, 0);
        rig.click(sky(east), east);
        // Pressed on the point, released over a block with no drag between: the point stays.
        rig.press(sky(east), east);
        rig.release(top(3, 3), look(0, -1, 0.2));
        assertEquals(List.of(new BlockPos(16, 80, 0)), rig.tool.line().points().nodes());
        Rig road = new Rig(GenerateSettings.Kind.PATH);
        road.click(top(0, 0), look(0, -1, 0));
        road.press(top(0, 0), look(0, -1, 0));
        road.release(top(1, 0), look(1, -17, 0));
        assertEquals(List.of(new BlockPos(0, GROUND, 0)), road.tool.path().nodes(), "a road node too");
        Rig stamp = new Rig(GenerateSettings.Kind.STAMP);
        stamp.click(top(0, 0), look(0, -1, 0));
        stamp.press(top(0, 0), look(0, -1, 0));
        stamp.release(top(1, 0), look(1, -17, 0));
        assertEquals(List.of(new BlockPos(0, GROUND, 0)), stamp.tool.stamp().points().nodes(), "and a stamp point");
    }

    @Test
    void aRoadNodeIsPickedAndDraggedThroughTheAir() {
        Rig rig = new Rig(GenerateSettings.Kind.PATH);
        rig.click(top(0, 0), look(0, -1, 0));
        rig.click(top(6, 0), look(6, -17, 0));
        Ray atNode = look(6, -17, 0);
        rig.press(top(6, 0), atNode);
        assertEquals(1, rig.tool.path().selected(), "the ground node is picked as before");
        Ray up = look(1, 0.2, 0);
        rig.drag(sky(up), up);
        BlockPos moved = rig.tool.path().node(1);
        assertTrue(moved.y() > GROUND + 5, "dragged up into the air: " + moved);
        rig.release(sky(up), up);
        assertEquals(RoadKernel.HeightMode.STRAIGHT, rig.values().get(GenerateSettings.HEIGHT));
    }

    @Test
    void sweepAndStampPointsGoInTheAirToo() {
        Rig rig = new Rig(GenerateSettings.Kind.SWEEP);
        Ray east = look(1, 0, 0);
        rig.frame(sky(east), east);
        rig.click(sky(east), east);
        assertEquals(List.of(new BlockPos(16, 80, 0)), rig.tool.sweep().points().nodes());
        rig.set(GenerateSettings.KIND, GenerateSettings.Kind.STAMP);
        rig.frame(sky(east), east);
        assertTrue(rig.scroll(-1, ALT));
        rig.click(sky(east), east);
        assertEquals(List.of(new BlockPos(15, 80, 0)), rig.tool.stamp().points().nodes(), "one marker distance for all");
        rig.press(sky(east), east);
        assertTrue(rig.tool.stamp().dragging(), "a stamp point in the air is picked");
        Ray up = look(1, 1, 0);
        rig.drag(sky(up), up);
        assertEquals(AirPoints.cellAt(up, 15), rig.tool.stamp().points().node(0));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL));
        assertEquals(new BlockPos(15, 80, 0), rig.tool.stamp().points().node(0));
    }

    @Test
    void stampsStackedStraightUpKeepTheTurnOfThePathLeadingUpToThem() {
        Rig rig = new Rig(GenerateSettings.Kind.STAMP);
        PathModel points = rig.tool.stamp().points();
        points.add(new BlockPos(0, GROUND, 0));
        points.add(new BlockPos(10, GROUND, 0));
        points.add(new BlockPos(10, GROUND, 10));
        points.add(new BlockPos(10, 80, 10));
        points.add(new BlockPos(10, 95, 10));
        Orientation south = rig.tool.stamp().orientation(2);
        assertNotEquals(rig.tool.stamp().orientation(0), south, "the path turned south");
        assertEquals(south, rig.tool.stamp().orientation(3), "straight up: the turn it had");
        assertEquals(south, rig.tool.stamp().orientation(4));
        // A column first, then east, then south: the copies up the column take the heading the path levels out to
        // (east), so the last copy is turned the quarter the path turns after it.
        points.clear();
        points.add(new BlockPos(0, GROUND, 0));
        points.add(new BlockPos(0, 80, 0));
        points.add(new BlockPos(0, 90, 0));
        points.add(new BlockPos(10, 90, 0));
        points.add(new BlockPos(10, 90, 10));
        for (int i = 0; i < 3; i++) assertEquals(Orientation.IDENTITY, rig.tool.stamp().orientation(i), "copy " + i);
        assertNotEquals(Orientation.IDENTITY, rig.tool.stamp().orientation(3), "turning south-east");
        assertNotEquals(Orientation.IDENTITY, rig.tool.stamp().orientation(4), "turned from east to south");
    }

    @Test
    void theNewKeysAreTranslated() throws IOException {
        JsonObject lang;
        try (InputStream in = getClass().getResourceAsStream("/assets/sculptory/lang/en_us.json")) {
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        for (String key : List.of(AirPoints.HINT, AirPoints.LABEL, GenerateTool.ROAD_STRAIGHT,
                "sculptory.hint.line.add_point_air")) {
            assertTrue(lang.has(key), key);
        }
    }

    // ---------------------------------------------------------------- fixtures

    private final class Rig {
        final FakeWorld world = new FakeWorld(states);
        final MockEditorSession mock = new MockEditorSession();
        final List<Notice> notices = new ArrayList<>();
        final FakeServices services = new FakeServices();
        final EditorContext ctx;
        final GenerateTool tool;
        final ToolContext view;
        long now = 1_000 * MS;

        Rig(GenerateSettings.Kind kind) {
            int stone = states.state("minecraft:stone");
            int grass = states.state("minecraft:grass_block");
            for (int x = -32; x <= 32; x++) {
                for (int z = -32; z <= 32; z++) {
                    for (int y = 55; y <= GROUND; y++) world.set(x, y, z, y == GROUND ? grass : stone);
                }
            }
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
            set(GenerateSettings.KIND, kind);
        }

        void frame(WorldCursor cursor, Ray ray) {
            services.ray = ray;
            now += 16 * MS;
            tool.frame(view, new FrameInfo(now, 0f, 100, 60, cursor));
        }

        void pointer(PointerEvent.Kind kind, WorldCursor cursor, Ray ray) {
            services.ray = ray;
            tool.onPointer(view, new PointerEvent(kind, PointerEvent.LEFT, 100, 60, 0, cursor));
            tool.frame(view, new FrameInfo(now += 16 * MS, 0f, 100, 60, cursor));
        }

        void press(WorldCursor cursor, Ray ray) {
            pointer(PointerEvent.Kind.PRESS, cursor, ray);
        }

        void drag(WorldCursor cursor, Ray ray) {
            pointer(PointerEvent.Kind.DRAG, cursor, ray);
        }

        void release(WorldCursor cursor, Ray ray) {
            pointer(PointerEvent.Kind.RELEASE, cursor, ray);
        }

        void click(WorldCursor cursor, Ray ray) {
            press(cursor, ray);
            release(cursor, ray);
        }

        boolean scroll(double amount, int modifiers) {
            return tool.onScroll(view, new ScrollEvent(amount, modifiers));
        }

        <T> void set(SettingDef<T> def, T value) {
            ctx.updateSettings(ToolId.GENERATE, ctx.settings(ToolId.GENERATE).with(def, value));
        }

        dev.sculptory.fabric.client.editor.settings.SettingsValues values() {
            return ctx.settings(ToolId.GENERATE);
        }

        List<String> noticeKeys() {
            return notices.stream().map(Notice::key).toList();
        }
    }

    private static final class FakeServices implements GenerateTool.Services {
        Ray ray;

        @Override
        public void showGhosts(List<GhostPlacement> placements) {}

        @Override
        public void releaseGhost(GhostVolume volume) {}

        @Override
        public Executor background() {
            return Runnable::run;
        }

        @Override
        public String keyLabel(KeyAction action) {
            return EditorKeymap.defaults().display(action);
        }

        @Override
        public void confirm(String opNameKey, long blocks, Runnable onConfirm) {
            onConfirm.run();
        }

        @Override
        public Optional<Ray> cursorRay() {
            return Optional.ofNullable(ray);
        }
    }

    private static final class RecordingDraw implements WorldDraw {
        final List<Box> boxes = new ArrayList<>();
        /** The path-coloured lines (the drawn path; the points draw their cubes in their own colours). */
        final List<double[]> lines = new ArrayList<>();

        @Override
        public void boxOutline(Box box, int argb) {
            boxes.add(box);
        }

        @Override
        public void boxFill(Box box, int argb) {}

        @Override
        public void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb) {
            if (argb == GenerateTool.PATH_COLOUR) lines.add(new double[] {x1, y1, z1, x2, y2, z2});
        }

        @Override
        public void ring(double centerX, double y, double centerZ, double radius, int argb) {}

        @Override
        public void seeThrough(boolean enabled) {}

        @Override
        public void shapeOutlines(List<? extends Region> regions, int argb, int copyArgb) {}
    }

    private static final class RecordingHud implements HudDraw {
        final List<String> texts = new ArrayList<>();

        @Override
        public int width() {
            return 854;
        }

        @Override
        public int height() {
            return 480;
        }

        @Override
        public int textWidth(String text) {
            return text.length() * 6;
        }

        @Override
        public void text(String text, int x, int y, int argb) {
            texts.add(text);
        }

        @Override
        public void fill(int x1, int y1, int x2, int y2, int argb) {}
    }
}
