package dev.sculptory.fabric.client.editor.biomes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The biomes the editor offers (protocol 6, the Biome tool): every biome of the client's registry, the server's
 * data-pack and modded ones included, each with a name to show and a colour to stand for it (its grass colour, which is
 * how the ground looks there). The game's catalog is {@link ClientBiomeCatalog}; {@link #FALLBACK} serves headless code.
 */
public interface BiomeCatalog {
    /** One biome: its registry id, the name shown and its colour (opaque ARGB). */
    record Entry(String id, String name, int color) {}

    /** No registry: names made from the ids, every biome grey. */
    BiomeCatalog FALLBACK = new BiomeCatalog() {
        @Override
        public List<Entry> entries() {
            return List.of();
        }

        @Override
        public String name(String id) {
            return pretty(id);
        }

        @Override
        public int color(String id) {
            return 0xFF8A8A8A;
        }
    };

    /** Every biome, sorted by name. */
    List<Entry> entries();

    /** The name shown for biome {@code id} (its id made readable when the registry does not name it). */
    String name(String id);

    /** Biome {@code id}'s colour (opaque ARGB; grey when unknown). */
    int color(String id);

    /** "minecraft:cherry_grove" as "Cherry grove", for a biome without a translated name. */
    static String pretty(String id) {
        String path = id.substring(id.indexOf(':') + 1).replace('_', ' ').replace('/', ' ');
        return path.isEmpty() ? id : path.substring(0, 1).toUpperCase(Locale.ROOT) + path.substring(1);
    }

    /** Biomes whose name or id contains every word of {@code query}, ignoring case. */
    static List<Entry> filter(List<Entry> entries, String query) {
        String[] words = query.trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (words.length == 1 && words[0].isEmpty()) return entries;
        List<Entry> matches = new ArrayList<>();
        for (Entry entry : entries) {
            String name = entry.name().toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String word : words) {
                if (!name.contains(word) && !entry.id().contains(word)) {
                    all = false;
                    break;
                }
            }
            if (all) matches.add(entry);
        }
        return matches;
    }
}
