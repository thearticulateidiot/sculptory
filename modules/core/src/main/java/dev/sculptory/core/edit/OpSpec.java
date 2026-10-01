package dev.sculptory.core.edit;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.transform.Transform;
import java.util.Objects;
import java.util.UUID;

/**
 * A requested edit, as sent by the client. {@link OpCompiler} turns it into an {@link EditProgram}.
 *
 * <p>The Select operations work on a {@link Region}. Each
 * keeps its earlier {@link Box} constructor, which means that box as a {@link Region.Cuboid} (and, for Move and Stack,
 * no entities), and a {@code box()} method giving the region's bounds.
 *
 * <p>Every op but a scatter commit carries a {@link Symmetry}: the op is applied to its region (or paste) and to each image of it under the mode, as one edit
 * ({@link OpSymmetry}). Every constructor without it means {@link Symmetry#NONE}. Paste, Move and Stack also carry a
 * {@link PasteOptions.Into} (a paste's inside its options): every constructor
 * without it means {@link PasteOptions.Into#EVERYTHING}.
 */
public sealed interface OpSpec {
    /** Sets every masked cell of the region from the pattern. */
    record Fill(Region region, Pattern pattern, CellMask mask, Symmetry symmetry) implements OpSpec {
        public Fill {
            Objects.requireNonNull(region);
            Objects.requireNonNull(pattern);
            Objects.requireNonNull(mask);
            Objects.requireNonNull(symmetry);
        }

        public Fill(Region region, Pattern pattern, CellMask mask) {
            this(region, pattern, mask, Symmetry.NONE);
        }

        public Fill(Box box, Pattern pattern, CellMask mask) {
            this(new Region.Cuboid(box), pattern, mask, Symmetry.NONE);
        }

        /** The region's bounds. */
        public Box box() {
            return region.bounds();
        }
    }

    /** Sets the cells of the region matching {@code from} to {@code to}. */
    record Replace(Region region, CellMask from, Pattern to, Symmetry symmetry) implements OpSpec {
        public Replace {
            Objects.requireNonNull(region);
            Objects.requireNonNull(from);
            Objects.requireNonNull(to);
            Objects.requireNonNull(symmetry);
        }

        public Replace(Region region, CellMask from, Pattern to) {
            this(region, from, to, Symmetry.NONE);
        }

        public Replace(Box box, CellMask from, Pattern to) {
            this(new Region.Cuboid(box), from, to, Symmetry.NONE);
        }

        /** The region's bounds. */
        public Box box() {
            return region.bounds();
        }
    }

    /** Sets the masked cells of the region to air. */
    record Erase(Region region, CellMask mask, Symmetry symmetry) implements OpSpec {
        public Erase {
            Objects.requireNonNull(region);
            Objects.requireNonNull(mask);
            Objects.requireNonNull(symmetry);
        }

        public Erase(Region region, CellMask mask) {
            this(region, mask, Symmetry.NONE);
        }

        public Erase(Box box, CellMask mask) {
            this(new Region.Cuboid(box), mask, Symmetry.NONE);
        }

        /** The region's bounds. */
        public Box box() {
            return region.bounds();
        }
    }

    /** Keeps a shell {@code thickness} thick inside the region and fills the rest of it with {@code inside}. */
    record Hollow(Region region, int thickness, Pattern inside, Symmetry symmetry) implements OpSpec {
        public Hollow {
            Objects.requireNonNull(region);
            Objects.requireNonNull(inside);
            Objects.requireNonNull(symmetry);
            if (thickness < 1) throw new IllegalArgumentException("Thickness must be positive");
        }

        public Hollow(Region region, int thickness, Pattern inside) {
            this(region, thickness, inside, Symmetry.NONE);
        }

        public Hollow(Box box, int thickness, Pattern inside) {
            this(new Region.Cuboid(box), thickness, inside, Symmetry.NONE);
        }

        /** The region's bounds. */
        public Box box() {
            return region.bounds();
        }
    }

