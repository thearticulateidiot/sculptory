package dev.sculptory.core.scatter;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.MultiPaste;
import dev.sculptory.core.edit.SourceBlocks;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.transform.Transform;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The result of {@link ScatterPlanner}: where each placement goes, which variant and transform it uses, and why
 * the other area columns were rejected. Immutable. The same settings, sources and world give an equal plan (and
 * {@link #hash()}), however the survey was partitioned.
 *
 * <p>A plan keeps its sources, so committing it ({@link #toMultiPaste()}) writes exactly what was planned even if
 * a library file changes afterwards. A plan reflects the world as the planner read it; a commit writes only into
 * cells that are still open (still water, for an underwater variant) and skips a placement whole when any of its
 * cells no longer is ({@link MultiPaste.Replace}), so it never overwrites what was built since the preview.
 */
public final class ScatterPlan {
    // The hash domain keeps the pre-rename name on purpose, like the clipboard hash: plan hashes stay comparable.
    private static final byte[] HASH_MAGIC = "BuilderSuite scatter plan v2\0".getBytes(StandardCharsets.US_ASCII);

    /**
     * One accepted placement: the variant's anchor cell lands on {@code anchor} (the cell above the surface).
     *
     * @param height a column plant's column height, 1-{@value ScatterSettings#MAX_COLUMN_HEIGHT}; 1 for every other
     *     variant
     */
    public record Placement(BlockPos anchor, int variant, Transform transform, int height) {
        public Placement {
            Objects.requireNonNull(anchor);
            Objects.requireNonNull(transform);
            if (transform.upsideDown()) throw new IllegalArgumentException("Scatter placements are never upside down");
            if (height < 1 || height > ScatterSettings.MAX_COLUMN_HEIGHT) {
                throw new IllegalArgumentException("Column height must be 1-" + ScatterSettings.MAX_COLUMN_HEIGHT);
            }
        }

        /** A placement one block tall (anything but a taller column plant). */
        public Placement(BlockPos anchor, int variant, Transform transform) {
            this(anchor, variant, transform, 1);
        }
    }

    private final ScatterSettings settings;
    private final List<Clipboard> sources;
    private final List<BlockVariants.Medium> media;
    /** Per source, the commit rule its placements follow. */
    private final List<MultiPaste.Replace> rules;
    /** Column plants' taller columns, by {@link #columnKey}. */
    private final Map<Integer, Clipboard> columns;
    private final List<Placement> placements;
    private final long[] counts;
    private final long columnCount;
    private final long candidates;
    private final long totalCells;
    private final Box bounds;
    /** The grown clusters: overlapping growths merged, in the order of their first growth. */
    private final List<GrownFeature> clusters;
    /** Per placement, its cluster's index in {@link #clusters}, or -1 for a held or block placement. */
    private final int[] clusterOf;
    /** Per placement, whether it is a tree or feature (grown, and so never pasted from its placeholder source). */
    private final boolean[] grownPlacement;
    private final long grownCells;
    /** Computed on first use ({@link #hash()}): a server never needs it while it plans. */
    private volatile Sha256 hash;

    /**
     * The planner's growths: in acceptance order, each with its placement index and its cluster (the index of the
     * cluster's first growth; growths whose cells overlap share one).
     */
    record Grown(List<GrownFeature> growths, int[] placementOf, int[] clusterOf) {
        static final Grown NONE = new Grown(List.of(), new int[0], new int[0]);
    }

    ScatterPlan(ScatterSettings settings, List<Clipboard> sources, List<BlockVariants.Medium> media,
                List<MultiPaste.Replace> rules, Map<Integer, Clipboard> columns, List<Placement> placements,
                long[] counts, long columnCount, long candidates, long footprintCells, Box bounds, Grown grown) {
        this.settings = settings;
        this.sources = List.copyOf(sources);
        this.media = List.copyOf(media);
        this.rules = List.copyOf(rules);
        if (this.media.size() != this.sources.size() || this.rules.size() != this.sources.size()) {
            throw new IllegalArgumentException("One medium and rule per source");
        }
        this.columns = Map.copyOf(columns);
        this.placements = Collections.unmodifiableList(new ArrayList<>(placements));
        this.counts = counts.clone();
        this.columnCount = columnCount;
        this.candidates = candidates;
        this.bounds = bounds;
        // Growths that overlap were grown one around the other: merged in acceptance order, each cell keeps the state
        // the world held (the first growth's before) and takes the last growth's state and block entity.
        this.clusterOf = new int[this.placements.size()];
        Arrays.fill(clusterOf, -1);
        this.grownPlacement = new boolean[this.placements.size()];
        for (int placement : grown.placementOf()) grownPlacement[placement] = true;
        List<GrownFeature> merged = new ArrayList<>();
        // Members per cluster root, in acceptance order (roots are first growths, so this is first-growth order).
        Map<Integer, List<GrownFeature>> byRoot = new LinkedHashMap<>();
        for (int g = 0; g < grown.growths().size(); g++) {
            byRoot.computeIfAbsent(grown.clusterOf()[g], root -> new ArrayList<>()).add(grown.growths().get(g));
        }
        Map<Integer, Integer> clusterIndex = new HashMap<>();
        long grownTotal = 0;
        for (Map.Entry<Integer, List<GrownFeature>> entry : byRoot.entrySet()) {
            List<GrownFeature> members = entry.getValue();
            GrownFeature cluster;
            if (members.size() == 1) {
                cluster = members.get(0); // most trees stand alone: nothing to merge
            } else {
                GrownFeature.Builder builder = GrownFeature.builder();
                for (GrownFeature growth : members) {
                    for (int i = 0; i < growth.size(); i++) {
                        builder.set(growth.x(i), growth.y(i), growth.z(i), growth.after(i), growth.tile(i),
                                growth.before(i));
                    }
                }
                cluster = builder.build();
            }
            if (cluster == null) continue; // every cell ended as it was (never in practice)
            clusterIndex.put(entry.getKey(), merged.size());
            merged.add(cluster);
            grownTotal += cluster.size();
        }
        for (int g = 0; g < grown.growths().size(); g++) {
            Integer index = clusterIndex.get(grown.clusterOf()[g]);
            clusterOf[grown.placementOf()[g]] = index == null ? -1 : index;
        }
        this.clusters = List.copyOf(merged);
        this.grownCells = grownTotal;
        this.totalCells = footprintCells + grownTotal;
    }

    /** The key of source {@code source}'s column of {@code height} cells. */
    static int columnKey(int source, int height) {
        return source * (ScatterSettings.MAX_COLUMN_HEIGHT + 1) + height;
    }

    public ScatterSettings settings() {
        return settings;
    }

    /** The planner's sources; variants index into this list. */
    public List<Clipboard> sources() {
        return sources;
    }

    /** Where source {@code source}'s placements go (always {@link BlockVariants.Medium#LAND} for a held source). */
    public BlockVariants.Medium medium(int source) {
        return media.get(source);
    }

    /** What placement {@code p} writes: its variant's source, or for a taller column plant that column. */
    public Clipboard content(Placement p) {
        int source = settings.variants().get(p.variant()).source();
        return p.height() == 1 ? sources.get(source) : columns.get(columnKey(source, p.height()));
    }

    /** Accepted placements, in acceptance (rank) order. */
    public List<Placement> placements() {
        return placements;
    }

    /** How many area columns ended with {@code outcome}. */
    public long count(Outcome outcome) {
        return counts[outcome.ordinal()];
    }

    /** The non-zero outcome counts. */
    public Map<Outcome, Long> rejectedCounts() {
        EnumMap<Outcome, Long> map = new EnumMap<>(Outcome.class);
        for (Outcome outcome : Outcome.values()) {
            if (counts[outcome.ordinal()] != 0) map.put(outcome, counts[outcome.ordinal()]);
        }
        return Collections.unmodifiableMap(map);
    }

    /** The area's columns: the sum of every outcome count and the placements. */
    public long columns() {
        return columnCount;
    }

    /** The columns that passed the density prefilter and the surface filters. */
    public long candidates() {
        return candidates;
    }

    /**
     * The cells the placements write at most: the sum of their footprints (present, non-air source cells), plus the
     * cells of the grown clusters (air included).
     */
    public long totalCells() {
        return totalCells;
    }

    /**
     * What the trees and features grew, as the commit writes it: one cluster per group of growths whose cells overlap
     * (most hold one tree), in the order of their first growth. A cell appears in one cluster only.
     */
    public List<GrownFeature> clusters() {
        return clusters;
    }

    /** The index in {@link #clusters()} of placement {@code index}'s growth, or -1 for a held or block placement. */
    public int clusterOf(int index) {
        return clusterOf[index];
    }

    /** The cells the clusters write, all together. */
    public long grownCells() {
        return grownCells;
    }

    /** The union of the placements' footprint bounds; empty without placements. */
    public Optional<Box> bounds() {
        return Optional.ofNullable(bounds);
    }

    /**
     * SHA-256 over the sources' content hashes and media, the variant table, the placements, the outcome counts and
     * the totals. Equal plans have equal hashes across runs and servers.
     * Computed once, on first use (any thread; plans are immutable).
     */
    public Sha256 hash() {
        Sha256 h = hash;
        if (h == null) {
            h = computeHash();
            hash = h;
        }
        return h;
    }

    /** Approximate heap footprint of the plan itself (its sources are shared with whoever holds them). */
    public long estimatedBytes() {
        long bytes = 256 + 96L * placements.size() + 4L * clusterOf.length;
        for (GrownFeature cluster : clusters) bytes += cluster.estimatedBytes();
        return bytes;
    }

    /**
     * The placements {@code guard} denies a cell of, judged whole as the planner judges them
     * ({@link ScatterPlanner.CellGuard}, {@link Outcome#MASKED}): every footprint cell of a held or block placement,
     * every cell of a tree's or feature's cluster (all of the cluster's placements are denied together). For a commit
     * under a mask that changed since the preview, or a world that did; {@link #toMultiPaste(BitSet)} leaves them out.
     */
    public BitSet denied(ScatterPlanner.CellGuard guard, StateSpace states) {
        Objects.requireNonNull(guard);
        Objects.requireNonNull(states);
        BitSet denied = new BitSet();
        BitSet deniedClusters = new BitSet();
        for (int c = 0; c < clusters.size(); c++) {
            GrownFeature cluster = clusters.get(c);
            for (int i = 0; i < cluster.size(); i++) {
                if (!guard.allows(cluster.x(i), cluster.y(i), cluster.z(i))) {
                    deniedClusters.set(c);
                    break;
                }
            }
        }
        Map<Clipboard, Footprint> footprints = new IdentityHashMap<>();
        for (int p = 0; p < placements.size(); p++) {
            if (grownPlacement[p]) {
                if (clusterOf[p] >= 0 && deniedClusters.get(clusterOf[p])) denied.set(p);
                continue;
            }
            Placement placement = placements.get(p);
            Footprint footprint = footprints.computeIfAbsent(content(placement), clipboard -> Footprint.of(clipboard,
                    states));
            Footprint.Oriented o = footprint.orient(placement.transform());
            BlockPos a = placement.anchor();
            for (int i = 0; i < footprint.cells; i++) {
                int rx = footprint.rx[i], rz = footprint.rz[i];
                if (!guard.allows(a.x() + o.dx(rx, rz), a.y() + footprint.ry[i], a.z() + o.dz(rx, rz))) {
                    denied.set(p);
                    break;
                }
            }
        }
        return denied;
    }

    /**
     * What {@code OpSpec.ScatterCommit} writes: one placement per plan placement, each pasting its variant's source
     * (a fresh copy of the source cells; a taller column plant's column) with the placement's transform onto its
     * anchor, into cells that pass its source's rule: open, dry cells ({@link MultiPaste.Replace#OPEN}; for a held
     * source {@link MultiPaste.Replace#OPEN_OR_FLUID} when the fit allows fluids), or for an underwater variant still
     * water ({@link MultiPaste.Replace#WATER}, or {@link MultiPaste.Replace#WATER_OR_WET_PLANT} when the fit allows
     * fluids). Each grown cluster is one more placement of its own source, air cells included, written only while every
     * cell still holds what it held when the trees grew ({@link MultiPaste.Replace#EXPECTED}); the placements of trees
     * and features themselves write nothing else.
     */
    public MultiPaste toMultiPaste() {
        return toMultiPaste(new BitSet());
    }

    /**
     * {@link #toMultiPaste()} without the placements in {@code skip} (indices into {@link #placements()}): a skipped
     * tree or feature leaves out its whole cluster, so no cluster is ever written in part.
     */
    public MultiPaste toMultiPaste(BitSet skip) {
        Objects.requireNonNull(skip);
        BitSet skippedClusters = new BitSet();
        for (int p = skip.nextSetBit(0); p >= 0; p = skip.nextSetBit(p + 1)) {
            if (p < clusterOf.length && clusterOf[p] >= 0) skippedClusters.set(clusterOf[p]);
        }
        List<SourceBlocks> blocks = new ArrayList<>(sources.size() + clusters.size());
        List<MultiPaste.Replace> rules = new ArrayList<>(this.rules);
        for (int s = 0; s < sources.size(); s++) blocks.add(sources.get(s).toSource());
        Map<Integer, Integer> columnSources = new HashMap<>();
        Map<Integer, SourceBlocks> expected = new HashMap<>();
        List<MultiPaste.Placement> pastes = new ArrayList<>(placements.size());
        for (int c = 0; c < clusters.size(); c++) {
            if (skippedClusters.get(c)) continue;
            GrownFeature cluster = clusters.get(c);
            Box box = cluster.bounds();
            BlockPos min = box.min();
            BlockBuffer after = new BlockBuffer(), before = new BlockBuffer();
            for (int i = 0; i < cluster.size(); i++) {
                int x = cluster.x(i) - min.x(), y = cluster.y(i) - min.y(), z = cluster.z(i) - min.z();
                after.set(x, y, z, cluster.after(i));
                BlockEntityData tile = cluster.tile(i);
                if (tile != null) after.setTile(x, y, z, tile);
                before.set(x, y, z, cluster.before(i));
            }
            BlockPos size = new BlockPos(box.sizeX(), box.sizeY(), box.sizeZ());
            int index = blocks.size();
            blocks.add(new SourceBlocks(after, size, BlockPos.ORIGIN));
            expected.put(index, new SourceBlocks(before, size, BlockPos.ORIGIN));
            rules.add(MultiPaste.Replace.EXPECTED);
            pastes.add(new MultiPaste.Placement(min, index, Transform.IDENTITY));
        }
        for (int p = 0; p < placements.size(); p++) {
            if (grownPlacement[p] || skip.get(p)) continue; // a tree is written by its cluster
            Placement placement = placements.get(p);
            int source = settings.variants().get(placement.variant()).source();
            int index = source;
            if (placement.height() > 1) {
                int key = columnKey(source, placement.height());
                Integer known = columnSources.get(key);
                if (known == null) {
                    known = blocks.size();
                    blocks.add(columns.get(key).toSource());
                    rules.add(rules.get(source));
                    columnSources.put(key, known);
                }
                index = known;
            }
            pastes.add(new MultiPaste.Placement(placement.anchor(), index, placement.transform()));
        }
        // One rule for every source: the paste's own. Mixed rules: per source (a cell whose placement is not known is
        // then never written).
        boolean mixed = false;
        for (MultiPaste.Replace rule : rules) mixed |= rule != rules.get(0);
        return new MultiPaste(blocks, pastes, rules.isEmpty() ? MultiPaste.Replace.OPEN : rules.get(0),
                mixed ? rules : List.of(), expected);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ScatterPlan other && hash().equals(other.hash()) && settings.equals(other.settings);
    }

    @Override
    public int hashCode() {
        return hash().hashCode();
    }

    @Override
    public String toString() {
        return "ScatterPlan[placements=" + placements.size() + ", cells=" + totalCells + ", columns=" + columnCount
                + ", rejected=" + rejectedCounts() + ", hash=" + hash().hex().substring(0, 12) + "]";
    }

    private Sha256 computeHash() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 + 21 * placements.size());
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.write(HASH_MAGIC);
            out.writeInt(sources.size());
            for (int s = 0; s < sources.size(); s++) {
                out.write(sources.get(s).contentHash().bytes());
                out.writeByte(media.get(s).ordinal());
                out.writeByte(rules.get(s).ordinal());
            }
            out.writeInt(settings.variants().size());
            for (ScatterSettings.Variant variant : settings.variants()) out.writeInt(variant.source());
            out.writeInt(placements.size());
            for (Placement placement : placements) {
                out.writeInt(placement.anchor().x());
                out.writeInt(placement.anchor().y());
                out.writeInt(placement.anchor().z());
                out.writeShort(placement.variant());
                out.writeByte(placement.transform().quarterTurnsCw());
                out.writeByte(placement.transform().mirror().ordinal());
                out.writeByte(placement.height());
            }
            out.writeInt(counts.length);
            for (long count : counts) out.writeLong(count);
            out.writeLong(columnCount);
            out.writeLong(candidates);
            out.writeLong(totalCells);
            // Grown clusters (a plan without trees or features hashes as before).
            if (!clusters.isEmpty()) {
                out.writeInt(clusters.size());
                for (GrownFeature cluster : clusters) {
                    // The cells as one block (a stream write per value would dominate the plan's making).
                    java.nio.ByteBuffer cells = java.nio.ByteBuffer.allocate(4 + 16 * cluster.size());
                    cells.putInt(cluster.size());
                    for (int i = 0; i < cluster.size(); i++) {
                        cells.putLong(cluster.cell(i)).putInt(cluster.after(i)).putInt(cluster.before(i));
                    }
                    out.write(cells.array());
                    out.writeInt(cluster.tileCount());
                    for (int i = 0; i < cluster.size(); i++) {
                        BlockEntityData tile = cluster.tile(i);
                        if (tile == null) continue;
                        out.writeInt(i);
                        out.writeUTF(tile.typeId());
                        byte[] nbt = tile.nbtBytes();
                        out.writeInt(nbt.length);
                        out.write(nbt);
                    }
                }
                for (int cluster : clusterOf) out.writeInt(cluster);
            }
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return Sha256.digest(bytes.toByteArray());
    }
}
