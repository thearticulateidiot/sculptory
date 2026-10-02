package dev.sculptory.fabric.client.editor.tools.scatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.SparseUpload;
import dev.sculptory.core.scatter.BlockVariants;
import dev.sculptory.core.scatter.FeatureCatalog;
import dev.sculptory.core.scatter.ScatterArea;
import dev.sculptory.core.scatter.ScatterPlan;
import dev.sculptory.core.scatter.ScatterSettings;
import dev.sculptory.core.scatter.ScatterSource;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.EditorBackend;
import dev.sculptory.fabric.client.editor.EditorContext;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.input.KeyChord;
import dev.sculptory.fabric.client.editor.mock.MockEditorSession;
import dev.sculptory.fabric.client.editor.render.ghost.GhostPlacement;
import dev.sculptory.fabric.client.editor.render.ghost.GhostVolume;
import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.FrameInfo;
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.ScrollEvent;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.tool.WorldDraw;
import dev.sculptory.fabric.client.editor.tools.place.GhostBaker;
import dev.sculptory.fabric.client.session.ClipboardCache;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Permissions;
import dev.sculptory.fabric.client.session.Reply;
import dev.sculptory.fabric.client.session.ScatterPreviewRequest;
import dev.sculptory.fabric.client.session.ScatterPreviewResult;
import dev.sculptory.fabric.client.session.ToolAction;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.Perm;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The Scatter tool's flows against the mock session: painting, previews, ghosts, commit, Esc, staleness. */
class ScatterToolTest {
    private static final long MS = 1_000_000L;
    private static final String HASH = "ab".repeat(32);
    private static final SourceRef ASSET = new SourceRef.Asset(HASH);
    private static final ScatterSource HELD_ASSET = new ScatterSource.Held(ASSET);

    private final MockEditorSession session = new MockEditorSession();
    private final FakeStateSpace states = new FakeStateSpace();
    private final FakeWorld world = new FakeWorld(states, -64, 320);
    private final List<Notice> notices = new ArrayList<>();
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

    // Fake services
    private final List<List<GhostPlacement>> shownGhosts = new ArrayList<>();
    private final List<GhostVolume> released = new ArrayList<>();
    private final List<Long> confirmations = new ArrayList<>();
    private final AtomicLong worldChanges = new AtomicLong();
    private final List<Box> watched = new ArrayList<>();
    private double[] eye;
    private long ghostNanos;
    private final EditorKeymap keymap = EditorKeymap.defaults();

    private final ScatterTool.Services services = new ScatterTool.Services() {
        @Override
        public Optional<double[]> eye() {
            return Optional.ofNullable(eye);
        }

        @Override
        public void showGhosts(List<GhostPlacement> placements) {
            shownGhosts.add(List.copyOf(placements));
        }

        @Override
        public String ghostStatus() {
            return "";
        }

        @Override
        public long ghostRenderNanos() {
            return ghostNanos;
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
        public String keyLabel(KeyAction action) {
            return keymap.display(action);
        }

        @Override
        public Optional<KeyAction> scrollAction(int modifiers) {
            return keymap.match(KeyChord.scroll(modifiers));
        }

        @Override
        public Translator translator() {
            return Translator.KEYS;
        }

        @Override
        public long changeStamp() {
            return 0;
        }

        @Override
        public ScatterTool.ChangeWatch watchChanges(Box box) {
            watched.add(box);
            long base = worldChanges.get();
            return new ScatterTool.ChangeWatch() {
                @Override
                public long changes() {
                    return worldChanges.get() - base;
                }

                @Override
                public void close() {}
            };
        }

        @Override
        public void confirm(String opNameKey, long blocks, Runnable onConfirm) {
            confirmations.add(blocks);
            onConfirm.run();
        }

        @Override
        public boolean windowFocused() {
            return true;
        }

        @Override
        public void notify(Notice notice) {
            notices.add(notice);
        }
    };

    private final ScatterTool tool = new ScatterTool(services);
    private ToolContext view;
    private long now = 1_000 * MS;

    @BeforeEach
    void activate() {
        ctx.tools().register(tool);
        view = ctx.contextFor(ToolId.SCATTER);
        assertTrue(ctx.tools().activate(ToolId.SCATTER, view));
        world.setLoadedByDefault(true);
        frame(16); // the editor runs frames all the time: the tool's clock starts here
    }

    // ---- Helpers ----

    private static WorldCursor hit(double x, int y, double z) {
        return new WorldCursor(new BlockPos((int) Math.floor(x), y, (int) Math.floor(z)), WorldCursor.Face.UP, x, y + 1, z,
                false);
    }

    /** Advances the clock and runs one frame. */
    private void frame(long millis) {
        now += millis * MS;
        tool.frame(view, new FrameInfo(now, 0f, 0, 0, hit(0.5, 63, 0.5)));
    }

    private void pointer(PointerEvent.Kind kind, WorldCursor cursor, int modifiers) {
        tool.onPointer(view, new PointerEvent(kind, PointerEvent.LEFT, 0, 0, modifiers, cursor));
    }

    /** A stroke along x from {@code fromX} to {@code toX} at z. */
    private void stroke(double fromX, double toX, double z, int modifiers) {
        pointer(PointerEvent.Kind.PRESS, hit(fromX, 63, z), modifiers);
        pointer(PointerEvent.Kind.DRAG, hit(toX, 63, z), 0);
        pointer(PointerEvent.Kind.RELEASE, hit(toX, 63, z), 0);
    }

    private boolean action(EditorAction action) {
        return tool.onAction(view, action);
    }

    private ClipboardCache.Preview preview(String key) {
        BlockPos dims = new BlockPos(3, 4, 3);
        BlockBuffer cells = new BlockBuffer();
        cells.set(1, 0, 1, states.state("minecraft:oak_log"));
        cells.set(1, 3, 1, states.state("minecraft:oak_stairs[facing=north]"));
        GhostVolume volume = GhostVolume.of(cells, GhostBaker.air(states));
        volume.setFrame(new Box(BlockPos.ORIGIN, dims.offset(-1, -1, -1)));
        return new ClipboardCache.Preview(key, dims, new BlockPos(1, 0, 1), 2, volume, 1024, 0);
    }

    /** An asset whose preview the mock has, added through the Library's Add to scatter. */
    private void addAsset() {
        session.putPreview(ASSET, preview("asset"));
        assertTrue(tool.addAsset("trees/oak.schem", HASH));
    }

    private List<ScatterPreviewRequest> previewsSent() {
        return session.calls().stream().filter(call -> call.kind().equals("scatter_preview"))
                .map(call -> (ScatterPreviewRequest) call.argument()).toList();
    }

    private List<String> noticeKeys() {
        return notices.stream().map(Notice::key).toList();
    }

    private List<GhostPlacement> lastGhosts() {
        return shownGhosts.get(shownGhosts.size() - 1);
    }

    /** A planner answering {@code count} placements of variant 0 on a 4-block grid, turned as {@code turn} says. */
    private void planner(int count, Function<Integer, Transform> turn) {
        session.setScatterPlanner(request -> {
            List<ScatterPlan.Placement> placements = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                placements.add(new ScatterPlan.Placement(new BlockPos((i % 100) * 4, 64, (i / 100) * 4), 0, turn.apply(i)));
            }
            return new ScatterPreviewResult(1, UUID.randomUUID(), new TreeMap<>(java.util.Map.of("SLOPE", 7)),
                    12L * count, count == 0 ? null : Box.of(new BlockPos(-1, 63, -1), new BlockPos(401, 70, 401)),
                    placements);
        });
    }

