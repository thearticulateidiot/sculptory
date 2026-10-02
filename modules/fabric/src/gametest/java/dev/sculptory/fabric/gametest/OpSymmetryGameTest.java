package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.deny;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpRegions;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.OpSymmetry;
import dev.sculptory.core.edit.PasteGeometry;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.gametest.ClipTestSupport.Captured;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobTicket;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.impl.ServerClipboards;
import dev.sculptory.server.platform.WriteOptions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;

/**
 * Symmetry for the Select operations and the Place tool on a real server (regionCorner slots 760-799): Fill, Replace, Erase, Hollow, Walls and Paste under every mode
 * against a brute-force model with exact undo and redo, the original winning where copies overlap at the plane,
 * moves and stacks of the image regions' own content, and the rules: a copy beyond the world limit refused, a copy in
 * a protected chunk skipped while the rest writes, a copy in an unloaded chunk refused, a copy's box locked while the
 * job runs, and one history step per symmetric op.
 */
public final class OpSymmetryGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    // =================================================================== helpers

    /** The state a cell must hold, as a raw state id. */
    @FunctionalInterface
    private interface Expected {
        int at(int x, int y, int z);
    }

    private static void checkCells(ServerWorld world, Box area, Expected expected, String what) {
        WorldSnapshot now = capture(world, area);
        for (int y = area.min().y(); y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    int want = expected.at(x, y, z), got = now.get(x, y, z);
                    if (want != got) {
                        throw new GameTestException(what + " at " + x + "," + y + "," + z + ": "
                                + Block.getStateFromRawId(got) + " instead of " + Block.getStateFromRawId(want));
                    }
                }
            }
        }
    }

    /** Wool of four colours by position (symmetric blocks: turning changes nothing but the place). */
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

    private static JobTicket run(Harness h, ServerPlayerEntity player, OpSpec op, RecordingListener listener) {
        try {
            return h.service.run(player, op, RunOptions.DEFAULT, listener);
        } catch (EditRejected e) {
            throw new GameTestException(op.getClass().getSimpleName() + " refused: " + e.getMessage());
        }
    }

    private static EditRejected refusal(ClipboardGameTest.ThrowingRun run) {
        return ClipboardGameTest.refusal(run);
    }

    /** {@code raw} turned as the copy under {@code image} turns it (vanilla's mirror, then rotation). */
    private static int turned(int raw, Symmetry.Image image) {
        Transform t = image.transform();
        BlockState state = Block.getStateFromRawId(raw);
        state = state.mirror(ClipboardGameTest.vanilla(t.mirror())).rotate(ClipboardGameTest.vanilla(t.quarterTurnsCw()));
        return Block.getRawIdFromState(state);
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

    /** The cells a region op's copies cover, with what each copy writes: the first copy covering a cell decides it. */
    private static Map<Long, Integer> covered(OpSpec op, Box area, CoverRule rule) {
        Map<Long, Integer> cells = new HashMap<>();
        for (OpSymmetry.Copy copy : OpSymmetry.copies(op)) {
            Region region = OpRegions.region(copy.op());
            Box b = region.bounds();
            for (int y = Math.max(b.min().y(), area.min().y()); y <= Math.min(b.max().y(), area.max().y()); y++) {
                for (int z = b.min().z(); z <= b.max().z(); z++) {
                    for (int x = b.min().x(); x <= b.max().x(); x++) {
                        if (!region.contains(x, y, z)) continue;
                        int state = rule.state(region, copy.image(), x, y, z);
                        if (state < 0) continue;
                        cells.putIfAbsent(net.minecraft.util.math.BlockPos.asLong(x, y, z), state);
                    }
                }
            }
        }
        return cells;
    }

    /** What a copy writes at a region cell, or -1 for nothing. */
    @FunctionalInterface
    private interface CoverRule {
        int state(Region region, Symmetry.Image image, int x, int y, int z);
    }

    private static Expected expected(Map<Long, Integer> covered, WorldSnapshot before) {
        return (x, y, z) -> {
            Integer state = covered.get(net.minecraft.util.math.BlockPos.asLong(x, y, z));
            return state != null ? state : before.get(x, y, z);
        };
    }

    // =================================================================== region ops

    /**
     * Fill with east-facing stairs under every mode (the copies' stairs face the mirrored or turned way), then
     * Replace, Hollow, Walls and Erase of a cone with Mirror both ways and Rotate 4: each cell as the brute-force model
     * says, one history step per op, every step undone exactly and the last redone exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_op_symmetry_region", tickLimit = LIMIT)
    public void regionOpsUnderEveryModeMatchTheModelAndUndoExactly(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 760);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 99, z0, x0 + 63, 118, z0 + 63);
        loadAndForce(world, area);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        paint(h, writer, box(x0, 100, z0, x0 + 63, 101, z0 + 63));
        WorldSnapshot original = capture(world, area);
        Region.Shape cone = new Region.Shape(box(x0 + 4, 100, z0 + 6, x0 + 19, 112, z0 + 17), ShapeKind.CONE, Facing.EAST);
        // A block corner: both parities alike, so Rotate 4 takes it as it is.
        int x2 = 2 * (x0 + 26), z2 = 2 * (z0 + 26);
        int east = h.state("minecraft:oak_stairs[facing=east]"), stone = h.state("minecraft:stone");
        int glass = h.state("minecraft:glass"), air = h.state("minecraft:air");
        List<Symmetry> modes = new ArrayList<>();
        for (Symmetry.Mode mode : Symmetry.Mode.values()) {
            if (mode != Symmetry.Mode.OFF) modes.add(new Symmetry(mode, x2, z2));
        }
        int historyBefore = h.history().undoLabels().size();
        var runner = context.createTimedTaskRunner();
        for (Symmetry symmetry : modes) {
            RecordingListener fill = new RecordingListener(), undo = new RecordingListener();
            OpSpec.Fill op = new OpSpec.Fill(cone, new Pattern.Single(east), CellMask.ANY, symmetry);
            long cells = cone.cellCount() * OpSymmetry.copyCount(op);
            runner.createAndAdd(() -> {
                        JobTicket ticket = run(h, h.player, op, fill);
                        check(ticket.estimatedCells() == cells, symmetry + ": estimate " + ticket.estimatedCells() + " of " + cells);
                    })
                    .createAndAdd(() -> check(fill.result != null, symmetry + " fill running"))
                    .createAndAdd(() -> {
                        check(fill.result.outcome() == JobOutcome.COMPLETED && fill.result.changed() == cells,
                                symmetry + " fill " + fill.result);
                        checkCells(world, area, expected(covered(op, area, (r, image, x, y, z) -> turned(east, image)),
                                original), symmetry + " fill");
                        check(h.history().undoLabels().size() == historyBefore + 1, "history " + h.history().undoLabels());
                        h.undo(undo);
                    })
                    .createAndAdd(() -> check(undo.result != null, symmetry + " undo running"))
                    .createAndAdd(() -> {
                        check(undo.result.skippedConflicts() == 0, symmetry + " undo " + undo.result);
                        checkSame(original, capture(world, area), "after undoing the " + symmetry + " fill");
                    });
        }
        Symmetry both = new Symmetry(Symmetry.Mode.MIRROR_XZ, x2, z2), turns = new Symmetry(Symmetry.Mode.ROTATE_4, x2, z2);
        OpSpec.Fill fillStone = new OpSpec.Fill(cone, new Pattern.Single(stone), CellMask.ANY, both);
        OpSpec.Replace replace = new OpSpec.Replace(cone, new CellMask.Blocks(List.of(
                new dev.sculptory.core.NamespacedId("minecraft:stone"))), new Pattern.Single(glass), turns);
        OpSpec.Hollow hollow = new OpSpec.Hollow(cone, 2, new Pattern.Single(air), both);
        OpSpec.Walls walls = new OpSpec.Walls(cone, 1, new Pattern.Single(east), turns);
        OpSpec.Erase erase = new OpSpec.Erase(cone, CellMask.ANY, both);
        List<OpSpec> ops = List.of(fillStone, replace, hollow, walls, erase);
        WorldSnapshot[] steps = new WorldSnapshot[ops.size() + 1];
        steps[0] = original;
        for (int i = 0; i < ops.size(); i++) {
            int step = i;
            OpSpec op = ops.get(i);
            RecordingListener listener = new RecordingListener();
            runner.createAndAdd(() -> run(h, h.player, op, listener))
                    .createAndAdd(() -> check(listener.result != null, op.getClass().getSimpleName() + " running"))
                    .createAndAdd(() -> {
                        check(listener.result.outcome() == JobOutcome.COMPLETED, op + " " + listener.result);
                        WorldSnapshot before = steps[step];
                        CoverRule rule = switch (op) {
                            case OpSpec.Fill f -> (r, image, x, y, z) -> stone;
                            case OpSpec.Replace f -> (r, image, x, y, z) -> before.get(x, y, z) == stone ? glass : -1;
                            case OpSpec.Hollow f -> (r, image, x, y, z) -> inside(r, x, y, z, 2) ? air : -1;
                            case OpSpec.Walls f -> (r, image, x, y, z) -> wall(r, x, y, z, 1) ? turned(east, image) : -1;
                            case OpSpec.Erase f -> (r, image, x, y, z) -> air;
                            default -> throw new GameTestException("unexpected " + op);
                        };
                        checkCells(world, area, expected(covered(op, area, rule), before), op.getClass().getSimpleName());
                        steps[step + 1] = capture(world, area);
                        check(h.history().undoLabels().size() == historyBefore + step + 1, "history " + h.history().undoLabels());
                    });
        }
        // Undo the last op, redo it, then undo all five back to the original.
        RecordingListener undoLast = new RecordingListener(), redoLast = new RecordingListener();
        runner.createAndAdd(() -> h.undo(undoLast))
                .createAndAdd(() -> check(undoLast.result != null, "undo running"))
                .createAndAdd(() -> {
                    checkSame(steps[ops.size() - 1], capture(world, area), "after undoing the erase");
                    h.redo(redoLast);
                })
                .createAndAdd(() -> check(redoLast.result != null, "redo running"))
                .createAndAdd(() -> {
                    check(redoLast.result.skippedConflicts() == 0, "redo " + redoLast.result);
                    checkSame(steps[ops.size()], capture(world, area), "after redoing the erase");
                });
        for (int i = ops.size(); i >= 1; i--) {
            int step = i;
            RecordingListener undo = new RecordingListener();
            runner.createAndAdd(() -> h.undo(undo))
                    .createAndAdd(() -> check(undo.result != null, "undo " + step + " running"))
                    .createAndAdd(() -> {
                        check(undo.result.skippedConflicts() == 0, "undo " + step + " " + undo.result);
                        checkSame(steps[step - 1], capture(world, area), "after undo " + step);
                    });
        }
        runner.createAndAdd(() -> {
            forceChunks(world, area, false);
            h.close();
        }).completeIfSuccessful();
    }

    /**
     * A box straddling the mirror plane filled with east-facing stairs: where the copy overlaps the original the
     * original wins (east), the rest of the copy faces west, the fill counts each cell once, and the undo is exact.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_op_symmetry_overlap", tickLimit = LIMIT)
    public void theOriginalWinsWhereTheCopyOverlapsIt(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 762);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 99, z0, x0 + 31, 106, z0 + 15);
        loadAndForce(world, area);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        paint(h, writer, box(x0, 100, z0, x0 + 31, 101, z0 + 15));
        WorldSnapshot original = capture(world, area);
        int east = h.state("minecraft:oak_stairs[facing=east]"), west = h.state("minecraft:oak_stairs[facing=west]");
        // The box x0+4..x0+12 and the plane at x0+10 (x2 = 2 (x0 + 10)): its mirror is x0+7..x0+15.
        Box box = box(x0 + 4, 102, z0 + 2, x0 + 12, 104, z0 + 6);
        OpSpec.Fill fill = new OpSpec.Fill(new Region.Cuboid(box), new Pattern.Single(east), CellMask.ANY,
                new Symmetry(Symmetry.Mode.MIRROR_X, 2 * (x0 + 10), 0));
        RecordingListener listener = new RecordingListener(), undo = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    JobTicket ticket = run(h, h.player, fill, listener);
                    check(ticket.estimatedCells() == 2 * box.volume(), "estimate " + ticket.estimatedCells());
                })
                .createAndAdd(() -> check(listener.result != null, "fill running"))
                .createAndAdd(() -> {
                    long union = 12L * 3 * 5; // x0+4..x0+15
                    check(listener.result.outcome() == JobOutcome.COMPLETED && listener.result.changed() == union,
                            "each cell written once: " + listener.result);
                    checkCells(world, area, (x, y, z) -> {
                        if (y < 102 || y > 104 || z < z0 + 2 || z > z0 + 6) return original.get(x, y, z);
                        if (x >= x0 + 4 && x <= x0 + 12) return east;
                        if (x >= x0 + 7 && x <= x0 + 15) return west;
                        return original.get(x, y, z);
                    }, "overlap");
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.skippedConflicts() == 0, "undo " + undo.result);
                    checkSame(original, capture(world, area), "after undoing the straddling fill");
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    // =================================================================== paste, move and stack

    /**
     * A copied box of wool with a stair and a chest pasted with Mirror both ways and with Rotate 4: every copy is the
     * exact image of the paste (positions through the paste geometry, states through vanilla's mirror and rotation,
     * the chest's diamonds kept), each paste one history step, both undone exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_op_symmetry_paste", tickLimit = LIMIT)
    public void pastesUnderSymmetryAreExactImages(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 764);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 99, z0, x0 + 63, 112, z0 + 63);
        loadAndForce(world, area);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        Box source = box(x0 + 2, 100, z0 + 2, x0 + 6, 102, z0 + 4);
        paint(h, writer, source);
        writer.write(x0 + 2, 101, z0 + 2, h.state("minecraft:oak_stairs[facing=east]"), null);
        writer.write(x0 + 6, 100, z0 + 4, h.state("minecraft:chest[facing=north]"), null);
        ((net.minecraft.block.entity.ChestBlockEntity) world.getBlockEntity(pos(x0 + 6, 100, z0 + 4)))
                .setStack(0, new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND, 5));
        WorldSnapshot sourceContent = capture(world, source);
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        BlockPos anchor = source.min();
        Captured<ClipboardService.ClipboardInfo> copied = ClipboardGameTest.copy(clips, h.player, source, anchor);
        BlockPos size = new BlockPos(source.sizeX(), source.sizeY(), source.sizeZ());
        int x2 = 2 * (x0 + 30) + 1, z2 = 2 * (z0 + 30) + 1;
        List<OpSpec.Paste> pastes = new ArrayList<>();
        RecordingListener[] listeners = new RecordingListener[2];
        WorldSnapshot[] before = new WorldSnapshot[3];
        var runner = context.createTimedTaskRunner();
        runner.createAndAdd(() -> check(copied.finished(), "copy running"))
                .createAndAdd(() -> {
                    ClipboardService.ClipboardInfo info = copied.get("the copy");
                    Transform t = Transform.rotation(1);
                    pastes.add(new OpSpec.Paste(new SourceRef.Clipboard(info.clipboardId()), new BlockPos(x0 + 12, 104, z0 + 14), t,
                            PasteOptions.DEFAULT, new Symmetry(Symmetry.Mode.MIRROR_XZ, x2, z2)));
                    pastes.add(new OpSpec.Paste(new SourceRef.Clipboard(info.clipboardId()), new BlockPos(x0 + 8, 107, z0 + 20),
                            new Transform(0, dev.sculptory.core.transform.Mirror.X), PasteOptions.DEFAULT,
                            new Symmetry(Symmetry.Mode.ROTATE_4, x2, z2)));
                    before[0] = capture(world, area);
                    listeners[0] = new RecordingListener();
                    run(h, h.player, pastes.get(0), listeners[0]);
                });
        for (int i = 0; i < 2; i++) {
            int step = i;
            runner.createAndAdd(() -> check(listeners[step].result != null, "paste " + step + " running"))
                    .createAndAdd(() -> {
                        OpSpec.Paste paste = pastes.get(step);
                        check(listeners[step].result.outcome() == JobOutcome.COMPLETED, "paste " + listeners[step].result);
                        Map<Long, Integer> covered = new HashMap<>();
                        Map<Long, Integer> diamonds = new HashMap<>();
                        for (OpSymmetry.Copy copy : OpSymmetry.copies(paste)) {
                            OpSpec.Paste c = (OpSpec.Paste) copy.op();
                            Box target = PasteGeometry.pasteTarget(size, BlockPos.ORIGIN, c.t(), c.origin());
                            for (int lx = 0; lx < size.x(); lx++) {
                                for (int ly = 0; ly < size.y(); ly++) {
                                    for (int lz = 0; lz < size.z(); lz++) {
                                        int raw = sourceContent.get(source.min().x() + lx, source.min().y() + ly, source.min().z() + lz);
                                        BlockState state = Block.getStateFromRawId(raw)
                                                .mirror(ClipboardGameTest.vanilla(c.t().mirror()))
                                                .rotate(ClipboardGameTest.vanilla(c.t().quarterTurnsCw()));
                                        int x = target.min().x() + c.t().mapX(lx, lz, size.x(), size.z());
                                        int z = target.min().z() + c.t().mapZ(lx, lz, size.x(), size.z());
                                        int y = target.min().y() + ly;
                                        long key = net.minecraft.util.math.BlockPos.asLong(x, y, z);
                                        covered.putIfAbsent(key, Block.getRawIdFromState(state));
                                        if (state.isOf(net.minecraft.block.Blocks.CHEST)) diamonds.putIfAbsent(key, 5);
                                    }
                                }
                            }
                        }
                        checkCells(world, area, expected(covered, before[step]), "paste " + step);
                        for (long key : diamonds.keySet()) {
                            net.minecraft.util.math.BlockPos chest = net.minecraft.util.math.BlockPos.fromLong(key);
                            check(((net.minecraft.block.entity.ChestBlockEntity) world.getBlockEntity(chest)).getStack(0).getCount() == 5,
                                    "the copied chest at " + chest + " keeps its diamonds");
                        }
                        before[step + 1] = capture(world, area);
                        if (step == 0) {
                            listeners[1] = new RecordingListener();
                            run(h, h.player, pastes.get(1), listeners[1]);
                        }
                    });
        }
        RecordingListener undo1 = new RecordingListener(), undo2 = new RecordingListener();
        runner.createAndAdd(() -> {
                    check(h.history().undoLabels().size() == 2, "one step per paste: " + h.history().undoLabels());
                    h.undo(undo1);
                })
                .createAndAdd(() -> check(undo1.result != null, "undo 1 running"))
                .createAndAdd(() -> {
                    checkSame(before[1], capture(world, area), "after undoing the second paste");
                    h.undo(undo2);
                })
                .createAndAdd(() -> check(undo2.result != null, "undo 2 running"))
                .createAndAdd(() -> {
                    check(undo2.result.skippedConflicts() == 0, "undo " + undo2.result);
                    checkSame(before[0], capture(world, area), "after undoing the first paste");
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A stack of a painted box with Mirror east/west copies the mirror box's own content along the mirrored step, and
     * a turned move with Rotate 2 lands the image region's content on the image of the destination (a stair turned
     * with it); both undo exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_op_symmetry_move_stack", tickLimit = LIMIT)
    public void movesAndStacksWorkOnTheImageRegionsOwnContent(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 766);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 99, z0, x0 + 63, 116, z0 + 47);
        loadAndForce(world, area);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        paint(h, writer, box(x0, 100, z0, x0 + 63, 102, z0 + 47));
        writer.write(x0 + 5, 103, z0 + 5, h.state("minecraft:oak_stairs[facing=east]"), null);
        WorldSnapshot original = capture(world, area);
        int air = h.state("minecraft:air");
        Box box = box(x0 + 2, 101, z0 + 3, x0 + 8, 103, z0 + 7);
        Region.Cuboid region = new Region.Cuboid(box);
        OpSpec.Stack stack = new OpSpec.Stack(region, 0, 3, 0, 2, EntityFilter.NONE, new Symmetry(Symmetry.Mode.MIRROR_X, 2 * (x0 + 20), 0));
        OpSpec.Move move = new OpSpec.Move(region, new BlockPos(3, 6, 9), Transform.rotation(1), new Pattern.Single(air),
                EntityFilter.NONE, new Symmetry(Symmetry.Mode.ROTATE_2, 2 * (x0 + 20) + 1, 2 * (z0 + 20) + 1));
        RecordingListener stacked = new RecordingListener(), moved = new RecordingListener();
        RecordingListener undoMove = new RecordingListener(), undoStack = new RecordingListener();
        WorldSnapshot[] afterStack = new WorldSnapshot[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> run(h, h.player, stack, stacked))
                .createAndAdd(() -> check(stacked.result != null, "stack running"))
                .createAndAdd(() -> {
                    check(stacked.result.outcome() == JobOutcome.COMPLETED, "stack " + stacked.result);
                    Map<Long, Integer> covered = new HashMap<>();
                    for (OpSymmetry.Copy copy : OpSymmetry.copies(stack)) {
                        OpSpec.Stack c = (OpSpec.Stack) copy.op();
                        Box b = c.region().bounds();
                        for (int k = 1; k <= c.count(); k++) {
                            for (int x = b.min().x(); x <= b.max().x(); x++) {
                                for (int y = b.min().y(); y <= b.max().y(); y++) {
                                    for (int z = b.min().z(); z <= b.max().z(); z++) {
                                        long key = net.minecraft.util.math.BlockPos.asLong(x + k * c.dx(), y + k * c.dy(), z + k * c.dz());
                                        // Within a copy the later stack copy wins; across copies the earlier op copy.
                                        if (copy.image() == Symmetry.Image.IDENTITY || !covered.containsKey(key)) {
                                            covered.put(key, original.get(x, y, z));
                                        }
                                    }
                                }
                            }
                        }
                    }
                    checkCells(world, area, expected(covered, original), "stack");
                    afterStack[0] = capture(world, area);
                    run(h, h.player, move, moved);
                })
                .createAndAdd(() -> check(moved.result != null, "move running"))
                .createAndAdd(() -> {
                    check(moved.result.outcome() == JobOutcome.COMPLETED, "move " + moved.result);
                    Map<Long, Integer> covered = new HashMap<>();
                    for (OpSymmetry.Copy copy : OpSymmetry.copies(move)) {
                        OpSpec.Move c = (OpSpec.Move) copy.op();
                        Box b = c.region().bounds();
                        Map<Long, Integer> own = new HashMap<>();
                        for (int x = b.min().x(); x <= b.max().x(); x++) {
                            for (int y = b.min().y(); y <= b.max().y(); y++) {
                                for (int z = b.min().z(); z <= b.max().z(); z++) own.put(net.minecraft.util.math.BlockPos.asLong(x, y, z), air);
                            }
                        }
                        BlockPos destMin = b.min().add(c.offset());
                        for (int x = b.min().x(); x <= b.max().x(); x++) {
                            for (int y = b.min().y(); y <= b.max().y(); y++) {
                                for (int z = b.min().z(); z <= b.max().z(); z++) {
                                    int lx = x - b.min().x(), lz = z - b.min().z();
                                    int tx = destMin.x() + c.t().mapX(lx, lz, b.sizeX(), b.sizeZ());
                                    int tz = destMin.z() + c.t().mapZ(lx, lz, b.sizeX(), b.sizeZ());
                                    BlockState state = Block.getStateFromRawId(afterStack[0].get(x, y, z))
                                            .mirror(ClipboardGameTest.vanilla(c.t().mirror()))
                                            .rotate(ClipboardGameTest.vanilla(c.t().quarterTurnsCw()));
                                    own.put(net.minecraft.util.math.BlockPos.asLong(tx, y + c.offset().y(), tz), Block.getRawIdFromState(state));
                                }
                            }
                        }
                        for (Map.Entry<Long, Integer> entry : own.entrySet()) covered.putIfAbsent(entry.getKey(), entry.getValue());
                    }
                    checkCells(world, area, expected(covered, afterStack[0]), "move");
                    h.undo(undoMove);
                })
                .createAndAdd(() -> check(undoMove.result != null, "undo move running"))
                .createAndAdd(() -> {
                    check(undoMove.result.skippedConflicts() == 0, "undo move " + undoMove.result);
                    checkSame(afterStack[0], capture(world, area), "after undoing the move");
                    h.undo(undoStack);
                })
                .createAndAdd(() -> check(undoStack.result != null, "undo stack running"))
                .createAndAdd(() -> {
                    check(undoStack.result.skippedConflicts() == 0, "undo stack " + undoStack.result);
                    checkSame(original, capture(world, area), "after undoing the stack");
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    // =================================================================== rules

    /**
     * A copy beyond the world limit is refused {@code INVALID} (the centre inside the world), a copy in an unloaded
     * chunk is refused {@code UNLOADED} for a player who may not load chunks, a copy's box is locked while the job
     * runs (a cut over it is {@code AREA_BUSY}), and a copy in a protected chunk is skipped while the original writes,
     * the whole op one history step that undoes exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_op_symmetry_rules", tickLimit = LIMIT)
    public void copiesFollowTheWorldLimitLoadingLockAndProtectionRules(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 768);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 99, z0, x0 + 63, 110, z0 + 31);
        loadAndForce(world, area);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        paint(h, writer, box(x0, 100, z0, x0 + 63, 101, z0 + 31));
        WorldSnapshot original = capture(world, area);
        int stone = h.state("minecraft:stone");
        Box box = box(x0 + 4, 102, z0 + 4, x0 + 11, 104, z0 + 11);
        Region.Cuboid region = new Region.Cuboid(box);
        Pattern pattern = new Pattern.Single(stone);

        // Beyond the world limit: a centre inside the world (x 29,900,000) whose mirror copy lands beyond it.
        EditRejected beyond = refusal(() -> h.service.run(h.player, new OpSpec.Fill(region, pattern, CellMask.ANY,
                new Symmetry(Symmetry.Mode.MIRROR_X, 2 * 29_900_000, 0)), RunOptions.DEFAULT, null));
        check(beyond.reason() == RejectReason.INVALID, "a copy beyond the world limit: " + beyond.reason());
        check(h.history().undoLabels().isEmpty(), "nothing was pushed");

        // An unloaded chunk: the mirror plane 3,000 blocks east puts the copy in chunks nobody loaded.
        ServerPlayerEntity grounded = h.addPlayer();
        deny(grounded, Perm.EDIT_UNLOADED);
        EditRejected unloaded = refusal(() -> h.service.run(grounded, new OpSpec.Fill(region, pattern, CellMask.ANY,
                new Symmetry(Symmetry.Mode.MIRROR_X, 2 * (x0 + 3000), 0)), RunOptions.DEFAULT, null));
        check(unloaded.reason() == RejectReason.UNLOADED, "a copy in an unloaded chunk: " + unloaded.reason());
        checkSame(original, capture(world, area), "after the refusals");

        // The copy's box is locked while the job runs: a cut over it is AREA_BUSY.
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 2 * (x0 + 20), 0);
        Box copyBox = mirror.imageBox(Symmetry.Image.MIRROR_X, box);
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        RecordingListener fill = new RecordingListener();
        run(h, h.player, new OpSpec.Fill(region, pattern, CellMask.ANY, mirror), fill);
        ServerPlayerEntity other = h.addPlayer();
        check(h.runtime.executor().isLockedFor(world, copyBox, other.getUuid()), "the copy's box is locked");
        EditRejected busy = refusal(() -> clips.copy(other, new Region.Cuboid(copyBox), copyBox.min(), true, CellMask.ANY,
                EntityFilter.NONE, new RecordingListener(), new Captured<>()));
        check(busy.reason() == RejectReason.AREA_BUSY, "a cut over the copy's box: " + busy.reason());
        RecordingListener undo = new RecordingListener(), partial = new RecordingListener(), undoPartial = new RecordingListener();
        // A protected copy: the mirror box lies in chunks protected from the player.
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(fill.result != null, "fill running"))
                .createAndAdd(() -> {
                    check(fill.result.outcome() == JobOutcome.COMPLETED && fill.result.changed() == 2 * box.volume(),
                            "fill " + fill.result);
                    checkCells(world, area, (x, y, z) -> box.contains(x, y, z) || copyBox.contains(x, y, z) ? stone
                            : original.get(x, y, z), "the mirrored fill");
                    check(h.history().undoLabels().size() == 1, "one step: " + h.history().undoLabels());
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.skippedConflicts() == 0, "undo " + undo.result);
                    checkSame(original, capture(world, area), "after undoing the mirrored fill");
                    ProtectionHook.protect(h.player, world, copyBox.min().x() >> 4 << 4, copyBox.min().z() >> 4 << 4,
                            (copyBox.max().x() >> 4 << 4) + 15, (copyBox.max().z() >> 4 << 4) + 15);
                    check(!world.canPlayerModifyAt(h.player, pos(copyBox.min().x(), 102, copyBox.min().z())), "the copy is protected");
                    check(world.canPlayerModifyAt(h.player, pos(box.min().x(), 102, box.min().z())), "the original is free");
                    run(h, h.player, new OpSpec.Fill(region, pattern, CellMask.ANY, mirror), partial);
                })
                .createAndAdd(() -> check(partial.result != null, "partial fill running"))
                .createAndAdd(() -> {
                    ProtectionHook.clear(h.player);
                    check(partial.result.outcome() == JobOutcome.COMPLETED && partial.result.changed() == box.volume()
                            && partial.result.skippedProtected() == box.volume(), "the protected copy is skipped: " + partial.result);
                    checkCells(world, area, (x, y, z) -> box.contains(x, y, z) ? stone : original.get(x, y, z), "the partial fill");
                    h.undo(undoPartial);
                })
                .createAndAdd(() -> check(undoPartial.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undoPartial.result.skippedConflicts() == 0, "undo " + undoPartial.result);
                    checkSame(original, capture(world, area), "after undoing the partial fill");
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A symmetric move is all or nothing across its copies: with the copy's destination protected, or the copy's
     * source, the move is refused PROTECTED, the world is unchanged and nothing is pushed to the history.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_op_symmetry_move_protected", tickLimit = LIMIT)
    public void aSymmetricMoveWithAProtectedCopyIsRefusedWhole(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 770);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 99, z0, x0 + 63, 112, z0 + 31);
        loadAndForce(world, area);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        paint(h, writer, box(x0, 100, z0, x0 + 63, 103, z0 + 31));
        WorldSnapshot original = capture(world, area);
        int air = h.state("minecraft:air");
        Box box = box(x0 + 2, 101, z0 + 2, x0 + 9, 103, z0 + 9);
        // The plane x = x0 + 20: the copy's source is x0+30..x0+37, its destination (moved 16 south) z0+18..z0+25.
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 2 * (x0 + 20), 0);
        OpSpec.Move move = new OpSpec.Move(new Region.Cuboid(box), new BlockPos(0, 0, 16), Transform.IDENTITY,
                new Pattern.Single(air), EntityFilter.NONE, mirror);
        try {
            ProtectionHook.protect(h.player, world, x0 + 16, z0 + 16, x0 + 47, z0 + 31);
            EditRejected destination = refusal(() -> h.service.run(h.player, move, RunOptions.DEFAULT, null));
            check(destination.reason() == RejectReason.PROTECTED, "the copy's destination is protected: " + destination.reason());
            ProtectionHook.protect(h.player, world, x0 + 16, z0, x0 + 47, z0 + 15);
            EditRejected source = refusal(() -> h.service.run(h.player, move, RunOptions.DEFAULT, null));
            check(source.reason() == RejectReason.PROTECTED, "the copy's source is protected: " + source.reason());
            check(h.service.jobs(h.player.getUuid()).isEmpty(), "a job was admitted");
        } finally {
            ProtectionHook.clear(h.player);
        }
        checkSame(original, capture(world, area), "after the refused moves");
        check(h.history().undoLabels().isEmpty(), "history " + h.history().undoLabels());
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    /**
     * A symmetric stack whose copy's source is protected is refused PROTECTED without {@code limit.bypass}; with it
     * the stack runs, but the job's captured tiles are untrusted: the command block in the copy's source arrives
     * without its command.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_op_symmetry_stack_protected", tickLimit = LIMIT)
    public void aSymmetricStackFromAProtectedCopySourceNeedsBypassAndIsUntrusted(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.CLIPBOARD, Perm.REGION);
        ServerPlayerEntity bypasser = h.addPlayer(false);
        EditTestSupport.grant(bypasser, Perm.USE, Perm.CLIPBOARD, Perm.REGION, Perm.LIMIT_BYPASS);
        check(!h.runtime.permissions().mayWriteOperatorNbt(bypasser), "the bypasser may write operator NBT");
        int[] at = regionCorner(context, 772);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 99, z0, x0 + 63, 112, z0 + 31);
        loadAndForce(world, area);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        paint(h, writer, box(x0, 100, z0, x0 + 63, 103, z0 + 15));
        net.minecraft.util.math.BlockPos command = pos(x0 + 33, 101, z0 + 5);
        h.runtime.writer(world, new WriteOptions(false, true))
                .write(command.getX(), command.getY(), command.getZ(), h.state("minecraft:command_block"), null);
        ((net.minecraft.block.entity.CommandBlockBlockEntity) world.getBlockEntity(command)).getCommandExecutor().setCommand("op me");
        Box box = box(x0 + 2, 101, z0 + 2, x0 + 9, 103, z0 + 9);
        // The plane x = x0 + 20: the copy's source x0+30..x0+37 lies in protected chunks; both stack 16 south.
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 2 * (x0 + 20), 0);
        OpSpec.Stack stack = new OpSpec.Stack(new Region.Cuboid(box), 0, 0, 16, 1, EntityFilter.NONE, mirror);
        RecordingListener untrusted = new RecordingListener();
        ProtectionHook.protect(builder, world, x0 + 16, z0, x0 + 47, z0 + 15);
        ProtectionHook.protect(bypasser, world, x0 + 16, z0, x0 + 47, z0 + 15);
        try {
            check(!world.canPlayerModifyAt(builder, command) && world.canPlayerModifyAt(builder, pos(x0 + 5, 101, z0 + 5)),
                    "the protection is not the copy's source alone");
            check(refusal(() -> h.service.run(builder, stack, RunOptions.DEFAULT, null)).reason() == RejectReason.PROTECTED,
                    "a non-bypass stack whose copy reads a protected source");
            check(h.history().undoLabels().isEmpty(), "nothing was pushed");
            h.service.run(bypasser, stack, RunOptions.DEFAULT, untrusted);
        } catch (EditRejected e) {
            ProtectionHook.clear(builder);
            ProtectionHook.clear(bypasser);
            throw new GameTestException("unexpected refusal: " + e.getMessage());
        }
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(untrusted.result != null, "stack running"))
                .createAndAdd(() -> {
                    ProtectionHook.clear(builder);
                    ProtectionHook.clear(bypasser);
                    check(untrusted.result.outcome() == JobOutcome.COMPLETED && untrusted.result.strippedNbt() == 1
                            && untrusted.result.changed() == 2 * box.volume(), "untrusted symmetric stack " + untrusted.result);
                    net.minecraft.block.entity.CommandBlockBlockEntity copy =
                            (net.minecraft.block.entity.CommandBlockBlockEntity) world.getBlockEntity(command.add(0, 0, 16));
                    check(copy != null && copy.getCommandExecutor().getCommand().isEmpty(),
                            "a bypass stack from a protected copy source kept the command");
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Sizes count every copy: a box of 1,048,576 cells fits {@code maxOpVolume} once, and Mirror both ways (four
     * copies) is refused TOO_LARGE for a player without {@code limit.bypass} (nothing pushed), while a player with it
     * is admitted (the job is cancelled at once, before it writes).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_op_symmetry_too_large", tickLimit = LIMIT)
    public void copiesCountAgainstTheVolumeLimit(TestContext context) {
        Harness h = new Harness(context);
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.REGION, Perm.EDIT_UNLOADED);
        int[] at = regionCorner(context, 774);
        int x0 = at[0], z0 = at[1];
        long limit = h.runtime.config().limits.maxOpVolume;
        Box box = box(x0, 0, z0, x0 + 127, 63, z0 + 127);
        check(box.volume() <= limit && 4 * box.volume() > limit, "the box fits once and not four times: " + limit);
        Pattern stone = new Pattern.Single(h.state("minecraft:stone"));
        Symmetry both = new Symmetry(Symmetry.Mode.MIRROR_XZ, 2 * (x0 + 200), 2 * (z0 + 200));
        EditRejected tooLarge = refusal(() -> h.service.run(builder, new OpSpec.Fill(new Region.Cuboid(box), stone, CellMask.ANY,
                both), RunOptions.DEFAULT, null));
        check(tooLarge.reason() == RejectReason.TOO_LARGE, "four copies: " + tooLarge.reason());
        check(h.history().undoLabels().isEmpty() && h.service.jobs(builder.getUuid()).isEmpty(), "something was admitted");
        RecordingListener admitted = new RecordingListener();
        // The op player holds limit.bypass through the op-level fallback.
        run(h, h.player, new OpSpec.Fill(new Region.Cuboid(box), stone, CellMask.ANY, both), admitted);
        check(h.service.cancelAll(h.player) == 1, "the admitted fill was cancelled");
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(admitted.result != null, "the cancelled fill is still running"))
                .createAndAdd(() -> {
                    check(admitted.result.outcome() == JobOutcome.CANCELLED && admitted.result.changed() == 0,
                            "cancelled " + admitted.result);
                    h.close();
                })
                .completeIfSuccessful();
    }
}
