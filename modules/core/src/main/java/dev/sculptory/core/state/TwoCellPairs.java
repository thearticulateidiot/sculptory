package dev.sculptory.core.state;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.transform.CellResampler;
import dev.sculptory.core.transform.Orientation;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Objects;
import java.util.function.IntUnaryOperator;

/**
 * A resampler that keeps two-cell blocks ({@link TwoCellBlocks}: doors, beds, two-block plants) whole under a free
 * orientation. Each pair is placed from its first cell (the lower half, a bed's foot): it lands where the underlying
 * resampler puts that cell (the first of its {@link CellResampler#takers}; when none takes it, the cell holding its
 * turned centre), and the other half goes right beside it in the direction the turned state says (above a lower half,
 * along a bed's turned facing). No other result cell takes either half; a block whose cell a half took moves into a
 * cell the pair's halves left. With exact count a pair whose displaced blocks find no such cell is dropped, so the count
 * stays exact; with the other ways such a block is lost like any block those ways lose, and counted ({@link #lostBlocks}).
 * The two halves are one: {@link #partner} names the other half, and a program writes both or neither. A pair is <b>dropped</b>, both halves, and
 * counted ({@link #droppedPairs}) when its first cell turns out of the result box, its other half would leave it, or
 * either cell is already taken by an earlier pair (pairs are placed in source order: y, then z, then x). A half whose
 * other half is missing from the source is an ordinary cell.
 *
 * <p>Built once per source ({@link #of}), from the source cells that hold halves; then a lookup per result cell. The
 * cells another pair claims take that pair's half, every other cell takes what the underlying resampler gives, except
 * a half that belongs to a pair (it lands only where its pair put it). Immutable and thread-safe once built.
 */
public final class TwoCellPairs implements CellResampler {
    private final CellResampler base;
    /** Result cell → the source cell it takes, for the cells pairs claim and the cells blocks they displaced move to. */
    private final Long2LongOpenHashMap claimed;
    /** Source cells that belong to a pair (placed or dropped): only a claim lands them. */
    private final LongOpenHashSet paired;
    /** Source cell → the result cell its pair put it on. */
    private final Long2LongOpenHashMap landed;
    /** Displaced source cell → the result cells it moved to. */
    private final Long2ObjectOpenHashMap<LongArrayList> moved;
    /** Result cell → the result cell of the other half, for the cells placed pairs claim. */
    private final Long2LongOpenHashMap partners;
    private final int placed;
    private final int dropped;
    /** Blocks a placed half displaced that found no cell to move into. */
    private final long lost;

    private TwoCellPairs(CellResampler base, Long2LongOpenHashMap claimed, LongOpenHashSet paired,
                         Long2LongOpenHashMap landed, Long2ObjectOpenHashMap<LongArrayList> moved,
                         Long2LongOpenHashMap partners, int placed, int dropped, long lost) {
        this.base = base;
        this.claimed = claimed;
        this.paired = paired;
        this.landed = landed;
        this.moved = moved;
        this.partners = partners;
        this.placed = placed;
        this.dropped = dropped;
        this.lost = lost;
    }

    /**
     * Pairs up the halves among {@code candidates} (source-local cells packed with {@link #pack}, any order; cells that
     * hold no half are skipped) and places them. A block that a placed half displaces (the underlying resampler gave its
     * cell to another source cell) moves into a cell the same pair's halves vacated, when there is one, so exact count
     * stays exact.
     *
     * @param base the resampler that places every other cell
     * @param cells the source blocks
     * @param blocks which states are halves
     * @param orient a source state as the copy writes it (its turned state), for the direction of the other half
     */
    public static TwoCellPairs of(CellResampler base, SourceCells cells, TwoCellBlocks blocks, IntUnaryOperator orient,
                                  long[] candidates) {
        return of(base, cells, blocks, orient, candidates, (x, y, z) -> true);
    }

    /** Which result cells a copy may write (a move writes only the cells it worked out at admission). */
    @FunctionalInterface
    public interface Writable {
        boolean test(int x, int y, int z);
    }

