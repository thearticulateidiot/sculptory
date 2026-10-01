package dev.sculptory.fabric.client.editor.brush;

import dev.sculptory.core.brush.BrushKernel;
import dev.sculptory.core.brush.BrushKernels;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.StrokeState;
import dev.sculptory.core.brush.SymmetricStep;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import java.util.ArrayList;
import java.util.List;

/** Bumpy terrain with plants and a pond, the server's replay of a stroke, and a recording prediction target. */
public final class BrushTerrain {
    public static final int EXTENT = 32;
    public static final int FLOOR = 40;
    public static final int WATER_LEVEL = 60;

    public record Cell(int x, int y, int z, int state) {}

    private BrushTerrain() {}

    /** The surface height of column (x, z): steps and ridges between y 56 and 66. */
    public static int height(int x, int z) {
        int bumps = Math.floorMod(x * 7 + z * 13, 5);
        int ridge = x > 6 ? 4 : 0;
        int basin = x < -12 ? -5 : 0;
        return 61 + bumps + ridge + basin;
    }

    /** Stone below a grass top, short grass on some columns, and water up to {@link #WATER_LEVEL} in the basin. */
    public static FakeWorld world(FakeStateSpace states) {
        FakeWorld world = new FakeWorld(states);
        int stone = states.state("minecraft:stone");
        int grass = states.state("minecraft:grass_block");
        int plant = states.state("minecraft:short_grass");
        int water = states.state("minecraft:water");
        for (int x = -EXTENT; x <= EXTENT; x++) {
            for (int z = -EXTENT; z <= EXTENT; z++) {
                int top = height(x, z);
                for (int y = FLOOR; y <= top; y++) {
                    world.set(x, y, z, y < top ? stone : grass);
                }
                if (top < WATER_LEVEL) {
                    for (int y = top + 1; y <= WATER_LEVEL; y++) {
                        world.set(x, y, z, water);
                    }
                } else if (Math.floorMod(x + z, 4) == 0) {
                    world.set(x, top + 1, z, plant);
                }
            }
        }
        return world;
    }

    /** A wavy path of {@code count} dabs from the basin across the ridge. */
    public static List<Dab> path(int count) {
        List<Dab> dabs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            dabs.add(Dab.of(i, -18 + i * 1.25, 62 + (i % 3) * 0.5, -3 + (i % 5) * 0.75, Dab.FULL_PRESSURE));
        }
        return dabs;
    }

    /**
     * What the server does with a stroke: one state, every dab in order, each written through. As
     * {@code EngineEditService.applyDab}: the dab's copies are stood on the ground against the world before the dab
     * ({@link SymmetricStep#of}), then applied as one step.
     */
    public static List<Cell> serverReplay(BrushSpec spec, List<Dab> dabs, FakeWorld world) {
        BrushKernel kernel = BrushKernels.forTool(spec.tool());
        StrokeState state = new StrokeState();
        List<Cell> cells = new ArrayList<>();
        for (Dab dab : dabs) {
            SymmetricStep step = SymmetricStep.of(spec, dab, world);
            kernel.applyStep(spec, step.dabs(), state, world, (x, y, z, h) -> {
                cells.add(new Cell(x, y, z, h));
                world.set(x, y, z, h);
            });
        }
        return cells;
    }

    /** Every cell of the terrain's box, for comparing two worlds. */
    public static int[] snapshot(FakeWorld world) {
        int side = 2 * EXTENT + 1;
        int height = 100 - FLOOR + 1;
        int[] cells = new int[side * side * height];
        int i = 0;
        for (int x = -EXTENT; x <= EXTENT; x++) {
            for (int z = -EXTENT; z <= EXTENT; z++) {
                for (int y = FLOOR; y <= 100; y++) {
                    cells[i++] = world.get(x, y, z);
                }
            }
        }
        return cells;
    }

    /** A prediction target that writes into a fake world and records sequences and cells. */
    public static final class RecordingTarget implements BrushPredictor.Target {
        public final FakeWorld world;
        public final List<Cell> cells = new ArrayList<>();
        /** The sequence open for each recorded cell. */
        public final List<Integer> cellSequences = new ArrayList<>();
        public final List<Integer> opened = new ArrayList<>();
        public int closed;
        public boolean unavailable;
        private int sequence = 100;
        private boolean open;

        public RecordingTarget(FakeWorld world) {
            this.world = world;
        }

        public boolean isOpen() {
            return open;
        }

        @Override
        public BrushPredictor.Scope open() {
            if (unavailable) {
                return null;
            }
            if (open) {
                throw new AssertionError("prediction sequences never nest");
            }
            open = true;
            int seq = ++sequence;
            opened.add(seq);
            return new BrushPredictor.Scope() {
                @Override
                public int sequence() {
                    return seq;
                }

                @Override
                public void set(int x, int y, int z, int handle) {
                    if (!open) {
                        throw new AssertionError("a cell was written outside its sequence");
                    }
                    cells.add(new Cell(x, y, z, handle));
                    cellSequences.add(seq);
                    world.set(x, y, z, handle);
                }

                @Override
                public void close() {
                    open = false;
                    closed++;
                }
            };
        }
    }
}
