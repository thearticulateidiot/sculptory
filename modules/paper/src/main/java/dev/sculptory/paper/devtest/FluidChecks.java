package dev.sculptory.paper.devtest;

import static dev.sculptory.paper.devtest.SelfTestRun.box;
import static dev.sculptory.paper.devtest.SelfTestRun.cells;

import dev.sculptory.core.Box;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.mc.world.FluidTrails;
import dev.sculptory.paper.SculptoryPlugin;
import dev.sculptory.paper.nms.PaperAccess;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluids;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;

/**
 * Fluid trails on Paper (PaperFluidTrails), one covered case and the recorded result of uncovered ones:
 * <ul>
 *   <li>covered: water written by a fill (still, physics off) is set flowing; it spreads over a stone floor, Bukkit's
 *       flow and level events record it into the fill's trail, its marks reach the chunk's data container at a full
 *       save, and the fill's undo takes every cell of it back;</li>
 *   <li>only what the fluid explains: a furnace beside the flow that another plugin lights in the flow's own tick is
 *       left lit by the fill's undo; a stone a piston pushes into the flowed cell later that tick is left by the undo;
 *       water into a tall plant beside another entry's lava (whose form event comes inside the water's effect):
 *       the undo puts back both halves;</li>
 *   <li>uncovered (information, not failures): the same water washing away the first dust of a powered redstone line:
 *       the line's far dust loses power in that same tick with no event, so after the undo it stays unpowered; and a
 *       block another plugin sets in a marked cell keeps the cell's mark.</li>
 * </ul>
 */
final class FluidChecks {
    private static final int FLOOR = 100;

    private FluidChecks() {
    }

    static CompletableFuture<Void> run(SelfTestRun r) {
        int x0 = r.baseX + 448, z0 = r.baseZ; // chunk-aligned
        Box area = box(x0, FLOOR, z0, x0 + 31, FLOOR + 4, z0 + 15);
        r.claim(area);
        for (BlockPos cell : cells(area)) {
            BlockState state = cell.getY() == FLOOR ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
            if (r.level.getBlockState(cell) != state) PaperAccess.setBlockWithoutCallbacks(r.level, cell, state);
            r.level.getChunkSource().blockChanged(cell);
        }
        return covered(r, x0, z0).thenCompose(v -> redstone(r, x0 + 16, z0)).thenCompose(v -> otherPlugin(r, x0, z0))
                .thenCompose(v -> furnaceBeside(r, x0, z0 + 16))
                .thenCompose(v -> unexplained(r, x0 + 16, z0 + 16));
    }

    /** Water from a fill flows out; its trail and marks are recorded; the fill's undo takes all of it back. */
    private static CompletableFuture<Void> covered(SelfTestRun r, int x0, int z0) {
        Box floor = box(x0, FLOOR + 1, z0, x0 + 15, FLOOR + 1, z0 + 15);
        int[] before = r.snapshot(floor);
        BlockPos source = new BlockPos(x0 + 8, FLOOR + 1, z0 + 8);
        Box one = box(source.getX(), source.getY(), source.getZ(), source.getX(), source.getY(), source.getZ());
        int water = Block.getId(Blocks.WATER.defaultBlockState());
        UUID[] entry = {null};
        return r.fill(one, water).thenCompose(result -> {
            entry[0] = newest(r);
            // The written water lies still (physics off) until something updates it: its own tick, as a neighbour's
            // change would set off.
            r.level.scheduleTick(source, Fluids.WATER, 1);
            return r.ticks(60);
        }).thenCompose(v -> {
            int wet = 0;
            for (BlockPos cell : cells(floor)) if (!r.level.getFluidState(cell).isEmpty()) wet++;
            long trail = r.host.engine().fluidTrails().trailCells(r.level, entry[0]);
            BlockPos edge = source.east(3);
            boolean marked = r.host.engine().fluidTrails().markedBy(r.level, edge, entry[0]);
            r.record("fluid trails: water a fill wrote flows out into its entry's trail", wet > 20 && trail >= wet - 1
                    && marked, wet + " wet cells, " + trail + " cells in the trail, the cell 3 east marked " + marked);
            // A full save writes the column's marks into the chunk's data container.
            r.level.save(null, false, false);
            LevelChunk chunk = r.level.getChunkSource().getChunkNow(source.getX() >> 4, source.getZ() >> 4);
            boolean saved = chunk != null && chunk.persistentDataContainer.getRaw().containsKey(FluidTrails.NBT_KEY);
            r.record("fluid trails: the marks go into the chunk's data container", saved,
                    "key " + FluidTrails.NBT_KEY + " present " + saved);
            return r.undo();
        }).thenCompose(result -> r.ticks(20).thenApply(v -> result)).thenAccept(result -> {
            int differ = r.countDiffering(floor, before);
            r.record("fluid trails: the fill's undo takes the flowed water back", differ == 0,
                    result.outcome() + ", " + result.changed() + " changed, " + differ + " cells differ");
        });
    }

