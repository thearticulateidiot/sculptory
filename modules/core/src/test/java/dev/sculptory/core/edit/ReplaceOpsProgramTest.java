package dev.sculptory.core.edit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.NbtBytes;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeExecutor;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.world.WorldReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Better Replace's patterns in a region program, Overlay and Naturalize's column logic, and Update blocks' rules, run the way the executor runs them ({@link FakeExecutor}).
 */
class ReplaceOpsProgramTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final CompileContext context = CopyTestSupport.context(states, Map.of());
    private final int air = states.air();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");
    private final int grass = states.state("minecraft:grass_block");
    private final int andesite = states.state("minecraft:andesite");
    private final int cobble = states.state("minecraft:cobblestone");
    private final int water = states.state("minecraft:water");

    private static Region box(int x0, int y0, int z0, int x1, int y1, int z1) {
        return new Region.Cuboid(new Box(new BlockPos(x0, y0, z0), new BlockPos(x1, y1, z1)));
    }

    private static NamespacedId id(String value) {
        return new NamespacedId(value);
    }

    private FakeExecutor.Result run(OpSpec op, FakeWorld world) {
        return FakeExecutor.run(OpCompiler.compile(op, context), world);
    }

    // ============================================================= Keep shape and remap

    @Test
    void remapSwapsBlocksKeepingOrDroppingTheirShape() {
        int stair = states.state("minecraft:oak_stairs[facing=east,half=top,shape=inner_left,waterlogged=true]");
        Pattern.Remap keep = new Pattern.Remap(List.of(
                new Pattern.BlockSwap(id("minecraft:oak_stairs"), id("minecraft:stone_brick_stairs")),
                new Pattern.BlockSwap(id("minecraft:oak_planks"), id("minecraft:stone_bricks")),
                new Pattern.BlockSwap(id("minecraft:oak_log"), id("mymod:missing"))), true);
        assertEquals(states.state("minecraft:stone_brick_stairs[facing=east,half=top,shape=inner_left,waterlogged=true]"),
                keep.apply(states, 0, 0, 0, stair));
        assertEquals(states.state("minecraft:stone_bricks"), keep.apply(states, 0, 0, 0,
                states.state("minecraft:oak_planks")));
        int log = states.state("minecraft:oak_log[axis=x]");
        assertEquals(log, keep.apply(states, 0, 0, 0, log), "a block the space lacks swaps nothing");
        assertEquals(stone, keep.apply(states, 0, 0, 0, stone), "an unmapped block is unchanged");
        Pattern.Remap plain = new Pattern.Remap(keep.swaps(), false);
        assertEquals(states.state("minecraft:stone_brick_stairs"), plain.apply(states, 0, 0, 0, stair),
                "without keep shape: the default state");
        assertThrows(IllegalArgumentException.class, () -> new Pattern.Remap(List.of(), true));
        assertThrows(IllegalArgumentException.class, () -> new Pattern.Remap(List.of(
                new Pattern.BlockSwap(id("minecraft:stone"), id("minecraft:dirt")),
                new Pattern.BlockSwap(id("minecraft:stone"), id("minecraft:sand"))), true));
        assertThrows(IllegalArgumentException.class, () -> new Pattern.KeepShape(keep));
    }

    @Test
    void replaceWithKeepShapeKeepsEachStairsFacingAndTheCachedAnswersMatchThePattern() {
        FakeWorld world = new FakeWorld(states);
        Random random = new Random(3);
        List<Integer> stairs = new ArrayList<>();
        for (int h = 0; h < states.size(); h++) {
            if (states.blockId(h).equals(id("minecraft:oak_stairs"))) stairs.add(h);
        }
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                for (int y = 60; y < 64; y++) {
                    world.set(x, y, z, random.nextInt(3) == 0 ? stone : stairs.get(random.nextInt(stairs.size())));
                }
            }
        }
        FakeWorld reference = copy(world, 0, 60, 0, 19, 63, 19);
        Pattern to = new Pattern.KeepShape(new Pattern.Weighted(new int[] {states.state("minecraft:stone_brick_stairs"),
                states.state("minecraft:oak_slab")}, new int[] {3, 1}, 9L));
        OpSpec.Replace replace = new OpSpec.Replace(box(0, 60, 0, 19, 63, 19),
                new CellMask.Blocks(List.of(id("minecraft:oak_stairs"))), to);
        run(replace, world);
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                for (int y = 60; y < 64; y++) {
                    int before = reference.get(x, y, z);
                    int expected = before == stone ? stone : to.apply(states, x, y, z, before);
                    assertEquals(expected, world.get(x, y, z), x + "," + y + "," + z);
                    if (before != stone && states.blockId(expected).equals(id("minecraft:stone_brick_stairs"))) {
                        assertEquals(states.describe(before).get("facing"), states.describe(expected).get("facing"));
                        assertEquals(states.describe(before).get("half"), states.describe(expected).get("half"));
                    }
                }
            }
        }
    }

    @Test
    void keepShapeCarriesABlockEntityOnlyWhereTheNewBlockCanHoldIt() {
        NbtBytes contents = new NbtBytes("minecraft:chest", new byte[] {10, 0, 0});
        // By default only the same block keeps its block entity: a chest made a hopper starts empty.
        FakeWorld world = new FakeWorld(states);
        world.set(1, 64, 1, states.state("minecraft:chest[facing=north]"));
        world.setTile(1, 64, 1, contents);
        Pattern.Remap toHopper = new Pattern.Remap(List.of(new Pattern.BlockSwap(id("minecraft:chest"),
                id("minecraft:hopper"))), true);
        run(new OpSpec.Replace(box(0, 64, 0, 3, 64, 3), CellMask.ANY, toHopper), world);
        assertEquals(states.state("minecraft:hopper[facing=north]"), world.get(1, 64, 1), "facing kept");
        assertNull(world.tile(1, 64, 1), "the chest's contents do not fit a hopper");
        // A space whose chest block entity fits the hopper (as a sign's fits every wood's sign on Fabric) carries it.
        StateSpace fits = new DelegatingSpace(states) {
            @Override
            public boolean keepsBlockEntity(int from, int to) {
                return true;
            }
        };
        FakeWorld second = new FakeWorld(fits);
        second.set(1, 64, 1, states.state("minecraft:chest[facing=north]"));
        second.setTile(1, 64, 1, contents);
        FakeExecutor.run(OpCompiler.compile(new OpSpec.Replace(box(0, 64, 0, 3, 64, 3), CellMask.ANY, toHopper),
                CopyTestSupport.context(fits, Map.of())), second);
        assertEquals(contents, second.tile(1, 64, 1));
        // Without keep shape nothing is carried.
        FakeWorld third = new FakeWorld(fits);
        third.set(1, 64, 1, states.state("minecraft:chest[facing=north]"));
        third.setTile(1, 64, 1, contents);
        FakeExecutor.run(OpCompiler.compile(new OpSpec.Replace(box(0, 64, 0, 3, 64, 3), CellMask.ANY,
                new Pattern.Remap(toHopper.swaps(), false)), CopyTestSupport.context(fits, Map.of())), third);
        assertNull(third.tile(1, 64, 1));
    }

    @Test
    void aSymmetricCopyRemapsItsOwnStairsWithoutTurningThem() {
        FakeWorld world = new FakeWorld(states);
        world.set(2, 64, 0, states.state("minecraft:oak_stairs[facing=east]"));
        world.set(-3, 64, 0, states.state("minecraft:oak_stairs[facing=west]"));
        Pattern.Remap remap = new Pattern.Remap(List.of(new Pattern.BlockSwap(id("minecraft:oak_stairs"),
                id("minecraft:stone_brick_stairs"))), true);
        run(new OpSpec.Replace(box(1, 64, 0, 3, 64, 0), CellMask.ANY, remap,
                new Symmetry(Symmetry.Mode.MIRROR_X, 0, 0)), world);
        assertEquals(states.state("minecraft:stone_brick_stairs[facing=east]"), world.get(2, 64, 0));
        assertEquals(states.state("minecraft:stone_brick_stairs[facing=west]"), world.get(-3, 64, 0));
    }

    // ============================================================= Overlay

    /** Grass at 64 over stone in x, z 0..7, with the columns the tests name changed. */
    private FakeWorld terrain() {
        FakeWorld world = new FakeWorld(states);
        world.fill(new Box(new BlockPos(0, 50, 0), new BlockPos(7, 63, 7)), stone);
        world.fill(new Box(new BlockPos(0, 64, 0), new BlockPos(7, 64, 7)), grass);
        world.set(1, 65, 1, states.state("minecraft:poppy"));
        world.set(2, 65, 2, states.state("minecraft:short_grass"));
        world.set(3, 65, 3, states.state("minecraft:torch"));
        world.set(4, 64, 4, water);
        for (int y = 65; y <= 72; y++) world.set(5, y, 5, stone);
        world.set(6, 65, 6, states.state("minecraft:oak_log"));
        world.set(6, 66, 6, states.state("minecraft:sugar_cane"));
        world.set(7, 65, 7, states.state("minecraft:tall_grass[half=lower]"));
        world.set(7, 66, 7, states.state("minecraft:tall_grass[half=upper]"));
        return world;
    }

    @Test
    void overlayLaysItsLayerOnTheHighestBlockOfEachColumn() {
        FakeWorld world = terrain();
        FakeWorld before = copy(world, 0, 50, 0, 7, 75, 7);
        run(new OpSpec.Overlay(box(0, 60, 0, 7, 70, 7), new Pattern.Single(cobble), 2), world);
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z <= 7; z++) {
                String at = x + "," + z;
                if (x == z) {
                    switch (x) {
                        case 1, 3, 6, 7 -> assertColumnUnchanged(before, world, x, z, at + ": a plant, torch, cane or "
                                + "tall grass stops the layer");
                        case 2 -> {
                            assertEquals(cobble, world.get(x, 65, z), "short grass is replaceable");
                            assertEquals(cobble, world.get(x, 66, z));
                        }
                        case 4 -> assertColumnUnchanged(before, world, x, z, "water is no highest block");
                        case 5 -> assertColumnUnchanged(before, world, x, z, "buried: stone above the region");
                        default -> {
                            assertEquals(cobble, world.get(x, 65, z), at);
                            assertEquals(cobble, world.get(x, 66, z), at);
                        }
                    }
                    continue;
                }
                assertEquals(grass, world.get(x, 64, z), at + ": the highest block stays");
                assertEquals(cobble, world.get(x, 65, z), at);
                assertEquals(cobble, world.get(x, 66, z), at);
                assertEquals(air, world.get(x, 67, z), at + ": depth 2");
            }
        }
    }

    @Test
    void overlaysLayerMayReachAboveTheRegionAndItsSectionsSaySo() {
        FakeWorld world = terrain();
        // A block selection of just the grass (as magic select makes), the top of a section at y 63.
        CellSet.Builder cells = CellSet.builder();
        for (int x = 0; x <= 3; x++) {
            for (int z = 0; z <= 3; z++) {
                world.set(x, 63, z, grass);
                world.set(x, 64, z, air);
                world.set(x, 65, z, air);
                cells.add(x, 63, z);
            }
        }
        Region region = new Region.Cells(cells.build());
        EditProgram program = OpCompiler.compile(new OpSpec.Overlay(region, new Pattern.Single(cobble), 3), context);
        assertEquals(66, program.bounds().max().y());
        assertArrayEquals(new long[] {BlockBuffer.key(0, 3, 0), BlockBuffer.key(0, 4, 0)}, program.sectionOrder(),
                "the region's section and the one above");
        FakeExecutor.run(program, world);
        for (int x = 0; x <= 3; x++) {
            for (int z = 0; z <= 3; z++) {
                assertEquals(grass, world.get(x, 63, z));
                for (int y = 64; y <= 66; y++) assertEquals(cobble, world.get(x, y, z), x + "," + y + "," + z);
            }
        }
        long cellCount = 16;
        assertEquals(cellCount + 4 * 4 * 3, OpCompiler.targetVolume(new OpSpec.Overlay(region,
                new Pattern.Single(cobble), 3), null), "the region's cells plus a layer over each column");
    }

    @Test
    void overlaySpreadsAPaletteAndMirrorsItWithSymmetry() {
        FakeWorld world = new FakeWorld(states);
        world.fill(new Box(new BlockPos(-8, 60, 0), new BlockPos(7, 63, 3)), stone);
        Pattern.Weighted mix = new Pattern.Weighted(new int[] {cobble, andesite}, new int[] {1, 1}, 5L);
        run(new OpSpec.Overlay(box(1, 60, 0, 7, 66, 3), mix, 1, new Symmetry(Symmetry.Mode.MIRROR_X, 0, 0)), world);
        for (int x = 1; x <= 7; x++) {
            for (int z = 0; z <= 3; z++) {
                int here = world.get(x, 64, z);
                assertEquals(mix.apply(states, x, 64, z, air), here, x + "," + z);
                assertEquals(here, world.get(-x - 1, 64, z), "the mirror copy lays the mirrored mix");
            }
        }
        assertEquals(air, world.get(0, 64, 0), "outside both copies");
    }

    // ============================================================= Naturalize

    @Test
    void naturalizeLaysGrassDirtAndStoneOnTheGroundOnly() {
        FakeWorld world = new FakeWorld(states);
        world.fill(new Box(new BlockPos(0, 40, 0), new BlockPos(3, 64, 3)), andesite);
        for (int y = 55; y <= 57; y++) world.set(1, y, 1, air); // a cave
        int chest = states.state("minecraft:chest");
        world.set(2, 63, 2, chest);
        world.set(3, 65, 3, water);
        world.set(0, 65, 1, states.state("minecraft:oak_stairs"));
        world.set(0, 65, 2, states.state("minecraft:poppy"));
        run(new OpSpec.Naturalize(box(0, 40, 0, 3, 70, 3), new Pattern.Single(grass), 1, new Pattern.Single(dirt), 3,
                new Pattern.Single(stone)), world);
        // An ordinary column, and one under a poppy (a plant is passed through).
        for (int[] column : new int[][] {{0, 0}, {0, 2}, {2, 0}}) {
            int x = column[0], z = column[1];
            assertEquals(grass, world.get(x, 64, z));
            for (int y = 61; y <= 63; y++) assertEquals(dirt, world.get(x, y, z), x + "," + y + "," + z);
            for (int y = 40; y <= 60; y++) assertEquals(stone, world.get(x, y, z), x + "," + y + "," + z);
        }
        assertEquals(states.state("minecraft:poppy"), world.get(0, 65, 2));
        // A cave keeps its air, and the depth counts in cells below the top.
        assertEquals(air, world.get(1, 56, 1));
        assertEquals(stone, world.get(1, 54, 1));
        assertEquals(stone, world.get(1, 58, 1));
        // A chest stays and still counts as depth.
        assertEquals(chest, world.get(2, 63, 2));
        assertEquals(dirt, world.get(2, 62, 2));
        assertEquals(dirt, world.get(2, 61, 2));
        assertEquals(stone, world.get(2, 60, 2));
        // Under water: left alone.
        for (int y = 40; y <= 64; y++) assertEquals(andesite, world.get(3, y, 3));
        // Stairs on top are the highest block: grass would go there, so the ground below starts with dirt.
        assertEquals(states.state("minecraft:oak_stairs"), world.get(0, 65, 1));
        assertEquals(dirt, world.get(0, 64, 1));
        assertEquals(dirt, world.get(0, 62, 1));
        assertEquals(stone, world.get(0, 61, 1));
    }

    @Test
    void naturalizeLooksPastTreesAndLeavesBuildsAlone() {
        FakeWorld world = new FakeWorld(states);
        world.fill(new Box(new BlockPos(0, 50, 0), new BlockPos(3, 64, 3)), andesite);
        // A tree: a trunk with a plant on top (leaves stand in); the ground under it is naturalized, the trunk kept.
        int log = states.state("minecraft:oak_log");
        for (int y = 65; y <= 68; y++) world.set(1, y, 1, log);
        world.set(1, 69, 1, states.state("minecraft:sugar_cane"));
        // A plank floor and wall on the ground: not natural, left as it is (and counted as depth like a chest).
        int planks = states.state("minecraft:oak_planks");
        world.set(2, 64, 2, planks);
        world.set(2, 65, 2, planks);
        run(new OpSpec.Naturalize(box(0, 50, 0, 3, 72, 3), new Pattern.Single(grass), 1, new Pattern.Single(dirt), 3,
                new Pattern.Single(stone)), world);
        for (int y = 65; y <= 68; y++) assertEquals(log, world.get(1, y, 1), "the trunk stays at " + y);
        assertEquals(grass, world.get(1, 64, 1), "grass under the tree");
        assertEquals(dirt, world.get(1, 61, 1));
        assertEquals(stone, world.get(1, 60, 1));
        assertEquals(planks, world.get(2, 65, 2));
        assertEquals(planks, world.get(2, 64, 2));
        assertEquals(dirt, world.get(2, 63, 2), "depth counts from the plank wall's top");
        assertEquals(stone, world.get(2, 61, 2));
        // Run again with other blocks: its own blocks count as ground.
        int sand = states.state("minecraft:sand");
        run(new OpSpec.Naturalize(box(0, 50, 0, 0, 72, 0), new Pattern.Single(sand), 1, new Pattern.Single(sand), 0,
                new Pattern.Single(andesite)), world);
        assertEquals(sand, world.get(0, 64, 0));
        assertEquals(andesite, world.get(0, 63, 0));
    }

    @Test
    void aGapInABlockSelectionIsLookedThroughNotJumped() {
        FakeWorld world = new FakeWorld(states);
        world.fill(new Box(new BlockPos(0, 60, 0), new BlockPos(1, 60, 1)), stone);
        // A roof over column (0, 0), outside the selection: the selected stone under it is not its highest block.
        world.set(0, 64, 0, stone);
        CellSet.Builder cells = CellSet.builder();
        cells.add(0, 66, 0).add(0, 60, 0).add(1, 66, 1).add(1, 60, 1);
        run(new OpSpec.Overlay(new Region.Cells(cells.build()), new Pattern.Single(cobble), 1), world);
        assertEquals(air, world.get(0, 61, 0), "nothing under the roof");
        assertEquals(stone, world.get(0, 64, 0));
        assertEquals(air, world.get(0, 65, 0), "the roof is outside the selection: no layer on it either");
        assertEquals(cobble, world.get(1, 61, 1), "an open column gets its layer across the gap");
    }

    @Test
    void naturalizeWithoutMiddleLayerAndOnAShape() {
        FakeWorld world = new FakeWorld(states);
        world.fill(new Box(new BlockPos(-8, 50, -8), new BlockPos(8, 64, 8)), andesite);
        Region sphere = new Region.Shape(new Box(new BlockPos(-6, 52, -6), new BlockPos(6, 64, 6)),
                dev.sculptory.core.region.ShapeKind.ELLIPSOID, dev.sculptory.core.region.Facing.UP);
        run(new OpSpec.Naturalize(sphere, new Pattern.Single(grass), 2, new Pattern.Single(dirt), 0,
                new Pattern.Single(stone)), world);
        // The sphere's top cell at its centre column, 64: grass there and at 63, stone below.
        assertEquals(grass, world.get(0, 64, 0));
        assertEquals(grass, world.get(0, 63, 0));
        assertEquals(stone, world.get(0, 62, 0));
        assertEquals(andesite, world.get(0, 51, 0), "outside the sphere");
        // A column whose topmost sphere cell is under andesite is buried: left alone.
        assertEquals(andesite, world.get(5, 58, 0));
    }

    @Test
    void layerDepthsOutOfRangeAreRefused() {
        Region region = box(0, 60, 0, 3, 64, 3);
        Pattern p = new Pattern.Single(stone);
        assertThrows(IllegalArgumentException.class, () -> new OpSpec.Overlay(region, p, 0));
        assertThrows(IllegalArgumentException.class, () -> new OpSpec.Overlay(region, p, 17));
        assertThrows(IllegalArgumentException.class, () -> new OpSpec.Naturalize(region, p, 0, p, 3, p));
        assertThrows(IllegalArgumentException.class, () -> new OpSpec.Naturalize(region, p, 1, p, 17, p));
        assertThrows(IllegalArgumentException.class, () -> OpCompiler.compile(new OpSpec.Overlay(region,
                new Pattern.Single(states.size() + 5), 1), context), "a state outside the space");
        assertThrows(IllegalArgumentException.class, () -> OpCompiler.compile(new OpSpec.Overlay(
                box(0, 400, 0, 3, 410, 3), p, 1), context), "outside the build height");
    }

    // ============================================================= Update blocks

    /** Stairs east of stone turn outer-left, others straight; a door loses itself (a block change); a chest turns east. */
    private final class FakeShapes implements NeighbourShapes {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public int reshape(int x, int y, int z, int state, WorldReader view) {
            calls.incrementAndGet();
            String block = states.blockId(state).value();
            return switch (block) {
                case "minecraft:oak_stairs" -> states.withProperty(state, "shape",
                        view.get(x + 1, y, z) == stone ? "outer_left" : "straight");
                case "minecraft:oak_door" -> air;
                case "minecraft:chest" -> states.withProperty(state, "facing", "east");
                default -> state;
            };
        }
    }

    private CompileContext withShapes(NeighbourShapes shapes) {
        return new CompileContext() {
            @Override
            public StateSpace states() {
                return states;
            }

            @Override
            public Optional<SourceBlocks> source(SourceRef ref) {
                return Optional.empty();
            }

            @Override
            public int bottomY() {
                return -64;
            }

            @Override
            public int topYExclusive() {
                return 320;
            }

            @Override
            public NeighbourShapes neighbourShapes() {
                return shapes;
            }
        };
    }

    @Test
    void updateBlocksKeepsShapeChangesOnlyAndCarriesBlockEntities() {
        FakeWorld world = new FakeWorld(states);
        world.fill(new Box(new BlockPos(0, 63, 0), new BlockPos(7, 63, 7)), stone);
        world.set(1, 64, 1, states.state("minecraft:oak_stairs[shape=inner_right]"));
        world.set(2, 64, 1, stone);
        world.set(1, 64, 3, states.state("minecraft:oak_stairs[shape=outer_right]"));
        int door = states.state("minecraft:oak_door[half=lower]");
        world.set(3, 64, 3, door);
        world.set(4, 64, 4, states.state("minecraft:chest[facing=north]"));
        NbtBytes contents = new NbtBytes("minecraft:chest", new byte[] {10, 0, 0});
        world.setTile(4, 64, 4, contents);
        world.set(5, 64, 5, water);
        FakeShapes shapes = new FakeShapes();
        EditProgram program = OpCompiler.compile(new OpSpec.UpdateBlocks(box(0, 63, 0, 7, 65, 7)), withShapes(shapes));
        assertTrue(program.relightsAfter());
        assertEquals("Update blocks", program.label());
        FakeExecutor.Result result = FakeExecutor.run(program, world);
        assertEquals(states.state("minecraft:oak_stairs[shape=outer_left]"), world.get(1, 64, 1));
        assertEquals(states.state("minecraft:oak_stairs[shape=straight]"), world.get(1, 64, 3));
        assertEquals(door, world.get(3, 64, 3), "a result of another block (physics) is dropped");
        assertEquals(states.state("minecraft:chest[facing=east]"), world.get(4, 64, 4));
        assertEquals(contents, world.tile(4, 64, 4), "the chest keeps its contents");
        assertEquals(3, result.written());
        assertEquals(4, shapes.calls.get(), "stone, air and water are never asked");
    }

    @Test
    void updateBlocksNeedsTheServersShapesAndReadsTheColumnsBeside() {
        Region region = box(0, 60, 0, 3, 64, 3);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> OpCompiler.compile(new OpSpec.UpdateBlocks(region), context));
        assertTrue(refused.getMessage().contains("Update blocks"), refused.getMessage());
        EditProgram program = OpCompiler.compile(new OpSpec.UpdateBlocks(region), withShapes(new FakeShapes()));
        long[] columns = program.readColumns(BlockBuffer.key(0, 3, 0));
        assertEquals(4, columns.length);
        assertTrue(java.util.Arrays.stream(columns).anyMatch(c -> c == EditProgram.column(-1, 0)));
        assertTrue(java.util.Arrays.stream(columns).anyMatch(c -> c == EditProgram.column(0, 1)));
        EditProgram mirrored = OpCompiler.compile(new OpSpec.UpdateBlocks(region,
                new Symmetry(Symmetry.Mode.MIRROR_X, 20, 0)), withShapes(new FakeShapes()));
        assertTrue(mirrored.relightsAfter(), "a symmetric op relights like its copies");
        assertFalse(OpCompiler.compile(new OpSpec.Fill(region, new Pattern.Single(stone), CellMask.ANY), context)
                .relightsAfter());
    }

    // ============================================================= helpers

    private void assertColumnUnchanged(FakeWorld before, FakeWorld after, int x, int z, String what) {
        for (int y = 50; y <= 75; y++) assertEquals(before.get(x, y, z), after.get(x, y, z), what + " at y " + y);
    }

    private FakeWorld copy(FakeWorld world, int x0, int y0, int z0, int x1, int y1, int z1) {
        FakeWorld copy = new FakeWorld(states);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) copy.set(x, y, z, world.get(x, y, z));
            }
        }
        return copy;
    }

    /** A state space answering as another does, for overriding one method. */
    private static class DelegatingSpace implements StateSpace {
        private final StateSpace inner;

        DelegatingSpace(StateSpace inner) {
            this.inner = inner;
        }

        @Override
        public int size() {
            return inner.size();
        }

        @Override
        public int air() {
            return inner.air();
        }

        @Override
        public int flags(int h) {
            return inner.flags(h);
        }

        @Override
        public String format(int h) {
            return inner.format(h);
        }

        @Override
        public int parse(String spec) {
            return inner.parse(spec);
        }

        @Override
        public dev.sculptory.core.BlockDescriptor describe(int h) {
            return inner.describe(h);
        }

        @Override
        public int resolve(dev.sculptory.core.BlockDescriptor d) {
            return inner.resolve(d);
        }

        @Override
        public NamespacedId blockId(int h) {
            return inner.blockId(h);
        }

        @Override
        public boolean inTag(int h, NamespacedId tag) {
            return inner.inTag(h, tag);
        }

        @Override
        public int rotate(int h, int clockwiseQuarterTurns) {
            return inner.rotate(h, clockwiseQuarterTurns);
        }

        @Override
        public int mirror(int h, dev.sculptory.core.transform.Mirror m) {
            return inner.mirror(h, m);
        }

        @Override
        public int withWaterlogged(int h, boolean on) {
            return inner.withWaterlogged(h, on);
        }

        @Override
        public int fluidSource(int h) {
            return inner.fluidSource(h);
        }
    }
}
