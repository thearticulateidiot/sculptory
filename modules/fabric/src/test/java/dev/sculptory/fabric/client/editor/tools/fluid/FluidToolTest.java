package dev.sculptory.fabric.client.editor.tools.fluid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.ShapeSpec;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.EditorBackend;
import dev.sculptory.fabric.client.editor.EditorContext;
import dev.sculptory.fabric.client.editor.brush.BrushPredictor;
import dev.sculptory.fabric.client.editor.hud.HelpSheet;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.input.KeyChord;
import dev.sculptory.fabric.client.editor.mock.MockEditorSession;
import dev.sculptory.fabric.client.editor.render.BrushCursor;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.FrameInfo;
import dev.sculptory.fabric.client.editor.tool.KeyHint;
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.RayOverlay;
import dev.sculptory.fabric.client.editor.tool.RaycastMode;
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
import dev.sculptory.fabric.client.editor.tools.brush.BrushServices;
import dev.sculptory.fabric.client.editor.tools.brush.SymmetryCentre;
import dev.sculptory.fabric.client.editor.tools.select.SelectionActions;
import dev.sculptory.fabric.client.session.Capabilities;
import dev.sculptory.fabric.client.session.ClipboardCache;
import dev.sculptory.fabric.client.session.EditorSession;
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
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The Fluid tool: slot and key, the settings per mode, hover previews, a click sending the exact op and pattern, a click
 * while a search runs, Esc, limits and symmetry, permissions, the fluid ball delegating to the Shape stroke, presets,
 * hints and English text.
 */
class FluidToolTest {
    private static final long MS = 1_000_000L;
    private static final long SEED = 42L;
    private static final int FLOOR = 60;

    private final FakeStateSpace states = new FakeStateSpace();
    private final int stone = states.state("minecraft:stone");
    private final int water = states.state("minecraft:water[level=0]");
    private final int lava = states.state("minecraft:lava[level=0]");
    private final int dryStairs = states.state("minecraft:oak_stairs[facing=east]");
    private final int wetStairs = states.state("minecraft:oak_stairs[facing=east,waterlogged=true]");

    private static Box box(int x0, int y0, int z0, int x1, int y1, int z1) {
        return new Box(new BlockPos(x0, y0, z0), new BlockPos(x1, y1, z1));
    }

    /** A hit on face {@code face} of block (x, y, z). */
    private static WorldCursor on(int x, int y, int z, WorldCursor.Face face) {
        return new WorldCursor(new BlockPos(x, y, z), face, x + 0.5 + face.dx() * 0.5, y + 0.5 + face.dy() * 0.5,
                z + 0.5 + face.dz() * 0.5, false);
    }

    /** Stone ground up to {@link #FLOOR} over ±120, and a basin with walls to FLOOR + 4 around x, z 1-8. */
    private FakeWorld basin() {
        FakeWorld world = new FakeWorld(states);
        world.fill(box(-120, 40, -120, 120, FLOOR, 120), stone);
        world.fill(box(0, FLOOR + 1, 0, 9, FLOOR + 4, 0), stone);
        world.fill(box(0, FLOOR + 1, 9, 9, FLOOR + 4, 9), stone);
        world.fill(box(0, FLOOR + 1, 0, 0, FLOOR + 4, 9), stone);
        world.fill(box(9, FLOOR + 1, 0, 9, FLOOR + 4, 9), stone);
        return world;
    }

    private FakeWorld pool() {
        FakeWorld world = basin();
        world.fill(box(1, FLOOR + 1, 1, 8, FLOOR + 3, 8), water);
        return world;
    }

    private static Permissions without(Perm... missing) {
        EnumSet<Perm> granted = EnumSet.allOf(Perm.class);
        for (Perm perm : missing) granted.remove(perm);
        return new Permissions(Perm.mask(granted), Limits.DEFAULTS);
    }

    private static Permissions limited(long maxOpVolume, boolean bypass) {
        Permissions all = bypass ? without() : without(Perm.LIMIT_BYPASS);
        return new Permissions(all.mask(), new Limits(maxOpVolume, 2_097_152L, 32, 20, 32L << 20, 2));
    }

    // ---------------------------------------------------------------- registration

    @Test
    void theToolIsPaletteSlotThirteenOnKeyLeftBracket() {
        ToolRegistry registry = new ToolRegistry();
        EditorToolSet.register(registry, new PlaceholderTool(new ToolDescriptor(ToolId.SELECT, "sculptory.tool.select",
                "minecraft:stone", Perm.REGION), "sculptory.hint.select.drag"));
        assertEquals(15, registry.paletteOrder().size(), "Tinker follows in slot 14, Weather in 15");
        assertEquals(15, ToolRegistry.PALETTE_SLOTS);
        Tool fluid = registry.slot(13).orElseThrow();
        assertInstanceOf(FluidTool.class, fluid);
        assertEquals(ToolId.FLUID, fluid.descriptor().id());
        assertEquals(Perm.USE, fluid.descriptor().permission(), "each mode asks for its own node");
        assertEquals("sculptory.tool.fluid", fluid.descriptor().nameKey());
        assertEquals(ToolId.EXTRUDE, registry.slot(12).orElseThrow().descriptor().id(), "slot 12 is the Extrude tool");
        assertEquals(KeyAction.TOOL_13, KeyAction.toolSlot(13));
        assertEquals(13, KeyAction.TOOL_13.toolSlot());
        assertEquals(12, KeyAction.TOOL_12.toolSlot());
        assertEquals(0, KeyAction.TOOL_SIZE.toolSlot());
        assertEquals(List.of(KeyChord.parse("left_bracket")), KeyAction.TOOL_13.defaultChords());
        assertEquals(List.of(KeyChord.parse("equal")), KeyAction.TOOL_12.defaultChords());
        assertEquals("[", EditorKeymap.defaults().display(KeyAction.TOOL_13));
        assertEquals("=", EditorKeymap.defaults().display(KeyAction.TOOL_12));
        assertEquals("1–9, 0, -, =, [", HelpSheet.toolKeys(EditorKeymap.defaults()));
    }

