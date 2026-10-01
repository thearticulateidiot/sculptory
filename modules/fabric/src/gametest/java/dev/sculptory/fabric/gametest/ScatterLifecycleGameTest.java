package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;
import static dev.sculptory.fabric.gametest.ScatterGameTest.FLOOR_Y;
import static dev.sculptory.fabric.gametest.ScatterGameTest.block;
import static dev.sculptory.fabric.gametest.ScatterGameTest.floor;
import static dev.sculptory.fabric.gametest.ScatterGameTest.heldPlan;
import static dev.sculptory.fabric.gametest.ScatterGameTest.planNow;
import static dev.sculptory.fabric.gametest.ScatterGameTest.preview;
import static dev.sculptory.fabric.gametest.ScatterGameTest.refusal;
import static dev.sculptory.fabric.gametest.ScatterGameTest.region;
import static dev.sculptory.fabric.gametest.ScatterGameTest.request;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.clipboard.Clipboard;

import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.scatter.ScatterArea;
import dev.sculptory.core.scatter.ScatterPlan;
import dev.sculptory.core.scatter.ScatterPlanner;
import dev.sculptory.core.scatter.ScatterSettings;
import dev.sculptory.fabric.engine.DabOutcome;
import dev.sculptory.fabric.engine.EditRejected;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.fabric.engine.RunOptions;

import dev.sculptory.fabric.engine.impl.AssetCache;
import dev.sculptory.fabric.engine.impl.EditExecutor;
import dev.sculptory.fabric.engine.impl.ServerScatter;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.gametest.ScatterGameTest.Reply;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.protocol.v2.RejectReason;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.world.World;

/**
 * Scatter failure paths and fairness (M3-B review): every way a preview ends releases its area hold; holds wait for
 * earlier jobs and hold off later ones and dabs; caps and the queue refuse up front; the stepped plan equals a
 * synchronous one; a commit never overwrites what was built since the preview.
 */
