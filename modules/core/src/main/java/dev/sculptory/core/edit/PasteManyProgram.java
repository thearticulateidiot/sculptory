package dev.sculptory.core.edit;

import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.transform.Transform;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes an {@link OpSpec.PasteMany} (Generate's Stamp): every placement compiled exactly as the matching
 * {@link OpSpec.Paste} ({@link PasteProgram}: the transform and its flip upside down, include air, {@link
 * PasteOptions#into} judged per cell, tiles copied), all in one program, so one job and one history entry. Within a
 * section the placements are applied last first and each claims the cells it covers ({@link ClaimingProgram}), so where
 * two overlap the later one wins exactly. As a symmetric op's copy it shares the claims of the copies before it.
 *
 * <p>Each placement is compiled by {@link PasteProgram#compilePlacement}: a free orientation resamples, and a symmetric
 * copy of the op is its own placements under the image (a free one as the exact image of what it writes). The source is
 * scanned once per transform ({@link PasteProgram#scan}), not once per placement, for every exact placement the build
 * height leaves whole; one it cuts, or a free one, is scanned on its own. A placement entirely above or below the build
 * height is left out (a lone paste there is refused); the op is refused when every placement is.
 */
final class PasteManyProgram implements ClaimingProgram {
    private final List<PasteProgram> placements;
    /** Per section, the placements writing it, in placement order. */
    private final Long2ObjectOpenHashMap<IntArrayList> bySection;
    private final long[] order;
    private final Box bounds;
    private final long estimatedCells;

    private PasteManyProgram(List<PasteProgram> placements, Long2ObjectOpenHashMap<IntArrayList> bySection, long[] order,
                             Box bounds, long estimatedCells) {
        this.placements = placements;
        this.bySection = bySection;
        this.order = order;
        this.bounds = bounds;
        this.estimatedCells = estimatedCells;
    }

    /** The op without symmetry: its placements as they are. */
    static PasteManyProgram compile(OpSpec.PasteMany op, SourceBlocks source, StateSpace states,
                                    RegionProgram.Height height) {
        return compile(op, Symmetry.Image.IDENTITY, op.symmetry(), source, states, height, new HashMap<>());
    }

    /**
     * The op's copy under {@code image} of its {@code symmetry}: each of the op's own placements as
     * {@link PasteProgram#compilePlacement} pastes it under that image (an exact placement turned and moved with the
     * image, a free one as the exact image of what it writes). {@code scans} is shared between the copies.
     *
     * @throws IllegalArgumentException if no placement reaches the build height, a placement leaves the coordinate
     *     range, or the source holds a state outside {@code states}
     */
    static PasteManyProgram compile(OpSpec.PasteMany op, Symmetry.Image image, Symmetry symmetry, SourceBlocks source,
                                    StateSpace states, RegionProgram.Height height,
                                    Map<Transform, PasteProgram.Scan> scans) {
        List<PasteProgram> programs = new ArrayList<>(op.placements().size());
        Long2ObjectOpenHashMap<IntArrayList> bySection = new Long2ObjectOpenHashMap<>();
        LongOpenHashSet sections = new LongOpenHashSet();
        Box bounds = null;
        long cells = 0;
        for (OpSpec.PasteMany.Placement placement : op.placements()) {
            // The images are horizontal: a placement the build height leaves out leaves out each of its images.
            Box target = PasteGeometry.pasteTarget(source.size(), source.anchor(), placement.orientation(),
                    placement.origin());
            if (height.clip(target) == null) continue;
            PasteProgram program = PasteProgram.compilePlacement(op.src(), placement, op.o(), symmetry, image, source,
                    states, height, scans);
            int index = programs.size();
            programs.add(program);
            for (long key : program.sectionOrder()) {
                sections.add(key);
                bySection.computeIfAbsent(key, k -> new IntArrayList(2)).add(index);
            }
            bounds = bounds == null ? program.bounds() : CopySupport.union(bounds, program.bounds());
            long sum = cells + program.estimatedCells();
            cells = sum < 0 ? Long.MAX_VALUE : sum;
        }
        if (programs.isEmpty()) throw new IllegalArgumentException("Every placement is outside the build height");
        return new PasteManyProgram(List.copyOf(programs), bySection, CopySupport.ordered(sections), bounds, cells);
    }

    @Override
    public String label() {
        return "Paste";
    }

    /** The union of the placements' boxes within the build height. */
    @Override
    public Box bounds() {
        return bounds;
    }

    /** The placements' estimates added up (a cell two placements cover counts twice). */
    @Override
    public long estimatedCells() {
        return estimatedCells;
    }

    @Override
    public long[] sourceSections() {
        return new long[0];
    }

    @Override
    public long[] sectionOrder() {
        return order.clone();
    }

    /** The columns the placements touching section {@code key} read (their two-cell blocks' other halves). */
    @Override
    public long[] readColumns(long key) {
        IntArrayList touching = bySection.get(key);
        if (touching == null) return NO_COLUMNS;
        LongLinkedOpenHashSet columns = new LongLinkedOpenHashSet();
        for (int i = 0; i < touching.size(); i++) {
            for (long column : placements.get(touching.getInt(i)).readColumns(key)) columns.add(column);
        }
        return columns.isEmpty() ? NO_COLUMNS : columns.toLongArray();
    }

    @Override
    public void compute(long key, SectionBuffer before, SectionBuffer out, ComputeContext ctx) {
        compute(key, before, out, ctx, null);
    }

    @Override
    public void compute(long key, SectionBuffer before, SectionBuffer out, ComputeContext ctx, long[] claimed) {
        IntArrayList writers = bySection.get(key);
        if (writers == null) return;
        // The placements share claims even without symmetric copies: the later one wins.
        long[] claims = claimed != null ? claimed : writers.size() > 1 ? new long[CLAIM_WORDS] : null;
        for (int k = writers.size() - 1; k >= 0; k--) {
            placements.get(writers.getInt(k)).compute(key, before, out, ctx, claims);
        }
    }

    /** The placements' programs, in order (tests). */
    List<PasteProgram> placements() {
        return placements;
    }
}
