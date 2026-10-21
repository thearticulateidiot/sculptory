package dev.sculptory.mc;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Installs the Fabric server loader before every test class, as the mod initializer does in a game: code that asks the
 * loader for its folders ({@code FolderMigration} through {@code Loaders.info()}) then gets Fabric Loader's, which
 * fabric-loader-junit provides. {@code FabricServerLoader.install()} is idempotent. Registered for every test by
 * {@code META-INF/services/org.junit.jupiter.api.extension.Extension} with autodetection on
 * ({@code junit-platform.properties}).
 *
 * <p>JUnit loads this extension with the application class loader, while fabric-loader-junit loads the test classes
 * (and the mod's classes they use) with Knot's. The loader is installed in the test class's own class loader, by name,
 * so the {@code Loaders} it lands in is the one the tests read.
 */
public final class FabricLoadersExtension implements BeforeAllCallback {
    private static final String LOADER = "dev.sculptory.fabric.FabricServerLoader";

    @Override
    public void beforeAll(ExtensionContext context) throws ReflectiveOperationException {
        ClassLoader tests = context.getRequiredTestClass().getClassLoader();
        Class.forName(LOADER, true, tests).getMethod("install").invoke(null);
    }
}
