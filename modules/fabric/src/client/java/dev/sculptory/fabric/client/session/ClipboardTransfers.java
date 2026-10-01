package dev.sculptory.fabric.client.session;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.palette.BlockPalette;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.schem.SchematicFormat;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.fabric.SculptoryMod;
import dev.sculptory.fabric.library.LibraryPath;
import dev.sculptory.fabric.library.LibraryPathException;
import dev.sculptory.protocol.v2.AssetAccess;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.Features;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.Message;
import dev.sculptory.protocol.v2.ProtocolException;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.IntFunction;
import java.util.regex.Pattern;

/**
 * The M2 half of {@link FabricEditorSession}: clipboard, preview, library (palettes included) and schematic requests,
 * and the streams that carry previews, exported files and uploads.
 *
 * <p><b>Pacing.</b> The server runs at most {@value #MAX_IN_FLIGHT} of a player's M2 requests at once and refuses more
 * with {@code QUEUE_FULL}, so requests wait here in order (at most {@value #MAX_QUEUED}; selection uploads go first,
 * since the session holds later requests behind them) and go out as earlier ones are answered. A preview refusal is a notice without a request id, so at most one preview that has not started
 * streaming is in flight: a {@code preview_refused} notice then belongs to it. The server runs one library write per
 * player (a save, or an M4 rename, move, delete or new folder), so those go out one at a time too; the others wait
 * without holding up the requests behind them.
 *
 * <p><b>Streams.</b> Inbound streams are matched to their request when they open (previews by the clipboard id or
 * asset hash in their meta, exports by {@code meta.reqId}); credit is granted as data arrives
 * ({@link StreamAssembler}); previews are capped at {@value #MAX_PREVIEW_STREAM} bytes and files at
 * {@value #MAX_FILE_STREAM}. A preview is decoded on the {@code decoder} executor and installed in the
 * {@link SessionClipboardCache} on the render thread at the next {@link #tick}. Uploads are sent at
 * {@link StreamSender#CLIENT_BYTES_PER_TICK} per tick within the server's credit.
 *
 * <p><b>Timeouts.</b> A request with no answer, stream data, grant or credit for {@link #STALL_NANOS} fails with
 * {@link Reply.Failure#TIMED_OUT}, and its stream is aborted; an inbound stream also has an overall deadline
 * ({@link #streamDeadlineNanos}). A preview that timed out leaves a tombstone, so its late answer is not taken for
 * the next preview's.
 *
 * <p><b>A hostile server</b> cannot make this client hold what it did not ask for: streams that answer no request,
 * upload-kind or empty streams are aborted; unsolicited or impossible {@code ClipboardReady} messages, invalid
 * listing entries (bad paths, non-hex hashes) and invalid upload grants are dropped; previews decode within
 * {@link PreviewDecoder.Limits} and must agree with their clipboard's size, anchor and cell count.
 *
 * <p><b>Selections</b> (regions): a copy or op of a cell set (magic select) needs the server to hold the set. It is
 * uploaded once per connection ({@link #selection}: {@code SelectionUpload}, its bytes on a {@code SELECTION_UPLOAD}
 * stream, {@code SelectionReady}), as a request paced like the others and listed as an upload; the copy or op then names
 * it as a {@code Region.Uploaded}. When the server answers {@code SELECTION_NOT_LOADED} (it keeps a player's last few
 * sets) the set is uploaded again and the request retried once.
 *
 * <p><b>Toasts</b> (see {@link Reply}): a {@code request_refused} notice following a refusal is relayed in plain
 * English with its detail; a refusal without one gets the generic message after {@link #REFUSAL_NOTICE_WAIT_NANOS};
 * upload errors and failures are toasted here. A quiet request (the upload of a selection an op needs) toasts
 * nothing: the op's caller reports its outcome.
 *
 * <p>Render thread only, apart from the decoder tasks.
 */
final class ClipboardTransfers {
    /** Matches the server's stall limit for streams and uploads. */
    static final long STALL_NANOS = 30_000_000_000L;
    /** The server's per-player request slots ({@code RequestSlots.MAX_TASKS}). */
    static final int MAX_IN_FLIGHT = 2;
    static final int MAX_QUEUED = 16;
    static final long MAX_PREVIEW_STREAM = 32L << 20;
    static final long MAX_FILE_STREAM = 64L << 20;
    static final int MAX_INBOUND_STREAMS = 2;
    /** A preview still decoding after this is given up (its late result is dropped). */
    static final long DECODE_TIMEOUT_NANOS = 60_000_000_000L;
    /** Every inbound stream must finish within {@code max(60 s, size / 256 KiB/s)}, as the server requires. */
    static final long MIN_STREAM_DEADLINE_NANOS = 60_000_000_000L;
    static final long MIN_STREAM_BYTES_PER_SECOND = 256L << 10;
    /** The largest clipboard side a server may announce ({@code bspv1} and the schematic format). */
    static final int MAX_SIDE = 65_535;
    /** Anchors lie within ±30,000,000 on every axis (as the server's schematic limits require). */
    static final int MAX_ANCHOR = 30_000_000;
    /** A lowercase hexadecimal SHA-256, as {@link SourceRef.Asset} requires. */
    static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    /** How long a refusal waits for the server's {@code request_refused} notice before a generic toast. */
    static final long REFUSAL_NOTICE_WAIT_NANOS = 250_000_000L;

    static final String REQUEST_REFUSED = "sculptory.notice.request_refused";
    static final String PREVIEW_REFUSED = "sculptory.notice.preview_refused";
    static final String UPLOAD_ERROR = "sculptory.transfer.upload_error";

    /** What the helper needs from the session. */
    interface Link {
        long now();

        int nextReqId();

        /** Encodes and sends; returns why it failed (prefixed with the reason name), or {@code null}. */
        String send(C2S message);

        void notice(Notice notice);

        Permissions permissions();

        /** The negotiated features ({@code Welcome}). */
        Features features();

        StateSpace states();

        /** The local player's id as the server knows it, or {@code null} (palettes: where a redirected save lands). */
        default UUID player() {
            return null;
        }

        /** A cut's erase job was admitted: follow it like any job this client started. */
        void cutAccepted(S2C.JobAccepted accepted, String label);

        /** The player starts an edit (a cut): queued undo/redo presses no longer apply. */
        void newEdit();

        /** Every completed inbound stream, requested or not. */
        void streamCompleted(FabricEditorSession.ReceivedStream stream);

        /**
         * Whether the server refused request {@code reqId} only because the player's brush stroke is still being
         * applied (its {@code stroke_pending} notice came); answers once per request.
         */
        default boolean strokePending(int reqId) {
            return false;
        }

        /**
         * Sends a copy again a moment later ({@code send}), in its place in the caller's order ({@code order}, from
         * {@link Order#forStroke}); {@code abandon} ends it instead when that order is given up.
         */
        default void sendAgain(Order order, Runnable send, Runnable abandon) {
            abandon.run();
        }
    }

    /**
     * A copy's place in the caller's request order ({@code FabricEditorSession}): the requests the caller issued after
     * it wait until it has gone out.
     */
    interface Order {
        /** No order: always live, nothing waits behind it, and a retry is always allowed. */
        Order NONE = new Order() {
            @Override
            public boolean live() {
                return true;
            }

            @Override
            public void settled() {
            }

            @Override
            public boolean mayRetry() {
                return true;
            }

            @Override
            public Order again() {
                return this;
            }
        };

        /** Whether it may still go out (false once the caller gave up on the order it was in). */
        boolean live();

        /** It went out, or ended without going out; later calls do nothing. */
        void settled();

        /** After {@code SELECTION_NOT_LOADED}: whether it may be sent again (nothing went out after it). */
        boolean mayRetry();

        /** The order of its retry: what the caller issues meanwhile waits behind it again. */
        Order again();

        /**
         * After a refusal for the player's brush stroke still being applied: whether the copy is sent again rather than
         * the refusal reported (the caller retries for a while).
         */
        default boolean retriesForStroke() {
            return false;
        }

        /** The order of sending it again after such a refusal: what the caller issues meanwhile waits behind it. */
        default Order forStroke() {
            return this;
        }
    }

    /** The request kinds; {@code label} names them in toasts. */
    enum RequestKind {
        COPY("Copy", Features.CLIPBOARD),
        CUT("Cut", Features.CLIPBOARD),
        PREVIEW("Preview", Features.CLIPBOARD),
        LIST("Library", Features.LIBRARY),
        LOAD("Load", Features.LIBRARY),
        SAVE("Save", Features.LIBRARY),
        MANAGE("Library change", Features.LIBRARY),
        EXPORT("Export", Features.SCHEMATICS),
        UPLOAD("Upload", Features.SCHEMATICS),
        /** Palettes: a save is a library write, answered like a change ({@code LibraryChanged}). */
        PALETTE_SAVE("Save palette", Features.LIBRARY),
        PALETTE_LOAD("Load palette", Features.LIBRARY),
        /** Per-asset access: who may load an entry ({@code LibraryAccessGet}); a change is a {@link #MANAGE}. */
        ACCESS("Access", Features.LIBRARY),
        /** Regions: a cell set sent for the ops and copies that name it ({@code SelectionUpload}). */
        SELECTION("Selection upload", Features.REGION_OPS),
        /** Generators: a sparse clipboard made on this client ({@code GeneratedUpload}), answered like an upload. */
        GENERATED("Generate", Features.CLIPBOARD);

