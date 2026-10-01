package dev.sculptory.fabric.client.editor.render.ghost;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.SplitMix64;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import java.util.Arrays;
import java.util.function.IntPredicate;
import org.jetbrains.annotations.Nullable;

/**
 * One 16³ section of a {@link GhostVolume}: a private copy of its block-state handles, plus what the renderer needs
 * without reading every cell again (content hash, counts, occupied bounds, erase cells).
 *
 * <p>Coordinates are the volume's local cell coordinates. "Blocks" are present cells that will hold a block and
 * get meshed; "erase" cells are present cells whose handle the volume's predicate marks as becoming air (an erase
 * preview, drawn as red outlines). Absent cells are left untouched by the edit and are not drawn.
 *
 * <p>Immutable after construction, so mesher threads read it without locking.
 */
public final class GhostSection {
    private static final short[] NO_CELLS = new short[0];

    private final long key;
    private final int sectionX;
    private final int sectionY;
    private final int sectionZ;
    private final SectionBuffer cells;
    private final long hash;
    private final int blockCount;
    private final short[] eraseCells;
    private final @Nullable Box bounds;
    private final @Nullable Box blockBounds;
    private final @Nullable Box eraseBounds;

    /**
     * @param key the {@link BlockBuffer#key} of the section
     * @param source the cells; copied, so the caller may keep changing it
     * @param erases which handles become air
     */
    GhostSection(long key, SectionBuffer source, IntPredicate erases) {
        this.key = key;
        this.sectionX = BlockBuffer.keyX(key);
        this.sectionY = BlockBuffer.keyY(key);
        this.sectionZ = BlockBuffer.keyZ(key);
        this.cells = source.compact();
        this.hash = hashCells(cells);

        CellBounds all = new CellBounds();
        CellBounds solid = new CellBounds();
        CellBounds erase = new CellBounds();
        short[] erased = new short[16];
        int eraseCount = 0;
        int blocks = 0;
        int lastHandle = -1;
        boolean lastErases = false;
        for (int i = 0; i < SectionBuffer.SIZE; i++) {
            int handle = cells.get(i);
            if (handle < 0) {
                continue;
            }
            if (handle != lastHandle) {
                lastHandle = handle;
                lastErases = erases.test(handle);
            }
            all.add(i);
            if (lastErases) {
                if (eraseCount == erased.length) {
                    erased = Arrays.copyOf(erased, erased.length * 2);
                }
                erased[eraseCount++] = (short) i;
                erase.add(i);
            } else {
                blocks++;
                solid.add(i);
            }
        }
        this.blockCount = blocks;
        this.eraseCells = eraseCount == 0 ? NO_CELLS : Arrays.copyOf(erased, eraseCount);
        this.bounds = all.toBox(sectionX, sectionY, sectionZ);
        this.blockBounds = solid.toBox(sectionX, sectionY, sectionZ);
        this.eraseBounds = erase.toBox(sectionX, sectionY, sectionZ);
    }

    /**
     * A hash of the present cells and their handles; independent of the buffer's palette order or bit width, so
     * two sections with the same content hash equally.
     */
    static long hashCells(SectionBuffer cells) {
        long hash = 0x6A09E667F3BCC909L;
        for (int i = 0; i < SectionBuffer.SIZE; i++) {
            int handle = cells.get(i);
            if (handle >= 0) {
                hash = SplitMix64.mix(hash ^ (((long) i << 32) | handle));
            }
        }
        return SplitMix64.mix(hash ^ cells.presentCount());
    }

    /** The {@link BlockBuffer#key} of this section. */
    public long key() {
        return key;
    }

    public int sectionX() {
        return sectionX;
    }

    public int sectionY() {
        return sectionY;
    }

    public int sectionZ() {
        return sectionZ;
    }

    /** The handle at a cell index ({@link SectionBuffer#index}), or -1 if the cell is absent. */
    public int handle(int index) {
        return cells.get(index);
    }

    /** Content hash; see {@link #hashCells}. */
    public long hash() {
        return hash;
    }

    /** Present cells. */
    public int cellCount() {
        return cells.presentCount();
    }

    /** Present cells that hold a block (and get meshed). */
    public int blockCount() {
        return blockCount;
    }

    /** Present cells that become air. */
    public int eraseCount() {
        return eraseCells.length;
    }

    /** Cell indices of the erase cells, ascending. Do not modify. */
    short[] eraseCells() {
        return eraseCells;
    }

    /** Bounds of every present cell in volume-local coordinates, or null if none. */
    public @Nullable Box bounds() {
        return bounds;
    }

    /** Bounds of the block cells, or null if none. */
    public @Nullable Box blockBounds() {
        return blockBounds;
    }

    /** Bounds of the erase cells, or null if none. */
    public @Nullable Box eraseBounds() {
        return eraseBounds;
    }

    @Override
    public String toString() {
        return "GhostSection[" + sectionX + "," + sectionY + "," + sectionZ + ", blocks=" + blockCount
                + ", erase=" + eraseCells.length + "]";
    }

    /** Running min/max of local cell coordinates 0..15. */
    private static final class CellBounds {
        int minX = 16;
        int minY = 16;
        int minZ = 16;
        int maxX = -1;
        int maxY = -1;
        int maxZ = -1;

        void add(int index) {
            int x = SectionBuffer.localX(index);
            int y = SectionBuffer.localY(index);
            int z = SectionBuffer.localZ(index);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }

        @Nullable Box toBox(int sectionX, int sectionY, int sectionZ) {
            if (maxX < 0) {
                return null;
            }
            int baseX = sectionX << 4;
            int baseY = sectionY << 4;
            int baseZ = sectionZ << 4;
            return new Box(
                    new BlockPos(baseX + minX, baseY + minY, baseZ + minZ),
                    new BlockPos(baseX + maxX, baseY + maxY, baseZ + maxZ));
        }
    }
}
