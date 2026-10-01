package dev.sculptory.fabric.client.editor.wiki;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.sculptory.fabric.SculptoryMod;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * The wiki bundled in the mod: pages are {@code assets/sculptory/wiki/<id>.md} (the build copies {@code docs/wiki}
 * there), read through the resource manager; pictures ({@code wiki/images/*.png}) are loaded on first use into dynamic
 * textures ({@code sculptory:wiki_picture/...}, drawn with linear filtering since they are shown scaled down) and
 * freed by {@link #keepOnly}. Anything missing or unreadable is "not found", logged once, never thrown.
 */
public final class McWikiResources implements WikiSource, WikiPictures {
    private static final String NAMESPACE = "sculptory";
    private static final String FOLDER = "wiki";

    private final MinecraftClient client;
    /** Pictures asked for since they were last freed; empty for one that isn't there. */
    private final Map<String, Optional<Picture>> pictures = new HashMap<>();

    public McWikiResources(MinecraftClient client) {
        this.client = client;
    }

    @Override
    public List<String> pageIds() {
        List<String> ids = new ArrayList<>();
        Map<Identifier, Resource> found = client.getResourceManager().findResources(FOLDER,
                id -> id.getNamespace().equals(NAMESPACE) && id.getPath().endsWith(".md"));
        for (Identifier id : found.keySet()) {
            String name = id.getPath().substring(FOLDER.length() + 1, id.getPath().length() - ".md".length());
            if (!name.contains("/")) {
                ids.add(name);
            }
        }
        return ids;
    }

    @Override
    public Optional<String> read(String id) {
        if (!WikiLink.PAGE_ID.matcher(id).matches()) {
            return Optional.empty();
        }
        Optional<Resource> resource = client.getResourceManager().getResource(Identifier.of(NAMESPACE,
                FOLDER + "/" + id + ".md"));
        if (resource.isEmpty()) {
            return Optional.empty();
        }
        try (InputStream in = resource.get().getInputStream()) {
            return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            SculptoryMod.LOG.warn("Sculptory wiki: could not read page {}: {}", id, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public Optional<Picture> picture(String path) {
        Optional<Picture> known = pictures.get(path);
        if (known != null) {
            return known;
        }
        Optional<Picture> loaded = load(path);
        pictures.put(path, loaded);
        return loaded;
    }

    private Optional<Picture> load(String path) {
        String file = FOLDER + "/" + path;
        String texturePath = "wiki_picture/" + path;
        if (!path.endsWith(".png") || !Identifier.isPathValid(file) || path.contains("..")) {
            SculptoryMod.LOG.warn("Sculptory wiki: not a picture path: {}", path);
            return Optional.empty();
        }
        Optional<Resource> resource = client.getResourceManager().getResource(Identifier.of(NAMESPACE, file));
        if (resource.isEmpty()) {
            SculptoryMod.LOG.warn("Sculptory wiki: picture not found: {}", path);
            return Optional.empty();
        }
        try (InputStream in = resource.get().getInputStream()) {
            NativeImage image = NativeImage.read(in);
            int width = image.getWidth();
            int height = image.getHeight();
            NativeImageBackedTexture texture = new NativeImageBackedTexture(image);
            Identifier id = Identifier.of(NAMESPACE, texturePath);
            client.getTextureManager().registerTexture(id, texture);
            // Shown scaled down to the page's width: smooth rather than nearest pixels, and no bleed from the far edge.
            texture.setFilter(true, false);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            return Optional.of(new Picture(id, width, height));
        } catch (IOException | RuntimeException e) {
            SculptoryMod.LOG.warn("Sculptory wiki: could not load picture {}: {}", path, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public void keepOnly(Set<String> paths) {
        Iterator<Map.Entry<String, Optional<Picture>>> entries = pictures.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, Optional<Picture>> entry = entries.next();
            if (!paths.contains(entry.getKey())) {
                entry.getValue().ifPresent(picture -> client.getTextureManager().destroyTexture(picture.texture()));
                entries.remove();
            }
        }
    }
}
