package dev.sculptory.server.net;

import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.protocol.v2.Features;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.PermissionMask;
import dev.sculptory.protocol.v2.RateLimiter;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.protocol.v2.StreamAssembler;
import dev.sculptory.protocol.v2.StreamSender;
import dev.sculptory.protocol.v2.TokenBucket;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * One Sculptory connection on the server: handshake state, negotiated protocol and features, the
 * permissions and limits last sent, rate limiters, prediction acks, the open stroke and its footprint, streams
 * and the violation count. Created on the player's first frame, so vanilla clients never get one. Server thread
 * only.
 *
 * @param <P> the platform's player type
 */
public final class NetSession<P> {
    public enum Stage {
        /** Only {@code Hello} is accepted (and only it is decoded). */
        AWAITING_HELLO,
        READY,
        /** The client's protocol range does not overlap ours; everything else is ignored. */
        INCOMPATIBLE,
        /** Disconnected or dropped for violations; everything is ignored. */
        CLOSED
    }

    /** Malformed or out-of-order frames tolerated before the player is disconnected. */
    public static final int MAX_VIOLATIONS = 8;
    /** Concurrent server-to-client streams per player. */
    public static final int MAX_OUTBOUND_STREAMS = 8;
    /** Concurrent granted uploads per player. */
    public static final int MAX_UPLOADS = 2;
    /** Unresolvable block states refused per minute before they count as violations. */
    public static final int UNKNOWN_STATES_PER_MINUTE = 20;
    /** Chunks remembered per stroke for resync. */
    public static final int MAX_FOOTPRINT_CHUNKS = 4096;
    /**
     * Frames refused for their rate limit that are tolerated in a burst before each further one counts as a violation
     * (flooding), and how fast that allowance comes back.
     */
    public static final int FLOOD_BURST = 128;
    public static final int FLOOD_PER_SECOND = 32;
    /**
     * Uploads that ended (finished, failed or aborted) remembered per connection, and for how long: chunks the client
     * still had in flight for them are ignored; chunks of a stream never granted are violations.
     */
    public static final int RECENT_UPLOADS = 8;
    public static final long RECENT_UPLOAD_NANOS = 10_000_000_000L;

    final ServerTransport<P> transport;
    final long epoch;
    final RateLimiter limiter;
    final TokenBucket unknownStates;
    /** Rate-limit refusals left before they count as violations (see {@link #FLOOD_BURST}). */
    final TokenBucket floodAllowance;
    /** A flooding violation was counted since the last server tick (at most one per tick). */
    boolean floodCountedThisTick;
    /** Ids of uploads that ended recently, oldest first, with when their late chunks stop being excused. */
    final LinkedHashMap<Integer, Long> endedUploads = new LinkedHashMap<>();
    final PredictionAcks acks;
    Stage stage = Stage.AWAITING_HELLO;
    int protocol;
    /** The build id the client sent in {@code Hello} ({@code ""} before it). */
    String clientBuild = "";
    Features features = Features.NONE;
    PermissionMask permissions = PermissionMask.NONE;
    Limits limits;
    int violations;
    /** Server ticks since the permissions were last re-read (see {@link ServerDispatcher#PERMISSION_RECHECK_TICKS}). */
    int ticksSincePermissionCheck;

    /** The one open stroke (a new StrokeBegin ends the previous one). */
    boolean strokeOpen;
    int strokeId;
    /** How far the open stroke's dabs write from their blocks ({@code BrushSpec.reach}): its footprints' half-size. */
    int strokeReach;
    /** The open stroke's symmetry: its dabs are charged, and their footprints recorded, with every copy. */
    Symmetry strokeSymmetry = Symmetry.NONE;
    int strokeAckedIndex = -1;
    /** The open stroke's last dab the brush lane has written (-1: none), reported in its {@code StrokeStatus}. */
    int strokeAppliedIndex = -1;
    /**
     * The last {@value #MAX_WRITE_REPORTS} Shape strokes begun, open or ended, and their last dab written: their client
     * paces on written dabs (across strokes: a new press waits for the last one's large shapes), so each batch the
     * brush lane finishes for them is reported.
     */
    final java.util.LinkedHashMap<Integer, Integer> writeReports = new java.util.LinkedHashMap<>();
    static final int MAX_WRITE_REPORTS = 8;
    /** Chunks the open (or last) stroke's accepted dabs could have changed, and when that stroke ended. */
    LongOpenHashSet strokeChunks = new LongOpenHashSet();
    long strokeEndedAt = Long.MIN_VALUE;
    /** The stroke before that, kept for late resyncs. */
    LongOpenHashSet previousChunks = new LongOpenHashSet();
    long previousEndedAt = Long.MIN_VALUE;