    @Test
    void theSettingsShowPerModeAndFluid() {
        Rig rig = new Rig(basin());
        SettingsValues flood = rig.values();
        assertEquals(FluidSettings.Mode.FLOOD, flood.get(FluidSettings.MODE));
        assertEquals(FluidSettings.Fluid.WATER, flood.get(FluidSettings.FLUID));
        assertTrue(flood.isVisible(FluidSettings.LIMIT));
        assertTrue(flood.isVisible(FluidSettings.WATERLOG_RIM));
        assertFalse(flood.isVisible(FluidSettings.DRAIN_WATERLOGGED));
        assertFalse(flood.isVisible(FluidSettings.RADIUS));
        assertFalse(flood.isVisible(FluidSettings.WATERLOG_BALL));
        assertEquals(RaycastMode.BLOCKS, rig.tool.raycastMode(flood));
        rig.set(FluidSettings.MODE, FluidSettings.Mode.DRAIN);
        SettingsValues drain = rig.values();
        assertTrue(drain.isVisible(FluidSettings.LIMIT));
        assertFalse(drain.isVisible(FluidSettings.WATERLOG_RIM));
        assertTrue(drain.isVisible(FluidSettings.DRAIN_WATERLOGGED));
        assertEquals(RaycastMode.FLUIDS, rig.tool.raycastMode(drain), "Drain aims at fluid surfaces");
        rig.set(FluidSettings.MODE, FluidSettings.Mode.BALL);
        SettingsValues ball = rig.values();
        assertFalse(ball.isVisible(FluidSettings.LIMIT));
        assertTrue(ball.isVisible(FluidSettings.RADIUS));
        assertTrue(ball.isVisible(FluidSettings.WATERLOG_BALL));
        assertEquals(RaycastMode.BLOCKS, rig.tool.raycastMode(ball));
        rig.set(FluidSettings.FLUID, FluidSettings.Fluid.LAVA);
        assertFalse(rig.values().isVisible(FluidSettings.WATERLOG_BALL), "lava cannot waterlog");
        rig.set(FluidSettings.MODE, FluidSettings.Mode.FLOOD);
        assertFalse(rig.values().isVisible(FluidSettings.WATERLOG_RIM));
        assertTrue(rig.values().isVisible(FluidSettings.SYMMETRY));
    }

    // ---------------------------------------------------------------- flood

    @Test
    void hoveringShowsThePocketAndAClickFloodsItWaterloggingTheRim() {
        FakeWorld world = basin();
        world.set(0, FLOOR + 1, 4, dryStairs); // in the wall: on the rim
        world.set(3, FLOOR + 1, 3, wetStairs); // inside: already wet, not written
        Rig rig = new Rig(world);
        rig.hover(on(4, FLOOR, 4, WorldCursor.Face.UP));
        FluidSearch found = rig.tool.found().orElseThrow();
        assertTrue(found.done());
        assertEquals(8 * 8 - 1 + 1, found.count(), "the floor layer of the basin less the wet stairs, plus the wall's stairs");
        assertTrue(found.contains(0, FLOOR + 1, 4));
        assertFalse(found.contains(3, FLOOR + 1, 3));
        RecordingDraw draw = new RecordingDraw();
        rig.tool.renderWorld(rig.view, draw);
        assertEquals(1, draw.regions.size());
        assertInstanceOf(Region.Cells.class, draw.regions.get(0));
        assertEquals(64, ((Region.Cells) draw.regions.get(0)).cells().size());
        assertEquals(FluidTool.COLOUR, draw.outlineArgb);
        assertTrue(draw.boxes.contains(found.cells().bounds()), "the cells' bounds frame the outline: " + draw.boxes);
        RecordingDraw again = new RecordingDraw();
        rig.tool.renderWorld(rig.view, again);
        assertSame(draw.regions.get(0), again.regions.get(0),
                "the same region instance every frame: the outline mesher tells cell sets apart by identity");
        List<KeyHint> hints = rig.tool.hints(rig.view);
        assertEquals(new KeyHint(FluidTool.CLICK, "sculptory.hint.fluid.flood", List.of("64")), hints.get(0));

        rig.click(on(4, FLOOR, 4, WorldCursor.Face.UP));
        OpSpec.Fill fill = rig.onlyFill();
        assertEquals(new Pattern.Waterlog(water), fill.pattern());
        assertEquals(CellMask.ANY, fill.mask());
        assertEquals(Symmetry.NONE, fill.symmetry());
        Region.Cells region = assertInstanceOf(Region.Cells.class, fill.region());
        assertEquals(found.cells(), region.cells());
        assertTrue(rig.noticeKeys().contains(FluidTool.FLOOD_SENT), rig.noticeKeys().toString());
        rig.mock.finishJobs();
        assertEquals(OpLabel.FLOOD, rig.lastRun().label(), "named after the tool, for the history entry");
        assertEquals("Flood", rig.mock.history().undoLabel(), "one undo step, named Flood");
        assertTrue(rig.tool.found().isEmpty(), "the cells are about to change: the preview is dropped until they show");
        rig.frame(on(4, FLOOR, 4, WorldCursor.Face.UP));
        assertTrue(rig.tool.found().isEmpty() && rig.tool.searching().isEmpty(), "not searched again at once");
        rig.services.advance(FluidTool.REFRESH_AFTER_NANOS);
        rig.hover(on(4, FLOOR, 4, WorldCursor.Face.UP));
        assertEquals(64, rig.tool.found().orElseThrow().count(), "searched again a second later (the mock world is unchanged)");
    }

