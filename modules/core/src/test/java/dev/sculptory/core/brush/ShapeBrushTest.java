package dev.sculptory.core.brush;

import dev.sculptory.core.region.Facing;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.nbt.BlockEntityNbt;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.world.WorldReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The Shape brush's spec, geometry and kernel (the solids' own cells are compared with the regions' in ShapeCellsTest). */
class ShapeBrushTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final int air = states.air();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");
    private final int grass = states.state("minecraft:grass_block");
    private final int water = states.state("minecraft:water");
    private final int shortGrass = states.state("minecraft:short_grass");
    private final int chest = states.state("minecraft:chest");

    record Write(int x, int y, int z, int state) {}

    private static BrushSpec cube(int radius, int height, Facing facing, ShapeSpec.Mode mode, int hollow,
                                  Pattern material) {
        return BrushSpec.shape(radius, new ShapeSpec(ShapeSpec.Kind.CUBE, height, facing, mode, hollow), material, 7L,
                null, Symmetry.NONE);
    }

    private BrushSpec cube(int radius, int height, Facing facing) {
        return cube(radius, height, facing, ShapeSpec.Mode.PLACE, 0, new Pattern.Single(stone));
    }

    /** A dab whose point is the centre of block (x, y, z). */
    private static Dab at(int x, int y, int z) {
        return new Dab(0, 16 * x + 8, 16 * y + 8, 16 * z + 8, Dab.FULL_PRESSURE);
    }

    private static List<Write> apply(BrushSpec spec, WorldReader world, Dab... dabs) {
        List<Write> writes = new ArrayList<>();
        BrushKernels.forTool(BrushTool.SHAPE).applyStep(spec, List.of(dabs), new StrokeState(), world,
                (x, y, z, h) -> writes.add(new Write(x, y, z, h)));
        return writes;
    }

    private static List<Write> stroke(BrushSpec spec, FakeWorld world, Dab dab) {
        List<Write> writes = new ArrayList<>();
        BrushKernels.forTool(BrushTool.SHAPE).apply(spec, dab, new StrokeState(), world, (x, y, z, h) -> {
            writes.add(new Write(x, y, z, h));
            world.set(x, y, z, h);
        });
        return writes;
    }

    private static Set<BlockPos> cells(List<Write> writes) {
        Set<BlockPos> cells = new HashSet<>();
        for (Write w : writes) assertTrue(cells.add(new BlockPos(w.x(), w.y(), w.z())), "written twice: " + w);
        return cells;
    }

    private static Set<BlockPos> cells(Box box) {
        Set<BlockPos> cells = new HashSet<>();
        for (int x = box.min().x(); x <= box.max().x(); x++) {
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) cells.add(new BlockPos(x, y, z));
            }
        }
        return cells;
    }

    // ---- Spec ----

    @Test
    void theShapeBrushNeedsItsShapeAndOthersTakeNone() {
        ShapeSpec sphere = ShapeSpec.solid(ShapeSpec.Kind.SPHERE, 9, Facing.UP);
        assertThrows(IllegalArgumentException.class, () -> new BrushSpec(BrushTool.SHAPE, 4, 1f, Falloff.CONSTANT,
                Shape.CIRCLE, new Pattern.Single(1), SurfaceMask.ANY, 0, 0, 0L));
        assertThrows(IllegalArgumentException.class, () -> new BrushSpec(BrushTool.RAISE, 4, 1f, Falloff.CONSTANT,
                Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 0L, null, Symmetry.NONE, sphere));
        assertThrows(IllegalArgumentException.class, () -> BrushSpec.shape(4, sphere, null, 0L, null, Symmetry.NONE),
                "placing needs a material");
        ShapeSpec carve = new ShapeSpec(ShapeSpec.Kind.SPHERE, 9, Facing.UP, ShapeSpec.Mode.CARVE, 0);
        assertDoesNotThrow(() -> BrushSpec.shape(4, carve, null, 0L, null, Symmetry.NONE));
        for (ShapeSpec.Mode mode : List.of(ShapeSpec.Mode.PLACE, ShapeSpec.Mode.PLACE_IN_AIR, ShapeSpec.Mode.PAINT)) {
            ShapeSpec placing = new ShapeSpec(ShapeSpec.Kind.CONE, 9, Facing.UP, mode, 0);
            assertThrows(IllegalArgumentException.class, () -> BrushSpec.shape(4, placing, null, 0L, null, Symmetry.NONE));
        }
        assertThrows(IllegalArgumentException.class, () -> new ShapeSpec(ShapeSpec.Kind.CUBE, 0, Facing.UP,
                ShapeSpec.Mode.PLACE, 0));
        assertThrows(IllegalArgumentException.class, () -> new ShapeSpec(ShapeSpec.Kind.CUBE, ShapeSpec.MAX_HEIGHT + 1,
                Facing.UP, ShapeSpec.Mode.PLACE, 0));
        assertThrows(IllegalArgumentException.class, () -> new ShapeSpec(ShapeSpec.Kind.CUBE, 3, Facing.UP,
                ShapeSpec.Mode.PLACE, -1));
        assertThrows(IllegalArgumentException.class, () -> new ShapeSpec(ShapeSpec.Kind.CUBE, 3, Facing.UP,
                ShapeSpec.Mode.PLACE, ShapeSpec.MAX_HOLLOW + 1));
        assertEquals(65, ShapeSpec.MAX_HEIGHT, "the largest diameter");
        BrushSpec spec = BrushSpec.shape(4, sphere, new Pattern.Single(1), 3L, null, Symmetry.NONE);
        Box clip = Box.of(BlockPos.ORIGIN);
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 1, 0);
        assertEquals(sphere, spec.withClip(clip).withSymmetry(mirror).shapeSpec(), "the withers keep the shape");
        assertNotEquals(spec, BrushSpec.shape(4, ShapeSpec.solid(ShapeSpec.Kind.SPHERE, 8, Facing.UP),
                new Pattern.Single(1), 3L, null, Symmetry.NONE), "the shape is part of the spec");
    }

    @Test
    void theReachIsTheBoxsLongestHalfSidePlusOne() {
        Pattern stone = new Pattern.Single(1);
        assertEquals(5, BrushSpec.shape(4, ShapeSpec.solid(ShapeSpec.Kind.SPHERE, 65, Facing.UP), stone, 0, null,
                Symmetry.NONE).reach(), "a sphere ignores its height");
        assertEquals(5, BrushSpec.shape(4, ShapeSpec.solid(ShapeSpec.Kind.CUBE, 3, Facing.UP), stone, 0, null,
                Symmetry.NONE).reach());
        assertEquals(11, BrushSpec.shape(4, ShapeSpec.solid(ShapeSpec.Kind.CUBE, 20, Facing.EAST), stone, 0, null,
                Symmetry.NONE).reach());
        assertEquals(11, BrushSpec.shape(4, ShapeSpec.solid(ShapeSpec.Kind.CUBE, 21, Facing.UP), stone, 0, null,
                Symmetry.NONE).reach());
        assertEquals(6, new BrushSpec(BrushTool.RAISE, 6, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0,
                0L).reach(), "the terrain brushes' reach is their radius");
    }

    // ---- Boxes ----

    @Test
    void boxesAreCentredOnTheDabAndAnEvenHeightOnTheNearestEdge() {
        BrushSpec up = cube(2, 4, Facing.UP);
        // Odd sizes are centred on the dab's block whatever the point's fraction.
        for (int fraction = 0; fraction < 16; fraction++) {
            Box box = ShapeStamp.box(up, Facing.UP, 16 * 10 + fraction, 16 * 64, 16 * -3 + fraction);
            assertEquals(8, box.min().x());
            assertEquals(12, box.max().x());
            assertEquals(-5, box.min().z());
            assertEquals(-1, box.max().z());
        }
        // Height 4 centred on the edge y = 64: cells 62-65; a point just below the next edge still rounds to 64.
        assertEquals(new Box(new BlockPos(8, 62, -5), new BlockPos(12, 65, -1)), ShapeStamp.box(up, Facing.UP,
                16 * 10 + 8, 16 * 64, 16 * -3 + 8));
        assertEquals(62, ShapeStamp.box(up, Facing.UP, 168, 16 * 64 + 7, -40).min().y());
        assertEquals(63, ShapeStamp.box(up, Facing.UP, 168, 16 * 64 + 8, -40).min().y(), "ties toward positive");
        // Facing east, the height runs along x.
        Box east = ShapeStamp.box(up, Facing.EAST, 16 * 10, 16 * 64 + 8, 16 * -3 + 8);
        assertEquals(new Box(new BlockPos(8, 62, -5), new BlockPos(11, 66, -1)), east);
        Box south = ShapeStamp.box(up, Facing.SOUTH, 16 * 10 + 8, 16 * 64 + 8, 16 * -3);
        assertEquals(new Box(new BlockPos(8, 62, -5), new BlockPos(12, 66, -2)), south);
    }

    @Test
    void theReachBoxHoldsTheShapeWithEveryFacing() {
        for (ShapeSpec.Kind kind : ShapeSpec.Kind.values()) {
            for (int radius : new int[] {1, 3, 32}) {
                for (int height : new int[] {1, 2, 7, 30, 65}) {
                    BrushSpec spec = BrushSpec.shape(radius, ShapeSpec.solid(kind, height, Facing.UP),
                            new Pattern.Single(1), 0L, null, Symmetry.NONE);
                    for (int fraction : new int[] {0, 5, 8, 15}) {
                        Dab dab = new Dab(0, 160 + fraction, 1024 + fraction, -48 + fraction, 255);
                        Box reach = ShapeStamp.reachBox(spec, dab, -2048, 2048);
                        for (Facing facing : Facing.values()) {
                            Box box = ShapeStamp.box(spec, facing, dab.x16(), dab.y16(), dab.z16());
                            assertTrue(reach.contains(box), spec + " " + facing + " " + fraction);
                        }
                    }
                }
            }
        }
        BrushSpec tall = cube(3, 20, Facing.UP);
        Box clamped = ShapeStamp.reachBox(tall, at(0, 318, 0), -64, 320);
        assertEquals(307, clamped.min().y());
        assertEquals(319, clamped.max().y(), "clamped to the build height");
    }

    // ---- Symmetry ----

    @Test
    void imagesMirrorAndTurnTheFacing() {
        assertEquals(Facing.WEST, ShapeStamp.image(Symmetry.Image.MIRROR_X, Facing.EAST));
        assertEquals(Facing.NORTH, ShapeStamp.image(Symmetry.Image.MIRROR_X, Facing.NORTH));
        assertEquals(Facing.SOUTH, ShapeStamp.image(Symmetry.Image.MIRROR_Z, Facing.NORTH));
        assertEquals(Facing.EAST, ShapeStamp.image(Symmetry.Image.MIRROR_Z, Facing.EAST));
        assertEquals(Facing.SOUTH, ShapeStamp.image(Symmetry.Image.QUARTER_CW, Facing.EAST));
        assertEquals(Facing.EAST, ShapeStamp.image(Symmetry.Image.QUARTER_CW, Facing.NORTH));
        assertEquals(Facing.NORTH, ShapeStamp.image(Symmetry.Image.QUARTER_CCW, Facing.EAST));
        assertEquals(Facing.WEST, ShapeStamp.image(Symmetry.Image.HALF_TURN, Facing.EAST));
        for (Symmetry.Image image : Symmetry.Image.values()) {
            assertEquals(Facing.UP, ShapeStamp.image(image, Facing.UP));
            assertEquals(Facing.DOWN, ShapeStamp.image(image, Facing.DOWN));
        }
    }

    @Test
    void theImagesOfAPointAreTheCopiesOfTheDab() {
        Dab dab = new Dab(4, 16 * 13 + 3, 900, 16 * -6 + 11, 200);
        for (Symmetry.Mode mode : Symmetry.Mode.values()) {
            Symmetry symmetry = new Symmetry(mode, 9, -3);
            List<Dab> copies = symmetry.copies(dab);
            List<Symmetry.Image> images = symmetry.images();
            assertEquals(mode.copies(), images.size());
            assertEquals(Symmetry.Image.IDENTITY, images.get(0));
            for (int i = 0; i < images.size(); i++) {
                assertEquals(copies.get(i).x16(), symmetry.imageX(images.get(i), dab.x16(), dab.z16()), mode + " " + i);
                assertEquals(copies.get(i).z16(), symmetry.imageZ(images.get(i), dab.x16(), dab.z16()), mode + " " + i);
            }
        }
    }

    @Test
    void placementsAreTheExactImagesOfTheShape() {
        // A 5x5 cube 6 long facing east, its point on the block edge x = 20 (the even height's centre).
        BrushSpec spec = cube(2, 6, Facing.EAST);
        Dab dab = new Dab(0, 16 * 20, 16 * 64 + 8, 16 * 7 + 8, 255);
        Box own = ShapeStamp.placement(spec, dab).box();
        assertEquals(new Box(new BlockPos(17, 62, 5), new BlockPos(22, 66, 9)), own);
        // Mirror across x = 10.5: cell x -> 20 - x.
        List<ShapeStamp.Placement> mirrored = ShapeStamp.placements(spec.withSymmetry(
                new Symmetry(Symmetry.Mode.MIRROR_X, 21, 0)), dab);
        assertEquals(2, mirrored.size());
        assertEquals(new Box(new BlockPos(20 - 22, 62, 5), new BlockPos(20 - 17, 66, 9)), mirrored.get(1).box());
        assertEquals(Facing.UP, mirrored.get(1).facing(), "a cube carries no facing");
        // Quarter turns about (10.5, 0.5): the 6-long side turns to run along z.
        Symmetry turns = new Symmetry(Symmetry.Mode.ROTATE_4, 21, 1);
        List<ShapeStamp.Placement> turned = ShapeStamp.placements(spec.withSymmetry(turns), dab);
        assertEquals(4, turned.size());
        for (int i = 0; i < 4; i++) {
            Box box = turned.get(i).box();
            Symmetry.Image image = turns.images().get(i);
            // Each box is the image of the dab's box: map its corners' outer edges (in half blocks) and compare.
            long ax = turns.imageX(image, 16L * own.min().x(), 16L * own.min().z());
            long az = turns.imageZ(image, 16L * own.min().x(), 16L * own.min().z());
            long bx = turns.imageX(image, 16L * (own.max().x() + 1), 16L * (own.max().z() + 1));
            long bz = turns.imageZ(image, 16L * (own.max().x() + 1), 16L * (own.max().z() + 1));
            assertEquals(Math.min(ax, bx), 16L * box.min().x(), "image " + image);
            assertEquals(Math.max(ax, bx), 16L * (box.max().x() + 1), "image " + image);
            assertEquals(Math.min(az, bz), 16L * box.min().z(), "image " + image);
            assertEquals(Math.max(az, bz), 16L * (box.max().z() + 1), "image " + image);
            assertEquals(own.min().y(), box.min().y(), "copies keep the dab's height");
        }
    }

    @Test
    void aShapeOnTheMirrorPlaneKeepsItsTurnedTwin() {
        // On the plane x = 20 (edge): the east and west cube boxes coincide, so they are one placement.
        BrushSpec cubes = cube(2, 6, Facing.EAST).withSymmetry(new Symmetry(Symmetry.Mode.MIRROR_X, 40, 0));
        Dab onPlane = new Dab(0, 16 * 20, 16 * 64 + 8, 16 * 7 + 8, 255);
        assertEquals(1, ShapeStamp.placements(cubes, onPlane).size());
        // A cone there faces both ways.
        BrushSpec cones = BrushSpec.shape(2, ShapeSpec.solid(ShapeSpec.Kind.CONE, 6, Facing.EAST),
                new Pattern.Single(1), 0L, null, new Symmetry(Symmetry.Mode.MIRROR_X, 40, 0));
        List<ShapeStamp.Placement> twins = ShapeStamp.placements(cones, onPlane);
        assertEquals(2, twins.size());
        assertEquals(Facing.EAST, twins.get(0).facing());
        assertEquals(Facing.WEST, twins.get(1).facing());
        assertEquals(twins.get(0).box(), twins.get(1).box());
        assertEquals(1, cones.symmetry().copies(onPlane).size(), "the dab alone is listed");
        // Spheres and cubes carry no facing.
        assertEquals(Facing.UP, new ShapeStamp.Placement(own(), ShapeSpec.Kind.SPHERE, Facing.WEST).facing());
        assertEquals(Facing.UP, new ShapeStamp.Placement(own(), ShapeSpec.Kind.CUBE, Facing.NORTH).facing());
    }

    private static Box own() {
        return Box.of(BlockPos.ORIGIN);
    }

    // ---- Kernel ----

    private FakeWorld floor() {
        FakeWorld world = new FakeWorld(states);
        world.fill(new Box(new BlockPos(-24, 50, -24), new BlockPos(24, 59, 24)), dirt);
        world.fill(new Box(new BlockPos(-24, 60, -24), new BlockPos(24, 60, 24)), grass);
        return world;
    }

    @Test
    void placeWritesEveryCellOfTheShapeThatChanges() {
        FakeWorld world = floor();
        BrushSpec spec = cube(2, 7, Facing.UP);
        Box box = ShapeStamp.placement(spec, at(3, 61, -2)).box();
        assertEquals(new Box(new BlockPos(1, 58, -4), new BlockPos(5, 64, 0)), box);
        world.set(3, 62, -2, stone); // already the material: no write
        List<Write> writes = stroke(spec, world, at(3, 61, -2));
        Set<BlockPos> expected = cells(box);
        expected.remove(new BlockPos(3, 62, -2));
        assertEquals(expected, cells(writes));
        for (Write w : writes) assertEquals(stone, w.state());
        assertTrue(stroke(spec, world, at(3, 61, -2)).isEmpty(), "a second dab changes nothing");
    }

    @Test
    void modesChooseTheCells() {
        BrushSpec place = cube(1, 3, Facing.UP);
        Dab dab = at(0, 60, 0);
        Box box = ShapeStamp.placement(place, dab).box();
        FakeWorld world = floor();
        world.set(0, 61, 0, shortGrass);
        world.set(1, 61, 0, water);
        world.set(-1, 61, 0, chest);
        world.set(1, 61, 1, stone);
        Set<BlockPos> open = new HashSet<>();
        Set<BlockPos> solid = new HashSet<>();
        for (BlockPos pos : cells(box)) {
            int state = world.get(pos.x(), pos.y(), pos.z());
            (state == air || state == shortGrass || state == water ? open : solid).add(pos);
        }
        Set<BlockPos> changedSolid = new HashSet<>(solid);
        changedSolid.remove(new BlockPos(1, 61, 1)); // stone already
        Pattern material = new Pattern.Single(stone);
        assertEquals(open, cells(apply(cube(1, 3, Facing.UP, ShapeSpec.Mode.PLACE_IN_AIR, 0, material), world, dab)),
                "place in air: air, plants and fluids only");
        assertEquals(changedSolid, cells(apply(cube(1, 3, Facing.UP, ShapeSpec.Mode.PAINT, 0, material), world, dab)),
                "paint: the other cells, the chest included");
        Set<BlockPos> both = new HashSet<>(open);
        both.addAll(changedSolid);
        assertEquals(both, cells(apply(place, world, dab)), "place is both");
        List<Write> carved = apply(cube(1, 3, Facing.UP, ShapeSpec.Mode.CARVE, 0, null), world, dab);
        Set<BlockPos> notAir = new HashSet<>(solid);
        notAir.add(new BlockPos(0, 61, 0));
        notAir.add(new BlockPos(1, 61, 0));
        assertEquals(notAir, cells(carved), "carve: everything that isn't air");
        for (Write w : carved) assertEquals(air, w.state());
    }

    @Test
    void hollowKeepsTheShellOfTheGivenThickness() {
        FakeWorld world = new FakeWorld(states);
        Dab dab = at(0, 100, 0);
        Box box = ShapeStamp.placement(cube(2, 5, Facing.UP), dab).box();
        for (int t = 1; t <= 3; t++) {
            BrushSpec spec = cube(2, 5, Facing.UP, ShapeSpec.Mode.PLACE, t, new Pattern.Single(stone));
            Set<BlockPos> expected = new HashSet<>();
            for (BlockPos pos : cells(box)) {
                int inside = Math.min(Math.min(Math.min(pos.x() - box.min().x(), box.max().x() - pos.x()),
                        Math.min(pos.y() - box.min().y(), box.max().y() - pos.y())),
                        Math.min(pos.z() - box.min().z(), box.max().z() - pos.z()));
                if (inside < t) expected.add(pos);
            }
            assertEquals(expected, cells(apply(spec, world, dab)), "thickness " + t);
            assertEquals(expected.size(), ShapeStamp.cellCount(spec));
        }
        assertEquals(125 - 27, ShapeStamp.cellCount(cube(2, 5, Facing.UP, ShapeSpec.Mode.PLACE, 1,
                new Pattern.Single(stone))));
        assertEquals(125, ShapeStamp.cellCount(cube(2, 5, Facing.UP)));
        // A flat box (5 x 2 x 5) is all shell.
        assertEquals(50, ShapeStamp.cellCount(cube(2, 2, Facing.UP, ShapeSpec.Mode.PLACE, 1, new Pattern.Single(stone))));
    }

    @Test
    void theClipBoxTheBuildHeightAndUnloadedChunksLimitTheWrites() {
        Dab dab = at(7, 100, 7);
        BrushSpec spec = cube(3, 7, Facing.UP);
        Box box = ShapeStamp.placement(spec, dab).box();
        Box clip = new Box(new BlockPos(5, 99, 0), new BlockPos(20, 200, 8));
        Set<BlockPos> inside = new HashSet<>();
        for (BlockPos pos : cells(box)) {
            if (clip.contains(pos)) inside.add(pos);
        }
        assertEquals(inside, cells(apply(spec.withClip(clip), new FakeWorld(states), dab)));
        assertTrue(apply(spec.withClip(Box.of(new BlockPos(100, 100, 100))), new FakeWorld(states), dab).isEmpty());

        // A world 96-101 tall keeps the cells inside it (the box is 97-103).
        FakeWorld low = new FakeWorld(states, 96, 102);
        Set<BlockPos> within = new HashSet<>();
        for (BlockPos pos : cells(box)) {
            if (pos.y() < 102) within.add(pos);
        }
        assertEquals(7 * 7 * 5, within.size());
        assertEquals(within, cells(apply(spec, low, dab)));

        // Across a chunk edge (cells x 12-18) with chunk (0, 0) not loaded: only x >= 16 is read and written.
        FakeWorld partly = new FakeWorld(states);
        partly.setLoaded(0, 0, false); // FakeWorld throws if an unloaded chunk is read
        Dab edge = at(15, 100, 7);
        Set<BlockPos> loaded = new HashSet<>();
        for (BlockPos pos : cells(ShapeStamp.placement(spec, edge).box())) {
            if (pos.x() >= 16) loaded.add(pos);
        }
        assertEquals(loaded, cells(apply(spec, partly, edge)));
    }

    /**
     * A Waterlog material writes exactly the cells the pattern changes: the cube's air becomes water, its dry stairs
     * are waterlogged, its stone and already wet stairs are not written; a Waterlog of a non-fluid is refused.
     */
    @Test
    void waterlogMaterialWritesOnlyWhatItChanges() {
        int water = states.state("minecraft:water[level=0]");
        int dryStairs = states.state("minecraft:oak_stairs[facing=south]");
        int wetStairs = states.state("minecraft:oak_stairs[facing=south,waterlogged=true]");
        FakeWorld world = new FakeWorld(states);
        world.set(0, 100, 0, dryStairs);
        world.set(1, 100, 0, stone);
        world.set(0, 101, 0, wetStairs);
        Pattern waterlog = new Pattern.Waterlog(water);
        BrushSpec spec = cube(1, 3, Facing.UP, ShapeSpec.Mode.PLACE, 0, waterlog);
        Dab dab = at(0, 100, 0);
        Box box = ShapeStamp.placement(spec, dab).box();
        Set<Write> expected = new HashSet<>();
        for (BlockPos pos : cells(box)) {
            int before = world.get(pos.x(), pos.y(), pos.z());
            int after = waterlog.apply(states, pos.x(), pos.y(), pos.z(), before);
            if (after != before) expected.add(new Write(pos.x(), pos.y(), pos.z(), after));
        }
        assertEquals(27 - 2, expected.size(), "everything but the stone and the wet stairs changes");
        assertTrue(expected.contains(new Write(0, 100, 0, wetStairs)));
        assertEquals(expected, new HashSet<>(apply(spec, world, dab)));
        BrushSpec bad = cube(1, 3, Facing.UP, ShapeSpec.Mode.PLACE, 0, new Pattern.Waterlog(stone));
        assertThrows(IllegalArgumentException.class, () -> apply(bad, world, dab), "not a fluid source");

        // A chest inside the cube is waterlogged with its items carried to the sink; the stairs carry no tile.
        int dryChest = states.state("minecraft:chest[facing=north]");
        int wetChest = states.state("minecraft:chest[facing=north,waterlogged=true]");
        world.set(1, 101, 1, dryChest);
        BlockEntityData items = BlockEntityNbt.toNbtBytes(
                "minecraft:chest", NbtCompound.builder().putInt("Test", 7).build());
        world.setTile(1, 101, 1, items);
        List<Object[]> writes = new ArrayList<>();
        BrushKernels.forTool(BrushTool.SHAPE).applyStep(spec, List.of(dab), new StrokeState(), world, new CellSink() {
            @Override
            public void set(int x, int y, int z, int h) {
                writes.add(new Object[] {x, y, z, h, null});
            }

            @Override
            public void set(int x, int y, int z, int h, BlockEntityData tile) {
                writes.add(new Object[] {x, y, z, h, tile});
            }
        });
        boolean chestSeen = false;
        for (Object[] w : writes) {
            if ((int) w[0] == 1 && (int) w[1] == 101 && (int) w[2] == 1) {
                chestSeen = true;
                assertEquals(wetChest, w[3]);
                assertTrue(items.sameContent((BlockEntityData) w[4]), "the chest's tile travels");
            } else if ((int) w[0] == 0 && (int) w[1] == 100 && (int) w[2] == 0) {
                assertEquals(wetStairs, w[3]);
                assertEquals(null, w[4], "a stair has no block entity to carry");
            }
        }
        assertTrue(chestSeen);
    }

    @Test
    void weightedMaterialPicksByPositionTheSameEveryTime() {
        Pattern.Weighted mix = new Pattern.Weighted(new int[] {stone, dirt, grass}, new int[] {5, 3, 1}, 99L);
        BrushSpec spec = cube(3, 7, Facing.UP, ShapeSpec.Mode.PLACE, 0, mix);
        List<Write> first = apply(spec, new FakeWorld(states), at(0, 100, 0));
        List<Write> second = apply(spec, new FakeWorld(states), at(0, 100, 0));
        assertEquals(first, second, "the same cells, blocks and order");
        Set<Integer> used = new HashSet<>();
        for (Write w : first) {
            assertEquals(mix.apply(states, w.x(), w.y(), w.z(), air), w.state());
            used.add(w.state());
        }
        assertEquals(3, used.size());
        BrushSpec reseeded = cube(3, 7, Facing.UP, ShapeSpec.Mode.PLACE, 0,
                new Pattern.Weighted(new int[] {stone, dirt, grass}, new int[] {5, 3, 1}, 100L));
        assertNotEquals(first, apply(reseeded, new FakeWorld(states), at(0, 100, 0)));
    }

    @Test
    void symmetricCopiesAreMirroredAndOverlapsWrittenOnce() {
        // Mirror across x = 0.5 (block 0's centre) with a cube around x 0: the copies overlap.
        BrushSpec spec = cube(2, 5, Facing.EAST).withSymmetry(new Symmetry(Symmetry.Mode.MIRROR_X, 1, 0));
        Dab dab = at(2, 100, 0);
        List<Write> writes = stroke(spec, new FakeWorld(states), dab);
        Set<BlockPos> expected = cells(new Box(new BlockPos(0, 98, -2), new BlockPos(4, 102, 2)));
        expected.addAll(cells(new Box(new BlockPos(-4, 98, -2), new BlockPos(0, 102, 2))));
        assertEquals(expected, cells(writes), "the union, each cell once");
        // The server's step lists the copies too; they add nothing.
        assertEquals(writes, apply(spec, new FakeWorld(states), spec.symmetry().copies(dab).toArray(new Dab[0])));
        // Rotate 4 around a far centre: four disjoint cubes, each the turned box.
        BrushSpec turned = cube(1, 4, Facing.EAST).withSymmetry(new Symmetry(Symmetry.Mode.ROTATE_4, 41, 41));
        List<ShapeStamp.Placement> placements = ShapeStamp.placements(turned, at(5, 100, 3));
        Set<BlockPos> all = new HashSet<>();
        for (ShapeStamp.Placement placement : placements) all.addAll(cells(placement.box()));
        assertEquals(4 * 3 * 3 * 4, all.size());
        assertEquals(all, cells(stroke(turned, new FakeWorld(states), at(5, 100, 3))));
    }

    @Test
    void stepsAreCheckedAndDeterministic() {
        BrushSpec spec = cube(2, 5, Facing.UP).withSymmetry(new Symmetry(Symmetry.Mode.ROTATE_2, 1, 1));
        BrushKernel kernel = BrushKernels.forTool(BrushTool.SHAPE);
        FakeWorld world = new FakeWorld(states);
        CellSink none = (x, y, z, h) -> {};
        assertThrows(IllegalArgumentException.class, () -> kernel.applyStep(spec, List.of(at(3, 100, 3), at(30, 100, 3)),
                new StrokeState(), world, none), "a dab that is no copy of the first");
        List<Dab> copies = spec.symmetry().copies(at(3, 100, 3));
        assertEquals(2, copies.size());
        // The step's order says which dab was laid: with quarter turns, the dab first and its copies in turn order.
        BrushSpec turned = spec.withSymmetry(new Symmetry(Symmetry.Mode.ROTATE_4, 1, 1));
        List<Dab> four = turned.symmetry().copies(at(3, 100, 3));
        assertDoesNotThrow(() -> kernel.applyStep(turned, four, new StrokeState(), world, none));
        assertThrows(IllegalArgumentException.class, () -> kernel.applyStep(turned, List.of(four.get(0), four.get(2),
                four.get(1), four.get(3)), new StrokeState(), world, none), "copies out of turn order");
        assertThrows(IllegalArgumentException.class, () -> kernel.applyStep(turned, List.of(four.get(3), four.get(2),
                four.get(1), four.get(0)), new StrokeState(), world, none), "turned the other way");
        assertThrows(IllegalArgumentException.class, () -> kernel.applyStep(spec, List.of(copies.get(0)), new StrokeState(),
                world, none), "the dab without its copy");
        assertThrows(IllegalArgumentException.class, () -> kernel.applyStep(spec, List.of(), new StrokeState(), world, none));
        assertThrows(IllegalArgumentException.class, () -> kernel.applyStep(spec,
                List.of(at(3, 100, 3), at(3, 100, 3), at(3, 100, 3), at(3, 100, 3), at(3, 100, 3)), new StrokeState(), world,
                none));
        assertThrows(IllegalArgumentException.class, () -> kernel.applyStep(spec,
                List.of(at(TerrainKernel.MAX_HORIZONTAL + 1, 100, 0)), new StrokeState(), world, none));
        BrushSpec raise = new BrushSpec(BrushTool.RAISE, 2, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0,
                0L);
        assertThrows(IllegalArgumentException.class, () -> kernel.apply(raise, at(0, 100, 0), new StrokeState(), world, none));
        assertThrows(IllegalArgumentException.class, () -> BrushKernels.forTool(BrushTool.RAISE).apply(spec, at(0, 100, 0),
                new StrokeState(), world, none));
        BrushSpec unknownBlock = cube(1, 3, Facing.UP, ShapeSpec.Mode.PLACE, 0, new Pattern.Single(states.size()));
        assertThrows(IllegalArgumentException.class, () -> kernel.apply(unknownBlock, at(0, 100, 0), new StrokeState(),
                world, none));
        assertEquals(apply(spec, floor(), copies.toArray(new Dab[0])), apply(spec, floor(), copies.toArray(new Dab[0])));
    }

    @Test
    void aStepInPartsWritesWhatItWritesInOneGo() {
        // A radius-32 cube with four copies: 4 x 274,625 cells, each placement 65 layers of 4,225 cells, 3 layers a part.
        BrushSpec spec = cube(32, 65, Facing.UP).withSymmetry(new Symmetry(Symmetry.Mode.ROTATE_4, 1, 1));
        Dab dab = at(60, 100, 60);
        List<Dab> step = spec.symmetry().copies(dab);
        List<Write> whole = apply(spec, new FakeWorld(states), step.toArray(new Dab[0]));
        ShapeStep parts = ShapeStep.of(spec, step, new StrokeState(), new FakeWorld(states));
        assertEquals(4L * 65 * 65 * 65, parts.cells());
        assertEquals(88, parts.parts(), "22 parts per placement");
        List<Write> sliced = new ArrayList<>();
        Set<Box> areas = new HashSet<>();
        while (!parts.done()) {
            assertTrue(parts.nextCells() <= ShapeStep.PART_CELLS, "a part holds at most " + ShapeStep.PART_CELLS);
            Box area = parts.nextArea();
            assertTrue(areas.add(area), "each part its own area");
            int before = sliced.size();
            parts.runNext((x, y, z, h) -> sliced.add(new Write(x, y, z, h)));
            for (Write w : sliced.subList(before, sliced.size())) {
                assertTrue(area.contains(w.x(), w.y(), w.z()), "a part writes inside its area: " + w);
            }
        }
        assertEquals(whole, sliced, "the same cells in the same order");
        assertThrows(IllegalStateException.class, () -> parts.runNext((x, y, z, h) -> {}));

        // Hollow, clipped and at the top of the world: the parts cover only what is written.
        BrushSpec shell = cube(20, 41, Facing.EAST, ShapeSpec.Mode.PLACE, 2, new Pattern.Single(stone))
                .withClip(new Box(new BlockPos(-100, 290, -100), new BlockPos(100, 400, 100)));
        ShapeStep top = ShapeStep.of(shell, List.of(at(0, 300, 0)), new StrokeState(), new FakeWorld(states));
        List<Write> parted = new ArrayList<>();
        while (!top.done()) top.runNext((x, y, z, h) -> parted.add(new Write(x, y, z, h)));
        assertEquals(apply(shell, new FakeWorld(states), at(0, 300, 0)), parted);
        for (Write w : parted) assertTrue(w.y() >= 290 && w.y() <= 319, w.toString());
        // A small step is one part per placement; a part holds a layer of the widest shape.
        assertEquals(1, ShapeStep.of(cube(4, 9, Facing.UP), List.of(at(0, 100, 0)), new StrokeState(),
                new FakeWorld(states)).parts());
        assertTrue(ShapeStep.PART_CELLS >= 65 * 65, "a part holds a layer of the widest shape");
    }

    /**
     * One measure of a step's size: the cells it reads, which is the shell's for a hollow shape. A hollow radius-32
     * sphere (predicted by the client: its shell is under the cap) counts its shell, as the client and the server's work
     * units do, and its parts are sized by it, not by the solid sphere's cells.
     */
    @Test
    void aHollowStepIsSizedByItsShell() {
        BrushSpec hollow = BrushSpec.shape(32, new ShapeSpec(ShapeSpec.Kind.SPHERE, 65, Facing.UP, ShapeSpec.Mode.PLACE, 1),
                new Pattern.Single(stone), 3L, null, Symmetry.NONE);
        ShapeStep step = ShapeStep.of(hollow, List.of(at(0, 100, 0)), new StrokeState(), new FakeWorld(states));
        long shell = ShapeStamp.cellCount(hollow);
        assertEquals(shell, step.cells(), "the step's cells are its shell's");
        assertTrue(shell < 32_768 && shell > 10_000, "the shell: " + shell);
        // The solid sphere's 137,000-odd cells would make nine parts.
        assertTrue(step.parts() <= 2, "sized by the shell: " + step.parts() + " parts");
        assertEquals((shell + ShapeStep.PART_CELLS - 1) / ShapeStep.PART_CELLS, ShapeStamp.units(1, shell));
        List<Write> parted = new ArrayList<>();
        while (!step.done()) step.runNext((x, y, z, h) -> parted.add(new Write(x, y, z, h)));
        assertEquals(apply(hollow, new FakeWorld(states), at(0, 100, 0)), parted);
        assertEquals(shell, parted.size(), "every shell cell written into the air");
    }

    @Test
    void unitsCountPlacementsAndTheirSize() {
        assertEquals(1, ShapeStamp.units(1, 1));
        assertEquals(1, ShapeStamp.units(1, ShapeStamp.WORK_UNIT_CELLS));
        assertEquals(2, ShapeStamp.units(1, ShapeStamp.WORK_UNIT_CELLS + 1));
        assertEquals(ShapeStep.PART_CELLS, ShapeStamp.WORK_UNIT_CELLS, "a unit is a part's worth of lane time");
        assertEquals(68, ShapeStamp.units(4, 65L * 65 * 65), "the largest step: four placements of 17 units");
        assertEquals(4, ShapeStamp.units(4, 100), "a cone on the rotation centre: four placements, not one position");
        assertEquals(4L * 65 * 65 * 65, ShapeStamp.MAX_STEP_CELLS);
        // The count does not depend on the facing.
        for (ShapeSpec.Kind kind : ShapeSpec.Kind.values()) {
            long up = ShapeStamp.cellCount(new ShapeSpec(kind, 7, Facing.UP, ShapeSpec.Mode.PLACE, 1), 3);
            for (Facing facing : Facing.values()) {
                ShapeSpec turned = new ShapeSpec(kind, 7, facing, ShapeSpec.Mode.PLACE, 1);
                assertEquals(up, ShapeStamp.cells(ShapeStamp.placement(BrushSpec.shape(3, turned, new Pattern.Single(1), 0L,
                        null, Symmetry.NONE), at(0, 0, 0)), 1).count(), kind + " " + facing);
            }
        }
    }

    @Test
    void workIsBoundedByTheBox() {
        int[] reads = new int[1];
        FakeWorld world = new FakeWorld(states);
        WorldReader counting = new WorldReader() {
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
                return true;
            }

            @Override
            public int get(int x, int y, int z) {
                reads[0]++;
                return world.get(x, y, z);
            }

            @Override
            public BlockEntityData tile(int x, int y, int z) {
                throw new AssertionError("no tiles are read");
            }

            @Override
            public void copySection(int sx, int sy, int sz, dev.sculptory.core.buffer.SectionBuffer into) {
                throw new AssertionError("no sections are read");
            }
        };
        BrushSpec biggest = cube(32, 65, Facing.UP);
        List<Write> writes = apply(biggest, counting, at(0, 100, 0));
        assertEquals(65L * 65 * 65, reads[0], "one read per cell");
        assertEquals(65 * 65 * 65, writes.size());
        reads[0] = 0;
        BrushSpec hollow = cube(32, 65, Facing.UP, ShapeSpec.Mode.PLACE, 1, new Pattern.Single(stone));
        assertEquals(ShapeStamp.cellCount(hollow), apply(hollow, counting, at(0, 100, 0)).size());
        assertEquals(ShapeStamp.cellCount(hollow), reads[0], "the inside of a hollow shape is not read");
        assertFalse(ShapeStamp.cellCount(hollow) > 6L * 65 * 65);
    }
}
