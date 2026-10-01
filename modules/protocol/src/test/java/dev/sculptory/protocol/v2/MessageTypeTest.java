package dev.sculptory.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.palette.BlockPalette;
import dev.sculptory.core.scatter.ScatterArea;
import dev.sculptory.core.scatter.ScatterSettings;
import dev.sculptory.core.tinker.EntityEdit;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageTypeTest {
    private static final UUID ID = new UUID(1, 2);
    private static final Box BOX = Box.of(new BlockPos(0, 0, 0), new BlockPos(3, 3, 3));
    private static final String HASH = Sha256.digest(new byte[0]).hex();

    /** One instance of every message, in catalogue order. */
    static List<Message> samples() {
        BrushSpec spec = new BrushSpec(BrushTool.RAISE, 8, 0.5f, Falloff.SMOOTH, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L);
        OpSpec fill = new OpSpec.Fill(BOX, new Pattern.Single(1), CellMask.ANY);
        return List.of(
                new C2S.Hello(2, 2, "0.2.0", Features.of(Features.STROKES)),
                new C2S.StrokeBegin(1, spec),
                new C2S.Dabs(1, 5, List.of(new Dab(0, 16, 16, 16, 255))),
                new C2S.StrokeEnd(1),
                new C2S.Resync(BOX),
                new C2S.RunOp(3, fill, false, ConflictPolicy.SKIP_CONFLICTS),
                new C2S.CancelJob(ID),
                new C2S.Undo(4, ConflictPolicy.SKIP_CONFLICTS),
                new C2S.Redo(5, ConflictPolicy.OVERWRITE),
                new C2S.Copy(6, BOX, BlockPos.ORIGIN, false, CellMask.ANY),
                new C2S.PreviewRequest(new SourceRef.Asset(HASH)),
                new C2S.LibraryList(7, ""),
                new C2S.LibraryLoad(8, "trees/oak.schem"),
                new C2S.SaveAsset(9, ID, "mine/house.schem"),
                new C2S.ExportClipboard(10, ID),
                new C2S.UploadBegin(11, "house.schem", 1234),
                new C2S.ScatterPreview(12, new ScatterArea.Stamps(List.of(ScatterArea.Stamp.paint(10, -4, 8),
                        ScatterArea.Stamp.erase(12, -2, 3))),
                        new C2S.ScatterPreview.Settings(1L, 4, new ScatterSettings.Density.Fraction(0.5), SurfaceMask.ANY,
                                ScatterSettings.Fit.DEFAULT),
                        List.of(new C2S.ScatterPreview.Variant(new SourceRef.Clipboard(ID), 1)),
                        ScatterSettings.Transforms.ALL),
                new C2S.LibraryMove(13, false, "trees/oak.schem", "rocks/oak.schem"),
                new C2S.LibraryDelete(14, true, "old"),
                new C2S.LibraryCreateFolder(15, "trees/big"),
                new C2S.HistoryOverwrite(18, false, 2),
                new C2S.PaletteSave(16, "palettes/moss.palette.json", new BlockPalette(List.of(
                        new BlockPalette.Entry("minecraft:moss_block", 4), new BlockPalette.Entry("minecraft:stone", 1)))),
                new C2S.PaletteLoad(17, "palettes/moss.palette.json"),
                new C2S.SelectionUpload(19, Sha256.digest(new byte[] {7}), BOX, 40, 900),
                new C2S.LibraryAccessGet(20, "trees/oak.schem"),
                new C2S.LibraryAccessSet(21, "trees/oak.schem", AssetAccess.listed(List.of(
                        new AssetAccess.Grantee(new UUID(1, 2), "Alice"), new AssetAccess.Grantee(null, "Typed")))),
                new C2S.GeneratedUpload(20, BOX, 12, 300),
                new C2S.TinkerBlock(22, new BlockPos(3, 64, -9), 1, 2, null),
                new C2S.TinkerEntity(23, ID, List.of(new EntityEdit.Yaw(90), new EntityEdit.ItemRotation(2))),
                new C2S.BuilderPowers(BuilderPower.LONG_REACH.bit() | BuilderPower.MIRROR.bit()),
                new C2S.BuilderPlace(9, false, new BlockPos(3, 64, -2), dev.sculptory.core.region.Facing.UP, 0.25f, 1f, 0.5f,
                        BuilderPower.KEEP_SHAPE.bit(), dev.sculptory.core.brush.Symmetry.NONE),
                new C2S.BuilderBreak(10, 2, List.of(new BlockPos(3, 64, -2)), BuilderPower.BULLDOZER.bit(),
                        dev.sculptory.core.brush.Symmetry.NONE, false, true),
                new C2S.BuilderDragEnd(2),
                new C2S.SetEditMask(24, dev.sculptory.core.mask.EditMask.NONE),
                new C2S.Navigate(25, NavigateMode.JUMP, new BlockPos(3, 64, -2), dev.sculptory.core.region.Facing.UP, 0f,
                        -1f, 0f),
                new S2C.Welcome(2, Features.NONE, Limits.DEFAULTS, PermissionMask.NONE, 7L, "0.2.0-dev+6a043a85"),
                new S2C.Incompatible(3, 4, "0.2.0-dev+6a043a85"),
                new S2C.PermissionsChanged(PermissionMask.NONE.with(0), Limits.DEFAULTS),
                new S2C.StrokeStatus(1, 4, S2C.StrokeStatus.Status.REJECTED, RejectReason.AREA_BUSY),
                new S2C.StrokeStatus(1, 9, S2C.StrokeStatus.Status.OK, null, 7),
                new S2C.JobAccepted(3, ID, 64),
                new S2C.JobRejected(3, RejectReason.TOO_LARGE),
                new S2C.JobProgress(ID, 10, 64, Phase.APPLY),
                new S2C.JobFinished(ID, JobOutcome.COMPLETED, 60, 4, 0, 0),
                new S2C.HistoryState(true, false, "Fill", "", 4096),
                new S2C.ClipboardReady(6, ID, new BlockPos(4, 4, 4), BlockPos.ORIGIN, 64, 1024),
                new S2C.LibraryListing(7, "", List.of(new S2C.LibraryListing.Entry("trees", true, 0, "")), true),
                new S2C.AssetSaved(9, "mine/house.schem", HASH),
                new S2C.UploadGrant(11, 42, 4L << 20),
                new S2C.UploadResult(11, null, "too large"),
                new S2C.ScatterPlan(12, ID, 30, new TreeMap<>(java.util.Map.of("SLOPE", 3)), 900, BOX),
                new S2C.Notice(S2C.Notice.Level.INFO, "sculptory.notice.test", List.of("a")),
                new S2C.LibraryChanged(13, false, "trees/oak.schem", "rocks/oak.schem"),
                new S2C.PaletteData(17, "palettes/moss.palette.json", BlockPalette.of("minecraft:moss_block", 4), 1,
                        List.of("modded:gone")),
                new S2C.SelectionReady(19, Sha256.digest(new byte[] {7})),
                new S2C.LibraryAccess(20, "trees/oak.schem", AssetAccess.listed(List.of(
                        new AssetAccess.Grantee(new UUID(1, 2), "Alice")))),
                S2C.TinkerResult.done(23, new byte[] {10, 0, 0, 0}),
                S2C.EditMaskState.accepted(24),
                S2C.NavigateResult.landed(25, new BlockPos(3, 65, -2)),
                new StreamOpen(42, StreamKind.SCHEM_FILE, 100, new TreeMap<>(java.util.Map.of("name", "house.schem"))),
                new StreamChunk(42, 0, new byte[] {1, 2, 3}),
                new StreamEnd(42, Sha256.digest(new byte[] {1, 2, 3})),
                new StreamAbort(42, "cancelled"),
                new StreamCredit(42, 4L << 20));
    }

    /** Must list every C2S record with no default branch, so adding a message breaks compilation here. */
    static MessageType switchC2S(C2S message) {
        return switch (message) {
            case C2S.Hello m -> MessageType.HELLO;
            case C2S.StrokeBegin m -> MessageType.STROKE_BEGIN;
            case C2S.Dabs m -> MessageType.DABS;
            case C2S.StrokeEnd m -> MessageType.STROKE_END;
            case C2S.Resync m -> MessageType.RESYNC;
            case C2S.RunOp m -> MessageType.RUN_OP;
            case C2S.CancelJob m -> MessageType.CANCEL_JOB;
            case C2S.Undo m -> MessageType.UNDO;
            case C2S.Redo m -> MessageType.REDO;
            case C2S.Copy m -> MessageType.COPY;
            case C2S.PreviewRequest m -> MessageType.PREVIEW_REQUEST;
            case C2S.LibraryList m -> MessageType.LIBRARY_LIST;
            case C2S.LibraryLoad m -> MessageType.LIBRARY_LOAD;
            case C2S.SaveAsset m -> MessageType.SAVE_ASSET;
            case C2S.ExportClipboard m -> MessageType.EXPORT_CLIPBOARD;
            case C2S.UploadBegin m -> MessageType.UPLOAD_BEGIN;
            case C2S.ScatterPreview m -> MessageType.SCATTER_PREVIEW;
            case C2S.LibraryMove m -> MessageType.LIBRARY_MOVE;
            case C2S.LibraryDelete m -> MessageType.LIBRARY_DELETE;
            case C2S.LibraryCreateFolder m -> MessageType.LIBRARY_CREATE_FOLDER;
            case C2S.HistoryOverwrite m -> MessageType.HISTORY_OVERWRITE;
            case C2S.PaletteSave m -> MessageType.PALETTE_SAVE;
            case C2S.PaletteLoad m -> MessageType.PALETTE_LOAD;
            case C2S.SelectionUpload m -> MessageType.SELECTION_UPLOAD;
            case C2S.LibraryAccessGet m -> MessageType.LIBRARY_ACCESS_GET;
            case C2S.LibraryAccessSet m -> MessageType.LIBRARY_ACCESS_SET;
            case C2S.GeneratedUpload m -> MessageType.GENERATED_UPLOAD;
            case C2S.TinkerBlock m -> MessageType.TINKER_BLOCK;
            case C2S.TinkerEntity m -> MessageType.TINKER_ENTITY;
            case C2S.BuilderPowers m -> MessageType.BUILDER_POWERS;
            case C2S.BuilderPlace m -> MessageType.BUILDER_PLACE;
            case C2S.BuilderBreak m -> MessageType.BUILDER_BREAK;
            case C2S.BuilderDragEnd m -> MessageType.BUILDER_DRAG_END;
            case C2S.SetEditMask m -> MessageType.SET_EDIT_MASK;
            case C2S.Navigate m -> MessageType.NAVIGATE;
            case StreamOpen m -> MessageType.STREAM_OPEN;
            case StreamChunk m -> MessageType.STREAM_CHUNK;
            case StreamEnd m -> MessageType.STREAM_END;
            case StreamAbort m -> MessageType.STREAM_ABORT;
            case StreamCredit m -> MessageType.STREAM_CREDIT;
        };
    }

    /** Must list every S2C record with no default branch. */
    static MessageType switchS2C(S2C message) {
        return switch (message) {
            case S2C.Welcome m -> MessageType.WELCOME;
            case S2C.Incompatible m -> MessageType.INCOMPATIBLE;
            case S2C.PermissionsChanged m -> MessageType.PERMISSIONS_CHANGED;
            case S2C.StrokeStatus m -> MessageType.STROKE_STATUS;
            case S2C.JobAccepted m -> MessageType.JOB_ACCEPTED;
            case S2C.JobRejected m -> MessageType.JOB_REJECTED;
            case S2C.JobProgress m -> MessageType.JOB_PROGRESS;
            case S2C.JobFinished m -> MessageType.JOB_FINISHED;
            case S2C.HistoryState m -> MessageType.HISTORY_STATE;
            case S2C.ClipboardReady m -> MessageType.CLIPBOARD_READY;
            case S2C.LibraryListing m -> MessageType.LIBRARY_LISTING;
            case S2C.AssetSaved m -> MessageType.ASSET_SAVED;
            case S2C.UploadGrant m -> MessageType.UPLOAD_GRANT;
            case S2C.UploadResult m -> MessageType.UPLOAD_RESULT;
            case S2C.ScatterPlan m -> MessageType.SCATTER_PLAN;
            case S2C.Notice m -> MessageType.NOTICE;
            case S2C.LibraryChanged m -> MessageType.LIBRARY_CHANGED;
            case S2C.PaletteData m -> MessageType.PALETTE_DATA;
            case S2C.SelectionReady m -> MessageType.SELECTION_READY;
            case S2C.LibraryAccess m -> MessageType.LIBRARY_ACCESS;
            case S2C.TinkerResult m -> MessageType.TINKER_RESULT;
            case S2C.EditMaskState m -> MessageType.EDIT_MASK_STATE;
            case S2C.NavigateResult m -> MessageType.NAVIGATE_RESULT;
            case StreamOpen m -> MessageType.STREAM_OPEN;
            case StreamChunk m -> MessageType.STREAM_CHUNK;
            case StreamEnd m -> MessageType.STREAM_END;
            case StreamAbort m -> MessageType.STREAM_ABORT;
            case StreamCredit m -> MessageType.STREAM_CREDIT;
        };
    }

    @Test
    void codesAreUniqueStableAndOneByte() {
        Set<Integer> codes = new HashSet<>();
        for (MessageType type : MessageType.values()) {
            assertTrue(codes.add(type.code()), "duplicate code " + type.code());
            assertTrue(type.code() >= 1 && type.code() < 128, type + " fits a one-byte varint");
            assertSame(type, MessageType.fromCode(type.code()));
            int expectedFloor = switch (type.direction()) {
                case C2S -> 1;
                case S2C -> 64;
                case BOTH -> 100;
            };
            assertTrue(type.code() >= expectedFloor, type + " code in its direction's range");
        }
        assertNull(MessageType.fromCode(0));
        assertNull(MessageType.fromCode(63));
        assertEquals(1, MessageType.HELLO.code());
        assertEquals(64, MessageType.WELCOME.code());
        assertEquals(100, MessageType.STREAM_OPEN.code());
    }

    @Test
    void everyPermittedRecordHasATypeInItsDirection() {
        Set<Class<?>> c2s = new HashSet<>(Arrays.asList(C2S.class.getPermittedSubclasses()));
        Set<Class<?>> s2c = new HashSet<>(Arrays.asList(S2C.class.getPermittedSubclasses()));
        for (MessageType type : MessageType.values()) {
            assertEquals(type.clientToServer(), c2s.contains(type.messageClass()), type + " C2S membership");
            assertEquals(type.serverToClient(), s2c.contains(type.messageClass()), type + " S2C membership");
            assertTrue(type.messageClass().isRecord(), type + " is a record");
        }
        Set<Class<?>> all = new HashSet<>(c2s);
        all.addAll(s2c);
        assertEquals(MessageType.values().length, all.size());
        assertThrows(IllegalArgumentException.class, () -> MessageType.of(String.class));
    }

    @Test
    void samplesCoverEveryTypeAndMatchTheSwitches() {
        EnumSet<MessageType> seen = EnumSet.noneOf(MessageType.class);
        for (Message message : samples()) {
            MessageType type = message.type();
            assertSame(message.getClass(), type.messageClass());
            seen.add(type);
            if (message instanceof C2S c2s) assertSame(type, switchC2S(c2s));
            if (message instanceof S2C s2c) assertSame(type, switchS2C(s2c));
        }
        assertEquals(EnumSet.allOf(MessageType.class), seen);
    }

    @Test
    void recordInvariants() {
        assertThrows(IllegalArgumentException.class, () -> new C2S.Dabs(1, 1, List.of()));
        List<Dab> seventeen = java.util.Collections.nCopies(17, new Dab(0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new C2S.Dabs(1, 1, seventeen));
        assertThrows(IllegalArgumentException.class, () -> new C2S.Hello(3, 2, "x", Features.NONE));
        assertThrows(IllegalArgumentException.class, () -> new S2C.StrokeStatus(1, 0, S2C.StrokeStatus.Status.REJECTED, null));
        assertThrows(IllegalArgumentException.class, () -> new S2C.StrokeStatus(1, 0, S2C.StrokeStatus.Status.OK, RejectReason.INVALID));
        assertThrows(IllegalArgumentException.class, () -> new S2C.StrokeStatus(1, 0, S2C.StrokeStatus.Status.OK, null, -2));
        assertThrows(IllegalArgumentException.class, () -> new S2C.UploadResult(1, null, null));
        assertThrows(IllegalArgumentException.class, () -> new S2C.UploadResult(1, ID, "error"));
        byte[] data = {1, 2};
        StreamChunk chunk = new StreamChunk(1, 0, data);
        data[0] = 9;
        assertEquals(new StreamChunk(1, 0, new byte[] {1, 2}), chunk);
        assertFalse(chunk.equals(new StreamChunk(1, 1, new byte[] {1, 2})));
    }

    @Test
    void featuresLimitsAndPermissions() {
        Features features = Features.of(Features.STROKES, Features.HISTORY);
        assertTrue(features.has(Features.STROKES));
        assertEquals(Features.of(Features.HISTORY), features.intersect(Features.of(Features.HISTORY, Features.SCATTER)));
        assertThrows(IllegalArgumentException.class, () -> Features.of("Bad Name"));
        assertEquals(2_097_152L, Limits.DEFAULTS.maxOpVolume());
        assertEquals(32, Limits.DEFAULTS.maxBrushRadius());
        assertEquals(20, Limits.DEFAULTS.maxDabRate());
        assertEquals(32L << 20, Limits.DEFAULTS.maxUploadBytes());
        assertThrows(IllegalArgumentException.class, () -> new Limits(0, 1, 1, 1, 1, 1));
        assertEquals(2_097_152L, Limits.DEFAULTS.maxSelectionCells());
        assertEquals(65_536, Limits.DEFAULTS.maxSelectionSections());
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 1, 1, 1, 1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 1, 1, 1, 1, 1, 1, 0));
        PermissionMask mask = PermissionMask.NONE.with(3).with(63);
        assertTrue(mask.has(3));
        assertTrue(mask.has(63));
        assertFalse(mask.has(4));
        assertThrows(IndexOutOfBoundsException.class, () -> mask.has(64));
    }
}
