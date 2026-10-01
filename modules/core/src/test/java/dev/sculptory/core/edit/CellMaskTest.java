package dev.sculptory.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.testing.FakeStateSpace;
import java.util.List;
import org.junit.jupiter.api.Test;

class CellMaskTest {
    private final FakeStateSpace states = new FakeStateSpace();

    private int count(CellMask mask) {
        CellPredicate p = mask.bind(states);
        int n = 0;
        for (int h = 0; h < states.size(); h++) {
            if (p.test(0, 0, 0, h)) n++;
        }
        return n;
    }

    @Test
    void anyAcceptsEverything() {
        assertEquals(states.size(), count(CellMask.ANY));
    }

    @Test
    void statesMatchExactHandles() {
        int stone = states.state("minecraft:stone");
        int north = states.state("minecraft:oak_stairs[facing=north]");
        CellPredicate p = new CellMask.States(new int[] {stone, north}).bind(states);
        assertTrue(p.test(1, 2, 3, stone));
        assertTrue(p.test(1, 2, 3, north));
        assertFalse(p.test(1, 2, 3, states.state("minecraft:oak_stairs[facing=east]")));
        assertFalse(p.test(1, 2, 3, -1));
        assertEquals(2, count(new CellMask.States(new int[] {stone, north})));
        assertThrows(IndexOutOfBoundsException.class, () -> new CellMask.States(new int[] {states.size()}).bind(states));
    }

    @Test
    void blocksExpandToEveryStateOfTheBlock() {
        CellMask stairs = new CellMask.Blocks(List.of(new NamespacedId("minecraft:oak_stairs")));
        assertEquals(4 * 2 * 5 * 2, count(stairs));
        CellPredicate p = stairs.bind(states);
        assertTrue(p.test(0, 0, 0, states.state("minecraft:oak_stairs[facing=west,half=top,waterlogged=true]")));
        assertFalse(p.test(0, 0, 0, states.state("minecraft:stone")));
        CellMask two = new CellMask.Blocks(List.of(new NamespacedId("minecraft:stone"), new NamespacedId("testmod:widget")));
        assertEquals(1 + 6, count(two));
        assertEquals(0, count(new CellMask.Blocks(List.of(new NamespacedId("other:missing")))));
    }

    @Test
    void tagsExpandThroughTheStateSpace() {
        assertEquals(3, count(new CellMask.Tag(new NamespacedId("minecraft:logs"))));
        CellPredicate dirt = new CellMask.Tag(new NamespacedId("minecraft:dirt")).bind(states);
        assertTrue(dirt.test(0, 0, 0, states.state("minecraft:dirt")));
        assertTrue(dirt.test(0, 0, 0, states.state("minecraft:grass_block[snowy=true]")));
        assertFalse(dirt.test(0, 0, 0, states.state("minecraft:sand")));
    }

    @Test
    void combinators() {
        CellMask logs = new CellMask.Tag(new NamespacedId("minecraft:logs"));
        CellMask stone = new CellMask.Blocks(List.of(new NamespacedId("minecraft:stone")));
        assertEquals(4, count(new CellMask.Or(List.of(logs, stone))));
        assertEquals(0, count(new CellMask.And(List.of(logs, stone))));
        assertEquals(3, count(new CellMask.And(List.of(logs, CellMask.ANY))));
        assertEquals(states.size() - 3, count(new CellMask.Not(logs)));
        assertEquals(3, count(new CellMask.Not(new CellMask.Not(logs))));
    }

    @Test
    void treeLimits() {
        CellMask mask = CellMask.ANY;
        for (int depth = 2; depth <= CellMask.MAX_DEPTH; depth++) mask = new CellMask.Not(mask);
        assertEquals(CellMask.MAX_DEPTH, mask.depth());
        CellMask deepest = mask;
        assertThrows(IllegalArgumentException.class, () -> new CellMask.Not(deepest));
        assertThrows(IllegalArgumentException.class, () -> new CellMask.And(List.of()));
        CellMask wide = new CellMask.Or(java.util.Collections.nCopies(CellMask.MAX_NODES - 1, CellMask.ANY));
        assertEquals(CellMask.MAX_NODES, wide.nodeCount());
        assertThrows(IllegalArgumentException.class, () -> new CellMask.Or(java.util.Collections.nCopies(CellMask.MAX_NODES, CellMask.ANY)));
        assertThrows(IllegalArgumentException.class, () -> new CellMask.States(new int[CellMask.MAX_LIST + 1]));
    }

    @Test
    void statesArrayIsCopied() {
        int[] handles = {1, 2};
        CellMask.States mask = new CellMask.States(handles);
        handles[0] = 5;
        mask.handles()[1] = 5;
        assertEquals(new CellMask.States(new int[] {1, 2}), mask);
    }
}