        final String label;
        final String feature;

        RequestKind(String label, String feature) {
            this.label = label;
            this.feature = feature;
        }

        /** Requests that make a clipboard, whose size limit a TOO_LARGE refusal quotes. */
        boolean makesClipboard() {
            return this == COPY || this == CUT || this == LOAD || this == UPLOAD || this == GENERATED;
        }

        /** Uploads that end with {@code UploadResult}: a file, or a generated clipboard. */
        boolean uploadsClipboard() {
            return this == UPLOAD || this == GENERATED;
        }

        /** Library writes: the server runs one per player at a time (its save slot), so this client sends one at a time. */
        boolean writes() {
            return this == SAVE || this == MANAGE || this == PALETTE_SAVE;
        }

        /** Requests that stream bytes to the server once granted ({@code UploadGrant}). */
        boolean uploads() {
            return this == UPLOAD || this == SELECTION || this == GENERATED;
        }

        Transfer.Kind transferKind() {
            return switch (this) {
                case PREVIEW -> Transfer.Kind.PREVIEW;
                case EXPORT -> Transfer.Kind.EXPORT;
                case UPLOAD, SELECTION, GENERATED -> Transfer.Kind.UPLOAD;
                default -> null;
            };
        }

        /** The stream kind an upload sends its bytes on. */
        StreamKind streamKind() {
            return switch (this) {
                case SELECTION -> StreamKind.SELECTION_UPLOAD;
                case GENERATED -> StreamKind.GENERATED_UPLOAD;
                default -> StreamKind.SCHEM_UPLOAD;
            };
        }
    }

    /** One request, queued, in flight or (previews) decoding. Doubles as the caller's {@link Transfer}. */
    private final class Request<T> implements Transfer<T> {
        final RequestKind kind;
        final CompletableFuture<Reply<T>> future = new CompletableFuture<>();
        final IntFunction<C2S> message;
        String label;
        int reqId;
        long lastActivity;
        /** Its future already completed (cancelled); kept only until the server's answer or a timeout. */
        boolean abandoned;
        /** A cut's JobAccepted arrived. */
        boolean jobSeen;
        // Previews
        SourceRef source;
        boolean decoding;
        long decodeStarted;
        // Streams (previews, exports: inbound; uploads: outbound)
        int streamId = -1;
        long total;
        long done;
        /** When an inbound stream must have finished, however slowly it still moves (0 before it opens). */
        long deadline;
        // Uploads
        byte[] payload;
        StreamSender sender;
        ClipboardCache.Entry uploaded;
        // Exports
        UUID clipboardId;
        // Library changes: what was asked, which the answer must confirm
        LibraryChange change;
        // Selection uploads: the set's hash, which SelectionReady must confirm
        Sha256 selection;
        /** Its refusals and failures are not toasted: its caller reports them (an op on the selection). */
        boolean quiet;
        /** A copy of an uploaded selection: a SELECTION_NOT_LOADED refusal may be retried (then it is not toasted). */
        boolean retriesSelection;
        /** It was refused SELECTION_NOT_LOADED and its order allows sending it again: the caller retries it. */
        boolean retrying;
        /** Refused for the player's stroke still being applied, and to be sent again ({@link Order#retriesForStroke}). */
        boolean strokeRetrying;
        /** Its place in the caller's request order: settled when it goes out or completes, whichever comes first. */
        Order order = Order.NONE;

        Request(RequestKind kind, String label, IntFunction<C2S> message) {
            this.kind = kind;
            this.label = label;
            this.message = message;
        }

        @Override
        public Transfer.Kind kind() {
            return kind.transferKind();
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public long doneBytes() {
            return done;
        }

        @Override
        public long totalBytes() {
            return total;
        }

        @Override
        public boolean finished() {
            return future.isDone();
        }

        @Override
        public CompletionStage<Reply<T>> result() {
            return future;
        }

        @Override
        public void cancel() {
            ClipboardTransfers.this.cancel(this);
        }

        boolean streaming() {
            return streamId >= 0;
        }

        @SuppressWarnings("unchecked")
        void complete(Reply<?> reply) {
            future.complete((Reply<T>) reply);
            order.settled();
        }
    }

    /** An inbound stream and the request it answers (null when nobody asked for it). */
    private record Inbound(StreamAssembler assembler, Request<?> request) {}

    /** A preview decoded off-thread, waiting for the render thread; {@code generation} is the connection's. */
    private record Decoded(int generation, Request<?> request, ClipboardCache.Preview preview, String error) {}

    /** A refusal whose {@code request_refused} notice may still arrive; a quiet one's is swallowed. */
    private record Refusal(RequestKind kind, RejectReason reason, long at, boolean quiet) {}

    private final Link link;
    private final SessionClipboardCache cache;
    private final Executor decoder;
    private final Executor encoder;
    private final ArrayDeque<Request<?>> queue = new ArrayDeque<>();
    private final List<Request<?>> inFlight = new ArrayList<>();
    private final Map<Integer, Request<?>> byReqId = new HashMap<>();
    private final Map<Integer, Request<?>> uploadsByStream = new LinkedHashMap<>();
    private final Map<Integer, Inbound> inbound = new HashMap<>();
    private final List<Request<?>> decodingNow = new ArrayList<>();
    private final Queue<Decoded> decoded = new ConcurrentLinkedQueue<>();
    private final ArrayDeque<Refusal> refusals = new ArrayDeque<>();
    /** Cuts answered before their JobAccepted (not expected, but their erase job is still followed). */
    private final Set<Integer> cutsAwaitingJob = new HashSet<>();
    /**
     * Previews that timed out before their stream opened: a late {@code preview_refused} (or stream) for one of them
     * must not be taken for the next preview's. Each expires after {@link #STALL_NANOS}.
     */
    private final ArrayDeque<Tombstone> tombstones = new ArrayDeque<>();
    /** Bumped on every reset, so a decode that finishes after a disconnect is dropped (handles may differ). */
    private int generation;
    private final LibraryChanges changes = new LibraryChanges();
    private final PreviewDecoder.Limits previewLimits;
    /** Regions: the cell sets the server confirmed holding for this connection ({@code SelectionReady}). */
    private final Set<Sha256> heldSelections = new HashSet<>();
    /** Regions: the upload of each set on its way, so a second op on it waits for the same one. */
    private final Map<Sha256, Request<Sha256>> selectionUploads = new HashMap<>();
    /** Regions: the latest sets encoded, newest first (a retry sends the same bytes). */
    private final ArrayDeque<Encoding> encodings = new ArrayDeque<>();
    /** Regions: encodings finished off the render thread, taken at the next {@link #tick}. */
    private final Queue<Encoded> encoded = new ConcurrentLinkedQueue<>();
    /** Regions: the requests whose set is being encoded (render thread), ended at once by a {@link #reset}. */
    private final List<CompletableFuture<Reply<Sha256>>> encodingNow = new ArrayList<>();

    /** Encodings kept for reuse. */
    static final int KEPT_ENCODINGS = 2;

    /** A set's hash and {@code CellSet.encode()} bytes. */
    private record Encoding(CellSet cells, Sha256 hash, byte[] bytes) {}

    /** An encoding done off the render thread for {@code result} ({@code encoding} null and {@code error} on failure). */
    private record Encoded(int generation, CellSet cells, Encoding encoding, boolean quiet,
                           CompletableFuture<Reply<Sha256>> result, String error) {}

    /** A preview that gave up waiting; {@code until} is when it is forgotten. */
    private record Tombstone(SourceRef source, long until) {}

    ClipboardTransfers(Link link, SessionClipboardCache cache, Executor decoder) {
        this(link, cache, decoder, PreviewDecoder.Limits.DEFAULT);
    }

    ClipboardTransfers(Link link, SessionClipboardCache cache, Executor decoder, PreviewDecoder.Limits previewLimits) {
        this(link, cache, decoder, decoder, previewLimits);
    }

    /** @param encoder encodes selections for upload (see {@link #selection}) */
    ClipboardTransfers(Link link, SessionClipboardCache cache, Executor decoder, Executor encoder,
                       PreviewDecoder.Limits previewLimits) {
        this.link = Objects.requireNonNull(link);
        this.cache = Objects.requireNonNull(cache);
        this.decoder = Objects.requireNonNull(decoder);
        this.encoder = Objects.requireNonNull(encoder);
        this.previewLimits = Objects.requireNonNull(previewLimits);
    }

    // =================================================================== requests

    CompletionStage<Reply<ClipboardCache.Entry>> copy(Region region, BlockPos origin, boolean cut, CellMask mask,
                                                      EntityFilter entities) {
        return copy(region, origin, cut, mask, entities, Order.NONE);
    }

