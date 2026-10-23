package dev.sculptory.paper.devtest;

import static dev.sculptory.paper.devtest.SelfTestRun.aabb;
import static dev.sculptory.paper.devtest.SelfTestRun.box;

import dev.sculptory.core.Box;
import dev.sculptory.core.history.EntityChange;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.mc.world.McEntities;
import dev.sculptory.paper.SculptoryPlugin;
import dev.sculptory.paper.engine.SelfTestEntities;
import dev.sculptory.paper.nms.PaperAccess;
import dev.sculptory.protocol.v2.BuilderPower;
import dev.sculptory.server.engine.JobResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.util.Vector;

/**
 * Item frames, paintings and armor stands in the history on Paper, after the shared HangingEntitiesGameTest.
 *
 * <p><b>Builder mode</b> ({@code BuilderEntityListener}, through the game's own paths: the item's use on a block, the
 * interact events the packet handler fires before the interaction, a punch): a frame hung, filled, emptied and broken,
 * a painting hung and broken, an armor stand placed, dressed and broken are each one step, undone and redone exactly
 * with no item dropped; a placement out of builder reach, and one another plugin cancels, place and record nothing.
 *
 * <p><b>Supports</b> ({@code HangingSupport}, the same engine code as the loaders): a Fill of air over a framed wall
 * takes the frame into its step and the undo brings it back with its item; a frame on a wall swapped for dirt, and one
 * on a wall the Fill did not reach, stay; undoing the Fill that built a wall takes its two frames into that step and the
 * redo hangs them again. No item drops, also after the game's own check of the frames.
 */
final class HangingChecks {
    private static final int FLOOR = 100;
    /** More than the 100 ticks between a hanging entity's own checks that it still hangs. */
    private static final int GAME_CHECK_TICKS = 110;

    private HangingChecks() {
    }

    /** Cancels the hanging place events of the self-test player while {@link #cancelling} (another plugin). */
    static final class Canceller implements Listener {
        boolean cancelling;

        @EventHandler(priority = EventPriority.HIGH)
        public void cancel(HangingPlaceEvent event) {
            if (cancelling) event.setCancelled(true);
        }
    }

    // ============================================================================================ builder mode

    static CompletableFuture<Void> builderMode(SelfTestRun r) {
        int x0 = r.baseX + 640, z0 = r.baseZ; // chunk-aligned, beside the other groups' areas
        Box area = box(x0, FLOOR - 1, z0, x0 + 31, FLOOR + 8, z0 + 31);
        SelfTestRun.Area claimed = r.claim(area);
        floor(r, x0, z0);
        SelfTestEntities.Handle handle = SelfTestEntities.listen(SculptoryPlugin.instance(), r.service);
        r.cleanup(handle::close);
        Canceller canceller = new Canceller();
        Bukkit.getPluginManager().registerEvents(canceller, SculptoryPlugin.instance());
        r.cleanup(() -> HandlerList.unregisterAll(canceller));
        r.record("builder entities: the plugin's own listener hears the place events",
                SelfTestEntities.pluginListens(SculptoryPlugin.instance()), "");
        Scene s = new Scene(r, handle, area);
        // Paper loads a chunk's entities a little after the chunk: look again once they are in.
        return r.ticks(5).thenCompose(v -> {
            if (claimed.hadEntities()) throw new IllegalStateException("the area holds entities already");
            r.service.builderPowers(r.player, BuilderPower.KEEP_SHAPE.bit());
            r.cleanup(() -> r.service.builderPowers(r.player, 0));
            return frame(s, x0, z0);
        }).thenCompose(v -> painting(s, x0 + 10, z0))
                .thenCompose(v -> stand(s, x0 + 16, z0))
                .thenAccept(v -> refused(s, canceller, x0 + 22, z0))
                .thenAccept(v -> betweenTheEvents(s, x0 + 26, z0))
                .thenCompose(v -> r.ticks(GAME_CHECK_TICKS))
                .thenAccept(v -> r.record("builder entities: no item dropped after the game's own check",
                        s.items().isEmpty(), s.items().toString()));
    }

