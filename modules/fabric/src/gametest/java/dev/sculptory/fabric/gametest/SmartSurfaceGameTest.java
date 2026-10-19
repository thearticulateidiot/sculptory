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
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.GeneratedTooLargeException;
import dev.sculptory.core.generate.GroundFill;
import dev.sculptory.core.generate.GroundMap;
import dev.sculptory.core.generate.SmartSurfaceKernel;
import dev.sculptory.core.generate.SparseUpload;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.mask.BlockSet;
import dev.sculptory.core.mask.BoundMask;
import dev.sculptory.core.mask.EditMask;
import dev.sculptory.core.mask.MaskEntry;
import dev.sculptory.core.mask.MaskRule;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.gametest.ClipTestSupport.Captured;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.SnapshotWorld;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.OpLabel;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.impl.EditMasks;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.platform.WriteOptions;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;

/**
 * Generate's Smart Surface on the server: the kernel's sheet, uploaded as a generated clipboard and pasted with the
 * {@code SMART_SURFACE} label as the client sends it, writes exactly its cells as one undo step and undoes exactly;
 * Keep Existing (a paste into air) leaves the blocks there; the global mask is honoured; a player without
 * {@code region} and a sheet over the caps are refused with nothing changed; Extend to Ground fills through trees and
 * under overhangs. Region slots 2200-2204.
 */
