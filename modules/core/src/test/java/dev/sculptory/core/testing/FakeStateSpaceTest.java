package dev.sculptory.core.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.transform.Mirror;
import java.util.List;
import org.junit.jupiter.api.Test;

class FakeStateSpaceTest {
    private final FakeStateSpace states = new FakeStateSpace();

    private boolean has(String spec, int flag) {
        return StateFlags.has(states.flags(states.state(spec)), flag);
    }

    @Test
    void layoutIsDeterministic() {
        // air, stone, dirt, grass 2, water 16, stairs 80, log 3, slab 6, chest 8, sand, short grass, widget 6, poppy,
        // tall grass 2, pink petals 16, seagrass, sea pickle 8, vertical slab 2, kelp 26, kelp plant, tall seagrass 2, coral fan 2,
        // lily pad, sugar cane 16, lava 16, bubble column, oak planks, stone bricks, stone brick stairs 80, stone brick
        // slab 6, cobblestone, andesite, torch, lantern 2, hopper 5, oak door 8, gizmo 12
        assertEquals(1 + 1 + 1 + 2 + 16 + 80 + 3 + 6 + 8 + 1 + 1 + 6 + 1 + 2 + 16 + 1 + 8 + 2 + 26 + 1 + 2 + 2 + 1 + 16 + 16 + 1
                + 1 + 1 + 80 + 6 + 1 + 1 + 1 + 2 + 5 + 8 + 12, states.size());
        assertEquals(0, states.air());
        assertEquals("minecraft:air", states.format(0));
        assertEquals(states.format(17), new FakeStateSpace().format(17));
        assertEquals(37, states.blockIds().size());
    }

    @Test
    void formatAndParseRoundTripEveryState() {
        for (int h = 0; h < states.size(); h++) {
            String spec = states.format(h);
            assertEquals(h, states.parse(spec), spec);
            BlockDescriptor d = states.describe(h);
            assertEquals(h, states.resolve(d));
            assertEquals(d.block(), states.blockId(h));
        }
    }

    @Test
    void parseFillsDefaultsAndRejectsUnknowns() {
        assertEquals("minecraft:oak_log[axis=y]", states.format(states.parse("minecraft:oak_log")));
        assertEquals("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]",
                states.format(states.parse("minecraft:oak_stairs[facing=east]")));
        assertEquals(-1, states.parse("minecraft:diamond_block"));
        assertEquals(-1, states.parse("minecraft:oak_log[axis=w]"));
        assertEquals(-1, states.parse("minecraft:oak_log[color=red]"));
        assertEquals(-1, states.parse("not a state"));
        assertThrows(IllegalArgumentException.class, () -> states.state("minecraft:diamond_block"));
        assertThrows(IndexOutOfBoundsException.class, () -> states.flags(states.size()));
    }

    @Test
    void flags() {
        assertTrue(has("minecraft:air", StateFlags.AIR));
        assertTrue(has("minecraft:stone", StateFlags.TERRAIN_SOLID));
        assertTrue(has("minecraft:water[level=3]", StateFlags.FLUID_BLOCK));
        assertTrue(has("minecraft:chest", StateFlags.HAS_BLOCK_ENTITY));
        assertTrue(has("minecraft:chest", StateFlags.WATERLOGGABLE));
        assertFalse(has("minecraft:chest", StateFlags.WATERLOGGED));
        assertTrue(has("minecraft:oak_stairs[waterlogged=true]", StateFlags.WATERLOGGED));
        assertFalse(has("minecraft:oak_log", StateFlags.WATERLOGGABLE));
        assertTrue(has("minecraft:sand", StateFlags.FALLING));
        assertTrue(has("minecraft:short_grass", StateFlags.VEGETATION));
        assertTrue(has("minecraft:short_grass", StateFlags.REPLACEABLE));
        assertTrue(has("minecraft:oak_slab[type=double]", StateFlags.TERRAIN_SOLID));
        assertFalse(has("minecraft:oak_slab[type=top]", StateFlags.TERRAIN_SOLID));
        assertEquals(0, states.flags(states.state("testmod:widget")));
    }

