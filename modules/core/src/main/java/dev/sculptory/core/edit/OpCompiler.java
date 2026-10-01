package dev.sculptory.core.edit;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.Regions;
import dev.sculptory.core.state.StateSpace;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Compiles an {@link OpSpec} into a section-wise {@link EditProgram}.
 *
 * <p>The Select operations work on any {@link Region} but {@link Region.Uploaded}, which the server resolves to its
 * cells first. Region programs ({@code Fill}, {@code Replace},
 * {@code Erase}, {@code Hollow}, {@code Walls}) report the region's bounds clipped to the build height
 * ({@link CompileContext#bottomY()}) as {@link EditProgram#bounds()}, list only the sections holding region cells (in
 * {@link SectionOrder} order; for a box's Hollow and Walls, only those holding cells they may write), and write only
 * cells whose state changes. {@code estimatedCells()} is the number of region cells inside the build height, before
 * masks (for a box's Hollow and Walls, the cells of the inner box or sides). Hollow's inside and Walls' sides are
 * measured on the whole region, then clipped. See {@code RegionProgram}.
 *
 * <p>Copying programs write a cell only when its state or tile content changes:
 * <ul>
 *   <li>{@code Paste} reads the {@link CompileContext#source source} content and maps each target cell back
 *       through the inverse transform; states are rotated/mirrored (cached per handle), tiles are copied as they
 *       are. It reads no world sections. See {@code PasteProgram}.</li>
 *   <li>{@code Move} and {@code Stack} list the region's sections in {@link EditProgram#sourceSections()} and read
 *       only that pre-write snapshot, so overlapping sources and destinations are exact; only region cells move or
 *       are copied. A moved region's bounds keep their minimum corner at {@code bounds.min() + offset} after the
 *       transform. See {@code MoveProgram} and {@code StackProgram}.</li>
 *   <li>{@code ScatterCommit} writes the {@link MultiPaste} that {@link CompileContext#scatterPlan} resolves the
 *       plan id to: every placement as a paste without air, in one program (so one job and one history entry);
 *       the later placement wins where two overlap. Labelled "Scatter". See {@code MultiPasteProgram}.</li>
 * </ul>
 */
public final class OpCompiler {
    /**
     * The most rows a {@link Region.Shape} whose box holds more than 2^29 cells may span, counted as the product of the
     * two shorter sides of its box: counting its cells or listing its sections goes along its longest side, one row span
     * per row, in {@code BigInteger} arithmetic (0.15 to 2 µs a row depending on the shape, so at most about 60 ms at
     * this size; measured: {@code RegionProgramTest}), and a larger shape is refused before either
     * ({@link EditTooLargeException}). A smaller box needs no cap: at most 2^29 cells span at most 659,344 rows (the
     * two shorter sides of an 812-cube), counted in {@code long} arithmetic in about 30 ms.
     */
    public static final long MAX_BIG_SHAPE_ROWS = 1L << 15;
    /**
     * {@link #MAX_BIG_SHAPE_ROWS} for a player with {@code limit.bypass} (a server passes it in
     * {@link CompileContext#maxBigShapeRows}): measured 0.5 to 0.7 s for a full count at this size (a 1,024-cube sphere, a
     * 1,000,000 × 1,024 × 1,024 ellipsoid), about 2 s at the slowest rows measured ({@code RegionProgramTest}).
     */
    public static final long MAX_BIG_SHAPE_ROWS_BYPASS = 1L << 20;
    /** Boxes of more cells than this are counted in {@code BigInteger} arithmetic, and their shapes' rows capped. */
    public static final long LONG_PATH_VOLUME = 1L << 29;
    /** The thickest Hollow and Walls (the editor's slider and the Shape brush stop there too). */
    public static final int MAX_THICKNESS = 16;

    private OpCompiler() {}

    /**
     * @throws EditTooLargeException if the op's {@link #targetVolume target} or {@link #sourceVolume source}
     *     volume exceeds {@link CompileContext#maxCells()}, its region is a shape of too many rows
     *     ({@link #checkShape}), or a region other than a box reaches more than {@link CompileContext#maxSections()}
     *     sections; checked before any work (the sections as soon as they are listed). For a scatter commit, the
     *     plan's cells (its placements' present non-air source cells) are checked instead, once the plan is known.
     * @throws IllegalArgumentException if the op is invalid in this context (e.g. a pattern or mask state
     *     outside the state space, a box entirely outside the build height, a paste source or scatter plan the
     *     context does not know, a scatter plan with nothing inside the build height, a box spanning more than
     *     4,194,304 sections, or a Hollow or Walls thicker than {@link #MAX_THICKNESS}), or its region is a
     *     {@link Region.Uploaded} (resolve it to its cells first)
     */
    public static EditProgram compile(OpSpec s, CompileContext c) {
        return compile(s, OpSymmetry.copies(Objects.requireNonNull(s)), c);
    }

    /**
     * {@link #compile(OpSpec, CompileContext)} with the op's symmetric copies already expanded
     * ({@link OpSymmetry#copies}), so a caller that checked them need not map a cell set again.
     */
    public static EditProgram compile(OpSpec s, List<OpSymmetry.Copy> copies, CompileContext c) {
        Objects.requireNonNull(s);
        Objects.requireNonNull(copies);
        Objects.requireNonNull(c);
        Region region = OpRegions.region(s);
        if (region instanceof Region.Uploaded) {
            throw new IllegalArgumentException("An uploaded region must be resolved to its cells first");
        }
        int thickness = s instanceof OpSpec.Hollow hollow ? hollow.thickness() : s instanceof OpSpec.Walls walls
                ? walls.thickness() : 1;
        if (thickness > MAX_THICKNESS) {
            throw new IllegalArgumentException("Thickness " + thickness + " > " + MAX_THICKNESS);
        }
        if (region != null) checkShape(region, c.maxBigShapeRows());
        StateSpace states = Objects.requireNonNull(c.states());
        int bottom = c.bottomY();
        long top = (long) c.topYExclusive() - 1;
        if (top < bottom) throw new IllegalArgumentException("Empty build height");
        RegionProgram.Height height = new RegionProgram.Height(bottom, (int) top);
        long budget = c.maxCells();
        long sections = c.maxSections();
        // A paste's volume needs its source; it is checked once the source is known. Counts stop past the budget.
        // Volumes count every symmetric copy.
        if (!(s instanceof OpSpec.Paste) && budget != Long.MAX_VALUE) {
            checkBudget(Math.max(targetVolume(s, null, budget), sourceVolume(s, budget)), budget);
        }
        SourceBlocks source = null;
        if (s instanceof OpSpec.Paste paste) {
            source = Objects.requireNonNull(c.source(paste.src())).orElseThrow(
                    () -> new IllegalArgumentException("Unknown paste source: " + paste.src()));
            // The cells the paste can write: the source's present cells, not its box (a sparse source writes far
            // fewer), times the symmetric copies, as the server admits it.
            long cells = source.cells().cellCount();
            int pasteCopies = OpSymmetry.copyCount(paste);
            checkBudget(cells > Long.MAX_VALUE / pasteCopies ? Long.MAX_VALUE : cells * pasteCopies, budget);
        }
        Symmetry symmetry = OpSymmetry.of(s);
        if (copies.isEmpty()) throw new IllegalArgumentException("An op has at least one copy");
        byte[] table = layerTable(s, c, states);
        if (copies.size() == 1) {
            return compileCopy(copies.get(0).op(), null, c, states, height, sections, source, budget, table);
        }
        List<ClaimingProgram> programs = new ArrayList<>(copies.size());
        for (OpSymmetry.Copy copy : copies) {
            CopyFrame frame = copy.image() == Symmetry.Image.IDENTITY ? null : new CopyFrame(symmetry, copy.image(), states);
            programs.add((ClaimingProgram) compileCopy(copy.op(), frame, c, states, height, sections, source, budget,
                    table));
        }
        return new SymmetricProgram(programs);
    }

    /**
     * The table an op's copies share: the cell classes of Overlay and Naturalize ({@link ColumnProgram#classes}), the
     * candidate states of Update blocks; {@code null} for any other op. Update blocks needs the context's
     * {@link CompileContext#neighbourShapes}: refused without them.
     */
    private static byte[] layerTable(OpSpec s, CompileContext c, StateSpace states) {
        return switch (s) {
            case OpSpec.Overlay overlay -> ColumnProgram.classes(states);
            case OpSpec.Naturalize n -> ColumnProgram.classes(states, n.top(), n.middle(), n.bottom());
            case OpSpec.UpdateBlocks update -> {
                if (c.neighbourShapes() == null) {
                    throw new IllegalArgumentException("Update blocks needs the server's world to shape blocks");
                }
                yield UpdateProgram.candidates(states);
            }
            default -> null;
        };
    }

    /** One copy of an op (the op itself without symmetry), under {@code frame} for a copy other than the original. */
    private static EditProgram compileCopy(OpSpec s, CopyFrame frame, CompileContext c, StateSpace states,
                                           RegionProgram.Height height, long sections, SourceBlocks source, long budget,
                                           byte[] table) {
        return switch (s) {
            case OpSpec.Fill fill -> RegionProgram.solid("Fill", fill.region(), height, bind(fill.mask(), states),
                    checked(fill.pattern(), states), states, sections, frame);
            case OpSpec.Replace replace -> RegionProgram.solid("Replace", replace.region(), height,
                    bind(replace.from(), states), checked(replace.to(), states), states, sections, frame);
            case OpSpec.Erase erase -> RegionProgram.solid("Erase", erase.region(), height, bind(erase.mask(), states),
                    new Pattern.Single(states.air()), states, sections, frame);
            case OpSpec.Hollow hollow -> RegionProgram.inside("Hollow", hollow.region(), height, hollow.thickness(),
                    checked(hollow.inside(), states), states, sections, frame);
            case OpSpec.Walls walls -> RegionProgram.walls("Walls", walls.region(), height, walls.thickness(),
                    checked(walls.pattern(), states), states, sections, frame);
            case OpSpec.Paste paste -> PasteProgram.compile(paste, source, states, height);
            case OpSpec.Move move -> MoveProgram.compile(move, checked(move.leave(), states), states, height, sections, frame,
                    Objects.requireNonNull(c.sourceMask()));
            case OpSpec.Stack stack -> StackProgram.compile(stack, states, height, sections);
            case OpSpec.ScatterCommit scatter -> {
                MultiPaste plan = Objects.requireNonNull(c.scatterPlan(scatter.planId())).orElseThrow(
                        () -> new IllegalArgumentException("Unknown scatter plan: " + scatter.planId()));
                yield MultiPasteProgram.compile("Scatter", plan, states, height, budget);
            }
            case OpSpec.Overlay overlay -> ColumnProgram.overlay(overlay.region(), height, checked(overlay.pattern(), states),
                    overlay.depth(), states, table, sections, frame);
            case OpSpec.Naturalize n -> ColumnProgram.naturalize(n.region(), height, checked(n.top(), states), n.topDepth(),
                    checked(n.middle(), states), n.middleDepth(), checked(n.bottom(), states), states, table, sections, frame);
            case OpSpec.UpdateBlocks update -> new UpdateProgram(update.region(), height, c.neighbourShapes(), states, table,
                    sections);
        };
    }

    /**
     * An upper bound on the cells an op may write, so a caller can refuse an op before compiling it. It counts the
     * region's cells, not its bounds (a box's volume, an uploaded set's announced count, a cell set's size, a shape's
     * count along its longest side, so bound a shape from untrusted input with {@link #checkShape} first). It ignores
     * the build height (cells outside the world count) and masks, and counts every symmetric copy (the single
     * copy's cells times {@link OpSymmetry#copyCount}). Saturates at {@link Long#MAX_VALUE}.
     * <ul>
     *   <li>Fill, Replace, Erase, Hollow, Walls, Naturalize, Update blocks: the region's cells.</li>
     *   <li>Overlay: the region's cells plus {@code depth} cells over each column of its bounds (its layer may reach
     *       above the region).</li>
     *   <li>Paste: the source box volume ({@code pasteSourceSize}, which is required for a paste and ignored
     *       otherwise): an upper bound; {@link #compile} and the server admit a paste on its source's present cells
     *       instead, so a sparse source (a magic-select copy, a generated road) counts what it writes.</li>
     *   <li>Move: the destination plus the vacated cells: for a box {@code 2 * volume - overlap}, for any other region
     *       twice its cells (its overlap with itself would need the moved cells, worked out only when compiling).</li>
     *   <li>Stack: the region's cells times the count.</li>
     *   <li>ScatterCommit: 0 (its size lives in the server's plan: {@code ScatterPlan.totalCells()}; compile checks
     *       it against {@link CompileContext#maxCells()}).</li>
     * </ul>
     */
    public static long targetVolume(OpSpec s, BlockPos pasteSourceSize) {
        return targetVolume(s, pasteSourceSize, Long.MAX_VALUE);
    }

    /**
     * {@link #targetVolume(OpSpec, BlockPos)}, exact when at most {@code cap}, otherwise some value above it: a shape's
     * count stops once past the cap, so checking a request against a limit costs little however large its box.
     */
    public static long targetVolume(OpSpec s, BlockPos pasteSourceSize, long cap) {
        Objects.requireNonNull(s);
        int copies = OpSymmetry.copyCount(s);
        long single = switch (s) {
            case OpSpec.Fill fill -> cells(fill.region(), cap);
            case OpSpec.Replace replace -> cells(replace.region(), cap);
            case OpSpec.Erase erase -> cells(erase.region(), cap);
            case OpSpec.Hollow hollow -> cells(hollow.region(), cap);
            case OpSpec.Walls walls -> cells(walls.region(), cap);
            case OpSpec.Paste paste -> {
                Objects.requireNonNull(pasteSourceSize, "a paste's volume needs its source size");
                if (pasteSourceSize.x() < 0 || pasteSourceSize.y() < 0 || pasteSourceSize.z() < 0) {
                    throw new IllegalArgumentException("Negative paste source size " + pasteSourceSize);
                }
                yield product(pasteSourceSize.x(), pasteSourceSize.y(), pasteSourceSize.z());
            }
            case OpSpec.Move move -> move.region() instanceof Region.Cuboid ? moveVolume(move)
                    : product(cells(move.region(), cap / 2 + 1), 2);
            case OpSpec.Stack stack -> product(cells(stack.region(), cap / stack.count() + 1), stack.count());
            case OpSpec.ScatterCommit scatter -> 0;
            // The layer may reach above the region: up to depth cells over each column of its bounds.
            case OpSpec.Overlay overlay -> saturatingAdd(cells(overlay.region(), cap), product(overlay.box().sizeX(),
                    overlay.box().sizeZ(), overlay.depth()));
            case OpSpec.Naturalize naturalize -> cells(naturalize.region(), cap);
            case OpSpec.UpdateBlocks update -> cells(update.region(), cap);
        };
        return product(single, copies);
    }

    /** The world cells an op snapshots before writing (its region's cells for Move and Stack, times the symmetric copies, else 0). Unclipped. */
    public static long sourceVolume(OpSpec s) {
        return sourceVolume(s, Long.MAX_VALUE);
    }

    /** {@link #sourceVolume(OpSpec)}, exact when at most {@code cap}, otherwise some value above it. */
    public static long sourceVolume(OpSpec s, long cap) {
        Objects.requireNonNull(s);
        long single = switch (s) {
            case OpSpec.Move move -> cells(move.region(), cap);
            case OpSpec.Stack stack -> cells(stack.region(), cap);
            default -> 0;
        };
        return product(single, OpSymmetry.copyCount(s));
    }

    /** {@link #checkShape(Region, long)} with the cap for players without {@code limit.bypass}. */
    public static void checkShape(Region region) {
        checkShape(region, MAX_BIG_SHAPE_ROWS);
    }

    /**
     * Refuses a {@link Region.Shape} spanning too many rows ({@link EditTooLargeException}), in constant time, before
     * anything counts its cells: for a box of more than {@link #LONG_PATH_VOLUME} cells, the product of the two shorter
     * sides of its box must be at most {@code maxBigRows} ({@link #MAX_BIG_SHAPE_ROWS}, or
     * {@link #MAX_BIG_SHAPE_ROWS_BYPASS}). Smaller boxes and other regions pass.
     */
    public static void checkShape(Region region, long maxBigRows) {
        if (!(Objects.requireNonNull(region) instanceof Region.Shape shape)) return;
        if (shape.box().volume() <= LONG_PATH_VOLUME) return; // at most 659,344 rows, in long arithmetic
        long rows = Regions.shapeRows(shape, Integer.MIN_VALUE, Integer.MAX_VALUE);
        if (rows > maxBigRows) {
            throw new EditTooLargeException("A shape spanning " + rows + " rows (its two shorter sides) > " + maxBigRows,
                    rows, maxBigRows);
        }
    }

    /** A region's cells, exact when at most {@code cap} (a shape's count stops once past it). */
    private static long cells(Region region, long cap) {
        return region instanceof Region.Shape ? Regions.cellsBetween(region, Integer.MIN_VALUE, Integer.MAX_VALUE, cap)
                : region.cellCount();
    }

    private static void checkBudget(long cells, long budget) {
        if (cells > budget) throw new EditTooLargeException(cells, budget);
    }

    /** Destination plus vacated cells of a move: the transform keeps the volume, the offset decides the overlap. */
    private static long moveVolume(OpSpec.Move move) {
        Box box = move.box();
        int sizeX = move.t().sizeX(box.sizeX(), box.sizeZ()), sizeZ = move.t().sizeZ(box.sizeX(), box.sizeZ());
        long overlap = product(
                overlap(box.min().x(), box.max().x(), (long) box.min().x() + move.offset().x(), sizeX),
                overlap(box.min().y(), box.max().y(), (long) box.min().y() + move.offset().y(), box.sizeY()),
                overlap(box.min().z(), box.max().z(), (long) box.min().z() + move.offset().z(), sizeZ));
        long volume = box.volume();
        long twice = volume > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : 2 * volume;
        return twice == Long.MAX_VALUE ? twice : twice - overlap;
    }

    /** Cells shared on one axis by [min, max] and [start, start + size - 1]. */
    private static long overlap(int min, int max, long start, int size) {
        long end = start + size - 1;
        return Math.max(0, Math.min(max, end) - Math.max(min, start) + 1);
    }

    /** a + b for non-negative terms, saturating at {@link Long#MAX_VALUE}. */
    private static long saturatingAdd(long a, long b) {
        long sum = a + b;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }

    /** a * b * c for non-negative factors, saturating at {@link Long#MAX_VALUE}. */
    private static long product(long a, long b, long c) {
        return product(product(a, b), c);
    }

    /** a * b for non-negative factors, saturating at {@link Long#MAX_VALUE}. */
    private static long product(long a, long b) {
        if (a == 0 || b == 0) return 0;
        return a > Long.MAX_VALUE / b ? Long.MAX_VALUE : a * b;
    }

    /**
     * Throws if the pattern writes a state outside {@code states}, is a {@link Pattern.Waterlog} of a state that is
     * not a fluid source, or lays a mix out by steepness (only Palette Paint measures the ground's steepness).
     */
    static Pattern checked(Pattern pattern, StateSpace states) {
        switch (pattern) {
            case Pattern.Single single -> checkState(single.state(), states);
            case Pattern.Weighted weighted -> {
                for (int entry = 0; entry < weighted.size(); entry++) checkState(weighted.state(entry), states);
            }
            case Pattern.Arranged arranged -> {
                if (arranged.steepness()) {
                    throw new IllegalArgumentException("A Steepness pattern works only in Palette Paint");
                }
                for (int entry = 0; entry < arranged.mix().size(); entry++) checkState(arranged.mix().state(entry), states);
            }
            case Pattern.Waterlog waterlog -> {
                checkState(waterlog.fluidSource(), states);
                if (!Pattern.isFluidSource(states, waterlog.fluidSource())) {
                    throw new IllegalArgumentException("Waterlog needs a fluid source state, not "
                            + states.format(waterlog.fluidSource()));
                }
            }
            case Pattern.Dry dry -> {
            }
            case Pattern.SetProperty set -> {
                checkState(set.template(), states);
                if (!set.validIn(states)) {
                    throw new IllegalArgumentException(states.format(set.template()) + " has no property "
                            + set.property());
                }
            }
            case Pattern.KeepShape keep -> checked(keep.inner(), states);
            // A remap names blocks, not states: a block the state space lacks swaps nothing.
            case Pattern.Remap remap -> {
            }
        }
        return pattern;
    }

    private static CellPredicate bind(CellMask mask, StateSpace states) {
        try {
            return mask.bind(states);
        } catch (IndexOutOfBoundsException unknownState) {
            throw new IllegalArgumentException("Mask names a state outside the state space", unknownState);
        }
    }

    private static void checkState(int state, StateSpace states) {
        if (state < 0 || state >= states.size()) {
            throw new IllegalArgumentException("Pattern state " + state + " is outside the state space");
        }
    }
}
