package dev.sculptory.fabric.client.editor.wiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.wiki.WikiBlock.Callout;
import dev.sculptory.fabric.client.editor.wiki.WikiBlock.Heading;
import dev.sculptory.fabric.client.editor.wiki.WikiBlock.Item;
import dev.sculptory.fabric.client.editor.wiki.WikiBlock.ListBlock;
import dev.sculptory.fabric.client.editor.wiki.WikiBlock.Paragraph;
import dev.sculptory.fabric.client.editor.wiki.WikiBlock.Picture;
import dev.sculptory.fabric.client.editor.wiki.WikiBlock.Rule;
import dev.sculptory.fabric.client.editor.wiki.WikiBlock.Span;
import dev.sculptory.fabric.client.editor.wiki.WikiBlock.Table;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** The wiki's Markdown subset: every construct, the anchors, and what is outside it. */
class WikiParserTest {
    private static WikiPage parse(String markdown) {
        return WikiParser.parse("page", markdown);
    }

    private static List<String> problems(String markdown) {
        return parse(markdown).problems().stream().map(WikiProblem::message).toList();
    }

    private static boolean reports(String markdown, String fragment) {
        return problems(markdown).stream().anyMatch(message -> message.contains(fragment));
    }

    private static Span span(String text, boolean bold, boolean italic, boolean code) {
        return new Span(text, bold, italic, code, null);
    }

    // ---- Anchors ----

    @Test
    void anchorsFollowTheFrozenRule() {
        assertEquals("magic-select-shift-click", WikiAnchors.anchor("Magic select (Shift+click)"));
        assertEquals("ctrl-k-find-anything", WikiAnchors.anchor("Ctrl+K: find anything"));
        assertEquals("hello-world", WikiAnchors.anchor("  --Hello__World!! "));
        assertEquals("ui-50", WikiAnchors.anchor("UI 50%"));
        assertEquals("caf-cr-me", WikiAnchors.anchor("Café crème"), "letters outside a–z are separators too");
        assertEquals("123", WikiAnchors.anchor("123"));
        assertEquals("", WikiAnchors.anchor("!!!"));
    }

    @Test
    void headingsGetTheirAnchorsAndTheFirstIsTheTitle() {
        WikiPage page = parse("# Shape brush\n\nText.\n\n## Radius and *height*\n\n### Magic select (Shift+click)\n");
        assertEquals("Shape brush", page.title());
        assertEquals(List.of("shape-brush", "radius-and-height", "magic-select-shift-click"),
                page.headings().stream().map(Heading::anchor).toList());
        assertEquals(List.of(1, 2, 3), page.headings().stream().map(Heading::level).toList());
        assertTrue(page.hasAnchor("radius-and-height"));
        assertFalse(page.hasAnchor("radius"));
        assertEquals(List.of(), page.problems());
    }

    @Test
    void aRepeatedHeadingGetsANumberedAnchorAsOnGitHub() {
        WikiPage page = parse("# Tools\n\n## Settings\n\nOne.\n\n## Settings\n\nTwo.\n");
        assertEquals(List.of("tools", "settings", "settings-1"), page.headings().stream().map(Heading::anchor).toList());
    }

    // ---- Blocks ----

    @Test
    void paragraphsJoinTheirLinesAndEndAtABlankLine() {
        WikiPage page = parse("# T\n\nOne line\nand the next.\n\nA second paragraph.\n");
        assertEquals(List.of(new Paragraph(List.of(Span.plain("One line and the next."))),
                new Paragraph(List.of(Span.plain("A second paragraph.")))), page.blocks().subList(1, 3));
    }

    @Test
    void boldItalicCodeAndEscapes() {
        Paragraph paragraph = (Paragraph) parse("# T\n\nA **bold** and *italic* word, `Ctrl+K`, **bold *and italic* too**"
                + " \\*stars\\*.").blocks().get(1);
        assertEquals(List.of(
                Span.plain("A "), span("bold", true, false, false), Span.plain(" and "),
                span("italic", false, true, false), Span.plain(" word, "), span("Ctrl+K", false, false, true),
                Span.plain(", "), span("bold ", true, false, false), span("and italic", true, true, false),
                span(" too", true, false, false), Span.plain(" *stars*.")), paragraph.spans());
    }