    /**
     * A redstone block powering a line of six dust eastwards; water written above the first dust and set flowing washes
     * it away. Reported: which cells differ after the fill's undo (the far dust that lost power in the same tick).
     */
    private static CompletableFuture<Void> redstone(SelfTestRun r, int x0, int z0) {
        int z = z0 + 8;
        Box line = box(x0 + 2, FLOOR + 1, z, x0 + 8, FLOOR + 1, z);
        r.level.setBlockAndUpdate(new BlockPos(x0 + 2, FLOOR + 1, z), Blocks.REDSTONE_BLOCK.defaultBlockState());
        for (int x = x0 + 3; x <= x0 + 8; x++) {
            r.level.setBlockAndUpdate(new BlockPos(x, FLOOR + 1, z), Blocks.REDSTONE_WIRE.defaultBlockState());
        }
        Box all = box(x0, FLOOR + 1, z0, x0 + 15, FLOOR + 2, z0 + 15);
        int[] before = r.snapshot(all);
        BlockPos source = new BlockPos(x0 + 3, FLOOR + 2, z);
        Box one = box(source.getX(), source.getY(), source.getZ(), source.getX(), source.getY(), source.getZ());
        return r.fill(one, Block.getId(Blocks.WATER.defaultBlockState())).thenCompose(result -> {
            r.level.scheduleTick(source, Fluids.WATER, 1);
            return r.ticks(60);
        }).thenCompose(v -> r.undo()).thenCompose(result -> r.ticks(10)).thenAccept(v -> {
            List<String> differ = new ArrayList<>();
            List<BlockPos> cells = cells(all);
            int[] now = r.snapshot(all);
            for (int i = 0; i < now.length; i++) {
                if (now[i] == before[i]) continue;
                BlockState was = Block.stateById(before[i]), is = Block.stateById(now[i]);
                differ.add(cells.get(i).toShortString() + " " + describe(was) + " -> " + describe(is));
            }
            r.info("fluid trails, uncovered: a redstone line the water cuts", differ.isEmpty()
                    ? "the undo restored everything" : differ.size() + " cells differ after the undo: " + differ);
            r.record("fluid trails: the redstone check's own cells are restored where events cover them",
                    r.level.getBlockState(new BlockPos(x0 + 3, FLOOR + 1, z)).is(Blocks.REDSTONE_WIRE)
                            && r.level.getFluidState(source).isEmpty(),
                    "first dust back " + r.level.getBlockState(new BlockPos(x0 + 3, FLOOR + 1, z)));
        });
    }

