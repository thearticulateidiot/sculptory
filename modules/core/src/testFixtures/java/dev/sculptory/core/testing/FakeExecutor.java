package dev.sculptory.core.testing;

import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.edit.ComputeContext;
import dev.sculptory.core.edit.EditProgram;
import dev.sculptory.core.history.EditRecord;
import dev.sculptory.core.history.RecordBuilder;
import dev.sculptory.core.state.StateSpace;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * Runs an {@link EditProgram} against a {@link FakeWorld} the way the server executor does, section by
 * section: capture the section, compute, then write the result (state and tile) cell by cell, asking
 * {@link EditProgram#mayReplace} against the live cell (state and tile) and skipping columns the {@link Hooks} deny, and
 * record the change.
 */
public final class FakeExecutor {
    /**
     * @param record what the run changed, ready for a history entry
     * @param written cells written
     * @param conflicts conflicts reported through {@link ComputeContext#conflicts}
     * @param sections sections computed
     * @param refused cells {@link EditProgram#mayReplace} refused
     * @param denied cells skipped because {@link Hooks#mayWrite} denies their column
     */
    public record Result(EditRecord record, long written, long conflicts, int sections, long refused, long denied) {}

    /** What a test can change about a run. */
    public interface Hooks {
        Hooks NONE = new Hooks() {};

        /** Whether the job may write column (x, z), as protection and the world border decide on a server. */
        default boolean mayWrite(int x, int z) {
            return true;
        }

        /**
         * Runs after {@code compute(key)} and before its cells are written: a change made here lands between the
         * two, as one made while a server writes a section over several ticks.
         */
        default void afterCompute(long key, FakeWorld world) {}

        /** Runs before section {@code key} is captured and computed (after the previous section was written). */
        default void beforeCompute(long key, FakeWorld world) {}
    }

    private FakeExecutor() {}

    public static Result run(EditProgram program, FakeWorld world) {
        return run(program, world, Integer.MAX_VALUE, Hooks.NONE);
    }

    /** Runs only the first {@code maxSections} sections, like a job cancelled at a section boundary. */
    public static Result run(EditProgram program, FakeWorld world, int maxSections) {
        return run(program, world, maxSections, Hooks.NONE);
    }

    public static Result run(EditProgram program, FakeWorld world, Hooks hooks) {
        return run(program, world, Integer.MAX_VALUE, hooks);
    }

    public static Result run(EditProgram program, FakeWorld world, int maxSections, Hooks hooks) {
        Long2ObjectOpenHashMap<SectionBuffer> sources = new Long2ObjectOpenHashMap<>();
        for (long key : program.sourceSections()) sources.put(key, capture(world, key));
        long[] conflicts = {0};
        ComputeContext ctx = new ComputeContext() {
            @Override
            public StateSpace states() {
                return world.states();
            }

            @Override
            public long seed() {
                return 0x5EEDL;
            }

            @Override
            public SectionBuffer source(long key) {
                return sources.get(key);
            }

            @Override
            public dev.sculptory.core.world.WorldReader world() {
                return world;
            }

            @Override
            public boolean mayWrite(int x, int z) {
                return hooks.mayWrite(x, z);
            }

            @Override
            public void conflicts(int n) {
                if (n <= 0) throw new AssertionError("conflicts(" + n + ")");
                conflicts[0] += n;
            }
        };
        RecordBuilder recorder = new RecordBuilder();
        long written = 0, refused = 0, denied = 0;
        int sections = 0;
        for (long key : program.sectionOrder()) {
            if (sections == maxSections) break;
            hooks.beforeCompute(key, world);
            SectionBuffer before = capture(world, key);
            SectionBuffer out = new SectionBuffer();
            program.compute(key, before, out, ctx);
            hooks.afterCompute(key, world);
            int ox = BlockBuffer.keyX(key) << 4, oy = BlockBuffer.keyY(key) << 4, oz = BlockBuffer.keyZ(key) << 4;
            IntArrayList cells = new IntArrayList();
            out.forEachPresent(cells::add);
            SectionBuffer applied = new SectionBuffer();
            SectionBuffer prior = new SectionBuffer();
            for (int k = 0; k < cells.size(); k++) {
                int i = cells.getInt(k);
                int x = ox + SectionBuffer.localX(i), y = oy + SectionBuffer.localY(i), z = oz + SectionBuffer.localZ(i);
                if (!hooks.mayWrite(x, z)) {
                    denied++;
                    continue;
                }
                int live = world.get(x, y, z);
                if (!program.mayReplace(key, i, live, world.tile(x, y, z), ctx)) {
                    refused++;
                    continue;
                }
                prior.set(i, live);
                prior.setTile(i, world.tile(x, y, z));
                world.set(x, y, z, out.get(i));
                world.setTile(x, y, z, out.tile(i));
                applied.set(i, out.get(i));
                applied.setTile(i, out.tile(i));
                written++;
            }
            // The record holds what each written cell really held right before its write.
            SectionBuffer recordBefore = before.copy();
            applied.forEachPresent(i -> {
                recordBefore.set(i, prior.get(i));
                recordBefore.setTile(i, prior.tile(i));
            });
            recorder.recordSection(key, recordBefore, applied);
            sections++;
        }
        return new Result(recorder.build(), written, conflicts[0], sections, refused, denied);
    }

    private static SectionBuffer capture(FakeWorld world, long key) {
        SectionBuffer section = new SectionBuffer();
        world.copySection(BlockBuffer.keyX(key), BlockBuffer.keyY(key), BlockBuffer.keyZ(key), section);
        return section;
    }
}
