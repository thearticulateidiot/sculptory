package dev.sculptory.fabric.client.editor.wiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.tool.ToolId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The page list (grouped like the home page), the search, and the tool-to-page table. */
class WikiLibraryTest {
    private final WikiLibrary library = DirectoryWikiSource.sampleLibrary();

    @Test
    void theListIsGroupedLikeTheHomePageAndUnlinkedPagesComeLast() {
        List<WikiContents.Group> groups = library.contents().groups();
        assertEquals(List.of(
                new WikiContents.Group(WikiContents.Kind.TOP, null, List.of("home", "getting-started")),
                new WikiContents.Group(WikiContents.Kind.SECTION, "Basics", List.of("constructs")),
                new WikiContents.Group(WikiContents.Kind.SECTION, "Tools", List.of("shape-brush", "terrain-brushes")),
                new WikiContents.Group(WikiContents.Kind.OTHERS, null, List.of("orphan"))), groups);
        assertEquals(List.of("home", "getting-started", "constructs", "shape-brush", "terrain-brushes", "orphan"),
                library.pages().stream().map(WikiPage::id).toList());
    }

    @Test
    void withoutAHomePageEveryPageIsListedByTitle() {
        WikiLibrary noHome = new WikiLibrary(WikiSource.of(Map.of("b", "# Beta\n", "a", "# Zulu\n", "c", "# alpha\n")));
        assertEquals(List.of(new WikiContents.Group(WikiContents.Kind.OTHERS, null, List.of("c", "b", "a"))),
                noHome.contents().groups());
    }

    @Test
    void aMissingPageIsEmptyAndABrokenSourceGivesAnEmptyWiki() {
        assertEquals(Optional.empty(), library.page("nope"));
        assertEquals(Optional.empty(), library.page(null));
        WikiSource broken = new WikiSource() {
            @Override
            public List<String> pageIds() {
                throw new IllegalStateException("no resources");
            }

            @Override
            public Optional<String> read(String id) {
                throw new IllegalStateException("no resources");
            }
        };
        WikiLibrary empty = new WikiLibrary(broken);
        assertTrue(empty.isEmpty());
        assertEquals(List.of(), empty.contents().groups());
        WikiLibrary odd = new WikiLibrary(WikiSource.of(Map.of("Bad Id", "# Bad\n", "good", "# Good\n")));
        assertEquals(List.of("good"), odd.pages().stream().map(WikiPage::id).toList(), "ids that aren't page ids");
    }

    @Test
    void theSearchFindsTitlesFirstThenHeadings() {
        assertEquals(List.of(), library.search(""));
        assertEquals(List.of(), library.search("zzz"));
        assertEquals(List.of(new WikiSearch.Result("shape-brush", null, "Shape brush", null)), library.search("shape"));
        assertEquals(List.of(new WikiSearch.Result("shape-brush", "radius", "Shape brush", "Radius")),
                library.search("RADIUS"));
        assertEquals(List.of(new WikiSearch.Result("constructs", "magic-select-shift-click", "Every construct",
                "Magic select (Shift+click)")), library.search("magic  sel"));
        List<WikiSearch.Result> brush = library.search("brush");
        assertEquals(List.of("shape-brush", "terrain-brushes"), brush.stream().map(WikiSearch.Result::pageId).toList(),
                "both titles, in list order");
        List<WikiSearch.Result> t = library.search("t");
        assertTrue(t.get(0).isPage(), "titles before headings");
        assertEquals("terrain-brushes", t.get(0).pageId(), "a title starting with the query first");
    }

    @Test
    void everyToolHasItsPage() {
        assertEquals(new WikiPages.Target("select", null), WikiPages.forTool(ToolId.SELECT));
        for (ToolId tool : List.of(ToolId.RAISE, ToolId.LOWER, ToolId.SMOOTH, ToolId.FLATTEN)) {
            assertEquals(new WikiPages.Target("terrain-brushes", null), WikiPages.forTool(tool));
        }
        assertEquals(new WikiPages.Target("terrain-brushes", "paint"), WikiPages.forTool(ToolId.PAINT));
        assertEquals(new WikiPages.Target("terrain-brushes", "paint"), WikiPages.forTool(ToolId.PALETTE));
        assertEquals(new WikiPages.Target("place", null), WikiPages.forTool(ToolId.PLACE));
        assertEquals(new WikiPages.Target("scatter", null), WikiPages.forTool(ToolId.SCATTER));
        assertEquals(new WikiPages.Target("shape-brush", null), WikiPages.forTool(ToolId.SHAPE));
        assertEquals(new WikiPages.Target("generate", null), WikiPages.forTool(ToolId.GENERATE));
        assertEquals(new WikiPages.Target("extrude", null), WikiPages.forTool(ToolId.EXTRUDE));
        assertEquals(new WikiPages.Target("fluid", null), WikiPages.forTool(ToolId.FLUID));
        assertEquals(new WikiPages.Target("tinker", null), WikiPages.forTool(ToolId.TINKER));
        assertEquals(new WikiPages.Target("weather", null), WikiPages.forTool(ToolId.WEATHER));
        assertEquals(new WikiPages.Target("home", null), WikiPages.forTool(new ToolId("future")));
        for (WikiPages.Target target : WikiPages.tools().values()) {
            assertTrue(WikiPages.FROZEN.contains(target.pageId()), target.pageId());
        }
        assertEquals(32, WikiPages.FROZEN.size());
        assertTrue(WikiPages.FROZEN.stream().allMatch(id -> WikiLink.PAGE_ID.matcher(id).matches()));
    }
}
