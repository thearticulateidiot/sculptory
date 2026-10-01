package dev.sculptory.fabric.client.session;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.palette.BlockPalette;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.tinker.EntityEdit;
import dev.sculptory.core.tinker.EntityView;
import dev.sculptory.core.tinker.SignText;
import dev.sculptory.core.schem.SchematicFormat;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.protocol.v2.AssetAccess;
import dev.sculptory.protocol.v2.RejectReason;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * The editor's only link to the server. {@code FabricEditorSession} speaks protocol v2;
 * {@code MockEditorSession} runs the core kernels on an in-memory world for tests.
 * Called on the client (render) thread; completion stages complete on that thread too.
 */
public interface EditorSession {
    SessionState state();

    Capabilities capabilities();

    /** Granted nodes and server limits. */
    Permissions permissions();

    /**
     * Sends a tool's request. A {@link ToolAction.RunOp} takes every region kind the editor makes; the session uploads a
     * {@code Region.Cells} first (until that exists, such an op is rejected with a clear message).
     */
    CompletionStage<ToolResult> send(ToolAction a);

    StrokeHandle beginStroke(ToolId tool, BrushSpec spec, StrokeParams p);

    /**
     * Undoes one entry. While an undo or redo is still in flight the press is queued (as a signed step count with
     * any other queued presses) and sent once the running one finishes. The "Undo: label" toast comes from the
     * session when the server accepts the step, not from the caller.
     */
    void undo();

    /** Redoes one entry; queued like {@link #undo}. */
    void redo();

    /**
     * Moves through history by repeated undo/redo. {@code historyId} is a signed step count relative to the
     * current position: {@code -n} undoes n entries, {@code +n} redoes n entries (see
     * {@link HistoryMirror#undoTarget} and {@link HistoryMirror#redoTarget}).
     */
    void jumpTo(long historyId);

    /**
     * True while undo/redo steps are queued or one is in flight. The history mirror then lags behind, so a caller
     * should pass presses on to {@link #undo}/{@link #redo} instead of judging them from the mirror.
     */
    default boolean historyBusy() {
        return false;
    }

    /**
     * True while a brush stroke hasn't finished on the server: not yet ended, or a Shape stroke whose dabs are still
     * being written. Its history entry arrives once it has (the tutorial waits for strokes begun before a lesson).
     */
    default boolean strokesPending() {
        return false;
    }

    /**
     * Forgets undo/redo presses not sent yet (the step already running finishes). The editor calls it when it
     * closes; sessions also do it themselves before starting a new edit, so a queued undo never undoes that edit.
     */
    default void dropQueuedHistorySteps() {}

    /** The {@link #jumpTo} still running, with its progress; empty when none runs (or a press took it over). */
    default Optional<HistoryJump> historyJump() {
        return Optional.empty();
    }

    /**
     * Undo anyway (or Redo anyway) on offer: present while the undo (or redo) steps this player made in a row, since
     * any other change to their history, kept blocks that had changed since the edit ("Only my changes", the default).
     * Gone once the history changes otherwise (a new edit, a step the other way, a cancelled step, an eviction) or the
     * overwrite completes.
     */
    default Optional<HistoryOffer> historyOffer() {
        return Optional.empty();
    }

    /**
     * Accepts the {@link #historyOffer}: the server re-applies those steps with {@code OVERWRITE}, so the blocks they
     * kept are overwritten too, exactly as if the steps had overwritten from the start. The history position does not
     * move. Does nothing without an offer, while it runs, or while undo/redo steps are queued or running. The session
     * toasts the result (or why the server refused, e.g. because the history changed meanwhile).
     */
    default void acceptHistoryOffer() {}

    JobTracker jobs();

    HistoryMirror history();

    ClipboardCache clipboards();

    Subscription onNotice(Consumer<Notice> l);

    // ---- M2: clipboards, previews, library, schematic files ----
    //
    // Every answer is a Reply. The session raises the toast for refusals and failures itself (see Reply), so
    // callers only react to the outcome. Requests are paced to what the server runs at once and fail with
    // TIMED_OUT when nothing arrives for 30 s. A disconnect fails everything pending with DISCONNECTED.

    /**
     * Copies (or cuts) every cell of {@code region} into the player's clipboard, with the entities {@code entities}
     * selects; {@code origin} becomes its anchor. Completes with the new clipboard ({@code ClipboardReady}), which also
     * becomes {@link ClipboardCache#current()}. A cut's erase is a job, followed in {@link #jobs()} and undoable.
     * Takes every region kind the editor makes (a {@code Region.Cells} is uploaded first; until that exists it is
     * refused with a clear message).
     */
    default CompletionStage<Reply<ClipboardCache.Entry>> copy(Region region, BlockPos origin, boolean cut,
                                                               EntityFilter entities) {
        return CompletableFuture.completedFuture(Reply.refused(RejectReason.DISABLED, "not supported"));
    }

