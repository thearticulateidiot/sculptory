package dev.sculptory.fabric.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.fabric.engine.EditRejected;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.fabric.net.ServerDispatcher;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.Codec;
import dev.sculptory.protocol.v2.Features;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.protocol.v2.ProtocolException;
import dev.sculptory.protocol.v2.ProtocolV2;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Undo anyway and history jumps in the session: the run of steps it follows (as the server does), the offer, the
 * {@code HistoryOverwrite} it sends and its answers, and a jump's progress. Server messages come in the order the
 * server sends them: a step's {@code HistoryState} before its {@code JobFinished}.
 */
class FabricEditorSessionHistoryTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final long MS = 1_000_000L;
    private static final List<String> ENTRIES = List.of("E", "D", "C", "B", "A");

    private FakeTransport transport;
    private AtomicLong clock;
    private FabricEditorSession session;
    private final List<Notice> notices = new ArrayList<>();
    private int jobs;
    private long bytes = 100;

    @BeforeEach
    void setUp() {
        transport = new FakeTransport();
        clock = new AtomicLong(10_000 * MS);
        session = new FabricEditorSession(transport, () -> STATES, clock::get, "0.2.0-test");
        session.onNotice(notices::add);
        session.onJoin();
        server(new S2C.Welcome(ProtocolV2.VERSION, Features.of(Features.HISTORY), Limits.DEFAULTS,
                Perm.mask(EnumSet.of(Perm.USE)), 1L));
        assertEquals(SessionState.READY, session.state());
        history(0);
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

    /** The server's history after {@code undone} of {@link #ENTRIES} (newest first) were undone. */
    private void history(int undone) {
        history(ENTRIES, undone);
    }

    private void history(List<String> entries, int undone) {
        List<String> undo = entries.subList(undone, entries.size());
        List<String> redo = new ArrayList<>(entries.subList(0, undone));
        Collections.reverse(redo);
        server(new S2C.HistoryState(!undo.isEmpty(), !redo.isEmpty(), undo.isEmpty() ? "" : undo.get(0),
                redo.isEmpty() ? "" : redo.get(0), bytes, undo, redo));
    }

    private int lastReqId() {
        C2S last = transport.last();
        return switch (last) {
            case C2S.Undo u -> u.reqId();
            case C2S.Redo r -> r.reqId();
            case C2S.HistoryOverwrite o -> o.reqId();
            default -> throw new AssertionError(last);
        };
    }

    /**
     * Presses undo (or redo), waits for the pacing, and answers the step as the server does: accepted, the history it
     * leaves ({@code undoneAfter}), then its result with {@code kept} skipped conflicts.
     */
    private UUID step(boolean undo, int undoneAfter, long kept) {
        tick(250);
        if (undo) {
            session.undo();
        } else {
            session.redo();
        }
        UUID job = new UUID(1, ++jobs);
        server(new S2C.JobAccepted(lastReqId(), job, 10));
        history(undoneAfter);
        server(new S2C.JobFinished(job, JobOutcome.COMPLETED, 10 - kept, 0, kept, 0));
        history(undoneAfter);
        return job;
    }

    private HistoryOffer offer() {
        return session.historyOffer().orElseThrow(() -> new AssertionError("no offer"));
    }

    @Test
    void aStepThatKeptBlocksOffersUndoAnywayAndAcceptingSendsTheRun() {
        step(true, 1, 0);
        assertTrue(session.historyOffer().isEmpty(), "nothing kept, nothing to offer");
        step(true, 2, 5);
        HistoryOffer offer = offer();
        assertFalse(offer.redo());
        assertEquals(2, offer.steps(), "the run holds both undos, the clean one too");
        assertEquals(5, offer.skipped());
        assertFalse(offer.running());

        tick(250);
        transport.sent.clear();
        session.acceptHistoryOffer();
        C2S.HistoryOverwrite sent = assertInstanceOf(C2S.HistoryOverwrite.class, transport.last());
        assertEquals(new C2S.HistoryOverwrite(sent.reqId(), false, 2), sent);
        assertTrue(offer().running());
        assertTrue(session.historyBusy());
        session.acceptHistoryOffer();
        assertEquals(1, transport.sent.size(), "one overwrite at a time");

        UUID job = new UUID(9, 1);
        server(new S2C.JobAccepted(sent.reqId(), job, 5));
        assertEquals("Undo anyway", session.jobs().job(job).orElseThrow().label());
        server(new S2C.JobProgress(job, 2, 5, Phase.APPLY));
        notices.clear();
        server(new S2C.JobFinished(job, JobOutcome.COMPLETED, 5, 0, 0, 0));
        history(2);
        assertEquals(List.of(Notice.of(Notice.Level.SUCCESS, SessionNotices.OVERWRITE_DONE, "Undo anyway", "5")), notices);
        assertTrue(session.historyOffer().isEmpty(), "a completed overwrite resolves the offer");
        assertFalse(session.historyBusy());
    }

    @Test
    void theRunFollowsOneDirectionAndTheOtherDirectionStartsOver() {
        step(true, 1, 3);
        step(true, 2, 0);
        step(true, 3, 2);
        assertEquals(3, offer().steps());
        assertEquals(5, offer().skipped());
        long id = offer().id();
        step(false, 2, 1);
        HistoryOffer redo = offer();
        assertTrue(redo.redo());
        assertEquals(1, redo.steps());
        assertEquals(1, redo.skipped());
        assertTrue(redo.id() != id, "a new offer, so the toast shows again");
        step(false, 1, 0);
        assertEquals(2, offer().steps());
        assertEquals(1, offer().skipped());
    }

    @Test
    void anyOtherHistoryChangeWithdrawsTheOffer() {
        step(true, 1, 4);
        assertTrue(session.historyOffer().isPresent());
        // A push (another edit, a stroke committed by its idle timeout, a command).
        history(List.of("S", "D", "C", "B", "A"), 0);
        assertTrue(session.historyOffer().isEmpty());

        history(0);
        step(true, 1, 4);
        assertTrue(session.historyOffer().isPresent());
        // An eviction beyond the labels shown changes only the history's size.
        bytes = 90;
        history(1);
        assertTrue(session.historyOffer().isEmpty(), "the server ends its run on every eviction");
    }

    @Test
    void aStepWhoseHistoryStateAlsoCarriesAPushStartsNoRun() {
        step(true, 1, 2);
        tick(250);
        session.undo();
        UUID job = new UUID(2, 1);
        server(new S2C.JobAccepted(lastReqId(), job, 10));
        // The undo of D, then a push deferred behind it: one state that the undo alone does not explain.
        server(new S2C.HistoryState(true, false, "S", "", 100, List.of("S", "C", "B", "A"), List.of()));
        server(new S2C.JobFinished(job, JobOutcome.COMPLETED, 6, 0, 4, 0));
        assertTrue(session.historyOffer().isEmpty(), "the server's run ended with the push");

        tick(250);
        session.undo();
        UUID next = new UUID(2, 2);
        server(new S2C.JobAccepted(lastReqId(), next, 10));
        server(new S2C.HistoryState(true, true, "C", "S", 100, List.of("C", "B", "A"), List.of("S")));
        server(new S2C.JobFinished(next, JobOutcome.COMPLETED, 7, 0, 3, 0));
        assertEquals(1, offer().steps(), "a fresh run");
        assertEquals(3, offer().skipped());
    }

    @Test
    void aChangeBeforeTheStepsOwnMoveStartsAFreshRunWithThatStep() {
        step(true, 1, 2);
        tick(250);
        session.undo();
        UUID job = new UUID(3, 1);
        server(new S2C.JobAccepted(lastReqId(), job, 10));
        bytes = 80; // an eviction while the undo runs (the server's run ends)...
        history(1);
        history(2); // ...then the undo's own move
        server(new S2C.JobFinished(job, JobOutcome.COMPLETED, 9, 0, 1, 0));
        assertEquals(1, offer().steps(), "the server's run starts again with this step");
        assertEquals(1, offer().skipped());
    }

    /**
     * The play check's find (a water Fill a player built beside, undone in the real client): the server folds what an
     * entry's water did since into it when stepping it (the history grows) and keeps its run, so the offer must stay.
     */
    @Test
    void aStepThatFoldsWhatItsWaterDidGrowsTheHistoryAndKeepsTheRun() {
        step(true, 1, 0);
        tick(250);
        session.undo();
        UUID undo = new UUID(5, 1);
        server(new S2C.JobAccepted(lastReqId(), undo, 10));
        bytes = 160;
        history(2);
        server(new S2C.JobFinished(undo, JobOutcome.COMPLETED, 8, 0, 2, 0));
        history(2);
        assertEquals(2, offer().steps(), "the run goes on through the grown history");
        assertEquals(2, offer().skipped());

        tick(250);
        session.redo();
        UUID redo = new UUID(5, 2);
        server(new S2C.JobAccepted(lastReqId(), redo, 10));
        bytes = 200;
        history(1);
        server(new S2C.JobFinished(redo, JobOutcome.COMPLETED, 9, 0, 1, 0));
        assertTrue(offer().redo());
        assertEquals(1, offer().steps());
        assertEquals(1, offer().skipped());
    }

    @Test
    void aGrownHistoryStateArrivingAfterTheStepsResultStillKeepsTheRun() {
        step(true, 1, 0);
        tick(250);
        session.undo();
        UUID undo = new UUID(7, 1);
        server(new S2C.JobAccepted(lastReqId(), undo, 10));
        // The result first, then the step's own move with the fold's growth.
        server(new S2C.JobFinished(undo, JobOutcome.COMPLETED, 7, 0, 3, 0));
        bytes = 175;
        history(2);
        assertEquals(2, offer().steps());
        assertEquals(3, offer().skipped());
    }

    @Test
    void aStepWhoseStateAlsoShowsAnEvictionEndsTheRun() {
        step(true, 1, 3);
        tick(250);
        session.undo();
        UUID job = new UUID(6, 1);
        server(new S2C.JobAccepted(lastReqId(), job, 10));
        // The undo's own move and an eviction in one state: the history is smaller.
        bytes = 70;
        history(2);
        server(new S2C.JobFinished(job, JobOutcome.COMPLETED, 8, 0, 2, 0));
        assertTrue(session.historyOffer().isEmpty(), "the server ended its run with the eviction");
    }

    @Test
    void aCancelledOrFailedStepEndsTheRun() {
        step(true, 1, 4);
        tick(250);
        session.undo();
        UUID job = new UUID(4, 1);
        server(new S2C.JobAccepted(lastReqId(), job, 10));
        server(new S2C.JobFinished(job, JobOutcome.CANCELLED, 3, 0, 2, 0));
        assertTrue(session.historyOffer().isEmpty());
    }

    /** The server's refusal of the overwrite in flight: its notice first, then the JobRejected. */
    private void refuseOverwrite(RejectReason reason, String detail, String kind) {
        int reqId = lastReqId();
        server(new S2C.Notice(S2C.Notice.Level.WARN, ServerDispatcher.NOTICE_OVERWRITE_REFUSED,
                List.of(reason.name(), detail, kind)));
        server(new S2C.JobRejected(reqId, reason));
    }

    @Test
    void aRefusalOfTheRunWithdrawsTheOfferAndSaysWhyWithTheServersDetail() {
        step(true, 1, 4);
        tick(250);
        session.undo();
        session.redo(); // queued behind the undo; the refusal below drops it
        server(new S2C.JobRejected(lastReqId(), RejectReason.AREA_BUSY));
        assertEquals(1, offer().steps(), "a refused step changes nothing, so the run stays");
        tick(250);
        session.acceptHistoryOffer();
        int reqId = lastReqId();
        notices.clear();
        server(new S2C.Notice(S2C.Notice.Level.WARN, ServerDispatcher.NOTICE_OVERWRITE_REFUSED,
                List.of("INVALID", "the history changed since those undo steps", EditRejected.HISTORY_RUN)));
        assertTrue(notices.isEmpty(), "the server's notice is folded into the refusal's toast");
        server(new S2C.JobRejected(reqId, RejectReason.INVALID));
        assertEquals(List.of(SessionNotices.overwriteRefused(false, "the history changed since those undo steps")),
                notices);
        assertTrue(session.historyOffer().isEmpty());
        assertFalse(session.historyBusy());
        assertEquals(SessionNotices.OVERWRITE_REFUSED_BY_SERVER, ServerDispatcher.NOTICE_OVERWRITE_REFUSED);
        assertEquals(SessionNotices.OVERWRITE_RUN_REFUSED, EditRejected.HISTORY_RUN);
    }

    /**
     * Refusals that are not about the run keep the offer for another try: INVALID of another kind (outside the build
     * limit, a world not loaded), INVALID with no notice at all (a server error), and QUEUE_FULL, UNLOADED, TOO_LARGE
     * and NO_PERMISSION, each with its own toast.
     */
    @Test
    void otherRefusalsKeepTheOfferForAnotherTry() {
        step(true, 1, 4);
        List<Object[]> refusals = List.of(
                new Object[] {RejectReason.INVALID, "outside the build limit",
                        Notice.of(Notice.Level.WARNING, "sculptory.reject.invalid.detail", "outside the build limit")},
                new Object[] {RejectReason.INVALID, "world minecraft:the_nether is not loaded",
                        Notice.of(Notice.Level.WARNING, "sculptory.reject.invalid.detail",
                                "world minecraft:the_nether is not loaded")},
                new Object[] {RejectReason.QUEUE_FULL, "a job is still running",
                        Notice.of(Notice.Level.WARNING, SessionNotices.QUEUE_FULL_UNDO)},
                new Object[] {RejectReason.UNLOADED, "chunks are not loaded",
                        Notice.of(Notice.Level.WARNING, "sculptory.reject.unloaded")},
                new Object[] {RejectReason.TOO_LARGE, "20000 chunk columns > 16384",
                        Notice.of(Notice.Level.WARNING, "sculptory.reject.too_large")},
                new Object[] {RejectReason.NO_PERMISSION, "sculptory.use",
                        Notice.of(Notice.Level.WARNING, "sculptory.reject.no_permission")});
        for (Object[] refusal : refusals) {
            session.acceptHistoryOffer();
            notices.clear();
            refuseOverwrite((RejectReason) refusal[0], (String) refusal[1], "");
            assertEquals(List.of(refusal[2]), notices, "toast for " + refusal[0]);
            assertEquals(1, offer().steps(), "the offer stays after " + refusal[0] + " " + refusal[1]);
            assertFalse(offer().running());
            assertFalse(session.historyBusy());
        }

        session.acceptHistoryOffer();
        notices.clear();
        server(new S2C.JobRejected(lastReqId(), RejectReason.INVALID)); // no notice: the server failed
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.reject.invalid")), notices);
        assertEquals(1, offer().steps(), "without the server's word that the run changed, the offer stays");

        // A run refusal's kind with another reason is not a run refusal either.
        session.acceptHistoryOffer();
        refuseOverwrite(RejectReason.QUEUE_FULL, "an undo or redo is still running", EditRejected.HISTORY_RUN);
        assertEquals(1, offer().steps());
        transport.sent.clear();
        session.acceptHistoryOffer();
        assertInstanceOf(C2S.HistoryOverwrite.class, transport.last());
    }

    @Test
    void aFailedOverwriteKeepsTheOffer() {
        step(true, 1, 4);
        session.acceptHistoryOffer();
        UUID job = new UUID(5, 9);
        server(new S2C.JobAccepted(lastReqId(), job, 4));
        notices.clear();
        server(new S2C.JobFinished(job, JobOutcome.FAILED, 1, 0, 0, 0));
        assertEquals(List.of(Notice.of(Notice.Level.ERROR, SessionNotices.OVERWRITE_FAILED, "Undo anyway", "1")), notices);
        assertEquals(1, offer().steps());
        assertFalse(offer().running());
    }

    @Test
    void aCancelledOverwriteKeepsTheOfferAndRunningItAgainIsAllowed() {
        step(true, 1, 4);
        session.acceptHistoryOffer();
        UUID job = new UUID(5, 1);
        server(new S2C.JobAccepted(lastReqId(), job, 4));
        notices.clear();
        server(new S2C.JobFinished(job, JobOutcome.CANCELLED, 2, 0, 0, 0));
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, SessionNotices.OVERWRITE_CANCELLED, "Undo anyway", "2")),
                notices);
        assertFalse(offer().running());
        transport.sent.clear();
        session.acceptHistoryOffer();
        assertEquals(new C2S.HistoryOverwrite(lastReqId(), false, 1), transport.last());
    }

    /**
     * Undo anyway folds what its entries' water did into them (the history grows, no label moves) and the server keeps
     * its run when the overwrite is cancelled: the client keeps the offer too. A smaller history then is an eviction.
     */
    @Test
    void anOverwriteThatFoldsWaterGrowsTheHistoryAndACancelStillKeepsTheOffer() {
        step(true, 1, 4);
        session.acceptHistoryOffer();
        UUID job = new UUID(8, 1);
        server(new S2C.JobAccepted(lastReqId(), job, 4));
        bytes = 150;
        history(1);
        server(new S2C.JobFinished(job, JobOutcome.CANCELLED, 2, 0, 0, 0));
        history(1);
        assertEquals(1, offer().steps(), "the run is kept for another try");
        assertFalse(offer().running());

        session.acceptHistoryOffer();
        UUID again = new UUID(8, 2);
        server(new S2C.JobAccepted(lastReqId(), again, 4));
        bytes = 120;
        history(1);
        server(new S2C.JobFinished(again, JobOutcome.CANCELLED, 1, 0, 0, 0));
        assertTrue(session.historyOffer().isEmpty(), "an eviction while it ran ended the run");
    }

    @Test
    void stepsWaitForTheOverwriteAndAcceptingWaitsForSteps() {
        step(true, 1, 4);
        session.acceptHistoryOffer();
        int overwrite = lastReqId();
        transport.sent.clear();
        session.undo();
        tick(1_000);
        assertTrue(transport.sent.isEmpty(), "an undo pressed during Undo anyway waits for it");
        UUID job = new UUID(6, 1);
        server(new S2C.JobAccepted(overwrite, job, 4));
        server(new S2C.JobFinished(job, JobOutcome.COMPLETED, 4, 0, 0, 0));
        tick(250);
        assertInstanceOf(C2S.Undo.class, transport.last(), "then it goes out");

        // With an undo in flight, Undo anyway is not sent (the server would refuse it).
        server(new S2C.JobAccepted(lastReqId(), new UUID(6, 2), 10));
        history(2);
        server(new S2C.JobFinished(new UUID(6, 2), JobOutcome.COMPLETED, 5, 0, 5, 0));
        tick(250);
        session.undo();
        transport.sent.clear();
        session.acceptHistoryOffer();
        assertTrue(transport.sent.isEmpty());
    }

    @Test
    void anUnansweredOverwriteIsAbandonedAfterTheStall() {
        step(true, 1, 4);
        session.acceptHistoryOffer();
        notices.clear();
        tick(31_000);
        assertFalse(session.historyBusy());
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.notice.history_overwrite_stalled")), notices);
        assertFalse(offer().running(), "the offer is still there to try again");
    }

    @Test
    void withoutAnOfferAcceptingSendsNothingAndADisconnectForgetsIt() {
        session.acceptHistoryOffer();
        assertTrue(transport.sent.isEmpty());
        step(true, 1, 4);
        assertTrue(session.historyOffer().isPresent());
        session.onDisconnect();
        assertTrue(session.historyOffer().isEmpty());
    }

    @Test
    void aJumpReportsItsProgressUntilItEnds() {
        assertTrue(session.historyJump().isEmpty());
        session.jumpTo(HistoryMirror.undoTarget(2));
        assertEquals(Optional.of(new HistoryJump(true, 0, 3)), session.historyJump());
        for (int i = 0; i < 3; i++) {
            UUID job = new UUID(7, i);
            server(new S2C.JobAccepted(lastReqId(), job, 10));
            history(i + 1);
            server(new S2C.JobFinished(job, JobOutcome.COMPLETED, 9, 0, 1, 0));
            if (i < 2) {
                assertEquals(Optional.of(new HistoryJump(true, i + 1, 3)), session.historyJump());
            }
            tick(250);
        }
        assertTrue(session.historyJump().isEmpty(), "done");
        assertEquals(3, offer().steps(), "a jump's steps make a run like presses do");
        assertEquals(3, offer().skipped());

        session.jumpTo(HistoryMirror.redoTarget(1));
        server(new S2C.JobAccepted(lastReqId(), new UUID(8, 1), 10));
        session.dropQueuedHistorySteps(); // the window's Stop
        history(2);
        server(new S2C.JobFinished(new UUID(8, 1), JobOutcome.COMPLETED, 10, 0, 0, 0));
        tick(1_000);
        assertTrue(session.historyJump().isEmpty(), "stopped after the step that was running");
        assertFalse(session.historyBusy());
    }

    private static final class FakeTransport implements FabricEditorSession.Transport {
        final List<C2S> sent = new ArrayList<>();

        C2S last() {
            return sent.get(sent.size() - 1);
        }

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

    @Test
    void theMirrorSaysWhichChangesAreThisClientsOwnUndoAndRedoSteps() {
        List<HistoryMirror.Cause> causes = new ArrayList<>();
        List<List<String>> undoLists = new ArrayList<>();
        session.history().onChange(() -> {
            if (undoLists.isEmpty() || !undoLists.get(undoLists.size() - 1).equals(session.history().undoLabels())) {
                causes.add(session.history().lastCause());
            }
            undoLists.add(session.history().undoLabels());
        });
        step(true, 1, 0);
        step(false, 0, 0);
        assertEquals(List.of(HistoryMirror.Cause.UNDO_STEP, HistoryMirror.Cause.REDO_STEP), causes);

        // The step's result first, its history after: still that step's move.
        causes.clear();
        tick(250);
        session.undo();
        UUID job = new UUID(1, ++jobs);
        server(new S2C.JobAccepted(lastReqId(), job, 10));
        server(new S2C.JobFinished(job, JobOutcome.COMPLETED, 10, 0, 0, 0));
        history(1);
        assertEquals(List.of(HistoryMirror.Cause.UNDO_STEP), causes);

        // The same move with no step of this client (a command, say) leaves the bytes as they were: not a step, but
        // not certainly anything else either. A new edit changes the bytes: certainly not a step.
        causes.clear();
        history(2);
        bytes = 120;
        history(List.of("F", "C", "B", "A"), 0);
        assertEquals(List.of(HistoryMirror.Cause.UNKNOWN, HistoryMirror.Cause.OTHER), causes);

        // A redo made another way with the bytes unchanged: the labels alone would read it as a new edit "F" too, so
        // it is UNKNOWN (the tutorial then never takes it for an edit of the lesson's).
        causes.clear();
        history(List.of("F", "C", "B", "A"), 1);
        history(List.of("F", "C", "B", "A"), 0);
        assertEquals(List.of(HistoryMirror.Cause.UNKNOWN, HistoryMirror.Cause.UNKNOWN), causes);

        // This client's own step whose state also changed the bytes (an eviction with it): not the step alone.
        causes.clear();
        tick(250);
        session.undo();
        UUID evicting = new UUID(1, ++jobs);
        server(new S2C.JobAccepted(lastReqId(), evicting, 10));
        bytes = 110;
        history(List.of("F", "C", "B", "A"), 1);
        server(new S2C.JobFinished(evicting, JobOutcome.COMPLETED, 10, 0, 0, 0));
        assertEquals(List.of(HistoryMirror.Cause.OTHER), causes);
    }
}
