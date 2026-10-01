package dev.sculptory.fabric.client.editor.check;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.block.BlockState;

/** Questions about what an edit changed (the {@link Cells#diff} of before and after). */
final class Changes {
    private Changes() {}

    /** A block (not air, not a fluid) became air. */
    static boolean emptied(Cells.Change change) {
        return solid(change.before()) && change.after().isAir();
    }

    /** Air became a block. */
    static boolean filled(Cells.Change change) {
        return change.before().isAir() && solid(change.after());
    }

    static boolean solid(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty();
    }

    static boolean fluid(BlockState state) {
        return !state.getFluidState().isEmpty();
    }

    static long count(List<Cells.Change> changes, Predicate<Cells.Change> which) {
        return changes.stream().filter(which).count();
    }

    /** "" when every change passes {@code ok}, else how many don't and the first few. */
    static String offenders(List<Cells.Change> changes, Predicate<Cells.Change> ok) {
        List<Cells.Change> bad = changes.stream().filter(ok.negate()).toList();
        return bad.isEmpty() ? "" : Cells.summary(bad, 4);
    }

    /** The distance from a cell's centre to a point. */
    static double distance(Cells.Change change, double x, double y, double z) {
        double dx = change.x() + 0.5 - x;
        double dy = change.y() + 0.5 - y;
        double dz = change.z() + 0.5 - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Whether every change lies within {@code radius} of a point. */
    static Predicate<Cells.Change> near(double x, double y, double z, double radius) {
        return change -> distance(change, x, y, z) <= radius;
    }
}
