package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.ClipboardGameTest.copy;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.SculptoryMod;
import dev.sculptory.fabric.client.session.FabricEditorSession;
import dev.sculptory.fabric.config.SculptoryConfig;
import dev.sculptory.fabric.engine.ClipboardService;
import dev.sculptory.fabric.engine.EditRejected;
import dev.sculptory.fabric.engine.impl.EngineRuntime;
import dev.sculptory.fabric.engine.impl.ServerClipboards;
import dev.sculptory.fabric.gametest.ClipTestSupport.Captured;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.net.PreviewPayload;
import dev.sculptory.fabric.net.ServerNet;
import dev.sculptory.fabric.world.FabricStateSpace;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.Codec;
import dev.sculptory.protocol.v2.Handshake;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.PermissionMask;
import dev.sculptory.protocol.v2.ProtocolException;
import dev.sculptory.protocol.v2.S2C;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;

/**
 * Chipped and Farmer's Delight states in the block-state space and on the wire: every
 * modded state has a text form that parses back to itself, rotates and mirrors as vanilla's
 * {@code BlockState.rotate}/{@code mirror} or, for blocks that do not turn themselves, the modded facing fallback, is
 * counted in the space the client builds on join, and crosses the protocol codec and the preview stream as itself. A
 * state the other side does not know is refused, never replaced.
 */
