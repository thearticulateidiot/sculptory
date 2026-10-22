package dev.sculptory.paper.devtest;

import static dev.sculptory.paper.devtest.SelfTestRun.aabb;
import static dev.sculptory.paper.devtest.SelfTestRun.box;
import static dev.sculptory.paper.devtest.SelfTestRun.ticks;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.mc.world.McEntities;
import dev.sculptory.paper.nms.CraftBukkitKeys;
import dev.sculptory.server.engine.RunOptions;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.decoration.ArmorStand;

/**
 * Entities as Paper saves them: CraftBukkit writes keys of its own into every saved entity. A capture leaves out the
 * world-bound and volatile ones ({@code CraftBukkitKeys.STRIPPED}: the world id, the spawn origin and reason, the tick
 * age, the data version) and keeps the rest, such as a plugin's {@code Bukkit.visibleByDefault}; an entity pasted from
 * data without the stripped keys (a schematic made elsewhere) but with a plugin setting keeps the setting and is undone
 * without a conflict.
 */
final class EntityChecks {
    private EntityChecks() {
    }

    static CompletableFuture<Void> craftBukkitKeys(SelfTestRun r) {
        int x = r.baseX + 320, z = r.baseZ + 4;
        Box area = box(x, 99, z, x + 9, 106, z + 9);
        SelfTestRun.Area claimed = r.claim(area);
        return ticks(5).thenCompose(v -> {
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
            r.record("entities: a capture leaves the world and spawn keys out and keeps plugin settings",
                    !stripped.isEmpty() && left.isEmpty() && kept, "the game saves " + stripped + ", the capture "
                            + "keeps " + left + "; Bukkit.visibleByDefault kept " + kept);
            stand.discard();
            return pasteWithoutPaperKeys(r, x, z);
        });
    }

    /**
     * An armor stand pasted from data with none of the stripped keys (as a schematic from another platform holds it) but
     * with a plugin setting ({@code Bukkit.visibleByDefault: false}), then undone: the placed stand keeps the setting, and
     * the undo compares it with what the paste recorded after placing it, so it must find no conflict and take it away.
     */
    private static CompletableFuture<Void> pasteWithoutPaperKeys(SelfTestRun r, int x, int z) {
        byte[] data = NbtIo.toBytes(NbtCompound.builder().putByte("NoGravity", (byte) 1)
                .putByte("Bukkit.visibleByDefault", (byte) 0).build());
        Clipboard clipboard = Clipboard.builder(r.host.engine().states(), new BlockPos(3, 3, 3)).build()
                .withEntities(List.of(new EntitySnapshot("minecraft:armor_stand", 1.5, 0, 1.5, 0f, 0f, null, data,
                        false)));
        UUID id = r.service.clipboards().install(r.player.getUUID(), clipboard).id();
        BlockPos origin = new BlockPos(x + 5, 101, z + 5);
        OpSpec.Paste paste = new OpSpec.Paste(new SourceRef.Clipboard(id), origin, Transform.IDENTITY,
                PasteOptions.DEFAULT, Symmetry.NONE);
        Box pasted = box(origin.x(), origin.y(), origin.z(), origin.x() + 2, origin.y() + 2, origin.z() + 2);
        return r.job("paste", listener -> r.service.run(r.player, paste, RunOptions.DEFAULT, listener))
                .thenCompose(result -> {
                    List<ArmorStand> placed = r.level.getEntitiesOfClass(ArmorStand.class, aabb(pasted));
                    boolean hidden = placed.size() == 1 && !placed.get(0).getBukkitEntity().isVisibleByDefault();
                    r.record("entities: a pasted stand keeps the plugin setting it was saved with",
                            placed.size() == 1 && hidden, placed.size() + " stands placed, visible by default "
                                    + !hidden + " (" + result.outcome() + ")");
                    // A few ticks, as a player would undo later: CraftBukkit's tick counter moves on meanwhile.
                    return ticks(10).thenCompose(v -> r.undo());
                })
                .thenCompose(result -> ticks(2).thenApply(v -> result))
                .thenAccept(result -> {
                    int left = r.level.getEntitiesOfClass(ArmorStand.class, aabb(pasted), ArmorStand::isAlive).size();
                    r.record("entities: its undo finds no conflict and takes it away",
                            left == 0 && result.skippedConflicts() == 0, result.outcome() + ", " + result.changed()
                                    + " changed, " + result.skippedConflicts() + " conflicts, " + left + " stands left");
                });
    }
}
