package dev.sculptory.fabric.engine.impl;

import dev.sculptory.core.brush.CellSink;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.mask.BoundMask;
import dev.sculptory.core.mask.EditMask;
import dev.sculptory.core.mask.MaskEntry;
import dev.sculptory.core.mask.MaskRule;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.engine.EditRejected;
import dev.sculptory.protocol.v2.RejectReason;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each player's global mask on the server: what their last {@code SetEditMask} set,
 * bound once to the server's state space. Region ops, strokes, copies and builder mode read it when they are admitted
 * ({@link #current}); the frames of one connection arrive in order on the server thread, so an op sent after a mask
 * change runs under the new mask.
 *
 * <p><b>Fails closed.</b> After a {@code SetEditMask} the server refused (malformed, over the rate limit, an inside
 * rule on a selection the server does not hold), every edit is refused {@code INVALID} "edit mask refused" until a
 * mask is accepted: no edit runs under a mask other than the one the player sees. A new handshake (and a disconnect)
 * resets the player's mask to off.
 *
 * <p>Server thread only (the map is concurrent only so a stray read elsewhere sees a consistent entry).
 */
public final class EditMasks {
    /** The refusal every edit gets while the player's last mask was refused. */
    public static final String REFUSED_DETAIL = "edit mask refused";

    /** A player's mask as the server holds it; {@code bound} is {@code null} after a refusal. */
    private record Entry(EditMask mask, BoundMask bound) {}

    private static final Entry REFUSED = new Entry(null, null);
    private static final Map<UUID, Entry> MASKS = new ConcurrentHashMap<>();

    private EditMasks() {}

    /** Turns a wire region the server holds by reference ({@code Region.Uploaded}) into its cells. */
    @FunctionalInterface
    public interface RegionResolver {
        Region resolve(Region region) throws EditRejected;
    }

    /**
     * Sets {@code owner}'s mask: its uploaded inside regions resolved through {@code regions}, bound to {@code states}.
     * A refusal ({@code EditRejected}) leaves the player's edits refused until a later mask is accepted.
     */
    public static void set(UUID owner, EditMask mask, RegionResolver regions, StateSpace states) throws EditRejected {
        Objects.requireNonNull(mask);
        try {
            EditMask resolved = resolve(mask, regions);
            BoundMask bound = resolved.bind(Objects.requireNonNull(states));
            if (owner != null) MASKS.put(owner, new Entry(resolved, bound));
        } catch (EditRejected e) {
            refuse(owner);
            throw e;
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            refuse(owner);
            throw new EditRejected(RejectReason.INVALID, "the mask cannot be applied: " + e.getMessage());
        }
    }

    /** Marks {@code owner}'s mask refused: their edits are refused until a mask is accepted. */
    public static void refuse(UUID owner) {
        if (owner != null) MASKS.put(owner, REFUSED);
    }

    /** Back to no mask (a new handshake, a disconnect). */
    public static void reset(UUID owner) {
        if (owner != null) MASKS.remove(owner);
    }

    /**
     * The player's bound mask for an edit being admitted: {@link BoundMask#ALL} while it is off.
     *
     * @throws EditRejected {@code INVALID} "edit mask refused" after a refused {@code SetEditMask}
     */
    public static BoundMask current(UUID owner) throws EditRejected {
        Entry entry = owner == null ? null : MASKS.get(owner);
        if (entry == null) return BoundMask.ALL;
        if (entry == REFUSED) throw new EditRejected(RejectReason.INVALID, REFUSED_DETAIL);
        return entry.bound();
    }

    /** Whether the player's last mask was refused (their edits are refused). */
    public static boolean refused(UUID owner) {
        return owner != null && MASKS.get(owner) == REFUSED;
    }

    /** The mask the server holds for {@code owner} ({@link EditMask#NONE} when off or refused), for tests and logs. */
    public static EditMask mask(UUID owner) {
        Entry entry = owner == null ? null : MASKS.get(owner);
        return entry == null || entry == REFUSED ? EditMask.NONE : entry.mask();
    }

    private static EditMask resolve(EditMask mask, RegionResolver regions) throws EditRejected {
        boolean uploaded = false;
        for (MaskEntry entry : mask.entries()) {
            uploaded |= entry.rule() instanceof MaskRule.Inside inside && inside.region() instanceof Region.Uploaded;
        }
        if (!uploaded) return mask;
        List<MaskEntry> entries = new ArrayList<>(mask.entries().size());
        for (MaskEntry entry : mask.entries()) {
            if (entry.rule() instanceof MaskRule.Inside inside && inside.region() instanceof Region.Uploaded) {
                entries.add(new MaskEntry(new MaskRule.Inside(regions.resolve(inside.region())), entry.not()));
            } else {
                entries.add(entry);
            }
        }
        return new EditMask(entries, mask.invertAll());
    }

    // ------------------------------------------------------------------ the world before an edit

    /**
     * A world as it was before an edit that has written some of its cells already: those cells' earlier states (as
     * {@link #remember}ed), every other cell from the live world. For the Shape brush's parts (written over several
     * ticks) and builder mode (vanilla has already changed the world when the mask is checked).
     */
    static final class BeforeView implements WorldReader {
        private final WorldReader live;
        private final Long2IntOpenHashMap before = new Long2IntOpenHashMap();

        BeforeView(WorldReader live) {
            this.live = Objects.requireNonNull(live);
            before.defaultReturnValue(-1);
        }

        /** Notes cell (x, y, z)'s state before the edit, unless one is noted already. */
        void remember(int x, int y, int z, int state) {
            before.putIfAbsent(key(x, y, z), state);
        }

        private static long key(int x, int y, int z) {
            return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (y & 0xFFFL);
        }

        @Override
        public StateSpace states() {
            return live.states();
        }

        @Override
        public int bottomY() {
            return live.bottomY();
        }

        @Override
        public int topYExclusive() {
            return live.topYExclusive();
        }

        @Override
        public boolean isLoaded(int cx, int cz) {
            return live.isLoaded(cx, cz);
        }

        @Override
        public int get(int x, int y, int z) {
            int state = before.isEmpty() ? -1 : before.get(key(x, y, z));
            return state >= 0 ? state : live.get(x, y, z);
        }

        @Override
        public BlockEntityData tile(int x, int y, int z) {
            return live.tile(x, y, z);
        }

        @Override
        public void copySection(int sx, int sy, int sz, SectionBuffer into) {
            live.copySection(sx, sy, sz, into);
            if (before.isEmpty()) return;
            for (int i = 0; i < SectionBuffer.SIZE; i++) {
                int state = before.get(key((sx << 4) + SectionBuffer.localX(i), (sy << 4) + SectionBuffer.localY(i),
                        (sz << 4) + SectionBuffer.localZ(i)));
                if (state >= 0) into.set(i, state);
            }
        }
    }

    /**
     * The Shape brush's parts through the global mask: each cell is tested against the world before the step (the
     * step's earlier parts remembered) and forwarded when accepted, as {@code MaskedKernel} does in one go on the
     * client.
     */
    static final class ShapeMaskSink implements CellSink {
        private final CellSink out;
        private final BoundMask mask;
        private final BeforeView view;

        ShapeMaskSink(CellSink out, BoundMask mask, WorldReader live) {
            this.out = out;
            this.mask = mask;
            this.view = new BeforeView(live);
        }

        @Override
        public void set(int x, int y, int z, int handle) {
            if (accept(x, y, z)) out.set(x, y, z, handle);
        }

        @Override
        public void set(int x, int y, int z, int handle, BlockEntityData tile) {
            if (accept(x, y, z)) out.set(x, y, z, handle, tile);
        }

        private boolean accept(int x, int y, int z) {
            int before = view.get(x, y, z);
            if (!mask.test(x, y, z, before, view)) return false;
            view.remember(x, y, z, before);
            return true;
        }
    }
}
