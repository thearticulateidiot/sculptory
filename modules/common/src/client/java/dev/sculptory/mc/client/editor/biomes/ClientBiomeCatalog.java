package dev.sculptory.mc.client.editor.biomes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;

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
        Minecraft client = Minecraft.getInstance();
        Registry<Biome> current = client == null || client.level == null ? null
                : client.level.registryAccess().registry(Registries.BIOME).orElse(null);
        if (current == registry) return;
        registry = current;
        if (current == null) {
            entries = List.of();
            byId = Map.of();
            return;
        }
        List<Entry> list = new ArrayList<>();
        Map<String, Entry> map = new HashMap<>();
        for (Map.Entry<net.minecraft.resources.ResourceKey<Biome>, Biome> biome : current.entrySet()) {
            ResourceLocation id = biome.getKey().location();
            String key = Util.makeDescriptionId("biome", id);
            String name = I18n.exists(key) ? I18n.get(key) : BiomeCatalog.pretty(id.toString());
            Entry entry = new Entry(id.toString(), name, 0xFF000000 | biome.getValue().getGrassColor(0, 0));
            list.add(entry);
            map.put(entry.id(), entry);
        }
        list.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::id));
        entries = List.copyOf(list);
        byId = Map.copyOf(map);
    }
}
