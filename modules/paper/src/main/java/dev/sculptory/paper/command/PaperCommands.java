package dev.sculptory.paper.command;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
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
import dev.sculptory.paper.SculptoryPlugin;
import dev.sculptory.paper.devtest.NetTest;
import dev.sculptory.paper.devtest.RegionBench;
import dev.sculptory.paper.devtest.SelfTest;
import dev.sculptory.paper.engine.PaperEditHost;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobListener;
import dev.sculptory.server.engine.JobResult;
import dev.sculptory.server.engine.JobTicket;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Bukkit;

/**
 * {@code /sculptory} on Paper (spike): fill, undo, redo and version as the loaders' command has them, plus the dev
 * self-test and network test. Registered on the server's Brigadier dispatcher, so {@code execute as <player> run
 * sculptory ...} acts as that player. Fill, undo and redo run as a player and through that player's permissions and
 * protection, like an editor request; their outcome is logged.
 */
public final class PaperCommands {
    private static final SimpleCommandExceptionType NOT_RUNNING =
            new SimpleCommandExceptionType(Component.literal("The Sculptory engine is not running"));
    private final Supplier<PaperEditHost> host;

    public PaperCommands(Supplier<PaperEditHost> host) {
        this.host = host;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        LiteralArgumentBuilder<CommandSourceStack> root = literal("sculptory")
                .then(literal("fill").requires(source -> allowed(source, Perm.ADMIN))
                        .then(argument("from", BlockPosArgument.blockPos())
                                .then(argument("to", BlockPosArgument.blockPos())
                                        .then(argument("block", BlockStateArgument.block(context))
                                                .executes(this::fill)))))
                .then(literal("undo").requires(source -> allowed(source, Perm.ADMIN)).executes(c -> undoRedo(c, true)))
                .then(literal("redo").requires(source -> allowed(source, Perm.ADMIN)).executes(c -> undoRedo(c, false)))
                .then(literal("version").executes(this::version));
        // Dev only: the adapter checks, the frame-size probe and the region benchmark exist only when the JVM was started
        // for them (SelfTest.enabled), and then only for the console or a holder of sculptory.selftest (not ops).
        if (SelfTest.enabled()) {
            root.then(literal("selftest").requires(SelfTest::allowed)
                            .executes(c -> SelfTest.run(host.get(), c.getSource(), ""))
                            .then(argument("player", EntityArgument.player())
                                    .executes(c -> SelfTest.run(host.get(), c.getSource(),
                                            EntityArgument.getPlayer(c, "player").getGameProfile().getName()))))
                    .then(literal("wgbench").requires(SelfTest::allowed)
                            .executes(c -> RegionBench.run(host.get(), c.getSource(), 48))
                            .then(argument("height", IntegerArgumentType.integer(1, 384))
                                    .executes(c -> RegionBench.run(host.get(), c.getSource(),
                                            IntegerArgumentType.getInteger(c, "height")))))
                    // Disables and enables this plugin instance on the next tick (what a plugin manager does).
                    .then(literal("reenable").requires(SelfTest::allowed).executes(c -> reenable(c.getSource())))
                    .then(literal("nettest").requires(SelfTest::allowed)
                            .then(argument("player", EntityArgument.player())
                                    .then(argument("bytes", IntegerArgumentType.integer(1, 4 * 1024 * 1024))
                                            .then(argument("route", StringArgumentType.word())
                                                    .executes(c -> NetTest.send(c.getSource(),
                                                            EntityArgument.getPlayer(c, "player"),
                                                            IntegerArgumentType.getInteger(c, "bytes"),
                                                            StringArgumentType.getString(c, "route")))))));
        }
        dispatcher.register(root);
    }

    private static int reenable(CommandSourceStack source) {
        SculptoryPlugin plugin = SculptoryPlugin.instance();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Sculptory.LOG.info("Sculptory: disabling and enabling the plugin again (dev)");
            Bukkit.getPluginManager().disablePlugin(plugin);
            Bukkit.getPluginManager().enablePlugin(plugin);
            Sculptory.LOG.info("Sculptory: enabled again: {}", plugin.isEnabled());
        });
        source.sendSuccess(() -> Component.literal("Re-enabling Sculptory on the next tick"), false);
        return 1;
    }

    /** The running engine; a command without one fails (the plugin is being enabled again, or failed to start). */
    private PaperEditHost running(CommandSourceStack source) throws CommandSyntaxException {
        PaperEditHost running = host.get();
        if (running == null) throw NOT_RUNNING.create();
        return running;
    }

    private boolean allowed(CommandSourceStack source, Perm node) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return source.hasPermission(4);
        PaperEditHost running = host.get();
        return running != null && running.engine().permissions().has(player, node);
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

    private int version(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        StringBuilder text = new StringBuilder("Sculptory for Paper ").append(Sculptory.productBuildId())
                .append(" (engine ").append(Sculptory.buildId()).append(")");
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            text.append("; your client: ").append(ServerNet.clientBuild(player).orElse("no Sculptory handshake"));
        }
        source.sendSuccess(() -> Component.literal(text.toString()), false);
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
