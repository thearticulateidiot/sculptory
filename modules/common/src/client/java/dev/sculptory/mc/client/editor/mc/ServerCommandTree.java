package dev.sculptory.mc.client.editor.mc;

import com.mojang.brigadier.CommandDispatcher;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.commands.SharedSuggestionProvider;

/**
 * The server's command tree as the server sent it, before the loader's client command API merges the client commands
 * into it: {@code ClientCommandTreeMixin} hands each one here, and the loader's {@code /flyspeed} registration listens
 * (it reads whether the server has a {@code /flyspeed} of its own and readies the tree for the merge). Client thread
 * only.
 */
public final class ServerCommandTree {
    private static final List<Consumer<CommandDispatcher<SharedSuggestionProvider>>> LISTENERS =
            new CopyOnWriteArrayList<>();

    private ServerCommandTree() {
    }

    /** Calls {@code listener} with every command tree the server sends from now on. */
    public static void listen(Consumer<CommandDispatcher<SharedSuggestionProvider>> listener) {
        LISTENERS.add(Objects.requireNonNull(listener));
    }

    /** A command tree arrived ({@code ClientCommandTreeMixin}). */
    public static void received(CommandDispatcher<SharedSuggestionProvider> tree) {
        for (Consumer<CommandDispatcher<SharedSuggestionProvider>> listener : LISTENERS) {
            listener.accept(tree);
        }
    }
}
