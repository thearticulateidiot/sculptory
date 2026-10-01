package dev.sculptory.fabric.client.session;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.scatter.ScatterPlan;
import dev.sculptory.fabric.SculptoryMod;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.Features;
import dev.sculptory.protocol.v2.ProtocolException;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The M3 half of {@link FabricEditorSession}: scatter previews ({@code ScatterPreview} → {@code ScatterPlan}, then the
 * {@code SCATTER_PLACEMENTS} stream).
 *
 * <p><b>Only the latest preview counts.</b> The server plans one preview per player and a newer one replaces the
 * older (whose request then gets {@code JobRejected(QUEUE_FULL)}, which is swallowed here). So sending a preview
 * completes the one before it at once with {@link Reply.Failure#CANCELLED} ({@value #SUPERSEDED}, no toast), aborts
 * its stream if one is open, and from then on only answers carrying the new request id are taken: a late
 * {@code ScatterPlan} or placements stream of an older request is ignored (the stream aborted).
 *
 * <p><b>Answers.</b> A plan without placements completes at once. Otherwise the placements stream must open with the
 * plan's id and request id in its meta ({@link ScatterPlacements#META_PLAN_ID}, {@link ScatterPlacements#META_REQ_ID})
 * and format {@value ScatterPlacements#FORMAT_NAME}, be no larger than the plan's placement count can need, and decode
 * ({@link ScatterPlacements#decode}) to exactly that many placements with variant indices within the request's
 * variants. Plan bounds and anchors must lie inside the world ({@link #MAX_XZ}, {@link #MAX_Y}). When a placement is of
 * a tree or feature, the preview also waits for the grown cells' {@code SCATTER_GENERATED} stream
 * ({@link ScatterGenerated}), passed on undecoded. Anything else fails the preview with {@link Reply.Failure#CORRUPT} or
 * {@link Reply.Failure#ABORTED}.
 *
 * <p><b>Toasts</b> (see {@link Reply}): the server's refusal notice ({@code request_refused}) is relayed by the
 * session as usual; failures on the way are toasted here, superseded previews and disconnects are not.
 *
 * <p><b>Timeouts.</b> Planning may wait for jobs over the area and then take up to the server's planning deadline,
 * so a preview without its plan fails after {@link #PLAN_TIMEOUT_NANOS}; a placements stream with no data for
 * {@link ClipboardTransfers#STALL_NANOS} fails and is aborted.
 *
 * <p>Render thread only.
 */
final class ScatterRequests {
    /** A preview whose plan has not arrived after this fails (the server answers within about 105 s, then streams). */
    static final long PLAN_TIMEOUT_NANOS = 150_000_000_000L;
    static final String SUPERSEDED = "superseded";
    /** Superseded request ids remembered, so their late refusals are recognised. */
    static final int REMEMBERED_REQUESTS = 32;
    /** Header bytes of a placements payload: magic, format and the count varint. */
    static final int PAYLOAD_HEADER_BYTES = 10;
    /**
     * The largest encoded placement ({@code ScatterPlacements}): three 5-byte zigzags, the variant (under 64: one byte),
     * the transform byte and a column plant's height byte.
     */
    static final int MAX_ENTRY_BYTES = 18;
    /**
     * Plan bounds and placement anchors must lie within this many blocks of the origin horizontally (the planner's
     * range; worlds end at 30,000,000) and {@link #MAX_Y} vertically: a server sending more is broken or hostile, and
     * the geometry built from it (grown boxes, transformed footprints) could overflow.
     */
    static final int MAX_XZ = 1 << 25;
    static final int MAX_Y = 1 << 12;

    /** What the helper needs from the session. */
    interface Link {
        long now();

        int nextReqId();

        /** Encodes and sends; returns why it failed (prefixed with the reason name), or {@code null}. */
        String send(C2S message);

        void notice(Notice notice);

        /** The negotiated features ({@code Welcome}). */
        Features features();

        /** Whether another inbound or outbound stream already uses {@code id}. */
        boolean streamIdTaken(int id);

        /** Every completed inbound stream. */
        void streamCompleted(FabricEditorSession.ReceivedStream stream);
    }

    /** The preview in flight. */
    private static final class Pending {
        final int reqId;
        final int variants;
        /** Per variant, whether it is a tree or feature (whose grown cells follow the placements). */
        final boolean[] features;
        final CompletableFuture<Reply<ScatterPreviewResult>> future = new CompletableFuture<>();
        final long sentAt;
        S2C.ScatterPlan plan;
        StreamAssembler stream;
        /** The grown cells' stream ({@link ScatterGenerated}), and its bytes once in. */
        StreamAssembler grownStream;
        byte[] grown;
        /** The placements, once decoded (the preview may still wait for its grown cells). */
        List<ScatterPlan.Placement> decoded;
        long lastActivity;

        Pending(int reqId, int variants, boolean[] features, long sentAt) {
            this.reqId = reqId;
            this.variants = variants;
            this.features = features;
            this.sentAt = sentAt;
            this.lastActivity = sentAt;
        }

        /** Whether the answer is complete: the placements decoded, and the grown cells in if any are expected. */
        boolean complete() {
            return decoded != null && (grown != null || !ScatterGenerated.expected(decoded, features));
        }

        /** Aborts the open streams locally; returns their ids (for the server). */
        List<Integer> abortStreams() {
            List<Integer> ids = new ArrayList<>(2);
            if (stream != null) {
                stream.abort();
                ids.add(stream.id());
                stream = null;
            }
            if (grownStream != null) {
                grownStream.abort();
                ids.add(grownStream.id());
                grownStream = null;
            }
            return ids;
        }
    }

    private final Link link;
    private Pending current;
    private final ArrayDeque<Integer> superseded = new ArrayDeque<>();

    ScatterRequests(Link link) {
        this.link = Objects.requireNonNull(link);
    }

    // =================================================================== requests

    CompletionStage<Reply<ScatterPreviewResult>> preview(ScatterPreviewRequest request) {
        Objects.requireNonNull(request);
        supersede();
        if (!link.features().has(Features.SCATTER)) {
            return refuseLocally(RejectReason.DISABLED, "not offered by this server");
        }
        int reqId = link.nextReqId();
        String failure = link.send(request.message(reqId));
        if (failure != null) {
            RejectReason reason = failure.startsWith("TOO_LARGE") ? RejectReason.TOO_LARGE : RejectReason.INVALID;
            return refuseLocally(reason, failure);
        }
        current = new Pending(reqId, request.variants().size(),
                ScatterGenerated.featureVariants(request.variants()), link.now());
        return current.future;
    }

    /** The request id of the preview in flight, or 0. */
    int currentReqId() {
        return current == null ? 0 : current.reqId;
    }

    /** Whether a placements (or grown cells) stream with this id is being received. */
    boolean ownsStream(int id) {
        return current != null && streamOf(current, id) != null;
    }

    /** Ends the preview in flight: it no longer counts, and its stream (if any) is aborted. */
    private void supersede() {
        Pending old = current;
        if (old == null) return;
        current = null;
        remember(old.reqId);
        for (int id : old.abortStreams()) link.send(new StreamAbort(id, SUPERSEDED));
        old.future.complete(Reply.failed(Reply.Failure.CANCELLED, SUPERSEDED));
    }

    private void remember(int reqId) {
        superseded.addLast(reqId);
        while (superseded.size() > REMEMBERED_REQUESTS) superseded.removeFirst();
    }

    // =================================================================== answers

    /** A plan: taken only for the preview in flight. */
    void plan(S2C.ScatterPlan m) {
        Pending p = current;
        if (p == null || m.reqId() != p.reqId || p.plan != null) {
            SculptoryMod.LOG.debug("Sculptory: ignoring a scatter plan for request {}", m.reqId());
            return;
        }
        if (m.placements() > ScatterPlacements.MAX_PLACEMENTS || hasNegativeCount(m.rejectedCounts())
                || (m.bounds() != null && !inWorld(m.bounds()))) {
            fail(Reply.Failure.CORRUPT, "impossible scatter plan");
            return;
        }
        p.plan = m;
        p.lastActivity = link.now();
        if (m.placements() == 0) finish(Reply.ok(result(p, List.of())));
    }

    private static boolean hasNegativeCount(Map<String, Integer> counts) {
        for (int count : counts.values()) {
            if (count < 0) return true;
        }
        return false;
    }

    /** Whether a cell is within the coordinates a world can have ({@link #MAX_XZ}, {@link #MAX_Y}). */
    static boolean inWorld(BlockPos pos) {
        return Math.abs((long) pos.x()) <= MAX_XZ && Math.abs((long) pos.z()) <= MAX_XZ
                && Math.abs((long) pos.y()) <= MAX_Y;
    }

    static boolean inWorld(Box box) {
        return inWorld(box.min()) && inWorld(box.max());
    }

    /** Returns true when the refusal belongs to a scatter preview (the current one, or one it replaced). */
    boolean jobRejected(S2C.JobRejected m) {
        if (current != null && m.reqId() == current.reqId) {
            // The server's request_refused notice (when it sends one) says why; the session relays it.
            finish(Reply.refused(m.reason(), ""));
            return true;
        }
        return superseded.contains(m.reqId());
    }

    /**
     * A {@code SCATTER_PLACEMENTS} stream opened: accepted only as the placements of the plan just received. (A
     * {@code SCATTER_GENERATED} one goes to {@link #grownOpen}.)
     */
    void streamOpen(StreamOpen m) {
        if (m.kind() == StreamKind.SCATTER_GENERATED) {
            grownOpen(m);
            return;
        }
        Pending p = current;
        boolean matches = p != null && p.plan != null && p.stream == null && m.kind() == StreamKind.SCATTER_PLACEMENTS
                && ScatterPlacements.FORMAT_NAME.equals(m.meta().get(ScatterPlacements.META_FORMAT))
                && p.plan.planId().toString().equals(m.meta().get(ScatterPlacements.META_PLAN_ID))
                && Integer.toString(p.reqId).equals(m.meta().get(ScatterPlacements.META_REQ_ID));
        if (!matches) {
            link.send(new StreamAbort(m.id(), "unwanted"));
            return;
        }
        if (link.streamIdTaken(m.id())) {
            link.send(new StreamAbort(m.id(), "busy"));
            fail(Reply.Failure.ABORTED, "stream id in use");
            return;
        }
        long cap = maxPayloadBytes(p.plan.placements());
        if (m.totalBytes() <= 0 || m.totalBytes() > cap) {
            link.send(new StreamAbort(m.id(), "invalid"));
            fail(Reply.Failure.ABORTED, "placements of impossible size " + m.totalBytes());
            return;
        }
        try {
            p.stream = new StreamAssembler(m, cap, StreamAssembler.DEFAULT_WINDOW);
        } catch (ProtocolException e) {
            link.send(new StreamAbort(m.id(), "too_large"));
            fail(Reply.Failure.ABORTED, e.getMessage());
            return;
        }
        p.lastActivity = link.now();
    }

    /** A {@code SCATTER_GENERATED} stream opened: accepted only as the grown cells of the plan just received. */
    private void grownOpen(StreamOpen m) {
        Pending p = current;
        if (p == null || p.plan == null || p.grownStream != null || p.grown != null
                || !ScatterGenerated.matches(m, p.plan, p.reqId)) {
            link.send(new StreamAbort(m.id(), "unwanted"));
            return;
        }
        if (link.streamIdTaken(m.id()) || streamOf(p, m.id()) != null) {
            link.send(new StreamAbort(m.id(), "busy"));
            fail(Reply.Failure.ABORTED, "stream id in use");
            return;
        }
        long cap = ScatterGenerated.maxPayloadBytes(p.plan.totalCells());
        if (m.totalBytes() <= 0 || m.totalBytes() > cap) {
            link.send(new StreamAbort(m.id(), "invalid"));
            fail(Reply.Failure.ABORTED, "grown cells of impossible size " + m.totalBytes());
            return;
        }
        try {
            p.grownStream = new StreamAssembler(m, cap, StreamAssembler.DEFAULT_WINDOW);
        } catch (ProtocolException e) {
            link.send(new StreamAbort(m.id(), "too_large"));
            fail(Reply.Failure.ABORTED, e.getMessage());
            return;
        }
        p.lastActivity = link.now();
    }

    /** The most bytes {@code placements} placements can take. */
    static long maxPayloadBytes(int placements) {
        return PAYLOAD_HEADER_BYTES + (long) placements * MAX_ENTRY_BYTES;
    }

    /** Returns true when the chunk belongs to the placements (or grown cells) stream. */
    boolean streamChunk(StreamChunk m) {
        Pending p = current;
        StreamAssembler stream = p == null ? null : streamOf(p, m.id());
        if (stream == null) return false;
        try {
            stream.accept(m);
            StreamCredit credit = stream.takeCredit();
            if (credit != null) link.send(credit);
        } catch (ProtocolException e) {
            SculptoryMod.LOG.warn("Sculptory: dropping scatter placements stream {}: {}", m.id(), e.getMessage());
            link.send(new StreamAbort(m.id(), "invalid"));
            fail(Reply.Failure.ABORTED, "damaged transfer");
            return true;
        }
        p.lastActivity = link.now();
        return true;
    }

    /** The pending preview's stream with this id, or {@code null}. */
    private static StreamAssembler streamOf(Pending p, int id) {
        if (p.stream != null && p.stream.id() == id) return p.stream;
        if (p.grownStream != null && p.grownStream.id() == id) return p.grownStream;
        return null;
    }

    /** Returns true when the end belongs to the placements (or grown cells) stream. */
    boolean streamEnd(StreamEnd m) {
        Pending p = current;
        if (p != null && p.grownStream != null && p.grownStream.id() == m.id()) return grownEnd(p, m);
        if (p == null || p.stream == null || p.stream.id() != m.id()) return false;
        byte[] bytes;
        try {
            bytes = p.stream.finish(m);
        } catch (ProtocolException e) {
            SculptoryMod.LOG.warn("Sculptory: dropping scatter placements stream {}: {}", m.id(), e.getMessage());
            p.stream = null;
            fail(Reply.Failure.ABORTED, "damaged transfer");
            return true;
        }
        StreamOpen open = p.stream.open();
        p.stream = null;
        link.streamCompleted(new FabricEditorSession.ReceivedStream(open, bytes));
        List<ScatterPlan.Placement> placements;
        try {
            placements = ScatterPlacements.decode(bytes, p.variants);
        } catch (ProtocolException | RuntimeException e) {
            SculptoryMod.LOG.warn("Sculptory: cannot read scatter placements: {}", e.getMessage());
            fail(Reply.Failure.CORRUPT, String.valueOf(e.getMessage()));
            return true;
        }
        if (placements.size() != p.plan.placements()) {
            fail(Reply.Failure.CORRUPT, placements.size() + " placements instead of " + p.plan.placements());
            return true;
        }
        for (ScatterPlan.Placement placement : placements) {
            if (!inWorld(placement.anchor())) {
                fail(Reply.Failure.CORRUPT, "a placement outside the world at " + placement.anchor());
                return true;
            }
        }
        p.decoded = placements;
        p.lastActivity = link.now();
        if (p.complete()) finish(Reply.ok(result(p, placements)));
        return true;
    }

    /** The grown cells are in: the answer, once the placements are too. */
    private boolean grownEnd(Pending p, StreamEnd m) {
        byte[] bytes;
        try {
            bytes = p.grownStream.finish(m);
        } catch (ProtocolException e) {
            SculptoryMod.LOG.warn("Sculptory: dropping scatter grown cells stream {}: {}", m.id(), e.getMessage());
            p.grownStream = null;
            fail(Reply.Failure.ABORTED, "damaged transfer");
            return true;
        }
        StreamOpen open = p.grownStream.open();
        p.grownStream = null;
        link.streamCompleted(new FabricEditorSession.ReceivedStream(open, bytes));
        p.grown = bytes;
        p.lastActivity = link.now();
        if (p.complete()) finish(Reply.ok(result(p, p.decoded)));
        return true;
    }

    /** Returns true when the abort belongs to the placements (or grown cells) stream. */
    boolean streamAbort(StreamAbort m) {
        Pending p = current;
        StreamAssembler stream = p == null ? null : streamOf(p, m.id());
        if (stream == null) return false;
        stream.abort();
        if (stream == p.stream) {
            p.stream = null;
        } else {
            p.grownStream = null;
        }
        fail(Reply.Failure.ABORTED, m.reason());
        return true;
    }

    private static ScatterPreviewResult result(Pending p, List<ScatterPlan.Placement> placements) {
        S2C.ScatterPlan plan = p.plan;
        return new ScatterPreviewResult(p.reqId, plan.planId(), plan.rejectedCounts(), plan.totalCells(), plan.bounds(),
                placements, p.grown);
    }

    // =================================================================== tick and reset

    void tick(long now) {
        Pending p = current;
        if (p == null) return;
        if (p.plan == null) {
            if (now - p.sentAt > PLAN_TIMEOUT_NANOS) fail(Reply.Failure.TIMED_OUT, "no plan");
        } else if (now - p.lastActivity > ClipboardTransfers.STALL_NANOS) {
            fail(Reply.Failure.TIMED_OUT, p.decoded == null ? "no placements" : "no grown cells");
        }
    }

    /** The connection ended: the preview in flight fails with {@link Reply.Failure#DISCONNECTED}. */
    void reset() {
        Pending p = current;
        current = null;
        superseded.clear();
        if (p == null) return;
        p.abortStreams();
        p.future.complete(Reply.failed(Reply.Failure.DISCONNECTED, "disconnected"));
    }

    // =================================================================== helpers

    /** Fails the preview in flight (aborting its open stream) and toasts the failure. */
    private void fail(Reply.Failure failure, String detail) {
        Pending p = current;
        if (p != null) {
            for (int id : p.abortStreams()) link.send(new StreamAbort(id, "abandoned"));
        }
        finish(Reply.failed(failure, detail));
    }

    private void finish(Reply<ScatterPreviewResult> reply) {
        Pending p = current;
        if (p == null) return;
        current = null;
        p.future.complete(reply);
        if (reply instanceof Reply.Failed<ScatterPreviewResult> failed && failed.failure().announced()) {
            link.notice(Notice.of(Notice.Level.WARNING, failed.failure().noticeKey(), "Scatter preview"));
        }
    }

    private CompletionStage<Reply<ScatterPreviewResult>> refuseLocally(RejectReason reason, String detail) {
        link.notice(ClipboardTransfers.detailNotice(reason, detail));
        return CompletableFuture.completedFuture(Reply.refused(reason, detail));
    }
}
