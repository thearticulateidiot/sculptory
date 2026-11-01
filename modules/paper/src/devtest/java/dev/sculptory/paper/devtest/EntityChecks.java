package dev.sculptory.paper.devtest;

import static dev.sculptory.paper.devtest.SelfTestRun.aabb;
import static dev.sculptory.paper.devtest.SelfTestRun.box;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.entity.EntityNbt;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.nbt.NbtList;
import dev.sculptory.core.nbt.NbtTag;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.tinker.EntityEdit;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.mc.world.McEntities;
import dev.sculptory.paper.SculptoryPlugin;
import dev.sculptory.paper.nms.CraftBukkitKeys;
import dev.sculptory.paper.nms.PaperAccess;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobResult;
import dev.sculptory.server.engine.RunOptions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Fox;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

/**
 * Entities as Paper saves and spawns them.
 *
 * <p><b>CraftBukkit's keys</b> ({@link #craftBukkitKeys}): every capture leaves out the world id and the tick age
 * ({@code CraftBukkitKeys.STRIPPED}) and keeps CraftBukkit's data version ({@code Bukkit.updateLevel}) and a plugin's
 * settings such as {@code Bukkit.visibleByDefault}; history keeps the spawn context ({@code Paper.SpawnReason}), a
 * clipboard leaves it out; an entity pasted from data without Paper's keys (a schematic made elsewhere) but with a plugin
 * setting keeps the setting and is undone without a conflict.
 *
 * <p><b>Spawning</b> ({@link #spawning}), on a stone floor of its own: a pasted zombie is placed where the server's
 * spawn rules spawn none (Peaceful, spawn-monsters off; Paper's default spawn reason would have it turned away, which is
 * logged for comparison); entities another plugin turns away (its spawn event cancelled) are counted as refused and
 * nothing of them stays, the rest of the paste is placed and its undo takes exactly that away; a passenger turned away
 * refuses its vehicle with it; a fox moved is a new spawn (none of its spawn context carries over: Paper gives
 * an entity loaded from data without a spawn reason {@code DEFAULT}, its origin is where it landed) that still picks
 * up no items, and the move's undo puts the same fox back (UUID, spawn reason, origin, no item pickup, its saved
 * data); a zombie the same off Peaceful. A move's undo whose restore another plugin cancels counts it as refused and
 * keeps the moved copy (a redo then changes nothing, a later undo puts the source back); with a boat whose copy a pig
 * boarded since, the boat comes back without the pig, which stays; a Tinker edit's undo whose restore is cancelled
 * keeps the stand as edited. Last, the keys Paper saves that vanilla does not are listed, each is one history compares
 * without, and a Fabric-style capture matches the Paper one.
 */
final class EntityChecks {
    /** Entities with this scoreboard tag are turned away by {@link Refuser}. */
    static final String REFUSE_TAG = "sculptory_selftest_refuse";

    private EntityChecks() {
    }

