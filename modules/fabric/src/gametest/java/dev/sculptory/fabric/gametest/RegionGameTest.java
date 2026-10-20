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
import dev.sculptory.core.Sha256;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.gametest.ClipTestSupport.Captured;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.net.FabricTransport;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.Codec;
import dev.sculptory.protocol.v2.Features;
import dev.sculptory.protocol.v2.Handshake;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.Message;
import dev.sculptory.protocol.v2.ProtocolException;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.protocol.v2.StreamKind;
import dev.sculptory.protocol.v2.StreamSender;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobTicket;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.ScatterService;
import dev.sculptory.server.engine.impl.ServerClipboards;
import dev.sculptory.server.net.NetSession;
import dev.sculptory.server.net.ServerDispatcher;
import dev.sculptory.server.platform.WriteOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Select operations over shapes and cell sets against a real server (regionCorner slots 620-639): exact cells written and exact undo, copies and cuts of cell sets, protection by the
 * columns a region's cells are in rather than its bounds, and a selection uploaded through the network layer.
 */
public final class RegionGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    // =================================================================== helpers

    /** The state a cell must hold, as a raw state id. */
    @FunctionalInterface
    private interface Expected {
        int at(int x, int y, int z);
    }

    /** Every cell of {@code area} holds what {@code expected} says; names the first cell that does not. */
    private static void checkCells(ServerLevel world, Box area, Expected expected, String what) {
        WorldSnapshot now = capture(world, area);
        for (int y = area.min().y(); y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    int want = expected.at(x, y, z), got = now.get(x, y, z);
                    if (want != got) {
                        throw new GameTestAssertException(what + " at " + x + "," + y + "," + z + ": "
                                + Block.stateById(got) + " instead of " + Block.stateById(want));
                    }
                }
            }
        }
    }

    /** Hollow's inside: every cell within t along the six directions is a region cell. */
    private static boolean inside(Region r, int x, int y, int z, int t) {
        if (!r.contains(x, y, z)) return false;
        for (int k = 1; k <= t; k++) {
            if (!r.contains(x + k, y, z) || !r.contains(x - k, y, z) || !r.contains(x, y + k, z)
                    || !r.contains(x, y - k, z) || !r.contains(x, y, z + k) || !r.contains(x, y, z - k)) {
                return false;
            }
        }
        return true;
    }

    /** A wall: a cell outside the region lies within t along x or z. */
    private static boolean wall(Region r, int x, int y, int z, int t) {
        if (!r.contains(x, y, z)) return false;
        for (int k = 1; k <= t; k++) {
            if (!r.contains(x + k, y, z) || !r.contains(x - k, y, z) || !r.contains(x, y, z + k) || !r.contains(x, y, z - k)) {
                return true;
            }
        }
        return false;
    }

    private static JobTicket run(Harness h, ServerPlayer player, OpSpec op, RecordingListener listener) {
        try {
            return h.service.run(player, op, RunOptions.DEFAULT, listener);
        } catch (EditRejected e) {
            throw new GameTestAssertException(op.getClass().getSimpleName() + " refused: " + e.getMessage());
        }
    }

    private static EditRejected refusal(ClipboardGameTest.ThrowingRun run) {
        return ClipboardGameTest.refusal(run);
    }

    private static int raw(Harness h, String state) {
        return h.state(state);
    }

    /** Wool of four colours by position, so moved and copied cells are told apart. */
    private static void paint(Harness h, BlockWriter writer, Box box) {
        String[] colours = {"minecraft:white_wool", "minecraft:red_wool", "minecraft:blue_wool", "minecraft:lime_wool"};
        for (int x = box.min().x(); x <= box.max().x(); x++) {
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) {
                    writer.write(x, y, z, h.state(colours[Math.floorMod(x * 3 + y * 5 + z * 7, 4)]), null);
                }
            }
        }
    }

    /** A chest holding 5 diamonds at {@code at}. */
    private static void chest(Harness h, BlockWriter writer, net.minecraft.core.BlockPos at) {
        writer.write(at.getX(), at.getY(), at.getZ(), h.state("minecraft:chest[facing=north]"), null);
        ((ChestBlockEntity) h.world.getBlockEntity(at)).setItem(0, new ItemStack(Items.DIAMOND, 5));
    }

    /** A magic selection: an L of walls, a staircase and scattered cells, crossing section boundaries. */
    private static CellSet selection(int x0, int z0) {
        CellSet.Builder builder = CellSet.builder();
        for (int y = 100; y <= 104; y++) {
            for (int x = x0 + 2; x <= x0 + 17; x++) builder.add(x, y, z0 + 2);
            for (int z = z0 + 2; z <= z0 + 15; z++) builder.add(x0 + 2, y, z);
        }
        for (int i = 0; i < 12; i++) builder.add(x0 + 3 + i, 100 + i / 3, z0 + 3 + i);
        Random random = new Random(620);
        for (int i = 0; i < 25; i++) builder.add(x0 + 2 + random.nextInt(16), 100 + random.nextInt(6), z0 + 2 + random.nextInt(14));
        return builder.build();
    }

    // =================================================================== shapes

    /**
     * Fill of an ellipsoid, Hollow of it and Walls of a horizontal cylinder crossing it, each exactly the shape's cells
     * by its definition, then three undos back to the world as it was, chest contents included.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_region_shapes", timeoutTicks = LIMIT)
    public void shapedFillHollowAndWallsUndoExactly(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 620);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 99, z0, x0 + 31, 121, z0 + 31);
        loadAndForce(world, area);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        paint(h, writer, box(x0, 100, z0, x0 + 31, 101, z0 + 31));
        chest(h, writer, pos(x0 + 14, 108, z0 + 12));
        WorldSnapshot original = capture(world, area);
        Region.Shape ball = new Region.Shape(box(x0 + 2, 100, z0 + 2, x0 + 26, 118, z0 + 22), ShapeKind.ELLIPSOID, Facing.UP);
        Region.Shape cylinder = new Region.Shape(box(x0 + 5, 100, z0 + 4, x0 + 29, 112, z0 + 19), ShapeKind.CYLINDER,
                Facing.EAST);
        int stone = raw(h, "minecraft:stone"), air = raw(h, "minecraft:air"), glass = raw(h, "minecraft:glass");
        RecordingListener fill = new RecordingListener(), hollow = new RecordingListener(), walls = new RecordingListener();
        RecordingListener undo1 = new RecordingListener(), undo2 = new RecordingListener(), undo3 = new RecordingListener();
        WorldSnapshot[] steps = new WorldSnapshot[2];
        JobTicket ticket = run(h, h.player, new OpSpec.Fill(ball, new Pattern.Single(stone), CellMask.ANY), fill);
        check(ticket.estimatedCells() == ball.cellCount(), "estimate " + ticket.estimatedCells() + " of " + ball.cellCount());
        context.startSequence()
                .thenWaitUntil(() -> check(fill.result != null, "fill running"))
                .thenWaitUntil(() -> {
                    check(fill.result.outcome() == JobOutcome.COMPLETED, "fill " + fill.result);
                    checkCells(world, area, (x, y, z) -> ball.contains(x, y, z) ? stone : original.get(x, y, z), "fill");
                    steps[0] = capture(world, area);
                    run(h, h.player, new OpSpec.Hollow(ball, 2, new Pattern.Single(air)), hollow);
                })
                .thenWaitUntil(() -> check(hollow.result != null, "hollow running"))
                .thenWaitUntil(() -> {
                    check(hollow.result.outcome() == JobOutcome.COMPLETED, "hollow " + hollow.result);
                    checkCells(world, area, (x, y, z) -> inside(ball, x, y, z, 2) ? air : steps[0].get(x, y, z), "hollow");
                    steps[1] = capture(world, area);
                    run(h, h.player, new OpSpec.Walls(cylinder, 1, new Pattern.Single(glass)), walls);
                })
                .thenWaitUntil(() -> check(walls.result != null, "walls running"))
                .thenWaitUntil(() -> {
                    check(walls.result.outcome() == JobOutcome.COMPLETED, "walls " + walls.result);
                    checkCells(world, area, (x, y, z) -> wall(cylinder, x, y, z, 1) ? glass : steps[1].get(x, y, z), "walls");
                    h.undo(undo1);
                })
                .thenWaitUntil(() -> check(undo1.result != null, "undo 1 running"))
                .thenWaitUntil(() -> {
                    checkSame(steps[1], capture(world, area), "after undoing walls");
                    h.undo(undo2);
                })
                .thenWaitUntil(() -> check(undo2.result != null, "undo 2 running"))
                .thenWaitUntil(() -> {
                    checkSame(steps[0], capture(world, area), "after undoing hollow");
                    h.undo(undo3);
                })
                .thenWaitUntil(() -> check(undo3.result != null, "undo 3 running"))
                .thenWaitUntil(() -> {
                    check(undo3.result.skippedConflicts() == 0, "undo " + undo3.result);
                    checkSame(original, capture(world, area), "after undoing fill");
                    forceChunks(world, area, false);
                    h.close();
                })
                .thenSucceed();
    }

    // =================================================================== cell sets

    /**
     * A magic selection stacked twice upward, moved and turned, filled and erased: each exactly its cells (the moved
     * chest keeps its diamonds and the stairs turn), then four undos back to the original world.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_region_cells", timeoutTicks = LIMIT)
    public void cellSetOpsUndoExactly(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 622);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 99, z0, x0 + 47, 125, z0 + 31);
        loadAndForce(world, area);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        paint(h, writer, box(x0 + 2, 100, z0 + 2, x0 + 17, 105, z0 + 15));
        net.minecraft.core.BlockPos chestAt = pos(x0 + 2, 101, z0 + 9);
        chest(h, writer, chestAt);
        writer.write(x0 + 9, 102, z0 + 2, h.state("minecraft:oak_stairs[facing=north]"), null);
        CellSet set = selection(x0, z0);
        check(set.contains(chestAt.getX(), chestAt.getY(), chestAt.getZ()) && set.contains(x0 + 9, 102, z0 + 2),
                "the chest and stairs are selected");
        Region.Cells region = new Region.Cells(set);
        WorldSnapshot original = capture(world, area);
        int dy = 7;
        Box pivot = set.bounds();
        dev.sculptory.core.BlockPos offset = new dev.sculptory.core.BlockPos(22, 0, 1);
        Transform turn = Transform.rotation(1);
        dev.sculptory.core.BlockPos destMin = pivot.min().add(offset);
        RecordingListener stack = new RecordingListener(), move = new RecordingListener(), fill = new RecordingListener();
        RecordingListener erase = new RecordingListener();
        List<RecordingListener> undos = List.of(new RecordingListener(), new RecordingListener(), new RecordingListener(),
                new RecordingListener());
        WorldSnapshot[] steps = new WorldSnapshot[3];
        int stone = raw(h, "minecraft:stone"), air = raw(h, "minecraft:air");
        JobTicket ticket = run(h, h.player, new OpSpec.Stack(region, 0, dy, 0, 2, EntityFilter.NONE), stack);
        check(ticket.estimatedCells() == 2 * set.size(), "stack estimate " + ticket.estimatedCells());
        var runner = context.startSequence()
                .thenWaitUntil(() -> check(stack.result != null, "stack running"))
                .thenWaitUntil(() -> {
                    check(stack.result.outcome() == JobOutcome.COMPLETED, "stack " + stack.result);
                    checkCells(world, area, (x, y, z) -> {
                        for (int k = 2; k >= 1; k--) {
                            if (set.contains(x, y - k * dy, z)) return original.get(x, y - k * dy, z);
                        }
                        return original.get(x, y, z);
                    }, "stack");
                    steps[0] = capture(world, area);
                    run(h, h.player, new OpSpec.Move(region, offset, turn, new Pattern.Single(air), EntityFilter.NONE), move);
                })
                .thenWaitUntil(() -> check(move.result != null, "move running"))
                .thenWaitUntil(() -> {
                    check(move.result.outcome() == JobOutcome.COMPLETED, "move " + move.result);
                    int[][] landing = new int[area.sizeX() * area.sizeY() * area.sizeZ()][];
                    for (int x = pivot.min().x(); x <= pivot.max().x(); x++) {
                        for (int y = pivot.min().y(); y <= pivot.max().y(); y++) {
                            for (int z = pivot.min().z(); z <= pivot.max().z(); z++) {
                                if (!set.contains(x, y, z)) continue;
                                int lx = x - pivot.min().x(), lz = z - pivot.min().z();
                                int tx = destMin.x() + turn.mapX(lx, lz, pivot.sizeX(), pivot.sizeZ());
                                int tz = destMin.z() + turn.mapZ(lx, lz, pivot.sizeX(), pivot.sizeZ());
                                BlockState from = Block.stateById(steps[0].get(x, y, z));
                                landing[steps[0].index(tx, y, tz)] = new int[] {Block.getId(
                                        from.rotate(Rotation.CLOCKWISE_90))};
                            }
                        }
                    }
                    checkCells(world, area, (x, y, z) -> {
                        int[] moved = landing[steps[0].index(x, y, z)];
                        if (moved != null) return moved[0];
                        return set.contains(x, y, z) ? air : steps[0].get(x, y, z);
                    }, "move");
                    int lx = chestAt.getX() - pivot.min().x(), lz = chestAt.getZ() - pivot.min().z();
                    net.minecraft.core.BlockPos movedChest = pos(destMin.x() + turn.mapX(lx, lz, pivot.sizeX(), pivot.sizeZ()),
                            chestAt.getY(), destMin.z() + turn.mapZ(lx, lz, pivot.sizeX(), pivot.sizeZ()));
                    ChestBlockEntity moved = (ChestBlockEntity) world.getBlockEntity(movedChest);
                    check(moved != null && moved.getItem(0).is(Items.DIAMOND) && moved.getItem(0).getCount() == 5,
                            "the moved chest lost its diamonds");
                    steps[1] = capture(world, area);
                    run(h, h.player, new OpSpec.Fill(region, new Pattern.Single(stone), CellMask.ANY), fill);
                })
                .thenWaitUntil(() -> check(fill.result != null, "fill running"))
                .thenWaitUntil(() -> {
                    check(fill.result.changed() == set.size(), "fill changed " + fill.result.changed() + " of " + set.size());
                    checkCells(world, area, (x, y, z) -> set.contains(x, y, z) ? stone : steps[1].get(x, y, z), "fill");
                    steps[2] = capture(world, area);
                    run(h, h.player, new OpSpec.Erase(region, CellMask.ANY), erase);
                })
                .thenWaitUntil(() -> check(erase.result != null, "erase running"))
                .thenWaitUntil(() -> {
                    checkCells(world, area, (x, y, z) -> set.contains(x, y, z) ? air : steps[2].get(x, y, z), "erase");
                    h.undo(undos.get(0));
                });
        String[] names = {"erase", "fill", "move", "stack"};
        for (int i = 0; i < 4; i++) {
            int step = i;
            runner.thenWaitUntil(() -> check(undos.get(step).result != null, "undo " + step + " running"))
                    .thenWaitUntil(() -> {
                        check(undos.get(step).result.skippedConflicts() == 0, "undo " + undos.get(step).result);
                        WorldSnapshot want = step == 3 ? original : steps[2 - step];
                        checkSame(want, capture(world, area), "after undoing " + names[step]);
                        if (step < 3) {
                            h.undo(undos.get(step + 1));
                        } else {
                            forceChunks(world, area, false);
                            h.close();
                        }
                    });
        }
        runner.thenSucceed();
    }

    /**
     * Copy and cut of a magic selection: the clipboard holds exactly the selected cells (the rest absent), a paste of it
     * writes only those, and the cut erases exactly them and undoes exactly.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_region_clip", timeoutTicks = LIMIT)
    public void cellSetCopyCutAndPaste(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 624);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 99, z0, x0 + 47, 110, z0 + 31);
        loadAndForce(world, area);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        paint(h, writer, box(x0 + 2, 100, z0 + 2, x0 + 17, 105, z0 + 15));
        paint(h, writer, box(x0 + 24, 100, z0 + 2, x0 + 39, 105, z0 + 15));
        CellSet set = selection(x0, z0);
        Region.Cells region = new Region.Cells(set);
        Box bounds = set.bounds();
        WorldSnapshot original = capture(world, area);
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> copied = new Captured<>();
        Captured<ClipboardService.ClipboardInfo> cut = new Captured<>();
        RecordingListener paste = new RecordingListener(), erase = new RecordingListener(), undo = new RecordingListener();
        int dx = 22;
        try {
            check(clips.copy(h.player, region, bounds.min(), false, CellMask.ANY, EntityFilter.NONE, null, copied) == null,
                    "a copy has no job");
        } catch (EditRejected e) {
            throw new GameTestAssertException("copy refused: " + e.getMessage());
        }
        WorldSnapshot[] pasted = new WorldSnapshot[1];
        context.startSequence()
                .thenWaitUntil(() -> check(copied.finished(), "copy running"))
                .thenWaitUntil(() -> {
                    ClipboardService.ClipboardInfo info = copied.get("copy");
                    check(info.cells() == set.size(), info.cells() + " cells copied of " + set.size());
                    Clipboard clipboard = h.service.clipboards().get(h.player.getUUID()).orElseThrow().clipboard();
                    for (int x = bounds.min().x(); x <= bounds.max().x(); x++) {
                        for (int y = bounds.min().y(); y <= bounds.max().y(); y++) {
                            for (int z = bounds.min().z(); z <= bounds.max().z(); z++) {
                                int local = clipboard.get(x - bounds.min().x(), y - bounds.min().y(), z - bounds.min().z());
                                int want = set.contains(x, y, z) ? original.get(x, y, z) : -1;
                                check(local == want, "clipboard at " + x + "," + y + "," + z + ": " + local + " not " + want);
                            }
                        }
                    }
                    run(h, h.player, new OpSpec.Paste(new SourceRef.Clipboard(info.clipboardId()),
                            bounds.min().offset(dx, 0, 0), Transform.IDENTITY, new PasteOptions(true, false, false)), paste);
                })
                .thenWaitUntil(() -> check(paste.result != null, "paste running"))
                .thenWaitUntil(() -> {
                    check(paste.result.outcome() == JobOutcome.COMPLETED, "paste " + paste.result);
                    checkCells(world, area, (x, y, z) -> set.contains(x - dx, y, z) ? original.get(x - dx, y, z)
                            : original.get(x, y, z), "paste writes only the selected cells");
                    pasted[0] = capture(world, area);
                    try {
                        JobTicket job = clips.copy(h.player, region, bounds.min(), true, CellMask.ANY,
                                EntityFilter.NONE, erase, cut);
                        check(job != null && job.estimatedCells() == set.size(), "cut job " + job);
                    } catch (EditRejected e) {
                        throw new GameTestAssertException("cut refused: " + e.getMessage());
                    }
                })
                .thenWaitUntil(() -> check(erase.result != null && cut.finished(), "cut running"))
                .thenWaitUntil(() -> {
                    check(cut.get("cut").cells() == set.size(), "cut clipboard");
                    check(erase.result.changed() == set.size(), "cut erased " + erase.result.changed());
                    int air = raw(h, "minecraft:air");
                    checkCells(world, area, (x, y, z) -> set.contains(x, y, z) ? air : pasted[0].get(x, y, z), "cut");
                    h.undo(undo);
                })
                .thenWaitUntil(() -> check(undo.result != null, "undo running"))
                .thenWaitUntil(() -> {
                    checkSame(pasted[0], capture(world, area), "after undoing the cut");
                    forceChunks(world, area, false);
                    h.close();
                })
                .thenSucceed();
    }

    // =================================================================== protection

    /**
     * Protection follows the columns a region's cells are in. A builder may not modify one chunk (protected for them
     * like a claim); a ring of cells around it, whose bounds cover it, may be copied, stacked, moved and filled; the
     * same ring with one cell inside the chunk is refused as a box over it is ({@code PROTECTED}), and a fill of it
     * skips that one cell.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_region_protect", timeoutTicks = LIMIT)
    public void protectionFollowsTheRegionsColumns(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        ServerPlayer builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.CLIPBOARD, Perm.REGION);
        int[] at = regionCorner(context, 626);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 99, z0, x0 + 47, 115, z0 + 47);
        loadAndForce(world, area);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        paint(h, writer, box(x0 + 8, 100, z0 + 8, x0 + 39, 101, z0 + 39));
        CellSet.Builder ringBuilder = CellSet.builder();
        for (int i = 10; i <= 37; i++) {
            for (int y = 100; y <= 101; y++) {
                ringBuilder.add(x0 + i, y, z0 + 10).add(x0 + i, y, z0 + 37).add(x0 + 10, y, z0 + i).add(x0 + 37, y, z0 + i);
            }
        }
        CellSet ring = ringBuilder.build();
        Region.Cells ringRegion = new Region.Cells(ring);
        Region.Cells reaching = new Region.Cells(ring.union(CellSet.builder().add(x0 + 20, 101, z0 + 20).build()));
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Pattern air = new Pattern.Single(raw(h, "minecraft:air"));
        int stone = raw(h, "minecraft:stone");
        Captured<ClipboardService.ClipboardInfo> copied = new Captured<>();
        RecordingListener stack = new RecordingListener(), fill = new RecordingListener(), move = new RecordingListener();
        RecordingListener partial = new RecordingListener();
        // Hollow and Walls of a vast shape are refused by their capped count before anything is eroded, and a thickness
        // over 16 is invalid (review fixes 1 and 2).
        Region.Shape vast = new Region.Shape(box(x0, -60, z0, x0 + 1499, 300, z0 + 899), ShapeKind.ELLIPSOID, Facing.UP);
        long refusalStarted = System.nanoTime();
        check(refusal(() -> h.service.run(builder, new OpSpec.Hollow(vast, 2, air), RunOptions.DEFAULT, null)).reason()
                == RejectReason.TOO_LARGE, "a hollow of a vast shape");
        check(refusal(() -> h.service.run(builder, new OpSpec.Walls(vast, 16, new Pattern.Single(stone)), RunOptions.DEFAULT,
                null)).reason() == RejectReason.TOO_LARGE, "walls of a vast shape");
        long refusalMillis = (System.nanoTime() - refusalStarted) / 1_000_000;
        check(refusalMillis < 1_000, "the refusals took " + refusalMillis + " ms");
        check(refusal(() -> h.service.run(builder, new OpSpec.Hollow(new Region.Cuboid(box(x0, 100, z0, x0 + 40, 110, z0 + 40)),
                17, air), RunOptions.DEFAULT, null)).reason() == RejectReason.INVALID, "a thickness of 17");
        ProtectionHook.protect(builder, world, x0 + 16, z0 + 16, x0 + 31, z0 + 31);
        try {
            check(!world.mayInteract(builder, pos(x0 + 20, 100, z0 + 20)), "the chunk is not protected");
            check(world.mayInteract(builder, pos(x0 + 10, 100, z0 + 10)), "the ring is protected");
            check(world.mayInteract(h.player, pos(x0 + 20, 100, z0 + 20)), "the hook reached another player");
            // The ring's bounds cover the protected chunk, its cells do not.
            clips.copy(builder, ringRegion, ring.bounds().min(), false, CellMask.ANY, EntityFilter.NONE, null, copied);
            run(h, builder, new OpSpec.Stack(ringRegion, 0, 5, 0, 1, EntityFilter.NONE), stack);
            // A box over the same bounds is refused, as boxes are.
            check(refusal(() -> clips.copy(builder, ring.bounds(), ring.bounds().min(), false, CellMask.ANY, null,
                    new Captured<>())).reason() == RejectReason.PROTECTED, "a box over the protected chunk");
            // The ring with one cell inside the chunk is refused like that box.
            check(refusal(() -> clips.copy(builder, reaching, ring.bounds().min(), false, CellMask.ANY, EntityFilter.NONE,
                    null, new Captured<>()))
                    .reason() == RejectReason.PROTECTED, "a copy reaching into the chunk");
            check(refusal(() -> h.service.run(builder, new OpSpec.Stack(reaching, 0, 5, 0, 1, EntityFilter.NONE),
                    RunOptions.DEFAULT, null)).reason() == RejectReason.PROTECTED, "a stack reaching into the chunk");
            check(refusal(() -> h.service.run(builder, new OpSpec.Move(reaching, new dev.sculptory.core.BlockPos(0, 8, 0),
                    Transform.IDENTITY, air, EntityFilter.NONE), RunOptions.DEFAULT, null)).reason() == RejectReason.PROTECTED,
                    "a move reaching into the chunk");
            // A move whose cells land in the chunk is refused too.
            check(refusal(() -> h.service.run(builder, new OpSpec.Move(ringRegion, new dev.sculptory.core.BlockPos(8, 8, 8),
                    Transform.IDENTITY, air, EntityFilter.NONE), RunOptions.DEFAULT, null)).reason() == RejectReason.PROTECTED,
                    "a move into the chunk");
        } catch (EditRejected e) {
            ProtectionHook.clear(builder);
            throw new GameTestAssertException("unexpected refusal: " + e.getMessage());
        } catch (RuntimeException e) {
            ProtectionHook.clear(builder);
            throw e;
        }
        context.startSequence()
                .thenWaitUntil(() -> check(copied.finished() && stack.result != null, "copy and stack running"))
                .thenWaitUntil(() -> {
                    check(copied.get("ring copy").cells() == ring.size(), "ring copy cells");
                    check(stack.result.outcome() == JobOutcome.COMPLETED && stack.result.changed() > 0
                            && stack.result.skippedProtected() == 0, "ring stack " + stack.result);
                    run(h, builder, new OpSpec.Move(ringRegion, new dev.sculptory.core.BlockPos(0, 8, 0), Transform.IDENTITY,
                            air, EntityFilter.NONE), move);
                })
                .thenWaitUntil(() -> check(move.result != null, "move running"))
                .thenWaitUntil(() -> {
                    check(move.result.outcome() == JobOutcome.COMPLETED && move.result.skippedProtected() == 0,
                            "ring move " + move.result);
                    run(h, builder, new OpSpec.Fill(ringRegion, new Pattern.Single(stone), CellMask.ANY), fill);
                })
                .thenWaitUntil(() -> check(fill.result != null, "fill running"))
                .thenWaitUntil(() -> {
                    check(fill.result.changed() == ring.size() && fill.result.skippedProtected() == 0, "ring fill " + fill.result);
                    run(h, builder, new OpSpec.Fill(reaching, new Pattern.Single(stone), CellMask.ANY), partial);
                })
                .thenWaitUntil(() -> check(partial.result != null, "partial fill running"))
                .thenWaitUntil(() -> {
                    ProtectionHook.clear(builder);
                    check(partial.result.skippedProtected() == 1 && partial.result.changed() == 0,
                            "a fill reaching into the chunk skips its one protected cell: " + partial.result);
                    check(!world.getBlockState(pos(x0 + 20, 101, z0 + 20)).is(net.minecraft.world.level.block.Blocks.STONE),
                            "the protected cell was written");
                    forceChunks(world, area, false);
                    h.close();
                })
                .thenSucceed();
    }

    // =================================================================== uploads through the network layer

    /**
     * A selection uploaded through the dispatcher and named by its hash: an op on a set the server does not hold is
     * refused {@code SELECTION_NOT_LOADED}; after the upload ({@code SelectionReady}) a fill runs on exactly its cells
     * and a copy holds exactly them. Unresolved uploads and oversized shapes are refused by the services themselves.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_region_upload", timeoutTicks = LIMIT)
    public void anUploadedSelectionIsResolvedAndUnknownOnesRefused(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 628);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 99, z0, x0 + 31, 110, z0 + 31);
        loadAndForce(world, area);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        paint(h, writer, box(x0 + 2, 100, z0 + 2, x0 + 17, 105, z0 + 15));
        CellSet set = selection(x0, z0);
        Region.Uploaded reference = new Region.Uploaded(set.hash(), set.bounds(), set.size());
        WorldSnapshot original = capture(world, area);
        int stone = raw(h, "minecraft:stone");
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));

        // The services refuse what the network layer did not resolve, and shapes too large to count.
        check(refusal(() -> h.service.run(h.player, new OpSpec.Erase(reference, CellMask.ANY), RunOptions.DEFAULT, null))
                .reason() == RejectReason.SELECTION_NOT_LOADED, "an unresolved upload");
        check(refusal(() -> clips.copy(h.player, reference, set.bounds().min(), false, CellMask.ANY, EntityFilter.NONE,
                null, new Captured<>()))
                .reason() == RejectReason.SELECTION_NOT_LOADED, "an unresolved upload copied");
        Region.Shape vast = new Region.Shape(box(x0, -60, z0, x0 + 20_000, 300, z0 + 20_000), ShapeKind.CONE, Facing.UP);
        check(refusal(() -> h.service.run(h.player, new OpSpec.Fill(vast, new Pattern.Single(stone), CellMask.ANY),
                RunOptions.DEFAULT, null)).reason() == RejectReason.TOO_LARGE, "a shape of too many rows");

        ServerDispatcher<ServerPlayer> dispatcher = new ServerDispatcher<>(h.service, clips, ScatterService.disabled(), h.runtime.permissions(),
                () -> Limits.DEFAULTS, h.runtime::states, System::nanoTime);
        Transport transport = new Transport(h);
        NetSession<ServerPlayer> session = dispatcher.open(transport);
        transport.receive(dispatcher, session, Handshake.hello("test", Features.of(Features.REGION_OPS, Features.CLIPBOARD)));
        OpSpec.Fill fill = new OpSpec.Fill(reference, new Pattern.Single(stone), CellMask.ANY);
        transport.receive(dispatcher, session, new C2S.RunOp(1, fill, false, ConflictPolicy.SKIP_CONFLICTS));
        check(transport.sent.contains(new S2C.JobRejected(1, RejectReason.SELECTION_NOT_LOADED)),
                "an unknown set is not loaded: " + transport.sent);

        byte[] bytes = set.encode();
        transport.receive(dispatcher, session, new C2S.SelectionUpload(2, set.hash(), set.bounds(), set.size(), bytes.length));
        S2C.UploadGrant grant = transport.first(S2C.UploadGrant.class);
        StreamSender sender = new StreamSender(grant.streamId(), StreamKind.SELECTION_UPLOAD, bytes, new TreeMap<>(),
                StreamSender.MAX_C2S_CHUNK, grant.creditBytes());
        for (Message m : sender.poll(bytes.length)) transport.receive(dispatcher, session, (C2S) m);
        check(sender.done(), "the whole set fits one poll");
        context.startSequence()
                .thenWaitUntil(() -> check(!transport.sent(S2C.SelectionReady.class).isEmpty(),
                        "waiting for the set: " + transport.sent))
                .thenWaitUntil(() -> {
                    check(transport.first(S2C.SelectionReady.class).equals(new S2C.SelectionReady(2, set.hash())),
                            "ready " + transport.sent);
                    transport.receive(dispatcher, session, new C2S.RunOp(3, fill, false, ConflictPolicy.SKIP_CONFLICTS));
                    check(transport.sent(S2C.JobAccepted.class).stream().anyMatch(a -> a.reqId() == 3
                            && a.estCells() == set.size()), "the fill was not accepted: " + transport.sent);
                })
                .thenWaitUntil(() -> check(!transport.sent(S2C.JobFinished.class).isEmpty(), "fill running"))
                .thenWaitUntil(() -> {
                    S2C.JobFinished finished = transport.first(S2C.JobFinished.class);
                    check(finished.outcome() == JobOutcome.COMPLETED && finished.changed() == set.size(), "fill " + finished);
                    checkCells(world, area, (x, y, z) -> set.contains(x, y, z) ? stone : original.get(x, y, z),
                            "a fill of the uploaded set");
                    transport.receive(dispatcher, session, new C2S.Copy(4, reference, set.bounds().min(), false, CellMask.ANY,
                            EntityFilter.NONE));
                })
                .thenWaitUntil(() -> check(!transport.sent(S2C.ClipboardReady.class).isEmpty(), "copy running"))
                .thenWaitUntil(() -> {
                    S2C.ClipboardReady ready = transport.first(S2C.ClipboardReady.class);
                    check(ready.reqId() == 4 && ready.cells() == set.size(), "copy " + ready);
                    dispatcher.close(session);
                    check(session.selections().size() == 0, "the store outlived the connection");
                    forceChunks(world, area, false);
                    h.close();
                })
                .thenSucceed();
    }

    /** A transport collecting what the dispatcher sends, decoded. */
    private static final class Transport implements FabricTransport {
        final Harness h;
        final List<S2C> sent = new ArrayList<>();

        Transport(Harness h) {
            this.h = h;
        }

        void receive(ServerDispatcher<ServerPlayer> dispatcher, NetSession<ServerPlayer> session, C2S message) {
            try {
                dispatcher.receive(session, Codec.encodeC2S(message, h.runtime.states()));
            } catch (ProtocolException e) {
                throw new GameTestAssertException("encode: " + e.getMessage());
            }
        }

        <T> T first(Class<T> type) {
            List<T> all = sent(type);
            if (all.isEmpty()) throw new GameTestAssertException("no " + type.getSimpleName() + " sent: " + sent);
            return all.get(0);
        }

        <T> List<T> sent(Class<T> type) {
            return sent.stream().filter(type::isInstance).map(type::cast).toList();
        }

        @Override
        public ServerPlayer player() {
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
                throw new GameTestAssertException("the server sent an undecodable frame: " + e.getMessage());
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
            throw new GameTestAssertException("disconnected: " + reason);
        }
    }
}