    /** A block another plugin sets in a cell the fill's water marks: reported whether the mark stays. */
    private static CompletableFuture<Void> otherPlugin(SelfTestRun r, int x0, int z0) {
        BlockPos cell = new BlockPos(x0 + 2, FLOOR + 1, z0 + 2);
        Box one = box(cell.getX(), cell.getY(), cell.getZ(), cell.getX(), cell.getY(), cell.getZ());
        return r.fill(one, Block.getId(Blocks.WATER.defaultBlockState())).thenAccept(result -> {
            UUID entry = newest(r);
            boolean before = r.host.engine().fluidTrails().markedBy(r.level, cell, entry);
            r.level.setBlock(cell, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            boolean after = r.host.engine().fluidTrails().markedBy(r.level, cell, entry);
            r.info("fluid trails, uncovered: another plugin's block in a marked cell", "marked before " + before
                    + ", after the plugin set stone there " + after + (after ? " (the mark stays; on Fabric the "
                    + "block-change hook clears it)" : ""));
        });
    }

    /** Lights the furnace beside a cell water flows into, during that flow's own event (another plugin, same tick). */
    static final class FurnaceLighter implements Listener {
        private final BlockPos furnace;
        int lit;

        FurnaceLighter(BlockPos furnace) {
            this.furnace = furnace;
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void flowed(BlockFromToEvent event) {
            org.bukkit.block.Block to = event.getToBlock();
            BlockPos into = new BlockPos(to.getX(), to.getY(), to.getZ());
            if (lit > 0 || into.distManhattan(furnace) != 1) return;
            ServerLevel level = ((CraftWorld) to.getWorld()).getHandle();
            BlockState state = level.getBlockState(furnace);
            if (!state.is(Blocks.FURNACE)) return;
            level.setBlock(furnace, state.setValue(AbstractFurnaceBlock.LIT, true), Block.UPDATE_ALL);
            lit++;
        }
    }

    /**
     * Only what the fluid explains is recorded: water a fill wrote flows past a furnace, and in the very tick the water
     * reaches the furnace's side, another plugin lights the furnace (from the flow's own event). The fill's undo takes
     * the water back and leaves the furnace lit: its change was never the water's.
     */
    private static CompletableFuture<Void> furnaceBeside(SelfTestRun r, int x0, int z0) {
        Box area = box(x0, FLOOR, z0, x0 + 15, FLOOR + 4, z0 + 15);
        r.claim(area);
        for (BlockPos cell : cells(area)) {
            BlockState state = cell.getY() == FLOOR ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
            if (r.level.getBlockState(cell) != state) PaperAccess.setBlockWithoutCallbacks(r.level, cell, state);
            r.level.getChunkSource().blockChanged(cell);
        }
        BlockPos source = new BlockPos(x0 + 8, FLOOR + 1, z0 + 8);
        BlockPos furnace = source.east(2).north();
        PaperAccess.setBlockWithoutCallbacks(r.level, furnace, Blocks.FURNACE.defaultBlockState());
        r.level.getChunkSource().blockChanged(furnace);
        FurnaceLighter lighter = new FurnaceLighter(furnace);
        Bukkit.getPluginManager().registerEvents(lighter, SculptoryPlugin.instance());
        r.cleanup(() -> HandlerList.unregisterAll(lighter));
        Box one = box(source.getX(), source.getY(), source.getZ(), source.getX(), source.getY(), source.getZ());
        return r.fill(one, Block.getId(Blocks.WATER.defaultBlockState())).thenCompose(result -> {
            r.level.scheduleTick(source, Fluids.WATER, 1);
            return r.ticks(60);
        }).thenCompose(v -> {
            HandlerList.unregisterAll(lighter);
            return r.undo();
        }).thenCompose(result -> r.ticks(20)).thenAccept(v -> {
            boolean lit = r.level.getBlockState(furnace).getValue(AbstractFurnaceBlock.LIT);
            int wet = 0;
            for (BlockPos cell : cells(box(x0, FLOOR + 1, z0, x0 + 15, FLOOR + 1, z0 + 15))) {
                if (!r.level.getFluidState(cell).isEmpty()) wet++;
            }
            r.record("fluid trails: the fill's undo leaves a furnace lit in the flow's tick alone",
                    lighter.lit == 1 && lit && wet == 0,
                    "lit by the flow's event " + lighter.lit + " time(s), lit after the undo " + lit + ", " + wet
                            + " wet cells left");
        });
    }

    /** Lays a stone floor with air above over {@code area} (its lowest layer the floor), physics-free. */
    private static void floor(SelfTestRun r, Box area) {
        for (BlockPos cell : cells(area)) {
            BlockState state = cell.getY() == FLOOR ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
            if (r.level.getBlockState(cell) != state) PaperAccess.setBlockWithoutCallbacks(r.level, cell, state);
            r.level.getChunkSource().blockChanged(cell);
        }
    }

    /** Powers a piston when water flows into the cell in front of the block it pushes (another plugin, same tick). */
    static final class PistonPowerer implements Listener {
        private final BlockPos into;
        private final BlockPos power;
        int powered;

        PistonPowerer(BlockPos into, BlockPos power) {
            this.into = into;
            this.power = power;
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void flowed(BlockFromToEvent event) {
            org.bukkit.block.Block to = event.getToBlock();
            if (powered > 0 || to.getX() != into.getX() || to.getY() != into.getY() || to.getZ() != into.getZ()) {
                return;
            }
            ServerLevel level = ((CraftWorld) to.getWorld()).getHandle();
            level.setBlock(power, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
            powered++;
        }
    }

    /**
     * Only the cell's own change is the fluid's: water flows into a cell, and in that same tick (its block events, after
     * the fluid's) a piston another plugin powered from the flow's event pushes a stone into that cell. The fill's undo
     * takes the water back and leaves the pushed stone: the cell's change by the piston was never the water's.
     */
    private static CompletableFuture<Void> pistonInto(SelfTestRun r, int x0, int z0) {
        BlockPos source = new BlockPos(x0 + 4, FLOOR + 1, z0 + 4);
        BlockPos into = source.north();
        BlockPos pushed = into.west();
        BlockPos piston = pushed.west();
        set(r, pushed, Blocks.STONE.defaultBlockState());
        set(r, piston, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
        PistonPowerer powerer = new PistonPowerer(into, piston.north());
        Bukkit.getPluginManager().registerEvents(powerer, SculptoryPlugin.instance());
        r.cleanup(() -> HandlerList.unregisterAll(powerer));
        Box one = box(source.getX(), source.getY(), source.getZ(), source.getX(), source.getY(), source.getZ());
        return r.fill(one, Block.getId(Blocks.WATER.defaultBlockState())).thenCompose(result -> {
            r.level.scheduleTick(source, Fluids.WATER, 1);
            return r.ticks(60);
        }).thenCompose(v -> {
            HandlerList.unregisterAll(powerer);
            boolean pushedIn = r.level.getBlockState(into).is(Blocks.STONE);
            r.info("fluid trails: the piston pushed its stone into the flowed cell", "powered " + powerer.powered
                    + " time(s), stone in the cell " + pushedIn);
            return r.undo();
        }).thenCompose(result -> r.ticks(20)).thenAccept(v -> {
            boolean stone = r.level.getBlockState(into).is(Blocks.STONE);
            boolean wetSource = !r.level.getFluidState(source).isEmpty();
            r.record("fluid trails: the fill's undo leaves a block a piston pushed into the flowed cell",
                    powerer.powered == 1 && stone && !wetSource,
                    "powered " + powerer.powered + ", stone still in the cell " + stone + ", water left at the source "
                            + wetSource);
        });
    }

    /**
     * An event inside another change's effect is a recording of its own and ends nothing early: water flows into a
     * tall plant's lower half beside lava another entry wrote; the water's neighbour update turns the lava to obsidian
     * (a form event, the lava entry's) before the plant's upper half pops off (the water's shape update). The water
     * fill's undo puts back both halves of the plant.
     */
    private static CompletableFuture<Void> plantBesideLava(SelfTestRun r, int x0, int z0) {
        BlockPos source = new BlockPos(x0 + 3, FLOOR + 1, z0 + 4);
        BlockPos lower = source.east();
        BlockPos lava = lower.east();
        set(r, lower, Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER));
        set(r, lower.above(), Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF,
                DoubleBlockHalf.UPPER));
        Box lavaBox = box(lava.getX(), lava.getY(), lava.getZ(), lava.getX(), lava.getY(), lava.getZ());
        Box waterBox = box(source.getX(), source.getY(), source.getZ(), source.getX(), source.getY(), source.getZ());
        return r.fill(lavaBox, Block.getId(Blocks.LAVA.defaultBlockState()))
                .thenCompose(result -> r.fill(waterBox, Block.getId(Blocks.WATER.defaultBlockState())))
                .thenCompose(result -> {
                    r.level.scheduleTick(source, Fluids.WATER, 1);
                    return r.ticks(40);
                }).thenCompose(v -> {
                    boolean obsidian = r.level.getBlockState(lava).is(Blocks.OBSIDIAN);
                    boolean popped = r.level.getBlockState(lower.above()).isAir();
                    r.info("fluid trails: water into a plant beside lava", "lava turned to obsidian " + obsidian
                            + ", upper half gone " + popped);
                    return r.undo();
                }).thenCompose(result -> r.ticks(10)).thenAccept(v -> {
                    BlockState below = r.level.getBlockState(lower), above = r.level.getBlockState(lower.above());
                    boolean whole = below.is(Blocks.TALL_GRASS) && above.is(Blocks.TALL_GRASS)
                            && above.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER;
                    r.record("fluid trails: the water's undo puts back both halves of the plant beside lava", whole,
                            "lower " + below + ", upper " + above);
                });
    }

    /**
     * Two entries' fluids meeting: lava one fill wrote falls onto water another fill wrote, turning it to stone. The
     * stone is the water entry's change alone (the lava's flow announced the same cell first, but its own change never
     * happened there), so undoing both entries, in either order, puts the world back exactly.
     */
    private static CompletableFuture<Void> lavaOntoWater(SelfTestRun r, int x0, int z0, boolean lavaFirst) {
        BlockPos water = new BlockPos(x0 + 4, FLOOR + 1, z0 + 12);
        BlockPos lava = water.above();
        Box around = box(x0, FLOOR + 1, z0 + 6, x0 + 15, FLOOR + 3, z0 + 15);
        int[] before = r.snapshot(around);
        Box waterBox = box(water.getX(), water.getY(), water.getZ(), water.getX(), water.getY(), water.getZ());
        Box lavaBox = box(lava.getX(), lava.getY(), lava.getZ(), lava.getX(), lava.getY(), lava.getZ());
        int waterId = Block.getId(Blocks.WATER.defaultBlockState());
        int lavaId = Block.getId(Blocks.LAVA.defaultBlockState());
        CompletableFuture<?> filled = lavaFirst
                ? r.fill(lavaBox, lavaId).thenCompose(v -> r.fill(waterBox, waterId))
                : r.fill(waterBox, waterId).thenCompose(v -> r.fill(lavaBox, lavaId));
        String order = lavaFirst ? "the water undone first" : "the lava undone first";
        return filled.thenCompose(v -> {
            r.level.scheduleTick(lava, Fluids.LAVA, 1);
            return r.ticks(160);
        }).thenCompose(v -> {
            r.info("fluid trails: lava onto water (" + order + ")", "the water cell is now "
                    + r.level.getBlockState(water));
            return r.undo();
        }).thenCompose(v -> r.undo()).thenCompose(v -> r.ticks(40)).thenAccept(v -> {
            int fluid = 0;
            for (BlockPos cell : cells(around)) if (!r.level.getFluidState(cell).isEmpty()) fluid++;
            int differ = r.countDiffering(around, before);
            r.record("fluid trails: lava onto another entry's water, both undone (" + order + "): world exact",
                    differ == 0 && fluid == 0, differ + " cells differ, " + fluid + " fluid cells left");
        });
    }

    /** The cells the fluid does not explain, in an area of their own: a piston's push, a plant beside lava. */
    private static CompletableFuture<Void> unexplained(SelfTestRun r, int x0, int z0) {
        Box area = box(x0, FLOOR, z0, x0 + 15, FLOOR + 4, z0 + 15);
        r.claim(area);
        floor(r, area);
        return pistonInto(r, x0, z0).thenCompose(v -> plantBesideLava(r, x0 + 8, z0))
                .thenCompose(v -> lavaOntoWater(r, x0, z0, false))
                .thenCompose(v -> lavaOntoWater(r, x0, z0, true));
    }

    private static void set(SelfTestRun r, BlockPos pos, BlockState state) {
        PaperAccess.setBlockWithoutCallbacks(r.level, pos, state);
        r.level.getChunkSource().blockChanged(pos);
    }

    private static UUID newest(SelfTestRun r) {
        List<HistoryEntry> entries = r.service.historyService().undoEntries(r.player.getUUID());
        return entries.isEmpty() ? null : entries.get(0).id();
    }

    private static String describe(BlockState state) {
        if (state.getBlock() instanceof RedStoneWireBlock) return "dust power " + state.getValue(RedStoneWireBlock.POWER);
        return state.getBlock().getDescriptionId().replace("block.minecraft.", "");
    }
}
