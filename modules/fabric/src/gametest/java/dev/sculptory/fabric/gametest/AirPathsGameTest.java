package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.generate.FaceCells;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.GroundMap;
import dev.sculptory.core.generate.PathExtrudeKernel;
import dev.sculptory.core.generate.RoadKernel;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.mc.world.BlockWriter;
import dev.sculptory.mc.world.McWorldReader;
import dev.sculptory.protocol.v2.OpLabel;
import dev.sculptory.server.platform.WriteOptions;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;

/**
 * Paths into the air on a real world: a Straight road ramping from the ground into the sky is a floating deck, its fill
 * below reaching down only where it meets the ground the world reads; a wall face carried along a Curve straight up 32
 * blocks keeps its stairs facing the way they did all the way up. Each builds and undoes exactly as one step. Region slots
 * 1600-1602.
 */
public final class AirPathsGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_air_road", timeoutTicks = LIMIT)
    public void aRoadRampingIntoTheSkyIsADeckWithFillOnlyNearTheGround(GameTestHelper context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 1600);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0 - 4, 96, z0 - 4, x0 + 28, 130, z0 + 4);
        loadAndForce(h.world, area);
        ExtrudePathsGameTest.ground(h, area);
        int bricks = h.state("minecraft:stone_bricks");
        // From the ground (its top at 99) up one block per block along, to 24 blocks over it.
        RoadKernel.Spec spec = new RoadKernel.Spec(List.of(new BlockPos(x0, 99, z0), new BlockPos(x0 + 24, 123, z0)), 1,
                new Pattern.Single(bricks), null, RoadKernel.HeightMode.STRAIGHT, true, 3, 0, h.state("minecraft:air"));
        RoadKernel.Path path = RoadKernel.path(spec);
        // What the fill meets, read as the client reads it: down from the deck in the real world.
        GroundMap ground = RoadKernel.deckGround(spec, path, new McWorldReader(h.world, h.runtime.states()));
        RoadKernel.Result result = RoadKernel.generate(spec, path, ground, h.world.getMinBuildHeight(), h.world.getMaxBuildHeight(), 1 << 16);
        check(result.unloaded().isEmpty() && result.bareColumns() == 0, "every column is loaded: " + result.unloaded());
        GeneratedSource source = result.source();
        for (int x = x0; x <= x0 + 24; x++) {
            int y = 99 + (x - x0);
            check(source.get(x, y, z0) == bricks, "the surface at " + x);
            boolean meets = y - 3 - 1 <= 99;
            for (int k = 1; k <= 3; k++) {
                check(source.contains(x, y - k, z0) == meets, "fill " + k + " under " + x + (meets ? " missing" : " hangs"));
            }
        }
        ExtrudePathsGameTest.buildAndUndo(context, h, area, source, OpLabel.ROAD, undone -> {});
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_air_extrude", timeoutTicks = LIMIT)
    public void aWallCarriedStraightUpACurveKeepsItsStairsTheWayTheyFaced(GameTestHelper context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 1602);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0 - 4, 96, z0 - 4, x0 + 8, 140, z0 + 8);
        loadAndForce(h.world, area);
        ExtrudePathsGameTest.ground(h, area);
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        int bricks = h.state("minecraft:stone_bricks");
        int stairs = h.state("minecraft:stone_brick_stairs[facing=east]");
        // A 3 × 3 wall facing east at x0, stairs on its top row.
        int n = 9;
        int[] xs = new int[n], ys = new int[n], zs = new int[n], states = new int[n];
        int i = 0;
        for (int y = 100; y <= 102; y++) {
            for (int z = z0; z <= z0 + 2; z++) {
                writer.write(x0, y, z, y == 102 ? stairs : bricks, null);
                xs[i] = x0;
                ys[i] = y;
                zs[i] = z;
                states[i++] = Block.getId(h.world.getBlockState(pos(x0, y, z)));
            }
        }
        FaceCells face = new FaceCells(0, 1, xs, ys, zs, states);
        GeneratedSource source = PathExtrudeKernel.generate(new PathExtrudeKernel.Spec(face,
                List.of(new BlockPos(x0 + 1, 101, z0 + 1), new BlockPos(x0 + 1, 133, z0 + 1)), PathKind.CURVE, 0, false),
                h.runtime.states(), 1 << 20, h.world.getMinBuildHeight(), h.world.getMaxBuildHeight()).source();
        int[] flipped = {0};
        source.forEach((x, y, z, state) -> {
            if (state != bricks && state != stairs) flipped[0]++;
        });
        check(flipped[0] == 0, flipped[0] + " cells turned on the way up");
        // The centripetal Curve leans up to a block east (toward the short first step) partway up: no hole either way.
        for (int y = 104; y <= 130; y++) {
            for (int z = z0; z <= z0 + 2; z++) {
                check(source.contains(x0 + 1, y, z) || source.contains(x0 + 2, y, z), "a hole at " + y + "," + z);
            }
        }
        ExtrudePathsGameTest.buildAndUndo(context, h, area, source, OpLabel.PATH_EXTRUDE, undone -> {});
    }
}
