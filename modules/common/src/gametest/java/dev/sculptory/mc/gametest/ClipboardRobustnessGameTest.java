package dev.sculptory.mc.gametest;

import static dev.sculptory.mc.gametest.EngineTestSupport.check;
import static dev.sculptory.mc.gametest.GameTestSuite.EMPTY_STRUCTURE;

import dev.sculptory.mc.gametest.ClipTestSupport.Captured;
import dev.sculptory.mc.gametest.EditTestSupport.Harness;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.impl.ServerClipboards;
import java.nio.file.Path;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/** Clipboard requests release what they hold whatever goes wrong: throwing checks, throwing replies, shutdown. */
public final class ClipboardRobustnessGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /**
     * An upload whose permission re-check fails (a broken permissions mod throws) is refused NO_PERMISSION, since a
     * failed check grants nothing, and the refusal releases the player's clipboard and request slots, which the upload
     * had not handed off yet.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_robust_upload", timeoutTicks = LIMIT)
    public void uploadLeaseReleasedWhenChecksThrow(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerPlayer builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.CLIPBOARD, Perm.SCHEMATIC_IMPORT);
        Path root = ClipTestSupport.libraryRoot(context);
        var clips = ClipTestSupport.clipboards(h, root);
        ClipboardService.Upload upload;
        try {
            upload = clips.beginUpload(builder, "x.schem", 100);
        } catch (EditRejected e) {
            throw new GameTestAssertException("upload refused: " + e.getMessage());
        }
        check(clips.building(builder.getUUID()) && clips.requests(builder.getUUID()) == 1, "nothing reserved");
        Captured<ClipboardService.ClipboardInfo> reply = new Captured<>();
        EditTestSupport.failPermissionChecks(builder, true);
        int failedBefore = EditTestSupport.failedChecks(builder);
        try {
            upload.completed(new byte[100], reply);
        } catch (RuntimeException e) {
            throw new GameTestAssertException("a failing permission check escaped the upload: " + e);
        } finally {
            EditTestSupport.failPermissionChecks(builder, false);
        }
        check(EditTestSupport.failedChecks(builder) > failedBefore, "the permission check did not fail");
        reply.failedWith(RejectReason.NO_PERMISSION, "the upload after a failed check");
        check(!clips.building(builder.getUUID()) && clips.requests(builder.getUUID()) == 0, "the upload leaked its slots");
        try {
            clips.beginUpload(builder, "again.schem", 100).abort();
        } catch (EditRejected e) {
            throw new GameTestAssertException("the slots were not released: " + e.getMessage());
        }
        check(clips.requests(builder.getUUID()) == 0, "abort did not release");
        ClipTestSupport.deleteTree(root.getParent());
        h.close();
        context.succeed();
    }

    /** A reply whose {@code done} throws is answered with {@code INVALID "internal error"}, and its slot is freed. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_robust_reply", timeoutTicks = LIMIT)
    public void throwingRepliesAreAnsweredAndReleased(GameTestHelper context) {
        Harness h = new Harness(context);
        Path root = ClipTestSupport.libraryRoot(context);
        var clips = ClipTestSupport.clipboards(h, root);
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
            throw new GameTestAssertException("list refused: " + e.getMessage());
        }
        context.startSequence()
                .thenWaitUntil(() -> check(failures.finished(), "no answer after the reply threw"))
                .thenWaitUntil(() -> {
                    check(done[0] == 1, "done called " + done[0] + " times");
                    failures.failedWith(RejectReason.INVALID, "the fallback answer");
                    check("internal error".equals(failures.detail), "detail " + failures.detail);
                    check(clips.requests(h.player.getUUID()) == 0, "the request slot leaked");
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .thenSucceed();
    }

    /** Work finishing while the service shuts down only releases its slot: no reply, no clipboard changes. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_robust_shutdown", timeoutTicks = LIMIT)
    public void shutdownOnlyReleasesLeases(GameTestHelper context) {
        Harness h = new Harness(context);
        Path root = ClipTestSupport.libraryRoot(context);
        var clips = ClipTestSupport.clipboards(h, root);
        Captured<ClipboardService.Listing> listed = new Captured<>();
        try {
            clips.list(h.player, "", listed);
        } catch (EditRejected e) {
            throw new GameTestAssertException("list refused: " + e.getMessage());
        }
        clips.shutdown(); // waits for the executor
        context.startSequence()
                .thenWaitUntil(() -> {
                    check(clips.requests(h.player.getUUID()) == 0, "the slot was not released at shutdown");
                    check(!listed.finished(), "a reply was delivered while closing");
                    try {
                        clips.list(h.player, "", new Captured<>());
                        throw new GameTestAssertException("work was accepted after shutdown");
                    } catch (EditRejected e) {
                        check(e.reason() == RejectReason.QUEUE_FULL, "reason " + e.reason());
                    }
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .thenSucceed();
    }
}