    static CompletableFuture<Void> craftBukkitKeys(SelfTestRun r) {
        int x = r.baseX + 320, z = r.baseZ + 4;
        Box area = box(x, 99, z, x + 9, 106, z + 9);
        SelfTestRun.Area claimed = r.claim(area);
        return r.ready(claimed).thenCompose(v -> {
            if (claimed.hadEntities() || !r.level.getEntitiesOfClass(ArmorStand.class, aabb(area)).isEmpty()) {
                throw new IllegalStateException("the entity check's area holds entities already; it changes nothing");
            }
            ArmorStand stand = RegionChecks.stand(r, x + 1.5, 101, z + 1.5);
            // A setting a plugin makes through Paper's API, saved under a key of CraftBukkit's own.
            stand.getBukkitEntity().setVisibleByDefault(false);
            CompoundTag raw = new CompoundTag();
            stand.saveAsPassenger(raw);
            List<String> stripped = raw.getAllKeys().stream().filter(CraftBukkitKeys::stripped).sorted().toList();
            CompoundTag captured = McEntities.save(stand, EntityFilter.DECORATIONS);
            List<String> left = captured.getAllKeys().stream().filter(CraftBukkitKeys::stripped).sorted().toList();
            boolean kept = captured.contains("Bukkit.visibleByDefault");
            boolean version = captured.contains("Bukkit.updateLevel");
            boolean reason = captured.contains("Paper.SpawnReason");
            r.record("entities: a capture leaves the world id and the age out and keeps the data version and plugin "
                            + "settings", !stripped.isEmpty() && left.isEmpty() && kept && version,
                    "the game saves " + stripped + ", the capture keeps " + left + "; Bukkit.visibleByDefault kept "
                            + kept + ", Bukkit.updateLevel kept " + version);
            NbtCompound clip;
            try {
                clip = EntityNbt.decode(McEntities.snapshot(stand, EntityFilter.DECORATIONS, new BlockPos(x, 100, z),
                        false).nbt());
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            List<String> context = clip.keys().stream().filter(CraftBukkitKeys::spawnContext).sorted().toList();
            r.record("entities: history keeps the spawn context, a clipboard leaves it out",
                    reason && context.isEmpty() && clip.contains("Bukkit.updateLevel"),
                    "history capture has Paper.SpawnReason " + reason + "; the clipboard keeps " + context
                            + " of the spawn context and Bukkit.updateLevel " + clip.contains("Bukkit.updateLevel"));
            stand.discard();
            return pasteWithoutPaperKeys(r, x, z);
        });
    }

    /**
     * An armor stand pasted from data with none of Paper's keys (as a schematic from another platform holds it) but
     * with a plugin setting ({@code Bukkit.visibleByDefault: false}), then undone: the placed stand keeps the setting, and
     * the undo compares it with what the paste recorded after placing it, so it must find no conflict and take it away.
     */
    private static CompletableFuture<Void> pasteWithoutPaperKeys(SelfTestRun r, int x, int z) {
        byte[] data = NbtIo.toBytes(NbtCompound.builder().putByte("NoGravity", (byte) 1)
                .putByte("Bukkit.visibleByDefault", (byte) 0).build());
        Clipboard clipboard = Clipboard.builder(r.host.engine().states(), new BlockPos(3, 3, 3)).build()
                .withEntities(List.of(new EntitySnapshot("minecraft:armor_stand", 1.5, 0, 1.5, 0f, 0f, null, data,
                        false)));
        BlockPos origin = new BlockPos(x + 5, 101, z + 5);
        Box pasted = box(origin.x(), origin.y(), origin.z(), origin.x() + 2, origin.y() + 2, origin.z() + 2);
        return paste(r, clipboard, origin)
                .thenCompose(result -> {
                    List<ArmorStand> placed = r.level.getEntitiesOfClass(ArmorStand.class, aabb(pasted));
                    boolean hidden = placed.size() == 1 && !placed.get(0).getBukkitEntity().isVisibleByDefault();
                    r.record("entities: a pasted stand keeps the plugin setting it was saved with",
                            placed.size() == 1 && hidden, placed.size() + " stands placed, visible by default "
                                    + !hidden + " (" + result.outcome() + ")");
                    // A few ticks, as a player would undo later: CraftBukkit's tick counter moves on meanwhile.
                    return r.ticks(10).thenCompose(v -> r.undo());
                })
                .thenCompose(result -> r.ticks(2).thenApply(v -> result))
                .thenAccept(result -> {
                    int left = r.level.getEntitiesOfClass(ArmorStand.class, aabb(pasted), ArmorStand::isAlive).size();
                    r.record("entities: its undo finds no conflict and takes it away",
                            left == 0 && result.skippedConflicts() == 0, result.outcome() + ", " + result.changed()
                                    + " changed, " + result.skippedConflicts() + " conflicts, " + left + " stands left");
                });
    }

    // ================================================================== spawning

    /** Another plugin turning entities away: it cancels the spawn of a tagged entity, or of one whose UUID it lists. */
    static final class Refuser implements Listener {
        final Set<UUID> refuse = new HashSet<>();
        /** UUIDs whose next spawn alone is cancelled (a plugin turning one spawn away, not every later one). */
        final Set<UUID> refuseOnce = new HashSet<>();
        /** The spawn reasons of the entities seen, as "type reason[ cancelled]". */
        final List<String> seen = new ArrayList<>();

        @EventHandler(priority = EventPriority.HIGHEST)
        public void spawning(CreatureSpawnEvent event) {
            org.bukkit.entity.Entity entity = event.getEntity();
            boolean cancel = refused(entity);
            if (cancel) event.setCancelled(true);
            seen.add(entity.getType().getKey().getKey() + " " + event.getSpawnReason() + (cancel ? " cancelled" : ""));
        }

        /** A hanging entity (an item frame) is announced with EntitySpawnEvent (living ones with the subclass above). */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void spawningOther(org.bukkit.event.entity.EntitySpawnEvent event) {
            if (event instanceof CreatureSpawnEvent) return;
            org.bukkit.entity.Entity entity = event.getEntity();
            boolean cancel = refused(entity);
            if (cancel) event.setCancelled(true);
            seen.add(entity.getType().getKey().getKey() + " spawn" + (cancel ? " cancelled" : ""));
        }

        /** A boat or minecart is a vehicle: Bukkit announces its spawn with VehicleCreateEvent. */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void creating(org.bukkit.event.vehicle.VehicleCreateEvent event) {
            org.bukkit.entity.Entity entity = event.getVehicle();
            boolean cancel = refused(entity);
            if (cancel) event.setCancelled(true);
            seen.add(entity.getType().getKey().getKey() + " vehicle" + (cancel ? " cancelled" : ""));
        }

        private boolean refused(org.bukkit.entity.Entity entity) {
            return entity.getScoreboardTags().contains(REFUSE_TAG) || refuse.contains(entity.getUniqueId())
                    || refuseOnce.remove(entity.getUniqueId());
        }

        List<String> take() {
            List<String> out = List.copyOf(seen);
            seen.clear();
            return out;
        }
    }

    static CompletableFuture<Void> spawning(SelfTestRun r) {
        int x = r.baseX + 320, z = r.baseZ + 48;
        Box area = box(x, 99, z, x + 23, 108, z + 31);
        SelfTestRun.Area claimed = r.claim(area);
        for (int cx = x; cx <= x + 23; cx++) {
            for (int cz = z; cz <= z + 31; cz++) {
                set(r, new net.minecraft.core.BlockPos(cx, 100, cz), Blocks.STONE.defaultBlockState());
                for (int y = 101; y <= 108; y++) {
                    set(r, new net.minecraft.core.BlockPos(cx, y, cz), Blocks.AIR.defaultBlockState());
                }
            }
        }
        Refuser refuser = new Refuser();
        Bukkit.getPluginManager().registerEvents(refuser, SculptoryPlugin.instance());
        r.cleanup(() -> HandlerList.unregisterAll(refuser));
        r.info("entities: the server's spawn rules", "difficulty " + r.level.getDifficulty().getKey() + ", monsters "
                + r.level.getWorld().getAllowMonsters() + ", animals " + r.level.getWorld().getAllowAnimals()
                + ", villagers " + r.level.getServer().areNpcsEnabled());
        return r.ready(claimed).thenCompose(v -> {
            if (claimed.hadEntities()) {
                throw new IllegalStateException("the spawning check's area holds entities already; it changes nothing");
            }
            return pasteMonster(r, x, z);
        }).thenCompose(v -> refusedPaste(r, refuser, x + 6, z + 1))
                .thenCompose(v -> moveFox(r, x + 1, z + 6))
                .thenCompose(v -> moveZombie(r, x + 1, z + 11))
                .thenCompose(v -> refusedRestore(r, refuser, x + 16, z + 6))
                .thenCompose(v -> refusedRestoreLeavesRider(r, refuser, x + 20, z + 1))
                .thenCompose(v -> refusedTinkerUndo(r, refuser, x + 21, z + 11))
                .thenCompose(v -> frameIntoAir(r, refuser, x, z + 16))
                .thenCompose(v -> pigIntoStone(r, refuser, x, z + 24))
                .thenAccept(v -> platformKeys(r, x + 12, z + 10));
    }

    /**
     * A zombie pasted where the server spawns no monsters is placed (the plugin spawn reason the engine uses skips those
     * rules), and the paste's undo takes it away. For comparison, a zombie added with Paper's default reason is logged:
     * turned away there. The paste runs on a world that keeps monsters ({@link #keepingMonsters}), so the check does
     * not depend on the tick the job ends in.
     */
    private static CompletableFuture<Void> pasteMonster(SelfTestRun r, int x, int z) {
        Zombie probe = new Zombie(EntityType.ZOMBIE, r.level);
        probe.moveTo(x + 20.5, 101, z + 14.5, 0f, 0f);
        boolean added = r.level.tryAddFreshEntityWithPassengers(probe);
        boolean in = inWorld(r, probe);
        if (in) probe.discard();
        r.info("entities: a zombie added with Paper's default spawn reason",
                "tryAddFreshEntityWithPassengers returned " + added + ", in the world " + in);
        org.bukkit.Difficulty was = keepingMonsters(r, "pasting a zombie");
        if (was == null) return CompletableFuture.completedFuture(null);
        byte[] data = NbtIo.toBytes(NbtCompound.builder().putByte("NoAI", (byte) 1)
                .putByte("PersistenceRequired", (byte) 1).build());
        Clipboard clipboard = Clipboard.builder(r.host.engine().states(), new BlockPos(3, 3, 3)).build()
                .withEntities(List.of(new EntitySnapshot("minecraft:zombie", 1.5, 0, 1.5, 0f, 0f, null, data, false)));
        BlockPos origin = new BlockPos(x + 1, 101, z + 1);
        Box pasted = box(origin.x(), origin.y(), origin.z(), origin.x() + 2, origin.y() + 2, origin.z() + 2);
        return paste(r, clipboard, origin).thenCompose(result -> {
            List<Zombie> placed = r.level.getEntitiesOfClass(Zombie.class, aabb(pasted), Zombie::isAlive);
            String reason = placed.size() == 1 ? String.valueOf(placed.get(0).getBukkitEntity().getEntitySpawnReason())
                    : "-";
            r.record("entities: a paste places a zombie where the server's rules spawn none",
                    placed.size() == 1 && result.skippedProtected() == 0, placed.size() + " zombies placed, spawn reason "
                            + reason + " (" + result.outcome() + ", " + result.skippedProtected() + " protected)");
            return r.ticks(5).thenCompose(v -> r.undo());
        }).thenAccept(result -> {
            int left = r.level.getEntitiesOfClass(Zombie.class, aabb(pasted), Zombie::isAlive).size();
            r.record("entities: the zombie paste's undo takes the zombie away", left == 0 && result.changed() == 1
                    && result.skippedConflicts() == 0, result.outcome() + ", " + result.changed() + " changed, "
                    + result.skippedConflicts() + " conflicts, " + left + " left");
        }).whenComplete((v, e) -> r.level.getWorld().setDifficulty(was));
    }

    /**
     * Readies the run's world for a check with a monster, which Peaceful despawns on its next tick: a Peaceful world
     * with spawn-monsters off (no monster spawns naturally either way) is set to Normal, and set back by the caller and
     * by the run's clean-up. Returns the difficulty to set back, or null when the check is skipped (Peaceful with
     * spawn-monsters on, where Normal would let monsters spawn).
     */
    private static org.bukkit.Difficulty keepingMonsters(SelfTestRun r, String check) {
        org.bukkit.World bukkit = r.level.getWorld();
        org.bukkit.Difficulty was = bukkit.getDifficulty();
        if (r.level.getDifficulty() == Difficulty.PEACEFUL) {
            boolean spawnMonsters = !(r.level.getServer() instanceof net.minecraft.server.dedicated.DedicatedServer d)
                    || d.getProperties().spawnMonsters;
            if (spawnMonsters) {
                r.info("entities: " + check, "skipped: Peaceful despawns a monster on its next tick, and "
                        + "spawn-monsters is on (Normal would let monsters spawn)");
                return null;
            }
            bukkit.setDifficulty(org.bukkit.Difficulty.NORMAL);
            r.cleanup(() -> bukkit.setDifficulty(was));
        }
        r.info("entities: " + check, "difficulty " + r.level.getDifficulty().getKey() + " for the check, monsters "
                + bukkit.getAllowMonsters());
        return was;
    }

    /**
     * A paste of three pigs: one plain, one tagged (another plugin cancels its spawn), one carrying a tagged chicken.
     * Only the plain pig is placed and the other two count as refused (a passenger turned away refuses its vehicle
     * too; nothing of either stays); the undo takes the plain pig away without a conflict.
     */
    private static CompletableFuture<Void> refusedPaste(SelfTestRun r, Refuser refuser, int x, int z) {
        NbtTag tags = NbtList.ofStrings(List.of(REFUSE_TAG));
        NbtCompound chicken = NbtCompound.builder().putString("id", "minecraft:chicken").putByte("NoAI", (byte) 1)
                .put("Tags", tags).build();
        byte[] plain = NbtIo.toBytes(NbtCompound.builder().putByte("NoAI", (byte) 1).build());
        byte[] tagged = NbtIo.toBytes(NbtCompound.builder().putByte("NoAI", (byte) 1).put("Tags", tags).build());
        byte[] carrying = NbtIo.toBytes(NbtCompound.builder().putByte("NoAI", (byte) 1)
                .put("Passengers", NbtList.of(NbtTag.COMPOUND, List.of(chicken))).build());
        Clipboard clipboard = Clipboard.builder(r.host.engine().states(), new BlockPos(8, 3, 3)).build()
                .withEntities(List.of(new EntitySnapshot("minecraft:pig", 1.5, 0, 1.5, 0f, 0f, null, plain, false),
                        new EntitySnapshot("minecraft:pig", 3.5, 0, 1.5, 0f, 0f, null, tagged, false),
                        new EntitySnapshot("minecraft:pig", 5.5, 0, 1.5, 0f, 0f, null, carrying, false)));
        BlockPos origin = new BlockPos(x, 101, z);
        Box pasted = box(x, 101, z, x + 7, 103, z + 2);
        refuser.take();
        return paste(r, clipboard, origin).thenCompose(result -> {
            int pigs = r.level.getEntitiesOfClass(Pig.class, aabb(pasted), Pig::isAlive).size();
            int chickens = r.level.getEntitiesOfClass(Chicken.class, aabb(pasted), Chicken::isAlive).size();
            r.record("entities: a paste whose spawns another plugin cancels places only what it let in",
                    pigs == 1 && chickens == 0 && result.skippedProtected() == 2,
                    pigs + " pigs and " + chickens + " chickens placed, " + result.skippedProtected()
                            + " refused (" + result.outcome() + "); spawn events " + refuser.take());
            return r.ticks(5).thenCompose(v -> r.undo());
        }).thenAccept(result -> {
            int pigs = r.level.getEntitiesOfClass(Pig.class, aabb(pasted), Pig::isAlive).size();
            r.record("entities: its undo takes away exactly the pig it placed", pigs == 0
                    && result.skippedConflicts() == 0, result.outcome() + ", " + result.changed() + " changed, "
                    + result.skippedConflicts() + " conflicts, " + pigs + " pigs left");
        });
    }

    /**
     * A fox (bred, so its spawn reason is BREEDING; set not to pick items up) moved 10 east, then the move undone. The
     * moved fox is a new spawn (not BREEDING: Paper gives an entity loaded from data without a spawn reason
     * {@code DEFAULT}; its origin where it landed) and still picks up nothing (CraftBukkit
     * applies a saved {@code CanPickUpLoot} false only with {@code Bukkit.updateLevel}); the undo puts the same fox back
     * with its spawn reason, origin and data.
     */
    private static CompletableFuture<Void> moveFox(SelfTestRun r, int x, int z) {
        Fox fox = new Fox(EntityType.FOX, r.level);
        fox.moveTo(x + 1.5, 101, z + 1.5, 90f, 0f);
        fox.setNoAi(true);
        fox.setPersistenceRequired();
        fox.setCanPickUpLoot(false);
        fox.setCustomName(net.minecraft.network.chat.Component.literal("selftest fox"));
        r.level.tryAddFreshEntityWithPassengers(fox, CreatureSpawnEvent.SpawnReason.BREEDING);
        if (!inWorld(r, fox)) {
            r.info("entities: moving a fox", "skipped: the server did not let the bred fox in (spawn-animals off?)");
            return CompletableFuture.completedFuture(null);
        }
        UUID id = fox.getUUID();
        org.bukkit.Location origin = fox.getBukkitEntity().getOrigin();
        CompoundTag before = comparable(fox);
        Box source = box(x, 101, z, x + 2, 103, z + 2);
        Box dest = box(x + 10, 101, z, x + 12, 103, z + 2);
        return move(r, source, new BlockPos(10, 0, 0)).thenCompose(result -> {
            List<Fox> moved = r.level.getEntitiesOfClass(Fox.class, aabb(dest), Fox::isAlive);
            Fox copy = moved.size() == 1 ? moved.get(0) : null;
            boolean fresh = copy != null && !copy.getUUID().equals(id)
                    && copy.getBukkitEntity().getEntitySpawnReason() != CreatureSpawnEvent.SpawnReason.BREEDING
                    && copy.getBukkitEntity().getOrigin() != null
                    && copy.getBukkitEntity().getOrigin().getBlockX() >= x + 10;
            boolean noLoot = copy != null && !copy.canPickUpLoot();
            r.record("entities: a moved fox is a new spawn and still picks up no items", fresh && noLoot
                    && r.level.getEntity(id) == null, moved.size() + " foxes at the destination, spawn reason "
                    + (copy == null ? "-" : copy.getBukkitEntity().getEntitySpawnReason()) + ", picks up items "
                    + (copy != null && copy.canPickUpLoot()) + " (" + result.outcome() + ")");
            return r.ticks(5).thenCompose(v -> r.undo());
        }).thenAccept(result -> {
            Entity back = r.level.getEntity(id);
            Fox restored = back instanceof Fox f && f.isAlive() ? f : null;
            int atDest = r.level.getEntitiesOfClass(Fox.class, aabb(dest), Fox::isAlive).size();
            boolean same = restored != null
                    && restored.getBukkitEntity().getEntitySpawnReason() == CreatureSpawnEvent.SpawnReason.BREEDING
                    && Objects.equals(restored.getBukkitEntity().getOrigin(), origin) && !restored.canPickUpLoot()
                    && comparable(restored).equals(before);
            r.record("entities: the move's undo puts the same fox back exactly", same && atDest == 0
                            && result.skippedConflicts() == 0,
                    "restored " + (restored != null) + ", spawn reason " + (restored == null ? "-"
                            : restored.getBukkitEntity().getEntitySpawnReason()) + ", origin kept " + (restored != null
                            && Objects.equals(restored.getBukkitEntity().getOrigin(), origin)) + ", picks up items "
                            + (restored != null && restored.canPickUpLoot()) + ", data the same " + (restored != null
                            && comparable(restored).equals(before)) + "; " + atDest + " at the destination ("
                            + result.outcome() + ", " + result.skippedConflicts() + " conflicts)");
        });
    }

    /**
     * A zombie moved where the server spawns no monsters, and the move undone: the copy is placed and the undo puts the
     * zombie back. On a world that keeps monsters ({@link #keepingMonsters}): on Peaceful a monster despawns on its next
     * tick, before a move could start.
     */
    private static CompletableFuture<Void> moveZombie(SelfTestRun r, int x, int z) {
        org.bukkit.World bukkit = r.level.getWorld();
        org.bukkit.Difficulty was = keepingMonsters(r, "moving a zombie");
        if (was == null) return CompletableFuture.completedFuture(null);
        Zombie zombie = new Zombie(EntityType.ZOMBIE, r.level);
        zombie.moveTo(x + 1.5, 101, z + 1.5, 0f, 0f);
        zombie.setNoAi(true);
        zombie.setPersistenceRequired();
        r.level.tryAddFreshEntityWithPassengers(zombie, CreatureSpawnEvent.SpawnReason.CUSTOM);
        UUID id = zombie.getUUID();
        Box source = box(x, 101, z, x + 2, 103, z + 2);
        Box dest = box(x + 10, 101, z, x + 12, 103, z + 2);
        return move(r, source, new BlockPos(10, 0, 0)).thenCompose(result -> {
            int moved = r.level.getEntitiesOfClass(Zombie.class, aabb(dest), Zombie::isAlive).size();
            r.record("entities: a moved zombie is placed where the server spawns no monsters", moved == 1,
                    moved + " zombies at the destination (" + result.outcome() + ", " + result.skippedProtected()
                            + " protected)");
            return r.ticks(5).thenCompose(v -> r.undo());
        }).thenAccept(result -> {
            Entity back = r.level.getEntity(id);
            int atDest = r.level.getEntitiesOfClass(Zombie.class, aabb(dest), Zombie::isAlive).size();
            r.record("entities: the zombie move's undo puts it back", back != null && back.isAlive() && atDest == 0
                    && result.skippedConflicts() == 0, "restored " + (back != null && back.isAlive()) + ", " + atDest
                    + " at the destination (" + result.outcome() + ", " + result.skippedConflicts() + " conflicts)");
        }).whenComplete((v, e) -> bukkit.setDifficulty(was));
    }

    /**
     * A pig moved 5 south, then another plugin cancels the spawn of the pig's own UUID and the move is undone: the
     * restore counts as refused and the copy stays where the move put it (put back by the undo after it took it away),
     * so the pig is never only in the history. With the plugin gone, the entry's redo changes nothing (the copy is
     * where it left it) and a second undo puts the pig back at the source and takes the copy away.
     */
    private static CompletableFuture<Void> refusedRestore(SelfTestRun r, Refuser refuser, int x, int z) {
        Pig pig = new Pig(EntityType.PIG, r.level);
        pig.moveTo(x + 1.5, 101, z + 1.5, 0f, 0f);
        pig.setNoAi(true);
        r.level.tryAddFreshEntityWithPassengers(pig, CreatureSpawnEvent.SpawnReason.CUSTOM);
        UUID id = pig.getUUID();
        Box source = box(x, 101, z, x + 2, 103, z + 2);
        Box dest = box(x, 101, z + 5, x + 2, 103, z + 7);
        UUID[] copy = {null};
        return move(r, source, new BlockPos(0, 0, 5)).thenCompose(result -> {
            List<Pig> moved = r.level.getEntitiesOfClass(Pig.class, aabb(dest), Pig::isAlive);
            copy[0] = moved.size() == 1 ? moved.get(0).getUUID() : null;
            refuser.refuse.add(id);
            refuser.take();
            return r.ticks(5).thenCompose(v -> r.undo());
        }).thenCompose(result -> {
            refuser.refuse.clear();
            int atSource = pigs(r, source);
            Entity kept = copy[0] == null ? null : r.level.getEntity(copy[0]);
            r.record("entities: an undo whose restore another plugin cancels keeps the moved copy",
                    atSource == 0 && pigs(r, dest) == 1 && kept != null && kept.isAlive()
                            && result.skippedProtected() >= 1,
                    atSource + " pigs at the source, " + pigs(r, dest) + " at the destination (the copy itself "
                            + (kept != null && kept.isAlive()) + "), " + result.skippedProtected() + " refused ("
                            + result.outcome() + "); spawn events " + refuser.take());
            return r.ticks(5).thenCompose(v -> r.redo());
        }).thenCompose(result -> {
            r.record("entities: the entry's redo then changes nothing", pigs(r, source) == 0 && pigs(r, dest) == 1,
                    pigs(r, source) + " at the source, " + pigs(r, dest) + " at the destination (" + result.outcome()
                            + ", " + result.skippedConflicts() + " conflicts)");
            return r.ticks(5).thenCompose(v -> r.undo());
        }).thenAccept(result -> {
            Entity back = r.level.getEntity(id);
            r.record("entities: a later undo puts the pig back at the source and takes the copy away", back != null
                    && back.isAlive() && pigs(r, source) == 1 && pigs(r, dest) == 0, pigs(r, source)
                    + " at the source (the pig itself " + (back != null && back.isAlive()) + "), " + pigs(r, dest)
                    + " at the destination (" + result.outcome() + ", " + result.skippedConflicts() + " conflicts)");
        });
    }

    private static int pigs(SelfTestRun r, Box box) {
        return r.level.getEntitiesOfClass(Pig.class, aabb(box), Pig::isAlive).size();
    }

    /**
     * A boat moved 5 south, a pig then boards the moved boat (a change since the move: a plain undo keeps it as a
     * conflict), another plugin turns the source boat's restore away (once) and the move is undone anyway: the moved
     * boat comes back as it was but without the pig (its removal took only what the move recorded and dismounted the
     * pig, which stays in the world), and the step counts as refused, not failed.
     */
    private static CompletableFuture<Void> refusedRestoreLeavesRider(SelfTestRun r, Refuser refuser, int x, int z) {
        Boat boat = new Boat(EntityType.BOAT, r.level);
        boat.moveTo(x + 1.5, 101, z + 1.5, 0f, 0f);
        r.level.tryAddFreshEntityWithPassengers(boat, CreatureSpawnEvent.SpawnReason.CUSTOM);
        UUID source = boat.getUUID();
        Box from = box(x, 101, z, x + 2, 103, z + 2);
        Box to = box(x, 101, z + 5, x + 2, 103, z + 7);
        UUID[] ids = {null, null};
        return move(r, from, new BlockPos(0, 0, 5)).thenCompose(result -> {
            List<Boat> moved = r.level.getEntitiesOfClass(Boat.class, aabb(to), Boat::isAlive);
            if (moved.size() != 1) throw new IllegalStateException(moved.size() + " boats at the destination");
            Boat copy = moved.get(0);
            Pig pig = new Pig(EntityType.PIG, r.level);
            pig.moveTo(copy.getX(), copy.getY(), copy.getZ(), 0f, 0f);
            pig.setNoAi(true);
            r.level.tryAddFreshEntityWithPassengers(pig, CreatureSpawnEvent.SpawnReason.CUSTOM);
            pig.startRiding(copy, true);
            ids[0] = copy.getUUID();
            ids[1] = pig.getUUID();
            refuser.refuseOnce.add(source);
            refuser.take();
            return r.ticks(5).thenCompose(v -> r.undoAnyway());
        }).thenAccept(result -> {
            refuser.refuseOnce.clear();
            Entity copy = r.level.getEntity(ids[0]);
            Entity pig = r.level.getEntity(ids[1]);
            boolean boatBack = copy != null && copy.isAlive() && copy.getPassengers().isEmpty();
            boolean pigStays = pig != null && pig.isAlive() && !pig.isPassenger();
            int atSource = r.level.getEntitiesOfClass(Boat.class, aabb(from), Boat::isAlive).size();
            r.record("entities: a refused restore gives the moved boat back without the rider that boarded it since",
                    boatBack && pigStays && atSource == 0 && result.skippedProtected() >= 1
                            && result.skippedConflicts() == 0,
                    "the moved boat back " + boatBack + ", the pig still there and on foot " + pigStays + ", "
                            + atSource + " boats at the source, " + result.skippedProtected() + " refused, "
                            + result.skippedConflicts() + " conflicts (" + result.outcome() + "); spawn events "
                            + refuser.take());
        });
    }

    /**
     * An armor stand turned by Tinker (one step whose before and after are the same UUID), then another plugin turns
     * the stand's restore away (once) and the step is undone: the stand stays as Tinker left it (taken away and put
     * back), and the step counts as refused, not failed.
     */
    private static CompletableFuture<Void> refusedTinkerUndo(SelfTestRun r, Refuser refuser, int x, int z) {
        ArmorStand stand = RegionChecks.stand(r, x + 0.5, 101, z + 0.5);
        UUID id = stand.getUUID();
        try {
            r.service.entity(r.player, id, List.of(new EntityEdit.Yaw(90)));
        } catch (EditRejected e) {
            throw new IllegalStateException("the Tinker edit was refused: " + e.getMessage(), e);
        }
        Entity edited = r.level.getEntity(id);
        float yaw = edited == null ? Float.NaN : edited.getYRot();
        refuser.refuseOnce.add(id);
        refuser.take();
        return r.ticks(5).thenCompose(v -> r.undo()).thenAccept(result -> {
            refuser.refuseOnce.clear();
            Entity now = r.level.getEntity(id);
            boolean kept = now != null && now.isAlive() && Math.abs(now.getYRot() - yaw) < 0.5f;
            r.record("entities: a Tinker undo whose restore another plugin cancels keeps the stand as edited",
                    kept && Math.abs(yaw - 90f) < 0.5f && result.skippedProtected() >= 1
                            && result.skippedConflicts() == 0,
                    "edited to yaw " + yaw + ", after the undo " + (now == null ? "gone" : "yaw " + now.getYRot())
                            + ", " + result.skippedProtected() + " refused, " + result.skippedConflicts()
                            + " conflicts (" + result.outcome() + "); spawn events " + refuser.take());
        });
    }

    /**
     * The keys Paper writes into a saved pig, zombie, horse, item frame and armor stand that vanilla does not:
     * CraftBukkit and Paper keys (their {@code Bukkit.}, {@code Paper.} and {@code Spigot.} names) and {@code AgeLocked}
     * (found by diffing Paper 1.21.1's entity save code with vanilla's). Each must be one history compares without
     * ({@code EntityNbt.PLATFORM_KEYS}), and the same entity captured as Fabric or NeoForge would capture it (without
     * them) must match its Paper capture both ways.
     */
    private static void platformKeys(SelfTestRun r, int x, int z) {
        List<Entity> made = new ArrayList<>();
        Pig pig = new Pig(EntityType.PIG, r.level);
        Zombie zombie = new Zombie(EntityType.ZOMBIE, r.level);
        Horse horse = new Horse(EntityType.HORSE, r.level);
        ArmorStand stand = new ArmorStand(EntityType.ARMOR_STAND, r.level);
        made.add(pig);
        made.add(zombie);
        made.add(horse);
        made.add(stand);
        for (int i = 0; i < made.size(); i++) made.get(i).moveTo(x + 0.5 + i, 101, z + 0.5, 0f, 0f);
        made.add(new ItemFrame(r.level, new net.minecraft.core.BlockPos(x + 4, 101, z),
                net.minecraft.core.Direction.NORTH));
        Set<String> found = new java.util.TreeSet<>();
        List<String> perType = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        dev.sculptory.core.history.EntityMatcher matcher = dev.sculptory.core.history.EntityMatcher.ignoringVolatile();
        for (Entity entity : made) {
            r.level.tryAddFreshEntityWithPassengers(entity, CreatureSpawnEvent.SpawnReason.CUSTOM);
            CompoundTag paper = McEntities.save(entity, EntityFilter.ALL);
            entity.discard();
            if (paper == null) continue;
            List<String> extra = paper.getAllKeys().stream().filter(EntityChecks::paperOnly).sorted().toList();
            found.addAll(extra);
            perType.add(McEntities.typeId(entity) + " " + extra);
            CompoundTag vanilla = paper.copy();
            for (String key : extra) vanilla.remove(key);
            dev.sculptory.core.history.EntityState onPaper = state(entity, paper);
            dev.sculptory.core.history.EntityState onFabric = state(entity, vanilla);
            if (!matcher.matches(onFabric, onPaper) || !dev.sculptory.core.history.EntityMatcher.ignoringVolatile()
                    .matches(onPaper, onFabric)) {
                unmatched.add(McEntities.typeId(entity));
            }
        }
        r.info("entities: keys Paper saves that vanilla does not", String.join("; ", perType));
        List<String> compared = found.stream().filter(key -> !EntityNbt.PLATFORM_KEYS.contains(key)).toList();
        r.record("entities: history compares without every Paper-only key a fresh entity carries",
                compared.isEmpty() && !found.isEmpty(), "found " + found + ", compared all the same " + compared);
        r.record("entities: each entity captured as Fabric would match its Paper capture both ways", unmatched.isEmpty(),
                unmatched.isEmpty() ? "all " + made.size() + " match" : "not matching: " + unmatched);
    }

    /** A key only Paper (CraftBukkit) writes into a saved entity. */
    private static boolean paperOnly(String key) {
        return key.startsWith("Bukkit.") || key.startsWith("Paper.") || key.startsWith("Spigot.")
                || key.startsWith("WorldUUID") || key.equals("AgeLocked");
    }

    private static dev.sculptory.core.history.EntityState state(Entity entity, CompoundTag nbt) {
        return new dev.sculptory.core.history.EntityState(McEntities.typeId(entity), entity.getX(), entity.getY(),
                entity.getZ(), McEntities.bytes(nbt));
    }

    /**
     * An item frame holding a diamond, moved with its wall 8 east into air; another plugin turns the source frame's
     * restore away once and the move is undone: the undo puts the air back at the destination, so the moved frame has
     * no wall there any more. It is not put back (it would pop within 100 ticks and drop its item, and the entry's redo
     * would then restore it with the item: a duplicate): the step counts a conflict and keeps the frame's data. 120
     * ticks later no frame and no dropped item are there; the redo then puts the wall and the frame back, one diamond in
     * all.
     */
    private static CompletableFuture<Void> frameIntoAir(SelfTestRun r, Refuser refuser, int x, int z) {
        net.minecraft.core.BlockPos wall = new net.minecraft.core.BlockPos(x + 2, 101, z + 2);
        set(r, wall, Blocks.STONE.defaultBlockState());
        ItemFrame frame = new ItemFrame(r.level, wall.south(), net.minecraft.core.Direction.SOUTH);
        frame.setItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND), false);
        r.level.tryAddFreshEntityWithPassengers(frame, CreatureSpawnEvent.SpawnReason.CUSTOM);
        UUID source = frame.getUUID();
        Box from = box(x + 2, 101, z + 2, x + 2, 101, z + 3);
        Box area = box(x, 100, z, x + 14, 104, z + 6);
        return move(r, from, new BlockPos(8, 0, 0)).thenCompose(result -> {
            int moved = frames(r, area);
            if (moved != 1) throw new IllegalStateException(moved + " frames after the move");
            refuser.refuseOnce.add(source);
            refuser.take();
            return r.ticks(5).thenCompose(v -> r.undo());
        }).thenCompose(result -> {
            refuser.refuseOnce.clear();
            boolean wallGone = r.level.getBlockState(new net.minecraft.core.BlockPos(x + 10, 101, z + 2)).isAir();
            r.info("entities: a frame moved into air, its restore refused, undone", frames(r, area) + " frames, "
                    + "the destination wall gone " + wallGone + ", " + result.skippedProtected() + " refused, "
                    + result.skippedConflicts() + " conflicts; spawn events " + refuser.take());
            return r.ticks(120).thenApply(v -> result);
        }).thenCompose(result -> {
            int framesLeft = frames(r, area);
            int drops = drops(r, area);
            r.record("entities: a moved frame whose wall the undo removed is not put back to pop and drop its item",
                    framesLeft == 0 && drops == 0 && result.skippedConflicts() >= 1,
                    framesLeft + " frames and " + drops + " dropped items 120 ticks after the undo, "
                            + result.skippedConflicts() + " conflicts");
            return r.redo();
        }).thenCompose(result -> r.ticks(5).thenApply(v -> result)).thenAccept(result -> {
            List<ItemFrame> back = r.level.getEntitiesOfClass(ItemFrame.class, aabb(area), ItemFrame::isAlive);
            int diamonds = 0;
            for (net.minecraft.world.entity.item.ItemEntity item : r.level.getEntitiesOfClass(
                    net.minecraft.world.entity.item.ItemEntity.class, aabb(area))) {
                if (item.getItem().is(net.minecraft.world.item.Items.DIAMOND)) diamonds += item.getItem().getCount();
            }
            for (ItemFrame f : back) diamonds += f.getItem().is(net.minecraft.world.item.Items.DIAMOND) ? 1 : 0;
            r.record("entities: the entry's redo puts the wall and the frame back, one diamond in all",
                    back.size() == 1 && diamonds == 1, back.size() + " frames, " + diamonds + " diamonds in all ("
                            + result.outcome() + ", " + result.skippedConflicts() + " conflicts)");
        });
    }

    /**
     * A pig moved 8 east into a column of stone (the move writes air there); another plugin turns the source pig's
     * restore away once and the move is undone: the undo puts the stone back, so the moved pig is not put back into
     * it (it would suffocate): a conflict, the data kept. 60 ticks later no pig is there; the redo then puts the air
     * and the pig back, unhurt.
     */
    private static CompletableFuture<Void> pigIntoStone(SelfTestRun r, Refuser refuser, int x, int z) {
        for (int y = 101; y <= 102; y++) {
            set(r, new net.minecraft.core.BlockPos(x + 10, y, z + 2), Blocks.STONE.defaultBlockState());
        }
        Pig pig = new Pig(EntityType.PIG, r.level);
        pig.moveTo(x + 2.5, 101, z + 2.5, 0f, 0f);
        pig.setNoAi(true);
        r.level.tryAddFreshEntityWithPassengers(pig, CreatureSpawnEvent.SpawnReason.CUSTOM);
        UUID source = pig.getUUID();
        Box from = box(x + 2, 101, z + 2, x + 2, 102, z + 2);
        Box area = box(x, 100, z, x + 14, 104, z + 6);
        return move(r, from, new BlockPos(8, 0, 0)).thenCompose(result -> {
            refuser.refuseOnce.add(source);
            refuser.take();
            return r.ticks(5).thenCompose(v -> r.undo());
        }).thenCompose(result -> {
            refuser.refuseOnce.clear();
            List<Pig> there = r.level.getEntitiesOfClass(Pig.class, aabb(area), Pig::isAlive);
            boolean stone = r.level.getBlockState(new net.minecraft.core.BlockPos(x + 10, 101, z + 2)).is(Blocks.STONE);
            r.info("entities: a pig moved into stone, its restore refused, undone", there.size() + " pigs, "
                    + "the stone back " + stone + ", " + result.skippedProtected() + " refused, "
                    + result.skippedConflicts() + " conflicts");
            return r.ticks(60).thenApply(v -> result);
        }).thenCompose(result -> {
            List<Pig> there = r.level.getEntitiesOfClass(Pig.class, aabb(area), Pig::isAlive);
            boolean hurt = there.stream().anyMatch(p -> p.getHealth() < p.getMaxHealth());
            r.record("entities: a moved pig whose cell the undo filled with stone is not put back into it",
                    there.isEmpty() && result.skippedConflicts() >= 1, there.size() + " pigs (hurt " + hurt
                            + ") 60 ticks after the undo, " + result.skippedConflicts() + " conflicts");
            return r.redo();
        }).thenCompose(result -> r.ticks(5).thenApply(v -> result)).thenAccept(result -> {
            List<Pig> there = r.level.getEntitiesOfClass(Pig.class, aabb(area), Pig::isAlive);
            boolean unhurt = there.size() == 1 && there.get(0).getHealth() >= there.get(0).getMaxHealth();
            r.record("entities: the entry's redo puts the air and the pig back, unhurt", unhurt, there.size()
                    + " pigs (" + result.outcome() + ", " + result.skippedConflicts() + " conflicts)");
        });
    }

    private static int frames(SelfTestRun r, Box box) {
        return r.level.getEntitiesOfClass(ItemFrame.class, aabb(box), ItemFrame::isAlive).size();
    }

    private static int drops(SelfTestRun r, Box box) {
        return r.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, aabb(box),
                net.minecraft.world.entity.item.ItemEntity::isAlive).size();
    }

    // ================================================================== helpers

    private static CompletableFuture<JobResult> paste(SelfTestRun r, Clipboard clipboard, BlockPos origin) {
        UUID id = r.service.clipboards().install(r.player.getUUID(), clipboard).id();
        OpSpec.Paste paste = new OpSpec.Paste(new SourceRef.Clipboard(id), origin, Transform.IDENTITY,
                PasteOptions.DEFAULT, Symmetry.NONE);
        return r.job("paste", listener -> r.service.run(r.player, paste, RunOptions.DEFAULT, listener));
    }

    private static CompletableFuture<JobResult> move(SelfTestRun r, Box source, BlockPos offset) {
        int air = Block.getId(Blocks.AIR.defaultBlockState());
        OpSpec.Move move = new OpSpec.Move(new Region.Cuboid(source), offset, Transform.IDENTITY, new Pattern.Single(air),
                EntityFilter.ALL);
        return r.job("move", listener -> r.service.run(r.player, move, RunOptions.DEFAULT, listener));
    }

    /** Whether {@code entity} is in the run's world: added, not removed, the entity its UUID finds. */
    private static boolean inWorld(SelfTestRun r, Entity entity) {
        return !entity.isRemoved() && r.level.getEntity(entity.getUUID()) == entity;
    }

    /** The entity as history captures it, without what the game changes by itself (EntityNbt.VOLATILE_KEYS). */
    private static CompoundTag comparable(Mob mob) {
        CompoundTag nbt = McEntities.save(mob, EntityFilter.ALL);
        for (String key : EntityNbt.VOLATILE_KEYS) nbt.remove(key);
        return nbt;
    }

    private static void set(SelfTestRun r, net.minecraft.core.BlockPos pos, BlockState state) {
        if (r.level.getBlockState(pos) == state) return;
        PaperAccess.setBlockWithoutCallbacks(r.level, pos, state);
        r.level.getChunkSource().blockChanged(pos);
    }
}
