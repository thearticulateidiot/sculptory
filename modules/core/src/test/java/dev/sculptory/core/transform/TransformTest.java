package dev.sculptory.core.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.testing.FakeStateSpace;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TransformTest {
    private static final List<BlockPos> SIZES = List.of(
            new BlockPos(3, 2, 5), new BlockPos(1, 1, 1), new BlockPos(4, 3, 4), new BlockPos(7, 1, 2));

    /**
     * Every record value, including the Z mirrors that duplicate X mirrors plus a half turn, each also flipped upside
     * down.
     */
    private static List<Transform> everyRecord() {
        List<Transform> all = new ArrayList<>();
        for (boolean flip : new boolean[] {false, true}) {
            for (Mirror mirror : Mirror.values()) {
                for (int turns = 0; turns < 4; turns++) all.add(new Transform(turns, mirror, flip));
            }
        }
        return all;
    }

    private static List<BlockPos> cells(BlockPos size) {
        List<BlockPos> cells = new ArrayList<>();
        for (int x = 0; x < size.x(); x++) {
            for (int y = 0; y < size.y(); y++) {
                for (int z = 0; z < size.z(); z++) cells.add(new BlockPos(x, y, z));
            }
        }
        return cells;
    }

    @Test
    void allHasEightDistinctMappings() {
        List<Transform> all = Transform.all();
        assertEquals(8, all.size());
        BlockPos size = new BlockPos(3, 1, 5);
        Set<List<BlockPos>> mappings = new HashSet<>();
        for (Transform t : all) mappings.add(cells(size).stream().map(c -> t.apply(c, size)).toList());
        assertEquals(8, mappings.size());
    }

    @Test
    void everyTransformRoundTripsThroughItsInverse() {
        for (Transform t : everyRecord()) {
            for (BlockPos size : SIZES) {
                BlockPos transformedSize = t.size(size.x(), size.y(), size.z());
                Set<BlockPos> seen = new HashSet<>();
                for (BlockPos cell : cells(size)) {
                    BlockPos mapped = t.apply(cell, size);
                    assertTrue(mapped.x() >= 0 && mapped.x() < transformedSize.x(), t + " x in range");
                    assertTrue(mapped.z() >= 0 && mapped.z() < transformedSize.z(), t + " z in range");
                    assertEquals(t.upsideDown() ? size.y() - 1 - cell.y() : cell.y(), mapped.y());
                    assertTrue(seen.add(mapped), t + " is a bijection");
                    assertEquals(cell, t.inverse().apply(mapped, transformedSize), t + " inverse");
                }
                assertEquals(size, t.inverse().size(transformedSize.x(), transformedSize.y(), transformedSize.z()));
            }
        }
    }

    @Test
    void sizesSwapOnOddTurns() {
        for (Transform t : everyRecord()) {
            BlockPos size = t.size(3, 2, 5);
            if ((t.quarterTurnsCw() & 1) == 1) {
                assertEquals(new BlockPos(5, 2, 3), size, t.toString());
            } else {
                assertEquals(new BlockPos(3, 2, 5), size, t.toString());
            }
        }
    }

    @Test
    void composeMatchesSequentialApplication() {
        for (Transform a : everyRecord()) {
            for (Transform b : everyRecord()) {
                Transform ab = a.compose(b);
                for (BlockPos size : SIZES) {
                    BlockPos middle = a.size(size.x(), size.y(), size.z());
                    for (BlockPos cell : cells(size)) {
                        assertEquals(b.apply(a.apply(cell, size), middle), ab.apply(cell, size), a + " then " + b);
                    }
                }
            }
            assertEquals(Transform.IDENTITY, canonical(a.compose(a.inverse())), a + " with inverse");
        }
    }

    /** (k, Z) behaves exactly like (k + 2, X). */
    private static Transform canonical(Transform t) {
        return t.mirror() == Mirror.Z ? new Transform(t.quarterTurnsCw() + 2, Mirror.X, t.upsideDown()) : t;
    }

    @Test
    void clockwiseTurnMovesNorthWestToNorthEast() {
        Transform turn = Transform.rotation(1);
        // Box 3 wide (x) by 2 deep (z): north-west corner (0, 0) ends at the north-east corner of the 2x3 result.
        assertEquals(new BlockPos(1, 0, 0), turn.apply(0, 0, 0, 3, 1, 2));
        assertEquals(new BlockPos(0, 0, 2), turn.apply(2, 0, 1, 3, 1, 2));
        assertEquals(new BlockPos(2, 0, 0), new Transform(0, Mirror.X).apply(0, 0, 0, 3, 1, 2));
        assertEquals(new BlockPos(0, 0, 1), new Transform(0, Mirror.Z).apply(0, 0, 0, 3, 1, 2));
    }

    @Test
    void turnsAreNormalizedAndBoundsChecked() {
        assertEquals(3, new Transform(-1, Mirror.NONE).quarterTurnsCw());
        assertEquals(1, new Transform(5, Mirror.NONE).quarterTurnsCw());
        assertTrue(new Transform(4, Mirror.NONE).isIdentity());
        assertNotEquals(Transform.IDENTITY, new Transform(0, Mirror.X));
        assertThrows(IllegalArgumentException.class, () -> Transform.IDENTITY.apply(3, 0, 0, 3, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> Transform.IDENTITY.size(0, 1, 1));
    }

    @Test
    void applyToStateMirrorsThenRotates() {
        FakeStateSpace states = new FakeStateSpace();
        int east = states.state("minecraft:oak_stairs[facing=east]");
        // Mirror X turns east into west; a clockwise turn then gives north.
        assertEquals(states.state("minecraft:oak_stairs[facing=north]"), new Transform(1, Mirror.X).applyToState(states, east));
        assertEquals(states.state("minecraft:oak_stairs[facing=south]"), Transform.rotation(1).applyToState(states, east));
        assertEquals(east, Transform.IDENTITY.applyToState(states, east));
    }

    @Test
    void allWithFlipsHasSixteenDistinctMappings() {
        List<Transform> all = Transform.allWithFlips();
        assertEquals(16, all.size());
        BlockPos size = new BlockPos(3, 2, 5);
        Set<List<BlockPos>> mappings = new HashSet<>();
        for (Transform t : all) mappings.add(cells(size).stream().map(c -> t.apply(c, size)).toList());
        assertEquals(16, mappings.size());
        assertEquals(Transform.all(), all.subList(0, 8));
    }

    @Test
    void theFlipTurnsHeightsOverAndCommutesWithTheRest() {
        BlockPos size = new BlockPos(3, 4, 2);
        assertEquals(new BlockPos(0, 3, 0), Transform.UPSIDE_DOWN.apply(0, 0, 0, 3, 4, 2));
        assertEquals(new BlockPos(2, 0, 1), Transform.UPSIDE_DOWN.apply(2, 3, 1, 3, 4, 2));
        assertTrue(Transform.UPSIDE_DOWN.compose(Transform.UPSIDE_DOWN).isIdentity());
        assertEquals(Transform.UPSIDE_DOWN, Transform.UPSIDE_DOWN.inverse());
        assertTrue(!Transform.UPSIDE_DOWN.isIdentity() && !Transform.UPSIDE_DOWN.isHorizontal());
        for (Transform t : Transform.all()) {
            assertEquals(t.withUpsideDown(true), t.compose(Transform.UPSIDE_DOWN), t + " then the flip");
            assertEquals(t.withUpsideDown(true), Transform.UPSIDE_DOWN.compose(t), "the flip then " + t);
            assertEquals(t, t.withUpsideDown(true).horizontal());
            assertEquals(3L - 1 - 7, t.withUpsideDown(true).mapY(7L, 3), "long heights far outside the box");
        }
        assertEquals(5, Transform.UPSIDE_DOWN.size(3, 5, 2).y());
        assertEquals(size, Transform.UPSIDE_DOWN.size(3, 4, 2));
    }

    @Test
    void applyToStateFlipsLast() {
        FakeStateSpace states = new FakeStateSpace();
        int east = states.state("minecraft:oak_stairs[facing=east,half=bottom]");
        assertEquals(states.state("minecraft:oak_stairs[facing=north,half=top]"),
                new Transform(1, Mirror.X, true).applyToState(states, east));
        assertEquals(states.state("minecraft:oak_stairs[facing=east,half=top]"),
                Transform.UPSIDE_DOWN.applyToState(states, east));
        for (Transform t : Transform.allWithFlips()) {
            for (int h = 0; h < states.size(); h++) {
                assertEquals(h, t.inverse().applyToState(states, t.applyToState(states, h)),
                        t + " undone on " + states.format(h));
            }
        }
    }
}
