package dev.sculptory.fabric.client.editor.wiki;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Where the wiki's pages come from: in game the mod's resources ({@code sculptory:wiki/<id>.md},
 * {@link McWikiResources}), in tests a map or the repository's {@code docs/wiki}.
 */
public interface WikiSource {
    /** A source without pages: every page is "not found". */
    WikiSource EMPTY = of(Map.of());

    /** The ids of every page there is (in any order). */
    List<String> pageIds();

    /** The Markdown of page {@code id}, or empty when there is no such page or it can't be read. */
    Optional<String> read(String id);

    /** A source holding the given pages (id to Markdown). */
    static WikiSource of(Map<String, String> pages) {
        Map<String, String> copy = new TreeMap<>(pages);
        return new WikiSource() {
            @Override
            public List<String> pageIds() {
                return List.copyOf(copy.keySet());
            }

            @Override
            public Optional<String> read(String id) {
                return Optional.ofNullable(copy.get(id));
            }
        };
    }
}
