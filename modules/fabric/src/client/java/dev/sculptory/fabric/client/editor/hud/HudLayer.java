package dev.sculptory.fabric.client.editor.hud;

import dev.sculptory.fabric.client.editor.ui.Covers;
import dev.sculptory.fabric.client.editor.ui.FadedGraphics;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import dev.sculptory.fabric.client.editor.ui.widget.Tooltip;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

/**
 * Screen-anchored UI that isn't a window: the top bar, the tool palette, the hint line and the job
 * bars. Each element is a node tree placed from its measured size every layout. Has its own
 * {@link UiContext} for hover, pointer capture and tooltips; popups opened from here go to the
 * window manager's popup layer so they sit above everything.
 */
public final class HudLayer {
    /** Places an element of the measured size on a screen of the given size. */
    @FunctionalInterface
    public interface Placement {
        Rect place(Size size, int screenWidth, int screenHeight);
    }

    /** An element, drawn with its surfaces at {@code alpha}'s opacity (View > Opacity…; 1 for opaque). */
    private record Element(Node root, Placement placement, BooleanSupplier visible, DoubleSupplier alpha) {}

    private final UiContext ctx;
    private final List<Element> elements = new ArrayList<>();
    private int screenWidth;
    private int screenHeight;

    public HudLayer(TextMeasure text, Theme theme) {
        this.ctx = new UiContext(text, theme);
    }

    public UiContext context() {
        return ctx;
    }

    /** Adds an element that is always drawn opaque. */
    public void add(Node root, Placement placement, BooleanSupplier visible) {
        add(root, placement, visible, () -> 1.0);
    }

    /**
     * Adds an element whose panel surfaces are drawn at the opacity {@code alpha} gives each frame
     * ({@link FadedGraphics}): the top bar, palette, hint line and the Undo anyway toast.
     */
    public void add(Node root, Placement placement, BooleanSupplier visible, DoubleSupplier alpha) {
        elements.add(new Element(Objects.requireNonNull(root), Objects.requireNonNull(placement),
                Objects.requireNonNull(visible), Objects.requireNonNull(alpha)));
    }

    public void layout(int width, int height) {
        screenWidth = width;
        screenHeight = height;
        ctx.setScreenSize(width, height);
        for (Element element : elements) {
            boolean shown = element.visible().getAsBoolean();
            element.root().setVisible(shown);
            if (shown) {
                Size size = element.root().measure(ctx, width);
                element.root().layout(ctx, element.placement().place(size, width, height));
            }
        }
        ctx.dropDetached(root -> root.isVisible() && elements.stream().anyMatch(e -> e.root() == root));
    }

    /** Where the elements shown at the last layout are. */
    public List<Rect> shownBounds() {
        return elements.stream().map(Element::root).filter(Node::isVisible).map(Node::bounds).toList();
    }

    public Node hitTest(double x, double y) {
        for (int i = elements.size() - 1; i >= 0; i--) {
            Node root = elements.get(i).root();
            if (!root.isVisible()) {
                continue;
            }
            Node hit = root.hitTest(x, y);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    public boolean isOver(double x, double y) {
        return hitTest(x, y) != null;
    }

    public boolean isInteracting() {
        return ctx.captured() != null;
    }

    public void render(UiGraphics g, long nowMs) {
        render(g, nowMs, List.of());
    }

    /**
     * How far past an element's bounds its drawing is kept when it is clipped around a cover: a text shadow, a focus
     * ring or a border may lie a unit or two outside the bounds, and would be cut only while a cover overlaps.
     */
    public static final int COVER_MARGIN = 2;

    /**
     * Draws the elements shown, each only where {@code covers} leave it ({@link Covers}): the see-through windows and
     * toasts drawn over the HUD later (View > Opacity…), so the HUD's text doesn't show through them. An element a
     * cover overlaps is clipped to its bounds grown by {@link #COVER_MARGIN}, less the covers, so what it draws at
     * its edges is the same as when nothing covers it.
     */
    public void render(UiGraphics g, long nowMs, List<Rect> covers) {
        ctx.setNow(nowMs);
        for (Element element : elements) {
            Node root = element.root();
            if (root.isVisible()) {
                UiGraphics faded = FadedGraphics.of(g, ctx.theme(), (float) element.alpha().getAsDouble());
                Covers.drawUncovered(g, root.bounds().inset(-COVER_MARGIN), covers, () -> root.render(faded, ctx));
            }
        }
    }

    /** Draws the hovered node's tooltip after its delay; call last so it covers everything. */
    public void renderTooltip(UiGraphics g, double mouseX, double mouseY) {
        Node hovered = ctx.hovered();
        if (hovered == null || isInteracting() || ctx.hoverDurationMs() < ctx.theme().tooltipDelayMs) {
            return;
        }
        String text = null;
        for (Node node = hovered; node != null && text == null; node = node.parent()) {
            text = node.tooltip();
        }
        if (text != null && !text.isBlank()) {
            Tooltip.render(g, ctx, text, (int) mouseX, (int) mouseY);
        }
    }

    // ---- Input ----

    public boolean mouseDown(double x, double y, int button, int modifiers) {
        ctx.setMouse(x, y);
        ctx.setModifiers(modifiers);
        Node target = hitTest(x, y);
        if (target == null) {
            return false;
        }
        for (Node node = target; node != null; node = node.parent()) {
            if (!node.isEffectivelyEnabled()) {
                break;
            }
            if (node.mouseDown(ctx, x, y, button)) {
                ctx.capture(node, button);
                break;
            }
        }
        return true;
    }

    public boolean mouseDragged(double x, double y, int button) {
        ctx.setMouse(x, y);
        Node captured = ctx.captured();
        if (captured != null && button == ctx.captureButton()) {
            captured.mouseDrag(ctx, x, y, button);
            return true;
        }
        return false;
    }

    public boolean mouseUp(double x, double y, int button) {
        ctx.setMouse(x, y);
        Node captured = ctx.captured();
        if (captured != null && button == ctx.captureButton()) {
            ctx.releaseCapture();
            captured.mouseUp(ctx, x, y, button);
            return true;
        }
        return false;
    }

    public boolean mouseScrolled(double x, double y, double amount, int modifiers) {
        ctx.setMouse(x, y);
        ctx.setModifiers(modifiers);
        Node target = hitTest(x, y);
        for (Node node = target; node != null; node = node.parent()) {
            if (!node.isEffectivelyEnabled() || node.mouseScroll(ctx, x, y, amount)) {
                break;
            }
        }
        return target != null;
    }

    public void mouseMoved(double x, double y) {
        ctx.setMouse(x, y);
        Node hovered = hitTest(x, y);
        ctx.setHovered(hovered);
        if (hovered != null) {
            hovered.mouseMove(ctx, x, y);
        }
    }

    /** Forgets the hovered node (the pointer went over a window). */
    public void clearHover() {
        ctx.setHovered(null);
    }

    public int screenWidth() {
        return screenWidth;
    }

    public int screenHeight() {
        return screenHeight;
    }
}
