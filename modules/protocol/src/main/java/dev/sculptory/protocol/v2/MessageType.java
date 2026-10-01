package dev.sculptory.protocol.v2;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Envelope type codes. Codes are stable: never renumber or reuse one. C2S uses 1-63, S2C 64-99 and
 * bidirectional stream messages 100-127, so every code is a one-byte varint.
 */
public enum MessageType {
    HELLO(1, Direction.C2S, C2S.Hello.class),
    STROKE_BEGIN(2, Direction.C2S, C2S.StrokeBegin.class),
    DABS(3, Direction.C2S, C2S.Dabs.class),
    STROKE_END(4, Direction.C2S, C2S.StrokeEnd.class),
    RESYNC(5, Direction.C2S, C2S.Resync.class),
    RUN_OP(6, Direction.C2S, C2S.RunOp.class),
    CANCEL_JOB(7, Direction.C2S, C2S.CancelJob.class),
    UNDO(8, Direction.C2S, C2S.Undo.class),
    REDO(9, Direction.C2S, C2S.Redo.class),
    COPY(10, Direction.C2S, C2S.Copy.class),
    PREVIEW_REQUEST(11, Direction.C2S, C2S.PreviewRequest.class),
    LIBRARY_LIST(12, Direction.C2S, C2S.LibraryList.class),
    LIBRARY_LOAD(13, Direction.C2S, C2S.LibraryLoad.class),
    SAVE_ASSET(14, Direction.C2S, C2S.SaveAsset.class),
    EXPORT_CLIPBOARD(15, Direction.C2S, C2S.ExportClipboard.class),
    UPLOAD_BEGIN(16, Direction.C2S, C2S.UploadBegin.class),
    SCATTER_PREVIEW(17, Direction.C2S, C2S.ScatterPreview.class),
    LIBRARY_MOVE(18, Direction.C2S, C2S.LibraryMove.class),
    LIBRARY_DELETE(19, Direction.C2S, C2S.LibraryDelete.class),
    LIBRARY_CREATE_FOLDER(20, Direction.C2S, C2S.LibraryCreateFolder.class),
    HISTORY_OVERWRITE(21, Direction.C2S, C2S.HistoryOverwrite.class),
    PALETTE_SAVE(22, Direction.C2S, C2S.PaletteSave.class),
    PALETTE_LOAD(23, Direction.C2S, C2S.PaletteLoad.class),
    SELECTION_UPLOAD(24, Direction.C2S, C2S.SelectionUpload.class),
    LIBRARY_ACCESS_GET(25, Direction.C2S, C2S.LibraryAccessGet.class),
    LIBRARY_ACCESS_SET(26, Direction.C2S, C2S.LibraryAccessSet.class),
    // Generators (the stream's codes start at 30; other streams append below).
    GENERATED_UPLOAD(30, Direction.C2S, C2S.GeneratedUpload.class),
    // Tinker, protocol 5 (the stream's codes start at 40).
    TINKER_BLOCK(40, Direction.C2S, C2S.TinkerBlock.class),
    TINKER_ENTITY(41, Direction.C2S, C2S.TinkerEntity.class),
    // Builder mode (protocol 5; codes 44-47, after Tinker's).
    BUILDER_POWERS(44, Direction.C2S, C2S.BuilderPowers.class),
    BUILDER_PLACE(45, Direction.C2S, C2S.BuilderPlace.class),
    BUILDER_BREAK(46, Direction.C2S, C2S.BuilderBreak.class),
    BUILDER_DRAG_END(47, Direction.C2S, C2S.BuilderDragEnd.class),
    // The WorldEdit-inspired batch (protocol 5): the global mask 48, Jump and Through 50; 49 and 51 spare.
    SET_EDIT_MASK(48, Direction.C2S, C2S.SetEditMask.class),
    NAVIGATE(50, Direction.C2S, C2S.Navigate.class),

