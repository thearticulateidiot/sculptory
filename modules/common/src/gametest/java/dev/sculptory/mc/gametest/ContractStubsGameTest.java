package dev.sculptory.mc.gametest;

import static dev.sculptory.mc.gametest.ClipboardGameTest.refusal;
import static dev.sculptory.mc.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.mc.gametest.EngineTestSupport.box;
import static dev.sculptory.mc.gametest.EngineTestSupport.check;
import static dev.sculptory.mc.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.mc.gametest.EngineTestSupport.regionCorner;
import static dev.sculptory.mc.gametest.GameTestSuite.EMPTY_STRUCTURE;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.mc.gametest.EditTestSupport.Harness;
import dev.sculptory.mc.world.BlockWriter;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.platform.WriteOptions;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/**
 * The contracts of the batch after 0.3.0-alpha (protocol 6), all built now: what stays is that the multi-paste asks for
 * the clipboard node before anything else, and that a refusal writes nothing, holds nothing and pushes no history.
 * Region slot 990.
 */
public final class ContractStubsGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_contract_stubs", timeoutTicks = LIMIT)
    public void theProtocolSixStubsAreRefusedCleanly(GameTestHelper context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 990);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 99, z0, x0 + 7, 104, z0 + 7);
        loadAndForce(h.world, area);
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        int stone = h.state("minecraft:stone");
        for (int x = x0; x <= x0 + 7; x++) {
            for (int z = z0; z <= z0 + 7; z++) writer.write(x, 100, z, stone, null);
        }
        SourceRef clipboard = new SourceRef.Clipboard(new UUID(9, 9));
        List<String> undoBefore = h.service.history(h.player).undoLabels();

        // Every protocol 6 contract is built now (BiomesGameTest, HeightmapGameTest, FreeRotationGameTest,
        // ExtrudePathsGameTest). What stays here: the multi-paste needs the clipboard node, checked before its source or
        // placements (use and region are not enough), and a refusal leaves nothing behind.
        OpSpec.PasteMany exactStamp = new OpSpec.PasteMany(clipboard, List.of(new OpSpec.PasteMany.Placement(
                new BlockPos(x0, 101, z0), Orientation.IDENTITY)), PasteOptions.DEFAULT);
        EditRejected stampNoNode = refusal(() -> h.service.run(h.addPlayer(false), exactStamp, RunOptions.DEFAULT, null));
        check(stampNoNode.reason() == RejectReason.NO_PERMISSION, "a stamp without any node: " + stampNoNode.reason());
        ServerPlayer noClipboard = h.addPlayer(false);
        EditTestSupport.grant(noClipboard, Perm.USE, Perm.REGION, Perm.BRUSH);
        EditRejected stampNoClipboard = refusal(() -> h.service.run(noClipboard, exactStamp, RunOptions.DEFAULT, null));
        check(stampNoClipboard.reason() == RejectReason.NO_PERMISSION
                && Perm.CLIPBOARD.node().equals(stampNoClipboard.detail()),
                "a stamp without the clipboard node: " + stampNoClipboard.reason() + " " + stampNoClipboard.detail());

        // Nothing written, nothing held, nothing pushed, no stroke left open.
        check(!h.service.executor().isLocked(h.world, area), "a refusal held the area");
        check(h.service.jobs(h.player.getUUID()).isEmpty(), "a refusal left a job");
        check(h.service.history(h.player).undoLabels().equals(undoBefore), "a refusal pushed history");
        check(h.service.dabs(h.player, 7, 0, List.of(new dev.sculptory.core.brush.Dab(0, (x0 + 3) * 16, 101 * 16,
                (z0 + 3) * 16, 255))).reason() == RejectReason.INVALID, "a refused stroke takes no dabs");
        for (int x = x0; x <= x0 + 7; x++) {
            for (int z = z0; z <= z0 + 7; z++) {
                check(h.world.getBlockState(new net.minecraft.core.BlockPos(x, 100, z)).is(Blocks.STONE),
                        "a refusal changed " + x + ", 100, " + z);
                check(h.world.getBlockState(new net.minecraft.core.BlockPos(x, 101, z)).isAir(),
                        "a refusal wrote above " + x + ", " + z);
            }
        }
        forceChunks(h.world, area, false);
        h.close();
        context.succeed();
    }
}