    /**
     * A copy or cut, keeping its place in the caller's request order: {@code order} settles once the copy's request goes
     * out (a cell set's after its upload), or once it ends without going out; a request whose order is no longer
     * {@link Order#live live} when its turn comes is not sent, and fails as timed out.
     */
    CompletionStage<Reply<ClipboardCache.Entry>> copy(Region region, BlockPos origin, boolean cut, CellMask mask,
                                                      EntityFilter entities, Order order) {
        Objects.requireNonNull(region);
        Objects.requireNonNull(origin);
        Objects.requireNonNull(mask);
        Objects.requireNonNull(entities);
        Objects.requireNonNull(order);
        if (region instanceof Region.Cells cells) {
            CompletableFuture<Reply<ClipboardCache.Entry>> result = new CompletableFuture<>();
            copySelection(cells.cells(), origin, cut, mask, entities, true, result, order);
            return result;
        }
        Request<ClipboardCache.Entry> request = new Request<>(cut ? RequestKind.CUT : RequestKind.COPY,
                cut ? "Cut" : "Copy", reqId -> new C2S.Copy(reqId, region, origin, cut, mask, entities));
        request.order = order;
        CompletableFuture<Reply<ClipboardCache.Entry>> result = new CompletableFuture<>();
        enqueue(request).future.thenAccept(reply -> {
            if (request.strokeRetrying) {
                Order next = order.forStroke();
                link.sendAgain(next, () -> copy(region, origin, cut, mask, entities, next).thenAccept(result::complete),
                        () -> result.complete(Reply.failed(Reply.Failure.TIMED_OUT, FabricEditorSession.GAVE_UP)));
            } else {
                result.complete(reply);
            }
        });
        return result;
    }

    /**
     * A copy of a cell set: the set is uploaded unless the server holds it ({@link #selection}), then the copy names it
     * as a {@code Region.Uploaded}; a {@code SELECTION_NOT_LOADED} refusal (the server dropped it) uploads it again and
     * retries once, if the order allows it ({@link Order#mayRetry}; else the refusal is the copy's answer).
     */
    private void copySelection(CellSet cells, BlockPos origin, boolean cut, CellMask mask, EntityFilter entities,
                               boolean retry, CompletableFuture<Reply<ClipboardCache.Entry>> result, Order order) {
        selection(cells, false).thenAccept(uploaded -> {
            switch (uploaded) {
                case Reply.Ok<Sha256> ok -> {
                    if (!order.live()) {
                        order.settled();
                        result.complete(Reply.failed(Reply.Failure.TIMED_OUT, FabricEditorSession.GAVE_UP));
                        break;
                    }
                    Region reference = new Region.Uploaded(ok.value(), cells.bounds(), cells.size());
                    Request<ClipboardCache.Entry> request = new Request<>(cut ? RequestKind.CUT : RequestKind.COPY,
                            cut ? "Cut" : "Copy", reqId -> new C2S.Copy(reqId, reference, origin, cut, mask, entities));
                    request.retriesSelection = retry;
                    request.order = order;
                    enqueue(request).future.thenAccept(reply -> {
                        if (request.retrying) {
                            heldSelections.remove(ok.value());
                            copySelection(cells, origin, cut, mask, entities, false, result, order.again());
                        } else if (request.strokeRetrying) {
                            Order next = order.forStroke();
                            link.sendAgain(next, () -> copySelection(cells, origin, cut, mask, entities, retry, result, next),
                                    () -> result.complete(Reply.failed(Reply.Failure.TIMED_OUT, FabricEditorSession.GAVE_UP)));
                        } else {
                            result.complete(reply);
                        }
                    });
                }
                case Reply.Refused<Sha256> refused -> {
                    order.settled();
                    result.complete(Reply.refused(refused.reason(), refused.detail()));
                }
                case Reply.Failed<Sha256> failed -> {
                    order.settled();
                    result.complete(Reply.failed(failed.failure(), failed.detail()));
                }
            }
        }).whenComplete((ignored, error) -> {
            if (error != null) order.settled();
        });
    }

    /**
     * Regions: makes sure the server holds {@code cells} for this connection, answering its hash: at once when it
     * confirmed holding it before, else after uploading it ({@code SelectionUpload}, then its bytes on a
     * {@code SELECTION_UPLOAD} stream, until {@code SelectionReady}); a second call while that upload is on its way waits
     * for the same one. The upload is paced and timed out like the other requests and listed in {@link #transfers()}. A
     * {@code quiet} upload raises no toast for its refusals and failures: the caller reports them.
     *
     * <p>A set over the server's selection caps ({@code Limits.maxSelectionCells}, {@code maxSelectionSections}) is
     * refused here, before anything is encoded. Encoding and hashing (a large set's body is tens of megabytes) run on
     * the {@code encoder} executor, off the render thread (the game gives it a low-priority thread of its own); the
     * result comes back at the next {@link #tick}, and the last {@value #KEPT_ENCODINGS} encodings are kept, so a retry
     * or the next op on the same set does not encode again. A selection upload starts before the other queued requests
     * (requests the caller holds wait behind it).
     */
    CompletionStage<Reply<Sha256>> selection(CellSet cells, boolean quiet) {
        Objects.requireNonNull(cells);
        if (cells.isEmpty()) {
            return CompletableFuture.completedFuture(Reply.refused(RejectReason.INVALID, "an empty selection"));
        }
        Limits limits = link.permissions().limits();
        String over = cells.size() > limits.maxSelectionCells()
                ? cells.size() + " > " + limits.maxSelectionCells() + " blocks in a selection"
                : sectionsOver(cells, limits.maxSelectionSections());
        if (over != null) {
            Request<Sha256> refused = new Request<>(RequestKind.SELECTION, "selection", null);
            refused.quiet = quiet;
            refuseLocally(refused, RejectReason.TOO_LARGE, over);
            return refused.future;
        }
        Encoding known = encoding(cells);
        if (known != null) return selection(cells, known, quiet);
        CompletableFuture<Reply<Sha256>> result = new CompletableFuture<>();
        encodingNow.add(result);
        int connection = generation;
        Runnable task = () -> {
            try {
                byte[] bytes = cells.encode();
                encoded.add(new Encoded(connection, cells, new Encoding(cells, cells.hash(), bytes), quiet, result, null));
            } catch (Throwable e) {
                String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                encoded.add(new Encoded(connection, cells, null, quiet, result, error));
            }
        };
        try {
            encoder.execute(task);
        } catch (RejectedExecutionException e) {
            task.run();
        }
        drainEncoded();
        return result;
    }

    /** Why a set touches more sections than the server takes, or null. */
    private static String sectionsOver(CellSet cells, int max) {
        int sections = cells.sectionKeys().length;
        return sections > max ? "a selection touching " + sections + " > " + max + " sections" : null;
    }

    /** {@link #selection(CellSet, boolean)} once the set is encoded. */
    private CompletionStage<Reply<Sha256>> selection(CellSet cells, Encoding encoding, boolean quiet) {
        Sha256 hash = encoding.hash();
        if (heldSelections.contains(hash)) return CompletableFuture.completedFuture(Reply.ok(hash));
        Request<Sha256> pending = selectionUploads.get(hash);
        if (pending != null && !pending.finished()) {
            if (!quiet) pending.quiet = false;
            return pending.future;
        }
        byte[] bytes = encoding.bytes();
        Request<Sha256> request = new Request<>(RequestKind.SELECTION, "selection",
                reqId -> new C2S.SelectionUpload(reqId, hash, cells.bounds(), cells.size(), bytes.length));
        request.selection = hash;
        request.quiet = quiet;
        request.total = bytes.length;
        long max = Math.min(link.permissions().limits().maxUploadBytes(), Transfer.MAX_UPLOAD_BYTES);
        if (bytes.length > max) {
            refuseLocally(request, RejectReason.TOO_LARGE, bytes.length + " > " + max + " bytes");
            return request.future;
        }
        request.payload = bytes;
        selectionUploads.put(hash, request);
        request.future.whenComplete((reply, error) -> selectionUploads.remove(hash, request));
        return enqueue(request).future;
    }

    /** The kept encoding of this very set (by identity: comparing sets could hash tens of megabytes), or null. */
    private Encoding encoding(CellSet cells) {
        for (Encoding known : encodings) {
            if (known.cells() == cells) return known;
        }
        return null;
    }

