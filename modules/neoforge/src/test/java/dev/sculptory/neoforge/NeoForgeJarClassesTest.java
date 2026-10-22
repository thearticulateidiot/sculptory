package dev.sculptory.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * The NeoForge mod has one source set, so the compiler does not keep server code off client classes (modules/common's
 * split compile does for the common code; this covers the NeoForge layer and the compiled result). A dedicated server
 * loads every class outside the client packages: none may name a client-only class, or it fails to load there. The one
 * allowed way in is {@link SculptoryNeoForge} calling {@code SculptoryNeoForgeClient.init} on a client. Also, as
 * modules/fabric's DevHarnessClassesTest: the jar leaves the dev-only packages out (the client harnesses and the
 * server-side playtest setup), and no shipped class but the entrypoint that starts one names it. Paths come from the
 * build ({@code sculptory.mainClassDirs}, {@code sculptory.mainResourceDir}, {@code sculptory.jar},
 * {@code sculptory.devHarnessPackages}, {@code sculptory.devServerPackages}).
 */
class NeoForgeJarClassesTest {
    /** Internal-name prefixes only a client has, or that hold client-only code of the mod. */
    private static final List<String> CLIENT_ONLY = List.of("net/minecraft/client/", "com/mojang/blaze3d/",
            "net/neoforged/neoforge/client/", "dev/sculptory/mc/client/", "dev/sculptory/neoforge/client/",
            "dev/sculptory/mc/loader/ClientLoader", "dev/sculptory/mc/loader/WorldFrame");
    /**
     * The mod's client code: loaded only through the client entrypoint, so it may name client classes. The two client
     * packages, and the client seam, which modules/common's client source set keeps in the shared loader package
     * ({@code ClientLoader}, its nested types, {@code ClientLoaders}, {@code WorldFrame}).
     */
    private static final List<String> CLIENT_PACKAGES = List.of("dev/sculptory/mc/client/",
            "dev/sculptory/neoforge/client/", "dev/sculptory/mc/loader/ClientLoader",
            "dev/sculptory/mc/loader/WorldFrame");
    /** The common entrypoint and the one client class it may name (called only when the dist is the client). */
    private static final String ENTRYPOINT = "dev/sculptory/neoforge/SculptoryNeoForge.class";
    private static final String CLIENT_ENTRYPOINT = "dev/sculptory/neoforge/client/SculptoryNeoForgeClient";

    @Test
    void classesADedicatedServerLoadsNameNoClientOnlyClass() throws IOException {
        int scanned = 0;
        boolean sawEntrypoint = false;
        List<String> offenders = new ArrayList<>();
        for (Path root : classDirs()) {
            for (Path file : classFiles(root)) {
                String name = root.relativize(file).toString().replace(File.separatorChar, '/');
                if (CLIENT_PACKAGES.stream().anyMatch(name::startsWith)) continue;
                scanned++;
                // Class and descriptor names sit in the constant pool as (modified) UTF-8; these are ASCII.
                String bytes = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                if (name.equals(ENTRYPOINT)) {
                    sawEntrypoint = true;
                    bytes = bytes.replace(CLIENT_ENTRYPOINT, "");
                }
                for (String prefix : CLIENT_ONLY) {
                    if (bytes.contains(prefix)) offenders.add(name + " references " + prefix);
                }
            }
        }
        assertTrue(scanned > 300, "only " + scanned + " server-side classes scanned");
        assertTrue(sawEntrypoint, "the common entrypoint was not among the scanned classes");
        assertEquals(List.of(), offenders);
    }

