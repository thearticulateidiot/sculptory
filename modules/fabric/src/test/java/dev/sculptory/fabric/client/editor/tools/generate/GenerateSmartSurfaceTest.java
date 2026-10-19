package dev.sculptory.fabric.client.editor.tools.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.SmartSurfaceKernel;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
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
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.tools.brush.ShapeSettings;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Permissions;
import dev.sculptory.fabric.client.session.ToolAction;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.OpLabel;
import dev.sculptory.server.engine.Perm;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

/** Generate's Smart Surface: points, the preview, Enter building one "Smart surface" paste, Esc, refusals. */
class GenerateSmartSurfaceTest {
    private static final long MS = 1_000_000L;
    private static final int GROUND = 63;

    private final FakeStateSpace states = new FakeStateSpace();

    /** A hit on block (x, y, z), on its top. */
    private static WorldCursor on(int x, int y, int z) {
        return new WorldCursor(new BlockPos(x, y, z), WorldCursor.Face.UP, x + 0.5, y + 1, z + 0.5, false);
    }

    /** A square outline on the ground, points on top of the clicked blocks. */
    private static void square(Rig rig) {
        rig.click(on(0, GROUND, 0));
        rig.click(on(12, GROUND, 0));
        rig.click(on(12, GROUND + 4, 10));
        rig.click(on(0, GROUND + 2, 10));
    }

    @Test
    void clicksMakeAnOutlineThenInsidePointsAndThePreviewIsTheKernels() {
        Rig rig = new Rig();
        rig.click(on(0, GROUND, 0));
        rig.click(on(12, GROUND, 0));
        rig.frame(on(12, GROUND, 0));
        assertTrue(rig.smart().preview().isEmpty(), "two points make no surface yet");
        rig.click(on(12, GROUND + 4, 10));
        rig.click(on(0, GROUND + 2, 10));
        assertEquals(List.of(new BlockPos(0, 64, 0), new BlockPos(12, 64, 0), new BlockPos(12, 68, 10),
                new BlockPos(0, 66, 10)), rig.smart().points().nodes(), "on top of the clicked blocks, in order");
        // Inside the outline (three points or more): an inside point; outside: the outline grows.
        rig.click(on(6, GROUND + 8, 5));
        assertEquals(List.of(new BlockPos(6, 72, 5)), rig.smart().points().insideNodes());
        assertEquals(4, rig.smart().points().size());
        rig.frame(on(6, GROUND + 8, 5));
        SmartSurfaceDraft.Built built = rig.smart().preview().orElseThrow();
        Pattern stone = new Pattern.Single(states.state("minecraft:stone"));
        SmartSurfaceKernel.Surface surface = SmartSurfaceKernel.surface(new SmartSurfaceKernel.Shape(
                rig.smart().points().nodes(), rig.smart().points().insideNodes(), false));
        GeneratedSource expected = SmartSurfaceKernel.generate(surface, SmartSurfaceKernel.Fill.of(stone), null, rig.world.bottomY(), rig.world.topYExclusive(),
                1 << 20).source();
        assertEquals(expected.cells(), built.cells());
        expected.forEach((x, y, z, state) -> assertEquals(state, built.source().get(x, y, z)));
        assertEquals(72, built.surface().surfaceY(6, 5), "through the inside point");
        assertNotNull(built.ghost());
        assertEquals(1, rig.services.shown.size());
        assertTrue(built.terraces().length > 0 && built.terraces().length % 6 == 0, "terrace outlines");

        rig.click(on(-8, GROUND, 5));
        assertEquals(5, rig.smart().points().size(), "a click outside extends the outline");
        assertEquals(new BlockPos(-8, 64, 5), rig.smart().points().node(4), "after the last outline point");

        // Faceted and a deeper top over a mix body: the preview follows the settings.
        int dirt = states.state("minecraft:dirt"), coarse = states.state("minecraft:sand");
        rig.set(GenerateSettings.SMART_FACETED, true);
        rig.set(GenerateSettings.SMART_TOP_DEPTH, 2);
        rig.set(GenerateSettings.SMART_BODY_BLOCKS, ShapeSettings.Blocks.PALETTE);
        rig.set(GenerateSettings.SMART_BODY_PALETTE, List.of(new SettingDef.WeightedBlock(
                dev.sculptory.core.BlockDescriptor.of(new dev.sculptory.core.NamespacedId("minecraft:dirt")), 3),
                new SettingDef.WeightedBlock(
                        dev.sculptory.core.BlockDescriptor.of(new dev.sculptory.core.NamespacedId("minecraft:sand")), 1)));
        rig.set(GenerateSettings.SMART_THICKNESS, 4);
        rig.frame(on(-8, GROUND, 5));
        SmartSurfaceDraft.Built faceted = rig.smart().preview().orElseThrow();
        assertTrue(faceted.surface().faceted());
        int y = faceted.surface().surfaceY(6, 5);
        assertEquals(states.state("minecraft:stone"), faceted.source().get(6, y - 1, 5), "the top two deep");
        int body = faceted.source().get(6, y - 2, 5);
        assertTrue(body == dirt || body == coarse, "the body is the mix");
    }

