package dev.sculptory.mc.gametest;

import static dev.sculptory.mc.gametest.EngineTestSupport.box;
import static dev.sculptory.mc.gametest.EngineTestSupport.check;
import static dev.sculptory.mc.gametest.EngineTestSupport.handle;
import static dev.sculptory.mc.gametest.EngineTestSupport.runtime;
import static dev.sculptory.mc.gametest.GameTestSuite.EMPTY_STRUCTURE;

import dev.sculptory.core.Box;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.mc.engine.impl.EngineRuntime;
import dev.sculptory.mc.gametest.EngineTestSupport.BoxFill;
import dev.sculptory.mc.net.ServerNet;
import dev.sculptory.mc.perm.McPermissions;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.ChunkPermit;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.impl.JobRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpList;
import net.minecraft.server.players.ServerOpListEntry;
import org.slf4j.LoggerFactory;

/** Permission nodes and refusals for a real (mock-connected) non-op player. */
public final class PermissionGameTest {
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_perm")
    @SuppressWarnings("removal") // createMockCreativeServerPlayerInWorld is vanilla's test-only mock player
    public void permissionRefusalNonOp(GameTestHelper context) {
        EngineRuntime runtime = runtime(context);
        ServerLevel world = context.getLevel();
        MinecraftServer server = world.getServer();
        McPermissions permissions = runtime.permissions();
        ServerPlayer player = context.makeMockServerPlayerInLevel();
        try {
            // The GameTest server reports itself as dedicated; either way the mock player is not a host.
            check(!server.isSingleplayerOwner(player.getGameProfile()), "mock player is a host");
            for (Perm perm : Perm.values()) check(!permissions.has(player, perm), "non-op holds " + perm.node());
            check(permissions.mask(player).bits() == 0, "non-op mask is not empty");
            check(!permissions.mayWriteOperatorNbt(player), "non-op may write operator NBT");

            // Loading unloaded chunks needs sculptory.edit.unloaded: the executor refuses before any work.
            int[] at = EngineTestSupport.regionCorner(context, 5);
            Box far = box(at[0], 0, at[1], at[0] + 15, 15, at[1] + 15);
            BoxFill program = new BoxFill("far", far, handle(runtime.states(), "minecraft:stone"));
            JobRequest<ServerLevel> request = forPlayer(runtime, player, program, RunOptions.DEFAULT);
            check(!request.mayLoadChunks(), "non-op may load chunks");
            try {
                runtime.executor().submit(request);
                throw new GameTestAssertException("job over unloaded chunks was admitted for a non-op");
            } catch (EditRejected e) {
                check(e.reason() == RejectReason.UNLOADED, "reason " + e.reason());
            }
            check(program.computeCount == 0 && !runtime.executor().isLocked(world, far), "refused job left state");

            // Physics needs sculptory.physics.
            try {
                JobRequest.forPlayer(runtime, player, program, new RunOptions(true, ConflictPolicy.SKIP_CONFLICTS),
                        null, null);
                throw new GameTestAssertException("physics was allowed for a non-op");
            } catch (EditRejected e) {
                check(e.reason() == RejectReason.NO_PERMISSION, "reason " + e.reason());
            }

            // No spawn protection or claims in the test world: the permit allows everything.
            check(permissions.chunk(player, world, at[0] >> 4, at[1] >> 4, far) == ChunkPermit.ALLOW, "permit");

            // Op level 2 is the fallback when no permissions mod decides a node.
            ServerOpList ops = server.getPlayerList().getOps();
            ops.add(new ServerOpListEntry(player.getGameProfile(), 2, false));
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
                server.getPlayerList().remove(player);
            } catch (RuntimeException e) {
                LoggerFactory.getLogger("sculptory").warn("Could not remove the mock player", e);
            }
        }
        context.succeed();
    }

    /**
     * {@code /op} and {@code /deop} reach the editor at once: vanilla resends the command tree, and
     * {@code PlayerManagerMixin} turns that into a permission re-check (which pushes {@code PermissionsChanged} to a
     * client with the editor; this mock player has no Sculptory session, so nothing is sent).
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_perm_push")
    @SuppressWarnings("removal") // createMockCreativeServerPlayerInWorld is vanilla's test-only mock player
    public void opAndDeopRecheckEditorPermissions(GameTestHelper context) {
        EngineRuntime runtime = runtime(context);
        MinecraftServer server = context.getLevel().getServer();
        ServerPlayer player = context.makeMockServerPlayerInLevel();
        UUID id = player.getUUID();
        // Whether the player held the editor node when each re-check ran.
        List<Boolean> rechecks = new ArrayList<>();
        ServerNet.observePermissionChecks(checked -> {
            if (checked.getUUID().equals(id)) rechecks.add(runtime.permissions().has(checked, Perm.USE));
        });
        ServerOpList ops = server.getPlayerList().getOps();
        try {
            check(!runtime.permissions().has(player, Perm.USE), "mock player starts without the editor node");

            // The vanilla op and deop paths re-check. (The GameTest server ops at level 0, so no node changes here.)
            server.getPlayerList().op(player.getGameProfile());
            check(rechecks.size() == 1, "op re-checked the editor permissions " + rechecks.size() + " times");
            server.getPlayerList().deop(player.getGameProfile());
            check(rechecks.size() == 2, "deop re-checked the editor permissions " + (rechecks.size() - 1) + " times");

            // What op does on a real server (a level-2+ entry, then the command tree): the re-check sees the node.
            ops.add(new ServerOpListEntry(player.getGameProfile(), 2, false));
            server.getPlayerList().sendPlayerPermissionLevel(player);
            check(rechecks.size() == 3 && rechecks.get(2), "the re-check after a level-2 op sees sculptory.use");
            ops.remove(player.getGameProfile());
            server.getPlayerList().sendPlayerPermissionLevel(player);
            check(rechecks.size() == 4 && !rechecks.get(3), "the re-check after deop sees the node gone");
            check(!ServerNet.isReady(player), "a player without the editor has no session to push to");
        } finally {
            ServerNet.observePermissionChecks(null);
            if (server.getPlayerList().isOp(player.getGameProfile())) {
                ops.remove(player.getGameProfile());
            }
            try {
                server.getPlayerList().remove(player);
            } catch (RuntimeException e) {
                LoggerFactory.getLogger("sculptory").warn("Could not remove the mock player", e);
            }
        }
        context.succeed();
    }

    private static JobRequest<ServerLevel> forPlayer(EngineRuntime runtime, ServerPlayer player, BoxFill program,
                                        RunOptions options) {
        try {
            return JobRequest.forPlayer(runtime, player, program, options, null, null);
        } catch (EditRejected e) {
            throw new GameTestAssertException("forPlayer rejected: " + e.getMessage());
        }
    }
}
