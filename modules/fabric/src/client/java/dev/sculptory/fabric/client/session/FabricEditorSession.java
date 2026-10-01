package dev.sculptory.fabric.client.session;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpRegions;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.palette.BlockPalette;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.schem.SchematicFormat;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.fabric.SculptoryMod;
import dev.sculptory.fabric.client.editor.mask.EditMaskModel;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.nav.Navigation;
import dev.sculptory.protocol.v2.AssetAccess;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.Codec;
import dev.sculptory.protocol.v2.Features;
import dev.sculptory.protocol.v2.Handshake;
import dev.sculptory.protocol.v2.NavigateMode;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.ProtocolException;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.protocol.v2.StreamAbort;
import dev.sculptory.protocol.v2.StreamChunk;
import dev.sculptory.protocol.v2.StreamCredit;
import dev.sculptory.protocol.v2.StreamEnd;
import dev.sculptory.protocol.v2.StreamKind;
import dev.sculptory.protocol.v2.StreamOpen;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * {@link EditorSession} over protocol v2. The Fabric glue ({@code ClientNet}) feeds it frames and lifecycle
 * events; everything else is plain logic over a {@link Transport}, so it runs in unit tests.
 *
 * <p>Lifecycle: {@link #onJoin} sends {@code Hello} when the server has the channel (otherwise the state is
 * {@code NO_SERVER_SUPPORT}); {@code Welcome} makes it {@code READY}; no answer within
 * {@link Handshake#TIMEOUT_NANOS} also means {@code NO_SERVER_SUPPORT}. {@link #onDisconnect} resets
 * everything and fails pending requests. {@link #tick} runs once per client tick: it sends each stroke's dab
 * batch and paces undo/redo steps.
 *
 * <p>Undo, redo and {@link #jumpTo} share one queue of signed steps. One step is in flight at a time (the server
 * refuses a second with {@code QUEUE_FULL}); the next goes out once its job has finished, at least
 * {@link #JUMP_STEP_NANOS} after the previous one. A refused, failed or cancelled step drops the steps queued
 * behind it.
 *
 * <p><b>Undo anyway.</b> The session follows the run of completed steps this client made in one direction since any
 * other change to the history, as the server does: a {@code HistoryState} its step in flight
 * does not explain (a push, an eviction; history bytes included) ends the run, and so does a step that did not
 * complete. When the run's steps kept changed blocks ({@code skippedConflicts}), {@link #historyOffer} offers Undo anyway
 * (or Redo anyway); accepting sends {@code HistoryOverwrite(redo, steps)}. That is a history operation too: queued
 * steps wait for it. The server says why it refused in a notice sent before its {@code JobRejected}.
 *
 * <p>Notices ({@link #onNotice}): server {@code Notice} messages are relayed as they are. An accepted undo or
 * redo raises "Undo: label" ({@link SessionNotices#historyStep}). Each job this client started raises its result
 * once both {@code JobAccepted} and {@code JobFinished} have arrived (in either order; see
 * {@link SessionNotices#jobResult}). Refusals of undo, redo and strokes raise {@link SessionNotices#rejection};
 * refusals of {@link #send} requests only complete the stage with {@link ToolResult.Rejected}, for the caller to
 * show.
 *
 * <p>M2 requests (clipboards, previews, the library and its M4 changes, schematic files) and every stream are
 * handled by {@link ClipboardTransfers}; they toast their own refusals and failures (see {@link Reply}). Scatter
 * previews (M3) and their {@code SCATTER_PLACEMENTS} streams are handled by {@link ScatterRequests}; a scatter
 * commit is a {@link ToolAction.RunOp} like any region op.
 *
 * <p><b>Regions.</b> An op or copy takes any region. For a cell set (magic select) the set is first uploaded unless the
 * server confirmed holding it on this connection ({@link ClipboardTransfers#selection}), and the op goes out naming it
 * as a {@code Region.Uploaded}; a {@code SELECTION_NOT_LOADED} answer uploads it again and retries once. Editor code
 * never sees {@code Uploaded}; an upload's refusal or failure completes the op's stage as a refusal.
 *
 * <p><b>Request order.</b> Ops, copies, undo and redo presses, jumps and Undo anyway reach the server in the order the
 * player made them. An op or copy on a cell set goes out only after its upload, and any copy only once it gets a
 * request slot; while one waits (a {@code Hold}) the requests after it are held and then go in order, strokes are
 * refused with a toast, and {@link #historyBusy} is true. A {@code SELECTION_NOT_LOADED} answer is retried only while
 * nothing went out after the op or copy. After {@link #HOLD_TIMEOUT_NANOS} everything waiting is dropped, with a toast.
 *
 * <p><b>Requests held up by the player's stroke.</b> While the player's large brush stroke is still being written or
 * committed, the server refuses an op, copy, cut, undo, redo or Undo anyway, marking the refusal with a
 * {@code stroke_pending} notice ({@link SessionNotices#STROKE_PENDING}; Undo anyway's refusal notice carries the kind).
 * Such a refusal is not reported: the request is sent again after {@link #STROKE_RETRY_NANOS}, as a hold in the request
 * order (the requests made meanwhile wait behind it, strokes are refused with a toast), until
 * {@link #STROKE_RETRY_FOR_NANOS} after it first went out; only then is its refusal reported. Requests sent before the
 * refusal came back are not moved behind the retry (the server refuses them too while the stroke is committing).
 *
 * <p>Render thread only; completion stages and listeners run on it too.
 */
public final class FabricEditorSession implements EditorSession {
    /** How frames reach the server. */
    public interface Transport {
        /** Whether the server registered {@code sculptory:c2s}. */
        boolean canSend();

        void send(byte[] frame);

        /**
         * The local player's id as the server knows it (its {@code _players/<uuid>/} folder), or {@code null} when not
         * known: then a palette save is only taken as answered at the very path asked for.
         */
        default UUID player() {
            return null;
        }
    }

    /** A completed server-to-client stream (M2: previews and schematic files). The array is not copied. */
    public record ReceivedStream(StreamOpen open, byte[] bytes) {
        public ReceivedStream {
            Objects.requireNonNull(open);
            Objects.requireNonNull(bytes);
        }
    }

    /**
     * What this client implements (M1, M2, M3 and builder mode), and that it can turn previews with the modded facing
     * fallback: the editor follows a server that negotiates {@link Features#MODDED_FACING_FALLBACK}
     * (ClientEditorBackend).
     */
    public static final Features CLIENT_FEATURES = Features.of(Features.STROKES, Features.REGION_OPS, Features.HISTORY,
            Features.CLIPBOARD, Features.SCHEMATICS, Features.LIBRARY, Features.SCATTER, Features.MODDED_FACING_FALLBACK,
            Features.TINKER, Features.BUILDER, Features.EDIT_MASK, Features.NAVIGATE);
    /** Largest inbound stream (a schematic file; previews are capped at 32 MiB). */
    public static final long MAX_INBOUND_STREAM_BYTES = ClipboardTransfers.MAX_FILE_STREAM;
    public static final int MAX_INBOUND_STREAMS = ClipboardTransfers.MAX_INBOUND_STREAMS;
    /** Resync requests sent per acknowledgement timeout (each covers at most 64 chunks). */
    public static final int MAX_RESYNC_REQUESTS = 8;
    /**
     * Ended Shape strokes kept while the server still writes them (it reports the writes of its last 8 Shape strokes,
     * the current one included).
     */
    static final int MAX_ENDED_STROKES = 7;
    /** Spacing of undo/redo steps, kept under the server's 5 ops/s limit. */
    public static final long JUMP_STEP_NANOS = 220_000_000L;
    /** A step with no answer (or job progress) for this long abandons it and the steps queued behind it. */
    public static final long JUMP_STEP_TIMEOUT_NANOS = 30_000_000_000L;
    /** How long after a scatter result the server's matching {@code scatter_skipped} notice is swallowed. */
    static final long SCATTER_SKIPPED_WAIT_NANOS = 10_000_000_000L;
    /**
     * How long requests may wait behind a selection's upload (or a copy's request slot) before they are dropped: long
     * enough for the largest upload the server may allow at the client's pace (64 MiB at 48 KiB a tick: about 70 s).
     */
    static final long HOLD_TIMEOUT_NANOS = 120_000_000_000L;
    static final String WAITING_FOR_SELECTION = "sculptory.notice.waiting_for_selection";
    static final String STROKE_WAITS_FOR_SELECTION = "sculptory.notice.stroke_waits_for_selection";
    static final String STROKE_WAITS_FOR_COPY = "sculptory.notice.stroke_waits_for_copy";
    static final String WAIT_TIMED_OUT = "sculptory.notice.wait_timed_out";
    static final String STROKE_WAITS_FOR_STROKE = "sculptory.notice.stroke_waits_for_stroke";
    /** How long after a refusal for a stroke still being applied ({@link SessionNotices#STROKE_PENDING}) it is sent again. */
    static final long STROKE_RETRY_NANOS = 250_000_000L;
    /** How long such a request keeps being sent again before its refusal is reported. */
    static final long STROKE_RETRY_FOR_NANOS = 10_000_000_000L;
    /** The detail of an op or copy given up after {@link #HOLD_TIMEOUT_NANOS}. */
    static final String GAVE_UP = "gave up waiting for the server to take the selection";

    private enum Kind {
        RUN_OP(SessionNotices.Subject.EDIT),
        /** A scatter commit: its result toast counts the placements skipped ({@link SessionNotices#scatterResult}). */
        SCATTER(SessionNotices.Subject.EDIT),
        COPY(SessionNotices.Subject.COPY),
        UNDO(SessionNotices.Subject.UNDO),
        REDO(SessionNotices.Subject.REDO),
        /** Undo anyway or Redo anyway ({@code HistoryOverwrite}); its toasts come from {@link SessionNotices#overwriteResult}. */
        OVERWRITE(SessionNotices.Subject.UNDO);

        final SessionNotices.Subject subject;

        Kind(SessionNotices.Subject subject) {
            this.subject = subject;
        }

        boolean history() {
            return this == UNDO || this == REDO;
        }
    }

    /**
     * A request waiting for its answer; {@code stepGeneration} is 0 unless it is an undo/redo step. {@code retry}, when
     * set, runs instead of completing the request on a {@code SELECTION_NOT_LOADED} refusal (an op on an uploaded
     * selection the server dropped: upload it again and send the op once more).
     */
    private record Pending(Kind kind, String label, CompletableFuture<ToolResult> future, int stepGeneration,
                           Runnable retry, StrokeRetry strokeRetry) {
        Pending(Kind kind, String label, CompletableFuture<ToolResult> future, int stepGeneration) {
            this(kind, label, future, stepGeneration, null, null);
        }

        Pending(Kind kind, String label, CompletableFuture<ToolResult> future, int stepGeneration, Runnable retry) {
            this(kind, label, future, stepGeneration, retry, null);
        }
    }

    /**
     * How an op is sent again after a refusal for the player's stroke still being applied: {@code send} sends it in
     * place of the refused one (settling {@code Hold} once it went out), {@code abandon} ends it when it never will be;
     * {@code firstAt} is when it first went out ({@link #STROKE_RETRY_FOR_NANOS} runs from there).
     */
    private record StrokeRetry(long firstAt, Consumer<Hold> send, Consumer<Abandon> abandon) {}

    /** A request waiting to be sent again ({@link #retryForStroke}) and the hold keeping the later ones behind it. */
    private record Resend(long at, Hold hold, StrokeRetry retry) {}

    private final Transport transport;
    private final Supplier<StateSpace> states;
    private final LongSupplier nanoClock;
    private final String modVersion;

    private SessionState state = SessionState.DISCONNECTED;
    private Capabilities capabilities = Capabilities.NONE;
    private Permissions permissions = Permissions.NONE;
    /** The server build id from the handshake ({@link #serverBuild}). */
    private String serverBuild = "";
    private long handshakeStartedAt;
    private int nextReqId = 1;
    private int nextStrokeId = 1;
    private final Map<Integer, Pending> pending = new HashMap<>();
    /** Accepted jobs whose JobFinished has not arrived yet: the request that started each. */
    private final Map<UUID, Pending> awaitingResult = new HashMap<>();
    private final Map<Integer, FabricStrokeHandle> strokes = new LinkedHashMap<>();
    private final SessionJobTracker jobs = new SessionJobTracker();
    private final SessionHistoryMirror history = new SessionHistoryMirror();
    private final SessionClipboardCache clipboards = new SessionClipboardCache();
    private final ClipboardTransfers transfers;
    private final ScatterRequests scatter;
    /** Tinker requests (protocol 5). */
    private final TinkerRequests tinker;
    /** The global mask's copy on the server. */
    private final EditMaskSync maskSync;
    private final Listeners<Consumer<Notice>> noticeListeners = new Listeners<>();
    private final Listeners<Consumer<ReceivedStream>> streamListeners = new Listeners<>();

    // Undo/redo steps (see the class comment).
    /** Bumped whenever the queue is abandoned, so answers to abandoned steps stop driving it. Never 0. */
    private int stepGeneration = 1;
    /** Steps still to send: negative undoes, positive redoes. */
    private int stepsQueued;
    /** The step in flight: -1 undo, +1 redo, 0 none. In flight until its JobFinished or refusal. */
    private int stepInFlight;
    /** The request id of the step in flight. */
    private int stepReqId;
    /** The direction of the last step sent (-1 undo, +1 redo): what the next HistoryState may show. */
    private int stepLastDirection;
    /** The job of the step in flight, once accepted. */
    private UUID stepJob;
    private boolean stepSent;
    private long stepLastSend;
    private long stepActivityAt;
    /** The queue came from {@link #jumpTo}: running out of history then ends it without a toast. */
    private boolean stepsFromJump;
    /** The jump's size and the steps of it finished, for {@link #historyJump}. */
    private int jumpTotal;
    private int jumpDone;
    private boolean jumpUndo;

    // Undo anyway (see the class comment).
    /** The run of completed steps: -1 undo, +1 redo, 0 none; how many, and the blocks they kept. */
    private int runDirection;
    private int runSteps;
    private long runSkipped;
    /** Bumped whenever the run changes: the offer's id. */
    private long runId;
    /** The step in flight moved the history (its own HistoryState came), or a change it doesn't explain came first. */
    private boolean stepMoved;
    private boolean stepBroken;
    /** The direction of a completed step whose own history state has not come yet (0 when none is owed). */
    private int moveOwed;
    /** The overwrite sent and not finished (request id, 0 for none), its job once accepted, and its last sign of life. */
    private int overwriteReqId;
    private UUID overwriteJob;
    private long overwriteActivityAt;
    /**
     * The server's detail and kind for refusing the overwrite in flight: its notice comes right before the JobRejected.
     * The kind is {@link SessionNotices#OVERWRITE_RUN_REFUSED} when the server's run is not the one offered.
     */
    private String overwriteRefusal;
    private String overwriteRefusalKind;
    /** Hello went out on this connection, so a Welcome that arrives after the timeout is still accepted. */
    private boolean helloSent;
    /** The skipped placements of the scatter result just toasted, and until when its server notice is expected. */
    private long expectedScatterSkips;
    private long expectedScatterSkipsUntil;

    // Request order (see the class comment).
    /** Why a held request ends without going out. */
    private enum Abandon { DISCONNECTED, TIMED_OUT, FAILED }

    /** A request held back; {@code history} for undo, redo, jumps and Undo anyway (Esc and Stop drop those). */
    private record HeldBack(boolean history, Runnable action, Consumer<Abandon> abandon) {}

    private final ArrayDeque<HeldBack> heldBack = new ArrayDeque<>();
    /** Live {@link Hold}s: requests the held ones wait behind, not gone out yet. */
    private int holds;
    /** Of {@link #holds}, the ones waiting for a cell set's upload (the others are copies waiting for a request slot). */
    private int selectionHolds;
    /** When {@link #holds} last became non-zero: {@link #HOLD_TIMEOUT_NANOS} runs from there. */
    private long holdingSince;
    /** Bumped on every reset and when a hold times out: holds of an earlier order no longer count, nor go out. */
    private int orderEpoch;
    /** Requests sent that the order covers (ops, copies, undo, redo, Undo anyway, strokes): see {@link Hold#mayRetry}. */
    private long orderedSends;
    /** Of {@link #holds}, the ones of requests waiting to be sent again for a stroke still being applied. */
    private int strokeHolds;

    // Requests held up by the player's brush stroke (see the class comment).
    /** Requests whose refusal the server marked as caused by the player's stroke ({@code stroke_pending} notices). */
    private final Set<Integer> strokePending = new LinkedHashSet<>();
    /** Requests to send again, in the order they were refused. */
    private final ArrayDeque<Resend> resends = new ArrayDeque<>();
    /** The undo/redo step refused for the stroke: when it first was, and when it may go again (0: none). */
    private long stepHeldSince;
    private long stepNotBefore;
    /** The hold keeping later requests behind that step. */
    private Hold stepHold;
    /** Undo anyway refused for the stroke: when it first went out (0: none waiting). */
    private long overwriteHeldSince;

    /**
     * @param states the client's state space, rebuilt on every join; read whenever block states are encoded
     * @param nanoClock a monotonic clock, normally {@code System::nanoTime}
     */
    public FabricEditorSession(Transport transport, Supplier<StateSpace> states, LongSupplier nanoClock, String modVersion) {
        this(transport, states, nanoClock, modVersion, Runnable::run);
    }

    /**
     * @param decoder runs preview decoding off the render thread (the result is installed at the next {@link #tick});
     *     {@code Runnable::run} decodes on the caller's thread
     */
    public FabricEditorSession(Transport transport, Supplier<StateSpace> states, LongSupplier nanoClock, String modVersion,
                               Executor decoder) {
        this(transport, states, nanoClock, modVersion, decoder, PreviewDecoder.Limits.DEFAULT);
    }

    /** @param previewLimits what one decoded preview may cost this client (memory, sections, cells) */
    public FabricEditorSession(Transport transport, Supplier<StateSpace> states, LongSupplier nanoClock, String modVersion,
                               Executor decoder, PreviewDecoder.Limits previewLimits) {
        this(transport, states, nanoClock, modVersion, decoder, decoder, previewLimits);
    }

    /**
     * @param encoder encodes selections for upload (up to about a second for the largest), off the render thread; the
     *     game gives it a thread of its own, so it never holds up the worker pool that meshes chunks
     */
    public FabricEditorSession(Transport transport, Supplier<StateSpace> states, LongSupplier nanoClock, String modVersion,
                               Executor decoder, Executor encoder, PreviewDecoder.Limits previewLimits) {
        this.transport = Objects.requireNonNull(transport);
        this.states = Objects.requireNonNull(states);
        this.nanoClock = Objects.requireNonNull(nanoClock);
        this.modVersion = Objects.requireNonNull(modVersion);
        this.transfers = new ClipboardTransfers(new TransferLink(), clipboards, Objects.requireNonNull(decoder),
                Objects.requireNonNull(encoder), previewLimits);
        this.scatter = new ScatterRequests(new ScatterLink());
        this.tinker = new TinkerRequests(new TinkerLink());
        this.maskSync = new EditMaskSync(EditMaskModel.global(), new MaskLink());
    }

    /** What {@link EditMaskSync} reaches of this session. */
    private final class MaskLink implements EditMaskSync.Link {
        @Override
        public long now() {
            return FabricEditorSession.this.now();
        }

        @Override
        public boolean ready() {
            return state == SessionState.READY;
        }

        @Override
        public Features features() {
            return capabilities.features();
        }

        @Override
        public int nextReqId() {
            return nextReqId++;
        }

        @Override
        public String send(C2S message) {
            return sendMessage(message);
        }

        @Override
        public CompletionStage<Reply<Sha256>> upload(CellSet cells) {
            return transfers.selection(cells, true);
        }

        @Override
        public void forgetUpload(Sha256 hash) {
            transfers.forgetSelection(hash);
        }

        @Override
        public void notice(Notice notice) {
            FabricEditorSession.this.notice(notice);
        }
    }

    /** The global mask this session follows instead of the game's (tests). */
    void followMask(EditMaskModel model) {
        maskSync.follow(model);
    }

    /** What {@link TinkerRequests} reaches of this session. */
    private final class TinkerLink implements TinkerRequests.Link {
        @Override
        public long now() {
            return FabricEditorSession.this.now();
        }

        @Override
        public int nextReqId() {
            return nextReqId++;
        }

        @Override
        public String send(C2S message) {
            return sendMessage(message);
        }

        @Override
        public void notice(Notice notice) {
            FabricEditorSession.this.notice(notice);
        }

        @Override
        public Features features() {
            return capabilities.features();
        }

        @Override
        public Permissions permissions() {
            return permissions;
        }

        @Override
        public void newEdit() {
            FabricEditorSession.this.newEdit();
        }
    }

    /** What {@link ScatterRequests} reaches of this session. */
    private final class ScatterLink implements ScatterRequests.Link {
        @Override
        public long now() {
            return FabricEditorSession.this.now();
        }

        @Override
        public int nextReqId() {
            return nextReqId++;
        }

        @Override
        public String send(C2S message) {
            return sendMessage(message);
        }

        @Override
        public void notice(Notice notice) {
            FabricEditorSession.this.notice(notice);
        }

        @Override
        public Features features() {
            return capabilities.features();
        }

        @Override
        public boolean streamIdTaken(int id) {
            return transfers.ownsStream(id);
        }

        @Override
        public void streamCompleted(ReceivedStream stream) {
            streamListeners.forEach(l -> l.accept(stream));
        }
    }

    /** What {@link ClipboardTransfers} reaches of this session. */
    private final class TransferLink implements ClipboardTransfers.Link {
        @Override
        public long now() {
            return FabricEditorSession.this.now();
        }

        @Override
        public int nextReqId() {
            return nextReqId++;
        }

        @Override
        public String send(C2S message) {
            return sendMessage(message);
        }

        @Override
        public void notice(Notice notice) {
            FabricEditorSession.this.notice(notice);
        }

        @Override
        public Permissions permissions() {
            return permissions;
        }

        @Override
        public Features features() {
            return capabilities.features();
        }

        @Override
        public StateSpace states() {
            return states.get();
        }

        @Override
        public UUID player() {
            return transport.player();
        }

        @Override
        public void cutAccepted(S2C.JobAccepted accepted, String label) {
            Pending p = new Pending(Kind.COPY, label, null, 0);
            jobs.accepted(accepted.jobId(), label, accepted.estCells());
            JobTracker.Job job = jobs.job(accepted.jobId()).orElse(null);
            if (job != null && job.finished()) {
                announceResult(job, p);
            } else {
                awaitingResult.put(accepted.jobId(), p);
            }
        }

        @Override
        public void newEdit() {
            FabricEditorSession.this.newEdit();
        }

        @Override
        public boolean strokePending(int reqId) {
            return strokePending.remove(reqId);
        }

        @Override
        public void sendAgain(ClipboardTransfers.Order order, Runnable send, Runnable abandon) {
            if (!(order instanceof Hold hold)) {
                abandon.run();
                return;
            }
            resends.add(new Resend(now() + STROKE_RETRY_NANOS, hold, new StrokeRetry(0, h -> send.run(), why -> abandon.run())));
        }

        @Override
        public void streamCompleted(ReceivedStream stream) {
            streamListeners.forEach(l -> l.accept(stream));
        }
    }

    // =================================================================== lifecycle (driven by ClientNet)

    /**
     * The client joined a world: starts the handshake if the server has the Sculptory channel. Without a
     * state space for this connection nothing can be encoded, so the session reports {@code INCOMPATIBLE}
     * (with an error notice) instead of ever becoming {@code READY}.
     */
    public void onJoin() {
        reset();
        if (!transport.canSend()) {
            state = SessionState.NO_SERVER_SUPPORT;
            return;
        }
        if (states.get() == null) {
            state = SessionState.INCOMPATIBLE;
            notice(Notice.Level.ERROR, "sculptory.notice.no_block_states");
            return;
        }
        if (sendMessage(Handshake.hello(modVersion, CLIENT_FEATURES)) != null) {
            state = SessionState.NO_SERVER_SUPPORT;
            return;
        }
        state = SessionState.HANDSHAKING;
        helloSent = true;
        handshakeStartedAt = now();
    }

    /** The client left the world: resets everything and fails pending requests. */
    public void onDisconnect() {
        reset();
    }

    /** Once per client tick. */
    public void tick() {
        long now = now();
        if (state == SessionState.HANDSHAKING && now - handshakeStartedAt > Handshake.TIMEOUT_NANOS) {
            SculptoryMod.LOG.info("Sculptory: no handshake answer; the server does not run Sculptory");
            state = SessionState.NO_SERVER_SUPPORT;
        }
        if (state == SessionState.READY) {
            if (holds > 0 && now - holdingSince > HOLD_TIMEOUT_NANOS) holdTimedOut();
            for (FabricStrokeHandle stroke : List.copyOf(strokes.values())) stroke.tick(now);
            sendAgain(now);
            stepHistory(now);
            transfers.tick(now);
            scatter.tick(now);
            tinker.tick(now);
            maskSync.tick(now);
        }
        jobs.prune(now);
    }

    /** A {@code sculptory:s2c} frame arrived. */
    public void onFrame(byte[] frame) {
        S2C message;
        try {
            message = Codec.decodeS2C(frame, states.get());
        } catch (ProtocolException e) {
            SculptoryMod.LOG.warn("Sculptory: ignoring a malformed server message: {}", e.getMessage());
            return;
        } catch (RuntimeException e) {
            // No state space yet (it failed to build) for a message that carries block states.
            SculptoryMod.LOG.warn("Sculptory: cannot decode a server message: {}", e.toString());
            return;
        }
        if (message instanceof S2C.Welcome welcome) {
            welcome(welcome);
        } else if (message instanceof S2C.Incompatible incompatible) {
            incompatible(incompatible);
        } else if (state == SessionState.READY) {
            handle(message);
        }
    }

    /** Handshake answers count while handshaking, and after a timeout if our Hello did go out. */
    private boolean awaitingHandshakeAnswer() {
        return state == SessionState.HANDSHAKING || (state == SessionState.NO_SERVER_SUPPORT && helloSent);
    }

    private void welcome(S2C.Welcome m) {
        if (!awaitingHandshakeAnswer()) return;
        if (!Handshake.supports(m.protocol())) {
            state = SessionState.INCOMPATIBLE;
            notice(Notice.Level.ERROR, "sculptory.notice.incompatible", Integer.toString(m.protocol()),
                    protocols(Handshake.MIN_PROTOCOL, Handshake.MAX_PROTOCOL));
            return;
        }
        state = SessionState.READY;
        capabilities = new Capabilities(m.protocol(), m.features(), m.sessionEpoch());
        permissions = new Permissions(m.permissions(), m.limits());
        // Cleaned before it is stored, logged or shown: it is whatever the server sent.
        serverBuild = Handshake.cleanBuild(m.serverBuild());
        SculptoryMod.LOG.info("Sculptory: server ready (protocol {}, server build {}, this client's build {}, max op "
                + "{} blocks, max brush radius {})", m.protocol(), serverBuild.isEmpty() ? "unknown" : serverBuild,
                modVersion, m.limits().maxOpVolume(), m.limits().maxBrushRadius());
        // Same protocol, so editing works; a different build may still behave differently, so say so once.
        if (Handshake.differentBuilds(serverBuild, modVersion)) {
            notice(Notice.Level.WARNING, "sculptory.notice.different_build", serverBuild, modVersion);
        }
        maskSync.welcome();
    }

    private void incompatible(S2C.Incompatible m) {
        if (!awaitingHandshakeAnswer()) return;
        state = SessionState.INCOMPATIBLE;
        serverBuild = Handshake.cleanBuild(m.serverBuild());
        String server = protocols(m.serverMinProtocol(), m.serverMaxProtocol());
        String client = protocols(Handshake.MIN_PROTOCOL, Handshake.MAX_PROTOCOL);
        SculptoryMod.LOG.warn("Sculptory: this server's Sculptory speaks another protocol (server build {}, "
                + "protocol {}; this client's build {}, protocol {}): editing is off here", serverBuild.isEmpty()
                ? "unknown" : serverBuild, server, modVersion, client);
        if (serverBuild.isEmpty()) {
            notice(Notice.Level.ERROR, "sculptory.notice.incompatible", server, client);
        } else {
            notice(Notice.Level.ERROR, "sculptory.notice.incompatible_build", serverBuild, server, modVersion, client);
        }
    }

    /** A protocol range as the notices show it: {@code 4}, or {@code 4-5}. */
    static String protocols(int min, int max) {
        return min == max ? Integer.toString(min) : min + "-" + max;
    }

    /** The server's build id from the handshake ({@code ""} before it, or when the server sent none). */
    public String serverBuild() {
        return serverBuild;
    }

    /** This client's build id, sent in {@code Hello}. */
    public String clientBuild() {
        return modVersion;
    }

    private void handle(S2C message) {
        long now = now();
        switch (message) {
            case S2C.Welcome m -> { }
            case S2C.Incompatible m -> { }
            case S2C.PermissionsChanged m -> permissions = new Permissions(m.permissions(), m.limits());
            case S2C.StrokeStatus m -> {
                FabricStrokeHandle stroke = strokes.get(m.strokeId());
                if (stroke != null) {
                    stroke.onStatus(m, now);
                    // An ended Shape stroke stays while the server still writes its dabs (it reports each batch).
                    if (stroke.ended() && stroke.unapplied() == 0) strokes.remove(m.strokeId());
                }
            }
            case S2C.JobAccepted m -> {
                if (pending.containsKey(m.reqId()) || !transfers.jobAccepted(m)) jobAccepted(m);
            }
            case S2C.JobRejected m -> {
                if (scatter.jobRejected(m)) {
                    // Scatter previews are not sent again: their refusal is shown as it is.
                    strokePending.remove(m.reqId());
                    return;
                }
                if (pending.containsKey(m.reqId()) || !transfers.jobRejected(m)) jobRejected(m);
            }
            case S2C.JobProgress m -> {
                jobs.progress(m);
                if (m.jobId().equals(stepJob)) stepActivityAt = now;
                if (m.jobId().equals(overwriteJob)) overwriteActivityAt = now;
            }
            case S2C.JobFinished m -> jobFinished(m, now);
            case S2C.HistoryState m -> historyState(m);
            case S2C.ClipboardReady m -> transfers.clipboardReady(m);
            case S2C.LibraryListing m -> transfers.libraryListing(m);
            case S2C.AssetSaved m -> transfers.assetSaved(m);
            case S2C.LibraryChanged m -> transfers.libraryChanged(m);
            case S2C.PaletteData m -> transfers.paletteData(m);
            case S2C.LibraryAccess m -> transfers.libraryAccess(m);
            case S2C.SelectionReady m -> transfers.selectionReady(m);
            case S2C.UploadGrant m -> transfers.uploadGrant(m);
            case S2C.UploadResult m -> transfers.uploadResult(m);
            case S2C.ScatterPlan m -> scatter.plan(m);
            case S2C.TinkerResult m -> tinker.result(m);
            case S2C.EditMaskState m -> maskSync.state(m);
            case S2C.NavigateResult m -> Navigation.result(m).ifPresent(this::notice);
            case S2C.Notice m -> {
                if (m.key().equals(SessionNotices.OVERWRITE_REFUSED_BY_SERVER)) {
                    // Why the overwrite in flight is refused: its JobRejected follows and raises the toast.
                    if (overwriteReqId != 0) {
                        overwriteRefusal = m.args().size() > 1 ? m.args().get(1) : "";
                        overwriteRefusalKind = m.args().size() > 2 ? m.args().get(2) : "";
                    }
                    return;
                }
                if (m.key().equals(SessionNotices.STROKE_PENDING)) {
                    // The refusal that follows is only the player's stroke still being applied: sent again, not shown.
                    try {
                        if (!m.args().isEmpty()) strokePending.add(Integer.parseInt(m.args().get(0)));
                        // Refusals answered elsewhere (a scatter preview) are not taken: keep only the latest few.
                        if (strokePending.size() > 64) strokePending.remove(strokePending.iterator().next());
                    } catch (NumberFormatException e) {
                        SculptoryMod.LOG.warn("Sculptory: ignoring a malformed stroke_pending notice {}", m.args());
                    }
                    return;
                }
                if (announcedScatterSkips(m, now)) return;
                Notice shown = transfers.onNotice(new Notice(level(m.level()), m.key(), m.args()));
                if (shown != null) notice(shown);
            }
            case StreamOpen m -> streamOpen(m);
            case StreamChunk m -> {
                if (!scatter.streamChunk(m)) transfers.streamChunk(m);
            }
            case StreamEnd m -> {
                if (!scatter.streamEnd(m)) transfers.streamEnd(m);
            }
            case StreamAbort m -> {
                if (!scatter.streamAbort(m)) transfers.streamAbort(m);
            }
            case StreamCredit m -> transfers.streamCredit(m);
        }
    }

    /** Scatter placements and grown cells go to {@link ScatterRequests}; every other stream to {@link ClipboardTransfers}. */
    private void streamOpen(StreamOpen m) {
        if (m.kind() == StreamKind.SCATTER_PLACEMENTS || m.kind() == StreamKind.SCATTER_GENERATED) {
            scatter.streamOpen(m);
        } else if (scatter.ownsStream(m.id())) {
            SculptoryMod.LOG.warn("Sculptory: ignoring a second stream {} while it carries scatter placements", m.id());
        } else {
            transfers.streamOpen(m);
        }
    }

    private void reset() {
        state = SessionState.DISCONNECTED;
        // First, so holds of this connection settling during the reset below no longer release anything.
        List<HeldBack> held = endOrder();
        helloSent = false;
        capabilities = Capabilities.NONE;
        permissions = Permissions.NONE;
        serverBuild = "";
        cancelSteps();
        stepSent = false;
        stepLastDirection = 0;
        endRun();
        stepMoved = false;
        stepBroken = false;
        moveOwed = 0;
        overwriteReqId = 0;
        overwriteJob = null;
        overwriteRefusal = null;
        overwriteRefusalKind = null;
        overwriteHeldSince = 0;
        stepHeldSince = 0;
        stepNotBefore = 0;
        strokePending.clear();
        List<Resend> waiting = new ArrayList<>(resends);
        resends.clear();
        List<Pending> abandoned = new ArrayList<>(pending.values());
        pending.clear();
        awaitingResult.clear();
        List<FabricStrokeHandle> open = new ArrayList<>(strokes.values());
        strokes.clear();
        open.forEach(FabricStrokeHandle::disconnect);
        transfers.reset();
        scatter.reset();
        tinker.reset();
        maskSync.reset();
        jobs.clear();
        history.clear();
        clipboards.clear();
        for (Pending p : abandoned) {
            if (p.future() != null) p.future().complete(new ToolResult.Rejected(RejectReason.DISABLED, "Disconnected"));
        }
        for (Resend r : waiting) giveUp(r.retry(), Abandon.DISCONNECTED);
        abandon(held, Abandon.DISCONNECTED);
    }

    // =================================================================== EditorSession

    @Override
    public SessionState state() {
        return state;
    }

    @Override
    public Capabilities capabilities() {
        return capabilities;
    }

    @Override
    public Permissions permissions() {
        return permissions;
    }

    @Override
    public CompletionStage<ToolResult> send(ToolAction action) {
        Objects.requireNonNull(action);
        if (state != SessionState.READY) return notAvailable();
        return switch (action) {
            case ToolAction.RunOp a -> {
                CompletableFuture<ToolResult> result = new CompletableFuture<>();
                inOrder(false, () -> runOp(a, result), why -> result.complete(switch (why) {
                    case DISCONNECTED -> disconnectedResult();
                    case TIMED_OUT -> gaveUpResult();
                    case FAILED -> new ToolResult.Rejected(RejectReason.INVALID, "the op could not be sent");
                }));
                yield result;
            }
            case ToolAction.Cancel a -> {
                String failure = sendMessage(new C2S.CancelJob(a.jobId()));
                yield CompletableFuture.completedFuture(failure == null
                        ? new ToolResult.Done()
                        : new ToolResult.Rejected(RejectReason.INVALID, failure));
            }
            case ToolAction.Copy a -> copy(new Region.Cuboid(a.box()), a.origin(), a.cut(), a.mask(), EntityFilter.NONE)
                    .thenApply(reply -> switch (reply) {
                        case Reply.Ok<ClipboardCache.Entry> ok -> (ToolResult) new ToolResult.Done();
                        case Reply.Refused<ClipboardCache.Entry> refused -> new ToolResult.Rejected(refused.reason(),
                                refused.detail());
                        case Reply.Failed<ClipboardCache.Entry> failed -> new ToolResult.Rejected(
                                failed.failure() == Reply.Failure.DISCONNECTED ? RejectReason.DISABLED : RejectReason.INVALID,
                                failed.detail());
                    });
        };
    }

    /**
     * Sends a region op now: a cell set's after its upload ({@link #runOnSelection}), which holds back later requests.
     * Undo and redo presses still queued from before no longer apply, as when it goes out at once.
     */
    private void runOp(ToolAction.RunOp a, CompletableFuture<ToolResult> result) {
        newEdit();
        if (OpRegions.region(a.op()) instanceof Region.Cells cells) {
            runOnSelection(a, cells.cells(), true, result, new Hold(true), now());
            return;
        }
        sendOp(a, result, now());
    }

    /**
     * Sends a region op on a box (first at {@code firstAt}); a refusal for the player's stroke still being applied sends
     * it again a moment later ({@link #retryForStroke}).
     */
    private void sendOp(ToolAction.RunOp a, CompletableFuture<ToolResult> result, long firstAt) {
        int reqId = nextReqId++;
        String failure = sendMessage(new C2S.RunOp(reqId, a.op(), a.physics(), ConflictPolicy.SKIP_CONFLICTS, a.label()));
        if (failure != null) {
            RejectReason reason = failure.startsWith("TOO_LARGE") ? RejectReason.TOO_LARGE : RejectReason.INVALID;
            result.complete(new ToolResult.Rejected(reason, failure));
            return;
        }
        StrokeRetry again = new StrokeRetry(firstAt, hold -> {
            try {
                sendOp(a, result, firstAt);
            } finally {
                hold.settled();
            }
        }, why -> result.complete(abandoned(why)));
        pending.put(reqId, new Pending(a.op() instanceof OpSpec.ScatterCommit ? Kind.SCATTER : Kind.RUN_OP, labelOf(a),
                result, 0, null, again));
    }

    private static ToolResult abandoned(Abandon why) {
        return switch (why) {
            case DISCONNECTED -> disconnectedResult();
            case TIMED_OUT -> gaveUpResult();
            case FAILED -> new ToolResult.Rejected(RejectReason.INVALID, "the op could not be sent");
        };
    }

    // =================================================================== requests held up by the player's stroke

    /**
     * The server refused a request only because the player's brush stroke is still being written or committed
     * ({@link SessionNotices#STROKE_PENDING}): unless {@link #STROKE_RETRY_FOR_NANOS} has passed since it first went out,
     * it is sent again after {@link #STROKE_RETRY_NANOS}, and the requests the player makes meanwhile wait behind it (a
     * {@link Hold}; strokes are refused). Returns false when it is not sent again (its refusal is then reported).
     */
    private boolean retryForStroke(StrokeRetry retry) {
        if (retry == null || now() - retry.firstAt() >= STROKE_RETRY_FOR_NANOS) return false;
        resends.add(new Resend(now() + STROKE_RETRY_NANOS, new Hold(false, true), retry));
        return true;
    }

    /** Sends again the requests whose time has come, in order ({@link #retryForStroke}). */
    private void sendAgain(long now) {
        while (!resends.isEmpty() && now - resends.peekFirst().at() >= 0) {
            Resend next = resends.pollFirst();
            if (!next.hold().live()) {
                // The order was given up meanwhile (a timeout): it no longer goes out.
                giveUp(next.retry(), Abandon.TIMED_OUT);
                continue;
            }
            try {
                next.retry().send().accept(next.hold());
            } catch (RuntimeException e) {
                SculptoryMod.LOG.error("Sculptory: sending a request again failed", e);
                next.hold().settled();
                giveUp(next.retry(), Abandon.FAILED);
            }
        }
    }

    private static void giveUp(StrokeRetry retry, Abandon why) {
        try {
            retry.abandon().accept(why);
        } catch (RuntimeException e) {
            SculptoryMod.LOG.error("Sculptory: ending a request failed", e);
        }
    }

    // =================================================================== request order (regions)

    /**
     * A request the ones after it wait behind until it has gone out: an op or copy on a cell set (its upload comes
     * first) or any copy (it waits for a request slot). {@link #settled} once it went out, or ended without going out;
     * a hold of an earlier order ({@link #orderEpoch}: a reset or {@link #holdTimedOut}) no longer counts and its request
     * no longer goes out ({@link #live}).
     */
    private final class Hold implements ClipboardTransfers.Order {
        private final int epoch = orderEpoch;
        private final boolean selection;
        /** The request waits to be sent again for the player's stroke ({@link #retryForStroke}). */
        private final boolean stroke;
        private boolean settled;
        /** {@link #orderedSends} when it settled: whether anything went out after it. */
        private long sentMark;
        /** A copy's: when it first went out, for {@link #retriesForStroke} (0 until then). */
        private long firstAt;

        Hold(boolean selection) {
            this(selection, false);
        }

        Hold(boolean selection, boolean stroke) {
            this.selection = selection;
            this.stroke = stroke;
            if (holds++ == 0) holdingSince = now();
            if (selection) selectionHolds++;
            if (stroke) strokeHolds++;
        }

        @Override
        public boolean live() {
            return epoch == orderEpoch;
        }

        @Override
        public void settled() {
            if (settled) return;
            settled = true;
            sentMark = orderedSends;
            if (!live()) return;
            holds--;
            if (selection) selectionHolds--;
            if (stroke) strokeHolds--;
            if (holds == 0) releaseHeld();
        }

        /**
         * Whether its request may be sent again after {@code SELECTION_NOT_LOADED}: only while nothing the order covers
         * went out after it and nothing waits, so the retry is still in the player's order.
         */
        @Override
        public boolean mayRetry() {
            return live() && settled && orderedSends == sentMark && holds == 0 && heldBack.isEmpty()
                    && state == SessionState.READY;
        }

        /** A hold for the retry, which the requests issued meanwhile wait behind too. */
        @Override
        public Hold again() {
            return new Hold(selection);
        }

        /**
         * Whether a refusal of its copy for the player's stroke still being applied is sent again rather than reported:
         * while the order stands and {@link #STROKE_RETRY_FOR_NANOS} has not passed since the copy first went out.
         */
        @Override
        public boolean retriesForStroke() {
            if (firstAt == 0) firstAt = now();
            return live() && state == SessionState.READY && now() - firstAt < STROKE_RETRY_FOR_NANOS;
        }

        /** A hold for sending the copy again after such a refusal, keeping when it first went out. */
        @Override
        public Hold forStroke() {
            Hold next = new Hold(selection, true);
            next.firstAt = firstAt == 0 ? now() : firstAt;
            return next;
        }
    }

    /**
     * Runs {@code action} now, or, while a {@link Hold} is live, after it and every request held back before
     * ({@code abandon} runs instead when the connection ends, the hold times out or the action throws). So an op, copy,
     * undo or redo issued after an op on a magic selection reaches the server after it, as the player issued them.
     */
    private void inOrder(boolean history, Runnable action, Consumer<Abandon> abandon) {
        if (holds == 0 && heldBack.isEmpty()) {
            action.run();
            return;
        }
        if (heldBack.isEmpty() && selectionHolds > 0) notice(Notice.Level.INFO, WAITING_FOR_SELECTION);
        heldBack.add(new HeldBack(history, action, abandon));
    }

    /** The last hold settled: the held requests go, in order, until one holds again. One failing does not stop the rest. */
    private void releaseHeld() {
        while (holds == 0 && !heldBack.isEmpty()) {
            HeldBack next = heldBack.poll();
            try {
                next.action().run();
            } catch (RuntimeException e) {
                SculptoryMod.LOG.error("Sculptory: a request held behind a selection upload failed", e);
                abandon(List.of(next), Abandon.FAILED);
            }
        }
        if (holds == 0 && state == SessionState.READY) stepHistory(now());
    }

    /** Whether requests wait behind a hold (undo and redo steps wait too; strokes are refused meanwhile). */
    private boolean holding() {
        return holds > 0 || !heldBack.isEmpty();
    }

    /** Ends the current order: its holds no longer count or go out; returns the held requests, now dropped. */
    private List<HeldBack> endOrder() {
        orderEpoch++;
        holds = 0;
        selectionHolds = 0;
        strokeHolds = 0;
        stepHold = null;
        List<HeldBack> held = new ArrayList<>(heldBack);
        heldBack.clear();
        return held;
    }

    private static void abandon(List<HeldBack> held, Abandon why) {
        for (HeldBack h : held) {
            try {
                h.abandon().accept(why);
            } catch (RuntimeException e) {
                SculptoryMod.LOG.error("Sculptory: ending a held request failed", e);
            }
        }
    }

    /**
     * A hold lasted {@link #HOLD_TIMEOUT_NANOS}: the requests held behind it are dropped, and the ops and copies holding
     * them never go out (they end as given up when their uploads finish), so nothing reaches the server out of order.
     */
    private void holdTimedOut() {
        SculptoryMod.LOG.warn("Sculptory: gave up waiting for a selection upload; {} request(s) behind it dropped",
                heldBack.size());
        List<HeldBack> held = endOrder();
        notice(Notice.Level.WARNING, WAIT_TIMED_OUT);
        abandon(held, Abandon.TIMED_OUT);
    }

    private static ToolResult disconnectedResult() {
        return new ToolResult.Rejected(RejectReason.DISABLED, "Disconnected");
    }

    /** An op given up after {@link #HOLD_TIMEOUT_NANOS} (the caller shows it as a busy server). */
    private static ToolResult gaveUpResult() {
        return new ToolResult.Rejected(RejectReason.QUEUE_FULL, GAVE_UP);
    }

    // =================================================================== M2 requests (see ClipboardTransfers)

    @Override
    public CompletionStage<Reply<ClipboardCache.Entry>> copy(Region region, BlockPos origin, boolean cut,
                                                              EntityFilter entities) {
        return copy(region, origin, cut, CellMask.ANY, entities);
    }

    /**
     * A copy or cut in request order ({@link #inOrder}); it holds back the requests after it until its request goes out
     * (a cell set's after its upload; any copy may wait for a request slot).
     */
    private CompletionStage<Reply<ClipboardCache.Entry>> copy(Region region, BlockPos origin, boolean cut, CellMask mask,
                                                               EntityFilter entities) {
        Objects.requireNonNull(region);
        Objects.requireNonNull(origin);
        Objects.requireNonNull(mask);
        Objects.requireNonNull(entities);
        if (state != SessionState.READY) return CompletableFuture.completedFuture(disconnected());
        CompletableFuture<Reply<ClipboardCache.Entry>> result = new CompletableFuture<>();
        inOrder(false, () -> {
            Hold hold = new Hold(region instanceof Region.Cells);
            try {
                transfers.copy(region, origin, cut, mask, entities, hold).whenComplete((reply, error) -> result.complete(
                        reply != null ? reply : Reply.failed(Reply.Failure.ABORTED, String.valueOf(error))));
            } catch (RuntimeException e) {
                hold.settled();
                throw e;
            }
        }, why -> result.complete(switch (why) {
            case DISCONNECTED -> disconnected();
            case TIMED_OUT -> Reply.failed(Reply.Failure.TIMED_OUT, GAVE_UP);
            case FAILED -> Reply.failed(Reply.Failure.ABORTED, "the copy could not be sent");
        }));
        return result;
    }

    @Override
    public Transfer<ClipboardCache.Preview> requestPreview(SourceRef source) {
        if (state != SessionState.READY) return Transfers.done(Transfer.Kind.PREVIEW, "preview", disconnected());
        return transfers.preview(source);
    }

    @Override
    public CompletionStage<Reply<LibraryFolder>> libraryList(String folder) {
        if (state != SessionState.READY) return CompletableFuture.completedFuture(disconnected());
        return transfers.list(folder);
    }

    @Override
    public CompletionStage<Reply<ClipboardCache.Entry>> libraryLoad(String path) {
        if (state != SessionState.READY) return CompletableFuture.completedFuture(disconnected());
        return transfers.load(path);
    }

    @Override
    public CompletionStage<Reply<SavedAsset>> saveAsset(UUID clipboardId, String path) {
        if (state != SessionState.READY) return CompletableFuture.completedFuture(disconnected());
        return transfers.save(clipboardId, path);
    }

    @Override
    public CompletionStage<Reply<LibraryChange>> libraryMove(String from, String to, boolean folder) {
        if (state != SessionState.READY) return CompletableFuture.completedFuture(disconnected());
        return transfers.move(from, to, folder);
    }

    @Override
    public CompletionStage<Reply<LibraryChange>> libraryDelete(String path, boolean folder) {
        if (state != SessionState.READY) return CompletableFuture.completedFuture(disconnected());
        return transfers.delete(path, folder);
    }

    @Override
    public CompletionStage<Reply<LibraryChange>> libraryCreateFolder(String path) {
        if (state != SessionState.READY) return CompletableFuture.completedFuture(disconnected());
        return transfers.createFolder(path);
    }

    @Override
    public LibraryChanges libraryChanges() {
        return transfers.changes();
    }

    @Override
    public CompletionStage<Reply<AssetAccess>> libraryAccess(String path) {
        if (state != SessionState.READY) return CompletableFuture.completedFuture(disconnected());
        return transfers.access(path);
    }

    @Override
    public CompletionStage<Reply<LibraryChange>> setLibraryAccess(String path, AssetAccess access) {
        if (state != SessionState.READY) return CompletableFuture.completedFuture(disconnected());
        return transfers.setAccess(path, access);
    }

    @Override
    public CompletionStage<Reply<LibraryChange>> savePalette(String path, BlockPalette palette) {
        if (state != SessionState.READY) return CompletableFuture.completedFuture(disconnected());
        return transfers.savePalette(path, palette);
    }

    @Override
    public CompletionStage<Reply<LoadedPalette>> loadPalette(String path) {
        if (state != SessionState.READY) return CompletableFuture.completedFuture(disconnected());
        return transfers.loadPalette(path);
    }

    @Override
    public boolean tinkerOffered() {
        return state == SessionState.READY && tinker.offered();
    }

    @Override
    public CompletionStage<Reply<Boolean>> tinkerBlock(BlockPos pos, int expected, int target,
                                                       dev.sculptory.core.tinker.SignText sign) {
        if (state != SessionState.READY) return CompletableFuture.completedFuture(disconnected());
        return tinker.block(pos, expected, target, sign);
    }

    @Override
    public CompletionStage<Reply<dev.sculptory.core.tinker.EntityView>> tinkerEntity(UUID id,
            List<dev.sculptory.core.tinker.EntityEdit> edits) {
        if (state != SessionState.READY) return CompletableFuture.completedFuture(disconnected());
        return tinker.entity(id, edits);
    }

    @Override
    public Transfer<ExportedFile> export(UUID clipboardId, SchematicFormat format) {
        if (state != SessionState.READY) return Transfers.done(Transfer.Kind.EXPORT, "export", disconnected());
        return transfers.export(clipboardId, format);
    }

    @Override
    public Transfer<ClipboardCache.Entry> upload(String fileName, byte[] bytes) {
        if (state != SessionState.READY) return Transfers.done(Transfer.Kind.UPLOAD, fileName, disconnected());
        return transfers.upload(fileName, bytes);
    }

    @Override
    public Transfer<ClipboardCache.Entry> uploadGenerated(Box bounds, long cells, byte[] bytes) {
        if (state != SessionState.READY) return Transfers.done(Transfer.Kind.UPLOAD, "generated", disconnected());
        return transfers.uploadGenerated(bounds, cells, bytes);
    }

    @Override
    public List<Transfer<?>> transfers() {
        return transfers.transfers();
    }

    // =================================================================== M3 requests (see ScatterRequests)

    @Override
    public CompletionStage<Reply<ScatterPreviewResult>> scatterPreview(ScatterPreviewRequest request) {
        Objects.requireNonNull(request);
        if (state != SessionState.READY) return CompletableFuture.completedFuture(disconnected());
        return scatter.preview(request);
    }

    private static <T> Reply<T> disconnected() {
        return Reply.failed(Reply.Failure.DISCONNECTED, "Sculptory is not available on this server");
    }

    @Override
    public StrokeHandle beginStroke(ToolId tool, BrushSpec spec, StrokeParams p) {
        FabricStrokeHandle stroke = new FabricStrokeHandle(this, nextStrokeId++, tool, spec, p);
        if (state != SessionState.READY) {
            stroke.reject(RejectReason.DISABLED);
            return stroke;
        }
        if (holding()) {
            // A stroke would reach the server before the op or copy the player made first (and an undo after it would
            // undo the stroke): refused while that waits, for every brush.
            stroke.refuseQuietly(RejectReason.QUEUE_FULL);
            notice(Notice.Level.INFO, selectionHolds > 0 ? STROKE_WAITS_FOR_SELECTION
                    : strokeHolds > 0 ? STROKE_WAITS_FOR_STROKE : STROKE_WAITS_FOR_COPY);
            return stroke;
        }
        newEdit();
        // One stroke at a time, as on the server: a new press ends the previous stroke.
        for (FabricStrokeHandle open : List.copyOf(strokes.values())) {
            if (open.active()) open.end();
        }
        String failure = sendMessage(new C2S.StrokeBegin(stroke.strokeId(), spec));
        if (failure != null) {
            SculptoryMod.LOG.warn("Sculptory: cannot begin stroke: {}", failure);
            stroke.reject(RejectReason.INVALID);
            return stroke;
        }
        strokes.put(stroke.strokeId(), stroke);
        // Ended strokes wait here for the server to finish writing them; it reports only its last few Shape strokes.
        List<FabricStrokeHandle> waiting = new ArrayList<>();
        for (FabricStrokeHandle open : strokes.values()) {
            if (open.ended()) waiting.add(open);
        }
        for (int i = 0; i < waiting.size() - MAX_ENDED_STROKES; i++) {
            waiting.get(i).forgetWrites();
            strokes.remove(waiting.get(i).strokeId());
        }
        return stroke;
    }

    /** Whether the server offers builder mode on this connection (ready, {@link Features#BUILDER} negotiated). */
    public boolean builderOffered() {
        return state == SessionState.READY && capabilities.features().has(Features.BUILDER);
    }

    /**
     * Builder mode: sends a {@code BuilderPowers}, {@code BuilderPlace},
     * {@code BuilderBreak} or {@code BuilderDragEnd}. Returns why it was not sent (not connected, the server does not
     * offer builder mode, an encoding problem), or {@code null}.
     */
    public String sendBuilder(C2S message) {
        if (!(message instanceof C2S.BuilderPowers || message instanceof C2S.BuilderPlace
                || message instanceof C2S.BuilderBreak || message instanceof C2S.BuilderDragEnd)) {
            throw new IllegalArgumentException("Not a builder message: " + message.type());
        }
        if (state != SessionState.READY) return "NOT_READY: no Sculptory session";
        if (!capabilities.features().has(Features.BUILDER)) return "NOT_OFFERED: the server has no builder mode";
        return sendMessage(message);
    }

    /**
     * Jump or Through ({@link Navigation}): sends a {@code Navigate}; returns why it was not sent (not connected, the
     * server has no Jump), or {@code null}. The answer comes back to {@link Navigation#result}.
     */
    public String navigate(NavigateMode mode, BlockPos hit, Facing side, float dx, float dy, float dz) {
        if (state != SessionState.READY) return "NOT_READY: no Sculptory session";
        if (!capabilities.features().has(Features.NAVIGATE)) return "NOT_OFFERED: the server has no Jump";
        return sendMessage(new C2S.Navigate(nextReqId++, mode, hit, side, dx, dy, dz));
    }

    @Override
    public void undo() {
        queueSteps(-1);
    }

    @Override
    public void redo() {
        queueSteps(1);
    }

    /** Replaces the queued steps; a step already in flight still runs and counts toward the target. */
    @Override
    public void jumpTo(long historyId) {
        if (state != SessionState.READY) return;
        inOrder(true, () -> jump(historyId), why -> { });
    }

    private void jump(long historyId) {
        // Relative to the position the mirror shows, which the step in flight has not reached yet.
        int target = clampSteps(historyId);
        stepsQueued = clampSteps(target - (long) stepInFlight);
        stepsFromJump = true;
        jumpUndo = target < 0;
        jumpTotal = Math.abs(target);
        jumpDone = 0;
        stepHistory(now());
    }

    /**
     * Undo/redo steps are queued, held or running, or an overwrite (Undo anyway) is; or requests wait behind a
     * selection's upload (the history mirror then lags behind what the player did).
     */
    @Override
    public boolean historyBusy() {
        return stepsBusy() || holding();
    }

    @Override
    public boolean strokesPending() {
        return strokes.values().stream().anyMatch(stroke -> !stroke.ended() || stroke.unapplied() > 0);
    }

    /** Steps are queued or running, or an overwrite is: another history operation must wait. */
    private boolean stepsBusy() {
        return stepsQueued != 0 || stepInFlight != 0 || overwriteReqId != 0;
    }

    @Override
    public Optional<HistoryJump> historyJump() {
        if (!stepsFromJump || jumpTotal < 1 || (stepsQueued == 0 && stepInFlight == 0)) return Optional.empty();
        return Optional.of(new HistoryJump(jumpUndo, Math.min(jumpDone, jumpTotal), jumpTotal));
    }

    @Override
    public Optional<HistoryOffer> historyOffer() {
        if (runSteps < 1 || runSkipped < 1) return Optional.empty();
        return Optional.of(new HistoryOffer(runId, runDirection > 0, runSteps, runSkipped, overwriteReqId != 0));
    }

    @Override
    public void acceptHistoryOffer() {
        if (state != SessionState.READY) return;
        inOrder(true, this::sendHistoryOverwrite, why -> { });
    }

    private void sendHistoryOverwrite() {
        if (stepsBusy()) return;
        HistoryOffer offer = historyOffer().orElse(null);
        if (offer == null) return;
        int reqId = nextReqId++;
        String failure = sendMessage(new C2S.HistoryOverwrite(reqId, offer.redo(), offer.steps()));
        if (failure != null) {
            SculptoryMod.LOG.warn("Sculptory: cannot send {} anyway: {}", offer.redo() ? "redo" : "undo", failure);
            notice(SessionNotices.rejection(RejectReason.INVALID, subject(offer.redo()), permissions.limits()));
            return;
        }
        pending.put(reqId, new Pending(Kind.OVERWRITE, offer.redo() ? "Redo anyway" : "Undo anyway", null, 0));
        overwriteReqId = reqId;
        overwriteJob = null;
        overwriteRefusal = null;
        overwriteRefusalKind = null;
        overwriteActivityAt = now();
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
    public Subscription onNotice(Consumer<Notice> l) {
        return noticeListeners.add(l);
    }

    /** Completed server-to-client streams. */
    public Subscription onStream(Consumer<ReceivedStream> l) {
        return streamListeners.add(l);
    }

    /** The server's report for a recently finished job (changed and skipped counts). */
    public java.util.Optional<S2C.JobFinished> jobResult(UUID jobId) {
        return jobs.result(jobId);
    }

    // =================================================================== requests and jobs

    /**
     * An op on a cell set: the set is uploaded unless the server holds it for this connection
     * ({@link ClipboardTransfers#selection}, quietly: the caller reports a refusal), then the op names it as a
     * {@code Region.Uploaded}. With {@code retry}, a {@code SELECTION_NOT_LOADED} answer (the server dropped the set)
     * uploads it again and sends the op once more, unless something went out after it meanwhile ({@link Hold#mayRetry}:
     * the op then ends refused, for the player to run again). Completes {@code result} like {@link #sendOp};
     * {@code hold} settles once the op is sent, or has failed before that (the requests held back behind it may then go),
     * and an op whose hold no longer counts (it timed out) is not sent.
     */
    private void runOnSelection(ToolAction.RunOp action, CellSet cells, boolean retry, CompletableFuture<ToolResult> result,
                                Hold hold, long firstAt) {
        transfers.selection(cells, true).thenAccept(uploaded -> {
            switch (uploaded) {
                case Reply.Ok<Sha256> ok -> {
                    if (state != SessionState.READY) {
                        result.complete(disconnectedResult());
                        break;
                    }
                    if (!hold.live()) {
                        result.complete(gaveUpResult());
                        break;
                    }
                    OpSpec op = OpRegions.withRegion(action.op(), new Region.Uploaded(ok.value(), cells.bounds(), cells.size()));
                    int reqId = nextReqId++;
                    String failure = sendMessage(new C2S.RunOp(reqId, op, action.physics(), ConflictPolicy.SKIP_CONFLICTS, action.label()));
                    if (failure != null) {
                        RejectReason reason = failure.startsWith("TOO_LARGE") ? RejectReason.TOO_LARGE : RejectReason.INVALID;
                        result.complete(new ToolResult.Rejected(reason, failure));
                        break;
                    }
                    Runnable again = !retry ? null : () -> {
                        if (!hold.mayRetry()) {
                            result.complete(new ToolResult.Rejected(RejectReason.SELECTION_NOT_LOADED,
                                    "the server dropped the selection after later requests went out"));
                            return;
                        }
                        transfers.forgetSelection(ok.value());
                        runOnSelection(action, cells, false, result, hold.again(), firstAt);
                    };
                    // Refused for the player's stroke: the same op on the set, sent again (the server keeps the set).
                    StrokeRetry later = new StrokeRetry(firstAt,
                            next -> runOnSelection(action, cells, retry, result, next, firstAt),
                            why -> result.complete(abandoned(why)));
                    pending.put(reqId, new Pending(Kind.RUN_OP, labelOf(action), result, 0, again, later));
                }
                case Reply.Refused<Sha256> refused -> result.complete(new ToolResult.Rejected(refused.reason(),
                        refused.detail()));
                case Reply.Failed<Sha256> failed -> result.complete(new ToolResult.Rejected(switch (failed.failure()) {
                    case DISCONNECTED, CANCELLED -> RejectReason.DISABLED;
                    case BUSY -> RejectReason.QUEUE_FULL;
                    case TIMED_OUT, ABORTED, CORRUPT -> RejectReason.INVALID;
                }, "the selection could not be sent: " + failed.detail()));
            }
        }).whenComplete((ignored, error) -> hold.settled());
    }

    private void jobAccepted(S2C.JobAccepted m) {
        Pending p = pending.remove(m.reqId());
        if (p == null) return;
        jobs.accepted(m.jobId(), p.label(), m.estCells());
        if (p.future() != null) p.future().complete(new ToolResult.Accepted(m.jobId(), m.estCells()));
        if (p.kind().history()) {
            // The mirror still shows the history before this step: its HistoryState follows its JobFinished.
            boolean undo = p.kind() == Kind.UNDO;
            notice(SessionNotices.historyStep(undo, undo ? history.undoLabel() : history.redoLabel()));
        }
        if (p.kind() == Kind.OVERWRITE && m.reqId() == overwriteReqId) {
            overwriteHeldSince = 0;
            overwriteJob = m.jobId();
            overwriteActivityAt = now();
        }
        JobTracker.Job job = jobs.job(m.jobId()).orElse(null);
        boolean finishedFirst = job != null && job.finished();
        if (finishedFirst) {
            announceResult(job, p);
        } else {
            awaitingResult.put(m.jobId(), p);
        }
        if (isCurrentStep(p)) {
            stepActivityAt = now();
            stepHeldSince = 0;
            if (finishedFirst) {
                // Its JobFinished came first: the step is over, and a failed one ends the queue.
                stepInFlight = 0;
                if (job.outcome() != JobOutcome.COMPLETED) cancelSteps();
            } else {
                stepJob = m.jobId();
            }
        }
    }

    private void jobRejected(S2C.JobRejected m) {
        boolean heldByStroke = strokePending.remove(m.reqId());
        Pending p = pending.remove(m.reqId());
        if (p == null) return;
        if (m.reason() == RejectReason.SELECTION_NOT_LOADED && p.retry() != null) {
            p.retry().run();
            return;
        }
        if (heldByStroke && retryForStroke(p.strokeRetry())) return;
        if (heldByStroke && p.kind().history() && isCurrentStep(p) && holdStep(p)) return;
        if (p.future() != null) p.future().complete(new ToolResult.Rejected(m.reason(), ""));
        if (p.kind() == Kind.OVERWRITE) {
            overwriteRejected(m.reqId(), m.reason());
            return;
        }
        // A request with a completion stage (anything but undo/redo) is reported by its caller.
        if (!p.kind().history()) return;
        stepHeldSince = 0;
        boolean current = isCurrentStep(p);
        // Running out of history ends a jump quietly; a refused press also drops the presses queued behind it.
        boolean quiet = current && stepsFromJump && m.reason() == RejectReason.HISTORY_EMPTY;
        if (current) cancelSteps();
        if (!quiet) notice(SessionNotices.rejection(m.reason(), p.kind().subject, permissions.limits()));
    }

    private void jobFinished(S2C.JobFinished m, long now) {
        jobs.finished(m, now);
        if (m.jobId().equals(stepJob)) {
            stepJob = null;
            stepInFlight = 0;
            if (m.outcome() != JobOutcome.COMPLETED) cancelSteps();
        }
        // Jobs this client did not start (or whose JobAccepted is still on its way) are not announced here.
        Pending p = awaitingResult.remove(m.jobId());
        if (p != null) jobs.job(m.jobId()).ifPresent(job -> announceResult(job, p));
    }

    /** Raises the result toasts of a finished job started by request {@code p}. */
    private void announceResult(JobTracker.Job job, Pending p) {
        if (p.kind() == Kind.SCATTER) {
            // The server follows JobFinished with a scatter_skipped notice holding the same count: shown here already.
            if (job.skippedConflicts() > 0) {
                expectedScatterSkips = job.skippedConflicts();
                expectedScatterSkipsUntil = now() + SCATTER_SKIPPED_WAIT_NANOS;
            }
            for (Notice n : SessionNotices.scatterResult(job)) notice(n);
            return;
        }
        if (p.kind() == Kind.OVERWRITE) {
            overwriteFinished(job);
            return;
        }
        if (p.kind().history()) {
            boolean undo = p.kind() == Kind.UNDO;
            if (stepsFromJump && undo == jumpUndo && job.outcome() == JobOutcome.COMPLETED) jumpDone++;
            runStepFinished(undo ? -1 : 1, job);
        }
        for (Notice n : SessionNotices.jobResult(job, p.kind().history())) notice(n);
    }

    /**
     * Whether {@code m} is the server's {@code scatter_skipped} notice for the scatter result just toasted (same count,
     * shortly after), which is then not shown again.
     */
    private boolean announcedScatterSkips(S2C.Notice m, long now) {
        if (!m.key().equals(SessionNotices.SCATTER_SKIPPED) || expectedScatterSkips <= 0) return false;
        boolean same = m.args().size() == 1 && m.args().get(0).equals(Long.toString(expectedScatterSkips))
                && now - expectedScatterSkipsUntil < 0;
        expectedScatterSkips = 0;
        return same;
    }

    // =================================================================== undo/redo steps

    /**
     * Drops the undo/redo steps not sent yet; the step in flight still runs and is still followed. Called when the
     * editor closes, and before this client starts a new edit: the server would push the new edit's entry while an
     * undo runs, and a queued undo would then undo that newer edit instead of the one the player meant.
     */
    @Override
    public void dropQueuedHistorySteps() {
        stepsQueued = 0;
        // Presses and jumps held behind a selection's upload too (Esc, the History window's Stop).
        heldBack.removeIf(HeldBack::history);
    }

    /**
     * The player starts an edit (stroke, region op, cut): queued undo/redo presses no longer apply. Presses held behind a
     * selection's upload stay: they came after this edit (an op runs from the held requests in order).
     */
    private void newEdit() {
        stepsQueued = 0;
    }

    /**
     * Updates the mirror, and drops the queued steps if the history changed in a way the last step can't explain:
     * an entry pushed by an edit this session didn't start (a stroke committed by its idle timeout, a command). The
     * server defers such a push while an undo runs, so it can arrive together with the undo's result.
     */
    private void historyState(S2C.HistoryState next) {
        S2C.HistoryState before = history.state();
        history.update(next, cause(before, next));
        followRun(before, next);
        if (stepsQueued != 0 && !explainedByStep(before, next, stepLastDirection)) {
            SculptoryMod.LOG.info("Sculptory: the history changed under queued undo/redo steps; dropping them");
            stepsQueued = 0;
        }
    }

    /**
     * What moved the history from {@code before} to {@code next}, for the mirror ({@link HistoryMirror#lastCause}). A
     * step moves one entry across and leaves the history's byte total as it was; a new entry and an eviction change
     * it. So the change is this client's undo or redo step (in flight, or just finished with its history arriving
     * after its result) only when it is that step's move of one entry with the bytes unchanged; it is
     * {@link HistoryMirror.Cause#OTHER} only when the bytes changed (certainly not a step alone); and a move with the
     * bytes unchanged that no step of this client explains (an undo or redo made some other way: a command's) is
     * {@link HistoryMirror.Cause#UNKNOWN}, read from the labels alone, so that a redo made another way is never taken
     * for a new edit (and flagged as a tutorial lesson's). The server runs one history operation of a player at a
     * time and defers new entries while a step runs, so a state that is the step's move is the step's.
     */
    private HistoryMirror.Cause cause(S2C.HistoryState before, S2C.HistoryState next) {
        if (before.bytes() != next.bytes()) {
            return HistoryMirror.Cause.OTHER;
        }
        boolean labelsMoved = !next.undoLabels().equals(before.undoLabels())
                || !next.redoLabels().equals(before.redoLabels());
        int direction = stepInFlight != 0 && explainedByStep(before, next, stepInFlight) ? stepInFlight
                : moveOwed != 0 && explainedByStep(before, next, moveOwed) ? moveOwed : 0;
        if (!labelsMoved || direction == 0) {
            return HistoryMirror.Cause.UNKNOWN;
        }
        return direction < 0 ? HistoryMirror.Cause.UNDO_STEP : HistoryMirror.Cause.REDO_STEP;
    }

    /**
     * Whether {@code next} is {@code before} unchanged (a refused step) or with exactly one entry moved by a step in
     * {@code direction}: an undo moves the newest undo entry to the front of the redo list, a redo the other way.
     * A push clears the redo list and adds a new undo entry, which neither explains. Both label lists are capped at
     * {@link S2C.HistoryState#MAX_LABELS}, so the list losing an entry is compared as a prefix.
     */
    static boolean explainedByStep(S2C.HistoryState before, S2C.HistoryState next, int direction) {
        if (next.undoLabels().equals(before.undoLabels()) && next.redoLabels().equals(before.redoLabels())) return true;
        if (direction == 0) return false;
        List<String> from = direction < 0 ? before.undoLabels() : before.redoLabels();
        List<String> to = direction < 0 ? before.redoLabels() : before.undoLabels();
        List<String> nextFrom = direction < 0 ? next.undoLabels() : next.redoLabels();
        List<String> nextTo = direction < 0 ? next.redoLabels() : next.undoLabels();
        if (from.isEmpty()) return false;
        List<String> expectedTo = new ArrayList<>(to.size() + 1);
        expectedTo.add(from.get(0));
        expectedTo.addAll(to);
        if (expectedTo.size() > S2C.HistoryState.MAX_LABELS) {
            expectedTo = expectedTo.subList(0, S2C.HistoryState.MAX_LABELS);
        }
        List<String> kept = from.subList(1, from.size());
        return nextTo.equals(expectedTo) && nextFrom.size() >= kept.size()
                && nextFrom.subList(0, kept.size()).equals(kept);
    }

    // =================================================================== Undo anyway (the run of steps)

    /**
     * Follows the run through a history change: the step in flight's own move (one entry across, the history's bytes
     * unchanged or grown) is noted for its result; any other change ends the run, and a step in flight when it came may
     * no longer extend it (the server ends its run on the same changes, {@code HistoryService}).
     *
     * <p>A step may grow the history: the server folds what the stepped entry's water or lava did since into that entry
     * ({@code FluidTrails}, {@code PlayerHistory.replace}, which never shrinks it) and keeps its run. An eviction, which
     * ends the server's run, only ever shrinks it. So a smaller history is an eviction, as before, and a larger one the
     * step's own fold. (Should an eviction come with a fold that outweighs it, the client keeps a run the server ended;
     * the server then refuses Undo anyway, and that refusal withdraws the offer.) Undo anyway folds its run's entries
     * the same way and moves none: a larger history with the same labels while it runs is its own, and a cancelled or
     * failed overwrite still keeps the run on both sides.
     */
    private void followRun(S2C.HistoryState before, S2C.HistoryState next) {
        boolean sameLabels = before.undoLabels().equals(next.undoLabels())
                && before.redoLabels().equals(next.redoLabels());
        boolean unchanged = before.bytes() == next.bytes() && sameLabels;
        if (unchanged) return;
        boolean noEviction = next.bytes() >= before.bytes();
        int owed = moveOwed;
        moveOwed = 0;
        if (overwriteReqId != 0 && sameLabels && noEviction) return;
        if (stepInFlight != 0 && noEviction && explainedByStep(before, next, stepInFlight)) {
            stepMoved = true;
            return;
        }
        // The server sends a step's history state before its JobFinished; should it come after, it is still that move.
        if (owed != 0 && noEviction && explainedByStep(before, next, owed)) return;
        endRun();
        if (stepInFlight != 0) {
            stepBroken = true;
            stepMoved = false;
        }
    }

    /**
     * A step of this client finished. A completed step extends the run in its direction, or starts a new one; a step
     * that did not complete ends it. A step during which the history changed otherwise starts a new run only if its own
     * move came after that change (then the server's run starts with it too), and ends the run otherwise.
     */
    private void runStepFinished(int direction, JobTracker.Job job) {
        boolean moved = stepMoved;
        boolean broken = stepBroken;
        stepMoved = false;
        stepBroken = false;
        if (job.outcome() != JobOutcome.COMPLETED || (broken && !moved)) {
            endRun();
            return;
        }
        moveOwed = moved ? 0 : direction;
        if (broken || runDirection != direction) {
            runDirection = direction;
            runSteps = 0;
            runSkipped = 0;
        }
        runSteps++;
        runSkipped += Math.max(0, job.skippedConflicts());
        runId++;
    }

    private void endRun() {
        if (runSteps == 0 && runDirection == 0) return;
        runDirection = 0;
        runSteps = 0;
        runSkipped = 0;
        runId++;
    }

    private static SessionNotices.Subject subject(boolean redo) {
        return redo ? SessionNotices.Subject.REDO : SessionNotices.Subject.UNDO;
    }

    /**
     * The overwrite was refused. Only a refusal the server marks as about the run ({@code INVALID} with the kind
     * {@link SessionNotices#OVERWRITE_RUN_REFUSED}: the history changed meanwhile, nothing was kept, the steps span worlds)
     * withdraws the offer, with the server's detail in the toast. Any other refusal (a job still running, no permission,
     * unloaded chunks, too large, a world not loaded, no notice at all) leaves the offer for another try.
     */
    private void overwriteRejected(int reqId, RejectReason reason) {
        if (reqId != overwriteReqId) return;
        boolean redo = runDirection > 0;
        String detail = overwriteRefusal == null ? "" : overwriteRefusal;
        boolean runRefused = reason == RejectReason.INVALID
                && SessionNotices.OVERWRITE_RUN_REFUSED.equals(overwriteRefusalKind);
        boolean heldByStroke = SessionNotices.STROKE_PENDING_KIND.equals(overwriteRefusalKind);
        overwriteReqId = 0;
        overwriteJob = null;
        overwriteRefusal = null;
        overwriteRefusalKind = null;
        if (heldByStroke) {
            // Only the player's stroke still being applied: sent again in a moment (if the offer still stands then).
            long firstAt = overwriteHeldSince != 0 ? overwriteHeldSince : now();
            overwriteHeldSince = firstAt;
            if (retryForStroke(new StrokeRetry(firstAt, hold -> {
                try {
                    sendHistoryOverwrite();
                } finally {
                    hold.settled();
                }
            }, why -> overwriteHeldSince = 0))) {
                return;
            }
        }
        overwriteHeldSince = 0;
        if (runRefused) {
            endRun();
            notice(SessionNotices.overwriteRefused(redo, detail));
        } else if (reason == RejectReason.INVALID && !detail.isBlank()) {
            notice(Notice.of(Notice.Level.WARNING, SessionNotices.reasonKey(reason) + ".detail", detail));
        } else {
            notice(SessionNotices.rejection(reason, subject(redo), permissions.limits()));
        }
    }

    /**
     * An overwrite's job finished: a completed one resolves the offer; a cancelled or failed one leaves it. (One given up
     * after the stall may still finish later; it no longer stands for the overwrite in flight then.)
     */
    private void overwriteFinished(JobTracker.Job job) {
        if (job.jobId().equals(overwriteJob)) {
            overwriteReqId = 0;
            overwriteJob = null;
            overwriteRefusal = null;
            overwriteRefusalKind = null;
        }
        if (job.outcome() == JobOutcome.COMPLETED) endRun();
        for (Notice n : SessionNotices.overwriteResult(job)) notice(n);
    }

    /** Adds a press to the signed queue (an undo cancels a queued redo and vice versa) and sends it if it may. */
    private void queueSteps(int delta) {
        if (state != SessionState.READY) return;
        inOrder(true, () -> addSteps(delta), why -> { });
    }

    private void addSteps(int delta) {
        stepsQueued = clampSteps((long) stepsQueued + delta);
        stepsFromJump = false;
        stepHistory(now());
    }

    private static int clampSteps(long steps) {
        return (int) Math.max(-S2C.HistoryState.MAX_LABELS, Math.min(S2C.HistoryState.MAX_LABELS, steps));
    }

    private boolean isCurrentStep(Pending p) {
        return p.stepGeneration() != 0 && p.stepGeneration() == stepGeneration;
    }

    /**
     * The undo/redo step in flight was refused only because the player's brush stroke is still being applied: it goes
     * back to the front of the queue and is sent again after {@link #STROKE_RETRY_NANOS}, the requests made meanwhile
     * waiting behind it ({@link #stepHold}). Returns false once {@link #STROKE_RETRY_FOR_NANOS} has passed since its
     * first such refusal (then it is refused as usual).
     */
    private boolean holdStep(Pending p) {
        long now = now();
        if (stepHeldSince == 0) stepHeldSince = now;
        if (now - stepHeldSince >= STROKE_RETRY_FOR_NANOS) return false;
        stepInFlight = 0;
        stepJob = null;
        stepsQueued = clampSteps((long) stepsQueued + (p.kind() == Kind.UNDO ? -1 : 1));
        stepNotBefore = now + STROKE_RETRY_NANOS;
        if (stepHold == null || !stepHold.live()) stepHold = new Hold(false, true);
        return true;
    }

    /** The held step went out, or will not: the requests behind it may go. */
    private void releaseStepHold() {
        stepNotBefore = 0;
        Hold hold = stepHold;
        stepHold = null;
        if (hold != null) hold.settled();
    }

    /** Sends the next queued step when none is in flight and the pacing allows; abandons a stalled step. */
    private void stepHistory(long now) {
        if (overwriteReqId != 0) {
            // Undo anyway is a history operation too: steps wait for it, and it is abandoned like a stalled step.
            if (now - overwriteActivityAt > JUMP_STEP_TIMEOUT_NANOS) {
                if (overwriteJob == null) pending.remove(overwriteReqId);
                overwriteReqId = 0;
                overwriteJob = null;
                overwriteRefusal = null;
                overwriteRefusalKind = null;
                notice(Notice.Level.WARNING, "sculptory.notice.history_overwrite_stalled");
            }
            return;
        }
        if (stepInFlight != 0) {
            // The timeout restarts whenever the step's job reports progress.
            if (now - stepActivityAt > JUMP_STEP_TIMEOUT_NANOS) {
                // Forget the unanswered request too, so a very late JobAccepted doesn't announce the step.
                if (stepJob == null) pending.remove(stepReqId);
                cancelSteps();
                notice(Notice.Level.WARNING, "sculptory.notice.history_jump_stalled");
            }
            return;
        }
        // A step held for the player's stroke whose presses were dropped meanwhile (Esc): nothing waits for it.
        if (stepHold != null && stepsQueued == 0) releaseStepHold();
        // Queued steps wait while an op or copy the player made after them has not gone out (later presses are held);
        // a step held for the player's stroke waits for its time.
        boolean ownHold = stepHold != null && stepHold.live();
        if (holds > (ownHold ? 1 : 0)) return;
        if (ownHold && now - stepNotBefore < 0) return;
        if (stepsQueued == 0 || (stepSent && now - stepLastSend < JUMP_STEP_NANOS)) return;
        boolean undo = stepsQueued < 0;
        int reqId = nextReqId++;
        C2S message = undo
                ? new C2S.Undo(reqId, ConflictPolicy.SKIP_CONFLICTS)
                : new C2S.Redo(reqId, ConflictPolicy.SKIP_CONFLICTS);
        if (sendMessage(message) != null) {
            cancelSteps();
            return;
        }
        pending.put(reqId, new Pending(undo ? Kind.UNDO : Kind.REDO, undo ? "Undo" : "Redo", null, stepGeneration));
        stepsQueued += undo ? 1 : -1;
        stepInFlight = undo ? -1 : 1;
        stepLastDirection = stepInFlight;
        stepMoved = false;
        stepBroken = false;
        moveOwed = 0;
        stepReqId = reqId;
        stepSent = true;
        stepLastSend = now;
        stepActivityAt = now;
        // A step held for the player's stroke went out: the requests behind it may go too.
        if (stepHold != null) releaseStepHold();
    }

    /** Drops the queued steps and stops following the one in flight. */
    private void cancelSteps() {
        if (++stepGeneration == 0) stepGeneration = 1;
        stepsQueued = 0;
        stepInFlight = 0;
        stepJob = null;
        stepHeldSince = 0;
        if (stepHold != null) releaseStepHold();
    }

    // =================================================================== strokes (called by FabricStrokeHandle)

    long now() {
        return nanoClock.getAsLong();
    }

    /** Sends one batch; returns why it could not be sent, or {@code null}. */
    RejectReason sendDabs(int strokeId, int seq, List<Dab> batch) {
        if (state != SessionState.READY) return RejectReason.DISABLED;
        String failure = sendMessage(new C2S.Dabs(strokeId, seq, batch));
        return failure == null ? null : RejectReason.INVALID;
    }

    void sendStrokeEnd(int strokeId) {
        if (state == SessionState.READY) sendMessage(new C2S.StrokeEnd(strokeId));
    }

    void resync(Box box) {
        if (state == SessionState.READY) sendMessage(new C2S.Resync(box));
    }

    void strokeRejected(FabricStrokeHandle stroke, RejectReason reason) {
        if (reason == RejectReason.DISABLED && state != SessionState.READY) return;
        notice(SessionNotices.rejection(reason, SessionNotices.Subject.STROKE, permissions.limits()));
    }

    // =================================================================== helpers

    /** Encodes and sends; returns why it failed (prefixed with the reason name), or {@code null}. */
    private String sendMessage(C2S message) {
        // An edit goes out under the mask the player sees: a change not yet sent goes first, or the edit is refused.
        String masked = maskSync == null ? null : maskSync.gate(message);
        if (masked != null) return masked;
        byte[] frame;
        try {
            frame = Codec.encodeC2S(message, states.get());
        } catch (ProtocolException e) {
            return e.reason() + ": " + e.getMessage();
        } catch (RuntimeException e) {
            // No state space yet, or a handle outside it.
            SculptoryMod.LOG.warn("Sculptory: cannot encode {}: {}", message.type(), e.toString());
            return "INVALID: " + e.getMessage();
        }
        transport.send(frame);
        if (message instanceof C2S.RunOp || message instanceof C2S.Copy || message instanceof C2S.Undo
                || message instanceof C2S.Redo || message instanceof C2S.HistoryOverwrite || message instanceof C2S.StrokeBegin
                || message instanceof C2S.TinkerBlock
                || (message instanceof C2S.TinkerEntity tinkered && !tinkered.edits().isEmpty())) {
            orderedSends++;
        }
        return null;
    }

    private void notice(Notice.Level level, String key, String... args) {
        notice(Notice.of(level, key, args));
    }

    private void notice(Notice notice) {
        noticeListeners.forEach(l -> l.accept(notice));
    }

    private static CompletionStage<ToolResult> notAvailable() {
        return CompletableFuture.completedFuture(new ToolResult.Rejected(RejectReason.DISABLED,
                "Sculptory is not available on this server"));
    }

    private static Notice.Level level(S2C.Notice.Level level) {
        return switch (level) {
            case INFO -> Notice.Level.INFO;
            case WARN -> Notice.Level.WARNING;
            case ERROR -> Notice.Level.ERROR;
        };
    }

    /** The job bar's name for a run: the tool label when the action carries one, else the op's name as the server writes it. */
    static String labelOf(ToolAction.RunOp run) {
        return run.label().text() != null ? run.label().text() : labelOf(run.op());
    }

    static String labelOf(OpSpec op) {
        return switch (op) {
            case OpSpec.Fill f -> "Fill";
            case OpSpec.Replace r -> "Replace";
            case OpSpec.Erase e -> "Erase";
            case OpSpec.Hollow h -> "Hollow";
            case OpSpec.Walls w -> "Walls";
            case OpSpec.Paste p -> "Paste";
            case OpSpec.Move m -> "Move";
            case OpSpec.Stack s -> "Stack";
            case OpSpec.ScatterCommit s -> "Scatter";
            case OpSpec.Overlay o -> "Overlay";
            case OpSpec.Naturalize n -> "Naturalize";
            case OpSpec.UpdateBlocks u -> "Update blocks";
        };
    }
}
