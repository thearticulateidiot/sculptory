package dev.sculptory.fabric.client.editor.ui.widget;

import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;

/**
 * A heading over a group of rows in a list (the Keys window's and the key sheet's key groups): its title in the
 * heading colour on a band as wide as the list ({@link Theme#headingBand}, a little lighter than a Tool Settings
 * section's header, which has its fold arrow) with an accent strip at its left end, so it reads as the start of a
 * group rather than as one more row. One that follows other rows keeps {@link #setSpaceAbove space} above the band,
 * and it may keep {@link #setSpaceBelow some} below it. Narrow, the title is cut with "..." (and is the tooltip, as
 * a label's).
 */
public final class SectionHeading extends Label {
    private int spaceAbove;
    private int spaceBelow;

    public SectionHeading(String title) {
        super(title);
        setStyle(Style.HEADING);
    }

    /** Space kept above the band, in UI units (none by default: the first heading of a list). */
    public SectionHeading setSpaceAbove(int units) {
        this.spaceAbove = Math.max(0, units);
        return this;
    }

    public int spaceAbove() {
        return spaceAbove;
    }

    /** Space kept below the band, before the group's first row, in UI units (none by default). */
    public SectionHeading setSpaceBelow(int units) {
        this.spaceBelow = Math.max(0, units);
        return this;
    }

    public int spaceBelow() {
        return spaceBelow;
    }

    /** Where the band is drawn, from the last layout. */
    public Rect band(UiContext ctx) {
        return new Rect(bounds().x(), bounds().y() + spaceAbove, bounds().width(), ctx.theme().rowHeight);
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        Theme theme = ctx.theme();
        return new Size(theme.headingStripWidth + ctx.text().width(text()) + 2 * theme.rowInset,
                spaceAbove + theme.rowHeight + spaceBelow);
    }

    @Override
    public void render(UiGraphics g, UiContext ctx) {
        Theme theme = ctx.theme();
        Rect band = band(ctx);
        g.fill(band, theme.headingBand);
        g.fill(band.x(), band.y(), theme.headingStripWidth, band.height(), theme.accentDim);
        int x = band.x() + theme.headingStripWidth + theme.rowInset;
        String shown = TextLayout.ellipsize(ctx.text(), text(), band.right() - theme.rowInset - x);
        int y = band.y() + (band.height() - ctx.text().lineHeight() + 1) / 2;
        g.text(shown, x, y, isEffectivelyEnabled() ? theme.titleText : theme.textDisabled, theme.textShadow);
    }
}