    /** Paints, adds the asset and lets the debounce send a preview. */
    private void previewed() {
        addAsset();
        stroke(0.5, 10.5, 0.5, 0);
        frame(300);
        assertTrue(tool.plan().isPresent(), "the preview arrived");
    }

    // ---- Painting ----

    @Test
    void paintingStampsAlongTheDragAndAltErases() {
        ctx.updateSettings(ToolId.SCATTER, view.settings().with(tool.settings().radius, 8));
        stroke(0.5, 16.5, 0.5, 0);
        assertEquals(List.of(ScatterArea.Stamp.paint(0, 0, 8), ScatterArea.Stamp.paint(4, 0, 8),
                ScatterArea.Stamp.paint(8, 0, 8), ScatterArea.Stamp.paint(12, 0, 8), ScatterArea.Stamp.paint(16, 0, 8)),
                tool.area().stamps(), "every half radius along the path");
        // Alt+drag erases, from where the press was (an Alt click without a drag places one instead).
        stroke(8.5, 12.5, 0.5, Modifiers.ALT);
        assertEquals(ScatterArea.Stamp.erase(8, 0, 8), tool.area().stamps().get(5));
        assertEquals(ScatterArea.Stamp.erase(12, 0, 8), tool.area().stamps().get(6));
        assertFalse(tool.area().mask().contains(8, 0));
        assertFalse(tool.painting());
    }

    @Test
    void altClickPlacesOneItemAtTheSpotAndCommitsIt() {
        previewed();
        int painted = tool.area().stampCount();
        int before = previewsSent().size();
        pointer(PointerEvent.Kind.PRESS, hit(20.5, 63, 4.5), Modifiers.ALT);
        pointer(PointerEvent.Kind.DRAG, hit(20.7, 63, 4.2), 0); // within the same column: still a click
        pointer(PointerEvent.Kind.RELEASE, hit(20.7, 63, 4.2), 0);
        assertEquals(painted, tool.area().stampCount(), "a click erases nothing");
        assertEquals(before + 1, previewsSent().size());
        ScatterPreviewRequest one = previewsSent().get(previewsSent().size() - 1);
        assertEquals(new ScatterArea.Stamps(List.of(ScatterArea.Stamp.paint(20, 4, 0))), one.area());
        assertEquals(new ScatterSettings.Density.Count(1), one.settings().density());
        // The mock plans one placement on the stamp's centre; the tool commits it at once.
        ToolAction.RunOp run = assertInstanceOf(ToolAction.RunOp.class, session.sent().get(session.sent().size() - 1));
        assertInstanceOf(OpSpec.ScatterCommit.class, run.op());
        assertFalse(tool.placingOne());
        // The painted area is previewed again.
        frame(300);
        assertEquals(before + 2, previewsSent().size());
        assertEquals(tool.area().toArea().orElseThrow(), previewsSent().get(previewsSent().size() - 1).area());
    }

    @Test
    void escCancelsAnAltClickWhosePlanHasNotComeBack() {
        previewed();
        session.setHoldScatterPreviews(true);
        pointer(PointerEvent.Kind.PRESS, hit(20.5, 63, 4.5), Modifiers.ALT);
        pointer(PointerEvent.Kind.RELEASE, hit(20.5, 63, 4.5), 0);
        assertTrue(tool.placingOne());
        assertTrue(session.heldScatterPreview().isPresent());
        int sent = session.sent().size();
        assertTrue(action(EditorAction.CANCEL), "Esc takes the pending Alt+click");
        assertFalse(tool.placingOne());
        // The plan comes back afterwards: nothing is committed.
        ScatterPreviewRequest one = session.heldScatterPreview().orElseThrow();
        session.completeScatterPreview(Reply.ok(new ScatterPreviewResult(1, UUID.randomUUID(), new TreeMap<>(), 3, null,
                List.of(new ScatterPlan.Placement(new BlockPos(20, 64, 4), 0, Transform.IDENTITY)))));
        assertEquals(new ScatterArea.Stamps(List.of(ScatterArea.Stamp.paint(20, 4, 0))), one.area());
        assertEquals(sent, session.sent().size(), "no commit after Esc");
        assertFalse(session.sent().stream().anyMatch(action -> action instanceof ToolAction.RunOp run
                && run.op() instanceof OpSpec.ScatterCommit), "no scatter commit at all");
    }

    @Test
    void anAltClickWhereNothingFitsSaysWhy() {
        addAsset();
        frame(16); // the asset's preview is in
        session.setScatterPlanner(request -> new ScatterPreviewResult(1, UUID.randomUUID(),
                new TreeMap<>(java.util.Map.of("SLOPE", 1)), 0, null, List.of()));
        int sent = session.sent().size();
        pointer(PointerEvent.Kind.PRESS, hit(3.5, 63, 3.5), Modifiers.ALT);
        pointer(PointerEvent.Kind.RELEASE, hit(3.5, 63, 3.5), 0);
        assertEquals(sent, session.sent().size(), "nothing committed");
        Notice last = notices.get(notices.size() - 1);
        assertEquals("sculptory.notice.scatter_one_nothing", last.key());
        assertTrue(last.args().get(0).contains("slope"), last.args().toString());
    }