    /** A frame hung, filled, emptied by a punch and broken by another; undone back to the bare wall, redone. */
    private static CompletableFuture<Void> frame(Scene s, int x0, int z0) {
        SelfTestRun r = s.r;
        BlockPos wall = wall(r, x0 + 6, z0 + 3, 3);
        r.stand(x0 + 6.5, FLOOR + 1, z0 + 5.5, 180f, new ItemStack(Items.ITEM_FRAME));
        List<String> states = new ArrayList<>();
        s.useOn(wall, Direction.SOUTH);
        List<ItemFrame> frames = s.all(ItemFrame.class);
        r.record("builder entities: hanging a frame is one step", frames.size() == 1
                && s.labels().get(0).equals("Place · item frame"), frames + ", history " + s.labels());
        if (frames.size() != 1) return CompletableFuture.completedFuture(null);
        ItemFrame frame = frames.get(0);
        states.add(s.describe());
        hold(r, Items.DIAMOND);
        s.interact(frame);
        states.add(s.describe());
        hold(r, Items.AIR);
        s.attack(frame);
        states.add(s.describe());
        s.attack(frame);
        states.add(s.describe());
        r.record("builder entities: filling, emptying and breaking the frame are three steps",
                frame.isRemoved() && s.labels().subList(0, 4).equals(List.of("Break · item frame", "Change · item frame",
                        "Change · item frame", "Place · item frame")), "history " + s.labels());
        return s.undoRedo("the frame", states);
    }

    /** A painting hung and broken: two steps, undone and redone exactly. */
    private static CompletableFuture<Void> painting(Scene s, int x0, int z0) {
        SelfTestRun r = s.r;
        BlockPos wall = wall(r, x0 + 2, z0 + 3, 4);
        r.stand(x0 + 2.5, FLOOR + 1, z0 + 5.5, 180f, new ItemStack(Items.PAINTING));
        List<String> states = new ArrayList<>();
        s.useOn(wall.above(), Direction.SOUTH);
        List<Painting> paintings = s.all(Painting.class);
        r.record("builder entities: hanging a painting is one step", paintings.size() == 1
                && s.labels().get(0).equals("Place · painting"), paintings + ", history " + s.labels());
        if (paintings.size() != 1) return CompletableFuture.completedFuture(null);
        states.add(s.describe());
        hold(r, Items.AIR);
        s.attack(paintings.get(0));
        states.add(s.describe());
        r.record("builder entities: breaking the painting is one step", paintings.get(0).isRemoved()
                && s.labels().get(0).equals("Break · painting"), "history " + s.labels());
        return s.undoRedo("the painting", states);
    }

    /** An armor stand placed, given a helmet and broken in Creative: three steps, undone and redone exactly. */
    private static CompletableFuture<Void> stand(Scene s, int x0, int z0) {
        SelfTestRun r = s.r;
        r.stand(x0 + 2.5, FLOOR + 1, z0 + 5.5, 180f, new ItemStack(Items.ARMOR_STAND));
        List<String> states = new ArrayList<>();
        s.useOn(new BlockPos(x0 + 2, FLOOR, z0 + 3), Direction.UP);
        List<ArmorStand> stands = s.all(ArmorStand.class);
        r.record("builder entities: placing an armor stand is one step", stands.size() == 1
                && s.labels().get(0).equals("Place · armor stand"), stands + ", history " + s.labels());
        if (stands.size() != 1) return CompletableFuture.completedFuture(null);
        ArmorStand stand = stands.get(0);
        states.add(s.describe());
        hold(r, Items.IRON_HELMET);
        s.interactAt(stand, new Vec3(0, 1.8, 0));
        states.add(s.describe());
        hold(r, Items.AIR);
        s.attack(stand);
        states.add(s.describe());
        r.record("builder entities: dressing and breaking the stand are two steps",
                stand.isRemoved() && s.labels().subList(0, 2).equals(List.of("Break · armor stand",
                        "Change · armor stand")), "history " + s.labels());
        return s.undoRedo("the armor stand", states);
    }

