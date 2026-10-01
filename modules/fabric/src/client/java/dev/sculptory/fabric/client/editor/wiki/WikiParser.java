package dev.sculptory.fabric.client.editor.wiki;

import dev.sculptory.fabric.client.editor.wiki.WikiBlock.Callout;
import dev.sculptory.fabric.client.editor.wiki.WikiBlock.Heading;
import dev.sculptory.fabric.client.editor.wiki.WikiBlock.Item;
import dev.sculptory.fabric.client.editor.wiki.WikiBlock.ListBlock;
import dev.sculptory.fabric.client.editor.wiki.WikiBlock.Paragraph;
import dev.sculptory.fabric.client.editor.wiki.WikiBlock.Picture;
import dev.sculptory.fabric.client.editor.wiki.WikiBlock.Rule;
import dev.sculptory.fabric.client.editor.wiki.WikiBlock.Span;
import dev.sculptory.fabric.client.editor.wiki.WikiBlock.Table;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a wiki page written in the wiki's Markdown subset, and nothing else: {@code #},
 * {@code ##} and {@code ###} headings; paragraphs; {@code -} and {@code 1.} lists with one level of nesting (two-space
 * indent); {@code **bold**}, {@code *italic*}, {@code `code`}; links to pages ({@code other.md},
 * {@code other.md#anchor}), sections ({@code #anchor}) and websites; a picture alone on its line; simple pipe tables;
 * {@code >} callouts; {@code ---} rules.
 *
 * <p>It never throws. Whatever is outside the subset (HTML, code blocks, deeper nesting, {@code _italic_}, footnotes,
 * inline pictures, alignment markers...) or would look different on GitHub (a {@code ---} right under a line of text,
 * which GitHub makes a heading) is shown as well as possible and reported as a {@link WikiProblem}.
 */
public final class WikiParser {
    private record Line(int number, String text) {}

    private static final Pattern HEADING = Pattern.compile("(#{1,6})(?:\\s+(.*))?");
    private static final Pattern RULE = Pattern.compile("-{3,}");
    private static final Pattern OTHER_RULE = Pattern.compile("\\*{3,}|_{3,}|(?:- ){2,}-|(?:\\* ){2,}\\*");
    private static final Pattern PICTURE = Pattern.compile("!\\[([^\\]]*)\\]\\(([^()\\s]*)\\)");
    private static final Pattern ITEM = Pattern.compile("( *)([-*+]|\\d{1,9}[.)])(?: +(.*))?");
    private static final Pattern SEPARATOR = Pattern.compile("\\|?\\s*:?-+:?\\s*(?:\\|\\s*:?-+:?\\s*)*\\|?");
    private static final Pattern REFERENCE_DEFINITION = Pattern.compile("\\[[^\\]]+\\]:\\s*\\S.*");
    private static final Pattern FENCE = Pattern.compile("(```|~~~).*");
    private static final Pattern UNDERSCORE_EMPHASIS =
            Pattern.compile("(__?)(?=[^_\\s])(.+?)(?<=[^_\\s])\\1(?![A-Za-z0-9_])");
    private static final Pattern PICTURE_PATH = Pattern.compile("images/[a-z0-9][a-z0-9_-]*\\.png");
    private static final String PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";

    private final String id;
    private final List<WikiProblem> problems = new ArrayList<>();
    private final List<WikiPage.LinkUse> links = new ArrayList<>();
    private final List<WikiPage.PictureUse> pictures = new ArrayList<>();
    private final List<Heading> headings = new ArrayList<>();
    private final Set<String> anchors = new HashSet<>();

    private WikiParser(String id) {
        this.id = id;
    }

    /** Parses page {@code id}; {@code markdown} may be anything (null reads as empty). */
    public static WikiPage parse(String id, String markdown) {
        String pageId = id == null ? "" : id;
        try {
            return new WikiParser(pageId).run(markdown == null ? "" : markdown);
        } catch (RuntimeException e) {
            // A bug here must not take the editor down: show the text as it is.
            String text = markdown == null ? "" : markdown;
            return new WikiPage(pageId, pageId, List.of(new Paragraph(List.of(Span.plain(text)))), List.of(), List.of(),
                    List.of(), List.of(new WikiProblem(1, "the reader could not read this page: " + e)));
        }
    }

    private WikiPage run(String markdown) {
        String text = markdown.startsWith("﻿") ? markdown.substring(1) : markdown;
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] raw = text.split("\n", -1);
        List<Line> lines = new ArrayList<>(raw.length);
        for (int i = 0; i < raw.length; i++) {
            String line = raw[i];
            if (line.indexOf('\t') >= 0) {
                problem(i + 1, "a tab: indent with spaces");
                line = line.replace("\t", "  ");
            }
            if (line.indexOf('§') >= 0) {
                // Minecraft's formatting character: the reader would draw it and the next one as a colour change.
                problem(i + 1, "the character § is not allowed");
                line = line.replaceAll("§.?", "");
            }
            lines.add(new Line(i + 1, line.stripTrailing()));
        }
        List<WikiBlock> blocks = blocks(lines, false);
        String title = id;
        if (!blocks.isEmpty() && blocks.get(0) instanceof Heading heading && heading.level() == 1) {
            title = heading.text().strip();
        } else {
            problem(firstTextLine(lines), "a page starts with its # title");
            for (Heading heading : headings) {
                if (heading.level() == 1) {
                    title = heading.text().strip();
                    break;
                }
            }
        }
        if (title.isEmpty()) {
            title = id;
        }
        return new WikiPage(id, title, blocks, headings, links, pictures, problems);
    }

    private static int firstTextLine(List<Line> lines) {
        for (Line line : lines) {
            if (!line.text().isBlank()) {
                return line.number();
            }
        }
        return 1;
    }

    private void problem(int line, String message) {
        problems.add(new WikiProblem(line, message));
    }

    // ---- Blocks ----

    private List<WikiBlock> blocks(List<Line> lines, boolean inCallout) {
        List<WikiBlock> blocks = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            Line line = lines.get(i);
            String text = line.text();
            if (text.isBlank()) {
                i++;
                continue;
            }
            String stripped = text.strip();
            if (indent(text) >= 4) {
                problem(line.number(), "a line indented by four or more spaces is a code block on GitHub");
            }
            Matcher heading = HEADING.matcher(stripped);
            if (heading.matches()) {
                int level = heading.group(1).length();
                String content = heading.group(2) == null ? "" : heading.group(2);
                if (level > 3) {
                    problem(line.number(), "only #, ## and ### headings");
                }
                if (inCallout) {
                    problem(line.number(), "a heading inside a > callout");
                    blocks.add(new Paragraph(bold(inline(content, line.number()))));
                } else {
                    blocks.add(heading(Math.min(level, 3), content, line.number()));
                }
                i++;
                continue;
            }
            if (RULE.matcher(stripped).matches() || OTHER_RULE.matcher(stripped).matches()) {
                if (!RULE.matcher(stripped).matches()) {
                    problem(line.number(), "a rule is written ---");
                }
                blocks.add(new Rule());
                i++;
                continue;
            }
            Matcher picture = PICTURE.matcher(stripped);
            if (picture.matches()) {
                blocks.add(picture(picture.group(1), picture.group(2), line.number()));
                i++;
                continue;
            }
            int next;
            if (stripped.startsWith(">") && indent(text) < 4) {
                next = callout(lines, i, blocks, inCallout);
            } else if (startsTable(lines, i)) {
                if (inCallout) {
                    problem(line.number(), "a table inside a > callout");
                }
                next = table(lines, i, blocks);
            } else if (ITEM.matcher(text).matches() && indent(text) < 4) {
                next = list(lines, i, blocks);
            } else {
                next = paragraph(lines, i, blocks);
            }
            // Every block takes at least its first line.
            i = Math.max(next, i + 1);
        }
        return blocks;
    }

    private static int indent(String text) {
        int indent = 0;
        while (indent < text.length() && text.charAt(indent) == ' ') {
            indent++;
        }
        return indent;
    }

    private Heading heading(int level, String content, int line) {
        List<Span> spans = inline(content, line);
        String text = WikiBlock.plainText(spans).strip();
        if (text.isEmpty()) {
            problem(line, "an empty heading");
        }
        String anchor = WikiAnchors.anchor(text);
        if (anchor.isEmpty()) {
            anchor = "section";
        }
        String unique = anchor;
        for (int n = 1; !anchors.add(unique); n++) {
            unique = anchor + "-" + n;
        }
        Heading heading = new Heading(level, spans, unique);
        headings.add(heading);
        return heading;
    }

    private static List<Span> bold(List<Span> spans) {
        return spans.stream()
                .map(span -> new Span(span.text(), true, span.italic(), span.code(), span.link()))
                .toList();
    }

    private Picture picture(String alt, String path, int line) {
        if (!PICTURE_PATH.matcher(path).matches()) {
            problem(line, "a picture is a PNG in images/ with a lower-case name: " + path);
        }
        pictures.add(new WikiPage.PictureUse(line, path));
        return new Picture(alt.strip(), path);
    }

    /** A {@code >} callout from line {@code start}; returns the line after it. */
    private int callout(List<Line> lines, int start, List<WikiBlock> blocks, boolean inCallout) {
        List<Line> inner = new ArrayList<>();
        int i = start;
        while (i < lines.size() && lines.get(i).text().strip().startsWith(">")
                && (i == start || indent(lines.get(i).text()) < 4)) {
            Line line = lines.get(i);
            String rest = line.text().strip().substring(1);
            if (rest.startsWith(" ")) {
                rest = rest.substring(1);
            }
            if (rest.strip().startsWith(">") || inCallout) {
                problem(line.number(), "a callout inside a callout");
                while (rest.strip().startsWith(">")) {
                    rest = rest.strip().substring(1);
                }
            }
            inner.add(new Line(line.number(), rest));
            i++;
        }
        if (i < lines.size() && !lines.get(i).text().isBlank()) {
            problem(lines.get(i).number(),
                    "a line right after a > callout joins it on GitHub: start it with > too, or leave a blank line");
        }
        blocks.add(new Callout(blocks(inner, true)));
        return i;
    }

    // ---- Tables ----

    private static boolean startsTable(List<Line> lines, int i) {
        if (i + 1 >= lines.size() || !lines.get(i).text().contains("|")) {
            return false;
        }
        String separator = lines.get(i + 1).text().strip();
        return separator.contains("|") && separator.contains("-") && SEPARATOR.matcher(separator).matches();
    }

    private int table(List<Line> lines, int start, List<WikiBlock> blocks) {
        Line headerLine = lines.get(start);
        Line separatorLine = lines.get(start + 1);
        List<String> header = cells(headerLine);
        List<String> separator = cells(separatorLine);
        if (separatorLine.text().contains(":")) {
            problem(separatorLine.number(), "alignment markers (:) in a table");
        }
        if (separator.size() != header.size()) {
            problem(separatorLine.number(), "the --- row has " + separator.size() + " cells and the header "
                    + header.size() + ": GitHub shows no table");
        }
        List<List<Span>> headerSpans = new ArrayList<>();
        for (String cell : header) {
            headerSpans.add(inline(cell, headerLine.number()));
        }
        List<List<List<Span>>> rows = new ArrayList<>();
        int i = start + 2;
        while (i < lines.size() && !lines.get(i).text().isBlank() && lines.get(i).text().contains("|")) {
            Line line = lines.get(i);
            List<String> cells = cells(line);
            if (cells.size() != header.size()) {
                problem(line.number(), "this row has " + cells.size() + " cells and the header " + header.size()
                        + " (a | inside a cell?)");
            }
            List<List<Span>> row = new ArrayList<>();
            for (String cell : cells) {
                row.add(inline(cell, line.number()));
            }
            rows.add(row);
            i++;
        }
        if (i < lines.size() && !lines.get(i).text().isBlank()) {
            problem(lines.get(i).number(), "a line right after a table is one more row on GitHub: leave a blank line");
        }
        blocks.add(new Table(headerSpans, rows));
        return i;
    }

    /** The cells of a table row: split at every {@code |}, the outer ones dropped. */
    private List<String> cells(Line line) {
        String text = line.text().strip();
        if (text.contains("\\|")) {
            problem(line.number(), "a | inside a table cell");
        }
        if (text.startsWith("|")) {
            text = text.substring(1);
        }
        if (text.endsWith("|")) {
            text = text.substring(0, text.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : text.split("\\|", -1)) {
            cells.add(cell.strip());
        }
        return cells;
    }

    // ---- Lists ----

    /** A list being read: its items' first lines and texts, and each item's nested list. */
    private static final class ListBuilder {
        final boolean ordered;
        final int start;
        final List<ItemBuilder> items = new ArrayList<>();

        ListBuilder(boolean ordered, int start) {
            this.ordered = ordered;
            this.start = start;
        }
    }

    private static final class ItemBuilder {
        final int line;
        final StringBuilder text;
        ListBuilder children;

        ItemBuilder(int line, String text) {
            this.line = line;
            this.text = new StringBuilder(text);
        }
    }

    private static boolean ordered(String marker) {
        return Character.isDigit(marker.charAt(0));
    }

    private static int number(String marker) {
        try {
            return Integer.parseInt(marker.substring(0, marker.length() - 1));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private void checkMarker(String marker, int line) {
        if (marker.equals("*") || marker.equals("+")) {
            problem(line, "a bullet is written -");
        }
        if (marker.endsWith(")")) {
            problem(line, "a numbered item is written 1.");
        }
    }

    /** A list from line {@code start} (an item at the left edge); returns the line after it. */
    private int list(List<Line> lines, int start, List<WikiBlock> blocks) {
        Matcher first = ITEM.matcher(lines.get(start).text());
        first.matches();
        String firstMarker = first.group(2);
        ListBuilder list = new ListBuilder(ordered(firstMarker), ordered(firstMarker) ? number(firstMarker) : 1);
        // Items at this indent (normally 0) are the list's own; two or more spaces further in, nested ones.
        int base = indent(lines.get(start).text());
        if (base >= 2) {
            problem(lines.get(start).number(), "a list starts at the left edge");
        }
        ItemBuilder last = null;
        ItemBuilder lastTop = null;
        int nestedIndent = 2;
        boolean blank = false;
        int i = start;
        while (i < lines.size()) {
            Line line = lines.get(i);
            String text = line.text();
            if (text.isBlank()) {
                int next = i;
                while (next < lines.size() && lines.get(next).text().isBlank()) {
                    next++;
                }
                if (next < lines.size() && continuesList(lines.get(next).text(), base, list.ordered)) {
                    blank = true;
                    i = next;
                    continue;
                }
                break;
            }
            int indent = indent(text) - base;
            Matcher item = ITEM.matcher(text);
            if (item.matches() && indent < 2) {
                String marker = item.group(2);
                if (ordered(marker) != list.ordered) {
                    break;
                }
                checkMarker(marker, line.number());
                lastTop = new ItemBuilder(line.number(), item.group(3) == null ? "" : item.group(3).strip());
                list.items.add(lastTop);
                last = lastTop;
                blank = false;
                i++;
                continue;
            }
            if (item.matches() && lastTop != null) {
                String marker = item.group(2);
                checkMarker(marker, line.number());
                if (lastTop.children == null) {
                    lastTop.children = new ListBuilder(ordered(marker), ordered(marker) ? number(marker) : 1);
                    nestedIndent = indent;
                } else if (indent >= nestedIndent + 2) {
                    problem(line.number(), "only one level of nesting (two spaces)");
                }
                if (lastTop.children.ordered != ordered(marker)) {
                    problem(line.number(), "bullets and numbers mixed in one nested list");
                }
                last = new ItemBuilder(line.number(), item.group(3) == null ? "" : item.group(3).strip());
                lastTop.children.items.add(last);
                blank = false;
                i++;
                continue;
            }
            if (blank) {
                if (indent < 2) {
                    break;
                }
                problem(line.number(), "a second paragraph inside a list item");
            } else if (startsBlock(lines, i)) {
                break;
            }
            // A line that goes on with the item above (GitHub joins it too).
            if (!last.text.isEmpty()) {
                last.text.append(' ');
            }
            last.text.append(text.strip());
            blank = false;
            i++;
        }
        blocks.add(build(list));
        return i;
    }

    /** After a blank line: the list goes on with an item of the same kind, a nested item or an indented line. */
    private static boolean continuesList(String text, int base, boolean ordered) {
        Matcher item = ITEM.matcher(text);
        int indent = indent(text) - base;
        if (item.matches() && indent < 2) {
            return indent >= 0 && ordered(item.group(2)) == ordered;
        }
        return indent >= 2 && indent < 4 || item.matches() && indent >= 2 && indent < 6;
    }

    private ListBlock build(ListBuilder list) {
        List<Item> items = new ArrayList<>();
        for (ItemBuilder item : list.items) {
            ListBlock children = item.children == null ? null : build(item.children);
            items.add(new Item(inline(item.text.toString(), item.line), children));
        }
        return new ListBlock(list.ordered, list.start, items);
    }

    // ---- Paragraphs ----

    /** Whether line {@code i} starts a block that ends a paragraph or list item before it. */
    private static boolean startsBlock(List<Line> lines, int i) {
        String text = lines.get(i).text();
        String stripped = text.strip();
        return HEADING.matcher(stripped).matches() || RULE.matcher(stripped).matches()
                || OTHER_RULE.matcher(stripped).matches() || PICTURE.matcher(stripped).matches()
                || stripped.startsWith(">") || startsTable(lines, i)
                || ITEM.matcher(text).matches() && indent(text) < 4;
    }

    private int paragraph(List<Line> lines, int start, List<WikiBlock> blocks) {
        StringBuilder text = new StringBuilder();
        List<Integer> starts = new ArrayList<>();
        List<Integer> numbers = new ArrayList<>();
        int i = start;
        while (i < lines.size() && !lines.get(i).text().isBlank() && (i == start || !startsBlock(lines, i))) {
            Line line = lines.get(i);
            String stripped = line.text().strip();
            if (FENCE.matcher(stripped).matches()) {
                problem(line.number(), "code blocks (``` or ~~~) are not supported");
            }
            if (REFERENCE_DEFINITION.matcher(stripped).matches()) {
                problem(line.number(), "reference links ([name]: address) are not supported");
            }
            if (stripped.matches("={2,}")) {
                problem(line.number(), "=== under a line makes it a heading on GitHub: use #");
                i++;
                continue;
            }
            if (!text.isEmpty()) {
                text.append(' ');
            }
            starts.add(text.length());
            numbers.add(line.number());
            text.append(stripped);
            i++;
            if (i < lines.size() && lines.get(i).text().strip().matches("-{2,}")) {
                problem(lines.get(i).number(), "--- right under a line of text makes that text a heading on GitHub: "
                        + "leave a blank line above it");
            }
        }
        Origin origin = starts.isEmpty() ? Origin.of(lines.get(start).number())
                : new Origin(starts.stream().mapToInt(Integer::intValue).toArray(),
                        numbers.stream().mapToInt(Integer::intValue).toArray());
        blocks.add(new Paragraph(inline(text.toString(), origin)));
        return i;
    }

    // ---- Inline text ----

    /** Which line of the file each part of a text joined from several lines (a paragraph) came from. */
    private record Origin(int[] starts, int[] lines) {
        static Origin of(int line) {
            return new Origin(new int[] {0}, new int[] {line});
        }

        /** The line of the character at {@code offset} in the joined text. */
        int line(int offset) {
            int index = 0;
            while (index + 1 < starts.length && starts[index + 1] <= offset) {
                index++;
            }
            return lines[index];
        }
    }

    /** The spans of {@code text} (one item, cell or heading, on or starting at {@code line}). */
    private List<Span> inline(String text, int line) {
        return inline(text, Origin.of(line));
    }

    /** The spans of {@code text}, whose parts came from the lines {@code origin} says. */
    private List<Span> inline(String text, Origin origin) {
        List<Span> spans = new ArrayList<>();
        inline(text, origin, 0, false, false, null, spans);
        return merge(spans);
    }

    private void inline(String s, Origin origin, int offset, boolean bold, boolean italic, WikiLink link,
            List<Span> out) {
        StringBuilder text = new StringBuilder();
        int n = s.length();
        int i = 0;
        while (i < n) {
            int line = origin.line(offset + i);
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n && PUNCTUATION.indexOf(s.charAt(i + 1)) >= 0) {
                text.append(s.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '`') {
                int run = run(s, i, '`');
                int close = closingBackticks(s, i + run, run);
                if (close < 0) {
                    problem(line, "a ` without its closing `");
                    text.append(s, i, i + run);
                    i += run;
                    continue;
                }
                flush(text, bold, italic, link, out);
                String code = s.substring(i + run, close);
                if (code.length() >= 2 && code.startsWith(" ") && code.endsWith(" ") && !code.isBlank()) {
                    code = code.substring(1, code.length() - 1);
                }
                if (code.isEmpty()) {
                    problem(line, "an empty `code`");
                } else {
                    out.add(new Span(code, bold, italic, true, link));
                }
                i = close + run;
                continue;
            }
            if (c == '*') {
                int run = run(s, i, '*');
                boolean opens = i + run < n && !Character.isWhitespace(s.charAt(i + run));
                if (run > 2) {
                    problem(line, "*** is not supported: ** for bold, * for italic");
                    text.append(s, i, i + run);
                    i += run;
                    continue;
                }
                if (!opens) {
                    text.append(s, i, i + run);
                    i += run;
                    continue;
                }
                int close = closingStars(s, i + run, run);
                if (close < 0) {
                    problem(line, "a " + "*".repeat(run) + " without its closing " + "*".repeat(run));
                    text.append(s, i, i + run);
                    i += run;
                    continue;
                }
                flush(text, bold, italic, link, out);
                inline(s.substring(i + run, close), origin, offset + i + run, bold || run == 2, italic || run == 1,
                        link, out);
                i = close + run;
                continue;
            }
            if (c == '_' && (i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)) && s.charAt(i - 1) != '_')) {
                Matcher emphasis = UNDERSCORE_EMPHASIS.matcher(s);
                emphasis.region(i, n);
                if (emphasis.lookingAt()) {
                    problem(line, "_ makes italic or bold text on GitHub: use * or **, or put the name in `code`");
                }
            }
            if (c == '!' && i + 1 < n && s.charAt(i + 1) == '[') {
                int[] picture = link(s, i + 1);
                if (picture != null) {
                    problem(line, "a picture stands alone on its own line");
                    text.append(s, i + 2, picture[0]);
                    i = picture[2];
                    continue;
                }
            }
            if (c == '[') {
                int[] found = link(s, i);
                if (found == null) {
                    if (s.startsWith("[^", i)) {
                        problem(line, "footnotes are not supported");
                    } else if (referenceLink(s, i)) {
                        problem(line, "reference links ([text][name]) are not supported");
                    }
                    text.append(c);
                    i++;
                    continue;
                }
                String label = s.substring(i + 1, found[0]);
                String target = s.substring(found[0] + 2, found[1]).strip();
                if (target.indexOf(' ') >= 0) {
                    problem(line, "link titles are not supported: " + target);
                    target = target.substring(0, target.indexOf(' '));
                }
                WikiLink parsed = WikiLink.parse(target);
                if (link != null) {
                    problem(line, "a link inside a link");
                    parsed = link;
                } else {
                    links.add(new WikiPage.LinkUse(line, parsed));
                    if (!parsed.isValid()) {
                        problem(line, "a link goes to a wiki page (page.md, page.md#section), a section (#section) or "
                                + "a website (https://...), not to " + target);
                    }
                }
                if (label.isBlank()) {
                    problem(line, "a link without text");
                }
                flush(text, bold, italic, link, out);
                inline(label, origin, offset + i + 1, bold, italic, parsed.isValid() ? parsed : null, out);
                i = found[1] + 1;
                continue;
            }
            if (c == '<' && i + 1 < n && (Character.isLetter(s.charAt(i + 1)) || s.charAt(i + 1) == '/'
                    || s.charAt(i + 1) == '!')) {
                problem(line, "HTML is not supported");
            }
            text.append(c);
            i++;
        }
        flush(text, bold, italic, link, out);
    }

    private static void flush(StringBuilder text, boolean bold, boolean italic, WikiLink link, List<Span> out) {
        if (!text.isEmpty()) {
            out.add(new Span(text.toString(), bold, italic, false, link));
            text.setLength(0);
        }
    }

    /** Joins neighbouring spans of the same style. */
    private static List<Span> merge(List<Span> spans) {
        List<Span> merged = new ArrayList<>();
        for (Span span : spans) {
            if (!merged.isEmpty()) {
                Span last = merged.get(merged.size() - 1);
                if (!span.code() && !last.code() && last.bold() == span.bold() && last.italic() == span.italic()
                        && last.link() == span.link()) {
                    merged.set(merged.size() - 1, new Span(last.text() + span.text(), span.bold(), span.italic(), false,
                            span.link()));
                    continue;
                }
            }
            merged.add(span);
        }
        return merged;
    }

    private static int run(String s, int from, char c) {
        int end = from;
        while (end < s.length() && s.charAt(end) == c) {
            end++;
        }
        return end - from;
    }

    /** Where a run of exactly {@code length} backticks starts at or after {@code from}, or -1. */
    private static int closingBackticks(String s, int from, int length) {
        int i = from;
        while (i < s.length()) {
            if (s.charAt(i) == '`') {
                int run = run(s, i, '`');
                if (run == length) {
                    return i;
                }
                i += run;
            } else {
                i++;
            }
        }
        return -1;
    }

    /**
     * Where the {@code length} stars closing an emphasis that opened just before {@code from} start, or -1: the first
     * run of exactly that many stars after a non-space, skipping escapes, code and runs of another length (the other
     * kind of emphasis, nested inside). One pass, so no input makes it slow.
     */
    private static int closingStars(String s, int from, int length) {
        int i = from;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '`') {
                int run = run(s, i, '`');
                int close = closingBackticks(s, i + run, run);
                i = close < 0 ? i + run : close + run;
                continue;
            }
            if (c != '*') {
                i++;
                continue;
            }
            int run = run(s, i, '*');
            if (run == length && i > from && !Character.isWhitespace(s.charAt(i - 1))) {
                return i;
            }
            i += run;
        }
        return -1;
    }

    /**
     * A link starting at {@code [} at {@code start}: {labelEnd (the {@code ]}), targetEnd (the {@code )}), the index
     * after it}, or null when there is none.
     */
    private static int[] link(String s, int start) {
        int depth = 0;
        int labelEnd = -1;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '`') {
                int run = run(s, i, '`');
                int close = closingBackticks(s, i + run, run);
                i = (close < 0 ? i + run : close + run) - 1;
            } else if (c == '[') {
                depth++;
            } else if (c == ']' && --depth == 0) {
                labelEnd = i;
                break;
            }
        }
        if (labelEnd < 0 || labelEnd + 1 >= s.length() || s.charAt(labelEnd + 1) != '(') {
            return null;
        }
        int parens = 0;
        for (int i = labelEnd + 2; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                parens++;
            } else if (c == ')') {
                if (parens == 0) {
                    return new int[] {labelEnd, i, i + 1};
                }
                parens--;
            }
        }
        return null;
    }

    private static boolean referenceLink(String s, int start) {
        int close = s.indexOf(']', start);
        return close > start + 1 && close + 1 < s.length() && s.charAt(close + 1) == '['
                && s.indexOf(']', close + 1) > close + 1;
    }
}
