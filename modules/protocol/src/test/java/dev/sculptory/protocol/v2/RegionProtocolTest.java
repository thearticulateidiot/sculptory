package dev.sculptory.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.OpSymmetry;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.transform.Transform;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Regions, entity filters and selection uploads on the wire. */
class RegionProtocolTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    /** Small coordinates, so each zigzag is one byte: the box is 6 bytes on the wire. */
    private static final Box BOX = Box.of(new BlockPos(0, 0, 0), new BlockPos(3, 3, 3));
    private static final Sha256 HASH = Sha256.digest(new byte[] {4, 2});

    private static List<Region> regions() {
        List<Region> regions = new ArrayList<>();
        regions.add(new Region.Cuboid(BOX));
        regions.add(new Region.Cuboid(new Box(new BlockPos(-30_000_000, -64, 5), new BlockPos(29_999_999, 319, 90))));
        for (ShapeKind kind : ShapeKind.values()) {
            for (Facing facing : Facing.values()) regions.add(new Region.Shape(BOX.offset(-9, 70, 12), kind, facing));
        }
        regions.add(new Region.Uploaded(HASH, BOX, 1));
        regions.add(new Region.Uploaded(HASH, BOX, 64));
        Box world = new Box(new BlockPos(-1 << 25, -64, -1 << 25), new BlockPos(1 << 25, 319, 1 << 25));
        regions.add(new Region.Uploaded(HASH, world, world.volume()));
        return regions;
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

    private static byte[] patched(byte[] frame, int index, int value) {
        byte[] copy = frame.clone();
        copy[index] = (byte) value;
        return copy;
    }

    // =================================================================== round trips

    @Test
    void everyOpRoundTripsOverEveryRegionKind() throws ProtocolException {
        Pattern stone = new Pattern.Single(STATES.parse("minecraft:stone"));
        int reqId = 1;
        for (Region region : regions()) {
            List<OpSpec> ops = new ArrayList<>(List.of(
                    new OpSpec.Fill(region, stone, CellMask.ANY),
                    new OpSpec.Replace(region, CellMask.ANY, stone),
                    new OpSpec.Erase(region, CellMask.ANY),
                    new OpSpec.Hollow(region, 2, stone),
                    new OpSpec.Walls(region, 3, stone)));
            for (EntityFilter entities : EntityFilter.values()) {
                ops.add(new OpSpec.Move(region, new BlockPos(5, -3, 0), Transform.IDENTITY, stone, entities));
                ops.add(new OpSpec.Stack(region, 0, 4, 0, 7, entities));
            }
            for (OpSpec op : ops) {
                C2S.RunOp run = new C2S.RunOp(reqId++, op, false, ConflictPolicy.SKIP_CONFLICTS);
                assertEquals(run, Codec.decodeC2S(encode(run), STATES), op.toString());
            }
            for (EntityFilter entities : EntityFilter.values()) {
                C2S.Copy copy = new C2S.Copy(reqId++, region, new BlockPos(1, 2, 3), true, CellMask.ANY, entities);
                byte[] frame = encode(copy);
                assertEquals(copy, Codec.decodeC2S(frame, STATES));
                assertEquals(OptionalInt.of(copy.reqId()), Codec.peekLeadingId(frame));
                assertEquals(region.bounds(), copy.box());
            }
        }
    }

    @Test
    void boxConstructorsMeanACuboidWithoutEntities() throws ProtocolException {
        C2S.Copy old = new C2S.Copy(3, BOX, BlockPos.ORIGIN, false, CellMask.ANY);
        assertEquals(new C2S.Copy(3, new Region.Cuboid(BOX), BlockPos.ORIGIN, false, CellMask.ANY, EntityFilter.NONE), old);
        assertEquals(old, Codec.decodeC2S(encode(old), STATES));
        OpSpec.Move move = new OpSpec.Move(BOX, BlockPos.ORIGIN, Transform.IDENTITY, new Pattern.Single(0));
        assertEquals(EntityFilter.NONE, move.entities());
        assertEquals(new Region.Cuboid(BOX), move.region());
        assertEquals(BOX, move.box());
        OpSpec.Stack stack = new OpSpec.Stack(BOX, 1, 0, 0, 2);
        assertEquals(EntityFilter.NONE, stack.entities());
        assertEquals(BOX, new OpSpec.Fill(new Region.Shape(BOX, ShapeKind.CONE, Facing.UP), new Pattern.Single(0),
                CellMask.ANY).box());
        assertEquals(new PasteOptions(true, false, true), new PasteOptions(true, false));
        assertEquals(new PasteOptions(false, false, true), PasteOptions.DEFAULT);
    }

    @Test
    void pasteOptionsCarryTheEntitiesBit() throws ProtocolException {
        for (int bits = 0; bits < 8; bits++) {
            PasteOptions options = new PasteOptions((bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0);
            C2S.RunOp run = new C2S.RunOp(2, new OpSpec.Paste(new SourceRef.Clipboard(new UUID(1, 2)), BlockPos.ORIGIN,
                    Transform.IDENTITY, options), false, ConflictPolicy.SKIP_CONFLICTS);
            byte[] frame = encode(run);
            assertEquals(run, Codec.decodeC2S(frame, STATES));
            // type, reqId, empty palette, op tag, source tag, 16-byte id, origin (3), transform, options, into,
            // symmetry (Off: one byte), physics, policy, label
            int at = frame.length - 6;
            assertEquals(bits, frame[at]);
            for (int unknown : new int[] {8, 9, 16, 0x80, 0xFF}) {
                assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(patched(frame, at, unknown)), "bits " + unknown);
            }
        }
    }

    @Test
    void selectionUploadsAndTheirAnswerRoundTripWithTheirRequestIdUpFront() throws ProtocolException {
        CellSet set = CellSet.builder().add(1, 2, 3).add(-40, 70, 900).build();
        C2S.SelectionUpload upload = new C2S.SelectionUpload(-8, set.hash(), set.bounds(), set.size(), set.encode().length);
        byte[] frame = encode(upload);
        assertEquals(upload, Codec.decodeC2S(frame, STATES));
        assertEquals(OptionalInt.of(-8), Codec.peekLeadingId(frame));
        assertEquals(RateLimiter.Kind.OPS, RateLimiter.frameKind(MessageType.SELECTION_UPLOAD));
        S2C.SelectionReady ready = new S2C.SelectionReady(-8, set.hash());
        byte[] answer = Codec.encodeS2C(ready, STATES);
        assertEquals(ready, Codec.decodeS2C(answer, STATES));
        assertEquals(OptionalInt.of(-8), Codec.peekLeadingId(answer));
        // The ops that follow name the set by its hash.
        C2S.RunOp erase = new C2S.RunOp(9, new OpSpec.Erase(new Region.Uploaded(set.hash(), set.bounds(), set.size()),
                CellMask.ANY), false, ConflictPolicy.SKIP_CONFLICTS);
        assertEquals(erase, Codec.decodeC2S(encode(erase), STATES));
    }

    @Test
    void clipboardsCountTheirEntities() throws ProtocolException {
        S2C.ClipboardReady ready = new S2C.ClipboardReady(4, new UUID(3, 4), new BlockPos(4, 4, 4), BlockPos.ORIGIN, 64,
                1024, 17);
        assertEquals(ready, Codec.decodeS2C(Codec.encodeS2C(ready, STATES), STATES));
        S2C.ClipboardReady blocksOnly = new S2C.ClipboardReady(4, new UUID(3, 4), new BlockPos(4, 4, 4), BlockPos.ORIGIN,
                64, 1024);
        assertEquals(0, blocksOnly.entities());
        byte[] frame = Codec.encodeS2C(blocksOnly, STATES);
        assertEquals(blocksOnly, Codec.decodeS2C(frame, STATES));
        // A negative count (bit 31 of the varint) is refused.
        byte[] negative = Arrays.copyOf(frame, frame.length + 4);
        System.arraycopy(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F}, 0, negative, frame.length - 1, 5);
        assertEquals(ProtocolException.Reason.MALFORMED, s2cReason(negative));
        assertThrows(IllegalArgumentException.class, () -> new S2C.ClipboardReady(1, new UUID(0, 1), BlockPos.ORIGIN,
                BlockPos.ORIGIN, 0, 0, -1));
    }

    // =================================================================== refusals

    @Test
    void cellSetsNeverTravelInline() {
        Region.Cells cells = new Region.Cells(CellSet.builder().add(0, 0, 0).build());
        ProtocolException copy = assertThrows(ProtocolException.class, () -> encode(new C2S.Copy(1, cells,
                BlockPos.ORIGIN, false, CellMask.ANY, EntityFilter.ALL)));
        assertEquals(ProtocolException.Reason.MALFORMED, copy.reason());
        ProtocolException op = assertThrows(ProtocolException.class, () -> encode(new C2S.RunOp(1,
                new OpSpec.Fill(cells, new Pattern.Single(0), CellMask.ANY), false, ConflictPolicy.SKIP_CONFLICTS)));
        assertEquals(ProtocolException.Reason.MALFORMED, op.reason());
    }

    @Test
    void malformedRegionsAreRefused() throws ProtocolException {
        // Copy: type, reqId, empty palette, then the region: its tag at index 3.
        byte[] cuboid = encode(new C2S.Copy(1, new Region.Cuboid(BOX), BlockPos.ORIGIN, false, CellMask.ANY,
                EntityFilter.DECORATIONS));
        assertEquals(0, cuboid[3]);
        for (int tag : new int[] {3, 4, 0x7F, 0xFF}) {
            assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(patched(cuboid, 3, tag)), "tag " + tag);
        }
        // The entity filter is the last byte.
        assertEquals(1, cuboid[cuboid.length - 1]);
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(patched(cuboid, cuboid.length - 1, 3)));

        // Shape: tag, box (6 bytes), kind, facing.
        byte[] shape = encode(new C2S.Copy(1, new Region.Shape(BOX, ShapeKind.PYRAMID, Facing.WEST), BlockPos.ORIGIN, false,
                CellMask.ANY, EntityFilter.NONE));
        assertEquals(1, shape[3]);
        assertEquals(ShapeKind.PYRAMID.ordinal(), shape[10]);
        assertEquals(Facing.WEST.ordinal(), shape[11]);
        for (int kind : new int[] {ShapeKind.values().length, 0x80, 0xFF}) {
            assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(patched(shape, 10, kind)), "kind " + kind);
        }
        for (int facing : new int[] {Facing.values().length, 0x80, 0xFF}) {
            assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(patched(shape, 11, facing)), "facing " + facing);
        }
        // An inverted box.
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(patched(shape, 4, 20)));

        // Uploaded: tag, hash (32), box (6), varlong cells, then origin (3), cut, mask, entities.
        byte[] uploaded = encode(new C2S.Copy(1, new Region.Uploaded(HASH, BOX, 64), BlockPos.ORIGIN, false, CellMask.ANY,
                EntityFilter.NONE));
        int cells = 3 + 1 + 32 + 6;
        assertEquals(64, uploaded[cells]);
        assertEquals(cells + 7, uploaded.length);
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(patched(uploaded, cells, 0)), "no cells");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(patched(uploaded, cells, 65)), "more cells than the box");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(Arrays.copyOf(uploaded, 20)), "truncated hash");

        // The same region inside an op.
        byte[] op = encode(new C2S.RunOp(1, new OpSpec.Erase(new Region.Cuboid(BOX), CellMask.ANY), false,
                ConflictPolicy.SKIP_CONFLICTS));
        assertEquals(0, op[4]);
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(patched(op, 4, 3)));
        byte[] stack = encode(new C2S.RunOp(1, new OpSpec.Stack(new Region.Cuboid(BOX), 1, 0, 0, 2, EntityFilter.ALL),
                false, ConflictPolicy.SKIP_CONFLICTS));
        // ..., count, entities, symmetry (Off: one byte), into, upside down (protocol 5), physics, policy, label
        assertEquals(EntityFilter.ALL.ordinal(), stack[stack.length - 7]);
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(patched(stack, stack.length - 7, 3)));
    }

    /**
     * Paste, Move and Stack carry their paste-into filter: inside a paste's
     * options, after a move's or stack's symmetry. Every value round trips; an unknown value and a frame cut before
     * it are MALFORMED, and the earlier constructors decode as EVERYTHING.
     */
    @Test
    void everyPlacingOpCarriesItsIntoFilterAndUnknownValuesAreRefused() throws ProtocolException {
        Pattern stone = new Pattern.Single(STATES.parse("minecraft:stone"));
        Region region = new Region.Cuboid(BOX);
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_Z, 4, -6);
        int reqId = 1;
        for (PasteOptions.Into into : PasteOptions.Into.values()) {
            for (Symmetry symmetry : List.of(Symmetry.NONE, mirror)) {
                List<OpSpec> ops = List.of(
                        new OpSpec.Paste(new SourceRef.Clipboard(new UUID(1, 2)), BlockPos.ORIGIN, Transform.IDENTITY,
                                new PasteOptions(true, false, true, into), symmetry),
                        new OpSpec.Move(region, new BlockPos(5, -3, 0), Transform.IDENTITY, stone, EntityFilter.ALL, symmetry, into),
                        new OpSpec.Stack(region, 0, 4, 0, 7, EntityFilter.DECORATIONS, symmetry, into));
                for (OpSpec op : ops) {
                    C2S.RunOp run = new C2S.RunOp(reqId++, op, false, ConflictPolicy.SKIP_CONFLICTS);
                    byte[] frame = encode(run);
                    assertEquals(run, Codec.decodeC2S(frame, STATES), op.toString());
                    // A paste's into sits before its symmetry (Off: one byte; the mirror: three); a move's right after it,
                    // before physics, policy and label; a stack's before its upside-down flag (protocol 5).
                    int at = op instanceof OpSpec.Paste ? frame.length - (symmetry.isOff() ? 5 : 7)
                            : op instanceof OpSpec.Stack ? frame.length - 5 : frame.length - 4;
                    assertEquals(into.ordinal(), frame[at], op.toString());
                    for (int unknown : new int[] {PasteOptions.Into.values().length, 0x7F}) {
                        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(patched(frame, at, unknown)),
                                op + " into " + unknown);
                    }
                }
            }
        }
        // The field is required: a move or stack cut after its symmetry, or a paste cut after its options byte, is refused.
        byte[] move = encode(new C2S.RunOp(1, new OpSpec.Move(region, BlockPos.ORIGIN, Transform.IDENTITY, stone, EntityFilter.NONE),
                false, ConflictPolicy.SKIP_CONFLICTS));
        assertEquals(PasteOptions.Into.EVERYTHING.ordinal(), move[move.length - 4]);
        byte[] cut = new byte[move.length - 1];
        System.arraycopy(move, 0, cut, 0, move.length - 4);
        System.arraycopy(move, move.length - 3, cut, move.length - 4, 3);
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(cut), "a move without its into");
        byte[] paste = encode(new C2S.RunOp(1, new OpSpec.Paste(new SourceRef.Clipboard(new UUID(1, 2)), BlockPos.ORIGIN,
                Transform.IDENTITY, PasteOptions.DEFAULT), false, ConflictPolicy.SKIP_CONFLICTS));
        byte[] pasteCut = new byte[paste.length - 1];
        System.arraycopy(paste, 0, pasteCut, 0, paste.length - 5);
        System.arraycopy(paste, paste.length - 4, pasteCut, paste.length - 5, 4);
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(pasteCut), "a paste without its into");
    }

    /**
     * Every op ends with its symmetry: every mode and centre
     * round trips on every op, and a malformed symmetry (an unknown mode, a missing or half centre, a centre beyond the
     * range, a mixed-parity Rotate 4 centre, bytes after Off) is refused.
     */
    @Test
    void everyOpCarriesItsSymmetryAndMalformedOnesAreRefused() throws ProtocolException {
        Pattern stone = new Pattern.Single(STATES.parse("minecraft:stone"));
        Region region = new Region.Shape(BOX, ShapeKind.CYLINDER, Facing.EAST);
        int max = Symmetry.MAX_CENTRE2;
        List<Symmetry> symmetries = new ArrayList<>();
        for (Symmetry.Mode mode : Symmetry.Mode.values()) {
            for (int[] centre : new int[][] {{0, 0}, {21, -7}, {-40, 12}, {max, -max}, {-max, max - 1}}) {
                if (mode == Symmetry.Mode.ROTATE_4 && ((centre[0] ^ centre[1]) & 1) != 0) continue;
                symmetries.add(new Symmetry(mode, centre[0], centre[1]));
            }
        }
        int reqId = 1;
        for (Symmetry symmetry : symmetries) {
            List<OpSpec> ops = List.of(
                    new OpSpec.Fill(region, stone, CellMask.ANY, symmetry),
                    new OpSpec.Replace(region, CellMask.ANY, stone, symmetry),
                    new OpSpec.Erase(region, CellMask.ANY, symmetry),
                    new OpSpec.Hollow(region, 2, stone, symmetry),
                    new OpSpec.Walls(region, 3, stone, symmetry),
                    new OpSpec.Paste(new SourceRef.Clipboard(new UUID(1, 2)), BlockPos.ORIGIN, Transform.IDENTITY,
                            PasteOptions.DEFAULT, symmetry),
                    new OpSpec.Move(region, new BlockPos(5, -3, 0), Transform.IDENTITY, stone, EntityFilter.ALL, symmetry),
                    new OpSpec.Stack(region, 0, 4, 0, 7, EntityFilter.DECORATIONS, symmetry));
            for (OpSpec op : ops) {
                C2S.RunOp run = new C2S.RunOp(reqId++, op, false, ConflictPolicy.SKIP_CONFLICTS);
                assertEquals(run, Codec.decodeC2S(encode(run), STATES), op.toString());
            }
        }
        // Each op without symmetry ends "... 0 | physics | policy" (a move "... 0 | into | physics | policy", a stack
        // "... 0 | into | upside down | physics | policy"): splice a tail in place of the 0.
        List<OpSpec> plain = List.of(
                new OpSpec.Fill(region, stone, CellMask.ANY),
                new OpSpec.Replace(region, CellMask.ANY, stone),
                new OpSpec.Erase(region, CellMask.ANY),
                new OpSpec.Hollow(region, 2, stone),
                new OpSpec.Walls(region, 3, stone),
                new OpSpec.Paste(new SourceRef.Clipboard(new UUID(1, 2)), BlockPos.ORIGIN, Transform.IDENTITY, PasteOptions.DEFAULT),
                new OpSpec.Move(region, new BlockPos(5, -3, 0), Transform.IDENTITY, stone, EntityFilter.ALL),
                new OpSpec.Stack(region, 0, 4, 0, 7, EntityFilter.DECORATIONS));
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
        for (OpSpec op : plain) {
            byte[] frame = encode(new C2S.RunOp(1, op, false, ConflictPolicy.SKIP_CONFLICTS));
            int at = op instanceof OpSpec.Stack ? frame.length - 6
                    : op instanceof OpSpec.Move ? frame.length - 5 : frame.length - 4;
            assertEquals(0, frame[at], op.toString());
            WireWriter mirror = new WireWriter(16);
            mirror.varint(Symmetry.Mode.MIRROR_X.ordinal());
            mirror.zigzag(21);
            mirror.zigzag(-7);
            C2S expected = new C2S.RunOp(1, OpSymmetry.withSymmetry(op, new Symmetry(Symmetry.Mode.MIRROR_X, 21, -7)),
                    false, ConflictPolicy.SKIP_CONFLICTS);
            assertEquals(expected, Codec.decodeC2S(spliced(frame, at, mirror.toByteArray()), STATES), op.toString());
            for (int[] values : malformed) {
                WireWriter tail = new WireWriter(32);
                tail.varint(values[0]);
                for (int i = 1; i < values.length; i++) tail.zigzag(values[i]);
                assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(spliced(frame, at, tail.toByteArray())),
                        op + " " + Arrays.toString(values));
            }
        }
        // A scatter commit carries none.
        C2S.RunOp commit = new C2S.RunOp(4, new OpSpec.ScatterCommit(new UUID(3, 4)), false, ConflictPolicy.OVERWRITE);
        assertEquals(commit, Codec.decodeC2S(encode(commit), STATES));
    }

    /** {@code frame} with the byte at {@code at} replaced by {@code tail}. */
    private static byte[] spliced(byte[] frame, int at, byte[] tail) {
        byte[] out = new byte[frame.length - 1 + tail.length];
        System.arraycopy(frame, 0, out, 0, at);
        System.arraycopy(tail, 0, out, at, tail.length);
        System.arraycopy(frame, at + 1, out, at + tail.length, frame.length - at - 1);
        return out;
    }

    @Test
    void malformedSelectionUploadsAreRefused() throws ProtocolException {
        C2S.SelectionUpload upload = new C2S.SelectionUpload(1, HASH, BOX, 10, 100);
        byte[] frame = encode(upload);
        // type, reqId, hash (32), box (6), cells, totalBytes
        int cells = 2 + 32 + 6;
        assertEquals(10, frame[cells]);
        assertEquals(100, frame[cells + 1]);
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(patched(frame, cells, 0)), "no cells");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(patched(frame, cells, 65)), "more cells than the box");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(patched(frame, cells + 1, 0)), "no bytes");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(Arrays.copyOf(frame, frame.length - 1)), "truncated");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(Arrays.copyOf(frame, frame.length + 1)), "trailing");
        assertThrows(IllegalArgumentException.class, () -> new C2S.SelectionUpload(1, HASH, BOX, 65, 100));
        assertThrows(IllegalArgumentException.class, () -> new C2S.SelectionUpload(1, HASH, BOX, 1, 0));
        byte[] ready = Codec.encodeS2C(new S2C.SelectionReady(1, HASH), STATES);
        assertEquals(ProtocolException.Reason.MALFORMED, s2cReason(Arrays.copyOf(ready, ready.length - 1)));
        // Neither travels the wrong way.
        assertEquals(ProtocolException.Reason.UNKNOWN_TYPE, s2cReason(frame));
        assertEquals(ProtocolException.Reason.UNKNOWN_TYPE, c2sReason(ready));
    }

    @Test
    void newEnumValuesAreAppended() {
        assertEquals(RejectReason.values().length - 1, RejectReason.SELECTION_NOT_LOADED.ordinal());
        assertEquals(StreamKind.SCATTER_PLACEMENTS.ordinal() + 1, StreamKind.SELECTION_UPLOAD.ordinal());
        assertEquals(StreamKind.SELECTION_UPLOAD.ordinal() + 1, StreamKind.GENERATED_UPLOAD.ordinal(), "generators appended");
        assertEquals(24, MessageType.SELECTION_UPLOAD.code());
        assertEquals(82, MessageType.SELECTION_READY.code());
        assertEquals(List.of("ELLIPSOID", "CYLINDER", "CONE", "PYRAMID"),
                Arrays.stream(ShapeKind.values()).map(Enum::name).toList());
        assertEquals(List.of("UP", "DOWN", "NORTH", "SOUTH", "EAST", "WEST"),
                Arrays.stream(Facing.values()).map(Enum::name).toList());
        assertEquals(List.of("NONE", "DECORATIONS", "ALL"), Arrays.stream(EntityFilter.values()).map(Enum::name).toList());
    }
}
