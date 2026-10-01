package dev.sculptory.fabric.client.editor.wiki;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The wiki's pages, read from a {@link WikiSource} and parsed the first time anything asks (all at once: the list and
 * the search need every title), then kept. A page that can't be read is left out; nothing here throws.
 */
public final class WikiLibrary {
    private final WikiSource source;
    private Map<String, WikiPage> pages;
    private WikiContents contents;
    private List<WikiPage> ordered;

    public WikiLibrary(WikiSource source) {
        this.source = Objects.requireNonNull(source);
    }

    /** A library without pages. */
    public static WikiLibrary empty() {
        return new WikiLibrary(WikiSource.EMPTY);
    }

    private void load() {
        if (pages != null) {
            return;
        }
        Map<String, WikiPage> loaded = new HashMap<>();
        List<String> ids;
        try {
            ids = source.pageIds();
        } catch (RuntimeException e) {
            ids = List.of();
        }
        for (String id : ids) {
            if (id == null || !WikiLink.PAGE_ID.matcher(id).matches() || loaded.containsKey(id)) {
                continue;
            }
            Optional<String> text;
            try {
                text = source.read(id);
            } catch (RuntimeException e) {
                text = Optional.empty();
            }
            text.ifPresent(markdown -> loaded.put(id, WikiParser.parse(id, markdown)));
        }
        pages = loaded;
        contents = WikiContents.of(loaded);
        ordered = new ArrayList<>();
        for (String id : contents.pageIds()) {
            ordered.add(loaded.get(id));
        }
    }

    /** Page {@code id}, if the wiki has it. */
    public Optional<WikiPage> page(String id) {
        load();
        return Optional.ofNullable(id == null ? null : pages.get(id));
    }

    /** Every page, in the page list's order. */
    public List<WikiPage> pages() {
        load();
        return List.copyOf(ordered);
    }

    public boolean isEmpty() {
        load();
        return pages.isEmpty();
    }

    /** The page list's groups. */
    public WikiContents contents() {
        load();
        return contents;
    }

    /** What the search box finds for {@code query}. */
    public List<WikiSearch.Result> search(String query) {
        load();
        return WikiSearch.search(query, ordered);
    }
}