    @Test
    void aLoneStarBetweenSpacesIsJustAStar() {
        Paragraph paragraph = (Paragraph) parse("# T\n\n2 * 3 = 6").blocks().get(1);
        assertEquals(List.of(Span.plain("2 * 3 = 6")), paragraph.spans());
        assertEquals(List.of(), parse("# T\n\n2 * 3 = 6").problems());
    }

    @Test
    void linksToPagesSectionsAndWebsites() {
        WikiPage page = parse("# T\n\nSee [the brush](shape-brush.md), [its radius](shape-brush.md#radius), "
                + "[below](#tables), [**bold** link](https://example.com/a_(b)).");
        Paragraph paragraph = (Paragraph) page.blocks().get(1);
        List<WikiLink> links = paragraph.spans().stream().map(Span::link).filter(link -> link != null).distinct().toList();
        assertEquals(List.of(WikiLink.Kind.PAGE, WikiLink.Kind.PAGE, WikiLink.Kind.SECTION, WikiLink.Kind.EXTERNAL),
                links.stream().map(WikiLink::kind).toList());
        assertEquals("shape-brush", links.get(0).pageId());
        assertNull(links.get(0).anchor());
        assertEquals("radius", links.get(1).anchor());
        assertEquals("tables", links.get(2).anchor());
        assertEquals("https://example.com/a_(b)", links.get(3).url(), "parentheses inside an address");
        Span bold = paragraph.spans().stream().filter(span -> span.text().equals("bold")).findFirst().orElseThrow();
        assertTrue(bold.bold());
        assertEquals(links.get(3), bold.link(), "formatting inside a link keeps the link");
        assertEquals(4, page.links().size());
        assertEquals(List.of(), page.problems());
    }

    @Test
    void linkTargetsParse() {
        assertEquals(WikiLink.page("shape-brush", null), WikiLink.parse("shape-brush.md"));
        assertEquals(WikiLink.Kind.PAGE, WikiLink.parse("./shape-brush.md").kind());
        assertEquals("radius", WikiLink.parse("shape-brush.md#radius").anchor());
        assertNull(WikiLink.parse("shape-brush.md#").anchor());
        assertEquals(WikiLink.Kind.SECTION, WikiLink.parse("#radius").kind());
        assertEquals(WikiLink.Kind.EXTERNAL, WikiLink.parse("http://example.com").kind());
        for (String invalid : List.of("../README.md", "images/x.png", "Shape-Brush.md", "mailto:a@b.c", "", "#",
                "https://", "shape brush.md", "wiki/shape-brush.md")) {
            assertEquals(WikiLink.Kind.INVALID, WikiLink.parse(invalid).kind(), invalid);
        }
    }

    @Test
    void bulletAndNumberedListsWithOneLevelOfNesting() {
        WikiPage page = parse("# T\n\n- One\n- Two with\n  more text\n  - Nested\n  - Nested two\n- Three\n\n"
                + "3. Third\n4. Fourth\n  1. Nested step\n");
        ListBlock bullets = (ListBlock) page.blocks().get(1);
        assertFalse(bullets.ordered());
        assertEquals(List.of("One", "Two with more text", "Three"),
                bullets.items().stream().map(item -> WikiBlock.plainText(item.spans())).toList());
        Item two = bullets.items().get(1);
        assertNotNull(two.children());
        assertEquals(List.of("Nested", "Nested two"),
                two.children().items().stream().map(item -> WikiBlock.plainText(item.spans())).toList());
        ListBlock numbered = (ListBlock) page.blocks().get(2);
        assertTrue(numbered.ordered());
        assertEquals(3, numbered.start());
        assertEquals(2, numbered.items().size());
        assertTrue(numbered.items().get(1).children().ordered());
        assertEquals(List.of(), page.problems());
    }

