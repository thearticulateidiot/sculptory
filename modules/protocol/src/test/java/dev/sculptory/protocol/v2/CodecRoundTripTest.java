package dev.sculptory.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.testing.FakeStateSpace;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodecRoundTripTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final Box BOX = Box.of(new BlockPos(-5, -64, 3), new BlockPos(40, 319, 90));

    static Message roundTripC2S(C2S message) throws ProtocolException {
        byte[] frame = Codec.encodeC2S(message, STATES);
        assertTrue(frame.length <= ProtocolV2.MAX_C2S_FRAME);
        assertEquals(message.type(), Codec.peekType(frame));
        return Codec.decodeC2S(frame, STATES);
    }

    static Message roundTripS2C(S2C message) throws ProtocolException {
        byte[] frame = Codec.encodeS2C(message, STATES);
        assertTrue(frame.length <= ProtocolV2.MAX_S2C_FRAME);
        assertEquals(message.type(), Codec.peekType(frame));
        return Codec.decodeS2C(frame, STATES);
    }

    @Test
    void everySampleMessageRoundTripsInEachOfItsDirections() throws ProtocolException {
        EnumSet<MessageType> seen = EnumSet.noneOf(MessageType.class);
        for (Message message : MessageTypeTest.samples()) {
            if (message instanceof C2S c2s) assertEquals(message, roundTripC2S(c2s), message.type() + " C2S");
            if (message instanceof S2C s2c) assertEquals(message, roundTripS2C(s2c), message.type() + " S2C");
            seen.add(message.type());
        }
        assertEquals(EnumSet.allOf(MessageType.class), seen);
    }

    @Test
    void randomMessagesOfEveryTypeRoundTrip() throws ProtocolException {
        RandomMessages random = new RandomMessages(20260925L, STATES);
        EnumSet<MessageType> seen = EnumSet.noneOf(MessageType.class);
        for (int i = 0; i < 3000; i++) {
            C2S c2s = random.c2s();
            assertEquals(c2s, roundTripC2S(c2s));
            S2C s2c = random.s2c();
            assertEquals(s2c, roundTripS2C(s2c));
            seen.add(c2s.type());
            seen.add(s2c.type());
        }
        assertEquals(EnumSet.allOf(MessageType.class), seen, "the generator covers every message type");
    }

    @Test
    void randomOpSpecsPatternsMasksAndBrushesRoundTrip() throws ProtocolException {
        RandomMessages random = new RandomMessages(77L, STATES);
        for (int i = 0; i < 2000; i++) {
            OpSpec op = random.op();
            C2S.RunOp run = new C2S.RunOp(i, op, i % 2 == 0, ConflictPolicy.values()[i % 2]);
            assertEquals(run, roundTripC2S(run), "op " + op);

            CellMask mask = random.mask();
            C2S.Copy copy = new C2S.Copy(i, BOX, BlockPos.ORIGIN, i % 3 == 0, mask);
            assertEquals(copy, roundTripC2S(copy), "mask " + mask);

            Pattern pattern = random.pattern();
            C2S.RunOp fill = new C2S.RunOp(i, new OpSpec.Fill(BOX, pattern, CellMask.ANY), false, ConflictPolicy.OVERWRITE);
            assertEquals(fill, roundTripC2S(fill), "pattern " + pattern);

            BrushSpec brush = random.brush();
            C2S.StrokeBegin begin = new C2S.StrokeBegin(i, brush);
            assertEquals(begin, roundTripC2S(begin), "brush " + brush);
        }
    }

    @Test
    void palettesWithManyStatesKeepExactHandles() throws ProtocolException {
        int[] every = new int[STATES.size()];
        for (int h = 0; h < every.length; h++) every[h] = h;
        int[] sixtyFour = new int[Pattern.Weighted.MAX_ENTRIES];
        int[] weights = new int[sixtyFour.length];
        for (int i = 0; i < sixtyFour.length; i++) {
            sixtyFour[i] = every.length - 1 - i;
            weights[i] = 1 + i;
        }
        int[] repeated = new int[CellMask.MAX_LIST];
        for (int i = 0; i < repeated.length; i++) repeated[i] = (i * 7) % every.length;
        CellMask mask = new CellMask.Or(List.of(new CellMask.States(every), new CellMask.Not(new CellMask.States(repeated))));
        C2S.RunOp run = new C2S.RunOp(9, new OpSpec.Replace(BOX, mask, new Pattern.Weighted(sixtyFour, weights, -3L)),
                false, ConflictPolicy.SKIP_CONFLICTS);

        C2S.RunOp decoded = (C2S.RunOp) roundTripC2S(run);
        assertEquals(run, decoded);
        OpSpec.Replace replace = (OpSpec.Replace) decoded.op();
        assertArrayEquals(sixtyFour, ((Pattern.Weighted) replace.to()).states());
        CellMask.Or or = (CellMask.Or) replace.from();
        assertArrayEquals(every, ((CellMask.States) or.masks().get(0)).handles());
    }

    @Test
    void statesAreSentAsTextNotHandles() throws ProtocolException {
        int stairs = STATES.state("minecraft:oak_stairs[facing=east,half=top,shape=outer_left,waterlogged=true]");
        byte[] frame = Codec.encodeC2S(new C2S.RunOp(1, new OpSpec.Fill(BOX, new Pattern.Single(stairs), CellMask.ANY),
                false, ConflictPolicy.SKIP_CONFLICTS), STATES);
        String text = new String(frame, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("minecraft:oak_stairs[facing=east,half=top,shape=outer_left,waterlogged=true]"));
    }

    @Test
    void deepAndWideMasksAtTheirLimitsRoundTrip() throws ProtocolException {
        CellMask deep = CellMask.ANY;
        for (int i = 1; i < CellMask.MAX_DEPTH; i++) deep = new CellMask.Not(deep);
        assertEquals(CellMask.MAX_DEPTH, deep.depth());
        List<CellMask> wide = new ArrayList<>();
        for (int i = 0; i < CellMask.MAX_NODES - 1; i++) wide.add(new CellMask.Tag(new NamespacedId("minecraft:logs")));
        CellMask widest = new CellMask.And(wide);
        assertEquals(CellMask.MAX_NODES, widest.nodeCount());
        for (CellMask mask : List.of(deep, widest)) {
            C2S.Copy copy = new C2S.Copy(1, BOX, BlockPos.ORIGIN, false, mask);
            assertEquals(copy, roundTripC2S(copy));
        }
        SurfaceMask surface = SurfaceMask.ANY;
        for (int i = 1; i < SurfaceMask.MAX_DEPTH; i++) surface = new SurfaceMask.Not(surface);
        BrushSpec spec = new BrushSpec(BrushTool.PAINT, 32, 1f, Falloff.SPHERE, Shape.SQUARE, new Pattern.Single(1),
                surface, 32, -64, Long.MIN_VALUE);
        assertEquals(new C2S.StrokeBegin(Integer.MIN_VALUE, spec), roundTripC2S(new C2S.StrokeBegin(Integer.MIN_VALUE, spec)));
        int h = BrushSpec.CLIP_MAX_HORIZONTAL, v = BrushSpec.CLIP_MAX_Y;
        BrushSpec widestClip = spec.withClip(new Box(new BlockPos(-h, -v, -h), new BlockPos(h, v, h)));
        assertEquals(new C2S.StrokeBegin(3, widestClip), roundTripC2S(new C2S.StrokeBegin(3, widestClip)));
        // Exact states and an inverted mask, as the brush settings build them, with a one-cell clip.
        BrushSpec exact = new BrushSpec(BrushTool.RAISE, 5, 0.5f, Falloff.SMOOTH, Shape.CIRCLE, null,
                new SurfaceMask.Not(new SurfaceMask.And(List.of(
                        new SurfaceMask.SurfaceBlocks(new CellMask.States(new int[] {STATES.state("minecraft:grass_block[snowy=true]"),
                                STATES.state("minecraft:oak_log[axis=x]")})),
                        new SurfaceMask.Elevation(60, 80)))),
                0, 0, 9L, Box.of(new BlockPos(4, 70, -2)));
        assertEquals(new C2S.StrokeBegin(4, exact), roundTripC2S(new C2S.StrokeBegin(4, exact)));
    }

    @Test
    void peekLeadingIdReadsRequestAndStrokeIds() throws ProtocolException {
        byte[] run = Codec.encodeC2S(new C2S.RunOp(-77, new OpSpec.Erase(BOX, CellMask.ANY), false,
                ConflictPolicy.SKIP_CONFLICTS), STATES);
        assertEquals(-77, Codec.peekLeadingId(run).getAsInt());
        byte[] stroke = Codec.encodeC2S(new C2S.StrokeEnd(12), STATES);
        assertEquals(12, Codec.peekLeadingId(stroke).getAsInt());
        byte[] cancel = Codec.encodeC2S(new C2S.CancelJob(new java.util.UUID(1, 2)), STATES);
        assertTrue(Codec.peekLeadingId(cancel).isEmpty());
        assertTrue(Codec.peekLeadingId(new byte[0]).isEmpty());
        assertTrue(Codec.peekLeadingId(new byte[] {(byte) MessageType.RUN_OP.code()}).isEmpty());
    }
}
