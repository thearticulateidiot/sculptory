package dev.sculptory.paper.devtest;

import static dev.sculptory.paper.devtest.SelfTestRun.aabb;
import static dev.sculptory.paper.devtest.SelfTestRun.box;

import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.region.Facing;
import dev.sculptory.mc.builder.BuilderChange;
import dev.sculptory.mc.builder.BuilderSteps;
import dev.sculptory.mc.engine.impl.BuilderService;
import dev.sculptory.paper.SculptoryPlugin;
import dev.sculptory.paper.engine.PaperBuilderSteps;
import dev.sculptory.paper.nms.PaperAccess;
import dev.sculptory.paper.perm.WorldGuardTestRegions;
import dev.sculptory.protocol.v2.BuilderPower;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.server.engine.BuilderOutcome;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Builder mode on Paper (PaperBuilderSteps), after the shared BuilderModeGameTests: a placement and a break are each
 * one step undone exactly and fire Bukkit's block events; without Keep shape the game's own changes around a placement
 * (a fence joining its neighbour) are recorded with it, with Keep shape nothing around reacts (the old fence does not
 * join, sand whose support is broken stays); a placement or break another plugin cancels is refused and changes
 * nothing; a Keep shape bed whose head lands in a WorldGuard region is refused whole and put back without updating the
 * comparator beside its head. Redstone: without Keep shape a redstone block placed beside a lamp lights it (both
 * recorded, the undo exact), with Keep shape the lamp stays off; breaking a redstone block closes the door it powered
 * (recorded, the undo exact), and breaking a lever turns off the lamp behind the block it hangs on (the lever's own
 * removal updates, which Paper drops while a capture is on). A carved pumpkin placed on two snow blocks builds a snow
 * golem (the pattern's cells recorded, the undo exact). A step that throws an {@link Error} leaves the world's block
 * capture empty and its action can still end. Runs on a stone floor of its own at y 100; the area is put back at the
 * end of the run.
 */
final class BuilderChecks {
    private static final int FLOOR = 100;

    private BuilderChecks() {
    }

    /** Bukkit block events seen while the checks run (MONITOR: as a logging plugin sees them). */
    static final class Events implements Listener {
        final List<String> seen = new ArrayList<>();
        /** A cell whose place or break this "plugin" cancels, or null. */
        BlockPos cancelPlaceAt;
        BlockPos cancelBreakAt;

        @EventHandler(priority = EventPriority.HIGHEST)
        public void cancelPlace(BlockPlaceEvent event) {
            if (cancelPlaceAt != null && at(event.getBlock()).equals(cancelPlaceAt)) event.setCancelled(true);
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void cancelBreak(BlockBreakEvent event) {
            if (cancelBreakAt != null && at(event.getBlock()).equals(cancelBreakAt)) event.setCancelled(true);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void placed(BlockPlaceEvent event) {
            String kind = event instanceof BlockMultiPlaceEvent multi
                    ? "multi-place(" + multi.getReplacedBlockStates().size() + ")" : "place";
            seen.add(kind + " " + at(event.getBlock()).toShortString() + (event.isCancelled() ? " cancelled" : ""));
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void broken(BlockBreakEvent event) {
            seen.add("break " + at(event.getBlock()).toShortString() + (event.isCancelled() ? " cancelled" : ""));
        }

        private static BlockPos at(org.bukkit.block.Block block) {
            return new BlockPos(block.getX(), block.getY(), block.getZ());
        }

        List<String> take() {
            List<String> out = List.copyOf(seen);
            seen.clear();
            return out;
        }
    }

    static CompletableFuture<Void> run(SelfTestRun r) {
        int x0 = r.baseX + 368, z0 = r.baseZ; // chunk-aligned: x0 + 16 starts the next chunk
        Box area = box(x0, FLOOR - 1, z0, x0 + 31, FLOOR + 15, z0 + 31);
        r.claim(area);
        for (int x = x0; x <= x0 + 31; x++) {
            for (int z = z0; z <= z0 + 31; z++) {
                set(r, new BlockPos(x, FLOOR, z), Blocks.STONE.defaultBlockState());
                for (int y = FLOOR + 1; y <= FLOOR + 15; y++) set(r, new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
            }
        }
        earlierEntries = r.service.historyService().undoEntries(r.player.getUUID()).size();
        Events events = new Events();
        Bukkit.getPluginManager().registerEvents(events, SculptoryPlugin.instance());
        r.cleanup(() -> HandlerList.unregisterAll(events));
        Box work = box(x0, FLOOR, z0, x0 + 31, FLOOR + 15, z0 + 31);
        return placeAndBreak(r, events, x0, z0, work)
                .thenCompose(v -> neighbours(r, events, x0, z0, work))
                .thenCompose(v -> keepShape(r, x0, z0, work))
                .thenCompose(v -> cancelled(r, events, x0, z0, work))
                .thenCompose(v -> regionAndComparator(r, events, x0, z0, work))
                .thenCompose(v -> lamp(r, x0, z0 + 24, work, false))
                .thenCompose(v -> lamp(r, x0, z0 + 27, work, true))
                .thenCompose(v -> door(r, x0, z0 + 30, work))
                .thenCompose(v -> lever(r, x0 + 16, z0 + 5, work))
                .thenCompose(v -> golem(r, x0 + 16, z0 + 9, work))
                .thenAccept(v -> errorInStep(r, new BlockPos(x0 + 24, FLOOR + 1, z0 + 13)))
                .thenCompose(v -> crafter(r, x0 + 16, z0 + 17, work))
                .thenCompose(v -> tripwire(r, x0 + 14, z0 + 24, work))
                .thenCompose(v -> pastTheCap(r, x0 + 12, z0 + 27, work))
                .thenAccept(v -> errorThroughService(r, x0 + 16, z0 + 30));
    }

    /**
     * A redstone block placed beside a lamp: without Keep shape the lamp lights at once and both cells are the step
     * (undone exactly); with Keep shape only the block is placed and the lamp stays off, also five ticks later.
     */
    private static CompletableFuture<Void> lamp(SelfTestRun r, int x0, int z, Box work, boolean keepShape) {
        String mode = keepShape ? "with Keep shape" : "without Keep shape";
        BlockPos lamp = new BlockPos(x0 + 8, FLOOR + 1, z);
        set(r, lamp, Blocks.REDSTONE_LAMP.defaultBlockState());
        r.stand(x0 + 4.5, FLOOR + 1, z + 0.5, -90f, new ItemStack(Items.REDSTONE_BLOCK, 64));
        int[] before = r.snapshot(work);
        BuilderOutcome placed = place(r, x0 + 7, FLOOR, z, keepShape ? BuilderPower.KEEP_SHAPE.bit() : 0);
        boolean lit = r.level.getBlockState(lamp).getValue(RedstoneLampBlock.LIT);
        if (!keepShape) {
            r.record("builder: a redstone block placed beside a lamp lights it (both recorded)",
                    placed.accepted() && placed.changed() == 2 && lit, placed + ", lamp lit " + lit);
            return r.undo().thenAccept(v -> r.record("builder: the lamp step's undo is exact",
                    r.countDiffering(work, before) == 0 && !r.level.getBlockState(lamp).getValue(RedstoneLampBlock.LIT),
                    r.countDiffering(work, before) + " cells differ, lamp " + r.level.getBlockState(lamp)));
        }
        return r.ticks(5).thenCompose(v -> {
            boolean litLater = r.level.getBlockState(lamp).getValue(RedstoneLampBlock.LIT);
            r.record("builder: with Keep shape the lamp beside a placed redstone block stays off",
                    placed.accepted() && placed.changed() == 1 && !lit && !litLater,
                    placed + ", lamp lit " + lit + ", five ticks later " + litLater);
            return r.undo();
        }).thenAccept(v -> r.record("builder: the Keep shape lamp step's undo is exact",
                r.countDiffering(work, before) == 0, r.countDiffering(work, before) + " cells differ"));
    }

    /**
     * A door held open by a redstone block beside it: breaking the block (without Keep shape) closes the door at once,
     * both halves recorded with the block, and the undo puts all three back exactly.
     */
    private static CompletableFuture<Void> door(SelfTestRun r, int x0, int z, Box work) {
        BlockPos lower = new BlockPos(x0 + 8, FLOOR + 1, z);
        BlockPos power = lower.west();
        BlockState open = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST)
                .setValue(DoorBlock.OPEN, true).setValue(DoorBlock.POWERED, true);
        set(r, lower, open.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        set(r, lower.above(), open.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        set(r, power, Blocks.REDSTONE_BLOCK.defaultBlockState());
        r.stand(x0 + 4.5, FLOOR + 1, z + 0.5, -90f, ItemStack.EMPTY);
        int[] before = r.snapshot(work);
        BuilderOutcome broken = breakCells(r, 4, List.of(power), 0);
        boolean closed = !r.level.getBlockState(lower).getValue(DoorBlock.OPEN)
                && !r.level.getBlockState(lower.above()).getValue(DoorBlock.OPEN);
        r.record("builder: breaking the redstone block beside a door closes it (all three recorded)",
                broken.accepted() && broken.changed() == 3 && closed, broken + ", door closed " + closed);
        return r.undo().thenAccept(v -> r.record("builder: the door step's undo is exact",
                r.countDiffering(work, before) == 0, r.countDiffering(work, before) + " cells differ"));
    }

    /**
     * A powered lever hanging on a stone block with a lit lamp behind it: breaking the lever (without Keep shape) runs
     * the lever's own removal updates, which reach the lamp through the block, and the lamp goes off (its own tick, four
     * ticks later, as in vanilla). The undo puts the lever back (the lamp's later change is not part of the step).
     */
    private static CompletableFuture<Void> lever(SelfTestRun r, int x0, int z, Box work) {
        BlockPos lever = new BlockPos(x0 + 5, FLOOR + 1, z);
        BlockPos wall = lever.east();
        BlockPos lamp = wall.east();
        set(r, wall, Blocks.STONE.defaultBlockState());
        set(r, lever, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.WALL)
                .setValue(LeverBlock.FACING, Direction.WEST).setValue(LeverBlock.POWERED, true));
        set(r, lamp, Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, true));
        r.stand(x0 + 2.5, FLOOR + 1, z + 0.5, -90f, ItemStack.EMPTY);
        BuilderOutcome broken = breakCells(r, 5, List.of(lever), 0);
        return r.ticks(8).thenCompose(v -> {
            boolean off = !r.level.getBlockState(lamp).getValue(RedstoneLampBlock.LIT);
            r.record("builder: breaking a lever turns off the lamp behind the block it hangs on",
                    broken.accepted() && broken.changed() >= 1 && r.level.getBlockState(lever).isAir() && off,
                    broken + ", lamp off " + off);
            return r.undo();
        }).thenAccept(v -> r.record("builder: the lever step's undo puts the lever back",
                r.level.getBlockState(lever).is(Blocks.LEVER), "now " + r.level.getBlockState(lever)));
    }

    /**
     * A carved pumpkin placed (without Keep shape) on two snow blocks builds a snow golem, as a player's placement does:
     * the pumpkin's add callback runs once the place event has let it stand; the pattern's cells are the step and the
     * undo puts the snow back exactly (the golem is an entity the step does not record; it is removed here).
     */
    private static CompletableFuture<Void> golem(SelfTestRun r, int x0, int z, Box work) {
        BlockPos base = new BlockPos(x0 + 5, FLOOR + 1, z);
        set(r, base, Blocks.SNOW_BLOCK.defaultBlockState());
        set(r, base.above(), Blocks.SNOW_BLOCK.defaultBlockState());
        r.stand(x0 + 2.5, FLOOR + 1, z + 0.5, -90f, new ItemStack(Items.CARVED_PUMPKIN, 64));
        int[] before = r.snapshot(work);
        BuilderOutcome placed = place(r, base.getX(), base.getY() + 1, z, 0);
        List<SnowGolem> golems = r.level.getEntitiesOfClass(SnowGolem.class, aabb(work), e -> e.tickCount < 5);
        boolean cleared = r.level.getBlockState(base).isAir() && r.level.getBlockState(base.above()).isAir()
                && r.level.getBlockState(base.above(2)).isAir();
        r.record("builder: a carved pumpkin on two snow blocks builds a snow golem (the pattern recorded)",
                placed.accepted() && placed.changed() == 2 && golems.size() == 1 && cleared,
                placed + ", " + golems.size() + " new snow golems, pattern cleared " + cleared);
        // The golem would wander and leave snow on the floor: it goes before the undo is compared.
        golems.forEach(SnowGolem::discard);
        return r.undo().thenAccept(v -> r.record("builder: the golem step's undo puts the snow back exactly",
                r.countDiffering(work, before) == 0, r.countDiffering(work, before) + " cells differ"));
    }

    /**
     * Paper's steps directly (as {@code BuilderService} drives them): a placement step that throws an {@link Error}
     * after the game placed its block (a stack overflow, here from the placement context) leaves the world's block
     * capture switched off and empty (the next placement anywhere would take those states for its own), Keep shape's
     * callback switch restored; the action still ends (aborted, after a break before it: the physics listener that
     * break registered is gone), gives both cells, and its revert puts them back.
     */
    private static void errorInStep(SelfTestRun r, BlockPos cell) {
        PaperBuilderSteps steps = new PaperBuilderSteps(SculptoryPlugin.instance());
        for (boolean keepShape : new boolean[] {false, true}) {
            boolean physicsBefore = r.level.hasPhysicsEvent;
            // A break first: without Keep shape it runs watched, so the physics listener is registered when the step
            // fails.
            BlockPos broken = cell.east(2);
            set(r, broken, Blocks.STONE.defaultBlockState());
            BuilderSteps.Action action = steps.open(r.level, r.player, keepShape);
            BlockPlaceContext context = new FailingContext(r, cell);
            StackOverflowError thrown = null;
            try {
                action.breakBlock(broken);
                action.place((BlockItem) Items.STONE, context, Blocks.STONE.defaultBlockState(), false);
            } catch (StackOverflowError e) {
                thrown = e;
            }
            boolean clean = !r.level.captureBlockStates && r.level.capturedBlockStates.isEmpty()
                    && r.level.capturedTileEntities.isEmpty() && !r.level.isBlockPlaceCancelled;
            List<BuilderChange> changes = action.abort();
            String left = physicsLeftOn(r, physicsBefore);
            steps.revert(r.level, changes);
            boolean back = r.level.getBlockState(cell).isAir() && r.level.getBlockState(broken).is(Blocks.STONE);
            r.record("builder: a step that throws an Error leaves the block capture empty ("
                            + (keepShape ? "Keep shape" : "no Keep shape") + ")",
                    thrown != null && clean && changes.size() == 2 && back && left == null,
                    "thrown " + thrown + ", capture clean " + clean + ", " + changes.size() + " cells given, put back "
                            + back + ", " + (left == null ? "physics released" : left));
        }
    }

    /**
     * Whether no builder action's physics listener is left registered and the world's physics-event switch is back
     * where it was ({@code before}); null when so, else what is wrong.
     */
    private static String physicsLeftOn(SelfTestRun r, boolean before) {
        long left = Arrays.stream(BlockPhysicsEvent.getHandlerList().getRegisteredListeners())
                .filter(l -> l.getListener().getClass().getName().startsWith(PaperBuilderSteps.class.getName()))
                .count();
        boolean others = BlockPhysicsEvent.getHandlerList().getRegisteredListeners().length > 0;
        boolean switchOk = r.level.hasPhysicsEvent == (before || others);
        if (left == 0 && switchOk) return null;
        return left + " builder physics listeners left, physics event " + r.level.hasPhysicsEvent + " (before "
                + before + ")";
    }

    /**
     * A redstone block placed beside a crafter (without Keep shape): the crafter sets TRIGGERED and its block entity's
     * {@code triggered} in one reaction. The crafter's block entity is saved when it is noted, before it reacts, so the
     * undo puts back an untriggered crafter whose data agrees. The physics listener is gone afterwards.
     */
    private static CompletableFuture<Void> crafter(SelfTestRun r, int x0, int z, Box work) {
        BlockPos crafter = new BlockPos(x0 + 4, FLOOR + 1, z);
        set(r, crafter, Blocks.CRAFTER.defaultBlockState());
        r.stand(x0 + 0.5, FLOOR + 1, z + 0.5, -90f, new ItemStack(Items.REDSTONE_BLOCK, 64));
        int[] before = r.snapshot(work);
        boolean physicsBefore = r.level.hasPhysicsEvent;
        BuilderOutcome placed = place(r, x0 + 3, FLOOR, z, 0);
        String left = physicsLeftOn(r, physicsBefore);
        boolean triggered = r.level.getBlockState(crafter).getValue(CrafterBlock.TRIGGERED);
        r.record("builder: a redstone block beside a crafter triggers it (recorded)",
                placed.accepted() && placed.changed() == 2 && triggered, placed + ", triggered " + triggered);
        r.record("builder: the action's physics listener is gone and the physics switch restored", left == null,
                left == null ? "released" : left);
        return r.undo().thenAccept(v -> {
            boolean data = r.level.getBlockEntity(crafter) instanceof CrafterBlockEntity entity && entity.isTriggered();
            boolean state = r.level.getBlockState(crafter).getValue(CrafterBlock.TRIGGERED);
            r.record("builder: the crafter step's undo puts back an untriggered crafter, block and data alike",
                    r.countDiffering(work, before) == 0 && !state && !data,
                    r.countDiffering(work, before) + " cells differ, TRIGGERED " + state + ", block entity triggered "
                            + data);
        });
    }

    /**
     * A tripwire of ten strings between two hooks, the middle string missing: placing it attaches both hooks (the hooks
     * are 5 and 6 blocks away, set by the string's add callback before their own events), and the undo puts both back
     * unattached. Then, the whole line attached, breaking one hook unattaches the far one (11 blocks away), and the undo
     * puts the far hook back attached.
     */
    private static CompletableFuture<Void> tripwire(SelfTestRun r, int x0, int z, Box work) {
        BlockPos west = new BlockPos(x0 + 1, FLOOR + 1, z);
        BlockPos east = new BlockPos(x0 + 12, FLOOR + 1, z);
        BlockPos middle = new BlockPos(x0 + 6, FLOOR + 1, z);
        set(r, west.west(), Blocks.STONE.defaultBlockState());
        set(r, east.east(), Blocks.STONE.defaultBlockState());
        line(r, west, east, middle, false);
        r.stand(x0 + 6.5, FLOOR + 1, z - 2.5, 0f, new ItemStack(Items.STRING, 64));
        int[] before = r.snapshot(work);
        BuilderOutcome placed = place(r, middle.getX(), FLOOR, z, 0);
        boolean attached = r.level.getBlockState(west).getValue(TripWireHookBlock.ATTACHED)
                && r.level.getBlockState(east).getValue(TripWireHookBlock.ATTACHED);
        r.record("builder: the string completing a tripwire attaches both hooks", placed.accepted() && attached,
                placed + ", both hooks attached " + attached);
        return r.undo().thenCompose(v -> {
            r.record("builder: the string's undo puts both far hooks back unattached",
                    r.countDiffering(work, before) == 0, r.countDiffering(work, before) + " cells differ");
            line(r, west, east, null, true);
            r.stand(x0 + 3.5, FLOOR + 1, z - 2.5, 0f, ItemStack.EMPTY);
            int[] armed = r.snapshot(work);
            BuilderOutcome broken = breakCells(r, 6, List.of(west), 0);
            boolean farLoose = !r.level.getBlockState(east).getValue(TripWireHookBlock.ATTACHED);
            r.record("builder: breaking a hook unattaches the far hook", broken.accepted() && farLoose,
                    broken + ", far hook unattached " + farLoose);
            return r.undo().thenAccept(w -> {
                boolean farBack = r.level.getBlockState(east).getValue(TripWireHookBlock.ATTACHED);
                r.record("builder: the hook's undo puts the far hook back attached",
                        r.countDiffering(work, armed) == 0 && farBack,
                        r.countDiffering(work, armed) + " cells differ, far hook " + r.level.getBlockState(east));
            });
        });
    }

    /** Two facing hooks with strings between them (all but {@code gap}), physics-free, attached or not. */
    private static void line(SelfTestRun r, BlockPos west, BlockPos east, BlockPos gap, boolean attached) {
        BlockState hook = Blocks.TRIPWIRE_HOOK.defaultBlockState().setValue(TripWireHookBlock.ATTACHED, attached);
        set(r, west, hook.setValue(TripWireHookBlock.FACING, Direction.EAST));
        set(r, east, hook.setValue(TripWireHookBlock.FACING, Direction.WEST));
        BlockState string = Blocks.TRIPWIRE.defaultBlockState().setValue(TripWireBlock.EAST, true)
                .setValue(TripWireBlock.WEST, true).setValue(TripWireBlock.ATTACHED, attached);
        for (int x = west.getX() + 1; x < east.getX(); x++) {
            BlockPos cell = new BlockPos(x, west.getY(), west.getZ());
            set(r, cell, cell.equals(gap) ? Blocks.AIR.defaultBlockState() : string);
        }
    }

    /** The cap of cells noted around an action's physics in {@link #pastTheCap}. */
    private static final int SMALL_CAP = 64;

    /**
     * Past the cap of cells noted around an action's physics (lowered to {@value #SMALL_CAP} here): one break message
     * whose first target, a redstone block, sets a redstone line going out and so fills the cap, and whose second
     * target is a stone block apart. Both targets are recorded (they are always noted, cap or not): the step holds
     * both and its undo puts both back.
     */
    private static CompletableFuture<Void> pastTheCap(SelfTestRun r, int x0, int z, Box work) {
        BlockPos power = new BlockPos(x0, FLOOR + 1, z);
        set(r, power, Blocks.REDSTONE_BLOCK.defaultBlockState());
        for (int x = x0 + 1; x <= x0 + 14; x++) {
            set(r, new BlockPos(x, FLOOR + 1, z), Blocks.REDSTONE_WIRE.defaultBlockState()
                    .setValue(RedStoneWireBlock.EAST, RedstoneSide.SIDE)
                    .setValue(RedStoneWireBlock.WEST, RedstoneSide.SIDE)
                    .setValue(RedStoneWireBlock.POWER, Math.max(0, 15 - (x - x0))));
        }
        BlockPos stone = new BlockPos(x0 + 2, FLOOR + 1, z + 2);
        set(r, stone, Blocks.STONE.defaultBlockState());
        r.stand(x0 + 1.5, FLOOR + 1, z + 1.5, -90f, ItemStack.EMPTY);
        BuilderService capped = new BuilderService(r.service, r.host.engine(), r.host.engine().server(),
                new PaperBuilderSteps(SculptoryPlugin.instance(), SMALL_CAP), r.service.executor(),
                r.service.historyService(), System::nanoTime);
        BuilderOutcome broken = capped.breakBlocks(r.player, new C2S.BuilderBreak(seq++, 7,
                List.of(core(power), core(stone)), 0, Symmetry.NONE, false, true));
        boolean gone = r.level.getBlockState(power).isAir() && r.level.getBlockState(stone).isAir();
        r.record("builder: past the noted-cell cap both targets of a break message are recorded",
                broken.accepted() && broken.changed() >= 2 && gone, broken + ", both broken " + gone);
        return r.undo().thenAccept(v -> {
            boolean back = r.level.getBlockState(power).is(Blocks.REDSTONE_BLOCK)
                    && r.level.getBlockState(stone).is(Blocks.STONE);
            r.record("builder: its undo puts both targets back", back, "redstone block " + r.level.getBlockState(power)
                    + ", stone " + r.level.getBlockState(stone));
        });
    }

    private static dev.sculptory.core.BlockPos core(BlockPos pos) {
        return new dev.sculptory.core.BlockPos(pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * Through {@code BuilderService}: a carved pumpkin placed on two snow blocks, the step then failing with a stack
     * overflow. The action is aborted (its deferred physics, which would build the golem, do not run), put back, and no
     * step is added; the error reaches the caller. Whether a golem appeared is reported.
     */
    private static void errorThroughService(SelfTestRun r, int x0, int z) {
        BlockPos base = new BlockPos(x0 + 5, FLOOR + 1, z);
        set(r, base, Blocks.SNOW_BLOCK.defaultBlockState());
        set(r, base.above(), Blocks.SNOW_BLOCK.defaultBlockState());
        r.stand(x0 + 2.5, FLOOR + 1, z + 0.5, -90f, new ItemStack(Items.CARVED_PUMPKIN, 64));
        PaperBuilderSteps paper = new PaperBuilderSteps(SculptoryPlugin.instance());
        BuilderService failing = new BuilderService(r.service, r.host.engine(), r.host.engine().server(),
                new FailingSteps(paper), r.service.executor(), r.service.historyService(), System::nanoTime);
        List<String> labelsBefore = labels(r);
        boolean physicsBefore = r.level.hasPhysicsEvent;
        StackOverflowError thrown = null;
        try {
            failing.place(r.player, new C2S.BuilderPlace(seq++, false, core(base.above()), Facing.UP, 0.5f, 1f, 0.5f,
                    0, Symmetry.NONE));
        } catch (StackOverflowError e) {
            thrown = e;
        }
        String left = physicsLeftOn(r, physicsBefore);
        boolean back = r.level.getBlockState(base).is(Blocks.SNOW_BLOCK)
                && r.level.getBlockState(base.above()).is(Blocks.SNOW_BLOCK)
                && r.level.getBlockState(base.above(2)).isAir();
        int golems = r.level.getEntitiesOfClass(SnowGolem.class, aabb(box(x0, FLOOR, z - 3, x0 + 10, FLOOR + 6, z + 3)),
                e -> e.tickCount < 5).size();
        r.record("builder: an Error after a pumpkin's placement is aborted and put back, without a step",
                thrown != null && back && labels(r).equals(labelsBefore) && left == null,
                "thrown " + thrown + ", blocks back " + back + ", history " + labels(r) + ", "
                        + (left == null ? "physics released" : left));
        r.info("builder: entities after the aborted pumpkin", golems + " snow golems (0: the deferred add callback did "
                + "not run)");
    }

    /** Paper's steps, except that a placement throws a {@link StackOverflowError} once the game has done it. */
    private record FailingSteps(PaperBuilderSteps inner) implements BuilderSteps {
        @Override
        public Action open(ServerLevel world, ServerPlayer player, boolean keepShape) {
            Action action = inner.open(world, player, keepShape);
            return new Action() {
                @Override
                public void emptyContainer(BlockPos pos) {
                    action.emptyContainer(pos);
                }

                @Override
                public Step place(BlockItem item, BlockPlaceContext context, BlockState state, boolean copy) {
                    action.place(item, context, state, copy);
                    throw new StackOverflowError("a builder step failed (test)");
                }

                @Override
                public Step breakBlock(BlockPos pos) {
                    return action.breakBlock(pos);
                }

                @Override
                public List<BuilderChange> finish() {
                    return action.finish();
                }

                @Override
                public List<BuilderChange> abort() {
                    return action.abort();
                }
            };
        }

        @Override
        public void revert(ServerLevel world, List<BuilderChange> changes) {
            inner.revert(world, changes);
        }
    }

    /** A placement context whose player cannot be read once the block is in its cell (a step failing halfway). */
    private static final class FailingContext extends BlockPlaceContext {
        private final BlockPos cell;

        FailingContext(SelfTestRun r, BlockPos cell) {
            super(r.level, r.player, net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.STONE),
                    new BlockHitResult(Vec3.atCenterOf(cell.below()).add(0, 0.5, 0), Direction.UP, cell.below(), false));
            this.cell = cell;
        }

        @Override
        public net.minecraft.world.entity.player.Player getPlayer() {
            if (getLevel().getBlockState(cell).is(Blocks.STONE)) throw new StackOverflowError("a builder step failed (test)");
            return super.getPlayer();
        }
    }

    /** A place on a block's top, then its break: each one step with its event, undone exactly. */
    private static CompletableFuture<Void> placeAndBreak(SelfTestRun r, Events events, int x0, int z0, Box work) {
        r.stand(x0 + 2.5, FLOOR + 1, z0 + 2.5, -90f, new ItemStack(Items.STONE, 64));
        int[] before = r.snapshot(work);
        events.take();
        BlockPos cell = new BlockPos(x0 + 6, FLOOR + 1, z0 + 2);
        BuilderOutcome placed = place(r, x0 + 6, FLOOR, z0 + 2, 0);
        boolean stone = r.level.getBlockState(cell).is(Blocks.STONE);
        List<String> placeEvents = events.take();
        r.record("builder: a placement is one step with its place event", placed.accepted() && placed.changed() == 1
                        && stone && labels(r).equals(List.of("Place · 1 block"))
                        && placeEvents.equals(List.of("place " + cell.toShortString())),
                placed + ", stone " + stone + ", history " + labels(r) + ", events " + placeEvents);
        hold(r, Items.AIR);
        BuilderOutcome broken = breakCells(r, 1, List.of(cell), 0);
        List<String> breakEvents = events.take();
        r.record("builder: a break is one step with its break event", broken.accepted() && broken.changed() == 1
                        && r.level.getBlockState(cell).isAir() && labels(r).size() == 2
                        && breakEvents.equals(List.of("break " + cell.toShortString())),
                broken + ", history " + labels(r) + ", events " + breakEvents);
        return r.undo().thenCompose(v -> r.undo()).thenAccept(v -> {
            int differ = r.countDiffering(work, before);
            r.record("builder: both steps undone exactly", differ == 0 && labels(r).isEmpty(),
                    differ + " cells differ, history " + labels(r));
        });
    }

    /** Without Keep shape the old fence joins the new one, both recorded; the undo takes both back. */
    private static CompletableFuture<Void> neighbours(SelfTestRun r, Events events, int x0, int z0, Box work) {
        BlockPos fence = new BlockPos(x0 + 5, FLOOR + 1, z0 + 5);
        r.level.setBlockAndUpdate(fence, Blocks.OAK_FENCE.defaultBlockState());
        r.stand(x0 + 2.5, FLOOR + 1, z0 + 5.5, -90f, new ItemStack(Items.OAK_FENCE, 64));
        int[] before = r.snapshot(work);
        BuilderOutcome placed = place(r, x0 + 6, FLOOR, z0 + 5, 0);
        boolean joined = r.level.getBlockState(fence).getValue(CrossCollisionBlock.EAST);
        r.record("builder: without Keep shape the neighbour's change is recorded", placed.accepted()
                && placed.changed() == 2 && joined, placed + ", the old fence joined " + joined);
        return r.undo().thenAccept(v -> r.record("builder: its undo takes both back",
                r.countDiffering(work, before) == 0, r.countDiffering(work, before) + " cells differ"));
    }

    /**
     * Keep shape: the old fence does not join, and sand whose support is broken stays (its own fall check, set off
     * when it was put there, has run first); nothing drops; both undo exactly.
     */
    private static CompletableFuture<Void> keepShape(SelfTestRun r, int x0, int z0, Box work) {
        int keep = BuilderPower.KEEP_SHAPE.bit();
        BlockPos fence = new BlockPos(x0 + 5, FLOOR + 1, z0 + 9);
        r.level.setBlockAndUpdate(fence, Blocks.OAK_FENCE.defaultBlockState());
        BlockPos support = new BlockPos(x0 + 10, FLOOR + 1, z0 + 9);
        r.level.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
        r.level.setBlockAndUpdate(support.above(), Blocks.SAND.defaultBlockState());
        r.stand(x0 + 2.5, FLOOR + 1, z0 + 9.5, -90f, new ItemStack(Items.OAK_FENCE, 64));
        int[] before = r.snapshot(work);
        BuilderOutcome placed = place(r, x0 + 6, FLOOR, z0 + 9, keep);
        boolean joined = r.level.getBlockState(fence).getValue(CrossCollisionBlock.EAST);
        r.record("builder: Keep shape places only the block", placed.accepted() && placed.changed() == 1 && !joined,
                placed + ", the old fence joined " + joined);
        hold(r, Items.AIR);
        return r.ticks(5).thenCompose(v -> {
            BuilderOutcome broken = breakCells(r, 2, List.of(support), keep);
            r.record("builder: Keep shape breaks only the block", broken.accepted() && broken.changed() == 1,
                    broken.toString());
            return r.ticks(10);
        }).thenCompose(v -> {
            boolean sand = r.level.getBlockState(support.above()).is(Blocks.SAND);
            int drops = r.level.getEntitiesOfClass(ItemEntity.class, aabb(work), e -> e.tickCount < 40).size();
            r.record("builder: with Keep shape the sand stays and nothing drops", sand && drops == 0,
                    "sand " + sand + ", " + drops + " new item entities");
            return r.undo();
        }).thenCompose(v -> r.undo()).thenAccept(v -> r.record("builder: Keep shape steps undone exactly",
                r.countDiffering(work, before) == 0 && labels(r).isEmpty(), r.countDiffering(work, before)
                        + " cells differ, history " + labels(r)));
    }

    /** A placement and a break another plugin cancels (its block event) are refused and change nothing. */
    private static CompletableFuture<Void> cancelled(SelfTestRun r, Events events, int x0, int z0, Box work) {
        r.stand(x0 + 2.5, FLOOR + 1, z0 + 13.5, -90f, new ItemStack(Items.STONE, 64));
        int[] before = r.snapshot(work);
        BlockPos cell = new BlockPos(x0 + 6, FLOOR + 1, z0 + 13);
        events.cancelPlaceAt = cell;
        BuilderOutcome placed = place(r, x0 + 6, FLOOR, z0 + 13, 0);
        events.cancelPlaceAt = null;
        r.record("builder: a placement a plugin cancels is refused and put back",
                placed.refusal() == BuilderOutcome.Refusal.PROTECTED && placed.changed() == 0
                        && r.countDiffering(work, before) == 0 && labels(r).isEmpty(),
                placed + ", " + r.countDiffering(work, before) + " cells differ, history " + labels(r));
        hold(r, Items.AIR);
        BlockPos floor = new BlockPos(x0 + 6, FLOOR, z0 + 13);
        events.cancelBreakAt = floor;
        BuilderOutcome broken = breakCells(r, 3, List.of(floor), 0);
        events.cancelBreakAt = null;
        r.record("builder: a break a plugin cancels is refused and changes nothing",
                broken.refusal() == BuilderOutcome.Refusal.PROTECTED && broken.changed() == 0
                        && r.countDiffering(work, before) == 0 && labels(r).isEmpty(),
                broken + ", " + r.countDiffering(work, before) + " cells differ, history " + labels(r));
        events.take();
        return CompletableFuture.completedFuture(null);
    }

    /**
     * A Keep shape bed whose head lands in a WorldGuard region (the head's cell only) is refused whole: both halves
     * stay air, no step is added, and the comparator beside the head (reading a lectern with a book, set without
     * updates, so off) gets no update from the revert: no tick is scheduled and it is still off 10 ticks later.
     */
    private static CompletableFuture<Void> regionAndComparator(SelfTestRun r, Events events, int x0, int z0, Box work) {
        if (!r.worldGuard()) {
            r.info("builder: WorldGuard region", "skipped: WorldGuard is not installed");
            return CompletableFuture.completedFuture(null);
        }
        BlockPos foot = new BlockPos(x0 + 15, FLOOR + 1, z0 + 20);
        BlockPos head = foot.east();
        BlockPos comparator = head.north();
        BlockPos lectern = comparator.north();
        String id = r.region("builder_head");
        WorldGuardTestRegions.add(r.level, id, head.getX(), head.getY(), head.getZ(), head.getX(), head.getY(),
                head.getZ());
        r.level.setBlockAndUpdate(lectern, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.HAS_BOOK, true));
        if (r.level.getBlockEntity(lectern) instanceof LecternBlockEntity book) {
            book.setBook(new ItemStack(Items.WRITABLE_BOOK));
        }
        BlockState reading = Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.NORTH);
        r.level.setBlock(comparator, reading, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        boolean tickBefore = r.level.getBlockTicks().hasScheduledTick(comparator, Blocks.COMPARATOR);
        r.stand(x0 + 12.5, FLOOR + 1, z0 + 20.5, -90f, new ItemStack(Items.RED_BED, 1));
        int[] before = r.snapshot(work);
        events.take();
        BuilderOutcome placed = place(r, foot.getX(), FLOOR, foot.getZ(), BuilderPower.KEEP_SHAPE.bit());
        List<String> seen = events.take();
        boolean bothAir = r.level.getBlockState(foot).isAir() && r.level.getBlockState(head).isAir();
        boolean ticked = r.level.getBlockTicks().hasScheduledTick(comparator, Blocks.COMPARATOR);
        r.record("builder: a bed whose head is in a WorldGuard region is refused whole and put back",
                placed.refusal() == BuilderOutcome.Refusal.PROTECTED && placed.changed() == 0 && bothAir
                        && r.countDiffering(work, before) == 0 && labels(r).isEmpty(),
                placed + ", both halves air " + bothAir + ", " + r.countDiffering(work, before) + " cells differ, "
                        + "events " + seen);
        r.record("builder: the revert leaves the comparator alone", !tickBefore && !ticked,
                "tick scheduled before " + tickBefore + ", after the revert " + ticked);
        return r.ticks(10).thenAccept(v -> {
            boolean powered = r.level.getBlockState(comparator).getValue(ComparatorBlock.POWERED);
            r.record("builder: the comparator is still off 10 ticks later", !powered, "powered " + powered);
        });
    }

    private static int seq = 1;

    private static BuilderOutcome place(SelfTestRun r, int x, int y, int z, int powers) {
        return r.service.builderPlace(r.player, new C2S.BuilderPlace(seq++, false,
                new dev.sculptory.core.BlockPos(x, y, z), Facing.UP, 0.5f, 1f, 0.5f, powers, Symmetry.NONE));
    }

    private static BuilderOutcome breakCells(SelfTestRun r, int dragId, List<BlockPos> cells, int powers) {
        List<dev.sculptory.core.BlockPos> wire = new ArrayList<>();
        for (BlockPos cell : cells) wire.add(new dev.sculptory.core.BlockPos(cell.getX(), cell.getY(), cell.getZ()));
        return r.service.builderBreak(r.player, new C2S.BuilderBreak(seq++, dragId, wire, powers, Symmetry.NONE, false,
                true));
    }

    private static void hold(SelfTestRun r, Item item) {
        r.player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item, 64));
    }

    /** The entries builder mode added to the run's history (newest first); the earlier checks' are left out. */
    private static List<String> labels(SelfTestRun r) {
        List<HistoryEntry> entries = r.service.historyService().undoEntries(r.player.getUUID());
        return entries.subList(0, Math.max(0, entries.size() - earlierEntries)).stream().map(HistoryEntry::label)
                .toList();
    }

    /** How many history entries the run held before these checks. */
    private static int earlierEntries;

    private static void set(SelfTestRun r, BlockPos pos, BlockState state) {
        if (r.level.getBlockState(pos) == state) return;
        PaperAccess.setBlockWithoutCallbacks(r.level, pos, state);
        r.level.getChunkSource().blockChanged(pos);
    }
}
