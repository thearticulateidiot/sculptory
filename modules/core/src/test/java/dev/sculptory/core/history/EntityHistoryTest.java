package dev.sculptory.core.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.edit.EditProgram;
import dev.sculptory.core.entity.EntityNbt;
import dev.sculptory.core.history.EntityHistory.Action;
import dev.sculptory.core.history.EntityHistory.Step;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtIo;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Undo and redo of entities: the conflict rule, runs, the matcher and records holding entities only. */
class EntityHistoryTest {
    private static final UUID PLAYER = UUID.randomUUID();
    private final EntityMatcher matcher = EntityMatcher.ignoringVolatile();

    /** An item frame's whole NBT as the world holds it. */
    static EntityState frame(UUID id, double x, String item, int age) {
        NbtCompound nbt = NbtCompound.builder().putString("id", "minecraft:item_frame")
                .put("Pos", EntityNbt.doubles(x, 64.5, 0.03)).putIntArray("UUID", ints(id))
                .putShort("Air", (short) age).putString("Item", item).build();
        return new EntityState("minecraft:item_frame", x, 64.5, 0.03, NbtIo.toBytes(nbt));
    }

    private static int[] ints(UUID id) {
        long m = id.getMostSignificantBits(), l = id.getLeastSignificantBits();
        return new int[] {(int) (m >> 32), (int) m, (int) (l >> 32), (int) l};
    }

    @Test
    void undoRemovesWhatThePasteLeftAndKeepsWhatChanged() {
        UUID id = UUID.randomUUID();
        EntityState placed = frame(id, 3.5, "apple", 300);
        Step undo = new Step(id, placed, null);
        assertEquals(Action.APPLY, EntityHistory.decide(undo, placed, ConflictPolicy.SKIP_CONFLICTS, matcher));
        assertEquals(Action.APPLY, EntityHistory.decide(undo, frame(id, 3.5, "apple", 12),
                ConflictPolicy.SKIP_CONFLICTS, matcher), "a volatile key changed: still the pasted frame");
        EntityState filled = frame(id, 3.5, "diamond", 300);
        assertEquals(Action.CONFLICT, EntityHistory.decide(undo, filled, ConflictPolicy.SKIP_CONFLICTS, matcher),
                "a frame holding something else since is kept");
        assertEquals(Action.APPLY, EntityHistory.decide(undo, filled, ConflictPolicy.OVERWRITE, matcher),
                "Undo anyway takes it");
        assertEquals(Action.SKIP, EntityHistory.decide(undo, null, ConflictPolicy.SKIP_CONFLICTS, matcher),
                "already gone: nothing to do and nothing lost");
    }

    @Test
    void undoOfACutPutsTheEntityBackUnlessOneIsThere() {
        UUID id = UUID.randomUUID();
        EntityState before = frame(id, 3.5, "apple", 300);
        Step undo = new Step(id, null, before);
        assertEquals(Action.APPLY, EntityHistory.decide(undo, null, ConflictPolicy.SKIP_CONFLICTS, matcher));
        assertEquals(Action.SKIP, EntityHistory.decide(undo, frame(id, 3.5, "apple", 0), ConflictPolicy.SKIP_CONFLICTS,
                matcher), "already back");
        assertEquals(Action.CONFLICT, EntityHistory.decide(undo, frame(id, 3.5, "stick", 0),
                ConflictPolicy.SKIP_CONFLICTS, matcher), "another entity with its UUID");
        assertEquals(Action.APPLY, EntityHistory.decide(undo, frame(id, 3.5, "stick", 0), ConflictPolicy.OVERWRITE,
                matcher));
    }

    @Test
    void stepsFollowTheDirection() {
        UUID removed = UUID.randomUUID(), added = UUID.randomUUID();
        EntityState a = frame(removed, 1.5, "apple", 0), b = frame(added, 9.5, "apple", 0);
        HistoryEntry move = entry(new EditRecord(new BlockBuffer(), new BlockBuffer(),
                List.of(new EntityChange(removed, a, null), new EntityChange(added, null, b))));
        List<Step> undo = EntityHistory.steps(move, false);
        assertEquals(List.of(new Step(removed, null, a), new Step(added, b, null)), undo);
        List<Step> redo = EntityHistory.steps(move, true);
        assertEquals(List.of(new Step(removed, a, null), new Step(added, null, b)), redo);
        assertEquals(a, undo.get(0).where());
        assertEquals(b, undo.get(1).where());
        assertThrows(IllegalArgumentException.class, () -> new Step(removed, null, null));
    }

