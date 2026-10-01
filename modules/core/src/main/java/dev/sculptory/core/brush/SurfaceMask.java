package dev.sculptory.core.brush;

import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.mask.EditMask;
import java.util.List;
import java.util.Objects;

/**
 * Which surface columns a brush may change, as a declarative, serializable expression tree evaluated per
 * column on the surface cell (its state, elevation and steepest cardinal step to a neighbouring surface).
 * Ported from the old {@code SurfaceMask}. Trees are limited to {@value #MAX_DEPTH} levels and
 * {@value #MAX_NODES} nodes. Evaluation belongs to the brush kernels (WS1).
 */
public sealed interface SurfaceMask {
    int MAX_DEPTH = 8;
    int MAX_NODES = 64;

    SurfaceMask ANY = new Any();

    default int nodeCount() {
        return 1;
    }

    default int depth() {
        return 1;
    }

    /** Every column. */
    record Any() implements SurfaceMask {}

    /**
     * Columns whose surface state the cell mask accepts. The nested cell mask's nodes count toward this tree's
     * {@value #MAX_NODES}-node budget.
     */
    record SurfaceBlocks(CellMask mask) implements SurfaceMask {
        public SurfaceBlocks {
            Objects.requireNonNull(mask);
            if (mask.nodeCount() + 1 > MAX_NODES) throw new IllegalArgumentException("Mask too large");
        }

        @Override
        public int nodeCount() {
            return 1 + mask.nodeCount();
        }
    }

    /** Columns whose surface y is in {@code [minY, maxY]}. */
    record Elevation(int minY, int maxY) implements SurfaceMask {
        public Elevation {
            if (minY > maxY) throw new IllegalArgumentException("Inverted elevation range");
        }
    }

    /** Columns whose steepest cardinal step, in blocks, is in {@code [minStep, maxStep]}. */
    record Slope(int minStep, int maxStep) implements SurfaceMask {
        public Slope {
            if (minStep < 0 || minStep > maxStep) throw new IllegalArgumentException("Invalid slope range");
        }
    }

    /** Columns every child accepts. */
    record And(List<SurfaceMask> masks) implements SurfaceMask {
        public And {
            masks = List.copyOf(masks);
            if (masks.isEmpty()) throw new IllegalArgumentException("Mask needs at least one child");
            int nodes = 1, height = 1;
            for (SurfaceMask child : masks) {
                nodes += child.nodeCount();
                height = Math.max(height, 1 + child.depth());
            }
            if (nodes > MAX_NODES || height > MAX_DEPTH) throw new IllegalArgumentException("Mask too large or too deep");
        }

        @Override
        public int nodeCount() {
            return 1 + masks.stream().mapToInt(SurfaceMask::nodeCount).sum();
        }

        @Override
        public int depth() {
            return 1 + masks.stream().mapToInt(SurfaceMask::depth).max().orElse(0);
        }
    }

    /**
     * Columns whose surface cell the rules accept, as the global mask would test that cell: the brush's own mask in the rule list form. A brush preset's legacy mask keys become one of these.
     * Counts as one node.
     */
    record Rules(EditMask rules) implements SurfaceMask {
        public Rules {
            Objects.requireNonNull(rules);
        }
    }

    /** Columns the child rejects. */
    record Not(SurfaceMask mask) implements SurfaceMask {
        public Not {
            Objects.requireNonNull(mask);
            if (mask.nodeCount() + 1 > MAX_NODES || mask.depth() + 1 > MAX_DEPTH) {
                throw new IllegalArgumentException("Mask too large or too deep");
            }
        }

        @Override
        public int nodeCount() {
            return 1 + mask.nodeCount();
        }

        @Override
        public int depth() {
            return 1 + mask.depth();
        }
    }
}