    @Test
    void rotateTurnsFacingAndAxis() {
        assertEquals(states.state("minecraft:oak_stairs[facing=east,half=top]"),
                states.rotate(states.state("minecraft:oak_stairs[facing=north,half=top]"), 1));
        assertEquals(states.state("minecraft:chest[facing=west]"), states.rotate(states.state("minecraft:chest[facing=north]"), -1));
        assertEquals(states.state("testmod:widget[facing=south]"), states.rotate(states.state("testmod:widget[facing=north]"), 2));
        assertEquals(states.state("testmod:widget[facing=up]"), states.rotate(states.state("testmod:widget[facing=up]"), 1));
        assertEquals(states.state("minecraft:oak_log[axis=z]"), states.rotate(states.state("minecraft:oak_log[axis=x]"), 1));
        assertEquals(states.state("minecraft:oak_log[axis=x]"), states.rotate(states.state("minecraft:oak_log[axis=x]"), 2));
        assertEquals(states.state("minecraft:oak_log[axis=y]"), states.rotate(states.state("minecraft:oak_log[axis=y]"), 1));
        for (int h = 0; h < states.size(); h++) {
            assertEquals(h, states.rotate(states.rotate(h, 1), 3));
            assertEquals(h, states.rotate(h, 4));
        }
    }

    @Test
    void mirrorSwapsSidesAndHandedness() {
        assertEquals(states.state("minecraft:chest[facing=west]"), states.mirror(states.state("minecraft:chest[facing=east]"), Mirror.X));
        assertEquals(states.state("minecraft:chest[facing=north]"), states.mirror(states.state("minecraft:chest[facing=north]"), Mirror.X));
        assertEquals(states.state("minecraft:chest[facing=south]"), states.mirror(states.state("minecraft:chest[facing=north]"), Mirror.Z));
        assertEquals(states.state("minecraft:oak_stairs[facing=north,shape=outer_right]"),
                states.mirror(states.state("minecraft:oak_stairs[facing=north,shape=outer_left]"), Mirror.X));
        assertEquals(states.state("minecraft:oak_stairs[facing=south,shape=inner_left]"),
                states.mirror(states.state("minecraft:oak_stairs[facing=north,shape=inner_right]"), Mirror.Z));
        assertEquals(states.state("minecraft:oak_log[axis=x]"), states.mirror(states.state("minecraft:oak_log[axis=x]"), Mirror.Z));
        for (int h = 0; h < states.size(); h++) {
            for (Mirror m : Mirror.values()) assertEquals(h, states.mirror(states.mirror(h, m), m));
            assertEquals(h, states.mirror(h, Mirror.NONE));
        }
    }

    @Test
    void waterloggingAndFluidSource() {
        int dry = states.state("minecraft:oak_slab[type=top]");
        int wet = states.withWaterlogged(dry, true);
        assertEquals(states.state("minecraft:oak_slab[type=top,waterlogged=true]"), wet);
        assertEquals(dry, states.withWaterlogged(wet, false));
        int stone = states.state("minecraft:stone");
        assertEquals(stone, states.withWaterlogged(stone, true));
        int source = states.state("minecraft:water[level=0]");
        assertEquals(source, states.fluidSource(wet));
        assertEquals(source, states.fluidSource(states.state("minecraft:water[level=7]")));
        assertEquals(-1, states.fluidSource(dry));
        assertEquals(-1, states.fluidSource(stone));
    }

    @Test
    void tags() {
        NamespacedId logs = new NamespacedId("minecraft:logs");
        NamespacedId dirt = new NamespacedId("minecraft:dirt");
        assertTrue(states.inTag(states.state("minecraft:oak_log[axis=z]"), logs));
        assertFalse(states.inTag(states.state("minecraft:stone"), logs));
        for (String spec : List.of("minecraft:dirt", "minecraft:grass_block", "minecraft:grass_block[snowy=true]")) {
            assertTrue(states.inTag(states.state(spec), dirt), spec);
        }
        assertFalse(states.inTag(states.state("minecraft:sand"), dirt));
    }
}
