package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The client command tree: {@code /sculptory flyspeed} always, {@code /flyspeed} only where the server has none of its
 * own (an unknown client command goes on to the server: Fabric sends on exactly the "unknown command" refusal), and
 * the rest of {@code /sculptory} forwarded to the server as typed.
 */
class FlySpeedCommandTreeTest {
    private final List<String> ran = new ArrayList<>();
    private boolean serverHasOwn;
    private final CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();

    FlySpeedCommandTreeTest() {
        FlySpeedCommand.register(dispatcher, () -> serverHasOwn, new FlySpeedCommand.Handler<>() {
            @Override
            public int flySpeed(Object source, Optional<String> argument) {
                ran.add("flyspeed " + argument.orElse("(show)"));
                return 1;
            }

            @Override
            public int forward(Object source, String command) {
                ran.add("server: " + command);
                return 1;
            }
        });
    }

    private void run(String command) throws CommandSyntaxException {
        dispatcher.execute(command, new Object());
    }

    @Test
    void withoutAServerFlyspeedTheBareCommandIsSculptorys() throws CommandSyntaxException {
        run("flyspeed");
        run("flyspeed 2.5");
        run("flyspeed reset");
        assertEquals(List.of("flyspeed (show)", "flyspeed 2.5", "flyspeed reset"), ran);
    }

    @Test
    void aServerWithItsOwnFlyspeedGetsTheBareCommand() {
        serverHasOwn = true;
        CommandSyntaxException unknown = assertThrows(CommandSyntaxException.class, () -> run("flyspeed 2"));
        assertSame(CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand(), unknown.getType(),
                "the refusal Fabric sends on to the server");
        assertEquals(List.of(), ran);
    }

    @Test
    void sculptoryFlyspeedWorksEitherWay() throws CommandSyntaxException {
        run("sculptory flyspeed 3");
        serverHasOwn = true;
        run("sculptory flyspeed");
        run("sculptory flyspeed reset");
        assertEquals(List.of("flyspeed 3", "flyspeed (show)", "flyspeed reset"), ran);
    }

    @Test
    void theServersSculptoryCommandsGoToTheServerAsTyped() throws CommandSyntaxException {
        run("sculptory");
        run("sculptory undo");
        run("sculptory fill 1 2 3 4 5 6 minecraft:stone");
        run("sculptory cancel Alex");
        run("sculptory flyspeedx");
        assertEquals(List.of("server: sculptory", "server: sculptory undo",
                "server: sculptory fill 1 2 3 4 5 6 minecraft:stone", "server: sculptory cancel Alex",
                "server: sculptory flyspeedx"), ran);
    }

    /**
     * Fabric checks the client commands for ambiguities at join and logs each as a warning: the forwarded rest of
     * {@code /sculptory} leaves {@code flyspeed} to its own node, so there are none.
     */
    @Test
    void theTreeHasNoAmbiguities() throws CommandSyntaxException {
        List<String> ambiguities = new ArrayList<>();
        dispatcher.findAmbiguities((parent, child, sibling, inputs) ->
                ambiguities.add(dispatcher.getPath(child) + " / " + dispatcher.getPath(sibling) + " " + inputs));
        assertEquals(List.of(), ambiguities);
        // The forwarded rest reads what greedyString reads, but not flyspeed as its first word.
        assertEquals("undo now", FlySpeedCommand.ForwardedRest.INSTANCE.parse(new StringReader("undo now")));
        assertEquals("flyspeedx 2", FlySpeedCommand.ForwardedRest.INSTANCE.parse(new StringReader("flyspeedx 2")));
        assertThrows(CommandSyntaxException.class,
                () -> FlySpeedCommand.ForwardedRest.INSTANCE.parse(new StringReader("flyspeed 2")));
        assertThrows(CommandSyntaxException.class,
                () -> FlySpeedCommand.ForwardedRest.INSTANCE.parse(new StringReader("flyspeed")));
        StringReader reader = new StringReader("sculptory cancel Alex");
        reader.setCursor("sculptory ".length());
        assertEquals("cancel Alex", FlySpeedCommand.ForwardedRest.INSTANCE.parse(reader));
        assertTrue(!reader.canRead(), "reads to the end");
    }

