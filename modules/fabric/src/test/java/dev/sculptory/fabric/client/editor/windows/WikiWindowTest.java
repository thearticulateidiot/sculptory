package dev.sculptory.fabric.client.editor.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.English;
import dev.sculptory.fabric.client.editor.ui.McFontText;
import dev.sculptory.fabric.client.editor.ui.PopupLayer;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.RecordingGraphics;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.wiki.DirectoryWikiSource;
import dev.sculptory.fabric.client.editor.wiki.FakePictures;
import dev.sculptory.fabric.client.editor.wiki.PictureOverlay;
import dev.sculptory.fabric.client.editor.wiki.WikiLibrary;
import dev.sculptory.fabric.client.editor.wiki.WikiLink;
import dev.sculptory.fabric.client.editor.wiki.WikiPage;
import dev.sculptory.fabric.client.editor.wiki.WikiPageView;
import dev.sculptory.fabric.client.editor.wiki.WikiPictures;
import dev.sculptory.fabric.client.editor.wiki.WikiSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/**
 * The Wiki window laid out and drawn headless, on the sample wiki, with Minecraft's font widths, on the reference screen
 * (GUI scale 6: 854x498 units at UI 50%, 427x249 at 100%).
 */
class WikiWindowTest {
    private static final String PICTURE = "images/constructs-settings.png";
    private static final int GUI_SCALE = 6;

    private final UiContext ctx = new UiContext(McFontText.INSTANCE, Theme.DARK);
    private final WikiWindow window = new WikiWindow(English.INSTANCE);
    private final FakePictures pictures = new FakePictures().with(PICTURE, 600, 200);
    private final List<String> websites = new ArrayList<>();
    private int percent;

    @BeforeEach
    void setUp() {
        window.setWiki(DirectoryWikiSource.sampleLibrary(), pictures, websites::add);
        uiSize(50);
    }

    /** The reference screen at this editor UI size. */
    private void uiSize(int percent) {
        this.percent = percent;
        window.setDisplay(GUI_SCALE * percent / 100.0, percent / 100.0);
        ctx.setScreenSize(2 * 427 * 50 / percent, 2 * 249 * 50 / percent);
    }

    /** The screen's pixels per UI unit. */
    private double pixelsPerUnit() {
        return GUI_SCALE * percent / 100.0;
    }

    private void layout(int width, int height) {
        window.refresh();
        window.node().layout(ctx, new Rect(0, 0, width, height));
        ctx.popups().layout(ctx, ctx.screenWidth(), ctx.screenHeight());
    }

    private RecordingGraphics render() {
        RecordingGraphics g = new RecordingGraphics();
        window.node().render(g, ctx);
        assertTrue(g.isBalanced());
        return g;
    }

    private RecordingGraphics renderPopups() {
        RecordingGraphics g = new RecordingGraphics();
        ctx.popups().render(g, ctx);
        assertTrue(g.isBalanced());
        return g;
    }

    /** A point on the page (inside the pane) over a link to {@code raw}. */
    private double[] pointOnLink(String raw) {
        Rect pane = window.pagePane().bounds();
        for (int y = pane.y(); y < pane.bottom(); y++) {
            for (int x = pane.x(); x < pane.right(); x++) {
                Optional<WikiLink> link = window.pageView().linkAt(ctx, x, y);
                if (link.isPresent() && link.get().raw().equals(raw)) {
                    return new double[] {x, y};
                }
            }
        }
        throw new AssertionError("No link to " + raw + " on the page");
    }

