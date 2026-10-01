package dev.sculptory.core.brush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The Surface mode ({@link SculptMode#SURFACE}, {@link SurfaceKernel}): floors behave as the Terrain mode inside the
 * ball, walls and ceilings are raised, lowered, smoothed and flattened along the way they face, nothing outside the
 * ball changes, masks, the clip box, materials, structures, plants and fluids, and the output is the same whatever
 * order a step lists its dabs in.
 */
class SurfaceKernelTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final int air = states.air();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");
    private final int grass = states.state("minecraft:grass_block");
    private final int water = states.state("minecraft:water");
    private final int shortGrass = states.state("minecraft:short_grass");
    private final int stairs = states.state("minecraft:oak_stairs");

    record Write(int x, int y, int z, int state) {}

    // ------------------------------------------------------------------ the normal

    @Test
    void theNormalOfAFlatFloorWallAndCeilingIsExactWhereverTheFaceIsHit() {
        FakeWorld floor = floor(60);
        assertEquals(List.of(0L, 0L), horizontal(SurfaceNormal.estimate(floor, 3, 61, -2, 5)));
        assertTrue(SurfaceNormal.estimate(floor, 3, 61, -2, 5)[1] > 0);
        assertTrue(SurfaceNormal.estimate(floor, 3, 60, -2, 5)[1] > 0, "a hit a sixteenth short, in the ground block");
        assertEquals(Facing.UP, SurfaceNormal.facing(SurfaceNormal.estimate(floor, 0, 61, 0, 8)));

        FakeWorld wall = eastWall(9);
        assertEquals(Facing.EAST, SurfaceNormal.facing(SurfaceNormal.estimate(wall, 10, 65, 0, 3)));
        assertEquals(Facing.EAST, SurfaceNormal.facing(SurfaceNormal.estimate(wall, 9, 65, 0, 3)));
        long[] n = SurfaceNormal.estimate(wall, 10, 65, 3, 5);
        assertEquals(0, n[1]);
        assertEquals(0, n[2]);

        FakeWorld ceiling = ceiling(70);
        assertEquals(Facing.DOWN, SurfaceNormal.facing(SurfaceNormal.estimate(ceiling, 0, 69, 0, 4)));
        assertEquals(Facing.DOWN, SurfaceNormal.facing(SurfaceNormal.estimate(ceiling, 0, 70, 0, 4)));
        assertEquals(Facing.UP, SurfaceNormal.facing(new long[] {0, 0, 0}), "no surface counts as up");
    }

    @Test
    void theNormalStaysUpOnBumpyGroundAndTheSlopeReadsSteepness() {
        // Ground with one-block steps every other column, and a single spike.
        FakeWorld world = terrain((x, z) -> 60 + Math.floorMod(x, 2) + (x == 2 && z == 1 ? 3 : 0));
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                int top = 60 + Math.floorMod(x, 2);
                assertEquals(Facing.UP, SurfaceNormal.facing(SurfaceNormal.estimate(world, x, top + 1, z, 5)), x + "," + z);
            }
        }
        assertEquals(0, SurfaceNormal.slope(SurfaceNormal.estimate(floor(60), 0, 60, 0, 2)));
        assertEquals(SurfaceNormal.MAX_SLOPE, SurfaceNormal.slope(SurfaceNormal.estimate(eastWall(9), 9, 65, 0, 2)));
        assertEquals(0, SurfaceNormal.slope(SurfaceNormal.estimate(ceiling(70), 0, 70, 0, 2)), "a ceiling is flat");
        // A 45° staircase.
        FakeWorld stairsWorld = terrain((x, z) -> 60 + x);
        assertEquals(1, SurfaceNormal.slope(SurfaceNormal.estimate(stairsWorld, 0, 60, 0, 2)));
    }

    // ------------------------------------------------------------------ floors: as the Terrain mode, inside the ball

    @Test
    void raiseAndLowerOnAFloorAreTheTerrainModes() {
        // The cylinder's footprint is the Terrain mode's, and on a floor its reach holds every column's surface.
        for (BrushTool tool : List.of(BrushTool.RAISE, BrushTool.LOWER)) {
            for (Falloff falloff : Falloff.values()) {
                BrushSpec terrainSpec = spec(tool, 4, 1f, falloff, Shape.CIRCLE);
                Dab dab = new Dab(0, 8, 61 * 16, 8, 255);
                Set<Write> terrain = new HashSet<>(apply(terrainSpec, dab, floor(60)));
                FakeWorld world = floor(60);
                List<Write> surface = apply(terrainSpec.withSurface(null), dab, world);
                assertFalse(surface.isEmpty(), tool + " " + falloff);
                assertEquals(terrain, new HashSet<>(surface), tool + " " + falloff);
            }
        }
    }

    /**
     * A held Raise on a wall grows a mound, as the Terrain mode grows one on a floor: the cylinder keeps every line of the
     * footprint as the surface, and the cursor on it, move away from the wall (a ball lost the outer lines one by one and
     * left a pillar). The same dabs on a floor in the Terrain mode grow the same profile, turned.
     */
    @Test
    void holdingRaiseOnAWallGrowsAMoundAtTheTerrainModesRate() {
        int radius = 5, dabs = 24;
        BrushSpec spec = spec(BrushTool.RAISE, radius, 1f, Falloff.SMOOTH, Shape.CIRCLE);
        // The wall: the cursor held still, each dab on the face where it now is.
        FakeWorld wall = eastWall(9);
        StrokeState wallStroke = new StrokeState();
        for (int i = 0; i < dabs; i++) {
            int face = 10;
            while (wall.get(face, 65, 0) != air) face++;
            apply(spec.withSurface(null), wallStroke, new Dab(i, face * 16, 65 * 16 + 8, 8, 255), wall);
        }
        // The floor in the Terrain mode: the same dabs, aimed down at the column's top as it rises.
        FakeWorld floor = floor(60);
        StrokeState floorStroke = new StrokeState();
        for (int i = 0; i < dabs; i++) {
            int top = 60;
            while (floor.get(0, top + 1, 0) != air) top++;
            apply(spec, floorStroke, new Dab(i, 8, (top + 1) * 16, 8, 255), floor);
        }
        int centreGrowth = 0;
        while (wall.get(10 + centreGrowth, 65, 0) != air) centreGrowth++;
        assertEquals(dabs, centreGrowth, "full strength at the centre: one block a dab");
        int outerLines = 0;
        for (int y = 65 - radius; y <= 65 + radius; y++) {
            for (int z = -radius; z <= radius; z++) {
                int wallGrowth = 0;
                while (wall.get(10 + wallGrowth, y, z) != air) wallGrowth++;
                int floorGrowth = 0;
                while (floor.get(y - 65, 60 + 1 + floorGrowth, z) != air) floorGrowth++;
                assertEquals(floorGrowth, wallGrowth, "growth at " + y + "," + z + " on the wall as on the floor");
                if (Math.abs(y - 65) + Math.abs(z) >= radius && wallGrowth > 0) outerLines++;
            }
        }
        assertTrue(outerLines >= 4, "the footprint's outer lines grew too (a mound, not a pillar): " + outerLines);
    }

    @Test
    void flattenOnAFloorLevelsAtThePressHeightAsTheTerrainModeDoes() {
        FakeWorld terrainWorld = hillAndPit();
        FakeWorld surfaceWorld = hillAndPit();
        BrushSpec terrainSpec = new BrushSpec(BrushTool.FLATTEN, 6, 1f, Falloff.CONSTANT, Shape.CIRCLE, null,
                SurfaceMask.ANY, 0, 60, 1L);
        SurfacePlane plane = SurfacePlane.through(Facing.UP, 8, 61 * 16, 8);
        assertEquals(new SurfacePlane(Facing.UP, 60), plane);
        BrushSpec surfaceSpec = terrainSpec.withSurface(plane);
        for (int i = 0; i < 3; i++) {
            Dab dab = new Dab(i, 8, 61 * 16, 8, 255);
            apply(terrainSpec, dab, terrainWorld);
            apply(surfaceSpec, dab, surfaceWorld);
        }
        // Columns well inside the ball end where the Terrain mode's do: level at 60.
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                assertEquals(60, top(terrainWorld, x, z), "terrain " + x + "," + z);
                assertEquals(60, top(surfaceWorld, x, z), "surface " + x + "," + z);
            }
        }
    }

    @Test
    void smoothLeavesAFlatFloorAloneAndRemovesASpikeAndADent() {
        FakeWorld world = floor(60);
        BrushSpec smooth = spec(BrushTool.SMOOTH, 4, 1f, Falloff.CONSTANT, Shape.CIRCLE).withSurface(null);
        assertTrue(apply(smooth, new Dab(0, 8, 61 * 16, 8, 255), world).isEmpty(), "a flat floor stays");
        for (int y = 61; y <= 63; y++) world.set(1, y, 0, stone);
        world.set(-1, 60, 1, air);
        apply(smooth, new Dab(1, 8, 61 * 16, 8, 255), world);
        for (int y = 61; y <= 63; y++) assertEquals(air, world.get(1, y, 0), "spike at " + y);
        assertEquals(grass, world.get(-1, 60, 1), "the dent fills with the ground around it");
        assertEquals(60, top(world, 1, 0));
    }

    // ------------------------------------------------------------------ walls

    @Test
    void raiseOnAWallPushesItOutwardAndLowerPullsItIn() {
        Dab dab = new Dab(0, 160, 65 * 16 + 8, 8, 255);
        FakeWorld world = eastWall(9);
        List<Write> raised = apply(spec(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE).withSurface(null), dab,
                world);
        assertFalse(raised.isEmpty());
        for (Write write : raised) {
            assertEquals(10, write.x, "raised one block east: " + write);
            assertEquals(dirt, write.state, "copies the wall's block");
            assertTrue(acrossX(dab, 3, write.y, write.z), "inside the footprint: " + write);
        }
        // Every wall cell in front of the footprint moved out: (10, y, z) with y, z within the radius across.
        int expected = 0;
        for (int y = 60; y <= 70; y++) {
            for (int z = -5; z <= 5; z++) if (acrossX(dab, 3, y, z)) expected++;
        }
        assertEquals(expected, raised.size());

        FakeWorld lowerWorld = eastWall(9);
        List<Write> lowered = apply(spec(BrushTool.LOWER, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE).withSurface(null), dab,
                lowerWorld);
        assertFalse(lowered.isEmpty());
        for (Write write : lowered) {
            assertEquals(9, write.x, "the wall's face removed: " + write);
            assertEquals(air, write.state);
        }
        // A wall facing west is pushed west.
        FakeWorld west = westWall(20);
        Dab westDab = new Dab(0, 20 * 16, 65 * 16 + 8, 8, 255);
        for (Write write : apply(spec(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE).withSurface(null), westDab,
                west)) {
            assertEquals(19, write.x, "raised one block west: " + write);
        }
    }

    @Test
    void holdingRaiseOnAWallAdvancesOneBlockPerFullDabAndNoFaster() {
        FakeWorld world = eastWall(9);
        BrushSpec spec = spec(BrushTool.RAISE, 4, 0.5f, Falloff.SMOOTH, Shape.CIRCLE).withSurface(null);
        StrokeState stroke = new StrokeState();
        int face = 10;
        for (int i = 0; i < 20; i++) {
            // The cursor held still: each dab lands on the wall's face where it is now (the ray hits the bump's tip).
            int front = 10;
            while (world.get(front, 65, 0) != air) front++;
            face = front;
            apply(spec, stroke, new Dab(i, face * 16, 65 * 16 + 8, 8, 255), world);
        }
        int tip = 10;
        while (world.get(tip, 65, 0) != air) tip++;
        // Strength 0.5 at the centre: one block every two dabs, never more than one block a dab.
        assertEquals(20, tip, "the bump's tip after 20 half-strength dabs");
        for (int y = 55; y <= 75; y++) {
            for (int z = -8; z <= 8; z++) {
                int front = 10;
                while (front < 40 && world.get(front, y, z) != air) front++;
                assertTrue(front <= 20, "no cell ran ahead of the centre at " + y + "," + z);
            }
        }
    }

    @Test
    void smoothOnAWallRemovesABumpAndFillsADent() {
        FakeWorld world = eastWall(9);
        world.set(10, 65, 0, stone);
        world.set(10, 66, 0, stone);
        world.set(9, 63, 2, air);
        BrushSpec smooth = spec(BrushTool.SMOOTH, 4, 1f, Falloff.CONSTANT, Shape.CIRCLE).withSurface(null);
        List<Write> writes = apply(smooth, new Dab(0, 160, 65 * 16 + 8, 8, 255), world);
        assertEquals(air, world.get(10, 65, 0));
        assertEquals(air, world.get(10, 66, 0));
        assertEquals(dirt, world.get(9, 63, 2), "the dent takes the wall's block");
        assertEquals(3, writes.size(), "only the bump and the dent change: " + writes);
    }

    @Test
    void flattenOnAWallCutsBumpsAndFillsDentsToThePlane() {
        FakeWorld world = eastWall(9);
        world.set(10, 64, 1, dirt);
        world.set(11, 64, 1, dirt);
        world.set(10, 66, -1, dirt);
        world.set(9, 65, 2, air);
        world.set(8, 65, 2, air);
        SurfacePlane plane = SurfacePlane.through(Facing.EAST, 160, 65 * 16 + 8, 8);
        assertEquals(new SurfacePlane(Facing.EAST, 9), plane);
        BrushSpec flatten = new BrushSpec(BrushTool.FLATTEN, 4, 1f, Falloff.CONSTANT, Shape.CIRCLE, null,
                SurfaceMask.ANY, 0, 0, 1L).withSurface(plane);
        List<Write> writes = apply(flatten, new Dab(0, 160, 65 * 16 + 8, 8, 255), world);
        for (int y = 62; y <= 68; y++) {
            for (int z = -2; z <= 2; z++) {
                assertEquals(dirt, world.get(9, y, z), "the wall's face at " + y + "," + z);
                assertEquals(dirt, world.get(8, y, z));
                assertEquals(air, world.get(10, y, z), "in front of the wall at " + y + "," + z);
            }
        }
        assertEquals(air, world.get(11, 64, 1));
        assertEquals(5, writes.size(), writes.toString());
    }

    // ------------------------------------------------------------------ ceilings

    /**
     * One Flatten click at low strength still moves every column it touches a whole block toward the plane (the hill
     * down, the pit up), charged to the line's accumulator: the next dab moves nothing until the weight has paid the
     * block off, and a held stroke reaches the plane and stops there.
     */
    @Test
    void theFirstFlattenDabMovesEachLineAtLeastOneBlockTowardThePlane() {
        FakeWorld world = hillAndPit();
        BrushSpec spec = new BrushSpec(BrushTool.FLATTEN, 6, 0.1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY,
                0, 60, 1L).withSurface(SurfacePlane.through(Facing.UP, 8, 61 * 16, 8));
        int[][] before = new int[13][13];
        for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) before[x + 6][z + 6] = top(world, x, z);
        StrokeState stroke = new StrokeState();
        Dab dab = new Dab(0, 8, 61 * 16, 8, 255);
        apply(spec, stroke, dab, world);
        int moved = 0;
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                int was = before[x + 6][z + 6];
                if (was == 60 || x * x + z * z > 36) {
                    assertEquals(was, top(world, x, z), "outside, or level already: " + x + "," + z);
                } else {
                    assertEquals(was - Integer.signum(was - 60), top(world, x, z), "one block toward the plane at " + x + "," + z);
                    moved++;
                }
            }
        }
        assertTrue(moved >= 20, "the hill and the pit moved: " + moved);
        // The second dab: weight 0.1 × the distance left is under the block still owed, so nothing moves yet.
        int[][] after = new int[13][13];
        for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) after[x + 6][z + 6] = top(world, x, z);
        apply(spec, stroke, new Dab(1, 8, 61 * 16, 8, 255), world);
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) assertEquals(after[x + 6][z + 6], top(world, x, z), "no free block at " + x + "," + z);
        }
        // Held: every column ends level at 60 and stays there.
        for (int i = 2; i < 40; i++) apply(spec, stroke, new Dab(i, 8, 61 * 16, 8, 255), world);
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) assertEquals(60, top(world, x, z), x + "," + z);
    }

    @Test
    void aCeilingIsRaisedDownLoweredUpSmoothedAndFlattened() {
        Dab dab = new Dab(0, 8, 70 * 16, 8, 255);
        FakeWorld raiseWorld = ceiling(70);
        for (Write write : apply(spec(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE).withSurface(null), dab,
                raiseWorld)) {
            assertEquals(69, write.y, "the ceiling grows down: " + write);
            assertEquals(stone, write.state);
        }
        FakeWorld lowerWorld = ceiling(70);
        List<Write> lowered = apply(spec(BrushTool.LOWER, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE).withSurface(null), dab,
                lowerWorld);
        assertFalse(lowered.isEmpty());
        for (Write write : lowered) assertEquals(70, write.y, "the ceiling's face removed: " + write);

        FakeWorld smoothWorld = ceiling(70);
        smoothWorld.set(0, 69, 0, stone);
        smoothWorld.set(0, 68, 0, stone);
        List<Write> smoothed = apply(spec(BrushTool.SMOOTH, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE).withSurface(null), dab,
                smoothWorld);
        assertEquals(air, smoothWorld.get(0, 69, 0), "the stalactite goes");
        assertEquals(air, smoothWorld.get(0, 68, 0));
        assertEquals(2, smoothed.size());

        FakeWorld flattenWorld = ceiling(70);
        flattenWorld.set(1, 69, 1, stone);
        flattenWorld.set(-1, 70, 0, air);
        flattenWorld.set(-1, 71, 0, air);
        SurfacePlane plane = SurfacePlane.through(Facing.DOWN, 8, 70 * 16, 8);
        assertEquals(new SurfacePlane(Facing.DOWN, 70), plane);
        apply(new BrushSpec(BrushTool.FLATTEN, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L)
                .withSurface(plane), dab, flattenWorld);
        assertEquals(air, flattenWorld.get(1, 69, 1));
        assertEquals(stone, flattenWorld.get(-1, 70, 0));
        assertEquals(stone, flattenWorld.get(-1, 71, 0));
    }

    // ------------------------------------------------------------------ the ball, masks, clip, materials

    @Test
    void nothingOutsideTheBallOrCylinderChanges() {
        // Smooth writes inside the ball; Raise, Lower and Flatten inside the cylinder along the direction (up, on this
        // terrain): the footprint across it, radius + 8 along it.
        for (BrushTool tool : List.of(BrushTool.RAISE, BrushTool.LOWER, BrushTool.SMOOTH, BrushTool.FLATTEN)) {
            for (Shape shape : Shape.values()) {
                FakeWorld world = TerrainModeGoldenTest.terrain(states);
                SurfacePlane plane = tool == BrushTool.FLATTEN ? new SurfacePlane(Facing.UP, 61) : null;
                BrushSpec spec = new BrushSpec(tool, 5, 1f, Falloff.CONSTANT, shape, null, SurfaceMask.ANY, 0, 0, 3L)
                        .withSurface(plane);
                StrokeState stroke = new StrokeState();
                for (int i = 0; i < 6; i++) {
                    Dab dab = new Dab(i, (-3 + i * 2) * 16 + 5, (62 + i % 2) * 16 + 3, (i - 2) * 16 + 11, 255);
                    List<Write> writes = new ArrayList<>();
                    SurfaceKernelTest.apply(spec, stroke, dab, world, writes);
                    for (Write write : writes) {
                        boolean plant = write.state == air && plantBelowOrAbove(write, dab, 5, shape);
                        boolean inside = tool == BrushTool.SMOOTH ? inBall(dab, 5, shape, write.x, write.y, write.z)
                                : inCylinder(dab, 5, shape, write.x, write.y, write.z);
                        assertTrue(inside || plant, tool + " " + shape + " dab " + i + " wrote outside: " + write);
                    }
                }
            }
        }
    }

    @Test
    void masksAndTheClipBoxWorkPerCell() {
        Dab dab = new Dab(0, 160, 65 * 16 + 8, 8, 255);
        // Stone below y 65 on the wall's face, dirt from 65.
        FakeWorld world = eastWall(9);
        for (int y = 55; y < 65; y++) for (int z = -10; z <= 10; z++) world.set(9, y, z, stone);
        SurfaceMask dirtOnly = new SurfaceMask.SurfaceBlocks(new CellMask.Blocks(List.of(new NamespacedId("minecraft:dirt"))));
        BrushSpec raise = new BrushSpec(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, dirtOnly, 0, 0, 1L)
                .withSurface(null);
        List<Write> writes = apply(raise, dab, world);
        assertFalse(writes.isEmpty());
        for (Write write : writes) assertTrue(write.y >= 65, "only the dirt part moves: " + write);

        SurfaceMask high = new SurfaceMask.Elevation(66, 67);
        List<Write> banded = apply(new BrushSpec(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, high, 0, 0,
                1L).withSurface(null), dab, eastWall(9));
        assertFalse(banded.isEmpty());
        for (Write write : banded) assertTrue(write.y == 66 || write.y == 67, "the Y mask: " + write);

        // The Slope mask: a wall is steep (16), so "0 to 3" leaves it alone and "3 to 16" lets it move.
        assertTrue(apply(new BrushSpec(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, null,
                new SurfaceMask.Slope(0, 3), 0, 0, 1L).withSurface(null), dab, eastWall(9)).isEmpty());
        assertFalse(apply(new BrushSpec(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, null,
                new SurfaceMask.Slope(3, 16), 0, 0, 1L).withSurface(null), dab, eastWall(9)).isEmpty());

        Box clip = new Box(new BlockPos(0, 64, -10), new BlockPos(20, 70, 0));
        BrushSpec clipped = spec(BrushTool.SMOOTH, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE).withClip(clip).withSurface(null);
        FakeWorld bumpy = eastWall(9);
        for (int y = 62; y <= 68; y += 2) for (int z = -2; z <= 2; z += 2) bumpy.set(10, y, z, dirt);
        List<Write> smoothed = apply(clipped, dab, bumpy);
        assertFalse(smoothed.isEmpty());
        for (Write write : smoothed) assertTrue(clip.contains(write.x, write.y, write.z), "outside the clip: " + write);
        assertEquals(dirt, bumpy.get(10, 62, 2), "a bump outside the box stays");
    }

    @Test
    void newBlocksTakeTheSurroundingGroundAndStructuresStay() {
        // A dent in a wall of stone with a dirt band: the dent takes what is most common around it.
        FakeWorld world = eastWall(9);
        for (int y = 55; y <= 75; y++) for (int z = -10; z <= 10; z++) world.set(9, y, z, stone);
        for (int z = -10; z <= 10; z++) world.set(9, 66, z, dirt);
        world.set(9, 64, 0, air);
        apply(spec(BrushTool.SMOOTH, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE).withSurface(null),
                new Dab(0, 160, 65 * 16 + 8, 8, 255), world);
        assertEquals(stone, world.get(9, 64, 0));

        // A stair on the wall's face is never overwritten, nor the block it stands on removed.
        FakeWorld structures = eastWall(9);
        structures.set(10, 65, 0, stairs);
        structures.set(9, 64, 1, dirt);
        structures.set(9, 65, 1, stairs);
        List<Write> lowered = apply(spec(BrushTool.LOWER, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE).withSurface(null),
                new Dab(0, 160, 65 * 16 + 8, 8, 255), structures);
        assertEquals(stairs, structures.get(10, 65, 0));
        assertEquals(stairs, structures.get(9, 65, 1));
        assertEquals(dirt, structures.get(9, 64, 1), "holds up the stair");
        assertFalse(lowered.isEmpty());
    }

    @Test
    void plantsGoWithTheirGroundAndWaterRefillsRemovedCells() {
        FakeWorld world = floor(60);
        world.set(0, 61, 0, shortGrass);
        apply(spec(BrushTool.LOWER, 2, 1f, Falloff.CONSTANT, Shape.CIRCLE).withSurface(null),
                new Dab(0, 8, 61 * 16, 8, 255), world);
        assertEquals(air, world.get(0, 61, 0), "no floating grass");
        assertEquals(air, world.get(0, 60, 0));

        // A wall under water: lowering it lets the water in.
        FakeWorld wet = eastWall(9);
        for (int x = 10; x <= 20; x++) {
            for (int y = 55; y <= 75; y++) for (int z = -10; z <= 10; z++) wet.set(x, y, z, water);
        }
        List<Write> lowered = apply(spec(BrushTool.LOWER, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE).withSurface(null),
                new Dab(0, 160, 65 * 16 + 8, 8, 255), wet);
        assertFalse(lowered.isEmpty());
        for (Write write : lowered) assertEquals(water, write.state, "refilled: " + write);
    }

    // ------------------------------------------------------------------ strength, steps, determinism

    @Test
    void lowStrengthBuildsUpOverDabs() {
        FakeWorld world = eastWall(9);
        BrushSpec spec = spec(BrushTool.LOWER, 2, 0.5f, Falloff.CONSTANT, Shape.SQUARE).withSurface(null);
        StrokeState stroke = new StrokeState();
        Dab dab = new Dab(0, 160, 65 * 16 + 8, 8, 255);
        assertTrue(apply(spec, stroke, dab, world).isEmpty());
        assertTrue(stroke.trackedSurface() > 0);
        assertFalse(apply(spec, stroke, new Dab(1, 160, 65 * 16 + 8, 8, 255), world).isEmpty());

        FakeWorld smoothWorld = floor(60);
        smoothWorld.set(0, 61, 0, stone);
        BrushSpec smooth = spec(BrushTool.SMOOTH, 2, 0.5f, Falloff.CONSTANT, Shape.CIRCLE).withSurface(null);
        StrokeState smoothStroke = new StrokeState();
        assertTrue(apply(smooth, smoothStroke, new Dab(0, 8, 61 * 16, 8, 255), smoothWorld).isEmpty());
        assertEquals(1, apply(smooth, smoothStroke, new Dab(1, 8, 61 * 16, 8, 255), smoothWorld).size());
        assertEquals(air, smoothWorld.get(0, 61, 0));
    }

    @Test
    void aStepWritesTheSameWhateverOrderItsDabsAreListedIn() {
        for (BrushTool tool : List.of(BrushTool.RAISE, BrushTool.LOWER, BrushTool.SMOOTH, BrushTool.FLATTEN)) {
            SurfacePlane plane = tool == BrushTool.FLATTEN ? new SurfacePlane(Facing.UP, 61) : null;
            BrushSpec spec = new BrushSpec(tool, 4, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 3L)
                    .withSurface(plane);
            List<Dab> dabs = List.of(new Dab(0, 20, 63 * 16, 40, 255), new Dab(0, 70, 62 * 16 + 9, 30, 200),
                    new Dab(0, 40, 64 * 16, 100, 128));
            List<Write> forward = new ArrayList<>();
            List<Write> backward = new ArrayList<>();
            BrushKernels.forTool(tool).applyStep(spec, dabs, new StrokeState(), TerrainModeGoldenTest.terrain(states),
                    (x, y, z, h) -> forward.add(new Write(x, y, z, h)));
            BrushKernels.forTool(tool).applyStep(spec, List.of(dabs.get(2), dabs.get(0), dabs.get(1)), new StrokeState(),
                    TerrainModeGoldenTest.terrain(states), (x, y, z, h) -> backward.add(new Write(x, y, z, h)));
            assertFalse(forward.isEmpty(), tool.toString());
            assertEquals(forward, backward, tool.toString());
        }
    }

    @Test
    void theSurfaceModeDiffersFromTheTerrainModeOnlyWhereItShould() {
        // On a wall the Terrain mode raises the columns' tops; the Surface mode pushes the face out.
        Dab dab = new Dab(0, 160, 65 * 16 + 8, 8, 255);
        BrushSpec raise = spec(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE);
        assertNotEquals(apply(raise, dab, eastWall(9)), apply(raise.withSurface(null), dab, eastWall(9)));
    }

    // ------------------------------------------------------------------ symmetry

    @Test
    void aMirroredCopyWorksTheMirroredWallAndFollowsTheGroundElsewhere() {
        // A corridor along z between walls x <= -10 and x >= 10 (mirror plane x = 0): the copy of a dab on the east
        // side of the west wall lands on the west side of the east wall, at the same height.
        FakeWorld world = new FakeWorld(states);
        for (int y = 55; y <= 75; y++) {
            for (int z = -10; z <= 10; z++) {
                for (int x = -14; x <= -10; x++) world.set(x, y, z, dirt);
                for (int x = 10; x <= 14; x++) world.set(x, y, z, dirt);
            }
        }
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 0, 0);
        BrushSpec raise = spec(BrushTool.RAISE, 2, 1f, Falloff.CONSTANT, Shape.CIRCLE).withSymmetry(mirror).withSurface(null);
        Dab dab = new Dab(0, -9 * 16, 65 * 16 + 8, 8, 255);
        SymmetricStep step = SymmetricStep.of(raise, dab, world);
        assertEquals(2, step.dabs().size());
        assertEquals(dab.y16(), step.dabs().get(1).y16(), "the copy stays at its mirrored point");
        List<Write> writes = apply(raise, dab, world);
        Set<Integer> xs = new HashSet<>();
        for (Write write : writes) xs.add(write.x);
        assertEquals(Set.of(-9, 9), xs, "both walls pushed into the corridor: " + writes);

        // Ground 6 blocks lower on the copy's side: the copy stands on it, as in the Terrain mode.
        FakeWorld uneven = terrain((x, z) -> x >= 0 ? 54 : 60);
        SymmetricStep grounded = SymmetricStep.of(raise, new Dab(0, -5 * 16 + 8, 61 * 16, 8, 255), uneven);
        assertEquals(2, grounded.dabs().size());
        assertEquals(55 * 16, grounded.dabs().get(1).y16());
    }

    @Test
    void flattenCopiesUseTheMirroredPlane() {
        SurfacePlane east = new SurfacePlane(Facing.EAST, -10);
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 0, 0);
        assertEquals(new SurfacePlane(Facing.WEST, 9), east.image(mirror, Symmetry.Image.MIRROR_X));
        assertEquals(new SurfacePlane(Facing.UP, 61), new SurfacePlane(Facing.UP, 61).image(mirror, Symmetry.Image.MIRROR_X));
        Symmetry turns = new Symmetry(Symmetry.Mode.ROTATE_4, 0, 0);
        // A quarter turn clockwise about (0, 0): solid x <= -10 facing east becomes solid z <= -10 facing south.
        assertEquals(new SurfacePlane(Facing.SOUTH, -10), east.image(turns, Symmetry.Image.QUARTER_CW));
        assertEquals(new SurfacePlane(Facing.WEST, 9), east.image(turns, Symmetry.Image.HALF_TURN));
        assertEquals(new SurfacePlane(Facing.NORTH, 9), east.image(turns, Symmetry.Image.QUARTER_CCW));
    }

    @Test
    void aFlattenDabKeepsItsOwnPlaneWhereAnImageLiesNearer() {
        // Solid for x <= 29; a dab on that face at z 9.5 flattens toward the press's plane (EAST, 9) with Rotate 4
        // about (0, 0), although the plane's quarter-turned image (SOUTH, -10 ... NORTH, 9) passes nearer the dab.
        FakeWorld world = new FakeWorld(states);
        for (int x = -40; x <= 29; x++) {
            for (int y = 50; y <= 80; y++) for (int z = -40; z <= 40; z++) world.set(x, y, z, dirt);
        }
        BrushSpec flatten = new BrushSpec(BrushTool.FLATTEN, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY,
                0, 0, 1L, null, new Symmetry(Symmetry.Mode.ROTATE_4, 0, 0)).withSurface(new SurfacePlane(Facing.EAST, 9));
        List<Write> writes = apply(flatten, new Dab(0, 30 * 16, 65 * 16 + 8, 9 * 16 + 8, 255), world);
        assertTrue(writes.contains(new Write(29, 65, 9, air)), "the face is cut back toward x 9: " + writes);
        for (Write write : writes) {
            if (write.x > 20) assertEquals(air, write.state, "only cut, toward the plane: " + write);
        }
    }

    @Test
    void groundAStructureTouchesIsNotRemoved() {
        int widget = states.state("testmod:widget[facing=north]");
        // A bump on a wall with a "torch" fixed to its north side: Smooth leaves the bump.
        FakeWorld wall = eastWall(9);
        wall.set(10, 65, 0, dirt);
        wall.set(10, 65, -1, widget);
        apply(spec(BrushTool.SMOOTH, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE).withSurface(null),
                new Dab(0, 160, 65 * 16 + 8, 8, 255), wall);
        assertEquals(dirt, wall.get(10, 65, 0), "the bump holds the widget");
        assertEquals(widget, wall.get(10, 65, -1));
        // A "lantern" hanging under a ceiling: Lower keeps the cell above it and removes its neighbours.
        FakeWorld ceiling = ceiling(70);
        ceiling.set(0, 69, 0, widget);
        List<Write> lowered = apply(spec(BrushTool.LOWER, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE).withSurface(null),
                new Dab(0, 8, 70 * 16, 8, 255), ceiling);
        assertEquals(stone, ceiling.get(0, 70, 0), "holds the lantern");
        assertEquals(widget, ceiling.get(0, 69, 0));
        assertTrue(lowered.contains(new Write(1, 70, 0, air)), lowered.toString());
        // A stair set into a wall's face: Lower keeps the blocks beside it, above it and below it.
        FakeWorld set = eastWall(9);
        set.set(9, 65, 1, stairs);
        List<Write> wallLowered = apply(spec(BrushTool.LOWER, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE).withSurface(null),
                new Dab(0, 160, 65 * 16 + 8, 8, 255), set);
        for (int[] kept : new int[][] {{9, 65, 0}, {9, 65, 2}, {9, 64, 1}, {9, 66, 1}}) {
            assertEquals(dirt, set.get(kept[0], kept[1], kept[2]), "beside the stair: " + java.util.Arrays.toString(kept));
        }
        assertTrue(wallLowered.contains(new Write(9, 63, 0, air)), wallLowered.toString());
    }

    @Test
    void theOutputDoesNotDependOnHowStatesAreNumbered() {
        Reversed reversed = new Reversed(states);
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_XZ, 1, -1);
        SurfaceMask notSand = new SurfaceMask.Not(new SurfaceMask.SurfaceBlocks(
                new CellMask.Blocks(List.of(new NamespacedId("minecraft:sand")))));
        for (BrushTool tool : List.of(BrushTool.RAISE, BrushTool.LOWER, BrushTool.SMOOTH, BrushTool.FLATTEN)) {
            SurfacePlane plane = tool == BrushTool.FLATTEN ? new SurfacePlane(Facing.UP, 61) : null;
            BrushSpec spec = new BrushSpec(tool, 4, 0.7f, Falloff.SMOOTH, Shape.CIRCLE, null, notSand, 0, 0, 3L, null,
                    mirror).withSurface(plane);
            FakeWorld plain = TerrainModeGoldenTest.terrain(states);
            FakeWorld renumbered = new FakeWorld(reversed);
            for (int x = -30; x <= 30; x++) {
                for (int z = -30; z <= 30; z++) {
                    for (int y = 48; y <= 75; y++) renumbered.set(x, y, z, reversed.to(plain.get(x, y, z)));
                }
            }
            StrokeState a = new StrokeState(), b = new StrokeState();
            List<Write> first = new ArrayList<>(), second = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                Dab dab = new Dab(i, (-6 + 2 * i) * 16 + 3, (63 + i % 2) * 16 + 5, (i - 3) * 16 + 9, 180 + i * 9);
                apply(spec, a, dab, plain, first);
                List<Write> other = new ArrayList<>();
                apply(spec, b, dab, renumbered, other);
                for (Write write : other) second.add(new Write(write.x, write.y, write.z, reversed.from(write.state)));
            }
            assertFalse(first.isEmpty(), tool.toString());
            assertEquals(first, second, tool + ": the same cells, in the same order, whatever the handles");
        }
    }

    @Test
    void readsAndWritesStayInsideTheBoxesTheServerChecks() {
        // A short world (y 0-63): brushes at its bottom and top, and in the middle, of radius 1, 2 and 32.
        for (int radius : new int[] {1, 2, 32}) {
            for (Shape shape : Shape.values()) {
                for (int level : new int[] {1, 30, 61}) {
                    FakeWorld world = new FakeWorld(states, 0, 64);
                    for (int x = -45; x <= 45; x++) {
                        for (int z = -45; z <= 45; z++) {
                            int top = Math.min(63, level + Math.floorMod(x * 3 + z, 3) + (x > 3 ? 2 : 0));
                            for (int y = 0; y <= top; y++) world.set(x, y, z, y == top ? grass : stone);
                            if (x == -2 && top < 62) world.set(x, top + 1, z, shortGrass);
                        }
                    }
                    for (BrushTool tool : List.of(BrushTool.RAISE, BrushTool.LOWER, BrushTool.SMOOTH, BrushTool.FLATTEN)) {
                        SurfacePlane plane = tool == BrushTool.FLATTEN ? new SurfacePlane(Facing.UP, level) : null;
                        BrushSpec spec = new BrushSpec(tool, radius, 1f, Falloff.CONSTANT, shape, null, SurfaceMask.ANY, 0,
                                0, 1L, null, new Symmetry(Symmetry.Mode.MIRROR_XZ, 1, 3)).withSurface(plane);
                        Dab dab = new Dab(0, 2 * 16 + 5, (level + 1) * 16, 5 * 16 + 11, 255);
                        SymmetricStep step = SymmetricStep.of(spec, dab, world);
                        List<Box> allowed = new ArrayList<>();
                        for (Dab placed : step.dabs()) allowed.add(dabBox(spec, world, placed));
                        for (Dab copy : step.searched()) {
                            allowed.add(SymmetricStep.searchBox(spec, copy.blockX(), copy.blockZ(), dab.blockY(), 0, 64));
                        }
                        String what = tool + " radius " + radius + " " + shape + " at " + level;
                        Watched watched = new Watched(world, allowed, what);
                        BrushKernels.forTool(tool).applyStep(spec, step.dabs(), new StrokeState(), watched,
                                (x, y, z, h) -> {
                                    assertTrue(y >= 0 && y < 64, what + " wrote outside the world at y " + y);
                                    assertTrue(inside(allowed, x, y, z), what + " wrote outside its boxes at " + x + ","
                                            + y + "," + z);
                                });
                        assertTrue(watched.reads > 0, what);
                    }
                }
            }
        }
    }

    /** A reader that fails any read outside {@code allowed}. */
    private static final class Watched implements dev.sculptory.core.world.WorldReader {
        final FakeWorld world;
        final List<Box> allowed;
        final String what;
        int reads;

        Watched(FakeWorld world, List<Box> allowed, String what) {
            this.world = world;
            this.allowed = allowed;
            this.what = what;
        }

        @Override
        public dev.sculptory.core.state.StateSpace states() {
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
            assertTrue(inside(allowed, x, Math.max(0, Math.min(63, y)), z), what + " read outside its boxes at " + x + ","
                    + y + "," + z);
            return world.get(x, y, z);
        }

        @Override
        public dev.sculptory.core.buffer.BlockEntityData tile(int x, int y, int z) {
            return world.tile(x, y, z);
        }

        @Override
        public void copySection(int sx, int sy, int sz, dev.sculptory.core.buffer.SectionBuffer into) {
            throw new AssertionError("not used by brushes");
        }
    }

    private static boolean inside(List<Box> boxes, int x, int y, int z) {
        for (Box box : boxes) {
            if (box.contains(x, y, z)) return true;
        }
        return false;
    }

    /**
     * {@code EngineEditService.dabBox}: every cell a terrain dab may read or write, clamped to the world; as wide across
     * as the reach for Surface Raise, Lower and Flatten (the cylinder faces any of six ways).
     */
    private static Box dabBox(BrushSpec spec, FakeWorld world, Dab dab) {
        int radius = spec.radius();
        long reach = radius + TerrainKernel.SCAN_MARGIN;
        int across = spec.surface() && spec.tool() != BrushTool.SMOOTH ? (int) reach + 2 : radius + 2;
        int bottom = world.bottomY(), top = world.topYExclusive() - 1;
        int y0 = (int) Math.max(bottom, Math.min(top, dab.blockY() - 2 * reach - BrushSpec.MAX_DEPTH - 1));
        int y1 = (int) Math.max(bottom, Math.min(top, dab.blockY() + 2 * reach + 3));
        return new Box(new BlockPos(dab.blockX() - across, y0, dab.blockZ() - across),
                new BlockPos(dab.blockX() + across, y1, dab.blockZ() + across));
    }

    /** The fake state space numbered backwards: the same blocks, every handle different (air last). */
    private static final class Reversed implements dev.sculptory.core.state.StateSpace {
        final FakeStateSpace inner;
        final int n;

        Reversed(FakeStateSpace inner) {
            this.inner = inner;
            this.n = inner.size();
        }

        int to(int h) {
            return h < 0 ? h : n - 1 - h;
        }

        int from(int h) {
            return h < 0 ? h : n - 1 - h;
        }

        @Override
        public int size() {
            return n;
        }

        @Override
        public int air() {
            return to(inner.air());
        }

        @Override
        public int flags(int h) {
            return inner.flags(from(h));
        }

        @Override
        public String format(int h) {
            return inner.format(from(h));
        }

        @Override
        public int parse(String spec) {
            return to(inner.parse(spec));
        }

        @Override
        public dev.sculptory.core.BlockDescriptor describe(int h) {
            return inner.describe(from(h));
        }

        @Override
        public int resolve(dev.sculptory.core.BlockDescriptor d) {
            return to(inner.resolve(d));
        }

        @Override
        public NamespacedId blockId(int h) {
            return inner.blockId(from(h));
        }

        @Override
        public boolean inTag(int h, NamespacedId tag) {
            return inner.inTag(from(h), tag);
        }

        @Override
        public int rotate(int h, int turns) {
            return to(inner.rotate(from(h), turns));
        }

        @Override
        public int mirror(int h, dev.sculptory.core.transform.Mirror m) {
            return to(inner.mirror(from(h), m));
        }

        @Override
        public int withWaterlogged(int h, boolean on) {
            return to(inner.withWaterlogged(from(h), on));
        }

        @Override
        public int fluidSource(int h) {
            return to(inner.fluidSource(from(h)));
        }
    }

    // ------------------------------------------------------------------ specs

    @Test
    void onlyTheSculptingBrushesHaveASurfaceModeAndOnlyFlattenAPlane() {
        BrushSpec paint = new BrushSpec(BrushTool.PAINT, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, new Pattern.Single(stone),
                SurfaceMask.ANY, 1, 0, 1L);
        assertThrows(IllegalArgumentException.class, () -> paint.withSurface(null));
        BrushSpec flatten = spec(BrushTool.FLATTEN, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE);
        assertThrows(IllegalArgumentException.class, () -> flatten.withSurface(null), "Surface Flatten needs a plane");
        BrushSpec raise = spec(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE);
        assertThrows(IllegalArgumentException.class, () -> raise.withSurface(new SurfacePlane(Facing.UP, 1)));
        assertThrows(IllegalArgumentException.class, () -> new SurfacePlane(Facing.EAST, BrushSpec.CLIP_MAX_HORIZONTAL + 1));
        assertThrows(IllegalArgumentException.class, () -> new SurfacePlane(Facing.UP, BrushSpec.CLIP_MAX_Y + 1));
        assertEquals(SculptMode.TERRAIN, raise.mode());
        assertEquals(SculptMode.SURFACE, raise.withSurface(null).mode());
        assertEquals(SculptMode.SURFACE, raise.withSurface(null).withClip(null).withSymmetry(Symmetry.NONE).mode());
    }

    // ------------------------------------------------------------------ cost

    @Test
    void aRadius32DabStaysWithinItsBudget() {
        for (BrushTool tool : List.of(BrushTool.RAISE, BrushTool.LOWER, BrushTool.SMOOTH, BrushTool.FLATTEN)) {
            SurfacePlane plane = tool == BrushTool.FLATTEN ? new SurfacePlane(Facing.UP, 62) : null;
            BrushSpec spec = new BrushSpec(tool, 32, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 3L)
                    .withSurface(plane);
            long best = Long.MAX_VALUE;
            int written = 0;
            for (int run = 0; run < 3; run++) {
                FakeWorld copy = TerrainModeGoldenTest.terrain(states);
                List<Write> writes = new ArrayList<>();
                long start = System.nanoTime();
                SurfaceKernelTest.apply(spec, new StrokeState(), new Dab(0, 8, 62 * 16, 8, 255), copy, writes);
                best = Math.min(best, System.nanoTime() - start);
                written = writes.size();
            }
            System.out.printf("Surface %s radius 32: %.1f ms, %d cells (FakeWorld)%n", tool, best / 1e6, written);
            assertTrue(best < 2_000_000_000L, tool + " took " + best / 1e6 + " ms");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static BrushSpec spec(BrushTool tool, int radius, float strength, Falloff falloff, Shape shape) {
        return new BrushSpec(tool, radius, strength, falloff, shape, null, SurfaceMask.ANY, 0, 0, 1L);
    }

    private static List<Write> apply(BrushSpec spec, Dab dab, FakeWorld world) {
        return apply(spec, new StrokeState(), dab, world);
    }

    private static List<Write> apply(BrushSpec spec, StrokeState stroke, Dab dab, FakeWorld world) {
        List<Write> writes = new ArrayList<>();
        apply(spec, stroke, dab, world, writes);
        return writes;
    }

    private static void apply(BrushSpec spec, StrokeState stroke, Dab dab, FakeWorld world, List<Write> writes) {
        List<Write> step = new ArrayList<>();
        BrushKernels.forTool(spec.tool()).apply(spec, dab, stroke, world, (x, y, z, h) -> step.add(new Write(x, y, z, h)));
        for (Write write : step) world.set(write.x, write.y, write.z, write.state);
        writes.addAll(step);
    }

    private static List<Long> horizontal(long[] n) {
        return List.of(n[0], n[2]);
    }

    private static boolean inBall(Dab dab, int radius, int x, int y, int z) {
        return inBall(dab, radius, Shape.CIRCLE, x, y, z);
    }

    private static boolean inBall(Dab dab, int radius, Shape shape, int x, int y, int z) {
        long dx = 16L * x + 8 - dab.x16(), dy = 16L * y + 8 - dab.y16(), dz = 16L * z + 8 - dab.z16();
        long r16 = 16L * radius;
        if (shape == Shape.SQUARE) return Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) <= r16;
        return dx * dx + dy * dy + dz * dz <= r16 * r16;
    }

    /**
     * The line tools' cylinder along one of the three axes (the kernel picks it from the surface): the footprint across
     * it, {@code radius + 8} each way along it from the dab's point.
     */
    private static boolean inCylinder(Dab dab, int radius, Shape shape, int x, int y, int z) {
        long[] d = {16L * x + 8 - dab.x16(), 16L * y + 8 - dab.y16(), 16L * z + 8 - dab.z16()};
        long r16 = 16L * radius, reach16 = 16L * (radius + TerrainKernel.SCAN_MARGIN);
        for (int axis = 0; axis < 3; axis++) {
            long along = d[axis], a = d[(axis + 1) % 3], b = d[(axis + 2) % 3];
            if (Math.abs(along) > reach16) continue;
            boolean across = shape == Shape.SQUARE ? Math.max(Math.abs(a), Math.abs(b)) <= r16 : a * a + b * b <= r16 * r16;
            if (across) return true;
        }
        return false;
    }

    /** Whether cell (y, z) lies in the footprint of a dab working along x. */
    private static boolean acrossX(Dab dab, int radius, int y, int z) {
        long dy = 16L * y + 8 - dab.y16(), dz = 16L * z + 8 - dab.z16();
        long r16 = 16L * radius;
        return dy * dy + dz * dz <= r16 * r16;
    }

    /** A plant cleared at most two blocks above or below a cell of the ball. */
    private static boolean plantBelowOrAbove(Write write, Dab dab, int radius, Shape shape) {
        for (int dy = -2; dy <= 2; dy++) {
            if (dy != 0 && inBall(dab, radius, shape, write.x, write.y + dy, write.z)) return true;
        }
        return false;
    }

    private FakeWorld terrain(java.util.function.IntBinaryOperator height) {
        FakeWorld world = new FakeWorld(states);
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                int top = height.applyAsInt(x, z);
                for (int y = 40; y <= top; y++) world.set(x, y, z, y < top ? stone : grass);
            }
        }
        return world;
    }

    private FakeWorld floor(int top) {
        return terrain((x, z) -> top);
    }

    /** Flat at 60 with a stepped hill (63 at the centre) and a two-deep pit at (3, -3). */
    private FakeWorld hillAndPit() {
        FakeWorld world = terrain((x, z) -> 60 + Math.max(0, 3 - Math.max(Math.abs(x), Math.abs(z))));
        world.set(3, 60, -3, air);
        world.set(3, 59, -3, air);
        return world;
    }

    /** Dirt for x in [-5, face] (y 50-80, z -20..20), air east of it: a wall facing east. */
    private FakeWorld eastWall(int face) {
        FakeWorld world = new FakeWorld(states);
        for (int x = face - 5; x <= face; x++) {
            for (int y = 50; y <= 80; y++) for (int z = -20; z <= 20; z++) world.set(x, y, z, dirt);
        }
        return world;
    }

    /** Dirt for x in [face, face + 5], air west of it: a wall facing west. */
    private FakeWorld westWall(int face) {
        FakeWorld world = new FakeWorld(states);
        for (int x = face; x <= face + 5; x++) {
            for (int y = 50; y <= 80; y++) for (int z = -20; z <= 20; z++) world.set(x, y, z, dirt);
        }
        return world;
    }

    /** Stone from y {@code bottom} up to 80 over |x|, |z| <= 20, air below: a ceiling. */
    private FakeWorld ceiling(int bottom) {
        FakeWorld world = new FakeWorld(states);
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) for (int y = bottom; y <= 80; y++) world.set(x, y, z, stone);
        }
        return world;
    }

    private int top(FakeWorld world, int x, int z) {
        for (int y = 100; y >= 40; y--) {
            if (world.get(x, y, z) != air) return y;
        }
        return 39;
    }
}
