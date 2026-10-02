package dev.sculptory.fabric.world;

import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.history.EditRecord;
import dev.sculptory.core.history.EntityState;
import dev.sculptory.core.history.RecordBuilder;
import dev.sculptory.server.engine.impl.RecordSink;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SpreadableBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the fluid a history step wrote did afterwards, so the entry's next
 * step takes it back too. Server thread only.
 *
 * <p>Edits write fluid with physics off: it lies still until something updates a neighbour (a grass block under it
 * dying to dirt, a block placed beside it). Then vanilla's fluid ticks spread it beyond the edit's cells, and flowing
 * water beside two sources over a solid block becomes a source. The history records only the edit's own cells, so an
 * undo used to leave that water behind.
 *
 * <ul>
 *   <li><b>Marks.</b> Every cell an edit wrote a fluid-holding state (or ice, frozen water) into is marked with its
 *       entry's id ({@link #marking}, {@link #wrote}); so is every cell its fluid spread to or froze in, and every cell
 *       an undo, redo or Undo anyway of the entry put fluid or ice into where there was neither ({@link #stepMarking}: a
 *       drain's water an undo puts back; fluid restored over fluid, such as a player's stream, stays unmarked). A change
 *       made any other way clears the cell's mark (a player took the water away), so fluid a player pours there later
 *       is theirs.</li>
 *   <li><b>Trails.</b> These ticks are attributed to an entry: a scheduled fluid tick at a cell it marks; one at unmarked
 *       fluid its own attributed tick updated (woke) in the last {@value #WAKE_TICKS} ticks; one at unmarked flowing
 *       fluid with its fluid beside or above (a player's stream its water raises); a random tick of a grass block under
 *       its fluid (the grass dies to dirt); a random tick of its lava (fire); the ice-and-snow tick of a column whose
 *       top is its water (a cold biome freezes it); and a random tick of its ice (light melts it). Every block change
 *       during such a tick, its own and those its block updates cause (water breaking a torch, lava turned to
 *       obsidian), is recorded into the entry's trail, first state and last state per cell, block entities
 *       included.</li>
 *   <li><b>Folding.</b> The history takes the trail ({@link #take}) when the entry's next undo or redo starts and folds
 *       it into the entry's record ({@code TrailFold}); a step that is refused gives it back ({@link #giveBack}).
 *       Between steps, what the trail holds in a chunk column is folded into the entry (and journaled) right before the
 *       game saves that column ({@link #drainColumn}), and all of it when the entry's history is unloaded or the server
 *       stops ({@link #drain}): the saved history then holds what the fluid did as far as the saved world shows it. A
 *       part that cannot be folded at a save (its player's step in flight, its history not loaded) waits in memory and
 *       the chunk is flagged to be saved again ({@link #chunkSaved}).</li>
 *   <li><b>Holding still.</b> While a step of the entry writes (the edit's own job or open stroke, an undo, a redo),
 *       the entry is {@link #freeze frozen}: fluid ticks attributed to it are put off until it is done (the fluid
 *       mixin schedules them again) and random ticks attributed to it are skipped, so its fluid never flows into a
 *       cell the step has yet to write, or past what an undo restores.</li>
 *   <li><b>Saved with the chunks.</b> A chunk column's marks are written into the chunk's own data when the game saves it
 *       ({@link #chunkSaved}, under {@value #NBT_KEY}) and read back when it loads, before it ticks ({@link #chunkLoaded}),
 *       so after a restart (or a crash, as far as the chunk was saved) the entries' fluid is followed again. While the
 *       server runs, memory is what counts: a column's saved marks are read only if this run has not seen it yet.</li>
 * </ul>
 * Trails and wakes are in memory only (a trail is folded into its entry when the game saves its chunks). Entries no
 * history holds any more are forgotten every {@value #SWEEP_TICKS} ticks ({@link #register}).
 */
public final class FluidTrails {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    /** How often entries no history holds any more lose their marks and trails, in server ticks. */
    public static final int SWEEP_TICKS = 1200;
    /**
     * A trail larger than this (what it holds plus what was folded into its entry since the entry's last step) records
     * nothing more and its entry stops being followed: its undo takes back what was recorded up to then, not what the
     * fluid does afterwards.
     */
    public static final long MAX_TRAIL_BYTES = 32L << 20;
    /** Trails check their size every this many recorded cells. */
    private static final int SIZE_CHECK_CELLS = 4096;
    /** How long fluid an attributed tick woke stays that entry's when its own tick comes, in ticks. */
    private static final int WAKE_TICKS = 200;
    /**
     * The key of a chunk's saved marks in its data: {@code {version: 1, marks: [{entry: UUID, y: section y, bits: 64
     * longs}]}}, one element per entry and section holding marks. Vanilla ignores it; without the mod it is dropped at
     * the chunk's next save.
     */
    public static final String NBT_KEY = "sculptory:fluid_marks";
    /** The key in chunks saved before the rename: read when {@link #NBT_KEY} is absent, never written. */
    public static final String LEGACY_NBT_KEY = "buildersuite:fluid_marks";
    private static final int NBT_VERSION = 1;
    private static final int SECTION_LONGS = SectionBuffer.SIZE / 64;

    private static volatile FluidTrails active;

    private final Thread thread;
    /** Caps lowered for single entries (tests of what happens past the cap); their size is checked every cell. */
    private final Map<UUID, Long> trailCaps = new HashMap<>();
    private boolean captureFailureLogged;
    /** The kinds of chunk-data problem met so far (each logged once): {@link #chunkDataProblem}. */
    private final Set<String> chunkDataProblems = new LinkedHashSet<>();
    private final Map<ServerWorld, WorldTrails> worlds = new IdentityHashMap<>();
    private final Set<UUID> frozen = new HashSet<>();
    private final List<WeakReference<Supplier<Set<UUID>>>> holders = new ArrayList<>();
    /** The tick being recorded, or {@code null}. */
    private Recording recording;
    /** Sections holding marks, over all worlds: the block-change hook does nothing while there are none. */
    private int markedSections;
    private int ticks;

    public FluidTrails(Thread serverThread) {
        this.thread = Objects.requireNonNull(serverThread);
    }

    /** Makes this the instance the world hooks report to (the running server's). */
    public void install() {
        active = this;
    }

    /** Stops the world hooks reporting to this instance. */
    public void close() {
        if (active == this) active = null;
    }

    // ============================================================================================ hooks (mixins)

    /** {@code World.setBlockState}, before it changes anything: records the cell, or clears its mark. */
    public static void beforeSetBlockState(World world, BlockPos pos, BlockState state) {
        FluidTrails trails = active;
        if (trails == null || world.isClient || Thread.currentThread() != trails.thread) return;
        if (world instanceof ServerWorld server) trails.changing(server, pos, state);
    }

    /**
     * A scheduled fluid tick at {@code pos} is about to run: {@code null} to run it as usual, {@link Recording#SKIPPED}
     * to put it off (its entry's step is writing: the caller schedules it again), or a recording the caller closes
     * after the tick.
     */
    public static Recording fluidTick(ServerWorld world, BlockPos pos) {
        FluidTrails trails = active;
        if (trails == null || trails.recording != null || Thread.currentThread() != trails.thread) return null;
        WorldTrails w = trails.worlds.get(world);
        if (w == null || (w.sections.isEmpty() && w.woken.isEmpty())) return null;
        UUID owner = w.ownerAt(pos.getX(), pos.getY(), pos.getZ());
        if (owner == null) owner = w.wokenBy(pos.asLong(), world.getTime());
        if (owner == null && !world.getFluidState(pos).isStill()) owner = flowingBeside(w, world, pos);
        if (owner == null) return null;
        if (trails.frozen.contains(owner)) return Recording.SKIPPED;
        return trails.start(world, owner);
    }

    /**
     * The entry whose fluid lies beside or above {@code pos}: a flowing cell's level follows its neighbours', so an
     * entry's water beside a player's stream raises it in the stream's own ticks.
     */
    private static UUID flowingBeside(WorldTrails w, ServerWorld world, BlockPos pos) {
        for (Direction direction : FEEDING) {
            BlockPos next = pos.offset(direction);
            UUID owner = w.ownerAt(next.getX(), next.getY(), next.getZ());
            if (owner != null && !world.getFluidState(next).isEmpty()) return owner;
        }
        return null;
    }

    /** The sides fluid flows into a cell from (it never flows up). */
    private static final Direction[] FEEDING = {Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST,
            Direction.EAST};

    /**
     * A random tick of {@code state} at {@code pos} is about to run: a grass block (any {@link SpreadableBlock}) under
     * a full block of marked fluid (a source or falling fluid) dies to dirt in it, and marked ice (an entry's water
     * that froze) melts in enough light, so they are recorded; {@code null} for every other tick;
     * {@link Recording#SKIPPED} (skip the tick) while that entry is frozen.
     */
    public static Recording blockRandomTick(ServerWorld world, BlockPos pos, BlockState state) {
        FluidTrails trails = active;
        if (trails == null || trails.markedSections == 0 || trails.recording != null) return null;
        if (Thread.currentThread() != trails.thread) return null;
        if (state.isOf(Blocks.ICE)) return trails.startUnlessFrozen(world, trails.ownerAt(world, pos));
        if (!(state.getBlock() instanceof SpreadableBlock)) return null;
        BlockPos above = pos.up();
        // Only a full fluid block kills grass; under flowing water it lives on and may spread, which is not the fluid's.
        if (world.getFluidState(above).getLevel() != 8) return null;
        return trails.startUnlessFrozen(world, trails.ownerAt(world, above));
    }

    /**
     * The world's ice-and-snow tick of column {@code pos} is about to run ({@code ServerWorld.tickIceAndSnow}: in a
     * cold biome the water at the top of the column freezes, and snow falls in the rain): recorded for the entry whose
     * fluid is that top cell, {@link Recording#SKIPPED} (skip the tick) while that entry is frozen, else {@code null}.
     */
    public static Recording iceAndSnowTick(ServerWorld world, BlockPos pos) {
        FluidTrails trails = active;
        if (trails == null || trails.markedSections == 0 || trails.recording != null) return null;
        if (Thread.currentThread() != trails.thread) return null;
        // The cell vanilla freezes: the one below the column's top (fluids count as motion blocking).
        BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING, pos).down();
        if (world.getFluidState(top).isEmpty()) return null;
        return trails.startUnlessFrozen(world, trails.ownerAt(world, top));
    }

    /**
     * A random tick of the fluid at {@code pos} (lava setting fire) is about to run: recorded when it is marked,
     * {@link Recording#SKIPPED} (skip the tick) while its entry is frozen.
     */
    public static Recording fluidRandomTick(ServerWorld world, BlockPos pos) {
        FluidTrails trails = active;
        if (trails == null || trails.markedSections == 0 || trails.recording != null) return null;
        if (Thread.currentThread() != trails.thread) return null;
        return trails.startUnlessFrozen(world, trails.ownerAt(world, pos));
    }

    /**
     * The game serialized chunk column {@code pos} of {@code world} ({@code chunk}, when known) into {@code chunkData}
     * to save it (the chunk-save hook has already folded the column's trails into their entries where it could): its
     * marks go in under {@value #NBT_KEY}. A trail part the hook could not fold (its player's undo, redo or overwrite
     * in flight, or its history not loaded) is still in memory while the chunk now on disk holds what that fluid did:
     * the chunk is flagged to be saved again, so the next incremental save folds the part once it can (else a chunk
     * that changed no more would never be saved again, and a crash would leave fluid changes the journal lacks). Never
     * throws; off the server thread (a mod saving chunks elsewhere) nothing is added, logged once.
     */
    public static void chunkSaved(ServerWorld world, ChunkPos pos, NbtCompound chunkData, Chunk chunk) {
        FluidTrails trails = active;
        if (trails == null || world == null || pos == null || chunkData == null) return;
        try {
            if (Thread.currentThread() != trails.thread) {
                trails.chunkDataProblem("save-thread", "a chunk was saved off the server thread; the water there is "
                        + "not followed after a restart", null);
                return;
            }
            NbtCompound marks = trails.saveMarks(world, pos.x, pos.z);
            if (marks != null) chunkData.put(NBT_KEY, marks);
            if (chunk != null && trails.holdsUnfoldedPart(world, pos.x, pos.z)) chunk.setNeedsSaving(true);
        } catch (RuntimeException e) {
            trails.chunkDataProblem("save", "the fluid marks of chunk " + pos + " could not be saved", e);
        }
    }

    /** Whether a trail still holds a part for chunk column (cx, cz) of {@code world} (the save hook did not fold it). */
    public boolean holdsUnfoldedPart(ServerWorld world, int cx, int cz) {
        WorldTrails w = worlds.get(world);
        if (w == null) return false;
        long column = ChunkPos.toLong(cx, cz);
        for (Trail trail : w.trails.values()) {
            RecordBuilder part = trail.columns.get(column);
            if (part != null && !part.build().isEmpty()) return true;
        }
        return false;
    }

    /**
     * The game read chunk column {@code pos} of {@code world} from {@code chunkData} (before it ticks): its saved marks
     * are restored, unless this run has seen the column already (then memory holds its marks). Never throws.
     */
    public static void chunkLoaded(ServerWorld world, ChunkPos pos, NbtCompound chunkData) {
        FluidTrails trails = active;
        if (trails == null || world == null || pos == null || chunkData == null) return;
        String key = chunkData.contains(NBT_KEY, NbtElement.COMPOUND_TYPE) ? NBT_KEY
                : chunkData.contains(LEGACY_NBT_KEY, NbtElement.COMPOUND_TYPE) ? LEGACY_NBT_KEY : null;
        if (key == null) return;
        try {
            if (Thread.currentThread() != trails.thread) {
                trails.chunkDataProblem("load-thread", "a chunk was loaded off the server thread; its saved water is "
                        + "not followed", null);
                return;
            }
            trails.loadMarks(world, pos.x, pos.z, chunkData.getCompound(key));
        } catch (RuntimeException e) {
            trails.chunkDataProblem("load", "the fluid marks of chunk " + pos + " could not be read", e);
        }
    }

    /** Logs a chunk-data problem once per {@code kind} (the first chunk's message; later ones are counted only). */
    private void chunkDataProblem(String kind, String message, Throwable cause) {
        if (!chunkDataProblems.add(kind)) return;
        LOG.warn("Sculptory: {}", message, cause);
    }

    /** The kinds of chunk-data problem met so far, in order (tests): each was logged once. */
    public Set<String> chunkDataProblems() {
        return Set.copyOf(chunkDataProblems);
    }

    private Recording startUnlessFrozen(ServerWorld world, UUID owner) {
        if (owner == null) return null;
        if (frozen.contains(owner)) return Recording.SKIPPED;
        return start(world, owner);
    }

    /** One attributed tick being recorded: every cell changed during it, with the state and tile it had first. */
    public static final class Recording implements AutoCloseable {
        /** The tick belongs to a frozen entry: a fluid tick is put off, a random tick skipped. */
        public static final Recording SKIPPED = new Recording(null, null, null, null);

        private final FluidTrails trails;
        private final ServerWorld world;
        private final WorldTrails state;
        private final UUID owner;
        private final Long2ObjectLinkedOpenHashMap<Before> touched = new Long2ObjectLinkedOpenHashMap<>();

        private Recording(FluidTrails trails, ServerWorld world, WorldTrails state, UUID owner) {
            this.trails = trails;
            this.world = world;
            this.state = state;
            this.owner = owner;
        }

        /** Ends the tick: its changes go into the owner's trail, and the cells it left holding fluid are marked. */
        @Override
        public void close() {
            if (trails == null) return;
            trails.finish(this);
        }
    }

    private record Before(int state, BlockEntityData tile) {}

    private record Cause(UUID owner, long until) {}

    // ============================================================================================ steps

    /**
     * {@code sink}, also marking the cells it records whose new state holds a fluid with {@code owner} (the entry the
     * job's writes belong to).
     */
    public RecordSink marking(RecordSink sink, ServerWorld world, UUID owner) {
        Objects.requireNonNull(sink);
        Objects.requireNonNull(world);
        Objects.requireNonNull(owner);
        return new RecordSink() {
            @Override
            public void record(int x, int y, int z, int before, BlockEntityData beforeTile, int after,
                               BlockEntityData afterTile) {
                sink.record(x, y, z, before, beforeTile, after, afterTile);
                wrote(world, x, y, z, after, owner);
            }

            @Override
            public void sectionFinished(long key) {
                sink.sectionFinished(key);
            }

            @Override
            public void entity(UUID id, EntityState before, EntityState after) {
                sink.entity(id, before, after);
            }

            @Override
            public void entitiesFinished() {
                sink.entitiesFinished();
            }
        };
    }

    /**
     * A step of {@code owner} wrote state {@code after} at (x, y, z): marks the cell if it holds a fluid or ice, and says
     * whether it did. (The write went through {@code World.setBlockState}, whose hook already cleared the cell's old
     * mark.)
     */
    public boolean wrote(ServerWorld world, int x, int y, int z, int after, UUID owner) {
        if (!followed(after)) return false;
        mark(worlds.computeIfAbsent(world, k -> new WorldTrails()), x, y, z, owner);
        return true;
    }

    /** The entry a history step's write of cell (x, y, z) belongs to, or {@code null} for none. */
    @FunctionalInterface
    public interface CellOwner {
        UUID at(int x, int y, int z);
    }

    /**
     * {@code sink} for an undo, redo or overwrite of history entries: marks a cell it records with {@code owner}'s entry
     * where the write puts fluid or ice in place of neither (a drain's water an undo puts back, a Fill's water, or the
     * ice it froze into, a redo puts back). Fluid written over fluid (a player's stream restored to its own level) is
     * left unmarked: it is not the entry's.
     */
    public RecordSink stepMarking(RecordSink sink, ServerWorld world, CellOwner owner) {
        Objects.requireNonNull(sink);
        Objects.requireNonNull(world);
        Objects.requireNonNull(owner);
        return new RecordSink() {
            @Override
            public void record(int x, int y, int z, int before, BlockEntityData beforeTile, int after,
                               BlockEntityData afterTile) {
                sink.record(x, y, z, before, beforeTile, after, afterTile);
                if (followed(before) || !followed(after)) return;
                UUID entry = owner.at(x, y, z);
                if (entry != null) wrote(world, x, y, z, after, entry);
            }

            @Override
            public void sectionFinished(long key) {
                sink.sectionFinished(key);
            }

            @Override
            public void entity(UUID id, EntityState before, EntityState after) {
                sink.entity(id, before, after);
            }

            @Override
            public void entitiesFinished() {
                sink.entitiesFinished();
            }
        };
    }

    private static boolean followed(int handle) {
        return followed(Block.getStateFromRawId(handle));
    }

    /**
     * Whether an entry's mark stays on a cell holding {@code state}: fluid, or ice (the water froze; light melts it back
     * into water).
     */
    private static boolean followed(BlockState state) {
        return !state.getFluidState().isEmpty() || state.isOf(Blocks.ICE);
    }

    /**
     * Removes and returns what {@code owner}'s fluid changed in {@code world} since the trail was last taken or folded,
     * as a record (first state before, last state after; unchanged cells dropped), or {@code null} when nothing: the
     * entry's next step takes it. The trail starts afresh (its cap counts from here).
     */
    public EditRecord take(ServerWorld world, UUID owner) {
        WorldTrails w = worlds.get(world);
        if (w == null) return null;
        Trail trail = w.trails.remove(owner);
        if (trail == null) {
            w.taken.remove(owner);
            return null;
        }
        // What the cap counted, kept in case the step is refused and the trail given back.
        w.taken.put(owner, new long[] {trail.folded, trail.full ? 1 : 0});
        return trail.buildAll();
    }

    /**
     * Removes and returns what {@code owner}'s fluid changed in {@code world} since the trail was last taken or folded,
     * as {@link #take} does, to be folded into the entry between steps (its history is unloaded, the server stops). The
     * trail stays, counting what was folded against its cap.
     */
    public EditRecord drain(ServerWorld world, UUID owner) {
        WorldTrails w = worlds.get(world);
        Trail trail = w == null ? null : w.trails.get(owner);
        if (trail == null) return null;
        EditRecord record = trail.buildAll();
        trail.drained(record);
        trail.columns.clear();
        return record;
    }

    /**
     * Right before the game saves chunk column (cx, cz) of {@code world}: hands each entry's part of that column (what
     * its fluid changed there since it was last taken or folded; entries with nothing there are left out) to
     * {@code fold}, which folds it into the entry and journals it ahead of the chunk and answers the entry's player, or
     * {@code null} when it cannot now (the player's step in flight, the history not loaded). A part that was folded
     * leaves the trail, which counts it against its cap; a part {@code fold} refused, or failed on (the exception goes
     * on after the other parts), stays for a later save ({@link #chunkSaved} flags the chunk for one).
     *
     * @return the players whose entries were folded into
     */
    public Set<UUID> drainColumn(ServerWorld world, int cx, int cz, BiFunction<UUID, EditRecord, UUID> fold) {
        WorldTrails w = worlds.get(world);
        if (w == null || w.trails.isEmpty()) return Set.of();
        long column = ChunkPos.toLong(cx, cz);
        Set<UUID> players = new LinkedHashSet<>();
        RuntimeException failure = null;
        for (Map.Entry<UUID, Trail> e : w.trails.entrySet()) {
            Trail trail = e.getValue();
            RecordBuilder held = trail.columns.get(column);
            if (held == null) continue;
            EditRecord part = held.build();
            if (part.isEmpty()) {
                trail.columns.remove(column);
                continue;
            }
            UUID player;
            try {
                player = fold.apply(e.getKey(), part);
            } catch (RuntimeException ex) {
                if (failure == null) failure = ex; else failure.addSuppressed(ex);
                continue;
            }
            if (player == null) continue;
            trail.columns.remove(column);
            trail.drained(part);
            players.add(player);
        }
        if (failure != null) throw failure;
        return players;
    }

    /**
     * Puts back a trail {@link #take taken} for a step that was then refused: its cells go first, then whatever the
     * fluid did since, so each cell keeps its first state and its last, and the cap goes on counting what was folded
     * into the entry before the take. Does nothing for {@code null}.
     */
    public void giveBack(ServerWorld world, UUID owner, EditRecord taken) {
        if (taken == null || taken.before().isEmpty()) return;
        WorldTrails w = worlds.computeIfAbsent(world, k -> new WorldTrails());
        Trail since = w.trails.remove(owner);
        long[] cap = w.taken.remove(owner);
        Trail trail = new Trail();
        trail.addCells(taken);
        if (cap != null) {
            trail.folded = cap[0];
            trail.full = cap[1] != 0;
        }
        if (since != null) {
            // What the fluid did since the take, and what a chunk save folded of it meanwhile.
            EditRecord later = since.buildAll();
            if (later != null) trail.addCells(later);
            trail.folded += since.folded;
            trail.full |= since.full;
        }
        w.trails.put(owner, trail);
    }

    /**
     * Holds {@code owner}'s fluid still until {@link #thaw}: while one of its steps writes (its edit's job or open
     * stroke, an undo or redo of it), fluid ticks attributed to it are put off and random ticks skipped.
     */
    public void freeze(UUID owner) {
        frozen.add(Objects.requireNonNull(owner));
    }

    public void thaw(UUID owner) {
        frozen.remove(owner);
    }

    /** Whether {@code owner} is {@link #freeze frozen} (tests). */
    public boolean isFrozen(UUID owner) {
        return frozen.contains(owner);
    }

    /**
     * Registers what a history holds: every {@value #SWEEP_TICKS} ticks, entries none of the registered holders reports
     * lose their marks and trails, and are thawed if a step failed to. A holder answers {@code null} while it cannot
     * tell (a saved history still loading): then nothing is forgotten. Holders are referenced weakly: the caller keeps
     * the supplier.
     */
    public void register(Supplier<Set<UUID>> entries) {
        holders.add(new WeakReference<>(Objects.requireNonNull(entries)));
    }

    /** Lowers the trail cap of {@code owner} alone to {@code maxBytes}, checked at every cell (tests). */
    public void capTrail(UUID owner, long maxBytes) {
        trailCaps.put(Objects.requireNonNull(owner), maxBytes);
    }

    /** Per server tick: the periodic sweep. */
    public void tick() {
        if (++ticks < SWEEP_TICKS) return;
        ticks = 0;
        sweep();
    }

    /**
     * Forgets every entry no registered holder reports (nothing while no holder is registered), and wakes that have
     * expired.
     */
    public void sweep() {
        for (Map.Entry<ServerWorld, WorldTrails> e : worlds.entrySet()) {
            long now = e.getKey().getTime();
            e.getValue().woken.values().removeIf(cause -> cause.until() < now);
        }
        Set<UUID> live = new HashSet<>();
        boolean any = false;
        for (int i = holders.size() - 1; i >= 0; i--) {
            Supplier<Set<UUID>> holder = holders.get(i).get();
            if (holder == null) {
                holders.remove(i);
                continue;
            }
            Set<UUID> held = holder.get();
            if (held == null) return; // a history is still loading: its entries are unknown
            any = true;
            live.addAll(held);
        }
        if (!any) return;
        frozen.retainAll(live);
        trailCaps.keySet().retainAll(live);
        for (Map.Entry<ServerWorld, WorldTrails> e : worlds.entrySet()) {
            WorldTrails w = e.getValue();
            for (UUID owner : new ArrayList<>(w.sectionsOf.keySet())) {
                if (!live.contains(owner) && !frozen.contains(owner)) forgetMarks(e.getKey(), w, owner);
            }
            w.trails.keySet().removeIf(owner -> !live.contains(owner) && !frozen.contains(owner));
        }
        // A world's seen columns stay with it: their saved marks must not be read again in this run.
        worlds.values().removeIf(w -> w.sections.isEmpty() && w.trails.isEmpty() && w.woken.isEmpty()
                && w.seen.isEmpty());
    }

    /**
     * Forgets every mark, trail, wake and hold, and which chunk columns were seen, as a server starting again has none
     * until its chunks load ({@link #chunkLoaded}): tests of what survives a restart.
     */
    public void restart() {
        worlds.clear();
        frozen.clear();
        trailCaps.clear();
        recording = null;
        markedSections = 0;
        ticks = 0;
    }

    /** Whether (x, y, z) is marked with {@code owner} (tests). */
    public boolean markedBy(ServerWorld world, BlockPos pos, UUID owner) {
        return owner.equals(ownerAt(world, pos));
    }

    /** The number of cells {@code owner}'s trail holds in {@code world}, not yet taken or folded (tests); 0 without one. */
    public long trailCells(ServerWorld world, UUID owner) {
        WorldTrails w = worlds.get(world);
        Trail trail = w == null ? null : w.trails.get(owner);
        EditRecord record = trail == null ? null : trail.buildAll();
        return record == null ? 0 : record.before().cellCount();
    }

    // ============================================================================================ internals

    private void changing(ServerWorld world, BlockPos pos, BlockState state) {
        Recording r = recording;
        if (r != null) {
            if (world != r.world) return;
            long key = pos.asLong();
            if (r.touched.containsKey(key)) return;
            BlockState old = world.getBlockState(pos);
            if (old == state) return;
            r.touched.put(key, new Before(Block.getRawIdFromState(old), old.hasBlockEntity() ? capture(world, pos) : null));
            return;
        }
        if (markedSections == 0) return;
        WorldTrails w = worlds.get(world);
        if (w == null || w.ownerAt(pos.getX(), pos.getY(), pos.getZ()) == null) return;
        if (world.getBlockState(pos) == state) return;
        unmark(w, pos.getX(), pos.getY(), pos.getZ());
    }

    private UUID ownerAt(ServerWorld world, BlockPos pos) {
        WorldTrails w = worlds.get(world);
        return w == null ? null : w.ownerAt(pos.getX(), pos.getY(), pos.getZ());
    }

    private Recording start(ServerWorld world, UUID owner) {
        Recording r = new Recording(this, world, worlds.get(world), owner);
        recording = r;
        return r;
    }

    private void finish(Recording r) {
        if (recording == r) recording = null;
        if (r.touched.isEmpty()) return;
        WorldTrails w = r.state;
        Trail trail = w.trails.get(r.owner);
        if (trail == null) {
            trail = new Trail();
            w.trails.put(r.owner, trail);
        }
        long wakeUntil = r.world.getTime() + WAKE_TICKS;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        BlockPos.Mutable next = new BlockPos.Mutable();
        for (Long2ObjectMap.Entry<Before> entry : r.touched.long2ObjectEntrySet()) {
            pos.set(entry.getLongKey());
            Before before = entry.getValue();
            BlockState now = r.world.getBlockState(pos);
            int after = Block.getRawIdFromState(now);
            BlockEntityData tile = now.hasBlockEntity() ? capture(r.world, pos) : null;
            if (after == before.state() && sameTile(before.tile(), tile)) continue;
            if (trail.full) {
                unmark(w, pos.getX(), pos.getY(), pos.getZ());
                continue;
            }
            trail.column(pos.getX(), pos.getZ()).record(pos.getX(), pos.getY(), pos.getZ(), before.state(),
                    before.tile(), after, tile);
            if (!followed(now)) {
                unmark(w, pos.getX(), pos.getY(), pos.getZ());
            } else {
                mark(w, pos.getX(), pos.getY(), pos.getZ(), r.owner);
            }
            // The change updated its neighbours: fluid there that was lying still may tick now, on this entry's account.
            for (Direction direction : Direction.values()) {
                next.set(pos, direction);
                if (r.world.getFluidState(next).isEmpty()) continue;
                if (w.ownerAt(next.getX(), next.getY(), next.getZ()) != null) continue;
                w.woken.put(next.asLong(), new Cause(r.owner, wakeUntil));
            }
            Long cap = trailCaps.get(r.owner);
            if (++trail.sinceCheck >= (cap == null ? SIZE_CHECK_CELLS : 1)) {
                trail.sinceCheck = 0;
                long maxBytes = cap == null ? MAX_TRAIL_BYTES : cap;
                if (trail.estimatedBytes() > maxBytes) {
                    trail.full = true;
                    forgetMarks(r.world, w, r.owner);
                    LOG.warn("Sculptory: the fluid of history entry {} changed more than {} KiB of blocks; what it "
                            + "does from now on is not undone with it", r.owner, maxBytes >> 10);
                }
            }
        }
    }

    /**
     * The block entity at {@code pos}, captured, or {@code null}. Runs inside vanilla's ticks: a block entity that fails
     * to save (a broken mod) is logged once and taken as none, never thrown into the world tick.
     */
    private BlockEntityData capture(ServerWorld world, BlockPos pos) {
        try {
            BlockEntity entity = world.getWorldChunk(pos).getBlockEntity(pos, WorldChunk.CreationType.CHECK);
            return entity == null || entity.isRemoved() ? null : FabricTile.capture(entity, world.getRegistryManager());
        } catch (RuntimeException e) {
            if (!captureFailureLogged) {
                captureFailureLogged = true;
                LOG.warn("Sculptory: a block entity at {} could not be saved for a fluid trail; it is taken as none",
                        pos.toShortString(), e);
            }
            return null;
        }
    }

    private static boolean sameTile(BlockEntityData a, BlockEntityData b) {
        if (a == null || b == null) return a == b;
        return a.sameContent(b);
    }

    private void mark(WorldTrails w, int x, int y, int z, UUID owner) {
        long key = BlockBuffer.keyOfBlock(x, y, z);
        SectionMarks section = w.sections.get(key);
        if (section == null) {
            section = new SectionMarks();
            w.sections.put(key, section);
            markedSections++;
            // Memory holds this column's marks from now on: what its chunk data says is older.
            w.seen.add(ChunkPos.toLong(x >> 4, z >> 4));
        }
        int i = SectionBuffer.index(x & 15, y & 15, z & 15);
        UUID dropped = section.clear(i);
        if (dropped != null && !section.holds(dropped)) leftSection(w, dropped, key);
        if (section.set(i, owner)) w.sectionsOf.computeIfAbsent(owner, k -> new LongOpenHashSet()).add(key);
    }

    /** {@code owner} holds no mark in section {@code key} any more. */
    private static void leftSection(WorldTrails w, UUID owner, long key) {
        LongOpenHashSet keys = w.sectionsOf.get(owner);
        if (keys == null) return;
        keys.remove(key);
        if (keys.isEmpty()) w.sectionsOf.remove(owner);
    }

    private void unmark(WorldTrails w, int x, int y, int z) {
        long key = BlockBuffer.keyOfBlock(x, y, z);
        SectionMarks section = w.sections.get(key);
        if (section == null) return;
        UUID dropped = section.clear(SectionBuffer.index(x & 15, y & 15, z & 15));
        if (dropped == null) return;
        if (!section.holds(dropped)) leftSection(w, dropped, key);
        if (section.isEmpty()) {
            w.sections.remove(key);
            markedSections--;
        }
    }

    /**
     * Drops every mark of {@code owner} in {@code world}. Their loaded chunks are due to be saved again, so the marks
     * saved with them go too (the game saves only chunks that changed; these did not).
     */
    private void forgetMarks(ServerWorld world, WorldTrails w, UUID owner) {
        LongOpenHashSet keys = w.sectionsOf.remove(owner);
        if (keys == null) return;
        LongOpenHashSet columns = new LongOpenHashSet();
        for (long key : keys) {
            SectionMarks section = w.sections.get(key);
            if (section == null) continue;
            section.drop(owner);
            if (section.isEmpty()) {
                w.sections.remove(key);
                markedSections--;
            }
            columns.add(ChunkPos.toLong(BlockBuffer.keyX(key), BlockBuffer.keyZ(key)));
        }
        for (long column : columns) {
            WorldChunk chunk = world.getChunkManager().getWorldChunk(ChunkPos.getPackedX(column),
                    ChunkPos.getPackedZ(column));
            if (chunk != null) chunk.setNeedsSaving(true);
        }
    }

    /**
     * Chunk column (cx, cz)'s marks as saved chunk data ({@value #NBT_KEY}), or {@code null} when it holds none. The
     * sections of the world's build height are looked up (a few map lookups a chunk save; nothing without marks).
     */
    private NbtCompound saveMarks(ServerWorld world, int cx, int cz) {
        WorldTrails w = worlds.get(world);
        if (w == null || w.sections.isEmpty()) return null;
        NbtList list = new NbtList();
        for (int sy = world.getBottomSectionCoord(); sy < world.getTopSectionCoord(); sy++) {
            SectionMarks section = w.sections.get(BlockBuffer.key(cx, sy, cz));
            if (section == null) continue;
            for (int k = 0; k < section.size; k++) {
                NbtCompound element = new NbtCompound();
                element.putUuid("entry", section.owners[k]);
                element.putInt("y", sy);
                element.putLongArray("bits", section.bits[k].clone());
                list.add(element);
            }
        }
        if (list.isEmpty()) return null;
        NbtCompound marks = new NbtCompound();
        marks.putInt("version", NBT_VERSION);
        marks.put("marks", list);
        return marks;
    }

    /**
     * Restores chunk column (cx, cz)'s saved marks ({@link #saveMarks}), unless this run has seen the column (memory
     * then holds its marks, newer than what the chunk was saved with). Elements that do not fit (another version, a
     * section outside the build height, a bit set of the wrong length) are skipped.
     */
    private void loadMarks(ServerWorld world, int cx, int cz, NbtCompound marks) {
        if (marks.getInt("version") != NBT_VERSION) {
            chunkDataProblem("version", "chunk " + cx + ", " + cz + " holds fluid marks of another version; they are "
                    + "ignored", null);
            return;
        }
        NbtList list = marks.getList("marks", NbtElement.COMPOUND_TYPE);
        if (list.isEmpty()) return;
        WorldTrails w = worlds.computeIfAbsent(world, k -> new WorldTrails());
        if (!w.seen.add(ChunkPos.toLong(cx, cz))) return;
        for (int n = 0; n < list.size(); n++) {
            NbtCompound element = list.getCompound(n);
            int sy = element.getInt("y");
            long[] bits = element.getLongArray("bits");
            if (!element.containsUuid("entry") || bits.length != SECTION_LONGS || sy < world.getBottomSectionCoord()
                    || sy >= world.getTopSectionCoord()) {
                chunkDataProblem("malformed", "chunk " + cx + ", " + cz + " holds fluid marks that do not fit; they "
                        + "are skipped", null);
                continue;
            }
            UUID owner = element.getUuid("entry");
            int ox = cx << 4, oy = sy << 4, oz = cz << 4;
            for (int word = 0; word < SECTION_LONGS; word++) {
                long set = bits[word];
                while (set != 0) {
                    int i = (word << 6) | Long.numberOfTrailingZeros(set);
                    set &= set - 1;
                    mark(w, ox + SectionBuffer.localX(i), oy + SectionBuffer.localY(i), oz + SectionBuffer.localZ(i),
                            owner);
                }
            }
        }
    }

    /** One world's marks and trails. */
    private static final class WorldTrails {
        final Long2ObjectOpenHashMap<SectionMarks> sections = new Long2ObjectOpenHashMap<>();
        /** The sections each entry holds marks in. */
        final Map<UUID, LongOpenHashSet> sectionsOf = new HashMap<>();
        final Map<UUID, Trail> trails = new LinkedHashMap<>();
        /** Per entry whose trail was last {@link #take taken}: the bytes folded before and whether it was full. */
        final Map<UUID, long[]> taken = new HashMap<>();
        /** Fluid cells an attributed tick updated (woke) that no entry marks: their next tick is that entry's. */
        final Long2ObjectOpenHashMap<Cause> woken = new Long2ObjectOpenHashMap<>();
        /**
         * Chunk columns ({@link ChunkPos#toLong}) whose marks memory holds in this run (marked here, or restored from
         * their chunk data): their saved marks are not read again when they load.
         */
        final LongOpenHashSet seen = new LongOpenHashSet();

        UUID ownerAt(int x, int y, int z) {
            SectionMarks section = sections.get(BlockBuffer.keyOfBlock(x, y, z));
            return section == null ? null : section.ownerAt(SectionBuffer.index(x & 15, y & 15, z & 15));
        }

        /** The entry that woke the fluid at {@code key} (once, until {@code now} passes its time), or {@code null}. */
        UUID wokenBy(long key, long now) {
            Cause cause = woken.remove(key);
            return cause == null || cause.until() < now ? null : cause.owner();
        }
    }

    /**
     * What an entry's fluid changed since the trail was last taken or folded, per chunk column (so one column can be
     * folded as the game saves it), and how much was folded into the entry since its last step.
     */
    private static final class Trail {
        /** Per chunk column ({@link ChunkPos#toLong}): first and last state of each cell changed there. */
        final Long2ObjectOpenHashMap<RecordBuilder> columns = new Long2ObjectOpenHashMap<>();
        /** Estimated bytes of the parts folded into the entry since its last step (they count against the cap). */
        long folded;
        int sinceCheck;
        /** Over {@link #MAX_TRAIL_BYTES}: records nothing more. */
        boolean full;

        RecordBuilder column(int x, int z) {
            long key = ChunkPos.toLong(x >> 4, z >> 4);
            RecordBuilder builder = columns.get(key);
            if (builder == null) {
                builder = new RecordBuilder();
                columns.put(key, builder);
            }
            return builder;
        }

        /** Held and folded bytes: what the cap is checked against. */
        long estimatedBytes() {
            long total = folded;
            for (RecordBuilder builder : columns.values()) total += builder.estimatedBytes();
            return total;
        }

        /** Every column's cells as one record, or {@code null} when nothing changed. */
        EditRecord buildAll() {
            BlockBuffer before = new BlockBuffer(), after = new BlockBuffer();
            for (RecordBuilder builder : columns.values()) {
                EditRecord part = builder.build();
                for (long key : part.before().keys()) {
                    before.putSection(key, part.before().section(key));
                    after.putSection(key, part.after().section(key));
                }
            }
            return before.isEmpty() ? null : new EditRecord(before, after);
        }

        /** {@code part} was folded into the entry. */
        void drained(EditRecord part) {
            if (part != null) folded += part.estimatedBytes();
        }

        /** Records {@code record}'s cells, after what is held (each cell keeps its first state and takes its last). */
        void addCells(EditRecord record) {
            BlockBuffer before = record.before(), after = record.after();
            for (long key : before.sortedKeys()) {
                SectionBuffer b = before.section(key), a = after.section(key);
                int ox = BlockBuffer.keyX(key) << 4, oy = BlockBuffer.keyY(key) << 4, oz = BlockBuffer.keyZ(key) << 4;
                RecordBuilder builder = column(ox, oz);
                b.forEachPresent(i -> builder.record(ox + SectionBuffer.localX(i), oy + SectionBuffer.localY(i),
                        oz + SectionBuffer.localZ(i), b.get(i), b.tile(i), a.get(i), a.tile(i)));
            }
        }
    }

    /** The marks of one 16³ section: per entry holding any, a 4096-bit set and its count. Usually one entry. */
    private static final class SectionMarks {
        private UUID[] owners = new UUID[1];
        private long[][] bits = new long[1][];
        private int[] counts = new int[1];
        private int size;

        UUID ownerAt(int i) {
            long bit = 1L << i;
            for (int k = 0; k < size; k++) {
                if ((bits[k][i >>> 6] & bit) != 0) return owners[k];
            }
            return null;
        }

        boolean holds(UUID owner) {
            return indexOf(owner) >= 0;
        }

        boolean isEmpty() {
            return size == 0;
        }

        /** Sets the cell's mark to {@code owner} (it holds none); true when the owner is new to this section. */
        boolean set(int i, UUID owner) {
            int k = indexOf(owner);
            boolean added = k < 0;
            if (added) {
                if (size == owners.length) {
                    owners = java.util.Arrays.copyOf(owners, size * 2);
                    bits = java.util.Arrays.copyOf(bits, size * 2);
                    counts = java.util.Arrays.copyOf(counts, size * 2);
                }
                k = size++;
                owners[k] = owner;
                bits[k] = new long[SectionBuffer.SIZE / 64];
                counts[k] = 0;
            }
            bits[k][i >>> 6] |= 1L << i;
            counts[k]++;
            return added;
        }

        /** Clears the cell's mark; returns the owner it had (dropped from the section when it was its last cell). */
        UUID clear(int i) {
            long bit = 1L << i;
            for (int k = 0; k < size; k++) {
                if ((bits[k][i >>> 6] & bit) == 0) continue;
                UUID owner = owners[k];
                bits[k][i >>> 6] &= ~bit;
                if (--counts[k] == 0) remove(k);
                return owner;
            }
            return null;
        }

        void drop(UUID owner) {
            int k = indexOf(owner);
            if (k >= 0) remove(k);
        }

        private int indexOf(UUID owner) {
            for (int k = 0; k < size; k++) {
                if (owners[k].equals(owner)) return k;
            }
            return -1;
        }

        private void remove(int k) {
            size--;
            owners[k] = owners[size];
            bits[k] = bits[size];
            counts[k] = counts[size];
            owners[size] = null;
            bits[size] = null;
        }
    }
}
