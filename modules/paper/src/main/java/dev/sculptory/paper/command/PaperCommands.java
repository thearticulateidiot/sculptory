package dev.sculptory.paper.command;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.mc.Sculptory;
import dev.sculptory.mc.net.ServerNet;
import dev.sculptory.mc.net.VersionReport;
import dev.sculptory.paper.DevExtras;
import dev.sculptory.paper.engine.PaperEditHost;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobListener;
import dev.sculptory.server.engine.JobResult;
import dev.sculptory.server.engine.JobTicket;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code /sculptory} on Paper: fill, undo, redo and version as the loaders' command has them, plus, in the dev jar
 * only, the self-test and network test ({@link DevExtras}). Registered on the server's Brigadier dispatcher, so {@code execute as <player> run
 * sculptory ...} acts as that player. Fill, undo and redo run as a player and through that player's permissions and
 * protection, like an editor request; their outcome is logged.
 */
public final class PaperCommands {
    private static final SimpleCommandExceptionType NOT_RUNNING =
            new SimpleCommandExceptionType(Component.literal("The Sculptory engine is not running"));
    private final Supplier<PaperEditHost> host;
    private final Optional<DevExtras> extras;

    public PaperCommands(Supplier<PaperEditHost> host, Optional<DevExtras> extras) {
        this.host = host;
        this.extras = extras;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        // Like the loaders' command: only a holder of use (or admin) sees /sculptory at all; the console always.
        LiteralArgumentBuilder<CommandSourceStack> root = literal("sculptory").requires(source -> allowed(source, Perm.USE))
                .then(literal("fill").requires(source -> allowed(source, Perm.ADMIN))
                        .then(argument("from", BlockPosArgument.blockPos())
                                .then(argument("to", BlockPosArgument.blockPos())
                                        .then(argument("block", BlockStateArgument.block(context))
                                                .executes(this::fill)))))
                .then(literal("undo").requires(source -> allowed(source, Perm.ADMIN)).executes(c -> undoRedo(c, true)))
                .then(literal("redo").requires(source -> allowed(source, Perm.ADMIN)).executes(c -> undoRedo(c, false)))
                .then(literal("version").executes(this::version));
        // The dev jar's self-test commands (DevExtras); the release jar has none.
        extras.ifPresent(dev -> dev.commands(root, host));
        dispatcher.register(root);
    }

    /** The running engine; a command without one fails (the plugin is being disabled, or failed to start). */
    private PaperEditHost running(CommandSourceStack source) throws CommandSyntaxException {
        PaperEditHost running = host.get();
        if (running == null) throw NOT_RUNNING.create();
        return running;
    }

    /**
     * Who may address a command node, as the loaders' command decides it: a player holding {@code node}, or holding
     * {@code admin} (a permissions plugin may grant admin alone, so it counts for every node), or the console and RCON
     * (level 4, not command blocks). Without a running engine, no player.
     */
    private boolean allowed(CommandSourceStack source, Perm node) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return source.hasPermission(4);
        PaperEditHost running = host.get();
        if (running == null) return false;
        return running.engine().permissions().has(player, Perm.ADMIN)
                || node != Perm.ADMIN && running.engine().permissions().has(player, node);
    }

    private int fill(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        BlockPos from = BlockPosArgument.getBlockPos(context, "from");
        BlockPos to = BlockPosArgument.getBlockPos(context, "to");
        BlockState state = BlockStateArgument.getBlock(context, "block").getState();
        dev.sculptory.core.Box box = dev.sculptory.core.Box.of(
                new dev.sculptory.core.BlockPos(from.getX(), from.getY(), from.getZ()),
                new dev.sculptory.core.BlockPos(to.getX(), to.getY(), to.getZ()));
        OpSpec.Fill op = new OpSpec.Fill(box, new Pattern.Single(Block.getId(state)), CellMask.ANY);
        try {
            JobListener log = logging(player, "Fill");
            JobTicket ticket = running(source).service().run(player, op, RunOptions.DEFAULT, log);
            Sculptory.LOG.info("Sculptory fill by {}: {} {} -> {} started, job {}, {} cells", name(player), state,
                    from.toShortString(), to.toShortString(), ticket.jobId(), ticket.estimatedCells());
            source.sendSuccess(() -> Component.literal("Fill started: job " + ticket.jobId()), false);
            return 1;
        } catch (EditRejected e) {
            Sculptory.LOG.info("Sculptory fill by {} refused: {}", name(player), e.getMessage());
            source.sendFailure(Component.literal("Fill refused: " + e.getMessage()));
            return 0;
        }
    }

    private int undoRedo(CommandContext<CommandSourceStack> context, boolean undo) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        String what = undo ? "Undo" : "Redo";
        try {
            JobListener log = logging(player, what);
            JobTicket ticket = undo
                    ? running(source).service().undo(player, ConflictPolicy.SKIP_CONFLICTS, log)
                    : running(source).service().redo(player, ConflictPolicy.SKIP_CONFLICTS, log);
            Sculptory.LOG.info("Sculptory {} by {} started: {} (job {})", what.toLowerCase(), name(player),
                    ticket.label(), ticket.jobId());
            source.sendSuccess(() -> Component.literal(what + ": " + ticket.label()), false);
            return 1;
        } catch (EditRejected e) {
            Sculptory.LOG.info("Sculptory {} by {} refused: {}", what.toLowerCase(), name(player), e.getMessage());
            source.sendFailure(Component.literal(what + " refused: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * {@code /sculptory version} ({@code use}): the server's builds and protocol and the caller's client build; for admins
     * and the console every other online player's too. The same report as the loaders' command ({@link VersionReport}).
     */
    private int version(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String text = VersionReport.text(source.getPlayer(), source.getServer().getPlayerList().getPlayers(),
                allowed(source, Perm.ADMIN));
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    /** The job's progress to the player's client (ServerNet's listener) and its outcome to the server log. */
    private static JobListener logging(ServerPlayer player, String what) {
        JobListener client = ServerNet.jobListener(player);
        return new JobListener() {
            @Override
            public void progress(UUID job, long done, long total, Phase phase) {
                client.progress(job, done, total, phase);
            }

            @Override
            public void finished(JobResult result) {
                client.finished(result);
                Sculptory.LOG.info("Sculptory {} job {} finished: {}, {} changed, {} skipped (protected), {} skipped "
                        + "(conflicts)", what.toLowerCase(), result.jobId(), result.outcome(), result.changed(),
                        result.skippedProtected(), result.skippedConflicts());
            }
        };
    }

    private static String name(ServerPlayer player) {
        return player.getGameProfile().getName();
    }
}
