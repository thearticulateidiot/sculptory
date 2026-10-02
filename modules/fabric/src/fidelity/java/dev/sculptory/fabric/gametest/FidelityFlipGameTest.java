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

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.PasteGeometry;
import dev.sculptory.core.state.VerticalFlip;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.engine.impl.ServerClipboards;
import dev.sculptory.fabric.gametest.ClipTestSupport.Captured;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.world.FabricStateSpace;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.server.engine.ClipboardService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import org.slf4j.LoggerFactory;

/**
 * The flip upside down with Chipped and Farmer's Delight: their blocks
 * follow the vanilla-named rules (a Chipped door's halves swap, its trapdoor and pointed dripstones turn over, a
 * basket pointing up points down), properties no vanilla block has are left alone and counted (the rope's
 * {@code tied_to_bell}, a Chipped workbench's {@code model}), and the modded build pasted upside down keeps its
 * contents and undoes exactly.
 */
public final class FidelityFlipGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;
    private static final Set<String> MODS = Set.of("chipped", "farmersdelight");
    private static final Set<String> FLIP_PROPERTIES = Set.of("facing", "vertical_direction", "half", "type", "face",
            "attachment", "hanging", "up", "down", "orientation");

    /**
     * Every Chipped and Farmer's Delight state: flipping twice gives it back, the flip commutes with the block's turns
     * and mirrors (the space's, fallback included), only properties with an up or down meaning change, and the blocks
     * named in the build flip as the rules say. The counts per mod are logged.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fidelity_flip_states")
    public void moddedStatesFlipByTheirVanillaNamedProperties(TestContext context) {
        FabricStateSpace space = EngineTestSupport.runtime(context).states();
        Map<String, int[]> counts = new TreeMap<>();
        for (int h = 0; h < space.size(); h++) {
            String namespace = space.blockId(h).value().substring(0, space.blockId(h).value().indexOf(':'));
            if (!MODS.contains(namespace)) continue;
            int flipped = space.flip(h);
            String text = space.format(h);
            check(space.flip(flipped) == h, text + " flipped twice");
            for (int turns = 1; turns < 4; turns++) {
                check(space.rotate(flipped, turns) == space.flip(space.rotate(h, turns)), text + " turned " + turns);
            }
            for (Mirror mirror : List.of(Mirror.X, Mirror.Z)) {
                check(space.mirror(flipped, mirror) == space.flip(space.mirror(h, mirror)), text + " " + mirror);
            }
            BlockDescriptor before = space.describe(h);
            BlockDescriptor after = space.describe(flipped);
            for (Map.Entry<String, String> entry : before.properties().entrySet()) {
                if (!FLIP_PROPERTIES.contains(entry.getKey())) {
                    check(entry.getValue().equals(after.get(entry.getKey())), text + " keeps " + entry.getKey());
                }
            }
            int[] c = counts.computeIfAbsent(namespace, n -> new int[4]);
            c[0]++;
            c[1 + space.flipKind(h)]++;
        }
        counts.forEach((mod, c) -> LoggerFactory.getLogger("sculptory").info(
                "Upside-down flip, {}: {} states, {} flip or look the same, {} kept, {} with unknown properties", mod,
                c[0], c[1], c[2], c[3]));
        flips(space, "chipped:barred_birch_door[facing=east,half=lower,hinge=left,open=false,powered=false]",
                "chipped:barred_birch_door[facing=east,half=upper,hinge=left,open=false,powered=false]");
        flips(space, "chipped:airy_birch_trapdoor[facing=south,half=top,open=true,powered=false,waterlogged=true]",
                "chipped:airy_birch_trapdoor[facing=south,half=bottom,open=true,powered=false,waterlogged=true]");
        flips(space, "chipped:andesite_pointed_dripstone[thickness=tip,vertical_direction=up,waterlogged=false]",
                "chipped:andesite_pointed_dripstone[thickness=tip,vertical_direction=down,waterlogged=false]");
        flips(space, "farmersdelight:basket[enabled=true,facing=up,waterlogged=false]",
                "farmersdelight:basket[enabled=true,facing=down,waterlogged=false]");
        // The pot sits low in its cell: kept whole (its support, which no vanilla block has, never matters then).
        kind(space, "farmersdelight:cooking_pot[facing=south,support=none,waterlogged=false]", VerticalFlip.KEPT);
        kind(space, "chipped:botanist_workbench[facing=east,model=main]", VerticalFlip.UNKNOWN_PROPERTIES);
        kind(space, "farmersdelight:rope[east=false,north=true,south=true,tied_to_bell=false,waterlogged=false,west=true]",
                VerticalFlip.UNKNOWN_PROPERTIES);
        kind(space, "chipped:acacia_wall_torch[facing=south]", VerticalFlip.KEPT);
        kind(space, "chipped:barky_black_carpet", VerticalFlip.KEPT);
        kind(space, "farmersdelight:canvas_sign[rotation=6,waterlogged=false]", VerticalFlip.KEPT);
        kind(space, "farmersdelight:cabbages[age=5]", VerticalFlip.KEPT);
        check(space.flip(h(space, "farmersdelight:wild_rice[half=lower,waterlogged=true]"))
                == h(space, "farmersdelight:wild_rice[half=upper,waterlogged=true]"), "wild rice stays whole");
        context.complete();
    }

    private static int h(FabricStateSpace space, String spec) {
        int handle = space.parse(spec);
        check(handle >= 0, "unknown " + spec);
        return handle;
    }

    private static void flips(FabricStateSpace space, String from, String to) {
        check(space.flip(h(space, from)) == h(space, to), from + " flips to " + space.format(space.flip(h(space, from))));
        check(space.flipKind(h(space, from)) == VerticalFlip.FLIPS, from + " kind " + space.flipKind(h(space, from)));
    }

    private static void kind(FabricStateSpace space, String spec, int expected) {
        check(space.flipKind(h(space, spec)) == expected, spec + " kind " + space.flipKind(h(space, spec)));
    }

    /**
     * The modded build (a Farmer's Delight kitchen and garden in a Chipped room) pasted upside down, and turned and
     * mirrored upside down: every cell is its source's state flipped where the flip puts it, every block entity keeps its
     * contents, and undoing both pastes leaves the world exactly as before.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fidelity_flip_paste", tickLimit = LIMIT)
    public void theModdedBuildPastedUpsideDownKeepsContentsAndUndoesExactly(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 1005);
        int x0 = at[0] + 8, y0 = 100, z0 = at[1] + 8;
        Box source = FidelitySupport.buildBox(x0, y0, z0);
        List<Transform> transforms = List.of(Transform.UPSIDE_DOWN, new Transform(1, Mirror.X, true));
        int spacing = 16;
        Box all = box(x0 - 8, y0 - 1, z0 - 8, x0 + spacing * (transforms.size() + 1) + 8, y0 + 4, z0 + 16);
        loadAndForce(world, all);
        FidelitySupport.build(h, world, x0, y0, z0);
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        WorldSnapshot[] before = new WorldSnapshot[2];
        List<Captured<ClipboardService.ClipboardInfo>> copied = new ArrayList<>();
        List<RecordingListener> pastes = new ArrayList<>();
        context.createTimedTaskRunner()
                .createAndAdd(FidelitySupport.settled(context, world, all))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    before[0] = capture(world, source);
                    before[1] = capture(world, all);
                    copied.add(copy(clips, h.player, source, source.min()));
                }))
                .createAndAdd(() -> copied.get(0).get("copy"))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    ClipboardService.ClipboardInfo info = copied.get(0).get("copy");
                    for (int k = 0; k < transforms.size(); k++) {
                        RecordingListener listener = new RecordingListener();
                        // The anchor (the build's minimum corner) turns over to the top: the origin is 3 up.
                        ClipboardGameTest.paste(h, h.player, info.clipboardId(),
                                new BlockPos(x0 + spacing * (k + 1), y0 + 3, z0), transforms.get(k), listener);
                        pastes.add(listener);
                    }
                }))
                .createAndAdd(() -> {
                    for (RecordingListener listener : pastes) check(listener.result != null, "pastes running");
                })
                .createAndAdd(EntitiesGameTest.once(() -> {
                    for (int k = 0; k < transforms.size(); k++) {
                        Transform t = transforms.get(k);
                        check(pastes.get(k).result.outcome() == JobOutcome.COMPLETED
                                && pastes.get(k).result.strippedNbt() == 0, "paste " + t + " " + pastes.get(k).result);
                        BlockPos targetMin = PasteGeometry.pasteTarget(new BlockPos(8, 4, 8), BlockPos.ORIGIN, t,
                                new BlockPos(x0 + spacing * (k + 1), y0 + 3, z0)).min();
                        check(targetMin.y() == y0, t + ": the box starts at " + targetMin);
                        FlipGameTest.checkTransformed(world, h.runtime.states(), before[0], targetMin, t, null, "paste");
                        checkContents(world, before[0], targetMin, t);
                    }
                }))
                .createAndAdd(FlipGameTest.undoAll(h, transforms.size()))
                .createAndAdd(() -> {
                    checkSame(FlipGameTest.settled(before[1]), FlipGameTest.settled(capture(world, all)),
                            "after undoing the flipped pastes");
                    check(FidelitySupport.itemEntities(world, all) == 0, "a paste or undo dropped items");
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** Every block entity of the source is at its flipped place with the same contents. */
    private static void checkContents(ServerWorld world, WorldSnapshot source, BlockPos targetMin, Transform t) {
        Box box = source.box;
        int sx = box.sizeX(), sy = box.sizeY(), sz = box.sizeZ();
        BlockPos size = t.size(sx, sy, sz);
        WorldSnapshot pasted = capture(world, new Box(targetMin, targetMin.offset(size.x() - 1, size.y() - 1,
                size.z() - 1)));
        for (Map.Entry<Long, NbtCompound> entry : source.tiles.entrySet()) {
            net.minecraft.util.math.BlockPos from = net.minecraft.util.math.BlockPos.fromLong(entry.getKey());
            int x = from.getX() - box.min().x(), y = from.getY() - box.min().y(), z = from.getZ() - box.min().z();
            net.minecraft.util.math.BlockPos to = pos(targetMin.x() + t.mapX(x, z, sx, sz),
                    targetMin.y() + t.mapY(y, sy), targetMin.z() + t.mapZ(x, z, sx, sz));
            NbtCompound got = pasted.tiles.get(to.asLong());
            NbtCompound want = entry.getValue().copy();
            want.remove("TransferCooldown");
            if (got != null) {
                got = got.copy();
                got.remove("TransferCooldown");
            }
            if (!Objects.equals(want, got)) {
                throw new GameTestException(t + ": the block entity from " + from.toShortString() + " at "
                        + to.toShortString() + " is " + got + ", expected " + want + " ("
                        + Block.getStateFromRawId(source.get(from.getX(), from.getY(), from.getZ())) + ")");
            }
        }
        check(pasted.tiles.size() == source.tiles.size(), t + ": " + pasted.tiles.size() + " block entities pasted");
    }
}
