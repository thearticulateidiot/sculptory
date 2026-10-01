package dev.sculptory.fabric.client.editor.ui.layout;

import dev.sculptory.fabric.client.editor.ui.Align;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import java.util.List;

/** Lays children out in a line with a gap. Base of {@link Row} and {@link Column}. */
public abstract class FlexContainer extends Container {
    private final boolean horizontal;
    private int gap = -1;
    private Align crossAlign;

    protected FlexContainer(boolean horizontal, Align crossAlign) {
        this.horizontal = horizontal;
        this.crossAlign = crossAlign;
    }

    /** Pixels between children; negative uses the theme's gap. */
    public FlexContainer setGap(int gap) {
        this.gap = gap;
        return this;
    }

    /** How children are placed across the main axis. */
    public FlexContainer setCrossAlign(Align align) {
        this.crossAlign = align;
        return this;
    }

    public final int gap(UiContext ctx) {
        return gap >= 0 ? gap : ctx.theme().gap;
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        int main = 0;
        int cross = 0;
        List<Node> children = visibleChildren();
        for (Node child : children) {
            Size size = child.measure(ctx, maxWidth);
            main += horizontal ? size.width() : size.height();
            cross = Math.max(cross, horizontal ? size.height() : size.width());
        }
        main += gap(ctx) * Math.max(0, children.size() - 1);
        return horizontal ? new Size(main, cross) : new Size(cross, main);
    }

    @Override
    public void layout(UiContext ctx, Rect bounds) {
        super.layout(ctx, bounds);
        List<Node> children = visibleChildren();
        int count = children.size();
        if (count == 0) {
            return;
        }
        int crossSpace = horizontal ? bounds.height() : bounds.width();
        int[] preferred = new int[count];
        int[] minimum = new int[count];
        int[] crossPreferred = new int[count];
        float[] grow = new float[count];
        for (int i = 0; i < count; i++) {
            Node child = children.get(i);
            Size size = child.measure(ctx, bounds.width());
            preferred[i] = horizontal ? size.width() : size.height();
            crossPreferred[i] = horizontal ? size.height() : size.width();
            minimum[i] = horizontal ? child.minWidth() : child.minHeight();
            grow[i] = child.grow();
        }
        int gap = gap(ctx);
        int[] mains = FlexLayout.distribute(horizontal ? bounds.width() : bounds.height(), gap,
                preferred, minimum, grow);
        int position = horizontal ? bounds.x() : bounds.y();
        for (int i = 0; i < count; i++) {
            Node child = children.get(i);
            boolean fixedCross = horizontal ? child.hasFixedHeight() : child.hasFixedWidth();
            int crossSize = crossAlign == Align.STRETCH && !fixedCross
                    ? crossSpace
                    : Math.min(crossPreferred[i], crossSpace);
            int crossOffset = crossAlign.offset(crossSpace, crossSize);
            Rect rect = horizontal
                    ? new Rect(position, bounds.y() + crossOffset, mains[i], crossSize)
                    : new Rect(bounds.x() + crossOffset, position, crossSize, mains[i]);
            child.layout(ctx, rect);
            position += mains[i] + gap;
        }
    }
}
