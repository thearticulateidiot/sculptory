package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.PasteOptions.Into;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.engine.ClipboardService;
import dev.sculptory.fabric.engine.EditRejected;
import dev.sculptory.fabric.engine.JobTicket;
import dev.sculptory.fabric.engine.RunOptions;
import dev.sculptory.fabric.engine.impl.ServerClipboards;
import dev.sculptory.fabric.gametest.ClipTestSupport.Captured;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.protocol.v2.JobOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.test.TimedTaskRunner;

/**
 * The paste-into filter on a real server (regionCorner slots 860-879): a
 * paste, a move and a stack with each Into value over a mixed area of air, blocks, water and plants, every cell as
 * the rule says (fluids and plants are existing blocks), one history step each, exact undo and redo; a move with
 * Only existing blocks whose landing is partly air loses those blocks from the source until undo restores them.
 */
public final class PasteIntoGameTest implements FabricGameTest {
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

    private static boolean isAir(int raw) {
        return Block.getStateFromRawId(raw).isAir();
    }

    /** The contract's rule on the landing cell's content: EXISTING wants a block that is not air, AIR wants air. */
    private static boolean writable(Into into, int raw) {
        return switch (into) {
            case EVERYTHING -> true;
            case EXISTING -> !isAir(raw);
            case AIR -> isAir(raw);
        };
    }

