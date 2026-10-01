package dev.sculptory.core.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.sculptory.core.region.Facing;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Vanilla's stair shape rule, case by case. */
class StairShapesTest {
    private final Map<String, Facing> stairs = new HashMap<>();
    private final StairShapes.Stairs lookup = (x, y, z) -> stairs.get(x + "," + y + "," + z);

    private void stair(int x, int z, Facing facing) {
        stairs.put(x + ",0," + z, facing);
    }

    @Test
    void aLoneStairOrARowIsStraight() {
        stair(0, 0, Facing.NORTH);
        assertEquals(StairShapes.Shape.STRAIGHT, StairShapes.shape(lookup, 0, 0, 0, Facing.NORTH));
        stair(1, 0, Facing.NORTH);
        stair(-1, 0, Facing.NORTH);
        assertEquals(StairShapes.Shape.STRAIGHT, StairShapes.shape(lookup, 0, 0, 0, Facing.NORTH));
        // Stairs before and behind on the same axis do not turn it either.
        stair(0, -1, Facing.SOUTH);
        stair(0, 1, Facing.NORTH);
        assertEquals(StairShapes.Shape.STRAIGHT, StairShapes.shape(lookup, 0, 0, 0, Facing.NORTH));
    }

    @Test
    void aStairAcrossTheFrontMakesAnOuterCorner() {
        // Facing north; in front (north, z -1) a stair facing west: west is north's counterclockwise turn.
        stair(0, 0, Facing.NORTH);
        stair(0, -1, Facing.WEST);
        assertEquals(StairShapes.Shape.OUTER_LEFT, StairShapes.shape(lookup, 0, 0, 0, Facing.NORTH));
        stair(0, -1, Facing.EAST);
        assertEquals(StairShapes.Shape.OUTER_RIGHT, StairShapes.shape(lookup, 0, 0, 0, Facing.NORTH));
        // Unless the cell the front stair rises from holds a stair like this one (a straight run beside a turn).
        stair(-1, 0, Facing.NORTH);
        assertEquals(StairShapes.Shape.STRAIGHT, StairShapes.shape(lookup, 0, 0, 0, Facing.NORTH),
                "the east-facing front stair's low side holds a north stair");
    }

    @Test
    void aStairAcrossTheBackMakesAnInnerCorner() {
        stair(0, 0, Facing.NORTH);
        stair(0, 1, Facing.WEST);
        assertEquals(StairShapes.Shape.INNER_LEFT, StairShapes.shape(lookup, 0, 0, 0, Facing.NORTH));
        stair(0, 1, Facing.EAST);
        assertEquals(StairShapes.Shape.INNER_RIGHT, StairShapes.shape(lookup, 0, 0, 0, Facing.NORTH));
        stair(1, 0, Facing.NORTH);
        assertEquals(StairShapes.Shape.STRAIGHT, StairShapes.shape(lookup, 0, 0, 0, Facing.NORTH),
                "the east-facing back stair's high side holds a north stair");
        // The front decides first.
        stairs.remove("1,0,0");
        stair(0, -1, Facing.WEST);
        assertEquals(StairShapes.Shape.OUTER_LEFT, StairShapes.shape(lookup, 0, 0, 0, Facing.NORTH));
    }

    @Test
    void turnsAndOffsetsFollowVanilla() {
        assertEquals(Facing.WEST, StairShapes.counterclockwise(Facing.NORTH));
        assertEquals(Facing.SOUTH, StairShapes.counterclockwise(Facing.WEST));
        assertEquals(Facing.EAST, StairShapes.counterclockwise(Facing.SOUTH));
        assertEquals(Facing.NORTH, StairShapes.counterclockwise(Facing.EAST));
        assertEquals(1, StairShapes.dx(Facing.EAST));
        assertEquals(-1, StairShapes.dz(Facing.NORTH));
        assertEquals(0, StairShapes.dx(Facing.SOUTH));
        assertThrows(IllegalArgumentException.class, () -> StairShapes.counterclockwise(Facing.UP));
        assertThrows(IllegalArgumentException.class, () -> StairShapes.shape(lookup, 0, 0, 0, Facing.DOWN));
        assertEquals("outer_left", StairShapes.Shape.OUTER_LEFT.property());
    }
}