    /** The rest of {@code /sculptory} suggests nothing of its own, as the greedy string it replaced. */
    @Test
    void theForwardedRestSuggestsNothing() throws Exception {
        assertEquals(List.of(), suggestions(dispatcher, "sculptory und"));
        assertEquals(List.of("flyspeed"), suggestions(dispatcher, "sculptory fl"));
    }

    /**
     * Fabric's merge of the client commands into the server's tree (ClientCommandInternals.copyChildren in
     * fabric-command-api-v2 2.2.27): each node is added to the target before its own children are copied into it.
     */
    private static <S> void fabricMerge(CommandNode<S> origin, CommandNode<S> target) {
        for (CommandNode<S> child : origin.getChildren()) {
            ArgumentBuilder<S, ?> builder = child.createBuilder();
            builder.requires(source -> true);
            if (builder.getCommand() != null) builder.executes(context -> 0);
            CommandNode<S> result = builder.build();
            target.addChild(result);
            fabricMerge(child, result);
        }
    }

    /** A server tree with its own {@code /sculptory} (as a Sculptory server sends it), with the client's merged in. */
    private CommandDispatcher<Object> mergedServerTree(boolean prepared) {
        CommandDispatcher<Object> server = new CommandDispatcher<>();
        server.register(LiteralArgumentBuilder.literal("sculptory")
                .then(LiteralArgumentBuilder.literal("undo").executes(context -> 1))
                .then(LiteralArgumentBuilder.literal("version").executes(context -> 1)));
        if (prepared) FlySpeedCommand.prepareServerTree(server.getRoot());
        fabricMerge(dispatcher.getRoot(), server.getRoot());
        return server;
    }

    private static List<String> suggestions(CommandDispatcher<Object> tree, String input) throws Exception {
        return tree.getCompletionSuggestions(tree.parse(input, new Object())).get().getList().stream()
                .map(suggestion -> suggestion.getText()).toList();
    }

    @Test
    void aServerWithItsOwnSculptoryRootStillOffersFlyspeedOnceTheTreeIsPrepared() throws Exception {
        CommandDispatcher<Object> unprepared = mergedServerTree(false);
        assertEquals(List.of(), suggestions(unprepared, "sculptory fl"), "what Fabric's merge alone leaves");

        CommandDispatcher<Object> merged = mergedServerTree(true);
        assertEquals(List.of("flyspeed"), suggestions(merged, "sculptory fl"));
        assertEquals(List.of("reset"), suggestions(merged, "sculptory flyspeed r"));
        ParseResults<Object> parsed = merged.parse("sculptory flyspeed 2", new Object());
        assertTrue(parsed.getExceptions().isEmpty() && !parsed.getReader().canRead(), "parses whole, no red underline");
        assertEquals(List.of("undo"), suggestions(merged, "sculptory un"), "the server's own commands stay");
        assertTrue(merged.getRoot().getChild("flyspeed") != null, "bare /flyspeed merged too (no server one)");
    }

    @Test
    void preparingLeavesATreeWithoutSculptoryOrWithItsOwnFlyspeedAlone() {
        CommandDispatcher<Object> vanilla = new CommandDispatcher<>();
        vanilla.register(LiteralArgumentBuilder.literal("gamemode").executes(context -> 1));
        FlySpeedCommand.prepareServerTree(vanilla.getRoot());
        assertEquals(1, vanilla.getRoot().getChildren().size());
        CommandDispatcher<Object> own = new CommandDispatcher<>();
        CommandNode<Object> ownFlySpeed = LiteralArgumentBuilder.literal("flyspeed").executes(context -> 1).build();
        own.register(LiteralArgumentBuilder.literal("sculptory"));
        own.getRoot().getChild("sculptory").addChild(ownFlySpeed);
        FlySpeedCommand.prepareServerTree(own.getRoot());
        assertSame(ownFlySpeed, own.getRoot().getChild("sculptory").getChild("flyspeed"));
    }

    @Test
    void theSpeedSuggestsItsValues() throws Exception {
        List<String> offered = dispatcher.getCompletionSuggestions(dispatcher.parse("flyspeed r", new Object())).get()
                .getList().stream().map(s -> s.getText()).toList();
        assertEquals(List.of("reset"), offered);
    }
}
