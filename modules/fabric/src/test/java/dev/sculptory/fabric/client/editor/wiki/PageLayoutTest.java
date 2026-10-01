package dev.sculptory.fabric.client.editor.wiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.English;
import dev.sculptory.fabric.client.editor.ui.McFontText;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Theme;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * A page's layout in page units with Minecraft's font widths: line spacing, space between blocks and above headings,
 * heading sizes, code chips, links, callouts, thumbnails and captions; and the page scale on the reference screen.
 */
class PageLayoutTest {
    private static final Theme THEME = Theme.DARK;
    /** The reference screen: GUI scale 6, at UI 50% and 100%. */
    private static final PageScale AT_50 = PageScale.of(3, 0.5);
    private static final PageScale AT_100 = PageScale.of(6, 1.0);

    private static PageLayout layout(String markdown, int width, PageScale scale, WikiPictures pictures) {
        return PageLayout.build(WikiParser.parse("page", markdown), width, McFontText.INSTANCE, THEME, pictures,
                English.INSTANCE, true, scale);
    }

    private static PageLayout layout(String markdown, int width) {
        return layout(markdown, width, AT_50, WikiPictures.NONE);
    }

    private static List<PageLayout.TextOp> texts(PageLayout layout) {
        return layout.ops.stream().filter(op -> op instanceof PageLayout.TextOp).map(op -> (PageLayout.TextOp) op)
                .toList();
    }

    private static PageLayout.TextOp text(PageLayout layout, String text) {
        return texts(layout).stream().filter(op -> op.text().equals(text)).findFirst()
                .orElseThrow(() -> new AssertionError("No \"" + text + "\" in " + texts(layout)));
    }

    @Test
    void theOwnersScreenDrawsPageTextAThirdLargerOnWholePixelsAtUi50() {
        assertEquals(4.0 / 3, AT_50.scale(), 1e-5, "a font pixel on 4 screen pixels, not 3");
        assertEquals(4.0, AT_50.pagePixels(), 1e-5);
        assertEquals(1.25, AT_50.headingScale(), 1e-5, "headings on 5");
        assertEquals(1.0, AT_50.scale() * AT_50.captionScale(), 1e-5, "captions at the UI's own size");
        assertEquals(1.0, AT_100.scale(), "no larger than at UI 100%");
        assertEquals(8.0 / 6, AT_100.headingScale(), 1e-5, "headings on 8 pixels where 7.5 isn't whole");
        assertEquals(1.0, PageScale.of(9, 1.5).scale(), "never smaller either");
        assertEquals(4.0 / 3, PageScale.of(2.25, 0.75).scale(), 1e-5, "UI 75%, GUI scale 3: as at 100%");
        assertEquals(1.5, PageScale.of(2, 0.5).scale(), 1e-5, "GUI scale 4: 3 pixels, not 2.67");
        assertEquals(1.0, PageScale.of(1, 0.5).scale(), "GUI scale 2: 1.33 pixels would be ragged, so 1");
        assertEquals(PageScale.PLAIN, PageScale.of(Double.NaN, 0));
    }

    @Test
    void linesAreAboutOneAndAHalfFontHeightsApartAndParagraphsHaveRoomBetween() {
        PageLayout layout = layout("One two three four five six seven eight nine ten eleven twelve thirteen fourteen"
                + " fifteen sixteen seventeen eighteen.\n\nA second paragraph.", 120);
        TreeSet<Integer> lines = new TreeSet<>();
        texts(layout).forEach(op -> lines.add(op.y()));
        List<Integer> ys = List.copyOf(lines);
        assertTrue(ys.size() >= 4, ys.toString());
        int pitch = ys.get(1) - ys.get(0);
        assertEquals(9 + PageLayout.LINE_GAP, pitch);
        double ratio = pitch / 9.0;
        assertTrue(ratio >= 1.3 && ratio <= 1.5, "line spacing " + ratio);
        int paragraphGap = text(layout, "A").y() - ys.get(ys.size() - 2);
        assertEquals(9 + PageLayout.BLOCK_GAP, paragraphGap);
        assertTrue(paragraphGap - 9 > PageLayout.LINE_GAP + 4, "clear space between paragraphs: " + paragraphGap);
    }

    @Test
    void headingsAreLargerOrBoldWithRoomAboveAndNoRuleUnder() {
        PageLayout layout = layout("# Title\n\nFirst.\n\n## Section\n\nSecond.\n\n### Sub\n\nThird.", 300);
        PageLayout.TextOp section = text(layout, "§lSection");
        assertEquals(1.25f, section.scale(), 1e-5);
        assertEquals((int) Math.ceil(9 * 1.25), section.lineHeight());
        assertEquals(THEME.titleText, section.color());
        assertTrue(section.y() - (text(layout, "First.").y() + 9) >= PageLayout.HEADING_GAP, "room above");
        PageLayout.TextOp sub = text(layout, "§lSub");
        assertEquals(1f, sub.scale(), "### bold at the text's size");
        assertTrue(sub.y() - (text(layout, "Second.").y() + 9) >= PageLayout.SUBHEADING_GAP);
        assertEquals(PageLayout.AFTER_HEADING, text(layout, "Second.").y() - section.bottom());
        assertFalse(texts(layout).stream().anyMatch(op -> op.text().contains("Title")), "the title is the window's");
        assertTrue(layout.ops.stream().noneMatch(op -> op instanceof PageLayout.FillOp), "no rules under headings");
    }

