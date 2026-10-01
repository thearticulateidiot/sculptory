package dev.sculptory.protocol.v2;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.palette.BlockPalette;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/** Server-to-client messages. */
public sealed interface S2C extends Message permits S2C.Welcome, S2C.Incompatible, S2C.PermissionsChanged,
        S2C.StrokeStatus, S2C.JobAccepted, S2C.JobRejected, S2C.JobProgress, S2C.JobFinished, S2C.HistoryState,
        S2C.ClipboardReady, S2C.LibraryListing, S2C.AssetSaved, S2C.UploadGrant, S2C.UploadResult, S2C.ScatterPlan,
        S2C.Notice, S2C.LibraryChanged, S2C.PaletteData, S2C.SelectionReady, S2C.LibraryAccess, S2C.TinkerResult,
        S2C.EditMaskState, S2C.NavigateResult,
        StreamOpen, StreamChunk, StreamEnd, StreamAbort, StreamCredit {

    /**
     * The handshake's success. {@code serverBuild} is the server's build id ({@code 0.2.0-dev+6a043a85}; empty when
     * unknown), so the client can tell a server of the same protocol but another build apart (a notice, not a refusal).
     */
    record Welcome(int protocol, Features features, Limits limits, PermissionMask permissions, long sessionEpoch,
                   String serverBuild) implements S2C {
        public Welcome {
            Objects.requireNonNull(features);
            Objects.requireNonNull(limits);
            Objects.requireNonNull(permissions);
            Objects.requireNonNull(serverBuild);
        }

        /** Without a server build id. */
        public Welcome(int protocol, Features features, Limits limits, PermissionMask permissions, long sessionEpoch) {
            this(protocol, features, limits, permissions, sessionEpoch, "");
        }
    }

    /**
     * The server's supported protocol range, when it does not overlap the client's, and its build id (empty when
     * unknown). The build id goes on the wire only when not empty; the server leaves it out for clients whose newest
     * protocol is below 4, which cannot decode it ("Handshake").
     */
    record Incompatible(int serverMinProtocol, int serverMaxProtocol, String serverBuild) implements S2C {
        public Incompatible {
            Objects.requireNonNull(serverBuild);
        }

        /** Without a server build id. */
        public Incompatible(int serverMinProtocol, int serverMaxProtocol) {
            this(serverMinProtocol, serverMaxProtocol, "");
        }
    }

    /** Pushed when the player's permissions (and so possibly limits) change, e.g. on op/deop. */
    record PermissionsChanged(PermissionMask permissions, Limits limits) implements S2C {
        public PermissionsChanged {
            Objects.requireNonNull(permissions);
            Objects.requireNonNull(limits);
        }
    }

    /**
     * Stroke progress. {@code ackedIndex} is the last dab index applied (-1 for none); {@code reason} is set
     * only for {@link Status#REJECTED}.
     */
    /**
     * A stroke's state: dabs admitted up to {@code ackedIndex}, and written by the server up to {@code appliedIndex}
     * (-1: none yet, or not reported). Sent when a batch is admitted or refused, when the stroke ends, and (OK) when the
     * brush lane has written a batch, so a client can pace large dabs on what the server has written.
     */
    record StrokeStatus(int strokeId, int ackedIndex, Status status, RejectReason reason, int appliedIndex)
            implements S2C {
        public enum Status {
            OK,
            REJECTED,
            ENDED
        }

        public StrokeStatus {
            Objects.requireNonNull(status);
            if (ackedIndex < -1) throw new IllegalArgumentException("ackedIndex");
            if (appliedIndex < -1) throw new IllegalArgumentException("appliedIndex");
            if ((status == Status.REJECTED) != (reason != null)) {
                throw new IllegalArgumentException("A reason is required exactly when rejected");
            }
        }

        /** Without an applied index (-1). */
        public StrokeStatus(int strokeId, int ackedIndex, Status status, RejectReason reason) {
            this(strokeId, ackedIndex, status, reason, -1);
        }
    }

    record JobAccepted(int reqId, UUID jobId, long estCells) implements S2C {
        public JobAccepted {
            Objects.requireNonNull(jobId);
        }
    }

    record JobRejected(int reqId, RejectReason reason) implements S2C {
        public JobRejected {
            Objects.requireNonNull(reason);
        }
    }

    record JobProgress(UUID jobId, long done, long total, Phase phase) implements S2C {
        public JobProgress {
            Objects.requireNonNull(jobId);
            Objects.requireNonNull(phase);
        }
    }

    record JobFinished(UUID jobId, JobOutcome outcome, long changed, long skippedProtected, long skippedConflicts,
                       long strippedNbt) implements S2C {
        public JobFinished {
            Objects.requireNonNull(jobId);
            Objects.requireNonNull(outcome);
        }
    }

    /**
     * Labels are {@code ""} when there is nothing to undo or redo. {@code undoLabels} lists the undoable
     * entries newest first and {@code redoLabels} the redoable entries nearest first, each capped at
     * {@value #MAX_LABELS}; the client jumps through history by repeated undo/redo over these.
     */
    record HistoryState(boolean canUndo, boolean canRedo, String undoLabel, String redoLabel, long bytes,
                        List<String> undoLabels, List<String> redoLabels) implements S2C {
        public static final int MAX_LABELS = 64;

        public HistoryState {
            Objects.requireNonNull(undoLabel);
            Objects.requireNonNull(redoLabel);
            undoLabels = List.copyOf(undoLabels);
            redoLabels = List.copyOf(redoLabels);
            if (undoLabels.size() > MAX_LABELS || redoLabels.size() > MAX_LABELS) {
                throw new IllegalArgumentException("At most " + MAX_LABELS + " history labels each way");
            }
        }

        /** A state with only the next undo/redo label; the lists hold that label when there is one. */
        public HistoryState(boolean canUndo, boolean canRedo, String undoLabel, String redoLabel, long bytes) {
            this(canUndo, canRedo, undoLabel, redoLabel, bytes,
                    canUndo && !undoLabel.isEmpty() ? List.of(undoLabel) : List.of(),
                    canRedo && !redoLabel.isEmpty() ? List.of(redoLabel) : List.of());
        }

        public static final HistoryState EMPTY = new HistoryState(false, false, "", "", 0L);
    }

    /**
     * M2. A clipboard now held by the server (after Copy, LibraryLoad or an upload). {@code entities}: the entities it
     * holds.
     */
    record ClipboardReady(int reqId, UUID clipboardId, BlockPos dims, BlockPos anchor, long cells, long bytes,
                          int entities) implements S2C {
        public ClipboardReady {
            Objects.requireNonNull(clipboardId);
            Objects.requireNonNull(dims);
            Objects.requireNonNull(anchor);
            if (entities < 0) throw new IllegalArgumentException("Negative entity count");
        }

        /** A clipboard without entities. */
        public ClipboardReady(int reqId, UUID clipboardId, BlockPos dims, BlockPos anchor, long cells, long bytes) {
            this(reqId, clipboardId, dims, anchor, cells, bytes, 0);
        }
    }

    /**
     * The answer to {@code SelectionUpload}: the server holds the cell set {@code hash} for this player, so ops and
     * copies may name it as {@code Region.Uploaded}.
     */
    record SelectionReady(int reqId, Sha256 hash) implements S2C {
        public SelectionReady {
            Objects.requireNonNull(hash);
        }
    }

    /**
     * M2. One library folder's contents. {@code writable} (M4): the player may create folders in it and rename, move
     * and delete its entries (the server checks every request again); the client shows those actions only then. The
     * folder {@code _shared} (per-asset access) lists what other players granted this one, with the entries' real
     * paths.
     */
    record LibraryListing(int reqId, String folder, List<Entry> entries, boolean writable) implements S2C {
        /**
         * {@code contentHash} is {@code ""} for folders (and for palettes, which are never assets). {@code kind}
         * (palettes) tells a schematic from a palette; it is {@link Kind#FOLDER} exactly for folders.
         * {@code restricted} (per-asset access): only listed players may load the file; never set for a folder.
         */
        public record Entry(String path, boolean folder, long bytes, String contentHash, Kind kind, boolean restricted) {
            /** What a listed entry is. Append-only: the wire carries the ordinal. */
            public enum Kind {
                FOLDER,
                /** A {@code .schem} asset. */
                SCHEMATIC,
                /** A {@code .palette.json} block palette. */
                PALETTE
            }

            public Entry {
                Objects.requireNonNull(path);
                Objects.requireNonNull(contentHash);
                Objects.requireNonNull(kind);
                if (folder != (kind == Kind.FOLDER)) throw new IllegalArgumentException("Only a folder is a FOLDER");
                if (folder && restricted) throw new IllegalArgumentException("A folder has no access of its own");
            }

            /** An entry everyone may load. */
            public Entry(String path, boolean folder, long bytes, String contentHash, Kind kind) {
                this(path, folder, bytes, contentHash, kind, false);
            }

            /** A folder, or a schematic everyone may load. */
            public Entry(String path, boolean folder, long bytes, String contentHash) {
                this(path, folder, bytes, contentHash, folder ? Kind.FOLDER : Kind.SCHEMATIC, false);
            }
        }

        public LibraryListing {
            Objects.requireNonNull(folder);
            entries = List.copyOf(entries);
        }
    }

    /** M2. */
    record AssetSaved(int reqId, String path, String contentHash) implements S2C {
        public AssetSaved {
            Objects.requireNonNull(path);
            Objects.requireNonNull(contentHash);
        }
    }

    /**
     * M4. A library change: the answer to {@code LibraryMove}, {@code LibraryDelete} or {@code LibraryCreateFolder}
     * (with the request's {@code reqId}), or, with {@code reqId} {@value #PUSH}, a change another player made that
     * this player may see, so an open Library window can list its folder again. {@code from} is {@code ""} for a
     * created folder and {@code to} is {@code ""} for a deleted entry; in a push, a path the receiver may not read is
     * {@code ""} too. At least one of them is set.
     */
    record LibraryChanged(int reqId, boolean folder, String from, String to) implements S2C {
        /** The request id of a change pushed to other players (clients never use 0 for a request). */
        public static final int PUSH = 0;

        public LibraryChanged {
            Objects.requireNonNull(from);
            Objects.requireNonNull(to);
            if (from.isEmpty() && to.isEmpty()) throw new IllegalArgumentException("A library change names a path");
        }
    }

    /**
     * Palettes. The answer to {@code PaletteLoad}: the palette at {@code path} as this server knows it. {@code palette}
     * holds the entries whose states the server knows, as its own state text; {@code dropped} counts the entries it
     * left out (states it doesn't know, or can't read), and {@code droppedStates} names the first
     * {@value #MAX_SHOWN_DROPPED} of them for the player. A palette with nothing left is refused instead.
     */
    record PaletteData(int reqId, String path, BlockPalette palette, int dropped, List<String> droppedStates)
            implements S2C {
        public static final int MAX_SHOWN_DROPPED = 3;

        public PaletteData {
            Objects.requireNonNull(path);
            Objects.requireNonNull(palette);
            droppedStates = List.copyOf(droppedStates);
            if (droppedStates.size() > MAX_SHOWN_DROPPED || dropped < droppedStates.size()
                    || dropped > BlockPalette.MAX_ENTRIES) {
                throw new IllegalArgumentException("Dropped count " + dropped + " with " + droppedStates.size() + " names");
            }
        }

        /** Nothing left out. */
        public PaletteData(int reqId, String path, BlockPalette palette) {
            this(reqId, path, palette, 0, List.of());
        }
    }

    /**
     * Per-asset access. The answer to {@code LibraryAccessGet}: who may load the library file {@code path}. Grantees
     * always carry their UUID here.
     */
    record LibraryAccess(int reqId, String path, AssetAccess access) implements S2C {
        public LibraryAccess {
            Objects.requireNonNull(path);
            Objects.requireNonNull(access);
        }
    }

    /** M2. Lets the client stream an upload on {@code streamId} with an initial credit. */
    record UploadGrant(int reqId, int streamId, long creditBytes) implements S2C {}

    /** M2. Exactly one of {@code clipboardId} and {@code error} is non-null. */
    record UploadResult(int reqId, UUID clipboardId, String error) implements S2C {
        public UploadResult {
            if ((clipboardId == null) == (error == null)) {
                throw new IllegalArgumentException("Exactly one of clipboardId and error");
            }
        }
    }

    /**
     * M3. A planned scatter, which the server holds as {@code planId} until it is committed
     * ({@code RunOp(ScatterCommit(planId))}), replaced by the player's next preview, or expired. The placements
     * themselves follow as a {@code SCATTER_PLACEMENTS} stream when there is at least one.
     *
     * @param placements how many placements the plan holds
     * @param rejectedCounts per scatter {@code Outcome} name, the area columns that ended with it (non-zero only);
     *     with the placements they add up to the area's columns
     * @param totalCells the cells the placements write at most (their footprints)
     * @param bounds the union of the placements' footprints, or {@code null} without placements
     */
    record ScatterPlan(int reqId, UUID planId, int placements, SortedMap<String, Integer> rejectedCounts,
                       long totalCells, Box bounds) implements S2C {
        public ScatterPlan {
            Objects.requireNonNull(planId);
            rejectedCounts = Collections.unmodifiableSortedMap(new TreeMap<>(rejectedCounts));
            if (placements < 0 || totalCells < 0) throw new IllegalArgumentException("Negative scatter plan size");
        }
    }

    /**
     * Tinker (protocol 5): the answer to {@code TinkerBlock} or {@code TinkerEntity}
     * {@code reqId}. {@code reason} is null when it was carried out (or, for an entity request without edits, looked at),
     * else why nothing was changed, with {@code detail} saying more ("" when nothing more is known). {@code data} is, for
     * an entity request that was carried out, what the panel shows of the entity now ({@code core.tinker.EntityView}'s
     * compound as binary NBT, at most {@value #MAX_DATA_BYTES} bytes); empty otherwise. The array is copied in and out.
     */
    record TinkerResult(int reqId, RejectReason reason, String detail, byte[] data) implements S2C {
        public static final int MAX_DATA_BYTES = 64 * 1024;

        public TinkerResult {
            Objects.requireNonNull(detail);
            Objects.requireNonNull(data);
            if (data.length > MAX_DATA_BYTES) throw new IllegalArgumentException("Tinker data of " + data.length + " bytes");
            if (reason != null && data.length > 0) throw new IllegalArgumentException("A refusal carries no data");
            data = data.clone();
        }

        /** Carried out. */
        public static TinkerResult done(int reqId, byte[] data) {
            return new TinkerResult(reqId, null, "", data);
        }

        public static TinkerResult refused(int reqId, RejectReason reason, String detail) {
            return new TinkerResult(reqId, Objects.requireNonNull(reason), detail, new byte[0]);
        }

        @Override
        public byte[] data() {
            return data.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof TinkerResult other && reqId == other.reqId && reason == other.reason
                    && detail.equals(other.detail) && java.util.Arrays.equals(data, other.data);
        }

        @Override
        public int hashCode() {
            return Objects.hash(reqId, reason, detail, java.util.Arrays.hashCode(data));
        }

        @Override
        public String toString() {
            return "TinkerResult[reqId=" + reqId + ", reason=" + reason + ", detail=" + detail + ", " + data.length
                    + " bytes]";
        }
    }

    /**
     * The answer to {@code C2S.SetEditMask} (protocol 5): {@code reason} null when the
     * server took the mask (then {@code detail} is empty), else why it refused it, with a readable {@code detail}.
     */
    record EditMaskState(int reqId, RejectReason reason, String detail) implements S2C {
        public EditMaskState {
            Objects.requireNonNull(detail);
            if (reason == null && !detail.isEmpty()) throw new IllegalArgumentException("An accepted mask has no detail");
        }

        public static EditMaskState accepted(int reqId) {
            return new EditMaskState(reqId, null, "");
        }

        public static EditMaskState refused(int reqId, RejectReason reason, String detail) {
            return new EditMaskState(reqId, Objects.requireNonNull(reason), detail);
        }
    }

    /**
     * The answer to {@code C2S.Navigate} (protocol 5): where the
     * player's feet were put ({@code reason} null), or why nothing happened ({@code feet} null). A refusal for want of
     * the {@code navigate} node or of Creative or Spectator is {@code NO_PERMISSION}, and the server says which in a
     * {@link Notice}.
     */
    record NavigateResult(int reqId, RejectReason reason, BlockPos feet) implements S2C {
        public NavigateResult {
            if ((reason == null) == (feet == null)) {
                throw new IllegalArgumentException("A navigate result has either a landing spot or a reason");
            }
        }

        public static NavigateResult landed(int reqId, BlockPos feet) {
            return new NavigateResult(reqId, null, Objects.requireNonNull(feet));
        }

        public static NavigateResult refused(int reqId, RejectReason reason) {
            return new NavigateResult(reqId, Objects.requireNonNull(reason), null);
        }
    }

    /** A translatable message for the player. */
    record Notice(Level level, String key, List<String> args) implements S2C {
        public enum Level {
            INFO,
            WARN,
            ERROR
        }

        public Notice {
            Objects.requireNonNull(level);
            Objects.requireNonNull(key);
            args = List.copyOf(args);
        }
    }
}