    @Test
    void treesAndFeaturesJoinTheMixAndAreSentAsFeatures() {
        FeatureCatalog.FeatureDef oak = FeatureCatalog.find("minecraft:fancy_oak").orElseThrow();
        assertTrue(tool.addFeature(oak));
        assertEquals(new ScatterSource.Feature("minecraft:fancy_oak"), tool.mix().variants().get(0).source());
        frame(16);
        assertEquals(VariantPreviews.Status.READY, tool.variantStatus(new ScatterSource.Feature("minecraft:fancy_oak")));
        stroke(0.5, 10.5, 0.5, 0);
        frame(300);
        assertEquals(List.of(new C2S.ScatterPreview.Variant(new ScatterSource.Feature("minecraft:fancy_oak"),
                ScatterMix.DEFAULT_WEIGHT)), previewsSent().get(previewsSent().size() - 1).variants());
        assertTrue(tool.plan().isPresent());
        assertFalse(tool.addFeature(oak), "a duplicate");
    }

    @Test
    void theGrownCellsOfAPlanShowAsOneGhost() {
        assertTrue(tool.addFeature(FeatureCatalog.find("minecraft:oak").orElseThrow()));
        GeneratedSource.Builder cells = GeneratedSource.builder(100);
        cells.set(4, 64, 4, states.state("minecraft:oak_log"));
        cells.set(4, 65, 4, states.state("minecraft:oak_log"));
        cells.set(4, 63, 4, states.state("minecraft:dirt"));
        byte[] payload = SparseUpload.encode(cells.build(), states);
        session.setScatterPlanner(request -> new ScatterPreviewResult(1, UUID.randomUUID(), new TreeMap<>(), 3,
                Box.of(new BlockPos(4, 63, 4), new BlockPos(4, 65, 4)),
                List.of(new ScatterPlan.Placement(new BlockPos(4, 64, 4), 0, Transform.IDENTITY)), payload));
        stroke(0.5, 10.5, 0.5, 0);
        frame(300);
        frame(16);
        assertTrue(tool.grownGhost().isPresent(), "the grown ghost is built");
        assertEquals(3, tool.grownGhost().get().blockCount());
        assertTrue(lastGhosts().stream().anyMatch(ghost -> ghost.volume() == tool.grownGhost().get()));
        tool.clearPreview();
        assertTrue(tool.grownGhost().isEmpty(), "cleared with the plan");
    }

    @Test
    void ctrlScrollSetsTheRadiusWithoutAPreview() {
        addAsset();
        stroke(0.5, 0.5, 0.5, 0);
        frame(300);
        int previews = previewsSent().size();
        int radius = view.settings().get(tool.settings().radius);
        assertTrue(tool.onScroll(view, new ScrollEvent(1, Modifiers.CONTROL)));
        assertTrue(tool.onScroll(view, new ScrollEvent(1, Modifiers.CONTROL | Modifiers.SHIFT)));
        assertEquals(radius + 5, view.settings().get(tool.settings().radius));
        frame(300);
        assertEquals(previews, previewsSent().size(), "the painting radius alone doesn't change the plan");
        for (int i = 0; i < 40; i++) tool.onScroll(view, new ScrollEvent(1, Modifiers.CONTROL | Modifiers.SHIFT));
        assertEquals(ScatterArea.MAX_RADIUS, view.settings().get(tool.settings().radius));
        assertFalse(tool.onScroll(view, new ScrollEvent(1, 0)), "plain scroll is the fly speed's");
    }

    @Test
    void theStampCapIsToastedOncePerStroke() {
        ctx.updateSettings(ToolId.SCATTER, view.settings().with(tool.settings().radius, 2));
        pointer(PointerEvent.Kind.PRESS, hit(0.5, 63, 0.5), 0);
        pointer(PointerEvent.Kind.DRAG, hit(5_000.5, 63, 0.5), 0);
        pointer(PointerEvent.Kind.DRAG, hit(9_000.5, 63, 0.5), 0);
        pointer(PointerEvent.Kind.RELEASE, hit(9_000.5, 63, 0.5), 0);
        assertEquals(PaintedArea.MAX_STAMPS, tool.area().stampCount());
        assertEquals(List.of("sculptory.notice.scatter_area_full"), noticeKeys());
        assertTrue(tool.hudLines().stream().anyMatch(line -> line.text().startsWith("sculptory.scatter.status.stamps")));
    }

    @Test
    void ctrlZUndoesTheLastStrokeUntilACommitThenTheServerHistory() {
        addAsset();
        stroke(0.5, 0.5, 0.5, 0);
        stroke(20.5, 20.5, 0.5, 0);
        assertTrue(action(EditorAction.UNDO));
        assertEquals(1, tool.area().stampCount());
        frame(300);
        assertEquals(List.of(ScatterArea.Stamp.paint(0, 0, 8)),
                ((ScatterArea.Stamps) previewsSent().get(previewsSent().size() - 1).area()).stamps());
        assertTrue(action(EditorAction.COMMIT));
        assertFalse(action(EditorAction.UNDO), "after a commit, Ctrl+Z undoes the scatter on the server");
        assertEquals(1, tool.area().stampCount(), "the area is kept");
    }

    private boolean undoHinted() {
        return tool.hints(view).stream().anyMatch(hint -> hint.descriptionKey().equals("sculptory.hint.scatter.undo"));
    }

    /** B2: after a commit, Ctrl+Z never reaches back to the strokes painted before it, even once new ones follow. */
    @Test
    void strokesPaintedBeforeACommitAreOutOfCtrlZsReach() {
        addAsset();
        stroke(0.5, 0.5, 0.5, 0);
        frame(300);
        assertTrue(action(EditorAction.COMMIT));
        stroke(20.5, 20.5, 0.5, 0);
        assertEquals(2, tool.area().stampCount());
        assertTrue(undoHinted());
        assertTrue(action(EditorAction.UNDO), "the stroke painted after the commit");
        assertEquals(1, tool.area().stampCount());
        assertFalse(undoHinted());
        assertFalse(action(EditorAction.UNDO), "the stroke before the commit is sealed: Ctrl+Z is the server's");
        assertEquals(1, tool.area().stampCount(), "and it stays painted");
    }

