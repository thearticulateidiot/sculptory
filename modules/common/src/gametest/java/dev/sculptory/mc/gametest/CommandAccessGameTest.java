package dev.sculptory.mc.gametest;

import static dev.sculptory.mc.gametest.GameTestSuite.EMPTY_STRUCTURE;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.mc.engine.impl.EditServiceHost;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.impl.EngineEditService;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import org.slf4j.LoggerFactory;

/** Who may address the {@code /sculptory} commands: the console, admins, and (for {@code /sculptory cancel}) editor users. */
public final class CommandAccessGameTest {
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_command_access")
    public void consoleMayAddressCommandsButCommandBlocksMayNot(GameTestHelper context) {
        MinecraftServer server = context.getLevel().getServer();
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        CommandSourceStack console = server.createCommandSourceStack();

        // The console (level 4) sees /sculptory, e.g. for "execute as <player> run sculptory ...".
        ParseResults<CommandSourceStack> parsed = dispatcher.parse("sculptory history", console);
        context.assertTrue(parsed.getExceptions().isEmpty() && !parsed.getReader().canRead(),
                "the console should be able to parse /sculptory history");

        // Without a player to act as, running it fails instead of acting on nobody's history.
        boolean refused = false;
        try {
            dispatcher.execute(parsed);
        } catch (CommandSyntaxException e) {
            refused = true;
        }
        context.assertTrue(refused, "/sculptory history run by the console itself must require a player");

        // Command blocks and other level-2 sources don't see the commands at all.
        ParseResults<CommandSourceStack> hidden = dispatcher.parse("sculptory history", console.withPermission(2));
        context.assertTrue(hidden.getReader().canRead() || !hidden.getExceptions().isEmpty(),
                "a level-2 source must not be able to address /sculptory");
        context.succeed();
    }

