package dev.sculptory.fabric.client.editor.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.FlexLayout;
import dev.sculptory.fabric.client.editor.ui.layout.Padding;
import dev.sculptory.fabric.client.editor.ui.layout.Row;
import dev.sculptory.fabric.client.editor.ui.layout.Spacer;
import dev.sculptory.fabric.client.editor.ui.layout.Stack;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.CollapsibleSection;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import java.util.List;
import org.junit.jupiter.api.Test;

class LayoutTest {
    private final UiContext ctx = FakeTextMeasure.context();
    private final int gap = Theme.DARK.gap;

    @Test
    void rowMeasuresSumOfWidthsPlusGapsAndTallestChild() {
        Row row = Row.of(new Spacer(10, 5), new Spacer(20, 8), new Spacer(30, 2));
        assertEquals(new Size(60 + 2 * gap, 8), row.measure(ctx, Integer.MAX_VALUE));
    }

    @Test
    void columnMeasuresWidestChildAndSumOfHeightsPlusGaps() {
        Column column = Column.of(new Spacer(10, 5), new Spacer(20, 8));
        assertEquals(new Size(20, 13 + gap), column.measure(ctx, Integer.MAX_VALUE));
    }

    @Test
    void explicitGapOverridesTheme() {
        Row row = Row.of(new Spacer(10, 5), new Spacer(10, 5));
        row.setGap(0);
        assertEquals(20, row.measure(ctx, Integer.MAX_VALUE).width());
    }

    @Test
    void rowPlacesChildrenLeftToRight() {
        Spacer a = new Spacer(10, 5);
        Spacer b = new Spacer(20, 5);
        Row row = Row.of(a, b);
        row.layout(ctx, new Rect(100, 50, 200, 5));
        assertEquals(new Rect(100, 50, 10, 5), a.bounds());
        assertEquals(new Rect(110 + gap, 50, 20, 5), b.bounds());
    }

    @Test
    void growSharesSpareSpaceByWeight() {
        Spacer fixed = new Spacer(20, 5);
        Spacer one = (Spacer) new Spacer(0, 5).setGrow(1);
        Spacer three = (Spacer) new Spacer(0, 5).setGrow(3);
        Row row = Row.of(fixed, one, three);
        row.setGap(4);
        row.layout(ctx, new Rect(0, 0, 120, 5));
        // 120 - 20 fixed - 8 gap = 92 spare, split 1:3.
        assertEquals(20, fixed.bounds().width());
        assertEquals(23, one.bounds().width());
        assertEquals(69, three.bounds().width());
        assertEquals(120, three.bounds().right());
    }

    @Test
    void shortSpaceShrinksInProportionToSlackButNotBelowMinimum() {
        Spacer a = (Spacer) new Spacer(100, 5).setMinSize(40, 0);
        Spacer b = (Spacer) new Spacer(100, 5).setMinSize(90, 0);
        Row row = Row.of(a, b);
        row.setGap(0);
        row.layout(ctx, new Rect(0, 0, 150, 5));
        assertEquals(150, a.bounds().width() + b.bounds().width());
        assertTrue(a.bounds().width() >= 40);
        assertTrue(b.bounds().width() >= 90);
        assertTrue(a.bounds().width() < 100 && b.bounds().width() < 100);
    }

    @Test
    void minimumsThatDoNotFitOverflowAtTheirMinimums() {
        int[] sizes = FlexLayout.distribute(50, 0, new int[] {100, 100}, new int[] {40, 30}, new float[] {0, 0});
        assertEquals(40, sizes[0]);
        assertEquals(30, sizes[1]);
    }

    @Test
    void distributeGivesRoundingRemainderToLastGrowingItem() {
        int[] sizes = FlexLayout.distribute(10, 0, new int[] {0, 0, 0}, new int[] {0, 0, 0}, new float[] {1, 1, 1});
        assertEquals(10, sizes[0] + sizes[1] + sizes[2]);
        assertEquals(3, sizes[0]);
        assertEquals(4, sizes[2]);
    }

    @Test
    void minSizeRaisesMeasureAndFixedSizeOverridesIt() {
        Spacer spacer = new Spacer(10, 5);
        spacer.setMinSize(50, 20);
        assertEquals(new Size(50, 20), spacer.measure(ctx, Integer.MAX_VALUE));
        spacer.setFixedSize(30, 12);
        assertEquals(new Size(30, 12), spacer.measure(ctx, Integer.MAX_VALUE));
        assertEquals(30, spacer.minWidth());
    }

    @Test
    void paddingAddsInsetsAroundChild() {
        Spacer child = new Spacer(10, 10);
        Padding padding = new Padding(new Insets(1, 2, 3, 4), child);
        assertEquals(new Size(14, 16), padding.measure(ctx, Integer.MAX_VALUE));
        padding.layout(ctx, new Rect(0, 0, 50, 50));
        assertEquals(new Rect(1, 2, 46, 44), child.bounds());
    }