    @Test
    void withoutWaterloggingOnlyAirIsFloodedStillWithWaterlog() {
        FakeWorld world = basin();
        world.set(0, FLOOR + 1, 4, dryStairs);
        Rig rig = new Rig(world);
        rig.set(FluidSettings.WATERLOG_RIM, false);
        rig.hover(on(3, FLOOR, 3, WorldCursor.Face.UP));
        assertEquals(64, rig.tool.found().orElseThrow().count());
        rig.click(on(3, FLOOR, 3, WorldCursor.Face.UP));
        assertEquals(new Pattern.Waterlog(water), rig.onlyFill().pattern(), "Waterlog still: a block placed meanwhile stays");

        Rig lavaRig = new Rig(world);
        lavaRig.set(FluidSettings.FLUID, FluidSettings.Fluid.LAVA);
        lavaRig.hover(on(3, FLOOR, 3, WorldCursor.Face.UP));
        assertEquals(64, lavaRig.tool.found().orElseThrow().count(), "lava never takes the rim");
        lavaRig.click(on(3, FLOOR, 3, WorldCursor.Face.UP));
        assertEquals(new Pattern.Waterlog(lava), lavaRig.onlyFill().pattern(), "lava fills air only through Waterlog too");
    }

    @Test
    void aimingAtAWallFloodsUpToThatLevel() {
        Rig rig = new Rig(basin());
        rig.hover(on(0, FLOOR + 2, 4, WorldCursor.Face.EAST));
        FluidFlood flood = assertInstanceOf(FluidFlood.class, rig.tool.found().orElseThrow());
        assertEquals(FLOOR + 2, flood.level());
        assertEquals(128, flood.count(), "two layers");
        rig.hover(on(0, FLOOR + 4, 4, WorldCursor.Face.EAST));
        assertEquals(256, rig.tool.found().orElseThrow().count(), "a new seed starts a new search");
    }

    @Test
    void nothingToFloodOrDrainSaysSo() {
        Rig rig = new Rig(basin());
        rig.hover(on(3, FLOOR, 3, WorldCursor.Face.DOWN));
        assertEquals(0, rig.tool.found().orElseThrow().count(), "the cell under the floor is stone");
        assertEquals(KeyHint.text("sculptory.hint.fluid.nothing_flood"), rig.tool.hints(rig.view).get(0));
        rig.click(on(3, FLOOR, 3, WorldCursor.Face.DOWN));
        assertTrue(rig.sentOps().isEmpty());
        assertEquals(List.of(FluidTool.NOTHING_TO_FLOOD), rig.noticeKeys());
        RecordingDraw draw = new RecordingDraw();
        rig.tool.renderWorld(rig.view, draw);
        assertTrue(draw.regions.isEmpty(), "nothing to outline");

        rig.set(FluidSettings.MODE, FluidSettings.Mode.DRAIN);
        rig.hover(on(3, FLOOR, 3, WorldCursor.Face.UP));
        assertEquals(0, rig.tool.found().orElseThrow().count(), "stone holds no fluid");
        rig.click(on(3, FLOOR, 3, WorldCursor.Face.UP));
        assertTrue(rig.sentOps().isEmpty());
        assertEquals(FluidTool.NOTHING_TO_DRAIN, rig.noticeKeys().get(1));
        Rig fresh = new Rig(basin());
        assertEquals(KeyHint.text("sculptory.hint.fluid.aim_flood"), fresh.tool.hints(fresh.view).get(0),
                "before anything is aimed at");
    }

    // ---------------------------------------------------------------- drain

