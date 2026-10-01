package dev.sculptory.protocol.v2;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.SculptMode;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.ShapeSpec;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.brush.SurfacePlane;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.brush.WeatherSpec;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.MixLayout;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.mask.BlockSet;
import dev.sculptory.core.mask.EditMask;
import dev.sculptory.core.mask.MaskEntry;
import dev.sculptory.core.mask.MaskRule;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire forms of the core value types. Every sealed variant starts with a one-byte tag; tags are stable and
 * append-only, like enum ordinals. Recursive masks are decoded with depth and node budgets, so a hostile tree
 * fails fast instead of recursing deeply.
 */
final class CoreCodec {
    private CoreCodec() {}

    static final int NAMESPACED_ID_BYTES = 256;

    // Pattern tags (append-only)
    static final int PATTERN_SINGLE = 0;
    static final int PATTERN_WEIGHTED = 1;
    static final int PATTERN_WATERLOG = 2;
    static final int PATTERN_DRY = 3;
    /** Protocol 5: a weighted mix laid out in space. */
    static final int PATTERN_ARRANGED = 4;

    // MixLayout kinds (append-only)
    static final int LAYOUT_PATCHES = 0;
    static final int LAYOUT_GRADIENT = 1;
    static final int LAYOUT_STEEPNESS = 2;
    /** Protocol 5: one property set on every cell. */
    static final int PATTERN_SET_PROPERTY = 5;
    /** Longest property name a pattern carries (a {@code BlockDescriptor} token). */
    static final int PROPERTY_NAME_BYTES = 128;
    /** Protocol 5: Keep shape around another pattern. */
    static final int PATTERN_KEEP_SHAPE = 6;
    /** Protocol 5: Better Replace's Whole family, a list of block swaps. */
    static final int PATTERN_REMAP = 7;

    // CellMask tags
    static final int MASK_ANY = 0;
    static final int MASK_STATES = 1;
    static final int MASK_BLOCKS = 2;
    static final int MASK_TAG = 3;
    static final int MASK_AND = 4;
    static final int MASK_OR = 5;
    static final int MASK_NOT = 6;

    // SurfaceMask tags
    static final int SURFACE_ANY = 0;
    static final int SURFACE_BLOCKS = 1;
    static final int SURFACE_ELEVATION = 2;
    static final int SURFACE_SLOPE = 3;
    static final int SURFACE_AND = 4;
    static final int SURFACE_NOT = 5;
    /** Protocol 5: a brush mask in the rule list form. */
    static final int SURFACE_RULES = 6;

    // Mask rule kinds (protocol 5, append-only)
    static final int RULE_IS = 0;
    static final int RULE_ON_TOP_OF = 1;
    static final int RULE_UNDER = 2;
    static final int RULE_NEXT_TO = 3;
    static final int RULE_EXPOSED = 4;
    static final int RULE_NOT_AIR = 5;
    static final int RULE_SOLID = 6;
    static final int RULE_HEIGHT = 7;
    static final int RULE_SLOPE = 8;
    static final int RULE_INSIDE = 9;
    static final int RULE_CHANCE = 10;

    // Block set entry tags (protocol 5, append-only)
    static final int BLOCK_SET_BLOCK = 0;
    static final int BLOCK_SET_TAG = 1;
    static final int BLOCK_SET_STATE = 2;
    /** Longest exact state text a block set carries. */
    static final int BLOCK_SET_STATE_BYTES = 256;

    // SourceRef tags
    static final int SOURCE_CLIPBOARD = 0;
    static final int SOURCE_ASSET = 1;

    // OpSpec tags
    static final int OP_FILL = 0;
    static final int OP_REPLACE = 1;
    static final int OP_ERASE = 2;
    static final int OP_HOLLOW = 3;
    static final int OP_WALLS = 4;
    static final int OP_PASTE = 5;
    static final int OP_MOVE = 6;
    static final int OP_STACK = 7;
    static final int OP_SCATTER_COMMIT = 8;
    // Protocol 5: the selection ops Overlay and Naturalize
    static final int OP_OVERLAY = 9;
    static final int OP_NATURALIZE = 10;
    static final int OP_UPDATE_BLOCKS = 11;

    // Region tags (a Region.Cells has none: cell sets travel as uploads)
    static final int REGION_CUBOID = 0;
    static final int REGION_SHAPE = 1;
    static final int REGION_UPLOADED = 2;

    // ---------------------------------------------------------------- geometry

    static void writePos(WireWriter out, BlockPos pos) throws ProtocolException {
        out.zigzag(pos.x());
        out.zigzag(pos.y());
        out.zigzag(pos.z());
    }

    static BlockPos readPos(WireReader in) throws ProtocolException {
        int x = in.zigzag();
        int y = in.zigzag();
        return new BlockPos(x, y, in.zigzag());
    }

    static void writeBox(WireWriter out, Box box) throws ProtocolException {
        writePos(out, box.min());
        writePos(out, box.max());
    }

    static Box readBox(WireReader in) throws ProtocolException {
        BlockPos min = readPos(in);
        return new Box(min, readPos(in));
    }

    /**
     * {@code u8 tag}: 0 cuboid ({@code box}), 1 shape ({@code box | u8 kind | u8 facing}), 2 uploaded
     * ({@code 32-byte hash | box | varlong cells}). A {@code Region.Cells} is never written: cell sets travel as
     * {@code SelectionUpload}s. An unknown tag, kind or facing, or a cell count outside 1 to the box's volume, is
     * {@code MALFORMED}.
     */
    static void writeRegion(WireWriter out, Region region) throws ProtocolException {
        switch (region) {
            case Region.Cuboid cuboid -> {
                out.u8(REGION_CUBOID);
                writeBox(out, cuboid.box());
            }
            case Region.Shape shape -> {
                out.u8(REGION_SHAPE);
                writeBox(out, shape.box());
                out.u8(shape.kind().ordinal());
                out.u8(shape.facing().ordinal());
            }
            case Region.Uploaded uploaded -> {
                out.u8(REGION_UPLOADED);
                out.raw(uploaded.hash().bytes());
                writeBox(out, uploaded.bounds());
                out.varlong(uploaded.cellCount());
            }
            case Region.Cells cells -> throw new ProtocolException(ProtocolException.Reason.MALFORMED,
                    "A cell set travels as a SelectionUpload and is named by Region.Uploaded, never inline");
        }
    }