    /**
     * B2: an edit made with another tool after painting is newer than the strokes: Ctrl+Z undoes it on the server, and
     * the strokes painted before it stay out of reach; strokes painted after it are undone as usual.
     */
    @Test
    void anEditWithAnotherToolTakesCtrlZFromTheStrokesBeforeIt() {
        stroke(0.5, 0.5, 0.5, 0);
        assertTrue(undoHinted());
        fillWithAnotherTool();
        assertFalse(undoHinted());
        assertFalse(action(EditorAction.UNDO), "Ctrl+Z undoes the other tool's fill");
        assertEquals(1, tool.area().stampCount(), "the stroke is kept");

        // Painted, then another tool's edit, then painted again: only the newest stroke is local.
        stroke(20.5, 20.5, 0.5, 0);
        fillWithAnotherTool();
        stroke(40.5, 40.5, 0.5, 0);
        assertEquals(3, tool.area().stampCount());
        assertTrue(action(EditorAction.UNDO));
        assertEquals(2, tool.area().stampCount());
        assertFalse(action(EditorAction.UNDO), "the stroke before the fill is older than the fill");
        assertEquals(2, tool.area().stampCount());
    }

    /** A fill by another tool that finishes and lands in the server history. */
    private void fillWithAnotherTool() {
        session.send(new ToolAction.RunOp(new OpSpec.Fill(Box.of(new BlockPos(100, 64, 100), new BlockPos(101, 64, 101)),
                new dev.sculptory.core.edit.Pattern.Single(states.state("minecraft:stone")),
                dev.sculptory.core.edit.CellMask.ANY)));
        session.finishJobs();
    }

    @Test
    void useSelectionScattersOverTheBox() {
        addAsset();
        Box box = Box.of(new BlockPos(0, 60, 0), new BlockPos(47, 90, 31));
        ctx.setSelection(box);
        assertTrue(tool.useSelection());
        frame(300);
        assertEquals(new ScatterArea.Region(box), previewsSent().get(0).area());
        ctx.setSelection(null);
        assertFalse(tool.useSelection());
        assertEquals("sculptory.notice.select_first", notices.get(notices.size() - 1).key());
    }

    // ---- Previews ----

    @Test
    void changesArePreviewedOnce250MillisecondsAfterTheLastOne() {
        addAsset();
        stroke(0.5, 10.5, 0.5, 0);
        frame(100);
        assertTrue(previewsSent().isEmpty(), "still within the debounce");
        ctx.updateSettings(ToolId.SCATTER, view.settings().with(tool.settings().spacing, 9));
        frame(200);
        assertTrue(previewsSent().isEmpty(), "the setting restarted the wait");
        frame(60);
        assertEquals(1, previewsSent().size());
        assertEquals(9, previewsSent().get(0).settings().spacing());
        frame(1_000);
        assertEquals(1, previewsSent().size(), "nothing more without a change");
    }

    @Test
    void theSeedsRandomButtonRerollsThePreviewReusingTheBakedVolumes() {
        planner(2, i -> Transform.rotation(2));
        previewed();
        frame(16); // the half-turned variant is baked
        GhostVolume baked = lastGhosts().get(0).volume();
        assertTrue(baked != tool.variantPreview(HELD_ASSET).orElseThrow().volume());
        ScatterPreviewResult first = tool.plan().orElseThrow();
        ctx.updateSettings(ToolId.SCATTER, view.settings().with(tool.settings().seed, 99L));
        frame(300);
        assertEquals(99L, previewsSent().get(previewsSent().size() - 1).settings().seed());
        assertTrue(tool.plan().orElseThrow() != first);
        assertSame(baked, lastGhosts().get(0).volume(), "the new plan draws the same baked volume: no new meshes");
        assertTrue(released.isEmpty());
        assertTrue(action(EditorAction.CANCEL));
        assertEquals(List.of(baked), released, "clearing the preview frees it");
    }

    @Test
    void assetsArePreviewedBeforeThePreviewIsSent() {
        session.setHoldTransfers(true);
        tool.addAsset("trees/oak.schem", HASH);
        stroke(0.5, 0.5, 0.5, 0);
        frame(300);
        frame(300);
        assertTrue(previewsSent().isEmpty(), "waiting for the server to hold the asset");
        assertTrue(tool.hudLines().stream().anyMatch(l -> l.text().startsWith("sculptory.scatter.status.loading")));
        session.completeHeld(session.heldTransfers().get(0), Reply.ok(preview("asset")));
        frame(16);
        assertEquals(1, previewsSent().size());
        assertEquals(List.of(new dev.sculptory.protocol.v2.C2S.ScatterPreview.Variant(ASSET, ScatterMix.DEFAULT_WEIGHT)),
                previewsSent().get(0).variants());
    }

    @Test
    void thePlacementsFeedTheGhostListWithSharedVolumes() {
        planner(3, i -> i == 2 ? Transform.rotation(1) : Transform.IDENTITY);
        previewed();
        frame(16);
        List<GhostPlacement> ghosts = lastGhosts();
        assertEquals(3, ghosts.size());
        ClipboardCache.Preview preview = tool.variantPreview(HELD_ASSET).orElseThrow();
        assertSame(preview.volume(), ghosts.get(0).volume());
        assertSame(preview.volume(), ghosts.get(1).volume(), "one volume for every placement of the variant");
        // The anchor (1, 0, 1) lands on each placement's anchor.
        assertEquals(new BlockPos(-1, 64, -1), new BlockPos(ghosts.get(0).originX(), ghosts.get(0).originY(),
                ghosts.get(0).originZ()));
        assertEquals(new BlockPos(3, 64, -1), new BlockPos(ghosts.get(1).originX(), ghosts.get(1).originY(),
                ghosts.get(1).originZ()));
        assertTrue(ghosts.get(2).volume() != preview.volume(), "the turned one was baked (the executor ran at once)");
        assertEquals(Transform.IDENTITY, ghosts.get(2).transform());
        // (Translator.KEYS has no texts, so outcome names fall back to their lower-cased enum names.)
        assertEquals("sculptory.scatter.summary[3,36]  ·  sculptory.scatter.summary.skipped[slope 7]",
                tool.hudLines().get(0).text());
    }

    @Test
    void thousandsOfPlacementsAreCappedToTheNearestGhostsAndOutlinesAndDots() {
        planner(12_000, i -> Transform.IDENTITY);
        eye = new double[] {200, 70, 240}; // over the middle of the 400 × 480 grid
        previewed();
        frame(16);
        ScatterLod.Plan plan = tool.drawPlan().orElseThrow();
        assertEquals(ScatterLod.DEFAULT.ghosts(), lastGhosts().size());
        assertEquals(ScatterLod.DEFAULT.ghosts(), plan.ghosts());
        assertTrue(plan.boxes() > 0 && plan.dots() > 0);
        CountingDraw draw = new CountingDraw();
        tool.renderWorld(view, draw);
        assertEquals(plan.boxes(), draw.boxes, "an outline per outlined placement");
        assertTrue(draw.lines >= plan.dots(), "a mark per dot");
        assertTrue(tool.hudLines().stream().anyMatch(l -> l.text().startsWith("sculptory.scatter.status.lod")));

        // A slow ghost pass lowers the cap; the same meshes are drawn, fewer of them.
        ghostNanos = ScatterLod.GHOST_BUDGET_NANOS * 3;
        frame(300);
        frame(16);
        assertEquals(1_500, tool.caps().ghosts());
        assertEquals(1_500, lastGhosts().size());
        assertSame(lastGhosts().get(0).volume(), tool.variantPreview(HELD_ASSET).orElseThrow().volume());
    }

