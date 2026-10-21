package dev.sculptory.mc.loader;

import dev.sculptory.mc.Sculptory;

/**
 * Finds the dev-only classes that run from a dev checkout and that the mod jars leave out (the server-side playtest
 * setup here; the client harnesses have the same check in each client entrypoint). Looks for the class file by name,
 * so nothing is loaded when it is missing, and an entrypoint can start a dev class behind this check without failing
 * to link from the jar.
 */
public final class DevClasses {
    private DevClasses() {
    }

    /**
     * Whether the dev-only class {@code className} should start: {@code -D<property>=true} is set and the class file
     * is on {@code loader}'s classpath (a dev run). With the property but without the class (a jar), only logs.
     */
    public static boolean wanted(ClassLoader loader, String property, String className) {
        if (!Boolean.parseBoolean(System.getProperty(property))) return false;
        if (present(loader, className)) return true;
        Sculptory.LOG.warn("Sculptory: -D{} is set, but this jar has no dev classes (they run from a dev checkout only)",
                property);
        return false;
    }

    /** Whether the class file of {@code className} is on {@code loader}'s classpath. */
    public static boolean present(ClassLoader loader, String className) {
        return loader.getResource(className.replace('.', '/') + ".class") != null;
    }
}
