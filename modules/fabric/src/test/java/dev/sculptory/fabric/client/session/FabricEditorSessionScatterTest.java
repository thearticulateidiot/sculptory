package dev.sculptory.fabric.client.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.SparseUpload;
import dev.sculptory.core.scatter.ScatterArea;
import dev.sculptory.core.scatter.ScatterPlan;
import dev.sculptory.core.scatter.ScatterSettings;
import dev.sculptory.core.scatter.ScatterSource;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.Codec;
import dev.sculptory.protocol.v2.Features;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.Message;
import dev.sculptory.protocol.v2.ProtocolException;
import dev.sculptory.protocol.v2.ProtocolV2;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.protocol.v2.ScatterPlacements;
import dev.sculptory.protocol.v2.StreamAbort;
import dev.sculptory.protocol.v2.StreamAssembler;
import dev.sculptory.protocol.v2.StreamCredit;
import dev.sculptory.protocol.v2.StreamKind;
import dev.sculptory.protocol.v2.StreamSender;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Scatter previews and commits of {@link FabricEditorSession} against a scripted server (M3). */
class FabricEditorSessionScatterTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final long MS = 1_000_000L;
    private static final String HASH = "cd".repeat(32);
    private static final UUID PLAN = new UUID(7, 7);
    private static final UUID JOB = new UUID(3, 3);
    private static final Features ALL = Features.of(Features.STROKES, Features.REGION_OPS, Features.HISTORY,
            Features.CLIPBOARD, Features.SCHEMATICS, Features.LIBRARY, Features.SCATTER);
    private static final ScatterPreviewRequest REQUEST = new ScatterPreviewRequest(
            new ScatterArea.Stamps(List.of(ScatterArea.Stamp.paint(10, 20, 8), ScatterArea.Stamp.erase(12, 20, 2))),
            new C2S.ScatterPreview.Settings(42L, 3, new ScatterSettings.Density.Fraction(0.25),
                    new SurfaceMask.Slope(0, 4), new ScatterSettings.Fit(true, 0.75)),
            List.of(new C2S.ScatterPreview.Variant(new SourceRef.Asset(HASH), 5),
                    new C2S.ScatterPreview.Variant(new SourceRef.Clipboard(new UUID(1, 1)), 1)),
            new ScatterSettings.Transforms(0b0101, true));
    private static final List<ScatterPlan.Placement> PLACEMENTS = List.of(
            new ScatterPlan.Placement(new BlockPos(10, 64, 20), 0, Transform.IDENTITY),
            new ScatterPlan.Placement(new BlockPos(14, 65, 18), 1, new Transform(2, Mirror.NONE)),
            new ScatterPlan.Placement(new BlockPos(7, 63, 25), 0, new Transform(0, Mirror.X)));
    private static final Box BOUNDS = Box.of(new BlockPos(5, 63, 16), new BlockPos(16, 70, 27));

    private FakeTransport transport;
    private AtomicLong clock;
    private FabricEditorSession session;
    private final List<Notice> notices = new ArrayList<>();

    @BeforeEach
    void setUp() {
        transport = new FakeTransport();
        clock = new AtomicLong(10_000 * MS);
        session = new FabricEditorSession(transport, () -> STATES, clock::get, "0.3.0-test");
        session.onNotice(notices::add);
    }

    private void connect(Features features) {
        session.onJoin();
        server(new S2C.Welcome(ProtocolV2.VERSION, features, Limits.DEFAULTS, Perm.mask(EnumSet.allOf(Perm.class)), 1L));
        assertEquals(SessionState.READY, session.state());
        transport.sent.clear();
    }

    private void server(S2C message) {
        try {
            session.onFrame(Codec.encodeS2C(message, STATES));
        } catch (ProtocolException e) {
            throw new AssertionError(e);
        }
    }

    private void tick(long millis) {
        clock.addAndGet(millis * MS);
        session.tick();
    }

    private <T extends C2S> T sent(Class<T> type) {
        for (int i = transport.sent.size() - 1; i >= 0; i--) {
            if (type.isInstance(transport.sent.get(i))) return type.cast(transport.sent.get(i));
        }
        throw new AssertionError("nothing of type " + type.getSimpleName() + " was sent: " + transport.sent);
    }

    private <T extends C2S> List<T> allSent(Class<T> type) {
        return transport.sent.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static <T> Reply<T> now(CompletionStage<Reply<T>> stage) {
        CompletableFuture<Reply<T>> future = stage.toCompletableFuture();
        assertTrue(future.isDone(), "the reply has arrived");
        return future.join();
    }

    private static boolean done(CompletionStage<?> stage) {
        return stage.toCompletableFuture().isDone();
    }

    private static S2C.ScatterPlan plan(int reqId, UUID planId, int placements) {
        TreeMap<String, Integer> rejected = new TreeMap<>(Map.of("SLOPE", 20, "SPACING", 55));
        return new S2C.ScatterPlan(reqId, planId, placements, rejected, 3_800, placements == 0 ? null : BOUNDS);
    }

    private static TreeMap<String, String> meta(UUID planId, int reqId, int placements) {
        TreeMap<String, String> meta = new TreeMap<>();
        meta.put(ScatterPlacements.META_FORMAT, ScatterPlacements.FORMAT_NAME);
        meta.put(ScatterPlacements.META_PLAN_ID, planId.toString());
        meta.put(ScatterPlacements.META_REQ_ID, Integer.toString(reqId));
        meta.put(ScatterPlacements.META_PLACEMENTS, Integer.toString(placements));
        return meta;
    }

    private StreamSender placementsStream(int streamId, byte[] payload, TreeMap<String, String> meta) {
        return new StreamSender(streamId, StreamKind.SCATTER_PLACEMENTS, payload, meta, StreamSender.MAX_S2C_CHUNK,
                StreamAssembler.DEFAULT_WINDOW);
    }

    /** Streams {@code sender}'s messages to the session, giving it credit back, until it has ended. */
    private void stream(StreamSender sender) {
        for (int round = 0; round < 1_000 && !sender.done(); round++) {
            for (Message message : sender.poll(StreamSender.SERVER_BYTES_PER_TICK)) server((S2C) message);
            for (C2S message : List.copyOf(transport.sent)) {
                if (message instanceof StreamCredit credit && credit.id() == sender.id()) sender.credit(credit);
            }
            transport.sent.removeIf(m -> m instanceof StreamCredit);
        }
    }

    private List<String> noticeKeys() {
        return notices.stream().map(Notice::key).toList();
    }

    // ---------------------------------------------------------------- previews

    @Test
    void aPreviewSendsTheRequestAndCompletesWithThePlanAndItsDecodedPlacements() {
        connect(ALL);
        CompletionStage<Reply<ScatterPreviewResult>> preview = session.scatterPreview(REQUEST);
        C2S.ScatterPreview sent = sent(C2S.ScatterPreview.class);
        assertEquals(REQUEST.message(sent.reqId()), sent, "the request goes out as it was given");
        server(plan(sent.reqId(), PLAN, 3));
        assertFalse(done(preview), "the placements are still to come");
        stream(placementsStream(9, ScatterPlacements.encode(PLACEMENTS), meta(PLAN, sent.reqId(), 3)));

        ScatterPreviewResult result = now(preview).toOptional().orElseThrow();
        assertEquals(PLAN, result.planId());
        assertEquals(PLACEMENTS, result.placements());
        assertEquals(3_800, result.totalCells());
        assertEquals(BOUNDS, result.bounds());
        assertEquals(Map.of("SLOPE", 20, "SPACING", 55), result.rejectedCounts());
        assertEquals(75, result.rejectedTotal());
        assertTrue(notices.isEmpty(), "nothing to toast: " + notices);
    }

    /** A preview of an oak (a tree variant) and an asset. */
    private static final ScatterPreviewRequest TREES = new ScatterPreviewRequest(REQUEST.area(), REQUEST.settings(),
            List.of(new C2S.ScatterPreview.Variant(new ScatterSource.Feature("minecraft:oak"), 5),
                    new C2S.ScatterPreview.Variant(new SourceRef.Asset(HASH), 1)), REQUEST.transforms());

    private StreamSender grownStream(int streamId, byte[] payload, UUID planId, int reqId) {
        TreeMap<String, String> meta = new TreeMap<>(Map.of(ScatterPlacements.META_PLAN_ID, planId.toString(),
                ScatterPlacements.META_REQ_ID, Integer.toString(reqId)));
        return new StreamSender(streamId, StreamKind.SCATTER_GENERATED, payload, meta, StreamSender.MAX_S2C_CHUNK,
                StreamAssembler.DEFAULT_WINDOW);
    }

    private static byte[] grownPayload() {
        GeneratedSource.Builder cells = GeneratedSource.builder(10);
        cells.set(10, 64, 20, STATES.state("minecraft:oak_log"));
        cells.set(10, 63, 20, STATES.state("minecraft:dirt"));
        return SparseUpload.encode(cells.build(), STATES);
    }

    @Test
    void aPlanWithTreesWaitsForItsGrownCellsAndCarriesThem() {
        connect(ALL);
        CompletionStage<Reply<ScatterPreviewResult>> preview = session.scatterPreview(TREES);
        int reqId = sent(C2S.ScatterPreview.class).reqId();
        server(plan(reqId, PLAN, 3));
        stream(placementsStream(9, ScatterPlacements.encode(PLACEMENTS), meta(PLAN, reqId, 3)));
        assertFalse(done(preview), "placements of variant 0 (a tree): its grown cells are still to come");
        byte[] grown = grownPayload();
        stream(grownStream(10, grown, PLAN, reqId));
        ScatterPreviewResult result = now(preview).toOptional().orElseThrow();
        assertEquals(PLACEMENTS, result.placements());
        assertArrayEquals(grown, result.grownPayload());
        assertTrue(notices.isEmpty(), "nothing to toast: " + notices);
    }

    @Test
    void grownCellsMayArriveBeforeThePlacementsAndThoseOfAnotherPlanAreRefused() {
        connect(ALL);
        CompletionStage<Reply<ScatterPreviewResult>> preview = session.scatterPreview(TREES);
        int reqId = sent(C2S.ScatterPreview.class).reqId();
        server(plan(reqId, PLAN, 3));
        stream(grownStream(11, grownPayload(), new UUID(8, 8), reqId));
        assertTrue(allSent(StreamAbort.class).stream().anyMatch(abort -> abort.id() == 11), "another plan's cells");
        stream(grownStream(12, grownPayload(), PLAN, reqId));
        assertFalse(done(preview));
        stream(placementsStream(13, ScatterPlacements.encode(PLACEMENTS), meta(PLAN, reqId, 3)));
        assertTrue(now(preview).toOptional().orElseThrow().grownPayload() != null);
    }

    @Test
    void aPlanWithoutTreePlacementsNeedsNoGrownCellsAndMissingOnesTimeOut() {
        connect(ALL);
        List<ScatterPlan.Placement> assetsOnly = List.of(new ScatterPlan.Placement(new BlockPos(1, 64, 1), 1,
                Transform.IDENTITY));
        CompletionStage<Reply<ScatterPreviewResult>> preview = session.scatterPreview(TREES);
        int reqId = sent(C2S.ScatterPreview.class).reqId();
        server(plan(reqId, PLAN, 1));
        stream(placementsStream(9, ScatterPlacements.encode(assetsOnly), meta(PLAN, reqId, 1)));
        assertNull(now(preview).toOptional().orElseThrow().grownPayload());

        CompletionStage<Reply<ScatterPreviewResult>> trees = session.scatterPreview(TREES);
        int second = sent(C2S.ScatterPreview.class).reqId();
        server(plan(second, PLAN, 3));
        stream(placementsStream(14, ScatterPlacements.encode(PLACEMENTS), meta(PLAN, second, 3)));
        tick(ClipboardTransfers.STALL_NANOS / MS + 1);
        assertTrue(done(trees));
        assertInstanceOf(Reply.Failed.class, now(trees));
    }

    @Test
    void aPlanWithoutPlacementsCompletesAtOnce() {
        connect(ALL);
        CompletionStage<Reply<ScatterPreviewResult>> preview = session.scatterPreview(REQUEST);
        server(plan(sent(C2S.ScatterPreview.class).reqId(), PLAN, 0));
        ScatterPreviewResult result = now(preview).toOptional().orElseThrow();
        assertTrue(result.placements().isEmpty());
        assertTrue(result.boundsIfAny().isEmpty());
    }

    @Test
    void onlyTheLatestPreviewCountsAndTheReplacedOnesAnswersAreIgnored() {
        connect(ALL);
        CompletionStage<Reply<ScatterPreviewResult>> first = session.scatterPreview(REQUEST);
        int firstId = sent(C2S.ScatterPreview.class).reqId();
        CompletionStage<Reply<ScatterPreviewResult>> second = session.scatterPreview(REQUEST);
        int secondId = sent(C2S.ScatterPreview.class).reqId();
        assertTrue(secondId != firstId);

        Reply.Failed<ScatterPreviewResult> replaced = assertInstanceOf(Reply.Failed.class, now(first));
        assertEquals(Reply.Failure.CANCELLED, replaced.failure());
        // The server answers the replaced preview with QUEUE_FULL, then (maybe) a plan it made first: both ignored.
        server(new S2C.JobRejected(firstId, RejectReason.QUEUE_FULL));
        server(plan(firstId, new UUID(9, 9), 3));
        stream(placementsStream(4, ScatterPlacements.encode(PLACEMENTS), meta(new UUID(9, 9), firstId, 3)));
        assertEquals(List.of(new StreamAbort(4, "unwanted")), allSent(StreamAbort.class),
                "the old plan's stream is refused");
        assertFalse(done(second));
        assertTrue(notices.isEmpty(), "a replaced preview is not toasted: " + notices);

        server(plan(secondId, PLAN, 3));
        stream(placementsStream(5, ScatterPlacements.encode(PLACEMENTS), meta(PLAN, secondId, 3)));
        assertEquals(PLAN, now(second).toOptional().orElseThrow().planId());
    }

    @Test
    void replacingAPreviewWhosePlacementsAreArrivingAbortsThatStream() {
        connect(ALL);
        session.scatterPreview(REQUEST);
        int reqId = sent(C2S.ScatterPreview.class).reqId();
        server(plan(reqId, PLAN, 3));
        StreamSender sender = placementsStream(6, ScatterPlacements.encode(PLACEMENTS), meta(PLAN, reqId, 3));
        server((S2C) sender.poll(0).get(0)); // StreamOpen only
        session.scatterPreview(REQUEST);
        assertEquals(List.of(new StreamAbort(6, ScatterRequests.SUPERSEDED)), allSent(StreamAbort.class));
    }

    @Test
    void aRefusalCompletesThePreviewAndTheServersNoticeIsShownWithItsDetail() {
        connect(ALL);
        CompletionStage<Reply<ScatterPreviewResult>> preview = session.scatterPreview(REQUEST);
        int reqId = sent(C2S.ScatterPreview.class).reqId();
        server(new S2C.JobRejected(reqId, RejectReason.AREA_BUSY));
        server(new S2C.Notice(S2C.Notice.Level.WARN, "sculptory.notice.request_refused",
                List.of("AREA_BUSY", "another edit is still working in the area")));
        Reply.Refused<ScatterPreviewResult> refused = assertInstanceOf(Reply.Refused.class, now(preview));
        assertEquals(RejectReason.AREA_BUSY, refused.reason());
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.reject.area_busy.detail",
                "another edit is still working in the area")), notices);
    }

    @Test
    void placementsThatDisagreeWithThePlanFailThePreview() {
        connect(ALL);
        CompletionStage<Reply<ScatterPreviewResult>> preview = session.scatterPreview(REQUEST);
        int reqId = sent(C2S.ScatterPreview.class).reqId();
        server(plan(reqId, PLAN, 4)); // four announced, three sent
        stream(placementsStream(7, ScatterPlacements.encode(PLACEMENTS), meta(PLAN, reqId, 4)));
        Reply.Failed<ScatterPreviewResult> failed = assertInstanceOf(Reply.Failed.class, now(preview));
        assertEquals(Reply.Failure.CORRUPT, failed.failure());
        assertEquals(List.of("sculptory.transfer.corrupt"), noticeKeys());
    }

    /** B4: plan bounds past the world's coordinates (near the int range) fail the preview before anything uses them. */
    @Test
    void aPlanWithBoundsOutsideTheWorldIsCorrupt() {
        connect(ALL);
        CompletionStage<Reply<ScatterPreviewResult>> preview = session.scatterPreview(REQUEST);
        int reqId = sent(C2S.ScatterPreview.class).reqId();
        Box huge = Box.of(new BlockPos(Integer.MAX_VALUE - 5, 64, 0), new BlockPos(Integer.MAX_VALUE, 70, 5));
        server(new S2C.ScatterPlan(reqId, PLAN, 3, new TreeMap<>(), 3_800, huge));
        Reply.Failed<ScatterPreviewResult> failed = assertInstanceOf(Reply.Failed.class, now(preview));
        assertEquals(Reply.Failure.CORRUPT, failed.failure());
        // The edge of the planner's range is still a world coordinate.
        CompletionStage<Reply<ScatterPreviewResult>> edge = session.scatterPreview(REQUEST);
        int edgeId = sent(C2S.ScatterPreview.class).reqId();
        Box far = Box.of(new BlockPos(-ScatterRequests.MAX_XZ, -64, 0), new BlockPos(-ScatterRequests.MAX_XZ + 3, 70, 3));
        server(new S2C.ScatterPlan(edgeId, PLAN, 0, new TreeMap<>(), 0, far));
        assertEquals(far, now(edge).toOptional().orElseThrow().bounds());
    }

    /** B4: a placement anchored outside the world fails the preview too. */
    @Test
    void aPlacementOutsideTheWorldIsCorrupt() {
        connect(ALL);
        CompletionStage<Reply<ScatterPreviewResult>> preview = session.scatterPreview(REQUEST);
        int reqId = sent(C2S.ScatterPreview.class).reqId();
        server(plan(reqId, PLAN, 1));
        List<ScatterPlan.Placement> far =
                List.of(new ScatterPlan.Placement(new BlockPos(Integer.MIN_VALUE + 3, 64, 0), 0, Transform.IDENTITY));
        stream(placementsStream(7, ScatterPlacements.encode(far), meta(PLAN, reqId, 1)));
        assertEquals(Reply.Failure.CORRUPT, assertInstanceOf(Reply.Failed.class, now(preview)).failure());
    }

    @Test
    void aVariantIndexBeyondTheRequestsVariantsIsCorrupt() {
        connect(ALL);
        CompletionStage<Reply<ScatterPreviewResult>> preview = session.scatterPreview(REQUEST);
        int reqId = sent(C2S.ScatterPreview.class).reqId();
        server(plan(reqId, PLAN, 1));
        List<ScatterPlan.Placement> wrong =
                List.of(new ScatterPlan.Placement(new BlockPos(0, 64, 0), 2, Transform.IDENTITY));
        stream(placementsStream(7, ScatterPlacements.encode(wrong), meta(PLAN, reqId, 1)));
        assertEquals(Reply.Failure.CORRUPT, assertInstanceOf(Reply.Failed.class, now(preview)).failure());
    }

    @Test
    void aStreamForAnotherPlanIsRefusedAndAnOversizedOneFailsThePreview() {
        connect(ALL);
        CompletionStage<Reply<ScatterPreviewResult>> preview = session.scatterPreview(REQUEST);
        int reqId = sent(C2S.ScatterPreview.class).reqId();
        server(plan(reqId, PLAN, 3));
        StreamSender other = placementsStream(3, ScatterPlacements.encode(PLACEMENTS), meta(new UUID(8, 8), reqId, 3));
        server((S2C) other.poll(0).get(0));
        assertEquals(List.of(new StreamAbort(3, "unwanted")), allSent(StreamAbort.class));
        assertFalse(done(preview), "still waiting for its own placements");

        byte[] tooMuch = new byte[(int) ScatterRequests.maxPayloadBytes(3) + 1];
        StreamSender huge = placementsStream(4, tooMuch, meta(PLAN, reqId, 3));
        server((S2C) huge.poll(0).get(0));
        assertEquals(new StreamAbort(4, "invalid"), allSent(StreamAbort.class).get(1));
        assertEquals(Reply.Failure.ABORTED, assertInstanceOf(Reply.Failed.class, now(preview)).failure());
    }

    @Test
    void aPreviewWithoutAPlanTimesOutAfterThePlanningWait() {
        connect(ALL);
        CompletionStage<Reply<ScatterPreviewResult>> preview = session.scatterPreview(REQUEST);
        tick(ScatterRequests.PLAN_TIMEOUT_NANOS / MS - 1_000);
        assertFalse(done(preview), "planning may take a while");
        tick(2_000);
        assertEquals(Reply.Failure.TIMED_OUT, assertInstanceOf(Reply.Failed.class, now(preview)).failure());
        assertEquals(List.of("sculptory.transfer.timed_out"), noticeKeys());
    }

    @Test
    void withoutTheScatterFeatureNothingIsSent() {
        connect(Features.of(Features.STROKES, Features.CLIPBOARD, Features.LIBRARY));
        Reply.Refused<ScatterPreviewResult> refused =
                assertInstanceOf(Reply.Refused.class, now(session.scatterPreview(REQUEST)));
        assertEquals(RejectReason.DISABLED, refused.reason());
        assertTrue(allSent(C2S.ScatterPreview.class).isEmpty());
        assertEquals(List.of("sculptory.reject.disabled.detail"), noticeKeys());
    }

    @Test
    void aDisconnectFailsThePreviewQuietly() {
        connect(ALL);
        CompletionStage<Reply<ScatterPreviewResult>> preview = session.scatterPreview(REQUEST);
        session.onDisconnect();
        assertEquals(Reply.Failure.DISCONNECTED, assertInstanceOf(Reply.Failed.class, now(preview)).failure());
        assertTrue(notices.isEmpty());
    }

    // ---------------------------------------------------------------- commits

    @Test
    void aCommitIsARunOpWhoseResultCountsTheSkippedPlacements() {
        connect(ALL);
        CompletionStage<ToolResult> commit = session.scatterCommit(PLAN);
        C2S.RunOp run = sent(C2S.RunOp.class);
        assertEquals(new OpSpec.ScatterCommit(PLAN), run.op());
        server(new S2C.JobAccepted(run.reqId(), JOB, 3_800));
        assertInstanceOf(ToolResult.Accepted.class, commit.toCompletableFuture().join());
        server(new S2C.JobFinished(JOB, JobOutcome.COMPLETED, 3_500, 0, 3, 0));
        // The server's own notice of the same count follows; it is not shown twice.
        server(new S2C.Notice(S2C.Notice.Level.WARN, SessionNotices.SCATTER_SKIPPED, List.of("3")));
        assertEquals(List.of(Notice.of(Notice.Level.SUCCESS, SessionNotices.SCATTER_FINISHED_SKIPPED, "3,500", "3")),
                notices);
    }

    @Test
    void aCleanCommitSaysHowManyBlocksWerePlaced() {
        connect(ALL);
        session.scatterCommit(PLAN);
        server(new S2C.JobAccepted(sent(C2S.RunOp.class).reqId(), JOB, 3_800));
        server(new S2C.JobFinished(JOB, JobOutcome.COMPLETED, 3_800, 0, 0, 0));
        assertEquals(List.of(Notice.of(Notice.Level.SUCCESS, SessionNotices.SCATTER_FINISHED, "3,800")), notices);
        // A skipped-placements notice nobody announced is shown as it is.
        server(new S2C.Notice(S2C.Notice.Level.WARN, SessionNotices.SCATTER_SKIPPED, List.of("2")));
        assertEquals(SessionNotices.SCATTER_SKIPPED, notices.get(1).key());
    }

    @Test
    void aRefusedCommitIsTheCallersToShow() {
        connect(ALL);
        CompletionStage<ToolResult> commit = session.scatterCommit(PLAN);
        server(new S2C.JobRejected(sent(C2S.RunOp.class).reqId(), RejectReason.NO_PERMISSION));
        ToolResult.Rejected rejected = assertInstanceOf(ToolResult.Rejected.class, commit.toCompletableFuture().join());
        assertEquals(RejectReason.NO_PERMISSION, rejected.reason());
        assertTrue(notices.isEmpty());
    }

    private static final class FakeTransport implements FabricEditorSession.Transport {
        final List<C2S> sent = new ArrayList<>();

        @Override
        public boolean canSend() {
            return true;
        }

        @Override
        public void send(byte[] frame) {
            try {
                sent.add(Codec.decodeC2S(frame, STATES));
            } catch (ProtocolException e) {
                throw new AssertionError("The client sent an undecodable frame", e);
            }
        }
    }
}
