package dev.sculptory.fabric.client.editor.heightmap;

import dev.sculptory.fabric.client.editor.Translator;
import java.util.Optional;
import net.minecraft.util.Identifier;

/**
 * How the UI shows a height image: its thumbnail, when one is loaded. The game's is {@code HeightmapThumbnails}; without
 * the game ({@link #NONE}) pictures are shown by name only.
 */
@FunctionalInterface
public interface HeightmapView {
    /** No thumbnails. */
    HeightmapView NONE = ref -> Optional.empty();

    /** A registered texture of {@code width × height} pixels. */
    record Thumbnail(Identifier texture, int width, int height) {}

    /** The picture's thumbnail, if it has one (a missing picture has none). */
    Optional<Thumbnail> thumbnail(HeightmapRef ref);

    /** The picture's name as the player reads it: a built-in's translation, a file's name without ".png". */
    static String label(Translator translator, HeightmapRef ref) {
        return ref.builtIn() ? translator.translateOr("sculptory.heightmap." + ref.name(), ref.name()) : ref.fileLabel();
    }
}