    /** Undo anyway over a run: per entity the target of the last entry of the run recording it. */
    @Test
    void aRunTakesTheLastTargetOfEachEntity() {
        UUID id = UUID.randomUUID(), other = UUID.randomUUID();
        EntityState first = frame(id, 1.5, "apple", 0), second = frame(id, 1.5, "stick", 0);
        EntityState lone = frame(other, 7.5, "bone", 0);
        // Steps undone in this order: the newer entry (id: first → second) first, then the older (placed first).
        HistoryEntry newer = entry(new EditRecord(new BlockBuffer(), new BlockBuffer(),
                List.of(new EntityChange(id, first, second))));
        HistoryEntry older = entry(new EditRecord(new BlockBuffer(), new BlockBuffer(),
                List.of(new EntityChange(id, null, first), new EntityChange(other, null, lone))));
        List<Step> undo = EntityHistory.reapplySteps(List.of(newer, older), false);
        assertEquals(2, undo.size());
        Step last = undo.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
        assertNull(last.target(), "the older entry's before: no entity");
        assertEquals(first, last.expected());
        List<Step> redo = EntityHistory.reapplySteps(List.of(older, newer), true);
        Step redone = redo.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
        assertEquals(second, redone.target(), "redone in order: the newer entry's after wins");
    }

    @Test
    void theMatcherComparesDataNotBytesOrOrder() {
        UUID id = UUID.randomUUID();
        EntityState a = frame(id, 3.5, "apple", 300);
        NbtCompound reordered = NbtCompound.builder().putString("Item", "apple").putShort("Air", (short) 1)
                .putIntArray("UUID", ints(id)).put("Pos", EntityNbt.doubles(8, 64.5, 0.03))
                .putString("id", "minecraft:item_frame").build();
        EntityState b = new EntityState("minecraft:item_frame", 8, 64.5, 0.03, NbtIo.toBytes(reordered));
        assertTrue(matcher.matches(a, b), "key order, air and position do not matter");
        assertFalse(EntityMatcher.EXACT.matches(a, b));
        EntityState otherType = new EntityState("minecraft:glow_item_frame", 3.5, 64.5, 0.03, a.nbt());
        assertFalse(matcher.matches(a, otherType));
        EntityState junk = new EntityState("minecraft:item_frame", 3.5, 64.5, 0.03, new byte[] {1, 2, 3});
        assertFalse(matcher.matches(a, junk), "data that does not decode counts as changed");
        assertFalse(matcher.matches(junk, a));
    }

    @Test
    void recordsHoldingOnlyEntitiesAreUndoable() {
        UUID id = UUID.randomUUID();
        EditRecord record = new EditRecord(new BlockBuffer(), new BlockBuffer(),
                List.of(new EntityChange(id, null, frame(id, -20.5, "apple", 0))));
        assertFalse(record.isEmpty());
        assertEquals(new Box(new BlockPos(-21, 64, 0), new BlockPos(-21, 64, 0)), record.bounds());
        HistoryEntry entry = entry(record);
        PlayerHistory history = new PlayerHistory(HistoryLimits.DEFAULTS);
        history.push(entry);
        EditProgram undo = HistoryPrograms.undo(entry, ConflictPolicy.SKIP_CONFLICTS);
        assertEquals(0, undo.sectionOrder().length, "no cells to write");
        assertEquals(0, undo.estimatedCells());
        assertEquals(record.bounds(), undo.bounds());
        EditProgram reapply = HistoryPrograms.reapply(List.of(entry), false);
        assertEquals(record.bounds(), reapply.bounds());
        assertTrue(new EditRecord(new BlockBuffer(), new BlockBuffer()).isEmpty());
    }

    /** An armor stand's whole NBT at (x, 64, 0) facing {@code yaw}, small or not, its air timer at {@code air}. */
    static EntityState stand(UUID id, double x, float yaw, boolean small, int air) {
        NbtCompound nbt = NbtCompound.builder().putString("id", "minecraft:armor_stand")
                .put("Pos", EntityNbt.doubles(x, 64, 0)).putIntArray("UUID", ints(id)).putShort("Air", (short) air)
                .put("Rotation", dev.sculptory.core.nbt.NbtList.of(dev.sculptory.core.nbt.NbtTag.FLOAT,
                        List.of(new dev.sculptory.core.nbt.NbtTag.NbtFloat(yaw),
                                new dev.sculptory.core.nbt.NbtTag.NbtFloat(0))))
                .putByte("Small", (byte) (small ? 1 : 0)).build();
        return new EntityState("minecraft:armor_stand", x, 64, 0, NbtIo.toBytes(nbt));
    }

