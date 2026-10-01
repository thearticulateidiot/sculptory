package dev.sculptory.fabric.gametest;

import dev.sculptory.fabric.engine.impl.EditServiceHost;
import dev.sculptory.fabric.engine.impl.EngineRuntime;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import net.fabricmc.api.ModInitializer;
import org.slf4j.LoggerFactory;

/**
 * Makes sure the engine runs on the GameTest server. {@link EngineRuntime#install()} is idempotent, so this stays
 * harmless once the mod initializer installs it too.
 *
 * <p>It also moves the server's own undo history ({@link EditServiceHost#historyDir}) into a temporary folder deleted
 * when the JVM exits, so tests that edit through the server's service (mock players come and go with every run) never
 * leave history files in the persistent GameTest world.
 */
public final class EngineTestBootstrap implements ModInitializer {
    @Override
    public void onInitialize() {
        EngineRuntime.install();
        if (System.getProperty(EditServiceHost.HISTORY_DIR_PROPERTY) != null) return;
        try {
            Path temp = Files.createTempDirectory("sculptory-gametest-history");
            System.setProperty(EditServiceHost.HISTORY_DIR_PROPERTY, temp.toString());
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(temp), "Sculptory test cleanup"));
        } catch (IOException e) {
            LoggerFactory.getLogger("sculptory").warn("Could not make a temporary history folder for GameTests", e);
        }
    }

    /** Deletes a folder and everything in it (best effort). */
    static void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException e) {
            LoggerFactory.getLogger("sculptory").warn("Could not delete the test history folder {}", dir, e);
        }
    }
}
