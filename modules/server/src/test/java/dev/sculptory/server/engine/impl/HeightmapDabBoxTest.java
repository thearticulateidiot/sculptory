package dev.sculptory.server.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
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
import dev.sculptory.core.brush.SymmetricStep;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.protocol.v2.RejectReason;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Heightmap brush reads and writes only inside the boxes the server checks and locks for a step
 * ({@link EngineEditService#dabBox} of the dab and each located copy): raising and lowering, a stamp 1, 40 and 128 blocks
 * tall, at the smallest and largest radius, on rough ground with a lake, along a drag that climbs the ridge it lays,
 * mirrored across x with the dab on a block edge (its copy's stamp then lies a column off the copy's block) and with its
 * first dab on the mirror plane, mirrored across z, and turned four ways.
 */
class HeightmapDabBoxTest {
    private final FakeStateSpace states = new FakeStateSpace();

    @Test
    void everyReadAndWriteLiesInsideTheStepsBoxes() {
        int stone = states.state("minecraft:stone"), grass = states.state("minecraft:grass_block");
        int water = states.state("minecraft:water"), plant = states.state("minecraft:short_grass");
        for (boolean lower : new boolean[] {false, true}) {
            for (int radius : new int[] {1, 5, 32}) {
                for (int heightBlocks : new int[] {1, 40, 128}) {
                    for (Symmetry symmetry : List.of(Symmetry.NONE, new Symmetry(Symmetry.Mode.MIRROR_X, 0, 0),
                            new Symmetry(Symmetry.Mode.MIRROR_X, 16, 0), new Symmetry(Symmetry.Mode.MIRROR_Z, 0, -4),
                            new Symmetry(Symmetry.Mode.ROTATE_4, 16, -4))) {
                        FakeWorld world = new FakeWorld(states);
                        for (int x = -90; x <= 90; x++) {
                            for (int z = -50; z <= 50; z++) {
                                int top = 60 + Math.floorMod(x * 7 + z * 3, 5) + (x > 10 ? 6 : 0);
                                for (int y = 20; y <= top; y++) world.set(x, y, z, y == top ? grass : stone);
                                if (Math.abs(x - 20) < 4 && Math.abs(z) < 4) {
                                    for (int y = top + 1; y <= 70; y++) world.set(x, y, z, water);
                                } else if (Math.floorMod(x + z, 3) == 0) {
                                    world.set(x, top + 1, z, plant);
                                }
                            }
                        }
                        int side = 2 * radius + 1;
                        byte[] heights = new byte[side * side];
                        for (int i = 0; i < heights.length; i++) heights[i] = (byte) (i * 37 % 256);
                        BrushSpec spec = new BrushSpec(BrushTool.HEIGHTMAP, radius, 1f, Falloff.CONSTANT, Shape.SQUARE, null,
                                SurfaceMask.ANY, 0, 0, 3L, null, symmetry, null, SculptMode.TERRAIN, null, null,
                                new HeightmapSpec(side, heights, heightBlocks, lower), null);
                        String what = (lower ? "lower" : "raise") + " radius " + radius + " height " + heightBlocks
                                + " " + symmetry;
                        StrokeState stroke = new StrokeState();
                        for (int i = 0; i < 6; i++) {
                            // Along x, on a block edge, the dab's height following the ground it lays (as the cursor does).
                            int y = 66 + (lower ? -i * heightBlocks / 6 : i * heightBlocks / 6);
                            Dab dab = new Dab(i, (8 + 3 * i) * 16, y * 16, -2 * 16 + 11, 255);
                            SymmetricStep step = SymmetricStep.of(spec, dab, world);
                            List<Box> boxes = new ArrayList<>();
                            for (Dab placed : step.dabs()) {
                                boxes.add(EngineEditService.dabBox(spec, world.bottomY(), world.topYExclusive(), placed));
                            }
                            Bounded reader = new Bounded(world, boxes, what);
                            List<int[]> writes = new ArrayList<>();
                            BrushKernels.forTool(BrushTool.HEIGHTMAP).applyStep(spec, step.dabs(), stroke, reader,
                                    (x, yy, z, h) -> writes.add(new int[] {x, yy, z, h}));
                            for (int[] w : writes) {
                                assertTrue(reader.inside(w[0], w[1], w[2]), what + " wrote " + Arrays.toString(w)
                                        + " outside " + boxes);
                                world.set(w[0], w[1], w[2], w[3]);
                            }
                            assertFalse(reader.reads == 0, what + " read nothing");
                        }
                    }
                }
            }
        }
    }

    /**
     * The Heightmap brush's own limits: {@code maxHeightmapRadius} (not {@code maxBrushRadius}), {@code maxHeightmapHeight},
     * and its stamp's expected cells, times its copies, within {@code maxOpVolume}.
     */
    @Test
    void aStampOverTheServersCapsIsOverTheLimit() {
        BrushSpec tall = stamp(4, new byte[81], 64, Symmetry.NONE);
        assertTrue(EngineEditService.overBrushLimit(tall, 32, 128, 40, Long.MAX_VALUE).contains("stamp height 64 > 40"));
        assertNull(EngineEditService.overBrushLimit(tall, 32, 128, 64, Long.MAX_VALUE));
        assertNull(EngineEditService.overBrushLimit(tall, 32), "the default caps are the largest stamp");
        assertEquals("radius 4 > 3", EngineEditService.overBrushLimit(tall, 32, 3, 128, Long.MAX_VALUE));
        assertNull(EngineEditService.overBrushLimit(tall, 3, 128, 128, Long.MAX_VALUE), "maxBrushRadius is not its cap");

        // Radius 128, white all over, 128 tall: 257² columns of 128 + 3 cells.
        byte[] white = new byte[257 * 257];
        Arrays.fill(white, (byte) 255);
        BrushSpec widest = stamp(128, white, 128, Symmetry.NONE);
        assertEquals(257L * 257 * 131, EngineEditService.stampCells(widest));
        assertEquals(HeightmapSpec.MAX_STEP_CELLS, EngineEditService.stampCells(widest), "the cap holds the largest stamp");
        assertNull(EngineEditService.overBrushLimit(widest, 32, 128, 128, Long.MAX_VALUE));
        assertEquals("a stamp of 8652419 blocks > 2097152",
                EngineEditService.overBrushLimit(widest, 32, 128, 128, 2_097_152L));
        BrushSpec mirrored = stamp(128, white, 128, new Symmetry(Symmetry.Mode.MIRROR_X, 0, 0));
        assertEquals(2 * HeightmapSpec.MAX_STEP_CELLS, EngineEditService.stampCells(mirrored), "each copy counts");
        // A black picture moves nothing.
        assertEquals(0, EngineEditService.stampCells(stamp(128, new byte[257 * 257], 128, Symmetry.NONE)));
    }

    /**
     * Between turns of working a step out, an area no longer loaded refuses it UNLOADED before a locked box refuses it
     * AREA_BUSY; with both fine it goes on.
     */
    @Test
    void aPlanningTurnIsRefusedWhenItsAreaIsUnloadedOrLocked() {
        Box a = new Box(new BlockPos(0, 0, 0), new BlockPos(15, 15, 15));
        Box b = new Box(new BlockPos(16, 0, 0), new BlockPos(31, 15, 15));
        assertNull(EngineEditService.planningRefusal(List.of(a, b), box -> true, List.of(a, b), box -> false));
        assertEquals(RejectReason.UNLOADED,
                EngineEditService.planningRefusal(List.of(a, b), box -> box != b, List.of(a), box -> true));
        assertEquals(RejectReason.AREA_BUSY,
                EngineEditService.planningRefusal(List.of(a, b), box -> true, List.of(a, b), box -> box == b));
    }
    /**
     * A Heightmap dab weighs its stamp's columns in radius-32 stamps in the player's queue (32 units): one up to radius
     * 32, four at radius 64, sixteen at radius 128, so at most two radius-128 dabs are queued; copies multiply it.
     */
    @Test
    void aWideStampWeighsMoreInTheQueue() {
        assertEquals(1, EngineEditService.heightmapUnits(stamp(32, new byte[65 * 65], 8, Symmetry.NONE), 1));
        assertEquals(1, EngineEditService.heightmapUnits(stamp(3, new byte[49], 8, Symmetry.NONE), 1));
        assertEquals(2, EngineEditService.heightmapUnits(stamp(33, new byte[67 * 67], 8, Symmetry.NONE), 1));
        assertEquals(4, EngineEditService.heightmapUnits(stamp(64, new byte[129 * 129], 8, Symmetry.NONE), 1));
        assertEquals(16, EngineEditService.heightmapUnits(stamp(128, new byte[257 * 257], 8, Symmetry.NONE), 1));
        assertEquals(64, EngineEditService.heightmapUnits(stamp(128, new byte[257 * 257], 8, Symmetry.NONE), 4));
        assertEquals(EngineEditService.MAX_QUEUED_DABS, 2 * 16, "two radius-128 dabs fill a player's queue");
    }

    private static BrushSpec stamp(int radius, byte[] heights, int tall, Symmetry symmetry) {
        return new BrushSpec(BrushTool.HEIGHTMAP, radius, 1f, Falloff.CONSTANT, Shape.SQUARE, null, SurfaceMask.ANY, 0, 0,
                3L, null, symmetry, null, SculptMode.TERRAIN, null, null,
                new HeightmapSpec(2 * radius + 1, heights, tall, false), null);
    }

    /** A reader that fails any read outside its boxes. */
    private static final class Bounded implements WorldReader {
        private final WorldReader world;
        private final List<Box> boxes;
        private final String what;
        int reads;

        Bounded(WorldReader world, List<Box> boxes, String what) {
            this.world = world;
            this.boxes = boxes;
            this.what = what;
        }

        boolean inside(int x, int y, int z) {
            for (Box box : boxes) {
                if (box.contains(x, y, z)) return true;
            }
            return false;
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
            assertTrue(inside(x, y, z), what + " read " + x + "," + y + "," + z + " outside " + boxes);
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
