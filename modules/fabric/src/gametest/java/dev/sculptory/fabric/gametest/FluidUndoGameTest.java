package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.ShapeSpec;
import dev.sculptory.core.brush.ShapeStamp;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.history.HistoryLimits;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.fabric.engine.impl.EditExecutor;
import dev.sculptory.fabric.engine.impl.JobRequest;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.fabric.world.FluidTrails;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobTicket;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.command.FillBiomeCommand;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Undoing an edit whose fluid flowed (the case: water left over after undoing a water Fill, even
 * with Undo anyway). Edited fluid is written with physics off and lies still
 * until something updates a neighbour: a grass block under it dying to dirt on a random tick, a block placed beside it.
 * Then it flows out of the edited cells, and flowing water beside two sources over a solid block becomes a source. The
 * entry's next step takes all of that back. regionCorner slots 920-939 and 958-959.
 */
public final class FluidUndoGameTest implements FabricGameTest {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;
    static final int FLOOR = 100;
    /** Area side: the edit sits in the middle, with room for water to flow 7 blocks and lava 3. */
    static final int SIDE = 48;
    /**
     * How long the area is watched after a step for anything moving again (water ticks every 5 ticks, lava every 30).
     * Waiting for fluid to flow uses {@link #settles} instead.
     */
    static final int WATER_TICKS = 600;
    private static final int LAVA_TICKS = 600;

    // =================================================================== helpers

    /**
     * Stone below {@link #FLOOR}, {@code ground} at it and air above, over the {@link #SIDE}² area at (x0, z0); returns
     * the area's box (FLOOR - 3 to FLOOR + 8).
     */
    static Box terrain(Harness h, int x0, int z0, String ground) {
        Box area = box(x0, FLOOR - 3, z0, x0 + SIDE - 1, FLOOR + 8, z0 + SIDE - 1);
        loadAndForce(h.world, area);
        BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
        int stone = h.state("minecraft:stone"), air = h.state("minecraft:air"), top = h.state(ground);
        for (int x = x0; x < x0 + SIDE; x++) {
            for (int z = z0; z < z0 + SIDE; z++) {
                for (int y = FLOOR - 3; y < FLOOR; y++) writer.write(x, y, z, stone, null);
                writer.write(x, FLOOR, z, top, null);
                for (int y = FLOOR + 1; y <= FLOOR + 8; y++) writer.write(x, y, z, air, null);
            }
        }
        writer.clearTicksAtWrittenCells();
        return area;
    }

    /** What differs from {@code before}: cells, and how many of them hold fluid sources or flowing fluid. */
    record Leftover(int cells, int sources, int flowing, String first) {
        boolean none() {
            return cells == 0;
        }

        @Override
        public String toString() {
            return cells + " cells differ (" + sources + " fluid sources, " + flowing + " flowing)"
                    + (first == null ? "" : "; first " + first);
        }
    }

