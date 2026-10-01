package dev.sculptory.core.brush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The Shape brush writes exactly the contract's cells: a {@code Region.Shape} for spheres, cylinders and cones, a
 * {@code Region.Cuboid} for cubes, and with a hollow thickness the regions' shell rule, computed here by brute force
 * over {@code Region.contains}.
 */
class ShapeCellsTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");

    private BrushSpec spec(ShapeSpec.Kind kind, int radius, int height, Facing facing, ShapeSpec.Mode mode, int hollow) {
        return BrushSpec.shape(radius, new ShapeSpec(kind, height, facing, mode, hollow), new Pattern.Single(stone), 3L, null,
                Symmetry.NONE);
    }

    private static Set<BlockPos> written(BrushSpec spec, FakeWorld world, Dab dab) {
        Set<BlockPos> cells = new HashSet<>();
        BrushKernels.forTool(BrushTool.SHAPE).apply(spec, dab, new StrokeState(), world,
                (x, y, z, h) -> assertTrue(cells.add(new BlockPos(x, y, z)), "written twice"));
        return cells;
    }

    /** The region's cells, or with {@code t > 0} its shell: a cell outside lies within t along an axis direction. */
    private static Set<BlockPos> expected(Region region, int t) {
        Set<BlockPos> cells = new HashSet<>();
        Box box = region.bounds();
        int[][] directions = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int x = box.min().x(); x <= box.max().x(); x++) {
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) {
                    if (!region.contains(x, y, z)) continue;
                    boolean keep = t == 0;
                    for (int[] d : directions) {
                        for (int k = 1; k <= t && !keep; k++) {
                            if (!region.contains(x + k * d[0], y + k * d[1], z + k * d[2])) keep = true;
                        }
                    }
                    if (keep) cells.add(new BlockPos(x, y, z));
                }
            }
        }
        return cells;
    }

    /** The box a dab at (x16, y16, z16) gets, from first principles: each side's cells centred nearest the point. */
    private static Box box(int radius, int axial, Facing facing, int x16, int y16, int z16) {
        int[] size = {2 * radius + 1, 2 * radius + 1, 2 * radius + 1};
        size[facing.axis()] = axial;
        int[] point = {x16, y16, z16};
        int[] min = new int[3];
        for (int a = 0; a < 3; a++) {
            // The run of size cells whose centre, min + size / 2, is nearest point / 16 (ties toward positive).
            double centre = point[a] / 16.0;
            min[a] = (int) Math.floor(centre - size[a] / 2.0 + 0.5);
        }
        return new Box(new BlockPos(min[0], min[1], min[2]),
                new BlockPos(min[0] + size[0] - 1, min[1] + size[1] - 1, min[2] + size[2] - 1));
    }

    private static Region region(ShapeSpec.Kind kind, Box box, Facing facing) {
        return switch (kind) {
            case SPHERE -> new Region.Shape(box, ShapeKind.ELLIPSOID, facing);
            case CYLINDER -> new Region.Shape(box, ShapeKind.CYLINDER, facing);
            case CONE -> new Region.Shape(box, ShapeKind.CONE, facing);
            case PYRAMID -> new Region.Shape(box, ShapeKind.PYRAMID, facing);
            case CUBE -> new Region.Cuboid(box);
        };
    }

    @Test
    void everyShapeFacingSizeAndHollowWritesExactlyTheRegionsCells() {
        int checked = 0;
        for (ShapeSpec.Kind kind : ShapeSpec.Kind.values()) {
            for (Facing facing : Facing.values()) {
                for (int radius : new int[] {1, 2, 4}) {
                    for (int height : new int[] {1, 2, 5, 8, 2 * radius + 1}) {
                        for (int hollow : new int[] {0, 1, 2}) {
                            BrushSpec spec = spec(kind, radius, height, facing, ShapeSpec.Mode.PLACE, hollow);
                            int axial = kind == ShapeSpec.Kind.SPHERE ? 2 * radius + 1 : height;
                            // A dab on a block centre (Centre anchor) and one on the block edges (an even height's centre).
                            for (int[] point : new int[][] {{16 * 3 + 8, 16 * 70 + 8, 16 * -5 + 8}, {16 * 3, 16 * 70, 16 * -5},
                                    {16 * 3 + 3, 16 * 70 + 13, 16 * -5 + 8}}) {
                                Dab dab = new Dab(0, point[0], point[1], point[2], Dab.FULL_PRESSURE);
                                Box box = box(radius, axial, kind == ShapeSpec.Kind.SPHERE ? Facing.UP : facing, point[0],
                                        point[1], point[2]);
                                Region region = region(kind, box, kind == ShapeSpec.Kind.SPHERE ? Facing.UP : facing);
                                Set<BlockPos> expected = expected(region, hollow);
                                String what = kind + " " + facing + " r" + radius + " h" + height + " t" + hollow + " "
                                        + box;
                                assertEquals(expected, written(spec, new FakeWorld(states), dab), what);
                                assertEquals(expected.size(), ShapeStamp.cellCount(spec), what);
                                assertEquals(region, ShapeStamp.placement(spec, dab).region(), what);
                                checked++;
                            }
                        }
                    }
                }
            }
        }
        assertEquals(ShapeSpec.Kind.values().length * 6 * 3 * 5 * 3 * 3, checked);
    }

    @Test
    void aBrushSphereIsTheSelectionSphereOfTheSameBox() {
        BrushSpec sphere = spec(ShapeSpec.Kind.SPHERE, 6, 13, Facing.UP, ShapeSpec.Mode.PLACE, 0);
        Dab dab = new Dab(0, 16 * 10 + 8, 16 * 80 + 8, 16 * 10 + 8, 255);
        Region selection = new Region.Shape(new Box(new BlockPos(4, 74, 4), new BlockPos(16, 86, 16)), ShapeKind.ELLIPSOID,
                Facing.UP);
        Set<BlockPos> written = written(sphere, new FakeWorld(states), dab);
        assertEquals(expected(selection, 0), written);
        assertEquals(selection.cellCount(), written.size());
        // It is a sphere: the same in every direction.
        for (BlockPos pos : written) {
            assertTrue(written.contains(new BlockPos(pos.z(), pos.y(), pos.x())));
            assertTrue(written.contains(new BlockPos(pos.y() - 70, pos.x() + 70, pos.z())));
        }
    }

    @Test
    void conesPointWhereTheyFaceAndStandOnTheirBase() {
        // A cone facing up, 5 wide and 6 tall, on the ground: its base layer is the widest, its apex the narrowest.
        BrushSpec up = spec(ShapeSpec.Kind.CONE, 2, 6, Facing.UP, ShapeSpec.Mode.PLACE, 0);
        Set<BlockPos> cells = written(up, new FakeWorld(states), new Dab(0, 8, 16 * 64, 8, 255));
        int bottom = Integer.MAX_VALUE, top = Integer.MIN_VALUE;
        for (BlockPos pos : cells) {
            bottom = Math.min(bottom, pos.y());
            top = Math.max(top, pos.y());
        }
        assertEquals(61, bottom);
        long base = cells.stream().filter(p -> p.y() == 61).count();
        long apex = cells.stream().filter(p -> p.y() == 66).count();
        assertTrue(base > apex, base + " " + apex);
        // Facing down it is the same cone upside down.
        BrushSpec down = spec(ShapeSpec.Kind.CONE, 2, 6, Facing.DOWN, ShapeSpec.Mode.PLACE, 0);
        Set<BlockPos> flipped = written(down, new FakeWorld(states), new Dab(0, 8, 16 * 64, 8, 255));
        for (BlockPos pos : cells) assertTrue(flipped.contains(new BlockPos(pos.x(), 61 + 66 - pos.y(), pos.z())), pos.toString());
        assertEquals(cells.size(), flipped.size());
    }

    @Test
    void symmetricShapesAreExactMirrorAndTurnedImages() {
        Pattern material = new Pattern.Single(stone);
        for (ShapeSpec.Kind kind : ShapeSpec.Kind.values()) {
            // Mirror across x = 20 (a block edge; x2 = 40): cell x -> 39 - x. The dab is off the plane.
            BrushSpec mirrored = BrushSpec.shape(3, new ShapeSpec(kind, 6, Facing.EAST, ShapeSpec.Mode.PLACE, 0), material,
                    0L, null, new Symmetry(Symmetry.Mode.MIRROR_X, 40, 0));
            Dab dab = new Dab(0, 16 * 28, 16 * 70 + 8, 16 * 3 + 8, 255);
            Set<BlockPos> cells = written(mirrored, new FakeWorld(states), dab);
            Set<BlockPos> own = written(mirrored.withSymmetry(Symmetry.NONE), new FakeWorld(states), dab);
            for (BlockPos pos : cells) assertTrue(cells.contains(new BlockPos(39 - pos.x(), pos.y(), pos.z())), kind + " " + pos);
            for (BlockPos pos : own) assertTrue(cells.contains(new BlockPos(39 - pos.x(), pos.y(), pos.z())));
            assertEquals(2 * own.size(), cells.size(), kind + ": disjoint copies");

            // Quarter turns about (0.5, 0.5) (x2 = z2 = 1): (x, z) -> (-z, x) in cells.
            BrushSpec turned = mirrored.withSymmetry(new Symmetry(Symmetry.Mode.ROTATE_4, 1, 1));
            Set<BlockPos> four = written(turned, new FakeWorld(states), dab);
            for (BlockPos pos : four) {
                assertTrue(four.contains(new BlockPos(-pos.z(), pos.y(), pos.x())), kind + " turned " + pos);
            }
            assertEquals(4 * own.size(), four.size(), kind.name());
        }
        // A cone on the mirror plane gets its mirrored twin: a double cone, symmetric about the plane.
        BrushSpec cones = BrushSpec.shape(2, new ShapeSpec(ShapeSpec.Kind.CONE, 6, Facing.EAST, ShapeSpec.Mode.PLACE, 0),
                material, 0L, null, new Symmetry(Symmetry.Mode.MIRROR_X, 40, 0));
        Set<BlockPos> twins = written(cones, new FakeWorld(states), new Dab(0, 16 * 20, 16 * 70 + 8, 8, 255));
        for (BlockPos pos : twins) assertTrue(twins.contains(new BlockPos(39 - pos.x(), pos.y(), pos.z())), pos.toString());
        Set<BlockPos> single = written(cones.withSymmetry(Symmetry.NONE), new FakeWorld(states), new Dab(0, 16 * 20,
                16 * 70 + 8, 8, 255));
        assertTrue(twins.size() > single.size());
        assertTrue(twins.containsAll(single));
    }

    @Test
    void modesOnARealShape() {
        // Half of the sphere's box is dirt (below y 70), half air.
        FakeWorld world = new FakeWorld(states);
        world.fill(new Box(new BlockPos(-10, 60, -10), new BlockPos(10, 69, 10)), dirt);
        Dab dab = new Dab(0, 8, 16 * 70 + 8, 8, 255);
        Set<BlockPos> sphere = written(spec(ShapeSpec.Kind.SPHERE, 4, 9, Facing.UP, ShapeSpec.Mode.PLACE, 0),
                new FakeWorld(states), dab);
        Set<BlockPos> inAir = written(spec(ShapeSpec.Kind.SPHERE, 4, 9, Facing.UP, ShapeSpec.Mode.PLACE_IN_AIR, 0), world, dab);
        Set<BlockPos> painted = written(spec(ShapeSpec.Kind.SPHERE, 4, 9, Facing.UP, ShapeSpec.Mode.PAINT, 0), world, dab);
        Set<BlockPos> carved = written(spec(ShapeSpec.Kind.SPHERE, 4, 9, Facing.UP, ShapeSpec.Mode.CARVE, 0), world, dab);
        for (BlockPos pos : inAir) assertTrue(pos.y() >= 70);
        for (BlockPos pos : painted) assertTrue(pos.y() < 70);
        assertEquals(carved, painted, "carve clears exactly the non-air cells");
        Set<BlockPos> union = new HashSet<>(inAir);
        union.addAll(painted);
        assertEquals(sphere, union);
        assertFalse(inAir.isEmpty() || painted.isEmpty());
    }

    @Test
    void theLargestShapesStayWithinTheirBoxes() {
        for (ShapeSpec.Kind kind : ShapeSpec.Kind.values()) {
            BrushSpec spec = spec(kind, 32, 65, Facing.NORTH, ShapeSpec.Mode.PLACE, 1);
            Dab dab = new Dab(0, 8, 16 * 100 + 8, 8, 255);
            Box reach = ShapeStamp.reachBox(spec, dab, -64, 320);
            Set<BlockPos> cells = written(spec, new FakeWorld(states), dab);
            for (BlockPos pos : cells) assertTrue(reach.contains(pos));
            assertEquals(ShapeStamp.cellCount(spec), cells.size(), kind.name());
        }
        assertEquals(List.of(Facing.UP), List.of(new ShapeStamp.Placement(Box.of(BlockPos.ORIGIN), ShapeSpec.Kind.SPHERE,
                Facing.EAST).facing()));
    }
}