    @Test
    void onlyTheLatestPreviewIsShown() {
        session.setHoldScatterPreviews(true);
        addAsset();
        stroke(0.5, 0.5, 0.5, 0);
        frame(300);
        assertTrue(tool.planning());
        ctx.updateSettings(ToolId.SCATTER, view.settings().with(tool.settings().seed, 5L));
        frame(300);
        assertEquals(2, previewsSent().size());
        assertTrue(tool.planning(), "the first was replaced (its answer is ignored)");
        planner(1, i -> Transform.IDENTITY);
        session.completeScatterPreview(Reply.ok(new ScatterPreviewResult(2, UUID.randomUUID(), new TreeMap<>(), 12,
                Box.of(BlockPos.ORIGIN, new BlockPos(2, 3, 2)),
                List.of(new ScatterPlan.Placement(new BlockPos(1, 64, 1), 0, Transform.IDENTITY)))));
        assertFalse(tool.planning());
        assertEquals(1, tool.plan().orElseThrow().placements().size());
        assertTrue(noticeKeys().stream().noneMatch(key -> key.contains("superseded")));
    }

    @Test
    void refusalsShowReadablyAndAreNotRetriedOnTheirOwn() {
        session.setHoldScatterPreviews(true);
        addAsset();
        stroke(0.5, 0.5, 0.5, 0);
        frame(300);
        session.completeScatterPreview(Reply.refused(RejectReason.AREA_BUSY, "another edit is still working in the area"));
        assertEquals("sculptory.scatter.status.refused.area_busy[R]", tool.hudLines().get(0).text());
        frame(1_000);
        frame(1_000);
        assertEquals(1, previewsSent().size(), "no retry loop");
        assertTrue(action(EditorAction.ROTATE_CW), "R asks again");
        frame(16);
        assertEquals(2, previewsSent().size());
        session.completeScatterPreview(Reply.refused(RejectReason.RATE_LIMITED, "the last scatter took too long; wait 7 s"));
        assertEquals("sculptory.scatter.status.refused.rate_limited[R]", tool.hudLines().get(0).text());
    }

    @Test
    void anAssetTheServerLetGoOfIsPreviewedAgainOnceAndThePreviewResent() {
        session.setHoldScatterPreviews(true);
        addAsset();
        stroke(0.5, 0.5, 0.5, 0);
        frame(300);
        long previewRequests = session.calls().stream().filter(c -> c.kind().equals("preview")).count();
        session.setHoldTransfers(true);
        session.completeScatterPreview(Reply.refused(RejectReason.ASSET_NOT_LOADED, "asset not loaded on the server"));
        assertEquals(previewRequests + 1, session.calls().stream().filter(c -> c.kind().equals("preview")).count(),
                "the asset's preview is asked for again (which makes the server load it again)");
        frame(300);
        assertEquals(1, previewsSent().size(), "waiting for that preview");
        session.completeHeld(session.heldTransfers().get(0), Reply.ok(preview("asset")));
        frame(16);
        assertEquals(2, previewsSent().size(), "then the scatter preview is sent again");
        session.completeScatterPreview(Reply.refused(RejectReason.ASSET_NOT_LOADED, "asset not loaded on the server"));
        frame(300);
        assertEquals(2, previewsSent().size(), "only once");
        assertEquals("sculptory.scatter.status.refused[sculptory.reject.asset_not_loaded]", tool.hudLines().get(0).text());
    }

    /**
     * B4: a plan the tool cannot show (bounds at the edge of the int range, which a broken server could send past the
     * session's checks) is dropped whole: no HUD for a plan without ghosts, nothing left to commit.
     */
    @Test
    void aPlanThatCannotBeShownIsDroppedWhole() {
        previewed();
        assertFalse(lastGhosts().isEmpty());
        session.setScatterPlanner(request -> new ScatterPreviewResult(1, UUID.randomUUID(), new TreeMap<>(), 12L,
                Box.of(new BlockPos(Integer.MAX_VALUE - 2, 64, 0), new BlockPos(Integer.MAX_VALUE, 66, 2)),
                List.of(new ScatterPlan.Placement(new BlockPos(Integer.MAX_VALUE - 1, 64, 1), 0, Transform.IDENTITY))));
        assertTrue(action(EditorAction.ROTATE_CW));
        frame(150); // past the minimum interval between previews
        assertFalse(tool.planning());
        assertTrue(tool.plan().isEmpty(), "the plan was half shown");
        assertTrue(lastGhosts().isEmpty(), "the old plan's ghosts are still shown");
        assertEquals("sculptory.scatter.status.failed[R]", tool.hudLines().get(0).text());
        int sent = session.sent().size();
        assertTrue(action(EditorAction.COMMIT));
        assertEquals(sent, session.sent().size(), "nothing to commit");
    }

    /** B3: an INVALID refusal (bad settings, say) is shown; the assets are not downloaded again. */
    @Test
    void anInvalidRefusalDoesNotDownloadTheAssetsAgain() {
        session.setHoldScatterPreviews(true);
        addAsset();
        stroke(0.5, 0.5, 0.5, 0);
        frame(300);
        long previewRequests = session.calls().stream().filter(c -> c.kind().equals("preview")).count();
        session.completeScatterPreview(Reply.refused(RejectReason.INVALID, "the filter names an unknown block"));
        frame(300);
        assertEquals(previewRequests, session.calls().stream().filter(c -> c.kind().equals("preview")).count(),
                "no asset preview asked for again");
        assertEquals(1, previewsSent().size(), "and no preview sent again");
        assertEquals("sculptory.scatter.status.refused[sculptory.reject.invalid]", tool.hudLines().get(0).text());
    }

