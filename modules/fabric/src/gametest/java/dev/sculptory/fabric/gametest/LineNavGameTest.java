package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import com.mojang.authlib.GameProfile;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.ShapeSpec;
import dev.sculptory.core.brush.ShapeSweep;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.LineKernel;
import dev.sculptory.core.generate.SparseUpload;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.core.path.PathSpec;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.engine.ClipboardService;
import dev.sculptory.fabric.engine.EditRejected;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.fabric.engine.RunOptions;
import dev.sculptory.fabric.engine.impl.NavigateService;
import dev.sculptory.fabric.engine.impl.ServerClipboards;
import dev.sculptory.fabric.gametest.ClipTestSupport.Captured;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.NavigateMode;
import dev.sculptory.protocol.v2.OpLabel;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.server.OperatorEntry;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.world.GameMode;

/**
 * Jump and Through, Generate's Line and the Shape brush's Line: a
 * line and a carved shape line go up as generated clipboards, paste exactly their cells as one labelled history step
 * and undo exactly; Jump and Through land on the spot {@code Landing} finds and move the player there; every refusal
 * (no node, Survival, too far, no spot) leaves the player where they were. Region slots 1160-1179.
 */
public final class LineNavGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /** Stone from y {@code from} to {@code to} over the area's columns. */
    private static void ground(Harness h, Box area, int from, int to) {
        BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
        int stone = h.state("minecraft:stone");
        for (int x = area.min().x(); x <= area.max().x(); x++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int y = from; y <= to; y++) writer.write(x, y, z, stone, null);
            }
        }
    }

    /** Every cell of {@code area} holds its source cell, else what {@code before} held. */
    private static void checkPasted(ServerWorld world, Box area, GeneratedSource source, WorldSnapshot before, String what) {
        WorldSnapshot now = capture(world, area);
        for (int y = area.min().y(); y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    int generated = source.get(x, y, z);
                    int want = generated >= 0 ? generated : before.get(x, y, z);
                    int got = now.get(x, y, z);
                    if (want != got) {
                        throw new GameTestException(what + " at " + x + "," + y + "," + z + ": "
                                + Block.getStateFromRawId(got) + " instead of " + Block.getStateFromRawId(want));
                    }
                }
            }
        }
    }

    private static OpSpec.Paste paste(UUID clipboardId, Box bounds, PasteOptions options) {
        return new OpSpec.Paste(new SourceRef.Clipboard(clipboardId), bounds.min(), Transform.IDENTITY, options);
    }

    private static PathSpec path(PathKind kind, double sag, int... xyz) {
        List<BlockPos> points = new java.util.ArrayList<>();
        for (int i = 0; i < xyz.length; i += 3) points.add(new BlockPos(xyz[i], xyz[i + 1], xyz[i + 2]));
        return new PathSpec(points, kind, sag);
    }

    /**
     * Uploads {@code source} as the player's generated clipboard and pastes it with {@code label}; checks the paste
     * wrote exactly the source's cells as one history step named {@code labelPrefix}, then that undo restores every
     * cell.
     */
    private static void pasteAndUndo(TestContext context, Harness h, Box area, GeneratedSource source, PasteOptions options,
                                     OpLabel label, String labelPrefix) {
        WorldSnapshot before = capture(h.world, area);
        byte[] payload = SparseUpload.encode(source, h.runtime.states());
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> uploaded = new Captured<>();
        try {
            clips.beginGeneratedUpload(h.player, source.bounds(), source.cells(), payload.length).completed(payload, uploaded);
        } catch (EditRejected e) {
            throw new GameTestException("upload refused: " + e.getMessage());
        }
        RecordingListener pasted = new RecordingListener(), undo = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(uploaded.finished(), "upload running"))
                .createAndAdd(() -> {
                    ClipboardService.ClipboardInfo info = uploaded.get("the upload");
                    check(info.cells() == source.cells(), "uploaded " + info);
                    try {
                        h.service.run(h.player, paste(info.clipboardId(), source.bounds(), options),
                                new RunOptions(false, ConflictPolicy.SKIP_CONFLICTS, label), pasted);
                    } catch (EditRejected e) {
                        throw new GameTestException("paste refused: " + e.getMessage());
                    }
                })
                .createAndAdd(() -> check(pasted.result != null, "paste running"))
                .createAndAdd(() -> {
                    check(pasted.result.outcome() == JobOutcome.COMPLETED, "paste " + pasted.result);
                    checkPasted(h.world, area, source, before, "the " + labelPrefix);
                    String undoLabel = h.history().undoLabel();
                    check(undoLabel.startsWith(labelPrefix + " · "), "history " + h.history());
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0,
                            "undo " + undo.result);
                    checkSame(before, capture(h.world, area), "after the undo");
                    forceChunks(h.world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Generate's Line: a curved round line of stone bricks, thickness 3, through the stone ground and the air above it,
     * pasted as "Line", writes exactly its cells and undoes exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_line_build", tickLimit = LIMIT)
    public void aLineIsBuiltAndUndoneExactly(TestContext context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 1160);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 96, z0, x0 + 31, 112, z0 + 31);
        loadAndForce(h.world, area);
        ground(h, area, 98, 101);
        PathSpec curve = path(PathKind.CURVE, 0, x0 + 3, 100, z0 + 4, x0 + 14, 106, z0 + 20, x0 + 28, 102, z0 + 10);
        GeneratedSource line = LineKernel.generate(new LineKernel.Spec(curve, 3, LineKernel.Profile.ROUND,
                new Pattern.Single(h.state("minecraft:stone_bricks"))), h.runtime.states(), 100_000);
        check(line.cells() > 100 && area.contains(line.bounds().min().x(), line.bounds().min().y(), line.bounds().min().z())
                && area.contains(line.bounds().max().x(), line.bounds().max().y(), line.bounds().max().z()), "line " + line);
        pasteAndUndo(context, h, area, line, new PasteOptions(true, false, false), OpLabel.LINE, "Line");
    }

    /**
     * The Shape brush's Line in Carve: a sphere of radius 2 swept along a hanging line inside solid stone writes air in
     * exactly the tunnel's cells (the paste includes air), labelled "Shape line", and undo refills it exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_shape_line_carve", tickLimit = LIMIT)
    public void aShapeLineCarvesATunnelAndUndoesExactly(TestContext context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 1162);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 96, z0, x0 + 31, 112, z0 + 31);
        loadAndForce(h.world, area);
        ground(h, area, 96, 112);
        PathSpec hanging = path(PathKind.HANGING, 4, x0 + 3, 108, z0 + 8, x0 + 28, 108, z0 + 20);
        ShapeSpec carve = new ShapeSpec(ShapeSpec.Kind.SPHERE, 5, Facing.UP, ShapeSpec.Mode.CARVE, 0);
        GeneratedSource tunnel = ShapeSweep.generate(hanging, 2, carve, null, h.runtime.states(), 100_000);
        int air = h.state("minecraft:air");
        tunnel.forEach((x, y, z, state) -> check(state == air, "a carved cell is not air"));
        check(tunnel.cells() > 200, "tunnel " + tunnel);
        pasteAndUndo(context, h, area, tunnel, new PasteOptions(true, false, false), OpLabel.SHAPE_LINE, "Shape line");
    }

    /** A floor at y 100 over the area, a pillar to y 104 at (8, 8), a wall at x 20 from y 101 to 108. */
    private static void course(Harness h, Box area, int x0, int z0) {
        ground(h, area, 99, 100);
        BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
        int stone = h.state("minecraft:stone");
        for (int y = 101; y <= 104; y++) writer.write(x0 + 8, y, z0 + 8, stone, null);
        for (int z = z0; z <= z0 + 31; z++) {
            for (int y = 101; y <= 108; y++) writer.write(x0 + 20, y, z, stone, null);
        }
    }

    private static void stand(ServerPlayerEntity p, double x, double y, double z) {
        p.refreshPositionAndAngles(x, y, z, 0f, 0f);
    }

    private static void checkAt(ServerPlayerEntity p, double x, double y, double z, String what) {
        check(Math.abs(p.getX() - x) < 1e-6 && Math.abs(p.getY() - y) < 1e-6 && Math.abs(p.getZ() - z) < 1e-6,
                what + ": the player is at " + p.getPos());
    }

    /**
     * Jump stands the player on top of the pillar looked at; Through walks the look direction through the wall and
     * stands them on the floor behind it; a Spectator may jump too. The result names the feet, and the facing stays.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_navigate_land", tickLimit = LIMIT)
    public void jumpAndThroughLandWhereLandingSays(TestContext context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 1164);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 96, z0, x0 + 31, 112, z0 + 31);
        loadAndForce(h.world, area);
        course(h, area, x0, z0);
        ServerPlayerEntity p = h.player;
        stand(p, x0 + 2.5, 101, z0 + 2.5);
        p.setYaw(37f);
        p.setPitch(12f);
        S2C.NavigateResult jump = h.service.navigate(p, new C2S.Navigate(1, NavigateMode.JUMP,
                new BlockPos(x0 + 8, 102, z0 + 8), Facing.WEST, 1f, 0f, 1f));
        check(jump.reqId() == 1 && jump.reason() == null && new BlockPos(x0 + 8, 105, z0 + 8).equals(jump.feet()),
                "jump " + jump);
        checkAt(p, x0 + 8.5, 105, z0 + 8.5, "after the jump");
        check(p.getYaw() == 37f && p.getPitch() == 12f, "the facing changed: " + p.getYaw() + ", " + p.getPitch());
        // Through: looking level (east) at the wall's block at eye height.
        stand(p, x0 + 15.5, 101, z0 + 12.5);
        S2C.NavigateResult through = h.service.navigate(p, new C2S.Navigate(2, NavigateMode.THROUGH,
                new BlockPos(x0 + 20, 102, z0 + 12), Facing.WEST, 1f, 0f, 0f));
        check(new BlockPos(x0 + 21, 101, z0 + 12).equals(through.feet()), "through " + through);
        checkAt(p, x0 + 21.5, 101, z0 + 12.5, "after going through");
        // Down through the floor (y 99-100) into the air under it: hanging under the floor, and set flying.
        p.getAbilities().allowFlying = true;
        p.getAbilities().flying = false;
        S2C.NavigateResult down = h.service.navigate(p, new C2S.Navigate(4, NavigateMode.THROUGH,
                new BlockPos(x0 + 4, 100, z0 + 4), Facing.UP, 0f, -1f, 0f));
        check(new BlockPos(x0 + 4, 97, z0 + 4).equals(down.feet()), "down through the floor " + down);
        checkAt(p, x0 + 4.5, 97, z0 + 4.5, "under the floor");
        check(p.getAbilities().flying, "a Creative player left over nothing does not fly");
        // A Spectator jumps too.
        ModePlayer spectator = ModePlayer.join(context, GameMode.SPECTATOR);
        stand(spectator, x0 + 21.5, 101, z0 + 12.5);
        S2C.NavigateResult watched = h.service.navigate(spectator, new C2S.Navigate(3, NavigateMode.JUMP,
                new BlockPos(x0 + 20, 105, z0 + 20), Facing.EAST, -1f, 0f, 0f));
        check(new BlockPos(x0 + 20, 109, z0 + 20).equals(watched.feet()), "spectator " + watched);
        spectator.leave();
        forceChunks(h.world, area, false);
        h.close();
        context.complete();
    }

    /**
     * Refusals move nobody: without the {@code navigate} node and in Survival ({@code NO_PERMISSION}, each notice saying
     * which is missing), a block beyond {@code navigate.maxDistance} ({@code TOO_LARGE}), a pillar topped with lava and a
     * wall deeper than {@code maxThroughDepth} ({@code INVALID}, no spot).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_navigate_refusals", tickLimit = LIMIT)
    public void refusalsLeaveThePlayerWhereTheyWere(TestContext context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 1166);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 96, z0, x0 + 31, 112, z0 + 31);
        loadAndForce(h.world, area);
        course(h, area, x0, z0);
        BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
        writer.write(x0 + 8, 105, z0 + 8, h.state("minecraft:lava"), null);
        C2S.Navigate ontoPillar = new C2S.Navigate(1, NavigateMode.JUMP, new BlockPos(x0 + 4, 100, z0 + 4), Facing.UP,
                0f, -1f, 0f);

        ServerPlayerEntity stranger = h.addPlayer(false);
        stand(stranger, x0 + 2.5, 101, z0 + 2.5);
        refused(h, stranger, ontoPillar, RejectReason.NO_PERMISSION, NavigateService.NEEDS_NODE, List.of("sculptory.use"));
        EditTestSupport.grant(stranger, Perm.USE);
        refused(h, stranger, ontoPillar, RejectReason.NO_PERMISSION, NavigateService.NEEDS_NODE,
                List.of(Perm.NAVIGATE.node()));
        EditTestSupport.grant(stranger, Perm.USE, Perm.NAVIGATE);
        S2C.NavigateResult granted = h.service.navigate(stranger, ontoPillar);
        check(granted.feet() != null, "granted the node, the jump is still refused: " + granted);

        // An operator, but in Survival or Adventure.
        for (GameMode mode : List.of(GameMode.SURVIVAL, GameMode.ADVENTURE)) {
            ModePlayer walker = ModePlayer.join(context, mode);
            stand(walker, x0 + 2.5, 101, z0 + 2.5);
            refused(h, walker, ontoPillar, RejectReason.NO_PERMISSION, NavigateService.NEEDS_MODE, List.of());
            walker.leave();
        }
        ServerPlayerEntity p = h.player;
        stand(p, x0 + 2.5, 101, z0 + 2.5);

        int max = h.runtime.config().navigate.maxDistance;
        C2S.Navigate far = new C2S.Navigate(2, NavigateMode.JUMP, new BlockPos(x0 + 2, 100, z0 + 2 + max + 10), Facing.UP,
                0f, 0f, 1f);
        S2C.NavigateResult tooFar = refused(h, p, far, RejectReason.TOO_LARGE, NavigateService.TOO_FAR, null);
        check(tooFar.reqId() == 2, "too far " + tooFar);
        C2S.Navigate lava = new C2S.Navigate(3, NavigateMode.JUMP, new BlockPos(x0 + 8, 103, z0 + 8), Facing.WEST, 1f, 0f, 0f);
        refused(h, p, lava, RejectReason.INVALID, NavigateService.NO_SPOT, List.of());
        int depth = h.runtime.config().navigate.maxThroughDepth;
        h.runtime.config().navigate.maxThroughDepth = 1;
        try {
            // The floor is two blocks thick (y 99-100): looking down through it, one block deep finds only stone.
            C2S.Navigate down = new C2S.Navigate(4, NavigateMode.THROUGH, new BlockPos(x0 + 4, 100, z0 + 4), Facing.UP,
                    0f, -1f, 0f);
            refused(h, p, down, RejectReason.INVALID, NavigateService.NO_SPOT_THROUGH, List.of("1"));
        } finally {
            h.runtime.config().navigate.maxThroughDepth = depth;
        }
        forceChunks(h.world, area, false);
        h.close();
        context.complete();
    }

    /** The request is refused with {@code reason} and a notice {@code key} (with {@code args}, unless null); nobody moved. */
    private static S2C.NavigateResult refused(Harness h, ServerPlayerEntity p, C2S.Navigate request, RejectReason reason,
                                              String key, List<String> args) {
        double x = p.getX(), y = p.getY(), z = p.getZ();
        NavigateService.Decision decision = NavigateService.decide(h.runtime, p, request);
        check(decision.reason() == reason && decision.notice() != null && decision.notice().key().equals(key)
                && (args == null || decision.notice().args().equals(args)), "decided " + decision);
        S2C.NavigateResult result = h.service.navigate(p, request);
        check(result.reqId() == request.reqId() && result.reason() == reason && result.feet() == null, "result " + result);
        checkAt(p, x, y, z, "after a refusal");
        return result;
    }
    /**
     * An operator (level 2) in a given game mode: vanilla's mock player is always in Creative, so the mode is this
     * player's own (as BuilderModeGameTest's). {@link #leave} removes it.
     */
    private static final class ModePlayer extends ServerPlayerEntity {
        private final GameMode mode;

        private ModePlayer(ServerWorld world, GameProfile profile, ConnectedClientData data, GameMode mode) {
            super(world.getServer(), world, profile, data.syncedOptions());
            this.mode = mode;
        }

        static ModePlayer join(TestContext context, GameMode mode) {
            ServerWorld world = context.getWorld();
            GameProfile profile = new GameProfile(UUID.randomUUID(), "navigator-" + mode.asString());
            ConnectedClientData data = ConnectedClientData.createDefault(profile, false);
            ModePlayer player = new ModePlayer(world, data.gameProfile(), data, mode);
            ClientConnection connection = new ClientConnection(NetworkSide.SERVERBOUND);
            new EmbeddedChannel(connection);
            world.getServer().getPlayerManager().onPlayerConnect(connection, player, data);
            world.getServer().getPlayerManager().getOpList().add(new OperatorEntry(profile, 2, false));
            return player;
        }

        void leave() {
            getServer().getPlayerManager().getOpList().remove(getGameProfile());
            getServer().getPlayerManager().remove(this);
        }

        @Override
        public boolean isCreative() {
            return mode == GameMode.CREATIVE;
        }

        @Override
        public boolean isSpectator() {
            return mode == GameMode.SPECTATOR;
        }
    }
}
