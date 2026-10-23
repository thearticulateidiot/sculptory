package dev.sculptory.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * The self-test is dev only: the release jar ({@code :paper:jar}) has none of its classes and no class there names
 * them, while the dev jar ({@code :paper:devJar}) has every one of them plus the service entry the plugin finds them
 * by ({@link DevExtras}). Paths come from the build ({@code sculptory.jar}, {@code sculptory.devJar},
 * {@code sculptory.devClassDirs}).
 */
class PaperJarClassesTest {
    private static final String SERVICE = "META-INF/services/dev.sculptory.paper.DevExtras";
    private static final String DEV_PACKAGE = "dev/sculptory/paper/devtest/";

    @Test
    void theReleaseJarHasNoSelfTest() throws IOException {
        List<String> devClasses = devClasses();
        List<String> found = new ArrayList<>();
        List<String> naming = new ArrayList<>();
        try (ZipFile jar = new ZipFile(property("sculptory.jar"))) {
            assertNotNull(jar.getEntry("dev/sculptory/paper/SculptoryPlugin.class"), "no plugin class in the release jar");
            assertNotNull(jar.getEntry("plugin.yml"), "no plugin.yml in the release jar");
            if (jar.getEntry(SERVICE) != null) found.add(SERVICE);
            for (ZipEntry entry : jar.stream().toList()) {
                String name = entry.getName();
                if (name.startsWith(DEV_PACKAGE) || devClasses.contains(name)) found.add(name);
                if (!name.endsWith(".class")) continue;
                // Class names sit in the constant pool as (modified) UTF-8; these are ASCII.
                String bytes;
                try (InputStream in = jar.getInputStream(entry)) {
                    bytes = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                }
                if (bytes.contains(DEV_PACKAGE) || bytes.contains("dev.sculptory.paper.devtest")) naming.add(name);
            }
        }
        assertEquals(List.of(), found, "self-test entries in the release jar");
        assertEquals(List.of(), naming, "release classes that name the self-test package");
    }

    @Test
    void theDevJarHasTheReleaseJarAndTheSelfTest() throws IOException {
        List<String> devClasses = devClasses();
        // Otherwise the check below would pass for the wrong reason.
        assertTrue(devClasses.size() > 20, "only " + devClasses.size() + " compiled dev classes");
        assertTrue(devClasses.contains("dev/sculptory/paper/devtest/SelfTest.class"));
        assertTrue(devClasses.contains("dev/sculptory/paper/engine/SelfTestSteps.class"));
        assertTrue(devClasses.contains("dev/sculptory/paper/perm/WorldGuardTestRegions.class"));
        assertTrue(devClasses.contains("dev/sculptory/paper/devtest/RegionBench.class"));
        List<String> missing = new ArrayList<>();
        try (ZipFile release = new ZipFile(property("sculptory.jar"));
             ZipFile dev = new ZipFile(property("sculptory.devJar"))) {
            for (String name : devClasses) {
                if (dev.getEntry(name) == null) missing.add(name);
            }
            for (ZipEntry entry : release.stream().toList()) {
                if (!entry.isDirectory() && dev.getEntry(entry.getName()) == null) missing.add(entry.getName());
            }
            ZipEntry service = dev.getEntry(SERVICE);
            assertNotNull(service, "the dev jar has no " + SERVICE);
            try (InputStream in = dev.getInputStream(service)) {
                String implementation = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                assertNotNull(dev.getEntry(implementation.replace('.', '/') + ".class"), implementation + " is not in the dev jar");
            }
        }
        assertEquals(List.of(), missing, "entries missing from the dev jar");
    }

    /** The compiled dev classes, as jar entry names. */
    private static List<String> devClasses() throws IOException {
        List<String> names = new ArrayList<>();
        for (String dir : property("sculptory.devClassDirs").split(File.pathSeparator)) {
            Path root = Path.of(dir);
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".class"))
                        .forEach(p -> names.add(root.relativize(p).toString().replace(File.separatorChar, '/')));
            }
        }
        return names;
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        assertNotNull(value, "the build did not pass " + name);
        return value;
    }
}
