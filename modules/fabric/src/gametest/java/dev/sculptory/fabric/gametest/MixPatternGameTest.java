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
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.ShapeSpec;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.MixLayout;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.palette.BlockPalette;
import dev.sculptory.core.palette.PalettePattern;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.fabric.engine.ClipboardService;
import dev.sculptory.fabric.engine.ClipboardService.LibraryChange;
import dev.sculptory.fabric.engine.impl.EditExecutor;
import dev.sculptory.fabric.engine.impl.ServerClipboards;
import dev.sculptory.fabric.gametest.ClipTestSupport.Captured;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.SnapshotWorld;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.library.PaletteFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;

/**
 * Block mixes laid out in space on the server with its real state space: Palette
 * Paint and the Shape brush with every pattern write exactly what the shared kernel writes on a snapshot (what the
 * client predicts), each stroke one history entry, undone exactly; Fill with each pattern writes the pattern's block at
 * every cell (a Mirror X copy the pattern mirrored), undone exactly, and a Steepness Fill is refused writing nothing; a
 * palette's pattern goes through the server's file and back; a pattern needs the plain edit's permission and nothing more.
 * A patterned Fill reaching into protected columns applies partly and undoes exactly. Region slots 1040-1059 (1040,
 * 1042, 1044, 1046, 1048 used).
 */
