package dev.sculptory.core.mask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.SplitMix64;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every global mask rule, Not, invert and the binding's edge cases. */
class MaskRulesTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final int AIR = STATES.air();
    private static final int STONE = STATES.state("minecraft:stone");
    private static final int DIRT = STATES.state("minecraft:dirt");
    private static final int GRASS = STATES.state("minecraft:grass_block");
    private static final int LOG = STATES.state("minecraft:oak_log");
    private static final int WATER = STATES.state("minecraft:water");
    private static final int PLANKS = STATES.state("minecraft:oak_planks");

    private static BlockSet set(String text) {
        return BlockSet.parse(text);
    }

    private static BoundMask bind(MaskRule... rules) {
        List<MaskEntry> entries = new ArrayList<>();
        for (MaskRule rule : rules) entries.add(MaskEntry.of(rule));
        return new EditMask(entries, false).bind(STATES);
    }

    private static boolean test(BoundMask mask, FakeWorld world, int x, int y, int z) {
        return mask.test(x, y, z, world.get(x, y, z), world);
    }

    @Test
    void theOffMaskAcceptsEverythingAndReadsNothing() {
        assertSame(BoundMask.ALL, EditMask.NONE.bind(STATES));
        BoundMask none = new EditMask(List.of(), true).bind(STATES);
        assertFalse(none.acceptsAll());
        FakeWorld world = new FakeWorld(STATES);
        assertFalse(test(none, world, 0, 0, 0), "an empty mask inverted accepts nothing");
    }

    @Test
    void isMatchesBlocksTagsAndExactStates() {
        FakeWorld world = new FakeWorld(STATES);
        world.set(0, 0, 0, STONE);
        world.set(1, 0, 0, LOG);
        world.set(2, 0, 0, DIRT);
        world.set(3, 0, 0, GRASS);
        BoundMask mask = bind(new MaskRule.Is(set("minecraft:stone;#minecraft:logs")));
        assertTrue(test(mask, world, 0, 0, 0));
        assertTrue(test(mask, world, 1, 0, 0), "a block of the tag");
        assertFalse(test(mask, world, 2, 0, 0));
        BoundMask dirtTag = bind(new MaskRule.Is(set("#minecraft:dirt")));
        assertTrue(test(dirtTag, world, 2, 0, 0));
        assertTrue(test(dirtTag, world, 3, 0, 0), "grass is in the dirt tag");
        int eastStairs = STATES.state("minecraft:oak_stairs[facing=east]");
        int northStairs = STATES.state("minecraft:oak_stairs[facing=north]");
        world.set(4, 0, 0, eastStairs);
        world.set(5, 0, 0, northStairs);
        BoundMask exact = bind(new MaskRule.Is(BlockSet.of(new BlockSet.State(STATES.describe(eastStairs)))));
        assertTrue(test(exact, world, 4, 0, 0));
        assertFalse(test(exact, world, 5, 0, 0), "another state of the block");
        BoundMask unknown = bind(new MaskRule.Is(BlockSet.of(new BlockSet.State(
                BlockDescriptor.parse("minecraft:oak_stairs[facing=sideways]")))));
        for (int x = 0; x < 6; x++) assertFalse(test(unknown, world, x, 0, 0), "an unknown state matches nothing");
    }

    @Test
    void onTopOfUnderAndNextToLookAtTheirNeighbours() {
        FakeWorld world = new FakeWorld(STATES);
        world.set(0, 10, 0, STONE);
        BoundMask onTop = bind(new MaskRule.OnTopOf(set("minecraft:stone")));
        assertTrue(test(onTop, world, 0, 11, 0));
        assertFalse(test(onTop, world, 0, 12, 0));
        assertFalse(test(onTop, world, 0, 9, 0));
        BoundMask under = bind(new MaskRule.Under(set("minecraft:stone")));
        assertTrue(test(under, world, 0, 9, 0));
        assertFalse(test(under, world, 0, 11, 0));
        BoundMask next = bind(new MaskRule.NextTo(set("minecraft:stone")));
        for (int[] d : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
            assertTrue(test(next, world, d[0], 10 + d[1], d[2]), "next to, from " + d[0] + "," + d[1] + "," + d[2]);
        }
        assertFalse(test(next, world, 1, 11, 0), "a diagonal is not next to");
        assertFalse(test(next, world, 0, 10, 0), "the cell itself is not its own neighbour");
    }

    @Test
    void neighbourRulesReadAcrossSectionAndChunkEdges() {
        FakeWorld world = new FakeWorld(STATES);
        // Chunk edge in x (15 | 16), in z (-1 | 0) and a section edge in y (15 | 16).
        world.set(16, 20, 5, STONE);
        world.set(3, 20, -1, STONE);
        world.set(3, 16, 7, STONE);
        BoundMask next = bind(new MaskRule.NextTo(set("minecraft:stone")));
        assertTrue(test(next, world, 15, 20, 5));
        assertTrue(test(next, world, 3, 20, 0));
        BoundMask onTop = bind(new MaskRule.OnTopOf(set("minecraft:stone")));
        assertTrue(test(onTop, world, 3, 17, 7));
        BoundMask under = bind(new MaskRule.Under(set("minecraft:stone")));
        assertTrue(test(under, world, 3, 15, 7));
        // A neighbour in a chunk that is not loaded matches nothing, air included.
        world.setLoaded(1, 0, false);
        assertFalse(test(next, world, 15, 20, 5));
        BoundMask touches = bind(new MaskRule.Exposed());
        world.fill(Box.of(new BlockPos(14, 19, 4), new BlockPos(15, 21, 6)), DIRT);
        world.set(16, 20, 5, AIR);
        world.set(15, 20, 5, DIRT);
        assertFalse(test(touches, world, 15, 20, 5), "the only air neighbour is in an unloaded chunk");
        world.setLoaded(1, 0, true);
        assertTrue(test(touches, world, 15, 20, 5), "once loaded, it touches air");
    }

    @Test
    void touchesAirNotAirAndSolidReadTheFlags() {
        FakeWorld world = new FakeWorld(STATES);
        world.fill(Box.of(new BlockPos(0, 0, 0), new BlockPos(2, 2, 2)), STONE);
        BoundMask touches = bind(new MaskRule.Exposed());
        assertFalse(test(touches, world, 1, 1, 1), "buried");
        assertTrue(test(touches, world, 1, 2, 1), "air above");
        world.set(0, 3, 0, WATER);
        world.set(0, 2, 1, WATER);
        world.set(1, 2, 0, WATER);
        world.set(0, 2, -1, WATER);
        world.set(-1, 2, 0, WATER);
        assertFalse(test(touches, world, 0, 2, 0), "water is not air");
        BoundMask notAir = bind(new MaskRule.NotAir());
        assertTrue(test(notAir, world, 0, 0, 0));
        assertTrue(test(notAir, world, 0, 3, 0), "water is not air");
        assertFalse(test(notAir, world, 5, 5, 5));
        BoundMask solid = bind(new MaskRule.Solid());
        assertTrue(test(solid, world, 0, 0, 0));
        assertFalse(test(solid, world, 0, 3, 0));
        world.set(9, 0, 0, STATES.state("minecraft:oak_stairs[facing=east]"));
        assertFalse(test(solid, world, 9, 0, 0), "stairs are not solid ground");
    }

    @Test
    void heightInsideAndChanceReadTheCellAlone() {
        FakeWorld world = new FakeWorld(STATES);
        BoundMask height = bind(new MaskRule.Height(10, 20));
        assertFalse(test(height, world, 0, 9, 0));
        assertTrue(test(height, world, 0, 10, 0));
        assertTrue(test(height, world, 0, 20, 0));
        assertFalse(test(height, world, 0, 21, 0));
        assertEquals(0, height.reach());

        Region sphere = new Region.Shape(Box.of(new BlockPos(0, 0, 0), new BlockPos(8, 8, 8)), ShapeKind.ELLIPSOID,
                Facing.UP);
        BoundMask inside = bind(new MaskRule.Inside(sphere));
        assertTrue(test(inside, world, 4, 4, 4));
        assertFalse(test(inside, world, 0, 0, 0), "a corner of the box is outside the sphere");
        assertThrows(IllegalArgumentException.class, () -> bind(new MaskRule.Inside(new Region.Uploaded(
                Sha256.digest(new byte[] {1}), Box.of(new BlockPos(0, 0, 0)), 1))), "an uploaded region is resolved first");

        BoundMask chance = bind(new MaskRule.Chance(30, 99L));
        int hits = 0;
        for (int x = 0; x < 100; x++) {
            for (int z = 0; z < 100; z++) {
                boolean hit = test(chance, world, x, 5, z);
                assertEquals(Long.remainderUnsigned(SplitMix64.hash(99L, x, 5, z), 100) < 30, hit);
                if (hit) hits++;
            }
        }
        assertTrue(hits > 2700 && hits < 3300, "about 30%: " + hits);
        assertEquals(test(chance, world, 7, 5, 9), test(bind(new MaskRule.Chance(30, 99L)), world, 7, 5, 9),
                "the same cells for the same seed");
    }

    @Test
    void slopeMeasuresTheSteepestCardinalStepOfTheCellsColumn() {
        FakeWorld world = new FakeWorld(STATES);
        // Ground at y 10 everywhere around, a pillar to y 13 at (1, 0).
        world.fill(Box.of(new BlockPos(-3, 0, -3), new BlockPos(3, 10, 3)), STONE);
        world.fill(Box.of(new BlockPos(1, 11, 0), new BlockPos(1, 13, 0)), STONE);
        BoundMask gentle = bind(new MaskRule.Slope(0, 1));
        BoundMask steep = bind(new MaskRule.Slope(3, 16));
        assertTrue(test(gentle, world, -2, 11, -2), "flat ground");
        assertFalse(test(gentle, world, 0, 11, 0), "next to the pillar: a 3-block step");
        assertTrue(test(steep, world, 0, 11, 0));
        assertTrue(test(steep, world, 0, 5, 0), "any cell of the column within reach of its surface");
        assertFalse(test(steep, world, 0, -20, 0), "no surface within 16 blocks: the rule fails");
        assertEquals(BoundMask.REACH_LIMIT, steep.reach());
    }

    @Test
    void notFlipsOneRuleAndInvertAllFlipsTheWhole() {
        FakeWorld world = new FakeWorld(STATES);
        world.set(0, 0, 0, STONE);
        world.set(1, 0, 0, PLANKS);
        EditMask notStone = new EditMask(List.of(new MaskEntry(new MaskRule.Is(set("minecraft:stone")), true),
                MaskEntry.of(new MaskRule.NotAir())), false);
        BoundMask bound = notStone.bind(STATES);
        assertFalse(test(bound, world, 0, 0, 0));
        assertTrue(test(bound, world, 1, 0, 0));
        assertFalse(test(bound, world, 2, 0, 0), "air fails Not air");
        BoundMask inverted = new EditMask(notStone.entries(), true).bind(STATES);
        for (int x = 0; x < 3; x++) assertEquals(!test(bound, world, x, 0, 0), test(inverted, world, x, 0, 0));
        assertEquals(0, bound.reach(), "cell-only rules reach 0");
    }

    @Test
    void blockSetsAndMasksKeepTheirLimits() {
        List<BlockSet.Entry> seventeen = new ArrayList<>();
        for (int i = 0; i < 17; i++) seventeen.add(new BlockSet.Block(new NamespacedId("minecraft:b" + i)));
        assertThrows(IllegalArgumentException.class, () -> new BlockSet(seventeen));
        assertThrows(IllegalArgumentException.class, () -> new MaskRule.Slope(0, 17));
        assertThrows(IllegalArgumentException.class, () -> new MaskRule.Height(5, 4));
    }
}