    private void click(double[] point) {
        assertTrue(window.pageView().mouseDown(ctx, point[0], point[1], GLFW.GLFW_MOUSE_BUTTON_LEFT));
        window.pageView().mouseUp(ctx, point[0], point[1], GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    private static double[] centre(Rect rect) {
        return new double[] {rect.x() + rect.width() / 2.0, rect.y() + rect.height() / 2.0};
    }

    @Test
    void itOpensAtHomeWithThePageListHiddenAndOneSlimRowOverThePage() {
        layout(WikiWindow.WIDTH, WikiWindow.HEIGHT);
        assertEquals(Optional.of("home"), window.currentPage());
        assertEquals("Sample wiki", window.title());
        assertFalse(window.backButton().isEnabled());
        assertFalse(window.forwardButton().isEnabled());
        assertFalse(window.isListShown(), "the list is hidden until Pages");
        List<String> texts = render().drawnTexts();
        assertEquals(1, Collections.frequency(texts, "Sample wiki"), "the title once, above the page: " + texts);
        assertTrue(texts.contains("§lBasics"), "the page's own headings, bold: " + texts);
        assertFalse(texts.contains("More pages"), "no list: " + texts);
        assertFalse(texts.contains("Search the wiki"));
        Rect back = window.backButton().bounds();
        Rect pages = window.pagesButton().bounds();
        assertEquals(back.y(), pages.y(), "Back, Forward, Home, the title and Pages on one row");
        assertEquals(back.y(), window.homeButton().bounds().y());
        assertFalse(window.homeButton().isEnabled(), "Home is dim on the home page");
        assertEquals(Theme.DARK.controlHeight, pages.height());
        assertEquals(pages.bottom() + Theme.DARK.gap, window.pagePane().bounds().y(), "the page right under it");
        assertEquals(WikiWindow.WIDTH, window.pagePane().bounds().width(), "the page has the window's width");
    }

    @Test
    void pagesDropsTheListDownWithTheSearchAndPickingAPageClosesIt() {
        layout(WikiWindow.WIDTH, WikiWindow.HEIGHT);
        window.pagesButton().click();
        layout(WikiWindow.WIDTH, WikiWindow.HEIGHT);
        assertTrue(window.isListShown());
        assertTrue(ctx.isFocused(window.searchBox()), "typing searches at once");
        assertEquals(List.of("Sample wiki", "Getting started", "## Basics", "Every construct", "## Tools", "Shape brush",
                "Terrain brushes", "## More pages", "An orphan page"), window.listed());
        PopupLayer.Popup popup = ctx.popups().popups().get(0);
        Rect pages = window.pagesButton().bounds();
        assertEquals(pages.bottom(), popup.rect().y(), "under Pages");
        assertEquals(pages.right(), popup.rect().right(), "its right edge at Pages'");
        assertEquals(WikiWindow.LIST_WIDTH, popup.rect().width());
        List<String> texts = renderPopups().drawnTexts();
        assertTrue(texts.containsAll(List.of("Search the wiki", "Every construct", "More pages")), texts.toString());

        window.row("shape-brush").orElseThrow().click();
        layout(WikiWindow.WIDTH, WikiWindow.HEIGHT);
        assertEquals(Optional.of("shape-brush"), window.currentPage());
        assertEquals("Shape brush", window.title());
        assertFalse(window.isListShown(), "picking a page closes the list");
        assertFalse(ctx.popups().isOpen());

        window.pagesButton().click();
        assertTrue(window.isListShown());
        window.pagesButton().click();
        assertFalse(window.isListShown(), "Pages closes it again");
        window.pagesButton().click();
        ctx.popups().closeTop();
        assertFalse(window.isListShown(), "as Esc does (the top popup)");
    }

    @Test
    void theSearchFindsTitlesAndHeadingsAndEnterOpensTheFirst() {
        layout(WikiWindow.WIDTH, WikiWindow.HEIGHT);
        window.pagesButton().click();
        window.search("radius");
        assertEquals(List.of("Radius · Shape brush"), window.listed());
        window.searchBox().keyPressed(ctx, GLFW.GLFW_KEY_ENTER, 0, 0);
        layout(WikiWindow.WIDTH, WikiWindow.HEIGHT);
        assertEquals(Optional.of("shape-brush"), window.currentPage());
        assertFalse(window.isListShown());
        assertTrue(window.pagePane().scroll().offset() > 0 || window.pagePane().scroll().maxOffset() == 0);
        window.search("brush");
        assertEquals(List.of("Shape brush", "Terrain brushes"), window.listed());
        window.search("zzz");
        assertEquals(List.of("No page title or heading has those words."), window.listed());
        window.pagesButton().click();
        assertEquals(9, window.listed().size(), "Pages shows the whole list again");
        assertEquals("", window.searchBox().text());
    }

    @Test
    void backAndForwardReturnAndTheMouseSideButtonsToo() {
        layout(WikiWindow.WIDTH, WikiWindow.HEIGHT);
        window.open("shape-brush", null);
        layout(WikiWindow.WIDTH, WikiWindow.HEIGHT);
        assertEquals("Shape brush", window.title());
        assertTrue(window.backButton().isEnabled());
        window.backButton().click();
        assertEquals(Optional.of("home"), window.currentPage());
        assertTrue(window.forwardButton().isEnabled());
        window.forwardButton().click();
        assertEquals(Optional.of("shape-brush"), window.currentPage());
        assertTrue(window.pagePane().mouseDown(ctx, 300, 100, GLFW.GLFW_MOUSE_BUTTON_4), "the mouse's back button");
        assertEquals(Optional.of("home"), window.currentPage());
        assertTrue(window.pagePane().mouseDown(ctx, 300, 100, GLFW.GLFW_MOUSE_BUTTON_5), "the mouse's forward button");
        assertEquals(Optional.of("shape-brush"), window.currentPage());

        // The keyboard on the page: Backspace and Alt+Left go back, Alt+Right forward.
        assertTrue(window.pagePane().keyPressed(ctx, GLFW.GLFW_KEY_BACKSPACE, 0, 0), "Backspace goes back");
        assertEquals(Optional.of("home"), window.currentPage());
        assertTrue(window.pagePane().keyPressed(ctx, GLFW.GLFW_KEY_RIGHT, 0, GLFW.GLFW_MOD_ALT), "Alt+Right forward");
        assertEquals(Optional.of("shape-brush"), window.currentPage());
        assertTrue(window.pagePane().keyPressed(ctx, GLFW.GLFW_KEY_LEFT, 0, GLFW.GLFW_MOD_ALT), "Alt+Left back");
        assertEquals(Optional.of("home"), window.currentPage());
        assertFalse(window.pagePane().keyPressed(ctx, GLFW.GLFW_KEY_BACKSPACE, 0, 0), "nothing further back");
        assertFalse(window.pagePane().keyPressed(ctx, GLFW.GLFW_KEY_BACKSPACE, 0, GLFW.GLFW_MOD_CONTROL),
                "Ctrl+Backspace is not Back");

        // Home from any page; Back returns from it.
        window.open("shape-brush", null);
        layout(WikiWindow.WIDTH, WikiWindow.HEIGHT);
        assertTrue(window.homeButton().isEnabled());
        window.homeButton().click();
        assertEquals(Optional.of("home"), window.currentPage());
        assertEquals("Sample wiki", window.title());
        assertFalse(window.homeButton().isEnabled());
        window.backButton().click();
        assertEquals(Optional.of("shape-brush"), window.currentPage());
    }

    @Test
    void linksOpenPagesScrollToSectionsAndAskBeforeWebsites() {
        window.open("constructs", null);
        layout(WikiWindow.WIDTH, 200);
        assertEquals(0, window.pagePane().scroll().offset());

        click(pointOnLink("https://example.com/wiki"));
        assertEquals(List.of("https://example.com/wiki"), websites, "handed on to Minecraft's link screen");
        assertEquals(Optional.of("constructs"), window.currentPage());

        click(pointOnLink("terrain-brushes.md#paint"));
        layout(WikiWindow.WIDTH, 200);
        assertEquals(Optional.of("terrain-brushes"), window.currentPage());
        assertEquals("Terrain brushes", window.title());
        window.back();
        layout(WikiWindow.WIDTH, 200);
        assertEquals(Optional.of("constructs"), window.currentPage());

        click(pointOnLink("#tables"));
        layout(WikiWindow.WIDTH, 200);
        int tables = window.pageView().anchorY(ctx, window.pageView().bounds().width(), "tables").orElseThrow();
        assertTrue(tables > 0);
        int offset = window.pagePane().scroll().offset();
        assertEquals(Math.min(tables, window.pagePane().scroll().maxOffset()), offset,
                "the section's heading at the top");
        assertTrue(offset > 0);

        window.open("shape-brush", null);
        layout(WikiWindow.WIDTH, 200);
        assertEquals(0, window.pagePane().scroll().offset());
        window.back();
        layout(WikiWindow.WIDTH, 200);
        assertEquals(Optional.of("constructs"), window.currentPage());
        assertEquals(offset, window.pagePane().scroll().offset(), "Back returns to where the page was left");
    }

    @Test
    void anUnknownSectionOpensThePageAtItsTop() {
        window.open("constructs", "no-such-section");
        layout(WikiWindow.WIDTH, WikiWindow.HEIGHT);
        assertEquals(Optional.of("constructs"), window.currentPage());
        assertEquals(0, window.pagePane().scroll().offset());
    }

    @Test
    void textIsAThirdLargerAtUi50WithRoomyLinesInAColumnOfAbout70Characters() {
        for (int size : List.of(50, 100)) {
            uiSize(size);
            float scale = window.pageView().scale().scale();
            assertEquals(size == 50 ? 4.0 / 3 : 1.0, scale, 1e-5, "at " + size + "%");
            window.open("constructs", null);
            int wide = ctx.screenWidth() - 8;
            layout(wide, 2000);
            Rect view = window.pageView().bounds();
            Rect column = window.pageView().columnBounds(ctx);
            String at = " at " + size + "%";
            assertTrue(column.width() <= Math.ceil(WikiPageView.MAX_COLUMN * scale) + 1, column + at);
            int seventy = McFontText.INSTANCE.width("The quick brown fox jumps over the lazy dog. ".repeat(2)
                    .substring(0, 70));
            assertTrue(Math.abs(column.width() / scale - seventy) <= 30, "about 70 characters: " + column + at);
            if (view.width() > column.width() + 20) {
                int left = column.x() - view.x();
                int right = view.right() - column.right();
                assertTrue(Math.abs(left - right) <= 2, "centred in a wide window: " + left + " " + right + at);
            }
            RecordingGraphics g = render();
            TreeSet<Integer> lines = new TreeSet<>();
            for (RecordingGraphics.DrawnText text : g.drawnTextPositions()) {
                if (Math.abs(text.scale() - scale) < 1e-4 && text.y() >= view.y()) {
                    assertTrue(text.x() >= column.x(), text + at);
                    lines.add(text.y());
                }
            }
            int pitch = Integer.MAX_VALUE;
            Integer before = null;
            for (int y : lines) {
                if (before != null) {
                    pitch = Math.min(pitch, y - before);
                }
                before = y;
            }
            double lineHeight = McFontText.INSTANCE.lineHeight() * scale;
            assertTrue(pitch / lineHeight >= 1.3 && pitch / lineHeight <= 1.55, "lines " + pitch + " apart" + at);
            assertTrue(g.drawnTextPositions().stream().anyMatch(text -> text.text().equals("§lTables")
                    && text.scale() > scale + 0.1), "## headings larger" + at);
        }
    }

    @Test
    void picturesAreThumbnailsWithCaptionsAndAreFreedWhenLeftOrClosed() {
        window.open("constructs", "pictures");
        layout(WikiWindow.WIDTH, WikiWindow.HEIGHT);
        RecordingGraphics g = render();
        List<RecordingGraphics.Texture> textures = g.drawnTextures();
        assertEquals(1, textures.size());
        RecordingGraphics.Texture texture = textures.get(0);
        assertEquals(FakePictures.texture(PICTURE), texture.id());
        Rect column = window.pageView().columnBounds(ctx);
        Rect thumbnail = texture.rect();
        assertTrue(thumbnail.width() <= column.width() * 0.4 + 1, thumbnail + " in " + column);
        assertTrue(thumbnail.width() >= column.width() * 0.35, "not much smaller either: " + thumbnail);
        assertEquals(200.0 / 600, (double) thumbnail.height() / thumbnail.width(), 0.02, "the picture's proportions");
        assertTrue(thumbnail.width() * pixelsPerUnit() <= 600 + pixelsPerUnit(), "never enlarged");
        Rect frame = window.pageView().pictureBounds(ctx).get(0);
        assertTrue(frame.contains(thumbnail.x(), thumbnail.y()) && frame.width() >= thumbnail.width() + 2);
        assertTrue(Math.abs(frame.x() - column.x() - (column.right() - frame.right())) <= 2, "centred");
        RecordingGraphics.DrawnText caption = g.drawnTextPositions().stream()
                .filter(text -> text.text().equals("The Shape brush's settings")).findFirst().orElseThrow();
        assertEquals(1.0, caption.scale(), 1e-4, "the caption at the UI's own size, smaller than the page's text");
        assertTrue(caption.y() >= frame.bottom(), "under the picture");

        window.open("shape-brush", null);
        assertEquals(List.of(PICTURE), pictures.freed, "freed when its page is left");
        window.back();
        layout(WikiWindow.WIDTH, WikiWindow.HEIGHT);
        render();
        assertEquals(List.of(PICTURE), List.copyOf(pictures.loaded), "loaded again");
        window.closed();
        assertTrue(pictures.loaded.isEmpty(), "freed when the window closes");
        layout(WikiWindow.WIDTH, WikiWindow.HEIGHT);
        assertEquals(1, render().drawnTextures().size(), "and loaded again when it shows");
    }

    @Test
    void aSmallPictureIsNotBlownUpAndAWideStripMayTakeTheColumn() {
        for (int size : List.of(50, 100)) {
            uiSize(size);
            window.setWiki(DirectoryWikiSource.sampleLibrary(), new FakePictures().with(PICTURE, 40, 20), url -> {});
            window.open("constructs", "pictures");
            layout(WikiWindow.WIDTH, WikiWindow.HEIGHT);
            Rect rect = render().drawnTextures().get(0).rect();
            assertEquals(40 / pixelsPerUnit(), rect.width(), 1.0, "its own size at " + size + "%: " + rect);
            assertEquals(20 / pixelsPerUnit(), rect.height(), 1.0);
        }
        uiSize(50);
        window.setWiki(DirectoryWikiSource.sampleLibrary(), new FakePictures().with(PICTURE, 1200, 100), url -> {});
        window.open("constructs", "pictures");
        layout(WikiWindow.WIDTH, WikiWindow.HEIGHT);
        Rect strip = render().drawnTextures().get(0).rect();
        assertTrue(strip.width() > window.pageView().columnBounds(ctx).width() / 2, strip.toString());
        assertTrue(strip.width() <= 1200 / pixelsPerUnit() + 1, strip.toString());
    }

    @Test
    void aClickOnAPictureShowsItFullSizeOverTheEditorUntilAClick() {
        window.open("constructs", "pictures");
        layout(WikiWindow.WIDTH, WikiWindow.HEIGHT);
        Rect thumbnail = window.pageView().pictureBounds(ctx).get(0);
        double[] point = centre(thumbnail);
        window.pageView().mouseMove(ctx, point[0], point[1]);
        assertEquals("Click to see it full size", window.pageView().tooltip());
        click(point);
        assertEquals(1, ctx.popups().popups().size());
        PopupLayer.Popup popup = ctx.popups().popups().get(0);
        PictureOverlay overlay = assertInstanceOf(PictureOverlay.class, popup.content());
        assertEquals(Theme.DARK.backdrop, popup.backdrop(), "the editor dimmed under it");
        layout(WikiWindow.WIDTH, WikiWindow.HEIGHT);
        RecordingGraphics g = renderPopups();
        assertTrue(g.filledRects().contains(new Rect(0, 0, ctx.screenWidth(), ctx.screenHeight())));
        Rect shown = g.drawnTextures().get(0).rect();
        assertEquals(new Rect(shown.x(), shown.y(), 200, 67), shown, "600x200 pixels at 3 per unit");
        assertTrue(Math.abs(centre(popup.rect())[0] - ctx.screenWidth() / 2.0) <= 1, "centred: " + popup.rect());
        assertTrue(g.drawnTexts().contains("The Shape brush's settings"), "its caption");
        assertEquals("Click or press Esc to close", overlay.tooltip());

        assertTrue(overlay.mouseDown(ctx, shown.x() + 5, shown.y() + 5, GLFW.GLFW_MOUSE_BUTTON_LEFT));
        assertFalse(ctx.popups().isOpen(), "a click closes it");
        assertFalse(overlay.isOpen());

        // Larger than the screen: fitted to it, whole.
        window.setWiki(DirectoryWikiSource.sampleLibrary(), new FakePictures().with(PICTURE, 4000, 3000), url -> {});
        layout(WikiWindow.WIDTH, WikiWindow.HEIGHT);
        click(centre(window.pageView().pictureBounds(ctx).get(0)));
        layout(WikiWindow.WIDTH, WikiWindow.HEIGHT);
        Rect fitted = renderPopups().drawnTextures().get(0).rect();
        Rect screen = new Rect(0, 0, ctx.screenWidth(), ctx.screenHeight());
        assertTrue(screen.contains(fitted.x(), fitted.y()) && fitted.right() <= screen.right()
                && fitted.bottom() <= screen.bottom(), fitted.toString());
        assertEquals(4.0 / 3, (double) fitted.width() / fitted.height(), 0.02);
        assertTrue(fitted.height() > screen.height() * 0.7, "as large as fits: " + fitted);
        ctx.popups().closeTop();
        assertFalse(ctx.popups().isOpen(), "Esc (the top popup)");
    }

    @Test
    void aMissingPageOrPictureShowsNotFound() {
        window.open("nowhere", null);
        layout(WikiWindow.WIDTH, WikiWindow.HEIGHT);
        assertTrue(render().drawnTexts().contains("Page not found: nowhere"));
        assertEquals("nowhere", window.title());

        window.setWiki(DirectoryWikiSource.sampleLibrary(), WikiPictures.NONE, url -> {});
        window.open("constructs", "pictures");
        layout(WikiWindow.WIDTH, WikiWindow.HEIGHT);
        RecordingGraphics g = render();
        assertTrue(String.join(" ", g.drawnTexts()).contains("Picture not found: " + PICTURE), g.drawnTexts().toString());
        assertEquals(List.of(), g.drawnTextures());
    }

    @Test
    void withoutPagesTheListSaysSoAndHomeIsNotFound() {
        window.setWiki(new WikiLibrary(WikiSource.of(Map.of())), WikiPictures.NONE, url -> {});
        layout(WikiWindow.WIDTH, WikiWindow.HEIGHT);
        assertEquals(List.of("This copy of the mod has no wiki pages."), window.listed());
        assertTrue(render().drawnTexts().contains("Page not found: home"));
    }

    @Test
    void everyTextTheWikiShowsHasEnglish() {
        java.util.Set<String> keys = new java.util.TreeSet<>();
        dev.sculptory.fabric.client.editor.Translator recording = new dev.sculptory.fabric.client.editor.Translator() {
            @Override
            public String translate(String key, Object... args) {
                keys.add(key);
                return English.INSTANCE.translate(key, args);
            }

            @Override
            public boolean has(String key) {
                return English.INSTANCE.has(key);
            }
        };
        WikiWindow recorded = new WikiWindow(recording);
        recorded.setWiki(DirectoryWikiSource.sampleLibrary(), pictures, url -> {});
        recorded.refresh();
        recorded.search("zzz");
        recorded.search("");
        recorded.open("nowhere", null);
        recorded.open("constructs", "pictures");
        recorded.node().layout(ctx, new Rect(0, 0, 420, 300));
        recorded.node().render(new RecordingGraphics(), ctx);
        double[] point = centre(recorded.pageView().pictureBounds(ctx).get(0));
        recorded.pageView().mouseMove(ctx, point[0], point[1]);
        recorded.pageView().tooltip();
        recorded.pageView().mouseDown(ctx, point[0], point[1], GLFW.GLFW_MOUSE_BUTTON_LEFT);
        recorded.pageView().mouseUp(ctx, point[0], point[1], GLFW.GLFW_MOUSE_BUTTON_LEFT);
        recorded.setWiki(DirectoryWikiSource.sampleLibrary(), WikiPictures.NONE, url -> {});
        recorded.node().layout(ctx, new Rect(0, 0, 420, 300));
        recorded.setWiki(new WikiLibrary(WikiSource.of(Map.of())), WikiPictures.NONE, url -> {});
        keys.addAll(List.of("sculptory.wiki.search_entry", "sculptory.wiki.tool_help.tooltip",
                EditorWindows.titleKey(EditorWindows.WIKI)));
        assertTrue(keys.containsAll(List.of("sculptory.wiki.picture.tooltip", "sculptory.wiki.picture.close",
                "sculptory.wiki.picture_not_found")), keys.toString());
        assertTrue(keys.size() >= 17, keys.toString());
        for (String key : keys) {
            assertTrue(English.INSTANCE.has(key), key);
        }
    }

    @Test
    void nothingRunsPastThePageFrom200To700UnitsWideAtUi50And100() {
        checkNothingRunsPast(List.of("home", "getting-started", "constructs", "shape-brush", "terrain-brushes",
                "orphan"), List.of(200, 260, 420, 560, 700));
    }

    @Test
    void norOnTheRepositorysOwnPages() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("docs").resolve("wiki"))) {
            dir = dir.getParent();
        }
        assertTrue(dir != null, "docs/wiki above " + Path.of("").toAbsolutePath());
        WikiLibrary library = new WikiLibrary(new DirectoryWikiSource(dir.resolve("docs").resolve("wiki")));
        FakePictures all = new FakePictures();
        List<String> ids = new ArrayList<>();
        for (WikiPage page : library.pages()) {
            ids.add(page.id());
            page.pictures().forEach(use -> all.with(use.path(), 900, 500));
        }
        window.setWiki(library, all, url -> {});
        checkNothingRunsPast(ids, List.of(200, WikiWindow.WIDTH, 700));
    }

    private void checkNothingRunsPast(List<String> ids, List<Integer> widths) {
        for (int size : List.of(50, 100)) {
            uiSize(size);
            for (String id : ids) {
                for (int width : widths) {
                    window.open(id, null);
                    layout(width, 3000);
                    RecordingGraphics g = render();
                    Rect page = window.pageView().bounds();
                    String at = id + " at " + width + " wide, UI " + size + "%";
                    for (RecordingGraphics.DrawnText text : g.drawnTextPositions()) {
                        int right = text.x() + (int) Math.ceil(McFontText.INSTANCE.width(text.text()) * text.scale());
                        assertTrue(text.x() >= 0 && right <= width + 1,
                                () -> at + ": \"" + text.text() + "\" runs past the window");
                        if (text.y() >= page.y()) {
                            assertTrue(text.x() >= page.x() && right <= page.right() + 1,
                                    () -> at + ": \"" + text.text() + "\" runs past the page");
                        }
                    }
                    for (RecordingGraphics.Texture texture : g.drawnTextures()) {
                        assertTrue(texture.rect().x() >= page.x() && texture.rect().right() <= page.right(),
                                at + ": " + texture);
                    }
                    for (Rect fill : g.filledRects()) {
                        assertTrue(fill.x() >= 0 && fill.right() <= width + 1, at + ": " + fill);
                    }
                }
            }
        }
    }
}
