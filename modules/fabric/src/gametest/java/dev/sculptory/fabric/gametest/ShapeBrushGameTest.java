package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.ShapeSpec;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.SnapshotWorld;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.config.SculptoryConfig;
import dev.sculptory.server.config.UnloadedPolicy;
import dev.sculptory.server.engine.DabOutcome;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.impl.EditExecutor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.IntUnaryOperator;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.world.border.WorldBorder;
import org.slf4j.LoggerFactory;

/**
 * The Shape brush on the server (regionCorner slots 680-699): each solid writes exactly the contract's region cells
 * (Region.Shape, Region.Cuboid for a cube) and each stroke is one exact undo; a symmetric drag of hollow shapes and a
 * large carving match the shared kernel (the client's prediction) cell for cell as one undo step each; Carve, Paint and
 * Place in air change only their cells; protected cells are skipped and a wholly protected shape is refused PROTECTED;
 * refusals: no permission, a copy in an unloaded chunk, a lock anywhere in a shape's box. Large steps are written in
 * parts over several ticks (measured at the largest size), other players' dabs between their parts; a lock met by a
 * later part stops the step and what was written undoes exactly; block entities are replaced and restored with their
 * contents; the size limit applies at the stroke's start. Each test runs its own executor, ticked here.
 */
public final class ShapeBrushGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /** A dab centred on block (x, y, z). */
    static Dab at(int index, int x, int y, int z) {
        return new Dab(index, 16 * x + 8, 16 * y + 8, 16 * z + 8, Dab.FULL_PRESSURE);
    }

    static BrushSpec shape(int radius, ShapeSpec.Kind kind, int height, Facing facing, ShapeSpec.Mode mode,
                                   int hollow, Pattern material, Symmetry symmetry) {
        return BrushSpec.shape(radius, new ShapeSpec(kind, height, facing, mode, hollow), material, 11L, null, symmetry);
    }

    /** Places one stroke of {@code dabs} (batches of up to 8) and waits for the lane; returns the next sequence. */
    static int stroke(Harness h, EditExecutor<ServerWorld> executor, int strokeId, BrushSpec spec, List<Dab> dabs,
                      int seq) {
        BrushSymmetryGameTest.begin(h, strokeId, spec);
        for (int from = 0; from < dabs.size(); from += 8) {
            DabOutcome outcome = h.service.dabs(h.player, strokeId, seq, dabs.subList(from, Math.min(dabs.size(), from + 8)));
            check(outcome.accepted(), "stroke " + strokeId + ": " + outcome);
            int acked = h.acks.seqs.size() + 1;
            MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == acked, 20, "stroke " + strokeId);
            seq++;
        }
        h.service.endStroke(h.player, strokeId);
        return seq;
    }

    /** {@code before} with every cell of {@code region} in {@code area} set to what {@code next} gives for it. */
    private static int[] expect(WorldSnapshot before, Box area, Region region, CellRule next) {
        int[] expected = new int[Math.toIntExact(area.volume())];
        int i = 0;
        for (int y = area.min().y(); y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    int state = before.get(x, y, z);
                    expected[i++] = region.contains(x, y, z) ? next.apply(x, y, z, state) : state;
                }
            }
        }
        return expected;
    }

    @FunctionalInterface
    private interface CellRule {
        int apply(int x, int y, int z, int before);
    }

    /** "" when the world over {@code area} holds {@code expected} (in {@link #expect} order), else the first difference. */
    private static String differs(ServerWorld world, Box area, int[] expected) {
        WorldSnapshot now = capture(world, area);
        int i = 0, count = 0;
        String first = null;
        for (int y = area.min().y(); y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    int want = expected[i++];
                    if (now.get(x, y, z) == want) continue;
                    count++;
                    if (first == null) {
                        first = x + "," + y + "," + z + ": expected " + net.minecraft.block.Block.getStateFromRawId(want)
                                + ", got " + net.minecraft.block.Block.getStateFromRawId(now.get(x, y, z));
                    }
                }
            }
        }
        return count == 0 ? "" : count + " cells differ; first " + first;
    }

    private static int changed(WorldSnapshot before, int[] expected, Box area) {
        int i = 0, count = 0;
        for (int y = area.min().y(); y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    if (before.get(x, y, z) != expected[i++]) count++;
                }
            }
        }
        return count;
    }

    private static void undoAll(Harness h, EditExecutor<ServerWorld> executor, int entries) {
        undoAll(h, executor, entries, 20);
    }

    static void undoAll(Harness h, EditExecutor<ServerWorld> executor, int entries, int maxTicks) {
        for (int i = 0; i < entries; i++) {
            RecordingListener undo = MultiplayerGameTest.historyStep(h, h.player, true);
            MultiplayerGameTest.tickUntil(executor, () -> undo.result != null, maxTicks, "undo " + i);
            check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0, "undo " + undo.result);
        }
    }

    /**
     * A click of each solid, straddling the terrain's surface: a sphere, a cylinder lying east, a cone of a mix and a
     * cube standing north write exactly the cells of the region of their box and nothing else; each click is one
     * history entry "Shape · N blocks" with N the blocks it changed; undoing all four restores the area exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_shape_cells", tickLimit = LIMIT)
    public void eachSolidWritesExactlyItsRegionAndUndoesExactly(TestContext context) {
        EditExecutor<ServerWorld> executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] corner = regionCorner(context, 680);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 95, z0, x0 + 79, 130, z0 + 47);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 80, 48);
        WorldSnapshot before = capture(world, area);
        int glass = h.state("minecraft:glass"), wool = h.state("minecraft:white_wool");
        Pattern.Weighted mix = new Pattern.Weighted(new int[] {glass, wool}, new int[] {3, 1}, 5L);

        record Click(BrushSpec spec, Dab dab, Region region, CellRule rule) {}
        List<Click> clicks = List.of(
                new Click(shape(4, ShapeSpec.Kind.SPHERE, 9, Facing.UP, ShapeSpec.Mode.PLACE, 0, new Pattern.Single(glass),
                        Symmetry.NONE), at(0, x0 + 10, 108, z0 + 10),
                        new Region.Shape(box(x0 + 6, 104, z0 + 6, x0 + 14, 112, z0 + 14), ShapeKind.ELLIPSOID, Facing.UP),
                        (x, y, z, s) -> glass),
                // An even length east: centred on the block edge x0 + 30.
                new Click(shape(3, ShapeSpec.Kind.CYLINDER, 8, Facing.EAST, ShapeSpec.Mode.PLACE, 0, new Pattern.Single(glass),
                        Symmetry.NONE), new Dab(0, 16 * (x0 + 30), 16 * 109 + 8, 16 * (z0 + 10) + 8, 255),
                        new Region.Shape(box(x0 + 26, 106, z0 + 7, x0 + 33, 112, z0 + 13), ShapeKind.CYLINDER, Facing.EAST),
                        (x, y, z, s) -> glass),
                new Click(shape(4, ShapeSpec.Kind.CONE, 9, Facing.UP, ShapeSpec.Mode.PLACE, 0, mix, Symmetry.NONE),
                        at(0, x0 + 50, 108, z0 + 10),
                        new Region.Shape(box(x0 + 46, 104, z0 + 6, x0 + 54, 112, z0 + 14), ShapeKind.CONE, Facing.UP),
                        (x, y, z, s) -> mix.apply(h.runtime.states(), x, y, z, s)),
                new Click(shape(2, ShapeSpec.Kind.CUBE, 6, Facing.NORTH, ShapeSpec.Mode.PLACE, 0, new Pattern.Single(wool),
                        Symmetry.NONE), new Dab(0, 16 * (x0 + 10) + 8, 16 * 108 + 8, 16 * (z0 + 30), 255),
                        new Region.Cuboid(box(x0 + 8, 106, z0 + 27, x0 + 12, 110, z0 + 32)), (x, y, z, s) -> wool));
        WorldSnapshot current = before;
        int seq = 1;
        for (int i = 0; i < clicks.size(); i++) {
            Click click = clicks.get(i);
            int[] expected = expect(current, area, click.region(), click.rule());
            seq = stroke(h, executor, 10 + i, click.spec(), List.of(click.dab()), seq);
            String diff = differs(world, area, expected);
            check(diff.isEmpty(), click.spec().shapeSpec().kind() + ": " + diff);
            List<HistoryEntry> entries = h.service.historyService().undoEntries(h.player.getUuid());
            check(entries.size() == i + 1, "one entry per click, got " + entries.size());
            String label = String.format(Locale.ROOT, "Shape · %,d blocks", changed(current, expected, area));
            check(entries.get(0).label().equals(label), "label " + entries.get(0).label() + ", expected " + label);
            current = capture(world, area);
        }
        check(h.events.dabRejections.isEmpty(), "dabs rejected: " + h.events.dabRejections);
        undoAll(h, executor, clicks.size());
        checkSame(before, capture(world, area), "after undoing every click");
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    /**
     * A drag of ten hollow spheres of a mix, turned a half turn about the area's centre, and a two-dab carving of
     * radius-20 spheres (too large for the client to predict): the server's result equals the shared kernel's on a
     * snapshot, cell for cell; each drag is one history entry; undoing both restores the area exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_shape_drag", tickLimit = LIMIT)
    public void aDragIsOneUndoStepAndMatchesTheKernel(TestContext context) {
        EditExecutor<ServerWorld> executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] corner = regionCorner(context, 682);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 90, z0, x0 + 63, 150, z0 + 63);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 64, 64);
        WorldSnapshot before = capture(world, area);
        SnapshotWorld snapshot = new SnapshotWorld(world, h.runtime.states(), area);
        int glass = h.state("minecraft:glass"), wool = h.state("minecraft:white_wool");
        Pattern.Weighted mix = new Pattern.Weighted(new int[] {glass, wool}, new int[] {3, 1}, 9L);

        BrushSpec hollow = shape(5, ShapeSpec.Kind.SPHERE, 11, Facing.UP, ShapeSpec.Mode.PLACE, 1, mix,
                new Symmetry(Symmetry.Mode.ROTATE_2, 2 * (x0 + 32), 2 * (z0 + 32)));
        List<Dab> drag = new ArrayList<>();
        for (int i = 0; i < 10; i++) drag.add(at(i, x0 + 8 + 2 * i, 110 + i % 3, z0 + 14 + i % 2));
        BrushSpec carve = shape(20, ShapeSpec.Kind.SPHERE, 41, Facing.UP, ShapeSpec.Mode.CARVE, 0, null, Symmetry.NONE);
        List<Dab> hole = List.of(at(0, x0 + 30, 118, z0 + 36), at(1, x0 + 34, 118, z0 + 38));
        List<BrushSpec> specs = List.of(hollow, carve);
        List<List<Dab>> strokes = List.of(drag, hole);
        int seq = 1;
        for (int s = 0; s < specs.size(); s++) seq = stroke(h, executor, 20 + s, specs.get(s), strokes.get(s), seq);
        check(h.events.dabRejections.isEmpty(), "dabs rejected: " + h.events.dabRejections);
        String replay = BrushSymmetryGameTest.replay(specs, strokes, snapshot);
        check(replay.isEmpty(), replay);
        String mismatches = BrushSymmetryGameTest.mismatches(world, area, snapshot);
        check(mismatches.isEmpty(), mismatches);
        // Both halves of the turned drag changed, and the carving left air.
        WorldSnapshot after = capture(world, area);
        int west = 0, east = 0;
        for (int y = area.min().y(); y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    if (before.get(x, y, z) == after.get(x, y, z)) continue;
                    if (x < x0 + 32) west++;
                    else east++;
                }
            }
        }
        check(west > 500 && east > 500, "changed " + west + " west and " + east + " east");
        check(before.get(x0 + 30, 101, z0 + 36) == h.state("minecraft:stone")
                && after.get(x0 + 30, 101, z0 + 36) == h.state("minecraft:air"), "the carving reached into the terrain");
        List<HistoryEntry> entries = h.service.historyService().undoEntries(h.player.getUuid());
        check(entries.size() == 2, "one entry per stroke, got " + entries.size());
        undoAll(h, executor, 2);
        checkSame(before, capture(world, area), "after undoing both strokes");
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    /**
     * Modes on a sphere half in the terrain: Carve clears exactly its non-air cells, Paint changes only the solid ones,
     * Place in air fills only the air, and a hollow sphere writes only its shell (the regions' rule, checked by brute
     * force); each is one entry and undoes exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_shape_modes", tickLimit = LIMIT)
    public void modesChangeOnlyTheirCells(TestContext context) {
        EditExecutor<ServerWorld> executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] corner = regionCorner(context, 684);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 95, z0, x0 + 47, 125, z0 + 47);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 48, 48);
        WorldSnapshot before = capture(world, area);
        int glass = h.state("minecraft:glass"), air = h.state("minecraft:air");
        Dab dab = at(0, x0 + 24, 107, z0 + 24);
        Region sphere = new Region.Shape(box(x0 + 19, 102, z0 + 19, x0 + 29, 112, z0 + 29), ShapeKind.ELLIPSOID, Facing.UP);
        IntUnaryOperator airOnly = state -> state == air ? glass : state;
        record Case(ShapeSpec.Mode mode, int hollow, Region cells, CellRule rule) {}
        Region shell = shell(sphere, 2);
        List<Case> cases = List.of(
                new Case(ShapeSpec.Mode.CARVE, 0, sphere, (x, y, z, s) -> air),
                new Case(ShapeSpec.Mode.PAINT, 0, sphere, (x, y, z, s) -> s == air ? air : glass),
                new Case(ShapeSpec.Mode.PLACE_IN_AIR, 0, sphere, (x, y, z, s) -> airOnly.applyAsInt(s)),
                new Case(ShapeSpec.Mode.PLACE, 2, shell, (x, y, z, s) -> glass));
        int seq = 1;
        for (int i = 0; i < cases.size(); i++) {
            Case c = cases.get(i);
            BrushSpec spec = shape(5, ShapeSpec.Kind.SPHERE, 11, Facing.UP, c.mode(), c.hollow(),
                    c.mode() == ShapeSpec.Mode.CARVE ? null : new Pattern.Single(glass), Symmetry.NONE);
            int[] expected = expect(before, area, c.cells(), c.rule());
            check(changed(before, expected, area) > 50, c.mode() + " changes something");
            seq = stroke(h, executor, 30 + i, spec, List.of(dab), seq);
            String diff = differs(world, area, expected);
            check(diff.isEmpty(), c.mode() + " hollow " + c.hollow() + ": " + diff);
            check(h.service.historyService().undoEntries(h.player.getUuid()).size() == 1, "one entry");
            undoAll(h, executor, 1);
            checkSame(before, capture(world, area), "after undoing " + c.mode());
        }
        check(h.events.dabRejections.isEmpty(), "dabs rejected: " + h.events.dabRejections);
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    /** The cells of {@code region} within {@code t} of a cell outside it along an axis direction, as a cell set. */
    private static Region shell(Region region, int t) {
        dev.sculptory.core.region.CellSet.Builder cells = dev.sculptory.core.region.CellSet.builder();
        Box b = region.bounds();
        int[][] directions = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int x = b.min().x(); x <= b.max().x(); x++) {
            for (int y = b.min().y(); y <= b.max().y(); y++) {
                for (int z = b.min().z(); z <= b.max().z(); z++) {
                    if (!region.contains(x, y, z)) continue;
                    boolean keep = false;
                    for (int[] d : directions) {
                        for (int k = 1; k <= t && !keep; k++) {
                            keep = !region.contains(x + k * d[0], y + k * d[1], z + k * d[2]);
                        }
                    }
                    if (keep) cells.add(x, y, z);
                }
            }
        }
        return new Region.Cells(cells.build());
    }

    /**
     * Protection is per cell: with the world border protecting x >= x0 + 32, a cube across that line writes its western
     * cells and none of its eastern ones, and the stroke goes on; a cube wholly east writes nothing and is refused
     * PROTECTED. The first cube undoes exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_shape_protected", tickLimit = LIMIT)
    public void protectedCellsAreSkipped(TestContext context) {
        EditExecutor<ServerWorld> executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] corner = regionCorner(context, 686);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 95, z0, x0 + 63, 130, z0 + 47);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 64, 48);
        WorldSnapshot before = capture(world, area);
        int glass = h.state("minecraft:glass");
        BrushSpec cube = shape(4, ShapeSpec.Kind.CUBE, 9, Facing.UP, ShapeSpec.Mode.PLACE, 0, new Pattern.Single(glass),
                Symmetry.NONE);
        Region across = new Region.Cuboid(box(x0 + 26, 111, z0 + 16, x0 + 34, 119, z0 + 24));
        int[] expected = expect(before, area, across, (x, y, z, s) -> x < x0 + 32 ? glass : s);
        WorldBorder border = world.getWorldBorder();
        double centerX = border.getCenterX(), centerZ = border.getCenterZ(), size = border.getSize();
        try {
            border.setCenter(x0 + 32 - 100_000, z0);
            border.setSize(200_000);
            check(!world.canPlayerModifyAt(h.player, new net.minecraft.util.math.BlockPos(x0 + 40, 110, z0 + 20)),
                    "the east is not protected");
            int seq = stroke(h, executor, 40, cube, List.of(at(0, x0 + 30, 115, z0 + 20)), 1);
            check(h.events.dabRejections.isEmpty(), "a partly protected shape was refused: " + h.events.dabRejections);
            String diff = differs(world, area, expected);
            check(diff.isEmpty(), "across the border: " + diff);

            WorldSnapshot afterFirst = capture(world, area);
            stroke(h, executor, 41, cube, List.of(at(0, x0 + 48, 115, z0 + 20)), seq);
            check(h.events.dabRejections.equals(List.of(RejectReason.PROTECTED)), "refusals: " + h.events.dabRejections);
            checkSame(afterFirst, capture(world, area), "after the wholly protected shape");
        } finally {
            border.setCenter(centerX, centerZ);
            border.setSize(size);
        }
        check(h.service.historyService().undoEntries(h.player.getUuid()).size() == 1, "one entry: the first cube");
        undoAll(h, executor, 1);
        checkSame(before, capture(world, area), "after the undo");
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    /**
     * Refusals write nothing: a player without the brush permission is refused at the stroke's start; a mirrored copy
     * in an unloaded chunk refuses the dab UNLOADED; a job holding a section only a long shape's box reaches (16 blocks
     * east of the dab, beyond a terrain brush's area) refuses it AREA_BUSY.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_shape_refused", tickLimit = LIMIT)
    public void refusalsWriteNothing(TestContext context) {
        EditExecutor<ServerWorld> executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] corner = regionCorner(context, 688);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 95, z0, x0 + 47, 150, z0 + 47);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 48, 48);
        WorldSnapshot before = capture(world, area);
        int glass = h.state("minecraft:glass");
        BrushSpec sphere = shape(3, ShapeSpec.Kind.SPHERE, 7, Facing.UP, ShapeSpec.Mode.PLACE, 0, new Pattern.Single(glass),
                Symmetry.NONE);

        ServerPlayerEntity guest = h.addPlayer(false);
        try {
            h.service.beginStroke(guest, 50, sphere);
            throw new GameTestException("a player without the brush permission began a stroke");
        } catch (EditRejected e) {
            check(e.reason() == RejectReason.NO_PERMISSION, "refused with " + e.reason());
        }

        BrushSymmetryGameTest.begin(h, 51, sphere.withSymmetry(new Symmetry(Symmetry.Mode.MIRROR_X, 2 * (x0 + 3000), 0)));
        DabOutcome unloaded = h.service.dabs(h.player, 51, 1, List.of(at(0, x0 + 10, 115, z0 + 10)));
        check(!unloaded.accepted() && unloaded.reason() == RejectReason.UNLOADED, "a copy in an unloaded chunk: " + unloaded);
        h.service.endStroke(h.player, 51);

        // A cylinder 41 long lying east, centred on x0 + 22: its box reaches x0 + 42, into the section x0 + 32 to x0 + 47,
        // which a terrain brush of its radius at the same dab (x0 + 19 to x0 + 25) does not touch.
        RecordingListener fill = new RecordingListener();
        h.fill(box(x0 + 38, 120, z0 + 22, x0 + 39, 120, z0 + 22), "minecraft:glass", fill);
        BrushSpec lying = shape(1, ShapeSpec.Kind.CYLINDER, 41, Facing.EAST, ShapeSpec.Mode.PLACE, 0, new Pattern.Single(glass),
                Symmetry.NONE);
        BrushSymmetryGameTest.begin(h, 52, lying);
        DabOutcome busy = h.service.dabs(h.player, 52, 2, List.of(at(0, x0 + 22, 125, z0 + 22)));
        check(!busy.accepted() && busy.reason() == RejectReason.AREA_BUSY, "a lock in the shape's box: " + busy);
        h.service.endStroke(h.player, 52);
        MultiplayerGameTest.tickUntil(executor, () -> fill.result != null, 20, "the fill");
        undoAll(h, executor, 1);
        checkSame(before, capture(world, area), "after the refusals and undoing the fill");
        check(h.service.historyService().undoEntries(h.player.getUuid()).isEmpty(), "no shape entry");
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    /**
     * An executor whose brush lane runs one item a tick (a dab, a part of a large step, or a slice of a commit): its
     * share of the budget is a microsecond, used up by any work. Jobs keep a full budget.
     */
    static EditExecutor<ServerWorld> onePartATick(TestContext context) {
        return onePartATick(context, 1024);
    }

    /** {@link #onePartATick(TestContext)} with a brush queue of at most {@code maxBrushQueue} items. */
    static EditExecutor<ServerWorld> onePartATick(TestContext context, int maxBrushQueue) {
        return new EditExecutor<>(EngineTestSupport.runtime(context),
                new EditExecutor.Settings(50_000_000L, 0, 0.00002, 2, 8, 32, 64, UnloadedPolicy.LOAD, maxBrushQueue,
                        16_384));
    }

    /**
     * The lane serves players in turn: while one player's large step is written a part a tick, another player's small
     * dabs are written (and acknowledged) between its parts, not after it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_shape_turns", tickLimit = LIMIT)
    public void anotherPlayersDabsRunBetweenTheParts(TestContext context) {
        EditExecutor<ServerWorld> executor = onePartATick(context);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] corner = regionCorner(context, 692);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 90, z0, x0 + 95, 150, z0 + 47);
        loadAndForce(world, area);
        WorldSnapshot before = capture(world, area);
        int glass = h.state("minecraft:glass");
        ServerPlayerEntity other = h.addPlayer();
        // 41 layers of 41 x 41 cells: five parts.
        BrushSpec large = shape(20, ShapeSpec.Kind.CUBE, 41, Facing.UP, ShapeSpec.Mode.PLACE, 0, new Pattern.Single(glass),
                Symmetry.NONE);
        BrushSpec small = shape(2, ShapeSpec.Kind.SPHERE, 5, Facing.UP, ShapeSpec.Mode.PLACE, 0, new Pattern.Single(glass),
                Symmetry.NONE);
        BrushSymmetryGameTest.begin(h, 70, large);
        check(h.service.dabs(h.player, 70, 100, List.of(at(0, x0 + 24, 120, z0 + 24))).accepted(), "the large step");
        try {
            h.service.beginStroke(other, 71, small);
        } catch (EditRejected e) {
            throw new GameTestException("the other player's stroke was refused: " + e.getMessage());
        }
        DabOutcome smallDabs = h.service.dabs(other, 71, 200,
                List.of(at(0, x0 + 70, 120, z0 + 24), at(1, x0 + 76, 120, z0 + 24)));
        check(smallDabs.accepted(), "the small dabs: " + smallDabs);
        executor.tick(); // the large step's first part
        check(h.acks.seqs.isEmpty(), "acknowledged " + h.acks.seqs);
        executor.tick(); // the other player's first dab
        executor.tick(); // the large step's second part
        executor.tick(); // the other player's second dab
        check(h.acks.seqs.equals(List.of(200)), "the small dabs first: " + h.acks.seqs);
        check(h.service.queuedDabs(h.player.getUuid()) == 1, "the large step is still being written");
        MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 2, 20, "the large step");
        check(h.acks.seqs.equals(List.of(200, 100)), "acks " + h.acks.seqs);
        check(h.events.dabRejections.isEmpty(), "dabs rejected: " + h.events.dabRejections);
        check(world.getBlockState(new net.minecraft.util.math.BlockPos(x0 + 24, 138, z0 + 24)).isOf(Blocks.GLASS)
                && world.getBlockState(new net.minecraft.util.math.BlockPos(x0 + 76, 120, z0 + 24)).isOf(Blocks.GLASS),
                "both strokes were written");
        h.service.endStroke(other, 71);
        h.service.endStroke(h.player, 70);
        MultiplayerGameTest.tickUntil(executor, () -> h.service.queuedCommits(h.player.getUuid()) == 0, 200, "the commit");
        undoAll(h, executor, 1);
        RecordingListener undo = MultiplayerGameTest.historyStep(h, other, true);
        MultiplayerGameTest.tickUntil(executor, () -> undo.result != null, 20, "the other player's undo");
        checkSame(before, capture(world, area), "after undoing both strokes");
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    /**
     * A partial failure: another player's job admitted over the upper layers of a large step after its first part is
     * written stops the step AREA_BUSY at its second part, and the stroke stays refused. The layers written stay, recorded as the stroke's
     * one entry; undoing it and the job restores the area exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_shape_partial", tickLimit = LIMIT)
    public void aLockMidStepStopsItAndWhatWasWrittenUndoesExactly(TestContext context) {
        EditExecutor<ServerWorld> executor = onePartATick(context);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity other = h.addPlayer();
        ServerWorld world = h.world;
        int[] corner = regionCorner(context, 694);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 95, z0, x0 + 47, 150, z0 + 47);
        loadAndForce(world, area);
        WorldSnapshot before = capture(world, area);
        int glass = h.state("minecraft:glass");
        // 41 layers of 41 x 41 cells, y 100 to 140, in five parts: 100-108, 109-117, 118-126, 127-135 and 136-140.
        BrushSpec large = shape(20, ShapeSpec.Kind.CUBE, 41, Facing.UP, ShapeSpec.Mode.PLACE, 0, new Pattern.Single(glass),
                Symmetry.NONE);
        BrushSymmetryGameTest.begin(h, 80, large);
        check(h.service.dabs(h.player, 80, 1, List.of(at(0, x0 + 24, 120, z0 + 24))).accepted(), "the large step");
        executor.tick(); // the first part
        check(h.acks.seqs.isEmpty(), "the step was acknowledged after one part");
        // A job over the section y 112-127, which the second part reaches.
        RecordingListener fill = new RecordingListener();
        h.fill(other, box(x0 + 30, 120, z0 + 30, x0 + 31, 120, z0 + 31), "minecraft:white_wool", fill);
        MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 1, 20, "the step");
        check(h.events.dabRejections.equals(List.of(RejectReason.AREA_BUSY)), "refusals " + h.events.dabRejections);
        check(world.getBlockState(new net.minecraft.util.math.BlockPos(x0 + 24, 108, z0 + 24)).isOf(Blocks.GLASS),
                "the first part is written");
        check(!world.getBlockState(new net.minecraft.util.math.BlockPos(x0 + 24, 109, z0 + 24)).isOf(Blocks.GLASS),
                "the second part is not");
        DabOutcome later = h.service.dabs(h.player, 80, 2, List.of(at(1, x0 + 26, 120, z0 + 24)));
        check(!later.accepted() && later.reason() == RejectReason.AREA_BUSY, "the stroke stays refused: " + later);
        h.service.endStroke(h.player, 80);
        MultiplayerGameTest.tickUntil(executor,
                () -> fill.result != null && h.service.queuedCommits(h.player.getUuid()) == 0, 50, "the fill and the commit");
        List<HistoryEntry> entries = h.service.historyService().undoEntries(h.player.getUuid());
        String label = String.format(Locale.ROOT, "Shape · %,d blocks", 41 * 41 * 9);
        check(entries.size() == 1 && entries.get(0).label().equals(label), "one entry " + label + ": " + entries);
        undoAll(h, executor, 1);
        RecordingListener undoFill = MultiplayerGameTest.historyStep(h, other, true);
        MultiplayerGameTest.tickUntil(executor, () -> undoFill.result != null, 20, "undoing the fill");
        checkSame(before, capture(world, area), "after undoing the partly written stroke and the fill");
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    /**
     * Block entities: Place, Paint and Carve over a chest holding items and a written sign replace them (dropping
     * nothing) and undo restores them with their contents exactly; Place in air leaves them as they were.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_shape_tiles", tickLimit = LIMIT)
    public void blockEntitiesAreReplacedAndUndoneWithTheirContents(TestContext context) {
        EditExecutor<ServerWorld> executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] corner = regionCorner(context, 696);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 95, z0, x0 + 31, 125, z0 + 31);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 32, 32);
        net.minecraft.util.math.BlockPos chest = new net.minecraft.util.math.BlockPos(x0 + 16, 112, z0 + 16);
        net.minecraft.util.math.BlockPos sign = chest.east();
        world.setBlockState(chest.down(), Blocks.STONE.getDefaultState());
        world.setBlockState(sign.down(), Blocks.STONE.getDefaultState());
        world.setBlockState(chest, Blocks.CHEST.getDefaultState());
        ((ChestBlockEntity) world.getBlockEntity(chest)).setStack(0, new ItemStack(Items.DIAMOND, 5));
        world.setBlockState(sign, Blocks.OAK_SIGN.getDefaultState());
        ((SignBlockEntity) world.getBlockEntity(sign)).setText(new SignText().withMessage(0, Text.literal("Hi")), true);
        WorldSnapshot before = capture(world, area);
        check(before.tiles.size() == 2, "a chest and a sign: " + before.tiles.size() + " block entities");
        int stone = h.state("minecraft:stone");
        int seq = 1;
        int strokeId = 90;
        for (ShapeSpec.Mode mode : ShapeSpec.Mode.values()) {
            BrushSpec spec = shape(3, ShapeSpec.Kind.SPHERE, 7, Facing.UP, mode, 0,
                    mode == ShapeSpec.Mode.CARVE ? null : new Pattern.Single(stone), Symmetry.NONE);
            seq = stroke(h, executor, strokeId++, spec, List.of(at(0, x0 + 16, 112, z0 + 16)), seq);
            WorldSnapshot after = capture(world, area);
            if (mode == ShapeSpec.Mode.PLACE_IN_AIR) {
                check(after.tiles.equals(before.tiles), "Place in air changed the chest or the sign");
            } else {
                check(after.tiles.isEmpty(), mode + ": the chest and sign are still there");
            }
            check(world.getEntitiesByClass(ItemEntity.class, new net.minecraft.util.math.Box(chest).expand(8), e -> true)
                    .isEmpty(), mode + ": items were dropped");
            check(h.service.historyService().undoEntries(h.player.getUuid()).size() == 1, mode + ": one entry");
            undoAll(h, executor, 1);
            checkSame(before, capture(world, area), "after undoing " + mode);
        }
        check(h.events.dabRejections.isEmpty(), "dabs rejected: " + h.events.dabRejections);
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    /**
     * The size limit when a stroke begins: with {@code maxBrushRadius} 4 a player without {@code limit.bypass} may not
     * begin a cylinder longer than 9 (the largest diameter) or of radius 5, but may a sphere whatever height it carries
     * (its size is its diameter); an op, who has the bypass, may. Changes the shared config for this batch only.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_shape_limits", tickLimit = LIMIT)
    public void shapesTallerThanTheLargestDiameterAreRefused(TestContext context) {
        Harness h = new Harness(context);
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.BRUSH);
        SculptoryConfig.LimitsConfig limits = h.runtime.config().limits;
        int saved = limits.maxBrushRadius;
        Pattern stone = new Pattern.Single(h.state("minecraft:stone"));
        try {
            limits.maxBrushRadius = 4;
            BrushSpec nine = shape(4, ShapeSpec.Kind.CYLINDER, 9, Facing.UP, ShapeSpec.Mode.PLACE, 0, stone, Symmetry.NONE);
            BrushSpec ten = shape(4, ShapeSpec.Kind.CYLINDER, 10, Facing.EAST, ShapeSpec.Mode.PLACE, 0, stone, Symmetry.NONE);
            BrushSpec wide = shape(5, ShapeSpec.Kind.CYLINDER, 9, Facing.UP, ShapeSpec.Mode.PLACE, 0, stone, Symmetry.NONE);
            BrushSpec sphere = shape(4, ShapeSpec.Kind.SPHERE, 65, Facing.UP, ShapeSpec.Mode.PLACE, 0, stone, Symmetry.NONE);
            for (BrushSpec refused : List.of(ten, wide)) {
                EditRejected e = ClipboardGameTest.refusal(() -> h.service.beginStroke(builder, 1, refused));
                check(e.reason() == RejectReason.TOO_LARGE, "refused with " + e.reason() + ": " + e.getMessage());
                check(h.service.openStroke(builder.getUuid()).isEmpty(), "a refused stroke is open");
            }
            int strokeId = 2;
            for (BrushSpec allowed : List.of(nine, sphere)) {
                try {
                    h.service.beginStroke(builder, strokeId, allowed);
                } catch (EditRejected e) {
                    throw new GameTestException("refused " + allowed.shapeSpec() + ": " + e.getMessage());
                }
                h.service.endStroke(builder, strokeId++);
            }
            try {
                h.service.beginStroke(h.player, strokeId, ten);
            } catch (EditRejected e) {
                throw new GameTestException("an op was refused a long shape: " + e.getMessage());
            }
            h.service.endStroke(h.player, strokeId);
        } finally {
            limits.maxBrushRadius = saved;
        }
        h.close();
        context.complete();
    }
}
