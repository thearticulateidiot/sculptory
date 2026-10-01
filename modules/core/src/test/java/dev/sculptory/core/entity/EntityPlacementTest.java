package dev.sculptory.core.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.edit.PasteGeometry;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import org.junit.jupiter.api.Test;

class EntityPlacementTest {
    private static final byte[] NO_DATA = {10, 0, 0, 0};

    /** Every point of a cell lands in the cell {@code Transform} maps that cell to, for all 8 transforms. */
    @Test
    void pointsFollowTheirCells() {
        int sx = 5, sz = 3;
        for (Transform t : Transform.all()) {
            for (Mirror mirror : Mirror.values()) {
                Transform full = new Transform(t.quarterTurnsCw(), mirror);
                for (int x = 0; x < sx; x++) {
                    for (int z = 0; z < sz; z++) {
                        int cellX = full.mapX(x, z, sx, sz), cellZ = full.mapZ(x, z, sx, sz);
                        // Points strictly inside the cell (a point on an edge lands on the opposite edge of the mapped
                        // cell when its axis is flipped).
                        for (double fx : new double[] {0.01, 0.25, 0.5, 0.99}) {
                            for (double fz : new double[] {0.01, 0.3, 0.5, 0.75}) {
                                double px = EntityPlacement.mapX(x + fx, z + fz, full, sx, sz);
                                double pz = EntityPlacement.mapZ(x + fx, z + fz, full, sx, sz);
                                String at = full + " at " + (x + fx) + "," + (z + fz);
                                assertEquals(cellX, (int) Math.floor(px), at);
                                assertEquals(cellZ, (int) Math.floor(pz), at);
                            }
                        }
                        assertEquals(cellX + 0.5, EntityPlacement.mapX(x + 0.5, z + 0.5, full, sx, sz), 1e-9);
                        assertEquals(cellZ + 0.5, EntityPlacement.mapZ(x + 0.5, z + 0.5, full, sx, sz), 1e-9);
                    }
                }
            }
        }
    }

    @Test
    void theBoxEdgesMapOntoTheTransformedBox() {
        int sx = 4, sz = 2;
        Transform quarter = Transform.rotation(1);
        // (0, 0) is the north-west corner; a clockwise quarter turn puts it at the north-east corner of the 2x4 box.
        assertEquals(2.0, EntityPlacement.mapX(0, 0, quarter, sx, sz));
        assertEquals(0.0, EntityPlacement.mapZ(0, 0, quarter, sx, sz));
        assertEquals(0.0, EntityPlacement.mapX(4, 2, quarter, sx, sz));
        assertEquals(4.0, EntityPlacement.mapZ(4, 2, quarter, sx, sz));
        Transform mirrored = new Transform(0, Mirror.X);
        assertEquals(3.25, EntityPlacement.mapX(0.75, 1, mirrored, sx, sz));
        assertEquals(1.0, EntityPlacement.mapZ(0.75, 1, mirrored, sx, sz));
    }

    /** Vanilla yaw: 0 south, 90 west, 180 north, -90 (270) east. Mirror first, then turn clockwise. */
    @Test
    void headingsTurnAndMirror() {
        assertEquals(90f, EntityPlacement.yaw(0f, Transform.rotation(1)), "south turns to west");
        assertEquals(-180f, EntityPlacement.yaw(90f, Transform.rotation(1)), "west turns to north");
        assertEquals(-90f, EntityPlacement.yaw(0f, Transform.rotation(3)), "south turns back to east");
        assertEquals(90f, EntityPlacement.yaw(-90f, new Transform(0, Mirror.X)), "mirror X swaps east and west");
        assertEquals(0f, EntityPlacement.yaw(0f, new Transform(0, Mirror.X)), "and keeps south");
        assertEquals(0f, EntityPlacement.yaw(180f, new Transform(0, Mirror.Z)), "mirror Z swaps north and south");
        assertEquals(-90f, EntityPlacement.yaw(-90f, new Transform(0, Mirror.Z)), "and keeps east");
        // Mirror X, then a quarter turn: east → west → north.
        assertEquals(-180f, EntityPlacement.yaw(-90f, new Transform(1, Mirror.X)));
        for (float yaw = -180f; yaw < 180f; yaw += 7.5f) {
            float turned = yaw;
            for (int i = 0; i < 4; i++) turned = EntityPlacement.yaw(turned, Transform.rotation(1));
            assertEquals(EntityPlacement.wrap(yaw), turned, 1e-3, "four turns");
            float mirrored = EntityPlacement.yaw(EntityPlacement.yaw(yaw, new Transform(0, Mirror.Z)),
                    new Transform(0, Mirror.Z));
            assertEquals(EntityPlacement.wrap(yaw), mirrored, 1e-3, "the same mirror twice");
        }
    }

