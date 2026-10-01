package dev.sculptory.fabric.client.editor.wiki;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The Wiki window's page list, grouped like the home page: the home page first, then one group per {@code ##} (or
 * {@code ###}) section of {@code home.md} holding the pages it links to, in the order it links to them (each page
 * once, where it is first linked). Pages the home page doesn't link go last in a group of their own, by title. Without
 * a home page every page is in that last group.
 */
public record WikiContents(List<Group> groups) {
    public enum Kind {
        /** Before the home page's first section: the home page and what it links there. No heading. */
        TOP,
        /** A section of the home page; its heading is the section's. */
        SECTION,
        /** The pages the home page doesn't link. */
        OTHERS
    }

    /** One group of the list: its heading (null for {@link Kind#TOP} and {@link Kind#OTHERS}) and page ids. */
    public record Group(Kind kind, String title, List<String> pageIds) {
        public Group {
            Objects.requireNonNull(kind);
            pageIds = List.copyOf(pageIds);
        }
    }

    public WikiContents {
        groups = List.copyOf(groups);
    }

    /** Every page id, in list order. */
    public List<String> pageIds() {
        List<String> ids = new ArrayList<>();
        for (Group group : groups) {
            ids.addAll(group.pageIds());
        }
        return ids;
    }

    /** The list for {@code pages} (by id). */
    public static WikiContents of(Map<String, WikiPage> pages) {
        List<Group> groups = new ArrayList<>();
        Set<String> listed = new LinkedHashSet<>();
        WikiPage home = pages.get(WikiPages.HOME);
        if (home != null) {
            listed.add(home.id());
            Kind kind = Kind.TOP;
            String title = null;
            List<String> ids = new ArrayList<>(List.of(home.id()));
            for (WikiBlock block : home.blocks()) {
                if (block instanceof WikiBlock.Heading heading) {
                    if (heading.level() == 1) {
                        continue;
                    }
                    add(groups, kind, title, ids);
                    kind = Kind.SECTION;
                    title = heading.text().strip();
                    ids = new ArrayList<>();
                    continue;
                }
                for (WikiLink link : links(block)) {
                    if (link.kind() == WikiLink.Kind.PAGE && pages.containsKey(link.pageId())
                            && listed.add(link.pageId())) {
                        ids.add(link.pageId());
                    }
                }
            }
            add(groups, kind, title, ids);
        }
        List<String> others = pages.keySet().stream()
                .filter(id -> !listed.contains(id))
                .sorted(Comparator.comparing((String id) -> pages.get(id).title().toLowerCase(Locale.ROOT))
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
        add(groups, Kind.OTHERS, null, others);
        return new WikiContents(groups);
    }

    private static void add(List<Group> groups, Kind kind, String title, List<String> ids) {
        if (!ids.isEmpty()) {
            groups.add(new Group(kind, title, ids));
        }
    }

    /** The links in a block, in reading order. */
    static List<WikiLink> links(WikiBlock block) {
        List<WikiLink> links = new ArrayList<>();
        collect(block, links);
        return links;
    }

    private static void collect(WikiBlock block, List<WikiLink> out) {
        switch (block) {
            case WikiBlock.Heading heading -> collect(heading.spans(), out);
            case WikiBlock.Paragraph paragraph -> collect(paragraph.spans(), out);
            case WikiBlock.ListBlock list -> collect(list, out);
            case WikiBlock.Table table -> {
                table.header().forEach(cell -> collect(cell, out));
                table.rows().forEach(row -> row.forEach(cell -> collect(cell, out)));
            }
            case WikiBlock.Callout callout -> callout.blocks().forEach(inner -> collect(inner, out));
            case WikiBlock.Rule rule -> { }
            case WikiBlock.Picture picture -> { }
        }
    }

    private static void collect(WikiBlock.ListBlock list, List<WikiLink> out) {
        for (WikiBlock.Item item : list.items()) {
            collect(item.spans(), out);
            if (item.children() != null) {
                collect(item.children(), out);
            }
        }
    }

    private static void collect(List<WikiBlock.Span> spans, List<WikiLink> out) {
        WikiLink previous = null;
        for (WikiBlock.Span span : spans) {
            if (span.link() != null && span.link() != previous) {
                out.add(span.link());
            }
            previous = span.link();
        }
    }
}
