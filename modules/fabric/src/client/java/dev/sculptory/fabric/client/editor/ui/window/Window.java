package dev.sculptory.fabric.client.editor.ui.window;

import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Theme;
import java.util.Optional;

/**
 * A window's state: anchor corner and offsets, stored size, open/collapsed flags and its built
 * content. Geometry changes go through {@link WindowManager}; this class only exposes reads and
 * content rebuilding.
 */
public final class Window {
    private final WindowSpec spec;
    private Corner anchor;
    private int offsetX;
    private int offsetY;
    private int width;
    private int height;
    private boolean open;
    private boolean collapsed;
    /** Moved or resized by the user since the last reset; until then the window sits at its default place. */
    private boolean placed;
    /** The user left it covering the screen edges kept for the HUD, so a screen change doesn't move it off them. */
    private boolean overReserved;
    private Rect rect = Rect.EMPTY;
    /** Where the window goes by default (expanded), from the last layout. */
    private Rect defaultRect = Rect.EMPTY;
    /** The last layout moved or shortened this placed window to keep it inside the work area. */
    private boolean fitted;
    /**
     * Where this unplaced window opens instead of its default place, which covers another window (see
     * {@link WindowManager.DefaultLayout#clearPlace}); null for its default place. Not saved.
     */
    private Rect clearPlace;
    /** The work area {@link #clearPlace} was decided for; null when it is still to be decided (just opened). */
    private Rect clearPlaceFor;
    private Node content;

    Window(WindowSpec spec) {
        this.spec = spec;
        this.anchor = spec.anchor();
        this.offsetX = spec.offsetX();
        this.offsetY = spec.offsetY();
        this.width = spec.size().width();
        this.height = spec.size().height();
        this.open = spec.openByDefault();
    }

    public WindowSpec spec() {
        return spec;
    }

    public String id() {
        return spec.id();
    }

