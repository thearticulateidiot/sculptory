package dev.sculptory.fabric.world;

import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.history.TileMatcher;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.io.IOException;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether a cell's live block entity still holds what an undo or redo expects there, compared in the game's own canonical form. Server thread only; one per undo or redo program.
 *
 * <p><b>Canonical form.</b> The live side is the block entity's {@code createNbtWithId} (what {@link FabricTile#capture}
 * holds: with {@code id}, without {@code x/y/z}), read without copying. The expected side is what the world holds right
 * after {@link BlockWriter} writes the recorded data: the compound is loaded into a detached block entity of the cell's
 * state (the writer's type checks, then {@code BlockEntityType.instantiate} and {@code BlockEntity.read}) and saved again
 * with {@code createNbtWithId}. So neither NBT key order, nor keys the game adds or drops on load (defaults such as an
 * empty {@code Items} list, {@code x/y/z}, a stringified text written another way), nor the mod's own trust and
 * sanitizing (a sanitized sign, a foreign tile) make an untouched block entity look changed. No recorded data
 * ({@code null}) stands for the block entity the game creates for the state by itself, as the writer leaves it; so does
 * data that does not decode, names an unknown type, no longer fits the block or fails to load (then logged at most once
 * per block-entity type every 10 minutes). Compounds are compared as maps (order-free, types exact). Recorded data that
 * is a server-captured compound equal to the live one matches without any of this.
 *
 * <p><b>Cost.</b> Each recorded tile's canonical form is built once per section and remembered for the write-time check
 * of the same cells ({@link #section} forgets it when the next section is computed), so undoing a paste of full
 * containers loads each recorded container once, and nothing is copied for the comparison.
 *
 * <p><b>Volatile keys.</b> A few vanilla keys are rewritten by the game from time or from the block's surroundings,
 * without anyone touching the block, and hold no items, text or settings ({@link #VOLATILE_KEYS}); they are left out of
 * the comparison ({@link #VOLATILE_ENTRY_KEYS} for such keys inside a list, a bee's time in its nest). Without that,
 * an untouched hopper would never undo (its cooldown changes on its first tick), nor would a bee nest. Data that
 * changes because the block works (a furnace cooking, a brewing stand, a campfire, a playing jukebox, a spawner's next
 * mob, a head whose profile the game completes) is compared: such a block is kept, by design; Undo anyway
 * overwrites it. Modded block entities are compared whole.
 */
public final class FabricTileMatcher implements TileMatcher {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    /**
     * Per block-entity type, keys the game rewrites by itself that no player sets (checked with javap against 1.21.1):
     * a hopper's transfer cooldown (every tick), a spawner's countdown (while a player is near), an end gateway's age
     * (every tick), a beacon's pyramid level (recomputed every 80 ticks), a comparator's output (its redstone input), a
     * conduit's target (a hostile mob nearby) and the sculk blocks' last vibration and spreading charges (players
     * walking by, mobs dying).
     */
    static final Map<String, Set<String>> VOLATILE_KEYS = Map.of(
            "minecraft:hopper", Set.of("TransferCooldown"),
            "minecraft:mob_spawner", Set.of("Delay"),
            "minecraft:end_gateway", Set.of("Age"),
            "minecraft:beacon", Set.of("Levels"),
            "minecraft:comparator", Set.of("OutputSignal"),
            "minecraft:conduit", Set.of("Target"),
            "minecraft:sculk_sensor", Set.of("last_vibration_frequency", "listener"),
            "minecraft:calibrated_sculk_sensor", Set.of("last_vibration_frequency", "listener"),
            "minecraft:sculk_shrieker", Set.of("warning_level", "listener"),
            "minecraft:sculk_catalyst", Set.of("cursors"));
    /**
     * Per block-entity type, a list key whose compound entries hold keys the game rewrites by itself: each bee in a
     * beehive or bee nest counts its time in the nest ({@code ticks_in_hive}, every tick). The list itself (how many
     * bees, and each bee's data) is compared: a bee that left or came in since is a change, as for any block that works.
     */
    static final Map<String, Map<String, Set<String>>> VOLATILE_ENTRY_KEYS = Map.of(
            "minecraft:beehive", Map.of("bees", Set.of("ticks_in_hive")));
    /** A block-entity type whose data failed to load is logged again at most this often (server-wide). */
    private static final long WARN_EVERY_NANOS = TimeUnit.MINUTES.toNanos(10);
    private static final Map<String, Long> WARNED = new ConcurrentHashMap<>();
    /** Warnings logged, for tests. */
    static final AtomicInteger WARNINGS = new AtomicInteger();
    /** A state creates no block entity by itself, or its canonical form could not be made. */
    private static final NbtCompound NONE = new NbtCompound();

    private final RegistryWrapper.WrapperLookup registries;
    private final FabricStateSpace states;
    /** Per state handle: the block entity the game creates for it by itself, canonical ({@link #NONE}: none). */
    private final Int2ObjectOpenHashMap<NbtCompound> defaults = new Int2ObjectOpenHashMap<>();
    /** Canonical forms of the recorded tiles of the section being worked on ({@link #NONE}: none can be made). */
    private final IdentityHashMap<BlockEntityData, NbtCompound> canonical = new IdentityHashMap<>();

    public FabricTileMatcher(RegistryWrapper.WrapperLookup registries, FabricStateSpace states) {
        this.registries = Objects.requireNonNull(registries);
        this.states = Objects.requireNonNull(states);
    }

    @Override
    public void section(long key) {
        canonical.clear();
    }

    @Override
    public boolean matches(int handle, BlockEntityData live, BlockEntityData expected) {
        if (live == null && expected == null) return true;
        BlockState state = states.state(handle);
        // A state without a block entity holds no contents to lose, whatever was recorded.
        if (!state.hasBlockEntity()) return true;
        NbtCompound now = live == null ? fresh(handle, state) : liveNbt(live);
        if (now == null) return false; // unreadable live data counts as changed: the block is kept
        // Server-captured data (a copy of world blocks, or what an edit replaced) equal to the live data: no load needed.
        if (expected instanceof FabricTile tile && tile.compound().equals(now)) return true;
        NbtCompound wanted = expected == null ? fresh(handle, state) : canonical(handle, state, expected);
        if (wanted == null) return false;
        return sameLeavingOutVolatile(now, wanted, now.getString("id"));
    }

    /** Whether {@code a} and {@code b}, of block-entity type {@code type}, match apart from that type's volatile keys. */
    static boolean sameLeavingOutVolatile(NbtCompound a, NbtCompound b, String type) {
        Set<String> ignored = VOLATILE_KEYS.get(type);
        Map<String, Set<String>> lists = VOLATILE_ENTRY_KEYS.get(type);
        if (lists == null) return sameIgnoring(a, b, ignored);
        Set<String> outer = new HashSet<>(lists.keySet());
        if (ignored != null) outer.addAll(ignored);
        if (!sameIgnoring(a, b, outer)) return false;
        for (Map.Entry<String, Set<String>> list : lists.entrySet()) {
            if (!sameEntriesIgnoring(a.get(list.getKey()), b.get(list.getKey()), list.getValue())) return false;
        }
        return true;
    }

    /** Whether lists {@code a} and {@code b} hold the same entries in the same order, compounds leaving out {@code ignored}. */
    private static boolean sameEntriesIgnoring(NbtElement a, NbtElement b, Set<String> ignored) {
        if (!(a instanceof NbtList left) || !(b instanceof NbtList right)) return Objects.equals(a, b);
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            NbtElement x = left.get(i);
            NbtElement y = right.get(i);
            boolean same = x instanceof NbtCompound cx && y instanceof NbtCompound cy
                    ? sameIgnoring(cx, cy, ignored)
                    : Objects.equals(x, y);
            if (!same) return false;
        }
        return true;
    }

    /** The live content's compound, not copied ({@code createNbtWithId} for a captured tile), or {@code null}. */
    private static NbtCompound liveNbt(BlockEntityData live) {
        if (live instanceof FabricTile tile) return tile.compound();
        try {
            return FabricTile.from(live).compound();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * What the world holds after {@link BlockWriter} writes {@code tile} on {@code state}: the tile loaded into a
     * detached block entity and saved again; the state's own default when that cannot be done (as the writer then leaves
     * the default). Remembered for the section.
     */
    private NbtCompound canonical(int handle, BlockState state, BlockEntityData tile) {
        NbtCompound known = canonical.get(tile);
        if (known == null) {
            known = load(state, tile);
            if (known == null) known = fresh(handle, state);
            canonical.put(tile, known == null ? NONE : known);
        }
        return known == NONE ? null : known;
    }

    /** {@code tile} as a block entity of {@code state} would save it, or {@code null} where the writer fails too. */
    private NbtCompound load(BlockState state, BlockEntityData tile) {
        NbtCompound nbt;
        try {
            nbt = FabricTile.from(tile).copyNbt();
        } catch (IOException | RuntimeException e) {
            return null; // the writer cannot read it either
        }
        String typeId = nbt.getString("id");
        Identifier id = Identifier.tryParse(typeId);
        BlockEntityType<?> type = id == null ? null : Registries.BLOCK_ENTITY_TYPE.getOrEmpty(id).orElse(null);
        if (type == null || !type.supports(state)) return null;
        try {
            // Not BlockEntity.createFromNbt: it logs an error with a stack trace for every tile that fails.
            BlockEntity entity = type.instantiate(BlockPos.ORIGIN, state);
            if (entity == null) return null;
            entity.read(nbt, registries);
            return entity.createNbtWithId(registries);
        } catch (RuntimeException e) {
            warn(typeId, e);
            return null;
        }
    }

    /**
     * The block entity the game creates for {@code state} by itself (what {@code BlockWriter} leaves when it writes no
     * data), canonical; {@code null} when there is none or it cannot be made.
     */
    private NbtCompound fresh(int handle, BlockState state) {
        NbtCompound known = defaults.get(handle);
        if (known == null) {
            known = NONE;
            if (state.getBlock() instanceof BlockEntityProvider provider) {
                try {
                    BlockEntity entity = provider.createBlockEntity(BlockPos.ORIGIN, state);
                    if (entity != null) known = entity.createNbtWithId(registries);
                } catch (RuntimeException e) {
                    warn(String.valueOf(Registries.BLOCK.getId(state.getBlock())), e);
                }
            }
            defaults.put(handle, known);
        }
        return known == NONE ? null : known;
    }

    /** Logs a load that failed, at most once per block-entity type every 10 minutes (server-wide). */
    private static void warn(String type, RuntimeException e) {
        long now = System.nanoTime();
        Long last = WARNED.get(type);
        if (last != null && now - last < WARN_EVERY_NANOS) return;
        WARNED.put(type, now);
        WARNINGS.incrementAndGet();
        LOG.warn("Sculptory: undo could not load recorded {} data to compare it ({}); such blocks compare as the "
                + "game's default for them (logged at most every 10 minutes per type)", type, e.toString());
    }

    /** Whether {@code a} and {@code b} hold the same entries, leaving out {@code ignored} keys ({@code null}: none). */
    static boolean sameIgnoring(NbtCompound a, NbtCompound b, Set<String> ignored) {
        if (ignored == null) return a.equals(b);
        int entries = 0;
        for (String key : a.getKeys()) {
            if (ignored.contains(key)) continue;
            entries++;
            if (!Objects.equals(a.get(key), b.get(key))) return false;
        }
        for (String key : b.getKeys()) {
            if (!ignored.contains(key)) entries--;
        }
        return entries == 0;
    }
}