    /**
     * {@link #of(CellResampler, SourceCells, TwoCellBlocks, IntUnaryOperator, long[])} for a copy that writes only the
     * result cells {@code writable} accepts: a pair needing another is dropped, and a displaced block moves only into one.
     */
    public static TwoCellPairs of(CellResampler base, SourceCells cells, TwoCellBlocks blocks, IntUnaryOperator orient,
                                  long[] candidates, Writable writable) {
        Objects.requireNonNull(writable);
        Objects.requireNonNull(base);
        Objects.requireNonNull(cells);
        Objects.requireNonNull(blocks);
        Objects.requireNonNull(orient);
        BlockPos size = base.sourceSize();
        BlockPos result = base.size();
        // Boxes too large to pack (far beyond any build) keep no pairs: every half is an ordinary cell.
        long[] firsts = packable(size) && packable(result) ? candidates.clone() : new long[0];
        java.util.Arrays.sort(firsts);
        // 1. The whole pairs, in source order.
        LongArrayList pairs = new LongArrayList();
        LongOpenHashSet paired = new LongOpenHashSet();
        long previous = Long.MIN_VALUE;
        for (long packed : firsts) {
            if (packed == previous) continue;
            previous = packed;
            int x = unpackX(packed), y = unpackY(packed), z = unpackZ(packed);
            if (x >= size.x() || y >= size.y() || z >= size.z()) continue;
            int state = cells.state(x, y, z);
            if (state < 0 || !blocks.isHalf(state) || !blocks.isFirst(state)) continue;
            int[] step = blocks.towardsOther(state);
            int ox = x + step[0], oy = y + step[1], oz = z + step[2];
            if (ox < 0 || oy < 0 || oz < 0 || ox >= size.x() || oy >= size.y() || oz >= size.z()) continue;
            int otherState = cells.state(ox, oy, oz);
            if (otherState < 0 || otherState != blocks.other(state)) continue;
            long other = pack(ox, oy, oz);
            pairs.add(packed);
            pairs.add(other);
            paired.add(packed);
            paired.add(other);
        }
        // 2. Each pair from its first cell, the other half beside it. A resampler whose takers are dear to find (smooth:
        // 125 cells of 27 points) places the first cell where its centre turns to, so a pair costs a few lookups.
        boolean dear = base.maxTakers() > 27;
        Long2LongOpenHashMap claimed = new Long2LongOpenHashMap();
        claimed.defaultReturnValue(-1);
        Long2LongOpenHashMap landed = new Long2LongOpenHashMap();
        landed.defaultReturnValue(-1);
        Long2LongOpenHashMap partners = new Long2LongOpenHashMap();
        partners.defaultReturnValue(-1);
        int[] takers = new int[3 * Math.max(1, base.maxTakers())];
        int[] cell = new int[3];
        double[] point = new double[3];
        LongArrayList placedPairs = new LongArrayList();
        int dropped = 0;
        for (int i = 0; i < pairs.size(); i += 2) {
            long packed = pairs.getLong(i), other = pairs.getLong(i + 1);
            int x = unpackX(packed), y = unpackY(packed), z = unpackZ(packed);
            int[] turned = blocks.towardsOther(orient.applyAsInt(cells.state(x, y, z)));
            if (turned == null) {
                dropped++;
                continue;
            }
            int lx, ly, lz;
            if (!dear && base.takers(x, y, z, cells, takers) > 0) {
                lx = takers[0];
                ly = takers[1];
                lz = takers[2];
            } else {
                // The cell its centre turns into (no result cell takes it, or finding one is dear).
                base.forward(x + 0.5, y + 0.5, z + 0.5, point);
                lx = (int) Math.floor(point[0]);
                ly = (int) Math.floor(point[1]);
                lz = (int) Math.floor(point[2]);
            }
            int px = lx + turned[0], py = ly + turned[1], pz = lz + turned[2];
            if (!inside(result, lx, ly, lz) || !inside(result, px, py, pz) || !writable.test(lx, ly, lz)
                    || !writable.test(px, py, pz)) {
                dropped++;
                continue;
            }
            long first = pack(lx, ly, lz), second = pack(px, py, pz);
            if (claimed.containsKey(first) || claimed.containsKey(second)) {
                dropped++;
                continue;
            }
            claimed.put(first, packed);
            claimed.put(second, other);
            landed.put(packed, first);
            landed.put(other, second);
            placedPairs.add(packed);
            placedPairs.add(other);
        }
        // 3. Blocks the halves displaced move into cells the same pair vacated, in a fixed order. With exact count a
        // pair whose displaced blocks find no such cell is dropped instead, so no block is lost; otherwise the blocks
        // left without a cell are counted ({@link #lostBlocks}). The dear resampler's vacated cells are not looked for.
        boolean bijection = base.maxTakers() == 1;
        Long2ObjectOpenHashMap<LongArrayList> moved = new Long2ObjectOpenHashMap<>();
        long lost = 0;
        int placed = 0;
        for (int i = 0; i < placedPairs.size(); i += 2) {
            long[] halves = {placedPairs.getLong(i), placedPairs.getLong(i + 1)};
            LongArrayList displaced = new LongArrayList(2);
            LongArrayList vacated = new LongArrayList(2);
            for (long half : halves) {
                long at = landed.get(half);
                if (base.source(unpackX(at), unpackY(at), unpackZ(at), cells, cell)) {
                    long from = pack(cell[0], cell[1], cell[2]);
                    if (!paired.contains(from) && !displaced.contains(from)) displaced.add(from);
                }
                if (dear) continue;
                int n = base.takers(unpackX(half), unpackY(half), unpackZ(half), cells, takers);
                for (int t = 0; t < n; t++) {
                    long free = pack(takers[3 * t], takers[3 * t + 1], takers[3 * t + 2]);
                    if (!claimed.containsKey(free) && !vacated.contains(free)
                            && writable.test(takers[3 * t], takers[3 * t + 1], takers[3 * t + 2])) {
                        vacated.add(free);
                    }
                }
            }
            if (bijection && displaced.size() > vacated.size()) {
                for (long half : halves) claimed.remove(landed.remove(half));
                dropped++;
                continue;
            }
            for (int d = 0; d < displaced.size() && d < vacated.size(); d++) {
                claimed.put(vacated.getLong(d), displaced.getLong(d));
                moved.computeIfAbsent(displaced.getLong(d), k -> new LongArrayList()).add(vacated.getLong(d));
            }
            lost += Math.max(0, displaced.size() - vacated.size());
            long first = landed.get(halves[0]), second = landed.get(halves[1]);
            partners.put(first, second);
            partners.put(second, first);
            placed++;
        }
        return new TwoCellPairs(base, claimed, paired, landed, moved, partners, placed, dropped, lost);
    }

