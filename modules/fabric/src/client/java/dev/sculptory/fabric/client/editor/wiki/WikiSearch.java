package dev.sculptory.fabric.client.editor.wiki;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The Wiki window's search: page titles and section headings ({@code ##}, {@code ###}) that contain every word typed,
 * ignoring case. Page titles come before headings; within each, a text that starts with the query comes first, then
 * one whose words start with the query's words, then any other; ties keep the page list's order.
 */
public final class WikiSearch {
    /** A match: a page ({@code heading} null, {@code anchor} null) or a section of one. */
    public record Result(String pageId, String anchor, String pageTitle, String heading) {
        public boolean isPage() {
            return heading == null;
        }
    }

    private WikiSearch() {}

    /** What {@code query} finds in {@code pages} (in list order); nothing for a blank query. */
    public static List<Result> search(String query, List<WikiPage> pages) {
        String normalized = normalize(query);
        if (normalized.isEmpty()) {
            return List.of();
        }
        String[] words = normalized.split(" ");
        record Scored(Result result, int score, int order) {}
        List<Scored> scored = new ArrayList<>();
        int order = 0;
        for (WikiPage page : pages) {
            int rank = rank(normalized, words, page.title());
            if (rank >= 0) {
                scored.add(new Scored(new Result(page.id(), null, page.title(), null), rank, order));
            }
            order++;
            for (WikiBlock.Heading heading : page.headings()) {
                if (heading.level() == 1) {
                    continue;
                }
                String text = heading.text().strip();
                int headingRank = rank(normalized, words, text);
                if (headingRank >= 0) {
                    scored.add(new Scored(new Result(page.id(), heading.anchor(), page.title(), text), 3 + headingRank,
                            order));
                }
                order++;
            }
        }
        scored.sort(Comparator.comparingInt(Scored::score).thenComparingInt(Scored::order));
        return scored.stream().map(Scored::result).toList();
    }

    /** 0 starts with the query, 1 its words start words of the text, 2 contains them all, -1 no match. */
    static int rank(String query, String[] words, String text) {
        String lower = normalize(text);
        for (String word : words) {
            if (!lower.contains(word)) {
                return -1;
            }
        }
        if (lower.startsWith(query)) {
            return 0;
        }
        String[] textWords = lower.split("[^a-z0-9]+");
        for (String word : words) {
            boolean starts = false;
            for (String textWord : textWords) {
                if (textWord.startsWith(word)) {
                    starts = true;
                    break;
                }
            }
            if (!starts) {
                return 2;
            }
        }
        return 1;
    }

    static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", " ");
    }
}