    @Test
    void mixinConfigsLoadedEverywhereHoldNoClientMixins() throws IOException {
        Path resources = Path.of(property("sculptory.mainResourceDir"));
        String toml = Files.readString(resources.resolve("META-INF/neoforge.mods.toml"));
        Matcher configs = Pattern.compile("(?m)^config\\s*=\\s*\"([^\"]+)\"").matcher(toml);
        int count = 0;
        while (configs.find()) {
            count++;
            String json = Files.readString(resources.resolve(configs.group(1)));
            Matcher pkg = Pattern.compile("\"package\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            assertTrue(pkg.find(), configs.group(1) + " has no package");
            boolean clientPackage = pkg.group(1).contains(".client.");
            boolean anywhere = Pattern.compile("\"mixins\"\\s*:").matcher(json).find();
            // A config of client mixins lists them under "client" only, which Mixin applies on a client only.
            if (clientPackage) assertFalse(anywhere, configs.group(1) + " applies client mixins on a server");
        }
        assertEquals(4, count, "mixin configs in neoforge.mods.toml");
    }

    @Test
    void theJarLeavesTheDevPackagesOut() throws IOException {
        List<String> harnesses = devPackages("sculptory.devHarnessPackages");
        List<String> server = devPackages("sculptory.devServerPackages");
        int harnessesInOutput = 0;
        int serverInOutput = 0;
        for (Path root : classDirs()) {
            for (Path file : classFiles(root)) {
                String name = root.relativize(file).toString().replace(File.separatorChar, '/');
                if (harnesses.stream().anyMatch(name::startsWith)) harnessesInOutput++;
                if (server.stream().anyMatch(name::startsWith)) serverInOutput++;
            }
        }
        // Otherwise the check below would pass for the wrong reason.
        assertTrue(harnessesInOutput > 0, "no dev harness classes in the compiled output");
        assertTrue(serverInOutput > 0, "no playtest setup classes in the compiled output");
        List<String> packages = new ArrayList<>(harnesses);
        packages.addAll(server);
        List<String> inJar = new ArrayList<>();
        try (ZipFile jar = new ZipFile(property("sculptory.jar"))) {
            assertNotNull(jar.getEntry(CLIENT_ENTRYPOINT + ".class"), "the jar has no client entrypoint");
            assertNotNull(jar.getEntry(ENTRYPOINT), "the jar has no common entrypoint");
            for (ZipEntry entry : jar.stream().toList()) {
                if (packages.stream().anyMatch(entry.getName()::startsWith)) inJar.add(entry.getName());
            }
        }
        assertEquals(List.of(), inJar);
    }

    /**
     * No shipped class names a dev package, except the entrypoint that starts it when its class is there: the client
     * entrypoint the client harnesses, the common entrypoint the playtest setup.
     */
    @Test
    void noShippedClassButItsEntrypointNamesADevPackage() throws IOException {
        List<String> harnesses = devPackages("sculptory.devHarnessPackages");
        List<String> server = devPackages("sculptory.devServerPackages");
        List<String> packages = new ArrayList<>(harnesses);
        packages.addAll(server);
        List<String> offenders = new ArrayList<>();
        String entrypoint = null;
        for (Path root : classDirs()) {
            for (Path file : classFiles(root)) {
                String name = root.relativize(file).toString().replace(File.separatorChar, '/');
                if (packages.stream().anyMatch(name::startsWith)) continue;
                String bytes = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                List<String> named = packages;
                if (name.equals(CLIENT_ENTRYPOINT + ".class")) named = server;
                if (name.equals(ENTRYPOINT)) {
                    entrypoint = bytes;
                    named = harnesses;
                }
                for (String pkg : named) {
                    if (bytes.contains(pkg)) offenders.add(name + " references " + pkg);
                }
            }
        }
        assertEquals(List.of(), offenders);
        assertNotNull(entrypoint, "the common entrypoint was not among the scanned classes");
        assertTrue(entrypoint.contains("dev.sculptory.mc.playtest.PlaytestSetup"),
                "SculptoryNeoForge looks for PlaytestSetup by name");
    }

    private static List<Path> classDirs() {
        return Arrays.stream(property("sculptory.mainClassDirs").split(File.pathSeparator))
                .map(Path::of).filter(Files::isDirectory).toList();
    }

    private static List<Path> classFiles(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".class")).toList();
        }
    }

    /** A build-passed list of dev packages as internal-name prefixes ({@code dev/.../tour/}). */
    private static List<String> devPackages(String propertyName) {
        return Arrays.stream(property(propertyName).split(","))
                .map(glob -> glob.endsWith("**") ? glob.substring(0, glob.length() - 2) : glob).toList();
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        assertNotNull(value, "the build did not pass " + name);
        return value;
    }
}
