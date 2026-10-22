package dev.sculptory.mc.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.SculptoryClientMod;
import dev.sculptory.mc.loader.DevClasses;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * The mod jar leaves out the dev-only client harnesses (the screenshot tour, the play check, the scripted demo, the
 * development-only /bsclient commands and the UI toolkit preview; {@code devHarnessPackages} in
 * modules/fabric/build.gradle), so no class that ships may name one, or it would fail to load from the jar. Only
 * {@link SculptoryClientMod} starts them, by class-file name and only when that class file is there. The same holds on
 * the server side for the playtest setup ({@code devServerPackages}), which only {@code SculptoryMod} starts; and the
 * remapped jar provably has none of these packages. Paths and packages come from the build
 * ({@code sculptory.clientClassDirs}, {@code sculptory.mainClassDirs}, {@code sculptory.jar},
 * {@code sculptory.devHarnessPackages}, {@code sculptory.devServerPackages}).
 */
class DevHarnessClassesTest {
    private static final String ENTRYPOINT = "dev/sculptory/fabric/client/SculptoryClientMod.class";
    /** The classes {@code SculptoryClientMod} looks for (through DevClasses), by their binary names. */
    private static final List<String> STARTED = List.of("dev.sculptory.mc.client.editor.tour.UiTour",
            "dev.sculptory.mc.client.editor.check.PlayCheck", "dev.sculptory.mc.client.editor.demo.PlayDemo",
            "dev.sculptory.fabric.client.editor.devtools.DevCommands");
    private static final String SERVER_ENTRYPOINT = "dev/sculptory/fabric/SculptoryMod.class";
    /** The class {@code SculptoryMod} looks for, by its binary name. */
    private static final String PLAYTEST_SETUP = "dev.sculptory.mc.playtest.PlaytestSetup";

    @Test
    void noShippedClientClassNamesADevHarnessExceptTheEntrypoint() throws IOException {
        String dirs = System.getProperty("sculptory.clientClassDirs");
        String packages = System.getProperty("sculptory.devHarnessPackages");
        assertNotNull(dirs, "the build did not pass the client class directories");
        assertNotNull(packages, "the build did not pass the dev harness packages");
        // "dev/sculptory/mc/client/editor/tour/**" -> "dev/sculptory/mc/client/editor/tour/"
        List<String> prefixes = Arrays.stream(packages.split(",")).map(p -> p.substring(0, p.length() - 2)).toList();
        assertEquals(5, prefixes.size(), "the dev harness packages: " + packages);
        int scanned = 0;
        int harnessClasses = 0;
        List<String> offenders = new ArrayList<>();
        List<String> found = new ArrayList<>();
        String entrypoint = null;
        for (String dir : dirs.split(File.pathSeparator)) {
            Path root = Path.of(dir);
            if (!Files.isDirectory(root)) continue;
            List<Path> classes;
            try (Stream<Path> walk = Files.walk(root)) {
                classes = walk.filter(p -> p.toString().endsWith(".class")).toList();
            }
            for (Path file : classes) {
                String name = root.relativize(file).toString().replace(File.separatorChar, '/');
                if (prefixes.stream().anyMatch(name::startsWith)) {
                    harnessClasses++;
                    found.add(name);
                    continue;
                }
                scanned++;
                // Class and descriptor names sit in the constant pool as (modified) UTF-8; these are ASCII.
                String bytes = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                if (name.equals(ENTRYPOINT)) {
                    entrypoint = bytes;
                    continue;
                }
                for (String prefix : prefixes) {
                    if (bytes.contains(prefix)) offenders.add(name + " references " + prefix);
                }
            }
        }
        assertTrue(scanned > 100, "only " + scanned + " client classes scanned from " + dirs);
        assertTrue(harnessClasses > 20, "only " + harnessClasses + " dev harness classes found in " + dirs);
        assertEquals(List.of(), offenders);
        assertNotNull(entrypoint, "SculptoryClientMod.class not found in " + dirs);
        for (String started : STARTED) {
            assertTrue(entrypoint.contains(started), "SculptoryClientMod looks for " + started + " by name");
            assertTrue(found.contains(started.replace('.', '/') + ".class"), started + " is a dev harness class");
        }
    }

