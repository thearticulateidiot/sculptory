package dev.sculptory.fabric.client.editor.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

/**
 * A {@link UiGraphics} that records what was drawn and checks clip/layer/scale pushes balance. Fills and texts are
 * also kept in drawing order with their layer (the sum of the pushed z values), so a test can check what the
 * painter's order leaves visible: {@link #textsShowingThrough}.
 */
public final class RecordingGraphics implements UiGraphics {
    /**
     * A fill, or a text with its anchor as a one-unit rectangle, at the layer it was drawn on, and the clip it was drawn
     * under (null for none).
     */
    record Paint(Rect rect, int argb, String text, int z, Rect clip) {
        /** Whether a text's anchor shows at all: inside its clip. */
        boolean shows() {
            return clip == null || clip.contains(rect.x(), rect.y());
        }

        /** Where a fill shows: its rectangle within its clip. */
        Rect shown() {
            return clip == null ? rect : rect.intersect(clip);
        }
    }

    final List<String> texts = new ArrayList<>();
    final List<Paint> paints = new ArrayList<>();
    int fills;
    int items;
    int widgets;
    int clipDepth;
    int maxClipDepth;
    int layerDepth;
    int layersPushed;
    int scaleDepth;
    /** The factors of {@link #pushScale(float)} (the UI size), in order. */
    final List<Float> scales = new ArrayList<>();
    private final Deque<Integer> layers = new ArrayDeque<>();
    /** The clips pushed, each intersected with the one before, in the recorded coordinates. */
    private final Deque<Rect> clips = new ArrayDeque<>();
    private int z;
    /**
     * The transforms of {@link #pushScale(int, int, float)} (only those: positions are recorded as given under a
     * plain {@link #pushScale(float)}, the UI size): a unit is recorded at {@code unit * placeScale + origin}.
     */
    private final Deque<double[]> placements = new ArrayDeque<>();
    private double placeScale = 1;
    private double originX;
    private double originY;
    /** Every scale pushed (plain or placed) multiplied together. */
    private double pushedScale = 1;
    /** Each text's scale (from {@link #pushScale(int, int, float)}), by its index in {@link #paints}. */
    private final List<Double> textScales = new ArrayList<>();
    /** The screen's pixels per unit before any scale is pushed (the GUI scale). */
    private double guiScale = 1;

    /** Reports {@code guiScale} screen pixels per unit (times the scales pushed), as a screen with that GUI scale. */
    public RecordingGraphics withGuiScale(double guiScale) {
        this.guiScale = guiScale;
        return this;
    }

    @Override
    public double pixelScale() {
        return guiScale * pushedScale;
    }

    private int px(double x) {
        return (int) Math.round(x * placeScale + originX);
    }

    private int py(double y) {
        return (int) Math.round(y * placeScale + originY);
    }

    /** A rectangle in the recorded coordinates. */
    private Rect placed(int x, int y, int width, int height) {
        int left = px(x);
        int top = py(y);
        return new Rect(left, top, px(x + width) - left, py(y + height) - top);
    }

    @Override
    public void fill(int x, int y, int width, int height, int argb) {
        fills++;
        paints.add(new Paint(placed(x, y, width, height), argb, null, z, clips.peek()));
        textScales.add(null);
    }

    @Override
    public void text(String text, int x, int y, int argb, boolean shadow) {
        texts.add(text);
        paints.add(new Paint(new Rect(px(x), py(y), 1, 1), argb, text, z, clips.peek()));
        textScales.add(placeScale);
    }

    @Override
    public void pushClip(Rect clip) {
        Rect placed = placed(clip.x(), clip.y(), clip.width(), clip.height());
        Rect outer = clips.peek();
        clips.push(outer == null ? placed : outer.intersect(placed));
        clipDepth++;
        maxClipDepth = Math.max(maxClipDepth, clipDepth);
    }

    @Override
    public void popClip() {
        if (--clipDepth < 0) {
            throw new AssertionError("popClip without pushClip");
        }
        clips.pop();
    }

    @Override
    public void pushLayer(int z) {
        layerDepth++;
        layersPushed++;
        layers.push(this.z);
        this.z += z;
    }

    @Override
    public void popLayer() {
        if (--layerDepth < 0) {
            throw new AssertionError("popLayer without pushLayer");
        }
        z = layers.pop();
    }

    @Override
    public void pushScale(float factor) {
        scaleDepth++;
        scales.add(factor);
        placements.push(new double[] {placeScale, originX, originY, pushedScale});
        pushedScale *= factor;
    }

    @Override
    public void pushScale(int x, int y, float factor) {
        scaleDepth++;
        placements.push(new double[] {placeScale, originX, originY, pushedScale});
        pushedScale *= factor;
        originX += x * placeScale;
        originY += y * placeScale;
        placeScale *= factor;
    }

    @Override
    public void popScale() {
        if (--scaleDepth < 0) {
            throw new AssertionError("popScale without pushScale");
        }
        double[] before = placements.pop();
        placeScale = before[0];
        originX = before[1];
        originY = before[2];
        pushedScale = before[3];
    }

