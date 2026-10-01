package dev.sculptory.core.edit;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.Regions;
import dev.sculptory.core.transform.Transform;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An op's {@link Symmetry} and its copies: the op on its
 * own region (or paste) and on each image of it under the mode, in {@link Symmetry#images()} order, each copy
 * carrying no symmetry of its own. A copy equal to an earlier one (a box or shape symmetric about its own plane
 * filled, or one moved or stacked so that its copy lands where it does; a paste is never equal to its copy, since
 * the copy is turned) is dropped, so {@link #copyCount} is what sizes, hints and confirmations multiply by. A cell
 * set's copies are never compared (that would map every cell): a magic selection symmetric about the plane is
 * covered twice, which the union semantics make harmless.
 *
 * <ul>
 *   <li>Fill, Replace, Erase, Hollow, Walls: the same op on the image region ({@link Regions#image}); the copy's
 *       program turns the states it writes with the image.</li>
 *   <li>Paste: the same source with transform {@code t.compose(image.transform())} (the op's transform, then the
 *       image) and the anchor cell mapped: an exact image, since the paste map is affine.</li>
 *   <li>Move: the image region, moved so that its bounds' image lands on the image of the original's destination box,
 *       with the conjugated transform {@code image⁻¹ ∘ t ∘ image}: its cells land on the images of where the
 *       original's land.</li>
 *   <li>Stack: the image region stacked with the image of the step (flipped upside down as the original: the images
 *       are horizontal, so they commute with the flip).</li>
 * </ul>
 */
public final class OpSymmetry {
    /** One copy of a symmetric op: the op (without symmetry) and the image it is under. */
    public record Copy(OpSpec op, Symmetry.Image image) {
        public Copy {
            Objects.requireNonNull(op);
            Objects.requireNonNull(image);
        }
    }

    private OpSymmetry() {}

    /** The op's symmetry ({@link Symmetry#NONE} for a scatter commit). */
    public static Symmetry of(OpSpec op) {
        return switch (Objects.requireNonNull(op)) {
            case OpSpec.Fill fill -> fill.symmetry();
            case OpSpec.Replace replace -> replace.symmetry();
            case OpSpec.Erase erase -> erase.symmetry();
            case OpSpec.Hollow hollow -> hollow.symmetry();
            case OpSpec.Walls walls -> walls.symmetry();
            case OpSpec.Paste paste -> paste.symmetry();
            case OpSpec.Move move -> move.symmetry();
            case OpSpec.Stack stack -> stack.symmetry();
            case OpSpec.Overlay overlay -> overlay.symmetry();
            case OpSpec.Naturalize naturalize -> naturalize.symmetry();
            case OpSpec.UpdateBlocks update -> update.symmetry();
            case OpSpec.ScatterCommit scatter -> Symmetry.NONE;
        };
    }

    /**
     * The same op with {@code symmetry}.
     *
     * @throws IllegalArgumentException for a scatter commit, which has none
     */
    public static OpSpec withSymmetry(OpSpec op, Symmetry symmetry) {
        Objects.requireNonNull(symmetry);
        return switch (Objects.requireNonNull(op)) {
            case OpSpec.Fill fill -> new OpSpec.Fill(fill.region(), fill.pattern(), fill.mask(), symmetry);
            case OpSpec.Replace replace -> new OpSpec.Replace(replace.region(), replace.from(), replace.to(), symmetry);
            case OpSpec.Erase erase -> new OpSpec.Erase(erase.region(), erase.mask(), symmetry);
            case OpSpec.Hollow hollow -> new OpSpec.Hollow(hollow.region(), hollow.thickness(), hollow.inside(), symmetry);
            case OpSpec.Walls walls -> new OpSpec.Walls(walls.region(), walls.thickness(), walls.pattern(), symmetry);
            case OpSpec.Paste paste -> new OpSpec.Paste(paste.src(), paste.origin(), paste.t(), paste.o(), symmetry);
            case OpSpec.Move move -> new OpSpec.Move(move.region(), move.offset(), move.t(), move.leave(),
                    move.entities(), symmetry, move.into());
            case OpSpec.Stack stack -> new OpSpec.Stack(stack.region(), stack.dx(), stack.dy(), stack.dz(),
                    stack.count(), stack.entities(), symmetry, stack.into(), stack.upsideDown());
            case OpSpec.Overlay overlay -> new OpSpec.Overlay(overlay.region(), overlay.pattern(), overlay.depth(),
                    symmetry);
            case OpSpec.Naturalize n -> new OpSpec.Naturalize(n.region(), n.top(), n.topDepth(), n.middle(),
                    n.middleDepth(), n.bottom(), symmetry);
            case OpSpec.UpdateBlocks update -> new OpSpec.UpdateBlocks(update.region(), symmetry);
            case OpSpec.ScatterCommit scatter -> throw new IllegalArgumentException("A scatter commit has no symmetry");
        };
    }

    /**
     * The images the op's copies are under, the identity first: every image of the mode minus those whose copy
     * equals an earlier one. Cheap for every region but a cell set (whose images are all kept without mapping it).
     *
     * @throws IllegalArgumentException if an image leaves the coordinate range
     */
    public static List<Symmetry.Image> images(OpSpec op) {
        Symmetry symmetry = of(op);
        if (symmetry.isOff()) return List.of(Symmetry.Image.IDENTITY);
        if (OpRegions.region(op) instanceof Region.Cells) return symmetry.images();
        List<Symmetry.Image> images = new ArrayList<>(Symmetry.MAX_COPIES);
        List<OpSpec> seen = new ArrayList<>(Symmetry.MAX_COPIES);
        for (Symmetry.Image image : symmetry.images()) {
            OpSpec copy = canonical(copyOf(op, symmetry, image));
            if (seen.contains(copy)) continue;
            seen.add(copy);
            images.add(image);
        }
        return List.copyOf(images);
    }

    /**
     * The op with its shape's facing reduced to what decides its cells, so equal copies compare equal: an ellipsoid
     * faces up, a cylinder faces the positive way along its axis (the same cells either way).
     */
    private static OpSpec canonical(OpSpec op) {
        if (!(OpRegions.region(op) instanceof Region.Shape shape)) return op;
        Facing facing = switch (shape.kind()) {
            case ELLIPSOID -> Facing.UP;
            case CYLINDER -> shape.facing().sign() > 0 ? shape.facing() : shape.facing().opposite();
            default -> shape.facing();
        };
        if (facing == shape.facing()) return op;
        return OpRegions.withRegion(op, new Region.Shape(shape.box(), shape.kind(), facing));
    }

    /** How many copies the op writes ({@link #images}); 1 without symmetry. */
    public static int copyCount(OpSpec op) {
        return images(op).size();
    }

    /**
     * The op's copies in {@link #images} order, each without symmetry. A cell set is mapped cell by cell for each
     * image (server and compile time; the client keeps to {@link #images} and {@link #copyCount}).
     *
     * @throws IllegalArgumentException if an image leaves the coordinate range
     */
    public static List<Copy> copies(OpSpec op) {
        Symmetry symmetry = of(op);
        if (symmetry.isOff()) return List.of(new Copy(op, Symmetry.Image.IDENTITY));
        List<Copy> copies = new ArrayList<>(Symmetry.MAX_COPIES);
        for (Symmetry.Image image : images(op)) copies.add(new Copy(copyOf(op, symmetry, image), image));
        return List.copyOf(copies);
    }

    /** The op under {@code image} about {@code symmetry}'s centre, without symmetry of its own. */
    private static OpSpec copyOf(OpSpec op, Symmetry symmetry, Symmetry.Image image) {
        return switch (op) {
            case OpSpec.Fill fill -> new OpSpec.Fill(Regions.image(fill.region(), symmetry, image), fill.pattern(),
                    fill.mask());
            case OpSpec.Replace replace -> new OpSpec.Replace(Regions.image(replace.region(), symmetry, image),
                    replace.from(), replace.to());
            case OpSpec.Erase erase -> new OpSpec.Erase(Regions.image(erase.region(), symmetry, image), erase.mask());
            case OpSpec.Hollow hollow -> new OpSpec.Hollow(Regions.image(hollow.region(), symmetry, image),
                    hollow.thickness(), hollow.inside());
            case OpSpec.Walls walls -> new OpSpec.Walls(Regions.image(walls.region(), symmetry, image),
                    walls.thickness(), walls.pattern());
            case OpSpec.Paste paste -> new OpSpec.Paste(paste.src(), imageCell(symmetry, image, paste.origin()),
                    paste.t().compose(image.transform()), paste.o());
            case OpSpec.Move move -> moveCopy(move, symmetry, image);
            case OpSpec.Stack stack -> {
                BlockPos step = Symmetry.imageOffset(image, new BlockPos(stack.dx(), stack.dy(), stack.dz()));
                yield new OpSpec.Stack(Regions.image(stack.region(), symmetry, image),
                        step.x(), step.y(), step.z(), stack.count(), stack.entities(), Symmetry.NONE, stack.into(),
                        stack.upsideDown());
            }
            // Columns stay vertical under every image, so a layer op's copy is the op on the image region.
            case OpSpec.Overlay overlay -> new OpSpec.Overlay(Regions.image(overlay.region(), symmetry, image),
                    overlay.pattern(), overlay.depth());
            case OpSpec.Naturalize n -> new OpSpec.Naturalize(Regions.image(n.region(), symmetry, image), n.top(),
                    n.topDepth(), n.middle(), n.middleDepth(), n.bottom());
            case OpSpec.UpdateBlocks update -> new OpSpec.UpdateBlocks(Regions.image(update.region(), symmetry, image));
            case OpSpec.ScatterCommit scatter -> scatter;
        };
    }

    /**
     * A move's copy: the image region, moved so that its bounds' image lands where the image of the original's
     * destination box is, turned by {@code image⁻¹ ∘ t ∘ image} (with the move's transform applied between the
     * image's inverse and the image, the copy's cells land exactly on the images of where the original's land).
     */
    private static OpSpec.Move moveCopy(OpSpec.Move move, Symmetry symmetry, Symmetry.Image image) {
        Box from = move.box();
        Transform t = move.t();
        Box destination = CopySupport.boxAt((long) from.min().x() + move.offset().x(), from.min().y(),
                (long) from.min().z() + move.offset().z(), t.size(from.sizeX(), from.sizeY(), from.sizeZ()));
        Box imageFrom = symmetry.imageBox(image, from);
        Box imageDestination = symmetry.imageBox(image, destination);
        BlockPos offset = new BlockPos(Math.subtractExact(imageDestination.min().x(), imageFrom.min().x()),
                move.offset().y(), Math.subtractExact(imageDestination.min().z(), imageFrom.min().z()));
        Transform turned = image.inverse().transform().compose(t).compose(image.transform());
        return new OpSpec.Move(Regions.image(move.region(), symmetry, image), offset, turned, move.leave(),
                move.entities(), Symmetry.NONE, move.into());
    }

    /** The image of a block cell. */
    private static BlockPos imageCell(Symmetry symmetry, Symmetry.Image image, BlockPos cell) {
        long x = symmetry.cellX(image, cell.x(), cell.z()), z = symmetry.cellZ(image, cell.x(), cell.z());
        if (x != (int) x || z != (int) z) {
            throw new IllegalArgumentException("A symmetric copy of " + cell + " leaves the coordinate range");
        }
        return new BlockPos((int) x, cell.y(), (int) z);
    }

}
