package dev.sculptory.fabric.client.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
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
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.fabric.client.editor.EditorMode;
import dev.sculptory.fabric.client.editor.EditorState;
import dev.sculptory.fabric.client.editor.tool.DeactivateReason;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.fabric.net.ServerDispatcher;
import dev.sculptory.protocol.v2.BuilderPower;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.Codec;
import dev.sculptory.protocol.v2.Features;
import dev.sculptory.protocol.v2.Handshake;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.MessageType;
import dev.sculptory.protocol.v2.PermissionMask;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.protocol.v2.ProtocolException;
import dev.sculptory.protocol.v2.ProtocolV2;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.protocol.v2.StreamCredit;
import dev.sculptory.protocol.v2.StreamKind;
import dev.sculptory.protocol.v2.StreamSender;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FabricEditorSessionTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final long MS = 1_000_000L;
    private static final Box BOX = Box.of(new BlockPos(0, 60, 0), new BlockPos(9, 70, 9));
    private static final BrushSpec SPEC = new BrushSpec(BrushTool.RAISE, 4, 1f, Falloff.SMOOTH, Shape.CIRCLE, null,
            SurfaceMask.ANY, 0, 0, 1L);
    private static final UUID JOB = new UUID(3, 3);

    private FakeTransport transport;
    private AtomicLong clock;
    private FabricEditorSession session;
    private final List<Notice> notices = new ArrayList<>();

    @BeforeEach
    void setUp() {
        transport = new FakeTransport();
        clock = new AtomicLong(10_000 * MS);
        session = new FabricEditorSession(transport, () -> STATES, clock::get, "0.2.0-test");
        session.onNotice(notices::add);
    }

    private void server(S2C message) {
        try {
            session.onFrame(Codec.encodeS2C(message, STATES));
        } catch (ProtocolException e) {
            throw new AssertionError(e);
        }
    }

    private void connect() {
        session.onJoin();
        server(new S2C.Welcome(ProtocolV2.VERSION, Features.of(Features.STROKES), Limits.DEFAULTS,
                Perm.mask(EnumSet.of(Perm.USE, Perm.BRUSH)), 99L));
        assertEquals(SessionState.READY, session.state());
        transport.sent.clear();
    }

    private void tick(long millis) {
        clock.addAndGet(millis * MS);
        session.tick();
    }

    private static Dab dab(int index) {
        return new Dab(index, index * 16, 1024, 0, 255);
    }

    private static OpSpec fill() {
        return new OpSpec.Fill(BOX, new Pattern.Single(STATES.state("minecraft:dirt")), CellMask.ANY);
    }

    // ---------------------------------------------------------------- handshake

    @Test
    void noChannelMeansNoServerSupportAndNothingSent() {
        transport.canSend = false;
        session.onJoin();
        assertEquals(SessionState.NO_SERVER_SUPPORT, session.state());
        assertTrue(transport.sent.isEmpty());
        ToolResult result = session.send(new ToolAction.RunOp(fill())).toCompletableFuture().join();
        assertEquals(RejectReason.DISABLED, assertInstanceOf(ToolResult.Rejected.class, result).reason());
    }

    @Test
    void helloWelcomeMakesTheSessionReady() {
        session.onJoin();
        assertEquals(SessionState.HANDSHAKING, session.state());
        C2S.Hello hello = assertInstanceOf(C2S.Hello.class, transport.sent.get(0));
        assertEquals(Handshake.MIN_PROTOCOL, hello.minProtocol());
        assertEquals("0.2.0-test", hello.modVersion());
        assertEquals(FabricEditorSession.CLIENT_FEATURES, hello.features());
        server(new S2C.Welcome(ProtocolV2.VERSION, Features.of(Features.STROKES), Limits.DEFAULTS,
                Perm.mask(EnumSet.of(Perm.USE, Perm.BRUSH)), 99L));
        assertEquals(SessionState.READY, session.state());
        assertEquals(new Capabilities(ProtocolV2.VERSION, Features.of(Features.STROKES), 99L), session.capabilities());
        assertTrue(session.permissions().has(Perm.BRUSH));
        assertFalse(session.permissions().has(Perm.REGION));

        server(new S2C.PermissionsChanged(Perm.mask(EnumSet.of(Perm.USE, Perm.REGION)), new Limits(1, 1, 8, 4, 1, 1)));
        assertTrue(session.permissions().has(Perm.REGION));
        assertEquals(8, session.permissions().limits().maxBrushRadius());
    }

    @Test
    void handshakeTimesOutAsNoServerSupportButALateWelcomeStillConnects() {
        session.onJoin();
        tick(4_900);
        assertEquals(SessionState.HANDSHAKING, session.state());
        tick(200);
        assertEquals(SessionState.NO_SERVER_SUPPORT, session.state());
        server(new S2C.Welcome(ProtocolV2.VERSION, Features.NONE, Limits.DEFAULTS, Perm.mask(EnumSet.noneOf(Perm.class)), 1L));
        assertEquals(SessionState.READY, session.state(), "our Hello went out, so a slow server still counts");
    }

    @Test
    void handshakeAnswersWithoutAHelloOnThisConnectionAreIgnored() {
        transport.canSend = false;
        session.onJoin();
        server(new S2C.Welcome(ProtocolV2.VERSION, Features.NONE, Limits.DEFAULTS, Perm.mask(EnumSet.noneOf(Perm.class)), 1L));
        assertEquals(SessionState.NO_SERVER_SUPPORT, session.state());
        server(new S2C.Incompatible(1, 1));
        assertEquals(SessionState.NO_SERVER_SUPPORT, session.state());
    }

    @Test
    void incompatibleServersAreReported() {
        session.onJoin();
        server(new S2C.Incompatible(7, 9));
        assertEquals(SessionState.INCOMPATIBLE, session.state());
        assertEquals("sculptory.notice.incompatible", notices.get(0).key());
        String ours = FabricEditorSession.protocols(Handshake.MIN_PROTOCOL, Handshake.MAX_PROTOCOL);
        assertEquals(List.of("7-9", ours), notices.get(0).args());

        session.onJoin();
        server(new S2C.Welcome(ProtocolV2.VERSION + 5, Features.NONE, Limits.DEFAULTS, Perm.mask(EnumSet.noneOf(Perm.class)), 1L));
        assertEquals(SessionState.INCOMPATIBLE, session.state(), "a Welcome with a protocol we don't speak");
    }

    /** A protocol refusal names both builds when the server sent its own, so an admin can tell which jar is where. */
    @Test
    void incompatibleServersWithABuildNameBothBuilds() {
        session.onJoin();
        server(new S2C.Incompatible(7, 7, "0.3.0+1234abcd"));
        assertEquals(SessionState.INCOMPATIBLE, session.state());
        assertEquals(1, notices.size());
        assertEquals(Notice.Level.ERROR, notices.get(0).level());
        assertEquals("sculptory.notice.incompatible_build", notices.get(0).key());
        assertEquals(List.of("0.3.0+1234abcd", "7", "0.2.0-test",
                FabricEditorSession.protocols(Handshake.MIN_PROTOCOL, Handshake.MAX_PROTOCOL)), notices.get(0).args());
        assertEquals("0.3.0+1234abcd", session.serverBuild());
        ToolResult result = session.send(new ToolAction.RunOp(fill())).toCompletableFuture().join();
        assertEquals(RejectReason.DISABLED, assertInstanceOf(ToolResult.Rejected.class, result).reason(),
                "a protocol mismatch stays a refusal");
    }

    /**
     * An old v4 server (before build ids) answers this client's Hello with Incompatible(4, 4) and nothing after it
     * (65 | 8 | 8): it decodes, and the refusal names both protocols.
     */
    @Test
    void anOldV4ServersRefusalIsReadable() {
        session.onJoin();
        session.onFrame(new byte[] {(byte) MessageType.INCOMPATIBLE.code(), 8, 8});
        assertEquals(SessionState.INCOMPATIBLE, session.state());
        assertEquals("sculptory.notice.incompatible", notices.get(0).key());
        assertEquals(List.of("4", FabricEditorSession.protocols(Handshake.MIN_PROTOCOL, Handshake.MAX_PROTOCOL)),
                notices.get(0).args());
        assertEquals("", session.serverBuild());
    }

    /** The server's build id is cleaned before it is stored, logged or shown. */
    @Test
    void theServersBuildIdIsCleanedOnArrival() {
        session.onJoin();
        server(new S2C.Welcome(ProtocolV2.VERSION, Features.NONE, Limits.DEFAULTS, Perm.mask(EnumSet.of(Perm.USE)), 3L,
                "0.3§c\n<i>"));
        assertEquals("0.3?c??i?", session.serverBuild());
        assertEquals(List.of("0.3?c??i?", "0.2.0-test"), notices.get(0).args());
        session.onJoin();
        server(new S2C.Incompatible(9, 9, "9§x"));
        assertEquals("9?x", session.serverBuild());
    }

    /** Same protocol, another build: editing works, with one warning naming both builds. */
    @Test
    void aDifferentBuildOfTheSameProtocolIsANoticeNotARefusal() {
        session.onJoin();
        server(new S2C.Welcome(ProtocolV2.VERSION, Features.of(Features.STROKES), Limits.DEFAULTS,
                Perm.mask(EnumSet.of(Perm.USE, Perm.BRUSH)), 5L, "0.2.0-dev+6a043a85"));
        assertEquals(SessionState.READY, session.state());
        assertEquals(1, notices.size());
        assertEquals(Notice.Level.WARNING, notices.get(0).level());
        assertEquals("sculptory.notice.different_build", notices.get(0).key());
        assertEquals(List.of("0.2.0-dev+6a043a85", "0.2.0-test"), notices.get(0).args());
        assertEquals("0.2.0-dev+6a043a85", session.serverBuild());
        assertEquals("0.2.0-test", session.clientBuild());

        // The same build, or a server that sends none (or doesn't know its own), says nothing.
        for (String build : List.of("0.2.0-test", "", "unknown")) {
            notices.clear();
            session.onJoin();
            server(new S2C.Welcome(ProtocolV2.VERSION, Features.NONE, Limits.DEFAULTS, Perm.mask(EnumSet.of(Perm.USE)), 6L,
                    build));
            assertEquals(SessionState.READY, session.state());
            assertTrue(notices.isEmpty(), "build " + build + ": " + notices);
        }
        session.onDisconnect();
        assertEquals("", session.serverBuild(), "forgotten with the connection");
    }

    @Test
    void malformedServerFramesAreIgnored() {
        connect();
        session.onFrame(new byte[] {(byte) 0xFF, 1, 2});
        session.onFrame(new byte[] {1});
        assertEquals(SessionState.READY, session.state());
    }

    // ---------------------------------------------------------------- jobs

    @Test
    void runOpCompletesOnJobAcceptedAndTheTrackerFollowsTheJob() {
        connect();
        CompletionStage<ToolResult> stage = session.send(new ToolAction.RunOp(fill(), true));
        C2S.RunOp sent = assertInstanceOf(C2S.RunOp.class, transport.sent.get(0));
        assertTrue(sent.physics());
        assertEquals(fill(), sent.op());
        assertFalse(stage.toCompletableFuture().isDone());

        server(new S2C.JobAccepted(sent.reqId(), JOB, 100));
        assertEquals(new ToolResult.Accepted(JOB, 100), stage.toCompletableFuture().join());
        JobTracker.Job job = session.jobs().job(JOB).orElseThrow();
        assertEquals("Fill", job.label());
        assertEquals(Phase.QUEUED, job.phase());

        int[] changes = {0};
        session.jobs().onChange(() -> changes[0]++);
        server(new S2C.JobProgress(JOB, 50, 100, Phase.APPLY));
        assertEquals(50, session.jobs().job(JOB).orElseThrow().done());
        server(new S2C.JobFinished(JOB, JobOutcome.COMPLETED, 90, 7, 3, 0));
        JobTracker.Job done = session.jobs().job(JOB).orElseThrow();
        assertEquals(JobOutcome.COMPLETED, done.outcome());
        assertEquals(100, done.done());
        assertEquals(90, done.changed());
        assertEquals(7, done.skippedProtected());
        assertEquals(3, done.skippedConflicts());
        assertEquals(10, done.skipped());
        assertEquals(2, changes[0]);
        assertEquals(List.of(Notice.of(Notice.Level.SUCCESS, "sculptory.notice.job_finished", "Fill", "90", "10")), notices);
        assertEquals(7, session.jobResult(JOB).orElseThrow().skippedProtected());

        tick(9_000);
        assertTrue(session.jobs().job(JOB).isPresent(), "finished jobs stay visible for a while");
        tick(2_000);
        assertTrue(session.jobs().jobs().isEmpty());
    }

    @Test
    void jobEventsBeforeTheAcceptAreMerged() {
        connect();
        CompletionStage<ToolResult> stage = session.send(new ToolAction.RunOp(fill()));
        int reqId = ((C2S.RunOp) transport.sent.get(0)).reqId();
        server(new S2C.JobProgress(JOB, 5, 10, Phase.APPLY));
        server(new S2C.JobFinished(JOB, JobOutcome.COMPLETED, 1_234, 0, 0, 0));
        assertTrue(notices.isEmpty(), "the result waits for the accept, which carries the label");
        server(new S2C.JobAccepted(reqId, JOB, 10));
        assertTrue(stage.toCompletableFuture().isDone());
        JobTracker.Job job = session.jobs().job(JOB).orElseThrow();
        assertEquals("Fill", job.label());
        assertTrue(job.finished());
        assertEquals(1_234, job.changed(), "the accept keeps the result counts");
        assertEquals(List.of(Notice.of(Notice.Level.SUCCESS, "sculptory.notice.job_finished", "Fill", "1,234", "0")), notices);
    }

    @Test
    void cancelledAndFailedJobsReportTheBlocksAlreadyChanged() {
        connect();
        session.send(new ToolAction.RunOp(fill()));
        server(new S2C.JobAccepted(((C2S.RunOp) transport.last()).reqId(), JOB, 5_000));
        server(new S2C.JobFinished(JOB, JobOutcome.CANCELLED, 2_500, 0, 0, 0));
        UUID failed = new UUID(4, 4);
        session.send(new ToolAction.RunOp(fill()));
        server(new S2C.JobAccepted(((C2S.RunOp) transport.last()).reqId(), failed, 5_000));
        server(new S2C.JobFinished(failed, JobOutcome.FAILED, 12, 0, 0, 0));
        assertEquals(List.of(
                Notice.of(Notice.Level.WARNING, "sculptory.notice.job_cancelled", "Fill", "2,500"),
                Notice.of(Notice.Level.ERROR, "sculptory.notice.job_failed", "Fill", "12")), notices);
    }

    @Test
    void strippedBlockEntityDataGetsItsOwnNotice() {
        connect();
        session.send(new ToolAction.RunOp(fill()));
        server(new S2C.JobAccepted(((C2S.RunOp) transport.last()).reqId(), JOB, 100));
        server(new S2C.JobFinished(JOB, JobOutcome.COMPLETED, 100, 0, 0, 2));
        assertEquals(List.of(
                Notice.of(Notice.Level.SUCCESS, "sculptory.notice.job_finished", "Fill", "100", "0"),
                Notice.of(Notice.Level.WARNING, "sculptory.notice.job_stripped_nbt", "Fill", "2")), notices);
        assertEquals(2, session.jobs().job(JOB).orElseThrow().strippedNbt());
    }

    @Test
    void jobsThisClientDidNotStartAreTrackedButNotAnnounced() {
        connect();
        server(new S2C.JobProgress(JOB, 1, 2, Phase.APPLY));
        server(new S2C.JobFinished(JOB, JobOutcome.FAILED, 1, 0, 0, 0));
        assertTrue(session.jobs().job(JOB).orElseThrow().finished());
        assertTrue(notices.isEmpty());
    }

    /** The server's notice that request {@code reqId} is refused only for the player's stroke, then the refusal. */
    private void strokePending(int reqId) {
        server(new S2C.Notice(S2C.Notice.Level.INFO, SessionNotices.STROKE_PENDING, List.of(Integer.toString(reqId))));
        server(new S2C.JobRejected(reqId, RejectReason.QUEUE_FULL));
    }

    /**
     * An op refused only because the player's brush stroke is still being applied goes out again 250 ms later, quietly;
     * an undo pressed meanwhile waits behind it and a stroke is refused with a toast, so the server sees them in the
     * order the player made them.
     */
    @Test
    void anOpRefusedForTheStrokeIsSentAgainInItsPlace() {
        connect();
        CompletionStage<ToolResult> stage = session.send(new ToolAction.RunOp(fill()));
        strokePending(((C2S.RunOp) transport.last()).reqId());
        assertFalse(stage.toCompletableFuture().isDone(), "the refusal is not the op's answer");
        assertTrue(notices.isEmpty());
        session.undo();
        assertTrue(session.historyBusy());
        StrokeHandle stroke = session.beginStroke(ToolId.RAISE, SPEC, StrokeParams.DEFAULT);
        assertFalse(stroke.active(), "a stroke would overtake the op");
        assertEquals(List.of(Notice.of(Notice.Level.INFO, FabricEditorSession.STROKE_WAITS_FOR_STROKE)), notices);
        notices.clear();
        tick(100);
        assertEquals(1, sent(C2S.RunOp.class));
        assertEquals(0, sent(C2S.Undo.class));
        tick(200);
        List<C2S> order = transport.sent.stream().filter(m -> m instanceof C2S.RunOp || m instanceof C2S.Undo).toList();
        assertEquals(3, order.size(), order.toString());
        C2S.RunOp again = assertInstanceOf(C2S.RunOp.class, order.get(1));
        assertEquals(fill(), again.op());
        assertInstanceOf(C2S.Undo.class, order.get(2));
        server(new S2C.JobAccepted(again.reqId(), JOB, 100));
        assertEquals(new ToolResult.Accepted(JOB, 100), stage.toCompletableFuture().join());
        assertTrue(notices.isEmpty(), "nothing was shown: " + notices);
    }

    /** An op still refused for the stroke ten seconds after it first went out is refused after all. */
    @Test
    void anOpStillRefusedForTheStrokeAfterTenSecondsIsRefused() {
        connect();
        CompletionStage<ToolResult> stage = session.send(new ToolAction.RunOp(fill()));
        for (int i = 0; i < 60 && !stage.toCompletableFuture().isDone(); i++) {
            strokePending(((C2S.RunOp) transport.last()).reqId());
            tick(300);
        }
        assertEquals(RejectReason.QUEUE_FULL, ((ToolResult.Rejected) stage.toCompletableFuture().join()).reason());
        long sends = sent(C2S.RunOp.class);
        assertTrue(sends >= 30 && sends <= 45, "sent " + sends + " times in about ten seconds");
        assertFalse(session.historyBusy());
    }

    /** An undo refused for the stroke goes out again quietly, and its toast comes when it is accepted. */
    @Test
    void anUndoRefusedForTheStrokeIsSentAgain() {
        connect();
        history(List.of("Shape · 274,625 blocks"), 0);
        session.undo();
        strokePending(((C2S.Undo) transport.last()).reqId());
        assertTrue(notices.isEmpty(), "no toast for the wait");
        assertTrue(session.historyBusy());
        session.send(new ToolAction.RunOp(fill()));
        tick(100);
        assertEquals(1, sent(C2S.Undo.class));
        assertEquals(0, sent(C2S.RunOp.class), "the op waits behind the undo");
        tick(200);
        List<C2S> order = transport.sent.stream().filter(m -> m instanceof C2S.RunOp || m instanceof C2S.Undo).toList();
        assertEquals(3, order.size(), order.toString());
        C2S.Undo again = assertInstanceOf(C2S.Undo.class, order.get(1));
        assertInstanceOf(C2S.RunOp.class, order.get(2));
        server(new S2C.JobAccepted(again.reqId(), JOB, 5));
        assertEquals(List.of(Notice.of(Notice.Level.INFO, "sculptory.notice.undo", "Shape · 274,625 blocks")), notices);
    }

    @Test
    void runOpRefusalsAreLeftToTheCaller() {
        connect();
        CompletionStage<ToolResult> stage = session.send(new ToolAction.RunOp(fill()));
        server(new S2C.JobRejected(((C2S.RunOp) transport.last()).reqId(), RejectReason.TOO_LARGE));
        assertEquals(RejectReason.TOO_LARGE, ((ToolResult.Rejected) stage.toCompletableFuture().join()).reason());
        assertTrue(notices.isEmpty(), "the caller shows it (SessionNotices.rejection), so it is not toasted twice");
    }

    @Test
    void rejectionsCancelsAndRequestsBeforeReady() {
        CompletionStage<ToolResult> early = session.send(new ToolAction.RunOp(fill()));
        assertEquals(RejectReason.DISABLED, ((ToolResult.Rejected) early.toCompletableFuture().join()).reason());
        connect();
        CompletionStage<ToolResult> stage = session.send(new ToolAction.RunOp(fill()));
        server(new S2C.JobRejected(((C2S.RunOp) transport.sent.get(0)).reqId(), RejectReason.AREA_BUSY));
        assertEquals(new ToolResult.Rejected(RejectReason.AREA_BUSY, ""), stage.toCompletableFuture().join());
        assertTrue(session.jobs().jobs().isEmpty());

        ToolResult cancelled = session.send(new ToolAction.Cancel(JOB)).toCompletableFuture().join();
        assertInstanceOf(ToolResult.Done.class, cancelled);
        assertEquals(new C2S.CancelJob(JOB), transport.sent.get(1));
    }

    @Test
    void withoutAStateSpaceTheSessionNeverBecomesReady() {
        FabricEditorSession noStates = new FabricEditorSession(transport, () -> null, clock::get, "t");
        List<Notice> seen = new ArrayList<>();
        noStates.onNotice(seen::add);
        noStates.onJoin();
        assertEquals(SessionState.INCOMPATIBLE, noStates.state());
        assertTrue(transport.sent.isEmpty(), "no Hello");
        assertEquals(List.of(Notice.of(Notice.Level.ERROR, "sculptory.notice.no_block_states")), seen);
        noStates.onFrame(encode(new S2C.Welcome(ProtocolV2.VERSION, Features.NONE, Limits.DEFAULTS,
                Perm.mask(EnumSet.of(Perm.USE)), 1L)));
        assertEquals(SessionState.INCOMPATIBLE, noStates.state(), "a Welcome without our Hello is ignored");
    }

    @Test
    void unencodableRequestsFailLocally() {
        AtomicReference<StateSpace> space = new AtomicReference<>(STATES);
        FabricEditorSession lost = new FabricEditorSession(transport, space::get, clock::get, "t");
        lost.onJoin();
        lost.onFrame(encode(new S2C.Welcome(ProtocolV2.VERSION, Features.NONE, Limits.DEFAULTS,
                Perm.mask(EnumSet.noneOf(Perm.class)), 1L)));
        assertEquals(SessionState.READY, lost.state());
        space.set(null);
        ToolResult result = lost.send(new ToolAction.RunOp(fill())).toCompletableFuture().join();
        assertEquals(RejectReason.INVALID, ((ToolResult.Rejected) result).reason());
    }

    // ---------------------------------------------------------------- strokes

    @Test
    void dabsAreBatchedOncePerTickThroughThePredictionHook() {
        connect();
        FabricStrokeHandle stroke = (FabricStrokeHandle) session.beginStroke(ToolId.RAISE, SPEC, StrokeParams.DEFAULT);
        assertEquals(new C2S.StrokeBegin(stroke.strokeId(), SPEC), transport.sent.get(0));
        List<List<Dab>> predicted = new ArrayList<>();
        stroke.setBatchHook((s, batch) -> {
            predicted.add(batch);
            return 77;
        });
        stroke.dab(dab(0));
        stroke.dab(dab(1));
        stroke.dab(dab(2));
        assertEquals(1, transport.sent.size(), "nothing is sent between ticks");
        tick(50);
        C2S.Dabs dabs = assertInstanceOf(C2S.Dabs.class, transport.sent.get(1));
        assertEquals(77, dabs.seq());
        assertEquals(List.of(dab(0), dab(1), dab(2)), dabs.dabs());
        assertEquals(List.of(dabs.dabs()), predicted);
        tick(50);
        assertEquals(2, transport.sent.size(), "an empty tick sends nothing");
    }

    @Test
    void backpressureKeepsOnlyTheLatestPositionBeyondSixteenUnacked() {
        connect();
        FabricStrokeHandle stroke = (FabricStrokeHandle) session.beginStroke(ToolId.RAISE, SPEC, StrokeParams.DEFAULT);
        for (int i = 0; i < 20; i++) stroke.dab(dab(i));
        assertEquals(16, stroke.queued(), "the queue never exceeds the window");
        tick(50);
        C2S.Dabs first = (C2S.Dabs) transport.sent.get(1);
        assertEquals(16, first.dabs().size());
        assertEquals(19, first.dabs().get(15).index(), "the newest dab replaced the ones beyond the window");
        assertEquals(16, stroke.unacknowledged());

        stroke.dab(dab(20));
        stroke.dab(dab(21));
        stroke.dab(dab(22));
        assertEquals(1, stroke.queued());
        tick(50);
        assertEquals(2, transport.sent.size(), "blocked with 16 unacknowledged");

        server(new S2C.StrokeStatus(stroke.strokeId(), 7, S2C.StrokeStatus.Status.OK, null));
        assertEquals(8, stroke.unacknowledged());
        assertEquals(7, stroke.ackedIndex());
        tick(50);
        C2S.Dabs resumed = (C2S.Dabs) transport.sent.get(2);
        assertEquals(List.of(dab(22)), resumed.dabs());
        assertEquals(9, stroke.unacknowledged());
    }

    @Test
    void rejectionStopsTheStrokeButEndStillCloses() {
        connect();
        FabricStrokeHandle stroke = (FabricStrokeHandle) session.beginStroke(ToolId.RAISE, SPEC, StrokeParams.DEFAULT);
        stroke.dab(dab(0));
        tick(50);
        server(new S2C.StrokeStatus(stroke.strokeId(), 0, S2C.StrokeStatus.Status.REJECTED, RejectReason.PROTECTED));
        assertFalse(stroke.active());
        assertEquals(RejectReason.PROTECTED, stroke.rejectReason());
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.reject.protected")), notices);
        stroke.dab(dab(1));
        tick(50);
        assertEquals(2, transport.sent.size(), "no dabs after a rejection");
        stroke.end();
        assertEquals(new C2S.StrokeEnd(stroke.strokeId()), transport.sent.get(2));
        stroke.end();
        assertEquals(3, transport.sent.size(), "end is sent once");
    }

    @Test
    void aRefusalAfterTheReleaseIsStillToastedOnceWithItsReason() {
        connect();
        FabricStrokeHandle stroke = (FabricStrokeHandle) session.beginStroke(ToolId.RAISE, SPEC, StrokeParams.DEFAULT);
        stroke.dab(dab(0));
        tick(50);
        stroke.end();
        // The brush lane can refuse an admitted dab after the release; that REJECTED comes before the ENDED.
        server(new S2C.StrokeStatus(stroke.strokeId(), 0, S2C.StrokeStatus.Status.REJECTED, RejectReason.UNLOADED));
        server(new S2C.StrokeStatus(stroke.strokeId(), 0, S2C.StrokeStatus.Status.REJECTED, RejectReason.UNLOADED));
        server(new S2C.StrokeStatus(stroke.strokeId(), 0, S2C.StrokeStatus.Status.ENDED, null));
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.reject.unloaded")), notices,
                "one toast per stroke, naming the real reason");
        assertTrue(stroke.ended());
    }

    @Test
    void aStrokeIsPendingUntilTheServerHasEndedIt() {
        connect();
        assertFalse(session.strokesPending());
        FabricStrokeHandle stroke = (FabricStrokeHandle) session.beginStroke(ToolId.RAISE, SPEC, StrokeParams.DEFAULT);
        stroke.dab(dab(0));
        tick(50);
        assertTrue(session.strokesPending(), "pressed");
        stroke.end();
        assertTrue(session.strokesPending(), "let go, but its history entry comes with the server's end");
        server(new S2C.StrokeStatus(stroke.strokeId(), 0, S2C.StrokeStatus.Status.ENDED, null));
        assertFalse(session.strokesPending());
    }

    @Test
    void everyStrokeRefusalReasonHasItsOwnToast() {
        connect();
        Set<String> keys = new HashSet<>();
        for (RejectReason reason : RejectReason.values()) {
            notices.clear();
            FabricStrokeHandle stroke = (FabricStrokeHandle) session.beginStroke(ToolId.RAISE, SPEC, StrokeParams.DEFAULT);
            server(new S2C.StrokeStatus(stroke.strokeId(), -1, S2C.StrokeStatus.Status.REJECTED, reason));
            assertEquals(List.of(SessionNotices.rejection(reason, SessionNotices.Subject.STROKE, Limits.DEFAULTS)), notices,
                    reason.name());
            keys.add(notices.get(0).key());
        }
        assertEquals(RejectReason.values().length, keys.size(), "no two reasons share a message");
    }

    @Test
    void anOversizedStrokeQuotesTheServersBrushRadius() {
        session.onJoin();
        server(new S2C.Welcome(ProtocolV2.VERSION, Features.of(Features.STROKES), new Limits(1_000, 1_000, 12, 20, 1_000, 2),
                Perm.mask(EnumSet.of(Perm.USE, Perm.BRUSH)), 1L));
        FabricStrokeHandle stroke = (FabricStrokeHandle) session.beginStroke(ToolId.RAISE, SPEC, StrokeParams.DEFAULT);
        server(new S2C.StrokeStatus(stroke.strokeId(), -1, S2C.StrokeStatus.Status.REJECTED, RejectReason.TOO_LARGE));
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.reject.too_large.radius", "12")), notices);
    }

    @Test
    void endFlushesQueuedDabsAndTheServerConfirms() {
        connect();
        FabricStrokeHandle stroke = (FabricStrokeHandle) session.beginStroke(ToolId.RAISE, SPEC, StrokeParams.DEFAULT);
        stroke.dab(dab(0));
        stroke.dab(dab(1));
        stroke.end();
        assertFalse(stroke.active());
        assertEquals(List.of(dab(0), dab(1)), ((C2S.Dabs) transport.sent.get(1)).dabs());
        assertEquals(new C2S.StrokeEnd(stroke.strokeId()), transport.sent.get(2));
        server(new S2C.StrokeStatus(stroke.strokeId(), 1, S2C.StrokeStatus.Status.ENDED, null));
        assertTrue(stroke.ended());

        FabricStrokeHandle cancelled = (FabricStrokeHandle) session.beginStroke(ToolId.LOWER, SPEC, StrokeParams.DEFAULT);
        cancelled.dab(dab(0));
        cancelled.cancel();
        assertEquals(new C2S.StrokeEnd(cancelled.strokeId()), transport.last());
        assertFalse(transport.sent.stream().anyMatch(m -> m instanceof C2S.Dabs d && d.strokeId() == cancelled.strokeId()),
                "cancel drops queued dabs");
    }

    @Test
    void aNewStrokeEndsTheOpenOne() {
        connect();
        StrokeHandle first = session.beginStroke(ToolId.RAISE, SPEC, StrokeParams.DEFAULT);
        StrokeHandle second = session.beginStroke(ToolId.RAISE, SPEC, StrokeParams.DEFAULT);
        assertFalse(first.active());
        assertTrue(second.active());
        assertEquals(List.of(new C2S.StrokeBegin(first.strokeId(), SPEC), new C2S.StrokeEnd(first.strokeId()),
                new C2S.StrokeBegin(second.strokeId(), SPEC)), transport.sent);
    }

    @Test
    void missingAcknowledgementsTriggerAResync() {
        connect();
        FabricStrokeHandle stroke = (FabricStrokeHandle) session.beginStroke(ToolId.RAISE, SPEC, StrokeParams.DEFAULT);
        stroke.dab(new Dab(0, 16 * 100, 16 * 64, 16 * -50, 255));
        tick(50);
        tick(4_900);
        assertFalse(transport.last() instanceof C2S.Resync);
        tick(200);
        C2S.Resync resync = assertInstanceOf(C2S.Resync.class, transport.last());
        assertEquals(Box.of(new BlockPos(96, 60, -64), new BlockPos(111, 68, -33)), resync.box(), "whole chunks");
        assertEquals(0, stroke.unacknowledged(), "the window reopens after a resync");
    }

    @Test
    void wideResyncsAreSplitIntoRequestsOfAtMost64Chunks() {
        connect();
        BrushSpec wide = new BrushSpec(BrushTool.RAISE, 32, 1f, Falloff.SMOOTH, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L);
        FabricStrokeHandle stroke = (FabricStrokeHandle) session.beginStroke(ToolId.RAISE, wide, StrokeParams.DEFAULT);
        for (int i = 0; i < 16; i++) stroke.dab(new Dab(i, 16 * (i * 40), 16 * 64, 16 * (i % 2 == 0 ? 0 : 100), 255));
        tick(50);
        tick(5_100);
        List<C2S.Resync> resyncs = transport.sent.stream().filter(C2S.Resync.class::isInstance).map(C2S.Resync.class::cast).toList();
        assertFalse(resyncs.isEmpty());
        assertTrue(resyncs.size() <= FabricEditorSession.MAX_RESYNC_REQUESTS);
        for (C2S.Resync r : resyncs) {
            int chunksX = (r.box().max().x() >> 4) - (r.box().min().x() >> 4) + 1;
            int chunksZ = (r.box().max().z() >> 4) - (r.box().min().z() >> 4) + 1;
            assertTrue(chunksX * chunksZ <= 64, r.box() + " covers " + chunksX * chunksZ + " chunks");
        }
        // The first dab's footprint (chunks -2..2 on both axes) is covered by the first tiles.
        assertTrue(resyncs.stream().anyMatch(r -> r.box().contains(0, 64, 0)));
    }

    /**
     * With a distant symmetry centre the copies' tiles sort before the dabs' own (further west), but the dabs' own tiles
     * are requested first, so the cap of eight drops copies' tiles, never the area the player brushed.
     */
    @Test
    void symmetricResyncsRequestTheDabsOwnTilesBeforeTheCopies() {
        connect();
        BrushSpec mirrored = SPEC.withSymmetry(new Symmetry(Symmetry.Mode.MIRROR_X, -10_000, 0)); // the plane at x -5000
        FabricStrokeHandle stroke = (FabricStrokeHandle) session.beginStroke(ToolId.RAISE, mirrored, StrokeParams.DEFAULT);
        // Five dabs, each inside its own 8 × 8-chunk tile; their copies land in five more tiles far west.
        for (int i = 0; i < 5; i++) stroke.dab(new Dab(i, 16 * (64 + 256 * i), 16 * 64, 16 * 64, 255));
        tick(50);
        tick(5_100);
        List<C2S.Resync> resyncs = transport.sent.stream().filter(C2S.Resync.class::isInstance).map(C2S.Resync.class::cast).toList();
        assertEquals(FabricEditorSession.MAX_RESYNC_REQUESTS, resyncs.size(), "ten tiles, capped at eight");
        for (int i = 0; i < 5; i++) {
            assertTrue(resyncs.get(i).box().contains(64 + 256 * i, 64, 64), "the dab's own tile " + i + " first: " + resyncs);
        }
        for (int i = 5; i < resyncs.size(); i++) {
            assertTrue(resyncs.get(i).box().max().x() < -5000, "then the copies' tiles: " + resyncs.get(i).box());
        }
    }

    @Test
    void strokesBeforeReadyAreInactive() {
        StrokeHandle stroke = session.beginStroke(ToolId.RAISE, SPEC, StrokeParams.DEFAULT);
        assertFalse(stroke.active());
        stroke.dab(dab(0));
        stroke.end();
        assertTrue(transport.sent.isEmpty());
    }

    // ---------------------------------------------------------------- history

    @Test
    void historyStateFeedsTheMirror() {
        connect();
        int[] changes = {0};
        session.history().onChange(() -> changes[0]++);
        long version = session.history().version();
        server(new S2C.HistoryState(true, true, "Fill", "Erase", 4096, List.of("Fill", "Raise"), List.of("Erase")));
        HistoryMirror history = session.history();
        assertTrue(history.canUndo());
        assertEquals("Erase", history.redoLabel());
        assertEquals(List.of("Fill", "Raise"), history.undoLabels());
        assertEquals(4096, history.bytes());
        assertEquals(1, changes[0]);
        assertTrue(history.version() != version, "every history state moves the version (tools' local undo uses it)");
        long seen = history.version();
        server(new S2C.HistoryState(true, true, "Fill", "Erase", 4096, List.of("Fill", "Raise"), List.of("Erase")));
        assertTrue(history.version() != seen, "even one that looks the same: a push of an equal label");
        assertEquals(-2, HistoryMirror.undoTarget(1));
        assertEquals(1, HistoryMirror.redoTarget(0));
    }

    /** The server's history after {@code undone} of the entries (newest first) were undone. */
    private void history(List<String> entries, int undone) {
        List<String> undo = entries.subList(undone, entries.size());
        List<String> redo = new ArrayList<>(entries.subList(0, undone));
        java.util.Collections.reverse(redo);
        server(new S2C.HistoryState(!undo.isEmpty(), !redo.isEmpty(), undo.isEmpty() ? "" : undo.get(0),
                redo.isEmpty() ? "" : redo.get(0), 100, undo, redo));
    }

    /** Accepts and completes the step just sent, then sends the history it leaves, as the server does. */
    private void completeStep(UUID job, List<String> entries, int undoneAfter) {
        C2S last = transport.last();
        completeStep(last instanceof C2S.Undo u ? u.reqId() : ((C2S.Redo) last).reqId(), job, entries, undoneAfter);
    }

    private void completeStep(int reqId, UUID job, List<String> entries, int undoneAfter) {
        server(new S2C.JobAccepted(reqId, job, 10));
        server(new S2C.JobFinished(job, JobOutcome.COMPLETED, 10, 0, 0, 0));
        history(entries, undoneAfter);
    }

    private long sent(Class<? extends C2S> type) {
        return transport.sent.stream().filter(type::isInstance).count();
    }

    @Test
    void undoAndRedoAreRequestsAndRefusalsBecomeNotices() {
        connect();
        history(List.of("Fill"), 0);
        session.undo();
        C2S.Undo undo = assertInstanceOf(C2S.Undo.class, transport.sent.get(0));
        assertEquals(ConflictPolicy.SKIP_CONFLICTS, undo.policy());
        assertTrue(notices.isEmpty(), "nothing is announced before the server accepts");
        server(new S2C.JobAccepted(undo.reqId(), JOB, 5));
        assertEquals(List.of(Notice.of(Notice.Level.INFO, "sculptory.notice.undo", "Fill")), notices,
                "the toast names the entry being undone once the server accepts it");
        assertEquals("Undo", session.jobs().job(JOB).orElseThrow().label());
        server(new S2C.JobFinished(JOB, JobOutcome.COMPLETED, 5, 0, 0, 0));
        history(List.of("Fill"), 1);
        notices.clear();

        tick(250);
        session.redo();
        C2S.Redo redo = assertInstanceOf(C2S.Redo.class, transport.sent.get(1));
        server(new S2C.JobRejected(redo.reqId(), RejectReason.HISTORY_EMPTY));
        assertEquals(List.of(Notice.of(Notice.Level.INFO, "sculptory.notice.nothing_to_redo")), notices,
                "a refused press says why, and nothing else");
        tick(250);
        session.undo();
        server(new S2C.JobRejected(((C2S.Undo) transport.last()).reqId(), RejectReason.AREA_BUSY));
        assertEquals(Notice.of(Notice.Level.WARNING, "sculptory.reject.area_busy"), notices.get(1));
    }

    @Test
    void anUndoRefusedBecauseAnEditIsRunningSaysToWait() {
        connect();
        session.undo();
        server(new S2C.JobRejected(((C2S.Undo) transport.last()).reqId(), RejectReason.QUEUE_FULL));
        tick(250);
        session.redo();
        server(new S2C.JobRejected(((C2S.Redo) transport.last()).reqId(), RejectReason.QUEUE_FULL));
        assertEquals(List.of(
                Notice.of(Notice.Level.WARNING, "sculptory.reject.queue_full.undo"),
                Notice.of(Notice.Level.WARNING, "sculptory.reject.queue_full.redo")), notices);
    }

    @Test
    void undoResultsAreQuietUnlessSomethingWasSkipped() {
        connect();
        history(List.of("Raise stroke", "Fill"), 0);
        session.undo();
        server(new S2C.JobAccepted(((C2S.Undo) transport.last()).reqId(), JOB, 50));
        server(new S2C.JobFinished(JOB, JobOutcome.COMPLETED, 50, 0, 0, 0));
        assertEquals(List.of(Notice.of(Notice.Level.INFO, "sculptory.notice.undo", "Raise stroke")), notices,
                "a clean undo shows only its acceptance");
        history(List.of("Raise stroke", "Fill"), 1);
        notices.clear();
        UUID conflicted = new UUID(5, 5);
        tick(250);
        session.undo();
        server(new S2C.JobAccepted(((C2S.Undo) transport.last()).reqId(), conflicted, 50));
        server(new S2C.JobFinished(conflicted, JobOutcome.COMPLETED, 45, 0, 5, 0));
        assertEquals(List.of(
                Notice.of(Notice.Level.INFO, "sculptory.notice.undo", "Fill"),
                Notice.of(Notice.Level.SUCCESS, "sculptory.notice.job_finished", "Undo", "45", "5")), notices);
    }

    @Test
    void pressesDuringARunningUndoAreQueuedAndSentOneAtATime() {
        connect();
        List<String> entries = List.of("C", "B", "A");
        history(entries, 0);
        session.undo();
        session.undo();
        session.undo();
        assertEquals(1, sent(C2S.Undo.class), "one step in flight at a time; the server would refuse a second");
        assertTrue(session.historyBusy());
        assertTrue(notices.isEmpty());

        C2S.Undo first = (C2S.Undo) transport.last();
        server(new S2C.JobAccepted(first.reqId(), new UUID(1, 1), 10));
        assertEquals(List.of(Notice.of(Notice.Level.INFO, "sculptory.notice.undo", "C")), notices);
        tick(500);
        assertEquals(1, sent(C2S.Undo.class), "waits for the running undo to finish");
        server(new S2C.JobFinished(new UUID(1, 1), JobOutcome.COMPLETED, 10, 0, 0, 0));
        history(entries, 1);
        tick(50);
        assertEquals(2, sent(C2S.Undo.class), "the next press goes out once the first has finished");

        // A redo press cancels the last queued undo: the queue is a signed count.
        session.redo();
        completeStep(new UUID(1, 2), entries, 2);
        assertEquals(List.of(
                Notice.of(Notice.Level.INFO, "sculptory.notice.undo", "C"),
                Notice.of(Notice.Level.INFO, "sculptory.notice.undo", "B")), notices);
        tick(1_000);
        assertEquals(2, sent(C2S.Undo.class));
        assertEquals(0, sent(C2S.Redo.class));
        assertFalse(session.historyBusy());
    }

    @Test
    void steppingIsPacedUnderTheServersOpLimit() {
        connect();
        session.undo();
        completeStep(new UUID(2, 1), List.of("B", "A"), 1);
        session.undo();
        assertEquals(1, sent(C2S.Undo.class), "under 220 ms since the last step");
        assertTrue(session.historyBusy());
        tick(100);
        assertEquals(1, sent(C2S.Undo.class));
        tick(150);
        assertEquals(2, sent(C2S.Undo.class));
    }

    @Test
    void aRefusedStepDropsThePressesQueuedBehindIt() {
        connect();
        session.undo();
        session.undo();
        session.undo();
        server(new S2C.JobRejected(((C2S.Undo) transport.last()).reqId(), RejectReason.QUEUE_FULL));
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.reject.queue_full.undo")), notices,
                "one toast for the whole burst");
        assertFalse(session.historyBusy());
        tick(1_000);
        assertEquals(1, sent(C2S.Undo.class));
    }

    @Test
    void runningOutOfHistoryWhilePressingSaysSo() {
        connect();
        history(List.of("A"), 0);
        session.undo();
        session.undo();
        completeStep(new UUID(3, 1), List.of("A"), 1);
        tick(250);
        server(new S2C.JobRejected(((C2S.Undo) transport.last()).reqId(), RejectReason.HISTORY_EMPTY));
        assertEquals(List.of(
                Notice.of(Notice.Level.INFO, "sculptory.notice.undo", "A"),
                Notice.of(Notice.Level.INFO, "sculptory.notice.nothing_to_undo")), notices);
    }

    @Test
    void aFailedOrCancelledStepDropsTheQueue() {
        connect();
        session.undo();
        session.undo();
        UUID job = new UUID(4, 1);
        server(new S2C.JobAccepted(((C2S.Undo) transport.last()).reqId(), job, 10));
        server(new S2C.JobFinished(job, JobOutcome.CANCELLED, 3, 0, 0, 0));
        assertFalse(session.historyBusy());
        tick(1_000);
        assertEquals(1, sent(C2S.Undo.class));
    }

    @Test
    void startingAStrokeDropsTheQueuedUndos() {
        connect();
        List<String> entries = List.of("C", "B", "A");
        history(entries, 0);
        session.undo();
        session.undo();
        session.undo();
        int inFlight = ((C2S.Undo) transport.last()).reqId();
        session.beginStroke(ToolId.RAISE, SPEC, StrokeParams.DEFAULT).end();
        // The undo in flight still finishes and is still followed.
        assertTrue(session.historyBusy());
        completeStep(inFlight, new UUID(10, 1), entries, 1);
        tick(1_000);
        assertEquals(1, sent(C2S.Undo.class), "a queued undo must not undo the new stroke");
        assertFalse(session.historyBusy());
    }

    @Test
    void startingARegionOpDropsTheQueuedUndos() {
        connect();
        history(List.of("C", "B", "A"), 0);
        session.undo();
        session.undo();
        int inFlight = ((C2S.Undo) transport.last()).reqId();
        session.send(new ToolAction.RunOp(fill()));
        completeStep(inFlight, new UUID(10, 2), List.of("C", "B", "A"), 1);
        tick(1_000);
        assertEquals(1, sent(C2S.Undo.class), "a queued undo must not undo the new fill");
    }

    @Test
    void anUnexplainedHistoryPushDropsTheQueuedUndos() {
        connect();
        history(List.of("C", "B", "A"), 0);
        session.undo();
        session.undo();
        session.undo();
        C2S.Undo first = (C2S.Undo) transport.last();
        UUID job = new UUID(11, 1);
        server(new S2C.JobAccepted(first.reqId(), job, 10));
        server(new S2C.JobFinished(job, JobOutcome.COMPLETED, 10, 0, 0, 0));
        // The server deferred a push (e.g. a stroke committed by its idle timeout) until the undo finished:
        // C moved to redo, then the push added S and cleared redo. The list sizes alone look unchanged.
        server(new S2C.HistoryState(true, false, "S", "", 100, List.of("S", "B", "A"), List.of()));
        tick(1_000);
        assertEquals(1, sent(C2S.Undo.class), "the queued undos must not undo S");
        assertFalse(session.historyBusy());
    }

    @Test
    void onlyAStepsOwnMoveExplainsAHistoryChange() {
        S2C.HistoryState start = new S2C.HistoryState(true, true, "C", "D", 1, List.of("C", "B", "A"), List.of("D"));
        S2C.HistoryState undone = new S2C.HistoryState(true, true, "B", "C", 1, List.of("B", "A"), List.of("C", "D"));
        S2C.HistoryState redone = new S2C.HistoryState(true, false, "D", "", 1, List.of("D", "C", "B", "A"), List.of());
        S2C.HistoryState pushed = new S2C.HistoryState(true, false, "S", "", 1, List.of("S", "C", "B", "A"), List.of());
        assertTrue(FabricEditorSession.explainedByStep(start, start, -1), "unchanged (a refused step)");
        assertTrue(FabricEditorSession.explainedByStep(start, undone, -1));
        assertTrue(FabricEditorSession.explainedByStep(start, redone, 1));
        assertFalse(FabricEditorSession.explainedByStep(start, undone, 1), "an undo does not explain a redo step");
        assertFalse(FabricEditorSession.explainedByStep(start, pushed, -1));
        assertFalse(FabricEditorSession.explainedByStep(start, pushed, 1), "a push after the redo: new undo head");

        // Capped lists: the list losing an entry may reveal an older one at its end.
        List<String> many = new ArrayList<>();
        for (int i = 0; i < S2C.HistoryState.MAX_LABELS + 1; i++) many.add("E" + i);
        S2C.HistoryState full = new S2C.HistoryState(true, false, "E0", "", 1, many.subList(0, 64), List.of());
        S2C.HistoryState fullUndone = new S2C.HistoryState(true, true, "E1", "E0", 1, many.subList(1, 65), List.of("E0"));
        assertTrue(FabricEditorSession.explainedByStep(full, fullUndone, -1));
    }

    @Test
    void closingTheEditorDropsQueuedStepsButFollowsTheOneInFlight() {
        connect();
        List<String> entries = List.of("C", "B", "A");
        history(entries, 0);
        session.undo();
        session.undo();
        session.undo();
        session.dropQueuedHistorySteps();
        assertTrue(session.historyBusy(), "the undo already sent still runs");
        completeStep(new UUID(12, 1), entries, 1);
        assertEquals(List.of(Notice.of(Notice.Level.INFO, "sculptory.notice.undo", "C")), notices);
        tick(1_000);
        assertEquals(1, sent(C2S.Undo.class));
        assertFalse(session.historyBusy());
    }

    @Test
    void aStepAbandonedAfterTheStallIsNotAnnouncedIfItIsAcceptedLate() {
        connect();
        history(List.of("A"), 0);
        session.undo();
        C2S.Undo undo = (C2S.Undo) transport.last();
        tick(31_000);
        assertFalse(session.historyBusy());
        assertEquals("sculptory.notice.history_jump_stalled", notices.get(notices.size() - 1).key());
        notices.clear();
        server(new S2C.JobAccepted(undo.reqId(), new UUID(13, 1), 10));
        assertTrue(notices.isEmpty(), "no \"Undo: A\" half a minute after the player gave up on it");
    }

    @Test
    void jumpToCountsTheStepAlreadyInFlight() {
        connect();
        history(List.of("C", "B", "A"), 0);
        session.undo();
        // Two entries back from what the History window shows: the undo in flight is the first of them.
        session.jumpTo(HistoryMirror.undoTarget(1));
        assertEquals(1, sent(C2S.Undo.class));
        completeStep(new UUID(6, 1), List.of("C", "B", "A"), 1);
        tick(250);
        completeStep(new UUID(6, 2), List.of("C", "B", "A"), 2);
        tick(1_000);
        assertEquals(2, sent(C2S.Undo.class));
        assertFalse(session.historyBusy());
    }

    @Test
    void jumpToRunsPacedSequentialUndos() {
        connect();
        session.jumpTo(HistoryMirror.undoTarget(2));
        assertTrue(session.historyBusy());
        for (int step = 0; step < 3; step++) {
            C2S.Undo undo = assertInstanceOf(C2S.Undo.class, transport.last(), "step " + step);
            int sent = transport.sent.size();
            tick(10);
            assertEquals(sent, transport.sent.size(), "waits for the step's job");
            UUID job = new UUID(9, step);
            server(new S2C.JobAccepted(undo.reqId(), job, 1));
            server(new S2C.JobFinished(job, JobOutcome.COMPLETED, 1, 0, 0, 0));
            tick(100);
            assertEquals(sent, transport.sent.size(), "paced: under 220 ms since the last step");
            tick(150);
            assertEquals(step < 2 ? sent + 1 : sent, transport.sent.size());
        }
        assertEquals(3, transport.sent.stream().filter(m -> m instanceof C2S.Undo).count());
        assertFalse(session.historyBusy());
    }

    @Test
    void jumpStopsQuietlyAtTheEndOfHistoryAndPressesAddToIt() {
        connect();
        session.jumpTo(HistoryMirror.redoTarget(5));
        C2S.Redo redo = assertInstanceOf(C2S.Redo.class, transport.last());
        server(new S2C.JobRejected(redo.reqId(), RejectReason.HISTORY_EMPTY));
        assertFalse(session.historyBusy());
        assertTrue(notices.isEmpty(), "running out of history ends a jump without a warning");

        tick(250);
        session.jumpTo(-2);
        session.redo();
        assertEquals(1, sent(C2S.Undo.class));
        completeStep(JOB, List.of("B", "A"), 1);
        int sent = transport.sent.size();
        tick(1_000);
        assertEquals(sent, transport.sent.size(), "the redo press cancelled the jump's second undo");
        assertFalse(session.historyBusy());
    }

    @Test
    void aFailedStepEndsTheJumpEvenWhenItsResultArrivesFirst() {
        connect();
        session.jumpTo(-3);
        C2S.Undo undo = (C2S.Undo) transport.last();
        UUID job = new UUID(8, 8);
        server(new S2C.JobFinished(job, JobOutcome.FAILED, 0, 0, 0, 0));
        server(new S2C.JobAccepted(undo.reqId(), job, 1));
        assertFalse(session.historyBusy());
        tick(1_000);
        assertEquals(1, transport.sent.stream().filter(m -> m instanceof C2S.Undo).count());
    }

    @Test
    void jumpStepTimeoutRestartsOnProgress() {
        connect();
        session.jumpTo(-2);
        C2S.Undo undo = (C2S.Undo) transport.last();
        UUID job = new UUID(8, 9);
        server(new S2C.JobAccepted(undo.reqId(), job, 1_000_000));
        for (int i = 0; i < 4; i++) {
            tick(20_000);
            server(new S2C.JobProgress(job, i, 1_000_000, Phase.APPLY));
        }
        tick(20_000);
        assertTrue(session.historyBusy(), "a long step that keeps reporting progress is not abandoned");
        tick(11_000);
        assertFalse(session.historyBusy(), "30 s without progress abandons the jump");
        assertEquals("sculptory.notice.history_jump_stalled", notices.get(notices.size() - 1).key());
    }

    /** Protocol 5's optional features are negotiated: the client offers what the server does (a feature one side lacks is off). */
    @Test
    void theClientOffersTheEditMaskAndJump() {
        assertTrue(FabricEditorSession.CLIENT_FEATURES.has(Features.EDIT_MASK));
        assertTrue(FabricEditorSession.CLIENT_FEATURES.has(Features.NAVIGATE));
        Features common = ServerDispatcher.SERVER_FEATURES.intersect(FabricEditorSession.CLIENT_FEATURES);
        assertTrue(common.has(Features.EDIT_MASK) && common.has(Features.NAVIGATE));
    }

    // ---------------------------------------------------------------- builder mode

    /** Builder messages go out only on a ready session whose server negotiated builder mode; the client offers it. */
    @Test
    void builderMessagesNeedTheNegotiatedFeature() {
        assertTrue(FabricEditorSession.CLIENT_FEATURES.has(Features.BUILDER));
        assertFalse(session.builderOffered());
        C2S.BuilderPowers powers = new C2S.BuilderPowers(BuilderPower.LONG_REACH.bit() | BuilderPower.MIRROR.bit());
        assertTrue(session.sendBuilder(powers).startsWith("NOT_READY"));
        connect();
        assertFalse(session.builderOffered(), "the server offered strokes only");
        assertTrue(session.sendBuilder(powers).startsWith("NOT_OFFERED"));
        assertTrue(transport.sent.isEmpty());

        session.onDisconnect();
        session.onJoin();
        server(new S2C.Welcome(ProtocolV2.VERSION, Features.of(Features.STROKES, Features.BUILDER), Limits.DEFAULTS,
                Perm.mask(EnumSet.of(Perm.USE, Perm.BUILDER)), 100L));
        transport.sent.clear();
        assertTrue(session.builderOffered());
        assertNull(session.sendBuilder(powers));
        assertNull(session.sendBuilder(new C2S.BuilderPlace(7, false, new BlockPos(1, 64, 2), Facing.UP, 0.5f, 1f, 0.5f,
                BuilderPower.MIRROR.bit(), new Symmetry(Symmetry.Mode.MIRROR_X, 3, 5))));
        assertNull(session.sendBuilder(new C2S.BuilderBreak(8, 3, List.of(new BlockPos(1, 64, 2)), 0, Symmetry.NONE,
                false, false)));
        assertNull(session.sendBuilder(new C2S.BuilderDragEnd(3)));
        assertEquals(List.of(C2S.BuilderPowers.class, C2S.BuilderPlace.class, C2S.BuilderBreak.class,
                C2S.BuilderDragEnd.class), transport.sent.stream().map(Object::getClass).toList());
        assertEquals(powers, transport.sent.get(0));
        assertFalse(session.history().canUndo(), "builder steps reach the mirror through HistoryState, not the send");
        assertThrows(IllegalArgumentException.class, () -> session.sendBuilder(new C2S.Undo(1, ConflictPolicy.SKIP_CONFLICTS)));
    }

    // ---------------------------------------------------------------- permissions

    @Test
    void permissionsChangedMidSessionOpensAndClosesTheEditor() {
        session.onJoin();
        server(new S2C.Welcome(ProtocolV2.VERSION, Features.of(Features.STROKES), Limits.DEFAULTS, PermissionMask.NONE, 1L));
        List<Notice> toasts = new ArrayList<>();
        EditorMode mode = new EditorMode(new EditorModeHost(session, toasts));
        EditorMode.Observation inEditor = new EditorMode.Observation(true, false, "world", EditorMode.ScreenState.EDITOR);

        // A guest is refused...
        assertFalse(mode.enter());
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.notice.no_permission")), toasts);
        // ...until an operator grants the editor, without rejoining.
        server(new S2C.PermissionsChanged(Perm.mask(EnumSet.of(Perm.USE, Perm.BRUSH)), new Limits(1, 1, 8, 4, 1, 1)));
        assertTrue(session.permissions().has(Perm.USE));
        assertEquals(8, session.permissions().limits().maxBrushRadius());
        assertTrue(mode.enter());
        mode.tick(inEditor);
        assertEquals(EditorState.ACTIVE, mode.state());

        // Deopped mid-session: the next tick closes the editor and says why.
        toasts.clear();
        server(new S2C.PermissionsChanged(Perm.mask(EnumSet.of(Perm.BRUSH)), Limits.DEFAULTS));
        assertFalse(session.permissions().has(Perm.USE));
        mode.tick(inEditor);
        assertEquals(EditorState.INACTIVE, mode.state());
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.notice.editor_closed_permission")), toasts);
    }

    /** Just enough of the Minecraft side of {@link EditorMode} for permission changes. */
    private record EditorModeHost(EditorSession editorSession, List<Notice> toasts) implements EditorMode.Host {
        @Override
        public java.util.Optional<EditorSession> session() {
            return java.util.Optional.of(editorSession);
        }

        @Override
        public Object currentWorld() {
            return "world";
        }

        @Override
        public void showEditorScreen() {}

        @Override
        public void hideEditorScreen() {}

        @Override
        public void openVanillaScreen(EditorMode.SuspendTarget target) {}

        @Override
        public void setEditorVisuals(boolean editing) {}

        @Override
        public void activated() {}

        @Override
        public void deactivated(DeactivateReason reason) {}

        @Override
        public void toast(Notice notice) {
            toasts.add(notice);
        }
    }

    // ---------------------------------------------------------------- notices, streams, disconnect

    @Test
    void serverNoticesAreRelayed() {
        connect();
        server(new S2C.Notice(S2C.Notice.Level.WARN, "sculptory.notice.protected", List.of("12")));
        assertEquals(Notice.of(Notice.Level.WARNING, "sculptory.notice.protected", "12"), notices.get(0));
    }

    @Test
    void inboundStreamsAreAssembledWithCredit() {
        session.onJoin();
        server(new S2C.Welcome(ProtocolV2.VERSION, Features.of(Features.CLIPBOARD), Limits.DEFAULTS, PermissionMask.NONE, 1L));
        // Streams are accepted only as answers: ask for the preview this stream carries.
        UUID clipboard = new UUID(8, 8);
        session.requestPreview(new dev.sculptory.core.edit.SourceRef.Clipboard(clipboard));
        transport.sent.clear();
        byte[] payload = new byte[5 << 20];
        new Random(4).nextBytes(payload);
        TreeMap<String, String> meta = new TreeMap<>();
        meta.put("clipboardId", clipboard.toString());
        StreamSender sender = new StreamSender(3, StreamKind.CLIPBOARD_PREVIEW, payload, meta,
                StreamSender.MAX_S2C_CHUNK, 4L << 20);
        List<FabricEditorSession.ReceivedStream> received = new ArrayList<>();
        session.onStream(received::add);
        for (int round = 0; round < 40 && received.isEmpty(); round++) {
            for (var message : sender.poll(StreamSender.SERVER_BYTES_PER_TICK)) server((S2C) message);
            for (var message : List.copyOf(transport.sent)) {
                if (message instanceof StreamCredit credit) sender.credit(credit);
            }
            transport.sent.removeIf(m -> m instanceof StreamCredit);
        }
        assertEquals(1, received.size());
        assertArrayEquals(payload, received.get(0).bytes());
    }

    @Test
    void disconnectResetsEverythingAndFailsPendingRequests() {
        connect();
        CompletableFuture<ToolResult> pending = session.send(new ToolAction.RunOp(fill())).toCompletableFuture();
        StrokeHandle stroke = session.beginStroke(ToolId.RAISE, SPEC, StrokeParams.DEFAULT);
        server(new S2C.HistoryState(true, false, "Fill", "", 10));
        server(new S2C.JobProgress(JOB, 1, 2, Phase.APPLY));
        session.onDisconnect();
        assertEquals(SessionState.DISCONNECTED, session.state());
        assertEquals(Capabilities.NONE, session.capabilities());
        assertEquals(Permissions.NONE, session.permissions());
        assertEquals(RejectReason.DISABLED, ((ToolResult.Rejected) pending.join()).reason());
        assertFalse(stroke.active());
        assertFalse(session.history().canUndo());
        assertTrue(session.jobs().jobs().isEmpty());
        int sent = transport.sent.size();
        stroke.end();
        assertEquals(sent, transport.sent.size(), "nothing is sent after the connection is gone");
    }

    private static byte[] encode(S2C message) {
        try {
            return Codec.encodeS2C(message, STATES);
        } catch (ProtocolException e) {
            throw new AssertionError(e);
        }
    }

    /** Records what the session sends, decoded. */
    private static final class FakeTransport implements FabricEditorSession.Transport {
        boolean canSend = true;
        final List<C2S> sent = new ArrayList<>();

        @Override
        public boolean canSend() {
            return canSend;
        }

        @Override
        public void send(byte[] frame) {
            try {
                sent.add(Codec.decodeC2S(frame, STATES));
            } catch (ProtocolException e) {
                throw new AssertionError("The client sent an undecodable frame", e);
            }
        }

        C2S last() {
            return sent.get(sent.size() - 1);
        }
    }
}
