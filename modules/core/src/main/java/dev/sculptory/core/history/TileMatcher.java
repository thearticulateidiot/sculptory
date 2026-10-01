package dev.sculptory.core.history;

import dev.sculptory.core.buffer.BlockEntityData;

/**
 * Whether a cell's live block entity still holds what a history entry expects there. Undo and redo treat a cell whose contents changed since the step (a chest filled, a sign edited)
 * like a cell whose state changed: under {@link ConflictPolicy#SKIP_CONFLICTS} it is kept and counted.
 *
 * <p>A game-backed matcher compares in the game's own canonical form (what the world holds after writing the expected
 * data), so key order, defaults the game adds and the mod's own sanitizing never count as a change; {@link #EXACT} is
 * for worlds with no game behind them (tests).
 */
@FunctionalInterface
public interface TileMatcher {
    /** Byte-exact content ({@link BlockEntityData#sameContent}); both absent match. */
    TileMatcher EXACT = (state, live, expected) -> RecordBuilder.sameTile(live, expected);

    /**
     * @param state the cell's state handle (the live state, equal to the one the entry expects)
     * @param live the cell's block entity now, or {@code null} for none
     * @param expected the block entity the entry recorded there, or {@code null}: the state's default (a block placed
     *     without data, or data the writer did not apply)
     * @return true when the live content is what the entry expects
     */
    boolean matches(int state, BlockEntityData live, BlockEntityData expected);

    /**
     * A new section is about to be computed: the checks of the one before (at compute and write time) are over, so a
     * matcher may forget what it remembered for it. The default does nothing.
     */
    default void section(long key) {}
}
