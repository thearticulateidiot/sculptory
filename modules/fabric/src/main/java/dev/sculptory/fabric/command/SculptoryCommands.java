package dev.sculptory.fabric.command;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.fabric.SculptoryMod;
import dev.sculptory.fabric.engine.impl.EditServiceHost;
import dev.sculptory.fabric.engine.impl.EngineEditService;
import dev.sculptory.fabric.engine.impl.EngineRuntime;
import dev.sculptory.fabric.net.ServerNet;
import dev.sculptory.protocol.v2.Handshake;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.protocol.v2.ProtocolV2;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobListener;
import dev.sculptory.server.engine.JobResult;
import dev.sculptory.server.engine.JobTicket;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.impl.EditExecutor;
import dev.sculptory.server.engine.impl.HistorySnapshot;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.command.argument.BlockStateArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * Commands under {@code /sculptory}. They go through the same {@link EngineEditService} as the protocol, with the same
 * permission, limit and history rules. {@code /sculptory cancel} is for every editor user ({@code sculptory.use}), like the
 * job bars' Cancel button; the others are debug commands for server admins testing without the client editor and need
 * {@code sculptory.admin} (op level 2 by default). Players see only the ones they may use.
 * <ul>
 *   <li>{@code /sculptory fill <from> <to> <block>}: a fill job;</li>
 *   <li>{@code /sculptory undo}, {@code /sculptory redo}: skip conflicting cells;</li>
 *   <li>{@code /sculptory history}: your undo and redo labels;</li>
 *   <li>{@code /sculptory jobs}: your running jobs and the executor queues;</li>
 *   <li>{@code /sculptory version}: this server's build and your client's (admins: every player's; {@code use} is enough);</li>
 *   <li>{@code /sculptory reload}: reads {@code server.json} again (admin or console);</li>
 *   <li>{@code /sculptory cancel}: cancels all your jobs ({@code use} is enough);</li>
 *   <li>{@code /sculptory cancel <player>}: cancels all of another player's jobs, also after they left (admin or console).</li>
 * </ul>
 */
public final class SculptoryCommands {
    private static final SimpleCommandExceptionType NEEDS_USE = new SimpleCommandExceptionType(
            Text.literal("That player needs the " + Perm.USE.node() + " permission to use Sculptory"));
    private static final SimpleCommandExceptionType NEEDS_ADMIN = new SimpleCommandExceptionType(
            Text.literal("Cancelling another player's jobs needs the " + Perm.ADMIN.node() + " permission"));
    private static final SimpleCommandExceptionType UNKNOWN_PLAYER = new SimpleCommandExceptionType(
            Text.literal("No such player, and no jobs for that name (give an online player's name, the name of a "
                    + "player with jobs, or a UUID)"));

    private SculptoryCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                literal("sculptory").requires(source -> allowed(source, Perm.USE))
                        .then(literal("fill").requires(SculptoryCommands::admin)
                                .then(argument("from", BlockPosArgumentType.blockPos())
                                        .then(argument("to", BlockPosArgumentType.blockPos())
                                                .then(argument("block", BlockStateArgumentType.blockState(registryAccess))
                                                        .executes(SculptoryCommands::fill)))))
                        .then(literal("undo").requires(SculptoryCommands::admin)
                                .executes(context -> undoRedo(context, true)))
                        .then(literal("redo").requires(SculptoryCommands::admin)
                                .executes(context -> undoRedo(context, false)))
                        .then(literal("history").requires(SculptoryCommands::admin)
                                .executes(SculptoryCommands::history))
                        .then(literal("jobs").requires(SculptoryCommands::admin)
                                .executes(SculptoryCommands::jobs))
                        .then(literal("version").executes(SculptoryCommands::version))
                        .then(literal("reload").requires(SculptoryCommands::admin)
                                .executes(SculptoryCommands::reload))
                        .then(literal("cancel").executes(SculptoryCommands::cancel)
                                .then(argument("player", StringArgumentType.word())
                                        .requires(SculptoryCommands::admin)
                                        .suggests((context, builder) -> CommandSource.suggestMatching(
                                                context.getSource().getPlayerNames(), builder))
                                        .executes(SculptoryCommands::cancelOther)))));
    }

    private static boolean admin(ServerCommandSource source) {
        return allowed(source, Perm.ADMIN);
    }

    /**
     * Who may address a command node: a player holding {@code node} (admins hold every node through the op-level
     * fallback, but a permissions mod may grant {@code admin} alone, so it counts for every node here), or the console
     * and RCON (level 4, not command blocks), as in "execute as <player> run sculptory ...": Brigadier checks requirements
     * against the original source. Every subcommand still runs as a player and through that player's own permissions.
     */
    private static boolean allowed(ServerCommandSource source, Perm node) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return source.hasPermissionLevel(4);
        return EngineRuntime.find(source.getServer())
                .map(runtime -> runtime.permissions().has(player, Perm.ADMIN)
                        || node != Perm.ADMIN && runtime.permissions().has(player, node))
                .orElse(false);
    }

    private static int fill(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();
        Optional<EngineEditService> service = service(source);
        if (service.isEmpty()) return 0;
        BlockPos from = BlockPosArgumentType.getBlockPos(context, "from");
        BlockPos to = BlockPosArgumentType.getBlockPos(context, "to");
        BlockState state = BlockStateArgumentType.getBlockState(context, "block").getBlockState();
        Box box = Box.of(new dev.sculptory.core.BlockPos(from.getX(), from.getY(), from.getZ()),
                new dev.sculptory.core.BlockPos(to.getX(), to.getY(), to.getZ()));
        OpSpec.Fill op = new OpSpec.Fill(box, new Pattern.Single(Block.getRawIdFromState(state)), CellMask.ANY);
        try {
            JobTicket ticket = service.get().run(player, op, RunOptions.DEFAULT, feedback(source.getServer(), player, "Fill"));
            source.sendFeedback(() -> Text.literal("Fill started: job " + shortId(ticket.jobId()) + ", "
                    + count(ticket.estimatedCells()) + " cells"), false);
            return 1;
        } catch (EditRejected e) {
            source.sendError(Text.literal("Fill refused: " + e.getMessage()));
            return 0;
        }
    }

    private static int undoRedo(CommandContext<ServerCommandSource> context, boolean undo) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();
        Optional<EngineEditService> service = service(source);
        if (service.isEmpty()) return 0;
        String what = undo ? "Undo" : "Redo";
        JobListener feedback = feedback(source.getServer(), player, what);
        try {
            JobTicket ticket = undo
                    ? service.get().undo(player, ConflictPolicy.SKIP_CONFLICTS, feedback)
                    : service.get().redo(player, ConflictPolicy.SKIP_CONFLICTS, feedback);
            source.sendFeedback(() -> Text.literal(ticket.label() + ": job " + shortId(ticket.jobId())), false);
            return 1;
        } catch (EditRejected e) {
            source.sendError(Text.literal(what + " refused: " + e.getMessage()));
            return 0;
        }
    }

    private static int history(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = userOrThrow(source);
        Optional<EngineEditService> service = service(source);
        if (service.isEmpty()) return 0;
        HistorySnapshot snapshot = service.get().history(player);
        StringBuilder text = new StringBuilder("History (").append(kib(snapshot.bytes())).append(" KiB")
                .append(snapshot.busy() ? ", undo/redo running" : "").append(")");
        appendLabels(text, "Undo, next first", snapshot.undoLabels());
        appendLabels(text, "Redo, next first", snapshot.redoLabels());
        text.append("\nOn disk: ").append(service.get().historyService().storageStatus()).append("; ")
                .append(dev.sculptory.fabric.engine.impl.EditServiceHost.chunkSaveHookStatus());
        source.sendFeedback(() -> Text.literal(text.toString()), false);
        return snapshot.undoLabels().size() + snapshot.redoLabels().size();
    }

    private static int jobs(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = userOrThrow(source);
        Optional<EngineEditService> service = service(source);
        if (service.isEmpty()) return 0;
        List<EngineEditService.JobInfo> jobs = service.get().jobs(player.getUuid());
        EditExecutor<ServerWorld> executor = service.get().executor();
        StringBuilder text = new StringBuilder("Executor: ").append(executor.activeJobCount()).append(" active, ")
                .append(executor.queuedJobCount()).append(" queued, ").append(executor.brushQueueSize())
                .append(" dabs queued. Your jobs: ").append(jobs.size());
        for (EngineEditService.JobInfo job : jobs) {
            text.append("\n  ").append(shortId(job.jobId())).append(' ').append(job.label()).append(' ')
                    .append(job.phase()).append(' ').append(count(job.done())).append('/').append(count(job.total()));
        }
        source.sendFeedback(() -> Text.literal(text.toString()), false);
        return jobs.size();
    }

    /**
     * {@code /sculptory version} ({@code use}): the server's build id and protocol, and the build of the caller's client; for
     * admins and the console also every other online player's.
     */
    private static int version(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String server = SculptoryMod.buildId();
        StringBuilder text = new StringBuilder("Sculptory ").append(server).append(" on this server (protocol ")
                .append(ProtocolV2.VERSION).append(')');
        ServerPlayerEntity caller = source.getPlayer();
        if (caller != null) text.append("\nYour client: ").append(describeClient(caller, server));
        if (admin(source)) {
            for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
                if (player == caller) continue;
                text.append("\n  ").append(player.getGameProfile().getName()).append(": ")
                        .append(describeClient(player, server));
            }
        }
        source.sendFeedback(() -> Text.literal(text.toString()), false);
        return 1;
    }

    /**
     * {@code /sculptory reload} ({@code admin}, or the console): reads {@code server.json} again and applies it
     * ({@link EngineRuntime#reload}). A file that can't be used changes nothing and says why. Every player's editor is
     * told the new limits and permissions at once.
     */
    private static int reload(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<EngineRuntime> runtime = EngineRuntime.find(source.getServer());
        if (runtime.isEmpty()) {
            source.sendError(Text.literal("The Sculptory engine is not running"));
            return 0;
        }
        EngineRuntime.Reload result = runtime.get().reload(EngineRuntime.configFile());
        if (!result.ok()) {
            source.sendError(Text.literal(reloadReport(result)));
            return 0;
        }
        for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
            ServerNet.permissionsChanged(player);
        }
        source.sendFeedback(() -> Text.literal(reloadReport(result)), true);
        return Math.max(1, result.applied().size());
    }

    /** What {@code /sculptory reload} says about a reload. */
    static String reloadReport(EngineRuntime.Reload result) {
        if (!result.ok()) {
            return "Sculptory config not reloaded: " + result.file().toAbsolutePath() + " can't be used ("
                    + result.problem() + "). The running settings stay as they were. Fix the file, then run /sculptory reload "
                    + "again.";
        }
        StringBuilder text = new StringBuilder("Sculptory config reloaded from ")
                .append(result.file().toAbsolutePath()).append('.');
        if (result.applied().isEmpty() && result.needRestart().isEmpty()) text.append(" Nothing changed.");
        if (!result.applied().isEmpty()) {
            text.append("\nNow in effect: ").append(joined(result.applied()))
                    .append("\nEdits already running keep the limits they started with.");
        }
        if (!result.needRestart().isEmpty()) {
            text.append("\nChanged in the file, but only a restart applies them: ").append(joined(result.needRestart()));
        }
        if (!result.adjustments().isEmpty()) {
            text.append("\nOut of range, so adjusted: ").append(String.join("; ", result.adjustments()));
        }
        return text.toString();
    }

    private static String joined(List<?> values) {
        StringBuilder text = new StringBuilder();
        for (Object value : values) text.append(text.isEmpty() ? "" : ", ").append(value);
        return text.toString();
    }

    private static String describeClient(ServerPlayerEntity player, String serverBuild) {
        return describeClient(ServerNet.clientBuild(player).orElse(""), ServerNet.isIncompatible(player), serverBuild);
    }

    /** How {@code /sculptory version} describes a client's build next to the server's. */
    static String describeClient(String clientBuild, boolean incompatible, String serverBuild) {
        if (clientBuild.isEmpty()) return "no Sculptory editor connected";
        if (incompatible) return clientBuild + ", another protocol: editing is off (install the server's build)";
        if (clientBuild.equals(serverBuild)) return clientBuild + ", the same build";
        if (Handshake.differentBuilds(clientBuild, serverBuild)) {
            return clientBuild + ", a different build of the same protocol: editing works, but the same build is safer";
        }
        return clientBuild + ", the same protocol (which build isn't known)";
    }

    private static int cancel(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        // Addressing /sculptory cancel needs sculptory.use (or admin, or the console under "execute as"); cancelling one's
        // own jobs needs nothing more, like the protocol's CancelJob.
        ServerPlayerEntity player = source.getPlayerOrThrow();
        requireSelfOrAdmin(source, player);
        Optional<EngineEditService> service = service(source);
        if (service.isEmpty()) return 0;
        int cancelled = service.get().cancelAll(player);
        source.sendFeedback(() -> Text.literal("Cancelled " + cancelled + (cancelled == 1 ? " job" : " jobs")), false);
        return cancelled;
    }

    /**
     * Under "execute as <player> run sculptory cancel" the command acts as that player, but its output is still whoever issued
     * it, and Brigadier checked the requirements against the issuer, who needs only {@code use} to address
     * {@code /sculptory cancel}. So a player cancelling someone else's jobs this way needs {@code admin}, as for
     * {@code /sculptory cancel <player>}. The console and RCON may (they passed the level-4 requirement).
     */
    private static void requireSelfOrAdmin(ServerCommandSource source, ServerPlayerEntity player)
            throws CommandSyntaxException {
        if (source.output instanceof ServerPlayerEntity issuer && !issuer.getUuid().equals(player.getUuid())
                && !EngineRuntime.find(source.getServer())
                        .map(runtime -> runtime.permissions().has(issuer, Perm.ADMIN)).orElse(false)) {
            throw NEEDS_ADMIN.create();
        }
    }

    /**
     * {@code /sculptory cancel <player>}: cancels another player's jobs, online or not (a player who left keeps their running
     * jobs): waiting ones at once, running ones at their next section boundary. Run by a player it needs their
     * {@code sculptory.admin} (also under {@code execute as}); the console and RCON may run it. The player is named by
     * name (online, or recorded on one of their jobs) or by UUID.
     */
    private static int cancelOther(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity actor = source.getPlayer();
        boolean allowed = actor == null ? source.hasPermissionLevel(4) : EngineRuntime.find(source.getServer())
                .map(runtime -> runtime.permissions().has(actor, Perm.ADMIN)).orElse(false);
        if (!allowed) throw NEEDS_ADMIN.create();
        Optional<EngineEditService> service = service(source);
        if (service.isEmpty()) return 0;
        String name = StringArgumentType.getString(context, "player");
        UUID target = playerId(source.getServer(), service.get(), name).orElseThrow(UNKNOWN_PLAYER::create);
        int cancelled = service.get().cancelAll(target);
        source.sendFeedback(() -> Text.literal("Cancelled " + cancelled + (cancelled == 1 ? " job" : " jobs") + " of "
                + name), true);
        return cancelled;
    }

    /**
     * A player's UUID from the name of an online player, the owner name recorded on an admitted job (a player who left),
     * or a UUID. Never asks the profile cache, which may call Mojang's servers and block the server thread.
     */
    static Optional<UUID> playerId(MinecraftServer server, EngineEditService service, String text) {
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(text);
        if (online != null) return Optional.of(online.getUuid());
        Optional<UUID> owner = service.jobOwnerNamed(text);
        if (owner.isPresent()) return owner;
        try {
            return Optional.of(UUID.fromString(text));
        } catch (IllegalArgumentException notUuid) {
            return Optional.empty();
        }
    }

    /**
     * The player the command acts for, who must hold {@code sculptory.use} like any editor user. (Fill, undo and
     * redo get this check from the edit service; history and jobs read the player's state
     * directly.) Refused as a command error, like a missing player. Without a running engine the command goes on to
     * report that instead.
     */
    private static ServerPlayerEntity userOrThrow(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        Optional<EngineRuntime> runtime = EngineRuntime.find(source.getServer());
        if (runtime.isPresent() && !runtime.get().permissions().has(player, Perm.USE)) throw NEEDS_USE.create();
        return player;
    }

    private static Optional<EngineEditService> service(ServerCommandSource source) {
        Optional<EngineEditService> service = EditServiceHost.find(source.getServer());
        if (service.isEmpty()) source.sendError(Text.literal("The Sculptory engine is not running"));
        return service;
    }

    /** Reports the job's end to the player, if they are still online. */
    private static JobListener feedback(MinecraftServer server, ServerPlayerEntity player, String what) {
        UUID id = player.getUuid();
        return new JobListener() {
            @Override
            public void progress(UUID job, long done, long total, Phase ph) {}

            @Override
            public void finished(JobResult r) {
                ServerPlayerEntity online = server.getPlayerManager().getPlayer(id);
                if (online == null) return;
                StringBuilder text = new StringBuilder(what).append(' ')
                        .append(r.outcome().name().toLowerCase(Locale.ROOT)).append(": ")
                        .append(count(r.changed())).append(" changed");
                if (r.skippedProtected() > 0) text.append(", ").append(count(r.skippedProtected())).append(" protected");
                if (r.skippedConflicts() > 0) text.append(", ").append(count(r.skippedConflicts())).append(" conflicts kept");
                if (r.strippedNbt() > 0) text.append(", ").append(count(r.strippedNbt())).append(" NBT stripped");
                online.sendMessage(Text.literal(text.toString()));
            }
        };
    }

    private static void appendLabels(StringBuilder text, String title, List<String> labels) {
        text.append("\n").append(title).append(": ").append(labels.isEmpty() ? "none" : "");
        for (int i = 0; i < labels.size(); i++) text.append("\n  ").append(i + 1).append(". ").append(labels.get(i));
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static String count(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    private static String kib(long bytes) {
        return count((bytes + 1023) / 1024);
    }
}
