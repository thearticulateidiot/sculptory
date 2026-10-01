package dev.sculptory.core.history.store;

import static dev.sculptory.core.history.store.StoreTestSupport.AIR;
import static dev.sculptory.core.history.store.StoreTestSupport.CHEST;
import static dev.sculptory.core.history.store.StoreTestSupport.CODEC;
import static dev.sculptory.core.history.store.StoreTestSupport.DIRT;
import static dev.sculptory.core.history.store.StoreTestSupport.STONE;
import static dev.sculptory.core.history.store.StoreTestSupport.assertSameSection;
import static dev.sculptory.core.history.store.StoreTestSupport.tile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SectionCodecTest {
    /** A codec with unlimited synthetic states ("test:s<n>") and tiles that remember their trust. */
    private static final HistoryCodec SYNTHETIC = new HistoryCodec() {
        @Override
        public int dataVersion() {
            return 1;
        }

        @Override
        public String stateText(int handle) {
            return "test:s" + handle;
        }

        @Override
        public int state(String text) {
            if (!text.startsWith("test:s")) return -1;
            try {
                return Integer.parseInt(text.substring(6));
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        @Override
        public Trust trust(BlockEntityData tile) {
            return tile instanceof TrustedTile trusted ? trusted.trust : Trust.FOREIGN;
        }

        @Override
        public BlockEntityData tile(String typeId, byte[] nbt, Trust trust) {
            return new TrustedTile(typeId, nbt, trust);
        }
    };

    private record TrustedTile(String typeId, byte[] bytes, HistoryCodec.Trust trust) implements BlockEntityData {
        @Override
        public byte[] nbtBytes() {
            return bytes.clone();
        }

        @Override
        public int estimatedBytes() {
            return 32 + bytes.length;
        }

        @Override
        public boolean sameContent(BlockEntityData o) {
            return o != null && typeId.equals(o.typeId()) && Arrays.equals(bytes, o.nbtBytes());
        }
    }

    private static SectionBuffer[] pair(int cells, java.util.function.IntUnaryOperator before,
                                        java.util.function.IntUnaryOperator after, int stride) {
        SectionBuffer b = new SectionBuffer(), a = new SectionBuffer();
        for (int n = 0, i = 0; n < cells; n++, i = (i + stride) % SectionBuffer.SIZE) {
            b.set(i, before.applyAsInt(i));
            a.set(i, after.applyAsInt(i));
        }
        return new SectionBuffer[] {b, a};
    }

    private static SectionBuffer[] roundTrip(SectionBuffer[] pair, HistoryCodec codec) throws CorruptDataException {
        byte[] body = SectionCodec.encode(pair[0], pair[1], codec);
        SectionBuffer[] back = SectionCodec.decode(body, codec);
        assertSameSection(pair[0], back[0]);
        assertSameSection(pair[1], back[1]);
        return back;
    }

    @Test
    void emptySectionRoundTripsAsNull() throws CorruptDataException {
        byte[] body = SectionCodec.encode(null, null, CODEC);
        assertNull(SectionCodec.decode(body, CODEC));
        assertEquals(2, body.length);
    }

    @Test
    void everyPresenceModeRoundTrips() throws CorruptDataException {
        roundTrip(pair(1, i -> AIR, i -> STONE, 1), CODEC);
        roundTrip(pair(256, i -> AIR, i -> DIRT, 13), CODEC); // index list
        roundTrip(pair(257, i -> AIR, i -> DIRT, 13), CODEC); // bitmap
        roundTrip(pair(4095, i -> i % 2 == 0 ? AIR : DIRT, i -> STONE, 1), CODEC);
        SectionBuffer[] dense = roundTrip(pair(4096, i -> i % 5 == 0 ? DIRT : AIR, i -> STONE, 1), CODEC);
        assertTrue(dense[0].isDense() && dense[1].isDense());
    }

    @Test
    void uniformAndSameStateCellsRoundTrip() throws CorruptDataException {
        // An edit that changed only NBT keeps cells whose state is the same before and after.
        SectionBuffer b = new SectionBuffer(), a = new SectionBuffer();
        b.set(7, CHEST);
        b.setTile(7, tile("old"));
        a.set(7, CHEST);
        a.setTile(7, tile("new"));
        roundTrip(new SectionBuffer[] {b, a}, CODEC);
        roundTrip(new SectionBuffer[] {SectionBuffer.uniform(STONE), SectionBuffer.uniform(AIR)}, CODEC);
    }

    @Test
    void widePalettesRoundTrip() throws CorruptDataException {
        // More than 256 distinct states: two-byte palette indices.
        roundTrip(pair(4096, i -> i, i -> 5000 + (i % 700), 1), SYNTHETIC);
        roundTrip(pair(300, i -> i * 3, i -> i * 3 + 1, 7), SYNTHETIC);
    }

    @Test
    void tilesKeepTheirTrustAndOrder() throws CorruptDataException {
        SectionBuffer b = new SectionBuffer(), a = new SectionBuffer();
        for (int i : new int[] {0, 5, 4095, 17}) {
            b.set(i, 3);
            a.set(i, 4);
        }
        b.setTile(0, new TrustedTile("minecraft:chest", new byte[] {1, 2}, HistoryCodec.Trust.CAPTURED));
        a.setTile(0, new TrustedTile("minecraft:chest", new byte[] {3}, HistoryCodec.Trust.FOREIGN));
        a.setTile(17, new TrustedTile("minecraft:sign", new byte[0], HistoryCodec.Trust.SANITIZED));
        b.setTile(4095, new TrustedTile("minecraft:barrel", new byte[1000], HistoryCodec.Trust.CAPTURED));
        SectionBuffer[] back = roundTrip(new SectionBuffer[] {b, a}, SYNTHETIC);
        assertEquals(HistoryCodec.Trust.CAPTURED, ((TrustedTile) back[0].tile(0)).trust());
        assertEquals(HistoryCodec.Trust.FOREIGN, ((TrustedTile) back[1].tile(0)).trust());
        assertEquals(HistoryCodec.Trust.SANITIZED, ((TrustedTile) back[1].tile(17)).trust());
        assertEquals(HistoryCodec.Trust.CAPTURED, ((TrustedTile) back[0].tile(4095)).trust());
        assertNull(back[0].tile(5));
    }

    @Test
    void largeBodiesAreDeflatedAndSmallOnesAreNot() {
        SectionBuffer[] dense = pair(4096, i -> AIR, i -> STONE, 1);
        byte[] body = SectionCodec.encode(dense[0], dense[1], CODEC);
        assertEquals(1, body[0], "deflated");
        assertTrue(body.length < 200, "a uniform fill compresses: " + body.length);
        SectionBuffer[] one = pair(1, i -> AIR, i -> STONE, 1);
        assertEquals(0, SectionCodec.encode(one[0], one[1], CODEC)[0], "stored raw");
    }

    /**
     * A section whose raw body is larger than {@code decode} accepts is refused when encoded, however well it
     * compresses (here with a 1 MiB limit standing in for the real 256 MiB): it would otherwise be written and then
     * dropped as damaged when read back. Under the limit it round-trips.
     */
    @Test
    void aSectionLargerThanDecodeAcceptsIsRefusedWhenEncoded() throws CorruptDataException {
        SectionBuffer before = new SectionBuffer(), after = new SectionBuffer();
        byte[] zeros = new byte[300 << 10];
        for (int i = 0; i < 4; i++) {
            before.set(i, AIR);
            after.set(i, CHEST);
            after.setTile(i, new dev.sculptory.core.buffer.NbtBytes("minecraft:chest", zeros));
        }
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> SectionCodec.encode(before, after, CODEC, 1 << 20));
        assertTrue(refused.getMessage().startsWith("Section too large"), refused.getMessage());
        byte[] body = SectionCodec.encode(before, after, CODEC, 2 << 20);
        assertTrue(body.length < 64 << 10, "it compresses well: " + body.length);
        SectionBuffer[] back = SectionCodec.decode(body, CODEC);
        assertSameSection(after, back[1]);
    }

    @Test
    void unpairedBuffersAreRefused() {
        SectionBuffer b = new SectionBuffer(), a = new SectionBuffer();
        b.set(1, AIR);
        a.set(2, STONE);
        assertThrows(IllegalArgumentException.class, () -> SectionCodec.encode(b, a, CODEC));
        assertThrows(IllegalArgumentException.class, () -> SectionCodec.encode(b, new SectionBuffer(), CODEC));
    }

    @Test
    void unknownStatesAreUnrestorable() {
        SectionBuffer[] p = pair(10, i -> 900 + i, i -> 950, 1);
        byte[] body = SectionCodec.encode(p[0], p[1], SYNTHETIC);
        HistoryCodec forgetful = new HistoryCodec() {
            @Override
            public int dataVersion() {
                return 1;
            }

            @Override
            public String stateText(int handle) {
                return SYNTHETIC.stateText(handle);
            }

            @Override
            public int state(String text) {
                return text.equals("test:s905") ? -1 : SYNTHETIC.state(text);
            }

            @Override
            public Trust trust(BlockEntityData tile) {
                return Trust.FOREIGN;
            }

            @Override
            public BlockEntityData tile(String typeId, byte[] nbt, Trust trust) throws CorruptDataException {
                return SYNTHETIC.tile(typeId, nbt, trust);
            }
        };
        UnrestorableException e = assertThrows(UnrestorableException.class, () -> SectionCodec.decode(body, forgetful));
        assertTrue(e.getMessage().contains("test:s905"));
    }

    /** Damaged bodies never decode into something else silently, and never fail with anything but CorruptData. */
    @Test
    void damagedBodiesFailCleanly() throws CorruptDataException {
        Random random = new Random(42);
        SectionBuffer[][] samples = {
            pair(4096, i -> i % 7, i -> 10 + i % 3, 1), pair(40, i -> 2, i -> 3, 97), pair(300, i -> i % 300, i -> 1, 11)
        };
        samples[1][1].setTile(0, new TrustedTile("minecraft:chest", new byte[] {9, 9, 9}, HistoryCodec.Trust.CAPTURED));
        for (SectionBuffer[] sample : samples) {
            byte[] body = SectionCodec.encode(sample[0], sample[1], SYNTHETIC);
            for (int trial = 0; trial < 400; trial++) {
                byte[] damaged = body.clone();
                int kind = trial % 3;
                if (kind == 0) {
                    damaged = Arrays.copyOf(body, random.nextInt(body.length));
                } else {
                    for (int flips = 1 + random.nextInt(3); flips > 0; flips--) {
                        damaged[random.nextInt(damaged.length)] ^= (byte) (1 + random.nextInt(255));
                    }
                }
                try {
                    SectionBuffer[] back = SectionCodec.decode(damaged, SYNTHETIC);
                    if (back != null && back[0].presentCount() != back[1].presentCount()) fail("unpaired decode");
                } catch (CorruptDataException expected) {
                    // Refused.
                } catch (RuntimeException e) {
                    fail("damaged body failed with " + e, e);
                }
            }
        }
    }
}
