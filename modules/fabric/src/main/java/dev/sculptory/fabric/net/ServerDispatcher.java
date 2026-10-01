package dev.sculptory.fabric.net;

import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.edit.OpRegions;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.tinker.EntityView;
import dev.sculptory.fabric.SculptoryMod;
import dev.sculptory.fabric.engine.BuilderOutcome;
import dev.sculptory.fabric.engine.ClipboardService;
import dev.sculptory.fabric.engine.DabOutcome;
import dev.sculptory.fabric.engine.EditRejected;
import dev.sculptory.fabric.engine.EditService;
import dev.sculptory.fabric.engine.JobListener;
import dev.sculptory.fabric.engine.JobResult;
import dev.sculptory.fabric.engine.JobTicket;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.fabric.engine.PermissionService;
import dev.sculptory.fabric.engine.RunOptions;
import dev.sculptory.fabric.engine.ScatterService;
import dev.sculptory.fabric.engine.TinkerService;
import dev.sculptory.fabric.engine.impl.EditMasks;
import dev.sculptory.fabric.net.NetSession.Stage;
import dev.sculptory.protocol.v2.AssetAccess;
import dev.sculptory.protocol.v2.BuilderPower;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.Codec;
import dev.sculptory.protocol.v2.Features;
import dev.sculptory.protocol.v2.Handshake;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.Message;
import dev.sculptory.protocol.v2.MessageType;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.protocol.v2.PermissionMask;
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
import dev.sculptory.protocol.v2.StreamSender;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Decodes client frames and drives {@link EditService}; turns results and {@link JobListener} callbacks into
 * server-to-client messages. Pure logic over a {@link ServerTransport}, so it runs in unit tests.
 *
 * <p>Order of checks for every frame: the type is peeked without decoding; before the handshake anything but
 * {@code Hello} is a violation and is never decoded; the frame is charged to its rate bucket; only then is it
 * decoded. Malformed frames count as violations and after {@link NetSession#MAX_VIOLATIONS} the player is
 * disconnected. Unresolvable block states are refused without counting, up to
 * {@link NetSession#UNKNOWN_STATES_PER_MINUTE} per minute. Frames over their rate limit are refused without counting,
 * and answered, up to a burst of {@link NetSession#FLOOD_BURST} (refilled at {@link NetSession#FLOOD_PER_SECOND} per
 * second); past that they are dropped unanswered and count as violations, at most one per tick, so a flooding client
 * is disconnected ({@code withinFloodAllowance}).
 *
 * <p>Replies: {@code Hello} gets {@code Welcome} or {@code Incompatible}; region ops, undo and redo get
 * {@code JobAccepted} or {@code JobRejected}, and their job events always follow the {@code JobAccepted}; stroke
 * messages get {@code StrokeStatus}. Undo anyway ({@code HistoryOverwrite}) is answered like undo, and a refusal of it
 * also gets a {@code history_overwrite_refused} notice first.
 *
 * <p>M3 scatter ({@link ScatterService}): {@code ScatterPreview} → {@code ScatterPlan(reqId)} on a later tick, then,
 * when the plan has placements, a {@code SCATTER_PLACEMENTS} stream ({@code ScatterPlacements}) whose meta carries
 * {@code format}, {@code planId}, {@code reqId} and {@code placements}. A refusal is {@code JobRejected(reqId, reason)}
 * plus a {@code sculptory.notice.request_refused} notice; a preview superseded by the player's newer one gets only
 * {@code JobRejected(reqId, QUEUE_FULL)}, which clients ignore for a request they replaced (no new reason code: it
 * would break older clients' exhaustive handling). Stream room is reserved up front ({@code QUEUE_FULL} without
 * it); a new preview first releases the reservation of the one it replaces. If the
 * stream cannot be opened when the plan is ready, the plan still stands and a
 * {@code sculptory.notice.preview_refused} notice says why. The plan is committed with
 * {@code RunOp(ScatterCommit(planId))} like any region op.
 *
 * <p>M2 requests go to the {@link ClipboardService}; a refusal is {@code JobRejected(reqId, reason)} followed by a
 * {@code sculptory.notice.request_refused} notice ({@code [reason, detail]}), except where noted:
 * <ul>
 *   <li>{@code Copy} → {@code ClipboardReady(reqId)} (on a later tick). A cut also gets {@code JobAccepted(reqId)}
 *       for its erase job right away, then that job's events; so for a cut, {@code JobAccepted} usually arrives
 *       before {@code ClipboardReady}. A cut whose clipboard fails after the erase was admitted gets
 *       {@code JobRejected(reqId)} after its {@code JobAccepted} (the erase stays undoable).</li>
 *   <li>{@code PreviewRequest} (no request id) → a {@code CLIPBOARD_PREVIEW} or {@code ASSET_PREVIEW} stream whose
 *       meta names the clipboard ({@code clipboardId}) or asset ({@code contentHash}); a refusal is only a
 *       {@code sculptory.notice.preview_refused} notice ({@code [reason, detail]}).</li>
 *   <li>{@code LibraryList} → {@code LibraryListing}, plus a {@code sculptory.notice.library_truncated} notice
 *       ({@code [folder, shown]}) when the folder held more; {@code LibraryLoad} → {@code ClipboardReady};
 *       {@code SaveAsset} → {@code AssetSaved} (with the path actually written). The listing carries whether the
 *       player may change the folder ({@code writable}, M4).</li>
 *   <li>M4 library management: {@code LibraryMove}, {@code LibraryDelete} and {@code LibraryCreateFolder} →
 *       {@code LibraryChanged(reqId)}; the same change goes to every other ready session as {@code LibraryChanged}
 *       with request id {@code PUSH} (0), with the paths that player may not read blanked, or not at all when they may
 *       read neither ({@link ClipboardService#shownTo}).</li>
 *   <li>Palettes: {@code PaletteSave} → {@code LibraryChanged(reqId, false, "", path written)}, pushed to the others as
 *       above; {@code PaletteLoad} → {@code PaletteData(reqId)}.</li>
 *   <li>Per-asset access: {@code LibraryAccessGet} → {@code LibraryAccess(reqId)}; {@code LibraryAccessSet} →
 *       {@code LibraryChanged(reqId, false, path, path)}, pushed to the others as they may see it
 *       ({@link ClipboardService#shownAccessChange}: the path twice for a player who may read it afterwards, the
 *       path and {@code ""} for one who lost access).</li>
 *   <li>{@code ExportClipboard} → a {@code SCHEM_FILE} stream of the requested format whose meta carries
 *       {@code reqId}.</li>
 *   <li>{@code UploadBegin} → {@code UploadGrant} (the stream may not exceed the announced size), then after the
 *       {@code SCHEM_UPLOAD} stream: {@code ClipboardReady(reqId)} and {@code UploadResult(reqId, clipboardId)}, or
 *       {@code UploadResult(reqId, error)}. Refusals before the grant are {@code JobRejected}.</li>
 *   <li>{@code SelectionUpload} (regions) → {@code UploadGrant}, then after the {@code SELECTION_UPLOAD} stream
 *       {@code SelectionReady(reqId, hash)} once the set is decoded and kept in the connection's
 *       {@link SelectionStore}; a refusal before or after the stream is {@code JobRejected}.</li>
 *   <li>{@code GeneratedUpload} (generators) → {@code UploadGrant}, then after the {@code GENERATED_UPLOAD} stream
 *       {@code ClipboardReady(reqId)} and {@code UploadResult(reqId, clipboardId)}, or {@code UploadResult(reqId,
 *       error)}, exactly as an {@code UploadBegin}: the decoded payload is the player's clipboard.</li>
 *   <li>A {@code RunOp} or {@code Copy} naming a {@code Region.Uploaded} is carried out on the cells the connection
 *       uploaded under that hash; a set the store does not hold is {@code JobRejected(reqId, SELECTION_NOT_LOADED)}
 *       without a notice (the client uploads it again and retries), one whose bounds or cell count differ
 *       {@code INVALID}.</li>
 *   <li>A {@code Copy} takes the entities its filter takes; {@code ClipboardReady} counts the entities a clipboard
 *       holds, passengers included.</li>
 * </ul>
 * Import reports (unknown states, skipped block entities...) follow {@code ClipboardReady} as notices.
 *
 * <p>Strokes: {@code StrokeStatus.ackedIndex} means <em>admitted</em> by the brush lane, not applied, so the
 * lane must cap its per-player backlog (refusing with {@code AREA_BUSY} or {@code RATE_LIMITED} when over).
 * Prediction acknowledgements are ordered through {@link PredictionAcks}.
 *
 * <p>Server thread only.
 */
public final class ServerDispatcher {
    /** What this server offers (M1, M2 and M3, and builder mode). */
    public static final Features SERVER_FEATURES = Features.of(Features.STROKES, Features.REGION_OPS, Features.HISTORY,
            Features.CLIPBOARD, Features.SCHEMATICS, Features.LIBRARY, Features.SCATTER, Features.TINKER,
            Features.BUILDER, Features.EDIT_MASK, Features.NAVIGATE);
    public static final String NOTICE_REQUEST_REFUSED = "sculptory.notice.request_refused";
    public static final String NOTICE_PREVIEW_REFUSED = "sculptory.notice.preview_refused";
    public static final String NOTICE_LIBRARY_TRUNCATED = "sculptory.notice.library_truncated";
    /**
     * Why an Undo anyway / Redo anyway was refused ({@code [reason, detail, kind]}), sent before its {@code JobRejected}.
     */
    public static final String NOTICE_OVERWRITE_REFUSED = "sculptory.notice.history_overwrite_refused";
    /**
     * Sent right before the refusal of a request the player's brush stroke held up ({@code [reqId]}): the client retries
     * it rather than reporting it ({@link #strokePending}).
     */
    public static final String NOTICE_STROKE_PENDING = "sculptory.notice.stroke_pending";
    /** Largest resync request, in chunks (a radius-32 dab touches at most 25). */
    public static final int MAX_RESYNC_CHUNKS = 64;
    /** How long after a stroke ends its chunks may still be resynced. */
    public static final long RESYNC_WINDOW_NANOS = 10_000_000_000L;
    /** Resync refusals: the notice keys (no arguments) the client translates. */
    public static final String RESYNC_NO_PERMISSION = "sculptory.notice.resync_refused.no_permission";
    public static final String RESYNC_NO_STROKE = "sculptory.notice.resync_refused.no_stroke";
    public static final String RESYNC_OUT_OF_RANGE = "sculptory.notice.resync_refused.out_of_range";
    /**
     * Server ticks between re-checks of a ready session's permissions, which catch changes made by a permissions
     * mod (op and deop are pushed at once through {@code PlayerManagerMixin}).
     */
    public static final int PERMISSION_RECHECK_TICKS = 40;
    /** An outbound stream without credit progress, or a granted upload without chunk progress, is aborted after this. */
    public static final long STALL_NANOS = 30_000_000_000L;
    /** Every stream (either way) must finish within {@code max(60 s, size / 256 KiB/s)}. */
    public static final long STREAM_MIN_DEADLINE_NANOS = 60_000_000_000L;
    public static final long STREAM_MIN_BYTES_PER_SECOND = 256L << 10;
    /** Outbound stream payloads queued for one player, and for everyone; beyond them streams are refused. */
    public static final long MAX_PLAYER_OUTBOUND_BYTES = 128L << 20;
    public static final long MAX_SERVER_OUTBOUND_BYTES = 512L << 20;

    private static final int LOGGED_VIOLATIONS = 3;
    private static final int MAX_HELD_EVENTS = 64;
    private static final Set<MessageType> REQUESTS = EnumSet.of(MessageType.RUN_OP, MessageType.UNDO, MessageType.REDO,
            MessageType.COPY, MessageType.LIBRARY_LIST, MessageType.LIBRARY_LOAD, MessageType.SAVE_ASSET,
            MessageType.EXPORT_CLIPBOARD, MessageType.UPLOAD_BEGIN, MessageType.SCATTER_PREVIEW, MessageType.LIBRARY_MOVE,
            MessageType.LIBRARY_DELETE, MessageType.LIBRARY_CREATE_FOLDER, MessageType.HISTORY_OVERWRITE,
            MessageType.PALETTE_SAVE, MessageType.PALETTE_LOAD, MessageType.SELECTION_UPLOAD,
            MessageType.LIBRARY_ACCESS_GET, MessageType.LIBRARY_ACCESS_SET, MessageType.GENERATED_UPLOAD);
    /** Tinker requests: answered {@code TinkerResult}, refusals included. */
    private static final Set<MessageType> TINKER = EnumSet.of(MessageType.TINKER_BLOCK, MessageType.TINKER_ENTITY);

    /** Receives one granted upload (M2). Called on the server thread. */
    public interface UploadHandler {
        /** Every byte arrived and the SHA-256 matched. */
        void completed(StreamOpen open, byte[] bytes);

        /** The upload was refused, aborted or corrupt, or the player left. */
        default void failed(String reason) {}
    }

    private final EditService edits;
    private final ClipboardService clipboards;
    private final ScatterService scatter;
    private final PermissionService permissions;
    private final Supplier<Limits> limits;
    private final Supplier<StateSpace> states;
    private final LongSupplier nanoClock;
    private final AtomicLong epochs = new AtomicLong(System.currentTimeMillis() << 20);
    /** Every session opened and not closed, for pushes to all players (library changes). Server thread only. */
    private final Set<NetSession> sessions = new LinkedHashSet<>();
    /** Payload bytes of every session's outbound streams. Server thread only. */
    private long outboundBytesTotal;
    /** Stamps every use of an uploaded set, so the least recently used of all connections can be told. */
    private long selectionStamp;
    private long maxPlayerOutboundBytes = MAX_PLAYER_OUTBOUND_BYTES;
    private long maxServerOutboundBytes = MAX_SERVER_OUTBOUND_BYTES;
    /** What {@code Hello} is answered with (read at each handshake); {@link #SERVER_FEATURES} unless offered otherwise. */
    private Supplier<Features> offered = () -> SERVER_FEATURES;
    /** Tinker (protocol 5): {@code DISABLED} until {@link #serveTinker} gives the running server's. */
    private TinkerService tinker = TinkerService.DISABLED;
    /** This server's build id, sent in {@code Welcome} and {@code Incompatible} ({@code ""}: not sent). */
    private String buildId = "";

    /**
     * @param clipboards the M2 service (clipboards, schematics, library)
     * @param scatter the M3 scatter previews
     * @param states the server's state space, read when a frame is decoded (it may be built after install)
     * @param nanoClock a monotonic clock for rate limits and resync windows, normally {@code System::nanoTime}
     */
    public ServerDispatcher(EditService edits, ClipboardService clipboards, ScatterService scatter,
                            PermissionService permissions, Supplier<Limits> limits, Supplier<StateSpace> states,
                            LongSupplier nanoClock) {
        this.edits = Objects.requireNonNull(edits);
        this.clipboards = Objects.requireNonNull(clipboards);
        this.scatter = Objects.requireNonNull(scatter);
        this.permissions = Objects.requireNonNull(permissions);
        this.limits = Objects.requireNonNull(limits);
        this.states = Objects.requireNonNull(states);
        this.nanoClock = Objects.requireNonNull(nanoClock);
    }

    /** Without scatter: previews are refused with {@code DISABLED}. */
    public ServerDispatcher(EditService edits, ClipboardService clipboards, PermissionService permissions,
                            Supplier<Limits> limits, Supplier<StateSpace> states, LongSupplier nanoClock) {
        this(edits, clipboards, ScatterService.DISABLED, permissions, limits, states, nanoClock);
    }

    /** Without clipboards or scatter: M2 and M3 requests are refused with {@code DISABLED}. */
    public ServerDispatcher(EditService edits, PermissionService permissions, Supplier<Limits> limits,
                            Supplier<StateSpace> states, LongSupplier nanoClock) {
        this(edits, ClipboardService.DISABLED, permissions, limits, states, nanoClock);
    }

    /** Fixed limits. */
    public ServerDispatcher(EditService edits, PermissionService permissions, Limits limits, Supplier<StateSpace> states,
                            LongSupplier nanoClock) {
        this(edits, permissions, () -> limits, states, nanoClock);
    }

    /**
     * Sets the features later handshakes offer: {@link #SERVER_FEATURES} plus those that depend on the running server
     * (the modded facing fallback, {@code ServerNet.offered}).
     */
    public void offerFeatures(Supplier<Features> features) {
        this.offered = Objects.requireNonNull(features);
    }

    /** The service {@code TinkerBlock} and {@code TinkerEntity} go to. */
    public void serveTinker(TinkerService tinker) {
        this.tinker = Objects.requireNonNull(tinker);
    }

    /** Sets this server's build id, which later handshakes send to clients. */
    public void buildId(String buildId) {
        this.buildId = Objects.requireNonNull(buildId);
    }

    public NetSession open(ServerTransport transport) {
        NetSession session = new NetSession(transport, epochs.incrementAndGet(), nanoClock);
        sessions.add(session);
        return session;
    }

    // =================================================================== inbound

    public void receive(NetSession s, byte[] frame) {
        if (s.stage == Stage.CLOSED || s.stage == Stage.INCOMPATIBLE) return;
        MessageType type = Codec.peekType(frame);
        if (type == null || !type.clientToServer()) {
            violation(s, type == null ? "unknown message type" : type + " sent to the server");
            return;
        }
        if (s.stage != Stage.READY && type != MessageType.HELLO) {
            violation(s, type + " before the handshake");
            return;
        }
        if (!s.limiter.tryAcquireFrame(type, frame.length)) {
            if (type == MessageType.STREAM_CHUNK) {
                refuseChunk(s, frame);
            } else if (withinFloodAllowance(s, type)) {
                refuseRateLimited(s, type, frame);
            }
            return;
        }
        C2S message;
        try {
            message = Codec.decodeC2S(frame, states.get());
        } catch (ProtocolException e) {
            refuseUndecodable(s, frame, e);
            return;
        }
        if (message instanceof C2S.Dabs dabs) {
            predicted(s);
            if (!s.limiter.tryAcquireDabs(dabCost(s, dabs))) {
                if (withinFloodAllowance(s, type)) refuse(s, dabs, RejectReason.RATE_LIMITED);
                return;
            }
        }
        dispatch(s, message);
    }

    /**
     * What a {@code Dabs} frame costs in the dab bucket: every dab with its copies under the open stroke's symmetry
     * (one per dab for a frame of another stroke, which is refused anyway).
     */
    private static int dabCost(NetSession s, C2S.Dabs dabs) {
        if (!s.strokeOpen || s.strokeId != dabs.strokeId()) return dabs.dabs().size();
        int cost = 0;
        for (Dab dab : dabs.dabs()) cost += s.strokeSymmetry.copyCount(dab);
        return cost;
    }

    /**
     * The client predicted every {@code Dabs} frame it sent, whether or not the frame is carried out: refused over a rate
     * limit, for a stroke that is not open, or by the brush lane. So each decoded one is reported to the edit service
     * before any of those checks, and bulk writes near the player do not resend whole columns over its predictions.
     * ({@link EditService#dabs} records it too, for callers other than this dispatcher.)
     */
    private void predicted(NetSession s) {
        try {
            edits.predicted(s.transport.player());
        } catch (RuntimeException e) {
            SculptoryMod.LOG.error("Sculptory: recording a brush prediction failed", e);
        }
    }

    /**
     * Charges a refusal for a rate limit (any frame but a stream chunk) to the flood allowance. Refusing is cheap, and an
     * honest client paced by the server's replies stays far below {@link NetSession#FLOOD_BURST} refusals; within that
     * allowance (refilled at {@link NetSession#FLOOD_PER_SECOND}) each refused request is answered. Past it the client
     * is flooding: its refused frames are dropped without an answer, so it gets no reply to every frame it sends, and
     * count as violations, at most one per server tick. So a client that keeps flooding is disconnected within
     * {@link NetSession#MAX_VIOLATIONS} ticks, while a burst after a server stall (every frame of it handled in one
     * tick) costs at most one.
     *
     * @return whether the refusal is within the allowance and should be answered
     */
    private boolean withinFloodAllowance(NetSession s, MessageType type) {
        if (s.floodAllowance.tryAcquire(1)) return true;
        if (s.stage != Stage.CLOSED && !s.floodCountedThisTick) {
            s.floodCountedThisTick = true;
            violation(s, "frames far over the rate limits (" + type + ")");
        }
        return false;
    }

    /**
     * A stream chunk over the upload byte rate (or one a client sends by the thousand: each costs at least
     * {@code StreamAssembler.MIN_CHUNK_BYTES}). The live upload it belongs to is aborted; the chunks the client still had
     * in flight for an upload that ended recently are dropped; a chunk of a stream that was never granted is a
     * violation.
     */
    private void refuseChunk(NetSession s, byte[] frame) {
        OptionalInt id = Codec.peekLeadingId(frame);
        if (id.isEmpty()) {
            violation(s, "malformed stream chunk");
        } else if (s.uploads.containsKey(id.getAsInt())) {
            failUpload(s, id.getAsInt(), "rate_limited");
        } else if (!recentlyEnded(s, id.getAsInt())) {
            violation(s, "chunk of stream " + id.getAsInt() + ", which was never granted");
        }
    }

    /** Remembers an upload that ended, so the chunks the client still had in flight for it are not violations. */
    private void rememberEnded(NetSession s, int id) {
        s.endedUploads.remove(id);
        s.endedUploads.put(id, nanoClock.getAsLong() + NetSession.RECENT_UPLOAD_NANOS);
        Iterator<Integer> oldest = s.endedUploads.keySet().iterator();
        while (s.endedUploads.size() > NetSession.RECENT_UPLOADS) {
            oldest.next();
            oldest.remove();
        }
    }

    private boolean recentlyEnded(NetSession s, int id) {
        Long until = s.endedUploads.get(id);
        return until != null && nanoClock.getAsLong() - until < 0;
    }

    private void dispatch(NetSession s, C2S message) {
        switch (message) {
            case C2S.Hello m -> hello(s, m);
            case C2S.StrokeBegin m -> beginStroke(s, m);
            case C2S.Dabs m -> dabs(s, m);
            case C2S.StrokeEnd m -> endStroke(s, m.strokeId());
            case C2S.Resync m -> resync(s, m.box());
            case C2S.RunOp m -> runOp(s, m);
            case C2S.CancelJob m -> cancel(s, m.jobId());
            case C2S.Undo m -> history(s, m.reqId(), m.policy(), true);
            case C2S.Redo m -> history(s, m.reqId(), m.policy(), false);
            case C2S.Copy m -> copy(s, m);
            case C2S.PreviewRequest m -> preview(s, m);
            case C2S.LibraryList m -> libraryList(s, m);
            case C2S.LibraryLoad m -> libraryLoad(s, m);
            case C2S.SaveAsset m -> saveAsset(s, m);
            case C2S.ExportClipboard m -> export(s, m);
            case C2S.UploadBegin m -> uploadBegin(s, m);
            case C2S.ScatterPreview m -> scatterPreview(s, m);
            case C2S.LibraryMove m -> libraryMove(s, m);
            case C2S.LibraryDelete m -> libraryDelete(s, m);
            case C2S.LibraryCreateFolder m -> libraryCreateFolder(s, m);
            case C2S.HistoryOverwrite m -> historyOverwrite(s, m);
            case C2S.PaletteSave m -> paletteSave(s, m);
            case C2S.PaletteLoad m -> paletteLoad(s, m);
            case C2S.LibraryAccessGet m -> libraryAccessGet(s, m);
            case C2S.LibraryAccessSet m -> libraryAccessSet(s, m);
            case C2S.SelectionUpload m -> selectionUpload(s, m);
            case C2S.GeneratedUpload m -> generatedUpload(s, m);
            case C2S.TinkerBlock m -> tinkerBlock(s, m);
            case C2S.TinkerEntity m -> tinkerEntity(s, m);
            case C2S.BuilderPowers m -> builderPowers(s, m);
            case C2S.BuilderPlace m -> builderPlace(s, m);
            case C2S.BuilderBreak m -> builderBreak(s, m);
            case C2S.BuilderDragEnd m -> builderDragEnd(s, m);
            case C2S.SetEditMask m -> setEditMask(s, m);
            case C2S.Navigate m -> navigate(s, m);
            // Client-to-server streams exist only for uploads granted through grantUpload (M2).
            case StreamOpen m -> uploadOpen(s, m);
            case StreamChunk m -> uploadChunk(s, m);
            case StreamEnd m -> uploadEnd(s, m);
            case StreamAbort m -> {
                removeOutbound(s, m.id());
                NetSession.Upload upload = s.uploads.remove(m.id());
                if (upload != null) {
                    rememberEnded(s, m.id());
                    notifyFailed(upload, "aborted by the client: " + m.reason());
                }
            }
            case StreamCredit m -> {
                StreamSender sender = s.outbound.get(m.id());
                if (sender != null) sender.credit(m);
            }
        }
    }

    private void hello(NetSession s, C2S.Hello hello) {
        if (s.stage != Stage.AWAITING_HELLO) {
            violation(s, "repeated Hello");
            return;
        }
        Limits current = currentLimits();
        PermissionMask mask = permissionMask(s);
        // Cleaned before it is stored, logged or shown (/sculptory version): it is whatever the client sent.
        s.clientBuild = Handshake.cleanBuild(hello.modVersion());
        S2C answer = Handshake.answer(hello, offered.get(), current, mask, s.epoch, buildId);
        ServerPlayerEntity player = s.transport.player();
        String name = player == null ? "a player" : player.getName().getString();
        if (answer instanceof S2C.Welcome welcome) {
            s.stage = Stage.READY;
            // Every connection starts with the global mask off; the client sends its mask after the Welcome.
            EditMasks.reset(player == null ? null : player.getUuid());
            s.protocol = welcome.protocol();
            s.features = welcome.features();
            s.permissions = mask;
            s.limits = current;
            send(s, welcome);
            sendHistory(s);
            if (Handshake.differentBuilds(s.clientBuild, buildId)) {
                SculptoryMod.LOG.info("Sculptory: {} connected with the editor (protocol {}, client build {}, "
                        + "a different build from this server's {})", name, welcome.protocol(), s.clientBuild, buildId);
            } else {
                SculptoryMod.LOG.info("Sculptory: {} connected with the editor (protocol {}, build {})", name,
                        welcome.protocol(), s.clientBuild);
            }
        } else {
            s.stage = Stage.INCOMPATIBLE;
            send(s, answer);
            SculptoryMod.LOG.info("Sculptory: {} has an incompatible editor (client build {}, protocol {}-{}; this "
                    + "server's build {}, protocol {}-{}): editing is off for them", name, s.clientBuild,
                    hello.minProtocol(), hello.maxProtocol(), buildId.isEmpty() ? "unknown" : buildId,
                    Handshake.MIN_PROTOCOL, Handshake.MAX_PROTOCOL);
        }
    }

    // =================================================================== strokes

    private void beginStroke(NetSession s, C2S.StrokeBegin m) {
        if (s.strokeOpen) finishStroke(s);
        try {
            edits.beginStroke(s.transport.player(), m.strokeId(), m.spec());
        } catch (EditRejected e) {
            send(s, new S2C.StrokeStatus(m.strokeId(), -1, S2C.StrokeStatus.Status.REJECTED, e.reason()));
            return;
        } catch (RuntimeException e) {
            SculptoryMod.LOG.error("Sculptory: beginStroke failed", e);
            send(s, new S2C.StrokeStatus(m.strokeId(), -1, S2C.StrokeStatus.Status.REJECTED, RejectReason.INVALID));
            return;
        }
        s.previousChunks = s.strokeChunks;
        s.previousEndedAt = s.strokeEndedAt;
        s.strokeChunks = new LongOpenHashSet();
        s.strokeOpen = true;
        s.strokeId = m.strokeId();
        s.strokeReach = m.spec().reach();
        s.strokeSymmetry = m.spec().symmetry();
        s.strokeAckedIndex = -1;
        s.strokeAppliedIndex = -1;
        if (m.spec().tool() == BrushTool.SHAPE) {
            s.writeReports.remove(m.strokeId());
            s.writeReports.put(m.strokeId(), -1);
            if (s.writeReports.size() > NetSession.MAX_WRITE_REPORTS) s.writeReports.remove(s.writeReports.keySet().iterator().next());
        }
        send(s, new S2C.StrokeStatus(m.strokeId(), -1, S2C.StrokeStatus.Status.OK, null));
    }

    private void dabs(NetSession s, C2S.Dabs m) {
        if (!s.strokeOpen || s.strokeId != m.strokeId()) {
            refuse(s, m, RejectReason.INVALID);
            return;
        }
        DabOutcome outcome;
        try {
            outcome = edits.dabs(s.transport.player(), m.strokeId(), m.seq(), m.dabs());
        } catch (RuntimeException e) {
            SculptoryMod.LOG.error("Sculptory: dabs failed", e);
            outcome = null;
        }
        if (outcome == null) outcome = DabOutcome.rejected(lastIndex(m), RejectReason.INVALID);
        int acked = Math.max(-1, outcome.lastIndex());
        if (outcome.accepted()) {
            // The brush lane acknowledges through ServerNet.predictionApplied once the batch is written.
            s.acks.admitted(m.seq());
            recordFootprint(s, m.dabs());
        } else {
            s.acks.refused(m.seq());
        }
        s.strokeAckedIndex = Math.max(s.strokeAckedIndex, acked);
        S2C.StrokeStatus.Status status = outcome.accepted() ? S2C.StrokeStatus.Status.OK : S2C.StrokeStatus.Status.REJECTED;
        send(s, new S2C.StrokeStatus(m.strokeId(), acked, status, outcome.reason(), s.strokeAppliedIndex));
    }

    /**
     * Remembers the chunks accepted dabs and their symmetric copies may change, so resyncs can be limited to them. (The
     * edit service refuses copies outside the world; one beyond the dab coordinates' range is left out here.)
     */
    private static void recordFootprint(NetSession s, List<Dab> dabs) {
        int r = s.strokeReach;
        for (Dab original : dabs) {
            List<Dab> copies;
            try {
                copies = s.strokeSymmetry.copies(original);
            } catch (IllegalArgumentException outOfRange) {
                copies = List.of(original);
            }
            for (Dab dab : copies) {
                for (int cx = (dab.blockX() - r) >> 4; cx <= (dab.blockX() + r) >> 4; cx++) {
                    for (int cz = (dab.blockZ() - r) >> 4; cz <= (dab.blockZ() + r) >> 4; cz++) {
                        if (s.strokeChunks.size() >= NetSession.MAX_FOOTPRINT_CHUNKS) return;
                        s.strokeChunks.add(NetSession.chunkKey(cx, cz));
                    }
                }
            }
        }
    }

    /**
     * The brush lane finished a stroke's dabs up to {@code lastIndex}. For a recent Shape stroke, open or ended (its
     * client paces its large dabs on what is written, and shows the shapes still being written), this sends
     * {@code StrokeStatus OK} with the new applied index.
     */
    public void dabsApplied(NetSession s, int strokeId, int lastIndex) {
        if (s.stage == Stage.CLOSED) return;
        boolean current = s.strokeOpen && s.strokeId == strokeId;
        if (current) s.strokeAppliedIndex = Math.max(s.strokeAppliedIndex, lastIndex);
        Integer reported = s.writeReports.get(strokeId);
        if (reported == null || lastIndex <= reported) return;
        s.writeReports.put(strokeId, lastIndex);
        int acked = current ? s.strokeAckedIndex : lastIndex;
        send(s, new S2C.StrokeStatus(strokeId, acked, S2C.StrokeStatus.Status.OK, null, lastIndex));
    }

    /** The brush lane wrote (or dropped) a batch admitted under {@code seq}: acknowledge it in order. */
    public void predictionApplied(NetSession s, int seq) {
        if (s.stage == Stage.CLOSED) return;
        s.acks.applied(seq);
    }

    // =================================================================== builder mode

    /** How often one builder notice (by key) may be sent: a drag or a held click repeats its refusal many times. */
    static final long BUILDER_NOTICE_NANOS = 1_000_000_000L;
    /** A placement left out some of its mirrored copies ({@code [copies]}). */
    public static final String NOTICE_BUILDER_COPIES_SKIPPED = "sculptory.notice.builder.copies_skipped";

    private void builderPowers(NetSession s, C2S.BuilderPowers m) {
        try {
            edits.builderPowers(s.transport.player(), m.powers());
        } catch (RuntimeException e) {
            SculptoryMod.LOG.error("Sculptory: builderPowers failed", e);
        }
    }

    /**
     * A builder placement: carried out (or refused) at once, then its prediction sequence is acknowledged in order with
     * the player's other predictions; a refusal gets a notice with its reason.
     */
    private void builderPlace(NetSession s, C2S.BuilderPlace m) {
        BuilderOutcome outcome;
        try {
            outcome = edits.builderPlace(s.transport.player(), m);
        } catch (RuntimeException e) {
            SculptoryMod.LOG.error("Sculptory: builderPlace failed", e);
            outcome = BuilderOutcome.refused(BuilderOutcome.Refusal.FAILED, e.toString());
        }
        s.acks.refused(m.seq());
        builderNotice(s, outcome);
        if (outcome.accepted() && outcome.skipped() > 0 && BuilderPower.MIRROR.in(m.powers())) {
            throttledNotice(s, new S2C.Notice(S2C.Notice.Level.INFO, NOTICE_BUILDER_COPIES_SKIPPED,
                    List.of(Integer.toString(outcome.skipped()))));
        }
    }

    private void builderBreak(NetSession s, C2S.BuilderBreak m) {
        BuilderOutcome outcome;
        try {
            outcome = edits.builderBreak(s.transport.player(), m);
        } catch (RuntimeException e) {
            SculptoryMod.LOG.error("Sculptory: builderBreak failed", e);
            outcome = BuilderOutcome.refused(BuilderOutcome.Refusal.FAILED, e.toString());
        }
        s.acks.refused(m.seq());
        builderNotice(s, outcome);
    }

    private void builderDragEnd(NetSession s, C2S.BuilderDragEnd m) {
        try {
            edits.builderDragEnd(s.transport.player(), m.dragId());
        } catch (RuntimeException e) {
            SculptoryMod.LOG.error("Sculptory: builderDragEnd failed", e);
        }
    }

    // =================================================================== the global mask, Jump and Through

    /**
     * {@code SetEditMask}: the player's global mask from now on ({@code EditMasks}), an
     * inside rule on an uploaded selection resolved against this connection's store. Answered {@code EditMaskState}; a
     * refusal (here, over the rate limit or undecodable) leaves the player's edits refused until a mask is accepted.
     */
    private void setEditMask(NetSession s, C2S.SetEditMask m) {
        UUID owner = owner(s);
        try {
            EditMasks.set(owner, m.mask(), region -> resolveSelection(s, region), states.get());
            send(s, S2C.EditMaskState.accepted(m.reqId()));
        } catch (EditRejected e) {
            send(s, S2C.EditMaskState.refused(m.reqId(), e.reason(), clip(e.detail())));
        } catch (RuntimeException e) {
            EditMasks.refuse(owner);
            SculptoryMod.LOG.error("Sculptory: setEditMask failed", e);
            send(s, S2C.EditMaskState.refused(m.reqId(), RejectReason.INVALID, ""));
        }
    }

    private static UUID owner(NetSession s) {
        ServerPlayerEntity player = s.transport.player();
        return player == null ? null : player.getUuid();
    }

    /** {@code Navigate}: carried out (or refused) at once by the edit service, answered {@code NavigateResult}. */
    private void navigate(NetSession s, C2S.Navigate m) {
        S2C.NavigateResult result;
        try {
            result = edits.navigate(s.transport.player(), m);
        } catch (RuntimeException e) {
            SculptoryMod.LOG.error("Sculptory: navigate failed", e);
            result = S2C.NavigateResult.refused(m.reqId(), RejectReason.INVALID);
        }
        send(s, result);
    }

    /** The refusal's notice ({@code [detail]}), at most once a second per reason; none for "nothing to break". */
    private void builderNotice(NetSession s, BuilderOutcome outcome) {
        if (outcome.accepted() || outcome.refusal() == BuilderOutcome.Refusal.NOTHING) return;
        throttledNotice(s, new S2C.Notice(S2C.Notice.Level.WARN, outcome.refusal().noticeKey(),
                List.of(clip(outcome.detail()))));
    }

    private void throttledNotice(NetSession s, S2C.Notice notice) {
        long now = nanoClock.getAsLong();
        Long last = s.builderNoticeAt.get(notice.key());
        if (last != null && now - last < BUILDER_NOTICE_NANOS) return;
        s.builderNoticeAt.put(notice.key(), now);
        send(s, notice);
    }

    private void endStroke(NetSession s, int strokeId) {
        if (s.strokeOpen && s.strokeId == strokeId) {
            finishStroke(s);
        } else {
            send(s, new S2C.StrokeStatus(strokeId, -1, S2C.StrokeStatus.Status.ENDED, null));
        }
    }

    private void finishStroke(NetSession s) {
        s.strokeOpen = false;
        s.strokeEndedAt = nanoClock.getAsLong();
        try {
            edits.endStroke(s.transport.player(), s.strokeId);
        } catch (RuntimeException e) {
            SculptoryMod.LOG.error("Sculptory: endStroke failed", e);
        }
        send(s, new S2C.StrokeStatus(s.strokeId, s.strokeAckedIndex, S2C.StrokeStatus.Status.ENDED, null));
        sendHistory(s);
    }

    /**
     * Resends chunks after the client lost a prediction acknowledgement. Needs {@link Perm#USE}, {@link Perm#BRUSH}
     * and a stroke that is open or ended within {@link #RESYNC_WINDOW_NANOS}; serves only chunks inside that stroke's (or
     * the previous stroke's) accepted-dab footprint that the player is tracking, at most
     * {@link #MAX_RESYNC_CHUNKS}. A request with no chunk in the footprint, or over the chunk cap, counts as a
     * violation; a cap overrun is still served (clamped) and answered with a notice.
     */
    private void resync(NetSession s, Box box) {
        long now = nanoClock.getAsLong();
        if (!has(s, Perm.USE) || !has(s, Perm.BRUSH)) {
            refuseResync(s, RESYNC_NO_PERMISSION);
            return;
        }
        boolean current = s.strokeOpen || recent(now, s.strokeEndedAt);
        boolean previous = recent(now, s.previousEndedAt);
        if (!current && !previous) {
            refuseResync(s, RESYNC_NO_STROKE);
            return;
        }
        int minCx = box.min().x() >> 4;
        int maxCx = box.max().x() >> 4;
        int minCz = box.min().z() >> 4;
        int maxCz = box.max().z() >> 4;
        long requested = ((long) maxCx - minCx + 1) * ((long) maxCz - minCz + 1);
        // Iterate the bounded footprints, never the requested box.
        LongOpenHashSet inside = new LongOpenHashSet();
        if (current) collectInside(s.strokeChunks, minCx, maxCx, minCz, maxCz, inside);
        if (previous) collectInside(s.previousChunks, minCx, maxCx, minCz, maxCz, inside);
        if (inside.isEmpty()) {
            violation(s, "resync outside the stroke footprint");
            refuseResync(s, RESYNC_OUT_OF_RANGE);
            return;
        }
        boolean oversized = requested > MAX_RESYNC_CHUNKS;
        if (oversized) violation(s, "resync of " + requested + " chunks");
        if (s.stage == Stage.CLOSED) return;
        LongArrayList sorted = new LongArrayList(inside);
        sorted.sort(null);
        int served = 0;
        for (int i = 0; i < sorted.size() && served < MAX_RESYNC_CHUNKS; i++) {
            long key = sorted.getLong(i);
            int cx = NetSession.chunkX(key);
            int cz = NetSession.chunkZ(key);
            if (!s.transport.tracks(cx, cz)) continue;
            s.transport.resendChunk(cx, cz);
            served++;
        }
        if (oversized || inside.size() > MAX_RESYNC_CHUNKS) {
            send(s, new S2C.Notice(S2C.Notice.Level.WARN, "sculptory.notice.resync_clamped",
                    List.of(Integer.toString(served), Long.toString(requested))));
        }
    }

    private static void collectInside(LongOpenHashSet chunks, int minCx, int maxCx, int minCz, int maxCz,
                                      LongOpenHashSet into) {
        for (long key : chunks) {
            int cx = NetSession.chunkX(key);
            int cz = NetSession.chunkZ(key);
            if (cx >= minCx && cx <= maxCx && cz >= minCz && cz <= maxCz) into.add(key);
        }
    }

    /** @param key one translatable message per reason, so the player reads plain English rather than a code */
    private void refuseResync(NetSession s, String key) {
        send(s, new S2C.Notice(S2C.Notice.Level.WARN, key, List.of()));
    }

    private static boolean recent(long now, long endedAt) {
        return endedAt != Long.MIN_VALUE && now - endedAt <= RESYNC_WINDOW_NANOS;
    }

    // =================================================================== jobs

    private void runOp(NetSession s, C2S.RunOp m) {
        JobRelay relay = new JobRelay(s);
        JobTicket ticket;
        try {
            OpSpec op = m.op();
            Region region = OpRegions.region(op);
            if (region instanceof Region.Uploaded) op = OpRegions.withRegion(op, resolveSelection(s, region));
            ticket = edits.run(s.transport.player(), op, new RunOptions(m.physics(), m.conflictPolicy(), m.label()), relay);
        } catch (EditRejected e) {
            relay.discard();
            strokePending(s, m.reqId(), e);
            send(s, new S2C.JobRejected(m.reqId(), e.reason()));
            return;
        } catch (RuntimeException e) {
            relay.discard();
            SculptoryMod.LOG.error("Sculptory: run failed", e);
            send(s, new S2C.JobRejected(m.reqId(), RejectReason.INVALID));
            return;
        }
        send(s, new S2C.JobAccepted(m.reqId(), ticket.jobId(), ticket.estimatedCells()));
        relay.release();
    }

    /**
     * Undo and redo take no listener: the engine reports their jobs through {@link #jobListener} (via
     * {@link ServerNet#jobListener}). Events reported while the job is being admitted are held until the
     * {@code JobAccepted} or {@code JobRejected} is sent.
     */
    private void history(NetSession s, int reqId, ConflictPolicy policy, boolean undo) {
        s.heldJobEvents = new ArrayList<>();
        try {
            ServerPlayerEntity player = s.transport.player();
            JobTicket ticket = undo ? edits.undo(player, policy) : edits.redo(player, policy);
            send(s, new S2C.JobAccepted(reqId, ticket.jobId(), ticket.estimatedCells()));
        } catch (EditRejected e) {
            strokePending(s, reqId, e);
            send(s, new S2C.JobRejected(reqId, e.reason()));
            sendHistory(s);
        } catch (RuntimeException e) {
            SculptoryMod.LOG.error("Sculptory: {} failed", undo ? "undo" : "redo", e);
            send(s, new S2C.JobRejected(reqId, RejectReason.INVALID));
        } finally {
            List<S2C> held = s.heldJobEvents;
            s.heldJobEvents = null;
            held.forEach(event -> forwardJobEvent(s, event));
        }
    }

    /**
     * Undo anyway / Redo anyway ({@code HistoryOverwrite}), answered like undo and redo: its job's events come through
     * {@link #jobListener} and are held until {@code JobAccepted} is sent. A refusal sends a
     * {@link #NOTICE_OVERWRITE_REFUSED} notice ({@code [reason, detail, kind]}) before its {@code JobRejected}, so the
     * client has the detail ("the history changed since those undo steps") when the refusal arrives, and the kind
     * ({@link EditRejected#HISTORY_RUN} when the player's run is not the one asked for, else "") to decide whether to
     * withdraw its offer.
     */
    private void historyOverwrite(NetSession s, C2S.HistoryOverwrite m) {
        s.heldJobEvents = new ArrayList<>();
        try {
            JobTicket ticket = edits.historyOverwrite(s.transport.player(), m.redo(), m.steps());
            send(s, new S2C.JobAccepted(m.reqId(), ticket.jobId(), ticket.estimatedCells()));
        } catch (EditRejected e) {
            send(s, new S2C.Notice(S2C.Notice.Level.WARN, NOTICE_OVERWRITE_REFUSED,
                    List.of(e.reason().name(), clip(e.detail()), e.kind())));
            send(s, new S2C.JobRejected(m.reqId(), e.reason()));
            sendHistory(s);
        } catch (RuntimeException e) {
            SculptoryMod.LOG.error("Sculptory: {} anyway failed", m.redo() ? "redo" : "undo", e);
            send(s, new S2C.JobRejected(m.reqId(), RejectReason.INVALID));
        } finally {
            List<S2C> held = s.heldJobEvents;
            s.heldJobEvents = null;
            held.forEach(event -> forwardJobEvent(s, event));
        }
    }

    // =================================================================== Tinker (protocol 5)

    /**
     * {@code TinkerBlock}: the change is made at once (one block, one history step) and answered
     * {@code TinkerResult(reqId)}, or refused with the reason and detail, nothing changed. The history state follows as
     * the history service reports the push.
     */
    private void tinkerBlock(NetSession s, C2S.TinkerBlock m) {
        try {
            tinker.block(s.transport.player(), m.pos(), m.expected(), m.target(), m.sign());
            send(s, S2C.TinkerResult.done(m.reqId(), new byte[0]));
        } catch (EditRejected e) {
            send(s, S2C.TinkerResult.refused(m.reqId(), e.reason(), clip(e.detail())));
        } catch (RuntimeException e) {
            SculptoryMod.LOG.error("Sculptory: a Tinker block change failed", e);
            send(s, S2C.TinkerResult.refused(m.reqId(), RejectReason.INVALID, "the server failed to make the change"));
        }
    }

    /**
     * {@code TinkerEntity}: the edits are made at once (none: only a look) and answered {@code TinkerResult(reqId)}
     * carrying what the panel shows of the entity afterwards ({@code EntityView}), or refused.
     */
    private void tinkerEntity(NetSession s, C2S.TinkerEntity m) {
        try {
            EntityView view = tinker.entity(s.transport.player(), m.entity(), m.edits());
            byte[] data = dev.sculptory.core.nbt.NbtIo.toBytes(view.write());
            if (data.length > S2C.TinkerResult.MAX_DATA_BYTES) {
                // Cannot happen with the view's bounded fields; answer without it rather than fail the request.
                SculptoryMod.LOG.warn("Sculptory: a Tinker entity view of {} bytes is not sent", data.length);
                data = new byte[0];
            }
            send(s, S2C.TinkerResult.done(m.reqId(), data));
        } catch (EditRejected e) {
            send(s, S2C.TinkerResult.refused(m.reqId(), e.reason(), clip(e.detail())));
        } catch (RuntimeException e) {
            SculptoryMod.LOG.error("Sculptory: a Tinker entity edit failed", e);
            send(s, S2C.TinkerResult.refused(m.reqId(), RejectReason.INVALID, "the server failed to make the change"));
        }
    }

    private void cancel(NetSession s, UUID jobId) {
        try {
            edits.cancel(s.transport.player(), jobId);
        } catch (RuntimeException e) {
            SculptoryMod.LOG.error("Sculptory: cancel failed", e);
        }
    }

    /** A listener that reports a job's progress and end to this session (for jobs started without one). */
    public JobListener jobListener(NetSession s) {
        return new JobListener() {
            @Override
            public void progress(UUID job, long done, long total, Phase ph) {
                if (job != null && ph != null) sessionJobEvent(s, new S2C.JobProgress(job, done, total, ph));
            }

            @Override
            public void finished(JobResult r) {
                if (r != null) sessionJobEvent(s, finishedMessage(r));
            }
        };
    }

    private void sessionJobEvent(NetSession s, S2C event) {
        List<S2C> held = s.heldJobEvents;
        if (held != null) {
            if (held.size() < MAX_HELD_EVENTS || event instanceof S2C.JobFinished) held.add(event);
            return;
        }
        forwardJobEvent(s, event);
    }

    private void forwardJobEvent(NetSession s, S2C event) {
        send(s, event);
        if (event instanceof S2C.JobFinished) sendHistory(s);
    }

    private static S2C.JobFinished finishedMessage(JobResult r) {
        return new S2C.JobFinished(r.jobId(), r.outcome(), r.changed(), r.skippedProtected(), r.skippedConflicts(),
                r.strippedNbt());
    }

    // =================================================================== clipboards, schematics, library (M2)

    private void copy(NetSession s, C2S.Copy m) {
        JobRelay relay = new JobRelay(s);
        JobTicket ticket;
        try {
            Region region = resolveSelection(s, m.region());
            ticket = clipboards.copy(s.transport.player(), region, m.origin(), m.cut(), m.mask(), m.entities(), relay,
                    clipboardReply(s, m.reqId()));
        } catch (EditRejected e) {
            relay.discard();
            if (e.reason() == RejectReason.SELECTION_NOT_LOADED) {
                // The client uploads the set again and retries: nothing to tell the player.
                send(s, new S2C.JobRejected(m.reqId(), e.reason()));
            } else {
                refuseRequest(s, m.reqId(), e);
            }
            return;
        } catch (RuntimeException e) {
            relay.discard();
            SculptoryMod.LOG.error("Sculptory: copy failed", e);
            send(s, new S2C.JobRejected(m.reqId(), RejectReason.INVALID));
            return;
        }
        if (ticket == null) {
            relay.discard();
            return;
        }
        send(s, new S2C.JobAccepted(m.reqId(), ticket.jobId(), ticket.estimatedCells()));
        relay.release();
    }

    /** {@code ClipboardReady} (and the import notices), or {@code JobRejected}. */
    private ClipboardService.Reply<ClipboardService.ClipboardInfo> clipboardReply(NetSession s, int reqId) {
        return new ClipboardService.Reply<>() {
            @Override
            public void done(ClipboardService.ClipboardInfo info) {
                send(s, new S2C.ClipboardReady(reqId, info.clipboardId(), info.dims(), info.anchor(), info.cells(),
                        info.bytes(), info.entities()));
                info.notices().forEach(notice -> send(s, notice));
            }

            @Override
            public void failed(RejectReason reason, String detail) {
                refuseRequest(s, reqId, reason, detail);
            }
        };
    }

    /** Whether another outbound stream fits, counting the ones still being produced ({@code QUEUE_FULL} before work). */
    private boolean streamRoom(NetSession s) {
        return s.outbound.size() + s.pendingStreams < NetSession.MAX_OUTBOUND_STREAMS
                && s.outboundBytes < maxPlayerOutboundBytes && outboundBytesTotal < maxServerOutboundBytes;
    }

    /** Whether two more outbound streams of {@code bytes} together fit now (a scatter plan with grown cells). */
    private boolean roomForTwo(NetSession s, long bytes) {
        return s.ready() && s.outbound.size() + s.pendingStreams + 2 <= NetSession.MAX_OUTBOUND_STREAMS
                && s.outboundBytes + bytes <= maxPlayerOutboundBytes
                && outboundBytesTotal + bytes <= maxServerOutboundBytes;
    }

    /** Counts a stream being produced until {@link #run()} (only the first call has an effect). */
    private static final class PendingStream implements Runnable {
        private final NetSession session;
        private boolean released;

        PendingStream(NetSession session) {
            this.session = session;
            session.pendingStreams++;
        }

        @Override
        public void run() {
            if (released) return;
            released = true;
            session.pendingStreams--;
        }
    }

    private void preview(NetSession s, C2S.PreviewRequest m) {
        if (!streamRoom(s)) {
            notice(s, NOTICE_PREVIEW_REFUSED, RejectReason.QUEUE_FULL, "too many streams");
            return;
        }
        PendingStream pending = new PendingStream(s);
        ClipboardService.Reply<ClipboardService.Outbound> reply = new ClipboardService.Reply<>() {
            @Override
            public void done(ClipboardService.Outbound out) {
                pending.run();
                if (openStream(s, out.kind(), out.payload(), out.meta()).isEmpty()) {
                    notice(s, NOTICE_PREVIEW_REFUSED, RejectReason.QUEUE_FULL, "too many streams or bytes queued");
                    return;
                }
                out.notices().forEach(notice -> send(s, notice));
            }

            @Override
            public void failed(RejectReason reason, String detail) {
                pending.run();
                notice(s, NOTICE_PREVIEW_REFUSED, reason, detail);
            }
        };
        try {
            clipboards.preview(s.transport.player(), m.source(), reply);
        } catch (EditRejected e) {
            pending.run();
            notice(s, NOTICE_PREVIEW_REFUSED, e.reason(), e.getMessage());
        } catch (RuntimeException e) {
            pending.run();
            SculptoryMod.LOG.error("Sculptory: preview failed", e);
            notice(s, NOTICE_PREVIEW_REFUSED, RejectReason.INVALID, "internal error");
        }
    }

    private void libraryList(NetSession s, C2S.LibraryList m) {
        request(s, m.reqId(), "library list", () -> clipboards.list(s.transport.player(), m.folder(), new ClipboardService.Reply<>() {
            @Override
            public void done(ClipboardService.Listing listing) {
                send(s, new S2C.LibraryListing(m.reqId(), listing.folder(), listing.entries(), listing.writable()));
                if (listing.truncated()) {
                    send(s, new S2C.Notice(S2C.Notice.Level.INFO, NOTICE_LIBRARY_TRUNCATED,
                            List.of(clip(listing.folder()), Integer.toString(listing.entries().size()))));
                }
            }

            @Override
            public void failed(RejectReason reason, String detail) {
                refuseRequest(s, m.reqId(), reason, detail);
            }
        }));
    }

    private void libraryLoad(NetSession s, C2S.LibraryLoad m) {
        request(s, m.reqId(), "library load",
                () -> clipboards.load(s.transport.player(), m.path(), clipboardReply(s, m.reqId())));
    }

    private void libraryMove(NetSession s, C2S.LibraryMove m) {
        request(s, m.reqId(), "library move",
                () -> clipboards.move(s.transport.player(), m.folder(), m.from(), m.to(), libraryChanged(s, m.reqId())));
    }

    private void libraryDelete(NetSession s, C2S.LibraryDelete m) {
        request(s, m.reqId(), "library delete",
                () -> clipboards.delete(s.transport.player(), m.folder(), m.path(), libraryChanged(s, m.reqId())));
    }

    private void libraryCreateFolder(NetSession s, C2S.LibraryCreateFolder m) {
        request(s, m.reqId(), "library folder",
                () -> clipboards.createFolder(s.transport.player(), m.path(), libraryChanged(s, m.reqId())));
    }

    /**
     * {@code LibraryChanged(reqId)} for the requester, then the change pushed ({@code LibraryChanged.PUSH}) to every
     * other ready session as that player may see it ({@link ClipboardService#shownTo}); or {@code JobRejected} and a
     * notice.
     */
    private ClipboardService.Reply<ClipboardService.LibraryChange> libraryChanged(NetSession s, int reqId) {
        return new ClipboardService.Reply<>() {
            @Override
            public void done(ClipboardService.LibraryChange change) {
                send(s, new S2C.LibraryChanged(reqId, change.folder(), change.from(), change.to()));
                pushLibraryChange(s, change);
            }

            @Override
            public void failed(RejectReason reason, String detail) {
                refuseRequest(s, reqId, reason, detail);
            }
        };
    }

    /** Pushes a library change to the other ready sessions (that negotiated the library) that may see some of it. */
    private void pushLibraryChange(NetSession requester, ClipboardService.LibraryChange change) {
        pushLibraryChange(requester, other -> clipboards.shownTo(other.transport.player(), change));
    }

    /** {@link #pushLibraryChange(NetSession, ClipboardService.LibraryChange)} with {@code view} deciding what each sees. */
    private void pushLibraryChange(NetSession requester,
                                   java.util.function.Function<NetSession, Optional<ClipboardService.LibraryChange>> view) {
        for (NetSession other : new ArrayList<>(sessions)) {
            if (other == requester || !other.ready() || !other.features().has(Features.LIBRARY)) continue;
            Optional<ClipboardService.LibraryChange> shown;
            try {
                shown = view.apply(other);
            } catch (RuntimeException e) {
                SculptoryMod.LOG.error("Sculptory: could not tell {} about a library change", playerName(other), e);
                continue;
            }
            shown.ifPresent(visible -> send(other, new S2C.LibraryChanged(S2C.LibraryChanged.PUSH, visible.folder(),
                    visible.from(), visible.to())));
        }
    }

    /**
     * Palettes. A save is a library write like the M4 changes: {@code LibraryChanged(reqId, false, "", path written)}
     * for the requester and the same change pushed to the other players who may see it ({@link #libraryChanged}).
     */
    private void paletteSave(NetSession s, C2S.PaletteSave m) {
        request(s, m.reqId(), "palette save",
                () -> clipboards.savePalette(s.transport.player(), m.path(), m.palette(), libraryChanged(s, m.reqId())));
    }

    /** Palettes. {@code PaletteData(reqId)}, or {@code JobRejected} and a notice. */
    private void paletteLoad(NetSession s, C2S.PaletteLoad m) {
        request(s, m.reqId(), "palette load", () -> clipboards.loadPalette(s.transport.player(), m.path(),
                new ClipboardService.Reply<>() {
                    @Override
                    public void done(ClipboardService.LoadedPalette loaded) {
                        send(s, new S2C.PaletteData(m.reqId(), loaded.path(), loaded.palette(), loaded.dropped(),
                                loaded.droppedStates()));
                    }

                    @Override
                    public void failed(RejectReason reason, String detail) {
                        refuseRequest(s, m.reqId(), reason, detail);
                    }
                }));
    }

    /** Per-asset access. {@code LibraryAccess(reqId)}, or {@code JobRejected} and a notice. */
    private void libraryAccessGet(NetSession s, C2S.LibraryAccessGet m) {
        request(s, m.reqId(), "library access", () -> clipboards.access(s.transport.player(), m.path(),
                new ClipboardService.Reply<>() {
                    @Override
                    public void done(AssetAccess access) {
                        send(s, new S2C.LibraryAccess(m.reqId(), m.path(), access));
                    }

                    @Override
                    public void failed(RejectReason reason, String detail) {
                        refuseRequest(s, m.reqId(), reason, detail);
                    }
                }));
    }

    /**
     * Per-asset access. {@code LibraryChanged(reqId, false, path, path)} for the requester, then the change pushed
     * ({@code LibraryChanged.PUSH}) to every other ready session as that player may see it
     * ({@link ClipboardService#shownAccessChange}: the entry appearing, or vanishing for a player who lost access); or
     * {@code JobRejected} and a notice.
     */
    private void libraryAccessSet(NetSession s, C2S.LibraryAccessSet m) {
        request(s, m.reqId(), "library access change", () -> clipboards.setAccess(s.transport.player(), m.path(),
                m.access(), new ClipboardService.Reply<>() {
                    @Override
                    public void done(ClipboardService.AccessChange change) {
                        ClipboardService.LibraryChange own = change.asLibraryChange();
                        send(s, new S2C.LibraryChanged(m.reqId(), own.folder(), own.from(), own.to()));
                        pushLibraryChange(s, other -> clipboards.shownAccessChange(other.transport.player(), change));
                    }

                    @Override
                    public void failed(RejectReason reason, String detail) {
                        refuseRequest(s, m.reqId(), reason, detail);
                    }
                }));
    }

    private void saveAsset(NetSession s, C2S.SaveAsset m) {
        request(s, m.reqId(), "save asset", () -> clipboards.save(s.transport.player(), m.clipboardId(), m.path(),
                new ClipboardService.Reply<>() {
                    @Override
                    public void done(ClipboardService.Saved saved) {
                        send(s, new S2C.AssetSaved(m.reqId(), saved.path(), saved.contentHash()));
                        saved.notices().forEach(notice -> send(s, notice));
                    }

                    @Override
                    public void failed(RejectReason reason, String detail) {
                        refuseRequest(s, m.reqId(), reason, detail);
                    }
                }));
    }

    private void export(NetSession s, C2S.ExportClipboard m) {
        if (!streamRoom(s)) {
            refuseRequest(s, m.reqId(), RejectReason.QUEUE_FULL, "too many streams");
            return;
        }
        PendingStream pending = new PendingStream(s);
        request(s, m.reqId(), "export", () -> clipboards.export(s.transport.player(), m.clipboardId(), m.format(),
                new ClipboardService.Reply<>() {
                    @Override
                    public void done(ClipboardService.Outbound out) {
                        pending.run();
                        TreeMap<String, String> meta = new TreeMap<>(out.meta());
                        meta.put("reqId", Integer.toString(m.reqId()));
                        if (openStream(s, out.kind(), out.payload(), meta).isEmpty()) {
                            refuseRequest(s, m.reqId(), RejectReason.QUEUE_FULL, "too many streams or bytes queued");
                            return;
                        }
                        out.notices().forEach(notice -> send(s, notice));
                    }

                    @Override
                    public void failed(RejectReason reason, String detail) {
                        pending.run();
                        refuseRequest(s, m.reqId(), reason, detail);
                    }
                }), pending);
    }

    private void uploadBegin(NetSession s, C2S.UploadBegin m) {
        ClipboardService.Upload upload;
        try {
            upload = clipboards.beginUpload(s.transport.player(), m.fileName(), m.totalBytes());
        } catch (EditRejected e) {
            refuseRequest(s, m.reqId(), e);
            return;
        } catch (RuntimeException e) {
            SculptoryMod.LOG.error("Sculptory: upload refused after an error", e);
            send(s, new S2C.JobRejected(m.reqId(), RejectReason.INVALID));
            return;
        }
        int reqId = m.reqId();
        UploadHandler handler = new UploadHandler() {
            @Override
            public void completed(StreamOpen open, byte[] bytes) {
                if (open.kind() != StreamKind.SCHEM_UPLOAD) {
                    upload.abort();
                    send(s, new S2C.UploadResult(reqId, null, "INVALID: expected a SCHEM_UPLOAD stream"));
                    return;
                }
                ClipboardService.Reply<ClipboardService.ClipboardInfo> reply = new ClipboardService.Reply<>() {
                    @Override
                    public void done(ClipboardService.ClipboardInfo info) {
                        send(s, new S2C.ClipboardReady(reqId, info.clipboardId(), info.dims(), info.anchor(),
                                info.cells(), info.bytes(), info.entities()));
                        send(s, new S2C.UploadResult(reqId, info.clipboardId(), null));
                        info.notices().forEach(notice -> send(s, notice));
                    }

                    @Override
                    public void failed(RejectReason reason, String detail) {
                        send(s, new S2C.UploadResult(reqId, null, clip(reason.name() + ": " + detail)));
                    }
                };
                try {
                    upload.completed(bytes, reply);
                } catch (RuntimeException e) {
                    upload.abort();
                    SculptoryMod.LOG.error("Sculptory: upload handling failed", e);
                    send(s, new S2C.UploadResult(reqId, null, "INVALID: internal error"));
                }
            }

            @Override
            public void failed(String reason) {
                upload.abort();
                send(s, new S2C.UploadResult(reqId, null, clip("upload failed: " + reason)));
            }
        };
        if (grantUpload(s, reqId, Math.min(upload.maxBytes(), m.totalBytes()), handler).isEmpty()) {
            upload.abort();
            refuseRequest(s, reqId, RejectReason.QUEUE_FULL, "too many uploads");
        }
    }

    // =================================================================== generated uploads (generators)

    /**
     * {@code GeneratedUpload}, handled as {@code UploadBegin} is: {@code UploadGrant}, then the client streams the sparse
     * payload on a {@code GENERATED_UPLOAD} stream; the clipboard service decodes it off the server thread into the
     * player's clipboard and answers {@code ClipboardReady} + {@code UploadResult(reqId, clipboardId)}, or
     * {@code UploadResult(reqId, "REASON: detail")}. A refusal before the grant is {@code JobRejected}.
     */
    private void generatedUpload(NetSession s, C2S.GeneratedUpload m) {
        int reqId = m.reqId();
        ClipboardService.Upload upload;
        try {
            upload = clipboards.beginGeneratedUpload(s.transport.player(), m.bounds(), m.cells(), m.totalBytes());
        } catch (EditRejected e) {
            refuseRequest(s, reqId, e);
            return;
        } catch (RuntimeException e) {
            SculptoryMod.LOG.error("Sculptory: generated upload refused after an error", e);
            send(s, new S2C.JobRejected(reqId, RejectReason.INVALID));
            return;
        }
        UploadHandler handler = new UploadHandler() {
            @Override
            public void completed(StreamOpen open, byte[] bytes) {
                if (open.kind() != StreamKind.GENERATED_UPLOAD) {
                    upload.abort();
                    send(s, new S2C.UploadResult(reqId, null, "INVALID: expected a GENERATED_UPLOAD stream"));
                    return;
                }
                ClipboardService.Reply<ClipboardService.ClipboardInfo> reply = new ClipboardService.Reply<>() {
                    @Override
                    public void done(ClipboardService.ClipboardInfo info) {
                        send(s, new S2C.ClipboardReady(reqId, info.clipboardId(), info.dims(), info.anchor(),
                                info.cells(), info.bytes(), info.entities()));
                        send(s, new S2C.UploadResult(reqId, info.clipboardId(), null));
                        info.notices().forEach(notice -> send(s, notice));
                    }

                    @Override
                    public void failed(RejectReason reason, String detail) {
                        send(s, new S2C.UploadResult(reqId, null, clip(reason.name() + ": " + detail)));
                    }
                };
                try {
                    upload.completed(bytes, reply);
                } catch (RuntimeException e) {
                    upload.abort();
                    SculptoryMod.LOG.error("Sculptory: generated upload handling failed", e);
                    send(s, new S2C.UploadResult(reqId, null, "INVALID: internal error"));
                }
            }

            @Override
            public void failed(String reason) {
                upload.abort();
                send(s, new S2C.UploadResult(reqId, null, clip("upload failed: " + reason)));
            }
        };
        if (grantUpload(s, reqId, Math.min(upload.maxBytes(), m.totalBytes()), handler).isEmpty()) {
            upload.abort();
            refuseRequest(s, reqId, RejectReason.QUEUE_FULL, "too many uploads");
        }
    }

    // =================================================================== selection uploads (regions)

    /**
     * {@code SelectionUpload}, like {@code UploadBegin}: {@code UploadGrant}, then the client streams the set's
     * {@code CellSet.encode()} bytes on a {@code SELECTION_UPLOAD} stream; the clipboard service decodes them off the
     * server thread and checks they are the set announced, and the set joins the connection's
     * {@link SelectionStore}: {@code SelectionReady(reqId, hash)}. Past the server-wide cap ({@code totalStoreBytes}) the
     * least recently used sets of any connection are dropped. A refusal (before or after the stream), a failed stream,
     * and a set too large for a store are {@code JobRejected(reqId, reason)} with a {@code request_refused} notice. A set
     * decoded after the connection closed is dropped.
     */
    private void selectionUpload(NetSession s, C2S.SelectionUpload m) {
        int reqId = m.reqId();
        ClipboardService.SelectionUpload upload;
        try {
            upload = clipboards.beginSelectionUpload(s.transport.player(), m.hash(), m.bounds(), m.cells(), m.totalBytes());
        } catch (EditRejected e) {
            refuseRequest(s, reqId, e);
            return;
        } catch (RuntimeException e) {
            SculptoryMod.LOG.error("Sculptory: selection upload refused after an error", e);
            send(s, new S2C.JobRejected(reqId, RejectReason.INVALID));
            return;
        }
        ClipboardService.Reply<CellSet> reply = new ClipboardService.Reply<>() {
            @Override
            public void done(CellSet set) {
                // Decoded after the player left: nobody will use it.
                if (s.stage == Stage.CLOSED) return;
                long size = set.estimatedBytes();
                if (size > upload.totalStoreBytes() || !s.selections.put(set, upload.storeBytes(), ++selectionStamp)) {
                    refuseRequest(s, reqId, RejectReason.TOO_LARGE, "the selection needs more memory than a player may use");
                    return;
                }
                keepSelectionsWithin(upload.totalStoreBytes());
                send(s, new S2C.SelectionReady(reqId, set.hash()));
            }

            @Override
            public void failed(RejectReason reason, String detail) {
                refuseRequest(s, reqId, reason, detail);
            }
        };
        UploadHandler handler = new UploadHandler() {
            @Override
            public void completed(StreamOpen open, byte[] bytes) {
                if (open.kind() != StreamKind.SELECTION_UPLOAD) {
                    upload.abort();
                    refuseRequest(s, reqId, RejectReason.INVALID, "expected a SELECTION_UPLOAD stream");
                    return;
                }
                try {
                    upload.completed(bytes, reply);
                } catch (RuntimeException e) {
                    upload.abort();
                    SculptoryMod.LOG.error("Sculptory: selection upload handling failed", e);
                    send(s, new S2C.JobRejected(reqId, RejectReason.INVALID));
                }
            }

            @Override
            public void failed(String reason) {
                upload.abort();
                RejectReason why = switch (reason) {
                    case "too_large" -> RejectReason.TOO_LARGE;
                    case "rate_limited" -> RejectReason.RATE_LIMITED;
                    default -> RejectReason.INVALID;
                };
                refuseRequest(s, reqId, why, "the upload failed: " + reason);
            }
        };
        if (grantUpload(s, reqId, Math.min(upload.maxBytes(), m.totalBytes()), handler).isEmpty()) {
            upload.abort();
            refuseRequest(s, reqId, RejectReason.QUEUE_FULL, "too many uploads");
        }
    }

    /**
     * Drops the least recently used uploaded sets of any connection until all of them together hold at most
     * {@code maxBytes}; their owners' clients upload them again when they next need them ({@code SELECTION_NOT_LOADED}).
     */
    private void keepSelectionsWithin(long maxBytes) {
        long total = 0;
        for (NetSession session : sessions) total += session.selections.bytes();
        while (total > maxBytes) {
            NetSession oldest = null;
            for (NetSession session : sessions) {
                if (session.selections.size() > 0
                        && (oldest == null || session.selections.oldestStamp() < oldest.selections.oldestStamp())) {
                    oldest = session;
                }
            }
            if (oldest == null) return;
            total -= oldest.selections.dropOldest();
        }
    }

    /** The uploaded sets every connection holds together (tests). */
    public long selectionBytes() {
        long total = 0;
        for (NetSession session : sessions) total += session.selections.bytes();
        return total;
    }

    /**
     * {@code region} with an {@code Uploaded} reference swapped for the cells this connection uploaded under its hash:
     * {@code SELECTION_NOT_LOADED} when the store holds none (never uploaded here, or dropped for newer ones: the client
     * uploads it again), {@code INVALID} when the reference's bounds or cell count are not the set's. Any other region
     * is returned as it is.
     */
    Region resolveSelection(NetSession s, Region region) throws EditRejected {
        if (!(region instanceof Region.Uploaded uploaded)) return region;
        CellSet set = s.selections.get(uploaded.hash(), ++selectionStamp).orElseThrow(() -> new EditRejected(
                RejectReason.SELECTION_NOT_LOADED, "the server does not hold that selection"));
        if (!set.bounds().equals(uploaded.bounds()) || set.size() != uploaded.cellCount()) {
            throw new EditRejected(RejectReason.INVALID, "the selection is not the one uploaded");
        }
        return new Region.Cells(set);
    }

    // =================================================================== scatter (M3)

    /**
     * A new preview replaces the one in flight, so the old one's stream reservation is released before the room
     * check. (If the new one is then refused, the old one plans on without a reservation; its stream may then be
     * refused for room, with a notice.)
     */
    private void scatterPreview(NetSession s, C2S.ScatterPreview m) {
        int reqId = m.reqId();
        if (s.scatterReservation != null) {
            s.scatterReservation.run();
            s.scatterReservation = null;
        }
        if (!streamRoom(s)) {
            refuseRequest(s, reqId, RejectReason.QUEUE_FULL, "too many streams");
            return;
        }
        PendingStream reserved = new PendingStream(s);
        Runnable pending = () -> {
            reserved.run();
            if (s.scatterReservation == reserved) s.scatterReservation = null;
        };
        s.scatterReservation = reserved;
        ScatterService.PreviewReply reply = new ScatterService.PreviewReply() {
            @Override
            public void done(ScatterService.PlanReady plan) {
                pending.run();
                // Trees and features (scatter-features) need a second stream: both fit, or the preview is refused now.
                byte[] grownCells = plan.placements() == 0 ? null : plan.generatedPayload();
                if (grownCells != null && !roomForTwo(s, plan.placementsPayload().length + (long) grownCells.length)) {
                    refuseRequest(s, reqId, RejectReason.QUEUE_FULL, "too many streams or bytes queued");
                    return;
                }
                send(s, new S2C.ScatterPlan(reqId, plan.planId(), plan.placements(), plan.rejectedCounts(),
                        plan.totalCells(), plan.bounds()));
                if (plan.placements() == 0) return;
                TreeMap<String, String> meta = new TreeMap<>();
                meta.put(ScatterPlacements.META_FORMAT, ScatterPlacements.FORMAT_NAME);
                meta.put(ScatterPlacements.META_PLAN_ID, plan.planId().toString());
                meta.put(ScatterPlacements.META_REQ_ID, Integer.toString(reqId));
                meta.put(ScatterPlacements.META_PLACEMENTS, Integer.toString(plan.placements()));
                if (openStream(s, StreamKind.SCATTER_PLACEMENTS, plan.placementsPayload(), meta).isEmpty()) {
                    notice(s, NOTICE_PREVIEW_REFUSED, RejectReason.QUEUE_FULL, "too many streams or bytes queued");
                    return;
                }
                // What the plan's trees and features grow: a second stream, same plan and request.
                if (grownCells == null) return;
                TreeMap<String, String> grown = new TreeMap<>(Map.of(ScatterPlacements.META_PLAN_ID,
                        plan.planId().toString(), ScatterPlacements.META_REQ_ID, Integer.toString(reqId)));
                if (openStream(s, StreamKind.SCATTER_GENERATED, grownCells, grown).isEmpty()) {
                    notice(s, NOTICE_PREVIEW_REFUSED, RejectReason.QUEUE_FULL, "too many streams or bytes queued");
                }
            }

            @Override
            public void failed(RejectReason reason, String detail) {
                pending.run();
                refuseRequest(s, reqId, reason, detail);
            }

            @Override
            public void superseded() {
                pending.run();
                // The client replaced this preview itself: no notice.
                send(s, new S2C.JobRejected(reqId, RejectReason.QUEUE_FULL));
            }
        };
        request(s, reqId, "scatter preview", () -> scatter.preview(s.transport.player(), m, reply), pending);
    }

    @FunctionalInterface
    private interface Request {
        void run() throws EditRejected;
    }

    /** Runs a request, answering a synchronous refusal or failure with {@code JobRejected}. */
    private void request(NetSession s, int reqId, String what, Request request) {
        request(s, reqId, what, request, () -> { });
    }

    /** As {@link #request(NetSession, int, String, Request)}; {@code refused} runs first when it throws. */
    private void request(NetSession s, int reqId, String what, Request request, Runnable refused) {
        try {
            request.run();
        } catch (EditRejected e) {
            refused.run();
            refuseRequest(s, reqId, e);
        } catch (RuntimeException e) {
            refused.run();
            SculptoryMod.LOG.error("Sculptory: {} failed", what, e);
            send(s, new S2C.JobRejected(reqId, RejectReason.INVALID));
        }
    }

    private void refuseRequest(NetSession s, int reqId, EditRejected e) {
        strokePending(s, reqId, e);
        refuseRequest(s, reqId, e.reason(), e.getMessage());
    }

    /**
     * Before the refusal of request {@code reqId}: when it was refused only because the player's brush stroke is still
     * being written or committed ({@link EditRejected#STROKE_PENDING}), a {@link #NOTICE_STROKE_PENDING} notice
     * {@code [reqId]}, so the client holds the request and sends it again a moment later instead of reporting it.
     */
    private void strokePending(NetSession s, int reqId, EditRejected e) {
        if (!EditRejected.STROKE_PENDING.equals(e.kind())) return;
        send(s, new S2C.Notice(S2C.Notice.Level.INFO, NOTICE_STROKE_PENDING, List.of(Integer.toString(reqId))));
    }

    /** {@code JobRejected} plus a notice with the detail. */
    private void refuseRequest(NetSession s, int reqId, RejectReason reason, String detail) {
        send(s, new S2C.JobRejected(reqId, reason));
        notice(s, NOTICE_REQUEST_REFUSED, reason, detail);
    }

    private void notice(NetSession s, String key, RejectReason reason, String detail) {
        send(s, new S2C.Notice(S2C.Notice.Level.WARN, key, List.of(reason.name(), clip(detail))));
    }

    // =================================================================== refusals and violations

    /** Answers a decoded message that will not be carried out, so the client's pending request completes. */
    private void refuse(NetSession s, C2S message, RejectReason reason) {
        switch (message) {
            case C2S.StrokeBegin m -> send(s, new S2C.StrokeStatus(m.strokeId(), -1, S2C.StrokeStatus.Status.REJECTED, reason));
            case C2S.Dabs m -> {
                s.acks.refused(m.seq());
                send(s, new S2C.StrokeStatus(m.strokeId(), lastIndex(m), S2C.StrokeStatus.Status.REJECTED, reason));
            }
            case StreamOpen m -> send(s, new StreamAbort(m.id(), reason == RejectReason.INVALID ? "not_granted" : "rate_limited"));
            case C2S.TinkerBlock m -> send(s, S2C.TinkerResult.refused(m.reqId(), reason, ""));
            case C2S.TinkerEntity m -> send(s, S2C.TinkerResult.refused(m.reqId(), reason, ""));
            case C2S.SetEditMask m -> {
                EditMasks.refuse(owner(s));
                send(s, S2C.EditMaskState.refused(m.reqId(), reason, ""));
            }
            case C2S.Navigate m -> send(s, S2C.NavigateResult.refused(m.reqId(), reason));
            default -> {
                OptionalInt reqId = requestId(message);
                if (reqId.isPresent()) send(s, new S2C.JobRejected(reqId.getAsInt(), reason));
            }
        }
    }

    /** A frame over its rate limit, refused without decoding it (dabs excepted, so the prediction reverts). */
    private void refuseRateLimited(NetSession s, MessageType type, byte[] frame) {
        if (type == MessageType.DABS) {
            try {
                C2S dabs = Codec.decodeC2S(frame, null);
                predicted(s);
                refuse(s, dabs, RejectReason.RATE_LIMITED);
            } catch (ProtocolException e) {
                violation(s, e.getMessage());
            }
            return;
        }
        OptionalInt id = Codec.peekLeadingId(frame);
        if (id.isEmpty()) return;
        int value = id.getAsInt();
        if (type == MessageType.BUILDER_PLACE || type == MessageType.BUILDER_BREAK) {
            // Predicted by the client: acknowledging the sequence takes the prediction back.
            predicted(s);
            s.acks.refused(value);
            builderNotice(s, BuilderOutcome.refused(BuilderOutcome.Refusal.RATE_LIMITED, ""));
            return;
        }
        if (type == MessageType.STROKE_BEGIN) {
            send(s, new S2C.StrokeStatus(value, -1, S2C.StrokeStatus.Status.REJECTED, RejectReason.RATE_LIMITED));
        } else if (REQUESTS.contains(type)) {
            send(s, new S2C.JobRejected(value, RejectReason.RATE_LIMITED));
        } else if (TINKER.contains(type)) {
            send(s, S2C.TinkerResult.refused(value, RejectReason.RATE_LIMITED, ""));
        } else if (type == MessageType.SET_EDIT_MASK) {
            EditMasks.refuse(owner(s));
            send(s, S2C.EditMaskState.refused(value, RejectReason.RATE_LIMITED, ""));
        } else if (type == MessageType.NAVIGATE) {
            send(s, S2C.NavigateResult.refused(value, RejectReason.RATE_LIMITED));
        } else if (type == MessageType.STREAM_OPEN) { // stream chunks: refuseChunk
            if (s.uploads.containsKey(value)) failUpload(s, value, "rate_limited");
            else send(s, new StreamAbort(value, "rate_limited"));
        }
    }

    private void refuseUndecodable(NetSession s, byte[] frame, ProtocolException e) {
        boolean unknownState = e.reason() == ProtocolException.Reason.UNKNOWN_STATE;
        // A registry mismatch is not the client's fault, but only a few per minute are excused.
        boolean excused = unknownState && s.unknownStates.tryAcquire(1);
        if (!excused) violation(s, e.getMessage());
        if (s.stage != Stage.READY) return;
        MessageType type = Codec.peekType(frame);
        OptionalInt id = Codec.peekLeadingId(frame);
        if (type == null || id.isEmpty()) return;
        if (type == MessageType.STROKE_BEGIN) {
            send(s, new S2C.StrokeStatus(id.getAsInt(), -1, S2C.StrokeStatus.Status.REJECTED, RejectReason.INVALID));
        } else if (REQUESTS.contains(type)) {
            send(s, new S2C.JobRejected(id.getAsInt(), RejectReason.INVALID));
        } else if (TINKER.contains(type)) {
            // A bare reason, as the other kinds get: the parser's message is for the log, not the client.
            send(s, S2C.TinkerResult.refused(id.getAsInt(), RejectReason.INVALID, ""));
        } else if (type == MessageType.SET_EDIT_MASK) {
            EditMasks.refuse(owner(s));
            send(s, S2C.EditMaskState.refused(id.getAsInt(), RejectReason.INVALID, ""));
        } else if (type == MessageType.NAVIGATE) {
            send(s, S2C.NavigateResult.refused(id.getAsInt(), RejectReason.INVALID));
        }
        if (type == MessageType.BUILDER_PLACE || type == MessageType.BUILDER_BREAK) s.acks.refused(id.getAsInt());
        if (unknownState) {
            send(s, new S2C.Notice(S2C.Notice.Level.WARN, "sculptory.notice.unknown_state", List.of(clip(e.getMessage()))));
        }
    }

    private void violation(NetSession s, String reason) {
        s.violations++;
        if (s.violations <= LOGGED_VIOLATIONS) {
            SculptoryMod.LOG.warn("Sculptory: invalid message from {}: {}", playerName(s), reason);
        }
        if (s.violations >= NetSession.MAX_VIOLATIONS && s.stage != Stage.CLOSED) {
            SculptoryMod.LOG.warn("Sculptory: disconnecting {} after {} invalid messages", playerName(s), s.violations);
            close(s);
            s.transport.disconnect("Sculptory: too many invalid messages");
        }
    }

    // =================================================================== outbound and lifecycle

    /** Encodes and sends; drops the message when the session is closed or the client lacks the channel. */
    public void send(NetSession s, S2C message) {
        if (s.stage == Stage.CLOSED || !s.transport.canSend()) return;
        byte[] frame;
        try {
            frame = Codec.encodeS2C(message, states.get());
        } catch (ProtocolException e) {
            SculptoryMod.LOG.error("Sculptory: cannot encode {}: {}", message.type(), e.getMessage());
            return;
        }
        s.transport.send(frame);
    }

    /** Sends the history state from the {@link HistoryView} edit service, if it is one. */
    public void sendHistory(NetSession s) {
        if (!s.ready() || !(edits instanceof HistoryView view)) return;
        S2C.HistoryState state;
        try {
            state = view.historyState(s.transport.player());
        } catch (RuntimeException e) {
            SculptoryMod.LOG.error("Sculptory: history state failed", e);
            return;
        }
        if (state != null) send(s, state);
    }

    /**
     * Re-reads permissions and limits and pushes {@code PermissionsChanged} if either changed. Nothing happens for a
     * session without a completed handshake (vanilla clients never have one), and nothing is sent when nothing
     * changed.
     */
    public void permissionsChanged(NetSession s) {
        if (!s.ready()) return;
        s.ticksSincePermissionCheck = 0;
        PermissionMask mask = permissionMask(s);
        Limits current = currentLimits();
        if (mask.equals(s.permissions) && current.equals(s.limits)) return;
        s.permissions = mask;
        s.limits = current;
        send(s, new S2C.PermissionsChanged(mask, current));
    }

    /**
     * Queues a server-to-client stream (M2: previews, schematic files). Sent from {@link #tick} within the
     * per-tick budget and the client's credit. Empty when the session is not ready, has too many streams, or the
     * payload would take the player's queued bytes over {@link #MAX_PLAYER_OUTBOUND_BYTES} or everyone's over
     * {@link #MAX_SERVER_OUTBOUND_BYTES}. The stream must finish before {@link #streamDeadlineNanos its deadline}.
     */
    public OptionalInt openStream(NetSession s, StreamKind kind, byte[] payload, SortedMap<String, String> meta) {
        if (!s.ready() || s.outbound.size() >= NetSession.MAX_OUTBOUND_STREAMS) return OptionalInt.empty();
        long size = payload.length;
        if (s.outboundBytes + size > maxPlayerOutboundBytes || outboundBytesTotal + size > maxServerOutboundBytes) {
            return OptionalInt.empty();
        }
        int id = s.nextStreamId++;
        s.outbound.put(id, new StreamSender(id, kind, payload, meta, StreamSender.MAX_S2C_CHUNK,
                StreamAssembler.DEFAULT_WINDOW));
        long now = nanoClock.getAsLong();
        s.outboundProgress.put(id, new NetSession.Progress(0, now, now + streamDeadlineNanos(size)));
        s.outboundBytes += size;
        outboundBytesTotal += size;
        return OptionalInt.of(id);
    }

    /** How long a stream of {@code bytes} may take in all: {@code max(60 s, bytes / 256 KiB/s)}. */
    public static long streamDeadlineNanos(long bytes) {
        long transfer;
        try {
            transfer = Math.multiplyExact(Math.max(0, bytes), 1_000_000_000L) / STREAM_MIN_BYTES_PER_SECOND;
        } catch (ArithmeticException tooLarge) {
            transfer = Long.MAX_VALUE / 4;
        }
        return Math.max(STREAM_MIN_DEADLINE_NANOS, transfer);
    }

    /** Stream data queued for everyone (tests). */
    public long outboundBytesTotal() {
        return outboundBytesTotal;
    }

    /** Replaces the queued-bytes caps (defaults {@link #MAX_PLAYER_OUTBOUND_BYTES}, {@link #MAX_SERVER_OUTBOUND_BYTES}). */
    public void setOutboundByteCaps(long perPlayer, long total) {
        if (perPlayer < 1 || total < 1) throw new IllegalArgumentException("Caps must be positive");
        maxPlayerOutboundBytes = perPlayer;
        maxServerOutboundBytes = total;
    }

    /** Drops an outbound stream (finished, aborted by either side) and its byte count. */
    private void removeOutbound(NetSession s, int id) {
        StreamSender sender = s.outbound.remove(id);
        s.outboundProgress.remove(id);
        if (sender == null) return;
        long size = sender.open().totalBytes();
        s.outboundBytes -= size;
        outboundBytesTotal -= size;
    }

    /**
     * Lets the client upload one stream (M2: {@code .schem} files): sends {@code UploadGrant} with a fresh
     * stream id and a {@link StreamAssembler#DEFAULT_WINDOW} credit, then assembles the stream, granting more
     * credit as data arrives. The size cap is the smaller of {@code maxBytes} and the server's
     * {@code maxUploadBytes} when granted (a config reload later does not change it). Chunk frames count against the
     * 1 MiB/s upload limit and a frame over it aborts the upload, so clients pace uploads
     * ({@link StreamSender#CLIENT_BYTES_PER_TICK}). Empty when the session is not ready or already has
     * {@link NetSession#MAX_UPLOADS} uploads.
     */
    public OptionalInt grantUpload(NetSession s, int reqId, long maxBytes, UploadHandler handler) {
        Objects.requireNonNull(handler);
        if (!s.ready() || s.uploads.size() >= NetSession.MAX_UPLOADS) return OptionalInt.empty();
        int id = s.nextStreamId++;
        long now = nanoClock.getAsLong();
        long cap = Math.min(maxBytes, currentLimits().maxUploadBytes());
        s.uploads.put(id, new NetSession.Upload(cap, handler, now, now + streamDeadlineNanos(maxBytes)));
        send(s, new S2C.UploadGrant(reqId, id, StreamAssembler.DEFAULT_WINDOW));
        return OptionalInt.of(id);
    }

    private void uploadOpen(NetSession s, StreamOpen m) {
        NetSession.Upload upload = s.uploads.get(m.id());
        if (upload == null) {
            refuse(s, m, RejectReason.INVALID);
            return;
        }
        if (upload.assembler != null) {
            violation(s, "stream " + m.id() + " opened twice");
            return;
        }
        try {
            upload.assembler = new StreamAssembler(m, upload.maxBytes, StreamAssembler.DEFAULT_WINDOW);
            upload.lastProgress = nanoClock.getAsLong();
        } catch (ProtocolException e) {
            failUpload(s, m.id(), "too_large");
        }
    }

    private void uploadChunk(NetSession s, StreamChunk m) {
        NetSession.Upload upload = s.uploads.get(m.id());
        if (upload == null) {
            // Chunks still in flight after an upload ended are dropped; a stream that was never granted is a violation.
            if (!recentlyEnded(s, m.id())) violation(s, "chunk of stream " + m.id() + ", which was never granted");
            return;
        }
        if (upload.assembler == null) {
            failUpload(s, m.id(), "invalid");
            violation(s, "chunk of stream " + m.id() + " before it was opened");
            return;
        }
        try {
            upload.assembler.accept(m);
            upload.lastProgress = nanoClock.getAsLong();
            StreamCredit credit = upload.assembler.takeCredit();
            if (credit != null) send(s, credit);
        } catch (ProtocolException e) {
            failUpload(s, m.id(), "invalid");
            violation(s, e.getMessage());
        }
    }

    private void uploadEnd(NetSession s, StreamEnd m) {
        NetSession.Upload upload = s.uploads.get(m.id());
        if (upload == null || upload.assembler == null) return;
        s.uploads.remove(m.id());
        rememberEnded(s, m.id());
        byte[] bytes;
        try {
            bytes = upload.assembler.finish(m);
        } catch (ProtocolException e) {
            send(s, new StreamAbort(m.id(), "invalid"));
            notifyFailed(upload, e.getMessage());
            return;
        }
        try {
            upload.handler.completed(upload.assembler.open(), bytes);
        } catch (RuntimeException e) {
            SculptoryMod.LOG.error("Sculptory: upload handler failed", e);
        }
    }

    private void failUpload(NetSession s, int id, String reason) {
        NetSession.Upload upload = s.uploads.remove(id);
        if (upload == null) return;
        rememberEnded(s, id);
        if (upload.assembler != null) upload.assembler.abort();
        send(s, new StreamAbort(id, reason));
        notifyFailed(upload, reason);
    }

    private static void notifyFailed(NetSession.Upload upload, String reason) {
        try {
            upload.handler.failed(reason);
        } catch (RuntimeException e) {
            SculptoryMod.LOG.error("Sculptory: upload handler failed", e);
        }
    }

    /**
     * Once per server tick: re-checks the permissions every {@link #PERMISSION_RECHECK_TICKS} ticks, sends up to
     * {@link StreamSender#SERVER_BYTES_PER_TICK} of stream data, and aborts streams that hold memory without
     * finishing:
     * <ul>
     *   <li>past their {@link #streamDeadlineNanos deadline} (reason {@code "deadline"}), however slowly they still
     *       move, so tiny credit grants cannot keep a stream alive;</li>
     *   <li>an outbound stream waiting for the client's credit for {@link #STALL_NANOS} (reason
     *       {@code "stalled"}); waiting for its share of the tick budget does not count;</li>
     *   <li>a granted upload whose next chunk (or open) did not arrive for {@link #STALL_NANOS} ({@code "stalled"}).
     *       Failed uploads release what their handler reserved.</li>
     * </ul>
     */
    public void tick(NetSession s) {
        s.floodCountedThisTick = false;
        if (!s.ready()) return;
        if (++s.ticksSincePermissionCheck >= PERMISSION_RECHECK_TICKS) permissionsChanged(s);
        long now = nanoClock.getAsLong();
        if (!s.uploads.isEmpty()) {
            Map<Integer, String> expired = new TreeMap<>();
            s.uploads.forEach((id, upload) -> {
                if (now - upload.deadline > 0) {
                    expired.put(id, "deadline");
                } else if (now - upload.lastProgress > STALL_NANOS) {
                    expired.put(id, "stalled");
                }
            });
            expired.forEach((id, reason) -> failUpload(s, id, reason));
        }
        if (s.outbound.isEmpty()) return;
        long budget = StreamSender.SERVER_BYTES_PER_TICK;
        for (Map.Entry<Integer, StreamSender> entry : new ArrayList<>(s.outbound.entrySet())) {
            int id = entry.getKey();
            StreamSender sender = entry.getValue();
            NetSession.Progress progress = s.outboundProgress.get(id);
            if (progress != null && now - progress.deadline > 0) {
                abortOutbound(s, id, sender, "deadline");
                continue;
            }
            if (budget > 0) {
                for (Message m : sender.poll(budget)) {
                    if (m instanceof StreamChunk chunk) budget -= chunk.length();
                    send(s, (S2C) m);
                }
            }
            if (sender.done()) {
                removeOutbound(s, id);
            } else if (progress != null) {
                long remaining = sender.open().totalBytes() - sender.sentBytes();
                boolean waitingForCredit = sender.credit() < Math.min(StreamAssembler.MIN_CHUNK_BYTES, remaining);
                if (sender.sentBytes() > progress.sent || !waitingForCredit) {
                    // Sending, or held back only by the tick budget: not the client's fault.
                    progress.sent = sender.sentBytes();
                    progress.at = now;
                } else if (now - progress.at > STALL_NANOS) {
                    abortOutbound(s, id, sender, "stalled");
                }
            }
        }
    }

    private void abortOutbound(NetSession s, int id, StreamSender sender, String reason) {
        removeOutbound(s, id);
        StreamAbort abort = sender.abort(reason);
        if (abort != null) send(s, abort);
    }

    /** The connection ended: ends an open stroke and drops everything queued. Idempotent. */
    public void close(NetSession s) {
        sessions.remove(s);
        if (s.stage == Stage.CLOSED) return;
        s.stage = Stage.CLOSED;
        for (int id : new ArrayList<>(s.outbound.keySet())) removeOutbound(s, id);
        s.acks.clear();
        List<NetSession.Upload> uploads = new ArrayList<>(s.uploads.values());
        s.uploads.clear();
        for (NetSession.Upload upload : uploads) notifyFailed(upload, "disconnected");
        s.selections.clear();
        EditMasks.reset(owner(s));
        if (s.strokeOpen) {
            s.strokeOpen = false;
            try {
                edits.endStroke(s.transport.player(), s.strokeId);
            } catch (RuntimeException e) {
                SculptoryMod.LOG.error("Sculptory: endStroke on disconnect failed", e);
            }
        }
    }

    // =================================================================== helpers

    private Limits currentLimits() {
        Limits current = limits.get();
        return current != null ? current : Limits.DEFAULTS;
    }

    private boolean has(NetSession s, Perm perm) {
        try {
            return permissions.has(s.transport.player(), perm);
        } catch (RuntimeException e) {
            SculptoryMod.LOG.error("Sculptory: permission check {} failed", perm.node(), e);
            return false;
        }
    }

    private PermissionMask permissionMask(NetSession s) {
        EnumSet<Perm> granted = EnumSet.noneOf(Perm.class);
        for (Perm perm : Perm.values()) {
            if (has(s, perm)) granted.add(perm);
        }
        return Perm.mask(granted);
    }

    private static int lastIndex(C2S.Dabs m) {
        int last = -1;
        for (Dab dab : m.dabs()) last = Math.max(last, dab.index());
        return last;
    }

    private static OptionalInt requestId(C2S message) {
        return switch (message) {
            case C2S.RunOp m -> OptionalInt.of(m.reqId());
            case C2S.Undo m -> OptionalInt.of(m.reqId());
            case C2S.Redo m -> OptionalInt.of(m.reqId());
            case C2S.Copy m -> OptionalInt.of(m.reqId());
            case C2S.LibraryList m -> OptionalInt.of(m.reqId());
            case C2S.LibraryLoad m -> OptionalInt.of(m.reqId());
            case C2S.SaveAsset m -> OptionalInt.of(m.reqId());
            case C2S.ExportClipboard m -> OptionalInt.of(m.reqId());
            case C2S.UploadBegin m -> OptionalInt.of(m.reqId());
            case C2S.ScatterPreview m -> OptionalInt.of(m.reqId());
            case C2S.LibraryMove m -> OptionalInt.of(m.reqId());
            case C2S.LibraryDelete m -> OptionalInt.of(m.reqId());
            case C2S.LibraryCreateFolder m -> OptionalInt.of(m.reqId());
            case C2S.HistoryOverwrite m -> OptionalInt.of(m.reqId());
            case C2S.PaletteSave m -> OptionalInt.of(m.reqId());
            case C2S.PaletteLoad m -> OptionalInt.of(m.reqId());
            case C2S.SelectionUpload m -> OptionalInt.of(m.reqId());
            case C2S.LibraryAccessGet m -> OptionalInt.of(m.reqId());
            case C2S.LibraryAccessSet m -> OptionalInt.of(m.reqId());
            case C2S.GeneratedUpload m -> OptionalInt.of(m.reqId());
            case C2S.TinkerBlock m -> OptionalInt.of(m.reqId());
            case C2S.TinkerEntity m -> OptionalInt.of(m.reqId());
            case C2S.SetEditMask m -> OptionalInt.of(m.reqId());
            case C2S.Navigate m -> OptionalInt.of(m.reqId());
            default -> OptionalInt.empty();
        };
    }

    private static String playerName(NetSession s) {
        ServerPlayerEntity player = s.transport.player();
        return player == null ? "an unknown player" : player.getGameProfile().getName();
    }

    private static String clip(String text) {
        String value = text == null ? "" : text;
        return value.length() <= 200 ? value : value.substring(0, 200);
    }

    /**
     * Forwards one region-op job's events. Events that arrive before {@link #release} (e.g. a QUEUED progress
     * reported inside {@code EditService.run}) are held so {@code JobAccepted} always goes first.
     */
    private final class JobRelay implements JobListener {
        private final NetSession session;
        private List<S2C> held = new ArrayList<>();
        private boolean discarded;

        JobRelay(NetSession session) {
            this.session = session;
        }

        @Override
        public void progress(UUID job, long done, long total, Phase ph) {
            if (job == null || ph == null) return;
            deliver(new S2C.JobProgress(job, done, total, ph));
        }

        @Override
        public void finished(JobResult r) {
            if (r == null) return;
            deliver(finishedMessage(r));
        }

        private void deliver(S2C message) {
            if (discarded) return;
            if (held != null) {
                if (held.size() < MAX_HELD_EVENTS || message instanceof S2C.JobFinished) held.add(message);
                return;
            }
            forwardJobEvent(session, message);
        }

        void release() {
            List<S2C> pending = held;
            held = null;
            if (pending != null) pending.forEach(event -> forwardJobEvent(session, event));
        }

        void discard() {
            discarded = true;
            held = null;
        }
    }
}
