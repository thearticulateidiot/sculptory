package dev.sculptory.fabric.client.editor.ui.layout;

import dev.sculptory.fabric.client.editor.ui.Align;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A {@link Row} for a bar with more than it can always show (the editor's top bar): when its width can't hold every
 * child at its preferred width, it hides whole children, in the {@link #setDropOrder order given}, until the rest fit,
 * instead of squeezing every child into "…". What is left still shrinks as a row's children do if even that doesn't
 * fit. The row owns the visibility of the children it may hide: they show again as soon as there is room.
 */
public final class PriorityRow extends FlexContainer {
    private final List<Node> dropOrder = new ArrayList<>();

    public PriorityRow() {
        super(true, Align.CENTER);
    }

    public static PriorityRow of(Node... children) {
        PriorityRow row = new PriorityRow();
        row.add(children);
        return row;
    }

    /** The children this row may hide when short of room, the first to go first. Each must be a child of the row. */
    public PriorityRow setDropOrder(Node... nodes) {
        dropOrder.clear();
        for (Node node : nodes) {
            if (!children().contains(Objects.requireNonNull(node))) {
                throw new IllegalArgumentException("Not a child of this row: " + node);
            }
            dropOrder.add(node);
        }
        return this;
    }

    /** The children hidden to make the rest fit, at the last measure or layout. */
    public List<Node> dropped() {
        return dropOrder.stream().filter(node -> !node.isVisible()).toList();
    }

    /**
     * Shows every child it may hide, then hides them in order while the visible ones' preferred widths exceed
     * {@code width}.
     */
    private void fit(UiContext ctx, int width) {
        for (Node node : dropOrder) {
            node.setVisible(true);
        }
        if (width == Integer.MAX_VALUE) {
            return;
        }
        for (Node node : dropOrder) {
            if (super.measureContent(ctx, Integer.MAX_VALUE).width() <= width) {
                return;
            }
            node.setVisible(false);
        }
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        fit(ctx, maxWidth);
        return super.measureContent(ctx, maxWidth);
    }

    @Override
    public void layout(UiContext ctx, Rect bounds) {
        fit(ctx, bounds.width());
        super.layout(ctx, bounds);
    }
}