    /**
     * What happens between an action's event and the end of the tick, when the reads are compared: an armor stand that
     * only moved, sped up and fell a little (as one entity tick does) is no step, while one given a helmet with the same
     * drift is one; a frame the player right-clicked that then drops off by itself (its wall gone: the game's
     * HangingBreakEvent with a physics cause) is not recorded as the player's Break.
     */
    private static void betweenTheEvents(Scene s, int x0, int z0) {
        SelfTestRun r = s.r;
        r.stand(x0 + 2.5, FLOOR + 1, z0 + 5.5, 180f, new ItemStack(Items.ARMOR_STAND));
        s.useOn(new BlockPos(x0 + 2, FLOOR, z0 + 3), Direction.UP);
        List<ArmorStand> stands = s.all(ArmorStand.class).stream().filter(e -> e.getBlockX() == x0 + 2).toList();
        if (stands.size() != 1) {
            r.record("builder entities: an armor stand for the drift checks", false, stands.toString());
            return;
        }
        ArmorStand stand = stands.get(0);
        int before = s.labels().size();
        hold(r, Items.AIR);
        Bukkit.getPluginManager().callEvent(new PlayerInteractAtEntityEvent(r.player.getBukkitEntity(),
                stand.getBukkitEntity(), new Vector(0, 1, 0), org.bukkit.inventory.EquipmentSlot.HAND));
        drift(stand);
        s.handle.settle();
        r.record("builder entities: a stand that only drifted between its event and the tick's end is no step",
                s.labels().size() == before, "history " + s.labels());
        hold(r, Items.IRON_HELMET);
        Bukkit.getPluginManager().callEvent(new PlayerInteractAtEntityEvent(r.player.getBukkitEntity(),
                stand.getBukkitEntity(), new Vector(0, 1.8, 0), org.bukkit.inventory.EquipmentSlot.HAND));
        stand.interactAt(r.player, new Vec3(0, 1.8, 0), InteractionHand.MAIN_HAND);
        drift(stand);
        s.handle.settle();
        r.record("builder entities: a stand given a helmet is one step, whatever it drifted",
                s.labels().size() == before + 1 && s.labels().get(0).equals("Change · armor stand"),
                "history " + s.labels());

        BlockPos wall = wall(r, x0 + 4, z0 + 3, 1);
        ItemFrame frame = hang(r, wall, Items.APPLE);
        int framed = s.labels().size();
        hold(r, Items.AIR);
        Bukkit.getPluginManager().callEvent(new PlayerInteractEntityEvent(r.player.getBukkitEntity(),
                frame.getBukkitEntity(), org.bukkit.inventory.EquipmentSlot.HAND));
        // Then its wall goes and the game drops it off, before the reads are compared.
        set(r, wall, Blocks.AIR.defaultBlockState());
        Bukkit.getPluginManager().callEvent(new org.bukkit.event.hanging.HangingBreakEvent(
                (org.bukkit.entity.Hanging) frame.getBukkitEntity(),
                org.bukkit.event.hanging.HangingBreakEvent.RemoveCause.PHYSICS));
        frame.discard();
        s.handle.settle();
        r.record("builder entities: a frame that dropped off by itself after the player's click is not their Break",
                s.labels().size() == framed, "history " + s.labels());

        // Broken by something that is not a player (here the armor stand stands in for an arrow or a mob).
        BlockPos second = wall(r, x0 + 4, z0 + 1, 1);
        ItemFrame other = hang(r, second, Items.APPLE);
        Bukkit.getPluginManager().callEvent(new PlayerInteractEntityEvent(r.player.getBukkitEntity(),
                other.getBukkitEntity(), org.bukkit.inventory.EquipmentSlot.HAND));
        Bukkit.getPluginManager().callEvent(new org.bukkit.event.hanging.HangingBreakByEntityEvent(
                (org.bukkit.entity.Hanging) other.getBukkitEntity(), stand.getBukkitEntity(),
                org.bukkit.event.hanging.HangingBreakEvent.RemoveCause.ENTITY));
        other.discard();
        s.handle.settle();
        r.record("builder entities: a frame another entity broke after the player's click is not their Break",
                s.labels().size() == framed, "history " + s.labels());
    }

    /** What one entity tick may change by itself: where it stands, its motion, its fall. */
    private static void drift(Entity entity) {
        entity.setPos(entity.getX() + 0.01, entity.getY() - 0.02, entity.getZ());
        entity.setDeltaMovement(0, -0.08, 0);
        entity.fallDistance = 0.08f;
    }