    /**
     * "execute as <player> run sculptory history|jobs|cancel" from the console: Brigadier checks the {@code /sculptory}
     * requirement against the console, so history and jobs require the player's sculptory.use (cancel never does).
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_command_access_player")
    @SuppressWarnings("removal") // createMockCreativeServerPlayerInWorld is vanilla's test-only mock player
    public void consoleActingForAPlayerNeedsThatPlayersEditorPermission(GameTestHelper context) {
        MinecraftServer server = context.getLevel().getServer();
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        CommandSourceStack console = server.createCommandSourceStack();
        ServerPlayer guest = context.makeMockServerPlayerInLevel();
        try {
            for (String sub : List.of("history", "jobs")) {
                context.assertTrue(refused(dispatcher, console, guest, sub),
                        "/sculptory " + sub + " for a player without sculptory.use must be refused");
            }
            context.assertFalse(refused(dispatcher, console, guest, "cancel"),
                    "a player can always cancel their own jobs, even without sculptory.use");
            // Op level 2 is the node's fallback. (addToOperators would use the GameTest server's op level, 0.)
            server.getPlayerList().getOps().add(new ServerOpListEntry(guest.getGameProfile(), 2, false));
            for (String sub : List.of("history", "jobs", "cancel")) {
                context.assertFalse(refused(dispatcher, console, guest, sub),
                        "/sculptory " + sub + " for an operator must run");
            }
        } finally {
            server.getPlayerList().getOps().remove(guest.getGameProfile());
            try {
                server.getPlayerList().remove(guest);
            } catch (RuntimeException e) {
                LoggerFactory.getLogger("sculptory").warn("Could not remove the mock player", e);
            }
        }
        context.succeed();
    }

    /**
     * {@code /sculptory cancel} is for every editor user: a player with sculptory.use but not admin can address it and
     * cancel their own job with it, but no other {@code /sculptory} subcommand and not {@code /sculptory cancel <player>}; a player
     * without use sees no {@code /sculptory} at all.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_command_access_use")
    public void editorUsersMayCancelTheirOwnJobsByCommand(GameTestHelper context) {
        MinecraftServer server = context.getLevel().getServer();
        var host = EditServiceHost.find(server)
                .orElseThrow(() -> new GameTestAssertException("the mod did not install the edit service"));
        EditTestSupport.Harness h = new EditTestSupport.Harness(context);
        ServerPlayer user = h.addPlayer(false);
        ServerPlayer outsider = h.addPlayer(false);
        try {
            CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
            EditTestSupport.grant(user, Perm.USE, Perm.REGION);
            EditTestSupport.CapturedOutput out = new EditTestSupport.CapturedOutput();
            CommandSourceStack source = user.createCommandSourceStack().withSource(out);

            context.assertTrue(parses(dispatcher, "sculptory cancel", source),
                    "a player with sculptory.use must be able to address /sculptory cancel");
            for (String adminOnly : List.of("sculptory history", "sculptory jobs", "sculptory undo", "sculptory redo",
                    "sculptory fill 0 64 0 1 65 1 minecraft:stone", "sculptory cancel " + h.player.getUUID())) {
                context.assertFalse(parses(dispatcher, adminOnly, source), "/" + adminOnly + " must stay admin-only");
                context.assertTrue(parses(dispatcher, adminOnly, h.player.createCommandSourceStack()),
                        "an admin must still be able to address /" + adminOnly);
            }
            context.assertFalse(parses(dispatcher, "sculptory cancel", outsider.createCommandSourceStack()),
                    "a player without sculptory.use must not see /sculptory");

            waitingFill(context, h, host, user);
            server.getCommands().performPrefixedCommand(source, "sculptory cancel");
            context.assertTrue(out.all().contains("Cancelled 1 job"), "cancel output: " + out.all());
            context.assertTrue(host.jobs(user.getUUID()).isEmpty(), "the cancelled job is still listed");
        } finally {
            host.playerLeft(user.getUUID());
            h.close();
        }
        context.succeed();
    }

    /**
     * "execute as <victim> run sculptory cancel": Brigadier checks requirements against the issuer, who needs only
     * sculptory.use to address /sculptory cancel, so the command itself refuses to cancel someone else's jobs for an issuer
     * without admin (here an op whose admin node a permissions mod denies, so they may run /execute). An admin may.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_command_access_execute")
    public void executeAsCannotCancelAnotherPlayersJobsWithoutAdmin(GameTestHelper context) {
        MinecraftServer server = context.getLevel().getServer();
        var host = EditServiceHost.find(server)
                .orElseThrow(() -> new GameTestAssertException("the mod did not install the edit service"));
        EditTestSupport.Harness h = new EditTestSupport.Harness(context);
        ServerPlayer victim = h.addPlayer(false);
        ServerPlayer issuer = h.addPlayer(true);
        try {
            CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
            EditTestSupport.grant(victim, Perm.USE, Perm.REGION);
            EditTestSupport.deny(issuer, Perm.ADMIN);
            String command = "execute as " + victim.getUUID() + " run sculptory cancel";
            context.assertTrue(parses(dispatcher, command, issuer.createCommandSourceStack()),
                    "the issuer (use through op level 2, admin denied) must be able to address it, or this tests nothing");

            waitingFill(context, h, host, victim);
            server.getCommands().performPrefixedCommand(issuer.createCommandSourceStack(), command);
            context.assertTrue(host.jobs(victim.getUUID()).size() == 1,
                    "a player without admin cancelled another player's job through execute as");
            server.getCommands().performPrefixedCommand(h.player.createCommandSourceStack(), command);
            context.assertTrue(host.jobs(victim.getUUID()).isEmpty(), "an admin could not cancel it through execute as");
        } finally {
            host.playerLeft(victim.getUUID());
            h.close();
        }
        context.succeed();
    }

    /** Admits a small fill for {@code player} on the server's own edit service; it waits until the next tick. */
    private static void waitingFill(GameTestHelper context, EditTestSupport.Harness h,
                                    EngineEditService<ServerPlayer, ServerLevel> host,
                                    ServerPlayer player) {
        BlockPos corner = context.absolutePos(new BlockPos(0, 1, 0));
        Box box = new Box(new dev.sculptory.core.BlockPos(corner.getX(), corner.getY(), corner.getZ()),
                new dev.sculptory.core.BlockPos(corner.getX() + 1, corner.getY() + 1, corner.getZ() + 1));
        try {
            host.run(player, new OpSpec.Fill(box, new Pattern.Single(h.state("minecraft:stone")), CellMask.ANY),
                    RunOptions.DEFAULT, new EngineTestSupport.RecordingListener());
        } catch (EditRejected e) {
            throw new GameTestAssertException("fill rejected: " + e.getMessage());
        }
        context.assertTrue(host.jobs(player.getUUID()).size() == 1, "the fill was not admitted");
    }

    private static boolean parses(CommandDispatcher<CommandSourceStack> dispatcher, String command,
                                  CommandSourceStack source) {
        ParseResults<CommandSourceStack> parsed = dispatcher.parse(command, source);
        return parsed.getExceptions().isEmpty() && !parsed.getReader().canRead();
    }

    /** Parses "sculptory {@code sub}" as the console, then runs it as the console acting for {@code player}. */
    private static boolean refused(CommandDispatcher<CommandSourceStack> dispatcher, CommandSourceStack console,
                                   ServerPlayer player, String sub) {
        ParseResults<CommandSourceStack> parsed = dispatcher.parse("sculptory " + sub, console);
        if (!parsed.getExceptions().isEmpty() || parsed.getReader().canRead()) {
            throw new GameTestAssertException("the console could not parse /sculptory " + sub);
        }
        parsed.getContext().withSource(console.withEntity(player));
        try {
            dispatcher.execute(parsed);
            return false;
        } catch (CommandSyntaxException e) {
            return true;
        }
    }
}
