package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.scatter.ScatterArea;
import dev.sculptory.core.scatter.ScatterPlan;
import dev.sculptory.core.scatter.ScatterSettings;
import dev.sculptory.core.schem.AssetInfo;
import dev.sculptory.fabric.engine.impl.ServerScatter;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.net.FabricTransport;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.fabric.world.WorldChecks;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.Codec;
import dev.sculptory.protocol.v2.Features;
import dev.sculptory.protocol.v2.Handshake;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.ProtocolException;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.protocol.v2.ScatterPlacements;
import dev.sculptory.protocol.v2.StreamAssembler;
import dev.sculptory.protocol.v2.StreamChunk;
import dev.sculptory.protocol.v2.StreamEnd;
import dev.sculptory.protocol.v2.StreamKind;
import dev.sculptory.protocol.v2.StreamOpen;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.ScatterService;
import dev.sculptory.server.engine.impl.AssetCache;
import dev.sculptory.server.engine.impl.ScatterPlans;
import dev.sculptory.server.library.LibraryPath;
import dev.sculptory.server.net.NetSession;
import dev.sculptory.server.net.ServerDispatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.world.border.WorldBorder;

/**
 * Server scatter (M3-B) against a real server world: previews planned over ticks, commits, undo, ownership, expiry,
 * protection, unloaded chunks, fluids and the placements stream. Each test works on its own stone floor at y = 100 in
 * a far region, in its own batch.
 */
