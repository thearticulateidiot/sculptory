package dev.sculptory.fabric.client.editor.tools.select;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.ShapeSpec;
import dev.sculptory.core.brush.ShapeStamp;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BrushSelectTest {
    private static final long NO_CAP = SelectSettings.MAX_CELLS;

    private final FakeStateSpace states = new FakeStateSpace();
    private final FakeWorld world = new FakeWorld(states);
    private final int stone = states.state("minecraft:stone");

    private BrushSelect brush(int radius, boolean solidOnly, BrushSelect.Combine combine, long cap) {
        return new BrushSelect(world, radius, solidOnly, combine, cap);
    }

    private BrushSelect started(int radius, boolean solidOnly, BrushSelect.Combine combine, long cap, CellSet base) {
        BrushSelect brush = brush(radius, solidOnly, combine, cap);
        brush.setBase(base);
        return brush;
    }

    /** Every cell of a sphere, from the Shape brush's own stamp. */
    private static Set<BlockPos> shapeBrushSphere(BlockPos centre, int radius) {
        ShapeSpec sphere = ShapeSpec.solid(ShapeSpec.Kind.SPHERE, 1, Facing.UP);
        Box box = ShapeStamp.box(sphere, radius, Facing.UP, 16L * centre.x() + 8, 16L * centre.y() + 8, 16L * centre.z() + 8);
        ShapeStamp.Cells cells = ShapeStamp.cells(new ShapeStamp.Placement(box, ShapeSpec.Kind.SPHERE, Facing.UP), 0);
        Set<BlockPos> set = new HashSet<>();
        for (int x = box.min().x() - 1; x <= box.max().x() + 1; x++) {
            for (int y = box.min().y() - 1; y <= box.max().y() + 1; y++) {
                for (int z = box.min().z() - 1; z <= box.max().z() + 1; z++) {
                    if (cells.contains(x, y, z)) set.add(new BlockPos(x, y, z));
                }
            }
        }
        assertEquals(cells.count(), set.size());
        return set;
    }

    private static double distance(BlockPos cell, double x, double y, double z) {
        double dx = cell.x() + 0.5 - x, dy = cell.y() + 0.5 - y, dz = cell.z() + 0.5 - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Test
    void aSampleIsExactlyTheShapeBrushsSphereAndASelectionSphereOfTheSameBox() {
        // Negative coordinates, and centres that put the sphere across section borders.
        for (BlockPos centre : List.of(new BlockPos(-7, -3, 15), new BlockPos(0, 0, 0), new BlockPos(16, 64, -16))) {
            for (int radius : new int[] {1, 2, 3, 5, 8, 13}) {
                BrushSelect brush = started(radius, false, BrushSelect.Combine.ADD, NO_CAP, CellSet.empty());
                brush.sample(centre.x(), centre.y(), centre.z());
                Set<BlockPos> cells = CellSets.cells(brush.result());
                Set<BlockPos> expected = shapeBrushSphere(centre, radius);
                assertEquals(expected, cells, "radius " + radius + " at " + centre);

                Region.Shape sphere = BrushSelect.sphere(centre, radius);
                assertEquals(new Box(centre.offset(-radius, -radius, -radius), centre.offset(radius, radius, radius)),
                        sphere.box(), "a box of diameter 2r + 1 centred on the block");
                assertEquals(ShapeKind.ELLIPSOID, sphere.kind());
                assertEquals(expected, CellSets.cells(sphere), "the selection sphere of that box");

                // Sanity against the Euclidean ball: everything within r, nothing beyond r + 0.5 of the centre.
                double cx = centre.x() + 0.5, cy = centre.y() + 0.5, cz = centre.z() + 0.5;
                for (BlockPos cell : cells) assertTrue(distance(cell, cx, cy, cz) <= radius + 0.5, cell.toString());
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dy = -radius; dy <= radius; dy++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            BlockPos cell = centre.offset(dx, dy, dz);
                            if (distance(cell, cx, cy, cz) <= radius) assertTrue(cells.contains(cell), cell.toString());
                        }
                    }
                }
            }
        }
    }

    @Test
    void solidOnlyAddsOnlyBlocksAndNeverReadsAnUnloadedChunk() {
        // Stone along x through the border into chunk 1, which the client doesn't have: reading it would throw.
        for (int x = 8; x < 24; x++) world.set(x, 60, 5, stone);
        world.set(14, 61, 5, stone);
        world.setLoaded(1, 0, false);

        BrushSelect brush = started(3, true, BrushSelect.Combine.ADD, NO_CAP, CellSet.empty());
        brush.sample(14, 60, 5);

        Set<BlockPos> cells = CellSets.cells(brush.result());
        Set<BlockPos> expected = new HashSet<>();
        for (int x = 11; x <= 15; x++) expected.add(new BlockPos(x, 60, 5)); // the sphere's row, up to the border
        expected.add(new BlockPos(14, 61, 5));
        assertEquals(expected, cells);
        assertTrue(brush.hitUnloaded());
        assertFalse(brush.hitLimit());

        BrushSelect loadedOnly = started(3, true, BrushSelect.Combine.ADD, NO_CAP, CellSet.empty());
        loadedOnly.sample(2, 60, 5);
        assertFalse(loadedOnly.hitUnloaded(), "a sphere within loaded chunks skips nothing");
        assertTrue(loadedOnly.result().isEmpty(), "air is never added");
    }

    @Test
    void withoutSolidOnlyEveryCellOfTheSphereIsAddedLoadedOrNot() {
        world.setLoaded(1, 0, false);
        BrushSelect brush = started(3, false, BrushSelect.Combine.ADD, NO_CAP, CellSet.empty());
        brush.sample(15, 60, 5);
        assertEquals(shapeBrushSphere(new BlockPos(15, 60, 5), 3), CellSets.cells(brush.result()));
        assertFalse(brush.hitUnloaded());
    }

    @Test
    void theBuildHeightBoundsTheSphere() {
        BrushSelect top = started(4, false, BrushSelect.Combine.ADD, NO_CAP, CellSet.empty());
        top.sample(0, 318, 0);
        for (BlockPos cell : CellSets.cells(top.result())) assertTrue(cell.y() < 320, cell.toString());
        assertTrue(top.count() > 0);

        BrushSelect bottom = started(4, false, BrushSelect.Combine.ADD, NO_CAP, CellSet.empty());
        bottom.sample(0, -63, 0);
        for (BlockPos cell : CellSets.cells(bottom.result())) assertTrue(cell.y() >= -64, cell.toString());
    }

    @Test
    void addJoinsTheBaseAndRemoveLeavesIt() {
        CellSet base = CellSet.of(new Region.Cuboid(new Box(new BlockPos(0, 0, 0), new BlockPos(9, 9, 9))), NO_CAP);
        BrushSelect add = started(2, false, BrushSelect.Combine.ADD, NO_CAP, base);
        add.sample(20, 5, 5);
        Set<BlockPos> expected = CellSets.cells(base);
        expected.addAll(shapeBrushSphere(new BlockPos(20, 5, 5), 2));
        assertEquals(expected, CellSets.cells(add.result()));

        BrushSelect remove = started(2, false, BrushSelect.Combine.REMOVE, NO_CAP, base);
        remove.sample(5, 5, 5);
        expected = CellSets.cells(base);
        expected.removeAll(shapeBrushSphere(new BlockPos(5, 5, 5), 2));
        assertEquals(expected, CellSets.cells(remove.result()));
        assertEquals(1000 - shapeBrushSphere(new BlockPos(5, 5, 5), 2).size(), remove.count());

        BrushSelect all = started(10, false, BrushSelect.Combine.REMOVE, NO_CAP, base); // reaches the far corners
        all.sample(5, 5, 5);
        assertTrue(all.result().isEmpty(), "removing everything leaves an empty result");
    }

    @Test
    void changedIsClearedByTakingTheResult() {
        BrushSelect brush = started(2, false, BrushSelect.Combine.ADD, NO_CAP, CellSet.empty());
        assertTrue(brush.changed(), "a base to show");
        brush.result();
        assertFalse(brush.changed());
        brush.sample(0, 0, 0);
        assertTrue(brush.changed());
        brush.result();
        brush.sample(0, 0, 0);
        assertFalse(brush.changed(), "the same block again paints nothing");
    }

    @Test
    void aFastDragLeavesNoGaps() {
        int radius = 4;
        BrushSelect brush = started(radius, false, BrushSelect.Combine.ADD, NO_CAP, CellSet.empty());
        brush.dragTo(0, 10, 0);
        brush.dragTo(100, 10, 0); // one event for a hundred blocks
        assertTube(CellSets.cells(brush.result()), 0.5, 10.5, 0.5, 100.5, 10.5, 0.5, radius - 1);

        BrushSelect diagonal = started(radius, false, BrushSelect.Combine.ADD, NO_CAP, CellSet.empty());
        diagonal.dragTo(0, 10, 0);
        diagonal.dragTo(60, 40, 80);
        assertTube(CellSets.cells(diagonal.result()), 0.5, 10.5, 0.5, 60.5, 40.5, 80.5, radius - 1);

        BrushSelect thin = started(1, false, BrushSelect.Combine.ADD, NO_CAP, CellSet.empty());
        thin.dragTo(0, 10, 0);
        thin.dragTo(50, 10, 0);
        Set<BlockPos> line = CellSets.cells(thin.result());
        for (int x = 0; x <= 50; x++) assertTrue(line.contains(new BlockPos(x, 10, 0)), "x " + x);
    }

    /** Every cell whose centre is within {@code reach} of the segment is selected. */
    private static void assertTube(Set<BlockPos> cells, double x1, double y1, double z1, double x2, double y2, double z2,
                                   double reach) {
        double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        double length2 = dx * dx + dy * dy + dz * dz;
        int missing = 0;
        for (int x = (int) Math.floor(Math.min(x1, x2)) - 5; x <= Math.max(x1, x2) + 5; x++) {
            for (int y = (int) Math.floor(Math.min(y1, y2)) - 5; y <= Math.max(y1, y2) + 5; y++) {
                for (int z = (int) Math.floor(Math.min(z1, z2)) - 5; z <= Math.max(z1, z2) + 5; z++) {
                    double px = x + 0.5, py = y + 0.5, pz = z + 0.5;
                    double t = Math.max(0, Math.min(1, ((px - x1) * dx + (py - y1) * dy + (pz - z1) * dz) / length2));
                    double ex = px - (x1 + dx * t), ey = py - (y1 + dy * t), ez = pz - (z1 + dz * t);
                    if (Math.sqrt(ex * ex + ey * ey + ez * ez) <= reach && !cells.contains(new BlockPos(x, y, z))) {
                        missing++;
                    }
                }
            }
        }
        assertEquals(0, missing, "cells within " + reach + " of the drag left unselected");
    }

    @Test
    void theSpacingIsAQuarterOfTheRadiusAtLeastHalfABlock() {
        assertEquals(0.5, BrushSelect.spacing(1));
        assertEquals(0.5, BrushSelect.spacing(2));
        assertEquals(1.0, BrushSelect.spacing(4));
        assertEquals(8.0, BrushSelect.spacing(32));
        BrushSelect brush = brush(4, false, BrushSelect.Combine.ADD, NO_CAP);
        brush.setRadius(9);
        assertEquals(9, brush.radius());
        assertThrows(IllegalArgumentException.class, () -> brush.setRadius(0));
        assertThrows(IllegalArgumentException.class, () -> brush.setRadius(33));
    }

    @Test
    void theCapStopsAddingExactlyThereAndNeverBeyond() {
        BrushSelect brush = started(3, false, BrushSelect.Combine.ADD, 50, CellSet.empty());
        brush.sample(0, 60, 0); // far more than 50 cells in the sphere
        assertEquals(50, brush.count());
        assertTrue(brush.hitLimit());
        brush.dragTo(30, 60, 0);
        assertEquals(50, brush.count(), "nothing more is added");
        assertEquals(50, brush.result().size());

        int one = shapeBrushSphere(new BlockPos(0, 60, 0), 1).size();
        BrushSelect exact = started(1, false, BrushSelect.Combine.ADD, one, CellSet.empty());
        exact.sample(0, 60, 0); // exactly the cap
        assertEquals(one, exact.count());
        assertFalse(exact.hitLimit(), "an exact fit is not cut off");

        BrushSelect remove = started(3, false, BrushSelect.Combine.REMOVE, 50,
                CellSet.of(new Region.Cuboid(new Box(new BlockPos(0, 0, 0), new BlockPos(9, 9, 9))), NO_CAP));
        remove.sample(5, 5, 5);
        assertFalse(remove.hitLimit(), "removing has no cap");
    }

    @Test
    void aLateBaseMergesWhatWasPaintedBeforeIt() {
        CellSet base = CellSet.of(new Region.Cuboid(new Box(new BlockPos(0, 0, 0), new BlockPos(9, 9, 9))), NO_CAP);

        BrushSelect add = brush(2, false, BrushSelect.Combine.ADD, NO_CAP);
        assertFalse(add.baseKnown());
        add.sample(20, 5, 5);
        assertThrows(IllegalStateException.class, add::result);
        int painted = shapeBrushSphere(new BlockPos(20, 5, 5), 2).size();
        assertEquals(painted, add.count(), "the painted cells alone so far");
        add.setBase(base);
        assertTrue(add.baseKnown());
        assertFalse(add.overCap());
        Set<BlockPos> expected = CellSets.cells(base);
        expected.addAll(shapeBrushSphere(new BlockPos(20, 5, 5), 2));
        assertEquals(expected, CellSets.cells(add.result()));
        assertEquals(1000 + painted, add.count());
        assertThrows(IllegalStateException.class, () -> add.setBase(base));

        BrushSelect remove = brush(2, false, BrushSelect.Combine.REMOVE, NO_CAP);
        remove.sample(5, 5, 5);
        remove.setBase(base);
        expected = CellSets.cells(base);
        expected.removeAll(shapeBrushSphere(new BlockPos(5, 5, 5), 2));
        assertEquals(expected, CellSets.cells(remove.result()));

        BrushSelect over = brush(2, false, BrushSelect.Combine.ADD, 1000 + painted - 1);
        over.sample(20, 5, 5);
        over.setBase(base);
        assertTrue(over.overCap(), "the base and the painted cells together are over the cap");
    }

    @Test
    void cancelIsRemembered() {
        BrushSelect brush = brush(2, false, BrushSelect.Combine.ADD, NO_CAP);
        assertFalse(brush.cancelled());
        brush.cancel();
        assertTrue(brush.cancelled());
        assertThrows(IllegalArgumentException.class, () -> brush(2, false, BrushSelect.Combine.ADD, 0));
    }
}
