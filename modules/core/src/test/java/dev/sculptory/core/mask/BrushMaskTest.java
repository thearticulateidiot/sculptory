package dev.sculptory.core.mask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.brush.BrushKernel;
import dev.sculptory.core.brush.BrushKernels;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.ColumnFilter;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.SculptMode;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.StrokeState;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.brush.WeatherSpec;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Masks on brushes: the global mask around a kernel ({@link MaskedKernel}) and a brush's own rule-list mask
 * ({@link SurfaceMask.Rules}), which keeps a legacy mask's result exactly.
 */
class BrushMaskTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final int STONE = STATES.state("minecraft:stone");
    private static final int DIRT = STATES.state("minecraft:dirt");
    private static final int GRASS = STATES.state("minecraft:grass_block");
    private static final int SAND = STATES.state("minecraft:sand");
    private static final int PLANKS = STATES.state("minecraft:oak_planks");

    /** Rolling terrain of stone, dirt, grass and sand around y 64. */
    private static FakeWorld terrain(long seed) {
        Random random = new Random(seed);
        int[] tops = {STONE, DIRT, GRASS, SAND};
        FakeWorld world = new FakeWorld(STATES);
        for (int x = -24; x <= 24; x++) {
            for (int z = -24; z <= 24; z++) {
                int h = 60 + (int) Math.round(4 * Math.sin(x / 5.0) + 3 * Math.cos(z / 4.0)) + random.nextInt(2);
                for (int y = 50; y < h; y++) world.set(x, y, z, STONE);
                world.set(x, h, z, tops[random.nextInt(tops.length)]);
            }
        }
        return world;
    }

    private static BrushSpec spec(BrushTool tool, SurfaceMask mask, SculptMode mode) {
        Pattern material = tool == BrushTool.PAINT ? new Pattern.Single(PLANKS) : null;
        return new BrushSpec(tool, 6, 1f, Falloff.CONSTANT, Shape.CIRCLE, material, mask, 1, 64, 1L, null, Symmetry.NONE,
                null, mode, null);
    }

    private record Cell(int x, int y, int z, int state) {}

    private static List<Cell> step(BrushKernel kernel, BrushSpec spec, FakeWorld world, boolean write) {
        List<Cell> cells = new ArrayList<>();
        kernel.applyStep(spec, List.of(new Dab(0, 0, 64 * 16, 0, 255)), new StrokeState(), world, (x, y, z, h) -> {
            cells.add(new Cell(x, y, z, h));
            if (write) world.set(x, y, z, h);
        });
        return cells;
    }

    @Test
    void theGlobalMaskKeepsTheCellsItAcceptsBeforeTheStep() {
        EditMask mask = new EditMask(List.of(MaskEntry.of(new MaskRule.NextTo(BlockSet.parse("minecraft:grass_block"))),
                new MaskEntry(new MaskRule.Is(BlockSet.parse("minecraft:sand")), true)), false);
        BoundMask bound = mask.bind(STATES);
        for (BrushTool tool : List.of(BrushTool.PAINT, BrushTool.RAISE, BrushTool.LOWER, BrushTool.SMOOTH)) {
            for (SculptMode mode : List.of(SculptMode.TERRAIN, SculptMode.SURFACE)) {
                if (mode == SculptMode.SURFACE && (!SculptMode.surfaceTool(tool) || tool == BrushTool.FLATTEN)) continue;
                BrushSpec spec = spec(tool, SurfaceMask.ANY, mode);
                FakeWorld reference = terrain(3);
                List<Cell> unmasked = step(BrushKernels.forTool(tool), spec, reference, false);
                List<Cell> expected = new ArrayList<>();
                for (Cell cell : unmasked) {
                    if (bound.test(cell.x, cell.y, cell.z, reference.get(cell.x, cell.y, cell.z), reference)) {
                        expected.add(cell);
                    }
                }
                assertTrue(expected.size() < unmasked.size() || unmasked.isEmpty(), tool + " " + mode + ": the mask cuts");
                // The sink writes at once: the mask must still judge every cell against the world before the step.
                FakeWorld live = terrain(3);
                List<Cell> masked = step(MaskedKernel.wrap(BrushKernels.forTool(tool), bound), spec, live, true);
                assertEquals(expected, masked, tool + " " + mode);
            }
        }
        assertSame(BrushKernels.forTool(BrushTool.RAISE), MaskedKernel.wrap(BrushKernels.forTool(BrushTool.RAISE),
                EditMask.NONE.bind(STATES)));
    }

    @Test
    void aLegacyBrushMaskAndItsRulesGiveTheSameResult() {
        SurfaceMask legacy = new SurfaceMask.Not(new SurfaceMask.And(List.of(
                new SurfaceMask.SurfaceBlocks(new CellMask.Blocks(List.of(new NamespacedId("minecraft:stone"),
                        new NamespacedId("minecraft:grass_block")))),
                new SurfaceMask.Elevation(58, 64),
                new SurfaceMask.Slope(0, 2))));
        SurfaceMask rules = new SurfaceMask.Rules(new EditMask(List.of(
                MaskEntry.of(new MaskRule.Is(BlockSet.parse("minecraft:stone;minecraft:grass_block"))),
                MaskEntry.of(new MaskRule.Height(58, 64)),
                MaskEntry.of(new MaskRule.Slope(0, 2))), true));
        FakeWorld world = terrain(5);
        ColumnFilter a = ColumnFilter.compile(legacy, STATES, world), b = ColumnFilter.compile(rules, STATES, world);
        for (int state = 0; state < STATES.size(); state++) {
            for (int y = 50; y <= 70; y++) {
                for (int slope = 0; slope <= 5; slope++) {
                    assertEquals(a.test(1, y, 2, state, slope), b.test(1, y, 2, state, slope),
                            STATES.format(state) + " y " + y + " slope " + slope);
                }
            }
        }
        for (BrushTool tool : List.of(BrushTool.PAINT, BrushTool.RAISE, BrushTool.SMOOTH, BrushTool.FLATTEN)) {
            for (SculptMode mode : List.of(SculptMode.TERRAIN, SculptMode.SURFACE)) {
                if (mode == SculptMode.SURFACE && (!SculptMode.surfaceTool(tool) || tool == BrushTool.FLATTEN)) continue;
                List<Cell> withLegacy = step(BrushKernels.forTool(tool), spec(tool, legacy, mode), terrain(9), false);
                List<Cell> withRules = step(BrushKernels.forTool(tool), spec(tool, rules, mode), terrain(9), false);
                assertEquals(withLegacy, withRules, tool + " " + mode);
            }
        }
    }

    @Test
    void aBrushRuleMaskReadsNeighboursOfTheSurfaceCell() {
        FakeWorld world = new FakeWorld(STATES);
        world.fill(Box.of(new BlockPos(-3, 60, -3), new BlockPos(3, 63, 3)), STONE);
        world.set(0, 64, 0, SAND);
        SurfaceMask underSand = new SurfaceMask.Rules(new EditMask(List.of(
                MaskEntry.of(new MaskRule.Under(BlockSet.parse("minecraft:sand")))), false));
        ColumnFilter filter = ColumnFilter.compile(underSand, STATES, world);
        assertTrue(filter.test(0, 63, 0, STONE, 0));
        assertFalse(filter.test(1, 63, 0, STONE, 0));
        assertThrows(IllegalArgumentException.class, () -> ColumnFilter.compile(underSand, STATES),
                "without a world a rule that reads neighbours is refused");
        SurfaceMask cellOnly = new SurfaceMask.Rules(new EditMask(List.of(MaskEntry.of(new MaskRule.Solid())), false));
        assertTrue(ColumnFilter.compile(cellOnly, STATES).test(0, 0, 0, STONE, 0));
        assertTrue(ColumnFilter.usesSlope(new SurfaceMask.Rules(new EditMask(List.of(
                MaskEntry.of(new MaskRule.Slope(0, 3))), false))));
        assertFalse(ColumnFilter.usesSlope(cellOnly));
    }

    /** Weather's Melt under the global mask: a move is kept or dropped whole, so no block is lost or made. */
    @Test
    void aMaskedMeltMovesAWholeBlockOrNone() {
        int air = STATES.air();
        List<EditMask> masks = List.of(
                // Landings below the cliff's top fail, their sources pass: a per-cell mask would split these moves.
                new EditMask(List.of(MaskEntry.of(new MaskRule.Height(62, 80))), false),
                new EditMask(List.of(MaskEntry.of(new MaskRule.Chance(50, 11L))), false),
                // Every landing is air: nothing may move.
                new EditMask(List.of(MaskEntry.of(new MaskRule.NotAir())), false));
        int moved = 0;
        for (EditMask mask : masks) {
            BoundMask bound = mask.bind(STATES);
            boolean noLanding = mask.entries().get(0).rule() instanceof MaskRule.NotAir;
            for (boolean surface : new boolean[] {true, false}) {
                FakeWorld world = new FakeWorld(STATES);
                // A cliff four blocks high at x <= 0 over a floor at y 60, with an overhang off its top.
                for (int x = -16; x <= 16; x++) {
                    for (int z = -16; z <= 16; z++) {
                        int top = x <= 0 ? 64 : 60;
                        for (int y = 50; y <= top; y++) world.set(x, y, z, x <= 0 && y > 60 ? SAND : STONE);
                        if (x >= 1 && x <= 3) world.set(x, 64, z, DIRT);
                    }
                }
                Map<Integer, Integer> before = counts(world, air);
                BrushSpec spec = new BrushSpec(BrushTool.WEATHER, 6, 1f, Falloff.CONSTANT, Shape.CIRCLE, null,
                        SurfaceMask.ANY, 0, 0, 7L, null, Symmetry.NONE, null, SculptMode.TERRAIN, null,
                        new WeatherSpec(WeatherSpec.Mode.MELT));
                if (surface) spec = spec.withSurface(null);
                BrushKernel kernel = MaskedKernel.wrap(BrushKernels.forTool(BrushTool.WEATHER), bound);
                StrokeState stroke = new StrokeState();
                for (int i = 0; i < 12; i++) {
                    int[] writes = {0};
                    kernel.apply(spec, new Dab(i, 8, 62 * 16 + 8, 8, Dab.FULL_PRESSURE), stroke, world, (x, y, z, h) -> {
                        writes[0]++;
                        world.set(x, y, z, h);
                    });
                    // Melt moves only: each step writes removals and landings in pairs.
                    assertEquals(0, writes[0] % 2, mask + " surface " + surface + " dab " + i);
                    if (noLanding) assertEquals(0, writes[0], "no landing passes Not air");
                    moved += writes[0] / 2;
                }
                assertEquals(before, counts(world, air), mask + " surface " + surface);
            }
        }
        assertTrue(moved > 0, "some moves pass the masks");
    }

    private static Map<Integer, Integer> counts(FakeWorld world, int air) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int x = -16; x <= 16; x++) {
            for (int z = -16; z <= 16; z++) {
                for (int y = 50; y <= 80; y++) counts.merge(world.get(x, y, z), 1, Integer::sum);
            }
        }
        counts.remove(air);
        return counts;
    }
}
