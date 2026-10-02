package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.ClipboardGameTest.copy;
import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.client.editor.render.ghost.GhostVolume;
import dev.sculptory.fabric.client.editor.tools.place.GhostBaker;
import dev.sculptory.fabric.client.session.ClipboardCache;
import dev.sculptory.fabric.client.session.FabricEditorSession;
import dev.sculptory.fabric.client.session.PreviewDecoder;
import dev.sculptory.fabric.engine.impl.EngineRuntime;
import dev.sculptory.fabric.gametest.ClipTestSupport.Captured;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.net.ServerNet;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.fabric.world.FabricStateSpace;
import dev.sculptory.protocol.v2.Features;
import dev.sculptory.protocol.v2.Handshake;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.PermissionMask;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.server.config.SculptoryConfig;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.impl.ServerClipboards;
import dev.sculptory.server.net.ServerDispatcher;
import dev.sculptory.server.platform.WriteOptions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.test.TimedTaskRunner;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.Direction;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;
import vectorwing.farmersdelight.common.block.entity.SkilletBlockEntity;

/**
 * The modded facing fallback on the blocks the earlier fidelity run found not turning:
 * Farmer's Delight's cooking pot, skillet, pie and feasts and Chipped's special lanterns implement neither
 * {@code rotate} nor {@code mirror}. Pasted with every transform and moved turned, they face as vanilla blocks would
 * (a furnace beside them turns the same way), keep their contents and undo exactly; the ghost the client bakes from
 * the preview stream, with the space it builds and the features the handshake negotiates, is the server's result
 * cell for cell; and with {@code transform.moddedFacingFallback} off, server and client both leave them unturned.
 */
