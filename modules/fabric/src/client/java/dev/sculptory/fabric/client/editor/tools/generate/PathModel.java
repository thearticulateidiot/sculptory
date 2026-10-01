package dev.sculptory.fabric.client.editor.tools.generate;

import dev.sculptory.core.BlockPos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The Path generator's nodes (the ground blocks the builder clicked, in order) and which one is selected. Client
 * thread only.
 */
public final class PathModel {
    /** A click this many blocks above or below a node's block still hits the node (its column decides). */
    static final int PICK_HEIGHT = 2;

    private final List<BlockPos> nodes = new ArrayList<>();
    private int selected = -1;

    /** The nodes, in order (unmodifiable, a snapshot). */
    public List<BlockPos> nodes() {
        return Collections.unmodifiableList(new ArrayList<>(nodes));
    }

    public int size() {
        return nodes.size();
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    /** The selected node's index, or -1. */
    public int selected() {
        return selected;
    }

    public BlockPos node(int index) {
        return nodes.get(index);
    }

    /** Adds a node at the end; nothing is selected afterwards. */
    public void add(BlockPos node) {
        nodes.add(Objects.requireNonNull(node));
        selected = -1;
    }

    /** The node whose column holds {@code hit} within {@link #PICK_HEIGHT} blocks, the latest first, or -1. */
    public int indexAt(BlockPos hit) {
        Objects.requireNonNull(hit);
        for (int i = nodes.size() - 1; i >= 0; i--) {
            BlockPos node = nodes.get(i);
            if (node.x() == hit.x() && node.z() == hit.z() && Math.abs(node.y() - hit.y()) <= PICK_HEIGHT) return i;
        }
        return -1;
    }

    /** Selects a node (or clears the selection with -1). */
    public void select(int index) {
        if (index < -1 || index >= nodes.size()) throw new IndexOutOfBoundsException("node " + index);
        selected = index;
    }

    /** Moves a node; returns whether it moved. */
    public boolean move(int index, BlockPos to) {
        Objects.requireNonNull(to);
        if (nodes.get(index).equals(to)) return false;
        nodes.set(index, to);
        return true;
    }

    /** Removes the selected node, or the last one when none is selected; returns whether one was removed. */
    public boolean removeSelectedOrLast() {
        if (nodes.isEmpty()) return false;
        nodes.remove(selected >= 0 ? selected : nodes.size() - 1);
        selected = -1;
        return true;
    }

    public void clear() {
        nodes.clear();
        selected = -1;
    }
}
