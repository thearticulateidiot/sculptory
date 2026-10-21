package dev.sculptory.mc.gametest;

import static dev.sculptory.mc.gametest.EditTestSupport.dab;
import static dev.sculptory.mc.gametest.EngineTestSupport.box;
import static dev.sculptory.mc.gametest.EngineTestSupport.check;
import static dev.sculptory.mc.gametest.GameTestSuite.EMPTY_STRUCTURE;
import static dev.sculptory.mc.gametest.MultiplayerGameTest.tickUntil;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BiomeSpec;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.SculptMode;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.history.store.StorageIo;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.mc.gametest.ClipTestSupport.Captured;
import dev.sculptory.mc.gametest.DurableHistoryGameTest.Run;
import dev.sculptory.mc.gametest.DurableHistoryGameTest.Scene;
import dev.sculptory.mc.gametest.EditTestSupport.Harness;
import dev.sculptory.mc.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.DabOutcome;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobResult;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.impl.EditExecutor;
import dev.sculptory.server.engine.impl.EngineEditService;
import dev.sculptory.server.engine.impl.ServerClipboards;
import dev.sculptory.server.platform.WorldBiomes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Biomes (protocol 6) on a real world: the Biome fill sets every quart its region touches (whole columns or 3D) and the
 * Biome brush paints quarts around its dabs, each one undo step that undoes and redoes exactly; a quart painted since is
 * kept and counted, and Undo anyway takes it back; biome history survives a restart; a player without the biome node, or
 * an unknown biome, is refused with nothing written; copy and paste with Biomes on (off pastes none), move and stack
 * carry biomes with their Biomes box ticked (unticked they leave biomes where they are; ticked without the biome node
 * they are refused); a watching client receives the game's biome packets; and the brush keeps a quart whose centre is
 * protected at its height. Region slots 1500-1513 and 1800-1802.
 */
