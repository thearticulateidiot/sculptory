package dev.sculptory.core.brush;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Where the Shape brush's shapes go ({@link BrushTool#SHAPE}). Shared by the kernel, the server's area checks and the
 * client's cursor, so all three agree.
 *
 * <p><b>The box.</b> A dab's point (1/16 block) is the centre of the shape's box. The box's sizes are the diameter
 * {@code d = 2 × radius + 1} across and {@link ShapeSpec#axialSize} along the facing axis (for a sphere, {@code d}
 * every way). Along each axis the box is the run of {@code size} cells whose centre is nearest the dab's point, ties
 * toward positive: its first cell is {@code floorDiv(c16 - 8 × size + 8, 16)}. An odd size is therefore centred on the
 * dab's block whatever the point's fraction, and an even size (only a height can be even) on the nearest block edge.
 * The client lays each dab exactly on such a centre, so the rounding never decides anything there.
 *
 * <p><b>Symmetry.</b> {@link #placements} gives the dab's shape and its images under the spec's {@link Symmetry}, in
 * the order of {@link Symmetry#images()}. The dab's point is first fitted to its box's centre ({@link #fit}: a block
 * centre along an odd side, the nearest block edge along an even one, which changes nothing about its own box); each
 * image's centre is then the image of that centre, so its box is exactly the mirrored or turned box, whatever point the
 * dab carried. Its facing is mirrored or turned with it (a cone facing east mirrors to one facing west), and it keeps the
 * dab's height: the Shape brush's copies do not follow the terrain. An image equal to an earlier one is dropped, but one
 * at the same place with another facing is kept, so a cone on the mirror plane gets its mirrored twin.
 */
public final class ShapeStamp {
    private ShapeStamp() {}

    /**
     * One shape a dab places. For a sphere or a cube the facing is always {@link Facing#UP} (their cells depend
     * on the box alone), so equal shapes are equal placements.
     */
    public record Placement(Box box, ShapeSpec.Kind kind, Facing facing) {
        public Placement {
            Objects.requireNonNull(box);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(facing);
            if (kind == ShapeSpec.Kind.SPHERE || kind == ShapeSpec.Kind.CUBE) facing = Facing.UP;
        }

        /**
         * The contract's region of these cells: {@link Region.Cuboid} for a cube, else the {@link Region.Shape} of the
         * box (a sphere is its {@link ShapeKind#ELLIPSOID}; cylinder, cone and pyramid their own kinds), so a brush shape
         * and a selection shape of the same box are the same cells.
         */
        public Region region() {
            return switch (kind) {
                case SPHERE -> new Region.Shape(box, ShapeKind.ELLIPSOID, facing);
                case CYLINDER -> new Region.Shape(box, ShapeKind.CYLINDER, facing);
                case CONE -> new Region.Shape(box, ShapeKind.CONE, facing);
                case PYRAMID -> new Region.Shape(box, ShapeKind.PYRAMID, facing);
                case CUBE -> new Region.Cuboid(box);
            };
        }
    }

    /** The box sizes {x, y, z} of {@code spec}'s shape facing {@code facing}. */
    public static int[] sizes(BrushSpec spec, Facing facing) {
        return sizes(requireShape(spec), spec.radius(), facing);
    }

    /** The box sizes {x, y, z} of {@code shape} with {@code radius}, facing {@code facing}. */
    public static int[] sizes(ShapeSpec shape, int radius, Facing facing) {
        int across = 2 * radius + 1;
        int along = shape.axialSize(radius);
        return switch (Objects.requireNonNull(facing)) {
            case UP, DOWN -> new int[] {across, along, across};
            case EAST, WEST -> new int[] {along, across, across};
            case NORTH, SOUTH -> new int[] {across, across, along};
        };
    }

    /** The first cell of the run of {@code size} cells whose centre is nearest {@code c16} (1/16 block). */
    public static int boxMin(long c16, int size) {
        return (int) Math.floorDiv(c16 - 8L * size + 8, 16);
    }

    /**
     * The centre (1/16 block) of that run: {@code c16} fitted to a block centre for an odd size (the block holding it),
     * to the nearest block edge for an even one (ties toward positive).
     */
    public static long fit(long c16, int size) {
        return size % 2 == 1 ? 16 * Math.floorDiv(c16, 16) + 8 : 16 * Math.floorDiv(c16 + 8, 16);
    }

    /** The box of {@code spec}'s shape facing {@code facing} centred at (x16, y16, z16), in 1/16 block. */
    public static Box box(BrushSpec spec, Facing facing, long x16, long y16, long z16) {
        return box(requireShape(spec), spec.radius(), facing, x16, y16, z16);
    }

    /** The box of {@code shape} with {@code radius} facing {@code facing} centred at (x16, y16, z16), in 1/16 block. */
    public static Box box(ShapeSpec shape, int radius, Facing facing, long x16, long y16, long z16) {
        int[] size = sizes(shape, radius, facing);
        int x0 = boxMin(x16, size[0]), y0 = boxMin(y16, size[1]), z0 = boxMin(z16, size[2]);
        return new Box(new BlockPos(x0, y0, z0), new BlockPos(x0 + size[0] - 1, y0 + size[1] - 1, z0 + size[2] - 1));
    }

    /** The shape {@code dab} places, without its symmetric images. */
    public static Placement placement(BrushSpec spec, Dab dab) {
        ShapeSpec shape = requireShape(spec);
        return new Placement(box(spec, shape.facing(), dab.x16(), dab.y16(), dab.z16()), shape.kind(), shape.facing());
    }

    /**
     * The shape {@code dab} places and its images under the spec's symmetry, each once, in the order of
     * {@link Symmetry#images()} (see the class comment).
     *
     * @throws IllegalArgumentException if an image's centre lies beyond the integer range of dab coordinates
     */
    public static List<Placement> placements(BrushSpec spec, Dab dab) {
        return placements(requireShape(spec), spec.radius(), spec.symmetry(), dab);
    }

    /** {@link #placements(BrushSpec, Dab)} for {@code shape} with {@code radius} under {@code symmetry}. */
    public static List<Placement> placements(ShapeSpec shape, int radius, Symmetry symmetry, Dab dab) {
        return imagedPlacements(shape, radius, symmetry, dab).stream().map(Imaged::placement).toList();
    }

    /** A placement and the symmetry image that made it ({@link Symmetry.Image#IDENTITY} for the dab's own). */
    public record Imaged(Placement placement, Symmetry.Image image) {
        public Imaged {
            Objects.requireNonNull(placement);
            Objects.requireNonNull(image);
        }
    }

    /**
     * {@link #placements(ShapeSpec, int, Symmetry, Dab)}, each with the image that made it (a laid-out mix is read at a
     * copy's cells' pre-images under it).
     */
    public static List<Imaged> imagedPlacements(ShapeSpec shape, int radius, Symmetry symmetry, Dab dab) {
        Objects.requireNonNull(shape);
        Objects.requireNonNull(symmetry);
        Objects.requireNonNull(dab);
        int[] size = sizes(shape, radius, shape.facing());
        long cx = fit(dab.x16(), size[0]), cz = fit(dab.z16(), size[2]);
        List<Imaged> placements = new ArrayList<>(Symmetry.MAX_COPIES);
        for (Symmetry.Image image : symmetry.images()) {
            long x16 = symmetry.imageX(image, cx, cz);
            long z16 = symmetry.imageZ(image, cx, cz);
            if (x16 != (int) x16 || z16 != (int) z16) throw new IllegalArgumentException("Shape copy outside the world: " + dab);
            Facing facing = image(image, shape.facing());
            Placement placement = new Placement(box(shape, radius, facing, x16, dab.y16(), z16), shape.kind(), facing);
            if (placements.stream().noneMatch(earlier -> earlier.placement().equals(placement))) {
                placements.add(new Imaged(placement, image));
            }
        }
        return List.copyOf(placements);
    }

    /**
     * Cells in one unit of queued brush work: one part of a step ({@link ShapeStep#PART_CELLS}, about 4 ms of the brush
     * lane), so a player's queue in units is bounded in lane time.
     */
    public static final int WORK_UNIT_CELLS = ShapeStep.PART_CELLS;
    /**
     * The most cells one step (a dab and its copies) may visit: four of the largest box, 4 × 65³. Every valid spec is
     * within it; the server refuses a stroke over it as a backstop.
     */
    public static final long MAX_STEP_CELLS = 4L * ShapeSpec.MAX_HEIGHT * ShapeSpec.MAX_HEIGHT * ShapeSpec.MAX_HEIGHT;

    /**
     * The units of queued brush work {@code placements} placements of {@code cellsEach} cells cost: each at least one,
     * and one per {@value #WORK_UNIT_CELLS} cells begun. The server bounds each player's queue in these units, and the
     * client paces its dabs in them.
     */
    public static int units(int placements, long cellsEach) {
        long each = Math.max(1, (cellsEach + WORK_UNIT_CELLS - 1) / WORK_UNIT_CELLS);
        return (int) Math.min(Integer.MAX_VALUE, placements * each);
    }

    /**
     * How many cells one dab of {@code spec} writes at most, without its copies: its shape's cells (with a hollow
     * thickness, its shell's), before the mode, the clip box and the build height leave some out. The same for every
     * position and facing. One pass over the shape's cells.
     */
    public static long cellCount(BrushSpec spec) {
        return cellCount(requireShape(spec), spec.radius());
    }

    /**
     * {@link #cellCount(BrushSpec)} for {@code shape} with {@code radius}. The count does not depend on the facing (the
     * voxelization is exactly symmetric under turning the box), so it is counted facing up.
     */
    public static long cellCount(ShapeSpec shape, int radius) {
        Placement placement = new Placement(box(shape, radius, Facing.UP, 8, 8, 8), shape.kind(), Facing.UP);
        return cells(placement, shape.hollow()).count();
    }

    /** The cells of one placement, as the kernel writes them before the mode and the limits leave any out. */
    public static final class Cells {
        private final ShapeKernel.Stamp stamp;

        private Cells(ShapeKernel.Stamp stamp) {
            this.stamp = stamp;
        }

        /** Whether the placement writes cell (x, y, z) (world coordinates). */
        public boolean contains(int x, int y, int z) {
            return stamp.writes(x, y, z);
        }

        /** How many cells it writes. */
        public long count() {
            return stamp.count();
        }
    }

    /**
     * The cells {@code placement} writes with a hollow thickness {@code hollow} (0: the solid shape). Built once (one
     * pass over the box's rows); {@link Cells#contains} is then constant time.
     */
    public static Cells cells(Placement placement, int hollow) {
        return new Cells(new ShapeKernel.Stamp(Objects.requireNonNull(placement), hollow));
    }

    /** The facing {@code image} mirrors or turns {@code facing} into (up and down stay). */
    public static Facing image(Symmetry.Image image, Facing facing) {
        return Symmetry.imageFacing(image, facing);
    }

    /**
     * A bound on the cells a dab of {@code spec} (or one of its copies, at the copy's position) may write with any
     * facing, as {@link BrushSpec#reach()} describes it: the dab's block and {@code reach()} blocks around it every way, y
     * clamped to {@code [bottomY, topYExclusive)}. What resync footprints cover; the exact cells are the placements'.
     */
    public static Box reachBox(BrushSpec spec, Dab dab, int bottomY, int topYExclusive) {
        requireShape(spec);
        int m = spec.reach();
        int top = topYExclusive - 1;
        int y0 = (int) Math.max(bottomY, Math.min(top, (long) dab.blockY() - m));
        int y1 = (int) Math.max(bottomY, Math.min(top, (long) dab.blockY() + m));
        return new Box(new BlockPos(dab.blockX() - m, y0, dab.blockZ() - m), new BlockPos(dab.blockX() + m, y1, dab.blockZ() + m));
    }

    private static ShapeSpec requireShape(BrushSpec spec) {
        if (spec.shapeSpec() == null) throw new IllegalArgumentException("Not a Shape brush: " + spec.tool());
        return spec.shapeSpec();
    }
}
