package dev.sculptory.core.generate;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.region.Facing;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Objects;

/**
 * The Roof generator: a gable, hip or shed roof over a
 * footprint whose bottom layer is the eaves level. A pure function of the spec and the resolved materials.
 */
public final class RoofKernel {
    public static final int MAX_OVERHANG = 4;
    public static final int MIN_THICKNESS = 1;
    public static final int MAX_THICKNESS = 3;

    private RoofKernel() {}

    public enum Style { GABLE, HIP, SHED }

    /** Which way the ridge runs (gable); a hip's ridge always follows the longer side. */
    public enum Ridge { AUTO, EAST_WEST, NORTH_SOUTH }

    /** A shed roof's low side. */
    public enum Side { NORTH, SOUTH, EAST, WEST }

    /** How steep: 45° in stairs, two up per one across in full blocks, one up per two across in blocks and slabs. */
    public enum Pitch { NORMAL, STEEP, GENTLE }

    /** What happens under the roof inside the footprint, above the eaves. */
    public enum Inside { LEAVE, HOLLOW }

    /** The blocks: the stairs (any horizontal facing and shape), a slab and the full block. */
    public record Materials(BlockDescriptor stairs, BlockDescriptor slab, BlockDescriptor full) {
        public Materials {
            Objects.requireNonNull(stairs);
            Objects.requireNonNull(slab);
            Objects.requireNonNull(full);
        }
    }

    /**
     * The roof to generate.
     *
     * @param footprint the building's box: its bottom layer is the eaves level; its height is ignored
     * @param ridge gable only (a hip's ridge follows the longer side, a shed has none)
     * @param lowSide shed only
     * @param overhang the eaves' reach past the footprint, 0-4
     * @param thickness layers of the full block under the surface, 1-3
     * @param gableWalls fill the triangular ends (gable, shed) with the full block
     */
    public record Spec(Box footprint, Style style, Ridge ridge, Side lowSide, Pitch pitch, int overhang, int thickness,
                       boolean gableWalls, Inside inside, int air) {
        public Spec {
            Objects.requireNonNull(footprint);
            Objects.requireNonNull(style);
            Objects.requireNonNull(ridge);
            Objects.requireNonNull(lowSide);
            Objects.requireNonNull(pitch);
            Objects.requireNonNull(inside);
            if (overhang < 0 || overhang > MAX_OVERHANG) throw new IllegalArgumentException("Overhang " + overhang);
            if (thickness < MIN_THICKNESS || thickness > MAX_THICKNESS) throw new IllegalArgumentException("Thickness " + thickness);
            if (air < 0) throw new IllegalArgumentException("Negative air handle");
        }

        /** The eaves box: the footprint grown by the overhang in x and z (one layer tall, at the eaves level). */
        public Box eaves() {
            BlockPos min = footprint.min(), max = footprint.max();
            return new Box(new BlockPos(min.x() - overhang, min.y(), min.z() - overhang),
                    new BlockPos(max.x() + overhang, min.y(), max.z() + overhang));
        }

        /** The ridge axis a gable gets: as set, or for Auto along the eaves box's longer side. */
        public Ridge gableRidge() {
            if (ridge != Ridge.AUTO) return ridge;
            Box eaves = eaves();
            return eaves.sizeX() >= eaves.sizeZ() ? Ridge.EAST_WEST : Ridge.NORTH_SOUTH;
        }
    }

    /** A column's place on the roof: its row from the eave and the way up the slope ({@code null} on the ridge). */
    private record Row(int index, Facing up) {}

