package dev.sculptory.neoforge.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;

/**
 * Keeps the player's movement keys working while the editor screen is open.
 *
 * <p>NeoForge gives vanilla's movement keys the {@code IN_GAME} conflict context ({@code Options.setForgeKeybindProperties}),
 * which is inactive whenever any screen is open. Its {@code KeyMapping.set} finds only active mappings
 * ({@code KeyMappingLookup.findKeybinds} filters on {@code isActiveAndMatches}), and its {@code KeyMapping.isDown} answers
 * false while the context is inactive. So under the editor screen the forwarded W pressed nothing, a held key would not
 * read as held, and a release would not reach the mapping either. Fabric has no contexts, which is why the same
 * forwarding works there.
 *
 * <p>This context is the mapping's own one plus "the editor screen is open". Pressing, holding and releasing those keys
 * then work under the editor screen as they do in game, and every other screen (chat, inventory, menus) still blocks
 * them. Conflicts are the wrapped context's, so the Controls screen and key modifiers see the keys as before.
 */
final class EditorMovementContext implements IKeyConflictContext {
    private final IKeyConflictContext own;
    private final BooleanSupplier editorOpen;

    EditorMovementContext(IKeyConflictContext own, BooleanSupplier editorOpen) {
        this.own = Objects.requireNonNull(own);
        this.editorOpen = Objects.requireNonNull(editorOpen);
    }

    /**
     * Wraps the current context of each key that doesn't have this one yet (never a wrapper in a wrapper), and returns
     * those keys as "{@code <key name> (<context class>)}". Called again later, it finds the keys whose context another
     * mod has set since and wraps the new context.
     */
    static List<String> install(List<KeyMapping> keys, BooleanSupplier editorOpen) {
        List<String> wrapped = new ArrayList<>();
        for (KeyMapping key : keys) {
            IKeyConflictContext current = key.getKeyConflictContext();
            if (!(current instanceof EditorMovementContext)) {
                key.setKeyConflictContext(new EditorMovementContext(current, editorOpen));
                wrapped.add(key.getName() + " (" + describe(current) + ")");
            }
        }
        return wrapped;
    }

    /** A context's name for the log: an enum constant (NeoForge's own contexts) by name, others by class. */
    private static String describe(IKeyConflictContext context) {
        return context instanceof Enum<?> constant
                ? constant.getDeclaringClass().getName() + "." + constant.name()
                : context.getClass().getName();
    }

    @Override
    public boolean isActive() {
        return own.isActive() || editorOpen.getAsBoolean();
    }

    @Override
    public boolean conflicts(IKeyConflictContext other) {
        return own.conflicts(other instanceof EditorMovementContext wrapped ? wrapped.own : other);
    }
}