    /** Air, stone, water, short grass, a poppy and glass by position: every kind of landing cell. */
    private static void mixed(Harness h, BlockWriter writer, Box box) {
        String[] kinds = {"minecraft:air", "minecraft:stone", "minecraft:water", "minecraft:short_grass",
                "minecraft:poppy", "minecraft:glass"};
        for (int x = box.min().x(); x <= box.max().x(); x++) {
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) {
                    writer.write(x, y, z, h.state(kinds[Math.floorMod(x * 3 + y * 5 + z * 7, kinds.length)]), null);
                }
            }
        }
    }

    /** Wool of four colours by position, with an air hole every fifth cell. */
    private static void paint(Harness h, BlockWriter writer, Box box) {
        String[] colours = {"minecraft:white_wool", "minecraft:red_wool", "minecraft:blue_wool", "minecraft:lime_wool"};
        for (int x = box.min().x(); x <= box.max().x(); x++) {
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) {
                    int n = x * 3 + y * 5 + z * 7;
                    String state = Math.floorMod(n, 5) == 0 ? "minecraft:air" : colours[Math.floorMod(n, 4)];
                    writer.write(x, y, z, h.state(state), null);
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

    /**
     * Adds to {@code runner}: run the op {@code op} gives, check the world against {@code expected}, undo it (exactly),
     * redo it, undo it again.
     */
    private static void runCheckUndoRedo(TimedTaskRunner runner, Harness h, Supplier<OpSpec> op, Box area,
                                         WorldSnapshot before, Expected expected, String what, int historyBefore) {
        RecordingListener listener = new RecordingListener();
        RecordingListener undo = new RecordingListener(), redo = new RecordingListener(), undoAgain = new RecordingListener();
        WorldSnapshot[] after = new WorldSnapshot[1];
        runner.createAndAdd(() -> run(h, h.player, op.get(), listener))
                .createAndAdd(() -> check(listener.result != null, what + " running"))
                .createAndAdd(() -> {
                    check(listener.result.outcome() == JobOutcome.COMPLETED, what + " " + listener.result);
                    checkCells(h.world, area, expected, what);
                    after[0] = capture(h.world, area);
                    check(h.history().undoLabels().size() == historyBefore + 1, what + " history " + h.history().undoLabels());
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, what + " undo running"))
                .createAndAdd(() -> {
                    check(undo.result.skippedConflicts() == 0, what + " undo " + undo.result);
                    checkSame(before, capture(h.world, area), what + " after undo");
                    h.redo(redo);
                })
                .createAndAdd(() -> check(redo.result != null, what + " redo running"))
                .createAndAdd(() -> {
                    check(redo.result.skippedConflicts() == 0, what + " redo " + redo.result);
                    checkSame(after[0], capture(h.world, area), what + " after redo");
                    h.undo(undoAgain);
                })
                .createAndAdd(() -> check(undoAgain.result != null, what + " second undo running"))
                .createAndAdd(() -> {
                    check(undoAgain.result.skippedConflicts() == 0, what + " second undo " + undoAgain.result);
                    checkSame(before, capture(h.world, area), what + " after the second undo");
                    check(h.history().undoLabels().size() == historyBefore, what + " history after undo");
                });
    }

    // =================================================================== paste

    /**
     * A copied box of wool with air holes pasted over the mixed area with every Into, then with Only existing blocks
     * and Include air: each cell follows its own content (water, grass and poppies take an EXISTING paste, not an AIR
     * one; with Include air the source's holes clear existing blocks and leave air alone); one history step per
     * paste, undone and redone exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_paste_into_paste", tickLimit = LIMIT)
    public void pastesWriteOnlyTheCellsIntoAllows(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 860);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 99, z0, x0 + 31, 106, z0 + 31);
        loadAndForce(world, area);
        BlockWriter writer = h.runtime.writer(world, BlockWriter.Options.DEFAULT);
        Box source = box(x0 + 2, 100, z0 + 2, x0 + 7, 102, z0 + 6);
        paint(h, writer, source);
        Box landing = box(x0 + 16, 100, z0 + 8, x0 + 21, 102, z0 + 12);
        mixed(h, writer, box(x0 + 12, 100, z0 + 4, x0 + 25, 103, z0 + 16));
        WorldSnapshot sourceContent = capture(world, source);
        WorldSnapshot original = capture(world, area);
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> copied = ClipboardGameTest.copy(clips, h.player, source, source.min());
        List<PasteOptions> variants = new ArrayList<>();
        for (Into into : Into.values()) variants.add(new PasteOptions(false, false, false, into));
        variants.add(new PasteOptions(true, false, false, Into.EXISTING));
        variants.add(new PasteOptions(true, false, false, Into.AIR));
        int historyBefore = h.history().undoLabels().size();
        TimedTaskRunner runner = context.createTimedTaskRunner();
        runner.createAndAdd(() -> check(copied.finished(), "copy running"));
        for (PasteOptions options : variants) {
            Supplier<OpSpec> paste = () -> new OpSpec.Paste(new SourceRef.Clipboard(copied.get("the copy").clipboardId()),
                    landing.min(), Transform.IDENTITY, options);
            Expected expected = (x, y, z) -> {
                int was = original.get(x, y, z);
                if (!landing.contains(x, y, z)) return was;
                int state = sourceContent.get(x - landing.min().x() + source.min().x(), y - landing.min().y()
                        + source.min().y(), z - landing.min().z() + source.min().z());
                if (isAir(state) && !options.includeAir()) return was;
                return writable(options.into(), was) ? state : was;
            };
            runCheckUndoRedo(runner, h, paste, area, original, expected, "paste " + options, historyBefore);
        }
        runner.createAndAdd(() -> {
                    // Sanity: the mixed landing has every kind of cell, so every branch above was taken.
                    boolean air = false, block = false, water = false, plant = false;
                    for (int x = landing.min().x(); x <= landing.max().x(); x++) {
                        for (int y = landing.min().y(); y <= landing.max().y(); y++) {
                            for (int z = landing.min().z(); z <= landing.max().z(); z++) {
                                int raw = original.get(x, y, z);
                                air |= isAir(raw);
                                block |= raw == h.state("minecraft:stone");
                                water |= raw == h.state("minecraft:water");
                                plant |= raw == h.state("minecraft:short_grass") || raw == h.state("minecraft:poppy");
                            }
                        }
                    }
                    check(air && block && water && plant, "the landing mixes air, blocks, water and plants");
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    // =================================================================== move

    /**
     * A wool box (with air holes) moved over the mixed area with every Into: the source is vacated whole, each
     * landing cell follows its content, and with Only existing blocks the wool that would have landed on air is gone
     * from the world until the undo brings it back; one history step per move, undone and redone exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_paste_into_move", tickLimit = LIMIT)
    public void movesFilterTheirLandingWhileTheSourceVacates(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 862);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 99, z0, x0 + 31, 106, z0 + 31);
        loadAndForce(world, area);
        BlockWriter writer = h.runtime.writer(world, BlockWriter.Options.DEFAULT);
        Box source = box(x0 + 2, 100, z0 + 2, x0 + 7, 102, z0 + 6);
        paint(h, writer, source);
        mixed(h, writer, box(x0 + 12, 100, z0 + 4, x0 + 25, 103, z0 + 16));
        WorldSnapshot original = capture(world, area);
        int air = h.state("minecraft:air");
        BlockPos offset = new BlockPos(14, 0, 6);
        int historyBefore = h.history().undoLabels().size();
        TimedTaskRunner runner = context.createTimedTaskRunner();
        for (Into into : Into.values()) {
            OpSpec.Move move = new OpSpec.Move(new Region.Cuboid(source), offset, Transform.IDENTITY, new Pattern.Single(air),
                    EntityFilter.NONE, Symmetry.NONE, into);
            Box landing = source.offset(offset.x(), offset.y(), offset.z());
            Expected expected = (x, y, z) -> {
                int was = original.get(x, y, z);
                if (landing.contains(x, y, z)) {
                    int state = original.get(x - offset.x(), y - offset.y(), z - offset.z());
                    return writable(into, was) ? state : was;
                }
                return source.contains(x, y, z) ? air : was;
            };
            runCheckUndoRedo(runner, h, () -> move, area, original, expected, "move " + into, historyBefore);
        }
        runner.createAndAdd(() -> {
            // The documented loss under EXISTING: a wool cell whose landing cell is air is nowhere after the move.
            Box landing = source.offset(offset.x(), offset.y(), offset.z());
            int lost = 0;
            for (int x = landing.min().x(); x <= landing.max().x(); x++) {
                for (int y = landing.min().y(); y <= landing.max().y(); y++) {
                    for (int z = landing.min().z(); z <= landing.max().z(); z++) {
                        int moved = original.get(x - offset.x(), y - offset.y(), z - offset.z());
                        if (!isAir(moved) && isAir(original.get(x, y, z))) lost++;
                    }
                }
            }
            check(lost > 0, "some wool lands on air, so the EXISTING move lost blocks (restored by its undo above)");
            checkSame(original, capture(world, area), "after every move was undone");
            forceChunks(world, area, false);
            h.close();
        }).completeIfSuccessful();
    }

    // =================================================================== stack

    /**
     * A wool box (with air holes) stacked twice across the mixed area with every Into: each copy's cells land where
     * their content allows (the later copy winning where copies overlap), one history step per stack, undone and
     * redone exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_paste_into_stack", tickLimit = LIMIT)
    public void stacksWriteOnlyTheCellsIntoAllows(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 864);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 99, z0, x0 + 31, 106, z0 + 31);
        loadAndForce(world, area);
        BlockWriter writer = h.runtime.writer(world, BlockWriter.Options.DEFAULT);
        Box source = box(x0 + 2, 100, z0 + 2, x0 + 7, 102, z0 + 6);
        paint(h, writer, source);
        mixed(h, writer, box(x0 + 5, 100, z0 + 8, x0 + 25, 103, z0 + 20));
        WorldSnapshot original = capture(world, area);
        int dx = 4, dz = 5;
        int historyBefore = h.history().undoLabels().size();
        TimedTaskRunner runner = context.createTimedTaskRunner();
        for (Into into : Into.values()) {
            OpSpec.Stack stack = new OpSpec.Stack(new Region.Cuboid(source), dx, 0, dz, 2, EntityFilter.NONE, Symmetry.NONE, into);
            Expected expected = (x, y, z) -> {
                int was = original.get(x, y, z);
                for (int k = 2; k >= 1; k--) {
                    int sx = x - k * dx, sz = z - k * dz;
                    if (source.contains(sx, y, sz)) return writable(into, was) ? original.get(sx, y, sz) : was;
                }
                return was;
            };
            runCheckUndoRedo(runner, h, () -> stack, area, original, expected, "stack " + into, historyBefore);
        }
        runner.createAndAdd(() -> {
            checkSame(original, capture(world, area), "after every stack was undone");
            forceChunks(world, area, false);
            h.close();
        }).completeIfSuccessful();
    }
}
