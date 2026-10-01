package dev.sculptory.fabric.client.editor.wiki;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.util.Identifier;

/**
 * The wiki's pictures as textures the UI can draw ({@code UiGraphics.texture}). In game they are PNGs from the mod's
 * resources ({@code sculptory:wiki/images/...}) loaded on first use into dynamic textures, and freed when the page
 * that shows them is left or the Wiki window closes ({@link McWikiResources}).
 */
public interface WikiPictures {
    /** No pictures: every picture is "not found". */
    WikiPictures NONE = new WikiPictures() {
        @Override
        public Optional<Picture> picture(String path) {
            return Optional.empty();
        }

        @Override
        public void keepOnly(Set<String> paths) {}
    };

    /** A loaded picture: the texture to draw and its size in pixels. */
    record Picture(Identifier texture, int width, int height) {
        public Picture {
            Objects.requireNonNull(texture);
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("A picture has a size: " + width + "x" + height);
            }
        }
    }

    /**
     * The picture at {@code path} (relative to the wiki's folder, {@code images/x.png}), loading it if needed; empty
     * when there is no such picture or it can't be read. Call on the render thread.
     */
    Optional<Picture> picture(String path);

    /** Frees every loaded picture but these. */
    void keepOnly(Set<String> paths);

    /** Frees every loaded picture. */
    default void releaseAll() {
        keepOnly(Set.of());
    }
}
