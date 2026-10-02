package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.brush.BrushKernels;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.StrokeState;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.SnapshotWorld;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.server.engine.DabOutcome;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.platform.WriteOptions;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.test.TimedTaskRunner;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;

/**
 * Terrain brushes on Chipped and Farmer's Delight terrain: Raise, Lower, Smooth and
 * Flatten on rich soil, farmland and Chipped stone; Paint and Palette with modded materials; block and exact-state
 * masks (plain and inverted) on modded blocks. The server's strokes equal the shared kernel run on a snapshot (what
 * the client predicts), a cooking pot among them keeps its contents, and undoing every stroke is exact.
 */
public final class FidelityBrushGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;
    private static final int BASE = 100;

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fidelity_brush", tickLimit = LIMIT)
    public void moddedTerrainBrushesMatchTheKernelAndUndoExactly(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 230);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 90, z0, x0 + 47, 170, z0 + 47);
        loadAndForce(world, area);
        terrain(h, x0, z0, 48, 48);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        int cabbages = h.state("farmersdelight:cabbages[age=5]");
        int wildCabbages = h.state("farmersdelight:wild_cabbages");
        int farmland = h.state("farmersdelight:rich_soil_farmland[moisture=7]");
        int richSoil = h.state("farmersdelight:rich_soil");
        int riceLower = h.state("farmersdelight:wild_rice[half=lower,waterlogged=true]");
        int riceUpper = h.state("farmersdelight:wild_rice[half=upper,waterlogged=false]");
        for (int dx = 4; dx < 44; dx += 3) {
            for (int dz = 4; dz < 44; dz += 2) {
                int x = x0 + dx, z = z0 + dz, top = top(world, x, z);
                int ground = Block.getRawIdFromState(world.getBlockState(pos(x, top, z)));
                if (ground == farmland) writer.write(x, top + 1, z, cabbages, null);
                else if (ground == richSoil && (dx + dz) % 4 == 1) writer.write(x, top + 1, z, wildCabbages, null);
                else if (ground == richSoil && (dx + dz) % 4 == 3) {
                    // Two-block wild rice: brushes move or remove both halves, never one.
                    writer.write(x, top + 1, z, riceLower, null);
                    writer.write(x, top + 2, z, riceUpper, null);
                }
            }
        }
        // A cooking pot with ingredients in the middle of the strokes: a structure the brushes never sculpt.
        int potX = x0 + 22, potZ = z0 + 22, potY = top(world, potX, potZ) + 1;
        writer.write(potX, potY, potZ, h.state("farmersdelight:cooking_pot[facing=north,support=none,waterlogged=false]"),
                null);
        CookingPotBlockEntity pot = FidelitySupport.entity(world, potX, potY, potZ, CookingPotBlockEntity.class);
        pot.getInventory().setStackInSlot(2, new ItemStack(Items.CARROT, 3));
        writer.write(x0 + 26, top(world, x0 + 26, z0 + 18) + 1, z0 + 18,
                h.state("chipped:big_lantern[facing=north,waterlogged=false]"), null);
        WorldSnapshot[] before = new WorldSnapshot[1];
        SnapshotWorld[] snapshot = new SnapshotWorld[1];
        NbtCompound[] potNbt = new NbtCompound[1];

        int bricks = h.state("chipped:angry_mossy_stone_bricks");
        Pattern mix = new Pattern.Weighted(new int[] {h.state("chipped:boxed_oak_planks"), richSoil,
                h.state("chipped:bundled_acacia_log[axis=y]")}, new int[] {3, 2, 1}, 11L);
        SurfaceMask onRichSoil = new SurfaceMask.SurfaceBlocks(
                new CellMask.Blocks(List.of(new NamespacedId("farmersdelight:rich_soil"))));
        SurfaceMask onWetFarmland = new SurfaceMask.SurfaceBlocks(new CellMask.States(new int[] {farmland}));
        SurfaceMask notOnPlanks = new SurfaceMask.Not(new SurfaceMask.SurfaceBlocks(
                new CellMask.Blocks(List.of(new NamespacedId("chipped:boxed_oak_planks")))));
        List<BrushSpec> specs = List.of(
                new BrushSpec(BrushTool.RAISE, 5, 0.9f, Falloff.SMOOTH, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L),
                new BrushSpec(BrushTool.LOWER, 4, 1f, Falloff.LINEAR, Shape.SQUARE, null, SurfaceMask.ANY, 0, 0, 2L),
                new BrushSpec(BrushTool.SMOOTH, 6, 0.7f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 3L),
                new BrushSpec(BrushTool.PAINT, 4, 1f, Falloff.CONSTANT, Shape.CIRCLE, new Pattern.Single(bricks), onRichSoil,
                        1, 0, 4L),
                new BrushSpec(BrushTool.PALETTE, 5, 1f, Falloff.CONSTANT, Shape.SQUARE, mix, onWetFarmland, 2, 0, 5L),
                new BrushSpec(BrushTool.PAINT, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, new Pattern.Single(farmland),
                        notOnPlanks, 1, 0, 6L),
                new BrushSpec(BrushTool.FLATTEN, 6, 0.8f, Falloff.SMOOTH, Shape.CIRCLE, null, onRichSoil, 0, 106, 7L));
        List<List<Dab>> strokes = new ArrayList<>();
        for (int s = 0; s < specs.size(); s++) {
            List<Dab> dabs = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                int x16 = (x0 + 12 + i + s * 2) * 16 + (i * 5) % 16;
                int z16 = (z0 + 14 + (i * (s + 1)) % 18) * 16 + (i * 3) % 16;
                dabs.add(new Dab(i, x16, (108 + i % 3) * 16 + 4, z16, 150 + (i * 23) % 106));
            }
            strokes.add(dabs);
        }

        int[] seq = {1};
        TimedTaskRunner runner = context.createTimedTaskRunner()
                .createAndAdd(FidelitySupport.settled(context, world, area))
                .createAndAdd(() -> {
                    before[0] = capture(world, area);
                    snapshot[0] = new SnapshotWorld(world, h.runtime.states(), area);
                    potNbt[0] = pot.createNbtWithId(world.getRegistryManager());
                });
        for (int s = 0; s < specs.size(); s++) {
            int current = s;
            List<DabOutcome> outcomes = new ArrayList<>();
            // Sends the stroke once (only a refused begin, before anything is sent, retries); the outcomes are
            // checked in the next step, so a rejected batch fails with its reason instead of being sent again.
            runner.createAndAdd(() -> {
                begin(h, 60 + current, specs.get(current));
                List<Dab> dabs = strokes.get(current);
                for (int from = 0; from < dabs.size(); from += 8) {
                    outcomes.add(h.service.dabs(h.player, 60 + current, seq[0]++,
                            dabs.subList(from, Math.min(dabs.size(), from + 8))));
                }
            });
            runner.createAndAdd(() -> {
                for (int b = 0; b < outcomes.size(); b++) {
                    check(outcomes.get(b).accepted(), "stroke " + current + " batch " + b + ": " + outcomes.get(b));
                }
            });
            runner.createAndAdd(() -> check(h.acks.seqs.size() == seq[0] - 1, "stroke " + current + " still applying"));
            runner.createAndAdd(() -> h.service.endStroke(h.player, 60 + current));
        }
        // The replay mutates the snapshot, so it runs once; a failed check is retried against the same verdict.
        String[] replay = {null};
        runner.createAndAdd(() -> {
            check(h.events.dabRejections.isEmpty(), "dabs rejected: " + h.events.dabRejections);
            if (replay[0] == null) replay[0] = replay(specs, strokes, snapshot[0]);
            check(replay[0].isEmpty(), replay[0]);
            int mismatches = 0;
            String first = null;
            for (int y = area.min().y(); y <= area.max().y(); y++) {
                for (int z = area.min().z(); z <= area.max().z(); z++) {
                    for (int x = area.min().x(); x <= area.max().x(); x++) {
                        int server = Block.getRawIdFromState(world.getBlockState(pos(x, y, z)));
                        int kernel = snapshot[0].get(x, y, z);
                        if (server == kernel) continue;
                        mismatches++;
                        if (first == null) {
                            first = x + "," + y + "," + z + ": server " + Block.getStateFromRawId(server) + ", kernel "
                                    + Block.getStateFromRawId(kernel);
                        }
                    }
                }
            }
            check(mismatches == 0, mismatches + " cells differ; first " + first);
            checkRicePairs(world, before[0], riceLower, riceUpper);
            List<HistoryEntry> entries = h.service.historyService().undoEntries(h.player.getUuid());
            check(entries.size() == specs.size(), entries.size() + " history entries for " + specs.size() + " strokes");
            CookingPotBlockEntity after = FidelitySupport.entity(world, potX, potY, potZ, CookingPotBlockEntity.class);
            check(potNbt[0].equals(after.createNbtWithId(world.getRegistryManager())),
                    "the brushes changed the cooking pot");
        });
        RecordingListener[] undo = {null};
        for (int s = 0; s < specs.size(); s++) {
            int step = s;
            runner.createAndAdd(() -> {
                if (undo[0] != null) check(undo[0].result != null, "undo " + (step - 1) + " running");
                if (undo[0] != null) {
                    check(undo[0].result.outcome() == JobOutcome.COMPLETED && undo[0].result.skippedConflicts() == 0,
                            "undo " + (step - 1) + ": " + undo[0].result);
                }
                undo[0] = new RecordingListener();
                h.undo(undo[0]);
            });
        }
        runner.createAndAdd(() -> check(undo[0].result != null, "last undo running"));
        runner.createAndAdd(() -> {
            check(undo[0].result.outcome() == JobOutcome.COMPLETED && undo[0].result.skippedConflicts() == 0,
                    "last undo: " + undo[0].result);
            checkSame(before[0], capture(world, area), "after undoing every stroke");
            forceChunks(world, area, false);
            h.close();
        });
        runner.completeIfSuccessful();
    }

    /**
     * Every wild rice half has its other half (the brushes left no half plant behind), and the strokes did reach some
     * wild rice ({@code before} is the area before them).
     */
    private static void checkRicePairs(ServerWorld world, WorldSnapshot before, int lower, int upper) {
        Box area = before.box;
        int pairs = 0, gone = 0;
        for (int y = area.min().y(); y < area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    int here = Block.getRawIdFromState(world.getBlockState(pos(x, y, z)));
                    int above = Block.getRawIdFromState(world.getBlockState(pos(x, y + 1, z)));
                    if ((here == lower) != (above == upper)) {
                        throw new GameTestException("a half wild rice at " + x + "," + y + "," + z + ": "
                                + world.getBlockState(pos(x, y, z)) + " under " + world.getBlockState(pos(x, y + 1, z)));
                    }
                    if (here == lower) pairs++;
                    if (before.get(x, y, z) == lower && here != lower) gone++;
                }
            }
        }
        check(pairs > 0 && gone > 0, pairs + " wild rice left, " + gone + " moved or removed");
    }

    /**
     * Runs each stroke's kernel on the snapshot, in order, writing through; "" when every stroke wrote something,
     * else what went wrong first.
     */
    private static String replay(List<BrushSpec> specs, List<List<Dab>> strokes, SnapshotWorld snapshot) {
        for (int s = 0; s < specs.size(); s++) {
            BrushSpec spec = specs.get(s);
            StrokeState state = new StrokeState();
            int[] writes = {0};
            try {
                for (Dab dab : strokes.get(s)) {
                    BrushKernels.forTool(spec.tool()).apply(spec, dab, state, snapshot, (x, y, z, handle) -> {
                        snapshot.set(x, y, z, handle);
                        writes[0]++;
                    });
                }
            } catch (RuntimeException e) {
                return spec.tool() + " (stroke " + s + ") failed on the snapshot: " + e.getMessage();
            }
            if (writes[0] == 0) return spec.tool() + " (stroke " + s + ") wrote nothing on the snapshot";
        }
        return "";
    }

    /**
     * Chipped stone bricks from y {@value #BASE} with a varying top (104-110) of rich soil, wet rich soil farmland and
     * Chipped planks.
     */
    private static void terrain(Harness h, int x0, int z0, int w, int d) {
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        int stone = h.state("chipped:angry_mossy_stone_bricks");
        int[] tops = {h.state("farmersdelight:rich_soil"), h.state("farmersdelight:rich_soil_farmland[moisture=7]"),
            h.state("chipped:boxed_oak_planks"), h.state("farmersdelight:rich_soil")};
        for (int dx = 0; dx < w; dx++) {
            for (int dz = 0; dz < d; dz++) {
                int top = 104 + Math.floorMod(dx / 3 + dz / 4 + ((dx * 7 + dz * 3) & 1), 7);
                int surface = tops[Math.floorMod(dx / 5 + dz / 3, tops.length)];
                for (int y = BASE; y <= top; y++) writer.write(x0 + dx, y, z0 + dz, y == top ? surface : stone, null);
            }
        }
    }

    /** The topmost non-air y of a column (from y 140 down to the floor). */
    private static int top(ServerWorld world, int x, int z) {
        for (int y = 140; y >= BASE; y--) {
            if (!world.getBlockState(pos(x, y, z)).isAir()) return y;
        }
        return BASE;
    }

    private static void begin(Harness h, int strokeId, BrushSpec spec) {
        try {
            h.service.beginStroke(h.player, strokeId, spec);
        } catch (EditRejected e) {
            throw new GameTestException("stroke refused: " + e.getMessage());
        }
    }
}
