package dev.sculptory.core.schem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.biome.BiomeGrid;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.testing.FakeStateSpace;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Biomes in Sponge schematics (protocol 6): version 3 {@code Biomes} written and read back with the grid's phase,
 * version 2's per-column biomes read, version 1 has none, and biome ids data-fixed from older files.
 */
class SchematicBiomesTest {
    private static final int DATA_VERSION = 3955;
    private final FakeStateSpace states = new FakeStateSpace();
    private final int stone = states.state("minecraft:stone");
    private final DataFixHook identity = DataFixHook.identity(DATA_VERSION);

    private Clipboard solid(BlockPos size) {
        Clipboard.Builder builder = Clipboard.builder(states, size);
        for (int y = 0; y < size.y(); y++) {
            for (int z = 0; z < size.z(); z++) {
                for (int x = 0; x < size.x(); x++) builder.set(x, y, z, stone);
            }
        }
        return builder.build();
    }

    private Schematic decode(NbtCompound root, DataFixHook hook) throws SchematicException {
        return SchematicCodec.decode(root, states, SchematicCodec.Limits.DEFAULT, hook);
    }

    @Test
    void versionThreeBiomesRoundTripWithTheirPhase() throws SchematicException {
        // A 7 × 3 × 5 box copied from x = 1, y = 62, z = -3: quarts split at world x 4 and z 0.
        Clipboard copied = solid(new BlockPos(7, 3, 5)).withBiomesFrom(new BlockPos(1, 62, -3),
                (qx, qy, qz) -> qx < 1 ? "minecraft:desert" : qz < 0 ? "minecraft:plains" : "minecraft:cherry_grove");
        NbtCompound root = SchematicCodec.encode(copied, SchematicMetadata.EMPTY, DATA_VERSION);
        assertTrue(root.getCompound("Schematic").contains("Biomes"));
        Schematic back = decode(root, identity);
        assertEquals(copied.biomes(), back.clipboard().biomes());
        assertEquals(copied.contentHash(), back.clipboard().contentHash(), "the same content, the same hash");
        assertFalse(back.report().biomesSkipped());

        // A modded biome's namespace joins the required mods.
        Clipboard modded = solid(new BlockPos(2, 2, 2)).withBiomesFrom(BlockPos.ORIGIN, (qx, qy, qz) -> "glademod:glade");
        assertEquals(List.of("glademod"), SchematicCodec.encode(modded, SchematicMetadata.EMPTY, DATA_VERSION)
                .getCompound("Schematic").getCompound("Metadata").getList("RequiredMods").strings());
        assertTrue(decode(SchematicCodec.encode(modded, SchematicMetadata.EMPTY, DATA_VERSION), identity).clipboard()
                .biomes() != null);

        Clipboard plain = solid(new BlockPos(2, 2, 2));
        assertFalse(SchematicCodec.encode(plain, SchematicMetadata.EMPTY, DATA_VERSION).getCompound("Schematic")
                .contains("Biomes"), "no biomes, no Biomes tag");
        assertNull(decode(SchematicCodec.encode(plain, SchematicMetadata.EMPTY, DATA_VERSION), identity).clipboard()
                .biomes());
    }

    @Test
    void versionTwoReadsABiomePerColumnAtItsOffsetsPhase() throws SchematicException {
        NbtCompound biomePalette = NbtCompound.builder().putInt("minecraft:plains", 0).putInt("desert", 1).build();
        // 2 × 1 × 1: column x 0 plains, x 1 desert ("desert" gets its namespace).
        NbtCompound schem = NbtCompound.builder().putInt("Version", 2).putInt("DataVersion", DATA_VERSION)
                .putShort("Width", (short) 2).putShort("Height", (short) 6).putShort("Length", (short) 1)
                .putIntArray("Offset", new int[] {3, 0, 0})
                .put("Palette", NbtCompound.builder().putInt("minecraft:stone", 0).build())
                .putByteArray("BlockData", new byte[12])
                .put("BiomePalette", biomePalette).putInt("BiomePaletteMax", 2)
                .putByteArray("BiomeData", new byte[] {0, 1}).build();
        Schematic read = decode(schem, identity);
        BiomeGrid grid = read.clipboard().biomes();
        assertEquals(new BlockPos(3, 0, 0), grid.phase(), "the minimum corner x 3 sits at 3 in its quart");
        assertEquals("minecraft:plains", grid.biomeAt(0, 0, 0));
        assertEquals("minecraft:desert", grid.biomeAt(1, 5, 0), "the next quart, the whole height");
    }

    @Test
    void versionOneHasNoBiomesAndOlderIdsAreDataFixed() throws SchematicException {
        NbtCompound v1 = NbtCompound.builder().putInt("Version", 1)
                .putShort("Width", (short) 1).putShort("Height", (short) 1).putShort("Length", (short) 1)
                .put("Palette", NbtCompound.builder().putInt("minecraft:stone", 0).build())
                .putByteArray("BlockData", new byte[1]).build();
        assertNull(decode(v1, identity).clipboard().biomes());

        DataFixHook renaming = new DataFixHook() {
            @Override
            public int targetDataVersion() {
                return DATA_VERSION;
            }

            @Override
            public String fixBlockState(String state, int fromDataVersion) {
                return state;
            }

            @Override
            public NbtCompound fixBlockEntity(NbtCompound blockEntity, int fromDataVersion) {
                return blockEntity;
            }

            @Override
            public String fixBiome(String biomeId, int fromDataVersion) {
                return biomeId.equals("minecraft:mountains") ? "minecraft:windswept_hills" : biomeId;
            }
        };
        NbtCompound old = NbtCompound.builder().put("Schematic", NbtCompound.builder().putInt("Version", 3)
                .putInt("DataVersion", 2586)
                .putShort("Width", (short) 1).putShort("Height", (short) 1).putShort("Length", (short) 1)
                .put("Blocks", NbtCompound.builder().put("Palette", NbtCompound.builder().putInt("minecraft:stone", 0)
                        .build()).putByteArray("Data", new byte[1]).build())
                .put("Biomes", NbtCompound.builder().put("Palette", NbtCompound.builder()
                        .putInt("minecraft:mountains", 0).build()).putByteArray("Data", new byte[1]).build())
                .build()).build();
        assertEquals("minecraft:windswept_hills", decode(old, renaming).clipboard().biomes().biomeAt(0, 0, 0));
    }
}
