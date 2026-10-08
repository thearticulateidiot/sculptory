package dev.sculptory.fabric.client.editor.render.ghost;

import dev.sculptory.core.Box;
import dev.sculptory.core.SplitMix64;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.client.editor.world.Aabb;
import java.util.Objects;
import org.joml.Matrix4f;

/**
 * Where a volume's local cells land in the world: the {@code frame} (the volume's source box) is turned by
 * {@code orientation} and the result box's minimum corner is placed at the world cell {@code origin}. An exact
 * orientation mirrors and rotates the frame exactly as {@link Transform} maps cells. A free one (a placement being turned
 * by a gizmo ring, before its ghost is resampled) turns the frame as a solid, {@link Orientation#mapPoint}: the mesh is
 * drawn turned, cell maps take the cell a cell's centre turns to ({@link Orientation#mapCell}).
 *
 * <p>Cell maps ({@link #worldX}...) serve outlines and light sampling; the matrices serve meshes. A mesh never
 * changes when the placement moves or turns: only {@link #sectionModel} does. Matrices are built from small
 * numbers only (offsets inside the frame, and the origin relative to the camera, subtracted in double precision), so
 * they stay exact far from the world origin.
 *
 * <p>Minecraft-free (JOML only). Immutable.
 */
public record GhostMapping(Box frame, Orientation orientation, int originX, int originY, int originZ) {
    public GhostMapping {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(orientation, "orientation");
    }

    /** An exact mapping: {@code transform}'s orientation. */
    public GhostMapping(Box frame, Transform transform, int originX, int originY, int originZ) {
        this(frame, Orientation.of(Objects.requireNonNull(transform, "transform")), originX, originY, originZ);
    }

    /** The exact part of the orientation (all of it unless the mapping is free). */
    public Transform transform() {
        return orientation.transform();
    }

    /** Whether the frame is turned by free angles (drawn as a turned solid). */
    public boolean free() {
        return !orientation.isExact();
    }

    /**
     * World x of the local cell (x, z). Cells outside the frame map by the same affine rule. A free mapping takes the
     * cell at the frame's lowest height (see {@link #worldCell} for a whole cell).
     */
    public int worldX(int x, int z) {
        if (free()) return freeCell(x, frame.min().y(), z)[0];
        Transform transform = transform();
        return originX + transform.mapX(x - frame.min().x(), z - frame.min().z(), frame.sizeX(), frame.sizeZ());
    }

    /**
     * World y of local cell height y (turned over within the frame's height by a flip upside down). A free mapping
     * takes the frame's middle column.
     */
    public int worldY(int y) {
        if (free()) return freeCell(frame.min().x() + frame.sizeX() / 2, y, frame.min().z() + frame.sizeZ() / 2)[1];
        return originY + transform().mapY(y - frame.min().y(), frame.sizeY());
    }

    /** World z of the local cell (x, z). */
    public int worldZ(int x, int z) {
        if (free()) return freeCell(x, frame.min().y(), z)[2];
        Transform transform = transform();
        return originZ + transform.mapZ(x - frame.min().x(), z - frame.min().z(), frame.sizeX(), frame.sizeZ());
    }