    @Test
    void columnStretchesChildrenAcrossItsWidth() {
        Button button = new Button("Go", null);
        Column column = Column.of(button);
        column.layout(ctx, new Rect(0, 0, 150, 100));
        assertEquals(150, button.bounds().width());
        assertEquals(Theme.DARK.controlHeight, button.bounds().height());
    }

    @Test
    void columnKeepsFixedWidthChildrenAtTheirWidth() {
        Button button = new Button("Go", null);
        button.setFixedWidth(40);
        Column column = Column.of(button);
        column.layout(ctx, new Rect(0, 0, 150, 100));
        assertEquals(40, button.bounds().width());
    }

    @Test
    void rowCentresShorterChildrenVertically() {
        Label label = Label.of("Radius");
        Button button = new Button("Go", null);
        Row row = Row.of(label, button);
        row.layout(ctx, new Rect(0, 0, 200, 16));
        assertEquals(9, label.bounds().height());
        assertEquals(3, label.bounds().y());
        assertEquals(0, button.bounds().y());
    }

    @Test
    void hiddenChildrenTakeNoSpace() {
        Spacer a = new Spacer(10, 5);
        Spacer hidden = new Spacer(50, 5);
        hidden.setVisible(false);
        Spacer b = new Spacer(10, 5);
        Row row = Row.of(a, hidden, b);
        assertEquals(20 + gap, row.measure(ctx, Integer.MAX_VALUE).width());
        row.layout(ctx, new Rect(0, 0, 100, 5));
        assertEquals(10 + gap, b.bounds().x());
    }

    @Test
    void stackMeasuresLargestChildAndOverlaysChildren() {
        Spacer small = new Spacer(10, 30);
        Spacer wide = new Spacer(40, 5);
        Stack stack = Stack.of(small, wide);
        assertEquals(new Size(40, 30), stack.measure(ctx, Integer.MAX_VALUE));
        stack.layout(ctx, new Rect(5, 5, 40, 30));
        assertEquals(stack.bounds(), small.bounds());
        assertEquals(stack.bounds(), wide.bounds());
    }

    @Test
    void labelCutsWithEllipsisAndOffersFullTextAsTooltip() {
        Label label = Label.of("abcdefghij");
        assertEquals(new Size(60, 9), label.measure(ctx, Integer.MAX_VALUE));
        label.layout(ctx, new Rect(0, 0, 40, 9));
        assertEquals("abcdefghij", label.tooltip());
        RecordingGraphics g = new RecordingGraphics();
        label.render(g, ctx);
        assertEquals(List.of("abc..."), g.texts);

        label.layout(ctx, new Rect(0, 0, 60, 9));
        assertNull(label.tooltip());
    }

    @Test
    void wrappingLabelGrowsDownwards() {
        Label label = Label.of("one two three").setWrap(true);
        Size size = label.measure(ctx, 48);
        assertEquals(List.of("one two", "three"), TextLayout.wrap(FakeTextMeasure.INSTANCE, "one two three", 48));
        assertEquals(2 * 9 + Theme.DARK.lineSpacing, size.height());
        assertTrue(size.width() <= 48);
    }

    @Test
    void wrapBreaksWordsLongerThanALine() {
        assertEquals(List.of("abcd", "efgh", "ij"), TextLayout.wrap(FakeTextMeasure.INSTANCE, "abcdefghij", 24));
        assertEquals(List.of("a", "", "b"), TextLayout.wrap(FakeTextMeasure.INSTANCE, "a\n\nb", 24));
        assertEquals(List.of("x"), TextLayout.wrap(FakeTextMeasure.INSTANCE, "x", 0));
    }

    @Test
    void collapsedSectionMeasuresOnlyItsHeader() {
        Spacer body = new Spacer(10, 100);
        CollapsibleSection section = new CollapsibleSection("More", body, false);
        assertEquals(Theme.DARK.controlHeight, section.measure(ctx, 200).height());
        assertTrue(section.children().isEmpty());
        section.setExpanded(true);
        assertEquals(Theme.DARK.controlHeight + gap + 100, section.measure(ctx, 200).height());
        section.layout(ctx, new Rect(0, 0, 200, section.measure(ctx, 200).height()));
        assertEquals(Theme.DARK.sectionIndent, body.bounds().x());
        assertEquals(Theme.DARK.controlHeight + gap, body.bounds().y());
    }

    @Test
    void nodeCanHaveOnlyOneParent() {
        Spacer shared = new Spacer(1, 1);
        Row.of(shared);
        boolean threw = false;
        try {
            Column.of(shared);
        } catch (IllegalStateException expected) {
            threw = true;
        }
        assertTrue(threw);
    }

    @Test
    void rectHelpers() {
        Rect rect = new Rect(10, 10, 20, 20);
        assertTrue(rect.contains(10, 10));
        assertFalse(rect.contains(30, 30));
        assertEquals(new Rect(15, 15, 15, 15), rect.intersect(new Rect(15, 15, 100, 100)));
        assertTrue(rect.intersect(new Rect(100, 100, 5, 5)).isEmpty());
        assertEquals(0, new Rect(0, 0, -5, 3).width());
    }
}
