package dev.sculptory.fabric.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.fabric.client.editor.render.ghost.GhostVolume;
import dev.sculptory.server.net.PreviewPayload;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClipboardCacheTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();

    private static ClipboardCache.Preview preview(String key, long bytes) {
        return new ClipboardCache.Preview(key, new BlockPos(1, 1, 1), BlockPos.ORIGIN, 1,
                GhostVolume.of(new BlockBuffer(), h -> false), bytes, 0);
    }

    private static ClipboardCache.Entry entry(UUID id) {
        return new ClipboardCache.Entry(id, new BlockPos(2, 2, 2), BlockPos.ORIGIN, 8, 32);
    }

    // ---- Decoding ----

    @Test
    void decodesAPreviewIntoAGhostVolumeWithoutAir() throws IOException {
        Clipboard.Builder builder = Clipboard.builder(STATES, new BlockPos(20, 3, 18)).anchor(new BlockPos(10, 0, -2));
        builder.set(0, 0, 0, STATES.state("minecraft:oak_stairs[facing=south,half=top]"));
        builder.set(19, 2, 17, STATES.state("minecraft:oak_log[axis=z]"));
        builder.set(5, 1, 5, STATES.air());
        builder.set(6, 1, 5, STATES.air());
        Clipboard clipboard = builder.build();
        byte[] payload = PreviewPayload.encode(clipboard);

        ClipboardCache.Preview preview = PreviewDecoder.decode("hash-1", payload, STATES);
        assertEquals("hash-1", preview.key());
        assertEquals(new BlockPos(20, 3, 18), preview.dims());
        assertEquals(new BlockPos(10, 0, -2), preview.anchor());
        assertEquals(4, preview.cells(), "present cells, air included");
        GhostVolume volume = preview.volume();
        assertEquals(2, volume.blockCount(), "air is left out of the ghost");
        assertEquals(0, volume.eraseCount());
        assertEquals(STATES.state("minecraft:oak_stairs[facing=south,half=top]"), volume.handle(0, 0, 0));
        assertEquals(STATES.state("minecraft:oak_log[axis=z]"), volume.handle(19, 2, 17));
        assertEquals(-1, volume.handle(5, 1, 5));
        assertEquals(new Box(BlockPos.ORIGIN, new BlockPos(19, 2, 17)), volume.frame(), "the frame is the whole box");
        assertTrue(preview.bytes() > 0);
        assertEquals(0, preview.unknownCells());
    }

    @Test
    void statesThisClientDoesNotKnowAreCountedAndLeftOut() throws IOException {
        Clipboard.Builder builder = Clipboard.builder(STATES, new BlockPos(2, 1, 1));
        builder.set(0, 0, 0, STATES.state("testmod:widget[facing=up]"));
        builder.set(1, 0, 0, STATES.state("minecraft:stone"));
        byte[] payload = PreviewPayload.encode(builder.build());
        StateSpace withoutMod = new ParsingStateSpace(STATES, spec -> spec.startsWith("testmod:") ? -1 : STATES.parse(spec));
        ClipboardCache.Preview preview = PreviewDecoder.decode("k", payload, withoutMod);
        assertEquals(1, preview.unknownCells());
        assertEquals(1, preview.volume().blockCount());
    }

    @Test
    void malformedPayloadsAreRefused() {
        assertThrows(IOException.class, () -> PreviewDecoder.decode("k", new byte[] {1, 2, 3}, STATES));
        byte[] payload = PreviewPayload.encode(Clipboard.builder(STATES, new BlockPos(3, 3, 3)).build());
        byte[] truncated = Arrays.copyOf(payload, payload.length - 3);
        assertThrows(IOException.class, () -> PreviewDecoder.decode("k", truncated, STATES));
    }

    @Test
    void decodesTheSameCellsAsTheServersReference() throws IOException {
        Clipboard.Builder builder = Clipboard.builder(STATES, new BlockPos(37, 18, 21)).anchor(new BlockPos(-4, 2, 30));
        java.util.Random random = new java.util.Random(12);
        String[] specs = {"minecraft:stone", "minecraft:oak_log[axis=z]", "minecraft:oak_stairs[facing=east,half=top]",
                "minecraft:air", "minecraft:sand"};
        for (int i = 0; i < 4000; i++) {
            builder.set(random.nextInt(37), random.nextInt(18), random.nextInt(21), STATES.state(specs[random.nextInt(5)]));
        }
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 16; x < 32; x++) builder.set(x, y, z, STATES.state("minecraft:dirt"));
            }
        }
        byte[] payload = PreviewPayload.encode(builder.build());
        PreviewPayload.Decoded reference = PreviewPayload.decode(payload, 64L << 20);
        GhostVolume volume = PreviewDecoder.decode("k", payload, STATES).volume();
        for (int y = 0; y < 18; y++) {
            for (int z = 0; z < 21; z++) {
                for (int x = 0; x < 37; x++) {
                    String expected = reference.get(x, y, z);
                    int handle = expected == null || STATES.state(expected) == STATES.air() ? -1 : STATES.state(expected);
                    assertEquals(handle, volume.handle(x, y, z), x + "," + y + "," + z);
                }
            }
        }
    }

    // ---- Hostile payloads ----

    /** Writes raw {@code bspv1} payloads, valid or not, as a hostile server could. */
    private static final class Payload {
        private final java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();

        Payload varint(long value) {
            while ((value & ~0x7FL) != 0) {
                raw.write((int) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
            raw.write((int) value);
            return this;
        }

        Payload zigzag(int value) {
            return varint(((value << 1) ^ (value >> 31)) & 0xFFFFFFFFL);
        }

        Payload u8(int value) {
            raw.write(value);
            return this;
        }

        Payload header(int dx, int dy, int dz, long cells) {
            return varint(dx).varint(dy).varint(dz).zigzag(0).zigzag(0).zigzag(0).varint(cells);
        }

        Payload palette(String... states) {
            varint(states.length);
            for (String state : states) {
                byte[] utf8 = state.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                varint(utf8.length);
                raw.writeBytes(utf8);
            }
            return u8(PreviewPayload.bitsFor(states.length));
        }

        byte[] build() {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.writeBytes(new byte[] {'B', 'S', 'P', 'V', (byte) PreviewPayload.FORMAT});
            try (java.util.zip.DeflaterOutputStream zip = new java.util.zip.DeflaterOutputStream(out)) {
                raw.writeTo(zip);
            } catch (IOException impossible) {
                throw new AssertionError(impossible);
            }
            return out.toByteArray();
        }
    }

    /**
     * Whole uniform sections, each a few bytes inflated: a million of them is under 2 MB compressed, and would be
     * 16 GiB as an int[4096] per section.
     */
    private static byte[] uniformSections(int sections) {
        Payload payload = new Payload().header(65_535, 16, 65_535, (long) sections * 4096).palette("minecraft:stone");
        payload.varint(sections);
        for (int i = 0; i < sections; i++) {
            payload.varint(i % 4096).varint(0).varint(i / 4096).u8(0).varint(1);
        }
        return payload.build();
    }

    @Test
    void aSectionBombIsRefusedBeforeItAllocates() {
        byte[] bomb = uniformSections(1_000_000);
        assertTrue(bomb.length < (4 << 20), "well within the 32 MiB preview stream cap: " + bomb.length);
        PreviewDecoder.Limits manyCells = new PreviewDecoder.Limits(256L << 20, 512L << 20, 16_384, 1L << 40, 1 << 18);
        IOException refused = assertThrows(IOException.class, () -> PreviewDecoder.decode("k", bomb, STATES, manyCells));
        assertTrue(refused.getMessage().contains("sections is over the"), refused.getMessage());
        IOException byCells = assertThrows(IOException.class, () -> PreviewDecoder.decode("k", bomb, STATES));
        assertTrue(byCells.getMessage().contains("cells is over the"), byCells.getMessage());
    }

    @Test
    void theMemoryBudgetBoundsWhatIsBuilt() {
        byte[] payload = uniformSections(2_000);
        PreviewDecoder.Limits tight = new PreviewDecoder.Limits(64L << 20, 100_000, 16_384, 1L << 40, 1 << 18);
        IOException refused = assertThrows(IOException.class, () -> PreviewDecoder.decode("k", payload, STATES, tight));
        assertTrue(refused.getMessage().contains("memory"), refused.getMessage());
    }

    @Test
    void uniformSectionsDecodeWithoutPerCellArrays() throws IOException {
        PreviewDecoder.Limits roomy = new PreviewDecoder.Limits(64L << 20, 512L << 20, 16_384, 1L << 40, 1 << 18);
        ClipboardCache.Preview preview = PreviewDecoder.decode("k", uniformSections(2_000), STATES, roomy);
        assertEquals(2_000, preview.volume().sectionCount());
        assertEquals(2_000L * 4096, preview.volume().blockCount());
        assertTrue(preview.bytes() < 2_000L * 4096, "far less than 16 KiB per section: " + preview.bytes());
    }

    @Test
    void theHeaderCellCountMustMatchTheCells() {
        byte[] lying = new Payload().header(16, 16, 16, 10).palette("minecraft:stone")
                .varint(1).varint(0).varint(0).varint(0).u8(0).varint(1).build();
        IOException refused = assertThrows(IOException.class, () -> PreviewDecoder.decode("k", lying, STATES));
        assertTrue(refused.getMessage().contains("cells"), refused.getMessage());
    }

    @Test
    void oversizedPalettesAndCellCountsAreRefused() {
        String[] many = new String[300_000];
        Arrays.fill(many, "minecraft:stone");
        byte[] palette = new Payload().header(65_535, 16, 65_535, 16L << 20).palette(many).varint(0).build();
        assertThrows(IOException.class, () -> PreviewDecoder.decode("k", palette, STATES));
        byte[] cells = new Payload().header(65_535, 65_535, 65_535, (16L << 20) + 1).palette("minecraft:stone").varint(0).build();
        assertThrows(IOException.class, () -> PreviewDecoder.decode("k", cells, STATES));
        byte[] morePaletteThanCells = new Payload().header(4, 4, 4, 1).palette("minecraft:stone", "minecraft:dirt")
                .varint(0).build();
        assertThrows(IOException.class, () -> PreviewDecoder.decode("k", morePaletteThanCells, STATES));
    }

    @Test
    void repeatedOrUnorderedSectionsAreRefused() {
        byte[] repeated = new Payload().header(32, 16, 16, 100).palette("minecraft:stone")
                .varint(2).varint(1).varint(0).varint(0).u8(0).varint(1).varint(1).varint(0).varint(0).u8(0).varint(1)
                .build();
        assertThrows(IOException.class, () -> PreviewDecoder.decode("k", repeated, STATES));
        byte[] backwards = new Payload().header(32, 16, 16, 100).palette("minecraft:stone")
                .varint(2).varint(1).varint(0).varint(0).u8(0).varint(1).varint(0).varint(0).varint(0).u8(0).varint(1)
                .build();
        assertThrows(IOException.class, () -> PreviewDecoder.decode("k", backwards, STATES));
        byte[] outside = new Payload().header(16, 16, 16, 100).palette("minecraft:stone")
                .varint(1).varint(1).varint(0).varint(0).u8(0).varint(1).build();
        assertThrows(IOException.class, () -> PreviewDecoder.decode("k", outside, STATES));
        byte[] badIndex = new Payload().header(16, 16, 16, 100).palette("minecraft:stone")
                .varint(1).varint(0).varint(0).varint(0).u8(0).varint(5).build();
        assertThrows(IOException.class, () -> PreviewDecoder.decode("k", badIndex, STATES));
    }

    @Test
    void aZipBombIsCutOffAtTheInflateCap() {
        // 64 MiB of zeros deflates to about 64 KiB.
        Payload payload = new Payload().header(16, 16, 16, 1).palette("minecraft:stone").varint(0);
        byte[] zeros = new byte[1 << 20];
        for (int i = 0; i < 64; i++) payload.raw.writeBytes(zeros);
        byte[] bomb = payload.build();
        PreviewDecoder.Limits small = new PreviewDecoder.Limits(1 << 20, 512L << 20, 16_384, 16L << 20, 1 << 18);
        IOException refused = assertThrows(IOException.class, () -> PreviewDecoder.decode("k", bomb, STATES, small));
        assertTrue(refused.getMessage().contains("inflates") || refused.getMessage().contains("Trailing"),
                refused.getMessage());
    }

    // ---- The clipboard ----

    @Test
    void eachClipboardReplacesThePreviousOne() {
        SessionClipboardCache cache = new SessionClipboardCache();
        int[] changes = {0};
        cache.onChange(() -> changes[0]++);
        UUID first = new UUID(1, 1);
        UUID second = new UUID(2, 2);
        cache.setCurrent(entry(first));
        cache.putPreview(preview("h1", 10), List.of(new SourceRef.Clipboard(first)));
        cache.setCurrent(entry(second));
        assertEquals(entry(second), cache.current().orElseThrow());
        assertEquals(List.of(entry(second)), cache.entries());
        assertTrue(cache.get(first).isEmpty(), "the old id is dead");
        assertTrue(cache.preview(new SourceRef.Clipboard(first)).isEmpty(), "nor does it find a preview any more");
        assertTrue(cache.preview("h1").isPresent(), "the content (by hash) is still cached");
        cache.forgetCurrent();
        assertTrue(cache.current().isEmpty());
        assertEquals(4, changes[0]);
    }

    // ---- Previews ----

    @Test
    void previewsAreFoundByHashFromEverySourceThatAskedForThem() {
        SessionClipboardCache cache = new SessionClipboardCache();
        UUID clipboard = new UUID(3, 3);
        SourceRef asset = new SourceRef.Asset("cd".repeat(32));
        cache.putPreview(preview("same-content", 10), List.of(new SourceRef.Clipboard(clipboard)));
        cache.putPreview(preview("same-content", 10), List.of(asset));
        assertEquals(List.of("same-content"), cache.previewKeys(), "one entry per content hash");
        assertEquals(10, cache.previewBytes());
        assertEquals("same-content", cache.preview(new SourceRef.Clipboard(clipboard)).orElseThrow().key());
        assertEquals("same-content", cache.preview(asset).orElseThrow().key());
    }

    @Test
    void theLeastRecentlyUsedPreviewsAreEvictedOverTheByteCap() {
        SessionClipboardCache cache = new SessionClipboardCache(100, 10);
        SourceRef a = new SourceRef.Clipboard(new UUID(0, 1));
        SourceRef b = new SourceRef.Clipboard(new UUID(0, 2));
        SourceRef c = new SourceRef.Clipboard(new UUID(0, 3));
        cache.putPreview(preview("a", 40), List.of(a));
        cache.putPreview(preview("b", 40), List.of(b));
        cache.preview(a); // a is now the most recently used
        cache.putPreview(preview("c", 40), List.of(c));
        assertEquals(List.of("a", "c"), cache.previewKeys(), "b was the least recently used");
        assertTrue(cache.preview(b).isEmpty());
        assertEquals(80, cache.previewBytes());

        cache.putPreview(preview("huge", 500), List.of());
        assertEquals(List.of("huge"), cache.previewKeys(), "the newest stays even alone over the cap");
        assertEquals(500, cache.previewBytes());
        assertTrue(cache.preview(a).isEmpty());
    }

    @Test
    void theEntryCountIsCappedToo() {
        SessionClipboardCache cache = new SessionClipboardCache(1 << 30, 2);
        cache.putPreview(preview("1", 1), List.of());
        cache.putPreview(preview("2", 1), List.of());
        cache.putPreview(preview("3", 1), List.of());
        assertEquals(List.of("2", "3"), cache.previewKeys());
    }

    @Test
    void clearForgetsEverything() {
        SessionClipboardCache cache = new SessionClipboardCache();
        cache.setCurrent(entry(new UUID(9, 9)));
        cache.putPreview(preview("x", 5), List.of(new SourceRef.Clipboard(new UUID(9, 9))));
        cache.clear();
        assertTrue(cache.current().isEmpty());
        assertTrue(cache.previewKeys().isEmpty());
        assertEquals(0, cache.previewBytes());
    }
}