    /**
     * Fills the region's cells that have a cell outside the region within {@code thickness} horizontally (for a box:
     * the four vertical sides, {@code thickness} thick).
     */
    record Walls(Region region, int thickness, Pattern pattern, Symmetry symmetry) implements OpSpec {
        public Walls {
            Objects.requireNonNull(region);
            Objects.requireNonNull(pattern);
            Objects.requireNonNull(symmetry);
            if (thickness < 1) throw new IllegalArgumentException("Thickness must be positive");
        }

        public Walls(Region region, int thickness, Pattern pattern) {
            this(region, thickness, pattern, Symmetry.NONE);
        }

        public Walls(Box box, int thickness, Pattern pattern) {
            this(new Region.Cuboid(box), thickness, pattern, Symmetry.NONE);
        }

        /** The region's bounds. */
        public Box box() {
            return region.bounds();
        }
    }

    /** Places a server-held source so its anchor lands on {@code origin}, after the transform. */
    record Paste(SourceRef src, BlockPos origin, Transform t, PasteOptions o, Symmetry symmetry) implements OpSpec {
        public Paste {
            Objects.requireNonNull(src);
            Objects.requireNonNull(origin);
            Objects.requireNonNull(t);
            Objects.requireNonNull(o);
            Objects.requireNonNull(symmetry);
        }

        public Paste(SourceRef src, BlockPos origin, Transform t, PasteOptions o) {
            this(src, origin, t, o, Symmetry.NONE);
        }
    }

    /**
     * Moves the region by {@code offset} with a transform, filling the vacated cells with {@code leave}, and the
     * entities {@code entities} selects along with it. {@code into} filters the landing cells only: a moved block whose landing cell is left out is not placed, while its
     * source cell is vacated all the same.
     */
    record Move(Region region, BlockPos offset, Transform t, Pattern leave, EntityFilter entities, Symmetry symmetry,
                PasteOptions.Into into) implements OpSpec {
        public Move {
            Objects.requireNonNull(region);
            Objects.requireNonNull(offset);
            Objects.requireNonNull(t);
            Objects.requireNonNull(leave);
            Objects.requireNonNull(entities);
            Objects.requireNonNull(symmetry);
            Objects.requireNonNull(into);
        }

        public Move(Region region, BlockPos offset, Transform t, Pattern leave, EntityFilter entities, Symmetry symmetry) {
            this(region, offset, t, leave, entities, symmetry, PasteOptions.Into.EVERYTHING);
        }

        public Move(Region region, BlockPos offset, Transform t, Pattern leave, EntityFilter entities) {
            this(region, offset, t, leave, entities, Symmetry.NONE, PasteOptions.Into.EVERYTHING);
        }

        /** A box moved without its entities. */
        public Move(Box box, BlockPos offset, Transform t, Pattern leave) {
            this(new Region.Cuboid(box), offset, t, leave, EntityFilter.NONE, Symmetry.NONE, PasteOptions.Into.EVERYTHING);
        }

        /** The region's bounds. */
        public Box box() {
            return region.bounds();
        }
    }

    /**
     * Repeats the region {@code count} times, each copy offset by (dx, dy, dz) from the previous, with the entities
     * {@code entities} selects. {@code into} filters the cells the copies land on. With {@code upsideDown} every copy is the region flipped upside down within its bounds' height; copies are never turned or mirrored. Every constructor without it
     * means false.
     */
    record Stack(Region region, int dx, int dy, int dz, int count, EntityFilter entities, Symmetry symmetry,
                 PasteOptions.Into into, boolean upsideDown) implements OpSpec {
        public Stack {
            Objects.requireNonNull(region);
            Objects.requireNonNull(entities);
            Objects.requireNonNull(symmetry);
            Objects.requireNonNull(into);
            if (count < 1) throw new IllegalArgumentException("Stack count must be positive");
            if (dx == 0 && dy == 0 && dz == 0) throw new IllegalArgumentException("Stack offset must be non-zero");
        }

        public Stack(Region region, int dx, int dy, int dz, int count, EntityFilter entities, Symmetry symmetry,
                     PasteOptions.Into into) {
            this(region, dx, dy, dz, count, entities, symmetry, into, false);
        }

        public Stack(Region region, int dx, int dy, int dz, int count, EntityFilter entities, Symmetry symmetry) {
            this(region, dx, dy, dz, count, entities, symmetry, PasteOptions.Into.EVERYTHING);
        }

        public Stack(Region region, int dx, int dy, int dz, int count, EntityFilter entities) {
            this(region, dx, dy, dz, count, entities, Symmetry.NONE, PasteOptions.Into.EVERYTHING);
        }

        /** A box stacked without its entities. */
        public Stack(Box box, int dx, int dy, int dz, int count) {
            this(new Region.Cuboid(box), dx, dy, dz, count, EntityFilter.NONE, Symmetry.NONE, PasteOptions.Into.EVERYTHING);
        }

        /** The region's bounds. */
        public Box box() {
            return region.bounds();
        }
    }