public final class BiomesGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;
    private static final int TICKS = 2000;
    private static final String CHERRY = "minecraft:cherry_grove";
    private static final String DESERT = "minecraft:desert";

    /** The biome ids of every quart of the columns of {@code box} (full height), by packed key. */
    private static Map<Long, String> biomes(Harness h, Box box) {
        WorldBiomes world = h.runtime.biomes(h.world);
        Map<Long, String> out = new HashMap<>();
        int bottom = h.world.getMinBuildHeight() >> 2, top = (h.world.getMaxBuildHeight() >> 2) - 1;
        for (int qx = box.min().x() >> 2; qx <= box.max().x() >> 2; qx++) {
            for (int qz = box.min().z() >> 2; qz <= box.max().z() >> 2; qz++) {
                for (int qy = bottom; qy <= top; qy++) {
                    out.put(dev.sculptory.core.history.BiomeHistory.key(qx, qy, qz), world.id(world.get(qx, qy, qz)));
                }
            }
        }
        return out;
    }

    private static String biome(Harness h, int qx, int qy, int qz) {
        WorldBiomes world = h.runtime.biomes(h.world);
        return world.id(world.get(qx, qy, qz));
    }

    /** Sets every quart of the columns of {@code box} to {@code id}, as the world was before the test. */
    private static void paintAll(Harness h, Box box, String id) {
        WorldBiomes world = h.runtime.biomes(h.world);
        int handle = world.handle(id);
        int bottom = h.world.getMinBuildHeight() >> 2, top = (h.world.getMaxBuildHeight() >> 2) - 1;
        for (int qx = box.min().x() >> 2; qx <= box.max().x() >> 2; qx++) {
            for (int qz = box.min().z() >> 2; qz <= box.max().z() >> 2; qz++) {
                for (int qy = bottom; qy <= top; qy++) world.set(qx, qy, qz, handle);
            }
        }
        world.flush();
    }

    private static void checkBiomes(Map<Long, String> expected, Map<Long, String> actual, String what) {
        int wrong = 0;
        String first = null;
        for (Map.Entry<Long, String> quart : expected.entrySet()) {
            String got = actual.get(quart.getKey());
            if (quart.getValue().equals(got)) continue;
            wrong++;
            if (first == null) {
                long key = quart.getKey();
                first = dev.sculptory.core.history.BiomeHistory.keyX(key) + ","
                        + dev.sculptory.core.history.BiomeHistory.keyY(key) + ","
                        + dev.sculptory.core.history.BiomeHistory.keyZ(key) + ": " + got + " instead of " + quart.getValue();
            }
        }
        check(wrong == 0, what + ": " + wrong + " quarts differ; first " + first);
    }

    private static JobResult run(Run run, ServerPlayer player, OpSpec op) {
        RecordingListener listener = new RecordingListener();
        try {
            run.service.run(player, op, RunOptions.DEFAULT, listener);
        } catch (EditRejected e) {
            throw new GameTestAssertException(op.getClass().getSimpleName() + " was refused: " + e.getMessage());
        }
        tickUntil(run.executor, () -> listener.result != null, TICKS, op.getClass().getSimpleName());
        run.service.tick();
        check(listener.result.outcome() == JobOutcome.COMPLETED, op.getClass().getSimpleName() + " " + listener.result);
        return listener.result;
    }

    private static List<String> undoLabels(Run run, ServerPlayer player) {
        return run.service.historyService().undoEntries(player.getUUID()).stream().map(HistoryEntry::label).toList();
    }

    private static BrushSpec brush(int radius, boolean columns) {
        return new BrushSpec(BrushTool.BIOME, radius, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L,
                null, Symmetry.NONE, null, SculptMode.TERRAIN, null, null, null, new BiomeSpec(CHERRY, columns));
    }

    /** A stroke of {@code dabs} with {@code spec}, ended and committed. */
    private static void stroke(Run run, ServerPlayer player, int id, BrushSpec spec, List<Dab> dabs) {
        try {
            run.service.beginStroke(player, id, spec);
        } catch (EditRejected e) {
            throw new GameTestAssertException("the stroke was refused: " + e.getMessage());
        }
        DabOutcome outcome = run.service.dabs(player, id, 1, dabs);
        check(outcome.accepted(), "the dabs " + outcome);
        tickUntil(run.executor, () -> run.service.queuedDabs(player.getUUID()) == 0, TICKS, "the dabs");
        int before = undoLabels(run, player).size();
        run.service.endStroke(player, id);
        tickUntil(run.executor, () -> undoLabels(run, player).size() > before, TICKS, "the stroke's commit");
        run.service.tick();
    }

    // ---------------------------------------------------------------------------------------------- fill and brush

    /**
     * A columns fill sets two whole quart columns, a 3D fill two quarts; each is one undo step labelled with its biome
     * cells; undoing both, redoing both and undoing again leave exactly the biomes before each.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_biome_fill", timeoutTicks = LIMIT)
    public void aFillSetsTheQuartsItTouchesAndUndoesAndRedoesExactly(GameTestHelper context) {
        try (Scene s = new Scene(context, 1500)) {
            Harness h = s.h;
            int x = s.x, z = s.z;
            s.area(box(x, 96, z, x + 15, 107, z + 15), 99);
            paintAll(h, s.region, DESERT);
            Map<Long, String> original = biomes(h, s.region);
            Run run = s.run();
            run.load(h.player);
            // x + 1..6 and z + 1..2 touch quart columns 0 and 1 by 0: 2 columns of 96 quarts.
            JobResult columns = run(run, h.player, new OpSpec.BiomeFill(new Region.Cuboid(box(x + 1, 100, z + 1, x + 6, 100,
                    z + 2)), CHERRY, true));
            check(columns.changed() == 192, "the columns fill changed " + columns.changed());
            int qx = x >> 2, qz = z >> 2;
            check(biome(h, qx, -16, qz).equals(CHERRY) && biome(h, qx + 1, 79, qz).equals(CHERRY)
                    && biome(h, qx + 2, 25, qz).equals(DESERT), "the columns fill's quarts");
            Map<Long, String> afterColumns = biomes(h, s.region);
            // x + 9..10, y 100..105, z + 9: quart x 2, y 25 and 26, z 2.
            JobResult cells = run(run, h.player, new OpSpec.BiomeFill(new Region.Cuboid(box(x + 9, 100, z + 9, x + 10, 105,
                    z + 9)), CHERRY, false));
            check(cells.changed() == 2, "the 3D fill changed " + cells.changed());
            check(biome(h, qx + 2, 25, qz + 2).equals(CHERRY) && biome(h, qx + 2, 27, qz + 2).equals(DESERT), "3D quarts");
            Map<Long, String> afterBoth = biomes(h, s.region);
            check(undoLabels(run, h.player).equals(List.of("Biome fill · 2 biome cells", "Biome fill · 192 biome cells")),
                    "labels " + undoLabels(run, h.player));

            run.step(h.player, true);
            checkBiomes(afterColumns, biomes(h, s.region), "after undoing the 3D fill");
            run.step(h.player, true);
            checkBiomes(original, biomes(h, s.region), "after undoing both");
            run.step(h.player, false);
            run.step(h.player, false);
            checkBiomes(afterBoth, biomes(h, s.region), "after redoing both");
            run.step(h.player, true);
            run.step(h.player, true);
            checkBiomes(original, biomes(h, s.region), "after undoing both again");
            run.stop();
        }
        context.succeed();
    }

    /**
     * The Biome brush paints whole columns by default and only the quarts in its ball in 3D; each stroke is one step
     * "Biome stroke · N biome cells" and undoes exactly.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_biome_brush", timeoutTicks = LIMIT)
    public void theBrushPaintsColumnsOr3DAndEachStrokeUndoesExactly(GameTestHelper context) {
        try (Scene s = new Scene(context, 1501)) {
            Harness h = s.h;
            int x = s.x, z = s.z;
            s.area(box(x, 96, z, x + 31, 107, z + 31), 99);
            paintAll(h, s.region, DESERT);
            Map<Long, String> original = biomes(h, s.region);
            Run run = s.run();
            run.load(h.player);
            // Radius 1 at block (x + 13, z + 13): only its own quart column's centre (x + 14, z + 14) is within reach.
            stroke(run, h.player, 1, brush(1, true), List.of(dab(0, x + 13, 100, z + 13)));
            int qx = (x + 13) >> 2, qz = (z + 13) >> 2;
            check(biome(h, qx, -16, qz).equals(CHERRY) && biome(h, qx, 79, qz).equals(CHERRY), "the dab's column");
            check(biome(h, qx + 1, 25, qz).equals(DESERT), "a column beside it");
            Map<Long, String> afterColumns = biomes(h, s.region);
            check(undoLabels(run, h.player).equals(List.of("Biome stroke · 96 biome cells")),
                    "labels " + undoLabels(run, h.player));
            // 3D, radius 4 around the corner point (x + 18, 102, z + 18), the centre of quart (x/4 + 4, 25, z/4 + 4):
            // that quart and its six neighbours.
            stroke(run, h.player, 2, brush(4, false), List.of(new Dab(0, (x + 18) * 16, 102 * 16, (z + 18) * 16,
                    Dab.FULL_PRESSURE)));
            int cx = (x >> 2) + 4, cz = (z >> 2) + 4;
            check(biome(h, cx, 25, cz).equals(CHERRY) && biome(h, cx, 26, cz).equals(CHERRY)
                    && biome(h, cx + 1, 26, cz).equals(DESERT), "the ball's quarts");
            check(undoLabels(run, h.player).get(0).equals("Biome stroke · 7 biome cells"),
                    "labels " + undoLabels(run, h.player));
            run.step(h.player, true);
            checkBiomes(afterColumns, biomes(h, s.region), "after undoing the 3D stroke");
            run.step(h.player, true);
            checkBiomes(original, biomes(h, s.region), "after undoing both strokes");
            run.stop();
        }
        context.succeed();
    }

    /**
     * The Biome brush under protection limited in height: a 3D dab's ball reaches the quart below its centre quart,
     * whose centre (y 98) is protected, while the others' centres (y 102 and 106) are not. The stroke paints the six
     * others, keeps that one and is not refused; its undo puts back exactly what it painted. The permits are per cell,
     * asked for the dab's box widened to whole quarts (the GameTest runs check that every quart is asked inside them).
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_biome_height_permit", timeoutTicks = LIMIT)
    public void theBrushKeepsQuartsWhoseCentreIsProtectedAtTheirHeight(GameTestHelper context) {
        try (Scene s = new Scene(context, 1513)) {
            Harness h = s.h;
            int x = s.x, z = s.z;
            s.area(box(x, 96, z, x + 31, 107, z + 31), 99);
            paintAll(h, s.region, DESERT);
            Map<Long, String> original = biomes(h, s.region);
            EditExecutor<ServerLevel> executor = MultiplayerGameTest.executor(context, 0);
            // Every cell of the area at y 99 and below is protected, judged cell by cell.
            Box protectedArea = box(x, -64, z, x + 31, 99, z + 31);
            EngineEditService<ServerPlayer, ServerLevel> service = EditTestSupport.protectedService(h, executor,
                    (px, py, pz) -> !protectedArea.contains(px, py, pz));
            try {
                ServerPlayer player = h.player;
                // 3D, radius 4 around the corner point (x + 18, 102, z + 18), the centre of quart (x/4 + 4, 25, z/4 + 4):
                // that quart and its six neighbours, the one below (y 96..99, centre 98) protected.
                try {
                    service.beginStroke(player, 1, brush(4, false));
                } catch (EditRejected e) {
                    throw new GameTestAssertException("the stroke was refused: " + e.getMessage());
                }
                DabOutcome outcome = service.dabs(player, 1, 1, List.of(new Dab(0, (x + 18) * 16, 102 * 16,
                        (z + 18) * 16, Dab.FULL_PRESSURE)));
                check(outcome.accepted(), "the dab " + outcome);
                tickUntil(executor, () -> service.queuedDabs(player.getUUID()) == 0, TICKS, "the dab");
                service.endStroke(player, 1);
                tickUntil(executor, () -> !service.historyService().undoEntries(player.getUUID()).isEmpty(), TICKS,
                        "the stroke's commit");
                service.tick();
                int cx = (x >> 2) + 4, cz = (z >> 2) + 4;
                check(biome(h, cx, 25, cz).equals(CHERRY) && biome(h, cx, 26, cz).equals(CHERRY)
                        && biome(h, cx + 1, 25, cz).equals(CHERRY), "the allowed quarts of the ball");
                check(biome(h, cx, 24, cz).equals(DESERT), "the quart whose centre is protected was painted");
                List<String> labels = service.historyService().undoEntries(player.getUUID()).stream()
                        .map(HistoryEntry::label).toList();
                check(labels.equals(List.of("Biome stroke · 6 biome cells")), "labels " + labels);

                RecordingListener undone = new RecordingListener();
                try {
                    service.undo(player, dev.sculptory.core.history.ConflictPolicy.SKIP_CONFLICTS, undone);
                } catch (EditRejected e) {
                    throw new GameTestAssertException("the undo was refused: " + e.getMessage());
                }
                tickUntil(executor, () -> undone.result != null, TICKS, "the undo");
                service.tick();
                check(undone.result.outcome() == JobOutcome.COMPLETED && undone.result.skippedProtected() == 0,
                        "the undo " + undone.result);
                checkBiomes(original, biomes(h, s.region), "after the undo");
            } finally {
                executor.shutdown();
                service.shutdown();
            }
        }
        context.succeed();
    }

    /**
     * A quart painted by hand after a fill is kept by its undo and counted; Undo anyway takes it back too. Then a fill's
     * history survives a restart: undone exactly after it, and redone.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_biome_conflict_restart", timeoutTicks = LIMIT)
    public void aQuartPaintedSinceIsKeptUntilUndoAnywayAndHistorySurvivesARestart(GameTestHelper context) {
        try (Scene s = new Scene(context, 1502)) {
            Harness h = s.h;
            int x = s.x, z = s.z;
            s.area(box(x, 96, z, x + 15, 107, z + 15), 99);
            paintAll(h, s.region, DESERT);
            Map<Long, String> original = biomes(h, s.region);
            Run first = s.run();
            first.load(h.player);
            run(first, h.player, new OpSpec.BiomeFill(new Region.Cuboid(box(x, 100, z, x + 7, 103, z + 3)), CHERRY, false));
            int qx = x >> 2, qz = z >> 2;
            // Painted since by someone else's fill.
            ServerPlayer other = h.addPlayer(false);
            EditTestSupport.grant(other, Perm.USE, Perm.BIOME);
            first.load(other);
            run(first, other, new OpSpec.BiomeFill(new Region.Cuboid(box(x, 100, z, x + 3, 103, z + 3)), "minecraft:plains",
                    false));
            JobResult undo = first.step(h.player, true);
            check(undo.skippedConflicts() == 1, "the undo kept " + undo.skippedConflicts());
            check(biome(h, qx, 25, qz).equals("minecraft:plains") && biome(h, qx + 1, 25, qz).equals(DESERT),
                    "kept and restored");
            first.undoAnyway(h.player);
            checkBiomes(original, biomes(h, s.region), "after Undo anyway");
            run(first, h.player, new OpSpec.BiomeFill(new Region.Cuboid(box(x + 8, 100, z + 8, x + 15, 100, z + 15)),
                    CHERRY, true));
            Map<Long, String> filled = biomes(h, s.region);
            first.stop();

            Run second = s.run();
            second.load(h.player);
            check(undoLabels(second, h.player).get(0).equals("Biome fill · 384 biome cells"),
                    "after the restart: " + undoLabels(second, h.player));
            check(second.step(h.player, true).skippedConflicts() == 0, "the undo after the restart met conflicts");
            checkBiomes(original, biomes(h, s.region), "undo after the restart");
            second.step(h.player, false);
            checkBiomes(filled, biomes(h, s.region), "redo after the restart");
            second.stop();
        }
        context.succeed();
    }

    /**
     * Without the biome node a fill, a stroke and a paste with Biomes are refused NO_PERMISSION (the paste even with
     * the clipboard node); an unknown biome is refused INVALID; nothing is written, held or pushed.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_biome_refusals", timeoutTicks = LIMIT)
    public void refusalsWriteNothing(GameTestHelper context) {
        try (Scene s = new Scene(context, 1503)) {
            Harness h = s.h;
            int x = s.x, z = s.z;
            s.area(box(x, 96, z, x + 15, 107, z + 15), 99);
            paintAll(h, s.region, DESERT);
            Map<Long, String> original = biomes(h, s.region);
            Run run = s.run();
            ServerPlayer builder = h.addPlayer(false);
            EditTestSupport.grant(builder, Perm.USE, Perm.REGION, Perm.BRUSH, Perm.CLIPBOARD);
            run.load(builder);
            run.load(h.player);
            Region region = new Region.Cuboid(box(x, 100, z, x + 7, 100, z + 7));
            Clipboard clipboard = Clipboard.copyOf(h.runtime.reader(h.world), box(x, 100, z, x + 3, 101, z + 3),
                    new BlockPos(x, 100, z), null, "test").withBiomesFrom(new BlockPos(x, 100, z),
                            (qx, qy, qz) -> CHERRY);
            UUID mine = run.service.clipboards().install(builder.getUUID(), clipboard).id();
            OpSpec.Paste withBiomes = new OpSpec.Paste(new SourceRef.Clipboard(mine), new BlockPos(x + 8, 100, z + 8),
                    Transform.IDENTITY, PasteOptions.DEFAULT.withBiomes(true));
            List<String> refusals = new ArrayList<>();
            OpSpec.PasteMany stampWithBiomes = new OpSpec.PasteMany(new SourceRef.Clipboard(mine), List.of(
                    new OpSpec.PasteMany.Placement(new BlockPos(x + 8, 100, z + 8),
                            dev.sculptory.core.transform.Orientation.IDENTITY)), PasteOptions.DEFAULT.withBiomes(true),
                    Symmetry.NONE);
            for (OpSpec op : List.of(new OpSpec.BiomeFill(region, CHERRY, true), withBiomes, stampWithBiomes)) {
                EditRejected refused = ClipboardGameTest.refusal(() -> run.service.run(builder, op, RunOptions.DEFAULT,
                        null));
                check(refused.reason() == RejectReason.NO_PERMISSION, op.getClass().getSimpleName() + ": " + refused);
                refusals.add(refused.reason().name());
            }
            EditRejected stroke = ClipboardGameTest.refusal(() -> run.service.beginStroke(builder, 3, brush(3, true)));
            check(stroke.reason() == RejectReason.NO_PERMISSION, "the stroke: " + stroke);

            // Unknown biomes, for a player holding every node: a fill, a stroke, a paste carrying one.
            EditRejected fill = ClipboardGameTest.refusal(() -> run.service.run(h.player,
                    new OpSpec.BiomeFill(region, "sculptory:nowhere", true), RunOptions.DEFAULT, null));
            check(fill.reason() == RejectReason.INVALID, "an unknown biome filled: " + fill);
            BrushSpec nowhere = new BrushSpec(BrushTool.BIOME, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY,
                    0, 0, 1L, null, Symmetry.NONE, null, SculptMode.TERRAIN, null, null, null,
                    new BiomeSpec("sculptory:nowhere", true));
            EditRejected unknownStroke = ClipboardGameTest.refusal(() -> run.service.beginStroke(h.player, 4, nowhere));
            check(unknownStroke.reason() == RejectReason.INVALID, "an unknown biome painted: " + unknownStroke);
            UUID foreign = run.service.clipboards().install(h.player.getUUID(), clipboard.withBiomesFrom(
                    new BlockPos(x, 100, z), (qx, qy, qz) -> "sculptory:nowhere")).id();
            EditRejected paste = ClipboardGameTest.refusal(() -> run.service.run(h.player, new OpSpec.Paste(
                    new SourceRef.Clipboard(foreign), new BlockPos(x + 8, 100, z + 8), Transform.IDENTITY,
                    PasteOptions.DEFAULT.withBiomes(true)), RunOptions.DEFAULT, null));
            check(paste.reason() == RejectReason.INVALID, "a paste of an unknown biome: " + paste);

            // Fills too large to list: a huge mirrored box and a long thin cylinder, for a painter without limit.bypass
            // and for an operator with it (the job's chunk columns still cap it), each refused at once.
            ServerPlayer painter = h.addPlayer(false);
            EditTestSupport.grant(painter, Perm.USE, Perm.BIOME);
            run.load(painter);
            Region huge = new Region.Cuboid(box(-29_000_000, 60, -29_000_000, 29_000_000, 60, 29_000_000));
            Region cylinder = new Region.Shape(box(-29_000_000, 0, -90, 29_000_000, 179, 89),
                    dev.sculptory.core.region.ShapeKind.CYLINDER, dev.sculptory.core.region.Facing.EAST);
            for (ServerPlayer who : List.of(painter, h.player)) {
                for (OpSpec op : List.of(new OpSpec.BiomeFill(huge, CHERRY, true, new Symmetry(Symmetry.Mode.MIRROR_X, 0, 0)),
                        new OpSpec.BiomeFill(cylinder, CHERRY, false), new OpSpec.BiomeFill(cylinder, CHERRY, true))) {
                    long start = System.nanoTime();
                    EditRejected big = ClipboardGameTest.refusal(() -> run.service.run(who, op, RunOptions.DEFAULT, null));
                    check(big.reason() == RejectReason.TOO_LARGE, "a fill too large to list: " + big);
                    check(System.nanoTime() - start < 5_000_000_000L, "refused only after "
                            + (System.nanoTime() - start) / 1_000_000 + " ms");
                }
            }
            check(run.service.jobs(painter.getUUID()).isEmpty(), "a refused fill left a job");
            check(run.service.jobs(builder.getUUID()).isEmpty() && run.service.jobs(h.player.getUUID()).isEmpty(),
                    "a refusal left a job");
            check(undoLabels(run, builder).isEmpty() && undoLabels(run, h.player).isEmpty(), "a refusal pushed history");
            check(run.service.openStroke(builder.getUUID()).isEmpty(), "a refused stroke stayed open");
            checkBiomes(original, biomes(h, s.region), "after the refusals");
            run.stop();
        }
        context.succeed();
    }

    // ---------------------------------------------------------------------------------------------- clipboards

    /**
     * A copy captures the biomes of its quarts: pasted with Biomes off it writes none, on it writes them (one undo
     * restoring them); a move carries biomes to its destination and a stack into every copy; and a client watching
     * the area sees each change through the game's biome packets.
     */
    /** Starts {@code op} without waiting for it. */
    private static RecordingListener start(Run run, ServerPlayer player, OpSpec op) {
        RecordingListener listener = new RecordingListener();
        try {
            run.service.run(player, op, RunOptions.DEFAULT, listener);
        } catch (EditRejected e) {
            throw new GameTestAssertException(op.getClass().getSimpleName() + " was refused: " + e.getMessage());
        }
        return listener;
    }

    private static void awaitOutcome(Run run, RecordingListener listener, JobOutcome outcome, String what) {
        tickUntil(run.executor, () -> listener.result != null, TICKS, what);
        run.service.tick();
        check(listener.result.outcome() == outcome, what + ": " + listener.result);
    }

    /**
     * A biome fill stopped half way, cancelled by its player or by losing the biome node, is undone exactly (an undo
     * needs only the use node); a move and a stack by a player without the biome node leave biomes alone and are not
     * held to the node while they run. The executor writes one chunk column a tick.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_biome_cancel", timeoutTicks = LIMIT)
    public void stoppedFillsUndoExactlyAndMovesWithoutTheNodeLeaveBiomesAlone(GameTestHelper context) {
        try (Scene s = new Scene(context, 1505)) {
            Harness h = s.h;
            int x = s.x, z = s.z;
            s.area(box(x, 96, z, x + 63, 107, z + 31), 99);
            paintAll(h, s.region, DESERT);
            Map<Long, String> original = biomes(h, s.region);
            Run run = s.run(s.dir, 1, StorageIo.SYSTEM);
            ServerPlayer painter = h.addPlayer(false);
            EditTestSupport.grant(painter, Perm.USE, Perm.BIOME);
            run.load(painter);
            OpSpec fill = new OpSpec.BiomeFill(new Region.Cuboid(box(x, 100, z, x + 63, 103, z + 15)), CHERRY, true);
            int qx = x >> 2, qz = z >> 2, lastQx = (x + 63) >> 2;

            RecordingListener cancelled = start(run, painter, fill);
            tickUntil(run.executor, () -> biome(h, qx, 0, qz).equals(CHERRY), 200, "the fill's first column");
            check(biome(h, lastQx, 0, qz).equals(DESERT), "the fill ran to its last column in one tick");
            check(run.service.cancel(painter, run.service.jobs(painter.getUUID()).get(0).jobId()), "the cancel");
            awaitOutcome(run, cancelled, JobOutcome.CANCELLED, "the cancelled fill");
            check(biome(h, lastQx, 0, qz).equals(DESERT), "the cancelled fill went on");
            run.step(painter, true);
            checkBiomes(original, biomes(h, s.region), "the cancelled fill's undo");

            RecordingListener revoked = start(run, painter, fill);
            tickUntil(run.executor, () -> biome(h, qx, 0, qz).equals(CHERRY), 200, "the second fill's first column");
            EditTestSupport.grant(painter, Perm.USE);
            check(run.service.revalidate(painter, true) == 1, "losing the biome node cancels the fill");
            awaitOutcome(run, revoked, JobOutcome.CANCELLED, "the fill without the node");
            check(biome(h, lastQx, 0, qz).equals(DESERT), "the fill went on without the node");
            run.step(painter, true);
            checkBiomes(original, biomes(h, s.region), "the undo with only the use node");

            // A cherry grove quart in a source a player without the biome node moves and stacks.
            WorldBiomes world = h.runtime.biomes(h.world);
            world.set(qx, 25, qz + 4, world.handle(CHERRY));
            world.flush();
            Map<Long, String> before = biomes(h, s.region);
            ServerPlayer builder = h.addPlayer(false);
            EditTestSupport.grant(builder, Perm.USE, Perm.REGION);
            run.load(builder);
            Box source = box(x, 100, z + 16, x + 7, 103, z + 23);
            RecordingListener move = start(run, builder, new OpSpec.Move(new Region.Cuboid(source), new BlockPos(32, 0, 0),
                    Transform.IDENTITY, new Pattern.Single(h.state("minecraft:air")), EntityFilter.NONE));
            check(run.service.revalidate(builder, true) == 0, "a move without the biome node is held to it");
            awaitOutcome(run, move, JobOutcome.COMPLETED, "the move");
            RecordingListener stack = start(run, builder, new OpSpec.Stack(new Region.Cuboid(source), 8, 0, 0, 2,
                    EntityFilter.NONE));
            check(run.service.revalidate(builder, true) == 0, "a stack without the biome node is held to it");
            awaitOutcome(run, stack, JobOutcome.COMPLETED, "the stack");
            checkBiomes(before, biomes(h, s.region), "a move and a stack without the biome node");
            run.stop();
        }
        context.succeed();
    }

    /**
     * A move overlapping its source carries its biomes exactly (every source quart read before any is written) and
     * undoes exactly, while the job never holds more chunk tickets than its cap (64 here): the columns it reads and
     * those it writes each have a ticket window of their own. 64 cells a tick, so the stage spans many ticks.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_biome_overlap", timeoutTicks = LIMIT)
    public void anOverlappingMoveCarriesBiomesWithinItsTicketCap(GameTestHelper context) {
        try (Scene s = new Scene(context, 1506)) {
            Harness h = s.h;
            int x = s.x, z = s.z;
            s.area(box(x, 96, z, x + 175, 107, z + 175), 99);
            paintAll(h, s.region, DESERT);
            // The source, 10 × 10 chunk columns: cherry grove in a checkerboard of quarts at y 100..103.
            WorldBiomes world = h.runtime.biomes(h.world);
            Box source = box(x, 100, z, x + 159, 100, z + 159);
            for (int qx = x >> 2; qx <= (x + 159) >> 2; qx++) {
                for (int qz = z >> 2; qz <= (z + 159) >> 2; qz++) {
                    if (((qx + qz) & 1) == 0) world.set(qx, 25, qz, world.handle(CHERRY));
                }
            }
            world.flush();
            Map<Long, String> before = biomes(h, s.region);
            Run run = s.run(s.dir, 64, StorageIo.SYSTEM);
            run.load(h.player);
            RecordingListener move = start(run, h.player, new OpSpec.Move(new Region.Cuboid(source),
                    new BlockPos(4, 0, 0), Transform.IDENTITY, new Pattern.Single(h.state("minecraft:air")),
                    EntityFilter.NONE).withBiomes(true));
            int[] most = {0};
            tickUntil(run.executor, () -> {
                most[0] = Math.max(most[0], run.executor.ticketsInFlight());
                return move.result != null;
            }, 20_000, "the move");
            run.service.tick();
            check(move.result.outcome() == JobOutcome.COMPLETED, "the move " + move.result);
            check(most[0] <= 64, "the move held " + most[0] + " chunk tickets");
            // Every quart one east took its western neighbour's biome as it was before the move.
            for (int qx = (x >> 2) + 1; qx <= ((x + 159) >> 2) + 1; qx++) {
                for (int qz = z >> 2; qz <= (z + 159) >> 2; qz++) {
                    String expected = before.get(dev.sculptory.core.history.BiomeHistory.key(qx - 1, 25, qz));
                    check(expected.equals(biome(h, qx, 25, qz)), "quart " + qx + "," + qz + ": " + biome(h, qx, 25, qz)
                            + " instead of " + expected);
                }
            }
            run.step(h.player, true);
            checkBiomes(before, biomes(h, s.region), "the overlapping move's undo");

            // Two selected cells 29 million blocks apart: a move and a stack carrying biomes are admitted (or refused)
            // at once, listing only the cells' own columns; they are cancelled before any far chunk loads.
            dev.sculptory.core.region.CellSet.Builder far = dev.sculptory.core.region.CellSet.builder();
            far.add(x, 100, z);
            far.add(29_000_000, 100, 29_000_000);
            Region cells = new Region.Cells(far.build());
            for (OpSpec op : List.of(new OpSpec.Move(cells, new BlockPos(4, 0, 0), Transform.IDENTITY,
                    new Pattern.Single(h.state("minecraft:air")), EntityFilter.NONE).withBiomes(true),
                    new OpSpec.Stack(cells, 0, 0, 8, 2, EntityFilter.NONE).withBiomes(true))) {
                long started = System.nanoTime();
                try {
                    run.service.run(h.player, op, RunOptions.DEFAULT, new RecordingListener());
                } catch (EditRejected e) {
                    // Refused is fine; quickly is the point.
                }
                long millis = (System.nanoTime() - started) / 1_000_000;
                run.service.cancelAll(h.player);
                tickUntil(run.executor, () -> run.service.jobs(h.player.getUUID()).isEmpty(), 200, "the cancel");
                check(millis < 2_000, op.getClass().getSimpleName() + " of far-apart cells took " + millis + " ms");
            }
            run.stop();
        }
        context.succeed();
    }

    /**
     * What a free move of the box {@code box} by {@code offset} through {@code orientation} carries, worked out with the
     * orientation's own sampler: each quart of the result box takes the biome {@code before} held at the source quart
     * its clamped centre maps back to; {@code before} with those quarts replaced.
     */
    private static Map<Long, String> carriedByFreeMove(Map<Long, String> before, Box box, BlockPos offset,
                                                       dev.sculptory.core.transform.Orientation orientation, int bottomY,
                                                       int topYExclusive) {
        Map<Long, String> expected = new HashMap<>(before);
        int sx = box.sizeX(), sy = box.sizeY(), sz = box.sizeZ();
        dev.sculptory.core.transform.Orientation.Bounds turned = orientation.bounds(sx, sy, sz);
        BlockPos min = box.min().offset(turned.offset().x() + offset.x(), turned.offset().y() + offset.y(),
                turned.offset().z() + offset.z());
        BlockPos max = min.offset(turned.size().x() - 1, turned.size().y() - 1, turned.size().z() - 1);
        for (int qy = Math.max(bottomY, min.y()) >> 2; qy <= Math.min(topYExclusive - 1, max.y()) >> 2; qy++) {
            for (int qz = min.z() >> 2; qz <= max.z() >> 2; qz++) {
                for (int qx = min.x() >> 2; qx <= max.x() >> 2; qx++) {
                    int x = Math.max(min.x(), Math.min(max.x(), (qx << 2) + 2)) - min.x();
                    int y = Math.max(min.y(), Math.min(max.y(), (qy << 2) + 2)) - min.y();
                    int z = Math.max(min.z(), Math.min(max.z(), (qz << 2) + 2)) - min.z();
                    BlockPos cell = orientation.inverseCell(x, y, z, sx, sy, sz);
                    if (cell == null) continue;
                    String id = before.get(dev.sculptory.core.history.BiomeHistory.key((box.min().x() + cell.x()) >> 2,
                            (box.min().y() + cell.y()) >> 2, (box.min().z() + cell.z()) >> 2));
                    if (id != null) expected.put(dev.sculptory.core.history.BiomeHistory.key(qx, qy, qz), id);
                }
            }
        }
        return expected;
    }

    /**
     * A move cancelled while its biome stage reads its source, and one cancelled while it writes, are each undone
     * exactly. The executor writes 64 cells a tick, so both phases span many ticks.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_biome_cancel_phases", timeoutTicks = LIMIT)
    public void aMoveCancelledWhileReadingOrWritingBiomesUndoesExactly(GameTestHelper context) {
        try (Scene s = new Scene(context, 1507)) {
            Harness h = s.h;
            int x = s.x, z = s.z;
            s.area(box(x, 96, z, x + 207, 107, z + 159), 99);
            paintAll(h, s.region, DESERT);
            WorldBiomes world = h.runtime.biomes(h.world);
            Box source = box(x, 100, z, x + 79, 100, z + 159);
            for (int qx = x >> 2; qx <= (x + 79) >> 2; qx++) {
                for (int qz = z >> 2; qz <= (z + 159) >> 2; qz++) world.set(qx, 25, qz, world.handle(CHERRY));
            }
            world.flush();
            // Stone to move, so the test sees when the blocks are done.
            for (int bx = x; bx <= x + 79; bx++) {
                for (int bz = z; bz <= z + 159; bz++) {
                    h.world.setBlockAndUpdate(new net.minecraft.core.BlockPos(bx, 100, bz),
                            net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                }
            }
            Map<Long, String> before = biomes(h, s.region);
            Run run = s.run(s.dir, 64, StorageIo.SYSTEM);
            run.load(h.player);
            OpSpec move = new OpSpec.Move(new Region.Cuboid(source), new BlockPos(128, 0, 0), Transform.IDENTITY,
                    new Pattern.Single(h.state("minecraft:air")), EntityFilter.NONE).withBiomes(true);
            // The destination quarts at y 100..103 that hold cherry grove (all 20 × 40 once the move is done).
            java.util.function.IntSupplier carried = () -> {
                int n = 0;
                for (int qx = (x + 128) >> 2; qx <= (x + 207) >> 2; qx++) {
                    for (int qz = z >> 2; qz <= (z + 159) >> 2; qz++) {
                        if (biome(h, qx, 25, qz).equals(CHERRY)) n++;
                    }
                }
                return n;
            };
            int all = 20 * 40;

            // Read phase: the blocks are all moved, no biome written yet.
            RecordingListener reading = start(run, h.player, move);
            tickUntil(run.executor, () -> {
                if (reading.result != null) return true;
                for (int bx = x + 128; bx <= x + 207; bx++) {
                    for (int bz = z; bz <= z + 159; bz++) {
                        if (!h.world.getBlockState(new net.minecraft.core.BlockPos(bx, 100, bz)).is(
                                net.minecraft.world.level.block.Blocks.STONE)) {
                            return false;
                        }
                    }
                }
                return true;
            }, 20_000, "the move's blocks");
            check(carried.getAsInt() == 0, "the move wrote biomes before it was cancelled");
            check(reading.result == null, "the move ended before its biome stage: " + reading.result);
            run.service.cancelAll(h.player);
            awaitOutcome(run, reading, JobOutcome.CANCELLED, "the move cancelled while reading");
            run.step(h.player, true);
            checkBiomes(before, biomes(h, s.region), "the undo of the move cancelled while reading");

            // Write phase: some quarts written, not all.
            RecordingListener writing = start(run, h.player, move);
            tickUntil(run.executor, () -> carried.getAsInt() > 0 || writing.result != null, 20_000, "the first write");
            check(writing.result == null, "the move finished in one tick: " + writing.result);
            check(carried.getAsInt() < all, "the move wrote all its biomes at once");
            run.service.cancelAll(h.player);
            awaitOutcome(run, writing, JobOutcome.CANCELLED, "the move cancelled while writing");
            run.step(h.player, true);
            checkBiomes(before, biomes(h, s.region), "the undo of the move cancelled while writing");
            run.stop();
        }
        context.succeed();
    }

    /**
     * A move with mirror symmetry carries each copy's own source biomes, and a move turned 30° carries them through the
     * orientation's sampler over its landing cells (the blocks' own); both undo exactly.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_biome_symmetric_free", timeoutTicks = LIMIT)
    public void symmetricAndFreeMovesCarryBiomesAndUndoExactly(GameTestHelper context) {
        try (Scene s = new Scene(context, 1508)) {
            Harness h = s.h;
            int x = s.x, z = s.z;
            s.area(box(x, 96, z, x + 127, 111, z + 63), 99);
            paintAll(h, s.region, DESERT);
            WorldBiomes world = h.runtime.biomes(h.world);
            // Mirrored about x + 24: the original source x..x+7 holds cherry grove, its image x+40..x+47 plains.
            for (int qz = z >> 2; qz <= (z + 7) >> 2; qz++) {
                for (int qx = x >> 2; qx <= (x + 7) >> 2; qx++) world.set(qx, 25, qz, world.handle(CHERRY));
                for (int qx = (x + 40) >> 2; qx <= (x + 47) >> 2; qx++) {
                    world.set(qx, 25, qz, world.handle("minecraft:plains"));
                }
            }
            world.flush();
            Map<Long, String> before = biomes(h, s.region);
            Run run = s.run();
            run.load(h.player);
            Pattern air = new Pattern.Single(h.state("minecraft:air"));
            Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 2 * x + 48, 0);
            run(run, h.player, new OpSpec.Move(new Region.Cuboid(box(x, 100, z, x + 7, 103, z + 7)),
                    new BlockPos(8, 0, 0), dev.sculptory.core.transform.Orientation.IDENTITY, air, EntityFilter.NONE,
                    mirror, PasteOptions.Into.EVERYTHING, true));
            check(biome(h, (x + 8) >> 2, 25, z >> 2).equals(CHERRY) && biome(h, (x + 15) >> 2, 25, (z + 7) >> 2)
                    .equals(CHERRY), "the original carried its cherry grove");
            check(biome(h, (x + 32) >> 2, 25, z >> 2).equals("minecraft:plains")
                    && biome(h, (x + 39) >> 2, 25, (z + 7) >> 2).equals("minecraft:plains"),
                    "the mirrored copy carried its own source's plains");
            run.step(h.player, true);
            checkBiomes(before, biomes(h, s.region), "the symmetric move's undo");

            // A free move: 16 × 4 × 16 of cherry grove turned 30° about its middle, 64 east.
            Box free = box(x, 100, z + 32, x + 15, 103, z + 47);
            for (int qx = x >> 2; qx <= (x + 15) >> 2; qx++) {
                for (int qz = (z + 32) >> 2; qz <= (z + 47) >> 2; qz++) world.set(qx, 25, qz, world.handle(CHERRY));
            }
            world.flush();
            Map<Long, String> beforeFree = biomes(h, s.region);
            dev.sculptory.core.transform.Orientation turned =
                    dev.sculptory.core.transform.Orientation.free(Transform.IDENTITY, 3000, 0, 0);
            BlockPos offset = new BlockPos(64, 0, 0);
            run(run, h.player, new OpSpec.Move(new Region.Cuboid(free), offset, turned, air, EntityFilter.NONE,
                    Symmetry.NONE, PasteOptions.Into.EVERYTHING, true));
            Map<Long, String> expected = carriedByFreeMove(beforeFree, free, offset, turned, h.world.getMinBuildHeight(),
                    h.world.getMaxBuildHeight());
            check(!expected.equals(beforeFree), "the free move carries nothing");
            checkBiomes(expected, biomes(h, s.region), "the free move's biomes");
            run.step(h.player, true);
            checkBiomes(beforeFree, biomes(h, s.region), "the free move's undo");
            run.stop();
        }
        context.succeed();
    }

    /**
     * A stamp (a multi-paste) with Biomes ticked carries the clipboard's biomes at a free angle, as a paste maps them,
     * and undoes exactly; without the biome node it is refused NO_PERMISSION, asked before anything else.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_biome_stamp", timeoutTicks = LIMIT)
    public void aStampWithBiomesCarriesThemAtAFreeAngle(GameTestHelper context) {
        try (Scene s = new Scene(context, 1509)) {
            Harness h = s.h;
            int x = s.x, z = s.z;
            s.area(box(x, 96, z, x + 63, 111, z + 63), 99);
            paintAll(h, s.region, DESERT);
            Map<Long, String> before = biomes(h, s.region);
            // A 12 × 4 × 12 clipboard of stone, cherry grove except one plains quart.
            BlockPos size = new BlockPos(12, 4, 12);
            Clipboard.Builder builder = Clipboard.builder(h.runtime.states(), size);
            dev.sculptory.core.biome.BiomeGrid.Builder grid = dev.sculptory.core.biome.BiomeGrid.builder(
                    BlockPos.ORIGIN, size);
            int stone = h.state("minecraft:stone");
            for (int cy = 0; cy < 4; cy++) {
                for (int cz = 0; cz < 12; cz++) {
                    for (int cx = 0; cx < 12; cx++) {
                        builder.set(cx, cy, cz, stone);
                        grid.setAt(cx, cy, cz, cx < 4 && cz < 4 ? "minecraft:plains" : CHERRY);
                    }
                }
            }
            Clipboard clipboard = builder.biomes(grid.build()).build();
            Run run = s.run();
            run.load(h.player);
            UUID id = run.service.clipboards().install(h.player.getUUID(), clipboard).id();
            dev.sculptory.core.transform.Orientation turned =
                    dev.sculptory.core.transform.Orientation.free(Transform.IDENTITY, 3000, 0, 0);
            BlockPos origin = new BlockPos(x + 24, 100, z + 24);
            OpSpec stamp = new OpSpec.PasteMany(new SourceRef.Clipboard(id), List.of(new OpSpec.PasteMany.Placement(
                    origin, turned)), PasteOptions.DEFAULT.withBiomes(true));

            ServerPlayer noBiome = h.addPlayer(false);
            EditTestSupport.grant(noBiome, Perm.USE, Perm.CLIPBOARD, Perm.REGION);
            run.load(noBiome);
            EditRejected refused = ClipboardGameTest.refusal(() -> run.service.run(noBiome, stamp, RunOptions.DEFAULT,
                    null));
            check(refused.reason() == RejectReason.NO_PERMISSION && Perm.BIOME.node().equals(refused.detail()),
                    "a stamp with biomes without the node: " + refused.reason() + " " + refused.detail());

            run(run, h.player, stamp);
            Map<Long, String> expected = new HashMap<>(before);
            Box target = dev.sculptory.core.edit.PasteGeometry.pasteTarget(size, clipboard.anchor(), turned, origin);
            dev.sculptory.core.biome.BiomeMapping.map(clipboard.biomes(), size, turned, target.min(),
                    h.world.getMinBuildHeight(), h.world.getMaxBuildHeight(),
                    (qx, qy, qz, biome) -> expected.put(dev.sculptory.core.history.BiomeHistory.key(qx, qy, qz), biome));
            check(expected.containsValue("minecraft:plains") && !expected.equals(before), "the stamp maps nothing");
            checkBiomes(expected, biomes(h, s.region), "the stamp's biomes at 30°");
            run.step(h.player, true);
            checkBiomes(before, biomes(h, s.region), "the stamp's undo");
            run.stop();
        }
        context.succeed();
    }

    private static long quartKey(int qx, int qy, int qz) {
        return dev.sculptory.core.history.BiomeHistory.key(qx, qy, qz);
    }

    /**
     * Into {@code into}, what a free move's symmetric copy carries, as its blocks are taken: each quart of the image of
     * the result box takes its clamped centre back to the original's result cell, samples the original's source cell
     * with the orientation's sampler, and takes the biome {@code before} held under that cell's image.
     */
    private static void carriedByFreeMoveCopy(Map<Long, String> before, Map<Long, String> into, Box box,
                                              BlockPos offset, dev.sculptory.core.transform.Orientation orientation,
                                              Symmetry symmetry, Symmetry.Image image, int bottomY, int topYExclusive) {
        int sx = box.sizeX(), sy = box.sizeY(), sz = box.sizeZ();
        dev.sculptory.core.transform.Orientation.Bounds turned = orientation.bounds(sx, sy, sz);
        BlockPos min = box.min().offset(turned.offset().x() + offset.x(), turned.offset().y() + offset.y(),
                turned.offset().z() + offset.z());
        BlockPos max = min.offset(turned.size().x() - 1, turned.size().y() - 1, turned.size().z() - 1);
        Box imaged = symmetry.imageBox(image, new Box(min, max));
        Symmetry.Image back = image.inverse();
        for (int qy = Math.max(bottomY, min.y()) >> 2; qy <= Math.min(topYExclusive - 1, max.y()) >> 2; qy++) {
            for (int qz = imaged.min().z() >> 2; qz <= imaged.max().z() >> 2; qz++) {
                for (int qx = imaged.min().x() >> 2; qx <= imaged.max().x() >> 2; qx++) {
                    int x = Math.max(imaged.min().x(), Math.min(imaged.max().x(), (qx << 2) + 2));
                    int y = Math.max(min.y(), Math.min(max.y(), (qy << 2) + 2));
                    int z = Math.max(imaged.min().z(), Math.min(imaged.max().z(), (qz << 2) + 2));
                    int px = (int) symmetry.cellX(back, x, z) - min.x(), pz = (int) symmetry.cellZ(back, x, z) - min.z();
                    BlockPos cell = orientation.inverseCell(px, y - min.y(), pz, sx, sy, sz);
                    if (cell == null) continue;
                    int wx = box.min().x() + cell.x(), wy = box.min().y() + cell.y(), wz = box.min().z() + cell.z();
                    int ix = (int) symmetry.cellX(image, wx, wz), iz = (int) symmetry.cellZ(image, wx, wz);
                    String id = before.get(quartKey(ix >> 2, wy >> 2, iz >> 2));
                    if (id != null) into.put(quartKey(qx, qy, qz), id);
                }
            }
        }
    }

    /**
     * The quarts a paste or stamp with Biomes plans, in the blocks' claiming order (the original copy first, each copy's
     * placements in order; the first to plan a quart keeps it): an exact placement's copy maps through its own imaged
     * placement, a free one's copy is the image of the original's result box, each quart's clamped centre taken back to
     * the cell it is the image of and mapped as {@link dev.sculptory.core.biome.BiomeMapping#map} maps the original.
     */
    private static Map<Long, String> pastedBiomes(Clipboard clipboard, List<OpSpec.PasteMany.Placement> originals,
                                                  List<dev.sculptory.core.edit.OpSymmetry.Copy> copies,
                                                  java.util.function.Function<OpSpec, List<OpSpec.PasteMany.Placement>>
                                                          placementsOf, Symmetry symmetry, int bottomY,
                                                  int topYExclusive) {
        Map<Long, String> planned = new java.util.LinkedHashMap<>();
        BlockPos size = clipboard.size();
        dev.sculptory.core.biome.BiomeGrid grid = clipboard.biomes();
        for (dev.sculptory.core.edit.OpSymmetry.Copy copy : copies) {
            List<OpSpec.PasteMany.Placement> imaged = placementsOf.apply(copy.op());
            for (int i = 0; i < originals.size(); i++) {
                OpSpec.PasteMany.Placement original = originals.get(i);
                OpSpec.PasteMany.Placement own = original.orientation().isExact() ? imaged.get(i)
                        : copy.image() == Symmetry.Image.IDENTITY ? original : null;
                if (own != null) {
                    Box target = dev.sculptory.core.edit.PasteGeometry.pasteTarget(size, clipboard.anchor(),
                            own.orientation(), own.origin());
                    dev.sculptory.core.biome.BiomeMapping.map(grid, size, own.orientation(), target.min(), bottomY,
                            topYExclusive, (qx, qy, qz, id) -> planned.putIfAbsent(quartKey(qx, qy, qz), id));
                    continue;
                }
                Box target = dev.sculptory.core.edit.PasteGeometry.pasteTarget(size, clipboard.anchor(),
                        original.orientation(), original.origin());
                Box box = symmetry.imageBox(copy.image(), target);
                Symmetry.Image back = copy.image().inverse();
                for (int qy = Math.max(bottomY, box.min().y()) >> 2; qy <= Math.min(topYExclusive - 1, box.max().y()) >> 2;
                        qy++) {
                    for (int qz = box.min().z() >> 2; qz <= box.max().z() >> 2; qz++) {
                        for (int qx = box.min().x() >> 2; qx <= box.max().x() >> 2; qx++) {
                            int x = Math.max(box.min().x(), Math.min(box.max().x(), (qx << 2) + 2));
                            int y = Math.max(box.min().y(), Math.min(box.max().y(), (qy << 2) + 2));
                            int z = Math.max(box.min().z(), Math.min(box.max().z(), (qz << 2) + 2));
                            int px = (int) symmetry.cellX(back, x, z) - target.min().x();
                            int pz = (int) symmetry.cellZ(back, x, z) - target.min().z();
                            BlockPos cell = original.orientation().inverseCell(px, y - target.min().y(), pz, size.x(),
                                    size.y(), size.z());
                            if (cell == null) continue;
                            String id = grid.biomeAt(cell.x(), cell.y(), cell.z());
                            if (id != null) planned.putIfAbsent(quartKey(qx, qy, qz), id);
                        }
                    }
                }
            }
        }
        return planned;
    }

    /** A 12 × 4 × 12 clipboard of stone: plains under local x 0..3, cherry grove elsewhere (asymmetric in x). */
    private static Clipboard splitClipboard(Harness h) {
        BlockPos size = new BlockPos(12, 4, 12);
        Clipboard.Builder builder = Clipboard.builder(h.runtime.states(), size);
        dev.sculptory.core.biome.BiomeGrid.Builder grid = dev.sculptory.core.biome.BiomeGrid.builder(BlockPos.ORIGIN,
                size);
        int stone = h.state("minecraft:stone");
        for (int cy = 0; cy < 4; cy++) {
            for (int cz = 0; cz < 12; cz++) {
                for (int cx = 0; cx < 12; cx++) {
                    builder.set(cx, cy, cz, stone);
                    grid.setAt(cx, cy, cz, cx < 4 ? "minecraft:plains" : CHERRY);
                }
            }
        }
        return builder.biomes(grid.build()).build();
    }

    /**
     * A free move turned 30° with Mirror X about a half-cell plane, from a source off the quart grid, carries each
     * copy's own source biomes (cherry grove under the original, plains under its mirror image) as its blocks are
     * taken, and undoes exactly.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_biome_free_mirror", timeoutTicks = LIMIT)
    public void aMirroredFreeMoveCarriesEachCopysOwnBiomes(GameTestHelper context) {
        try (Scene s = new Scene(context, 1510)) {
            Harness h = s.h;
            int x = s.x, z = s.z;
            s.area(box(x, 96, z, x + 127, 111, z + 31), 99);
            paintAll(h, s.region, DESERT);
            WorldBiomes world = h.runtime.biomes(h.world);
            Box source = box(x + 1, 100, z + 1, x + 14, 103, z + 10);
            // The plane x = x + 60.5: the mirrored source is x + 106..119.
            Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 2 * x + 121, 0);
            Box mirrored = mirror.imageBox(mirror.images().get(1), source);
            for (int qz = source.min().z() >> 2; qz <= source.max().z() >> 2; qz++) {
                for (int qx = source.min().x() >> 2; qx <= source.max().x() >> 2; qx++) {
                    world.set(qx, 25, qz, world.handle(CHERRY));
                }
                for (int qx = mirrored.min().x() >> 2; qx <= mirrored.max().x() >> 2; qx++) {
                    world.set(qx, 25, qz, world.handle("minecraft:plains"));
                }
            }
            world.flush();
            Map<Long, String> before = biomes(h, s.region);
            Run run = s.run();
            run.load(h.player);
            dev.sculptory.core.transform.Orientation turned =
                    dev.sculptory.core.transform.Orientation.free(Transform.IDENTITY, 3000, 0, 0);
            BlockPos offset = new BlockPos(32, 0, 0);
            run(run, h.player, new OpSpec.Move(new Region.Cuboid(source), offset, turned,
                    new Pattern.Single(h.state("minecraft:air")), EntityFilter.NONE, mirror, PasteOptions.Into.EVERYTHING,
                    true));
            Map<Long, String> expected = carriedByFreeMove(before, source, offset, turned, h.world.getMinBuildHeight(),
                    h.world.getMaxBuildHeight());
            carriedByFreeMoveCopy(before, expected, source, offset, turned, mirror, mirror.images().get(1),
                    h.world.getMinBuildHeight(), h.world.getMaxBuildHeight());
            check(expected.containsValue("minecraft:plains") && expected.values().stream().filter(CHERRY::equals).count()
                    > before.values().stream().filter(CHERRY::equals).count(), "the expectation carries nothing");
            checkBiomes(expected, biomes(h, s.region), "the mirrored free move's biomes");
            run.step(h.player, true);
            checkBiomes(before, biomes(h, s.region), "the mirrored free move's undo");
            run.stop();
        }
        context.succeed();
    }

    /**
     * A free paste and a free stamp, each with Mirror X about a half-cell plane, write their copies' biomes over exactly
     * the copies' boxes, as the blocks; both undo exactly.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_biome_free_paste_mirror", timeoutTicks = LIMIT)
    public void mirroredFreePastesAndStampsCarryBiomes(GameTestHelper context) {
        try (Scene s = new Scene(context, 1511)) {
            Harness h = s.h;
            int x = s.x, z = s.z;
            s.area(box(x, 96, z, x + 127, 111, z + 63), 99);
            paintAll(h, s.region, DESERT);
            Map<Long, String> before = biomes(h, s.region);
            Clipboard clipboard = splitClipboard(h);
            Run run = s.run();
            run.load(h.player);
            UUID id = run.service.clipboards().install(h.player.getUUID(), clipboard).id();
            dev.sculptory.core.transform.Orientation turned =
                    dev.sculptory.core.transform.Orientation.free(Transform.IDENTITY, 3000, 0, 0);
            Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 2 * x + 61, 0);
            OpSpec.Paste paste = new OpSpec.Paste(new SourceRef.Clipboard(id), new BlockPos(x + 9, 100, z + 9), turned,
                    PasteOptions.DEFAULT.withBiomes(true), mirror);
            OpSpec.PasteMany stamp = new OpSpec.PasteMany(new SourceRef.Clipboard(id), List.of(
                    new OpSpec.PasteMany.Placement(new BlockPos(x + 9, 100, z + 40), turned)),
                    PasteOptions.DEFAULT.withBiomes(true), mirror);
            for (OpSpec op : List.<OpSpec>of(paste, stamp)) {
                run(run, h.player, op);
                Map<Long, String> expected = new HashMap<>(before);
                List<dev.sculptory.core.edit.OpSymmetry.Copy> copies = dev.sculptory.core.edit.OpSymmetry.copies(op);
                expected.putAll(pastedBiomes(clipboard, placementsOf().apply(op), copies, placementsOf(),
                        mirror, h.world.getMinBuildHeight(), h.world.getMaxBuildHeight()));
                check(expected.containsValue("minecraft:plains"), "the expectation maps nothing");
                checkBiomes(expected, biomes(h, s.region), op.getClass().getSimpleName() + " mirrored at 30°");
                run.step(h.player, true);
                checkBiomes(before, biomes(h, s.region), op.getClass().getSimpleName() + "'s undo");
            }
            run.stop();
        }
        context.succeed();
    }

    /** A paste's or stamp's placements, as the stamp's: a paste is one placement. */
    private static java.util.function.Function<OpSpec, List<OpSpec.PasteMany.Placement>> placementsOf() {
        return op -> op instanceof OpSpec.Paste p ? List.of(new OpSpec.PasteMany.Placement(p.origin(), p.orientation()))
                : ((OpSpec.PasteMany) op).placements();
    }

    /**
     * A stamp with Mirror X, one exact placement whose copy overlaps it and one free placement, carries biomes as the
     * blocks claim cells: where the original and its mirror image overlap, the original's biomes win. Undoes exactly.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_biome_stamp_mixed", timeoutTicks = LIMIT)
    public void aMirroredMixedStampKeepsTheOriginalWhereCopiesOverlap(GameTestHelper context) {
        try (Scene s = new Scene(context, 1512)) {
            Harness h = s.h;
            int x = s.x, z = s.z;
            s.area(box(x, 96, z, x + 127, 111, z + 63), 99);
            paintAll(h, s.region, DESERT);
            Map<Long, String> before = biomes(h, s.region);
            Clipboard clipboard = splitClipboard(h);
            Run run = s.run();
            run.load(h.player);
            UUID id = run.service.clipboards().install(h.player.getUUID(), clipboard).id();
            // The plane x = x + 15.5 cuts the exact placement x + 8..19: its mirror image x + 12..23 overlaps it.
            Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 2 * x + 31, 0);
            dev.sculptory.core.transform.Orientation turned =
                    dev.sculptory.core.transform.Orientation.free(Transform.IDENTITY, 3000, 0, 0);
            OpSpec.PasteMany stamp = new OpSpec.PasteMany(new SourceRef.Clipboard(id), List.of(
                    new OpSpec.PasteMany.Placement(new BlockPos(x + 8, 100, z + 4), dev.sculptory.core.transform
                            .Orientation.IDENTITY),
                    // Turned about its true centre, the free placement reaches a few columns west of its origin.
                    new OpSpec.PasteMany.Placement(new BlockPos(x + 10, 100, z + 36), turned)),
                    PasteOptions.DEFAULT.withBiomes(true), mirror);
            List<dev.sculptory.core.edit.OpSymmetry.Copy> copies = dev.sculptory.core.edit.OpSymmetry.copies(stamp);
            List<OpSpec.PasteMany.Placement> originals = stamp.placements();
            int bottom = h.world.getMinBuildHeight(), top = h.world.getMaxBuildHeight();
            Map<Long, String> planned = pastedBiomes(clipboard, originals, copies, placementsOf(), mirror, bottom, top);
            // Pin the rule: some quart the original and its copy both write gets different biomes from each, and the
            // original's is kept.
            Map<Long, String> originalOnly = pastedBiomes(clipboard, originals, List.of(copies.get(0)), placementsOf(),
                    mirror, bottom, top);
            Map<Long, String> copyOnly = pastedBiomes(clipboard, originals, List.of(copies.get(1)), placementsOf(),
                    mirror, bottom, top);
            long contested = originalOnly.keySet().stream().filter(k -> copyOnly.containsKey(k)
                    && !copyOnly.get(k).equals(originalOnly.get(k))).count();
            check(contested > 0, "the copies do not overlap with different biomes");
            check(originalOnly.keySet().stream().allMatch(k -> originalOnly.get(k).equals(planned.get(k))),
                    "the original does not win");
            run(run, h.player, stamp);
            Map<Long, String> expected = new HashMap<>(before);
            expected.putAll(planned);
            checkBiomes(expected, biomes(h, s.region), "the mixed mirrored stamp's biomes, the original winning");
            run.step(h.player, true);
            checkBiomes(before, biomes(h, s.region), "the mixed mirrored stamp's undo");
            run.stop();
        }
        context.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_biome_clipboard", timeoutTicks = LIMIT)
    public void copiesCarryBiomesAndClientsSeeThem(GameTestHelper context) {
        // The copy's answer comes back through the server's task queue: the steps run on ticks of their own.
        Scene s = new Scene(context, 1504);
        Captured<ClipboardService.ClipboardInfo>[] copied = new Captured[1];
        BenchSupport.Watcher[] watcher = new BenchSupport.Watcher[1];
        ClientView view = new ClientView(s.h.world);
        ServerClipboards<ServerPlayer, ServerLevel> clips = ClipTestSupport.clipboards(s.h,
                ClipTestSupport.libraryRoot(context));
        context.startSequence().thenWaitUntil(() -> {
            Harness h = s.h;
            int x = s.x, z = s.z;
            s.area(box(x, 96, z, x + 47, 107, z + 31), 99);
            paintAll(h, s.region, DESERT);
            WorldBiomes world = h.runtime.biomes(h.world);
            // The source: 8 × 4 × 8 at (x, 100, z), cherry grove in its two quart layers.
            Box source = box(x, 100, z, x + 7, 103, z + 7);
            for (int qx = x >> 2; qx <= (x + 7) >> 2; qx++) {
                for (int qz = z >> 2; qz <= (z + 7) >> 2; qz++) world.set(qx, 25, qz, world.handle(CHERRY));
            }
            world.flush();
            Run run = s.run();
            run.load(h.player);
            copied[0] = ClipboardGameTest.copy(clips, h.player, source, new BlockPos(x, 100, z));
            // A client watching where the paste goes: its chunks reach it over the next ticks.
            watcher[0] = BenchSupport.Watcher.join(h.world, x + 20, 110, z + 10, 4).observe(view);
        }).thenWaitUntil(() -> {
            copied[0].get("the copy");
            check(watcher[0].ready(box(s.x + 16, 100, s.z, s.x + 23, 103, s.z + 7)), "the watcher is still loading chunks");
            check(view.biome((s.x + 16) >> 2, 25, s.z >> 2) != null, "the watcher has not received the chunks yet");
            check(!watcher[0].player.isRemoved(), "the watcher was removed: " + watcher[0].player.getRemovalReason());
        }).thenWaitUntil(() -> {
            Harness h = s.h;
            int x = s.x, z = s.z;
            Run run = s.runs.get(0);
            Box source = box(x, 100, z, x + 7, 103, z + 7);
            Clipboard held = h.service.clipboards().get(h.player.getUUID()).orElseThrow().clipboard();
            check(held.biomes() != null && CHERRY.equals(held.biomes().biomeAt(0, 0, 0)), "the copy's biomes " + held);
            UUID id = run.service.clipboards().install(h.player.getUUID(), held).id();
            Map<Long, String> before = biomes(h, s.region);
            run(run, h.player, new OpSpec.Paste(new SourceRef.Clipboard(id), new BlockPos(x + 16, 100, z),
                    Transform.IDENTITY, PasteOptions.DEFAULT));
            checkBiomes(before, biomes(h, s.region), "a paste with Biomes off");
            run(run, h.player, new OpSpec.Paste(new SourceRef.Clipboard(id), new BlockPos(x + 16, 100, z),
                    Transform.IDENTITY, PasteOptions.DEFAULT.withBiomes(true)));
            int px = (x + 16) >> 2, pz = z >> 2;
            check(biome(h, px, 25, pz).equals(CHERRY) && biome(h, px + 1, 25, pz + 1).equals(CHERRY)
                    && biome(h, px, 26, pz).equals(DESERT), "the pasted biomes");
            check(undoLabels(run, h.player).get(0).endsWith(" · 4 biome cells"), "label " + undoLabels(run, h.player));
            run.executor.endTick(); // the tick's end sends the biomes
        }).thenWaitUntil(() -> {
            // Retried until the biome packet has come (a step of its own: retrying repeats nothing).
            int px = (s.x + 16) >> 2, pz = s.z >> 2;
            watcher[0].drain();
            check(view.biomePackets > 0 && CHERRY.equals(view.biome(px, 25, pz)) && DESERT.equals(view.biome(px, 26, pz)),
                    "the client sees " + view.biome(px, 25, pz) + " after " + view.biomePackets + " biome packets");
        }).thenWaitUntil(() -> {
            s.runs.get(0).step(s.h.player, true);
            check(biome(s.h, (s.x + 16) >> 2, 25, s.z >> 2).equals(DESERT), "the paste's undo");
            s.runs.get(0).executor.endTick();
        }).thenWaitUntil(() -> {
            watcher[0].drain();
            check(DESERT.equals(view.biome((s.x + 16) >> 2, 25, s.z >> 2)), "the client sees the undo");
        }).thenWaitUntil(() -> {
            watcher[0].close();
            Harness h = s.h;
            int x = s.x, z = s.z;
            Run run = s.runs.get(0);
            Box source = box(x, 100, z, x + 7, 103, z + 7);
            // A move by 32 east with Biomes ticked carries the cherry grove; the source keeps its biomes (biomes are
            // never "air").
            run(run, h.player, new OpSpec.Move(new Region.Cuboid(source), new BlockPos(32, 0, 0), Transform.IDENTITY,
                    new Pattern.Single(h.state("minecraft:air")), EntityFilter.NONE).withBiomes(true));
            int mx = (x + 32) >> 2;
            check(biome(h, mx, 25, z >> 2).equals(CHERRY) && biome(h, mx + 1, 25, (z >> 2) + 1).equals(CHERRY),
                    "the moved biomes");
            check(biome(h, x >> 2, 25, z >> 2).equals(CHERRY), "the move's source keeps its biomes");
            run.step(h.player, true);
            check(biome(h, mx, 25, z >> 2).equals(DESERT), "the move's undo");
            // A stack of two copies 8 south, Biomes ticked.
            run(run, h.player, new OpSpec.Stack(new Region.Cuboid(source), 0, 0, 8, 2, EntityFilter.NONE).withBiomes(true));
            check(biome(h, x >> 2, 25, (z >> 2) + 2).equals(CHERRY) && biome(h, x >> 2, 25, (z >> 2) + 5).equals(CHERRY)
                    && biome(h, x >> 2, 25, (z >> 2) + 6).equals(DESERT), "the stacked biomes");
            run.stop();
            clips.shutdown();
            s.close();
        }).thenSucceed();
    }

    // ---- The Biomes box on Move and Stack (slots 1800-1802) ----

    /** The mirror plane x + 24 of the Biomes-box tests' exact ops (sources x..x+7 and x+40..x+47). */
    private static Symmetry mirrorAt24(int x) {
        return new Symmetry(Symmetry.Mode.MIRROR_X, 2 * x + 48, 0);
    }

    /** The mirror plane x + 60.5 of their free mirrored move (sources x+1..x+14 and x+106..x+119). */
    private static Symmetry mirrorAt60(int x) {
        return new Symmetry(Symmetry.Mode.MIRROR_X, 2 * x + 121, 0);
    }

    /**
     * The scene of the Biomes-box tests, in {@code s.region} (x..x+127, z..z+63): desert everywhere; cherry grove in the
     * quarts at y 100..103 over x..x+15, z..z+11 and x..x+15, z+32..z+47 (the originals' sources), plains over the mirror
     * images x+40..x+47, z..z+7 and x+106..x+119, z+1..z+10; stone at y 100 over all of them, so every op moves blocks.
     */
    private static void biomeBoxScene(Scene s) {
        Harness h = s.h;
        int x = s.x, z = s.z;
        s.area(box(x, 96, z, x + 127, 111, z + 63), 99);
        paintAll(h, s.region, DESERT);
        WorldBiomes world = h.runtime.biomes(h.world);
        List<Box> cherry = List.of(box(x, 100, z, x + 15, 103, z + 11), box(x, 100, z + 32, x + 15, 103, z + 47));
        List<Box> plains = List.of(box(x + 40, 100, z, x + 47, 103, z + 7), box(x + 106, 100, z + 1, x + 119, 103, z + 10));
        for (Box b : cherry) setQuarts(world, b, CHERRY);
        for (Box b : plains) setQuarts(world, b, "minecraft:plains");
        world.flush();
        for (Box b : List.of(cherry.get(0), cherry.get(1), plains.get(0), plains.get(1))) {
            for (int bx = b.min().x(); bx <= b.max().x(); bx++) {
                for (int bz = b.min().z(); bz <= b.max().z(); bz++) {
                    h.world.setBlockAndUpdate(new net.minecraft.core.BlockPos(bx, 100, bz),
                            net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                }
            }
        }
    }

    private static void setQuarts(WorldBiomes world, Box b, String id) {
        for (int qx = b.min().x() >> 2; qx <= b.max().x() >> 2; qx++) {
            for (int qy = b.min().y() >> 2; qy <= b.max().y() >> 2; qy++) {
                for (int qz = b.min().z() >> 2; qz <= b.max().z() >> 2; qz++) world.set(qx, qy, qz, world.handle(id));
            }
        }
    }

    /**
     * With the Biomes box unticked (the default, and the flag every constructor without it sends) a move or stack moves
     * its blocks and leaves every biome where it was: exact, mirrored, turned 30° freely, turned and mirrored, and a
     * mirrored upside-down stack. Each is one undo step without biome cells that undoes and redoes its blocks exactly,
     * biomes untouched throughout.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_biome_box_unticked", timeoutTicks = LIMIT)
    public void untickedMovesAndStacksLeaveBiomesWhereTheyAre(GameTestHelper context) {
        try (Scene s = new Scene(context, 1800)) {
            Harness h = s.h;
            int x = s.x, z = s.z;
            biomeBoxScene(s);
            Box blocks = box(x, 96, z, x + 127, 111, z + 63);
            EditTestSupport.WorldSnapshot initial = EditTestSupport.capture(h.world, blocks);
            Map<Long, String> before = biomes(h, s.region);
            Run run = s.run();
            run.load(h.player);
            Pattern air = new Pattern.Single(h.state("minecraft:air"));
            dev.sculptory.core.transform.Orientation turned =
                    dev.sculptory.core.transform.Orientation.free(Transform.IDENTITY, 3000, 0, 0);
            Region small = new Region.Cuboid(box(x, 100, z, x + 7, 103, z + 7));
            Map<String, OpSpec> ops = new java.util.LinkedHashMap<>();
            // 48 south, onto desert: a carried biome would show.
            ops.put("the move", new OpSpec.Move(small, new BlockPos(0, 0, 48), Transform.IDENTITY, air,
                    EntityFilter.NONE));
            ops.put("the mirrored move", new OpSpec.Move(small, new BlockPos(8, 0, 0),
                    dev.sculptory.core.transform.Orientation.IDENTITY, air, EntityFilter.NONE, mirrorAt24(x),
                    PasteOptions.Into.EVERYTHING, false));
            ops.put("the free move", new OpSpec.Move(new Region.Cuboid(box(x, 100, z + 32, x + 15, 103, z + 47)),
                    new BlockPos(64, 0, 0), turned, air, EntityFilter.NONE, Symmetry.NONE, PasteOptions.Into.EVERYTHING,
                    false));
            ops.put("the mirrored free move", new OpSpec.Move(new Region.Cuboid(box(x + 1, 100, z + 1, x + 14, 103,
                    z + 10)), new BlockPos(32, 0, 0), turned, air, EntityFilter.NONE, mirrorAt60(x),
                    PasteOptions.Into.EVERYTHING, false));
            ops.put("the mirrored upside-down stack", new OpSpec.Stack(small, 0, 0, 8, 2, EntityFilter.NONE,
                    mirrorAt24(x), PasteOptions.Into.EVERYTHING, true, false));
            for (Map.Entry<String, OpSpec> op : ops.entrySet()) {
                String what = op.getKey();
                run(run, h.player, op.getValue());
                EditTestSupport.WorldSnapshot after = EditTestSupport.capture(h.world, blocks);
                check(EditTestSupport.difference(initial, after) != null, what + " moved no block");
                checkBiomes(before, biomes(h, s.region), what + " (unticked)");
                String label = undoLabels(run, h.player).get(0);
                check(!label.contains("biome"), what + " recorded biomes: " + label);
                run.step(h.player, true);
                check(EditTestSupport.difference(initial, EditTestSupport.capture(h.world, blocks)) == null,
                        what + "'s undo: " + EditTestSupport.difference(initial, EditTestSupport.capture(h.world, blocks)));
                checkBiomes(before, biomes(h, s.region), what + "'s undo");
                run.step(h.player, false);
                check(EditTestSupport.difference(after, EditTestSupport.capture(h.world, blocks)) == null,
                        what + "'s redo: " + EditTestSupport.difference(after, EditTestSupport.capture(h.world, blocks)));
                checkBiomes(before, biomes(h, s.region), what + "'s redo");
                run.step(h.player, true);
            }
            run.stop();
        }
        context.succeed();
    }

    /**
     * With the Biomes box ticked a move and a stack carry biomes exactly as before the box (a mirrored upside-down stack
     * into every copy, each copy its own source's; a mirrored free move through the sampler), and each undoes and redoes
     * its biomes exactly.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_biome_box_ticked", timeoutTicks = LIMIT)
    public void tickedMovesAndStacksCarryBiomesAndUndoAndRedoExactly(GameTestHelper context) {
        try (Scene s = new Scene(context, 1801)) {
            Harness h = s.h;
            int x = s.x, z = s.z;
            biomeBoxScene(s);
            Map<Long, String> before = biomes(h, s.region);
            Run run = s.run();
            run.load(h.player);
            Pattern air = new Pattern.Single(h.state("minecraft:air"));
            Region small = new Region.Cuboid(box(x, 100, z, x + 7, 103, z + 7));

            // The move 48 south carries its cherry grove onto desert.
            Map<Long, String> moved = new HashMap<>(before);
            for (int qx = x >> 2; qx <= (x + 7) >> 2; qx++) {
                for (int qz = (z + 48) >> 2; qz <= (z + 55) >> 2; qz++) moved.put(quartKey(qx, 25, qz), CHERRY);
            }
            OpSpec move = new OpSpec.Move(small, new BlockPos(0, 0, 48), Transform.IDENTITY, air, EntityFilter.NONE)
                    .withBiomes(true);
            checkCarried(run, h, s, move, before, moved, "the move");

            // The mirrored upside-down stack: two copies 8 south of each source (z+8..z+23), cherry under the original's
            // and plains under its mirror image's; the copies' one quart layer stays at y 100..103.
            Map<Long, String> stacked = new HashMap<>(before);
            for (int qz = (z + 8) >> 2; qz <= (z + 23) >> 2; qz++) {
                for (int qx = x >> 2; qx <= (x + 7) >> 2; qx++) stacked.put(quartKey(qx, 25, qz), CHERRY);
                for (int qx = (x + 40) >> 2; qx <= (x + 47) >> 2; qx++) stacked.put(quartKey(qx, 25, qz), "minecraft:plains");
            }
            OpSpec stack = new OpSpec.Stack(small, 0, 0, 8, 2, EntityFilter.NONE, mirrorAt24(x),
                    PasteOptions.Into.EVERYTHING, true, true);
            checkCarried(run, h, s, stack, before, stacked, "the mirrored upside-down stack");

            // The mirrored free move, 30° and 32 east.
            dev.sculptory.core.transform.Orientation turned =
                    dev.sculptory.core.transform.Orientation.free(Transform.IDENTITY, 3000, 0, 0);
            Box source = box(x + 1, 100, z + 1, x + 14, 103, z + 10);
            BlockPos offset = new BlockPos(32, 0, 0);
            Map<Long, String> free = carriedByFreeMove(before, source, offset, turned, h.world.getMinBuildHeight(),
                    h.world.getMaxBuildHeight());
            carriedByFreeMoveCopy(before, free, source, offset, turned, mirrorAt60(x), mirrorAt60(x).images().get(1),
                    h.world.getMinBuildHeight(), h.world.getMaxBuildHeight());
            check(!free.equals(before), "the free expectation carries nothing");
            checkCarried(run, h, s, new OpSpec.Move(new Region.Cuboid(source), offset, turned, air, EntityFilter.NONE,
                    mirrorAt60(x), PasteOptions.Into.EVERYTHING, true), before, free, "the mirrored free move");
            run.stop();
        }
        context.succeed();
    }

    /** Runs {@code op}, expecting {@code expected} biomes after it, {@code before} after its undo and again after redo. */
    private static void checkCarried(Run run, Harness h, Scene s, OpSpec op, Map<Long, String> before,
                                     Map<Long, String> expected, String what) {
        check(!expected.equals(before), what + ": the expectation carries nothing");
        run(run, h.player, op);
        checkBiomes(expected, biomes(h, s.region), what + " (ticked)");
        String label = undoLabels(run, h.player).get(0);
        check(label.contains("biome cell"), what + " recorded no biomes: " + label);
        run.step(h.player, true);
        checkBiomes(before, biomes(h, s.region), what + "'s undo");
        run.step(h.player, false);
        checkBiomes(expected, biomes(h, s.region), what + "'s redo");
        run.step(h.player, true);
        checkBiomes(before, biomes(h, s.region), what + "'s second undo");
    }

    /**
     * A move or stack sending the Biomes flag from a player without the biome node is refused NO_PERMISSION, asked
     * before anything else, with nothing written and nothing in the history (as a paste with Biomes ticked). A ticked
     * move is held to the node while it runs (losing it cancels the move, which then undoes exactly); an unticked one
     * is not.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_biome_box_refused", timeoutTicks = LIMIT)
    public void theBiomesFlagNeedsTheBiomeNodeAndHoldsTheMoveToIt(GameTestHelper context) {
        try (Scene s = new Scene(context, 1802)) {
            Harness h = s.h;
            int x = s.x, z = s.z;
            biomeBoxScene(s);
            Box blocks = box(x, 96, z, x + 127, 111, z + 63);
            EditTestSupport.WorldSnapshot initial = EditTestSupport.capture(h.world, blocks);
            Map<Long, String> before = biomes(h, s.region);
            Run run = s.run(s.dir, 64, StorageIo.SYSTEM);
            Pattern air = new Pattern.Single(h.state("minecraft:air"));
            Region small = new Region.Cuboid(box(x, 100, z, x + 7, 103, z + 7));
            dev.sculptory.core.transform.Orientation turned =
                    dev.sculptory.core.transform.Orientation.free(Transform.IDENTITY, 3000, 0, 0);

            ServerPlayer builder = h.addPlayer(false);
            EditTestSupport.grant(builder, Perm.USE, Perm.REGION);
            run.load(builder);
            for (OpSpec op : List.of(
                    new OpSpec.Move(small, new BlockPos(8, 0, 0), Transform.IDENTITY, air, EntityFilter.NONE)
                            .withBiomes(true),
                    new OpSpec.Stack(small, 0, 0, 8, 2, EntityFilter.NONE).withBiomes(true),
                    new OpSpec.Move(new Region.Cuboid(box(x + 1, 100, z + 1, x + 14, 103, z + 10)), new BlockPos(32, 0, 0),
                            turned, air, EntityFilter.NONE, mirrorAt60(x), PasteOptions.Into.EVERYTHING, true))) {
                EditRejected refused = ClipboardGameTest.refusal(() -> run.service.run(builder, op, RunOptions.DEFAULT,
                        null));
                check(refused.reason() == RejectReason.NO_PERMISSION && Perm.BIOME.node().equals(refused.detail()),
                        op.getClass().getSimpleName() + " with biomes without the node: " + refused.reason() + " "
                                + refused.detail());
                check(run.service.jobs(builder.getUUID()).isEmpty(), "a refused op left a job");
            }
            check(undoLabels(run, builder).isEmpty(), "refused ops left history: " + undoLabels(run, builder));
            check(EditTestSupport.difference(initial, EditTestSupport.capture(h.world, blocks)) == null,
                    "a refused op wrote blocks");
            checkBiomes(before, biomes(h, s.region), "the refused ops");

            // A ticked move whose player loses the biome node part way: cancelled, and undone exactly.
            ServerPlayer painter = h.addPlayer(false);
            EditTestSupport.grant(painter, Perm.USE, Perm.REGION, Perm.BIOME);
            run.load(painter);
            // 32 × 8 × 48 cells at 64 a tick: the blocks alone take hundreds of ticks, so the revoke lands mid-job.
            Region big = new Region.Cuboid(box(x, 100, z, x + 31, 107, z + 47));
            OpSpec ticked = new OpSpec.Move(big, new BlockPos(64, 0, 0), Transform.IDENTITY, air, EntityFilter.NONE)
                    .withBiomes(true);
            RecordingListener revoked = start(run, painter, ticked);
            tickUntil(run.executor, () -> {
                if (revoked.result != null) return true;
                for (int bx = x + 64; bx <= x + 79; bx++) {
                    for (int bz = z; bz <= z + 47; bz++) {
                        if (h.world.getBlockState(new net.minecraft.core.BlockPos(bx, 100, bz))
                                .is(net.minecraft.world.level.block.Blocks.STONE)) {
                            return true;
                        }
                    }
                }
                return false;
            }, 2_000, "the ticked move's first block");
            check(revoked.result == null, "the ticked move finished at once: " + revoked.result);
            EditTestSupport.grant(painter, Perm.USE, Perm.REGION);
            check(run.service.revalidate(painter, true) == 1, "losing the biome node cancels a ticked move");
            awaitOutcome(run, revoked, JobOutcome.CANCELLED, "the ticked move without the node");
            run.step(painter, true);
            check(EditTestSupport.difference(initial, EditTestSupport.capture(h.world, blocks)) == null,
                    "the cancelled move's undo: " + EditTestSupport.difference(initial,
                            EditTestSupport.capture(h.world, blocks)));
            checkBiomes(before, biomes(h, s.region), "the cancelled move's undo");

            // The same move unticked is not held to the node: losing it changes nothing.
            EditTestSupport.grant(painter, Perm.USE, Perm.REGION, Perm.BIOME);
            RecordingListener unticked = start(run, painter, ((OpSpec.Move) ticked).withBiomes(false));
            EditTestSupport.grant(painter, Perm.USE, Perm.REGION);
            check(run.service.revalidate(painter, true) == 0, "an unticked move is held to the biome node");
            awaitOutcome(run, unticked, JobOutcome.COMPLETED, "the unticked move");
            checkBiomes(before, biomes(h, s.region), "the unticked move");
            run.step(painter, true);
            check(EditTestSupport.difference(initial, EditTestSupport.capture(h.world, blocks)) == null,
                    "the unticked move's undo");
            run.stop();
        }
        context.succeed();
    }
}
