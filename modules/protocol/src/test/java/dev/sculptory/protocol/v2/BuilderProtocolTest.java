package dev.sculptory.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.testing.FakeStateSpace;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Builder mode on the wire (protocol 5): {@code BuilderPowers} (44), {@code BuilderPlace} (45), {@code BuilderBreak} (46)
 * and {@code BuilderDragEnd} (47).
 */
class BuilderProtocolTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final Symmetry MIRROR = new Symmetry(Symmetry.Mode.MIRROR_X, 21, -7);

    private static byte[] encode(C2S message) throws ProtocolException {
        return Codec.encodeC2S(message, STATES);
    }

    private static ProtocolException.Reason reason(byte[] frame) {
        return assertThrows(ProtocolException.class, () -> Codec.decodeC2S(frame, STATES)).reason();
    }

    private static C2S.BuilderPlace place(int seq, float hx, float hy, float hz, int powers, Symmetry symmetry) {
        return new C2S.BuilderPlace(seq, false, new BlockPos(10, -60, 30000000), Facing.EAST, hx, hy, hz, powers, symmetry);
    }

    @Test
    void theCodesDirectionsBucketsAndFeatureAreFixed() {
        assertEquals(44, MessageType.BUILDER_POWERS.code());
        assertEquals(45, MessageType.BUILDER_PLACE.code());
        assertEquals(46, MessageType.BUILDER_BREAK.code());
        assertEquals(47, MessageType.BUILDER_DRAG_END.code());
        for (MessageType type : List.of(MessageType.BUILDER_POWERS, MessageType.BUILDER_PLACE, MessageType.BUILDER_BREAK,
                MessageType.BUILDER_DRAG_END)) {
            assertTrue(type.clientToServer() && !type.serverToClient(), type + " is client-to-server only");
        }
        assertEquals(RateLimiter.Kind.BUILDER, RateLimiter.frameKind(MessageType.BUILDER_PLACE));
        assertEquals(RateLimiter.Kind.BUILDER, RateLimiter.frameKind(MessageType.BUILDER_BREAK));
        assertEquals(RateLimiter.Kind.CONTROL, RateLimiter.frameKind(MessageType.BUILDER_POWERS));
        assertEquals(RateLimiter.Kind.CONTROL, RateLimiter.frameKind(MessageType.BUILDER_DRAG_END));
        assertEquals("builder", Features.BUILDER);
        assertEquals(5, ProtocolV2.VERSION);
    }

    @Test
    void powersAreAppendOnlyBits() {
        assertEquals(List.of(BuilderPower.LONG_REACH, BuilderPower.PLACE_IN_AIR, BuilderPower.REPLACE, BuilderPower.BULLDOZER,
                BuilderPower.KEEP_SHAPE, BuilderPower.FORCE_PLACE, BuilderPower.MIRROR, BuilderPower.TINKER),
                List.of(BuilderPower.values()), "wire order");
        assertEquals(0xFF, BuilderPower.ALL);
        assertTrue(BuilderPower.valid(0));
        assertTrue(BuilderPower.valid(BuilderPower.ALL));
        assertFalse(BuilderPower.valid(0x100));
        assertFalse(BuilderPower.valid(-1));
        EnumSet<BuilderPower> some = EnumSet.of(BuilderPower.REPLACE, BuilderPower.TINKER);
        assertEquals(some, BuilderPower.of(BuilderPower.mask(some)));
        assertTrue(BuilderPower.TINKER.in(0x80));
        assertFalse(BuilderPower.TINKER.in(0x7F));
    }

    @Test
    void everyMessageRoundTripsAndCarriesItsSequenceUpFront() throws ProtocolException {
        List<C2S> messages = List.of(
                new C2S.BuilderPowers(0),
                new C2S.BuilderPowers(BuilderPower.ALL),
                place(7, 0f, 0.5f, 1f, 0, Symmetry.NONE),
                place(-3, -C2S.BuilderPlace.HIT_SLACK, 1 + C2S.BuilderPlace.HIT_SLACK, 0.125f,
                        BuilderPower.MIRROR.bit() | BuilderPower.KEEP_SHAPE.bit(), MIRROR),
                new C2S.BuilderPlace(Integer.MAX_VALUE, true, new BlockPos(-30000000, 319, 0), Facing.DOWN, 0.5f, 0f, 0.5f,
                        BuilderPower.PLACE_IN_AIR.bit(), new Symmetry(Symmetry.Mode.ROTATE_4, 3, 5)),
                new C2S.BuilderBreak(Integer.MIN_VALUE, 5, List.of(new BlockPos(1, 2, 3)), 0, Symmetry.NONE, false, true),
                new C2S.BuilderBreak(12, -1, nCells(C2S.BuilderBreak.MAX_CELLS), BuilderPower.BULLDOZER.bit(), MIRROR, true,
                        false),
                new C2S.BuilderDragEnd(0),
                new C2S.BuilderDragEnd(Integer.MIN_VALUE));
        for (C2S message : messages) {
            byte[] frame = encode(message);
            assertEquals(message, Codec.decodeC2S(frame, STATES), message.toString());
            assertEquals(message.type(), Codec.peekType(frame));
            OptionalInt leading = Codec.peekLeadingId(frame);
            switch (message) {
                case C2S.BuilderPlace m -> assertEquals(OptionalInt.of(m.seq()), leading);
                case C2S.BuilderBreak m -> assertEquals(OptionalInt.of(m.seq()), leading);
                case C2S.BuilderDragEnd m -> assertEquals(OptionalInt.of(m.dragId()), leading);
                default -> assertEquals(OptionalInt.empty(), leading);
            }
        }
    }

    @Test
    void theExactWireForms() throws ProtocolException {
        assertArrayEquals(new byte[] {44, (byte) 0x81, 1}, encode(new C2S.BuilderPowers(0x81)));
        // seq 7 | main hand | pos (1, 2, -1) | UP | 0, 1, 0.5 | powers 0 | symmetry off
        assertArrayEquals(new byte[] {45, 14, 0, 2, 4, 1, 0, 0, 0, 0, 0, 0x3F, (byte) 0x80, 0, 0, 0x3F, 0, 0, 0, 0, 0},
                encode(new C2S.BuilderPlace(7, false, new BlockPos(1, 2, -1), Facing.UP, 0f, 1f, 0.5f, 0, Symmetry.NONE)));
        // seq 1 | drag 2 | one cell (0, 0, 0) | bulldozer | symmetry off | same kind | not last
        assertArrayEquals(new byte[] {46, 2, 4, 1, 0, 0, 0, 8, 0, 1, 0},
                encode(new C2S.BuilderBreak(1, 2, List.of(BlockPos.ORIGIN), BuilderPower.BULLDOZER.bit(), Symmetry.NONE,
                        true, false)));
        assertArrayEquals(new byte[] {47, 3}, encode(new C2S.BuilderDragEnd(-2)));
    }

    @Test
    void invalidValuesAreRefusedByTheRecordsAndMalformedOnTheWire() throws ProtocolException {
        assertThrows(IllegalArgumentException.class, () -> new C2S.BuilderPowers(0x100));
        assertThrows(IllegalArgumentException.class, () -> new C2S.BuilderPowers(-1));
        for (float bad : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.01f, 1.01f}) {
            assertThrows(IllegalArgumentException.class, () -> place(1, bad, 0.5f, 0.5f, 0, Symmetry.NONE), "x " + bad);
            assertThrows(IllegalArgumentException.class, () -> place(1, 0.5f, bad, 0.5f, 0, Symmetry.NONE), "y " + bad);
            assertThrows(IllegalArgumentException.class, () -> place(1, 0.5f, 0.5f, bad, 0, Symmetry.NONE), "z " + bad);
        }
        assertThrows(IllegalArgumentException.class, () -> place(1, 0.5f, 0.5f, 0.5f, 0x100, Symmetry.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new C2S.BuilderBreak(1, 1, List.of(), 0, Symmetry.NONE, false, true));
        assertThrows(IllegalArgumentException.class,
                () -> new C2S.BuilderBreak(1, 1, nCells(C2S.BuilderBreak.MAX_CELLS + 1), 0, Symmetry.NONE, false, true));
        assertThrows(IllegalArgumentException.class,
                () -> new C2S.BuilderBreak(1, 1, List.of(BlockPos.ORIGIN), 1 << 8, Symmetry.NONE, false, true));
        assertThrows(NullPointerException.class,
                () -> new C2S.BuilderBreak(1, 1, java.util.Arrays.asList((BlockPos) null), 0, Symmetry.NONE, false, true));

        // On the wire the same values are malformed, never an exception escaping the decoder.
        assertEquals(ProtocolException.Reason.MALFORMED, reason(new byte[] {44, (byte) 0x80, 2}), "power bit 8");
        byte[] good = encode(place(1, 0.5f, 0.5f, 0.5f, 0, Symmetry.NONE));
        int hitX = good.length - 14; // f32 x, y, z | varint powers | symmetry mode
        for (float bad : new float[] {Float.NaN, 1.5f, -2f}) {
            byte[] frame = good.clone();
            int bits = Float.floatToRawIntBits(bad);
            frame[hitX] = (byte) (bits >>> 24);
            frame[hitX + 1] = (byte) (bits >>> 16);
            frame[hitX + 2] = (byte) (bits >>> 8);
            frame[hitX + 3] = (byte) bits;
            assertEquals(ProtocolException.Reason.MALFORMED, reason(frame), "hit x " + bad);
        }
        byte[] side = good.clone();
        side[hitX - 1] = 6; // the side, one past WEST
        assertEquals(ProtocolException.Reason.MALFORMED, reason(side), "unknown side");
        assertEquals(ProtocolException.Reason.MALFORMED, reason(new byte[] {46, 2, 4, 0, 0, 0, 1, 1}), "no cells");
        assertEquals(ProtocolException.Reason.TOO_LARGE, reason(new byte[] {46, 2, 4, 17}), "17 cells");
    }

    @Test
    void truncatedFramesAndTrailingBytesAreMalformed() throws ProtocolException {
        List<C2S> messages = List.of(new C2S.BuilderPowers(3), place(300, 0.25f, 0.75f, 1f, 0x7F, MIRROR),
                new C2S.BuilderBreak(9, 8, nCells(3), BuilderPower.ALL, MIRROR, true, true), new C2S.BuilderDragEnd(77));
        for (C2S message : messages) {
            byte[] frame = encode(message);
            for (int length = 1; length < frame.length; length++) {
                assertEquals(ProtocolException.Reason.MALFORMED, reason(Arrays.copyOf(frame, length)),
                        message.type() + " cut to " + length);
            }
            byte[] longer = Arrays.copyOf(frame, frame.length + 1);
            assertEquals(ProtocolException.Reason.MALFORMED, reason(longer), message.type() + " with a trailing byte");
        }
    }

    @Test
    void randomBytesAfterABuilderTypeNeverEscapeTheDecoder() {
        Random random = new Random(20260929L);
        for (int i = 0; i < 20_000; i++) {
            byte[] frame = new byte[1 + random.nextInt(60)];
            random.nextBytes(frame);
            frame[0] = (byte) (44 + random.nextInt(4));
            try {
                C2S decoded = Codec.decodeC2S(frame, STATES);
                // Whatever decodes encodes and decodes back to itself.
                assertEquals(decoded, Codec.decodeC2S(encode(decoded), STATES));
            } catch (ProtocolException expected) {
                // Refused cleanly.
            }
        }
    }

    private static List<BlockPos> nCells(int count) {
        List<BlockPos> cells = new ArrayList<>();
        for (int i = 0; i < count; i++) cells.add(new BlockPos(i, -i, i * 1000));
        return cells;
    }
}
