package dev.sculptory.mc.loader;

import java.nio.file.Path;

/** What the mod loader knows that the game does not: folders, the mod's own version, other mods. */
public interface LoaderInfo {
    /** The loader's config folder (Fabric: {@code <game>/config}). */
    Path configDir();

    /** The game folder (the one with {@code saves}, {@code mods}, ...). */
    Path gameDir();

    /** This build's version as the loader reads it from the mod's metadata, or {@code "unknown"}. */
    String modVersion();

    boolean isModLoaded(String modId);

    /** A development run (the IDE or Gradle run configurations), not an installed jar. */
    boolean isDevelopment();
}
