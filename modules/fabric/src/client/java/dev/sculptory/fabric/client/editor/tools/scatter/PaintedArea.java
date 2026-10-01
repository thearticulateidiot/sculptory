package dev.sculptory.fabric.client.editor.tools.scatter;

import dev.sculptory.core.Box;
import dev.sculptory.core.scatter.ScatterArea;
import dev.sculptory.protocol.v2.Codec;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The Scatter tool's area: disc stamps painted (or erased) in groups, one group per press, or a box taken from the
 * selection ("Use selection"). Local to this client until a preview sends it. Pure; client thread only.
 *
 * <ul>
 *   <li>At most {@value #MAX_STAMPS} stamps (the wire cap); {@link #nearCap()} from {@value #WARN_STAMPS}.</li>
 *   <li>The painted bounding rectangle holds at most {@link ScatterArea#MAX_COLUMNS} columns.</li>
 *   <li>An erase stamp that would remove nothing, and a stamp repeating the previous one, are skipped.</li>
 *   <li>{@link #undoGroup} removes the newest group (or leaves box mode first); {@link #cancelGroup} drops the group
 *       being painted; {@link #seal} puts everything painted so far out of undo's reach.</li>
 * </ul>
 * The raster ({@link #mask()}) follows the planner's rule: stamps in order, a column is in when the last stamp covering
 * it paints; a disc covers {@code dx² + dz² <= r²}.
 */
public final class PaintedArea {
    public static final int MAX_STAMPS = Codec.MAX_SCATTER_STAMPS;
    /** From this many stamps on, the HUD warns that the area is nearly full. */
    public static final int WARN_STAMPS = MAX_STAMPS - MAX_STAMPS / 8;

    /** What adding a stamp did. */
    public enum AddResult {
        ADDED,
        /** Nothing to erase there, or the same as the previous stamp. */
        SKIPPED,
        /** {@value #MAX_STAMPS} stamps already. */
        FULL,
        /** The area would span more than {@link ScatterArea#MAX_COLUMNS} columns. */
        TOO_WIDE
    }

    /** The painted columns over their bounding rectangle. */
    public static final class Mask {
        public final int x0;
        public final int z0;
        public final int x1;
        public final int z1;
        private final int width;
        private final BitSet bits;

        Mask(int x0, int z0, int x1, int z1, BitSet bits) {
            this.x0 = x0;
            this.z0 = z0;
            this.x1 = x1;
            this.z1 = z1;
            this.width = x1 - x0 + 1;
            this.bits = bits;
        }

        public boolean contains(int x, int z) {
            if (x < x0 || x > x1 || z < z0 || z > z1) return false;
            return bits.get((z - z0) * width + (x - x0));
        }

        /** Columns in the area. */
        public long columns() {
            return bits.cardinality();
        }

        /** Whether any column of row {@code z} between {@code fromX} and {@code toX} (inclusive) is in. */
        boolean anyInRow(int z, int fromX, int toX) {
            if (z < z0 || z > z1) return false;
            int from = Math.max(fromX, x0), to = Math.min(toX, x1);
            if (from > to) return false;
            int row = (z - z0) * width - x0;
            int next = bits.nextSetBit(row + from);
            return next >= 0 && next <= row + to;
        }
    }

    private static final Mask EMPTY = new Mask(0, 0, -1, -1, new BitSet());

    private final List<ScatterArea.Stamp> stamps = new ArrayList<>();
    /** The index in {@link #stamps} where each group starts, oldest first. */
    private final List<Integer> groups = new ArrayList<>();
    private boolean painting;
    private Box box;
    /** Whether leaving box mode is an undo step (it is until {@link #seal}). */
    private boolean boxUndoable;
    private int version;
    private Mask mask;
    private int maskVersion = -1;

    // ---- Painting ----

    /** Starts a group (a press). */
    public void beginGroup() {
        endGroup();
        groups.add(stamps.size());
        painting = true;
    }

    /** Ends the group being painted; an empty one is forgotten. */
    public void endGroup() {
        if (!painting) return;
        painting = false;
        if (groups.get(groups.size() - 1) == stamps.size()) groups.remove(groups.size() - 1);
    }

    /** Whether a group is being painted. */
    public boolean painting() {
        return painting;
    }

    /** Drops the stamps of the group being painted (Esc during a stroke); returns whether it had any. */
    public boolean cancelGroup() {
        if (!painting) return false;
        painting = false;
        int start = groups.remove(groups.size() - 1);
        boolean had = start < stamps.size();
        truncate(start);
        return had;
    }

    /** Adds a stamp to the group being painted (a group is started if none is). */
    public AddResult add(ScatterArea.Stamp stamp) {
        Objects.requireNonNull(stamp);
        if (!painting) beginGroup();
        if (!stamps.isEmpty() && stamps.get(stamps.size() - 1).equals(stamp)) return AddResult.SKIPPED;
        if (stamps.size() >= MAX_STAMPS) return AddResult.FULL;
        if (stamp.erase()) {
            if (!erasesSomething(stamp)) return AddResult.SKIPPED;
        } else if (!fits(stamp)) {
            return AddResult.TOO_WIDE;
        }
        stamps.add(stamp);
        changed();
        return AddResult.ADDED;
    }

    private boolean erasesSomething(ScatterArea.Stamp stamp) {
        Mask current = mask();
        int r = stamp.radius();
        for (int dz = -r; dz <= r; dz++) {
            int half = isqrt(r * r - dz * dz);
            if (current.anyInRow(stamp.z() + dz, stamp.x() - half, stamp.x() + half)) return true;
        }
        return false;
    }

    /** Whether the painted bounding rectangle stays within {@link ScatterArea#MAX_COLUMNS} with {@code stamp}. */
    private boolean fits(ScatterArea.Stamp stamp) {
        long minX = (long) stamp.x() - stamp.radius(), maxX = (long) stamp.x() + stamp.radius();
        long minZ = (long) stamp.z() - stamp.radius(), maxZ = (long) stamp.z() + stamp.radius();
        for (ScatterArea.Stamp other : stamps) {
            if (other.erase()) continue;
            minX = Math.min(minX, other.x() - other.radius());
            maxX = Math.max(maxX, other.x() + other.radius());
            minZ = Math.min(minZ, other.z() - other.radius());
            maxZ = Math.max(maxZ, other.z() + other.radius());
        }
        return (maxX - minX + 1) * (maxZ - minZ + 1) <= ScatterArea.MAX_COLUMNS;
    }

    // ---- Box, undo, clear ----

    /** Makes the area {@code box} (the selection); the painted stamps are kept for when box mode is left. */
    public void useBox(Box box) {
        endGroup();
        this.box = Objects.requireNonNull(box);
        boxUndoable = true;
        changed();
    }

    /** Leaves box mode (the painted stamps are the area again); returns whether it was in box mode. */
    public boolean leaveBox() {
        if (box == null) return false;
        box = null;
        changed();
        return true;
    }

    /** The box, in box mode. */
    public Optional<Box> box() {
        return Optional.ofNullable(box);
    }

    /**
     * Undoes the newest step: box mode first, then the newest group. Returns whether anything changed. Steps made
     * before the last {@link #seal} are not undone.
     */
    public boolean undoGroup() {
        endGroup();
        if (box != null && boxUndoable) return leaveBox();
        if (groups.isEmpty()) return false;
        truncate(groups.remove(groups.size() - 1));
        return true;
    }

    /**
     * Makes everything painted so far (and box mode) permanent for {@link #undoGroup}: the stamps stay, but undo no
     * longer reaches them. For a commit, and when the server history moved since they were painted: Ctrl+Z then belongs
     * to the server history, which is newer.
     */
    public void seal() {
        endGroup();
        groups.clear();
        boxUndoable = false;
    }

    /** Whether {@link #undoGroup} has a step to undo. */
    public boolean undoable() {
        return (box != null && boxUndoable) || !groups.isEmpty();
    }

    /** Forgets every stamp and the box. Returns whether anything changed. */
    public boolean clear() {
        painting = false;
        boolean had = box != null || !stamps.isEmpty();
        box = null;
        stamps.clear();
        groups.clear();
        if (had) changed();
        return had;
    }

    private void truncate(int size) {
        if (size >= stamps.size()) return;
        stamps.subList(size, stamps.size()).clear();
        changed();
    }

    private void changed() {
        version++;
    }

    // ---- Reading ----

    /** Bumped on every change. */
    public int version() {
        return version;
    }

    public List<ScatterArea.Stamp> stamps() {
        return List.copyOf(stamps);
    }

    public int stampCount() {
        return stamps.size();
    }

    /** Groups painted (undo steps), not counting box mode. */
    public int groupCount() {
        return groups.size();
    }

    public boolean nearCap() {
        return stamps.size() >= WARN_STAMPS;
    }

    /** Whether a preview can be sent: a box, or at least one painted column. */
    public boolean isEmpty() {
        return box == null && mask().columns() == 0;
    }

    /** The area to plan, if there is one. */
    public Optional<ScatterArea> toArea() {
        if (box != null) return Optional.of(new ScatterArea.Region(box));
        if (isEmpty()) return Optional.empty();
        return Optional.of(new ScatterArea.Stamps(stamps));
    }

    /** Columns in the area (the box's, in box mode). */
    public long columns() {
        return box != null ? (long) box.sizeX() * box.sizeZ() : mask().columns();
    }

    /** The painted columns (box mode aside). */
    public Mask mask() {
        if (maskVersion != version || mask == null) {
            mask = rasterize(stamps);
            maskVersion = version;
        }
        return mask;
    }

    /** The stamps' raster, as {@code ScatterPlanner} sees them. */
    static Mask rasterize(List<ScatterArea.Stamp> stamps) {
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (ScatterArea.Stamp stamp : stamps) {
            if (stamp.erase()) continue;
            x0 = Math.min(x0, stamp.x() - stamp.radius());
            z0 = Math.min(z0, stamp.z() - stamp.radius());
            x1 = Math.max(x1, stamp.x() + stamp.radius());
            z1 = Math.max(z1, stamp.z() + stamp.radius());
        }
        if (x0 > x1) return EMPTY;
        int width = x1 - x0 + 1;
        BitSet bits = new BitSet(width * (z1 - z0 + 1));
        for (ScatterArea.Stamp stamp : stamps) {
            int r = stamp.radius();
            for (int dz = -r; dz <= r; dz++) {
                int z = stamp.z() + dz;
                if (z < z0 || z > z1) continue;
                int half = isqrt(r * r - dz * dz);
                int from = Math.max(x0, stamp.x() - half), to = Math.min(x1, stamp.x() + half);
                if (from > to) continue;
                int row = (z - z0) * width - x0;
                if (stamp.erase()) {
                    bits.clear(row + from, row + to + 1);
                } else {
                    bits.set(row + from, row + to + 1);
                }
            }
        }
        return new Mask(x0, z0, x1, z1, bits);
    }

    /** The largest h with h² <= v, for 0 <= v. */
    static int isqrt(int v) {
        int h = (int) Math.sqrt(v);
        while ((long) h * h > v) h--;
        while ((long) (h + 1) * (h + 1) <= v) h++;
        return h;
    }
}