    /** Takes the encodings finished off the render thread and continues their uploads (render thread). */
    private void drainEncoded() {
        Encoded next;
        while ((next = encoded.poll()) != null) {
            CompletableFuture<Reply<Sha256>> result = next.result();
            encodingNow.remove(result);
            if (next.generation() != generation) {
                result.complete(Reply.failed(Reply.Failure.DISCONNECTED, "disconnected"));
                continue;
            }
            if (next.encoding() == null) {
                SculptoryMod.LOG.warn("Sculptory: cannot encode a selection: {}", next.error());
                if (!next.quiet()) link.notice(Notice.of(Notice.Level.WARNING, Reply.Failure.CORRUPT.noticeKey(), "Selection"));
                result.complete(Reply.failed(Reply.Failure.CORRUPT, next.error()));
                continue;
            }
            encodings.addFirst(next.encoding());
            while (encodings.size() > KEPT_ENCODINGS) encodings.removeLast();
            selection(next.cells(), next.encoding(), next.quiet()).whenComplete((reply, error) -> result.complete(
                    reply != null ? reply : Reply.failed(Reply.Failure.ABORTED, String.valueOf(error))));
        }
    }

    /** Regions: the server confirmed holding the set it answers ({@code SelectionReady}). */
    void selectionReady(S2C.SelectionReady m) {
        Request<?> request = byReqId.get(m.reqId());
        if (request == null || request.kind != RequestKind.SELECTION) {
            SculptoryMod.LOG.warn("Sculptory: ignoring a selection nobody uploaded (request {})", m.reqId());
            return;
        }
        release(request);
        if (!m.hash().equals(request.selection)) {
            finish(request, Reply.failed(Reply.Failure.CORRUPT, "the server confirmed another selection"));
            return;
        }
        heldSelections.add(m.hash());
        finish(request, Reply.ok(m.hash()));
    }

    /** Regions: the server no longer holds the set ({@code SELECTION_NOT_LOADED}): the next use uploads it again. */
    void forgetSelection(Sha256 hash) {
        heldSelections.remove(hash);
    }

    /** Regions: whether the server confirmed holding the set on this connection. */
    boolean holdsSelection(Sha256 hash) {
        return heldSelections.contains(hash);
    }

    Transfer<ClipboardCache.Preview> preview(SourceRef source) {
        Objects.requireNonNull(source);
        java.util.Optional<ClipboardCache.Preview> cached = cache.preview(source);
        if (cached.isPresent()) {
            Request<ClipboardCache.Preview> done = new Request<>(RequestKind.PREVIEW, previewLabel(source), null);
            done.source = source;
            done.total = done.done = cached.get().bytes();
            done.complete(Reply.ok(cached.get()));
            return done;
        }
        for (Request<?> pending : pendingRequests()) {
            if (pending.kind == RequestKind.PREVIEW && !pending.abandoned && source.equals(pending.source)) {
                @SuppressWarnings("unchecked")
                Request<ClipboardCache.Preview> same = (Request<ClipboardCache.Preview>) pending;
                return same;
            }
        }
        Request<ClipboardCache.Preview> request = new Request<>(RequestKind.PREVIEW, previewLabel(source),
                reqId -> new C2S.PreviewRequest(source));
        request.source = source;
        return enqueue(request);
    }

    CompletionStage<Reply<LibraryFolder>> list(String folder) {
        Objects.requireNonNull(folder);
        return enqueue(new Request<LibraryFolder>(RequestKind.LIST, folder, reqId -> new C2S.LibraryList(reqId, folder))).future;
    }

    CompletionStage<Reply<ClipboardCache.Entry>> load(String path) {
        Objects.requireNonNull(path);
        return enqueue(new Request<ClipboardCache.Entry>(RequestKind.LOAD, path, reqId -> new C2S.LibraryLoad(reqId, path))).future;
    }

    CompletionStage<Reply<SavedAsset>> save(UUID clipboardId, String path) {
        Objects.requireNonNull(clipboardId);
        Objects.requireNonNull(path);
        return enqueue(new Request<SavedAsset>(RequestKind.SAVE, path, reqId -> new C2S.SaveAsset(reqId, clipboardId, path))).future;
    }

    CompletionStage<Reply<LibraryChange>> move(String from, String to, boolean folder) {
        return manage(new LibraryChange(folder, Objects.requireNonNull(from), Objects.requireNonNull(to)),
                reqId -> new C2S.LibraryMove(reqId, folder, from, to));
    }

    CompletionStage<Reply<LibraryChange>> delete(String path, boolean folder) {
        return manage(new LibraryChange(folder, Objects.requireNonNull(path), ""),
                reqId -> new C2S.LibraryDelete(reqId, folder, path));
    }

    CompletionStage<Reply<LibraryChange>> createFolder(String path) {
        return manage(new LibraryChange(true, "", Objects.requireNonNull(path)),
                reqId -> new C2S.LibraryCreateFolder(reqId, path));
    }

    private CompletionStage<Reply<LibraryChange>> manage(LibraryChange change, IntFunction<C2S> message) {
        Request<LibraryChange> request = new Request<>(RequestKind.MANAGE,
                change.from().isEmpty() ? change.to() : change.from(), message);
        request.change = change;
        return enqueue(request).future;
    }

    /** The library changes answered or pushed so far (M4). */
    LibraryChanges changes() {
        return changes;
    }

    /** Per-asset access: who may load the entry at {@code path}. */
    CompletionStage<Reply<AssetAccess>> access(String path) {
        Objects.requireNonNull(path);
        return enqueue(new Request<AssetAccess>(RequestKind.ACCESS, path,
                reqId -> new C2S.LibraryAccessGet(reqId, path))).future;
    }

    /** Per-asset access: sets who may load the entry at {@code path}; answered as the entry changed in place. */
    CompletionStage<Reply<LibraryChange>> setAccess(String path, AssetAccess access) {
        Objects.requireNonNull(access);
        return manage(new LibraryChange(false, Objects.requireNonNull(path), path),
                reqId -> new C2S.LibraryAccessSet(reqId, path, access));
    }

    /** Palettes: saves a palette at {@code path}; the answer is the change the server made (the path it wrote). */
    CompletionStage<Reply<LibraryChange>> savePalette(String path, BlockPalette palette) {
        Objects.requireNonNull(path);
        Objects.requireNonNull(palette);
        return enqueue(new Request<LibraryChange>(RequestKind.PALETTE_SAVE, path,
                reqId -> new C2S.PaletteSave(reqId, path, palette))).future;
    }

    /** Palettes: loads the palette at {@code path}. */
    CompletionStage<Reply<LoadedPalette>> loadPalette(String path) {
        Objects.requireNonNull(path);
        return enqueue(new Request<LoadedPalette>(RequestKind.PALETTE_LOAD, path,
                reqId -> new C2S.PaletteLoad(reqId, path))).future;
    }

    Transfer<ExportedFile> export(UUID clipboardId, SchematicFormat format) {
        Objects.requireNonNull(clipboardId);
        Objects.requireNonNull(format);
        Request<ExportedFile> request = new Request<>(RequestKind.EXPORT, "clipboard" + format.extension(),
                reqId -> new C2S.ExportClipboard(reqId, clipboardId, format));
        request.clipboardId = clipboardId;
        return enqueue(request);
    }

    Transfer<ClipboardCache.Entry> upload(String fileName, byte[] bytes) {
        Objects.requireNonNull(fileName);
        Objects.requireNonNull(bytes);
        Request<ClipboardCache.Entry> request = new Request<>(RequestKind.UPLOAD, fileName,
                reqId -> new C2S.UploadBegin(reqId, fileName, bytes.length));
        request.total = bytes.length;
        long max = Math.min(link.permissions().limits().maxUploadBytes(), Transfer.MAX_UPLOAD_BYTES);
        if (bytes.length == 0) {
            refuseLocally(request, RejectReason.INVALID, "empty file");
            return request;
        }
        if (bytes.length > max) {
            refuseLocally(request, RejectReason.TOO_LARGE, bytes.length + " > " + max + " bytes");
            return request;
        }
        request.payload = bytes;
        return enqueue(request);
    }

    /**
     * Generators: uploads a sparse clipboard payload ({@code GeneratedUpload}, its bytes on a {@code GENERATED_UPLOAD}
     * stream), answered like a file upload ({@code ClipboardReady} then {@code UploadResult}). Refused here over the
     * server's {@code maxUploadBytes}.
     */
    Transfer<ClipboardCache.Entry> uploadGenerated(Box bounds, long cells, byte[] bytes) {
        Objects.requireNonNull(bounds);
        Objects.requireNonNull(bytes);
        Request<ClipboardCache.Entry> request = new Request<>(RequestKind.GENERATED, "generated",
                reqId -> new C2S.GeneratedUpload(reqId, bounds, cells, bytes.length));
        request.total = bytes.length;
        long max = Math.min(link.permissions().limits().maxUploadBytes(), Transfer.MAX_UPLOAD_BYTES);
        if (bytes.length == 0) {
            refuseLocally(request, RejectReason.INVALID, "nothing generated");
            return request;
        }
        if (bytes.length > max) {
            refuseLocally(request, RejectReason.TOO_LARGE, bytes.length + " > " + max + " bytes");
            return request;
        }
        if (cells < 1 || cells > bounds.volume()) {
            refuseLocally(request, RejectReason.INVALID, cells + " cells in " + bounds);
            return request;
        }
        request.payload = bytes;
        return enqueue(request);
    }

