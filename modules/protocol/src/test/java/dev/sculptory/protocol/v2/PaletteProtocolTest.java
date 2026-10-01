package dev.sculptory.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.palette.BlockPalette;
import dev.sculptory.core.palette.PalettePattern;
import dev.sculptory.core.testing.FakeStateSpace;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** Palettes on the wire: save, load, the data answer and the listing's entry kind. */
class PaletteProtocolTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final BlockPalette MOSS = new BlockPalette(List.of(
            new BlockPalette.Entry("minecraft:moss_block", 4),
            new BlockPalette.Entry("minecraft:sea_pickle[pickles=2,waterlogged=true]", 1000),
            new BlockPalette.Entry("modded:été", 1)));

    private static byte[] encode(C2S message) throws ProtocolException {
        return Codec.encodeC2S(message, STATES);
    }

    private static byte[] encode(S2C message) throws ProtocolException {
        return Codec.encodeS2C(message, STATES);
    }

    private static ProtocolException.Reason c2sReason(byte[] frame) {
        return assertThrows(ProtocolException.class, () -> Codec.decodeC2S(frame, STATES)).reason();
    }

    private static ProtocolException.Reason s2cReason(byte[] frame) {
        return assertThrows(ProtocolException.class, () -> Codec.decodeS2C(frame, STATES)).reason();
    }

    /** A varint as the codec writes it. */
    private static void varint(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static void string(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        varint(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    /** The bytes of the Random pattern with its default values, as a palette's pattern (kind, size, edges, seed). */
    private static final int[] RANDOM_PATTERN = {0, 6, 4, 10, 0};

    /** A hand-made {@code PaletteSave} frame: request 1, path "p.palette.json", the given entries, the Random pattern. */
    private static byte[] saveFrame(int count, List<String> states, List<Integer> weights) {
        return saveFrame(count, states, weights, RANDOM_PATTERN);
    }

    /** A hand-made {@code PaletteSave} frame with the pattern given as its bytes (none: a frame from protocol 4). */
    private static byte[] saveFrame(int count, List<String> states, List<Integer> weights, int... pattern) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        varint(out, MessageType.PALETTE_SAVE.code());
        varint(out, 2); // zigzag 1
        string(out, "p.palette.json");
        varint(out, count);
        for (int i = 0; i < states.size(); i++) {
            string(out, states.get(i));
            varint(out, weights.get(i));
        }
        for (int b : pattern) varint(out, b);
        return out.toByteArray();
    }

    @Test
    void requestsAndTheAnswerRoundTripWithTheirRequestIdUpFront() throws ProtocolException {
        List<C2S> requests = List.of(
                new C2S.PaletteSave(7, "palettes/moss.palette.json", MOSS),
                new C2S.PaletteSave(-3, "", BlockPalette.of("minecraft:stone", 1)),
                new C2S.PaletteLoad(8, "_players/00000000-0000-4000-8000-00000000000a/x.palette.json"),
                new C2S.PaletteLoad(0, ""));
        for (C2S request : requests) {
            byte[] frame = encode(request);
            assertEquals(request, Codec.decodeC2S(frame, STATES));
            int reqId = switch (request) {
                case C2S.PaletteSave m -> m.reqId();
                case C2S.PaletteLoad m -> m.reqId();
                default -> throw new AssertionError(request);
            };
            assertEquals(OptionalInt.of(reqId), Codec.peekLeadingId(frame), request.type() + " leading id");
        }
        S2C.PaletteData data = new S2C.PaletteData(8, "palettes/moss.palette.json", MOSS, 5,
                List.of("modded:gone", "minecraft:nope[a=b]", "x"));
        byte[] frame = encode(data);
        assertEquals(data, Codec.decodeS2C(frame, STATES));
        assertEquals(OptionalInt.of(8), Codec.peekLeadingId(frame));
        S2C.PaletteData clean = new S2C.PaletteData(9, "a.palette.json", MOSS);
        assertEquals(clean, Codec.decodeS2C(encode(clean), STATES));
    }

    @Test
    void theFullestPaletteFitsOneClientFrame() throws ProtocolException {
        List<BlockPalette.Entry> entries = new ArrayList<>();
        for (int i = 0; i < BlockPalette.MAX_ENTRIES; i++) {
            String suffix = Integer.toString(100 + i);
            // Two-byte characters: every state is exactly at the byte cap.
            String state = "m:" + "é".repeat((BlockPalette.MAX_STATE_BYTES - 2 - suffix.length()) / 2) + suffix;
            if (state.getBytes(StandardCharsets.UTF_8).length < BlockPalette.MAX_STATE_BYTES) state = state + "x";
            assertEquals(BlockPalette.MAX_STATE_BYTES, state.getBytes(StandardCharsets.UTF_8).length);
            entries.add(new BlockPalette.Entry(state, BlockPalette.MAX_WEIGHT));
        }
        C2S.PaletteSave save = new C2S.PaletteSave(Integer.MAX_VALUE, "p".repeat(Codec.MAX_PATH_BYTES),
                new BlockPalette(entries));
        byte[] frame = encode(save);
        assertTrue(frame.length <= ProtocolV2.MAX_C2S_FRAME, frame.length + " bytes");
        assertEquals(save, Codec.decodeC2S(frame, STATES));
    }

    @Test
    void theModelRefusesWhatTheWireMustNotCarry() {
        assertThrows(IllegalArgumentException.class, () -> new BlockPalette(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new BlockPalette.Entry("minecraft:stone", 0));
        assertThrows(IllegalArgumentException.class, () -> new BlockPalette.Entry("minecraft:stone", 1001));
        assertThrows(IllegalArgumentException.class, () -> new BlockPalette.Entry(" ", 1));
        assertThrows(IllegalArgumentException.class,
                () -> new BlockPalette.Entry("m:" + "x".repeat(BlockPalette.MAX_STATE_BYTES - 1), 1));
        assertThrows(IllegalArgumentException.class, () -> new BlockPalette(List.of(
                new BlockPalette.Entry("minecraft:stone", 1), new BlockPalette.Entry("minecraft:stone", 2))));
        List<BlockPalette.Entry> tooMany = new ArrayList<>();
        for (int i = 0; i <= BlockPalette.MAX_ENTRIES; i++) tooMany.add(new BlockPalette.Entry("m:b" + i, 1));
        assertThrows(IllegalArgumentException.class, () -> new BlockPalette(tooMany));
        assertThrows(IllegalArgumentException.class, () -> new S2C.PaletteData(1, "a", MOSS, 1, List.of("a", "b")));
        assertThrows(IllegalArgumentException.class,
                () -> new S2C.PaletteData(1, "a", MOSS, 9, List.of("a", "b", "c", "d")));
        assertThrows(IllegalArgumentException.class, () -> new S2C.PaletteData(1, "a", MOSS, -1, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new S2C.PaletteData(1, "a", MOSS, BlockPalette.MAX_ENTRIES + 1, List.of()));
    }

    @Test
    void malformedPaletteBodiesAreRefusedBeforeAnythingIsBuilt() {
        // A well-formed hand-made frame decodes (the helper matches the codec).
        assertEquals(new C2S.PaletteSave(1, "p.palette.json", BlockPalette.of("minecraft:stone", 3)),
                assertDoesNotThrowDecode(saveFrame(1, List.of("minecraft:stone"), List.of(3))));
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(saveFrame(0, List.of(), List.of())), "no entries");
        assertEquals(ProtocolException.Reason.TOO_LARGE,
                c2sReason(saveFrame(BlockPalette.MAX_ENTRIES + 1, List.of(), List.of())), "too many entries");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(saveFrame(1, List.of("minecraft:stone"), List.of(0))),
                "weight 0");
        assertEquals(ProtocolException.Reason.MALFORMED,
                c2sReason(saveFrame(1, List.of("minecraft:stone"), List.of(BlockPalette.MAX_WEIGHT + 1))), "weight 1001");
        assertEquals(ProtocolException.Reason.MALFORMED,
                c2sReason(saveFrame(1, List.of("minecraft:stone"), List.of(Integer.MAX_VALUE))), "a huge weight");
        assertEquals(ProtocolException.Reason.MALFORMED,
                c2sReason(saveFrame(2, List.of("minecraft:stone", "minecraft:stone"), List.of(1, 2))), "listed twice");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(saveFrame(1, List.of(""), List.of(1))), "empty state");
        assertEquals(ProtocolException.Reason.TOO_LARGE,
                c2sReason(saveFrame(1, List.of("m:" + "x".repeat(BlockPalette.MAX_STATE_BYTES)), List.of(1))),
                "a state over the byte cap");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(saveFrame(3, List.of("minecraft:stone"), List.of(1))),
                "a count the frame doesn't hold");
        byte[] trailing = saveFrame(1, List.of("minecraft:stone"), List.of(1));
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(Arrays.copyOf(trailing, trailing.length + 1)));
    }

    /**
     * A palette's pattern (protocol 5) travels with it both ways, every kind and
     * value at its limits; an unknown kind, a value out of range and a frame without a pattern (protocol 4) are
     * malformed.
     */
    @Test
    void palettePatternsRoundTripAndMalformedOnesAreRefused() throws ProtocolException {
        for (PalettePattern.Kind kind : PalettePattern.Kind.values()) {
            for (PalettePattern pattern : List.of(new PalettePattern(kind, 1, 0, 0, Long.MIN_VALUE),
                    new PalettePattern(kind, 32, 32, 45, Long.MAX_VALUE), new PalettePattern(kind, 7, 3, 12, -5))) {
                C2S.PaletteSave save = new C2S.PaletteSave(3, "a.palette.json", MOSS.withPattern(pattern));
                assertEquals(save, Codec.decodeC2S(encode(save), STATES));
                S2C.PaletteData data = new S2C.PaletteData(4, "a.palette.json", MOSS.withPattern(pattern), 1, List.of("x"));
                assertEquals(data, Codec.decodeS2C(encode(data), STATES));
            }
        }
        List<String> stone = List.of("minecraft:stone");
        List<Integer> one = List.of(1);
        assertEquals(new C2S.PaletteSave(1, "p.palette.json", BlockPalette.of("minecraft:stone", 1)
                        .withPattern(new PalettePattern(PalettePattern.Kind.STEEPNESS, 2, 3, 4, -1))),
                assertDoesNotThrowDecode(saveFrame(1, stone, one, 3, 2, 3, 4, 1)));
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(saveFrame(1, stone, one, 4, 6, 4, 10, 0)),
                "an unknown pattern kind");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(saveFrame(1, stone, one, 1, 0, 4, 10, 0)), "size 0");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(saveFrame(1, stone, one, 1, 33, 4, 10, 0)), "size 33");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(saveFrame(1, stone, one, 2, 6, 33, 10, 0)), "edge 33");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(saveFrame(1, stone, one, 3, 6, 4, 46, 0)),
                "steepness edge 46");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(saveFrame(1, stone, one, new int[0])),
                "no pattern (protocol 4)");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(saveFrame(1, stone, one, 0, 6, 4, 10)), "no seed");
        assertThrows(IllegalArgumentException.class, () -> new PalettePattern(PalettePattern.Kind.PATCHES, 0, 4, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> new PalettePattern(PalettePattern.Kind.GRADIENT, 6, -1, 10, 0));
        assertThrows(NullPointerException.class, () -> new PalettePattern(null, 6, 4, 10, 0));
        assertThrows(NullPointerException.class, () -> new BlockPalette(List.of(new BlockPalette.Entry("a:b", 1)), null));
    }

    private static C2S assertDoesNotThrowDecode(byte[] frame) {
        try {
            return Codec.decodeC2S(frame, STATES);
        } catch (ProtocolException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void truncatedFramesAreMalformedAtEveryLength() throws ProtocolException {
        byte[] save = encode(new C2S.PaletteSave(300, "a/b.palette.json", MOSS));
        for (int length = 1; length < save.length; length++) {
            assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(Arrays.copyOf(save, length)), "cut to " + length);
        }
        byte[] data = encode(new S2C.PaletteData(300, "a/b.palette.json", MOSS, 2, List.of("x")));
        for (int length = 1; length < data.length; length++) {
            assertEquals(ProtocolException.Reason.MALFORMED, s2cReason(Arrays.copyOf(data, length)), "cut to " + length);
        }
    }

    @Test
    void droppedCountsTheAnswerCannotHoldAreMalformed() throws ProtocolException {
        byte[] frame = encode(new S2C.PaletteData(1, "a.palette.json", BlockPalette.of("minecraft:stone", 1), 1,
                List.of("x")));
        // type, reqId, path (15 bytes with its length), palette (1 + 1 + 16 + 1, its pattern 5), dropped, names
        int droppedAt = 1 + 1 + 15 + 1 + 1 + "minecraft:stone".length() + 1 + RANDOM_PATTERN.length;
        assertEquals(1, frame[droppedAt]);
        byte[] fewer = frame.clone();
        fewer[droppedAt] = 0; // fewer dropped than names given
        assertEquals(ProtocolException.Reason.MALFORMED, s2cReason(fewer));
        byte[] more = frame.clone();
        more[droppedAt] = (byte) (BlockPalette.MAX_ENTRIES + 1);
        assertEquals(ProtocolException.Reason.MALFORMED, s2cReason(more));
    }

    @Test
    void listingEntriesCarryTheirKind() throws ProtocolException {
        List<S2C.LibraryListing.Entry> entries = List.of(
                new S2C.LibraryListing.Entry("trees", true, 0, ""),
                new S2C.LibraryListing.Entry("oak.schem", false, 12, "a".repeat(64)),
                new S2C.LibraryListing.Entry("moss.palette.json", false, 300, "", S2C.LibraryListing.Entry.Kind.PALETTE));
        S2C.LibraryListing listing = new S2C.LibraryListing(4, "", entries, true);
        assertEquals(listing, Codec.decodeS2C(encode(listing), STATES));
        assertEquals(S2C.LibraryListing.Entry.Kind.FOLDER, entries.get(0).kind());
        assertEquals(S2C.LibraryListing.Entry.Kind.SCHEMATIC, entries.get(1).kind());
        assertThrows(IllegalArgumentException.class,
                () -> new S2C.LibraryListing.Entry("x", true, 0, "", S2C.LibraryListing.Entry.Kind.PALETTE));
        assertThrows(IllegalArgumentException.class,
                () -> new S2C.LibraryListing.Entry("x", false, 0, "", S2C.LibraryListing.Entry.Kind.FOLDER));

        // One palette entry: its kind is the byte before its restricted flag (per-asset access) and the writable flag.
        byte[] frame = encode(new S2C.LibraryListing(1, "", List.of(entries.get(2)), false));
        assertEquals(S2C.LibraryListing.Entry.Kind.PALETTE.ordinal(), frame[frame.length - 3]);
        byte[] unknownKind = frame.clone();
        unknownKind[frame.length - 3] = 9;
        assertEquals(ProtocolException.Reason.MALFORMED, s2cReason(unknownKind), "a kind this client doesn't know");
        byte[] folderKind = frame.clone();
        folderKind[frame.length - 3] = (byte) S2C.LibraryListing.Entry.Kind.FOLDER.ordinal();
        assertEquals(ProtocolException.Reason.MALFORMED, s2cReason(folderKind), "a file that says it is a folder");
    }

    @Test
    void theNewTypesKeepTheirCodesDirectionAndBucket() throws ProtocolException {
        assertArrayEquals(new int[] {22, 23, 81}, new int[] {MessageType.PALETTE_SAVE.code(),
                MessageType.PALETTE_LOAD.code(), MessageType.PALETTE_DATA.code()});
        for (MessageType type : List.of(MessageType.PALETTE_SAVE, MessageType.PALETTE_LOAD)) {
            assertEquals(RateLimiter.Kind.OPS, RateLimiter.frameKind(type), type + " is charged like other requests");
            assertTrue(type.clientToServer());
            assertFalse(type.serverToClient());
        }
        assertTrue(MessageType.PALETTE_DATA.serverToClient());
        assertFalse(MessageType.PALETTE_DATA.clientToServer());
        assertEquals(ProtocolException.Reason.UNKNOWN_TYPE,
                c2sReason(encode(new S2C.PaletteData(1, "a", MOSS))), "a server message sent to the server");
        assertEquals(ProtocolException.Reason.UNKNOWN_TYPE,
                s2cReason(encode(new C2S.PaletteLoad(1, "a"))), "a client message sent to the client");
        assertTrue(ProtocolV2.VERSION >= 5, "palettes ship in protocol 4 and later, with their patterns from 5");
    }
}
