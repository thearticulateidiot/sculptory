package dev.sculptory.core.generate;

import dev.sculptory.core.Box;
import dev.sculptory.core.brush.SurfaceScan;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The Generate tool's Heightmap import: a height image stretched over the selection's footprint sets each column's
 * surface between a lowest and a highest y.
 *
 * <p>Per column of the footprint, with the image's value {@code v} (0-255) there, the new surface is
 * {@code t = minY + round(v × (maxY - minY) / 255)}. The column works within {@code [lo, hi]}: {@code lo} the lower of the
 * selection's bottom and {@code minY}, {@code hi} the higher of its top and {@code maxY}, both within the build height.
 * <ul>
 *   <li>Every cell above {@code t} up to {@code hi} becomes air (fluids and plants too).</li>
 *   <li>Below it the column keeps its own layers, as the Heightmap brush raises and lowers them: the column's surface
 *       {@code s} is its topmost ground cell ({@link SurfaceScan#ground}) at or below {@code hi}, its top block the
 *       state there, the block beneath it the state under {@code s} when that is ground (else the top block again).
 *       Rising ({@code s < t}): the cells from {@code s} (or {@code lo}) to {@code t - 1} take the block beneath, and
 *       {@code t} the top block. Falling ({@code s > t}): {@code t} takes the top block and everything above goes.</li>
 *   <li>A column with no ground down to the bottom of the world takes {@code t} and below as it is.</li>
 * </ul>
 * Only cells whose state changes are written, so a flat field imported flat writes nothing. Cells holding a block entity
 * (chests, furnaces, signs) are never written: they stay as they are, counted. Cells below {@code lo} and outside the
 * footprint are never written. The columns are read before (on the client thread, {@link #capture}), so the
 * kernel itself reads no world and can run anywhere.
 */
public final class HeightmapAreaKernel {
    private HeightmapAreaKernel() {}

    /**
     * What to import.
     *
     * @param box the selection's bounds: the footprint, and with minY and maxY the y range the columns work in
     * @param minY the surface where the image is black
     * @param maxY the surface where the image is white; at least {@code minY}
     * @param heights the image over the footprint, {@code box.sizeX() × box.sizeZ()} values, row by row from the north
     *     ({@code [(z - minZ) × sizeX + (x - minX)]}); copied
     */
    public record Spec(Box box, int minY, int maxY, byte[] heights) {
        public Spec {
            Objects.requireNonNull(box);
            Objects.requireNonNull(heights);
            if (minY > maxY) throw new IllegalArgumentException("Min Y " + minY + " above Max Y " + maxY);
            if ((long) box.sizeX() * box.sizeZ() != heights.length) {
                throw new IllegalArgumentException("The image needs " + (long) box.sizeX() * box.sizeZ() + " values");
            }
            heights = heights.clone();
        }

        /** The new surface of footprint column (x, z). */
        public int target(int x, int z) {
            int v = heights[(z - box.min().z()) * box.sizeX() + (x - box.min().x())] & 0xFF;
            return minY + (int) (((long) v * (maxY - minY) + 127) / 255);
        }
    }

    /**
     * One column as captured: its surface ({@link SurfaceScan#NONE} without ground), the top block and the block beneath,
     * and its cells from {@code bottom} up; cells above those read as air.
     */
    public record Column(int surface, int top, int fill, int bottom, int[] cells) {
        public Column {
            Objects.requireNonNull(cells);
        }

        /** The state captured at y, air above the captured cells. */
        int state(int y, int air) {
            int i = y - bottom;
            return i >= 0 && i < cells.length ? cells[i] : air;
        }
    }

    /** The captured columns: column (x, z), or {@code null} when its chunk was not loaded. */
    @FunctionalInterface
    public interface Columns {
        Column column(int x, int z);
    }

    /**
     * What {@link #generate} made: the cells, the footprint columns that were not loaded (nothing written there), and the
     * block-entity cells left as they are.
     */
    public record Result(GeneratedSource source, List<long[]> unloaded, long keptBlockEntities) {
        public Result {
            Objects.requireNonNull(source);
            unloaded = List.copyOf(unloaded);
        }
    }

    /** The y range columns of {@code spec} work in, {@code {lo, hi}}, within {@code [bottomY, topYExclusive)}. */
    public static int[] range(Spec spec, int bottomY, int topYExclusive) {
        return range(spec.box(), spec.minY(), spec.maxY(), bottomY, topYExclusive);
    }

    /** {@link #range(Spec, int, int)} for an import over {@code box} from {@code minY} to {@code maxY}. */
    public static int[] range(Box box, int minY, int maxY, int bottomY, int topYExclusive) {
        int lo = Math.max(bottomY, Math.min(box.min().y(), minY));
        int hi = Math.min(topYExclusive - 1, Math.max(box.max().y(), maxY));
        return new int[] {lo, hi};
    }

    /**
     * Reads column (x, z) of {@code world} for an import working in {@code [lo, hi]} whose Min Y is {@code minY}:
     * {@code null} when its chunk is not loaded. Only the cells the import may write or compare are kept: from the lower
     * of the column's surface and {@code minY} (never below {@code lo}) up to its highest non-air cell at or below
     * {@code hi}; the cells beneath are never written (the new surface is at least {@code minY}). A capture made for one
     * Min Y serves any higher one.
     */
    public static Column capture(WorldReader world, int x, int z, int lo, int hi, int minY) {
        if (!world.isLoaded(x >> 4, z >> 4)) return null;
        StateSpace states = world.states();
        int air = states.air();
        int bottomY = world.bottomY();
        int start = Math.min(hi, Math.max(lo - 1, world.heightHint(x, z)));
        // The highest non-air cell of the window (the hint never undershoots: cells above it are air; cave air is air).
        int topSolid = lo - 1;
        for (int y = start; y >= lo; y--) {
            if (!StateFlags.has(states.flags(world.get(x, y, z)), StateFlags.AIR)) {
                topSolid = y;
                break;
            }
        }
        int surface = SurfaceScan.NONE, top = air, fill = air;
        for (int y = Math.max(start, topSolid); y >= bottomY; y--) {
            int state = world.get(x, y, z);
            if (SurfaceScan.ground(states.flags(state))) {
                surface = y;
                top = state;
                int below = y - 1 >= bottomY ? world.get(x, y - 1, z) : air;
                fill = SurfaceScan.ground(states.flags(below)) ? below : state;
                break;
            }
        }
        int base = Math.max(lo, surface == SurfaceScan.NONE ? minY : Math.min(surface, minY));
        int[] cells = new int[Math.max(0, topSolid - base + 1)];
        for (int y = base; y <= topSolid; y++) cells[y - base] = world.get(x, y, z);
        return new Column(surface, top, fill, base, cells);
    }

    /**
     * The import's cells. A cell holding a block entity (a chest, a furnace, a sign) is never written: it is left as it
     * is and counted ({@link Result#keptBlockEntities}).
     *
     * @param bottomY the world's bottom
     * @param topYExclusive one above the world's top
     * @throws GeneratedTooLargeException past {@code maxCells}
     */
    public static Result generate(Spec spec, Columns columns, StateSpace states, int bottomY, int topYExclusive,
                                  long maxCells) {
        Objects.requireNonNull(spec);
        Objects.requireNonNull(columns);
        int air = states.air();
        int[] range = range(spec, bottomY, topYExclusive);
        int lo = range[0], hi = range[1];
        GeneratedSource.Builder out = GeneratedSource.builder(maxCells);
        List<long[]> unloaded = new ArrayList<>();
        long kept = 0;
        Box box = spec.box();
        for (int z = box.min().z(); z <= box.max().z(); z++) {
            for (int x = box.min().x(); x <= box.max().x(); x++) {
                Column column = columns.column(x, z);
                if (column == null) {
                    unloaded.add(new long[] {x, z});
                    continue;
                }
                int t = Math.max(lo, Math.min(hi, spec.target(x, z)));
                int s = column.surface();
                for (int y = Math.max(lo, column.bottom()); y <= hi; y++) {
                    int want = stateAt(column, y, t, s, air);
                    if (want < 0) continue;
                    int current = column.state(y, air);
                    if (want == current) continue;
                    if (StateFlags.has(states.flags(current), StateFlags.HAS_BLOCK_ENTITY)) {
                        kept++;
                        continue;
                    }
                    out.set(x, y, z, want);
                }
            }
        }
        return new Result(out.build(), unloaded, kept);
    }

    /** What cell y of a column becomes for a surface at {@code t}, or -1 to keep it. */
    private static int stateAt(Column column, int y, int t, int s, int air) {
        if (y > t) return air;
        if (s == SurfaceScan.NONE) return -1;
        if (y == t) return column.top();
        // Below the new surface: the layer beneath fills up from the old surface (which gives up its top block).
        if (y >= s && s < t) return column.fill();
        return -1;
    }
}