    /** The active previews, exports and uploads, oldest first. */
    List<Transfer<?>> transfers() {
        List<Transfer<?>> list = new ArrayList<>();
        for (Request<?> request : pendingRequests()) {
            if (request.kind.transferKind() != null && !request.finished()) list.add(request);
        }
        return list;
    }

    private List<Request<?>> pendingRequests() {
        List<Request<?>> all = new ArrayList<>(inFlight);
        all.addAll(decodingNow);
        all.addAll(queue);
        return all;
    }

    private <T> Request<T> enqueue(Request<T> request) {
        if (!link.features().has(request.kind.feature)) {
            refuseLocally(request, RejectReason.DISABLED, "not offered by this server");
            return request;
        }
        if (queue.size() >= MAX_QUEUED) {
            finish(request, Reply.failed(Reply.Failure.BUSY, queue.size() + " requests waiting"));
            return request;
        }
        queue.add(request);
        pump();
        return request;
    }

    /**
     * Starts queued requests while slots are free: selection uploads first (the caller's later requests wait behind
     * them), then the others, oldest first. A preview that must wait for another preview's stream stays queued without
     * holding up the requests behind it.
     */
    private void pump() {
        while (inFlight.size() < MAX_IN_FLIGHT) {
            Request<?> next = nextToStart();
            if (next == null) return;
            queue.remove(next);
            // start() may complete requests and change the queue: the next pick looks again from the front.
            start(next);
        }
    }

    /** The queued request to start next ({@link #pump}), or null when none may start now. */
    private Request<?> nextToStart() {
        Request<?> first = null;
        for (Request<?> request : queue) {
            if (request.kind == RequestKind.PREVIEW && unmatchedPreview() != null) continue;
            if (request.kind.writes() && writeInFlight()) continue;
            if (request.kind == RequestKind.SELECTION) return request;
            if (first == null) first = request;
        }
        return first;
    }

    private void start(Request<?> request) {
        if (request.abandoned) return;
        if (!request.order.live()) {
            // The caller gave up on the order it was in (and said so): sending it now would put it out of order.
            request.quiet = true;
            finish(request, Reply.failed(Reply.Failure.TIMED_OUT, FabricEditorSession.GAVE_UP));
            return;
        }
        request.reqId = request.kind == RequestKind.PREVIEW ? 0 : link.nextReqId();
        if (request.kind == RequestKind.CUT) link.newEdit();
        String failure = link.send(request.message.apply(request.reqId));
        if (failure != null) {
            RejectReason reason = failure.startsWith("TOO_LARGE") ? RejectReason.TOO_LARGE : RejectReason.INVALID;
            refuseLocally(request, reason, failure);
            return;
        }
        request.order.settled(); // it went out: the requests the caller holds behind it may follow
        request.lastActivity = link.now();
        inFlight.add(request);
        if (request.reqId != 0) byReqId.put(request.reqId, request);
    }

    /** Whether a library write (a save or a change) is in flight: the server holds one per player at a time. */
    private boolean writeInFlight() {
        for (Request<?> request : inFlight) {
            if (request.kind.writes()) return true;
        }
        return false;
    }

    /** The preview in flight whose stream has not opened yet: the one a {@code preview_refused} notice is about. */
    private Request<?> unmatchedPreview() {
        for (Request<?> request : inFlight) {
            if (request.kind == RequestKind.PREVIEW && !request.streaming()) return request;
        }
        return null;
    }

    private void cancel(Request<?> request) {
        if (request.finished()) return;
        if (queue.remove(request)) {
            finish(request, Reply.failed(Reply.Failure.CANCELLED, ""));
            return;
        }
        request.abandoned = true;
        request.complete(Reply.failed(Reply.Failure.CANCELLED, ""));
        if (request.decoding) return; // the decoded result is still cached when it arrives
        if (request.streaming()) {
            // Stop the transfer now; the server's answer (UploadResult) is ignored when it comes.
            dropStream(request, "cancelled");
            release(request);
        }
        // Otherwise it stays in flight until the server answers (its stream is then aborted) or it times out, so
        // the server's answer is never taken for another request's.
    }

    // =================================================================== answers

    /** Returns true when the job belongs to an M2 request. */
    boolean jobAccepted(S2C.JobAccepted m) {
        Request<?> request = byReqId.get(m.reqId());
        if (request == null) {
            if (!cutsAwaitingJob.remove(m.reqId())) return false;
            link.cutAccepted(m, RequestKind.CUT.label);
            return true;
        }
        if (request.kind == RequestKind.CUT && !request.jobSeen) {
            request.jobSeen = true;
            link.cutAccepted(m, RequestKind.CUT.label);
        }
        request.lastActivity = link.now();
        return true;
    }

    /** Returns true when the refusal belongs to an M2 request. */
    boolean jobRejected(S2C.JobRejected m) {
        Request<?> request = byReqId.get(m.reqId());
        if (request == null) return false;
        release(request);
        // A copy of a selection the server dropped is retried after a new upload (the server sends no notice), unless
        // something the caller ordered went out after it: then the refusal is its answer, toasted for the player.
        request.retrying = request.retriesSelection && m.reason() == RejectReason.SELECTION_NOT_LOADED
                && !request.abandoned && request.order.mayRetry();
        // Refused only because the player's stroke is still being applied: sent again, and its notice not shown.
        request.strokeRetrying = link.strokePending(m.reqId()) && !request.abandoned && !request.retrying
                && (request.kind == RequestKind.COPY || request.kind == RequestKind.CUT) && request.order.retriesForStroke();
        if (!request.abandoned && !request.retrying) {
            refusals.add(new Refusal(request.kind, m.reason(), link.now(), request.quiet || request.strokeRetrying));
        }
        finish(request, Reply.refused(m.reason(), ""));
        return true;
    }

    /**
     * A clipboard made for one of this client's requests. Unsolicited ones and ones with impossible sizes are
     * ignored: a clipboard is only ever made by a copy, a load or an upload this client asked for.
     */
    void clipboardReady(S2C.ClipboardReady m) {
        Request<?> request = byReqId.get(m.reqId());
        if (request == null || !request.kind.makesClipboard()) {
            SculptoryMod.LOG.warn("Sculptory: ignoring a clipboard nobody asked for (request {})", m.reqId());
            return;
        }
        if (!plausible(m)) {
            SculptoryMod.LOG.warn("Sculptory: ignoring a clipboard of impossible size {} (anchor {}, {} cells)",
                    m.dims(), m.anchor(), m.cells());
            return;
        }
        ClipboardCache.Entry entry = new ClipboardCache.Entry(m.clipboardId(), m.dims(), m.anchor(), m.cells(), m.bytes(),
                m.entities());
        cache.setCurrent(entry);
        request.lastActivity = link.now();
        if (request.kind.uploadsClipboard()) {
            request.uploaded = entry; // completed by the UploadResult that follows
            return;
        }
        if (request.kind == RequestKind.CUT && !request.jobSeen) {
            // JobAccepted is sent while the cut is admitted, before its clipboard can be ready; if it ever came
            // later, its erase job is still followed.
            if (cutsAwaitingJob.size() >= 64) cutsAwaitingJob.clear();
            cutsAwaitingJob.add(request.reqId);
        }
        release(request);
        finish(request, Reply.ok(entry));
    }

    /** Sides of 1 to {@value #MAX_SIDE}, anchors within ±{@value #MAX_ANCHOR}, and a cell count the box can hold. */
    static boolean plausible(S2C.ClipboardReady m) {
        BlockPos dims = m.dims();
        BlockPos anchor = m.anchor();
        for (int side : new int[] {dims.x(), dims.y(), dims.z()}) {
            if (side < 1 || side > MAX_SIDE) return false;
        }
        for (int coordinate : new int[] {anchor.x(), anchor.y(), anchor.z()}) {
            if (coordinate < -MAX_ANCHOR || coordinate > MAX_ANCHOR) return false;
        }
        long volume = (long) dims.x() * dims.y() * dims.z();
        return m.cells() >= 0 && m.cells() <= volume && m.bytes() >= 0;
    }

    /** The folder listed is the one asked for; entries that are not valid children of it are dropped. */
    void libraryListing(S2C.LibraryListing m) {
        Request<?> request = byReqId.get(m.reqId());
        if (request == null || request.kind != RequestKind.LIST) return;
        release(request);
        String folder = request.label;
        List<S2C.LibraryListing.Entry> entries = new ArrayList<>(m.entries().size());
        for (S2C.LibraryListing.Entry entry : m.entries()) {
            if (validEntry(entry, folder, link.player())) {
                entries.add(entry);
            } else {
                SculptoryMod.LOG.warn("Sculptory: ignoring an invalid library entry in '{}'", folder);
            }
        }
        finish(request, Reply.ok(new LibraryFolder(folder, entries, m.writable())));
    }

