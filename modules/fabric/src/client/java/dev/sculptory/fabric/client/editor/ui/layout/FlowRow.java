package dev.sculptory.fabric.client.editor.ui.layout;

import dev.sculptory.fabric.client.editor.ui.Align;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import java.util.ArrayList;
import java.util.List;

/**
 * Children left to right, wrapping onto a new line when the next one doesn't fit, instead of shrinking them until
 * their text is cut ("Fi…"). Each line then lays out like a {@link Row}: children that grow share the line's spare
 * width ({@link FlexLayout}), the others keep their width and the line is placed by {@link #setAlign} (start by
 * default); children are centred vertically in their line. The gap between children and between lines is the
 * theme's unless set. A child wider than the whole row gets a line of its own and shrinks to fit, down to its
 * minimum.
 */
public final class FlowRow extends Container {
    private int gap = -1;
    private Align align = Align.START;

    public static FlowRow of(Node... children) {
        FlowRow row = new FlowRow();
        row.add(children);
        return row;
    }

    /** Pixels between children and between lines; negative uses the theme's gap. */
    public FlowRow setGap(int gap) {
        this.gap = gap;
        return this;
    }

    /** Where a line whose children don't grow sits in the row's width. */
    public FlowRow setAlign(Align align) {
        this.align = align;
        return this;
    }

    public int gap(UiContext ctx) {
        return gap >= 0 ? gap : ctx.theme().gap;
    }

    /** The children on each line for {@code width} pixels, in order (pure; used by measure and layout). */
    public List<List<Node>> lines(UiContext ctx, int width) {
        int gap = gap(ctx);
        List<List<Node>> lines = new ArrayList<>();
        List<Node> line = new ArrayList<>();
        int used = 0;
        for (Node child : visibleChildren()) {
            int preferred = child.measure(ctx, width).width();
            int needed = line.isEmpty() ? preferred : used + gap + preferred;
            if (!line.isEmpty() && needed > width) {
                lines.add(line);
                line = new ArrayList<>();
                needed = preferred;
            }
            line.add(child);
            used = needed;
        }
        if (!line.isEmpty()) {
            lines.add(line);
        }
        return lines;
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        int gap = gap(ctx);
        int width = 0;
        int height = 0;
        List<List<Node>> lines = lines(ctx, maxWidth);
        for (List<Node> line : lines) {
            int lineWidth = gap * (line.size() - 1);
            int lineHeight = 0;
            for (Node child : line) {
                Size size = child.measure(ctx, maxWidth);
                lineWidth += size.width();
                lineHeight = Math.max(lineHeight, size.height());
            }
            width = Math.max(width, Math.min(lineWidth, maxWidth));
            height += lineHeight;
        }
        height += gap * Math.max(0, lines.size() - 1);
        return new Size(width, height);
    }

    @Override
    public void layout(UiContext ctx, Rect bounds) {
        super.layout(ctx, bounds);
        int gap = gap(ctx);
        int y = bounds.y();
        for (List<Node> line : lines(ctx, bounds.width())) {
            int count = line.size();
            int[] preferred = new int[count];
            int[] minimum = new int[count];
            int[] heights = new int[count];
            float[] grow = new float[count];
            int lineHeight = 0;
            int total = gap * (count - 1);
            boolean grows = false;
            for (int i = 0; i < count; i++) {
                Node child = line.get(i);
                Size size = child.measure(ctx, bounds.width());
                preferred[i] = size.width();
                minimum[i] = child.minWidth();
                heights[i] = size.height();
                grow[i] = child.grow();
                grows |= grow[i] > 0;
                lineHeight = Math.max(lineHeight, size.height());
                total += size.width();
            }
            int[] widths = FlexLayout.distribute(bounds.width(), gap, preferred, minimum, grow);
            int x = bounds.x() + (grows || total >= bounds.width() ? 0 : align.offset(bounds.width(), total));
            for (int i = 0; i < count; i++) {
                int height = Math.min(heights[i], lineHeight);
                line.get(i).layout(ctx, new Rect(x, y + (lineHeight - height) / 2, widths[i], height));
                x += widths[i] + gap;
            }
            y += lineHeight + gap;
        }
    }
}
