package dev.sculptory.fabric.client.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.fabric.client.editor.tool.ToolId;
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
import dev.sculptory.protocol.v2.StreamAbort;
import dev.sculptory.protocol.v2.StreamAssembler;
import dev.sculptory.protocol.v2.StreamChunk;
import dev.sculptory.protocol.v2.StreamCredit;
import dev.sculptory.protocol.v2.StreamEnd;
import dev.sculptory.protocol.v2.StreamKind;
import dev.sculptory.protocol.v2.StreamOpen;
import dev.sculptory.protocol.v2.StreamSender;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.net.PreviewPayload;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The M2 requests of {@link FabricEditorSession} against a scripted server. */
class FabricEditorSessionM2Test {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final long MS = 1_000_000L;
    private static final Box BOX = Box.of(new BlockPos(0, 60, 0), new BlockPos(9, 70, 9));
    private static final BlockPos ORIGIN = new BlockPos(5, 60, 5);
    private static final UUID JOB = new UUID(3, 3);
    private static final UUID CLIP = new UUID(5, 5);
    private static final String HASH = "ab".repeat(32);
    private static final Features ALL = Features.of(Features.STROKES, Features.REGION_OPS, Features.HISTORY,
            Features.CLIPBOARD, Features.SCHEMATICS, Features.LIBRARY);

