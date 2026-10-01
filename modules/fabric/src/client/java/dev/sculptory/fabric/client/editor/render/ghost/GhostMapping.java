package dev.sculptory.fabric.client.editor.render.ghost;

import dev.sculptory.core.Box;
import dev.sculptory.core.SplitMix64;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.client.editor.world.Aabb;
import java.util.Objects;
import org.joml.Matrix4f;

/**
 * Where a volume's local cells land in the world: the {@code frame} (the volume's source box) is mirrored and
 * rotated by {@code transform} exactly as {@link Transform} maps cells, and the transformed box's minimum corner is
 * placed at the world cell {@code origin}.
 *
 * <p>Cell maps ({@link #worldX}...) serve outlines and light sampling; the matrices serve meshes. A mesh never
 * changes when the placement moves or turns: only {@link #sectionModel} does. Matrices are built from small
 * numbers only (offsets inside the frame, and the origin relative to the camera, subtracted in double precision), so
 * they stay exact far from the world origin.
 *
 * <p>Minecraft-free (JOML only). Immutable.
 */
public record GhostMapping(Box frame, Transform transform, int originX, int originY, int originZ) {
    public GhostMapping {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(transform, "transform");
    }

    /** World x of the local cell (x, z). Cells outside the frame map by the same affine rule. */
    public int worldX(int x, int z) {
        return originX + transform.mapX(x - frame.min().x(), z - frame.min().z(), frame.sizeX(), frame.sizeZ());
    }

    /** World y of local cell height y (turned over within the frame's height by a flip upside down). */
    public int worldY(int y) {
        return originY + transform.mapY(y - frame.min().y(), frame.sizeY());
    }

    /** World z of the local cell (x, z). */
    public int worldZ(int x, int z) {
        return originZ + transform.mapZ(x - frame.min().x(), z - frame.min().z(), frame.sizeX(), frame.sizeZ());
    }

    /** The world box (block edges) covering the local cells {@code min..max}, both inclusive. */
    public Aabb worldBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        // The cell map is affine and axis-aligned, so opposite corner cells map to opposite corner cells.
        int ax = worldX(minX, minZ);
        int az = worldZ(minX, minZ);
        int bx = worldX(maxX, maxZ);
        int bz = worldZ(maxX, maxZ);
        int ay = worldY(minY);
        int by = worldY(maxY);
        return new Aabb(
                Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz),
                Math.max(ax, bx) + 1.0, Math.max(ay, by) + 1.0, Math.max(az, bz) + 1.0);
    }

    /** {@link #worldBox(int, int, int, int, int, int)} of a local box. */
    public Aabb worldBox(Box local) {
        return worldBox(local.min().x(), local.min().y(), local.min().z(), local.max().x(), local.max().y(), local.max().z());
    }

    /** The world box of one local cell. */
    public Aabb worldCell(int x, int y, int z) {
        int wx = worldX(x, z);
        int wy = worldY(y);
        int wz = worldZ(x, z);
        return new Aabb(wx, wy, wz, wx + 1.0, wy + 1.0, wz + 1.0);
    }

    /**
     * Whether the transform mirrors (a horizontal mirror or the flip upside down, but not both, which together make a
     * half turn): meshes then have reversed winding and must be drawn without culling.
     */
    public boolean mirrored() {
        return (transform.mirror() != Mirror.NONE) != transform.upsideDown();
    }

    /**
     * The continuous version of the cell map, from frame-relative points (local point minus the frame minimum) to
     * world points minus the origin: the mirror, then the clockwise quarter turns, then the flip upside down, with the
     * translations that keep the result inside the transformed box. The unit cube of cell c maps onto the unit cube of its mapped cell.
     */
    public Matrix4f frameToOrigin() {
        float sx = frame.sizeX();
        float sz = frame.sizeZ();
        Matrix4f mirror = switch (transform.mirror()) {
            case NONE -> new Matrix4f();
            case X -> affineXZ(-1, 0, 0, 1, sx, 0); // x -> sx - x
            case Z -> affineXZ(1, 0, 0, -1, 0, sz); // z -> sz - z
        };
        // The mirror keeps the box size, so the turn works in the same (sx, sz) box; the flip upside down turns y over
        // within the frame's height, whatever the rest does.
        Matrix4f turn = switch (transform.quarterTurnsCw()) {
            case 0 -> new Matrix4f();
            case 1 -> affineXZ(0, -1, 1, 0, sz, 0); // (x, z) -> (sz - z, x)
            case 2 -> affineXZ(-1, 0, 0, -1, sx, sz); // (x, z) -> (sx - x, sz - z)
            default -> affineXZ(0, 1, -1, 0, 0, sx); // (x, z) -> (z, sx - x)
        };
        Matrix4f turned = turn.mul(mirror);
        if (!transform.upsideDown()) return turned;
        Matrix4f flip = new Matrix4f().m11(-1).m31(frame.sizeY()); // y -> sy - y
        return flip.mul(turned);
    }

    /**
     * The model matrix of one section mesh, whose vertices are relative to the section's minimum corner, into
     * camera-relative world space (what vanilla's terrain shaders expect after the view rotation).
     */
    public Matrix4f sectionModel(int sectionX, int sectionY, int sectionZ, double cameraX, double cameraY, double cameraZ) {
        // Exact integer offset of the section inside the frame; never a large float cancellation.
        int offsetX = (sectionX << 4) - frame.min().x();
        int offsetY = (sectionY << 4) - frame.min().y();
        int offsetZ = (sectionZ << 4) - frame.min().z();
        return new Matrix4f()
                .translation((float) (originX - cameraX), (float) (originY - cameraY), (float) (originZ - cameraZ))
                .mul(frameToOrigin())
                .translate(offsetX, offsetY, offsetZ);
    }

    /**
     * Identifies everything world-lit meshes depend on (frame, transform, origin). Never 0, which stands for
     * "full-bright" in the mesh cache.
     */
    public long lightKey() {
        long hash = SplitMix64.mix(originX);
        hash = SplitMix64.mix(hash ^ originY);
        hash = SplitMix64.mix(hash ^ originZ);
        hash = SplitMix64.mix(hash ^ transform.quarterTurnsCw() ^ ((long) transform.mirror().ordinal() << 8)
                ^ (transform.upsideDown() ? 1L << 16 : 0));
        hash = SplitMix64.mix(hash ^ frame.min().x());
        hash = SplitMix64.mix(hash ^ frame.min().y());
        hash = SplitMix64.mix(hash ^ frame.min().z());
        hash = SplitMix64.mix(hash ^ frame.sizeX());
        hash = SplitMix64.mix(hash ^ frame.sizeY());
        hash = SplitMix64.mix(hash ^ frame.sizeZ());
        return hash == 0 ? 1 : hash;
    }

    /** x' = a·x + b·z + tx, z' = c·x + d·z + tz, y unchanged. */
    private static Matrix4f affineXZ(float a, float b, float c, float d, float tx, float tz) {
        // JOML's constructor takes columns: (m00 m01 m02 m03) is the image of the x axis, and so on.
        return new Matrix4f(
                a, 0, c, 0,
                0, 1, 0, 0,
                b, 0, d, 0,
                tx, 0, tz, 1);
    }
}
