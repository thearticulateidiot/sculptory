package dev.sculptory.fabric.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.palette.BlockPalette;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import dev.sculptory.core.scatter.ScatterArea;
import dev.sculptory.core.scatter.ScatterPlan;
import dev.sculptory.core.scatter.ScatterSettings;
import dev.sculptory.core.schem.SchematicFormat;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.engine.BuilderOutcome;
import dev.sculptory.fabric.engine.ChunkPermit;
import dev.sculptory.fabric.engine.ClipboardService;
import dev.sculptory.fabric.engine.ClipboardService.LibraryChange;
import dev.sculptory.fabric.engine.DabOutcome;
import dev.sculptory.fabric.engine.EditRejected;
import dev.sculptory.fabric.engine.EditService;
import dev.sculptory.fabric.engine.JobListener;
import dev.sculptory.fabric.engine.JobResult;
import dev.sculptory.fabric.engine.JobTicket;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.fabric.engine.PermissionService;
import dev.sculptory.fabric.engine.RunOptions;
import dev.sculptory.fabric.engine.ScatterService;
import dev.sculptory.protocol.v2.AssetAccess;
import dev.sculptory.protocol.v2.BuilderPower;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.Codec;
import dev.sculptory.protocol.v2.Features;
import dev.sculptory.protocol.v2.Handshake;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.Message;
import dev.sculptory.protocol.v2.MessageType;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.protocol.v2.ProtocolException;
import dev.sculptory.protocol.v2.ProtocolV2;
import dev.sculptory.protocol.v2.RateLimiter;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.protocol.v2.ScatterPlacements;
import dev.sculptory.protocol.v2.StreamAbort;
import dev.sculptory.protocol.v2.StreamAssembler;
import dev.sculptory.protocol.v2.StreamChunk;
import dev.sculptory.protocol.v2.StreamCredit;
import dev.sculptory.protocol.v2.StreamEnd;
import dev.sculptory.protocol.v2.StreamKind;
import dev.sculptory.protocol.v2.StreamOpen;
import dev.sculptory.protocol.v2.StreamSender;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ServerDispatcherTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final Box BOX = Box.of(new BlockPos(0, 60, 0), new BlockPos(15, 70, 15));
    private static final UUID JOB = new UUID(7, 7);
    private static final BrushSpec SPEC = new BrushSpec(BrushTool.RAISE, 8, 1f, Falloff.SMOOTH, Shape.CIRCLE, null,
            SurfaceMask.ANY, 0, 0, 1L);

    private FakeEdits edits;
    private FakeClipboards clipboards;
    private FakeScatter scatter;
    private FakePermissions permissions;
    private AtomicLong clock;
    private Limits limits;
    private ServerDispatcher dispatcher;
    private FakeTransport transport;
    private NetSession session;

    @BeforeEach
    void setUp() {
        edits = new FakeEdits();
        clipboards = new FakeClipboards();
        scatter = new FakeScatter();
        permissions = new FakePermissions(EnumSet.of(Perm.USE, Perm.BRUSH, Perm.REGION));
        clock = new AtomicLong(1_000_000_000L);
        limits = Limits.DEFAULTS;
        dispatcher = new ServerDispatcher(edits, clipboards, scatter, permissions, () -> limits, () -> STATES,
                clock::get);
        transport = new FakeTransport();
        session = dispatcher.open(transport);
    }

    private void receive(C2S message) {
        try {
            dispatcher.receive(session, Codec.encodeC2S(message, STATES));
        } catch (ProtocolException e) {
            throw new AssertionError(e);
        }
    }

    private void handshake() {
        receive(Handshake.hello("test", Features.of(Features.STROKES, Features.HISTORY, Features.SCATTER)));
        assertTrue(session.ready());
        transport.sent.clear();
        edits.historyQueries = 0;
    }

    private static C2S.RunOp fill(int reqId) {
        return new C2S.RunOp(reqId, new OpSpec.Fill(BOX, new Pattern.Single(STATES.state("minecraft:stone")), CellMask.ANY),
                false, ConflictPolicy.SKIP_CONFLICTS);
    }

    private static List<Dab> dabs(int from, int count) {
        List<Dab> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(new Dab(from + i, 16 * i, 1000, 0, 255));
        return out;
    }

    // ---------------------------------------------------------------- handshake

    @Test
    void helloGetsWelcomeWithCommonFeaturesPermissionsLimitsAndHistory() {
        receive(Handshake.hello("test", Features.of(Features.STROKES, Features.HISTORY, Features.SCATTER, "unknown")));
        S2C.Welcome welcome = transport.first(S2C.Welcome.class);
        assertEquals(ProtocolV2.VERSION, welcome.protocol());
        assertEquals(Features.of(Features.STROKES, Features.HISTORY, Features.SCATTER), welcome.features());
        assertEquals(Perm.mask(EnumSet.of(Perm.USE, Perm.BRUSH, Perm.REGION)), welcome.permissions());
        assertEquals(Limits.DEFAULTS, welcome.limits());
        assertEquals(session.epoch(), welcome.sessionEpoch());
        assertEquals(edits.historyState, transport.first(S2C.HistoryState.class));
        assertEquals(NetSession.Stage.READY, session.stage());
        assertTrue(dispatcher.open(new FakeTransport()).epoch() != session.epoch(), "epochs are unique");
    }

    @Test
    void incompatibleHelloIsAnsweredAndTheSessionGoesQuiet() {
        receive(new C2S.Hello(ProtocolV2.VERSION + 1, ProtocolV2.VERSION + 2, "future", Features.NONE));
        S2C.Incompatible incompatible = transport.first(S2C.Incompatible.class);
        assertEquals(Handshake.MIN_PROTOCOL, incompatible.serverMinProtocol());
        assertEquals(NetSession.Stage.INCOMPATIBLE, session.stage());
        transport.sent.clear();
        receive(fill(1));
        assertTrue(transport.sent.isEmpty());
        assertTrue(edits.calls.isEmpty());
    }

    /** The server's build id goes out in Welcome; the client's is kept from Hello. A different build is still welcome. */
    @Test
    void theHandshakeExchangesBuildIds() {
        dispatcher.buildId("0.2.0-dev+server1");
        receive(Handshake.hello("0.2.0-dev+client1", Features.of(Features.STROKES)));
        S2C.Welcome welcome = transport.first(S2C.Welcome.class);
        assertEquals("0.2.0-dev+server1", welcome.serverBuild());
        assertEquals("0.2.0-dev+client1", session.clientBuild);
        assertTrue(session.ready(), "a different build of the same protocol may edit");
    }

    /**
     * A client of another protocol is refused with a readable answer: clients of protocol 5 or newer get the server's
     * build too; older ones (a v4 build from before build ids) exactly the two protocol numbers they decode, 65 | 10 | 10
     * (the build would be trailing bytes to them).
     */
    @Test
    void protocolRefusalsCarryTheServerBuildOnlyToClientsThatReadIt() throws ProtocolException {
        dispatcher.buildId("0.2.0-dev+server1");
        receive(new C2S.Hello(ProtocolV2.VERSION + 1, ProtocolV2.VERSION + 1, "0.3.0+future", Features.NONE));
        S2C.Incompatible future = transport.first(S2C.Incompatible.class);
        assertEquals("0.2.0-dev+server1", future.serverBuild());
        assertEquals("0.3.0+future", session.clientBuild);
        assertEquals(NetSession.Stage.INCOMPATIBLE, session.stage());

        NetSession old = dispatcher.open(transport);
        transport.sent.clear();
        dispatcher.receive(old, Codec.encodeC2S(new C2S.Hello(4, 4, "0.2.0-dev", Features.NONE), STATES));
        assertEquals("", transport.first(S2C.Incompatible.class).serverBuild());
        byte[] v4Body = {(byte) MessageType.INCOMPATIBLE.code(), 10, 10};
        assertArrayEquals(v4Body, transport.lastFrame, "what a protocol 4 client decodes: the range 5-5 and nothing more");
        assertEquals(NetSession.Stage.INCOMPATIBLE, old.stage());
    }

    /** The client's build id is cleaned before it is stored, logged or shown: only [0-9A-Za-z.+_-] stay. */
    @Test
    void theClientsBuildIdIsCleanedOnArrival() {
        dispatcher.buildId("0.2.0-dev+server1");
        receive(Handshake.hello("0.2.0§c\n<b>", Features.NONE));
        assertEquals("0.2.0?c??b?", session.clientBuild);
        assertTrue(session.ready());
    }

    @Test
    void messagesBeforeTheHandshakeAreViolationsAndNeverReachTheEngine() {
        receive(fill(1));
        receive(new C2S.StrokeBegin(1, SPEC));
        assertTrue(edits.calls.isEmpty());
        assertTrue(transport.sent.isEmpty());
        assertEquals(2, session.violations());
    }

    @Test
    void repeatedHelloIsAViolation() {
        handshake();
        receive(Handshake.hello("test", Features.NONE));
        assertEquals(1, session.violations());
        assertTrue(transport.sent.isEmpty());
    }

    @Test
    void vanillaClientsWithoutTheChannelGetNothing() {
        transport.canSend = false;
        receive(Handshake.hello("test", Features.NONE));
        receive(fill(1));
        assertTrue(transport.frames == 0);
    }

    // ---------------------------------------------------------------- jobs

    @Test
    void runOpIsAcceptedAndJobEventsFollowInOrder() {
        handshake();
        // A QUEUED progress reported synchronously inside run() must still arrive after JobAccepted.
        edits.duringRun = l -> l.progress(JOB, 0, 64, Phase.QUEUED);
        receive(fill(5));
        assertEquals("run Fill", edits.calls.get(0));
        assertEquals(new RunOptions(false, ConflictPolicy.SKIP_CONFLICTS), edits.lastOptions);
        assertEquals(List.of(MessageType.JOB_ACCEPTED, MessageType.JOB_PROGRESS), transport.types());
        assertEquals(new S2C.JobAccepted(5, JOB, 64), transport.sent.get(0));

        edits.lastListener.progress(JOB, 32, 64, Phase.APPLY);
        edits.lastListener.finished(new JobResult(JOB, JobOutcome.COMPLETED, 60, 4, 0, 0));
        assertEquals(List.of(MessageType.JOB_ACCEPTED, MessageType.JOB_PROGRESS, MessageType.JOB_PROGRESS,
                MessageType.JOB_FINISHED, MessageType.HISTORY_STATE), transport.types());
        assertEquals(new S2C.JobFinished(JOB, JobOutcome.COMPLETED, 60, 4, 0, 0), transport.first(S2C.JobFinished.class));
    }

    @Test
    void refusedAndFailingOpsAreRejectedWithTheirReason() {
        handshake();
        edits.rejectNext = new EditRejected(RejectReason.TOO_LARGE, "big");
        receive(fill(6));
        assertEquals(new S2C.JobRejected(6, RejectReason.TOO_LARGE), transport.last());
        edits.failNext = new IllegalStateException("engine bug");
        receive(fill(7));
        assertEquals(new S2C.JobRejected(7, RejectReason.INVALID), transport.last());
        assertEquals(0, session.violations(), "engine refusals are not client violations");
    }

    @Test
    void undoAndRedoAreJobsAndRefusalsRefreshHistory() {
        handshake();
        receive(new C2S.Undo(8, ConflictPolicy.OVERWRITE));
        assertEquals("undo OVERWRITE", edits.calls.get(0));
        assertEquals(new S2C.JobAccepted(8, JOB, 10), transport.last());
        edits.rejectNext = new EditRejected(RejectReason.HISTORY_EMPTY);
        receive(new C2S.Redo(9, ConflictPolicy.SKIP_CONFLICTS));
        assertEquals(List.of(MessageType.JOB_ACCEPTED, MessageType.JOB_REJECTED, MessageType.HISTORY_STATE), transport.types());
        assertEquals(new S2C.JobRejected(9, RejectReason.HISTORY_EMPTY), transport.sent.get(1));

        // The engine reports undo/redo jobs through a session listener.
        JobListener listener = dispatcher.jobListener(session);
        listener.finished(new JobResult(JOB, JobOutcome.COMPLETED, 3, 0, 1, 0));
        assertEquals(new S2C.JobFinished(JOB, JobOutcome.COMPLETED, 3, 0, 1, 0), transport.sent.get(3));
        assertInstanceOf(S2C.HistoryState.class, transport.last());
    }

    @Test
    void undoJobEventsReportedDuringAdmissionFollowJobAccepted() {
        handshake();
        edits.duringHistory = () -> {
            JobListener listener = dispatcher.jobListener(session);
            listener.progress(JOB, 0, 10, Phase.QUEUED);
            listener.finished(new JobResult(JOB, JobOutcome.COMPLETED, 10, 0, 0, 0));
        };
        receive(new C2S.Undo(8, ConflictPolicy.SKIP_CONFLICTS));
        assertEquals(List.of(MessageType.JOB_ACCEPTED, MessageType.JOB_PROGRESS, MessageType.JOB_FINISHED,
                MessageType.HISTORY_STATE), transport.types());

        transport.sent.clear();
        edits.rejectNext = new EditRejected(RejectReason.AREA_BUSY);
        edits.duringHistory = () -> dispatcher.jobListener(session).progress(JOB, 1, 10, Phase.APPLY);
        receive(new C2S.Redo(9, ConflictPolicy.SKIP_CONFLICTS));
        assertEquals(List.of(MessageType.JOB_REJECTED, MessageType.HISTORY_STATE, MessageType.JOB_PROGRESS),
                transport.types(), "events of another job are held while a refused redo is answered, then sent");
    }

    @Test
    void undoAnywayIsAJobLikeUndoAndItsRefusalSaysWhyFirst() {
        handshake();
        receive(new C2S.HistoryOverwrite(8, false, 3));
        assertEquals("undo anyway 3", edits.calls.get(0));
        assertEquals(List.of(MessageType.JOB_ACCEPTED), transport.types());
        assertEquals(new S2C.JobAccepted(8, JOB, 12), transport.last());

        transport.sent.clear();
        edits.rejectNext = new EditRejected(RejectReason.INVALID, "the history changed since those redo steps",
                EditRejected.HISTORY_RUN);
        receive(new C2S.HistoryOverwrite(9, true, 2));
        assertEquals("redo anyway 2", edits.calls.get(1));
        assertEquals(List.of(MessageType.NOTICE, MessageType.JOB_REJECTED, MessageType.HISTORY_STATE), transport.types(),
                "the detail comes before the refusal, then the history to show");
        assertEquals(new S2C.Notice(S2C.Notice.Level.WARN, ServerDispatcher.NOTICE_OVERWRITE_REFUSED,
                List.of("INVALID", "the history changed since those redo steps", EditRejected.HISTORY_RUN)),
                transport.sent.get(0), "reason, detail (no reason prefix) and the kind the client tests");
        assertEquals(new S2C.JobRejected(9, RejectReason.INVALID), transport.sent.get(1));
        assertEquals(0, session.violations(), "a refusal is not a violation");

        transport.sent.clear();
        edits.rejectNext = new EditRejected(RejectReason.QUEUE_FULL);
        receive(new C2S.HistoryOverwrite(10, false, 1));
        assertEquals(new S2C.Notice(S2C.Notice.Level.WARN, ServerDispatcher.NOTICE_OVERWRITE_REFUSED,
                List.of("QUEUE_FULL", "", "")), transport.sent.get(0));

        transport.sent.clear();
        edits.rejectNext = new EditRejected(RejectReason.INVALID, "outside the build limit");
        receive(new C2S.HistoryOverwrite(12, false, 1));
        assertEquals(new S2C.Notice(S2C.Notice.Level.WARN, ServerDispatcher.NOTICE_OVERWRITE_REFUSED,
                List.of("INVALID", "outside the build limit", "")), transport.sent.get(0), "not a run refusal");

        transport.sent.clear();
        edits.failNext = new IllegalStateException("boom");
        receive(new C2S.HistoryOverwrite(11, false, 1));
        assertEquals(List.of(new S2C.JobRejected(11, RejectReason.INVALID)), transport.sent);
    }

    @Test
    void undoAnywayJobEventsReportedDuringAdmissionFollowJobAccepted() {
        handshake();
        edits.duringHistory = () -> {
            JobListener listener = dispatcher.jobListener(session);
            listener.progress(JOB, 0, 12, Phase.QUEUED);
            listener.finished(new JobResult(JOB, JobOutcome.COMPLETED, 12, 0, 0, 0));
        };
        receive(new C2S.HistoryOverwrite(8, true, 1));
        assertEquals(List.of(MessageType.JOB_ACCEPTED, MessageType.JOB_PROGRESS, MessageType.JOB_FINISHED,
                MessageType.HISTORY_STATE), transport.types());
    }

    @Test
    void undoAnywayIsChargedToTheOpsBucketAndAnsweredWhenOverIt() {
        handshake();
        for (int i = 0; i < 10; i++) receive(new C2S.HistoryOverwrite(i + 1, false, 1));
        transport.sent.clear();
        receive(new C2S.HistoryOverwrite(20, false, 1));
        assertEquals(List.of(new S2C.JobRejected(20, RejectReason.RATE_LIMITED)), transport.sent);
        assertEquals(10, edits.calls.size());
    }

    @Test
    void anEditServiceWithoutUndoAnywayRefusesItAsDisabled() {
        EditService plain = new EditService() {
            @Override
            public JobTicket run(ServerPlayerEntity p, OpSpec s, RunOptions o, JobListener l) {
                throw new AssertionError();
            }

            @Override
            public void beginStroke(ServerPlayerEntity p, int strokeId, BrushSpec spec) {}

            @Override
            public DabOutcome dabs(ServerPlayerEntity p, int strokeId, int seq, List<Dab> dabs) {
                throw new AssertionError();
            }

            @Override
            public void endStroke(ServerPlayerEntity p, int strokeId) {}

            @Override
            public JobTicket undo(ServerPlayerEntity p, ConflictPolicy c) {
                throw new AssertionError();
            }

            @Override
            public JobTicket redo(ServerPlayerEntity p, ConflictPolicy c) {
                throw new AssertionError();
            }

            @Override
            public boolean cancel(ServerPlayerEntity p, UUID jobId) {
                return false;
            }
        };
        EditRejected refused = assertThrows(EditRejected.class, () -> plain.historyOverwrite(null, false, 1));
        assertEquals(RejectReason.DISABLED, refused.reason());
    }

    @Test
    void cancelReachesTheEngine() {
        handshake();
        receive(new C2S.CancelJob(JOB));
        assertEquals("cancel " + JOB, edits.calls.get(0));
        assertTrue(transport.sent.isEmpty());
    }

    @Test
    void ungrantedClientStreamsAreAborted() {
        handshake();
        receive(new StreamOpen(3, StreamKind.SCHEM_UPLOAD, 10, new TreeMap<>()));
        assertEquals(List.of(new StreamAbort(3, "not_granted")), transport.sent);
        assertTrue(edits.calls.isEmpty());
        assertTrue(clipboards.calls.isEmpty());
    }

    @Test
    void withoutClipboardAndScatterServicesM2AndM3RequestsAreDisabled() {
        ServerDispatcher plain = new ServerDispatcher(edits, permissions, () -> limits, () -> STATES, clock::get);
        NetSession other = plain.open(transport);
        receiveOn(plain, other, Handshake.hello("t", Features.NONE));
        transport.sent.clear();
        receiveOn(plain, other, new C2S.LibraryList(11, ""));
        receiveOn(plain, other, new C2S.Copy(12, BOX, BlockPos.ORIGIN, false, CellMask.ANY));
        receiveOn(plain, other, scatterPreview(13));
        assertEquals(List.of(MessageType.JOB_REJECTED, MessageType.NOTICE, MessageType.JOB_REJECTED, MessageType.NOTICE,
                MessageType.JOB_REJECTED, MessageType.NOTICE), transport.types());
        assertEquals(new S2C.JobRejected(11, RejectReason.DISABLED), transport.sent.get(0));
        assertEquals(new S2C.JobRejected(12, RejectReason.DISABLED), transport.sent.get(2));
        assertEquals(new S2C.JobRejected(13, RejectReason.DISABLED), transport.sent.get(4));
    }

    // ---------------------------------------------------------------- scatter (M3)

    private static C2S.ScatterPreview scatterPreview(int reqId) {
        return new C2S.ScatterPreview(reqId, new ScatterArea.Stamps(List.of(ScatterArea.Stamp.paint(8, 8, 12))),
                new C2S.ScatterPreview.Settings(1L, 4, new ScatterSettings.Density.Fraction(0.25), SurfaceMask.ANY,
                        ScatterSettings.Fit.DEFAULT),
                List.of(new C2S.ScatterPreview.Variant(new SourceRef.Asset(HASH), 1)), ScatterSettings.Transforms.ALL);
    }

    private static final UUID PLAN = new UUID(9, 9);

    private static ScatterService.PlanReady planReady(List<ScatterPlan.Placement> placements) {
        TreeMap<String, Integer> counts = new TreeMap<>(java.util.Map.of("DENSITY", 400, "SPACING", 12));
        Box bounds = placements.isEmpty() ? null : Box.of(new BlockPos(0, 64, 0), new BlockPos(20, 70, 20));
        return new ScatterService.PlanReady(PLAN, placements.size(), counts, 9L * placements.size(), bounds,
                ScatterPlacements.encode(placements));
    }

    @Test
    void scatterPreviewsAnswerWithThePlanThenStreamItsPlacements() throws ProtocolException {
        handshake();
        receive(scatterPreview(21));
        assertEquals(List.of("preview 21"), scatter.calls);
        assertTrue(transport.sent.isEmpty(), "nothing until the plan is ready");
        List<ScatterPlan.Placement> placements = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            placements.add(new ScatterPlan.Placement(new BlockPos(i % 97, 64 + i % 5, i / 97), i % 2,
                    new Transform(i & 3, i % 3 == 0 ? Mirror.X : Mirror.NONE)));
        }
        ScatterService.PlanReady ready = planReady(placements);
        scatter.replies.get(0).done(ready);
        assertEquals(List.of(new S2C.ScatterPlan(21, PLAN, 3000, ready.rejectedCounts(), 27_000, ready.bounds())),
                transport.sent, "the summary first; the stream starts on the next tick");

        dispatcher.tick(session);
        StreamOpen open = transport.first(StreamOpen.class);
        assertEquals(StreamKind.SCATTER_PLACEMENTS, open.kind());
        assertEquals(ScatterPlacements.FORMAT_NAME, open.meta().get(ScatterPlacements.META_FORMAT));
        assertEquals(PLAN.toString(), open.meta().get(ScatterPlacements.META_PLAN_ID));
        assertEquals("21", open.meta().get(ScatterPlacements.META_REQ_ID));
        assertEquals("3000", open.meta().get(ScatterPlacements.META_PLACEMENTS));
        StreamAssembler assembler = new StreamAssembler(open, 1 << 20, 1 << 20);
        for (StreamChunk chunk : transport.sent(StreamChunk.class)) assembler.accept(chunk);
        assertEquals(placements, ScatterPlacements.decode(assembler.finish(transport.first(StreamEnd.class)), 2));
        assertEquals(0, dispatcher.outboundBytesTotal(), "the finished stream let its bytes go");
    }

    @Test
    void emptyPlansSendOnlyTheSummary() {
        handshake();
        receive(scatterPreview(22));
        scatter.replies.get(0).done(planReady(List.of()));
        dispatcher.tick(session);
        assertEquals(List.of(new S2C.ScatterPlan(22, PLAN, 0, planReady(List.of()).rejectedCounts(), 0, null)),
                transport.sent);
    }

    @Test
    void scatterRefusalsFailuresAndSupersededPreviews() {
        handshake();
        scatter.rejectNext = new EditRejected(RejectReason.TOO_LARGE, "513 stamps > 512");
        receive(scatterPreview(23));
        assertEquals(List.of(MessageType.JOB_REJECTED, MessageType.NOTICE), transport.types());
        assertEquals(new S2C.JobRejected(23, RejectReason.TOO_LARGE), transport.sent.get(0));
        assertEquals(ServerDispatcher.NOTICE_REQUEST_REFUSED, ((S2C.Notice) transport.last()).key());

        transport.sent.clear();
        receive(scatterPreview(24));
        receive(scatterPreview(25));
        // The service supersedes 24 when 25 arrives; that answer carries no notice.
        scatter.replies.get(0).superseded();
        assertEquals(List.of(new S2C.JobRejected(24, RejectReason.QUEUE_FULL)), transport.sent);
        scatter.replies.get(1).failed(RejectReason.INVALID, "you changed worlds");
        assertEquals(List.of(MessageType.JOB_REJECTED, MessageType.JOB_REJECTED, MessageType.NOTICE), transport.types());
        assertEquals(List.of("INVALID", "you changed worlds"), ((S2C.Notice) transport.last()).args());

        transport.sent.clear();
        scatter.failNext = new IllegalStateException("bug");
        receive(scatterPreview(26));
        assertEquals(List.of(new S2C.JobRejected(26, RejectReason.INVALID)), transport.sent);
        assertEquals(0, session.violations());
    }

    @Test
    void scatterPreviewsReserveTheirStreamUpFrontAndAReplacementReusesIt() {
        handshake();
        for (int i = 0; i < NetSession.MAX_OUTBOUND_STREAMS - 1; i++) {
            dispatcher.openStream(session, StreamKind.CLIPBOARD_PREVIEW, new byte[10], new TreeMap<>());
        }
        receive(scatterPreview(27));
        receive(new C2S.ExportClipboard(40, CLIPBOARD));
        assertEquals(List.of("preview 27"), scatter.calls);
        assertEquals(new S2C.JobRejected(40, RejectReason.QUEUE_FULL), transport.sent.get(0), "the preview holds the room");
        // A new preview replaces 27: its room is released before the check, so 28 gets it.
        receive(scatterPreview(28));
        assertEquals(List.of("preview 27", "preview 28"), scatter.calls);
        scatter.replies.get(0).superseded();
        receive(new C2S.ExportClipboard(41, CLIPBOARD));
        assertTrue(clipboards.calls.isEmpty(), "the superseded preview gave the room back twice");
        scatter.replies.get(1).failed(RejectReason.INVALID, "gone");
        receive(new C2S.ExportClipboard(42, CLIPBOARD));
        assertEquals(List.of("export " + CLIPBOARD), clipboards.calls, "a failed preview gives its room back");
    }

    @Test
    void scatterCommitsRunAsRegionOps() {
        handshake();
        receive(new C2S.RunOp(30, new OpSpec.ScatterCommit(PLAN), false, ConflictPolicy.SKIP_CONFLICTS));
        assertEquals(List.of("run ScatterCommit"), edits.calls);
        assertEquals(new S2C.JobAccepted(30, JOB, 64), transport.sent.get(0));
    }

    // ---------------------------------------------------------------- clipboards, schematics, library (M2)

    private void receiveOn(ServerDispatcher d, NetSession s, C2S message) {
        try {
            d.receive(s, Codec.encodeC2S(message, STATES));
        } catch (ProtocolException e) {
            throw new AssertionError(e);
        }
    }

    private static final String HASH = "ab".repeat(32);
    private static final UUID CLIPBOARD = new UUID(3, 4);

    private static ClipboardService.ClipboardInfo info(List<S2C.Notice> notices) {
        return new ClipboardService.ClipboardInfo(CLIPBOARD, new BlockPos(3, 4, 5), new BlockPos(1, 0, -2), 60, 4096,
                notices);
    }

    @Test
    void theServerOffersClipboardSchematicsAndLibrary() {
        receive(Handshake.hello("t", Features.of(Features.CLIPBOARD, Features.SCHEMATICS, Features.LIBRARY, Features.SCATTER)));
        assertEquals(Features.of(Features.CLIPBOARD, Features.SCHEMATICS, Features.LIBRARY, Features.SCATTER),
                transport.first(S2C.Welcome.class).features());
    }

    @Test
    void copyAnswersWithClipboardReadyWhenTheClipboardIsBuilt() {
        handshake();
        receive(new C2S.Copy(12, BOX, new BlockPos(1, 2, 3), false, CellMask.ANY));
        assertEquals(List.of("copy false"), clipboards.calls);
        assertTrue(transport.sent.isEmpty(), "nothing until the clipboard is built");
        S2C.Notice lost = new S2C.Notice(S2C.Notice.Level.WARN, "sculptory.notice.import_unknown_states", List.of("1"));
        clipboards.<ClipboardService.ClipboardInfo>reply(0).done(info(List.of(lost)));
        assertEquals(List.of(new S2C.ClipboardReady(12, CLIPBOARD, new BlockPos(3, 4, 5), new BlockPos(1, 0, -2), 60, 4096),
                lost), transport.sent);
    }

    @Test
    void aCutIsAcceptedAsAJobFirstAndItsEventsFollow() {
        handshake();
        clipboards.cutTicket = new JobTicket(JOB, "Erase", 64);
        clipboards.duringCopy = l -> l.progress(JOB, 0, 64, Phase.QUEUED);
        receive(new C2S.Copy(12, BOX, BlockPos.ORIGIN, true, CellMask.ANY));
        assertEquals(List.of(MessageType.JOB_ACCEPTED, MessageType.JOB_PROGRESS), transport.types());
        assertEquals(new S2C.JobAccepted(12, JOB, 64), transport.sent.get(0));
        clipboards.cutListener.finished(new JobResult(JOB, JobOutcome.COMPLETED, 64, 0, 0, 0));
        clipboards.<ClipboardService.ClipboardInfo>reply(0).done(info(List.of()));
        assertEquals(List.of(MessageType.JOB_ACCEPTED, MessageType.JOB_PROGRESS, MessageType.JOB_FINISHED,
                MessageType.HISTORY_STATE, MessageType.CLIPBOARD_READY), transport.types());
    }

    @Test
    void shapeCopiesGoToTheClipboardServiceAsTheyAre() {
        handshake();
        Region.Shape cylinder = new Region.Shape(BOX, ShapeKind.CYLINDER, Facing.UP);
        receive(new C2S.Copy(16, cylinder, BlockPos.ORIGIN, false, CellMask.ANY, EntityFilter.DECORATIONS));
        assertEquals(List.of("copy false"), clipboards.calls);
        assertEquals(cylinder, clipboards.lastRegion);
    }

    // ---------------------------------------------------------------- selection uploads (regions)

    /** A set spread over several sections, as magic select makes. */
    private static CellSet selection(int seed) {
        Random random = new Random(seed);
        CellSet.Builder builder = CellSet.builder();
        for (int i = 0; i < 2000; i++) builder.add(random.nextInt(40), 60 + random.nextInt(20), random.nextInt(40));
        return builder.build();
    }

    private static Region.Uploaded reference(CellSet set) {
        return new Region.Uploaded(set.hash(), set.bounds(), set.size());
    }

    /** Uploads {@code set} as request {@code reqId} up to the service's decode (it answers through its reply). */
    private void uploadSelection(int reqId, CellSet set) {
        byte[] bytes = set.encode();
        receive(new C2S.SelectionUpload(reqId, set.hash(), set.bounds(), set.size(), bytes.length));
        S2C.UploadGrant grant = (S2C.UploadGrant) transport.last();
        assertEquals(reqId, grant.reqId());
        StreamSender sender = new StreamSender(grant.streamId(), StreamKind.SELECTION_UPLOAD, bytes, new TreeMap<>(),
                StreamSender.MAX_C2S_CHUNK, grant.creditBytes());
        while (!sender.done()) {
            for (Message m : sender.poll(StreamSender.CLIENT_BYTES_PER_TICK)) receive((C2S) m);
            clock.addAndGet(100_000_000L);
        }
        assertArrayEquals(bytes, clipboards.uploadedBytes);
    }

    @Test
    void selectionUploadsAreKeptAndOpsAndCopiesNamingThemGetTheirCells() {
        handshake();
        CellSet set = selection(1);
        uploadSelection(15, set);
        transport.sent.clear();
        clipboards.<CellSet>reply(0).done(set);
        assertEquals(List.of(new S2C.SelectionReady(15, set.hash())), transport.sent);
        assertEquals(1, session.selections().size());

        transport.sent.clear();
        OpSpec.Fill fill = new OpSpec.Fill(reference(set), new Pattern.Single(STATES.state("minecraft:stone")), CellMask.ANY);
        receive(new C2S.RunOp(16, fill, false, ConflictPolicy.SKIP_CONFLICTS));
        assertEquals(new S2C.JobAccepted(16, JOB, 64), transport.sent.get(0));
        assertEquals(new OpSpec.Fill(new Region.Cells(set), fill.pattern(), fill.mask()), edits.lastOp,
                "the op runs on the uploaded cells");

        receive(new C2S.Copy(17, reference(set), BlockPos.ORIGIN, false, CellMask.ANY, EntityFilter.NONE));
        assertEquals(new Region.Cells(set), clipboards.lastRegion);
        assertEquals(0, clipboards.reservedUploads);
    }

    @Test
    void aSelectionTheServerDoesNotHoldIsNotLoadedAndAMismatchInvalid() {
        handshake();
        CellSet set = selection(2);
        OpSpec.Erase erase = new OpSpec.Erase(reference(set), CellMask.ANY);
        receive(new C2S.RunOp(20, erase, false, ConflictPolicy.SKIP_CONFLICTS));
        receive(new C2S.Copy(21, reference(set), BlockPos.ORIGIN, true, CellMask.ANY, EntityFilter.NONE));
        assertEquals(List.of(new S2C.JobRejected(20, RejectReason.SELECTION_NOT_LOADED),
                new S2C.JobRejected(21, RejectReason.SELECTION_NOT_LOADED)), transport.sent,
                "no notice: the client uploads it again and retries");
        assertTrue(edits.calls.isEmpty() && clipboards.calls.isEmpty(), "nothing ran");

        uploadSelection(22, set);
        clipboards.<CellSet>reply(0).done(set);
        transport.sent.clear();
        Region.Uploaded wrongCount = new Region.Uploaded(set.hash(), set.bounds(), set.size() - 1);
        Region.Uploaded wrongBounds = new Region.Uploaded(set.hash(), BOX, 5);
        receive(new C2S.RunOp(23, new OpSpec.Erase(wrongCount, CellMask.ANY), false, ConflictPolicy.SKIP_CONFLICTS));
        receive(new C2S.RunOp(24, new OpSpec.Erase(wrongBounds, CellMask.ANY), false, ConflictPolicy.SKIP_CONFLICTS));
        assertEquals(List.of(new S2C.JobRejected(23, RejectReason.INVALID), new S2C.JobRejected(24, RejectReason.INVALID)),
                transport.sent);
        receive(new C2S.Copy(25, wrongCount, BlockPos.ORIGIN, false, CellMask.ANY, EntityFilter.NONE));
        assertEquals(new S2C.JobRejected(25, RejectReason.INVALID), transport.sent.get(2));
        assertEquals(ServerDispatcher.NOTICE_REQUEST_REFUSED, ((S2C.Notice) transport.sent.get(3)).key());
        assertTrue(edits.calls.isEmpty() && clipboards.calls.stream().noneMatch(c -> c.startsWith("copy")));
    }

    @Test
    void selectionUploadRefusalsBadBytesAndWrongStreamsAreRejected() {
        handshake();
        CellSet set = selection(3);
        clipboards.rejectNext = new EditRejected(RejectReason.TOO_LARGE, "3000000 > 2097152 blocks in a selection");
        Box wide = Box.of(new BlockPos(0, 0, 0), new BlockPos(199, 199, 199));
        receive(new C2S.SelectionUpload(30, set.hash(), wide, 3_000_000, 1000));
        assertEquals(new S2C.JobRejected(30, RejectReason.TOO_LARGE), transport.sent.get(0));
        List<String> args = ((S2C.Notice) transport.sent.get(1)).args();
        assertEquals("TOO_LARGE", args.get(0));
        assertTrue(args.get(1).endsWith("3000000 > 2097152 blocks in a selection"), args.get(1));
        assertTrue(transport.sent(S2C.UploadGrant.class).isEmpty(), "refused before any byte is sent");

        // The service could not read the bytes (or they are another set than announced).
        transport.sent.clear();
        uploadSelection(31, set);
        clipboards.<CellSet>reply(0).failed(RejectReason.INVALID, "the selection is not the one announced");
        assertEquals(new S2C.JobRejected(31, RejectReason.INVALID), transport.sent.get(transport.sent.size() - 2));
        assertEquals(0, session.selections().size());

        // A stream larger than announced, or of another kind.
        transport.sent.clear();
        receive(new C2S.SelectionUpload(32, set.hash(), set.bounds(), set.size(), 100));
        S2C.UploadGrant grant = transport.first(S2C.UploadGrant.class);
        receive(new StreamOpen(grant.streamId(), StreamKind.SELECTION_UPLOAD, 101, new TreeMap<>()));
        assertEquals(new StreamAbort(grant.streamId(), "too_large"), transport.sent.get(1));
        assertEquals(new S2C.JobRejected(32, RejectReason.TOO_LARGE), transport.sent.get(2));
        transport.sent.clear();
        receive(new C2S.SelectionUpload(33, set.hash(), set.bounds(), set.size(), 10));
        S2C.UploadGrant other = transport.first(S2C.UploadGrant.class);
        StreamSender schematic = new StreamSender(other.streamId(), StreamKind.SCHEM_UPLOAD, new byte[10], new TreeMap<>(),
                StreamSender.MAX_C2S_CHUNK, other.creditBytes());
        clock.addAndGet(2_000_000_000L);
        for (Message m : schematic.poll(StreamSender.CLIENT_BYTES_PER_TICK)) receive((C2S) m);
        assertEquals(new S2C.JobRejected(33, RejectReason.INVALID), transport.sent.get(transport.sent.size() - 2));
        assertEquals(0, clipboards.reservedUploads, "every failed upload released its reservation");
    }

    @Test
    void theStoreKeepsTheLastFewSetsWithinItsBytesAndEndsWithTheConnection() {
        handshake();
        List<CellSet> sets = new ArrayList<>();
        for (int i = 0; i < SelectionStore.MAX_SETS + 1; i++) {
            CellSet set = selection(10 + i);
            sets.add(set);
            uploadSelection(40 + i, set);
            clipboards.<CellSet>reply(i).done(set);
        }
        assertEquals(SelectionStore.MAX_SETS, session.selections().size());
        assertFalse(session.selections().contains(sets.get(0).hash()), "the oldest set was dropped");
        transport.sent.clear();
        receive(new C2S.RunOp(50, new OpSpec.Erase(reference(sets.get(0)), CellMask.ANY), false, ConflictPolicy.SKIP_CONFLICTS));
        assertEquals(List.of(new S2C.JobRejected(50, RejectReason.SELECTION_NOT_LOADED)), transport.sent);

        // A set over the store's bytes is refused; one within them pushes out older ones.
        transport.sent.clear();
        CellSet big = selection(99);
        clipboards.selectionStoreBytes = big.estimatedBytes() - 1;
        uploadSelection(51, big);
        clipboards.<CellSet>reply(SelectionStore.MAX_SETS + 1).done(big);
        assertTrue(transport.sent.contains(new S2C.JobRejected(51, RejectReason.TOO_LARGE)), transport.sent.toString());
        clipboards.selectionStoreBytes = big.estimatedBytes() + 1;
        uploadSelection(52, big);
        clipboards.<CellSet>reply(SelectionStore.MAX_SETS + 2).done(big);
        assertEquals(1, session.selections().size(), "older sets made room");
        assertTrue(session.selections().bytes() <= clipboards.selectionStoreBytes);

        dispatcher.close(session);
        assertEquals(0, session.selections().size(), "dropped with the connection");
    }

    /**
     * All connections' sets together stay within the server-wide cap: past it the least recently used set of any
     * connection goes, and its owner is told SELECTION_NOT_LOADED (its client uploads it again).
     */
    @Test
    void theStoresOfEveryConnectionShareAServerWideCap() {
        handshake();
        FakeTransport otherTransport = new FakeTransport();
        NetSession other = otherPlayer(otherTransport);
        CellSet mine = selection(30);
        CellSet theirs = selection(31);
        CellSet third = selection(32);
        clipboards.selectionStoreBytesTotal = mine.estimatedBytes() + theirs.estimatedBytes() + 10;
        uploadSelection(60, mine);
        clipboards.<CellSet>reply(0).done(mine);
        // The other player uploads two sets: the second pushes out the least recently used of anyone's, which is mine.
        for (CellSet set : List.of(theirs, third)) {
            byte[] bytes = set.encode();
            receiveOn(dispatcher, other, new C2S.SelectionUpload(61, set.hash(), set.bounds(), set.size(), bytes.length));
            S2C.UploadGrant grant = (S2C.UploadGrant) otherTransport.last();
            StreamSender sender = new StreamSender(grant.streamId(), StreamKind.SELECTION_UPLOAD, bytes, new TreeMap<>(),
                    StreamSender.MAX_C2S_CHUNK, grant.creditBytes());
            while (!sender.done()) {
                for (Message m : sender.poll(StreamSender.CLIENT_BYTES_PER_TICK)) receiveOn(dispatcher, other, (C2S) m);
                clock.addAndGet(100_000_000L);
            }
            clipboards.<CellSet>reply(clipboards.replies.size() - 1).done(set);
        }
        assertTrue(dispatcher.selectionBytes() <= clipboards.selectionStoreBytesTotal, "within the server-wide cap");
        assertEquals(0, session.selections().size(), "the least recently used set of anyone went");
        assertEquals(2, other.selections().size());
        transport.sent.clear();
        receive(new C2S.RunOp(62, new OpSpec.Erase(reference(mine), CellMask.ANY), false, ConflictPolicy.SKIP_CONFLICTS));
        assertEquals(List.of(new S2C.JobRejected(62, RejectReason.SELECTION_NOT_LOADED)), transport.sent);
        // A set alone over the server-wide cap is refused.
        clipboards.selectionStoreBytesTotal = mine.estimatedBytes() - 1;
        uploadSelection(63, mine);
        clipboards.<CellSet>reply(clipboards.replies.size() - 1).done(mine);
        assertTrue(transport.sent.contains(new S2C.JobRejected(63, RejectReason.TOO_LARGE)), transport.sent.toString());
    }

    /** A set whose decode finishes after its connection closed is not kept (nor answered). */
    @Test
    void aSetDecodedAfterTheConnectionClosedIsDropped() {
        handshake();
        CellSet set = selection(40);
        uploadSelection(70, set);
        dispatcher.close(session);
        transport.sent.clear();
        clipboards.<CellSet>reply(0).done(set);
        assertEquals(0, session.selections().size(), "kept in a closed connection's store");
        assertEquals(0, dispatcher.selectionBytes());
        assertTrue(transport.sent.isEmpty());
    }

    @Test
    void m2RefusalsAreJobRejectedWithTheReasonAndANotice() {
        handshake();
        clipboards.rejectNext = new EditRejected(RejectReason.PROTECTED, "source chunk 1,2 is protected");
        receive(new C2S.Copy(12, BOX, BlockPos.ORIGIN, true, CellMask.ANY));
        assertEquals(new S2C.JobRejected(12, RejectReason.PROTECTED), transport.sent.get(0));
        S2C.Notice notice = assertInstanceOf(S2C.Notice.class, transport.sent.get(1));
        assertEquals(ServerDispatcher.NOTICE_REQUEST_REFUSED, notice.key());
        assertEquals("PROTECTED", notice.args().get(0));
        assertTrue(notice.args().get(1).contains("protected"));
        assertEquals(2, transport.sent.size());

        transport.sent.clear();
        receive(new C2S.LibraryLoad(14, "trees/oak.schem"));
        clipboards.<ClipboardService.ClipboardInfo>reply(0).failed(RejectReason.TOO_LARGE, "3000000 > 2097152");
        assertEquals(new S2C.JobRejected(14, RejectReason.TOO_LARGE), transport.sent.get(0));
        assertEquals(List.of("TOO_LARGE", "3000000 > 2097152"), ((S2C.Notice) transport.sent.get(1)).args());

        transport.sent.clear();
        clipboards.failNext = new IllegalStateException("boom");
        receive(new C2S.SaveAsset(15, CLIPBOARD, "a.schem"));
        assertEquals(List.of(new S2C.JobRejected(15, RejectReason.INVALID)), transport.sent);
    }

    @Test
    void previewsOpenAStreamOrExplainWhyNot() throws ProtocolException {
        handshake();
        receive(new C2S.PreviewRequest(new SourceRef.Clipboard(CLIPBOARD)));
        byte[] payload = new byte[5000];
        new Random(2).nextBytes(payload);
        TreeMap<String, String> meta = new TreeMap<>();
        meta.put("clipboardId", CLIPBOARD.toString());
        clipboards.<ClipboardService.Outbound>reply(0)
                .done(new ClipboardService.Outbound(StreamKind.CLIPBOARD_PREVIEW, payload, meta));
        dispatcher.tick(session);
        StreamOpen open = transport.first(StreamOpen.class);
        assertEquals(StreamKind.CLIPBOARD_PREVIEW, open.kind());
        assertEquals(CLIPBOARD.toString(), open.meta().get("clipboardId"));
        StreamAssembler assembler = new StreamAssembler(open, 1 << 20, 1 << 20);
        for (StreamChunk chunk : transport.sent(StreamChunk.class)) assembler.accept(chunk);
        assertArrayEquals(payload, assembler.finish(transport.first(StreamEnd.class)));

        transport.sent.clear();
        receive(new C2S.PreviewRequest(new SourceRef.Asset(HASH)));
        clipboards.<ClipboardService.Outbound>reply(1).failed(RejectReason.INVALID, "unknown asset");
        S2C.Notice notice = assertInstanceOf(S2C.Notice.class, transport.last());
        assertEquals(ServerDispatcher.NOTICE_PREVIEW_REFUSED, notice.key());
        assertEquals(List.of("INVALID", "unknown asset"), notice.args());

        transport.sent.clear();
        clipboards.rejectNext = new EditRejected(RejectReason.NO_PERMISSION, "sculptory.clipboard");
        receive(new C2S.PreviewRequest(new SourceRef.Clipboard(CLIPBOARD)));
        assertEquals(1, transport.sent.size());
        assertEquals("NO_PERMISSION", ((S2C.Notice) transport.last()).args().get(0));
    }

    @Test
    void libraryListLoadAndSaveAreAnsweredByRequestId() {
        handshake();
        receive(new C2S.LibraryList(11, "trees"));
        List<S2C.LibraryListing.Entry> entries = List.of(new S2C.LibraryListing.Entry("trees/big", true, 0, ""),
                new S2C.LibraryListing.Entry("trees/oak.schem", false, 1234, HASH));
        clipboards.<ClipboardService.Listing>reply(0).done(new ClipboardService.Listing("trees", entries, true, true));
        assertEquals(new S2C.LibraryListing(11, "trees", entries, true), transport.sent.get(0));
        S2C.Notice truncated = assertInstanceOf(S2C.Notice.class, transport.sent.get(1));
        assertEquals(ServerDispatcher.NOTICE_LIBRARY_TRUNCATED, truncated.key());
        assertEquals(List.of("trees", "2"), truncated.args());

        transport.sent.clear();
        receive(new C2S.LibraryLoad(12, "trees/oak.schem"));
        clipboards.<ClipboardService.ClipboardInfo>reply(1).done(info(List.of()));
        assertEquals(new S2C.ClipboardReady(12, CLIPBOARD, new BlockPos(3, 4, 5), new BlockPos(1, 0, -2), 60, 4096),
                transport.last());

        transport.sent.clear();
        receive(new C2S.SaveAsset(13, CLIPBOARD, "trees/new.schem"));
        String mine = "_players/" + new UUID(1, 2) + "/trees/new.schem";
        clipboards.<ClipboardService.Saved>reply(2).done(new ClipboardService.Saved(mine, HASH));
        assertEquals(List.of(new S2C.AssetSaved(13, mine, HASH)), transport.sent);
        assertEquals(List.of("list trees", "load trees/oak.schem", "save " + CLIPBOARD + " trees/new.schem"),
                clipboards.calls);
    }

    /** A ready session on its own transport (another player). */
    private NetSession otherPlayer(FakeTransport other) {
        NetSession s = dispatcher.open(other);
        receiveOn(dispatcher, s, Handshake.hello("t", Features.of(Features.LIBRARY)));
        other.sent.clear();
        return s;
    }

    @Test
    void libraryChangesAreAnsweredByRequestIdAndPushedToOtherPlayersAsTheyMaySeeThem() {
        handshake();
        FakeTransport seesAll = new FakeTransport();
        FakeTransport seesPart = new FakeTransport();
        FakeTransport seesNothing = new FakeTransport();
        FakeTransport throwing = new FakeTransport();
        FakeTransport notReady = new FakeTransport();
        FakeTransport noLibrary = new FakeTransport();
        FakeTransport left = new FakeTransport();
        otherPlayer(seesAll);
        otherPlayer(seesPart);
        otherPlayer(seesNothing);
        otherPlayer(throwing);
        dispatcher.open(notReady); // no handshake: never told anything
        NetSession withoutLibrary = dispatcher.open(noLibrary);
        receiveOn(dispatcher, withoutLibrary, Handshake.hello("t", Features.of(Features.CLIPBOARD)));
        noLibrary.sent.clear();
        NetSession gone = otherPlayer(left);
        dispatcher.close(gone);

        receive(new C2S.LibraryMove(21, false, "shared/x.schem", "_players/" + new UUID(1, 2) + "/x.schem"));
        assertEquals(List.of("move false shared/x.schem _players/" + new UUID(1, 2) + "/x.schem"), clipboards.calls);
        clipboards.shown.add(change -> change);
        clipboards.shown.add(change -> new LibraryChange(change.folder(), change.from(), ""));
        clipboards.shown.add(change -> null);
        clipboards.shown.add(change -> {
            throw new IllegalStateException("permission backend unavailable");
        });
        LibraryChange change = new LibraryChange(false, "shared/x.schem", "_players/" + new UUID(1, 2) + "/x.schem");
        clipboards.<LibraryChange>reply(0).done(change);

        assertEquals(List.of(new S2C.LibraryChanged(21, false, change.from(), change.to())), transport.sent,
                "the requester gets the answer, not a push");
        assertEquals(List.of(new S2C.LibraryChanged(S2C.LibraryChanged.PUSH, false, change.from(), change.to())),
                seesAll.sent);
        assertEquals(List.of(new S2C.LibraryChanged(S2C.LibraryChanged.PUSH, false, "shared/x.schem", "")),
                seesPart.sent, "a path the player may not read is blanked");
        assertEquals(List.of(), seesNothing.sent);
        assertEquals(List.of(), throwing.sent, "a failing check tells that player nothing");
        assertEquals(List.of(), notReady.sent);
        assertEquals(List.of(), noLibrary.sent, "a client without the library feature is never pushed to");
        assertEquals(List.of(), left.sent);
        assertEquals(4, clipboards.shownCalls, "only the other ready sessions are asked");

        transport.sent.clear();
        receive(new C2S.LibraryDelete(22, true, "old"));
        receive(new C2S.LibraryCreateFolder(23, "trees/big"));
        clipboards.<LibraryChange>reply(1).done(new LibraryChange(true, "old", ""));
        clipboards.<LibraryChange>reply(2).done(new LibraryChange(true, "", "trees/big"));
        assertEquals(List.of(new S2C.LibraryChanged(22, true, "old", ""), new S2C.LibraryChanged(23, true, "", "trees/big")),
                transport.sent);
        assertEquals(List.of("delete true old", "folder trees/big"), clipboards.calls.subList(1, 3));
    }

    @Test
    void refusedLibraryChangesAreRejectedWithTheReasonAndChangeNothingForOthers() {
        handshake();
        FakeTransport other = new FakeTransport();
        otherPlayer(other);
        clipboards.rejectNext = new EditRejected(RejectReason.NO_PERMISSION,
                "changing the shared library needs sculptory.library.write (trees)");
        receive(new C2S.LibraryCreateFolder(31, "trees"));
        assertEquals(new S2C.JobRejected(31, RejectReason.NO_PERMISSION), transport.sent.get(0));
        S2C.Notice notice = assertInstanceOf(S2C.Notice.class, transport.sent.get(1));
        assertEquals(ServerDispatcher.NOTICE_REQUEST_REFUSED, notice.key());
        assertEquals("NO_PERMISSION", notice.args().get(0));
        assertTrue(notice.args().get(1).endsWith("changing the shared library needs sculptory.library.write (trees)"),
                notice.args().get(1));

        transport.sent.clear();
        receive(new C2S.LibraryMove(32, false, "a/x.schem", "a/y.schem"));
        clipboards.<LibraryChange>reply(0).failed(RejectReason.INVALID, "not found: a/x.schem");
        assertEquals(new S2C.JobRejected(32, RejectReason.INVALID), transport.sent.get(0));
        assertEquals(List.of("INVALID", "not found: a/x.schem"), ((S2C.Notice) transport.sent.get(1)).args());

        transport.sent.clear();
        clipboards.failNext = new IllegalStateException("boom");
        receive(new C2S.LibraryDelete(33, false, "a/y.schem"));
        assertEquals(List.of(new S2C.JobRejected(33, RejectReason.INVALID)), transport.sent, "an internal error");
        assertEquals(List.of(), other.sent);
        assertEquals(0, clipboards.shownCalls);
    }

    @Test
    void libraryChangesAreRateLimitedAndUndecodableOnesAnswered() throws ProtocolException {
        handshake();
        for (int i = 0; i < 12; i++) receive(new C2S.LibraryCreateFolder(40 + i, "f" + i));
        assertEquals(10, clipboards.calls.size(), "the ops bucket's burst");
        assertEquals(List.of(new S2C.JobRejected(50, RejectReason.RATE_LIMITED), new S2C.JobRejected(51,
                RejectReason.RATE_LIMITED)), transport.sent);

        transport.sent.clear();
        clock.addAndGet(10_000_000_000L);
        byte[] frame = Codec.encodeC2S(new C2S.LibraryMove(60, false, "a.schem", "b.schem"), STATES);
        frame[2] = 7; // not a boolean
        dispatcher.receive(session, frame);
        assertEquals(List.of(new S2C.JobRejected(60, RejectReason.INVALID)), transport.sent);
        assertEquals(10, clipboards.calls.size(), "never reached the service");
    }

    // ---------------------------------------------------------------- per-asset access

    private static final AssetAccess BOB_ONLY_ACCESS = AssetAccess.listed(List.of(
            new AssetAccess.Grantee(new UUID(5, 6), "Bob")));

    @Test
    void anAccessIsAskedAndAnsweredByRequestIdOrRefusedWithTheReason() {
        handshake();
        receive(new C2S.LibraryAccessGet(50, "trees/oak.schem"));
        assertEquals(List.of("access trees/oak.schem"), clipboards.calls);
        clipboards.<AssetAccess>reply(0).done(BOB_ONLY_ACCESS);
        assertEquals(List.of(new S2C.LibraryAccess(50, "trees/oak.schem", BOB_ONLY_ACCESS)), transport.sent);

        transport.sent.clear();
        clipboards.rejectNext = new EditRejected(RejectReason.NO_PERMISSION,
                "changing who may load a shared entry needs sculptory.library.write (trees/oak.schem)");
        receive(new C2S.LibraryAccessGet(51, "trees/oak.schem"));
        assertEquals(new S2C.JobRejected(51, RejectReason.NO_PERMISSION), transport.sent.get(0));
        S2C.Notice notice = transport.first(S2C.Notice.class);
        assertEquals(ServerDispatcher.NOTICE_REQUEST_REFUSED, notice.key());
        assertEquals("NO_PERMISSION", notice.args().get(0));
        assertTrue(notice.args().get(1).contains("sculptory.library.write"), notice.args().get(1));

        transport.sent.clear();
        receive(new C2S.LibraryAccessGet(52, "trees/oak.schem"));
        clipboards.<AssetAccess>reply(1).failed(RejectReason.INVALID, "not found: trees/oak.schem");
        assertEquals(new S2C.JobRejected(52, RejectReason.INVALID), transport.sent.get(0));
        assertTrue(transport.sent(S2C.LibraryAccess.class).isEmpty(), "no access is sent on a refusal");
    }

    @Test
    void anAccessChangeIsAnsweredAsTheEntryChangedInPlaceAndPushedAsEachPlayerSeesIt() {
        handshake();
        FakeTransport granted = new FakeTransport();
        FakeTransport revoked = new FakeTransport();
        FakeTransport unaffected = new FakeTransport();
        FakeTransport throwing = new FakeTransport();
        FakeTransport noLibrary = new FakeTransport();
        otherPlayer(granted);
        otherPlayer(revoked);
        otherPlayer(unaffected);
        otherPlayer(throwing);
        NetSession withoutLibrary = dispatcher.open(noLibrary);
        receiveOn(dispatcher, withoutLibrary, Handshake.hello("t", Features.of(Features.CLIPBOARD)));
        noLibrary.sent.clear();

        receive(new C2S.LibraryAccessSet(60, "trees/oak.schem", BOB_ONLY_ACCESS));
        assertEquals(List.of("set access trees/oak.schem LISTED 1"), clipboards.calls);
        clipboards.shownAccess.add(change -> new LibraryChange(false, change.path(), change.path()));
        clipboards.shownAccess.add(change -> new LibraryChange(false, change.path(), ""));
        clipboards.shownAccess.add(change -> null);
        clipboards.shownAccess.add(change -> {
            throw new IllegalStateException("permission backend unavailable");
        });
        ClipboardService.AccessChange change = new ClipboardService.AccessChange("trees/oak.schem", AssetAccess.EVERYONE,
                BOB_ONLY_ACCESS);
        clipboards.<ClipboardService.AccessChange>reply(0).done(change);

        assertEquals(List.of(new S2C.LibraryChanged(60, false, "trees/oak.schem", "trees/oak.schem")), transport.sent,
                "the requester's entry changed in place");
        assertEquals(List.of(new S2C.LibraryChanged(S2C.LibraryChanged.PUSH, false, "trees/oak.schem", "trees/oak.schem")),
                granted.sent, "a player who may read it afterwards sees it change in place (or appear)");
        assertEquals(List.of(new S2C.LibraryChanged(S2C.LibraryChanged.PUSH, false, "trees/oak.schem", "")),
                revoked.sent, "a player who lost access sees it vanish");
        assertEquals(List.of(), unaffected.sent);
        assertEquals(List.of(), throwing.sent, "a failing check tells that player nothing");
        assertEquals(List.of(), noLibrary.sent, "a client without the library feature is never pushed to");
        assertEquals(4, clipboards.shownAccessCalls, "only the other ready sessions with the library are asked");
        assertEquals(0, clipboards.shownCalls, "an access change is not pushed as a plain change");
    }

    @Test
    void aRefusedAccessChangeIsRejectedWithTheReasonAndPushedToNobody() throws ProtocolException {
        handshake();
        FakeTransport other = new FakeTransport();
        otherPlayer(other);
        clipboards.rejectNext = new EditRejected(RejectReason.NO_PERMISSION,
                "another player's entry needs sculptory.admin (_players/00000000-0000-0000-0000-000000000001/x.schem)");
        receive(new C2S.LibraryAccessSet(70, "_players/00000000-0000-0000-0000-000000000001/x.schem", BOB_ONLY_ACCESS));
        assertEquals(new S2C.JobRejected(70, RejectReason.NO_PERMISSION), transport.sent.get(0));
        assertTrue(transport.first(S2C.Notice.class).args().get(1).contains("sculptory.admin"));
        assertEquals(List.of(), other.sent);

        transport.sent.clear();
        receive(new C2S.LibraryAccessSet(71, "trees/oak.schem", AssetAccess.listed(List.of(
                new AssetAccess.Grantee(null, "Zed")))));
        clipboards.<ClipboardService.AccessChange>reply(0).failed(RejectReason.INVALID, "unknown player: Zed");
        assertEquals(new S2C.JobRejected(71, RejectReason.INVALID), transport.sent.get(0));
        assertTrue(transport.first(S2C.Notice.class).args().get(1).endsWith("unknown player: Zed"));
        assertEquals(List.of(), other.sent, "nothing changed: nothing is pushed");
        assertEquals(0, clipboards.shownAccessCalls);

        // Undecodable: answered without reaching the service, like the other requests.
        transport.sent.clear();
        byte[] frame = Codec.encodeC2S(new C2S.LibraryAccessSet(63, "a.schem", BOB_ONLY_ACCESS), STATES);
        frame[1 + 1 + 1 + 7] = 5; // type, a one-byte request id, the path length and "a.schem", then the mode
        dispatcher.receive(session, frame);
        assertEquals(List.of(new S2C.JobRejected(63, RejectReason.INVALID)), transport.sent);
        assertEquals(2, clipboards.calls.size(), "never reached the service");
    }

    @Test
    void accessRequestsShareTheOpsBucket() {
        handshake();
        for (int i = 0; i < 6; i++) receive(new C2S.LibraryAccessGet(80 + i, "p" + i + ".schem"));
        for (int i = 0; i < 6; i++) receive(new C2S.LibraryAccessSet(90 + i, "p" + i + ".schem", AssetAccess.EVERYONE));
        assertEquals(10, clipboards.calls.size(), "the ops bucket's burst");
        assertEquals(List.of(new S2C.JobRejected(94, RejectReason.RATE_LIMITED), new S2C.JobRejected(95,
                RejectReason.RATE_LIMITED)), transport.sent);
    }

    // ---------------------------------------------------------------- palettes

    private static final BlockPalette MOSS = new BlockPalette(List.of(new BlockPalette.Entry("minecraft:moss_block", 4),
            new BlockPalette.Entry("minecraft:sea_pickle[pickles=2,waterlogged=true]", 1)));

    @Test
    void aPaletteSaveIsAnsweredAsALibraryChangeAndPushedToOthers() {
        handshake();
        FakeTransport other = new FakeTransport();
        otherPlayer(other);
        receive(new C2S.PaletteSave(70, "biomes/moss.palette.json", MOSS));
        assertEquals(List.of("save palette biomes/moss.palette.json 2"), clipboards.calls);
        assertEquals(List.of(), transport.sent, "answered once the file is written");
        String own = "_players/" + new UUID(1, 2) + "/biomes/moss.palette.json";
        clipboards.shown.add(change -> change);
        clipboards.<LibraryChange>reply(0).done(new LibraryChange(false, "", own));
        assertEquals(List.of(new S2C.LibraryChanged(70, false, "", own)), transport.sent,
                "the path actually written (a player folder without library.write)");
        assertEquals(List.of(new S2C.LibraryChanged(S2C.LibraryChanged.PUSH, false, "", own)), other.sent);
    }

    @Test
    void aPaletteLoadIsAnsweredWithTheServersPaletteAndWhatItLeftOut() {
        handshake();
        receive(new C2S.PaletteLoad(71, "biomes/moss.palette.json"));
        assertEquals(List.of("load palette biomes/moss.palette.json"), clipboards.calls);
        clipboards.<ClipboardService.LoadedPalette>reply(0).done(new ClipboardService.LoadedPalette(
                "biomes/moss.palette.json", MOSS, 2, List.of("modded:gone", "modded:also_gone")));
        assertEquals(List.of(new S2C.PaletteData(71, "biomes/moss.palette.json", MOSS, 2,
                List.of("modded:gone", "modded:also_gone"))), transport.sent);
    }

    @Test
    void refusedPaletteRequestsAreRejectedWithTheReasonAndNothingIsPushed() throws ProtocolException {
        handshake();
        FakeTransport other = new FakeTransport();
        otherPlayer(other);
        clipboards.rejectNext = new EditRejected(RejectReason.INVALID, "palette moss.palette.json: unknown block state: modded:gone");
        receive(new C2S.PaletteSave(72, "moss.palette.json", MOSS));
        assertEquals(new S2C.JobRejected(72, RejectReason.INVALID), transport.sent.get(0));
        S2C.Notice notice = assertInstanceOf(S2C.Notice.class, transport.sent.get(1));
        assertEquals(ServerDispatcher.NOTICE_REQUEST_REFUSED, notice.key());
        assertEquals("INVALID", notice.args().get(0));
        assertTrue(notice.args().get(1).endsWith("palette moss.palette.json: unknown block state: modded:gone"),
                notice.args().get(1));

        transport.sent.clear();
        receive(new C2S.PaletteLoad(73, "moss.palette.json"));
        clipboards.<ClipboardService.LoadedPalette>reply(0).failed(RejectReason.NO_PERMISSION, "not your folder");
        assertEquals(new S2C.JobRejected(73, RejectReason.NO_PERMISSION), transport.sent.get(0));
        assertEquals(List.of("NO_PERMISSION", "not your folder"), ((S2C.Notice) transport.sent.get(1)).args());

        transport.sent.clear();
        receive(new C2S.PaletteSave(74, "moss.palette.json", MOSS));
        clipboards.<LibraryChange>reply(1).failed(RejectReason.TOO_LARGE, "the player folder is full");
        assertEquals(new S2C.JobRejected(74, RejectReason.TOO_LARGE), transport.sent.get(0));
        assertEquals(List.of(), other.sent, "a refused save is pushed to nobody");
        assertEquals(0, clipboards.shownCalls);

        transport.sent.clear();
        clipboards.failNext = new IllegalStateException("boom");
        receive(new C2S.PaletteLoad(75, "moss.palette.json"));
        assertEquals(List.of(new S2C.JobRejected(75, RejectReason.INVALID)), transport.sent, "an internal error");

        // Undecodable: answered by request id, and never reaches the service.
        transport.sent.clear();
        int calls = clipboards.calls.size();
        byte[] frame = Codec.encodeC2S(new C2S.PaletteSave(76, "a.palette.json", BlockPalette.of("minecraft:stone", 5)),
                STATES);
        frame[frame.length - 6] = 0; // weight 0 (before the palette's 5-byte Random pattern)
        dispatcher.receive(session, frame);
        assertEquals(List.of(new S2C.JobRejected(76, RejectReason.INVALID)), transport.sent);
        assertEquals(calls, clipboards.calls.size());
    }

    @Test
    void paletteRequestsShareTheOpsBucket() {
        handshake();
        for (int i = 0; i < 6; i++) receive(new C2S.PaletteSave(80 + i, "p" + i + ".palette.json", MOSS));
        for (int i = 0; i < 6; i++) receive(new C2S.PaletteLoad(90 + i, "p" + i + ".palette.json"));
        assertEquals(10, clipboards.calls.size(), "the ops bucket's burst");
        assertEquals(List.of(new S2C.JobRejected(94, RejectReason.RATE_LIMITED), new S2C.JobRejected(95,
                RejectReason.RATE_LIMITED)), transport.sent);
    }

    @Test
    void exportStreamsTheSchematicTaggedWithTheRequestId() throws ProtocolException {
        handshake();
        receive(new C2S.ExportClipboard(15, CLIPBOARD));
        byte[] file = new byte[3000];
        new Random(4).nextBytes(file);
        TreeMap<String, String> meta = new TreeMap<>();
        meta.put("fileName", "clipboard.schem");
        clipboards.<ClipboardService.Outbound>reply(0).done(new ClipboardService.Outbound(StreamKind.SCHEM_FILE, file, meta));
        dispatcher.tick(session);
        StreamOpen open = transport.first(StreamOpen.class);
        assertEquals(StreamKind.SCHEM_FILE, open.kind());
        assertEquals("15", open.meta().get("reqId"));
        assertEquals("clipboard.schem", open.meta().get("fileName"));
        StreamAssembler assembler = new StreamAssembler(open, 1 << 20, 1 << 20);
        for (StreamChunk chunk : transport.sent(StreamChunk.class)) assembler.accept(chunk);
        assertArrayEquals(file, assembler.finish(transport.first(StreamEnd.class)));
    }

    @Test
    void exportsAskForTheRequestedFormat() {
        handshake();
        receive(new C2S.ExportClipboard(15, CLIPBOARD, SchematicFormat.LITEMATIC));
        receive(new C2S.ExportClipboard(16, CLIPBOARD, SchematicFormat.STRUCTURE));
        receive(new C2S.ExportClipboard(17, CLIPBOARD));
        assertEquals(List.of("export " + CLIPBOARD + " LITEMATIC", "export " + CLIPBOARD + " STRUCTURE",
                "export " + CLIPBOARD), clipboards.calls);
    }

    @Test
    void uploadsAreGrantedAssembledParsedAndAnswered() {
        handshake();
        byte[] file = new byte[70_000];
        new Random(8).nextBytes(file);
        receive(new C2S.UploadBegin(20, "house.schem", file.length));
        S2C.UploadGrant grant = transport.first(S2C.UploadGrant.class);
        assertEquals(20, grant.reqId());
        StreamSender sender = new StreamSender(grant.streamId(), StreamKind.SCHEM_UPLOAD, file, new TreeMap<>(),
                StreamSender.MAX_C2S_CHUNK, grant.creditBytes());
        while (!sender.done()) {
            for (Message m : sender.poll(StreamSender.CLIENT_BYTES_PER_TICK)) receive((C2S) m);
            clock.addAndGet(100_000_000L);
        }
        assertEquals(List.of("begin house.schem " + file.length, "uploaded house.schem " + file.length), clipboards.calls);
        assertArrayEquals(file, clipboards.uploadedBytes);
        transport.sent.clear();
        clipboards.<ClipboardService.ClipboardInfo>reply(0).done(info(List.of()));
        assertEquals(List.of(new S2C.ClipboardReady(20, CLIPBOARD, new BlockPos(3, 4, 5), new BlockPos(1, 0, -2), 60, 4096),
                new S2C.UploadResult(20, CLIPBOARD, null)), transport.sent);

        // A parse failure is an UploadResult error.
        transport.sent.clear();
        receive(new C2S.UploadBegin(21, "bad.schem", 2000));
        S2C.UploadGrant second = (S2C.UploadGrant) transport.last();
        StreamSender bad = new StreamSender(second.streamId(), StreamKind.SCHEM_UPLOAD, new byte[2000], new TreeMap<>(),
                StreamSender.MAX_C2S_CHUNK, second.creditBytes());
        clock.addAndGet(2_000_000_000L);
        for (Message m : bad.poll(StreamSender.CLIENT_BYTES_PER_TICK)) receive((C2S) m);
        clipboards.<ClipboardService.ClipboardInfo>reply(1).failed(RejectReason.INVALID, "missing Version");
        assertEquals(new S2C.UploadResult(21, null, "INVALID: missing Version"), transport.last());
    }

    /**
     * An upload keeps the byte cap it was granted with: lowering {@code maxUploadBytes} afterwards (a config reload)
     * doesn't cut off a stream of the granted size; the next upload gets the new cap.
     */
    @Test
    void aGrantedUploadKeepsItsByteCapWhenTheLimitIsLowered() {
        handshake();
        byte[] file = new byte[70_000];
        new Random(9).nextBytes(file);
        receive(new C2S.UploadBegin(20, "house.schem", file.length));
        S2C.UploadGrant grant = transport.first(S2C.UploadGrant.class);
        Limits d = Limits.DEFAULTS;
        limits = new Limits(d.maxOpVolume(), d.maxClipboardVolume(), d.maxBrushRadius(), d.maxDabRate(), 1_000,
                d.maxJobsPerPlayer());
        StreamSender sender = new StreamSender(grant.streamId(), StreamKind.SCHEM_UPLOAD, file, new TreeMap<>(),
                StreamSender.MAX_C2S_CHUNK, grant.creditBytes());
        while (!sender.done()) {
            for (Message m : sender.poll(StreamSender.CLIENT_BYTES_PER_TICK)) receive((C2S) m);
            clock.addAndGet(100_000_000L);
        }
        assertEquals(List.of("begin house.schem " + file.length, "uploaded house.schem " + file.length), clipboards.calls);
        assertArrayEquals(file, clipboards.uploadedBytes);
        assertEquals(0, session.violations(), "the stream at the granted size is not over any cap");

        // A grant after the change is capped by the new limit: a stream larger than it is refused.
        clipboards.<ClipboardService.ClipboardInfo>reply(0).done(info(List.of()));
        transport.sent.clear();
        receive(new C2S.UploadBegin(21, "next.schem", 5_000));
        S2C.UploadGrant next = transport.first(S2C.UploadGrant.class);
        StreamSender tooBig = new StreamSender(next.streamId(), StreamKind.SCHEM_UPLOAD, new byte[5_000], new TreeMap<>(),
                StreamSender.MAX_C2S_CHUNK, next.creditBytes());
        clock.addAndGet(2_000_000_000L);
        for (Message m : tooBig.poll(StreamSender.CLIENT_BYTES_PER_TICK)) receive((C2S) m);
        assertTrue(transport.sent(S2C.UploadResult.class).stream().anyMatch(r -> r.reqId() == 21 && r.error() != null),
                "a stream over the new cap was accepted: " + transport.sent);
    }

    @Test
    void uploadsAreRefusedBeforeTheGrantOrWhenLargerThanAnnounced() {
        handshake();
        clipboards.rejectNext = new EditRejected(RejectReason.TOO_LARGE, "big");
        receive(new C2S.UploadBegin(20, "house.schem", 1L << 40));
        assertEquals(new S2C.JobRejected(20, RejectReason.TOO_LARGE), transport.sent.get(0));
        assertTrue(transport.sent(S2C.UploadGrant.class).isEmpty());

        transport.sent.clear();
        receive(new C2S.UploadBegin(21, "house.schem", 100));
        S2C.UploadGrant grant = transport.first(S2C.UploadGrant.class);
        receive(new StreamOpen(grant.streamId(), StreamKind.SCHEM_UPLOAD, 101, new TreeMap<>()));
        assertEquals(new StreamAbort(grant.streamId(), "too_large"), transport.sent.get(1));
        assertEquals(new S2C.UploadResult(21, null, "upload failed: too_large"), transport.sent.get(2));

        // Uploads of another stream kind are refused.
        transport.sent.clear();
        receive(new C2S.UploadBegin(22, "house.schem", 10));
        S2C.UploadGrant other = transport.first(S2C.UploadGrant.class);
        StreamSender preview = new StreamSender(other.streamId(), StreamKind.CLIPBOARD_PREVIEW, new byte[10],
                new TreeMap<>(), StreamSender.MAX_C2S_CHUNK, other.creditBytes());
        clock.addAndGet(2_000_000_000L);
        for (Message m : preview.poll(StreamSender.CLIENT_BYTES_PER_TICK)) receive((C2S) m);
        assertEquals(new S2C.UploadResult(22, null, "INVALID: expected a SCHEM_UPLOAD stream"), transport.last());
        assertEquals(0, clipboards.reservedUploads, "every failed upload released its reservation");
    }

    /** Generators: a sparse clipboard upload runs exactly as a {@code .schem} upload does, on its own stream kind. */
    @Test
    void generatedUploadsAreGrantedAssembledAndAnsweredLikeSchematics() {
        handshake();
        byte[] payload = new byte[30_000];
        new Random(9).nextBytes(payload);
        Box bounds = new Box(new BlockPos(0, 60, 0), new BlockPos(9, 63, 9));
        receive(new C2S.GeneratedUpload(30, bounds, 120, payload.length));
        S2C.UploadGrant grant = transport.first(S2C.UploadGrant.class);
        assertEquals(30, grant.reqId());
        StreamSender sender = new StreamSender(grant.streamId(), StreamKind.GENERATED_UPLOAD, payload, new TreeMap<>(),
                StreamSender.MAX_C2S_CHUNK, grant.creditBytes());
        while (!sender.done()) {
            for (Message m : sender.poll(StreamSender.CLIENT_BYTES_PER_TICK)) receive((C2S) m);
            clock.addAndGet(100_000_000L);
        }
        assertEquals(List.of("begin generated 120 30000", "generated uploaded 30000"), clipboards.calls);
        assertArrayEquals(payload, clipboards.uploadedBytes);
        transport.sent.clear();
        clipboards.<ClipboardService.ClipboardInfo>reply(0).done(info(List.of()));
        assertEquals(List.of(new S2C.ClipboardReady(30, CLIPBOARD, new BlockPos(3, 4, 5), new BlockPos(1, 0, -2), 60, 4096),
                new S2C.UploadResult(30, CLIPBOARD, null)), transport.sent);

        // A decode failure is an UploadResult error, with the reason up front.
        transport.sent.clear();
        receive(new C2S.GeneratedUpload(31, bounds, 5, 200));
        S2C.UploadGrant second = (S2C.UploadGrant) transport.last();
        StreamSender bad = new StreamSender(second.streamId(), StreamKind.GENERATED_UPLOAD, new byte[200], new TreeMap<>(),
                StreamSender.MAX_C2S_CHUNK, second.creditBytes());
        clock.addAndGet(2_000_000_000L);
        for (Message m : bad.poll(StreamSender.CLIENT_BYTES_PER_TICK)) receive((C2S) m);
        clipboards.<ClipboardService.ClipboardInfo>reply(1).failed(RejectReason.INVALID, "not a sparse upload");
        assertEquals(new S2C.UploadResult(31, null, "INVALID: not a sparse upload"), transport.last());

        // A schematic stream on a generated grant is refused, and a refusal before the grant is a JobRejected.
        transport.sent.clear();
        receive(new C2S.GeneratedUpload(32, bounds, 5, 10));
        S2C.UploadGrant other = transport.first(S2C.UploadGrant.class);
        StreamSender schem = new StreamSender(other.streamId(), StreamKind.SCHEM_UPLOAD, new byte[10], new TreeMap<>(),
                StreamSender.MAX_C2S_CHUNK, other.creditBytes());
        clock.addAndGet(2_000_000_000L);
        for (Message m : schem.poll(StreamSender.CLIENT_BYTES_PER_TICK)) receive((C2S) m);
        assertEquals(new S2C.UploadResult(32, null, "INVALID: expected a GENERATED_UPLOAD stream"), transport.last());
        assertEquals(0, clipboards.reservedUploads, "the refused upload released its reservation");
        transport.sent.clear();
        clipboards.rejectNext = new EditRejected(RejectReason.NO_PERMISSION, "sculptory.clipboard");
        receive(new C2S.GeneratedUpload(33, bounds, 5, 10));
        assertEquals(new S2C.JobRejected(33, RejectReason.NO_PERMISSION), transport.sent.get(0));
        assertTrue(transport.sent(S2C.UploadGrant.class).isEmpty());
    }

    @Test
    void uploadReservationsAreReleasedWhenTheGrantFailsOrThePlayerLeaves() {
        handshake();
        receive(new C2S.UploadBegin(1, "a.schem", 100));
        receive(new C2S.UploadBegin(2, "b.schem", 100));
        receive(new C2S.UploadBegin(3, "c.schem", 100));
        assertEquals(new S2C.JobRejected(3, RejectReason.QUEUE_FULL), transport.sent(S2C.JobRejected.class).get(0));
        assertEquals(2, clipboards.reservedUploads, "the third upload's reservation was not released");
        dispatcher.close(session);
        assertEquals(0, clipboards.reservedUploads, "disconnecting did not release the reservations");
    }

    @Test
    void stalledUploadsAreAbortedAndReleased() {
        handshake();
        receive(new C2S.UploadBegin(1, "never-opened.schem", 5000));
        receive(new C2S.UploadBegin(2, "stops.schem", 5000));
        S2C.UploadGrant stops = transport.sent(S2C.UploadGrant.class).get(1);
        StreamSender sender = new StreamSender(stops.streamId(), StreamKind.SCHEM_UPLOAD, new byte[5000], new TreeMap<>(),
                StreamSender.MAX_C2S_CHUNK, stops.creditBytes());
        // The open and part of the data arrive, then nothing more.
        List<Message> first = sender.poll(2048);
        for (Message m : first) receive((C2S) m);
        clock.addAndGet(ServerDispatcher.STALL_NANOS - 1_000_000_000L);
        dispatcher.tick(session);
        assertEquals(2, session.openUploads(), "aborted too early");
        clock.addAndGet(2_000_000_000L);
        dispatcher.tick(session);
        assertEquals(0, session.openUploads());
        assertEquals(0, clipboards.reservedUploads);
        assertTrue(transport.sent(StreamAbort.class).stream().allMatch(a -> a.reason().equals("stalled")));
        assertEquals(2, transport.sent(StreamAbort.class).size());
        assertEquals(List.of(new S2C.UploadResult(1, null, "upload failed: stalled"),
                new S2C.UploadResult(2, null, "upload failed: stalled")), transport.sent(S2C.UploadResult.class));
    }

    @Test
    void outboundStreamsWithoutCreditAreAbortedAfterTheStallTimeout() {
        handshake();
        byte[] payload = new byte[6 << 20];
        int id = dispatcher.openStream(session, StreamKind.CLIPBOARD_PREVIEW, payload, new TreeMap<>()).getAsInt();
        for (int tick = 0; tick < 10; tick++) {
            dispatcher.tick(session);
            clock.addAndGet(50_000_000L);
        }
        assertEquals(1, session.outboundStreams(), "stalled on credit, not aborted yet");
        clock.addAndGet(ServerDispatcher.STALL_NANOS - 1_000_000_000L);
        dispatcher.tick(session);
        assertEquals(1, session.outboundStreams(), "aborted before the timeout");
        clock.addAndGet(2_000_000_000L);
        dispatcher.tick(session);
        assertEquals(0, session.outboundStreams());
        assertEquals(new StreamAbort(id, "stalled"), transport.last());
    }

    @Test
    void tinyCreditCannotKeepAStreamAlivePastItsDeadline() {
        handshake();
        byte[] payload = new byte[6 << 20];
        int id = dispatcher.openStream(session, StreamKind.CLIPBOARD_PREVIEW, payload, new TreeMap<>()).getAsInt();
        long deadline = ServerDispatcher.streamDeadlineNanos(payload.length);
        assertEquals(ServerDispatcher.STREAM_MIN_DEADLINE_NANOS, deadline, "6 MiB at 256 KiB/s is under a minute");
        long elapsed = 0;
        // Fill the window, then keep granting just enough to move one small chunk every 20 s.
        while (elapsed <= deadline) {
            dispatcher.tick(session);
            if (session.outboundStreams() == 0) break;
            receive(new StreamCredit(id, StreamAssembler.MIN_CHUNK_BYTES));
            clock.addAndGet(20_000_000_000L);
            elapsed += 20_000_000_000L;
        }
        dispatcher.tick(session);
        assertEquals(0, session.outboundStreams(), "the stream outlived its deadline");
        assertEquals(new StreamAbort(id, "deadline"), transport.last());
        assertEquals(0, session.outboundBytes());
        assertEquals(0, dispatcher.outboundBytesTotal());
        assertTrue(ServerDispatcher.streamDeadlineNanos(64L << 20) == 256_000_000_000L, "64 MiB gets 256 s");
    }

    @Test
    void queuedBytesAreCappedPerPlayerAndServerWide() {
        handshake();
        long perPlayer = 64 << 10;
        dispatcher.setOutboundByteCaps(perPlayer, 4 * perPlayer);
        byte[] big = new byte[(int) (perPlayer / 2)];
        assertTrue(dispatcher.openStream(session, StreamKind.SCHEM_FILE, big, new TreeMap<>()).isPresent());
        assertTrue(dispatcher.openStream(session, StreamKind.SCHEM_FILE, big, new TreeMap<>()).isPresent());
        assertTrue(dispatcher.openStream(session, StreamKind.SCHEM_FILE, new byte[1], new TreeMap<>()).isEmpty(),
                "past the per-player byte cap");
        assertEquals(perPlayer, session.outboundBytes());
        // Refused before any work, too.
        receive(new C2S.ExportClipboard(5, CLIPBOARD));
        assertTrue(clipboards.calls.isEmpty());
        assertEquals(new S2C.JobRejected(5, RejectReason.QUEUE_FULL), transport.sent(S2C.JobRejected.class).get(0));
        // Other players share the server-wide cap: 3 more full sessions fill it.
        List<NetSession> others = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            NetSession other = dispatcher.open(new FakeTransport());
            dispatcher.receive(other, encode(Handshake.hello("t", Features.NONE)));
            others.add(other);
            assertTrue(dispatcher.openStream(other, StreamKind.SCHEM_FILE, big, new TreeMap<>()).isPresent());
            assertTrue(dispatcher.openStream(other, StreamKind.SCHEM_FILE, big, new TreeMap<>()).isPresent());
        }
        assertEquals(4 * perPlayer, dispatcher.outboundBytesTotal());
        NetSession fifth = dispatcher.open(new FakeTransport());
        dispatcher.receive(fifth, encode(Handshake.hello("t", Features.NONE)));
        assertTrue(dispatcher.openStream(fifth, StreamKind.SCHEM_FILE, new byte[1], new TreeMap<>()).isEmpty(),
                "past the server-wide byte cap");
        dispatcher.close(others.get(0));
        assertTrue(dispatcher.openStream(fifth, StreamKind.SCHEM_FILE, new byte[1], new TreeMap<>()).isPresent(),
                "closing a session gives its bytes back");
        dispatcher.close(session);
        dispatcher.close(others.get(1));
        dispatcher.close(others.get(2));
        dispatcher.close(fifth);
        assertEquals(0, dispatcher.outboundBytesTotal());
    }

    private static byte[] encode(C2S message) {
        try {
            return Codec.encodeC2S(message, STATES);
        } catch (ProtocolException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aStreamWaitingForItsShareOfTheBudgetIsNotStalled() throws ProtocolException {
        handshake();
        byte[] large = new byte[6 << 20];
        byte[] small = new byte[10 << 10];
        new Random(6).nextBytes(small);
        int big = dispatcher.openStream(session, StreamKind.SCHEM_FILE, large, new TreeMap<>()).getAsInt();
        int little = dispatcher.openStream(session, StreamKind.CLIPBOARD_PREVIEW, small, new TreeMap<>()).getAsInt();
        // Three seconds per tick: the large stream takes the whole budget for more than the stall timeout.
        for (int tick = 0; tick < 16; tick++) {
            dispatcher.tick(session);
            receive(new StreamCredit(big, 1 << 20));
            clock.addAndGet(3_000_000_000L);
        }
        assertTrue(transport.sent(StreamAbort.class).isEmpty(), "aborted: " + transport.sent(StreamAbort.class));
        for (int tick = 0; tick < 4; tick++) dispatcher.tick(session);
        StreamOpen open = transport.sent(StreamOpen.class).stream().filter(o -> o.id() == little).findFirst().orElseThrow();
        StreamAssembler assembler = new StreamAssembler(open, 1 << 20, 1 << 20);
        for (StreamChunk chunk : transport.sent(StreamChunk.class)) {
            if (chunk.id() == little) assembler.accept(chunk);
        }
        StreamEnd end = transport.sent(StreamEnd.class).stream().filter(e -> e.id() == little).findFirst().orElseThrow();
        assertArrayEquals(small, assembler.finish(end), "the small stream arrived intact");
        assertEquals(0, session.outboundStreams());
    }

    @Test
    void streamCapacityIsCheckedBeforeAnyWork() {
        handshake();
        for (int i = 0; i < NetSession.MAX_OUTBOUND_STREAMS - 1; i++) {
            dispatcher.openStream(session, StreamKind.CLIPBOARD_PREVIEW, new byte[10], new TreeMap<>());
        }
        receive(new C2S.PreviewRequest(new SourceRef.Clipboard(CLIPBOARD)));
        assertEquals(1, clipboards.calls.size(), "the last free stream is taken by a pending preview");
        transport.sent.clear();
        receive(new C2S.PreviewRequest(new SourceRef.Clipboard(CLIPBOARD)));
        receive(new C2S.ExportClipboard(9, CLIPBOARD));
        assertEquals(1, clipboards.calls.size(), "work started past the stream cap");
        assertEquals(List.of(MessageType.NOTICE, MessageType.JOB_REJECTED, MessageType.NOTICE), transport.types());
        assertEquals(new S2C.JobRejected(9, RejectReason.QUEUE_FULL), transport.sent.get(1));
        // The pending preview fails: its place is free again.
        clipboards.<ClipboardService.Outbound>reply(0).failed(RejectReason.INVALID, "gone");
        receive(new C2S.ExportClipboard(10, CLIPBOARD));
        assertEquals(2, clipboards.calls.size());
        // A synchronous refusal frees it too.
        clipboards.<ClipboardService.Outbound>reply(1).failed(RejectReason.INVALID, "gone");
        clipboards.rejectNext = new EditRejected(RejectReason.NO_PERMISSION, "export");
        receive(new C2S.ExportClipboard(11, CLIPBOARD));
        receive(new C2S.ExportClipboard(12, CLIPBOARD));
        assertEquals(4, clipboards.calls.size());
    }

    @Test
    void removalNoticesFollowPreviewsExportsAndSaves() {
        handshake();
        S2C.Notice removed = new S2C.Notice(S2C.Notice.Level.WARN, "sculptory.notice.export_operator_nbt_removed",
                List.of("2", "1"));
        receive(new C2S.ExportClipboard(15, CLIPBOARD));
        clipboards.<ClipboardService.Outbound>reply(0).done(new ClipboardService.Outbound(StreamKind.SCHEM_FILE,
                new byte[10], new TreeMap<>(), List.of(removed)));
        assertEquals(removed, transport.last());
        receive(new C2S.SaveAsset(16, CLIPBOARD, "a.schem"));
        clipboards.<ClipboardService.Saved>reply(1).done(new ClipboardService.Saved("a.schem", HASH, List.of(removed)));
        assertEquals(List.of(new S2C.AssetSaved(16, "a.schem", HASH), removed),
                transport.sent.subList(transport.sent.size() - 2, transport.sent.size()));
    }

    // ---------------------------------------------------------------- strokes

    @Test
    void strokeBeginDabsAndEndProduceStrokeStatus() {
        handshake();
        receive(new C2S.StrokeBegin(1, SPEC));
        assertEquals(new S2C.StrokeStatus(1, -1, S2C.StrokeStatus.Status.OK, null), transport.last());
        receive(new C2S.Dabs(1, 40, dabs(0, 4)));
        assertEquals("dabs 1 40 4", edits.calls.get(1));
        assertEquals(new S2C.StrokeStatus(1, 3, S2C.StrokeStatus.Status.OK, null), transport.last());
        assertTrue(transport.acks.isEmpty(), "accepted dabs are acknowledged once the brush lane applies them");
        assertEquals(1, session.pendingPredictions());
        dispatcher.predictionApplied(session, 40);
        assertEquals(List.of(40), transport.acks);
        receive(new C2S.StrokeEnd(1));
        assertEquals("end 1", edits.calls.get(2));
        assertEquals(new S2C.StrokeStatus(1, 3, S2C.StrokeStatus.Status.ENDED, null), transport.sent.get(transport.sent.size() - 2));
        assertInstanceOf(S2C.HistoryState.class, transport.last());
    }

    /**
     * The brush lane's written batches of a Shape stroke are reported ({@code StrokeStatus OK} with the applied index), also
     * after the stroke ended, so its client paces on them; a terrain stroke's are not (its client paces on admission).
     */
    @Test
    void writtenShapeDabsAreReportedForTheClientsPacing() {
        handshake();
        BrushSpec shape = BrushSpec.shape(32, new dev.sculptory.core.brush.ShapeSpec(
                dev.sculptory.core.brush.ShapeSpec.Kind.CUBE, 65, dev.sculptory.core.region.Facing.UP,
                dev.sculptory.core.brush.ShapeSpec.Mode.PLACE, 0), new Pattern.Single(1), 1L, null, Symmetry.NONE);
        receive(new C2S.StrokeBegin(1, shape));
        receive(new C2S.Dabs(1, 40, dabs(0, 1)));
        assertEquals(new S2C.StrokeStatus(1, 0, S2C.StrokeStatus.Status.OK, null, -1), transport.last(), "admitted, not written");
        dispatcher.dabsApplied(session, 1, 0);
        assertEquals(new S2C.StrokeStatus(1, 0, S2C.StrokeStatus.Status.OK, null, 0), transport.last());
        receive(new C2S.Dabs(1, 41, dabs(1, 1)));
        receive(new C2S.StrokeEnd(1));
        transport.sent.clear();
        dispatcher.dabsApplied(session, 1, 1);
        assertEquals(List.of(new S2C.StrokeStatus(1, 1, S2C.StrokeStatus.Status.OK, null, 1)), transport.sent,
                "an ended stroke's writes are still reported");
        dispatcher.dabsApplied(session, 1, 1);
        assertEquals(1, transport.sent.size(), "reported once");

        receive(new C2S.StrokeBegin(2, SPEC));
        receive(new C2S.Dabs(2, 42, dabs(0, 2)));
        transport.sent.clear();
        dispatcher.dabsApplied(session, 2, 1);
        assertTrue(transport.sent.isEmpty(), "a terrain stroke's writes are not reported: " + transport.sent);
    }

    /**
     * A request refused only because the player's brush stroke is still being applied ({@code STROKE_PENDING}) is marked
     * with a notice naming it before its refusal, so the client sends it again instead of reporting it; other refusals
     * are not.
     */
    @Test
    void refusalsForAPendingStrokeAreMarkedFirst() {
        handshake();
        edits.rejectNext = new EditRejected(RejectReason.QUEUE_FULL, "a large brush stroke is still being applied",
                EditRejected.STROKE_PENDING);
        receive(fill(6));
        S2C.Notice marker = new S2C.Notice(S2C.Notice.Level.INFO, ServerDispatcher.NOTICE_STROKE_PENDING, List.of("6"));
        assertEquals(List.of(marker, new S2C.JobRejected(6, RejectReason.QUEUE_FULL)), transport.sent);

        transport.sent.clear();
        edits.rejectNext = new EditRejected(RejectReason.QUEUE_FULL, "a large brush stroke is still being applied",
                EditRejected.STROKE_PENDING);
        receive(new C2S.Undo(8, ConflictPolicy.SKIP_CONFLICTS));
        assertEquals(List.of(MessageType.NOTICE, MessageType.JOB_REJECTED, MessageType.HISTORY_STATE), transport.types());
        assertEquals(List.of("8"), ((S2C.Notice) transport.sent.get(0)).args());

        transport.sent.clear();
        edits.rejectNext = new EditRejected(RejectReason.QUEUE_FULL, "a job is still running");
        receive(fill(9));
        assertEquals(List.of(new S2C.JobRejected(9, RejectReason.QUEUE_FULL)), transport.sent, "not held up by a stroke");
    }

    @Test
    void rejectedDabsAreStillAcknowledgedSoThePredictionReverts() {
        handshake();
        receive(new C2S.StrokeBegin(1, SPEC));
        edits.dabOutcome = DabOutcome.rejected(5, RejectReason.AREA_BUSY);
        receive(new C2S.Dabs(1, 41, dabs(4, 2)));
        assertEquals(List.of(41), transport.acks);
        assertEquals(new S2C.StrokeStatus(1, 5, S2C.StrokeStatus.Status.REJECTED, RejectReason.AREA_BUSY), transport.last());
    }

    @Test
    void strokeRefusalsAndUnknownStrokes() {
        handshake();
        edits.rejectNext = new EditRejected(RejectReason.NO_PERMISSION);
        receive(new C2S.StrokeBegin(2, SPEC));
        assertEquals(new S2C.StrokeStatus(2, -1, S2C.StrokeStatus.Status.REJECTED, RejectReason.NO_PERMISSION), transport.last());
        receive(new C2S.Dabs(2, 42, dabs(0, 3)));
        assertEquals(new S2C.StrokeStatus(2, 2, S2C.StrokeStatus.Status.REJECTED, RejectReason.INVALID), transport.last());
        assertEquals(List.of(42), transport.acks);
        assertEquals(List.of("begin 2"), edits.calls, "dabs for a stroke that never began do not reach the engine");
    }

    @Test
    void aStrokeWithAMalformedClipBoxIsRefusedInvalidWithoutReachingTheEngine() throws ProtocolException {
        handshake();
        BrushSpec clipped = SPEC.withClip(Box.of(new BlockPos(0, 10, 0), new BlockPos(3, 20, 3)));
        receive(new C2S.StrokeBegin(4, clipped));
        assertEquals(List.of("begin 4"), edits.calls, "a valid clip box reaches the engine");
        assertEquals(clipped, edits.lastSpec);
        // The box is six one-byte zigzags before the symmetry (Off: one byte) that ends the frame; swap min x (0) and
        // max x (3, zigzag 6).
        byte[] frame = Codec.encodeC2S(new C2S.StrokeBegin(5, clipped), STATES);
        assertEquals(0, frame[frame.length - 1], "symmetry off");
        int box = frame.length - 7;
        assertEquals(1, frame[box - 1], "clip presence flag");
        assertEquals(0, frame[box]);
        assertEquals(6, frame[box + 3]);
        frame[box] = 6;
        frame[box + 3] = 0;
        dispatcher.receive(session, frame);
        assertEquals(new S2C.StrokeStatus(5, -1, S2C.StrokeStatus.Status.REJECTED, RejectReason.INVALID), transport.last());
        assertEquals(1, session.violations());
        assertEquals(List.of("begin 4"), edits.calls, "the malformed stroke never reaches the engine");
    }

    @Test
    void aNewStrokeEndsThePreviousOneAndDisconnectEndsTheOpenOne() {
        handshake();
        receive(new C2S.StrokeBegin(1, SPEC));
        receive(new C2S.StrokeBegin(2, SPEC));
        assertEquals(List.of("begin 1", "end 1", "begin 2"), edits.calls);
        dispatcher.close(session);
        assertEquals(List.of("begin 1", "end 1", "begin 2", "end 2"), edits.calls);
        int sent = transport.frames;
        receive(fill(3));
        dispatcher.close(session);
        assertEquals(sent, transport.frames, "nothing is sent after close");
        assertEquals(4, edits.calls.size());
    }

    @Test
    void refusalAcksWaitForEarlierAdmittedBatches() {
        handshake();
        receive(new C2S.StrokeBegin(1, SPEC));
        receive(new C2S.Dabs(1, 5, dabs(0, 2)));
        edits.dabOutcome = DabOutcome.rejected(3, RejectReason.AREA_BUSY);
        receive(new C2S.Dabs(1, 6, dabs(2, 2)));
        assertTrue(transport.acks.isEmpty(), "acking 6 now would revert batch 5's prediction before its blocks arrive");
        dispatcher.predictionApplied(session, 5);
        assertEquals(List.of(6), transport.acks, "one cumulative ack covers both");
        assertEquals(0, session.pendingPredictions());
    }

    // ---------------------------------------------------------------- resync

    /** Begins stroke 1 (radius 8) and has one dab accepted at block (x, 64, z). */
    private void strokeWithDabAt(int strokeId, int x, int z) {
        receive(new C2S.StrokeBegin(strokeId, SPEC));
        receive(new C2S.Dabs(strokeId, 1, List.of(new Dab(0, x * 16, 64 * 16, z * 16, 255))));
        transport.sent.clear();
    }

    private static Box chunks(int minCx, int minCz, int maxCx, int maxCz) {
        return new Box(new BlockPos(minCx * 16, 0, minCz * 16), new BlockPos(maxCx * 16 + 15, 100, maxCz * 16 + 15));
    }

    @Test
    void resyncServesOnlyTrackedChunksOfTheStrokeFootprint() {
        handshake();
        strokeWithDabAt(1, 100, -50); // radius 8: chunks x 5..6, z -4..-3
        receive(new C2S.Resync(chunks(5, -4, 6, -3)));
        assertEquals(List.of("5,-4", "5,-3", "6,-4", "6,-3"), transport.resent);
        transport.resent.clear();
        transport.tracked = (cx, cz) -> !(cx == 6 && cz == -3);
        receive(new C2S.Resync(chunks(0, -8, 7, -1)));
        assertEquals(List.of("5,-4", "5,-3", "6,-4"), transport.resent, "clamped to the footprint and view distance");
        assertEquals(0, session.violations());
        assertTrue(transport.sent.isEmpty());
    }

    @Test
    void resyncNeedsBrushPermissionAndARecentStroke() {
        handshake();
        receive(new C2S.Resync(chunks(5, -4, 6, -3)));
        assertEquals(new S2C.Notice(S2C.Notice.Level.WARN, ServerDispatcher.RESYNC_NO_STROKE, List.of()),
                transport.last());

        strokeWithDabAt(1, 100, -50);
        permissions.granted.remove(Perm.BRUSH);
        receive(new C2S.Resync(chunks(5, -4, 6, -3)));
        assertEquals(ServerDispatcher.RESYNC_NO_PERMISSION, ((S2C.Notice) transport.last()).key());
        permissions.granted.add(Perm.BRUSH);

        receive(new C2S.StrokeEnd(1));
        clock.addAndGet(9_000_000_000L);
        receive(new C2S.Resync(chunks(5, -4, 5, -4)));
        assertEquals(List.of("5,-4"), transport.resent, "still served 9 s after the stroke ended");
        clock.addAndGet(2_000_000_000L);
        receive(new C2S.Resync(chunks(5, -4, 5, -4)));
        assertEquals(ServerDispatcher.RESYNC_NO_STROKE, ((S2C.Notice) transport.last()).key());
        assertEquals(1, transport.resent.size());
        assertEquals(0, session.violations());
    }

    @Test
    void thePreviousStrokeStaysResyncableWhileANewOneRuns() {
        handshake();
        strokeWithDabAt(1, 100, -50);
        strokeWithDabAt(2, 500, 500);
        receive(new C2S.Resync(chunks(5, -4, 5, -4)));
        receive(new C2S.Resync(chunks(31, 31, 31, 31)));
        assertEquals(List.of("5,-4", "31,31"), transport.resent);
    }

    @Test
    void outOfRangeAndOversizedResyncsAreViolations() {
        handshake();
        strokeWithDabAt(1, 100, -50);
        receive(new C2S.Resync(chunks(1000, 1000, 1001, 1001)));
        assertEquals(1, session.violations());
        assertEquals(ServerDispatcher.RESYNC_OUT_OF_RANGE, ((S2C.Notice) transport.last()).key());
        assertTrue(transport.resent.isEmpty());

        // A whole-world request: clamped to the footprint, answered, and counted.
        receive(new C2S.Resync(new Box(new BlockPos(-30_000_000, 0, -30_000_000), new BlockPos(30_000_000, 0, 30_000_000))));
        assertEquals(2, session.violations());
        assertEquals(4, transport.resent.size());
        S2C.Notice clamped = (S2C.Notice) transport.last();
        assertEquals("sculptory.notice.resync_clamped", clamped.key());
        assertEquals("4", clamped.args().get(0));
    }

    // ---------------------------------------------------------------- limits and violations

    @Test
    void opsAndDabsAreRateLimited() {
        handshake();
        for (int i = 0; i < 10; i++) receive(new C2S.Undo(i, ConflictPolicy.SKIP_CONFLICTS));
        receive(new C2S.Undo(10, ConflictPolicy.SKIP_CONFLICTS));
        assertEquals(new S2C.JobRejected(10, RejectReason.RATE_LIMITED), transport.last());
        assertEquals(10, edits.calls.size());
        clock.addAndGet(1_000_000_000L);
        receive(new C2S.Undo(11, ConflictPolicy.SKIP_CONFLICTS));
        assertEquals(new S2C.JobAccepted(11, JOB, 10), transport.last());

        receive(new C2S.StrokeBegin(1, SPEC));
        for (int i = 0; i < 3; i++) receive(new C2S.Dabs(1, i, dabs(i * 16, 16)));
        receive(new C2S.Dabs(1, 99, dabs(48, 16)));
        assertEquals(new S2C.StrokeStatus(1, 63, S2C.StrokeStatus.Status.REJECTED, RejectReason.RATE_LIMITED), transport.last());
        assertTrue(transport.acks.isEmpty(), "the refusal waits for the three admitted batches");
        for (int seq = 0; seq < 3; seq++) dispatcher.predictionApplied(session, seq);
        assertEquals(List.of(0, 1, 99), transport.acks);
        assertEquals(0, session.violations(), "rate limiting is not a violation");
    }

    /**
     * The client predicted every dab frame it sent, so each decoded one is reported as a prediction, including those
     * refused before the edit service sees them (over the dab budget, or with no open stroke) and those the brush lane
     * refuses. Frames never decoded (before the handshake) are not.
     */
    @Test
    void refusedDabsStillCountAsPredictions() {
        receive(new C2S.Dabs(1, 0, dabs(0, 1)));
        assertEquals(0, edits.predictions, "a frame before the handshake is never decoded");
        handshake();

        receive(new C2S.Dabs(1, 0, dabs(0, 1)));
        assertEquals(new S2C.StrokeStatus(1, 0, S2C.StrokeStatus.Status.REJECTED, RejectReason.INVALID), transport.last());
        assertEquals(1, edits.predictions, "no open stroke: refused by the dispatcher, still predicted");

        receive(new C2S.StrokeBegin(1, SPEC));
        for (int i = 0; i < 3; i++) receive(new C2S.Dabs(1, i + 1, dabs(i * 16, 16)));
        assertEquals(4, edits.predictions);
        receive(new C2S.Dabs(1, 9, dabs(48, 16)));
        assertEquals(new S2C.StrokeStatus(1, 63, S2C.StrokeStatus.Status.REJECTED, RejectReason.RATE_LIMITED), transport.last());
        assertEquals(5, edits.predictions, "over the dab budget: refused before the edit service, still predicted");
        assertEquals(3, edits.calls.stream().filter(c -> c.startsWith("dabs ")).count(),
                "the refused frame never reached the edit service");

        clock.addAndGet(1_000_000_000L);
        edits.dabOutcome = DabOutcome.rejected(64, RejectReason.AREA_BUSY);
        receive(new C2S.Dabs(1, 10, dabs(64, 1)));
        assertEquals(new S2C.StrokeStatus(1, 64, S2C.StrokeStatus.Status.REJECTED, RejectReason.AREA_BUSY), transport.last());
        assertEquals(6, edits.predictions, "refused by the brush lane");
        assertEquals(1, session.violations(), "only the frame before the handshake");
    }

    /** A dab frame over the frame rate is refused before it is decoded for dispatch, and still counts as predicted. */
    @Test
    void dabFramesOverTheFrameRateStillCountAsPredictions() {
        handshake();
        receive(new C2S.StrokeBegin(1, SPEC));
        long burst = RateLimiter.Kind.DAB_FRAMES.burst();
        for (int i = 0; i < burst; i++) receive(new C2S.Dabs(1, i, dabs(i, 1)));
        assertEquals(burst, edits.calls.stream().filter(c -> c.startsWith("dabs ")).count());
        receive(new C2S.Dabs(1, 500, dabs((int) burst, 1)));
        assertEquals(new S2C.StrokeStatus(1, (int) burst, S2C.StrokeStatus.Status.REJECTED, RejectReason.RATE_LIMITED),
                transport.last());
        assertEquals(burst, edits.calls.stream().filter(c -> c.startsWith("dabs ")).count(),
                "the refused frame never reached the edit service");
        assertEquals(burst + 1, edits.predictions);
        assertEquals(0, session.violations());
    }

    @Test
    void onlyHelloIsDecodedBeforeTheHandshakeAndRateLimitsApplyBeforeDecoding() throws ProtocolException {
        CountingStates counting = new CountingStates();
        ServerDispatcher counted = new ServerDispatcher(edits, permissions, limits, () -> counting, clock::get);
        NetSession other = counted.open(transport);
        byte[] runOp = Codec.encodeC2S(fill(1), STATES);
        counted.receive(other, runOp);
        assertEquals(0, counting.parses, "nothing but Hello is decoded before the handshake");
        assertEquals(1, other.violations());

        counted.receive(other, Codec.encodeC2S(Handshake.hello("t", Features.NONE), STATES));
        transport.sent.clear();
        for (int i = 0; i < 10; i++) counted.receive(other, runOp);
        assertEquals(10, counting.parses);
        counted.receive(other, Codec.encodeC2S(fill(77), STATES));
        assertEquals(10, counting.parses, "a rate-limited frame is refused before it is decoded");
        assertEquals(new S2C.JobRejected(77, RejectReason.RATE_LIMITED), transport.last());
    }

    @Test
    void unknownStateRefusalsAreBudgetedThenCountAsViolations() throws ProtocolException {
        ServerDispatcher noStates = new ServerDispatcher(edits, permissions, limits, () -> null, clock::get);
        NetSession other = noStates.open(transport);
        noStates.receive(other, Codec.encodeC2S(Handshake.hello("t", Features.NONE), STATES));
        byte[] frame = Codec.encodeC2S(fill(22), STATES);
        // One frame every 200 ms keeps the ops bucket topped up; the unknown-state budget is 20/min.
        for (int i = 0; i <= 20; i++) {
            noStates.receive(other, frame);
            clock.addAndGet(200_000_000L);
        }
        assertEquals(0, other.violations(), "20 plus the 1 refilled over 4 s are excused");
        for (int i = 0; i < 4; i++) {
            noStates.receive(other, frame);
            clock.addAndGet(200_000_000L);
        }
        assertEquals(4, other.violations());
        assertEquals(new S2C.JobRejected(22, RejectReason.INVALID), transport.sent(S2C.JobRejected.class).get(24));
    }

    @Test
    void repeatedMalformedFramesDisconnect() {
        handshake();
        Random rnd = new Random(1);
        for (int i = 0; i < NetSession.MAX_VIOLATIONS - 1; i++) {
            byte[] garbage = new byte[8];
            rnd.nextBytes(garbage);
            garbage[0] = 0; // no such type
            dispatcher.receive(session, garbage);
        }
        assertNull(transport.disconnected);
        dispatcher.receive(session, new byte[] {(byte) MessageType.STROKE_END.code()});
        assertEquals("Sculptory: too many invalid messages", transport.disconnected);
        assertEquals(NetSession.Stage.CLOSED, session.stage());
        int sent = transport.frames;
        receive(fill(1));
        assertEquals(sent, transport.frames);
    }

    @Test
    void malformedRequestsAreStillAnsweredByRequestId() throws ProtocolException {
        handshake();
        byte[] frame = Codec.encodeC2S(fill(21), STATES);
        byte[] truncated = java.util.Arrays.copyOf(frame, frame.length - 1);
        dispatcher.receive(session, truncated);
        assertEquals(new S2C.JobRejected(21, RejectReason.INVALID), transport.last());
        assertEquals(1, session.violations());
    }

    @Test
    void unknownBlockStatesAreRefusedWithoutCountingAsViolations() throws ProtocolException {
        handshake();
        byte[] frame = Codec.encodeC2S(fill(22), STATES);
        ServerDispatcher noStates = new ServerDispatcher(edits, permissions, limits, () -> null, clock::get);
        NetSession other = noStates.open(transport);
        noStates.receive(other, Codec.encodeC2S(Handshake.hello("t", Features.NONE), STATES));
        transport.sent.clear();
        noStates.receive(other, frame);
        assertEquals(new S2C.JobRejected(22, RejectReason.INVALID), transport.sent.get(0));
        S2C.Notice notice = assertInstanceOf(S2C.Notice.class, transport.sent.get(1));
        assertEquals("sculptory.notice.unknown_state", notice.key());
        assertEquals(0, other.violations());
    }

    /**
     * A symmetric stroke's dabs cost every copy in the dab bucket (60): a dab off the centre is four under Rotate 4,
     * one on the centre is one. The spec reaches the edit service with its symmetry.
     */
    @Test
    void symmetricDabsAreChargedWithEveryCopy() {
        handshake();
        Symmetry rotate = new Symmetry(Symmetry.Mode.ROTATE_4, 1, 1);
        receive(new C2S.StrokeBegin(1, SPEC.withSymmetry(rotate)));
        assertEquals(rotate, edits.lastSpec.symmetry());
        for (int i = 0; i < 3; i++) receive(new C2S.Dabs(1, i, dabs(i * 5, 5))); // 3 × 5 dabs × 4 copies = 60
        assertEquals(3, edits.calls.stream().filter(c -> c.startsWith("dabs ")).count());
        receive(new C2S.Dabs(1, 3, dabs(15, 1)));
        assertEquals(new S2C.StrokeStatus(1, 15, S2C.StrokeStatus.Status.REJECTED, RejectReason.RATE_LIMITED), transport.last());
        clock.addAndGet(50_000_000L); // three tokens
        receive(new C2S.Dabs(1, 4, List.of(new Dab(16, 8, 1000, 8, 255)))); // on the centre: one copy
        assertEquals(new S2C.StrokeStatus(1, 16, S2C.StrokeStatus.Status.OK, null), transport.last());
        receive(new C2S.Dabs(1, 5, dabs(17, 1)));
        assertEquals(new S2C.StrokeStatus(1, 17, S2C.StrokeStatus.Status.REJECTED, RejectReason.RATE_LIMITED), transport.last());
        assertEquals(0, session.violations(), "rate limiting is not a violation");
    }

    /**
     * A malformed symmetry (an unknown mode, or a Rotate 4 centre mixing a block centre and an edge) makes the
     * StrokeBegin undecodable: refused INVALID with one violation each, and the engine never sees it.
     */
    @Test
    void aStrokeWithAMalformedSymmetryIsRefusedInvalidWithoutReachingTheEngine() throws ProtocolException {
        handshake();
        BrushSpec rotated = SPEC.withSymmetry(new Symmetry(Symmetry.Mode.ROTATE_4, 21, -7));
        receive(new C2S.StrokeBegin(4, rotated));
        assertEquals(List.of("begin 4"), edits.calls, "a valid symmetry reaches the engine");
        assertEquals(rotated, edits.lastSpec);
        // The frame ends with the mode and the centre as one-byte zigzags: 21 -> 42, -7 -> 13.
        byte[] frame = Codec.encodeC2S(new C2S.StrokeBegin(5, rotated), STATES);
        int mode = frame.length - 3;
        assertEquals(Symmetry.Mode.ROTATE_4.ordinal(), frame[mode]);
        assertEquals(42, frame[mode + 1]);
        assertEquals(13, frame[mode + 2]);
        byte[] mixed = frame.clone();
        mixed[mode + 2] = 15; // z -8: a block edge, while x 10.5 is a block centre
        dispatcher.receive(session, mixed);
        assertEquals(new S2C.StrokeStatus(5, -1, S2C.StrokeStatus.Status.REJECTED, RejectReason.INVALID), transport.last());
        assertEquals(1, session.violations());
        byte[] unknown = frame.clone();
        unknown[mode] = (byte) Symmetry.Mode.values().length;
        dispatcher.receive(session, unknown);
        assertEquals(new S2C.StrokeStatus(5, -1, S2C.StrokeStatus.Status.REJECTED, RejectReason.INVALID), transport.last());
        assertEquals(2, session.violations());
        assertEquals(List.of("begin 4"), edits.calls, "the malformed strokes never reach the engine");
    }

    @Test
    void resyncsServeTheCopiesFootprint() {
        handshake();
        receive(new C2S.StrokeBegin(1, SPEC.withSymmetry(new Symmetry(Symmetry.Mode.MIRROR_X, 0, 0))));
        receive(new C2S.Dabs(1, 1, List.of(new Dab(0, 100 * 16, 64 * 16, -50 * 16, 255)))); // mirrored to x -100
        transport.sent.clear();
        receive(new C2S.Resync(chunks(-7, -4, -6, -3)));
        assertEquals(List.of("-7,-4", "-7,-3", "-6,-4", "-6,-3"), transport.resent);
        transport.resent.clear();
        receive(new C2S.Resync(chunks(5, -4, 6, -3)));
        assertEquals(List.of("5,-4", "5,-3", "6,-4", "6,-3"), transport.resent, "and the dab's own");
        assertEquals(0, session.violations());
    }

    // ---------------------------------------------------------------- pushes and streams

    @Test
    void permissionsChangedIsPushedOnlyWhenSomethingChanged() {
        handshake();
        dispatcher.permissionsChanged(session);
        assertTrue(transport.sent.isEmpty());
        permissions.granted.add(Perm.PHYSICS);
        dispatcher.permissionsChanged(session);
        S2C.PermissionsChanged changed = transport.first(S2C.PermissionsChanged.class);
        assertTrue(changed.permissions().has(Perm.PHYSICS.bit()));
        limits = new Limits(10, 10, 4, 5, 100, 1);
        dispatcher.permissionsChanged(session);
        assertEquals(limits, ((S2C.PermissionsChanged) transport.last()).limits());
    }

    @Test
    void permissionsAreRecheckedEvery40TicksAndPushedOnlyWhenChanged() {
        handshake();
        int checks = permissions.checks;
        for (int i = 1; i < ServerDispatcher.PERMISSION_RECHECK_TICKS; i++) dispatcher.tick(session);
        assertEquals(checks, permissions.checks, "no permission lookups between re-checks");
        dispatcher.tick(session);
        assertEquals(checks + Perm.values().length, permissions.checks, "one lookup per node every 40 ticks");
        assertTrue(transport.sent.isEmpty(), "unchanged: nothing is pushed");

        // A permissions mod removes the editor node: the next re-check pushes it.
        permissions.granted.remove(Perm.USE);
        for (int i = 0; i < ServerDispatcher.PERMISSION_RECHECK_TICKS; i++) dispatcher.tick(session);
        S2C.PermissionsChanged pushed = transport.first(S2C.PermissionsChanged.class);
        assertFalse(pushed.permissions().has(Perm.USE.bit()));
        assertTrue(pushed.permissions().has(Perm.BRUSH.bit()));
        assertEquals(session.permissions(), pushed.permissions());
        for (int i = 0; i < 3 * ServerDispatcher.PERMISSION_RECHECK_TICKS; i++) dispatcher.tick(session);
        assertEquals(1, transport.sent.size(), "pushed once, not on every re-check");

        // An immediate re-check (op or deop) restarts the interval.
        permissions.granted.add(Perm.USE);
        dispatcher.permissionsChanged(session);
        assertEquals(2, transport.sent.size());
        checks = permissions.checks;
        for (int i = 1; i < ServerDispatcher.PERMISSION_RECHECK_TICKS; i++) dispatcher.tick(session);
        assertEquals(checks, permissions.checks);
    }

    @Test
    void playersWithoutTheEditorAreNeverCheckedOrSentPermissions() {
        // No handshake: a vanilla client, or one that has not said Hello yet.
        permissions.granted.remove(Perm.USE);
        for (int i = 0; i < 5 * ServerDispatcher.PERMISSION_RECHECK_TICKS; i++) dispatcher.tick(session);
        dispatcher.permissionsChanged(session);
        assertEquals(0, permissions.checks, "no lookups for a session without the editor");
        assertTrue(transport.sent.isEmpty());
        assertEquals(0, transport.frames);

        // A closed session (the player left) is ignored too.
        handshake();
        dispatcher.close(session);
        int checks = permissions.checks;
        permissions.granted.add(Perm.PHYSICS);
        dispatcher.permissionsChanged(session);
        for (int i = 0; i < 2 * ServerDispatcher.PERMISSION_RECHECK_TICKS; i++) dispatcher.tick(session);
        assertEquals(checks, permissions.checks);
        assertTrue(transport.sent.isEmpty());
    }

    @Test
    void strokeBeginsAndResyncsDoNotSpendTheOpBudget() {
        handshake();
        for (int i = 0; i < 10; i++) receive(new C2S.Undo(i, ConflictPolicy.SKIP_CONFLICTS));
        receive(new C2S.Undo(10, ConflictPolicy.SKIP_CONFLICTS));
        assertEquals(new S2C.JobRejected(10, RejectReason.RATE_LIMITED), transport.last(), "the op bucket is empty");

        // A resync burst the size of the client's per-timeout maximum is still answered (here: no stroke yet).
        transport.sent.clear();
        for (int i = 0; i < 8; i++) receive(new C2S.Resync(chunks(5, -4, 5, -4)));
        assertEquals(8, transport.sent.size());
        assertTrue(transport.sent.stream().allMatch(m -> m instanceof S2C.Notice n
                && n.key().equals(ServerDispatcher.RESYNC_NO_STROKE)), transport.sent.toString());
        receive(new C2S.Resync(chunks(5, -4, 5, -4)));
        assertEquals(8, transport.sent.size(), "the ninth in the same instant is over the resync limit");

        // Quick brush taps: each press begins a stroke.
        for (int id = 1; id <= 15; id++) {
            receive(new C2S.StrokeBegin(id, SPEC));
            assertEquals(new S2C.StrokeStatus(id, -1, S2C.StrokeStatus.Status.OK, null), transport.last(), "tap " + id);
        }
        assertEquals(0, session.violations());
    }

    @Test
    void outboundStreamsRespectTheTickBudgetAndCredit() {
        handshake();
        byte[] payload = new byte[6 << 20];
        new Random(3).nextBytes(payload);
        int id = dispatcher.openStream(session, StreamKind.CLIPBOARD_PREVIEW, payload, new TreeMap<>()).getAsInt();
        long sentBefore = 0;
        for (int tick = 0; tick < 12; tick++) {
            dispatcher.tick(session);
            long sent = transport.sent(StreamChunk.class).stream().mapToLong(StreamChunk::length).sum();
            assertTrue(sent - sentBefore <= StreamSender.SERVER_BYTES_PER_TICK, "per-tick budget");
            sentBefore = sent;
        }
        assertTrue(sentBefore <= StreamAssembler.DEFAULT_WINDOW
                && sentBefore > StreamAssembler.DEFAULT_WINDOW - StreamAssembler.MIN_CHUNK_BYTES, "stalls at the 4 MiB window");
        receive(new StreamCredit(id, 2 << 20));
        for (int tick = 0; tick < 5; tick++) dispatcher.tick(session);
        StreamAssembler assembler;
        try {
            assembler = new StreamAssembler(transport.first(StreamOpen.class), 8 << 20, Long.MAX_VALUE);
            for (StreamChunk chunk : transport.sent(StreamChunk.class)) assembler.accept(chunk);
            assertArrayEquals(payload, assembler.finish(transport.first(StreamEnd.class)));
        } catch (ProtocolException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void grantedUploadsAreAssembledWithCreditAtTheClientPace() {
        handshake();
        List<byte[]> completed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int id = dispatcher.grantUpload(session, 30, 8 << 20, new RecordingUpload(completed, failed)).getAsInt();
        S2C.UploadGrant grant = transport.first(S2C.UploadGrant.class);
        assertEquals(new S2C.UploadGrant(30, id, StreamAssembler.DEFAULT_WINDOW), grant);

        byte[] payload = new byte[6 << 20];
        new Random(5).nextBytes(payload);
        StreamSender sender = new StreamSender(id, StreamKind.SCHEM_UPLOAD, payload, new TreeMap<>(),
                StreamSender.MAX_C2S_CHUNK, grant.creditBytes());
        int ticks = 0;
        while (!sender.done()) {
            assertTrue(++ticks < 1000, "the upload makes progress");
            for (Message m : sender.poll(StreamSender.CLIENT_BYTES_PER_TICK)) receive((C2S) m);
            for (StreamCredit credit : transport.sent(StreamCredit.class)) sender.credit(credit);
            transport.sent.removeIf(m -> m instanceof StreamCredit);
            clock.addAndGet(50_000_000L);
        }
        assertEquals(1, completed.size());
        assertArrayEquals(payload, completed.get(0));
        assertTrue(failed.isEmpty());
        assertTrue(transport.sent(StreamAbort.class).isEmpty());
        assertEquals(0, session.violations());
    }

    @Test
    void uploadsAreRefusedWhenUngrantedOversizedCorruptOrTooFast() {
        handshake();
        List<byte[]> completed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        RecordingUpload handler = new RecordingUpload(completed, failed);

        receive(new StreamOpen(99, StreamKind.SCHEM_UPLOAD, 10, new TreeMap<>()));
        assertEquals(new StreamAbort(99, "not_granted"), transport.last());

        int big = dispatcher.grantUpload(session, 1, 100, handler).getAsInt();
        receive(new StreamOpen(big, StreamKind.SCHEM_UPLOAD, 101, new TreeMap<>()));
        assertEquals(new StreamAbort(big, "too_large"), transport.last());

        int corrupt = dispatcher.grantUpload(session, 2, 100, handler).getAsInt();
        receive(new StreamOpen(corrupt, StreamKind.SCHEM_UPLOAD, 3, new TreeMap<>()));
        receive(new StreamChunk(corrupt, 0, new byte[] {1, 2, 3}));
        receive(new StreamEnd(corrupt, dev.sculptory.core.Sha256.digest(new byte[] {9})));
        assertEquals(new StreamAbort(corrupt, "invalid"), transport.last());

        int fast = dispatcher.grantUpload(session, 3, 8 << 20, handler).getAsInt();
        receive(new StreamOpen(fast, StreamKind.SCHEM_UPLOAD, 2 << 20, new TreeMap<>()));
        for (int i = 0; i < 40 && !failed.contains("rate_limited"); i++) {
            receive(new StreamChunk(fast, i, new byte[StreamSender.MAX_C2S_CHUNK]));
        }
        assertEquals(new StreamAbort(fast, "rate_limited"), transport.last(), "bursting past 1 MiB/s aborts the upload");

        assertTrue(completed.isEmpty());
        assertEquals(3, failed.size());
        assertEquals("too_large", failed.get(0));
        assertTrue(failed.get(1).contains("SHA-256"));

        assertTrue(dispatcher.grantUpload(session, 4, 10, handler).isPresent());
        assertTrue(dispatcher.grantUpload(session, 5, 10, handler).isPresent());
        assertTrue(dispatcher.grantUpload(session, 6, 10, handler).isEmpty(), "at most two uploads at once");
        dispatcher.close(session);
        assertEquals(List.of("disconnected", "disconnected"), failed.subList(3, 5));
    }

    // ---------------------------------------------------------------- fakes

    private record RecordingUpload(List<byte[]> completed, List<String> failed) implements ServerDispatcher.UploadHandler {
        @Override
        public void completed(StreamOpen open, byte[] bytes) {
            completed.add(bytes);
        }

        @Override
        public void failed(String reason) {
            failed.add(reason);
        }
    }

    // =================================================================== builder mode

    private static C2S.BuilderPlace builderPlace(int seq) {
        return new C2S.BuilderPlace(seq, false, new BlockPos(1, 64, 1), Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE);
    }

    private static C2S.BuilderBreak builderBreak(int seq, int dragId, boolean last) {
        return new C2S.BuilderBreak(seq, dragId, List.of(new BlockPos(1, 64, 1)), BuilderPower.BULLDOZER.bit(),
                Symmetry.NONE, false, last);
    }

    private List<S2C.Notice> builderNotices() {
        List<S2C.Notice> notices = new ArrayList<>();
        for (S2C message : transport.sent) {
            if (message instanceof S2C.Notice notice && notice.key().startsWith("sculptory.notice.builder.")) {
                notices.add(notice);
            }
        }
        return notices;
    }

    @Test
    void builderMessagesReachTheServiceAndTheirSequencesAreAcknowledged() {
        handshake();
        receive(new C2S.BuilderPowers(BuilderPower.LONG_REACH.bit()));
        receive(builderPlace(7));
        receive(builderBreak(8, 3, false));
        receive(new C2S.BuilderDragEnd(3));
        assertEquals(List.of("powers 1", "place 7", "break 8 drag 3", "drag end 3"), edits.calls);
        assertEquals(List.of(7, 8), transport.acks, "each prediction settles once the action is done");
        assertTrue(builderNotices().isEmpty());
    }

    @Test
    void builderRefusalsAreAcknowledgedAndToldAtMostOnceASecond() {
        handshake();
        edits.builderOutcome = BuilderOutcome.refused(BuilderOutcome.Refusal.PROTECTED, "1, 64, 1");
        receive(builderPlace(1));
        receive(builderPlace(2));
        assertEquals(List.of(1, 2), transport.acks, "refused predictions settle (and revert) too");
        List<S2C.Notice> notices = builderNotices();
        assertEquals(1, notices.size(), "a held click repeats its refusal: told once");
        assertEquals("sculptory.notice.builder.protected", notices.get(0).key());
        assertEquals(List.of("1, 64, 1"), notices.get(0).args());
        clock.addAndGet(1_000_000_000L);
        receive(builderPlace(3));
        assertEquals(2, builderNotices().size(), "a second later it is told again");
        edits.builderOutcome = BuilderOutcome.refused(BuilderOutcome.Refusal.NOTHING, "");
        transport.sent.clear();
        receive(builderBreak(4, 1, true));
        assertTrue(builderNotices().isEmpty(), "sweeping air says nothing");
        edits.builderOutcome = BuilderOutcome.done(3, 1);
        receive(new C2S.BuilderPlace(5, false, new BlockPos(1, 64, 1), Facing.UP, 0.5f, 1f, 0.5f,
                BuilderPower.MIRROR.bit(), new Symmetry(Symmetry.Mode.MIRROR_XZ, 10, 10)));
        assertEquals(List.of(ServerDispatcher.NOTICE_BUILDER_COPIES_SKIPPED),
                builderNotices().stream().map(S2C.Notice::key).toList(), "a mirrored place that left out a copy");
        assertEquals(List.of(1, 2, 3, 4, 5), transport.acks);
    }

    @Test
    void builderFramesOverTheRateAreAcknowledgedWithoutReachingTheService() {
        handshake();
        int burst = (int) RateLimiter.Kind.BUILDER.burst();
        for (int i = 0; i < burst + 5; i++) receive(builderBreak(100 + i, 1, false));
        assertEquals(burst, edits.calls.size(), "the burst reaches the service");
        assertEquals(burst + 5, transport.acks.size(), "every frame's prediction settles");
        assertEquals(1, builderNotices().stream()
                .filter(n -> n.key().equals("sculptory.notice.builder.rate_limited")).count());
    }

    /** A builder frame that cannot be decoded still settles the prediction it carries up front (the client reverts it). */
    @Test
    void malformedBuilderFramesAcknowledgeTheirSequence() {
        handshake();
        // Type 45 (BuilderPlace), sequence 9 (zigzag 18), then a truncated body; type 46 (BuilderBreak), sequence 10.
        dispatcher.receive(session, new byte[] {45, 18, 127});
        dispatcher.receive(session, new byte[] {46, 20});
        assertEquals(List.of(9, 10), transport.acks);
        assertTrue(edits.calls.isEmpty(), "nothing reached the service");
        assertEquals(2, session.violations, "malformed frames count as violations");
        assertTrue(session.ready());
    }

    @Test
    void theServerOffersBuilderMode() {
        receive(Handshake.hello("test", Features.of(Features.BUILDER)));
        S2C.Welcome welcome = (S2C.Welcome) transport.sent.get(0);
        assertTrue(welcome.features().has(Features.BUILDER));
        assertTrue(ServerDispatcher.SERVER_FEATURES.has(Features.BUILDER));
    }

    private static final class FakeTransport implements ServerTransport {
        boolean canSend = true;
        int frames;
        byte[] lastFrame;
        final List<S2C> sent = new ArrayList<>();
        final List<Integer> acks = new ArrayList<>();
        final List<String> resent = new ArrayList<>();
        java.util.function.BiPredicate<Integer, Integer> tracked = (cx, cz) -> true;
        String disconnected;

        @Override
        public ServerPlayerEntity player() {
            return null;
        }

        @Override
        public boolean canSend() {
            return canSend;
        }

        @Override
        public void send(byte[] frame) {
            frames++;
            lastFrame = frame;
            try {
                sent.add(Codec.decodeS2C(frame, STATES));
            } catch (ProtocolException e) {
                throw new AssertionError("The server sent an undecodable frame", e);
            }
        }

        @Override
        public void acknowledge(int sequence) {
            acks.add(sequence);
        }

        @Override
        public boolean tracks(int cx, int cz) {
            return tracked.test(cx, cz);
        }

        @Override
        public void resendChunk(int cx, int cz) {
            resent.add(cx + "," + cz);
        }

        @Override
        public void disconnect(String reason) {
            disconnected = reason;
        }

        List<MessageType> types() {
            return sent.stream().map(Message::type).toList();
        }

        S2C last() {
            return sent.get(sent.size() - 1);
        }

        <T> T first(Class<T> type) {
            return sent(type).get(0);
        }

        <T> List<T> sent(Class<T> type) {
            return sent.stream().filter(type::isInstance).map(type::cast).toList();
        }
    }

    private static final class FakePermissions implements PermissionService {
        final Set<Perm> granted;
        int checks;

        FakePermissions(Set<Perm> granted) {
            this.granted = EnumSet.copyOf(granted);
        }

        @Override
        public boolean has(ServerPlayerEntity p, Perm node) {
            checks++;
            return granted.contains(node);
        }

        @Override
        public ChunkPermit chunk(ServerPlayerEntity p, ServerWorld w, int cx, int cz, Box bounds) {
            return ChunkPermit.ALLOW;
        }
    }

    /** Records calls and keeps each reply so tests can answer later, as the real service does off-thread. */
    private static final class FakeClipboards implements ClipboardService {
        final List<String> calls = new ArrayList<>();
        final List<Reply<?>> replies = new ArrayList<>();
        EditRejected rejectNext;
        RuntimeException failNext;
        JobTicket cutTicket;
        JobListener cutListener;
        /** The entity filter of the last copy. */
        EntityFilter copyEntities;
        Consumer<JobListener> duringCopy = l -> { };
        byte[] uploadedBytes;
        /** Uploads begun and neither completed nor aborted (what the real service holds reserved). */
        int reservedUploads;
        Region lastRegion;
        /** What a connection's uploaded selections may hold ({@code SelectionUpload.storeBytes}). */
        long selectionStoreBytes = 64L << 20;
        /** What every connection's selections may hold ({@code SelectionUpload.totalStoreBytes}). */
        long selectionStoreBytesTotal = 512L << 20;

        @SuppressWarnings("unchecked")
        <T> Reply<T> reply(int index) {
            return (Reply<T>) replies.get(index);
        }

        private void refuseIfAsked() throws EditRejected {
            EditRejected rejected = rejectNext;
            RuntimeException failure = failNext;
            rejectNext = null;
            failNext = null;
            if (rejected != null) throw rejected;
            if (failure != null) throw failure;
        }

        @Override
        public JobTicket copy(ServerPlayerEntity p, Region region, BlockPos origin, boolean cut, CellMask mask,
                              EntityFilter entities, JobListener listener, Reply<ClipboardInfo> reply)
                throws EditRejected {
            copyEntities = entities;
            calls.add("copy " + cut);
            lastRegion = region;
            refuseIfAsked();
            replies.add(reply);
            if (!cut) return null;
            cutListener = listener;
            duringCopy.accept(listener);
            return cutTicket;
        }

        @Override
        public void preview(ServerPlayerEntity p, SourceRef source, Reply<Outbound> reply) throws EditRejected {
            calls.add("preview " + source);
            refuseIfAsked();
            replies.add(reply);
        }

        @Override
        public void export(ServerPlayerEntity p, UUID clipboardId, SchematicFormat format, Reply<Outbound> reply)
                throws EditRejected {
            calls.add("export " + clipboardId + (format == SchematicFormat.SPONGE ? "" : " " + format));
            refuseIfAsked();
            replies.add(reply);
        }

        @Override
        public Upload beginUpload(ServerPlayerEntity p, String fileName, long totalBytes) throws EditRejected {
            calls.add("begin " + fileName + " " + totalBytes);
            refuseIfAsked();
            reservedUploads++;
            return new Upload() {
                boolean settled;

                @Override
                public long maxBytes() {
                    return Math.min(totalBytes, 8L << 20);
                }

                @Override
                public void completed(byte[] bytes, Reply<ClipboardInfo> reply) {
                    if (settled) return;
                    settled = true;
                    reservedUploads--;
                    calls.add("uploaded " + fileName + " " + bytes.length);
                    uploadedBytes = bytes;
                    replies.add(reply);
                }

                @Override
                public void abort() {
                    if (settled) return;
                    settled = true;
                    reservedUploads--;
                    calls.add("aborted " + fileName);
                }
            };
        }

        @Override
        public SelectionUpload beginSelectionUpload(ServerPlayerEntity p, Sha256 hash, Box bounds, long cells,
                                                    long totalBytes) throws EditRejected {
            calls.add("begin selection " + cells + " " + totalBytes);
            refuseIfAsked();
            reservedUploads++;
            return new SelectionUpload() {
                boolean settled;

                @Override
                public long maxBytes() {
                    return totalBytes;
                }

                @Override
                public long storeBytes() {
                    return selectionStoreBytes;
                }

                @Override
                public long totalStoreBytes() {
                    return selectionStoreBytesTotal;
                }

                @Override
                public void completed(byte[] bytes, Reply<CellSet> reply) {
                    if (settled) return;
                    settled = true;
                    reservedUploads--;
                    calls.add("selection uploaded " + bytes.length);
                    uploadedBytes = bytes;
                    replies.add(reply);
                }

                @Override
                public void abort() {
                    if (settled) return;
                    settled = true;
                    reservedUploads--;
                    calls.add("selection aborted");
                }
            };
        }

        @Override
        public Upload beginGeneratedUpload(ServerPlayerEntity p, Box bounds, long cells, long totalBytes)
                throws EditRejected {
            calls.add("begin generated " + cells + " " + totalBytes);
            refuseIfAsked();
            reservedUploads++;
            return new Upload() {
                boolean settled;

                @Override
                public long maxBytes() {
                    return totalBytes;
                }

                @Override
                public void completed(byte[] bytes, Reply<ClipboardInfo> reply) {
                    if (settled) return;
                    settled = true;
                    reservedUploads--;
                    calls.add("generated uploaded " + bytes.length);
                    uploadedBytes = bytes;
                    replies.add(reply);
                }

                @Override
                public void abort() {
                    if (settled) return;
                    settled = true;
                    reservedUploads--;
                    calls.add("generated aborted");
                }
            };
        }

        @Override
        public void list(ServerPlayerEntity p, String folder, Reply<Listing> reply) throws EditRejected {
            calls.add("list " + folder);
            refuseIfAsked();
            replies.add(reply);
        }

        @Override
        public void load(ServerPlayerEntity p, String path, Reply<ClipboardInfo> reply) throws EditRejected {
            calls.add("load " + path);
            refuseIfAsked();
            replies.add(reply);
        }

        @Override
        public void save(ServerPlayerEntity p, UUID clipboardId, String path, Reply<Saved> reply) throws EditRejected {
            calls.add("save " + clipboardId + " " + path);
            refuseIfAsked();
            replies.add(reply);
        }

        /** What {@link #shownTo} answers, one per call in order (the sessions are asked in the order they opened). */
        final java.util.ArrayDeque<java.util.function.UnaryOperator<LibraryChange>> shown = new java.util.ArrayDeque<>();
        int shownCalls;

        @Override
        public void move(ServerPlayerEntity p, boolean folder, String from, String to, Reply<LibraryChange> reply)
                throws EditRejected {
            calls.add("move " + folder + " " + from + " " + to);
            refuseIfAsked();
            replies.add(reply);
        }

        @Override
        public void delete(ServerPlayerEntity p, boolean folder, String path, Reply<LibraryChange> reply)
                throws EditRejected {
            calls.add("delete " + folder + " " + path);
            refuseIfAsked();
            replies.add(reply);
        }

        @Override
        public void createFolder(ServerPlayerEntity p, String path, Reply<LibraryChange> reply) throws EditRejected {
            calls.add("folder " + path);
            refuseIfAsked();
            replies.add(reply);
        }

        @Override
        public java.util.Optional<LibraryChange> shownTo(ServerPlayerEntity viewer, LibraryChange change) {
            shownCalls++;
            java.util.function.UnaryOperator<LibraryChange> answer = shown.poll();
            return java.util.Optional.ofNullable(answer == null ? null : answer.apply(change));
        }

        @Override
        public void savePalette(ServerPlayerEntity p, String path, BlockPalette palette, Reply<LibraryChange> reply)
                throws EditRejected {
            calls.add("save palette " + path + " " + palette.size());
            refuseIfAsked();
            replies.add(reply);
        }

        @Override
        public void loadPalette(ServerPlayerEntity p, String path, Reply<LoadedPalette> reply) throws EditRejected {
            calls.add("load palette " + path);
            refuseIfAsked();
            replies.add(reply);
        }

        /** What {@link #shownAccessChange} answers, one per call in order. */
        final java.util.ArrayDeque<java.util.function.Function<AccessChange, LibraryChange>> shownAccess = new java.util.ArrayDeque<>();
        int shownAccessCalls;

        @Override
        public void access(ServerPlayerEntity p, String path, Reply<AssetAccess> reply) throws EditRejected {
            calls.add("access " + path);
            refuseIfAsked();
            replies.add(reply);
        }

        @Override
        public void setAccess(ServerPlayerEntity p, String path, AssetAccess access, Reply<AccessChange> reply)
                throws EditRejected {
            calls.add("set access " + path + " " + access.mode() + " " + access.players().size());
            refuseIfAsked();
            replies.add(reply);
        }

        @Override
        public java.util.Optional<LibraryChange> shownAccessChange(ServerPlayerEntity viewer, AccessChange change) {
            shownAccessCalls++;
            java.util.function.Function<AccessChange, LibraryChange> answer = shownAccess.poll();
            return java.util.Optional.ofNullable(answer == null ? null : answer.apply(change));
        }
    }

    /** Records previews and keeps each reply so tests can answer later, as the real service does on a later tick. */
    private static final class FakeScatter implements ScatterService {
        final List<String> calls = new ArrayList<>();
        final List<PreviewReply> replies = new ArrayList<>();
        EditRejected rejectNext;
        RuntimeException failNext;

        @Override
        public void preview(ServerPlayerEntity p, C2S.ScatterPreview request, PreviewReply reply) throws EditRejected {
            calls.add("preview " + request.reqId());
            EditRejected rejected = rejectNext;
            RuntimeException failure = failNext;
            rejectNext = null;
            failNext = null;
            if (rejected != null) throw rejected;
            if (failure != null) throw failure;
            replies.add(reply);
        }
    }

    private static final class FakeEdits implements EditService, HistoryView {
        final List<String> calls = new ArrayList<>();
        EditRejected rejectNext;
        RuntimeException failNext;
        JobListener lastListener;
        RunOptions lastOptions;
        Consumer<JobListener> duringRun = l -> { };
        Runnable duringHistory = () -> { };
        DabOutcome dabOutcome;
        BrushSpec lastSpec;
        OpSpec lastOp;
        S2C.HistoryState historyState = new S2C.HistoryState(true, false, "Fill", "", 100);
        int historyQueries;
        int predictions;

        private void refuseIfAsked() throws EditRejected {
            EditRejected rejected = rejectNext;
            RuntimeException failure = failNext;
            rejectNext = null;
            failNext = null;
            if (rejected != null) throw rejected;
            if (failure != null) throw failure;
        }

        @Override
        public JobTicket run(ServerPlayerEntity p, OpSpec s, RunOptions o, JobListener l) throws EditRejected {
            calls.add("run " + s.getClass().getSimpleName());
            lastOp = s;
            refuseIfAsked();
            lastListener = l;
            lastOptions = o;
            duringRun.accept(l);
            return new JobTicket(JOB, "Fill", 64);
        }

        @Override
        public void beginStroke(ServerPlayerEntity p, int strokeId, BrushSpec spec) throws EditRejected {
            calls.add("begin " + strokeId);
            lastSpec = spec;
            refuseIfAsked();
        }

        @Override
        public DabOutcome dabs(ServerPlayerEntity p, int strokeId, int seq, List<Dab> dabs) {
            calls.add("dabs " + strokeId + " " + seq + " " + dabs.size());
            return dabOutcome != null ? dabOutcome : DabOutcome.accepted(dabs.get(dabs.size() - 1).index());
        }

        @Override
        public void predicted(ServerPlayerEntity p) {
            predictions++;
        }

        @Override
        public void endStroke(ServerPlayerEntity p, int strokeId) {
            calls.add("end " + strokeId);
        }

        @Override
        public JobTicket undo(ServerPlayerEntity p, ConflictPolicy c) throws EditRejected {
            calls.add("undo " + c);
            duringHistory.run();
            refuseIfAsked();
            return new JobTicket(JOB, "Undo", 10);
        }

        @Override
        public JobTicket redo(ServerPlayerEntity p, ConflictPolicy c) throws EditRejected {
            calls.add("redo " + c);
            duringHistory.run();
            refuseIfAsked();
            return new JobTicket(JOB, "Redo", 10);
        }

        @Override
        public JobTicket historyOverwrite(ServerPlayerEntity p, boolean redo, int steps) throws EditRejected {
            calls.add((redo ? "redo" : "undo") + " anyway " + steps);
            duringHistory.run();
            refuseIfAsked();
            return new JobTicket(JOB, "Undo anyway", 12);
        }

        BuilderOutcome builderOutcome = BuilderOutcome.done(1, 0);

        @Override
        public void builderPowers(ServerPlayerEntity p, int powers) {
            calls.add("powers " + powers);
        }

        @Override
        public BuilderOutcome builderPlace(ServerPlayerEntity p, C2S.BuilderPlace place) {
            calls.add("place " + place.seq());
            return builderOutcome;
        }

        @Override
        public BuilderOutcome builderBreak(ServerPlayerEntity p, C2S.BuilderBreak breaks) {
            calls.add("break " + breaks.seq() + " drag " + breaks.dragId() + (breaks.last() ? " last" : ""));
            return builderOutcome;
        }

        @Override
        public void builderDragEnd(ServerPlayerEntity p, int dragId) {
            calls.add("drag end " + dragId);
        }

        @Override
        public boolean cancel(ServerPlayerEntity p, UUID jobId) {
            calls.add("cancel " + jobId);
            return true;
        }

        @Override
        public S2C.HistoryState historyState(ServerPlayerEntity player) {
            historyQueries++;
            return historyState;
        }
    }

    /** The fake state space, counting block-state parses (i.e. palette decoding). */
    private static final class CountingStates implements StateSpace {
        int parses;

        @Override
        public int size() {
            return STATES.size();
        }

        @Override
        public int air() {
            return STATES.air();
        }

        @Override
        public int flags(int h) {
            return STATES.flags(h);
        }

        @Override
        public String format(int h) {
            return STATES.format(h);
        }

        @Override
        public int parse(String spec) {
            parses++;
            return STATES.parse(spec);
        }

        @Override
        public BlockDescriptor describe(int h) {
            return STATES.describe(h);
        }

        @Override
        public int resolve(BlockDescriptor d) {
            return STATES.resolve(d);
        }

        @Override
        public NamespacedId blockId(int h) {
            return STATES.blockId(h);
        }

        @Override
        public boolean inTag(int h, NamespacedId tag) {
            return STATES.inTag(h, tag);
        }

        @Override
        public int rotate(int h, int turns) {
            return STATES.rotate(h, turns);
        }

        @Override
        public int mirror(int h, Mirror m) {
            return STATES.mirror(h, m);
        }

        @Override
        public int withWaterlogged(int h, boolean on) {
            return STATES.withWaterlogged(h, on);
        }

        @Override
        public int fluidSource(int h) {
            return STATES.fluidSource(h);
        }
    }
}
