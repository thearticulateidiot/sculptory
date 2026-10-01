package dev.sculptory.core.generate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.testing.FakeStateSpace;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

/** The sparse upload payload: round trips and every refusal. */
class SparseUploadTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");
    private final int air = states.air();

    /** Stone, dirt and air cells in three sections, with gaps. */
    private GeneratedSource sample() {
        GeneratedSource.Builder builder = GeneratedSource.builder(1000);
        builder.set(-3, 60, 4, stone).set(-2, 60, 4, dirt).set(-1, 61, 4, air).set(17, 63, -9, stone).set(17, 80, -9, dirt);
        return builder.build();
    }

    private static List<int[]> cells(GeneratedSource source) {
        List<int[]> cells = new ArrayList<>();
        source.forEach((x, y, z, state) -> cells.add(new int[] {x, y, z, state}));
        return cells;
    }

    @Test
    void aSourceRoundTrips() throws SparseUploadException {
        GeneratedSource source = sample();
        assertEquals(5, source.cells());
        assertEquals(new Box(new BlockPos(-3, 60, -9), new BlockPos(17, 80, 4)), source.bounds());
        byte[] bytes = SparseUpload.encode(source, states);
        assertArrayEquals(new byte[] {'B', 'S', 'G', 'U', 1}, Arrays.copyOf(bytes, 5));
        GeneratedSource decoded = SparseUpload.decode(bytes, states, source.bounds(), 5, 1000, 1 << 16);
        assertEquals(5, decoded.cells());
        assertEquals(source.bounds(), decoded.bounds());
        List<int[]> expected = cells(source), got = cells(decoded);
        assertEquals(expected.size(), got.size());
        for (int i = 0; i < expected.size(); i++) assertArrayEquals(expected.get(i), got.get(i), "cell " + i);
        assertEquals(air, decoded.get(-1, 61, 4), "air travels as a state");
        assertEquals(-1, decoded.get(0, 60, 4), "absent stays absent");
        assertEquals(source.cellSet(), decoded.cellSet());
    }

    @Test
    void aLargeRandomSourceRoundTripsInOrder() throws SparseUploadException {
        Random random = new Random(11);
        GeneratedSource.Builder builder = GeneratedSource.builder(100_000);
        int[] palette = {stone, dirt, air, states.state("minecraft:sand"), states.state("minecraft:oak_log[axis=x]")};
        for (int i = 0; i < 20_000; i++) {
            builder.set(random.nextInt(80) - 40, random.nextInt(40) + 50, random.nextInt(80) - 40, palette[random.nextInt(5)]);
        }
        GeneratedSource source = builder.build();
        byte[] bytes = SparseUpload.encode(source, states);
        assertTrue(bytes.length < 40_000, "compressed: " + bytes.length);
        GeneratedSource decoded = SparseUpload.decode(bytes, states, source.bounds(), source.cells(), 100_000, 1 << 16);
        assertEquals(source.cells(), decoded.cells());
        source.forEach((x, y, z, state) -> assertEquals(state, decoded.get(x, y, z)));
    }

    @Test
    void anEmptySourceCannotBeEncoded() {
        assertThrows(IllegalArgumentException.class, () -> SparseUpload.encode(GeneratedSource.empty(), states));
        assertThrows(IllegalStateException.class, () -> GeneratedSource.empty().bounds());
    }

    // ---------------------------------------------------------------- refusals

    private static SparseUploadException refused(byte[] bytes, FakeStateSpace states, Box bounds, long cells, long max) {
        return refused(bytes, states, bounds, cells, max, 1 << 16);
    }

    private static SparseUploadException refused(byte[] bytes, FakeStateSpace states, Box bounds, long cells, long max,
                                                 int maxSections) {
        return assertThrows(SparseUploadException.class,
                () -> SparseUpload.decode(bytes, states, bounds, cells, max, maxSections));
    }

    @Test
    void theSectionCapBoundsTheDecodeWhateverTheCellCap() {
        GeneratedSource source = sample(); // three sections
        byte[] bytes = SparseUpload.encode(source, states);
        assertEquals(SparseUploadException.Kind.TOO_LARGE, refused(bytes, states, source.bounds(), 5, 1000, 2).kind());
        assertEquals(5, assertDecodes(bytes, source.bounds(), 3).cells());
        assertThrows(IllegalArgumentException.class, () -> SparseUpload.decode(bytes, states, source.bounds(), 5, 1000, 0));
    }

    @Test
    void aBodyInflatingPastItsBoundIsRefusedBeforeItIsHeld() {
        GeneratedSource source = sample();
        CellSet set = source.cellSet();
        // A zlib bomb: megabytes of zero indices for five cells inflate far past what five cells can need.
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        varint(body, 1);
        byte[] stone = "minecraft:stone".getBytes(StandardCharsets.UTF_8);
        varint(body, stone.length);
        body.write(stone, 0, stone.length);
        byte[] zeros = new byte[80 << 20];
        body.write(zeros, 0, zeros.length);
        byte[] bomb = assemble(set.encode(), body.toByteArray());
        assertTrue(bomb.length < 200_000, "compressed: " + bomb.length);
        assertEquals(SparseUploadException.Kind.TOO_LARGE, refused(bomb, states, source.bounds(), 5, 1000).kind());
    }

    @Test
    void anOversizedPaletteAndNonShortestVarintsAreRefused() {
        GeneratedSource source = sample();
        CellSet set = source.cellSet();
        List<String> palette = new ArrayList<>();
        for (int i = 0; i <= SparseUpload.MAX_PALETTE; i++) palette.add("minecraft:stone");
        assertEquals(SparseUploadException.Kind.MALFORMED,
                refused(craft(set, palette, new int[] {0, 0, 0, 0, 0}), states, source.bounds(), 5, 1000).kind());
        // Duplicate entries are accepted, only the indices matter.
        assertEquals(5, assertDecodes(craft(set, List.of("minecraft:stone", "minecraft:stone"), new int[] {0, 1, 0, 1, 0}),
                source.bounds(), 1 << 16).cells());
        // An index written as 0x80 0x00 (a zero in two bytes) is not in its shortest form.
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        varint(body, 1);
        byte[] stone = "minecraft:stone".getBytes(StandardCharsets.UTF_8);
        varint(body, stone.length);
        body.write(stone, 0, stone.length);
        body.write(0x80);
        body.write(0x00);
        for (int i = 0; i < 4; i++) body.write(0);
        assertEquals(SparseUploadException.Kind.MALFORMED,
                refused(assemble(set.encode(), body.toByteArray()), states, source.bounds(), 5, 1000).kind());
    }

    @Test
    void theAnnouncementMustMatchTheCellSet() {
        GeneratedSource source = sample();
        byte[] bytes = SparseUpload.encode(source, states);
        assertEquals(SparseUploadException.Kind.MALFORMED, refused(bytes, states, source.bounds(), 4, 1000).kind());
        assertEquals(SparseUploadException.Kind.MALFORMED, refused(bytes, states, source.bounds().offset(1, 0, 0), 5, 1000).kind());
        assertEquals(SparseUploadException.Kind.TOO_LARGE, refused(bytes, states, source.bounds(), 5, 4).kind());
        assertEquals(SparseUploadException.Kind.TOO_LARGE, refused(bytes, states, source.bounds(), 0, 1000).kind());
    }

    @Test
    void damagedHeadersAreRefused() {
        GeneratedSource source = sample();
        byte[] bytes = SparseUpload.encode(source, states);
        byte[] magic = bytes.clone();
        magic[0] = 'X';
        assertEquals(SparseUploadException.Kind.MALFORMED, refused(magic, states, source.bounds(), 5, 1000).kind());
        byte[] version = bytes.clone();
        version[4] = 2;
        assertEquals(SparseUploadException.Kind.MALFORMED, refused(version, states, source.bounds(), 5, 1000).kind());
        assertEquals(SparseUploadException.Kind.MALFORMED, refused(new byte[] {'B', 'S'}, states, source.bounds(), 5, 1000).kind());
        assertEquals(SparseUploadException.Kind.MALFORMED,
                refused(Arrays.copyOf(bytes, bytes.length - 3), states, source.bounds(), 5, 1000).kind());
        byte[] trailing = Arrays.copyOf(bytes, bytes.length + 1);
        assertEquals(SparseUploadException.Kind.MALFORMED, refused(trailing, states, source.bounds(), 5, 1000).kind());
        byte[] cut = Arrays.copyOf(bytes, 12);
        assertEquals(SparseUploadException.Kind.MALFORMED, refused(cut, states, source.bounds(), 5, 1000).kind());
    }

    @Test
    void thePaletteAndIndicesAreCheckedAgainstTheCells() {
        GeneratedSource source = sample();
        CellSet set = source.cellSet();
        Box bounds = source.bounds();
        // Three cells' worth of indices for five cells.
        assertEquals(SparseUploadException.Kind.MALFORMED,
                refused(craft(set, List.of("minecraft:stone"), new int[] {0, 0, 0}), states, bounds, 5, 1000).kind());
        // Six for five.
        assertEquals(SparseUploadException.Kind.MALFORMED,
                refused(craft(set, List.of("minecraft:stone"), new int[] {0, 0, 0, 0, 0, 0}), states, bounds, 5, 1000).kind());
        // An index past the palette.
        assertEquals(SparseUploadException.Kind.MALFORMED,
                refused(craft(set, List.of("minecraft:stone"), new int[] {0, 1, 0, 0, 0}), states, bounds, 5, 1000).kind());
        // An empty palette.
        assertEquals(SparseUploadException.Kind.MALFORMED,
                refused(craft(set, List.of(), new int[] {0, 0, 0, 0, 0}), states, bounds, 5, 1000).kind());
        // A state this space does not know.
        assertEquals(SparseUploadException.Kind.UNKNOWN_STATE,
                refused(craft(set, List.of("minecraft:stone", "modded:brick"), new int[] {0, 0, 0, 0, 0}), states, bounds, 5,
                        1000).kind());
        assertEquals(SparseUploadException.Kind.UNKNOWN_STATE,
                refused(craft(set, List.of("not a state"), new int[] {0, 0, 0, 0, 0}), states, bounds, 5, 1000).kind());
        // A block with a block entity: refused, whether or not any cell uses it.
        SparseUploadException tiles = refused(craft(set, List.of("minecraft:stone", "minecraft:chest[facing=north]"),
                new int[] {0, 0, 0, 0, 0}), states, bounds, 5, 1000);
        assertEquals(SparseUploadException.Kind.BLOCK_ENTITY, tiles.kind());
        assertTrue(tiles.getMessage().contains("minecraft:chest"));
        // A well-formed crafted payload decodes.
        assertEquals(5, assertDecodes(craft(set, List.of("minecraft:stone", "minecraft:dirt"), new int[] {1, 0, 1, 0, 1}),
                bounds).cells());
    }

    private GeneratedSource assertDecodes(byte[] bytes, Box bounds) {
        return assertDecodes(bytes, bounds, 1 << 16);
    }

    private GeneratedSource assertDecodes(byte[] bytes, Box bounds, int maxSections) {
        try {
            return SparseUpload.decode(bytes, states, bounds, 5, 1000, maxSections);
        } catch (SparseUploadException e) {
            throw new AssertionError(e);
        }
    }

    /** A payload with the given palette and indices (deflated), for malformed cases. */
    private static byte[] craft(CellSet set, List<String> palette, int[] indices) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        varint(body, palette.size());
        for (String spec : palette) {
            byte[] text = spec.getBytes(StandardCharsets.UTF_8);
            varint(body, text.length);
            body.write(text, 0, text.length);
        }
        for (int index : indices) varint(body, index);
        return assemble(set.encode(), body.toByteArray());
    }

    /** The header, the cell set and the deflated body. */
    private static byte[] assemble(byte[] cells, byte[] rawBody) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('B');
        out.write('S');
        out.write('G');
        out.write('U');
        out.write(1);
        varint(out, cells.length);
        out.write(cells, 0, cells.length);
        Deflater deflater = new Deflater();
        deflater.setInput(rawBody);
        deflater.finish();
        byte[] chunk = new byte[4096];
        while (!deflater.finished()) {
            int n = deflater.deflate(chunk);
            out.write(chunk, 0, n);
        }
        deflater.end();
        return out.toByteArray();
    }

    private static void varint(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }
}
