package dev.sculptory.fabric.client.editor.tools.select;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.MixLayout;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.mask.BlockSet;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.palette.PalettePattern;
import dev.sculptory.fabric.client.editor.tools.brush.GradientDrag;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.EditorBackend;
import dev.sculptory.fabric.client.editor.EditorContext;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.mock.MockEditorSession;
import dev.sculptory.fabric.client.editor.mock.MockWorldReader;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.HudDraw;
import dev.sculptory.fabric.client.editor.tool.KeyHint;
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.ScrollEvent;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.tools.EditorToolSet;
import dev.sculptory.fabric.client.editor.world.BoxFace;
import dev.sculptory.fabric.client.editor.world.Ray;
import dev.sculptory.fabric.client.editor.world.ScreenProjector;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.JobTracker;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Permissions;
import dev.sculptory.fabric.client.session.SessionState;
import dev.sculptory.fabric.client.session.ToolAction;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.Phase;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SelectToolTest {
    private static final Box CUBE = new Box(new BlockPos(0, 0, 0), new BlockPos(3, 3, 3));
    private static final Box TEN = new Box(new BlockPos(0, 0, 0), new BlockPos(9, 9, 9));
    private static final BlockDescriptor STONE = BlockDescriptor.of(new NamespacedId("minecraft:stone"));
    private static final BlockDescriptor DIRT = BlockDescriptor.of(new NamespacedId("minecraft:dirt"));

    private final MockEditorSession session = new MockEditorSession();
    private final StateSpace states = new FakeStateSpace();
    private final WorldReader world = new MockWorldReader(states);
    private final List<Notice> notices = new ArrayList<>();
    private final List<String> confirmations = new ArrayList<>();
    private Runnable pendingConfirmation;
    private Ray ray;
    private BoxFace hovered;
    private float yaw;

    private final EditorBackend backend = new EditorBackend() {
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

    private final EditorContext ctx = new EditorContext(() -> backend, notices::add);
    private final SelectionActions actions = new SelectionActions(() -> ctx.contextFor(ToolId.SELECT), ctx::activeBlock,
            (message, onConfirm) -> {
                confirmations.add(message);
                pendingConfirmation = onConfirm;
            }, Translator.KEYS, () -> 42L);
    private final SelectTool tool = new SelectTool(actions, new SelectTool.Services() {
        @Override
        public Optional<Ray> cursorRay() {
            return Optional.ofNullable(ray);
        }

        @Override
        public Optional<ScreenProjector> projector() {
            return Optional.of((x, y, z, out) -> {
                out[0] = x * 10;
                out[1] = z * 10;
                return true;
            });
        }

        @Override
        public Optional<double[]> eye() {
            return Optional.of(new double[] {-5, 20, -5});
        }

        @Override
        public float cameraYaw() {
            return yaw;
        }

        @Override
        public void setHoveredFace(BoxFace face) {
            hovered = face;
        }

        @Override
        public String keyLabel(KeyAction action) {
            return action.id();
        }
    });
    private ToolContext view;

    @BeforeEach
    void activateSelect() {
        EditorToolSet.register(ctx.tools(), tool);
        view = ctx.contextFor(ToolId.SELECT);
        assertTrue(ctx.tools().activate(ToolId.SELECT, view));
    }

    private int handle(BlockDescriptor block) {
        int handle = states.resolve(block);
        assertTrue(handle >= 0);
        return handle;
    }

    private OpSpec onlySentOp() {
        List<ToolAction> sent = session.sent();
        assertEquals(1, sent.size(), "exactly one action sent: " + sent);
        return assertInstanceOf(ToolAction.RunOp.class, sent.get(0)).op();
    }

    private List<String> noticeKeys() {
        return notices.stream().map(Notice::key).toList();
    }

    private static Permissions without(Perm... missing) {
        EnumSet<Perm> granted = EnumSet.allOf(Perm.class);
        for (Perm perm : missing) {
            granted.remove(perm);
        }
        return new Permissions(Perm.mask(granted), Limits.DEFAULTS);
    }

    private static Ray down(double x, double z) {
        return new Ray(x, 50, z, 0, -1, 0);
    }

    private static WorldCursor hit(int x, int y, int z) {
        return new WorldCursor(new BlockPos(x, y, z), WorldCursor.Face.UP, x + 0.5, y + 1, z + 0.5, false);
    }

    private boolean pointer(PointerEvent.Kind kind, WorldCursor cursor, int modifiers) {
        int button = kind == PointerEvent.Kind.MOVE ? -1 : PointerEvent.LEFT;
        return tool.onPointer(view, new PointerEvent(kind, button, 100, 100, modifiers, cursor));
    }

    // ---- Ops ----

    @Test
    void fillSendsAFillOfTheActiveBlockOverTheSelection() {
        ctx.setSelection(TEN);
        assertTrue(actions.fill());
        assertEquals(new OpSpec.Fill(TEN, new Pattern.Single(handle(STONE)), CellMask.ANY), onlySentOp());
        assertFalse(assertInstanceOf(ToolAction.RunOp.class, session.sent().get(0)).physics(), "physics stays off");
    }

    /**
     * The Into setting becomes the Fill's mask: Only air the states flagged
     * air, Only existing blocks their complement, Everything no mask; Walls and Hollow carry none.
     */
    @Test
    void fillIntoSendsTheMatchingMask() {
        ctx.setSelection(TEN);
        CellMask airMask = new CellMask.States(new int[] {states.air()});
        assertEquals(airMask, SelectionActions.intoMask(PasteOptions.Into.AIR, states), "the fake space flags air only");
        assertEquals(new CellMask.Not(airMask), SelectionActions.intoMask(PasteOptions.Into.EXISTING, states));
        assertEquals(CellMask.ANY, SelectionActions.intoMask(PasteOptions.Into.EVERYTHING, states));

        view.updateSettings(view.settings().with(SelectSettings.INTO, PasteOptions.Into.AIR));
        assertTrue(actions.fill());
        assertEquals(new OpSpec.Fill(TEN, new Pattern.Single(handle(STONE)), airMask), onlySentOp());
        view.updateSettings(view.settings().with(SelectSettings.INTO, PasteOptions.Into.EXISTING));
        assertTrue(actions.fill());
        assertTrue(actions.walls());
        List<ToolAction> sent = session.sent();
        assertEquals(3, sent.size());
        assertEquals(new OpSpec.Fill(TEN, new Pattern.Single(handle(STONE)), new CellMask.Not(airMask)),
                assertInstanceOf(ToolAction.RunOp.class, sent.get(1)).op());
        assertEquals(new OpSpec.Walls(TEN, 1, new Pattern.Single(handle(STONE))),
                assertInstanceOf(ToolAction.RunOp.class, sent.get(2)).op(), "walls carry no mask");
    }

    @Test
    void fillFollowsTheActiveBlock() {
        ctx.setSelection(TEN);
        ctx.setActiveBlock(DIRT);
        actions.fill();
        assertEquals(new OpSpec.Fill(TEN, new Pattern.Single(handle(DIRT)), CellMask.ANY), onlySentOp());
    }

    @Test
    void fillWithThePaletteSendsAWeightedPatternWithMergedDuplicates() {
        ctx.setSelection(TEN);
        SettingsValues settings = view.settings()
                .with(SelectSettings.FILL_WITH, SelectSettings.FillWith.PALETTE)
                .with(SelectSettings.PALETTE, List.of(new SettingDef.WeightedBlock(STONE, 3),
                        new SettingDef.WeightedBlock(DIRT, 1), new SettingDef.WeightedBlock(STONE, 2)));
        view.updateSettings(settings);
        actions.fill();
        Pattern expected = new Pattern.Weighted(new int[] {handle(STONE), handle(DIRT)}, new int[] {5, 1}, 42L);
        assertEquals(new OpSpec.Fill(TEN, expected, CellMask.ANY), onlySentOp());
    }

    /**
     * Fill's Pattern: Patches lays the palette out with the Seed setting; a
     * Gradient needs its line (a Fill without one sends nothing and says so); in Box mode Alt+drag draws the line without
     * touching the selection, and the Fill then runs along it. In Magic mode Alt+click still removes from the selection,
     * and the hint says where the line is drawn. Steepness is greyed out: a Fill doesn't measure the ground's slope.
     */
    @Test
    void fillLaysThePaletteOutByItsPatternAndAltDragDrawsTheGradientLine() {
        ctx.setSelection(TEN);
        view.updateSettings(view.settings()
                .with(SelectSettings.FILL_WITH, SelectSettings.FillWith.PALETTE)
                .with(SelectSettings.PALETTE, List.of(new SettingDef.WeightedBlock(STONE, 3),
                        new SettingDef.WeightedBlock(DIRT, 1)))
                .with(SelectSettings.PATTERN.pattern, PalettePattern.Kind.PATCHES)
                .with(SelectSettings.PATTERN.patchSize, 4)
                .with(SelectSettings.PATTERN.seed, 5L));
        Pattern.Weighted mix = new Pattern.Weighted(new int[] {handle(STONE), handle(DIRT)}, new int[] {3, 1}, 5L);
        actions.fill();
        assertEquals(new OpSpec.Fill(TEN, new Pattern.Arranged(mix, new MixLayout.Patches(4)), CellMask.ANY), onlySentOp());
        assertFalse(SelectSettings.PATTERN.pattern.available(PalettePattern.Kind.STEEPNESS));

        int sent = session.sent().size();
        view.updateSettings(view.settings().with(SelectSettings.PATTERN.pattern, PalettePattern.Kind.GRADIENT));
        assertFalse(actions.fill());
        assertEquals(sent, session.sent().size(), "nothing sent");
        assertTrue(noticeKeys().contains(GradientDrag.NO_LINE));
        assertTrue(tool.hints(view).stream().anyMatch(h -> h.descriptionKey().equals("sculptory.hint.pattern.draw_line")));

        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(0, 0, 0), Modifiers.ALT));
        pointer(PointerEvent.Kind.DRAG, hit(0, 9, 0), 0);
        assertTrue(pointer(PointerEvent.Kind.RELEASE, hit(0, 9, 0), 0));
        assertEquals(TEN, ctx.selection().orElseThrow(), "the line drawing left the selection alone");
        assertEquals(new BlockPos(0, 0, 0), actions.gradientLine().from());
        assertEquals(new BlockPos(0, 9, 0), actions.gradientLine().to());
        assertTrue(actions.fill());
        assertEquals(new OpSpec.Fill(TEN, new Pattern.Arranged(mix, new MixLayout.Gradient(new BlockPos(0, 0, 0),
                new BlockPos(0, 9, 0), MixLayout.Gradient.DEFAULT_EDGE)), CellMask.ANY),
                assertInstanceOf(ToolAction.RunOp.class, session.sent().get(sent)).op());

        // Magic mode: Alt+click removes, as before; the hint points to Box mode.
        view.updateSettings(view.settings().with(SelectSettings.MODE, SelectSettings.Mode.MAGIC));
        assertTrue(tool.hints(view).stream().anyMatch(h -> h.descriptionKey().equals(
                "sculptory.hint.pattern.draw_line_in_box")));
        BlockPos before = actions.gradientLine().to();
        pointer(PointerEvent.Kind.PRESS, hit(3, 3, 3), Modifiers.ALT);
        pointer(PointerEvent.Kind.RELEASE, hit(5, 5, 5), 0);
        assertEquals(before, actions.gradientLine().to(), "Alt+click in Magic mode drew no line");
    }

    @Test
    void deleteSendsErase() {
        ctx.setSelection(TEN);
        assertTrue(tool.onAction(view, EditorAction.ERASE_SELECTION));
        assertEquals(new OpSpec.Erase(TEN, CellMask.ANY), onlySentOp());
    }

    @Test
    void deleteWithoutASelectionExplainsHowToMakeOne() {
        assertTrue(tool.onAction(view, EditorAction.ERASE_SELECTION));
        assertEquals(List.of(), session.sent());
        assertEquals(List.of("sculptory.notice.select_first"), noticeKeys());
    }

    @Test
    void hollowWallsAndReplaceBuildTheirOps() {
        ctx.setSelection(TEN);
        view.updateSettings(view.settings().with(SelectSettings.THICKNESS, 2));
        actions.hollow();
        actions.walls();
        actions.replace(DIRT, STONE);
        List<OpSpec> ops = session.sent().stream().map(a -> ((ToolAction.RunOp) a).op()).toList();
        assertEquals(List.of(
                new OpSpec.Hollow(TEN, 2, new Pattern.Single(states.air())),
                new OpSpec.Walls(TEN, 2, new Pattern.Single(handle(STONE))),
                new OpSpec.Replace(TEN, new CellMask.Blocks(List.of(new NamespacedId("minecraft:dirt"))),
                        new Pattern.Single(handle(STONE)))), ops);
    }

    @Test
    void betterReplaceSendsItsBlockSetKeepShapeAndPalette() {
        ctx.setSelection(TEN);
        BlockSet from = new BlockSet(List.of(new BlockSet.Block(new NamespacedId("minecraft:oak_stairs")),
                new BlockSet.Tag(new NamespacedId("minecraft:logs"))));
        BlockDescriptor brickStairs = BlockDescriptor.of(new NamespacedId("minecraft:stone_brick_stairs"));
        assertTrue(actions.replace(from, brickStairs, false, true));
        assertEquals(new OpSpec.Replace(TEN, from.toCellMask(states),
                new Pattern.KeepShape(new Pattern.Single(handle(brickStairs)))), onlySentOp());
        // To the Select palette, without keeping the shape.
        view.updateSettings(view.settings().with(SelectSettings.PALETTE, List.of(new SettingDef.WeightedBlock(STONE, 1))));
        assertTrue(actions.replace(from, brickStairs, true, false));
        OpSpec last = ((ToolAction.RunOp) session.sent().get(1)).op();
        assertEquals(new OpSpec.Replace(TEN, from.toCellMask(states), new Pattern.Single(handle(STONE))), last);
    }

    @Test
    void wholeFamilySendsTheSwapsAsARemapOrExplainsThereAreNone() {
        ctx.setSelection(TEN);
        BlockDescriptor oakPlanks = BlockDescriptor.of(new NamespacedId("minecraft:oak_planks"));
        BlockDescriptor stoneBricks = BlockDescriptor.of(new NamespacedId("minecraft:stone_bricks"));
        List<Pattern.BlockSwap> swaps = actions.familySwaps(oakPlanks, stoneBricks);
        assertTrue(swaps.contains(new Pattern.BlockSwap(new NamespacedId("minecraft:oak_stairs"),
                new NamespacedId("minecraft:stone_brick_stairs"))), swaps.toString());
        assertTrue(swaps.contains(new Pattern.BlockSwap(new NamespacedId("minecraft:oak_planks"),
                new NamespacedId("minecraft:stone_bricks"))), swaps.toString());
        assertTrue(actions.replaceFamily(oakPlanks, stoneBricks));
        assertEquals(new OpSpec.Replace(TEN, new CellMask.Blocks(swaps.stream().map(Pattern.BlockSwap::from).toList()),
                new Pattern.Remap(swaps, true)), onlySentOp());
        assertFalse(actions.replaceFamily(DIRT, STONE));
        assertEquals(1, session.sent().size(), "nothing more is sent");
        assertEquals(List.of("sculptory.notice.no_family"), noticeKeys());
    }

    @Test
    void overlayNaturalizeAndUpdateBlocksBuildTheirOps() {
        ctx.setSelection(TEN);
        assertFalse(actions.overlay(0));
        assertFalse(actions.overlay(17));
        assertFalse(actions.naturalize(STONE, 1, DIRT, 17, STONE));
        assertEquals(List.of(), session.sent());
        assertEquals(List.of("sculptory.notice.layer_depth", "sculptory.notice.layer_depth",
                "sculptory.notice.layer_depth"), noticeKeys());
        assertTrue(actions.overlay(3));
        BlockDescriptor grass = BlockDescriptor.of(new NamespacedId("minecraft:grass_block"));
        assertTrue(actions.naturalize(grass, 1, DIRT, 3, STONE));
        assertTrue(actions.updateBlocks());
        List<OpSpec> ops = session.sent().stream().map(a -> ((ToolAction.RunOp) a).op()).toList();
        assertEquals(List.of(
                new OpSpec.Overlay(new Region.Cuboid(TEN), new Pattern.Single(handle(ctx.activeBlock())), 3),
                new OpSpec.Naturalize(new Region.Cuboid(TEN), new Pattern.Single(handle(grass)), 1,
                        new Pattern.Single(handle(DIRT)), 3, new Pattern.Single(handle(STONE))),
                new OpSpec.UpdateBlocks(new Region.Cuboid(TEN))), ops);
        assertEquals(Optional.of(SelectionActions.Op.UPDATE_BLOCKS), actions.lastOp());
    }

    @Test
    void aBoxTooThinToHollowIsExplainedInsteadOfSent() {
        ctx.setSelection(new Box(new BlockPos(0, 0, 0), new BlockPos(9, 1, 9)));
        assertFalse(actions.hollow());
        assertEquals(List.of(), session.sent());
        assertEquals(List.of("sculptory.notice.too_thin_to_hollow"), noticeKeys());
    }

    @Test
    void aLargeOpAsksForConfirmationFirst() {
        Box million = new Box(new BlockPos(0, 0, 0), new BlockPos(99, 99, 99));
        ctx.setSelection(million);
        assertTrue(actions.fill());
        assertEquals(List.of(), session.sent(), "nothing is sent before confirming");
        assertEquals(List.of("sculptory.confirm.large_op[sculptory.op.fill,1,000,000]"), confirmations);
        pendingConfirmation.run();
        assertEquals(new OpSpec.Fill(million, new Pattern.Single(handle(STONE)), CellMask.ANY), onlySentOp());
    }

    @Test
    void deleteOfALargeBoxAlsoAsks() {
        ctx.setSelection(new Box(new BlockPos(0, 0, 0), new BlockPos(99, 99, 99)));
        tool.onAction(view, EditorAction.ERASE_SELECTION);
        assertEquals(1, confirmations.size());
        assertEquals(List.of(), session.sent(), "a cancelled confirmation sends nothing");
    }

    @Test
    void exactlyTheThresholdNeedsNoConfirmation() {
        Box half = new Box(new BlockPos(0, 0, 0), new BlockPos(99, 49, 99));
        assertEquals(SelectionActions.CONFIRM_VOLUME, half.volume());
        ctx.setSelection(half);
        actions.erase();
        assertEquals(List.of(), confirmations);
        assertEquals(new OpSpec.Erase(half, CellMask.ANY), onlySentOp());
    }

    @Test
    void opsOverTheServerLimitAreRefusedWithTheLimit() {
        session.setPermissions(without(Perm.LIMIT_BYPASS));
        ctx.setSelection(new Box(new BlockPos(0, 0, 0), new BlockPos(199, 199, 199)));
        assertFalse(actions.fill());
        assertEquals(List.of(), confirmations);
        assertEquals(List.of(), session.sent());
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.notice.too_large", "8,000,000", "2,097,152")),
                notices);
    }

    @Test
    void withoutTheRegionPermissionNothingIsSent() {
        session.setPermissions(without(Perm.REGION));
        ctx.setSelection(TEN);
        assertFalse(actions.fill());
        assertEquals(List.of(), session.sent());
        assertEquals(List.of("sculptory.notice.needs_permission"), noticeKeys());
    }

    @Test
    void aRejectionFromTheSessionBecomesAToast() {
        session.setState(SessionState.HANDSHAKING);
        ctx.setSelection(TEN);
        actions.fill();
        assertEquals(List.of("sculptory.reject.disabled"), noticeKeys());
        assertTrue(actions.lastJob().isEmpty());
    }

    @Test
    void acceptedOpsRunAsSimulatedJobsAndLandInHistory() {
        List<Notice> sessionNotices = new ArrayList<>();
        session.onNotice(sessionNotices::add);
        ctx.setSelection(TEN);
        actions.fill();
        JobTracker.Job queued = session.jobs().jobs().get(0);
        assertEquals(Phase.QUEUED, queued.phase());
        assertEquals(1_000, queued.total());
        assertEquals(Optional.of(queued.jobId()), actions.lastJob());
        assertEquals(Optional.of(SelectionActions.Op.FILL), actions.lastOp());

        session.tick();
        JobTracker.Job running = session.jobs().job(queued.jobId()).orElseThrow();
        assertEquals(Phase.APPLY, running.phase());
        assertTrue(running.done() > 0 && running.done() < running.total());

        session.finishJobs();
        JobTracker.Job done = session.jobs().job(queued.jobId()).orElseThrow();
        assertEquals(JobOutcome.COMPLETED, done.outcome());
        assertEquals(done.total(), done.done());
        assertTrue(session.history().canUndo());
        assertEquals("Fill", session.history().undoLabel());
        assertEquals(List.of(Notice.of(Notice.Level.SUCCESS, "sculptory.notice.job_finished", "Fill", "1,000", "0")),
                sessionNotices);

        session.undo();
        assertEquals("Fill", session.history().redoLabel());
        assertFalse(session.history().canUndo());
    }

    @Test
    void cancellingASimulatedJobEndsItCancelled() {
        ctx.setSelection(new Box(new BlockPos(0, 0, 0), new BlockPos(99, 99, 49)));
        actions.fill();
        var job = session.jobs().jobs().get(0);
        session.tick();
        session.send(new ToolAction.Cancel(job.jobId()));
        session.tick();
        assertEquals(JobOutcome.CANCELLED, session.jobs().job(job.jobId()).orElseThrow().outcome());
        for (int i = 0; i < MockEditorSession.FINISHED_JOB_TICKS; i++) {
            session.tick();
        }
        assertEquals(List.of(), session.jobs().jobs(), "finished jobs leave the tracker after a while");
    }

    // ---- Pointer ----

    @Test
    void draggingOnTerrainCreatesABoxBetweenTheTwoBlocks() {
        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(1, 2, 3), 0));
        assertTrue(ctx.pointerCapture());
        assertEquals(Optional.of(Box.of(new BlockPos(1, 2, 3))), ctx.selection());
        pointer(PointerEvent.Kind.DRAG, hit(4, 5, 6), 0);
        pointer(PointerEvent.Kind.DRAG, WorldCursor.miss(0, 0, 0), 0);
        assertTrue(pointer(PointerEvent.Kind.RELEASE, hit(9, 9, 9), 0));
        assertEquals(Optional.of(new Box(new BlockPos(1, 2, 3), new BlockPos(4, 5, 6))), ctx.selection(),
                "the sky doesn't move the corner, and release doesn't either");
        assertFalse(ctx.pointerCapture());
    }

    @Test
    void clickingTheSkyStartsNothing() {
        assertTrue(pointer(PointerEvent.Kind.PRESS, WorldCursor.miss(0, 100, 0), 0));
        assertTrue(ctx.selection().isEmpty());
        assertFalse(ctx.pointerCapture());
    }

    @Test
    void escCancelsADragAndRestoresTheOldBox() {
        ctx.setSelection(CUBE);
        pointer(PointerEvent.Kind.PRESS, hit(10, 10, 10), 0);
        pointer(PointerEvent.Kind.DRAG, hit(20, 20, 20), 0);
        assertTrue(tool.onAction(view, EditorAction.CANCEL));
        assertEquals(Optional.of(CUBE), ctx.selection());
        assertFalse(ctx.pointerCapture());
        assertFalse(tool.onAction(view, EditorAction.CANCEL), "the next Esc goes on down the ladder");
    }

    @Test
    void shiftClickGrowsTheBoxToTheBlock() {
        ctx.setSelection(CUBE);
        pointer(PointerEvent.Kind.PRESS, hit(8, 1, 1), Modifiers.SHIFT);
        assertEquals(Optional.of(new Box(new BlockPos(0, 0, 0), new BlockPos(8, 3, 3))), ctx.selection());
        assertFalse(ctx.pointerCapture());
    }

    @Test
    void draggingAFaceHandleResizes() {
        ctx.setSelection(CUBE);
        ray = down(4, 2);
        pointer(PointerEvent.Kind.MOVE, WorldCursor.miss(0, 0, 0), 0);
        assertEquals(BoxFace.EAST, hovered, "the handle under the cursor lights up");
        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(3, 3, 2), 0));
        assertEquals(SelectionDrag.Mode.RESIZE, tool.drag().mode());
        ray = down(7.3, 2);
        pointer(PointerEvent.Kind.DRAG, hit(7, 0, 2), 0);
        assertEquals(Optional.of(new Box(new BlockPos(0, 0, 0), new BlockPos(6, 3, 3))), ctx.selection());
        pointer(PointerEvent.Kind.RELEASE, hit(7, 0, 2), 0);
        assertFalse(tool.drag().isActive());
    }

    @Test
    void ctrlDraggingInsideTheBoxMovesIt() {
        ctx.setSelection(CUBE);
        ray = down(1.5, 1.5);
        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(1, 3, 1), Modifiers.CONTROL));
        assertEquals(SelectionDrag.Mode.MOVE, tool.drag().mode());
        ray = down(4.6, 0.2);
        pointer(PointerEvent.Kind.DRAG, hit(4, 0, 0), Modifiers.CONTROL);
        assertEquals(Optional.of(CUBE.offset(3, 0, -1)), ctx.selection());
    }

    @Test
    void hoveringAwayFromHandlesClearsTheHighlight() {
        ctx.setSelection(CUBE);
        ray = down(30, 30);
        pointer(PointerEvent.Kind.MOVE, WorldCursor.miss(0, 0, 0), 0);
        assertNull(hovered);
    }

    // ---- Scroll and keys ----

    @Test
    void ctrlScrollGrowsTheFaceUnderTheCursorOrEveryFace() {
        ctx.setSelection(CUBE);
        assertFalse(tool.onScroll(view, new ScrollEvent(1, 0)), "plain scroll is fly speed, not the tool's");
        assertTrue(tool.onScroll(view, new ScrollEvent(1, Modifiers.CONTROL)));
        assertEquals(Optional.of(new Box(new BlockPos(-1, -1, -1), new BlockPos(4, 4, 4))), ctx.selection());
        ctx.setSelection(CUBE);
        ray = down(4, 2);
        tool.onScroll(view, new ScrollEvent(1, Modifiers.CONTROL | Modifiers.SHIFT));
        assertEquals(Optional.of(new Box(new BlockPos(0, 0, 0), new BlockPos(7, 3, 3))), ctx.selection(), "Shift: ×4");
        ray = down(8, 2);
        tool.onScroll(view, new ScrollEvent(-1, Modifiers.CONTROL));
        assertEquals(Optional.of(new Box(new BlockPos(0, 0, 0), new BlockPos(6, 3, 3))), ctx.selection());
    }

    @Test
    void arrowsNudgeCameraRelativeAndShiftMovesTen() {
        ctx.setSelection(CUBE);
        yaw = 0;
        assertTrue(tool.onAction(view, EditorAction.NUDGE_FORWARD));
        assertEquals(Optional.of(CUBE.offset(0, 0, 1)), ctx.selection());
        yaw = 90;
        ctx.setModifiers(Modifiers.SHIFT);
        tool.onAction(view, EditorAction.NUDGE_FORWARD);
        assertEquals(Optional.of(CUBE.offset(-10, 0, 1)), ctx.selection());
        ctx.setModifiers(0);
        tool.onAction(view, EditorAction.NUDGE_UP);
        assertEquals(Optional.of(CUBE.offset(-10, 1, 1)), ctx.selection());
    }

    @Test
    void ctrlDDeselects() {
        ctx.setSelection(CUBE);
        assertTrue(tool.onAction(view, EditorAction.DESELECT));
        assertTrue(ctx.selection().isEmpty());
        assertFalse(tool.onAction(view, EditorAction.COPY), "clipboard keys are not the Select tool's yet");
    }

    // ---- Hints and HUD ----

    @Test
    void hintsFollowWhatThePlayerCanDoNext() {
        assertEquals(List.of("sculptory.hint.select.drag", "sculptory.hint.select.add"),
                tool.hints(view).stream().map(KeyHint::descriptionKey).toList());
        ctx.setSelection(CUBE);
        List<KeyHint> withBox = tool.hints(view);
        assertEquals(5, withBox.size());
        assertTrue(withBox.contains(new KeyHint("erase_selection", "sculptory.hint.select.erase")),
                "hints show the player's own keys");
        pointer(PointerEvent.Kind.PRESS, hit(0, 0, 0), 0);
        assertEquals(List.of(new KeyHint("Esc", "sculptory.hint.cancel_drag")), tool.hints(view));
    }

    @Test
    void theHudLabelsEachEdgeLength() {
        ctx.setSelection(new Box(new BlockPos(0, 0, 0), new BlockPos(11, 4, 7)));
        List<String> texts = new ArrayList<>();
        tool.renderHud(view, new HudDraw() {
            @Override
            public int width() {
                return 1000;
            }

            @Override
            public int height() {
                return 1000;
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
            public void fill(int x1, int y1, int x2, int y2, int argb) {
            }
        });
        assertEquals(List.of("12", "5", "8"), texts);
    }
}