    /**
     * The client entrypoint links without the harness classes, as from the jar, and starts a harness only when its class
     * file is there: loaded on its own, with the harness packages hidden, it verifies and finds no harness even with the
     * property set.
     */
    @Test
    void theEntrypointLinksAndStartsNoHarnessWithoutTheirClasses() throws Exception {
        String dirs = System.getProperty("sculptory.clientClassDirs");
        String packages = System.getProperty("sculptory.devHarnessPackages");
        assertNotNull(dirs, "the build did not pass the client class directories");
        assertNotNull(packages, "the build did not pass the dev harness packages");
        List<String> hidden = Arrays.stream(packages.split(","))
                .map(p -> p.substring(0, p.length() - 2).replace('/', '.')).toList();
        byte[] entrypoint = null;
        for (String dir : dirs.split(File.pathSeparator)) {
            Path file = Path.of(dir).resolve(ENTRYPOINT);
            if (Files.isRegularFile(file)) entrypoint = Files.readAllBytes(file);
        }
        assertNotNull(entrypoint, "SculptoryClientMod.class not found in " + dirs);
        String entrypointName = "dev.sculptory.fabric.client.SculptoryClientMod";
        byte[] bytes = entrypoint;
        ClassLoader parent = getClass().getClassLoader();
        ClassLoader withoutHarnesses = new ClassLoader(parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    if (hidden.stream().anyMatch(name::startsWith)) throw new ClassNotFoundException(name + " (hidden)");
                    if (!name.equals(entrypointName)) return super.loadClass(name, resolve);
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) loaded = defineClass(name, bytes, 0, bytes.length);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }

