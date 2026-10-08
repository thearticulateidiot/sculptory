package dev.sculptory.core.biome;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.NamespacedId;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The biomes of a clipboard (protocol 6): one biome id per quart, at the world's quart resolution (4×4×4 cells aligned
 * to multiples of 4), over the clipboard's box. The box rarely starts on a quart boundary, so the grid keeps the box's
 * <i>phase</i> (its minimum corner's position within its quart, 0..3 per axis): local cell {@code (x, y, z)} of the
 * clipboard lies in grid quart {@code ((x + phaseX) >> 2, ...)}. A quart may be absent (no biome captured: none of the
 * copied cells is in it), and then nothing is pasted there.
 *
 * <p>Immutable. Ids are kept once each (the grid holds 16-bit indices), in first-use order over the quarts in index
 * order ({@code x} fastest, then {@code z}, then {@code y}), so equal grids are equal field by field and hash alike
 * ({@link #hashInto}).
 */
public final class BiomeGrid {
    /** What an absent quart holds. */
    private static final char ABSENT = Character.MAX_VALUE;
    /** Most distinct biome ids one grid holds. */
    public static final int MAX_IDS = ABSENT;
    private static final byte[] HASH_MAGIC = "biomes\0".getBytes(StandardCharsets.US_ASCII);

    private final int phaseX, phaseY, phaseZ;
    private final int sizeX, sizeY, sizeZ;
    private final String[] ids;
    private final char[] cells;

    private BiomeGrid(int phaseX, int phaseY, int phaseZ, int sizeX, int sizeY, int sizeZ, String[] ids, char[] cells) {
        this.phaseX = phaseX;
        this.phaseY = phaseY;
        this.phaseZ = phaseZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.ids = ids;
        this.cells = cells;
    }

    /** The grid's size in quarts that a box of {@code size} cells at {@code phase} spans, per axis. */
    public static int quarts(int phase, int size) {
        return ((phase + size - 1) >> 2) + 1;
    }

    /**
     * A builder for the grid of a box of {@code size} cells whose minimum corner sits at {@code phase} within its
     * quart (each 0..3; {@code worldMin & 3} for a box copied from the world).
     */
    public static Builder builder(BlockPos phase, BlockPos size) {
        return new Builder(phase, size);
    }

    /** The box's phase: its minimum corner's position within its quart. */
    public BlockPos phase() {
        return new BlockPos(phaseX, phaseY, phaseZ);
    }

    /** The grid's size in quarts. */
    public BlockPos quartSize() {
        return new BlockPos(sizeX, sizeY, sizeZ);
    }

    /** Whether this grid fits a box of {@code size} cells (its quart size is the one such a box spans at its phase). */
    public boolean fits(BlockPos size) {
        return sizeX == quarts(phaseX, size.x()) && sizeY == quarts(phaseY, size.y()) && sizeZ == quarts(phaseZ, size.z());
    }

    /** The biome of the quart holding local cell {@code (x, y, z)} of the box, or {@code null} (absent, or outside). */
    public String biomeAt(int x, int y, int z) {
        return quart((x + phaseX) >> 2, (y + phaseY) >> 2, (z + phaseZ) >> 2);
    }

    /** The biome of grid quart {@code (qx, qy, qz)}, or {@code null} (absent, or outside the grid). */
    public String quart(int qx, int qy, int qz) {
        if (qx < 0 || qy < 0 || qz < 0 || qx >= sizeX || qy >= sizeY || qz >= sizeZ) return null;
        char c = cells[index(qx, qy, qz)];
        return c == ABSENT ? null : ids[c];
    }

    /** Quarts holding a biome. */
    public long presentCount() {
        long n = 0;
        for (char c : cells) {
            if (c != ABSENT) n++;
        }
        return n;
    }

    /** The distinct biome ids, in first-use order. */
    public List<String> ids() {
        return List.of(ids);
    }

    /** Approximate heap footprint. */
    public long estimatedBytes() {
        long bytes = 64 + 2L * cells.length;
        for (String id : ids) bytes += 40 + 2L * id.length();
        return bytes;
    }

    /**
     * Writes the grid to a content hash: a marker, the phase, the size, the ids and every quart's id index (-1 for
     * absent), so the hash tells grids apart exactly when their content differs.
     */
    public void hashInto(DataOutputStream out) throws IOException {
        out.write(HASH_MAGIC);
        for (int v : new int[] {phaseX, phaseY, phaseZ, sizeX, sizeY, sizeZ}) out.writeInt(v);
        out.writeInt(ids.length);
        for (String id : ids) out.writeUTF(id);
        for (char c : cells) out.writeShort(c == ABSENT ? -1 : c);
    }

    private int index(int qx, int qy, int qz) {
        return (qy * sizeZ + qz) * sizeX + qx;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BiomeGrid g && phaseX == g.phaseX && phaseY == g.phaseY && phaseZ == g.phaseZ
                && sizeX == g.sizeX && sizeY == g.sizeY && sizeZ == g.sizeZ && Arrays.equals(ids, g.ids)
                && Arrays.equals(cells, g.cells);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(cells) + Arrays.hashCode(ids);
    }

    @Override
    public String toString() {
        return "BiomeGrid[" + sizeX + "x" + sizeY + "x" + sizeZ + " quarts, phase " + phaseX + "," + phaseY + ","
                + phaseZ + ", " + ids.length + " biomes]";
    }

    /** Builds a {@link BiomeGrid}. Not thread-safe. */
    public static final class Builder {
        private final int phaseX, phaseY, phaseZ;
        private final int sizeX, sizeY, sizeZ;
        private final String[] cells;

        private Builder(BlockPos phase, BlockPos size) {
            Objects.requireNonNull(phase);
            Objects.requireNonNull(size);
            if (phase.x() < 0 || phase.x() > 3 || phase.y() < 0 || phase.y() > 3 || phase.z() < 0 || phase.z() > 3) {
                throw new IllegalArgumentException("Quart phase " + phase);
            }
            if (size.x() < 1 || size.y() < 1 || size.z() < 1) throw new IllegalArgumentException("Box size " + size);
            phaseX = phase.x();
            phaseY = phase.y();
            phaseZ = phase.z();
            sizeX = quarts(phaseX, size.x());
            sizeY = quarts(phaseY, size.y());
            sizeZ = quarts(phaseZ, size.z());
            long count = (long) sizeX * sizeY * sizeZ;
            if (count > Integer.MAX_VALUE - 8) throw new IllegalArgumentException("Biome grid of " + count + " quarts");
            cells = new String[(int) count];
        }

        /** The grid's size in quarts. */
        public BlockPos quartSize() {
            return new BlockPos(sizeX, sizeY, sizeZ);
        }

        /**
         * Sets grid quart {@code (qx, qy, qz)} to {@code id} ({@code null}: absent).
         *
         * @throws IllegalArgumentException outside the grid, or for a malformed id
         */
        public Builder set(int qx, int qy, int qz, String id) {
            if (qx < 0 || qy < 0 || qz < 0 || qx >= sizeX || qy >= sizeY || qz >= sizeZ) {
                throw new IllegalArgumentException("Quart outside the grid: " + qx + "," + qy + "," + qz);
            }
            if (id != null) new NamespacedId(id);
            cells[(qy * sizeZ + qz) * sizeX + qx] = id;
            return this;
        }

        /** The grid quart holding local cell {@code (x, y, z)} of the box: {@link #set} it with this. */
        public Builder setAt(int x, int y, int z, String id) {
            return set((x + phaseX) >> 2, (y + phaseY) >> 2, (z + phaseZ) >> 2, id);
        }

        /** Whether the quart holding local cell {@code (x, y, z)} has a biome yet. */
        public boolean hasAt(int x, int y, int z) {
            int qx = (x + phaseX) >> 2, qy = (y + phaseY) >> 2, qz = (z + phaseZ) >> 2;
            return cells[(qy * sizeZ + qz) * sizeX + qx] != null;
        }

        /**
         * The grid, or {@code null} when no quart holds a biome.
         *
         * @throws IllegalArgumentException with more than {@value BiomeGrid#MAX_IDS} distinct ids
         */
        public BiomeGrid build() {
            Object2IntOpenHashMap<String> index = new Object2IntOpenHashMap<>();
            index.defaultReturnValue(-1);
            List<String> ids = new ArrayList<>();
            char[] out = new char[cells.length];
            for (int i = 0; i < cells.length; i++) {
                String id = cells[i];
                if (id == null) {
                    out[i] = ABSENT;
                    continue;
                }
                int ref = index.getInt(id);
                if (ref < 0) {
                    if (ids.size() == MAX_IDS) throw new IllegalArgumentException("More than " + MAX_IDS + " biomes");
                    ref = ids.size();
                    index.put(id, ref);
                    ids.add(id);
                }
                out[i] = (char) ref;
            }
            if (ids.isEmpty()) return null;
            return new BiomeGrid(phaseX, phaseY, phaseZ, sizeX, sizeY, sizeZ, ids.toArray(new String[0]), out);
        }
    }
}
