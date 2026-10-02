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
import dev.sculptory.core.Sha256;
import dev.sculptory.core.brush.BrushKernel;
import dev.sculptory.core.brush.BrushKernels;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.ShapeSpec;
import dev.sculptory.core.brush.StrokeState;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.mask.BlockSet;
import dev.sculptory.core.mask.BoundMask;
import dev.sculptory.core.mask.EditMask;
import dev.sculptory.core.mask.MaskEntry;
import dev.sculptory.core.mask.MaskRule;
import dev.sculptory.core.mask.MaskedKernel;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import dev.sculptory.core.scatter.ScatterArea;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.engine.impl.ServerClipboards;
import dev.sculptory.fabric.engine.impl.ServerScatter;
import dev.sculptory.fabric.gametest.ClipTestSupport.Captured;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.SnapshotWorld;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.net.FabricTransport;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.protocol.v2.BuilderPower;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.Codec;
import dev.sculptory.protocol.v2.Features;
import dev.sculptory.protocol.v2.Handshake;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.ProtocolException;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.BuilderOutcome;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.DabOutcome;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobListener;
import dev.sculptory.server.engine.JobResult;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.impl.EditMasks;
import dev.sculptory.server.net.NetSession;
import dev.sculptory.server.net.ServerDispatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.test.TimedTaskRunner;
import net.minecraft.util.Hand;

/**
 * The global mask on every edit through the real services: fills, pastes, Move (at its
 * source), copy and cut, brush strokes (the terrain kernel and the Shape brush's parts), builder mode and scatter; each
 * judged against the world before the edit, exactly undoable, undo never masked, and a refused mask fails closed.
 * Region slots 1100-1119 (1111: the opt-in benchmark).
 */
