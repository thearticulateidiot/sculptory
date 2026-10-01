package dev.sculptory.core.edit;

import static dev.sculptory.core.edit.CopyTestSupport.box;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.testing.FakeExecutor;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Fill (and the other region ops) with a mix laid out in space: every cell is
 * the pattern's block at that cell, a symmetric copy mirrors the pattern and a Gradient's line, the undo is exact, and a
 * Steepness pattern is refused (only Palette Paint measures the ground's steepness).
 */
class MixPatternFillTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final int stone = states.state("minecraft:stone");
    private final int andesite = states.state("minecraft:andesite");
    private final int sand = states.state("minecraft:sand");
    private final int logX = states.state("minecraft:oak_log[axis=x]");
    private final Pattern.Weighted mix = new Pattern.Weighted(new int[] {stone, andesite, sand}, new int[] {3, 2, 1}, 77L);

    private EditProgram compile(OpSpec op) {
        return OpCompiler.compile(op, CopyTestSupport.context(states, Map.of()));
    }

    private void assertFilledWith(Pattern pattern, FakeWorld world, Box box) {
        for (int x = box.min().x(); x <= box.max().x(); x++) {
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) {
                    assertEquals(pattern.apply(states, x, y, z, 0), world.get(x, y, z), "at " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void aFillWritesThePatternsBlockAtEveryCellAndUndoesExactly() {
        Box b = box(-20, 60, -20, 20, 70, 20);
        for (MixLayout layout : new MixLayout[] {new MixLayout.Patches(5),
                new MixLayout.Gradient(new BlockPos(-20, 60, 0), new BlockPos(20, 70, 0), 4)}) {
            Pattern pattern = new Pattern.Arranged(mix, layout);
            FakeWorld world = new FakeWorld(states);
            world.fill(box(-20, 60, -20, 20, 64, 20), stone);
            CopyTestSupport.runAndUndo(compile(new OpSpec.Fill(b, pattern, CellMask.ANY)), world, b, layout.toString());
            FakeExecutor.run(compile(new OpSpec.Fill(b, pattern, CellMask.ANY)), world);
            assertFilledWith(pattern, world, b);
        }
    }

    /**
     * Mirror X about x = 0.5 (a block centre): the copy's cells hold the original's pattern mirrored, for Patches and for
     * a Gradient whose line runs east (its copy runs west), with the states turned as a paste turns them (an x log stays
     * an x log across a mirror in x).
     */
    @Test
    void aSymmetricCopyMirrorsThePatternAndTheLine() {
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 1, 0);
        Box original = box(4, 60, -6, 14, 64, 6);
        Pattern.Weighted logs = new Pattern.Weighted(new int[] {stone, logX, sand}, new int[] {3, 2, 1}, 5L);
        for (MixLayout layout : new MixLayout[] {new MixLayout.Patches(3),
                new MixLayout.Gradient(new BlockPos(4, 62, 0), new BlockPos(14, 62, 0), 2)}) {
            Pattern pattern = new Pattern.Arranged(logs, layout);
            FakeWorld world = new FakeWorld(states);
            FakeExecutor.run(compile(new OpSpec.Fill(new Region.Cuboid(original), pattern, CellMask.ANY, mirror)), world);
            int differs = 0;
            for (int x = 4; x <= 14; x++) {
                for (int y = 60; y <= 64; y++) {
                    for (int z = -6; z <= 6; z++) {
                        int here = world.get(x, y, z);
                        assertEquals(pattern.apply(states, x, y, z, 0), here, "the original at " + x + "," + y + "," + z);
                        // x' = x2 - 1 - x = -x.
                        assertEquals(here, world.get(-x, y, z), "the mirrored copy at " + -x + "," + y + "," + z);
                        if (here != pattern.apply(states, -x, y, z, 0)) differs++;
                    }
                }
            }
            assertTrue(differs > 0, "the copy is not simply the pattern at its own cells (" + layout + ")");
        }
        // Along a Gradient's line the copy's first block is at its own start, mirrored: x = -4 holds what x = 4 holds.
        FakeWorld world = new FakeWorld(states);
        Pattern gradient = new Pattern.Arranged(mix, new MixLayout.Gradient(new BlockPos(4, 62, 0),
                new BlockPos(14, 62, 0), 0));
        FakeExecutor.run(compile(new OpSpec.Fill(new Region.Cuboid(original), gradient, CellMask.ANY, mirror)), world);
        assertEquals(stone, world.get(-4, 62, 0));
        assertEquals(sand, world.get(-14, 62, 0));
    }

    /** A Steepness pattern needs the ground's steepness, which only Palette Paint measures: every op refuses it. */
    @Test
    void steepnessIsRefusedInEveryOp() {
        Pattern steep = new Pattern.Arranged(mix, new MixLayout.Steepness(10));
        Box b = box(0, 60, 0, 4, 62, 4);
        assertThrows(IllegalArgumentException.class, () -> compile(new OpSpec.Fill(b, steep, CellMask.ANY)));
        assertThrows(IllegalArgumentException.class,
                () -> compile(new OpSpec.Replace(new Region.Cuboid(b), CellMask.ANY, steep, Symmetry.NONE)));
        assertThrows(IllegalArgumentException.class,
                () -> compile(new OpSpec.Walls(new Region.Cuboid(b), 1, steep, Symmetry.NONE)));
        assertThrows(IllegalArgumentException.class,
                () -> compile(new OpSpec.Hollow(new Region.Cuboid(b), 1, steep, Symmetry.NONE)));
        // A laid-out mix naming a state outside the space is refused as a weighted one is.
        Pattern outside = new Pattern.Arranged(new Pattern.Weighted(new int[] {stone, states.size()}, new int[] {1, 1}, 1L),
                new MixLayout.Patches(4));
        assertThrows(IllegalArgumentException.class, () -> compile(new OpSpec.Fill(b, outside, CellMask.ANY)));
    }
}
