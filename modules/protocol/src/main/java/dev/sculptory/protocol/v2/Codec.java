package dev.sculptory.protocol.v2;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.palette.BlockPalette;
import dev.sculptory.core.palette.PalettePattern;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.scatter.ScatterArea;
import dev.sculptory.core.scatter.ScatterSettings;
import dev.sculptory.core.scatter.ScatterSource;
import dev.sculptory.core.schem.SchematicFormat;
import dev.sculptory.core.state.StateSpace;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Frame codec: {@code varint type | body}, hand-written (varint, zigzag, length-capped UTF-8).
 *
 * <p>Block states cross the wire only as {@link StateSpace#format} strings in a per-message palette, converted
 * back with {@link StateSpace#parse}; raw handles never do. Messages that carry states ({@code StrokeBegin},
 * {@code RunOp}, {@code Copy}, {@code ScatterPreview}) write their leading id, then the palette, then the rest.
 * An unresolvable state fails with {@link ProtocolException.Reason#UNKNOWN_STATE}; no current message maps
 * unknown states to air.
 *
 * <p>Sealed variants ({@code OpSpec}, {@code Region}, {@code Pattern}, {@code CellMask}, {@code SurfaceMask},
 * {@code SourceRef}) are written with a one-byte tag. Decoding checks every length and count against its cap and
 * the bytes left before allocating, rejects trailing bytes, and turns invalid field values into
 * {@link ProtocolException.Reason#MALFORMED}.
 */
public final class Codec {
    private Codec() {}

    public static final int MAX_MOD_VERSION_BYTES = 64;
    /** Library paths and folders. */
    public static final int MAX_PATH_BYTES = 1024;
    /** File names, keys, reasons, history labels. */
    public static final int MAX_NAME_BYTES = 256;
    /** Free text: notice arguments, errors, stream meta values. */
    public static final int MAX_TEXT_BYTES = 1024;
    public static final int MAX_FEATURE_BYTES = 64;
    public static final int MAX_META_KEY_BYTES = 64;
    public static final int MAX_HASH_BYTES = 64;
    public static final int MAX_LIBRARY_ENTRIES = 4096;
    public static final int MAX_NOTICE_ARGS = 16;
    public static final int MAX_SCATTER_VARIANTS = ScatterSettings.MAX_VARIANTS;
    /** Painted disc stamps in one {@code ScatterPreview}. */
    public static final int MAX_SCATTER_STAMPS = 512;
    public static final int MAX_SCATTER_OUTCOMES = 64;

    // ScatterPreview area tags
    static final int SCATTER_AREA_BOX = 0;
    static final int SCATTER_AREA_STAMPS = 1;
    // ScatterPreview density tags
    static final int SCATTER_DENSITY_FRACTION = 0;
    static final int SCATTER_DENSITY_COUNT = 1;
    /** A scatter variant that is one block, after the paste-source tags (0 clipboard, 1 asset). */
    static final int SCATTER_VARIANT_BLOCK = 2;
    /** Protocol 5: a scatter variant that is a vanilla tree or feature, by its configured feature id. */
    static final int SCATTER_VARIANT_FEATURE = 3;

    /** Types whose body starts with an int request or stroke id (see {@link #peekLeadingId}). */
    private static final Set<MessageType> LEADING_ID = EnumSet.of(
            MessageType.STROKE_BEGIN, MessageType.DABS, MessageType.STROKE_END, MessageType.RUN_OP,
            MessageType.UNDO, MessageType.REDO, MessageType.COPY, MessageType.LIBRARY_LIST, MessageType.LIBRARY_LOAD,
            MessageType.SAVE_ASSET, MessageType.EXPORT_CLIPBOARD, MessageType.UPLOAD_BEGIN,
            MessageType.SCATTER_PREVIEW, MessageType.LIBRARY_MOVE, MessageType.LIBRARY_DELETE,
            MessageType.LIBRARY_CREATE_FOLDER, MessageType.JOB_ACCEPTED, MessageType.JOB_REJECTED,
            MessageType.CLIPBOARD_READY, MessageType.LIBRARY_LISTING, MessageType.ASSET_SAVED,
            MessageType.UPLOAD_GRANT, MessageType.UPLOAD_RESULT, MessageType.SCATTER_PLAN, MessageType.LIBRARY_CHANGED,
            MessageType.STREAM_OPEN, MessageType.STREAM_CHUNK, MessageType.STREAM_END, MessageType.STREAM_ABORT,
            MessageType.STREAM_CREDIT, MessageType.HISTORY_OVERWRITE, MessageType.PALETTE_SAVE, MessageType.PALETTE_LOAD,
            MessageType.PALETTE_DATA, MessageType.SELECTION_UPLOAD, MessageType.SELECTION_READY,
            MessageType.LIBRARY_ACCESS_GET, MessageType.LIBRARY_ACCESS_SET, MessageType.LIBRARY_ACCESS,
            MessageType.GENERATED_UPLOAD, MessageType.TINKER_BLOCK, MessageType.TINKER_ENTITY,
            MessageType.TINKER_RESULT, MessageType.BUILDER_PLACE, MessageType.BUILDER_BREAK,
            MessageType.BUILDER_DRAG_END, MessageType.SET_EDIT_MASK, MessageType.NAVIGATE, MessageType.EDIT_MASK_STATE,
            MessageType.NAVIGATE_RESULT);

    @FunctionalInterface
    private interface BodyWriter {
        void write(WireWriter body, StatePalette.Builder palette) throws ProtocolException;
    }

    // =================================================================== encode

    /**
     * @throws ProtocolException if the encoded frame would exceed {@link ProtocolV2#MAX_C2S_FRAME}, or a string
     *     or list exceeds its cap
     * @throws IllegalArgumentException if the message carries block states and {@code states} is null
     */
    public static byte[] encodeC2S(C2S message, StateSpace states) throws ProtocolException {
        Objects.requireNonNull(message);
        WireWriter out = new WireWriter(ProtocolV2.MAX_C2S_FRAME);
        out.varint(message.type().code());
        switch (message) {
            case C2S.Hello m -> {
                out.zigzag(m.minProtocol());
                out.zigzag(m.maxProtocol());
                out.string(m.modVersion(), MAX_MOD_VERSION_BYTES, "mod version");
                writeFeatures(out, m.features());
            }
            case C2S.StrokeBegin m -> {
                out.zigzag(m.strokeId());
                withPalette(out, states, (body, palette) -> CoreCodec.writeBrush(body, palette, m.spec()));
            }
            case C2S.Dabs m -> {
                out.zigzag(m.strokeId());
                out.zigzag(m.seq());
                out.count(m.dabs().size(), C2S.Dabs.MAX_DABS, "dabs");
                for (Dab dab : m.dabs()) CoreCodec.writeDab(out, dab);
            }
            case C2S.StrokeEnd m -> out.zigzag(m.strokeId());
            case C2S.Resync m -> CoreCodec.writeBox(out, m.box());
            case C2S.RunOp m -> {
                out.zigzag(m.reqId());
                withPalette(out, states, (body, palette) -> {
                    CoreCodec.writeOp(body, palette, m.op());
                    body.bool(m.physics());
                    body.enumValue(m.conflictPolicy());
                    body.enumValue(m.label());
                });
            }
            case C2S.CancelJob m -> out.uuid(m.jobId());
            case C2S.Undo m -> {
                out.zigzag(m.reqId());
                out.enumValue(m.policy());
            }
            case C2S.Redo m -> {
                out.zigzag(m.reqId());
                out.enumValue(m.policy());
            }
            case C2S.Copy m -> {
                out.zigzag(m.reqId());
                withPalette(out, states, (body, palette) -> {
                    CoreCodec.writeRegion(body, m.region());
                    CoreCodec.writePos(body, m.origin());
                    body.bool(m.cut());
                    CoreCodec.writeMask(body, palette, m.mask());
                    body.enumValue(m.entities());
                });
            }
            case C2S.PreviewRequest m -> CoreCodec.writeSource(out, m.source());
            case C2S.LibraryList m -> {
                out.zigzag(m.reqId());
                out.string(m.folder(), MAX_PATH_BYTES, "folder");
            }
            case C2S.LibraryLoad m -> {
                out.zigzag(m.reqId());
                out.string(m.path(), MAX_PATH_BYTES, "path");
            }
            case C2S.SaveAsset m -> {
                out.zigzag(m.reqId());
                out.uuid(m.clipboardId());
                out.string(m.path(), MAX_PATH_BYTES, "path");
            }
            case C2S.ExportClipboard m -> {
                out.zigzag(m.reqId());
                out.uuid(m.clipboardId());
                out.enumValue(m.format());
            }
            case C2S.UploadBegin m -> {
                out.zigzag(m.reqId());
                out.string(m.fileName(), MAX_NAME_BYTES, "file name");
                out.zigzagLong(m.totalBytes());
            }
            case C2S.ScatterPreview m -> {
                out.zigzag(m.reqId());
                withPalette(out, states, (body, palette) -> writeScatterPreview(body, palette, m));
            }
            case C2S.LibraryMove m -> {
                out.zigzag(m.reqId());
                out.bool(m.folder());
                out.string(m.from(), MAX_PATH_BYTES, "from");
                out.string(m.to(), MAX_PATH_BYTES, "to");
            }
            case C2S.LibraryDelete m -> {
                out.zigzag(m.reqId());
                out.bool(m.folder());
                out.string(m.path(), MAX_PATH_BYTES, "path");
            }
            case C2S.LibraryCreateFolder m -> {
                out.zigzag(m.reqId());
                out.string(m.path(), MAX_PATH_BYTES, "path");
            }
            case C2S.PaletteSave m -> {
                out.zigzag(m.reqId());
                out.string(m.path(), MAX_PATH_BYTES, "path");
                writePalette(out, m.palette());
            }
            case C2S.PaletteLoad m -> {
                out.zigzag(m.reqId());
                out.string(m.path(), MAX_PATH_BYTES, "path");
            }
            case C2S.LibraryAccessGet m -> {
                out.zigzag(m.reqId());
                out.string(m.path(), MAX_PATH_BYTES, "path");
            }
            case C2S.LibraryAccessSet m -> {
                out.zigzag(m.reqId());
                out.string(m.path(), MAX_PATH_BYTES, "path");
                writeAccess(out, m.access());
            }
            case C2S.HistoryOverwrite m -> {
                out.zigzag(m.reqId());
                out.bool(m.redo());
                out.varint(m.steps());
            }
            case C2S.SelectionUpload m -> {
                out.zigzag(m.reqId());
                out.raw(m.hash().bytes());
                CoreCodec.writeBox(out, m.bounds());
                out.varlong(m.cells());
                out.varlong(m.totalBytes());
            }
            case C2S.GeneratedUpload m -> {
                out.zigzag(m.reqId());
                CoreCodec.writeBox(out, m.bounds());
                out.varlong(m.cells());
                out.varlong(m.totalBytes());
            }
            case C2S.TinkerBlock m -> {
                out.zigzag(m.reqId());
                withPalette(out, states, (body, palette) -> TinkerCodec.writeBlock(body, palette, m));
            }
            case C2S.TinkerEntity m -> {
                out.zigzag(m.reqId());
                withPalette(out, states, (body, palette) -> TinkerCodec.writeEntity(body, palette, m));
            }
            case C2S.BuilderPowers m -> out.varint(m.powers());
            case C2S.BuilderPlace m -> {
                out.zigzag(m.seq());
                out.bool(m.offHand());
                CoreCodec.writePos(out, m.pos());
                out.enumValue(m.side());
                out.f32(m.hitX());
                out.f32(m.hitY());
                out.f32(m.hitZ());
                out.varint(m.powers());
                CoreCodec.writeSymmetry(out, m.symmetry());
            }
            case C2S.BuilderBreak m -> {
                out.zigzag(m.seq());
                out.zigzag(m.dragId());
                out.count(m.cells().size(), C2S.BuilderBreak.MAX_CELLS, "builder cells");
                for (BlockPos cell : m.cells()) CoreCodec.writePos(out, cell);
                out.varint(m.powers());
                CoreCodec.writeSymmetry(out, m.symmetry());
                out.bool(m.sameKind());
                out.bool(m.last());
            }
            case C2S.BuilderDragEnd m -> out.zigzag(m.dragId());
            case C2S.SetEditMask m -> {
                out.zigzag(m.reqId());
                CoreCodec.writeEditMask(out, m.mask());
            }
            case C2S.Navigate m -> {
                out.zigzag(m.reqId());
                out.enumValue(m.mode());
                CoreCodec.writePos(out, m.hit());
                out.enumValue(m.side());
                out.f32(m.dirX());
                out.f32(m.dirY());
                out.f32(m.dirZ());
            }
            case StreamOpen m -> writeStreamOpen(out, m);
            case StreamChunk m -> writeStreamChunk(out, m);
            case StreamEnd m -> writeStreamEnd(out, m);
            case StreamAbort m -> writeStreamAbort(out, m);
            case StreamCredit m -> writeStreamCredit(out, m);
        }
        return out.toByteArray();
    }

    /**
     * @throws ProtocolException if the encoded frame would exceed {@link ProtocolV2#MAX_S2C_FRAME}, or a string
     *     or list exceeds its cap
     */
    public static byte[] encodeS2C(S2C message, StateSpace states) throws ProtocolException {
        Objects.requireNonNull(message);
        WireWriter out = new WireWriter(ProtocolV2.MAX_S2C_FRAME);
        out.varint(message.type().code());
        switch (message) {
            case S2C.Welcome m -> {
                out.zigzag(m.protocol());
                writeFeatures(out, m.features());
                writeLimits(out, m.limits());
                out.varlong(m.permissions().bits());
                out.zigzagLong(m.sessionEpoch());
                out.string(m.serverBuild(), MAX_MOD_VERSION_BYTES, "server build");
            }
            case S2C.Incompatible m -> {
                out.zigzag(m.serverMinProtocol());
                out.zigzag(m.serverMaxProtocol());
                // Optional, so the body stays what clients before protocol 5 decode (Handshake.answer leaves it out).
                if (!m.serverBuild().isEmpty()) out.string(m.serverBuild(), MAX_MOD_VERSION_BYTES, "server build");
            }
            case S2C.PermissionsChanged m -> {
                out.varlong(m.permissions().bits());
                writeLimits(out, m.limits());
            }
            case S2C.StrokeStatus m -> {
                out.zigzag(m.strokeId());
                out.zigzag(m.ackedIndex());
                out.enumValue(m.status());
                if (m.status() == S2C.StrokeStatus.Status.REJECTED) out.enumValue(m.reason());
                out.zigzag(m.appliedIndex());
            }
            case S2C.JobAccepted m -> {
                out.zigzag(m.reqId());
                out.uuid(m.jobId());
                out.zigzagLong(m.estCells());
            }
            case S2C.JobRejected m -> {
                out.zigzag(m.reqId());
                out.enumValue(m.reason());
            }
            case S2C.JobProgress m -> {
                out.uuid(m.jobId());
                out.zigzagLong(m.done());
                out.zigzagLong(m.total());
                out.enumValue(m.phase());
            }
            case S2C.JobFinished m -> {
                out.uuid(m.jobId());
                out.enumValue(m.outcome());
                out.zigzagLong(m.changed());
                out.zigzagLong(m.skippedProtected());
                out.zigzagLong(m.skippedConflicts());
                out.zigzagLong(m.strippedNbt());
            }
            case S2C.HistoryState m -> {
                out.bool(m.canUndo());
                out.bool(m.canRedo());
                out.string(m.undoLabel(), MAX_NAME_BYTES, "undo label");
                out.string(m.redoLabel(), MAX_NAME_BYTES, "redo label");
                out.zigzagLong(m.bytes());
                writeStrings(out, m.undoLabels(), S2C.HistoryState.MAX_LABELS, MAX_NAME_BYTES, "undo labels");
                writeStrings(out, m.redoLabels(), S2C.HistoryState.MAX_LABELS, MAX_NAME_BYTES, "redo labels");
            }
            case S2C.ClipboardReady m -> {
                out.zigzag(m.reqId());
                out.uuid(m.clipboardId());
                CoreCodec.writePos(out, m.dims());
                CoreCodec.writePos(out, m.anchor());
                out.zigzagLong(m.cells());
                out.zigzagLong(m.bytes());
                out.varint(m.entities());
            }
            case S2C.SelectionReady m -> {
                out.zigzag(m.reqId());
                out.raw(m.hash().bytes());
            }
            case S2C.LibraryListing m -> {
                out.zigzag(m.reqId());
                out.string(m.folder(), MAX_PATH_BYTES, "folder");
                out.count(m.entries().size(), MAX_LIBRARY_ENTRIES, "library entries");
                for (S2C.LibraryListing.Entry entry : m.entries()) {
                    out.string(entry.path(), MAX_PATH_BYTES, "path");
                    out.bool(entry.folder());
                    out.zigzagLong(entry.bytes());
                    out.string(entry.contentHash(), MAX_HASH_BYTES, "content hash");
                    out.enumValue(entry.kind());
                    out.bool(entry.restricted());
                }
                out.bool(m.writable());
            }
            case S2C.LibraryAccess m -> {
                out.zigzag(m.reqId());
                out.string(m.path(), MAX_PATH_BYTES, "path");
                writeAccess(out, m.access());
            }
            case S2C.AssetSaved m -> {
                out.zigzag(m.reqId());
                out.string(m.path(), MAX_PATH_BYTES, "path");
                out.string(m.contentHash(), MAX_HASH_BYTES, "content hash");
            }
            case S2C.UploadGrant m -> {
                out.zigzag(m.reqId());
                out.zigzag(m.streamId());
                out.zigzagLong(m.creditBytes());
            }
            case S2C.UploadResult m -> {
                out.zigzag(m.reqId());
                if (m.clipboardId() != null) {
                    out.u8(0);
                    out.uuid(m.clipboardId());
                } else {
                    out.u8(1);
                    out.string(m.error(), MAX_TEXT_BYTES, "upload error");
                }
            }
            case S2C.ScatterPlan m -> {
                out.zigzag(m.reqId());
                out.uuid(m.planId());
                out.zigzag(m.placements());
                out.count(m.rejectedCounts().size(), MAX_SCATTER_OUTCOMES, "scatter outcomes");
                for (Map.Entry<String, Integer> entry : m.rejectedCounts().entrySet()) {
                    out.string(entry.getKey(), MAX_NAME_BYTES, "scatter outcome");
                    out.zigzag(entry.getValue());
                }
                out.zigzagLong(m.totalCells());
                out.bool(m.bounds() != null);
                if (m.bounds() != null) CoreCodec.writeBox(out, m.bounds());
            }
            case S2C.Notice m -> {
                out.enumValue(m.level());
                out.string(m.key(), MAX_NAME_BYTES, "notice key");
                writeStrings(out, m.args(), MAX_NOTICE_ARGS, MAX_TEXT_BYTES, "notice args");
            }
            case S2C.LibraryChanged m -> {
                out.zigzag(m.reqId());
                out.bool(m.folder());
                out.string(m.from(), MAX_PATH_BYTES, "from");
                out.string(m.to(), MAX_PATH_BYTES, "to");
            }
            case S2C.TinkerResult m -> TinkerCodec.writeResult(out, m);
            case S2C.EditMaskState m -> {
                out.zigzag(m.reqId());
                out.bool(m.reason() != null);
                if (m.reason() != null) {
                    out.enumValue(m.reason());
                    out.string(m.detail(), MAX_TEXT_BYTES, "edit mask detail");
                }
            }
            case S2C.NavigateResult m -> {
                out.zigzag(m.reqId());
                out.bool(m.reason() != null);
                if (m.reason() != null) {
                    out.enumValue(m.reason());
                } else {
                    CoreCodec.writePos(out, m.feet());
                }
            }
            case S2C.PaletteData m -> {
                out.zigzag(m.reqId());
                out.string(m.path(), MAX_PATH_BYTES, "path");
                writePalette(out, m.palette());
                out.varint(m.dropped());
                writeStrings(out, m.droppedStates(), S2C.PaletteData.MAX_SHOWN_DROPPED, BlockPalette.MAX_STATE_BYTES,
                        "dropped states");
            }
            case StreamOpen m -> writeStreamOpen(out, m);
            case StreamChunk m -> writeStreamChunk(out, m);
            case StreamEnd m -> writeStreamEnd(out, m);
            case StreamAbort m -> writeStreamAbort(out, m);
            case StreamCredit m -> writeStreamCredit(out, m);
        }
        return out.toByteArray();
    }

    // =================================================================== decode

    /**
     * @throws ProtocolException for malformed or oversized input, an unknown or server-to-client type, or an
     *     unresolvable block state
     */
    public static C2S decodeC2S(byte[] frame, StateSpace states) throws ProtocolException {
        return (C2S) decode(frame, states, true);
    }

    /**
     * @throws ProtocolException for malformed or oversized input, an unknown or client-to-server type, or an
     *     unresolvable block state
     */
    public static S2C decodeS2C(byte[] frame, StateSpace states) throws ProtocolException {
        return (S2C) decode(frame, states, false);
    }

    /** The frame's message type, or {@code null} if the type code is missing or unknown. Never throws. */
    public static MessageType peekType(byte[] frame) {
        if (frame == null || frame.length == 0) return null;
        try {
            return MessageType.fromCode(new WireReader(frame).varint());
        } catch (ProtocolException e) {
            return null;
        }
    }

    /**
     * The request, stroke or stream id at the start of a body (e.g. {@code RunOp.reqId},
     * {@code StrokeBegin.strokeId}, {@code StreamChunk.id}), readable without decoding the rest of the frame, so a
     * receiver can answer a request it will not decode. Empty for types without a leading id or when the id
     * itself is unreadable. Never throws.
     */
    public static OptionalInt peekLeadingId(byte[] frame) {
        if (frame == null || frame.length == 0) return OptionalInt.empty();
        try {
            WireReader in = new WireReader(frame);
            MessageType type = MessageType.fromCode(in.varint());
            if (type == null || !LEADING_ID.contains(type)) return OptionalInt.empty();
            return OptionalInt.of(in.zigzag());
        } catch (ProtocolException e) {
            return OptionalInt.empty();
        }
    }

    private static Message decode(byte[] frame, StateSpace states, boolean clientToServer) throws ProtocolException {
        Objects.requireNonNull(frame);
        int max = clientToServer ? ProtocolV2.MAX_C2S_FRAME : ProtocolV2.MAX_S2C_FRAME;
        if (frame.length > max) throw WireReader.tooLarge("Frame of " + frame.length + " bytes over " + max);
        if (frame.length == 0) throw WireReader.malformed("Empty frame");
        WireReader in = new WireReader(frame);
        try {
            int code = in.varint();
            MessageType type = MessageType.fromCode(code);
            if (type == null) {
                throw new ProtocolException(ProtocolException.Reason.UNKNOWN_TYPE, "Unknown message type " + code);
            }
            if (clientToServer ? !type.clientToServer() : !type.serverToClient()) {
                throw new ProtocolException(ProtocolException.Reason.UNKNOWN_TYPE, type + " sent in the wrong direction");
            }
            Message message = clientToServer ? readC2S(type, in, states) : readS2C(type, in, states);
            in.expectEnd();
            return message;
        } catch (IllegalArgumentException | NullPointerException | ArithmeticException | IndexOutOfBoundsException e) {
            // A record constructor rejected a decoded value (e.g. an inverted box or an out-of-range radius).
            throw new ProtocolException(ProtocolException.Reason.MALFORMED, "Invalid field: " + e.getMessage(), e);
        }
    }

    private static C2S readC2S(MessageType type, WireReader in, StateSpace states) throws ProtocolException {
        return switch (type) {
            case HELLO -> {
                int min = in.zigzag();
                int max = in.zigzag();
                String modVersion = in.string(MAX_MOD_VERSION_BYTES, "mod version");
                yield new C2S.Hello(min, max, modVersion, readFeatures(in));
            }
            case STROKE_BEGIN -> {
                int strokeId = in.zigzag();
                StatePalette.Table palette = StatePalette.Table.read(in, states);
                yield new C2S.StrokeBegin(strokeId, CoreCodec.readBrush(in, palette));
            }
            case DABS -> {
                int strokeId = in.zigzag();
                int seq = in.zigzag();
                int count = in.count(C2S.Dabs.MAX_DABS, "dabs");
                List<Dab> dabs = new ArrayList<>(count);
                for (int i = 0; i < count; i++) dabs.add(CoreCodec.readDab(in));
                yield new C2S.Dabs(strokeId, seq, dabs);
            }
            case STROKE_END -> new C2S.StrokeEnd(in.zigzag());
            case RESYNC -> new C2S.Resync(CoreCodec.readBox(in));
            case RUN_OP -> {
                int reqId = in.zigzag();
                StatePalette.Table palette = StatePalette.Table.read(in, states);
                var op = CoreCodec.readOp(in, palette);
                boolean physics = in.bool();
                ConflictPolicy policy = in.enumOf(ConflictPolicy.values(), "conflict policy");
                yield new C2S.RunOp(reqId, op, physics, policy, in.enumOf(OpLabel.values(), "op label"));
            }
            case CANCEL_JOB -> new C2S.CancelJob(in.uuid());
            case UNDO -> {
                int reqId = in.zigzag();
                yield new C2S.Undo(reqId, in.enumOf(ConflictPolicy.values(), "conflict policy"));
            }
            case REDO -> {
                int reqId = in.zigzag();
                yield new C2S.Redo(reqId, in.enumOf(ConflictPolicy.values(), "conflict policy"));
            }
            case COPY -> {
                int reqId = in.zigzag();
                StatePalette.Table palette = StatePalette.Table.read(in, states);
                Region region = CoreCodec.readRegion(in);
                BlockPos origin = CoreCodec.readPos(in);
                boolean cut = in.bool();
                CellMask mask = CoreCodec.readMask(in, palette);
                yield new C2S.Copy(reqId, region, origin, cut, mask, CoreCodec.readEntityFilter(in));
            }
            case PREVIEW_REQUEST -> new C2S.PreviewRequest(CoreCodec.readSource(in));
            case LIBRARY_LIST -> {
                int reqId = in.zigzag();
                yield new C2S.LibraryList(reqId, in.string(MAX_PATH_BYTES, "folder"));
            }
            case LIBRARY_LOAD -> {
                int reqId = in.zigzag();
                yield new C2S.LibraryLoad(reqId, in.string(MAX_PATH_BYTES, "path"));
            }
            case SAVE_ASSET -> {
                int reqId = in.zigzag();
                UUID clipboardId = in.uuid();
                yield new C2S.SaveAsset(reqId, clipboardId, in.string(MAX_PATH_BYTES, "path"));
            }
            case EXPORT_CLIPBOARD -> {
                int reqId = in.zigzag();
                UUID clipboardId = in.uuid();
                yield new C2S.ExportClipboard(reqId, clipboardId, in.enumOf(SchematicFormat.values(), "export format"));
            }
            case UPLOAD_BEGIN -> {
                int reqId = in.zigzag();
                String fileName = in.string(MAX_NAME_BYTES, "file name");
                yield new C2S.UploadBegin(reqId, fileName, in.zigzagLong());
            }
            case SCATTER_PREVIEW -> {
                int reqId = in.zigzag();
                StatePalette.Table palette = StatePalette.Table.read(in, states);
                yield readScatterPreview(reqId, in, palette);
            }
            case LIBRARY_MOVE -> {
                int reqId = in.zigzag();
                boolean folder = in.bool();
                String from = in.string(MAX_PATH_BYTES, "from");
                yield new C2S.LibraryMove(reqId, folder, from, in.string(MAX_PATH_BYTES, "to"));
            }
            case LIBRARY_DELETE -> {
                int reqId = in.zigzag();
                boolean folder = in.bool();
                yield new C2S.LibraryDelete(reqId, folder, in.string(MAX_PATH_BYTES, "path"));
            }
            case LIBRARY_CREATE_FOLDER -> {
                int reqId = in.zigzag();
                yield new C2S.LibraryCreateFolder(reqId, in.string(MAX_PATH_BYTES, "path"));
            }
            case PALETTE_SAVE -> {
                int reqId = in.zigzag();
                String path = in.string(MAX_PATH_BYTES, "path");
                yield new C2S.PaletteSave(reqId, path, readPalette(in));
            }
            case PALETTE_LOAD -> {
                int reqId = in.zigzag();
                yield new C2S.PaletteLoad(reqId, in.string(MAX_PATH_BYTES, "path"));
            }
            case LIBRARY_ACCESS_GET -> {
                int reqId = in.zigzag();
                yield new C2S.LibraryAccessGet(reqId, in.string(MAX_PATH_BYTES, "path"));
            }
            case LIBRARY_ACCESS_SET -> {
                int reqId = in.zigzag();
                String path = in.string(MAX_PATH_BYTES, "path");
                yield new C2S.LibraryAccessSet(reqId, path, readAccess(in, true));
            }
            case HISTORY_OVERWRITE -> {
                int reqId = in.zigzag();
                boolean redo = in.bool();
                int steps = in.varint();
                if (steps < 1 || steps > C2S.HistoryOverwrite.MAX_STEPS) {
                    throw WireReader.malformed("Invalid history step count " + steps);
                }
                yield new C2S.HistoryOverwrite(reqId, redo, steps);
            }
            case SELECTION_UPLOAD -> {
                int reqId = in.zigzag();
                Sha256 hash = Sha256.ofBytes(in.raw(32));
                Box bounds = CoreCodec.readBox(in);
                long cells = in.varlong();
                yield new C2S.SelectionUpload(reqId, hash, bounds, cells, in.varlong());
            }
            case GENERATED_UPLOAD -> {
                int reqId = in.zigzag();
                Box bounds = CoreCodec.readBox(in);
                long cells = in.varlong();
                long totalBytes = in.varlong();
                if (cells < 1 || cells > bounds.volume() || totalBytes < 1) {
                    throw WireReader.malformed("A generated clipboard of " + cells + " cells in " + totalBytes + " bytes");
                }
                yield new C2S.GeneratedUpload(reqId, bounds, cells, totalBytes);
            }
            case TINKER_BLOCK -> {
                int reqId = in.zigzag();
                StatePalette.Table palette = StatePalette.Table.read(in, states);
                yield TinkerCodec.readBlock(reqId, in, palette);
            }
            case TINKER_ENTITY -> {
                int reqId = in.zigzag();
                StatePalette.Table palette = StatePalette.Table.read(in, states);
                yield TinkerCodec.readEntity(reqId, in, palette);
            }
            case BUILDER_POWERS -> new C2S.BuilderPowers(in.varint());
            case BUILDER_PLACE -> {
                int seq = in.zigzag();
                boolean offHand = in.bool();
                BlockPos pos = CoreCodec.readPos(in);
                Facing side = in.enumOf(Facing.values(), "side");
                float hitX = in.f32();
                float hitY = in.f32();
                float hitZ = in.f32();
                int powers = in.varint();
                yield new C2S.BuilderPlace(seq, offHand, pos, side, hitX, hitY, hitZ, powers, CoreCodec.readSymmetry(in));
            }
            case BUILDER_BREAK -> {
                int seq = in.zigzag();
                int dragId = in.zigzag();
                int count = in.count(C2S.BuilderBreak.MAX_CELLS, "builder cells");
                List<BlockPos> cells = new ArrayList<>(count);
                for (int i = 0; i < count; i++) cells.add(CoreCodec.readPos(in));
                int powers = in.varint();
                var symmetry = CoreCodec.readSymmetry(in);
                boolean sameKind = in.bool();
                yield new C2S.BuilderBreak(seq, dragId, cells, powers, symmetry, sameKind, in.bool());
            }
            case BUILDER_DRAG_END -> new C2S.BuilderDragEnd(in.zigzag());
            case SET_EDIT_MASK -> {
                int reqId = in.zigzag();
                yield new C2S.SetEditMask(reqId, CoreCodec.readEditMask(in));
            }
            case NAVIGATE -> {
                int reqId = in.zigzag();
                NavigateMode mode = in.enumOf(NavigateMode.values(), "navigate mode");
                BlockPos hit = CoreCodec.readPos(in);
                Facing side = in.enumOf(Facing.values(), "side");
                float dirX = in.f32();
                float dirY = in.f32();
                // Navigate refuses a direction that is not finite or is zero (MALFORMED).
                yield new C2S.Navigate(reqId, mode, hit, side, dirX, dirY, in.f32());
            }
            case STREAM_OPEN, STREAM_CHUNK, STREAM_END, STREAM_ABORT, STREAM_CREDIT -> (C2S) readStream(type, in);
            default -> throw new ProtocolException(ProtocolException.Reason.UNKNOWN_TYPE, type + " is not client-to-server");
        };
    }

    private static S2C readS2C(MessageType type, WireReader in, StateSpace states) throws ProtocolException {
        return switch (type) {
            case WELCOME -> {
                int protocol = in.zigzag();
                Features features = readFeatures(in);
                Limits limits = readLimits(in);
                PermissionMask permissions = new PermissionMask(in.varlong());
                long sessionEpoch = in.zigzagLong();
                yield new S2C.Welcome(protocol, features, limits, permissions, sessionEpoch,
                        in.string(MAX_MOD_VERSION_BYTES, "server build"));
            }
            case INCOMPATIBLE -> {
                int min = in.zigzag();
                int max = in.zigzag();
                // The server build is optional (absent for clients before protocol 5, or when unknown); when present it
                // is not empty, so each Incompatible has one encoding.
                String serverBuild = "";
                if (in.remaining() > 0) {
                    serverBuild = in.string(MAX_MOD_VERSION_BYTES, "server build");
                    if (serverBuild.isEmpty()) throw WireReader.malformed("An empty server build in Incompatible");
                }
                yield new S2C.Incompatible(min, max, serverBuild);
            }
            case PERMISSIONS_CHANGED -> {
                PermissionMask permissions = new PermissionMask(in.varlong());
                yield new S2C.PermissionsChanged(permissions, readLimits(in));
            }
            case STROKE_STATUS -> {
                int strokeId = in.zigzag();
                int ackedIndex = in.zigzag();
                S2C.StrokeStatus.Status status = in.enumOf(S2C.StrokeStatus.Status.values(), "stroke status");
                RejectReason reason = status == S2C.StrokeStatus.Status.REJECTED
                        ? in.enumOf(RejectReason.values(), "reject reason")
                        : null;
                int appliedIndex = in.zigzag();
                yield new S2C.StrokeStatus(strokeId, ackedIndex, status, reason, appliedIndex);
            }
            case JOB_ACCEPTED -> {
                int reqId = in.zigzag();
                UUID jobId = in.uuid();
                yield new S2C.JobAccepted(reqId, jobId, in.zigzagLong());
            }
            case JOB_REJECTED -> {
                int reqId = in.zigzag();
                yield new S2C.JobRejected(reqId, in.enumOf(RejectReason.values(), "reject reason"));
            }
            case JOB_PROGRESS -> {
                UUID jobId = in.uuid();
                long done = in.zigzagLong();
                long total = in.zigzagLong();
                yield new S2C.JobProgress(jobId, done, total, in.enumOf(Phase.values(), "phase"));
            }
            case JOB_FINISHED -> {
                UUID jobId = in.uuid();
                JobOutcome outcome = in.enumOf(JobOutcome.values(), "job outcome");
                long changed = in.zigzagLong();
                long skippedProtected = in.zigzagLong();
                long skippedConflicts = in.zigzagLong();
                yield new S2C.JobFinished(jobId, outcome, changed, skippedProtected, skippedConflicts, in.zigzagLong());
            }
            case HISTORY_STATE -> {
                boolean canUndo = in.bool();
                boolean canRedo = in.bool();
                String undoLabel = in.string(MAX_NAME_BYTES, "undo label");
                String redoLabel = in.string(MAX_NAME_BYTES, "redo label");
                long bytes = in.zigzagLong();
                List<String> undoLabels = readStrings(in, S2C.HistoryState.MAX_LABELS, MAX_NAME_BYTES, "undo labels");
                List<String> redoLabels = readStrings(in, S2C.HistoryState.MAX_LABELS, MAX_NAME_BYTES, "redo labels");
                yield new S2C.HistoryState(canUndo, canRedo, undoLabel, redoLabel, bytes, undoLabels, redoLabels);
            }
            case CLIPBOARD_READY -> {
                int reqId = in.zigzag();
                UUID clipboardId = in.uuid();
                BlockPos dims = CoreCodec.readPos(in);
                BlockPos anchor = CoreCodec.readPos(in);
                long cells = in.zigzagLong();
                long bytes = in.zigzagLong();
                yield new S2C.ClipboardReady(reqId, clipboardId, dims, anchor, cells, bytes, in.varint());
            }
            case LIBRARY_LISTING -> {
                int reqId = in.zigzag();
                String folder = in.string(MAX_PATH_BYTES, "folder");
                int count = in.count(MAX_LIBRARY_ENTRIES, "library entries");
                List<S2C.LibraryListing.Entry> entries = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    String path = in.string(MAX_PATH_BYTES, "path");
                    boolean isFolder = in.bool();
                    long bytes = in.zigzagLong();
                    String hash = in.string(MAX_HASH_BYTES, "content hash");
                    S2C.LibraryListing.Entry.Kind kind = in.enumOf(S2C.LibraryListing.Entry.Kind.values(), "library entry kind");
                    boolean restricted = in.bool();
                    if (isFolder && restricted) throw WireReader.malformed("A folder entry marked restricted");
                    entries.add(new S2C.LibraryListing.Entry(path, isFolder, bytes, hash, kind, restricted));
                }
                yield new S2C.LibraryListing(reqId, folder, entries, in.bool());
            }
            case LIBRARY_ACCESS -> {
                int reqId = in.zigzag();
                String path = in.string(MAX_PATH_BYTES, "path");
                yield new S2C.LibraryAccess(reqId, path, readAccess(in, false));
            }
            case ASSET_SAVED -> {
                int reqId = in.zigzag();
                String path = in.string(MAX_PATH_BYTES, "path");
                yield new S2C.AssetSaved(reqId, path, in.string(MAX_HASH_BYTES, "content hash"));
            }
            case UPLOAD_GRANT -> {
                int reqId = in.zigzag();
                int streamId = in.zigzag();
                yield new S2C.UploadGrant(reqId, streamId, in.zigzagLong());
            }
            case UPLOAD_RESULT -> {
                int reqId = in.zigzag();
                int tag = in.u8();
                yield switch (tag) {
                    case 0 -> new S2C.UploadResult(reqId, in.uuid(), null);
                    case 1 -> new S2C.UploadResult(reqId, null, in.string(MAX_TEXT_BYTES, "upload error"));
                    default -> throw WireReader.malformed("Unknown upload result tag " + tag);
                };
            }
            case SCATTER_PLAN -> {
                int reqId = in.zigzag();
                UUID planId = in.uuid();
                int placements = in.zigzag();
                int count = in.count(MAX_SCATTER_OUTCOMES, "scatter outcomes");
                TreeMap<String, Integer> rejected = new TreeMap<>();
                for (int i = 0; i < count; i++) {
                    String key = in.string(MAX_NAME_BYTES, "scatter outcome");
                    if (rejected.put(key, in.zigzag()) != null) throw WireReader.malformed("Duplicate scatter outcome");
                }
                long totalCells = in.zigzagLong();
                Box bounds = in.bool() ? CoreCodec.readBox(in) : null;
                yield new S2C.ScatterPlan(reqId, planId, placements, rejected, totalCells, bounds);
            }
            case NOTICE -> {
                S2C.Notice.Level level = in.enumOf(S2C.Notice.Level.values(), "notice level");
                String key = in.string(MAX_NAME_BYTES, "notice key");
                yield new S2C.Notice(level, key, readStrings(in, MAX_NOTICE_ARGS, MAX_TEXT_BYTES, "notice args"));
            }
            case LIBRARY_CHANGED -> {
                int reqId = in.zigzag();
                boolean folder = in.bool();
                String from = in.string(MAX_PATH_BYTES, "from");
                yield new S2C.LibraryChanged(reqId, folder, from, in.string(MAX_PATH_BYTES, "to"));
            }
            case PALETTE_DATA -> {
                int reqId = in.zigzag();
                String path = in.string(MAX_PATH_BYTES, "path");
                BlockPalette palette = readPalette(in);
                int dropped = in.varint();
                yield new S2C.PaletteData(reqId, path, palette, dropped, readStrings(in, S2C.PaletteData.MAX_SHOWN_DROPPED,
                        BlockPalette.MAX_STATE_BYTES, "dropped states"));
            }
            case SELECTION_READY -> {
                int reqId = in.zigzag();
                yield new S2C.SelectionReady(reqId, Sha256.ofBytes(in.raw(32)));
            }
            case TINKER_RESULT -> TinkerCodec.readResult(in);
            case EDIT_MASK_STATE -> {
                int reqId = in.zigzag();
                if (!in.bool()) yield S2C.EditMaskState.accepted(reqId);
                RejectReason reason = in.enumOf(RejectReason.values(), "reject reason");
                yield S2C.EditMaskState.refused(reqId, reason, in.string(MAX_TEXT_BYTES, "edit mask detail"));
            }
            case NAVIGATE_RESULT -> {
                int reqId = in.zigzag();
                yield in.bool()
                        ? S2C.NavigateResult.refused(reqId, in.enumOf(RejectReason.values(), "reject reason"))
                        : S2C.NavigateResult.landed(reqId, CoreCodec.readPos(in));
            }
            case STREAM_OPEN, STREAM_CHUNK, STREAM_END, STREAM_ABORT, STREAM_CREDIT -> (S2C) readStream(type, in);
            default -> throw new ProtocolException(ProtocolException.Reason.UNKNOWN_TYPE, type + " is not server-to-client");
        };
    }

    // =================================================================== shared pieces

    private static void withPalette(WireWriter out, StateSpace states, BodyWriter writer) throws ProtocolException {
        StatePalette.Builder palette = new StatePalette.Builder(states);
        WireWriter body = new WireWriter(out.max());
        writer.write(body, palette);
        palette.writeTo(out);
        out.raw(body);
    }

    private static void writeFeatures(WireWriter out, Features features) throws ProtocolException {
        out.count(features.names().size(), Features.MAX_FEATURES, "features");
        for (String name : features.names()) out.string(name, MAX_FEATURE_BYTES, "feature");
    }

    private static Features readFeatures(WireReader in) throws ProtocolException {
        int count = in.count(Features.MAX_FEATURES, "features");
        TreeSet<String> names = new TreeSet<>();
        for (int i = 0; i < count; i++) names.add(in.string(MAX_FEATURE_BYTES, "feature"));
        return new Features(names);
    }

    private static void writeLimits(WireWriter out, Limits limits) throws ProtocolException {
        out.zigzagLong(limits.maxOpVolume());
        out.zigzagLong(limits.maxClipboardVolume());
        out.zigzag(limits.maxBrushRadius());
        out.zigzag(limits.maxDabRate());
        out.zigzagLong(limits.maxUploadBytes());
        out.zigzag(limits.maxJobsPerPlayer());
        out.zigzagLong(limits.maxSelectionCells());
        out.zigzag(limits.maxSelectionSections());
    }

    private static Limits readLimits(WireReader in) throws ProtocolException {
        long maxOpVolume = in.zigzagLong();
        long maxClipboardVolume = in.zigzagLong();
        int maxBrushRadius = in.zigzag();
        int maxDabRate = in.zigzag();
        long maxUploadBytes = in.zigzagLong();
        int maxJobsPerPlayer = in.zigzag();
        return new Limits(maxOpVolume, maxClipboardVolume, maxBrushRadius, maxDabRate, maxUploadBytes, maxJobsPerPlayer,
                in.zigzagLong(), in.zigzag());
    }

    private static void writeStrings(WireWriter out, List<String> values, int maxCount, int maxBytes, String what)
            throws ProtocolException {
        out.count(values.size(), maxCount, what);
        for (String value : values) out.string(value, maxBytes, what);
    }

    private static List<String> readStrings(WireReader in, int maxCount, int maxBytes, String what)
            throws ProtocolException {
        int count = in.count(maxCount, what);
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) values.add(in.string(maxBytes, what));
        return values;
    }

    /**
     * Per-asset access: {@code enum mode | count players (≤ 256) | per player: bool hasUuid, [uuid], string name}. A
     * listed access with no players, one for everyone with players, a repeated UUID, an empty name or one over
     * {@value AssetAccess#MAX_NAME_BYTES} bytes is {@code MALFORMED}; so is a grantee without a UUID where
     * {@code namesAllowed} is false (the server always answers with UUIDs).
     */
    private static void writeAccess(WireWriter out, AssetAccess access) throws ProtocolException {
        out.enumValue(access.mode());
        out.count(access.players().size(), AssetAccess.MAX_PLAYERS, "granted players");
        for (AssetAccess.Grantee grantee : access.players()) {
            out.bool(grantee.uuid() != null);
            if (grantee.uuid() != null) out.uuid(grantee.uuid());
            out.string(grantee.name(), AssetAccess.MAX_NAME_BYTES, "player name");
        }
    }

    private static AssetAccess readAccess(WireReader in, boolean namesAllowed) throws ProtocolException {
        AssetAccess.Mode mode = in.enumOf(AssetAccess.Mode.values(), "access mode");
        int count = in.count(AssetAccess.MAX_PLAYERS, "granted players");
        List<AssetAccess.Grantee> players = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID uuid = in.bool() ? in.uuid() : null;
            if (uuid == null && !namesAllowed) throw WireReader.malformed("A granted player without a UUID");
            String name = in.string(AssetAccess.MAX_NAME_BYTES, "player name");
            if (name.isEmpty()) throw WireReader.malformed("An empty player name");
            players.add(new AssetAccess.Grantee(uuid, name));
        }
        try {
            return new AssetAccess(mode, players);
        } catch (IllegalArgumentException e) {
            throw WireReader.malformed(e.getMessage());
        }
    }

    /**
     * A block palette (palettes): varint count (1-{@value BlockPalette#MAX_ENTRIES}), per entry its state text (UTF-8, at
     * most {@value BlockPalette#MAX_STATE_BYTES} bytes, not resolved here) and a varint weight
     * (1-{@value BlockPalette#MAX_WEIGHT}), then (protocol 5) its pattern:
     * {@code varint kind | varint patch size | varint edge | varint steepness edge | zigzag64 seed}. No count, a weight
     * outside its range, a state listed twice, an unknown pattern kind or a pattern value out of its range is
     * {@code MALFORMED}.
     */
    private static void writePalette(WireWriter out, BlockPalette palette) throws ProtocolException {
        out.count(palette.entries().size(), BlockPalette.MAX_ENTRIES, "palette entries");
        for (BlockPalette.Entry entry : palette.entries()) {
            out.string(entry.state(), BlockPalette.MAX_STATE_BYTES, "palette state");
            out.varint(entry.weight());
        }
        PalettePattern pattern = palette.pattern();
        out.enumValue(pattern.kind());
        out.varint(pattern.patchSize());
        out.varint(pattern.edge());
        out.varint(pattern.steepnessEdge());
        out.zigzagLong(pattern.seed());
    }

    private static BlockPalette readPalette(WireReader in) throws ProtocolException {
        int count = in.count(BlockPalette.MAX_ENTRIES, "palette entries");
        List<BlockPalette.Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String state = in.string(BlockPalette.MAX_STATE_BYTES, "palette state");
            entries.add(new BlockPalette.Entry(state, in.varint()));
        }
        PalettePattern.Kind kind = in.enumOf(PalettePattern.Kind.values(), "palette pattern");
        int patchSize = in.varint();
        int edge = in.varint();
        int steepnessEdge = in.varint();
        return new BlockPalette(entries, new PalettePattern(kind, patchSize, edge, steepnessEdge, in.zigzagLong()));
    }

    /**
     * {@code area | seed | spacing | density | surface | allowInFluid | minSupportFraction | survive | variants |
     * transforms | columnHeight}.
     * <ul>
     *   <li>area: u8 tag; 0 → box; 1 → varint count (at most {@value #MAX_SCATTER_STAMPS}), per stamp zigzag dx and
     *       dz (from the previous stamp's centre; the first from 0, 0) and u8 {@code radius << 1 | erase}.</li>
     *   <li>density: u8 tag; 0 → fraction as the i64 bits of the double; 1 → varint target count.</li>
     *   <li>minSupportFraction: the i64 bits of the double. Doubles travel exactly, so both sides plan alike.</li>
     *   <li>variants: varint count (at most {@value #MAX_SCATTER_VARIANTS}), per variant a u8 tag and a varint
     *       weight: tag 0 or 1 a paste source (clipboard id, asset hash, as elsewhere), tag 2 a block as its state
     *       text (UTF-8, at most {@value ScatterSource#MAX_STATE_BYTES} bytes; the server resolves it), tag 3
     *       (protocol 5) a tree or feature by its configured feature id (as long).</li>
     *   <li>transforms: u8 turn mask, bool mirror.</li>
     *   <li>columnHeight: u8 min, u8 max (1-{@value ScatterSettings#MAX_COLUMN_HEIGHT}, min &lt;= max); how tall column
     *       plants grow (1, 1 unless the player asked for more).</li>
     * </ul>
     * Values the core records reject (a radius over 64, no painting stamp, a density outside 0-1, NaN...) are
     * {@code MALFORMED}.
     */
    private static void writeScatterPreview(WireWriter out, StatePalette.Builder palette, C2S.ScatterPreview m)
            throws ProtocolException {
        switch (m.area()) {
            case ScatterArea.Region region -> {
                out.u8(SCATTER_AREA_BOX);
                CoreCodec.writeBox(out, region.box());
            }
            case ScatterArea.Stamps stamps -> {
                out.u8(SCATTER_AREA_STAMPS);
                out.count(stamps.stamps().size(), MAX_SCATTER_STAMPS, "scatter stamps");
                int x = 0, z = 0;
                for (ScatterArea.Stamp stamp : stamps.stamps()) {
                    out.zigzag(stamp.x() - x);
                    out.zigzag(stamp.z() - z);
                    out.u8(stamp.radius() << 1 | (stamp.erase() ? 1 : 0));
                    x = stamp.x();
                    z = stamp.z();
                }
            }
        }
        C2S.ScatterPreview.Settings settings = m.settings();
        out.zigzagLong(settings.seed());
        out.varint(settings.spacing());
        switch (settings.density()) {
            case ScatterSettings.Density.Fraction fraction -> {
                out.u8(SCATTER_DENSITY_FRACTION);
                out.i64(Double.doubleToLongBits(fraction.value()));
            }
            case ScatterSettings.Density.Count count -> {
                out.u8(SCATTER_DENSITY_COUNT);
                out.varint(count.target());
            }
        }
        CoreCodec.writeSurface(out, palette, settings.surface());
        out.bool(settings.fit().allowInFluid());
        out.i64(Double.doubleToLongBits(settings.fit().minSupportFraction()));
        out.bool(settings.fit().survive());
        out.count(m.variants().size(), MAX_SCATTER_VARIANTS, "scatter variants");
        for (C2S.ScatterPreview.Variant variant : m.variants()) {
            switch (variant.source()) {
                case ScatterSource.Held held -> CoreCodec.writeSource(out, held.ref());
                case ScatterSource.Block block -> {
                    out.u8(SCATTER_VARIANT_BLOCK);
                    out.string(block.state(), ScatterSource.MAX_STATE_BYTES, "scatter block variant");
                }
                case ScatterSource.Feature feature -> {
                    out.u8(SCATTER_VARIANT_FEATURE);
                    out.string(feature.id(), ScatterSource.MAX_STATE_BYTES, "scatter feature variant");
                }
            }
            out.varint(variant.weight());
        }
        out.u8(m.transforms().turns());
        out.bool(m.transforms().mirror());
        out.u8(settings.columnHeight().min());
        out.u8(settings.columnHeight().max());
    }

    private static C2S.ScatterPreview readScatterPreview(int reqId, WireReader in, StatePalette.Table palette)
            throws ProtocolException {
        int areaTag = in.u8();
        ScatterArea area = switch (areaTag) {
            case SCATTER_AREA_BOX -> new ScatterArea.Region(CoreCodec.readBox(in));
            case SCATTER_AREA_STAMPS -> {
                int count = in.count(MAX_SCATTER_STAMPS, "scatter stamps");
                List<ScatterArea.Stamp> stamps = new ArrayList<>(count);
                long x = 0, z = 0;
                for (int i = 0; i < count; i++) {
                    x += in.zigzag();
                    z += in.zigzag();
                    int packed = in.u8();
                    // The Stamp record checks the radius and the coordinates (within 2^25 of the origin).
                    stamps.add(new ScatterArea.Stamp(Math.toIntExact(x), Math.toIntExact(z), packed >>> 1,
                            (packed & 1) != 0));
                }
                yield new ScatterArea.Stamps(stamps);
            }
            default -> throw WireReader.malformed("Unknown scatter area tag " + areaTag);
        };
        long seed = in.zigzagLong();
        int spacing = in.varint();
        int densityTag = in.u8();
        ScatterSettings.Density density = switch (densityTag) {
            case SCATTER_DENSITY_FRACTION -> new ScatterSettings.Density.Fraction(Double.longBitsToDouble(in.i64()));
            case SCATTER_DENSITY_COUNT -> new ScatterSettings.Density.Count(in.varint());
            default -> throw WireReader.malformed("Unknown scatter density tag " + densityTag);
        };
        SurfaceMask surface = CoreCodec.readSurface(in, palette);
        boolean allowInFluid = in.bool();
        double minSupportFraction = Double.longBitsToDouble(in.i64());
        ScatterSettings.Fit fit = new ScatterSettings.Fit(allowInFluid, minSupportFraction, in.bool());
        int variantCount = in.count(MAX_SCATTER_VARIANTS, "scatter variants");
        List<C2S.ScatterPreview.Variant> variants = new ArrayList<>(variantCount);
        for (int i = 0; i < variantCount; i++) {
            int tag = in.u8();
            ScatterSource source = switch (tag) {
                case SCATTER_VARIANT_BLOCK -> new ScatterSource.Block(in.string(ScatterSource.MAX_STATE_BYTES,
                        "scatter block variant"));
                case SCATTER_VARIANT_FEATURE -> new ScatterSource.Feature(in.string(ScatterSource.MAX_STATE_BYTES,
                        "scatter feature variant"));
                default -> new ScatterSource.Held(CoreCodec.readSource(in, tag));
            };
            variants.add(new C2S.ScatterPreview.Variant(source, in.varint()));
        }
        int turns = in.u8();
        ScatterSettings.Transforms transforms = new ScatterSettings.Transforms(turns, in.bool());
        int minHeight = in.u8();
        ScatterSettings.ColumnHeight columnHeight = new ScatterSettings.ColumnHeight(minHeight, in.u8());
        C2S.ScatterPreview.Settings settings = new C2S.ScatterPreview.Settings(seed, spacing, density, surface, fit,
                columnHeight);
        return new C2S.ScatterPreview(reqId, area, settings, variants, transforms);
    }

    // ---------------------------------------------------------------- streams (both directions)

    private static void writeStreamOpen(WireWriter out, StreamOpen m) throws ProtocolException {
        out.zigzag(m.id());
        out.enumValue(m.kind());
        out.zigzagLong(m.totalBytes());
        out.count(m.meta().size(), StreamOpen.MAX_META, "stream meta");
        for (Map.Entry<String, String> entry : m.meta().entrySet()) {
            out.string(entry.getKey(), MAX_META_KEY_BYTES, "stream meta key");
            out.string(entry.getValue(), MAX_TEXT_BYTES, "stream meta value");
        }
    }

    private static void writeStreamChunk(WireWriter out, StreamChunk m) throws ProtocolException {
        out.zigzag(m.id());
        out.zigzag(m.seq());
        out.bytes(m.bytes(), out.max(), "stream chunk");
    }

    private static void writeStreamEnd(WireWriter out, StreamEnd m) throws ProtocolException {
        out.zigzag(m.id());
        out.raw(m.sha256().bytes());
    }

    private static void writeStreamAbort(WireWriter out, StreamAbort m) throws ProtocolException {
        out.zigzag(m.id());
        out.string(m.reason(), MAX_NAME_BYTES, "abort reason");
    }

    private static void writeStreamCredit(WireWriter out, StreamCredit m) throws ProtocolException {
        out.zigzag(m.id());
        out.zigzagLong(m.bytes());
    }

    private static Message readStream(MessageType type, WireReader in) throws ProtocolException {
        int id = in.zigzag();
        return switch (type) {
            case STREAM_OPEN -> {
                StreamKind kind = in.enumOf(StreamKind.values(), "stream kind");
                long totalBytes = in.zigzagLong();
                int count = in.count(StreamOpen.MAX_META, "stream meta");
                TreeMap<String, String> meta = new TreeMap<>();
                for (int i = 0; i < count; i++) {
                    String key = in.string(MAX_META_KEY_BYTES, "stream meta key");
                    if (meta.put(key, in.string(MAX_TEXT_BYTES, "stream meta value")) != null) {
                        throw WireReader.malformed("Duplicate stream meta key");
                    }
                }
                yield new StreamOpen(id, kind, totalBytes, meta);
            }
            case STREAM_CHUNK -> {
                int seq = in.zigzag();
                yield new StreamChunk(id, seq, in.bytes(ProtocolV2.MAX_S2C_FRAME, "stream chunk"));
            }
            case STREAM_END -> new StreamEnd(id, Sha256.ofBytes(in.raw(32)));
            case STREAM_ABORT -> new StreamAbort(id, in.string(MAX_NAME_BYTES, "abort reason"));
            case STREAM_CREDIT -> new StreamCredit(id, in.zigzagLong());
            default -> throw new AssertionError(type);
        };
    }
}
