package dev.sculptory.paper.devtest;

import static dev.sculptory.paper.devtest.SelfTestRun.aabb;
import static dev.sculptory.paper.devtest.SelfTestRun.box;

import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.region.Facing;
import dev.sculptory.paper.SculptoryPlugin;
import dev.sculptory.paper.nms.PaperAccess;
import dev.sculptory.paper.perm.WorldGuardTestRegions;
import dev.sculptory.protocol.v2.BuilderPower;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.server.engine.BuilderOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Builder mode on Paper (PaperBuilderSteps), after the shared BuilderModeGameTests: a placement and a break are each
 * one step undone exactly and fire Bukkit's block events; without Keep shape the game's own changes around a placement
 * (a fence joining its neighbour) are recorded with it, with Keep shape nothing around reacts (the old fence does not
 * join, sand whose support is broken stays); a placement or break another plugin cancels is refused and changes
 * nothing; a Keep shape bed whose head lands in a WorldGuard region is refused whole and put back without updating the
 * comparator beside its head. Runs on a stone floor of its own at y 100; the area is put back at the end of the run.
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
                .thenCompose(v -> regionAndComparator(r, events, x0, z0, work));
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
