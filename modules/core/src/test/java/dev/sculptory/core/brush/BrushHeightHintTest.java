package dev.sculptory.core.brush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.SplitMix64;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.world.WorldReader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link WorldReader#heightHint} only saves reads: kernels must write exactly the same cells with and without it.
 */
class BrushHeightHintTest {
    private final BrushFixture f = new BrushFixture();
    private final int log = f.states.state("minecraft:oak_log");
    private final int stairs = f.states.state("minecraft:oak_stairs");

    /**
     * Counts {@link #get} calls; optionally answers {@link #heightHint} from the exclusive top of non-air cells,
     * shifted by {@code hintError} (negative: a wrong, too low hint).
     */
    private static final class CountingReader implements WorldReader {
        final FakeWorld world;
        final boolean hints;
        final int hintError;
        long reads;
        long hintCalls;

        CountingReader(FakeWorld world, boolean hints) {
            this(world, hints, 0);
        }

        CountingReader(FakeWorld world, boolean hints, int hintError) {
            this.world = world;
            this.hints = hints;
            this.hintError = hintError;
        }

        @Override
        public StateSpace states() {
            return world.states();
        }

        @Override
        public int bottomY() {
            return world.bottomY();
        }

        @Override
        public int topYExclusive() {
            return world.topYExclusive();
        }

        @Override
        public boolean isLoaded(int cx, int cz) {
            return world.isLoaded(cx, cz);
        }

        @Override
        public int get(int x, int y, int z) {
            reads++;
            return world.get(x, y, z);
        }

        @Override
        public BlockEntityData tile(int x, int y, int z) {
            return world.tile(x, y, z);
        }

        @Override
        public void copySection(int sx, int sy, int sz, SectionBuffer into) {
            world.copySection(sx, sy, sz, into);
        }

        @Override
        public int heightHint(int x, int z) {
            if (!hints) return topYExclusive();
            hintCalls++;
            // Like the WORLD_SURFACE heightmap: one above the highest non-air cell (the backing world, uncounted).
            for (int y = world.topYExclusive() - 1; y >= world.bottomY(); y--) {
                if (world.get(x, y, z) != world.states().air()) return Math.max(world.bottomY(), y + 1 + hintError);
            }
            return world.bottomY();
        }
    }

    /**
     * Rolling ground with plants, a pond, tree trunks, an overhang, a stair structure and a tall pillar that
     * reaches above every scan window.
     */
    private FakeWorld variedTerrain() {
        FakeWorld world = f.terrain((x, z) -> 56 + (int) Long.remainderUnsigned(SplitMix64.hash(7L, x >> 1, 0, z >> 1), 6)
                + (Math.abs(x) + Math.abs(z)) % 2);
        for (int x = -BrushFixture.EXTENT; x <= BrushFixture.EXTENT; x++) {
            for (int z = -BrushFixture.EXTENT; z <= BrushFixture.EXTENT; z++) {
                int top = f.surface(world, x, z);
                long h = SplitMix64.hash(11L, x, 0, z);
                if (Long.remainderUnsigned(h, 5) == 0) world.set(x, top + 1, z, f.shortGrass);
                if (Long.remainderUnsigned(h, 7) == 0) world.set(x, top + 2, z, f.shortGrass);
            }
        }
        // A pond: water over lowered ground.
        for (int x = 3; x <= 8; x++) {
            for (int z = -6; z <= -2; z++) {
                f.column(world, x, z, 54);
                for (int y = 55; y <= 57; y++) world.set(x, y, z, f.water);
            }
        }
        // Tree trunks (terrain-solid logs) and a floating overhang.
        for (int y = 60; y <= 66; y++) {
            world.set(-5, y, 5, log);
            world.set(9, y, 9, log);
        }
        for (int x = -12; x <= -8; x++) {
            for (int z = -3; z <= 1; z++) world.set(x, 68, z, f.stone);
        }
        world.set(2, 64, 2, stairs);
        // A pillar far above every scan window (its hint is above yTop).
        for (int y = 60; y <= 140; y++) world.set(-1, y, -1, f.stone);
        return world;
    }

    private List<BrushFixture.Write> stroke(BrushSpec spec, FakeWorld world, boolean hints, long[] reads) {
        return stroke(spec, world, new CountingReader(world, hints), reads);
    }

