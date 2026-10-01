package dev.sculptory.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.NbtBytes;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.history.HistoryPrograms;
import dev.sculptory.core.nbt.BlockEntityNbt;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtList;
import dev.sculptory.core.nbt.NbtTag;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeExecutor;
import dev.sculptory.core.testing.FakeWorld;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Helpers shared by the paste, move and stack tests. */
final class CopyTestSupport {
    private CopyTestSupport() {}

    /** One world cell: state and tile. */
    record Cell(int state, BlockEntityData tile) {}

    /** An overworld-height context (y -64 to 319) whose paste sources come from {@code sources}. */
    static CompileContext context(StateSpace states, Map<SourceRef, SourceBlocks> sources) {
        return new CompileContext() {
            @Override
            public StateSpace states() {
                return states;
            }

            @Override
            public Optional<SourceBlocks> source(SourceRef ref) {
                return Optional.ofNullable(sources.get(ref));
            }

            @Override
            public int bottomY() {
                return -64;
            }

            @Override
            public int topYExclusive() {
                return 320;
            }
        };
    }

    /** Every cell of {@code box} (air where nothing was set). */
    static Map<BlockPos, Cell> snapshot(FakeWorld world, Box box) {
        Map<BlockPos, Cell> cells = new HashMap<>();
        for (int x = box.min().x(); x <= box.max().x(); x++) {
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) {
                    cells.put(new BlockPos(x, y, z), new Cell(world.get(x, y, z), world.tile(x, y, z)));
                }
            }
        }
        return cells;
    }

    static void assertWorld(Map<BlockPos, Cell> expected, FakeWorld world, String what) {
        for (Map.Entry<BlockPos, Cell> entry : expected.entrySet()) {
            BlockPos p = entry.getKey();
            Cell actual = new Cell(world.get(p.x(), p.y(), p.z()), world.tile(p.x(), p.y(), p.z()));
            assertEquals(entry.getValue(), actual, what + " at " + p);
        }
    }

    /** Runs {@code program}, then its undo, and checks the world is back to {@code original} over {@code region}. */
    static FakeExecutor.Result runAndUndo(EditProgram program, FakeWorld world, Box region, String what) {
        Map<BlockPos, Cell> original = snapshot(world, region);
        FakeExecutor.Result result = FakeExecutor.run(program, world);
        Map<BlockPos, Cell> after = snapshot(world, region);
        if (!result.record().before().isEmpty()) {
            HistoryEntry entry = new HistoryEntry(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld",
                    program.label(), result.record(), 0L);
            FakeExecutor.Result undo = FakeExecutor.run(HistoryPrograms.undo(entry, ConflictPolicy.SKIP_CONFLICTS), world);
            assertEquals(0, undo.conflicts(), what + ": undo conflicts");
            assertWorld(original, world, what + " after undo");
            FakeExecutor.Result redo = FakeExecutor.run(HistoryPrograms.redo(entry, ConflictPolicy.SKIP_CONFLICTS), world);
            assertEquals(0, redo.conflicts(), what + ": redo conflicts");
            assertWorld(after, world, what + " after redo");
        }
        return result;
    }

    /** A chest tile holding {@code count} of {@code item}. */
    static NbtBytes chest(String item, int count) {
        NbtCompound nbt = NbtCompound.builder()
                .put("Items", NbtList.of(NbtTag.COMPOUND, List.of(NbtCompound.builder()
                        .putByte("Slot", (byte) 0).putString("id", item).putInt("count", count).build())))
                .build();
        return BlockEntityNbt.toNbtBytes("minecraft:chest", nbt);
    }

    static Box box(int x0, int y0, int z0, int x1, int y1, int z1) {
        return Box.of(new BlockPos(x0, y0, z0), new BlockPos(x1, y1, z1));
    }

    /** {@code box} grown by {@code margin} on every side. */
    static Box grow(Box box, int margin) {
        return new Box(box.min().offset(-margin, -margin, -margin), box.max().offset(margin, margin, margin));
    }
}