    @Test
    void aReplacedClipboardVariantIsLeftOut() {
        session.copy(Box.of(BlockPos.ORIGIN, new BlockPos(2, 2, 2)), BlockPos.ORIGIN, false);
        assertTrue(tool.addClipboard(session, "Copy"));
        addAsset();
        stroke(0.5, 0.5, 0.5, 0);
        frame(300);
        assertEquals(2, previewsSent().get(0).variants().size());
        session.copy(Box.of(BlockPos.ORIGIN, new BlockPos(4, 4, 4)), BlockPos.ORIGIN, false); // replaces the clipboard
        ctx.updateSettings(ToolId.SCATTER, view.settings().with(tool.settings().seed, 3L));
        frame(300);
        assertEquals(List.of(HELD_ASSET), previewsSent().get(1).variants().stream()
                .map(dev.sculptory.protocol.v2.C2S.ScatterPreview.Variant::source).toList());
        assertEquals(List.of("trees/oak.schem"), tool.planVariants().stream().map(ScatterMix.Variant::name).toList());
    }

    @Test
    void addingFromTheLibraryToastsWhatHappened() {
        addAsset();
        assertFalse(tool.addAsset("trees/oak.schem", HASH));
        assertFalse(tool.addAsset("new.schem", ""), "not indexed yet");
        assertEquals(List.of("sculptory.notice.scatter_added", "sculptory.notice.scatter_duplicate",
                "sculptory.notice.asset_not_indexed"), noticeKeys());
    }

    // ---- Staleness ----

    @Test
    void aPlanGoesOutOfDateWhenBlocksAroundItChange() {
        planner(2, i -> Transform.IDENTITY);
        previewed();
        assertEquals(Box.of(new BlockPos(-2, 62, -2), new BlockPos(402, 71, 402)), watched.get(0),
                "the plan's bounds, the ground below and a block around");
        assertEquals(PlanStaleness.FRESH, tool.staleness());
        worldChanges.incrementAndGet();
        assertEquals(PlanStaleness.CHANGED, tool.staleness());
        assertEquals("sculptory.scatter.status.stale[R]", tool.hudLines().get(1).text());
        assertTrue(action(EditorAction.ROTATE_CW));
        frame(PreviewScheduler.MIN_INTERVAL_MILLIS);
        assertEquals(PlanStaleness.FRESH, tool.staleness(), "R made a fresh plan");
    }

    @Test
    void anExpiredPlanIsRefreshedInsteadOfCommitted() {
        previewed();
        frame(EditorSession.SCATTER_PLAN_TTL_NANOS / MS);
        assertEquals(PlanStaleness.EXPIRED, tool.staleness());
        int sent = session.sent().size();
        assertTrue(action(EditorAction.COMMIT));
        assertEquals(sent, session.sent().size(), "nothing committed");
        assertEquals("sculptory.notice.scatter_expired", notices.get(notices.size() - 1).key());
        frame(16);
        assertTrue(tool.plan().isPresent());
        assertEquals(PlanStaleness.FRESH, tool.staleness());
    }

    // ---- Commit and Esc ----

    @Test
    void enterCommitsThePlanThenClearsThePreviewButKeepsTheAreaAndSettings() {
        previewed();
        UUID planId = tool.plan().orElseThrow().planId();
        assertTrue(action(EditorAction.COMMIT));
        ToolAction.RunOp run = assertInstanceOf(ToolAction.RunOp.class, session.sent().get(session.sent().size() - 1));
        assertEquals(new OpSpec.ScatterCommit(planId), run.op());
        assertTrue(tool.plan().isEmpty());
        assertTrue(lastGhosts().isEmpty());
        assertEquals(3, tool.area().stampCount());
        assertEquals(1, session.jobs().jobs().size(), "the commit is a job");
        assertTrue(action(EditorAction.COMMIT));
        assertEquals("sculptory.notice.scatter_no_preview", notices.get(notices.size() - 1).key());
    }

    @Test
    void aCommitWhileAPreviewIsComingIsHeldBack() {
        session.setHoldScatterPreviews(true);
        addAsset();
        stroke(0.5, 0.5, 0.5, 0);
        frame(300);
        assertTrue(action(EditorAction.COMMIT));
        assertEquals("sculptory.notice.scatter_planning", notices.get(notices.size() - 1).key());
        assertTrue(session.sent().isEmpty());
    }

    @Test
    void aLargeCommitAsksFirstAndATooLargeOneIsStoppedHere() {
        planner(50_000, i -> Transform.IDENTITY); // 600,000 cells
        previewed();
        assertTrue(action(EditorAction.COMMIT));
        assertEquals(List.of(600_000L), confirmations);

        session.setScatterPlanner(request -> new ScatterPreviewResult(1, UUID.randomUUID(), new TreeMap<>(), 3_000_000L,
                Box.of(BlockPos.ORIGIN, BlockPos.ORIGIN), List.of(new ScatterPlan.Placement(BlockPos.ORIGIN, 0,
                Transform.IDENTITY))));
        assertTrue(action(EditorAction.ROTATE_CW));
        frame(300);
        session.setPermissions(new Permissions(
                Perm.mask(EnumSet.complementOf(EnumSet.of(Perm.LIMIT_BYPASS))), Limits.DEFAULTS));
        int sent = session.sent().size();
        assertTrue(action(EditorAction.COMMIT));
        assertEquals(sent, session.sent().size(), "3,000,000 blocks is over the server's limit");
        assertEquals("sculptory.notice.too_large", notices.get(notices.size() - 1).key());
    }

    @Test
    void aCommitTheServerNoLongerKnowsSaysSo() {
        previewed();
        // Another preview (say from a second client window) replaced the plan on the server.
        session.scatterPreview(previewsSent().get(0));
        assertTrue(action(EditorAction.COMMIT));
        assertEquals(Notice.of(Notice.Level.WARNING, "sculptory.notice.scatter_commit_invalid", "R"),
                notices.get(notices.size() - 1));
    }

    @Test
    void escCancelsTheStrokeThenThePreviewThenTheArea() {
        previewed();
        pointer(PointerEvent.Kind.PRESS, hit(40.5, 63, 40.5), 0);
        pointer(PointerEvent.Kind.DRAG, hit(60.5, 63, 40.5), 0);
        assertTrue(tool.painting());
        int stamps = tool.area().stampCount();
        assertTrue(action(EditorAction.CANCEL));
        assertFalse(tool.painting());
        assertEquals(3, tool.area().stampCount(), "the stroke's " + (stamps - 3) + " stamps are gone");
        frame(300);
        assertTrue(tool.plan().isPresent());

        assertTrue(action(EditorAction.CANCEL));
        assertTrue(tool.plan().isEmpty());
        assertTrue(lastGhosts().isEmpty());
        assertEquals(3, tool.area().stampCount());
        frame(300);
        assertTrue(tool.plan().isEmpty(), "a cleared preview does not come back on its own");

        assertTrue(action(EditorAction.CANCEL));
        assertTrue(tool.area().isEmpty());
        assertFalse(action(EditorAction.CANCEL), "the last rung leaves the editor");
    }

