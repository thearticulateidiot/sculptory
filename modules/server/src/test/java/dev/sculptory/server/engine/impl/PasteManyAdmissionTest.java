package dev.sculptory.server.engine.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.server.engine.EditRejected;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Generate's Stamp at admission, the parts that need no world: the symmetric copies of a multi-paste (free placements
 * too), and the entity work of its placements run as one.
 */
class PasteManyAdmissionTest {
    private static final SourceRef CLIPBOARD = new SourceRef.Clipboard(new UUID(3, 4));

    @Test
    void aMirroredStampHasOneCopyPerImageFreePlacementsIncluded() throws EditRejected {
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 21, 0);
        OpSpec.PasteMany exact = new OpSpec.PasteMany(CLIPBOARD, List.of(
                new OpSpec.PasteMany.Placement(new BlockPos(0, 64, 0), Orientation.IDENTITY),
                new OpSpec.PasteMany.Placement(new BlockPos(4, 64, 0), Orientation.of(Transform.rotation(1)))),
                PasteOptions.DEFAULT, mirror);
        assertEquals(2, EngineEditService.symmetricCopies(exact).size());
        OpSpec.PasteMany free = new OpSpec.PasteMany(CLIPBOARD, List.of(new OpSpec.PasteMany.Placement(
                new BlockPos(0, 64, 0), Orientation.free(Transform.IDENTITY, 1_500, 0, 0))), PasteOptions.DEFAULT, mirror);
        assertEquals(2, EngineEditService.symmetricCopies(free).size(), "free placements have their copies too");
    }

    @Test
    void theEntityWorkOfManyPlacementsRunsEachColumnsWorksInOrder() {
        List<String> log = new ArrayList<>();
        Fake a = new Fake("a", new long[] {1, 2}, log);
        Fake b = new Fake("b", new long[] {2, 3}, log);
        b.stepsPerColumn = 2; // b needs two calls per column (a step's allowance ran out once)
        EntityWork all = EntityJobs.all(List.of(a, b));
        assertArrayEquals(new long[] {1, 2, 3}, all.admissionColumns());
        assertArrayEquals(new long[0], all.beforeColumns());
        long[] columns = all.afterColumns();
        assertArrayEquals(new long[] {1, 2, 3}, columns);
        for (long column : columns) {
            int calls = 0;
            while (!all.after(column, null)) assertTrue(++calls < 10, "column " + column + " never finished");
        }
        assertEquals(List.of("a1", "a2", "b2", "b2", "b3", "b3"), log);
        assertSame(a, EntityJobs.all(List.of(a)), "one work is itself");
        assertFalse(log.isEmpty());
    }

    /** Entity work that logs each call and finishes a column after {@code stepsPerColumn} calls. */
    private static final class Fake implements EntityWork {
        final String name;
        final long[] columns;
        final List<String> log;
        int stepsPerColumn = 1;
        private int calls;

        Fake(String name, long[] columns, List<String> log) {
            this.name = name;
            this.columns = columns;
            this.log = log;
        }

        @Override
        public long[] admissionColumns() {
            return columns;
        }

        @Override
        public long[] beforeColumns() {
            return new long[0];
        }

        @Override
        public <E> boolean before(long column, Context<E> ctx) {
            return true;
        }

        @Override
        public long[] afterColumns() {
            return columns;
        }

        @Override
        public <E> boolean after(long column, Context<E> ctx) {
            log.add(name + column);
            if (++calls < stepsPerColumn) return false;
            calls = 0;
            return true;
        }
    }
}
