package dev.sculptory.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BlockDescriptorTest {
    @Test
    void formatSortsPropertiesAndOmitsEmptyBrackets() {
        BlockDescriptor stairs = BlockDescriptor.of(new NamespacedId("minecraft:oak_stairs"),
                Map.of("waterlogged", "false", "facing", "north", "shape", "straight", "half", "bottom"));
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]", stairs.format());
        assertEquals("minecraft:stone", BlockDescriptor.of(new NamespacedId("minecraft:stone")).format());
        assertEquals(List.of("facing", "half", "shape", "waterlogged"), List.copyOf(stairs.properties().keySet()));
    }

    @Test
    void parseRoundTrips() {
        for (String spec : List.of(
                "minecraft:air",
                "minecraft:water[level=15]",
                "minecraft:oak_stairs[facing=north,half=bottom,shape=inner_left,waterlogged=true]",
                "chipped:oak_planks_12",
                "farmersdelight:rice_bag[facing=up]",
                "testmod:widget[facing=down]",
                "a.b-c:path/sub_dir.x[k=v]")) {
            BlockDescriptor parsed = BlockDescriptor.parse(spec);
            assertEquals(spec, parsed.format());
            assertEquals(parsed, BlockDescriptor.parse(parsed.format()));
        }
    }

    @Test
    void parseCanonicalizesPropertyOrder() {
        BlockDescriptor parsed = BlockDescriptor.parse("minecraft:chest[waterlogged=false,facing=east]");
        assertEquals("minecraft:chest[facing=east,waterlogged=false]", parsed.format());
        assertEquals("east", parsed.get("facing"));
    }

    @Test
    void parseRejectsMalformedText() {
        for (String bad : List.of(
                "",
                "stone",
                "Minecraft:stone",
                "minecraft:stone[]",
                "minecraft:stone[",
                "minecraft:stone[a=b",
                "minecraft:stone[a]",
                "minecraft:stone[a=b=c]",
                "minecraft:stone[a=b,a=c]",
                "minecraft:stone[a=b,]",
                "minecraft:stone[a= b]",
                "minecraft:stone[a=b]x",
                "minecraft:stone [a=b]",
                "minecraft:stone[a=\"b\"]",
                "minecraft:stone[a={b}]")) {
            assertThrows(IllegalArgumentException.class, () -> BlockDescriptor.parse(bad), bad);
        }
    }

    @Test
    void limitsAreEnforced() {
        Map<String, String> many = new HashMap<>();
        for (int i = 0; i <= BlockDescriptor.MAX_PROPERTIES; i++) many.put("p" + i, "v");
        NamespacedId id = new NamespacedId("minecraft:stone");
        assertThrows(IllegalArgumentException.class, () -> BlockDescriptor.of(id, many));
        String longValue = "v".repeat(BlockDescriptor.MAX_COMPONENT_BYTES + 1);
        assertThrows(IllegalArgumentException.class, () -> BlockDescriptor.of(id, Map.of("k", longValue)));
        assertThrows(IllegalArgumentException.class, () -> BlockDescriptor.of(id, Map.of("k", "a,b")));
        assertThrows(IllegalArgumentException.class, () -> BlockDescriptor.parse("minecraft:stone[" + "a".repeat(9000) + "=b]"));
    }

    @Test
    void propertiesAreImmutable() {
        BlockDescriptor d = BlockDescriptor.parse("minecraft:oak_log[axis=y]");
        assertThrows(UnsupportedOperationException.class, () -> d.properties().put("axis", "x"));
        assertEquals("minecraft:oak_log[axis=x]", d.with("axis", "x").format());
        assertEquals("minecraft:oak_log[axis=y]", d.format());
    }

    @Test
    void sha256RoundTrips() {
        Sha256 empty = Sha256.digest(new byte[0]);
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", empty.hex());
        assertArrayEquals(empty.bytes(), Sha256.ofBytes(empty.bytes()).bytes());
        assertThrows(IllegalArgumentException.class, () -> new Sha256("E3B0"));
        assertThrows(IllegalArgumentException.class, () -> Sha256.ofBytes(new byte[31]));
    }
}