    /**
     * A listing entry this client can use: a valid library path of its kind (the server's own {@link LibraryPath}
     * rules: a schematic ends in {@code .schem}, a palette in {@code .palette.json}) directly inside {@code folder}, and
     * an empty or lowercase SHA-256 content hash (only schematics have one: a palette is never an asset). Two
     * exceptions for the virtual {@code _shared} folder (per-asset access): the root lists it as a folder, and it
     * lists files in other players' folders ({@code _players/<not me>/...}, any depth).
     */
    static boolean validEntry(S2C.LibraryListing.Entry entry, String folder, UUID me) {
        String hash = entry.contentHash();
        boolean schematic = entry.kind() == S2C.LibraryListing.Entry.Kind.SCHEMATIC;
        if (!hash.isEmpty() && (!schematic || !SHA256.matcher(hash).matches())) return false;
        String path = entry.path();
        if (entry.folder() && path.equals(LibraryPath.SHARED)) return folder.isEmpty() && !entry.restricted();
        LibraryPath parsed;
        try {
            parsed = switch (entry.kind()) {
                case FOLDER -> path.isEmpty() ? null : LibraryPath.folder(path);
                case SCHEMATIC -> LibraryPath.file(path);
                case PALETTE -> LibraryPath.palette(path);
            };
        } catch (LibraryPathException e) {
            return false;
        }
        if (parsed == null) return false;
        if (folder.equals(LibraryPath.SHARED)) {
            return parsed.isFile() && parsed.owner().isPresent() && !parsed.owner().get().equals(me);
        }
        int slash = path.lastIndexOf('/');
        String parent = slash < 0 ? "" : path.substring(0, slash);
        return parent.equals(folder);
    }

    /** Per-asset access: the access asked for; one for another path, or nobody's, is ignored. */
    void libraryAccess(S2C.LibraryAccess m) {
        Request<?> request = byReqId.get(m.reqId());
        if (request == null || request.kind != RequestKind.ACCESS) {
            SculptoryMod.LOG.warn("Sculptory: ignoring an access nobody asked for (request {})", m.reqId());
            return;
        }
        release(request);
        if (!m.path().equals(request.label)) {
            finish(request, Reply.failed(Reply.Failure.CORRUPT, "the server answered for another entry"));
            return;
        }
        finish(request, Reply.ok(m.access()));
    }

    void assetSaved(S2C.AssetSaved m) {
        Request<?> request = byReqId.get(m.reqId());
        if (request == null || request.kind != RequestKind.SAVE) return;
        release(request);
        finish(request, Reply.ok(new SavedAsset(m.path(), m.contentHash())));
    }

    /**
     * A library change (M4). With a request id, the answer to one of this client's changes: it must confirm exactly what
     * was asked. With {@link S2C.LibraryChanged#PUSH}, another player's change this player may see. Either way a valid
     * change goes into {@link #changes()}; one naming invalid paths, or answering nothing asked, is dropped.
     */
    void libraryChanged(S2C.LibraryChanged m) {
        if (m.reqId() != S2C.LibraryChanged.PUSH) {
            Request<?> request = byReqId.get(m.reqId());
            if (request != null && request.kind == RequestKind.PALETTE_SAVE) {
                paletteSaved(request, m);
                return;
            }
            if (request == null || request.kind != RequestKind.MANAGE) {
                SculptoryMod.LOG.warn("Sculptory: ignoring a library change nobody asked for (request {})", m.reqId());
                return;
            }
            release(request);
            LibraryChange asked = request.change;
            if (asked.folder() != m.folder() || !asked.from().equals(m.from()) || !asked.to().equals(m.to())) {
                finish(request, Reply.failed(Reply.Failure.CORRUPT, "the server answered a different change"));
                return;
            }
            changes.add(asked);
            finish(request, Reply.ok(asked));
            return;
        }
        if (!LibraryChange.valid(m.folder(), m.from(), m.to())) {
            SculptoryMod.LOG.warn("Sculptory: ignoring an invalid library change");
            return;
        }
        changes.add(new LibraryChange(m.folder(), m.from(), m.to()));
    }

    /**
     * The answer to a palette save: a new file ({@code from} {@code ""}) at the path asked for, or at that path under
     * this player's own folder (the server saves there for players without {@code library.write}); anything else is
     * {@link Reply.Failure#CORRUPT}.
     */
    private void paletteSaved(Request<?> request, S2C.LibraryChanged m) {
        release(request);
        String asked = request.label;
        if (m.folder() || !m.from().isEmpty() || !savedAt(asked, m.to(), link.player())) {
            finish(request, Reply.failed(Reply.Failure.CORRUPT, "the server answered a different change"));
            return;
        }
        LibraryChange change = new LibraryChange(false, "", m.to());
        changes.add(change);
        finish(request, Reply.ok(change));
    }

    /**
     * Whether a save asked for at {@code asked} may have landed at {@code written}: itself, or it in the player
     * folder of {@code player} (only the path itself when the player is not known).
     */
    static boolean savedAt(String asked, String written, UUID player) {
        LibraryPath path;
        LibraryPath wanted;
        try {
            path = LibraryPath.palette(written);
            wanted = LibraryPath.palette(asked);
        } catch (LibraryPathException e) {
            return false;
        }
        if (path.equals(wanted)) return true;
        return player != null && !wanted.inPlayersArea() && path.equals(wanted.under(player));
    }

    /** A palette loaded for one of this client's requests; one for another path, or nobody's, is ignored. */
    void paletteData(S2C.PaletteData m) {
        Request<?> request = byReqId.get(m.reqId());
        if (request == null || request.kind != RequestKind.PALETTE_LOAD) {
            SculptoryMod.LOG.warn("Sculptory: ignoring a palette nobody asked for (request {})", m.reqId());
            return;
        }
        release(request);
        if (!m.path().equals(request.label)) {
            finish(request, Reply.failed(Reply.Failure.CORRUPT, "the server sent another palette"));
            return;
        }
        finish(request, Reply.ok(new LoadedPalette(m.path(), m.palette(), m.dropped(), m.droppedStates())));
    }

    void uploadGrant(S2C.UploadGrant m) {
        Request<?> request = byReqId.get(m.reqId());
        if (request == null || !request.kind.uploads() || request.sender != null || request.abandoned) {
            // Nobody waits for it any more: free the server's reservation now rather than after its stall timeout.
            link.send(new StreamAbort(m.streamId(), "unwanted"));
            if (request != null && request.abandoned) release(request);
            return;
        }
        if (m.creditBytes() < 0 || m.streamId() < 0 || uploadsByStream.containsKey(m.streamId())
                || inbound.containsKey(m.streamId())) {
            link.send(new StreamAbort(m.streamId(), "invalid"));
            fail(request, Reply.Failure.ABORTED, "invalid grant");
            return;
        }
        TreeMap<String, String> meta = new TreeMap<>();
        if (request.kind == RequestKind.UPLOAD) meta.put("fileName", request.label);
        request.sender = new StreamSender(m.streamId(), request.kind.streamKind(), request.payload, meta,
                StreamSender.MAX_C2S_CHUNK, m.creditBytes());
        request.payload = null; // the sender holds its own copy
        request.streamId = m.streamId();
        request.lastActivity = link.now();
        uploadsByStream.put(m.streamId(), request);
        sendUploads(StreamSender.CLIENT_BYTES_PER_TICK);
    }

    void uploadResult(S2C.UploadResult m) {
        Request<?> request = byReqId.get(m.reqId());
        if (request == null || !request.kind.uploadsClipboard()) return;
        release(request);
        if (m.clipboardId() != null) {
            ClipboardCache.Entry entry = request.uploaded != null ? request.uploaded : cache.get(m.clipboardId()).orElse(null);
            finish(request, entry != null ? Reply.ok(entry) : Reply.failed(Reply.Failure.CORRUPT, "no clipboard details"));
            return;
        }
        String error = m.error();
        RejectReason reason = reasonPrefix(error);
        if (reason != null) {
            String detail = error.substring(reason.name().length() + 1).trim();
            if (!request.abandoned) link.notice(detailNotice(reason, detail));
            finish(request, Reply.refused(reason, detail));
        } else {
            if (!request.abandoned) link.notice(Notice.of(Notice.Level.WARNING, UPLOAD_ERROR, request.label, error));
            finish(request, Reply.failed(Reply.Failure.ABORTED, error));
        }
    }

    /**
     * A server notice: refusals are matched to their request and reworded; returns the notice to show, or
     * {@code null} to show none.
     */
    Notice onNotice(Notice notice) {
        boolean request = notice.key().equals(REQUEST_REFUSED);
        boolean preview = notice.key().equals(PREVIEW_REFUSED);
        if (!request && !preview) return notice;
        RejectReason reason = notice.args().isEmpty() ? null : reasonNamed(notice.args().get(0));
        String detail = notice.args().size() > 1 ? notice.args().get(1) : "";
        if (request) {
            Refusal refusal = refusals.poll();
            // The refusal of a quiet request (a selection an op needs): the op's caller reports it.
            if (refusal != null && refusal.quiet()) return null;
        } else {
            purgeTombstones(link.now());
            if (!tombstones.isEmpty()) {
                // The answer to a preview that already timed out (and said so): not the one waiting now.
                tombstones.poll();
                return null;
            }
            Request<?> pending = unmatchedPreview();
            if (pending != null) {
                release(pending);
                finish(pending, Reply.refused(reason != null ? reason : RejectReason.INVALID, detail));
                if (pending.abandoned) return null;
            }
        }
        return reason == null ? notice : detailNotice(reason, detail);
    }

