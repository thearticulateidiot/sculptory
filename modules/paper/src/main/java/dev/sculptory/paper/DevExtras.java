package dev.sculptory.paper;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.sculptory.paper.engine.PaperEditHost;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;

/**
 * What the dev jar ({@code :paper:devJar}, {@code sculptory-paper-<version>-dev.jar}) adds to the plugin: the self-test,
 * the network test and the region benchmark (package {@code dev.sculptory.paper.devtest}). The release jar has no
 * implementation, so nothing of it is in a server running the release jar. Found through {@link ServiceLoader}
 * ({@code META-INF/services} in the dev jar), so no release class names a dev class.
 */
public interface DevExtras {
    /** The plugin's engine started ({@code onEnable}): a start-up self-test may begin. */
    void started(PaperEditHost host);

    /** Adds the dev subcommands under {@code /sculptory}; they exist only when the JVM was started for them. */
    void commands(LiteralArgumentBuilder<CommandSourceStack> root, Supplier<PaperEditHost> host);

    /** The plugin is being disabled: a run in progress puts its test areas back while the plugin can still write. */
    void disabling();

    /** The dev jar's extras, or empty for the release jar. */
    static Optional<DevExtras> find() {
        return ServiceLoader.load(DevExtras.class, DevExtras.class.getClassLoader()).findFirst();
    }
}