    /** {@link #copy(Region, BlockPos, boolean, EntityFilter)} of a box, without entities. */
    default CompletionStage<Reply<ClipboardCache.Entry>> copy(Box box, BlockPos origin, boolean cut) {
        return copy(new Region.Cuboid(box), origin, cut, EntityFilter.NONE);
    }

    /**
     * Downloads and decodes the preview of the player's clipboard or of a library asset (which also lets the server
     * paste that asset). A cached preview completes at once; asking again for one in progress returns the same
     * transfer.
     */
    default Transfer<ClipboardCache.Preview> requestPreview(SourceRef source) {
        return Transfers.done(Transfer.Kind.PREVIEW, "preview", Reply.refused(RejectReason.DISABLED, "not supported"));
    }

    /** Lists one library folder ({@code ""} is the root). */
    default CompletionStage<Reply<LibraryFolder>> libraryList(String folder) {
        return CompletableFuture.completedFuture(Reply.refused(RejectReason.DISABLED, "not supported"));
    }

    /** Loads a library asset into the player's clipboard. */
    default CompletionStage<Reply<ClipboardCache.Entry>> libraryLoad(String path) {
        return CompletableFuture.completedFuture(Reply.refused(RejectReason.DISABLED, "not supported"));
    }

    /**
     * Saves a clipboard as a library asset. Players without {@code library.write} save under their own
     * {@code _players/<uuid>/} folder: the answer carries the path actually written.
     */
    default CompletionStage<Reply<SavedAsset>> saveAsset(UUID clipboardId, String path) {
        return CompletableFuture.completedFuture(Reply.refused(RejectReason.DISABLED, "not supported"));
    }

    // ---- M4: library management ----
    //
    // Allowed where the folder's listing says writable (the server checks every request again). One library write
    // (these and saveAsset) is sent at a time; the others wait in order. A success is also added to libraryChanges().

    /**
     * Renames or moves a file ({@code folder} false; the target may be in another folder), or renames a folder in
     * place. Never replaces anything: the server refuses a name that exists.
     */
    default CompletionStage<Reply<LibraryChange>> libraryMove(String from, String to, boolean folder) {
        return CompletableFuture.completedFuture(Reply.refused(RejectReason.DISABLED, "not supported"));
    }

    /** Deletes a file (the server keeps it in its trash) or an empty folder. */
    default CompletionStage<Reply<LibraryChange>> libraryDelete(String path, boolean folder) {
        return CompletableFuture.completedFuture(Reply.refused(RejectReason.DISABLED, "not supported"));
    }

    /** Creates a folder. */
    default CompletionStage<Reply<LibraryChange>> libraryCreateFolder(String path) {
        return CompletableFuture.completedFuture(Reply.refused(RejectReason.DISABLED, "not supported"));
    }

    /** The library changes this session has seen: its own, and those the server pushed from other players. */
    default LibraryChanges libraryChanges() {
        return LibraryChanges.NONE;
    }

    // ---- Per-asset access ----

    /**
     * Who may load the library file {@code path} (asset or palette). Only who may change that may ask: the server
     * refuses others with {@code NO_PERMISSION}.
     */
    default CompletionStage<Reply<AssetAccess>> libraryAccess(String path) {
        return CompletableFuture.completedFuture(Reply.refused(RejectReason.DISABLED, "not supported"));
    }

    /**
     * Sets who may load the library file {@code path}. Grantees without a UUID are names for the server to resolve
     * (an unknown one refuses the whole change). A library write, so it waits for the others; the answer is the
     * entry changed in place ({@code from} and {@code to} both {@code path}), also added to {@link #libraryChanges()}.
     */
    default CompletionStage<Reply<LibraryChange>> setLibraryAccess(String path, AssetAccess access) {
        return CompletableFuture.completedFuture(Reply.refused(RejectReason.DISABLED, "not supported"));
    }

    // ---- Palettes ----

    /**
     * Saves a block palette as a library file ({@code .palette.json}; a palette of that name is replaced). The server
     * refuses a state it doesn't know. Players without {@code library.write} save under their own
     * {@code _players/<uuid>/} folder: the answer ({@code from} {@code ""}) carries the path written. A library write,
     * so it waits for the others; a success is also added to {@link #libraryChanges()}.
     */
    default CompletionStage<Reply<LibraryChange>> savePalette(String path, BlockPalette palette) {
        return CompletableFuture.completedFuture(Reply.refused(RejectReason.DISABLED, "not supported"));
    }