    @Test
    void enterUploadsTheSheetThenPastesItAsOneSmartSurfaceJob() {
        Rig rig = new Rig();
        square(rig);
        rig.frame(on(0, GROUND, 0));
        long cells = rig.smart().preview().orElseThrow().cells();
        Box bounds = rig.smart().preview().orElseThrow().source().bounds();
        assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
        rig.frame(on(0, GROUND, 0));
        assertEquals(List.of(new MockEditorSession.Call("generate", bounds)), rig.mock.calls());
        ToolAction.RunOp run = assertInstanceOf(ToolAction.RunOp.class, rig.mock.sent().get(0));
        assertEquals(OpLabel.SMART_SURFACE, run.label());
        OpSpec.Paste paste = assertInstanceOf(OpSpec.Paste.class, run.op());
        assertEquals(bounds.min(), paste.origin());
        assertEquals(new PasteOptions(true, false, false), paste.o());
        assertEquals(List.of(SmartSurfaceDraft.SENT), rig.noticeKeys());
        assertEquals(List.of(Long.toString(cells)), rig.notices.get(0).args());
        rig.mock.finishJobs();
        assertEquals("Smart surface", rig.mock.history().undoLabel(), "one undo step, named Smart surface");
        assertEquals(4, rig.smart().points().size(), "the points stay");

        // Keep Existing pastes into air only.
        Rig keep = new Rig();
        keep.set(GenerateSettings.SMART_KEEP_EXISTING, true);
        square(keep);
        keep.frame(on(0, GROUND, 0));
        assertTrue(keep.tool.onAction(keep.view, EditorAction.COMMIT));
        keep.frame(on(0, GROUND, 0));
        ToolAction.RunOp kept = assertInstanceOf(ToolAction.RunOp.class, keep.mock.sent().get(0));
        assertEquals(PasteOptions.Into.AIR, assertInstanceOf(OpSpec.Paste.class, kept.op()).o().into());
    }

    @Test
    void keepExistingGhostsOnlyTheAir() {
        Rig rig = new Rig();
        rig.set(GenerateSettings.SMART_KEEP_EXISTING, true);
        rig.set(GenerateSettings.SMART_THICKNESS, 3);
        square(rig);
        rig.frame(on(0, GROUND, 0));
        SmartSurfaceDraft.Built built = rig.smart().preview().orElseThrow();
        // The sheet one to three below the points reaches into the ground at y 63 near the low corner.
        assertTrue(built.source().contains(0, GROUND, 0), "the sheet reaches into the ground");
        GeneratedSource air = SmartSurfaceDraft.airOnly(built.source(), rig.world);
        assertFalse(air.contains(0, GROUND, 0), "the grass there is kept");
        assertTrue(air.contains(0, 64, 0));
        assertTrue(air.cells() < built.cells());
    }

    @Test
    void extendToGroundFillsDownToTheTerrain() {
        Rig rig = new Rig();
        rig.set(GenerateSettings.SMART_EXTEND_TO_GROUND, true);
        rig.click(on(0, GROUND + 6, 0));
        rig.click(on(8, GROUND + 6, 0));
        rig.click(on(8, GROUND + 6, 8));
        rig.click(on(0, GROUND + 6, 8));
        rig.frame(on(0, GROUND, 0));
        SmartSurfaceDraft.Built built = rig.smart().preview().orElseThrow();
        for (int x = 0; x <= 8; x++) {
            for (int z = 0; z <= 8; z++) {
                for (int y = GROUND + 1; y <= GROUND + 7; y++) assertTrue(built.source().contains(x, y, z), x + "," + y + "," + z);
                assertFalse(built.source().contains(x, GROUND, z));
            }
        }
        assertEquals(81L * 7, built.cells());
    }

    /** Four outline points at {@code y} over x, z 0..8 (on top of blocks clicked at y - 1). */
    private static void squareAt(Rig rig, int y) {
        rig.click(on(0, y - 1, 0));
        rig.click(on(8, y - 1, 0));
        rig.click(on(8, y - 1, 8));
        rig.click(on(0, y - 1, 8));
    }

    @Test
    void extendToGroundUnderAnOverhangFillsToTheGroundBeneath() {
        Rig rig = new Rig();
        int stone = states.state("minecraft:stone");
        // A stone overhang at y 75 over the sheet's north half, which stands at y 69.
        for (int x = -2; x <= 10; x++) {
            for (int z = -2; z <= 3; z++) rig.world.set(x, 75, z, stone);
        }
        rig.set(GenerateSettings.SMART_EXTEND_TO_GROUND, true);
        squareAt(rig, 69);
        rig.frame(on(0, GROUND, 0));
        SmartSurfaceDraft.Built built = rig.smart().preview().orElseThrow();
        for (int x = 0; x <= 8; x++) {
            for (int z = 0; z <= 8; z++) {
                for (int y = GROUND + 1; y <= 69; y++) assertTrue(built.source().contains(x, y, z), x + "," + y + "," + z);
                assertFalse(built.source().contains(x, GROUND, z), "not into the ground");
                assertFalse(built.source().contains(x, 70, z));
            }
        }
        assertEquals(81L * (69 - GROUND), built.cells(), "under the overhang as in the open");
    }