            @Override
            public URL getResource(String name) {
                String binary = name.replace('/', '.');
                return hidden.stream().anyMatch(binary::startsWith) ? null : super.getResource(name);
            }
        };
        // Initializing links and verifies the class: a harness class it needed to load would fail here.
        Class<?> isolated = Class.forName(entrypointName, true, withoutHarnesses);
        assertEquals(withoutHarnesses, isolated.getClassLoader());
        Method devHarness = isolated.getDeclaredMethod("devHarness", String.class, String.class);
        devHarness.setAccessible(true);
        String property = "sculptory.test.devHarness";
        String harness = STARTED.get(0);
        assertEquals(false, devHarness.invoke(null, property, harness), "no property, no harness");
        System.setProperty(property, "x");
        try {
            assertEquals(false, devHarness.invoke(null, property, harness), "the property without the class");
            // The ordinary entrypoint, whose loader sees the harnesses (a dev run).
            Method dev = SculptoryClientMod.class.getDeclaredMethod("devHarness", String.class,
                    String.class);
            dev.setAccessible(true);
            assertEquals(true, dev.invoke(null, property, harness), "the property and the class");
        } finally {
            System.clearProperty(property);
        }
    }

    /**
     * The playtest setup is compiled into the common output, and no class there but the server entrypoint names it (the
     * client classes neither: the first test's scan covers the client harnesses, this one the server package in both).
     */
    @Test
    void noShippedClassNamesThePlaytestSetupExceptTheServerEntrypoint() throws IOException {
        List<String> prefixes = packages("sculptory.devServerPackages");
        assertEquals(List.of("dev/sculptory/mc/playtest/"), prefixes);
        String dirs = property("sculptory.mainClassDirs") + File.pathSeparator + property("sculptory.clientClassDirs");
        int scanned = 0;
        List<String> found = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        String entrypoint = null;
        for (String dir : dirs.split(File.pathSeparator)) {
            Path root = Path.of(dir);
            if (!Files.isDirectory(root)) continue;
            for (Path file : classFiles(root)) {
                String name = root.relativize(file).toString().replace(File.separatorChar, '/');
                if (prefixes.stream().anyMatch(name::startsWith)) {
                    found.add(name);
                    continue;
                }
                scanned++;
                String bytes = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                if (name.equals(SERVER_ENTRYPOINT)) {
                    entrypoint = bytes;
                    continue;
                }
                for (String prefix : prefixes) {
                    if (bytes.contains(prefix)) offenders.add(name + " references " + prefix);
                }
            }
        }
        assertTrue(scanned > 300, "only " + scanned + " classes scanned from " + dirs);
        assertTrue(found.contains(PLAYTEST_SETUP.replace('.', '/') + ".class"), "PlaytestSetup not compiled: " + found);
        assertEquals(List.of(), offenders);
        assertNotNull(entrypoint, "SculptoryMod.class not found in " + dirs);
        assertTrue(entrypoint.contains(PLAYTEST_SETUP), "SculptoryMod looks for PlaytestSetup by name");
    }

    /** The jar the build publishes (remapped) holds none of the dev packages, and does hold both entrypoints. */
    @Test
    void theJarLeavesEveryDevPackageOut() throws IOException {
        List<String> prefixes = new ArrayList<>(packages("sculptory.devHarnessPackages"));
        prefixes.addAll(packages("sculptory.devServerPackages"));
        List<String> inJar = new ArrayList<>();
        try (ZipFile jar = new ZipFile(property("sculptory.jar"))) {
            assertNotNull(jar.getEntry(SERVER_ENTRYPOINT), "the jar has no SculptoryMod");
            assertNotNull(jar.getEntry(ENTRYPOINT), "the jar has no SculptoryClientMod");
            assertNotNull(jar.getEntry("dev/sculptory/mc/loader/DevClasses.class"), "the jar has no DevClasses");
            for (ZipEntry entry : jar.stream().toList()) {
                if (prefixes.stream().anyMatch(entry.getName()::startsWith)) inJar.add(entry.getName());
            }
        }
        assertEquals(List.of(), inJar);
    }

    /**
     * The server entrypoint links without the playtest setup, as from the jar: loaded on its own with the package
     * hidden, it verifies, and {@code DevClasses} does not find the setup there even with the property set.
     */
    @Test
    void theServerEntrypointLinksAndStartsNoSetupWithoutItsClasses() throws Exception {
        List<String> hidden = packages("sculptory.devServerPackages").stream().map(p -> p.replace('/', '.')).toList();
        byte[] entrypoint = null;
        for (String dir : property("sculptory.mainClassDirs").split(File.pathSeparator)) {
            Path file = Path.of(dir).resolve(SERVER_ENTRYPOINT);
            if (Files.isRegularFile(file)) entrypoint = Files.readAllBytes(file);
        }
        assertNotNull(entrypoint, "SculptoryMod.class not found");
        String entrypointName = "dev.sculptory.fabric.SculptoryMod";
        ClassLoader withoutSetup = hidingLoader(entrypointName, entrypoint, hidden);
        Class<?> isolated = Class.forName(entrypointName, true, withoutSetup);
        assertEquals(withoutSetup, isolated.getClassLoader());
        String property = "sculptory.test.devServer";
        assertEquals(false, DevClasses.wanted(withoutSetup, property, PLAYTEST_SETUP), "no property, no setup");
        System.setProperty(property, "true");
        try {
            assertEquals(false, DevClasses.wanted(withoutSetup, property, PLAYTEST_SETUP), "the property without the class");
            assertEquals(true, DevClasses.wanted(getClass().getClassLoader(), property, PLAYTEST_SETUP),
                    "the property and the class");
            System.setProperty(property, "false");
            assertEquals(false, DevClasses.wanted(getClass().getClassLoader(), property, PLAYTEST_SETUP),
                    "the property set to false");
        } finally {
            System.clearProperty(property);
        }
    }

    /** A loader that defines {@code name} from {@code bytes} itself and hides the classes under {@code hidden}. */
    private ClassLoader hidingLoader(String name, byte[] bytes, List<String> hidden) {
        return new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(className)) {
                    if (hidden.stream().anyMatch(className::startsWith)) {
                        throw new ClassNotFoundException(className + " (hidden)");
                    }
                    if (!className.equals(name)) return super.loadClass(className, resolve);
                    Class<?> loaded = findLoadedClass(className);
                    if (loaded == null) loaded = defineClass(className, bytes, 0, bytes.length);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }

            @Override
            public URL getResource(String resource) {
                String binary = resource.replace('/', '.');
                return hidden.stream().anyMatch(binary::startsWith) ? null : super.getResource(resource);
            }
        };
    }

    /** A build-passed glob list as internal-name prefixes ({@code dev/.../playtest/**} to {@code dev/.../playtest/}). */
    private static List<String> packages(String propertyName) {
        return Arrays.stream(property(propertyName).split(",")).map(p -> p.substring(0, p.length() - 2)).toList();
    }

    private static List<Path> classFiles(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".class")).toList();
        }
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        assertNotNull(value, "the build did not pass " + name);
        return value;
    }
}