    @Test
    void drainFindsTheBodyOfWaterAndAClickDriesIt() {
        FakeWorld world = pool();
        world.set(1, FLOOR + 1, 1, wetStairs);
        Rig rig = new Rig(world);
        rig.set(FluidSettings.MODE, FluidSettings.Mode.DRAIN);
        rig.hover(on(4, FLOOR + 3, 4, WorldCursor.Face.UP));
        FluidDrain drain = assertInstanceOf(FluidDrain.class, rig.tool.found().orElseThrow());
        assertTrue(drain.water());
        assertEquals(8 * 8 * 3, drain.count(), "every water cell and the wet stairs");
        assertEquals(new KeyHint(FluidTool.CLICK, "sculptory.hint.fluid.drain", List.of("192")), rig.tool.hints(rig.view).get(0));
        rig.click(on(4, FLOOR + 3, 4, WorldCursor.Face.UP));
        OpSpec.Fill fill = rig.onlyFill();
        assertEquals(new Pattern.Dry(), fill.pattern());
        assertEquals(OpLabel.DRAIN, rig.lastRun().label());
        assertEquals(192, ((Region.Cells) fill.region()).cells().size());
        assertTrue(rig.noticeKeys().contains(FluidTool.DRAIN_SENT));

        Rig plain = new Rig(world);
        plain.set(FluidSettings.MODE, FluidSettings.Mode.DRAIN);
        plain.set(FluidSettings.DRAIN_WATERLOGGED, false);
        plain.hover(on(4, FLOOR + 3, 4, WorldCursor.Face.UP));
        assertEquals(191, plain.tool.found().orElseThrow().count(), "the stairs are left alone");
    }

    // ---------------------------------------------------------------- searches over frames

    @Test
    void aLargeSearchSpreadsOverFramesAndAClickCommitsWhenItLands() {
        Rig rig = new Rig(basin());
        rig.ctx.setSelection(box(20, FLOOR + 1, 20, 119, FLOOR + 1, 119)); // 10,000 cells of open air, bounded by it
        rig.services.nanosPerCall = 3 * MS; // every clock read passes the frame budget: one batch per frame
        rig.frame(on(50, FLOOR, 50, WorldCursor.Face.UP));
        assertTrue(rig.tool.searching().isPresent(), "not done in one frame");
        assertTrue(rig.tool.found().isEmpty());
        List<KeyHint> hints = rig.tool.hints(rig.view);
        assertEquals("sculptory.hint.fluid.searching", hints.get(0).descriptionKey());
        assertEquals(new KeyHint("Esc", "sculptory.hint.fluid.stop"), hints.get(1));
        rig.click(on(50, FLOOR, 50, WorldCursor.Face.UP));
        assertTrue(rig.sentOps().isEmpty(), "nothing is sent before the search is done");
        RecordingDraw draw = new RecordingDraw();
        rig.tool.renderWorld(rig.view, draw);
        assertEquals(1, draw.regions.size(), "the cells found so far are outlined");
        int frames = 0;
        while (rig.tool.searching().isPresent()) {
            rig.frame(on(50, FLOOR, 50, WorldCursor.Face.UP));
            assertTrue(++frames < 10_000);
        }
        assertTrue(frames > 5, "the search took several frames: " + frames);
        OpSpec.Fill fill = rig.onlyFill();
        assertEquals(10_000, ((Region.Cells) fill.region()).cells().size());
        assertTrue(rig.noticeKeys().contains(FluidTool.FLOOD_SENT));
    }

