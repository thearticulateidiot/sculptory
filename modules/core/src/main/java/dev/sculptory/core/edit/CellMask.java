package dev.sculptory.core.edit;

import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.state.StateSpace;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Which cells an op touches, as a declarative, serializable expression tree. Evaluate it by
 * {@link #bind binding} it to a {@code StateSpace}, which expands block and tag tests into handle sets.
 * Trees are limited to {@value #MAX_DEPTH} levels and {@value #MAX_NODES} nodes; lists to
 * {@value #MAX_LIST} entries.
 */
public sealed interface CellMask {
    int MAX_DEPTH = 8;
    int MAX_NODES = 64;
    int MAX_LIST = 1024;

    CellMask ANY = new Any();

    CellPredicate bind(StateSpace states);

    /** Nodes in this tree, counting shared subtrees once per occurrence. */
    default int nodeCount() {
        return 1;
    }

    default int depth() {
        return 1;
    }

    /** Every cell. */
    record Any() implements CellMask {
        @Override
        public CellPredicate bind(StateSpace states) {
            return CellPredicate.ALWAYS;
        }
    }

    /** Cells whose current state is one of these exact handles. The array is copied in and out. */
    record States(int[] handles) implements CellMask {
        public States {
            Objects.requireNonNull(handles);
            if (handles.length > MAX_LIST) throw new IllegalArgumentException("Too many states in mask");
            handles = handles.clone();
            for (int handle : handles) {
                if (handle < 0) throw new IllegalArgumentException("Negative state handle");
            }
        }

        @Override
        public int[] handles() {
            return handles.clone();
        }

        @Override
        public CellPredicate bind(StateSpace states) {
            BitSet set = new BitSet(states.size());
            for (int handle : handles) {
                Objects.checkIndex(handle, states.size());
                set.set(handle);
            }
            return handleSet(set);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof States other && Arrays.equals(handles, other.handles);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(handles);
        }

        @Override
        public String toString() {
            return "States" + Arrays.toString(handles);
        }
    }

    /** Cells whose current state belongs to one of these blocks (any properties). */
    record Blocks(List<NamespacedId> blocks) implements CellMask {
        public Blocks {
            blocks = List.copyOf(blocks);
            if (blocks.size() > MAX_LIST) throw new IllegalArgumentException("Too many blocks in mask");
        }

        @Override
        public CellPredicate bind(StateSpace states) {
            Set<NamespacedId> ids = new HashSet<>(blocks);
            BitSet set = new BitSet(states.size());
            for (int h = 0; h < states.size(); h++) {
                if (ids.contains(states.blockId(h))) set.set(h);
            }
            return handleSet(set);
        }
    }

    /** Cells whose current state is in a block tag. */
    record Tag(NamespacedId tag) implements CellMask {
        public Tag {
            Objects.requireNonNull(tag);
        }

        @Override
        public CellPredicate bind(StateSpace states) {
            BitSet set = new BitSet(states.size());
            for (int h = 0; h < states.size(); h++) {
                if (states.inTag(h, tag)) set.set(h);
            }
            return handleSet(set);
        }
    }

    /** Cells every child accepts. */
    record And(List<CellMask> masks) implements CellMask {
        public And {
            masks = checkChildren(masks);
        }

        @Override
        public CellPredicate bind(StateSpace states) {
            CellPredicate[] children = bindAll(masks, states);
            return (x, y, z, before) -> {
                for (CellPredicate child : children) {
                    if (!child.test(x, y, z, before)) return false;
                }
                return true;
            };
        }

        @Override
        public int nodeCount() {
            return 1 + masks.stream().mapToInt(CellMask::nodeCount).sum();
        }

        @Override
        public int depth() {
            return 1 + masks.stream().mapToInt(CellMask::depth).max().orElse(0);
        }
    }

    /** Cells at least one child accepts. */
    record Or(List<CellMask> masks) implements CellMask {
        public Or {
            masks = checkChildren(masks);
        }

        @Override
        public CellPredicate bind(StateSpace states) {
            CellPredicate[] children = bindAll(masks, states);
            return (x, y, z, before) -> {
                for (CellPredicate child : children) {
                    if (child.test(x, y, z, before)) return true;
                }
                return false;
            };
        }

        @Override
        public int nodeCount() {
            return 1 + masks.stream().mapToInt(CellMask::nodeCount).sum();
        }

        @Override
        public int depth() {
            return 1 + masks.stream().mapToInt(CellMask::depth).max().orElse(0);
        }
    }

    /** Cells the child rejects. */
    record Not(CellMask mask) implements CellMask {
        public Not {
            checkChildren(List.of(mask));
        }

        @Override
        public CellPredicate bind(StateSpace states) {
            CellPredicate child = mask.bind(states);
            return (x, y, z, before) -> !child.test(x, y, z, before);
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

    private static CellPredicate handleSet(BitSet set) {
        return (x, y, z, before) -> before >= 0 && set.get(before);
    }

    private static CellPredicate[] bindAll(List<CellMask> masks, StateSpace states) {
        CellPredicate[] bound = new CellPredicate[masks.size()];
        for (int i = 0; i < bound.length; i++) bound[i] = masks.get(i).bind(states);
        return bound;
    }

    private static List<CellMask> checkChildren(List<CellMask> masks) {
        List<CellMask> copy = List.copyOf(masks);
        if (copy.isEmpty()) throw new IllegalArgumentException("Mask needs at least one child");
        int nodes = 1, depth = 1;
        for (CellMask child : copy) {
            nodes += child.nodeCount();
            depth = Math.max(depth, 1 + child.depth());
        }
        if (nodes > MAX_NODES || depth > MAX_DEPTH) throw new IllegalArgumentException("Mask too large or too deep");
        return copy;
    }
}
