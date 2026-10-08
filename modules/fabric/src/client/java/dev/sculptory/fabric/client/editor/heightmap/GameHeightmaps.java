package dev.sculptory.fabric.client.editor.heightmap;

import dev.sculptory.core.brush.HeightImage;
import dev.sculptory.fabric.config.FolderMigration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

/**
 * The game's height images: one {@link HeightmapLibrary} over {@code <game dir>/sculptory/heightmaps/} shared by the
 * Heightmap brush, the Generate tool and the picker, and the pictures' thumbnails as dynamic textures
 * ({@code sculptory:heightmap_thumb/<n>}, {@value #THUMB} pixels a side, the picture fitted and centred), made on first
 * use and made again when a picture changes. Client thread only.
 */
public final class GameHeightmaps implements HeightmapView {
    /** Thumbnail size in pixels. */
    public static final int THUMB = 64;

    private static GameHeightmaps instance;

    private final MinecraftClient client;
    private final HeightmapLibrary library;
    private final Map<String, Made> thumbnails = new HashMap<>();
    private int textures;

    private record Made(long version, Thumbnail thumbnail) {}

    private GameHeightmaps(MinecraftClient client, HeightmapLibrary library) {
        this.client = client;
        this.library = library;
    }

    /** The game's instance. */
    public static synchronized GameHeightmaps get() {
        if (instance == null) {
            instance = new GameHeightmaps(MinecraftClient.getInstance(), new HeightmapLibrary(
                    FolderMigration.gameDir().resolve("heightmaps"), HeightmapLibrary.BuiltIns.CLASS_PATH,
                    Util.getMainWorkerExecutor()));
        }
        return instance;
    }

    public HeightmapLibrary library() {
        return library;
    }

    @Override
    public Optional<Thumbnail> thumbnail(HeightmapRef ref) {
        // Without a running game (UI tests) there is nowhere to put a texture: names only.
        if (client == null) return Optional.empty();
        Optional<HeightmapLibrary.Entry> entry = library.entry(ref);
        String key = ref.text();
        Made made = thumbnails.get(key);
        if (entry.isEmpty()) {
            if (made != null) {
                client.getTextureManager().destroyTexture(made.thumbnail().texture());
                thumbnails.remove(key);
            }
            return Optional.empty();
        }
        if (made != null && made.version() == entry.get().version()) return Optional.of(made.thumbnail());
        if (made != null) client.getTextureManager().destroyTexture(made.thumbnail().texture());
        Thumbnail thumbnail = upload(entry.get().image());
        thumbnails.put(key, new Made(entry.get().version(), thumbnail));
        return Optional.of(thumbnail);
    }

    /** The picture fitted into a square texture, gray on black. */
    private Thumbnail upload(HeightImage image) {
        byte[] values = image.stamp(THUMB, 0);
        NativeImage pixels = new NativeImage(THUMB, THUMB, false);
        for (int y = 0; y < THUMB; y++) {
            for (int x = 0; x < THUMB; x++) {
                int v = values[y * THUMB + x] & 0xFF;
                pixels.setColor(x, y, 0xFF000000 | v << 16 | v << 8 | v);
            }
        }
        NativeImageBackedTexture texture = new NativeImageBackedTexture(pixels);
        Identifier id = Identifier.of("sculptory", "heightmap_thumb/" + textures++);
        client.getTextureManager().registerTexture(id, texture);
        texture.setFilter(true, false);
        return new Thumbnail(id, THUMB, THUMB);
    }
}