    @Test
    void placesAnEntityInTheWorld() {
        BlockPos size = new BlockPos(4, 3, 2);
        EntitySnapshot frame = new EntitySnapshot("minecraft:item_frame", 1.5, 1.5, 0.03125, 0f, 0f,
                new BlockPos(1, 1, 0), NO_DATA, true);
        BlockPos origin = new BlockPos(100, 64, -20);
        Transform quarter = Transform.rotation(1);
        var target = PasteGeometry.pasteTarget(size, BlockPos.ORIGIN, quarter, origin);
        EntityPlacement.Placed placed = EntityPlacement.place(frame, size, quarter, target.min());
        // Local (1.5, 0.03125) turns to (2 - 0.03125, 1.5) in the 2x4 box; its block (1, 0) to (1, 1).
        assertEquals(target.min().x() + 2 - 0.03125, placed.x(), 1e-9);
        assertEquals(65.5, placed.y(), 1e-9);
        assertEquals(target.min().z() + 1.5, placed.z(), 1e-9);
        assertEquals(new BlockPos(target.min().x() + 1, 65, target.min().z() + 1), placed.attached());
        assertEquals(placed.attached(), placed.cell(), "a hanging entity belongs to its block");

        EntitySnapshot stand = new EntitySnapshot("minecraft:armor_stand", 3.5, 0, 1.5, 45f, 0f, null, NO_DATA, true);
        EntityPlacement.Placed moved = EntityPlacement.place(stand, size, Transform.IDENTITY, new BlockPos(-3, 10, 7));
        assertEquals(0.5, moved.x(), 1e-9);
        assertEquals(10.0, moved.y(), 1e-9);
        assertEquals(8.5, moved.z(), 1e-9);
        assertNull(moved.attached());
        assertEquals(new BlockPos(0, 10, 8), moved.cell());
    }

    /** Flipped upside down, a point's height turns over in the box, an attachment's cell likewise, and the pitch too. */
    @Test
    void theFlipTurnsHeightsAndPitchOver() {
        Transform flip = new Transform(1, Mirror.NONE, true);
        int sy = 4;
        for (int y = 0; y < sy; y++) {
            for (double fy : new double[] {0.01, 0.5, 0.99}) {
                double py = EntityPlacement.mapY(y + fy, flip, sy);
                assertEquals(flip.mapY(y, sy), (int) Math.floor(py), "height " + (y + fy));
            }
        }
        assertEquals(4.0, EntityPlacement.mapY(0, flip, sy));
        assertEquals(1.25, EntityPlacement.mapY(1.25, Transform.rotation(1), sy));
        assertEquals(-30f, EntityPlacement.pitch(30f, flip));
        assertEquals(30f, EntityPlacement.pitch(30f, Transform.rotation(3)));
        assertEquals(1.2, EntityPlacement.flippedFeet(3.0, 1.8), 1e-9);
        EntitySnapshot frame = new EntitySnapshot("minecraft:item_frame", 1.5, 0.03125, 0.5, 0f, -90f,
                new BlockPos(1, 0, 0), NO_DATA, true);
        BlockPos size = new BlockPos(3, sy, 2);
        EntityPlacement.Placed placed = EntityPlacement.place(frame, size, flip, new BlockPos(10, 20, 30));
        // On the floor of the bottom cell, it lands on the ceiling of the top cell.
        assertEquals(new BlockPos(11, 23, 31), placed.attached());
        assertEquals(20 + sy - 0.03125, placed.y(), 1e-9);
        assertEquals(placed.attached(), placed.cell());
    }
}