    /** Commits a scatter plan previously computed by the server (M3). */
    record ScatterCommit(UUID planId) implements OpSpec {
        public ScatterCommit {
            Objects.requireNonNull(planId);
        }
    }

    /**
     * Overlay: in each column of the region, up to {@code depth}
     * (1-{@value #MAX_LAYER_DEPTH}) open cells on top of the column's highest block are set from the pattern (the layer
     * may reach above the region; see {@code ColumnProgram} for "highest block" and "open").
     */
    record Overlay(Region region, Pattern pattern, int depth, Symmetry symmetry) implements OpSpec {
        public Overlay {
            Objects.requireNonNull(region);
            Objects.requireNonNull(pattern);
            Objects.requireNonNull(symmetry);
            checkDepth(depth, 1, "Overlay depth");
        }

        public Overlay(Region region, Pattern pattern, int depth) {
            this(region, pattern, depth, Symmetry.NONE);
        }

        /** The region's bounds. */
        public Box box() {
            return region.bounds();
        }
    }

    /**
     * Naturalize: in each column of the region, from the column's highest block down, {@code topDepth} cells of
     * {@code top} (1-{@value #MAX_LAYER_DEPTH}; grass), then {@code middleDepth} of {@code middle}
     * (0-{@value #MAX_LAYER_DEPTH}; dirt), then {@code bottom} (stone) for the rest of the column's region cells, counted
     * in cells below the top; only ground (full blocks without a block entity) changes.
     */
    record Naturalize(Region region, Pattern top, int topDepth, Pattern middle, int middleDepth, Pattern bottom,
                      Symmetry symmetry) implements OpSpec {
        public Naturalize {
            Objects.requireNonNull(region);
            Objects.requireNonNull(top);
            Objects.requireNonNull(middle);
            Objects.requireNonNull(bottom);
            Objects.requireNonNull(symmetry);
            checkDepth(topDepth, 1, "Naturalize top depth");
            checkDepth(middleDepth, 0, "Naturalize middle depth");
        }

        public Naturalize(Region region, Pattern top, int topDepth, Pattern middle, int middleDepth, Pattern bottom) {
            this(region, top, topDepth, middle, middleDepth, bottom, Symmetry.NONE);
        }

        /** The region's bounds. */
        public Box box() {
            return region.bounds();
        }
    }

    /**
     * Update blocks: every cell of the region takes the shape its neighbours give it (fences and panes connect,
     * stairs shape), without physics, and the region's light is fixed afterwards. Only property changes of the same
     * block are kept; exactly undoable.
     */
    record UpdateBlocks(Region region, Symmetry symmetry) implements OpSpec {
        public UpdateBlocks {
            Objects.requireNonNull(region);
            Objects.requireNonNull(symmetry);
        }

        public UpdateBlocks(Region region) {
            this(region, Symmetry.NONE);
        }

        /** The region's bounds. */
        public Box box() {
            return region.bounds();
        }
    }

    /** The deepest layer Overlay and Naturalize lay. */
    int MAX_LAYER_DEPTH = 16;

    private static void checkDepth(int depth, int min, String what) {
        if (depth < min || depth > MAX_LAYER_DEPTH) {
            throw new IllegalArgumentException(what + " must be " + min + "-" + MAX_LAYER_DEPTH);
        }
    }
}
