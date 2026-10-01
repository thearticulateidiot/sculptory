package dev.sculptory.protocol.v2;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.ShapeSpec;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.brush.SculptMode;
import dev.sculptory.core.brush.SurfacePlane;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.brush.WeatherSpec;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.MixLayout;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.mask.BlockSet;
import dev.sculptory.core.mask.EditMask;
import dev.sculptory.core.mask.MaskEntry;
import dev.sculptory.core.mask.MaskRule;
import dev.sculptory.core.palette.BlockPalette;
import dev.sculptory.core.palette.PalettePattern;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import dev.sculptory.core.scatter.ScatterArea;
import dev.sculptory.core.scatter.ScatterPlan;
import dev.sculptory.core.scatter.ScatterSettings;
import dev.sculptory.core.scatter.ScatterSource;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.tinker.EntityEdit;
import dev.sculptory.core.tinker.EntityEdits;
import dev.sculptory.core.tinker.SignText;
import dev.sculptory.core.transform.Transform;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** Random valid instances of every message and wire value type, for round-trip and fuzz tests. */
final class RandomMessages {
    private static final String[] ALPHABET = {"a", "z", "0", "_", "/", " ", "é", "ß", "中", "😀", "\u0000"};
    private static final List<String> TAGS = List.of("minecraft:logs", "minecraft:dirt", "minecraft:sand", "minecraft:stairs");

    final Random rnd;
    final FakeStateSpace states;

    RandomMessages(long seed, FakeStateSpace states) {
        this.rnd = new Random(seed);
        this.states = states;
    }

    // ---------------------------------------------------------------- primitives

    int handle() {
        return rnd.nextInt(states.size());
    }

    int anyInt() {
        return switch (rnd.nextInt(4)) {
            case 0 -> rnd.nextInt(100);
            case 1 -> -rnd.nextInt(100);
            case 2 -> rnd.nextBoolean() ? Integer.MAX_VALUE : Integer.MIN_VALUE;
            default -> rnd.nextInt();
        };
    }

    long anyLong() {
        return switch (rnd.nextInt(4)) {
            case 0 -> rnd.nextInt(1000);
            case 1 -> -rnd.nextInt(1000);
            case 2 -> rnd.nextBoolean() ? Long.MAX_VALUE : Long.MIN_VALUE;
            default -> rnd.nextLong();
        };
    }

    long nonNegativeLong() {
        return rnd.nextBoolean() ? rnd.nextInt(1 << 20) : rnd.nextLong() & Long.MAX_VALUE;
    }

    UUID uuid() {
        return new UUID(rnd.nextLong(), rnd.nextLong());
    }