    @Test
    void deleteClearsTheAreaAndItsPreview() {
        previewed();
        assertTrue(action(EditorAction.ERASE_SELECTION));
        assertTrue(tool.area().isEmpty());
        assertTrue(tool.plan().isEmpty());
        assertFalse(action(EditorAction.ERASE_SELECTION), "with nothing to clear, Delete is the selection's again");
    }

    @Test
    void theAreaIsTintedAndOutlinedOnTheSurface() {
        world.setHeightHints(true);
        for (int x = -10; x <= 20; x++) {
            for (int z = -10; z <= 10; z++) world.set(x, 62, z, states.state("minecraft:stone"));
        }
        ctx.updateSettings(ToolId.SCATTER, view.settings().with(tool.settings().radius, 3));
        stroke(0.5, 0.5, 0.5, 0);
        frame(16);
        CountingDraw draw = new CountingDraw();
        tool.renderWorld(view, draw);
        assertEquals(29, draw.quads, "one tint per painted column (a radius-3 disc)");
        assertTrue(draw.quadYs.stream().allMatch(y -> y > 63 && y < 63.1), "on the stone's top face");
        assertTrue(draw.lines > 0, "the outline and the cursor ring");
    }

    // ---- Block variants ----

    private static ScatterSource block(String state) {
        return new ScatterSource.Block(state);
    }

    private boolean addBlock(String state) {
        return tool.addBlock(dev.sculptory.core.BlockDescriptor.parse(state));
    }

    @Test
    void blocksJoinTheMixByExactStateAndAirFluidsAndUnknownBlocksAreRefused() {
        assertTrue(addBlock("minecraft:poppy"));
        assertFalse(addBlock("minecraft:poppy"), "one variant per exact state");
        assertTrue(addBlock("minecraft:tall_grass[half=upper]"));
        assertTrue(addBlock("minecraft:pink_petals[facing=north,flower_amount=2]"));
        assertTrue(addBlock("minecraft:pink_petals[facing=east,flower_amount=2]"));
        assertFalse(addBlock("minecraft:air"));
        assertFalse(addBlock("minecraft:water[level=0]"));
        assertFalse(addBlock("minecraft:not_a_block"));
        assertEquals(List.of(block("minecraft:poppy"), block("minecraft:tall_grass[half=lower]"),
                block("minecraft:pink_petals[facing=north,flower_amount=2]"),
                block("minecraft:pink_petals[facing=east,flower_amount=2]")),
                tool.mix().variants().stream().map(ScatterMix.Variant::source).toList(),
                "a double-tall block goes in by its lower half");
        assertEquals(List.of("sculptory.notice.scatter_added", "sculptory.notice.scatter_duplicate",
                "sculptory.notice.scatter_added", "sculptory.notice.scatter_added",
                "sculptory.notice.scatter_added", "sculptory.notice.scatter_block_refused",
                "sculptory.notice.scatter_block_refused", "sculptory.notice.scatter_block_refused"), noticeKeys());
        assertEquals(List.of("minecraft:air", "air is not a scatter variant"), notices.get(5).args());
        assertEquals(List.of("minecraft:water", "minecraft:water is a fluid, not a scatter variant"),
                notices.get(6).args());
        assertEquals(List.of("minecraft:not_a_block", "unknown block minecraft:not_a_block"), notices.get(7).args());

        tool.removeVariant(0);
        assertTrue(addBlock("minecraft:poppy"), "back in once removed");
        assertEquals(block("minecraft:poppy"), tool.mix().variants().get(3).source());
    }

    /**
     * Water plants join the mix: a waterlogged state is kept (it goes under water), live coral goes in waterlogged, a
     * picked kelp_plant as the kelp on top of its column; a bubble column is refused. Each row knows where it goes.
     */
    @Test
    void waterPlantsJoinTheMixAndKnowWhereTheyGo() {
        assertTrue(addBlock("minecraft:seagrass"));
        assertTrue(addBlock("minecraft:sea_pickle[pickles=2,waterlogged=true]"));
        assertTrue(addBlock("minecraft:sea_pickle[pickles=2,waterlogged=false]"));
        assertTrue(addBlock("minecraft:kelp_plant"));
        assertTrue(addBlock("minecraft:tube_coral_fan[waterlogged=false]"));
        assertTrue(addBlock("minecraft:lily_pad"));
        assertFalse(addBlock("minecraft:bubble_column"));
        assertEquals(List.of(block("minecraft:seagrass"), block("minecraft:sea_pickle[pickles=2,waterlogged=true]"),
                block("minecraft:sea_pickle[pickles=2,waterlogged=false]"), block("minecraft:kelp[age=0]"),
                block("minecraft:tube_coral_fan[waterlogged=true]"), block("minecraft:lily_pad")),
                tool.mix().variants().stream().map(ScatterMix.Variant::source).toList());
        assertEquals(List.of("minecraft:bubble_column", "minecraft:bubble_column carries water but is not a water plant"),
                notices.get(6).args());
        frame(16);
        assertEquals(java.util.Optional.of(BlockVariants.Medium.UNDERWATER), tool.variantMedium(block("minecraft:seagrass")));
        assertEquals(java.util.Optional.of(BlockVariants.Medium.UNDERWATER),
                tool.variantMedium(block("minecraft:sea_pickle[pickles=2,waterlogged=true]")));
        assertEquals(java.util.Optional.of(BlockVariants.Medium.LAND),
                tool.variantMedium(block("minecraft:sea_pickle[pickles=2,waterlogged=false]")));
        assertEquals(java.util.Optional.of(BlockVariants.Medium.WATER_SURFACE),
                tool.variantMedium(block("minecraft:lily_pad")));
        assertEquals(java.util.Optional.empty(), tool.variantMedium(HELD_ASSET), "not a block");
    }

