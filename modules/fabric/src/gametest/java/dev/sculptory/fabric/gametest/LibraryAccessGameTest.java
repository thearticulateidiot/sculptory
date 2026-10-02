package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.ClipboardGameTest.refusal;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;
import static dev.sculptory.fabric.gametest.ScatterGameTest.floor;
import static dev.sculptory.fabric.gametest.ScatterGameTest.planNow;
import static dev.sculptory.fabric.gametest.ScatterGameTest.preview;
import static dev.sculptory.fabric.gametest.ScatterGameTest.region;
import static dev.sculptory.fabric.gametest.ScatterGameTest.request;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.scatter.ScatterArea;
import dev.sculptory.core.schem.SchematicCodec;
import dev.sculptory.core.schem.SchematicMetadata;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.engine.impl.ServerClipboards;
import dev.sculptory.fabric.engine.impl.ServerScatter;
import dev.sculptory.fabric.gametest.ClipTestSupport.Captured;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.protocol.v2.AssetAccess;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.ClipboardService.AccessChange;
import dev.sculptory.server.engine.ClipboardService.LibraryChange;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;

/**
 * Per-asset access through the engine's guarded payload paths, with real players and permissions: a restricted
 * asset is never served to an ungranted player (listing, load by path, preview by hash, paste by hash, scatter
 * variant, commit), a revocation ends the grantee's plan, the "Shared with me" listing, a differently spelt path,
 * and the rename sequence (neither a plan nor a cached asset outlives a grant through a rename). One library write
 * and at most two requests per player per tick, as the request slots allow.
 */
public final class LibraryAccessGameTest implements FabricGameTest {
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

    /** A load whose refusal, at admission or later, lands in {@code reply} either way. */
    private static void load(ServerClipboards clips, ServerPlayerEntity player, String path,
                             Captured<ClipboardService.ClipboardInfo> reply) {
        try {
            clips.load(player, path, reply);
        } catch (EditRejected e) {
            reply.failed(e.reason(), e.getMessage());
        }
    }

    private static AssetAccess only(ServerPlayerEntity player) {
        return AssetAccess.listed(List.of(new AssetAccess.Grantee(player.getUuid(), player.getGameProfile().getName())));
    }

    private static List<String> paths(ClipboardService.Listing listing) {
        return listing.entries().stream().map(e -> e.path()).toList();
    }

