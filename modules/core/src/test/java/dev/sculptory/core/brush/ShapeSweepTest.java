package dev.sculptory.core.brush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.GeneratedTooLargeException;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.core.path.PathSpec;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.testing.FakeStateSpace;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The Shape brush's Line: the sweep of each shape, hollow tubes, carving and the cap. */
class ShapeSweepTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final int stone = states.state("minecraft:stone");
    private final Pattern material = new Pattern.Single(stone);

    private static PathSpec path(PathKind kind, int... xyz) {
        List<BlockPos> points = new ArrayList<>();
        for (int i = 0; i < xyz.length; i += 3) points.add(new BlockPos(xyz[i], xyz[i + 1], xyz[i + 2]));
        return new PathSpec(points, kind);
    }

    private static Set<String> cells(GeneratedSource source) {
        Set<String> cells = new HashSet<>();
        source.forEach((x, y, z, state) -> cells.add(x + "," + y + "," + z));
        return cells;
    }

    /** The cells of the shape a dab centred on block (x, y, z) places. */
    private static Set<String> dab(ShapeSpec shape, int radius, int x, int y, int z) {
        Box box = ShapeStamp.box(shape, radius, shape.facing(), 16L * x + 8, 16L * y + 8, 16L * z + 8);
        ShapeStamp.Cells cells = ShapeStamp.cells(new ShapeStamp.Placement(box, shape.kind(), shape.facing()), 0);
        Set<String> out = new HashSet<>();
        for (int cx = box.min().x(); cx <= box.max().x(); cx++) {
            for (int cy = box.min().y(); cy <= box.max().y(); cy++) {
                for (int cz = box.min().z(); cz <= box.max().z(); cz++) {
                    if (cells.contains(cx, cy, cz)) out.add(cx + "," + cy + "," + cz);
                }
            }
        }
        return out;
    }

    @Test
    void aStraightSweepIsTheUnionOfTheBrushsShapesAlongIt() {
        for (ShapeSpec.Kind kind : ShapeSpec.Kind.values()) {
            ShapeSpec shape = new ShapeSpec(kind, 5, Facing.NORTH, ShapeSpec.Mode.PLACE, 0);
            GeneratedSource sweep = ShapeSweep.generate(path(PathKind.STRAIGHT, 0, 64, 0, 12, 64, 0), 3, shape, material,
                    states, 1 << 20);
            Set<String> union = new HashSet<>();
            for (int x = 0; x <= 12; x++) union.addAll(dab(shape, 3, x, 64, 0));
            assertEquals(union, cells(sweep), kind.name());
            sweep.forEach((x, y, z, state) -> assertEquals(stone, state));
        }
    }

    @Test
    void theShapeKeepsItsFacingAlongTheLine() {
        // A flat cylinder (height 1, facing up) swept along x and z stays one layer high.
        ShapeSpec disc = new ShapeSpec(ShapeSpec.Kind.CYLINDER, 1, Facing.UP, ShapeSpec.Mode.PLACE, 0);
        GeneratedSource sweep = ShapeSweep.generate(path(PathKind.CURVE, 0, 70, 0, 10, 70, 5, 20, 70, 0), 2, disc, material,
                states, 1 << 20);
        assertEquals(70, sweep.bounds().min().y());
        assertEquals(70, sweep.bounds().max().y());
    }

    @Test
    void aHollowSweepIsATubeOpenAtBothEnds() {
        ShapeSpec shell = new ShapeSpec(ShapeSpec.Kind.SPHERE, 1, Facing.UP, ShapeSpec.Mode.PLACE, 1);
        GeneratedSource tube = ShapeSweep.generate(path(PathKind.STRAIGHT, 0, 64, 0, 20, 64, 0), 4, shell, material, states,
                1 << 20);
        Set<String> cells = cells(tube);
        // Nothing along the axis, from end to end and past them: the tube is empty inside and open.
        for (int x = -6; x <= 26; x++) assertFalse(cells.contains(x + ",64,0"), "a cell inside at x " + x);
        // Its wall is there all along.
        for (int x = 0; x <= 20; x++) {
            assertTrue(cells.contains(x + ",68,0") && cells.contains(x + ",60,0") && cells.contains(x + ",64,4"),
                    "no wall at x " + x);
            assertFalse(cells.contains(x + ",67,0"), "the wall is thicker than 1 at x " + x);
        }
        // A thicker shell.
        ShapeSpec thick = new ShapeSpec(ShapeSpec.Kind.SPHERE, 1, Facing.UP, ShapeSpec.Mode.PLACE, 2);
        Set<String> thickCells = cells(ShapeSweep.generate(path(PathKind.STRAIGHT, 0, 64, 0, 20, 64, 0), 4, thick, material,
                states, 1 << 20));
        assertTrue(thickCells.contains("10,67,0") && thickCells.contains("10,68,0"));
        assertFalse(thickCells.contains("10,66,0"));
    }

    @Test
    void aHollowClosedCurveIsARing() {
        ShapeSpec shell = new ShapeSpec(ShapeSpec.Kind.SPHERE, 1, Facing.UP, ShapeSpec.Mode.PLACE, 1);
        PathSpec loop = path(PathKind.CURVE, 0, 64, 0, 30, 64, 0, 30, 64, 30, 0, 64, 30, 0, 64, 0);
        Set<String> ring = cells(ShapeSweep.generate(loop, 3, shell, material, states, 1 << 20));
        assertFalse(ring.contains("0,64,0"), "the ring is filled at its start");
        assertTrue(ring.contains("0,67,0"), "no wall over the start");
    }

    @Test
    void carvingWritesAirAndNeedsNoMaterial() {
        ShapeSpec carve = new ShapeSpec(ShapeSpec.Kind.CUBE, 3, Facing.UP, ShapeSpec.Mode.CARVE, 0);
        GeneratedSource cut = ShapeSweep.generate(path(PathKind.HANGING, 0, 64, 0, 10, 64, 0), 1, carve, null, states, 1000);
        assertEquals(13 * 9, cut.cells());
        cut.forEach((x, y, z, state) -> assertEquals(states.air(), state));
        ShapeSpec place = new ShapeSpec(ShapeSpec.Kind.CUBE, 3, Facing.UP, ShapeSpec.Mode.PAINT, 0);
        assertThrows(NullPointerException.class,
                () -> ShapeSweep.generate(path(PathKind.STRAIGHT, 0, 64, 0, 10, 64, 0), 1, place, null, states, 1000));
        assertThrows(IllegalArgumentException.class,
                () -> ShapeSweep.generate(path(PathKind.STRAIGHT, 0, 64, 0, 10, 64, 0), 1, place, new Pattern.Dry(), states, 1000));
    }

    @Test
    void aSweepOverTheCapIsRefused() {
        ShapeSpec cube = new ShapeSpec(ShapeSpec.Kind.CUBE, 3, Facing.UP, ShapeSpec.Mode.PLACE, 0);
        PathSpec line = path(PathKind.STRAIGHT, 0, 64, 0, 10, 64, 0);
        assertEquals(117, ShapeSweep.generate(line, 1, cube, material, states, 117).cells());
        assertThrows(GeneratedTooLargeException.class, () -> ShapeSweep.generate(line, 1, cube, material, states, 116));
        ShapeSpec hollow = new ShapeSpec(ShapeSpec.Kind.SPHERE, 1, Facing.UP, ShapeSpec.Mode.PLACE, 1);
        assertThrows(GeneratedTooLargeException.class,
                () -> ShapeSweep.generate(path(PathKind.STRAIGHT, 0, 64, 0, 400, 64, 0), 32, hollow, material, states, 1000));
        assertThrows(GeneratedTooLargeException.class, () -> ShapeSweep.generate(
                path(PathKind.CURVE, -20_000_000, 64, 0, 20_000_000, 64, 0), 1, cube, material, states, 1_000_000));
    }

    @Test
    void theBuildHeightClipsTheSweep() {
        ShapeSpec cube = new ShapeSpec(ShapeSpec.Kind.CUBE, 3, Facing.UP, ShapeSpec.Mode.PLACE, 0);
        GeneratedSource sweep = ShapeSweep.generate(path(PathKind.STRAIGHT, 0, 319, 0, 10, 319, 0), 1, cube, material, states,
                1000, -64, 320);
        assertEquals(319, sweep.bounds().max().y());
        assertEquals(13 * 3 * 2, sweep.cells());
    }
}