    /** Out of builder reach, or cancelled by another plugin: nothing placed, nothing recorded. */
    private static void refused(Scene s, Canceller canceller, int x0, int z0) {
        SelfTestRun r = s.r;
        BlockPos wall = wall(r, x0 + 2, z0 + 3, 3);
        int before = s.labels().size();
        r.stand(x0 + 2.5, FLOOR + 1, z0 + 5.5, 180f, new ItemStack(Items.ITEM_FRAME));
        canceller.cancelling = true;
        try {
            s.useOn(wall, Direction.SOUTH);
        } finally {
            canceller.cancelling = false;
        }
        r.record("builder entities: a frame another plugin cancels is neither placed nor recorded",
                s.all(ItemFrame.class).isEmpty() && s.labels().size() == before,
                s.all(ItemFrame.class) + ", history " + s.labels());
        // Out of builder reach (64 blocks and the margin): refused like a block there, so the event is cancelled.
        r.stand(x0 + 2.5, FLOOR + 1, z0 + 100.5, 180f, new ItemStack(Items.ITEM_FRAME));
        s.useOn(wall, Direction.SOUTH);
        r.record("builder entities: a frame out of builder reach is neither placed nor recorded",
                s.all(ItemFrame.class).isEmpty() && s.labels().size() == before,
                s.all(ItemFrame.class) + ", history " + s.labels());
    }

    // ============================================================================================ supports

    static CompletableFuture<Void> supports(SelfTestRun r) {
        int x0 = r.baseX + 640, z0 = r.baseZ + 32;
        Box area = box(x0, FLOOR - 1, z0, x0 + 31, FLOOR + 8, z0 + 31);
        SelfTestRun.Area claimed = r.claim(area);
        floor(r, x0, z0);
        Scene s = new Scene(r, null, area);
        BlockPos wall = wall(r, x0 + 6, z0 + 3, 1);
        BlockPos farWall = wall(r, x0 + 12, z0 + 3, 1);
        BlockPos swapped = wall(r, x0 + 18, z0 + 3, 1);
        Box built = box(x0 + 24, FLOOR + 1, z0 + 3, x0 + 25, FLOOR + 2, z0 + 3);
        UUID[] ids = new UUID[5];
        String[] hung = new String[5];
        return r.ticks(5).thenCompose(v -> {
            if (claimed.hadEntities()) throw new IllegalStateException("the area holds entities already");
            ItemFrame frame = hang(r, wall, Items.DIAMOND);
            ItemFrame far = hang(r, farWall, Items.EMERALD);
            ItemFrame kept = hang(r, swapped, Items.GOLD_INGOT);
            ids[0] = frame.getUUID();
            ids[1] = far.getUUID();
            ids[2] = kept.getUUID();
            hung[0] = state(frame);
            hung[1] = state(far);
            return r.fill(cell(wall), Block.getId(Blocks.AIR.defaultBlockState()));
        }).thenCompose(fill -> {
            HistoryEntry entry = s.entries().get(0);
            r.record("supports: a Fill of air over a framed wall takes the frame into its step",
                    find(r, ids[0]) == null && entry.record().entities().size() == 1
                            && entry.record().entities().get(0).after() == null,
                    entry.label() + ", " + entry.record().entities());
            r.record("supports: the frame on a wall the Fill did not reach is untouched",
                    hung[1].equals(state(find(r, ids[1]))), "");
            return r.fill(cell(swapped), Block.getId(Blocks.DIRT.defaultBlockState()));
        }).thenCompose(fill -> {
            r.record("supports: a frame on a wall swapped for dirt stays, nothing recorded",
                    find(r, ids[2]) != null && s.entries().get(0).record().entities().isEmpty(),
                    s.entries().get(0).label());
            return r.undo();
        }).thenCompose(undo -> r.undo()).thenCompose(undo -> {
            Entity back = find(r, ids[0]);
            r.record("supports: the Fill's undo hangs the frame again with its diamond",
                    back != null && hung[0].equals(state(back)) && r.level.getBlockState(wall).is(Blocks.STONE),
                    "frame " + back + ", wall " + r.level.getBlockState(wall));
            return r.fill(built, Block.getId(Blocks.STONE.defaultBlockState()));
        }).thenCompose(fill -> {
            ItemFrame a = hang(r, new BlockPos(built.min().x(), FLOOR + 1, built.min().z()), Items.APPLE);
            ItemFrame c = hang(r, new BlockPos(built.max().x(), FLOOR + 2, built.min().z()), Items.STICK);
            ids[3] = a.getUUID();
            ids[4] = c.getUUID();
            hung[3] = state(a);
            hung[4] = state(c);
            return r.undo();
        }).thenCompose(undo -> {
            List<EntityChange> amended = r.service.historyService().redoEntries(r.player.getUUID()).get(0)
                    .record().entities();
            r.record("supports: undoing the wall takes its two frames into the wall's step",
                    find(r, ids[3]) == null && find(r, ids[4]) == null && amended.size() == 2
                            && amended.stream().allMatch(c -> c.before() == null && c.after() != null),
                    amended.toString());
            return r.redo();
        }).thenCompose(redo -> {
            Entity a = find(r, ids[3]), c = find(r, ids[4]);
            r.record("supports: the redo hangs both frames again",
                    a != null && c != null && hung[3].equals(state(a)) && hung[4].equals(state(c)), a + ", " + c);
            return r.undo();
        }).thenCompose(undo -> {
            r.record("supports: undone again, the frames go again", find(r, ids[3]) == null
                    && find(r, ids[4]) == null, "");
            return r.ticks(GAME_CHECK_TICKS);
        }).thenAccept(v -> r.record("supports: no item dropped, also after the game's own check",
                s.items().isEmpty(), s.items().toString()));
    }

