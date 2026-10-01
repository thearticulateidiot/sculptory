package dev.sculptory.core.edit;

import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;

/** What {@link EditProgram#compute} may read besides the section itself. */
public interface ComputeContext {
    StateSpace states();

    /**
     * The live world the job writes, for a program that must look beyond the section it computes (a scatter commit
     * checks each placement's cells once, see {@link MultiPaste.Replace}), or {@code null} when the runner offers
     * none. Callers check {@link WorldReader#isLoaded} before reading a chunk. The default offers none.
     */
    default WorldReader world() {
        return null;
    }

    /**
     * Whether the job may write world column (x, z): the requester's protection and the world border. A program that
     * decides about cells beyond the section it computes (a scatter commit skips a placement whole) asks it; the
     * runner still guards every write itself. The default allows every column.
     */
    default boolean mayWrite(int x, int z) {
        return true;
    }

    /** A per-job seed for any randomness not already fixed by the op's patterns. */
    long seed();

    /**
     * The pre-write snapshot of a section listed in {@link EditProgram#sourceSections()}, or {@code null}
     * for any other key.
     */
    SectionBuffer source(long key);

    /**
     * Reports {@code n > 0} conflicts: cells {@link EditProgram#compute} skipped because they changed since the edit
     * being undone or redone ({@code ConflictPolicy.SKIP_CONFLICTS}), or scatter placements skipped or cut short
     * because their cells were no longer open. Called from {@code compute} (at most once per section) and from
     * {@link EditProgram#mayReplace}. The edit service sums these into {@code JobFinished.skippedConflicts}.
     */
    default void conflicts(int n) {}
}
