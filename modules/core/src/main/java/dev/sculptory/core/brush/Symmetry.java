package dev.sculptory.core.brush;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Brush symmetry: every dab of a stroke is replicated around a vertical axis at ({@code x2 / 2}, {@code z2 / 2}),
 * so the centre has half-block precision (an odd value is a block centre, an even one a block edge). The copies
 * {@link #copies} gives keep the dab's height, pressure and index; only x and z move. The brush kernel then stands
 * each copy on the ground where it lands ({@link SymmetricStep}).
 *
 * <ul>
 *   <li>{@link Mode#MIRROR_X}: mirrored across the plane {@code x = cx}, which runs north-south: east and west swap
 *       (as {@code Mirror.X} does).</li>
 *   <li>{@link Mode#MIRROR_Z}: mirrored across the plane {@code z = cz}, which runs east-west: north and south swap
 *       (as {@code Mirror.Z} does).</li>
 *   <li>{@link Mode#MIRROR_XZ}: both mirrors, four copies.</li>
 *   <li>{@link Mode#ROTATE_2}: a half turn around the centre, two copies.</li>
 *   <li>{@link Mode#ROTATE_4}: quarter turns clockwise seen from above (north to east), four copies. Block columns
 *       only map onto block columns when both coordinates are block centres or both block edges, so the centre
 *       must be one of those.</li>
 * </ul>
 *
 * <p>A column's centre {@code 16c + 8} (in 1/16 block) maps exactly onto another column's centre, so a copy's
 * footprint is exactly the image of the dab's footprint. Copies that land on the same point as another (a dab on
 * a mirror plane, or on the centre) are dropped: {@link #copies} never lists a position twice.
 *
 * @param x2 twice the centre's x; within ±{@value #MAX_CENTRE2}
 * @param z2 twice the centre's z; within ±{@value #MAX_CENTRE2}
 */
public record Symmetry(Mode mode, int x2, int z2) {
    /** Symmetry modes. Wire order: append only. */
    public enum Mode {
        OFF(1),
        MIRROR_X(2),
        MIRROR_Z(2),
        MIRROR_XZ(4),
        ROTATE_2(2),
        ROTATE_4(4);

        private final int copies;

        Mode(int copies) {
            this.copies = copies;
        }

        /** How many copies a dab off every plane makes, the dab itself included. */
        public int copies() {
            return copies;
        }
    }

    /**
     * One image of a mode: how it maps a horizontal offset (dx, dz) from the centre, to
     * {@code (xx·dx + xz·dz, zx·dx + zz·dz)}. The Shape brush turns and mirrors a shape's facing with it.
     */
    public enum Image {
        IDENTITY(1, 0, 0, 1),
        /** Mirrored across the plane x = cx: east and west swap. */
        MIRROR_X(-1, 0, 0, 1),
        /** Mirrored across the plane z = cz: north and south swap. */
        MIRROR_Z(1, 0, 0, -1),
        HALF_TURN(-1, 0, 0, -1),
        /** A quarter turn clockwise seen from above: north to east, east to south. */
        QUARTER_CW(0, -1, 1, 0),
        QUARTER_CCW(0, 1, -1, 0);

        private final int xx, xz, zx, zz;

        Image(int xx, int xz, int zx, int zz) {
            this.xx = xx;
            this.xz = xz;
            this.zx = zx;
            this.zz = zz;
        }

        /** The image of offset (dx, dz): its x. */
        public long x(long dx, long dz) {
            return xx * dx + xz * dz;
        }

        /** The image of offset (dx, dz): its z. */
        public long z(long dx, long dz) {
            return zx * dx + zz * dz;
        }

        /** Whether the image swaps the x and z axes (a quarter turn). */
        public boolean swapsAxes() {
            return xx == 0;
        }

        /** The image that maps this one's images back: the mirrors and the half turn are their own, the turns swap. */
        public Image inverse() {
            return switch (this) {
                case QUARTER_CW -> QUARTER_CCW;
                case QUARTER_CCW -> QUARTER_CW;
                default -> this;
            };
        }

        /**
         * The image as the transform a paste or move applies: a mirror across x is {@link Mirror#X}, across z {@link Mirror#Z}, and the turns are quarter turns
         * clockwise. It maps block states with {@link Transform#applyToState} and composes with an op's own transform
         * through {@link Transform#compose}.
         */
        public Transform transform() {
            return switch (this) {
                case IDENTITY -> Transform.IDENTITY;
                case MIRROR_X -> new Transform(0, Mirror.X);
                case MIRROR_Z -> new Transform(0, Mirror.Z);
                case HALF_TURN -> Transform.rotation(2);
                case QUARTER_CW -> Transform.rotation(1);
                case QUARTER_CCW -> Transform.rotation(3);
            };
        }
    }

    /** No symmetry. */
    public static final Symmetry NONE = new Symmetry(Mode.OFF, 0, 0);
    /** Largest |x2| or |z2|: a centre within the kernel's dab limit (2²⁵ blocks). */
    public static final int MAX_CENTRE2 = 1 << 26;
    /** Most dabs one step holds: a dab and its copies. */
    public static final int MAX_COPIES = 4;

    /** {@link Mode#OFF} has no centre, and is kept as (0, 0) whatever is given. */
    public Symmetry {
        Objects.requireNonNull(mode);
        if (mode == Mode.OFF) {
            x2 = 0;
            z2 = 0;
        }
        if (Math.abs((long) x2) > MAX_CENTRE2 || Math.abs((long) z2) > MAX_CENTRE2) {
            throw new IllegalArgumentException("Symmetry centre out of range: " + x2 / 2.0 + ", " + z2 / 2.0);
        }
        if (mode == Mode.ROTATE_4 && ((x2 ^ z2) & 1) != 0) {
            throw new IllegalArgumentException("Rotate 4 needs a centre on a block centre or a block corner, not "
                    + x2 / 2.0 + ", " + z2 / 2.0);
        }
    }

    /** The centre's x in blocks. */
    public double centreX() {
        return x2 / 2.0;
    }

    /** The centre's z in blocks. */
    public double centreZ() {
        return z2 / 2.0;
    }

    public boolean isOff() {
        return mode == Mode.OFF;
    }

    /**
     * The dab and its copies, the dab first, then the images in the mode's order (mirror x, mirror z, both; or each
     * turn), without repeats. Each copy keeps the dab's index, height and pressure.
     *
     * @throws IllegalArgumentException if a copy lies beyond the integer range of dab coordinates
     */
    public List<Dab> copies(Dab dab) {
        Objects.requireNonNull(dab);
        long[] images = images(dab.x16(), dab.z16());
        List<Dab> copies = new ArrayList<>(images.length / 2);
        for (int i = 0; i < images.length; i += 2) {
            long x16 = images[i], z16 = images[i + 1];
            if (x16 != (int) x16 || z16 != (int) z16) throw new IllegalArgumentException("Dab copy outside the world: " + dab);
            if (i > 0 && seen(images, i)) continue;
            copies.add(i == 0 ? dab : new Dab(dab.index(), (int) x16, dab.y16(), (int) z16, dab.pressure()));
        }
        return List.copyOf(copies);
    }

    /** How many dabs {@link #copies} gives for {@code dab} (1 to {@value #MAX_COPIES}); never throws. */
    public int copyCount(Dab dab) {
        long[] images = images(dab.x16(), dab.z16());
        int count = 1;
        for (int i = 2; i < images.length; i += 2) {
            if (!seen(images, i)) count++;
        }
        return count;
    }

    /** Whether image {@code i} (an even index into {@code images}) repeats an earlier one. */
    private static boolean seen(long[] images, int i) {
        for (int j = 0; j < i; j += 2) {
            if (images[j] == images[i] && images[j + 1] == images[i + 1]) return true;
        }
        return false;
    }

    /**
     * The mode's images in the order of {@link #copies}, the identity first: Mirror X {@code [IDENTITY, MIRROR_X]},
     * Mirror Z {@code [IDENTITY, MIRROR_Z]}, both {@code [IDENTITY, MIRROR_X, MIRROR_Z, HALF_TURN]}, Rotate 2
     * {@code [IDENTITY, HALF_TURN]}, Rotate 4 {@code [IDENTITY, QUARTER_CW, HALF_TURN, QUARTER_CCW]}.
     */
    public List<Image> images() {
        return switch (mode) {
            case OFF -> List.of(Image.IDENTITY);
            case MIRROR_X -> List.of(Image.IDENTITY, Image.MIRROR_X);
            case MIRROR_Z -> List.of(Image.IDENTITY, Image.MIRROR_Z);
            case MIRROR_XZ -> List.of(Image.IDENTITY, Image.MIRROR_X, Image.MIRROR_Z, Image.HALF_TURN);
            case ROTATE_2 -> List.of(Image.IDENTITY, Image.HALF_TURN);
            case ROTATE_4 -> List.of(Image.IDENTITY, Image.QUARTER_CW, Image.HALF_TURN, Image.QUARTER_CCW);
        };
    }

    /**
     * The first of the mode's {@link #images()} that maps {@code first}'s point onto {@code copy}'s (x and z; heights
     * may differ, as copies follow the terrain), or {@link Image#IDENTITY} when none does (a step listing dabs that are
     * not one dab and its copies).
     */
    public Image imageOf(Dab first, Dab copy) {
        for (Image image : images()) {
            if (imageX(image, first.x16(), first.z16()) == copy.x16() && imageZ(image, first.x16(), first.z16()) == copy.z16()) {
                return image;
            }
        }
        return Image.IDENTITY;
    }

    /** The x of point (x16, z16)'s image under {@code image} about the centre, in 1/16 block. */
    public long imageX(Image image, long x16, long z16) {
        long cx = 8L * x2, cz = 8L * z2;
        return cx + image.x(x16 - cx, z16 - cz);
    }

    /** The z of point (x16, z16)'s image under {@code image} about the centre, in 1/16 block. */
    public long imageZ(Image image, long x16, long z16) {
        long cx = 8L * x2, cz = 8L * z2;
        return cz + image.z(x16 - cx, z16 - cz);
    }

    // ---- Block cells and regions ----

    /**
     * The x of block cell (x, z)'s image under {@code image}: where the cell's centre point lands. Mirrored about the
     * plane {@code x = x2 / 2} a cell lands on {@code x2 - 1 - x}; a quarter turn clockwise about the centre lands
     * (x, z) on {@code ((x2 + z2) / 2 - z - 1, (z2 - x2) / 2 + x)}, a block cell exactly because Rotate 4 needs
     * {@code x2 ≡ z2 (mod 2)}. Never overflows (a long); a result beyond the int range is the caller's to refuse.
     */
    public long cellX(Image image, int x, int z) {
        return Math.floorDiv(imageX(image, 16L * x + 8, 16L * z + 8) - 8, 16);
    }

    /** The z of block cell (x, z)'s image under {@code image} ({@link #cellX}). */
    public long cellZ(Image image, int x, int z) {
        return Math.floorDiv(imageZ(image, 16L * x + 8, 16L * z + 8) - 8, 16);
    }

    /**
     * The image of {@code box} under {@code image}: the box holding the images of its cells (two opposite corners
     * mapped, then min and max; y is kept).
     *
     * @throws IllegalArgumentException if the image leaves the int coordinate range
     */
    public Box imageBox(Image image, Box box) {
        Objects.requireNonNull(box);
        long ax = cellX(image, box.min().x(), box.min().z()), az = cellZ(image, box.min().x(), box.min().z());
        long bx = cellX(image, box.max().x(), box.max().z()), bz = cellZ(image, box.max().x(), box.max().z());
        long minX = Math.min(ax, bx), maxX = Math.max(ax, bx), minZ = Math.min(az, bz), maxZ = Math.max(az, bz);
        if (minX < Integer.MIN_VALUE || maxX > Integer.MAX_VALUE || minZ < Integer.MIN_VALUE || maxZ > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("A symmetric copy of " + box + " leaves the coordinate range");
        }
        return new Box(new BlockPos((int) minX, box.min().y(), (int) minZ), new BlockPos((int) maxX, box.max().y(), (int) maxZ));
    }

    /**
     * The image of the offset (dx, dy, dz) under {@code image}: its horizontal part mapped as a direction (no centre),
     * y kept. What a stack's step becomes in a copy.
     *
     * @throws ArithmeticException if the result leaves the int range (never for an image: the parts only swap and
     *     change sign)
     */
    public static BlockPos imageOffset(Image image, BlockPos offset) {
        Objects.requireNonNull(image);
        return new BlockPos(Math.toIntExact(image.x(offset.x(), offset.z())), offset.y(),
                Math.toIntExact(image.z(offset.x(), offset.z())));
    }

    /**
     * The facing {@code image} mirrors or turns {@code facing} into: up and down stay, a horizontal facing is mapped
     * as a direction (a cone facing east mirrors across x to one facing west).
     */
    public static Facing imageFacing(Image image, Facing facing) {
        Objects.requireNonNull(image);
        Objects.requireNonNull(facing);
        int dx = switch (facing) {
            case EAST -> 1;
            case WEST -> -1;
            default -> 0;
        };
        int dz = switch (facing) {
            case SOUTH -> 1;
            case NORTH -> -1;
            default -> 0;
        };
        if (dx == 0 && dz == 0) return facing;
        long x = image.x(dx, dz), z = image.z(dx, dz);
        if (x != 0) return x > 0 ? Facing.EAST : Facing.WEST;
        return z > 0 ? Facing.SOUTH : Facing.NORTH;
    }

    /**
     * The images of point (x16, z16), in 1/16 block, as x, z pairs: the point itself first ({@link #images()}). The
     * centre is {@code (8 × x2, 8 × z2)} in 1/16 block, so a mirror across x maps x to {@code 16 × x2 - x}, and a
     * quarter turn clockwise maps (dx, dz) from the centre to (-dz, dx).
     */
    private long[] images(long x16, long z16) {
        List<Image> images = images();
        long[] points = new long[2 * images.size()];
        for (int i = 0; i < images.size(); i++) {
            points[2 * i] = imageX(images.get(i), x16, z16);
            points[2 * i + 1] = imageZ(images.get(i), x16, z16);
        }
        return points;
    }
}
