package dev.sculptory.core.edit;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.Regions;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Overlay and Naturalize: edits that work column by column, from
 * each column's highest block.
 *
 * <p><b>The highest block</b> of a column of the region is found scanning the world down from the column's topmost region
 * cell: the scan passes through <em>cover</em> (air; plants, flowers and leaves, {@link StateFlags#VEGETATION}; anything
 * replaceable such as short grass; anything without collision such as torches, rails and signs; snow layers; logs, so a
 * tree is looked past) and stops at the first other cell, inside the region or not (a gap in a block selection is looked
 * through). When that cell is a fluid (water or lava, or a block that is always under water such as seagrass; a
 * waterlogged stair is a stair) or outside the region, the column has no highest block and is left alone; otherwise it
 * is the highest block.
 * A column whose topmost region cell has anything but cover right above it is buried (the region cuts into the ground),
 * and is left alone too. Each column is scanned once, on the world as it is before the edit writes that column
 * ({@link #compute} caches the columns of a chunk column: the executor writes a chunk column's sections one after the
 * other).
 *
 * <ul>
 *   <li><b>Overlay</b> lays the pattern on top of the highest block, up to {@code depth} cells, through air, replaceable
 *       cells (short grass, a thin snow layer) and snow layers only: it stops at the first other cell (a flower, tall
 *       grass, leaves, a log, a torch), so it never buries plants or builds through a canopy. The layer may reach
 *       above the region (a region of just the ground still gets its layer): the program's bounds and sections
 *       include up to {@code depth} cells above it.</li>
 *   <li><b>Naturalize</b> rewrites the natural ground ({@link #natural} full blocks without a block entity, and the
 *       op's own blocks) of each column's region cells at and below the highest block: {@code topDepth} cells of
 *       {@code top} (grass), then {@code middleDepth} of {@code middle} (dirt), then {@code bottom} (stone), counting
 *       depth in cells below the highest block, so a cave keeps the layers above it. Anything else (stairs, chests, air
 *       in a cave, planks, bricks, logs) is left as it is.</li>
 * </ul>
 */
final class ColumnProgram implements ClaimingProgram {
    enum Kind { OVERLAY, NATURALIZE }

    /** A column without a highest block. */
    private static final int NONE = Integer.MIN_VALUE;
    /** Cell classes ({@link #classes}). */
    static final byte COVER = 1, FLUID = 2, WRITABLE = 4, GROUND = 8;
    private static final NamespacedId SNOW_LAYER = new NamespacedId("minecraft:snow");
    private static final NamespacedId LOGS = new NamespacedId("minecraft:logs");
    private static final List<NamespacedId> NATURAL_TAGS = Stream.of("minecraft:dirt",
            "minecraft:sand", "minecraft:base_stone_overworld", "minecraft:base_stone_nether",
            "minecraft:stone_ore_replaceables", "minecraft:deepslate_ore_replaceables", "minecraft:terracotta",
            "minecraft:snow", "minecraft:nylium", "minecraft:coal_ores", "minecraft:iron_ores", "minecraft:copper_ores",
            "minecraft:gold_ores", "minecraft:redstone_ores", "minecraft:lapis_ores", "minecraft:diamond_ores",
            "minecraft:emerald_ores", "c:ores").map(NamespacedId::new).toList();
    private static final Set<NamespacedId> NATURAL_BLOCKS = Stream.of("minecraft:stone",
            "minecraft:granite", "minecraft:diorite", "minecraft:andesite", "minecraft:deepslate", "minecraft:tuff",
            "minecraft:calcite", "minecraft:dripstone_block", "minecraft:smooth_basalt", "minecraft:gravel",
            "minecraft:clay", "minecraft:sandstone", "minecraft:red_sandstone", "minecraft:grass_block", "minecraft:dirt",
            "minecraft:coarse_dirt", "minecraft:podzol", "minecraft:mycelium", "minecraft:rooted_dirt", "minecraft:mud",
            "minecraft:moss_block", "minecraft:snow_block", "minecraft:netherrack", "minecraft:end_stone",
            "minecraft:sand", "minecraft:red_sand").map(NamespacedId::new).collect(Collectors.toSet());

    private final Kind kind;
    private final String label;
    private final Region region;
    private final RegionProgram.Height height;
    private final Box bounds;
    private final long estimatedCells;
    private final long[] sectionOrder;
    /** The region's sections by chunk column, their section y ascending. */
    private final Long2ObjectOpenHashMap<int[]> regionColumns;
    private final StateSpace states;
    private final byte[] classes;
    private final CopyFrame frame;
    private final Pattern[] patterns;
    private final int depth;
    private final int topDepth;
    private final int middleDepth;

    private long cachedColumn = Long.MIN_VALUE;
    private final int[] tops = new int[256];
    private final int[] layers = new int[256];
    private final int[] rows = new int[Regions.ROWS];

    private ColumnProgram(Kind kind, String label, Region region, RegionProgram.Height height, int depth, int topDepth,
                          int middleDepth, Pattern[] patterns, StateSpace states, byte[] classes, long maxSections,
                          CopyFrame frame) {
        this.kind = kind;
        this.label = label;
        this.region = Objects.requireNonNull(region);
        this.height = height;
        this.depth = depth;
        this.topDepth = topDepth;
        this.middleDepth = middleDepth;
        this.patterns = patterns;
        this.states = Objects.requireNonNull(states);
        this.classes = classes;
        this.frame = frame;
        Box own = height.bounds(region.bounds());
        long[] sections = sections(region, height, maxSections);
        regionColumns = byColumn(sections);
        this.estimatedCells = region instanceof Region.Cuboid ? own.volume()
                : Regions.cellsBetween(region, height.bottom(), height.top());
        if (kind == Kind.OVERLAY) {
            // The layer may reach depth cells above the region: one section up at most.
            int top = (int) Math.min((long) own.max().y() + depth, height.top());
            this.bounds = new Box(own.min(), new BlockPos(own.max().x(), top, own.max().z()));
            LongOpenHashSet all = new LongOpenHashSet(sections);
            int topSection = height.top() >> 4;
            for (long key : sections) {
                int sy = BlockBuffer.keyY(key);
                if (sy < topSection) all.add(BlockBuffer.key(BlockBuffer.keyX(key), sy + 1, BlockBuffer.keyZ(key)));
            }
            long[] order = all.toLongArray();
            long cap = Math.min(maxSections, RegionProgram.MAX_SECTIONS);
            if (order.length > cap) {
                throw new EditTooLargeException("Overlay spans " + order.length + " sections > " + cap, order.length, cap);
            }
            SectionOrder.sort(order);
            this.sectionOrder = order;
        } else {
            this.bounds = own;
            this.sectionOrder = sections;
        }
    }

    static ColumnProgram overlay(Region region, RegionProgram.Height height, Pattern pattern, int depth, StateSpace states,
                                 byte[] classes, long maxSections, CopyFrame frame) {
        checkDepth(depth, 1);
        return new ColumnProgram(Kind.OVERLAY, "Overlay", region, height, depth, 0, 0, new Pattern[] {pattern}, states,
                classes, maxSections, frame);
    }

    static ColumnProgram naturalize(Region region, RegionProgram.Height height, Pattern top, int topDepth, Pattern middle,
                                    int middleDepth, Pattern bottom, StateSpace states, byte[] classes, long maxSections,
                                    CopyFrame frame) {
        checkDepth(topDepth, 1);
        checkDepth(middleDepth, 0);
        return new ColumnProgram(Kind.NATURALIZE, "Naturalize", region, height, 0, topDepth, middleDepth,
                new Pattern[] {top, middle, bottom}, states, classes, maxSections, frame);
    }

    private static void checkDepth(int depth, int min) {
        if (depth < min || depth > OpSpec.MAX_LAYER_DEPTH) {
            throw new IllegalArgumentException("Depth " + depth + " is not " + min + " to " + OpSpec.MAX_LAYER_DEPTH);
        }
    }

    /**
     * Every state's class for the column scan, shared by an op's copies: {@link #COVER} (also logs, so a tree's trunk is
     * looked past to the ground under it), {@link #FLUID}, and on top {@link #WRITABLE} (what Overlay's layer goes through:
     * air, replaceable non-fluids, snow layers) and {@link #GROUND} (what Naturalize rewrites: {@link #natural} full blocks
     * without a block entity, and the blocks of {@code own}, Naturalize's own patterns, so it can be run again).
     */
    static byte[] classes(StateSpace states, Pattern... own) {
        BitSet mine = new BitSet();
        for (Pattern pattern : own) {
            switch (pattern) {
                case Pattern.Single single -> mine.set(single.state());
                case Pattern.Weighted weighted -> {
                    for (int i = 0; i < weighted.size(); i++) mine.set(weighted.state(i));
                }
                case Pattern.Arranged arranged -> {
                    for (int i = 0; i < arranged.mix().size(); i++) mine.set(arranged.mix().state(i));
                }
                default -> { }
            }
        }
        Set<NamespacedId> ownBlocks = new HashSet<>();
        mine.stream().filter(h -> h < states.size()).forEach(h -> ownBlocks.add(states.blockId(h)));
        byte[] classes = new byte[states.size()];
        for (int h = 0; h < classes.length; h++) {
            int flags = states.flags(h);
            NamespacedId block = states.blockId(h);
            boolean snow = block.equals(SNOW_LAYER);
            boolean fluid = StateFlags.has(flags, StateFlags.FLUID_BLOCK)
                    || (StateFlags.has(flags, StateFlags.WATER) && !StateFlags.has(flags, StateFlags.WATERLOGGABLE));
            byte c = 0;
            if (fluid) {
                c = FLUID;
            } else if (StateFlags.has(flags, StateFlags.AIR) || snow || states.inTag(h, LOGS)
                    || (!StateFlags.has(flags, StateFlags.TERRAIN_SOLID) && (flags & (StateFlags.VEGETATION
                    | StateFlags.REPLACEABLE | StateFlags.NO_COLLISION)) != 0)) {
                c = COVER;
            }
            boolean blockEntity = StateFlags.has(flags, StateFlags.HAS_BLOCK_ENTITY);
            // Never half of a two-tall plant: the other half would be left standing alone.
            if (!fluid && !blockEntity && !StateFlags.has(flags, StateFlags.WATER)
                    && !StateFlags.has(flags, StateFlags.DOUBLE_TALL) && (StateFlags.has(flags, StateFlags.AIR)
                    || snow || StateFlags.has(flags, StateFlags.REPLACEABLE))) {
                c |= WRITABLE;
            }
            if (StateFlags.has(flags, StateFlags.TERRAIN_SOLID) && !blockEntity && (c & COVER) == 0
                    && (natural(states, h) || ownBlocks.contains(block))) {
                c |= GROUND;
            }
            classes[h] = c;
        }
        return classes;
    }

    /**
     * Natural terrain, what Naturalize rewrites (as WorldEdit's does): dirt, sand, stone and the other rocks, ores,
     * terracotta, gravel, clay, snow, netherrack and end stone; never planks, bricks, glass or wool (a build in the
     * selection keeps its walls).
     */
    static boolean natural(StateSpace states, int h) {
        if (NATURAL_BLOCKS.contains(states.blockId(h))) return true;
        for (NamespacedId tag : NATURAL_TAGS) {
            if (states.inTag(h, tag)) return true;
        }
        return false;
    }

    /** The region's sections inside the build height, in {@link SectionOrder} order. */
    static long[] sections(Region region, RegionProgram.Height height, long maxSections) {
        if (region instanceof Region.Cuboid cuboid) {
            Box box = height.bounds(cuboid.box());
            if (SectionOrder.sectionCount(box) > Math.min(maxSections, RegionProgram.MAX_SECTIONS)) {
                throw new EditTooLargeException("Edit spans too many sections: " + box, SectionOrder.sectionCount(box),
                        Math.min(maxSections, RegionProgram.MAX_SECTIONS));
            }
            LongArrayList keys = new LongArrayList((int) SectionOrder.sectionCount(box));
            box.forEachSectionKey(keys::add);
            return keys.toLongArray();
        }
        return RegionProgram.regionSections(region, height, maxSections);
    }

    private static Long2ObjectOpenHashMap<int[]> byColumn(long[] sections) {
        Long2ObjectOpenHashMap<IntArrayList> lists = new Long2ObjectOpenHashMap<>();
        for (long key : sections) {
            long column = EditProgram.column(BlockBuffer.keyX(key), BlockBuffer.keyZ(key));
            lists.computeIfAbsent(column, c -> new IntArrayList()).add(BlockBuffer.keyY(key));
        }
        Long2ObjectOpenHashMap<int[]> columns = new Long2ObjectOpenHashMap<>(lists.size());
        lists.long2ObjectEntrySet().fastForEach(entry -> {
            int[] ys = entry.getValue().toIntArray();
            Arrays.sort(ys);
            columns.put(entry.getLongKey(), ys);
        });
        return columns;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public Box bounds() {
        return bounds;
    }

    /** The region's cells inside the build height (what the scan covers; Overlay may write fewer or, above, more). */
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
        return sectionOrder.clone();
    }

    @Override
    public void compute(long key, SectionBuffer before, SectionBuffer out, ComputeContext ctx) {
        compute(key, before, out, ctx, null);
    }

    @Override
    public void compute(long key, SectionBuffer before, SectionBuffer out, ComputeContext ctx, long[] claimed) {
        int sx = BlockBuffer.keyX(key), sy = BlockBuffer.keyY(key), sz = BlockBuffer.keyZ(key);
        long column = EditProgram.column(sx, sz);
        if (column != cachedColumn) {
            scan(sx, sz, ctx.world());
            cachedColumn = column;
        }
        int ox = sx << 4, oy = sy << 4, oz = sz << 4;
        int y0 = Math.max(oy, height.bottom()), y1 = Math.min(oy + 15, height.top());
        boolean regionSection = kind == Kind.NATURALIZE
                && Regions.rows(region, key, height.bottom(), height.top(), rows) > 0;
        if (kind == Kind.NATURALIZE && !regionSection) return;
        for (int col = 0; col < 256; col++) {
            int top = tops[col];
            if (top == NONE) continue;
            int lx = col & 15, lz = col >>> 4;
            if (kind == Kind.OVERLAY) {
                int from = Math.max(y0, top + 1), to = Math.min(y1, top + layers[col]);
                for (int y = from; y <= to; y++) {
                    int i = SectionBuffer.index(lx, y - oy, lz);
                    if (ClaimingProgram.claimed(claimed, i)) continue;
                    int current = before.get(i);
                    if ((classes[current] & WRITABLE) == 0) continue;
                    ClaimingProgram.claim(claimed, i);
                    write(before, out, i, patterns[0], ox + lx, y, oz + lz, current);
                }
            } else {
                for (int y = y0; y <= Math.min(y1, top); y++) {
                    int ly = y - oy;
                    if ((rows[(ly << 4) | lz] & (1 << lx)) == 0) continue;
                    int i = SectionBuffer.index(lx, ly, lz);
                    if (ClaimingProgram.claimed(claimed, i)) continue;
                    int current = before.get(i);
                    if ((classes[current] & GROUND) == 0) continue;
                    ClaimingProgram.claim(claimed, i);
                    int below = top - y;
                    Pattern layer = below < topDepth ? patterns[0] : below < topDepth + middleDepth ? patterns[1]
                            : patterns[2];
                    write(before, out, i, layer, ox + lx, y, oz + lz, current);
                }
            }
        }
    }

    private void write(SectionBuffer before, SectionBuffer out, int i, Pattern pattern, int x, int y, int z, int current) {
        int next = frame == null ? pattern.apply(states, x, y, z, current) : frame.apply(pattern, x, y, z, current);
        if (next != current) out.set(i, next);
    }

    /**
     * Finds the highest block of every column of chunk column (sx, sz) ({@link #tops}, {@link #NONE} for none), and for
     * Overlay how many cells of its layer are open ({@link #layers}).
     */
    private void scan(int sx, int sz, WorldReader world) {
        Arrays.fill(tops, NONE);
        Arrays.fill(layers, 0);
        int[] ys = regionColumns.get(EditProgram.column(sx, sz));
        if (ys == null || world == null || !world.isLoaded(sx, sz)) return;
        int ox = sx << 4, oz = sz << 4;
        // 1. Each column's topmost region cell.
        int[] topmost = new int[256];
        Arrays.fill(topmost, NONE);
        int[] found = new int[16];
        int open = 256;
        for (int k = ys.length - 1; k >= 0 && open > 0; k--) {
            int sy = ys[k];
            if (Regions.rows(region, BlockBuffer.key(sx, sy, sz), height.bottom(), height.top(), rows) == 0) continue;
            for (int ly = 15; ly >= 0 && open > 0; ly--) {
                for (int lz = 0; lz < 16; lz++) {
                    int row = rows[(ly << 4) | lz] & ~found[lz];
                    found[lz] |= row;
                    while (row != 0) {
                        int lx = Integer.numberOfTrailingZeros(row);
                        row &= row - 1;
                        topmost[(lz << 4) | lx] = (sy << 4) + ly;
                        open--;
                    }
                }
            }
        }
        // 2. Down the world from there, through cover (also cells outside the region: a gap in a block selection) to
        // the first other cell, which must be a region cell.
        int bottom = Math.max(height.bottom(), region.bounds().min().y());
        for (int col = 0; col < 256; col++) {
            int start = topmost[col];
            if (start == NONE) continue;
            int x = ox + (col & 15), z = oz + (col >>> 4);
            // Buried unless cover lies right above the topmost region cell.
            int above = start + 1 > height.top() ? states.air() : world.get(x, start + 1, z);
            if ((classes[above] & COVER) == 0) continue;
            for (int y = start; y >= bottom; y--) {
                int c = classes[world.get(x, y, z)];
                if ((c & COVER) != 0) continue;
                if ((c & FLUID) == 0 && region.contains(x, y, z)) {
                    tops[col] = y;
                    if (kind == Kind.OVERLAY) layers[col] = openAbove(world, x, y, z);
                }
                break;
            }
        }
    }

    /** How many of the {@code depth} cells above (x, y, z) Overlay's layer can take, from the bottom up. */
    private int openAbove(WorldReader world, int x, int y, int z) {
        int n = 0;
        while (n < depth && (long) y + n + 1 <= height.top()
                && (classes[world.get(x, y + n + 1, z)] & WRITABLE) != 0) {
            n++;
        }
        return n;
    }
}