    private List<BrushFixture.Write> stroke(BrushSpec spec, FakeWorld world, CountingReader reader, long[] reads) {
        StrokeState stroke = new StrokeState();
        List<BrushFixture.Write> writes = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            // A path across the features, in 1/16 blocks, with varying height and pressure.
            Dab d = new Dab(i, (-20 + i * 3) * 16 + 5, 60 * 16 + (i % 4) * 9, (10 - i * 2) * 16 + 3, 150 + (i * 29) % 106);
            BrushKernels.forTool(spec.tool()).apply(spec, d, stroke, reader, (x, y, z, h) -> {
                writes.add(new BrushFixture.Write(x, y, z, h));
                world.set(x, y, z, h);
            });
        }
        reads[0] = reader.reads;
        return writes;
    }

    @Test
    void hintsDoNotChangeOutput() {
        Pattern palette = new Pattern.Weighted(new int[] {f.stone, f.dirt, f.sand}, new int[] {3, 2, 1}, 4L);
        for (BrushTool tool : BrushTool.TERRAIN) {
            int written = 0;
            for (int radius : new int[] {2, 7, 13}) {
                BrushSpec spec = new BrushSpec(tool, radius, 0.8f, Falloff.SMOOTH, radius == 7 ? Shape.SQUARE : Shape.CIRCLE,
                        palette, SurfaceMask.ANY, 2, 58, 3L);
                FakeWorld plain = variedTerrain();
                FakeWorld hinted = variedTerrain();
                long[] plainReads = new long[1];
                long[] hintedReads = new long[1];
                List<BrushFixture.Write> expected = stroke(spec, plain, false, plainReads);
                List<BrushFixture.Write> actual = stroke(spec, hinted, true, hintedReads);
                String what = tool + " radius " + radius;
                written += expected.size();
                assertEquals(expected, actual, what);
                assertEquals(plain.blocks().sortedKeys().length, hinted.blocks().sortedKeys().length, what);
                for (long key : plain.blocks().sortedKeys()) {
                    SectionBuffer a = plain.blocks().section(key);
                    SectionBuffer b = hinted.blocks().section(key);
                    for (int c = 0; c < SectionBuffer.SIZE; c++) assertEquals(a.get(c), b.get(c), what + " cell " + c);
                }
                assertTrue(hintedReads[0] < plainReads[0], what + ": " + hintedReads[0] + " >= " + plainReads[0]);
            }
            assertTrue(written > 0, tool + " wrote nothing");
        }
    }

    /**
     * A wrong (too low) hint whose cell is not air, like a stale heightmap, is detected and the whole window is
     * scanned: the output equals the no-hint output. The terrain has no air gaps under blocks (a too-low hint
     * landing in such a gap cannot be detected; the heightmap never gives one).
     */
    @Test
    void tooLowHintsAreDetected() {
        Pattern palette = new Pattern.Weighted(new int[] {f.stone, f.dirt}, new int[] {1, 1}, 2L);
        for (int error : new int[] {-1, -3}) {
            for (BrushTool tool : BrushTool.TERRAIN) {
                BrushSpec spec = new BrushSpec(tool, 9, 0.9f, Falloff.LINEAR, Shape.CIRCLE, palette, SurfaceMask.ANY, 2, 57, 6L);
                FakeWorld plain = gaplessTerrain();
                FakeWorld wrong = gaplessTerrain();
                long[] reads = new long[1];
                List<BrushFixture.Write> expected = stroke(spec, plain, false, reads);
                List<BrushFixture.Write> actual = stroke(spec, wrong, new CountingReader(wrong, true, error), reads);
                assertEquals(expected, actual, tool + " with hints " + error + " too low");
                assertTrue(!expected.isEmpty() || tool == BrushTool.SMOOTH, tool + " wrote nothing");
            }
        }
    }

    /** Rolling ground with plants, a pond and tree trunks: nothing floats above air. */
    private FakeWorld gaplessTerrain() {
        FakeWorld world = f.terrain((x, z) -> 56 + (int) Long.remainderUnsigned(SplitMix64.hash(5L, x >> 2, 0, z >> 2), 5));
        for (int x = -BrushFixture.EXTENT; x <= BrushFixture.EXTENT; x++) {
            for (int z = -BrushFixture.EXTENT; z <= BrushFixture.EXTENT; z++) {
                if (Long.remainderUnsigned(SplitMix64.hash(9L, x, 0, z), 4) == 0) {
                    world.set(x, f.surface(world, x, z) + 1, z, f.shortGrass);
                }
            }
        }
        for (int x = 3; x <= 8; x++) {
            for (int z = -6; z <= -2; z++) {
                f.column(world, x, z, 54);
                for (int y = 55; y <= 57; y++) world.set(x, y, z, f.water);
            }
        }
        for (int y = 57; y <= 66; y++) world.set(-5, y, 5, log);
        return world;
    }

    /** Radius 32 on flat ground: the reads saved per dab. */
    @Test
    void radius32FlatDabReadsFewerCells() {
        BrushSpec spec = BrushFixture.spec(BrushTool.RAISE, 32, 1f, Falloff.CONSTANT, Shape.CIRCLE);
        long[] plainReads = new long[1];
        long[] hintedReads = new long[1];
        List<BrushFixture.Write> expected = single(spec, f.flat(60), false, plainReads);
        List<BrushFixture.Write> actual = single(spec, f.flat(60), true, hintedReads);
        assertEquals(expected, actual);
        System.out.printf("Radius-32 RAISE dab on flat ground: %,d reads without hints, %,d with (%,d cells written)%n",
                plainReads[0], hintedReads[0], expected.size());
        assertTrue(plainReads[0] > 150_000, "expected a full window scan, got " + plainReads[0]);
        assertTrue(hintedReads[0] * 10 < plainReads[0], "hinted reads " + hintedReads[0]);
    }

    private List<BrushFixture.Write> single(BrushSpec spec, FakeWorld world, boolean hints, long[] reads) {
        CountingReader reader = new CountingReader(world, hints);
        List<BrushFixture.Write> writes = new ArrayList<>();
        BrushKernels.forTool(spec.tool()).apply(spec, BrushFixture.at(0, 0, 60, 0), new StrokeState(), reader,
                (x, y, z, h) -> {
                    writes.add(new BrushFixture.Write(x, y, z, h));
                    world.set(x, y, z, h);
                });
        reads[0] = reader.reads;
        return writes;
    }
}
