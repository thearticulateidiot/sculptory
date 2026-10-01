package dev.sculptory.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.SculptMode;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.brush.WeatherSpec;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.mask.BlockSet;
import dev.sculptory.core.mask.EditMask;
import dev.sculptory.core.mask.MaskEntry;
import dev.sculptory.core.mask.MaskRule;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.scatter.ScatterArea;
import dev.sculptory.core.scatter.ScatterSettings;
import dev.sculptory.core.scatter.ScatterSource;
import dev.sculptory.core.testing.FakeStateSpace;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * The wire contracts of the WorldEdit-inspired batch (protocol 5): the
 * global mask, Better Replace's patterns, Overlay, Naturalize and Update blocks, the Weather brush, Jump and Through,
 * the line labels and scatter features. Round trips, and the refusals a hostile or broken client meets.
 */
class WorldEditBatchProtocolTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final Region BOX = new Region.Cuboid(Box.of(new BlockPos(0, 60, 0), new BlockPos(9, 70, 9)));
    private static final Pattern STONE = new Pattern.Single(STATES.state("minecraft:stone"));
    private static final BlockSet LOGS = BlockSet.of(new BlockSet.Tag(new NamespacedId("minecraft:logs")));

    private static C2S roundTrip(C2S message) throws ProtocolException {
        return Codec.decodeC2S(Codec.encodeC2S(message, STATES), STATES);
    }

    private static S2C roundTrip(S2C message) throws ProtocolException {
        return Codec.decodeS2C(Codec.encodeS2C(message, STATES), STATES);
    }

    private static ProtocolException.Reason reasonOf(byte[] frame) {
        try {
            Codec.decodeC2S(frame, STATES);
        } catch (ProtocolException e) {
            return e.reason();
        }
        return fail("Expected a ProtocolException for " + HexFormat.of().formatHex(frame));
    }

    /** A SetEditMask frame whose mask body is {@code body} (count, invertAll, entries), written by hand. */
    private static byte[] maskFrame(int... body) throws ProtocolException {
        WireWriter out = new WireWriter(ProtocolV2.MAX_C2S_FRAME);
        out.varint(MessageType.SET_EDIT_MASK.code());
        out.zigzag(1);
        for (int b : body) out.u8(b);
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- the global mask

    @Test
    void everyRuleRoundTripsNegatedOrNotAndTheMaskOffIsFourBytes() throws ProtocolException {
        List<MaskRule> rules = List.of(new MaskRule.Is(LOGS), new MaskRule.OnTopOf(LOGS), new MaskRule.Under(LOGS),
                new MaskRule.NextTo(LOGS), new MaskRule.Exposed(), new MaskRule.NotAir(), new MaskRule.Solid(),
                new MaskRule.Height(Integer.MIN_VALUE, Integer.MAX_VALUE), new MaskRule.Slope(0, MaskRule.MAX_SLOPE),
                new MaskRule.Inside(new Region.Uploaded(dev.sculptory.core.Sha256.digest(new byte[] {4}),
                        Box.of(BlockPos.ORIGIN, new BlockPos(3, 3, 3)), 5)),
                new MaskRule.Chance(99, Long.MIN_VALUE));
        for (MaskRule rule : rules) {
            for (boolean not : new boolean[] {false, true}) {
                C2S.SetEditMask message = new C2S.SetEditMask(3, new EditMask(List.of(new MaskEntry(rule, not)), not));
                assertEquals(message, roundTrip(message), rule.toString());
            }
        }
        assertEquals(4, Codec.encodeC2S(new C2S.SetEditMask(1, EditMask.NONE), STATES).length);
        assertEquals(OptionalInt.of(-8), Codec.peekLeadingId(Codec.encodeC2S(new C2S.SetEditMask(-8, EditMask.NONE),
                STATES)));
        assertEquals(RateLimiter.Kind.OPS, RateLimiter.frameKind(MessageType.SET_EDIT_MASK));
    }

    @Test
    void blockSetsKeepTheirEntriesAndTheirTextForm() throws ProtocolException {
        BlockSet set = new BlockSet(List.of(new BlockSet.Block(new NamespacedId("minecraft:stone")),
                new BlockSet.Tag(new NamespacedId("minecraft:logs")),
                new BlockSet.State(BlockDescriptor.parse("minecraft:oak_stairs[facing=east,half=top]"))));
        C2S.SetEditMask message = new C2S.SetEditMask(2, new EditMask(List.of(MaskEntry.of(new MaskRule.Is(set))), false));
        assertEquals(message, roundTrip(message));
        assertEquals("minecraft:stone;#minecraft:logs;minecraft:oak_stairs[facing=east,half=top]", set.format());
        assertEquals(set, BlockSet.parse(set.format()));
        assertThrows(IllegalArgumentException.class, () -> BlockSet.parse(""));
        assertThrows(IllegalArgumentException.class, () -> BlockSet.parse("minecraft:stone;"));
        assertThrows(IllegalArgumentException.class, () -> new BlockSet(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new BlockSet.State(BlockDescriptor.parse("minecraft:stone")));
        List<BlockSet.Entry> seventeen = new ArrayList<>();
        for (int i = 0; i < BlockSet.MAX_ENTRIES + 1; i++) seventeen.add(new BlockSet.Block(new NamespacedId("m:b" + i)));
        assertThrows(IllegalArgumentException.class, () -> new BlockSet(seventeen));
    }

    @Test
    void malformedMasksAreRefused() throws ProtocolException {
        // 1 entry, not inverted: kind 11 is unknown.
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(maskFrame(1, 0, 11, 0)));
        // A bool that is neither 0 nor 1.
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(maskFrame(1, 0, 4, 2)));
        // 17 entries: over the count cap.
        assertEquals(ProtocolException.Reason.TOO_LARGE, reasonOf(maskFrame(17, 0)));
        // An Is rule of an empty block set, and of an unknown entry tag.
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(maskFrame(1, 0, 0, 0, 0)));
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(maskFrame(1, 0, 0, 0, 1, 3)));
        // A state entry without properties ("m:b").
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(maskFrame(1, 0, 0, 0, 1, 2, 3, 'm', ':', 'b')));
        // A slope of 0-17, an inverted height (zigzag 2, 0 = 1 then 0), a chance of 0% and of 100%.
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(maskFrame(1, 0, 8, 0, 0, 17)));
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(maskFrame(1, 0, 7, 0, 2, 0)));
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(maskFrame(1, 0, 10, 0, 0, 0)));
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(maskFrame(1, 0, 10, 0, 100, 0)));
        // Trailing bytes after the mask.
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(maskFrame(0, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new MaskRule.Slope(3, 2));
        assertThrows(IllegalArgumentException.class, () -> new MaskRule.Height(5, 4));
    }

    @Test
    void theMaskStateSaysAcceptedOrWhyNot() throws ProtocolException {
        S2C.EditMaskState accepted = S2C.EditMaskState.accepted(4);
        S2C.EditMaskState refused = S2C.EditMaskState.refused(5, RejectReason.INVALID, "the mask names an unknown tag");
        assertEquals(accepted, roundTrip(accepted));
        assertEquals(refused, roundTrip(refused));
        assertThrows(IllegalArgumentException.class, () -> new S2C.EditMaskState(4, null, "detail"));
        assertEquals(OptionalInt.of(5), Codec.peekLeadingId(Codec.encodeS2C(refused, STATES)));
    }

    @Test
    void aBrushMaskInTheRuleFormRoundTrips() throws ProtocolException {
        SurfaceMask rules = new SurfaceMask.Rules(new EditMask(List.of(MaskEntry.of(new MaskRule.OnTopOf(LOGS)),
                new MaskEntry(new MaskRule.Slope(2, 5), true)), true));
        C2S.StrokeBegin begin = new C2S.StrokeBegin(1, new BrushSpec(BrushTool.RAISE, 4, 0.5f, Falloff.SMOOTH,
                Shape.CIRCLE, null, new SurfaceMask.Not(rules), 0, 0, 1L));
        assertEquals(begin, roundTrip(begin));
    }

    // ---------------------------------------------------------------- Better Replace and the selection ops

    @Test
    void keepShapeAndRemapRoundTripAndRefuseNestingAndBadSwaps() throws ProtocolException {
        Pattern keep = new Pattern.KeepShape(new Pattern.Weighted(new int[] {1, 2}, new int[] {1, 1}, 5L));
        Pattern remap = new Pattern.Remap(List.of(new Pattern.BlockSwap(new NamespacedId("minecraft:oak_log"),
                new NamespacedId("minecraft:spruce_log"))), false);
        for (Pattern to : List.of(keep, remap)) {
            C2S.RunOp op = new C2S.RunOp(1, new OpSpec.Replace(BOX, CellMask.ANY, to), false, ConflictPolicy.SKIP_CONFLICTS);
            assertEquals(op, roundTrip(op));
        }
        assertThrows(IllegalArgumentException.class, () -> new Pattern.KeepShape(keep));
        assertThrows(IllegalArgumentException.class, () -> new Pattern.KeepShape(remap));
        assertThrows(IllegalArgumentException.class, () -> new Pattern.Remap(List.of(), true));
        NamespacedId oak = new NamespacedId("minecraft:oak_log");
        assertThrows(IllegalArgumentException.class, () -> new Pattern.Remap(List.of(
                new Pattern.BlockSwap(oak, new NamespacedId("minecraft:spruce_log")),
                new Pattern.BlockSwap(oak, new NamespacedId("minecraft:birch_log"))), true));
        // A Keep shape around a Keep shape, 30,000 deep: refused at the second tag, without recursing.
        byte[] frame = Codec.encodeC2S(new C2S.RunOp(1, new OpSpec.Fill(BOX, keep, CellMask.ANY), false,
                ConflictPolicy.SKIP_CONFLICTS), STATES);
        int tag = indexOf(frame, CoreCodec.PATTERN_KEEP_SHAPE, CoreCodec.PATTERN_WEIGHTED);
        byte[] nested = frame.clone();
        nested[tag + 1] = CoreCodec.PATTERN_KEEP_SHAPE;
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(nested));
        byte[] deep = new byte[ProtocolV2.MAX_C2S_FRAME - 16];
        System.arraycopy(frame, 0, deep, 0, tag);
        java.util.Arrays.fill(deep, tag, deep.length, (byte) CoreCodec.PATTERN_KEEP_SHAPE);
        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(deep)));
        // Steepness is refused inside a Keep shape too.
        assertTrue(Pattern.needsSteepness(new Pattern.KeepShape(new Pattern.Arranged(
                new Pattern.Weighted(new int[] {1}, new int[] {1}, 0), new dev.sculptory.core.edit.MixLayout.Steepness(5)))));
    }

    @Test
    void theLayerOpsRoundTripAndRefuseDepthsOutOfRange() throws ProtocolException {
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 4, 0);
        List<OpSpec> ops = List.of(new OpSpec.Overlay(BOX, STONE, OpSpec.MAX_LAYER_DEPTH, mirror),
                new OpSpec.Naturalize(BOX, STONE, 1, STONE, 0, STONE),
                new OpSpec.Naturalize(BOX, STONE, OpSpec.MAX_LAYER_DEPTH, STONE, OpSpec.MAX_LAYER_DEPTH, STONE, mirror),
                new OpSpec.UpdateBlocks(BOX), new OpSpec.UpdateBlocks(BOX, mirror));
        for (OpSpec op : ops) {
            C2S.RunOp run = new C2S.RunOp(9, op, false, ConflictPolicy.SKIP_CONFLICTS);
            assertEquals(run, roundTrip(run), op.toString());
        }
        assertThrows(IllegalArgumentException.class, () -> new OpSpec.Overlay(BOX, STONE, 0));
        assertThrows(IllegalArgumentException.class, () -> new OpSpec.Overlay(BOX, STONE, OpSpec.MAX_LAYER_DEPTH + 1));
        assertThrows(IllegalArgumentException.class, () -> new OpSpec.Naturalize(BOX, STONE, 0, STONE, 3, STONE));
        assertThrows(IllegalArgumentException.class, () -> new OpSpec.Naturalize(BOX, STONE, 1, STONE, 17, STONE));
        // An overlay depth of 0 on the wire: the depth follows the region and the pattern.
        byte[] frame = Codec.encodeC2S(new C2S.RunOp(9, new OpSpec.Overlay(BOX, STONE, 5), false,
                ConflictPolicy.SKIP_CONFLICTS), STATES);
        int depth = frame.length - 5; // then symmetry OFF, physics, policy, label
        assertEquals(5, frame[depth]);
        byte[] zero = frame.clone();
        zero[depth] = 0;
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(zero));
    }

    // ---------------------------------------------------------------- Weather, lines

    @Test
    void theWeatherBrushCarriesItsModeAndOnlyIt() throws ProtocolException {
        for (WeatherSpec.Mode mode : WeatherSpec.Mode.values()) {
            C2S.StrokeBegin begin = new C2S.StrokeBegin(2, weather(new WeatherSpec(mode)));
            assertEquals(begin, roundTrip(begin), mode.name());
        }
        assertThrows(IllegalArgumentException.class, () -> weather(null));
        assertThrows(IllegalArgumentException.class, () -> new BrushSpec(BrushTool.RAISE, 4, 1f, Falloff.SMOOTH,
                Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L, null, Symmetry.NONE, null, SculptMode.TERRAIN, null,
                new WeatherSpec(WeatherSpec.Mode.ERODE)));
        // An unknown mode ordinal (the last byte of the frame).
        byte[] frame = Codec.encodeC2S(new C2S.StrokeBegin(2, weather(new WeatherSpec(WeatherSpec.Mode.ERODE))), STATES);
        frame[frame.length - 1] = (byte) WeatherSpec.Mode.values().length;
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(frame));
        // A spec that keeps its weather mode through its copies.
        BrushSpec spec = weather(new WeatherSpec(WeatherSpec.Mode.MELT));
        assertEquals(spec.weather(), spec.withClip(Box.of(BlockPos.ORIGIN)).withSymmetry(Symmetry.NONE).weather());
    }

    private static BrushSpec weather(WeatherSpec spec) {
        return new BrushSpec(BrushTool.WEATHER, 5, 0.5f, Falloff.SMOOTH, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 3L, null,
                Symmetry.NONE, null, SculptMode.TERRAIN, null, spec);
    }

    @Test
    void theLineLabelsNamePastesOnly() {
        OpSpec fill = new OpSpec.Fill(BOX, STONE, CellMask.ANY);
        assertFalse(OpLabel.LINE.fits(fill));
        assertFalse(OpLabel.SHAPE_LINE.fits(new OpSpec.UpdateBlocks(BOX)));
    }

    // ---------------------------------------------------------------- Jump and Through

    @Test
    void navigateRoundTripsAndRefusesABadDirection() throws ProtocolException {
        C2S.Navigate through = new C2S.Navigate(6, NavigateMode.THROUGH, new BlockPos(-5, 70, 12), Facing.NORTH, 0f, 0f,
                1f);
        assertEquals(through, roundTrip(through));
        assertEquals(OptionalInt.of(6), Codec.peekLeadingId(Codec.encodeC2S(through, STATES)));
        assertEquals(RateLimiter.Kind.OPS, RateLimiter.frameKind(MessageType.NAVIGATE));
        assertThrows(IllegalArgumentException.class, () -> new C2S.Navigate(1, NavigateMode.JUMP, BlockPos.ORIGIN,
                Facing.UP, 0f, 0f, 0f));
        assertThrows(IllegalArgumentException.class, () -> new C2S.Navigate(1, NavigateMode.JUMP, BlockPos.ORIGIN,
                Facing.UP, Float.NaN, 1f, 0f));
        // The direction's last float set to infinity on the wire.
        byte[] frame = Codec.encodeC2S(through, STATES);
        byte[] infinite = frame.clone();
        int bits = Float.floatToIntBits(Float.POSITIVE_INFINITY);
        for (int i = 0; i < 4; i++) infinite[frame.length - 4 + i] = (byte) (bits >>> (24 - 8 * i));
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(infinite));
        byte[] badMode = frame.clone();
        badMode[2] = (byte) NavigateMode.values().length;
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(badMode));
    }

    @Test
    void aNavigateResultHasALandingOrAReason() throws ProtocolException {
        S2C.NavigateResult landed = S2C.NavigateResult.landed(3, new BlockPos(1, -60, 2));
        S2C.NavigateResult refused = S2C.NavigateResult.refused(4, RejectReason.NO_PERMISSION);
        assertEquals(landed, roundTrip(landed));
        assertEquals(refused, roundTrip(refused));
        assertThrows(IllegalArgumentException.class, () -> new S2C.NavigateResult(1, null, null));
        assertThrows(IllegalArgumentException.class, () -> new S2C.NavigateResult(1, RejectReason.INVALID, BlockPos.ORIGIN));
    }

    // ---------------------------------------------------------------- scatter features

    @Test
    void featureVariantsTravelAsTheirIdAndEmptyOnesAreRefused() throws ProtocolException {
        C2S.ScatterPreview preview = new C2S.ScatterPreview(8, new ScatterArea.Stamps(List.of(
                ScatterArea.Stamp.paint(3, 4, 0))),
                new C2S.ScatterPreview.Settings(1L, 0, new ScatterSettings.Density.Count(1), SurfaceMask.ANY,
                        ScatterSettings.Fit.DEFAULT),
                List.of(new C2S.ScatterPreview.Variant(new ScatterSource.Feature("minecraft:birch"), 1)),
                ScatterSettings.Transforms.ALL);
        assertEquals(preview, roundTrip(preview));
        assertThrows(IllegalArgumentException.class, () -> new ScatterSource.Feature(" "));
        assertThrows(IllegalArgumentException.class, () -> new ScatterSource.Feature("m:" + "x".repeat(300)));
        byte[] frame = Codec.encodeC2S(preview, STATES);
        // The variant: tag 3, text length 15, the id, weight 1, then the transforms (2 bytes) and column height (2).
        int at = frame.length - 4 - 1 - 15 - 1 - 1;
        assertEquals(Codec.SCATTER_VARIANT_FEATURE, frame[at]);
        byte[] empty = new byte[frame.length - 15];
        System.arraycopy(frame, 0, empty, 0, at + 1);
        empty[at + 1] = 0;
        System.arraycopy(frame, at + 2 + 15, empty, at + 2, frame.length - at - 2 - 15);
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(empty));
    }

    /** The index of the first {@code first} byte followed by {@code second}. */
    private static int indexOf(byte[] bytes, int first, int second) {
        for (int i = 0; i + 1 < bytes.length; i++) {
            if (bytes[i] == first && bytes[i + 1] == second) return i;
        }
        return fail("No " + first + ", " + second + " in " + HexFormat.of().formatHex(bytes));
    }
}
