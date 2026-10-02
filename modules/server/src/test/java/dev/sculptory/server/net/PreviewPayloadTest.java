package dev.sculptory.server.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.NbtBytes;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.testing.FakeStateSpace;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PreviewPayloadTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final long CAP = 64L << 20;

    private static Clipboard sample() {
        // 20 x 18 x 17: edge sections are partly outside the box; some cells absent (a masked copy).
        Clipboard.Builder builder = Clipboard.builder(STATES, new BlockPos(20, 18, 17)).anchor(new BlockPos(-3, 0, 25));
        Random random = new Random(11);
        String[] states = {"minecraft:stone", "minecraft:air", "minecraft:oak_stairs[facing=east,half=top]",
                "minecraft:oak_log[axis=x]", "minecraft:water[level=3]", "minecraft:chest[facing=west]"};
        for (int y = 0; y < 18; y++) {
            for (int z = 0; z < 17; z++) {
                for (int x = 0; x < 20; x++) {
                    if (random.nextInt(7) == 0) continue;
                    String spec = states[random.nextInt(states.length)];
                    builder.set(x, y, z, STATES.state(spec));
                    if (spec.startsWith("minecraft:chest")) builder.setTile(x, y, z, new NbtBytes("minecraft:chest", new byte[] {10, 0, 0, 0}));
                }
            }
        }
        // A whole uniform section.
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) builder.set(x, y, z, STATES.state("minecraft:dirt"));
            }
        }
        return builder.build();
    }

    @Test
    void roundTripsEveryCell() throws IOException {
        Clipboard clipboard = sample();
        byte[] payload = PreviewPayload.encode(clipboard);
        PreviewPayload.Decoded decoded = PreviewPayload.decode(payload, CAP);
        assertEquals(clipboard.size(), decoded.dims());
        assertEquals(clipboard.anchor(), decoded.anchor());
        assertEquals(clipboard.cellCount(), decoded.cells());
        for (int y = -1; y <= 18; y++) {
            for (int z = -1; z <= 17; z++) {
                for (int x = -1; x <= 20; x++) {
                    int state = clipboard.get(x, y, z);
                    String expected = state < 0 ? null : STATES.format(state);
                    assertEquals(expected, decoded.get(x, y, z), x + "," + y + "," + z);
                }
            }
        }
        assertTrue(decoded.sections().containsKey(BlockBuffer.key(0, 0, 0)));
        int[] uniform = decoded.sections().get(BlockBuffer.key(0, 0, 0));
        assertTrue(Arrays.stream(uniform).allMatch(i -> i == uniform[0]), "the dirt section is uniform");
        assertArrayEquals(payload, PreviewPayload.encode(clipboard), "encoding is deterministic");
    }

    @Test
    void emptyClipboardAndSinglePaletteEntry() throws IOException {
        Clipboard empty = Clipboard.builder(STATES, new BlockPos(3, 3, 3)).build();
        PreviewPayload.Decoded decoded = PreviewPayload.decode(PreviewPayload.encode(empty), CAP);
        assertEquals(0, decoded.cells());
        assertTrue(decoded.sections().isEmpty());
        assertNull(decoded.get(0, 0, 0));

        Clipboard.Builder one = Clipboard.builder(STATES, new BlockPos(1, 1, 1));
        one.set(0, 0, 0, STATES.state("minecraft:sand"));
        decoded = PreviewPayload.decode(PreviewPayload.encode(one.build()), CAP);
        assertEquals(List.of("minecraft:sand"), decoded.palette());
        assertEquals("minecraft:sand", decoded.get(0, 0, 0));
    }

    @Test
    void refusesGarbageTruncationAndBombs() {
        byte[] payload = PreviewPayload.encode(sample());
        assertThrows(IOException.class, () -> PreviewPayload.decode(new byte[] {1, 2, 3}, CAP));
        byte[] wrongFormat = payload.clone();
        wrongFormat[4] = 9;
        assertThrows(IOException.class, () -> PreviewPayload.decode(wrongFormat, CAP));
        assertThrows(IOException.class, () -> PreviewPayload.decode(Arrays.copyOf(payload, payload.length / 2), CAP));
        assertThrows(IOException.class, () -> PreviewPayload.decode(payload, 100), "inflation cap");
        byte[] corrupt = payload.clone();
        for (int i = 8; i < corrupt.length; i += 7) corrupt[i] ^= 0x5A;
        assertThrows(IOException.class, () -> PreviewPayload.decode(corrupt, CAP));
    }

    @Test
    void bitsCoverThePaletteAndTheAbsentIndex() {
        assertEquals(1, PreviewPayload.bitsFor(1));
        assertEquals(2, PreviewPayload.bitsFor(2));
        assertEquals(2, PreviewPayload.bitsFor(3));
        assertEquals(3, PreviewPayload.bitsFor(4));
        assertEquals(9, PreviewPayload.bitsFor(300));
    }

    private static EntitySnapshot entity(String type, double x, double y, double z) {
        return new EntitySnapshot(type, x, y, z, 0f, 0f, null, new byte[] {10, 0, 0, 0}, true);
    }

    @Test
    void entitiesRideInATrailerOnlyWhenThereAreAny() throws IOException {
        Clipboard plain = sample();
        Clipboard withEntities = plain.withEntities(List.of(entity("minecraft:armor_stand", 1.5, 1, 2.5),
                entity("minecraft:item_frame", 3.5, 2.5, 0.03125), entity("minecraft:armor_stand", 19.9, 17, 16.5)));
        byte[] without = PreviewPayload.encode(plain);
        assertTrue(PreviewPayload.decode(without, CAP).entities().isEmpty());
        PreviewPayload.EntitySizes sizes = type -> type.equals("minecraft:armor_stand")
                ? new float[] {0.5f, 1.975f} : new float[] {0.75f, 0.75f};
        PreviewPayload.Decoded decoded = PreviewPayload.decode(PreviewPayload.encode(withEntities, sizes), CAP);
        assertEquals(List.of(
                new PreviewPayload.Entity("minecraft:armor_stand", 1.5f, 1f, 2.5f, 0.5f, 1.975f),
                new PreviewPayload.Entity("minecraft:item_frame", 3.5f, 2.5f, 0.03125f, 0.75f, 0.75f),
                new PreviewPayload.Entity("minecraft:armor_stand", 19.9f, 17f, 16.5f, 0.5f, 1.975f)),
                decoded.entities(), "in the clipboard's (canonical) order");
        assertEquals(plain.cellCount(), decoded.cells(), "the blocks are unchanged");
        // Sizes the game should never give are replaced, not sent.
        PreviewPayload.Decoded odd = PreviewPayload.decode(PreviewPayload.encode(withEntities,
                type -> new float[] {Float.NaN, 1000f}), CAP);
        assertTrue(odd.entities().stream().allMatch(e -> e.width() == 0.5f && e.height() == PreviewPayload.MAX_ENTITY_SIZE),
                odd.entities().toString());
    }

    /** The trailer as {@link PreviewPayload#readEntities} reads it after the sections of a 4 x 4 x 4 box. */
    private static List<PreviewPayload.Entity> trailer(byte[] bytes) throws IOException {
        return PreviewPayload.readEntities(new DataInputStream(new ByteArrayInputStream(bytes)), new BlockPos(4, 4, 4));
    }

    private static byte[] oneEntity(String type, float x, float width, byte... after) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(raw);
        byte[] utf8 = type.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.write(new byte[] {1, 1, (byte) utf8.length});
        out.write(utf8);
        out.write(0);
        out.writeFloat(x);
        out.writeFloat(1f);
        out.writeFloat(1f);
        out.writeFloat(width);
        out.writeFloat(1f);
        out.write(after);
        return raw.toByteArray();
    }

    @Test
    void malformedEntityTrailersAreRefused() throws IOException {
        assertEquals(List.of(), trailer(new byte[0]), "no trailer, no entities");
        assertEquals(List.of(new PreviewPayload.Entity("minecraft:pig", 1f, 1f, 1f, 0.9f, 1f)),
                trailer(oneEntity("minecraft:pig", 1f, 0.9f)));
        assertThrows(IOException.class, () -> trailer(new byte[] {0}), "a zero count is trailing bytes");
        assertThrows(IOException.class, () -> trailer(oneEntity("minecraft:pig", 1f, 0.9f, (byte) 7)), "trailing");
        assertThrows(IOException.class, () -> trailer(oneEntity("minecraft:pig", 21f, 0.9f)), "far outside the box");
        assertThrows(IOException.class, () -> trailer(oneEntity("minecraft:pig", Float.NaN, 0.9f)), "not a number");
        assertThrows(IOException.class, () -> trailer(oneEntity("minecraft:pig", 1f, 0f)), "no size");
        assertThrows(IOException.class, () -> trailer(oneEntity("minecraft:pig", 1f, 65f)), "too big");
        byte[] good = oneEntity("minecraft:pig", 1f, 0.9f);
        assertThrows(IOException.class, () -> trailer(Arrays.copyOf(good, good.length - 1)), "truncated");
        byte[] badIndex = good.clone();
        badIndex[3 + "minecraft:pig".length()] = 1;
        assertThrows(IOException.class, () -> trailer(badIndex), "type index out of range");
        byte[] moreTypesThanEntities = good.clone();
        moreTypesThanEntities[1] = 2;
        assertThrows(IOException.class, () -> trailer(moreTypesThanEntities));
        assertThrows(IOException.class, () -> trailer(new byte[] {(byte) 0x81, (byte) 0x80, 0x04}), "count over the cap");
    }
}
