package dev.sculptory.paper.devtest;

import static dev.sculptory.paper.devtest.SelfTestRun.box;
import static dev.sculptory.paper.devtest.SelfTestRun.cells;

import dev.sculptory.core.Box;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.scatter.GrownFeature;
import dev.sculptory.core.scatter.ScatterArea;
import dev.sculptory.core.scatter.ScatterPlan;
import dev.sculptory.core.scatter.ScatterSettings;
import dev.sculptory.core.scatter.ScatterSource;
import dev.sculptory.paper.SculptoryPlugin;
import dev.sculptory.paper.nms.PaperAccess;
import dev.sculptory.paper.perm.WorldGuardTestRegions;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.ScatterService;
import dev.sculptory.server.engine.impl.ServerScatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Bukkit;

/**
 * A scatter of trees next to a WorldGuard region: a grass floor 32 x 16, the region over its east half (x0 + 16 on, the
 * whole height). The preview plans no tree whose cells reach into the region (those are skipped whole, PROTECTED) and
 * changes nothing in the world; the commit writes each planned tree whole and nothing in the region, and its undo is
 * exact. Trees as in the shared ScatterFeatureGameTest (oak and birch, spacing 7, three in ten columns).
 */
final class ScatterChecks {
    private static final int FLOOR = 100;

    private ScatterChecks() {
    }

    static CompletableFuture<Void> run(SelfTestRun r) {
        if (!r.worldGuard()) {
            r.info("scatter: skipped", "WorldGuard is not installed");
            return CompletableFuture.completedFuture(null);
        }
        int x0 = r.baseX + 496, z0 = r.baseZ; // chunk-aligned: the region starts at the next chunk
        Box all = box(x0, FLOOR - 1, z0, x0 + 31, FLOOR + 30, z0 + 15);
        r.claim(all);
        for (BlockPos cell : cells(all)) {
            BlockState state = cell.getY() == FLOOR - 1 ? Blocks.STONE.defaultBlockState()
                    : cell.getY() == FLOOR ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState();
            if (r.level.getBlockState(cell) != state) PaperAccess.setBlockWithoutCallbacks(r.level, cell, state);
            r.level.getChunkSource().blockChanged(cell);
        }
        String id = r.region("scatter");
        WorldGuardTestRegions.add(r.level, id, x0 + 16, r.level.getMinBuildHeight(), z0 - 8, x0 + 40,
                r.level.getMaxBuildHeight() - 1, z0 + 24);
        Box region = box(x0 + 16, FLOOR - 1, z0, x0 + 31, FLOOR + 30, z0 + 15);
        int[] regionBefore = r.snapshot(region);
        int[] allBefore = r.snapshot(all);

        ServerScatter<net.minecraft.server.level.ServerPlayer, net.minecraft.server.level.ServerLevel> scatter =
                new ServerScatter<>(r.service);
        r.cleanup(scatter::shutdown);
        Reply reply = new Reply();
        List<C2S.ScatterPreview.Variant> variants = List.of(
                new C2S.ScatterPreview.Variant(new ScatterSource.Feature("minecraft:oak"), 1),
                new C2S.ScatterPreview.Variant(new ScatterSource.Feature("minecraft:birch"), 1));
        C2S.ScatterPreview request = new C2S.ScatterPreview(1,
                new ScatterArea.Region(box(x0, FLOOR, z0, x0 + 31, FLOOR + 10, z0 + 15)),
                new C2S.ScatterPreview.Settings(4L, 7, new ScatterSettings.Density.Fraction(0.3), SurfaceMask.ANY,
                        ScatterSettings.Fit.DEFAULT), variants, ScatterSettings.Transforms.ALL);
        try {
            scatter.preview(r.player, request, reply);
        } catch (EditRejected e) {
            return CompletableFuture.failedFuture(new IllegalStateException("preview refused: " + e.getMessage(), e));
        }
        // Planned over the next server ticks, as the planning lane would (its payloads are encoded off the thread).
        return planned(scatter, reply).thenCompose(v -> {
            if (reply.plan == null) {
                throw new IllegalStateException("no plan: " + reply.reason + " " + reply.detail + "; planning "
                        + scatter.planning(r.player.getUUID()) + ", active " + scatter.active() + ", encoding "
                        + scatter.encoding(r.player.getUUID()) + ", encoded " + scatter.encoded(r.player.getUUID()));
            }
            return afterPlan(r, x0, all, allBefore, region, regionBefore, reply);
        });
    }