    private FakeTransport transport;
    private AtomicLong clock;
    private FabricEditorSession session;
    private final List<Notice> notices = new ArrayList<>();
    private final List<Runnable> decodes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        transport = new FakeTransport();
        clock = new AtomicLong(10_000 * MS);
        session = new FabricEditorSession(transport, () -> STATES, clock::get, "0.3.0-test");
        session.onNotice(notices::add);
    }

    private void connect(Features features, Limits limits) {
        session.onJoin();
        server(new S2C.Welcome(ProtocolV2.VERSION, features, limits, Perm.mask(EnumSet.allOf(Perm.class)), 1L));
        assertEquals(SessionState.READY, session.state());
        transport.sent.clear();
    }

    private void connect() {
        connect(ALL, Limits.DEFAULTS);
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

    /** The result, which must have arrived (a test must not hang on it). */
    private static <T> T joined(CompletionStage<T> stage) {
        assertTrue(done(stage), "the result has arrived");
        return stage.toCompletableFuture().join();
    }

    /** Streams {@code sender}'s messages to the session, giving it credit back, until it has ended. */
    private void stream(StreamSender sender, long untilBytes) {
        for (int round = 0; round < 1_000 && !sender.done() && sender.sentBytes() < untilBytes; round++) {
            for (Message message : sender.poll(Math.min(StreamSender.SERVER_BYTES_PER_TICK, untilBytes - sender.sentBytes()))) {
                server((S2C) message);
            }
            for (C2S message : List.copyOf(transport.sent)) {
                if (message instanceof StreamCredit credit && credit.id() == sender.id()) sender.credit(credit);
            }
            transport.sent.removeIf(m -> m instanceof StreamCredit);
        }
    }

    private static byte[] preview() {
        Clipboard.Builder builder = Clipboard.builder(STATES, new BlockPos(20, 4, 20)).anchor(new BlockPos(10, 0, 10));
        Random random = new Random(3);
        for (int i = 0; i < 400; i++) {
            builder.set(random.nextInt(20), random.nextInt(4), random.nextInt(20), STATES.state("minecraft:stone"));
        }
        builder.set(0, 0, 0, STATES.state("minecraft:oak_stairs[facing=west]"));
        return PreviewPayload.encode(builder.build());
    }

    private static TreeMap<String, String> meta(String... pairs) {
        TreeMap<String, String> meta = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) meta.put(pairs[i], pairs[i + 1]);
        return meta;
    }

    // ---------------------------------------------------------------- copy and cut

    @Test
    void aCopyCompletesOnClipboardReadyAndReplacesTheClipboard() {
        connect();
        CompletionStage<Reply<ClipboardCache.Entry>> first = session.copy(BOX, ORIGIN, false);
        C2S.Copy copy = sent(C2S.Copy.class);
        assertEquals(new C2S.Copy(copy.reqId(), BOX, ORIGIN, false, CellMask.ANY), copy);
        assertFalse(done(first));
        server(new S2C.ClipboardReady(copy.reqId(), CLIP, new BlockPos(10, 11, 10), new BlockPos(5, 0, 5), 1100, 4400));
        ClipboardCache.Entry entry = now(first).toOptional().orElseThrow();
        assertEquals(new ClipboardCache.Entry(CLIP, new BlockPos(10, 11, 10), new BlockPos(5, 0, 5), 1100, 4400), entry);
        assertEquals(entry, session.clipboards().current().orElseThrow());

        CompletionStage<Reply<ClipboardCache.Entry>> second = session.copy(BOX, ORIGIN, false);
        UUID next = new UUID(6, 6);
        server(new S2C.ClipboardReady(sent(C2S.Copy.class).reqId(), next, new BlockPos(1, 1, 1), BlockPos.ORIGIN, 1, 4));
        assertEquals(next, now(second).toOptional().orElseThrow().clipboardId());
        assertTrue(session.clipboards().get(CLIP).isEmpty(), "each copy replaces the clipboard; the old id is dead");
        assertTrue(notices.isEmpty(), "success toasts are the caller's");
    }

    @Test
    void aCutFollowsItsEraseJob() {
        connect();
        CompletionStage<Reply<ClipboardCache.Entry>> cut = session.copy(BOX, ORIGIN, true);
        C2S.Copy copy = sent(C2S.Copy.class);
        assertTrue(copy.cut());
        server(new S2C.JobAccepted(copy.reqId(), JOB, 1100));
        assertEquals("Cut", session.jobs().job(JOB).orElseThrow().label());
        assertFalse(done(cut), "the clipboard is not ready yet");
        server(new S2C.ClipboardReady(copy.reqId(), CLIP, new BlockPos(10, 11, 10), ORIGIN, 1100, 4400));
        assertTrue(now(cut).isOk());
        server(new S2C.JobFinished(JOB, JobOutcome.COMPLETED, 1100, 0, 0, 0));
        assertEquals(List.of(Notice.of(Notice.Level.SUCCESS, SessionNotices.JOB_FINISHED, "Cut", "1,100", "0")), notices);
    }

    @Test
    void shapeRegionsAndEntityFiltersTravelInTheCopy() {
        connect();
        Region.Shape ball = new Region.Shape(BOX, ShapeKind.ELLIPSOID, Facing.UP);
        CompletionStage<Reply<ClipboardCache.Entry>> copy = session.copy(ball, ORIGIN, true, EntityFilter.ALL);
        C2S.Copy sent = sent(C2S.Copy.class);
        assertEquals(new C2S.Copy(sent.reqId(), ball, ORIGIN, true, CellMask.ANY, EntityFilter.ALL), sent);
        server(new S2C.ClipboardReady(sent.reqId(), CLIP, new BlockPos(10, 11, 10), new BlockPos(5, 0, 5), 700, 2800, 3));
        assertTrue(now(copy).isOk());
        // The box form is a cuboid without entities.
        session.copy(BOX, ORIGIN, false);
        C2S.Copy box = sent(C2S.Copy.class);
        assertEquals(new C2S.Copy(box.reqId(), new Region.Cuboid(BOX), ORIGIN, false, CellMask.ANY, EntityFilter.NONE), box);
    }

    // ---------------------------------------------------------------- selections (regions)

    private static CellSet selection() {
        CellSet.Builder builder = CellSet.builder();
        Random random = new Random(12);
        for (int i = 0; i < 500; i++) builder.add(random.nextInt(40), 60 + random.nextInt(10), random.nextInt(40));
        return builder.build();
    }

    private static Region.Uploaded reference(CellSet set) {
        return new Region.Uploaded(set.hash(), set.bounds(), set.size());
    }

    /** Answers the selection upload in flight: grants it on {@code streamId}, lets it stream, and confirms it. */
    private void acceptSelection(CellSet set, int streamId) throws ProtocolException {
        C2S.SelectionUpload upload = sent(C2S.SelectionUpload.class);
        byte[] bytes = set.encode();
        assertEquals(new C2S.SelectionUpload(upload.reqId(), set.hash(), set.bounds(), set.size(), bytes.length), upload);
        server(new S2C.UploadGrant(upload.reqId(), streamId, StreamAssembler.DEFAULT_WINDOW));
        for (int i = 0; i < 20 && allSent(StreamEnd.class).stream().noneMatch(e -> e.id() == streamId); i++) tick(50);
        StreamOpen open = allSent(StreamOpen.class).stream().filter(o -> o.id() == streamId).findFirst().orElseThrow();
        assertEquals(new StreamOpen(streamId, StreamKind.SELECTION_UPLOAD, bytes.length, meta()), open);
        StreamAssembler assembler = new StreamAssembler(open, 64L << 20, Long.MAX_VALUE / 4);
        for (StreamChunk chunk : allSent(StreamChunk.class)) {
            if (chunk.id() == streamId) assembler.accept(chunk);
        }
        StreamEnd end = allSent(StreamEnd.class).stream().filter(e -> e.id() == streamId).findFirst().orElseThrow();
        assertArrayEquals(bytes, assembler.finish(end), "the set's encoding, every byte");
        server(new S2C.SelectionReady(upload.reqId(), set.hash()));
    }

    @Test
    void anOpOnACellSetUploadsTheSetOnceThenNamesIt() throws ProtocolException {
        connect();
        CellSet set = selection();
        OpSpec.Erase erase = new OpSpec.Erase(new Region.Cells(set), CellMask.ANY);
        CompletionStage<ToolResult> first = session.send(new ToolAction.RunOp(erase));
        assertTrue(allSent(C2S.RunOp.class).isEmpty(), "the op waits for its selection");
        assertEquals(1, session.transfers().size(), "the upload shows as a transfer");
        assertEquals(Transfer.Kind.UPLOAD, session.transfers().get(0).kind());
        acceptSelection(set, 7);
        C2S.RunOp run = sent(C2S.RunOp.class);
        assertEquals(new OpSpec.Erase(reference(set), CellMask.ANY), run.op(), "the server's copy of the set is named");
        assertFalse(done(first));
        server(new S2C.JobAccepted(run.reqId(), JOB, set.size()));
        assertEquals(new ToolResult.Accepted(JOB, set.size()), first.toCompletableFuture().join());
        assertTrue(session.transfers().isEmpty());

        // The set is held on this connection: the next op goes out at once.
        transport.sent.clear();
        session.send(new ToolAction.RunOp(new OpSpec.Fill(new Region.Cells(set),
                new Pattern.Single(STATES.state("minecraft:stone")), CellMask.ANY)));
        assertTrue(allSent(C2S.SelectionUpload.class).isEmpty(), "not uploaded again");
        assertEquals(reference(set), ((OpSpec.Fill) sent(C2S.RunOp.class).op()).region());
        assertTrue(notices.isEmpty());

        // A new connection holds nothing.
        session.onDisconnect();
        connect();
        session.send(new ToolAction.RunOp(erase));
        assertEquals(1, allSent(C2S.SelectionUpload.class).size());
    }

    @Test
    void anOpOnASetTheServerDroppedUploadsItAgainAndRetriesOnce() throws ProtocolException {
        connect();
        CellSet set = selection();
        OpSpec.Erase erase = new OpSpec.Erase(new Region.Cells(set), CellMask.ANY);
        session.send(new ToolAction.RunOp(erase));
        acceptSelection(set, 7);
        server(new S2C.JobAccepted(sent(C2S.RunOp.class).reqId(), JOB, 1));

        transport.sent.clear();
        CompletionStage<ToolResult> result = session.send(new ToolAction.RunOp(erase));
        server(new S2C.JobRejected(sent(C2S.RunOp.class).reqId(), RejectReason.SELECTION_NOT_LOADED));
        assertFalse(done(result), "the set goes up again first");
        acceptSelection(set, 8);
        assertEquals(2, allSent(C2S.RunOp.class).size(), "the op was sent again");
        server(new S2C.JobRejected(sent(C2S.RunOp.class).reqId(), RejectReason.SELECTION_NOT_LOADED));
        assertEquals(new ToolResult.Rejected(RejectReason.SELECTION_NOT_LOADED, ""), result.toCompletableFuture().join(),
                "only one retry");
        assertEquals(1, allSent(C2S.SelectionUpload.class).size());
        tick(1_000);
        assertTrue(notices.isEmpty(), "the caller reports the refusal");
    }

    @Test
    void aRefusedUploadRefusesTheOpWithoutASecondToast() {
        connect();
        CellSet set = selection();
        CompletionStage<ToolResult> result = session.send(new ToolAction.RunOp(new OpSpec.Erase(new Region.Cells(set),
                CellMask.ANY)));
        int reqId = sent(C2S.SelectionUpload.class).reqId();
        server(new S2C.JobRejected(reqId, RejectReason.TOO_LARGE));
        server(new S2C.Notice(S2C.Notice.Level.WARN, ClipboardTransfers.REQUEST_REFUSED,
                List.of("TOO_LARGE", "3000000 > 2097152 blocks in a selection")));
        assertEquals(new ToolResult.Rejected(RejectReason.TOO_LARGE, ""), result.toCompletableFuture().join());
        tick(1_000);
        assertTrue(notices.isEmpty(), "the op's caller toasts its refusal: " + notices);
        assertTrue(allSent(C2S.RunOp.class).isEmpty());

        // Leaving while the set goes up refuses the op as disconnected.
        CompletionStage<ToolResult> again = session.send(new ToolAction.RunOp(new OpSpec.Erase(new Region.Cells(set),
                CellMask.ANY)));
        session.onDisconnect();
        assertEquals(RejectReason.DISABLED, assertInstanceOf(ToolResult.Rejected.class, again.toCompletableFuture().join())
                .reason());
    }

    @Test
    void aCopyOfACellSetUploadsItAndRetriesOnceWhenDropped() throws ProtocolException {
        connect();
        CellSet set = selection();
        CompletionStage<Reply<ClipboardCache.Entry>> copy = session.copy(new Region.Cells(set), ORIGIN, false,
                EntityFilter.DECORATIONS);
        assertTrue(allSent(C2S.Copy.class).isEmpty());
        acceptSelection(set, 7);
        C2S.Copy first = sent(C2S.Copy.class);
        assertEquals(new C2S.Copy(first.reqId(), reference(set), ORIGIN, false, CellMask.ANY, EntityFilter.DECORATIONS),
                first);
        server(new S2C.JobRejected(first.reqId(), RejectReason.SELECTION_NOT_LOADED));
        assertFalse(done(copy));
        acceptSelection(set, 8);
        C2S.Copy second = sent(C2S.Copy.class);
        assertTrue(second.reqId() != first.reqId());
        server(new S2C.ClipboardReady(second.reqId(), CLIP, new BlockPos(40, 10, 40), new BlockPos(5, 0, 5), set.size(), 4096));
        assertEquals(CLIP, now(copy).toOptional().orElseThrow().clipboardId());
        tick(1_000);
        assertTrue(notices.isEmpty(), "a retried refusal is not toasted: " + notices);
    }

    @Test
    void aSecondUseWhileTheSetGoesUpWaitsForTheSameUpload() throws ProtocolException {
        connect();
        CellSet set = selection();
        session.send(new ToolAction.RunOp(new OpSpec.Erase(new Region.Cells(set), CellMask.ANY)));
        CompletionStage<Reply<ClipboardCache.Entry>> copy = session.copy(new Region.Cells(set), ORIGIN, false,
                EntityFilter.NONE);
        assertEquals(1, allSent(C2S.SelectionUpload.class).size(), "one upload for both");
        acceptSelection(set, 7);
        assertEquals(1, allSent(C2S.RunOp.class).size());
        assertEquals(1, allSent(C2S.Copy.class).size());
        assertFalse(done(copy));
        // A confirmation for another set is not taken for this one.
        server(new S2C.SelectionReady(999, Sha256.digest(new byte[] {1})));
        assertEquals(SessionState.READY, session.state());
    }

    private static final OpSpec.Fill FILL_BOX = new OpSpec.Fill(new Region.Cuboid(BOX),
            new Pattern.Single(STATES.state("minecraft:stone")), CellMask.ANY);

    /** The requests sent, in order, without the upload's own messages. */
    private List<C2S> requests() {
        return transport.sent.stream()
                .filter(m -> m instanceof C2S.RunOp || m instanceof C2S.Undo || m instanceof C2S.Redo
                        || m instanceof C2S.Copy)
                .toList();
    }

    @Test
    void requestsAfterAnOpOnASetGoingUpWaitForItAndKeepTheirOrder() throws ProtocolException {
        // Review fix 6: a later op, undo or copy must not reach the server before an op whose set is still going up.
        connect();
        CellSet set = selection();
        CompletionStage<ToolResult> first = session.send(new ToolAction.RunOp(new OpSpec.Erase(new Region.Cells(set),
                CellMask.ANY)));
        CompletionStage<ToolResult> second = session.send(new ToolAction.RunOp(FILL_BOX));
        session.undo();
        CompletionStage<Reply<ClipboardCache.Entry>> copy = session.copy(BOX, ORIGIN, false);
        tick(50);
        assertEquals(List.of(), requests(), "everything waits behind the upload");
        assertFalse(done(second));
        assertFalse(done(copy));

        acceptSelection(set, 7);
        List<C2S> requests = requests();
        assertEquals(4, requests.size(), requests.toString());
        assertEquals(new OpSpec.Erase(reference(set), CellMask.ANY), assertInstanceOf(C2S.RunOp.class, requests.get(0)).op());
        assertEquals(FILL_BOX, assertInstanceOf(C2S.RunOp.class, requests.get(1)).op());
        assertInstanceOf(C2S.Undo.class, requests.get(2));
        assertInstanceOf(C2S.Copy.class, requests.get(3));
        server(new S2C.JobAccepted(((C2S.RunOp) requests.get(1)).reqId(), JOB, 1100));
        assertEquals(new ToolResult.Accepted(JOB, 1100), joined(second));
        assertFalse(done(first), "each keeps its own reply");

        // Nothing is held back any more: the next op goes out at once.
        session.send(new ToolAction.RunOp(FILL_BOX));
        assertEquals(5, requests().size());
    }

    @Test
    void requestsHeldBehindAFailedUploadStillGoInOrder() {
        connect();
        CellSet set = selection();
        CompletionStage<ToolResult> first = session.send(new ToolAction.RunOp(new OpSpec.Erase(new Region.Cells(set),
                CellMask.ANY)));
        session.send(new ToolAction.RunOp(FILL_BOX));
        session.redo();
        assertEquals(List.of(), requests());
        server(new S2C.JobRejected(sent(C2S.SelectionUpload.class).reqId(), RejectReason.TOO_LARGE));
        assertEquals(RejectReason.TOO_LARGE, assertInstanceOf(ToolResult.Rejected.class, joined(first))
                .reason());
        List<C2S> requests = requests();
        assertEquals(2, requests.size(), requests.toString());
        assertEquals(FILL_BOX, assertInstanceOf(C2S.RunOp.class, requests.get(0)).op());
        assertInstanceOf(C2S.Redo.class, requests.get(1));
    }

    @Test
    void heldBackRequestsEndWithTheConnectionAndTheNextOneStartsClean() {
        connect();
        CellSet set = selection();
        CompletionStage<ToolResult> first = session.send(new ToolAction.RunOp(new OpSpec.Erase(new Region.Cells(set),
                CellMask.ANY)));
        CompletionStage<ToolResult> held = session.send(new ToolAction.RunOp(FILL_BOX));
        CompletionStage<Reply<ClipboardCache.Entry>> copy = session.copy(BOX, ORIGIN, false);
        session.undo();
        session.onDisconnect();
        assertEquals(RejectReason.DISABLED, assertInstanceOf(ToolResult.Rejected.class, joined(first))
                .reason());
        assertEquals(RejectReason.DISABLED, assertInstanceOf(ToolResult.Rejected.class, joined(held))
                .reason());
        assertEquals(Reply.Failure.DISCONNECTED, assertInstanceOf(Reply.Failed.class, now(copy)).failure());
        assertEquals(List.of(), requests(), "nothing held back was sent");

        connect();
        session.send(new ToolAction.RunOp(FILL_BOX));
        assertEquals(1, requests().size(), "nothing is held back on the next connection");
    }

    @Test
    void aSetIsEncodedOffTheRenderThreadOnceAndItsEncodingKeptForARetry() throws ProtocolException {
        // Review fix 7: encoding a large set must not stall the render thread, nor run again for the retry. Re-review
        // fix 9: it runs on its own executor, not the preview decoder's (the game's worker pool).
        List<Runnable> encodes = new ArrayList<>();
        session = new FabricEditorSession(transport, () -> STATES, clock::get, "0.3.0-test", decodes::add, encodes::add,
                PreviewDecoder.Limits.DEFAULT);
        session.onNotice(notices::add);
        connect();
        CellSet set = selection();
        CompletionStage<ToolResult> result = session.send(new ToolAction.RunOp(new OpSpec.Erase(new Region.Cells(set),
                CellMask.ANY)));
        assertEquals(1, encodes.size(), "the set is encoded on the encoder executor");
        assertTrue(decodes.isEmpty(), "not on the decoder's");
        assertTrue(transport.sent.isEmpty(), "nothing goes out before it is encoded");
        encodes.remove(0).run();
        assertTrue(transport.sent.isEmpty(), "encoded, but sent from the render thread");
        tick(50);
        acceptSelection(set, 7);
        server(new S2C.JobRejected(sent(C2S.RunOp.class).reqId(), RejectReason.SELECTION_NOT_LOADED));
        assertTrue(encodes.isEmpty(), "the retry uploads the kept encoding");
        acceptSelection(set, 8);
        assertEquals(2, allSent(C2S.RunOp.class).size());
        assertFalse(done(result));

        // A disconnect while a set is being encoded ends its op; the late encoding is dropped.
        CompletionStage<ToolResult> late = session.send(new ToolAction.RunOp(new OpSpec.Erase(
                new Region.Cells(CellSet.builder().add(1, 60, 1).build()), CellMask.ANY)));
        assertEquals(1, encodes.size());
        session.onDisconnect();
        assertTrue(done(late), "the op ends with the connection, not when its encoding arrives");
        assertEquals(RejectReason.DISABLED, assertInstanceOf(ToolResult.Rejected.class, joined(late))
                .reason());
        encodes.remove(0).run();
        connect();
        tick(50);
        assertTrue(allSent(C2S.SelectionUpload.class).isEmpty(), "the late encoding is dropped");
    }

    @Test
    void aSetOverTheServersSelectionCapsIsRefusedHereBeforeEncoding() {
        // Decision (b): the server's selection caps come in the Welcome; nothing is encoded or sent past them.
        session = new FabricEditorSession(transport, () -> STATES, clock::get, "0.3.0-test", decodes::add);
        session.onNotice(notices::add);
        connect(ALL, new Limits(2_097_152L, 2_097_152L, 32, 20, 32L << 20, 2, 100, 1 << 16));
        CompletionStage<ToolResult> tooMany = session.send(new ToolAction.RunOp(new OpSpec.Erase(
                new Region.Cells(selection()), CellMask.ANY)));
        ToolResult.Rejected refused = assertInstanceOf(ToolResult.Rejected.class, joined(tooMany));
        assertEquals(RejectReason.TOO_LARGE, refused.reason());
        assertEquals(selection().size() + " > 100 blocks in a selection", refused.detail());
        assertTrue(decodes.isEmpty());
        assertTrue(transport.sent.isEmpty());

        session.onDisconnect();
        connect(ALL, new Limits(2_097_152L, 2_097_152L, 32, 20, 32L << 20, 2, 2_097_152L, 2));
        CellSet.Builder spread = CellSet.builder();
        for (int i = 0; i < 3; i++) spread.add(i * 100, 60, 0);
        CompletionStage<Reply<ClipboardCache.Entry>> copy = session.copy(new Region.Cells(spread.build()), ORIGIN, false,
                EntityFilter.NONE);
        assertEquals(new Reply.Refused<>(RejectReason.TOO_LARGE, "a selection touching 3 > 2 sections"), now(copy));
        assertTrue(decodes.isEmpty());
        assertTrue(transport.sent.isEmpty());
    }

    // ---------------------------------------------------------------- request order (re-review of 53522004)

    private static final OpSpec.Fill FILL_OTHER = new OpSpec.Fill(new Region.Cuboid(Box.of(new BlockPos(20, 60, 20),
            new BlockPos(25, 65, 25))), new Pattern.Single(STATES.state("minecraft:stone")), CellMask.ANY);
    private static final BrushSpec STROKE = new BrushSpec(BrushTool.RAISE, 4, 1f, Falloff.SMOOTH, Shape.CIRCLE, null,
            SurfaceMask.ANY, 0, 0, 1L);

    private CompletionStage<ToolResult> eraseOf(CellSet set) {
        return session.send(new ToolAction.RunOp(new OpSpec.Erase(new Region.Cells(set), CellMask.ANY)));
    }

    @Test
    void escAndStopDropUndoRedoAndJumpsHeldBehindAnUpload() throws ProtocolException {
        // Re-review 1: presses held behind a selection's upload are dropped like queued ones (Esc, History's Stop).
        connect();
        CellSet set = selection();
        eraseOf(set);
        session.send(new ToolAction.RunOp(FILL_BOX));
        session.undo();
        session.redo();
        session.jumpTo(-2);
        assertTrue(session.historyBusy(), "held presses show as busy");
        session.dropQueuedHistorySteps();
        acceptSelection(set, 7);
        tick(1_000);
        List<C2S> requests = requests();
        assertEquals(2, requests.size(), "the erase and the fill, and no step: " + requests);
        assertEquals(FILL_BOX, assertInstanceOf(C2S.RunOp.class, requests.get(1)).op());
        assertFalse(session.historyBusy());
    }

    @Test
    void anOpRunningFromTheHeldRequestsKeepsThePressesMadeAfterIt() throws ProtocolException {
        // A held op drops only steps queued before it; the presses held after it are the player's later ones.
        connect();
        CellSet set = selection();
        eraseOf(set);
        session.send(new ToolAction.RunOp(FILL_BOX));
        session.undo();
        acceptSelection(set, 7);
        List<C2S> requests = requests();
        assertEquals(3, requests.size(), requests.toString());
        assertInstanceOf(C2S.Undo.class, requests.get(2));
    }

    @Test
    void strokesAreRefusedWhileAnEarlierOpWaitsForItsUpload() throws ProtocolException {
        // Re-review 2: a stroke would reach the server before the op the player made first.
        connect();
        CellSet set = selection();
        eraseOf(set);
        notices.clear();
        FabricStrokeHandle stroke = (FabricStrokeHandle) session.beginStroke(ToolId.RAISE, STROKE, StrokeParams.DEFAULT);
        assertEquals(RejectReason.QUEUE_FULL, stroke.rejectReason());
        assertFalse(stroke.active());
        assertTrue(allSent(C2S.StrokeBegin.class).isEmpty());
        assertEquals(List.of(Notice.of(Notice.Level.INFO, FabricEditorSession.STROKE_WAITS_FOR_SELECTION)), notices);
        acceptSelection(set, 7);
        FabricStrokeHandle later = (FabricStrokeHandle) session.beginStroke(ToolId.RAISE, STROKE, StrokeParams.DEFAULT);
        assertEquals(null, later.rejectReason());
        assertEquals(1, allSent(C2S.StrokeBegin.class).size());
        List<C2S> order = transport.sent.stream().filter(m -> m instanceof C2S.RunOp || m instanceof C2S.StrokeBegin).toList();
        assertInstanceOf(C2S.RunOp.class, order.get(0), "the op first");
    }

    @Test
    void aDroppedSelectionIsNotRetriedOnceLaterRequestsWentOut() throws ProtocolException {
        // Re-review 3: retrying then would put the op after requests the player made later.
        connect();
        CellSet set = selection();
        CompletionStage<ToolResult> erase = eraseOf(set);
        acceptSelection(set, 7);
        int eraseId = sent(C2S.RunOp.class).reqId();
        session.send(new ToolAction.RunOp(FILL_BOX));
        server(new S2C.JobRejected(eraseId, RejectReason.SELECTION_NOT_LOADED));
        assertEquals(RejectReason.SELECTION_NOT_LOADED,
                assertInstanceOf(ToolResult.Rejected.class, joined(erase)).reason(), "the caller asks to run it again");
        assertEquals(1, allSent(C2S.SelectionUpload.class).size(), "not uploaded again");
        assertEquals(2, allSent(C2S.RunOp.class).size());

        // The same for a copy: its refusal is then toasted.
        CompletionStage<Reply<ClipboardCache.Entry>> copy = session.copy(new Region.Cells(set), ORIGIN, false,
                EntityFilter.NONE);
        int copyId = sent(C2S.Copy.class).reqId();
        session.send(new ToolAction.RunOp(FILL_OTHER));
        notices.clear();
        server(new S2C.JobRejected(copyId, RejectReason.SELECTION_NOT_LOADED));
        assertEquals(RejectReason.SELECTION_NOT_LOADED, assertInstanceOf(Reply.Refused.class, now(copy)).reason());
        tick(1_000);
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.reject.selection_not_loaded")), notices);
        assertEquals(1, allSent(C2S.SelectionUpload.class).size());
    }

    @Test
    void aRetryHoldsTheRequestsMadeMeanwhile() throws ProtocolException {
        // Re-review 3: the retry counts as a hold, so what the player does while the set goes up again follows it.
        connect();
        CellSet set = selection();
        eraseOf(set);
        acceptSelection(set, 7);
        server(new S2C.JobRejected(sent(C2S.RunOp.class).reqId(), RejectReason.SELECTION_NOT_LOADED));
        assertEquals(2, allSent(C2S.SelectionUpload.class).size(), "uploaded again");
        session.send(new ToolAction.RunOp(FILL_BOX));
        assertEquals(1, allSent(C2S.RunOp.class).size(), "the fill waits for the retry");
        acceptSelection(set, 8);
        List<C2S.RunOp> runs = allSent(C2S.RunOp.class);
        assertEquals(3, runs.size());
        assertEquals(new OpSpec.Erase(reference(set), CellMask.ANY), runs.get(1).op(), "the retry first");
        assertEquals(FILL_BOX, runs.get(2).op());
    }

    @Test
    void aSelectionUploadStartsBeforeQueuedRequests() {
        // Re-review 4: the requests held behind it should not also wait behind the queue.
        connect();
        session.libraryList("a");
        session.libraryList("b");
        session.libraryList("c"); // queued: both slots are taken
        CellSet set = selection();
        eraseOf(set);
        assertTrue(allSent(C2S.SelectionUpload.class).isEmpty(), "no free slot yet");
        server(new S2C.LibraryListing(allSent(C2S.LibraryList.class).get(0).reqId(), "a", List.of(), false));
        assertEquals(1, allSent(C2S.SelectionUpload.class).size(), "the upload took the free slot");
        assertEquals(2, allSent(C2S.LibraryList.class).size(), "the list asked for before it still waits");
    }

    @Test
    void heldRequestsShowAsBusyWithAHintOnce() {
        connect();
        CellSet set = selection();
        eraseOf(set);
        assertTrue(session.historyBusy(), "the mirror lags behind the waiting op");
        assertTrue(notices.isEmpty(), "nothing held yet");
        session.send(new ToolAction.RunOp(FILL_BOX));
        session.send(new ToolAction.RunOp(FILL_OTHER));
        assertEquals(List.of(Notice.of(Notice.Level.INFO, FabricEditorSession.WAITING_FOR_SELECTION)), notices);
    }

    @Test
    void aHoldThatNeverEndsGivesUpAfterItsTimeoutAndNothingGoesOutOfOrder() throws ProtocolException {
        // Re-review 4: the hold is bounded. Here the encoding never finishes in time.
        List<Runnable> encodes = new ArrayList<>();
        session = new FabricEditorSession(transport, () -> STATES, clock::get, "0.3.0-test", Runnable::run, encodes::add,
                PreviewDecoder.Limits.DEFAULT);
        session.onNotice(notices::add);
        connect();
        CellSet set = selection();
        CompletionStage<ToolResult> erase = eraseOf(set);
        CompletionStage<ToolResult> fill = session.send(new ToolAction.RunOp(FILL_BOX));
        CompletionStage<Reply<ClipboardCache.Entry>> copy = session.copy(BOX, ORIGIN, false);
        session.undo();
        notices.clear();
        tick(FabricEditorSession.HOLD_TIMEOUT_NANOS / MS - 1_000);
        assertFalse(done(fill));
        tick(2_000);
        assertEquals(new ToolResult.Rejected(RejectReason.QUEUE_FULL, FabricEditorSession.GAVE_UP), joined(fill));
        assertEquals(Reply.Failure.TIMED_OUT, assertInstanceOf(Reply.Failed.class, now(copy)).failure());
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, FabricEditorSession.WAIT_TIMED_OUT)), notices);
        assertFalse(session.historyBusy());
        // The erase's set arrives late: the erase is not sent, since what came after it was dropped.
        encodes.remove(0).run();
        tick(50);
        acceptSelection(set, 7);
        assertEquals(new ToolResult.Rejected(RejectReason.QUEUE_FULL, FabricEditorSession.GAVE_UP), joined(erase));
        assertEquals(List.of(), requests());
        // Nothing waits any more.
        session.send(new ToolAction.RunOp(FILL_OTHER));
        assertEquals(1, requests().size());
    }

    @Test
    void aCopyHoldsLaterRequestsUntilItsRequestGoesOut() {
        // Re-review 5: a copy waiting for a request slot is not out yet; a fill after it must not overtake it.
        connect();
        session.libraryList("a");
        session.libraryList("b");
        CompletionStage<Reply<ClipboardCache.Entry>> cut = session.copy(BOX, ORIGIN, true);
        session.send(new ToolAction.RunOp(FILL_BOX));
        assertEquals(List.of(), requests(), "the cut waits for a slot, the fill for the cut");
        server(new S2C.LibraryListing(allSent(C2S.LibraryList.class).get(0).reqId(), "a", List.of(), false));
        List<C2S> requests = requests();
        assertEquals(2, requests.size(), requests.toString());
        assertTrue(assertInstanceOf(C2S.Copy.class, requests.get(0)).cut());
        assertEquals(FILL_BOX, assertInstanceOf(C2S.RunOp.class, requests.get(1)).op());
        assertFalse(done(cut));
    }

    @Test
    void aHeldRequestThatFailsDoesNotHoldUpTheOthers() throws ProtocolException {
        // Re-review 8: one held request throwing must not leave the rest held until the connection ends.
        connect();
        transport.throwsOn = m -> m instanceof C2S.RunOp run && run.op().equals(FILL_BOX);
        CellSet set = selection();
        eraseOf(set);
        CompletionStage<ToolResult> failing = session.send(new ToolAction.RunOp(FILL_BOX));
        session.send(new ToolAction.RunOp(FILL_OTHER));
        acceptSelection(set, 7);
        assertEquals(RejectReason.INVALID, assertInstanceOf(ToolResult.Rejected.class, joined(failing)).reason());
        List<C2S.RunOp> runs = allSent(C2S.RunOp.class);
        assertEquals(2, runs.size(), "the erase and the fill after the failing one");
        assertEquals(FILL_OTHER, runs.get(1).op());
        assertFalse(session.historyBusy());
    }

    /**
     * A copy refused only because the player's brush stroke is still being applied (the server's stroke_pending notice)
     * is not reported: it goes out again a moment later, and a fill the player made meanwhile waits behind it.
     */
    @Test
    void aCopyRefusedForTheStrokeIsSentAgainBeforeLaterRequests() {
        connect();
        CompletionStage<Reply<ClipboardCache.Entry>> copy = session.copy(BOX, ORIGIN, false);
        int reqId = sent(C2S.Copy.class).reqId();
        server(new S2C.Notice(S2C.Notice.Level.INFO, SessionNotices.STROKE_PENDING, List.of(Integer.toString(reqId))));
        server(new S2C.JobRejected(reqId, RejectReason.QUEUE_FULL));
        server(new S2C.Notice(S2C.Notice.Level.WARN, ClipboardTransfers.REQUEST_REFUSED,
                List.of("QUEUE_FULL", "a large brush stroke is still being applied")));
        assertFalse(done(copy), "the refusal is not the copy's answer");
        session.send(new ToolAction.RunOp(FILL_BOX));
        assertTrue(session.historyBusy(), "requests wait behind the copy");
        tick(100);
        assertEquals(1, requests().size(), "not sent again yet, and the fill waits: " + requests());
        tick(200);
        List<C2S> requests = requests();
        assertEquals(3, requests.size(), requests.toString());
        assertInstanceOf(C2S.Copy.class, requests.get(1));
        assertEquals(FILL_BOX, assertInstanceOf(C2S.RunOp.class, requests.get(2)).op());
        tick(1_000);
        assertTrue(notices.isEmpty(), "nothing was shown: " + notices);
        assertFalse(done(copy));
    }

    /** Still refused for the stroke ten seconds after it first went out, a copy's refusal is reported after all. */
    @Test
    void aCopyStillRefusedForTheStrokeAfterTenSecondsIsReported() {
        connect();
        CompletionStage<Reply<ClipboardCache.Entry>> copy = session.copy(BOX, ORIGIN, false);
        for (int i = 0; i < 60 && !done(copy); i++) {
            int reqId = sent(C2S.Copy.class).reqId();
            server(new S2C.Notice(S2C.Notice.Level.INFO, SessionNotices.STROKE_PENDING, List.of(Integer.toString(reqId))));
            server(new S2C.JobRejected(reqId, RejectReason.QUEUE_FULL));
            tick(300);
        }
        assertEquals(RejectReason.QUEUE_FULL, assertInstanceOf(Reply.Refused.class, now(copy)).reason());
        int sends = allSent(C2S.Copy.class).size();
        assertTrue(sends >= 30 && sends <= 45, "sent " + sends + " times in about ten seconds");
        tick(1_000);
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.reject.queue_full")), notices);
    }

    @Test
    void aRefusalReadsWithTheServersDetailOnce() {
        connect();
        CompletionStage<Reply<ClipboardCache.Entry>> copy = session.copy(BOX, ORIGIN, false);
        int reqId = sent(C2S.Copy.class).reqId();
        server(new S2C.JobRejected(reqId, RejectReason.PROTECTED));
        server(new S2C.Notice(S2C.Notice.Level.WARN, ClipboardTransfers.REQUEST_REFUSED, List.of("PROTECTED", "chunk 3,4")));
        assertEquals(new Reply.Refused<>(RejectReason.PROTECTED, ""), now(copy));
        tick(1_000);
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.reject.protected.detail", "chunk 3,4")), notices);
    }

    @Test
    void aRefusalWithoutADetailNoticeGetsTheGenericToast() {
        connect();
        CompletionStage<Reply<ClipboardCache.Entry>> copy = session.copy(BOX, ORIGIN, false);
        server(new S2C.JobRejected(sent(C2S.Copy.class).reqId(), RejectReason.RATE_LIMITED));
        assertEquals(RejectReason.RATE_LIMITED, assertInstanceOf(Reply.Refused.class, now(copy)).reason());
        tick(100);
        assertTrue(notices.isEmpty(), "the server's notice may still come");
        tick(300);
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.reject.rate_limited")), notices);
    }

    // ---------------------------------------------------------------- pacing and timeouts

    @Test
    void atMostTwoRequestsAreInFlight() {
        connect();
        CompletionStage<Reply<LibraryFolder>> root = session.libraryList("");
        session.libraryList("trees");
        CompletionStage<Reply<ClipboardCache.Entry>> load = session.libraryLoad("trees/oak.schem");
        assertEquals(2, allSent(C2S.LibraryList.class).size());
        assertTrue(allSent(C2S.LibraryLoad.class).isEmpty(), "the third waits for a free slot");
        server(new S2C.LibraryListing(allSent(C2S.LibraryList.class).get(0).reqId(), "", List.of(), false));
        assertTrue(now(root).isOk());
        C2S.LibraryLoad sentLoad = sent(C2S.LibraryLoad.class);
        assertEquals("trees/oak.schem", sentLoad.path());
        server(new S2C.ClipboardReady(sentLoad.reqId(), CLIP, new BlockPos(3, 3, 3), new BlockPos(1, 0, 1), 27, 108));
        assertEquals(CLIP, now(load).toOptional().orElseThrow().clipboardId());
    }

    @Test
    void anUnansweredRequestTimesOutAndFreesItsSlot() {
        connect();
        CompletionStage<Reply<LibraryFolder>> first = session.libraryList("a");
        session.libraryList("b");
        session.libraryList("c");
        tick(29_000);
        assertFalse(done(first));
        tick(2_000);
        assertEquals(new Reply.Failed<>(Reply.Failure.TIMED_OUT, "no answer for 30 s"), now(first));
        assertEquals(List.of("a", "b", "c"), allSent(C2S.LibraryList.class).stream().map(C2S.LibraryList::folder).toList());
        assertEquals(Notice.of(Notice.Level.WARNING, "sculptory.transfer.timed_out", "Library"), notices.get(0));
    }

    @Test
    void requestsTheServerDoesNotOfferAreRefusedHere() {
        connect(Features.of(Features.STROKES, Features.CLIPBOARD), Limits.DEFAULTS);
        CompletionStage<Reply<LibraryFolder>> list = session.libraryList("");
        assertEquals(RejectReason.DISABLED, assertInstanceOf(Reply.Refused.class, now(list)).reason());
        assertTrue(transport.sent.isEmpty());
        assertEquals("sculptory.reject.disabled.detail", notices.get(0).key());
    }

    // ---------------------------------------------------------------- previews

    @Test
    void aPreviewIsStreamedWithCreditAndDecodedIntoTheCache() {
        connect();
        byte[] payload = preview();
        SourceRef source = new SourceRef.Clipboard(CLIP);
        Transfer<ClipboardCache.Preview> transfer = session.requestPreview(source);
        assertEquals(new C2S.PreviewRequest(source), sent(C2S.PreviewRequest.class));
        assertEquals(0, transfer.totalBytes());
        assertEquals(List.of(transfer), session.transfers());

        StreamSender sender = new StreamSender(4, StreamKind.CLIPBOARD_PREVIEW, payload,
                meta("clipboardId", CLIP.toString(), "contentHash", "content-1", "format", "bspv1"),
                StreamSender.MAX_S2C_CHUNK, StreamAssembler.DEFAULT_WINDOW);
        stream(sender, Long.MAX_VALUE);
        assertEquals(payload.length, transfer.totalBytes());
        ClipboardCache.Preview preview = now(transfer.result()).toOptional().orElseThrow();
        assertEquals("content-1", preview.key());
        assertEquals(new BlockPos(20, 4, 20), preview.dims());
        assertEquals(new BlockPos(10, 0, 10), preview.anchor());
        assertEquals(STATES.state("minecraft:oak_stairs[facing=west]"), preview.volume().handle(0, 0, 0));
        assertEquals(preview, session.clipboards().preview(source).orElseThrow());
        assertTrue(session.transfers().isEmpty());

        transport.sent.clear();
        Transfer<ClipboardCache.Preview> again = session.requestPreview(source);
        assertTrue(again.finished(), "a cached preview completes at once");
        assertTrue(transport.sent.isEmpty(), "and is not downloaded again");
    }

    @Test
    void previewsAreDecodedOffTheRenderThreadAndInstalledAtTheNextTick() {
        session = new FabricEditorSession(transport, () -> STATES, clock::get, "0.3.0-test", decodes::add);
        session.onNotice(notices::add);
        connect();
        Transfer<ClipboardCache.Preview> transfer = session.requestPreview(new SourceRef.Clipboard(CLIP));
        stream(new StreamSender(4, StreamKind.CLIPBOARD_PREVIEW, preview(), meta("clipboardId", CLIP.toString(),
                "contentHash", "c"), StreamSender.MAX_S2C_CHUNK, StreamAssembler.DEFAULT_WINDOW), Long.MAX_VALUE);
        assertEquals(1, decodes.size());
        assertFalse(transfer.finished());
        decodes.remove(0).run();
        assertFalse(transfer.finished(), "decoded, but installed on the render thread");
        tick(50);
        assertTrue(transfer.finished());
        assertTrue(session.clipboards().preview("c").isPresent());
    }

    @Test
    void onlyOnePreviewWaitsForItsStreamAtATime() {
        connect();
        SourceRef first = new SourceRef.Clipboard(CLIP);
        SourceRef second = new SourceRef.Asset(HASH);
        session.requestPreview(first);
        Transfer<ClipboardCache.Preview> same = session.requestPreview(first);
        session.requestPreview(second);
        assertEquals(List.of(new C2S.PreviewRequest(first)), allSent(C2S.PreviewRequest.class),
                "a refusal would not say which preview it is about");
        assertEquals(2, session.transfers().size(), "asking twice for one preview shares the transfer");
        assertTrue(session.transfers().contains(same));
        server(new StreamOpen(4, StreamKind.CLIPBOARD_PREVIEW, 5_000, meta("clipboardId", CLIP.toString())));
        assertEquals(new C2S.PreviewRequest(second), sent(C2S.PreviewRequest.class), "its stream opened: the next goes");
    }

    @Test
    void aPreviewRefusalFailsTheWaitingPreviewAndReadsPlainly() {
        connect();
        Transfer<ClipboardCache.Preview> transfer = session.requestPreview(new SourceRef.Asset(HASH));
        server(new S2C.Notice(S2C.Notice.Level.WARN, ClipboardTransfers.PREVIEW_REFUSED, List.of("INVALID", "unknown asset")));
        assertEquals(new Reply.Refused<>(RejectReason.INVALID, "unknown asset"), now(transfer.result()));
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.reject.invalid.detail", "unknown asset")), notices);
    }

    @Test
    void cancellingAPreviewAbortsItsStreamQuietly() {
        connect();
        Transfer<ClipboardCache.Preview> transfer = session.requestPreview(new SourceRef.Clipboard(CLIP));
        StreamSender sender = new StreamSender(4, StreamKind.CLIPBOARD_PREVIEW, new byte[200_000],
                meta("clipboardId", CLIP.toString()), StreamSender.MAX_S2C_CHUNK, StreamAssembler.DEFAULT_WINDOW);
        stream(sender, 40_000);
        assertTrue(transfer.progress() > 0 && transfer.progress() < 1, "progress: " + transfer.progress());
        transfer.cancel();
        assertEquals(Reply.Failure.CANCELLED, assertInstanceOf(Reply.Failed.class, now(transfer.result())).failure());
        assertEquals(new StreamAbort(4, "cancelled"), sent(StreamAbort.class));
        assertTrue(notices.isEmpty());
        assertTrue(session.transfers().isEmpty());
    }

    @Test
    void oversizedPreviewStreamsAreRefused() {
        connect();
        Transfer<ClipboardCache.Preview> transfer = session.requestPreview(new SourceRef.Clipboard(CLIP));
        server(new StreamOpen(4, StreamKind.CLIPBOARD_PREVIEW, (32L << 20) + 1, meta("clipboardId", CLIP.toString())));
        assertEquals(new StreamAbort(4, "too_large"), sent(StreamAbort.class));
        assertEquals(Reply.Failure.ABORTED, assertInstanceOf(Reply.Failed.class, now(transfer.result())).failure());
    }

    // ---------------------------------------------------------------- export

    @Test
    void anExportStreamsTheFileMatchedByItsRequestId() {
        connect();
        Transfer<ExportedFile> transfer = session.export(CLIP);
        C2S.ExportClipboard request = sent(C2S.ExportClipboard.class);
        assertEquals(CLIP, request.clipboardId());
        byte[] file = new byte[300_000];
        new Random(8).nextBytes(file);
        // Another request's file first: not this one.
        server(new StreamOpen(8, StreamKind.SCHEM_FILE, 10, meta("reqId", Integer.toString(request.reqId() + 50))));
        StreamSender sender = new StreamSender(9, StreamKind.SCHEM_FILE, file, meta("reqId",
                Integer.toString(request.reqId()), "fileName", "clipboard.schem", "clipboardId", CLIP.toString()),
                StreamSender.MAX_S2C_CHUNK, StreamAssembler.DEFAULT_WINDOW);
        stream(sender, 100_000);
        assertEquals(300_000, transfer.totalBytes());
        assertTrue(transfer.doneBytes() >= 100_000 && transfer.doneBytes() < 300_000);
        stream(sender, Long.MAX_VALUE);
        ExportedFile exported = now(transfer.result()).toOptional().orElseThrow();
        assertEquals("clipboard.schem", exported.fileName());
        assertEquals(CLIP, exported.clipboardId());
        assertArrayEquals(file, exported.bytes());
    }

    @Test
    void aRefusedExportIsReported() {
        connect();
        Transfer<ExportedFile> transfer = session.export(CLIP);
        int reqId = sent(C2S.ExportClipboard.class).reqId();
        server(new S2C.JobRejected(reqId, RejectReason.NO_PERMISSION));
        server(new S2C.Notice(S2C.Notice.Level.WARN, ClipboardTransfers.REQUEST_REFUSED,
                List.of("NO_PERMISSION", "sculptory.schematic.export")));
        assertEquals(RejectReason.NO_PERMISSION, assertInstanceOf(Reply.Refused.class, now(transfer.result())).reason());
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.reject.no_permission.detail",
                "sculptory.schematic.export")), notices);
    }

    // ---------------------------------------------------------------- upload

    /** The upload bytes as the server would reassemble them from what the client sent. */
    private byte[] uploaded(int streamId) throws ProtocolException {
        StreamOpen open = allSent(StreamOpen.class).stream().filter(o -> o.id() == streamId).findFirst().orElseThrow();
        StreamAssembler assembler = new StreamAssembler(open, 64L << 20, Long.MAX_VALUE / 4);
        for (StreamChunk chunk : allSent(StreamChunk.class)) assembler.accept(chunk);
        return assembler.finish(sent(StreamEnd.class));
    }

    @Test
    void anUploadStreamsWithinItsGrantAndCompletesWithTheClipboard() throws ProtocolException {
        connect();
        byte[] file = new byte[100_000];
        new Random(2).nextBytes(file);
        Transfer<ClipboardCache.Entry> transfer = session.upload("house.schem", file);
        C2S.UploadBegin begin = sent(C2S.UploadBegin.class);
        assertEquals(new C2S.UploadBegin(begin.reqId(), "house.schem", 100_000), begin);
        assertTrue(allSent(StreamOpen.class).isEmpty(), "nothing streams before the grant");
        assertEquals(100_000, transfer.totalBytes());
        assertEquals(0, transfer.doneBytes());

        server(new S2C.UploadGrant(begin.reqId(), 7, StreamAssembler.DEFAULT_WINDOW));
        StreamOpen open = sent(StreamOpen.class);
        assertEquals(new StreamOpen(7, StreamKind.SCHEM_UPLOAD, 100_000, meta("fileName", "house.schem")), open);
        long firstTick = allSent(StreamChunk.class).stream().mapToLong(StreamChunk::length).sum();
        assertTrue(firstTick > 0 && firstTick <= StreamSender.CLIENT_BYTES_PER_TICK, "paced: " + firstTick);
        assertEquals(firstTick, transfer.doneBytes());
        for (int i = 0; i < 10 && allSent(StreamEnd.class).isEmpty(); i++) tick(50);
        assertEquals(100_000, transfer.doneBytes());
        assertArrayEquals(file, uploaded(7), "every byte, with the right SHA-256");
        assertFalse(transfer.finished(), "the server still has to read it");

        server(new S2C.ClipboardReady(begin.reqId(), CLIP, new BlockPos(4, 4, 4), new BlockPos(2, 0, 2), 64, 256));
        server(new S2C.UploadResult(begin.reqId(), CLIP, null));
        assertEquals(CLIP, now(transfer.result()).toOptional().orElseThrow().clipboardId());
        assertEquals(CLIP, session.clipboards().current().orElseThrow().clipboardId());
        assertTrue(notices.isEmpty());
    }

    /** Generators: a sparse clipboard upload goes as a file upload does, on its own stream kind and without a file name. */
    @Test
    void aGeneratedUploadStreamsAndCompletesWithTheClipboard() throws ProtocolException {
        connect();
        byte[] payload = new byte[50_000];
        new Random(5).nextBytes(payload);
        Box bounds = new Box(new BlockPos(0, 60, 0), new BlockPos(9, 61, 9));
        Transfer<ClipboardCache.Entry> transfer = session.uploadGenerated(bounds, 120, payload);
        C2S.GeneratedUpload begin = sent(C2S.GeneratedUpload.class);
        assertEquals(new C2S.GeneratedUpload(begin.reqId(), bounds, 120, 50_000), begin);
        assertEquals(Transfer.Kind.UPLOAD, transfer.kind());
        assertTrue(allSent(StreamOpen.class).isEmpty(), "nothing streams before the grant");
        server(new S2C.UploadGrant(begin.reqId(), 9, StreamAssembler.DEFAULT_WINDOW));
        assertEquals(new StreamOpen(9, StreamKind.GENERATED_UPLOAD, 50_000, new TreeMap<>()), sent(StreamOpen.class));
        for (int i = 0; i < 10 && allSent(StreamEnd.class).isEmpty(); i++) tick(50);
        assertArrayEquals(payload, uploaded(9));
        assertFalse(transfer.finished(), "the server still has to read it");
        server(new S2C.ClipboardReady(begin.reqId(), CLIP, new BlockPos(10, 2, 10), BlockPos.ORIGIN, 120, 512));
        server(new S2C.UploadResult(begin.reqId(), CLIP, null));
        ClipboardCache.Entry entry = now(transfer.result()).toOptional().orElseThrow();
        assertEquals(CLIP, entry.clipboardId());
        assertEquals(120, entry.cells());
        assertEquals(CLIP, session.clipboards().current().orElseThrow().clipboardId());
        assertTrue(notices.isEmpty());

        // A decode failure is a refusal with the server's detail, toasted once.
        Transfer<ClipboardCache.Entry> bad = session.uploadGenerated(bounds, 120, payload);
        int reqId = sent(C2S.GeneratedUpload.class).reqId();
        server(new S2C.UploadGrant(reqId, 10, StreamAssembler.DEFAULT_WINDOW));
        for (int i = 0; i < 10 && allSent(StreamEnd.class).size() < 2; i++) tick(50);
        server(new S2C.UploadResult(reqId, null, "INVALID: the generated blocks could not be read: block entities"));
        Reply.Refused<ClipboardCache.Entry> refused = assertInstanceOf(Reply.Refused.class, now(bad.result()));
        assertEquals(RejectReason.INVALID, refused.reason());
        assertEquals(1, notices.size());
        assertEquals("sculptory.reject.invalid.detail", notices.get(0).key());

        // Refused locally over the upload cap, and for an impossible announcement.
        Transfer<ClipboardCache.Entry> huge = session.uploadGenerated(bounds, 120, new byte[(int) Transfer.MAX_UPLOAD_BYTES + 1]);
        assertEquals(RejectReason.TOO_LARGE, assertInstanceOf(Reply.Refused.class, now(huge.result())).reason());
        Transfer<ClipboardCache.Entry> empty = session.uploadGenerated(bounds, 120, new byte[0]);
        assertEquals(RejectReason.INVALID, assertInstanceOf(Reply.Refused.class, now(empty.result())).reason());
        assertEquals(2, allSent(C2S.GeneratedUpload.class).size(), "neither was sent");
    }

    @Test
    void anUploadWithoutCreditStallsAndTimesOut() {
        connect();
        Transfer<ClipboardCache.Entry> transfer = session.upload("big.schem", new byte[200_000]);
        int reqId = sent(C2S.UploadBegin.class).reqId();
        server(new S2C.UploadGrant(reqId, 7, 40_000));
        tick(50);
        tick(50);
        assertEquals(40_000, transfer.doneBytes(), "no further than the credit");
        tick(20_000);
        server(new StreamCredit(7, 50_000));
        tick(50);
        tick(50);
        // One tick's worth; the 848 bytes of credit left are less than a chunk may be, so they wait.
        assertEquals(40_000 + StreamSender.CLIENT_BYTES_PER_TICK, transfer.doneBytes(), "credit keeps it going");
        tick(29_000);
        assertFalse(transfer.finished(), "credit counted as progress");
        tick(2_000);
        assertEquals(Reply.Failure.TIMED_OUT, assertInstanceOf(Reply.Failed.class, now(transfer.result())).failure());
        assertEquals(new StreamAbort(7, "stalled"), sent(StreamAbort.class));
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.transfer.timed_out", "Upload")), notices);
        int sentBefore = transport.sent.size();
        server(new StreamCredit(7, 1_000_000));
        tick(50);
        assertEquals(sentBefore, transport.sent.size(), "nothing more is sent for a timed-out upload");
    }

    @Test
    void anUploadRefusedBeforeItsGrantSendsNothing() {
        connect();
        Transfer<ClipboardCache.Entry> transfer = session.upload("x.schem", new byte[10]);
        int reqId = sent(C2S.UploadBegin.class).reqId();
        server(new S2C.JobRejected(reqId, RejectReason.NO_PERMISSION));
        server(new S2C.Notice(S2C.Notice.Level.WARN, ClipboardTransfers.REQUEST_REFUSED,
                List.of("NO_PERMISSION", "sculptory.schematic.import")));
        assertEquals(RejectReason.NO_PERMISSION, assertInstanceOf(Reply.Refused.class, now(transfer.result())).reason());
        tick(1_000);
        assertTrue(allSent(StreamOpen.class).isEmpty());
        assertEquals(1, notices.size());
    }

    @Test
    void anUploadTheServerCouldNotReadIsRefusedWithItsReason() {
        connect();
        Transfer<ClipboardCache.Entry> transfer = session.upload("x.schem", new byte[5_000]);
        int reqId = sent(C2S.UploadBegin.class).reqId();
        server(new S2C.UploadGrant(reqId, 7, StreamAssembler.DEFAULT_WINDOW));
        server(new S2C.UploadResult(reqId, null, "TOO_LARGE: 3000000 > 2097152 blocks"));
        assertEquals(new Reply.Refused<>(RejectReason.TOO_LARGE, "3000000 > 2097152 blocks"), now(transfer.result()));
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.reject.too_large.detail",
                "3000000 > 2097152 blocks")), notices);
    }

    @Test
    void anUploadAbortedByTheServerFails() {
        connect();
        Transfer<ClipboardCache.Entry> transfer = session.upload("x.schem", new byte[100_000]);
        int reqId = sent(C2S.UploadBegin.class).reqId();
        server(new S2C.UploadGrant(reqId, 7, StreamAssembler.DEFAULT_WINDOW));
        server(new StreamAbort(7, "rate_limited"));
        assertEquals(new Reply.Failed<>(Reply.Failure.ABORTED, "rate_limited"), now(transfer.result()));
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.transfer.aborted", "Upload")), notices);
        int sentBefore = transport.sent.size();
        tick(50);
        assertEquals(sentBefore, transport.sent.size());
        server(new S2C.UploadResult(reqId, null, "upload failed: rate_limited"));
        assertEquals(1, notices.size(), "the late result is not reported again");
    }

    @Test
    void anUploadOverTheServerLimitIsRefusedHere() {
        connect(ALL, new Limits(2_097_152L, 2_097_152L, 32, 20, 1_000, 2));
        Transfer<ClipboardCache.Entry> transfer = session.upload("x.schem", new byte[2_000]);
        assertEquals(RejectReason.TOO_LARGE, assertInstanceOf(Reply.Refused.class, now(transfer.result())).reason());
        assertTrue(transport.sent.isEmpty());
        assertEquals("sculptory.reject.too_large.detail", notices.get(0).key());
    }

    @Test
    void cancellingAGrantedUploadAbortsItsStream() {
        connect();
        Transfer<ClipboardCache.Entry> transfer = session.upload("x.schem", new byte[100_000]);
        int reqId = sent(C2S.UploadBegin.class).reqId();
        server(new S2C.UploadGrant(reqId, 7, StreamAssembler.DEFAULT_WINDOW));
        transfer.cancel();
        assertEquals(new StreamAbort(7, "cancelled"), sent(StreamAbort.class));
        int sentBefore = transport.sent.size();
        tick(50);
        assertEquals(sentBefore, transport.sent.size());
        assertTrue(notices.isEmpty());
    }

    // ---------------------------------------------------------------- library

    @Test
    void libraryListingLoadingAndSaving() {
        connect();
        CompletionStage<Reply<LibraryFolder>> list = session.libraryList("trees");
        C2S.LibraryList request = sent(C2S.LibraryList.class);
        assertEquals("trees", request.folder());
        List<S2C.LibraryListing.Entry> entries = List.of(new S2C.LibraryListing.Entry("trees/big", true, 0, ""),
                new S2C.LibraryListing.Entry("trees/oak.schem", false, 2048, HASH));
        server(new S2C.LibraryListing(request.reqId(), "trees", entries, true));
        assertEquals(new LibraryFolder("trees", entries, true), now(list).toOptional().orElseThrow());

        CompletionStage<Reply<SavedAsset>> save = session.saveAsset(CLIP, "trees/new.schem");
        C2S.SaveAsset saveRequest = sent(C2S.SaveAsset.class);
        assertEquals(new C2S.SaveAsset(saveRequest.reqId(), CLIP, "trees/new.schem"), saveRequest);
        String own = "_players/0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0/trees/new.schem";
        server(new S2C.AssetSaved(saveRequest.reqId(), own, HASH));
        assertEquals(new SavedAsset(own, HASH), now(save).toOptional().orElseThrow(), "the path actually written");
    }

    // ---------------------------------------------------------------- a hostile server

    @Test
    void unsolicitedAndImpossibleClipboardsAreIgnored() {
        connect();
        server(new S2C.ClipboardReady(999, CLIP, new BlockPos(4, 4, 4), BlockPos.ORIGIN, 64, 256));
        assertTrue(session.clipboards().current().isEmpty(), "nobody asked for it");
        CompletionStage<Reply<ClipboardCache.Entry>> copy = session.copy(BOX, ORIGIN, false);
        int reqId = sent(C2S.Copy.class).reqId();
        for (S2C.ClipboardReady impossible : List.of(
                new S2C.ClipboardReady(reqId, CLIP, new BlockPos(0, 5, 5), BlockPos.ORIGIN, 0, 0),
                new S2C.ClipboardReady(reqId, CLIP, new BlockPos(-3, 5, 5), BlockPos.ORIGIN, 0, 0),
                new S2C.ClipboardReady(reqId, CLIP, new BlockPos(70_000, 5, 5), BlockPos.ORIGIN, 0, 0),
                new S2C.ClipboardReady(reqId, CLIP, new BlockPos(5, 5, 5), new BlockPos(40_000_000, 0, 0), 1, 0),
                new S2C.ClipboardReady(reqId, CLIP, new BlockPos(2, 2, 2), BlockPos.ORIGIN, 9, 0),
                new S2C.ClipboardReady(reqId, CLIP, new BlockPos(2, 2, 2), BlockPos.ORIGIN, -1, 0))) {
            server(impossible);
        }
        assertFalse(done(copy));
        assertTrue(session.clipboards().current().isEmpty(), "Ctrl+V has nothing that could make it throw");
        tick(31_000);
        assertEquals(Reply.Failure.TIMED_OUT, assertInstanceOf(Reply.Failed.class, now(copy)).failure());
    }

    @Test
    void invalidLibraryEntriesAreDropped() {
        connect();
        CompletionStage<Reply<LibraryFolder>> list = session.libraryList("trees");
        int reqId = sent(C2S.LibraryList.class).reqId();
        S2C.LibraryListing.Entry good = new S2C.LibraryListing.Entry("trees/oak.schem", false, 10, HASH);
        S2C.LibraryListing.Entry unhashed = new S2C.LibraryListing.Entry("trees/new.schem", false, 10, "");
        S2C.LibraryListing.Entry folder = new S2C.LibraryListing.Entry("trees/big", true, 0, "");
        server(new S2C.LibraryListing(reqId, "somewhere/else", List.of(good, unhashed, folder,
                new S2C.LibraryListing.Entry("trees/x.schem", false, 10, "not-a-hash"),
                new S2C.LibraryListing.Entry("trees/y.schem", false, 10, HASH.toUpperCase()),
                new S2C.LibraryListing.Entry("trees/big2", true, 0, HASH),
                new S2C.LibraryListing.Entry("../evil.schem", false, 10, HASH),
                new S2C.LibraryListing.Entry("rocks/z.schem", false, 10, HASH),
                new S2C.LibraryListing.Entry("trees/a/b.schem", false, 10, HASH),
                new S2C.LibraryListing.Entry("trees/w.txt", false, 10, HASH),
                new S2C.LibraryListing.Entry("trees/w.NBT", false, 10, HASH),
                new S2C.LibraryListing.Entry("", true, 0, "")), false));
        LibraryFolder listed = now(list).toOptional().orElseThrow();
        assertEquals("trees", listed.folder(), "the folder asked for, whatever the server says");
        assertEquals(List.of(good, unhashed, folder), listed.entries());
        assertTrue(LibraryFolder.asset("not-a-hash").isEmpty());
        assertTrue(LibraryFolder.asset(HASH.toUpperCase()).isEmpty());
        assertTrue(LibraryFolder.asset(null).isEmpty());
        assertEquals(new SourceRef.Asset(HASH), LibraryFolder.asset(HASH).orElseThrow());
    }

    @Test
    void streamsNobodyAskedForAreAbortedAndHoldNothing() {
        connect();
        server(new StreamOpen(4, StreamKind.CLIPBOARD_PREVIEW, 5_000, meta("clipboardId", CLIP.toString())));
        assertEquals(new StreamAbort(4, "unwanted"), sent(StreamAbort.class));
        server(new StreamOpen(5, StreamKind.SCHEM_UPLOAD, 5_000, meta()));
        assertEquals(new StreamAbort(5, "unwanted"), sent(StreamAbort.class));
        server(new StreamOpen(6, StreamKind.SCHEM_FILE, 5_000, meta("reqId", "1")));
        assertEquals(new StreamAbort(6, "unwanted"), sent(StreamAbort.class));

        Transfer<ClipboardCache.Preview> empty = session.requestPreview(new SourceRef.Clipboard(CLIP));
        server(new StreamOpen(7, StreamKind.CLIPBOARD_PREVIEW, 0, meta("clipboardId", CLIP.toString())));
        assertEquals(new StreamAbort(7, "invalid"), sent(StreamAbort.class));
        assertEquals(Reply.Failure.ABORTED, assertInstanceOf(Reply.Failed.class, now(empty.result())).failure());
        assertTrue(session.transfers().isEmpty());
    }

    @Test
    void aTricklingStreamHitsItsDeadline() {
        connect();
        Transfer<ClipboardCache.Preview> transfer = session.requestPreview(new SourceRef.Clipboard(CLIP));
        StreamSender sender = new StreamSender(4, StreamKind.CLIPBOARD_PREVIEW, new byte[100_000],
                meta("clipboardId", CLIP.toString()), StreamSender.MAX_C2S_CHUNK / 16, StreamAssembler.DEFAULT_WINDOW);
        for (int i = 0; i < 5 && !transfer.finished(); i++) {
            for (Message message : sender.poll(2_048)) server((S2C) message);
            tick(20_000);
        }
        assertEquals(new Reply.Failed<>(Reply.Failure.TIMED_OUT, "not finished in time"), now(transfer.result()));
        assertEquals(new StreamAbort(4, "deadline"), sent(StreamAbort.class));
    }

    @Test
    void aPreviewMustMatchItsClipboard() {
        connect();
        CompletionStage<Reply<ClipboardCache.Entry>> copy = session.copy(BOX, ORIGIN, false);
        // What preview() encodes: 20 x 4 x 20, anchored at (10, 0, 10), with the cells it holds.
        byte[] payload = preview();
        long cells;
        try {
            cells = PreviewDecoder.decode("k", payload, STATES).cells();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        server(new S2C.ClipboardReady(sent(C2S.Copy.class).reqId(), CLIP, new BlockPos(20, 4, 20), new BlockPos(10, 0, 9),
                cells, 100));
        assertTrue(now(copy).isOk());
        Transfer<ClipboardCache.Preview> wrong = session.requestPreview(new SourceRef.Clipboard(CLIP));
        stream(new StreamSender(4, StreamKind.CLIPBOARD_PREVIEW, payload, meta("clipboardId", CLIP.toString(),
                "contentHash", "c"), StreamSender.MAX_S2C_CHUNK, StreamAssembler.DEFAULT_WINDOW), Long.MAX_VALUE);
        Reply.Failed<?> failed = assertInstanceOf(Reply.Failed.class, now(wrong.result()));
        assertEquals(Reply.Failure.CORRUPT, failed.failure());
        assertTrue(failed.detail().contains("anchor"), failed.detail());
        assertTrue(session.clipboards().preview("c").isEmpty(), "not cached");

        UUID other = new UUID(9, 9);
        CompletionStage<Reply<ClipboardCache.Entry>> second = session.copy(BOX, ORIGIN, false);
        server(new S2C.ClipboardReady(sent(C2S.Copy.class).reqId(), other, new BlockPos(20, 4, 20),
                new BlockPos(10, 0, 10), cells, 100));
        assertTrue(now(second).isOk());
        Transfer<ClipboardCache.Preview> right = session.requestPreview(new SourceRef.Clipboard(other));
        stream(new StreamSender(5, StreamKind.CLIPBOARD_PREVIEW, payload, meta("clipboardId", other.toString(),
                "contentHash", "c"), StreamSender.MAX_S2C_CHUNK, StreamAssembler.DEFAULT_WINDOW), Long.MAX_VALUE);
        assertTrue(now(right.result()).isOk());
    }

    @Test
    void aDecoderThatRunsOutOfMemoryFailsTheRequestAndFreesItsSlot() {
        ParsingStateSpace exploding = new ParsingStateSpace(STATES, spec -> {
            throw new OutOfMemoryError("Java heap space");
        });
        session = new FabricEditorSession(transport, () -> exploding, clock::get, "0.3.0-test");
        session.onNotice(notices::add);
        connect();
        Transfer<ClipboardCache.Preview> first = session.requestPreview(new SourceRef.Clipboard(CLIP));
        stream(new StreamSender(4, StreamKind.CLIPBOARD_PREVIEW, preview(), meta("clipboardId", CLIP.toString()),
                StreamSender.MAX_S2C_CHUNK, StreamAssembler.DEFAULT_WINDOW), Long.MAX_VALUE);
        assertEquals(new Reply.Failed<>(Reply.Failure.CORRUPT, "Java heap space"), now(first.result()));
        assertTrue(session.transfers().isEmpty(), "nothing is left decoding");
        session.requestPreview(new SourceRef.Asset(HASH));
        assertEquals(new C2S.PreviewRequest(new SourceRef.Asset(HASH)), sent(C2S.PreviewRequest.class));
    }

    @Test
    void aDecodeThatNeverFinishesTimesOut() {
        session = new FabricEditorSession(transport, () -> STATES, clock::get, "0.3.0-test", decodes::add);
        session.onNotice(notices::add);
        connect();
        Transfer<ClipboardCache.Preview> transfer = session.requestPreview(new SourceRef.Clipboard(CLIP));
        stream(new StreamSender(4, StreamKind.CLIPBOARD_PREVIEW, preview(), meta("clipboardId", CLIP.toString(),
                "contentHash", "c"), StreamSender.MAX_S2C_CHUNK, StreamAssembler.DEFAULT_WINDOW), Long.MAX_VALUE);
        tick(59_000);
        assertFalse(transfer.finished());
        tick(2_000);
        assertEquals(Reply.Failure.TIMED_OUT, assertInstanceOf(Reply.Failed.class, now(transfer.result())).failure());
        assertTrue(session.transfers().isEmpty());
        decodes.remove(0).run();
        tick(50);
        assertTrue(session.clipboards().preview("c").isEmpty(), "the late result is dropped");
    }

    @Test
    void aLatePreviewRefusalAfterATimeoutDoesNotFailTheNextPreview() {
        connect();
        Transfer<ClipboardCache.Preview> first = session.requestPreview(new SourceRef.Clipboard(CLIP));
        tick(31_000);
        assertEquals(Reply.Failure.TIMED_OUT, assertInstanceOf(Reply.Failed.class, now(first.result())).failure());
        notices.clear();
        Transfer<ClipboardCache.Preview> next = session.requestPreview(new SourceRef.Asset(HASH));
        server(new S2C.Notice(S2C.Notice.Level.WARN, ClipboardTransfers.PREVIEW_REFUSED, List.of("QUEUE_FULL", "late")));
        assertFalse(next.finished(), "the late refusal was the timed-out preview's");
        assertTrue(notices.isEmpty(), "and was already reported as a timeout");
        server(new S2C.Notice(S2C.Notice.Level.WARN, ClipboardTransfers.PREVIEW_REFUSED, List.of("INVALID", "unknown asset")));
        assertEquals(new Reply.Refused<>(RejectReason.INVALID, "unknown asset"), now(next.result()));
    }

    @Test
    void aWaitingPreviewDoesNotHoldUpOtherRequests() {
        connect();
        session.requestPreview(new SourceRef.Clipboard(CLIP));
        session.requestPreview(new SourceRef.Asset(HASH));
        session.export(CLIP);
        assertEquals(1, allSent(C2S.PreviewRequest.class).size(), "the second preview waits");
        assertEquals(1, allSent(C2S.ExportClipboard.class).size(), "the export behind it does not");
    }

    @Test
    void uploadsAreCappedWhateverTheServerAllows() {
        connect(ALL, new Limits(2_097_152L, 2_097_152L, 32, 20, 1L << 40, 2));
        Transfer<ClipboardCache.Entry> transfer = session.upload("huge.schem", new byte[(int) Transfer.MAX_UPLOAD_BYTES + 1]);
        assertEquals(RejectReason.TOO_LARGE, assertInstanceOf(Reply.Refused.class, now(transfer.result())).reason());
        assertTrue(transport.sent.isEmpty());
    }

    @Test
    void anInvalidUploadGrantFailsTheUpload() {
        connect();
        Transfer<ClipboardCache.Entry> transfer = session.upload("x.schem", new byte[100]);
        server(new S2C.UploadGrant(sent(C2S.UploadBegin.class).reqId(), 7, -5));
        assertEquals(new StreamAbort(7, "invalid"), sent(StreamAbort.class));
        assertEquals(Reply.Failure.ABORTED, assertInstanceOf(Reply.Failed.class, now(transfer.result())).failure());
        assertTrue(allSent(StreamOpen.class).isEmpty());
    }

    // ---------------------------------------------------------------- disconnect

    @Test
    void aDisconnectFailsEverythingPendingQuietly() {
        connect();
        CompletionStage<Reply<ClipboardCache.Entry>> copy = session.copy(BOX, ORIGIN, false);
        Transfer<ClipboardCache.Preview> preview = session.requestPreview(new SourceRef.Clipboard(CLIP));
        Transfer<ClipboardCache.Entry> upload = session.upload("x.schem", new byte[10]);
        session.onDisconnect();
        for (Reply<?> reply : List.of(now(copy), now(preview.result()), now(upload.result()))) {
            assertEquals(Reply.Failure.DISCONNECTED, assertInstanceOf(Reply.Failed.class, reply).failure());
        }
        assertTrue(session.transfers().isEmpty());
        assertTrue(notices.isEmpty());
        assertTrue(session.clipboards().current().isEmpty());
        Transfer<ClipboardCache.Preview> later = session.requestPreview(new SourceRef.Clipboard(CLIP));
        assertEquals(Reply.Failure.DISCONNECTED, assertInstanceOf(Reply.Failed.class, now(later.result())).failure());
    }

    @Test
    void theHelloOffersTheClipboardFeatures() {
        session.onJoin();
        C2S.Hello hello = sent(C2S.Hello.class);
        assertTrue(hello.features().has(Features.CLIPBOARD));
        assertTrue(hello.features().has(Features.SCHEMATICS));
        assertTrue(hello.features().has(Features.LIBRARY));
    }

    /** Records what the session sends, decoded. */
    private static final class FakeTransport implements FabricEditorSession.Transport {
        final List<C2S> sent = new ArrayList<>();
        /** Sending a message it matches throws, as a broken connection might. */
        Predicate<C2S> throwsOn = message -> false;

        @Override
        public boolean canSend() {
            return true;
        }

        @Override
        public void send(byte[] frame) {
            C2S message;
            try {
                message = Codec.decodeC2S(frame, STATES);
            } catch (ProtocolException e) {
                throw new AssertionError("The client sent an undecodable frame", e);
            }
            if (throwsOn.test(message)) throw new IllegalStateException("the connection broke (test)");
            sent.add(message);
        }
    }
}