    /** Random text (including multi-byte characters) of at most {@code maxBytes} UTF-8 bytes. */
    String text(int maxBytes) {
        StringBuilder text = new StringBuilder();
        int target = rnd.nextInt(Math.min(maxBytes, 40) + 1);
        int bytes = 0;
        while (true) {
            String next = ALPHABET[rnd.nextInt(ALPHABET.length)];
            int size = next.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > target) return text.toString();
            text.append(next);
            bytes += size;
        }
    }

    String hash() {
        byte[] data = new byte[8];
        rnd.nextBytes(data);
        return Sha256.digest(data).hex();
    }

    <E extends Enum<E>> E pick(E[] values) {
        return values[rnd.nextInt(values.length)];
    }

    BlockPos pos() {
        return new BlockPos(coordinate(), coordinate(), coordinate());
    }

    private int coordinate() {
        return rnd.nextInt(4) == 0 ? rnd.nextInt(60_000_000) - 30_000_000 : rnd.nextInt(512) - 256;
    }

    Box box() {
        BlockPos min = pos();
        return new Box(min, min.offset(rnd.nextInt(300), rnd.nextInt(300), rnd.nextInt(300)));
    }

    /** Any region the wire carries: a box, a shape of any kind and facing, or an uploaded cell set. */
    Region region() {
        Box box = box();
        return switch (rnd.nextInt(3)) {
            case 0 -> new Region.Cuboid(box);
            case 1 -> new Region.Shape(box, pick(ShapeKind.values()), pick(Facing.values()));
            default -> new Region.Uploaded(new Sha256(hash()), box, 1 + (rnd.nextLong() & Long.MAX_VALUE) % box.volume());
        };
    }

    /** A horizontal transform (scatter placements are never flipped). */
    Transform transform() {
        return new Transform(rnd.nextInt(4), pick(Mirror.values()));
    }

    /** Any transform an op may carry: possibly flipped upside down (protocol 5). */
    Transform opTransform() {
        return new Transform(rnd.nextInt(4), pick(Mirror.values()), rnd.nextBoolean());
    }

    NamespacedId id() {
        List<NamespacedId> known = states.blockIds();
        if (rnd.nextBoolean()) return known.get(rnd.nextInt(known.size()));
        return new NamespacedId("mod" + rnd.nextInt(9) + ":thing/" + rnd.nextInt(99));
    }

    Features features() {
        TreeSet<String> names = new TreeSet<>();
        int count = rnd.nextInt(8);
        for (int i = 0; i < count; i++) names.add(rnd.nextBoolean() ? Features.STROKES : "feature_" + rnd.nextInt(100));
        return new Features(names);
    }

    Limits limits() {
        return new Limits(1 + nonNegativeLong() % Long.MAX_VALUE, 1 + rnd.nextInt(Integer.MAX_VALUE), 1 + rnd.nextInt(64),
                1 + rnd.nextInt(100), 1 + rnd.nextInt(Integer.MAX_VALUE), 1 + rnd.nextInt(8),
                1 + nonNegativeLong() % Long.MAX_VALUE, 1 + rnd.nextInt(Integer.MAX_VALUE));
    }

    // ---------------------------------------------------------------- core values

    /** Any pattern an op may carry: every kind, a mix laid out any way but by steepness. */
    Pattern pattern() {
        return pattern(false);
    }

    /** Any pattern; with {@code steepness} also a mix laid out by steepness (only Palette Paint's brush takes one). */
    Pattern pattern(boolean steepness) {
        switch (rnd.nextInt(10)) {
            case 8 -> {
                return new Pattern.KeepShape(rnd.nextBoolean() ? new Pattern.Single(handle()) : weighted());
            }
            case 9 -> {
                return remap();
            }
            case 6 -> {
                return rnd.nextBoolean()
                        ? new Pattern.SetProperty(states.state("minecraft:oak_stairs[shape=outer_left]"), "shape")
                        : new Pattern.SetProperty(states.state("testmod:widget[facing=up]"), "facing");
            }
            case 0 -> {
                return new Pattern.Waterlog(states.state(rnd.nextBoolean() ? "minecraft:water[level=0]" : "minecraft:lava[level=0]"));
            }
            case 1 -> {
                return new Pattern.Dry();
            }
            case 2, 3 -> {
                return new Pattern.Single(handle());
            }
            case 4 -> {
                return new Pattern.Arranged(weighted(), layout(steepness));
            }
            default -> {
            }
        }
        return weighted();
    }

    /** Better Replace's Whole family: 1 to 40 swaps (the full {@value Pattern.Remap#MAX_SWAPS} would not fit a frame). */
    Pattern.Remap remap() {
        int count = 1 + rnd.nextInt(rnd.nextInt(8) == 0 ? 40 : 4);
        List<Pattern.BlockSwap> swaps = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            swaps.add(new Pattern.BlockSwap(new NamespacedId("minecraft:oak_" + i), id()));
        }
        return new Pattern.Remap(swaps, rnd.nextBoolean());
    }

    Pattern.Weighted weighted() {
        int count = 1 + rnd.nextInt(rnd.nextInt(8) == 0 ? Pattern.Weighted.MAX_ENTRIES : 6);
        Set<Integer> distinct = new LinkedHashSet<>();
        while (distinct.size() < count) distinct.add(handle());
        int[] handles = distinct.stream().mapToInt(Integer::intValue).toArray();
        int[] weights = new int[count];
        for (int i = 0; i < count; i++) weights[i] = 1 + rnd.nextInt(Pattern.Weighted.MAX_WEIGHT);
        return new Pattern.Weighted(handles, weights, anyLong());
    }

    /** Any layout over its whole ranges (lines anywhere within ±2²⁵ and ±4096); Steepness only when asked. */
    MixLayout layout(boolean steepness) {
        switch (rnd.nextInt(steepness ? 3 : 2)) {
            case 0 -> {
                return new MixLayout.Patches(MixLayout.Patches.MIN_SIZE + rnd.nextInt(MixLayout.Patches.MAX_SIZE));
            }
            case 1 -> {
                BlockPos from = lineEnd();
                BlockPos to = lineEnd();
                while (to.equals(from)) to = lineEnd();
                return new MixLayout.Gradient(from, to, rnd.nextInt(MixLayout.Gradient.MAX_EDGE + 1));
            }
            default -> {
                return new MixLayout.Steepness(rnd.nextInt(MixLayout.Steepness.MAX_EDGE + 1));
            }
        }
    }

    private BlockPos lineEnd() {
        if (rnd.nextBoolean()) return new BlockPos(rnd.nextInt(64) - 32, rnd.nextInt(64), rnd.nextInt(64) - 32);
        int h = MixLayout.MAX_HORIZONTAL, v = MixLayout.MAX_VERTICAL;
        return new BlockPos(rnd.nextInt(2 * h + 1) - h, rnd.nextInt(2 * v + 1) - v, rnd.nextInt(2 * h + 1) - h);
    }

    CellMask mask() {
        return mask(1, new int[] {CellMask.MAX_NODES});
    }

    private CellMask mask(int depth, int[] budget) {
        budget[0]--;
        boolean leaf = depth >= CellMask.MAX_DEPTH || budget[0] < 3 || rnd.nextInt(3) == 0;
        if (leaf) {
            return switch (rnd.nextInt(4)) {
                case 0 -> CellMask.ANY;
                case 1 -> {
                    int count = rnd.nextInt(10) == 0 ? states.size() : rnd.nextInt(12);
                    int[] handles = new int[count];
                    for (int i = 0; i < count; i++) handles[i] = rnd.nextInt(10) == 0 ? i % states.size() : handle();
                    yield new CellMask.States(handles);
                }
                case 2 -> {
                    List<NamespacedId> ids = new ArrayList<>();
                    int count = rnd.nextInt(5);
                    for (int i = 0; i < count; i++) ids.add(id());
                    yield new CellMask.Blocks(ids);
                }
                default -> new CellMask.Tag(new NamespacedId(TAGS.get(rnd.nextInt(TAGS.size()))));
            };
        }
        return switch (rnd.nextInt(3)) {
            case 0 -> new CellMask.And(children(depth, budget));
            case 1 -> new CellMask.Or(children(depth, budget));
            default -> new CellMask.Not(mask(depth + 1, budget));
        };
    }

    private List<CellMask> children(int depth, int[] budget) {
        List<CellMask> children = new ArrayList<>();
        int count = 1 + rnd.nextInt(3);
        for (int i = 0; i < count && (i == 0 || budget[0] > 2); i++) children.add(mask(depth + 1, budget));
        return children;
    }

    SurfaceMask surface() {
        return surface(1, new int[] {SurfaceMask.MAX_NODES});
    }

    private SurfaceMask surface(int depth, int[] budget) {
        budget[0]--;
        boolean leaf = depth >= SurfaceMask.MAX_DEPTH || budget[0] < 3 || rnd.nextInt(3) == 0;
        if (leaf) {
            return switch (rnd.nextInt(5)) {
                // One rule of a few blocks: a tree of 64 nodes of full masks would not fit a frame.
                case 4 -> new SurfaceMask.Rules(new EditMask(List.of(new MaskEntry(maskRule(2), rnd.nextBoolean())),
                        rnd.nextBoolean()));
                case 0 -> SurfaceMask.ANY;
                // The nested cell mask shares the surface tree's node budget.
                case 1 -> new SurfaceMask.SurfaceBlocks(mask(1, budget));
                case 2 -> rnd.nextInt(8) == 0
                        ? new SurfaceMask.Elevation(Integer.MIN_VALUE, Integer.MAX_VALUE)
                        : new SurfaceMask.Elevation(-64 + rnd.nextInt(100), 40 + rnd.nextInt(300));
                default -> {
                    int min = rnd.nextInt(10);
                    yield new SurfaceMask.Slope(min, min + rnd.nextInt(10));
                }
            };
        }
        if (rnd.nextBoolean()) return new SurfaceMask.Not(surface(depth + 1, budget));
        List<SurfaceMask> children = new ArrayList<>();
        int count = 1 + rnd.nextInt(3);
        for (int i = 0; i < count && (i == 0 || budget[0] > 2); i++) children.add(surface(depth + 1, budget));
        return new SurfaceMask.And(children);
    }

    /** Any global mask: 0 to 16 rules of every kind, sometimes negated, sometimes inverted as a whole. */
    EditMask editMask() {
        int count = rnd.nextInt(8) == 0 ? EditMask.MAX_ENTRIES : rnd.nextInt(5);
        List<MaskEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) entries.add(new MaskEntry(maskRule(BlockSet.MAX_ENTRIES), rnd.nextBoolean()));
        return new EditMask(entries, rnd.nextInt(4) == 0);
    }

    /** Any rule; a block set has at most {@code maxBlocks} entries. */
    MaskRule maskRule(int maxBlocks) {
        return switch (rnd.nextInt(11)) {
            case 0 -> new MaskRule.Is(blockSet(maxBlocks));
            case 1 -> new MaskRule.OnTopOf(blockSet(maxBlocks));
            case 2 -> new MaskRule.Under(blockSet(maxBlocks));
            case 3 -> new MaskRule.NextTo(blockSet(maxBlocks));
            case 4 -> new MaskRule.Exposed();
            case 5 -> new MaskRule.NotAir();
            case 6 -> new MaskRule.Solid();
            case 7 -> {
                int min = rnd.nextInt(8) == 0 ? Integer.MIN_VALUE : rnd.nextInt(400) - 64;
                yield new MaskRule.Height(min, rnd.nextInt(8) == 0 ? Integer.MAX_VALUE : Math.max(min, rnd.nextInt(400)));
            }
            case 8 -> {
                int min = rnd.nextInt(MaskRule.MAX_SLOPE + 1);
                yield new MaskRule.Slope(min, min + rnd.nextInt(MaskRule.MAX_SLOPE - min + 1));
            }
            case 9 -> new MaskRule.Inside(region());
            default -> new MaskRule.Chance(1 + rnd.nextInt(99), anyLong());
        };
    }

    /** 1 to {@code max} blocks, tags and exact states (mostly a few, so a mask of 16 rules still fits a frame). */
    BlockSet blockSet(int max) {
        int count = 1 + rnd.nextInt(rnd.nextInt(8) == 0 ? max : Math.min(max, 3));
        List<BlockSet.Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(switch (rnd.nextInt(3)) {
                case 0 -> new BlockSet.Block(id());
                case 1 -> new BlockSet.Tag(new NamespacedId(TAGS.get(rnd.nextInt(TAGS.size()))));
                default -> new BlockSet.State(dev.sculptory.core.BlockDescriptor.parse(
                        rnd.nextBoolean() ? "minecraft:oak_stairs[facing=east,half=top]" : "modded:widget[facing=up]"));
            });
        }
        return new BlockSet(entries);
    }

    SourceRef source() {
        return rnd.nextBoolean() ? new SourceRef.Clipboard(uuid()) : new SourceRef.Asset(hash());
    }

    /** Block state text for a block variant: any text within the cap (the server resolves it, not the codec). */
    String blockState() {
        return switch (rnd.nextInt(3)) {
            case 0 -> "minecraft:poppy";
            case 1 -> "minecraft:pink_petals[facing=east,flower_amount=3]";
            default -> "modded:été_" + rnd.nextInt(1000);
        };
    }

    /** 1-64 distinct states (sometimes the full 64, some near the byte cap) with any valid weight, and any pattern. */
    BlockPalette palette() {
        int count = rnd.nextInt(8) == 0 ? BlockPalette.MAX_ENTRIES : 1 + rnd.nextInt(6);
        List<BlockPalette.Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String suffix = Integer.toString(1000 + i);
            String state = rnd.nextInt(10) == 0 ? "m:" + "x".repeat(BlockPalette.MAX_STATE_BYTES - 2 - suffix.length()) + suffix
                    : blockState() + "_" + i;
            entries.add(new BlockPalette.Entry(state, 1 + rnd.nextInt(BlockPalette.MAX_WEIGHT)));
        }
        if (rnd.nextInt(4) == 0) return new BlockPalette(entries);
        return new BlockPalette(entries, new PalettePattern(pick(PalettePattern.Kind.values()),
                MixLayout.Patches.MIN_SIZE + rnd.nextInt(MixLayout.Patches.MAX_SIZE), rnd.nextInt(MixLayout.Gradient.MAX_EDGE + 1),
                rnd.nextInt(MixLayout.Steepness.MAX_EDGE + 1), anyLong()));
    }

    /** A box of at most 1024 × 1024 columns, or 1-40 stamps (sometimes the full 512) near one place. */
    ScatterArea scatterArea() {
        int limit = ScatterArea.MAX_HORIZONTAL - 2000;
        int cx = rnd.nextInt(4) == 0 ? rnd.nextInt(2 * limit) - limit : rnd.nextInt(2000) - 1000;
        int cz = rnd.nextInt(4) == 0 ? rnd.nextInt(2 * limit) - limit : rnd.nextInt(2000) - 1000;
        if (rnd.nextInt(3) == 0) {
            int y = rnd.nextInt(600) - 300;
            BlockPos min = new BlockPos(cx, y, cz);
            return new ScatterArea.Region(new Box(min, min.offset(rnd.nextInt(1024), rnd.nextInt(400), rnd.nextInt(1024))));
        }
        int count = rnd.nextInt(10) == 0 ? Codec.MAX_SCATTER_STAMPS : 1 + rnd.nextInt(40);
        List<ScatterArea.Stamp> stamps = new ArrayList<>(count);
        stamps.add(ScatterArea.Stamp.paint(cx, cz, rnd.nextInt(ScatterArea.MAX_RADIUS + 1)));
        for (int i = 1; i < count; i++) {
            stamps.add(new ScatterArea.Stamp(cx + rnd.nextInt(800) - 400, cz + rnd.nextInt(800) - 400,
                    rnd.nextInt(ScatterArea.MAX_RADIUS + 1), rnd.nextInt(4) == 0));
        }
        return new ScatterArea.Stamps(stamps);
    }

    ScatterSettings.Density density() {
        return switch (rnd.nextInt(4)) {
            case 0 -> new ScatterSettings.Density.Fraction(rnd.nextBoolean() ? 0 : 1);
            case 1 -> new ScatterSettings.Density.Count(1 + rnd.nextInt(ScatterSettings.MAX_PLACEMENTS));
            default -> new ScatterSettings.Density.Fraction(rnd.nextDouble());
        };
    }

    ScatterSettings.Fit fit() {
        return new ScatterSettings.Fit(rnd.nextBoolean(), rnd.nextBoolean() ? 0.5 : rnd.nextDouble(), rnd.nextBoolean());
    }

    ScatterSettings.Transforms scatterTransforms() {
        return new ScatterSettings.Transforms(1 + rnd.nextInt(ScatterSettings.Variant.ALL_TURNS), rnd.nextBoolean());
    }

    C2S.ScatterPreview scatterPreview() {
        List<C2S.ScatterPreview.Variant> variants = new ArrayList<>();
        int count = rnd.nextInt(10) == 0 ? ScatterSettings.MAX_VARIANTS : 1 + rnd.nextInt(5);
        for (int i = 0; i < count; i++) {
            ScatterSource source = switch (rnd.nextInt(4)) {
                case 0 -> new ScatterSource.Block(blockState());
                case 1 -> new ScatterSource.Feature(rnd.nextBoolean() ? "minecraft:fancy_oak"
                        : "modded:tree_" + rnd.nextInt(99));
                default -> new ScatterSource.Held(source());
            };
            variants.add(new C2S.ScatterPreview.Variant(source, 1 + rnd.nextInt(ScatterSettings.MAX_WEIGHT)));
        }
        return new C2S.ScatterPreview(anyInt(), scatterArea(), new C2S.ScatterPreview.Settings(anyLong(),
                rnd.nextInt(ScatterSettings.MAX_SPACING + 1), density(), surface(), fit(), columnHeight()), variants,
                scatterTransforms());
    }

    /** Mostly one block tall; sometimes any range. */
    ScatterSettings.ColumnHeight columnHeight() {
        if (rnd.nextInt(3) != 0) return ScatterSettings.ColumnHeight.ONE;
        int min = 1 + rnd.nextInt(ScatterSettings.MAX_COLUMN_HEIGHT);
        return new ScatterSettings.ColumnHeight(min, min + rnd.nextInt(ScatterSettings.MAX_COLUMN_HEIGHT - min + 1));
    }

    /** Placements scattered around one place (sometimes across the whole int range), in random order. */
    List<ScatterPlan.Placement> placements(int count, int variants) {
        List<ScatterPlan.Placement> placements = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BlockPos anchor = rnd.nextInt(20) == 0 ? new BlockPos(anyInt(), anyInt(), anyInt())
                    : new BlockPos(rnd.nextInt(1024) - 512, rnd.nextInt(384) - 64, rnd.nextInt(1024) - 512);
            int height = rnd.nextInt(4) == 0 ? 2 + rnd.nextInt(ScatterSettings.MAX_COLUMN_HEIGHT - 1) : 1;
            placements.add(new ScatterPlan.Placement(anchor, rnd.nextInt(variants), transform(), height));
        }
        return placements;
    }

    OpSpec op() {
        return switch (rnd.nextInt(12)) {
            case 9 -> new OpSpec.Overlay(region(), pattern(), 1 + rnd.nextInt(OpSpec.MAX_LAYER_DEPTH), symmetry());
            case 10 -> new OpSpec.Naturalize(region(), pattern(), 1 + rnd.nextInt(OpSpec.MAX_LAYER_DEPTH), pattern(),
                    rnd.nextInt(OpSpec.MAX_LAYER_DEPTH + 1), pattern(), symmetry());
            case 11 -> new OpSpec.UpdateBlocks(region(), symmetry());
            case 0 -> new OpSpec.Fill(region(), pattern(), mask());
            case 1 -> new OpSpec.Replace(region(), mask(), pattern());
            case 2 -> new OpSpec.Erase(region(), mask());
            case 3 -> new OpSpec.Hollow(region(), 1 + rnd.nextInt(16), pattern());
            case 4 -> new OpSpec.Walls(region(), 1 + rnd.nextInt(16), pattern());
            case 5 -> new OpSpec.Paste(source(), pos(), opTransform(),
                    new PasteOptions(rnd.nextBoolean(), rnd.nextBoolean(), rnd.nextBoolean(), pick(PasteOptions.Into.values())));
            case 6 -> new OpSpec.Move(region(), pos(), opTransform(), pattern(), pick(EntityFilter.values()), Symmetry.NONE,
                    pick(PasteOptions.Into.values()));
            case 7 -> new OpSpec.Stack(region(), 1 + rnd.nextInt(64), anyInt(), anyInt(), 1 + rnd.nextInt(100),
                    pick(EntityFilter.values()), Symmetry.NONE, pick(PasteOptions.Into.values()), rnd.nextBoolean());
            default -> new OpSpec.ScatterCommit(uuid());
        };
    }

    /** A hit coordinate within a cell, the edges and the slack included. */
    float hit() {
        return switch (rnd.nextInt(4)) {
            case 0 -> 0f;
            case 1 -> 1f;
            case 2 -> rnd.nextBoolean() ? -C2S.BuilderPlace.HIT_SLACK : 1 + C2S.BuilderPlace.HIT_SLACK;
            default -> rnd.nextFloat();
        };
    }

    BrushSpec brush() {
        BrushTool tool = pick(BrushTool.values());
        ShapeSpec shape = tool == BrushTool.SHAPE ? shape() : null;
        boolean needsMaterial = tool == BrushTool.PAINT || tool == BrushTool.PALETTE
                || (shape != null && shape.usesMaterial());
        float strength = switch (rnd.nextInt(3)) {
            case 0 -> 0f;
            case 1 -> 1f;
            default -> rnd.nextFloat();
        };
        BrushSpec spec = new BrushSpec(tool, 1 + rnd.nextInt(BrushSpec.MAX_RADIUS), strength, pick(Falloff.values()),
                pick(Shape.values()), needsMaterial || rnd.nextBoolean() ? pattern(tool == BrushTool.PALETTE) : null, surface(),
                rnd.nextInt(BrushSpec.MAX_DEPTH + 1), anyInt(), anyLong(), rnd.nextBoolean() ? clip() : null, symmetry(),
                shape, SculptMode.TERRAIN, null,
                tool == BrushTool.WEATHER ? new WeatherSpec(pick(WeatherSpec.Mode.values())) : null);
        if (!SculptMode.surfaceTool(tool) || rnd.nextBoolean()) return spec;
        return spec.withSurface(tool == BrushTool.FLATTEN ? plane() : null);
    }

    /** A Surface Flatten plane: any facing, targets over the whole range of its axis. */
    SurfacePlane plane() {
        Facing facing = pick(Facing.values());
        int limit = facing.axis() == 1 ? BrushSpec.CLIP_MAX_Y : BrushSpec.CLIP_MAX_HORIZONTAL;
        return new SurfacePlane(facing, rnd.nextInt(2 * limit + 1) - limit);
    }

    /** Any Shape brush data: every kind, facing and mode, heights and hollow thicknesses over their whole ranges. */
    ShapeSpec shape() {
        return new ShapeSpec(pick(ShapeSpec.Kind.values()), ShapeSpec.MIN_HEIGHT + rnd.nextInt(ShapeSpec.MAX_HEIGHT),
                pick(Facing.values()), pick(ShapeSpec.Mode.values()), rnd.nextInt(ShapeSpec.MAX_HOLLOW + 1));
    }

    /** Any symmetry: every mode, centres anywhere in range (a Rotate 4 centre on a block centre or corner). */
    Symmetry symmetry() {
        Symmetry.Mode mode = pick(Symmetry.Mode.values());
        int max = Symmetry.MAX_CENTRE2;
        int x2 = rnd.nextInt(2 * max + 1) - max;
        int z2 = rnd.nextInt(2 * max + 1) - max;
        if (mode == Symmetry.Mode.ROTATE_4 && ((x2 ^ z2) & 1) != 0) z2 += z2 > 0 ? -1 : 1;
        return new Symmetry(mode, x2, z2);
    }

    /** A brush clip box: corners within the spec's range, sometimes at its edges, sometimes one cell. */
    Box clip() {
        int h = BrushSpec.CLIP_MAX_HORIZONTAL, v = BrushSpec.CLIP_MAX_Y;
        return switch (rnd.nextInt(4)) {
            case 0 -> new Box(new BlockPos(-h, -v, -h), new BlockPos(h, v, h));
            case 1 -> Box.of(new BlockPos(rnd.nextInt(2 * h + 1) - h, rnd.nextInt(2 * v + 1) - v, rnd.nextInt(2 * h + 1) - h));
            default -> Box.of(new BlockPos(rnd.nextInt(2 * h + 1) - h, rnd.nextInt(2 * v + 1) - v, rnd.nextInt(2 * h + 1) - h),
                    new BlockPos(rnd.nextInt(2 * h + 1) - h, rnd.nextInt(2 * v + 1) - v, rnd.nextInt(2 * h + 1) - h));
        };
    }

    Dab dab() {
        return new Dab(rnd.nextInt(Integer.MAX_VALUE), anyInt(), anyInt(), anyInt(), rnd.nextInt(256));
    }

    private List<String> strings(int maxCount, int maxBytes) {
        List<String> values = new ArrayList<>();
        int count = rnd.nextInt(maxCount + 1);
        for (int i = 0; i < count; i++) values.add(text(maxBytes));
        return values;
    }

    // ---------------------------------------------------------------- messages

    Message stream() {
        return switch (rnd.nextInt(5)) {
            case 0 -> {
                TreeMap<String, String> meta = new TreeMap<>();
                int count = rnd.nextInt(StreamOpen.MAX_META + 1);
                for (int i = 0; i < count; i++) meta.put(text(Codec.MAX_META_KEY_BYTES), text(Codec.MAX_TEXT_BYTES));
                yield new StreamOpen(anyInt(), pick(StreamKind.values()), nonNegativeLong(), meta);
            }
            case 1 -> {
                byte[] data = new byte[rnd.nextInt(10) == 0 ? rnd.nextInt(20_000) : rnd.nextInt(64)];
                rnd.nextBytes(data);
                yield new StreamChunk(anyInt(), rnd.nextInt(Integer.MAX_VALUE), data);
            }
            case 2 -> new StreamEnd(anyInt(), new Sha256(hash()));
            case 3 -> new StreamAbort(anyInt(), text(Codec.MAX_NAME_BYTES));
            default -> new StreamCredit(anyInt(), nonNegativeLong());
        };
    }

    /** A per-asset access; in a request ({@code request}) some grantees may still lack a UUID. */
    AssetAccess access(boolean request) {
        if (rnd.nextInt(3) == 0) return AssetAccess.EVERYONE;
        int count = rnd.nextInt(10) == 0 ? AssetAccess.MAX_PLAYERS : 1 + rnd.nextInt(4);
        List<AssetAccess.Grantee> players = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = rnd.nextInt(5) == 0 ? "x".repeat(AssetAccess.MAX_NAME_BYTES) : "player" + i;
            players.add(new AssetAccess.Grantee(request && rnd.nextInt(3) == 0 ? null : new UUID(rnd.nextLong(), i), name));
        }
        return AssetAccess.listed(players);
    }

    C2S c2s() {
        return switch (rnd.nextInt(35)) {
            case 33 -> new C2S.SetEditMask(anyInt(), editMask());
            case 34 -> new C2S.Navigate(anyInt(), pick(NavigateMode.values()), pos(), pick(Facing.values()),
                    rnd.nextFloat() * 2 - 1, rnd.nextBoolean() ? -1f : rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2 - 1);
            case 27 -> tinkerBlock();
            case 28 -> new C2S.TinkerEntity(anyInt(), uuid(), entityEdits());
            case 29 -> new C2S.BuilderPowers(rnd.nextInt(BuilderPower.ALL + 1));
            case 30 -> new C2S.BuilderPlace(anyInt(), rnd.nextBoolean(), pos(), pick(Facing.values()), hit(), hit(), hit(),
                    rnd.nextInt(BuilderPower.ALL + 1), symmetry());
            case 31 -> {
                List<BlockPos> cells = new ArrayList<>();
                int count = 1 + rnd.nextInt(C2S.BuilderBreak.MAX_CELLS);
                for (int i = 0; i < count; i++) cells.add(pos());
                yield new C2S.BuilderBreak(anyInt(), anyInt(), cells, rnd.nextInt(BuilderPower.ALL + 1), symmetry(),
                        rnd.nextBoolean(), rnd.nextBoolean());
            }
            case 32 -> new C2S.BuilderDragEnd(anyInt());
            case 0 -> {
                int min = 1 + rnd.nextInt(10);
                yield new C2S.Hello(min, min + rnd.nextInt(10), text(Codec.MAX_MOD_VERSION_BYTES), features());
            }
            case 26 -> {
                Box bounds = box();
                yield new C2S.GeneratedUpload(anyInt(), bounds, 1 + (rnd.nextLong() & Long.MAX_VALUE) % bounds.volume(),
                        1 + nonNegativeLong() % Long.MAX_VALUE);
            }
            case 1 -> new C2S.StrokeBegin(anyInt(), brush());
            case 2 -> {
                List<Dab> dabs = new ArrayList<>();
                int count = 1 + rnd.nextInt(C2S.Dabs.MAX_DABS);
                for (int i = 0; i < count; i++) dabs.add(dab());
                yield new C2S.Dabs(anyInt(), anyInt(), dabs);
            }
            case 3 -> new C2S.StrokeEnd(anyInt());
            case 4 -> new C2S.Resync(box());
            case 5 -> new C2S.RunOp(anyInt(), op(), rnd.nextBoolean(), pick(ConflictPolicy.values()));
            case 6 -> new C2S.CancelJob(uuid());
            case 7 -> new C2S.Undo(anyInt(), pick(ConflictPolicy.values()));
            case 8 -> new C2S.Redo(anyInt(), pick(ConflictPolicy.values()));
            case 9 -> new C2S.Copy(anyInt(), region(), pos(), rnd.nextBoolean(), mask(), pick(EntityFilter.values()));
            case 10 -> new C2S.PreviewRequest(source());
            case 11 -> new C2S.LibraryList(anyInt(), text(Codec.MAX_PATH_BYTES));
            case 12 -> new C2S.LibraryLoad(anyInt(), text(Codec.MAX_PATH_BYTES));
            case 13 -> new C2S.SaveAsset(anyInt(), uuid(), text(Codec.MAX_PATH_BYTES));
            case 14 -> new C2S.ExportClipboard(anyInt(), uuid(), pick(dev.sculptory.core.schem.SchematicFormat.values()));
            case 15 -> new C2S.UploadBegin(anyInt(), text(Codec.MAX_NAME_BYTES), nonNegativeLong());
            case 16 -> scatterPreview();
            case 17 -> new C2S.LibraryMove(anyInt(), rnd.nextBoolean(), text(Codec.MAX_PATH_BYTES), text(Codec.MAX_PATH_BYTES));
            case 18 -> new C2S.LibraryDelete(anyInt(), rnd.nextBoolean(), text(Codec.MAX_PATH_BYTES));
            case 19 -> new C2S.LibraryCreateFolder(anyInt(), text(Codec.MAX_PATH_BYTES));
            case 20 -> new C2S.PaletteSave(anyInt(), text(Codec.MAX_PATH_BYTES), palette());
            case 21 -> new C2S.PaletteLoad(anyInt(), text(Codec.MAX_PATH_BYTES));
            case 22 -> new C2S.HistoryOverwrite(anyInt(), rnd.nextBoolean(), 1 + rnd.nextInt(C2S.HistoryOverwrite.MAX_STEPS));
            case 23 -> {
                Box bounds = box();
                yield new C2S.SelectionUpload(anyInt(), new Sha256(hash()), bounds,
                        1 + (rnd.nextLong() & Long.MAX_VALUE) % bounds.volume(), 1 + nonNegativeLong() % Long.MAX_VALUE);
            }
            case 24 -> new C2S.LibraryAccessGet(anyInt(), text(Codec.MAX_PATH_BYTES));
            case 25 -> new C2S.LibraryAccessSet(anyInt(), text(Codec.MAX_PATH_BYTES), access(true));
            default -> (C2S) stream();
        };
    }

    S2C s2c() {
        return switch (rnd.nextInt(24)) {
            case 22 -> rnd.nextBoolean() ? S2C.EditMaskState.accepted(anyInt())
                    : S2C.EditMaskState.refused(anyInt(), pick(RejectReason.values()), text(Codec.MAX_TEXT_BYTES));
            case 23 -> rnd.nextBoolean() ? S2C.NavigateResult.landed(anyInt(), pos())
                    : S2C.NavigateResult.refused(anyInt(), pick(RejectReason.values()));
            case 20 -> tinkerResult();
            case 0 -> new S2C.Welcome(anyInt(), features(), limits(), new PermissionMask(rnd.nextLong()), anyLong(),
                    text(Codec.MAX_MOD_VERSION_BYTES));
            case 1 -> new S2C.Incompatible(anyInt(), anyInt(), text(Codec.MAX_MOD_VERSION_BYTES));
            case 2 -> new S2C.PermissionsChanged(new PermissionMask(rnd.nextLong()), limits());
            case 3 -> {
                S2C.StrokeStatus.Status status = pick(S2C.StrokeStatus.Status.values());
                RejectReason reason = status == S2C.StrokeStatus.Status.REJECTED ? pick(RejectReason.values()) : null;
                yield new S2C.StrokeStatus(anyInt(), rnd.nextInt(1000) - 1, status, reason, rnd.nextInt(1000) - 1);
            }
            case 4 -> new S2C.JobAccepted(anyInt(), uuid(), anyLong());
            case 5 -> new S2C.JobRejected(anyInt(), pick(RejectReason.values()));
            case 6 -> new S2C.JobProgress(uuid(), anyLong(), anyLong(), pick(Phase.values()));
            case 7 -> new S2C.JobFinished(uuid(), pick(JobOutcome.values()), anyLong(), anyLong(), anyLong(), anyLong());
            case 8 -> new S2C.HistoryState(rnd.nextBoolean(), rnd.nextBoolean(), text(Codec.MAX_NAME_BYTES),
                    text(Codec.MAX_NAME_BYTES), anyLong(), strings(S2C.HistoryState.MAX_LABELS, Codec.MAX_NAME_BYTES),
                    strings(S2C.HistoryState.MAX_LABELS, Codec.MAX_NAME_BYTES));
            case 9 -> new S2C.ClipboardReady(anyInt(), uuid(), pos(), pos(), anyLong(), anyLong(),
                    rnd.nextInt(Integer.MAX_VALUE));
            case 10 -> {
                List<S2C.LibraryListing.Entry> entries = new ArrayList<>();
                int count = rnd.nextInt(40);
                for (int i = 0; i < count; i++) {
                    boolean folder = rnd.nextBoolean();
                    S2C.LibraryListing.Entry.Kind kind = folder ? S2C.LibraryListing.Entry.Kind.FOLDER
                            : rnd.nextBoolean() ? S2C.LibraryListing.Entry.Kind.SCHEMATIC : S2C.LibraryListing.Entry.Kind.PALETTE;
                    entries.add(new S2C.LibraryListing.Entry(text(Codec.MAX_PATH_BYTES), folder, anyLong(),
                            kind == S2C.LibraryListing.Entry.Kind.SCHEMATIC ? hash() : "", kind,
                            !folder && rnd.nextBoolean()));
                }
                yield new S2C.LibraryListing(anyInt(), text(Codec.MAX_PATH_BYTES), entries, rnd.nextBoolean());
            }
            case 11 -> new S2C.AssetSaved(anyInt(), text(Codec.MAX_PATH_BYTES), hash());
            case 12 -> new S2C.UploadGrant(anyInt(), anyInt(), anyLong());
            case 13 -> rnd.nextBoolean()
                    ? new S2C.UploadResult(anyInt(), uuid(), null)
                    : new S2C.UploadResult(anyInt(), null, text(Codec.MAX_TEXT_BYTES));
            case 14 -> {
                TreeMap<String, Integer> counts = new TreeMap<>();
                int count = rnd.nextInt(10);
                for (int i = 0; i < count; i++) counts.put(text(Codec.MAX_NAME_BYTES), anyInt());
                yield new S2C.ScatterPlan(anyInt(), uuid(), rnd.nextInt(Integer.MAX_VALUE), counts, nonNegativeLong(),
                        rnd.nextBoolean() ? box() : null);
            }
            case 15 -> new S2C.Notice(pick(S2C.Notice.Level.values()), text(Codec.MAX_NAME_BYTES),
                    strings(Codec.MAX_NOTICE_ARGS, Codec.MAX_TEXT_BYTES));
            case 16 -> {
                String from = rnd.nextBoolean() ? "" : "x" + text(Codec.MAX_PATH_BYTES - 1);
                String to = from.isEmpty() || rnd.nextBoolean() ? "y" + text(Codec.MAX_PATH_BYTES - 1) : "";
                yield new S2C.LibraryChanged(anyInt(), rnd.nextBoolean(), from, to);
            }
            case 17 -> {
                BlockPalette palette = palette();
                int shown = rnd.nextInt(S2C.PaletteData.MAX_SHOWN_DROPPED + 1);
                List<String> names = new ArrayList<>();
                for (int i = 0; i < shown; i++) names.add(blockState());
                yield new S2C.PaletteData(anyInt(), text(Codec.MAX_PATH_BYTES), palette,
                        shown + rnd.nextInt(BlockPalette.MAX_ENTRIES - shown + 1), names);
            }
            case 18 -> new S2C.SelectionReady(anyInt(), new Sha256(hash()));
            case 19 -> new S2C.LibraryAccess(anyInt(), text(Codec.MAX_PATH_BYTES), access(false));
            default -> (S2C) stream();
        };
    }

    // ---------------------------------------------------------------- Tinker (protocol 5)

    C2S.TinkerBlock tinkerBlock() {
        int expected = handle();
        int target = rnd.nextBoolean() ? expected : handle();
        SignText sign = expected == target || rnd.nextBoolean() ? new SignText(side(), side()) : null;
        return new C2S.TinkerBlock(anyInt(), pos(), expected, target, sign);
    }

    SignText.Side side() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < SignText.LINES; i++) lines.add(text(TinkerCodec.LINE_BYTES));
        return new SignText.Side(lines, SignText.COLORS.get(rnd.nextInt(SignText.COLORS.size())), rnd.nextBoolean());
    }

    List<EntityEdit> entityEdits() {
        int count = rnd.nextInt(8) == 0 ? EntityEdits.MAX_EDITS : rnd.nextInt(5);
        List<EntityEdit> edits = new ArrayList<>(count);
        for (int i = 0; i < count; i++) edits.add(entityEdit());
        return edits;
    }

    EntityEdit entityEdit() {
        return switch (rnd.nextInt(12)) {
            case 0 -> new EntityEdit.Pose(pick(EntityEdit.Part.values()), angle(), angle(), angle());
            case 1 -> new EntityEdit.Toggle(pick(EntityEdit.Flag.values()), rnd.nextBoolean());
            case 2 -> new EntityEdit.Position(rnd.nextGaussian() * 1e6, rnd.nextGaussian() * 300, -rnd.nextDouble());
            case 3 -> new EntityEdit.Yaw(rnd.nextFloat() * 1e4f - 5e3f);
            case 4 -> new EntityEdit.ItemRotation(rnd.nextInt(8));
            case 5 -> new EntityEdit.PaintingVariant(rnd.nextBoolean() ? "minecraft:kebab" : "modded:big_" + rnd.nextInt(99));
            case 6 -> new EntityEdit.Transformation(offsets(3), new float[] {rnd.nextFloat(), rnd.nextFloat(),
                    rnd.nextFloat(), 1 + rnd.nextFloat()}, offsets(3));
            case 7 -> new EntityEdit.BillboardMode(pick(EntityEdit.Billboard.values()));
            case 8 -> rnd.nextBoolean() ? EntityEdit.Brightness.AUTO
                    : new EntityEdit.Brightness(rnd.nextInt(16), rnd.nextInt(16));
            case 9 -> new EntityEdit.DisplayBlock(handle());
            case 10 -> new EntityEdit.DisplayItem(rnd.nextBoolean() ? "minecraft:apple" : "modded:thing_" + rnd.nextInt(99));
            default -> new EntityEdit.DisplayText(text(200) + (rnd.nextBoolean() ? "\n" + text(200) : ""));
        };
    }

    private float angle() {
        return rnd.nextFloat() * 720 - 360;
    }

    private float[] offsets(int n) {
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = rnd.nextFloat() * 2 * EntityEdit.MAX_DISPLAY_OFFSET - EntityEdit.MAX_DISPLAY_OFFSET;
        return out;
    }

    S2C.TinkerResult tinkerResult() {
        if (rnd.nextBoolean()) return S2C.TinkerResult.refused(anyInt(), pick(RejectReason.values()), text(Codec.MAX_TEXT_BYTES));
        byte[] data = new byte[rnd.nextInt(8) == 0 ? S2C.TinkerResult.MAX_DATA_BYTES : rnd.nextInt(200)];
        rnd.nextBytes(data);
        return S2C.TinkerResult.done(anyInt(), data);
    }
}