    @Test
    void listItemsAreFurtherApartThanLines() {
        PageLayout layout = layout("- One\n- Two\n  - Nested\n\n1. First\n2. Second", 300);
        int one = text(layout, "One").y();
        int two = text(layout, "Two").y();
        assertEquals(9 + PageLayout.ITEM_GAP, two - one);
        assertTrue(two - one > 9 + PageLayout.LINE_GAP);
        assertEquals(9 + PageLayout.ITEM_GAP, text(layout, "Nested").y() - two);
        assertEquals(9 + PageLayout.BLOCK_GAP, text(layout, "1.").y() - text(layout, "Nested").y());
    }

    @Test
    void codeChipsArePaddedKeyCapsAndLinksAreTheAccentColour() {
        PageLayout layout = layout("Press `Ctrl+K` or see [the page](other.md).", 300);
        PageLayout.TextOp key = text(layout, "Ctrl+K");
        PageLayout.FillOp chip = layout.ops.stream().filter(op -> op instanceof PageLayout.FillOp)
                .map(op -> (PageLayout.FillOp) op).findFirst().orElseThrow();
        assertEquals(McFontText.INSTANCE.width("Ctrl+K") + 2 * PageLayout.CHIP_PAD, chip.rect().width());
        assertEquals(key.x() - PageLayout.CHIP_PAD, chip.rect().x());
        assertEquals(key.y() - PageLayout.CHIP_PAD_Y, chip.rect().y());
        assertTrue(chip.rect().bottom() >= key.y() + 9 + 1, "room under the text");
        assertTrue(chip.rect().bottom() < key.y() + 9 + PageLayout.LINE_GAP, "and apart from the next line's");
        assertEquals(THEME.titleText, key.color(), "bright on the chip");
        assertEquals(THEME.controlHover, chip.color());
        assertEquals(THEME.accentHover, text(layout, "the").color(), "links show without hovering");
        assertEquals(THEME.text, text(layout, "Press").color());
    }

    @Test
    void aCalloutIsAStripBesideItsTextNotABox() {
        PageLayout layout = layout("> **Tip:** a callout\n> on two lines.", 300);
        List<PageLayout.FillOp> fills = layout.ops.stream().filter(op -> op instanceof PageLayout.FillOp)
                .map(op -> (PageLayout.FillOp) op).toList();
        assertEquals(1, fills.size());
        Rect strip = fills.get(0).rect();
        assertEquals(PageLayout.CALLOUT_STRIP, strip.width());
        assertEquals(THEME.accentDim, fills.get(0).color());
        assertEquals(PageLayout.CALLOUT_INDENT, text(layout, "§lTip:").x());
        assertTrue(layout.ops.stream().noneMatch(op -> op instanceof PageLayout.OutlineOp));
    }

    @Test
    void thumbnailsAreAtMostTwoFifthsOfTheColumnAndNeverLargerThanThePicture() {
        // 600x200 pixels at 4 pixels per page unit: 150x50 on its own; 40% of 300 is 120.
        assertEquals(List.of(120, 40), List.of(box(600, 200, 300, 4)));
        // A small picture stays its own size.
        assertEquals(List.of(10, 5), List.of(box(40, 20, 300, 4)));
        // A tall one fits a box three quarters as high as it is wide.
        int[] tall = PageLayout.thumbnail(400, 800, 300, 1);
        assertEquals(90, tall[1]);
        assertEquals(45, tall[0]);
        // A strip four times wider than high may take the column: it stays low.
        assertEquals(List.of(300, 25), List.of(box(1200, 100, 300, 4)));
        // In a narrow column, 80 units (or the column) rather than 40%.
        assertEquals(80, PageLayout.thumbnail(600, 200, 100, 1)[0]);
        assertEquals(60, PageLayout.thumbnail(600, 200, 60, 1)[0]);
    }

    private static Integer[] box(int width, int height, int column, double pagePixels) {
        int[] size = PageLayout.thumbnail(width, height, column, pagePixels);
        return new Integer[] {size[0], size[1]};
    }

    @Test
    void aPictureIsACentredFramedThumbnailWithASmallCaption() {
        FakePictures pictures = new FakePictures().with("images/a.png", 600, 200);
        PageLayout layout = layout("Text.\n\n![What the picture shows](images/a.png)\n\nAfter.", 300, AT_50, pictures);
        assertEquals(1, layout.pictures.size());
        PageLayout.PictureBox box = layout.pictures.get(0);
        int thumbnail = PageLayout.thumbnail(600, 200, 300 - 2, AT_50.pagePixels())[0];
        assertEquals(thumbnail + 2, box.rect().width(), "the thumbnail in a one-unit frame");
        assertTrue(thumbnail <= 300 * 2 / 5, thumbnail + " of 300");
        assertEquals((300 - box.rect().width()) / 2, box.rect().x(), "centred");
        PageLayout.TextOp caption = text(layout, "What the picture shows");
        assertEquals(AT_50.captionScale(), caption.scale(), 1e-5);
        assertEquals(THEME.textDim, caption.color());
        assertEquals(box.rect().bottom() + PageLayout.CAPTION_GAP, caption.y());
        int captionWidth = (int) Math.ceil(McFontText.INSTANCE.width("What the picture shows") * caption.scale());
        assertTrue(Math.abs(caption.x() + captionWidth / 2 - 150) <= 1, "centred");
        assertEquals(PageLayout.BLOCK_GAP, text(layout, "After.").y() - caption.bottom());
    }
}
