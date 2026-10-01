package dev.sculptory.fabric.client.editor.mock;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.OpSymmetry;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.palette.BlockPalette;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.scatter.ScatterArea;
import dev.sculptory.core.scatter.ScatterPlan;
import dev.sculptory.core.schem.SchematicFormat;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.EditorBackend;
import dev.sculptory.fabric.client.editor.Listeners;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.session.Capabilities;
import dev.sculptory.fabric.client.session.ClipboardCache;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.ExportedFile;
import dev.sculptory.fabric.client.session.HistoryJump;
import dev.sculptory.fabric.client.session.HistoryMirror;
import dev.sculptory.fabric.client.session.HistoryOffer;
import dev.sculptory.fabric.client.session.JobTracker;
import dev.sculptory.fabric.client.session.LibraryChange;
import dev.sculptory.fabric.client.session.LibraryChanges;
import dev.sculptory.fabric.client.session.LibraryFolder;
import dev.sculptory.fabric.client.session.LoadedPalette;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Permissions;
import dev.sculptory.fabric.client.session.Reply;
import dev.sculptory.fabric.client.session.SavedAsset;
import dev.sculptory.fabric.client.session.ScatterPreviewRequest;
import dev.sculptory.fabric.client.session.ScatterPreviewResult;
import dev.sculptory.fabric.client.session.SessionClipboardCache;
import dev.sculptory.fabric.client.session.SessionNotices;
import dev.sculptory.fabric.client.session.SessionState;
import dev.sculptory.fabric.client.session.StrokeHandle;
import dev.sculptory.fabric.client.session.StrokeParams;
import dev.sculptory.fabric.client.session.Subscription;
import dev.sculptory.fabric.client.session.ToolAction;
import dev.sculptory.fabric.client.session.ToolResult;
import dev.sculptory.fabric.client.session.Transfer;
import dev.sculptory.fabric.client.session.Transfers;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.fabric.library.LibraryPath;
import dev.sculptory.protocol.v2.AssetAccess;
import dev.sculptory.protocol.v2.Features;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * An {@link EditorSession} that needs no server: always ready and fully permitted. Region ops are
 * accepted as simulated jobs that progress over a few {@link #tick()}s and then finish, pushing a
 * history label, so the job bars, undo/redo buttons and the History window all work. Nothing is
 * applied to any world. Used by the tests, and in game with {@code -Dsculptory.mockSession=true}.
 *
 * <p>M2: copies make clipboard entries (a cut also runs a simulated job), the library is an in-memory map
 * ({@link #library()}; palettes in {@link #palettes()}), exports return placeholder bytes and uploads make a small
 * clipboard. There are no previews unless a test provides them ({@link #putPreview}, or {@link #setHoldTransfers} and {@link #completeHeld}).
 * Refusals are toasted as the real session does. Every M2 call is recorded ({@link #calls()}).
 *
 * <p>M3: a scatter preview is planned at once by a stand-in planner (a placement on each painting stamp's centre;
 * {@link #setScatterPlanner} replaces it) or held ({@link #setHoldScatterPreviews}); a newer preview completes the held
 * one with {@link Reply.Failure#CANCELLED}. Only the latest plan can be committed, once, as on the server.
 *
 * <p>Completion stages complete immediately on the calling thread.
 */
public final class MockEditorSession implements EditorSession {
    /** A simulated job takes at least this many ticks. */
    public static final int MIN_JOB_TICKS = 3;
    /** Simulated throughput. */
    public static final long CELLS_PER_TICK = 250_000;
    /** Finished jobs stay in {@link #jobs()} this long, so the HUD can show how they ended. */
    public static final int FINISHED_JOB_TICKS = 60;

    private SessionState state = SessionState.READY;
    private Permissions permissions = new Permissions(Perm.mask(EnumSet.allOf(Perm.class)), Limits.DEFAULTS);
    private final Capabilities capabilities = new Capabilities(2, Features.of(Features.STROKES, Features.REGION_OPS,
            Features.HISTORY, Features.CLIPBOARD, Features.SCHEMATICS, Features.LIBRARY, Features.SCATTER), 1L);
    private final List<ToolAction> sent = new ArrayList<>();
    private final Tracker jobs = new Tracker();
    private final History history = new History();
    private final SessionClipboardCache clipboards = new SessionClipboardCache();
    private final List<Call> calls = new ArrayList<>();
    private final Map<String, String> library = new java.util.TreeMap<>();
    /** Palettes: the palettes in the in-memory library, by path. */
    private final Map<String, BlockPalette> palettes = new java.util.TreeMap<>();
    /** Palettes: what the next load leaves out, as the server would for states it doesn't know. */
    private int nextPaletteDropped;
    /** M4: folders made by libraryCreateFolder (folders holding files need no entry). */
    private final java.util.TreeSet<String> folders = new java.util.TreeSet<>();
    private final LibraryChanges libraryChanges = new LibraryChanges();
    private Boolean libraryWritable;
    /** Per-asset access: the restricted entries, by path. */
    private final Map<String, AssetAccess> access = new java.util.TreeMap<>();
    /** The local player as the mock server knows it (own folder {@code _players/<PLAYER>}). */
    public static final UUID PLAYER = new UUID(1, 2);
    private final List<Transfers.Manual<?>> held = new ArrayList<>();
    private final Map<Transfers.Manual<?>, SourceRef> heldSources = new java.util.HashMap<>();
    private boolean holdTransfers;
    private RejectReason nextRejection;
    private final Listeners<Consumer<Notice>> noticeListeners = new Listeners<>();
    private int nextStrokeId = 1;
    private boolean historyBusy;
    // Undo anyway: the run of steps in one direction, as FabricEditorSession follows it
    private int runDirection;
    private int runSteps;
    private long runSkipped;
    private long runId;
    private long stepConflicts;
    private HistoryJump jump;
    private final List<Long> jumps = new ArrayList<>();
    private final List<HistoryOffer> overwrites = new ArrayList<>();
    // M3: scatter previews
    private boolean holdScatter;
    private CompletableFuture<Reply<ScatterPreviewResult>> heldScatter;
    private ScatterPreviewRequest heldScatterRequest;
    private ScatterPreviewResult livePlan;
    private int scatterRequests;
    private Function<ScatterPreviewRequest, ScatterPreviewResult> scatterPlanner = this::defaultScatterPlan;

    /** A backend around a fresh mock session, with an interning state space and an empty world. */
    public static EditorBackend backend() {
        MockEditorSession session = new MockEditorSession();
        MockStateSpace states = new MockStateSpace();
        MockWorldReader world = new MockWorldReader(states);
        return new EditorBackend() {
            @Override
            public Optional<EditorSession> session() {
                return Optional.of(session);
            }

            @Override
            public StateSpace states() {
                return states;
            }

            @Override
            public WorldReader world() {
                return world;
            }

            @Override
            public void tick() {
                session.tick();
            }
        };
    }

    // ---- Test controls ----

    /** Every action sent, oldest first. */
    public List<ToolAction> sent() {
        return List.copyOf(sent);
    }

    public void setState(SessionState state) {
        this.state = Objects.requireNonNull(state);
    }

    public void setPermissions(Permissions permissions) {
        this.permissions = Objects.requireNonNull(permissions);
    }

    /** Refuses the next {@link ToolAction.RunOp} with {@code reason}, as a server would. */
    public void rejectNextOp(RejectReason reason) {
        this.nextRejection = Objects.requireNonNull(reason);
    }

    /** Makes {@link #historyBusy()} report an undo or redo in flight, as a server session would. */
    public void setHistoryBusy(boolean busy) {
        this.historyBusy = busy;
    }

    /** Advances every simulated job by one tick. */
    public void tick() {
        jobs.tick();
    }

    /** Ticks until no job is running. */
    public void finishJobs() {
        for (int guard = 0; guard < 10_000 && jobs.running() > 0; guard++) {
            tick();
        }
    }

    /** Emits a notice to the listeners, as the server would. */
    public void emit(Notice notice) {
        noticeListeners.fire(listener -> listener.accept(notice));
    }

    // ---- EditorSession ----

    @Override
    public SessionState state() {
        return state;
    }

    @Override
    public Capabilities capabilities() {
        return state == SessionState.READY ? capabilities : Capabilities.NONE;
    }

    @Override
    public Permissions permissions() {
        return permissions;
    }

    @Override
    public CompletionStage<ToolResult> send(ToolAction action) {
        Objects.requireNonNull(action);
        sent.add(action);
        return CompletableFuture.completedFuture(handle(action));
    }

    private ToolResult handle(ToolAction action) {
        if (state != SessionState.READY) {
            return new ToolResult.Rejected(RejectReason.DISABLED, "Not connected");
        }
        return switch (action) {
            case ToolAction.RunOp run -> {
                if (nextRejection != null) {
                    RejectReason reason = nextRejection;
                    nextRejection = null;
                    yield new ToolResult.Rejected(reason, "");
                }
                Perm needed = switch (run.op()) {
                    case OpSpec.Paste paste -> Perm.CLIPBOARD;
                    case OpSpec.ScatterCommit commit -> Perm.SCATTER;
                    default -> Perm.REGION;
                };
                if (!permissions.has(needed) || (run.physics() && !permissions.has(Perm.PHYSICS))) {
                    yield new ToolResult.Rejected(RejectReason.NO_PERMISSION, "");
                }
                long cells = estimatedCells(run.op());
                if (run.op() instanceof OpSpec.ScatterCommit commit) {
                    // A plan is single-use and only the latest one is held, as on the server.
                    if (livePlan == null || !livePlan.planId().equals(commit.planId())) {
                        yield new ToolResult.Rejected(RejectReason.INVALID, "unknown or expired scatter plan");
                    }
                    cells = livePlan.totalCells();
                }
                if (cells > permissions.limits().maxOpVolume() && !permissions.has(Perm.LIMIT_BYPASS)) {
                    yield new ToolResult.Rejected(RejectReason.TOO_LARGE, "");
                }
                if (run.op() instanceof OpSpec.ScatterCommit) livePlan = null;
                yield new ToolResult.Accepted(jobs.start(run.label().text() != null ? run.label().text() : label(run.op()), cells), cells);
            }
            case ToolAction.Cancel cancel -> {
                jobs.cancel(cancel.jobId());
                yield new ToolResult.Done();
            }
            case ToolAction.Copy copy -> copy(copy.box(), copy.origin(), copy.cut()).toCompletableFuture().join()
                    instanceof Reply.Refused<ClipboardCache.Entry> refused
                    ? new ToolResult.Rejected(refused.reason(), refused.detail())
                    : new ToolResult.Done();
        };
    }

    @Override
    public StrokeHandle beginStroke(ToolId tool, BrushSpec spec, StrokeParams p) {
        Objects.requireNonNull(tool);
        Objects.requireNonNull(spec);
        Objects.requireNonNull(p);
        // Labelled as the server labels them: "Raise stroke", and the Shape brush's "Shape".
        String label = spec.tool() == BrushTool.SHAPE ? capitalize(tool.value()) : capitalize(tool.value()) + " stroke";
        return new Stroke(nextStrokeId++, label);
    }

    /** Accepted at once, so the "Undo: label" toast a real server's acceptance raises comes right away. */
    @Override
    public void undo() {
        if (!history.canUndo()) {
            return;
        }
        String label = history.undoLabel();
        history.undo();
        runStep(-1);
        emit(SessionNotices.historyStep(true, label));
    }

    @Override
    public void redo() {
        if (!history.canRedo()) {
            return;
        }
        String label = history.redoLabel();
        history.redo();
        runStep(1);
        emit(SessionNotices.historyStep(false, label));
    }

    /**
     * Undoes ({@code -n}) or redoes ({@code +n}) {@code n} entries at once, as {@link EditorSession#jumpTo} asks; the
     * jumps asked for are recorded ({@link #jumps()}).
     */
    @Override
    public void jumpTo(long historyId) {
        jumps.add(historyId);
        for (long i = 0; i < -historyId && history.canUndo(); i++) {
            history.undo();
            runStep(-1);
        }
        for (long i = 0; i < historyId && history.canRedo(); i++) {
            history.redo();
            runStep(1);
        }
    }

    @Override
    public Optional<HistoryJump> historyJump() {
        return Optional.ofNullable(jump);
    }

    @Override
    public Optional<HistoryOffer> historyOffer() {
        if (runSteps < 1 || runSkipped < 1) {
            return Optional.empty();
        }
        return Optional.of(new HistoryOffer(runId, runDirection > 0, runSteps, runSkipped, false));
    }

    /** Records the overwrite ({@link #overwrites()}), toasts it as done and withdraws the offer. */
    @Override
    public void acceptHistoryOffer() {
        Optional<HistoryOffer> offer = historyOffer();
        if (offer.isEmpty() || historyBusy) {
            return;
        }
        overwrites.add(offer.get());
        endRun();
        emit(Notice.of(Notice.Level.SUCCESS, SessionNotices.OVERWRITE_DONE,
                offer.get().redo() ? "Redo anyway" : "Undo anyway", count(offer.get().skipped())));
    }

    /** The next undo or redo steps each report {@code blocks} kept blocks (changed since the edit), as a server would. */
    public void setStepConflicts(long blocks) {
        this.stepConflicts = blocks;
    }

    /** Shows {@code jump} as the jump in progress ({@link #historyJump()}), or none. */
    public void setHistoryJump(HistoryJump jump) {
        this.jump = jump;
    }

    /** Every {@link #jumpTo} target, oldest first. */
    public List<Long> jumps() {
        return List.copyOf(jumps);
    }

    /** Every offer accepted, oldest first. */
    public List<HistoryOffer> overwrites() {
        return List.copyOf(overwrites);
    }

    /** A step extends the run in its direction (or starts one); see {@code FabricEditorSession}. */
    private void runStep(int direction) {
        if (runDirection != direction) {
            runDirection = direction;
            runSteps = 0;
            runSkipped = 0;
        }
        runSteps++;
        runSkipped += stepConflicts;
        runId++;
    }

    private void endRun() {
        runDirection = 0;
        runSteps = 0;
        runSkipped = 0;
        runId++;
    }

    /** A new entry: like any other change to the history, it ends the run. */
    private void pushHistory(String label) {
        history.push(label);
        endRun();
    }

    @Override
    public boolean historyBusy() {
        return historyBusy;
    }

    /** The mock applies steps at once, so only the {@link #setHistoryBusy} flag stands for queued presses. */
    @Override
    public void dropQueuedHistorySteps() {
        historyBusy = false;
    }

    @Override
    public JobTracker jobs() {
        return jobs;
    }

    @Override
    public HistoryMirror history() {
        return history;
    }

    @Override
    public ClipboardCache clipboards() {
        return clipboards;
    }

    @Override
    public Subscription onNotice(Consumer<Notice> listener) {
        return noticeListeners.add(listener);
    }

    // ---- Helpers ----

    /** The number of cells an op covers, as the server would estimate it (every symmetric copy counted). */
    public static long estimatedCells(OpSpec op) {
        return saturatingTimes(estimatedCellsOfOne(op), OpSymmetry.copyCount(op));
    }

    private static long estimatedCellsOfOne(OpSpec op) {
        return switch (op) {
            case OpSpec.Fill fill -> fill.box().volume();
            case OpSpec.Replace replace -> replace.box().volume();
            case OpSpec.Erase erase -> erase.box().volume();
            case OpSpec.Hollow hollow -> hollow.box().volume();
            case OpSpec.Walls walls -> walls.box().volume();
            case OpSpec.Move move -> saturatingTimes(move.box().volume(), 2);
            case OpSpec.Stack stack -> saturatingTimes(stack.box().volume(), stack.count());
            case OpSpec.Paste paste -> 1;
            case OpSpec.ScatterCommit scatter -> 1;
            case OpSpec.Overlay overlay -> overlay.box().volume();
            case OpSpec.Naturalize naturalize -> naturalize.box().volume();
            case OpSpec.UpdateBlocks update -> update.box().volume();
        };
    }

    /** The history label of an op, as the server would name it. */
    public static String label(OpSpec op) {
        return switch (op) {
            case OpSpec.Fill fill -> "Fill";
            case OpSpec.Replace replace -> "Replace";
            case OpSpec.Erase erase -> "Erase";
            case OpSpec.Hollow hollow -> "Hollow";
            case OpSpec.Walls walls -> "Walls";
            case OpSpec.Move move -> "Move";
            case OpSpec.Stack stack -> "Stack";
            case OpSpec.Paste paste -> "Paste";
            case OpSpec.ScatterCommit scatter -> "Scatter";
            case OpSpec.Overlay overlay -> "Overlay";
            case OpSpec.Naturalize naturalize -> "Naturalize";
            case OpSpec.UpdateBlocks update -> "Update blocks";
        };
    }

    private static long saturatingTimes(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    // ---- Jobs ----

    private final class Tracker implements JobTracker {
        private final class SimJob {
            final UUID id = UUID.randomUUID();
            final String label;
            final long total;
            final int ticksNeeded;
            int age;
            long done;
            Phase phase = Phase.QUEUED;
            JobOutcome outcome;
            boolean cancelRequested;
            int finishedFor;

            SimJob(String label, long total) {
                this.label = label;
                this.total = total;
                this.ticksNeeded = (int) Math.max(MIN_JOB_TICKS, Math.min(1_000, (total + CELLS_PER_TICK - 1) / CELLS_PER_TICK));
            }

            Job snapshot() {
                return new Job(id, label, done, total, phase, outcome);
            }
        }

        private final List<SimJob> jobs = new ArrayList<>();
        private final Listeners<Runnable> listeners = new Listeners<>();

        UUID start(String label, long total) {
            SimJob job = new SimJob(label, total);
            jobs.add(job);
            changed();
            return job.id;
        }

        void cancel(UUID id) {
            for (SimJob job : jobs) {
                if (job.id.equals(id) && job.outcome == null) {
                    job.cancelRequested = true;
                }
            }
        }

        int running() {
            int count = 0;
            for (SimJob job : jobs) {
                if (job.outcome == null) {
                    count++;
                }
            }
            return count;
        }

        void tick() {
            if (jobs.isEmpty()) {
                return;
            }
            for (SimJob job : List.copyOf(jobs)) {
                if (job.outcome != null) {
                    if (++job.finishedFor >= FINISHED_JOB_TICKS) {
                        jobs.remove(job);
                    }
                    continue;
                }
                if (job.cancelRequested) {
                    job.outcome = JobOutcome.CANCELLED;
                    if (job.done > 0) {
                        pushHistory(job.label + " (cancelled)");
                    }
                    emit(Notice.of(Notice.Level.WARNING, "sculptory.notice.job_cancelled", job.label, count(job.done)));
                    continue;
                }
                job.age++;
                if (job.age <= job.ticksNeeded) {
                    job.phase = Phase.APPLY;
                    job.done = Math.min(job.total, job.total / job.ticksNeeded * job.age);
                } else {
                    job.phase = Phase.FINALIZE;
                    job.done = job.total;
                    job.outcome = JobOutcome.COMPLETED;
                    pushHistory(job.label);
                    emit(Notice.of(Notice.Level.SUCCESS, "sculptory.notice.job_finished", job.label,
                            count(job.total), count(0)));
                }
            }
            changed();
        }

        private void changed() {
            listeners.fire(Runnable::run);
        }

        @Override
        public List<Job> jobs() {
            return jobs.stream().map(SimJob::snapshot).toList();
        }

        @Override
        public Optional<Job> job(UUID jobId) {
            return jobs.stream().filter(job -> job.id.equals(jobId)).findFirst().map(SimJob::snapshot);
        }

        @Override
        public Subscription onChange(Runnable listener) {
            return listeners.add(listener);
        }
    }

    // ---- History ----

    private static final class History implements HistoryMirror {
        private final Deque<String> undo = new ArrayDeque<>();
        private final Deque<String> redo = new ArrayDeque<>();
        private final Listeners<Runnable> listeners = new Listeners<>();
        private long version;
        private Cause lastCause = Cause.UNKNOWN;

        void push(String label) {
            undo.push(label);
            redo.clear();
            changed(Cause.OTHER);
        }

        void undo() {
            if (!undo.isEmpty()) {
                redo.push(undo.pop());
                changed(Cause.UNDO_STEP);
            }
        }

        void redo() {
            if (!redo.isEmpty()) {
                undo.push(redo.pop());
                changed(Cause.REDO_STEP);
            }
        }

        private void changed(Cause cause) {
            lastCause = cause;
            version++;
            listeners.fire(Runnable::run);
        }

        @Override
        public Cause lastCause() {
            return lastCause;
        }

        @Override
        public long version() {
            return version;
        }

        @Override
        public List<String> undoLabels() {
            return List.copyOf(undo).subList(0, Math.min(undo.size(), S2C.HistoryState.MAX_LABELS));
        }

        @Override
        public List<String> redoLabels() {
            return List.copyOf(redo).subList(0, Math.min(redo.size(), S2C.HistoryState.MAX_LABELS));
        }

        @Override
        public boolean canUndo() {
            return !undo.isEmpty();
        }

        @Override
        public boolean canRedo() {
            return !redo.isEmpty();
        }

        @Override
        public String undoLabel() {
            return undo.isEmpty() ? "" : undo.peek();
        }

        @Override
        public String redoLabel() {
            return redo.isEmpty() ? "" : redo.peek();
        }

        @Override
        public long bytes() {
            return 4_096L * undo.size() + 4_096L * redo.size();
        }

        @Override
        public Subscription onChange(Runnable listener) {
            return listeners.add(listener);
        }
    }

    // ---- Strokes and clipboards ----

    private final class Stroke implements StrokeHandle {
        private final int id;
        private final String label;
        private boolean active = true;
        private int dabs;

        Stroke(int id, String label) {
            this.id = id;
            this.label = label;
        }

        @Override
        public int strokeId() {
            return id;
        }

        @Override
        public void dab(Dab d) {
            Objects.requireNonNull(d);
            if (active) {
                dabs++;
            }
        }

        @Override
        public void end() {
            if (active) {
                active = false;
                if (dabs > 0) {
                    pushHistory(label);
                }
            }
        }

        @Override
        public void cancel() {
            end();
        }

        @Override
        public boolean active() {
            return active;
        }
    }

    // ---- M2: clipboards, previews, library, files ----

    /**
     * One M2 or M3 call, for tests: its kind ("copy", "cut", "preview", "list", "load", "save", "export", "upload",
     * "scatter_preview").
     */
    public record Call(String kind, Object argument) {}

    /** Every M2 call, oldest first. */
    public List<Call> calls() {
        return List.copyOf(calls);
    }

    /** The mock's in-memory library: paths ({@code a/b.schem}) and their asset hashes. */
    public Map<String, String> library() {
        return library;
    }

    /** Makes previews, exports and uploads wait until {@link #completeHeld} (to test progress and loading states). */
    public void setHoldTransfers(boolean hold) {
        this.holdTransfers = hold;
    }

    /** The transfers waiting because of {@link #setHoldTransfers}, oldest first. */
    public List<Transfers.Manual<?>> heldTransfers() {
        return List.copyOf(held);
    }

    /** Completes a held transfer, as the server would. */
    @SuppressWarnings("unchecked")
    public <T> void completeHeld(Transfers.Manual<?> transfer, Reply<T> reply) {
        held.remove(transfer);
        if (reply instanceof Reply.Ok<T> ok && ok.value() instanceof ClipboardCache.Preview preview) {
            clipboards.putPreview(preview, List.of(heldSources.remove(transfer)));
        }
        if (reply instanceof Reply.Ok<T> ok && ok.value() instanceof ClipboardCache.Entry entry) {
            clipboards.setCurrent(entry);
        }
        ((Transfers.Manual<T>) transfer).finish(reply);
    }

    /** Makes a preview available, as if it had been downloaded. */
    public void putPreview(SourceRef source, ClipboardCache.Preview preview) {
        clipboards.putPreview(preview, List.of(source));
    }

    /** The cache behind {@link #clipboards()}. */
    public SessionClipboardCache clipboardCache() {
        return clipboards;
    }

    /** Takes every region kind (no uploads here); a box copy is recorded with its box, any other with its region. */
    @Override
    public CompletionStage<Reply<ClipboardCache.Entry>> copy(Region region, BlockPos origin, boolean cut,
                                                              EntityFilter entities) {
        calls.add(new Call(cut ? "cut" : "copy", region instanceof Region.Cuboid cuboid ? cuboid.box() : region));
        Box box = region.bounds();
        long cells = region.cellCount();
        if (state != SessionState.READY) return CompletableFuture.completedFuture(disconnected());
        if (!permissions.has(Perm.CLIPBOARD) || (cut && !permissions.has(Perm.REGION))) {
            return CompletableFuture.completedFuture(refuse(RejectReason.NO_PERMISSION, Perm.CLIPBOARD.node()));
        }
        if (cells > permissions.limits().maxClipboardVolume() && !permissions.has(Perm.LIMIT_BYPASS)) {
            return CompletableFuture.completedFuture(refuse(RejectReason.TOO_LARGE, cells + " blocks"));
        }
        BlockPos anchor = new BlockPos(origin.x() - box.min().x(), origin.y() - box.min().y(), origin.z() - box.min().z());
        ClipboardCache.Entry entry = new ClipboardCache.Entry(UUID.randomUUID(),
                new BlockPos(box.sizeX(), box.sizeY(), box.sizeZ()), anchor, cells, cells * 4);
        clipboards.setCurrent(entry);
        if (cut) {
            jobs.start("Cut", cells);
        }
        return CompletableFuture.completedFuture(Reply.ok(entry));
    }

    @Override
    public Transfer<ClipboardCache.Preview> requestPreview(SourceRef source) {
        calls.add(new Call("preview", source));
        if (state != SessionState.READY) return Transfers.done(Transfer.Kind.PREVIEW, "preview", disconnected());
        Optional<ClipboardCache.Preview> cached = clipboards.preview(source);
        if (cached.isPresent()) return Transfers.done(Transfer.Kind.PREVIEW, "preview", Reply.ok(cached.get()));
        if (holdTransfers) {
            Transfers.Manual<ClipboardCache.Preview> transfer = new Transfers.Manual<>(Transfer.Kind.PREVIEW, "preview", 0);
            held.add(transfer);
            heldSources.put(transfer, source);
            return transfer;
        }
        return Transfers.done(Transfer.Kind.PREVIEW, "preview",
                refuse(RejectReason.INVALID, "the mock session has no previews"));
    }

    @Override
    public CompletionStage<Reply<LibraryFolder>> libraryList(String folder) {
        calls.add(new Call("list", folder));
        if (state != SessionState.READY) return CompletableFuture.completedFuture(disconnected());
        java.util.TreeMap<String, S2C.LibraryListing.Entry> entries = new java.util.TreeMap<>();
        java.util.TreeMap<String, String> files = new java.util.TreeMap<>(library);
        palettes.keySet().forEach(path -> files.put(path, ""));
        if (folder.equals(LibraryPath.SHARED)) {
            // Per-asset access: what other players granted this one, in their own folders, by real path.
            String others = LibraryPath.PLAYERS + "/";
            String own = others + PLAYER + "/";
            for (Map.Entry<String, String> asset : files.entrySet()) {
                String path = asset.getKey();
                AssetAccess granted = access.get(path);
                if (!path.startsWith(others) || path.startsWith(own) || granted == null || !granted.names(PLAYER)) continue;
                entries.put(path, fileEntry(path, asset.getValue()));
            }
            return CompletableFuture.completedFuture(Reply.ok(new LibraryFolder(folder, List.copyOf(entries.values()), false)));
        }
        String prefix = folder.isEmpty() ? "" : folder + "/";
        for (Map.Entry<String, String> asset : files.entrySet()) {
            String path = asset.getKey();
            if (!path.startsWith(prefix)) continue;
            String rest = path.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash < 0) {
                entries.put(path, fileEntry(path, asset.getValue()));
            } else {
                String sub = prefix + rest.substring(0, slash);
                entries.put(sub, new S2C.LibraryListing.Entry(sub, true, 0, ""));
            }
        }
        for (String made : folders) {
            if (!made.startsWith(prefix)) continue;
            String rest = made.substring(prefix.length());
            String sub = prefix + (rest.indexOf('/') < 0 ? rest : rest.substring(0, rest.indexOf('/')));
            entries.put(sub, new S2C.LibraryListing.Entry(sub, true, 0, ""));
        }
        if (folder.isEmpty()) entries.put(LibraryPath.SHARED, new S2C.LibraryListing.Entry(LibraryPath.SHARED, true, 0, ""));
        return CompletableFuture.completedFuture(Reply.ok(new LibraryFolder(folder, List.copyOf(entries.values()),
                libraryWritable(folder))));
    }

    /** A file's listing entry, with the lock (per-asset access) when it is restricted. */
    private S2C.LibraryListing.Entry fileEntry(String path, String hash) {
        boolean restricted = access.containsKey(path);
        return palettes.containsKey(path)
                ? new S2C.LibraryListing.Entry(path, false, 256, "", S2C.LibraryListing.Entry.Kind.PALETTE, restricted)
                : new S2C.LibraryListing.Entry(path, false, 1024, hash, S2C.LibraryListing.Entry.Kind.SCHEMATIC, restricted);
    }

    /**
     * M4: whether listings say the folder may be changed; {@code null} (the default) follows the permissions:
     * {@code library.write} or {@code admin}.
     */
    public void setLibraryWritable(Boolean writable) {
        this.libraryWritable = writable;
    }

    /** The folders {@link #libraryCreateFolder} made (and renames kept), for tests. */
    public java.util.SortedSet<String> libraryFolders() {
        return java.util.Collections.unmodifiableSortedSet(folders);
    }

    private boolean libraryWritable(String folder) {
        if (libraryWritable != null) return libraryWritable;
        return permissions.has(Perm.LIBRARY_WRITE) || permissions.has(Perm.ADMIN);
    }

    /** Whether {@code path} is a folder here: made, or holding an asset. */
    private boolean isLibraryFolder(String path) {
        if (folders.contains(path)) return true;
        String prefix = path + "/";
        return library.keySet().stream().anyMatch(key -> key.startsWith(prefix))
                || palettes.keySet().stream().anyMatch(key -> key.startsWith(prefix))
                || folders.stream().anyMatch(made -> made.startsWith(prefix));
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    @Override
    public CompletionStage<Reply<LibraryChange>> libraryMove(String from, String to, boolean folder) {
        calls.add(new Call("move", from + " -> " + to));
        if (state != SessionState.READY) return CompletableFuture.completedFuture(disconnected());
        if (!libraryWritable(parentOf(from)) || !libraryWritable(parentOf(to))) {
            return CompletableFuture.completedFuture(refuse(RejectReason.NO_PERMISSION, "may not change " + from));
        }
        if (folder ? !isLibraryFolder(from) : !(library.containsKey(from) || palettes.containsKey(from))) {
            return CompletableFuture.completedFuture(refuse(RejectReason.INVALID, "not found: " + from));
        }
        if (library.containsKey(to) || palettes.containsKey(to) || isLibraryFolder(to)) {
            return CompletableFuture.completedFuture(refuse(RejectReason.INVALID, "already exists: " + to));
        }
        if (folder) {
            String prefix = from + "/";
            for (String key : List.copyOf(library.keySet())) {
                if (key.startsWith(prefix)) library.put(to + key.substring(from.length()), library.remove(key));
            }
            for (String key : List.copyOf(palettes.keySet())) {
                if (key.startsWith(prefix)) palettes.put(to + key.substring(from.length()), palettes.remove(key));
            }
            for (String made : List.copyOf(folders)) {
                if (made.equals(from) || made.startsWith(prefix)) {
                    folders.remove(made);
                    folders.add(to + made.substring(from.length()));
                }
            }
        } else if (palettes.containsKey(from)) {
            palettes.put(to, palettes.remove(from));
        } else {
            library.put(to, library.remove(from));
        }
        if (!folder && access.containsKey(from)) access.put(to, access.remove(from)); // the grant follows the entry
        return CompletableFuture.completedFuture(changed(new LibraryChange(folder, from, to)));
    }

    @Override
    public CompletionStage<Reply<LibraryChange>> libraryDelete(String path, boolean folder) {
        calls.add(new Call("delete", path));
        if (state != SessionState.READY) return CompletableFuture.completedFuture(disconnected());
        if (!libraryWritable(parentOf(path))) {
            return CompletableFuture.completedFuture(refuse(RejectReason.NO_PERMISSION, "may not change " + path));
        }
        if (folder) {
            if (!isLibraryFolder(path)) return CompletableFuture.completedFuture(refuse(RejectReason.INVALID, "not found: " + path));
            if (!folders.contains(path) || folders.stream().anyMatch(made -> made.startsWith(path + "/"))
                    || library.keySet().stream().anyMatch(key -> key.startsWith(path + "/"))
                    || palettes.keySet().stream().anyMatch(key -> key.startsWith(path + "/"))) {
                return CompletableFuture.completedFuture(refuse(RejectReason.INVALID, "the folder is not empty: " + path));
            }
            folders.remove(path);
        } else if (library.remove(path) == null && palettes.remove(path) == null) {
            return CompletableFuture.completedFuture(refuse(RejectReason.INVALID, "not found: " + path));
        }
        access.remove(path);
        return CompletableFuture.completedFuture(changed(new LibraryChange(folder, path, "")));
    }

    // ---- Per-asset access ----

    /** Per-asset access: who may load each entry, by path; absent means everyone (tests may put grants here). */
    public Map<String, AssetAccess> access() {
        return access;
    }

    @Override
    public CompletionStage<Reply<AssetAccess>> libraryAccess(String path) {
        calls.add(new Call("access", path));
        if (state != SessionState.READY) return CompletableFuture.completedFuture(disconnected());
        if (!libraryWritable(parentOf(path))) {
            return CompletableFuture.completedFuture(refuse(RejectReason.NO_PERMISSION, "may not change " + path));
        }
        if (!library.containsKey(path) && !palettes.containsKey(path)) {
            return CompletableFuture.completedFuture(refuse(RejectReason.INVALID, "not found: " + path));
        }
        return CompletableFuture.completedFuture(Reply.ok(access.getOrDefault(path, AssetAccess.EVERYONE)));
    }

    /**
     * Per-asset access. A typed name (no UUID) resolves to a UUID made from the name, as the server's user cache
     * would; a name starting with {@code unknown} is one the server does not know ({@code INVALID}).
     */
    @Override
    public CompletionStage<Reply<LibraryChange>> setLibraryAccess(String path, AssetAccess next) {
        calls.add(new Call("set_access", path + " " + next.mode() + " " + next.players().stream()
                .map(AssetAccess.Grantee::name).toList()));
        if (state != SessionState.READY) return CompletableFuture.completedFuture(disconnected());
        if (!libraryWritable(parentOf(path))) {
            return CompletableFuture.completedFuture(refuse(RejectReason.NO_PERMISSION, "may not change " + path));
        }
        if (!library.containsKey(path) && !palettes.containsKey(path)) {
            return CompletableFuture.completedFuture(refuse(RejectReason.INVALID, "not found: " + path));
        }
        List<AssetAccess.Grantee> resolved = new ArrayList<>();
        for (AssetAccess.Grantee grantee : next.players()) {
            if (grantee.uuid() != null) {
                resolved.add(grantee);
            } else if (grantee.name().startsWith("unknown")) {
                return CompletableFuture.completedFuture(refuse(RejectReason.INVALID, "unknown player: " + grantee.name()));
            } else {
                resolved.add(new AssetAccess.Grantee(playerId(grantee.name()), grantee.name()));
            }
        }
        if (next.restricted()) {
            access.put(path, AssetAccess.listed(resolved));
        } else {
            access.remove(path);
        }
        return CompletableFuture.completedFuture(changed(new LibraryChange(false, path, path)));
    }

    /** The UUID the mock gives a player name (the server would ask its user cache). */
    public static UUID playerId(String name) {
        return UUID.nameUUIDFromBytes(("mock-player:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public CompletionStage<Reply<LibraryChange>> libraryCreateFolder(String path) {
        calls.add(new Call("create_folder", path));
        if (state != SessionState.READY) return CompletableFuture.completedFuture(disconnected());
        if (!libraryWritable(parentOf(path))) {
            return CompletableFuture.completedFuture(refuse(RejectReason.NO_PERMISSION, "may not change " + path));
        }
        if (isLibraryFolder(path) || library.containsKey(path) || palettes.containsKey(path)) {
            return CompletableFuture.completedFuture(refuse(RejectReason.INVALID, "already exists: " + path));
        }
        folders.add(path);
        return CompletableFuture.completedFuture(changed(new LibraryChange(true, "", path)));
    }

    /** Palettes: the in-memory library's palettes, by path (tests may put some there). */
    public Map<String, BlockPalette> palettes() {
        return palettes;
    }

    /** Palettes: the next load says it left out this many entries (states "the server" doesn't know). */
    public void dropOnNextPaletteLoad(int dropped) {
        this.nextPaletteDropped = dropped;
    }

    @Override
    public CompletionStage<Reply<LibraryChange>> savePalette(String path, BlockPalette palette) {
        calls.add(new Call("save_palette", path));
        if (state != SessionState.READY) return CompletableFuture.completedFuture(disconnected());
        if (!permissions.has(Perm.CLIPBOARD)) {
            return CompletableFuture.completedFuture(refuse(RejectReason.NO_PERMISSION, Perm.CLIPBOARD.node()));
        }
        if (library.containsKey(path) || isLibraryFolder(path)) {
            return CompletableFuture.completedFuture(refuse(RejectReason.INVALID, path + " exists and is not a palette"));
        }
        palettes.put(path, palette);
        return CompletableFuture.completedFuture(changed(new LibraryChange(false, "", path)));
    }

    @Override
    public CompletionStage<Reply<LoadedPalette>> loadPalette(String path) {
        calls.add(new Call("load_palette", path));
        if (state != SessionState.READY) return CompletableFuture.completedFuture(disconnected());
        BlockPalette palette = palettes.get(path);
        if (palette == null) return CompletableFuture.completedFuture(refuse(RejectReason.INVALID, "not found: " + path));
        int dropped = nextPaletteDropped;
        nextPaletteDropped = 0;
        List<String> names = new ArrayList<>();
        for (int i = 0; i < Math.min(dropped, 3); i++) names.add("modded:gone_" + i);
        return CompletableFuture.completedFuture(Reply.ok(new LoadedPalette(path, palette, dropped, names)));
    }

    /** Pushes a change as the server would for another player's (a Library window lists its folder again). */
    public void pushLibraryChange(LibraryChange change) {
        libraryChanges.add(change);
    }

    @Override
    public LibraryChanges libraryChanges() {
        return libraryChanges;
    }

    private Reply<LibraryChange> changed(LibraryChange change) {
        libraryChanges.add(change);
        return Reply.ok(change);
    }

    @Override
    public CompletionStage<Reply<ClipboardCache.Entry>> libraryLoad(String path) {
        calls.add(new Call("load", path));
        if (state != SessionState.READY) return CompletableFuture.completedFuture(disconnected());
        if (!library.containsKey(path)) return CompletableFuture.completedFuture(refuse(RejectReason.INVALID, "no " + path));
        ClipboardCache.Entry entry = new ClipboardCache.Entry(UUID.randomUUID(), new BlockPos(3, 3, 3),
                new BlockPos(1, 0, 1), 27, 108);
        clipboards.setCurrent(entry);
        return CompletableFuture.completedFuture(Reply.ok(entry));
    }

    @Override
    public CompletionStage<Reply<SavedAsset>> saveAsset(UUID clipboardId, String path) {
        calls.add(new Call("save", path));
        if (state != SessionState.READY) return CompletableFuture.completedFuture(disconnected());
        if (clipboards.get(clipboardId).isEmpty()) {
            return CompletableFuture.completedFuture(refuse(RejectReason.INVALID, "unknown clipboard"));
        }
        String hash = dev.sculptory.core.Sha256.digest((clipboardId + path).getBytes(java.nio.charset.StandardCharsets.UTF_8)).hex();
        library.put(path, hash);
        return CompletableFuture.completedFuture(Reply.ok(new SavedAsset(path, hash)));
    }

    @Override
    public Transfer<ExportedFile> export(UUID clipboardId, SchematicFormat format) {
        calls.add(new Call("export", format == SchematicFormat.SPONGE ? clipboardId : clipboardId + " " + format));
        if (state != SessionState.READY) return Transfers.done(Transfer.Kind.EXPORT, "export", disconnected());
        if (!permissions.has(Perm.SCHEMATIC_EXPORT)) {
            return Transfers.done(Transfer.Kind.EXPORT, "export", refuse(RejectReason.NO_PERMISSION, Perm.SCHEMATIC_EXPORT.node()));
        }
        if (clipboards.get(clipboardId).isEmpty()) {
            return Transfers.done(Transfer.Kind.EXPORT, "export", refuse(RejectReason.INVALID, "unknown clipboard"));
        }
        String name = "clipboard" + format.extension();
        ExportedFile file = new ExportedFile(clipboardId, name,
                ("mock schematic " + clipboardId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (holdTransfers) {
            Transfers.Manual<ExportedFile> transfer = new Transfers.Manual<>(Transfer.Kind.EXPORT, name,
                    file.bytes().length);
            held.add(transfer);
            return transfer;
        }
        return Transfers.done(Transfer.Kind.EXPORT, name, Reply.ok(file));
    }

    @Override
    public Transfer<ClipboardCache.Entry> upload(String fileName, byte[] bytes) {
        calls.add(new Call("upload", fileName));
        if (state != SessionState.READY) return Transfers.done(Transfer.Kind.UPLOAD, fileName, disconnected());
        if (!permissions.has(Perm.SCHEMATIC_IMPORT)) {
            return Transfers.done(Transfer.Kind.UPLOAD, fileName,
                    refuse(RejectReason.NO_PERMISSION, Perm.SCHEMATIC_IMPORT.node()));
        }
        if (bytes.length > permissions.limits().maxUploadBytes()) {
            return Transfers.done(Transfer.Kind.UPLOAD, fileName, refuse(RejectReason.TOO_LARGE, bytes.length + " bytes"));
        }
        ClipboardCache.Entry entry = new ClipboardCache.Entry(UUID.randomUUID(), new BlockPos(2, 2, 2),
                new BlockPos(1, 0, 1), 8, 32);
        if (holdTransfers) {
            Transfers.Manual<ClipboardCache.Entry> transfer = new Transfers.Manual<>(Transfer.Kind.UPLOAD, fileName,
                    bytes.length);
            held.add(transfer);
            return transfer;
        }
        clipboards.setCurrent(entry);
        return Transfers.done(Transfer.Kind.UPLOAD, fileName, Reply.ok(entry));
    }

    /** Generators: a clipboard entry of the announced size (recorded as a {@code "generate"} call with its bounds). */
    @Override
    public Transfer<ClipboardCache.Entry> uploadGenerated(Box bounds, long cells, byte[] bytes) {
        calls.add(new Call("generate", bounds));
        if (state != SessionState.READY) return Transfers.done(Transfer.Kind.UPLOAD, "generated", disconnected());
        if (!permissions.has(Perm.CLIPBOARD)) {
            return Transfers.done(Transfer.Kind.UPLOAD, "generated", refuse(RejectReason.NO_PERMISSION, Perm.CLIPBOARD.node()));
        }
        if (bytes.length > permissions.limits().maxUploadBytes()) {
            return Transfers.done(Transfer.Kind.UPLOAD, "generated", refuse(RejectReason.TOO_LARGE, bytes.length + " bytes"));
        }
        if (cells > permissions.limits().maxClipboardVolume() && !permissions.has(Perm.LIMIT_BYPASS)) {
            return Transfers.done(Transfer.Kind.UPLOAD, "generated", refuse(RejectReason.TOO_LARGE, cells + " blocks"));
        }
        ClipboardCache.Entry entry = new ClipboardCache.Entry(UUID.randomUUID(),
                new BlockPos(bounds.sizeX(), bounds.sizeY(), bounds.sizeZ()), BlockPos.ORIGIN, cells, bytes.length);
        if (holdTransfers) {
            Transfers.Manual<ClipboardCache.Entry> transfer = new Transfers.Manual<>(Transfer.Kind.UPLOAD, "generated",
                    bytes.length);
            held.add(transfer);
            return transfer;
        }
        clipboards.setCurrent(entry);
        return Transfers.done(Transfer.Kind.UPLOAD, "generated", Reply.ok(entry));
    }

    @Override
    public List<Transfer<?>> transfers() {
        return held.stream().filter(transfer -> !transfer.finished()).<Transfer<?>>map(transfer -> transfer).toList();
    }

    // ---- M3: scatter ----

    /** Makes scatter previews wait until {@link #completeScatterPreview} (to test loading and superseding). */
    public void setHoldScatterPreviews(boolean hold) {
        this.holdScatter = hold;
    }

    /** The scatter preview waiting because of {@link #setHoldScatterPreviews}, if any. */
    public Optional<ScatterPreviewRequest> heldScatterPreview() {
        return Optional.ofNullable(heldScatterRequest);
    }

    /** Answers the held scatter preview, as the server would; an Ok result becomes the live plan. */
    public void completeScatterPreview(Reply<ScatterPreviewResult> reply) {
        CompletableFuture<Reply<ScatterPreviewResult>> future = heldScatter;
        if (future == null) throw new IllegalStateException("No scatter preview is held");
        heldScatter = null;
        heldScatterRequest = null;
        if (reply instanceof Reply.Ok<ScatterPreviewResult> ok) livePlan = ok.value();
        future.complete(reply);
    }

    /** Replaces how the mock plans a scatter (by default: a placement on every painting stamp's centre). */
    public void setScatterPlanner(Function<ScatterPreviewRequest, ScatterPreviewResult> planner) {
        this.scatterPlanner = Objects.requireNonNull(planner);
    }

    /** The plan a commit may use now (the latest preview, until committed). */
    public Optional<ScatterPreviewResult> livePlan() {
        return Optional.ofNullable(livePlan);
    }

    @Override
    public CompletionStage<Reply<ScatterPreviewResult>> scatterPreview(ScatterPreviewRequest request) {
        Objects.requireNonNull(request);
        calls.add(new Call("scatter_preview", request));
        // Only the latest preview counts: the previous one ends at once, without a toast.
        if (heldScatter != null) {
            CompletableFuture<Reply<ScatterPreviewResult>> old = heldScatter;
            heldScatter = null;
            heldScatterRequest = null;
            old.complete(Reply.failed(Reply.Failure.CANCELLED, "superseded"));
        }
        livePlan = null;
        if (state != SessionState.READY) return CompletableFuture.completedFuture(disconnected());
        if (!permissions.has(Perm.SCATTER)) {
            return CompletableFuture.completedFuture(refuse(RejectReason.NO_PERMISSION, Perm.SCATTER.node()));
        }
        if (holdScatter) {
            heldScatter = new CompletableFuture<>();
            heldScatterRequest = request;
            return heldScatter;
        }
        livePlan = scatterPlanner.apply(request);
        return CompletableFuture.completedFuture(Reply.ok(livePlan));
    }

    /** One placement on the centre of every painting stamp (or of the box), at y 64, variants taken in turn. */
    private ScatterPreviewResult defaultScatterPlan(ScatterPreviewRequest request) {
        List<BlockPos> anchors = new ArrayList<>();
        switch (request.area()) {
            case ScatterArea.Stamps stamps -> {
                for (ScatterArea.Stamp stamp : stamps.stamps()) {
                    if (!stamp.erase()) anchors.add(new BlockPos(stamp.x(), 64, stamp.z()));
                }
            }
            case ScatterArea.Region region -> anchors.add(new BlockPos(
                    region.box().min().x() + region.box().sizeX() / 2, 64, region.box().min().z() + region.box().sizeZ() / 2));
        }
        List<ScatterPlan.Placement> placements = new ArrayList<>();
        Box bounds = null;
        for (int i = 0; i < anchors.size(); i++) {
            BlockPos anchor = anchors.get(i);
            placements.add(new ScatterPlan.Placement(anchor, i % request.variants().size(), Transform.IDENTITY));
            Box cell = Box.of(anchor.offset(-1, 0, -1), anchor.offset(1, 2, 1));
            bounds = bounds == null ? cell : Box.of(
                    new BlockPos(Math.min(bounds.min().x(), cell.min().x()), Math.min(bounds.min().y(), cell.min().y()),
                            Math.min(bounds.min().z(), cell.min().z())),
                    new BlockPos(Math.max(bounds.max().x(), cell.max().x()), Math.max(bounds.max().y(), cell.max().y()),
                            Math.max(bounds.max().z(), cell.max().z())));
        }
        java.util.TreeMap<String, Integer> rejected = new java.util.TreeMap<>();
        rejected.put("DENSITY", 10 * placements.size());
        return new ScatterPreviewResult(++scatterRequests, UUID.randomUUID(), rejected, 27L * placements.size(), bounds,
                placements);
    }

    /** A refusal, toasted as the real session would. */
    private <T> Reply<T> refuse(RejectReason reason, String detail) {
        emit(Notice.of(Notice.Level.WARNING, SessionNotices.reasonKey(reason) + ".detail", detail));
        return Reply.refused(reason, detail);
    }

    private static <T> Reply<T> disconnected() {
        return Reply.failed(Reply.Failure.DISCONNECTED, "Not connected");
    }
}
