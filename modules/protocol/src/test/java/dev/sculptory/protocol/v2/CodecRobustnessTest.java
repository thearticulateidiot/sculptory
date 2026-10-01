package dev.sculptory.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import dev.sculptory.core.brush.ShapeSpec;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.brush.SurfacePlane;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.MixLayout;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.transform.Mirror;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CodecRobustnessTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final Box BOX = Box.of(new BlockPos(0, 0, 0), new BlockPos(3, 3, 3));
    /** Fuzz inputs per decoder and per strategy (random bytes, mutated valid frames). */
    static final int FUZZ_ITERATIONS = 10_000;

    @FunctionalInterface
    interface Decoder {
        Message decode(byte[] frame) throws ProtocolException;
    }

    private static final Decoder C2S_DECODER = frame -> Codec.decodeC2S(frame, STATES);
    private static final Decoder S2C_DECODER = frame -> Codec.decodeS2C(frame, STATES);

    private static ProtocolException.Reason reasonOf(Decoder decoder, byte[] frame) {
        try {
            decoder.decode(frame);
        } catch (ProtocolException e) {
            return e.reason();
        }
        return fail("Expected a ProtocolException for " + HexFormat.of().formatHex(frame));
    }

    private static byte[] frame(int... bytes) {
        byte[] out = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) out[i] = (byte) bytes[i];
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) size += part.length;
        byte[] out = new byte[size];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    /** Decoding either succeeds (and then re-encodes to the same message) or throws ProtocolException. */
    private static boolean decodesOnlyWithProtocolExceptions(Decoder decoder, boolean c2s, byte[] frame) {
        Message message;
        try {
            message = decoder.decode(frame);
        } catch (ProtocolException expected) {
            return false;
        } catch (Throwable t) {
            throw new AssertionError("Decoder threw " + t + " for " + HexFormat.of().formatHex(frame), t);
        }
        try {
            byte[] again = c2s ? Codec.encodeC2S((C2S) message, STATES) : Codec.encodeS2C((S2C) message, STATES);
            assertEquals(message, decoder.decode(again), "re-encoding a decoded frame is stable");
        } catch (ProtocolException tooLarge) {
            assertEquals(ProtocolException.Reason.TOO_LARGE, tooLarge.reason());
        }
        return true;
    }

    // ---------------------------------------------------------------- targeted failures

    @Test
    void truncatedInputAtEveryLengthIsRejected() throws ProtocolException {
        RandomMessages random = new RandomMessages(5L, STATES);
        List<Message> messages = new ArrayList<>(MessageTypeTest.samples());
        for (int i = 0; i < 200; i++) {
            messages.add(random.c2s());
            messages.add(random.s2c());
        }
        for (Message message : messages) {
            boolean c2s = message instanceof C2S;
            byte[] frame = c2s ? Codec.encodeC2S((C2S) message, STATES) : Codec.encodeS2C((S2C) message, STATES);
            Decoder decoder = c2s ? C2S_DECODER : S2C_DECODER;
            // Incompatible's server build is optional (older clients decode the two protocol numbers only), so the frame
            // cut just before it is that Incompatible without a build.
            int optionalTail = message instanceof S2C.Incompatible m && !m.serverBuild().isEmpty()
                    ? Codec.encodeS2C(new S2C.Incompatible(m.serverMinProtocol(), m.serverMaxProtocol()), STATES).length
                    : -1;
            for (int length = 0; length < frame.length; length++) {
                byte[] prefix = Arrays.copyOf(frame, length);
                if (length == optionalTail) {
                    S2C.Incompatible m = (S2C.Incompatible) message;
                    assertEquals(new S2C.Incompatible(m.serverMinProtocol(), m.serverMaxProtocol()), S2C_DECODER.decode(prefix));
                    continue;
                }
                ProtocolException.Reason reason = reasonOf(decoder, prefix);
                assertTrue(reason == ProtocolException.Reason.MALFORMED, message.type() + " cut to " + length + ": " + reason);
            }
        }
    }

    @Test
    void trailingBytesAreRejected() throws ProtocolException {
        byte[] frame = Codec.encodeC2S(new C2S.StrokeEnd(3), STATES);
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, concat(frame, frame(0))));
        byte[] welcome = Codec.encodeS2C(new S2C.Welcome(4, Features.NONE, Limits.DEFAULTS, PermissionMask.NONE, 1, "b"),
                STATES);
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(S2C_DECODER, concat(welcome, frame(1, 2))));
        // Incompatible's optional server build is the last thing it may carry.
        byte[] incompatible = Codec.encodeS2C(new S2C.Incompatible(2, 2, "0.2.0-dev+abc"), STATES);
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(S2C_DECODER, concat(incompatible, frame(1, 2))));
    }

    /**
     * A client before protocol 5 (a v4 build from before build ids) decodes {@code Incompatible} as its two protocol
     * numbers and nothing after them, so the server leaves its build id out for such clients (and for everyone when it
     * has none): the body is then exactly what such clients read, {@code 65 | zigzag 5 | zigzag 5}.
     */
    @Test
    void incompatibleForOldClientsIsJustTheProtocolRange() throws ProtocolException {
        C2S.Hello v4 = new C2S.Hello(4, 4, "0.2.0-dev", Features.NONE);
        S2C.Incompatible toOld = (S2C.Incompatible) Handshake.answer(v4, Features.NONE, Limits.DEFAULTS,
                PermissionMask.NONE, 1L, "0.2.0-dev+new");
        assertEquals("", toOld.serverBuild());
        assertArrayEquals(frame(MessageType.INCOMPATIBLE.code(), 10, 10), Codec.encodeS2C(toOld, STATES));

        C2S.Hello v9 = new C2S.Hello(9, 9, "9.0.0", Features.NONE);
        S2C.Incompatible toNew = (S2C.Incompatible) Handshake.answer(v9, Features.NONE, Limits.DEFAULTS,
                PermissionMask.NONE, 1L, "0.2.0-dev+new");
        assertEquals("0.2.0-dev+new", toNew.serverBuild());
        assertEquals(toNew, Codec.decodeS2C(Codec.encodeS2C(toNew, STATES), STATES));
    }

    /**
     * An old v4 server answers this client's {@code Hello} with {@code Incompatible(4, 4)} and nothing more
     * ({@code 65 | 8 | 8}); it decodes, without a build. A build tail must not be empty: one encoding per message.
     */
    @Test
    void anOldServersIncompatibleDecodesAndAnEmptyBuildTailIsRefused() throws ProtocolException {
        assertEquals(new S2C.Incompatible(4, 4), Codec.decodeS2C(frame(MessageType.INCOMPATIBLE.code(), 8, 8), STATES));
        assertEquals(ProtocolException.Reason.MALFORMED,
                reasonOf(S2C_DECODER, frame(MessageType.INCOMPATIBLE.code(), 10, 10, 0)), "an empty build tail");
    }

    @Test
    void oversizeFramesAreRejectedBeforeDecoding() {
        byte[] c2s = new byte[ProtocolV2.MAX_C2S_FRAME + 1];
        c2s[0] = (byte) MessageType.STREAM_CHUNK.code();
        assertEquals(ProtocolException.Reason.TOO_LARGE, reasonOf(C2S_DECODER, c2s));
        byte[] s2c = new byte[ProtocolV2.MAX_S2C_FRAME + 1];
        s2c[0] = (byte) MessageType.STREAM_CHUNK.code();
        assertEquals(ProtocolException.Reason.TOO_LARGE, reasonOf(S2C_DECODER, s2c));
    }

    @Test
    void oversizeMessagesFailToEncode() {
        List<NamespacedId> ids = new ArrayList<>();
        for (int i = 0; i < CellMask.MAX_LIST; i++) ids.add(new NamespacedId("somemod:" + "x".repeat(40) + i));
        C2S.Copy copy = new C2S.Copy(1, BOX, BlockPos.ORIGIN, false, new CellMask.Blocks(ids));
        ProtocolException e = assertThrows(ProtocolException.class, () -> Codec.encodeC2S(copy, STATES));
        assertEquals(ProtocolException.Reason.TOO_LARGE, e.reason());

        StreamChunk chunk = new StreamChunk(1, 0, new byte[ProtocolV2.MAX_S2C_FRAME]);
        e = assertThrows(ProtocolException.class, () -> Codec.encodeS2C(chunk, STATES));
        assertEquals(ProtocolException.Reason.TOO_LARGE, e.reason());

        S2C.Notice notice = new S2C.Notice(S2C.Notice.Level.INFO, "k".repeat(Codec.MAX_NAME_BYTES + 1), List.of());
        e = assertThrows(ProtocolException.class, () -> Codec.encodeS2C(notice, STATES));
        assertEquals(ProtocolException.Reason.TOO_LARGE, e.reason());
    }

    @Test
    void maximumChunksFitTheirFrames() throws ProtocolException {
        StreamChunk c2s = new StreamChunk(Integer.MIN_VALUE, Integer.MAX_VALUE, new byte[StreamSender.MAX_C2S_CHUNK]);
        assertTrue(Codec.encodeC2S(c2s, STATES).length <= ProtocolV2.MAX_C2S_FRAME);
        StreamChunk s2c = new StreamChunk(Integer.MIN_VALUE, Integer.MAX_VALUE, new byte[StreamSender.MAX_S2C_CHUNK]);
        assertEquals(s2c, Codec.decodeS2C(Codec.encodeS2C(s2c, STATES), STATES));
    }

    @Test
    void hugeVarintsAreRejected() {
        int stroke = MessageType.STROKE_END.code();
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, frame(stroke, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01)));
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, frame(stroke, 0xFF, 0xFF, 0xFF, 0xFF, 0x1F)));
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, frame(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x7F)));
        byte[] longValue = new byte[13];
        longValue[0] = (byte) MessageType.STREAM_CREDIT.code();
        longValue[1] = 2;
        Arrays.fill(longValue, 2, 13, (byte) 0xFF);
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, longValue));
    }

    @Test
    void negativeAndHugeLengthsAreRejectedWithoutAllocating() {
        int abort = MessageType.STREAM_ABORT.code();
        // String length 0xFFFFFFFF reads as -1.
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, frame(abort, 2, 0xFF, 0xFF, 0xFF, 0xFF, 0x0F)));
        // String length Integer.MAX_VALUE: over the cap.
        assertEquals(ProtocolException.Reason.TOO_LARGE, reasonOf(C2S_DECODER, frame(abort, 2, 0xFF, 0xFF, 0xFF, 0xFF, 0x07)));
        // A within-cap length the frame cannot hold.
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, frame(abort, 2, 100, 'a', 'b')));
        // Stream chunk claiming 2^31-1 bytes, and one claiming more than the frame holds.
        assertEquals(ProtocolException.Reason.TOO_LARGE,
                reasonOf(C2S_DECODER, frame(MessageType.STREAM_CHUNK.code(), 2, 0, 0xFF, 0xFF, 0xFF, 0xFF, 0x07)));
        assertEquals(ProtocolException.Reason.MALFORMED,
                reasonOf(C2S_DECODER, frame(MessageType.STREAM_CHUNK.code(), 2, 0, 0x80, 0x01, 1, 2, 3)));
        // Library listing claiming 2^31-1 entries.
        assertEquals(ProtocolException.Reason.TOO_LARGE,
                reasonOf(S2C_DECODER, frame(MessageType.LIBRARY_LISTING.code(), 2, 0, 0xFF, 0xFF, 0xFF, 0xFF, 0x07)));
        // Features count within cap but beyond the frame.
        assertEquals(ProtocolException.Reason.MALFORMED,
                reasonOf(C2S_DECODER, frame(MessageType.HELLO.code(), 4, 4, 0, 60)));
    }

    @Test
    void listsOverTheirCapsAreRejected() throws ProtocolException {
        byte[] dabs = Codec.encodeC2S(new C2S.Dabs(1, 1, List.of(new dev.sculptory.core.brush.Dab(0, 0, 0, 0, 0))), STATES);
        dabs[3] = 17; // count byte after type, strokeId, seq
        assertEquals(ProtocolException.Reason.TOO_LARGE, reasonOf(C2S_DECODER, dabs));

        byte[] hello = Codec.encodeC2S(Handshake.hello("1", Features.NONE), STATES);
        hello[hello.length - 1] = (byte) (Features.MAX_FEATURES + 1);
        assertEquals(ProtocolException.Reason.TOO_LARGE, reasonOf(C2S_DECODER, hello));

        // A palette over its cap.
        byte[] palette = frame(MessageType.RUN_OP.code(), 2, 0x81, 0x20);
        assertEquals(ProtocolException.Reason.TOO_LARGE, reasonOf(C2S_DECODER, palette));
    }

    @Test
    void invalidEnumOrdinalsTagsAndFieldValuesAreMalformed() throws ProtocolException {
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, frame(MessageType.UNDO.code(), 2, 2)));
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(S2C_DECODER, frame(MessageType.JOB_REJECTED.code(), 2, 99)));
        byte[] erase = Codec.encodeC2S(new C2S.RunOp(1, new OpSpec.Erase(BOX, CellMask.ANY), false,
                ConflictPolicy.SKIP_CONFLICTS), STATES);
        byte[] badTag = erase.clone();
        badTag[3] = 42; // op tag after type, reqId, empty palette
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, badTag));
        byte[] badBool = erase.clone();
        badBool[badBool.length - 3] = 7; // physics, before the policy and the label
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, badBool));
        // The tool label ends a RunOp: an unknown one is malformed, and it is required.
        byte[] badLabel = erase.clone();
        badLabel[badLabel.length - 1] = (byte) OpLabel.values().length;
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, badLabel));
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, Arrays.copyOf(erase, erase.length - 1)));
        byte[] carve = erase.clone();
        carve[carve.length - 1] = (byte) OpLabel.CARVE.ordinal();
        assertEquals(OpLabel.CARVE, ((C2S.RunOp) Codec.decodeC2S(carve, STATES)).label());
        // An inverted box (min > max) is rejected by Box itself.
        byte[] inverted = frame(MessageType.RESYNC.code(), 10, 0, 0, 0, 0, 0);
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, inverted));
        // A transform byte with an unknown mirror.
        byte[] paste = Codec.encodeC2S(new C2S.RunOp(1, new OpSpec.Paste(new dev.sculptory.core.edit.SourceRef.Clipboard(
                new java.util.UUID(0, 0)), BlockPos.ORIGIN, new dev.sculptory.core.transform.Transform(1, Mirror.Z),
                dev.sculptory.core.edit.PasteOptions.DEFAULT), false, ConflictPolicy.SKIP_CONFLICTS), STATES);
        int transformAt = paste.length - 7; // transform, paste options, into, symmetry (Off), physics, policy, label
        assertEquals((1 | (Mirror.Z.ordinal() << 2)), paste[transformAt]);
        paste[transformAt] = (byte) 0xFC;
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, paste));
        // Protocol 5: bit 4 is the flip upside down; bits 5-7 must be zero.
        paste[transformAt] = (byte) (1 | (Mirror.Z.ordinal() << 2) | 0x10);
        C2S.RunOp flipped = (C2S.RunOp) Codec.decodeC2S(paste, STATES);
        assertEquals(new dev.sculptory.core.transform.Transform(1, Mirror.Z, true), ((OpSpec.Paste) flipped.op()).t());
        for (int high : new int[] {0x20, 0x40, 0x80}) {
            paste[transformAt] = (byte) (1 | high);
            assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, paste), "bit " + high);
        }
        // A stack op ends with its upside-down bool; any other byte there is malformed.
        byte[] stack = Codec.encodeC2S(new C2S.RunOp(2, new OpSpec.Stack(new dev.sculptory.core.region.Region.Cuboid(
                new Box(BlockPos.ORIGIN, new BlockPos(2, 2, 2))), 3, 0, 0, 2, dev.sculptory.core.entity.EntityFilter.NONE,
                Symmetry.NONE, dev.sculptory.core.edit.PasteOptions.Into.EVERYTHING, true), false,
                ConflictPolicy.SKIP_CONFLICTS), STATES);
        int flagAt = stack.length - 4; // upside down, physics, policy, label
        assertEquals(1, stack[flagAt]);
        assertTrue(((OpSpec.Stack) ((C2S.RunOp) Codec.decodeC2S(stack, STATES)).op()).upsideDown());
        stack[flagAt] = 2;
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, stack));
        // A NaN brush strength is rejected by BrushSpec.
        BrushSpec spec = new BrushSpec(BrushTool.RAISE, 4, 0.5f, Falloff.LINEAR, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 0);
        byte[] begin = Codec.encodeC2S(new C2S.StrokeBegin(1, spec), STATES);
        int strengthAt = 5; // type, strokeId, empty palette, tool, radius
        assertEquals(0x3F, begin[strengthAt] & 0xFF);
        begin[strengthAt] = 0x7F;
        begin[strengthAt + 1] = (byte) 0xC0;
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, begin));
    }

    @Test
    void brushClipBoxesRoundTripAndMalformedOnesAreRejected() throws ProtocolException {
        BrushSpec free = new BrushSpec(BrushTool.LOWER, 4, 0.5f, Falloff.LINEAR, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 0);
        Box clip = new Box(new BlockPos(-3, -64, 5), new BlockPos(10, 80, 7));
        for (Box box : List.of(clip, Box.of(new BlockPos(1, 2, 3)))) {
            C2S.StrokeBegin begin = new C2S.StrokeBegin(1, free.withClip(box));
            assertEquals(begin, Codec.decodeC2S(Codec.encodeC2S(begin, STATES), STATES));
        }
        // Without a clip the frame ends with its presence flag (0) and the symmetry (Off, 0); replace them with each
        // malformed tail.
        byte[] head = Codec.encodeC2S(new C2S.StrokeBegin(1, free), STATES);
        assertEquals(0, head[head.length - 2]);
        assertEquals(0, head[head.length - 1]);
        head = Arrays.copyOf(head, head.length - 2);
        assertEquals(new C2S.StrokeBegin(1, free), Codec.decodeC2S(concat(head, frame(0, 0)), STATES));
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, concat(head, frame(2, 0))), "presence flag 2");
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, concat(head, frame(1))), "flag without a box");
        int far = BrushSpec.CLIP_MAX_HORIZONTAL + 1;
        int[][] malformed = {
            {5, 0, 0, 4, 0, 0},                                  // min x > max x
            {0, 9, 0, 0, 8, 0},                                  // min y > max y
            {0, 0, 1, 0, 0, 0},                                  // min z > max z
            {0, 0, 0, far, 0, 0},                                // beyond the horizontal range
            {-far, 0, 0, 0, 0, 0},
            {0, 0, 0, 0, BrushSpec.CLIP_MAX_Y + 1, 0},           // beyond the vertical range
            {0, -BrushSpec.CLIP_MAX_Y - 1, 0, 0, 0, 0},
        };
        for (int[] corners : malformed) {
            WireWriter tail = new WireWriter(64);
            tail.u8(1);
            for (int value : corners) tail.zigzag(value);
            tail.u8(0);
            assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, concat(head, tail.toByteArray())),
                    Arrays.toString(corners));
        }
        // An unknown surface mask tag in the brush (type, strokeId, empty palette, tool, radius, strength x4,
        // falloff, shape, no material, then the mask tag).
        byte[] begin = Codec.encodeC2S(new C2S.StrokeBegin(1, free.withClip(clip)), STATES);
        int maskAt = 12;
        assertEquals(CoreCodec.SURFACE_ANY, begin[maskAt]);
        begin[maskAt] = 42;
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, begin));
        assertEquals(1, Codec.peekLeadingId(begin).getAsInt(), "the stroke id stays readable for the refusal");
    }

    @Test
    void brushSymmetryRoundTripsAndMalformedOnesAreRejected() throws ProtocolException {
        BrushSpec free = new BrushSpec(BrushTool.SMOOTH, 6, 0.5f, Falloff.LINEAR, Shape.SQUARE, null, SurfaceMask.ANY, 0, 0, 0);
        int max = Symmetry.MAX_CENTRE2;
        Box clip = new Box(new BlockPos(-3, -64, 5), new BlockPos(10, 80, 7));
        for (Symmetry.Mode mode : Symmetry.Mode.values()) {
            for (int[] centre : new int[][] {{0, 0}, {21, -7}, {-40, 12}, {max, -max}, {-max, max - 1}}) {
                if (mode == Symmetry.Mode.ROTATE_4 && ((centre[0] ^ centre[1]) & 1) != 0) continue;
                Symmetry symmetry = new Symmetry(mode, centre[0], centre[1]);
                for (Box box : java.util.Arrays.asList(null, clip)) {
                    C2S.StrokeBegin begin = new C2S.StrokeBegin(2, free.withClip(box).withSymmetry(symmetry));
                    assertEquals(begin, Codec.decodeC2S(Codec.encodeC2S(begin, STATES), STATES), symmetry + " " + box);
                }
            }
        }
        // Off is one byte (0) with no centre; replace it with each tail.
        byte[] head = Codec.encodeC2S(new C2S.StrokeBegin(2, free), STATES);
        assertEquals(0, head[head.length - 1]);
        head = Arrays.copyOf(head, head.length - 1);
        WireWriter mirror = new WireWriter(16);
        mirror.varint(Symmetry.Mode.MIRROR_X.ordinal());
        mirror.zigzag(21);
        mirror.zigzag(-7);
        assertEquals(new C2S.StrokeBegin(2, free.withSymmetry(new Symmetry(Symmetry.Mode.MIRROR_X, 21, -7))),
                Codec.decodeC2S(concat(head, mirror.toByteArray()), STATES));
        int count = Symmetry.Mode.values().length;
        List<int[]> malformed = List.of(
                new int[] {count},                                                // an unknown mode
                new int[] {count + 100, 0, 0},
                new int[] {Symmetry.Mode.MIRROR_X.ordinal()},                      // no centre
                new int[] {Symmetry.Mode.MIRROR_Z.ordinal(), 3},                   // half a centre
                new int[] {Symmetry.Mode.MIRROR_X.ordinal(), max + 1, 0},          // beyond the range
                new int[] {Symmetry.Mode.ROTATE_2.ordinal(), 0, -max - 1},
                new int[] {Symmetry.Mode.ROTATE_4.ordinal(), 21, -8},              // a block centre in x, an edge in z
                new int[] {Symmetry.Mode.OFF.ordinal(), 21, -7});                  // Off carries no centre: trailing bytes
        for (int[] values : malformed) {
            WireWriter tail = new WireWriter(32);
            tail.varint(values[0]);
            for (int i = 1; i < values.length; i++) tail.zigzag(values[i]);
            assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, concat(head, tail.toByteArray())),
                    Arrays.toString(values));
        }
        byte[] refused = concat(head, frame(count));
        assertEquals(2, Codec.peekLeadingId(refused).getAsInt(), "the stroke id stays readable for the refusal");
    }

    @Test
    void surfaceModeBrushesRoundTripAndMalformedOnesAreRejected() throws ProtocolException {
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 21, -7);
        Box clip = new Box(new BlockPos(-3, -64, 5), new BlockPos(10, 80, 7));
        for (BrushTool tool : List.of(BrushTool.RAISE, BrushTool.LOWER, BrushTool.SMOOTH, BrushTool.FLATTEN)) {
            BrushSpec terrain = new BrushSpec(tool, 7, 0.6f, Falloff.SMOOTH, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 64, 5L,
                    clip, mirror);
            List<SurfacePlane> planes = tool != BrushTool.FLATTEN ? java.util.Collections.singletonList(null) : List.of(
                    new SurfacePlane(Facing.UP, 64), new SurfacePlane(Facing.DOWN, -BrushSpec.CLIP_MAX_Y),
                    new SurfacePlane(Facing.EAST, BrushSpec.CLIP_MAX_HORIZONTAL),
                    new SurfacePlane(Facing.NORTH, -BrushSpec.CLIP_MAX_HORIZONTAL));
            for (C2S.StrokeBegin begin : List.of(new C2S.StrokeBegin(4, terrain))) {
                assertEquals(begin, Codec.decodeC2S(Codec.encodeC2S(begin, STATES), STATES));
            }
            for (SurfacePlane plane : planes) {
                C2S.StrokeBegin begin = new C2S.StrokeBegin(4, terrain.withSurface(plane));
                assertEquals(begin, Codec.decodeC2S(Codec.encodeC2S(begin, STATES), STATES), tool + " " + plane);
            }
        }
        // The mode follows flattenY: splice other bytes in between flattenY and the seed of a Terrain-mode frame.
        BrushSpec flatten = new BrushSpec(BrushTool.FLATTEN, 5, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY,
                0, 12345, 987654321L);
        BrushSpec paint = new BrushSpec(BrushTool.PAINT, 5, 1f, Falloff.CONSTANT, Shape.CIRCLE, new Pattern.Single(1),
                SurfaceMask.ANY, 1, 12345, 987654321L);
        int surface = SculptMode.SURFACE.ordinal();
        assertEquals(new C2S.StrokeBegin(4, flatten.withSurface(new SurfacePlane(Facing.EAST, -9))),
                Codec.decodeC2S(splice(flatten, surface, Facing.EAST.ordinal(), -9), STATES));
        List<int[]> malformed = List.of(
                new int[] {SculptMode.values().length},                            // an unknown mode
                new int[] {surface},                                               // no plane: the seed is misread as one
                new int[] {surface, Facing.values().length, 3},                    // an unknown facing
                new int[] {surface, Facing.UP.ordinal(), BrushSpec.CLIP_MAX_Y + 1}, // beyond the range
                new int[] {surface, Facing.WEST.ordinal(), -BrushSpec.CLIP_MAX_HORIZONTAL - 1});
        for (int[] values : malformed) {
            assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, splice(flatten, values)),
                    Arrays.toString(values));
        }
        // Paint, Palette Paint and the Shape brush have no Surface mode.
        BrushSpec palette = new BrushSpec(BrushTool.PALETTE, 5, 1f, Falloff.CONSTANT, Shape.CIRCLE,
                new Pattern.Weighted(new int[] {1, 2}, new int[] {1, 1}, 3L), SurfaceMask.ANY, 1, 12345, 987654321L);
        BrushSpec shape = new BrushSpec(BrushTool.SHAPE, 5, 1f, Falloff.CONSTANT, Shape.CIRCLE, new Pattern.Single(1),
                SurfaceMask.ANY, 0, 12345, 987654321L, null, Symmetry.NONE,
                new ShapeSpec(ShapeSpec.Kind.SPHERE, 5, Facing.UP, ShapeSpec.Mode.PLACE, 0));
        for (BrushSpec other : List.of(paint, palette, shape)) {
            assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, splice(other, surface)), other.tool().name());
        }
    }

    /**
     * {@code spec}'s StrokeBegin frame with the sculpt mode byte (between flattenY 12345 and seed 987654321) replaced by
     * {@code values}: the first as a varint, the others as zigzag varints, except that a second value is a facing (varint).
     */
    private static byte[] splice(BrushSpec spec, int... values) throws ProtocolException {
        byte[] frame = Codec.encodeC2S(new C2S.StrokeBegin(4, spec), STATES);
        WireWriter marker = new WireWriter(16);
        marker.zigzag(12345);
        marker.varint(0);
        marker.zigzagLong(987654321L);
        byte[] pattern = marker.toByteArray();
        int at = -1;
        for (int i = 0; i + pattern.length <= frame.length && at < 0; i++) {
            if (Arrays.equals(Arrays.copyOfRange(frame, i, i + pattern.length), pattern)) at = i;
        }
        assertTrue(at >= 0, "flattenY, mode and seed are not in the frame");
        WireWriter middle = new WireWriter(32);
        middle.zigzag(12345);
        middle.varint(values[0]);
        if (values.length > 1) middle.varint(values[1]);
        for (int i = 2; i < values.length; i++) middle.zigzag(values[i]);
        middle.zigzagLong(987654321L);
        return concat(Arrays.copyOfRange(frame, 0, at), middle.toByteArray(),
                Arrays.copyOfRange(frame, at + pattern.length, frame.length));
    }

    @Test
    void shapeBrushesRoundTripAndMalformedOnesAreRejected() throws ProtocolException {
        Box clip = new Box(new BlockPos(-3, -64, 5), new BlockPos(10, 80, 7));
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_XZ, 21, -7);
        Pattern stone = new Pattern.Single(1);
        for (ShapeSpec.Kind kind : ShapeSpec.Kind.values()) {
            for (Facing facing : Facing.values()) {
                for (ShapeSpec.Mode mode : ShapeSpec.Mode.values()) {
                    for (int[] sizes : new int[][] {{1, 1, 0}, {32, ShapeSpec.MAX_HEIGHT, ShapeSpec.MAX_HOLLOW}, {5, 8, 2}}) {
                        ShapeSpec shape = new ShapeSpec(kind, sizes[1], facing, mode, sizes[2]);
                        for (Pattern material : java.util.Arrays.asList(stone, null)) {
                            if (material == null && shape.usesMaterial()) continue;
                            BrushSpec spec = BrushSpec.shape(sizes[0], shape, material, -9L, sizes[0] == 5 ? clip : null,
                                    sizes[0] == 32 ? mirror : Symmetry.NONE);
                            C2S.StrokeBegin begin = new C2S.StrokeBegin(3, spec);
                            assertEquals(begin, Codec.decodeC2S(Codec.encodeC2S(begin, STATES), STATES), spec.toString());
                        }
                    }
                }
            }
        }
        // A carving sphere without material ends with symmetry Off (0) and the shape's five fields; replace them.
        BrushSpec carve = BrushSpec.shape(4, new ShapeSpec(ShapeSpec.Kind.SPHERE, 9, Facing.UP, ShapeSpec.Mode.CARVE, 0),
                null, 0L, null, Symmetry.NONE);
        byte[] head = Codec.encodeC2S(new C2S.StrokeBegin(3, carve), STATES);
        assertArrayEquals(new byte[] {0, 0, 9, 0, (byte) ShapeSpec.Mode.CARVE.ordinal(), 0},
                Arrays.copyOfRange(head, head.length - 6, head.length));
        head = Arrays.copyOf(head, head.length - 5);
        int kinds = ShapeSpec.Kind.values().length, facings = Facing.values().length;
        int modes = ShapeSpec.Mode.values().length, carving = ShapeSpec.Mode.CARVE.ordinal();
        assertEquals(new C2S.StrokeBegin(3, carve), Codec.decodeC2S(concat(head, frame(0, 9, 0, carving, 0)), STATES));
        List<int[]> malformed = List.of(
                new int[] {kinds, 9, 0, carving, 0},                               // an unknown kind
                new int[] {0, 9, facings, carving, 0},                             // an unknown facing
                new int[] {0, 9, 0, modes, 0},                                     // an unknown mode
                new int[] {0, 0, 0, carving, 0},                                   // height 0
                new int[] {0, ShapeSpec.MAX_HEIGHT + 1, 0, carving, 0},            // too tall
                new int[] {0, 9, 0, carving, ShapeSpec.MAX_HOLLOW + 1},            // too thick a shell
                new int[] {0, 9, 0, ShapeSpec.Mode.PLACE.ordinal(), 0},            // placing without a material
                new int[] {0, 9, 0, carving},                                      // cut short
                new int[] {},                                                      // no shape at all
                new int[] {0, 9, 0, carving, 0, 0});                               // trailing bytes
        for (int[] tail : malformed) {
            assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, concat(head, frame(tail))),
                    Arrays.toString(tail));
        }
        // A terrain brush carries no shape: the same five bytes after its symmetry are trailing bytes.
        BrushSpec raise = new BrushSpec(BrushTool.RAISE, 4, 0.5f, Falloff.LINEAR, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 0);
        byte[] terrain = Codec.encodeC2S(new C2S.StrokeBegin(3, raise), STATES);
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, concat(terrain, frame(0, 9, 0, carving, 0))));
        assertEquals(3, Codec.peekLeadingId(concat(head, frame(kinds, 9, 0, carving, 0))).getAsInt(),
                "the stroke id stays readable for the refusal");
    }

    @Test
    void unknownAndWrongDirectionTypesAreRejected() throws ProtocolException {
        assertEquals(ProtocolException.Reason.UNKNOWN_TYPE, reasonOf(C2S_DECODER, frame(0)));
        assertEquals(ProtocolException.Reason.UNKNOWN_TYPE, reasonOf(C2S_DECODER, frame(63)));
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, new byte[0]));
        byte[] welcome = Codec.encodeS2C(new S2C.Welcome(2, Features.NONE, Limits.DEFAULTS, PermissionMask.NONE, 1), STATES);
        assertEquals(ProtocolException.Reason.UNKNOWN_TYPE, reasonOf(C2S_DECODER, welcome));
        byte[] hello = Codec.encodeC2S(Handshake.hello("x", Features.NONE), STATES);
        assertEquals(ProtocolException.Reason.UNKNOWN_TYPE, reasonOf(S2C_DECODER, hello));
    }

    @Test
    void deepAndBushyMasksAreRejectedBeforeRecursingFar() {
        // RunOp, reqId 0, empty palette, Erase, box, then 200 nested Not tags.
        byte[] head = frame(MessageType.RUN_OP.code(), 0, 0, CoreCodec.OP_ERASE, 0, 0, 0, 0, 0, 0);
        byte[] nots = new byte[200];
        Arrays.fill(nots, (byte) CoreCodec.MASK_NOT);
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, concat(head, nots)));
        // An And with 64 Any children exceeds the node budget (65 nodes).
        byte[] and = new byte[2 + 64];
        and[0] = CoreCodec.MASK_AND;
        and[1] = 64;
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, concat(head, and, frame(0, 0))));
    }

    @Test
    void surfaceMasksShareOneNodeBudgetWithTheirNestedCellMasks() throws ProtocolException {
        StatePalette.Table emptyPalette = StatePalette.Table.read(new WireReader(new byte[] {0}), STATES);
        for (int anyChildren : new int[] {62, 63}) {
            WireWriter out = new WireWriter(1000);
            out.u8(CoreCodec.SURFACE_BLOCKS);
            out.u8(CoreCodec.MASK_AND);
            out.varint(anyChildren);
            for (int i = 0; i < anyChildren; i++) out.u8(CoreCodec.MASK_ANY);
            WireReader in = new WireReader(out.toByteArray());
            if (anyChildren == 62) {
                assertEquals(SurfaceMask.MAX_NODES, CoreCodec.readSurface(in, emptyPalette).nodeCount());
            } else {
                ProtocolException e = assertThrows(ProtocolException.class, () -> CoreCodec.readSurface(in, emptyPalette));
                assertEquals(ProtocolException.Reason.MALFORMED, e.reason(), "65 nodes across both trees");
            }
        }
    }

    /** The fluid patterns: a Waterlog carries a fluid source or is malformed; Dry carries nothing; new tags are refused. */
    @Test
    void fluidPatternsAreStrict() throws ProtocolException {
        int water = STATES.state("minecraft:water[level=0]");
        int lava = STATES.state("minecraft:lava[level=0]");
        C2S.RunOp flood = new C2S.RunOp(5, new OpSpec.Fill(BOX, new Pattern.Waterlog(water), CellMask.ANY), false,
                ConflictPolicy.SKIP_CONFLICTS);
        assertEquals(flood, Codec.decodeC2S(Codec.encodeC2S(flood, STATES), STATES));
        C2S.RunOp lavaFlood = new C2S.RunOp(5, new OpSpec.Fill(BOX, new Pattern.Waterlog(lava), CellMask.ANY), false,
                ConflictPolicy.SKIP_CONFLICTS);
        assertEquals(lavaFlood, Codec.decodeC2S(Codec.encodeC2S(lavaFlood, STATES), STATES));
        C2S.RunOp drain = new C2S.RunOp(6, new OpSpec.Fill(BOX, new Pattern.Dry(), CellMask.ANY), false,
                ConflictPolicy.SKIP_CONFLICTS);
        assertEquals(drain, Codec.decodeC2S(Codec.encodeC2S(drain, STATES), STATES));
        BrushSpec ball = BrushSpec.shape(3, new ShapeSpec(ShapeSpec.Kind.SPHERE, 7, Facing.UP, ShapeSpec.Mode.PLACE, 0),
                new Pattern.Waterlog(water), 1L, null, Symmetry.NONE);
        C2S.StrokeBegin begin = new C2S.StrokeBegin(7, ball);
        assertEquals(begin, Codec.decodeC2S(Codec.encodeC2S(begin, STATES), STATES));

        // The palette names stone where the sender meant water: a Waterlog of a non-fluid.
        StateSpace lying = new RenamingStateSpace(STATES, water, "minecraft:stone");
        byte[] stoneLog = Codec.encodeC2S(flood, lying);
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, stoneLog));
        StateSpace flowing = new RenamingStateSpace(STATES, water, "minecraft:water[level=3]");
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, Codec.encodeC2S(flood, flowing)),
                "flowing water is not a source");
        StateSpace wet = new RenamingStateSpace(STATES, water, "minecraft:oak_stairs[waterlogged=true]");
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, Codec.encodeC2S(flood, wet)),
                "a waterlogged block holds water but is not a fluid");

        // The pattern codec on its own: an unknown tag, a Waterlog cut short, and Dry carrying nothing.
        StatePalette.Table emptyPalette = StatePalette.Table.read(new WireReader(new byte[] {0}), STATES);
        assertEquals(ProtocolException.Reason.MALFORMED, patternReason(frame(6), emptyPalette), "tag 6 is unknown");
        assertEquals(ProtocolException.Reason.MALFORMED, patternReason(frame(2), emptyPalette), "no palette index");
        assertEquals(ProtocolException.Reason.MALFORMED, patternReason(frame(2, 0), emptyPalette), "index out of range");
        assertEquals(new Pattern.Dry(), CoreCodec.readPattern(new WireReader(frame(3)), emptyPalette));
    }

    /**
     * Mix patterns (protocol 5): every layout round-trips in an op and in a
     * brush; a bad layout is malformed, and so is a Steepness pattern anywhere but Palette Paint's brush.
     */
    @Test
    void mixPatternsRoundTripAndMalformedOnesAreRejected() throws ProtocolException {
        int stone = STATES.state("minecraft:stone"), sand = STATES.state("minecraft:sand");
        Pattern.Weighted mix = new Pattern.Weighted(new int[] {stone, sand}, new int[] {3, 1}, -7L);
        Pattern patches = new Pattern.Arranged(mix, new MixLayout.Patches(32));
        Pattern gradient = new Pattern.Arranged(mix, new MixLayout.Gradient(new BlockPos(-(1 << 25), -4096, 2),
                new BlockPos(9, 4096, 1 << 25), 32));
        Pattern steep = new Pattern.Arranged(mix, new MixLayout.Steepness(45));
        ShapeSpec sphere = new ShapeSpec(ShapeSpec.Kind.SPHERE, 7, Facing.UP, ShapeSpec.Mode.PLACE, 0);
        for (Pattern pattern : List.of(patches, gradient)) {
            C2S.RunOp fill = new C2S.RunOp(5, new OpSpec.Fill(BOX, pattern, CellMask.ANY), false,
                    ConflictPolicy.SKIP_CONFLICTS);
            assertEquals(fill, Codec.decodeC2S(Codec.encodeC2S(fill, STATES), STATES));
            C2S.StrokeBegin shape = new C2S.StrokeBegin(7, BrushSpec.shape(3, sphere, pattern, 1L, null, Symmetry.NONE));
            assertEquals(shape, Codec.decodeC2S(Codec.encodeC2S(shape, STATES), STATES));
        }
        BrushSpec palettePaint = new BrushSpec(BrushTool.PALETTE, 4, 1f, Falloff.CONSTANT, Shape.CIRCLE, steep,
                SurfaceMask.ANY, 1, 0, 2L);
        C2S.StrokeBegin paint = new C2S.StrokeBegin(8, palettePaint);
        assertEquals(paint, Codec.decodeC2S(Codec.encodeC2S(paint, STATES), STATES));

        // Steepness in an op: the op record takes it, the codec refuses it.
        C2S.RunOp steepFill = new C2S.RunOp(5, new OpSpec.Fill(BOX, steep, CellMask.ANY), false,
                ConflictPolicy.SKIP_CONFLICTS);
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, Codec.encodeC2S(steepFill, STATES)));
        // Steepness in another brush: Palette Paint's stroke with its tool byte changed to Paint (the same layout).
        StatePalette.Builder names = new StatePalette.Builder(STATES);
        WireWriter body = new WireWriter(ProtocolV2.MAX_C2S_FRAME);
        CoreCodec.writeBrush(body, names, palettePaint);
        WireWriter frame = new WireWriter(ProtocolV2.MAX_C2S_FRAME);
        frame.varint(MessageType.STROKE_BEGIN.code());
        frame.zigzag(9);
        names.writeTo(frame);
        frame.raw(body);
        byte[] asPalette = frame.toByteArray();
        assertEquals(new C2S.StrokeBegin(9, palettePaint), Codec.decodeC2S(asPalette, STATES), "the hand-made frame");
        byte[] asPaint = asPalette.clone();
        int toolAt = asPaint.length - body.toByteArray().length;
        assertEquals(BrushTool.PALETTE.ordinal(), asPaint[toolAt]);
        asPaint[toolAt] = (byte) BrushTool.PAINT.ordinal();
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(C2S_DECODER, asPaint), "a Steepness Paint brush");

        // The layout codec on its own.
        assertEquals(new MixLayout.Patches(1), CoreCodec.readLayout(new WireReader(frame(0, 1))));
        assertEquals(new MixLayout.Steepness(0), CoreCodec.readLayout(new WireReader(frame(2, 0))));
        assertEquals(new MixLayout.Gradient(new BlockPos(0, 1, -1), new BlockPos(1, 0, 0), 32),
                CoreCodec.readLayout(new WireReader(frame(1, 0, 2, 1, 2, 0, 0, 32))));
        assertEquals(ProtocolException.Reason.MALFORMED, layoutReason(frame(3, 1)), "kind 3 is unknown");
        assertEquals(ProtocolException.Reason.MALFORMED, layoutReason(frame(0, 0)), "patch size 0");
        assertEquals(ProtocolException.Reason.MALFORMED, layoutReason(frame(0, 33)), "patch size 33");
        assertEquals(ProtocolException.Reason.MALFORMED, layoutReason(frame(2, 46)), "steepness edge 46");
        assertEquals(ProtocolException.Reason.MALFORMED, layoutReason(frame(1, 0, 2, 1, 0, 2, 1, 4)), "one block");
        assertEquals(ProtocolException.Reason.MALFORMED, layoutReason(frame(1, 0, 2, 1, 2, 0, 0, 33)), "edge 33");
        assertEquals(ProtocolException.Reason.MALFORMED, layoutReason(frame(1, 0, 2, 1, 2, 0)), "cut short");
        // y 4097 (zigzag 8194: 0x82 0x40) is beyond a line end's range.
        assertEquals(ProtocolException.Reason.MALFORMED, layoutReason(frame(1, 0, 0x82, 0x40, 0, 2, 0, 0, 4)),
                "an end beyond the world");
    }

    private static ProtocolException.Reason layoutReason(byte[] bytes) {
        try {
            CoreCodec.readLayout(new WireReader(bytes));
        } catch (ProtocolException e) {
            return e.reason();
        } catch (IllegalArgumentException e) {
            // Refused by a layout's constructor: the message decoder turns this into MALFORMED.
            return ProtocolException.Reason.MALFORMED;
        }
        return fail("Expected a refusal for " + HexFormat.of().formatHex(bytes));
    }

    private static ProtocolException.Reason patternReason(byte[] bytes, StatePalette.Table palette) {
        try {
            CoreCodec.readPattern(new WireReader(bytes), palette);
        } catch (ProtocolException e) {
            return e.reason();
        }
        return fail("Expected a ProtocolException for " + HexFormat.of().formatHex(bytes));
    }

    @Test
    void unknownStatesFailWithUnknownState() throws ProtocolException {
        StateSpace renamed = new RenamingStateSpace(STATES, STATES.state("minecraft:sand"), "othermod:quicksand");
        byte[] frame = Codec.encodeC2S(new C2S.RunOp(4, new OpSpec.Fill(BOX, new Pattern.Single(STATES.state("minecraft:sand")),
                CellMask.ANY), false, ConflictPolicy.SKIP_CONFLICTS), renamed);
        ProtocolException e = assertThrows(ProtocolException.class, () -> Codec.decodeC2S(frame, STATES));
        assertEquals(ProtocolException.Reason.UNKNOWN_STATE, e.reason());
        assertTrue(e.getMessage().contains("othermod:quicksand"));
        assertEquals(4, Codec.peekLeadingId(frame).getAsInt(), "the request id stays readable");

        byte[] badProperty = frame(MessageType.COPY.code(), 2, 1, 26);
        byte[] spec = "minecraft:stone[bogus=true]".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        badProperty[3] = (byte) spec.length;
        e = assertThrows(ProtocolException.class, () -> Codec.decodeC2S(concat(badProperty, spec), STATES));
        assertEquals(ProtocolException.Reason.UNKNOWN_STATE, e.reason());

        ProtocolException noSpace = assertThrows(ProtocolException.class, () -> Codec.decodeC2S(frame, null));
        assertEquals(ProtocolException.Reason.UNKNOWN_STATE, noSpace.reason());
    }

    @Test
    void invalidUtf8IsRejected() {
        assertEquals(ProtocolException.Reason.MALFORMED,
                reasonOf(C2S_DECODER, frame(MessageType.STREAM_ABORT.code(), 2, 2, 0xC3, 0x28)));
        assertEquals(ProtocolException.Reason.MALFORMED,
                reasonOf(C2S_DECODER, frame(MessageType.STREAM_ABORT.code(), 2, 3, 0xED, 0xA0, 0x80)));
    }

    // ---------------------------------------------------------------- fuzz

    @Test
    void fuzzRandomBytesThrowOnlyProtocolExceptions() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            fuzzRandom(C2S_DECODER, true, 1L);
            fuzzRandom(S2C_DECODER, false, 2L);
        });
    }

    private static void fuzzRandom(Decoder decoder, boolean c2s, long seed) {
        Random rnd = new Random(seed);
        MessageType[] types = MessageType.values();
        int decoded = 0;
        for (int i = 0; i < FUZZ_ITERATIONS; i++) {
            byte[] frame = new byte[rnd.nextInt(rnd.nextInt(10) == 0 ? 2000 : 48)];
            rnd.nextBytes(frame);
            // Mostly start with a real type code so the body decoders get exercised.
            if (frame.length > 0 && rnd.nextInt(8) != 0) frame[0] = (byte) types[rnd.nextInt(types.length)].code();
            if (decodesOnlyWithProtocolExceptions(decoder, c2s, frame)) decoded++;
        }
        assertTrue(decoded < FUZZ_ITERATIONS, "random bytes mostly fail to decode");
    }

    @Test
    void fuzzMutatedValidFramesThrowOnlyProtocolExceptions() {
        assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            fuzzMutated(true, 3L);
            fuzzMutated(false, 4L);
        });
    }

    private static void fuzzMutated(boolean c2s, long seed) throws ProtocolException {
        RandomMessages random = new RandomMessages(seed, STATES);
        Random rnd = random.rnd;
        Decoder decoder = c2s ? C2S_DECODER : S2C_DECODER;
        int decoded = 0;
        for (int i = 0; i < FUZZ_ITERATIONS; i++) {
            Message message = c2s ? random.c2s() : random.s2c();
            byte[] frame = c2s ? Codec.encodeC2S((C2S) message, STATES) : Codec.encodeS2C((S2C) message, STATES);
            if (decodesOnlyWithProtocolExceptions(decoder, c2s, mutate(frame, rnd))) decoded++;
        }
        // Both outcomes occur, so the mutations reach deep into the body decoders.
        assertTrue(decoded > FUZZ_ITERATIONS / 20 && decoded < FUZZ_ITERATIONS * 19 / 20, "decoded " + decoded);
    }

    private static byte[] mutate(byte[] frame, Random rnd) {
        byte[] out = frame.clone();
        int edits = 1 + rnd.nextInt(4);
        for (int e = 0; e < edits && out.length > 0; e++) {
            int at = rnd.nextInt(out.length);
            switch (rnd.nextInt(6)) {
                case 0 -> out[at] = (byte) rnd.nextInt(256);
                case 1 -> out[at] ^= (byte) (1 << rnd.nextInt(8));
                case 2 -> out[at] = (byte) 0xFF;
                case 3 -> out = Arrays.copyOf(out, at);
                case 4 -> {
                    byte[] longer = new byte[out.length + 1 + rnd.nextInt(8)];
                    System.arraycopy(out, 0, longer, 0, at);
                    for (int k = at; k < at + longer.length - out.length; k++) longer[k] = (byte) rnd.nextInt(256);
                    System.arraycopy(out, at, longer, at + longer.length - out.length, out.length - at);
                    out = longer;
                }
                default -> {
                    int end = Math.min(out.length, at + 1 + rnd.nextInt(6));
                    byte[] shorter = new byte[out.length - (end - at)];
                    System.arraycopy(out, 0, shorter, 0, at);
                    System.arraycopy(out, end, shorter, at, out.length - end);
                    out = shorter;
                }
            }
        }
        return out;
    }

    /** Formats one state under another name, as a peer with a different registry would. */
    private record RenamingStateSpace(FakeStateSpace delegate, int handle, String name) implements StateSpace {
        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public int air() {
            return delegate.air();
        }

        @Override
        public int flags(int h) {
            return delegate.flags(h);
        }

        @Override
        public String format(int h) {
            return h == handle ? name : delegate.format(h);
        }

        @Override
        public int parse(String spec) {
            return delegate.parse(spec);
        }

        @Override
        public BlockDescriptor describe(int h) {
            return delegate.describe(h);
        }

        @Override
        public int resolve(BlockDescriptor d) {
            return delegate.resolve(d);
        }

        @Override
        public NamespacedId blockId(int h) {
            return delegate.blockId(h);
        }

        @Override
        public boolean inTag(int h, NamespacedId tag) {
            return delegate.inTag(h, tag);
        }

        @Override
        public int rotate(int h, int turns) {
            return delegate.rotate(h, turns);
        }

        @Override
        public int mirror(int h, Mirror m) {
            return delegate.mirror(h, m);
        }

        @Override
        public int withWaterlogged(int h, boolean on) {
            return delegate.withWaterlogged(h, on);
        }

        @Override
        public int fluidSource(int h) {
            return delegate.fluidSource(h);
        }
    }
}