    @Test
    void extendToGroundFillsThroughATreeAndKeepExistingLeavesIt() {
        Rig rig = new Rig();
        int log = states.state("minecraft:oak_log"), grass = states.state("minecraft:short_grass");
        for (int y = GROUND + 1; y <= GROUND + 7; y++) rig.world.set(4, y, 4, log);
        rig.world.set(6, GROUND + 1, 6, grass);
        rig.set(GenerateSettings.SMART_EXTEND_TO_GROUND, true);
        squareAt(rig, GROUND + 10);
        rig.frame(on(0, GROUND, 0));
        SmartSurfaceDraft.Built built = rig.smart().preview().orElseThrow();
        for (int y = GROUND + 1; y <= GROUND + 10; y++) {
            assertTrue(built.source().contains(4, y, 4), "through the trunk at y " + y);
            assertTrue(built.source().contains(6, y, 6), "through the plant at y " + y);
        }
        assertFalse(built.source().contains(4, GROUND, 4), "down to the ground under the tree");
        // Keep Existing: the ghost (and the paste, Into AIR) leave the tree and the plant.
        GeneratedSource air = SmartSurfaceDraft.airOnly(built.source(), rig.world);
        for (int y = GROUND + 1; y <= GROUND + 7; y++) assertFalse(air.contains(4, y, 4), "the trunk stays at y " + y);
        assertFalse(air.contains(6, GROUND + 1, 6), "the plant stays");
        assertTrue(air.contains(4, GROUND + 8, 4), "above the trunk is filled");
        rig.set(GenerateSettings.SMART_KEEP_EXISTING, true);
        rig.frame(on(0, GROUND, 0));
        assertEquals(PasteOptions.Into.AIR, rig.smart().preview().orElseThrow().options().into());
    }

    @Test
    void aFillDeeperThanTheCapLeavesIsRefusedBeforeTheCellsAreMade() {
        Rig rig = new Rig();
        // 81 surface cells fit a cap of 200, the fill 20 blocks down to the ground does not.
        rig.mock.setPermissions(new Permissions(Perm.mask(EnumSet.of(Perm.USE, Perm.CLIPBOARD, Perm.REGION)),
                new Limits(200, 200, 32, 20, 32L << 20, 2)));
        rig.set(GenerateSettings.SMART_EXTEND_TO_GROUND, true);
        squareAt(rig, GROUND + 20);
        rig.frame(on(0, GROUND, 0));
        SmartSurfaceDraft.Built built = rig.smart().preview().orElseThrow();
        assertTrue(built.tooLarge());
        assertNotNull(built.surface(), "the surface is still drawn");
        assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
        assertEquals(List.of("sculptory.notice.too_large"), rig.noticeKeys());
        assertTrue(rig.mock.calls().isEmpty(), "nothing was sent");
        // The same sheet 2 above the ground fits.
        Rig low = new Rig();
        low.mock.setPermissions(new Permissions(Perm.mask(EnumSet.of(Perm.USE, Perm.CLIPBOARD, Perm.REGION)),
                new Limits(200, 200, 32, 20, 32L << 20, 2)));
        low.set(GenerateSettings.SMART_EXTEND_TO_GROUND, true);
        squareAt(low, GROUND + 2);
        low.frame(on(0, GROUND, 0));
        assertFalse(low.smart().preview().orElseThrow().tooLarge());
        assertEquals(162, low.smart().preview().orElseThrow().cells());
    }

    /** The cells the kernel makes for the rig's points as they are now, in {@code block}, thickness 1, gap-free. */
    private GeneratedSource expectedSheet(Rig rig, String block) {
        SmartSurfaceKernel.Surface surface = SmartSurfaceKernel.surface(new SmartSurfaceKernel.Shape(
                rig.smart().points().nodes(), rig.smart().points().insideNodes(), false));
        return SmartSurfaceKernel.generate(surface, SmartSurfaceKernel.Fill.of(new Pattern.Single(states.state(block))),
                null, rig.world.bottomY(), rig.world.topYExclusive(), 1 << 20).source();
    }