    @Test
    void aBlankLineBetweenItemsKeepsTheListAndTextAfterItEndsIt() {
        WikiPage page = parse("# T\n\n- One\n\n- Two\n\nAfter.\n");
        assertEquals(2, ((ListBlock) page.blocks().get(1)).items().size());
        assertInstanceOf(Paragraph.class, page.blocks().get(2));
    }

    @Test
    void aBulletListAndANumberedListRightAfterItAreTwoLists() {
        WikiPage page = parse("# T\n\n- One\n1. First\n");
        assertEquals(3, page.blocks().size());
        assertTrue(((ListBlock) page.blocks().get(2)).ordered());
    }

    @Test
    void tablesHaveAHeaderAndRows() {
        WikiPage page = parse("# T\n\n| Key | What it does |\n| --- | --- |\n| `Ctrl+Z` | Undo |\n| Ctrl+Y | Redo **it** |\n");
        Table table = (Table) page.blocks().get(1);
        assertEquals(List.of(List.of(Span.plain("Key")), List.of(Span.plain("What it does"))), table.header());
        assertEquals(2, table.rows().size());
        assertEquals(List.of(span("Ctrl+Z", false, false, true)), table.rows().get(0).get(0));
        assertEquals(List.of(Span.plain("Redo "), span("it", true, false, false)), table.rows().get(1).get(1));
        assertEquals(2, table.columns());
        assertEquals(List.of(), page.problems());
    }

    @Test
    void aCalloutHoldsParagraphsAndLists() {
        WikiPage page = parse("# T\n\n> **Tip:** one\n> line.\n>\n> - item\n\nAfter.\n");
        Callout callout = (Callout) page.blocks().get(1);
        assertEquals(2, callout.blocks().size());
        assertEquals(List.of(span("Tip:", true, false, false), Span.plain(" one line.")),
                ((Paragraph) callout.blocks().get(0)).spans());
        assertInstanceOf(ListBlock.class, callout.blocks().get(1));
        assertInstanceOf(Paragraph.class, page.blocks().get(2));
        assertEquals(List.of(), page.problems());
    }

    @Test
    void rulesAndPictures() {
        WikiPage page = parse("# T\n\nText.\n\n---\n\n![The settings](images/t-settings.png)\n");
        assertInstanceOf(Rule.class, page.blocks().get(2));
        assertEquals(new Picture("The settings", "images/t-settings.png"), page.blocks().get(3));
        assertEquals(List.of(new WikiPage.PictureUse(7, "images/t-settings.png")), page.pictures());
        assertEquals(List.of(), page.problems());
    }

    @Test
    void theSampleWikiUsesEveryConstructAndNothingElse() {
        WikiLibrary library = DirectoryWikiSource.sampleLibrary();
        WikiPage page = library.page("constructs").orElseThrow();
        assertEquals(List.of(), page.problems());
        List<Class<?>> kinds = page.blocks().stream().<Class<?>>map(Object::getClass).distinct().toList();
        for (Class<?> kind : List.of(Heading.class, Paragraph.class, ListBlock.class, Table.class, Callout.class,
                Rule.class, Picture.class)) {
            assertTrue(kinds.contains(kind), kind.getSimpleName());
        }
        for (WikiPage each : library.pages()) {
            assertEquals(List.of(), each.problems(), each.id());
        }
    }

    // ---- Outside the subset ----

