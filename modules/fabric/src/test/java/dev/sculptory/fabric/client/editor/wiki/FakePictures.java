package dev.sculptory.fabric.client.editor.wiki;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.util.Identifier;

/** Pictures of given sizes; records what was loaded and freed. */
public final class FakePictures implements WikiPictures {
    private final Map<String, int[]> sizes = new HashMap<>();
    /** Loaded now (asked for and not freed since). */
    public final Set<String> loaded = new LinkedHashSet<>();
    public final List<String> freed = new ArrayList<>();
    public int loads;

    /** A picture at {@code path}, {@code width} x {@code height} pixels. */
    public FakePictures with(String path, int width, int height) {
        sizes.put(path, new int[] {width, height});
        return this;
    }

    public static Identifier texture(String path) {
        return Identifier.of("sculptory", "wiki_picture/" + path);
    }

    @Override
    public Optional<Picture> picture(String path) {
        int[] size = sizes.get(path);
        if (size == null) {
            return Optional.empty();
        }
        if (loaded.add(path)) {
            loads++;
        }
        return Optional.of(new Picture(texture(path), size[0], size[1]));
    }

    @Override
    public void keepOnly(Set<String> paths) {
        for (String path : List.copyOf(loaded)) {
            if (!paths.contains(path)) {
                loaded.remove(path);
                freed.add(path);
            }
        }
    }
}
