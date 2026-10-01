package dev.sculptory.fabric.client.editor.wiki;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.PopupLayer;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import java.util.Objects;

/**
 * A wiki picture shown full size over the editor (a click on its thumbnail): a popup centred on the dimmed screen,
 * the picture at its own size (one of its pixels to one of the screen's) or smaller to fit the screen, never larger,
 * with its alt text under it. A click anywhere or Esc closes it (Esc closes the top popup). It closes with the page
 * view that opened it (the Wiki window closing or hiding).
 */
public final class PictureOverlay extends Node {
    /** Space between the picture and the screen's edges, in UI units. */
    static final int MARGIN = 16;
    /** Space between the picture and its caption, and under the caption. */
    static final int CAPTION_GAP = 4;

    private final WikiPictures.Picture picture;
    private final String alt;
    private final double pixelsPerUnit;
    private PopupLayer popups;
    private PopupLayer.Popup popup;

    private PictureOverlay(WikiPictures.Picture picture, String alt, double pixelsPerUnit) {
        this.picture = Objects.requireNonNull(picture);
        this.alt = alt == null ? "" : alt.strip();
        this.pixelsPerUnit = pixelsPerUnit > 0 ? pixelsPerUnit : 1;
    }

    /**
     * Shows {@code picture} full size: {@code pixelsPerUnit} is the screen's pixels per UI unit, {@code owner} the node
     * that opened it (the overlay closes when it goes away).
     */
    public static PictureOverlay open(UiContext ctx, Node owner, WikiPictures.Picture picture, String alt,
            double pixelsPerUnit, Translator tr) {
        PictureOverlay overlay = new PictureOverlay(picture, alt, pixelsPerUnit);
        overlay.setTooltip(tr.translate("sculptory.wiki.picture.close"));
        Size size = overlay.measure(ctx, ctx.screenWidth());
        int width = size.width() + 2;
        int height = size.height() + 2;
        Rect anchor = new Rect(Math.max(0, (ctx.screenWidth() - width) / 2),
                Math.max(0, (ctx.screenHeight() - height) / 2), 0, 0);
        overlay.popups = ctx.popups();
        overlay.popup = ctx.popups().open(owner, overlay, anchor, width, null);
        overlay.popup.setBackdrop(ctx.theme().backdrop);
        ctx.clearFocus();
        return overlay;
    }

    public WikiPictures.Picture picture() {
        return picture;
    }

    /** The picture's size in UI units on a screen {@code screenWidth} x {@code screenHeight} units. */
    int[] pictureSize(int screenWidth, int screenHeight, int captionHeight) {
        double nativeWidth = picture.width() / pixelsPerUnit;
        double nativeHeight = picture.height() / pixelsPerUnit;
        double roomWidth = Math.max(1, screenWidth - 2 * MARGIN - 2);
        double roomHeight = Math.max(1, screenHeight - 2 * MARGIN - 2 - captionHeight);
        double fit = Math.min(1, Math.min(roomWidth / nativeWidth, roomHeight / nativeHeight));
        int width = Math.max(1, (int) Math.floor(nativeWidth * fit + 1e-6));
        int height = Math.max(1, (int) Math.round(nativeHeight * width / nativeWidth));
        return new int[] {width, Math.min(height, Math.max(1, (int) Math.floor(roomHeight)))};
    }

    private int captionHeight(UiContext ctx) {
        return alt.isEmpty() ? 0 : CAPTION_GAP + ctx.text().lineHeight() + CAPTION_GAP;
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        int caption = captionHeight(ctx);
        int[] size = pictureSize(ctx.screenWidth(), ctx.screenHeight(), caption);
        int width = size[0];
        if (caption > 0) {
            // As wide as the caption where the picture is narrower (within the screen).
            width = Math.max(width, Math.min(ctx.text().width(alt) + 8, ctx.screenWidth() - 2 * MARGIN - 2));
        }
        return new Size(width, size[1] + caption);
    }

    @Override
    public void render(UiGraphics g, UiContext ctx) {
        Theme theme = ctx.theme();
        int caption = captionHeight(ctx);
        int[] size = pictureSize(ctx.screenWidth(), ctx.screenHeight(), caption);
        int width = Math.min(size[0], bounds.width());
        int height = Math.min(size[1], Math.max(1, bounds.height() - caption));
        int x = bounds.x() + (bounds.width() - width) / 2;
        g.texture(picture.texture(), x, bounds.y(), width, height, picture.width(), picture.height());
        if (caption > 0) {
            String text = TextLayout.ellipsize(ctx.text(), alt, bounds.width() - 4);
            int textX = bounds.x() + (bounds.width() - ctx.text().width(text)) / 2;
            g.text(text, textX, bounds.y() + height + CAPTION_GAP, theme.textDim, theme.textShadow);
        }
    }

    /** A click anywhere on it closes it (a click beside it closes it too, as outside any popup). */
    @Override
    public boolean mouseDown(UiContext ctx, double x, double y, int button) {
        close();
        return true;
    }

    /** Closes the overlay if it is still open. */
    public void close() {
        if (popups != null) {
            popups.close(popup);
        }
    }

    public boolean isOpen() {
        return popups != null && popups.popups().contains(popup);
    }
}
