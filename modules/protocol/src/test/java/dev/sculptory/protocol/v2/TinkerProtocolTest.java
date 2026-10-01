package dev.sculptory.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.tinker.DisplayRotation;
import dev.sculptory.core.tinker.EntityEdit;
import dev.sculptory.core.tinker.EntityEdits;
import dev.sculptory.core.tinker.SignText;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Tinker's messages and property pattern (protocol 5). */
class TinkerProtocolTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final UUID ID = new UUID(0x1234, 0x5678);

    /** One of every edit, with values at their edges. */
    static List<EntityEdit> everyEdit() {
        return List.of(new EntityEdit.Pose(EntityEdit.Part.RIGHT_LEG, -360, 0.5f, 360),
                new EntityEdit.Toggle(EntityEdit.Flag.FIXED, true),
                new EntityEdit.Position(-29_999_999.5, -64.0625, 1e-9),
                new EntityEdit.Yaw(-179.5f),
                new EntityEdit.ItemRotation(7),
                new EntityEdit.PaintingVariant("minecraft:skull_and_roses"),
                new EntityEdit.Transformation(new float[] {64, -64, 0.0625f}, DisplayRotation.fromEuler(30, -45, 90),
                        new float[] {-64, 1, 0.5f}),
                new EntityEdit.BillboardMode(EntityEdit.Billboard.HORIZONTAL),
                new EntityEdit.Brightness(15, 0),
                EntityEdit.Brightness.AUTO,
                new EntityEdit.DisplayBlock(STATES.state("minecraft:oak_stairs[facing=west,half=top]")),
                new EntityEdit.DisplayItem("minecraft:diamond_sword"),
                new EntityEdit.DisplayText("Line one\nLigne deux é 中 😀"));
    }

    private static byte[] encode(C2S message) throws ProtocolException {
        return Codec.encodeC2S(message, STATES);
    }

    private static ProtocolException.Reason c2sReason(byte[] frame) {
        return assertThrows(ProtocolException.class, () -> Codec.decodeC2S(frame, STATES)).reason();
    }

    private static ProtocolException.Reason s2cReason(byte[] frame) {
        return assertThrows(ProtocolException.class, () -> Codec.decodeS2C(frame, STATES)).reason();
    }

    /** A TinkerEntity frame with an empty palette, the id, one edit written by {@code edit}. */
    private interface BodyWriter {
        void write(WireWriter out) throws ProtocolException;
    }

    private static byte[] entityFrame(BodyWriter edit) throws ProtocolException {
        WireWriter out = new WireWriter(ProtocolV2.MAX_C2S_FRAME);
        out.varint(MessageType.TINKER_ENTITY.code());
        out.zigzag(7);
        out.count(0, StatePalette.MAX_ENTRIES, "palette");
        out.uuid(ID);
        out.count(1, EntityEdits.MAX_EDITS, "edits");
        edit.write(out);
        return out.toByteArray();
    }

    @Test
    void blockChangesAndSignTextRoundTripWithTheirRequestIdUpFront() throws ProtocolException {
        int straight = STATES.state("minecraft:oak_stairs");
        int outer = STATES.state("minecraft:oak_stairs[shape=outer_left]");
        C2S.TinkerBlock change = new C2S.TinkerBlock(-12, new BlockPos(-30_000_000, -64, 29_999_999), straight, outer, null);
        byte[] frame = encode(change);
        assertEquals(change, Codec.decodeC2S(frame, STATES));
        assertEquals(OptionalInt.of(-12), Codec.peekLeadingId(frame));

        SignText.Side front = new SignText.Side(List.of("Hello", "x".repeat(SignText.MAX_LINE_CHARS), "", "中文 😀"),
                "light_blue", true);
        SignText.Side back = new SignText.Side(List.of("", "", "", "§cred"), "black", false);
        C2S.TinkerBlock sign = new C2S.TinkerBlock(3, BlockPos.ORIGIN, straight, straight, new SignText(front, back));
        C2S decoded = Codec.decodeC2S(encode(sign), STATES);
        assertEquals(sign, decoded);
        assertEquals("red", ((C2S.TinkerBlock) decoded).sign().back().lines().get(3), "cleaned when made");
        assertThrows(IllegalArgumentException.class, () -> new C2S.TinkerBlock(1, BlockPos.ORIGIN, straight, straight, null),
                "a change of nothing");
    }

    @Test
    void everyEntityEditRoundTrips() throws ProtocolException {
        C2S.TinkerEntity edits = new C2S.TinkerEntity(41, ID, everyEdit());
        byte[] frame = encode(edits);
        C2S.TinkerEntity decoded = (C2S.TinkerEntity) Codec.decodeC2S(frame, STATES);
        assertEquals(edits, decoded);
        assertEquals(OptionalInt.of(41), Codec.peekLeadingId(frame));
        C2S.TinkerEntity inspect = new C2S.TinkerEntity(42, ID, List.of());
        assertEquals(inspect, Codec.decodeC2S(encode(inspect), STATES), "no edits: only a look");
        C2S.TinkerEntity most = new C2S.TinkerEntity(43, ID, Collections.nCopies(EntityEdits.MAX_EDITS,
                new EntityEdit.DisplayText("x".repeat(300))));
        assertTrue(encode(most).length <= ProtocolV2.MAX_C2S_FRAME, "the most edits fit a frame");
        assertThrows(IllegalArgumentException.class, () -> new C2S.TinkerEntity(1, ID,
                Collections.nCopies(EntityEdits.MAX_EDITS + 1, new EntityEdit.Yaw(0))));
    }

    @Test
    void resultsRoundTrip() throws ProtocolException {
        for (S2C.TinkerResult result : List.of(S2C.TinkerResult.done(5, new byte[0]),
                S2C.TinkerResult.done(-5, new byte[S2C.TinkerResult.MAX_DATA_BYTES]),
                S2C.TinkerResult.refused(6, RejectReason.PROTECTED, "a claim"),
                S2C.TinkerResult.refused(7, RejectReason.INVALID, ""))) {
            byte[] frame = Codec.encodeS2C(result, STATES);
            assertEquals(result, Codec.decodeS2C(frame, STATES));
            assertEquals(OptionalInt.of(result.reqId()), Codec.peekLeadingId(frame));
        }
        assertThrows(IllegalArgumentException.class, () -> new S2C.TinkerResult(1, RejectReason.INVALID, "", new byte[1]));
        assertThrows(IllegalArgumentException.class,
                () -> S2C.TinkerResult.done(1, new byte[S2C.TinkerResult.MAX_DATA_BYTES + 1]));
        byte[] data = {1, 2, 3};
        S2C.TinkerResult copy = S2C.TinkerResult.done(1, data);
        data[0] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, copy.data(), "copied in");
    }

    @Test
    void thePropertyPatternRoundTripsAndIsStrict() throws ProtocolException {
        Pattern set = new Pattern.SetProperty(STATES.state("minecraft:oak_stairs[shape=inner_right]"), "shape");
        C2S.RunOp op = new C2S.RunOp(9, new OpSpec.Fill(new Region.Cuboid(dev.sculptory.core.Box.of(BlockPos.ORIGIN,
                new BlockPos(3, 3, 3))), set, CellMask.ANY), false, ConflictPolicy.SKIP_CONFLICTS);
        assertEquals(op, Codec.decodeC2S(encode(op), STATES));

        StatePalette.Builder palette = new StatePalette.Builder(STATES);
        WireWriter body = new WireWriter(1024);
        body.u8(CoreCodec.PATTERN_SET_PROPERTY);
        body.varint(palette.indexOf(STATES.state("minecraft:stone")));
        body.string("shape", CoreCodec.PROPERTY_NAME_BYTES, "property");
        WireWriter whole = new WireWriter(2048);
        palette.writeTo(whole);
        whole.raw(body);
        WireReader in = new WireReader(whole.toByteArray());
        StatePalette.Table table = StatePalette.Table.read(in, STATES);
        assertEquals(ProtocolException.Reason.MALFORMED,
                assertThrows(ProtocolException.class, () -> CoreCodec.readPattern(in, table)).reason(),
                "stone has no shape");
    }

    @Test
    void malformedEditsAreRefused() throws ProtocolException {
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(entityFrame(out -> out.u8(12))), "unknown tag");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(entityFrame(out -> {
            out.u8(0);
            out.u8(EntityEdit.Part.values().length);
            out.f32(0);
            out.f32(0);
            out.f32(0);
        })), "unknown part");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(entityFrame(out -> {
            out.u8(0);
            out.u8(0);
            out.f32(Float.NaN);
            out.f32(0);
            out.f32(0);
        })), "a NaN angle");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(entityFrame(out -> {
            out.u8(0);
            out.u8(0);
            out.f32(400);
            out.f32(0);
            out.f32(0);
        })), "past 360");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(entityFrame(out -> {
            out.u8(1);
            out.u8(EntityEdit.Flag.values().length);
            out.bool(true);
        })), "unknown flag");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(entityFrame(out -> {
            out.u8(2);
            out.i64(Double.doubleToRawLongBits(Double.POSITIVE_INFINITY));
            out.i64(0);
            out.i64(0);
        })), "an infinite position");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(entityFrame(out -> {
            out.u8(4);
            out.u8(8);
        })), "item rotation 8");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(entityFrame(out -> {
            out.u8(5);
            out.string("Not an id", 256, "id");
        })), "a variant that is no id");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(entityFrame(out -> {
            out.u8(6);
            for (int i = 0; i < 3; i++) out.f32(0);
            for (int i = 0; i < 4; i++) out.f32(0);
            for (int i = 0; i < 3; i++) out.f32(1);
        })), "a zero quaternion");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(entityFrame(out -> {
            out.u8(6);
            out.f32(65);
            for (int i = 0; i < 2; i++) out.f32(0);
            for (int i = 0; i < 4; i++) out.f32(1);
            for (int i = 0; i < 3; i++) out.f32(1);
        })), "translated too far");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(entityFrame(out -> {
            out.u8(7);
            out.u8(EntityEdit.Billboard.values().length);
        })), "unknown billboard");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(entityFrame(out -> {
            out.u8(8);
            out.zigzag(-1);
            out.zigzag(4);
        })), "half automatic light");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(entityFrame(out -> {
            out.u8(9);
            out.varint(0);
        })), "a palette index past an empty palette");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(entityFrame(out -> {
            out.u8(11);
            out.string("\n".repeat(EntityEdit.MAX_TEXT_LINES), TinkerCodec.TEXT_BYTES, "text");
        })), "too many lines");
        // Over the edit cap.
        WireWriter out = new WireWriter(ProtocolV2.MAX_C2S_FRAME);
        out.varint(MessageType.TINKER_ENTITY.code());
        out.zigzag(7);
        out.count(0, StatePalette.MAX_ENTRIES, "palette");
        out.uuid(ID);
        out.varint(EntityEdits.MAX_EDITS + 1);
        assertEquals(ProtocolException.Reason.TOO_LARGE, c2sReason(out.toByteArray()), "33 edits");
    }

    @Test
    void malformedBlockChangesAndResultsAreRefused() throws ProtocolException {
        int stairs = STATES.state("minecraft:oak_stairs");
        byte[] frame = encode(new C2S.TinkerBlock(1, BlockPos.ORIGIN, stairs, stairs, SignText.EMPTY));
        // type, reqId, palette (count, one state's text), pos (3 zigzag bytes), expected, target, sign flag, the sides
        int colour = frame.length - 1 - 1; // the back side's colour, before its glow
        byte[] badColour = frame.clone();
        badColour[colour] = (byte) SignText.COLORS.size();
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(badColour), "colour 16");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(Arrays.copyOf(frame, frame.length - 1)), "truncated");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(Arrays.copyOf(frame, frame.length + 1)), "trailing");
        // A state the server does not know.
        WireWriter unknown = new WireWriter(1024);
        unknown.varint(MessageType.TINKER_BLOCK.code());
        unknown.zigzag(1);
        unknown.count(2, StatePalette.MAX_ENTRIES, "palette");
        unknown.string("minecraft:oak_stairs", 100, "state");
        unknown.string("minecraft:oak_stairs[half=sideways]", 100, "state");
        CoreCodec.writePos(unknown, BlockPos.ORIGIN);
        unknown.varint(0);
        unknown.varint(1);
        unknown.bool(false);
        assertEquals(ProtocolException.Reason.UNKNOWN_STATE, c2sReason(unknown.toByteArray()));

        byte[] result = Codec.encodeS2C(S2C.TinkerResult.refused(2, RejectReason.INVALID, "x"), STATES);
        byte[] badOutcome = result.clone();
        badOutcome[2] = 2;
        assertEquals(ProtocolException.Reason.MALFORMED, s2cReason(badOutcome), "unknown outcome");
        WireWriter refusedWithData = new WireWriter(1024);
        refusedWithData.varint(MessageType.TINKER_RESULT.code());
        refusedWithData.zigzag(2);
        refusedWithData.u8(TinkerCodec.OUTCOME_REFUSED);
        refusedWithData.enumValue(RejectReason.INVALID);
        refusedWithData.string("", 10, "detail");
        refusedWithData.bytes(new byte[] {1}, 10, "data");
        assertEquals(ProtocolException.Reason.MALFORMED, s2cReason(refusedWithData.toByteArray()), "a refusal with data");
        assertEquals(ProtocolException.Reason.UNKNOWN_TYPE, c2sReason(result), "a server message sent to the server");
    }

    @Test
    void tinkerHasItsOwnRateBucketAndFeature() {
        assertEquals(RateLimiter.Kind.TINKER, RateLimiter.frameKind(MessageType.TINKER_BLOCK));
        assertEquals(RateLimiter.Kind.TINKER, RateLimiter.frameKind(MessageType.TINKER_ENTITY));
        assertEquals(40, MessageType.TINKER_BLOCK.code());
        assertEquals(41, MessageType.TINKER_ENTITY.code());
        assertEquals(90, MessageType.TINKER_RESULT.code());
        assertEquals("tinker", Features.TINKER);
        assertTrue(ProtocolV2.VERSION >= 5, "Tinker ships in protocol 5");
        List<EntityEdit> tags = new ArrayList<>(everyEdit());
        int[] seen = new int[12];
        for (EntityEdit edit : tags) seen[edit.tag()]++;
        for (int tag = 0; tag < seen.length; tag++) assertTrue(seen[tag] > 0, "tag " + tag + " is covered");
    }
}
