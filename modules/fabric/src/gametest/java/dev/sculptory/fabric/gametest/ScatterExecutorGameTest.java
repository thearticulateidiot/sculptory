package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;
import static dev.sculptory.fabric.gametest.ScatterGameTest.FLOOR_Y;
import static dev.sculptory.fabric.gametest.ScatterGameTest.block;
import static dev.sculptory.fabric.gametest.ScatterGameTest.find;
import static dev.sculptory.fabric.gametest.ScatterGameTest.floor;
import static dev.sculptory.fabric.gametest.ScatterGameTest.heldPlan;
import static dev.sculptory.fabric.gametest.ScatterGameTest.planNow;
import static dev.sculptory.fabric.gametest.ScatterGameTest.preview;
import static dev.sculptory.fabric.gametest.ScatterGameTest.region;
import static dev.sculptory.fabric.gametest.ScatterGameTest.request;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.scatter.ScatterPlan;
import dev.sculptory.fabric.engine.impl.ServerScatter;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.gametest.ScatterGameTest.Reply;
import dev.sculptory.fabric.world.WorldChecks;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.config.UnloadedPolicy;
import dev.sculptory.server.engine.impl.EditExecutor;
import dev.sculptory.server.platform.WriteOptions;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

/**
 * Scatter through a private executor ticked by hand (M3 review): planning in the executor's lane, a cell built on
 * while a commit is writing its section, and a commit whose neighbouring chunks are not loaded yet.
 */
