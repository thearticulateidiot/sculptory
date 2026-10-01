package dev.sculptory.fabric.client.editor.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.FlowRow;
import dev.sculptory.fabric.client.editor.ui.layout.Spacer;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import java.util.List;
import org.junit.jupiter.api.Test;

class FlowRowTest {
    private final UiContext ctx = FakeTextMeasure.context();
    private final int gap = Theme.DARK.gap;

    private static Spacer box(int width, int height) {
        return new Spacer(width, height);
    }

    @Test
    void unconstrainedItIsOneRow() {
        FlowRow row = FlowRow.of(box(30, 10), box(40, 12), box(50, 8));
        assertEquals(new Size(120 + 2 * gap, 12), row.measure(ctx, Integer.MAX_VALUE));
    }

    @Test
    void childrenThatDoNotFitWrapOntoTheNextLine() {
        Spacer a = box(30, 10);
        Spacer b = box(40, 12);
        Spacer c = box(50, 8);
        FlowRow row = FlowRow.of(a, b, c);
        // 30 + gap + 40 fits in 80; adding 50 more does not.
        assertEquals(List.of(List.of(a, b), List.of(c)), row.lines(ctx, 80));
        assertEquals(new Size(70 + gap, 12 + gap + 8), row.measure(ctx, 80));

        row.layout(ctx, new Rect(10, 20, 80, 12 + gap + 8));
        assertEquals(new Rect(10, 20 + 1, 30, 10), a.bounds(), "centred in its line");
        assertEquals(new Rect(10 + 30 + gap, 20, 40, 12), b.bounds());
        assertEquals(new Rect(10, 20 + 12 + gap, 50, 8), c.bounds(), "the second line starts at the left");
    }

    @Test
    void theWrapPointIsExact() {
        Spacer a = box(30, 10);
        Spacer b = box(30, 10);
        FlowRow row = FlowRow.of(a, b);
        row.setGap(4);
        assertEquals(1, row.lines(ctx, 64).size(), "30 + 4 + 30 = 64 fits");
        assertEquals(2, row.lines(ctx, 63).size(), "one pixel less wraps");
        assertEquals(new Size(30, 24), row.measure(ctx, 63), "the gap separates the lines too");
    }

    @Test
    void growingChildrenShareTheirLinesSpareWidth() {
        Spacer a = (Spacer) box(30, 10).setGrow(1);
        Spacer b = (Spacer) box(30, 10).setGrow(1);
        Spacer c = (Spacer) box(30, 10).setGrow(1);
        FlowRow row = FlowRow.of(a, b, c);
        row.setGap(4);
        row.layout(ctx, new Rect(0, 0, 70, 24));
        assertEquals(33, a.bounds().width(), "70 - 4 = 66 shared by the two on the first line");
        assertEquals(33, b.bounds().width());
        assertEquals(new Rect(0, 14, 70, 10), c.bounds(), "alone on its line, it takes all of it");
    }

    @Test
    void aChildWiderThanTheRowGetsALineAndShrinksToItsMinimum() {
        Spacer a = box(20, 10);
        Spacer wide = (Spacer) box(200, 10).setMinSize(60, 0);
        Spacer c = box(20, 10);
        FlowRow row = FlowRow.of(a, wide, c);
        row.setGap(4);
        assertEquals(List.of(List.of(a), List.of(wide), List.of(c)), row.lines(ctx, 100));
        row.layout(ctx, new Rect(0, 0, 100, 38));
        assertEquals(100, wide.bounds().width(), "cut to the row's width");
        row.layout(ctx, new Rect(0, 0, 40, 38));
        assertEquals(60, wide.bounds().width(), "never below its minimum");
    }

    @Test
    void hiddenChildrenTakeNoPlace() {
        Spacer a = box(30, 10);
        Spacer hidden = box(30, 10);
        hidden.setVisible(false);
        Spacer c = box(30, 10);
        FlowRow row = FlowRow.of(a, hidden, c);
        row.setGap(4);
        assertEquals(new Size(64, 10), row.measure(ctx, 64));
    }

    @Test
    void aLineThatDoesNotGrowFollowsTheAlignment() {
        Spacer a = box(30, 10);
        FlowRow row = FlowRow.of(a);
        row.setAlign(Align.END);
        row.layout(ctx, new Rect(0, 0, 100, 10));
        assertEquals(70, a.bounds().x());
        row.setAlign(Align.CENTER);
        row.layout(ctx, new Rect(0, 0, 100, 10));
        assertEquals(35, a.bounds().x());
    }

    @Test
    void buttonsKeepTheirWholeLabelsInANarrowColumn() {
        Button fill = new Button("Fill", null);
        Button replace = new Button("Replace...", null);
        Button erase = new Button("Erase", null);
        for (Button button : List.of(fill, replace, erase)) {
            button.setGrow(1);
        }
        FlowRow row = FlowRow.of(fill, replace, erase);
        Column column = Column.of(row);
        // Fill (36) + Replace... (72) fit in 120 with the gap; Erase (42) does not.
        int width = 120;
        int height = column.measure(ctx, width).height();
        column.layout(ctx, new Rect(0, 0, width, height));
        for (Button button : List.of(fill, replace, erase)) {
            int needed = FakeTextMeasure.INSTANCE.width(button.text()) + 2 * Theme.DARK.controlPaddingX;
            assertTrue(button.bounds().width() >= needed, button.text() + " is not cut: " + button.bounds());
        }
        assertTrue(erase.bounds().y() > fill.bounds().y(), "Erase went to the second line");
        assertEquals(2 * Theme.DARK.controlHeight + gap, height, "two lines tall");
    }
}
