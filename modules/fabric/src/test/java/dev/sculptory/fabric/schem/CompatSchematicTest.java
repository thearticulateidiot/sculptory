package dev.sculptory.fabric.schem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.biome.BiomeGrid;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.nbt.NbtLimits;
import dev.sculptory.core.schem.Schematic;
import dev.sculptory.core.schem.SchematicCodec;
import dev.sculptory.core.schem.SchematicFormat;
import dev.sculptory.fabric.world.FabricStateSpace;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The {@code .schem} files older builds exported ({@code src/test/resources/compat/<version>/compat.schem}, frozen)
 * read by this build exactly: a 12 x 4 x 12 copy of grass, an oak sign with text, a chest with 5 diamonds (slot 0) and
 * 3 oak logs (slot 13), a stone pedestal with a named armor stand wearing an iron helmet; the 0.4.0 file also carries
 * its biomes (cherry grove), which 0.3.0 didn't export. Read with the game's own block states.
 */
class CompatSchematicTest {
    private static FabricStateSpace states;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        states = FabricStateSpace.build();
    }

    private static byte[] sample(String version) throws IOException {
        try (InputStream in = CompatSchematicTest.class.getResourceAsStream("/compat/" + version + "/compat.schem")) {
            assertNotNull(in, "the " + version + " sample");
            return in.readAllBytes();
        }
    }

    private static String at(Clipboard clipboard, int x, int y, int z) {
        return states.format(clipboard.get(x, y, z));
    }

    private static NbtCompound nbt(byte[] bytes) throws IOException {
        return NbtIo.fromBytes(bytes, NbtLimits.DEFAULT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.3.0", "0.4.0"})
    void everyCellTileAndEntityReadsBackAsCopied(String version) throws IOException {
        Schematic schematic = SchematicCodec.read(new ByteArrayInputStream(sample(version)), states,
                SchematicCodec.Limits.DEFAULT, FabricDataFixHook.get());
        assertEquals(SchematicFormat.SPONGE, schematic.format());
        assertEquals(3, schematic.formatVersion());
        assertEquals(3955, schematic.dataVersion(), "Minecraft 1.21.1");
        assertTrue(schematic.report().isLossless(), "nothing skipped: " + schematic.report());
        assertEquals(2, schematic.report().blockEntities());
        assertEquals(1, schematic.report().entities());
        Clipboard clipboard = schematic.clipboard();
        assertEquals(new BlockPos(12, 4, 12), clipboard.size());

        int sign = 0, chest = 0, stone = 0, grass = 0, air = 0;
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 12; z++) {
                for (int x = 0; x < 12; x++) {
                    String state = at(clipboard, x, y, z);
                    if (y == 0) {
                        assertEquals("minecraft:grass_block[snowy=false]", state, x + " 0 " + z);
                        grass++;
                    } else if (x == 1 && y == 1 && z == 1) {
                        assertEquals("minecraft:oak_sign[rotation=4,waterlogged=false]", state);
                        sign++;
                    } else if (x == 4 && y == 1 && z == 4) {
                        assertEquals("minecraft:chest[facing=east,type=single,waterlogged=false]", state);
                        chest++;
                    } else if (x == 10 && y == 1 && z == 10) {
                        assertEquals("minecraft:stone", state);
                        stone++;
                    } else {
                        assertEquals("minecraft:air", state, x + " " + y + " " + z);
                        air++;
                    }
                }
            }
        }
        assertEquals(List.of(144, 1, 1, 1, 429), List.of(grass, sign, chest, stone, air));

        assertEquals(2, clipboard.tileCount());
        BlockEntityData signTile = clipboard.tile(1, 1, 1);
        assertEquals("minecraft:sign", signTile.typeId());
        assertTrue(nbt(signTile.nbtBytes()).getCompound("front_text").toString().contains("Sculptory " + version),
                "the sign's text: " + nbt(signTile.nbtBytes()));
        BlockEntityData chestTile = clipboard.tile(4, 1, 4);
        assertEquals("minecraft:chest", chestTile.typeId());
        List<NbtCompound> items = nbt(chestTile.nbtBytes()).getList("Items").compounds();
        assertEquals(2, items.size(), items.toString());
        assertEquals("minecraft:diamond", items.get(0).getString("id"));
        assertEquals(5, items.get(0).getInt("count"));
        assertEquals("0", items.get(0).get("Slot").toString().replaceAll("[^0-9]", ""));
        assertEquals("minecraft:oak_log", items.get(1).getString("id"));
        assertEquals(3, items.get(1).getInt("count"));
        assertEquals("13", items.get(1).get("Slot").toString().replaceAll("[^0-9]", ""));

        assertEquals(1, clipboard.entityCount());
        EntitySnapshot stand = clipboard.entities().get(0);
        assertEquals("minecraft:armor_stand", stand.typeId());
        assertEquals(10.5, stand.x(), 1e-9);
        assertEquals(2.0, stand.y(), 1e-9);
        assertEquals(10.5, stand.z(), 1e-9);
        assertEquals(30f, stand.yaw(), 1e-4f);
        NbtCompound data = nbt(stand.nbt());
        assertTrue(data.getString("CustomName").contains("Frozen sample"), data.getString("CustomName"));
        assertTrue(data.getList("ArmorItems").toString().contains("minecraft:iron_helmet"), data.toString());

        BiomeGrid biomes = clipboard.biomes();
        if (version.equals("0.3.0")) {
            assertNull(biomes, "0.3.0 exported no biomes");
        } else {
            assertNotNull(biomes, "0.4.0 exports the biomes");
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 12; z++) {
                    for (int x = 0; x < 12; x++) {
                        assertEquals("minecraft:cherry_grove", biomes.biomeAt(x, y, z), x + " " + y + " " + z);
                    }
                }
            }
        }
    }
}
