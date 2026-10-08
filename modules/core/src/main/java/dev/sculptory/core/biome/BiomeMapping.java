package dev.sculptory.core.biome;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.transform.Orientation;
import java.util.Objects;

/**
 * Carries biomes with copied blocks (protocol 6): what a copy captures ({@link #capture}) and where a paste, move or
 * stack writes them ({@link #map}).
 *
 * <p><b>Mapping.</b> Every world quart the result box touches is sampled at its centre cell ({@code 4q + 2} on every
 * axis) moved into the box (clamped to it, for a quart the box only partly covers), and takes the biome of the source
 * quart holding the source cell {@link Orientation#inverseCell} gives for that cell. Inside the box, for a plain shift,
 * this is the source grid moved by the shift rounded to the nearest quart (a shift of 2 rounds down), so a pasted biome
 * pattern keeps its shape; under a turn or a free orientation it is resampled the same way the blocks are. A thin
 * paste (one or two blocks tall) still carries its biomes. A quart whose sample maps outside the source, or to an
 * absent source quart, is left alone.
 */
public final class BiomeMapping {
    private BiomeMapping() {}

    /** Reads a world quart's biome id; {@code null} when it cannot be read (its chunk is not loaded). */
    @FunctionalInterface
    public interface QuartReader {
        String biome(int qx, int qy, int qz);
    }

    /** Receives one quart a mapping writes. */
    @FunctionalInterface
    public interface QuartSink {
        void set(int qx, int qy, int qz, String biomeId);
    }

    /**
     * The biomes of the quarts of {@code box} that hold a cell of {@code region} inside the build height (the cells a
     * copy takes), read from the world; the grid's local frame is {@code box}'s ({@code box.min()} is local 0). Quarts
     * {@code reader} cannot read are absent. Returns {@code null} when no quart was read.
     */
    public static BiomeGrid capture(Region region, Box box, int bottomY, int topYExclusive, QuartReader reader) {
        Objects.requireNonNull(reader);
        QuartSelection quarts = QuartSelection.touched(region, false, bottomY, topYExclusive);
        BiomeGrid.Builder grid = BiomeGrid.builder(phase(box.min()), size(box));
        int qx0 = box.min().x() >> 2, qy0 = box.min().y() >> 2, qz0 = box.min().z() >> 2;
        BlockPos quartSize = grid.quartSize();
        quarts.forEach((qx, qy, qz) -> {
            int gx = qx - qx0, gy = qy - qy0, gz = qz - qz0;
            if (gx < 0 || gy < 0 || gz < 0 || gx >= quartSize.x() || gy >= quartSize.y() || gz >= quartSize.z()) return;
            String id = reader.biome(qx, qy, qz);
            if (id != null) grid.set(gx, gy, gz, id);
        });
        return grid.build();
    }

    /** The phase of a box starting at {@code min}: its position within its quart. */
    public static BlockPos phase(BlockPos min) {
        return new BlockPos(min.x() & 3, min.y() & 3, min.z() & 3);
    }

    private static BlockPos size(Box box) {
        return new BlockPos(box.sizeX(), box.sizeY(), box.sizeZ());
    }

    /**
     * Maps {@code grid} (of a source box of {@code size} cells) through {@code orientation} onto the result box whose
     * minimum corner is {@code resultMin} (the box {@link Orientation#bounds} gives, placed in the world), and hands
     * {@code out} every quart inside the build height it writes.
     *
     * @return the quarts handed out
     */
    public static long map(BiomeGrid grid, BlockPos size, Orientation orientation, BlockPos resultMin, int bottomY,
                           int topYExclusive, QuartSink out) {
        Objects.requireNonNull(grid);
        Objects.requireNonNull(out);
        BlockPos result = orientation.bounds(size.x(), size.y(), size.z()).size();
        int minQy = bottomY >> 2, maxQy = (topYExclusive >> 2) - 1;
        long n = 0;
        // The quarts the result box touches.
        int maxX = resultMin.x() + result.x() - 1, maxY = resultMin.y() + result.y() - 1;
        int maxZ = resultMin.z() + result.z() - 1;
        int qx0 = resultMin.x() >> 2, qx1 = maxX >> 2;
        int qy0 = Math.max(minQy, resultMin.y() >> 2), qy1 = Math.min(maxQy, maxY >> 2);
        int qz0 = resultMin.z() >> 2, qz1 = maxZ >> 2;
        for (int qy = qy0; qy <= qy1; qy++) {
            for (int qz = qz0; qz <= qz1; qz++) {
                for (int qx = qx0; qx <= qx1; qx++) {
                    int x = clamp((qx << 2) + 2, resultMin.x(), maxX) - resultMin.x();
                    int y = clamp((qy << 2) + 2, resultMin.y(), maxY) - resultMin.y();
                    int z = clamp((qz << 2) + 2, resultMin.z(), maxZ) - resultMin.z();
                    BlockPos source = orientation.inverseCell(x, y, z, size.x(), size.y(), size.z());
                    if (source == null) continue;
                    String id = grid.biomeAt(source.x(), source.y(), source.z());
                    if (id == null) continue;
                    out.set(qx, qy, qz, id);
                    n++;
                }
            }
        }
        return n;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * How many quarts {@link #map} may hand out at most for a source of {@code size} under {@code orientation}: those of
     * the result box (for admission, before anything is mapped).
     */
    public static long bound(BlockPos size, Orientation orientation) {
        BlockPos result = orientation.bounds(size.x(), size.y(), size.z()).size();
        return ((long) result.x() / 4 + 2) * ((long) result.y() / 4 + 2) * ((long) result.z() / 4 + 2);
    }
}