    /** While an undo/redo is being admitted, its job events wait here so JobAccepted goes first. */
    List<S2C> heldJobEvents;

    final Map<Integer, StreamSender> outbound = new LinkedHashMap<>();
    /** When each outbound stream last sent data (for the stall timeout). */
    final Map<Integer, Progress> outboundProgress = new LinkedHashMap<>();
    /** Previews and exports requested but not produced yet: they count against the outbound stream cap. */
    int pendingStreams;
    /** The stream room reserved by the scatter preview in flight, released when a newer preview replaces it. */
    Runnable scatterReservation;
    /** Payload bytes of the outbound streams (the memory they pin). */
    long outboundBytes;
    /** Granted client-to-server streams; the assembler exists once the client opens the stream. */
    final Map<Integer, Upload> uploads = new LinkedHashMap<>();
    /** Stream ids are allocated by the server for both directions. */
    int nextStreamId = 1;
    /** The cell sets this connection uploaded ({@code SelectionUpload}); ops and copies name them by hash. */
    final SelectionStore selections = new SelectionStore();
    /** Builder mode: when each builder notice key was last sent (nanoTime), so a drag or a held click repeats none. */
    final Map<String, Long> builderNoticeAt = new java.util.HashMap<>();

    /** Bytes sent, when that last counted as progress, and when the stream must have finished. */
    static final class Progress {
        long sent;
        long at;
        final long deadline;

        Progress(long sent, long at, long deadline) {
            this.sent = sent;
            this.at = at;
            this.deadline = deadline;
        }
    }

    /** A granted upload. */
    static final class Upload {
        final long maxBytes;
        final ServerDispatcher.UploadHandler handler;
        StreamAssembler assembler;
        /** Grant, open or last chunk (for the stall timeout). */
        long lastProgress;
        /** When the whole upload must have arrived. */
        final long deadline;

        Upload(long maxBytes, ServerDispatcher.UploadHandler handler, long now, long deadline) {
            this.maxBytes = maxBytes;
            this.handler = handler;
            this.lastProgress = now;
            this.deadline = deadline;
        }
    }

    /** Payload bytes of the outbound streams (tests). */
    public long outboundBytes() {
        return outboundBytes;
    }

    /** Outbound streams (tests). */
    public int outboundStreams() {
        return outbound.size();
    }

    /** Granted uploads not finished (tests). */
    public int openUploads() {
        return uploads.size();
    }

    /** The cell sets this connection uploaded and the server still holds. */
    public SelectionStore selections() {
        return selections;
    }

    NetSession(ServerTransport<P> transport, long epoch, LongSupplier nanoClock) {
        this.transport = Objects.requireNonNull(transport);
        this.epoch = epoch;
        this.limiter = new RateLimiter(nanoClock);
        this.unknownStates = new TokenBucket(UNKNOWN_STATES_PER_MINUTE, TokenBucket.NANOS_PER_MINUTE,
                UNKNOWN_STATES_PER_MINUTE, nanoClock);
        this.floodAllowance = new TokenBucket(FLOOD_PER_SECOND, FLOOD_BURST, nanoClock);
        this.acks = new PredictionAcks(transport::acknowledge);
    }

    public Stage stage() {
        return stage;
    }

    /** The build id the client sent in {@code Hello}, cleaned ({@code ""} before it). */
    public String clientBuild() {
        return clientBuild;
    }

    public boolean ready() {
        return stage == Stage.READY;
    }

    public long epoch() {
        return epoch;
    }

    public int protocol() {
        return protocol;
    }

    public Features features() {
        return features;
    }

    public PermissionMask permissions() {
        return permissions;
    }

    public int violations() {
        return violations;
    }

    public ServerTransport<P> transport() {
        return transport;
    }

    /** Admitted dab batches the brush lane has not reported as applied. */
    public int pendingPredictions() {
        return acks.pending();
    }

    static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    static int chunkX(long key) {
        return (int) (key >> 32);
    }

    static int chunkZ(long key) {
        return (int) key;
    }
}
