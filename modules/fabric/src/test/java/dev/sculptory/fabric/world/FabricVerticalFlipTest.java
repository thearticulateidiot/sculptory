package dev.sculptory.fabric.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.state.VerticalFlip;
import dev.sculptory.core.transform.Mirror;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The upside-down flip over every vanilla block state, under
 * fabric-loader-junit with bootstrapped registries: every flip is undone by flipping again, commutes with vanilla's own
 * turns and mirrors, and changes only the properties with an up or down meaning; a set of named categories is
 * checked state by state; and a per-block table of what the flip does ({@code flip/vanilla-blocks.txt}) pins every
 * vanilla block, so any change to the rules shows up block by block. Regenerate the table with the environment variable
 * {@code SCULPTORY_UPDATE_FLIP_TABLE=1} and review the diff.
 */
class FabricVerticalFlipTest {
    private static final String TABLE = "/flip/vanilla-blocks.txt";
    /** The properties the flip may change. */
    private static final Set<String> FLIP_PROPERTIES = Set.of("facing", "vertical_direction", "half", "type", "face",
            "attachment", "hanging", "up", "down", "orientation");
    private static FabricStateSpace space;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        space = FabricStateSpace.build();
    }

    private static int h(String spec) {
        int handle = space.parse(spec);
        assertTrue(handle >= 0, "unknown " + spec);
        return handle;
    }

    /** {@code from} flips to {@code to} (and back), both {@link VerticalFlip#FLIPS}. */
    private static void flips(String from, String to) {
        assertEquals(space.format(h(to)), space.format(space.flip(h(from))), from);
        assertEquals(space.format(h(from)), space.format(space.flip(h(to))), to);
        assertEquals(VerticalFlip.FLIPS, space.flipKind(h(from)), from);
        assertEquals(VerticalFlip.FLIPS, space.flipKind(h(to)), to);
    }

    /** {@code spec} has no upside-down form: it becomes {@code result} (itself, or a two-block plant's other half). */
    private static void kept(String spec, String result) {
        assertEquals(space.format(h(result)), space.format(space.flip(h(spec))), spec);
        assertEquals(VerticalFlip.KEPT, space.flipKind(h(spec)), spec);
    }

    private static void kept(String spec) {
        kept(spec, spec);
    }

    @Test
    void everyStateFlipsBackAndCommutesWithTurnsAndMirrors() {
        int kept = 0;
        for (int handle = 0; handle < space.size(); handle++) {
            int flipped = space.flip(handle);
            String text = space.format(handle);
            assertEquals(handle, space.flip(flipped), text + " flipped twice");
            assertEquals(space.flipKind(handle), space.flipKind(flipped), text + " and its flip are counted alike");
            if (space.flipKind(handle) == VerticalFlip.KEPT) kept++;
            for (int turns = 1; turns < 4; turns++) {
                assertEquals(space.rotate(flipped, turns), space.flip(space.rotate(handle, turns)), text + " turned " + turns);
            }
            for (Mirror mirror : List.of(Mirror.X, Mirror.Z)) {
                assertEquals(space.mirror(flipped, mirror), space.flip(space.mirror(handle, mirror)), text + " " + mirror);
            }
            // Only properties with an up or down meaning change; the block never does.
            BlockDescriptor before = space.describe(handle);
            BlockDescriptor after = space.describe(flipped);
            assertEquals(before.block(), after.block(), text);
            for (Map.Entry<String, String> entry : before.properties().entrySet()) {
                if (!FLIP_PROPERTIES.contains(entry.getKey())) {
                    assertEquals(entry.getValue(), after.get(entry.getKey()), text + " keeps " + entry.getKey());
                }
            }
        }
        assertEquals(space.verticalFlipTables().keptStates(), kept);
        assertEquals(0, space.verticalFlipTables().unknownStates(), "vanilla knows every vanilla property");
    }

    @Test
    void stairsSlabsTrapdoorsAndDoorsTurnOver() {
        flips("minecraft:oak_stairs[facing=east,half=bottom,shape=inner_left,waterlogged=false]",
                "minecraft:oak_stairs[facing=east,half=top,shape=inner_left,waterlogged=false]");
        flips("minecraft:stone_brick_slab[type=bottom,waterlogged=true]", "minecraft:stone_brick_slab[type=top,waterlogged=true]");
        flips("minecraft:stone_brick_slab[type=double]", "minecraft:stone_brick_slab[type=double]");
        flips("minecraft:spruce_trapdoor[facing=north,half=bottom,open=true]",
                "minecraft:spruce_trapdoor[facing=north,half=top,open=true]");
        // A door's halves change places, so the door stays whole; its hinge and facing stay.
        flips("minecraft:oak_door[facing=west,half=lower,hinge=right,open=true,powered=false]",
                "minecraft:oak_door[facing=west,half=upper,hinge=right,open=true,powered=false]");
        flips("minecraft:iron_door[half=upper]", "minecraft:iron_door[half=lower]");
    }

    @Test
    void blocksFacingUpOrDownTurnOver() {
        for (String block : List.of("piston", "sticky_piston", "observer", "dispenser", "dropper", "end_rod",
                "lightning_rod", "amethyst_cluster", "large_amethyst_bud", "medium_amethyst_bud", "small_amethyst_bud",
                "barrel", "command_block", "shulker_box", "red_shulker_box")) {
            flips("minecraft:" + block + "[facing=up]", "minecraft:" + block + "[facing=down]");
            flips("minecraft:" + block + "[facing=north]", "minecraft:" + block + "[facing=north]");
        }
        flips("minecraft:piston_head[facing=up,short=false,type=sticky]",
                "minecraft:piston_head[facing=down,short=false,type=sticky]");
        // A hopper cannot point up, and its funnel has no upside-down form: every hopper is kept.
        kept("minecraft:hopper[facing=down]");
        kept("minecraft:hopper[facing=east]");
        flips("minecraft:pointed_dripstone[thickness=tip,vertical_direction=down]",
                "minecraft:pointed_dripstone[thickness=tip,vertical_direction=up]");
        flips("minecraft:pointed_dripstone[thickness=base,vertical_direction=up]",
                "minecraft:pointed_dripstone[thickness=base,vertical_direction=down]");
        flips("minecraft:crafter[orientation=down_east]", "minecraft:crafter[orientation=up_east]");
        flips("minecraft:jigsaw[orientation=up_north]", "minecraft:jigsaw[orientation=down_north]");
        // A crafter facing north with its top up has no form with its top down.
        kept("minecraft:crafter[orientation=north_up]");
    }

    @Test
    void floorAndCeilingBlocksSwap() {
        flips("minecraft:stone_button[face=floor,facing=east]", "minecraft:stone_button[face=ceiling,facing=east]");
        flips("minecraft:stone_button[face=wall,facing=east]", "minecraft:stone_button[face=wall,facing=east]");
        flips("minecraft:lever[face=ceiling,facing=south,powered=true]", "minecraft:lever[face=floor,facing=south,powered=true]");
        flips("minecraft:grindstone[face=floor,facing=west]", "minecraft:grindstone[face=ceiling,facing=west]");
        flips("minecraft:bell[attachment=floor,facing=north]", "minecraft:bell[attachment=ceiling,facing=north]");
        // A wall bell hangs from its bar: not the same upside down, so it is kept and counted (a wall lever is centred).
        kept("minecraft:bell[attachment=single_wall,facing=north]");
        kept("minecraft:bell[attachment=double_wall,facing=east]");
        flips("minecraft:lever[face=wall,facing=north,powered=false]", "minecraft:lever[face=wall,facing=north,powered=false]");
        flips("minecraft:lantern[hanging=false]", "minecraft:lantern[hanging=true]");
        flips("minecraft:soul_lantern[hanging=true,waterlogged=true]", "minecraft:soul_lantern[hanging=false,waterlogged=true]");
        flips("minecraft:brown_mushroom_block[down=false,up=true]", "minecraft:brown_mushroom_block[down=true,up=false]");
        flips("minecraft:chorus_plant[down=true,up=false,north=true]", "minecraft:chorus_plant[down=false,up=true,north=true]");
        flips("minecraft:glow_lichen[down=true,north=true,up=false]", "minecraft:glow_lichen[down=false,north=true,up=true]");
        flips("minecraft:sculk_vein[down=false,up=true]", "minecraft:sculk_vein[down=true,up=false]");
    }

    @Test
    void symmetricBlocksStayAsTheyAre() {
        for (String spec : List.of("minecraft:stone", "minecraft:oak_log[axis=y]", "minecraft:oak_log[axis=x]",
                "minecraft:chain[axis=y]", "minecraft:chain[axis=z]", "minecraft:glass", "minecraft:oak_fence[north=true]",
                "minecraft:cobblestone_wall[up=true,north=low]", "minecraft:iron_bars[east=true]", "minecraft:water",
                "minecraft:oak_leaves", "minecraft:furnace[facing=north,lit=true]", "minecraft:ladder[facing=south]",
                "minecraft:snow[layers=8]", "minecraft:vine[north=true]")) {
            flips(spec, spec);
        }
    }

    @Test
    void blocksWithNoUpsideDownFormAreKept() {
        for (String spec : List.of("minecraft:torch", "minecraft:soul_torch", "minecraft:redstone_torch",
                "minecraft:wall_torch[facing=east]", "minecraft:redstone_wall_torch[facing=north]", "minecraft:poppy",
                "minecraft:oak_sapling", "minecraft:wheat[age=3]", "minecraft:red_bed[facing=north,part=head]",
                "minecraft:white_carpet", "minecraft:rail[shape=north_south]", "minecraft:stone_pressure_plate",
                "minecraft:redstone_wire", "minecraft:repeater[facing=east]", "minecraft:oak_sign[rotation=4]",
                "minecraft:white_banner[rotation=0]", "minecraft:skeleton_skull[rotation=8]",
                "minecraft:oak_hanging_sign[rotation=2]", "minecraft:chest[facing=north,type=single]", "minecraft:candle",
                "minecraft:snow[layers=1]", "minecraft:big_dripleaf[facing=north]", "minecraft:cave_vines",
                "minecraft:weeping_vines", "minecraft:kelp", "minecraft:seagrass", "minecraft:bubble_column[drag=true]",
                "minecraft:vine[up=true,north=true]", "minecraft:flower_pot", "minecraft:cake", "minecraft:lily_pad",
                "minecraft:mangrove_propagule[hanging=true]")) {
            kept(spec);
        }
        // Two-block plants stay upright and whole: their halves change places.
        kept("minecraft:sunflower[half=lower]", "minecraft:sunflower[half=upper]");
        kept("minecraft:tall_grass[half=upper]", "minecraft:tall_grass[half=lower]");
        kept("minecraft:small_dripleaf[half=lower,facing=east]", "minecraft:small_dripleaf[half=upper,facing=east]");
    }

    /**
     * What the flip does to each vanilla block, one line per block: its states, how many turn over, how many are the
     * same either way up, and how many are kept. Compared with the checked-in table.
     */
    @Test
    void everyVanillaBlockMatchesTheTable() throws IOException {
        Map<String, int[]> blocks = new TreeMap<>();
        for (int handle = 0; handle < space.size(); handle++) {
            String block = space.blockId(handle).value();
            int[] counts = blocks.computeIfAbsent(block, b -> new int[4]);
            counts[0]++;
            if (space.flipKind(handle) == VerticalFlip.KEPT) counts[3]++;
            else if (space.flip(handle) != handle) counts[1]++;
            else counts[2]++;
        }
        List<String> lines = new ArrayList<>();
        lines.add("# block states turned-over same-either-way kept (FabricVerticalFlipTest; Minecraft 1.21.1)");
        blocks.forEach((block, c) -> lines.add(block + " " + c[0] + " " + c[1] + " " + c[2] + " " + c[3]));
        String actual = String.join("\n", lines) + "\n";
        if ("1".equals(System.getenv("SCULPTORY_UPDATE_FLIP_TABLE"))) {
            // The test runs in modules/fabric.
            Path out = Path.of("src/test/resources/flip/vanilla-blocks.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, actual, StandardCharsets.UTF_8);
            return;
        }
        String expected;
        try (InputStream in = getClass().getResourceAsStream(TABLE)) {
            if (in == null) fail("missing " + TABLE + " (run with SCULPTORY_UPDATE_FLIP_TABLE=1)");
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        List<String> want = expected.lines().toList();
        List<String> got = actual.lines().toList();
        List<String> differences = new ArrayList<>();
        for (String line : got) if (!want.contains(line)) differences.add("+ " + line);
        for (String line : want) if (!got.contains(line)) differences.add("- " + line);
        assertEquals(List.of(), differences, "the flip changed for these blocks");
    }
}
