package dev.sculptory.fabric.client.editor.render.ghost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.client.editor.world.Aabb;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/** The ghost model matrices against {@link Transform}'s cell mapping, for all 8 transforms. */
class GhostMappingTest {
    /** A frame that is not a cube, not at the origin, and spans section boundaries. */
    private static final Box FRAME = new Box(new BlockPos(-5, 60, 13), new BlockPos(17, 64, 19)); // 23 × 5 × 7

    private static List<Transform> everyTransform() {
        List<Transform> all = new ArrayList<>(Transform.all());
        // Mirror.Z is not in Transform.all() (it equals X plus a half turn), but it is a valid input.
        for (int turns = 0; turns < 4; turns++) {
            all.add(new Transform(turns, Mirror.Z));
        }
        // Each also flipped upside down.
        for (Transform transform : List.copyOf(all)) all.add(transform.withUpsideDown(true));
        return all;
    }

    /** The transformed unit cube of local cell (x, y, z), by the section model matrix with the camera at 0. */
    private static float[] matrixCell(GhostMapping mapping, int x, int y, int z) {
        int sectionX = x >> 4;
        int sectionY = y >> 4;
        int sectionZ = z >> 4;
        Matrix4f model = mapping.sectionModel(sectionX, sectionY, sectionZ, 0, 0, 0);
        float[] bounds = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int corner = 0; corner < 8; corner++) {
            // Mesh vertices are relative to the section's minimum corner.
            Vector3f point = new Vector3f(
                    (x & 15) + (corner & 1), (y & 15) + ((corner >> 1) & 1), (z & 15) + ((corner >> 2) & 1));
            model.transformPosition(point);
            bounds[0] = Math.min(bounds[0], point.x);
            bounds[1] = Math.min(bounds[1], point.y);
            bounds[2] = Math.min(bounds[2], point.z);
            bounds[3] = Math.max(bounds[3], point.x);
            bounds[4] = Math.max(bounds[4], point.y);
            bounds[5] = Math.max(bounds[5], point.z);
        }
        return bounds;
    }

    @Test
    void sectionMatricesMoveEveryCellWhereTransformMapsIt() {
        int originX = 1000;
        int originY = 70;
        int originZ = -2000;
        for (Transform transform : everyTransform()) {
            GhostMapping mapping = new GhostMapping(FRAME, transform, originX, originY, originZ);
            for (int x = FRAME.min().x(); x <= FRAME.max().x(); x++) {
                for (int y = FRAME.min().y(); y <= FRAME.max().y(); y++) {
                    for (int z = FRAME.min().z(); z <= FRAME.max().z(); z++) {
                        BlockPos mapped = transform.apply(
                                x - FRAME.min().x(), y - FRAME.min().y(), z - FRAME.min().z(),
                                FRAME.sizeX(), FRAME.sizeY(), FRAME.sizeZ());
                        int wx = originX + mapped.x();
                        int wy = originY + mapped.y();
                        int wz = originZ + mapped.z();
                        String where = transform + " cell " + x + "," + y + "," + z;

                        assertEquals(wx, mapping.worldX(x, z), where);
                        assertEquals(wy, mapping.worldY(y), where);
                        assertEquals(wz, mapping.worldZ(x, z), where);

                        float[] cube = matrixCell(mapping, x, y, z);
                        assertEquals(wx, cube[0], 1e-3, where + " min x");
                        assertEquals(wy, cube[1], 1e-3, where + " min y");
                        assertEquals(wz, cube[2], 1e-3, where + " min z");
                        assertEquals(wx + 1, cube[3], 1e-3, where + " max x");
                        assertEquals(wy + 1, cube[4], 1e-3, where + " max y");
                        assertEquals(wz + 1, cube[5], 1e-3, where + " max z");
                    }
                }
            }
        }
    }

    @Test
    void transformedFrameFillsTheBoxAtTheOrigin() {
        for (Transform transform : everyTransform()) {
            GhostMapping mapping = new GhostMapping(FRAME, transform, 3, 4, 5);
            BlockPos size = transform.size(FRAME.sizeX(), FRAME.sizeY(), FRAME.sizeZ());
            assertEquals(new Aabb(3, 4, 5, 3 + size.x(), 4 + size.y(), 5 + size.z()), mapping.worldBox(FRAME), transform.toString());
        }
    }

    @Test
    void worldBoxesCoverTheirMappedCells() {
        Box local = new Box(new BlockPos(-3, 61, 14), new BlockPos(2, 62, 18));
        for (Transform transform : everyTransform()) {
            GhostMapping mapping = new GhostMapping(FRAME, transform, -7, 0, 9);
            Aabb box = mapping.worldBox(local);
            double volume = box.size(0) * box.size(1) * box.size(2);
            assertEquals(6 * 2 * 5, volume, 1e-9, transform.toString());
            for (int x = local.min().x(); x <= local.max().x(); x++) {
                for (int z = local.min().z(); z <= local.max().z(); z++) {
                    Aabb cell = mapping.worldCell(x, 61, z);
                    assertTrue(cell.minX() >= box.minX() && cell.maxX() <= box.maxX(), transform + " x");
                    assertTrue(cell.minZ() >= box.minZ() && cell.maxZ() <= box.maxZ(), transform + " z");
                }
            }
        }
    }

    @Test
    void onlyMirroredTransformsFlipTheWinding() {
        for (Transform transform : everyTransform()) {
            GhostMapping mapping = new GhostMapping(FRAME, transform, 0, 0, 0);
            float determinant = mapping.frameToOrigin().determinant();
            assertEquals(1f, Math.abs(determinant), 1e-6f, transform.toString());
            assertEquals(mapping.mirrored(), determinant < 0, transform.toString());
        }
    }

    @Test
    void matricesStayExactFarFromTheWorldOrigin() {
        // Frame, origin and camera near the world border: only small differences reach float.
        Box far = new Box(new BlockPos(29_999_000, 100, -29_999_000), new BlockPos(29_999_040, 110, -29_998_990));
        Transform transform = new Transform(1, Mirror.X);
        GhostMapping mapping = new GhostMapping(far, transform, 29_998_950, 90, -29_999_100);
        double cameraX = 29_998_960.25;
        double cameraY = 95.5;
        double cameraZ = -29_999_080.75;
        int x = 29_999_033;
        int y = 107;
        int z = -29_998_995;
        Matrix4f model = mapping.sectionModel(x >> 4, y >> 4, z >> 4, cameraX, cameraY, cameraZ);
        Vector3f corner = model.transformPosition(new Vector3f(x & 15, y & 15, z & 15));
        Aabb expected = mapping.worldCell(x, y, z);
        // The (x, y, z) corner of the cell maps to one of the mapped cell's corners.
        double relativeX = corner.x + cameraX;
        double relativeZ = corner.z + cameraZ;
        assertTrue(Math.abs(relativeX - expected.minX()) < 1e-3 || Math.abs(relativeX - expected.maxX()) < 1e-3, "x " + relativeX);
        assertEquals(expected.minY(), corner.y + cameraY, 1e-3);
        assertTrue(Math.abs(relativeZ - expected.minZ()) < 1e-3 || Math.abs(relativeZ - expected.maxZ()) < 1e-3, "z " + relativeZ);
    }

    @Test
    void lightKeysFollowEverythingWorldLightDependsOn() {
        GhostMapping base = new GhostMapping(FRAME, Transform.IDENTITY, 1, 2, 3);
        assertEquals(base.lightKey(), new GhostMapping(FRAME, Transform.IDENTITY, 1, 2, 3).lightKey());
        assertNotEquals(0L, base.lightKey());
        assertNotEquals(base.lightKey(), new GhostMapping(FRAME, Transform.IDENTITY, 2, 2, 3).lightKey());
        assertNotEquals(base.lightKey(), new GhostMapping(FRAME, Transform.rotation(1), 1, 2, 3).lightKey());
        assertNotEquals(base.lightKey(), new GhostMapping(FRAME, new Transform(0, Mirror.X), 1, 2, 3).lightKey());
        assertNotEquals(base.lightKey(), new GhostMapping(FRAME, Transform.UPSIDE_DOWN, 1, 2, 3).lightKey());
        Box moved = new Box(FRAME.min().offset(1, 0, 0), FRAME.max().offset(1, 0, 0));
        assertNotEquals(base.lightKey(), new GhostMapping(moved, Transform.IDENTITY, 1, 2, 3).lightKey());
    }
}
