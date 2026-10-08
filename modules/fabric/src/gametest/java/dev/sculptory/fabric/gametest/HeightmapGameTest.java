package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BrushKernels;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.HeightmapSpec;
import dev.sculptory.core.brush.SculptMode;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.StrokeState;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.HeightmapAreaKernel;
import dev.sculptory.core.generate.SparseUpload;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.gametest.ClipTestSupport.Captured;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.SnapshotWorld;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.world.FabricWorldReader;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.OpLabel;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.config.SculptoryConfig;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.DabOutcome;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.impl.EditExecutor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.test.TimedTaskRunner;
import org.slf4j.LoggerFactory;

/**
 * The Heightmap brush and the Generate tool's Heightmap import on the server: strokes through the brush lane (a raised
 * ridge, a trench cut through a pond, a mirrored masked clipped ridge, a tall single stamp) write exactly what the
 * shared kernel writes on a snapshot, one entry a stroke, and undo exactly; a stroke is refused without the
 * {@code brush} node, where protected, and in an unloaded chunk; an import built through the generated upload and a
 * Paste labelled Heightmap writes exactly the kernel's cells, is one entry named Heightmap, undoes exactly, and needs
 * {@code region} and {@code clipboard}; and a radius-32 stamp's cost. Region slots 1400-1499 (1400, 1402, 1404, 1406
 * used).
 */
