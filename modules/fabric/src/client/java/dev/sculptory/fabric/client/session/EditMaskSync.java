package dev.sculptory.fabric.client.session;

import dev.sculptory.core.Sha256;
import dev.sculptory.core.mask.EditMask;
import dev.sculptory.core.mask.MaskEntry;
import dev.sculptory.core.mask.MaskRule;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Region;
import dev.sculptory.fabric.client.editor.mask.EditMaskModel;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.Features;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Keeps the server's copy of the player's global mask in step with
 * {@link EditMaskModel#effective()}: sends {@code SetEditMask} after each Welcome (the server starts every connection
 * with the mask off, so only a mask that is on is sent) and after each change, and reads the {@code EditMaskState}
 * answers.
 *
 * <p><b>Order.</b> The server applies a mask to the edits it receives after it, so before an edit goes out
 * ({@link #gate}) a change not yet sent is sent first, in the same tick. Changes that come in bursts (a selection being
 * dragged under an inside rule, a slider) otherwise go out at most every {@link #QUIET_NANOS}, to stay inside the
 * server's request budget.
 *
 * <p><b>An inside rule on a cell set</b> (magic select) names the set as the server holds it: the set is uploaded first
 * (as an op on it would be) and the mask names it as a {@code Region.Uploaded}. An edit made while that upload runs is
 * refused on the client ("the mask is being sent"); a {@code SELECTION_NOT_LOADED} answer uploads it again.
 *
 * <p><b>Refusals</b> fail closed on both sides: the server refuses every edit until it accepts a mask, and so does
 * {@link #gate} while the mask shown is one the server refused. Over the rate limit, or a set the server dropped, the
 * mask is sent again after {@link #RETRY_NANOS}; any other refusal waits for the next change. With the mask on, a
 * server without {@link Features#EDIT_MASK} gets no edits at all. Render thread only.
 */
public final class EditMaskSync {
    /** Least time between two masks sent for a burst of changes. */
    static final long QUIET_NANOS = 250_000_000L;
    /** When a mask refused for a passing reason is sent again. */
    static final long RETRY_NANOS = 500_000_000L;
    /** A client-side refusal's notice at most this often. */
    static final long NOTICE_NANOS = 2_000_000_000L;
    static final String NOTICE_NO_SUPPORT = "sculptory.notice.mask.no_server_support";
    static final String NOTICE_SENDING = "sculptory.notice.mask.sending";
    static final String NOTICE_REFUSED = "sculptory.notice.mask.refused";

    /** What the sync reaches of its session. */
    interface Link {
        long now();

        /** Whether the session is ready (handshake done). */
        boolean ready();

        Features features();

        int nextReqId();

        /** Sends a message; returns why it was not sent, or {@code null}. */
        String send(C2S message);

        /** Uploads a cell set the server then holds for this connection (quietly: the sync reports). */
        CompletionStage<Reply<Sha256>> upload(CellSet cells);

        /** Forgets that the server holds the set with this hash (it dropped it). */
        void forgetUpload(Sha256 hash);

        void notice(Notice notice);
    }

    private final Link link;
    private EditMaskModel model;
    /** The model's changes, followed while connected. */
    private Subscription listening;

    /** The mask the server holds as far as this client knows (sent and not refused); off after each Welcome. */
    private EditMask server = EditMask.NONE;
    /** The model's mask {@link #server} stands for (inside rules as the model has them, before an upload's name). */
    private EditMask serverFor = EditMask.NONE;
    /** The request of the last {@code SetEditMask} sent, 0 before any, and the model's mask it was for. */
    private int sentReqId;
    private EditMask sentFor;
    /** The model's mask the server refused last, and why; {@code null} when the last answer was not a refusal. */
    private EditMask refusedFor;
    private RejectReason refusedReason;
    private String refusedDetail = "";
    /** Don't send the refused mask again before this. */
    private long retryAt;
    /** A cell set being uploaded for an inside rule, and the model's mask it is for. */
    private CellSet uploading;
    private EditMask uploadingFor;
    /** The last cell set uploaded and its hash (reused while the selection is that set). */
    private CellSet uploaded;
    private Sha256 uploadedHash;
    /** When a mask last went out, and whether a change is waiting for {@link #QUIET_NANOS}. */
    private long lastSentAt = Long.MIN_VALUE / 2;
    private boolean waiting;
    private long lastNoticeAt = Long.MIN_VALUE / 2;
    private String lastNoticeKey = "";

    EditMaskSync(EditMaskModel model, Link link) {
        this.model = Objects.requireNonNull(model);
        this.link = Objects.requireNonNull(link);
    }

    /** Follows another model (tests); call before the Welcome. */
    void follow(EditMaskModel other) {
        this.model = Objects.requireNonNull(other);
    }

    /**
     * A new connection's Welcome: the server holds no mask; a mask that is on goes out now, and every change from here
     * on.
     */
    void welcome() {
        clear();
        if (listening != null) listening.close();
        listening = model.onChange(this::changed);
        flush(true);
    }

    /** The connection ended: the model's changes are no longer followed. */
    void reset() {
        clear();
        if (listening != null) listening.close();
        listening = null;
    }

    private void clear() {
        server = EditMask.NONE;
        serverFor = EditMask.NONE;
        sentReqId = 0;
        sentFor = null;
        refusedFor = null;
        refusedReason = null;
        refusedDetail = "";
        retryAt = 0;
        uploading = null;
        uploadingFor = null;
        uploaded = null;
        uploadedHash = null;
        waiting = false;
    }

    /** The model changed: send now, or once the burst is over. */
    private void changed() {
        if (!link.ready()) return;
        if (link.now() - lastSentAt >= QUIET_NANOS) {
            flush(false);
        } else {
            waiting = true;
        }
    }

    /** Once per tick: a change waiting out a burst, or a refused mask due to be sent again. */
    void tick(long now) {
        if (!link.ready()) return;
        if (waiting && now - lastSentAt >= QUIET_NANOS) flush(false);
        if (refusedFor != null && retryAt != 0 && now - retryAt >= 0 && refusedFor.equals(model.effective())) {
            retryAt = 0;
            flush(true);
        }
    }

    /**
     * Sends the model's mask when the server does not hold it yet ({@code again}: even when it was sent and refused).
     * Returns false when it cannot go out yet (a cell set is being uploaded for it) or the server cannot take it.
     */
    private boolean flush(boolean again) {
        waiting = false;
        EditMask want = model.effective();
        if (want.equals(serverFor)) return true;
        // A refused mask goes out again only when asked (a retry, or an edit after a passing refusal).
        if (!again && want.equals(refusedFor)) return true;
        if (!link.features().has(Features.EDIT_MASK)) return want.isOff();
        CellSet cells = cellsOf(want);
        Sha256 hash = null;
        if (cells != null) {
            if (cells.equals(uploaded)) {
                hash = uploadedHash;
            } else {
                upload(cells, want);
                // An upload the server already holds answers at once, and the mask has gone out already.
                return want.equals(serverFor);
            }
        }
        send(want, cells == null ? want : named(want, cells, hash));
        return true;
    }

    private void send(EditMask want, EditMask wire) {
        int reqId = link.nextReqId();
        String failure = link.send(new C2S.SetEditMask(reqId, wire));
        lastSentAt = link.now();
        if (failure != null) {
            // Not sent (an encoding problem): the server keeps what it had; edits are held back until a change.
            refused(want, RejectReason.INVALID, failure);
            return;
        }
        sentReqId = reqId;
        sentFor = want;
        // The server applies it to every edit sent after it; it answers in order.
        server = wire;
        serverFor = want;
        refusedFor = null;
    }

    private void upload(CellSet cells, EditMask want) {
        if (cells.equals(uploading)) {
            uploadingFor = want;
            return;
        }
        uploading = cells;
        uploadingFor = want;
        link.upload(cells).thenAccept(reply -> {
            if (uploading != cells) return;
            uploading = null;
            EditMask forMask = uploadingFor;
            uploadingFor = null;
            switch (reply) {
                case Reply.Ok<Sha256> ok -> {
                    uploaded = cells;
                    uploadedHash = ok.value();
                    if (link.ready() && forMask.equals(model.effective())) flush(true);
                }
                case Reply.Refused<Sha256> refused -> refused(forMask, refused.reason(), refused.detail());
                case Reply.Failed<Sha256> failed -> refused(forMask, RejectReason.INVALID,
                        "the selection could not be sent: " + failed.detail());
            }
        });
    }

    /** The server's answer to a {@code SetEditMask}; answers to earlier requests are stale and ignored. */
    void state(S2C.EditMaskState answer) {
        if (answer.reqId() != sentReqId) return;
        if (answer.reason() == null) {
            refusedFor = null;
            return;
        }
        EditMask forMask = sentFor;
        if (answer.reason() == RejectReason.SELECTION_NOT_LOADED && uploadedHash != null) {
            link.forgetUpload(uploadedHash);
            uploaded = null;
            uploadedHash = null;
        }
        refused(forMask, answer.reason(), answer.detail());
    }

    private void refused(EditMask forMask, RejectReason reason, String detail) {
        server = null;
        serverFor = null;
        refusedFor = forMask;
        refusedReason = reason;
        refusedDetail = detail == null ? "" : detail;
        boolean passing = reason == RejectReason.RATE_LIMITED || reason == RejectReason.SELECTION_NOT_LOADED;
        retryAt = passing ? link.now() + RETRY_NANOS : 0;
        if (!passing) notice(NOTICE_REFUSED, reason + (refusedDetail.isEmpty() ? "" : ": " + refusedDetail));
    }

    /**
     * Whether {@code message} (an edit: an op, a stroke, a copy, a builder action) may go out under the mask shown:
     * {@code null} when it may (a change not yet sent is sent first), else why not, as a {@code "REASON: detail"} text
     * the caller reports. Undo and redo are never masked and never asked about.
     */
    String gate(C2S message) {
        if (!edit(message)) return null;
        EditMask want = model.effective();
        if (want.isOff()) {
            // Turned off since the last mask went out: the server must hear it before this edit.
            if (!EditMask.NONE.equals(serverFor) && link.features().has(Features.EDIT_MASK)) flush(true);
            return null;
        }
        if (!link.features().has(Features.EDIT_MASK)) {
            notice(NOTICE_NO_SUPPORT);
            return "DISABLED: this server cannot apply the mask; turn it off (Ctrl+M) to edit here";
        }
        if (want.equals(refusedFor) && retryAt == 0) {
            // Refused for good (until it changes): the server would refuse the edit too.
            notice(NOTICE_REFUSED, refusedReason + (refusedDetail.isEmpty() ? "" : ": " + refusedDetail));
            return "INVALID: the server refused the mask (" + refusedReason + ")";
        }
        if (!want.equals(serverFor) && !flush(true)) {
            notice(NOTICE_SENDING);
            return "QUEUE_FULL: the mask's selection is still being sent";
        }
        return null;
    }

    /** The mask the server holds as far as this client knows ({@code null} after a refusal), for tests. */
    EditMask server() {
        return server;
    }

    private static boolean edit(C2S message) {
        return message instanceof C2S.RunOp || message instanceof C2S.StrokeBegin || message instanceof C2S.Copy
                || message instanceof C2S.BuilderPlace || message instanceof C2S.BuilderBreak;
    }

    /** The cell set an inside rule names, or {@code null} (at most one: the selection). */
    private static CellSet cellsOf(EditMask mask) {
        for (MaskEntry entry : mask.entries()) {
            if (entry.rule() instanceof MaskRule.Inside inside && inside.region() instanceof Region.Cells cells) {
                return cells.cells();
            }
        }
        return null;
    }

    /** {@code mask} with its cell-set inside rules naming the uploaded set. */
    private static EditMask named(EditMask mask, CellSet cells, Sha256 hash) {
        List<MaskEntry> entries = new ArrayList<>(mask.entries().size());
        for (MaskEntry entry : mask.entries()) {
            if (entry.rule() instanceof MaskRule.Inside inside && inside.region() instanceof Region.Cells) {
                entries.add(new MaskEntry(new MaskRule.Inside(new Region.Uploaded(hash, cells.bounds(), cells.size())),
                        entry.not()));
            } else {
                entries.add(entry);
            }
        }
        return new EditMask(entries, mask.invertAll());
    }

    private void notice(String key, String... args) {
        long now = link.now();
        if (key.equals(lastNoticeKey) && now - lastNoticeAt < NOTICE_NANOS) return;
        lastNoticeKey = key;
        lastNoticeAt = now;
        link.notice(Notice.of(Notice.Level.WARNING, key, args));
    }
}
