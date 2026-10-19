package dev.sculptory.fabric.client.editor;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * The fly speed's client command (no operator rights needed, any server), as {@code /sculptory flyspeed} always and as
 * {@code /flyspeed} where the server has no {@code flyspeed} command of its own: alone it shows the fly speed;
 * {@code <speed>} sets it ({@link FlySpeed#parse}: 0.25 to 8, decimals allowed, out-of-range values clamped);
 * {@code reset} sets 1×. The answer is a translatable message, the editor's {@code sculptory.flyspeed.*} keys. The
 * Minecraft side registers the tree ({@link #register}) and shows the reply in chat. Pure Java; client thread only.
 *
 * <p>The client's {@code /sculptory} root sits beside the server's ({@code SculptoryCommands}): a client command takes
 * the whole input once its root matches, so everything under {@code /sculptory} but {@code flyspeed} (and
 * {@code /sculptory} alone) is sent on to the server as typed ({@link Handler#forward}). The server's tree has no
 * {@code flyspeed} child, and none of its arguments is signed, so a forwarded command runs there as it always did.
 */
public final class FlySpeedCommand {
    public static final String NAME = "flyspeed";
    /** What the argument suggests while typing. */
    public static final List<String> SUGGESTIONS = List.of("reset", "0.5", "1", "1.5", "2", "4", "8");

    /**
     * A reply: a translation key with its arguments; {@code error} for a refusal (shown red), and
     * {@code notFlyingHint} when the speed applies only once the player flies in creative or spectator or opens the
     * editor (a second line, {@link #NOT_FLYING_KEY}).
     */
    public record Reply(String key, List<Object> args, boolean error, boolean notFlyingHint) {
        public Reply {
            Objects.requireNonNull(key);
            args = List.copyOf(args);
        }
    }

    public static final String NOT_FLYING_KEY = "sculptory.flyspeed.not_flying";

    /** The root of Sculptory's commands, the server's and this client one. */
    public static final String ROOT = "sculptory";

    /** What the command tree calls on the Minecraft side. */
    public interface Handler<S> {
        /** {@link #run} for {@code source} and shows the reply; returns the command result (0 for a refusal). */
        int flySpeed(S source, Optional<String> argument);

        /** Sends {@code command} (as typed, without the slash) to the server, bypassing the client commands. */
        int forward(S source, String command);
    }

    private FlySpeedCommand() {
    }

    /**
     * Registers {@code /sculptory flyspeed [<speed>]} (with {@code /sculptory} and {@code /sculptory <anything else>}
     * forwarded to the server) and {@code /flyspeed [<speed>]}, which only exists while {@code serverHasOwn} is false.
     * A client command that does not exist (or whose requirement fails) is "unknown", and Fabric sends unknown commands
     * on to the server: so on a server with its own {@code /flyspeed}, typing it runs the server's.
     */
    public static <S> void register(CommandDispatcher<S> dispatcher, BooleanSupplier serverHasOwn, Handler<S> handler) {
        Objects.requireNonNull(serverHasOwn);
        Objects.requireNonNull(handler);
        dispatcher.register(flySpeed(LiteralArgumentBuilder.<S>literal(NAME), handler)
                .requires(source -> !serverHasOwn.getAsBoolean()));
        dispatcher.register(LiteralArgumentBuilder.<S>literal(ROOT)
                .executes(context -> handler.forward(context.getSource(), context.getInput()))
                .then(flySpeed(LiteralArgumentBuilder.<S>literal(NAME), handler))
                .then(RequiredArgumentBuilder.<S, String>argument("command", StringArgumentType.greedyString())
                        .executes(context -> handler.forward(context.getSource(), context.getInput()))));
    }

    /**
     * Readies the server's command tree, as the server sent it, for Fabric's merge of the client commands (for
     * suggestions and the chat field's checking): where the server has a {@code sculptory} root without a
     * {@code flyspeed} child, one is added (its commands do nothing: the client command runs it). Fabric adds each client
     * node to the tree before filling in its children, and Brigadier folds a node into an existing one of the same name
     * by copying the children it has then (none), so the client's {@code /sculptory flyspeed} would otherwise never reach
     * a server tree that already has {@code /sculptory}.
     */
    public static <S> void prepareServerTree(RootCommandNode<S> server) {
        CommandNode<S> root = server.getChild(ROOT);
        if (root == null || root.getChild(NAME) != null) return;
        root.addChild(flySpeed(LiteralArgumentBuilder.<S>literal(NAME), new Handler<>() {
            @Override
            public int flySpeed(S source, Optional<String> argument) {
                return 0;
            }

            @Override
            public int forward(S source, String command) {
                return 0;
            }
        }).build());
    }

    private static <S> LiteralArgumentBuilder<S> flySpeed(LiteralArgumentBuilder<S> node, Handler<S> handler) {
        SuggestionProvider<S> suggestions = (context, builder) -> {
            for (String suggestion : SUGGESTIONS) {
                if (suggestion.startsWith(builder.getRemainingLowerCase())) builder.suggest(suggestion);
            }
            return builder.buildFuture();
        };
        return node.executes(context -> handler.flySpeed(context.getSource(), Optional.empty()))
                .then(RequiredArgumentBuilder.<S, String>argument("speed", StringArgumentType.greedyString())
                        .suggests(suggestions)
                        .executes(context -> handler.flySpeed(context.getSource(),
                                Optional.of(StringArgumentType.getString(context, "speed")))));
    }

    /**
     * Runs the command. {@code argument} is empty for {@code /flyspeed} alone; {@code appliesNow} says whether the
     * speed takes effect now (the editor is open, or the player is in creative or spectator).
     */
    public static Reply run(FlySpeed speed, Optional<String> argument, boolean appliesNow) {
        Objects.requireNonNull(speed);
        if (argument.isEmpty() || argument.get().isBlank()) {
            return new Reply("sculptory.flyspeed.current", List.of(speed.display()), false, false);
        }
        String typed = argument.get().strip();
        Optional<Double> parsed = FlySpeed.parse(typed);
        if (parsed.isEmpty()) {
            return new Reply("sculptory.flyspeed.invalid", List.of(typed), true, false);
        }
        boolean reset = typed.equalsIgnoreCase("reset");
        double clamped = FlySpeed.clamp(parsed.get());
        speed.set(clamped);
        List<Object> args = new ArrayList<>();
        String key;
        if (reset) {
            key = "sculptory.flyspeed.reset";
            args.add(speed.display());
        } else if (parsed.get() < FlySpeed.MIN || parsed.get() > FlySpeed.MAX) {
            key = "sculptory.flyspeed.clamped";
            args.add(speed.display());
            args.add(typed);
        } else {
            key = "sculptory.flyspeed.set";
            args.add(speed.display());
        }
        return new Reply(key, args, false, !appliesNow);
    }
}
