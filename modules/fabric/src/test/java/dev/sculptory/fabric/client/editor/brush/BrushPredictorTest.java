package dev.sculptory.fabric.client.editor.brush;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.fabric.client.session.StrokeParams;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Prediction must write exactly what the server's replay of the same dabs writes:
 * same cells, same order, same resulting world, however the dabs are split into batches.
 */
class BrushPredictorTest {
    private static final int[] BATCH_SIZES = {1, 3, 16, 2, 5};

    private final FakeStateSpace states = new FakeStateSpace();
    private final int dirt = states.state("minecraft:dirt");
    private final int stone = states.state("minecraft:stone");
    private final int sand = states.state("minecraft:sand");

    private BrushSpec spec(BrushTool tool, int radius, float strength, Falloff falloff, Shape shape, Pattern material,
            SurfaceMask mask, int depth, int flattenY) {
        return new BrushSpec(tool, radius, strength, falloff, shape, material, mask, depth, flattenY, 7L);
    }

    private List<BrushSpec> everyBrush() {
        SurfaceMask masked = new SurfaceMask.And(List.of(
                new SurfaceMask.SurfaceBlocks(new CellMask.Blocks(List.of(new NamespacedId("minecraft:grass_block")))),
                new SurfaceMask.Elevation(55, 64),
                new SurfaceMask.Slope(0, 2)));
        return List.of(
                spec(BrushTool.RAISE, 5, 0.7f, Falloff.SMOOTH, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0),
                spec(BrushTool.RAISE, 6, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, masked, 0, 0),
                spec(BrushTool.LOWER, 4, 1f, Falloff.LINEAR, Shape.SQUARE, null, SurfaceMask.ANY, 0, 0),
                spec(BrushTool.SMOOTH, 6, 0.8f, Falloff.SPHERE, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0),
                spec(BrushTool.FLATTEN, 5, 0.6f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 62),
                spec(BrushTool.PAINT, 4, 1f, Falloff.CONSTANT, Shape.CIRCLE, new Pattern.Single(dirt), SurfaceMask.ANY, 2, 0),
                spec(BrushTool.PALETTE, 5, 0.8f, Falloff.LINEAR, Shape.SQUARE,
                        new Pattern.Weighted(new int[] {dirt, stone, sand}, new int[] {3, 2, 1}, 99L), SurfaceMask.ANY, 1, 0));
    }

    private static List<List<Dab>> batches(List<Dab> dabs) {
        List<List<Dab>> batches = new ArrayList<>();
        int from = 0;
        for (int i = 0; from < dabs.size(); i++) {
            int to = Math.min(dabs.size(), from + BATCH_SIZES[i % BATCH_SIZES.length]);
            batches.add(dabs.subList(from, to));
            from = to;
        }
        return batches;
    }

    @Test
    void predictionWritesExactlyWhatTheServerReplayWrites() {
        List<Dab> dabs = BrushTerrain.path(24);
        for (BrushSpec spec : everyBrush()) {
            FakeWorld server = BrushTerrain.world(states);
            List<BrushTerrain.Cell> expected = BrushTerrain.serverReplay(spec, dabs, server);
            assertFalse(expected.isEmpty(), spec.tool() + " changes the terrain");

            FakeWorld client = BrushTerrain.world(states);
            BrushTerrain.RecordingTarget target = new BrushTerrain.RecordingTarget(client);
            BrushPredictor predictor = new BrushPredictor(spec, client, target);
            for (List<Dab> batch : batches(dabs)) {
                predictor.predict(batch);
            }
            assertEquals(expected, target.cells, spec.tool() + ": the same cells in the same order");
            assertArrayEquals(BrushTerrain.snapshot(server), BrushTerrain.snapshot(client), spec.tool() + ": the same world");
            assertEquals(dabs.size(), predictor.dabsApplied());
            assertTrue(predictor.predicting());
        }
    }

    @Test
    void theStrokeStateCarriesAcrossBatchesLikeTheServers() {
        // Weak strength: columns only move once fractions from several dabs add up.
        BrushSpec spec = spec(BrushTool.RAISE, 4, 0.3f, Falloff.LINEAR, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0);
        List<Dab> still = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            still.add(Dab.of(i, 2.5, 63, 2.5, Dab.FULL_PRESSURE));
        }
        FakeWorld server = BrushTerrain.world(states);
        List<BrushTerrain.Cell> expected = BrushTerrain.serverReplay(spec, still, server);

        FakeWorld client = BrushTerrain.world(states);
        BrushTerrain.RecordingTarget target = new BrushTerrain.RecordingTarget(client);
        BrushPredictor predictor = new BrushPredictor(spec, client, target);
        for (Dab dab : still) {
            predictor.predict(List.of(dab)); // one dab per batch
        }
        assertEquals(expected, target.cells);
        assertArrayEquals(BrushTerrain.snapshot(server), BrushTerrain.snapshot(client));

