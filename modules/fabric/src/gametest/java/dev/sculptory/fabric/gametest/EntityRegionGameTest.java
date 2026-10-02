package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;
import static dev.sculptory.fabric.gametest.EntitiesGameTest.entitiesIn;
import static dev.sculptory.fabric.gametest.EntitiesGameTest.load;
import static dev.sculptory.fabric.gametest.EntitiesGameTest.once;
import static dev.sculptory.fabric.gametest.EntitiesGameTest.ready;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.engine.impl.ServerClipboards;
import dev.sculptory.fabric.gametest.ClipTestSupport.Captured;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.fabric.world.FabricEntities;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.RunOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;

/**
 * Entities with regions other than boxes: an entity belongs
 * to a shape or a cell set only when its block is one of the region's cells, so copies, cuts, moves and stacks of
 * sparse selections take exactly their own entities (and a mask's); a sparse selection needs only its own chunks
 * loaded, however far apart. Work regions: {@code regionCorner} slots 662-664.
 */
public final class EntityRegionGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    private static Entity stand(ServerWorld world, double x, int y, double z) {
        Entity stand = load(world, "minecraft:armor_stand", x, y, z, 0f, nbt -> nbt.putBoolean("NoGravity", true));
        check(world.spawnEntity(stand), "a stand at " + x + "," + y + "," + z);
        return stand;
    }

    private static Set<UUID> ids(List<Entity> entities) {
        return entities.stream().map(Entity::getUuid).collect(Collectors.toSet());
    }

    private static void run(Harness h, OpSpec op, RecordingListener listener) {
        try {
            h.service.run(h.player, op, RunOptions.DEFAULT, listener);
        } catch (EditRejected e) {
            throw new GameTestException("refused: " + e.getMessage());
        }
    }

    private static void copy(ServerClipboards clips, Harness h, Region region, boolean cut, CellMask mask,
                             RecordingListener erase, Captured<ClipboardService.ClipboardInfo> reply) {
        try {
            clips.copy(h.player, region, region.bounds().min(), cut, mask, EntityFilter.DECORATIONS, erase, reply);
        } catch (EditRejected e) {
            throw new GameTestException("copy refused: " + e.getMessage());
        }
    }

    /**
     * A cell set of two 2 × 2 patches over a stone floor, with armor stands on three of its cells (one of them in a
     * glass block) and one on the floor between the patches, inside the set's bounds but not in the set. A copy takes the
     * three; a copy masked to air only the two in air; a cut removes the three and undo brings them back; a stack
     * copies the three; a move takes the three and leaves the fourth.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_entities_region_cells", tickLimit = LIMIT)
    public void cellSetsTakeOnlyTheirOwnEntities(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 662);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 32, y0 + 8, z0 + 64);
        loadAndForce(world, all);
        CellSet.Builder cells = CellSet.builder();
        for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 2; dz++) {
                cells.add(x0 + 1 + dx, y0 + 1, z0 + 1 + dz);
                cells.add(x0 + 9 + dx, y0 + 1, z0 + 9 + dz);
            }
        }
        Region set = new Region.Cells(cells.build());
        Box between = box(x0, y0, z0, x0 + 11, y0 + 2, z0 + 11);
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        List<Entity> inSet = new ArrayList<>();
        Entity[] outside = {null};
        Captured<ClipboardService.ClipboardInfo> copyAll = new Captured<>();
        Captured<ClipboardService.ClipboardInfo> copyAir = new Captured<>();
        Captured<ClipboardService.ClipboardInfo> cut = new Captured<>();
        RecordingListener erase = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        RecordingListener stack = new RecordingListener();
        RecordingListener move = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(() -> ready(world, all))
                .createAndAdd(once(() -> {
                    BlockWriter writer = h.runtime.writer(world, BlockWriter.Options.DEFAULT);
                    for (int x = x0; x <= x0 + 11; x++) {
                        for (int z = z0; z <= z0 + 11; z++) writer.write(x, y0, z, h.state("minecraft:stone"), null);
                    }
                    writer.write(x0 + 10, y0 + 1, z0 + 10, h.state("minecraft:glass"), null);
                    inSet.add(stand(world, x0 + 1.5, y0 + 1, z0 + 1.5));
                    inSet.add(stand(world, x0 + 2.5, y0 + 1, z0 + 1.5));
                    inSet.add(stand(world, x0 + 10.5, y0 + 1, z0 + 10.5)); // in the glass block
                    outside[0] = stand(world, x0 + 5.5, y0 + 1, z0 + 5.5);
                    copy(clips, h, set, false, CellMask.ANY, null, copyAll);
                }))
                .createAndAdd(() -> check(copyAll.finished(), "copy running"))
                .createAndAdd(once(() -> {
                    check(copyAll.get("the copy").entities() == 3, "the set's three: " + copyAll.value.entities());
                    copy(clips, h, set, false, new CellMask.States(new int[] {h.state("minecraft:air")}), null, copyAir);
                }))
                .createAndAdd(() -> check(copyAir.finished(), "masked copy running"))
                .createAndAdd(once(() -> {
                    check(copyAir.get("the masked copy").entities() == 2, "the two in air: " + copyAir.value.entities());
                    copy(clips, h, set, true, CellMask.ANY, erase, cut);
                }))
                .createAndAdd(() -> check(erase.result != null && cut.finished(), "cut running"))
                .createAndAdd(once(() -> {
                    check(cut.get("the cut").entities() == 3, "cut " + cut.value.entities());
                    check(erase.result.outcome() == JobOutcome.COMPLETED, "erase " + erase.result);
                    check(ids(entitiesIn(world, between)).equals(Set.of(outside[0].getUuid())),
                            "only the stand outside the set stays: " + entitiesIn(world, between));
                    check(world.getBlockState(pos(x0 + 10, y0 + 1, z0 + 10)).isAir(), "the set's glass was cut");
                    check(world.getBlockState(pos(x0 + 5, y0, z0 + 5)).isOf(Blocks.STONE), "the floor stays");
                    h.undo(undo);
                }))
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(once(() -> {
                    check(undo.result.skippedConflicts() == 0, "undo " + undo.result);
                    Set<UUID> expected = ids(inSet);
                    expected.add(outside[0].getUuid());
                    check(ids(entitiesIn(world, between)).equals(expected), "the same four are back");
                    check(world.getBlockState(pos(x0 + 10, y0 + 1, z0 + 10)).isOf(Blocks.GLASS), "the glass is back");
                    run(h, new OpSpec.Stack(set, 0, 0, 16, 1, EntityFilter.DECORATIONS), stack);
                }))
                .createAndAdd(() -> check(stack.result != null, "stack running"))
                .createAndAdd(once(() -> {
                    check(stack.result.outcome() == JobOutcome.COMPLETED, "stack " + stack.result);
                    check(entitiesIn(world, between.offset(0, 0, 16)).size() == 3, "the set's three copied: "
                            + entitiesIn(world, between.offset(0, 0, 16)));
                    check(entitiesIn(world, between).size() == 4, "the source is untouched");
                    run(h, new OpSpec.Move(set, new BlockPos(0, 0, 32), Transform.IDENTITY,
                            new Pattern.Single(h.state("minecraft:air")), EntityFilter.DECORATIONS), move);
                }))
                .createAndAdd(() -> check(move.result != null, "move running"))
                .createAndAdd(() -> {
                    check(move.result.outcome() == JobOutcome.COMPLETED, "move " + move.result);
                    check(entitiesIn(world, between.offset(0, 0, 32)).size() == 3, "the set's three moved: "
                            + entitiesIn(world, between.offset(0, 0, 32)));
                    check(ids(entitiesIn(world, between)).equals(Set.of(outside[0].getUuid())),
                            "the stand outside the set stays where it is");
                    entitiesIn(world, all).forEach(Entity::discard);
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A shape takes the entities on its own cells: 16 armor stands on a grid over the box of a flat ellipse. A copy takes
     * exactly those whose block the ellipse holds (some, not all), and a move takes them and leaves the others.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_entities_region_shape", tickLimit = LIMIT)
    public void shapesTakeOnlyTheirOwnEntities(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 663);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 48, y0 + 8, z0 + 32);
        loadAndForce(world, all);
        Box shapeBox = box(x0, y0 + 1, z0, x0 + 11, y0 + 1, z0 + 11);
        Region ellipse = new Region.Shape(shapeBox, ShapeKind.ELLIPSOID, Facing.UP);
        Box area = box(x0, y0, z0, x0 + 11, y0 + 2, z0 + 11);
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        List<Entity> stands = new ArrayList<>();
        Captured<ClipboardService.ClipboardInfo> copied = new Captured<>();
        RecordingListener move = new RecordingListener();
        int[] inside = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> ready(world, all))
                .createAndAdd(once(() -> {
                    for (int i = 0; i < 4; i++) {
                        for (int j = 0; j < 4; j++) {
                            int x = x0 + 3 * i, z = z0 + 3 * j;
                            stands.add(stand(world, x + 0.5, y0 + 1, z + 0.5));
                            if (ellipse.contains(x, y0 + 1, z)) inside[0]++;
                        }
                    }
                    check(inside[0] > 0 && inside[0] < 16, "the ellipse holds some of the stands' blocks: " + inside[0]);
                    copy(clips, h, ellipse, false, CellMask.ANY, null, copied);
                }))
                .createAndAdd(() -> check(copied.finished(), "copy running"))
                .createAndAdd(once(() -> {
                    check(copied.get("the copy").entities() == inside[0], "copied " + copied.value.entities()
                            + ", the ellipse holds " + inside[0]);
                    run(h, new OpSpec.Move(ellipse, new BlockPos(20, 0, 0), Transform.IDENTITY,
                            new Pattern.Single(h.state("minecraft:air")), EntityFilter.DECORATIONS), move);
                }))
                .createAndAdd(() -> check(move.result != null, "move running"))
                .createAndAdd(() -> {
                    check(move.result.outcome() == JobOutcome.COMPLETED, "move " + move.result);
                    check(entitiesIn(world, area.offset(20, 0, 0)).size() == inside[0], "moved "
                            + entitiesIn(world, area.offset(20, 0, 0)).size());
                    check(entitiesIn(world, area).size() == 16 - inside[0], "left " + entitiesIn(world, area).size());
                    entitiesIn(world, all).forEach(Entity::discard);
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A sparse selection needs only its own chunks loaded: two patches 20 chunks apart, only their two chunks loaded (the
     * chunks between, inside its bounds, are not). A copy and a stack taking entities are not refused and take the two
     * stands, as they would with every chunk loaded.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_entities_region_sparse", tickLimit = LIMIT)
    public void sparseSelectionsNeedOnlyTheirOwnChunksLoaded(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 664);
        int x0 = at[0], y0 = 100, z0 = at[1];
        int far = 20 * 16;
        Box first = box(x0, y0 - 2, z0, x0 + 15, y0 + 8, z0 + 15);
        Box second = first.offset(far, 0, 0);
        loadAndForce(world, first);
        loadAndForce(world, second);
        CellSet.Builder cells = CellSet.builder();
        for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 2; dz++) {
                cells.add(x0 + 6 + dx, y0 + 1, z0 + 6 + dz); // the middle of their chunks: no neighbour needed
                cells.add(x0 + far + 6 + dx, y0 + 1, z0 + 6 + dz);
            }
        }
        Region set = new Region.Cells(cells.build());
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> copied = new Captured<>();
        RecordingListener stack = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    ready(world, first);
                    ready(world, second);
                })
                .createAndAdd(once(() -> {
                    check(!FabricEntities.loaded(world, (x0 >> 4) + 10, z0 >> 4), "a chunk between them is loaded");
                    check(FabricEntities.firstUnloaded(world, set.bounds()) != null, "the bounds are all loaded");
                    stand(world, x0 + 6.5, y0 + 1, z0 + 6.5);
                    stand(world, x0 + far + 7.5, y0 + 1, z0 + 7.5);
                    copy(clips, h, set, false, CellMask.ANY, null, copied);
                    run(h, new OpSpec.Stack(set, 0, 2, 0, 1, EntityFilter.DECORATIONS), stack);
                }))
                .createAndAdd(() -> check(copied.finished() && stack.result != null, "copy and stack running"))
                .createAndAdd(() -> {
                    check(copied.get("the copy").entities() == 2, "copied " + copied.value.entities());
                    check(stack.result.outcome() == JobOutcome.COMPLETED, "stack " + stack.result);
                    check(entitiesIn(world, first).size() == 2 && entitiesIn(world, second).size() == 2,
                            "each stand and its copy: " + entitiesIn(world, first) + " " + entitiesIn(world, second));
                    check(!FabricEntities.loaded(world, (x0 >> 4) + 10, z0 >> 4), "the chunk between was loaded");
                    entitiesIn(world, first).forEach(Entity::discard);
                    entitiesIn(world, second).forEach(Entity::discard);
                    forceChunks(world, first, false);
                    forceChunks(world, second, false);
                    h.close();
                })
                .completeIfSuccessful();
    }
}
