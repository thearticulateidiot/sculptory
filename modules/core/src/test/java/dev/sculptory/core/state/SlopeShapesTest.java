package dev.sculptory.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.transform.CellResampler;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Transform;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Stairs and slabs on turned slopes ({@link SlopeShapes}) and the families they come from ({@link StairSlabVariants}).
 */
class SlopeShapesTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();

    // ---------------------------------------------------------------- variants

    @Test
    void fullBlocksFindTheirStairsAndSlabsByVanillaNames() {
        BlockFamiliesTest.NamesStateSpace vanilla = new BlockFamiliesTest.NamesStateSpace(vanilla());
        StairSlabVariants v = new StairSlabVariants(vanilla);
        check(v, vanilla, "oak_planks", "oak_stairs", "oak_slab");
        check(v, vanilla, "stone_bricks", "stone_brick_stairs", "stone_brick_slab");
        check(v, vanilla, "bricks", "brick_stairs", "brick_slab");
        check(v, vanilla, "quartz_block", "quartz_stairs", "quartz_slab");
        check(v, vanilla, "purpur_block", "purpur_stairs", "purpur_slab");
        check(v, vanilla, "stone", "stone_stairs", "stone_slab");
        check(v, vanilla, "cobblestone", "cobblestone_stairs", "cobblestone_slab");
        check(v, vanilla, "deepslate_tiles", "deepslate_tile_stairs", "deepslate_tile_slab");
        check(v, vanilla, "cut_copper", "cut_copper_stairs", "cut_copper_slab");
        check(v, vanilla, "waxed_oxidized_cut_copper", "waxed_oxidized_cut_copper_stairs",
                "waxed_oxidized_cut_copper_slab");
        check(v, vanilla, "mud_bricks", "mud_brick_stairs", "mud_brick_slab");
        check(v, vanilla, "smooth_stone", null, "smooth_stone_slab");
        check(v, vanilla, "cut_sandstone", null, "cut_sandstone_slab");
        check(v, vanilla, "dirt", null, null);
        check(v, vanilla, "glass", null, null);
        // Stairs and slabs themselves (not full) have none.
        check(v, vanilla, "oak_stairs", null, null);
        // Every vanilla full block that finds a stair finds one of its own family.
        int found = 0;
        for (int h = 1; h < vanilla.size(); h++) {
            int stairs = v.stairs(h);
            if (stairs < 0) continue;
            found++;
            String block = vanilla.blockId(h).value(), stair = vanilla.blockId(stairs).value();
            String family = stair.substring(0, stair.length() - "_stairs".length());
            assertTrue(block.startsWith(family), block + " -> " + stair);
        }
        assertTrue(found >= 40, "found " + found);
    }

    private static void check(StairSlabVariants v, StateSpace states, String block, String stairs, String slab) {
        int h = states.parse("minecraft:" + block);
        assertTrue(h > 0, block);
        assertEquals(stairs == null ? -1 : states.parse("minecraft:" + stairs), v.stairs(h), block + " stairs");
        assertEquals(slab == null ? -1 : states.parse("minecraft:" + slab), v.slab(h), block + " slab");
    }

    private static List<String> vanilla() {
        List<String> ids = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                SlopeShapesTest.class.getResourceAsStream("vanilla-1.21.1-blocks.txt"), StandardCharsets.UTF_8))) {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                if (!line.isBlank() && !line.startsWith("#")) ids.add("minecraft:" + line.trim());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return ids;
    }

    @Test
    void aStairOrSlabStateHasItsFacingHalfAndShape() {
        StairSlabVariants v = new StairSlabVariants(STATES);
        int planks = STATES.state("minecraft:oak_planks");
        assertEquals(STATES.state("minecraft:oak_stairs[facing=west,half=top,shape=outer_left,waterlogged=false]"),
                v.stairs(planks, "west", "top", "outer_left"));
        assertEquals(STATES.state("minecraft:oak_slab[type=top,waterlogged=false]"), v.slab(planks, "top"));
        assertEquals(-1, v.stairs(STATES.state("minecraft:dirt"), "west", "top", "straight"));
    }

    // ---------------------------------------------------------------- choosing a shape

    /** Eighths from a pattern: bit {@code (x << 2) | (y << 1) | z} full (1) or empty (0), others {@code rest}. */
    private static double[] eighths(int full, double rest) {
        double[] e = new double[8];
        for (int i = 0; i < 8; i++) e[i] = (full >> i & 1) == 1 ? 1 : rest;
        return e;
    }

    /** The four lower eighths (y bit 0). */
    private static int lower() {
        int bits = 0;
        for (int i = 0; i < 8; i++) if ((i >> 1 & 1) == 0) bits |= 1 << i;
        return bits;
    }

    /** The eighths on the north side (z bit 0) of the upper half. */
    private static int upperNorth() {
        int bits = 0;
        for (int i = 0; i < 8; i++) if ((i >> 1 & 1) == 1 && (i & 1) == 0) bits |= 1 << i;
        return bits;
    }

    @Test
    void aHalfFilledTopIsASlabAndAOneSidedTopIsAStairFacingThatSide() {
        int lower = lower();
        assertEquals(0, SlopeShapes.choose(eighths(lower, 0), true, true), "bottom slab");
        assertEquals(0, SlopeShapes.choose(eighths(lower, 0.2), true, true), "mostly empty top: still a slab");
        assertEquals(SlopeShapes.KEEP, SlopeShapes.choose(eighths(lower, 0.8), true, true), "mostly full: the block");
        int north = SlopeShapes.choose(eighths(lower | upperNorth(), 0), true, true);
        assertEquals(2 + SlopeShapes.stairCode(0, 0, 0), north, "a straight bottom stair facing north");
        // Without stairs in the family, the slab or the block.
        assertEquals(0, SlopeShapes.choose(eighths(lower, 0), false, true));
        int stairOnly = SlopeShapes.choose(eighths(lower | upperNorth(), 0), false, true);
        assertTrue(stairOnly == SlopeShapes.KEEP || stairOnly == 0);
        // One upper eighth (north-west): an outer corner; three (all but south-east): an inner corner.
        assertEquals(2 + SlopeShapes.stairCode(0, 0, 3), SlopeShapes.choose(eighths(lower | 1 << 2, 0), true, true));
        assertEquals(2 + SlopeShapes.stairCode(0, 0, 1),
                SlopeShapes.choose(eighths(lower | 1 << 2 | 1 << 3 | 1 << 6, 0), true, true));
        // Upside down: the lower half full on the south side only is a top stair facing south; empty, a top slab.
        int upper = ~lower & 0xFF;
        int southLow = 0;
        for (int i = 0; i < 8; i++) if ((i >> 1 & 1) == 0 && (i & 1) == 1) southLow |= 1 << i;
        assertEquals(2 + SlopeShapes.stairCode(2, 1, 0), SlopeShapes.choose(eighths(upper | southLow, 0), true, true));
        assertEquals(1, SlopeShapes.choose(eighths(upper, 0), true, true), "a top slab");
    }

    @Test
    void wallsAndInsidesKeepTheirBlock() {
        // The east half full top to bottom: a wall seen from the side, no slope.
        int east = 0;
        for (int i = 0; i < 8; i++) if ((i >> 2 & 1) == 1) east |= 1 << i;
        assertEquals(SlopeShapes.KEEP, SlopeShapes.choose(eighths(east, 0), true, true));
        assertEquals(SlopeShapes.KEEP, SlopeShapes.choose(eighths(0xFF, 0), true, true));
        assertEquals(SlopeShapes.KEEP, SlopeShapes.choose(eighths(0, 0), true, true));
    }

    // ---------------------------------------------------------------- a turned roof

    @Test
    void aPitchedPlateGetsStairsFacingUpTheSlopeAndKeepsItsCount() {
        int planks = STATES.state("minecraft:oak_planks");
        TwoCellPairsTest.Cells cells = new TwoCellPairsTest.Cells(12, 3, 12, planks);
        SlopeShapes slopes = new SlopeShapes(new StairSlabVariants(STATES));
        // Pitch turns up towards the south: south goes down, so the plate climbs northwards.
        Orientation o = Orientation.free(Transform.IDENTITY, 0, 2_000, 0);
        for (CellResampler r : List.of(CellResampler.smooth(o, 12, 3, 12), CellResampler.nearest(o, 12, 3, 12),
                CellResampler.exact(o, 12, 3, 12))) {
            String what = r.getClass().getSimpleName();
            OrientedStates oriented = new OrientedStates(STATES, o);
            BlockPos s = r.size();
            int[] cell = new int[3];
            int written = 0, stairs = 0, slabs = 0, kept = 0;
            double[] back = new double[3];
            for (int x = 0; x < s.x(); x++) {
                for (int y = 0; y < s.y(); y++) {
                    for (int z = 0; z < s.z(); z++) {
                        if (!r.source(x, y, z, cells, cell)) continue;
                        written++;
                        int state = oriented.map(cells.state(cell[0], cell[1], cell[2]));
                        int shaped = slopes.shape(r, cells, x, y, z, state);
                        if (shaped == SlopeShapes.KEEP) {
                            kept++;
                            continue;
                        }
                        NamespacedId block = STATES.blockId(shaped);
                        var d = STATES.describe(shaped);
                        if (block.value().equals("minecraft:oak_slab")) {
                            slabs++;
                            continue;
                        }
                        assertEquals("minecraft:oak_stairs", block.value(), what);
                        stairs++;
                        // On the top a stair's tall side is up the slope (north); underneath, down it (south). (The
                        // plate's tilted north and south ends have stairs of their own, so only its middle is checked.)
                        r.back(x + 0.5, y + 0.5, z + 0.5, back);
                        if (d.get("shape").equals("straight") && back[2] >= 2 && back[2] <= 10) {
                            assertEquals(d.get("half").equals("bottom") ? "north" : "south", d.get("facing"),
                                    what + " at " + x + "," + y + "," + z);
                        }
                    }
                }
            }
            assertEquals(written, stairs + slabs + kept, what + ": shaping never adds or removes a block");
            assertTrue(stairs >= 10, what + ": stairs " + stairs);
            assertTrue(kept > stairs, what + ": kept " + kept);
        }
    }

    @Test
    void blocksWithoutAFamilyAndUnturnedBuildsAreLeftAlone() {
        int stone = STATES.state("minecraft:dirt");
        TwoCellPairsTest.Cells cells = new TwoCellPairsTest.Cells(8, 3, 8, stone);
        SlopeShapes slopes = new SlopeShapes(new StairSlabVariants(STATES));
        Orientation o = Orientation.free(Transform.IDENTITY, 0, 2_000, 0);
        CellResampler r = CellResampler.smooth(o, 8, 3, 8);
        int[] cell = new int[3];
        BlockPos s = r.size();
        for (int x = 0; x < s.x(); x++) {
            for (int y = 0; y < s.y(); y++) {
                for (int z = 0; z < s.z(); z++) {
                    if (!r.source(x, y, z, cells, cell)) continue;
                    assertEquals(SlopeShapes.KEEP, slopes.shape(r, cells, x, y, z, stone));
                }
            }
        }
        // A quarter turn of pitch: every cell is whole, nothing is shaped.
        int planks = STATES.state("minecraft:oak_planks");
        TwoCellPairsTest.Cells plate = new TwoCellPairsTest.Cells(6, 2, 6, planks);
        Orientation quarter = Orientation.free(Transform.IDENTITY, 0, Orientation.QUARTER_TURN, 0);
        CellResampler q = CellResampler.nearest(quarter, 6, 2, 6);
        BlockPos qs = q.size();
        for (int x = 0; x < qs.x(); x++) {
            for (int y = 0; y < qs.y(); y++) {
                for (int z = 0; z < qs.z(); z++) {
                    if (!q.source(x, y, z, plate, cell)) continue;
                    assertEquals(SlopeShapes.KEEP, slopes.shape(q, plate, x, y, z, planks));
                }
            }
        }
    }
}