    private void purgeTombstones(long now) {
        while (!tombstones.isEmpty() && now - tombstones.peek().until() > 0) tombstones.poll();
    }

    /** The plain-English refusal with the server's detail: {@code sculptory.reject.<reason>.detail}. */
    static Notice detailNotice(RejectReason reason, String detail) {
        return Notice.of(Notice.Level.WARNING, SessionNotices.reasonKey(reason) + ".detail", detail);
    }

    // =================================================================== streams

    /**
     * Accepts a stream only as the answer to a request waiting for one; anything else (a stream nobody asked for, an
     * upload kind, an empty or oversized stream, one past the stream limit) is aborted at once and holds nothing.
     */
    void streamOpen(StreamOpen m) {
        boolean upload = m.kind() == StreamKind.SCHEM_UPLOAD || m.kind() == StreamKind.SELECTION_UPLOAD
                || m.kind() == StreamKind.GENERATED_UPLOAD;
        Request<?> request = upload ? null : match(m);
        if (request == null) {
            link.send(new StreamAbort(m.id(), "unwanted"));
            consumeTombstone(m);
            return;
        }
        if (inbound.size() >= MAX_INBOUND_STREAMS || inbound.containsKey(m.id()) || uploadsByStream.containsKey(m.id())) {
            link.send(new StreamAbort(m.id(), "busy"));
            fail(request, Reply.Failure.ABORTED, "too many transfers at once");
            return;
        }
        long cap = m.kind() == StreamKind.SCHEM_FILE ? MAX_FILE_STREAM : MAX_PREVIEW_STREAM;
        if (m.totalBytes() <= 0) {
            link.send(new StreamAbort(m.id(), "invalid"));
            fail(request, Reply.Failure.ABORTED, "empty transfer");
            return;
        }
        StreamAssembler assembler;
        try {
            assembler = new StreamAssembler(m, cap, StreamAssembler.DEFAULT_WINDOW);
        } catch (ProtocolException e) {
            link.send(new StreamAbort(m.id(), "too_large"));
            fail(request, Reply.Failure.ABORTED, e.getMessage());
            return;
        }
        if (request.abandoned) {
            // Cancelled while waiting: refuse the data and forget the request.
            link.send(new StreamAbort(m.id(), "cancelled"));
            release(request);
            return;
        }
        inbound.put(m.id(), new Inbound(assembler, request));
        long now = link.now();
        request.streamId = m.id();
        request.total = m.totalBytes();
        request.lastActivity = now;
        request.deadline = now + streamDeadlineNanos(m.totalBytes());
        String path = m.meta().get("path");
        if (request.kind == RequestKind.PREVIEW && path != null && !path.isEmpty()) request.label = path;
        String fileName = m.meta().get("fileName");
        if (request.kind == RequestKind.EXPORT && fileName != null && !fileName.isEmpty()) request.label = fileName;
        pump(); // a preview that started streaming no longer blocks the next one
    }

    /** How long a stream of {@code bytes} may take in all: {@code max(60 s, bytes / 256 KiB/s)}. */
    static long streamDeadlineNanos(long bytes) {
        long transfer = Math.max(0, bytes) / MIN_STREAM_BYTES_PER_SECOND * 1_000_000_000L;
        return Math.max(MIN_STREAM_DEADLINE_NANOS, transfer);
    }

    /** A late preview stream for a preview that timed out: its tombstone has been answered. */
    private void consumeTombstone(StreamOpen m) {
        if (m.kind() != StreamKind.CLIPBOARD_PREVIEW && m.kind() != StreamKind.ASSET_PREVIEW) return;
        Iterator<Tombstone> graves = tombstones.iterator();
        while (graves.hasNext()) {
            SourceRef source = graves.next().source();
            boolean same = switch (source) {
                case SourceRef.Clipboard clipboard -> clipboard.id().toString().equals(m.meta().get("clipboardId"));
                case SourceRef.Asset asset -> asset.contentHash().equals(m.meta().get("contentHash"));
            };
            if (same) {
                graves.remove();
                return;
            }
        }
    }

    /** The request a stream answers, or null. */
    private Request<?> match(StreamOpen m) {
        for (Request<?> request : inFlight) {
            if (request.streaming()) continue;
            switch (m.kind()) {
                case CLIPBOARD_PREVIEW -> {
                    if (request.source instanceof SourceRef.Clipboard clipboard
                            && clipboard.id().toString().equals(m.meta().get("clipboardId"))) {
                        return request;
                    }
                }
                case ASSET_PREVIEW -> {
                    if (request.source instanceof SourceRef.Asset asset
                            && asset.contentHash().equals(m.meta().get("contentHash"))) {
                        return request;
                    }
                }
                case SCHEM_FILE -> {
                    if (request.kind == RequestKind.EXPORT && Integer.toString(request.reqId).equals(m.meta().get("reqId"))) {
                        return request;
                    }
                }
                case SCHEM_UPLOAD, SELECTION_UPLOAD, GENERATED_UPLOAD, SCATTER_PLACEMENTS -> {
                    return null;
                }
            }
        }
        return null;
    }

    void streamChunk(StreamChunk m) {
        Inbound stream = inbound.get(m.id());
        if (stream == null) return;
        try {
            stream.assembler().accept(m);
            StreamCredit credit = stream.assembler().takeCredit();
            if (credit != null) link.send(credit);
        } catch (ProtocolException e) {
            inbound.remove(m.id());
            stream.assembler().abort();
            SculptoryMod.LOG.warn("Sculptory: dropping stream {}: {}", m.id(), e.getMessage());
            link.send(new StreamAbort(m.id(), "invalid"));
            fail(stream.request(), Reply.Failure.ABORTED, "damaged transfer");
            return;
        }
        Request<?> request = stream.request();
        request.done = stream.assembler().receivedBytes();
        request.lastActivity = link.now();
    }

    void streamEnd(StreamEnd m) {
        Inbound stream = inbound.remove(m.id());
        if (stream == null) return;
        byte[] bytes;
        try {
            bytes = stream.assembler().finish(m);
        } catch (ProtocolException e) {
            SculptoryMod.LOG.warn("Sculptory: dropping stream {}: {}", m.id(), e.getMessage());
            fail(stream.request(), Reply.Failure.ABORTED, "damaged transfer");
            return;
        }
        StreamOpen open = stream.assembler().open();
        link.streamCompleted(new FabricEditorSession.ReceivedStream(open, bytes));
        Request<?> request = stream.request();
        request.done = bytes.length;
        release(request);
        switch (open.kind()) {
            case CLIPBOARD_PREVIEW, ASSET_PREVIEW -> decode(request, open, bytes);
            case SCHEM_FILE -> {
                String fileName = open.meta().getOrDefault("fileName", request.label);
                finish(request, Reply.ok(new ExportedFile(request.clipboardId, fileName, bytes)));
            }
            case SCHEM_UPLOAD -> { }
        }
    }

    void streamAbort(StreamAbort m) {
        Inbound stream = inbound.remove(m.id());
        if (stream != null) {
            stream.assembler().abort();
            fail(stream.request(), Reply.Failure.ABORTED, m.reason());
            return;
        }
        Request<?> upload = uploadsByStream.remove(m.id());
        if (upload != null) {
            upload.sender.abort(m.reason());
            // Any UploadResult that follows is for a finished request and is ignored.
            fail(upload, Reply.Failure.ABORTED, m.reason());
        }
    }

    void streamCredit(StreamCredit m) {
        Request<?> upload = uploadsByStream.get(m.id());
        if (upload == null) return;
        upload.sender.credit(m);
        upload.lastActivity = link.now();
    }

    private void decode(Request<?> request, StreamOpen open, byte[] bytes) {
        String key = open.kind() == StreamKind.ASSET_PREVIEW
                ? open.meta().getOrDefault("clipboardHash", open.meta().getOrDefault("contentHash", ""))
                : open.meta().getOrDefault("contentHash", "");
        if (key.isEmpty()) key = Sha256.digest(bytes).hex();
        String cacheKey = key;
        StateSpace states = link.states();
        int connection = generation;
        PreviewDecoder.Limits limits = previewLimits;
        request.decoding = true;
        request.decodeStarted = link.now();
        decodingNow.add(request);
        Runnable task = () -> {
            try {
                decoded.add(new Decoded(connection, request, PreviewDecoder.decode(cacheKey, bytes, states, limits), null));
            } catch (Throwable e) {
                // Whatever the payload did (even running out of memory), the request must end and free its slot.
                String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                decoded.add(new Decoded(connection, request, null, error));
            }
        };
        try {
            decoder.execute(task);
        } catch (RejectedExecutionException e) {
            task.run();
        }
        drainDecoded();
    }

