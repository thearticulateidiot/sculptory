package dev.sculptory.core.brush;

import dev.sculptory.core.Box;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import java.util.Objects;

/**
 * Where a Heightmap dab ({@link HeightmapKernel}) can write, column by column, found against the world as it is before
 * the dab: for a caller that keeps those cells as they were (the client's cursor ray looks through a press's own
 * stamps).
 *
 * <p>The dab's step is located as the kernel's caller locates it ({@link SymmetricStep#of}). Each dab of the step writes
 * only columns within {@link BrushSpec#reach()} of its block across (a copy's stamp may lie one column off), inside the
 * clip box, and in each only a column whose surface its scan finds ({@link SurfaceScan#scan} over the kernel's window):
 * raising, from that surface up to {@code heightBlocks} plus two (its plants) above it; lowering, from {@code heightBlocks}
 * below it up to its plants, two above it. A stroke never moves a column
 * further than {@code heightBlocks} from where it first found it, so the ranges hold for a dab of a stroke whose earlier
 * dabs the world does not show yet too (a prediction applied with the next batch, a stamp the server is still writing).
 *
 * <p>Reads one surface scan per column (a few cells, from the reader's {@link WorldReader#heightHint}), at most
 * {@code (2 × radius + 3)²} columns per dab of the step.
 */
public final class HeightmapReach {
    /** Receives one column's range of cells that may be written, {@code minY <= maxY}. */
    @FunctionalInterface
    public interface Columns {
        void column(int x, int z, int minY, int maxY);
    }

    private HeightmapReach() {}

    /**
     * Gives {@code out} the columns {@code dab} of a Heightmap stroke of {@code spec} may write, with their ranges; a
     * column under several dabs of the step comes once for each.
     *
     * @throws IllegalArgumentException if {@code spec} is not a Heightmap spec, or a copy lies beyond the integer range of
     *     dab coordinates (as {@link Symmetry#copies})
     */
    public static void forEach(BrushSpec spec, Dab dab, WorldReader world, Columns out) {
        Objects.requireNonNull(out);
        if (spec.heightmap() == null) throw new IllegalArgumentException("Not a Heightmap spec: " + spec.tool());
        HeightmapSpec stamp = spec.heightmap();
        StateSpace states = world.states();
        int worldBottom = world.bottomY();
        int worldTop = world.topYExclusive() - 1;
        Box clip = spec.clip();
        int minY = clip == null ? worldBottom : Math.max(worldBottom, clip.min().y());
        int maxY = clip == null ? worldTop : Math.min(worldTop, clip.max().y());
        int reach = spec.reach();
        for (Dab d : SymmetricStep.of(spec, dab, world).dabs()) {
            if (!TerrainKernel.insideLimit(d)) continue;
            int top = HeightmapKernel.scanTop(spec, d, worldBottom, worldTop);
            int bottom = HeightmapKernel.scanBottom(spec, d, worldBottom, worldTop);
            int x0 = d.blockX() - reach, x1 = d.blockX() + reach;
            int z0 = d.blockZ() - reach, z1 = d.blockZ() + reach;
            if (clip != null) {
                x0 = Math.max(x0, clip.min().x());
                x1 = Math.min(x1, clip.max().x());
                z0 = Math.max(z0, clip.min().z());
                z1 = Math.min(z1, clip.max().z());
            }
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    if (!world.isLoaded(x >> 4, z >> 4)) continue;
                    int h = SurfaceScan.scan(world, states, x, z, top, bottom, null);
                    if (h == SurfaceScan.NONE) continue;
                    long low = stamp.lower() ? (long) h - stamp.heightBlocks() : h;
                    long high = (long) h + TerrainKernel.MAX_PLANT_RUN + (stamp.lower() ? 0 : stamp.heightBlocks());
                    int from = (int) Math.max(minY, low);
                    int to = (int) Math.min(maxY, high);
                    if (from <= to) out.column(x, z, from, to);
                }
            }
        }
    }
}