    static Region readRegion(WireReader in) throws ProtocolException {
        int tag = in.u8();
        return switch (tag) {
            case REGION_CUBOID -> new Region.Cuboid(readBox(in));
            case REGION_SHAPE -> {
                Box box = readBox(in);
                int kind = in.u8();
                if (kind >= ShapeKind.values().length) throw WireReader.malformed("Unknown shape kind " + kind);
                int facing = in.u8();
                if (facing >= Facing.values().length) throw WireReader.malformed("Unknown facing " + facing);
                yield new Region.Shape(box, ShapeKind.values()[kind], Facing.values()[facing]);
            }
            case REGION_UPLOADED -> {
                Sha256 hash = Sha256.ofBytes(in.raw(32));
                Box bounds = readBox(in);
                // Region.Uploaded refuses a count outside 1 to the box's volume.
                yield new Region.Uploaded(hash, bounds, in.varlong());
            }
            default -> throw WireReader.malformed("Unknown region tag " + tag);
        };
    }

    /** The transform byte's flip upside down bit (protocol 5). */
    static final int TRANSFORM_UPSIDE_DOWN = 0x10;

    /** One byte: bits 0-1 quarter turns, bits 2-3 mirror ordinal, bit 4 upside down (protocol 5); bits 5-7 zero. */
    static void writeTransform(WireWriter out, Transform t) throws ProtocolException {
        out.u8(t.quarterTurnsCw() | (t.mirror().ordinal() << 2) | (t.upsideDown() ? TRANSFORM_UPSIDE_DOWN : 0));
    }

    static Transform readTransform(WireReader in) throws ProtocolException {
        int b = in.u8();
        int mirror = (b >>> 2) & 3;
        if (mirror >= Mirror.values().length || (b & ~(TRANSFORM_UPSIDE_DOWN | 0xF)) != 0) {
            throw WireReader.malformed("Invalid transform byte " + b);
        }
        return new Transform(b & 3, Mirror.values()[mirror], (b & TRANSFORM_UPSIDE_DOWN) != 0);
    }

    static void writeId(WireWriter out, NamespacedId id) throws ProtocolException {
        out.string(id.value(), NAMESPACED_ID_BYTES, "namespaced id");
    }

    static NamespacedId readId(WireReader in) throws ProtocolException {
        return new NamespacedId(in.string(NAMESPACED_ID_BYTES, "namespaced id"));
    }

    // ---------------------------------------------------------------- patterns

    static void writePattern(WireWriter out, StatePalette.Builder palette, Pattern pattern) throws ProtocolException {
        switch (pattern) {
            case Pattern.Single single -> {
                out.u8(PATTERN_SINGLE);
                out.varint(palette.indexOf(single.state()));
            }
            case Pattern.Weighted weighted -> {
                out.u8(PATTERN_WEIGHTED);
                writeWeighted(out, palette, weighted);
            }
            case Pattern.Arranged arranged -> {
                out.u8(PATTERN_ARRANGED);
                writeWeighted(out, palette, arranged.mix());
                writeLayout(out, arranged.layout());
            }
            case Pattern.Waterlog waterlog -> {
                out.u8(PATTERN_WATERLOG);
                out.varint(palette.indexOf(waterlog.fluidSource()));
            }
            case Pattern.Dry dry -> out.u8(PATTERN_DRY);
            case Pattern.SetProperty set -> {
                out.u8(PATTERN_SET_PROPERTY);
                out.varint(palette.indexOf(set.template()));
                out.string(set.property(), PROPERTY_NAME_BYTES, "property name");
            }
            case Pattern.KeepShape keep -> {
                out.u8(PATTERN_KEEP_SHAPE);
                writePattern(out, palette, keep.inner());
            }
            case Pattern.Remap remap -> {
                out.u8(PATTERN_REMAP);
                out.count(remap.swaps().size(), Pattern.Remap.MAX_SWAPS, "remap swaps");
                for (Pattern.BlockSwap swap : remap.swaps()) {
                    writeId(out, swap.from());
                    writeId(out, swap.to());
                }
                out.bool(remap.keepShape());
            }
        }
    }

    /**
     * @throws ProtocolException {@code MALFORMED} for an unknown tag, a bad weighted entry, a bad layout, a Waterlog
     *     whose state is not a fluid source (still water or lava) in the palette's state space, or a property pattern
     *     whose template state lacks the property, a Keep shape around a Keep shape or a remap, or a remap of no swaps,
     *     over {@value Pattern.Remap#MAX_SWAPS} or with a block swapped twice
     */
    static Pattern readPattern(WireReader in, StatePalette.Table palette) throws ProtocolException {
        return readPattern(in, palette, false);
    }

