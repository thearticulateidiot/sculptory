package dev.sculptory.server.engine.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BrushKernels;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.SculptMode;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.StrokeState;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.brush.WeatherSpec;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.world.WorldReader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Weather brush reads and writes only inside the box the server checks and locks for a dab
 * ({@link EngineEditService#dabBox}: {@code radius + 2} across in both sculpt modes, as Smooth), for every mode, at the
 * smallest and largest radius, on rough ground with an overhang and a pit.
 */
class WeatherDabBoxTest {
    private final FakeStateSpace states = new FakeStateSpace();

    @Test
    void everyReadAndWriteLiesInsideTheDabBox() {
        FakeWorld world = new FakeWorld(states);
        int stone = states.state("minecraft:stone"), grass = states.state("minecraft:grass_block");
        for (int x = -60; x <= 60; x++) {
            for (int z = -60; z <= 60; z++) {
                int top = 60 + Math.floorMod(x * 7 + z * 3, 5) + (x > 10 ? 6 : 0);
                for (int y = 40; y <= top; y++) world.set(x, y, z, y == top ? grass : stone);
                if (Math.abs(x - 4) <= 3 && Math.abs(z) <= 5) world.set(x, 75, z, stone);
            }
        }
        for (WeatherSpec.Mode mode : WeatherSpec.Mode.values()) {
            for (boolean surface : new boolean[] {true, false}) {
                for (int radius : new int[] {1, 5, 32}) {
                    BrushSpec spec = new BrushSpec(BrushTool.WEATHER, radius, 1f, Falloff.CONSTANT, Shape.SQUARE, null,
                            SurfaceMask.ANY, 0, 0, 3L, null, Symmetry.NONE, null, SculptMode.TERRAIN, null,
                            new WeatherSpec(mode));
                    if (surface) spec = spec.withSurface(null);
                    StrokeState stroke = new StrokeState();
                    for (int i = 0; i < 4; i++) {
                        Dab dab = new Dab(i, 8 * 16 + 3, 66 * 16 + 5, -2 * 16 + 11, 255);
                        Box box = EngineEditService.dabBox(spec, world.bottomY(), world.topYExclusive(), dab);
                        String what = mode + (surface ? " surface" : " terrain") + " radius " + radius;
                        Bounded reader = new Bounded(world, box, what);
                        List<int[]> writes = new ArrayList<>();
                        BrushKernels.forTool(BrushTool.WEATHER).apply(spec, dab, stroke, reader,
                                (x, y, z, h) -> writes.add(new int[] {x, y, z, h}));
                        for (int[] w : writes) {
                            assertTrue(box.contains(w[0], w[1], w[2]), what + " wrote outside " + box);
                            world.set(w[0], w[1], w[2], w[3]);
                        }
                        assertFalse(reader.reads == 0, what + " read nothing");
                    }
                }
            }
        }
    }

    /** A reader that fails any read outside {@code box}. */
    private static final class Bounded implements WorldReader {
        private final WorldReader world;
        private final Box box;
        private final String what;
        int reads;

        Bounded(WorldReader world, Box box, String what) {
            this.world = world;
            this.box = box;
            this.what = what;
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
            assertTrue(box.contains(x, y, z), what + " read " + x + "," + y + "," + z + " outside " + box);
            reads++;
            return world.get(x, y, z);
        }

        @Override
        public BlockEntityData tile(int x, int y, int z) {
            return world.tile(x, y, z);
        }

        @Override
        public void copySection(int sx, int sy, int sz, SectionBuffer into) {
            throw new UnsupportedOperationException();
        }
    }
}
