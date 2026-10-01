package dev.sculptory.fabric.client.editor.wiki;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;
import org.lwjgl.glfw.GLFW;

/**
 * One wiki page as the Wiki window shows it (inside a scroll pane): a text column at most {@link #MAX_COLUMN} page
 * units wide, centred with a margin, drawn {@link PageScale#scale} times larger than the rest of the UI (so it reads
 * well at small UI sizes). Headings, paragraphs with bold, italic, code chips and links, lists, tables, callouts,
 * rules and picture thumbnails with captions. A click on a link (pressed and released on it) hands the link to the
 * window; a click on a picture shows it full size ({@link PictureOverlay}). The link under the pointer is underlined
 * and the picture under it framed in the accent colour; a website's link shows its address as the tooltip. Laid out
 * once per width ({@link PageLayout}); only what lies inside the scroll pane is drawn.
 */
public final class WikiPageView extends Node {
    /** The text column's widest (page units): about 70 characters of English. */
    public static final int MAX_COLUMN = 380;
    /** Space left and right of the column (at least), and above and below the page, in page units. */
    public static final int SIDE_MARGIN = 6;
    public static final int TOP_MARGIN = 4;
    public static final int BOTTOM_MARGIN = 12;
    private static final int CACHED_WIDTHS = 4;

    /** A layout placed in a view of one width: the column's left and the page's height, in page units. */
    private record Placed(PageLayout layout, int columnX) {}

