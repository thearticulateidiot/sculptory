package dev.sculptory.fabric.client.editor.settings.form;

import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.layout.Container;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Slider;

/**
 * A setting's first line: its control (or its name, above the control) as wide as the line, and its reset button at
 * the line's right end while the setting differs from its default. Nothing is kept for the button while it is
 * hidden, so the control reaches the right edge like every other row. When it shows, it sits beside the control,
 * which gets the rest of the line; or over a slider's right end, the slider keeping its value left of it, so a slider
 * never changes length (nor the value under the pointer) when the button appears in the middle of a drag.
 */
final class SettingLine extends Container {
    private static final int GAP = 2;

    private final Node main;
    private final Button reset;
    private final Slider slider;

    SettingLine(Node main, Button reset) {
        this.main = main;
        this.reset = reset;
        this.slider = main instanceof Slider over ? over : null;
        add(main, reset);
    }

    /** What the button takes beside the control while it shows. */
    private int beside(UiContext ctx) {
        return reset.isVisible() && slider == null ? ctx.theme().resetButtonWidth + GAP : 0;
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        int beside = beside(ctx);
        Size size = main.measure(ctx, maxWidth == Integer.MAX_VALUE ? maxWidth : Math.max(0, maxWidth - beside));
        return new Size(size.width() + beside, size.height());
    }

    @Override
    public void layout(UiContext ctx, Rect bounds) {
        super.layout(ctx, bounds);
        int width = ctx.theme().resetButtonWidth;
        if (slider != null) {
            slider.setTrailingInset(reset.isVisible() ? width : 0);
        }
        main.layout(ctx, bounds.withSize(Math.max(0, bounds.width() - beside(ctx)), bounds.height()));
        if (reset.isVisible()) {
            // As tall as the line up to a control's height: a heading's name is shorter than a slider.
            reset.layout(ctx, new Rect(bounds.right() - width, bounds.y(), width,
                    Math.min(bounds.height(), ctx.theme().controlHeight)));
        }
    }
}
