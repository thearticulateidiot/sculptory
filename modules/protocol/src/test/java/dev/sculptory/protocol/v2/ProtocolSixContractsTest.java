package dev.sculptory.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BiomeSpec;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.HeightmapSpec;
import dev.sculptory.core.brush.SculptMode;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Transform;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The wire contracts of the batch after 0.3.0-alpha (protocol 6): orientations on Paste and Move, the multi-paste, the
 * biome fill, the paste options' biomes bit, the Heightmap and Biome brushes and the new tool labels. Round trips, and
 * the refusals a hostile or broken client meets.
 */
class ProtocolSixContractsTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final Region BOX = new Region.Cuboid(Box.of(new BlockPos(0, 60, 0), new BlockPos(9, 70, 9)));
    private static final SourceRef CLIPBOARD = new SourceRef.Clipboard(new UUID(1, 2));
    private static final Pattern STONE = new Pattern.Single(STATES.state("minecraft:stone"));

    private static C2S roundTrip(C2S message) throws ProtocolException {
        return Codec.decodeC2S(Codec.encodeC2S(message, STATES), STATES);
    }

    private static ProtocolException.Reason reasonOf(byte[] frame) {
        try {
            Codec.decodeC2S(frame, STATES);
        } catch (ProtocolException e) {
            return e.reason();
        }
        return fail("Expected a ProtocolException for " + HexFormat.of().formatHex(frame));
    }

    /** What a test writes as an op's body. */
    private interface Body {
        void write(WireWriter out) throws ProtocolException;
    }

    /** A RunOp frame (no states, no physics, Skip conflicts, no label) around an op written by hand. */
    private static byte[] runOp(Body op) throws ProtocolException {
        WireWriter out = new WireWriter(ProtocolV2.MAX_C2S_FRAME);
        out.varint(MessageType.RUN_OP.code());
        out.zigzag(1);
        out.varint(0); // an empty state palette
        op.write(out);
        out.bool(false);
        out.varint(ConflictPolicy.SKIP_CONFLICTS.ordinal());
        out.varint(OpLabel.NONE.ordinal());
        return out.toByteArray();
    }

    /** A paste of the clipboard at the origin whose orientation is {@code orientation} bytes. */
    private static byte[] paste(int... orientation) throws ProtocolException {
        return runOp(out -> {
            out.u8(CoreCodec.OP_PASTE);
            CoreCodec.writeSource(out, CLIPBOARD);
            CoreCodec.writePos(out, BlockPos.ORIGIN);
            for (int b : orientation) out.u8(b);
            CoreCodec.writePasteOptions(out, PasteOptions.DEFAULT);
            CoreCodec.writeSymmetry(out, Symmetry.NONE);
        });
    }

    private static BrushSpec heightmap(int radius, int side) {
        return new BrushSpec(BrushTool.HEIGHTMAP, radius, 1f, Falloff.SMOOTH, Shape.SQUARE, null, SurfaceMask.ANY, 0, 0, 1L,
                null, Symmetry.NONE, null, SculptMode.TERRAIN, null, null,
                new HeightmapSpec(side, new byte[side * side], 8, false), null);
    }

    private static BrushSpec biome(String id) {
        return new BrushSpec(BrushTool.BIOME, 5, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L, null,
                Symmetry.NONE, null, SculptMode.TERRAIN, null, null, null, new BiomeSpec(id, true));
    }

    // ---------------------------------------------------------------- orientations

    @Test
    void exactAndFreeOrientationsRoundTripOnPastesAndMoves() throws ProtocolException {
        Transform turned = new Transform(3, Mirror.X, true);
        for (Orientation orientation : List.of(Orientation.IDENTITY, Orientation.of(turned),
                Orientation.free(turned, 1, 0, 0), Orientation.free(Transform.IDENTITY, 0, 9_000, 0),
                Orientation.free(turned, 35_999, 17_999, 12_345))) {
            C2S paste = new C2S.RunOp(1, new OpSpec.Paste(CLIPBOARD, new BlockPos(1, -2, 3), orientation,
                    PasteOptions.DEFAULT), false, ConflictPolicy.SKIP_CONFLICTS);
            assertEquals(paste, roundTrip(paste), orientation.toString());
            C2S move = new C2S.RunOp(2, new OpSpec.Move(BOX, BlockPos.ORIGIN, orientation, STONE, EntityFilter.ALL,
                    Symmetry.NONE, PasteOptions.Into.AIR), false, ConflictPolicy.OVERWRITE, OpLabel.ROTATE);
            assertEquals(move, roundTrip(move), orientation.toString());
        }
        // An exact orientation is the protocol 5 transform byte, nothing more.
        assertEquals(Codec.encodeC2S(new C2S.RunOp(1, new OpSpec.Paste(CLIPBOARD, BlockPos.ORIGIN, turned,
                PasteOptions.DEFAULT), false, ConflictPolicy.SKIP_CONFLICTS), STATES).length, paste(0).length);
    }

    @Test
    void aFreeOrientationIsTheTransformByteWithBitFiveThenThreeAngles() throws ProtocolException {
        C2S.RunOp decoded = (C2S.RunOp) Codec.decodeC2S(paste(0x21, 0xA9, 0x46, 0x00, 0x05), STATES);
        // yaw 9,001 (varint 0xA9 0x46), pitch 0, roll 5; the transform one quarter turn.
        assertEquals(Orientation.free(Transform.rotation(1), 9_001, 0, 5), ((OpSpec.Paste) decoded.op()).orientation());
    }

    @Test
    void onlyTheCanonicalFormOfAnOrientationIsAccepted() throws ProtocolException {
        // Bit 5 with no free angle, with a quarter turn of yaw (an exact transform), a half turn of pitch (upside down
        // plus a mirror), an angle of a full turn or more, or a negative varint.
        for (int[] bytes : List.of(new int[] {0x20, 0, 0, 0}, new int[] {0x20, 0xA8, 0x46, 0, 0},
                new int[] {0x20, 0, 0xD0, 0x8C, 0x01, 0}, new int[] {0x20, 0xA0, 0x99, 0x02, 0, 0},
                new int[] {0x20, 0xFF, 0xFF, 0xFF, 0xFF, 0x0F, 0, 0})) {
            assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(paste(bytes)), HexFormat.of().formatHex(
                    paste(bytes)));
        }
        // Bits 6 and 7 stay zero, with or without bit 5.
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(paste(0x40)));
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(paste(0xA0, 1, 0, 0)));
        // A quarter turn of pitch keeps the vertical axis horizontal: it stays free, and travels.
        Orientation pitched = Orientation.free(Transform.IDENTITY, 0, 9_000, 0);
        assertFalse(pitched.isExact());
        C2S.RunOp decoded = (C2S.RunOp) Codec.decodeC2S(paste(0x20, 0, 0xA8, 0x46, 0), STATES);
        assertEquals(pitched, ((OpSpec.Paste) decoded.op()).orientation());
    }

    // ---------------------------------------------------------------- paste options, multi-paste, biome fill

    @Test
    void thePasteOptionsCarryTheBiomesBitAndRefuseHigherBits() throws ProtocolException {
        PasteOptions biomes = new PasteOptions(true, false, true, PasteOptions.Into.EXISTING, true);
        C2S paste = new C2S.RunOp(3, new OpSpec.Paste(CLIPBOARD, BlockPos.ORIGIN, Transform.IDENTITY, biomes), false,
                ConflictPolicy.SKIP_CONFLICTS);
        assertEquals(paste, roundTrip(paste));
        assertFalse(PasteOptions.DEFAULT.biomes());
        assertTrue(PasteOptions.DEFAULT.withBiomes(true).withInto(PasteOptions.Into.AIR).biomes());
        byte[] bad = runOp(out -> {
            out.u8(CoreCodec.OP_PASTE);
            CoreCodec.writeSource(out, CLIPBOARD);
            CoreCodec.writePos(out, BlockPos.ORIGIN);
            out.u8(0);
            out.u8(0x10); // bit 4 of the paste options
            out.varint(0);
            CoreCodec.writeSymmetry(out, Symmetry.NONE);
        });
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(bad));
    }

    @Test
    void movesAndStacksCarryAnExplicitBiomesFlag() throws ProtocolException {
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 4, 0);
        for (boolean biomes : new boolean[] {false, true}) {
            for (Orientation orientation : List.of(Orientation.IDENTITY, Orientation.free(Transform.IDENTITY, 4_500, 0, 0))) {
                OpSpec.Move move = new OpSpec.Move(BOX, new BlockPos(3, 0, -2), orientation, STONE, EntityFilter.NONE,
                        mirror, PasteOptions.Into.EVERYTHING, biomes);
                C2S message = new C2S.RunOp(6, move, false, ConflictPolicy.SKIP_CONFLICTS);
                assertEquals(message, roundTrip(message));
                assertEquals(biomes, ((OpSpec.Move) ((C2S.RunOp) roundTrip(message)).op()).biomes());
            }
            for (boolean upsideDown : new boolean[] {false, true}) {
                OpSpec.Stack stack = new OpSpec.Stack(BOX, 0, 11, 0, 3, EntityFilter.DECORATIONS, mirror,
                        PasteOptions.Into.AIR, upsideDown, biomes);
                C2S message = new C2S.RunOp(7, stack, false, ConflictPolicy.SKIP_CONFLICTS);
                OpSpec.Stack decoded = (OpSpec.Stack) ((C2S.RunOp) roundTrip(message)).op();
                assertEquals(stack, decoded);
                assertEquals(upsideDown, decoded.upsideDown());
                assertEquals(biomes, decoded.biomes());
            }
        }
        // Every constructor without the flag means biomes stay where they are; withBiomes sets it and nothing else.
        OpSpec.Move plain = new OpSpec.Move(BOX, BlockPos.ORIGIN, Transform.IDENTITY, STONE, EntityFilter.NONE);
        assertFalse(plain.biomes());
        assertTrue(plain.withBiomes(true).biomes());
        assertEquals(plain, plain.withBiomes(true).withBiomes(false));
        OpSpec.Stack plainStack = new OpSpec.Stack(BOX, 1, 0, 0, 2, EntityFilter.NONE, Symmetry.NONE,
                PasteOptions.Into.EVERYTHING, true);
        assertFalse(plainStack.biomes());
        assertEquals(new OpSpec.Stack(BOX, 1, 0, 0, 2, EntityFilter.NONE, Symmetry.NONE, PasteOptions.Into.EVERYTHING,
                true, true), plainStack.withBiomes(true));
        // The flag is a wire boolean: anything but 0 or 1 is MALFORMED, and a stack without it is cut short.
        for (int flag : new int[] {2, 0xff}) {
            byte[] bad = runOp(out -> {
                writeStackUpToItsBiomes(out);
                out.u8(flag);
            });
            assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(bad), "biomes byte " + flag);
        }
        WireWriter cut = new WireWriter(ProtocolV2.MAX_C2S_FRAME);
        cut.varint(MessageType.RUN_OP.code());
        cut.zigzag(1);
        cut.varint(0);
        writeStackUpToItsBiomes(cut);
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(cut.toByteArray()), "a stack without its biomes flag");
    }

    /** A stack op's bytes up to (not including) its biomes flag. */
    private static void writeStackUpToItsBiomes(WireWriter out) throws ProtocolException {
        out.u8(CoreCodec.OP_STACK);
        CoreCodec.writeRegion(out, BOX);
        out.zigzag(1);
        out.zigzag(0);
        out.zigzag(0);
        out.zigzag(2);
        out.enumValue(EntityFilter.NONE);
        CoreCodec.writeSymmetry(out, Symmetry.NONE);
        out.enumValue(PasteOptions.Into.EVERYTHING);
        out.bool(false);
    }

    @Test
    void multiPastesRoundTripUpToTheirCapAndRefuseNoneOrMore() throws ProtocolException {
        List<OpSpec.PasteMany.Placement> placements = new ArrayList<>();
        for (int i = 0; i < OpSpec.PasteMany.MAX_PLACEMENTS; i++) {
            placements.add(new OpSpec.PasteMany.Placement(new BlockPos(i * 7 - 900, 64 + i % 5, -i * 13),
                    i % 3 == 0 ? Orientation.free(new Transform(i, Mirror.Z, true), i * 97, i * 31, i * 13)
                            : Orientation.of(Transform.rotation(i))));
        }
        C2S stamp = new C2S.RunOp(4, new OpSpec.PasteMany(new SourceRef.Asset(dev.sculptory.core.Sha256.digest(
                new byte[] {4}).hex()), placements, new PasteOptions(false, false, true, PasteOptions.Into.AIR, true),
                new Symmetry(Symmetry.Mode.MIRROR_X, 4, 0)), false, ConflictPolicy.SKIP_CONFLICTS, OpLabel.STAMP);
        byte[] frame = Codec.encodeC2S(stamp, STATES);
        assertTrue(frame.length < ProtocolV2.MAX_C2S_FRAME, "the largest stamp fits a frame: " + frame.length);
        assertEquals(stamp, Codec.decodeC2S(frame, STATES));
        assertThrows(IllegalArgumentException.class, () -> new OpSpec.PasteMany(CLIPBOARD, List.of(),
                PasteOptions.DEFAULT));
        List<OpSpec.PasteMany.Placement> tooMany = new ArrayList<>(placements);
        tooMany.add(placements.get(0));
        assertThrows(IllegalArgumentException.class, () -> new OpSpec.PasteMany(CLIPBOARD, tooMany, PasteOptions.DEFAULT));
        for (int count : new int[] {0, OpSpec.PasteMany.MAX_PLACEMENTS + 1}) {
            byte[] bad = runOp(out -> {
                out.u8(CoreCodec.OP_PASTE_MANY);
                CoreCodec.writeSource(out, CLIPBOARD);
                out.varint(count);
                for (int i = 0; i < count; i++) {
                    CoreCodec.writePos(out, BlockPos.ORIGIN);
                    out.u8(0);
                }
                CoreCodec.writePasteOptions(out, PasteOptions.DEFAULT);
                CoreCodec.writeSymmetry(out, Symmetry.NONE);
            });
            assertEquals(count == 0 ? ProtocolException.Reason.MALFORMED : ProtocolException.Reason.TOO_LARGE,
                    reasonOf(bad), count + " placements");
        }
    }

    @Test
    void biomeFillsRoundTripAndRefuseAMalformedBiomeId() throws ProtocolException {
        for (boolean columns : new boolean[] {true, false}) {
            C2S fill = new C2S.RunOp(5, new OpSpec.BiomeFill(BOX, "minecraft:cherry_grove", columns,
                    new Symmetry(Symmetry.Mode.ROTATE_4, 2, 2)), false, ConflictPolicy.SKIP_CONFLICTS, OpLabel.BIOME_FILL);
            assertEquals(fill, roundTrip(fill));
        }
        for (String id : List.of("", "plains", "Minecraft:Plains", "minecraft:plains with spaces")) {
            assertThrows(IllegalArgumentException.class, () -> new OpSpec.BiomeFill(BOX, id, true), id);
            byte[] bad = runOp(out -> {
                out.u8(CoreCodec.OP_BIOME_FILL);
                CoreCodec.writeRegion(out, BOX);
                out.string(id, CoreCodec.NAMESPACED_ID_BYTES, "biome id");
                out.bool(true);
                CoreCodec.writeSymmetry(out, Symmetry.NONE);
            });
            assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(bad), id);
        }
    }

    @Test
    void theNewLabelsFitTheirOpsAndTheServerSideRefusesAnyOther() {
        OpSpec paste = new OpSpec.Paste(CLIPBOARD, BlockPos.ORIGIN, Transform.IDENTITY, PasteOptions.DEFAULT);
        OpSpec many = new OpSpec.PasteMany(CLIPBOARD, List.of(new OpSpec.PasteMany.Placement(BlockPos.ORIGIN,
                Orientation.IDENTITY)), PasteOptions.DEFAULT);
        OpSpec move = new OpSpec.Move(BOX, BlockPos.ORIGIN, Transform.IDENTITY, STONE, EntityFilter.NONE);
        OpSpec biome = new OpSpec.BiomeFill(BOX, "minecraft:plains", true);
        OpSpec fill = new OpSpec.Fill(BOX, STONE, CellMask.ANY);
        List<OpSpec> ops = List.of(paste, many, move, biome, fill);
        for (OpLabel label : List.of(OpLabel.PATH_EXTRUDE, OpLabel.TAPER, OpLabel.SWEEP, OpLabel.HEIGHTMAP)) {
            assertEquals(List.of(paste), ops.stream().filter(label::fits).toList(), label.name());
        }
        assertEquals(List.of(many), ops.stream().filter(OpLabel.STAMP::fits).toList());
        assertEquals(List.of(move), ops.stream().filter(OpLabel.ROTATE::fits).toList());
        assertEquals(List.of(biome), ops.stream().filter(OpLabel.BIOME_FILL::fits).toList());
        assertFalse(OpLabel.ROAD.fits(many));
    }

    // ---------------------------------------------------------------- the Heightmap and Biome brushes

    /** A radius-32 image (65 × 65) still travels raw, its {@code side²} bytes as they are, inside a frame. */
    @Test
    void aRadius32HeightmapTravelsRaw() throws ProtocolException {
        int side = HeightmapImages.RAW_SIDE;
        byte[] heights = new byte[side * side];
        for (int i = 0; i < heights.length; i++) heights[i] = (byte) (i * 131 + 7);
        C2S begin = new C2S.StrokeBegin(1, stamp(BrushSpec.MAX_RADIUS, heights, new Symmetry(Symmetry.Mode.ROTATE_4, 0, 0)));
        byte[] frame = Codec.encodeC2S(begin, STATES);
        assertTrue(frame.length > side * side && frame.length < ProtocolV2.MAX_C2S_FRAME, "frame of " + frame.length);
        assertTrue(indexOf(frame, heights) > 0, "the image's bytes as they are");
        assertEquals(begin, Codec.decodeC2S(frame, STATES));
        assertEquals(65, side);
    }

    /**
     * A wider image travels deflated: the largest (radius 128, 257 × 257 = 66,049 bytes, twice a frame) round-trips
     * inside a {@code StrokeBegin} frame when it is a smooth picture, and so does one of every width up to it; one of
     * pure noise can't shrink and is refused when encoded (TOO_LARGE), before anything is sent.
     */
    @Test
    void aWiderHeightmapTravelsDeflatedInOneFrame() throws ProtocolException {
        assertEquals(257, HeightmapSpec.MAX_SIDE);
        C2S largest = new C2S.StrokeBegin(2, stamp(128, cone(257), new Symmetry(Symmetry.Mode.MIRROR_X, 4, -4)));
        byte[] frame = Codec.encodeC2S(largest, STATES);
        assertTrue(frame.length < ProtocolV2.MAX_C2S_FRAME / 2, "a cone in " + frame.length + " bytes");
        assertEquals(largest, Codec.decodeC2S(frame, STATES));
        for (int radius = 33; radius <= 128; radius += 19) {
            C2S begin = new C2S.StrokeBegin(radius, stamp(radius, cone(2 * radius + 1), Symmetry.NONE));
            assertEquals(begin, roundTrip(begin), "radius " + radius);
        }
        byte[] noise = new byte[257 * 257];
        new Random(5).nextBytes(noise);
        ProtocolException tooLarge = assertThrows(ProtocolException.class,
                () -> Codec.encodeC2S(new C2S.StrokeBegin(3, stamp(128, noise, Symmetry.NONE)), STATES));
        assertEquals(ProtocolException.Reason.TOO_LARGE, tooLarge.reason());
    }

    /**
     * A deflated image that is not exactly {@code side²} bytes is MALFORMED, and nothing larger than the image is ever
     * inflated: data cut short, a corrupt byte, a stream that inflates short or long (a "bomb" of zeros many times the
     * image included), bytes after the stream, no data at all; and an image whose side doesn't fit the radius, or is
     * wider than 257.
     */
    @Test
    void aBadDeflatedHeightmapIsMalformed() throws ProtocolException {
        byte[] heights = cone(201);
        byte[] frame = Codec.encodeC2S(new C2S.StrokeBegin(4, stamp(100, heights, Symmetry.NONE)), STATES);
        byte[] data = HeightmapImages.deflate(heights, 201);
        assertEquals(stamp(100, heights, Symmetry.NONE), ((C2S.StrokeBegin) Codec.decodeC2S(
                withImage(frame, data, data), STATES)).spec(), "the splice itself changes nothing");
        byte[] corrupt = data.clone();
        corrupt[data.length / 2] ^= 0x5A;
        byte[] trailing = Arrays.copyOf(data, data.length + 2);
        byte[][] bad = {
            Arrays.copyOf(data, data.length - 9),
            Arrays.copyOf(data, data.length / 2),
            corrupt,
            HeightmapImages.deflate(new byte[201 * 200], 200),
            HeightmapImages.deflate(new byte[202 * 202], 202),
            HeightmapImages.deflate(new byte[2000 * 2000], 2000),
            trailing,
            new byte[0],
            {1, 2, 3, 4},
        };
        for (int i = 0; i < bad.length; i++) {
            assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(withImage(frame, data, bad[i])), "case " + i);
        }
        // A length past what a client frame holds, or past the frame's end, is MALFORMED too (not TOO_LARGE).
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(withImage(frame, data, data, ProtocolV2.MAX_C2S_FRAME + 1)));
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(withImage(frame, data, data, Integer.MAX_VALUE)));
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(withImage(frame, data, data, data.length + 500)));
        // The side, two varint bytes before the length: 203 (radius 100 needs 201), then 259 (over the largest).
        int sideAt = indexOf(frame, data) - varintLength(data.length) - 2;
        assertEquals((byte) (0x80 | (201 & 0x7F)), frame[sideAt]);
        byte[] wrongSide = withImage(frame, data, HeightmapImages.deflate(new byte[203 * 203], 203));
        wrongSide[sideAt] = (byte) (0x80 | (203 & 0x7F));
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(wrongSide));
        byte[] tooWide = withImage(frame, data, HeightmapImages.deflate(new byte[259 * 259], 259));
        tooWide[sideAt] = (byte) (0x80 | (259 & 0x7F));
        tooWide[sideAt + 1] = (byte) (259 >>> 7);
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(tooWide));
    }

    /** A radius-{@code radius} Heightmap brush with {@code heights}, 64 tall, raising. */
    private static BrushSpec stamp(int radius, byte[] heights, Symmetry symmetry) {
        return new BrushSpec(BrushTool.HEIGHTMAP, radius, 0.5f, Falloff.SPHERE, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0,
                -3L, Box.of(new BlockPos(-100, -64, -100), new BlockPos(100, 300, 100)), symmetry, null, SculptMode.TERRAIN,
                null, null, new HeightmapSpec(2 * radius + 1, heights, 64, false), null);
    }

    /** A round cone {@code side} wide: 255 at the centre, 0 at the edge. */
    private static byte[] cone(int side) {
        int r = side / 2;
        byte[] heights = new byte[side * side];
        for (int v = 0; v < side; v++) {
            for (int u = 0; u < side; u++) {
                double d = Math.sqrt((u - r) * (u - r) + (v - r) * (v - r)) / r;
                heights[v * side + u] = (byte) Math.max(0, Math.round(255 * (1 - d)));
            }
        }
        return heights;
    }

    /** {@code frame} with the deflated image {@code data} (and its varint length) replaced by {@code image}. */
    private static byte[] withImage(byte[] frame, byte[] data, byte[] image) {
        return withImage(frame, data, image, image.length);
    }

    /** {@link #withImage}, with {@code length} written as the image's length whatever it holds. */
    private static byte[] withImage(byte[] frame, byte[] data, byte[] image, int length) {
        int at = indexOf(frame, data);
        int lengthAt = at - varintLength(data.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(frame, 0, lengthAt);
        int n = length;
        while ((n & ~0x7F) != 0) {
            out.write((n & 0x7F) | 0x80);
            n >>>= 7;
        }
        out.write(n);
        out.write(image, 0, image.length);
        out.write(frame, at + data.length, frame.length - at - data.length);
        return out.toByteArray();
    }

    private static int varintLength(int value) {
        int n = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            n++;
        }
        return n;
    }

    /** Where {@code part} first lies in {@code frame}, or -1. */
    private static int indexOf(byte[] frame, byte[] part) {
        outer:
        for (int i = 0; i + part.length <= frame.length; i++) {
            for (int j = 0; j < part.length; j++) {
                if (frame[i + j] != part[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    @Test
    void heightmapAndBiomeBrushesRoundTripAndRefuseWhatTheirSpecsRefuse() throws ProtocolException {
        C2S heightmap = new C2S.StrokeBegin(2, heightmap(3, 7));
        assertEquals(heightmap, roundTrip(heightmap));
        C2S biome = new C2S.StrokeBegin(3, biome("minecraft:deep_dark"));
        assertEquals(biome, roundTrip(biome));
        // A height image the radius does not fit, a missing or misplaced spec, a bad height, a bad biome id.
        assertThrows(IllegalArgumentException.class, () -> heightmap(3, 9));
        assertThrows(IllegalArgumentException.class, () -> new HeightmapSpec(3, new byte[8], 4, false));
        assertThrows(IllegalArgumentException.class, () -> new HeightmapSpec(3, new byte[9], 0, false));
        assertThrows(IllegalArgumentException.class, () -> new HeightmapSpec(3, new byte[9],
                HeightmapSpec.MAX_HEIGHT_BLOCKS + 1, false));
        assertThrows(IllegalArgumentException.class, () -> new HeightmapSpec(HeightmapSpec.MAX_SIDE + 2,
                new byte[(HeightmapSpec.MAX_SIDE + 2) * (HeightmapSpec.MAX_SIDE + 2)], 4, false));
        assertThrows(IllegalArgumentException.class, () -> new BrushSpec(BrushTool.HEIGHTMAP, 3, 1f, Falloff.SMOOTH,
                Shape.SQUARE, null, SurfaceMask.ANY, 0, 0, 1L));
        assertThrows(IllegalArgumentException.class, () -> new BrushSpec(BrushTool.RAISE, 3, 1f, Falloff.SMOOTH,
                Shape.SQUARE, null, SurfaceMask.ANY, 0, 0, 1L, null, Symmetry.NONE, null, SculptMode.TERRAIN, null, null,
                null, new BiomeSpec("minecraft:plains", true)));
        assertThrows(IllegalArgumentException.class, () -> biome("plains"));
        // On the wire: the image's side is checked before its bytes are read, and against the radius after.
        byte[] frame = Codec.encodeC2S(heightmap, STATES);
        int sideAt = indexOfSide(frame, 7);
        byte[] wrongSide = frame.clone();
        wrongSide[sideAt] = 5;
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(wrongSide));
        byte[] noSide = frame.clone();
        noSide[sideAt] = 0;
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(noSide));
        byte[] biomeFrame = Codec.encodeC2S(new C2S.StrokeBegin(3, biome("minecraft:a")), STATES);
        // The id's last character, then the columns bool: "minecraft:a" becomes "minecraft:A".
        biomeFrame[biomeFrame.length - 2] = 'A';
        assertEquals(ProtocolException.Reason.MALFORMED, reasonOf(biomeFrame));
    }

    /** Where the height image's side sits in a frame of a heightmap brush: just before its {@code side²} bytes. */
    private static int indexOfSide(byte[] frame, int side) {
        // The side, the heights, the height (varint 8) and the lower bool end the frame.
        int at = frame.length - 2 - side * side - 1;
        assertEquals(side, frame[at], "the side's place in " + HexFormat.of().formatHex(frame));
        return at;
    }
}
