package dev.sculptory.server.engine;

import dev.sculptory.protocol.v2.RejectReason;
import java.util.Objects;

/** An edit refused before it started; nothing was changed. */
public final class EditRejected extends Exception {
    /**
     * {@link #kind()} of an Undo anyway refused because the player's run of undo (or redo) steps is not the one asked
     * for (the history changed since, the steps kept nothing, or they span worlds): the client withdraws its offer.
     */
    public static final String HISTORY_RUN = "history_run";
    /**
     * {@link #kind()} of a region op, undo, redo, copy or scatter preview refused ({@code QUEUE_FULL}) because the
     * player's brush stroke is still being written or committed to history: nothing is wrong, and the same request a
     * moment later goes ahead, so the client holds it and retries rather than reporting it.
     */
    public static final String STROKE_PENDING = "stroke_pending";

    private final RejectReason reason;
    private final String detail;
    private final String kind;

    public EditRejected(RejectReason reason, String detail) {
        this(reason, detail, "");
    }

    public EditRejected(RejectReason reason) {
        this(reason, null);
    }

    /** @param kind a machine-readable kind of refusal a client acts on (such as {@link #HISTORY_RUN}), or "" */
    public EditRejected(RejectReason reason, String detail, String kind) {
        super(reason + (detail == null || detail.isEmpty() ? "" : ": " + detail));
        this.reason = Objects.requireNonNull(reason);
        this.detail = detail == null ? "" : detail;
        this.kind = Objects.requireNonNull(kind);
    }

    public RejectReason reason() {
        return reason;
    }

    /** The detail for the player, without the reason ("" when there is none). */
    public String detail() {
        return detail;
    }

    /** A machine-readable kind of refusal, or "". */
    public String kind() {
        return kind;
    }
}
