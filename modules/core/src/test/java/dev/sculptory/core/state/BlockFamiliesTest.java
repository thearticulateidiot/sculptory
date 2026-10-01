package dev.sculptory.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.transform.Mirror;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Better Replace's "Whole family" names, against every block of
 * Minecraft 1.21.1 (a list read from the game's jar) plus a few modded ones.
 */
class BlockFamiliesTest {
    private static final NamesStateSpace VANILLA = new NamesStateSpace(vanilla());

    private static List<String> vanilla() {
        List<String> ids = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                BlockFamiliesTest.class.getResourceAsStream("vanilla-1.21.1-blocks.txt"), StandardCharsets.UTF_8))) {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                if (!line.isBlank() && !line.startsWith("#")) ids.add("minecraft:" + line.trim());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return ids;
    }

    private static NamespacedId id(String value) {
        return new NamespacedId(value.contains(":") ? value : "minecraft:" + value);
    }

    private static Optional<String> token(String block) {
        return BlockFamilies.familyToken(id(block));
    }

    /** The swaps as a map of paths ("oak_stairs" -> "spruce_stairs"; modded ones keep their namespace). */
    private static Map<String, String> swaps(String from, String to, StateSpace states) {
        Map<String, String> swaps = new TreeMap<>();
        for (Pattern.BlockSwap swap : BlockFamilies.swaps(id(from), id(to), states)) {
            swaps.put(shortId(swap.from()), shortId(swap.to()));
        }
        return swaps;
    }

    private static String shortId(NamespacedId id) {
        return id.value().startsWith("minecraft:") ? id.value().substring("minecraft:".length()) : id.value();
    }

    @Test
    void theFamilyIsTheLongestKnownWordRun() {
        assertEquals(Optional.of("oak"), token("oak_stairs"));
        assertEquals(Optional.of("oak"), token("oak_planks"));
        assertEquals(Optional.of("dark_oak"), token("dark_oak_planks"));
        assertEquals(Optional.of("dark_oak"), token("stripped_dark_oak_wood"));
        assertEquals(Optional.of("oak"), token("stripped_oak_log"));
        assertEquals(Optional.of("oak"), token("potted_oak_sapling"));
        assertEquals(Optional.of("bamboo"), token("bamboo_mosaic_stairs"));
        assertEquals(Optional.of("crimson"), token("crimson_stem"));
        assertEquals(Optional.of("warped"), token("warped_hyphae"));
        assertEquals(Optional.of("red_sandstone"), token("red_sandstone_stairs"), "not the colour red");
        assertEquals(Optional.of("cut_red_sandstone"), token("cut_red_sandstone_slab"));
        assertEquals(Optional.of("red_nether_brick"), token("red_nether_bricks"));
        assertEquals(Optional.of("stone_brick"), token("stone_bricks"), "bricks count as brick");
        assertEquals(Optional.of("stone_brick"), token("stone_brick_stairs"));
        assertEquals(Optional.of("stone_brick"), token("cracked_stone_bricks"));
        assertEquals(Optional.of("mossy_stone_brick"), token("mossy_stone_brick_wall"));
        assertEquals(Optional.of("stone"), token("stone"));
        assertEquals(Optional.of("smooth_stone"), token("smooth_stone_slab"));
        assertEquals(Optional.of("deepslate_tile"), token("deepslate_tiles"));
        assertEquals(Optional.of("polished_blackstone_brick"), token("cracked_polished_blackstone_bricks"));
        assertEquals(Optional.of("waxed_exposed_cut_copper"), token("waxed_exposed_cut_copper_stairs"));
        assertEquals(Optional.of("light_gray"), token("light_gray_wool"), "not gray");
        assertEquals(Optional.of("brick"), token("bricks"));
        assertEquals(Optional.empty(), token("dirt"));
        assertEquals(Optional.empty(), token("glowstone"));
        assertEquals(Optional.empty(), token("redstone_wire"));
        // Modded: known words anywhere, and any planks name a (modded) wood.
        assertEquals(Optional.of("oak"), token("mymod:oak_table"));
        assertEquals(Optional.of("fir"), token("mymod:fir_planks"));
        assertEquals(Optional.empty(), token("mymod:fir_stairs"), "a modded word alone names no family");
        assertEquals(Optional.empty(), token("mymod:gizmo"));
    }

    @Test
    void oakToSpruceSwapsEveryOakBlockAndNoDarkOakOne() {
        Map<String, String> swaps = swaps("oak_planks", "spruce_planks", VANILLA);
        Map<String, String> expected = new LinkedHashMap<>();
        for (String suffix : List.of("button", "door", "fence", "fence_gate", "hanging_sign", "leaves", "log", "planks",
                "pressure_plate", "sapling", "sign", "slab", "stairs", "trapdoor", "wall_hanging_sign", "wall_sign",
                "wood")) {
            expected.put("oak_" + suffix, "spruce_" + suffix);
        }
        expected.put("potted_oak_sapling", "potted_spruce_sapling");
        expected.put("stripped_oak_log", "stripped_spruce_log");
        expected.put("stripped_oak_wood", "stripped_spruce_wood");
        assertEquals(new TreeMap<>(expected), swaps);
        for (String from : swaps.keySet()) assertFalse(from.contains("dark_oak"), from);
        assertFalse(swaps.containsKey("petrified_oak_slab"), "no petrified_spruce_slab");
        // The other way round, and from any member of the family.
        assertEquals(swaps("spruce_stairs", "oak_log", VANILLA).get("spruce_log"), "oak_log");
        Map<String, String> darkToOak = swaps("dark_oak_planks", "oak_planks", VANILLA);
        assertEquals("oak_stairs", darkToOak.get("dark_oak_stairs"));
        for (String from : darkToOak.keySet()) assertTrue(from.contains("dark_oak"), from);
    }

    @Test
    void netherWoodsAndBambooUseTheirOwnWordsForLogsAndWood() {
        Map<String, String> crimson = swaps("oak_planks", "crimson_planks", VANILLA);
        assertEquals("crimson_stem", crimson.get("oak_log"));
        assertEquals("stripped_crimson_stem", crimson.get("stripped_oak_log"));
        assertEquals("crimson_hyphae", crimson.get("oak_wood"));
        assertEquals("stripped_crimson_hyphae", crimson.get("stripped_oak_wood"));
        assertEquals("crimson_stairs", crimson.get("oak_stairs"));
        assertFalse(crimson.containsKey("oak_leaves"), "crimson has no leaves");
        assertFalse(crimson.containsKey("oak_sapling"), "nor saplings");
        assertEquals("oak_log", swaps("warped_planks", "oak_planks", VANILLA).get("warped_stem"));
        assertEquals("crimson_nylium", swaps("warped_planks", "crimson_planks", VANILLA).get("warped_nylium"));
        assertEquals("crimson_fungus", swaps("warped_stem", "crimson_stem", VANILLA).get("warped_fungus"));

        Map<String, String> bamboo = swaps("oak_planks", "bamboo_planks", VANILLA);
        assertEquals("bamboo_block", bamboo.get("oak_log"));
        assertEquals("stripped_bamboo_block", bamboo.get("stripped_oak_log"));
        assertEquals("bamboo_planks", bamboo.get("oak_planks"));
        assertFalse(bamboo.containsKey("oak_wood"), "bamboo has no wood");
        Map<String, String> fromBamboo = swaps("bamboo_planks", "oak_planks", VANILLA);
        assertEquals("oak_log", fromBamboo.get("bamboo_block"));
        assertEquals("oak_sapling", fromBamboo.get("bamboo_sapling"));
        assertFalse(fromBamboo.containsKey("bamboo"), "the bamboo plant is no base block: never oak planks");
        assertFalse(fromBamboo.containsKey("bamboo_mosaic"), "no oak mosaic");
    }

    @Test
    void stoneFamiliesKeepTheirBricksPluralAndBaseBlocks() {
        Map<String, String> bricks = swaps("stone_bricks", "deepslate_bricks", VANILLA);
        assertEquals("deepslate_bricks", bricks.get("stone_bricks"));
        assertEquals("deepslate_brick_stairs", bricks.get("stone_brick_stairs"));
        assertEquals("deepslate_brick_slab", bricks.get("stone_brick_slab"));
        assertEquals("deepslate_brick_wall", bricks.get("stone_brick_wall"));
        assertEquals("cracked_deepslate_bricks", bricks.get("cracked_stone_bricks"));
        assertFalse(bricks.containsKey("stone"), "plain stone is another family");
        assertFalse(bricks.containsKey("mossy_stone_bricks"), "mossy stone bricks are another family");

        Map<String, String> stone = swaps("stone", "granite", VANILLA);
        assertEquals("granite", stone.get("stone"));
        assertEquals("granite_stairs", stone.get("stone_stairs"));
        assertEquals("granite_slab", stone.get("stone_slab"));
        assertFalse(stone.containsKey("stone_bricks"));

        Map<String, String> mud = swaps("bricks", "mud_bricks", VANILLA);
        assertEquals("mud_bricks", mud.get("bricks"));
        assertEquals("mud_brick_stairs", mud.get("brick_stairs"));
        assertEquals("mud_brick_wall", mud.get("brick_wall"));
        assertEquals("stone_bricks", swaps("granite", "stone_bricks", VANILLA).get("granite"), "singular to plural");

        // Wood to stone: what exists swaps, the base block goes to the base block.
        Map<String, String> woodToStone = swaps("oak_planks", "stone", VANILLA);
        assertEquals("stone", woodToStone.get("oak_planks"));
        assertEquals("stone_stairs", woodToStone.get("oak_stairs"));
        assertEquals("stone_slab", woodToStone.get("oak_slab"));
        assertEquals("stone_button", woodToStone.get("oak_button"));
        assertFalse(woodToStone.containsKey("oak_fence"), "no stone fence");
        assertEquals("oak_planks", swaps("stone", "oak_planks", VANILLA).get("stone"));
        assertEquals("purpur_block", swaps("quartz_block", "purpur_block", VANILLA).get("quartz_block"));
        assertEquals("quartz_stairs", swaps("purpur_block", "quartz_block", VANILLA).get("purpur_stairs"));
    }

    @Test
    void coloursAndCopperSwapTheirWholeSets() {
        Map<String, String> colours = swaps("white_wool", "red_wool", VANILLA);
        for (String suffix : List.of("wool", "carpet", "concrete", "concrete_powder", "stained_glass",
                "stained_glass_pane", "terracotta", "glazed_terracotta", "bed", "banner", "shulker_box", "candle")) {
            assertEquals("red_" + suffix, colours.get("white_" + suffix), suffix);
        }
        assertFalse(colours.containsKey("light_gray_wool"));
        assertFalse(colours.containsKey("white_tulip"), "a colour's flowers are not dyed blocks");
        assertFalse(swaps("gray_wool", "red_wool", VANILLA).containsKey("light_gray_wool"), "light gray is its own");

        Map<String, String> copper = swaps("copper_block", "exposed_copper", VANILLA);
        assertEquals("exposed_copper", copper.get("copper_block"), "base block to base block");
        assertEquals("exposed_copper_door", copper.get("copper_door"));
        assertEquals("exposed_copper_bulb", copper.get("copper_bulb"));
        assertFalse(copper.containsKey("cut_copper"), "cut copper is its own family");
    }

    @Test
    void moddedBlocksSwapWithinTheirNamespaceOrRefuse() {
        List<String> ids = new ArrayList<>(vanilla());
        ids.addAll(List.of("mymod:oak_table", "mymod:spruce_table", "mymod:oak_chair", "mymod:fir_planks",
                "mymod:fir_stairs", "mymod:fir_log", "mymod:fir_table", "mymod:gizmo"));
        NamesStateSpace states = new NamesStateSpace(ids);
        Map<String, String> oak = swaps("oak_planks", "spruce_planks", states);
        assertEquals("mymod:spruce_table", oak.get("mymod:oak_table"));
        assertFalse(oak.containsKey("mymod:oak_chair"), "no spruce chair anywhere");
        // A modded wood named by its planks: its own blocks and vanilla's counterparts.
        Map<String, String> fir = swaps("mymod:fir_planks", "oak_planks", states);
        assertEquals("oak_planks", fir.get("mymod:fir_planks"), "no mymod:oak_planks: vanilla's");
        assertEquals("oak_stairs", fir.get("mymod:fir_stairs"));
        assertEquals("oak_log", fir.get("mymod:fir_log"));
        assertEquals("mymod:oak_table", fir.get("mymod:fir_table"));
        Map<String, String> toFir = swaps("oak_planks", "mymod:fir_planks", states);
        assertEquals("mymod:fir_table", toFir.get("mymod:oak_table"), "own namespace first");
        assertEquals("mymod:fir_stairs", toFir.get("oak_stairs"), "then the other family's");
        // Refusals: no family, or the same one.
        assertTrue(swaps("mymod:gizmo", "oak_planks", states).isEmpty());
        assertTrue(swaps("mymod:fir_stairs", "oak_planks", states).isEmpty(), "fir alone is no family");
        assertTrue(swaps("oak_planks", "dirt", states).isEmpty());
        assertTrue(swaps("oak_planks", "oak_stairs", states).isEmpty(), "the same family");
    }

    @Test
    void everyVanillaSwapStaysInItsFamilyAndMapsEachBlockOnce() {
        Set<String> woods = Set.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry",
                "bamboo", "crimson", "warped");
        for (String from : woods) {
            for (String to : woods) {
                if (from.equals(to)) continue;
                List<Pattern.BlockSwap> swaps = BlockFamilies.swaps(id(from + "_planks"), id(to + "_planks"), VANILLA);
                assertFalse(swaps.isEmpty(), from + " to " + to);
                Set<NamespacedId> seen = new java.util.HashSet<>();
                for (Pattern.BlockSwap swap : swaps) {
                    assertTrue(seen.add(swap.from()), "swapped twice: " + swap.from());
                    assertEquals(Optional.of(from), BlockFamilies.familyToken(swap.from()), swap.toString());
                    assertEquals(Optional.of(to), BlockFamilies.familyToken(swap.to()), swap.toString());
                }
                // Always usable as a remap.
                new Pattern.Remap(swaps, true);
            }
        }
    }

    /** One state per block, full blocks flagged {@link StateFlags#TERRAIN_SOLID} by their name, handle 0 air. */
    static final class NamesStateSpace implements StateSpace {
        private static final Set<String> NOT_FULL = Set.of("stairs", "slab", "fence", "gate", "door", "trapdoor",
                "button", "plate", "sign", "wall", "sapling", "leaves", "pane", "carpet", "bed", "banner", "candle",
                "torch", "potted", "fungus", "roots", "chair", "table", "air");
        private final List<NamespacedId> ids = new ArrayList<>();
        private final Map<NamespacedId, Integer> handles = new HashMap<>();
        private final int[] flags;

        NamesStateSpace(List<String> blocks) {
            List<String> all = new ArrayList<>();
            all.add("minecraft:air");
            for (String block : blocks) if (!block.equals("minecraft:air")) all.add(block);
            flags = new int[all.size()];
            for (String block : all) {
                NamespacedId id = new NamespacedId(block);
                handles.put(id, ids.size());
                String path = block.substring(block.indexOf(':') + 1);
                boolean full = !path.equals("bamboo");
                for (String word : path.split("_")) full &= !NOT_FULL.contains(word);
                flags[ids.size()] = path.equals("air") ? StateFlags.AIR : path.contains("tulip") ? StateFlags.VEGETATION
                        : full ? StateFlags.TERRAIN_SOLID : 0;
                ids.add(id);
            }
        }

        @Override
        public int size() {
            return ids.size();
        }

        @Override
        public int air() {
            return 0;
        }

        @Override
        public int flags(int h) {
            return flags[h];
        }

        @Override
        public String format(int h) {
            return ids.get(h).value();
        }

        @Override
        public int parse(String spec) {
            try {
                return resolve(BlockDescriptor.parse(spec));
            } catch (IllegalArgumentException e) {
                return -1;
            }
        }

        @Override
        public BlockDescriptor describe(int h) {
            return BlockDescriptor.of(ids.get(h));
        }

        @Override
        public int resolve(BlockDescriptor d) {
            return d.properties().isEmpty() ? handles.getOrDefault(d.block(), -1) : -1;
        }

        @Override
        public NamespacedId blockId(int h) {
            return ids.get(h);
        }

        @Override
        public boolean inTag(int h, NamespacedId tag) {
            return false;
        }

        @Override
        public int rotate(int h, int clockwiseQuarterTurns) {
            return h;
        }

        @Override
        public int mirror(int h, Mirror m) {
            return h;
        }

        @Override
        public int withWaterlogged(int h, boolean on) {
            return h;
        }

        @Override
        public int fluidSource(int h) {
            return -1;
        }
    }
}