    public boolean isOpen() {
        return open;
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    public Corner anchor() {
        return anchor;
    }

    public int offsetX() {
        return offsetX;
    }

    public int offsetY() {
        return offsetY;
    }

    /**
     * True once the user moved or resized the window (since the last layout reset): it keeps its stored anchor and
     * size. Until then it sits where the manager's default layout puts it for the current screen.
     */
    public boolean isPlaced() {
        return placed;
    }

    /** True if the user left the window over the screen edges kept for the HUD (see {@link WindowManager#setReserved}). */
    public boolean isOverReserved() {
        return overReserved;
    }

    /** Stored (expanded) width; the drawn width may be smaller on a small screen. */
    public int width() {
        return width;
    }

    /** Stored (expanded) height. */
    public int height() {
        return height;
    }

    /** Where the window is drawn, after the last layout. Only the title bar when collapsed. */
    public Rect rect() {
        return rect;
    }

    /** The content node, built on first use. */
    public Node content() {
        if (content == null) {
            content = spec.content().get();
        }
        return content;
    }

    public boolean hasContent() {
        return content != null;
    }

    /** Discards the content; the supplier builds it again at the next frame. */
    public void rebuildContent() {
        content = null;
    }

    // ---- Geometry (derived from the rect) ----

    public Rect titleBarRect(Theme theme) {
        return new Rect(rect.x(), rect.y(), rect.width(), Math.min(theme.titleBarHeight, rect.height()));
    }

    public Rect closeButtonRect(Theme theme) {
        if (!spec.closable()) {
            return Rect.EMPTY;
        }
        Rect title = titleBarRect(theme);
        int size = theme.titleButtonSize;
        return new Rect(title.right() - 2 - size, title.y() + (title.height() - size) / 2, size, size);
    }

    public Rect collapseButtonRect(Theme theme) {
        if (!spec.collapsible()) {
            return Rect.EMPTY;
        }
        Rect title = titleBarRect(theme);
        int size = theme.titleButtonSize;
        int right = spec.closable() ? closeButtonRect(theme).x() - 1 : title.right() - 2;
        return new Rect(right - size, title.y() + (title.height() - size) / 2, size, size);
    }

    /** Everything below the title bar; empty when collapsed. */
    public Rect bodyRect(Theme theme) {
        if (collapsed) {
            return Rect.EMPTY;
        }
        return new Rect(rect.x(), rect.y() + theme.titleBarHeight, rect.width(),
                rect.height() - theme.titleBarHeight);
    }

    /** Where the content node is laid out: the body inset by the theme padding. */
    public Rect contentRect(Theme theme) {
        Rect body = bodyRect(theme);
        return body.isEmpty() ? Rect.EMPTY : body.inset(theme.padding);
    }

    public Rect resizeGripRect(Theme theme) {
        if (collapsed || !spec.resizable()) {
            return Rect.EMPTY;
        }
        int size = theme.resizeGripSize;
        return new Rect(rect.right() - size, rect.bottom() - size, size, size);
    }

    /**
     * The edge or corner a point resizes from, or {@code null}. The band along each edge is
     * {@code border} wide: up to {@code theme.padding} of it lies inside the window, so it never
     * covers content, and the rest lies just outside the border. Corners reach {@code corner} along
     * both edges and win over edges; the bottom-right grip resizes too, outside the content area.
     * A collapsed window resizes only sideways. Resizing wins over the title bar buttons, so a
     * press on a button's outer rim can at worst start a resize, never close the window.
     *
     * @param border the band width in UI units (at least {@code theme.resizeBorder})
     * @param corner how far corners reach along the edges (at least {@code theme.resizeCornerSize})
     */
    public ResizeHandle resizeHandleAt(Theme theme, double x, double y, int border, int corner) {
        if (!spec.resizable() || rect.isEmpty()) {
            return null;
        }
        int inside = Math.min(border, theme.padding);
        int outside = border - inside;
        boolean inReach = x >= rect.x() - outside && x < rect.right() + outside
                && y >= rect.y() - outside && y < rect.bottom() + outside;
        if (!inReach) {
            return null;
        }
        boolean left = x < rect.x() + inside;
        boolean right = x >= rect.right() - inside;
        if (collapsed) {
            boolean beside = y >= rect.y() && y < rect.bottom();
            return !beside ? null : left ? ResizeHandle.W : right ? ResizeHandle.E : null;
        }
        boolean top = y < rect.y() + inside;
        boolean bottom = y >= rect.bottom() - inside;
        boolean nearLeft = x < rect.x() + corner;
        boolean nearRight = x >= rect.right() - corner;
        boolean nearTop = y < rect.y() + corner;
        boolean nearBottom = y >= rect.bottom() - corner;
        if (top && nearLeft || left && nearTop) {
            return ResizeHandle.NW;
        }
        if (top && nearRight || right && nearTop) {
            return ResizeHandle.NE;
        }
        if (bottom && nearLeft || left && nearBottom) {
            return ResizeHandle.SW;
        }
        boolean onGrip = resizeGripRect(theme).contains(x, y) && !contentRect(theme).contains(x, y);
        if (bottom && nearRight || right && nearBottom || onGrip) {
            return ResizeHandle.SE;
        }
        if (top) {
            return ResizeHandle.N;
        }
        if (bottom) {
            return ResizeHandle.S;
        }
        if (left) {
            return ResizeHandle.W;
        }
        return right ? ResizeHandle.E : null;
    }

    // ---- Mutation (WindowManager only) ----

    void setOpen(boolean open) {
        this.open = open;
    }

    void setCollapsed(boolean collapsed) {
        this.collapsed = collapsed;
    }

    void setSize(int width, int height) {
        this.width = Math.max(spec.minSize().width(), width);
        this.height = Math.max(spec.minSize().height(), height);
    }

    void setAnchor(Corner anchor, int offsetX, int offsetY) {
        this.anchor = anchor;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
    }

    void setPlaced(boolean placed) {
        this.placed = placed;
    }

    void setOverReserved(boolean overReserved) {
        this.overReserved = overReserved;
    }

    /**
     * Where the window opens instead of its default place because that covers another open window, while it isn't
     * placed; empty for its default place. It holds for the work area it was decided for and is never saved.
     */
    public Optional<Rect> clearPlace() {
        return Optional.ofNullable(placed ? null : clearPlace);
    }

    /** Whether where to open was decided for this work area (see {@link #clearPlace}). */
    boolean clearPlaceDecidedFor(Rect work) {
        return work.equals(clearPlaceFor);
    }

    /** Records where to open in this work area: {@code place}, or its default place when null. */
    void decideClearPlace(Rect place, Rect work) {
        this.clearPlace = place;
        this.clearPlaceFor = work;
    }

    /** Forgets where to open (closed, reset or restored): it is decided again at the next layout while open. */
    void forgetClearPlace() {
        this.clearPlace = null;
        this.clearPlaceFor = null;
    }

    /** Puts the window at a drawn rectangle and re-anchors it to the nearest corner. */
    void place(Rect drawn, int screenWidth, int screenHeight) {
        Corner corner = Corner.nearest(drawn, screenWidth, screenHeight);
        setAnchor(corner, corner.offsetX(drawn.x(), drawn.width(), screenWidth),
                corner.offsetY(drawn.y(), drawn.height(), screenHeight));
        rect = drawn;
    }

    /**
     * Makes what is drawn the stored place (anchor and expanded size) when the user starts moving or resizing the
     * window, so the move starts from what is on screen: its default place, or where a screen change moved or
     * shortened it to keep it off the reserved edges.
     */
    void adoptDrawn(int screenWidth, int screenHeight) {
        Rect expanded = placed ? (collapsed ? null : rect) : (defaultRect.isEmpty() ? rect : defaultRect);
        if (!placed || fitted) {
            Rect at = collapsed && placed ? rect : expanded;
            Corner corner = Corner.nearest(at, screenWidth, screenHeight);
            setAnchor(corner, corner.offsetX(at.x(), at.width(), screenWidth),
                    corner.offsetY(at.y(), at.height(), screenHeight));
            if (expanded != null) {
                setSize(expanded.width(), expanded.height());
            }
        }
        placed = true;
    }

    /** True if the last layout moved or shortened this placed window to keep it off the reserved edges. */
    boolean isFitted() {
        return fitted;
    }

    /**
     * Recomputes the drawn rectangle, keeping it on screen; stored state is unchanged. A window the user hasn't placed
     * goes to {@code defaultPlace} (its expanded rectangle). A placed one goes to its anchor and, unless the user left
     * it over the reserved edges, is kept inside {@code work}: moved in and, if still too tall, drawn shorter (not
     * below its minimum height).
     */
    void layout(Theme theme, int screenWidth, int screenHeight, Rect defaultPlace, Rect work) {
        Rect screen = new Rect(0, 0, screenWidth, screenHeight);
        fitted = false;
        int titleHeight = Math.min(theme.titleBarHeight, screenHeight);
        if (!placed) {
            Rect expanded = anchored(Corner.TOP_LEFT, defaultPlace.x(), defaultPlace.y(), defaultPlace.width(),
                    defaultPlace.height(), screen);
            defaultRect = expanded;
            rect = collapsed ? anchored(Corner.TOP_LEFT, expanded.x(), expanded.y(), expanded.width(), titleHeight,
                    screen, titleHeight) : expanded;
            return;
        }
        Rect drawn = collapsed ? anchored(anchor, offsetX, offsetY, width, titleHeight, screen, titleHeight)
                : anchored(anchor, offsetX, offsetY, width, height, screen);
        if (overReserved) {
            rect = drawn;
            return;
        }
        Rect inside = keepInside(drawn, work, collapsed ? drawn.height() : spec.minSize().height());
        rect = inside.withPosition(inside.x(), Math.max(0, Math.min(inside.y(), screenHeight - inside.height())));
        fitted = !rect.equals(drawn);
    }

    /**
     * A window {@code width x height} (at least the minimum size, at most the area) placed {@code offsetX/offsetY}
     * in from {@code corner} of {@code area}, and moved inside it.
     */
    Rect anchored(Corner corner, int offsetX, int offsetY, int width, int height, Rect area) {
        return anchored(corner, offsetX, offsetY, width, height, area, spec.minSize().height());
    }

    private Rect anchored(Corner corner, int offsetX, int offsetY, int width, int height, Rect area, int minHeight) {
        int w = Math.max(Math.min(spec.minSize().width(), area.width()), Math.min(width, area.width()));
        int h = Math.max(Math.min(minHeight, area.height()), Math.min(height, area.height()));
        int x = area.x() + corner.x(offsetX, w, area.width());
        int y = area.y() + corner.y(offsetY, h, area.height());
        x = Math.max(area.x(), Math.min(x, area.right() - w));
        y = Math.max(area.y(), Math.min(y, area.bottom() - h));
        return new Rect(x, y, w, h);
    }

    /**
     * {@code drawn} moved inside {@code area}; a window taller than the area is drawn as tall as the area, but not
     * shorter than {@code minHeight}. One still taller starts at the area's top (it covers the bottom edge rather
     * than the top one), and one wider than the area keeps its x.
     */
    static Rect keepInside(Rect drawn, Rect area, int minHeight) {
        if (area.isEmpty()) {
            return drawn;
        }
        int h = Math.min(drawn.height(), Math.max(area.height(), minHeight));
        int x = drawn.width() <= area.width()
                ? Math.max(area.x(), Math.min(drawn.x(), area.right() - drawn.width())) : drawn.x();
        int y = h <= area.height() ? Math.max(area.y(), Math.min(drawn.y(), area.bottom() - h)) : area.y();
        return new Rect(x, y, drawn.width(), h);
    }
}