public final class MixPatternGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /**
     * Terrain with every kind of slope: the strokes' usual rolling ground ({@link StrokeGameTest#terrain}), a 1:1 ramp
     * rising east from x0 + 20 to a plateau 12 blocks up, which ends in a cliff at x0 + 44.
     */
    private static void slopes(Harness h, int x0, int z0, int w, int d) {
        StrokeGameTest.terrain(h, x0, z0, w, d);
        BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
        int stone = h.state("minecraft:stone"), grass = h.state("minecraft:grass_block");
        for (int dx = 20; dx <= 44; dx++) {
            int top = 110 + Math.min(12, dx - 20);
            for (int dz = 0; dz < d; dz++) {
                for (int y = 104; y <= top; y++) writer.write(x0 + dx, y, z0 + dz, y == top ? grass : stone, null);
            }
        }
    }

    private static int[] states(Harness h, String... specs) {
        int[] states = new int[specs.length];
        for (int i = 0; i < specs.length; i++) states[i] = h.state(specs[i]);
        return states;
    }

    private static List<Dab> line(int x0, int z0, int from, int to, int y, int z) {
        List<Dab> dabs = new ArrayList<>();
        for (int x = from, i = 0; x <= to; x += 3, i++) dabs.add(ShapeBrushGameTest.at(i, x0 + x, y, z0 + z));
        return dabs;
    }

    /**
     * Palette Paint with Random, Patches, a Gradient running up the ramp, Steepness over the ramp and cliff, and Patches
     * under Mirror X: through the server's brush lane each stroke writes exactly what the kernel writes on a snapshot,
     * is one history entry, and all of them undo exactly. Steepness puts the last block at the cliff's edge and the
     * first on flat ground; the mirrored copy's patches mirror the dab's.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mix_palette_paint", tickLimit = LIMIT)
    public void palettePaintWithEveryPatternMatchesTheKernelAndUndoesExactly(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] corner = regionCorner(context, 1040);
        int x0 = corner[0], z0 = corner[1];
        // Tall enough for the mirrored copies' ground search (64 blocks above and below the dabs at 112).
        Box area = box(x0, 45, z0, x0 + 63, 180, z0 + 55);
        loadAndForce(world, area);
        slopes(h, x0, z0, 64, 56);
        WorldSnapshot before = capture(world, area);
        SnapshotWorld snapshot = new SnapshotWorld(world, h.runtime.states(), area);
        int[] blocks = states(h, "minecraft:moss_block", "minecraft:coarse_dirt", "minecraft:cobblestone");
        Pattern.Weighted mix = new Pattern.Weighted(blocks, new int[] {1, 1, 1}, 1234L);
        List<Pattern> materials = List.of(
                mix,
                new Pattern.Arranged(mix, new MixLayout.Patches(4)),
                new Pattern.Arranged(mix, new MixLayout.Gradient(new BlockPos(x0 + 20, 110, z0), new BlockPos(x0 + 32, 122, z0),
                        3)),
                new Pattern.Arranged(mix, new MixLayout.Steepness(0)),
                new Pattern.Arranged(mix, new MixLayout.Patches(3)));
        List<BrushSpec> specs = new ArrayList<>();
        List<List<Dab>> strokes = new ArrayList<>();
        for (int i = 0; i < materials.size(); i++) {
            BrushSpec spec = new BrushSpec(BrushTool.PALETTE, 4, 1f, Falloff.CONSTANT, Shape.CIRCLE, materials.get(i),
                    SurfaceMask.ANY, 2, 0, 7L + i);
            // The mirror plane x = x0 + 24.5: column x0 + 24 + k mirrors onto x0 + 24 - k.
            if (i == 4) spec = spec.withSymmetry(new Symmetry(Symmetry.Mode.MIRROR_X, 2 * (x0 + 24) + 1, 0));
            specs.add(spec);
        }
        // Every dab's area and reads (radius 4, two columns more) stay inside the snapshot's box.
        strokes.add(line(x0, z0, 8, 17, 112, 8));
        strokes.add(line(x0, z0, 8, 17, 112, 18));
        strokes.add(line(x0, z0, 18, 36, 118, 28));
        strokes.add(line(x0, z0, 14, 56, 118, 38));
        strokes.add(line(x0, z0, 8, 14, 112, 48));
        int seq = 1;
        for (int s = 0; s < specs.size(); s++) seq = ShapeBrushGameTest.stroke(h, executor, 40 + s, specs.get(s), strokes.get(s), seq);
        check(h.events.dabRejections.isEmpty(), "dabs rejected: " + h.events.dabRejections);
        String replay = BrushSymmetryGameTest.replay(specs, strokes, snapshot);
        check(replay.isEmpty(), replay);
        String mismatches = BrushSymmetryGameTest.mismatches(world, area, snapshot);
        check(mismatches.isEmpty(), mismatches);

        WorldSnapshot after = capture(world, area);
        // Steepness (thirds of 90°): the cliff's edge is the last block, the plateau the first, the 1:1 ramp the middle.
        check(topState(after, x0 + 44, z0 + 38) == blocks[2], "the cliff's edge is cobblestone: "
                + Block.getStateFromRawId(topState(after, x0 + 44, z0 + 38)));
        check(topState(after, x0 + 38, z0 + 38) == blocks[0], "the plateau is moss: "
                + Block.getStateFromRawId(topState(after, x0 + 38, z0 + 38)));
        check(topState(after, x0 + 26, z0 + 38) == blocks[1], "the 1:1 ramp is coarse dirt: "
                + Block.getStateFromRawId(topState(after, x0 + 26, z0 + 38)));
        // The mirrored Patches: a copy's column x holds the pattern read at its mirror image 2 × x0 + 48 - x, at its own
        // height (the copy stands on its own ground).
        Pattern mirroredPatches = materials.get(4);
        int mirrored = 0;
        for (int x = x0 + 34; x <= x0 + 40; x++) {
            int y = topY(after, x, z0 + 48);
            if (after.get(x, y, z0 + 48) == before.get(x, y, z0 + 48)) continue;
            check(after.get(x, y, z0 + 48) == mirroredPatches.apply(h.runtime.states(), 2 * x0 + 48 - x, y, z0 + 48, 0),
                    "the copy reads the patches mirrored at " + (x - x0));
            mirrored++;
        }
        check(mirrored > 3, "the mirrored stroke painted the copy's side: " + mirrored);
        List<HistoryEntry> entries = h.service.historyService().undoEntries(h.player.getUuid());
        check(entries.size() == specs.size(), "one entry per stroke, got " + entries.size());
        ShapeBrushGameTest.undoAll(h, executor, specs.size(), 40);
        checkSame(before, capture(world, area), "after undoing every Palette Paint stroke");
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    /** The topmost non-air y of column (x, z) in a snapshot (from y 140 down), or 0. */
    private static int topY(WorldSnapshot snapshot, int x, int z) {
        int air = Block.getRawIdFromState(net.minecraft.block.Blocks.AIR.getDefaultState());
        for (int y = 140; y >= 96; y--) {
            if (snapshot.get(x, y, z) != air) return y;
        }
        return 0;
    }

    private static int topState(WorldSnapshot snapshot, int x, int z) {
        return snapshot.get(x, topY(snapshot, x, z), z);
    }

    /**
     * The Shape brush with a Random mix, Patches (placing, and painting over the terrain) and a Gradient, under Rotate 2:
     * each stroke writes exactly what the kernel writes on a snapshot, is one entry, and all undo exactly; the turned
     * copy of the Gradient cube holds the dab's cube turned.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mix_shape", tickLimit = LIMIT)
    public void shapeBrushWithEveryPatternMatchesTheKernelAndUndoesExactly(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] corner = regionCorner(context, 1042);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 95, z0, x0 + 63, 140, z0 + 63);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 64, 64);
        WorldSnapshot before = capture(world, area);
        SnapshotWorld snapshot = new SnapshotWorld(world, h.runtime.states(), area);
        int[] blocks = states(h, "minecraft:stone", "minecraft:andesite", "minecraft:gravel", "minecraft:tuff");
        Pattern.Weighted mix = new Pattern.Weighted(blocks, new int[] {4, 2, 1, 1}, 55L);
        Symmetry turn = new Symmetry(Symmetry.Mode.ROTATE_2, 2 * (x0 + 32), 2 * (z0 + 32));
        BlockPos from = new BlockPos(x0 + 8, 118, z0 + 10), to = new BlockPos(x0 + 18, 118, z0 + 10);
        List<BrushSpec> specs = List.of(
                ShapeBrushGameTest.shape(4, ShapeSpec.Kind.SPHERE, 9, Facing.UP, ShapeSpec.Mode.PLACE, 0, mix, turn),
                ShapeBrushGameTest.shape(5, ShapeSpec.Kind.SPHERE, 11, Facing.UP, ShapeSpec.Mode.PLACE, 1,
                        new Pattern.Arranged(mix, new MixLayout.Patches(3)), turn),
                ShapeBrushGameTest.shape(6, ShapeSpec.Kind.SPHERE, 13, Facing.UP, ShapeSpec.Mode.PAINT, 0,
                        new Pattern.Arranged(mix, new MixLayout.Patches(2)), Symmetry.NONE),
                ShapeBrushGameTest.shape(5, ShapeSpec.Kind.CUBE, 7, Facing.UP, ShapeSpec.Mode.PLACE, 0,
                        new Pattern.Arranged(mix, new MixLayout.Gradient(from, to, 2)), turn));
        List<List<Dab>> strokes = List.of(
                List.of(ShapeBrushGameTest.at(0, x0 + 10, 118, z0 + 20), ShapeBrushGameTest.at(1, x0 + 14, 119, z0 + 22)),
                List.of(ShapeBrushGameTest.at(0, x0 + 20, 120, z0 + 12)),
                List.of(ShapeBrushGameTest.at(0, x0 + 40, 106, z0 + 40)),
                List.of(ShapeBrushGameTest.at(0, x0 + 13, 118, z0 + 10)));
        int seq = 1;
        for (int s = 0; s < specs.size(); s++) seq = ShapeBrushGameTest.stroke(h, executor, 60 + s, specs.get(s), strokes.get(s), seq);
        check(h.events.dabRejections.isEmpty(), "dabs rejected: " + h.events.dabRejections);
        String replay = BrushSymmetryGameTest.replay(specs, strokes, snapshot);
        check(replay.isEmpty(), replay);
        String mismatches = BrushSymmetryGameTest.mismatches(world, area, snapshot);
        check(mismatches.isEmpty(), mismatches);
        // The Gradient cube runs stone (at the line's start, x0 + 8) to tuff (its end, x0 + 18); the half-turned copy
        // (cell x lands on 2 × x0 + 63 - x) runs the other way.
        WorldSnapshot after = capture(world, area);
        check(after.get(x0 + 8, 118, z0 + 10) == blocks[0] && after.get(x0 + 18, 118, z0 + 10) == blocks[3],
                "the gradient cube runs stone to tuff");
        check(after.get(x0 + 55, 118, z0 + 53) == blocks[0] && after.get(x0 + 45, 118, z0 + 53) == blocks[3],
                "the turned copy runs along the turned line");
        check(h.service.historyService().undoEntries(h.player.getUuid()).size() == specs.size(), "one entry per stroke");
        ShapeBrushGameTest.undoAll(h, executor, specs.size(), 40);
        checkSame(before, capture(world, area), "after undoing every Shape stroke");
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    /**
     * Fill with Random, Patches and a Gradient: every cell holds what the pattern gives there (Random: its own seed's
     * pick), a Mirror X Fill with Patches writes the pattern mirrored into the copy, each is one entry and all undo
     * exactly. A Steepness Fill is refused INVALID and writes nothing.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mix_fill", tickLimit = LIMIT)
    public void fillWithEveryPatternWritesThePatternAndUndoesExactly(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] corner = regionCorner(context, 1044);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 95, z0, x0 + 63, 130, z0 + 31);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 64, 32);
        WorldSnapshot before = capture(world, area);
        int[] blocks = states(h, "minecraft:white_concrete", "minecraft:light_gray_concrete", "minecraft:gray_concrete");
        Pattern.Weighted mix = new Pattern.Weighted(blocks, new int[] {2, 1, 1}, 99L);
        record Case(Box box, Pattern pattern, Symmetry symmetry) {}
        Box a = box(x0 + 2, 100, z0 + 2, x0 + 13, 115, z0 + 13);
        Box b = box(x0 + 16, 100, z0 + 2, x0 + 27, 115, z0 + 13);
        Box c = box(x0 + 2, 100, z0 + 16, x0 + 13, 115, z0 + 27);
        Box d = box(x0 + 34, 100, z0 + 16, x0 + 45, 115, z0 + 27);
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 2 * (x0 + 48), 0);
        List<Case> cases = List.of(
                new Case(a, mix, Symmetry.NONE),
                new Case(b, new Pattern.Arranged(mix, new MixLayout.Patches(3)), Symmetry.NONE),
                new Case(c, new Pattern.Arranged(mix, new MixLayout.Gradient(new BlockPos(x0 + 2, 100, z0 + 20),
                        new BlockPos(x0 + 2, 115, z0 + 20), 2)), Symmetry.NONE),
                new Case(d, new Pattern.Arranged(mix, new MixLayout.Patches(2)), mirror));
        for (Case fill : cases) {
            RecordingListener listener = new RecordingListener();
            try {
                h.service.run(h.player, new OpSpec.Fill(new Region.Cuboid(fill.box()), fill.pattern(), CellMask.ANY,
                        fill.symmetry()), RunOptions.DEFAULT, listener);
            } catch (EditRejected e) {
                throw new GameTestException("fill refused: " + e.getMessage());
            }
            MultiplayerGameTest.tickUntil(executor, () -> listener.result != null, 40, "fill");
            check(listener.result.outcome() == JobOutcome.COMPLETED, "fill: " + listener.result);
        }
        WorldSnapshot after = capture(world, area);
        var states = h.runtime.states();
        for (Case fill : cases) {
            Box box = fill.box();
            for (int x = box.min().x(); x <= box.max().x(); x++) {
                for (int y = box.min().y(); y <= box.max().y(); y++) {
                    for (int z = box.min().z(); z <= box.max().z(); z++) {
                        int expected = fill.pattern().apply(states, x, y, z, before.get(x, y, z));
                        check(after.get(x, y, z) == expected, "a cell of " + fill.pattern() + " at " + (x - x0) + ","
                                + y + "," + (z - z0));
                        if (!fill.symmetry().isOff()) {
                            // The mirror plane x = x0 + 48: cell x lands on 2 × (x0 + 48) - 1 - x.
                            int image = 2 * (x0 + 48) - 1 - x;
                            check(after.get(image, y, z) == expected, "the mirrored copy at " + (image - x0));
                        }
                    }
                }
            }
        }
        // The Gradient runs up: its bottom layer is white, its top gray.
        check(after.get(x0 + 5, 100, z0 + 20) == blocks[0] && after.get(x0 + 5, 115, z0 + 20) == blocks[2],
                "the gradient runs bottom to top");
        // Steepness is refused: only Palette Paint measures the ground's slope.
        EditRejected steep = ClipboardGameTest.refusal(() -> h.service.run(h.player, new OpSpec.Fill(
                new Region.Cuboid(box(x0 + 50, 100, z0 + 2, x0 + 55, 105, z0 + 7)),
                new Pattern.Arranged(mix, new MixLayout.Steepness(5)), CellMask.ANY, Symmetry.NONE),
                RunOptions.DEFAULT, new RecordingListener()));
        check(steep.reason() == RejectReason.INVALID, "a Steepness Fill: " + steep.reason() + " " + steep.getMessage());
        check(h.service.historyService().undoEntries(h.player.getUuid()).size() == cases.size(), "one entry per Fill");
        ShapeBrushGameTest.undoAll(h, executor, cases.size(), 40);
        checkSame(before, capture(world, area), "after undoing every Fill");
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    /**
     * A palette saved with a pattern keeps its order and pattern through the server's file and back (the server's own
     * state text in the file), and a Steepness palette too; a file with an unknown pattern is refused INVALID naming it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mix_palette_file", tickLimit = LIMIT)
    public void aPalettesPatternGoesThroughTheServersFileAndBack(TestContext context) throws IOException {
        Harness h = new Harness(context);
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.CLIPBOARD, Perm.LIBRARY_WRITE);
        Path root = ClipTestSupport.libraryRoot(context);
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);
        PalettePattern pattern = new PalettePattern(PalettePattern.Kind.STEEPNESS, 9, 5, 22, -123456789012345L);
        BlockPalette saved = new BlockPalette(List.of(new BlockPalette.Entry("minecraft:grass_block", 3),
                new BlockPalette.Entry("minecraft:coarse_dirt", 1), new BlockPalette.Entry("minecraft:stone", 2)), pattern);
        Captured<LibraryChange> change = new Captured<>();
        Captured<ClipboardService.LoadedPalette> loaded = new Captured<>();
        Captured<ClipboardService.LoadedPalette> strange = new Captured<>();
        try {
            clips.savePalette(builder, "mixes/hills.palette.json", saved, change);
        } catch (EditRejected e) {
            throw new GameTestException("save refused: " + e.getMessage());
        }
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    String path = change.get("save").to();
                    try {
                        PaletteFile.Content content = PaletteFile.decode(Files.readAllBytes(root.resolve(path)));
                        check(content.pattern().equals(pattern), "the file's pattern: " + content.pattern());
                        check(content.entries().get(0).state().equals("minecraft:grass_block[snowy=false]"),
                                "the order and the server's own state text: " + content.entries());
                        // A hand-edited file naming a pattern this build doesn't know.
                        Files.writeString(root.resolve("mixes/strange.palette.json"), "{\"format\": \"sculptory:palette\","
                                + " \"version\": 1, \"entries\": [{\"state\": \"minecraft:stone\", \"weight\": 1}],"
                                + " \"pattern\": {\"kind\": \"stripes\"}}");
                        clips.loadPalette(builder, path, loaded);
                    } catch (IOException | PaletteFile.PaletteFormatException | EditRejected e) {
                        throw new GameTestException("reading the palette back: " + e);
                    }
                })
                .createAndAdd(() -> {
                    BlockPalette back = loaded.get("load").palette();
                    check(back.pattern().equals(pattern), "the pattern loads back: " + back.pattern());
                    check(back.entries().get(1).state().equals("minecraft:coarse_dirt")
                            && back.entries().get(2).state().equals("minecraft:stone"), "in order: " + back.entries());
                    try {
                        clips.loadPalette(builder, "mixes/strange.palette.json", strange);
                    } catch (EditRejected e) {
                        throw new GameTestException("the load was refused up front: " + e.getMessage());
                    }
                })
                .createAndAdd(() -> {
                    check(strange.finished(), "the load of an unknown pattern is still running");
                    check(strange.reason == RejectReason.INVALID && strange.detail.contains("unknown pattern"),
                            "an unknown pattern: " + strange.reason + " " + strange.detail);
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A pattern needs nothing beyond the plain edit's permission and gets nothing less: a player with only
     * {@code sculptory.use} has a Patches Fill refused NO_PERMISSION naming {@code region}, and a builder with
     * {@code region} but no {@code brush} has a Gradient Palette Paint stroke refused naming {@code brush}. Nothing is
     * written, no stroke stays open and no history entry is made; the same builder's Patches Fill is then admitted.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mix_permission", tickLimit = LIMIT)
    public void patternsNeedThePlainEditsPermission(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] corner = regionCorner(context, 1046);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 95, z0, x0 + 31, 130, z0 + 31);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 32, 32);
        WorldSnapshot before = capture(world, area);
        ServerPlayerEntity user = h.addPlayer(false);
        EditTestSupport.grant(user, Perm.USE);
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.REGION);
        int[] blocks = states(h, "minecraft:stone", "minecraft:andesite");
        Pattern.Weighted mix = new Pattern.Weighted(blocks, new int[] {1, 1}, 7L);
        OpSpec.Fill fill = new OpSpec.Fill(new Region.Cuboid(box(x0 + 4, 100, z0 + 4, x0 + 11, 112, z0 + 11)),
                new Pattern.Arranged(mix, new MixLayout.Patches(3)), CellMask.ANY, Symmetry.NONE);

        EditRejected noRegion = ClipboardGameTest.refusal(() -> h.service.run(user, fill, RunOptions.DEFAULT,
                new RecordingListener()));
        check(noRegion.reason() == RejectReason.NO_PERMISSION && noRegion.getMessage().contains(Perm.REGION.node()),
                "a Patches Fill without region: " + noRegion.reason() + " " + noRegion.getMessage());

        BrushSpec gradient = new BrushSpec(BrushTool.PALETTE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, new Pattern.Arranged(mix,
                new MixLayout.Gradient(new BlockPos(x0 + 4, 112, z0 + 20), new BlockPos(x0 + 20, 112, z0 + 20), 2)),
                SurfaceMask.ANY, 2, 0, 9L);
        EditRejected noBrush = ClipboardGameTest.refusal(() -> h.service.beginStroke(builder, 70, gradient));
        check(noBrush.reason() == RejectReason.NO_PERMISSION && noBrush.getMessage().contains(Perm.BRUSH.node()),
                "a Gradient stroke without brush: " + noBrush.reason() + " " + noBrush.getMessage());
        check(h.service.openStroke(builder.getUuid()).isEmpty(), "a refused stroke stays open");
        executor.tick();
        checkSame(before, capture(world, area), "after the refusals");
        check(h.service.historyService().undoEntries(user.getUuid()).isEmpty()
                && h.service.historyService().undoEntries(builder.getUuid()).isEmpty(), "a refusal made history");

        // The plain edit's permission is all a pattern needs: the builder's Fill runs and writes the pattern.
        RecordingListener listener = new RecordingListener();
        try {
            h.service.run(builder, fill, RunOptions.DEFAULT, listener);
        } catch (EditRejected e) {
            throw new GameTestException("the builder's Patches Fill was refused: " + e.getMessage());
        }
        MultiplayerGameTest.tickUntil(executor, () -> listener.result != null, 40, "fill");
        check(listener.result.outcome() == JobOutcome.COMPLETED, "fill: " + listener.result);
        WorldSnapshot after = capture(world, area);
        check(after.get(x0 + 7, 105, z0 + 7) == fill.pattern().apply(h.runtime.states(), x0 + 7, 105, z0 + 7,
                before.get(x0 + 7, 105, z0 + 7)), "the admitted Fill wrote the pattern");
        check(h.service.historyService().undoEntries(builder.getUuid()).size() == 1, "one entry for the admitted Fill");
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    /**
     * Partial failure (guardrail 3): a Patches Fill reaching into a chunk protected from the builder (spawn protection,
     * a claim) writes its unprotected cells, skips the protected ones (reported in the result, nothing refused), is one
     * history entry, and undoes exactly: the same code paths as a plain Fill, the pattern changing nothing about them.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mix_partial", tickLimit = LIMIT)
    public void aPatternedFillOverProtectedColumnsAppliesPartlyAndUndoesExactly(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] corner = regionCorner(context, 1048);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 95, z0, x0 + 31, 130, z0 + 31);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 32, 32);
        WorldSnapshot before = capture(world, area);
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.REGION);
        int[] blocks = states(h, "minecraft:white_concrete", "minecraft:gray_concrete");
        Pattern.Arranged patches = new Pattern.Arranged(new Pattern.Weighted(blocks, new int[] {1, 1}, 3L),
                new MixLayout.Patches(3));
        // The box spans two chunks in x; the east one (x0 + 16 on) is protected from the builder.
        Box box = box(x0 + 4, 100, z0 + 4, x0 + 27, 110, z0 + 11);
        long half = 12L * 11 * 8;
        ProtectionHook.protect(builder, world, x0 + 16, z0, x0 + 31, z0 + 31);
        RecordingListener fill = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        try {
            try {
                h.service.run(builder, new OpSpec.Fill(new Region.Cuboid(box), patches, CellMask.ANY, Symmetry.NONE),
                        RunOptions.DEFAULT, fill);
            } catch (EditRejected e) {
                throw new GameTestException("the fill over a partly protected box was refused: " + e.getMessage());
            }
            MultiplayerGameTest.tickUntil(executor, () -> fill.result != null, 40, "partial fill");
        } finally {
            ProtectionHook.clear(builder);
        }
        check(fill.result.outcome() == JobOutcome.COMPLETED && fill.result.skippedProtected() == half
                && fill.result.changed() == half, "half the box skipped, half written: " + fill.result);
        WorldSnapshot after = capture(world, area);
        var states = h.runtime.states();
        for (int x = box.min().x(); x <= box.max().x(); x++) {
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) {
                    int expected = x < x0 + 16 ? patches.apply(states, x, y, z, before.get(x, y, z)) : before.get(x, y, z);
                    check(after.get(x, y, z) == expected, (x < x0 + 16 ? "an unprotected cell holds the pattern at "
                            : "a protected cell was written at ") + (x - x0) + "," + y + "," + (z - z0));
                }
            }
        }
        check(h.service.historyService().undoEntries(builder.getUuid()).size() == 1, "one entry for the partial Fill");
        try {
            h.service.undo(builder, ConflictPolicy.SKIP_CONFLICTS, undo);
        } catch (EditRejected e) {
            throw new GameTestException("undo refused: " + e.getMessage());
        }
        MultiplayerGameTest.tickUntil(executor, () -> undo.result != null, 40, "undo");
        check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.changed() == half
                && undo.result.skippedConflicts() == 0, "the undo puts back exactly the written half: " + undo.result);
        checkSame(before, capture(world, area), "after undoing the partial Fill");
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }
}
