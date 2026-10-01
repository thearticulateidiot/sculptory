package dev.sculptory.core.scatter;

import dev.sculptory.core.Box;
import java.util.List;
import java.util.Objects;

/**
 * The columns a scatter covers: painted disc stamps, or every column of a box. The area's bounding rectangle
 * holds at most {@value #MAX_COLUMNS} columns, and its coordinates stay within {@value #MAX_HORIZONTAL} of the
 * origin (the vanilla world is ±30M).
 */
public sealed interface ScatterArea {
    int MAX_STAMPS = 4096;
    int MAX_RADIUS = 64;
    /** The largest bounding rectangle, in columns (1024 × 1024). */
    long MAX_COLUMNS = 1L << 20;
    int MAX_HORIZONTAL = 1 << 25;

    /**
     * A disc of columns {@code (x + dx, z + dz)} with {@code dx² + dz² <= radius²}. An erase stamp removes its
     * columns from the area painted so far.
     */
    record Stamp(int x, int z, int radius, boolean erase) {
        public Stamp {
            if (radius < 0 || radius > MAX_RADIUS) throw new IllegalArgumentException("Stamp radius must be 0-" + MAX_RADIUS);
            if (Math.abs((long) x) + radius > MAX_HORIZONTAL || Math.abs((long) z) + radius > MAX_HORIZONTAL) {
                throw new IllegalArgumentException("Stamp outside the world: " + x + "," + z);
            }
        }

        public static Stamp paint(int x, int z, int radius) {
            return new Stamp(x, z, radius, false);
        }

        public static Stamp erase(int x, int z, int radius) {
            return new Stamp(x, z, radius, true);
        }
    }

    /**
     * Stamps applied in order: a column is in the area when the last stamp covering it paints. Surfaces are
     * searched over the whole build height. At least one stamp must paint.
     */
    record Stamps(List<Stamp> stamps) implements ScatterArea {
        public Stamps {
            stamps = List.copyOf(stamps);
            if (stamps.size() > MAX_STAMPS) throw new IllegalArgumentException("More than " + MAX_STAMPS + " stamps");
            long minX = Long.MAX_VALUE, minZ = Long.MAX_VALUE, maxX = Long.MIN_VALUE, maxZ = Long.MIN_VALUE;
            for (Stamp stamp : stamps) {
                if (stamp.erase()) continue;
                minX = Math.min(minX, (long) stamp.x() - stamp.radius());
                minZ = Math.min(minZ, (long) stamp.z() - stamp.radius());
                maxX = Math.max(maxX, (long) stamp.x() + stamp.radius());
                maxZ = Math.max(maxZ, (long) stamp.z() + stamp.radius());
            }
            if (minX == Long.MAX_VALUE) throw new IllegalArgumentException("A scatter area needs a painting stamp");
            checkColumns(maxX - minX + 1, maxZ - minZ + 1);
        }
    }

    /**
     * Every column of the box. Surfaces are searched only between the box's lowest and highest y (clipped to the
     * build height), like a brush's scan window: a column whose top cell in the box is not open has no surface.
     */
    record Region(Box box) implements ScatterArea {
        public Region {
            Objects.requireNonNull(box);
            if (Math.max(Math.abs((long) box.min().x()), Math.abs((long) box.max().x())) > MAX_HORIZONTAL
                    || Math.max(Math.abs((long) box.min().z()), Math.abs((long) box.max().z())) > MAX_HORIZONTAL) {
                throw new IllegalArgumentException("Scatter box outside the world: " + box);
            }
            checkColumns(box.sizeX(), box.sizeZ());
        }
    }

    private static void checkColumns(long sizeX, long sizeZ) {
        if (sizeX * sizeZ > MAX_COLUMNS) {
            throw new IllegalArgumentException("Scatter area spans " + sizeX + "x" + sizeZ + " columns, more than "
                    + MAX_COLUMNS);
        }
    }
}
