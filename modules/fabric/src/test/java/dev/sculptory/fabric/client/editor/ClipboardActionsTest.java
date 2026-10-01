package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.client.editor.clipboard.ClipboardActions;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.mock.MockEditorSession;
import dev.sculptory.fabric.client.editor.tool.RegionWork;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tools.place.PlaceTool;
import dev.sculptory.fabric.client.session.ClipboardCache;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Permissions;
import dev.sculptory.fabric.client.session.Reply;
import dev.sculptory.fabric.client.session.SessionNotices;
import dev.sculptory.fabric.client.session.Transfer;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.protocol.v2.Limits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClipboardActionsTest {
    private final EditorTestRig rig = new EditorTestRig();
    private final Box box = new Box(new BlockPos(0, 60, 0), new BlockPos(3, 61, 4));

    @TempDir
    Path directory;

    @BeforeEach
    void enter() {
        rig.exportDirectory = directory.resolve("sculptory").resolve("exports");
        assertTrue(rig.mode.enter());
    }

    private Notice lastNotice() {
        return rig.notices.get(rig.notices.size() - 1);
    }

    private UUID copy() {
        rig.ctx.setSelection(box);
        return rig.clipboard.copySelection(false).toCompletableFuture().join().toOptional().orElseThrow().clipboardId();
    }

    @Test
    void exportWritesTheFileWithoutOverwritingAndToastsItsPath() throws IOException {
        UUID id = copy();
        assertTrue(rig.clipboard.exportClipboard().isPresent());
        Path first = rig.exportDirectory.resolve("clipboard.schem");
        assertArrayEquals(("mock schematic " + id).getBytes(StandardCharsets.UTF_8), Files.readAllBytes(first));
        assertEquals(Notice.of(Notice.Level.SUCCESS, "sculptory.notice.exported", first.toString()), lastNotice());
        assertEquals(List.of("io"), rig.io, "written off the client thread");
        rig.clipboard.exportClipboard();
        assertTrue(Files.isRegularFile(rig.exportDirectory.resolve("clipboard-1.schem")), "never overwritten");
        assertEquals(rig.exportDirectory.resolve("clipboard-1.schem"), rig.clipboard.lastExport().orElseThrow());
    }

    @Test
    void anExportFromALibraryAssetIsNamedAfterIt() {
        rig.session.library().put("trees/Big Oak.schem", "ab".repeat(32));
        assertTrue(rig.clipboard.load("trees/Big Oak.schem").toCompletableFuture().join().isOk());
        assertEquals("sculptory.notice.loaded", lastNotice().key());
        rig.clipboard.exportClipboard();
        assertTrue(Files.isRegularFile(rig.exportDirectory.resolve("Big_Oak.schem")));
    }

    @Test
    void exportNeedsThePermissionAndAClipboard() {
        rig.clipboard.exportClipboard();
        assertEquals("sculptory.notice.nothing_copied", lastNotice().key());
        copy();
        EnumSet<Perm> granted = EnumSet.allOf(Perm.class);
        granted.remove(Perm.SCHEMATIC_EXPORT);
        rig.session.setPermissions(new Permissions(Perm.mask(granted), Limits.DEFAULTS));
        assertTrue(rig.clipboard.exportClipboard().isEmpty());
        assertEquals(Notice.of(Notice.Level.WARNING, "sculptory.notice.needs_permission", Perm.SCHEMATIC_EXPORT.node()),
                lastNotice());
        assertFalse(Files.exists(rig.exportDirectory));
    }

    @Test
    void exportingTheSelectionCopiesItFirst() {
        rig.ctx.setSelection(box);
        rig.clipboard.exportSelection();
        assertEquals(List.of("copy", "export"), rig.session.calls().stream().map(MockEditorSession.Call::kind).toList());
        assertTrue(Files.isRegularFile(rig.exportDirectory.resolve("clipboard.schem")));
    }

    @Test
    void aDroppedSchematicIsUploadedThenPlaced() throws IOException {
        Path file = Files.write(directory.resolve("House.SCHEM"), new byte[] {1, 2, 3});
        Path other = Files.writeString(directory.resolve("notes.txt"), "x");
        rig.clipboard.upload(List.of(other, file, directory.resolve("second.schem")));
        assertTrue(rig.noticeKeys().contains("sculptory.notice.upload_one"));
        assertEquals(new MockEditorSession.Call("upload", "House.SCHEM"), rig.session.calls().get(0));
        assertEquals("sculptory.notice.uploaded", lastNotice().key());
        UUID uploaded = rig.session.clipboards().current().orElseThrow().clipboardId();
        assertEquals(ToolId.PLACE, rig.ctx.tools().active().orElseThrow().descriptor().id());
        PlaceTool.Request.Paste paste = assertInstanceOf(PlaceTool.Request.Paste.class, rig.placeRequests.get(0));
        assertEquals(new SourceRef.Clipboard(uploaded), paste.source());
        assertEquals("House.SCHEM", rig.clipboard.source());
    }

    @Test
    void onlySchematicsAreUploaded() throws IOException {
        rig.clipboard.upload(List.of(Files.writeString(directory.resolve("a.schematic"), "x"),
                Files.writeString(directory.resolve("b.json"), "x")));
        assertEquals("sculptory.notice.not_schem", lastNotice().key());
        assertTrue(rig.session.calls().isEmpty());
    }

    @Test
    void litematicaAndStructureFilesAreUploadedToo() throws IOException {
        rig.clipboard.upload(List.of(Files.writeString(directory.resolve("Ship.litematic"), "x")));
        rig.clipboard.upload(List.of(Files.writeString(directory.resolve("igloo.NBT"), "x")));
        assertEquals(List.of(new MockEditorSession.Call("upload", "Ship.litematic"),
                new MockEditorSession.Call("upload", "igloo.NBT")), rig.session.calls().stream()
                .filter(call -> call.kind().equals("upload")).toList());
    }

    @Test
    void exportsTakeTheChosenFormatAndName() {
        UUID id = copy();
        rig.clipboard.exportClipboard(dev.sculptory.core.schem.SchematicFormat.LITEMATIC, "my house");
        assertTrue(Files.isRegularFile(rig.exportDirectory.resolve("my_house.litematic")));
        assertEquals(new MockEditorSession.Call("export", id + " LITEMATIC"), rig.session.calls().get(1));
        assertEquals(dev.sculptory.core.schem.SchematicFormat.LITEMATIC, rig.clipboard.format(), "remembered");
        rig.clipboard.exportClipboard();
        assertTrue(Files.isRegularFile(rig.exportDirectory.resolve("clipboard.litematic")), "the last format again");
        rig.clipboard.exportClipboard(dev.sculptory.core.schem.SchematicFormat.STRUCTURE, "igloo.schem");
        assertTrue(Files.isRegularFile(rig.exportDirectory.resolve("igloo.nbt")), "the typed extension gives way");
        rig.ctx.setSelection(box);
        rig.clipboard.exportSelection(dev.sculptory.core.schem.SchematicFormat.SPONGE, "");
        assertTrue(Files.isRegularFile(rig.exportDirectory.resolve("clipboard.schem")));
    }

    @Test
    void filesOverTheServerLimitAreNotRead() throws IOException {
        rig.session.setPermissions(new Permissions(Perm.mask(EnumSet.allOf(Perm.class)),
                new Limits(2_097_152L, 2_097_152L, 32, 20, 2, 2)));
        rig.clipboard.upload(List.of(Files.write(directory.resolve("big.schem"), new byte[10])));
        assertEquals(Notice.of(Notice.Level.WARNING, "sculptory.notice.file_too_large", "big.schem", "2"), lastNotice());
        assertTrue(rig.session.calls().isEmpty());
    }

    @Test
    void uploadsNeverExceedTheClientCapWhateverTheServerSays() throws IOException {
        Permissions generous = new Permissions(Perm.mask(EnumSet.allOf(Perm.class)),
                new Limits(2_097_152L, 2_097_152L, 32, 20, 1L << 40, 2));
        assertEquals(Transfer.MAX_UPLOAD_BYTES, ClipboardActions.maxUploadBytes(generous));
        Path file = Files.write(directory.resolve("ten.schem"), new byte[10]);
        assertEquals(null, ClipboardActions.readAtMost(file, 9));
        assertEquals(10, ClipboardActions.readAtMost(file, 10).length);
    }

    @Test
    void anUnreadableFileSaysSo() {
        rig.clipboard.upload(List.of(directory.resolve("missing.schem")));
        assertEquals("sculptory.notice.file_unreadable", lastNotice().key());
    }

    @Test
    void savingChecksThePathHereFirst() {
        copy();
        assertFalse(rig.clipboard.saveClipboard("../evil").toCompletableFuture().join().isOk());
        assertEquals("sculptory.notice.bad_library_path", lastNotice().key());
        assertEquals(List.of("copy"), rig.session.calls().stream().map(MockEditorSession.Call::kind).toList());
        Reply<?> saved = rig.clipboard.saveClipboard("trees/oak").toCompletableFuture().join();
        assertTrue(saved.isOk());
        assertEquals(new MockEditorSession.Call("save", "trees/oak.schem"), rig.session.calls().get(1));
        assertEquals(Notice.of(Notice.Level.SUCCESS, "sculptory.notice.saved", "trees/oak.schem"), lastNotice());
    }

    @Test
    void savingTheSelectionCopiesItFirst() {
        rig.ctx.setSelection(box);
        assertTrue(rig.clipboard.saveSelection("walls.schem").toCompletableFuture().join().isOk());
        assertEquals(List.of("copy", "save"), rig.session.calls().stream().map(MockEditorSession.Call::kind).toList());
        assertTrue(rig.session.library().containsKey("walls.schem"));
    }

    @Test
    void rotateAndFlipWithoutAPlacementTurnTheNextPaste() {
        copy();
        rig.clipboard.rotate();
        rig.clipboard.flip();
        Transform expected = Transform.rotation(1).compose(new Transform(0, Mirror.X));
        assertEquals(expected, rig.clipboard.nextTransform());
        assertTrue(rig.clipboard.paste());
        assertEquals(expected, rig.place.placement().orElseThrow().transform());
        rig.clipboard.rotate();
        assertEquals(expected.compose(Transform.rotation(1)), rig.place.placement().orElseThrow().transform(),
                "while placing, Rotate turns the placement");
        assertEquals(expected, rig.clipboard.nextTransform());
    }

    @Test
    void clearForgetsTheClipboard() {
        copy();
        rig.clipboard.forget();
        assertTrue(rig.session.clipboards().current().isEmpty());
        assertFalse(rig.clipboard.paste());
        assertEquals("sculptory.notice.nothing_copied", lastNotice().key());
    }

    @Test
    void placingAnAssetNeedsItsHash() {
        assertFalse(rig.clipboard.placeAsset("new.schem", ""));
        assertEquals("sculptory.notice.asset_not_indexed", lastNotice().key());
        rig.session.setHoldTransfers(true);
        assertTrue(rig.clipboard.placeAsset("trees/oak.schem", "cd".repeat(32)));
        assertEquals(new MockEditorSession.Call("preview", new SourceRef.Asset("cd".repeat(32))), rig.session.calls().get(0));
        assertTrue(rig.place.placing());
    }

    // ---- Shapes and cell sets ----

    @Test
    void copyCutMoveAndStackTakeTheShapeOrCellSet() {
        Region.Shape sphere = new Region.Shape(box, ShapeKind.ELLIPSOID, Facing.UP);
        rig.ctx.setSelectionRegion(sphere);
        Reply<ClipboardCache.Entry> copied = rig.clipboard.copySelection(false).toCompletableFuture().join();
        assertEquals(sphere.cellCount(), copied.toOptional().orElseThrow().cells(), "the clipboard holds the sphere's cells");
        assertEquals(Notice.of(Notice.Level.SUCCESS, "sculptory.notice.copied",
                SessionNotices.count(sphere.cellCount()), rig.keymap.display(KeyAction.PASTE)), lastNotice());
        assertEquals(ClipboardActions.bottomCentre(box), rig.session.clipboards().current().orElseThrow().anchor()
                .offset(box.min().x(), box.min().y(), box.min().z()), "anchored at the bottom centre of the bounds");

        CellSet.Builder builder = CellSet.builder();
        builder.add(0, 60, 0).add(5, 62, 1);
        Region.Cells cells = new Region.Cells(builder.build());
        rig.ctx.setSelectionRegion(cells);
        rig.clipboard.copySelection(true).toCompletableFuture().join();
        assertEquals(List.of(new MockEditorSession.Call("copy", sphere), new MockEditorSession.Call("cut", cells)),
                rig.session.calls());

        assertTrue(rig.clipboard.move());
        assertEquals(new PlaceTool.Request.Move(cells), rig.placeRequests.get(rig.placeRequests.size() - 1));
        assertTrue(rig.clipboard.stack());
        assertEquals(new PlaceTool.Request.Stack(cells), rig.placeRequests.get(rig.placeRequests.size() - 1));
    }

    @Test
    void aLargeShapeIsCountedInTheBackgroundBeforeItIsCopied() {
        List<Runnable> work = new ArrayList<>();
        List<Runnable> client = new ArrayList<>();
        rig.ctx.setRegionWork(new RegionWork(work::add, client::add));
        EnumSet<Perm> granted = EnumSet.allOf(Perm.class);
        granted.remove(Perm.LIMIT_BYPASS);
        rig.session.setPermissions(new Permissions(Perm.mask(granted), new Limits(4_000_000L, 2_000_000L, 32, 20,
                32L << 20, 2)));
        // 150³ = 3.4M cells of box, over the clipboard limit; the sphere inside holds 1.8M, under it.
        Region.Shape sphere = new Region.Shape(new Box(new BlockPos(0, 0, 0), new BlockPos(149, 149, 149)),
                ShapeKind.ELLIPSOID, Facing.UP);
        rig.ctx.setSelectionRegion(sphere);
        var copied = rig.clipboard.copySelection(false).toCompletableFuture();
        assertFalse(copied.isDone());
        assertEquals("sculptory.notice.counting", lastNotice().key());
        assertTrue(rig.session.calls().isEmpty(), "nothing is asked of the server before the count is in");
        while (!work.isEmpty() || !client.isEmpty()) {
            while (!work.isEmpty()) work.remove(0).run();
            while (!client.isEmpty()) client.remove(0).run();
        }
        assertTrue(copied.join().isOk());
        assertEquals(List.of(new MockEditorSession.Call("copy", sphere)), rig.session.calls());
    }

    @Test
    void copyChecksTheCellCountAndRefusesAnEmptyShape() {
        EnumSet<Perm> granted = EnumSet.allOf(Perm.class);
        granted.remove(Perm.LIMIT_BYPASS);
        rig.session.setPermissions(new Permissions(Perm.mask(granted), new Limits(2_097_152L, 35, 32, 20, 32L << 20, 2)));
        Box twelve = new Box(new BlockPos(0, 60, 0), new BlockPos(3, 61, 4)); // 40 cells in the box, 32 in the cylinder
        Region.Shape cylinder = new Region.Shape(twelve, ShapeKind.CYLINDER, Facing.UP);
        assertEquals(32, cylinder.cellCount());
        rig.ctx.setSelectionRegion(cylinder);
        assertTrue(rig.clipboard.copySelection(false).toCompletableFuture().join().isOk(),
                "the cylinder's own cells fit the clipboard limit");
        rig.ctx.setSelection(twelve);
        assertFalse(rig.clipboard.copySelection(false).toCompletableFuture().join().isOk());
        assertEquals(Notice.of(Notice.Level.WARNING, "sculptory.notice.too_large", "40", "35"), lastNotice());

        rig.ctx.setSelectionRegion(new Region.Shape(new Box(new BlockPos(0, 60, 0), new BlockPos(1, 60, 1)),
                ShapeKind.CONE, Facing.UP));
        int calls = rig.session.calls().size();
        assertFalse(rig.clipboard.copySelection(false).toCompletableFuture().join().isOk());
        assertFalse(rig.clipboard.move());
        assertEquals("sculptory.notice.empty_shape", lastNotice().key());
        assertEquals(calls, rig.session.calls().size(), "nothing sent for a shape without cells");
    }
}
