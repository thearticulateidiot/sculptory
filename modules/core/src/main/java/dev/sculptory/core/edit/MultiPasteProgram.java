package dev.sculptory.core.edit;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.SurfaceScan;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.edit.CopySupport.InverseMap;
import dev.sculptory.core.edit.CopySupport.StateMapper;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.state.Water;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.core.world.WorldReader;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a {@link MultiPaste}: every placement pasted as {@link PasteProgram} would (without air), in one program.
 *
 * <p>Each source is split into <i>parts</i>, one per non-empty 16³ source section: the occupied bounds of its present
 * non-air cells. Compiling lists, per target section, the (placement, part) pairs whose transformed part box reaches
 * it, so {@code compute} only visits those. A dense part (at least 1/{@value #DENSE_RATIO} of its box is cells) is
 * visited as its transformed box, mapping each target cell back through the inverse transform; a sparse part is
 * visited as its list of cells, mapped forward and kept when they fall in the section. A part's box is at most 16
 * wide, so it reaches at most 8 target sections: either way {@code compute} visits at most {@value #DENSE_RATIO}
 * cells per placed cell, which the cell budget bounds ({@link #visitBound()}).
 *
 * <p>Within a section the pairs are applied last placement first, and a cell a later placement covers is never
 * written by an earlier one: the later placement wins, exactly. States are mapped through one cached
 * {@link StateMapper} per distinct transform (the mapping does not depend on the source).
 *
 * <p>Under any rule but {@link MultiPaste.Replace#ANY} (an OPEN rule below; each placement follows its source's rule,
 * {@link MultiPaste#rule}), the first section that
 * reaches a placement decides it: every one of its cells is read once through {@link ComputeContext#world()} and the
 * placement is skipped whole unless all are loaded, open and writable ({@link ComputeContext#mayWrite}: protection and
 * the world border). {@link #readColumns} lists the other chunks of the placements a section would decide, so a runner
 * can load them first (with no world offered, only the per-cell guards apply). Each write is then guarded twice: in
 * {@code compute} against the section's captured content, and in {@link #mayReplace} against the cell's live content
 * right before the runner writes it (a section may be written over several ticks). Right before a placement's first
 * cell is written, all of its cells in chunks still loaded are checked again against the live world, so one built on
 * since the decision skips it whole (this re-check is not charged to the runner's budget; it costs at most the
 * placement's cells once). A placement a guard refuses once is cut short: none of its remaining cells is written, in this
 * section or later ones (what it already wrote stays). Placements skipped or cut short are reported once each through
 * {@link ComputeContext#conflicts}.
 *
 * <p>Half of a block that stands two cells tall ({@link StateFlags#DOUBLE_TALL}: tall grass, a sunflower, a door) is
 * open to a placement only if the placement also writes the cell of its other half, so no half is left standing
 * alone. Placements may not overlap under an OPEN rule (the scatter planner keeps them apart): a cell another
 * placement wrote counts as built on.
 *
 * <p>A source under {@link MultiPaste.Replace#EXPECTED} (grown trees and features) is written with its air cells, its
 * placements are never transformed, and its cells are open exactly while the world cell still holds the state its
 * {@link MultiPaste#expected} cell gives; the decision, the re-check and the per-cell guards above apply to it as to
 * the OPEN rules.
 */
final class MultiPasteProgram implements EditProgram {
    /** A part is visited as its box when at least 1/8 of the box is cells, else as its cell list. */
    static final int DENSE_RATIO = 8;
    private static final int FLUID = StateFlags.FLUID_BLOCK | StateFlags.WATERLOGGED;
    // Placement states under an OPEN rule.
    private static final byte UNDECIDED = 0, WRITE = 1, SKIPPED = 2, CUT_SHORT = 3, WRITING = 4;

    private final String label;
    private final StateSpace states;
    /**
     * The rule for a cell no placement is known to write: the paste's own, or {@code null} (never written) when its
     * sources have rules of their own.
     */
    private final MultiPaste.Replace fallback;
    /** Per source, the rule its placements follow. */
    private final MultiPaste.Replace[] rules;
    /** Per source: its air cells are written too ({@link MultiPaste.Replace#EXPECTED}). */
    private final boolean[] air;
    /** Per source under {@link MultiPaste.Replace#EXPECTED}: what its cells must still hold (local cells); else null. */
    private final BlockBuffer[] expected;
    /**
     * Per placement, under an OPEN rule: {@link #UNDECIDED}; {@link #WRITE} (decided, nothing written yet);
     * {@link #WRITING} (its first cell passed the write-time check); {@link #SKIPPED}; {@link #CUT_SHORT}.
     */
    private final byte[] status;
    /** Placements the per-cell guard cut short in the section being computed. */
    private int cutShort;
    /**
     * Under an OPEN rule: the placement that wrote each cell of the section computed last ({@link #ownerKey}), so
     * {@link #mayReplace} can cut the right one short.
     */
    private int[] owner;
    private long ownerKey;
    private boolean ownerValid;
    private final SourceInfo[] info;
    private final List<SourceBlocks> sources;
    /** Per placement: its source, its whole target box's minimum corner, that box cut to the build height. */
    private final int[] sourceOf;
    private final int[] minX, minY, minZ;
    private final Box[] clipped;
    private final InverseMap[] inverse;
    private final Affine[] forward;
    private final StateMapper[] mapper;
    /** Target section key to its (placement << 32 | part) pairs, ascending by placement. */
    private final Long2ObjectOpenHashMap<LongArrayList> bySection;
    private final long[] order;
    private final Box bounds;
    private final long estimatedCells;
    private final long visitBound;

    private MultiPasteProgram(String label, StateSpace states, MultiPaste paste, SourceInfo[] info,
                              List<SourceBlocks> sources, int[] sourceOf, int[] minX, int[] minY, int[] minZ,
                              Box[] clipped, InverseMap[] inverse, Affine[] forward, StateMapper[] mapper,
                              Long2ObjectOpenHashMap<LongArrayList> bySection,
                              long[] order, Box bounds, long estimatedCells, long visitBound) {
        this.label = label;
        this.states = states;
        this.fallback = paste.sourceRules().isEmpty() ? paste.replace() : null;
        this.rules = new MultiPaste.Replace[sources.size()];
        this.air = new boolean[sources.size()];
        this.expected = new BlockBuffer[sources.size()];
        for (int s = 0; s < rules.length; s++) {
            rules[s] = paste.rule(s);
            air[s] = writesAir(paste, s);
            SourceBlocks want = paste.expected().get(s);
            expected[s] = want == null ? null : want.cells();
        }
        this.status = paste.guarded() ? new byte[sourceOf.length] : null;
        this.info = info;
        this.sources = sources;
        this.sourceOf = sourceOf;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.clipped = clipped;
        this.inverse = inverse;
        this.forward = forward;
        this.mapper = mapper;
        this.bySection = bySection;
        this.order = order;
        this.bounds = bounds;
        this.estimatedCells = estimatedCells;
        this.visitBound = visitBound;
    }

    /**
     * @param budget the most cells the placements may hold (present, non-air source cells, before the build-height
     *     cut); checked before any per-placement work
     * @throws EditTooLargeException over the budget
     * @throws IllegalArgumentException if a source holds a state outside {@code states}, a placement leaves the
     *     coordinate range, or no placement has a cell inside the build height
     */
    static MultiPasteProgram compile(String label, MultiPaste paste, StateSpace states, RegionProgram.Height height,
                                     long budget) {
        List<SourceBlocks> sources = paste.sources();
        List<MultiPaste.Placement> placements = paste.placements();
        SourceInfo[] info = new SourceInfo[sources.size()];
        long cells = 0;
        for (MultiPaste.Placement placement : placements) {
            int s = placement.source();
            if (info[s] == null) info[s] = SourceInfo.of(sources.get(s), states, writesAir(paste, s));
            cells += info[s].cells;
            if (cells > budget) throw new EditTooLargeException(cells, budget);
        }

        int n = placements.size();
        int[] sourceOf = new int[n], minX = new int[n], minY = new int[n], minZ = new int[n];
        Box[] clipped = new Box[n];
        InverseMap[] inverse = new InverseMap[n];
        Affine[] forward = new Affine[n];
        StateMapper[] mapper = new StateMapper[n];
        Map<Transform, StateMapper> mappers = new HashMap<>();
        Map<Long, InverseMap> inverses = new HashMap<>();
        Map<Long, Affine> forwards = new HashMap<>();
        Long2ObjectOpenHashMap<LongArrayList> sections = new Long2ObjectOpenHashMap<>();
        Box bounds = null;
        long estimated = 0, visits = 0;
        for (int p = 0; p < n; p++) {
            MultiPaste.Placement placement = placements.get(p);
            int s = placement.source();
            SourceBlocks source = sources.get(s);
            Transform t = placement.transform();
            Box target = CopySupport.pasteTarget(source.size(), source.anchor(), t, placement.origin());
            sourceOf[p] = s;
            minX[p] = target.min().x();
            minY[p] = target.min().y();
            minZ[p] = target.min().z();
            clipped[p] = height.clip(target);
            if (clipped[p] == null) continue;
            estimated += info[s].cellsBetween(clipped[p].min().y() - minY[p], clipped[p].max().y() - minY[p]);
            mapper[p] = mappers.computeIfAbsent(t, transform -> new StateMapper(states, transform));
            long mapKey = (long) s * 16 + t.mirror().ordinal() * 4 + t.quarterTurnsCw();
            inverse[p] = inverses.computeIfAbsent(mapKey, key -> InverseMap.of(t, source.size().x(), source.size().z()));
            forward[p] = forwards.computeIfAbsent(mapKey, key -> Affine.of(t, source.size().x(), source.size().z()));
            List<Part> parts = info[s].parts;
            for (int k = 0; k < parts.size(); k++) {
                Part part = parts.get(k);
                Box written = height.clip(CopySupport.transformedPart(target, source.size(), t,
                        part.x0, part.y0, part.z0, part.x1, part.y1, part.z1));
                if (written == null) continue;
                bounds = bounds == null ? written : CopySupport.union(bounds, written);
                long pair = ((long) p << 32) | k;
                long[] touched = {0};
                written.forEachSectionKey(key -> {
                    LongArrayList list = sections.get(key);
                    if (list == null) {
                        list = new LongArrayList(2);
                        sections.put(key, list);
                    }
                    list.add(pair);
                    touched[0]++;
                });
                visits += part.list == null ? written.volume() : (long) part.cells * touched[0];
            }
        }
        if (bounds == null) throw new IllegalArgumentException("Nothing to place inside the build height");
        long[] order = sections.keySet().toLongArray();
        SectionOrder.sort(order);
        return new MultiPasteProgram(label, states, paste, info, List.copyOf(sources), sourceOf, minX, minY,
                minZ, clipped, inverse, forward, mapper, sections, order, bounds, estimated, visits);
    }

    @Override
    public String label() {
        return label;
    }

    /** The union of the written parts of every placement (source sections' occupied bounds, transformed). */
    @Override
    public Box bounds() {
        return bounds;
    }

    /** The placements' present non-air cells inside the build height (overlaps counted once per placement). */
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

    /**
     * The most cells {@code compute} visits over all sections: at most {@value #DENSE_RATIO} times the placements'
     * cells.
     */
    long visitBound() {
        return visitBound;
    }

    @Override
    public void compute(long key, SectionBuffer before, SectionBuffer out, ComputeContext ctx) {
        ownerValid = false;
        LongArrayList pairs = bySection.get(key);
        if (pairs == null) return;
        int ox = BlockBuffer.keyX(key) << 4, oy = BlockBuffer.keyY(key) << 4, oz = BlockBuffer.keyZ(key) << 4;
        int count = pairs.size();
        // Cells claimed by a later placement; only needed when several placements share the section.
        boolean shared = (pairs.getLong(0) >>> 32) != (pairs.getLong(count - 1) >>> 32);
        long[] claimed = shared ? new long[SectionBuffer.SIZE / 64] : null;
        int skipped = 0;
        cutShort = 0;
        if (status != null) {
            if (owner == null) owner = new int[SectionBuffer.SIZE];
            ownerKey = key;
            ownerValid = true;
        }
        for (int k = count - 1; k >= 0; k--) {
            long pair = pairs.getLong(k);
            int p = (int) (pair >>> 32);
            if (status != null) {
                if (status[p] == UNDECIDED) {
                    status[p] = placementOpen(p, ctx, false) ? WRITE : SKIPPED;
                    if (status[p] == SKIPPED) skipped++;
                }
                if (status[p] == SKIPPED || status[p] == CUT_SHORT) continue;
            }
            Part part = info[sourceOf[p]].parts.get((int) pair);
            // The part's cells within this section and the build height.
            Box clip = clipped[p];
            int x0 = Math.max(clip.min().x(), ox), x1 = Math.min(clip.max().x(), ox + 15);
            int y0 = Math.max(clip.min().y(), oy), y1 = Math.min(clip.max().y(), oy + 15);
            int z0 = Math.max(clip.min().z(), oz), z1 = Math.min(clip.max().z(), oz + 15);
            if (part.list == null) {
                dense(p, part, x0, y0, z0, x1, y1, z1, ox, oy, oz, before, out, claimed);
            } else {
                sparse(p, part, x0, y0, z0, x1, y1, z1, ox, oy, oz, before, out, claimed);
            }
        }
        if (skipped + cutShort > 0) ctx.conflicts(skipped + cutShort);
    }

    /**
     * Whether every cell placement {@code p} writes (inside the build height) is loaded, open and writable
     * ({@link ComputeContext#mayWrite}) in the context's world; true without a world (the per-cell guards alone apply
     * then). Visits the placement's cells once. On the {@code recheck} before its first write, a cell whose chunk is
     * no longer loaded passes: its read ticket may have been let go since the decision, and its own section's guards
     * still apply when that section is written (its chunk loaded again).
     */
    private boolean placementOpen(int p, ComputeContext ctx, boolean recheck) {
        WorldReader world = ctx.world();
        if (world == null) return true;
        Affine f = forward[p];
        Box clip = clipped[p];
        int y0 = clip.min().y(), y1 = clip.max().y();
        long[] loadedChunk = {Long.MIN_VALUE};
        for (Part part : info[sourceOf[p]].parts) {
            SectionBuffer section = part.section;
            if (part.list != null) {
                for (short index : part.list) {
                    int si = index & 0xFFF;
                    int lx = part.sectionX + SectionBuffer.localX(si), lz = part.sectionZ + SectionBuffer.localZ(si);
                    int y = minY[p] + part.sectionY + SectionBuffer.localY(si);
                    if (y < y0 || y > y1) continue;
                    int x = minX[p] + f.x(lx, lz), z = minZ[p] + f.z(lx, lz);
                    if (!cellOpen(p, ctx, world, x, y, z, loadedChunk, recheck)) {
                        return false;
                    }
                }
                continue;
            }
            boolean withAir = air[sourceOf[p]];
            for (int ly = part.y0; ly <= part.y1; ly++) {
                int y = minY[p] + ly;
                if (y < y0 || y > y1) continue;
                for (int lz = part.z0; lz <= part.z1; lz++) {
                    for (int lx = part.x0; lx <= part.x1; lx++) {
                        int state = section.get(SectionBuffer.index(lx & 15, ly & 15, lz & 15));
                        if (skipped(state, withAir)) continue;
                        int x = minX[p] + f.x(lx, lz), z = minZ[p] + f.z(lx, lz);
                        if (!cellOpen(p, ctx, world, x, y, z, loadedChunk, recheck)) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * Whether world cell (x, y, z) is writable for the job, in a loaded chunk, and may be written by placement
     * {@code p} under the rule.
     */
    private boolean cellOpen(int p, ComputeContext ctx, WorldReader world, int x, int y, int z, long[] loadedChunk,
                             boolean recheck) {
        if (!ctx.mayWrite(x, z)) return false;
        long chunk = EditProgram.column(x >> 4, z >> 4);
        if (chunk != loadedChunk[0]) {
            if (!world.isLoaded(x >> 4, z >> 4)) return recheck;
            loadedChunk[0] = chunk;
        }
        return writableBy(p, world.get(x, y, z), x, y, z);
    }

    /**
     * Under an OPEN rule: the chunk columns, other than the section's own, of the placements that computing
     * {@code key} would decide (those not decided yet), so they are loaded when the decision reads them.
     */
    @Override
    public long[] readColumns(long key) {
        if (status == null) return NO_COLUMNS;
        LongArrayList pairs = bySection.get(key);
        if (pairs == null) return NO_COLUMNS;
        long own = EditProgram.column(BlockBuffer.keyX(key), BlockBuffer.keyZ(key));
        LongOpenHashSet columns = new LongOpenHashSet();
        int last = -1;
        for (int k = 0; k < pairs.size(); k++) {
            int p = (int) (pairs.getLong(k) >>> 32);
            if (p == last || status[p] != UNDECIDED) continue;
            last = p;
            Affine f = forward[p];
            Box clip = clipped[p];
            for (Part part : info[sourceOf[p]].parts) {
                if (minY[p] + part.y1 < clip.min().y() || minY[p] + part.y0 > clip.max().y()) continue;
                int ax = minX[p] + f.x(part.x0, part.z0), bx = minX[p] + f.x(part.x1, part.z1);
                int az = minZ[p] + f.z(part.x0, part.z0), bz = minZ[p] + f.z(part.x1, part.z1);
                for (int cx = Math.min(ax, bx) >> 4; cx <= Math.max(ax, bx) >> 4; cx++) {
                    for (int cz = Math.min(az, bz) >> 4; cz <= Math.max(az, bz) >> 4; cz++) {
                        long column = EditProgram.column(cx, cz);
                        if (column != own) columns.add(column);
                    }
                }
            }
        }
        if (columns.isEmpty()) return NO_COLUMNS;
        long[] sorted = columns.toLongArray();
        Arrays.sort(sorted);
        return sorted;
    }

    /**
     * Under an OPEN rule, the write-time guard: a cell that is no longer open (a chest or a wall built there, water
     * flowed in, while the section was being written) is not written, and the placement that would have written it is
     * cut short and reported once; none of a cut-short placement's cells is written after that. Before a placement's
     * first cell is written, all of its cells are checked again against the live world ({@link ComputeContext#world};
     * cells in chunks no longer loaded are left to their own section's guards), so a placement one of whose cells was
     * built on since the decision writes nothing.
     */
    @Override
    public boolean mayReplace(long key, int index, int liveState, ComputeContext ctx) {
        if (status == null) return true;
        int p = ownerValid && key == ownerKey ? owner[index] : -1;
        if (p >= 0 && status[p] == CUT_SHORT) return false;
        int x = (BlockBuffer.keyX(key) << 4) + SectionBuffer.localX(index);
        int y = (BlockBuffer.keyY(key) << 4) + SectionBuffer.localY(index);
        int z = (BlockBuffer.keyZ(key) << 4) + SectionBuffer.localZ(index);
        boolean ok = writableBy(p, liveState, x, y, z);
        if (ok && p >= 0 && status[p] == WRITE) {
            ok = placementOpen(p, ctx, true);
            if (ok) status[p] = WRITING;
        }
        if (!ok && p >= 0 && (status[p] == WRITE || status[p] == WRITING)) {
            status[p] = CUT_SHORT;
            ctx.conflicts(1);
        }
        return ok;
    }

    /**
     * Whether a cell holding {@code state} may be written under {@code rule}: open, and then for {@link
     * MultiPaste.Replace#OPEN} holding no fluid (not a fluid block, not waterlogged, and no other fluid: seagrass, kelp,
     * bubble columns), for {@link MultiPaste.Replace#WATER} plain still water, for {@link
     * MultiPaste.Replace#WATER_OR_WET_PLANT} still water in any open cell.
     */
    private boolean writable(MultiPaste.Replace rule, int state) {
        int flags = states.flags(state);
        if (!SurfaceScan.open(flags)) return false;
        return switch (rule) {
            case ANY, OPEN_OR_FLUID -> true;
            case OPEN -> (flags & FLUID) == 0 && states.fluidSource(state) < 0;
            case WATER -> Water.isSourceBlock(states, state);
            case WATER_OR_WET_PLANT -> Water.holdsSource(states, state);
            // Decided per cell against the expected state ({@link #writableBy}).
            case EXPECTED -> false;
        };
    }

    /** Whether source {@code s} of {@code paste} writes its air cells too. */
    private static boolean writesAir(MultiPaste paste, int s) {
        return paste.rule(s) == MultiPaste.Replace.EXPECTED;
    }

    /** Whether a source cell holding {@code state} is left out: absent, or air when the source writes no air. */
    private boolean skipped(int state, boolean withAir) {
        return state < 0 || (!withAir && StateFlags.has(states.flags(state), StateFlags.AIR));
    }

    /**
     * The state placement {@code p}'s {@link MultiPaste.Replace#EXPECTED} source expects at world cell (x, y, z), or -1
     * when the source has no cell there (its placements are never transformed).
     */
    private int expectedAt(int p, int x, int y, int z) {
        BlockBuffer want = expected[sourceOf[p]];
        if (want == null) return -1;
        BlockPos size = sources.get(sourceOf[p]).size();
        int sx = x - minX[p], sy = y - minY[p], sz = z - minZ[p];
        if (sx < 0 || sy < 0 || sz < 0 || sx >= size.x() || sy >= size.y() || sz >= size.z()) return -1;
        return want.get(sx, sy, sz);
    }

    /**
     * {@link #writable} under placement {@code p}'s rule (for {@code p < 0}, the paste's own, and never when its
     * sources have rules of their own), and for half of a double-tall block only if placement {@code p} also writes
     * its other half's cell (right above a lower half, right below an upper one); never when the placement is not
     * known ({@code p < 0}).
     */
    private boolean writableBy(int p, int state, int x, int y, int z) {
        MultiPaste.Replace rule = p >= 0 ? rules[sourceOf[p]] : fallback;
        if (rule == MultiPaste.Replace.EXPECTED) return p >= 0 && state >= 0 && expectedAt(p, x, y, z) == state;
        if (rule == null || !writable(rule, state)) return false;
        int flags = states.flags(state);
        if (!StateFlags.has(flags, StateFlags.DOUBLE_TALL)) return true;
        return p >= 0 && covers(p, x, StateFlags.has(flags, StateFlags.LOWER_HALF) ? y + 1 : y - 1, z);
    }

    /** Whether placement {@code p} writes world cell (x, y, z): a present, non-air source cell in the build height. */
    private boolean covers(int p, int x, int y, int z) {
        Box clip = clipped[p];
        if (clip == null || x < clip.min().x() || x > clip.max().x() || y < clip.min().y() || y > clip.max().y()
                || z < clip.min().z() || z > clip.max().z()) {
            return false;
        }
        SourceBlocks source = sources.get(sourceOf[p]);
        int tx = x - minX[p], tz = z - minZ[p], sy = y - minY[p];
        int sx = inverse[p].x(tx, tz), sz = inverse[p].z(tx, tz);
        BlockPos size = source.size();
        if (sx < 0 || sy < 0 || sz < 0 || sx >= size.x() || sy >= size.y() || sz >= size.z()) return false;
        return !skipped(source.cells().get(sx, sy, sz), air[sourceOf[p]]);
    }

    /** Visits the part's transformed box within [x0, x1] × [y0, y1] × [z0, z1], mapping each cell back. */
    private void dense(int p, Part part, int x0, int y0, int z0, int x1, int y1, int z1, int ox, int oy, int oz,
                       SectionBuffer before, SectionBuffer out, long[] claimed) {
        Affine f = forward[p];
        int ax = minX[p] + f.x(part.x0, part.z0), bx = minX[p] + f.x(part.x1, part.z1);
        int az = minZ[p] + f.z(part.x0, part.z0), bz = minZ[p] + f.z(part.x1, part.z1);
        x0 = Math.max(x0, Math.min(ax, bx));
        x1 = Math.min(x1, Math.max(ax, bx));
        y0 = Math.max(y0, minY[p] + part.y0);
        y1 = Math.min(y1, minY[p] + part.y1);
        z0 = Math.max(z0, Math.min(az, bz));
        z1 = Math.min(z1, Math.max(az, bz));
        if (x0 > x1 || y0 > y1 || z0 > z1) return;
        InverseMap map = inverse[p];
        StateMapper stateMap = mapper[p];
        SectionBuffer section = part.section;
        boolean withAir = air[sourceOf[p]];
        int tx0 = x0 - minX[p];
        for (int y = y0; y <= y1; y++) {
            int sy = (y - minY[p]) & 15;
            for (int z = z0; z <= z1; z++) {
                int tz = z - minZ[p];
                int sx = map.x(tx0, tz), sz = map.z(tx0, tz);
                int base = SectionBuffer.index(0, y - oy, z - oz);
                for (int x = x0; x <= x1; x++, sx += map.xPerTx(), sz += map.zPerTx()) {
                    int si = SectionBuffer.index(sx & 15, sy, sz & 15);
                    int state = section.get(si);
                    if (skipped(state, withAir)) continue;
                    write(p, before, out, claimed, base | (x - ox), stateMap.map(state), section, si, x, y, z);
                }
            }
        }
    }

    /** Visits the part's cell list, mapping each cell forward and keeping those in [x0, x1] × [y0, y1] × [z0, z1]. */
    private void sparse(int p, Part part, int x0, int y0, int z0, int x1, int y1, int z1, int ox, int oy, int oz,
                        SectionBuffer before, SectionBuffer out, long[] claimed) {
        Affine f = forward[p];
        StateMapper stateMap = mapper[p];
        SectionBuffer section = part.section;
        for (short index : part.list) {
            int si = index & 0xFFF;
            int lx = part.sectionX + SectionBuffer.localX(si), lz = part.sectionZ + SectionBuffer.localZ(si);
            int x = minX[p] + f.x(lx, lz), y = minY[p] + part.sectionY + SectionBuffer.localY(si), z = minZ[p] + f.z(lx, lz);
            if (x < x0 || x > x1 || y < y0 || y > y1 || z < z0 || z > z1) continue;
            write(p, before, out, claimed, SectionBuffer.index(x - ox, y - oy, z - oz), stateMap.map(section.get(si)),
                    section, si, x, y, z);
        }
    }

    /** Writes source cell {@code si} of placement {@code p} into section index {@code i}, world cell (x, y, z). */
    private void write(int p, SectionBuffer before, SectionBuffer out, long[] claimed, int i, int state,
                       SectionBuffer source, int si, int x, int y, int z) {
        if (status != null) {
            if (status[p] == CUT_SHORT) return;
            if (!writableBy(p, before.get(i), x, y, z)) {
                // Under an OPEN rule a cell that is no longer open is never written; its placement is cut short, and
                // what it already put in this section is taken back.
                status[p] = CUT_SHORT;
                cutShort++;
                for (int k = 0; k < SectionBuffer.SIZE; k++) {
                    if (owner[k] == p && out.has(k)) out.clear(k);
                }
                return;
            }
        }
        if (claimed != null) {
            long bit = 1L << i;
            if ((claimed[i >>> 6] & bit) != 0) return;
            claimed[i >>> 6] |= bit;
        }
        if (status != null) owner[i] = p;
        CopySupport.copyCell(before, out, i, state, source.tile(si));
    }

    /** A transform's forward map of source-local (x, z) into the transformed box: affine, sampled at three points. */
    private record Affine(int originX, int originZ, int xPerX, int xPerZ, int zPerX, int zPerZ) {
        static Affine of(Transform t, int sizeX, int sizeZ) {
            int ox = t.mapX(0, 0, sizeX, sizeZ), oz = t.mapZ(0, 0, sizeX, sizeZ);
            return new Affine(ox, oz, t.mapX(1, 0, sizeX, sizeZ) - ox, t.mapX(0, 1, sizeX, sizeZ) - ox,
                    t.mapZ(1, 0, sizeX, sizeZ) - oz, t.mapZ(0, 1, sizeX, sizeZ) - oz);
        }

        int x(int x, int z) {
            return originX + xPerX * x + xPerZ * z;
        }

        int z(int x, int z) {
            return originZ + zPerX * x + zPerZ * z;
        }
    }

    /**
     * One non-empty source section's present non-air cells inside the source box: their occupied local bounds, and
     * for a sparse part their section-local indices.
     */
    private static final class Part {
        final SectionBuffer section;
        /** The section's origin in source-local coordinates. */
        final int sectionX, sectionY, sectionZ;
        final int x0, y0, z0, x1, y1, z1;
        final int cells;
        /** Section-local indices of the cells, or {@code null} for a dense part. */
        final short[] list;

        Part(SectionBuffer section, int sectionX, int sectionY, int sectionZ, int[] box, short[] cellList) {
            this.section = section;
            this.sectionX = sectionX;
            this.sectionY = sectionY;
            this.sectionZ = sectionZ;
            this.x0 = box[0];
            this.y0 = box[1];
            this.z0 = box[2];
            this.x1 = box[3];
            this.y1 = box[4];
            this.z1 = box[5];
            this.cells = cellList.length;
            long volume = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
            this.list = (long) cells * DENSE_RATIO >= volume ? null : cellList;
        }
    }

    /** What compiling needs to know about one source: its parts and its cell count per layer. */
    private static final class SourceInfo {
        /** Present non-air cells inside the source box. */
        final long cells;
        final List<Part> parts;
        /** Per part: its section's lowest local y, and its cell count per layer of that section. */
        private final List<int[]> layers;

        private SourceInfo(long cells, List<Part> parts, List<int[]> layers) {
            this.cells = cells;
            this.parts = parts;
            this.layers = layers;
        }

        /** @param withAir whether the source's air cells are written too (and so counted and visited) */
        static SourceInfo of(SourceBlocks source, StateSpace states, boolean withAir) {
            BlockPos size = source.size();
            List<Part> parts = new ArrayList<>();
            List<int[]> layers = new ArrayList<>();
            long cells = 0;
            for (long key : source.cells().sortedKeys()) {
                SectionBuffer section = source.cells().section(key);
                if (section.isEmpty()) continue;
                int ox = BlockBuffer.keyX(key) << 4, oy = BlockBuffer.keyY(key) << 4, oz = BlockBuffer.keyZ(key) << 4;
                int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                        Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
                int[] layer = new int[17];
                layer[16] = oy;
                ShortArrayList list = new ShortArrayList();
                section.forEachPresent(i -> {
                    int x = ox + SectionBuffer.localX(i), y = oy + SectionBuffer.localY(i), z = oz + SectionBuffer.localZ(i);
                    if (x < 0 || y < 0 || z < 0 || x >= size.x() || y >= size.y() || z >= size.z()) return;
                    int state = section.get(i);
                    if (state >= states.size()) {
                        throw new IllegalArgumentException("Scatter source state " + state + " is outside the state space");
                    }
                    if (!withAir && StateFlags.has(states.flags(state), StateFlags.AIR)) return;
                    box[0] = Math.min(box[0], x);
                    box[1] = Math.min(box[1], y);
                    box[2] = Math.min(box[2], z);
                    box[3] = Math.max(box[3], x);
                    box[4] = Math.max(box[4], y);
                    box[5] = Math.max(box[5], z);
                    layer[y - oy]++;
                    list.add((short) i);
                });
                if (list.isEmpty()) continue;
                cells += list.size();
                parts.add(new Part(section, ox, oy, oz, box, list.toShortArray()));
                layers.add(layer);
            }
            return new SourceInfo(cells, List.copyOf(parts), List.copyOf(layers));
        }

        /** Cells whose local y is in {@code [y0, y1]}. */
        long cellsBetween(int y0, int y1) {
            long count = 0;
            for (int[] layer : layers) {
                int oy = layer[16];
                for (int ly = Math.max(0, y0 - oy); ly <= Math.min(15, y1 - oy); ly++) count += layer[ly];
            }
            return count;
        }
    }
}