    /**
     * The roof's cells, within {@code [bottomY, topYExclusive)}.
     *
     * @throws GeneratedTooLargeException over {@code maxCells}
     */
    public static GeneratedSource generate(Spec spec, RoofStates states, int bottomY, int topYExclusive, long maxCells) {
        Objects.requireNonNull(spec);
        Objects.requireNonNull(states);
        Box eaves = spec.eaves();
        Box footprint = spec.footprint();
        int y0 = eaves.min().y();
        // Every eaves column holds at least one cell, so an area over the cap is over it before any work; a roof whose
        // eaves start above the build height has nothing to write.
        long area = (long) eaves.sizeX() * eaves.sizeZ();
        if (area > maxCells) throw new GeneratedTooLargeException(maxCells);
        if (y0 >= topYExclusive) return GeneratedSource.empty();
        GeneratedSource.Builder out = GeneratedSource.builder(maxCells);
        Long2ObjectOpenHashMap<Facing> stairFacings = new Long2ObjectOpenHashMap<>();
        // Per eaves column, the roof's lowest cell (indexed (z - minZ) * sizeX + (x - minX)).
        int[] bottoms = new int[(int) area];
        int sizeX = eaves.sizeX();
        int minX = eaves.min().x(), minZ = eaves.min().z();
        // Pass 1: the profile blocks (stairs recorded by facing, shaped in pass 2) and the layers under them.
        for (int z = eaves.min().z(); z <= eaves.max().z(); z++) {
            for (int x = eaves.min().x(); x <= eaves.max().x(); x++) {
                Row row = row(spec, eaves, x, z);
                int bottom;
                switch (spec.pitch()) {
                    case NORMAL -> {
                        int y = y0 + row.index();
                        bottom = y;
                        if (row.up() == null) {
                            put(out, x, y, z, states.full(), bottomY, topYExclusive);
                        } else if (y >= bottomY && y < topYExclusive) {
                            stairFacings.put(cell(x, y, z), row.up());
                        }
                    }
                    case STEEP -> {
                        bottom = y0 + 2 * row.index();
                        put(out, x, bottom, z, states.full(), bottomY, topYExclusive);
                        put(out, x, bottom + 1, z, states.full(), bottomY, topYExclusive);
                    }
                    case GENTLE -> {
                        boolean slab = (row.index() & 1) == 0;
                        bottom = y0 + row.index() / 2;
                        put(out, x, bottom, z, slab ? states.slab() : states.full(), bottomY, topYExclusive);
                    }
                    default -> throw new AssertionError(spec.pitch());
                }
                for (int k = 1; k < spec.thickness(); k++) {
                    put(out, x, bottom - k, z, states.full(), bottomY, topYExclusive);
                }
                bottoms[(z - minZ) * sizeX + (x - minX)] = bottom - (spec.thickness() - 1);
            }
        }
        // Pass 2: the stairs, shaped as vanilla would shape them among each other.
        StairShapes.Stairs lookup = (x, y, z) -> stairFacings.get(cell(x, y, z));
        for (int z = eaves.min().z(); z <= eaves.max().z(); z++) {
            for (int x = eaves.min().x(); x <= eaves.max().x(); x++) {
                Row row = row(spec, eaves, x, z);
                if (spec.pitch() != Pitch.NORMAL || row.up() == null) continue;
                int y = y0 + row.index();
                Facing facing = stairFacings.get(cell(x, y, z));
                if (facing == null) continue;
                put(out, x, y, z, states.stair(facing, StairShapes.shape(lookup, x, y, z, facing)), bottomY, topYExclusive);
            }
        }
        // Gable walls: the footprint's end columns, from the eaves up to under the roof.
        if (spec.gableWalls() && spec.style() != Style.HIP) {
            boolean endsAlongX = spec.style() == Style.GABLE
                    ? spec.gableRidge() == Ridge.EAST_WEST
                    : spec.lowSide() == Side.NORTH || spec.lowSide() == Side.SOUTH;
            for (int z = footprint.min().z(); z <= footprint.max().z(); z++) {
                for (int x = footprint.min().x(); x <= footprint.max().x(); x++) {
                    boolean end = endsAlongX
                            ? x == footprint.min().x() || x == footprint.max().x()
                            : z == footprint.min().z() || z == footprint.max().z();
                    if (!end) continue;
                    fillUnder(out, x, z, y0, bottoms[(z - minZ) * sizeX + (x - minX)], states.full(), bottomY, topYExclusive);
                }
            }
        }
        if (spec.inside() == Inside.HOLLOW) {
            for (int z = footprint.min().z(); z <= footprint.max().z(); z++) {
                for (int x = footprint.min().x(); x <= footprint.max().x(); x++) {
                    fillUnder(out, x, z, y0, bottoms[(z - minZ) * sizeX + (x - minX)], spec.air(), bottomY, topYExclusive);
                }
            }
        }
        return out.build();
    }

    /** The row and up-slope facing of column (x, z) of the eaves box. */
    static Row row(Spec spec, Box eaves, int x, int z) {
        int minX = eaves.min().x(), maxX = eaves.max().x(), minZ = eaves.min().z(), maxZ = eaves.max().z();
        int fromMinX = x - minX, fromMaxX = maxX - x, fromMinZ = z - minZ, fromMaxZ = maxZ - z;
        int dx = Math.min(fromMinX, fromMaxX), dz = Math.min(fromMinZ, fromMaxZ);
        Facing alongZ = fromMinZ < fromMaxZ ? Facing.SOUTH : Facing.NORTH;
        Facing alongX = fromMinX < fromMaxX ? Facing.EAST : Facing.WEST;
        return switch (spec.style()) {
            case GABLE -> {
                if (spec.gableRidge() == Ridge.EAST_WEST) {
                    boolean ridge = (eaves.sizeZ() & 1) == 1 && fromMinZ == fromMaxZ;
                    yield new Row(dz, ridge ? null : alongZ);
                }
                boolean ridge = (eaves.sizeX() & 1) == 1 && fromMinX == fromMaxX;
                yield new Row(dx, ridge ? null : alongX);
            }
            case HIP -> {
                int shorter = Math.min(eaves.sizeX(), eaves.sizeZ());
                int index = Math.min(dx, dz);
                boolean ridge = (shorter & 1) == 1 && index == (shorter - 1) / 2;
                yield new Row(index, ridge ? null : dx < dz ? alongX : alongZ);
            }
            case SHED -> switch (spec.lowSide()) {
                case NORTH -> new Row(fromMinZ, Facing.SOUTH);
                case SOUTH -> new Row(fromMaxZ, Facing.NORTH);
                case WEST -> new Row(fromMinX, Facing.EAST);
                case EAST -> new Row(fromMaxX, Facing.WEST);
            };
        };
    }

    private static void put(GeneratedSource.Builder out, int x, int y, int z, int state, int bottomY, int topYExclusive) {
        if (y >= bottomY && y < topYExclusive) out.set(x, y, z, state);
    }

    /** Sets {@code state} in the cells of column (x, z) from {@code fromY} up to below {@code belowY} that are still free. */
    private static void fillUnder(GeneratedSource.Builder out, int x, int z, int fromY, int belowY, int state, int bottomY,
                                  int topYExclusive) {
        for (int y = fromY; y < belowY; y++) {
            if (y < bottomY || y >= topYExclusive || out.has(x, y, z)) continue;
            out.set(x, y, z, state);
        }
    }

    /** A cell key: 26 bits of x and z (the world reaches ±30 million) and 12 of y (a roof spans far less than 4096). */
    private static long cell(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
