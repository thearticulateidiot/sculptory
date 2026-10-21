package dev.sculptory.mc.client.editor.ui.render;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.sculptory.mc.client.editor.ui.Rect;
import dev.sculptory.mc.client.editor.ui.UiGraphics;
import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * {@link UiGraphics} backed by a vanilla {@link GuiGraphics}. Create one per frame.
 *
 * <p><b>Clipping under a scale.</b> In 1.21.1 {@code DrawContext.enableScissor} ignores the matrix
 * stack (checked with javap: it multiplies the rectangle by the window's GUI scale only). Unscaled
 * clips go through it unchanged. Clips pushed inside {@link #pushScale} (with or without a moved origin) are
 * transformed here instead
 * and kept on this class's own stack in framebuffer pixels, rounded the way the GPU fills pixels
 * (a pixel is inside when its centre is), so a clip edge lines up with a fill drawn at the same UI
 * coordinate at any size. Don't nest a scaled clip inside an unscaled one; the editor never does.
 */
public final class DrawContextGraphics implements UiGraphics {
    private final GuiGraphics context;
    private final Font textRenderer;
    /** The transforms pushed before, each as {scale, originX, originY}. */
    private final Deque<double[]> scales = new ArrayDeque<>();
    /** The pushed transforms together: a unit is drawn at {@code unit * scale + origin} GUI pixels. */
    private double scale = 1;
    private double originX;
    private double originY;
    /** Clips pushed while scaled, as framebuffer pixel edges {left, top, right, bottom} from the top-left. */
    private final Deque<int[]> pixelClips = new ArrayDeque<>();
    /** Per pushed clip, innermost first: true if it is on {@link #pixelClips}, false if on vanilla's stack. */
    private final Deque<Boolean> clipKinds = new ArrayDeque<>();

    public DrawContextGraphics(GuiGraphics context, Font textRenderer) {
        this.context = context;
        this.textRenderer = textRenderer;
    }

    @Override
    public void fill(int x, int y, int width, int height, int argb) {
        if (width > 0 && height > 0) {
            context.fill(x, y, x + width, y + height, argb);
        }
    }

    @Override
    public void outline(int x, int y, int width, int height, int argb) {
        if (width > 0 && height > 0) {
            context.renderOutline(x, y, width, height, argb);
        }
    }

    @Override
    public void text(String text, int x, int y, int argb, boolean shadow) {
        context.drawString(textRenderer, text, x, y, argb, shadow);
    }

    @Override
    public void pushClip(Rect clip) {
        if (scale == 1 && originX == 0 && originY == 0 && pixelClips.isEmpty()) {
            // DrawContext's scissor stack intersects nested clips.
            context.enableScissor(clip.x(), clip.y(), clip.right(), clip.bottom());
            clipKinds.push(false);
            return;
        }
        Window window = Minecraft.getInstance().getWindow();
        double gui = window.getGuiScale();
        int[] edges = pixelEdges(clip, scale * gui, originX * gui, originY * gui, pixelClips.peek());
        pixelClips.push(edges);
        clipKinds.push(true);
        applyPixelClip(edges, window);
    }

    @Override
    public void popClip() {
        if (!clipKinds.pop()) {
            context.disableScissor();
            return;
        }
        pixelClips.pop();
        context.flush();
        int[] outer = pixelClips.peek();
        if (outer == null) {
            RenderSystem.disableScissor();
        } else {
            applyPixelClip(outer, Minecraft.getInstance().getWindow());
        }
    }

    /** Flushes what was drawn under the previous clip, then clips to the pixel edges (GL's origin is bottom-left). */
    private void applyPixelClip(int[] edges, Window window) {
        context.flush();
        int width = Math.max(0, edges[2] - edges[0]);
        int height = Math.max(0, edges[3] - edges[1]);
        RenderSystem.enableScissor(edges[0], window.getHeight() - edges[1] - height, width, height);
    }

    /**
     * A clip in framebuffer pixel edges {left, top, right, bottom}: {@code clip} in UI units times
     * {@code factor} (UI size times GUI scale), intersected with {@code outer} if there is one.
     */
    static int[] pixelEdges(Rect clip, double factor, int[] outer) {
        return pixelEdges(clip, factor, 0, 0, outer);
    }

    /** As {@link #pixelEdges(Rect, double, int[])}, the clip's origin at ({@code offsetX}, {@code offsetY}) pixels. */
    static int[] pixelEdges(Rect clip, double factor, double offsetX, double offsetY, int[] outer) {
        int[] edges = {pixel(clip.x() * factor + offsetX), pixel(clip.y() * factor + offsetY),
                pixel(clip.right() * factor + offsetX), pixel(clip.bottom() * factor + offsetY)};
        if (outer != null) {
            edges[0] = Math.max(edges[0], outer[0]);
            edges[1] = Math.max(edges[1], outer[1]);
            edges[2] = Math.min(edges[2], outer[2]);
            edges[3] = Math.min(edges[3], outer[3]);
        }
        return edges;
    }

    /**
     * The first pixel whose centre is at or past {@code edge}: a fill from {@code a} to {@code b}
     * covers the pixels {@code pixel(a)} up to (not including) {@code pixel(b)}.
     */
    static int pixel(double edge) {
        return (int) Math.ceil(edge - 0.5);
    }

    @Override
    public void pushLayer(int z) {
        context.pose().pushPose();
        context.pose().translate(0.0F, 0.0F, (float) z);
    }

    @Override
    public void popLayer() {
        context.pose().popPose();
    }

    @Override
    public void pushScale(float factor) {
        context.pose().pushPose();
        context.pose().scale(factor, factor, 1.0F);
        scales.push(new double[] {scale, originX, originY});
        scale *= factor;
    }

    @Override
    public void pushScale(int x, int y, float factor) {
        context.pose().pushPose();
        context.pose().translate((float) x, (float) y, 0.0F);
        context.pose().scale(factor, factor, 1.0F);
        scales.push(new double[] {scale, originX, originY});
        originX += x * scale;
        originY += y * scale;
        scale *= factor;
    }

    @Override
    public void popScale() {
        context.pose().popPose();
        double[] before = scales.pop();
        scale = before[0];
        originX = before[1];
        originY = before[2];
    }

    @Override
    public double pixelScale() {
        return scale * Minecraft.getInstance().getWindow().getGuiScale();
    }

    @Override
    public void item(ItemStack stack, int x, int y) {
        context.renderItem(stack, x, y);
    }

    @Override
    public void widget(AbstractWidget widget, int mouseX, int mouseY, float delta) {
        widget.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void texture(ResourceLocation texture, int x, int y, int width, int height, int textureWidth, int textureHeight) {
        if (width > 0 && height > 0 && textureWidth > 0 && textureHeight > 0) {
            // drawTexture draws at once (javap: Tessellator, drawWithGlobalProgram); anything still batched goes first.
            context.flush();
            context.blit(texture, x, y, width, height, 0.0F, 0.0F, textureWidth, textureHeight, textureWidth,
                    textureHeight);
        }
    }
}