    /** Installs previews decoded since the last call (render thread). */
    private void drainDecoded() {
        Decoded next;
        while ((next = decoded.poll()) != null) {
            if (next.generation() != generation) continue;
            Request<?> request = next.request();
            // Gone already when its decode took too long (see tick): the late result is dropped.
            if (!decodingNow.remove(request)) continue;
            request.decoding = false;
            if (next.preview() == null) {
                SculptoryMod.LOG.warn("Sculptory: cannot read a preview: {}", next.error());
                finish(request, Reply.failed(Reply.Failure.CORRUPT, next.error()));
                continue;
            }
            String mismatch = mismatch(request.source, next.preview());
            if (mismatch != null) {
                SculptoryMod.LOG.warn("Sculptory: ignoring a preview that does not match its clipboard: {}", mismatch);
                finish(request, Reply.failed(Reply.Failure.CORRUPT, mismatch));
                continue;
            }
            cache.putPreview(next.preview(), List.of(request.source));
            finish(request, Reply.ok(next.preview()));
        }
    }

    /**
     * Why a clipboard's preview contradicts what {@code ClipboardReady} said about that clipboard (size, anchor, cell
     * count), or null when it agrees (or the clipboard is not known here any more).
     */
    private String mismatch(SourceRef source, ClipboardCache.Preview preview) {
        if (!(source instanceof SourceRef.Clipboard clipboard)) return null;
        ClipboardCache.Entry entry = cache.get(clipboard.id()).orElse(null);
        if (entry == null) return null;
        if (!entry.dims().equals(preview.dims())) return "size " + preview.dims() + " instead of " + entry.dims();
        if (!entry.anchor().equals(preview.anchor())) return "anchor " + preview.anchor() + " instead of " + entry.anchor();
        if (entry.cells() != preview.cells()) return preview.cells() + " cells instead of " + entry.cells();
        return null;
    }

    /** Sends upload data within {@code budget} bytes, oldest upload first. */
    private void sendUploads(long budget) {
        Iterator<Request<?>> uploads = uploadsByStream.values().iterator();
        while (uploads.hasNext() && budget > 0) {
            Request<?> upload = uploads.next();
            long before = upload.sender.sentBytes();
            for (Message message : upload.sender.poll(budget)) {
                String failure = link.send((C2S) message);
                if (failure != null) {
                    uploads.remove();
                    fail(upload, Reply.Failure.ABORTED, failure);
                    break;
                }
            }
            long sent = upload.sender.sentBytes() - before;
            budget -= sent;
            upload.done = upload.sender.sentBytes();
            if (sent > 0) upload.lastActivity = link.now();
            // Once the end is out, the request waits for its UploadResult (the stall timeout still applies).
            if (upload.sender.done()) uploads.remove();
        }
    }

    // =================================================================== tick and reset

    void tick(long now) {
        drainDecoded();
        drainEncoded();
        sendUploads(StreamSender.CLIENT_BYTES_PER_TICK);
        while (!refusals.isEmpty() && now - refusals.peek().at() > REFUSAL_NOTICE_WAIT_NANOS) {
            Refusal refusal = refusals.poll();
            if (refusal.quiet()) continue;
            link.notice(refusal.kind().makesClipboard()
                    ? SessionNotices.rejection(refusal.reason(), SessionNotices.Subject.COPY, link.permissions().limits())
                    : Notice.of(Notice.Level.WARNING, SessionNotices.reasonKey(refusal.reason())));
        }
        purgeTombstones(now);
        for (Request<?> request : List.copyOf(decodingNow)) {
            if (now - request.decodeStarted > DECODE_TIMEOUT_NANOS) {
                decodingNow.remove(request);
                request.decoding = false;
                finish(request, Reply.failed(Reply.Failure.TIMED_OUT, "the preview took too long to read"));
            }
        }
        for (Request<?> request : List.copyOf(inFlight)) {
            boolean stalled = now - request.lastActivity > STALL_NANOS;
            boolean late = request.deadline != 0 && request.sender == null && now - request.deadline > 0;
            if (!stalled && !late) continue;
            String reason = stalled ? "stalled" : "deadline";
            if (request.streaming()) {
                dropStream(request, reason);
            } else if (request.kind == RequestKind.PREVIEW) {
                // Its answer may still come: keep a tombstone so it is not taken for the next preview's.
                tombstones.add(new Tombstone(request.source, now + STALL_NANOS));
            }
            fail(request, Reply.Failure.TIMED_OUT, stalled
                    ? "no answer for " + STALL_NANOS / 1_000_000_000L + " s"
                    : "not finished in time");
        }
        pump();
    }

    /** The connection ended: every request fails with {@link Reply.Failure#DISCONNECTED}, all state is dropped. */
    void reset() {
        generation++;
        List<Request<?>> all = pendingRequests();
        queue.clear();
        inFlight.clear();
        byReqId.clear();
        uploadsByStream.clear();
        inbound.values().forEach(stream -> stream.assembler().abort());
        inbound.clear();
        decodingNow.clear();
        decoded.clear();
        refusals.clear();
        cutsAwaitingJob.clear();
        tombstones.clear();
        heldSelections.clear();
        selectionUploads.clear();
        encodings.clear();
        drainEncoded(); // encodings of this connection: their requests end as disconnected
        // Sets still being encoded: their requests end now (the encoding is dropped when it arrives).
        List<CompletableFuture<Reply<Sha256>>> encoding = List.copyOf(encodingNow);
        encodingNow.clear();
        for (CompletableFuture<Reply<Sha256>> result : encoding) {
            result.complete(Reply.failed(Reply.Failure.DISCONNECTED, "disconnected"));
        }
        for (Request<?> request : all) request.complete(Reply.failed(Reply.Failure.DISCONNECTED, "disconnected"));
    }

    /** Inbound streams open (for tests). */
    int inboundStreams() {
        return inbound.size();
    }

    /** Whether an inbound stream or an upload uses {@code id}. */
    boolean ownsStream(int id) {
        return inbound.containsKey(id) || uploadsByStream.containsKey(id);
    }

    // =================================================================== helpers

    /** Frees the request's slot (and lets the next queued request go). */
    private void release(Request<?> request) {
        if (inFlight.remove(request)) {
            if (request.reqId != 0) byReqId.remove(request.reqId);
            if (request.kind.uploads() && request.streamId >= 0) {
                uploadsByStream.remove(request.streamId, request);
            }
            pump();
        }
    }

    private void fail(Request<?> request, Reply.Failure failure, String detail) {
        release(request);
        decodingNow.remove(request);
        finish(request, Reply.failed(failure, detail));
    }

    /**
     * Aborts the request's stream if it is still open (an inbound stream still assembling, an upload still sending):
     * a finished request never keeps receiving or sending data.
     */
    private void dropStream(Request<?> request, String reason) {
        if (request.streamId < 0) return;
        if (request.sender != null) {
            uploadsByStream.remove(request.streamId, request);
            StreamAbort abort = request.sender.abort(reason);
            if (abort != null) link.send(abort);
            return;
        }
        Inbound stream = inbound.get(request.streamId);
        if (stream != null && stream.request() == request) {
            inbound.remove(request.streamId);
            stream.assembler().abort();
            link.send(new StreamAbort(request.streamId, reason));
        }
    }

    /** Completes the request (if not already), drops its open stream and toasts announced failures. */
    private void finish(Request<?> request, Reply<?> reply) {
        if (!(reply instanceof Reply.Ok<?>)) dropStream(request, "abandoned");
        if (request.finished()) return;
        request.complete(reply);
        if (reply instanceof Reply.Failed<?> failed && failed.failure().announced() && !request.quiet) {
            link.notice(Notice.of(Notice.Level.WARNING, failed.failure().noticeKey(), request.kind.label));
        }
    }

    /** A refusal decided on this client: toasted at once. */
    private void refuseLocally(Request<?> request, RejectReason reason, String detail) {
        if (!request.quiet) {
            link.notice(request.kind.makesClipboard() && reason == RejectReason.TOO_LARGE && request.kind != RequestKind.UPLOAD
                    ? SessionNotices.rejection(reason, SessionNotices.Subject.COPY, link.permissions().limits())
                    : detailNotice(reason, detail));
        }
        request.complete(Reply.refused(reason, detail));
    }

    private static String previewLabel(SourceRef source) {
        return switch (source) {
            case SourceRef.Clipboard clipboard -> "clipboard";
            case SourceRef.Asset asset -> asset.contentHash().substring(0, 8);
        };
    }

    /** The reason an upload error starts with ({@code "TOO_LARGE: ..."}), or null. */
    static RejectReason reasonPrefix(String error) {
        int colon = error.indexOf(':');
        return colon <= 0 ? null : reasonNamed(error.substring(0, colon));
    }

    static RejectReason reasonNamed(String name) {
        for (RejectReason reason : RejectReason.values()) {
            if (reason.name().equals(name)) return reason;
        }
        return null;
    }
}