    @Override
    public void item(ItemStack stack, int x, int y) {
        items++;
    }

    @Override
    public void widget(ClickableWidget widget, int mouseX, int mouseY, float delta) {
        widgets++;
    }

    /** A texture drawn: which one and where. */
    public record Texture(Identifier id, Rect rect) {}

    final List<Texture> textures = new ArrayList<>();

    @Override
    public void texture(Identifier texture, int x, int y, int width, int height, int textureWidth, int textureHeight) {
        textures.add(new Texture(texture, placed(x, y, width, height)));
    }

    /** Every texture drawn, in drawing order. */
    public List<Texture> drawnTextures() {
        return List.copyOf(textures);
    }

    /**
     * A text drawn, with its top left and the scale it was drawn at ({@link #pushScale(int, int, float)}: 1 without;
     * its width is the font's times this).
     */
    public record DrawnText(String text, int x, int y, double scale) {}

    /** Every text drawn with where it went, in drawing order. */
    public List<DrawnText> drawnTextPositions() {
        List<DrawnText> out = new ArrayList<>();
        for (int i = 0; i < paints.size(); i++) {
            Paint paint = paints.get(i);
            if (paint.text() != null) {
                out.add(new DrawnText(paint.text(), paint.rect().x(), paint.rect().y(), textScales.get(i)));
            }
        }
        return out;
    }

    /** Whether every clip, layer and scale pushed was popped. */
    public boolean isBalanced() {
        return balanced();
    }

    /** Every text drawn, in drawing order. */
    public List<String> drawnTexts() {
        return List.copyOf(texts);
    }

    /** Where each fill went, in drawing order. */
    public List<Rect> filledRects() {
        return paints.stream().filter(paint -> paint.text() == null).map(Paint::rect).toList();
    }

    /** A fill drawn: where, and in what colour. */
    public record Fill(Rect rect, int argb) {}

    /** Every fill with its colour, in drawing order. */
    public List<Fill> drawnFills() {
        return paints.stream().filter(paint -> paint.text() == null).map(paint -> new Fill(paint.rect(), paint.argb()))
                .toList();
    }

    /** The colour of each text drawn, in drawing order. */
    public List<Integer> textColors() {
        return paints.stream().filter(paint -> paint.text() != null).map(Paint::argb).toList();
    }

    /** The top left of the first text drawn equal to {@code text}, or null when there is none. */
    public Rect textAnchor(String text) {
        return paints.stream().filter(paint -> text.equals(paint.text())).map(Paint::rect).findFirst().orElse(null);
    }

    /** The texts drawn with their top left at this point, in drawing order. */
    public List<String> textsAt(int x, int y) {
        return paints.stream().filter(paint -> paint.text() != null && paint.rect().x() == x && paint.rect().y() == y)
                .map(Paint::text).toList();
    }

    /** How many vanilla widgets were drawn. */
    public int drawnWidgets() {
        return widgets;
    }

    boolean balanced() {
        return clipDepth == 0 && layerDepth == 0 && scaleDepth == 0;
    }

    /** The texts drawn whose anchor lies inside the clip they were drawn under (every text drawn without a clip). */
    public List<String> shownTexts() {
        return paints.stream().filter(paint -> paint.text() != null && paint.shows()).map(Paint::text).toList();
    }

    /**
     * Texts whose anchor lies inside {@code area} and that later fills on other layers paint over without hiding:
     * every such fill there is see-through, so the text would show through it (a window's body over the text of a
     * window or HUD element under it, say). Fills on the text's own layer (a hover or disabled tint) don't count.
     * Clips count: a text outside its clip isn't drawn, and a fill covers only within its clip.
     */
    public List<String> textsShowingThrough(Rect area) {
        List<String> through = new ArrayList<>();
        for (int i = 0; i < paints.size(); i++) {
            Paint text = paints.get(i);
            if (text.text() == null || !text.shows() || !area.contains(text.rect().x(), text.rect().y())) {
                continue;
            }
            boolean covered = false;
            boolean hidden = false;
            for (Paint later : paints.subList(i + 1, paints.size())) {
                if (later.text() == null && later.z() != text.z()
                        && later.shown().contains(text.rect().x(), text.rect().y())) {
                    covered = true;
                    hidden |= later.argb() >>> 24 == 0xFF;
                }
            }
            if (covered && !hidden) {
                through.add(text.text());
            }
        }
        return through;
    }

    /** Texts whose anchor lies inside {@code area} and that some later fill on another layer paints over. */
    public List<String> textsPaintedOver(Rect area) {
        List<String> over = new ArrayList<>();
        for (int i = 0; i < paints.size(); i++) {
            Paint text = paints.get(i);
            if (text.text() == null || !text.shows() || !area.contains(text.rect().x(), text.rect().y())) {
                continue;
            }
            for (Paint later : paints.subList(i + 1, paints.size())) {
                if (later.text() == null && later.z() != text.z()
                        && later.shown().contains(text.rect().x(), text.rect().y())) {
                    over.add(text.text());
                    break;
                }
            }
        }
        return over;
    }
}