    static Leftover leftover(ServerWorld world, WorldSnapshot before) {
        Box area = before.box;
        int cells = 0, sources = 0, flowing = 0;
        String first = null;
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int y = area.min().y(); y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    BlockState now = world.getBlockState(p.set(x, y, z));
                    int was = before.get(x, y, z);
                    if (Block.getRawIdFromState(now) == was) continue;
                    cells++;
                    FluidState fluid = now.getFluidState();
                    if (!fluid.isEmpty()) {
                        if (fluid.isStill()) {
                            sources++;
                        } else {
                            flowing++;
                        }
                    }
                    if (first == null) first = x + "," + y + "," + z + ": " + now + " instead of " + Block.getStateFromRawId(was);
                }
            }
        }
        return new Leftover(cells, sources, flowing, first);
    }

    /** The area is exactly as {@code before} (states and block entities), with the fluid leftovers counted if not. */
    static void checkExact(ServerWorld world, WorldSnapshot before, String what) {
        Leftover left = leftover(world, before);
        LOG.info("FluidUndoGameTest: {}: {}", what, left);
        check(left.none(), what + ": " + left);
        checkSame(before, capture(world, before.box), what);
    }

    /** Fluid cells (source or flowing) in {@code area} outside {@code edited}. */
    static int fluidOutside(ServerWorld world, Box area, Region edited) {
        int n = 0;
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int y = area.min().y(); y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    if (edited.contains(x, y, z)) continue;
                    if (!world.getFluidState(p.set(x, y, z)).isEmpty()) n++;
                }
            }
        }
        return n;
    }

    /**
     * A task (for {@code createAndAdd}, retried every tick) that passes once {@code area} has changed from how it was at
     * the first try and then stayed unchanged for {@code ticks} world ticks: the fluid has moved and come to rest. A
     * tick count alone is a poor clock here: a freshly loaded region can wait a while before its chunks tick.
     */
    static Runnable settles(ServerWorld world, Box area, int ticks, String what) {
        long[] seen = new long[3]; // the first hash, the last hash, when it last changed
        boolean[] started = {false};
        return () -> {
            long now = world.getTime(), hash = hash(world, area);
            if (!started[0]) {
                started[0] = true;
                seen[0] = hash;
                seen[1] = hash;
                seen[2] = now;
            }
            if (hash != seen[1]) {
                seen[1] = hash;
                seen[2] = now;
            }
            check(hash != seen[0] && now - seen[2] >= ticks, what + " has not come to rest");
        };
    }

    private static long hash(ServerWorld world, Box area) {
        long hash = 17;
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int y = area.min().y(); y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    hash = hash * 31 + Block.getRawIdFromState(world.getBlockState(p.set(x, y, z)));
                }
            }
        }
        return hash;
    }

    /** Whether every chunk of {@code area} ticks blocks and fluids (a freshly loaded region takes a moment). */
    static boolean ticking(ServerWorld world, Box area) {
        for (int cx = area.min().x() >> 4; cx <= area.max().x() >> 4; cx++) {
            for (int cz = area.min().z() >> 4; cz <= area.max().z() >> 4; cz++) {
                long key = ChunkPos.toLong(cx, cz);
                // What the world's tick schedulers ask before running a chunk's scheduled ticks.
                if (!world.isChunkLoaded(key) || !world.getChunkManager().isTickingFutureReady(key)) return false;
            }
        }
        return true;
    }

    static int count(ServerWorld world, Box area, Block block) {
        int n = 0;
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int y = area.min().y(); y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    if (world.getBlockState(p.set(x, y, z)).isOf(block)) n++;
                }
            }
        }
        return n;
    }

    /** A neighbour update from (x, y, z), as a player placing or breaking a block there sends: wakes fluid beside it. */
    static void nudge(ServerWorld world, int x, int y, int z) {
        world.updateNeighbors(pos(x, y, z), Blocks.AIR);
    }

    private static void run(Harness h, ServerPlayerEntity player, Region region, Pattern pattern, RecordingListener l) {
        try {
            h.service.run(player, new OpSpec.Fill(region, pattern, CellMask.ANY), RunOptions.DEFAULT, l);
        } catch (EditRejected e) {
            throw new GameTestException("fill refused: " + e.getMessage());
        }
    }

    private static void step(Harness h, ServerPlayerEntity player, boolean undo, RecordingListener listener) {
        try {
            if (undo) {
                h.service.undo(player, ConflictPolicy.SKIP_CONFLICTS, listener);
            } else {
                h.service.redo(player, ConflictPolicy.SKIP_CONFLICTS, listener);
            }
        } catch (EditRejected e) {
            throw new GameTestException((undo ? "undo" : "redo") + " refused: " + e.getMessage());
        }
    }

    private static void undoAnyway(Harness h, RecordingListener listener) {
        try {
            h.service.historyOverwrite(h.player, false, 1, listener);
        } catch (EditRejected e) {
            throw new GameTestException("Undo anyway refused: " + e.getMessage());
        }
    }

    private static void completed(RecordingListener listener, String what) {
        check(listener.result.outcome() == JobOutcome.COMPLETED, what + " " + listener.result);
    }

    // =================================================================== the reported case

    /**
     * The reported scenario: a Fill with water (default settings) over open grass. Random ticks kill grass blocks under
     * the water (they turn to dirt), which wakes it; it flows out over the grass. Undo: the area is exactly as before
     * the Fill, the grass included, and stays so. Redo puts back the water as it lay before the undo (the flowed water
     * too, lying still); undo again restores the area once more.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_undo_fill", tickLimit = LIMIT)
    public void undoingAWaterFillOnGrassTakesBackWhatTheWaterDid(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 920);
        int x0 = at[0], z0 = at[1];
        Box area = terrain(h, x0, z0, "minecraft:grass_block");
        Box fill = box(x0 + 18, FLOOR + 1, z0 + 18, x0 + 29, FLOOR + 2, z0 + 29);
        WorldSnapshot before = capture(world, area);
        WorldSnapshot[] flowed = new WorldSnapshot[1];
        RecordingListener filled = new RecordingListener(), undone = new RecordingListener(),
                redone = new RecordingListener(), again = new RecordingListener();
        h.fill(fill, "minecraft:water", filled);
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(filled.result != null, "fill running"))
                .createAndAddReported(() -> {
                    completed(filled, "fill");
                    check(fluidOutside(world, area, new Region.Cuboid(fill)) == 0, "the edited water lies still");
                    // Random ticks, as the world gives them (the Fill lies in one chunk column), until grass has died.
                    for (int i = 0; i < 8 && count(world, area, Blocks.DIRT) == 0; i++) {
                        world.tickChunk(world.getChunk((x0 + 18) >> 4, (z0 + 18) >> 4), 4096);
                    }
                    check(count(world, area, Blocks.DIRT) > 0, "grass under the water died");
                })
                .createAndAdd(settles(world, area, 100, "the water"))
                .createAndAddReported(() -> {
                    int outside = fluidOutside(world, area, new Region.Cuboid(fill));
                    LOG.info("FluidUndoGameTest fill: {} fluid cells outside the Fill after it flowed, {} dirt", outside,
                            count(world, area, Blocks.DIRT));
                    check(outside > 0, "the water flowed out of the Fill");
                    flowed[0] = capture(world, area);
                    step(h, h.player, true, undone);
                })
                .createAndAdd(() -> check(undone.result != null, "undo running"))
                .createAndAddReported(() -> {
                    completed(undone, "undo");
                    check(undone.result.skippedConflicts() == 0, "nothing kept: " + undone.result);
                    checkExact(world, before, "fill: right after the undo");
                })
                .expectMinDuration(WATER_TICKS)
                .createAndAddReported(() -> {
                    checkExact(world, before, "fill: " + WATER_TICKS + " ticks after the undo");
                    step(h, h.player, false, redone);
                })
                .createAndAdd(() -> check(redone.result != null, "redo running"))
                .createAndAddReported(() -> {
                    completed(redone, "redo");
                    checkSame(flowed[0], capture(world, area), "the redo puts back the water as the undo found it");
                    step(h, h.player, true, again);
                })
                .createAndAdd(() -> check(again.result != null, "second undo running"))
                .createAndAddReported(() -> {
                    completed(again, "second undo");
                    checkExact(world, before, "fill: after undo, redo and undo");
                    UUID id = h.service.historyService().redoEntries(h.player.getUuid()).get(0).id();
                    check(!h.runtime.fluidTrails().isFrozen(id), "the entry is not held still once its steps are done");
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * An L-shaped water Fill (a cell-set selection) on stone. A player places a block in the water, which wakes it; it
     * flows out, and the notch of the L fills with sources (flowing water beside two sources over stone). The player
     * also builds on a cell the water flowed to. Undo keeps both of the player's blocks and takes back everything else;
     * Undo anyway then overwrites them: the area is exactly as before the Fill.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_undo_anyway", tickLimit = LIMIT)
    public void undoAnywayTakesBackTheSourcesTheFlowMade(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 922);
        int x0 = at[0], z0 = at[1];
        Box area = terrain(h, x0, z0, "minecraft:stone");
        CellSet.Builder cells = CellSet.builder();
        for (int x = x0 + 16; x <= x0 + 27; x++) {
            for (int z = z0 + 16; z <= z0 + 21; z++) cells.add(x, FLOOR + 1, z);
        }
        for (int x = x0 + 16; x <= x0 + 21; x++) {
            for (int z = z0 + 22; z <= z0 + 27; z++) cells.add(x, FLOOR + 1, z);
        }
        Region.Cells ell = new Region.Cells(cells.build());
        BlockPos corner = pos(x0 + 22, FLOOR + 1, z0 + 22);
        BlockPos outside = pos(x0 + 13, FLOOR + 1, z0 + 18);
        check(!ell.contains(corner.getX(), corner.getY(), corner.getZ()), "the notch is outside the L");
        WorldSnapshot before = capture(world, area);
        RecordingListener filled = new RecordingListener(), undone = new RecordingListener(), anyway = new RecordingListener();
        run(h, h.player, ell, new Pattern.Single(h.state("minecraft:water")), filled);
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(filled.result != null, "fill running"))
                .createAndAddReported(() -> {
                    completed(filled, "fill");
                    // A player's block in the water, with vanilla's block updates.
                    world.setBlockState(pos(x0 + 27, FLOOR + 1, z0 + 18), Blocks.STONE.getDefaultState());
                })
                .createAndAdd(settles(world, area, 100, "the water"))
                .createAndAddReported(() -> {
                    LOG.info("FluidUndoGameTest anyway: {} fluid cells outside the L; the notch corner {}",
                            fluidOutside(world, area, ell), world.getBlockState(corner));
                    check(world.getFluidState(corner).isStill(), "the notch became a source: " + world.getBlockState(corner));
                    check(!world.getFluidState(outside).isEmpty(), "the water reached " + outside.toShortString());
                    // The player builds where the water flowed.
                    world.setBlockState(outside, Blocks.OAK_PLANKS.getDefaultState());
                })
                .expectMinDuration(WATER_TICKS)
                .createAndAddReported(() -> step(h, h.player, true, undone))
                .createAndAdd(() -> check(undone.result != null, "undo running"))
                .createAndAddReported(() -> {
                    completed(undone, "undo");
                    Leftover left = leftover(world, before);
                    LOG.info("FluidUndoGameTest anyway: after the undo (kept {}): {}", undone.result.skippedConflicts(), left);
                    check(undone.result.skippedConflicts() == 2, "the player's two blocks were kept: " + undone.result);
                    check(left.cells() == 2 && left.sources() == 0 && left.flowing() == 0, "only they differ: " + left);
                    check(world.getBlockState(outside).isOf(Blocks.OAK_PLANKS), "the planks were kept");
                    undoAnyway(h, anyway);
                })
                .createAndAdd(() -> check(anyway.result != null, "Undo anyway running"))
                .createAndAddReported(() -> completed(anyway, "Undo anyway"))
                .expectMinDuration(WATER_TICKS)
                .createAndAddReported(() -> {
                    checkExact(world, before, "anyway: after Undo anyway");
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** Lava: a Fill on stone, woken by a neighbour update; it flows 3 blocks. Undo leaves the area as before. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_undo_lava", tickLimit = LIMIT)
    public void undoingALavaFillTakesBackTheLavaThatFlowed(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 924);
        int x0 = at[0], z0 = at[1];
        Box area = terrain(h, x0, z0, "minecraft:stone");
        Box fill = box(x0 + 20, FLOOR + 1, z0 + 20, x0 + 27, FLOOR + 1, z0 + 27);
        WorldSnapshot before = capture(world, area);
        RecordingListener filled = new RecordingListener(), undone = new RecordingListener();
        h.fill(fill, "minecraft:lava", filled);
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(filled.result != null, "fill running"))
                .createAndAddReported(() -> {
                    completed(filled, "fill");
                    nudge(world, x0 + 19, FLOOR + 1, z0 + 23);
                })
                .createAndAdd(settles(world, area, 300, "the lava"))
                .createAndAddReported(() -> {
                    int outside = fluidOutside(world, area, new Region.Cuboid(fill));
                    LOG.info("FluidUndoGameTest lava: {} fluid cells outside the Fill after it flowed", outside);
                    check(outside > 0, "the lava flowed out of the Fill");
                    step(h, h.player, true, undone);
                })
                .createAndAdd(() -> check(undone.result != null, "undo running"))
                .createAndAddReported(() -> completed(undone, "undo"))
                .expectMinDuration(LAVA_TICKS)
                .createAndAddReported(() -> {
                    checkExact(world, before, "lava: after the undo");
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    // =================================================================== what must stay

    /**
     * A player's stream (a source they poured at the head of a stone channel, flowing down it) ends at a water Fill.
     * Woken, the Fill's water flows into the channel and raises the stream. Undo takes the Fill's water back and leaves
     * the player's stream exactly as it was, source and levels.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_undo_players_water", tickLimit = LIMIT)
    public void thePlayersOwnWaterBesideTheFillIsKept(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 926);
        int x0 = at[0], z0 = at[1], zc = z0 + 24;
        Box area = terrain(h, x0, z0, "minecraft:stone");
        for (int x = x0 + 7; x <= x0 + 15; x++) {
            world.setBlockState(pos(x, FLOOR + 1, zc - 1), Blocks.STONE.getDefaultState());
            world.setBlockState(pos(x, FLOOR + 1, zc + 1), Blocks.STONE.getDefaultState());
        }
        world.setBlockState(pos(x0 + 7, FLOOR + 1, zc), Blocks.STONE.getDefaultState());
        BlockPos source = pos(x0 + 8, FLOOR + 1, zc);
        world.setBlockState(source, Blocks.WATER.getDefaultState()); // the player's bucket
        Box fill = box(x0 + 16, FLOOR + 1, zc - 3, x0 + 23, FLOOR + 1, zc + 3);
        WorldSnapshot[] before = new WorldSnapshot[1];
        BlockState[] streamEnd = new BlockState[1];
        RecordingListener filled = new RecordingListener(), undone = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(settles(world, area, 100, "the water"))
                .createAndAddReported(() -> {
                    streamEnd[0] = world.getBlockState(pos(x0 + 15, FLOOR + 1, zc));
                    check(streamEnd[0].getFluidState().getLevel() == 1, "the stream reaches the Fill's edge: " + streamEnd[0]);
                    before[0] = capture(world, area);
                    h.fill(fill, "minecraft:water", filled);
                })
                .createAndAdd(() -> check(filled.result != null, "fill running"))
                .createAndAddReported(() -> {
                    completed(filled, "fill");
                    // Something happens above the stream's end: it updates, next to the Fill's water.
                    nudge(world, x0 + 15, FLOOR + 2, zc);
                })
                .createAndAdd(settles(world, area, 100, "the water"))
                .createAndAddReported(() -> {
                    BlockState raised = world.getBlockState(pos(x0 + 15, FLOOR + 1, zc));
                    LOG.info("FluidUndoGameTest players_water: the stream's end {} after the Fill's water came", raised);
                    check(raised.getFluidState().getLevel() > 1, "the Fill's water raised the stream: " + raised);
                    step(h, h.player, true, undone);
                })
                .createAndAdd(() -> check(undone.result != null, "undo running"))
                .createAndAddReported(() -> completed(undone, "undo"))
                .expectMinDuration(WATER_TICKS)
                .createAndAddReported(() -> {
                    check(world.getBlockState(source).isOf(Blocks.WATER) && world.getFluidState(source).isStill(),
                            "the player's source is there");
                    check(world.getBlockState(pos(x0 + 15, FLOOR + 1, zc)) == streamEnd[0], "the stream is as it was");
                    checkExact(world, before[0], "players_water: after the undo");
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    // =================================================================== other tools and paths

    /**
     * Waterlogged blocks leak: a Fill with the Waterlog pattern (the Fluid tool's flood) waterlogs a row of bottom slabs
     * on stone, and woken, the water runs out of their sides. Undo dries the slabs and takes back what ran out.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_undo_waterlogged", tickLimit = LIMIT)
    public void undoingAWaterlogFillTakesBackWhatLeakedFromTheBlocks(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 928);
        int x0 = at[0], z0 = at[1], zc = z0 + 24;
        Box area = terrain(h, x0, z0, "minecraft:stone");
        CellSet.Builder cells = CellSet.builder();
        BlockWriter writer = h.runtime.writer(world, BlockWriter.Options.DEFAULT);
        for (int x = x0 + 20; x <= x0 + 27; x++) {
            writer.write(x, FLOOR + 1, zc, h.state("minecraft:oak_slab[type=bottom]"), null);
            cells.add(x, FLOOR + 1, zc);
        }
        writer.clearTicksAtWrittenCells();
        Region.Cells slabs = new Region.Cells(cells.build());
        WorldSnapshot before = capture(world, area);
        RecordingListener filled = new RecordingListener(), undone = new RecordingListener();
        run(h, h.player, slabs, new Pattern.Waterlog(h.state("minecraft:water")), filled);
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(filled.result != null, "fill running"))
                .createAndAddReported(() -> {
                    completed(filled, "waterlog");
                    check(world.getFluidState(pos(x0 + 20, FLOOR + 1, zc)).isStill(), "the slabs are waterlogged");
                    // A player places a block beside the row and breaks it again: waterlogged blocks wake on the
                    // shape update a changed neighbour sends (a bare neighbour update does not reach them).
                    world.setBlockState(pos(x0 + 19, FLOOR + 1, zc), Blocks.STONE.getDefaultState());
                    world.setBlockState(pos(x0 + 19, FLOOR + 1, zc), Blocks.AIR.getDefaultState());
                })
                .createAndAdd(settles(world, area, 100, "the water"))
                .createAndAddReported(() -> {
                    int outside = fluidOutside(world, area, slabs);
                    LOG.info("FluidUndoGameTest waterlogged: {} fluid cells leaked from the slabs", outside);
                    check(outside > 0, "water ran out of the slabs");
                    step(h, h.player, true, undone);
                })
                .createAndAdd(() -> check(undone.result != null, "undo running"))
                .createAndAddReported(() -> completed(undone, "undo"))
                .expectMinDuration(WATER_TICKS)
                .createAndAddReported(() -> {
                    checkExact(world, before, "waterlogged: after the undo");
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** A Fluid ball (a Shape-brush stroke of water) on stone, woken on every side: it flows; undo leaves nothing. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_undo_ball", tickLimit = LIMIT)
    public void undoingAFluidBallTakesBackTheWaterThatFlowed(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 930);
        int x0 = at[0], z0 = at[1];
        Box area = terrain(h, x0, z0, "minecraft:stone");
        BrushSpec spec = ShapeBrushGameTest.shape(3, ShapeSpec.Kind.SPHERE, 7, Facing.UP, ShapeSpec.Mode.PLACE, 0,
                new Pattern.Single(h.state("minecraft:water")), Symmetry.NONE);
        Dab dab = ShapeBrushGameTest.at(0, x0 + 24, FLOOR, z0 + 24);
        Region ball = ShapeStamp.placement(spec, dab).region();
        WorldSnapshot before = capture(world, area);
        ShapeBrushGameTest.stroke(h, executor, 20, spec, List.of(dab), 1);
        check(fluidOutside(world, area, ball) == 0 && count(world, area, Blocks.WATER) > 0, "the ball is water, lying still");
        Box bounds = ball.bounds();
        for (int x = bounds.min().x(); x <= bounds.max().x(); x++) {
            for (int z = bounds.min().z(); z <= bounds.max().z(); z++) {
                for (int y = bounds.min().y(); y <= bounds.max().y(); y++) {
                    if (ball.contains(x, y, z)) nudge(world, x, y, z);
                }
            }
        }
        context.createTimedTaskRunner()
                .createAndAdd(settles(world, area, 100, "the water"))
                .createAndAddReported(() -> {
                    int outside = fluidOutside(world, area, ball);
                    LOG.info("FluidUndoGameTest ball: {} fluid cells outside the ball after it flowed", outside);
                    check(outside > 0, "the ball's water flowed");
                    ShapeBrushGameTest.undoAll(h, executor, 1, 20);
                })
                .expectMinDuration(WATER_TICKS)
                .createAndAddReported(() -> {
                    checkExact(world, before, "ball: after the undo");
                    executor.shutdown();
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** Undo while the water is still flowing: the flow stops for the undo, and nothing is left behind. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_undo_while_flowing", tickLimit = LIMIT)
    public void undoWhileTheWaterStillFlowsLeavesNothing(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 932);
        int x0 = at[0], z0 = at[1];
        Box area = terrain(h, x0, z0, "minecraft:stone");
        Box fill = box(x0 + 20, FLOOR + 1, z0 + 20, x0 + 27, FLOOR + 1, z0 + 27);
        WorldSnapshot before = capture(world, area);
        RecordingListener filled = new RecordingListener(), undone = new RecordingListener();
        h.fill(fill, "minecraft:water", filled);
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(filled.result != null, "fill running"))
                .createAndAddReported(() -> {
                    completed(filled, "fill");
                    for (int z = z0 + 20; z <= z0 + 27; z++) nudge(world, x0 + 19, FLOOR + 1, z);
                })
                // As soon as water is outside (the flow has only begun), the undo starts.
                .createAndAdd(() -> check(fluidOutside(world, area, new Region.Cuboid(fill)) > 0, "the water is flowing"))
                .createAndAddReported(() -> {
                    LOG.info("FluidUndoGameTest while_flowing: {} fluid cells outside when the undo starts",
                            fluidOutside(world, area, new Region.Cuboid(fill)));
                    step(h, h.player, true, undone);
                })
                .createAndAdd(() -> check(undone.result != null, "undo running"))
                .createAndAddReported(() -> completed(undone, "undo"))
                .expectMinDuration(WATER_TICKS)
                .createAndAddReported(() -> {
                    checkExact(world, before, "while_flowing: after the undo");
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Redo takes back what fluid an undo put back did: a drain empties a player's pool, the player breaks a wall of it,
     * and undo refills the pool. Woken, its water runs out through the gap. Redo drains the pool again and takes back
     * what ran out: the area is exactly as after the drain and the broken wall.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_undo_redo_drain", tickLimit = LIMIT)
    public void redoOfADrainTakesBackWhatTheRefilledPoolDid(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 934);
        int x0 = at[0], z0 = at[1];
        Box area = terrain(h, x0, z0, "minecraft:stone");
        CellSet.Builder cells = CellSet.builder();
        for (int x = x0 + 18; x <= x0 + 29; x++) {
            for (int z = z0 + 18; z <= z0 + 29; z++) {
                boolean wall = x == x0 + 18 || x == x0 + 29 || z == z0 + 18 || z == z0 + 29;
                if (wall) world.setBlockState(pos(x, FLOOR + 1, z), Blocks.STONE.getDefaultState());
            }
        }
        for (int x = x0 + 19; x <= x0 + 28; x++) {
            for (int z = z0 + 19; z <= z0 + 28; z++) {
                world.setBlockState(pos(x, FLOOR + 1, z), Blocks.WATER.getDefaultState());
                cells.add(x, FLOOR + 1, z);
            }
        }
        Region.Cells pool = new Region.Cells(cells.build());
        BlockPos gap = pos(x0 + 18, FLOOR + 1, z0 + 23);
        WorldSnapshot[] drained = new WorldSnapshot[1];
        RecordingListener drain = new RecordingListener(), undone = new RecordingListener(), redone = new RecordingListener();
        run(h, h.player, pool, new Pattern.Dry(), drain);
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(drain.result != null, "drain running"))
                .createAndAddReported(() -> {
                    completed(drain, "drain");
                    world.setBlockState(gap, Blocks.AIR.getDefaultState()); // the player breaks a wall block
                    drained[0] = capture(world, area);
                    step(h, h.player, true, undone);
                })
                .createAndAdd(() -> check(undone.result != null, "undo running"))
                .createAndAddReported(() -> {
                    completed(undone, "undo");
                    check(world.getFluidState(pos(x0 + 19, FLOOR + 1, z0 + 23)).isStill(), "the pool is full again");
                    check(fluidOutside(world, area, pool) == 0, "its water lies still");
                    nudge(world, gap.getX(), gap.getY(), gap.getZ());
                })
                .createAndAdd(settles(world, area, 100, "the water"))
                .createAndAddReported(() -> {
                    int outside = fluidOutside(world, area, pool);
                    LOG.info("FluidUndoGameTest redo_drain: {} fluid cells ran out of the refilled pool", outside);
                    check(outside > 0, "the water ran out through the gap");
                    step(h, h.player, false, redone);
                })
                .createAndAdd(() -> check(redone.result != null, "redo running"))
                .createAndAddReported(() -> completed(redone, "redo"))
                .expectMinDuration(WATER_TICKS)
                .createAndAddReported(() -> {
                    checkExact(world, drained[0], "redo_drain: after the redo");
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    // =================================================================== refusals and bookkeeping

    /**
     * Protection applies to what the fluid did: a builder's water flows into a chunk protected against them; their undo
     * skips the cells there (counted as protected) and restores everything else.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_undo_protected", tickLimit = LIMIT)
    public void undoLeavesWhatFlowedIntoAProtectedAreaAlone(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.REGION);
        int[] at = regionCorner(context, 936);
        int x0 = at[0], z0 = at[1];
        Box area = terrain(h, x0, z0, "minecraft:stone");
        Box fill = box(x0 + 9, FLOOR + 1, z0 + 20, x0 + 14, FLOOR + 1, z0 + 25);
        BlockPos east = pos(x0 + 17, FLOOR + 1, z0 + 22);
        WorldSnapshot before = capture(world, area);
        RecordingListener filled = new RecordingListener(), undone = new RecordingListener();
        run(h, builder, new Region.Cuboid(fill), new Pattern.Single(h.state("minecraft:water")), filled);
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(filled.result != null, "fill running"))
                .createAndAddReported(() -> {
                    completed(filled, "fill");
                    for (int z = z0 + 20; z <= z0 + 25; z++) nudge(world, x0 + 15, FLOOR + 1, z);
                })
                .createAndAdd(settles(world, area, 100, "the water"))
                .createAndAddReported(() -> {
                    check(!world.getFluidState(east).isEmpty(), "the water crossed into the next chunk");
                    ProtectionHook.protect(builder, world, x0 + 16, z0, x0 + SIDE - 1, z0 + SIDE - 1);
                    step(h, builder, true, undone);
                })
                .createAndAdd(() -> check(undone.result != null, "undo running"))
                .createAndAddReported(() -> {
                    ProtectionHook.clear(builder);
                    completed(undone, "undo");
                    check(undone.result.skippedProtected() > 0, "cells in the protected chunk were skipped: " + undone.result);
                    check(!world.getFluidState(east).isEmpty(), "the water there stays");
                    int differing = 0;
                    BlockPos.Mutable p = new BlockPos.Mutable();
                    for (int y = area.min().y(); y <= area.max().y(); y++) {
                        for (int z = area.min().z(); z <= area.max().z(); z++) {
                            for (int x = area.min().x(); x < x0 + 16; x++) {
                                if (Block.getRawIdFromState(world.getBlockState(p.set(x, y, z))) != before.get(x, y, z)) {
                                    differing++;
                                }
                            }
                        }
                    }
                    check(differing == 0, differing + " cells outside the protected chunk were not restored");
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Marks live as long as their entry: an entry dropped from the history (over its step cap) loses the marks of its
     * water at the next sweep, while the entry still held keeps them.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_undo_sweep", tickLimit = LIMIT)
    public void anEntryDroppedFromTheHistoryLosesItsMarks(TestContext context) {
        Harness h = new Harness(context, new HistoryLimits(1, 1L << 30, 1L << 32));
        ServerWorld world = h.world;
        FluidTrails trails = h.runtime.fluidTrails();
        int[] at = regionCorner(context, 937);
        int x0 = at[0], z0 = at[1];
        Box area = terrain(h, x0, z0, "minecraft:stone");
        BlockPos first = pos(x0 + 10, FLOOR + 1, z0 + 10), second = pos(x0 + 30, FLOOR + 1, z0 + 30);
        UUID[] ids = new UUID[2];
        RecordingListener a = new RecordingListener(), b = new RecordingListener();
        h.fill(box(x0 + 10, FLOOR + 1, z0 + 10, x0 + 12, FLOOR + 1, z0 + 12), "minecraft:water", a);
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(a.result != null, "first fill running"))
                .createAndAddReported(() -> {
                    completed(a, "first fill");
                    ids[0] = h.service.historyService().undoEntries(h.player.getUuid()).get(0).id();
                    check(trails.markedBy(world, first, ids[0]), "the first fill's water is marked");
                    h.fill(box(x0 + 30, FLOOR + 1, z0 + 30, x0 + 32, FLOOR + 1, z0 + 32), "minecraft:water", b);
                })
                .createAndAdd(() -> check(b.result != null, "second fill running"))
                .createAndAddReported(() -> {
                    completed(b, "second fill");
                    List<HistoryEntry> held = h.service.historyService().undoEntries(h.player.getUuid());
                    check(held.size() == 1 && !held.get(0).id().equals(ids[0]), "the first entry was dropped");
                    ids[1] = held.get(0).id();
                    // While a saved history is still loading its entries are unknown: nothing is forgotten.
                    boolean[] loading = {true};
                    java.util.function.Supplier<java.util.Set<UUID>> loadingHistory =
                            () -> loading[0] ? null : java.util.Set.of();
                    trails.register(loadingHistory);
                    trails.sweep();
                    check(trails.markedBy(world, first, ids[0]), "a sweep during a history load forgets nothing");
                    loading[0] = false;
                    trails.sweep();
                    check(!trails.markedBy(world, first, ids[0]), "the dropped entry's marks are gone");
                    check(trails.markedBy(world, second, ids[1]), "the held entry keeps its marks");
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    // =================================================================== failure cases (review of 2d392ba4)

    private static UUID newestEntry(Harness h) {
        return h.service.historyService().undoEntries(h.player.getUuid()).get(0).id();
    }

    /**
     * Water woken while its own Fill is still writing (a job over several ticks) stays put until the Fill is done: it
     * never flows into a cell the Fill writes later (the record would then take that water as the cell's "before" and
     * undo would leave it). Woken on its outer edge too, it flows once the Fill is done; undo leaves nothing.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_undo_mid_fill", tickLimit = LIMIT)
    public void waterWokenWhileItsFillStillWritesIsUndoneExactly(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 256);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        FluidTrails trails = h.runtime.fluidTrails();
        int[] at = regionCorner(context, 921);
        int x0 = at[0], z0 = at[1];
        Box area = terrain(h, x0, z0, "minecraft:stone");
        // Two sections along z, 256 cells each: the executor writes one per tick.
        Box fill = box(x0 + 16, FLOOR + 1, z0 + 16, x0 + 31, FLOOR + 1, z0 + 47);
        BlockPos written = pos(x0 + 20, FLOOR + 1, z0 + 31), later = pos(x0 + 20, FLOOR + 1, z0 + 32);
        WorldSnapshot[] before = new WorldSnapshot[1];
        RecordingListener filled = new RecordingListener(), undone = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(ticking(world, area), "the area's chunks tick"))
                .createAndAddReported(() -> {
                    before[0] = capture(world, area);
                    h.fill(fill, "minecraft:water", filled);
                    MultiplayerGameTest.tickUntil(executor, () -> world.getFluidState(written).isStill(), 20,
                            "the first section");
                    check(filled.result == null && world.getBlockState(later).isAir(), "the Fill is half written");
                    for (int x = x0 + 16; x <= x0 + 31; x++) nudge(world, x, FLOOR + 1, z0 + 32); // the written edge
                    for (int z = z0 + 16; z <= z0 + 31; z++) nudge(world, x0 + 15, FLOOR + 1, z); // its outer side
                })
                .expectMinDuration(100)
                .createAndAddReported(() -> {
                    check(world.getFluidTickScheduler().isQueued(written, Fluids.WATER), "the woken water waits to tick");
                    check(fluidOutside(world, area, new Region.Cuboid(fill)) == 0 && world.getBlockState(later).isAir(),
                            "the half-written Fill's water stayed put");
                    MultiplayerGameTest.tickUntil(executor, () -> filled.result != null, 20, "the Fill");
                    completed(filled, "fill");
                })
                .createAndAdd(settles(world, area, 100, "the water"))
                .createAndAddReported(() -> {
                    check(fluidOutside(world, area, new Region.Cuboid(fill)) > 0, "the water flowed once the Fill was done");
                    check(!trails.isFrozen(newestEntry(h)), "the Fill's entry is no longer held still");
                    step(h, h.player, true, undone);
                    MultiplayerGameTest.tickUntil(executor, () -> undone.result != null, 40, "the undo");
                    completed(undone, "undo");
                })
                .expectMinDuration(WATER_TICKS)
                .createAndAddReported(() -> {
                    checkExact(world, before[0], "mid_fill: after the undo");
                    executor.shutdown();
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * An undo refused after its trail was taken (the executor's queue is full) changes nothing: the trail is given
     * back, the entry is not replaced and not held still. The next undo takes everything back.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_undo_refused", tickLimit = LIMIT)
    public void anUndoRefusedAfterTakingTheTrailGivesItBack(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        FluidTrails trails = h.runtime.fluidTrails();
        EditExecutor executor = h.runtime.executor();
        int[] at = regionCorner(context, 923);
        int x0 = at[0], z0 = at[1];
        Box area = terrain(h, x0, z0, "minecraft:stone");
        Box fill = box(x0 + 20, FLOOR + 1, z0 + 20, x0 + 27, FLOOR + 1, z0 + 27);
        WorldSnapshot before = capture(world, area);
        RecordingListener filled = new RecordingListener(), undone = new RecordingListener();
        h.fill(fill, "minecraft:water", filled);
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(filled.result != null, "fill running"))
                .createAndAddReported(() -> {
                    completed(filled, "fill");
                    for (int z = z0 + 20; z <= z0 + 27; z++) nudge(world, x0 + 19, FLOOR + 1, z);
                })
                .createAndAdd(settles(world, area, 100, "the water"))
                .createAndAddReported(() -> {
                    HistoryEntry held = h.service.historyService().undoEntries(h.player.getUuid()).get(0);
                    long trail = trails.trailCells(world, held.id());
                    check(trail > 0, "the water left a trail");
                    // The server's own jobs fill the executor's queue.
                    Box one = box(x0, FLOOR + 8, z0, x0, FLOOR + 8, z0);
                    int air = h.state("minecraft:air");
                    List<UUID> fillers = new ArrayList<>();
                    try {
                        for (int i = 0; i <= executor.settings().maxQueued(); i++) {
                            fillers.add(executor.submit(JobRequest.system(world,
                                    new EngineTestSupport.BoxFill("filler " + i, one, air), null)).jobId());
                        }
                        throw new GameTestException("the queue never filled");
                    } catch (EditRejected e) {
                        check(e.reason() == RejectReason.QUEUE_FULL, "filler " + e.getMessage());
                    }
                    try {
                        h.service.undo(h.player, ConflictPolicy.SKIP_CONFLICTS, undone);
                        throw new GameTestException("the undo was admitted into a full queue");
                    } catch (EditRejected e) {
                        check(e.reason() == RejectReason.QUEUE_FULL, "refused " + e.getMessage());
                    } finally {
                        for (UUID filler : fillers) executor.cancel(filler);
                    }
                    check(trails.trailCells(world, held.id()) == trail, "the trail was given back");
                    check(h.service.historyService().undoEntries(h.player.getUuid()).get(0).record() == held.record(),
                            "the entry was not replaced");
                    check(!trails.isFrozen(held.id()), "the entry is not held still");
                    step(h, h.player, true, undone);
                })
                .createAndAdd(() -> check(undone.result != null, "undo running"))
                .createAndAddReported(() -> completed(undone, "undo"))
                .expectMinDuration(WATER_TICKS)
                .createAndAddReported(() -> {
                    checkExact(world, before, "refused: after the second undo");
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * An undo cancelled part way leaves the entry undoable and not held still; what its remaining water does meanwhile
     * is followed, and the undo run again takes everything back.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_undo_cancelled", tickLimit = LIMIT)
    public void aCancelledUndoRunAgainLeavesNothing(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 64);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        FluidTrails trails = h.runtime.fluidTrails();
        int[] at = regionCorner(context, 925);
        int x0 = at[0], z0 = at[1];
        Box area = terrain(h, x0, z0, "minecraft:stone");
        // Across a chunk border (x0 + 16), so the undo has several sections to be cancelled between.
        Box fill = box(x0 + 12, FLOOR + 1, z0 + 20, x0 + 19, FLOOR + 1, z0 + 27);
        WorldSnapshot before = capture(world, area);
        RecordingListener filled = new RecordingListener(), undone = new RecordingListener(),
                again = new RecordingListener();
        h.fill(fill, "minecraft:water", filled);
        MultiplayerGameTest.tickUntil(executor, () -> filled.result != null, 40, "the Fill");
        completed(filled, "fill");
        for (int z = z0 + 20; z <= z0 + 27; z++) nudge(world, x0 + 11, FLOOR + 1, z);
        for (int z = z0 + 20; z <= z0 + 27; z++) nudge(world, x0 + 20, FLOOR + 1, z);
        UUID[] id = new UUID[1];
        context.createTimedTaskRunner()
                .createAndAdd(settles(world, area, 100, "the water"))
                .createAndAddReported(() -> {
                    id[0] = newestEntry(h);
                    JobTicket ticket;
                    try {
                        ticket = h.service.undo(h.player, ConflictPolicy.SKIP_CONFLICTS, undone);
                    } catch (EditRejected e) {
                        throw new GameTestException("undo refused: " + e.getMessage());
                    }
                    executor.tick();
                    check(h.service.cancel(h.player, ticket.jobId()), "the undo was cancelled");
                    MultiplayerGameTest.tickUntil(executor, () -> undone.result != null, 400, "the cancelled undo");
                    check(undone.result.outcome() == JobOutcome.CANCELLED, "undo " + undone.result);
                    check(!leftover(world, before).none(), "the cancelled undo took back only part");
                    check(!trails.isFrozen(id[0]), "a cancelled undo does not hold the entry still");
                    check(newestEntry(h).equals(id[0]), "the entry is still the one to undo");
                })
                .expectMinDuration(WATER_TICKS)
                .createAndAddReported(() -> {
                    step(h, h.player, true, again);
                    MultiplayerGameTest.tickUntil(executor, () -> again.result != null, 400, "the undo run again");
                    completed(again, "undo run again");
                })
                .expectMinDuration(WATER_TICKS)
                .createAndAddReported(() -> {
                    checkExact(world, before, "cancelled: after the undo run again");
                    executor.shutdown();
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Redo anyway marks the water it puts back as the entry's: a redo kept a cell a player built on, Redo anyway puts
     * the water there too, and what that water does next is undone with the entry.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_undo_redo_anyway", tickLimit = LIMIT)
    public void redoAnywayMarksTheWaterItPutsBack(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        FluidTrails trails = h.runtime.fluidTrails();
        int[] at = regionCorner(context, 927);
        int x0 = at[0], z0 = at[1];
        Box area = terrain(h, x0, z0, "minecraft:stone");
        Box fill = box(x0 + 20, FLOOR + 1, z0 + 20, x0 + 27, FLOOR + 1, z0 + 27);
        BlockPos built = pos(x0 + 20, FLOOR + 1, z0 + 23);
        WorldSnapshot before = capture(world, area);
        RecordingListener filled = new RecordingListener(), undone = new RecordingListener(),
                redone = new RecordingListener(), anyway = new RecordingListener(), last = new RecordingListener();
        h.fill(fill, "minecraft:water", filled);
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(filled.result != null, "fill running"))
                .createAndAddReported(() -> {
                    completed(filled, "fill");
                    step(h, h.player, true, undone);
                })
                .createAndAdd(() -> check(undone.result != null, "undo running"))
                .createAndAddReported(() -> {
                    completed(undone, "undo");
                    world.setBlockState(built, Blocks.STONE.getDefaultState()); // a player builds in the emptied Fill
                    step(h, h.player, false, redone);
                })
                .createAndAdd(() -> check(redone.result != null, "redo running"))
                .createAndAddReported(() -> {
                    completed(redone, "redo");
                    check(redone.result.skippedConflicts() == 1, "the redo kept the player's block: " + redone.result);
                    try {
                        h.service.historyOverwrite(h.player, true, 1, anyway);
                    } catch (EditRejected e) {
                        throw new GameTestException("Redo anyway refused: " + e.getMessage());
                    }
                })
                .createAndAdd(() -> check(anyway.result != null, "Redo anyway running"))
                .createAndAddReported(() -> {
                    completed(anyway, "Redo anyway");
                    UUID id = newestEntry(h);
                    check(world.getFluidState(built).isStill(), "Redo anyway put the water back");
                    check(trails.markedBy(world, built, id), "Redo anyway marked its water as the entry's");
                    check(!trails.isFrozen(id), "the entry is not held still after Redo anyway");
                    for (int z = z0 + 20; z <= z0 + 27; z++) nudge(world, x0 + 19, FLOOR + 1, z);
                })
                .createAndAdd(settles(world, area, 100, "the water"))
                .createAndAddReported(() -> {
                    check(fluidOutside(world, area, new Region.Cuboid(fill)) > 0, "the water flowed");
                    step(h, h.player, true, last);
                })
                .createAndAdd(() -> check(last.result != null, "undo running"))
                .createAndAddReported(() -> completed(last, "undo"))
                .expectMinDuration(WATER_TICKS)
                .createAndAddReported(() -> {
                    checkExact(world, before, "redo_anyway: after the last undo");
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Redo anyway after the redone water flowed: a water Fill on grass is undone, a player builds in the emptied Fill,
     * the redo keeps that block, and the redone water kills grass and flows. Redo anyway puts the water on the player's
     * block and leaves what the water did as it is (it folds it into the entry toward its before, as the next undo
     * would); that undo then leaves the area exactly as before the Fill, grass included. (Folded toward the after, as
     * before 2026-09-29, Redo anyway put the grass back and the undo then turned it to dirt.)
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_undo_redo_anyway_flowed", tickLimit = LIMIT)
    public void redoAnywayAfterTheWaterFlowedLeavesTheNextUndoExact(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 958);
        int x0 = at[0], z0 = at[1];
        Box area = terrain(h, x0, z0, "minecraft:grass_block");
        Box fill = box(x0 + 18, FLOOR + 1, z0 + 18, x0 + 29, FLOOR + 2, z0 + 29);
        BlockPos built = pos(x0 + 18, FLOOR + 2, z0 + 23);
        WorldSnapshot before = capture(world, area);
        RecordingListener filled = new RecordingListener(), undone = new RecordingListener(),
                redone = new RecordingListener(), anyway = new RecordingListener(), last = new RecordingListener();
        h.fill(fill, "minecraft:water", filled);
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(filled.result != null, "fill running"))
                .createAndAddReported(() -> {
                    completed(filled, "fill");
                    step(h, h.player, true, undone);
                })
                .createAndAdd(() -> check(undone.result != null, "undo running"))
                .createAndAddReported(() -> {
                    completed(undone, "undo");
                    world.setBlockState(built, Blocks.STONE.getDefaultState()); // a player builds in the emptied Fill
                    step(h, h.player, false, redone);
                })
                .createAndAdd(() -> check(redone.result != null, "redo running"))
                .createAndAddReported(() -> {
                    completed(redone, "redo");
                    check(redone.result.skippedConflicts() == 1, "the redo kept the player's block: " + redone.result);
                    for (int i = 0; i < 8 && count(world, area, Blocks.DIRT) == 0; i++) {
                        world.tickChunk(world.getChunk((x0 + 18) >> 4, (z0 + 18) >> 4), 4096);
                    }
                    check(count(world, area, Blocks.DIRT) > 0, "grass under the redone water died");
                })
                .createAndAdd(settles(world, area, 100, "the water"))
                .createAndAddReported(() -> {
                    check(fluidOutside(world, area, new Region.Cuboid(fill)) > 0, "the redone water flowed");
                    try {
                        h.service.historyOverwrite(h.player, true, 1, anyway);
                    } catch (EditRejected e) {
                        throw new GameTestException("Redo anyway refused: " + e.getMessage());
                    }
                })
                .createAndAdd(() -> check(anyway.result != null, "Redo anyway running"))
                .createAndAddReported(() -> {
                    completed(anyway, "Redo anyway");
                    check(world.getFluidState(built).isStill(), "Redo anyway put the water on the player's block");
                    check(count(world, area, Blocks.DIRT) > 0, "Redo anyway left what the water did");
                    step(h, h.player, true, last);
                })
                .createAndAdd(() -> check(last.result != null, "undo running"))
                .createAndAddReported(() -> {
                    completed(last, "undo");
                    check(last.result.skippedConflicts() == 0, "nothing kept: " + last.result);
                    checkExact(world, before, "redo_anyway_flowed: the undo after Redo anyway");
                })
                .expectMinDuration(WATER_TICKS)
                .createAndAddReported(() -> {
                    checkExact(world, before, "redo_anyway_flowed: " + WATER_TICKS + " ticks after the undo");
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Water that froze (the play check: a water Fill in a cold biome with fast random ticks turned edge sources to ice,
     * and undo left the ice). In a snowy biome the world's ice-and-snow tick freezes the Fill's edge sources; a player
     * puts glowstone over one and its light melts the ice, whose water runs out; the player takes the glowstone away.
     * Undo leaves the area exactly as before the Fill, and stays so; redo puts the water and ice back as the undo found
     * them, and undo again restores the area.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_undo_frozen", tickLimit = LIMIT)
    public void undoingAWaterFillThatFrozeTakesBackTheIce(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        FluidTrails trails = h.runtime.fluidTrails();
        int[] at = regionCorner(context, 959);
        int x0 = at[0], z0 = at[1];
        Box area = terrain(h, x0, z0, "minecraft:stone");
        RegistryEntry<Biome> snowy = world.getRegistryManager().get(RegistryKeys.BIOME)
                .getEntry(BiomeKeys.SNOWY_PLAINS).orElseThrow();
        check(FillBiomeCommand.fillBiome(world, pos(x0, FLOOR - 3, z0), pos(x0 + SIDE - 1, FLOOR + 8, z0 + SIDE - 1),
                snowy).left().isPresent(), "the area became a snowy plain");
        world.setWeather(24_000, 0, false, false); // no snow falls on the stone meanwhile
        Box fill = box(x0 + 20, FLOOR + 1, z0 + 20, x0 + 27, FLOOR + 1, z0 + 27);
        BlockPos lit = pos(x0 + 20, FLOOR + 1, z0 + 23);
        BlockPos lamp = lit.up();
        WorldSnapshot before = capture(world, area);
        WorldSnapshot[] found = new WorldSnapshot[1];
        RecordingListener filled = new RecordingListener(), undone = new RecordingListener(),
                redone = new RecordingListener(), again = new RecordingListener();
        h.fill(fill, "minecraft:water", filled);
        UUID[] id = new UUID[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(filled.result != null, "fill running"))
                .createAndAddReported(() -> {
                    completed(filled, "fill");
                    id[0] = newestEntry(h);
                    // The world's ice-and-snow tick of every column of the Fill: the edge sources freeze.
                    for (int x = fill.min().x(); x <= fill.max().x(); x++) {
                        for (int z = fill.min().z(); z <= fill.max().z(); z++) world.tickIceAndSnow(pos(x, FLOOR + 1, z));
                    }
                    // Edges first; a source beside ice is not surrounded by water any more, so it freezes too.
                    int ice = count(world, area, Blocks.ICE);
                    check(ice >= 28, "at least the Fill's 28 edge sources froze: " + ice);
                    check(world.getBlockState(lit).isOf(Blocks.ICE), "the ice at " + lit.toShortString());
                    check(trails.markedBy(world, lit, id[0]), "the ice is still the entry's");
                    check(trails.trailCells(world, id[0]) >= 28, "the freezing was followed");
                    world.setBlockState(lamp, Blocks.GLOWSTONE.getDefaultState()); // a player's light over the ice
                })
                // Random ticks until the light has reached the ice and melted it.
                .createAndAdd(() -> {
                    world.tickChunk(world.getChunk(lit.getX() >> 4, lit.getZ() >> 4), 4096);
                    check(world.getFluidState(lit).isStill(), "waiting for the ice under the glowstone to melt");
                })
                .createAndAdd(settles(world, area, 100, "the meltwater"))
                .createAndAddReported(() -> {
                    check(fluidOutside(world, area, new Region.Cuboid(fill)) > 0, "the meltwater ran out");
                    world.setBlockState(lamp, Blocks.AIR.getDefaultState()); // the player takes the glowstone away
                })
                .expectMinDuration(100)
                .createAndAddReported(() -> {
                    found[0] = capture(world, area);
                    step(h, h.player, true, undone);
                })
                .createAndAdd(() -> check(undone.result != null, "undo running"))
                .createAndAddReported(() -> {
                    completed(undone, "undo");
                    check(undone.result.skippedConflicts() == 0, "nothing kept: " + undone.result);
                    checkExact(world, before, "frozen: right after the undo");
                })
                .expectMinDuration(WATER_TICKS)
                .createAndAddReported(() -> {
                    checkExact(world, before, "frozen: " + WATER_TICKS + " ticks after the undo");
                    step(h, h.player, false, redone);
                })
                .createAndAdd(() -> check(redone.result != null, "redo running"))
                .createAndAddReported(() -> {
                    completed(redone, "redo");
                    checkSame(found[0], capture(world, area), "the redo puts back the water and ice as the undo found them");
                    step(h, h.player, true, again);
                })
                .createAndAdd(() -> check(again.result != null, "second undo running"))
                .createAndAddReported(() -> {
                    completed(again, "second undo");
                    checkExact(world, before, "frozen: after undo, redo and undo");
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Past its trail cap an entry is no longer followed: its marks go, and its undo takes back what was recorded until
     * then (the Fill's own cells always) and leaves the rest (the documented limit).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_undo_capped", tickLimit = LIMIT)
    public void pastItsTrailCapAnEntryIsNoLongerFollowed(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        FluidTrails trails = h.runtime.fluidTrails();
        int[] at = regionCorner(context, 929);
        int x0 = at[0], z0 = at[1];
        Box area = terrain(h, x0, z0, "minecraft:stone");
        Box fill = box(x0 + 20, FLOOR + 1, z0 + 20, x0 + 27, FLOOR + 1, z0 + 27);
        BlockPos inside = pos(x0 + 23, FLOOR + 1, z0 + 23);
        RecordingListener filled = new RecordingListener(), undone = new RecordingListener();
        h.fill(fill, "minecraft:water", filled);
        UUID[] id = new UUID[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(filled.result != null, "fill running"))
                .createAndAddReported(() -> {
                    completed(filled, "fill");
                    id[0] = newestEntry(h);
                    check(trails.markedBy(world, inside, id[0]), "the Fill's water is marked");
                    trails.capTrail(id[0], 1);
                    for (int z = z0 + 20; z <= z0 + 27; z++) nudge(world, x0 + 19, FLOOR + 1, z);
                })
                .createAndAdd(settles(world, area, 100, "the water"))
                .createAndAddReported(() -> {
                    check(!trails.markedBy(world, inside, id[0]), "past the cap the entry's marks are gone");
                    step(h, h.player, true, undone);
                })
                .createAndAdd(() -> check(undone.result != null, "undo running"))
                .createAndAddReported(() -> {
                    completed(undone, "undo");
                    for (int x = x0 + 20; x <= x0 + 27; x++) {
                        for (int z = z0 + 20; z <= z0 + 27; z++) {
                            check(world.getBlockState(pos(x, FLOOR + 1, z)).isAir(), "the Fill's cells are restored");
                        }
                    }
                    check(fluidOutside(world, area, new Region.Cuboid(fill)) > 0,
                            "water the entry no longer followed stays");
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }
}
