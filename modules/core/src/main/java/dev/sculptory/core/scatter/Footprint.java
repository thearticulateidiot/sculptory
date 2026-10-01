package dev.sculptory.core.scatter;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.transform.Transform;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntArrays;

/**
 * The cells a source writes when pasted: its present, non-air cells, relative to its anchor. The contact cells
 * (the lowest layer of those cells) come first. Under a transform, a cell at relative (rx, ry, rz) lands at
 * {@code anchor + (xx·rx + xz·rz, ry, zx·rx + zz·rz)}: the same map a paste uses, which is linear around the anchor.
 */
final class Footprint {
    final int[] rx, ry, rz;
    final int cells;
    /** Cells {@code [0, contacts)} form the lowest layer. */
    final int contacts;
    final int minRx, maxRx, minRy, maxRy, minRz, maxRz;
    private final BlockPos size;
    /** Indexed by {@code mirror.ordinal() * 4 + quarterTurnsCw}. */
    private final Oriented[] oriented = new Oriented[12];
    /** The cells' indices sorted by (rx, rz, ry), for {@link #covers}; built on first use. */
    private int[] byColumn;

    /** A transform's linear map and the footprint's horizontal extent under it, relative to the anchor. */
    record Oriented(int xx, int xz, int zx, int zz, int minDx, int maxDx, int minDz, int maxDz) {
        int dx(int rx, int rz) {
            return xx * rx + xz * rz;
        }

        int dz(int rx, int rz) {
            return zx * rx + zz * rz;
        }
    }

    private Footprint(int[] rx, int[] ry, int[] rz, int contacts, BlockPos size) {
        this.rx = rx;
        this.ry = ry;
        this.rz = rz;
        this.cells = rx.length;
        this.contacts = contacts;
        this.size = size;
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE;
        int z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        for (int i = 0; i < cells; i++) {
            x0 = Math.min(x0, rx[i]);
            x1 = Math.max(x1, rx[i]);
            y0 = Math.min(y0, ry[i]);
            y1 = Math.max(y1, ry[i]);
            z0 = Math.min(z0, rz[i]);
            z1 = Math.max(z1, rz[i]);
        }
        minRx = x0;
        maxRx = x1;
        minRy = y0;
        maxRy = y1;
        minRz = z0;
        maxRz = z1;
    }

    /**
     * @throws IllegalArgumentException if the source has no non-air cell, holds a state outside {@code states}, or
     *     its anchor lies more than {@value ScatterArea#MAX_HORIZONTAL} blocks from its cells
     */
    static Footprint of(Clipboard source, StateSpace states) {
        BlockPos anchor = source.anchor();
        IntArrayList xs = new IntArrayList(), ys = new IntArrayList(), zs = new IntArrayList();
        int[] lowest = {Integer.MAX_VALUE};
        source.forEachCell((x, y, z, state, tile) -> {
            if (state < 0) return;
            if (state >= states.size()) throw new IllegalArgumentException("Source state " + state + " is outside the state space");
            if (StateFlags.has(states.flags(state), StateFlags.AIR)) return;
            xs.add(relative(x, anchor.x()));
            ys.add(relative(y, anchor.y()));
            zs.add(relative(z, anchor.z()));
            lowest[0] = Math.min(lowest[0], y);
        });
        int n = xs.size();
        if (n == 0) throw new IllegalArgumentException("Scatter source has no blocks: " + source);
        // Contacts (the lowest layer) first, each group in visiting order.
        int bottom = lowest[0] - anchor.y();
        int[] rx = new int[n], ry = new int[n], rz = new int[n];
        int at = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < n; i++) {
                if ((ys.getInt(i) == bottom) != (pass == 0)) continue;
                rx[at] = xs.getInt(i);
                ry[at] = ys.getInt(i);
                rz[at] = zs.getInt(i);
                at++;
            }
        }
        int contacts = 0;
        while (contacts < n && ry[contacts] == bottom) contacts++;
        return new Footprint(rx, ry, rz, contacts, source.size());
    }

    /**
     * Whether the footprint has a cell at relative (rx, ry, rz). The first call sorts an index of the cells (by
     * column, then height); planner thread only.
     */
    boolean covers(int x, int y, int z) {
        if (x < minRx || x > maxRx || y < minRy || y > maxRy || z < minRz || z > maxRz) return false;
        int[] index = byColumn;
        if (index == null) {
            index = new int[cells];
            for (int i = 0; i < cells; i++) index[i] = i;
            IntArrays.quickSort(index, (a, b) -> compare(rx[a], rz[a], ry[a], rx[b], rz[b], ry[b]));
            byColumn = index;
        }
        int lo = 0, hi = cells - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = index[mid];
            int order = compare(rx[c], rz[c], ry[c], x, z, y);
            if (order == 0) return true;
            if (order < 0) lo = mid + 1;
            else hi = mid - 1;
        }
        return false;
    }

    private static int compare(int ax, int az, int ay, int bx, int bz, int by) {
        if (ax != bx) return Integer.compare(ax, bx);
        if (az != bz) return Integer.compare(az, bz);
        return Integer.compare(ay, by);
    }

    Oriented orient(Transform t) {
        int slot = t.mirror().ordinal() * 4 + t.quarterTurnsCw();
        Oriented o = oriented[slot];
        if (o != null) return o;
        int sx = size.x(), sz = size.z();
        int ox = t.mapX(0, 0, sx, sz), oz = t.mapZ(0, 0, sx, sz);
        int xx = t.mapX(1, 0, sx, sz) - ox, xz = t.mapX(0, 1, sx, sz) - ox;
        int zx = t.mapZ(1, 0, sx, sz) - oz, zz = t.mapZ(0, 1, sx, sz) - oz;
        int minDx = Integer.MAX_VALUE, maxDx = Integer.MIN_VALUE, minDz = Integer.MAX_VALUE, maxDz = Integer.MIN_VALUE;
        for (int cx : new int[] {minRx, maxRx}) {
            for (int cz : new int[] {minRz, maxRz}) {
                int dx = xx * cx + xz * cz, dz = zx * cx + zz * cz;
                minDx = Math.min(minDx, dx);
                maxDx = Math.max(maxDx, dx);
                minDz = Math.min(minDz, dz);
                maxDz = Math.max(maxDz, dz);
            }
        }
        o = new Oriented(xx, xz, zx, zz, minDx, maxDx, minDz, maxDz);
        oriented[slot] = o;
        return o;
    }

    private static int relative(int local, int anchor) {
        long r = (long) local - anchor;
        if (Math.abs(r) > ScatterArea.MAX_HORIZONTAL) {
            throw new IllegalArgumentException("Scatter source anchor is too far from its blocks");
        }
        return (int) r;
    }
}