public final class ScatterLifecycleGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    private static C2S.ScatterPreview small(int x0, int z0, SourceRef source) {
        return request(region(x0, z0, x0 + 31, z0 + 31), 4, 1L, source);
    }

    /** No hold left behind, nothing in flight, and (no job being there) the area unlocked. */
    private static void checkReleased(Harness h, ServerScatter scatter, int holds, Box area, String what) {
        checkReleased(h, scatter, holds, what);
        check(!h.service.executor().isLocked(h.world, area), what + ": the area is still locked");
    }

    /** No hold left behind and nothing in flight (jobs may lock the area). */
    private static void checkReleased(Harness h, ServerScatter scatter, int holds, String what) {
        check(h.service.executor().holdCount() == holds, what + ": " + h.service.executor().holdCount() + " holds, expected "
                + holds);
        check(scatter.active() == 0, what + ": " + scatter.active() + " previews in flight");
    }

    /**
     * Completion, a planner failure, the player leaving, the player gone without notice, a world change, server
     * shutdown and a superseded preview each release the area; only completion and failures answer.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_endings", tickLimit = LIMIT)
    public void scatterEndingsReleaseTheArea(TestContext context) {
        Harness h = new Harness(context);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 80);
        int x0 = at[0], z0 = at[1];
        Box all = floor(h, x0, z0, 32, 32);
        Box area = box(x0, FLOOR_Y, z0, x0 + 31, FLOOR_Y + 5, z0 + 31);
        int holds = h.service.executor().holdCount();
        SourceRef gold = block(h, h.player, "minecraft:gold_block");

        Reply done = preview(scatter, h.player, small(x0, z0, gold));
        check(h.service.executor().holdCount() == holds + 1 && h.service.executor().isLocked(h.world, area), "not held");
        planNow(scatter, done);
        done.get("completed preview");
        checkReleased(h, scatter, holds, area, "completion");

        Reply failing = preview(scatter, h.player, small(x0, z0, gold));
        scatter.observeSteps(() -> {
            throw new IllegalStateException("planner failure (test)");
        });
        scatter.tick();
        scatter.observeSteps(null);
        check(failing.reason == RejectReason.INVALID && failing.calls == 1, "failure answered " + failing.reason);
        checkReleased(h, scatter, holds, area, "planner failure");

        Reply left = preview(scatter, h.player, small(x0, z0, gold));
        scatter.playerLeft(h.player.getUuid());
        check(!left.finished(), "a player who left was answered");
        checkReleased(h, scatter, holds, area, "player left");

        ServerPlayerEntity ghost = h.addPlayer();
        SourceRef ghostGold = block(h, ghost, "minecraft:gold_block");
        Reply gone = preview(scatter, ghost, small(x0, z0, ghostGold));
        h.world.getServer().getPlayerManager().remove(ghost);
        scatter.tick();
        check(!gone.finished(), "a player no longer on the server was answered");
        checkReleased(h, scatter, holds, area, "player gone");

        Reply stopped = preview(scatter, h.player, small(x0, z0, gold));
        scatter.shutdown();
        check(!stopped.finished(), "a stopped preview was answered");
        checkReleased(h, scatter, holds, area, "shutdown");

        ServerPlayerEntity traveller = h.addPlayer();
        SourceRef travellerGold = block(h, traveller, "minecraft:gold_block");
        Reply moved = preview(scatter, traveller, small(x0, z0, travellerGold));
        ServerWorld nether = h.world.getServer().getWorld(World.NETHER);
        check(nether != null && traveller.teleport(nether, 0.5, 100, 0.5, Set.of(), 0, 0), "teleport failed");
        scatter.tick();
        check(moved.reason == RejectReason.INVALID, "world change answered " + moved.reason + " " + moved.detail);
        checkReleased(h, scatter, holds, area, "world change");

        Reply first = preview(scatter, h.player, small(x0, z0, gold));
        int tick = h.world.getServer().getTicks();
        check(refusal(() -> scatter.preview(h.player, small(x0, z0, gold), new Reply())).reason()
                == RejectReason.RATE_LIMITED, "two previews in one tick");
        Reply[] second = new Reply[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    check(h.world.getServer().getTicks() > tick, "waiting for the next tick");
                    second[0] = preview(scatter, h.player, small(x0, z0, gold));
                    check(first.superseded && first.calls == 1, "not superseded");
                    check(h.service.executor().holdCount() == holds + 1, "the superseded hold was kept");
                    planNow(scatter, second[0]);
                    second[0].get("replacing preview");
                    checkReleased(h, scatter, holds, area, "supersede");
                    forceChunks(h.world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A4: planning time counts only the preview's own steps. A preview that waits long between its steps (a server
     * busy with other work) is still planned, whatever the wall-clock time; one given more than
     * {@code scatter.maxPlanningMillis} of steps is refused TOO_LARGE, releases the area and starts a 10 s cooldown
     * (RATE_LIMITED) that leaving and rejoining does not clear; one still unfinished after the wall-clock limit is
     * refused RATE_LIMITED without a cooldown. A3: holding uses the player's hold budget, and an overdrawn budget
     * refuses the next preview (RATE_LIMITED) until it has refilled.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_deadline", tickLimit = LIMIT)
    public void scatterDeadlineAndCooldown(TestContext context) {
        AtomicLong clock = new AtomicLong(System.nanoTime());
        Harness h = new Harness(context, null, clock::get);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 81);
        int x0 = at[0], z0 = at[1];
        Box all = floor(h, x0, z0, 32, 32);
        Box area = box(x0, FLOOR_Y, z0, x0 + 31, FLOOR_Y + 5, z0 + 31);
        int holds = h.service.executor().holdCount();
        SourceRef gold = block(h, h.player, "minecraft:gold_block");
        java.util.UUID id = h.player.getUuid();
        long maxPlanning = h.runtime.config().scatter.maxPlanningMillis * 1_000_000L;
        double refill = h.runtime.config().scatter.holdRefillShare;

        // A busy server: 20 s between the first single steps, past what a wall-clock minute used to allow (and within
        // the wall-clock limit). The 32 × 32 area takes four survey tiles, then acceptance.
        Reply patient = preview(scatter, h.player, small(x0, z0, gold));
        int steps = 0;
        long waited = 0;
        while (!patient.finished() && steps < 10_000) {
            scatter.runLane(System.nanoTime()); // no time left: exactly the guaranteed step
            if (steps < 4) {
                clock.addAndGet(20_000_000_000L);
                waited += 20;
            }
            steps++;
        }
        patient.get("a preview planned slowly");
        check(steps > 4 && waited > 60 && waited * 1_000_000_000L < ServerScatter.PLANNING_WALL_LIMIT_NANOS,
                "planned in " + steps + " steps over " + waited + " s");
        checkReleased(h, scatter, holds, area, "slow planning");

        // That held the area for over a minute, but nobody else wanted it: the hold budget is untouched and an
        // immediate re-roll goes out.
        long burst = (long) (h.runtime.config().scatter.holdBudgetSeconds * 1e9);
        check(scatter.holdLeftNanos(id) == burst, "a solo preview was charged: " + scatter.holdLeftNanos(id));
        Reply reroll = preview(scatter, h.player, small(x0, z0, gold));
        planNow(scatter, reroll);
        reroll.get("an immediate re-roll after a long solo preview");
        checkReleased(h, scatter, holds, area, "re-roll");

        // More planning time than allowed: TOO_LARGE, then a cooldown that rejoining does not clear.
        Reply slow = preview(scatter, h.player, small(x0, z0, gold));
        scatter.observeSteps(() -> clock.addAndGet(maxPlanning + 1));
        planNow(scatter, slow);
        scatter.observeSteps(null);
        check(slow.reason == RejectReason.TOO_LARGE, "deadline answered " + slow.reason + " " + slow.detail);
        checkReleased(h, scatter, holds, area, "deadline");
        check(scatter.cooldownNanos(id) > 0, "no cooldown after the deadline");
        check(refusal(() -> scatter.preview(h.player, small(x0, z0, gold), new Reply())).reason()
                == RejectReason.RATE_LIMITED, "no cooldown after the deadline");
        scatter.playerLeft(id);
        check(refusal(() -> scatter.preview(h.player, small(x0, z0, gold), new Reply())).reason()
                == RejectReason.RATE_LIMITED, "rejoining cleared the cooldown");
        long waitBudget = (long) Math.ceil((1_000_000_000L - Math.min(0, scatter.holdLeftNanos(id))) / refill);
        clock.addAndGet(Math.max(ServerScatter.DEADLINE_COOLDOWN_NANOS, waitBudget));
        Reply again = preview(scatter, h.player, small(x0, z0, gold));
        planNow(scatter, again);
        again.get("a preview after the cooldown");
        checkReleased(h, scatter, holds, area, "after the cooldown");

        // Never given enough time within the wall-clock limit: RATE_LIMITED, without a cooldown.
        Reply stuck = preview(scatter, h.player, small(x0, z0, gold));
        scatter.runLane(System.nanoTime());
        check(!stuck.finished(), "planned in one step");
        clock.addAndGet(ServerScatter.PLANNING_WALL_LIMIT_NANOS);
        scatter.runLane(System.nanoTime());
        check(stuck.reason == RejectReason.RATE_LIMITED, "wall-clock limit answered " + stuck.reason + " " + stuck.detail);
        check(scatter.cooldownNanos(id) == 0, "the wall-clock limit started a cooldown");
        checkReleased(h, scatter, holds, area, "wall-clock limit");
        forceChunks(h.world, all, false);
        h.close();
        context.complete();
    }

    /**
     * A hold waits for the jobs admitted before it (and gives up with AREA_BUSY after a while); jobs admitted after it
     * stay queued and dabs in the area are refused AREA_BUSY until it is released.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_holds", tickLimit = LIMIT)
    public void scatterHoldsWaitAndHoldOff(TestContext context) {
        AtomicLong clock = new AtomicLong(System.nanoTime());
        Harness h = new Harness(context, null, clock::get);
        ServerScatter scatter = new ServerScatter(h.service);
        EditExecutor executor = h.service.executor();
        int[] at = regionCorner(context, 82);
        int x0 = at[0], z0 = at[1];
        Box all = floor(h, x0, z0, 32, 32);
        Box area = box(x0, FLOOR_Y, z0, x0 + 31, FLOOR_Y + 5, z0 + 31);
        Box air = box(x0, FLOOR_Y + 6, z0, x0 + 31, FLOOR_Y + 9, z0 + 31);
        int holds = executor.holdCount();
        SourceRef gold = block(h, h.player, "minecraft:gold_block");

        // A job admitted first: the preview waits for it.
        RecordingListener earlier = new RecordingListener();
        h.fill(air, "minecraft:air", earlier);
        Reply waiting = preview(scatter, h.player, small(x0, z0, gold));
        scatter.tick();
        check(!waiting.finished() && scatter.planning(h.player.getUuid()), "planned while an earlier job holds the area");

        Reply[] timedOut = new Reply[1];
        RecordingListener[] blocked = new RecordingListener[2];
        long[] queuedAt = new long[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(earlier.result != null, "earlier job running"))
                .createAndAdd(() -> {
                    planNow(scatter, waiting);
                    waiting.get("preview after the earlier job");
                    checkReleased(h, scatter, holds, area, "after waiting");

                    // A preview first: later jobs queue and dabs are refused.
                    Reply holding = preview(scatter, h.player, small(x0, z0, gold));
                    blocked[0] = new RecordingListener();
                    h.fill(air, "minecraft:air", blocked[0]);
                    try {
                        h.service.beginStroke(h.player, 7, new BrushSpec(BrushTool.RAISE, 3, 1f, Falloff.SMOOTH,
                                Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L));
                    } catch (EditRejected e) {
                        throw new GameTestException("stroke refused: " + e.getMessage());
                    }
                    DabOutcome dab = h.service.dabs(h.player, 7, 0, List.of(EditTestSupport.dab(0, x0 + 10, FLOOR_Y, z0 + 10)));
                    check(!dab.accepted() && dab.reason() == RejectReason.AREA_BUSY, "dab during planning: " + dab);
                    h.service.endStroke(h.player, 7);
                    check(executor.holdCount() == holds + 1 && scatter.active() == 1 && !holding.finished(),
                            "after the fill: holds " + executor.holdCount() + ", planning " + scatter.active());
                    queuedAt[0] = h.world.getServer().getTicks();
                    timedOut[0] = holding;
                })
                .createAndAdd(() -> {
                    check(h.world.getServer().getTicks() >= queuedAt[0] + 5, "letting the executor tick");
                    check(blocked[0].result == null && blocked[0].phases.contains(Phase.QUEUED)
                            && !blocked[0].phases.contains(Phase.APPLY), "a job ran inside a held area: " + blocked[0].phases
                            + "; holds " + executor.holdCount() + ", planning " + scatter.active() + ", reply "
                            + timedOut[0].calls + " " + timedOut[0].reason + " " + timedOut[0].detail + " superseded "
                            + timedOut[0].superseded + " planned " + (timedOut[0].plan != null) + ", locked "
                            + executor.isLocked(h.world, air) + ", ticks " + h.world.getServer().getTicks() + " vs "
                            + queuedAt[0]);
                    planNow(scatter, timedOut[0]);
                    timedOut[0].get("holding preview");
                    checkReleased(h, scatter, holds, "after holding");
                })
                .createAndAdd(() -> check(blocked[0].result != null, "the held-off job runs once released"))
                .createAndAdd(() -> {
                    check(blocked[0].result.outcome() == JobOutcome.COMPLETED, "held-off job " + blocked[0].result);
                    // A preview still waiting for an earlier job after a while gives up.
                    blocked[1] = new RecordingListener();
                    h.fill(air, "minecraft:air", blocked[1]);
                    Reply busy = preview(scatter, h.player, small(x0, z0, gold));
                    clock.addAndGet(ServerScatter.HOLD_WAIT_NANOS);
                    scatter.tick();
                    check(busy.reason == RejectReason.AREA_BUSY, "long wait answered " + busy.reason);
                    checkReleased(h, scatter, holds, "given up");
                })
                .createAndAdd(() -> {
                    check(blocked[1].result != null, "the earlier job still runs");
                    forceChunks(h.world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** The queue cap, the per-tick limit and the TOO_LARGE caps refuse up front and hold nothing. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_caps", tickLimit = LIMIT)
    public void scatterCapsAndQueue(TestContext context) {
        Harness h = new Harness(context);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 83);
        int x0 = at[0], z0 = at[1];
        Box all = floor(h, x0, z0, 32, 32);
        int holds = h.service.executor().holdCount();

        List<Reply> replies = new ArrayList<>();
        for (int i = 0; i < ServerScatter.MAX_ACTIVE; i++) {
            ServerPlayerEntity p = h.addPlayer();
            replies.add(preview(scatter, p, small(x0, z0, block(h, p, "minecraft:gold_block"))));
        }
        ServerPlayerEntity ninth = h.addPlayer();
        SourceRef ninthGold = block(h, ninth, "minecraft:gold_block");
        check(refusal(() -> scatter.preview(ninth, small(x0, z0, ninthGold), new Reply())).reason()
                == RejectReason.QUEUE_FULL, "a ninth preview");
        check(h.service.executor().holdCount() == holds + ServerScatter.MAX_ACTIVE, "holds " + h.service.executor().holdCount());
        scatter.shutdown();
        check(h.service.executor().holdCount() == holds, "shutdown kept holds");

        SourceRef gold = block(h, h.player, "minecraft:gold_block");
        List<ScatterArea.Stamp> stamps = new ArrayList<>();
        for (int i = 0; i <= ServerScatter.MAX_STAMPS; i++) stamps.add(ScatterArea.Stamp.paint(x0 + i % 32, z0, 1));
        tooLarge(scatter, h.player, request(new ScatterArea.Stamps(stamps), 2, 1L, gold), "513 stamps");

        Clipboard hollow = Clipboard.builder(h.runtime.states(), new BlockPos(65, 65, 65))
                .set(0, 0, 0, h.state("minecraft:stone")).build();
        SourceRef big = new SourceRef.Clipboard(h.service.clipboards().install(h.player.getUuid(), hollow).id());
        tooLarge(scatter, h.player, small(x0, z0, big), "a variant over the source volume");

        List<SourceRef> assets = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            Clipboard cube = Clipboard.builder(h.runtime.states(), new BlockPos(64, 64, 64))
                    .set(i, 0, 0, h.state("minecraft:stone")).build();
            String hash = Sha256.digest(new byte[] {(byte) i, 9}).hex();
            h.service.assets().put(new AssetCache.Asset(hash, ScatterGameTest.libraryFile("caps/cube" + i + ".schem"), cube));
            assets.add(new SourceRef.Asset(hash));
        }
        tooLarge(scatter, h.player, request(region(x0, z0, x0 + 31, z0 + 31), 4, 1L, assets.toArray(SourceRef[]::new)),
                "variants over the total source volume");

        Clipboard.Builder dense = Clipboard.builder(h.runtime.states(), new BlockPos(10, 10, 10));
        for (int i = 0; i < 1000; i++) dense.set(i % 10, (i / 10) % 10, i / 100, h.state("minecraft:stone"));
        SourceRef thousand = new SourceRef.Clipboard(h.service.clipboards().install(h.player.getUuid(), dense.build()).id());
        tooLarge(scatter, h.player, request(region(x0, z0, x0 + 999, z0 + 999), 4, 1L, thousand), "the work estimate");
        tooLarge(scatter, h.player, request(1, region(x0, z0, x0 + 999, z0 + 999), 4,
                new ScatterSettings.Density.Count(1), ScatterSettings.Fit.DEFAULT, 1L, ScatterSettings.Transforms.ALL,
                thousand), "a target count counts every column");
        SourceRef one = block(h, h.player, "minecraft:gold_block");
        tooLarge(scatter, h.player, request(1, region(x0, z0, x0 + 1023, z0 + 1023), 4,
                new ScatterSettings.Density.Fraction(0), ScatterSettings.Fit.DEFAULT, 1L, ScatterSettings.Transforms.ALL,
                one), "the held area");
        check(h.service.executor().holdCount() == holds && scatter.active() == 0, "a refusal held something");
        forceChunks(h.world, all, false);
        h.close();
        context.complete();
    }

    /**
     * A3: the hold budget pays only for time a hold blocks someone else. Another player's fill queued behind the
     * preview charges from then until the release (40 s here: the budget is overdrawn and the next preview refused
     * RATE_LIMITED); another player's dab refused AREA_BUSY charges too; the owner's own dab refused by their own hold
     * does not. A dab refused by a job the hold keeps waiting charges the hold; one refused by a job that runs whatever
     * the holds does not.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_budget", tickLimit = LIMIT)
    public void scatterHoldBudgetChargesOnlyBlockingTime(TestContext context) {
        AtomicLong clock = new AtomicLong(System.nanoTime());
        Harness h = new Harness(context, null, clock::get);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 91);
        int x0 = at[0], z0 = at[1];
        Box all = floor(h, x0, z0, 64, 32);
        int holds = h.service.executor().holdCount();
        ServerPlayerEntity other = h.addPlayer();
        SourceRef gold = block(h, h.player, "minecraft:gold_block");
        java.util.UUID id = h.player.getUuid();
        long second = 1_000_000_000L;
        long burst = (long) (h.runtime.config().scatter.holdBudgetSeconds * 1e9);
        double refill = h.runtime.config().scatter.holdRefillShare;
        Box glass = box(x0, FLOOR_Y + 8, z0, x0 + 3, FLOOR_Y + 8, z0 + 3);

        Reply first = preview(scatter, h.player, small(x0, z0, gold));
        RecordingListener theirs = new RecordingListener();
        h.fill(other, glass, "minecraft:glass", theirs);
        clock.addAndGet(40 * second);
        planNow(scatter, first);
        first.get("a preview another player waited behind");
        long left = scatter.holdLeftNanos(id);
        check(left == Math.max(-burst, burst - 40 * second), "charged for 40 s of blocking: " + left);
        check(refusal(() -> scatter.preview(h.player, small(x0, z0, gold), new Reply())).reason()
                == RejectReason.RATE_LIMITED, "an overdrawn budget");
        checkReleased(h, scatter, holds, "after the blocked fill");

        long[] leftAfterDab = new long[1];
        RecordingListener ownFill = new RecordingListener();
        RecordingListener earlierFill = new RecordingListener();
        Box glassBox = box(x0 + 8, FLOOR_Y + 5, z0 + 8, x0 + 12, FLOOR_Y + 5, z0 + 12);
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(theirs.result != null, "the other player's fill runs once released"))
                .createAndAdd(() -> {
                    check(theirs.result.outcome() == JobOutcome.COMPLETED, "their fill " + theirs.result);
                    clock.addAndGet((long) Math.ceil((burst - left) / refill) + second);
                    check(scatter.holdLeftNanos(id) == burst, "not refilled: " + scatter.holdLeftNanos(id));

                    // Another player's dab refused because of the hold: charged from then.
                    Reply dabbed = preview(scatter, h.player, small(x0, z0, gold));
                    DabOutcome dab = dab(h, other, 21, x0 + 10, z0 + 10);
                    check(!dab.accepted() && dab.reason() == RejectReason.AREA_BUSY, "their dab: " + dab);
                    clock.addAndGet(10 * second);
                    planNow(scatter, dabbed);
                    dabbed.get("a preview that refused another player's dab");
                    leftAfterDab[0] = scatter.holdLeftNanos(id);
                    check(leftAfterDab[0] == burst - 10 * second, "charged for 10 s of blocking: " + leftAfterDab[0]);

                    // The owner's own dab, refused by their own hold, is free.
                    Reply own = preview(scatter, h.player, small(x0, z0, gold));
                    DabOutcome mine = dab(h, h.player, 22, x0 + 12, z0 + 12);
                    check(!mine.accepted() && mine.reason() == RejectReason.AREA_BUSY, "own dab: " + mine);
                    clock.addAndGet(10 * second);
                    planNow(scatter, own);
                    own.get("a preview that refused only its owner's dab");
                    long expected = Math.min(burst, leftAfterDab[0] + (long) (10 * second * refill));
                    check(scatter.holdLeftNanos(id) == expected, "charged for its owner's dab: "
                            + scatter.holdLeftNanos(id) + ", expected " + expected);

                    // Outside the held chunks, a dab locked by a job the hold keeps waiting (the owner's own fill, reaching
                    // into the held chunks, queued behind their hold): the hold is what refuses it, so it is charged.
                    long before = scatter.holdLeftNanos(id);
                    Reply keepsWaiting = preview(scatter, h.player, small(x0, z0, gold));
                    h.fill(box(x0 + 20, FLOOR_Y + 5, z0 + 8, x0 + 60, FLOOR_Y + 5, z0 + 12), "minecraft:glass", ownFill);
                    DabOutcome refused = dab(h, other, 23, x0 + 56, z0 + 10); // chunk cx0 + 3: not held
                    check(!refused.accepted() && refused.reason() == RejectReason.AREA_BUSY, "their dab: " + refused);
                    clock.addAndGet(10 * second);
                    planNow(scatter, keepsWaiting);
                    keepsWaiting.get("a preview keeping a job waiting");
                    long charged = Math.max(-burst, Math.min(burst, before + (long) (10 * second * refill)) - 10 * second);
                    check(scatter.holdLeftNanos(id) == charged, "not charged for the job it kept waiting: "
                            + scatter.holdLeftNanos(id) + ", expected " + charged);
                    checkReleased(h, scatter, holds, "after the held-back job");
                })
                .createAndAdd(() -> check(ownFill.result != null, "the owner's fill runs once released"))
                .createAndAdd(() -> {
                    check(ownFill.result.outcome() == JobOutcome.COMPLETED, "own fill " + ownFill.result);
                    // A job that runs whatever the holds (admitted before the preview, which waits for it) refuses the
                    // dab anyway: no hold is charged. The preview then gives up waiting (AREA_BUSY).
                    long before = scatter.holdLeftNanos(id);
                    h.fill(glassBox, "minecraft:air", earlierFill);
                    Reply waits = preview(scatter, h.player, small(x0, z0, gold));
                    DabOutcome refused = dab(h, other, 24, x0 + 10, z0 + 10);
                    check(!refused.accepted() && refused.reason() == RejectReason.AREA_BUSY, "their dab: " + refused);
                    clock.addAndGet(10 * second);
                    planNow(scatter, waits);
                    check(waits.reason == RejectReason.AREA_BUSY, "the waiting preview: " + waits.reason);
                    long unchanged = Math.min(burst, before + (long) (10 * second * refill));
                    check(scatter.holdLeftNanos(id) == unchanged, "charged for a dab a free job refused: "
                            + scatter.holdLeftNanos(id) + ", expected " + unchanged);
                    checkReleased(h, scatter, holds, "at the end");
                })
                .createAndAdd(() -> check(earlierFill.result != null, "the earlier fill runs"))
                .createAndAdd(() -> {
                    check(earlierFill.result.outcome() == JobOutcome.COMPLETED, "earlier fill " + earlierFill.result);
                    forceChunks(h.world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** One dab of a fresh raise stroke by {@code p} at column (x, z), then the stroke ends. */
    private static DabOutcome dab(Harness h, ServerPlayerEntity p, int strokeId, int x, int z) {
        try {
            h.service.beginStroke(p, strokeId, new BrushSpec(BrushTool.RAISE, 3, 1f, Falloff.SMOOTH, Shape.CIRCLE, null,
                    SurfaceMask.ANY, 0, 0, 1L));
        } catch (EditRejected e) {
            throw new GameTestException("stroke refused: " + e.getMessage());
        }
        DabOutcome outcome = h.service.dabs(p, strokeId, 0, List.of(EditTestSupport.dab(0, x, FLOOR_Y, z)));
        h.service.endStroke(p, strokeId);
        return outcome;
    }

    /**
     * A3: a preview holds only the chunks of its painted discs widened by the variants' reach: two dots 900 blocks
     * apart hold two chunk columns, not the rectangle between them, so edits between the dots are not held off; and
     * chunks wholly outside the world border are not held at all.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_heldchunks", tickLimit = LIMIT)
    public void scatterHoldsOnlyThePaintedChunks(TestContext context) {
        Harness h = new Harness(context);
        ServerScatter scatter = new ServerScatter(h.service);
        EditExecutor executor = h.service.executor();
        int[] at = regionCorner(context, 87);
        int x0 = at[0], z0 = at[1];
        Box all = floor(h, x0, z0, 16, 16);
        int holds = executor.holdCount();
        SourceRef gold = block(h, h.player, "minecraft:gold_block");
        ScatterArea dots = new ScatterArea.Stamps(List.of(ScatterArea.Stamp.paint(x0 + 8, z0 + 8, 3),
                ScatterArea.Stamp.paint(x0 + 904, z0 + 8, 3)));
        java.util.function.IntPredicate held = x -> executor.isLocked(h.world, box(x, FLOOR_Y, z0 + 8, x, FLOOR_Y, z0 + 8));

        Reply reply = preview(scatter, h.player, request(dots, 2, 1L, gold));
        long columns = scatter.heldColumnCount(h.player.getUuid());
        check(columns == 2, "two dots hold " + columns + " chunk columns");
        check(held.test(x0 + 8) && held.test(x0 + 904), "a dot is not held");
        check(!held.test(x0 + 450) && !held.test(x0 + 40) && !held.test(x0 + 870), "the chunks between the dots are held");
        planNow(scatter, reply);
        check(reply.get("two dots").placements() > 0, "nothing placed at the loaded dot");
        checkReleased(h, scatter, holds, "after planning");

        net.minecraft.world.border.WorldBorder border = h.world.getWorldBorder();
        double centerX = border.getCenterX(), centerZ = border.getCenterZ(), size = border.getSize();
        try {
            border.setCenter(x0 + 450 - 100_000, z0);
            border.setSize(200_000);
            Reply cut = preview(scatter, h.player, request(dots, 2, 1L, gold));
            check(scatter.heldColumnCount(h.player.getUuid()) == 1, "held " + scatter.heldColumnCount(h.player.getUuid())
                    + " columns with the second dot outside the border");
            check(held.test(x0 + 8) && !held.test(x0 + 904), "a chunk outside the border is held");
            scatter.shutdown();
            check(!cut.finished(), "a stopped preview was answered");
        } finally {
            border.setCenter(centerX, centerZ);
            border.setSize(size);
        }
        checkReleased(h, scatter, holds, "after shutdown");
        forceChunks(h.world, all, false);
        h.close();
        context.complete();
    }

    private static void tooLarge(ServerScatter scatter, ServerPlayerEntity p, C2S.ScatterPreview request, String what) {
        EditRejected e = refusal(() -> scatter.preview(p, request, new Reply()));
        check(e.reason() == RejectReason.TOO_LARGE, what + ": " + e.reason() + " " + e.getMessage());
    }

    /**
     * Planned one step at a time over many lane runs, the held plan hashes the same as a synchronous
     * {@code finish()} with the same settings, sources, guard and budgets over the same world.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_stepped", tickLimit = LIMIT)
    public void scatterSteppedPlanMatchesSynchronousFinish(TestContext context) {
        Harness h = new Harness(context);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 84);
        int x0 = at[0], z0 = at[1];
        Box all = floor(h, x0, z0, 96, 96);
        BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
        for (int i = 0; i < 96; i += 5) writer.write(x0 + i, FLOOR_Y + 1, z0 + (i * 11) % 96, h.state("minecraft:oak_log[axis=y]"), null);
        Clipboard.Builder bush = Clipboard.builder(h.runtime.states(), new BlockPos(3, 2, 3)).anchor(new BlockPos(1, 0, 1));
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) bush.set(x, 0, z, h.state("minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]"));
        }
        bush.set(1, 1, 1, h.state("minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]"));
        SourceRef source = new SourceRef.Clipboard(h.service.clipboards().install(h.player.getUuid(), bush.build()).id());
        Reply reply = preview(scatter, h.player, request(1, region(x0, z0, x0 + 95, z0 + 95), 3,
                new ScatterSettings.Density.Fraction(0.6), ScatterSettings.Fit.DEFAULT, 77L, ScatterSettings.Transforms.ALL,
                source));
        int runs = 0;
        while (!reply.finished() && runs < 100_000) {
            scatter.runLane(System.nanoTime()); // no time left: exactly the guaranteed step
            runs++;
        }
        reply.get("stepped preview");
        check(runs > 36, "planned in " + runs + " steps");
        ScatterPlan stepped = heldPlan(h, h.player);
        long maxCells = h.runtime.permissions().has(h.player, Perm.LIMIT_BYPASS)
                ? Long.MAX_VALUE : h.runtime.config().limits.maxOpVolume;
        ScatterPlan synchronous = new ScatterPlanner(stepped.settings(), stepped.sources(), h.runtime.reader(h.world),
                maxCells, h.runtime.config().scatter.maxWork,
                ServerScatter.protectionGuard(h.runtime.permissions(), h.player, h.world)).finish();
        check(stepped.placements().size() > 50, "only " + stepped.placements().size() + " placements");
        check(synchronous.hash().equals(stepped.hash()), "the stepped plan differs from a synchronous one");
        forceChunks(h.world, all, false);
        h.close();
        context.complete();
    }

    /**
     * An admin previews an asset from another player's library folder; after losing {@code admin} they may not
     * commit it ({@code NO_PERMISSION}), and nothing is written.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_library", tickLimit = LIMIT)
    public void scatterCommitRechecksLibraryAccess(TestContext context) {
        Harness h = new Harness(context);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 86);
        int x0 = at[0], z0 = at[1];
        Box all = floor(h, x0, z0, 32, 32);
        ServerPlayerEntity curator = h.addPlayer(false);
        EditTestSupport.grant(curator, Perm.USE, Perm.SCATTER, Perm.CLIPBOARD, Perm.ADMIN);
        Clipboard stone = Clipboard.builder(h.runtime.states(), new BlockPos(1, 1, 1))
                .set(0, 0, 0, h.state("minecraft:emerald_block")).build();
        String hash = Sha256.digest(new byte[] {86}).hex();
        String folder = "_players/" + java.util.UUID.randomUUID() + "/gem.schem";
        h.service.assets().put(new AssetCache.Asset(hash, ScatterGameTest.libraryFile(folder), stone));
        Reply reply = preview(scatter, curator, small(x0, z0, new SourceRef.Asset(hash)));
        planNow(scatter, reply);
        ScatterPlan plan = heldPlan(h, curator);
        check(plan.placements().size() > 5, "only " + plan.placements().size() + " placements");
        EditTestSupport.grant(curator, Perm.USE, Perm.SCATTER, Perm.CLIPBOARD);
        EditRejected refused = refusal(() -> h.service.run(curator, new OpSpec.ScatterCommit(reply.get("preview").planId()),
                RunOptions.DEFAULT, null));
        check(refused.reason() == RejectReason.NO_PERMISSION, "commit after losing access: " + refused.reason());
        check(ScatterGameTest.find(h.world, all, FLOOR_Y + 1, Blocks.EMERALD_BLOCK).isEmpty(), "blocks were written");
        forceChunks(h.world, all, false);
        h.close();
        context.complete();
    }

    /**
     * A chest placed where a tree was previewed survives the commit with its contents; that placement is skipped
     * whole (no half tree), counted in skippedConflicts and reported; the other trees are written.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_builton", tickLimit = LIMIT)
    public void scatterCommitSkipsAPlacementBuiltOn(TestContext context) {
        Harness h = new Harness(context);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 85);
        int x0 = at[0], z0 = at[1];
        Box all = floor(h, x0, z0, 32, 32);
        Clipboard.Builder tree = Clipboard.builder(h.runtime.states(), new BlockPos(3, 4, 3)).anchor(new BlockPos(1, 0, 1));
        for (int y = 0; y < 3; y++) tree.set(1, y, 1, h.state("minecraft:oak_log[axis=y]"));
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) tree.set(x, 3, z, h.state("minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]"));
        }
        SourceRef source = new SourceRef.Clipboard(h.service.clipboards().install(h.player.getUuid(), tree.build()).id());
        Reply reply = preview(scatter, h.player, request(region(x0, z0, x0 + 31, z0 + 31), 6, 5L, source));
        planNow(scatter, reply);
        ScatterPlan plan = heldPlan(h, h.player);
        check(plan.placements().size() > 5, "only " + plan.placements().size() + " placements");
        BlockPos anchor = plan.placements().get(0).anchor();
        net.minecraft.util.math.BlockPos chest = pos(anchor.x(), anchor.y(), anchor.z());
        h.runtime.writer(h.world, BlockWriter.Options.DEFAULT).write(chest.getX(), chest.getY(), chest.getZ(),
                h.state("minecraft:chest[facing=north,type=single,waterlogged=false]"), null);
        ((ChestBlockEntity) h.world.getBlockEntity(chest)).setStack(0, new ItemStack(Items.DIAMOND, 3));
        RecordingListener job = ScatterGameTest.commit(h, h.player, reply.get("preview").planId());
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(job.result != null, "commit running"))
                .createAndAdd(() -> {
                    check(job.result.outcome() == JobOutcome.COMPLETED, "commit " + job.result);
                    check(job.result.skippedConflicts() == 1, "skipped " + job.result.skippedConflicts());
                    check(h.events.scatterSkips.equals(List.of(1L)), "reported " + h.events.scatterSkips);
                    check(h.world.getBlockState(chest).isOf(Blocks.CHEST), "the chest was replaced");
                    ChestBlockEntity kept = (ChestBlockEntity) h.world.getBlockEntity(chest);
                    check(kept != null && kept.getStack(0).isOf(Items.DIAMOND) && kept.getStack(0).getCount() == 3,
                            "the chest lost its contents");
                    check(!h.world.getBlockState(chest.up()).isOf(Blocks.OAK_LOG)
                            && !h.world.getBlockState(chest.up(3)).isOf(Blocks.OAK_LEAVES), "half a tree above the chest");
                    int trunks = ScatterGameTest.find(h.world, all, FLOOR_Y + 1, Blocks.OAK_LOG).size();
                    check(trunks == plan.placements().size() - 1, trunks + " trunks for " + plan.placements().size()
                            + " placements, one skipped");
                    scatter.shutdown();
                    forceChunks(h.world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A new preview while the player's large Shape step is still being written is refused {@code QUEUE_FULL}
     * ({@code EditRejected.STROKE_PENDING}, which the client retries) before anything is replaced: the running preview
     * goes on, keeps its hold, and still answers.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_stroke_pending", tickLimit = LIMIT)
    public void aPreviewWaitingForAStrokeKeepsTheRunningOne(TestContext context) {
        EditExecutor executor = ShapeBrushGameTest.onePartATick(context);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 760);
        int x0 = at[0], z0 = at[1];
        Box all = floor(h, x0, z0, 32, 32);
        Box shapeArea = box(x0 + 160, 86, z0, x0 + 240, 154, z0 + 80);
        EditTestSupport.loadAndForce(h.world, shapeArea);
        SourceRef gold = block(h, h.player, "minecraft:gold_block");
        Reply first = preview(scatter, h.player, small(x0, z0, gold));
        int holds = executor.holdCount();
        BrushSpec cube = BrushSpec.shape(32, new dev.sculptory.core.brush.ShapeSpec(dev.sculptory.core.brush.ShapeSpec.Kind.CUBE,
                65, dev.sculptory.core.region.Facing.UP, dev.sculptory.core.brush.ShapeSpec.Mode.PLACE, 0),
                new dev.sculptory.core.edit.Pattern.Single(h.state("minecraft:glass")), 1L, null,
                dev.sculptory.core.brush.Symmetry.NONE);
        try {
            h.service.beginStroke(h.player, 7, cube);
        } catch (EditRejected e) {
            throw new GameTestException("stroke refused: " + e.getMessage());
        }
        check(h.service.dabs(h.player, 7, 1, List.of(ShapeBrushGameTest.at(0, x0 + 200, 120, z0 + 40))).accepted(), "the step");
        int tick = h.world.getServer().getTicks();
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    check(h.world.getServer().getTicks() > tick, "waiting for the next tick");
                    EditRejected e = refusal(() -> scatter.preview(h.player, small(x0, z0, gold), new Reply()));
                    check(e.reason() == RejectReason.QUEUE_FULL && e.kind().equals(EditRejected.STROKE_PENDING),
                            "refused " + e.reason() + " " + e.kind());
                    check(!first.superseded && !first.finished(), "the running preview was replaced");
                    check(scatter.active() == 1 && executor.holdCount() == holds, "the running preview's hold changed");
                    planNow(scatter, first);
                    first.get("the running preview");
                    MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 1, 100, "the step");
                    h.service.endStroke(h.player, 7);
                    RecordingListener undo = MultiplayerGameTest.historyStep(h, h.player, true);
                    MultiplayerGameTest.tickUntil(executor, () -> undo.result != null, 50, "undoing the step");
                    forceChunks(h.world, all, false);
                    forceChunks(h.world, shapeArea, false);
                    executor.shutdown();
                    h.close();
                })
                .completeIfSuccessful();
    }
}
