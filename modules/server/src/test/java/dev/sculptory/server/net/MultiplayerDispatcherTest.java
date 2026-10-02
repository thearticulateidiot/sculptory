package dev.sculptory.server.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.Codec;
import dev.sculptory.protocol.v2.Features;
import dev.sculptory.protocol.v2.Handshake;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.Message;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.protocol.v2.ProtocolException;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.protocol.v2.StreamAbort;
import dev.sculptory.protocol.v2.StreamChunk;
import dev.sculptory.protocol.v2.StreamCredit;
import dev.sculptory.protocol.v2.StreamKind;
import dev.sculptory.protocol.v2.StreamOpen;
import dev.sculptory.protocol.v2.StreamSender;
import dev.sculptory.server.engine.ChunkPermit;
import dev.sculptory.server.engine.DabOutcome;
import dev.sculptory.server.engine.EditService;
import dev.sculptory.server.engine.JobListener;
import dev.sculptory.server.engine.JobResult;
import dev.sculptory.server.engine.JobTicket;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.PermissionService;
import dev.sculptory.server.engine.RunOptions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The protocol layer with several players connected at once (M4): every limit is per connection and exact at its
 * edge, a flooding client is disconnected without slowing anyone else, concurrent transfers never mix, and one
 * player leaving releases only what was theirs.
 */
class MultiplayerDispatcherTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final Box BOX = Box.of(new BlockPos(0, 60, 0), new BlockPos(15, 70, 15));
    private static final BrushSpec SPEC = new BrushSpec(BrushTool.RAISE, 8, 1f, Falloff.SMOOTH, Shape.CIRCLE, null,
            SurfaceMask.ANY, 0, 0, 1L);
    /** Nanoseconds for one token of the op bucket (5 per second). */
    private static final long OP_TOKEN_NANOS = 200_000_000L;

    private AtomicLong clock;
    private FakeEdits edits;
    private Granted permissions;
    private ServerDispatcher<FakePlayer> dispatcher;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(1_000_000_000L);
        edits = new FakeEdits();
        permissions = new Granted();
        dispatcher = new ServerDispatcher<>(edits, permissions, () -> Limits.DEFAULTS, () -> STATES, clock::get);
    }

    private Player connect(String name) {
        Player player = new Player(name);
        player.session = dispatcher.open(player.transport);
        player.send(Handshake.hello("test", Features.of(Features.STROKES, Features.HISTORY, Features.REGION_OPS)));
        assertTrue(player.session.ready(), name + " completed the handshake");
        player.transport.sent.clear();
        return player;
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

    // ---------------------------------------------------------------- limits

    @Test
    void rateLimitsArePerPlayerAndExactAtTheirEdges() {
        Player alice = connect("alice");
        Player bob = connect("bob");
        // The op bucket holds 10: the 10th request in one instant is served, the 11th refused.
        for (int i = 1; i <= 10; i++) alice.send(new C2S.Undo(i, ConflictPolicy.SKIP_CONFLICTS));
        assertEquals(10, alice.transport.count(S2C.JobAccepted.class));
        alice.send(new C2S.Undo(11, ConflictPolicy.SKIP_CONFLICTS));
        assertEquals(new S2C.JobRejected(11, RejectReason.RATE_LIMITED), alice.transport.last());
        // Bob's bucket is his own.
        for (int i = 1; i <= 10; i++) bob.send(new C2S.Undo(i, ConflictPolicy.SKIP_CONFLICTS));
        assertEquals(10, bob.transport.count(S2C.JobAccepted.class));
        assertEquals(0, bob.transport.count(S2C.JobRejected.class));

        // 5 per second: one nanosecond short of 200 ms is still refused, 200 ms buys exactly one more.
        clock.addAndGet(OP_TOKEN_NANOS - 1);
        alice.send(new C2S.Undo(12, ConflictPolicy.SKIP_CONFLICTS));
        assertEquals(new S2C.JobRejected(12, RejectReason.RATE_LIMITED), alice.transport.last());
        clock.addAndGet(1);
        alice.send(new C2S.Undo(13, ConflictPolicy.SKIP_CONFLICTS));
        assertEquals(S2C.JobAccepted.class, alice.transport.last().getClass());
        alice.send(new C2S.Undo(14, ConflictPolicy.SKIP_CONFLICTS));
        assertEquals(new S2C.JobRejected(14, RejectReason.RATE_LIMITED), alice.transport.last());

        // Dabs: 60 in one instant are admitted, the 61st is refused.
        alice.send(new C2S.StrokeBegin(1, SPEC));
        for (int batch = 0; batch < 4; batch++) alice.send(new C2S.Dabs(1, batch, dabs(batch * 15, 15)));
        assertEquals(4, edits.dabBatches);
        alice.send(new C2S.Dabs(1, 4, dabs(60, 1)));
        assertEquals(new S2C.StrokeStatus(1, 60, S2C.StrokeStatus.Status.REJECTED, RejectReason.RATE_LIMITED),
                alice.transport.last());
        assertEquals(4, edits.dabBatches, "the refused batch reached the engine");
        assertEquals(0, alice.session.violations(), "refusals within the flood allowance are not violations");
        assertEquals(0, bob.session.violations());
    }

    @Test
    void aFloodingClientIsDisconnectedWhileOthersAreServed() {
        Player alice = connect("alice");
        Player bob = connect("bob");
        for (int i = 1; i <= 5; i++) bob.send(fill(i));
        int reqId = 0;
        for (int i = 0; i < 10; i++) alice.send(fill(++reqId));
        assertEquals(10, alice.transport.count(S2C.JobAccepted.class));
        // Exactly FLOOD_BURST refusals are tolerated...
        for (int i = 0; i < NetSession.FLOOD_BURST; i++) alice.send(fill(++reqId));
        assertEquals(0, alice.session.violations());
        assertEquals(NetSession.FLOOD_BURST, alice.transport.count(S2C.JobRejected.class));
        // ...then further ones are dropped unanswered and are violations, one per server tick at most; the last allowed
        // violation disconnects.
        int answered = alice.transport.frames;
        for (int i = 1; i < NetSession.MAX_VIOLATIONS; i++) {
            alice.send(fill(++reqId));
            alice.send(fill(++reqId));
            dispatcher.tick(alice.session);
        }
        assertEquals(NetSession.MAX_VIOLATIONS - 1, alice.session.violations());
        assertEquals(answered, alice.transport.frames, "a flooding client gets no answer to its refused frames");
        assertNull(alice.transport.disconnected);
        alice.send(fill(++reqId));
        assertEquals("Sculptory: too many invalid messages", alice.transport.disconnected);
        assertEquals(NetSession.Stage.CLOSED, alice.session.stage());
        int frames = alice.transport.frames;
        for (int i = 0; i < 100; i++) alice.send(fill(++reqId));
        assertEquals(frames, alice.transport.frames, "a closed session is answered nothing");

        // Bob was served before, and is served after, with his own budget.
        for (int i = 6; i <= 10; i++) bob.send(fill(i));
        assertEquals(10, bob.transport.count(S2C.JobAccepted.class));
        assertEquals(0, bob.session.violations());
        assertNull(bob.transport.disconnected);
    }

    @Test
    void aBurstAfterAServerStallCostsAtMostOneViolation() {
        Player alice = connect("alice");
        // A stalled server handles every frame the client sent meanwhile in one tick: a thousand over the limits.
        for (int i = 1; i <= 1000; i++) alice.send(new C2S.Undo(i, ConflictPolicy.SKIP_CONFLICTS));
        assertEquals(1, alice.session.violations());
        assertNull(alice.transport.disconnected);
        assertEquals(NetSession.FLOOD_BURST, alice.transport.count(S2C.JobRejected.class),
                "refused requests are answered up to the allowance, the rest are dropped");
        dispatcher.tick(alice.session);
        clock.addAndGet(10_000_000_000L); // ten seconds later the allowance is back
        alice.transport.sent.clear();
        for (int i = 1001; i <= 1100; i++) alice.send(new C2S.Undo(i, ConflictPolicy.SKIP_CONFLICTS));
        assertEquals(1, alice.session.violations(), "refusals within the refilled allowance");
        assertEquals(100, alice.transport.count(S2C.JobAccepted.class) + alice.transport.count(S2C.JobRejected.class),
                "within the allowance every request is answered again");
    }

    @Test
    void aClientHittingItsLimitAtAHumanPaceIsNeverDisconnected() {
        Player masher = connect("masher");
        // 25 requests a second for ten seconds (a held button): most are refused, none counts as flooding.
        for (int i = 1; i <= 250; i++) {
            masher.send(fill(i));
            clock.addAndGet(40_000_000L);
        }
        int accepted = masher.transport.count(S2C.JobAccepted.class);
        assertTrue(accepted >= 59 && accepted <= 61, "accepted " + accepted);
        assertEquals(250 - accepted, masher.transport.count(S2C.JobRejected.class));
        assertEquals(0, masher.session.violations());
        assertNull(masher.transport.disconnected);
    }

    @Test
    void uploadChunksOverTheByteRateAbortTheUploadWithoutCountingAsFlooding() {
        Player alice = connect("alice");
        List<byte[]> completed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int id = dispatcher.grantUpload(alice.session, 1, 8 << 20, new RecordingUpload(completed, failed)).getAsInt();
        int chunk = StreamSender.MAX_C2S_CHUNK;
        alice.send(new StreamOpen(id, StreamKind.SCHEM_UPLOAD, 200L * chunk, new TreeMap<>()));
        // A client (or a burst after a server stall) sends 200 chunks at once, 6 MB: about 1 MiB is taken, the upload is
        // aborted at the first chunk over the rate, and the chunks still in flight after it are refused too.
        for (int seq = 0; seq < 200; seq++) alice.send(new StreamChunk(id, seq, new byte[chunk]));
        assertEquals(List.of("rate_limited"), failed);
        assertTrue(completed.isEmpty());
        assertEquals(1, alice.transport.count(StreamAbort.class), "aborted once; its late chunks are dropped");
        assertEquals(0, alice.session.violations(), "the chunks of an upload that just ended are not violations");
        assertNull(alice.transport.disconnected);
        assertEquals(0, alice.session.openUploads());
    }

    @Test
    void chunksOfStreamsNeverGrantedAreViolationsWhateverTheirRate() {
        Player alice = connect("alice");
        int chunk = StreamSender.MAX_C2S_CHUNK;
        // Within the byte rate: a chunk of a stream that was never granted is a violation.
        alice.send(new StreamChunk(77, 0, new byte[chunk]));
        assertEquals(1, alice.session.violations());
        // A live upload takes the rest of the 1 MiB bucket and is aborted at its first chunk over it.
        List<String> failed = new ArrayList<>();
        int live = dispatcher.grantUpload(alice.session, 1, 8 << 20, new RecordingUpload(new ArrayList<>(), failed))
                .getAsInt();
        alice.send(new StreamOpen(live, StreamKind.SCHEM_UPLOAD, 8 << 20, new TreeMap<>()));
        for (int seq = 0; failed.isEmpty(); seq++) alice.send(new StreamChunk(live, seq, new byte[chunk]));
        assertEquals(List.of("rate_limited"), failed);
        assertEquals(1, alice.session.violations(), "the live upload's refused chunk is not a violation");
        // Over the byte rate (tiny frames cost MIN_CHUNK_BYTES each): refusing them is no way around the rule.
        for (int i = 1; i < NetSession.MAX_VIOLATIONS - 1; i++) alice.send(new StreamChunk(78, i, new byte[16]));
        assertEquals(NetSession.MAX_VIOLATIONS - 1, alice.session.violations());
        assertNull(alice.transport.disconnected);
        alice.send(new StreamChunk(79, 0, new byte[16]));
        assertEquals("Sculptory: too many invalid messages", alice.transport.disconnected);
        assertEquals(1, alice.transport.count(StreamAbort.class), "only the live upload's abort is sent");
    }

    @Test
    void lateChunksAreExcusedOnlyForARecentlyEndedUploadAndOnlyForAWhile() {
        Player alice = connect("alice");
        List<String> failed = new ArrayList<>();
        int id = dispatcher.grantUpload(alice.session, 1, 1 << 20, new RecordingUpload(new ArrayList<>(), failed)).getAsInt();
        alice.send(new StreamOpen(id, StreamKind.SCHEM_UPLOAD, 100_000, new TreeMap<>()));
        alice.send(new StreamAbort(id, "user cancelled"));
        assertEquals(List.of("aborted by the client: user cancelled"), failed);
        for (int seq = 0; seq < 20; seq++) alice.send(new StreamChunk(id, seq, new byte[2048]));
        assertEquals(0, alice.session.violations(), "chunks already in flight when the client aborted");
        clock.addAndGet(NetSession.RECENT_UPLOAD_NANOS);
        alice.send(new StreamChunk(id, 20, new byte[2048]));
        assertEquals(1, alice.session.violations(), "chunks long after the upload ended");

        // A chunk before the stream is opened: a violation, and the upload fails.
        int unopened = dispatcher.grantUpload(alice.session, 2, 1 << 20, new RecordingUpload(new ArrayList<>(), failed))
                .getAsInt();
        alice.send(new StreamChunk(unopened, 0, new byte[2048]));
        assertEquals(2, alice.session.violations());
        assertEquals("invalid", failed.get(failed.size() - 1));
        assertEquals(0, alice.session.openUploads());
    }

    @Test
    void theNinthEndedUploadEvictsTheOldestRemembered() {
        Player alice = connect("alice");
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i <= NetSession.RECENT_UPLOADS; i++) {
            int id = dispatcher.grantUpload(alice.session, i, 1 << 20, new RecordingUpload(new ArrayList<>(),
                    new ArrayList<>())).getAsInt();
            alice.send(new StreamOpen(id, StreamKind.SCHEM_UPLOAD, 100_000, new TreeMap<>()));
            alice.send(new StreamAbort(id, "user cancelled"));
            ids.add(id);
        }
        assertEquals(NetSession.RECENT_UPLOADS, alice.session.endedUploads.size(), "at most the last few are remembered");
        alice.send(new StreamChunk(ids.get(1), 0, new byte[2048]));
        assertEquals(0, alice.session.violations(), "the oldest still remembered is excused");
        alice.send(new StreamChunk(ids.get(0), 0, new byte[2048]));
        assertEquals(1, alice.session.violations(), "the evicted one counts as never granted");
    }

    @Test
    void resyncNeedsUseAsWellAsBrush() {
        Player alice = connect("alice");
        permissions.granted.remove(Perm.USE);
        alice.send(new C2S.Resync(BOX));
        assertEquals(ServerDispatcher.RESYNC_NO_PERMISSION, ((S2C.Notice) alice.transport.last()).key());
        permissions.granted.add(Perm.USE);
        permissions.granted.remove(Perm.BRUSH);
        alice.send(new C2S.Resync(BOX));
        assertEquals(ServerDispatcher.RESYNC_NO_PERMISSION, ((S2C.Notice) alice.transport.last()).key());
        permissions.granted.add(Perm.BRUSH);
        alice.send(new C2S.Resync(BOX));
        assertEquals(ServerDispatcher.RESYNC_NO_STROKE, ((S2C.Notice) alice.transport.last()).key(),
                "with both nodes the request goes on (and finds no stroke)");
    }

    // ---------------------------------------------------------------- concurrent transfers

    @Test
    void twoPlayersUploadingAtOnceNeverMixTheirStreams() {
        Player alice = connect("alice");
        Player bob = connect("bob");
        List<byte[]> aliceDone = new ArrayList<>(), bobDone = new ArrayList<>();
        List<String> aliceFailed = new ArrayList<>(), bobFailed = new ArrayList<>();
        int aliceId = dispatcher.grantUpload(alice.session, 7, 4 << 20, new RecordingUpload(aliceDone, aliceFailed)).getAsInt();
        int bobId = dispatcher.grantUpload(bob.session, 7, 4 << 20, new RecordingUpload(bobDone, bobFailed)).getAsInt();
        assertEquals(aliceId, bobId, "stream ids are per connection, so both players have the same one");
        byte[] alicePayload = random(3 << 20, 11), bobPayload = random((2 << 20) + 12_345, 12);
        StreamSender aliceSender = sender(alice, aliceId, alicePayload);
        StreamSender bobSender = sender(bob, bobId, bobPayload);
        int ticks = 0;
        while (!aliceSender.done() || !bobSender.done()) {
            assertTrue(++ticks < 2000, "the uploads make progress");
            pump(alice, aliceSender);
            pump(bob, bobSender);
            clock.addAndGet(50_000_000L);
        }
        assertEquals(1, aliceDone.size());
        assertEquals(1, bobDone.size());
        assertArrayEquals(alicePayload, aliceDone.get(0));
        assertArrayEquals(bobPayload, bobDone.get(0));
        assertTrue(aliceFailed.isEmpty() && bobFailed.isEmpty());
        assertEquals(0, alice.session.violations() + bob.session.violations());
    }

    @Test
    void oneDisconnectReleasesOnlyThatPlayersStreamsAndUploads() {
        Player alice = connect("alice");
        Player bob = connect("bob");
        byte[] payload = random(1 << 20, 3);
        dispatcher.openStream(alice.session, StreamKind.CLIPBOARD_PREVIEW, payload, new TreeMap<>()).getAsInt();
        dispatcher.openStream(bob.session, StreamKind.CLIPBOARD_PREVIEW, payload, new TreeMap<>()).getAsInt();
        List<String> aliceFailed = new ArrayList<>(), bobFailed = new ArrayList<>();
        dispatcher.grantUpload(alice.session, 1, 1 << 20, new RecordingUpload(new ArrayList<>(), aliceFailed));
        dispatcher.grantUpload(bob.session, 1, 1 << 20, new RecordingUpload(new ArrayList<>(), bobFailed));
        assertEquals(2L << 20, dispatcher.outboundBytesTotal());

        dispatcher.close(alice.session);
        assertEquals(1L << 20, dispatcher.outboundBytesTotal(), "only Alice's queued bytes are released");
        assertEquals(0, alice.session.outboundStreams());
        assertEquals(List.of("disconnected"), aliceFailed);
        assertTrue(bobFailed.isEmpty(), "Bob's upload failed with Alice's connection");
        assertEquals(1, bob.session.openUploads());

        int aliceFrames = alice.transport.frames;
        dispatcher.tick(alice.session);
        dispatcher.tick(bob.session);
        assertEquals(aliceFrames, alice.transport.frames, "Alice's closed session still sends");
        assertEquals(1, bob.transport.count(StreamOpen.class));
        assertTrue(bob.transport.count(StreamChunk.class) > 0, "Bob's stream stopped with Alice's connection");
    }

    @Test
    void jobEventsOfAConnectionThatEndedReachNobody() {
        Player first = connect("alice");
        JobListener listener = dispatcher.jobListener(first.session);
        dispatcher.close(first.session);
        // The same player, reconnected, is a new session: the old connection's job is not theirs to hear about.
        Player again = connect("alice again");
        UUID job = UUID.randomUUID();
        int frames = first.transport.frames, framesAgain = again.transport.frames;
        listener.progress(job, 10, 100, Phase.APPLY);
        listener.finished(new JobResult(job, JobOutcome.COMPLETED, 100, 0, 0, 0));
        assertEquals(frames, first.transport.frames);
        assertEquals(framesAgain, again.transport.frames);
    }

    // ---------------------------------------------------------------- helpers and fakes

    private void pump(Player player, StreamSender sender) {
        for (Message m : sender.poll(StreamSender.CLIENT_BYTES_PER_TICK)) player.send((C2S) m);
        for (StreamCredit credit : player.transport.sent(StreamCredit.class)) sender.credit(credit);
        player.transport.sent.removeIf(m -> m instanceof StreamCredit);
    }

    private static StreamSender sender(Player player, int id, byte[] payload) {
        S2C.UploadGrant grant = player.transport.sent(S2C.UploadGrant.class).get(0);
        return new StreamSender(id, StreamKind.SCHEM_UPLOAD, payload, new TreeMap<>(), StreamSender.MAX_C2S_CHUNK,
                grant.creditBytes());
    }

    private static byte[] random(int size, long seed) {
        byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    /** One connected client: its transport and session. */
    private final class Player {
        final FakeTransport transport = new FakeTransport();
        final String name;
        NetSession<FakePlayer> session;

        Player(String name) {
            this.name = name;
        }

        void send(C2S message) {
            try {
                dispatcher.receive(session, Codec.encodeC2S(message, STATES));
            } catch (ProtocolException e) {
                throw new AssertionError(name + ": " + e.getMessage(), e);
            }
        }
    }

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

    private static final class FakeTransport implements ServerTransport<FakePlayer> {
        final List<S2C> sent = new ArrayList<>();
        int frames;
        String disconnected;

        @Override
        public FakePlayer player() {
            return null;
        }

        @Override
        public UUID playerId() {
            return null;
        }

        @Override
        public String playerName() {
            return null;
        }

        @Override
        public boolean canSend() {
            return true;
        }

        @Override
        public void send(byte[] frame) {
            frames++;
            try {
                sent.add(Codec.decodeS2C(frame, STATES));
            } catch (ProtocolException e) {
                throw new AssertionError("The server sent an undecodable frame", e);
            }
        }

        @Override
        public void acknowledge(int sequence) {}

        @Override
        public boolean tracks(int cx, int cz) {
            return true;
        }

        @Override
        public void resendChunk(int cx, int cz) {}

        @Override
        public void disconnect(String reason) {
            disconnected = reason;
        }

        S2C last() {
            return sent.get(sent.size() - 1);
        }

        <T> List<T> sent(Class<T> type) {
            return sent.stream().filter(type::isInstance).map(type::cast).toList();
        }

        int count(Class<?> type) {
            return (int) sent.stream().filter(type::isInstance).count();
        }
    }

    /** Every node, unless the test removes some. */
    private static final class Granted implements PermissionService<FakePlayer, Object> {
        final Set<Perm> granted = EnumSet.allOf(Perm.class);

        @Override
        public boolean has(FakePlayer p, Perm node) {
            return granted.contains(node);
        }

        @Override
        public ChunkPermit chunk(FakePlayer p, Object w, int cx, int cz, Box bounds) {
            return ChunkPermit.ALLOW;
        }
    }

    private static final class FakeEdits implements EditService<FakePlayer> {
        int dabBatches;

        @Override
        public JobTicket run(FakePlayer p, OpSpec s, RunOptions o, JobListener l) {
            return new JobTicket(UUID.randomUUID(), "Fill", 64);
        }

        @Override
        public void beginStroke(FakePlayer p, int strokeId, BrushSpec spec) {}

        @Override
        public DabOutcome dabs(FakePlayer p, int strokeId, int seq, List<Dab> dabs) {
            dabBatches++;
            return DabOutcome.accepted(dabs.get(dabs.size() - 1).index());
        }

        @Override
        public void endStroke(FakePlayer p, int strokeId) {}

        @Override
        public JobTicket undo(FakePlayer p, ConflictPolicy c) {
            return new JobTicket(UUID.randomUUID(), "Undo", 10);
        }

        @Override
        public JobTicket redo(FakePlayer p, ConflictPolicy c) {
            return new JobTicket(UUID.randomUUID(), "Redo", 10);
        }

        @Override
        public boolean cancel(FakePlayer p, UUID jobId) {
            return true;
        }
    }
}
