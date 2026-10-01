package dev.sculptory.core.brush;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.world.WorldReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A dab and its symmetric copies as the kernel applies them: each copy stands on the ground where it lands
 * (rather than keeping the dab's height). Every copy is located against the world as it is before
 * the step, so the step stays one order-independent snapshot, and the client's prediction and the server locate them
 * with this same function.
 *
 * <p><b>The ground search</b> ({@link #ground}). The copy's centre column (the block column holding its centre) is
 * scanned with {@link SurfaceScan#scan}, the surface the brushes sculpt and scatter stands on, from
 * {@value #GROUND_SEARCH} blocks above the dab's block y down to {@value #GROUND_SEARCH} below it, clamped to the build
 * height. So:
 * <ul>
 *   <li>the copy lands on the topmost ground below open cells in that window: on a slope or at a cliff, the top of its
 *       own column; under an overhang, on top of the overhang;</li>
 *   <li>fluids are open cells: over water or lava the copy lands on the ground beneath them, where the brush sculpts
 *       (and Lower refills) as it does for any column under water;</li>
 *   <li>there is <b>no ground</b> when the window's top cell is not open (terrain rising more than
 *       {@value #GROUND_SEARCH} blocks above the dab, or the rock above a cave), the scan meets a structure first
 *       (stairs, a fence, a block with a block entity), it finds only open cells, or the column's chunk is not loaded.
 *       The copy is then left out of the step: it writes nothing anywhere (it is never applied at another height), and
 *       {@link #noGround} lists it so the caller can say so.</li>
 * </ul>
 * A copy whose ground is at y stands at {@code y + 1} (y16 = 16 × (y + 1)), where a dab aimed at the top of that ground
 * lies; its x, z, index and pressure are those {@link Symmetry#copies} gives. Its own reads and writes are then those of
 * any dab at that height (the kernel's scan window of {@code radius + 8} around it, its work caps).
 *
 * <p><b>The Surface mode</b> ({@link SculptMode#SURFACE}, 2026-09-29) first looks at the copy's own point: when a
 * surface passes through its block ({@link #surfaceAt}: a ground and an open cell among the block and its six
 * neighbours) the copy stays there, the exact image of the dab, so a mirrored wall or ceiling is worked where the
 * mirror puts it. Otherwise it stands on the ground in its column as above ({@link #locate}).
 *
 * <p><b>The Shape brush</b> ({@link BrushTool#SHAPE}) searches nothing: its copies keep the dab's height and are the
 * exact mirror or turned images of the dab's shape, so a symmetric build stays symmetric whatever the ground does
 * ({@link ShapeStamp#placements}).
 *
 * <p>The dab itself keeps its height: a step without copies, and the dab of every step, is applied as before. Copies
 * whose footprint misses the clip box's x/z range (they write nothing) and copies beyond the kernel's horizontal limit
 * (they fail the step) are not searched and stay as {@link Symmetry#copies} gives them.
 *
 * <p><b>Cost.</b> One column scan per copy (at most three per step), at most {@code 2 × GROUND_SEARCH + 1} cells each and
 * usually a few, as the scan starts at the reader's {@link WorldReader#heightHint}.
 *
 * @param dabs the step for {@link BrushKernel#applyStep}: the dab, then each copy that found ground, in the order of
 *     {@link Symmetry#copies}
 * @param searched the copies whose column was searched, as {@link Symmetry#copies} gives them (at the dab's height)
 * @param noGround those of {@code searched} that found no ground, left out of {@code dabs}
 */
public record SymmetricStep(List<Dab> dabs, List<Dab> searched, List<Dab> noGround) {
    /** How far above and below the dab's block y a copy's ground is looked for. */
    public static final int GROUND_SEARCH = 64;

    public SymmetricStep {
        dabs = List.copyOf(dabs);
        searched = List.copyOf(searched);
        noGround = List.copyOf(noGround);
    }

    /**
     * The step of {@code dab} under {@code spec}'s symmetry, its copies located against {@code world}.
     *
     * @throws IllegalArgumentException if a copy lies beyond the integer range of dab coordinates (as
     *     {@link Symmetry#copies})
     */
    public static SymmetricStep of(BrushSpec spec, Dab dab, WorldReader world) {
        Objects.requireNonNull(spec);
        Objects.requireNonNull(world);
        List<Dab> copies = spec.symmetry().copies(dab);
        // The Shape brush's copies are the exact images of its shape (ShapeStamp): they keep the dab's height.
        if (copies.size() == 1 || spec.tool() == BrushTool.SHAPE) return new SymmetricStep(copies, List.of(), List.of());
        List<Dab> dabs = new ArrayList<>(copies.size());
        List<Dab> searched = new ArrayList<>(copies.size() - 1);
        List<Dab> noGround = new ArrayList<>(0);
        dabs.add(copies.get(0));
        for (int i = 1; i < copies.size(); i++) {
            Dab copy = copies.get(i);
            if (!TerrainKernel.insideLimit(copy) || !TerrainKernel.reachesClip(spec, copy)) {
                dabs.add(copy);
                continue;
            }
            searched.add(copy);
            Dab located = locate(spec, world, copy);
            if (located == null) {
                noGround.add(copy);
            } else {
                dabs.add(located);
            }
        }
        return new SymmetricStep(dabs, searched, noGround);
    }

    /**
     * Where a copy of a dab (as {@link Symmetry#copies} gives it, at the dab's height) stands, or {@code null} when it
     * finds no ground: in the Surface mode ({@link BrushSpec#surface()}) at its own point when a surface passes there
     * ({@link #surfaceAt}: a mirrored wall or ceiling, or ground at the dab's height), else, as in the Terrain mode, on
     * the ground in its column ({@link #ground}). A Surface copy whose column or a column beside it is in a chunk the
     * reader lacks finds nothing (a client without those chunks leaves it out rather than guess; the server has them).
     */
    public static Dab locate(BrushSpec spec, WorldReader world, Dab copy) {
        if (spec.surface()) {
            // Unknown around the copy's point (a client without those chunks): left out, never guessed at.
            if (!aroundLoaded(world, copy.blockX(), copy.blockZ())) return null;
            if (surfaceAt(world, copy.blockX(), copy.blockY(), copy.blockZ())) return copy;
        }
        int y = ground(world, copy.blockX(), copy.blockZ(), copy.blockY());
        return y == SurfaceScan.NONE ? null : new Dab(copy.index(), copy.x16(), (y + 1) * 16, copy.z16(), copy.pressure());
    }

    /**
     * Whether a surface passes through block (x, y, z): it and its six neighbours hold both a ground cell and an open
     * cell ({@link SurfaceScan}). False when one of their chunks is not loaded; cells outside the build height count as
     * neither. Reads only those seven cells, all inside {@link #searchBox} for the Surface mode.
     */
    public static boolean surfaceAt(WorldReader world, int x, int y, int z) {
        if (!aroundLoaded(world, x, z)) return false;
        int[][] cells = {{0, 0, 0}, {0, -1, 0}, {0, 1, 0}, {-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {0, 0, 1}};
        boolean ground = false, open = false;
        for (int[] cell : cells) {
            int cy = y + cell[1];
            if (cy < world.bottomY() || cy >= world.topYExclusive()) continue;
            int flags = world.states().flags(world.get(x + cell[0], cy, z + cell[2]));
            ground |= SurfaceScan.ground(flags);
            open |= SurfaceScan.open(flags);
        }
        return ground && open;
    }

    /** Whether the chunks of column (x, z) and the columns around it are loaded. */
    private static boolean aroundLoaded(WorldReader world, int x, int z) {
        for (int cx = (x - 1) >> 4; cx <= (x + 1) >> 4; cx++) {
            for (int cz = (z - 1) >> 4; cz <= (z + 1) >> 4; cz++) {
                if (!world.isLoaded(cx, cz)) return false;
            }
        }
        return true;
    }

    /**
     * Every cell {@link #of} may read to locate a copy of {@code spec} centred on column (x, z) of a dab at block y
     * {@code dabY}: {@link #searchColumn}, and in the Surface mode the columns around it too ({@link #surfaceAt}).
     */
    public static Box searchBox(BrushSpec spec, int x, int z, int dabY, int bottomY, int topYExclusive) {
        Box column = searchColumn(x, z, dabY, bottomY, topYExclusive);
        if (!spec.surface()) return column;
        return new Box(new BlockPos(x - 1, column.min().y(), z - 1), new BlockPos(x + 1, column.max().y(), z + 1));
    }

    /**
     * The ground y a copy centred on column (x, z) of a dab at block y {@code dabY} stands on, or {@link SurfaceScan#NONE}
     * (see the class comment). Reads only {@link #searchColumn}, and nothing when the column's chunk is not loaded.
     */
    public static int ground(WorldReader world, int x, int z, int dabY) {
        if (!world.isLoaded(x >> 4, z >> 4)) return SurfaceScan.NONE;
        int bottom = world.bottomY(), top = world.topYExclusive() - 1;
        return SurfaceScan.scan(world, world.states(), x, z, clamp((long) dabY + GROUND_SEARCH, bottom, top),
                clamp((long) dabY - GROUND_SEARCH, bottom, top), null);
    }

    /**
     * Every cell {@link #ground} may read for a copy centred on column (x, z) of a dab at block y {@code dabY}: one
     * column, {@value #GROUND_SEARCH} blocks above and below, clamped to {@code [bottomY, topYExclusive)}.
     */
    public static Box searchColumn(int x, int z, int dabY, int bottomY, int topYExclusive) {
        int top = topYExclusive - 1;
        return new Box(new BlockPos(x, clamp((long) dabY - GROUND_SEARCH, bottomY, top), z),
                new BlockPos(x, clamp((long) dabY + GROUND_SEARCH, bottomY, top), z));
    }

    private static int clamp(long value, int min, int max) {
        return (int) Math.max(min, Math.min(max, value));
    }
}
