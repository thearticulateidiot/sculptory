package dev.sculptory.mc.loader;

import dev.sculptory.mc.Sculptory;

/**
 * Finds the dev-only classes that run from a dev checkout and that the mod jars leave out: the server-side playtest
 * setup and the client harnesses (the screenshot tour, the play check, the scripted demo, the development-only client
 * commands). Looks for the class file by name, so nothing is loaded when it is missing, and an entrypoint can start a
 * dev class behind this check without failing to link from the jar. Each entrypoint passes its own class loader, the
 * one that loaded the mod's classes.
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
        return availableOrLog(loader, property, className);
    }

    /**
     * Whether the dev-only harness {@code className} should start: {@code -D<property>} is set to any value (the
     * harnesses take an output directory there) and the class file is on {@code loader}'s classpath. With the property
     * but without the class (a jar), only logs.
     */
    public static boolean requested(ClassLoader loader, String property, String className) {
        if (System.getProperty(property) == null) return false;
        return availableOrLog(loader, property, className);
    }

    /** Whether the class file of {@code className} is on {@code loader}'s classpath. */
    public static boolean present(ClassLoader loader, String className) {
        return loader.getResource(className.replace('.', '/') + ".class") != null;
    }

    private static boolean availableOrLog(ClassLoader loader, String property, String className) {
        if (present(loader, className)) return true;
        Sculptory.LOG.warn("Sculptory: -D{} is set, but this jar has no dev classes (they run from a dev checkout only)",
                property);
        return false;
    }
}