    /** Without brush or region (the server's rule too), no block joins the mix; clipboards and assets still do. */
    @Test
    void blocksNeedBrushOrRegion() {
        session.setPermissions(new Permissions(
                Perm.mask(EnumSet.complementOf(EnumSet.of(Perm.BRUSH, Perm.REGION))), Limits.DEFAULTS));
        assertFalse(addBlock("minecraft:poppy"));
        assertEquals(List.of("sculptory.notice.scatter_block_permission"), noticeKeys());
        assertTrue(tool.mix().isEmpty());
        addAsset();
        assertEquals(1, tool.mix().size());
        session.setPermissions(new Permissions(
                Perm.mask(EnumSet.complementOf(EnumSet.of(Perm.BRUSH))), Limits.DEFAULTS));
        assertTrue(addBlock("minecraft:poppy"), "region is enough");
        session.setPermissions(new Permissions(
                Perm.mask(EnumSet.complementOf(EnumSet.of(Perm.REGION))), Limits.DEFAULTS));
        assertTrue(addBlock("minecraft:tall_grass"), "brush is enough");
    }

    @Test
    void aBlockVariantIsSentAsItsStateWithTheSurvivalSetting() {
        addBlock("minecraft:poppy");
        addAsset();
        stroke(0.5, 0.5, 0.5, 0);
        frame(300);
        ScatterPreviewRequest sent = previewsSent().get(0);
        assertEquals(List.of(
                new dev.sculptory.protocol.v2.C2S.ScatterPreview.Variant(block("minecraft:poppy"),
                        ScatterMix.DEFAULT_WEIGHT),
                new dev.sculptory.protocol.v2.C2S.ScatterPreview.Variant(ASSET, ScatterMix.DEFAULT_WEIGHT)),
                sent.variants());
        assertTrue(sent.settings().fit().survive(), "only where it can survive, by default");
        assertEquals(List.of(ASSET), session.calls().stream().filter(call -> call.kind().equals("preview"))
                .map(call -> call.argument()).toList(), "a block needs no download");

        ctx.updateSettings(ToolId.SCATTER, view.settings().with(tool.settings().survive, false));
        frame(300);
        assertFalse(previewsSent().get(1).settings().fit().survive());
    }

    /** A block variant's ghost is built on the client from its state: one cell, or two for a double-tall block. */
    @Test
    void aBlockVariantsGhostIsBuiltFromItsState() {
        planner(3, i -> i == 2 ? Transform.rotation(1) : Transform.IDENTITY);
        addBlock("minecraft:tall_grass[half=lower]");
        stroke(0.5, 10.5, 0.5, 0);
        frame(300);
        assertTrue(tool.plan().isPresent(), "the preview arrived");
        frame(16);
        ScatterSource tall = block("minecraft:tall_grass[half=lower]");
        assertEquals(VariantPreviews.Status.READY, tool.variantStatus(tall));
        ClipboardCache.Preview preview = tool.variantPreview(tall).orElseThrow();
        assertEquals(new BlockPos(1, 2, 1), preview.dims());
        assertEquals(BlockPos.ORIGIN, preview.anchor());
        List<GhostPlacement> ghosts = lastGhosts();
        assertEquals(3, ghosts.size());
        assertSame(preview.volume(), ghosts.get(0).volume());
        assertEquals(new BlockPos(4, 64, 0), new BlockPos(ghosts.get(1).originX(), ghosts.get(1).originY(),
                ghosts.get(1).originZ()), "the lower half on the placement's anchor");
        assertTrue(session.calls().stream().noneMatch(call -> call.kind().equals("preview")),
                "nothing is downloaded for a block");
    }

    /**
     * A column plant's placement taller than one block shows its column (kelp_plant under kelp) as its ghost, and the
     * column height setting travels with the preview. Underwater placements also get a see-through outline: the water
     * surface hides their ghosts from above.
     */
    @Test
    void aColumnPlantsGhostIsItsColumnAndUnderwaterOnesShowThroughTheWater() {
        session.setScatterPlanner(request -> new ScatterPreviewResult(1, UUID.randomUUID(), new TreeMap<>(), 4L,
                Box.of(new BlockPos(0, 61, 0), new BlockPos(4, 63, 0)), List.of(
                        new ScatterPlan.Placement(new BlockPos(0, 61, 0), 0, Transform.IDENTITY, 3),
                        new ScatterPlan.Placement(new BlockPos(4, 61, 0), 0, Transform.IDENTITY, 1))));
        ctx.updateSettings(ToolId.SCATTER, view.settings().with(tool.settings().columnHeight,
                new dev.sculptory.fabric.client.editor.settings.SettingDef.IntSpan(1, 4)));
        addBlock("minecraft:kelp");
        stroke(0.5, 10.5, 0.5, 0);
        frame(300);
        assertTrue(tool.plan().isPresent(), "the preview arrived");
        assertEquals(new dev.sculptory.core.scatter.ScatterSettings.ColumnHeight(1, 4),
                previewsSent().get(0).settings().columnHeight());
        frame(16);
        List<GhostPlacement> ghosts = lastGhosts();
        assertEquals(2, ghosts.size());
        assertEquals(Box.of(BlockPos.ORIGIN, new BlockPos(0, 2, 0)), ghosts.get(0).volume().frame(), "three cells");
        assertSame(tool.variantPreview(block("minecraft:kelp[age=0]")).orElseThrow().volume(), ghosts.get(1).volume());
        assertEquals(new BlockPos(0, 61, 0), new BlockPos(ghosts.get(0).originX(), ghosts.get(0).originY(),
                ghosts.get(0).originZ()), "the column stands on its anchor");
        CountingDraw draw = new CountingDraw();
        tool.renderWorld(view, draw);
        assertEquals(2, draw.throughBoxes, "both kelp outlined through the water");
    }

    // ---- Fakes ----

    private static final class CountingDraw implements WorldDraw {
        int boxes;
        int lines;
        int quads;
        /** Boxes and lines drawn see-through. */
        int throughBoxes, throughLines;
        boolean through;
        final List<Double> quadYs = new ArrayList<>();

        @Override
        public void boxOutline(Box box, int argb) {
            boxes++;
            if (through) throughBoxes++;
        }

        @Override
        public void boxFill(Box box, int argb) {}

        @Override
        public void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb) {
            lines++;
            if (through) throughLines++;
        }

        @Override
        public void ring(double centerX, double y, double centerZ, double radius, int argb) {}

        @Override
        public void seeThrough(boolean enabled) {
            through = enabled;
        }

        @Override
        public void surfaceQuad(double minX, double minZ, double maxX, double maxZ, double y, int argb) {
            quads++;
            quadYs.add(y);
        }
    }
}
