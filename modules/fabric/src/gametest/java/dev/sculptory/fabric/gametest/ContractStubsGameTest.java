package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.ClipboardGameTest.refusal;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BiomeSpec;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.HeightmapSpec;
import dev.sculptory.core.brush.SculptMode;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.impl.EngineEditService;
import dev.sculptory.server.platform.WriteOptions;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

/**
 * The contracts of the batch after 0.3.0-alpha (protocol 6) before its streams land: every new op, orientation, paste
 * option and brush is refused cleanly by the real service ({@code INVALID}, {@value EngineEditService#NOT_IMPLEMENTED}),
 * after the permission check it will keep, and writes nothing, holds nothing and pushes no history. Each stream deletes
 * its own lines here when it makes them work. Region slot 990.
 */
public final class ContractStubsGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_contract_stubs", tickLimit = LIMIT)
    public void theProtocolSixStubsAreRefusedCleanly(TestContext context) {
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
        Region region = new Region.Cuboid(box(x0, 100, z0, x0 + 7, 102, z0 + 7));
        SourceRef clipboard = new SourceRef.Clipboard(new UUID(9, 9));
        List<String> undoBefore = h.service.history(h.player).undoLabels();

        // ws/biomes: the biome fill and pasted biomes. Free orientations (ws/free-rotation, FreeRotationGameTest) and the
        // multi-paste, free placements included (ws/extrude-paths, ExtrudePathsGameTest), work.
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 2 * x0 + 7, 0);
        OpSpec.BiomeFill biomeFill = new OpSpec.BiomeFill(region, "minecraft:desert", true);
        List<OpSpec> ops = List.of(
                biomeFill,
                new OpSpec.BiomeFill(region, "minecraft:desert", false, mirror),
                new OpSpec.Paste(clipboard, new BlockPos(x0, 101, z0), Transform.IDENTITY,
                        PasteOptions.DEFAULT.withBiomes(true)));
        for (OpSpec op : ops) {
            EditRejected refused = refusal(() -> h.service.run(h.player, op, RunOptions.DEFAULT, null));
            check(refused.reason() == RejectReason.INVALID && EngineEditService.NOT_IMPLEMENTED.equals(refused.detail()),
                    op.getClass().getSimpleName() + " refused " + refused.reason() + ": " + refused.detail());
        }
        // ws/heightmap and ws/biomes: their brushes.
        BrushSpec heightmap = new BrushSpec(BrushTool.HEIGHTMAP, 2, 1f, Falloff.SMOOTH, Shape.SQUARE, null, SurfaceMask.ANY,
                0, 0, 1L, null, Symmetry.NONE, null, SculptMode.TERRAIN, null, null,
                new HeightmapSpec(5, new byte[] {0, 0, 0, 0, 0, 0, 9, 9, 9, 0, 0, 9, (byte) 255, 9, 0, 0, 9, 9, 9, 0, 0, 0,
                        0, 0, 0}, 8, false), null);
        BrushSpec biome = new BrushSpec(BrushTool.BIOME, 4, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0,
                0, 1L, null, Symmetry.NONE, null, SculptMode.TERRAIN, null, null, null,
                new BiomeSpec("minecraft:cherry_grove", true));
        for (BrushSpec spec : List.of(heightmap, biome)) {
            EditRejected refused = refusal(() -> h.service.beginStroke(h.player, 7, spec));
            check(refused.reason() == RejectReason.INVALID && EngineEditService.NOT_IMPLEMENTED.equals(refused.detail()),
                    spec.tool() + " stroke refused " + refused.reason() + ": " + refused.detail());
        }
        // The biome node is checked first, as it will be: a player with the brush and region nodes but not biome.
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.BRUSH, Perm.REGION, Perm.CLIPBOARD);
        EditRejected noFill = refusal(() -> h.service.run(builder, biomeFill, RunOptions.DEFAULT, null));
        check(noFill.reason() == RejectReason.NO_PERMISSION, "a fill without the biome node: " + noFill.reason());
        EditRejected noPaint = refusal(() -> h.service.beginStroke(builder, 8, biome));
        check(noPaint.reason() == RejectReason.NO_PERMISSION, "a biome stroke without the biome node: " + noPaint.reason());
        // The multi-paste needs the clipboard node, checked before its source or placements: use and region are not enough.
        OpSpec.PasteMany exactStamp = new OpSpec.PasteMany(clipboard, List.of(new OpSpec.PasteMany.Placement(
                new BlockPos(x0, 101, z0), Orientation.IDENTITY)), PasteOptions.DEFAULT);
        EditRejected stampNoNode = refusal(() -> h.service.run(h.addPlayer(false), exactStamp, RunOptions.DEFAULT, null));
        check(stampNoNode.reason() == RejectReason.NO_PERMISSION, "a stamp without any node: " + stampNoNode.reason());
        ServerPlayerEntity noClipboard = h.addPlayer(false);
        EditTestSupport.grant(noClipboard, Perm.USE, Perm.REGION, Perm.BRUSH);
        EditRejected stampNoClipboard = refusal(() -> h.service.run(noClipboard, exactStamp, RunOptions.DEFAULT, null));
        check(stampNoClipboard.reason() == RejectReason.NO_PERMISSION
                && Perm.CLIPBOARD.node().equals(stampNoClipboard.detail()),
                "a stamp without the clipboard node: " + stampNoClipboard.reason() + " " + stampNoClipboard.detail());

        // Nothing written, nothing held, nothing pushed, no stroke left open.
        check(!h.service.executor().isLocked(h.world, area), "a refusal held the area");
        check(h.service.jobs(h.player.getUuid()).isEmpty(), "a refusal left a job");
        check(h.service.history(h.player).undoLabels().equals(undoBefore), "a refusal pushed history");
        check(h.service.dabs(h.player, 7, 0, List.of(new dev.sculptory.core.brush.Dab(0, (x0 + 3) * 16, 101 * 16,
                (z0 + 3) * 16, 255))).reason() == RejectReason.INVALID, "a refused stroke takes no dabs");
        for (int x = x0; x <= x0 + 7; x++) {
            for (int z = z0; z <= z0 + 7; z++) {
                check(h.world.getBlockState(new net.minecraft.util.math.BlockPos(x, 100, z)).isOf(Blocks.STONE),
                        "a refusal changed " + x + ", 100, " + z);
                check(h.world.getBlockState(new net.minecraft.util.math.BlockPos(x, 101, z)).isAir(),
                        "a refusal wrote above " + x + ", " + z);
            }
        }
        forceChunks(h.world, area, false);
        h.close();
        context.complete();
    }
}