    private static boolean inside(BlockPos box, int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < box.x() && y < box.y() && z < box.z();
    }

    /**
     * Every source cell of {@code cells} in the source box that holds a half, packed: the candidates for
     * {@link #of} when the caller has no list of its own (a walk over the whole box).
     */
    public static long[] halves(CellResampler base, SourceCells cells, TwoCellBlocks blocks) {
        BlockPos size = base.sourceSize();
        LongArrayList out = new LongArrayList();
        for (int y = 0; y < size.y(); y++) {
            for (int z = 0; z < size.z(); z++) {
                for (int x = 0; x < size.x(); x++) {
                    addHalf(out, blocks, cells.state(x, y, z), x, y, z);
                }
            }
        }
        return out.toLongArray();
    }

    /** How many pairs were dropped (two blocks each). */
    public int droppedPairs() {
        return dropped;
    }

    /** How many pairs were placed. */
    public int placedPairs() {
        return placed;
    }

    /** Blocks that a placed half displaced and that found no cell to move into (never with exact count). */
    public long lostBlocks() {
        return lost;
    }

    /** Visits each placed pair's halves: (x, y, z) a half's result cell, (px, py, pz) the other half's. */
    @FunctionalInterface
    public interface PairVisitor {
        void half(int x, int y, int z, int px, int py, int pz);
    }

    /** Every half of every placed pair, with the other half's cell. */
    public void forEachHalf(PairVisitor visitor) {
        for (Long2LongMap.Entry e : partners.long2LongEntrySet()) {
            long a = e.getLongKey(), b = e.getLongValue();
            visitor.half(unpackX(a), unpackY(a), unpackZ(a), unpackX(b), unpackY(b), unpackZ(b));
        }
    }

    /** Whether any pair was placed. */
    public boolean hasPairs() {
        return !partners.isEmpty();
    }

    /**
     * The result cell of the other half, into {@code out}, when result cell (x, y, z) holds a placed pair's half: the
     * two are written together or not at all. False for every other cell.
     */
    public boolean partner(int x, int y, int z, int[] out) {
        if (partners.isEmpty() || !packable(x, y, z)) return false;
        long other = partners.get(pack(x, y, z));
        if (other == -1) return false;
        unpackInto(other, out, 0);
        return true;
    }

    /** The resampler that places every other cell. */
    public CellResampler base() {
        return base;
    }

    @Override
    public Orientation orientation() {
        return base.orientation();
    }

    @Override
    public BlockPos size() {
        return base.size();
    }

    @Override
    public BlockPos offset() {
        return base.offset();
    }

    @Override
    public BlockPos sourceSize() {
        return base.sourceSize();
    }

    @Override
    public boolean source(int x, int y, int z, SourceCells cells, int[] out) {
        if (!claimed.isEmpty()) {
            long claim = claimed.get(pack(x, y, z));
            if (claim != -1) {
                out[0] = unpackX(claim);
                out[1] = unpackY(claim);
                out[2] = unpackZ(claim);
                return true;
            }
        }
        if (!base.source(x, y, z, cells, out)) return false;
        return paired.isEmpty() || !paired.contains(pack(out[0], out[1], out[2]));
    }