    private WikiPictures pictures;
    private final Translator tr;
    private final Consumer<WikiLink> onLink;
    private PageScale scale = PageScale.PLAIN;
    private WikiPage page;
    private String missing = "";
    private final Map<Integer, Placed> layouts = new LinkedHashMap<>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Placed> eldest) {
            return size() > CACHED_WIDTHS;
        }
    };
    private WikiLink pressed;
    private PageLayout.PictureBox pressedPicture;
    private double mouseX = Double.NaN;
    private double mouseY = Double.NaN;

    public WikiPageView(WikiPictures pictures, Translator tr, Consumer<WikiLink> onLink) {
        this.pictures = Objects.requireNonNull(pictures);
        this.tr = Objects.requireNonNull(tr);
        this.onLink = Objects.requireNonNull(onLink);
    }

    /** Shows {@code page} (its {@code #} title left out: the window shows it). */
    public void setPage(WikiPage page) {
        this.page = Objects.requireNonNull(page);
        this.missing = "";
        layouts.clear();
        pressed = null;
        pressedPicture = null;
    }

    /** Shows only {@code text}, dim (a page that isn't there). */
    public void setMissing(String text) {
        this.page = null;
        this.missing = Objects.requireNonNull(text);
        layouts.clear();
        pressed = null;
        pressedPicture = null;
    }

    public Optional<WikiPage> page() {
        return Optional.ofNullable(page);
    }

    /** Where pictures come from from now on. */
    public void setPictures(WikiPictures pictures) {
        this.pictures = Objects.requireNonNull(pictures);
        layouts.clear();
    }

    /** How large the page is drawn on this screen; a change lays the page out again. */
    public void setScale(PageScale scale) {
        if (!scale.equals(this.scale)) {
            this.scale = Objects.requireNonNull(scale);
            layouts.clear();
        }
    }

    public PageScale scale() {
        return scale;
    }

    /** Lays the page out again at the next frame (its pictures were freed and are loaded again then). */
    public void forgetLayout() {
        layouts.clear();
    }

    /** The layout for a view {@code width} UI units wide, built on first use. */
    private Placed placed(UiContext ctx, int width) {
        int w = Math.max(1, width);
        Placed placed = layouts.get(w);
        if (placed == null) {
            int pageWidth = Math.max(1, scale.toPage(w));
            int margin = Math.min(SIDE_MARGIN, Math.max(0, (pageWidth - 1) / 2));
            int column = Math.max(1, Math.min(MAX_COLUMN, pageWidth - 2 * margin));
            PageLayout layout = page == null ? PageLayout.line(missing, column, ctx.text(), ctx.theme())
                    : PageLayout.build(page, column, ctx.text(), ctx.theme(), pictures, tr, true, scale);
            placed = new Placed(layout, Math.max(0, (pageWidth - column) / 2));
            layouts.put(w, placed);
        }
        return placed;
    }

    /** How far below the view's top the heading with {@code anchor} starts when laid out {@code width} wide. */
    public OptionalInt anchorY(UiContext ctx, int width, String anchor) {
        Integer y = placed(ctx, width).layout().anchors.get(anchor);
        return y == null ? OptionalInt.empty() : OptionalInt.of((int) Math.floor((TOP_MARGIN + y) * scale.scale()));
    }

    /** A point (UI units, screen coordinates) in the column's page units. */
    private double[] toColumn(Placed placed, double x, double y) {
        return new double[] {(x - bounds.x()) / scale.scale() - placed.columnX(),
                (y - bounds.y()) / scale.scale() - TOP_MARGIN};
    }

    /** A rectangle of the column (page units) on the screen (UI units): its edges rounded outwards. */
    private Rect toScreen(Placed placed, Rect rect) {
        double s = scale.scale();
        int left = (int) Math.floor(bounds.x() + (placed.columnX() + rect.x()) * s + 1e-4);
        int top = (int) Math.floor(bounds.y() + (TOP_MARGIN + rect.y()) * s + 1e-4);
        int right = (int) Math.ceil(bounds.x() + (placed.columnX() + rect.right()) * s - 1e-4);
        int bottom = (int) Math.ceil(bounds.y() + (TOP_MARGIN + rect.bottom()) * s - 1e-4);
        return Rect.ofEdges(left, top, right, bottom);
    }

    /** The link at a point (screen coordinates, from the last layout), if any. */
    public Optional<WikiLink> linkAt(UiContext ctx, double x, double y) {
        if (bounds.isEmpty()) {
            return Optional.empty();
        }
        Placed placed = placed(ctx, bounds.width());
        double[] at = toColumn(placed, x, y);
        return placed.layout().linkAt(at[0], at[1]);
    }

    private Optional<PageLayout.PictureBox> pictureAt(UiContext ctx, double x, double y) {
        if (bounds.isEmpty()) {
            return Optional.empty();
        }
        Placed placed = placed(ctx, bounds.width());
        double[] at = toColumn(placed, x, y);
        return placed.layout().pictureAt(at[0], at[1]);
    }

    /** Where the text column is on the screen (UI units, from the last layout); its width is the column's. */
    public Rect columnBounds(UiContext ctx) {
        if (bounds.isEmpty()) {
            return Rect.EMPTY;
        }
        Placed placed = placed(ctx, bounds.width());
        return toScreen(placed, new Rect(0, 0, placed.layout().width, placed.layout().height));
    }

    /** Where each picture's thumbnail (with its frame) is on the screen, in the page's order, from the last layout. */
    public List<Rect> pictureBounds(UiContext ctx) {
        List<Rect> out = new ArrayList<>();
        if (!bounds.isEmpty()) {
            Placed placed = placed(ctx, bounds.width());
            for (PageLayout.PictureBox box : placed.layout().pictures) {
                out.add(toScreen(placed, box.rect()));
            }
        }
        return out;
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        int width = maxWidth == Integer.MAX_VALUE ? 400 : maxWidth;
        int height = placed(ctx, width).layout().height + TOP_MARGIN + BOTTOM_MARGIN;
        return new Size(width, scale.toUi(height));
    }

    @Override
    public void render(UiGraphics g, UiContext ctx) {
        if (bounds.isEmpty()) {
            return;
        }
        Placed placed = placed(ctx, bounds.width());
        PageLayout layout = placed.layout();
        float s = scale.scale();
        Rect visible = parent() != null ? parent().bounds() : bounds;
        double top = (visible.y() - bounds.y()) / (double) s - TOP_MARGIN;
        double bottom = (visible.bottom() - bounds.y()) / (double) s - TOP_MARGIN;
        boolean hoveredHere = ctx.isHovered(this);
        double[] mouse = toColumn(placed, ctx.mouseX(), ctx.mouseY());
        WikiLink hovered = hoveredHere ? layout.linkAt(mouse[0], mouse[1]).orElse(null) : null;
        PageLayout.PictureBox hoveredPicture = hoveredHere && hovered == null
                ? layout.pictureAt(mouse[0], mouse[1]).orElse(null) : null;
        // Under the page's scale, the column's top left is at (x, y).
        int x;
        int y;
        if (s == 1) {
            x = bounds.x() + placed.columnX();
            y = bounds.y() + TOP_MARGIN;
        } else {
            g.pushScale(bounds.x(), bounds.y(), s);
            x = placed.columnX();
            y = TOP_MARGIN;
        }
        try {
            for (PageLayout.Op op : layout.ops) {
                if (op.bottom() < top - 2 || op.top() > bottom + 2) {
                    continue;
                }
                switch (op) {
                    case PageLayout.TextOp text -> drawText(g, text, x, y);
                    case PageLayout.FillOp fill -> g.fill(fill.rect().translate(x, y), fill.color());
                    case PageLayout.OutlineOp outline -> g.outline(outline.rect().translate(x, y), outline.color());
                    case PageLayout.PictureOp picture -> {
                        Rect rect = picture.rect().translate(x, y);
                        g.texture(picture.picture().texture(), rect.x(), rect.y(), rect.width(), rect.height(),
                                picture.picture().width(), picture.picture().height());
                    }
                    case PageLayout.UnderlineOp underline -> {
                        if (hovered != null && underline.link() == hovered) {
                            g.fill(underline.rect().translate(x, y), underline.color());
                        }
                    }
                }
            }
            if (hoveredPicture != null) {
                g.outline(hoveredPicture.rect().translate(x, y), ctx.theme().accentHover);
            }
        } finally {
            if (s != 1) {
                g.popScale();
            }
        }
    }

    private static void drawText(UiGraphics g, PageLayout.TextOp text, int x, int y) {
        if (text.scale() == 1) {
            g.text(text.text(), x + text.x(), y + text.y(), text.color(), text.shadow());
            return;
        }
        g.pushScale(x + text.x(), y + text.y(), text.scale());
        try {
            g.text(text.text(), 0, 0, text.color(), text.shadow());
        } finally {
            g.popScale();
        }
    }

    @Override
    public String tooltip() {
        if (bounds.isEmpty() || Double.isNaN(mouseX) || page == null) {
            return super.tooltip();
        }
        Placed placed = layouts.get(bounds.width());
        if (placed == null) {
            return super.tooltip();
        }
        double[] at = toColumn(placed, mouseX, mouseY);
        Optional<WikiLink> link = placed.layout().linkAt(at[0], at[1]);
        if (link.isPresent()) {
            return link.get().kind() == WikiLink.Kind.EXTERNAL ? link.get().url() : null;
        }
        return placed.layout().pictureAt(at[0], at[1]).map(box -> tr.translate("sculptory.wiki.picture.tooltip"))
                .orElse(null);
    }

    @Override
    public void mouseMove(UiContext ctx, double x, double y) {
        mouseX = x;
        mouseY = y;
    }

    @Override
    public boolean mouseDown(UiContext ctx, double x, double y, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        pressed = linkAt(ctx, x, y).orElse(null);
        pressedPicture = pressed == null ? pictureAt(ctx, x, y).orElse(null) : null;
        return pressed != null || pressedPicture != null;
    }

    @Override
    public void mouseUp(UiContext ctx, double x, double y, int button) {
        WikiLink link = pressed;
        PageLayout.PictureBox picture = pressedPicture;
        pressed = null;
        pressedPicture = null;
        if (link != null && linkAt(ctx, x, y).orElse(null) == link) {
            onLink.accept(link);
        } else if (picture != null && pictureAt(ctx, x, y).orElse(null) == picture) {
            PictureOverlay.open(ctx, this, picture.picture(), picture.alt(), scale.pixelsPerUnit(), tr);
        }
    }
}
