package dev.sculptory.fabric.client.session;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.nbt.NbtLimits;
import dev.sculptory.core.tinker.EntityEdit;
import dev.sculptory.core.tinker.EntityView;
import dev.sculptory.core.tinker.SignText;
import dev.sculptory.fabric.SculptoryMod;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.Features;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The Tinker half of {@link FabricEditorSession} (protocol 5): {@code TinkerBlock}
 * and {@code TinkerEntity}, each answered by one {@code TinkerResult} with its request id.
 *
 * <p><b>Toasts</b> (see {@link Reply}): a refusal is toasted here, with the server's detail when it gives one ("the
 * block changed meanwhile"), else the reason's own message; a request without an answer for {@link #TIMEOUT_NANOS}
 * fails {@link Reply.Failure#TIMED_OUT} (toasted); a disconnect fails every pending request quietly. A refusal only
 * because the player's brush stroke is still being written ({@code QUEUE_FULL}) is sent again once, a moment later,
 * before it is reported.
 *
 * <p>Render thread only.
 */
final class TinkerRequests {
    /** A request without an answer for this long has failed. */
    static final long TIMEOUT_NANOS = 30_000_000_000L;
    /** A request refused while the player's stroke is written goes again this much later (once). */
    static final long RETRY_NANOS = 300_000_000L;
    /** The toast of a refusal with a detail: "Tinker: the block changed meanwhile". */
    static final String REFUSED = "sculptory.tinker.refused";
    static final String NOT_OFFERED = "not offered by this server";

    /** What the helper needs from the session. */
    interface Link {
        long now();

        int nextReqId();

        /** Encodes and sends; returns why it failed (prefixed with the reason name), or {@code null}. */
        String send(C2S message);

        void notice(Notice notice);

        Features features();

        Permissions permissions();

        /** A new edit is on its way: undo and redo presses still queued are dropped. */
        void newEdit();
    }

    /** One request waiting for its answer (or for its one retry). */
    private static final class Pending {
        C2S message;
        /** Completes with the answer's data (empty for a block change). */
        final CompletableFuture<Reply<byte[]>> future = new CompletableFuture<>();
        long sentAt;
        boolean retried;
        /** When a refused request goes out again (0: it is out). */
        long retryAt;

        Pending(C2S message, long sentAt) {
            this.message = message;
            this.sentAt = sentAt;
        }
    }

    private final Link link;
    private final Map<Integer, Pending> pending = new HashMap<>();

    TinkerRequests(Link link) {
        this.link = Objects.requireNonNull(link);
    }

    /** Whether the server answers Tinker requests. */
    boolean offered() {
        return link.features().has(Features.TINKER);
    }

    CompletionStage<Reply<Boolean>> block(BlockPos pos, int expected, int target, SignText sign) {
        Objects.requireNonNull(pos);
        if (!offered()) return CompletableFuture.completedFuture(Reply.refused(RejectReason.DISABLED, NOT_OFFERED));
        int reqId = link.nextReqId();
        C2S.TinkerBlock message;
        try {
            message = new C2S.TinkerBlock(reqId, pos, expected, target, sign);
        } catch (IllegalArgumentException invalid) {
            return CompletableFuture.completedFuture(Reply.refused(RejectReason.INVALID, invalid.getMessage()));
        }
        return send(message).thenApply(reply -> switch (reply) {
            case Reply.Ok<byte[]> ok -> Reply.ok(Boolean.TRUE);
            case Reply.Refused<byte[]> refused -> Reply.refused(refused.reason(), refused.detail());
            case Reply.Failed<byte[]> failed -> Reply.failed(failed.failure(), failed.detail());
        });
    }

    CompletionStage<Reply<EntityView>> entity(UUID id, List<EntityEdit> edits) {
        Objects.requireNonNull(id);
        if (!offered()) return CompletableFuture.completedFuture(Reply.refused(RejectReason.DISABLED, NOT_OFFERED));
        C2S.TinkerEntity message;
        try {
            message = new C2S.TinkerEntity(link.nextReqId(), id, edits);
        } catch (IllegalArgumentException invalid) {
            return CompletableFuture.completedFuture(Reply.refused(RejectReason.INVALID, invalid.getMessage()));
        }
        return send(message).thenApply(reply -> switch (reply) {
            case Reply.Ok<byte[]> ok -> view(ok.value());
            case Reply.Refused<byte[]> refused -> Reply.refused(refused.reason(), refused.detail());
            case Reply.Failed<byte[]> failed -> Reply.failed(failed.failure(), failed.detail());
        });
    }

    /** The panel data of a done entity request. */
    private Reply<EntityView> view(byte[] data) {
        try {
            return Reply.ok(EntityView.read(NbtIo.fromBytes(data, NbtLimits.BLOCK_ENTITY)));
        } catch (IOException | IllegalArgumentException e) {
            SculptoryMod.LOG.warn("Sculptory: a Tinker answer did not read: {}", e.getMessage());
            link.notice(Notice.of(Notice.Level.WARNING, Reply.Failure.CORRUPT.noticeKey(), "Tinker"));
            return Reply.failed(Reply.Failure.CORRUPT, String.valueOf(e.getMessage()));
        }
    }

    private CompletionStage<Reply<byte[]>> send(C2S message) {
        boolean edit = !(message instanceof C2S.TinkerEntity look && look.edits().isEmpty());
        if (edit) link.newEdit();
        String failure = link.send(message);
        if (failure != null) {
            RejectReason reason = failure.startsWith("TOO_LARGE") ? RejectReason.TOO_LARGE : RejectReason.INVALID;
            Reply<byte[]> refused = Reply.refused(reason, failure);
            toast(reason, failure);
            return CompletableFuture.completedFuture(refused);
        }
        Pending request = new Pending(message, link.now());
        pending.put(reqId(message), request);
        return request.future;
    }

    /** A {@code TinkerResult} arrived; one for no request of ours (a late answer) is ignored. */
    void result(S2C.TinkerResult result) {
        Pending request = pending.get(result.reqId());
        if (request == null || request.retryAt != 0) return;
        if (result.reason() == RejectReason.QUEUE_FULL && !request.retried) {
            // Most likely the player's brush stroke is still being written: go again once, a moment later.
            request.retried = true;
            request.retryAt = link.now() + RETRY_NANOS;
            return;
        }
        pending.remove(result.reqId());
        if (result.reason() != null) {
            toast(result.reason(), result.detail());
            request.future.complete(Reply.refused(result.reason(), result.detail()));
            return;
        }
        request.future.complete(Reply.ok(result.data()));
    }

    /** Retries due requests and fails the ones without an answer for too long. */
    void tick(long now) {
        List<Map.Entry<Integer, Pending>> due = new ArrayList<>(pending.entrySet());
        for (Map.Entry<Integer, Pending> entry : due) {
            Pending request = entry.getValue();
            if (request.retryAt != 0 && now - request.retryAt >= 0) {
                pending.remove(entry.getKey());
                C2S again = withReqId(request.message, link.nextReqId());
                request.message = again;
                request.retryAt = 0;
                request.sentAt = now;
                String failure = link.send(again);
                if (failure != null) {
                    toast(RejectReason.INVALID, failure);
                    request.future.complete(Reply.refused(RejectReason.INVALID, failure));
                } else {
                    pending.put(reqId(again), request);
                }
            } else if (request.retryAt == 0 && now - request.sentAt > TIMEOUT_NANOS) {
                pending.remove(entry.getKey());
                link.notice(Notice.of(Notice.Level.WARNING, Reply.Failure.TIMED_OUT.noticeKey(), "Tinker"));
                request.future.complete(Reply.failed(Reply.Failure.TIMED_OUT, "no answer"));
            }
        }
    }

    /** The connection ended: every request fails, quietly. */
    void reset() {
        List<Pending> abandoned = new ArrayList<>(pending.values());
        pending.clear();
        for (Pending request : abandoned) request.future.complete(Reply.failed(Reply.Failure.DISCONNECTED, "disconnected"));
    }

    /** How many requests wait for an answer. */
    int pendingCount() {
        return pending.size();
    }

    private void toast(RejectReason reason, String detail) {
        if (detail != null && !detail.isEmpty()) {
            link.notice(Notice.of(Notice.Level.WARNING, REFUSED, detail));
        } else {
            link.notice(SessionNotices.rejection(reason, SessionNotices.Subject.EDIT, link.permissions().limits()));
        }
    }

    private static int reqId(C2S message) {
        return switch (message) {
            case C2S.TinkerBlock block -> block.reqId();
            case C2S.TinkerEntity entity -> entity.reqId();
            default -> throw new IllegalArgumentException("Not a Tinker request: " + message.type());
        };
    }

    private static C2S withReqId(C2S message, int reqId) {
        return switch (message) {
            case C2S.TinkerBlock b -> new C2S.TinkerBlock(reqId, b.pos(), b.expected(), b.target(), b.sign());
            case C2S.TinkerEntity e -> new C2S.TinkerEntity(reqId, e.entity(), e.edits());
            default -> throw new IllegalArgumentException("Not a Tinker request: " + message.type());
        };
    }
}
