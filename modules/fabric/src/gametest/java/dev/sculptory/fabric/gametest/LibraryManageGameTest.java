package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.ClipboardGameTest.refusal;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.schem.SchematicCodec;
import dev.sculptory.core.schem.SchematicMetadata;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.engine.impl.ServerClipboards;
import dev.sculptory.fabric.gametest.ClipTestSupport.Captured;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.ClipboardService.LibraryChange;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.library.Library;
import dev.sculptory.server.library.LibraryPath;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;

/**
 * M4 library management through the server's clipboard service with real players and permissions: who may rename,
 * move, delete and make folders; path validation; content-hash references across a rename; the trash; racing players.
 */
public final class LibraryManageGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /** A small schematic of {@code block} (a 2 x 1 x 1 row), as a library file holds it. */
    private static byte[] schem(Harness h, String block) throws IOException {
        Clipboard.Builder content = Clipboard.builder(h.runtime.states(), new BlockPos(2, 1, 1));
        content.set(0, 0, 0, h.state(block));
        content.set(1, 0, 0, h.state(block));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SchematicCodec.write(out, content.build(), SchematicMetadata.EMPTY, 3955);
        return out.toByteArray();
    }

    private static void put(Path root, String path, byte[] bytes) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    private static void run(ClipboardGameTest.ThrowingRun request) {
        try {
            request.run();
        } catch (EditRejected e) {
            throw new GameTestException("refused: " + e.reason() + " " + e.getMessage());
        }
    }

    /**
     * Own folder: always (with {@code clipboard}); shared: {@code library.write}; another player's: admin. Checked at
     * every request (a node taken away refuses the next one), and the listing says which folders may be changed.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_lib_manage_perms", tickLimit = LIMIT)
    public void libraryChangesFollowTheLibraryPermissions(TestContext context) throws IOException {
        Harness h = new Harness(context);
        ServerPlayerEntity builder = h.addPlayer(false);
        ServerPlayerEntity writer = h.addPlayer(false);
        ServerPlayerEntity useOnly = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.CLIPBOARD);
        EditTestSupport.grant(writer, Perm.USE, Perm.CLIPBOARD, Perm.LIBRARY_WRITE);
        EditTestSupport.grant(useOnly, Perm.USE);
        Path root = ClipTestSupport.libraryRoot(context);
        String mine = "_players/" + builder.getUuid();
        String writers = "_players/" + writer.getUuid();
        byte[] stone = schem(h, "minecraft:stone");
        put(root, "shared/tree.schem", stone);
        put(root, mine + "/mine.schem", stone);
        put(root, writers + "/w.schem", stone);
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);

        // Refused up front, before any file work.
        check(refusal(() -> clips.createFolder(builder, "shared2", new Captured<>())).reason() == RejectReason.NO_PERMISSION,
                "a shared folder without library.write");
        check(refusal(() -> clips.delete(builder, false, "shared/tree.schem", new Captured<>())).reason()
                == RejectReason.NO_PERMISSION, "a shared delete without library.write");
        check(refusal(() -> clips.move(builder, false, mine + "/mine.schem", "shared/mine.schem", new Captured<>()))
                .reason() == RejectReason.NO_PERMISSION, "a move into the shared area without library.write");
        check(refusal(() -> clips.move(builder, false, writers + "/w.schem", writers + "/x.schem", new Captured<>()))
                .reason() == RejectReason.NO_PERMISSION, "another player's folder");
        check(refusal(() -> clips.delete(writer, false, mine + "/mine.schem", new Captured<>())).reason()
                == RejectReason.NO_PERMISSION, "library.write is not admin");
        check(refusal(() -> clips.createFolder(useOnly, "_players/" + useOnly.getUuid() + "/x", new Captured<>()))
                .reason() == RejectReason.NO_PERMISSION, "the library needs clipboard");
        EditRejected detail = refusal(() -> clips.delete(builder, false, "shared/tree.schem", new Captured<>()));
        check(detail.getMessage().contains("sculptory.library.write"), "the refusal says why: " + detail.getMessage());
        check(clips.requests(builder.getUuid()) == 0, "a refusal left a request slot taken");

        Captured<LibraryChange> ownFolder = new Captured<>();
        Captured<LibraryChange> ownRename = new Captured<>();
        Captured<LibraryChange> sharedRename = new Captured<>();
        Captured<LibraryChange> adminDelete = new Captured<>();
        Captured<ClipboardService.Listing> builderRoot = new Captured<>();
        Captured<ClipboardService.Listing> builderOwn = new Captured<>();
        Captured<ClipboardService.Listing> writerRoot = new Captured<>();
        Captured<ClipboardService.Listing> adminPlayers = new Captured<>();
        run(() -> clips.createFolder(builder, mine + "/sub", ownFolder));
        run(() -> clips.move(writer, false, "shared/tree.schem", "shared/oak.schem", sharedRename));
        run(() -> clips.list(h.player, "_players", adminPlayers));
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    check(ownFolder.get("own folder").equals(new LibraryChange(true, "", mine + "/sub")), "own folder");
                    check(sharedRename.get("shared rename").equals(new LibraryChange(false, "shared/tree.schem",
                            "shared/oak.schem")), "shared rename");
                    check(Files.isDirectory(root.resolve(mine).resolve("sub")), "the folder is missing");
                    check(Files.isRegularFile(root.resolve("shared").resolve("oak.schem")), "the rename is missing");
                    check(!adminPlayers.get("admin _players").writable(), "_players holds only player folders");
                    run(() -> clips.move(builder, false, mine + "/mine.schem", mine + "/sub/mine.schem", ownRename));
                    run(() -> clips.delete(h.player, false, writers + "/w.schem", adminDelete));
                    run(() -> clips.list(builder, "", builderRoot));
                })
                .createAndAdd(() -> {
                    check(ownRename.get("own move").to().equals(mine + "/sub/mine.schem"), "own move");
                    check(Files.isRegularFile(root.resolve(mine).resolve("sub").resolve("mine.schem")), "moved file missing");
                    adminDelete.get("admin delete");
                    check(!Files.exists(root.resolve(writers).resolve("w.schem")), "the admin delete did nothing");
                    check(!builderRoot.get("builder root").writable(), "the shared root is read-only without library.write");
                    run(() -> clips.list(builder, mine, builderOwn));
                    run(() -> clips.list(writer, "", writerRoot));
                })
                .createAndAdd(() -> {
                    check(builderOwn.get("builder own").writable(), "the own folder is writable");
                    check(writerRoot.get("writer root").writable(), "library.write may change the shared root");
                    // A node taken away is noticed at the next request.
                    EditTestSupport.grant(writer, Perm.USE, Perm.CLIPBOARD);
                    check(refusal(() -> clips.createFolder(writer, "shared3", new Captured<>())).reason()
                            == RejectReason.NO_PERMISSION, "library.write was taken away");
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** Paths are checked exactly like saves: traversal, absolute paths, bad and reserved names never reach the disk. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_lib_manage_paths", tickLimit = LIMIT)
    public void libraryChangesValidatePathsLikeSaves(TestContext context) throws IOException {
        Harness h = new Harness(context);
        Path root = ClipTestSupport.libraryRoot(context);
        Path sandbox = root.getParent();
        put(root, "ok/fine.schem", schem(h, "minecraft:stone"));
        Files.createDirectories(sandbox.resolve("outside"));
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);
        List<String> badFiles = List.of("../evil.schem", "../../evil.schem", "ok/../../evil.schem", "/evil.schem",
                "//server/share/evil.schem", "C:/evil.schem", "C:evil.schem", "c:\\evil.schem", "ok\\evil.schem",
                "CON.schem", "ok/nul.schem", ".hidden.schem", ".trash/x.schem", "ok//b.schem", "evil.txt", "evil",
                "_players/../evil.schem", "a b.schem", "ok/./b.schem", "evil.schem:stream", "trailing./x.schem",
                "_players/x.schem", "x".repeat(300) + ".schem", "a/b/c/d/e/f/g/h/i.schem");
        for (String bad : badFiles) {
            check(refusal(() -> clips.move(h.player, false, "ok/fine.schem", bad, new Captured<>())).reason()
                    == RejectReason.INVALID, "move to " + bad);
            check(refusal(() -> clips.move(h.player, false, bad, "ok/new.schem", new Captured<>())).reason()
                    == RejectReason.INVALID, "move from " + bad);
            check(refusal(() -> clips.delete(h.player, false, bad, new Captured<>())).reason() == RejectReason.INVALID,
                    "delete " + bad);
        }
        List<String> badFolders = List.of("..", "../..", "ok/..", "/", "C:", "ok\\b", "CON", "ok/", ".git", ".trash",
                "_x", "a b", "", "_players", "_players/" + UUID.randomUUID(), "_players/not-a-uuid");
        for (String bad : badFolders) {
            check(refusal(() -> clips.createFolder(h.player, bad, new Captured<>())).reason() == RejectReason.INVALID,
                    "create " + bad);
            check(refusal(() -> clips.delete(h.player, true, bad, new Captured<>())).reason() == RejectReason.INVALID,
                    "delete folder " + bad);
            check(refusal(() -> clips.move(h.player, true, "ok", bad, new Captured<>())).reason() == RejectReason.INVALID,
                    "rename folder to " + bad);
        }
        check(refusal(() -> clips.move(h.player, true, "ok", "elsewhere/ok", new Captured<>())).reason()
                == RejectReason.INVALID, "a folder is renamed in place, not moved");
        check(refusal(() -> clips.move(h.player, false, "ok/fine.schem", "ok/fine.schem", new Captured<>())).reason()
                == RejectReason.INVALID, "the same name");
        check(clips.requests(h.player.getUuid()) == 0, "a refusal left state behind");
        try (Stream<Path> files = Files.walk(sandbox)) {
            List<Path> stray = files.filter(p -> !p.startsWith(root) && !p.equals(sandbox)
                    && !p.equals(sandbox.resolve("outside"))).toList();
            check(stray.isEmpty(), "something was made outside the library: " + stray);
        }
        try (Stream<Path> files = Files.walk(root)) {
            check(files.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/')).toList()
                    .equals(List.of("ok/fine.schem")), "the library changed");
        }
        ClipTestSupport.deleteTree(sandbox);
        h.close();
        context.complete();
    }

    /**
     * Assets are referenced by content hash (pastes, scatter mixes, presets), so a rename breaks nothing: the loaded
     * asset takes the new path, a fresh lookup by hash finds the file under its new name, and a paste by hash works.
     * After a delete the hash no longer finds it, loaded or not.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_lib_manage_hash", tickLimit = LIMIT)
    public void renamedAssetsKeepTheirHashReferencesAndDeletedOnesAreGone(TestContext context) throws IOException {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 400);
        Box region = box(at[0], 100, at[1], at[0] + 7, 101, at[1] + 7);
        loadAndForce(world, region);
        Path root = ClipTestSupport.libraryRoot(context);
        byte[] gold = schem(h, "minecraft:gold_block");
        String hash = Sha256.digest(gold).hex();
        put(root, "trees/oak.schem", gold);
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);
        Captured<ClipboardService.Listing> listed = new Captured<>();
        Captured<ClipboardService.Outbound> firstPreview = new Captured<>();
        Captured<LibraryChange> renamed = new Captured<>();
        Captured<ClipboardService.Outbound> secondPreview = new Captured<>();
        Captured<LibraryChange> deleted = new Captured<>();
        Captured<ClipboardService.Outbound> thirdPreview = new Captured<>();
        RecordingListener paste = new RecordingListener();
        run(() -> clips.list(h.player, "trees", listed)); // indexes the file (as a player's listing does)
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    check(listed.get("list").entries().get(0).contentHash().equals(hash), "listing " + listed.value);
                    run(() -> clips.preview(h.player, new SourceRef.Asset(hash), firstPreview));
                })
                .createAndAdd(() -> {
                    check("trees/oak.schem".equals(firstPreview.get("preview").meta().get("path")), "preview path");
                    run(() -> clips.move(h.player, false, "trees/oak.schem", "trees/old_oak.schem", renamed));
                })
                .createAndAdd(() -> {
                    renamed.get("rename");
                    Optional<String> cached = h.service.assets().get(hash).map(asset -> asset.path().toString());
                    check(cached.equals(Optional.of("trees/old_oak.schem")), "the loaded asset keeps the old path: " + cached);
                    // A paste by hash from the loaded asset.
                    run(() -> h.service.run(h.player, new OpSpec.Paste(new SourceRef.Asset(hash),
                            new BlockPos(at[0] + 2, 100, at[1] + 2), Transform.IDENTITY, PasteOptions.DEFAULT),
                            RunOptions.DEFAULT, paste));
                    // And a fresh lookup by hash, as after a restart or an eviction.
                    h.service.assets().clear();
                    run(() -> clips.preview(h.player, new SourceRef.Asset(hash), secondPreview));
                })
                .createAndAdd(() -> check(paste.result != null && secondPreview.finished(), "paste and preview running"))
                .createAndAdd(() -> {
                    check(paste.result.outcome() == JobOutcome.COMPLETED && paste.result.changed() == 2, "paste " + paste.result);
                    check(world.getBlockState(pos(at[0] + 2, 100, at[1] + 2)).isOf(Blocks.GOLD_BLOCK), "not pasted");
                    check("trees/old_oak.schem".equals(secondPreview.get("preview by hash").meta().get("path")),
                            "the hash finds the renamed file: " + secondPreview.value.meta());
                    run(() -> clips.delete(h.player, false, "trees/old_oak.schem", deleted));
                })
                .createAndAdd(() -> {
                    deleted.get("delete");
                    check(h.service.assets().get(hash).isEmpty(), "a deleted asset stays loaded");
                    check(refusal(() -> h.service.run(h.player, new OpSpec.Paste(new SourceRef.Asset(hash),
                                    new BlockPos(at[0] + 4, 100, at[1] + 4), Transform.IDENTITY, PasteOptions.DEFAULT),
                            RunOptions.DEFAULT, null)).reason() == RejectReason.ASSET_NOT_LOADED, "pasted a deleted asset");
                    run(() -> clips.preview(h.player, new SourceRef.Asset(hash), thirdPreview));
                })
                .createAndAdd(() -> {
                    thirdPreview.failedWith(RejectReason.INVALID, "preview of a deleted asset");
                    forceChunks(world, region, false);
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A deleted file goes to the hidden trash: recoverable on disk, never listed, loaded or found by hash. Renames never
     * replace a file, and only an empty folder can be deleted.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_lib_manage_trash", tickLimit = LIMIT)
    public void deletesGoToTheTrashAndNothingIsReplaced(TestContext context) throws IOException {
        Harness h = new Harness(context);
        Path root = ClipTestSupport.libraryRoot(context);
        byte[] stone = schem(h, "minecraft:stone");
        byte[] dirt = schem(h, "minecraft:dirt");
        put(root, "a/stone.schem", stone);
        put(root, "a/dirt.schem", dirt);
        ServerPlayerEntity other = h.addPlayer(true);
        Files.createDirectories(root.resolve("empty"));
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);
        Captured<LibraryChange> onto = new Captured<>();
        Captured<LibraryChange> fullFolder = new Captured<>();
        Captured<LibraryChange> deleted = new Captured<>();
        Captured<LibraryChange> emptyFolder = new Captured<>();
        Captured<ClipboardService.Listing> rootListing = new Captured<>();
        Captured<ClipboardService.ClipboardInfo> load = new Captured<>();
        run(() -> clips.move(h.player, false, "a/stone.schem", "a/dirt.schem", onto));
        run(() -> clips.delete(other, true, "a", fullFolder)); // another player: one library write each at a time
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    onto.failedWith(RejectReason.INVALID, "rename onto an existing name");
                    check(onto.detail.equals("already exists: a/dirt.schem"), onto.detail);
                    fullFolder.failedWith(RejectReason.INVALID, "delete a folder that is not empty");
                    try {
                        check(java.util.Arrays.equals(Files.readAllBytes(root.resolve("a").resolve("dirt.schem")), dirt)
                                && java.util.Arrays.equals(Files.readAllBytes(root.resolve("a").resolve("stone.schem")),
                                stone), "a file was replaced");
                    } catch (IOException e) {
                        throw new GameTestException("a file is gone: " + e);
                    }
                    run(() -> clips.delete(h.player, false, "a/stone.schem", deleted));
                    run(() -> clips.delete(other, true, "empty", emptyFolder));
                })
                .createAndAdd(() -> {
                    deleted.get("delete");
                    emptyFolder.get("delete the empty folder");
                    check(!Files.exists(root.resolve("empty")), "the empty folder is still there");
                    try (Stream<Path> files = Files.walk(root.resolve(Library.TRASH_FOLDER))) {
                        List<String> trashed = files.filter(Files::isRegularFile)
                                .map(p -> root.relativize(p).toString().replace('\\', '/')).toList();
                        check(trashed.size() == 1 && trashed.get(0).matches("\\.trash/\\d{8}-\\d{6}-\\d{3}-[0-9a-f]{8}/a/stone\\.schem"),
                                "trash " + trashed);
                        check(java.util.Arrays.equals(Files.readAllBytes(root.resolve(trashed.get(0))), stone),
                                "the trashed file changed");
                    } catch (IOException e) {
                        throw new GameTestException("no trash: " + e);
                    }
                    run(() -> clips.list(h.player, "", rootListing));
                    run(() -> clips.load(h.player, "a/stone.schem", load));
                })
                .createAndAdd(() -> {
                    List<String> shown = rootListing.get("root").entries().stream().map(e -> e.path()).toList();
                    check(shown.equals(List.of("a", LibraryPath.SHARED)), "the root lists " + shown); // the trash is never listed; Shared with me always is
                    load.failedWith(RejectReason.INVALID, "load of a deleted asset");
                    check(refusal(() -> clips.list(h.player, ".trash", new Captured<>())).reason() == RejectReason.INVALID,
                            "the trash is listed");
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * What other players are told about a change: everything they may read, a blank for a path in someone else's
     * folder (a move into a private folder looks like a delete), and nothing without the library.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_lib_manage_push", tickLimit = LIMIT)
    public void pushedChangesShowOnlyWhatEachPlayerMayRead(TestContext context) {
        Harness h = new Harness(context);
        ServerPlayerEntity owner = h.addPlayer(false);
        ServerPlayerEntity other = h.addPlayer(false);
        ServerPlayerEntity useOnly = h.addPlayer(false);
        EditTestSupport.grant(owner, Perm.USE, Perm.CLIPBOARD, Perm.LIBRARY_WRITE);
        EditTestSupport.grant(other, Perm.USE, Perm.CLIPBOARD);
        EditTestSupport.grant(useOnly, Perm.USE);
        Path root = ClipTestSupport.libraryRoot(context);
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);
        String own = "_players/" + owner.getUuid() + "/x.schem";
        LibraryChange intoOwn = new LibraryChange(false, "shared/x.schem", own);
        LibraryChange outOfOwn = new LibraryChange(false, own, "shared/x.schem");
        LibraryChange ownFolder = new LibraryChange(true, "", "_players/" + owner.getUuid() + "/sub");
        check(clips.shownTo(owner, intoOwn).equals(Optional.of(intoOwn)), "the owner sees their own folder");
        check(clips.shownTo(h.player, intoOwn).equals(Optional.of(intoOwn)), "an admin sees everything");
        check(clips.shownTo(other, intoOwn).equals(Optional.of(new LibraryChange(false, "shared/x.schem", ""))),
                "another player sees it leave the shared folder, not where it went");
        check(clips.shownTo(other, outOfOwn).equals(Optional.of(new LibraryChange(false, "", "shared/x.schem"))),
                "and arrive, not where it came from");
        check(clips.shownTo(other, ownFolder).isEmpty(), "nothing of another player's folder");
        check(clips.shownTo(useOnly, intoOwn).isEmpty(), "nothing without clipboard");
        h.close();
        context.complete();
    }

    /**
     * A preview and a load by one player race another player's delete of the same file: whatever the order, the
     * deleted asset is not left loaded, so it can't be pasted by hash.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_lib_manage_stale", tickLimit = LIMIT)
    public void anAssetDeletedWhileItLoadsIsNotLeftLoaded(TestContext context) throws IOException {
        Harness h = new Harness(context);
        ServerPlayerEntity other = h.addPlayer(true);
        ServerPlayerEntity third = h.addPlayer(true);
        Path root = ClipTestSupport.libraryRoot(context);
        byte[] stone = schem(h, "minecraft:stone");
        String hash = Sha256.digest(stone).hex();
        put(root, "a/x.schem", stone);
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);
        Captured<ClipboardService.Listing> listed = new Captured<>();
        Captured<ClipboardService.Outbound> preview = new Captured<>();
        Captured<ClipboardService.ClipboardInfo> load = new Captured<>();
        Captured<LibraryChange> deleted = new Captured<>();
        run(() -> clips.list(h.player, "a", listed)); // indexes it, so a preview by hash finds it
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    listed.get("list");
                    run(() -> clips.preview(h.player, new SourceRef.Asset(hash), preview));
                    run(() -> clips.load(third, "a/x.schem", load));
                    run(() -> clips.delete(other, false, "a/x.schem", deleted));
                })
                .createAndAdd(() -> check(preview.finished() && load.finished() && deleted.finished(), "running"))
                .createAndAdd(() -> {
                    deleted.get("delete");
                    check(h.service.assets().get(hash).isEmpty(), "the deleted asset stayed loaded");
                    check(refusal(() -> h.service.run(h.player, new OpSpec.Paste(new SourceRef.Asset(hash),
                                    BlockPos.ORIGIN, Transform.IDENTITY, PasteOptions.DEFAULT), RunOptions.DEFAULT, null))
                            .reason() == RejectReason.ASSET_NOT_LOADED, "pasted a deleted asset by hash");
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** Two players change the same file in the same tick: exactly one succeeds, the other is refused cleanly. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_lib_manage_race", tickLimit = LIMIT)
    public void racingPlayersGetOneSuccessAndOneCleanRefusal(TestContext context) throws IOException {
        Harness h = new Harness(context);
        ServerPlayerEntity other = h.addPlayer(true);
        ServerPlayerEntity third = h.addPlayer(true);
        ServerPlayerEntity fourth = h.addPlayer(true);
        Path root = ClipTestSupport.libraryRoot(context);
        byte[] stone = schem(h, "minecraft:stone");
        put(root, "race/one.schem", stone);
        put(root, "race/two.schem", stone);
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);
        Captured<LibraryChange> renameA = new Captured<>();
        Captured<LibraryChange> renameB = new Captured<>();
        Captured<LibraryChange> rename = new Captured<>();
        Captured<LibraryChange> delete = new Captured<>();
        run(() -> clips.move(h.player, false, "race/one.schem", "race/a.schem", renameA));
        run(() -> clips.move(other, false, "race/one.schem", "race/b.schem", renameB));
        // Each player has one library write at a time, so the second race is between two more players.
        run(() -> clips.move(third, false, "race/two.schem", "race/c.schem", rename));
        run(() -> clips.delete(fourth, false, "race/two.schem", delete));
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(renameA.finished() && renameB.finished() && rename.finished() && delete.finished(),
                        "changes running"))
                .createAndAdd(() -> {
                    for (List<Captured<LibraryChange>> pair : List.of(List.of(renameA, renameB), List.of(rename, delete))) {
                        long wins = pair.stream().filter(c -> c.value != null).count();
                        check(wins == 1, "wins " + wins + ": " + pair.get(0).reason + " / " + pair.get(1).reason);
                        for (Captured<LibraryChange> answer : pair) {
                            check(answer.calls == 1, "answered " + answer.calls + " times");
                            if (answer.value == null) {
                                check(answer.reason == RejectReason.INVALID && answer.detail.startsWith("not found: "),
                                        "a clean refusal: " + answer.reason + " " + answer.detail);
                            }
                        }
                    }
                    int present = 0;
                    for (String name : List.of("one", "a", "b")) {
                        if (Files.exists(root.resolve("race").resolve(name + ".schem"))) present++;
                    }
                    check(present == 1 && !Files.exists(root.resolve("race").resolve("one.schem")), present + " copies of one");
                    boolean renamedTwo = Files.exists(root.resolve("race").resolve("c.schem"));
                    check(renamedTwo == (rename.value != null), "the rename's answer matches the disk");
                    check(!Files.exists(root.resolve("race").resolve("two.schem")), "two is still there");
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .completeIfSuccessful();
    }
}