    @Test
    void escDropsARunningSearch() {
        Rig rig = new Rig(basin());
        rig.ctx.setSelection(box(20, FLOOR + 1, 20, 119, FLOOR + 1, 119));
        rig.services.nanosPerCall = 3 * MS;
        rig.frame(on(50, FLOOR, 50, WorldCursor.Face.UP));
        assertTrue(rig.tool.searching().isPresent());
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL));
        assertTrue(rig.tool.searching().isEmpty());
        rig.frame(on(50, FLOOR, 50, WorldCursor.Face.UP));
        assertTrue(rig.tool.searching().isEmpty(), "not started again until the cursor moves");
        assertFalse(rig.tool.onAction(rig.view, EditorAction.CANCEL), "nothing left to cancel");
        rig.click(on(50, FLOOR, 50, WorldCursor.Face.UP));
        assertTrue(rig.sentOps().isEmpty());
        assertEquals(List.of(FluidTool.NOTHING_TO_FLOOD), rig.noticeKeys());
        rig.services.nanosPerCall = 0;
        rig.hover(on(51, FLOOR, 50, WorldCursor.Face.UP));
        assertEquals(10_000, rig.tool.found().orElseThrow().count(), "a moved cursor searches again");
    }

    // ---------------------------------------------------------------- limits, symmetry, permissions

    @Test
    void theLimitAndTheServersCapsStopTheSearchAndTheClickSaysSo() {
        Rig rig = new Rig(basin());
        rig.set(FluidSettings.LIMIT, 20);
        rig.hover(on(3, FLOOR, 3, WorldCursor.Face.UP));
        FluidSearch found = rig.tool.found().orElseThrow();
        assertEquals(20, found.count());
        assertTrue(found.hitLimit());
        assertTrue(rig.tool.hints(rig.view).contains(KeyHint.text("sculptory.hint.fluid.limit", "20")));
        rig.click(on(3, FLOOR, 3, WorldCursor.Face.UP));
        assertEquals(20, ((Region.Cells) rig.onlyFill().region()).cells().size());
        assertEquals(List.of(FluidTool.LIMIT, FluidTool.FLOOD_SENT), rig.noticeKeys());

        Rig capped = new Rig(basin());
        capped.mock.setPermissions(limited(10, false));
        capped.set(FluidSettings.LIMIT, 20);
        capped.hover(on(3, FLOOR, 3, WorldCursor.Face.UP));
        assertEquals(10, capped.tool.found().orElseThrow().count(), "the server's op limit caps the search");
        Rig bypass = new Rig(basin());
        bypass.mock.setPermissions(limited(10, true));
        bypass.set(FluidSettings.LIMIT, 20);
        bypass.hover(on(3, FLOOR, 3, WorldCursor.Face.UP));
        assertEquals(20, bypass.tool.found().orElseThrow().count(), "unless the player bypasses limits");
        bypass.click(on(3, FLOOR, 3, WorldCursor.Face.UP));
        assertEquals(1, bypass.sentOps().size());
    }

    @Test
    void unloadedChunksStopTheSearchAndTheClickSaysSo() {
        FakeWorld world = basin();
        world.setLoaded(0, 0, false);
        Rig rig = new Rig(world);
        rig.hover(on(20, FLOOR, 20, WorldCursor.Face.UP));
        rig.ctx.setSelection(box(10, FLOOR + 1, 10, 25, FLOOR + 1, 25));
        rig.hover(on(20, FLOOR, 20, WorldCursor.Face.UP));
        FluidSearch found = rig.tool.found().orElseThrow();
        assertTrue(found.hitUnloaded());
        assertEquals(256 - 6 * 6, found.count(), "the columns of the selection outside the unloaded chunk");
        assertTrue(rig.tool.hints(rig.view).contains(KeyHint.text("sculptory.hint.fluid.unloaded")));
        rig.click(on(20, FLOOR, 20, WorldCursor.Face.UP));
        assertEquals(List.of(FluidTool.UNLOADED, FluidTool.FLOOD_SENT), rig.noticeKeys());
    }

    @Test
    void symmetryCopiesTheOpAndNeedsACentre() {
        Rig rig = new Rig(basin());
        rig.set(FluidSettings.SYMMETRY, Symmetry.Mode.MIRROR_X);
        rig.hover(on(3, FLOOR, 3, WorldCursor.Face.UP));
        rig.click(on(3, FLOOR, 3, WorldCursor.Face.UP));
        assertTrue(rig.sentOps().isEmpty());
        assertEquals(List.of(SelectionActions.SYMMETRY_CENTRE_NEEDED), rig.noticeKeys());
        assertTrue(rig.tool.hints(rig.view).contains(KeyHint.text("sculptory.hint.symmetry_centre_needed", "M")));
        rig.centre.set(2 * 4 + 1, 2 * 4 + 1);
        assertEquals(new KeyHint(FluidTool.CLICK, "sculptory.hint.fluid.flood_copies", List.of("64", "2")),
                rig.tool.hints(rig.view).get(0));
        rig.mock.setPermissions(limited(100, false));
        rig.click(on(3, FLOOR, 3, WorldCursor.Face.UP));
        assertTrue(rig.sentOps().isEmpty(), "64 cells × 2 copies is over the limit");
        assertEquals("sculptory.notice.too_large", rig.noticeKeys().get(1));
        rig.mock.setPermissions(without());
        rig.click(on(3, FLOOR, 3, WorldCursor.Face.UP));
        OpSpec.Fill fill = rig.onlyFill();
        assertEquals(new Symmetry(Symmetry.Mode.MIRROR_X, 9, 9), fill.symmetry());
        assertEquals(64, ((Region.Cells) fill.region()).cells().size());
        RecordingDraw draw = new RecordingDraw();
        rig.tool.renderWorld(rig.view, draw);
        assertFalse(draw.lines.isEmpty(), "the mirror plane is drawn");
    }

    @Test
    void withoutRegionPermissionAClickIsRefused() {
        Rig rig = new Rig(basin());
        rig.mock.setPermissions(without(Perm.REGION));
        rig.hover(on(3, FLOOR, 3, WorldCursor.Face.UP));
        rig.click(on(3, FLOOR, 3, WorldCursor.Face.UP));
        assertTrue(rig.sentOps().isEmpty());
        assertEquals(List.of(new Notice(Notice.Level.WARNING, "sculptory.notice.needs_permission",
                List.of("sculptory.region"))), rig.notices);
        rig.mock.setPermissions(without());
        rig.mock.rejectNextOp(dev.sculptory.protocol.v2.RejectReason.PROTECTED);
        rig.click(on(3, FLOOR, 3, WorldCursor.Face.UP));
        assertEquals(1, rig.sentOps().size());
        assertEquals("sculptory.reject.protected", rig.noticeKeys().get(1), "the server's refusal is toasted");
    }

    // ---------------------------------------------------------------- the fluid ball

    @Test
    void theBallIsAShapeStrokeOfTheFluid() {
        Rig rig = new Rig(basin());
        rig.set(FluidSettings.MODE, FluidSettings.Mode.BALL);
        rig.press(on(20, FLOOR, 20, WorldCursor.Face.UP));
        rig.frame(on(20, FLOOR, 20, WorldCursor.Face.UP));
        rig.release(on(20, FLOOR, 20, WorldCursor.Face.UP));
        assertEquals(1, rig.session.begins.size());
        RecordingSession.Begin begin = rig.session.begins.get(0);
        assertEquals(ToolId.FLUID, begin.tool(), "the stroke is the Fluid tool's");
        assertEquals(BrushSpec.shape(4, new ShapeSpec(ShapeSpec.Kind.SPHERE, 9, Facing.UP, ShapeSpec.Mode.PLACE, 0),
                new Pattern.Waterlog(water), SEED, null, Symmetry.NONE), begin.spec());
        assertTrue(begin.params().predict());
        assertEquals(1, begin.handle().dabs.size());
        assertTrue(begin.handle().ended);
        assertEquals("Fluid", rig.mock.history().undoLabel(), "one undo step");
        assertTrue(rig.sentOps().isEmpty(), "a ball is a stroke, not an op");

        rig.set(FluidSettings.WATERLOG_BALL, false);
        rig.ballClick(on(20, FLOOR, 20, WorldCursor.Face.UP));
        BrushSpec plain = rig.session.begins.get(1).spec();
        assertEquals(ShapeSpec.Mode.PLACE_IN_AIR, plain.shapeSpec().mode(), "air only with the plain fluid");
        assertEquals(new Pattern.Single(water), plain.material());

        rig.set(FluidSettings.FLUID, FluidSettings.Fluid.LAVA);
        rig.set(FluidSettings.WATERLOG_BALL, true);
        rig.ballClick(on(20, FLOOR, 20, WorldCursor.Face.UP));
        BrushSpec lavaBall = rig.session.begins.get(2).spec();
        assertEquals(ShapeSpec.Mode.PLACE_IN_AIR, lavaBall.shapeSpec().mode(), "lava never waterlogs");
        assertEquals(new Pattern.Single(lava), lavaBall.material());
    }

    @Test
    void ctrlScrollChangesTheBallRadiusAndTheDragPaintsBalls() {
        Rig rig = new Rig(basin());
        assertFalse(rig.scroll(1, Modifiers.CONTROL), "Flood has no size");
        rig.set(FluidSettings.MODE, FluidSettings.Mode.BALL);
        assertTrue(rig.scroll(1, Modifiers.CONTROL));
        assertEquals(5, rig.setting(FluidSettings.RADIUS));
        assertTrue(rig.scroll(1, Modifiers.CONTROL | Modifiers.SHIFT));
        assertEquals(9, rig.setting(FluidSettings.RADIUS), "Shift steps by four");
        rig.press(on(20, FLOOR, 20, WorldCursor.Face.UP));
        rig.frame(on(20, FLOOR, 20, WorldCursor.Face.UP));
        rig.drag(on(60, FLOOR, 20, WorldCursor.Face.UP));
        rig.frame(on(60, FLOOR, 20, WorldCursor.Face.UP));
        rig.release(on(60, FLOOR, 20, WorldCursor.Face.UP));
        RecordingSession.Begin begin = rig.session.begins.get(0);
        assertEquals(9, begin.spec().radius());
        assertTrue(begin.handle().dabs.size() > 1, "a drag lays several balls: " + begin.handle().dabs.size());
        assertEquals(1, rig.session.begins.size(), "one stroke, one undo step");
        List<KeyHint> hints = rig.tool.hints(rig.view);
        assertEquals(new KeyHint(FluidTool.CLICK, "sculptory.hint.fluid.ball", List.of("water")), hints.get(0));
        assertTrue(hints.contains(new KeyHint("Ctrl+Scroll", "sculptory.hint.brush.radius")));
        RecordingDraw draw = new RecordingDraw();
        rig.frame(on(20, FLOOR, 20, WorldCursor.Face.UP));
        rig.tool.renderWorld(rig.view, draw);
        assertEquals(1, draw.regions.size(), "the sphere a click would place is outlined");
        assertInstanceOf(Region.Shape.class, draw.regions.get(0));
    }

    /** The ball's press looks through the balls it placed, as the Shape brush's does; Flood and Drain see the world. */
    @Test
    void theBallsCursorLooksThroughTheBallsOfItsPress() {
        Rig rig = new Rig(basin());
        WorldCursor at = on(20, FLOOR, 20, WorldCursor.Face.UP);
        rig.frame(at);
        assertNull(rig.tool.rayOverlay(), "Flood sees the world");
        rig.set(FluidSettings.MODE, FluidSettings.Mode.BALL);
        rig.press(at);
        assertNull(rig.tool.rayOverlay(), "no ball out yet");
        rig.frame(at);
        RayOverlay overlay = rig.tool.rayOverlay();
        assertNotNull(overlay, "the first ball went out");
        int before = rig.world.get(20, FLOOR + 2, 20);
        rig.world.set(20, FLOOR + 2, 20, water); // the ball, predicted or written by the server
        assertEquals(before, overlay.stateAt(20, FLOOR + 2, 20), "the cursor sees the cell as it was");
        rig.release(at);
        assertNull(rig.tool.rayOverlay(), "after the press, the ball is seen");
    }

    @Test
    void leavingBallModeMidPressEndsTheStrokeAndEscStopsIt() {
        Rig rig = new Rig(basin());
        rig.set(FluidSettings.MODE, FluidSettings.Mode.BALL);
        rig.press(on(20, FLOOR, 20, WorldCursor.Face.UP));
        rig.frame(on(20, FLOOR, 20, WorldCursor.Face.UP));
        assertTrue(rig.tool.ball().controller().pressed());
        assertEquals(List.of(new KeyHint("Esc", "sculptory.hint.cancel_drag")), rig.tool.hints(rig.view).subList(0, 1));
        rig.set(FluidSettings.MODE, FluidSettings.Mode.FLOOD);
        assertFalse(rig.tool.ball().controller().pressed(), "the press ended with the mode");
        assertTrue(rig.session.begins.get(0).handle().ended);

        rig.set(FluidSettings.MODE, FluidSettings.Mode.BALL);
        rig.press(on(20, FLOOR, 20, WorldCursor.Face.UP));
        rig.frame(on(20, FLOOR, 20, WorldCursor.Face.UP));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL));
        assertFalse(rig.tool.ball().controller().pressed());
        assertEquals(2, rig.session.begins.size());
    }

    @Test
    void withoutBrushPermissionTheBallIsRefused() {
        Rig rig = new Rig(basin());
        rig.set(FluidSettings.MODE, FluidSettings.Mode.BALL);
        rig.mock.setPermissions(without(Perm.BRUSH));
        assertTrue(rig.tool.onPointer(rig.view, new PointerEvent(PointerEvent.Kind.PRESS, PointerEvent.LEFT, 0, 0, 0,
                on(20, FLOOR, 20, WorldCursor.Face.UP))));
        assertTrue(rig.session.begins.isEmpty());
        assertEquals(List.of("sculptory.notice.needs_permission"), rig.noticeKeys());
        assertFalse(rig.tool.ball().controller().pressed());
    }

    /**
     * A player with {@code brush} but not {@code region} can pick the tool (its descriptor needs only {@code use}) and
     * paint balls; Flood and Drain toast for {@code region} at the click and send nothing.
     */
    @Test
    void aBrushOnlyPlayerPicksTheToolPaintsBallsAndIsToldFloodNeedsRegion() {
        Rig rig = new Rig(basin());
        Permissions brushOnly = without(Perm.REGION);
        rig.mock.setPermissions(brushOnly);
        assertTrue(brushOnly.has(rig.tool.descriptor().permission()), "the palette lets a brush-only player pick it");
        assertFalse(without(Perm.USE).has(rig.tool.descriptor().permission()), "but not without use");

        rig.set(FluidSettings.MODE, FluidSettings.Mode.BALL);
        rig.ballClick(on(20, FLOOR, 20, WorldCursor.Face.UP));
        assertEquals(1, rig.session.begins.size(), "the ball needs brush only");
        assertTrue(rig.notices.isEmpty());

        List<Notice> needsRegion = List.of(new Notice(Notice.Level.WARNING, "sculptory.notice.needs_permission",
                List.of("sculptory.region")));
        rig.set(FluidSettings.MODE, FluidSettings.Mode.FLOOD);
        rig.hover(on(3, FLOOR, 3, WorldCursor.Face.UP));
        rig.click(on(3, FLOOR, 3, WorldCursor.Face.UP));
        assertTrue(rig.sentOps().isEmpty(), "Flood sends nothing");
        assertEquals(needsRegion, rig.notices, "Flood toasts region");

        Rig drain = new Rig(pool());
        drain.mock.setPermissions(brushOnly);
        drain.set(FluidSettings.MODE, FluidSettings.Mode.DRAIN);
        drain.hover(on(4, FLOOR + 3, 4, WorldCursor.Face.UP));
        drain.click(on(4, FLOOR + 3, 4, WorldCursor.Face.UP));
        assertTrue(drain.sentOps().isEmpty(), "Drain sends nothing");
        assertEquals(needsRegion, drain.notices, "Drain toasts region");
    }

    // ---------------------------------------------------------------- presets and text

    @Test
    void presetsRoundTripEverySetting() {
        Rig rig = new Rig(basin());
        rig.set(FluidSettings.MODE, FluidSettings.Mode.DRAIN);
        rig.set(FluidSettings.FLUID, FluidSettings.Fluid.LAVA);
        rig.set(FluidSettings.LIMIT, 5_000);
        rig.set(FluidSettings.WATERLOG_RIM, false);
        rig.set(FluidSettings.DRAIN_WATERLOGGED, false);
        rig.set(FluidSettings.RADIUS, 9);
        rig.set(FluidSettings.WATERLOG_BALL, false);
        rig.set(FluidSettings.SYMMETRY, Symmetry.Mode.MIRROR_Z);
        SettingsValues values = rig.values();
        assertEquals(values, SettingsValues.decode(FluidSettings.SCHEMA, values.encode()));
        assertEquals(SettingsValues.defaults(FluidSettings.SCHEMA), SettingsValues.decode(FluidSettings.SCHEMA,
                SettingsValues.defaults(FluidSettings.SCHEMA).encode()));
    }

    @Test
    void theEnglishTextCoversTheTool() throws IOException {
        JsonObject lang;
        try (InputStream in = getClass().getResourceAsStream("/assets/sculptory/lang/en_us.json")) {
            assertNotNull(in);
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        List<String> keys = new ArrayList<>(List.of("sculptory.tool.fluid", "sculptory.tool.fluid.tooltip",
                "sculptory.key.tool_12", "sculptory.key.tool_13", FluidTool.OP_FLOOD,
                FluidTool.OP_DRAIN, FluidTool.NOTHING_TO_FLOOD, FluidTool.NOTHING_TO_DRAIN, FluidTool.LIMIT, FluidTool.UNLOADED,
                FluidTool.FLOOD_SENT, FluidTool.DRAIN_SENT, "sculptory.hint.fluid.aim_flood", "sculptory.hint.fluid.aim_drain",
                "sculptory.hint.fluid.searching", "sculptory.hint.fluid.stop", "sculptory.hint.fluid.flood",
                "sculptory.hint.fluid.flood_copies", "sculptory.hint.fluid.drain", "sculptory.hint.fluid.drain_copies",
                "sculptory.hint.fluid.nothing_flood", "sculptory.hint.fluid.nothing_drain", "sculptory.hint.fluid.limit",
                "sculptory.hint.fluid.unloaded", "sculptory.hint.fluid.ball"));
        for (SettingDef<?> def : FluidSettings.SCHEMA.defs()) {
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

    /** The Fluid tool, active in an editor context over a world and a recording mock session. */
    private final class Rig {
        final FakeWorld world;
        final MockEditorSession mock = new MockEditorSession();
        final RecordingSession session = new RecordingSession(mock);
        final List<Notice> notices = new ArrayList<>();
        final FakeServices services = new FakeServices();
        final SymmetryCentre centre = new SymmetryCentre();
        final EditorContext ctx;
        final FluidTool tool;
        final ToolContext view;
        long now = 1_000 * MS;

        Rig(FakeWorld world) {
            this.world = world;
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
            tool = new FluidTool(services, new FakeBrushServices(), centre);
            ctx.tools().register(tool);
            view = ctx.contextFor(ToolId.FLUID);
            assertTrue(ctx.tools().activate(ToolId.FLUID, view));
        }

        void frame(WorldCursor cursor) {
            now += 16 * MS;
            tool.frame(view, new FrameInfo(now, 0f, 0, 0, cursor));
        }

        /** Frames at {@code cursor} until the search there is done. */
        void hover(WorldCursor cursor) {
            int frames = 0;
            do {
                frame(cursor);
                assertTrue(++frames < 10_000, "the search never finished");
            } while (tool.searching().isPresent());
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

        /** A click in Flood or Drain mode: press and release. */
        void click(WorldCursor cursor) {
            press(cursor);
            release(cursor);
        }

        /** A click in Fluid ball mode: press, one frame, release. */
        void ballClick(WorldCursor cursor) {
            press(cursor);
            frame(cursor);
            release(cursor);
        }

        boolean scroll(double amount, int modifiers) {
            return tool.onScroll(view, new ScrollEvent(amount, modifiers));
        }

        <T> void set(SettingDef<T> def, T value) {
            ctx.updateSettings(ToolId.FLUID, ctx.settings(ToolId.FLUID).with(def, value));
        }

        SettingsValues values() {
            return ctx.settings(ToolId.FLUID);
        }

        <T> T setting(SettingDef<T> def) {
            return values().get(def);
        }

        List<OpSpec> sentOps() {
            List<OpSpec> ops = new ArrayList<>();
            for (ToolAction action : mock.sent()) {
                if (action instanceof ToolAction.RunOp run) ops.add(run.op());
            }
            return ops;
        }

        /** The last run sent. */
        ToolAction.RunOp lastRun() {
            List<ToolAction> sent = mock.sent();
            return assertInstanceOf(ToolAction.RunOp.class, sent.get(sent.size() - 1));
        }

        /** The one Fill sent so far. */
        OpSpec.Fill onlyFill() {
            List<OpSpec> ops = sentOps();
            assertEquals(1, ops.size(), ops.toString());
            return assertInstanceOf(OpSpec.Fill.class, ops.get(0));
        }

        List<String> noticeKeys() {
            return notices.stream().map(Notice::key).toList();
        }
    }

    private static final class FakeServices implements FluidTool.Services {
        final EditorKeymap keymap = EditorKeymap.defaults();
        final List<String> confirmations = new ArrayList<>();
        /** How far the clock advances per read: 0 lets a search finish within one frame. */
        long nanosPerCall;
        private long clock;

        void advance(long nanos) {
            clock += nanos;
        }

        @Override
        public String keyLabel(KeyAction action) {
            return keymap.display(action);
        }

        @Override
        public void confirm(String opNameKey, long blocks, int copies, Runnable onConfirm) {
            confirmations.add(opNameKey + " " + blocks + " " + copies);
            onConfirm.run();
        }

        @Override
        public long nanoTime() {
            clock += nanosPerCall;
            return clock;
        }
    }

    private static final class FakeBrushServices implements BrushServices {
        final EditorKeymap keymap = EditorKeymap.defaults();

        @Override
        public BrushPredictor.Target predictionTarget() {
            return null;
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
            if (delegate.active()) dabs.add(d);
            delegate.dab(d);
        }

        @Override
        public void end() {
            ended = true;
            delegate.end();
        }

        @Override
        public void cancel() {
            ended = true;
            delegate.cancel();
        }

        @Override
        public boolean active() {
            return delegate.active();
        }
    }

    private static final class RecordingDraw implements WorldDraw {
        record Line(double x1, double y1, double z1, double x2, double y2, double z2, int color) {}

        final List<Line> lines = new ArrayList<>();
        final List<Box> boxes = new ArrayList<>();
        List<Region> regions = List.of();
        int outlineArgb;

        @Override
        public void shapeOutlines(List<? extends Region> regions, int argb, int copyArgb) {
            this.regions = List.copyOf(regions);
            this.outlineArgb = argb;
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
        public void seeThrough(boolean enabled) {}
    }
}
