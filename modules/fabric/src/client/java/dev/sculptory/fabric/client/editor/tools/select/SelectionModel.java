package dev.sculptory.fabric.client.editor.tools.select;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.client.editor.world.Aabb;
import dev.sculptory.fabric.client.editor.world.BoxFace;
import dev.sculptory.fabric.client.editor.world.Ray;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Pure selection maths: resizing a face, growing to a block, nudging, the ray geometry for dragging handles and moving
 * the box, and resizing, moving and turning regions (boxes, shapes, cell sets). No Minecraft types.
 */
public final class SelectionModel {
    private SelectionModel() {}

    /** The world-space box covering every cell of {@code box}. */
    public static Aabb toAabb(Box box) {
        return Aabb.ofBlocks(box.min().x(), box.min().y(), box.min().z(), box.max().x(), box.max().y(), box.max().z());
    }

    /**
     * Moves one face by {@code delta} blocks along its axis (positive is towards +x/+y/+z), keeping
     * the box at least one block thick: a face can't be dragged past the opposite face.
     */
    public static Box moveFace(Box box, BoxFace face, int delta) {
        int axis = face.axis();
        int min = coordinate(box.min(), axis);
        int max = coordinate(box.max(), axis);
        if (face.sign() > 0) {
            max = Math.max(min, saturatingAdd(max, delta));
        } else {
            min = Math.min(max, saturatingAdd(min, delta));
        }
        return new Box(with(box.min(), axis, min), with(box.max(), axis, max));
    }

    /** Pushes a face outwards by {@code amount} blocks (inwards when negative). */
    public static Box expand(Box box, BoxFace face, int amount) {
        return moveFace(box, face, face.sign() * amount);
    }

    /** Grows (or, when negative, shrinks) every face by {@code amount}, keeping at least one block per axis. */
    public static Box expandAll(Box box, int amount) {
        Box result = box;
        for (BoxFace face : BoxFace.values()) {
            result = expand(result, face, amount);
        }
        return result;
    }

    /** The smallest box containing {@code box} and {@code cell}. */
    public static Box grow(Box box, BlockPos cell) {
        Objects.requireNonNull(cell);
        if (box == null) {
            return Box.of(cell);
        }
        return new Box(
                new BlockPos(Math.min(box.min().x(), cell.x()), Math.min(box.min().y(), cell.y()), Math.min(box.min().z(), cell.z())),
                new BlockPos(Math.max(box.max().x(), cell.x()), Math.max(box.max().y(), cell.y()), Math.max(box.max().z(), cell.z())));
    }

    /** The box moved by whole blocks. */
    public static Box nudge(Box box, int dx, int dy, int dz) {
        return box.offset(dx, dy, dz);
    }

    /** The box with a new min corner; axes where it would pass the max corner are swapped into order. */
    public static Box withMin(Box box, BlockPos min) {
        return Box.of(min, box.max());
    }

    /** The box with a new max corner; axes where it would pass the min corner are swapped into order. */
    public static Box withMax(Box box, BlockPos max) {
        return Box.of(box.min(), max);
    }

    // ---- Regions ----

    /** Whether a region's bounds can be resized (a box or a shape; a cell set can only move). */
    public static boolean resizable(Region region) {
        return region instanceof Region.Cuboid || region instanceof Region.Shape;
    }

    /**
     * A box or shape with new bounds: a box becomes {@code box}, a shape the same shape (kind and facing) inscribed in
     * it.
     *
     * @throws IllegalArgumentException for a cell set, which can't be resized
     */
    public static Region withBounds(Region region, Box box) {
        Objects.requireNonNull(box);
        return switch (region) {
            case Region.Cuboid cuboid -> new Region.Cuboid(box);
            case Region.Shape shape -> new Region.Shape(box, shape.kind(), shape.facing());
            case Region.Cells cells -> throw new IllegalArgumentException("A cell set can't be resized");
            case Region.Uploaded uploaded -> throw new IllegalArgumentException("An uploaded region has no cells here");
        };
    }

    /** The region moved so its bounds start at {@code min}. */
    public static Region movedTo(Region region, BlockPos min) {
        BlockPos from = region.bounds().min();
        return region.translate(min.x() - from.x(), min.y() - from.y(), min.z() - from.z());
    }

    /**
     * Where a move with a transform puts the region: its bounds' local cells mapped by {@code t} (as the server's move
     * maps them) into the box starting at {@code destinationMin}. A shape keeps its kind with its facing turned (shapes
     * are exactly symmetric, so that is the same cells); a cell set is mapped cell by cell.
     */
    public static Region moved(Region region, BlockPos destinationMin, Transform t) {
        Objects.requireNonNull(destinationMin);
        if (t.isIdentity()) return movedTo(region, destinationMin);
        Box source = region.bounds();
        BlockPos size = t.size(source.sizeX(), source.sizeY(), source.sizeZ());
        Box destination = new Box(destinationMin, destinationMin.offset(size.x() - 1, size.y() - 1, size.z() - 1));
        return switch (region) {
            case Region.Cuboid cuboid -> new Region.Cuboid(destination);
            case Region.Shape shape -> new Region.Shape(destination, shape.kind(), turned(shape.facing(), t));
            case Region.Cells cells -> new Region.Cells(mapped(cells, destinationMin, t));
            case Region.Uploaded uploaded -> throw new IllegalArgumentException("An uploaded region has no cells here");
        };
    }