public final class ScatterExecutorGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /** A private executor: a 200 ms budget, the LOAD policy, a cell cap per tick and a ticket window. */
    private static EditExecutor<ServerWorld> executor(TestContext context, long maxBlocksPerTick, int maxTickets) {
        return new EditExecutor<>(EngineTestSupport.runtime(context),
                new EditExecutor.Settings(200_000_000L, maxBlocksPerTick, 0.4, 2, 8, 32, maxTickets, UnloadedPolicy.LOAD,
                        1024, 16_384));
    }

    /** Ticks the executor until the listener has a result (bounded). */
    private static void runUntilFinished(EditExecutor<ServerWorld> executor, RecordingListener listener, String what) {
        for (int i = 0; i < 10_000 && listener.result == null; i++) executor.tick();
        check(listener.result != null, what + " did not finish");
    }

    /** A small tree: a three-log trunk under a 3 × 3 leaf layer, anchored under the trunk. */
    private static Clipboard tree(Harness h) {
        Clipboard.Builder tree = Clipboard.builder(h.runtime.states(), new BlockPos(3, 4, 3)).anchor(new BlockPos(1, 0, 1));
        for (int y = 0; y < 3; y++) tree.set(1, y, 1, h.state("minecraft:oak_log[axis=y]"));
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                tree.set(x, 3, z, h.state("minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]"));
            }
        }
        return tree.build();
    }

    /**
     * A5: planning runs through the real {@code addLane}/{@code runLanes} path. Only the executor is ticked: the
     * preview is planned in its lane (a share so small that each tick plans its one guaranteed step), a fill admitted
     * over the held area stays queued until the plan is done and then runs, and a planner failure in the lane is
     * answered without stopping the executor.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_lane", tickLimit = LIMIT)
    public void scatterPlansInTheExecutorLane(TestContext context) {
        EditExecutor<ServerWorld> executor = executor(context, 0, 64);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerScatter scatter = new ServerScatter(h.service);
        EditExecutor.Lane lane = scatter.lane();
        executor.addLane(lane, 1e-6);
        int[] at = regionCorner(context, 88);
        int x0 = at[0], z0 = at[1];
        Box all = floor(h, x0, z0, 64, 64);
        SourceRef gold = block(h, h.player, "minecraft:gold_block");
        Reply reply = preview(scatter, h.player, request(region(x0, z0, x0 + 63, z0 + 63), 3, 3L, gold));
        check(lane.busy(), "the lane has nothing to do");
        RecordingListener fill = new RecordingListener();
        h.fill(box(x0, FLOOR_Y + 8, z0, x0 + 63, FLOOR_Y + 8, z0 + 63), "minecraft:glass", fill);
        int ticks = 0;
        while ((!reply.finished() || fill.result == null) && ticks < 10_000) {
            executor.tick();
            ticks++;
            if (!reply.finished()) {
                check(fill.result == null && !fill.phases.contains(Phase.APPLY), "the fill ran while the lane planned");
            }
        }
        reply.get("lane-planned preview");
        check(ticks > 16, "planned in " + ticks + " executor ticks");
        check(fill.phases.contains(Phase.QUEUED), "the fill was not held off: " + fill.phases);
        check(fill.result != null && fill.result.outcome() == JobOutcome.COMPLETED, "fill " + fill.result);
        check(!lane.busy() && scatter.active() == 0 && executor.holdCount() == 0, "the lane kept work");
        check(heldPlan(h, h.player).placements().size() > 50, "the lane plan is small");

        Reply failing = preview(scatter, h.player, request(region(x0, z0, x0 + 63, z0 + 63), 3, 4L, gold));
        scatter.observeSteps(() -> {
            throw new IllegalStateException("planner failure (test)");
        });
        executor.tick();
        scatter.observeSteps(null);
        check(failing.reason == RejectReason.INVALID && failing.calls == 1, "failure answered " + failing.reason);
        check(executor.holdCount() == 0 && !lane.busy(), "the failed preview kept its hold");
        RecordingListener after = new RecordingListener();
        h.fill(box(x0, FLOOR_Y + 8, z0, x0 + 3, FLOOR_Y + 8, z0 + 3), "minecraft:air", after);
        runUntilFinished(executor, after, "a job after the lane failure");
        check(after.result.outcome() == JobOutcome.COMPLETED, "after " + after.result);
        executor.removeLane(lane);
        executor.shutdown();
        scatter.shutdown();
        forceChunks(h.world, all, false);
        h.close();
        context.complete();
    }

    /**
     * A1: the commit writes its one section over many ticks (8 cells per tick). A chest placed, after the section was
     * computed, where a tree's leaves are still to be written survives with its contents. If that tree had not started
     * yet, the check before its first write skips it whole; if it had, the write-time guard refuses the chest's cell
     * and cuts it short: nothing of it after that cell is written. Either way it is reported once, the other trees are
     * whole, and undo restores everything the commit wrote while leaving the chest alone.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_race", tickLimit = LIMIT)
    public void scatterCommitLeavesACellBuiltWhileItWrites(TestContext context) {
        EditExecutor<ServerWorld> executor = executor(context, 8, 64);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 89);
        int x0 = at[0], z0 = at[1];
        Box all = floor(h, x0, z0, 16, 16);
        SourceRef source = new SourceRef.Clipboard(h.service.clipboards().install(h.player.getUuid(), tree(h)).id());
        // Anchors keep the 3 × 3 crowns inside one chunk column, all in section y 6: one section to write.
        Reply reply = preview(scatter, h.player, request(region(x0 + 1, z0 + 1, x0 + 14, z0 + 14), 4, 21L, source));
        planNow(scatter, reply);
        ScatterPlan plan = heldPlan(h, h.player);
        check(plan.placements().size() >= 4, "only " + plan.placements().size() + " placements");
        Box area = box(x0, FLOOR_Y, z0, x0 + 15, FLOOR_Y + 8, z0 + 15);
        WorldSnapshot before = capture(h.world, area);
        RecordingListener job = ScatterGameTest.commit(h, h.player, reply.get("preview").planId());
        executor.tick();
        check(job.result == null && job.phases.contains(Phase.APPLY), "the commit is not writing: " + job.phases);

        // Some trunks are written, no crown yet: build a chest where the last tree's crown goes.
        BlockPos anchor = plan.placements().get(plan.placements().size() - 1).anchor();
        net.minecraft.util.math.BlockPos chest = pos(anchor.x() + 1, anchor.y() + 3, anchor.z());
        check(h.world.getBlockState(chest).isAir(), "the crown cell is already written");
        boolean started = h.world.getBlockState(pos(anchor.x(), anchor.y(), anchor.z())).isOf(Blocks.OAK_LOG);
        check(!find(h.world, area, FLOOR_Y + 1, Blocks.OAK_LOG).isEmpty(), "no trunk written in the first tick");
        h.runtime.writer(h.world, WriteOptions.DEFAULT).write(chest.getX(), chest.getY(), chest.getZ(),
                h.state("minecraft:chest[facing=north,type=single,waterlogged=false]"), null);
        ((ChestBlockEntity) h.world.getBlockEntity(chest)).setStack(0, new ItemStack(Items.DIAMOND, 3));
        before.states[before.index(chest.getX(), chest.getY(), chest.getZ())] = Block.getRawIdFromState(
                h.world.getBlockState(chest));
        before.tiles.put(chest.asLong(), h.world.getBlockEntity(chest).createNbtWithId(h.world.getRegistryManager()));

        runUntilFinished(executor, job, "the commit");
        check(job.result.outcome() == JobOutcome.COMPLETED, "commit " + job.result);
        long cells = 12L * plan.placements().size();
        // Started: its trunk and the 5 crown cells before the chest's (in write order) are written, the 3 after not.
        long written = started ? cells - 4 : cells - 12;
        check(job.result.changed() == written, "changed " + job.result.changed() + " of " + cells
                + (started ? " (the tree had started)" : " (the tree had not started)"));
        check(job.result.skippedConflicts() == 1, "skipped " + job.result.skippedConflicts());
        check(h.events.scatterSkips.equals(List.of(1L)), "reported " + h.events.scatterSkips);
        check(h.world.getBlockState(chest).isOf(Blocks.CHEST), "the chest was replaced");
        ChestBlockEntity kept = (ChestBlockEntity) h.world.getBlockEntity(chest);
        check(kept != null && kept.getStack(0).isOf(Items.DIAMOND) && kept.getStack(0).getCount() == 3,
                "the chest lost its contents");
        int trees = plan.placements().size();
        check(find(h.world, area, FLOOR_Y + 1, Blocks.OAK_LOG).size() == (started ? trees : trees - 1), "trunks");
        check(find(h.world, area, FLOOR_Y + 4, Blocks.OAK_LEAVES).size() == 9 * trees - (started ? 4 : 9), "crowns");
        for (int x = anchor.x() - 1; x <= anchor.x() + 1; x++) {
            check(h.world.getBlockState(pos(x, anchor.y() + 3, anchor.z() + 1)).isAir(),
                    "the cut-short tree went on writing at " + x + "," + (anchor.z() + 1));
        }

        RecordingListener undo = new RecordingListener();
        h.undo(undo);
        runUntilFinished(executor, undo, "the undo");
        check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0, "undo " + undo.result);
        check(undo.result.changed() == written, "undo changed " + undo.result.changed());
        checkSame(before, capture(h.world, area), "after undo");
        executor.shutdown();
        scatter.shutdown();
        forceChunks(h.world, all, false);
        h.close();
        context.complete();
    }

    /**
     * A2: a commit of slabs that straddle two chunks, with neither chunk loaded and a one-ticket window (the job never
     * loads ahead). Before deciding a placement the job loads its other chunk, so no placement is skipped for a chunk
     * that simply had not loaded yet; every slab is whole and every ticket is given back.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_remote", tickLimit = LIMIT)
    public void scatterCommitWaitsForNeighbouringChunks(TestContext context) {
        EditExecutor<ServerWorld> executor = executor(context, 0, 1);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 90);
        int x0 = at[0], z0 = at[1];
        int cx0 = x0 >> 4, cz0 = z0 >> 4;
        Box all = floor(h, x0, z0, 32, 16);
        Clipboard slab = ScatterGameTest.slab(h, "minecraft:gold_block");
        SourceRef source = new SourceRef.Clipboard(h.service.clipboards().install(h.player.getUuid(), slab).id());
        // Anchors only on the two columns either side of the chunk boundary: every 3 × 3 slab straddles it.
        Reply reply = preview(scatter, h.player, request(region(x0 + 15, z0 + 1, x0 + 16, z0 + 14), 3, 8L, source));
        planNow(scatter, reply);
        ScatterPlan plan = heldPlan(h, h.player);
        long straddling = plan.placements().stream()
                .filter(p -> p.anchor().x() - 1 < x0 + 16 && p.anchor().x() + 1 >= x0 + 16).count();
        check(straddling >= 2 && plan.placements().size() == straddling, "placements " + plan.placements().size()
                + ", straddling " + straddling);
        forceChunks(h.world, all, false);
        RecordingListener[] job = new RecordingListener[1];
        int[] mostTickets = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    for (int cx = cx0 - 1; cx <= cx0 + 2; cx++) {
                        for (int cz = cz0 - 1; cz <= cz0 + 1; cz++) {
                            check(!WorldChecks.isChunkLoaded(h.world, cx, cz), "waiting for chunk " + cx + "," + cz
                                    + " to unload");
                        }
                    }
                    job[0] = ScatterGameTest.commit(h, h.player, reply.get("preview").planId());
                })
                .createAndAdd(() -> {
                    executor.tick();
                    mostTickets[0] = Math.max(mostTickets[0], executor.ticketsInFlight());
                    check(job[0].result != null, "commit running");
                })
                .createAndAdd(() -> {
                    check(job[0].result.outcome() == JobOutcome.COMPLETED, "commit " + job[0].result);
                    check(job[0].result.skippedConflicts() == 0, "skipped " + job[0].result.skippedConflicts()
                            + " placements (" + straddling + " straddle the chunks)");
                    check(job[0].result.changed() == 9L * plan.placements().size(), "changed " + job[0].result.changed());
                    check(job[0].phases.contains(Phase.LOAD_CHUNKS), "the chunks were loaded already: " + job[0].phases);
                    check(mostTickets[0] <= 2, "the job held " + mostTickets[0] + " tickets; the cap is 1 + 1");
                    check(executor.ticketsInFlight() == 0, executor.ticketsInFlight() + " tickets kept");
                    loadAndCount(h, all, plan);
                    executor.shutdown();
                    scatter.shutdown();
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * The read-column tickets are capped: with a one-ticket window, a slab on a chunk corner needs three other chunks,
     * more than the one extra ticket the job may hold. Only one of them is loaded for it: the placement is decided
     * without the others, skipped and reported, and the job never holds more than two tickets.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_readcap", tickLimit = LIMIT)
    public void scatterReadColumnTicketsAreCapped(TestContext context) {
        EditExecutor<ServerWorld> executor = executor(context, 0, 1);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 93);
        int x0 = at[0], z0 = at[1];
        int cx0 = x0 >> 4, cz0 = z0 >> 4;
        Box all = floor(h, x0, z0, 32, 32);
        Clipboard slab = ScatterGameTest.slab(h, "minecraft:gold_block");
        SourceRef source = new SourceRef.Clipboard(h.service.clipboards().install(h.player.getUuid(), slab).id());
        Reply reply = preview(scatter, h.player, request(region(x0 + 16, z0 + 16, x0 + 16, z0 + 16), 3, 8L, source));
        planNow(scatter, reply);
        check(heldPlan(h, h.player).placements().size() == 1, "one slab on the corner");
        forceChunks(h.world, all, false);
        RecordingListener[] job = new RecordingListener[1];
        int[] mostTickets = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    for (int cx = cx0 - 1; cx <= cx0 + 2; cx++) {
                        for (int cz = cz0 - 1; cz <= cz0 + 2; cz++) {
                            check(!WorldChecks.isChunkLoaded(h.world, cx, cz), "waiting for chunk " + cx + "," + cz
                                    + " to unload");
                        }
                    }
                    job[0] = ScatterGameTest.commit(h, h.player, reply.get("preview").planId());
                })
                .createAndAdd(() -> {
                    executor.tick();
                    mostTickets[0] = Math.max(mostTickets[0], executor.ticketsInFlight());
                    check(job[0].result != null, "commit running");
                })
                .createAndAdd(() -> {
                    check(job[0].result.outcome() == JobOutcome.COMPLETED, "commit " + job[0].result);
                    check(job[0].result.skippedConflicts() == 1 && job[0].result.changed() == 0, "commit " + job[0].result);
                    check(mostTickets[0] <= 2, "the job held " + mostTickets[0] + " tickets; the cap is 1 + 1");
                    check(executor.ticketsInFlight() == 0, executor.ticketsInFlight() + " tickets kept");
                    executor.shutdown();
                    scatter.shutdown();
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** Loads the floor again and checks every slab is whole. */
    private static void loadAndCount(Harness h, Box all, ScatterPlan plan) {
        EditTestSupport.loadAndForce(h.world, all);
        int gold = find(h.world, all, FLOOR_Y + 1, Blocks.GOLD_BLOCK).size();
        forceChunks(h.world, all, false);
        check(gold == 9 * plan.placements().size(), gold + " gold blocks for " + plan.placements().size() + " slabs");
    }
}
