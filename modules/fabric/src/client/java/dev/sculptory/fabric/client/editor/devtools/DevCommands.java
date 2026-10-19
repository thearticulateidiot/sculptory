package dev.sculptory.fabric.client.editor.devtools;

import dev.sculptory.core.state.StateSpace;
import java.util.function.Supplier;

/**
 * The development-only client commands: {@code /bsclient ghost ...} ({@link GhostDebug}, ghost previews without the
 * Place tool) and {@code /bsclient rotation compare} ({@link RotationCompare}, the free-rotation methods side by side).
 * The client entrypoint registers them in development runs only, and the mod jar leaves this package out
 * ({@code devHarnessPackages} in modules/fabric/build.gradle).
 */
public final class DevCommands {
    private DevCommands() {
    }

    /** Registers the commands. Call once from the client initializer. */
    public static void register(Supplier<StateSpace> states) {
        GhostDebug.register();
        RotationCompare.register(states);
    }
}