    /**
     * A facing after a transform: horizontal ones are mirrored, then turned clockwise; up and down stay, or swap when the
     * transform flips upside down.
     */
    public static Facing turned(Facing facing, Transform t) {
        if (facing.axis() == 1) return t.upsideDown() ? facing.opposite() : facing;
        int dx = facing == Facing.EAST ? 1 : facing == Facing.WEST ? -1 : 0;
        int dz = facing == Facing.SOUTH ? 1 : facing == Facing.NORTH ? -1 : 0;
        if (t.mirror() == Mirror.X) dx = -dx;
        if (t.mirror() == Mirror.Z) dz = -dz;
        for (int turn = 0; turn < t.quarterTurnsCw(); turn++) {
            int x = -dz;
            dz = dx;
            dx = x;
        }
        return dx > 0 ? Facing.EAST : dx < 0 ? Facing.WEST : dz > 0 ? Facing.SOUTH : Facing.NORTH;
    }

    private static CellSet mapped(Region.Cells region, BlockPos destinationMin, Transform t) {
        Box source = region.bounds();
        int sx = source.sizeX();
        int sy = source.sizeY();
        int sz = source.sizeZ();
        CellSet.Builder out = CellSet.builder();
        for (long key : region.sectionKeys()) {
            int baseX = BlockBuffer.keyX(key) << 4;
            int baseY = BlockBuffer.keyY(key) << 4;
            int baseZ = BlockBuffer.keyZ(key) << 4;
            for (int y = baseY; y < baseY + 16; y++) {
                for (int z = baseZ; z < baseZ + 16; z++) {
                    for (int x = baseX; x < baseX + 16; x++) {
                        if (!region.contains(x, y, z)) continue;
                        int lx = x - source.min().x();
                        int lz = z - source.min().z();
                        out.add(destinationMin.x() + t.mapX(lx, lz, sx, sz),
                                destinationMin.y() + t.mapY(y - source.min().y(), sy),
                                destinationMin.z() + t.mapZ(lx, lz, sx, sz));
                    }
                }
            }
        }
        return out.build();
    }

    /** "12 × 5 × 8" (x × y × z). */
    public static String dimensions(Box box) {
        return box.sizeX() + " × " + box.sizeY() + " × " + box.sizeZ();
    }

    /** A block count with thousands separators: "1,234,567". */
    public static String count(long blocks) {
        return String.format(Locale.ROOT, "%,d", blocks);
    }

    // ---- Ray geometry ----

    /**
     * Where the ray passes closest to the line through {@code (px, py, pz)} along {@code axis}, as
     * the coordinate on that axis. Empty when the ray runs (almost) parallel to the axis, since the
     * closest point is then undefined.
     */
    public static OptionalDouble closestOnAxis(Ray ray, double px, double py, double pz, int axis) {
        double ux = axis == 0 ? 1 : 0;
        double uy = axis == 1 ? 1 : 0;
        double uz = axis == 2 ? 1 : 0;
        double wx = ray.originX() - px;
        double wy = ray.originY() - py;
        double wz = ray.originZ() - pz;
        double b = ray.dirX() * ux + ray.dirY() * uy + ray.dirZ() * uz;
        double d = ray.dirX() * wx + ray.dirY() * wy + ray.dirZ() * wz;
        double e = ux * wx + uy * wy + uz * wz;
        double denominator = 1 - b * b;
        if (denominator < 1e-6) {
            return OptionalDouble.empty();
        }
        double t = (e - b * d) / denominator;
        double origin = axis == 0 ? px : axis == 1 ? py : pz;
        return OptionalDouble.of(origin + t);
    }

    /**
     * Where the ray crosses the plane {@code coordinate = value} on {@code axis}, or null when it is
     * parallel to the plane or the plane is behind the ray.
     */
    public static double[] intersectPlane(Ray ray, int axis, double value) {
        double origin = axis == 0 ? ray.originX() : axis == 1 ? ray.originY() : ray.originZ();
        double direction = axis == 0 ? ray.dirX() : axis == 1 ? ray.dirY() : ray.dirZ();
        if (Math.abs(direction) < 1e-9) {
            return null;
        }
        double t = (value - origin) / direction;
        if (t < 0) {
            return null;
        }
        return new double[] {ray.pointX(t), ray.pointY(t), ray.pointZ(t)};
    }

    static int coordinate(BlockPos pos, int axis) {
        return switch (axis) {
            case 0 -> pos.x();
            case 1 -> pos.y();
            case 2 -> pos.z();
            default -> throw new IllegalArgumentException("axis must be 0, 1 or 2: " + axis);
        };
    }

    static BlockPos with(BlockPos pos, int axis, int value) {
        return switch (axis) {
            case 0 -> new BlockPos(value, pos.y(), pos.z());
            case 1 -> new BlockPos(pos.x(), value, pos.z());
            case 2 -> new BlockPos(pos.x(), pos.y(), value);
            default -> throw new IllegalArgumentException("axis must be 0, 1 or 2: " + axis);
        };
    }

    private static int saturatingAdd(int value, int delta) {
        long sum = (long) value + delta;
        return (int) Math.max(Integer.MIN_VALUE / 2, Math.min(Integer.MAX_VALUE / 2, sum));
    }
}