    WELCOME(64, Direction.S2C, S2C.Welcome.class),
    INCOMPATIBLE(65, Direction.S2C, S2C.Incompatible.class),
    PERMISSIONS_CHANGED(66, Direction.S2C, S2C.PermissionsChanged.class),
    STROKE_STATUS(67, Direction.S2C, S2C.StrokeStatus.class),
    JOB_ACCEPTED(68, Direction.S2C, S2C.JobAccepted.class),
    JOB_REJECTED(69, Direction.S2C, S2C.JobRejected.class),
    JOB_PROGRESS(70, Direction.S2C, S2C.JobProgress.class),
    JOB_FINISHED(71, Direction.S2C, S2C.JobFinished.class),
    HISTORY_STATE(72, Direction.S2C, S2C.HistoryState.class),
    CLIPBOARD_READY(73, Direction.S2C, S2C.ClipboardReady.class),
    LIBRARY_LISTING(74, Direction.S2C, S2C.LibraryListing.class),
    ASSET_SAVED(75, Direction.S2C, S2C.AssetSaved.class),
    UPLOAD_GRANT(76, Direction.S2C, S2C.UploadGrant.class),
    UPLOAD_RESULT(77, Direction.S2C, S2C.UploadResult.class),
    SCATTER_PLAN(78, Direction.S2C, S2C.ScatterPlan.class),
    NOTICE(79, Direction.S2C, S2C.Notice.class),
    LIBRARY_CHANGED(80, Direction.S2C, S2C.LibraryChanged.class),
    PALETTE_DATA(81, Direction.S2C, S2C.PaletteData.class),
    SELECTION_READY(82, Direction.S2C, S2C.SelectionReady.class),
    LIBRARY_ACCESS(83, Direction.S2C, S2C.LibraryAccess.class),
    // Tinker, protocol 5 (the stream's codes start at 90).
    TINKER_RESULT(90, Direction.S2C, S2C.TinkerResult.class),
    // The WorldEdit-inspired batch (protocol 5): 91-92; 93-94 spare.
    EDIT_MASK_STATE(91, Direction.S2C, S2C.EditMaskState.class),
    NAVIGATE_RESULT(92, Direction.S2C, S2C.NavigateResult.class),

    STREAM_OPEN(100, Direction.BOTH, StreamOpen.class),
    STREAM_CHUNK(101, Direction.BOTH, StreamChunk.class),
    STREAM_END(102, Direction.BOTH, StreamEnd.class),
    STREAM_ABORT(103, Direction.BOTH, StreamAbort.class),
    STREAM_CREDIT(104, Direction.BOTH, StreamCredit.class);

    public enum Direction {
        C2S,
        S2C,
        BOTH
    }

    private static final Map<Integer, MessageType> BY_CODE = new HashMap<>();
    private static final Map<Class<?>, MessageType> BY_CLASS = new HashMap<>();

    static {
        for (MessageType type : values()) {
            if (BY_CODE.put(type.code, type) != null) throw new ExceptionInInitializerError("Duplicate code " + type.code);
            if (BY_CLASS.put(type.messageClass, type) != null) throw new ExceptionInInitializerError("Duplicate class");
        }
    }

    private final int code;
    private final Direction direction;
    private final Class<? extends Message> messageClass;

    MessageType(int code, Direction direction, Class<? extends Message> messageClass) {
        this.code = code;
        this.direction = direction;
        this.messageClass = messageClass;
    }

    public int code() {
        return code;
    }

    public Direction direction() {
        return direction;
    }

    public Class<? extends Message> messageClass() {
        return messageClass;
    }

    public boolean clientToServer() {
        return direction != Direction.S2C;
    }

    public boolean serverToClient() {
        return direction != Direction.C2S;
    }

    /** The type with {@code code}, or {@code null}. */
    public static MessageType fromCode(int code) {
        return BY_CODE.get(code);
    }

    /** The type of a message record class. */
    public static MessageType of(Class<?> messageClass) {
        MessageType type = BY_CLASS.get(Objects.requireNonNull(messageClass));
        if (type == null) throw new IllegalArgumentException("Not a message class: " + messageClass.getName());
        return type;
    }
}
