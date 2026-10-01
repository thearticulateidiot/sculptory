package dev.sculptory.fabric.client.editor.wiki;

/**
 * How large a wiki page is drawn on this screen. A page is laid out in <em>page units</em> and drawn {@link #scale}
 * UI units per page unit: at small editor UI sizes its text is a third larger than the rest of the UI (never larger
 * than at UI 100%), so it stays readable. Each scale is chosen so a pixel of Minecraft's font covers a whole number of
 * the screen's pixels where it can, since the font is drawn without smoothing and uneven pixels look ragged.
 *
 * @param scale UI units per page unit (1 at UI 100% and above)
 * @param pixelsPerUnit the screen's pixels per UI unit: the GUI scale times the editor UI size
 */
public record PageScale(float scale, double pixelsPerUnit) {
    /** What the page's text aims at: this much larger than the UI's own... */
    static final double TEXT = 4.0 / 3.0;
    /** ...and {@code #}/{@code ##} headings this much larger than the page's text. */
    static final double HEADING = 1.25;

    /** At UI 100% on a screen of one pixel per unit: page units are UI units. */
    public static final PageScale PLAIN = new PageScale(1, 1);

    public PageScale {
        if (!(scale >= 1) || !(pixelsPerUnit > 0)) {
            throw new IllegalArgumentException("scale " + scale + ", pixels per unit " + pixelsPerUnit);
        }
    }

    /**
     * The scale for a screen of {@code pixelsPerUnit} pixels per UI unit at the editor UI size {@code uiFactor}
     * (1 at 100%).
     */
    public static PageScale of(double pixelsPerUnit, double uiFactor) {
        double pixels = pixelsPerUnit > 0 && Double.isFinite(pixelsPerUnit) ? pixelsPerUnit : 1;
        double factor = uiFactor > 0 && Double.isFinite(uiFactor) ? uiFactor : 1;
        double target = Math.min(TEXT, 1 / factor);
        float scale = target <= 1 ? 1 : snapped(pixels, target);
        return new PageScale(scale, pixels);
    }

    /** A factor of at least 1 near {@code target} that makes {@code pixels} times it a whole number where it can. */
    static float snapped(double pixels, double target) {
        long whole = Math.round(pixels * target);
        double factor = whole / pixels;
        return factor >= 1 ? (float) factor : 1;
    }

    /** The screen's pixels per page unit. */
    public double pagePixels() {
        return pixelsPerUnit * scale;
    }

    /** How much larger than the page's text {@code #} and {@code ##} headings are drawn. */
    public float headingScale() {
        return snapped(pagePixels(), HEADING);
    }

    /** How large (against the page's text) a picture's caption is drawn: the UI's own text size. */
    public float captionScale() {
        return 1 / scale;
    }

    /** Page units to UI units, rounded up. */
    public int toUi(int pageUnits) {
        return (int) Math.ceil(pageUnits * (double) scale - 1e-6);
    }

    /** UI units to whole page units, rounded down. */
    public int toPage(int uiUnits) {
        return (int) Math.floor(uiUnits / (double) scale + 1e-6);
    }
}
