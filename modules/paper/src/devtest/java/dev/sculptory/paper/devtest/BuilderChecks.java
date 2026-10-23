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
import dev.sculptory.mc.world.McTile;
import dev.sculptory.paper.SculptoryPlugin;
import dev.sculptory.paper.engine.PaperBuilderSteps;
import dev.sculptory.paper.engine.SelfTestSteps;
import dev.sculptory.paper.nms.PaperAccess;
import dev.sculptory.paper.perm.WorldGuardTestRegions;
import dev.sculptory.protocol.v2.BuilderPower;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.server.engine.BuilderOutcome;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.StructureBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.level.block.state.properties.StructureMode;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.RegisteredListener;

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
        // Whether another plugin (WorldGuard) listens to the physics event: then Paper fires it every tick anyway.
        r.info("builder: BlockPhysicsEvent listeners", Arrays.stream(BlockPhysicsEvent.getHandlerList()
                .getRegisteredListeners()).map(l -> l.getPlugin().getName() + ":" + l.getListener().getClass().getName())
                .toList() + ", physics event " + r.level.hasPhysicsEvent);
        Box work = box(x0, FLOOR, z0, x0 + 31, FLOOR + 15, z0 + 31);
        return placeAndBreak(r, events, x0, z0, work)
                .thenCompose(v -> neighbours(r, events, x0, z0, work))
                .thenCompose(v -> keepShape(r, x0, z0, work))
                .thenCompose(v -> cancelled(r, events, x0, z0, work))
                .thenCompose(v -> replaceMirrorRefusedCopy(r, events, x0, z0 + 32))
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
                .thenAccept(v -> errorThroughService(r, x0 + 16, z0 + 30))
                .thenCompose(v -> structureLoad(r, x0 + 20, z0 + 21, work))
                .thenCompose(v -> deferredFailure(r, x0 + 25, z0 + 2, work))
                .thenAccept(v -> chestWall(r, x0 + 24, z0 + 11))
                .thenCompose(v -> loadBoxUnderDoor(r, x0 + 2, z0 + 17, work))
                .thenCompose(v -> crafterBesideFarHook(r, x0 + 1, z0, work))
                .thenCompose(v -> loadAlongTripwire(r, x0 + 13, z0, work))
                .thenCompose(v -> {
                    platform(r, x0 + 14, z0, x0 + 30, z0 + 11);
                    return loadThroughWire(r, x0 + 18, z0 + 1, 1, work);
                })
                .thenCompose(v -> loadThroughWire(r, x0 + 18, z0 + 4, 3, work))
                .thenCompose(v -> placedLoadBesidePower(r, x0 + 16, z0 + 8, work))
                .thenCompose(v -> {
                    platform(r, x0 + 14, z0 + 12, x0 + 30, z0 + 20);
                    return unpoweredLoadBesideDust(r, x0 + 16, z0 + 13, work);
                })
                .thenCompose(v -> armedLoadBesideFallingDust(r, x0 + 16, z0 + 17, work));
    }

    /** Stone at height {@link #UPPER} over the rectangle (the area above it is air from the start). */
    private static void platform(SelfTestRun r, int minX, int minZ, int maxX, int maxZ) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) set(r, new BlockPos(x, UPPER, z), Blocks.STONE.defaultBlockState());
        }
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
     * Whether no builder action's physics listener is left registered and the world's physics-event switch is on only
     * if it was before the action ({@code before}) or another listener is registered now (fluid trails register one
     * for the rest of a tick); null when so, else what is wrong.
     */
    private static String physicsLeftOn(SelfTestRun r, boolean before) {
        RegisteredListener[] listeners = BlockPhysicsEvent.getHandlerList().getRegisteredListeners();
        long left = Arrays.stream(listeners)
                .filter(l -> l.getListener().getClass().getName().startsWith(PaperBuilderSteps.class.getName()))
                .count();
        boolean expected = before || listeners.length > left;
        if (left == 0 && r.level.hasPhysicsEvent == expected) return null;
        return left + " builder physics listeners left, physics event " + r.level.hasPhysicsEvent + " (before "
                + before + ", " + (listeners.length - left) + " other listeners)";
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

    /**
     * The cap of cells noted around an action's physics in {@link #pastTheCap}: the first target's own pre-note (the
     * 124 cells within 2 blocks of it) fits, the cascade of its redstone line does not.
     */
    private static final int SMALL_CAP = 130;

    /**
     * Past the cap of cells noted around an action's physics (lowered to {@value #SMALL_CAP} here): one break message
     * whose first target, a redstone block, depowers a redstone line going out, the cascade filling the cap, and whose
     * second target is a stone block apart. Both targets are recorded (they are always noted, cap or not): the step
     * holds both and its undo puts both back. The documented limit shows too: the dust within 2 blocks of the first
     * target (noted before) comes back powered, the dust further out (its change past the cap) stays unpowered.
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
            int near = r.level.getBlockState(power.east(2)).getValue(RedStoneWireBlock.POWER);
            int far = r.level.getBlockState(power.east(10)).getValue(RedStoneWireBlock.POWER);
            r.record("builder: past the cap, the dust noted before comes back, the dust beyond stays unpowered (limit)",
                    near == 13 && far == 0, "dust 2 east power " + near + " (13 before), 10 east power " + far
                            + " (5 before)");
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
        r.record("builder: an Error after a pumpkin's placement is aborted and put back, without a step or a golem",
                thrown != null && back && labels(r).equals(labelsBefore) && left == null && golems == 0,
                "thrown " + thrown + ", blocks back " + back + ", history " + labels(r) + ", " + golems
                        + " snow golems, " + (left == null ? "physics released" : left));
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

    /**
     * What a break costs in front of a wall of full chests: a wall of 7 by 3 chests, each holding 27 shulker boxes of
     * 27 stacks, one block behind the broken stone. The chests within the watch around the target are only referenced,
     * so the break saves at most the chest its update reaches. Reported: the time and the bytes the server thread
     * allocated for the one break message (about 12 ms on a desktop); it must take under {@value #WALL_LIMIT_MS} ms, a
     * bound loose enough for a shared CI runner, the number itself is in the report.
     */
    private static void chestWall(SelfTestRun r, int x0, int z) {
        ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
        List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < 27; i++) stacks.add(new ItemStack(Items.DIAMOND, 64));
        shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(stacks));
        for (int x = x0; x < x0 + 7; x++) {
            for (int y = FLOOR + 1; y <= FLOOR + 3; y++) {
                BlockPos chest = new BlockPos(x, y, z + 1);
                set(r, chest, Blocks.CHEST.defaultBlockState());
                if (r.level.getBlockEntity(chest) instanceof ChestBlockEntity entity) {
                    for (int slot = 0; slot < 27; slot++) entity.setItem(slot, shulker.copy());
                }
            }
        }
        BlockPos target = new BlockPos(x0 + 3, FLOOR + 1, z);
        set(r, target, Blocks.STONE.defaultBlockState());
        r.stand(x0 + 3.5, FLOOR + 1, z - 1.5, 0f, ItemStack.EMPTY);
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId();
        long bytesBefore = threads.getThreadAllocatedBytes(thread);
        long start = System.nanoTime();
        BuilderOutcome broken = breakCells(r, 8, List.of(target), 0);
        long micros = (System.nanoTime() - start) / 1000;
        long bytes = threads.getThreadAllocatedBytes(thread) - bytesBefore;
        r.record("builder: a break in front of a wall of full chests is quick", broken.accepted()
                        && broken.changed() == 1 && micros < WALL_LIMIT_MS * 1000L,
                broken + ", " + micros / 1000.0 + " ms, " + bytes / 1024 + " KiB allocated");
        // What the round before did for the same break: every chest within 2 blocks of the target saved up front.
        long eagerBytesBefore = threads.getThreadAllocatedBytes(thread);
        long eagerStart = System.nanoTime();
        int saved = 0;
        for (BlockPos cell : BlockPos.betweenClosed(target.offset(-2, -2, -2), target.offset(2, 2, 2))) {
            if (r.level.getBlockEntity(cell) instanceof ChestBlockEntity entity) {
                McTile.capture(entity, r.level.registryAccess());
                saved++;
            }
        }
        long eagerMicros = (System.nanoTime() - eagerStart) / 1000;
        long eagerBytes = threads.getThreadAllocatedBytes(thread) - eagerBytesBefore;
        r.info("builder: the chest wall break's cost", "now " + micros / 1000.0 + " ms, " + bytes / 1024
                + " KiB allocated; saving the " + saved + " chests within reach up front, as before: " + eagerMicros
                / 1000.0 + " ms, " + eagerBytes / 1024 + " KiB");
    }

    private static final int WALL_LIMIT_MS = 250;

    /**
     * A structure block in LOAD mode, a template of 3 by 3 by 3 stone with two empty chests facing north in its bottom
     * row, set 1 to 3 blocks east of it: a redstone block placed beside it (without Keep shape) powers it and it places
     * the template at once, cells no update reaches. Under the template's chests stand two chests with items, one
     * facing north as the template's (2 blocks from the redstone block, so noted around it first), one facing south
     * (in the load box only); the template empties both before it sets its own. The load box was noted with its block
     * entities saved, so the step holds the template's 27 cells (the north chest too, whose block stayed the same) and
     * its undo removes the template and puts back both chests with their items.
     */
    private static CompletableFuture<Void> structureLoad(SelfTestRun r, int x0, int z, Box work) {
        StructureTemplateManager manager = r.level.getStructureManager();
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("sculptory", "selftest_" + r.runId);
        BlockPos source = new BlockPos(x0 + 8, FLOOR + 1, z - 3);
        BlockState north = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
        for (BlockPos cell : BlockPos.betweenClosed(source, source.offset(2, 2, 2))) {
            set(r, cell.immutable(), Blocks.STONE.defaultBlockState());
        }
        set(r, source, north);
        set(r, source.east(2), north);
        StructureTemplate template = manager.getOrCreate(id);
        template.fillFromWorld(r.level, source, new Vec3i(3, 3, 3), false, null);
        r.cleanup(() -> manager.remove(id));
        for (BlockPos cell : BlockPos.betweenClosed(source, source.offset(2, 2, 2))) {
            set(r, cell.immutable(), Blocks.AIR.defaultBlockState());
        }
        BlockPos block = new BlockPos(x0 + 2, FLOOR + 1, z);
        set(r, block, Blocks.STRUCTURE_BLOCK.defaultBlockState().setValue(StructureBlock.MODE, StructureMode.LOAD));
        if (r.level.getBlockEntity(block) instanceof StructureBlockEntity entity) {
            entity.setMode(StructureMode.LOAD);
            entity.setStructureName(id);
            entity.setStructurePos(new BlockPos(1, 0, 0));
            entity.setStructureSize(new Vec3i(3, 3, 3));
        }
        BlockPos origin = block.east();
        BlockPos sameChest = origin;
        BlockPos turnedChest = origin.east(2);
        set(r, sameChest, north);
        set(r, turnedChest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH));
        fillChest(r, sameChest, new ItemStack(Items.DIAMOND, 5));
        fillChest(r, turnedChest, new ItemStack(Items.EMERALD, 7));
        r.stand(x0 - 1.5, FLOOR + 1, z + 0.5, -90f, new ItemStack(Items.REDSTONE_BLOCK, 64));
        int[] before = r.snapshot(work);
        BuilderOutcome placed = place(r, x0 + 1, FLOOR, z, 0);
        BlockPos far = origin.offset(2, 2, 2);
        boolean loaded = r.level.getBlockState(origin.east()).is(Blocks.STONE)
                && r.level.getBlockState(far).is(Blocks.STONE)
                && r.level.getBlockState(turnedChest).getValue(ChestBlock.FACING) == Direction.NORTH
                && chestItem(r, sameChest).isEmpty() && chestItem(r, turnedChest).isEmpty();
        r.record("builder: a structure block powered by a placed redstone block loads its template (recorded)",
                placed.accepted() && loaded && placed.changed() == 28, placed + ", template placed (its chests "
                        + "empty) " + loaded);
        return r.undo().thenAccept(v -> {
            r.record("builder: its undo removes the loaded template exactly",
                    r.countDiffering(work, before) == 0, r.countDiffering(work, before) + " cells differ");
            ItemStack same = chestItem(r, sameChest), turned = chestItem(r, turnedChest);
            boolean back = same.is(Items.DIAMOND) && same.getCount() == 5 && turned.is(Items.EMERALD)
                    && turned.getCount() == 7
                    && r.level.getBlockState(turnedChest).getValue(ChestBlock.FACING) == Direction.SOUTH;
            r.record("builder: the undo puts back the items of the chests the template emptied (same facing and turned)",
                    back, "same-facing chest " + same + ", turned chest " + turned + " facing "
                            + r.level.getBlockState(turnedChest).getValue(ChestBlock.FACING));
        });
    }

    private static void fillChest(SelfTestRun r, BlockPos pos, ItemStack stack) {
        if (r.level.getBlockEntity(pos) instanceof ChestBlockEntity chest) chest.setItem(0, stack);
    }

    /** The first slot of the chest at {@code pos}, or empty without a chest. */
    private static ItemStack chestItem(SelfTestRun r, BlockPos pos) {
        return r.level.getBlockEntity(pos) instanceof ChestBlockEntity chest ? chest.getItem(0) : ItemStack.EMPTY;
    }

    /**
     * A structure block in LOAD mode under a door, naming a template of 33 by 33 by 33 cells whose box lies below the
     * floor (larger than the cap of noted cells, {@value PaperBuilderSteps#DEFAULT_MAX_WATCHED_CELLS}): breaking the
     * door's lower half does not power the block, so its load box is never noted and cannot take the cap from the
     * cells around the break, the upper half among them. Both halves are the step and its undo puts the whole door back.
     */
    private static CompletableFuture<Void> loadBoxUnderDoor(SelfTestRun r, int x0, int z, Box work) {
        StructureTemplateManager manager = r.level.getStructureManager();
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("sculptory", "selftest_large_" + r.runId);
        StructureTemplate template = manager.getOrCreate(id);
        // Its size is all that counts here (the block is never powered): air is left out of the template.
        template.fillFromWorld(r.level, new BlockPos(x0, FLOOR + 20, z), new Vec3i(33, 33, 33), false, Blocks.AIR);
        r.cleanup(() -> manager.remove(id));
        BlockPos lower = new BlockPos(x0 + 1, FLOOR + 1, z);
        BlockPos block = lower.below();
        set(r, block, Blocks.STRUCTURE_BLOCK.defaultBlockState().setValue(StructureBlock.MODE, StructureMode.LOAD));
        if (r.level.getBlockEntity(block) instanceof StructureBlockEntity entity) {
            entity.setMode(StructureMode.LOAD);
            entity.setStructureName(id);
            // The box: from 4 blocks west and 16 north of the block, 40 down; 33 cells each way (y 60 to 92).
            entity.setStructurePos(new BlockPos(-4, -40, -16));
            entity.setStructureSize(new Vec3i(33, 33, 33));
        }
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
        set(r, lower, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        set(r, lower.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        r.stand(x0 - 1.5, FLOOR + 1, z + 0.5, -90f, ItemStack.EMPTY);
        int[] before = r.snapshot(work);
        LogLines capped = LogLines.attach(LogLines.CAP_REACHED);
        r.cleanup(capped::detach);
        BuilderOutcome broken = breakCells(r, 9, List.of(lower), 0);
        boolean gone = r.level.getBlockState(lower).isAir() && r.level.getBlockState(lower.above()).isAir();
        r.record("builder: a door broken above a LOAD structure block with a large template: both halves recorded",
                broken.accepted() && broken.changed() == 2 && gone, broken + ", both halves gone " + gone);
        // The server's log may be written on another thread: a few ticks before the warning is counted.
        return r.ticks(3).thenAccept(v -> {
            capped.detach();
            r.record("builder: the unpowered LOAD block notes nothing (no cap warning)", capped.seen() == 0,
                    capped.seen() + " cap warnings");
        }).thenCompose(v -> r.undo()).thenAccept(v -> {
            BlockState upper = r.level.getBlockState(lower.above());
            r.record("builder: its undo puts the whole door back",
                    r.countDiffering(work, before) == 0 && upper.is(Blocks.OAK_DOOR),
                    r.countDiffering(work, before) + " cells differ, upper half " + upper);
        });
    }

    /** The platform the checks of loads through redstone run on: stone at this height, the area above it air. */
    private static final int UPPER = FLOOR + 6;

    /**
     * A structure block in LOAD mode at the end of a line of {@code wires} redstone dust, naming a template of a stone
     * and an empty chest set 2 and 3 blocks east of it, over a chest with items. A redstone block placed at the line's
     * other end (without Keep shape) powers the dust and the dust the structure block, which loads its template. With
     * Paper's Eigencraft redstone the dust is set and the structure block updated without any physics event; the load
     * box is noted at the wire power event of the dust beside it. The undo takes the template back and puts the chest
     * back with its items. Reported: the redstone implementation in force.
     */
    private static CompletableFuture<Void> loadThroughWire(SelfTestRun r, int x0, int z, int wires, Box work) {
        StructureTemplateManager manager = r.level.getStructureManager();
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("sculptory", "selftest_wire" + wires + "_"
                + r.runId);
        BlockPos power = new BlockPos(x0, UPPER + 1, z);
        BlockPos block = power.east(wires + 1);
        BlockPos origin = block.east(2);
        BlockPos chest = origin.east();
        set(r, origin, Blocks.STONE.defaultBlockState());
        set(r, chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH));
        StructureTemplate template = manager.getOrCreate(id);
        template.fillFromWorld(r.level, origin, new Vec3i(2, 1, 1), false, null);
        r.cleanup(() -> manager.remove(id));
        set(r, origin, Blocks.AIR.defaultBlockState());
        set(r, chest, Blocks.AIR.defaultBlockState());
        set(r, chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH));
        fillChest(r, chest, new ItemStack(Items.REDSTONE, 11));
        BlockState dust = Blocks.REDSTONE_WIRE.defaultBlockState().setValue(RedStoneWireBlock.EAST, RedstoneSide.SIDE)
                .setValue(RedStoneWireBlock.WEST, RedstoneSide.SIDE);
        for (int i = 1; i <= wires; i++) set(r, power.east(i), dust);
        set(r, block, Blocks.STRUCTURE_BLOCK.defaultBlockState().setValue(StructureBlock.MODE, StructureMode.LOAD));
        if (r.level.getBlockEntity(block) instanceof StructureBlockEntity entity) {
            entity.setMode(StructureMode.LOAD);
            entity.setStructureName(id);
            entity.setStructurePos(new BlockPos(2, 0, 0));
            entity.setStructureSize(new Vec3i(2, 1, 1));
        }
        r.stand(x0 - 2.5, UPPER + 1, z + 0.5, -90f, new ItemStack(Items.REDSTONE_BLOCK, 64));
        int[] before = r.snapshot(work);
        String implementation = String.valueOf(r.level.paperConfig().misc.redstoneImplementation);
        BuilderOutcome placed = place(r, power.getX(), UPPER, z, 0);
        boolean loaded = r.level.getBlockState(origin).is(Blocks.STONE)
                && r.level.getBlockState(chest).getValue(ChestBlock.FACING) == Direction.NORTH
                && chestItem(r, chest).isEmpty();
        String through = wires + (wires == 1 ? " wire" : " wires");
        r.record("builder: a redstone block powering a LOAD structure block through " + through + " loads it (recorded)",
                placed.accepted() && loaded, placed + ", template placed " + loaded + ", redstone " + implementation);
        return r.undo().thenAccept(v -> {
            ItemStack back = chestItem(r, chest);
            boolean whole = back.is(Items.REDSTONE) && back.getCount() == 11 && r.level.getBlockState(origin).isAir();
            r.record("builder: its undo (" + through + ", redstone " + implementation + ") takes the template back and "
                            + "the chest's items with it", r.countDiffering(work, before) == 0 && whole,
                    r.countDiffering(work, before) + " cells differ, chest " + back + ", template's stone cell "
                            + r.level.getBlockState(origin));
        });
    }

    /**
     * Paper's steps directly, with a small cap: one action places a structure block in LOAD mode beside a redstone
     * block (named, its block entity not powered, a 20 by 20 by 20 template), then a redstone block at the start of a
     * line of five dust. The structure block's own placement does not power it (the game never runs a block's
     * neighbour reaction for its own change), so its box is not noted: no cap warning, and the dust 5 blocks along the
     * line, noted only as the cascade reaches it, is among the action's changes. The changes are put back after.
     */
    private static CompletableFuture<Void> placedLoadBesidePower(SelfTestRun r, int x0, int z, Box work) {
        StructureTemplateManager manager = r.level.getStructureManager();
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("sculptory", "selftest_placed_" + r.runId);
        StructureTemplate template = manager.getOrCreate(id);
        template.fillFromWorld(r.level, new BlockPos(x0, FLOOR + 20, z), new Vec3i(20, 20, 20), false, Blocks.AIR);
        r.cleanup(() -> manager.remove(id));
        BlockPos block = new BlockPos(x0 + 1, UPPER + 1, z);
        set(r, block.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        BlockPos power = block.east(4);
        BlockState dust = Blocks.REDSTONE_WIRE.defaultBlockState().setValue(RedStoneWireBlock.EAST, RedstoneSide.SIDE)
                .setValue(RedStoneWireBlock.WEST, RedstoneSide.SIDE);
        for (int i = 1; i <= 5; i++) set(r, power.east(i), dust);
        BlockPos far = power.east(5);
        r.stand(x0 - 1.5, UPPER + 1, z + 2.5, -90f, ItemStack.EMPTY);
        int[] before = r.snapshot(work);
        LogLines capped = LogLines.attach(LogLines.CAP_REACHED);
        r.cleanup(capped::detach);
        PaperBuilderSteps steps = new PaperBuilderSteps(SculptoryPlugin.instance(), PLACED_CAP);
        BuilderSteps.Action action = steps.open(r.level, r.player, false);
        action.place((BlockItem) Items.STRUCTURE_BLOCK, context(r, block, Items.STRUCTURE_BLOCK),
                Blocks.STRUCTURE_BLOCK.defaultBlockState().setValue(StructureBlock.MODE, StructureMode.LOAD), false);
        // Named as a placed block with block entity data is, before its physics run (the template's box lies below).
        if (r.level.getBlockEntity(block) instanceof StructureBlockEntity entity) {
            entity.setMode(StructureMode.LOAD);
            entity.setStructureName(id);
            entity.setStructurePos(new BlockPos(0, -40, 0));
            entity.setStructureSize(new Vec3i(20, 20, 20));
        }
        action.place((BlockItem) Items.REDSTONE_BLOCK, context(r, power, Items.REDSTONE_BLOCK),
                Blocks.REDSTONE_BLOCK.defaultBlockState(), false);
        List<BuilderChange> changes = action.finish();
        boolean farPowered = r.level.getBlockState(far).getValue(RedStoneWireBlock.POWER) > 0;
        boolean farRecorded = changes.stream().anyMatch(c -> c.pos().equals(far));
        steps.revert(r.level, changes);
        return r.ticks(3).thenAccept(v -> {
            capped.detach();
            r.record("builder: a LOAD structure block placed beside power notes no box; the cascade after is recorded",
                    capped.seen() == 0 && farPowered && farRecorded && r.countDiffering(work, before) == 0,
                    capped.seen() + " cap warnings (cap " + PLACED_CAP + "), dust 5 along powered " + farPowered
                            + " and recorded " + farRecorded + ", " + changes.size() + " changes, "
                            + r.countDiffering(work, before) + " cells differ after they were put back");
        });
    }

    /** The cap for {@link #placedLoadBesidePower}: the action's own cells and its cascade fit (about 1400), a 20^3 box does not. */
    private static final int PLACED_CAP = 2500;

    /**
     * Paper's steps directly, with the same small cap: a structure block in LOAD mode (not powered, a 20 by 20 by 20
     * template) stands north of the second of five dust running east-west, which do not point into it. One action
     * places a redstone block at the line's west end. The dust powers up; the structure block gets no signal and does
     * not load. With Paper's vanilla redstone or Alternate Current nothing notes its box: no cap warning, and the dust 5
     * along is among the changes. With Eigencraft a LOAD block within 2 blocks of a wire gaining power has its box noted
     * without a signal to test (documented over-noting), which this checks too: the cap warning is logged. The changes
     * are put back after.
     */
    private static CompletableFuture<Void> unpoweredLoadBesideDust(SelfTestRun r, int x0, int z, Box work) {
        StructureTemplateManager manager = r.level.getStructureManager();
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("sculptory", "selftest_beside_" + r.runId);
        StructureTemplate template = manager.getOrCreate(id);
        template.fillFromWorld(r.level, new BlockPos(x0, FLOOR + 20, z), new Vec3i(20, 20, 20), false, Blocks.AIR);
        r.cleanup(() -> manager.remove(id));
        BlockPos power = new BlockPos(x0, UPPER + 1, z);
        BlockState dust = Blocks.REDSTONE_WIRE.defaultBlockState().setValue(RedStoneWireBlock.EAST, RedstoneSide.SIDE)
                .setValue(RedStoneWireBlock.WEST, RedstoneSide.SIDE);
        for (int i = 1; i <= 5; i++) set(r, power.east(i), dust);
        BlockPos far = power.east(5);
        BlockPos block = power.east(2).north();
        set(r, block, Blocks.STRUCTURE_BLOCK.defaultBlockState().setValue(StructureBlock.MODE, StructureMode.LOAD));
        if (r.level.getBlockEntity(block) instanceof StructureBlockEntity entity) {
            entity.setMode(StructureMode.LOAD);
            entity.setStructureName(id);
            entity.setStructurePos(new BlockPos(0, -40, 0));
            entity.setStructureSize(new Vec3i(20, 20, 20));
        }
        r.stand(x0 - 1.5, UPPER + 1, z + 2.5, -90f, ItemStack.EMPTY);
        int[] before = r.snapshot(work);
        boolean eigencraft = r.level.paperConfig().misc.redstoneImplementation
                == io.papermc.paper.configuration.WorldConfiguration.Misc.RedstoneImplementation.EIGENCRAFT;
        LogLines capped = LogLines.attach(LogLines.CAP_REACHED);
        r.cleanup(capped::detach);
        PaperBuilderSteps steps = new PaperBuilderSteps(SculptoryPlugin.instance(), PLACED_CAP);
        BuilderSteps.Action action = steps.open(r.level, r.player, false);
        action.place((BlockItem) Items.REDSTONE_BLOCK, context(r, power, Items.REDSTONE_BLOCK),
                Blocks.REDSTONE_BLOCK.defaultBlockState(), false);
        List<BuilderChange> changes = action.finish();
        boolean farPowered = r.level.getBlockState(far).getValue(RedStoneWireBlock.POWER) > 0;
        boolean farRecorded = changes.stream().anyMatch(c -> c.pos().equals(far));
        boolean loaded = r.level.getBlockEntity(block) instanceof StructureBlockEntity entity && entity.isPowered();
        steps.revert(r.level, changes);
        String implementation = String.valueOf(r.level.paperConfig().misc.redstoneImplementation);
        return r.ticks(3).thenAccept(v -> {
            capped.detach();
            int differ = r.countDiffering(work, before);
            // With Eigencraft the box fills the cap, so the dust further out is not among the changes and stays
            // powered after they are put back (the documented cost of that over-noting); the check expects exactly that.
            boolean expected = eigencraft ? capped.seen() >= 1
                    : capped.seen() == 0 && farRecorded && differ == 0;
            r.record("builder: an unpowered LOAD block beside dust that does not point into it notes its box only with "
                            + "Eigencraft", expected && farPowered && !loaded,
                    capped.seen() + " cap warnings (cap " + PLACED_CAP + "), redstone " + implementation
                            + ", dust 5 along powered " + farPowered + " and recorded " + farRecorded
                            + ", structure block powered " + loaded + ", " + changes.size() + " changes, " + differ
                            + " cells differ after they were put back");
            // The line as it was, whatever was left powered, for the checks after.
            for (int i = 1; i <= 5; i++) set(r, power.east(i), dust);
        });
    }

    /**
     * An armed structure block in LOAD mode: a redstone block beside it gives it a signal its block entity never saw
     * (set without updates), so any neighbour update makes it load. Two blocks from it run three dust a lever on the
     * floor powers. Breaking the lever (without Keep shape) powers the dust down; the updates of the dust losing power
     * reach the structure block (with Eigencraft, directly from the wire walk, no physics event) and it loads its
     * template over a chest with items. Its box was noted (with Eigencraft at the wire power event, the signal from the
     * redstone block tested), so the undo takes the template back and the chest's items with it.
     */
    private static CompletableFuture<Void> armedLoadBesideFallingDust(SelfTestRun r, int x0, int z, Box work) {
        StructureTemplateManager manager = r.level.getStructureManager();
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("sculptory", "selftest_armed_" + r.runId);
        BlockPos lever = new BlockPos(x0, UPPER + 1, z);
        BlockPos block = lever.east(2).south(2);
        BlockPos origin = block.east(2);
        BlockPos chest = origin.east();
        set(r, origin, Blocks.STONE.defaultBlockState());
        set(r, chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH));
        StructureTemplate template = manager.getOrCreate(id);
        template.fillFromWorld(r.level, origin, new Vec3i(2, 1, 1), false, null);
        r.cleanup(() -> manager.remove(id));
        set(r, origin, Blocks.AIR.defaultBlockState());
        set(r, chest, Blocks.AIR.defaultBlockState());
        set(r, chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH));
        fillChest(r, chest, new ItemStack(Items.LAPIS_LAZULI, 13));
        set(r, lever, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(LeverBlock.FACING, Direction.EAST).setValue(LeverBlock.POWERED, true));
        BlockState dust = Blocks.REDSTONE_WIRE.defaultBlockState().setValue(RedStoneWireBlock.EAST, RedstoneSide.SIDE)
                .setValue(RedStoneWireBlock.WEST, RedstoneSide.SIDE);
        for (int i = 1; i <= 3; i++) set(r, lever.east(i), dust.setValue(RedStoneWireBlock.POWER, 16 - i));
        set(r, block.south(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        set(r, block, Blocks.STRUCTURE_BLOCK.defaultBlockState().setValue(StructureBlock.MODE, StructureMode.LOAD));
        if (r.level.getBlockEntity(block) instanceof StructureBlockEntity entity) {
            entity.setMode(StructureMode.LOAD);
            entity.setStructureName(id);
            entity.setStructurePos(new BlockPos(2, 0, 0));
            entity.setStructureSize(new Vec3i(2, 1, 1));
        }
        r.stand(x0 - 1.5, UPPER + 1, z + 0.5, -90f, ItemStack.EMPTY);
        int[] before = r.snapshot(work);
        String implementation = String.valueOf(r.level.paperConfig().misc.redstoneImplementation);
        BuilderOutcome broken = breakCells(r, 11, List.of(lever), 0);
        boolean down = r.level.getBlockState(lever.east()).getValue(RedStoneWireBlock.POWER) == 0;
        boolean loaded = r.level.getBlockState(origin).is(Blocks.STONE) && chestItem(r, chest).isEmpty();
        r.record("builder: dust losing power beside an armed LOAD structure block makes it load (recorded)",
                broken.accepted() && down && loaded, broken + ", dust off " + down + ", template placed " + loaded
                        + ", redstone " + implementation);
        return r.undo().thenAccept(v -> {
            ItemStack back = chestItem(r, chest);
            boolean whole = back.is(Items.LAPIS_LAZULI) && back.getCount() == 13
                    && r.level.getBlockState(origin).isAir();
            r.record("builder: its undo (redstone " + implementation + ") takes the template back and the chest's items "
                    + "with it", r.countDiffering(work, before) == 0 && whole, r.countDiffering(work, before)
                    + " cells differ, chest " + back + ", template's stone cell " + r.level.getBlockState(origin));
        });
    }

    /** A placement context for {@code cell}, clicked on the top of the block below it, holding {@code item}. */
    private static BlockPlaceContext context(SelfTestRun r, BlockPos cell, Item item) {
        return new BlockPlaceContext(r.level, r.player, net.minecraft.world.InteractionHand.MAIN_HAND,
                new ItemStack(item), new BlockHitResult(Vec3.atCenterOf(cell.below()).add(0, 0.5, 0), Direction.UP,
                cell.below(), false));
    }

    /**
     * A structure block in LOAD mode reached only along a tripwire's line: an attached north-south line whose north
     * hook hangs on the structure block, which names a template of a stone and an empty chest set 2 and 3 blocks west of
     * it, over a chest with items and an empty cell. Breaking a string in the middle (without Keep shape) powers both
     * hooks for a moment (a string's removal counts as a step on it), the north hook powers the structure block and it
     * loads its template. The block was first noted along the line, not around a change; its load box is noted when the
     * update that powers it reaches it, so the undo takes the template back and puts the chest back with its items.
     */
    private static CompletableFuture<Void> loadAlongTripwire(SelfTestRun r, int x, int z0, Box work) {
        StructureTemplateManager manager = r.level.getStructureManager();
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("sculptory", "selftest_line_" + r.runId);
        BlockPos block = new BlockPos(x, FLOOR + 1, z0);
        BlockPos origin = block.west(3);
        BlockPos chest = origin.east();
        set(r, origin, Blocks.STONE.defaultBlockState());
        set(r, chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH));
        StructureTemplate template = manager.getOrCreate(id);
        template.fillFromWorld(r.level, origin, new Vec3i(2, 1, 1), false, null);
        r.cleanup(() -> manager.remove(id));
        set(r, origin, Blocks.AIR.defaultBlockState());
        set(r, chest, Blocks.AIR.defaultBlockState());
        set(r, chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH));
        fillChest(r, chest, new ItemStack(Items.GOLD_INGOT, 9));
        set(r, block, Blocks.STRUCTURE_BLOCK.defaultBlockState().setValue(StructureBlock.MODE, StructureMode.LOAD));
        if (r.level.getBlockEntity(block) instanceof StructureBlockEntity entity) {
            entity.setMode(StructureMode.LOAD);
            entity.setStructureName(id);
            entity.setStructurePos(new BlockPos(-3, 0, 0));
            entity.setStructureSize(new Vec3i(2, 1, 1));
        }
        BlockPos northHook = block.south();
        BlockPos southHook = northHook.south(11);
        set(r, southHook.south(), Blocks.STONE.defaultBlockState());
        BlockState hook = Blocks.TRIPWIRE_HOOK.defaultBlockState().setValue(TripWireHookBlock.ATTACHED, true);
        set(r, northHook, hook.setValue(TripWireHookBlock.FACING, Direction.SOUTH));
        set(r, southHook, hook.setValue(TripWireHookBlock.FACING, Direction.NORTH));
        BlockState string = Blocks.TRIPWIRE.defaultBlockState().setValue(TripWireBlock.NORTH, true)
                .setValue(TripWireBlock.SOUTH, true).setValue(TripWireBlock.ATTACHED, true);
        for (int i = 1; i < 11; i++) set(r, northHook.south(i), string);
        BlockPos broken = northHook.south(6);
        r.stand(x + 3.5, FLOOR + 1, broken.getZ() + 0.5, 90f, ItemStack.EMPTY);
        int[] before = r.snapshot(work);
        BuilderOutcome outcome = breakCells(r, 10, List.of(broken), 0);
        boolean loaded = r.level.getBlockState(origin).is(Blocks.STONE)
                && r.level.getBlockState(chest).getValue(ChestBlock.FACING) == Direction.NORTH
                && chestItem(r, chest).isEmpty();
        r.record("builder: breaking a string powers a LOAD structure block down the line, which loads (recorded)",
                outcome.accepted() && loaded, outcome + ", template placed " + loaded);
        return r.undo().thenAccept(v -> {
            ItemStack back = chestItem(r, chest);
            boolean whole = back.is(Items.GOLD_INGOT) && back.getCount() == 9
                    && r.level.getBlockState(origin).isAir();
            r.record("builder: its undo takes the template back and the chest's items with it",
                    r.countDiffering(work, before) == 0 && whole,
                    r.countDiffering(work, before) + " cells differ, chest " + back + ", template's stone cell "
                            + r.level.getBlockState(origin));
        });
    }

    /**
     * A tripwire running north-south, one of its strings powered (as by an entity on it), the middle string missing,
     * and a crafter beside the north hook (west of it, the first neighbour the hook's updates reach). Placing the middle
     * string (without Keep shape) attaches and powers both hooks; the north hook, set from the south one's calculation
     * 6 blocks from the string, updates the crafter first: no event has reached a cell near it before, so it is noted
     * at its own event and its block entity saved before it reacts; it triggers. The undo puts back an untriggered
     * crafter whose data agrees, and the line as it was. (Paper fires the physics event twice for each neighbour of a
     * multi-neighbour update, so this case also passed when the cell was saved only from its second event; the
     * order noted-then-saved covers a cell first reached by a single neighbour update.)
     */
    private static CompletableFuture<Void> crafterBesideFarHook(SelfTestRun r, int x, int z0, Box work) {
        BlockPos northHook = new BlockPos(x, FLOOR + 1, z0 + 1);
        BlockPos southHook = northHook.south(11);
        BlockPos middle = northHook.south(6);
        BlockPos crafter = northHook.west();
        set(r, northHook.north(), Blocks.STONE.defaultBlockState());
        set(r, southHook.south(), Blocks.STONE.defaultBlockState());
        BlockState hook = Blocks.TRIPWIRE_HOOK.defaultBlockState();
        set(r, northHook, hook.setValue(TripWireHookBlock.FACING, Direction.SOUTH));
        set(r, southHook, hook.setValue(TripWireHookBlock.FACING, Direction.NORTH));
        BlockState string = Blocks.TRIPWIRE.defaultBlockState().setValue(TripWireBlock.NORTH, true)
                .setValue(TripWireBlock.SOUTH, true);
        for (int i = 1; i < 11; i++) {
            BlockPos cell = northHook.south(i);
            if (cell.equals(middle)) continue;
            set(r, cell, i == 2 ? string.setValue(TripWireBlock.POWERED, true) : string);
        }
        set(r, crafter, Blocks.CRAFTER.defaultBlockState());
        r.stand(x + 3.5, FLOOR + 1, middle.getZ() + 0.5, 90f, new ItemStack(Items.STRING, 64));
        int[] before = r.snapshot(work);
        BuilderOutcome placed = place(r, middle.getX(), FLOOR, middle.getZ(), 0);
        boolean powered = r.level.getBlockState(northHook).getValue(TripWireHookBlock.POWERED);
        boolean triggered = r.level.getBlockState(crafter).getValue(CrafterBlock.TRIGGERED);
        r.record("builder: a string powering a far hook triggers the crafter beside it (recorded)",
                placed.accepted() && powered && triggered, placed + ", north hook powered " + powered
                        + ", crafter triggered " + triggered);
        return r.undo().thenAccept(v -> {
            boolean data = r.level.getBlockEntity(crafter) instanceof CrafterBlockEntity entity && entity.isTriggered();
            boolean state = r.level.getBlockState(crafter).getValue(CrafterBlock.TRIGGERED);
            r.record("builder: its undo puts back an untriggered crafter, block and data alike",
                    r.countDiffering(work, before) == 0 && !state && !data,
                    r.countDiffering(work, before) + " cells differ, TRIGGERED " + state + ", block entity triggered "
                            + data);
        });
    }

    /**
     * What the deferred physics throw halfway, through {@code BuilderService}: a bed placed (foot, then head), its
     * physics failing after the foot's updates ran and before the head's. A RuntimeException leaves the step recorded
     * (FAILED, one history step, both halves placed), the head still registered as a point of interest (it tells the
     * clients and the points of interest though its updates never ran); the step's undo removes the bed and its point.
     * An Error puts the placement back without a step (the head's point never registered, nor removed) and reaches the
     * caller. No "POI data mismatch" is logged throughout but the one a deliberate removal makes (the counter's control).
     */
    private static CompletableFuture<Void> deferredFailure(SelfTestRun r, int x0, int z, Box work) {
        r.stand(x0 + 0.5, FLOOR + 1, z + 0.5, -90f, new ItemStack(Items.RED_BED, 1));
        BlockPos runtimeFoot = new BlockPos(x0 + 3, FLOOR + 1, z);
        BlockPos runtimeHead = runtimeFoot.east();
        BlockPos errorFoot = runtimeFoot.south(2);
        BlockPos errorHead = errorFoot.east();
        // The physics fail as the head's updates are about to run (after the foot's).
        PaperBuilderSteps throwing = SelfTestSteps.failingBeforeUpdate(SculptoryPlugin.instance(), pos -> {
            if (pos.equals(runtimeHead)) throw new IllegalStateException("deferred physics failed (test)");
            if (pos.equals(errorHead)) throw new StackOverflowError("deferred physics failed (test)");
        });
        BuilderService failing = new BuilderService(r.service, r.host.engine(), r.host.engine().server(), throwing,
                r.service.executor(), r.service.historyService(), System::nanoTime);
        // A point of interest registered in the beds' section: the game logs a mismatch only for a section holding
        // points already (an empty one is not even loaded for the removal).
        BlockPos composter = new BlockPos(x0 + 5, FLOOR + 1, z + 5);
        r.level.setBlockAndUpdate(composter, Blocks.COMPOSTER.defaultBlockState());
        boolean sectionHeld = r.level.getPoiManager().getType(composter).isPresent();
        LogLines mismatches = LogLines.attach(LogLines.POI_MISMATCH);
        r.cleanup(mismatches::detach);
        // The counter's positive control: one deliberate removal of a point never registered logs one mismatch.
        r.level.getPoiManager().remove(composter.east());
        int[] before = r.snapshot(work);
        List<String> labelsBefore = labels(r);
        BuilderOutcome failed = failing.place(r.player, new C2S.BuilderPlace(seq++, false,
                core(runtimeFoot.below()), Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE));
        boolean stays = r.level.getBlockState(runtimeFoot).is(Blocks.RED_BED)
                && r.level.getBlockState(runtimeHead).is(Blocks.RED_BED);
        boolean point = r.level.getPoiManager().getType(runtimeHead).isPresent();
        r.record("builder: a RuntimeException halfway through the deferred physics leaves the step recorded (FAILED)",
                failed.refusal() == BuilderOutcome.Refusal.FAILED && failed.changed() == 2 && stays && point
                        && labels(r).size() == labelsBefore.size() + 1,
                failed + ", bed stays " + stays + ", head's point of interest registered " + point + ", history "
                        + labels(r));
        return r.undo().thenCompose(v -> {
            boolean pointLeft = r.level.getPoiManager().getType(runtimeHead).isPresent();
            r.record("builder: that step's undo removes the bed and its point of interest",
                    r.countDiffering(work, before) == 0 && !pointLeft,
                    r.countDiffering(work, before) + " cells differ, point of interest left " + pointLeft);
            r.stand(x0 + 0.5, FLOOR + 1, errorFoot.getZ() + 0.5, -90f, new ItemStack(Items.RED_BED, 1));
            List<String> labelsNow = labels(r);
            boolean physicsBefore = r.level.hasPhysicsEvent;
            StackOverflowError thrown = null;
            try {
                failing.place(r.player, new C2S.BuilderPlace(seq++, false, core(errorFoot.below()), Facing.UP, 0.5f,
                        1f, 0.5f, 0, Symmetry.NONE));
            } catch (StackOverflowError e) {
                thrown = e;
            }
            String left = physicsLeftOn(r, physicsBefore);
            boolean gone = r.level.getBlockState(errorFoot).isAir() && r.level.getBlockState(errorHead).isAir();
            boolean errorPoint = r.level.getPoiManager().getType(errorHead).isPresent();
            r.record("builder: an Error halfway through the deferred physics puts the placement back without a step",
                    thrown != null && gone && !errorPoint && labels(r).equals(labelsNow) && left == null,
                    "thrown " + thrown + ", put back " + gone + ", head's point of interest " + errorPoint
                            + ", history " + labels(r) + ", " + (left == null ? "physics released" : left));
            // The server's log may be written on another thread: a few ticks for it.
            return r.ticks(5);
        }).thenAccept(v -> {
            mismatches.detach();
            int seen = mismatches.seen();
            r.record("builder: the log counter sees a deliberate point-of-interest mismatch (positive control)",
                    sectionHeld && seen >= 1, seen + " \"POI data mismatch\" lines in all, the section holding a "
                            + "composter's point " + sectionHeld);
            r.record("builder: no point-of-interest mismatch logged by the failed physics, their undo or revert",
                    seen == 1, (seen - 1) + " lines besides the deliberate one");
        });
    }

    /** Counts the server log's lines holding a text while attached to log4j's root logger. */
    private static final class LogLines extends AbstractAppender {
        /** What the game logs for a point of interest removed or added against its data. */
        static final String POI_MISMATCH = "POI data mismatch";
        /** What PaperBuilderSteps logs when an action's noted cells reach the cap. */
        static final String CAP_REACHED = "set off updates beyond the";

        private final String text;
        private final AtomicInteger seen = new AtomicInteger();
        private boolean attached;

        private LogLines(String text) {
            super("sculptory-selftest-log", null, null, true, Property.EMPTY_ARRAY);
            this.text = text;
        }

        static LogLines attach(String text) {
            LogLines appender = new LogLines(text);
            appender.start();
            ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).addAppender(appender);
            appender.attached = true;
            return appender;
        }

        void detach() {
            if (!attached) return;
            attached = false;
            ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).removeAppender(this);
            stop();
        }

        int seen() {
            return seen.get();
        }

        @Override
        public void append(LogEvent event) {
            if (event.getMessage().getFormattedMessage().contains(text)) seen.incrementAndGet();
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
     * Replace with Mirror over two chests holding diamonds, another plugin cancelling the mirrored copy's placement (its
     * place event): the original chest is replaced by stone and only it is recorded (its diamonds with it), the copy is
     * skipped and its chest keeps its diamonds, untouched and unrecorded; the undo puts the original chest back full.
     * On a floor of its own north of the main area ({@code z0} is its corner).
     */
    private static CompletableFuture<Void> replaceMirrorRefusedCopy(SelfTestRun r, Events events, int x0, int z0) {
        Box area = box(x0, FLOOR, z0, x0 + 11, FLOOR + 3, z0 + 7);
        r.claim(area);
        for (BlockPos cell : SelfTestRun.cells(area)) {
            set(r, cell, cell.getY() == FLOOR ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        }
        BlockPos original = new BlockPos(x0 + 2, FLOOR + 1, z0 + 4);
        // Mirrored across the plane x = x0 + 6 (a block edge): x0 + 2 lands on x0 + 9.
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 2 * (x0 + 6), 2 * (z0 + 4));
        BlockPos image = new BlockPos(x0 + 9, FLOOR + 1, z0 + 4);
        for (BlockPos chest : List.of(original, image)) {
            set(r, chest, Blocks.CHEST.defaultBlockState());
            fillChest(r, chest, new ItemStack(Items.DIAMOND, 5));
        }
        r.stand(x0 + 5.5, FLOOR + 1, z0 + 1.5, 0f, new ItemStack(Items.STONE, 64));
        int[] before = r.snapshot(area);
        events.take();
        events.cancelPlaceAt = image;
        int powers = BuilderPower.REPLACE.bit() | BuilderPower.MIRROR.bit();
        BuilderOutcome placed = r.service.builderPlace(r.player, new C2S.BuilderPlace(seq++, false,
                new dev.sculptory.core.BlockPos(original.getX(), original.getY(), original.getZ()), Facing.WEST, 0f,
                0.5f, 0.5f, powers, mirror));
        events.cancelPlaceAt = null;
        List<String> seen = events.take();
        boolean replaced = r.level.getBlockState(original).is(Blocks.STONE);
        ItemStack kept = chestItem(r, image);
        boolean copyFull = r.level.getBlockState(image).is(Blocks.CHEST) && kept.is(Items.DIAMOND) && kept.getCount() == 5;
        r.record("builder: a Replace copy another plugin refuses leaves its chest untouched and unrecorded",
                placed.accepted() && placed.changed() == 1 && placed.skipped() == 1 && replaced && copyFull
                        && labels(r).equals(List.of("Replace · 1 block")) && r.countDiffering(area, before) == 1,
                placed + ", original replaced " + replaced + ", the copy's chest holds " + kept + ", history "
                        + labels(r) + ", " + r.countDiffering(area, before) + " cells differ, events " + seen);
        return r.undo().thenAccept(result -> {
            ItemStack back = chestItem(r, original);
            ItemStack still = chestItem(r, image);
            r.record("builder: its undo puts the original chest back full and leaves the copy's alone",
                    r.countDiffering(area, before) == 0 && back.is(Items.DIAMOND) && back.getCount() == 5
                            && still.is(Items.DIAMOND) && still.getCount() == 5 && labels(r).isEmpty(),
                    result.outcome() + ", " + r.countDiffering(area, before) + " cells differ, the original holds "
                            + back + ", the copy's " + still + ", history " + labels(r));
        });
    }

    /**
     * A Keep shape bed whose head lands in a WorldGuard region (the head's cell only) is refused whole: both halves
     * stay air, no step is added, and the comparator beside the head (reading a lectern with a book, set without
     * updates, so off) gets no update from the revert: no tick is scheduled and it is still off 10 ticks later.
     */
    private static CompletableFuture<Void> regionAndComparator(SelfTestRun r, Events events, int x0, int z0, Box work) {
        if (r.regionChecksSkipped("builder: WorldGuard region")) return CompletableFuture.completedFuture(null);
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
