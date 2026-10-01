package dev.sculptory.core.edit;

import static dev.sculptory.core.edit.CopyTestSupport.box;
import static dev.sculptory.core.edit.CopyTestSupport.chest;
import static dev.sculptory.core.edit.CopyTestSupport.grow;
import static dev.sculptory.core.edit.CopyTestSupport.runAndUndo;
import static dev.sculptory.core.edit.CopyTestSupport.snapshot;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.NbtBytes;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.CopyTestSupport.Cell;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.testing.FakeExecutor;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PasteTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final Map<SourceRef, SourceBlocks> sources = new HashMap<>();
    private final CompileContext context = CopyTestSupport.context(states, sources);
    private final SourceRef ref = new SourceRef.Clipboard(UUID.randomUUID());
    private final int air = states.air();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");

    /**
     * Every transform record, including the Z mirrors (equal in effect to an X mirror plus a half turn), each also
     * flipped upside down.
     */
    private static List<Transform> everyTransform() {
        List<Transform> all = new ArrayList<>();
        for (boolean flip : new boolean[] {false, true}) {
            for (Mirror mirror : Mirror.values()) {
                for (int turns = 0; turns < 4; turns++) all.add(new Transform(turns, mirror, flip));
            }
        }
        return all;
    }

    private EditProgram paste(Clipboard clipboard, BlockPos origin, Transform t, PasteOptions options) {
        sources.put(ref, clipboard.toSource());
        return OpCompiler.compile(new OpSpec.Paste(ref, origin, t, options), context);
    }

    private int stairs(String facing) {
        return states.state("minecraft:oak_stairs[facing=" + facing + "]");
    }

    private String property(int state, String name) {
        return states.describe(state).get(name);
    }

    /**
     * A geometric check independent of the transform maths: stairs on the four arms of a plus face outward and
     * logs lie along their arm, so after any transform they must still face outward and lie along their arm.
     */
    @Test
    void everyTransformKeepsFacingsAndAxesConsistentWithPositions() {
        Clipboard plus = Clipboard.builder(states, new BlockPos(5, 1, 5)).anchor(new BlockPos(2, 0, 2))
                .set(2, 0, 2, stone)
                .set(4, 0, 2, stairs("east")).set(0, 0, 2, stairs("west"))
                .set(2, 0, 0, stairs("north")).set(2, 0, 4, stairs("south"))
                .set(3, 0, 2, states.state("minecraft:oak_log[axis=x]"))
                .set(2, 0, 3, states.state("minecraft:oak_log[axis=z]"))
                .set(1, 0, 2, states.state("testmod:widget[facing=up]"))
                .build();
        Map<List<Integer>, String> outward = Map.of(List.of(2, 0), "east", List.of(-2, 0), "west",
                List.of(0, -2), "north", List.of(0, 2), "south");
        for (Transform t : everyTransform()) {
            FakeWorld world = new FakeWorld(states);
            BlockPos o = new BlockPos(100, 10, -50);
            FakeExecutor.Result result = FakeExecutor.run(paste(plus, o, t, PasteOptions.DEFAULT), world);
            assertEquals(8, result.written(), t.toString());
            assertEquals(stone, world.get(o.x(), o.y(), o.z()), t + ": the anchor lands on the origin");
            outward.forEach((d, facing) -> {
                int state = world.get(o.x() + d.get(0), o.y(), o.z() + d.get(1));
                assertEquals("minecraft:oak_stairs", states.blockId(state).value(), t + " arm " + d);
                assertEquals(facing, property(state, "facing"), t + " arm " + d);
            });
            int logs = 0, widgets = 0;
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int state = world.get(o.x() + d[0], o.y(), o.z() + d[1]);
                if (state == air) continue;
                if (states.blockId(state).value().equals("minecraft:oak_log")) {
                    logs++;
                    assertEquals(d[0] != 0 ? "x" : "z", property(state, "axis"), t + " log at " + d[0] + "," + d[1]);
                } else {
                    widgets++;
                    assertEquals(t.upsideDown() ? "down" : "up", property(state, "facing"),
                            t + ": up stays up unless flipped");
                }
            }
            assertEquals(2, logs, t.toString());
            assertEquals(1, widgets, t.toString());
        }
    }

    /** Positions and states worked out by hand for a quarter turn and an x mirror of a 3 x 1 x 2 source. */
    @Test
    void quarterTurnAndMirrorByHand() {
        int logX = states.state("minecraft:oak_log[axis=x]");
        Clipboard source = Clipboard.builder(states, new BlockPos(3, 1, 2))
                .set(0, 0, 0, stairs("east")).set(2, 0, 0, stone).set(0, 0, 1, dirt).set(2, 0, 1, logX)
                .build();
        BlockPos origin = new BlockPos(10, 5, 10);

        FakeWorld turned = new FakeWorld(states);
        FakeExecutor.run(paste(source, origin, Transform.rotation(1), PasteOptions.DEFAULT), turned);
        // The 3 (x) by 2 (z) source becomes 2 by 3; the anchor (0,0,0) maps to local (1,0,0), so min is (9,5,10).
        assertEquals(stairs("south"), turned.get(10, 5, 10));
        assertEquals(dirt, turned.get(9, 5, 10));
        assertEquals(stone, turned.get(10, 5, 12));
        assertEquals(states.state("minecraft:oak_log[axis=z]"), turned.get(9, 5, 12));
        assertEquals(4, turned.blocks().cellCount());

        FakeWorld mirrored = new FakeWorld(states);
        FakeExecutor.run(paste(source, origin, new Transform(0, Mirror.X), PasteOptions.DEFAULT), mirrored);
        // x is negated: the anchor maps to local (2,0,0), so min is (8,5,10).
        assertEquals(stairs("west"), mirrored.get(10, 5, 10));
        assertEquals(stone, mirrored.get(8, 5, 10));
        assertEquals(dirt, mirrored.get(10, 5, 11));
        assertEquals(logX, mirrored.get(8, 5, 11));
    }

    /** Random content through every transform, against a forward-mapping reference model, then exact undo/redo. */
    @Test
    void everyTransformMatchesTheReferenceModel() {
        Random random = new Random(42);
        List<Integer> palette = new ArrayList<>();
        for (int h = 0; h < states.size(); h++) {
            if (!states.blockId(h).value().equals("minecraft:water")) palette.add(h);
        }
        BlockPos size = new BlockPos(5, 3, 4);
        for (BlockPos anchor : List.of(new BlockPos(1, 1, 2), new BlockPos(-3, 2, 7))) {
            Clipboard.Builder builder = Clipboard.builder(states, size).anchor(anchor);
            int chests = 0;
            for (int x = 0; x < size.x(); x++) {
                for (int y = 0; y < size.y(); y++) {
                    for (int z = 0; z < size.z(); z++) {
                        if (random.nextInt(7) == 0) continue;
                        int state = random.nextInt(5) == 0 ? air : palette.get(random.nextInt(palette.size()));
                        builder.set(x, y, z, state);
                        if (StateFlags.has(states.flags(state), StateFlags.HAS_BLOCK_ENTITY)) {
                            builder.setTile(x, y, z, chest("minecraft:apple", ++chests));
                        }
                    }
                }
            }
            Clipboard clipboard = builder.build();
            for (Transform t : everyTransform()) {
                for (boolean includeAir : new boolean[] {false, true}) {
                    String what = t + " anchor " + anchor + (includeAir ? " with air" : "");
                    BlockPos origin = new BlockPos(-7, 70, 21);
                    FakeWorld world = new FakeWorld(states);
                    Box region = grow(Box.of(origin), 12);
                    world.fill(box(-12, 64, 16, 0, 80, 26), dirt);
                    world.set(-7, 70, 21, states.state("minecraft:chest"));
                    world.setTile(-7, 70, 21, chest("minecraft:diamond", 9));
                    Map<BlockPos, Cell> expected = snapshot(world, region);
                    int ax = t.mapX(anchor.x(), anchor.z(), size.x(), size.z());
                    int az = t.mapZ(anchor.x(), anchor.z(), size.x(), size.z());
                    clipboard.forEachCell((x, y, z, state, tile) -> {
                        if (state < 0 || (!includeAir && state == air)) return;
                        BlockPos at = new BlockPos(origin.x() - ax + t.mapX(x, z, size.x(), size.z()),
                                origin.y() - t.mapY(anchor.y(), size.y()) + t.mapY(y, size.y()),
                                origin.z() - az + t.mapZ(x, z, size.x(), size.z()));
                        expected.put(at, new Cell(t.applyToState(states, state), tile));
                    });
                    EditProgram program = paste(clipboard, origin, t, new PasteOptions(includeAir, false));
                    runAndUndo(program, world, region, what);
                    // runAndUndo ends after a redo: the world holds the paste again.
                    CopyTestSupport.assertWorld(expected, world, what);
                }
            }
        }
    }

    @Test
    void airIsPastedOnlyWhenIncludedAndAbsentCellsNever() {
        Clipboard source = Clipboard.builder(states, new BlockPos(3, 1, 1)).set(0, 0, 0, air).set(2, 0, 0, stone).build();
        for (boolean includeAir : new boolean[] {false, true}) {
            FakeWorld world = new FakeWorld(states);
            world.fill(box(0, 0, 0, 2, 0, 0), dirt);
            EditProgram program = paste(source, BlockPos.ORIGIN, Transform.IDENTITY, new PasteOptions(includeAir, false));
            assertEquals(includeAir ? 2 : 1, program.estimatedCells());
            FakeExecutor.run(program, world);
            assertEquals(includeAir ? air : dirt, world.get(0, 0, 0));
            assertEquals(dirt, world.get(1, 0, 0), "absent cells are never written");
            assertEquals(stone, world.get(2, 0, 0));
        }
    }

    @Test
    void tilesAreCopiedAndUnchangedCellsSkipped() {
        int chestState = states.state("minecraft:chest[facing=east]");
        NbtBytes gold = chest("minecraft:gold_ingot", 3);
        Clipboard source = Clipboard.builder(states, new BlockPos(2, 1, 1))
                .set(0, 0, 0, chestState).setTile(0, 0, 0, gold).set(1, 0, 0, stone).build();
        FakeWorld world = new FakeWorld(states);
        world.set(5, 5, 5, chestState);
        world.setTile(5, 5, 5, chest("minecraft:dirt", 64));
        world.set(6, 5, 5, stone);
        EditProgram program = paste(source, new BlockPos(5, 5, 5), Transform.IDENTITY, PasteOptions.DEFAULT);
        FakeExecutor.Result first = runAndUndo(program, world, box(4, 4, 4, 7, 6, 6), "chest paste");
        assertEquals(1, first.written(), "same state, other contents: only the chest is written");
        BlockEntityData tile = world.tile(5, 5, 5);
        assertEquals(gold, tile);
        assertEquals(0, FakeExecutor.run(program, world).written(), "pasting the same content again writes nothing");

        // Turning the chest changes its facing but carries the NBT as it is.
        FakeWorld turned = new FakeWorld(states);
        FakeExecutor.run(paste(source, BlockPos.ORIGIN, Transform.rotation(1), PasteOptions.DEFAULT), turned);
        assertEquals(states.state("minecraft:chest[facing=south]"), turned.get(0, 0, 0));
        assertEquals(gold, turned.tile(0, 0, 0));
    }

    @Test
    void clipsToTheBuildHeightAndRefusesPastesOutsideIt() {
        Clipboard.Builder builder = Clipboard.builder(states, new BlockPos(4, 10, 4));
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 10; y++) {
                for (int z = 0; z < 4; z++) builder.set(x, y, z, stone);
            }
        }
        Clipboard tower = builder.build();
        EditProgram top = paste(tower, new BlockPos(0, 315, 0), Transform.IDENTITY, PasteOptions.DEFAULT);
        assertEquals(box(0, 315, 0, 3, 319, 3), top.bounds());
        assertEquals(4 * 5 * 4, top.estimatedCells());
        FakeWorld world = new FakeWorld(states);
        assertEquals(80, FakeExecutor.run(top, world).written());
        EditProgram bottom = paste(tower, new BlockPos(0, -70, 0), Transform.IDENTITY, PasteOptions.DEFAULT);
        assertEquals(4 * 4 * 4, bottom.estimatedCells());
        assertThrows(IllegalArgumentException.class,
                () -> paste(tower, new BlockPos(0, 400, 0), Transform.IDENTITY, PasteOptions.DEFAULT));
        assertThrows(IllegalArgumentException.class,
                () -> paste(tower, new BlockPos(Integer.MAX_VALUE - 1, 0, 0), Transform.IDENTITY, PasteOptions.DEFAULT));
    }

    @Test
    void listsOnlyTheSectionsTheSourceOccupies() {
        Clipboard sparse = Clipboard.builder(states, new BlockPos(40, 1, 40)).set(0, 0, 0, stone).set(39, 0, 39, dirt).build();
        for (Transform t : everyTransform()) {
            EditProgram program = paste(sparse, new BlockPos(0, 0, 0), t, PasteOptions.DEFAULT);
            assertEquals(2, program.estimatedCells(), t.toString());
            long[] order = program.sectionOrder();
            assertEquals(2, order.length, t.toString());
            long[] sorted = order.clone();
            SectionOrder.sort(sorted);
            assertArrayEquals(sorted, order);
            assertEquals(0, program.sourceSections().length);
            FakeWorld world = new FakeWorld(states);
            assertEquals(2, FakeExecutor.run(program, world).written(), t.toString());
        }
    }

    @Test
    void refusesUnknownSourcesAndForeignStates() {
        OpSpec.Paste unknown = new OpSpec.Paste(new SourceRef.Asset("a".repeat(64)), BlockPos.ORIGIN, Transform.IDENTITY,
                PasteOptions.DEFAULT);
        assertThrows(IllegalArgumentException.class, () -> OpCompiler.compile(unknown, context));
        BlockBuffer foreign = new BlockBuffer();
        foreign.set(0, 0, 0, states.size());
        sources.put(ref, new SourceBlocks(foreign, new BlockPos(1, 1, 1), BlockPos.ORIGIN));
        assertThrows(IllegalArgumentException.class, () -> OpCompiler.compile(
                new OpSpec.Paste(ref, BlockPos.ORIGIN, Transform.IDENTITY, PasteOptions.DEFAULT), context));
    }

    /** An anchor near the int range used to wrap when transformed and could land the paste somewhere valid. */
    @Test
    void farAnchorsCannotWrapAround() {
        Clipboard wrapping = Clipboard.builder(states, new BlockPos(3, 1, 3)).set(0, 0, 0, stone)
                .anchor(new BlockPos(Integer.MIN_VALUE + 1, 0, 0)).build();
        BlockPos origin = new BlockPos(Integer.MIN_VALUE + 5, 0, 0);
        for (Transform t : List.of(Transform.rotation(2), new Transform(0, Mirror.X), Transform.rotation(1))) {
            assertThrows(IllegalArgumentException.class, () -> paste(wrapping, origin, t, PasteOptions.DEFAULT), t.toString());
        }
        // A large anchor that fits still places exactly: a half turn maps local (x, z) to (2 - x, 2 - z), so the
        // anchor (1e6, 0, 0) maps to (2 - 1e6, 0, 2) and the box starts at origin minus that.
        Clipboard far = Clipboard.builder(states, new BlockPos(3, 1, 3)).set(0, 0, 0, stone)
                .anchor(new BlockPos(1_000_000, 0, 0)).build();
        EditProgram program = paste(far, new BlockPos(1_000_000, 0, 0), Transform.rotation(2), PasteOptions.DEFAULT);
        assertEquals(box(1_999_998, 0, -2, 2_000_000, 0, 0), program.bounds());
    }

    @Test
    void emptySourcePastesNothing() {
        Clipboard empty = Clipboard.builder(states, new BlockPos(3, 3, 3)).build();
        EditProgram program = paste(empty, BlockPos.ORIGIN, Transform.IDENTITY, PasteOptions.DEFAULT);
        assertEquals(0, program.estimatedCells());
        assertEquals(0, program.sectionOrder().length);
    }
}