    // ============================================================================================ helpers

    /** What a check acts on: the run, the builder listener of its service (or null), and its area. */
    private static final class Scene {
        final SelfTestRun r;
        final SelfTestEntities.Handle handle;
        final Box area;

        Scene(SelfTestRun r, SelfTestEntities.Handle handle, Box area) {
            this.r = r;
            this.handle = handle;
            this.area = area;
        }

        /** The held item's use on {@code face} of {@code block}, as the game runs it for the use-on packet. */
        void useOn(BlockPos block, Direction face) {
            Vec3 hit = Vec3.atCenterOf(block).relative(face, 0.5);
            r.player.gameMode.useItemOn(r.player, r.level, r.player.getMainHandItem(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(hit, face, block, false));
            handle.settle();
        }

        /** A right-click on {@code entity}: the event the packet handler fires, then the interaction. */
        void interact(Entity entity) {
            PlayerInteractEntityEvent event = new PlayerInteractEntityEvent(r.player.getBukkitEntity(),
                    entity.getBukkitEntity(), org.bukkit.inventory.EquipmentSlot.HAND);
            Bukkit.getPluginManager().callEvent(event);
            if (!event.isCancelled()) r.player.interactOn(entity, InteractionHand.MAIN_HAND);
            handle.settle();
        }

        /** A right-click at {@code at} on {@code entity} (an armor stand), as the packet handler runs it. */
        void interactAt(Entity entity, Vec3 at) {
            PlayerInteractAtEntityEvent event = new PlayerInteractAtEntityEvent(r.player.getBukkitEntity(),
                    entity.getBukkitEntity(), new Vector(at.x, at.y, at.z), org.bukkit.inventory.EquipmentSlot.HAND);
            Bukkit.getPluginManager().callEvent(event);
            if (!event.isCancelled()) entity.interactAt(r.player, at, InteractionHand.MAIN_HAND);
            handle.settle();
        }

        /**
         * A punch: the game's own attack, which fires the damage (and hanging break) events itself. The fake player has
         * no connection, and the attack's hit sound on a living target goes to it: there the damage the attack deals is
         * dealt directly (the same hurt, with the same events).
         */
        void attack(Entity entity) {
            if (r.player.connection == null && entity instanceof net.minecraft.world.entity.LivingEntity) {
                entity.hurt(r.player.damageSources().playerAttack(r.player), 1.0f);
            } else {
                r.player.attack(entity);
            }
            handle.settle();
        }

        <T extends Entity> List<T> all(Class<T> type) {
            return r.level.getEntitiesOfClass(type, aabb(area), e -> !e.isRemoved());
        }

        List<ItemEntity> items() {
            return all(ItemEntity.class);
        }

        List<String> labels() {
            return r.service.history(r.player).undoLabels();
        }

        List<HistoryEntry> entries() {
            return r.service.historyService().undoEntries(r.player.getUUID());
        }

        /** The frames, paintings and armor stands of the area as undo compares them, sorted. */
        String describe() {
            List<String> out = new ArrayList<>();
            for (Entity entity : r.level.getEntities((Entity) null, aabb(area), e -> !e.isRemoved()
                    && (e instanceof ItemFrame || e instanceof Painting || e instanceof ArmorStand))) {
                out.add(entity.getUUID() + "=" + state(entity));
            }
            out.sort(null);
            return out.toString();
        }

        /**
         * Undoes the {@code after.size()} steps one by one (each state checked), then redoes them, all without a
         * conflict or a dropped item; one check each way.
         */
        CompletableFuture<Void> undoRedo(String what, List<String> after) {
            List<String> problems = new ArrayList<>();
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (int i = after.size(); i >= 1; i--) {
                String wanted = i == 1 ? "[]" : after.get(i - 2);
                chain = chain.thenCompose(v -> r.undo()).thenAccept(result -> compare("undo", result, wanted, problems));
            }
            chain = chain.thenAccept(v -> {
                r.record("builder entities: " + what + "'s steps undo exactly", problems.isEmpty(), problems.toString());
                problems.clear();
            });
            for (int i = 1; i <= after.size(); i++) {
                String wanted = after.get(i - 1);
                chain = chain.thenCompose(v -> r.redo()).thenAccept(result -> compare("redo", result, wanted, problems));
            }
            return chain.thenAccept(v -> r.record("builder entities: " + what + "'s steps redo exactly",
                    problems.isEmpty(), problems.toString()));
        }

        private void compare(String step, JobResult result, String wanted, List<String> problems) {
            String now = describe();
            if (result.skippedConflicts() != 0) problems.add(step + " kept " + result.skippedConflicts());
            if (!now.equals(wanted)) problems.add(step + ": " + now + ", expected " + wanted);
            if (!items().isEmpty()) problems.add(step + " dropped " + items());
        }
    }