public final class FidelityTurnGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /** Relative "x,z" (one layer) and state: the blocks that do not turn themselves, a furnace and a stove that do. */
    static final String[][] TURN_BUILD = {
            {"0,0", "farmersdelight:cooking_pot[facing=south,support=none,waterlogged=false]"},
            {"2,0", "farmersdelight:skillet[facing=west,support=false,waterlogged=false]"},
            {"4,0", "farmersdelight:apple_pie[bites=1,facing=north]"},
            {"5,0", "farmersdelight:stove[facing=east,lit=false]"},
            {"0,2", "farmersdelight:roast_chicken_block[facing=east,servings=2]"},
            {"2,2", "farmersdelight:honey_glazed_ham_block[facing=south,servings=3]"},
            {"4,2", "farmersdelight:rice_roll_medley_block[facing=west,servings=5]"},
            {"0,4", "chipped:big_lantern[facing=east,waterlogged=false]"},
            {"1,4", "chipped:tall_soul_lantern[facing=north,waterlogged=true]"},
            {"2,4", "chipped:wide_lantern[facing=south,waterlogged=false]"},
            {"3,4", "chipped:donut_lantern[facing=west,waterlogged=false]"},
            {"5,4", "minecraft:furnace[facing=east,lit=false]"},
    };
    static final int SIZE_X = 6;
    static final int SIZE_Z = 5;
    /** The big lantern and the furnace both face east in the build: turned, they must face the same way. */
    private static final int[] LANTERN = {0, 4};
    private static final int[] FURNACE = {5, 4};
    private static final int[] POT = {0, 0};
    private static final int[] SKILLET = {2, 0};

    /**
     * The build copied and pasted with all twelve transforms: every cell is {@link FidelitySupport#expectedTurn} of its
     * source (each non-rotating block's facing is vanilla's mirror then turn of its own, and the lantern faces as the
     * furnace does), block entities keep their contents, the client's baked ghost equals each result, and undoing the
     * twelve pastes restores the world exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fidelity_turn_paste", tickLimit = LIMIT)
    public void nonRotatingModdedBlocksTurnWithEveryPasteTransform(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 240);
        int x0 = at[0] + 8, y0 = 100, z0 = at[1] + 8;
        Box source = buildBox(x0, y0, z0);
        List<Transform> transforms = FidelityEditGameTest.everyTransform();
        // A turned copy reaches up to 5 blocks west or north of its origin and 5 east; checks look one block around it.
        int spacing = 14;
        Box all = box(x0 - 8, y0, z0 - 8, x0 + spacing * (transforms.size() + 1) + 8, y0, z0 + 8);
        loadAndForce(world, all);
        build(h, world, x0, y0, z0);
        FabricStateSpace client = following(FabricStateSpace.build(), h.runtime.states());
        check(client.moddedFacingFallback(), "the client does not follow the server's fallback");
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        WorldSnapshot[] before = new WorldSnapshot[2];
        List<Captured<ClipboardService.ClipboardInfo>> copied = new ArrayList<>();
        Captured<ClipboardService.Outbound> preview = new Captured<>();
        List<RecordingListener> pastes = new ArrayList<>();
        List<BlockPos> origins = new ArrayList<>();
        TimedTaskRunner runner = context.createTimedTaskRunner()
                .createAndAdd(FidelitySupport.settled(context, world, all))
                .createAndAdd(() -> {
                    before[0] = capture(world, source);
                    before[1] = capture(world, all);
                    checkPremise(before[0]);
                    copied.add(copy(clips, h.player, source, source.min()));
                })
                .createAndAdd(() -> {
                    ClipboardService.ClipboardInfo info = copied.get(0).get("copy");
                    try {
                        clips.preview(h.player, new SourceRef.Clipboard(info.clipboardId()), preview);
                    } catch (EditRejected e) {
                        throw new GameTestException("preview refused: " + e.getMessage());
                    }
                    // Two rounds: at most 8 of a player's jobs may wait to start (executor.maxQueuedJobsPerPlayer).
                    FidelityEditGameTest.pasteRange(h, info, transforms, 0, transforms.size() / 2, x0, y0, z0, spacing,
                            pastes, origins);
                })
                .createAndAdd(() -> {
                    for (RecordingListener listener : pastes) check(listener.result != null, "first round running");
                    FidelityEditGameTest.pasteRange(h, copied.get(0).get("copy"), transforms, transforms.size() / 2,
                            transforms.size(), x0, y0, z0, spacing, pastes, origins);
                })
                .createAndAdd(() -> {
                    for (RecordingListener listener : pastes) check(listener.result != null, "pastes running");
                })
                .createAndAdd(() -> {
                    ClipboardCache.Preview decoded = decode(preview.get("preview").payload(), client);
                    for (int k = 0; k < transforms.size(); k++) {
                        Transform t = transforms.get(k);
                        check(pastes.get(k).result.outcome() == JobOutcome.COMPLETED
                                && pastes.get(k).result.strippedNbt() == 0, "paste " + k + " " + pastes.get(k).result);
                        FidelitySupport.checkTransformed(world, before[0], origins.get(k), t, "paste");
                        Box target = FidelitySupport.targetBox(source, origins.get(k), t);
                        checkFacings(world, before[0], target, t);
                        checkContents(world, target, t);
                        checkGhost(world, decoded, target, t, client, "paste " + t);
                    }
                });
        FidelityEditGameTest.undoRedo(runner, h, transforms.size(), true);
        runner.createAndAdd(() -> {
            checkSame(before[1], capture(world, all), "after undoing every paste");
            check(FidelitySupport.itemEntities(world, all) == 0, "a paste or undo dropped items");
            forceChunks(world, all, false);
            h.close();
        });
        runner.completeIfSuccessful();
    }

    /**
     * A move with a turn and a mirror ({@code Move} goes through the same state mapping as pastes and scatter): the
     * moved blocks face as {@link FidelitySupport#expectedTurn} says with their contents, the source is left empty,
     * and one undo restores both exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fidelity_turn_move", tickLimit = LIMIT)
    public void aTurnedMoveTurnsNonRotatingModdedBlocksAndUndoesExactly(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 241);
        int x0 = at[0] + 8, y0 = 100, z0 = at[1] + 8;
        Box source = buildBox(x0, y0, z0);
        Box all = box(x0 - 4, y0, z0 - 4, x0 + 24, y0, z0 + 12);
        loadAndForce(world, all);
        build(h, world, x0, y0, z0);
        Transform t = new Transform(1, Mirror.Z);
        BlockPos offset = new BlockPos(12, 0, 0);
        // Where the source's minimum corner lands: the moved box's minimum corner is source.min + offset.
        BlockPos anchorAt = new BlockPos(x0 + offset.x() + t.mapX(0, 0, SIZE_X, SIZE_Z), y0,
                z0 + offset.z() + t.mapZ(0, 0, SIZE_X, SIZE_Z));
        WorldSnapshot[] before = new WorldSnapshot[2];
        RecordingListener move = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(FidelitySupport.settled(context, world, all))
                .createAndAdd(() -> {
                    before[0] = capture(world, source);
                    before[1] = capture(world, all);
                    checkPremise(before[0]);
                    try {
                        h.service.run(h.player, new OpSpec.Move(source, offset, t,
                                new Pattern.Single(h.state("minecraft:air"))), RunOptions.DEFAULT, move);
                    } catch (EditRejected e) {
                        throw new GameTestException("move refused: " + e.getMessage());
                    }
                })
                .createAndAdd(() -> check(move.result != null, "move running"))
                .createAndAdd(() -> {
                    check(move.result.outcome() == JobOutcome.COMPLETED && move.result.strippedNbt() == 0,
                            "move " + move.result);
                    FidelitySupport.checkTransformed(world, before[0], anchorAt, t, "move");
                    Box target = FidelitySupport.targetBox(source, anchorAt, t);
                    checkFacings(world, before[0], target, t);
                    checkContents(world, target, t);
                    WorldSnapshot vacated = capture(world, source);
                    for (int state : vacated.states) {
                        check(Block.getStateFromRawId(state).isAir(), "the move left " + Block.getStateFromRawId(state));
                    }
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0,
                            "undo " + undo.result);
                    checkSame(before[1], capture(world, all), "after undoing the move");
                    check(FidelitySupport.itemEntities(world, all) == 0, "the move or its undo dropped items");
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * {@code transform.moddedFacingFallback}: a server built with it off (as the engine builds its space) turns the
     * non-rotating blocks only as vanilla does, offers no fallback in the handshake, and the client following it bakes
     * ghosts that match; with it on, both turn them. Either way the client's ghost is the server's result.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fidelity_turn_config", tickLimit = LIMIT)
    public void theClientFollowsTheServersFallbackSetting(TestContext context) {
        FabricStateSpace on = EngineTestSupport.runtime(context).states();
        SculptoryConfig offConfig = SculptoryConfig.defaults();
        offConfig.transform.moddedFacingFallback = false;
        FabricStateSpace off = EngineRuntime.buildStates(offConfig);
        check(on.moddedFacingFallback() && !off.moddedFacingFallback(), "config not applied");
        check(ServerNet.offered(on).has(Features.MODDED_FACING_FALLBACK)
                && !ServerNet.offered(off).has(Features.MODDED_FACING_FALLBACK), "the handshake offers the wrong setting");
        FabricStateSpace built = FabricStateSpace.build();
        FabricStateSpace clientOn = following(built, on);
        FabricStateSpace clientOff = following(built, off);
        check(clientOn.moddedFacingFallback() && !clientOff.moddedFacingFallback(), "the client does not follow");
        // An older client's Hello (the features before this one) is answered without it: its previews turn as vanilla.
        S2C older = Handshake.answer(Handshake.hello("older", ServerDispatcher.SERVER_FEATURES), ServerNet.offered(on),
                Limits.DEFAULTS, PermissionMask.NONE, 1L);
        check(!((S2C.Welcome) older).features().has(Features.MODDED_FACING_FALLBACK), "an older client was offered it");
        int fellBack = 0;
        for (String[] cell : TURN_BUILD) {
            int h = EngineTestSupport.handle(on, cell[1]);
            BlockState state = on.state(h);
            for (Transform t : FidelityEditGameTest.everyTransform()) {
                int vanilla = Block.getRawIdFromState(state.mirror(ClipboardGameTest.vanilla(t.mirror()))
                        .rotate(ClipboardGameTest.vanilla(t.quarterTurnsCw())));
                int expected = Block.getRawIdFromState(FidelitySupport.expectedTurn(state, t));
                int withFallback = t.applyToState(on, h);
                int without = t.applyToState(off, h);
                check(withFallback == expected && without == vanilla, cell[1] + " " + t + ": on "
                        + on.format(withFallback) + ", off " + on.format(without));
                check(bakeOne(h, t, clientOn) == withFallback && bakeOne(h, t, clientOff) == without,
                        "a ghost differs from the server for " + cell[1] + " " + t);
                if (expected != vanilla) fellBack++;
            }
            if (FidelitySupport.NON_ROTATING.contains(Registries.BLOCK.getId(state.getBlock()).toString())) {
                check(on.rotate(h, 1) != h && off.rotate(h, 1) == h, cell[1] + ": the setting is not applied");
            }
        }
        check(fellBack > 0, "nothing in the build needed the fallback");
        context.complete();
    }

    // ------------------------------------------------------------------ helpers

    static Box buildBox(int x, int y, int z) {
        return box(x, y, z, x + SIZE_X - 1, y, z + SIZE_Z - 1);
    }

    /** Writes {@link #TURN_BUILD} with its minimum corner at (x, y, z) and fills the pot and the skillet. */
    static void build(Harness h, ServerWorld world, int x, int y, int z) {
        BlockWriter writer = h.runtime.writer(world, new WriteOptions(false, true));
        for (String[] cell : TURN_BUILD) {
            String[] xz = cell[0].split(",");
            writer.write(x + Integer.parseInt(xz[0]), y, z + Integer.parseInt(xz[1]), h.state(cell[1]), null);
        }
        CookingPotBlockEntity pot = FidelitySupport.entity(world, x + POT[0], y, z + POT[1], CookingPotBlockEntity.class);
        pot.getInventory().setStackInSlot(0, new ItemStack(Items.CARROT, 2));
        pot.getInventory().setStackInSlot(7, new ItemStack(Items.BOWL, 4));
        SkilletBlockEntity skillet = FidelitySupport.entity(world, x + SKILLET[0], y, z + SKILLET[1],
                SkilletBlockEntity.class);
        skillet.getInventory().setStackInSlot(0, new ItemStack(Items.CHICKEN, 2));
    }

    /**
     * The build's pot, skillet, pie, feasts and lanterns do not turn themselves (vanilla's rotate and mirror give them
     * back unchanged), so these tests show the fallback at work; the furnace and the stove do.
     */
    private static void checkPremise(WorldSnapshot source) {
        int nonRotating = 0;
        for (int state : source.states) {
            BlockState s = Block.getStateFromRawId(state);
            String id = Registries.BLOCK.getId(s.getBlock()).toString();
            if (FidelitySupport.NON_ROTATING.contains(id)) {
                check(s.rotate(BlockRotation.CLOCKWISE_90) == s && s.mirror(BlockMirror.FRONT_BACK) == s
                        && s.mirror(BlockMirror.LEFT_RIGHT) == s, id + " turns itself now: update the fidelity premise");
                nonRotating++;
            } else if (!s.isAir()) {
                check(s.rotate(BlockRotation.CLOCKWISE_90) != s, id + " was meant to turn itself");
            }
        }
        check(nonRotating == 10, "the build has " + nonRotating + " non-rotating blocks");
    }

    /** Each non-rotating block faces vanilla's mirror-then-turn of its own facing; the lantern faces as the furnace. */
    private static void checkFacings(ServerWorld world, WorldSnapshot source, Box target, Transform t) {
        BlockMirror mirror = ClipboardGameTest.vanilla(t.mirror());
        BlockRotation rotation = ClipboardGameTest.vanilla(t.quarterTurnsCw());
        for (String[] cell : TURN_BUILD) {
            String[] xz = cell[0].split(",");
            int x = Integer.parseInt(xz[0]), z = Integer.parseInt(xz[1]);
            BlockState from = Block.getStateFromRawId(source.get(source.box.min().x() + x, source.box.min().y(),
                    source.box.min().z() + z));
            BlockState got = world.getBlockState(targetPos(target, t, x, z));
            Direction facing = from.get(Properties.HORIZONTAL_FACING);
            Direction want = rotation.rotate(mirror.apply(facing));
            check(got.getBlock() == from.getBlock() && got.get(Properties.HORIZONTAL_FACING) == want,
                    cell[1] + " " + t + " faces " + got + ", expected " + want);
            if ((t.quarterTurnsCw() & 1) == 1) check(want != facing, "a quarter turn kept " + cell[1] + " facing");
        }
        Direction lantern = world.getBlockState(targetPos(target, t, LANTERN[0], LANTERN[1]))
                .get(Properties.HORIZONTAL_FACING);
        Direction furnace = world.getBlockState(targetPos(target, t, FURNACE[0], FURNACE[1]))
                .get(Properties.HORIZONTAL_FACING);
        check(lantern == furnace, t + ": the lantern faces " + lantern + ", the furnace " + furnace);
    }

    /** The turned pot still holds its carrots and bowls, the skillet its chicken. */
    private static void checkContents(ServerWorld world, Box target, Transform t) {
        net.minecraft.util.math.BlockPos potAt = targetPos(target, t, POT[0], POT[1]);
        CookingPotBlockEntity pot = FidelitySupport.entity(world, potAt.getX(), potAt.getY(), potAt.getZ(),
                CookingPotBlockEntity.class);
        check(pot.getInventory().getStackInSlot(0).isOf(Items.CARROT) && pot.getInventory().getStackInSlot(0).getCount() == 2
                && pot.getInventory().getStackInSlot(7).isOf(Items.BOWL)
                && pot.getInventory().getStackInSlot(7).getCount() == 4, t + ": the pot lost its contents");
        net.minecraft.util.math.BlockPos skilletAt = targetPos(target, t, SKILLET[0], SKILLET[1]);
        SkilletBlockEntity skillet = FidelitySupport.entity(world, skilletAt.getX(), skilletAt.getY(), skilletAt.getZ(),
                SkilletBlockEntity.class);
        check(skillet.getInventory().getStackInSlot(0).isOf(Items.CHICKEN)
                && skillet.getInventory().getStackInSlot(0).getCount() == 2, t + ": the skillet lost its chicken");
    }

    /** Where the source cell (x, z) of the build lands in {@code target}. */
    private static net.minecraft.util.math.BlockPos targetPos(Box target, Transform t, int x, int z) {
        return pos(target.min().x() + t.mapX(x, z, SIZE_X, SIZE_Z), target.min().y(),
                target.min().z() + t.mapZ(x, z, SIZE_X, SIZE_Z));
    }

    /**
     * The client's ghost of the paste, as the Place tool makes it: the preview stream decoded with the client's space,
     * baked with the transform ({@link GhostBaker#bake}); every cell equals the world after the server's paste. With
     * the fallback off, the same ghost would differ at the non-rotating blocks for a quarter turn.
     */
    private static void checkGhost(ServerWorld world, ClipboardCache.Preview preview, Box target, Transform t,
                                   FabricStateSpace client, String what) {
        GhostVolume source = preview.volume();
        GhostVolume ghost = GhostBaker.bake(source.frame(), source.sections(), t, client);
        GhostVolume vanillaOnly = GhostBaker.bake(source.frame(), source.sections(), t,
                client.withModdedFacingFallback(false));
        int differing = 0;
        for (int y = target.min().y(); y <= target.max().y(); y++) {
            for (int z = target.min().z(); z <= target.max().z(); z++) {
                for (int x = target.min().x(); x <= target.max().x(); x++) {
                    int lx = x - target.min().x(), ly = y - target.min().y(), lz = z - target.min().z();
                    int cell = ghost.handle(lx, ly, lz);
                    BlockState actual = world.getBlockState(pos(x, y, z));
                    boolean same = cell < 0 ? actual.isAir() : cell == Block.getRawIdFromState(actual);
                    if (!same) {
                        throw new GameTestException(what + ": the ghost shows " + (cell < 0 ? "nothing"
                                : client.format(cell)) + " at " + x + "," + y + "," + z + ", the server placed " + actual);
                    }
                    if (vanillaOnly.handle(lx, ly, lz) != cell) differing++;
                }
            }
        }
        if ((t.quarterTurnsCw() & 1) == 1) {
            check(differing == FidelitySupport.NON_ROTATING.size(), what + ": without the fallback " + differing
                    + " ghost cells would differ, expected " + FidelitySupport.NON_ROTATING.size());
        }
    }

    /** A one-block ghost of {@code h} turned by {@code t}, baked as the Place tool bakes. */
    private static int bakeOne(int h, Transform t, FabricStateSpace client) {
        BlockBuffer cells = new BlockBuffer();
        cells.set(0, 0, 0, h);
        GhostVolume volume = GhostVolume.of(cells, GhostBaker.air(client));
        volume.setFrame(new Box(BlockPos.ORIGIN, BlockPos.ORIGIN));
        return GhostBaker.bake(volume.frame(), volume.sections(), t, client).handle(0, 0, 0);
    }

    /**
     * The editor's view of {@code built} (the space a client builds on join) after the handshake with a server whose
     * space is {@code server}: this client's Hello, the features that server offers, and the view they select.
     */
    private static FabricStateSpace following(FabricStateSpace built, FabricStateSpace server) {
        S2C answer = Handshake.answer(Handshake.hello("fidelity", FabricEditorSession.CLIENT_FEATURES),
                ServerNet.offered(server), Limits.DEFAULTS, PermissionMask.NONE, 1L);
        return built.followingServer(((S2C.Welcome) answer).features());
    }

    private static ClipboardCache.Preview decode(byte[] payload, FabricStateSpace client) {
        try {
            return PreviewDecoder.decode("fidelity", payload, client);
        } catch (IOException e) {
            throw new GameTestException("undecodable preview: " + e);
        }
    }
}