    /** Runs the planning a step every server tick until the reply came (at most 600 ticks). */
    private static CompletableFuture<Void> planned(ServerScatter<?, ?> scatter, Reply reply) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        int[] ticks = {0};
        Bukkit.getScheduler().runTaskTimer(SculptoryPlugin.instance(), task -> {
            scatter.tick();
            if (reply.plan != null || reply.reason != null || ++ticks[0] >= 600) {
                task.cancel();
                done.complete(null);
            }
        }, 1, 1);
        return done;
    }

    private static CompletableFuture<Void> afterPlan(SelfTestRun r, int x0, Box all, int[] allBefore, Box region,
                                                     int[] regionBefore, Reply reply) {
        ScatterPlan held = r.service.scatterPlans().get(r.player.getUUID()).orElseThrow().plan();
        List<GrownFeature> trees = held.clusters();
        int reaching = 0;
        for (GrownFeature tree : trees) {
            if (tree.bounds().max().x() >= x0 + 16) reaching++;
        }
        Integer protectedCount = reply.plan.rejectedCounts().get("PROTECTED");
        r.record("scatter: the preview plans no tree reaching into the region and changes nothing",
                !trees.isEmpty() && reaching == 0 && r.countDiffering(all, allBefore) == 0,
                trees.size() + " trees planned, " + reaching + " reach the region, " + protectedCount
                        + " skipped as protected, " + r.countDiffering(all, allBefore) + " cells changed by the preview");
        return r.job("scatter commit", listener -> r.service.run(r.player,
                new OpSpec.ScatterCommit(reply.plan.planId()), RunOptions.DEFAULT, listener)).thenCompose(result -> {
                    int half = 0, whole = 0;
                    for (GrownFeature tree : trees) {
                        int written = 0;
                        for (int i = 0; i < tree.size(); i++) {
                            BlockState now = r.level.getBlockState(new BlockPos(tree.x(i), tree.y(i), tree.z(i)));
                            if (r.host.engine().states().handle(now) != tree.before(i)) written++;
                        }
                        if (written > 0 && written < changedCells(tree)) half++;
                        if (written > 0) whole++;
                    }
                    int regionChanged = r.countDiffering(region, regionBefore);
                    r.record("scatter: the commit cuts no tree and writes nothing in the region",
                            half == 0 && whole > 0 && regionChanged == 0, result.outcome() + ", " + result.changed()
                                    + " changed, " + whole + " trees written, " + half + " half built, " + regionChanged
                                    + " region cells changed");
                    return r.undo();
                }).thenAccept(result -> r.record("scatter: the commit's undo is exact",
                        r.countDiffering(all, allBefore) == 0, result.outcome() + ", "
                                + r.countDiffering(all, allBefore) + " cells differ"));
    }

    /** The cells of a planned tree that differ from what was there (its grown blocks over air or grass). */
    private static int changedCells(GrownFeature tree) {
        int n = 0;
        for (int i = 0; i < tree.size(); i++) {
            if (tree.after(i) != tree.before(i)) n++;
        }
        return n;
    }

    private static final class Reply implements ScatterService.PreviewReply {
        ScatterService.PlanReady plan;
        RejectReason reason;
        String detail;

        @Override
        public void done(ScatterService.PlanReady ready) {
            plan = ready;
        }

        @Override
        public void failed(RejectReason r, String d) {
            reason = r;
            detail = d;
        }

        @Override
        public void superseded() {
            reason = RejectReason.INVALID;
            detail = "superseded";
        }
    }
}