public final class FidelityStateGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /**
     * Every state's text form, and how every state turns: vanilla blocks and modded blocks that turn themselves exactly
     * as vanilla's {@code rotate}/{@code mirror}, modded blocks that do not (Farmer's Delight pots, skillets, pies and
     * feasts, Chipped's special lanterns) by the modded facing fallback ({@link FidelitySupport#expectedTurn}, worked
     * out with vanilla's helpers). The client's space, following the server's handshake, turns every state the same way;
     * with the fallback off (config {@code transform.moddedFacingFallback}) every state turns as vanilla's alone.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fidelity_states", tickLimit = LIMIT)
    public void everyModdedStateRoundTripsAndTransformsLikeVanilla(TestContext context) {
        FabricStateSpace server = EngineTestSupport.runtime(context).states();
        check(server.moddedFacingFallback(), "the fidelity server runs with the default config (fallback on)");
        // What the client builds on every join (SculptoryClientMod), after registry sync, following the handshake.
        S2C answer = Handshake.answer(Handshake.hello("test", FabricEditorSession.CLIENT_FEATURES),
                ServerNet.offered(server), Limits.DEFAULTS, PermissionMask.NONE, 1L);
        FabricStateSpace client = FabricStateSpace.build().followingServer(((S2C.Welcome) answer).features());
        // A server with transform.moddedFacingFallback off, built as the engine builds it.
        SculptoryConfig offConfig = SculptoryConfig.defaults();
        offConfig.transform.moddedFacingFallback = false;
        FabricStateSpace off = EngineRuntime.buildStates(offConfig);
        check(server.size() == Block.STATE_IDS.size() && client.size() == server.size() && off.size() == server.size(),
                "sizes: server " + server.size() + ", client " + client.size() + ", registry " + Block.STATE_IDS.size());
        check(client.moddedFacingFallback() && !off.moddedFacingFallback(), "the client does not follow the server");
        List<Transform> transforms = FidelityEditGameTest.everyTransform();
        Map<String, Integer> turnedByFallback = new TreeMap<>();
        for (int h = 0; h < server.size(); h++) {
            BlockState state = server.state(h);
            boolean vanillaBlock = Registries.BLOCK.getId(state.getBlock()).getNamespace().equals("minecraft");
            boolean fellBack = false;
            for (Transform t : transforms) {
                int expected = Block.getRawIdFromState(FidelitySupport.expectedTurn(state, t));
                int vanilla = Block.getRawIdFromState(state.mirror(ClipboardGameTest.vanilla(t.mirror()))
                        .rotate(ClipboardGameTest.vanilla(t.quarterTurnsCw())));
                int got = t.applyToState(server, h);
                int onClient = t.applyToState(client, h);
                int withoutFallback = t.applyToState(off, h);
                if (got != expected || onClient != expected || withoutFallback != vanilla
                        || (vanillaBlock && expected != vanilla)) {
                    throw new GameTestException(server.format(h) + " " + t + ": server " + server.format(got)
                            + ", client " + server.format(onClient) + ", fallback off " + server.format(withoutFallback)
                            + "; expected " + server.format(expected) + ", vanilla " + server.format(vanilla));
                }
                fellBack |= expected != vanilla;
            }
            for (int turns = -4; turns < 8; turns++) {
                int expected = Block.getRawIdFromState(
                        FidelitySupport.expectedTurn(state, Transform.rotation(turns)));
                if (server.rotate(h, turns) != expected) {
                    throw new GameTestException(server.format(h) + " turned " + turns + " is "
                            + server.format(server.rotate(h, turns)) + ", expected " + server.format(expected));
                }
            }
            if (server.mirror(h, Mirror.NONE) != h) throw new GameTestException("mirror NONE of " + server.format(h));
            if (fellBack) turnedByFallback.merge(Registries.BLOCK.getId(state.getBlock()).toString(), 1, Integer::sum);
        }
        // The blocks the earlier fidelity run found not turning: each of their horizontal states turns now.
        for (String block : FidelitySupport.NON_ROTATING) {
            check(turnedByFallback.getOrDefault(block, 0) >= 4, block + " is not turned by the fallback: "
                    + turnedByFallback.getOrDefault(block, 0) + " states");
        }
        check(turnedByFallback.keySet().stream().noneMatch(id -> id.startsWith("minecraft:")), "vanilla blocks fell back");
        int turnedStates = turnedByFallback.values().stream().mapToInt(Integer::intValue).sum();
        check(turnedStates == server.moddedFacingFallbackTables().turnedStates(), "the fallback's tables turn "
                + server.moddedFacingFallbackTables().turnedStates() + " states, the reference " + turnedStates);
        SculptoryMod.LOG.info("Sculptory fidelity: the modded facing fallback turns {} states of {} blocks: {}",
                turnedStates, turnedByFallback.size(), turnedByFallback.keySet());

        Map<String, Integer> perMod = new TreeMap<>();
        for (int h = 0; h < server.size(); h++) {
            BlockState state = server.state(h);
            String namespace = Registries.BLOCK.getId(state.getBlock()).getNamespace();
            if (!namespace.equals("chipped") && !namespace.equals("farmersdelight")) continue;
            perMod.merge(namespace, 1, Integer::sum);
            String text = server.format(h);
            BlockDescriptor descriptor = server.describe(h);
            check(text.equals(descriptor.format()) && descriptor.properties().size() == state.getProperties().size(),
                    "descriptor of " + state + ": " + text);
            check(server.parse(text) == h && server.resolve(BlockDescriptor.parse(text)) == h,
                    "does not parse back: " + text);
            check(client.parse(text) == h && client.format(h).equals(text) && client.flags(h) == server.flags(h),
                    "the client space differs for " + text);
            check(server.blockId(h).value().equals(Registries.BLOCK.getId(state.getBlock()).toString()), "block id " + text);
        }
        check(perMod.getOrDefault("chipped", 0) > 40_000 && perMod.getOrDefault("farmersdelight", 0) > 2_000,
                "modded states " + perMod);
        context.complete();
    }

    /**
     * Ops, strokes and copies naming modded states and blocks survive the codec (encoded with the server's space,
     * decoded with a space built like the client's); the preview of a modded clipboard names each state as the
     * client parses it. A state the decoding side does not know (another version of the mod) is refused.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fidelity_wire", tickLimit = LIMIT)
    public void moddedStatesCrossTheWire(TestContext context) throws ProtocolException {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        FabricStateSpace server = h.runtime.states();
        FabricStateSpace client = FabricStateSpace.build();
        int pot = h.state("farmersdelight:cooking_pot[facing=south,support=none,waterlogged=true]");
        int lantern = h.state("chipped:big_lantern[facing=east,waterlogged=true]");
        int farmland = h.state("farmersdelight:rich_soil_farmland[moisture=7]");
        int trapdoor = h.state(
                "chipped:airy_birch_trapdoor[facing=south,half=top,open=true,powered=false,waterlogged=true]");
        Box box = box(0, 64, 0, 9, 70, 9);
        List<NamespacedId> blocks = List.of(new NamespacedId("farmersdelight:rich_soil"),
                new NamespacedId("chipped:boxed_oak_planks"));
        Pattern mix = new Pattern.Weighted(new int[] {pot, lantern, farmland}, new int[] {1, 2, 3}, 7L);
        List<C2S> messages = List.of(
                new C2S.RunOp(1, new OpSpec.Fill(box, mix, new CellMask.States(new int[] {trapdoor, farmland})), false,
                        ConflictPolicy.SKIP_CONFLICTS),
                new C2S.RunOp(2, new OpSpec.Replace(box, new CellMask.Blocks(blocks), new Pattern.Single(trapdoor)), false,
                        ConflictPolicy.SKIP_CONFLICTS),
                new C2S.StrokeBegin(3, new BrushSpec(BrushTool.PALETTE, 6, 1f, Falloff.SMOOTH, Shape.CIRCLE, mix,
                        new SurfaceMask.Not(new SurfaceMask.SurfaceBlocks(new CellMask.States(new int[] {farmland}))),
                        2, 0, 9L)),
                new C2S.StrokeBegin(4, new BrushSpec(BrushTool.PAINT, 3, 1f, Falloff.CONSTANT, Shape.SQUARE,
                        new Pattern.Single(lantern), new SurfaceMask.SurfaceBlocks(new CellMask.Blocks(blocks)), 1, 0, 1L)),
                new C2S.Copy(5, box, new BlockPos(0, 64, 0), false, new CellMask.States(new int[] {pot, lantern})));
        for (C2S message : messages) {
            C2S decoded = Codec.decodeC2S(Codec.encodeC2S(message, server), client);
            check(decoded.equals(message), "decoded differently: " + message + " -> " + decoded);
        }
        // Another side's Chipped names a variant this one does not have: refused, not replaced by some other state.
        StateSpace newerChipped = new RenamingStateSpace(server, lantern, "chipped:big_lantern_of_the_future[facing=east]");
        byte[] frame = Codec.encodeC2S(messages.get(0), newerChipped);
        try {
            Codec.decodeC2S(frame, client);
            throw new GameTestException("an unknown modded state was accepted");
        } catch (ProtocolException e) {
            check(e.reason() == ProtocolException.Reason.UNKNOWN_STATE, "refused with " + e.reason());
        }

        int[] at = regionCorner(context, 210);
        int x0 = at[0] + 4, y0 = 100, z0 = at[1] + 4;
        Box source = FidelitySupport.buildBox(x0, y0, z0);
        loadAndForce(world, source);
        FidelitySupport.build(h, world, x0, y0, z0);
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> copied = copy(clips, h.player, source, source.min());
        Captured<ClipboardService.Outbound> preview = new Captured<>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    ClipboardService.ClipboardInfo info = copied.get("copy");
                    try {
                        clips.preview(h.player, new SourceRef.Clipboard(info.clipboardId()), preview);
                    } catch (EditRejected e) {
                        throw new GameTestException("preview refused: " + e.getMessage());
                    }
                })
                .createAndAdd(() -> {
                    ClipboardService.Outbound out = preview.get("preview");
                    Clipboard held = h.service.clipboards().get(h.player.getUuid()).orElseThrow().clipboard();
                    PreviewPayload.Decoded decoded;
                    try {
                        decoded = PreviewPayload.decode(out.payload(), 64L << 20);
                    } catch (IOException e) {
                        throw new GameTestException("undecodable preview: " + e);
                    }
                    List<String> modded = new ArrayList<>();
                    for (int y = 0; y < FidelitySupport.HEIGHT; y++) {
                        for (int z = 0; z < FidelitySupport.SIZE; z++) {
                            for (int x = 0; x < FidelitySupport.SIZE; x++) {
                                String text = decoded.get(x, y, z);
                                int expected = held.get(x, y, z);
                                check(text != null && client.parse(text) == expected, "preview cell " + x + "," + y + ","
                                        + z + " is " + text + ", clipboard " + server.format(expected));
                                if (!text.startsWith("minecraft:")) modded.add(text);
                            }
                        }
                    }
                    check(modded.size() > 60, "only " + modded.size() + " modded cells in the preview");
                    forceChunks(world, source, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** A state space that formats one handle as another text (a peer with a different mod version). */
    private record RenamingStateSpace(StateSpace delegate, int renamed, String text) implements StateSpace {
        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public int air() {
            return delegate.air();
        }

        @Override
        public int flags(int h) {
            return delegate.flags(h);
        }

        @Override
        public String format(int h) {
            return h == renamed ? text : delegate.format(h);
        }

        @Override
        public int parse(String spec) {
            return delegate.parse(spec);
        }

        @Override
        public BlockDescriptor describe(int h) {
            return delegate.describe(h);
        }

        @Override
        public int resolve(BlockDescriptor d) {
            return delegate.resolve(d);
        }

        @Override
        public NamespacedId blockId(int h) {
            return delegate.blockId(h);
        }

        @Override
        public boolean inTag(int h, NamespacedId tag) {
            return delegate.inTag(h, tag);
        }

        @Override
        public int rotate(int h, int clockwiseQuarterTurns) {
            return delegate.rotate(h, clockwiseQuarterTurns);
        }

        @Override
        public int mirror(int h, Mirror m) {
            return delegate.mirror(h, m);
        }

        @Override
        public int withWaterlogged(int h, boolean on) {
            return delegate.withWaterlogged(h, on);
        }

        @Override
        public int fluidSource(int h) {
            return delegate.fluidSource(h);
        }

        @Override
        public boolean isSignBlockEntity(String typeId) {
            return delegate.isSignBlockEntity(typeId);
        }
    }
}
