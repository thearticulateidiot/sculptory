package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.handle;
import static dev.sculptory.fabric.gametest.EngineTestSupport.runtime;

import dev.sculptory.core.Box;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.fabric.engine.impl.EngineRuntime;
import dev.sculptory.fabric.gametest.EngineTestSupport.BoxFill;
import dev.sculptory.fabric.net.ServerNet;
import dev.sculptory.fabric.perm.FabricPermissionService;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.ChunkPermit;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.impl.JobRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.OperatorEntry;
import net.minecraft.server.OperatorList;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import org.slf4j.LoggerFactory;

/** Permission nodes and refusals for a real (mock-connected) non-op player. */
public final class PermissionGameTest implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_perm")
    @SuppressWarnings("removal") // createMockCreativeServerPlayerInWorld is vanilla's test-only mock player
    public void permissionRefusalNonOp(TestContext context) {
        EngineRuntime runtime = runtime(context);
        ServerWorld world = context.getWorld();
        MinecraftServer server = world.getServer();
        FabricPermissionService permissions = runtime.permissions();
        ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
        try {
            // Fabric's GameTest server reports itself as dedicated; either way the mock player is not a host.
            check(!server.isHost(player.getGameProfile()), "mock player is a host");
            for (Perm perm : Perm.values()) check(!permissions.has(player, perm), "non-op holds " + perm.node());
            check(permissions.mask(player).bits() == 0, "non-op mask is not empty");
            check(!permissions.mayWriteOperatorNbt(player), "non-op may write operator NBT");

            // Loading unloaded chunks needs sculptory.edit.unloaded: the executor refuses before any work.
            int[] at = EngineTestSupport.regionCorner(context, 5);
            Box far = box(at[0], 0, at[1], at[0] + 15, 15, at[1] + 15);
            BoxFill program = new BoxFill("far", far, handle(runtime.states(), "minecraft:stone"));
            JobRequest<ServerWorld> request = forPlayer(runtime, player, program, RunOptions.DEFAULT);
            check(!request.mayLoadChunks(), "non-op may load chunks");
            try {
                runtime.executor().submit(request);
                throw new GameTestException("job over unloaded chunks was admitted for a non-op");
            } catch (EditRejected e) {
                check(e.reason() == RejectReason.UNLOADED, "reason " + e.reason());
            }
            check(program.computeCount == 0 && !runtime.executor().isLocked(world, far), "refused job left state");

            // Physics needs sculptory.physics.
            try {
                JobRequest.forPlayer(runtime, player, program, new RunOptions(true, ConflictPolicy.SKIP_CONFLICTS),
                        null, null);
                throw new GameTestException("physics was allowed for a non-op");
            } catch (EditRejected e) {
                check(e.reason() == RejectReason.NO_PERMISSION, "reason " + e.reason());
            }

            // No spawn protection or claims in the test world: the permit allows everything.
            check(permissions.chunk(player, world, at[0] >> 4, at[1] >> 4, far) == ChunkPermit.ALLOW, "permit");

            // Op level 2 is the fallback when no permissions mod decides a node.
            OperatorList ops = server.getPlayerManager().getOpList();
            ops.add(new OperatorEntry(player.getGameProfile(), 2, false));
            try {
                check(permissions.has(player, Perm.USE) && permissions.has(player, Perm.EDIT_UNLOADED), "level-2 op");
                check(permissions.mayWriteOperatorNbt(player), "creative level-2 op may write operator NBT");
                check(forPlayer(runtime, player, program, RunOptions.DEFAULT).mayLoadChunks(), "op may load chunks");
            } finally {
                ops.remove(player.getGameProfile());
            }
            check(!permissions.has(player, Perm.USE), "node still held after deop");
        } finally {
            try {
                server.getPlayerManager().remove(player);
            } catch (RuntimeException e) {
                LoggerFactory.getLogger("sculptory").warn("Could not remove the mock player", e);
            }
        }
        context.complete();
    }

    /**
     * {@code /op} and {@code /deop} reach the editor at once: vanilla resends the command tree, and
     * {@code PlayerManagerMixin} turns that into a permission re-check (which pushes {@code PermissionsChanged} to a
     * client with the editor; this mock player has no Sculptory session, so nothing is sent).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_perm_push")
    @SuppressWarnings("removal") // createMockCreativeServerPlayerInWorld is vanilla's test-only mock player
    public void opAndDeopRecheckEditorPermissions(TestContext context) {
        EngineRuntime runtime = runtime(context);
        MinecraftServer server = context.getWorld().getServer();
        ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
        UUID id = player.getUuid();
        // Whether the player held the editor node when each re-check ran.
        List<Boolean> rechecks = new ArrayList<>();
        ServerNet.observePermissionChecks(checked -> {
            if (checked.getUuid().equals(id)) rechecks.add(runtime.permissions().has(checked, Perm.USE));
        });
        OperatorList ops = server.getPlayerManager().getOpList();
        try {
            check(!runtime.permissions().has(player, Perm.USE), "mock player starts without the editor node");

            // The vanilla op and deop paths re-check. (The GameTest server ops at level 0, so no node changes here.)
            server.getPlayerManager().addToOperators(player.getGameProfile());
            check(rechecks.size() == 1, "op re-checked the editor permissions " + rechecks.size() + " times");
            server.getPlayerManager().removeFromOperators(player.getGameProfile());
            check(rechecks.size() == 2, "deop re-checked the editor permissions " + (rechecks.size() - 1) + " times");

            // What op does on a real server (a level-2+ entry, then the command tree): the re-check sees the node.
            ops.add(new OperatorEntry(player.getGameProfile(), 2, false));
            server.getPlayerManager().sendCommandTree(player);
            check(rechecks.size() == 3 && rechecks.get(2), "the re-check after a level-2 op sees sculptory.use");
            ops.remove(player.getGameProfile());
            server.getPlayerManager().sendCommandTree(player);
            check(rechecks.size() == 4 && !rechecks.get(3), "the re-check after deop sees the node gone");
            check(!ServerNet.isReady(player), "a player without the editor has no session to push to");
        } finally {
            ServerNet.observePermissionChecks(null);
            if (server.getPlayerManager().isOperator(player.getGameProfile())) {
                ops.remove(player.getGameProfile());
            }
            try {
                server.getPlayerManager().remove(player);
            } catch (RuntimeException e) {
                LoggerFactory.getLogger("sculptory").warn("Could not remove the mock player", e);
            }
        }
        context.complete();
    }

    private static JobRequest<ServerWorld> forPlayer(EngineRuntime runtime, ServerPlayerEntity player, BoxFill program,
                                        RunOptions options) {
        try {
            return JobRequest.forPlayer(runtime, player, program, options, null, null);
        } catch (EditRejected e) {
            throw new GameTestException("forPlayer rejected: " + e.getMessage());
        }
    }
}