    @Test
    void escWhileAPreviewIsBeingMadeDropsTheOldOneSoEnterBuildsTheNewPoints() {
        Rig rig = new Rig();
        square(rig);
        rig.frame(on(0, GROUND, 0));
        assertTrue(rig.smart().preview().isPresent());
        rig.services.deferred = true;
        rig.click(on(-6, GROUND, 5)); // a fifth outline point: a new preview, still being made
        rig.frame(on(0, GROUND, 0));
        assertTrue(rig.smart().previewRunning());
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL), "deselect the new point");
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL), "stop the preview being made");
        assertFalse(rig.smart().previewRunning());
        assertTrue(rig.smart().preview().isEmpty(), "the old preview went too");
        assertEquals(List.of(), rig.services.shown);
        assertEquals(5, rig.smart().points().size(), "the points stay");
        rig.services.deferred = false;
        rig.services.queued.clear();
        rig.frame(on(0, GROUND, 0));
        assertTrue(rig.smart().preview().isEmpty(), "not made again by itself");
        assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
        rig.frame(on(0, GROUND, 0));
        GeneratedSource expected = expectedSheet(rig, "minecraft:stone");
        assertEquals(List.of(new MockEditorSession.Call("generate", expected.bounds())), rig.mock.calls(),
                "Enter builds the five-point sheet, not the old four-point one");
    }

    @Test
    void aPreviewThatFailsLeavesNothingToBuild() {
        Rig rig = new Rig();
        square(rig);
        rig.frame(on(0, GROUND, 0));
        assertTrue(rig.smart().preview().isPresent());
        rig.services.deferred = true;
        rig.click(on(-6, GROUND, 5));
        rig.frame(on(0, GROUND, 0));
        rig.failing = true;
        rig.services.runQueued(); // the ghost's bake throws off the client thread
        rig.failing = false;
        rig.frame(on(0, GROUND, 0));
        assertTrue(rig.smart().preview().isEmpty(), "the failed preview leaves none, not the old one");
        assertEquals(List.of(), rig.services.shown);
        rig.services.deferred = false;
        assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
        rig.frame(on(0, GROUND, 0));
        GeneratedSource expected = expectedSheet(rig, "minecraft:stone");
        assertEquals(List.of(new MockEditorSession.Call("generate", expected.bounds())), rig.mock.calls(),
                "Enter makes the preview again and builds that");
    }

    @Test
    void changingTheActiveBlockMakesThePreviewAgain() {
        Rig rig = new Rig();
        square(rig);
        rig.frame(on(0, GROUND, 0));
        int stone = states.state("minecraft:stone"), sand = states.state("minecraft:sand");
        assertEquals(stone, rig.smart().preview().orElseThrow().source().get(0, 64, 0));
        rig.services.active = dev.sculptory.core.BlockDescriptor.of(new dev.sculptory.core.NamespacedId("minecraft:sand"));
        rig.frame(on(0, GROUND, 0));
        assertEquals(sand, rig.smart().preview().orElseThrow().source().get(0, 64, 0), "the next frame follows it");
        // Enter right after a change (no frame between) builds the new block too.
        rig.services.active = dev.sculptory.core.BlockDescriptor.of(new dev.sculptory.core.NamespacedId("minecraft:dirt"));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
        assertEquals(states.state("minecraft:dirt"), rig.smart().preview().orElseThrow().source().get(0, 64, 0));
        // A mix in both layers does not use the active block: changing it changes nothing.
        Rig mixed = new Rig();
        mixed.set(GenerateSettings.SMART_TOP_BLOCKS, ShapeSettings.Blocks.PALETTE);
        mixed.set(GenerateSettings.SMART_TOP_PALETTE, List.of(new SettingDef.WeightedBlock(
                dev.sculptory.core.BlockDescriptor.of(new dev.sculptory.core.NamespacedId("minecraft:dirt")), 1)));
        mixed.set(GenerateSettings.SMART_BODY_BLOCKS, ShapeSettings.Blocks.PALETTE);
        mixed.set(GenerateSettings.SMART_BODY_PALETTE, List.of(new SettingDef.WeightedBlock(
                dev.sculptory.core.BlockDescriptor.of(new dev.sculptory.core.NamespacedId("minecraft:dirt")), 1)));
        square(mixed);
        mixed.frame(on(0, GROUND, 0));
        SmartSurfaceDraft.Built before = mixed.smart().preview().orElseThrow();
        mixed.services.active = dev.sculptory.core.BlockDescriptor.of(new dev.sculptory.core.NamespacedId("minecraft:sand"));
        mixed.frame(on(0, GROUND, 0));
        assertTrue(before == mixed.smart().preview().orElseThrow(), "the same preview");
    }

    @Test
    void escDeselectsThenClearsAndBackspaceRemovesTheSelectedPoint() {
        Rig rig = new Rig();
        square(rig);
        rig.click(on(6, GROUND, 5));
        assertEquals(1, rig.smart().points().insideCount());
        rig.frame(on(6, GROUND, 5));
        assertTrue(rig.smart().points().selection().isPresent(), "the new inside point is selected");
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL), "Esc deselects");
        assertTrue(rig.smart().points().selection().isEmpty());
        assertEquals(4, rig.smart().points().size());
        // Clicking an outline point selects it; Backspace removes it.
        rig.click(on(12, GROUND, 0));
        assertEquals(1, rig.smart().points().selected());
        assertTrue(rig.tool.onAction(rig.view, EditorAction.REMOVE_NODE));
        assertEquals(3, rig.smart().points().size());
        assertFalse(rig.smart().points().nodes().contains(new BlockPos(12, 64, 0)));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL), "deselect");
        rig.frame(on(0, GROUND, 0));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL), "then clear");
        assertTrue(rig.smart().points().isEmpty());
        assertEquals(0, rig.smart().points().insideCount());
        rig.frame(on(0, GROUND, 0));
        assertTrue(rig.smart().preview().isEmpty());
        assertEquals(List.of(), rig.services.shown, "the ghost is gone");
        assertFalse(rig.tool.onAction(rig.view, EditorAction.CANCEL));
    }

    @Test
    void switchingKindsKeepsThePointsAndShowsOneGhost() {
        Rig rig = new Rig();
        square(rig);
        rig.frame(on(0, GROUND, 0));
        assertEquals(1, rig.services.shown.size());
        rig.set(GenerateSettings.KIND, GenerateSettings.Kind.LINE);
        rig.frame(on(0, GROUND, 0));
        assertEquals(List.of(), rig.services.shown, "the line has no points: no ghost");
        assertEquals(4, rig.smart().points().size(), "the outline stays");
        rig.set(GenerateSettings.KIND, GenerateSettings.Kind.SMART_SURFACE);
        rig.frame(on(0, GROUND, 0));
        assertEquals(1, rig.services.shown.size(), "the sheet's ghost is back");
    }

    @Test
    void refusalsSayWhyAndSendNothing() {
        Rig few = new Rig();
        few.click(on(0, GROUND, 0));
        few.click(on(5, GROUND, 0));
        assertTrue(few.tool.onAction(few.view, EditorAction.COMMIT));
        assertEquals(List.of(SmartSurfaceDraft.NEEDS_POINTS), few.noticeKeys());

        Rig line = new Rig();
        line.click(on(0, GROUND, 0));
        line.click(on(5, GROUND, 5));
        line.click(on(10, GROUND, 10));
        line.frame(on(10, GROUND, 10));
        assertNotNull(line.smart().preview().orElseThrow().refused());
        assertTrue(line.tool.onAction(line.view, EditorAction.COMMIT));
        assertEquals(List.of(SmartSurfaceDraft.problemKey(SmartSurfaceKernel.Problem.NO_AREA)), line.noticeKeys());

        Rig small = new Rig();
        small.mock.setPermissions(new Permissions(Perm.mask(EnumSet.of(Perm.USE, Perm.CLIPBOARD, Perm.REGION)),
                new Limits(20, 20, 32, 20, 32L << 20, 2)));
        square(small);
        small.frame(on(0, GROUND, 0));
        assertTrue(small.smart().preview().orElseThrow().tooLarge());
        assertNull(small.smart().preview().orElseThrow().ghost());
        assertTrue(small.tool.onAction(small.view, EditorAction.COMMIT));
        assertEquals(List.of("sculptory.notice.too_large"), small.noticeKeys());

        Rig far = new Rig();
        far.set(GenerateSettings.SMART_EXTEND_TO_GROUND, true);
        far.world.setLoaded(1, 0, false);
        far.click(on(0, GROUND, 0));
        far.click(on(20, GROUND, 0));
        far.click(on(20, GROUND, 6));
        far.click(on(0, GROUND, 6));
        far.frame(on(0, GROUND, 0));
        assertTrue(far.smart().preview().orElseThrow().unloaded().length > 0);
        assertTrue(far.tool.onAction(far.view, EditorAction.COMMIT));
        assertEquals(List.of(GenerateTool.UNLOADED), far.noticeKeys());

        for (Perm missing : List.of(Perm.REGION, Perm.CLIPBOARD)) {
            Rig rig = new Rig();
            EnumSet<Perm> nodes = EnumSet.of(Perm.USE, Perm.CLIPBOARD, Perm.REGION);
            nodes.remove(missing);
            rig.mock.setPermissions(new Permissions(Perm.mask(nodes), Limits.DEFAULTS));
            square(rig);
            rig.frame(on(0, GROUND, 0));
            assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
            assertEquals(List.of("sculptory.notice.needs_permission"), rig.noticeKeys());
            assertEquals(List.of(missing.node()), rig.notices.get(0).args());
            assertTrue(rig.mock.calls().isEmpty() && rig.mock.sent().isEmpty(), "nothing was sent");
        }
    }

    @Test
    void theHintsFollowThePoints() {
        Rig rig = new Rig();
        assertTrue(rig.hintKeys().contains("sculptory.hint.smart.start"));
        rig.click(on(0, GROUND, 0));
        assertTrue(rig.hintKeys().contains("sculptory.hint.smart.more_points"));
        rig.click(on(12, GROUND, 0));
        rig.click(on(12, GROUND, 10));
        rig.frame(on(0, GROUND, 0));
        List<String> keys = rig.hintKeys();
        assertTrue(keys.contains("sculptory.hint.smart.add") && keys.contains("sculptory.hint.smart.build")
                && keys.contains(dev.sculptory.fabric.client.editor.tools.points.PointEditor.HINT_DESELECT), keys.toString());
    }

    // ---- The point editor's drags, inserts and keys through the host ----

    /** Screen = (20 x + 8 z, -20 y + 8 z): every axis shows as a line of its own (as PointEditorTest's camera). */
    static final dev.sculptory.fabric.client.editor.world.ScreenProjector OBLIQUE = (x, y, z, out) -> {
        out[0] = 20 * x + 8 * z;
        out[1] = -20 * y + 8 * z;
        return true;
    };

    private static double[] screen(double x, double y, double z) {
        double[] out = new double[2];
        OBLIQUE.project(x, y, z, out);
        return out;
    }

    /** A block far from every point: a drag over it shows a point never lands on the block under the cursor. */
    private static final WorldCursor TERRAIN = on(30, 50, -30);

    @Test
    void anArrowDragsAnOutlinePointAndThePreviewFollowsLiveWithoutTouchingTerrain() {
        Rig rig = new Rig();
        square(rig);
        rig.click(on(12, GROUND, 0));
        assertEquals(1, rig.smart().points().selected());
        rig.services.eye = new double[] {12.5, 64.5, 20.5};
        double[] centre = screen(12.5, 64.5, 0.5);
        double[] onY = {centre[0], centre[1] - 40};
        rig.services.aimAt(12.5, 64.5, 0.5);
        rig.frame(onY, TERRAIN);
        rig.pointer(PointerEvent.Kind.PRESS, onY, TERRAIN);
        assertEquals(dev.sculptory.fabric.client.editor.tools.points.PointEditor.DragMode.AXIS,
                rig.smart().editor().dragMode());
        rig.services.aimAt(12.5, 70.5, 0.5);
        rig.pointer(PointerEvent.Kind.DRAG, onY, TERRAIN);
        assertEquals(new BlockPos(12, 70, 0), rig.smart().points().node(1), "straight up, not onto the terrain");
        rig.frame(onY, TERRAIN);
        assertEquals(70, rig.smart().preview().orElseThrow().surface().surfaceY(12, 0), "the preview follows mid-drag");
        rig.pointer(PointerEvent.Kind.RELEASE, onY, TERRAIN);
        assertFalse(rig.smart().editor().dragging());
        // Esc during a drag puts the point back.
        rig.frame(onY, TERRAIN);
        double[] at = screen(12.5, 70.5, 0.5);
        double[] onX = {at[0] + 40, at[1]};
        rig.services.aimAt(12.5, 70.5, 0.5);
        rig.frame(onX, TERRAIN);
        rig.pointer(PointerEvent.Kind.PRESS, onX, TERRAIN);
        rig.services.aimAt(16.5, 70.5, 0.5);
        rig.pointer(PointerEvent.Kind.DRAG, onX, TERRAIN);
        assertEquals(new BlockPos(16, 70, 0), rig.smart().points().node(1));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL));
        assertEquals(new BlockPos(12, 70, 0), rig.smart().points().node(1), "Esc puts it back");
        rig.frame(onX, TERRAIN);
        assertEquals(70, rig.smart().preview().orElseThrow().surface().surfaceY(12, 0));
        assertTrue(rig.smart().points().selection().isPresent(), "still selected after the drag's Esc");
    }

    @Test
    void theLevelSquareAndTheCentreMoveAnInsidePointAndAHeldPointFollowsTheCamera() {
        Rig rig = new Rig();
        square(rig);
        rig.click(on(6, GROUND + 8, 5));
        assertEquals(List.of(new BlockPos(6, 72, 5)), rig.smart().points().insideNodes());
        rig.services.eye = new double[] {6.5, 82.5, 15.5};
        double[] centre = screen(6.5, 72.5, 5.5);
        double[] onLevel = {centre[0] + 17, centre[1] + 5};
        rig.services.aimAt(6.5, 72.5, 5.5);
        rig.pointer(PointerEvent.Kind.PRESS, onLevel, TERRAIN);
        assertEquals(dev.sculptory.fabric.client.editor.tools.points.PointEditor.DragMode.PLANE,
                rig.smart().editor().dragMode());
        rig.services.aimAt(8.5, 72.5, 2.5);
        rig.pointer(PointerEvent.Kind.DRAG, onLevel, TERRAIN);
        rig.pointer(PointerEvent.Kind.RELEASE, onLevel, TERRAIN);
        assertEquals(new BlockPos(8, 72, 2), rig.smart().points().insidePoint(0), "sideways at its height");
        rig.frame(onLevel, TERRAIN);
        assertEquals(72, rig.smart().preview().orElseThrow().surface().surfaceY(8, 2), "the sheet passes through it");

        // The centre cube: a free drag at its distance, and flying carries it along.
        rig.services.eye = new double[] {8.5, 72.5, 22.5};
        double[] cube = screen(8.5, 72.5, 2.5);
        rig.services.aimAt(8.5, 72.5, 2.5);
        rig.frame(cube, TERRAIN);
        rig.pointer(PointerEvent.Kind.PRESS, cube, TERRAIN);
        assertEquals(dev.sculptory.fabric.client.editor.tools.points.PointEditor.DragMode.FREE,
                rig.smart().editor().dragMode());
        rig.services.eye = new double[] {5.5, 75.5, 22.5};
        rig.services.ray = new dev.sculptory.fabric.client.editor.world.Ray(5.5, 75.5, 22.5, 0, 0, -1);
        rig.frame(cube, TERRAIN);
        assertEquals(new BlockPos(5, 75, 2), rig.smart().points().insidePoint(0), "it follows the camera");
        rig.pointer(PointerEvent.Kind.RELEASE, cube, TERRAIN);
        rig.frame(cube, TERRAIN);
        assertEquals(75, rig.smart().preview().orElseThrow().surface().surfaceY(5, 2));
    }

    @Test
    void theInsertKeyAddsAnOutlinePointAfterAnOutlinePointAndAnInsidePointBesideAnInsidePoint() {
        Rig rig = new Rig();
        square(rig);
        rig.click(on(0, GROUND, 0));
        assertEquals(0, rig.smart().points().selected());
        rig.frame(on(5, GROUND, -3));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.EXTRUDE_POINT));
        assertTrue(rig.smart().editor().placingInserted());
        assertEquals(new BlockPos(5, 64, -3), rig.smart().points().node(1), "right after the selected point");
        rig.frame(on(6, GROUND, -4));
        assertEquals(new BlockPos(6, 64, -4), rig.smart().points().node(1), "it follows the cursor");
        rig.click(on(6, GROUND, -4));
        assertFalse(rig.smart().editor().placingInserted());
        assertEquals(List.of(new BlockPos(0, 64, 0), new BlockPos(6, 64, -4), new BlockPos(12, 64, 0),
                new BlockPos(12, 68, 10), new BlockPos(0, 66, 10)), rig.smart().points().nodes());
        rig.frame(on(6, GROUND, -4));
        assertTrue(rig.smart().preview().orElseThrow().surface().covers(6, -4), "the sheet takes the new corner");

        // On an inside point: another inside point; Esc drops one being placed.
        rig.click(on(5, GROUND + 4, 5));
        assertEquals(1, rig.smart().points().insideCount());
        rig.frame(on(7, GROUND, 6));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.EXTRUDE_POINT));
        assertEquals(2, rig.smart().points().insideCount());
        assertEquals(5, rig.smart().points().size(), "the outline is untouched");
        rig.click(on(8, GROUND, 6));
        assertEquals(new BlockPos(8, 64, 6), rig.smart().points().insidePoint(1));
        rig.frame(on(9, GROUND, 7));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.EXTRUDE_POINT));
        assertEquals(3, rig.smart().points().insideCount());
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL), "Esc drops the point being placed");
        assertEquals(2, rig.smart().points().insideCount());
        assertEquals(5, rig.smart().points().size());
    }

    @Test
    void aClickOnAnOutlineEdgeInsertsAPointThereAndBackspaceRemovesEitherRole() {
        Rig rig = new Rig();
        square(rig);
        rig.click(on(6, GROUND + 4, 5));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL), "deselect");
        rig.services.eye = new double[] {6.5, 64.5, 30.5};
        double[] middle = screen(6.5, 64.5, 0.5);
        WorldCursor ground = on(6, 40, 0); // the block aimed at lies far below the edge
        rig.frame(middle, ground);
        assertEquals(java.util.OptionalInt.of(0), rig.smart().editor().hoveredSegment());
        rig.pointer(PointerEvent.Kind.PRESS, middle, ground);
        rig.pointer(PointerEvent.Kind.RELEASE, middle, ground);
        assertEquals(new BlockPos(6, 64, 0), rig.smart().points().node(1), "on the edge, at its height");
        assertEquals(5, rig.smart().points().size());
        assertEquals(1, rig.smart().points().selected());
        // The closing edge (last to first) takes inserts too.
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL));
        double[] closing = screen(0.5, 65.5, 5.5);
        rig.frame(closing, on(0, 30, 5));
        assertEquals(java.util.OptionalInt.of(4), rig.smart().editor().hoveredSegment());
        rig.pointer(PointerEvent.Kind.PRESS, closing, on(0, 30, 5));
        assertEquals(6, rig.smart().points().size());
        assertEquals(new BlockPos(0, 65, 5), rig.smart().points().node(5), "between the last point and the first");
        // Backspace: the selected outline point, then an inside point (nothing left selected).
        assertTrue(rig.tool.onAction(rig.view, EditorAction.REMOVE_NODE));
        assertEquals(5, rig.smart().points().size());
        rig.services.eye = null;
        rig.click(on(6, GROUND + 4, 5));
        assertEquals(Optional.of(new PathModel.PointRef(PathModel.Role.INSIDE, 0)), rig.smart().points().selection());
        assertTrue(rig.tool.onAction(rig.view, EditorAction.REMOVE_NODE));
        assertEquals(0, rig.smart().points().insideCount());
        assertTrue(rig.smart().points().selection().isEmpty());
        assertEquals(5, rig.smart().points().size());
    }

    @Test
    void pastTheCapsEachRoleSaysItsOwnLimit() {
        Rig rig = new Rig();
        square(rig);
        for (int i = 0; i < SmartSurfaceKernel.MAX_POINTS - 4; i++) {
            rig.smart().editor().addInside(rig.view, new BlockPos(1 + i % 11, 70, 1 + i / 11));
        }
        rig.notices.clear();
        rig.smart().editor().addInside(rig.view, new BlockPos(5, 80, 5));
        rig.smart().editor().addEnd(rig.view, new BlockPos(-5, 64, 5));
        assertEquals(List.of(SmartSurfaceDraft.TOO_MANY_INSIDE, SmartSurfaceDraft.TOO_MANY_OUTLINE), rig.noticeKeys());
        assertEquals(List.of("4"), rig.notices.get(1).args(), "the outline's share: what the inside points leave");
    }

    private final class Rig {
        final FakeWorld world = flatWorld();
        final MockEditorSession mock = new MockEditorSession();
        final List<Notice> notices = new ArrayList<>();
        final FakeServices services = new FakeServices();
        /** Whether the state space throws (a preview's work failing off the client thread). */
        boolean failing;
        /** The states, through a proxy that throws while {@link #failing}. */
        final StateSpace failingStates = (StateSpace) java.lang.reflect.Proxy.newProxyInstance(
                StateSpace.class.getClassLoader(), new Class<?>[] {StateSpace.class}, (proxy, method, args) -> {
                    if (failing) throw new IllegalStateException("failing on purpose");
                    try {
                        return method.invoke(states, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
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
                    return failingStates;
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
            set(GenerateSettings.KIND, GenerateSettings.Kind.SMART_SURFACE);
        }

        SmartSurfaceDraft smart() {
            return tool.smartSurface();
        }

        void frame(WorldCursor cursor) {
            now += 16 * MS;
            tool.frame(view, new FrameInfo(now, 0f, 0, 0, cursor));
        }

        void pointer(PointerEvent.Kind kind, double[] mouse, WorldCursor cursor) {
            tool.onPointer(view, new PointerEvent(kind, PointerEvent.LEFT, mouse[0], mouse[1], 0, cursor));
        }

        void frame(double[] mouse, WorldCursor cursor) {
            now += 16 * MS;
            tool.frame(view, new FrameInfo(now, 0f, mouse[0], mouse[1], cursor));
        }

        void click(WorldCursor cursor) {
            tool.onPointer(view, new PointerEvent(PointerEvent.Kind.PRESS, PointerEvent.LEFT, 0, 0, 0, cursor));
            tool.onPointer(view, new PointerEvent(PointerEvent.Kind.RELEASE, PointerEvent.LEFT, 0, 0, 0, cursor));
        }

        <T> void set(SettingDef<T> def, T value) {
            ctx.updateSettings(ToolId.GENERATE, ctx.settings(ToolId.GENERATE).with(def, value));
        }

        List<String> noticeKeys() {
            return notices.stream().map(Notice::key).toList();
        }

        List<String> hintKeys() {
            return tool.hints(view).stream().map(h -> h.descriptionKey()).toList();
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
        /** The camera: none until a test sets it (then the oblique projection below). */
        double[] eye;
        dev.sculptory.fabric.client.editor.world.Ray ray;

        void aimAt(double x, double y, double z) {
            ray = new dev.sculptory.fabric.client.editor.world.Ray(eye[0], eye[1], eye[2], x - eye[0], y - eye[1],
                    z - eye[2]);
        }

        @Override
        public Optional<dev.sculptory.fabric.client.editor.world.Ray> cursorRay() {
            return Optional.ofNullable(ray);
        }

        @Override
        public Optional<dev.sculptory.fabric.client.editor.world.ScreenProjector> projector() {
            return eye == null ? Optional.empty() : Optional.of(OBLIQUE);
        }

        @Override
        public Optional<double[]> eye() {
            return Optional.ofNullable(eye);
        }

        @Override
        public void showGhosts(List<GhostPlacement> placements) {
            shown = List.copyOf(placements);
        }

        @Override
        public void releaseGhost(GhostVolume volume) {}

        @Override
        public Executor background() {
            return task -> {
                if (deferred) {
                    queued.add(task);
                } else {
                    task.run();
                }
            };
        }

        /** While set, background work waits in {@link #queued} until {@link #runQueued}. */
        boolean deferred;
        final List<Runnable> queued = new ArrayList<>();
        /** The editor's active block. */
        dev.sculptory.core.BlockDescriptor active = dev.sculptory.core.BlockDescriptor.of(
                new dev.sculptory.core.NamespacedId("minecraft:stone"));

        void runQueued() {
            List<Runnable> tasks = new ArrayList<>(queued);
            queued.clear();
            tasks.forEach(Runnable::run);
        }

        @Override
        public dev.sculptory.core.BlockDescriptor activeBlock() {
            return active;
        }

        @Override
        public String keyLabel(KeyAction action) {
            return keymap.display(action);
        }

        @Override
        public void confirm(String opNameKey, long blocks, Runnable onConfirm) {
            onConfirm.run();
        }
    }
}