    /** Loads a library palette as the server knows it: states it doesn't know are left out and counted. */
    default CompletionStage<Reply<LoadedPalette>> loadPalette(String path) {
        return CompletableFuture.completedFuture(Reply.refused(RejectReason.DISABLED, "not supported"));
    }

    /** Downloads a clipboard as a Sponge v3 {@code .schem} file (the bytes; writing them is the caller's). */
    default Transfer<ExportedFile> export(UUID clipboardId) {
        return export(clipboardId, SchematicFormat.SPONGE);
    }

    /**
     * Downloads a clipboard as a file of {@code format}: Sponge {@code .schem}, Litematica {@code .litematic} or a
     * structure {@code .nbt} (the bytes; writing them is the caller's).
     */
    default Transfer<ExportedFile> export(UUID clipboardId, SchematicFormat format) {
        return Transfers.done(Transfer.Kind.EXPORT, "export", Reply.refused(RejectReason.DISABLED, "not supported"));
    }

    /**
     * Uploads a {@code .schem} file; once the server has read it, it is the player's clipboard. Files over the
     * server's {@code maxUploadBytes} are refused here.
     */
    default Transfer<ClipboardCache.Entry> upload(String fileName, byte[] bytes) {
        return Transfers.done(Transfer.Kind.UPLOAD, fileName, Reply.refused(RejectReason.DISABLED, "not supported"));
    }

    /**
     * Generators. Uploads a sparse clipboard made on this
     * client ({@code core.generate.SparseUpload.encode} bytes of {@code cells} cells with exactly {@code bounds});
     * once the server has read it, it is the player's clipboard, to be pasted at {@code bounds.min()} with the
     * identity transform and {@code PasteOptions(includeAir = true, physics = false, entities = false)}. Payloads over
     * the server's {@code maxUploadBytes} are refused here.
     */
    default Transfer<ClipboardCache.Entry> uploadGenerated(Box bounds, long cells, byte[] bytes) {
        return Transfers.done(Transfer.Kind.UPLOAD, "generated", Reply.refused(RejectReason.DISABLED, "not supported"));
    }

    /** Previews, exports and uploads in progress, oldest first. */
    default List<Transfer<?>> transfers() {
        return List.of();
    }

    // ---- M3: scatter ----

    /**
     * Plans a scatter on the server and downloads its placements. Only the latest preview counts: asking again
     * completes the previous one at once with {@link Reply.Failure#CANCELLED} (no toast), and the server replaces its
     * plan. The plan is held for {@link #SCATTER_PLAN_TTL_NANOS} or until the next preview or a commit. Every library
     * asset among the variants must have been previewed or loaded first ({@link #requestPreview}), so the server holds
     * it; the player's own clipboard needs nothing.
     */
    default CompletionStage<Reply<ScatterPreviewResult>> scatterPreview(ScatterPreviewRequest request) {
        return CompletableFuture.completedFuture(Reply.refused(RejectReason.DISABLED, "not supported"));
    }

    // ---- Tinker (protocol 5) ----
    //
    // Answers are Replies (the session toasts refusals and failures itself, with the server's detail such as "the block
    // changed meanwhile"). Each change is one history step on the server; the history state follows as usual.

    /** Whether the server answers Tinker requests (the feature was negotiated). */
    default boolean tinkerOffered() {
        return false;
    }

    /**
     * Changes the block at {@code pos}, which this client sees as state {@code expected}, to {@code target} (the same
     * block, other property values; {@code expected} to change only the text), and its sign text when {@code sign} is
     * not null. Refused, with nothing written, when the server's block is no longer {@code expected}.
     */
    default CompletionStage<Reply<Boolean>> tinkerBlock(BlockPos pos, int expected, int target, SignText sign) {
        return CompletableFuture.completedFuture(Reply.refused(RejectReason.DISABLED, "not supported"));
    }

    /**
     * Applies {@code edits} to the entity {@code id} (none: only looks at it) and answers with what the panel shows of it
     * afterwards.
     */
    default CompletionStage<Reply<EntityView>> tinkerEntity(UUID id, List<EntityEdit> edits) {
        return CompletableFuture.completedFuture(Reply.refused(RejectReason.DISABLED, "not supported"));
    }

    /** How long the server keeps a scatter plan after making it ({@code ScatterPlans.TTL_NANOS}). */
    long SCATTER_PLAN_TTL_NANOS = 10L * 60 * 1_000_000_000L;

    /**
     * Commits a scatter plan ({@code RunOp(ScatterCommit(planId))}): one job, one history entry. A plan is single-use;
     * an expired, replaced or spent one is refused with {@link RejectReason#INVALID}.
     */
    default CompletionStage<ToolResult> scatterCommit(UUID planId) {
        return send(new ToolAction.RunOp(new OpSpec.ScatterCommit(planId)));
    }
}
