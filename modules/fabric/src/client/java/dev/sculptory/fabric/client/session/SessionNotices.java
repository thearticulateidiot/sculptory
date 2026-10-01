package dev.sculptory.fabric.client.session;

import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.RejectReason;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The plain-English toasts for server answers: one refusal message per {@link RejectReason}, and the result
 * of a finished job. Keys live in {@code assets/sculptory/lang/en_us.json}. Pure Java.
 *
 * <p>{@link FabricEditorSession} raises these itself for refusals nobody else sees (undo, redo, strokes).
 * Refusals of {@link EditorSession#send} requests come back as {@link ToolResult.Rejected} and are the caller's
 * to show, ideally with {@link #rejection}.
 */
public final class SessionNotices {
    /** What was refused; picks the limit a {@link RejectReason#TOO_LARGE} message quotes. */
    public enum Subject {
        /** A region op (fill, replace, erase, paste...): limited by {@link Limits#maxOpVolume()}. */
        EDIT,
        /** A copy or cut: limited by {@link Limits#maxClipboardVolume()}. */
        COPY,
        /** A brush stroke: limited by {@link Limits#maxBrushRadius()}. */
        STROKE,
        UNDO,
        REDO
    }

    public static final String JOB_FINISHED = "sculptory.notice.job_finished";
    public static final String JOB_CANCELLED = "sculptory.notice.job_cancelled";
    public static final String JOB_FAILED = "sculptory.notice.job_failed";
    public static final String JOB_STRIPPED_NBT = "sculptory.notice.job_stripped_nbt";
    public static final String TOO_LARGE_BLOCKS = "sculptory.reject.too_large.blocks";
    public static final String TOO_LARGE_RADIUS = "sculptory.reject.too_large.radius";
    public static final String QUEUE_FULL_UNDO = "sculptory.reject.queue_full.undo";
    public static final String QUEUE_FULL_REDO = "sculptory.reject.queue_full.redo";
    public static final String UNDONE = "sculptory.notice.undo";
    public static final String REDONE = "sculptory.notice.redo";
    public static final String SCATTER_FINISHED = "sculptory.notice.scatter_finished";
    public static final String SCATTER_FINISHED_SKIPPED = "sculptory.notice.scatter_finished_skipped";
    public static final String SCATTER_PROTECTED = "sculptory.notice.scatter_protected";
    /** The server's notice of placements a scatter commit skipped ({@code [count]}). */
    public static final String SCATTER_SKIPPED = "sculptory.notice.scatter_skipped";
    /**
     * The server's notice ({@code [reason, detail]}) of why it refused an Undo anyway, sent before the refusal
     * ({@code ServerDispatcher.NOTICE_OVERWRITE_REFUSED}); the session turns it into {@link #overwriteRefused}.
     */
    public static final String OVERWRITE_REFUSED_BY_SERVER = "sculptory.notice.history_overwrite_refused";
    /**
     * The third argument of {@link #OVERWRITE_REFUSED_BY_SERVER} when the server's run of steps is not the one offered
     * ({@code EditRejected.HISTORY_RUN}): only then is the offer withdrawn.
     */
    public static final String OVERWRITE_RUN_REFUSED = "history_run";
    /**
     * The server's notice ({@code [reqId]}) that it refuses that request only because the player's brush stroke is still
     * being written or committed ({@code ServerDispatcher.NOTICE_STROKE_PENDING}): the session sends it again a moment
     * later instead of reporting the refusal.
     */
    public static final String STROKE_PENDING = "sculptory.notice.stroke_pending";
    /** The third argument of {@link #OVERWRITE_REFUSED_BY_SERVER} for such a refusal ({@code EditRejected.STROKE_PENDING}). */
    public static final String STROKE_PENDING_KIND = "stroke_pending";
    public static final String OVERWRITE_DONE = "sculptory.notice.overwrite_done";
    public static final String OVERWRITE_PROTECTED = "sculptory.notice.overwrite_protected";
    public static final String OVERWRITE_CANCELLED = "sculptory.notice.overwrite_cancelled";
    public static final String OVERWRITE_FAILED = "sculptory.notice.overwrite_failed";

    private SessionNotices() {}

    /** The toast for a refusal. */
    public static Notice rejection(RejectReason reason, Subject subject, Limits limits) {
        Objects.requireNonNull(reason);
        Objects.requireNonNull(subject);
        Objects.requireNonNull(limits);
        return switch (reason) {
            case TOO_LARGE -> switch (subject) {
                case EDIT -> Notice.of(Notice.Level.WARNING, TOO_LARGE_BLOCKS, count(limits.maxOpVolume()));
                case COPY -> Notice.of(Notice.Level.WARNING, TOO_LARGE_BLOCKS, count(limits.maxClipboardVolume()));
                case STROKE -> Notice.of(Notice.Level.WARNING, TOO_LARGE_RADIUS, count(limits.maxBrushRadius()));
                case UNDO, REDO -> Notice.of(Notice.Level.WARNING, reasonKey(reason));
            };
            case HISTORY_EMPTY -> switch (subject) {
                case UNDO -> Notice.of(Notice.Level.INFO, "sculptory.notice.nothing_to_undo");
                case REDO -> Notice.of(Notice.Level.INFO, "sculptory.notice.nothing_to_redo");
                default -> Notice.of(Notice.Level.INFO, reasonKey(reason));
            };
            case QUEUE_FULL -> switch (subject) {
                // The server runs one undo or redo at a time, and none while this player's own edit runs.
                case UNDO -> Notice.of(Notice.Level.WARNING, QUEUE_FULL_UNDO);
                case REDO -> Notice.of(Notice.Level.WARNING, QUEUE_FULL_REDO);
                default -> Notice.of(Notice.Level.WARNING, reasonKey(reason));
            };
            case NO_PERMISSION, PROTECTED, AREA_BUSY, UNLOADED, RATE_LIMITED, INVALID, DISABLED, ASSET_NOT_LOADED,
                 SELECTION_NOT_LOADED -> Notice.of(Notice.Level.WARNING, reasonKey(reason));
        };
    }

    /** The toast for an undo or redo the server accepted: "Undo: Fill". */
    public static Notice historyStep(boolean undo, String label) {
        return Notice.of(Notice.Level.INFO, undo ? UNDONE : REDONE, Objects.requireNonNull(label));
    }

    /**
     * The argument-free message key of a reason, {@code sculptory.reject.<reason>}: what a caller without the
     * server limits can show.
     */
    public static String reasonKey(RejectReason reason) {
        return "sculptory.reject." + reason.name().toLowerCase(Locale.ROOT);
    }

    /**
     * The toasts for a finished job, in order; empty while it runs.
     *
     * <ul>
     *   <li>Completed: {@code job_finished(label, changed, skipped)}, where skipped is protected plus conflicting
     *       blocks. With {@code quietWhenClean} (undo and redo, whose acceptance already showed a toast) only when
     *       something was skipped.</li>
     *   <li>Cancelled or failed: {@code job_cancelled}/{@code job_failed(label, changed)}.</li>
     *   <li>Any outcome with stripped block-entity data adds {@code job_stripped_nbt(label, stripped)}.</li>
     * </ul>
     */
    public static List<Notice> jobResult(JobTracker.Job job, boolean quietWhenClean) {
        Objects.requireNonNull(job);
        List<Notice> notices = new ArrayList<>(2);
        if (!job.finished()) return notices;
        String label = job.label();
        switch (job.outcome()) {
            case COMPLETED -> {
                if (!quietWhenClean || job.skipped() > 0) {
                    notices.add(Notice.of(Notice.Level.SUCCESS, JOB_FINISHED, label, count(job.changed()), count(job.skipped())));
                }
            }
            case CANCELLED -> notices.add(Notice.of(Notice.Level.WARNING, JOB_CANCELLED, label, count(job.changed())));
            case FAILED -> notices.add(Notice.of(Notice.Level.ERROR, JOB_FAILED, label, count(job.changed())));
        }
        if (job.strippedNbt() > 0) {
            notices.add(Notice.of(Notice.Level.WARNING, JOB_STRIPPED_NBT, label, count(job.strippedNbt())));
        }
        return notices;
    }

    /**
     * The toasts for a finished scatter commit. Completed: {@code scatter_finished(changed)}, or
     * {@code scatter_finished_skipped(changed, skipped)} when placements were skipped (the server reports them in
     * {@code skippedConflicts}: a placement is skipped whole when any of its cells was built on or protected since the
     * preview, or its chunks could not be loaded, and cut short when a cell was built on while it was written), plus
     * {@code scatter_protected(blocks)} for protected blocks left alone. Cancelled, failed and stripped block-entity
     * data as in {@link #jobResult}.
     */
    public static List<Notice> scatterResult(JobTracker.Job job) {
        Objects.requireNonNull(job);
        if (!job.finished() || job.outcome() != JobOutcome.COMPLETED) {
            return jobResult(job, false);
        }
        List<Notice> notices = new ArrayList<>(3);
        notices.add(job.skippedConflicts() > 0
                ? Notice.of(Notice.Level.SUCCESS, SCATTER_FINISHED_SKIPPED, count(job.changed()), count(job.skippedConflicts()))
                : Notice.of(Notice.Level.SUCCESS, SCATTER_FINISHED, count(job.changed())));
        if (job.skippedProtected() > 0) {
            notices.add(Notice.of(Notice.Level.WARNING, SCATTER_PROTECTED, count(job.skippedProtected())));
        }
        if (job.strippedNbt() > 0) {
            notices.add(Notice.of(Notice.Level.WARNING, JOB_STRIPPED_NBT, job.label(), count(job.strippedNbt())));
        }
        return notices;
    }

    /**
     * The toast for an Undo anyway (Redo anyway) the server refused because its run no longer matches the offer.
     * {@code detail} is the server's reason ("the history changed since those undo steps").
     */
    public static Notice overwriteRefused(boolean redo, String detail) {
        return Notice.of(Notice.Level.WARNING, "sculptory.notice.overwrite_refused." + (redo ? "redo" : "undo"),
                Objects.requireNonNull(detail));
    }

    /**
     * The toasts for a finished Undo anyway (Redo anyway), labelled so by the session. Completed:
     * {@code overwrite_done(label, changed)}, plus {@code overwrite_protected(label, blocks)} for protected blocks left
     * alone. Cancelled or failed: {@code overwrite_cancelled}/{@code overwrite_failed(label, changed)}; the offer stays,
     * and running it again finishes it exactly. Stripped block-entity data as in {@link #jobResult}.
     */
    public static List<Notice> overwriteResult(JobTracker.Job job) {
        Objects.requireNonNull(job);
        List<Notice> notices = new ArrayList<>(2);
        if (!job.finished()) return notices;
        String label = job.label();
        switch (job.outcome()) {
            case COMPLETED -> {
                notices.add(Notice.of(Notice.Level.SUCCESS, OVERWRITE_DONE, label, count(job.changed())));
                if (job.skippedProtected() > 0) {
                    notices.add(Notice.of(Notice.Level.WARNING, OVERWRITE_PROTECTED, label, count(job.skippedProtected())));
                }
            }
            case CANCELLED -> notices.add(Notice.of(Notice.Level.WARNING, OVERWRITE_CANCELLED, label, count(job.changed())));
            case FAILED -> notices.add(Notice.of(Notice.Level.ERROR, OVERWRITE_FAILED, label, count(job.changed())));
        }
        if (job.strippedNbt() > 0) {
            notices.add(Notice.of(Notice.Level.WARNING, JOB_STRIPPED_NBT, label, count(job.strippedNbt())));
        }
        return notices;
    }

    /** A count as the toasts show it: digits grouped with commas, e.g. {@code 1,000}. */
    public static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
