package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.ClipboardGameTest.refusal;
import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.GeneratedTooLargeException;
import dev.sculptory.core.generate.GroundFill;
import dev.sculptory.core.generate.GroundMap;
import dev.sculptory.core.generate.LineKernel;
import dev.sculptory.core.generate.RoadKernel;
import dev.sculptory.core.generate.SlopeSteps;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.core.path.PathSampler;
import dev.sculptory.core.path.PathSpec;
import dev.sculptory.core.state.StairSlabVariants;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.mc.world.BlockWriter;
import dev.sculptory.mc.world.McWorldReader;
import dev.sculptory.protocol.v2.OpLabel;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.platform.WriteOptions;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/**
 * Generate's path-point tools on a real world: a Line with Extend to Ground stands on the terrain the world
 * reads under it (through plants and water, down into a valley), builds as one step and undoes exactly; a fill past the
 * cap is refused before anything is sent, and an upload over the server's limit writes nothing; with Use Stairs and Slabs
 * a Line and a Road climb in stone brick stairs and undo exactly. Region slots 2300-2309.
 */
public final class PathPointsGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /** Stone at 96-99 over {@code area}, a valley (air at 97-99) across x0+4..x0+12, grass and water on the top. */
    private static void terrain(Harness h, Box area, int x0, int z0) {
        ExtrudePathsGameTest.ground(h, area);
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        int air = h.state("minecraft:air");
        for (int x = x0 + 4; x <= x0 + 12; x++) {
            for (int z = z0 - 2; z <= z0 + 2; z++) {
                for (int y = 97; y <= 99; y++) writer.write(x, y, z, air, null);
            }
        }
        writer.write(x0 + 1, 100, z0, h.state("minecraft:short_grass"), null);
        writer.write(x0 + 2, 100, z0, h.state("minecraft:water"), null);
    }

    /** A Line of stone bricks 3 thick along x at y 104, from x0 to x0 + 16. */
    private static GeneratedSource line(Harness h, int x0, int z0) {
        return LineKernel.generate(new LineKernel.Spec(new PathSpec(List.of(new BlockPos(x0, 104, z0),
                new BlockPos(x0 + 16, 104, z0)), PathKind.STRAIGHT), 3, LineKernel.Profile.SQUARE,
                new Pattern.Single(h.state("minecraft:stone_bricks"))), h.runtime.states(), 1 << 16,
                h.world.getMinBuildHeight(), h.world.getMaxBuildHeight());
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_points_extend_ground", timeoutTicks = LIMIT)
    public void aLineExtendedToGroundStandsOnTheTerrainBelowAndUndoesExactly(GameTestHelper context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 2300);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0 - 4, 96, z0 - 4, x0 + 20, 110, z0 + 4);
        loadAndForce(h.world, area);
        terrain(h, area, x0, z0);
        int stone = h.state("minecraft:stone");
        GeneratedSource line = line(h, x0, z0);
        // As the client does it: each column's lowest cell, the ground read under it, the fill made from that.
        Long2IntMap lowest = GroundFill.lowest(line);
        GroundMap ground = GroundFill.groundUnder(lowest, new McWorldReader(h.world, h.runtime.states()));
        GeneratedSource wall = GroundFill.extend(line, lowest, ground, (x, y, z, low) -> stone, 1 << 16,
                h.world.getMinBuildHeight());
        for (Long2IntMap.Entry column : lowest.long2IntEntrySet()) {
            int x = GroundMap.columnX(column.getLongKey()), z = GroundMap.columnZ(column.getLongKey());
            boolean valley = x >= x0 + 4 && x <= x0 + 12 && Math.abs(z - z0) <= 2;
            int floor = valley ? 97 : 100;
            check(ground.ground(x, z) == floor - 1, "the ground under " + x + "," + z + ": " + ground.ground(x, z));
            for (int y = floor; y < column.getIntValue(); y++) {
                check(wall.get(x, y, z) == stone, "a hole in the wall at " + x + "," + y + "," + z);
            }
            check(!wall.contains(x, floor - 1, z), "the terrain itself is not written at " + x + "," + z);
        }
        check(wall.cells() > line.cells(), "the fill is there");
        ExtrudePathsGameTest.buildAndUndo(context, h, area, wall, OpLabel.LINE, undone -> {});
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_points_extend_refused", timeoutTicks = LIMIT)
    public void aFillPastTheCapIsRefusedAndAnUploadOverTheLimitWritesNothing(GameTestHelper context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 2301);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0 - 4, 96, z0 - 4, x0 + 20, 110, z0 + 4);
        loadAndForce(h.world, area);
        terrain(h, area, x0, z0);
        int stone = h.state("minecraft:stone");
        GeneratedSource line = line(h, x0, z0);
        Long2IntMap lowest = GroundFill.lowest(line);
        GroundMap ground = GroundFill.groundUnder(lowest, new McWorldReader(h.world, h.runtime.states()));
        boolean refused = false;
        try {
            GroundFill.extend(line, lowest, ground, (x, y, z, low) -> stone, line.cells() + 10, h.world.getMinBuildHeight());
        } catch (GeneratedTooLargeException e) {
            refused = true;
        }
        check(refused, "a fill past the cap is too large (the preview says so and Enter sends nothing)");

        ServerPlayer builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.REGION, Perm.CLIPBOARD);
        WorldSnapshot before = capture(h.world, area);
        List<String> undoBefore = h.service.history(builder).undoLabels();
        long limit = h.runtime.config().limits.maxClipboardVolume;
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        int side = (int) Math.ceil(Math.cbrt(limit + 1.0)) + 1;
        BlockPos min = line.bounds().min();
        Box room = new Box(min, min.offset(side, side, side));
        RejectReason reason = refusal(() -> clips.beginGeneratedUpload(builder, room, limit + 1, 64)).reason();
        check(reason == RejectReason.TOO_LARGE, "an upload over the clipboard limit: " + reason);
        checkSame(before, capture(h.world, area), "after the refusal");
        check(h.service.history(builder).undoLabels().equals(undoBefore), "a refusal pushed history");
        forceChunks(h.world, area, false);
        h.close();
        context.succeed();
    }

    /** The stairs and slabs of a block's family, as Generate reads them. */
    private static SlopeSteps.Variants steps(Harness h) {
        StairSlabVariants variants = new StairSlabVariants(h.runtime.states());
        return new SlopeSteps.Variants() {
            @Override
            public int stairs(int state, String facing, String half) {
                return variants.stairs(state, facing, half, "straight");
            }

            @Override
            public int slab(int state, String type) {
                return variants.slab(state, type);
            }
        };
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_points_line_stairs", timeoutTicks = LIMIT)
    public void aLineWithStairsAndSlabsClimbsInStoneBrickStairsAndUndoesExactly(GameTestHelper context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 2302);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0 - 4, 96, z0 - 4, x0 + 20, 120, z0 + 4);
        loadAndForce(h.world, area);
        ExtrudePathsGameTest.ground(h, area);
        PathSpec path = new PathSpec(List.of(new BlockPos(x0, 101, z0), new BlockPos(x0 + 12, 107, z0)), PathKind.STRAIGHT);
        int bricks = h.state("minecraft:stone_bricks");
        GeneratedSource line = LineKernel.generate(new LineKernel.Spec(path, 1, LineKernel.Profile.ROUND,
                new Pattern.Single(bricks)), h.runtime.states(), 1 << 16, h.world.getMinBuildHeight(), h.world.getMaxBuildHeight());
        SlopeSteps.Result stepped = SlopeSteps.apply(line, SlopeSteps.along(PathSampler.sample(path, 0.5)), steps(h),
                h.state("minecraft:air"));
        int stairs = h.state("minecraft:stone_brick_stairs[facing=east,half=bottom]");
        int[] found = {0};
        stepped.source().forEach((x, y, z, state) -> {
            if (state == stairs) found[0]++;
        });
        check(found[0] >= 5 && stepped.plain() == 0, "stairs at the steps: " + found[0] + ", " + stepped.plain() + " plain");
        check(stepped.source().cells() == line.cells(), "no cell added or removed");
        ExtrudePathsGameTest.buildAndUndo(context, h, area, stepped.source(), OpLabel.LINE, undone -> {});
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_points_road_stairs", timeoutTicks = LIMIT)
    public void aRoadWithStairsAndSlabsStepsUpInStairsAcrossItsWidthAndUndoesExactly(GameTestHelper context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 2303);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0 - 4, 96, z0 - 4, x0 + 20, 120, z0 + 4);
        loadAndForce(h.world, area);
        ExtrudePathsGameTest.ground(h, area);
        int bricks = h.state("minecraft:stone_bricks");
        RoadKernel.Spec spec = new RoadKernel.Spec(List.of(new BlockPos(x0, 99, z0), new BlockPos(x0 + 12, 105, z0)), 3,
                new Pattern.Single(bricks), null, RoadKernel.HeightMode.STRAIGHT, true, 0, 2, h.state("minecraft:air"));
        RoadKernel.Path laid = RoadKernel.path(spec);
        GroundMap ground = RoadKernel.deckGround(spec, laid, new McWorldReader(h.world, h.runtime.states()));
        GeneratedSource road = RoadKernel.generate(spec, laid, ground, h.world.getMinBuildHeight(), h.world.getMaxBuildHeight(), 1 << 16)
                .source();
        GeneratedSource stepped = SlopeSteps.apply(road, SlopeSteps.byColumn(laid.climbAxes()), steps(h),
                h.state("minecraft:air")).source();
        int stairs = h.state("minecraft:stone_brick_stairs[facing=east,half=bottom]");
        int[] found = {0};
        stepped.forEach((x, y, z, state) -> {
            if (state == stairs) found[0]++;
        });
        check(found[0] >= 15, "a stair across the width at each step: " + found[0]);
        ExtrudePathsGameTest.buildAndUndo(context, h, area, stepped, OpLabel.ROAD, undone -> {});
    }
}
