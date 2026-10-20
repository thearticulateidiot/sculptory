package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.GroundFill;
import dev.sculptory.core.generate.GroundMap;
import dev.sculptory.core.generate.LineKernel;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.core.path.PathSpec;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.fabric.world.FabricWorldReader;
import dev.sculptory.protocol.v2.OpLabel;
import dev.sculptory.server.platform.WriteOptions;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;

/**
 * Extend to Ground's ground rule on a real world: a Line over a small oak fills down through the oak's leaves and
 * trunk (the world's own tags and flags) to the stone under it, replacing the tree where it stands, builds as one step
 * and undoes exactly; the leaves beside the line stay. Region slots 2400-2409.
 */
public final class ExtendToGroundGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;
    private static final String LEAVES = "minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]";

    /** An oak on the stone (96-99) at (x, z): a trunk at 100-103, leaves over the 5 x 5 around it at 102-104. */
    private static void tree(Harness h, int x, int z) {
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        int leaves = h.state(LEAVES), log = h.state("minecraft:oak_log[axis=y]");
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int y = 102; y <= 104; y++) writer.write(x + dx, y, z + dz, leaves, null);
            }
        }
        for (int y = 100; y <= 103; y++) writer.write(x, y, z, log, null);
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_ground_rule_tree", timeoutTicks = LIMIT)
    public void aLineOverATreeFillsThroughItToTheGroundAndUndoesExactly(GameTestHelper context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 2400);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0 - 4, 96, z0 - 4, x0 + 12, 112, z0 + 4);
        loadAndForce(h.world, area);
        ExtrudePathsGameTest.ground(h, area);
        tree(h, x0 + 4, z0);
        int stone = h.state("minecraft:stone");
        // A Line of stone bricks 3 thick along x at y 108 (cells 107-109), over the tree.
        GeneratedSource line = LineKernel.generate(new LineKernel.Spec(new PathSpec(List.of(new BlockPos(x0, 108, z0),
                new BlockPos(x0 + 8, 108, z0)), PathKind.STRAIGHT), 3, LineKernel.Profile.SQUARE,
                new Pattern.Single(h.state("minecraft:stone_bricks"))), h.runtime.states(), 1 << 16,
                h.world.getMinBuildHeight(), h.world.getMaxBuildHeight());
        // As the client does it: each column's lowest cell, the ground read under it within the budget, the fill.
        Long2IntMap lowest = GroundFill.lowest(line);
        FabricWorldReader reader = new FabricWorldReader(h.world, h.runtime.states());
        check(GroundFill.lookedThrough(h.runtime.states(), h.state(LEAVES))
                && GroundFill.lookedThrough(h.runtime.states(), h.state("minecraft:oak_log[axis=y]")),
                "leaves and logs are looked through");
        check(!GroundFill.lookedThrough(h.runtime.states(), stone), "stone is the ground");
        GroundMap ground = GroundFill.groundUnder(lowest, reader, (1 << 16) - line.cells());
        check(ground != null, "the fill fits the budget");
        GeneratedSource wall = GroundFill.extend(line, lowest, ground, (x, y, z, low) -> stone, 1 << 16,
                h.world.getMinBuildHeight());
        for (Long2IntMap.Entry column : lowest.long2IntEntrySet()) {
            int x = GroundMap.columnX(column.getLongKey()), z = GroundMap.columnZ(column.getLongKey());
            check(ground.ground(x, z) == 99, "the ground under " + x + "," + z + ": " + ground.ground(x, z));
            for (int y = 100; y < column.getIntValue(); y++) {
                check(wall.get(x, y, z) == stone, "a hole in the wall at " + x + "," + y + "," + z);
            }
        }
        check(wall.get(x0 + 4, 100, z0) == stone && wall.get(x0 + 4, 103, z0) == stone, "the trunk is filled over");
        check(wall.get(x0 + 3, 104, z0 + 1) == stone, "the leaves in the way are filled over");
        check(!wall.contains(x0 + 4, 104, z0 + 2), "the leaves beside the line are not touched");
        ExtrudePathsGameTest.buildAndUndo(context, h, area, wall, OpLabel.LINE, undone -> {
            int now = Block.getId(h.world.getBlockState(pos(x0 + 4, 101, z0)));
            check(undone ? now == h.state("minecraft:oak_log[axis=y]") : now == stone,
                    (undone ? "the trunk is back" : "the trunk is built over") + ": " + now);
        });
    }
}