public final class ScatterGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;
    static final int FLOOR_Y = 100;

    // ---------------------------------------------------------------- helpers

    /** Records the one answer a preview gets. */
    static final class Reply implements ScatterService.PreviewReply {
        ScatterService.PlanReady plan;
        RejectReason reason;
        String detail;
        boolean superseded;
        int calls;

        @Override
        public void done(ScatterService.PlanReady ready) {
            plan = ready;
            calls++;
        }

        @Override
        public void failed(RejectReason r, String d) {
            reason = r;
            detail = d;
            calls++;
        }

        @Override
        public void superseded() {
            superseded = true;
            calls++;
        }

        boolean finished() {
            return calls > 0;
        }

        ScatterService.PlanReady get(String what) {
            check(finished(), what + " still planning");
            check(calls == 1, what + " answered " + calls + " times");
            check(plan != null, what + " failed: " + reason + " " + detail + (superseded ? " (superseded)" : ""));
            return plan;
        }
    }

    /** A stone floor at y = 100 over [x0, x0 + w) × [z0, z0 + d), its chunks loaded and forced. */
    static Box floor(Harness h, int x0, int z0, int w, int d) {
        Box all = box(x0 - 16, FLOOR_Y - 4, z0 - 16, x0 + w + 15, FLOOR_Y + 30, z0 + d + 15);
        loadAndForce(h.world, all);
        BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
        int stone = h.state("minecraft:stone");
        for (int x = x0; x < x0 + w; x++) {
            for (int z = z0; z < z0 + d; z++) writer.write(x, FLOOR_Y, z, stone, null);
        }
        return all;
    }

    /** The columns [x0, x1] × [z0, z1], searched for a surface between y = 100 and 110. */
    static ScatterArea region(int x0, int z0, int x1, int z1) {
        return new ScatterArea.Region(box(x0, FLOOR_Y, z0, x1, FLOOR_Y + 10, z1));
    }

    static C2S.ScatterPreview request(int reqId, ScatterArea area, int spacing, ScatterSettings.Density density,
                                      ScatterSettings.Fit fit, long seed, ScatterSettings.Transforms transforms,
                                      SourceRef... sources) {
        List<C2S.ScatterPreview.Variant> variants = new ArrayList<>();
        for (SourceRef source : sources) variants.add(new C2S.ScatterPreview.Variant(source, 1));
        return new C2S.ScatterPreview(reqId, area, new C2S.ScatterPreview.Settings(seed, spacing, density,
                SurfaceMask.ANY, fit), variants, transforms);
    }

    static C2S.ScatterPreview request(ScatterArea area, int spacing, long seed, SourceRef... sources) {
        return request(1, area, spacing, new ScatterSettings.Density.Fraction(1), ScatterSettings.Fit.DEFAULT, seed,
                ScatterSettings.Transforms.ALL, sources);
    }

    /** One block of {@code state}, anchored on it: installed as the player's clipboard. */
    static SourceRef block(Harness h, ServerPlayerEntity player, String state) {
        Clipboard clipboard = Clipboard.builder(h.runtime.states(), new BlockPos(1, 1, 1)).set(0, 0, 0, h.state(state))
                .build();
        return new SourceRef.Clipboard(h.service.clipboards().install(player.getUuid(), clipboard).id());
    }

    /** A 3 × 1 × 3 slab of {@code state} anchored at its centre, as a clipboard. */
    static Clipboard slab(Harness h, String state) {
        Clipboard.Builder b = Clipboard.builder(h.runtime.states(), new BlockPos(3, 1, 3)).anchor(new BlockPos(1, 0, 1));
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) b.set(x, 0, z, h.state(state));
        }
        return b.build();
    }

    static LibraryPath libraryFile(String path) {
        try {
            return LibraryPath.file(path);
        } catch (dev.sculptory.server.library.LibraryPathException e) {
            throw new GameTestException("bad library path " + path);
        }
    }

    static Reply preview(ServerScatter scatter, ServerPlayerEntity player, C2S.ScatterPreview request) {
        Reply reply = new Reply();
        try {
            scatter.preview(player, request, reply);
        } catch (EditRejected e) {
            throw new GameTestException("preview refused: " + e.getMessage());
        }
        return reply;
    }

    static EditRejected refusal(ClipboardGameTest.ThrowingRun run) {
        return ClipboardGameTest.refusal(run);
    }

    /** Plans synchronously (one simulated tick after another) until the reply arrives. */
    static void planNow(ServerScatter scatter, Reply reply) {
        for (int i = 0; i < 100_000 && !reply.finished(); i++) scatter.tick();
        check(reply.finished(), "planning did not finish");
    }

    static RecordingListener commit(Harness h, ServerPlayerEntity player, UUID planId) {
        RecordingListener listener = new RecordingListener();
        try {
            h.service.run(player, new OpSpec.ScatterCommit(planId), RunOptions.DEFAULT, listener);
        } catch (EditRejected e) {
            throw new GameTestException("commit refused: " + e.getMessage());
        }
        return listener;
    }

    static ScatterPlan heldPlan(Harness h, ServerPlayerEntity player) {
        return h.service.scatterPlans().get(player.getUuid()).orElseThrow(() -> new GameTestException("no plan held"))
                .plan();
    }

    /** Positions of {@code block} in the layer y of the box. */
    static List<net.minecraft.util.math.BlockPos> find(ServerWorld world, Box box, int y, net.minecraft.block.Block block) {
        List<net.minecraft.util.math.BlockPos> found = new ArrayList<>();
        for (int x = box.min().x(); x <= box.max().x(); x++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                if (world.getBlockState(pos(x, y, z)).isOf(block)) found.add(pos(x, y, z));
            }
        }
        return found;
    }

    // ---------------------------------------------------------------- tests

    /**
     * The same seed gives the same plan (hash and placements payload), another seed another plan; the preview holds
     * its area while it plans; the committed blocks keep the spacing.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_determinism", tickLimit = LIMIT)
    public void scatterDeterministicAndSpaced(TestContext context) {
        Harness h = new Harness(context);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 70);
        int x0 = at[0], z0 = at[1];
        Box all = floor(h, x0, z0, 64, 64);
        SourceRef gold = block(h, h.player, "minecraft:gold_block");
        ScatterArea area = region(x0, z0, x0 + 63, z0 + 63);
        int spacing = 6;
        Reply first = preview(scatter, h.player, request(area, spacing, 42L, gold));
        check(h.service.executor().isLocked(h.world, box(x0, FLOOR_Y, z0, x0 + 63, FLOOR_Y + 5, z0 + 63)),
                "the preview does not hold its area");
        Reply[] later = new Reply[3];
        Sha256[] hashes = new Sha256[3];
        RecordingListener[] committed = new RecordingListener[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    scatter.tick();
                    check(first.finished(), "first preview planning");
                })
                .createAndAdd(() -> {
                    first.get("first preview");
                    check(!h.service.executor().isLocked(h.world, all), "the area is still held after planning");
                    hashes[0] = heldPlan(h, h.player).hash();
                    check(first.plan.placements() > 40, "only " + first.plan.placements() + " placements");
                    later[0] = preview(scatter, h.player, request(area, spacing, 42L, gold));
                })
                .createAndAdd(() -> {
                    scatter.tick();
                    check(later[0].finished(), "second preview planning");
                })
                .createAndAdd(() -> {
                    ScatterService.PlanReady again = later[0].get("second preview");
                    hashes[1] = heldPlan(h, h.player).hash();
                    check(hashes[0].equals(hashes[1]), "same seed, different plans");
                    check(java.util.Arrays.equals(first.plan.placementsPayload(), again.placementsPayload()),
                            "same seed, different placements");
                    check(!first.plan.planId().equals(again.planId()), "plan ids repeat");
                    check(first.plan.rejectedCounts().equals(again.rejectedCounts()), "different outcome counts");
                    later[1] = preview(scatter, h.player, request(area, spacing, 43L, gold));
                })
                .createAndAdd(() -> {
                    scatter.tick();
                    check(later[1].finished(), "third preview planning");
                })
                .createAndAdd(() -> {
                    later[1].get("third preview");
                    hashes[2] = heldPlan(h, h.player).hash();
                    check(!hashes[2].equals(hashes[0]), "another seed gave the same plan");
                    later[2] = preview(scatter, h.player, request(area, spacing, 42L, gold));
                })
                .createAndAdd(() -> {
                    scatter.tick();
                    check(later[2].finished(), "fourth preview planning");
                })
                .createAndAdd(() -> {
                    ScatterService.PlanReady plan = later[2].get("fourth preview");
                    check(heldPlan(h, h.player).hash().equals(hashes[0]), "the seed-42 plan changed");
                    committed[0] = commit(h, h.player, plan.planId());
                })
                .createAndAdd(() -> check(committed[0].result != null, "commit running"))
                .createAndAdd(() -> {
                    check(committed[0].result.outcome() == JobOutcome.COMPLETED, "commit " + committed[0].result);
                    List<net.minecraft.util.math.BlockPos> placed = find(h.world, all, FLOOR_Y + 1, Blocks.GOLD_BLOCK);
                    check(placed.size() == later[2].plan.placements(), placed.size() + " gold blocks for "
                            + later[2].plan.placements() + " placements");
                    check(committed[0].result.changed() == placed.size(), "changed " + committed[0].result.changed());
                    for (int i = 0; i < placed.size(); i++) {
                        for (int j = i + 1; j < placed.size(); j++) {
                            long dx = placed.get(i).getX() - placed.get(j).getX();
                            long dz = placed.get(i).getZ() - placed.get(j).getZ();
                            check(dx * dx + dz * dz >= (long) spacing * spacing, "gold blocks closer than the spacing at "
                                    + placed.get(i).toShortString() + " and " + placed.get(j).toShortString());
                        }
                    }
                    check(find(h.world, all, FLOOR_Y + 2, Blocks.GOLD_BLOCK).isEmpty(), "gold above the anchors");
                    scatter.shutdown();
                    forceChunks(h.world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A commit of small trees is one job and one history entry labelled with its placements; undo restores the area
     * exactly, and the plan cannot be committed twice.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_undo", tickLimit = LIMIT)
    public void scatterCommitUndoExact(TestContext context) {
        Harness h = new Harness(context);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 71);
        int x0 = at[0], z0 = at[1];
        Box all = floor(h, x0, z0, 48, 48);
        // A few blocks already there: short grass is replaced, a log blocks a placement.
        BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
        for (int i = 0; i < 48; i += 3) writer.write(x0 + i, FLOOR_Y + 1, z0 + (i * 7) % 48, h.state("minecraft:short_grass"), null);
        writer.write(x0 + 20, FLOOR_Y + 1, z0 + 20, h.state("minecraft:oak_log[axis=y]"), null);
        Clipboard.Builder tree = Clipboard.builder(h.runtime.states(), new BlockPos(3, 4, 3)).anchor(new BlockPos(1, 0, 1));
        for (int y = 0; y < 3; y++) tree.set(1, y, 1, h.state("minecraft:oak_log[axis=y]"));
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) tree.set(x, 3, z, h.state("minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]"));
        }
        tree.set(0, 2, 1, h.state("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"));
        SourceRef source = new SourceRef.Clipboard(h.service.clipboards().install(h.player.getUuid(), tree.build()).id());
        Box area = box(x0 - 4, FLOOR_Y - 1, z0 - 4, x0 + 51, FLOOR_Y + 8, z0 + 51);
        WorldSnapshot before = capture(h.world, area);
        Reply reply = preview(scatter, h.player, request(region(x0, z0, x0 + 47, z0 + 47), 5, 7L, source));
        RecordingListener[] jobs = new RecordingListener[2];
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    scatter.tick();
                    check(reply.finished(), "planning");
                })
                .createAndAdd(() -> {
                    ScatterService.PlanReady plan = reply.get("preview");
                    check(plan.placements() > 10, "only " + plan.placements() + " placements");
                    jobs[0] = commit(h, h.player, plan.planId());
                    EditRejected twice = refusal(() -> h.service.run(h.player, new OpSpec.ScatterCommit(plan.planId()),
                            RunOptions.DEFAULT, null));
                    check(twice.reason() == RejectReason.INVALID, "a plan committed twice: " + twice.reason());
                })
                .createAndAdd(() -> check(jobs[0].result != null, "commit running"))
                .createAndAdd(() -> {
                    check(jobs[0].result.outcome() == JobOutcome.COMPLETED, "commit " + jobs[0].result);
                    String label = h.history().undoLabel();
                    String expected = "Scatter · " + reply.plan.placements() + " placements · ";
                    check(label.startsWith(expected) && label.endsWith(" blocks"), "history label " + label);
                    check(h.history().undoLabels().size() == 1, "one history entry: " + h.history().undoLabels());
                    check(EditTestSupport.difference(before, capture(h.world, area)) != null, "the commit changed nothing");
                    jobs[1] = new RecordingListener();
                    h.undo(jobs[1]);
                })
                .createAndAdd(() -> check(jobs[1].result != null, "undo running"))
                .createAndAdd(() -> {
                    check(jobs[1].result.outcome() == JobOutcome.COMPLETED && jobs[1].result.skippedConflicts() == 0,
                            "undo " + jobs[1].result);
                    checkSame(before, capture(h.world, area), "after undo");
                    scatter.shutdown();
                    forceChunks(h.world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Only the owner commits a plan; previews and commits need {@code scatter}; variants follow the paste rule (own
     * clipboard or a cached asset the player may read), and an asset's rotations restrict its turns.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_ownership", tickLimit = LIMIT)
    public void scatterRefusesUnownedPlan(TestContext context) {
        Harness h = new Harness(context);
        ServerScatter scatter = new ServerScatter(h.service);
        ServerPlayerEntity other = h.addPlayer();
        ServerPlayerEntity noScatter = h.addPlayer(false);
        EditTestSupport.grant(noScatter, Perm.USE, Perm.REGION, Perm.CLIPBOARD);
        int[] at = regionCorner(context, 72);
        int x0 = at[0], z0 = at[1];
        Box all = floor(h, x0, z0, 32, 32);
        ScatterArea area = region(x0, z0, x0 + 31, z0 + 31);
        SourceRef mine = block(h, h.player, "minecraft:gold_block");
        // Another player may not use this clipboard, nor an asset that is not loaded.
        check(refusal(() -> scatter.preview(other, request(area, 4, 1L, mine), new Reply())).reason()
                == RejectReason.INVALID, "another player's clipboard");
        String hash = Sha256.digest(new byte[] {42}).hex();
        check(refusal(() -> scatter.preview(h.player, request(area, 4, 1L, new SourceRef.Asset(hash)), new Reply()))
                .reason() == RejectReason.ASSET_NOT_LOADED, "an asset not loaded");
        SourceRef theirs = block(h, noScatter, "minecraft:diamond_block");
        check(refusal(() -> scatter.preview(noScatter, request(area, 4, 1L, theirs), new Reply())).reason()
                == RejectReason.NO_PERMISSION, "a preview without scatter");
        // A library asset limited to unrotated placements (loaded as a preview would load it).
        Clipboard slab = slab(h, "minecraft:emerald_block");
        h.service.assets().put(new AssetCache.Asset(hash, libraryFile("rocks/flat.schem"), slab,
                new AssetInfo(List.of(), new BlockPos(1, 0, 1), List.of(0), 1)));
        Reply reply = preview(scatter, h.player, request(area, 5, 1L, new SourceRef.Asset(hash)));
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    scatter.tick();
                    check(reply.finished(), "planning");
                })
                .createAndAdd(() -> {
                    ScatterService.PlanReady plan = reply.get("preview");
                    check(plan.placements() > 5, "only " + plan.placements() + " placements");
                    for (ScatterPlan.Placement p : heldPlan(h, h.player).placements()) {
                        check(p.transform().quarterTurnsCw() == 0, "a placement turned against the asset's rotations");
                    }
                    check(refusal(() -> h.service.run(other, new OpSpec.ScatterCommit(plan.planId()), RunOptions.DEFAULT,
                            null)).reason() == RejectReason.INVALID, "another player committed the plan");
                    check(refusal(() -> h.service.run(noScatter, new OpSpec.ScatterCommit(plan.planId()),
                            RunOptions.DEFAULT, null)).reason() == RejectReason.NO_PERMISSION, "a commit without scatter");
                    check(refusal(() -> h.service.run(h.player, new OpSpec.ScatterCommit(UUID.randomUUID()),
                            RunOptions.DEFAULT, null)).reason() == RejectReason.INVALID, "an unknown plan");
                    check(h.service.scatterPlans().get(h.player.getUuid()).isPresent(), "a refusal dropped the plan");
                    check(!h.service.executor().isLocked(h.world, all), "a refusal left a job or hold behind");
                    scatter.shutdown();
                    forceChunks(h.world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Protection at preview and at commit. While the preview plans, the world border cuts the area at x0 + 16 (nobody
     * may modify the columns east of it): the planner skips every placement whose footprint reaches them and counts
     * it as PROTECTED. Before the commit the border moves in to x0 + 8, and stays there while the commit runs: the
     * commit writes nothing east of it, and a placement reaching past it is skipped whole (reported as skipped, no
     * half slab) rather than cut. The border is restored when the job ends.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_protect", tickLimit = LIMIT)
    public void scatterRespectsProtection(TestContext context) {
        Harness h = new Harness(context);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 73);
        int x0 = at[0], z0 = at[1];
        Box all = floor(h, x0, z0, 32, 16);
        Clipboard slab = slab(h, "minecraft:gold_block");
        SourceRef source = new SourceRef.Clipboard(h.service.clipboards().install(h.player.getUuid(), slab).id());
        Reply reply;
        WorldBorder border = h.world.getWorldBorder();
        double centerX = border.getCenterX(), centerZ = border.getCenterZ(), size = border.getSize();
        Runnable restore = () -> {
            border.setCenter(centerX, centerZ);
            border.setSize(size);
        };
        try {
            border.setCenter(x0 + 16 - 100_000, z0);
            border.setSize(200_000);
            check(!h.world.canPlayerModifyAt(h.player, pos(x0 + 16, FLOOR_Y + 1, z0 + 5))
                    && h.world.canPlayerModifyAt(h.player, pos(x0 + 15, FLOOR_Y + 1, z0 + 5)), "the border is not at x0 + 16");
            reply = preview(scatter, h.player, request(region(x0, z0, x0 + 31, z0 + 15), 4, 3L, source));
            planNow(scatter, reply);
        } finally {
            restore.run();
        }
        ScatterService.PlanReady plan = reply.get("preview");
        Integer protectedCount = plan.rejectedCounts().get("PROTECTED");
        check(protectedCount != null && protectedCount > 0, "nothing counted as protected: " + plan.rejectedCounts());
        check(plan.placements() > 3, "only " + plan.placements() + " placements");
        List<ScatterPlan.Placement> placements = heldPlan(h, h.player).placements();
        for (ScatterPlan.Placement p : placements) {
            check(p.anchor().x() + 1 < x0 + 16, "a footprint reaches the protected columns: " + p.anchor());
        }
        check(plan.bounds().max().x() < x0 + 16, "bounds " + plan.bounds());
        long crossing = placements.stream().filter(p -> p.anchor().x() + 1 >= x0 + 8).count();
        check(crossing > 0, "no placement reaches past x0 + 8");

        RecordingListener job = new RecordingListener();
        job.onFinished = restore;
        border.setCenter(x0 + 8 - 100_000, z0);
        border.setSize(200_000);
        try {
            h.service.run(h.player, new OpSpec.ScatterCommit(plan.planId()), RunOptions.DEFAULT, job);
        } catch (EditRejected | RuntimeException e) {
            restore.run();
            throw new GameTestException("commit refused: " + e.getMessage());
        }
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(job.result != null, "commit running"))
                .createAndAdd(() -> {
                    check(job.result.outcome() == JobOutcome.COMPLETED, "commit " + job.result);
                    check(job.result.skippedConflicts() == crossing && job.result.skippedProtected() == 0,
                            "commit " + job.result + ", " + crossing + " placements reach past the border");
                    check(job.result.changed() == 9L * (plan.placements() - crossing), "commit " + job.result);
                    check(h.events.scatterSkips.equals(List.of(crossing)), "reported " + h.events.scatterSkips);
                    Box east = box(x0 + 8, FLOOR_Y + 1, z0, x0 + 31, FLOOR_Y + 1, z0 + 15);
                    check(find(h.world, east, FLOOR_Y + 1, Blocks.GOLD_BLOCK).isEmpty(), "gold past the commit-time border");
                    // Slabs anchored on the area's edge reach one column past it.
                    Box west = box(x0 - 1, FLOOR_Y + 1, z0 - 1, x0 + 7, FLOOR_Y + 1, z0 + 16);
                    check(find(h.world, west, FLOOR_Y + 1, Blocks.GOLD_BLOCK).size() == 9 * (plan.placements() - crossing),
                            "a placement reaching past the border was half built");
                    scatter.shutdown();
                    forceChunks(h.world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * An area reaching far into chunks nobody loaded: those columns end as UNLOADED, and planning leaves the chunks
     * unloaded.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_unloaded", tickLimit = LIMIT)
    public void scatterPreviewDoesNotLoadChunks(TestContext context) {
        Harness h = new Harness(context);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 74);
        int x0 = at[0], z0 = at[1];
        int cx0 = x0 >> 4, cz0 = z0 >> 4;
        Box loaded = box(x0, FLOOR_Y, z0, x0 + 15, FLOOR_Y, z0 + 15);
        loadAndForce(h.world, loaded);
        BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
        for (int x = x0; x < x0 + 16; x++) {
            for (int z = z0; z < z0 + 16; z++) writer.write(x, FLOOR_Y, z, h.state("minecraft:stone"), null);
        }
        // Chunks three and more to the east are beyond the forced chunk's neighbourhood.
        for (int cx = cx0 + 3; cx <= cx0 + 7; cx++) {
            check(!WorldChecks.isChunkLoaded(h.world, cx, cz0), "chunk " + cx + "," + cz0 + " is already loaded");
        }
        SourceRef gold = block(h, h.player, "minecraft:gold_block");
        Reply reply = preview(scatter, h.player, request(region(x0, z0, x0 + 127, z0 + 15), 3, 5L, gold));
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    scatter.tick();
                    check(reply.finished(), "planning");
                })
                .createAndAdd(() -> {
                    ScatterService.PlanReady plan = reply.get("preview");
                    Integer unloaded = plan.rejectedCounts().get("UNLOADED");
                    check(unloaded != null && unloaded >= 5 * 256, "UNLOADED " + plan.rejectedCounts());
                    check(plan.placements() > 5, "only " + plan.placements() + " placements");
                    for (ScatterPlan.Placement p : heldPlan(h, h.player).placements()) {
                        check(p.anchor().x() >> 4 == cx0, "a placement outside the floor's chunk: " + p.anchor());
                    }
                    for (int cx = cx0 + 3; cx <= cx0 + 7; cx++) {
                        check(!WorldChecks.isChunkLoaded(h.world, cx, cz0), "planning loaded chunk " + cx + "," + cz0);
                    }
                    scatter.shutdown();
                    forceChunks(h.world, loaded, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** A plan may be committed for ten minutes (service clock), then it is gone. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_expiry", tickLimit = LIMIT)
    public void scatterPlanExpires(TestContext context) {
        AtomicLong clock = new AtomicLong(System.nanoTime());
        Harness h = new Harness(context, null, clock::get);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 75);
        int x0 = at[0], z0 = at[1];
        Box all = floor(h, x0, z0, 16, 16);
        SourceRef gold = block(h, h.player, "minecraft:gold_block");
        Reply reply = preview(scatter, h.player, request(region(x0, z0, x0 + 15, z0 + 15), 4, 9L, gold));
        planNow(scatter, reply);
        ScatterService.PlanReady plan = reply.get("preview");
        String world = h.world.getRegistryKey().getValue().toString();
        clock.addAndGet(ScatterPlans.TTL_NANOS - 1_000_000_000L);
        check(h.service.scatterPlans().find(h.player.getUuid(), plan.planId(), world, clock.get()).isPresent(),
                "the plan expired early");
        check(h.service.scatterPlans().find(h.player.getUuid(), plan.planId(), "minecraft:the_nether", clock.get())
                .isEmpty(), "a plan resolved in another world");
        // Looking it up from another world dropped it: plan again.
        Reply again = preview(scatter, h.player, request(region(x0, z0, x0 + 15, z0 + 15), 4, 9L, gold));
        planNow(scatter, again);
        ScatterService.PlanReady fresh = again.get("second preview");
        clock.addAndGet(ScatterPlans.TTL_NANOS);
        EditRejected expired = refusal(() -> h.service.run(h.player, new OpSpec.ScatterCommit(fresh.planId()),
                RunOptions.DEFAULT, null));
        check(expired.reason() == RejectReason.INVALID, "an expired plan: " + expired.reason());
        check(h.service.scatterPlans().get(h.player.getUuid()).isEmpty(), "the expired plan is still held");
        check(find(h.world, all, FLOOR_Y + 1, Blocks.GOLD_BLOCK).isEmpty(), "an expired plan wrote blocks");
        scatter.shutdown();
        forceChunks(h.world, all, false);
        h.close();
        context.complete();
    }

    /**
     * Water covers the west half: by default nothing is placed in it (COLLISION), and the committed blocks leave the
     * water alone; with {@code allowInFluid} placements go into it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_fluid", tickLimit = LIMIT)
    public void scatterFluidDefault(TestContext context) {
        Harness h = new Harness(context);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 76);
        int x0 = at[0], z0 = at[1];
        Box all = floor(h, x0, z0, 32, 32);
        BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
        int water = h.state("minecraft:water[level=0]");
        for (int x = x0; x < x0 + 16; x++) {
            for (int z = z0; z < z0 + 32; z++) writer.write(x, FLOOR_Y + 1, z, water, null);
        }
        SourceRef gold = block(h, h.player, "minecraft:gold_block");
        ScatterArea area = region(x0, z0, x0 + 31, z0 + 31);
        Reply dry = preview(scatter, h.player, request(area, 3, 11L, gold));
        planNow(scatter, dry);
        ScatterService.PlanReady plan = dry.get("default preview");
        check(plan.placements() > 10, "only " + plan.placements() + " placements");
        Integer collisions = plan.rejectedCounts().get("COLLISION");
        check(collisions != null && collisions > 10, "water columns not refused: " + plan.rejectedCounts());
        for (ScatterPlan.Placement p : heldPlan(h, h.player).placements()) {
            check(p.anchor().x() >= x0 + 16, "a placement in the water: " + p.anchor());
        }
        RecordingListener job = commit(h, h.player, plan.planId());
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(job.result != null, "commit running"))
                .createAndAdd(() -> {
                    check(job.result.outcome() == JobOutcome.COMPLETED, "commit " + job.result);
                    for (int x = x0; x < x0 + 16; x++) {
                        for (int z = z0; z < z0 + 32; z++) {
                            check(h.world.getBlockState(pos(x, FLOOR_Y + 1, z)).isOf(Blocks.WATER),
                                    "the water changed at " + x + "," + z);
                        }
                    }
                    Reply wet = preview(scatter, h.player, request(1, area, 3, new ScatterSettings.Density.Fraction(1),
                            ScatterSettings.Fit.DEFAULT.withAllowInFluid(true), 11L, ScatterSettings.Transforms.ALL, gold));
                    planNow(scatter, wet);
                    wet.get("aquatic preview");
                    long inWater = heldPlan(h, h.player).placements().stream().filter(p -> p.anchor().x() < x0 + 16).count();
                    check(inWater > 5, "allowInFluid placed " + inWater + " in the water");
                    scatter.shutdown();
                    forceChunks(h.world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * End to end over the wire: a preview frame through the dispatcher gets {@code ScatterPlan}, then a
     * {@code SCATTER_PLACEMENTS} stream whose placements equal the plan; a preview sent right after another
     * supersedes it; the commit runs as a {@code RunOp}.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_stream", tickLimit = LIMIT)
    public void scatterPlacementsStream(TestContext context) {
        Harness h = new Harness(context);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 77);
        int x0 = at[0], z0 = at[1];
        Box all = floor(h, x0, z0, 48, 48);
        SourceRef gold = block(h, h.player, "minecraft:gold_block");
        Clipboard slab = slab(h, "minecraft:emerald_block");
        String hash = Sha256.digest(new byte[] {7}).hex();
        h.service.assets().put(new AssetCache.Asset(hash, libraryFile("rocks/slab.schem"), slab));
        ServerDispatcher<ServerPlayerEntity> dispatcher = new ServerDispatcher<>(h.service, ClipboardService.disabled(), scatter,
                h.runtime.permissions(), () -> Limits.DEFAULTS, h.runtime::states, System::nanoTime);
        Transport transport = new Transport(h);
        NetSession<ServerPlayerEntity> session = dispatcher.open(transport);
        ScatterArea area = new ScatterArea.Stamps(List.of(ScatterArea.Stamp.paint(x0 + 20, z0 + 20, 18),
                ScatterArea.Stamp.erase(x0 + 20, z0 + 20, 4), ScatterArea.Stamp.paint(x0 + 38, z0 + 38, 8)));
        transport.receive(dispatcher, session, Handshake.hello("test", Features.of(Features.SCATTER)));
        check(transport.first(S2C.Welcome.class).features().has(Features.SCATTER), "scatter is not offered");
        transport.receive(dispatcher, session, request(1, area, 3, new ScatterSettings.Density.Fraction(0.8),
                ScatterSettings.Fit.DEFAULT, 5L, ScatterSettings.Transforms.ALL, gold));
        int firstTick = h.world.getServer().getTicks();
        // A second preview in the same tick is refused; on a later tick it replaces the first.
        transport.receive(dispatcher, session, request(9, area, 3, new ScatterSettings.Density.Fraction(0.8),
                ScatterSettings.Fit.DEFAULT, 5L, ScatterSettings.Transforms.ALL, gold));
        check(transport.sent.contains(new S2C.JobRejected(9, RejectReason.RATE_LIMITED)), "two previews in one tick");
        UUID[] planId = new UUID[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    check(h.world.getServer().getTicks() > firstTick, "waiting for the next tick");
                    transport.receive(dispatcher, session, request(2, area, 3, new ScatterSettings.Density.Fraction(0.8),
                            ScatterSettings.Fit.DEFAULT, 5L, ScatterSettings.Transforms.ALL, gold, new SourceRef.Asset(hash)));
                    check(transport.sent.contains(new S2C.JobRejected(1, RejectReason.QUEUE_FULL)),
                            "the first preview was not superseded: " + transport.sent);
                    check(scatter.active() == 1, scatter.active() + " previews in flight");
                })
                .createAndAdd(() -> {
                    scatter.tick();
                    dispatcher.tick(session);
                    check(!transport.sent(StreamEnd.class).isEmpty(), "planning and streaming");
                })
                .createAndAdd(() -> {
                    S2C.ScatterPlan summary = transport.first(S2C.ScatterPlan.class);
                    ScatterPlan plan = heldPlan(h, h.player);
                    check(summary.reqId() == 2 && summary.placements() == plan.placements().size()
                            && summary.totalCells() == plan.totalCells()
                            && summary.bounds().equals(plan.bounds().orElseThrow()), "summary " + summary);
                    long counted = summary.placements();
                    for (int count : summary.rejectedCounts().values()) counted += count;
                    check(counted == plan.columns(), "summary counts " + counted + " of " + plan.columns() + " columns");
                    check(transport.sent.indexOf(summary) < transport.sent.indexOf(transport.first(StreamOpen.class)),
                            "the stream came before the summary");
                    StreamOpen open = transport.first(StreamOpen.class);
                    check(open.kind() == StreamKind.SCATTER_PLACEMENTS, "stream kind " + open.kind());
                    check(open.meta().get(ScatterPlacements.META_PLAN_ID).equals(summary.planId().toString())
                            && open.meta().get(ScatterPlacements.META_REQ_ID).equals("2"), "stream meta " + open.meta());
                    try {
                        StreamAssembler assembler = new StreamAssembler(open, 64L << 20, 64L << 20);
                        for (StreamChunk chunk : transport.sent(StreamChunk.class)) assembler.accept(chunk);
                        List<ScatterPlan.Placement> received = ScatterPlacements.decode(
                                assembler.finish(transport.first(StreamEnd.class)), 2);
                        check(received.equals(plan.placements()), "the streamed placements differ from the plan");
                    } catch (ProtocolException e) {
                        throw new GameTestException("stream: " + e.getMessage());
                    }
                    check(plan.placements().stream().anyMatch(p -> p.variant() == 1), "no asset placements");
                    planId[0] = summary.planId();
                    transport.receive(dispatcher, session, new C2S.RunOp(3, new OpSpec.ScatterCommit(planId[0]), false,
                            ConflictPolicy.SKIP_CONFLICTS));
                    check(transport.sent(S2C.JobAccepted.class).stream().anyMatch(a -> a.reqId() == 3), "commit not accepted: "
                            + transport.sent);
                })
                .createAndAdd(() -> check(!transport.sent(S2C.JobFinished.class).isEmpty(), "commit running"))
                .createAndAdd(() -> {
                    S2C.JobFinished finished = transport.first(S2C.JobFinished.class);
                    check(finished.outcome() == JobOutcome.COMPLETED && finished.changed() > 0, "finished " + finished);
                    dispatcher.close(session);
                    scatter.shutdown();
                    forceChunks(h.world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** A transport collecting what the dispatcher sends, decoded. */
    private static final class Transport implements FabricTransport {
        final Harness h;
        final List<S2C> sent = new ArrayList<>();

        Transport(Harness h) {
            this.h = h;
        }

        void receive(ServerDispatcher<ServerPlayerEntity> dispatcher, NetSession<ServerPlayerEntity> session, C2S message) {
            try {
                dispatcher.receive(session, Codec.encodeC2S(message, h.runtime.states()));
            } catch (ProtocolException e) {
                throw new GameTestException("encode: " + e.getMessage());
            }
        }

        <T> T first(Class<T> type) {
            List<T> all = sent(type);
            if (all.isEmpty()) throw new GameTestException("no " + type.getSimpleName() + " sent: " + sent);
            return all.get(0);
        }

        <T> List<T> sent(Class<T> type) {
            return sent.stream().filter(type::isInstance).map(type::cast).toList();
        }

        @Override
        public ServerPlayerEntity player() {
            return h.player;
        }

        @Override
        public boolean canSend() {
            return true;
        }

        @Override
        public void send(byte[] frame) {
            try {
                sent.add(Codec.decodeS2C(frame, h.runtime.states()));
            } catch (ProtocolException e) {
                throw new GameTestException("the server sent an undecodable frame: " + e.getMessage());
            }
        }

        @Override
        public void acknowledge(int sequence) {
        }

        @Override
        public boolean tracks(int cx, int cz) {
            return false;
        }

        @Override
        public void resendChunk(int cx, int cz) {
        }

        @Override
        public void disconnect(String reason) {
            throw new GameTestException("disconnected: " + reason);
        }
    }
}