        // A fresh state per batch (the bug this guards against) would lose the fractions.
        FakeWorld wrong = BrushTerrain.world(states);
        List<BrushTerrain.Cell> restarted = new ArrayList<>();
        for (Dab dab : still) {
            restarted.addAll(BrushTerrain.serverReplay(spec, List.of(dab), wrong));
        }
        assertFalse(expected.equals(restarted), "the fixture really depends on the stroke state");
    }

    @Test
    void eachBatchIsAppliedUnderItsOwnSequence() {
        BrushSpec spec = spec(BrushTool.RAISE, 5, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0);
        FakeWorld client = BrushTerrain.world(states);
        BrushTerrain.RecordingTarget target = new BrushTerrain.RecordingTarget(client);
        BrushPredictor predictor = new BrushPredictor(spec, client, target);
        List<Dab> dabs = BrushTerrain.path(6);

        assertEquals(101, predictor.predict(dabs.subList(0, 2)));
        int firstBatchCells = target.cells.size();
        assertEquals(102, predictor.predict(dabs.subList(2, 6)));
        assertEquals(List.of(101, 102), target.opened);
        assertEquals(2, target.closed, "every sequence is closed after its batch");
        assertFalse(target.isOpen());
        assertTrue(firstBatchCells > 0);
        for (int i = 0; i < target.cells.size(); i++) {
            assertEquals(i < firstBatchCells ? 101 : 102, target.cellSequences.get(i), "cell " + i);
        }
        assertEquals(0, predictor.predict(List.of()), "an empty batch opens nothing");
        assertEquals(2, target.opened.size());
    }

    @Test
    void withoutAWorldNothingIsPredicted() {
        BrushSpec spec = spec(BrushTool.LOWER, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0);
        FakeWorld client = BrushTerrain.world(states);
        BrushTerrain.RecordingTarget target = new BrushTerrain.RecordingTarget(client);
        target.unavailable = true;
        BrushPredictor predictor = new BrushPredictor(spec, client, target);
        assertEquals(0, predictor.predict(BrushTerrain.path(2)));
        assertTrue(target.cells.isEmpty());
        assertTrue(predictor.predicting(), "a missing world is not a failure");
    }

    @Test
    void aKernelFailureStopsPredictionButStillReturnsTheSequence() {
        // A material outside the state space: the kernel refuses it.
        BrushSpec spec = spec(BrushTool.PAINT, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE,
                new Pattern.Single(states.size() + 10), SurfaceMask.ANY, 1, 0);
        FakeWorld client = BrushTerrain.world(states);
        BrushTerrain.RecordingTarget target = new BrushTerrain.RecordingTarget(client);
        BrushPredictor predictor = new BrushPredictor(spec, client, target);
        assertEquals(101, predictor.predict(BrushTerrain.path(2)), "the sequence still goes to the server");
        assertFalse(predictor.predicting());
        assertEquals(1, target.closed);
        assertEquals(0, predictor.predict(BrushTerrain.path(2)), "the rest of the stroke is left to the server");
        assertEquals(1, target.opened.size());
    }

    @Test
    void theEstimateBoundsTheCellsADabCanWrite() {
        assertEquals(3_209, BrushPredictor.columns(32, Shape.CIRCLE), "the kernel's radius-32 footprint");
        assertEquals(1, BrushPredictor.columns(0, Shape.CIRCLE));
        assertEquals(5, BrushPredictor.columns(1, Shape.CIRCLE));
        assertEquals(121, BrushPredictor.columns(5, Shape.SQUARE));

        int cap = StrokeParams.DEFAULT.maxPredictedCells();
        assertEquals(3_209L * 3, BrushPredictor.estimatedCells(BrushTool.RAISE, 32, Shape.CIRCLE, 0));
        assertTrue(BrushPredictor.estimatedCells(BrushTool.LOWER, 32, Shape.SQUARE, 0) <= cap,
                "Raise and Lower are always predicted");
        assertEquals(1_129L * 29, BrushPredictor.estimatedCells(BrushTool.FLATTEN, 19, Shape.CIRCLE, 0));
        assertTrue(BrushPredictor.estimatedCells(BrushTool.FLATTEN, 19, Shape.CIRCLE, 0) <= cap);
        assertTrue(BrushPredictor.estimatedCells(BrushTool.SMOOTH, 20, Shape.CIRCLE, 0) > cap,
                "big Flatten and Smooth brushes run on the server only");
        assertEquals(3_209L * 10, BrushPredictor.estimatedCells(BrushTool.PAINT, 32, Shape.CIRCLE, 10));
        assertTrue(BrushPredictor.estimatedCells(BrushTool.PALETTE, 32, Shape.CIRCLE, 11) > cap);
        assertEquals(BrushPredictor.estimatedCells(BrushTool.PAINT, 3, Shape.CIRCLE, 1),
                BrushPredictor.estimatedCells(BrushTool.PAINT, 3, Shape.CIRCLE, 0), "depth 0 paints one cell");

        BrushSpec small = spec(BrushTool.SMOOTH, 8, 1f, Falloff.SMOOTH, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0);
        assertTrue(BrushPredictor.predictable(small, cap));
        assertFalse(BrushPredictor.predictable(small, 100));
    }
}