    /** The world box (block edges) covering the local cells {@code min..max}, both inclusive. */
    public Aabb worldBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (free()) {
            // The turned box's corners, bounded.
            double[] lo = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
            double[] hi = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
            for (int corner = 0; corner < 8; corner++) {
                double[] p = point((corner & 1) == 0 ? minX : maxX + 1.0, (corner & 2) == 0 ? minY : maxY + 1.0,
                        (corner & 4) == 0 ? minZ : maxZ + 1.0);
                for (int i = 0; i < 3; i++) {
                    lo[i] = Math.min(lo[i], p[i]);
                    hi[i] = Math.max(hi[i], p[i]);
                }
            }
            return new Aabb(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
        }
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

    /** The world box of one local cell (for a free mapping, the cell its centre turns to). */
    public Aabb worldCell(int x, int y, int z) {
        int wx, wy, wz;
        if (free()) {
            int[] cell = freeCell(x, y, z);
            wx = cell[0];
            wy = cell[1];
            wz = cell[2];
        } else {
            wx = worldX(x, z);
            wy = worldY(y);
            wz = worldZ(x, z);
        }
        return new Aabb(wx, wy, wz, wx + 1.0, wy + 1.0, wz + 1.0);
    }

    /**
     * Whether the transform mirrors (a horizontal mirror or the flip upside down, but not both, which together make a
     * half turn): meshes then have reversed winding and must be drawn without culling. A free turn never mirrors.
     */
    public boolean mirrored() {
        Transform transform = transform();
        return (transform.mirror() != Mirror.NONE) != transform.upsideDown();
    }

    /**
     * The continuous version of the cell map, from frame-relative points (local point minus the frame minimum) to
     * world points minus the origin: the mirror, then the clockwise quarter turns, then the flip upside down, with the
     * translations that keep the result inside the transformed box (then, for a free mapping, the free turn about the
     * middle cell, into the result box: {@link Orientation#mapPoint}). The unit cube of cell c maps onto the unit cube
     * of its mapped cell when the mapping is exact.
     */
    public Matrix4f frameToOrigin() {
        if (free()) {
            double[] o = orientation.mapPoint(0, 0, 0, frame.sizeX(), frame.sizeY(), frame.sizeZ());
            double[] ex = orientation.mapPoint(1, 0, 0, frame.sizeX(), frame.sizeY(), frame.sizeZ());
            double[] ey = orientation.mapPoint(0, 1, 0, frame.sizeX(), frame.sizeY(), frame.sizeZ());
            double[] ez = orientation.mapPoint(0, 0, 1, frame.sizeX(), frame.sizeY(), frame.sizeZ());
            return new Matrix4f(
                    (float) (ex[0] - o[0]), (float) (ex[1] - o[1]), (float) (ex[2] - o[2]), 0,
                    (float) (ey[0] - o[0]), (float) (ey[1] - o[1]), (float) (ey[2] - o[2]), 0,
                    (float) (ez[0] - o[0]), (float) (ez[1] - o[1]), (float) (ez[2] - o[2]), 0,
                    (float) o[0], (float) o[1], (float) o[2], 1);
        }
        Transform transform = transform();
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
     * Identifies everything world-lit meshes depend on (frame, orientation, origin). Never 0, which stands for
     * "full-bright" in the mesh cache.
     */
    public long lightKey() {
        Transform transform = transform();
        long hash = SplitMix64.mix(originX);
        hash = SplitMix64.mix(hash ^ originY);
        hash = SplitMix64.mix(hash ^ originZ);
        hash = SplitMix64.mix(hash ^ transform.quarterTurnsCw() ^ ((long) transform.mirror().ordinal() << 8)
                ^ (transform.upsideDown() ? 1L << 16 : 0));
        if (free()) {
            hash = SplitMix64.mix(hash ^ orientation.yaw() ^ ((long) orientation.pitch() << 16)
                    ^ ((long) orientation.roll() << 32));
        }
        hash = SplitMix64.mix(hash ^ frame.min().x());
        hash = SplitMix64.mix(hash ^ frame.min().y());
        hash = SplitMix64.mix(hash ^ frame.min().z());
        hash = SplitMix64.mix(hash ^ frame.sizeX());
        hash = SplitMix64.mix(hash ^ frame.sizeY());
        hash = SplitMix64.mix(hash ^ frame.sizeZ());
        return hash == 0 ? 1 : hash;
    }

    /** A free mapping's world cell of local cell (x, y, z). */
    private int[] freeCell(int x, int y, int z) {
        dev.sculptory.core.BlockPos to = orientation.mapCell(x - frame.min().x(), y - frame.min().y(),
                z - frame.min().z(), frame.sizeX(), frame.sizeY(), frame.sizeZ());
        return new int[] {originX + to.x(), originY + to.y(), originZ + to.z()};
    }

    /** A free mapping's world point of local point (x, y, z). */
    private double[] point(double x, double y, double z) {
        double[] p = orientation.mapPoint(x - frame.min().x(), y - frame.min().y(), z - frame.min().z(), frame.sizeX(),
                frame.sizeY(), frame.sizeZ());
        return new double[] {originX + p[0], originY + p[1], originZ + p[2]};
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
