package dev.sculptory.core.tinker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.sculptory.core.testing.FakeStateSpace;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tinker's property order, value and property cycling, the remembered choice and names as shown. */
class TinkerPropertiesTest {
    private final FakeStateSpace states = new FakeStateSpace();

    @Test
    void usefulPropertiesComeFirstThenTheRestByName() {
        assertEquals(List.of("shape", "facing", "half", "waterlogged"),
                TinkerProperties.order(Set.of("waterlogged", "half", "facing", "shape")));
        assertEquals(List.of("facing", "north", "age", "zeta"), TinkerProperties.order(Set.of("zeta", "north", "age", "facing")));
        assertEquals(List.of(), TinkerProperties.order(Set.of()));
        assertEquals(List.of("shape", "facing", "half", "waterlogged"),
                TinkerProperties.of(states, states.state("minecraft:oak_stairs")));
        assertEquals(List.of("type", "waterlogged"), TinkerProperties.of(states, states.state("minecraft:oak_slab")));
        assertEquals(List.of(), TinkerProperties.of(states, states.state("minecraft:stone")));
    }

    @Test
    void scrollStepsThroughAPropertysValuesGoingRound() {
        int straight = states.state("minecraft:oak_stairs[facing=east,half=top,shape=straight]");
        int innerLeft = TinkerProperties.stepValue(states, straight, "shape", 1);
        assertEquals(states.state("minecraft:oak_stairs[facing=east,half=top,shape=inner_left]"), innerLeft,
                "the other properties stay");
        assertEquals(states.state("minecraft:oak_stairs[facing=east,half=top,shape=outer_right]"),
                TinkerProperties.stepValue(states, straight, "shape", -1), "back from the first goes round");
        assertEquals(straight, TinkerProperties.stepValue(states, straight, "shape", 5), "five shapes: once round");
        assertEquals(states.state("minecraft:oak_stairs[facing=south,half=top]"),
                TinkerProperties.stepValue(states, straight, "facing", 1));
        assertEquals(straight, TinkerProperties.stepValue(states, straight, "age", 1), "no such property");
        int widget = states.state("testmod:widget[facing=down]");
        assertEquals(states.state("testmod:widget[facing=north]"), TinkerProperties.stepValue(states, widget, "facing", 1),
                "a modded property's values from its state definition");
        assertEquals(List.of("north", "east", "south", "west", "up", "down"), states.propertyValues(widget, "facing"));
        assertEquals("b", TinkerProperties.step(List.of("a", "b", "c"), "a", 1));
        assertEquals("c", TinkerProperties.step(List.of("a", "b", "c"), "a", -1));
        assertEquals("x", TinkerProperties.step(List.of("a", "b"), "x", 1), "not a value: unchanged");
        assertEquals("a", TinkerProperties.step(List.of("a"), "a", 1));
    }

    @Test
    void shiftScrollPicksTheNextPropertyAndTheChoiceIsKeptPerBlock() {
        int stairs = states.state("minecraft:oak_stairs");
        assertEquals("shape", TinkerProperties.chosen(states, stairs, null), "the first useful one");
        assertEquals("half", TinkerProperties.chosen(states, stairs, "half"), "the one remembered for the block");
        assertEquals("shape", TinkerProperties.chosen(states, stairs, "axis"), "remembered but not a property of it");
        assertNull(TinkerProperties.chosen(states, states.state("minecraft:stone"), null));
        assertEquals("facing", TinkerProperties.stepProperty(states, stairs, "shape", 1));
        assertEquals("waterlogged", TinkerProperties.stepProperty(states, stairs, "shape", -1));
        assertEquals("shape", TinkerProperties.stepProperty(states, stairs, "waterlogged", 1));
        assertEquals("shape", TinkerProperties.stepProperty(states, stairs, null, 1));
        assertNull(TinkerProperties.stepProperty(states, states.state("minecraft:stone"), null, 1));
    }

    @Test
    void namesAreShownWithSpaces() {
        assertEquals("outer left", TinkerProperties.shown("outer_left"));
        assertEquals("shape: outer left", TinkerProperties.shown("shape", "outer_left"));
        assertEquals("waterlogged: true", TinkerProperties.shown("waterlogged", "true"));
    }

    @Test
    void theStateSpaceDefaultsGoThroughDescribeAndResolve() {
        int slab = states.state("minecraft:oak_slab[type=bottom]");
        assertEquals(states.state("minecraft:oak_slab[type=top]"), states.withProperty(slab, "type", "top"));
        assertEquals(slab, states.withProperty(slab, "type", "bottom"));
        assertEquals(-1, states.withProperty(slab, "type", "sideways"), "no such value");
        assertEquals(-1, states.withProperty(slab, "shape", "straight"), "no such property");
        assertEquals(List.of("bottom", "top", "double"), states.propertyValues(slab, "type"));
        assertEquals(List.of(), states.propertyValues(slab, "shape"));
    }
}
