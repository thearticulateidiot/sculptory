package dev.sculptory.mc.client.editor.tools.generate;

import dev.sculptory.core.BlockPos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Path points (the Path generator's nodes, the Line's and Sweep's points, a Stamp's spots, an extrusion's path or tip,
 * Smart Surface's outline and inside points) and which one is selected: the model the shared point editor
 * ({@code tools.points.PointEditor}) edits. Client thread only.
 *
 * <p><b>Two kinds of model.</b> An open path ({@link #PathModel()}): ordered points from the first to the last, the
 * {@link Role#OUTLINE} points. A closed outline ({@link #closedOutline}): the ordered points form a loop whose last
 * point joins the first, and the model also holds an unordered set of {@link Role#INSIDE} points, which a surface passes
 * through as it does the outline's ({@code SmartSurfaceKernel.Shape(outline, inside, faceted)}: {@link #nodes()} and
 * {@link #insideNodes()}). An open path never has inside points. The ordered-point methods ({@link #nodes()},
 * {@link #size()}, {@link #node}, {@link #insert}, {@link #move}, ...) are about the outline points only.
 *
 * <p><b>Segments.</b> Segment {@code i} runs from point {@code i} to point {@code i + 1}; a closed outline of three or
 * more points also has segment {@code size - 1}, from the last point back to the first ({@link #segmentCount},
 * {@link #segmentEnd}). Inserting on segment {@code i} puts the new point at index {@code i + 1}.
 *
 * <p><b>Selection.</b> At most one point is selected, an outline or an inside one ({@link #selection()});
 * {@link #selected()} is the selected outline point's index (-1 when none, or when an inside point is selected), as the
 * tools have always read it. {@link #insert}, {@link #remove} and their inside counterparts keep the same point
 * selected (its index shifts); removing the selected point leaves nothing selected. {@link #add} keeps its old rule
 * (nothing selected afterwards); the point editor selects the point it adds.
 */
public final class PathModel {
    /** A click this many blocks above or below a node's block still hits the node (its column decides). */
    static final int PICK_HEIGHT = 2;

    /** Which list a point is in. */
    public enum Role {
        /** The ordered points: an open path's points, or a closed outline's loop. */
        OUTLINE,
        /** A closed outline's unordered inside points (never on an open path). */
        INSIDE
    }

    /** A point, by list and index. */
    public record PointRef(Role role, int index) {
        public PointRef {
            Objects.requireNonNull(role);
            if (index < 0) throw new IllegalArgumentException("index " + index);
        }
    }

    private final List<BlockPos> nodes = new ArrayList<>();
    private final List<BlockPos> inside = new ArrayList<>();
    private final boolean closed;
    private Role selectedRole = Role.OUTLINE;
    private int selected = -1;

    /** An open path. */
    public PathModel() {
        this(false);
    }

    private PathModel(boolean closed) {
        this.closed = closed;
    }

    /** A closed outline (the last point joins the first) with inside points: Smart Surface. */
    public static PathModel closedOutline() {
        return new PathModel(true);
    }

    /** Whether the last point joins the first (and the model takes inside points). */
    public boolean isClosed() {
        return closed;
    }

    /** The outline points, in order (unmodifiable, a snapshot). */
    public List<BlockPos> nodes() {
        return Collections.unmodifiableList(new ArrayList<>(nodes));
    }

    /** How many outline points there are. */
    public int size() {
        return nodes.size();
    }

    /** Whether there are no points at all (outline or inside). */
    public boolean isEmpty() {
        return nodes.isEmpty() && inside.isEmpty();
    }

    /** The selected outline point's index, or -1 (none, or an inside point is selected). */
    public int selected() {
        return selectedRole == Role.OUTLINE ? selected : -1;
    }

    /** The selected inside point's index, or -1. */
    public int selectedInside() {
        return selectedRole == Role.INSIDE ? selected : -1;
    }

    /** The selected point, outline or inside, if any. */
    public Optional<PointRef> selection() {
        return selected < 0 ? Optional.empty() : Optional.of(new PointRef(selectedRole, selected));
    }

    public BlockPos node(int index) {
        return nodes.get(index);
    }

    /** Whether {@code index} is the last outline point (the end a click draws on from). */
    public boolean isEnd(int index) {
        return index >= 0 && index == nodes.size() - 1;
    }

    /** Adds an outline node at the end; nothing is selected afterwards. */
    public void add(BlockPos node) {
        nodes.add(Objects.requireNonNull(node));
        selected = -1;
    }

    /**
     * Inserts an outline node at {@code index} (0 to {@link #size()}; the nodes from there on move up one). The selected
     * point stays selected.
     */
    public void insert(int index, BlockPos node) {
        Objects.requireNonNull(node);
        if (index < 0 || index > nodes.size()) throw new IndexOutOfBoundsException("node " + index);
        nodes.add(index, node);
        if (selectedRole == Role.OUTLINE && selected >= index) selected++;
    }

    /** Removes outline node {@code index} and returns it; the selected point stays selected, or none if it was this one. */
    public BlockPos remove(int index) {
        BlockPos removed = nodes.remove(index);
        if (selectedRole == Role.OUTLINE) selected = shifted(selected, index);
        return removed;
    }

    // ---- Inside points (closed outlines only) ----

    /** The inside points (unmodifiable, a snapshot); always empty on an open path. */
    public List<BlockPos> insideNodes() {
        return Collections.unmodifiableList(new ArrayList<>(inside));
    }

    public int insideCount() {
        return inside.size();
    }

    public BlockPos insidePoint(int index) {
        return inside.get(index);
    }

    /**
     * Adds an inside point and returns its index; the selection is unchanged.
     *
     * @throws IllegalStateException on an open path
     */
    public int addInside(BlockPos point) {
        Objects.requireNonNull(point);
        if (!closed) throw new IllegalStateException("An open path has no inside points");
        inside.add(point);
        return inside.size() - 1;
    }

    /** Removes inside point {@code index} and returns it; the selected point stays selected, or none if it was this one. */
    public BlockPos removeInside(int index) {
        BlockPos removed = inside.remove(index);
        if (selectedRole == Role.INSIDE) selected = shifted(selected, index);
        return removed;
    }

    /** Moves inside point {@code index}; returns whether it moved. */
    public boolean moveInside(int index, BlockPos to) {
        Objects.requireNonNull(to);
        if (inside.get(index).equals(to)) return false;
        inside.set(index, to);
        return true;
    }

    /** The inside point whose column holds {@code hit} within {@link #PICK_HEIGHT} blocks, the latest first, or -1. */
    public int insideIndexAt(BlockPos hit) {
        return columnIndex(inside, hit);
    }

    /** Selects inside point {@code index} (-1 clears the selection). */
    public void selectInside(int index) {
        if (index < -1 || index >= inside.size()) throw new IndexOutOfBoundsException("inside point " + index);
        selectedRole = Role.INSIDE;
        selected = index;
    }

    /** Selects {@code point} (by its role), or nothing for null. */
    public void select(PointRef point) {
        if (point == null) {
            selected = -1;
        } else if (point.role() == Role.INSIDE) {
            selectInside(point.index());
        } else {
            select(point.index());
        }
    }

    // ---- Segments, picking and the rest ----

    /**
     * How many segments join the outline points: {@code size - 1} for an open path; for a closed outline of three or
     * more points one more, from the last point back to the first. 0 for fewer than two points.
     */
    public int segmentCount() {
        int size = nodes.size();
        if (size < 2) return 0;
        return closed && size >= 3 ? size : size - 1;
    }

    /** The index of the point segment {@code segment} ends at: the next point, or 0 for a closed outline's last one. */
    public int segmentEnd(int segment) {
        if (segment < 0 || segment >= segmentCount()) throw new IndexOutOfBoundsException("segment " + segment);
        return (segment + 1) % nodes.size();
    }

    /** The outline node whose column holds {@code hit} within {@link #PICK_HEIGHT} blocks, the latest first, or -1. */
    public int indexAt(BlockPos hit) {
        return columnIndex(nodes, hit);
    }

    /** Selects an outline node (or clears the selection with -1). */
    public void select(int index) {
        if (index < -1 || index >= nodes.size()) throw new IndexOutOfBoundsException("node " + index);
        selectedRole = Role.OUTLINE;
        selected = index;
    }

    /** Moves an outline node; returns whether it moved. */
    public boolean move(int index, BlockPos to) {
        Objects.requireNonNull(to);
        if (nodes.get(index).equals(to)) return false;
        nodes.set(index, to);
        return true;
    }

    /** Removes the selected outline node, or the last one when none is selected; returns whether one was removed. */
    public boolean removeSelectedOrLast() {
        if (nodes.isEmpty()) return false;
        int at = selected();
        nodes.remove(at >= 0 ? at : nodes.size() - 1);
        selected = -1;
        return true;
    }

    /** Removes every point, outline and inside. */
    public void clear() {
        nodes.clear();
        inside.clear();
        selected = -1;
    }

    private static int columnIndex(List<BlockPos> points, BlockPos hit) {
        Objects.requireNonNull(hit);
        for (int i = points.size() - 1; i >= 0; i--) {
            BlockPos node = points.get(i);
            if (node.x() == hit.x() && node.z() == hit.z() && Math.abs(node.y() - hit.y()) <= PICK_HEIGHT) return i;
        }
        return -1;
    }

    /** The selected index after point {@code removed} of its list goes: shifted down, or -1 if it was this one. */
    private static int shifted(int selected, int removed) {
        if (selected == removed) return -1;
        return selected > removed ? selected - 1 : selected;
    }
}
