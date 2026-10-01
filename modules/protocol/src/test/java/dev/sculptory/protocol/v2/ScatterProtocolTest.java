package dev.sculptory.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.scatter.ScatterArea;
import dev.sculptory.core.scatter.ScatterPlan;
import dev.sculptory.core.scatter.ScatterSettings;
import dev.sculptory.core.scatter.ScatterSource;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M3-B wire additions: the {@code ScatterPreview} body, the {@code ScatterPlan} summary and the placements stream. */
class ScatterProtocolTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final UUID ID = new UUID(5, 6);

    private static C2S.ScatterPreview preview(ScatterArea area, ScatterSettings.Density density) {
        return new C2S.ScatterPreview(7, area, new C2S.ScatterPreview.Settings(-9L, 6, density,
                new SurfaceMask.And(List.of(new SurfaceMask.Slope(0, 2), new SurfaceMask.Elevation(60, 90),
                        new SurfaceMask.SurfaceBlocks(new CellMask.States(new int[] {STATES.state("minecraft:grass_block")})))),
                new ScatterSettings.Fit(true, 0.25)),
                List.of(new C2S.ScatterPreview.Variant(new SourceRef.Clipboard(ID), 3),
                        new C2S.ScatterPreview.Variant(new SourceRef.Asset(Sha256.digest(new byte[] {1}).hex()), 1000)),
                new ScatterSettings.Transforms(0b0101, true));
    }

    private static ProtocolException.Reason reasonOf(byte[] frame) {
        try {
            Codec.decodeC2S(frame, STATES);
        } catch (ProtocolException e) {
            return e.reason();
        }
        return fail("Expected a ProtocolException for " + HexFormat.of().formatHex(frame));
    }

    // ---------------------------------------------------------------- ScatterPreview

    @Test
    void previewsRoundTripWithStampsBoxesFractionsAndCounts() throws ProtocolException {
        List<ScatterArea> areas = List.of(
                new ScatterArea.Stamps(List.of(ScatterArea.Stamp.paint(-1000, 2000, 64), ScatterArea.Stamp.erase(-990, 2010, 0),
                        ScatterArea.Stamp.paint(-1100, 1900, 5))),
                new ScatterArea.Region(Box.of(new BlockPos(-5, -64, 3), new BlockPos(1018, 319, 1026))));
        for (ScatterArea area : areas) {
            for (ScatterSettings.Density density : List.of(new ScatterSettings.Density.Fraction(0.123456789),
                    new ScatterSettings.Density.Fraction(0), new ScatterSettings.Density.Count(ScatterSettings.MAX_PLACEMENTS))) {
                C2S.ScatterPreview message = preview(area, density);
                assertEquals(message, CodecRoundTripTest.roundTripC2S(message));
            }
        }
    }

    /**
     * Block variants travel as their state text next to paste sources (the server resolves the text, so an unknown
     * block is its refusal, not a codec error), with the survival flag; an empty text, an unknown variant tag and a
     * text over the cap are rejected.
     */
    @Test
    void blockVariantsTravelAsStateTextAndBadOnesAreRejected() throws ProtocolException {
        ScatterArea area = new ScatterArea.Region(Box.of(new BlockPos(0, 60, 0), new BlockPos(31, 80, 31)));
        C2S.ScatterPreview mixed = new C2S.ScatterPreview(3, area, new C2S.ScatterPreview.Settings(1L, 2,
                new ScatterSettings.Density.Fraction(0.5), SurfaceMask.ANY, new ScatterSettings.Fit(false, 0.5, false)),
                List.of(new C2S.ScatterPreview.Variant(new ScatterSource.Block("minecraft:pink_petals[facing=east,flower_amount=3]"), 5),
                        new C2S.ScatterPreview.Variant(new SourceRef.Clipboard(ID), 2),
                        new C2S.ScatterPreview.Variant(new ScatterSource.Block("modded:not_a_block_here"), 1)),
                ScatterSettings.Transforms.ALL);
        C2S.ScatterPreview back = (C2S.ScatterPreview) CodecRoundTripTest.roundTripC2S(mixed);
        assertEquals(mixed, back);
        assertEquals(false, back.settings().fit().survive());
        assertEquals(new ScatterSource.Block("minecraft:pink_petals[facing=east,flower_amount=3]"),
                back.variants().get(0).source());

        C2S.ScatterPreview one = new C2S.ScatterPreview(3, area, mixed.settings(),
                List.of(new C2S.ScatterPreview.Variant(new ScatterSource.Block("x"), 1)), ScatterSettings.Transforms.ALL);
        byte[] frame = Codec.encodeC2S(one, STATES);
        // The variant: tag 2, text length 1, 'x', weight 1, then the transforms (turn mask, mirror) and the column
        // height (min, max).
        int at = frame.length - 8;
        assertEquals(Codec.SCATTER_VARIANT_BLOCK, frame[at]);
        assertEquals('x', frame[at + 2]);
        byte[] badTag = frame.clone();
        badTag[at] = 4; // after the feature variant (3)
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(badTag));
        byte[] empty = new byte[frame.length - 1];
        System.arraycopy(frame, 0, empty, 0, at + 1);
        empty[at + 1] = 0; // an empty text
        System.arraycopy(frame, at + 3, empty, at + 2, frame.length - at - 3);
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(empty));
        byte[] huge = frame.clone();
        huge[at + 1] = 0x7F; // longer than the frame holds, but under the cap
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(huge));
        WireWriter over = new WireWriter(16);
        over.varint(ScatterSource.MAX_STATE_BYTES + 1);
        byte[] overCap = new byte[frame.length + 1];
        System.arraycopy(frame, 0, overCap, 0, at + 1);
        System.arraycopy(over.toByteArray(), 0, overCap, at + 1, 2);
        System.arraycopy(frame, at + 2, overCap, at + 3, frame.length - at - 2);
        ProtocolException.Reason reason = reasonOf(overCap);
        assertTrue(reason == ProtocolException.Reason.TOO_LARGE || reason == ProtocolException.Reason.MALFORMED,
                "over the cap: " + reason);
        assertThrows(IllegalArgumentException.class, () -> new ScatterSource.Block(" "));
        assertThrows(IllegalArgumentException.class, () -> new ScatterSource.Block("a".repeat(ScatterSource.MAX_STATE_BYTES + 1)));
    }

    /** 512 stamps as far apart as the core allows, 64 asset variants and a busy surface mask still fit one frame. */
    @Test
    void theLargestPreviewFitsOneFrameAndOneMoreStampDoesNot() throws ProtocolException {
        List<ScatterArea.Stamp> stamps = new ArrayList<>();
        for (int i = 0; i < Codec.MAX_SCATTER_STAMPS; i++) {
            // Hop between the corners of a 1024 x 1024 rectangle far from the origin: two-byte deltas throughout.
            int x = (i & 1) == 0 ? -33_000_000 : -33_000_000 + 1023 - 128;
            int z = (i & 2) == 0 ? 33_000_000 - 1023 + 128 : 33_000_000;
            stamps.add(new ScatterArea.Stamp(x, z, ScatterArea.MAX_RADIUS, i % 3 == 0 && i > 0));
        }
        List<C2S.ScatterPreview.Variant> variants = new ArrayList<>();
        for (int i = 0; i < ScatterSettings.MAX_VARIANTS; i++) {
            variants.add(new C2S.ScatterPreview.Variant(new SourceRef.Asset(Sha256.digest(new byte[] {(byte) i}).hex()),
                    ScatterSettings.MAX_WEIGHT));
        }
        C2S.ScatterPreview largest = new C2S.ScatterPreview(Integer.MIN_VALUE, new ScatterArea.Stamps(stamps),
                new C2S.ScatterPreview.Settings(Long.MIN_VALUE, ScatterSettings.MAX_SPACING,
                        new ScatterSettings.Density.Fraction(Math.nextDown(1.0)), preview(new ScatterArea.Stamps(stamps),
                        new ScatterSettings.Density.Count(1)).settings().surface(), ScatterSettings.Fit.DEFAULT),
                variants, ScatterSettings.Transforms.ALL);
        byte[] frame = Codec.encodeC2S(largest, STATES);
        assertTrue(frame.length < ProtocolV2.MAX_C2S_FRAME / 2, "largest preview is " + frame.length + " bytes");
        assertEquals(largest, Codec.decodeC2S(frame, STATES));

        List<ScatterArea.Stamp> tooMany = new ArrayList<>(stamps);
        tooMany.add(ScatterArea.Stamp.paint(-33_000_000, 33_000_000, 1));
        C2S.ScatterPreview over = new C2S.ScatterPreview(1, new ScatterArea.Stamps(tooMany), largest.settings(), variants,
                ScatterSettings.Transforms.NONE);
        ProtocolException e = assertThrows(ProtocolException.class, () -> Codec.encodeC2S(over, STATES));
        assertEquals(ProtocolException.Reason.TOO_LARGE, e.reason());
    }

    /**
     * The most a client can send with block variants: 512 stamps as far apart as the core allows (deltas of up to
     * 2^26, the longest varints), 64 block variants whose state texts are all at the 256-byte cap, the busy surface
     * mask. It fits one client frame.
     */
    @Test
    void sixtyFourBlockVariantsAtTheCapFitOneFrame() throws ProtocolException {
        List<ScatterArea.Stamp> stamps = new ArrayList<>();
        int far = ScatterArea.MAX_HORIZONTAL - ScatterArea.MAX_RADIUS - 1;
        for (int i = 0; i < Codec.MAX_SCATTER_STAMPS; i++) {
            // Painting stamps stay within the area's column cap; erase stamps may be anywhere: alternate the two.
            boolean erase = (i & 1) == 1;
            int x = erase ? far : -far + ((i >> 1) & 1) * 800, z = erase ? far : -far + ((i >> 2) & 1) * 800;
            stamps.add(new ScatterArea.Stamp(x, z, ScatterArea.MAX_RADIUS, erase));
        }
        List<C2S.ScatterPreview.Variant> variants = new ArrayList<>();
        for (int i = 0; i < ScatterSettings.MAX_VARIANTS; i++) {
            String prefix = "modded:plant_" + i + "[";
            String state = prefix + "x".repeat(ScatterSource.MAX_STATE_BYTES - prefix.length() - 1) + "]";
            assertEquals(ScatterSource.MAX_STATE_BYTES, state.length());
            variants.add(new C2S.ScatterPreview.Variant(new ScatterSource.Block(state), ScatterSettings.MAX_WEIGHT));
        }
        C2S.ScatterPreview largest = new C2S.ScatterPreview(Integer.MIN_VALUE, new ScatterArea.Stamps(stamps),
                new C2S.ScatterPreview.Settings(Long.MIN_VALUE, ScatterSettings.MAX_SPACING,
                        new ScatterSettings.Density.Fraction(Math.nextDown(1.0)), preview(new ScatterArea.Stamps(stamps),
                        new ScatterSettings.Density.Count(1)).settings().surface(), ScatterSettings.Fit.DEFAULT),
                variants, ScatterSettings.Transforms.ALL);
        byte[] frame = Codec.encodeC2S(largest, STATES);
        assertTrue(frame.length <= ProtocolV2.MAX_C2S_FRAME, "largest block preview is " + frame.length + " bytes");
        assertEquals(largest, Codec.decodeC2S(frame, STATES));
    }

    private static byte[] encode(C2S.ScatterPreview message) throws ProtocolException {
        return Codec.encodeC2S(message, STATES);
    }

    @Test
    void invalidAreasDensitiesAndFitsAreRejected() throws ProtocolException {
        ScatterArea one = new ScatterArea.Stamps(List.of(ScatterArea.Stamp.paint(0, 0, 3)));
        C2S.ScatterPreview simple = new C2S.ScatterPreview(1, one, new C2S.ScatterPreview.Settings(0, 0,
                new ScatterSettings.Density.Fraction(0.5), SurfaceMask.ANY, ScatterSettings.Fit.DEFAULT),
                List.of(new C2S.ScatterPreview.Variant(new SourceRef.Clipboard(ID), 1)), ScatterSettings.Transforms.NONE);
        byte[] frame = encode(simple);
        // type, reqId, palette count 0, area tag, stamp count, dx, dz, radius|erase, ...
        assertEquals(Codec.SCATTER_AREA_STAMPS, frame[3]);
        assertEquals(1, frame[4]);
        assertEquals(3 << 1, frame[7]);

        byte[] badTag = frame.clone();
        badTag[3] = 9;
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(badTag));

        byte[] radius65 = frame.clone();
        radius65[7] = (byte) (65 << 1);
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(radius65));

        byte[] onlyErase = frame.clone();
        onlyErase[7] = (byte) (3 << 1 | 1);
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(onlyErase), "no painting stamp");

        byte[] tooManyStamps = frame.clone();
        tooManyStamps[4] = (byte) 0x81; // varint 513 = 0x81 0x04: the count byte continues into dx
        tooManyStamps[5] = 0x04;
        assertEquals(ProtocolException.Reason.TOO_LARGE, reasonOf(tooManyStamps));

        // The density follows seed (1 byte for 0) and spacing (1 byte): tag, then 8 bytes of the double.
        int densityAt = 8 + 2;
        assertEquals(Codec.SCATTER_DENSITY_FRACTION, frame[densityAt]);
        for (double bad : new double[] {Double.NaN, -0.5, 1.5, Double.POSITIVE_INFINITY}) {
            byte[] mutated = frame.clone();
            long bits = Double.doubleToLongBits(bad);
            for (int i = 0; i < 8; i++) mutated[densityAt + 1 + i] = (byte) (bits >>> (56 - 8 * i));
            assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(mutated), "density " + bad);
        }
        byte[] badDensityTag = frame.clone();
        badDensityTag[densityAt] = 5;
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(badDensityTag));

        // Support fraction: after the density, the ANY surface mask (1 byte) and allowInFluid.
        int supportAt = densityAt + 9 + 1 + 1;
        byte[] badSupport = frame.clone();
        badSupport[supportAt] = 0x7F;
        badSupport[supportAt + 1] = (byte) 0xF8; // NaN
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(badSupport));

        // The transform mask comes before the mirror flag and the two column height bytes: 0 turns is invalid.
        byte[] noTurns = frame.clone();
        noTurns[noTurns.length - 4] = 0;
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(noTurns));

        // A stamp delta that leaves the int range, and one that leaves the world.
        WireWriter far = new WireWriter(64);
        far.varint(MessageType.SCATTER_PREVIEW.code());
        far.zigzag(1);
        far.varint(0);
        far.u8(Codec.SCATTER_AREA_STAMPS);
        far.varint(2);
        far.zigzag(Integer.MAX_VALUE);
        far.zigzag(0);
        far.u8(2);
        far.zigzag(Integer.MAX_VALUE);
        far.zigzag(0);
        far.u8(2);
        byte[] farFrame = Arrays.copyOf(far.toByteArray(), far.size() + 40);
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(farFrame));
    }

    /**
     * The column height travels as its last two bytes (min, max); one block tall by default. A range outside 1-32, an
     * inverted one and a frame cut before it are MALFORMED.
     */
    @Test
    void theColumnHeightTravelsLastAndBadRangesAreRejected() throws ProtocolException {
        ScatterArea one = new ScatterArea.Stamps(List.of(ScatterArea.Stamp.paint(0, 0, 3)));
        List<C2S.ScatterPreview.Variant> kelp = List.of(new C2S.ScatterPreview.Variant(
                new ScatterSource.Block("minecraft:kelp"), 1));
        C2S.ScatterPreview plain = new C2S.ScatterPreview(1, one, new C2S.ScatterPreview.Settings(0, 0,
                new ScatterSettings.Density.Fraction(0.5), SurfaceMask.ANY, ScatterSettings.Fit.DEFAULT), kelp,
                ScatterSettings.Transforms.NONE);
        assertEquals(ScatterSettings.ColumnHeight.ONE, plain.settings().columnHeight());
        byte[] frame = encode(plain);
        assertEquals(1, frame[frame.length - 2]);
        assertEquals(1, frame[frame.length - 1]);
        for (ScatterSettings.ColumnHeight range : List.of(new ScatterSettings.ColumnHeight(1, 4),
                new ScatterSettings.ColumnHeight(3, 3), new ScatterSettings.ColumnHeight(1, ScatterSettings.MAX_COLUMN_HEIGHT))) {
            C2S.ScatterPreview tall = new C2S.ScatterPreview(1, one, new C2S.ScatterPreview.Settings(0, 0,
                    new ScatterSettings.Density.Fraction(0.5), SurfaceMask.ANY, ScatterSettings.Fit.DEFAULT, range), kelp,
                    ScatterSettings.Transforms.NONE);
            byte[] encoded = encode(tall);
            assertEquals(frame.length, encoded.length, "two bytes whatever the range");
            assertEquals(tall, Codec.decodeC2S(encoded, STATES));
        }
        int[][] bad = {{0, 1}, {1, 0}, {0, 0}, {4, 3}, {1, ScatterSettings.MAX_COLUMN_HEIGHT + 1},
                {ScatterSettings.MAX_COLUMN_HEIGHT + 1, ScatterSettings.MAX_COLUMN_HEIGHT + 1}, {255, 255}};
        for (int[] range : bad) {
            byte[] mutated = frame.clone();
            mutated[mutated.length - 2] = (byte) range[0];
            mutated[mutated.length - 1] = (byte) range[1];
            assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(mutated), range[0] + "-" + range[1]);
        }
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(Arrays.copyOf(frame, frame.length - 1)),
                "no max");
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(Arrays.copyOf(frame, frame.length - 2)),
                "no column height (an older client's frame)");
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(Arrays.copyOf(frame, frame.length + 1)),
                "a byte after it");
        assertThrows(IllegalArgumentException.class, () -> new ScatterSettings.ColumnHeight(2, 1));
        assertThrows(IllegalArgumentException.class, () -> new ScatterSettings.ColumnHeight(0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ScatterSettings.ColumnHeight(1, ScatterSettings.MAX_COLUMN_HEIGHT + 1));
    }

    /**
     * Placements of taller columns carry their height after a flag bit of the transform byte; one-block placements
     * encode as before. A flagged height outside 2-32, a missing height and the reserved bits are MALFORMED.
     */
    @Test
    void placementHeightsTravelAfterTheTransformAndBadOnesAreRejected() throws ProtocolException {
        List<ScatterPlan.Placement> flat = List.of(new ScatterPlan.Placement(new BlockPos(1, 2, 3), 0,
                new Transform(1, Mirror.X)));
        byte[] one = ScatterPlacements.encode(flat);
        assertEquals((1 | (Mirror.X.ordinal() << 2)), one[one.length - 1], "the transform byte, no height");
        List<ScatterPlan.Placement> tall = List.of(
                new ScatterPlan.Placement(new BlockPos(1, 2, 3), 0, new Transform(1, Mirror.X), 4),
                new ScatterPlan.Placement(new BlockPos(5, 2, 3), 1, Transform.IDENTITY, ScatterSettings.MAX_COLUMN_HEIGHT),
                new ScatterPlan.Placement(new BlockPos(9, 2, 3), 1, Transform.IDENTITY, 1));
        byte[] payload = ScatterPlacements.encode(tall);
        assertEquals(tall, ScatterPlacements.decode(payload, 2));
        assertEquals(one.length + 1, ScatterPlacements.encode(tall.subList(0, 1)).length, "one height byte");

        byte[] single = ScatterPlacements.encode(tall.subList(0, 1));
        for (int height : new int[] {0, 1, ScatterSettings.MAX_COLUMN_HEIGHT + 1, 255}) {
            byte[] mutated = single.clone();
            mutated[mutated.length - 1] = (byte) height;
            assertEquals(ProtocolException.Reason.MALFORMED, decodeReason(mutated, 1), "height " + height);
        }
        assertEquals(ProtocolException.Reason.MALFORMED, decodeReason(Arrays.copyOf(single, single.length - 1), 1),
                "flagged without a height");
        for (int reserved : new int[] {0x20, 0x40, 0x80}) {
            byte[] mutated = one.clone();
            mutated[mutated.length - 1] |= (byte) reserved;
            assertEquals(ProtocolException.Reason.MALFORMED, decodeReason(mutated, 1), "bit " + reserved);
        }
        byte[] badMirror = one.clone();
        badMirror[badMirror.length - 1] = (byte) (3 << 2);
        assertEquals(ProtocolException.Reason.MALFORMED, decodeReason(badMirror, 1), "mirror 3");
        assertThrows(IllegalArgumentException.class,
                () -> new ScatterPlan.Placement(BlockPos.ORIGIN, 0, Transform.IDENTITY, 0));
        assertThrows(IllegalArgumentException.class, () -> new ScatterPlan.Placement(BlockPos.ORIGIN, 0,
                Transform.IDENTITY, ScatterSettings.MAX_COLUMN_HEIGHT + 1));
    }

    @Test
    void variantWeightsAndSpacingAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new C2S.ScatterPreview.Variant(new SourceRef.Clipboard(ID), 0));
        assertThrows(IllegalArgumentException.class, () -> new C2S.ScatterPreview.Variant(new SourceRef.Clipboard(ID),
                ScatterSettings.MAX_WEIGHT + 1));
        assertThrows(IllegalArgumentException.class, () -> new C2S.ScatterPreview.Settings(0, ScatterSettings.MAX_SPACING + 1,
                new ScatterSettings.Density.Count(1), SurfaceMask.ANY, ScatterSettings.Fit.DEFAULT));
        List<C2S.ScatterPreview.Variant> tooMany = new ArrayList<>();
        for (int i = 0; i <= ScatterSettings.MAX_VARIANTS; i++) {
            tooMany.add(new C2S.ScatterPreview.Variant(new SourceRef.Clipboard(ID), 1));
        }
        ScatterArea area = new ScatterArea.Stamps(List.of(ScatterArea.Stamp.paint(0, 0, 1)));
        C2S.ScatterPreview.Settings settings = new C2S.ScatterPreview.Settings(0, 0, new ScatterSettings.Density.Count(1),
                SurfaceMask.ANY, ScatterSettings.Fit.DEFAULT);
        assertThrows(IllegalArgumentException.class,
                () -> new C2S.ScatterPreview(1, area, settings, tooMany, ScatterSettings.Transforms.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new C2S.ScatterPreview(1, area, settings, List.of(), ScatterSettings.Transforms.NONE));
    }

    // ---------------------------------------------------------------- ScatterPlan summary

    @Test
    void planSummariesRoundTripWithAndWithoutBounds() throws ProtocolException {
        TreeMap<String, Integer> counts = new TreeMap<>();
        counts.put("DENSITY", 1_000_000);
        counts.put("PROTECTED", 3);
        for (Box bounds : Arrays.asList(Box.of(new BlockPos(-3, 60, -3), new BlockPos(90, 80, 40)), null)) {
            S2C.ScatterPlan plan = new S2C.ScatterPlan(4, ID, 142, counts, 38_000, bounds);
            assertEquals(plan, CodecRoundTripTest.roundTripS2C(plan));
        }
        assertThrows(IllegalArgumentException.class, () -> new S2C.ScatterPlan(1, ID, -1, counts, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new S2C.ScatterPlan(1, ID, 0, counts, -1, null));
    }

    // ---------------------------------------------------------------- SCATTER_PLACEMENTS payload

    @Test
    void placementPayloadsRoundTripInPlanOrder() throws ProtocolException {
        RandomMessages random = new RandomMessages(31L, STATES);
        for (int round = 0; round < 200; round++) {
            int variants = 1 + random.rnd.nextInt(ScatterSettings.MAX_VARIANTS);
            List<ScatterPlan.Placement> placements = random.placements(random.rnd.nextInt(300), variants);
            byte[] payload = ScatterPlacements.encode(placements);
            assertEquals(placements, ScatterPlacements.decode(payload, variants));
        }
        assertEquals(List.of(), ScatterPlacements.decode(ScatterPlacements.encode(List.of()), 1));
        // Extreme anchors: every delta wraps and unwraps exactly.
        List<ScatterPlan.Placement> extreme = List.of(
                new ScatterPlan.Placement(new BlockPos(Integer.MAX_VALUE, Integer.MIN_VALUE, 0), 0, new Transform(3, Mirror.Z)),
                new ScatterPlan.Placement(new BlockPos(Integer.MIN_VALUE, Integer.MAX_VALUE, -1), 63, Transform.IDENTITY),
                new ScatterPlan.Placement(new BlockPos(0, 0, Integer.MIN_VALUE), 5, new Transform(1, Mirror.X)));
        assertEquals(extreme, ScatterPlacements.decode(ScatterPlacements.encode(extreme), 64));
    }

    @Test
    void aFullPlanIsCompactAndStreamable() throws ProtocolException {
        RandomMessages random = new RandomMessages(32L, STATES);
        List<ScatterPlan.Placement> placements = new ArrayList<>();
        // A realistic dense plan: anchors within a 1024 x 1024 area.
        for (int i = 0; i < ScatterPlacements.MAX_PLACEMENTS; i++) {
            placements.add(new ScatterPlan.Placement(new BlockPos(random.rnd.nextInt(1024), 60 + random.rnd.nextInt(20),
                    random.rnd.nextInt(1024)), random.rnd.nextInt(8), random.transform()));
        }
        byte[] payload = ScatterPlacements.encode(placements);
        assertTrue(payload.length < 10 * placements.size(), payload.length + " bytes");
        assertEquals(placements, ScatterPlacements.decode(payload, 8));
        List<ScatterPlan.Placement> tooMany = new ArrayList<>(placements);
        tooMany.add(placements.get(0));
        assertThrows(IllegalArgumentException.class, () -> ScatterPlacements.encode(tooMany));
    }

    @Test
    void malformedPlacementPayloadsAreRejected() throws ProtocolException {
        List<ScatterPlan.Placement> two = List.of(
                new ScatterPlan.Placement(new BlockPos(1, 2, 3), 1, Transform.IDENTITY),
                new ScatterPlan.Placement(new BlockPos(4, 5, 6), 0, new Transform(2, Mirror.X)));
        byte[] payload = ScatterPlacements.encode(two);
        assertEquals(two, ScatterPlacements.decode(payload, 2));
        assertEquals(ProtocolException.Reason.MALFORMED, decodeReason(payload, 1), "variant 1 of 1");
        byte[] magic = payload.clone();
        magic[0] = 'X';
        assertEquals(ProtocolException.Reason.MALFORMED, decodeReason(magic, 2));
        byte[] format = payload.clone();
        format[4] = 2;
        assertEquals(ProtocolException.Reason.MALFORMED, decodeReason(format, 2));
        byte[] transform = payload.clone();
        transform[transform.length - 1] = (byte) 0xFC;
        assertEquals(ProtocolException.Reason.MALFORMED, decodeReason(transform, 2));
        byte[] trailing = Arrays.copyOf(payload, payload.length + 1);
        assertEquals(ProtocolException.Reason.MALFORMED, decodeReason(trailing, 2));
        for (int length = 0; length < payload.length; length++) {
            assertEquals(ProtocolException.Reason.MALFORMED, decodeReason(Arrays.copyOf(payload, length), 2), "cut to " + length);
        }
        // A count over the cap, and one the bytes cannot hold.
        WireWriter huge = new WireWriter(32);
        huge.raw("BSSP".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        huge.u8(ScatterPlacements.FORMAT);
        huge.varint(ScatterPlacements.MAX_PLACEMENTS + 1);
        assertEquals(ProtocolException.Reason.TOO_LARGE, decodeReason(huge.toByteArray(), 2));
        WireWriter truncated = new WireWriter(32);
        truncated.raw("BSSP".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        truncated.u8(ScatterPlacements.FORMAT);
        truncated.varint(1000);
        truncated.raw(new byte[] {0, 0, 0, 0, 0});
        assertEquals(ProtocolException.Reason.MALFORMED, decodeReason(truncated.toByteArray(), 2));
    }

    private static ProtocolException.Reason decodeReason(byte[] payload, int variants) {
        try {
            ScatterPlacements.decode(payload, variants);
        } catch (ProtocolException e) {
            return e.reason();
        }
        return fail("Expected a ProtocolException for " + HexFormat.of().formatHex(payload));
    }

    @Test
    void fuzzedPlacementPayloadsThrowOnlyProtocolExceptions() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            RandomMessages random = new RandomMessages(33L, STATES);
            Random rnd = random.rnd;
            int decoded = 0;
            for (int i = 0; i < CodecRobustnessTest.FUZZ_ITERATIONS; i++) {
                byte[] payload;
                if (rnd.nextInt(4) == 0) {
                    payload = new byte[rnd.nextInt(64)];
                    rnd.nextBytes(payload);
                    if (payload.length > 5 && rnd.nextBoolean()) {
                        System.arraycopy("BSSP".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, payload, 0, 4);
                        payload[4] = 1;
                    }
                } else {
                    payload = ScatterPlacements.encode(random.placements(1 + rnd.nextInt(20), 8));
                    int edits = 1 + rnd.nextInt(3);
                    for (int e = 0; e < edits; e++) {
                        int at = rnd.nextInt(payload.length);
                        switch (rnd.nextInt(3)) {
                            case 0 -> payload[at] = (byte) rnd.nextInt(256);
                            case 1 -> payload = Arrays.copyOf(payload, at);
                            default -> payload[at] ^= (byte) (1 << rnd.nextInt(8));
                        }
                        if (payload.length == 0) break;
                    }
                }
                try {
                    List<ScatterPlan.Placement> placements = ScatterPlacements.decode(payload, 8);
                    assertEquals(placements, ScatterPlacements.decode(ScatterPlacements.encode(placements), 8));
                    decoded++;
                } catch (ProtocolException expected) {
                    // fine
                } catch (Throwable t) {
                    throw new AssertionError("Decoder threw " + t + " for " + HexFormat.of().formatHex(payload), t);
                }
            }
            assertTrue(decoded > 0 && decoded < CodecRobustnessTest.FUZZ_ITERATIONS, "decoded " + decoded);
        });
    }
}
