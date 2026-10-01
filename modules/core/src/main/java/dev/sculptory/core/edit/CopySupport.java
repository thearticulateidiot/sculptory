package dev.sculptory.core.edit;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.transform.Transform;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.function.LongFunction;

/** Shared pieces of the copying programs (paste, move, stack). */
final class CopySupport {
    private CopySupport() {}

    /**
     * Writes {@code next} with {@code tile} at cell {@code i} unless the cell already holds exactly that state and
     * tile content. A {@code null} tile means the state's default block entity, so it replaces a tile that is there.
     */
    static void copyCell(SectionBuffer before, SectionBuffer out, int i, int next, BlockEntityData tile) {
        if (next == before.get(i) && sameTile(tile, before.tile(i))) return;
        out.set(i, next);
        out.setTile(i, tile);
    }

    static boolean sameTile(BlockEntityData a, BlockEntityData b) {
        if (a == null || b == null) return a == b;
        return a == b || a.sameContent(b);
    }

    /**
     * Whether a landing cell holding {@code before} may be written under {@code into}: EXISTING only a block that is not air, AIR only air. A cell without content (a negative handle)
     * is never written by either filter.
     */
    static boolean writable(PasteOptions.Into into, StateSpace states, int before) {
        return switch (into) {
            case EVERYTHING -> true;
            case EXISTING -> before >= 0 && !StateFlags.has(states.flags(before), StateFlags.AIR);
            case AIR -> before >= 0 && StateFlags.has(states.flags(before), StateFlags.AIR);
        };
    }

    /**
     * The 16 cells of row {@code r} ({@code (ly << 4) | lz}, bit lx) of {@code before} that {@code into} allows
     * ({@link #writable}); every cell with EVERYTHING, without reading the row.
     */
    static int writableRow(PasteOptions.Into into, StateSpace states, SectionBuffer before, int r) {
        if (into == PasteOptions.Into.EVERYTHING) return 0xFFFF;
        int base = SectionBuffer.index(0, r >>> 4, r & 15);
        int bits = 0;
        for (int lx = 0; lx < 16; lx++) {
            if (writable(into, states, before.get(base | lx))) bits |= 1 << lx;
        }
        return bits;
    }

    /** Adds the section keys of {@code box} to {@code keys}. */
    static void addSections(Box box, LongOpenHashSet keys) {
        box.forEachSectionKey(keys::add);
    }

    /** The keys in {@link SectionOrder} order. */
    static long[] ordered(LongOpenHashSet keys) {
        long[] order = keys.toLongArray();
        SectionOrder.sort(order);
        return order;
    }

    /** The keys of {@code box} in {@link SectionOrder} order. */
    static long[] ordered(Box box) {
        LongOpenHashSet keys = new LongOpenHashSet();
        addSections(box, keys);
        return ordered(keys);
    }

    /** The smallest box holding both. */
    static Box union(Box a, Box b) {
        return new Box(
                new BlockPos(Math.min(a.min().x(), b.min().x()), Math.min(a.min().y(), b.min().y()), Math.min(a.min().z(), b.min().z())),
                new BlockPos(Math.max(a.max().x(), b.max().x()), Math.max(a.max().y(), b.max().y()), Math.max(a.max().z(), b.max().z())));
    }

    /** The common cells, or {@code null}. */
    static Box intersection(Box a, Box b) {
        if (!a.intersects(b)) return null;
        return new Box(
                new BlockPos(Math.max(a.min().x(), b.min().x()), Math.max(a.min().y(), b.min().y()), Math.max(a.min().z(), b.min().z())),
                new BlockPos(Math.min(a.max().x(), b.max().x()), Math.min(a.max().y(), b.max().y()), Math.min(a.max().z(), b.max().z())));
    }

    /**
     * Where a paste of a source of {@code size} with {@code anchor} puts the whole transformed box so that the
     * transformed anchor lands on {@code origin}. The map is affine, so it is sampled at small points and extended
     * in long arithmetic: an anchor far outside the box can neither overflow nor wrap around.
     *
     * @throws IllegalArgumentException if the box leaves the int coordinate range
     */
    static Box pasteTarget(BlockPos size, BlockPos anchor, Transform t, BlockPos origin) {
        int sx = size.x(), sz = size.z();
        int originX = t.mapX(0, 0, sx, sz), originZ = t.mapZ(0, 0, sx, sz);
        long anchorX = originX + (long) (t.mapX(1, 0, sx, sz) - originX) * anchor.x()
                + (long) (t.mapX(0, 1, sx, sz) - originX) * anchor.z();
        long anchorZ = originZ + (long) (t.mapZ(1, 0, sx, sz) - originZ) * anchor.x()
                + (long) (t.mapZ(0, 1, sx, sz) - originZ) * anchor.z();
        long anchorY = t.mapY((long) anchor.y(), size.y());
        return boxAt((long) origin.x() - anchorX, (long) origin.y() - anchorY, (long) origin.z() - anchorZ,
                t.size(size.x(), size.y(), size.z()));
    }