    @Test
    void whatIsOutsideTheSubsetIsReported() {
        assertTrue(reports("# T\n\n<b>bold</b>\n", "HTML"));
        assertTrue(reports("# T\n\n```\ncode\n```\n", "code blocks"));
        assertTrue(reports("# T\n\n#### Deep\n", "only #, ## and ###"));
        assertTrue(reports("# T\n\n* item\n", "a bullet is written -"));
        assertTrue(reports("# T\n\n1) item\n", "a numbered item is written 1."));
        assertTrue(reports("# T\n\n- a\n  - b\n    - c\n", "only one level of nesting"));
        assertTrue(reports("# T\n\nSome _italic_ text\n", "_ makes italic"));
        assertTrue(reports("# T\n\nText[^1] here\n", "footnotes"));
        assertTrue(reports("# T\n\nSee ![x](images/x.png) inline\n", "a picture stands alone"));
        assertTrue(reports("# T\n\n| a | b |\n| :-- | --: |\n| 1 | 2 |\n", "alignment markers"));
        assertTrue(reports("# T\n\n| a | b |\n| --- | --- |\n| 1 | 2 | 3 |\n", "a | inside a cell"));
        assertTrue(reports("# T\n\n> one\n> > two\n", "a callout inside a callout"));
        assertTrue(reports("# T\n\nText\n---\n", "leave a blank line above it"));
        assertTrue(reports("# T\n\n***\n", "a rule is written ---"));
        assertTrue(reports("# T\n\nSee [x][ref]\n", "reference links"));
        assertTrue(reports("# T\n\n[ref]: https://example.com\n", "reference links"));
        assertTrue(reports("# T\n\nSee [install](../README.md)\n", "a link goes to a wiki page"));
        assertTrue(reports("# T\n\n-\titem\n", "a tab"));
        assertTrue(reports("# T\n\nA **bold start\n", "without its closing **"));
        assertTrue(reports("# T\n\nA `code start\n", "without its closing `"));
        assertTrue(reports("# T\n\n[x](shape.md \"Title\")\n", "link titles"));
        assertTrue(reports("# T\n\n> callout\nmore\n", "joins it on GitHub"));
        assertTrue(reports("# T\n\n| a |\n| --- |\n| 1 |\ntext\n", "a line right after a table"));
        assertTrue(reports("# T\n\n![x](pictures/X.PNG)\n", "a picture is a PNG in images/"));
        assertTrue(reports("Text first\n", "starts with its # title"));
        assertTrue(reports("# T\n\n    indented code\n", "code block on GitHub"));
        assertTrue(reports("# T\n\nText\n===\n", "=== under a line"));
        assertTrue(reports("# T\n\n> ## Heading\n", "a heading inside a > callout"));
        assertTrue(reports("# T\n\nA §cred\n", "§"));
    }

    @Test
    void theFormattingCharacterNeverReachesThePage() {
        WikiPage page = parse("# T§l\n\nA §cred word\n");
        assertEquals("T", page.title());
        assertEquals(List.of(Span.plain("A red word")), ((Paragraph) page.blocks().get(1)).spans());
    }

    @Test
    void garbageNeverThrowsAndAlwaysGivesAPage() {
        String alphabet = "#*_`[]()!|>-+.:1 \n\n  \t\\<a/ §x";
        SplittableRandom random = new SplittableRandom(20260928L);
        for (int round = 0; round < 3000; round++) {
            int length = random.nextInt(400);
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < length; i++) {
                text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            WikiPage page = WikiParser.parse("garbage", text.toString());
            assertNotNull(page);
            assertTrue(page.problems().stream().noneMatch(problem -> problem.message().contains("could not read")),
                    () -> "the parser failed on: " + text);
        }
        for (String odd : List.of("", "\n", "*", "**", "***", "`", "``", "[", "[]", "[](", "![](", "|", "|\n|-|",
                ">", "> >", "-", "- \n  - \n    -", "1.", "#", "######", "﻿# T", "\r\n\r\n", "\u0000", "x".repeat(100_000),
                "[a](b.md#)", "**`**`**", "*a **b* c**")) {
            assertNotNull(WikiParser.parse("odd", odd));
            assertTrue(WikiParser.parse("odd", odd).problems().stream()
                    .noneMatch(problem -> problem.message().contains("could not read")), odd);
        }
        assertNotNull(WikiParser.parse(null, null));
    }
}