public final class HeightmapGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /**
     * Four strokes on rough ground with a pond and plants; every dab stays radius + 3 inside the area and its window inside
     * the area's height, so the kernel reads only the snapshot's cells. The world then equals the kernel's replay on the
     * snapshot cell for cell; each stroke made one entry; undoing them all restores the area exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_heightmap_strokes", tickLimit = LIMIT)
    public void strokesMatchTheKernelAndUndoExactly(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 1400);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 60, z0, x0 + 79, 200, z0 + 79);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 80, 80);
        BrushSurfaceGameTest.decorate(h, x0, z0);
        WorldSnapshot before = capture(world, area);
        SnapshotWorld snapshot = new SnapshotWorld(world, h.runtime.states(), area);

        List<BrushSpec> specs = new ArrayList<>();
        List<List<Dab>> strokes = new ArrayList<>();
        // A raised ridge along x.
        specs.add(heightmap(6, cone(6), 10, false, SurfaceMask.ANY, null, Symmetry.NONE));
        strokes.add(onGround(world, 12, i -> new int[] {x0 + 8 + i, z0 + 8 + i % 2}));
        // A trench across the pond (x0 + 20..26, z0 + 18..24, water to 107): the cut fills with water.
        specs.add(heightmap(4, lopsided(4), 5, true, SurfaceMask.ANY, null, Symmetry.NONE));
        strokes.add(onGround(world, 14, i -> new int[] {x0 + 14 + i, z0 + 21}));
        // Mirrored about z0 + 40, under a height mask and a clip box.
        Box clip = box(x0 + 38, 100, z0 + 25, x0 + 60, 118, z0 + 56);
        specs.add(heightmap(5, lopsided(5), 8, false, new SurfaceMask.Elevation(100, 107), clip,
                new Symmetry(Symmetry.Mode.MIRROR_Z, 0, 2 * (z0 + 40))));
        strokes.add(onGround(world, 12, i -> new int[] {x0 + 40 + i, z0 + 30}));
        // One tall stamp.
        specs.add(heightmap(8, cone(8), 20, false, SurfaceMask.ANY, null, Symmetry.NONE));
        strokes.add(onGround(world, 1, i -> new int[] {x0 + 62, z0 + 64}));

        int[] seq = {1};
        TimedTaskRunner runner = context.createTimedTaskRunner();
        for (int s = 0; s < specs.size(); s++) {
            int current = s;
            runner.createAndAdd(() -> {
                begin(h, h.player, 400 + current, specs.get(current));
                DabOutcome outcome = h.service.dabs(h.player, 400 + current, seq[0]++, strokes.get(current));
                check(outcome.accepted(), "stroke " + current + ": " + outcome);
            });
            runner.createAndAdd(() -> check(h.acks.seqs.size() == seq[0] - 1, "stroke " + current + " still applying"));
            runner.createAndAdd(() -> h.service.endStroke(h.player, 400 + current));
        }
        String[] replay = {null};
        runner.createAndAdd(() -> {
            check(h.events.dabRejections.isEmpty(), "dabs rejected: " + h.events.dabRejections);
            if (replay[0] == null) replay[0] = replay(specs, strokes, snapshot);
            check(replay[0].isEmpty(), replay[0]);
            WorldSnapshot after = capture(world, area);
            int mismatches = 0;
            String first = null;
            for (int y = area.min().y(); y <= area.max().y(); y++) {
                for (int z = area.min().z(); z <= area.max().z(); z++) {
                    for (int x = area.min().x(); x <= area.max().x(); x++) {
                        if (after.get(x, y, z) == snapshot.get(x, y, z)) continue;
                        mismatches++;
                        if (first == null) first = x + "," + y + "," + z;
                    }
                }
            }
            check(mismatches == 0, mismatches + " cells differ from the kernel; first " + first);
            check(h.service.historyService().undoEntries(h.player.getUuid()).size() == specs.size(), "one entry per stroke");
            check(h.history().undoLabel().startsWith("Heightmap stroke"), "named " + h.history().undoLabel());
            // The ridge rose, the trench holds water, the mirrored copies did their share.
            int ridge = 0;
            for (int y = 100; y <= 140; y++) {
                for (int x = x0 + 8; x <= x0 + 19; x++) {
                    if (after.get(x, y, z0 + 8) != before.get(x, y, z0 + 8)) ridge++;
                }
            }
            check(ridge > 50, "the ridge changed only " + ridge + " cells along its crest");
            int water = h.state("minecraft:water");
            check(after.get(x0 + 23, 103, z0 + 21) == water, "the trench under the pond did not fill with water");
            int mirrored = 0;
            for (int y = area.min().y(); y <= area.max().y(); y++) {
                for (int z = z0 + 46; z <= z0 + 56; z++) {
                    for (int x = x0 + 34; x <= x0 + 60; x++) {
                        if (before.get(x, y, z) != after.get(x, y, z)) mirrored++;
                    }
                }
            }
            check(mirrored > 0, "the mirrored copies changed nothing");
        });
        List<RecordingListener> undos = new ArrayList<>();
        for (int s = 0; s < specs.size(); s++) {
            int current = s;
            runner.createAndAdd(() -> {
                if (undos.size() == current) {
                    RecordingListener undo = new RecordingListener();
                    undos.add(undo);
                    h.undo(undo);
                }
            });
            runner.createAndAdd(() -> {
                RecordingListener undo = undos.get(current);
                check(undo.result != null, "undo " + current + " running");
                check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0,
                        "undo " + current + ": " + undo.result);
            });
        }
        runner.createAndAdd(() -> {
            checkSame(before, capture(world, area), "after undoing every Heightmap stroke");
            forceChunks(world, area, false);
            h.close();
        });
        runner.completeIfSuccessful();
    }

    /**
     * Refusals: a player without the {@code brush} node cannot begin a Heightmap stroke; a dab in an unloaded chunk is
     * refused {@code UNLOADED}; a stroke wholly on protected columns is refused {@code PROTECTED} and writes nothing; one
     * across the protected area's edge writes only outside it and undoes exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_heightmap_refused", tickLimit = LIMIT)
    public void refusedWithoutTheBrushNodeUnloadedAndWhereProtected(TestContext context) {
        EditExecutor<ServerWorld> executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 1402);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 60, z0, x0 + 63, 160, z0 + 63);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 64, 64);
        WorldSnapshot before = capture(world, area);
        BrushSpec stamp = heightmap(4, cone(4), 6, false, SurfaceMask.ANY, null, Symmetry.NONE);
        try {
            ServerPlayerEntity user = h.addPlayer(false);
            EditTestSupport.grant(user, Perm.USE, Perm.REGION);
            EditRejected refused = ClipboardGameTest.refusal(() -> h.service.beginStroke(user, 40, stamp));
            check(refused.reason() == RejectReason.NO_PERMISSION && refused.getMessage().contains(Perm.BRUSH.node()),
                    "without brush: " + refused.reason() + " " + refused.getMessage());
            check(h.service.openStroke(user.getUuid()).isEmpty(), "a refused stroke is open");

            // A dab 3,000 blocks away, in a chunk nobody loaded.
            begin(h, h.player, 41, stamp);
            DabOutcome far = h.service.dabs(h.player, 41, 1, List.of(new Dab(0, (x0 + 3000) * 16 + 8, 110 * 16,
                    (z0 + 10) * 16 + 8, 255)));
            check(!far.accepted() && far.reason() == RejectReason.UNLOADED, "a dab in an unloaded chunk: " + far);
            h.service.endStroke(h.player, 41);

            // Wholly protected: nothing written, the lane refuses the dabs.
            ProtectionHook.protect(h.player, world, x0, z0, x0 + 63, z0 + 63);
            begin(h, h.player, 42, stamp);
            int acks = h.acks.seqs.size();
            DabOutcome outcome = h.service.dabs(h.player, 42, 2, onGround(world, 6, i -> new int[] {x0 + 20 + i, z0 + 20}));
            if (outcome.accepted()) {
                MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == acks + 1, 40, "the protected dabs");
                check(h.events.dabRejections.contains(RejectReason.PROTECTED), "refusals " + h.events.dabRejections);
            } else {
                check(outcome.reason() == RejectReason.PROTECTED, "protected: " + outcome);
            }
            h.service.endStroke(h.player, 42);
            MultiplayerGameTest.tickUntil(executor, () -> h.service.queuedCommits(h.player.getUuid()) == 0, 40, "commit");
            check(h.service.historyService().undoEntries(h.player.getUuid()).isEmpty(), "a protected stroke made an entry");
            checkSame(before, capture(world, area), "after the refused strokes");

            // Across the edge (columns from x0 + 32 east protected): only the west side changes, and undoes exactly.
            ProtectionHook.protect(h.player, world, x0 + 32, z0, x0 + 63, z0 + 63);
            h.events.dabRejections.clear();
            begin(h, h.player, 43, heightmap(6, cone(6), 9, false, SurfaceMask.ANY, null, Symmetry.NONE));
            int acks2 = h.acks.seqs.size();
            DabOutcome across = h.service.dabs(h.player, 43, 3, onGround(world, 6, i -> new int[] {x0 + 28 + i, z0 + 30}));
            check(across.accepted(), "across the edge: " + across);
            MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == acks2 + 1, 40, "the dabs across the edge");
            h.service.endStroke(h.player, 43);
            MultiplayerGameTest.tickUntil(executor, () -> h.service.queuedCommits(h.player.getUuid()) == 0, 40, "commit");
            WorldSnapshot partial = capture(world, area);
            int changed = 0;
            for (int y = area.min().y(); y <= area.max().y(); y++) {
                for (int z = area.min().z(); z <= area.max().z(); z++) {
                    for (int x = area.min().x(); x <= area.max().x(); x++) {
                        if (before.get(x, y, z) == partial.get(x, y, z)) continue;
                        check(x < x0 + 32, "a protected cell changed at " + x + "," + y + "," + z);
                        changed++;
                    }
                }
            }
            check(changed > 20, "only " + changed + " cells changed outside the protected columns");
            ProtectionHook.clear(h.player);
            RecordingListener undo = MultiplayerGameTest.historyStep(h, h.player, true);
            MultiplayerGameTest.tickUntil(executor, () -> undo.result != null, 40, "the undo");
            check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0, "undo " + undo.result);
            checkSame(before, capture(world, area), "after undoing the stroke across the edge");
        } finally {
            ProtectionHook.clear(h.player);
            executor.shutdown();
            forceChunks(world, area, false);
            h.close();
        }
        context.complete();
    }

    /**
     * The Heightmap import: the columns of a selection read from the live world, the kernel's cells uploaded as a
     * generated clipboard and pasted where they were made with the Heightmap label: the world then holds exactly those
     * cells (air included) and is otherwise unchanged, two chests in the selection stay with their contents, the entry is
     * named Heightmap, and undo restores it exactly. A player without {@code region} is refused the upload, holding
     * nothing.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_heightmap_import", tickLimit = LIMIT)
    public void anImportBuildsThroughTheGeneratedPasteAndUndoesExactly(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 1404);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 90, z0, x0 + 31, 140, z0 + 31);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 32, 32);
        // A chest in the air above a column the import cuts, one where a column it raises grows: both stay, contents too.
        List<net.minecraft.util.math.BlockPos> chests = List.of(new net.minecraft.util.math.BlockPos(x0 + 6, 112, z0 + 6),
                new net.minecraft.util.math.BlockPos(x0 + 20, 112, z0 + 15));
        for (net.minecraft.util.math.BlockPos chest : chests) {
            world.setBlockState(chest, Blocks.CHEST.getDefaultState());
            ((ChestBlockEntity) world.getBlockEntity(chest)).setStack(3, new ItemStack(Items.DIAMOND, 5));
        }
        Box selection = box(x0 + 4, 102, z0 + 4, x0 + 27, 118, z0 + 19);
        byte[] picture = new byte[24 * 16];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 24; x++) picture[z * 24 + x] = (byte) Math.min(255, (x * 11 + z * 5) % 300);
        }
        HeightmapAreaKernel.Spec spec = new HeightmapAreaKernel.Spec(selection, 100, 118, picture);
        FabricWorldReader reader = new FabricWorldReader(world, h.runtime.states());
        int bottom = world.getBottomY(), top = world.getTopY();
        int[] range = HeightmapAreaKernel.range(spec, bottom, top);
        HeightmapAreaKernel.Result imported = HeightmapAreaKernel.generate(spec,
                (x, z) -> HeightmapAreaKernel.capture(reader, x, z, range[0], range[1], spec.minY()), h.runtime.states(),
                bottom, top, 1 << 20);
        GeneratedSource source = imported.source();
        check(imported.keptBlockEntities() == 2, "kept " + imported.keptBlockEntities() + " block entities");
        check(source.cells() > 100, "an import of " + source.cells() + " cells");
        WorldSnapshot before = capture(world, area);
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        byte[] payload = SparseUpload.encode(source, h.runtime.states());

        ServerPlayerEntity clipboardOnly = h.addPlayer(false);
        EditTestSupport.grant(clipboardOnly, Perm.USE, Perm.CLIPBOARD);
        EditRejected refused = ClipboardGameTest.refusal(() -> clips.beginGeneratedUpload(clipboardOnly, source.bounds(),
                source.cells(), payload.length));
        check(refused.reason() == RejectReason.NO_PERMISSION, "without region: " + refused.reason());
        check(h.service.clipboards().get(clipboardOnly.getUuid()).isEmpty(), "a refused upload held a clipboard");

        Captured<ClipboardService.ClipboardInfo> uploaded = new Captured<>();
        try {
            clips.beginGeneratedUpload(h.player, source.bounds(), source.cells(), payload.length).completed(payload, uploaded);
        } catch (EditRejected e) {
            throw new GameTestException("upload refused: " + e.getMessage());
        }
        RecordingListener paste = new RecordingListener(), undo = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(uploaded.finished(), "decoding the upload"))
                .createAndAdd(() -> {
                    OpSpec.Paste op = new OpSpec.Paste(new SourceRef.Clipboard(uploaded.get("the upload").clipboardId()),
                            source.bounds().min(), Transform.IDENTITY, new PasteOptions(true, false, false));
                    try {
                        h.service.run(h.player, op, new RunOptions(false, ConflictPolicy.SKIP_CONFLICTS, OpLabel.HEIGHTMAP),
                                paste);
                    } catch (EditRejected e) {
                        throw new GameTestException("paste refused: " + e.getMessage());
                    }
                })
                .createAndAdd(() -> check(paste.result != null, "paste running"))
                .createAndAdd(() -> {
                    check(paste.result.outcome() == JobOutcome.COMPLETED, "paste " + paste.result);
                    WorldSnapshot now = capture(world, area);
                    for (int y = area.min().y(); y <= area.max().y(); y++) {
                        for (int z = area.min().z(); z <= area.max().z(); z++) {
                            for (int x = area.min().x(); x <= area.max().x(); x++) {
                                int generated = source.get(x, y, z);
                                int want = generated >= 0 ? generated : before.get(x, y, z);
                                check(now.get(x, y, z) == want, "the import at " + x + "," + y + "," + z);
                            }
                        }
                    }
                    check(h.history().undoLabel().startsWith("Heightmap"), "history " + h.history().undoLabel());
                    checkChests(world, chests, "after the import");
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0,
                            "undo " + undo.result);
                    checkSame(before, capture(world, area), "after undoing the import");
                    checkChests(world, chests, "after the undo");
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * What a radius-32 Heightmap dab costs on a real world: the kernel alone (through the server's world reader, writes
     * discarded) for a first stamp 16, 32 and 128 blocks tall, best and median of twenty with the garbage collector's
     * pauses left out; then, through a dedicated server's brush lane, a ridge of six radius-32 dabs 32 blocks tall and a
     * 128-deep trench cut along it, their steps written in parts, each tick timed without GC pauses. Logged; the 32-tall
     * ridge may run past {@link ShapeBrushLaneGameTest#TICK_MICROS} at most
     * {@link ShapeBrushLaneGameTest#LONG_TICKS_ALLOWED} times (a slow machine), the rest only catches a stall.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_heightmap_timing", tickLimit = LIMIT)
    public void aRadius32DabComputesInMilliseconds(TestContext context) {
        EditExecutor<ServerWorld> executor = new EditExecutor<>(EngineTestSupport.runtime(context),
                EditExecutor.Settings.from(SculptoryConfig.defaults(), true));
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 1406);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 60, z0, x0 + 111, 260, z0 + 79);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 112, 80);
        try {
            FabricWorldReader reader = new FabricWorldReader(world, h.runtime.states());
            Dab dab = new Dab(0, (x0 + 40) * 16 + 8, 112 * 16, (z0 + 40) * 16 + 8, 255);
            StringBuilder report = new StringBuilder();
            for (int round = 0; round < 2; round++) {
                for (int tall : new int[] {16, 32, 128}) {
                    BrushSpec spec = heightmap(32, cone(32), tall, false, SurfaceMask.ANY, null, Symmetry.NONE);
                    long[] took = new long[20];
                    int[] cells = {0};
                    for (int run = 0; run < took.length; run++) {
                        cells[0] = 0;
                        reader.invalidate();
                        StrokeState stroke = new StrokeState();
                        long gcBefore = ShapeBrushLaneGameTest.gcMillis();
                        long start = System.nanoTime();
                        BrushKernels.forTool(BrushTool.HEIGHTMAP).apply(spec, dab, stroke, reader, (x, y, z, s) -> cells[0]++);
                        long nanos = System.nanoTime() - start;
                        took[run] = Math.max(0, nanos - (ShapeBrushLaneGameTest.gcMillis() - gcBefore) * 1_000_000L);
                    }
                    if (round == 0) continue;
                    Arrays.sort(took);
                    report.append(String.format(Locale.ROOT, " stamp %d tall best %.1f ms, median %.1f ms (%d cells);", tall,
                            took[0] / 1e6, took[took.length / 2] / 1e6, cells[0]));
                    check(took[0] < 1_000_000_000L, "a radius-32 stamp took " + took[0] / 1e6 + " ms");
                }
            }
            LoggerFactory.getLogger("sculptory").info("Heightmap radius-32 dab compute:{}", report);

            // Through a dedicated server's brush lane: a 32-tall ridge of six dabs, then a 128-tall trench cut along it,
            // their steps written in parts; each tick timed without the garbage collector's pauses.
            StringBuilder lane = new StringBuilder();
            int strokeId = 300;
            for (int tall : new int[] {32, 128}) {
                boolean lower = tall == 128;
                BrushSpec spec = heightmap(32, cone(32), tall, lower, SurfaceMask.ANY, null, Symmetry.NONE);
                int id = strokeId++;
                begin(h, h.player, id, spec);
                int acks = h.acks.seqs.size();
                List<Dab> dabs = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    int x = x0 + 40 + 6 * i;
                    dabs.add(new Dab(i, x * 16 + 8, (ground(world, x, z0 + 40) + 1) * 16, (z0 + 40) * 16 + 8, 255));
                }
                DabOutcome outcome = h.service.dabs(h.player, id, id, dabs);
                check(outcome.accepted(), tall + " tall: " + outcome);
                long[] stroke = timedTicks(executor, h, () -> h.acks.seqs.size() == acks + 1, 600, tall + " tall stroke");
                h.service.endStroke(h.player, id);
                long[] commit = timedTicks(executor, h, () -> h.service.queuedCommits(h.player.getUuid()) == 0, 600,
                        tall + " tall commit");
                lane.append(String.format(Locale.ROOT, " %d tall (%s): %d ticks, longest %.1f ms, %d over %d ms; commit"
                        + " %d ticks, longest %.1f ms; %.1f ms of GC left out;", tall, lower ? "lower" : "raise", stroke[0],
                        stroke[1] / 1000.0, stroke[2], ShapeBrushLaneGameTest.TICK_MICROS / 1000, commit[0],
                        commit[1] / 1000.0, (stroke[3] + commit[3]) / 1000.0));
                if (tall == 32) {
                    check(stroke[2] <= ShapeBrushLaneGameTest.LONG_TICKS_ALLOWED
                            && stroke[1] < ShapeBrushLaneGameTest.STALL_MICROS, "ticks over the lane's budget:" + lane);
                }
            }
            LoggerFactory.getLogger("sculptory").info("Heightmap radius-32 strokes on a dedicated server's lane:{}", lane);
        } finally {
            executor.shutdown();
            forceChunks(world, area, false);
            h.close();
        }
        context.complete();
    }

    /**
     * Ticks the executor and the service until {@code done}; {ticks, longest tick, ticks over
     * {@link ShapeBrushLaneGameTest#TICK_MICROS}, GC pauses left out}, times in microseconds, each tick's time without
     * the garbage collectors' pauses during it.
     */
    private static long[] timedTicks(EditExecutor<ServerWorld> executor, Harness h,
                                     java.util.function.BooleanSupplier done, int max, String what) {
        long ticks = 0, longest = 0, over = 0, gc = 0;
        while (!done.getAsBoolean() && ticks < max) {
            long gcBefore = ShapeBrushLaneGameTest.gcMillis();
            long start = System.nanoTime();
            executor.tick();
            h.service.tick();
            long took = (System.nanoTime() - start) / 1000;
            long paused = (ShapeBrushLaneGameTest.gcMillis() - gcBefore) * 1000;
            took = Math.max(0, took - paused);
            gc += paused;
            longest = Math.max(longest, took);
            if (took > ShapeBrushLaneGameTest.TICK_MICROS) over++;
            ticks++;
        }
        check(done.getAsBoolean(), what + " did not finish in " + max + " ticks");
        return new long[] {ticks, longest, over, gc};
    }

    // ------------------------------------------------------------------ helpers

    /** Each chest is still a chest holding its five diamonds. */
    private static void checkChests(ServerWorld world, List<net.minecraft.util.math.BlockPos> chests, String what) {
        for (net.minecraft.util.math.BlockPos chest : chests) {
            check(world.getBlockState(chest).isOf(Blocks.CHEST), what + ": no chest at " + chest);
            ItemStack held = ((ChestBlockEntity) world.getBlockEntity(chest)).getStack(3);
            check(held.isOf(Items.DIAMOND) && held.getCount() == 5, what + ": the chest at " + chest + " holds " + held);
        }
    }


    static BrushSpec heightmap(int radius, byte[] heights, int tall, boolean lower, SurfaceMask mask, Box clip,
                               Symmetry symmetry) {
        return new BrushSpec(BrushTool.HEIGHTMAP, radius, 1f, Falloff.CONSTANT, Shape.SQUARE, null, mask, 0, 0, 7L, clip,
                symmetry, null, SculptMode.TERRAIN, null, null, new HeightmapSpec(2 * radius + 1, heights, tall, lower), null);
    }

    /** A round cone: 255 at the centre, 0 at the edge. */
    static byte[] cone(int radius) {
        int side = 2 * radius + 1;
        byte[] heights = new byte[side * side];
        for (int v = 0; v < side; v++) {
            for (int u = 0; u < side; u++) {
                double d = Math.sqrt((u - radius) * (u - radius) + (v - radius) * (v - radius)) / radius;
                heights[v * side + u] = (byte) Math.max(0, (int) Math.round(255 * (1 - d)));
            }
        }
        return heights;
    }

    /** A cone leaning east with a spike in its south-west corner, so a mirror or turn shows. */
    static byte[] lopsided(int radius) {
        byte[] heights = cone(radius);
        int side = 2 * radius + 1;
        for (int v = 0; v < side; v++) {
            for (int u = radius; u < side; u++) heights[v * side + u] = (byte) Math.min(255, (heights[v * side + u] & 0xFF) + 60);
        }
        heights[(side - 1) * side] = (byte) 255;
        return heights;
    }

    /** {@code count} dabs on the ground's top face at the columns {@code column} gives, where the cursor would hit it. */
    private static List<Dab> onGround(ServerWorld world, int count, IntFunction<int[]> column) {
        List<Dab> dabs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int[] c = column.apply(i);
            dabs.add(new Dab(i, c[0] * 16 + 8, (ground(world, c[0], c[1]) + 1) * 16, c[1] * 16 + 8, 255));
        }
        return dabs;
    }

    /** The topmost ground of a column (not air, plants or water), from y 150 down. */
    static int ground(ServerWorld world, int x, int z) {
        for (int y = 150; y >= 100; y--) {
            BlockState state = world.getBlockState(new net.minecraft.util.math.BlockPos(x, y, z));
            if (!state.isAir() && !state.isReplaceable() && state.getFluidState().isEmpty()) return y;
        }
        return 100;
    }

    /** Runs each stroke's kernel on the snapshot, in order, writing through; "" when every stroke wrote something. */
    private static String replay(List<BrushSpec> specs, List<List<Dab>> strokes, SnapshotWorld snapshot) {
        for (int s = 0; s < specs.size(); s++) {
            BrushSpec spec = specs.get(s);
            StrokeState state = new StrokeState();
            int[] writes = {0};
            try {
                for (Dab dab : strokes.get(s)) {
                    BrushKernels.forTool(spec.tool()).apply(spec, dab, state, snapshot, (x, y, z, handle) -> {
                        snapshot.set(x, y, z, handle);
                        writes[0]++;
                    });
                }
            } catch (RuntimeException e) {
                return "stroke " + s + " failed on the snapshot: " + e;
            }
            if (writes[0] == 0) return "stroke " + s + " wrote nothing on the snapshot";
        }
        return "";
    }

    private static void begin(Harness h, ServerPlayerEntity player, int strokeId, BrushSpec spec) {
        try {
            h.service.beginStroke(player, strokeId, spec);
        } catch (EditRejected e) {
            throw new GameTestException("stroke refused: " + e.getMessage());
        }
    }
}
