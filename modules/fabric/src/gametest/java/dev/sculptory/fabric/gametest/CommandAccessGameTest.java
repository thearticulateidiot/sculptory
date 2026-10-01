package dev.sculptory.fabric.gametest;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.fabric.engine.EditRejected;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.fabric.engine.RunOptions;
import dev.sculptory.fabric.engine.impl.EditServiceHost;
import dev.sculptory.fabric.engine.impl.EngineEditService;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.OperatorEntry;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import org.slf4j.LoggerFactory;

/** Who may address the {@code /sculptory} commands: the console, admins, and (for {@code /sculptory cancel}) editor users. */
public final class CommandAccessGameTest implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_command_access")
    public void consoleMayAddressCommandsButCommandBlocksMayNot(TestContext context) {
        MinecraftServer server = context.getWorld().getServer();
        CommandDispatcher<ServerCommandSource> dispatcher = server.getCommandManager().getDispatcher();
        ServerCommandSource console = server.getCommandSource();

        // The console (level 4) sees /sculptory, e.g. for "execute as <player> run sculptory ...".
        ParseResults<ServerCommandSource> parsed = dispatcher.parse("sculptory history", console);
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
        ParseResults<ServerCommandSource> hidden = dispatcher.parse("sculptory history", console.withLevel(2));
        context.assertTrue(hidden.getReader().canRead() || !hidden.getExceptions().isEmpty(),
                "a level-2 source must not be able to address /sculptory");
        context.complete();
    }

    /**
     * "execute as <player> run sculptory history|jobs|cancel" from the console: Brigadier checks the {@code /sculptory}
     * requirement against the console, so history and jobs require the player's sculptory.use (cancel never does).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_command_access_player")
    @SuppressWarnings("removal") // createMockCreativeServerPlayerInWorld is vanilla's test-only mock player
    public void consoleActingForAPlayerNeedsThatPlayersEditorPermission(TestContext context) {
        MinecraftServer server = context.getWorld().getServer();
        CommandDispatcher<ServerCommandSource> dispatcher = server.getCommandManager().getDispatcher();
        ServerCommandSource console = server.getCommandSource();
        ServerPlayerEntity guest = context.createMockCreativeServerPlayerInWorld();
        try {
            for (String sub : List.of("history", "jobs")) {
                context.assertTrue(refused(dispatcher, console, guest, sub),
                        "/sculptory " + sub + " for a player without sculptory.use must be refused");
            }
            context.assertFalse(refused(dispatcher, console, guest, "cancel"),
                    "a player can always cancel their own jobs, even without sculptory.use");
            // Op level 2 is the node's fallback. (addToOperators would use the GameTest server's op level, 0.)
            server.getPlayerManager().getOpList().add(new OperatorEntry(guest.getGameProfile(), 2, false));
            for (String sub : List.of("history", "jobs", "cancel")) {
                context.assertFalse(refused(dispatcher, console, guest, sub),
                        "/sculptory " + sub + " for an operator must run");
            }
        } finally {
            server.getPlayerManager().getOpList().remove(guest.getGameProfile());
            try {
                server.getPlayerManager().remove(guest);
            } catch (RuntimeException e) {
                LoggerFactory.getLogger("sculptory").warn("Could not remove the mock player", e);
            }
        }
        context.complete();
    }

    /**
     * {@code /sculptory cancel} is for every editor user: a player with sculptory.use but not admin can address it and
     * cancel their own job with it, but no other {@code /sculptory} subcommand and not {@code /sculptory cancel <player>}; a player
     * without use sees no {@code /sculptory} at all.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_command_access_use")
    public void editorUsersMayCancelTheirOwnJobsByCommand(TestContext context) {
        MinecraftServer server = context.getWorld().getServer();
        EngineEditService host = EditServiceHost.find(server)
                .orElseThrow(() -> new GameTestException("the mod did not install the edit service"));
        EditTestSupport.Harness h = new EditTestSupport.Harness(context);
        ServerPlayerEntity user = h.addPlayer(false);
        ServerPlayerEntity outsider = h.addPlayer(false);
        try {
            CommandDispatcher<ServerCommandSource> dispatcher = server.getCommandManager().getDispatcher();
            EditTestSupport.grant(user, Perm.USE, Perm.REGION);
            EditTestSupport.CapturedOutput out = new EditTestSupport.CapturedOutput();
            ServerCommandSource source = user.getCommandSource().withOutput(out);

            context.assertTrue(parses(dispatcher, "sculptory cancel", source),
                    "a player with sculptory.use must be able to address /sculptory cancel");
            for (String adminOnly : List.of("sculptory history", "sculptory jobs", "sculptory undo", "sculptory redo",
                    "sculptory fill 0 64 0 1 65 1 minecraft:stone", "sculptory cancel " + h.player.getUuid())) {
                context.assertFalse(parses(dispatcher, adminOnly, source), "/" + adminOnly + " must stay admin-only");
                context.assertTrue(parses(dispatcher, adminOnly, h.player.getCommandSource()),
                        "an admin must still be able to address /" + adminOnly);
            }
            context.assertFalse(parses(dispatcher, "sculptory cancel", outsider.getCommandSource()),
                    "a player without sculptory.use must not see /sculptory");

            waitingFill(context, h, host, user);
            server.getCommandManager().executeWithPrefix(source, "sculptory cancel");
            context.assertTrue(out.all().contains("Cancelled 1 job"), "cancel output: " + out.all());
            context.assertTrue(host.jobs(user.getUuid()).isEmpty(), "the cancelled job is still listed");
        } finally {
            host.playerLeft(user.getUuid());
            h.close();
        }
        context.complete();
    }

    /**
     * "execute as <victim> run sculptory cancel": Brigadier checks requirements against the issuer, who needs only
     * sculptory.use to address /sculptory cancel, so the command itself refuses to cancel someone else's jobs for an issuer
     * without admin (here an op whose admin node a permissions mod denies, so they may run /execute). An admin may.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_command_access_execute")
    public void executeAsCannotCancelAnotherPlayersJobsWithoutAdmin(TestContext context) {
        MinecraftServer server = context.getWorld().getServer();
        EngineEditService host = EditServiceHost.find(server)
                .orElseThrow(() -> new GameTestException("the mod did not install the edit service"));
        EditTestSupport.Harness h = new EditTestSupport.Harness(context);
        ServerPlayerEntity victim = h.addPlayer(false);
        ServerPlayerEntity issuer = h.addPlayer(true);
        try {
            CommandDispatcher<ServerCommandSource> dispatcher = server.getCommandManager().getDispatcher();
            EditTestSupport.grant(victim, Perm.USE, Perm.REGION);
            EditTestSupport.deny(issuer, Perm.ADMIN);
            String command = "execute as " + victim.getUuid() + " run sculptory cancel";
            context.assertTrue(parses(dispatcher, command, issuer.getCommandSource()),
                    "the issuer (use through op level 2, admin denied) must be able to address it, or this tests nothing");

            waitingFill(context, h, host, victim);
            server.getCommandManager().executeWithPrefix(issuer.getCommandSource(), command);
            context.assertTrue(host.jobs(victim.getUuid()).size() == 1,
                    "a player without admin cancelled another player's job through execute as");
            server.getCommandManager().executeWithPrefix(h.player.getCommandSource(), command);
            context.assertTrue(host.jobs(victim.getUuid()).isEmpty(), "an admin could not cancel it through execute as");
        } finally {
            host.playerLeft(victim.getUuid());
            h.close();
        }
        context.complete();
    }

    /** Admits a small fill for {@code player} on the server's own edit service; it waits until the next tick. */
    private static void waitingFill(TestContext context, EditTestSupport.Harness h, EngineEditService host,
                                    ServerPlayerEntity player) {
        BlockPos corner = context.getAbsolutePos(new BlockPos(0, 1, 0));
        Box box = new Box(new dev.sculptory.core.BlockPos(corner.getX(), corner.getY(), corner.getZ()),
                new dev.sculptory.core.BlockPos(corner.getX() + 1, corner.getY() + 1, corner.getZ() + 1));
        try {
            host.run(player, new OpSpec.Fill(box, new Pattern.Single(h.state("minecraft:stone")), CellMask.ANY),
                    RunOptions.DEFAULT, new EngineTestSupport.RecordingListener());
        } catch (EditRejected e) {
            throw new GameTestException("fill rejected: " + e.getMessage());
        }
        context.assertTrue(host.jobs(player.getUuid()).size() == 1, "the fill was not admitted");
    }

    private static boolean parses(CommandDispatcher<ServerCommandSource> dispatcher, String command,
                                  ServerCommandSource source) {
        ParseResults<ServerCommandSource> parsed = dispatcher.parse(command, source);
        return parsed.getExceptions().isEmpty() && !parsed.getReader().canRead();
    }

    /** Parses "sculptory {@code sub}" as the console, then runs it as the console acting for {@code player}. */
    private static boolean refused(CommandDispatcher<ServerCommandSource> dispatcher, ServerCommandSource console,
                                   ServerPlayerEntity player, String sub) {
        ParseResults<ServerCommandSource> parsed = dispatcher.parse("sculptory " + sub, console);
        if (!parsed.getExceptions().isEmpty() || parsed.getReader().canRead()) {
            throw new GameTestException("the console could not parse /sculptory " + sub);
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