    /** A stone floor at {@link #FLOOR} with air above, over the 32 × 32 area at (x0, z0). */
    private static void floor(SelfTestRun r, int x0, int z0) {
        for (int x = x0; x <= x0 + 31; x++) {
            for (int z = z0; z <= z0 + 31; z++) {
                set(r, new BlockPos(x, FLOOR, z), Blocks.STONE.defaultBlockState());
                for (int y = FLOOR + 1; y <= FLOOR + 8; y++) set(r, new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
            }
        }
    }

    /** A stone column {@code height} high on the floor: a wall to hang things on its south face. */
    private static BlockPos wall(SelfTestRun r, int x, int z, int height) {
        for (int y = FLOOR + 1; y <= FLOOR + height; y++) set(r, new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
        return new BlockPos(x, FLOOR + 1, z);
    }

    /** A frame holding {@code item} on the south face of {@code wall}, outside any history. */
    private static ItemFrame hang(SelfTestRun r, BlockPos wall, Item item) {
        ItemFrame frame = new ItemFrame(r.level, wall.south(), Direction.SOUTH);
        frame.setItem(new ItemStack(item), false);
        if (!frame.survives() || !r.level.addFreshEntity(frame)) {
            throw new IllegalStateException("a frame could not be hung at " + wall.south().toShortString());
        }
        return frame;
    }

    private static void hold(SelfTestRun r, Item item) {
        r.player.setItemInHand(InteractionHand.MAIN_HAND, item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item));
    }

    private static Entity find(SelfTestRun r, UUID id) {
        Entity entity = r.level.getEntity(id);
        return entity == null || entity.isRemoved() ? null : entity;
    }

    private static Box cell(BlockPos pos) {
        return box(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
    }

    /** The entity's data as undo compares it (without volatile keys); "gone" for none. */
    private static String state(Entity entity) {
        if (entity == null || entity.isRemoved()) return "gone";
        try {
            return dev.sculptory.core.entity.EntityNbt.withoutVolatile(McEntities.fromBytes(McEntities.bytes(
                    McEntities.save(entity, dev.sculptory.core.entity.EntityFilter.ALL)))).toString();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("entity NBT: " + e);
        }
    }

    private static void set(SelfTestRun r, BlockPos pos, BlockState state) {
        if (r.level.getBlockState(pos) == state) return;
        PaperAccess.setBlockWithoutCallbacks(r.level, pos, state);
        r.level.getChunkSource().blockChanged(pos);
    }
}
