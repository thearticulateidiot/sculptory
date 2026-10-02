package dev.sculptory.server.platform;

import dev.sculptory.core.history.EditRecord;
import dev.sculptory.server.engine.impl.RecordSink;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Follows what fluid written by history steps does afterwards ({@link Platform#fluidTrails}): a cell a step wrote
 * fluid (or ice) into is marked as its entry's, and what that fluid changes later (flowing out, turning grass under it
 * to dirt) becomes the entry's trail, taken back with the entry's next step. The platform hooks the game's fluid and
 * block updates; the engine marks cells and takes trails. Server thread only.
 *
 * @param <W> the platform's world type
 */
public interface FluidTrailHook<W> {
    /** The entry a history step's write of cell (x, y, z) belongs to, or {@code null} for none. */
    @FunctionalInterface
    interface CellOwner {
        UUID at(int x, int y, int z);
    }

    /**
     * {@code sink}, also marking the cells it records whose new state holds a fluid with {@code owner} (the entry the
     * job's writes belong to).
     */
    RecordSink marking(RecordSink sink, W world, UUID owner);

    /**
     * {@code sink} for an undo, redo or overwrite of history entries: marks a cell it records with {@code owner}'s entry
     * where the write puts fluid or ice in place of neither. Fluid written over fluid is left unmarked: it is not the
     * entry's.
     */
    RecordSink stepMarking(RecordSink sink, W world, CellOwner owner);

    /**
     * A step of {@code owner} wrote state {@code after} at (x, y, z): marks the cell if it holds a fluid or ice, and
     * says whether it did.
     */
    boolean wrote(W world, int x, int y, int z, int after, UUID owner);

    /**
     * Removes and returns what {@code owner}'s fluid changed in {@code world} since the trail was last taken or folded,
     * as a record, or {@code null} when nothing: the entry's next step takes it. The trail starts afresh.
     */
    EditRecord take(W world, UUID owner);

    /**
     * What {@code owner}'s fluid changed since the trail was last taken or folded, as {@link #take} gives it, to be
     * folded into the entry between steps (its history is unloaded, the server stops). The trail stays, counting what
     * was folded against its cap.
     */
    EditRecord drain(W world, UUID owner);

    /**
     * Right before the game saves chunk column (cx, cz) of {@code world}: hands each entry's part of that column to
     * {@code fold}, which folds it into the entry, journals it ahead of the chunk and answers the entry's player, or
     * {@code null} when it cannot now. A part {@code fold} refused stays for a later save.
     *
     * @return the players whose entries were folded into
     */
    Set<UUID> drainColumn(W world, int cx, int cz, BiFunction<UUID, EditRecord, UUID> fold);

    /** Puts back a trail {@link #take taken} for a step that was then refused. Does nothing for {@code null}. */
    void giveBack(W world, UUID owner, EditRecord taken);

    /** Holds {@code owner}'s fluid still until {@link #thaw}, while one of its steps writes. */
    void freeze(UUID owner);

    void thaw(UUID owner);

    /**
     * Registers what a history holds: entries none of the registered holders reports lose their marks and trails from
     * time to time. A holder answers {@code null} while it cannot tell. Holders are referenced weakly: the caller keeps
     * the supplier.
     */
    void register(Supplier<Set<UUID>> entries);
}