public final class MaskGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    // ---------------------------------------------------------------- helpers

    private static MaskEntry rule(MaskRule rule) {
        return MaskEntry.of(rule);
    }

    private static MaskEntry not(MaskRule rule) {
        return new MaskEntry(rule, true);
    }

    private static BlockSet set(String text) {
        return BlockSet.parse(text);
    }

    /** Sets the player's global mask as an accepted {@code SetEditMask} would. */
    private static void mask(Harness h, ServerPlayerEntity player, MaskEntry... entries) {
        try {
            EditMasks.set(player.getUuid(), new EditMask(List.of(entries), false), region -> region, h.runtime.states());
        } catch (EditRejected e) {
            throw new GameTestException("mask refused: " + e.getMessage());
        }
    }

    private static void mask(Harness h, MaskEntry... entries) {
        mask(h, h.player, entries);
    }

    private static BoundMask bound(Harness h) {
        try {
            return EditMasks.current(h.player.getUuid());
        } catch (EditRejected e) {
            throw new GameTestException("mask refused: " + e.getMessage());
        }
    }

    private static RecordingListener run(Harness h, OpSpec op) {
        RecordingListener listener = new RecordingListener();
        try {
            h.service.run(h.player, op, RunOptions.DEFAULT, listener);
        } catch (EditRejected e) {
            throw new GameTestException(op.getClass().getSimpleName() + " refused: " + e.getMessage());
        }
        return listener;
    }

    private static net.minecraft.util.math.BlockPos pos(int x, int y, int z) {
        return new net.minecraft.util.math.BlockPos(x, y, z);
    }

    private static int at(ServerWorld world, int x, int y, int z) {
        return Block.getRawIdFromState(world.getBlockState(pos(x, y, z)));
    }

    private static void done(Harness h, Box area) {
        EditMasks.reset(h.player.getUuid());
        forceChunks(h.world, area, false);
        h.close();
    }

    /** The first cell of {@code area} whose state differs from {@code expected} (indexed like a snapshot), or "". */
    private static String differs(ServerWorld world, Box area, WorldSnapshot expected) {
        int n = 0;
        String first = "";
        for (int y = area.min().y(); y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    int want = expected.get(x, y, z), got = at(world, x, y, z);
                    if (want == got) continue;
                    if (n++ == 0) {
                        first = x + "," + y + "," + z + ": expected " + Block.getStateFromRawId(want) + ", got "
                                + Block.getStateFromRawId(got);
                    }
                }
            }
        }
        return n == 0 ? "" : n + " cells differ, first " + first;
    }

    /** The world as a snapshot of {@code area}, with the cells {@code accepted} (by the mask) set to {@code state}. */
    private static WorldSnapshot expectFill(Harness h, Box area, Box region, int state, BoundMask mask) {
        WorldSnapshot before = capture(h.world, area);
        SnapshotWorld reader = new SnapshotWorld(h.world, h.runtime.states(), area);
        WorldSnapshot expected = capture(h.world, area);
        for (int y = region.min().y(); y <= region.max().y(); y++) {
            for (int z = region.min().z(); z <= region.max().z(); z++) {
                for (int x = region.min().x(); x <= region.max().x(); x++) {
                    if (mask.test(x, y, z, before.get(x, y, z), reader)) expected.states[expected.index(x, y, z)] = state;
                }
            }
        }
        return expected;
    }

    // ---------------------------------------------------------------- fill

    /**
     * A fill under "sits on top of stone" and "not next to gold", over two chunks and two sections: only the layer on
     * the old floor is written (a cell on a block the fill itself wrote is not on top of stone), gold's neighbours across
     * the chunk edge are left alone, and undo restores the area exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mask_fill", tickLimit = LIMIT)
    public void aFillIsJudgedAgainstTheWorldBeforeItAcrossChunksAndSections(TestContext context) {
        Harness h = new Harness(context);
        int[] corner = regionCorner(context, 1100);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 108, z0, x0 + 31, 136, z0 + 15);
        loadAndForce(h.world, area);
        BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
        int stone = h.state("minecraft:stone"), gold = h.state("minecraft:gold_block"), glass = h.state("minecraft:glass");
        for (int x = x0; x <= x0 + 31; x++) {
            for (int z = z0; z <= z0 + 15; z++) writer.write(x, 111, z, stone, null);
        }
        // Gold on the floor right at the chunk edge (x0 + 16 is the next chunk's first column).
        writer.write(x0 + 16, 112, z0 + 5, gold, null);
        mask(h, rule(new MaskRule.OnTopOf(set("minecraft:stone"))), not(new MaskRule.NextTo(set("minecraft:gold_block"))));
        Box region = box(x0 + 2, 112, z0 + 2, x0 + 29, 134, z0 + 13);
        WorldSnapshot before = capture(h.world, area);
        WorldSnapshot expected = expectFill(h, area, region, glass, bound(h));
        check(expected.get(x0 + 15, 112, z0 + 5) != glass && expected.get(x0 + 17, 112, z0 + 5) != glass,
                "the reference keeps gold's neighbours");
        RecordingListener fill = run(h, new OpSpec.Fill(new Region.Cuboid(region), new Pattern.Single(glass), CellMask.ANY));
        RecordingListener undo = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(fill.result != null, "fill running"))
                .createAndAdd(() -> {
                    check(fill.result.outcome() == JobOutcome.COMPLETED, "fill " + fill.result);
                    String diff = differs(h.world, area, expected);
                    check(diff.isEmpty(), "masked fill: " + diff);
                    check(fill.result.changed() == 28 * 12 - 4, "changed " + fill.result.changed());
                    check(at(h.world, x0 + 5, 113, z0 + 5) == h.state("minecraft:air"), "the second layer was written");
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0,
                            "undo " + undo.result);
                    checkSame(before, capture(h.world, area), "after undo");
                    done(h, area);
                })
                .completeIfSuccessful();
    }

    // ---------------------------------------------------------------- paste

    /** A paste under "is air" lands only in the air cells of its box; the dirt there stays. Undo is exact. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mask_paste", tickLimit = LIMIT)
    public void aPasteIsJudgedWhereItLands(TestContext context) {
        Harness h = new Harness(context);
        int[] corner = regionCorner(context, 1101);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 99, z0, x0 + 15, 106, z0 + 15);
        loadAndForce(h.world, area);
        BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
        int dirt = h.state("minecraft:dirt"), planks = h.state("minecraft:oak_planks");
        for (int x = x0; x <= x0 + 7; x++) {
            for (int z = z0; z <= z0 + 7; z++) {
                if ((x + z) % 3 == 0) writer.write(x, 101, z, dirt, null);
            }
        }
        Clipboard.Builder source = Clipboard.builder(h.runtime.states(), new BlockPos(8, 3, 8));
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 3; y++) {
                for (int z = 0; z < 8; z++) source.set(x, y, z, planks);
            }
        }
        java.util.UUID id = h.service.clipboards().install(h.player.getUuid(), source.build()).id();
        mask(h, rule(new MaskRule.Is(set("minecraft:air"))));
        WorldSnapshot before = capture(h.world, area);
        WorldSnapshot expected = expectFill(h, area, box(x0, 100, z0, x0 + 7, 102, z0 + 7), planks, bound(h));
        RecordingListener paste = run(h, new OpSpec.Paste(new SourceRef.Clipboard(id), new BlockPos(x0, 100, z0),
                Transform.IDENTITY, PasteOptions.DEFAULT));
        RecordingListener undo = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(paste.result != null, "paste running"))
                .createAndAdd(() -> {
                    check(paste.result.outcome() == JobOutcome.COMPLETED, "paste " + paste.result);
                    String diff = differs(h.world, area, expected);
                    check(diff.isEmpty(), "masked paste: " + diff);
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    checkSame(before, capture(h.world, area), "after undo");
                    done(h, area);
                })
                .completeIfSuccessful();
    }

    // ---------------------------------------------------------------- Move: at the source

    /**
     * A Move under "is stone" lifts only the stone: the dirt stays where it was, the landing cells of the dirt keep
     * their sand, the stone lands over sand (landing writes are not masked), nothing is duplicated; undo is exact.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mask_move", tickLimit = LIMIT)
    public void aMoveLiftsOnlyTheMatchingBlocks(TestContext context) {
        Harness h = new Harness(context);
        int[] corner = regionCorner(context, 1102);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 99, z0, x0 + 31, 104, z0 + 15);
        loadAndForce(h.world, area);
        BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
        int stone = h.state("minecraft:stone"), dirt = h.state("minecraft:dirt"), sand = h.state("minecraft:sand");
        int air = h.state("minecraft:air");
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 8; z++) {
                    writer.write(x0 + x, 100 + y, z0 + z, (x + y + z) % 2 == 0 ? stone : dirt, null);
                    writer.write(x0 + 20 + x, 100 + y, z0 + z, sand, null);
                }
            }
        }
        mask(h, rule(new MaskRule.Is(set("minecraft:stone"))));
        WorldSnapshot before = capture(h.world, area);
        RecordingListener move = run(h, new OpSpec.Move(new Region.Cuboid(box(x0, 100, z0, x0 + 7, 101, z0 + 7)),
                new BlockPos(20, 0, 0), Transform.IDENTITY, new Pattern.Single(air), EntityFilter.NONE));
        RecordingListener undo = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(move.result != null, "move running"))
                .createAndAdd(() -> {
                    check(move.result.outcome() == JobOutcome.COMPLETED, "move " + move.result);
                    for (int x = 0; x < 8; x++) {
                        for (int y = 0; y < 2; y++) {
                            for (int z = 0; z < 8; z++) {
                                boolean lifted = (x + y + z) % 2 == 0;
                                check(at(h.world, x0 + x, 100 + y, z0 + z) == (lifted ? air : dirt), "source " + x + "," + z);
                                check(at(h.world, x0 + 20 + x, 100 + y, z0 + z) == (lifted ? stone : sand),
                                        "destination " + x + "," + z);
                            }
                        }
                    }
                    check(move.result.changed() == 128, "changed " + move.result.changed());
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    checkSame(before, capture(h.world, area), "after undo");
                    done(h, area);
                })
                .completeIfSuccessful();
    }

    // ---------------------------------------------------------------- copy and cut

    /**
     * Copy and Cut under "is stone" take only the stone: the clipboard holds just those cells, the cut erases just
     * those, and both give the same clipboard. The cut is undone exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mask_cut", tickLimit = LIMIT)
    public void copyAndCutTakeOnlyTheMatchingBlocks(TestContext context) {
        Harness h = new Harness(context);
        int[] corner = regionCorner(context, 1103);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 99, z0, x0 + 31, 104, z0 + 15);
        loadAndForce(h.world, area);
        BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
        int stone = h.state("minecraft:stone"), dirt = h.state("minecraft:dirt"), air = h.state("minecraft:air");
        int stones = 0;
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                boolean isStone = (x * 3 + z) % 4 < 2;
                writer.write(x0 + x, 100, z0 + z, isStone ? stone : dirt, null);
                if (isStone) stones++;
            }
        }
        int expectedCells = stones;
        mask(h, rule(new MaskRule.Is(set("minecraft:stone"))));
        Box source = box(x0, 100, z0, x0 + 7, 100, z0 + 7);
        WorldSnapshot before = capture(h.world, area);
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> copy = new Captured<>();
        Captured<ClipboardService.ClipboardInfo> cut = new Captured<>();
        RecordingListener erase = new RecordingListener();
        RecordingListener paste = new RecordingListener();
        RecordingListener undoPaste = new RecordingListener();
        RecordingListener undoCut = new RecordingListener();
        try {
            clips.copy(h.player, source, new BlockPos(x0, 100, z0), false, CellMask.ANY, null, copy);
        } catch (EditRejected e) {
            throw new GameTestException("copy refused: " + e.getMessage());
        }
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    ClipboardService.ClipboardInfo info = copy.get("copy");
                    check(info.cells() == expectedCells, "copied " + info.cells() + " cells, " + expectedCells + " are stone");
                    try {
                        clips.copy(h.player, source, new BlockPos(x0, 100, z0), true, CellMask.ANY, erase, cut);
                    } catch (EditRejected e) {
                        throw new GameTestException("cut refused: " + e.getMessage());
                    }
                })
                .createAndAdd(() -> check(erase.result != null && cut.finished(), "cut running"))
                .createAndAdd(() -> {
                    check(erase.result.outcome() == JobOutcome.COMPLETED, "erase " + erase.result);
                    check(cut.get("cut").cells() == expectedCells, "cut " + cut.value.cells() + " cells");
                    check(erase.result.changed() == expectedCells, "erased " + erase.result.changed());
                    for (int x = 0; x < 8; x++) {
                        for (int z = 0; z < 8; z++) {
                            boolean isStone = (x * 3 + z) % 4 < 2;
                            check(at(h.world, x0 + x, 100, z0 + z) == (isStone ? air : dirt), "after the cut at " + x + "," + z);
                        }
                    }
                    // The clipboard lands whole (a paste is masked where it lands: "is stone" would drop it into air).
                    EditMasks.reset(h.player.getUuid());
                    ClipboardGameTest.paste(h, h.player, cut.value.clipboardId(), new BlockPos(x0 + 20, 100, z0),
                            Transform.IDENTITY, paste);
                })
                .createAndAdd(() -> check(paste.result != null, "paste running"))
                .createAndAdd(() -> {
                    for (int x = 0; x < 8; x++) {
                        for (int z = 0; z < 8; z++) {
                            boolean isStone = (x * 3 + z) % 4 < 2;
                            check(at(h.world, x0 + 20 + x, 100, z0 + z) == (isStone ? stone : air), "pasted at " + x + "," + z);
                        }
                    }
                    h.undo(undoPaste);
                })
                .createAndAdd(() -> check(undoPaste.result != null, "undoing the paste"))
                .createAndAdd(() -> h.undo(undoCut))
                .createAndAdd(() -> check(undoCut.result != null, "undoing the cut"))
                .createAndAdd(() -> {
                    checkSame(before, capture(h.world, area), "after undoing the cut");
                    done(h, area);
                })
                .completeIfSuccessful();
    }

    /**
     * A cut under "sits on top of stone" takes and erases the same cells: the dirt layer on the stone floor, not the
     * layer above it (which sat on dirt before the cut); undo restores both layers.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mask_cut_neighbours", tickLimit = LIMIT)
    public void aCutUnderANeighbourRuleErasesWhatItCopied(TestContext context) {
        Harness h = new Harness(context);
        int[] corner = regionCorner(context, 1113);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 98, z0, x0 + 20, 104, z0 + 20);
        loadAndForce(h.world, area);
        BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
        int stone = h.state("minecraft:stone"), dirt = h.state("minecraft:dirt"), air = h.state("minecraft:air");
        for (int x = x0; x <= x0 + 20; x++) {
            for (int z = z0; z <= z0 + 20; z++) {
                writer.write(x, 99, z, stone, null);
                writer.write(x, 100, z, dirt, null);
                writer.write(x, 101, z, dirt, null);
            }
        }
        mask(h, rule(new MaskRule.OnTopOf(set("minecraft:stone"))));
        WorldSnapshot before = capture(h.world, area);
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> cut = new Captured<>();
        RecordingListener erase = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        try {
            clips.copy(h.player, box(x0 + 2, 100, z0 + 2, x0 + 17, 101, z0 + 17), new BlockPos(x0 + 2, 100, z0 + 2), true,
                    CellMask.ANY, erase, cut);
        } catch (EditRejected e) {
            throw new GameTestException("cut refused: " + e.getMessage());
        }
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(erase.result != null && cut.finished(), "cut running"))
                .createAndAdd(() -> {
                    check(cut.get("cut").cells() == 256, "cut " + cut.value.cells() + " cells");
                    check(erase.result.changed() == 256, "erased " + erase.result.changed());
                    for (int x = x0 + 2; x <= x0 + 17; x++) {
                        for (int z = z0 + 2; z <= z0 + 17; z++) {
                            check(at(h.world, x, 100, z) == air && at(h.world, x, 101, z) == dirt, "at " + x + "," + z);
                        }
                    }
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    checkSame(before, capture(h.world, area), "after undo");
                    done(h, area);
                })
                .completeIfSuccessful();
    }

    // ---------------------------------------------------------------- strokes

    /**
     * Paint and Raise strokes under a neighbour mask give exactly what the masked kernel gives on a snapshot, dab after
     * dab (the client's prediction runs the same), and the mask read at StrokeBegin holds for the whole stroke.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mask_stroke", tickLimit = LIMIT)
    public void strokesMatchTheMaskedKernel(TestContext context) {
        Harness h = new Harness(context);
        int[] corner = regionCorner(context, 1104);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 95, z0, x0 + 47, 140, z0 + 47);
        loadAndForce(h.world, area);
        StrokeGameTest.terrain(h, x0, z0, 48, 48);
        int planks = h.state("minecraft:oak_planks");
        mask(h, not(new MaskRule.NextTo(set("minecraft:oak_planks"))),
                rule(new MaskRule.Chance(70, 5L)));
        BoundMask mask = bound(h);
        SnapshotWorld snapshot = new SnapshotWorld(h.world, h.runtime.states(), area);
        List<BrushSpec> specs = List.of(
                new BrushSpec(BrushTool.PAINT, 5, 1f, Falloff.CONSTANT, Shape.CIRCLE, new Pattern.Single(planks),
                        SurfaceMask.ANY, 1, 0, 1L),
                new BrushSpec(BrushTool.RAISE, 5, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 2L));
        List<List<Dab>> strokes = new ArrayList<>();
        for (int s = 0; s < specs.size(); s++) {
            List<Dab> dabs = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                int x = x0 + 14 + i * 2 + s, z = z0 + 18 + (i * (s + 3)) % 12;
                dabs.add(new Dab(i, x * 16 + 8, 110 * 16, z * 16 + 8, 255));
            }
            strokes.add(dabs);
        }
        int[] seq = {1};
        TimedTaskRunner runner = context.createTimedTaskRunner();
        for (int s = 0; s < specs.size(); s++) {
            int current = s;
            runner.createAndAdd(() -> {
                begin(h, 70 + current, specs.get(current));
                // A mask change during the stroke does not reach it: the stroke keeps the mask of its StrokeBegin.
                if (current == 1) mask(h, rule(new MaskRule.Is(set("minecraft:bedrock"))));
                DabOutcome outcome = h.service.dabs(h.player, 70 + current, seq[0]++, strokes.get(current));
                check(outcome.accepted(), "stroke " + current + ": " + outcome);
            });
            runner.createAndAdd(() -> check(h.acks.seqs.size() == seq[0] - 1, "stroke " + current + " still applying"));
            runner.createAndAdd(() -> {
                h.service.endStroke(h.player, 70 + current);
                if (current == 1) mask(h, not(new MaskRule.NextTo(set("minecraft:oak_planks"))),
                        rule(new MaskRule.Chance(70, 5L)));
            });
        }
        String[] replay = {null};
        runner.createAndAdd(() -> {
            check(h.events.dabRejections.isEmpty(), "dabs rejected: " + h.events.dabRejections);
            if (replay[0] == null) replay[0] = replay(specs, strokes, snapshot, mask);
            check(replay[0].isEmpty(), replay[0]);
            String diff = matchesSnapshot(h, area, snapshot);
            check(diff.isEmpty(), "server and masked kernel: " + diff);
            done(h, area);
        }).completeIfSuccessful();
    }

    /**
     * The Shape brush, whose steps the server writes in parts over several ticks, under "not on top of grass" and a
     * chance: exactly what the masked Shape kernel gives in one go on a snapshot.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mask_shape", tickLimit = LIMIT)
    public void theShapeBrushsPartsMatchTheMaskedKernel(TestContext context) {
        Harness h = new Harness(context);
        int[] corner = regionCorner(context, 1105);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 90, z0, x0 + 47, 150, z0 + 47);
        loadAndForce(h.world, area);
        StrokeGameTest.terrain(h, x0, z0, 48, 48);
        mask(h, not(new MaskRule.OnTopOf(set("minecraft:grass_block"))), rule(new MaskRule.Chance(80, 9L)),
                rule(new MaskRule.Exposed()));
        BoundMask mask = bound(h);
        SnapshotWorld snapshot = new SnapshotWorld(h.world, h.runtime.states(), area);
        int glass = h.state("minecraft:glass");
        // A radius-18 sphere (about 24,000 cells): a step of two parts on the server.
        BrushSpec sphere = BrushSpec.shape(18, new ShapeSpec(ShapeSpec.Kind.SPHERE, 37, Facing.UP, ShapeSpec.Mode.PLACE, 0),
                new Pattern.Single(glass), 11L, null, Symmetry.NONE);
        List<Dab> dabs = List.of(new Dab(0, (x0 + 24) * 16 + 8, 110 * 16 + 8, (z0 + 24) * 16 + 8, 255));
        int[] seq = {1};
        TimedTaskRunner runner = context.createTimedTaskRunner();
        runner.createAndAdd(() -> {
            begin(h, 90, sphere);
            DabOutcome outcome = h.service.dabs(h.player, 90, seq[0]++, dabs);
            check(outcome.accepted(), "shape: " + outcome);
        });
        runner.createAndAdd(() -> check(h.acks.seqs.size() == seq[0] - 1, "shape still applying"));
        runner.createAndAdd(() -> h.service.endStroke(h.player, 90));
        String[] replay = {null};
        runner.createAndAdd(() -> {
            if (replay[0] == null) replay[0] = replay(List.of(sphere), List.of(dabs), snapshot, mask);
            check(replay[0].isEmpty(), replay[0]);
            String diff = matchesSnapshot(h, area, snapshot);
            check(diff.isEmpty(), "server and masked Shape kernel: " + diff);
            done(h, area);
        }).completeIfSuccessful();
    }

    private static void begin(Harness h, int strokeId, BrushSpec spec) {
        try {
            h.service.beginStroke(h.player, strokeId, spec);
        } catch (EditRejected e) {
            throw new GameTestException("stroke refused: " + e.getMessage());
        }
    }

    /** Runs each stroke's masked kernel on the snapshot, writing through; "" when each wrote something. */
    private static String replay(List<BrushSpec> specs, List<List<Dab>> strokes, SnapshotWorld snapshot, BoundMask mask) {
        for (int s = 0; s < specs.size(); s++) {
            BrushSpec spec = specs.get(s);
            BrushKernel kernel = MaskedKernel.wrap(BrushKernels.forTool(spec.tool()), mask);
            StrokeState state = new StrokeState();
            int[] writes = {0};
            try {
                for (Dab dab : strokes.get(s)) {
                    kernel.apply(spec, dab, state, snapshot, (x, y, z, handle) -> {
                        snapshot.set(x, y, z, handle);
                        writes[0]++;
                    });
                }
            } catch (RuntimeException e) {
                return spec.tool() + " failed on the snapshot: " + e;
            }
            if (writes[0] == 0) return spec.tool() + " wrote nothing on the snapshot";
        }
        return "";
    }

    private static String matchesSnapshot(Harness h, Box area, SnapshotWorld snapshot) {
        int n = 0;
        String first = "";
        for (int y = area.min().y(); y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    int server = at(h.world, x, y, z), kernel = snapshot.get(x, y, z);
                    if (server == kernel) continue;
                    if (n++ == 0) {
                        first = x + "," + y + "," + z + ": server " + Block.getStateFromRawId(server) + ", kernel "
                                + Block.getStateFromRawId(kernel);
                    }
                }
            }
        }
        return n == 0 ? "" : n + " cells differ, first " + first;
    }

    // ---------------------------------------------------------------- builder mode

    /**
     * Builder mode under "height up to floor + 1": a placement there goes ahead; one a block higher is refused
     * {@code MASKED} and writes nothing; a door whose upper half would pass the limit is refused whole; a Bulldozer drag
     * under "is dirt" skips the stone and breaks the dirt.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mask_builder", tickLimit = LIMIT)
    public void builderModeHonoursTheMask(TestContext context) {
        Harness h = new Harness(context);
        int[] corner = regionCorner(context, 1106);
        int x0 = corner[0], z0 = corner[1];
        int floor = 100;
        Box area = box(x0, floor, z0, x0 + 15, floor + 10, z0 + 15);
        loadAndForce(h.world, area);
        for (int x = x0; x <= x0 + 15; x++) {
            for (int z = z0; z <= z0 + 15; z++) {
                h.world.setBlockState(pos(x, floor, z), Blocks.STONE.getDefaultState(), 2);
                for (int y = floor + 1; y <= floor + 10; y++) h.world.setBlockState(pos(x, y, z), Blocks.AIR.getDefaultState(), 2);
            }
        }
        h.world.setBlockState(pos(x0 + 8, floor, z0 + 8), Blocks.DIRT.getDefaultState(), 2);
        h.player.refreshPositionAndAngles(x0 + 2.5, floor + 1, z0 + 2.5, -90f, 0f);
        h.player.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.STONE, 64));
        mask(h, rule(new MaskRule.Height(floor - 10, floor + 1)));
        BuilderOutcome low = h.service.builderPlace(h.player, new C2S.BuilderPlace(1, false,
                new BlockPos(x0 + 6, floor, z0 + 2), Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE));
        check(low.accepted() && low.changed() == 1, "a placement the mask accepts: " + low);
        WorldSnapshot beforeHigh = capture(h.world, area);
        int entries = h.service.historyService().undoEntries(h.player.getUuid()).size();
        BuilderOutcome high = h.service.builderPlace(h.player, new C2S.BuilderPlace(2, false,
                new BlockPos(x0 + 6, floor + 1, z0 + 2), Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE));
        check(high.refusal() == BuilderOutcome.Refusal.MASKED && high.changed() == 0, "above the mask: " + high);
        h.player.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.OAK_DOOR, 1));
        BuilderOutcome door = h.service.builderPlace(h.player, new C2S.BuilderPlace(3, false,
                new BlockPos(x0 + 10, floor, z0 + 2), Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE));
        check(door.refusal() == BuilderOutcome.Refusal.MASKED, "a door reaching above the mask: " + door);
        checkSame(beforeHigh, capture(h.world, area), "refused placements wrote something");
        check(h.service.historyService().undoEntries(h.player.getUuid()).size() == entries, "a refusal left history");

        h.player.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
        mask(h, rule(new MaskRule.Is(set("minecraft:dirt"))));
        List<BlockPos> cells = List.of(new BlockPos(x0 + 7, floor, z0 + 8), new BlockPos(x0 + 8, floor, z0 + 8),
                new BlockPos(x0 + 9, floor, z0 + 8));
        BuilderOutcome dozer = h.service.builderBreak(h.player, new C2S.BuilderBreak(4, 1, cells,
                BuilderPower.BULLDOZER.bit(), Symmetry.NONE, false, true));
        check(dozer.accepted() && dozer.changed() == 1, "Bulldozer under 'is dirt': " + dozer);
        check(h.world.getBlockState(pos(x0 + 8, floor, z0 + 8)).isAir(), "the dirt was not broken");
        check(h.world.getBlockState(pos(x0 + 7, floor, z0 + 8)).isOf(Blocks.STONE)
                && h.world.getBlockState(pos(x0 + 9, floor, z0 + 8)).isOf(Blocks.STONE), "stone was broken");
        done(h, area);
        context.complete();
    }

    // ---------------------------------------------------------------- scatter

    /** A scatter commit under "sits on top of dirt" writes only the placements standing on dirt. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mask_scatter", tickLimit = LIMIT)
    public void aScatterCommitIsJudgedWhereItLands(TestContext context) {
        Harness h = new Harness(context);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] corner = regionCorner(context, 1107);
        int x0 = corner[0], z0 = corner[1];
        Box all = ScatterGameTest.floor(h, x0, z0, 32, 32);
        BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
        int dirt = h.state("minecraft:dirt");
        for (int x = x0; x < x0 + 16; x++) {
            for (int z = z0; z < z0 + 32; z++) writer.write(x, ScatterGameTest.FLOOR_Y, z, dirt, null);
        }
        SourceRef gold = ScatterGameTest.block(h, h.player, "minecraft:gold_block");
        ScatterArea region = ScatterGameTest.region(x0, z0, x0 + 31, z0 + 31);
        ScatterGameTest.Reply reply = ScatterGameTest.preview(scatter, h.player, ScatterGameTest.request(region, 3, 7L, gold));
        RecordingListener[] commit = new RecordingListener[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    scatter.tick();
                    check(reply.finished(), "planning");
                })
                .createAndAdd(() -> {
                    long placements = reply.get("preview").placements();
                    check(placements > 20, "only " + placements + " placements");
                    mask(h, rule(new MaskRule.OnTopOf(set("minecraft:dirt"))));
                    commit[0] = ScatterGameTest.commit(h, h.player, reply.plan.planId());
                })
                .createAndAdd(() -> check(commit[0].result != null, "commit running"))
                .createAndAdd(() -> {
                    check(commit[0].result.outcome() == JobOutcome.COMPLETED, "commit " + commit[0].result);
                    List<net.minecraft.util.math.BlockPos> placed = ScatterGameTest.find(h.world, all,
                            ScatterGameTest.FLOOR_Y + 1, Blocks.GOLD_BLOCK);
                    check(!placed.isEmpty(), "nothing placed on the dirt");
                    for (net.minecraft.util.math.BlockPos p : placed) {
                        check(p.getX() < x0 + 16, "a placement on stone at " + p.toShortString());
                    }
                    check(placed.size() < reply.plan.placements(), "the mask cut nothing");
                    scatter.shutdown();
                    done(h, all);
                })
                .completeIfSuccessful();
    }

    // ---------------------------------------------------------------- failing closed

    /**
     * After a refused mask (here an inside rule on a selection the server does not hold) every edit is refused
     * {@code INVALID} "edit mask refused" and writes nothing: region ops, strokes, builder mode and copies. A mask the
     * server accepts lifts it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mask_fail_closed", tickLimit = LIMIT)
    public void aRefusedMaskFailsClosed(TestContext context) {
        Harness h = new Harness(context);
        int[] corner = regionCorner(context, 1108);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 99, z0, x0 + 15, 110, z0 + 15);
        loadAndForce(h.world, area);
        for (int x = x0; x <= x0 + 15; x++) {
            for (int z = z0; z <= z0 + 15; z++) h.world.setBlockState(pos(x, 100, z), Blocks.STONE.getDefaultState(), 2);
        }
        UUID owner = h.player.getUuid();
        Region notHeld = new Region.Uploaded(Sha256.digest(new byte[] {7}), box(x0, 100, z0, x0 + 3, 100, z0 + 3), 16);
        EditRejected refused = refusal(() -> EditMasks.set(owner, new EditMask(List.of(rule(new MaskRule.Inside(notHeld))),
                false), region -> {
                    throw new EditRejected(RejectReason.SELECTION_NOT_LOADED, "not held");
                }, h.runtime.states()));
        check(refused.reason() == RejectReason.SELECTION_NOT_LOADED, "the uploaded region: " + refused.reason());
        check(EditMasks.refused(owner), "the refusal did not fail closed");
        WorldSnapshot before = capture(h.world, area);
        OpSpec fill = new OpSpec.Fill(new Region.Cuboid(box(x0, 101, z0, x0 + 7, 102, z0 + 7)),
                new Pattern.Single(h.state("minecraft:glass")), CellMask.ANY);
        EditRejected op = refusal(() -> h.service.run(h.player, fill, RunOptions.DEFAULT, null));
        check(op.reason() == RejectReason.INVALID && EditMasks.REFUSED_DETAIL.equals(op.detail()), "fill: " + op.getMessage());
        EditRejected stroke = refusal(() -> h.service.beginStroke(h.player, 5, new BrushSpec(BrushTool.RAISE, 3, 1f,
                Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L)));
        check(stroke.reason() == RejectReason.INVALID, "stroke: " + stroke.getMessage());
        h.player.refreshPositionAndAngles(x0 + 2.5, 101, z0 + 2.5, -90f, 0f);
        h.player.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.STONE, 64));
        BuilderOutcome place = h.service.builderPlace(h.player, new C2S.BuilderPlace(1, false,
                new BlockPos(x0 + 6, 100, z0 + 2), Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE));
        check(place.refusal() == BuilderOutcome.Refusal.INVALID && place.changed() == 0, "builder: " + place);
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        EditRejected copy = refusal(() -> clips.copy(h.player, box(x0, 100, z0, x0 + 3, 100, z0 + 3),
                new BlockPos(x0, 100, z0), true, CellMask.ANY, null, new Captured<>()));
        check(copy.reason() == RejectReason.INVALID, "cut: " + copy.getMessage());
        checkSame(before, capture(h.world, area), "a refused edit wrote something");
        // An accepted mask lifts it: the fill runs, under the new mask.
        mask(h, rule(new MaskRule.Height(101, 101)));
        check(!EditMasks.refused(owner), "still refused");
        RecordingListener after = run(h, fill);
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(after.result != null, "fill running"))
                .createAndAdd(() -> {
                    check(after.result.outcome() == JobOutcome.COMPLETED && after.result.changed() == 64,
                            "fill " + after.result);
                    check(h.world.getBlockState(pos(x0, 102, z0)).isAir(), "the new mask was not applied");
                    done(h, area);
                })
                .completeIfSuccessful();
    }

    // ---------------------------------------------------------------- undo, redo and a mask change between ops

    /**
     * Two fills under different masks each take their own; undo and redo are never masked (a mask that accepts nothing
     * is on while they run), and restore the area exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mask_undo", tickLimit = LIMIT)
    public void undoAndRedoAreNeverMasked(TestContext context) {
        Harness h = new Harness(context);
        int[] corner = regionCorner(context, 1109);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 99, z0, x0 + 15, 104, z0 + 15);
        loadAndForce(h.world, area);
        int stone = h.state("minecraft:stone"), dirt = h.state("minecraft:dirt");
        int glass = h.state("minecraft:glass"), planks = h.state("minecraft:oak_planks");
        BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
        for (int x = x0; x <= x0 + 15; x++) {
            for (int z = z0; z <= z0 + 15; z++) writer.write(x, 100, z, x < x0 + 8 ? stone : dirt, null);
        }
        Region layer = new Region.Cuboid(box(x0, 100, z0, x0 + 15, 100, z0 + 15));
        WorldSnapshot before = capture(h.world, area);
        mask(h, rule(new MaskRule.Is(set("minecraft:stone"))));
        RecordingListener first = run(h, new OpSpec.Fill(layer, new Pattern.Single(glass), CellMask.ANY));
        RecordingListener[] second = new RecordingListener[1];
        RecordingListener undo1 = new RecordingListener(), undo2 = new RecordingListener(), redo = new RecordingListener();
        WorldSnapshot[] after = new WorldSnapshot[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(first.result != null, "first fill running"))
                .createAndAdd(() -> {
                    mask(h, rule(new MaskRule.Is(set("minecraft:dirt"))));
                    second[0] = run(h, new OpSpec.Fill(layer, new Pattern.Single(planks), CellMask.ANY));
                })
                .createAndAdd(() -> check(second[0].result != null, "second fill running"))
                .createAndAdd(() -> {
                    for (int x = x0; x <= x0 + 15; x++) {
                        check(at(h.world, x, 100, z0 + 3) == (x < x0 + 8 ? glass : planks), "after both fills at " + x);
                    }
                    after[0] = capture(h.world, area);
                    // A mask that accepts nothing: undo and redo must still restore every cell.
                    mask(h, rule(new MaskRule.Is(set("minecraft:bedrock"))));
                    h.undo(undo1);
                })
                .createAndAdd(() -> check(undo1.result != null, "first undo running"))
                .createAndAdd(() -> h.undo(undo2))
                .createAndAdd(() -> check(undo2.result != null, "second undo running"))
                .createAndAdd(() -> {
                    checkSame(before, capture(h.world, area), "after both undos");
                    h.redo(redo);
                })
                .createAndAdd(() -> check(redo.result != null, "redo running"))
                .createAndAdd(() -> {
                    check(redo.result.changed() == 128, "redo changed " + redo.result.changed());
                    done(h, area);
                })
                .completeIfSuccessful();
    }

    /** "Inside the selection" over a sphere: a box fill writes only the sphere's cells. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mask_inside", tickLimit = LIMIT)
    public void insideTheSelectionKeepsTheFillInside(TestContext context) {
        Harness h = new Harness(context);
        int[] corner = regionCorner(context, 1110);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 99, z0, x0 + 15, 112, z0 + 15);
        loadAndForce(h.world, area);
        Region sphere = new Region.Shape(box(x0 + 2, 100, z0 + 2, x0 + 10, 108, z0 + 10), ShapeKind.ELLIPSOID, Facing.UP);
        mask(h, rule(new MaskRule.Inside(sphere)));
        int glass = h.state("minecraft:glass");
        RecordingListener fill = run(h, new OpSpec.Fill(new Region.Cuboid(box(x0, 100, z0, x0 + 12, 110, z0 + 12)),
                new Pattern.Single(glass), CellMask.ANY));
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(fill.result != null, "fill running"))
                .createAndAdd(() -> {
                    for (int x = x0; x <= x0 + 12; x++) {
                        for (int y = 100; y <= 110; y++) {
                            for (int z = z0; z <= z0 + 12; z++) {
                                boolean inside = sphere.contains(x, y, z);
                                check((at(h.world, x, y, z) == glass) == inside, "at " + x + "," + y + "," + z);
                            }
                        }
                    }
                    done(h, area);
                })
                .completeIfSuccessful();
    }

    // ---------------------------------------------------------------- benchmark (opt-in)

    /**
     * An unmasked 2,097,152-cell fill against the same fill under three neighbour rules that accept every cell (so both
     * write the same cells): the masked fill should take at most 30% longer. Run with {@code SCULPTORY_BENCH=1}; the
     * {@code bench} lines in the log give the times.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_bench_mask", tickLimit = 200_000)
    public void benchMaskedFill(TestContext context) {
        if (BenchSupport.skipped(context, "benchMaskedFill")) return;
        Harness h = new Harness(context);
        int[] corner = regionCorner(context, 1111);
        Box region = box(corner[0], 100, corner[1], corner[0] + 127, 227, corner[1] + 127);
        loadAndForce(h.world, region);
        int glass = h.state("minecraft:glass");
        long cells = region.volume();
        // Two rounds, unmasked then masked, each fill undone; the second round is compared (the first warms up the JIT).
        int jobs = 8;
        long[] start = new long[jobs];
        long[] end = new long[jobs];
        JobResult[] results = new JobResult[jobs];
        String[] labels = new String[jobs];
        for (int i = 0; i < jobs; i++) {
            labels[i] = i % 2 == 1 ? "undo" : (i % 4 == 0 ? "unmasked fill" : "masked fill (3 neighbour rules)")
                    + (i < 4 ? " (warm-up)" : "");
        }
        TimedTaskRunner runner = context.createTimedTaskRunner();
        for (int i = 0; i < jobs; i++) {
            int k = i;
            runner.createAndAdd(() -> {
                if (k % 4 == 2) {
                    mask(h, not(new MaskRule.NextTo(set("minecraft:bedrock"))), not(new MaskRule.OnTopOf(set(
                            "minecraft:diamond_block"))), not(new MaskRule.Under(set("minecraft:emerald_block"))));
                }
                if (k % 4 == 3) EditMasks.reset(h.player.getUuid());
                JobListener listener = new JobListener() {
                    @Override
                    public void progress(UUID job, long done, long total, Phase phase) {}

                    @Override
                    public void finished(JobResult r) {
                        end[k] = System.nanoTime();
                        results[k] = r;
                    }
                };
                start[k] = System.nanoTime();
                try {
                    if (k % 2 == 0) {
                        h.service.run(h.player, new OpSpec.Fill(new Region.Cuboid(region), new Pattern.Single(glass),
                                CellMask.ANY), RunOptions.DEFAULT, listener);
                    } else {
                        h.service.undo(h.player, dev.sculptory.core.history.ConflictPolicy.SKIP_CONFLICTS, listener);
                    }
                } catch (EditRejected e) {
                    throw new GameTestException(labels[k] + " refused: " + e.getMessage());
                }
            });
            runner.createAndAdd(() -> check(results[k] != null, labels[k] + " running"));
            runner.createAndAdd(() -> {
                check(results[k].outcome() == JobOutcome.COMPLETED && results[k].changed() == cells,
                        labels[k] + " " + results[k]);
                BenchSupport.log("bench mask: %s of %,d cells in %.0f ms", labels[k], cells,
                        BenchSupport.ms(end[k] - start[k]));
            });
        }
        runner.createAndAdd(() -> {
            double plain = end[4] - start[4], masked = end[6] - start[6];
            BenchSupport.log("bench mask: masked / unmasked = %s", String.format(Locale.ROOT, "%.2f", masked / plain));
            done(h, region);
        });
        runner.completeIfSuccessful();
    }
    // ---------------------------------------------------------------- the wire: SetEditMask through the dispatcher

    /**
     * Through the dispatcher: the server offers {@code edit_mask}; an accepted {@code SetEditMask} applies to the next
     * op; one refused for an uploaded selection the connection does not hold is answered {@code SELECTION_NOT_LOADED}
     * and every op after it is refused until the mask off is accepted; closing the connection resets the mask.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mask_wire", tickLimit = LIMIT)
    public void setEditMaskThroughTheDispatcher(TestContext context) {
        Harness h = new Harness(context);
        int[] corner = regionCorner(context, 1112);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 99, z0, x0 + 15, 104, z0 + 15);
        loadAndForce(h.world, area);
        for (int x = x0; x <= x0 + 15; x++) {
            for (int z = z0; z <= z0 + 15; z++) h.world.setBlockState(pos(x, 100, z), Blocks.STONE.getDefaultState(), 2);
        }
        ServerDispatcher<ServerPlayerEntity> dispatcher = new ServerDispatcher<>(h.service, h.runtime.permissions(), () -> Limits.DEFAULTS,
                h.runtime::states, System::nanoTime);
        Transport transport = new Transport(h);
        NetSession<ServerPlayerEntity> session = dispatcher.open(transport);
        transport.receive(dispatcher, session, Handshake.hello("test", Features.of(Features.REGION_OPS, Features.HISTORY,
                Features.EDIT_MASK)));
        check(transport.first(S2C.Welcome.class).features().has(Features.EDIT_MASK), "edit_mask is not offered");
        int glass = h.state("minecraft:glass");
        OpSpec fill = new OpSpec.Fill(new Region.Cuboid(box(x0, 101, z0, x0 + 7, 103, z0 + 7)), new Pattern.Single(glass),
                CellMask.ANY);
        EditMask layer = new EditMask(List.of(rule(new MaskRule.OnTopOf(set("minecraft:stone")))), false);
        transport.receive(dispatcher, session, new C2S.SetEditMask(1, layer));
        check(transport.sent.contains(S2C.EditMaskState.accepted(1)), "the mask was not accepted: " + transport.sent);
        transport.receive(dispatcher, session, new C2S.RunOp(2, fill, false, ConflictPolicy.SKIP_CONFLICTS));
        check(!transport.sent(S2C.JobAccepted.class).isEmpty(), "the op was not accepted: " + transport.sent);
        Region notHeld = new Region.Uploaded(Sha256.digest(new byte[] {9}), box(x0, 100, z0, x0 + 1, 100, z0 + 1), 4);
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    check(h.world.getBlockState(pos(x0 + 3, 101, z0 + 3)).isOf(Blocks.GLASS), "the first layer");
                    check(h.world.getBlockState(pos(x0 + 3, 102, z0 + 3)).isAir(), "the mask did not apply");
                })
                .createAndAdd(() -> {
                    transport.sent.clear();
                    transport.receive(dispatcher, session, new C2S.SetEditMask(3, new EditMask(List.of(
                            rule(new MaskRule.Inside(notHeld))), false)));
                    S2C.EditMaskState state = transport.first(S2C.EditMaskState.class);
                    check(state.reqId() == 3 && state.reason() == RejectReason.SELECTION_NOT_LOADED, "refusal " + state);
                    transport.receive(dispatcher, session, new C2S.RunOp(4, fill, false, ConflictPolicy.SKIP_CONFLICTS));
                    check(transport.sent.contains(new S2C.JobRejected(4, RejectReason.INVALID)),
                            "an op after the refusal: " + transport.sent);
                    transport.receive(dispatcher, session, new C2S.SetEditMask(5, EditMask.NONE));
                    check(transport.sent.contains(S2C.EditMaskState.accepted(5)), "the mask off: " + transport.sent);
                    check(!EditMasks.refused(h.player.getUuid()), "still failing closed");
                    transport.receive(dispatcher, session, new C2S.SetEditMask(6, layer));
                    dispatcher.close(session);
                    check(EditMasks.mask(h.player.getUuid()).isOff(), "closing the connection kept the mask");
                    done(h, area);
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
