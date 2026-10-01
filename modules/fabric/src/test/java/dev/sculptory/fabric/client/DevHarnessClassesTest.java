package dev.sculptory.fabric.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.api.Test;

/**
 * The mod jar leaves out the dev-only client harnesses (the screenshot tour, the play check, the scripted demo and the
 * UI toolkit preview; {@code devHarnessPackages} in modules/fabric/build.gradle), so no class that ships may name one,
 * or it would fail to load from the jar. Only {@link SculptoryClientMod} starts them, by class-file name and only when
 * that class file is there. Paths and packages come from the build ({@code sculptory.clientClassDirs},
 * {@code sculptory.devHarnessPackages}).
 */
class DevHarnessClassesTest {
    private static final String ENTRYPOINT = "dev/sculptory/fabric/client/SculptoryClientMod.class";
    /** The classes {@code SculptoryClientMod.devHarness} looks for, by their binary names. */
    private static final List<String> STARTED = List.of("dev.sculptory.fabric.client.editor.tour.UiTour",
            "dev.sculptory.fabric.client.editor.check.PlayCheck", "dev.sculptory.fabric.client.editor.demo.PlayDemo");

    @Test
    void noShippedClientClassNamesADevHarnessExceptTheEntrypoint() throws IOException {
        String dirs = System.getProperty("sculptory.clientClassDirs");
        String packages = System.getProperty("sculptory.devHarnessPackages");
        assertNotNull(dirs, "the build did not pass the client class directories");
        assertNotNull(packages, "the build did not pass the dev harness packages");
        // "dev/sculptory/fabric/client/editor/tour/**" -> "dev/sculptory/fabric/client/editor/tour/"
        List<String> prefixes = Arrays.stream(packages.split(",")).map(p -> p.substring(0, p.length() - 2)).toList();
        assertEquals(4, prefixes.size(), "the dev harness packages: " + packages);
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
}
