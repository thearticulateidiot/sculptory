package dev.sculptory.fabric.client.editor.wiki;

import java.util.List;
import java.util.Objects;

/**
 * One block of a parsed wiki page: exactly the wiki's Markdown subset (headings, paragraphs,
 * lists with one level of nesting, simple tables, callouts, rules and pictures on their own line). Text inside blocks
 * is a list of {@link Span}s.
 */
public sealed interface WikiBlock {
    /**
     * A run of text in one style. {@code code} spans are drawn as a chip (keys, labels); {@code link} is null outside
     * links.
     */
    record Span(String text, boolean bold, boolean italic, boolean code, WikiLink link) {
        public Span {
            Objects.requireNonNull(text);
        }

        public static Span plain(String text) {
            return new Span(text, false, false, false, null);
        }
    }

    /** {@code #} (level 1) to {@code ###} (level 3), with its anchor ({@link WikiAnchors}, unique within the page). */
    record Heading(int level, List<Span> spans, String anchor) implements WikiBlock {
        public Heading {
            spans = List.copyOf(spans);
            Objects.requireNonNull(anchor);
        }

        public String text() {
            return WikiBlock.plainText(spans);
        }
    }

    record Paragraph(List<Span> spans) implements WikiBlock {
        public Paragraph {
            spans = List.copyOf(spans);
        }
    }

    /** A bullet ({@code -}) or numbered ({@code 1.}) list; numbered items count up from {@code start}. */
    record ListBlock(boolean ordered, int start, List<Item> items) implements WikiBlock {
        public ListBlock {
            items = List.copyOf(items);
        }
    }

    /** One list item; {@code children} is its nested list (one level deep), or null. */
    record Item(List<Span> spans, ListBlock children) {
        public Item {
            spans = List.copyOf(spans);
        }
    }

    /** A pipe table: a header row and body rows of cells. Rows may be shorter or longer than the header. */
    record Table(List<List<Span>> header, List<List<List<Span>>> rows) implements WikiBlock {
        public Table {
            header = header.stream().map(List::copyOf).toList();
            rows = rows.stream().map(row -> row.stream().map(List::copyOf).toList()).toList();
        }

        public int columns() {
            int columns = header.size();
            for (List<List<Span>> row : rows) {
                columns = Math.max(columns, row.size());
            }
            return columns;
        }
    }

    /** A {@code >} blockquote, shown as a callout box around its blocks. */
    record Callout(List<WikiBlock> blocks) implements WikiBlock {
        public Callout {
            blocks = List.copyOf(blocks);
        }
    }

    /** A {@code ---} horizontal rule. */
    record Rule() implements WikiBlock {}

    /** A picture alone on its line: {@code ![alt](images/x.png)}; {@code path} is relative to the wiki's folder. */
    record Picture(String alt, String path) implements WikiBlock {
        public Picture {
            Objects.requireNonNull(alt);
            Objects.requireNonNull(path);
        }
    }

    /** The text of {@code spans} without formatting. */
    static String plainText(List<Span> spans) {
        StringBuilder out = new StringBuilder();
        for (Span span : spans) {
            out.append(span.text());
        }
        return out.toString();
    }
}
