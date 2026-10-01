package dev.sculptory.core.brush;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.world.WorldReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One step of the Shape brush (a dab and its copies) cut into parts, so the server can write a large step over several
 * ticks: a part is one placement's run of y layers holding at most {@value #PART_CELLS} of the cells it reads (its
 * shape's, or with a hollow thickness its shell's; at least one layer, at most 65 × 65 cells). Running every part in
 * order writes exactly what {@link ShapeKernel} writes in one go, cell for cell and in the same order: the kernel runs these same parts, and a cell's new state depends only on the
 * state it holds, each cell being read once right before it is written. Between parts the world may change; each cell
 * is decided by what it holds when its part runs.
 *
 * <p>Each part's area ({@link #nextArea}) is its placement's box over the part's layers, which the caller can check
 * (loaded chunks, locks) before running it. Not thread-safe.
 */
public final class ShapeStep {
    /**
     * Most cells a part reads (unless one layer holds more): about 4 ms of writes on the test machine, a dedicated
     * server's default brush budget.
     */
    public static final int PART_CELLS = 16_384;

    private final ShapeKernel.Pass pass;
    /** Per part: placement, first and last layer, cells. */
    private final int[] placement, yFrom, yTo;
    private final long[] cells;
    private final int parts;
    private final long total;
    private int next;

    private ShapeStep(ShapeKernel.Pass pass) {
        this.pass = pass;
        List<int[]> ranges = new ArrayList<>();
        List<Long> counts = new ArrayList<>();
        long sum = 0;
        for (int i = 0; i < pass.placements(); i++) {
            int low = pass.yLow(i), high = pass.yHigh(i);
            int from = low;
            long partCells = 0;
            for (int y = low; y <= high; y++) {
                long layer = pass.layerCells(i, y);
                if (y > from && partCells + layer > PART_CELLS) {
                    ranges.add(new int[] {i, from, y - 1});
                    counts.add(partCells);
                    from = y;
                    partCells = 0;
                }
                partCells += layer;
            }
            if (low <= high) {
                ranges.add(new int[] {i, from, high});
                counts.add(partCells);
            }
        }
        parts = ranges.size();
        placement = new int[parts];
        yFrom = new int[parts];
        yTo = new int[parts];
        cells = new long[parts];
        for (int p = 0; p < parts; p++) {
            placement[p] = ranges.get(p)[0];
            yFrom[p] = ranges.get(p)[1];
            yTo[p] = ranges.get(p)[2];
            cells[p] = counts.get(p);
            sum += cells[p];
        }
        total = sum;
    }

    /**
     * The step of {@code dabs} under {@code s}: exactly the dab the player laid and its {@link Symmetry#copies}, in that
     * order ({@link SymmetricStep#of} lists them so), checked as {@link BrushKernel#applyStep} checks a step.
     *
     * @throws IllegalArgumentException for another tool, a step that is not the first dab and its copies, a dab or copy
     *     outside the world, or a material state outside {@code w}'s state space
     */
    public static ShapeStep of(BrushSpec s, List<Dab> dabs, StrokeState st, WorldReader w) {
        Objects.requireNonNull(s);
        Objects.requireNonNull(dabs);
        Objects.requireNonNull(st);
        Objects.requireNonNull(w);
        if (s.tool() != BrushTool.SHAPE) throw new IllegalArgumentException("The SHAPE kernel cannot apply a " + s.tool() + " brush");
        if (dabs.isEmpty() || dabs.size() > Symmetry.MAX_COPIES) {
            throw new IllegalArgumentException("A step holds 1-" + Symmetry.MAX_COPIES + " dabs, not " + dabs.size());
        }
        for (Dab d : dabs) {
            Objects.requireNonNull(d);
            if (!TerrainKernel.insideLimit(d)) throw new IllegalArgumentException("Dab outside the world: " + d);
        }
        Dab dab = dabs.get(0);
        if (!dabs.equals(s.symmetry().copies(dab))) {
            throw new IllegalArgumentException("A Shape step is its first dab and that dab's copies in order, not " + dabs);
        }
        st.checkMaterial(s.material(), w.states());
        List<ShapeStamp.Imaged> placements = ShapeStamp.imagedPlacements(s.shapeSpec(), s.radius(), s.symmetry(), dab);
        ShapeKernel.Stamp[] stamps = new ShapeKernel.Stamp[placements.size()];
        Symmetry.Image[] images = new Symmetry.Image[placements.size()];
        for (int i = 0; i < stamps.length; i++) {
            ShapeStamp.Placement placement = placements.get(i).placement();
            if (!ShapeKernel.insideLimit(placement.box())) {
                throw new IllegalArgumentException("Shape copy outside the world: " + placement);
            }
            stamps[i] = new ShapeKernel.Stamp(placement, s.shapeSpec().hollow());
            images[i] = placements.get(i).image();
        }
        return new ShapeStep(new ShapeKernel.Pass(s, stamps, images, w));
    }

    /**
     * The cells the step reads (its shapes', or with a hollow thickness their shells'; within the build height and the
     * clip box, counting cells an earlier placement also covers): the measure its parts, and the server's work units
     * ({@link ShapeStamp#units}), are sized by.
     */
    public long cells() {
        return total;
    }

    /** The cells of the parts not run yet. */
    public long remainingCells() {
        long left = 0;
        for (int p = next; p < parts; p++) left += cells[p];
        return left;
    }

    /** How many parts the step has. */
    public int parts() {
        return parts;
    }

    /** Whether every part has run. */
    public boolean done() {
        return next == parts;
    }

    /** The cells the next part reads ({@link #cells()}). */
    public long nextCells() {
        requireNext();
        return cells[next];
    }

    /** The next part's area: its placement's box over its layers. */
    public Box nextArea() {
        requireNext();
        Box box = pass.stamp(placement[next]).box;
        return new Box(new BlockPos(box.min().x(), yFrom[next], box.min().z()), new BlockPos(box.max().x(), yTo[next],
                box.max().z()));
    }

    /** Runs the next part: its changed cells go to {@code out}. */
    public void runNext(CellSink out) {
        Objects.requireNonNull(out);
        requireNext();
        int part = next++;
        pass.place(placement[part], yFrom[part], yTo[part], out);
    }

    /** Runs every part left. */
    public void runAll(CellSink out) {
        while (!done()) runNext(out);
    }

    private void requireNext() {
        if (next == parts) throw new IllegalStateException("The step is done");
    }
}