    /** {@code inKeepShape}: a Keep shape's inner pattern, which may not nest another (checked before reading on). */
    private static Pattern readPattern(WireReader in, StatePalette.Table palette, boolean inKeepShape)
            throws ProtocolException {
        int tag = in.u8();
        if (inKeepShape && (tag == PATTERN_KEEP_SHAPE || tag == PATTERN_REMAP)) {
            throw WireReader.malformed("A Keep shape pattern around pattern tag " + tag);
        }
        return switch (tag) {
            case PATTERN_SINGLE -> new Pattern.Single(palette.readHandle(in));
            case PATTERN_WEIGHTED -> readWeighted(in, palette);
            case PATTERN_WATERLOG -> {
                int fluid = palette.readHandle(in);
                // readHandle succeeded, so the palette is non-empty and was read with a state space: states() is non-null.
                if (!Pattern.isFluidSource(palette.states(), fluid)) {
                    throw WireReader.malformed("Waterlog pattern needs a fluid source state, not "
                            + palette.states().format(fluid));
                }
                yield new Pattern.Waterlog(fluid);
            }
            case PATTERN_DRY -> new Pattern.Dry();
            case PATTERN_ARRANGED -> {
                Pattern.Weighted mix = readWeighted(in, palette);
                yield new Pattern.Arranged(mix, readLayout(in));
            }
            case PATTERN_SET_PROPERTY -> {
                int template = palette.readHandle(in);
                String property = in.string(PROPERTY_NAME_BYTES, "property name");
                Pattern.SetProperty set = new Pattern.SetProperty(template, property);
                if (!set.validIn(palette.states())) {
                    throw WireReader.malformed("Property pattern: " + palette.states().format(template)
                            + " has no property " + property);
                }
                yield set;
            }
            case PATTERN_KEEP_SHAPE -> new Pattern.KeepShape(readPattern(in, palette, true));
            case PATTERN_REMAP -> {
                int count = in.count(Pattern.Remap.MAX_SWAPS, "remap swaps");
                List<Pattern.BlockSwap> swaps = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    NamespacedId from = readId(in);
                    swaps.add(new Pattern.BlockSwap(from, readId(in)));
                }
                // Remap refuses no swaps and a block swapped twice (MALFORMED).
                yield new Pattern.Remap(swaps, in.bool());
            }
            default -> throw WireReader.malformed("Unknown pattern tag " + tag);
        };
    }

    /** A weighted mix: {@code varint count (1-64) | count × (varint palette index, varint weight) | zigzag64 seed}. */
    private static void writeWeighted(WireWriter out, StatePalette.Builder palette, Pattern.Weighted weighted)
            throws ProtocolException {
        out.count(weighted.size(), Pattern.Weighted.MAX_ENTRIES, "weighted pattern");
        for (int i = 0; i < weighted.size(); i++) {
            out.varint(palette.indexOf(weighted.state(i)));
            out.varint(weighted.weight(i));
        }
        out.zigzagLong(weighted.seed());
    }

    private static Pattern.Weighted readWeighted(WireReader in, StatePalette.Table palette) throws ProtocolException {
        int count = in.count(Pattern.Weighted.MAX_ENTRIES, "weighted pattern");
        int[] states = new int[count];
        int[] weights = new int[count];
        for (int i = 0; i < count; i++) {
            states[i] = palette.readHandle(in);
            weights[i] = in.varint();
        }
        return new Pattern.Weighted(states, weights, in.zigzagLong());
    }

    /**
     * A mix's layout (protocol 5): {@code u8 kind}, then for Patches
     * {@code varint size}, for a Gradient {@code pos from | pos to | varint edge}, for Steepness {@code varint edge}. An
     * unknown kind is MALFORMED, and so is what the layouts refuse: a size outside 1-32, an edge outside 0-32 (0-45
     * degrees for Steepness), a line whose ends are one block or beyond ±2²⁵ (x, z) or ±4096 (y).
     */
    static void writeLayout(WireWriter out, MixLayout layout) throws ProtocolException {
        switch (layout) {
            case MixLayout.Patches patches -> {
                out.u8(LAYOUT_PATCHES);
                out.varint(patches.size());
            }
            case MixLayout.Gradient gradient -> {
                out.u8(LAYOUT_GRADIENT);
                writePos(out, gradient.from());
                writePos(out, gradient.to());
                out.varint(gradient.edge());
            }
            case MixLayout.Steepness steepness -> {
                out.u8(LAYOUT_STEEPNESS);
                out.varint(steepness.edge());
            }
        }
    }

    static MixLayout readLayout(WireReader in) throws ProtocolException {
        int kind = in.u8();
        return switch (kind) {
            case LAYOUT_PATCHES -> new MixLayout.Patches(in.varint());
            case LAYOUT_GRADIENT -> {
                BlockPos from = readPos(in);
                BlockPos to = readPos(in);
                yield new MixLayout.Gradient(from, to, in.varint());
            }
            case LAYOUT_STEEPNESS -> new MixLayout.Steepness(in.varint());
            default -> throw WireReader.malformed("Unknown mix layout " + kind);
        };
    }

    /**
     * {@code pattern}, refused (MALFORMED) when it lays a mix out by steepness: only Palette Paint's brush measures the
     * ground's steepness, so an op's pattern or another brush's material may not.
     */
    static Pattern withoutSteepness(Pattern pattern, String where) throws ProtocolException {
        if (Pattern.needsSteepness(pattern)) throw WireReader.malformed("A Steepness pattern in " + where);
        return pattern;
    }

    // ---------------------------------------------------------------- cell masks

    static void writeMask(WireWriter out, StatePalette.Builder palette, CellMask mask) throws ProtocolException {
        switch (mask) {
            case CellMask.Any any -> out.u8(MASK_ANY);
            case CellMask.States states -> {
                out.u8(MASK_STATES);
                int[] handles = states.handles();
                out.count(handles.length, CellMask.MAX_LIST, "state mask");
                for (int handle : handles) out.varint(palette.indexOf(handle));
            }
            case CellMask.Blocks blocks -> {
                out.u8(MASK_BLOCKS);
                out.count(blocks.blocks().size(), CellMask.MAX_LIST, "block mask");
                for (NamespacedId id : blocks.blocks()) writeId(out, id);
            }
            case CellMask.Tag tag -> {
                out.u8(MASK_TAG);
                writeId(out, tag.tag());
            }
            case CellMask.And and -> {
                out.u8(MASK_AND);
                writeMaskChildren(out, palette, and.masks());
            }
            case CellMask.Or or -> {
                out.u8(MASK_OR);
                writeMaskChildren(out, palette, or.masks());
            }
            case CellMask.Not not -> {
                out.u8(MASK_NOT);
                writeMask(out, palette, not.mask());
            }
        }
    }

    private static void writeMaskChildren(WireWriter out, StatePalette.Builder palette, List<CellMask> children)
            throws ProtocolException {
        out.count(children.size(), CellMask.MAX_NODES, "mask children");
        for (CellMask child : children) writeMask(out, palette, child);
    }

    static CellMask readMask(WireReader in, StatePalette.Table palette) throws ProtocolException {
        return readMask(in, palette, 1, new int[1]);
    }

    private static CellMask readMask(WireReader in, StatePalette.Table palette, int depth, int[] nodes)
            throws ProtocolException {
        if (depth > CellMask.MAX_DEPTH) throw WireReader.malformed("Cell mask deeper than " + CellMask.MAX_DEPTH);
        if (++nodes[0] > CellMask.MAX_NODES) throw WireReader.malformed("Cell mask over " + CellMask.MAX_NODES + " nodes");
        int tag = in.u8();
        return switch (tag) {
            case MASK_ANY -> CellMask.ANY;
            case MASK_STATES -> {
                int count = in.count(CellMask.MAX_LIST, "state mask");
                int[] handles = new int[count];
                for (int i = 0; i < count; i++) handles[i] = palette.readHandle(in);
                yield new CellMask.States(handles);
            }
            case MASK_BLOCKS -> {
                int count = in.count(CellMask.MAX_LIST, "block mask");
                List<NamespacedId> ids = new ArrayList<>(count);
                for (int i = 0; i < count; i++) ids.add(readId(in));
                yield new CellMask.Blocks(ids);
            }
            case MASK_TAG -> new CellMask.Tag(readId(in));
            case MASK_AND -> new CellMask.And(readMaskChildren(in, palette, depth, nodes));
            case MASK_OR -> new CellMask.Or(readMaskChildren(in, palette, depth, nodes));
            case MASK_NOT -> new CellMask.Not(readMask(in, palette, depth + 1, nodes));
            default -> throw WireReader.malformed("Unknown cell mask tag " + tag);
        };
    }

    private static List<CellMask> readMaskChildren(WireReader in, StatePalette.Table palette, int depth, int[] nodes)
            throws ProtocolException {
        int count = in.count(CellMask.MAX_NODES, "mask children");
        List<CellMask> children = new ArrayList<>(count);
        for (int i = 0; i < count; i++) children.add(readMask(in, palette, depth + 1, nodes));
        return children;
    }

    // ---------------------------------------------------------------- surface masks

    static void writeSurface(WireWriter out, StatePalette.Builder palette, SurfaceMask mask) throws ProtocolException {
        switch (mask) {
            case SurfaceMask.Any any -> out.u8(SURFACE_ANY);
            case SurfaceMask.SurfaceBlocks blocks -> {
                out.u8(SURFACE_BLOCKS);
                writeMask(out, palette, blocks.mask());
            }
            case SurfaceMask.Elevation elevation -> {
                out.u8(SURFACE_ELEVATION);
                out.zigzag(elevation.minY());
                out.zigzag(elevation.maxY());
            }
            case SurfaceMask.Slope slope -> {
                out.u8(SURFACE_SLOPE);
                out.zigzag(slope.minStep());
                out.zigzag(slope.maxStep());
            }
            case SurfaceMask.And and -> {
                out.u8(SURFACE_AND);
                out.count(and.masks().size(), SurfaceMask.MAX_NODES, "surface mask children");
                for (SurfaceMask child : and.masks()) writeSurface(out, palette, child);
            }
            case SurfaceMask.Not not -> {
                out.u8(SURFACE_NOT);
                writeSurface(out, palette, not.mask());
            }
            case SurfaceMask.Rules rules -> {
                out.u8(SURFACE_RULES);
                writeEditMask(out, rules.rules());
            }
        }
    }

    static SurfaceMask readSurface(WireReader in, StatePalette.Table palette) throws ProtocolException {
        return readSurface(in, palette, 1, new int[1]);
    }

    private static SurfaceMask readSurface(WireReader in, StatePalette.Table palette, int depth, int[] nodes)
            throws ProtocolException {
        if (depth > SurfaceMask.MAX_DEPTH) throw WireReader.malformed("Surface mask deeper than " + SurfaceMask.MAX_DEPTH);
        if (++nodes[0] > SurfaceMask.MAX_NODES) {
            throw WireReader.malformed("Surface mask over " + SurfaceMask.MAX_NODES + " nodes");
        }
        int tag = in.u8();
        return switch (tag) {
            case SURFACE_ANY -> SurfaceMask.ANY;
            // The nested cell mask spends the same node budget as the surface tree around it.
            case SURFACE_BLOCKS -> new SurfaceMask.SurfaceBlocks(readMask(in, palette, 1, nodes));
            case SURFACE_ELEVATION -> {
                int min = in.zigzag();
                yield new SurfaceMask.Elevation(min, in.zigzag());
            }
            case SURFACE_SLOPE -> {
                int min = in.zigzag();
                yield new SurfaceMask.Slope(min, in.zigzag());
            }
            case SURFACE_AND -> {
                int count = in.count(SurfaceMask.MAX_NODES, "surface mask children");
                List<SurfaceMask> children = new ArrayList<>(count);
                for (int i = 0; i < count; i++) children.add(readSurface(in, palette, depth + 1, nodes));
                yield new SurfaceMask.And(children);
            }
            case SURFACE_NOT -> new SurfaceMask.Not(readSurface(in, palette, depth + 1, nodes));
            case SURFACE_RULES -> new SurfaceMask.Rules(readEditMask(in));
            default -> throw WireReader.malformed("Unknown surface mask tag " + tag);
        };
    }

    // ---------------------------------------------------------------- edit masks (protocol 5)

    /**
     * The global mask: {@code varint n (0-16) | bool invertAll}, then per entry
     * {@code u8 kind | bool not | body}. Kinds: 0 Is, 1 OnTopOf, 2 Under, 3 NextTo (each a block set), 4 Exposed,
     * 5 NotAir, 6 Solid (no body), 7 Height ({@code zigzag min, zigzag max}), 8 Slope ({@code varint min, varint max}),
     * 9 Inside (a region, as {@link #writeRegion}), 10 Chance ({@code varint percent, zigzag64 seed}). An unknown kind
     * and the values the rules refuse (an inverted range, a slope over 16, a chance outside 1-99) are MALFORMED.
     */
    static void writeEditMask(WireWriter out, EditMask mask) throws ProtocolException {
        out.count(mask.entries().size(), EditMask.MAX_ENTRIES, "mask rules");
        out.bool(mask.invertAll());
        for (MaskEntry entry : mask.entries()) {
            switch (entry.rule()) {
                case MaskRule.Is is -> {
                    ruleHeader(out, RULE_IS, entry);
                    writeBlockSet(out, is.blocks());
                }
                case MaskRule.OnTopOf onTop -> {
                    ruleHeader(out, RULE_ON_TOP_OF, entry);
                    writeBlockSet(out, onTop.blocks());
                }
                case MaskRule.Under under -> {
                    ruleHeader(out, RULE_UNDER, entry);
                    writeBlockSet(out, under.blocks());
                }
                case MaskRule.NextTo nextTo -> {
                    ruleHeader(out, RULE_NEXT_TO, entry);
                    writeBlockSet(out, nextTo.blocks());
                }
                case MaskRule.Exposed exposed -> ruleHeader(out, RULE_EXPOSED, entry);
                case MaskRule.NotAir notAir -> ruleHeader(out, RULE_NOT_AIR, entry);
                case MaskRule.Solid solid -> ruleHeader(out, RULE_SOLID, entry);
                case MaskRule.Height height -> {
                    ruleHeader(out, RULE_HEIGHT, entry);
                    out.zigzag(height.minY());
                    out.zigzag(height.maxY());
                }
                case MaskRule.Slope slope -> {
                    ruleHeader(out, RULE_SLOPE, entry);
                    out.varint(slope.minStep());
                    out.varint(slope.maxStep());
                }
                case MaskRule.Inside inside -> {
                    ruleHeader(out, RULE_INSIDE, entry);
                    writeRegion(out, inside.region());
                }
                case MaskRule.Chance chance -> {
                    ruleHeader(out, RULE_CHANCE, entry);
                    out.varint(chance.percent());
                    out.zigzagLong(chance.seed());
                }
            }
        }
    }

    private static void ruleHeader(WireWriter out, int kind, MaskEntry entry) throws ProtocolException {
        out.u8(kind);
        out.bool(entry.not());
    }

    static EditMask readEditMask(WireReader in) throws ProtocolException {
        int count = in.count(EditMask.MAX_ENTRIES, "mask rules");
        boolean invertAll = in.bool();
        List<MaskEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int kind = in.u8();
            boolean not = in.bool();
            MaskRule rule = switch (kind) {
                case RULE_IS -> new MaskRule.Is(readBlockSet(in));
                case RULE_ON_TOP_OF -> new MaskRule.OnTopOf(readBlockSet(in));
                case RULE_UNDER -> new MaskRule.Under(readBlockSet(in));
                case RULE_NEXT_TO -> new MaskRule.NextTo(readBlockSet(in));
                case RULE_EXPOSED -> new MaskRule.Exposed();
                case RULE_NOT_AIR -> new MaskRule.NotAir();
                case RULE_SOLID -> new MaskRule.Solid();
                case RULE_HEIGHT -> {
                    int min = in.zigzag();
                    yield new MaskRule.Height(min, in.zigzag());
                }
                case RULE_SLOPE -> {
                    int min = in.varint();
                    yield new MaskRule.Slope(min, in.varint());
                }
                case RULE_INSIDE -> new MaskRule.Inside(readRegion(in));
                case RULE_CHANCE -> {
                    int percent = in.varint();
                    yield new MaskRule.Chance(percent, in.zigzagLong());
                }
                default -> throw WireReader.malformed("Unknown mask rule kind " + kind);
            };
            entries.add(new MaskEntry(rule, not));
        }
        return new EditMask(entries, invertAll);
    }

    /**
     * A block set: {@code varint n (1-16)}, then per entry {@code u8 tag}: 0 a block ({@code namespaced id}), 1 a tag
     * ({@code namespaced id}), 2 an exact state (its text, at most {@value #BLOCK_SET_STATE_BYTES} UTF-8 bytes, with
     * properties). An empty set, an unknown tag or a state text that does not parse is MALFORMED.
     */
    static void writeBlockSet(WireWriter out, BlockSet set) throws ProtocolException {
        out.count(set.entries().size(), BlockSet.MAX_ENTRIES, "block set");
        for (BlockSet.Entry entry : set.entries()) {
            switch (entry) {
                case BlockSet.Block block -> {
                    out.u8(BLOCK_SET_BLOCK);
                    writeId(out, block.id());
                }
                case BlockSet.Tag tag -> {
                    out.u8(BLOCK_SET_TAG);
                    writeId(out, tag.tag());
                }
                case BlockSet.State state -> {
                    out.u8(BLOCK_SET_STATE);
                    out.string(state.state().format(), BLOCK_SET_STATE_BYTES, "block set state");
                }
            }
        }
    }

    static BlockSet readBlockSet(WireReader in) throws ProtocolException {
        int count = in.count(BlockSet.MAX_ENTRIES, "block set");
        List<BlockSet.Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int tag = in.u8();
            entries.add(switch (tag) {
                case BLOCK_SET_BLOCK -> new BlockSet.Block(readId(in));
                case BLOCK_SET_TAG -> new BlockSet.Tag(readId(in));
                // BlockDescriptor and BlockSet.State refuse malformed text and a state without properties (MALFORMED).
                case BLOCK_SET_STATE -> new BlockSet.State(BlockDescriptor.parse(
                        in.string(BLOCK_SET_STATE_BYTES, "block set state")));
                default -> throw WireReader.malformed("Unknown block set entry tag " + tag);
            });
        }
        // BlockSet refuses an empty set (MALFORMED).
        return new BlockSet(entries);
    }

    // ---------------------------------------------------------------- sources and ops

    static void writeSource(WireWriter out, SourceRef source) throws ProtocolException {
        switch (source) {
            case SourceRef.Clipboard clipboard -> {
                out.u8(SOURCE_CLIPBOARD);
                out.uuid(clipboard.id());
            }
            case SourceRef.Asset asset -> {
                out.u8(SOURCE_ASSET);
                out.raw(new Sha256(asset.contentHash()).bytes());
            }
        }
    }

    static SourceRef readSource(WireReader in) throws ProtocolException {
        return readSource(in, in.u8());
    }

    /** A source whose tag was read already (a scatter variant reads its own tags first). */
    static SourceRef readSource(WireReader in, int tag) throws ProtocolException {
        return switch (tag) {
            case SOURCE_CLIPBOARD -> new SourceRef.Clipboard(in.uuid());
            case SOURCE_ASSET -> new SourceRef.Asset(Sha256.ofBytes(in.raw(32)).hex());
            default -> throw WireReader.malformed("Unknown source tag " + tag);
        };
    }

    /**
     * One byte (bit 0 include air, bit 1 physics, bit 2 entities; other bits must be 0), then the {@code into} filter
     * as a varint ordinal (an unknown value is MALFORMED).
     */
    static void writePasteOptions(WireWriter out, PasteOptions options) throws ProtocolException {
        out.u8((options.includeAir() ? 1 : 0) | (options.physics() ? 2 : 0) | (options.entities() ? 4 : 0));
        out.enumValue(options.into());
    }

    static PasteOptions readPasteOptions(WireReader in) throws ProtocolException {
        int b = in.u8();
        if ((b & ~7) != 0) throw WireReader.malformed("Invalid paste options " + b);
        PasteOptions.Into into = readInto(in);
        return new PasteOptions((b & 1) != 0, (b & 2) != 0, (b & 4) != 0, into);
    }

    static PasteOptions.Into readInto(WireReader in) throws ProtocolException {
        return in.enumOf(PasteOptions.Into.values(), "paste into");
    }

    static EntityFilter readEntityFilter(WireReader in) throws ProtocolException {
        return in.enumOf(EntityFilter.values(), "entity filter");
    }

    static void writeOp(WireWriter out, StatePalette.Builder palette, OpSpec op) throws ProtocolException {
        switch (op) {
            case OpSpec.Fill fill -> {
                out.u8(OP_FILL);
                writeRegion(out, fill.region());
                writePattern(out, palette, fill.pattern());
                writeMask(out, palette, fill.mask());
                writeSymmetry(out, fill.symmetry());
            }
            case OpSpec.Replace replace -> {
                out.u8(OP_REPLACE);
                writeRegion(out, replace.region());
                writeMask(out, palette, replace.from());
                writePattern(out, palette, replace.to());
                writeSymmetry(out, replace.symmetry());
            }
            case OpSpec.Erase erase -> {
                out.u8(OP_ERASE);
                writeRegion(out, erase.region());
                writeMask(out, palette, erase.mask());
                writeSymmetry(out, erase.symmetry());
            }
            case OpSpec.Hollow hollow -> {
                out.u8(OP_HOLLOW);
                writeRegion(out, hollow.region());
                out.zigzag(hollow.thickness());
                writePattern(out, palette, hollow.inside());
                writeSymmetry(out, hollow.symmetry());
            }
            case OpSpec.Walls walls -> {
                out.u8(OP_WALLS);
                writeRegion(out, walls.region());
                out.zigzag(walls.thickness());
                writePattern(out, palette, walls.pattern());
                writeSymmetry(out, walls.symmetry());
            }
            case OpSpec.Paste paste -> {
                out.u8(OP_PASTE);
                writeSource(out, paste.src());
                writePos(out, paste.origin());
                writeTransform(out, paste.t());
                writePasteOptions(out, paste.o());
                writeSymmetry(out, paste.symmetry());
            }
            case OpSpec.Move move -> {
                out.u8(OP_MOVE);
                writeRegion(out, move.region());
                writePos(out, move.offset());
                writeTransform(out, move.t());
                writePattern(out, palette, move.leave());
                out.enumValue(move.entities());
                writeSymmetry(out, move.symmetry());
                out.enumValue(move.into());
            }
            case OpSpec.Stack stack -> {
                out.u8(OP_STACK);
                writeRegion(out, stack.region());
                out.zigzag(stack.dx());
                out.zigzag(stack.dy());
                out.zigzag(stack.dz());
                out.zigzag(stack.count());
                out.enumValue(stack.entities());
                writeSymmetry(out, stack.symmetry());
                out.enumValue(stack.into());
                out.bool(stack.upsideDown());
            }
            case OpSpec.ScatterCommit commit -> {
                out.u8(OP_SCATTER_COMMIT);
                out.uuid(commit.planId());
            }
            case OpSpec.Overlay overlay -> {
                out.u8(OP_OVERLAY);
                writeRegion(out, overlay.region());
                writePattern(out, palette, overlay.pattern());
                out.varint(overlay.depth());
                writeSymmetry(out, overlay.symmetry());
            }
            case OpSpec.Naturalize naturalize -> {
                out.u8(OP_NATURALIZE);
                writeRegion(out, naturalize.region());
                writePattern(out, palette, naturalize.top());
                out.varint(naturalize.topDepth());
                writePattern(out, palette, naturalize.middle());
                out.varint(naturalize.middleDepth());
                writePattern(out, palette, naturalize.bottom());
                writeSymmetry(out, naturalize.symmetry());
            }
            case OpSpec.UpdateBlocks update -> {
                out.u8(OP_UPDATE_BLOCKS);
                writeRegion(out, update.region());
                writeSymmetry(out, update.symmetry());
            }
        }
    }

    static OpSpec readOp(WireReader in, StatePalette.Table palette) throws ProtocolException {
        int tag = in.u8();
        return switch (tag) {
            case OP_FILL -> {
                Region region = readRegion(in);
                Pattern pattern = withoutSteepness(readPattern(in, palette), "an op");
                CellMask mask = readMask(in, palette);
                yield new OpSpec.Fill(region, pattern, mask, readSymmetry(in));
            }
            case OP_REPLACE -> {
                Region region = readRegion(in);
                CellMask from = readMask(in, palette);
                Pattern to = withoutSteepness(readPattern(in, palette), "an op");
                yield new OpSpec.Replace(region, from, to, readSymmetry(in));
            }
            case OP_ERASE -> {
                Region region = readRegion(in);
                CellMask mask = readMask(in, palette);
                yield new OpSpec.Erase(region, mask, readSymmetry(in));
            }
            case OP_HOLLOW -> {
                Region region = readRegion(in);
                int thickness = in.zigzag();
                Pattern inside = withoutSteepness(readPattern(in, palette), "an op");
                yield new OpSpec.Hollow(region, thickness, inside, readSymmetry(in));
            }
            case OP_WALLS -> {
                Region region = readRegion(in);
                int thickness = in.zigzag();
                Pattern pattern = withoutSteepness(readPattern(in, palette), "an op");
                yield new OpSpec.Walls(region, thickness, pattern, readSymmetry(in));
            }
            case OP_PASTE -> {
                SourceRef source = readSource(in);
                BlockPos origin = readPos(in);
                Transform transform = readTransform(in);
                PasteOptions options = readPasteOptions(in);
                yield new OpSpec.Paste(source, origin, transform, options, readSymmetry(in));
            }
            case OP_MOVE -> {
                Region region = readRegion(in);
                BlockPos offset = readPos(in);
                Transform transform = readTransform(in);
                Pattern leave = withoutSteepness(readPattern(in, palette), "an op");
                EntityFilter entities = readEntityFilter(in);
                Symmetry symmetry = readSymmetry(in);
                yield new OpSpec.Move(region, offset, transform, leave, entities, symmetry, readInto(in));
            }
            case OP_STACK -> {
                Region region = readRegion(in);
                int dx = in.zigzag();
                int dy = in.zigzag();
                int dz = in.zigzag();
                int count = in.zigzag();
                EntityFilter entities = readEntityFilter(in);
                Symmetry symmetry = readSymmetry(in);
                PasteOptions.Into into = readInto(in);
                yield new OpSpec.Stack(region, dx, dy, dz, count, entities, symmetry, into, in.bool());
            }
            case OP_SCATTER_COMMIT -> new OpSpec.ScatterCommit(in.uuid());
            // Protocol 5: region | pattern | varint depth | symmetry. A depth outside 1-16 is MALFORMED.
            case OP_OVERLAY -> {
                Region region = readRegion(in);
                Pattern pattern = withoutSteepness(readPattern(in, palette), "an op");
                int depth = in.varint();
                yield new OpSpec.Overlay(region, pattern, depth, readSymmetry(in));
            }
            // Protocol 5: region | top | varint topDepth | middle | varint middleDepth | bottom | symmetry.
            case OP_NATURALIZE -> {
                Region region = readRegion(in);
                Pattern top = withoutSteepness(readPattern(in, palette), "an op");
                int topDepth = in.varint();
                Pattern middle = withoutSteepness(readPattern(in, palette), "an op");
                int middleDepth = in.varint();
                Pattern bottom = withoutSteepness(readPattern(in, palette), "an op");
                yield new OpSpec.Naturalize(region, top, topDepth, middle, middleDepth, bottom, readSymmetry(in));
            }
            // Protocol 5: region | symmetry.
            case OP_UPDATE_BLOCKS -> {
                Region region = readRegion(in);
                yield new OpSpec.UpdateBlocks(region, readSymmetry(in));
            }
            default -> throw WireReader.malformed("Unknown op tag " + tag);
        };
    }

    // ---------------------------------------------------------------- brushes

    static void writeBrush(WireWriter out, StatePalette.Builder palette, BrushSpec spec) throws ProtocolException {
        out.enumValue(spec.tool());
        out.zigzag(spec.radius());
        out.f32(spec.strength());
        out.enumValue(spec.falloff());
        out.enumValue(spec.shape());
        out.bool(spec.material() != null);
        if (spec.material() != null) writePattern(out, palette, spec.material());
        writeSurface(out, palette, spec.mask());
        out.zigzag(spec.depth());
        out.zigzag(spec.flattenY());
        out.enumValue(spec.mode());
        if (spec.plane() != null) writePlane(out, spec.plane());
        out.zigzagLong(spec.seed());
        out.bool(spec.clip() != null);
        if (spec.clip() != null) writeBox(out, spec.clip());
        writeSymmetry(out, spec.symmetry());
        if (spec.shapeSpec() != null) writeShape(out, spec.shapeSpec());
        // Protocol 5: the Weather brush's mode, a varint ordinal after the (absent) shape spec.
        if (spec.weather() != null) out.enumValue(spec.weather().mode());
    }

    static BrushSpec readBrush(WireReader in, StatePalette.Table palette) throws ProtocolException {
        BrushTool tool = in.enumOf(BrushTool.values(), "brush tool");
        int radius = in.zigzag();
        float strength = in.f32();
        Falloff falloff = in.enumOf(Falloff.values(), "falloff");
        Shape shape = in.enumOf(Shape.values(), "brush shape");
        // BrushSpec refuses a Steepness material on any brush but Palette Paint (MALFORMED).
        Pattern material = in.bool() ? readPattern(in, palette) : null;
        SurfaceMask mask = readSurface(in, palette);
        int depth = in.zigzag();
        int flattenY = in.zigzag();
        SculptMode mode = in.enumOf(SculptMode.values(), "sculpt mode");
        // Only a Surface Flatten carries a plane; BrushSpec refuses a Surface mode on another tool (MALFORMED).
        SurfacePlane plane = mode == SculptMode.SURFACE && tool == BrushTool.FLATTEN ? readPlane(in) : null;
        long seed = in.zigzagLong();
        // An inverted or out-of-range clip box is refused by Box or BrushSpec (MALFORMED).
        Box clip = in.bool() ? readBox(in) : null;
        Symmetry symmetry = readSymmetry(in);
        // A Shape brush without a material that needs one is refused by BrushSpec (MALFORMED).
        ShapeSpec solid = tool == BrushTool.SHAPE ? readShape(in) : null;
        WeatherSpec weather = tool == BrushTool.WEATHER
                ? new WeatherSpec(in.enumOf(WeatherSpec.Mode.values(), "weather mode"))
                : null;
        return new BrushSpec(tool, radius, strength, falloff, shape, material, mask, depth, flattenY, seed, clip, symmetry,
                solid, mode, plane, weather);
    }

    /**
     * The Shape brush (appended to a {@code SHAPE} brush in the unreleased v4, after the symmetry):
     * {@code varint kind | varint height | varint facing | varint mode | varint hollow}. An unknown kind, facing or mode
     * is MALFORMED, and so is what {@link ShapeSpec} refuses: a height outside 1-{@value ShapeSpec#MAX_HEIGHT} or a
     * hollow thickness outside 0-{@value ShapeSpec#MAX_HOLLOW}.
     */
    static void writeShape(WireWriter out, ShapeSpec shape) throws ProtocolException {
        out.enumValue(shape.kind());
        out.varint(shape.height());
        out.enumValue(shape.facing());
        out.enumValue(shape.mode());
        out.varint(shape.hollow());
    }

    static ShapeSpec readShape(WireReader in) throws ProtocolException {
        ShapeSpec.Kind kind = in.enumOf(ShapeSpec.Kind.values(), "shape kind");
        int height = in.varint();
        Facing facing = in.enumOf(Facing.values(), "shape facing");
        ShapeSpec.Mode mode = in.enumOf(ShapeSpec.Mode.values(), "shape mode");
        return new ShapeSpec(kind, height, facing, mode, in.varint());
    }

    /**
     * Surface-mode Flatten's plane (in the unreleased v4, after the brush's {@code varint sculpt mode}, which follows
     * flattenY; only a Flatten in the Surface mode carries one): {@code varint facing | zigzag target}. An unknown
     * facing is MALFORMED, and so is a target {@link SurfacePlane} refuses (beyond ±2²⁵ on x or z, ±4096 on y).
     */
    static void writePlane(WireWriter out, SurfacePlane plane) throws ProtocolException {
        out.enumValue(plane.facing());
        out.zigzag(plane.target());
    }

    static SurfacePlane readPlane(WireReader in) throws ProtocolException {
        Facing facing = in.enumOf(Facing.values(), "plane facing");
        return new SurfacePlane(facing, in.zigzag());
    }

    /**
     * Brush symmetry (appended to the brush in the unreleased v4): {@code varint mode}, then for any mode but
     * {@code OFF} the centre in half blocks, {@code zigzag x2, zigzag z2}. An unknown mode is MALFORMED, and so is
     * what {@link Symmetry} refuses: a centre beyond ±{@value Symmetry#MAX_CENTRE2} half blocks, or a Rotate 4 centre
     * that is neither a block centre nor a block corner.
     */
    static void writeSymmetry(WireWriter out, Symmetry symmetry) throws ProtocolException {
        out.enumValue(symmetry.mode());
        if (symmetry.isOff()) return;
        out.zigzag(symmetry.x2());
        out.zigzag(symmetry.z2());
    }

    static Symmetry readSymmetry(WireReader in) throws ProtocolException {
        Symmetry.Mode mode = in.enumOf(Symmetry.Mode.values(), "symmetry mode");
        if (mode == Symmetry.Mode.OFF) return Symmetry.NONE;
        int x2 = in.zigzag();
        int z2 = in.zigzag();
        return new Symmetry(mode, x2, z2);
    }

    static void writeDab(WireWriter out, Dab dab) throws ProtocolException {
        out.varint(dab.index());
        out.zigzag(dab.x16());
        out.zigzag(dab.y16());
        out.zigzag(dab.z16());
        out.u8(dab.pressure());
    }

    static Dab readDab(WireReader in) throws ProtocolException {
        int index = in.varint();
        int x16 = in.zigzag();
        int y16 = in.zigzag();
        int z16 = in.zigzag();
        return new Dab(index, x16, y16, z16, in.u8());
    }
}
