package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;
import static dev.sculptory.fabric.gametest.FlipGameTest.settled;
import static dev.sculptory.fabric.gametest.FlipGameTest.undoAll;
import static dev.sculptory.fabric.gametest.FreeRotationGameTest.checkResampled;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.OpCompiler;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteGeometry;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Resampling;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.gametest.ClipTestSupport.Captured;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;

/**
 * The free-rotation methods in a real world: Nearest, Exact count and Smooth, with and
 * without smooth slopes, paste and move the directional showcase as the programs' model says, keep Exact count's
 * blocks, undo exactly, and a smooth turn is refused sooner (its resampling work counts against the op limit). Region
 * slots 2100-2105.
 */
public final class ResamplingGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;
    private static final Orientation TURN = Orientation.free(Transform.IDENTITY, 3_000, 2_000, 0);

    private static List<Orientation> methods() {
        List<Orientation> out = new ArrayList<>();
        for (Resampling method : Resampling.values()) {
            for (boolean slopes : new boolean[] {false, true}) out.add(TURN.withResampling(method, slopes));
        }
        return out;
    }

    private static int nonAir(ServerWorld world, Box box) {
        int n = 0;
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) {
                    if (!world.getBlockState(pos(x, y, z)).isAir()) n++;
                }
            }
        }
        return n;
    }

    private static void run(Harness h, OpSpec op, RecordingListener listener) {
        try {
            h.service.run(h.player, op, RunOptions.DEFAULT, listener);
        } catch (EditRejected e) {
            throw new GameTestException(op.getClass().getSimpleName() + " refused: " + e.getMessage());
        }
    }

    /**
     * The showcase pasted once per method (and with smooth slopes): each copy holds the programs' model, Exact count
     * keeps every block, and undoing them all leaves the area as it was.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_resampling_paste", tickLimit = LIMIT)
    public void everyMethodPastesTheShowcaseAndUndoesExactly(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 2100);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 5, y0 + 3, z0 + 5);
        List<Orientation> orientations = methods();
        int spacing = 16;
        Box all = box(x0 - 4, y0 - 4, z0 - 8, x0 + spacing * (orientations.size() + 1) + 8, y0 + 16, z0 + 16);
        loadAndForce(world, all);
        FlipGameTest.build(h, source.min());
        WorldSnapshot before = capture(world, source);
        WorldSnapshot allBefore = capture(world, all);
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> copied = ClipboardGameTest.copy(clips, h.player, source, source.min());
        List<RecordingListener> pastes = new ArrayList<>();
        List<BlockPos> origins = new ArrayList<>();
        for (int k = 0; k < orientations.size(); k++) origins.add(new BlockPos(x0 + spacing * (k + 1), y0 + 5, z0 + 2));
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    UUID clipboard = copied.get("copy").clipboardId();
                    for (int k = 0; k < orientations.size(); k++) {
                        RecordingListener listener = new RecordingListener();
                        run(h, new OpSpec.Paste(new SourceRef.Clipboard(clipboard), origins.get(k), orientations.get(k),
                                PasteOptions.DEFAULT), listener);
                        pastes.add(listener);
                    }
                })
                .createAndAdd(() -> {
                    for (RecordingListener listener : pastes) check(listener.result != null, "pastes running");
                })
                .createAndAdd(EntitiesGameTest.once(() -> {
                    for (int k = 0; k < orientations.size(); k++) {
                        Orientation o = orientations.get(k);
                        check(pastes.get(k).result.outcome() == JobOutcome.COMPLETED, "paste " + o + " " + pastes.get(k).result);
                        Box target = PasteGeometry.pasteTarget(new BlockPos(6, 4, 6), BlockPos.ORIGIN, o, origins.get(k));
                        checkResampled(world, h.runtime.states(), before, target.min(), o, null, "paste");
                        if (o.resampling() == Resampling.EXACT) {
                            // Every block once, but for two-cell blocks left out whole for want of room.
                            int dropped = FreeRotationGameTest.droppedBlocks(h.runtime.states(), before, o, false);
                            check(nonAir(world, target) == nonAir(world, source) - dropped, "exact count keeps every "
                                    + "block: " + nonAir(world, target) + " of " + nonAir(world, source) + " - " + dropped);
                        }
                    }
                }))
                .createAndAdd(undoAll(h, orientations.size()))
                .createAndAdd(() -> {
                    checkSame(settled(allBefore), settled(capture(world, all)), "after undoing the pastes");
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_resampling_move_nearest", tickLimit = LIMIT)
    public void aNearestMoveTurnsTheShowcaseAndUndoesExactly(TestContext context) {
        moveAndUndo(context, 2101, TURN.withResampling(Resampling.NEAREST, true));
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_resampling_move_exact", tickLimit = LIMIT)
    public void anExactCountMoveTurnsTheShowcaseAndUndoesExactly(TestContext context) {
        moveAndUndo(context, 2102, TURN.withResampling(Resampling.EXACT, false));
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_resampling_move_smooth", tickLimit = LIMIT)
    public void aSmoothMoveTurnsTheShowcaseAndUndoesExactly(TestContext context) {
        moveAndUndo(context, 2103, TURN.withResampling(Resampling.SMOOTH, true));
    }

    /**
     * The showcase moved away turned by {@code o}: the result is the programs' model, the source is vacated, Exact count
     * keeps every block, and the undo leaves the area as it was.
     */
    private static void moveAndUndo(TestContext context, int slot, Orientation o) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, slot);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0 + 4, y0, z0 + 4, x0 + 9, y0 + 3, z0 + 9);
        Box all = box(x0 - 4, y0 - 4, z0 - 4, x0 + 36, y0 + 12, z0 + 20);
        loadAndForce(world, all);
        FlipGameTest.build(h, source.min());
        WorldSnapshot before = capture(world, source);
        WorldSnapshot allBefore = capture(world, all);
        int blocks = nonAir(world, source);
        Pattern air = new Pattern.Single(h.state("minecraft:air"));
        BlockPos offset = new BlockPos(18, 2, 1);
        RecordingListener moved = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(EntitiesGameTest.once(() -> run(h, new OpSpec.Move(new Region.Cuboid(source), offset, o,
                        air, EntityFilter.NONE, Symmetry.NONE, PasteOptions.Into.EVERYTHING), moved)))
                .createAndAdd(() -> check(moved.result != null, "move running"))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    check(moved.result.outcome() == JobOutcome.COMPLETED, "move " + moved.result);
                    Orientation.Bounds bounds = o.bounds(6, 4, 6);
                    BlockPos targetMin = source.min().add(bounds.offset()).add(offset);
                    checkResampled(world, h.runtime.states(), before, targetMin, o, null, null, true, "moved away");
                    check(nonAir(world, source) == 0, "the source is vacated");
                    if (o.resampling() == Resampling.EXACT) {
                        Box target = new Box(targetMin, targetMin.offset(bounds.size().x() - 1, bounds.size().y() - 1,
                                bounds.size().z() - 1));
                        int dropped = FreeRotationGameTest.droppedBlocks(h.runtime.states(), before, o, true);
                        check(nonAir(world, target) == blocks - dropped, "exact count keeps every block: "
                                + nonAir(world, target) + " of " + blocks + " - " + dropped);
                    }
                    check(h.history().undoLabel().startsWith("Move"), "history " + h.history().undoLabel());
                }))
                .createAndAdd(undoAll(h, 1))
                .createAndAdd(() -> {
                    checkSame(settled(allBefore), settled(capture(world, all)), "after undoing the " + o.resampling()
                            + " move");
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A smooth turn's resampling work counts against the op limit: a free move a player without limit.bypass could turn
     * by the nearest block is refused when smooth, before anything is read or written.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_resampling_refusals", tickLimit = LIMIT)
    public void aSmoothTurnIsRefusedSoonerThanANearestOne(TestContext context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 2104);
        int x0 = at[0], y0 = 70, z0 = at[1];
        // 800,000 cells: under the default op limit (2,097,152), over a third of it.
        Region region = new Region.Cuboid(box(x0, y0, z0, x0 + 99, y0 + 79, z0 + 99));
        long cells = 100L * 80 * 100;
        long limit = Limits.DEFAULTS.maxOpVolume();
        Orientation nearest = TURN;
        Orientation smooth = TURN.withResampling(Resampling.SMOOTH, false);
        check(OpCompiler.maxFreeMoveCells(limit, nearest) >= cells, "nearest may turn it");
        check(OpCompiler.maxFreeMoveCells(limit, smooth) < cells, "smooth may not");
        ServerPlayerEntity limited = h.addPlayer(false);
        EditTestSupport.grant(limited, Perm.USE, Perm.REGION);
        Pattern air = new Pattern.Single(h.state("minecraft:air"));
        for (Orientation o : List.of(smooth, TURN.withResampling(Resampling.SMOOTH, true))) {
            EditRejected refused = ClipboardGameTest.refusal(() -> h.service.run(limited, new OpSpec.Move(region,
                    BlockPos.ORIGIN, o, air, EntityFilter.NONE, Symmetry.NONE, PasteOptions.Into.EVERYTHING),
                    RunOptions.DEFAULT, null));
            check(refused.reason() == RejectReason.TOO_LARGE, o + ": " + refused.reason());
        }
        check(h.history().undoLabels().isEmpty(), "nothing ran: " + h.history().undoLabels());
        h.close();
        context.complete();
    }

    /**
     * Exact count's result box can be a cell wider than the nearest one: admission judges that wider box. A free move
     * whose exact box alone reaches protected columns is refused as protected, and one whose exact box alone reaches past
     * the build height is refused, both before anything is written.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_resampling_exact_box", tickLimit = LIMIT)
    public void exactCountsWiderBoxIsJudgedAtAdmission(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 2105);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box area = box(x0, y0 - 1, z0, x0 + 47, y0 + 12, z0 + 47);
        loadAndForce(world, area);
        Box source = box(x0 + 16, y0, z0 + 16, x0 + 21, y0 + 3, z0 + 21);
        FlipGameTest.build(h, source.min());
        WorldSnapshot original = capture(world, area);
        Pattern air = new Pattern.Single(h.state("minecraft:air"));
        // A turn whose exact box reaches further west than the nearest one.
        Orientation exact = null;
        for (int yaw = 500; yaw < 9_000 && exact == null; yaw += 250) {
            Orientation o = Orientation.free(Transform.IDENTITY, yaw, 0, 0, Resampling.EXACT, false);
            if (o.bounds(6, 4, 6).offset().x() < o.withResampling(Resampling.NEAREST, false).bounds(6, 4, 6).offset().x()) {
                exact = o;
            }
        }
        check(exact != null, "some yaw widens the exact box");
        Orientation turn = exact;
        int nearestWest = source.min().x() + turn.withResampling(Resampling.NEAREST, false).bounds(6, 4, 6).offset().x();
        int exactWest = source.min().x() + turn.bounds(6, 4, 6).offset().x();
        OpSpec.Move move = new OpSpec.Move(new Region.Cuboid(source), BlockPos.ORIGIN, turn, air, EntityFilter.NONE,
                Symmetry.NONE, PasteOptions.Into.EVERYTHING);
        try {
            ProtectionHook.protect(h.player, world, exactWest, z0, nearestWest - 1, z0 + 47);
            EditRejected guarded = ClipboardGameTest.refusal(() -> h.service.run(h.player, move, RunOptions.DEFAULT,
                    null));
            check(guarded.reason() == RejectReason.PROTECTED, "exact box into protected columns: " + guarded.reason());
        } finally {
            ProtectionHook.clear(h.player);
        }
        // A tilt whose exact box stands taller than the nearest one, placed so that only the exact box leaves the top.
        int top = world.getTopY();
        Orientation tall = null;
        for (int pitch = 500; pitch < 9_000 && tall == null; pitch += 250) {
            Orientation o = Orientation.free(Transform.IDENTITY, 0, pitch, 0, Resampling.EXACT, false);
            Orientation.Bounds e = o.bounds(6, 4, 6), n = o.withResampling(Resampling.NEAREST, false).bounds(6, 4, 6);
            if (e.offset().y() + e.size().y() > n.offset().y() + n.size().y()) tall = o;
        }
        check(tall != null, "some pitch makes the exact box taller");
        Orientation.Bounds near = tall.withResampling(Resampling.NEAREST, false).bounds(6, 4, 6);
        int bottomY = top - near.offset().y() - near.size().y();
        Region high = new Region.Cuboid(box(x0 + 30, bottomY, z0 + 30, x0 + 35, bottomY + 3, z0 + 35));
        Orientation tilt = tall;
        EditRejected leaves = ClipboardGameTest.refusal(() -> h.service.run(h.player, new OpSpec.Move(high,
                BlockPos.ORIGIN, tilt, air, EntityFilter.NONE, Symmetry.NONE, PasteOptions.Into.EVERYTHING),
                RunOptions.DEFAULT, null));
        check(leaves.reason() == RejectReason.INVALID, "exact box out of the build height: " + leaves.reason());
        check(h.service.jobs(h.player.getUuid()).isEmpty(), "a refusal left a job");
        check(h.history().undoLabels().isEmpty(), "a refusal pushed history: " + h.history().undoLabels());
        checkSame(original, capture(world, area), "after the refusals");
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }
}