    /**
     * The world box holding the source-local box {@code [x0, x1] × [y0, y1] × [z0, z1]} once pasted into
     * {@code target} (the {@link #pasteTarget} of a source of {@code size}) with {@code t}.
     */
    static Box transformedPart(Box target, BlockPos size, Transform t, int x0, int y0, int z0, int x1, int y1, int z1) {
        int ax = t.mapX(x0, z0, size.x(), size.z()), az = t.mapZ(x0, z0, size.x(), size.z());
        int bx = t.mapX(x1, z1, size.x(), size.z()), bz = t.mapZ(x1, z1, size.x(), size.z());
        int ay = t.mapY(y0, size.y()), by = t.mapY(y1, size.y());
        BlockPos min = target.min();
        return new Box(
                new BlockPos(min.x() + Math.min(ax, bx), min.y() + Math.min(ay, by), min.z() + Math.min(az, bz)),
                new BlockPos(min.x() + Math.max(ax, bx), min.y() + Math.max(ay, by), min.z() + Math.max(az, bz)));
    }

    /** {@code min} plus {@code size - 1} on each axis, refusing boxes past the int range. */
    static Box boxAt(long minX, long minY, long minZ, BlockPos size) {
        long maxX = minX + size.x() - 1, maxY = minY + size.y() - 1, maxZ = minZ + size.z() - 1;
        if (minX < Integer.MIN_VALUE || minY < Integer.MIN_VALUE || minZ < Integer.MIN_VALUE
                || maxX > Integer.MAX_VALUE || maxY > Integer.MAX_VALUE || maxZ > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Destination is outside the coordinate range");
        }
        return new Box(new BlockPos((int) minX, (int) minY, (int) minZ), new BlockPos((int) maxX, (int) maxY, (int) maxZ));
    }

    /**
     * Maps local cells of a transformed box back to the source box: {@code source = origin + tx * dx + tz * dz}
     * on x and z (y maps by {@link Transform#mapY}, which is its own inverse). The inverse transform is affine, so three
     * samples determine it exactly.
     */
    record InverseMap(int originX, int originZ, int xPerTx, int zPerTx, int xPerTz, int zPerTz) {
        static InverseMap of(Transform t, int sourceX, int sourceZ) {
            int tsx = t.sizeX(sourceX, sourceZ), tsz = t.sizeZ(sourceX, sourceZ);
            Transform inverse = t.inverse();
            int ox = inverse.mapX(0, 0, tsx, tsz), oz = inverse.mapZ(0, 0, tsx, tsz);
            return new InverseMap(ox, oz,
                    inverse.mapX(1, 0, tsx, tsz) - ox, inverse.mapZ(1, 0, tsx, tsz) - oz,
                    inverse.mapX(0, 1, tsx, tsz) - ox, inverse.mapZ(0, 1, tsx, tsz) - oz);
        }

        int x(int tx, int tz) {
            return originX + xPerTx * tx + xPerTz * tz;
        }

        int z(int tx, int tz) {
            return originZ + zPerTx * tx + zPerTz * tz;
        }
    }

    /**
     * Applies a transform to block states, caching each result by handle. The cache is an int array the size of
     * the state space; concurrent use is safe because every writer stores the same deterministic value.
     */
    static final class StateMapper {
        private final StateSpace states;
        private final Transform transform;
        /** 0 = not computed yet, otherwise the mapped handle + 1. {@code null} for the identity. */
        private final int[] cache;

        StateMapper(StateSpace states, Transform transform) {
            this.states = states;
            this.transform = transform;
            this.cache = transform.isIdentity() ? null : new int[states.size()];
        }

        int map(int handle) {
            if (cache == null) return handle;
            int cached = cache[handle];
            if (cached != 0) return cached - 1;
            int mapped = transform.applyToState(states, handle);
            if (mapped < 0 || mapped >= cache.length) {
                throw new IllegalStateException("Transform of state " + handle + " gave " + mapped);
            }
            cache[handle] = mapped + 1;
            return mapped;
        }
    }

    /**
     * Reads cells from keyed sections, remembering the last section so runs of cells in one section cost one
     * lookup. One per {@code compute} call (not thread-safe).
     */
    static final class SectionCursor {
        private final LongFunction<SectionBuffer> lookup;
        private boolean valid;
        private int sectionX, sectionY, sectionZ;
        private SectionBuffer section;

        SectionCursor(LongFunction<SectionBuffer> lookup) {
            this.lookup = lookup;
        }

        /** The section holding (x, y, z), or {@code null}. */
        SectionBuffer at(int x, int y, int z) {
            int sx = x >> 4, sy = y >> 4, sz = z >> 4;
            if (!valid || sx != sectionX || sy != sectionY || sz != sectionZ) {
                section = lookup.apply(BlockBuffer.key(sx, sy, sz));
                sectionX = sx;
                sectionY = sy;
                sectionZ = sz;
                valid = true;
            }
            return section;
        }
    }
}