    @Override
    public int maxTakers() {
        return base.maxTakers();
    }

    @Override
    public int takers(int x, int y, int z, SourceCells cells, int[] out) {
        if (paired.isEmpty()) return base.takers(x, y, z, cells, out);
        long cell = pack(x, y, z);
        if (!paired.isEmpty() && paired.contains(cell)) {
            long at = landed.get(cell);
            if (at == -1) return 0;
            out[0] = unpackX(at);
            out[1] = unpackY(at);
            out[2] = unpackZ(at);
            return 1;
        }
        int n = base.takers(x, y, z, cells, out);
        if (claimed.isEmpty()) return n;
        // The underlying takers no pair claimed, and the cells the block moved to (each move replaces a claimed taker,
        // so there are never more than the underlying resampler's most), in the fixed order.
        LongArrayList kept = new LongArrayList(n + 2);
        for (int i = 0; i < n; i++) {
            long at = pack(out[3 * i], out[3 * i + 1], out[3 * i + 2]);
            if (!claimed.containsKey(at)) kept.add(at);
        }
        LongArrayList moves = moved.get(cell);
        if (moves == null) {
            for (int i = 0; i < kept.size(); i++) unpackInto(kept.getLong(i), out, i);
            return kept.size();
        }
        kept.addAll(moves);
        long[] sorted = kept.toLongArray();
        java.util.Arrays.sort(sorted);
        for (int i = 0; i < sorted.length; i++) unpackInto(sorted[i], out, i);
        return sorted.length;
    }

    private static void unpackInto(long packed, int[] out, int i) {
        out[3 * i] = unpackX(packed);
        out[3 * i + 1] = unpackY(packed);
        out[3 * i + 2] = unpackZ(packed);
    }

    /**
     * The underlying resampler's turned box of the source box grown by {@link #REACH} cells (when any pair was placed): a
     * pair's other half lands beside its first cell, and a displaced block moves into a cell its pair vacated, never
     * further than that.
     */
    @Override
    public int[] turnedBox(int x0, int y0, int z0, int x1, int y1, int z1) {
        if (claimed.isEmpty()) return base.turnedBox(x0, y0, z0, x1, y1, z1);
        BlockPos s = base.sourceSize();
        return base.turnedBox(Math.max(0, x0 - REACH), Math.max(0, y0 - REACH), Math.max(0, z0 - REACH),
                Math.min(s.x() - 1, x1 + REACH), Math.min(s.y() - 1, y1 + REACH), Math.min(s.z() - 1, z1 + REACH));
    }

    /** How far (in cells) a pair's half or a displaced block can land from where the underlying resampler puts it. */
    private static final int REACH = 3;

    @Override
    public void forward(double x, double y, double z, double[] out) {
        base.forward(x, y, z, out);
    }

    @Override
    public void back(double x, double y, double z, double[] out) {
        base.back(x, y, z, out);
    }

    /** Whether a cell can be packed ({@link #pack}). */
    static boolean packable(int x, int y, int z) {
        return (x | y | z) >= 0 && x < 1 << 24 && z < 1 << 24 && y < 1 << 15;
    }

    /**
     * Adds {@code (x, y, z)} to {@code out} as a candidate for {@link #of} when it holds a half (and can be packed): the
     * one way the programs and the ghost collect them.
     */
    public static void addHalf(LongArrayList out, TwoCellBlocks blocks, int state, int x, int y, int z) {
        if (state >= 0 && blocks.isHalf(state) && packable(x, y, z)) out.add(pack(x, y, z));
    }

    /** Whether every cell of a box of {@code size} can be packed ({@link #pack}). */
    static boolean packable(BlockPos size) {
        return size.x() <= 1 << 24 && size.z() <= 1 << 24 && size.y() <= 1 << 15;
    }

    /** A local cell packed into a long, ordered by y, then z, then x: y in 15 bits, z and x in 24 each (non-negative). */
    public static long pack(int x, int y, int z) {
        if ((x | y | z) < 0 || x >= 1 << 24 || z >= 1 << 24 || y >= 1 << 15) {
            throw new IllegalArgumentException("A local cell outside the packable range: " + x + "," + y + "," + z);
        }
        return ((long) y << 48) | ((long) z << 24) | x;
    }

    static int unpackX(long packed) {
        return (int) (packed & 0xFFFFFF);
    }

    static int unpackY(long packed) {
        return (int) (packed >>> 48);
    }

    static int unpackZ(long packed) {
        return (int) ((packed >>> 24) & 0xFFFFFF);
    }
}
