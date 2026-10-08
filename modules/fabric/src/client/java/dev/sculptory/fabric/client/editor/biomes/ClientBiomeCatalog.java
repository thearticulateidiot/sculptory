package dev.sculptory.fabric.client.editor.biomes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.biome.Biome;

/**
 * The biomes of the world the client is in (protocol 6): the registry the server sent, so data-pack and modded biomes
 * are there. A biome is named by its translation ({@code biome.<namespace>.<path>}) when the client has one, else by
 * its id; its colour is its grass colour at the origin (swamps and dark forests included), opaque. Rebuilt when the
 * registry changes (another world or server). Client thread only.
 */
public final class ClientBiomeCatalog implements BiomeCatalog {
    private Registry<Biome> registry;
    private List<Entry> entries = List.of();
    private Map<String, Entry> byId = Map.of();

    @Override
    public List<Entry> entries() {
        refresh();
        return entries;
    }

    @Override
    public String name(String id) {
        refresh();
        Entry entry = byId.get(id);
        return entry != null ? entry.name() : BiomeCatalog.pretty(id);
    }

    @Override
    public int color(String id) {
        refresh();
        Entry entry = byId.get(id);
        return entry != null ? entry.color() : FALLBACK.color(id);
    }

    private void refresh() {
        MinecraftClient client = MinecraftClient.getInstance();
        Registry<Biome> current = client == null || client.world == null ? null
                : client.world.getRegistryManager().getOptional(RegistryKeys.BIOME).orElse(null);
        if (current == registry) return;
        registry = current;
        if (current == null) {
            entries = List.of();
            byId = Map.of();
            return;
        }
        List<Entry> list = new ArrayList<>();
        Map<String, Entry> map = new HashMap<>();
        for (Map.Entry<net.minecraft.registry.RegistryKey<Biome>, Biome> biome : current.getEntrySet()) {
            Identifier id = biome.getKey().getValue();
            String key = Util.createTranslationKey("biome", id);
            String name = I18n.hasTranslation(key) ? I18n.translate(key) : BiomeCatalog.pretty(id.toString());
            Entry entry = new Entry(id.toString(), name, 0xFF000000 | biome.getValue().getGrassColorAt(0, 0));
            list.add(entry);
            map.put(entry.id(), entry);
        }
        list.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::id));
        entries = List.copyOf(list);
        byId = Map.copyOf(map);
    }
}