public final class SmartSurfaceGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /**
     * Stone from y 100 to 103 over the area's columns, and a stone mound up to y 107 near the middle; returns that
     * ground per column (the brushes' surface: the topmost stone).
     */
    private static GroundMap ground(Harness h, Box area, int x0, int z0) {
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        int stone = h.state("minecraft:stone");
        GroundMap ground = new GroundMap();
        for (int x = area.min().x(); x <= area.max().x(); x++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                int dx = x - (x0 + 12), dz = z - (z0 + 14);
                int top = 103 + Math.max(0, 4 - (int) Math.sqrt(dx * dx + dz * dz) / 2);
                for (int y = 100; y <= top; y++) writer.write(x, y, z, stone, null);
                ground.put(x, z, top);
            }
        }
        return ground;
    }

    /** A non-convex outline over the area with one inside point well above it. */
    private static SmartSurfaceKernel.Shape shape(int x0, int z0, boolean faceted) {
        return new SmartSurfaceKernel.Shape(List.of(
                new BlockPos(x0 + 2, 110, z0 + 2), new BlockPos(x0 + 28, 108, z0 + 3), new BlockPos(x0 + 18, 111, z0 + 14),
                new BlockPos(x0 + 27, 114, z0 + 28), new BlockPos(x0 + 4, 112, z0 + 26)),
                List.of(new BlockPos(x0 + 10, 118, z0 + 14)), faceted);
    }

    private static GeneratedSource sheet(Harness h, SmartSurfaceKernel.Shape shape, SmartSurfaceKernel.Fill fill,
                                         GroundMap ground) {
        SmartSurfaceKernel.Surface surface = SmartSurfaceKernel.surface(shape);
        return SmartSurfaceKernel.generate(surface, fill, ground, h.world.getBottomY(), h.world.getTopY(), 1 << 20)
                .source();
    }

    /** Uploads {@code source} as {@code player}'s generated clipboard; the reply lands in the returned capture. */
    private static Captured<ClipboardService.ClipboardInfo> upload(Harness h, ServerPlayerEntity player,
                                                                    GeneratedSource source) {
        byte[] payload = SparseUpload.encode(source, h.runtime.states());
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(h.context));
        Captured<ClipboardService.ClipboardInfo> reply = new Captured<>();
        try {
            clips.beginGeneratedUpload(player, source.bounds(), source.cells(), payload.length).completed(payload, reply);
        } catch (EditRejected e) {
            throw new GameTestException("upload refused: " + e.getMessage());
        }
        return reply;
    }

    /** The paste the client sends after the upload, labelled Smart surface. */
    private static RecordingListener paste(Harness h, ClipboardService.ClipboardInfo info, Box bounds,
                                           PasteOptions options) {
        RecordingListener listener = new RecordingListener();
        try {
            h.service.run(h.player, new OpSpec.Paste(new SourceRef.Clipboard(info.clipboardId()), bounds.min(),
                    Transform.IDENTITY, options), new RunOptions(false, ConflictPolicy.SKIP_CONFLICTS,
                    OpLabel.SMART_SURFACE), listener);
        } catch (EditRejected e) {
            throw new GameTestException("paste refused: " + e.getMessage());
        }
        return listener;
    }

    /** Every cell of {@code area} holds {@code expected}'s state. */
    private static void checkWorld(Harness h, Box area, WorldSnapshot expected, String what) {
        WorldSnapshot now = capture(h.world, area);
        for (int y = area.min().y(); y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    int want = expected.get(x, y, z), got = now.get(x, y, z);
                    if (want != got) {
                        throw new GameTestException(what + " at " + x + "," + y + "," + z + ": "
                                + Block.getStateFromRawId(got) + " instead of " + Block.getStateFromRawId(want));
                    }
                }
            }
        }
    }

    /** {@code before} with {@code source}'s cells written where {@code writes} accepts them. */
    private static WorldSnapshot expected(Harness h, Box area, GeneratedSource source, CellRule writes) {
        WorldSnapshot expected = capture(h.world, area);
        WorldSnapshot before = capture(h.world, area);
        source.forEach((x, y, z, state) -> {
            if (writes.accepts(x, y, z, before.get(x, y, z))) expected.states[expected.index(x, y, z)] = state;
        });
        return expected;
    }

    @FunctionalInterface
    private interface CellRule {
        boolean accepts(int x, int y, int z, int before);
    }

    /**
     * Smooth, Extend to Ground, grass over dirt: the paste writes exactly the sheet's cells (down to the stone, the
     * mound included), as one history entry named Smart surface; undo restores every cell.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_smart_surface_build", tickLimit = LIMIT)
    public void aSheetBuildsAsOneStepAndUndoesExactly(TestContext context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 2200);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 98, z0, x0 + 31, 124, z0 + 31);
        loadAndForce(h.world, area);
        GroundMap ground = ground(h, area, x0, z0);
        SmartSurfaceKernel.Fill fill = new SmartSurfaceKernel.Fill(new Pattern.Single(h.state("minecraft:grass_block")),
                1, new Pattern.Single(h.state("minecraft:dirt")), true, 1, true);
        GeneratedSource source = sheet(h, shape(x0, z0, false), fill, ground);
        check(source.contains(x0 + 10, 118, z0 + 14), "through the inside point");
        check(source.contains(x0 + 12, 108, z0 + 14), "down to the mound (ground 107)");
        check(!source.contains(x0 + 12, 107, z0 + 14), "not into the mound");
        WorldSnapshot before = capture(h.world, area);
        WorldSnapshot expected = expected(h, area, source, (x, y, z, b) -> true);
        Captured<ClipboardService.ClipboardInfo> uploaded = upload(h, h.player, source);
        RecordingListener[] pasted = new RecordingListener[1];
        RecordingListener undo = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(uploaded.finished(), "upload running"))
                .createAndAdd(() -> pasted[0] = paste(h, uploaded.get("the upload"), source.bounds(),
                        new PasteOptions(true, false, false)))
                .createAndAdd(() -> check(pasted[0].result != null, "paste running"))
                .createAndAdd(() -> {
                    check(pasted[0].result.outcome() == JobOutcome.COMPLETED, "paste " + pasted[0].result);
                    checkWorld(h, area, expected, "the sheet");
                    check(h.history().undoLabel().contains("Smart surface"), "history " + h.history().undoLabel());
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0,
                            "undo " + undo.result);
                    checkSame(before, capture(h.world, area), "after the undo");
                    forceChunks(h.world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Keep Existing: a faceted sheet three thick pasted into air leaves the glass and the mound's stone it reaches
     * through as they are and writes the rest; undo is exact.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_smart_surface_keep", tickLimit = LIMIT)
    public void keepExistingLeavesTheBlocksThere(TestContext context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 2201);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 98, z0, x0 + 31, 124, z0 + 31);
        loadAndForce(h.world, area);
        ground(h, area, x0, z0);
        int glass = h.state("minecraft:glass");
        SmartSurfaceKernel.Fill fill = SmartSurfaceKernel.Fill.of(new Pattern.Single(h.state("minecraft:oak_planks")));
        fill = new SmartSurfaceKernel.Fill(fill.top(), 1, fill.body(), false, 3, true);
        GeneratedSource source = sheet(h, shape(x0, z0, true), fill, null);
        // Glass in a few of the sheet's cells.
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        int[] placed = {0};
        source.forEach((x, y, z, state) -> {
            if ((x + 2 * z + y) % 7 == 0) {
                writer.write(x, y, z, glass, null);
                placed[0]++;
            }
        });
        check(placed[0] > 10, "glass placed");
        int air = h.state("minecraft:air");
        WorldSnapshot before = capture(h.world, area);
        WorldSnapshot expected = expected(h, area, source, (x, y, z, b) -> b == air);
        Captured<ClipboardService.ClipboardInfo> uploaded = upload(h, h.player, source);
        RecordingListener[] pasted = new RecordingListener[1];
        RecordingListener undo = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(uploaded.finished(), "upload running"))
                .createAndAdd(() -> pasted[0] = paste(h, uploaded.get("the upload"), source.bounds(),
                        new PasteOptions(true, false, false).withInto(PasteOptions.Into.AIR)))
                .createAndAdd(() -> check(pasted[0].result != null, "paste running"))
                .createAndAdd(() -> {
                    check(pasted[0].result.outcome() == JobOutcome.COMPLETED, "paste " + pasted[0].result);
                    checkWorld(h, area, expected, "the sheet into air");
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.outcome() == JobOutcome.COMPLETED, "undo " + undo.result);
                    checkSame(before, capture(h.world, area), "after the undo");
                    forceChunks(h.world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** Under the global mask "on top of stone" only the sheet's cells resting on stone are written; undo is exact. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_smart_surface_mask", tickLimit = LIMIT)
    public void theGlobalMaskIsHonoured(TestContext context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 2202);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 98, z0, x0 + 31, 124, z0 + 31);
        loadAndForce(h.world, area);
        GroundMap ground = ground(h, area, x0, z0);
        SmartSurfaceKernel.Fill fill = SmartSurfaceKernel.Fill.of(new Pattern.Single(h.state("minecraft:moss_block")));
        fill = new SmartSurfaceKernel.Fill(fill.top(), 1, fill.body(), true, 1, true);
        GeneratedSource source = sheet(h, shape(x0, z0, false), fill, ground);
        try {
            EditMasks.set(h.player.getUuid(), new EditMask(List.of(MaskEntry.of(new MaskRule.OnTopOf(
                    BlockSet.parse("minecraft:stone")))), false), region -> region, h.runtime.states());
        } catch (EditRejected e) {
            throw new GameTestException("mask refused: " + e.getMessage());
        }
        BoundMask mask;
        try {
            mask = EditMasks.current(h.player.getUuid());
        } catch (EditRejected e) {
            throw new GameTestException("mask refused: " + e.getMessage());
        }
        WorldSnapshot before = capture(h.world, area);
        SnapshotWorld reader = new SnapshotWorld(h.world, h.runtime.states(), area);
        WorldSnapshot expected = expected(h, area, source, (x, y, z, b) -> mask.test(x, y, z, b, reader));
        long written = source.cells() - countSame(source, expected, before);
        check(written > 50 && written < source.cells(), "the mask keeps some cells and drops others: " + written);
        Captured<ClipboardService.ClipboardInfo> uploaded = upload(h, h.player, source);
        RecordingListener[] pasted = new RecordingListener[1];
        RecordingListener undo = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(uploaded.finished(), "upload running"))
                .createAndAdd(() -> pasted[0] = paste(h, uploaded.get("the upload"), source.bounds(),
                        new PasteOptions(true, false, false)))
                .createAndAdd(() -> check(pasted[0].result != null, "paste running"))
                .createAndAdd(() -> {
                    check(pasted[0].result.outcome() == JobOutcome.COMPLETED, "paste " + pasted[0].result);
                    checkWorld(h, area, expected, "the masked sheet");
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.outcome() == JobOutcome.COMPLETED, "undo " + undo.result);
                    checkSame(before, capture(h.world, area), "after the undo");
                    EditMasks.reset(h.player.getUuid());
                    forceChunks(h.world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Extend to Ground by the shared rule ({@code GroundFill.groundUnder} from under each surface cell, as the client
     * reads it): over a tree of real logs and leaves the sheet fills through the canopy and the trunk to the stone, and
     * under a planks overhang down to the stone beneath it. Pasted with Keep Existing (into air) the tree and the
     * overhang stay and the air around them is filled; undo is exact.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_smart_surface_trees", tickLimit = LIMIT)
    public void extendToGroundFillsThroughTreesAndUnderOverhangsAndKeepExistingKeepsThem(TestContext context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 2204);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 98, z0, x0 + 31, 124, z0 + 31);
        loadAndForce(h.world, area);
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        int stone = h.state("minecraft:stone"), log = h.state("minecraft:oak_log"),
                leaves = h.state("minecraft:oak_leaves[persistent=true]"), planks = h.state("minecraft:oak_planks");
        for (int x = area.min().x(); x <= area.max().x(); x++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int y = 100; y <= 103; y++) writer.write(x, y, z, stone, null);
            }
        }
        // A tree: a trunk from 104 to 109 under a 3 x 3 crown of leaves at 108-110; a planks roof at 115 over a corner.
        int tx = x0 + 10, tz = z0 + 10;
        for (int y = 104; y <= 109; y++) writer.write(tx, y, tz, log, null);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int y = 108; y <= 110; y++) {
                    if (dx != 0 || dz != 0 || y == 110) writer.write(tx + dx, y, tz + dz, leaves, null);
                }
            }
        }
        for (int x = x0 + 18; x <= x0 + 24; x++) {
            for (int z = z0 + 18; z <= z0 + 24; z++) writer.write(x, 115, z, planks, null);
        }
        SmartSurfaceKernel.Shape flat = new SmartSurfaceKernel.Shape(List.of(new BlockPos(x0 + 4, 112, z0 + 4),
                new BlockPos(x0 + 26, 112, z0 + 4), new BlockPos(x0 + 26, 112, z0 + 26), new BlockPos(x0 + 4, 112, z0 + 26)),
                List.of(), false);
        SmartSurfaceKernel.Surface surface = SmartSurfaceKernel.surface(flat);
        SnapshotWorld reader = new SnapshotWorld(h.world, h.runtime.states(), area);
        GroundMap ground = GroundFill.groundUnder(surface.tops(), reader, 1 << 20);
        check(ground != null, "within the budget");
        check(ground.ground(tx, tz) == 103 && ground.ground(tx + 1, tz) == 103, "the ground under the tree is the stone");
        check(ground.ground(x0 + 20, z0 + 20) == 103, "under the overhang: the stone beneath");
        check(GroundFill.groundUnder(surface.tops(), reader, 100) == null, "a fill deeper than the budget stops the look");
        SmartSurfaceKernel.Fill fill = new SmartSurfaceKernel.Fill(new Pattern.Single(h.state("minecraft:grass_block")),
                1, new Pattern.Single(h.state("minecraft:dirt")), true, 1, true);
        GeneratedSource source = SmartSurfaceKernel.generate(surface, fill, ground, h.world.getBottomY(),
                h.world.getTopY(), 1 << 20).source();
        for (int y = 104; y <= 112; y++) {
            check(source.contains(tx, y, tz) && source.contains(tx + 1, y, tz), "through the tree at y " + y);
            check(source.contains(x0 + 20, y, z0 + 20), "under the overhang at y " + y);
        }
        int air = h.state("minecraft:air");
        WorldSnapshot before = capture(h.world, area);
        WorldSnapshot expected = expected(h, area, source, (x, y, z, b) -> b == air);
        check(expected.get(tx, 105, tz) == log && expected.get(tx + 1, 109, tz) == leaves, "the reference keeps the tree");
        Captured<ClipboardService.ClipboardInfo> uploaded = upload(h, h.player, source);
        RecordingListener[] pasted = new RecordingListener[1];
        RecordingListener undo = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(uploaded.finished(), "upload running"))
                .createAndAdd(() -> pasted[0] = paste(h, uploaded.get("the upload"), source.bounds(),
                        new PasteOptions(true, false, false).withInto(PasteOptions.Into.AIR)))
                .createAndAdd(() -> check(pasted[0].result != null, "paste running"))
                .createAndAdd(() -> {
                    check(pasted[0].result.outcome() == JobOutcome.COMPLETED, "paste " + pasted[0].result);
                    checkWorld(h, area, expected, "the sheet around the tree");
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.outcome() == JobOutcome.COMPLETED, "undo " + undo.result);
                    checkSame(before, capture(h.world, area), "after the undo");
                    forceChunks(h.world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** The source cells whose expected state is the state before (left out by the rule). */
    private static long countSame(GeneratedSource source, WorldSnapshot expected, WorldSnapshot before) {
        long[] same = {0};
        source.forEach((x, y, z, state) -> {
            if (expected.get(x, y, z) == before.get(x, y, z) && state != before.get(x, y, z)) same[0]++;
        });
        return same[0];
    }

    /**
     * Refusals change nothing: a player without {@code region} can't upload the sheet; a sheet over the generated-cell
     * cap is refused by the kernel before any cell, and one announced over the clipboard limit by the server (for a player
     * without {@code limit.bypass}).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_smart_surface_refusals", tickLimit = LIMIT)
    public void refusalsChangeNothing(TestContext context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 2203);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 98, z0, x0 + 31, 124, z0 + 31);
        loadAndForce(h.world, area);
        GroundMap ground = ground(h, area, x0, z0);
        ServerPlayerEntity noRegion = h.addPlayer(false);
        EditTestSupport.grant(noRegion, Perm.USE, Perm.CLIPBOARD);
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.CLIPBOARD, Perm.REGION);
        SmartSurfaceKernel.Fill fill = new SmartSurfaceKernel.Fill(new Pattern.Single(h.state("minecraft:dirt")), 1,
                new Pattern.Single(h.state("minecraft:dirt")), true, 1, true);
        GeneratedSource source = sheet(h, shape(x0, z0, false), fill, ground);
        WorldSnapshot before = capture(h.world, area);
        byte[] payload = SparseUpload.encode(source, h.runtime.states());
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        EditRejected noNode = refusal(() -> clips.beginGeneratedUpload(noRegion, source.bounds(), source.cells(),
                payload.length));
        check(noNode.reason() == RejectReason.NO_PERMISSION, "without region: " + noNode.reason());
        check(h.service.clipboards().get(noRegion.getUuid()).isEmpty(), "a clipboard was made without region");

        SmartSurfaceKernel.Surface surface = SmartSurfaceKernel.surface(shape(x0, z0, false));
        boolean refused = false;
        try {
            SmartSurfaceKernel.generate(surface, fill, ground, h.world.getBottomY(), h.world.getTopY(), source.cells() - 1);
        } catch (GeneratedTooLargeException e) {
            refused = true;
        }
        check(refused, "one cell over the cap is refused");
        long limit = h.runtime.config().limits.maxClipboardVolume;
        Box vast = box(x0, -60, z0, x0 + 511, 300, z0 + 511);
        check(vast.volume() > limit, "the box holds the limit");
        EditRejected tooLarge = refusal(() -> clips.beginGeneratedUpload(builder, vast, limit + 1, 100));
        check(tooLarge.reason() == RejectReason.TOO_LARGE, "over the clipboard limit: " + tooLarge.reason());
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    checkSame(before, capture(h.world, area), "after the refusals");
                    forceChunks(h.world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }
}