    /**
     * A Tinker move or turn changes only keys the matcher leaves out (where the entity stands and looks): its undo and redo
     * still put the entity back where it was, and one moved again since is a conflict (Undo anyway takes it). A step that
     * also changed a setting is decided as before, where it stands not counting.
     */
    @Test
    void aStepThatOnlyMovedOrTurnedTheEntityIsUndone() {
        UUID id = UUID.randomUUID();
        EntityState before = stand(id, 3.5, 0, false, 300);
        EntityState moved = stand(id, 3.5625, 0, false, 300);
        EntityState turned = stand(id, 3.5, 15, false, 300);
        for (EntityState after : List.of(moved, turned)) {
            Step undo = new Step(id, after, before);
            assertEquals(Action.APPLY, EntityHistory.decide(undo, after, ConflictPolicy.SKIP_CONFLICTS, matcher),
                    "the undo puts it back");
            assertEquals(Action.APPLY, EntityHistory.decide(undo, stand(id, after.x(), yaw(after), false, 12),
                    ConflictPolicy.SKIP_CONFLICTS, matcher), "an air timer that ticked does not matter");
            assertEquals(Action.SKIP, EntityHistory.decide(undo, before, ConflictPolicy.SKIP_CONFLICTS, matcher),
                    "already back");
            EntityState elsewhere = stand(id, 7.5, 90, false, 300);
            assertEquals(Action.CONFLICT, EntityHistory.decide(undo, elsewhere, ConflictPolicy.SKIP_CONFLICTS, matcher),
                    "moved again since: kept");
            assertEquals(Action.APPLY, EntityHistory.decide(undo, elsewhere, ConflictPolicy.OVERWRITE, matcher));
            Step redo = new Step(id, before, after);
            assertEquals(Action.APPLY, EntityHistory.decide(redo, before, ConflictPolicy.SKIP_CONFLICTS, matcher));
            assertEquals(Action.SKIP, EntityHistory.decide(redo, after, ConflictPolicy.SKIP_CONFLICTS, matcher));
        }
        // A setting changed too: the usual rule, where it stands left out.
        EntityState small = stand(id, 3.5625, 0, true, 300);
        Step undo = new Step(id, small, before);
        assertEquals(Action.APPLY, EntityHistory.decide(undo, stand(id, 9, 45, true, 300), ConflictPolicy.SKIP_CONFLICTS,
                matcher), "still the small stand the step left, wherever it stands");
        assertEquals(Action.SKIP, EntityHistory.decide(undo, stand(id, 9, 45, false, 300), ConflictPolicy.SKIP_CONFLICTS,
                matcher));
        assertTrue(matcher.placementAware() == matcher.placementAware(), "made once per matcher");
        assertTrue(EntityMatcher.EXACT.placementAware() == EntityMatcher.EXACT);
    }

    /**
     * A display recomputes its rotation from its matrix each time it loads, so the same display saves slightly different
     * floats after an undo or redo put it back: the matcher takes floats within a tolerance, and nothing else loosely.
     */
    @Test
    void floatsMatchWithinATolerance() {
        UUID id = UUID.randomUUID();
        java.util.function.BiFunction<Float, Double, EntityState> display = (w, x) -> {
            NbtCompound nbt = NbtCompound.builder().putString("id", "minecraft:block_display")
                    .put("Pos", EntityNbt.doubles(x, 64, 0)).putIntArray("UUID", ints(id))
                    .put("transformation", NbtCompound.builder().put("left_rotation",
                            dev.sculptory.core.nbt.NbtList.of(dev.sculptory.core.nbt.NbtTag.FLOAT,
                                    List.of(new dev.sculptory.core.nbt.NbtTag.NbtFloat(0),
                                            new dev.sculptory.core.nbt.NbtTag.NbtFloat(0.3826835f),
                                            new dev.sculptory.core.nbt.NbtTag.NbtFloat(0),
                                            new dev.sculptory.core.nbt.NbtTag.NbtFloat(w)))).build()).build();
            return new EntityState("minecraft:block_display", x, 64, 0, NbtIo.toBytes(nbt));
        };
        EntityState saved = display.apply(0.9238796f, 1.0);
        assertTrue(matcher.matches(saved, display.apply(0.9238795f, 1.0)), "the last bit of a float");
        assertFalse(matcher.matches(saved, display.apply(0.9f, 1.0)), "a real turn");
        assertTrue(matcher.placementAware().matches(saved, display.apply(0.9238795f, 1.0)));
        assertFalse(matcher.placementAware().matches(saved, display.apply(0.9238796f, 1.0000001)),
                "positions compare exactly");
        assertTrue(EntityNbt.nearlyEqual(new dev.sculptory.core.nbt.NbtTag.NbtFloat(Float.NaN),
                new dev.sculptory.core.nbt.NbtTag.NbtFloat(Float.NaN)));
        assertFalse(EntityNbt.nearlyEqual(new dev.sculptory.core.nbt.NbtTag.NbtFloat(1),
                new dev.sculptory.core.nbt.NbtTag.NbtInt(1)), "types compare exactly");
    }

    private static float yaw(EntityState state) {
        try {
            return EntityNbt.rotation(EntityNbt.decode(state.nbt()))[0];
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static HistoryEntry entry(EditRecord record) {
        return new HistoryEntry(UUID.randomUUID(), PLAYER, "minecraft:overworld", "Entities", record, 0L);
    }
}
