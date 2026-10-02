package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EngineTestSupport.check;

import dev.sculptory.fabric.engine.impl.ServerClipboards;
import dev.sculptory.fabric.gametest.ClipTestSupport.Captured;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import java.nio.file.Path;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;

/** Clipboard requests release what they hold whatever goes wrong: throwing checks, throwing replies, shutdown. */
public final class ClipboardRobustnessGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /**
     * An upload whose permission re-check fails (a broken permissions mod throws) is refused NO_PERMISSION, since a
     * failed check grants nothing, and the refusal releases the player's clipboard and request slots, which the upload
     * had not handed off yet.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_robust_upload", tickLimit = LIMIT)
    public void uploadLeaseReleasedWhenChecksThrow(TestContext context) {
        Harness h = new Harness(context);
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.CLIPBOARD, Perm.SCHEMATIC_IMPORT);
        Path root = ClipTestSupport.libraryRoot(context);
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);
        ClipboardService.Upload upload;
        try {
            upload = clips.beginUpload(builder, "x.schem", 100);
        } catch (EditRejected e) {
            throw new GameTestException("upload refused: " + e.getMessage());
        }
        check(clips.building(builder.getUuid()) && clips.requests(builder.getUuid()) == 1, "nothing reserved");
        Captured<ClipboardService.ClipboardInfo> reply = new Captured<>();
        EditTestSupport.failPermissionChecks(builder, true);
        int failedBefore = EditTestSupport.failedChecks(builder);
        try {
            upload.completed(new byte[100], reply);
        } catch (RuntimeException e) {
            throw new GameTestException("a failing permission check escaped the upload: " + e);
        } finally {
            EditTestSupport.failPermissionChecks(builder, false);
        }
        check(EditTestSupport.failedChecks(builder) > failedBefore, "the permission check did not fail");
        reply.failedWith(RejectReason.NO_PERMISSION, "the upload after a failed check");
        check(!clips.building(builder.getUuid()) && clips.requests(builder.getUuid()) == 0, "the upload leaked its slots");
        try {
            clips.beginUpload(builder, "again.schem", 100).abort();
        } catch (EditRejected e) {
            throw new GameTestException("the slots were not released: " + e.getMessage());
        }
        check(clips.requests(builder.getUuid()) == 0, "abort did not release");
        ClipTestSupport.deleteTree(root.getParent());
        h.close();
        context.complete();
    }

    /** A reply whose {@code done} throws is answered with {@code INVALID "internal error"}, and its slot is freed. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_robust_reply", tickLimit = LIMIT)
    public void throwingRepliesAreAnsweredAndReleased(TestContext context) {
        Harness h = new Harness(context);
        Path root = ClipTestSupport.libraryRoot(context);
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);
        int[] done = {0};
        Captured<ClipboardService.Listing> failures = new Captured<>();
        ClipboardService.Reply<ClipboardService.Listing> reply = new ClipboardService.Reply<>() {
            @Override
            public void done(ClipboardService.Listing value) {
                done[0]++;
                throw new IllegalStateException("the reply failed");
            }

            @Override
            public void failed(RejectReason reason, String detail) {
                failures.failed(reason, detail);
            }
        };
        try {
            clips.list(h.player, "", reply);
        } catch (EditRejected e) {
            throw new GameTestException("list refused: " + e.getMessage());
        }
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(failures.finished(), "no answer after the reply threw"))
                .createAndAdd(() -> {
                    check(done[0] == 1, "done called " + done[0] + " times");
                    failures.failedWith(RejectReason.INVALID, "the fallback answer");
                    check("internal error".equals(failures.detail), "detail " + failures.detail);
                    check(clips.requests(h.player.getUuid()) == 0, "the request slot leaked");
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** Work finishing while the service shuts down only releases its slot: no reply, no clipboard changes. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_robust_shutdown", tickLimit = LIMIT)
    public void shutdownOnlyReleasesLeases(TestContext context) {
        Harness h = new Harness(context);
        Path root = ClipTestSupport.libraryRoot(context);
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);
        Captured<ClipboardService.Listing> listed = new Captured<>();
        try {
            clips.list(h.player, "", listed);
        } catch (EditRejected e) {
            throw new GameTestException("list refused: " + e.getMessage());
        }
        clips.shutdown(); // waits for the executor
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    check(clips.requests(h.player.getUuid()) == 0, "the slot was not released at shutdown");
                    check(!listed.finished(), "a reply was delivered while closing");
                    try {
                        clips.list(h.player, "", new Captured<>());
                        throw new GameTestException("work was accepted after shutdown");
                    } catch (EditRejected e) {
                        check(e.reason() == RejectReason.QUEUE_FULL, "reason " + e.reason());
                    }
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .completeIfSuccessful();
    }
}