    /** A paste of the asset {@code hash} by {@code player}, expected to be refused. */
    private static EditRejected pasteRefusal(Harness h, ServerPlayerEntity player, String hash, int x, int z) {
        return refusal(() -> h.service.run(player, new OpSpec.Paste(new SourceRef.Asset(hash),
                new BlockPos(x, 102, z), Transform.IDENTITY, PasteOptions.DEFAULT), RunOptions.DEFAULT, null));
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_lib_access", tickLimit = LIMIT)
    public void restrictedAssetsAreNeverServedToUngrantedPlayers(TestContext context) throws IOException {
        Harness h = new Harness(context);
        ServerPlayerEntity writer = h.player; // op: library.write and admin
        ServerPlayerEntity bob = h.addPlayer(false);
        ServerPlayerEntity carol = h.addPlayer(false);
        EditTestSupport.grant(bob, Perm.USE, Perm.CLIPBOARD, Perm.SCATTER);
        EditTestSupport.grant(carol, Perm.USE, Perm.CLIPBOARD, Perm.SCATTER);
        int[] at = regionCorner(context, 800);
        int x0 = at[0], z0 = at[1];
        Box all = floor(h, x0, z0, 32, 32);
        ScatterArea area = region(x0, z0, x0 + 31, z0 + 31);
        Path root = ClipTestSupport.libraryRoot(context);
        byte[] gold = schem(h, "minecraft:gold_block");
        String hash = Sha256.digest(gold).hex();
        String hut = "_players/" + writer.getUuid() + "/hut.schem";
        put(root, "trees/oak.schem", gold);
        put(root, hut, schem(h, "minecraft:dirt"));
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);
        ServerScatter scatter = new ServerScatter(h.service);
        UUID bobId = bob.getUuid();

        Captured<ClipboardService.Listing> indexed = new Captured<>();
        Captured<AccessChange> grantOak = new Captured<>();
        Captured<AccessChange> grantHut = new Captured<>();
        Captured<ClipboardService.Listing> carolTrees = new Captured<>();
        Captured<ClipboardService.Listing> bobTrees = new Captured<>();
        Captured<ClipboardService.Outbound> bobPreview = new Captured<>();
        Captured<ClipboardService.Outbound> carolPreview = new Captured<>();
        Captured<ClipboardService.Listing> bobShared = new Captured<>();
        Captured<ClipboardService.Listing> carolShared = new Captured<>();
        Captured<ClipboardService.ClipboardInfo> carolLoad = new Captured<>();
        Captured<ClipboardService.ClipboardInfo> carolMissing = new Captured<>();
        Captured<ClipboardService.ClipboardInfo> carolSpelt = new Captured<>();
        Captured<ClipboardService.ClipboardInfo> bobSpelt = new Captured<>();
        ScatterGameTest.Reply[] plan = new ScatterGameTest.Reply[2];
        Captured<AccessChange> revoke = new Captured<>();
        Captured<ClipboardService.Outbound> bobPreviewAfter = new Captured<>();
        Captured<ClipboardService.ClipboardInfo> bobLoadAfter = new Captured<>();
        Captured<AccessChange> regrant = new Captured<>();
        Captured<ClipboardService.Outbound> bobPreviewAgain = new Captured<>();
        Captured<LibraryChange> renamed = new Captured<>();
        Captured<AccessChange> revokeRenamed = new Captured<>();
        Captured<ClipboardService.Outbound> bobPreviewRenamed = new Captured<>();
        Captured<ClipboardService.ClipboardInfo> bobLoadRenamed = new Captured<>();
        Captured<ClipboardService.ClipboardInfo> bobLoadOld = new Captured<>();
        run(() -> clips.list(writer, "trees", indexed)); // indexes the file, as a player's listing does
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    check(indexed.get("index").entries().get(0).contentHash().equals(hash), "listing " + indexed.value);
                    run(() -> clips.setAccess(writer, "trees/oak.schem", only(bob), grantOak));
                })
                .createAndAdd(() -> {
                    check(grantOak.get("grant oak").after().names(bobId), "grant " + grantOak.value);
                    run(() -> clips.setAccess(writer, hut, only(bob), grantHut));
                })
                .createAndAdd(() -> {
                    grantHut.get("grant hut");
                    run(() -> clips.list(carol, "trees", carolTrees));
                    run(() -> clips.list(bob, "trees", bobTrees));
                })
                .createAndAdd(() -> {
                    check(paths(carolTrees.get("carol trees")).isEmpty(), "carol sees a restricted entry: " + carolTrees.value);
                    check(paths(bobTrees.get("bob trees")).equals(List.of("trees/oak.schem"))
                            && bobTrees.value.entries().get(0).restricted(), "bob's listing: " + bobTrees.value);
                    run(() -> clips.preview(bob, new SourceRef.Asset(hash), bobPreview));
                    run(() -> clips.preview(carol, new SourceRef.Asset(hash), carolPreview));
                })
                .createAndAdd(() -> {
                    check("trees/oak.schem".equals(bobPreview.get("bob preview").meta().get("path")), "bob's preview");
                    carolPreview.failedWith(RejectReason.INVALID, "carol's preview by hash");
                    check(carolPreview.value == null, "a payload was built for carol");
                    run(() -> clips.list(bob, "_shared", bobShared));
                    run(() -> clips.list(carol, "_shared", carolShared));
                })
                .createAndAdd(() -> {
                    check(paths(bobShared.get("bob shared")).equals(List.of(hut)), "Shared with me: " + bobShared.value);
                    check(!bobShared.value.writable() && bobShared.value.entries().get(0).restricted(), "shared flags");
                    check(paths(carolShared.get("carol shared")).isEmpty(), "carol's Shared with me: " + carolShared.value);
                    // One load (one clipboard being made) per player at a time, so the loads come one per tick.
                    load(clips, carol, "trees/oak.schem", carolLoad);
                    load(clips, bob, "Trees/oak.schem", bobSpelt);
                })
                .createAndAdd(() -> {
                    carolLoad.failedWith(RejectReason.INVALID, "carol's load by path");
                    check(carolLoad.detail.contains("not found, or not shared"), "carol's refusal says " + carolLoad.detail);
                    bobSpelt.failedWith(RejectReason.INVALID, "even a grantee must spell the entry as it is");
                    load(clips, carol, "trees/gone.schem", carolMissing);
                })
                .createAndAdd(() -> {
                    carolMissing.failedWith(RejectReason.INVALID, "carol's load of a missing entry");
                    check(carolLoad.detail.replace("oak", "gone").equals(carolMissing.detail),
                            "a restricted and a missing entry answer alike: " + carolLoad.detail + " / " + carolMissing.detail);
                    load(clips, carol, "trees/OAK.schem", carolSpelt);
                })
                .createAndAdd(() -> {
                    carolSpelt.failedWith(RejectReason.INVALID, "carol's load by another spelling");
                    // The asset is cached (bob's preview): carol still cannot paste or scatter it by hash.
                    check(h.service.assets().get(hash).isPresent(), "the asset is not cached");
                    check(pasteRefusal(h, carol, hash, x0 + 2, z0 + 2).reason() == RejectReason.ASSET_NOT_LOADED, "carol pasted by hash");
                    check(refusal(() -> scatter.preview(carol, request(area, 4, 1L, new SourceRef.Asset(hash)),
                            new ScatterGameTest.Reply())).reason() == RejectReason.ASSET_NOT_LOADED, "carol scattered by hash");
                    plan[0] = preview(scatter, bob, request(area, 4, 1L, new SourceRef.Asset(hash)));
                    planNow(scatter, plan[0]);
                    check(plan[0].get("bob's plan").placements() > 0, "an empty plan");
                    // Revoked: bob's plan ends, and nothing of the asset reaches him any more.
                    run(() -> clips.setAccess(writer, "trees/oak.schem", only(writer), revoke));
                })
                .createAndAdd(() -> {
                    revoke.get("revoke");
                    check(h.service.scatterPlans().get(bobId).isEmpty(), "the revoked player's plan survived");
                    check(refusal(() -> h.service.run(bob, new OpSpec.ScatterCommit(plan[0].plan.planId()), RunOptions.DEFAULT,
                            null)).reason() == RejectReason.INVALID, "a revoked plan committed");
                    check(pasteRefusal(h, bob, hash, x0 + 2, z0 + 2).reason() == RejectReason.ASSET_NOT_LOADED, "bob pasted after the revocation");
                    check(refusal(() -> scatter.preview(bob, request(area, 4, 1L, new SourceRef.Asset(hash)),
                            new ScatterGameTest.Reply())).reason() == RejectReason.ASSET_NOT_LOADED, "bob scattered after the revocation");
                    run(() -> clips.preview(bob, new SourceRef.Asset(hash), bobPreviewAfter));
                })
                .createAndAdd(() -> {
                    bobPreviewAfter.failedWith(RejectReason.INVALID, "bob's preview by hash after the revocation");
                    check(bobPreviewAfter.value == null, "a payload was built for bob after the revocation");
                    load(clips, bob, "trees/oak.schem", bobLoadAfter);
                })
                .createAndAdd(() -> {
                    bobLoadAfter.failedWith(RejectReason.INVALID, "bob's load after the revocation");
                    // Granted again, previewed and planned; then the entry is renamed while restricted.
                    run(() -> clips.setAccess(writer, "trees/oak.schem", only(bob), regrant));
                })
                .createAndAdd(() -> {
                    regrant.get("grant again");
                    run(() -> clips.preview(bob, new SourceRef.Asset(hash), bobPreviewAgain));
                })
                .createAndAdd(() -> {
                    bobPreviewAgain.get("bob's preview again");
                    plan[1] = preview(scatter, bob, request(area, 4, 2L, new SourceRef.Asset(hash)));
                    planNow(scatter, plan[1]);
                    plan[1].get("bob's second plan");
                    check(h.service.assets().get(hash).isPresent(), "the asset is not cached before the rename");
                    run(() -> clips.move(writer, false, "trees/oak.schem", "trees/oak2.schem", renamed));
                    // Right after admission, before any file work: nothing of the restricted file is left in memory
                    // under its old path, so a check by that path cannot pass while the grant moves.
                    check(h.service.assets().get(hash).isEmpty(), "the restricted asset stayed cached through the rename");
                    check(h.service.scatterPlans().get(bobId).isEmpty(), "the plan on a restricted asset outlived its rename");
                })
                .createAndAdd(() -> {
                    renamed.get("rename");
                    check(refusal(() -> h.service.run(bob, new OpSpec.ScatterCommit(plan[1].plan.planId()), RunOptions.DEFAULT,
                            null)).reason() == RejectReason.INVALID, "the renamed asset's plan committed");
                    run(() -> clips.setAccess(writer, "trees/oak2.schem", only(writer), revokeRenamed));
                })
                .createAndAdd(() -> {
                    revokeRenamed.get("revoke after the rename");
                    check(pasteRefusal(h, bob, hash, x0 + 4, z0 + 4).reason() == RejectReason.ASSET_NOT_LOADED, "bob pasted the renamed asset");
                    run(() -> clips.preview(bob, new SourceRef.Asset(hash), bobPreviewRenamed));
                })
                .createAndAdd(() -> {
                    bobPreviewRenamed.failedWith(RejectReason.INVALID, "bob's preview of the renamed asset");
                    check(bobPreviewRenamed.value == null, "a payload was built after the rename and revocation");
                    load(clips, bob, "trees/oak2.schem", bobLoadRenamed);
                })
                .createAndAdd(() -> {
                    bobLoadRenamed.failedWith(RejectReason.INVALID, "bob's load of the renamed asset");
                    load(clips, bob, "trees/oak.schem", bobLoadOld);
                })
                .createAndAdd(() -> {
                    bobLoadOld.failedWith(RejectReason.INVALID, "bob's load of the old name");
                    check(bobLoadRenamed.detail.equals(bobLoadOld.detail.replace("oak.schem", "oak2.schem")),
                            "a restricted and a missing entry answer alike: " + bobLoadRenamed.detail + " / " + bobLoadOld.detail);
                    check(!h.service.executor().isLocked(h.world, all), "a refusal left a job or hold behind");
                    scatter.shutdown();
                    forceChunks(h.world, all, false);
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .completeIfSuccessful();
    }
}
