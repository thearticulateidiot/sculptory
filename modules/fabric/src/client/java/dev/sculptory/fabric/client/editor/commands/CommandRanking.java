package dev.sculptory.fabric.client.editor.commands;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Orders the command search's results. Matching ignores case. A name that starts with the query ranks first ("hol"
 * finds Hollow, "undo a" finds Undo anyway); then names where each word of the query starts a word of the name, in
 * order ("sym cen" finds Set symmetry centre, "big" finds Preset: Big hill); then names that contain the query's letters
 * in order ("flft" finds Flip left–right). Within a rank, recently run entries come first (most recent first), then
 * the given (menu) order. With an empty query: the recently run entries, then everything in the given order.
 */
public final class CommandRanking {
    /** How well a name matches a query; lower is better. */
    public enum Match { PREFIX, WORD_START, SUBSEQUENCE, NONE }

    private CommandRanking() {}

    /**
     * The items matching {@code query}, best first.
     *
     * @param name what the query matches
     * @param id the ids {@code recent} lists
     * @param recent ids run recently, newest first
     */
    public static <T> List<T> rank(String query, List<T> items, Function<T, String> name, Function<T, String> id,
            List<String> recent) {
        String normalized = normalize(query);
        record Scored<T>(T item, Match match, int recency, int order) {}
        List<Scored<T>> scored = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            T item = items.get(i);
            Match match = match(normalized, name.apply(item));
            if (match == Match.NONE) {
                continue;
            }
            int recency = recent.indexOf(id.apply(item));
            scored.add(new Scored<>(item, match, recency < 0 ? Integer.MAX_VALUE : recency, i));
        }
        scored.sort(Comparator.<Scored<T>, Match>comparing(Scored::match)
                .thenComparingInt(Scored::recency)
                .thenComparingInt(Scored::order));
        return scored.stream().map(Scored::item).toList();
    }

    /** How {@code name} matches {@code query} (an empty query matches everything as a prefix). */
    public static Match match(String query, String name) {
        String q = normalize(query);
        if (q.isEmpty()) {
            return Match.PREFIX;
        }
        String n = name.toLowerCase(Locale.ROOT);
        if (n.startsWith(q)) {
            return Match.PREFIX;
        }
        if (wordStarts(q, n)) {
            return Match.WORD_START;
        }
        if (subsequence(q.replace(" ", ""), n)) {
            return Match.SUBSEQUENCE;
        }
        return Match.NONE;
    }

    private static String normalize(String query) {
        return query.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Each query word starts a word of the name, in order. */
    private static boolean wordStarts(String query, String name) {
        String[] words = name.split("[^\\p{L}\\p{N}]+");
        int next = 0;
        for (String token : query.split(" ")) {
            String word = token.replaceAll("[^\\p{L}\\p{N}]", "");
            if (word.isEmpty()) {
                continue;
            }
            while (next < words.length && !words[next].startsWith(word)) {
                next++;
            }
            if (next == words.length) {
                return false;
            }
            next++;
        }
        return true;
    }

    private static boolean subsequence(String query, String name) {
        int at = 0;
        for (int i = 0; i < query.length(); i++) {
            at = name.indexOf(query.charAt(i), at);
            if (at < 0) {
                return false;
            }
            at++;
        }
        return true;
    }
}
