package dev.sculptory.fabric.client.editor.ui.window;

import dev.sculptory.fabric.client.editor.ui.Covers;
import dev.sculptory.fabric.client.editor.ui.FadedGraphics;
import dev.sculptory.fabric.client.editor.ui.FocusTraversal;
import dev.sculptory.fabric.client.editor.ui.Insets;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.PanelFade;
import dev.sculptory.fabric.client.editor.ui.PopupLayer;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiCursor;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import dev.sculptory.fabric.client.editor.ui.widget.Tooltip;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.lwjgl.glfw.GLFW;

/**
 * Owns the editor's floating windows and the popup layer above them, and routes input to them.
 *
 * <p>Windows drag by the title bar and snap to the work area's edges (see {@link #setReserved}) and to each other
 * within the theme's snap distance; they collapse to their title bar, close, resize from any edge or corner (and
 * the bottom-right grip), and come to the front when clicked. Each window is anchored to its nearest
 * screen corner, so {@link #layout} after a GUI-scale or resolution change keeps it in the
 * same place relative to that corner, clamped on screen. {@link #snapshot()} and {@link #restore(LayoutState)}
 * save and load that state. All coordinates are UI units.
 *
 * <p>Each UI size has its own arrangement: where the windows are and how big ({@link SizedLayouts}).
 * {@link #arrangeForUiSize} switches to another size's; which windows are open or collapsed, and their order, stay
 * as they are. Moving or resizing a window, and Reset layout, change the current size's arrangement only.
 * {@link #layouts()} and {@link #restore(SizedLayouts, int)} save and load every size's.
 *
 * <p>Pure Java apart from the {@link UiGraphics} it renders into, so it is unit-tested directly.
 * Mouse methods return true when the UI consumed the event; otherwise the editor should handle it.
 */
public final class WindowManager {
    /** How far a window's shadow reaches past its right and bottom edges ({@link UiGraphics#dropShadow}). */
    private static final int SHADOW = 2;

    /** Which part of a window a point is over. */
    public enum Region { NONE, TITLE, CLOSE, COLLAPSE, RESIZE, BODY }

    /**
     * A window hit; {@code node} is the content node under the point, if any, and {@code handle}
     * the edge or corner for {@link Region#RESIZE}.
     */
    public record HitResult(Window window, Region region, Node node, ResizeHandle handle) {
        public static final HitResult NONE = new HitResult(null, Region.NONE, null);

        public HitResult(Window window, Region region, Node node) {
            this(window, region, node, null);
        }
    }

    /**
     * Where windows go by default, before the user moves or resizes them: their expanded rectangles by window id,
     * inside {@code workArea} where they fit. Windows missing from the result use their spec's anchor and size,
     * relative to the work area. {@code shown} holds the ids of the windows drawn at their default place now (open and
     * not moved or resized by the user), so the layout can make room for them; a window not among them gets the place
     * it would open at.
     */
    @FunctionalInterface
    public interface DefaultLayout {
        Map<String, Rect> place(Rect workArea, Map<String, WindowSpec> specs, Set<String> shown);

        /**
         * Where window {@code id}, opening now (or open when the work area changed), goes instead of its default place,
         * which covers one of {@code occupied} (the other open windows by id, where they are drawn; windows the user
         * placed can be anywhere): a place clear of them, or the one covering the least of them. Empty, or its default
         * place, leaves it there.
         */
        default Optional<Rect> clearPlace(Rect workArea, Map<String, WindowSpec> specs, Set<String> shown, String id,
                Map<String, Rect> occupied) {
            return Optional.empty();
        }
    }

    private record Saved(Corner anchor, int offsetX, int offsetY, int width, int height, boolean placed,
            boolean overReserved) {
        static Saved of(Window window) {
            return new Saved(window.anchor(), window.offsetX(), window.offsetY(), window.width(), window.height(),
                    window.isPlaced(), window.isOverReserved());
        }

        void applyTo(Window window) {
            window.setAnchor(anchor, offsetX, offsetY);
            window.setSize(width, height);
            window.setPlaced(placed);
            window.setOverReserved(overReserved);
        }
    }

    private final UiContext ctx;
    private final UnaryOperator<String> translator;
    private final List<Window> windows = new ArrayList<>();
    private final Map<String, LayoutState.WindowState> pending = new LinkedHashMap<>();
    private final Map<String, Integer> restoredOrder = new HashMap<>();
    /** Every UI size's arrangement as last saved or switched from; the current size's is the windows themselves. */
    private SizedLayouts layouts = SizedLayouts.EMPTY;
    /** The UI size (percent) the windows are arranged for; 0 until the first is given, which takes them as they are. */
    private int uiSize;
    private int screenWidth;
    private int screenHeight;
    private Insets reserved = Insets.NONE;
    private DefaultLayout defaultLayout;
    private boolean allHidden;
    private HitResult hoverHit = HitResult.NONE;
    private int resizeBorder;
    private int resizeCorner;
    /** How opaque each window's surfaces are drawn (View > Opacity…); null draws them opaque. */
    private PanelFade panelFade;
    /** How opaque each open window is drawn this frame ({@link #prepare}). */
    private final Map<Window, Float> frameAlphas = new IdentityHashMap<>();

    private Window dragging;
    private double grabX;
    private double grabY;
    private Window resizing;
    private ResizeHandle resizeHandle;
    /** The drawn rectangle and pointer position when the resize started. */
    private Rect resizeStart;
    private double pressX;
    private double pressY;
    private Saved interactionStart;
    /** The window being dragged or resized has moved (it then follows the pointer, reserved edges included). */
    private boolean moved;

    /**
     * @param translator turns a window's title key into display text; in game use
     *                   {@code I18n::translate}, in tests {@code UnaryOperator.identity()}
     */
    public WindowManager(TextMeasure text, Theme theme, UnaryOperator<String> translator) {
        this.ctx = new UiContext(text, theme);
        this.translator = Objects.requireNonNull(translator);
        this.resizeBorder = theme.resizeBorder;
        this.resizeCorner = theme.resizeCornerSize;
    }

    /**
     * How far the resize band and the corners reach, in UI units; never less than the theme's.
     * The editor widens them at small UI sizes so they stay a few screen pixels wide.
     */
    public void setResizeReach(int border, int corner) {
        Theme theme = ctx.theme();
        this.resizeBorder = Math.max(theme.resizeBorder, border);
        this.resizeCorner = Math.max(theme.resizeCornerSize, corner);
    }

    public UiContext context() {
        return ctx;
    }

    /**
     * Fades the windows' surfaces with the Panels opacity (View > Opacity…): each window is a panel of its own, engaged
     * ({@link #isEngaged}) while the pointer is over it, it holds the keyboard or the pointer, it is being dragged or
     * resized, or a popup it opened is open. Menus, dropdowns, dialogs and tooltips stay opaque. Null: all opaque.
     */
    public void setPanelFade(PanelFade panelFade) {
        this.panelFade = panelFade;
    }

    /**
     * Whether the window counts as in use for "Fade only when not hovered": the pointer is over it (not over a popup),
     * a control in it has the keyboard or the pointer, it is being dragged or resized, or a popup opened from it is open.
     */
    public boolean isEngaged(Window window) {
        if (window == dragging || window == resizing || hoverHit.window() == window) {
            return true;
        }
        if (!window.hasContent()) {
            return false;
        }
        Node content = window.content();
        if (isIn(ctx.focused(), content) || isIn(ctx.captured(), content)) {
            return true;
        }
        return ctx.popups().popups().stream().anyMatch(popup -> isIn(popup.owner(), content));
    }

    private static boolean isIn(Node node, Node root) {
        return node != null && node.root() == root;
    }

    public int screenWidth() {
        return screenWidth;
    }

    public int screenHeight() {
        return screenHeight;
    }

    /**
     * Screen edges kept for the HUD (the editor's top bar, hint line and palette), in UI units. Windows open inside
     * the rest, the work area; a window the user moved or resized keeps out of these edges too when a screen or UI
     * size change would push it onto them, unless the user left it there. Applies from the next layout.
     */
    public void setReserved(Insets reserved) {
        this.reserved = Objects.requireNonNull(reserved);
    }

    /** Where windows go before the user moves or resizes them; {@code null} uses each spec's anchor and size. */
    public void setDefaultLayout(DefaultLayout defaultLayout) {
        this.defaultLayout = defaultLayout;
    }

    /** The screen minus the reserved edges (the whole screen if nothing would be left). */
    public Rect workArea() {
        Rect screen = new Rect(0, 0, screenWidth, screenHeight);
        Rect work = screen.inset(reserved).intersect(screen);
        return work.isEmpty() ? screen : work;
    }

    // ---- Registration ----

    /** Adds a window on top of the others. A saved state from {@link #restore} is applied if present. */
    public Window register(WindowSpec spec) {
        if (window(spec.id()).isPresent()) {
            throw new IllegalArgumentException("Window already registered: " + spec.id());
        }
        Window window = new Window(spec);
        LayoutState.WindowState saved = pending.remove(spec.id());
        if (saved != null) {
            apply(window, saved);
        }
        // A window from the restored layout goes below the first window saved above it; others go on top.
        int order = restoredOrder.getOrDefault(spec.id(), Integer.MAX_VALUE);
        int index = windows.size();
        for (int i = 0; i < windows.size(); i++) {
            if (restoredOrder.getOrDefault(windows.get(i).id(), Integer.MAX_VALUE) > order) {
                index = i;
                break;
            }
        }
        windows.add(index, window);
        relayout();
        return window;
    }

    public void unregister(String id) {
        window(id).ifPresent(window -> {
            if (window == dragging || window == resizing) {
                cancelInteraction();
            }
            windows.remove(window);
            relayout();
        });
    }

    public Optional<Window> window(String id) {
        for (Window window : windows) {
            if (window.id().equals(id)) {
                return Optional.of(window);
            }
        }
        return Optional.empty();
    }

    /** All windows, bottom to top. */
    public List<Window> windows() {
        return Collections.unmodifiableList(windows);
    }

    // ---- Window commands ----

    public void open(String id) {
        window(id).ifPresent(window -> {
            window.setOpen(true);
            bringToFront(window);
            relayout();
        });
    }

    /**
     * Decides where the open windows the user hasn't placed open in this work area, for those not decided yet (just
     * opened or restored) or decided for another work area (the screen or UI size changed): a window whose default
     * place covers another open window goes where the default layout finds room instead
     * ({@link DefaultLayout#clearPlace}). It stays there while it is open and the work area stays the same, whatever
     * other windows do; it is not saved and isn't a placement (it stays unplaced), so closing it, Reset layout or a
     * restored layout forget it. Returns whether a window moved.
     */
    private boolean decideClearPlaces(Rect work) {
        if (defaultLayout == null || allHidden) {
            return false;
        }
        boolean changed = false;
        for (Window window : windows) {
            if (!window.isOpen() || window.isPlaced() || window.isCollapsed() || window.clearPlaceDecidedFor(work)) {
                continue;
            }
            // Laid out at its default place for this work area just now.
            Rect at = window.rect();
            window.decideClearPlace(null, work);
            Map<String, Rect> occupied = new LinkedHashMap<>();
            for (Window other : windows) {
                if (other != window && other.isOpen()) {
                    occupied.put(other.id(), other.rect());
                }
            }
            if (occupied.values().stream().noneMatch(at::intersects)) {
                continue;
            }
            Optional<Rect> clear = defaultLayout.clearPlace(work, specs(), shown(), window.id(),
                    Collections.unmodifiableMap(occupied)).filter(place -> !place.equals(at));
            if (clear.isPresent()) {
                window.decideClearPlace(clear.get(), work);
                changed = true;
            }
        }
        return changed;
    }

    public void close(String id) {
        window(id).ifPresent(window -> {
            if (!window.spec().closable()) {
                return;
            }
            if (window == dragging || window == resizing) {
                cancelInteraction();
            }
            window.setOpen(false);
            window.forgetClearPlace();
            relayout();
        });
    }

    public void toggle(String id) {
        if (isOpen(id)) {
            close(id);
        } else {
            open(id);
        }
    }

    public boolean isOpen(String id) {
        return window(id).map(Window::isOpen).orElse(false);
    }

    public void setCollapsed(String id, boolean collapsed) {
        window(id).ifPresent(window -> {
            if (window.isCollapsed() != collapsed) {
                toggleCollapsed(window);
            }
        });
    }

    public void bringToFront(String id) {
        window(id).ifPresent(this::bringToFront);
    }

    private void bringToFront(Window window) {
        if (windows.get(windows.size() - 1) != window) {
            windows.remove(window);
            windows.add(window);
        }
    }

    /**
     * Moves the keyboard to the first control of the next open window (the editor's F6) or the previous one
     * (Shift+F6), and brings that window to the front. Windows go in screen order: left to right by their left edge,
     * then top to bottom; collapsed windows and windows without a control that takes the keyboard are skipped. The
     * step starts from the window holding the focused control; with none, F6 goes to the first window and Shift+F6 to
     * the last. Returns false, changing nothing, when no window qualifies (or all are hidden).
     */
    public boolean focusNextWindow(boolean forward) {
        if (allHidden) {
            return false;
        }
        List<Window> order = new ArrayList<>();
        for (Window window : windows) {
            if (window.isOpen() && !window.isCollapsed() && !FocusTraversal.focusables(window.content()).isEmpty()) {
                order.add(window);
            }
        }
        if (order.isEmpty()) {
            return false;
        }
        order.sort(Comparator.comparingInt((Window window) -> window.rect().x())
                .thenComparingInt(window -> window.rect().y()));
        Node focused = ctx.focused();
        int index = -1;
        for (int i = 0; focused != null && i < order.size(); i++) {
            if (focused.root() == order.get(i).content()) {
                index = i;
            }
        }
        int count = order.size();
        int next = forward ? (index < 0 ? 0 : (index + 1) % count) : (index < 0 ? count - 1 : (index - 1 + count) % count);
        Window target = order.get(next);
        bringToFront(target);
        ctx.setFocus(FocusTraversal.focusables(target.content()).get(0));
        relayout();
        return true;
    }

    /** Hides or shows every window at once (the editor's Tab) without changing which are open. */
    public void setAllHidden(boolean hidden) {
        if (hidden == allHidden) {
            return;
        }
        allHidden = hidden;
        if (hidden) {
            cancelInteraction();
            ctx.popups().closeAll();
            ctx.clearFocus();
            ctx.releaseCapture();
        }
        relayout();
    }

    public boolean isAllHidden() {
        return allHidden;
    }

    /**
     * View > Reset layout: puts every window back at its default place and size (see {@link #setDefaultLayout}) at the
     * current UI size only. Which windows are open or collapsed, and their order, stay as they are, and other sizes
     * keep their arrangements. A window not registered yet also loses its place at this size (it opens at its default
     * place) and keeps its flags. A drag or resize in progress is cancelled.
     */
    public void resetLayout() {
        cancelInteraction();
        for (Window window : windows) {
            unplace(window);
        }
        pending.replaceAll((id, saved) -> saved.withPlacement(null));
        layouts = layouts.without(uiSize);
        relayout();
    }

    /** The window at its default place and size (its spec's anchor until the next layout), not moved by the user. */
    private static void unplace(Window window) {
        WindowSpec spec = window.spec();
        window.setAnchor(spec.anchor(), spec.offsetX(), spec.offsetY());
        window.setSize(spec.size().width(), spec.size().height());
        window.setPlaced(false);
        window.setOverReserved(false);
        window.forgetClearPlace();
    }

    /**
     * Every window at its default place, open as by default and expanded (keeping its title bar where it is at the
     * other UI sizes); saved states of unregistered ones dropped. What {@link #restore(SizedLayouts, int)} starts from.
     */
    private void resetWindows() {
        cancelInteraction();
        pending.clear();
        restoredOrder.clear();
        for (Window window : windows) {
            WindowSpec spec = window.spec();
            if (window.isCollapsed()) {
                window.setCollapsed(false);
                keepTitleBarAtOtherSizes(window);
            }
            unplace(window);
            window.setOpen(spec.openByDefault() || !spec.closable());
        }
    }

    /**
     * A window just collapsed or expanded: at the other UI sizes its title bar stays where it was too (collapsed is
     * shared by every size, while each size's place was stored for the shape the window had then).
     */
    private void keepTitleBarAtOtherSizes(Window window) {
        int title = ctx.theme().titleBarHeight;
        boolean collapsed = window.isCollapsed();
        layouts = layouts.withPlacementsOf(window.id(), uiSize, placement -> collapsed
                ? placement.reshaped(placement.height(), title) : placement.reshaped(title, placement.height()));
    }

    private void toggleCollapsed(Window window) {
        if (!window.spec().collapsible()) {
            return;
        }
        Rect before = window.rect();
        window.setCollapsed(!window.isCollapsed());
        keepTitleBarAtOtherSizes(window);
        // A window at its default place, or fitted into the work area, keeps its stored place.
        if (screenWidth > 0 && screenHeight > 0 && window.isPlaced() && !window.isFitted()) {
            int height = window.isCollapsed() ? ctx.theme().titleBarHeight : Math.min(window.height(), screenHeight);
            // Keep the title bar where it is, then re-anchor from the new shape.
            window.place(clampToScreen(before.withSize(before.width(), height)), screenWidth, screenHeight);
        }
        relayout();
    }

    // ---- Layout ----

    /**
     * Lays out windows for a screen of the given scaled size: each window is placed from its anchor
     * and clamped on screen (without changing its stored anchor), then its content is laid out.
     * Call it when the screen is initialised or resized; {@link #render} also calls it every frame.
     */
    public void layout(int screenWidth, int screenHeight) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        ctx.setScreenSize(screenWidth, screenHeight);
        if (screenWidth <= 0 || screenHeight <= 0) {
            return;
        }
        Rect work = workArea();
        Map<String, Rect> defaults = defaultPlaces(work);
        placeWindows(work, defaults);
        if (decideClearPlaces(work)) {
            placeWindows(work, defaults);
        }
        ctx.popups().closeIf(popup -> popup.owner() != null && !isLive(popup.owner()));
        ctx.popups().layout(ctx, screenWidth, screenHeight);
        ctx.dropDetached(this::isLiveRoot);
        updateHover(ctx.mouseX(), ctx.mouseY());
    }

    /**
     * Places every window: an unplaced one at its default place in {@code work} (or where it was decided to open
     * instead, {@link #decideClearPlaces}), a placed one from its anchor; then lays out the open ones' content.
     */
    private void placeWindows(Rect work, Map<String, Rect> defaults) {
        Theme theme = ctx.theme();
        for (Window window : windows) {
            WindowSpec spec = window.spec();
            Rect place = defaults.get(window.id());
            if (place == null) {
                place = window.anchored(spec.anchor(), spec.offsetX(), spec.offsetY(), spec.size().width(),
                        spec.size().height(), work);
            }
            if (window.clearPlaceDecidedFor(work)) {
                place = window.clearPlace().orElse(place);
            }
            // Once the user drags or resizes a window it goes where the pointer takes it, HUD edges included.
            boolean held = moved && (window == dragging || window == resizing);
            window.layout(theme, screenWidth, screenHeight, place, held ? Rect.EMPTY : work);
            if (window.isOpen() && !window.isCollapsed() && !allHidden) {
                window.content().layout(ctx, window.contentRect(theme));
            }
        }
    }

    private void relayout() {
        layout(screenWidth, screenHeight);
    }

    private Map<String, Rect> defaultPlaces(Rect work) {
        return defaultLayout == null ? Map.of() : defaultLayout.place(work, specs(), shown());
    }

    /** The windows drawn at their default place: open and not placed by the user. */
    private Set<String> shown() {
        Set<String> shown = new LinkedHashSet<>();
        for (Window window : windows) {
            if (window.isOpen() && !window.isPlaced()) {
                shown.add(window.id());
            }
        }
        return Collections.unmodifiableSet(shown);
    }

    /** Every registered window's spec by id. */
    private Map<String, WindowSpec> specs() {
        Map<String, WindowSpec> specs = new LinkedHashMap<>();
        for (Window window : windows) {
            specs.put(window.id(), window.spec());
        }
        return Collections.unmodifiableMap(specs);
    }

    private boolean isLiveRoot(Node root) {
        if (ctx.popups().isPopupRoot(root)) {
            return true;
        }
        if (allHidden) {
            return false;
        }
        for (Window window : windows) {
            if (window.hasContent() && window.content() == root) {
                return window.isOpen() && !window.isCollapsed();
            }
        }
        return false;
    }

    private boolean isLive(Node node) {
        return node.isShown() && isLiveRoot(node.root());
    }

    private Rect clampToScreen(Rect rect) {
        int x = Math.max(0, Math.min(rect.x(), screenWidth - rect.width()));
        int y = Math.max(0, Math.min(rect.y(), screenHeight - rect.height()));
        return rect.withPosition(x, y);
    }

    // ---- Hit testing ----

    /**
     * The topmost window part under the point. Popups are not included; see {@link #isMouseOverUi}.
     * Priority within a window: the resize band along the edges (corners first; it may reach just
     * outside the border, see {@link Window#resizeHandleAt}), then the close and collapse buttons,
     * then the title bar, then the body content.
     */
    public HitResult hitTest(double x, double y) {
        if (allHidden) {
            return HitResult.NONE;
        }
        Theme theme = ctx.theme();
        for (int i = windows.size() - 1; i >= 0; i--) {
            Window window = windows.get(i);
            if (!window.isOpen()) {
                continue;
            }
            ResizeHandle handle = window.resizeHandleAt(theme, x, y, resizeBorder, resizeCorner);
            if (handle != null) {
                return new HitResult(window, Region.RESIZE, null, handle);
            }
            if (!window.rect().contains(x, y)) {
                continue;
            }
            if (window.closeButtonRect(theme).contains(x, y)) {
                return new HitResult(window, Region.CLOSE, null);
            }
            if (window.collapseButtonRect(theme).contains(x, y)) {
                return new HitResult(window, Region.COLLAPSE, null);
            }
            if (window.titleBarRect(theme).contains(x, y)) {
                return new HitResult(window, Region.TITLE, null);
            }
            Node node = window.hasContent() ? window.content().hitTest(x, y) : null;
            return new HitResult(window, Region.BODY, node);
        }
        return HitResult.NONE;
    }

    /** True if the point is over a popup or a visible window, so the world shouldn't get the event. */
    public boolean isMouseOverUi(double x, double y) {
        return ctx.popups().popupAt(x, y) != null || hitTest(x, y).window() != null;
    }

    /** True while a window drag/resize or a control drag is in progress. */
    public boolean isInteracting() {
        return dragging != null || resizing != null || ctx.captured() != null;
    }

    /**
     * The cursor for the pointer's last position: a resize cursor over a window's edge or corner
     * and throughout a resize, otherwise the normal one.
     */
    public UiCursor cursor() {
        Window window = resizing != null ? resizing : hoverHit.window();
        ResizeHandle handle = window == null || resizing == null && ctx.popups().isOpen()
                ? null : activeResizeHandle(window);
        return handle == null ? UiCursor.DEFAULT : handle.cursor();
    }

    /** True if a control has keyboard focus, so typed keys belong to the UI. */
    public boolean hasKeyboardFocus() {
        return ctx.focused() != null;
    }

    /**
     * True while the focused control listens for a key to bind ({@link Node#capturesAllInput}): every press, scroll
     * and key goes to it, whatever the pointer is over.
     */
    public boolean isCapturingInput() {
        Node focused = ctx.focused();
        return focused != null && focused.capturesAllInput();
    }

    private void updateHover(double x, double y) {
        Node target = null;
        HitResult hit = HitResult.NONE;
        PopupLayer.Popup popup = ctx.popups().popupAt(x, y);
        if (popup != null) {
            target = popup.content().hitTest(x, y);
        } else if (!ctx.popups().isOpen()) {
            hit = hitTest(x, y);
            if (hit.region() == Region.BODY) {
                target = hit.node();
            }
        }
        hoverHit = hit;
        ctx.setHovered(target);
    }

    // ---- Mouse input ----

    public boolean mouseDown(double x, double y, int button, int modifiers) {
        ctx.setMouse(x, y);
        ctx.setModifiers(modifiers);
        boolean consumed = handleMouseDown(x, y, button);
        relayout();
        return consumed;
    }

    private boolean handleMouseDown(double x, double y, int button) {
        if (isCapturingInput()) {
            dispatchMouseDown(ctx.focused(), x, y, button);
            return true;
        }
        PopupLayer popups = ctx.popups();
        if (popups.isOpen()) {
            PopupLayer.Popup popup = popups.popupAt(x, y);
            if (popup == null) {
                popups.closeAll();
            } else {
                dispatchMouseDown(popup.content().hitTest(x, y), x, y, button);
            }
            return true;
        }
        HitResult hit = hitTest(x, y);
        Window window = hit.window();
        if (window == null) {
            ctx.clearFocus();
            return false;
        }
        bringToFront(window);
        if (hit.region() == Region.BODY) {
            dispatchMouseDown(hit.node(), x, y, button);
            return true;
        }
        ctx.clearFocus();
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return true;
        }
        switch (hit.region()) {
            case CLOSE -> close(window.id());
            case COLLAPSE -> toggleCollapsed(window);
            case TITLE -> {
                dragging = window;
                interactionStart = Saved.of(window);
                grabX = x - window.rect().x();
                grabY = y - window.rect().y();
            }
            case RESIZE -> {
                resizing = window;
                resizeHandle = hit.handle();
                interactionStart = Saved.of(window);
                resizeStart = window.rect();
                pressX = x;
                pressY = y;
            }
            default -> {
            }
        }
        return true;
    }

    /** Sends the press to the node and its ancestors until one consumes it; that node gets capture. */
    private void dispatchMouseDown(Node target, double x, double y, int button) {
        Node focusBefore = ctx.focused();
        Node consumer = null;
        for (Node node = target; node != null; node = node.parent()) {
            if (!node.isEffectivelyEnabled()) {
                break;
            }
            if (node.mouseDown(ctx, x, y, button)) {
                consumer = node;
                break;
            }
        }
        if (consumer != null && consumer.focusOnClick()) {
            ctx.setFocus(consumer);
        } else if (focusBefore != null && ctx.focused() == focusBefore && consumer != focusBefore) {
            ctx.clearFocus();
        }
        if (consumer != null) {
            ctx.capture(consumer, button);
        }
    }

    public boolean mouseDragged(double x, double y, int button) {
        ctx.setMouse(x, y);
        if (dragging != null) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                dragTo(x, y);
                relayout();
            }
            return true;
        }
        if (resizing != null) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                resizeTo(x, y);
                relayout();
            }
            return true;
        }
        Node captured = ctx.captured();
        if (captured != null && button == ctx.captureButton()) {
            captured.mouseDrag(ctx, x, y, button);
            relayout();
            return true;
        }
        return false;
    }

    public boolean mouseUp(double x, double y, int button) {
        ctx.setMouse(x, y);
        if ((dragging != null || resizing != null) && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            Window window = dragging != null ? dragging : resizing;
            if (moved && interactionStart != null && !Saved.of(window).equals(interactionStart)) {
                // Left over the HUD's edges on purpose: a later screen change keeps it there.
                Rect work = workArea();
                window.setOverReserved(!work.intersect(window.rect()).equals(window.rect()));
            }
            endInteraction();
            return true;
        }
        Node captured = ctx.captured();
        if (captured != null && button == ctx.captureButton()) {
            ctx.releaseCapture();
            captured.mouseUp(ctx, x, y, button);
            relayout();
            return true;
        }
        return false;
    }

    /** Positive {@code amount} scrolls up. Returns true if the pointer is over the UI. */
    public boolean mouseScrolled(double x, double y, double amount, int modifiers) {
        ctx.setMouse(x, y);
        ctx.setModifiers(modifiers);
        boolean consumed = handleScroll(x, y, amount);
        relayout();
        return consumed;
    }

    private boolean handleScroll(double x, double y, double amount) {
        if (isCapturingInput()) {
            dispatchScroll(ctx.focused(), x, y, amount);
            return true;
        }
        PopupLayer.Popup popup = ctx.popups().popupAt(x, y);
        if (popup != null) {
            dispatchScroll(popup.content().hitTest(x, y), x, y, amount);
            return true;
        }
        ctx.popups().closeAll();
        HitResult hit = hitTest(x, y);
        if (hit.window() == null) {
            return false;
        }
        if (hit.region() == Region.BODY) {
            dispatchScroll(hit.node(), x, y, amount);
        }
        return true;
    }

    private void dispatchScroll(Node target, double x, double y, double amount) {
        for (Node node = target; node != null; node = node.parent()) {
            if (!node.isEffectivelyEnabled() || node.mouseScroll(ctx, x, y, amount)) {
                return;
            }
        }
    }

    public void mouseMoved(double x, double y) {
        ctx.setMouse(x, y);
        updateHover(x, y);
        Node hovered = ctx.hovered();
        if (hovered != null) {
            hovered.mouseMove(ctx, x, y);
        }
    }

    private void dragTo(double x, double y) {
        if (!moved) {
            dragging.adoptDrawn(screenWidth, screenHeight);
            moved = true;
        }
        Rect current = dragging.rect();
        Rect candidate = current.withPosition((int) Math.floor(x - grabX), (int) Math.floor(y - grabY));
        List<Rect> others = new ArrayList<>();
        for (Window window : windows) {
            if (window != dragging && window.isOpen()) {
                others.add(window.rect());
            }
        }
        Rect snapped = WindowSnapper.snap(candidate, others, workArea(), ctx.theme().snapDistance);
        dragging.place(clampToScreen(snapped), screenWidth, screenHeight);
    }

    private void resizeTo(double x, double y) {
        if (!moved) {
            resizing.adoptDrawn(screenWidth, screenHeight);
            moved = true;
        }
        resizing.place(resizedRect(resizeStart, resizeHandle, x - pressX, y - pressY, resizing.spec().minSize(),
                screenWidth, screenHeight), screenWidth, screenHeight);
        Rect drawn = resizing.rect();
        // A collapsed window resizes only sideways and keeps its expanded height.
        resizing.setSize(drawn.width(), resizing.isCollapsed() ? resizing.height() : drawn.height());
    }

    /**
     * The rectangle after dragging {@code handle} of {@code start} by ({@code dx}, {@code dy}): the
     * dragged edges move, the opposite edges stay put, and the result keeps the minimum size (when
     * the screen allows it) and stays on screen.
     */
    static Rect resizedRect(Rect start, ResizeHandle handle, double dx, double dy, Size min, int screenWidth,
            int screenHeight) {
        int moveX = (int) Math.floor(dx);
        int moveY = (int) Math.floor(dy);
        int left = start.x();
        int top = start.y();
        int right = start.right();
        int bottom = start.bottom();
        if (handle.left()) {
            left = Math.max(0, Math.min(start.x() + moveX, right - min.width()));
        } else if (handle.right()) {
            right = Math.min(screenWidth, Math.max(start.right() + moveX, left + min.width()));
        }
        if (handle.top()) {
            top = Math.max(0, Math.min(start.y() + moveY, bottom - min.height()));
        } else if (handle.bottom()) {
            bottom = Math.min(screenHeight, Math.max(start.bottom() + moveY, top + min.height()));
        }
        return Rect.ofEdges(left, top, right, bottom);
    }

    /**
     * Aborts a window drag or resize, putting the window back where it started (Esc, or the UI
     * size changing under it).
     */
    public void cancelInteraction() {
        Window window = dragging != null ? dragging : resizing;
        if (window != null && interactionStart != null) {
            interactionStart.applyTo(window);
        }
        endInteraction();
    }

    private void endInteraction() {
        moved = false;
        dragging = null;
        resizing = null;
        resizeHandle = null;
        resizeStart = null;
        interactionStart = null;
    }

    // ---- Keyboard input ----

    /**
     * Esc closes the top popup, then cancels a window drag, then ends an edit in place ({@link Node#escapePressed}),
     * then clears focus; otherwise it is not
     * consumed so the editor can continue its Esc ladder. Other keys go to the focused control and
     * bubble; Tab and Shift+Tab move focus within the focused window.
     */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        ctx.setModifiers(modifiers);
        boolean consumed = handleKey(keyCode, scanCode, modifiers);
        if (consumed) {
            relayout();
        }
        return consumed;
    }

    private boolean handleKey(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (ctx.popups().isOpen()) {
                ctx.popups().closeTop();
                return true;
            }
            if (dragging != null || resizing != null) {
                cancelInteraction();
                return true;
            }
            if (ctx.focused() != null) {
                for (Node node = ctx.focused(); node != null; node = node.parent()) {
                    if (node.escapePressed(ctx)) {
                        return true;
                    }
                }
                ctx.clearFocus();
                return true;
            }
            return false;
        }
        Node focused = ctx.focused();
        if (focused == null) {
            return false;
        }
        for (Node node = focused; node != null; node = node.parent()) {
            if (node.keyPressed(ctx, keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            if ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0) {
                ctx.focusPrevious(focused.root());
            } else {
                ctx.focusNext(focused.root());
            }
            return true;
        }
        return false;
    }

    public boolean charTyped(char chr, int modifiers) {
        ctx.setModifiers(modifiers);
        for (Node node = ctx.focused(); node != null; node = node.parent()) {
            if (node.charTyped(ctx, chr, modifiers)) {
                return true;
            }
        }
        return false;
    }

    // ---- Rendering ----

    /**
     * Lays out and draws all windows (their surfaces faded as {@link #setPanelFade} says), then popups, then the hover
     * tooltip, each on its own layer: {@link #prepare}, then {@link #draw} with nothing over the windows.
     */
    public void render(UiGraphics g, double mouseX, double mouseY, long nowMs) {
        prepare(mouseX, mouseY, nowMs);
        draw(g, List.of());
    }

    /**
     * Lays the windows out for a frame and works out how opaque each is drawn this frame (View > Opacity…); then
     * {@link #fadedRects} says where the see-through ones are, and {@link #draw} draws them.
     */
    public void prepare(double mouseX, double mouseY, long nowMs) {
        ctx.setNow(nowMs);
        ctx.setMouse(mouseX, mouseY);
        relayout();
        frameAlphas.clear();
        for (Window window : windows) {
            if (window.isOpen()) {
                frameAlphas.put(window, panelFade == null ? 1.0F : panelFade.alpha(window, isEngaged(window), nowMs));
            }
        }
    }

    /**
     * Where the open windows drawn see-through this frame are ({@link #prepare}: Panels opacity below 100%), bottom to
     * top; none while the windows are hidden. What is drawn under them (the HUD) is left out there, so it doesn't show
     * through them.
     */
    public List<Rect> fadedRects() {
        List<Rect> faded = new ArrayList<>();
        if (allHidden) {
            return faded;
        }
        for (Window window : windows) {
            if (window.isOpen() && frameAlphas.getOrDefault(window, 1.0F) < 1.0F) {
                faded.add(window.rect());
            }
        }
        return faded;
    }

    /**
     * Draws the windows as {@link #prepare} left them, then popups, then the hover tooltip, each on its own layer. A
     * window under see-through ones (a faded window above it, or one of {@code coversAbove}: see-through things drawn
     * over the windows later, the faded toasts) is drawn only where they leave it ({@link Covers}), so within the UI a
     * window hides what is under it at any opacity while the world still shows through.
     */
    public void draw(UiGraphics g, List<Rect> coversAbove) {
        if (screenWidth <= 0 || screenHeight <= 0) {
            return;
        }
        Theme theme = ctx.theme();
        int z = 0;
        if (!allHidden) {
            Window top = topOpenWindow();
            for (int i = 0; i < windows.size(); i++) {
                Window window = windows.get(i);
                if (!window.isOpen()) {
                    continue;
                }
                float alpha = frameAlphas.getOrDefault(window, 1.0F);
                List<Rect> covers = new ArrayList<>(coversAbove);
                for (Window above : windows.subList(i + 1, windows.size())) {
                    if (above.isOpen() && frameAlphas.getOrDefault(above, 1.0F) < 1.0F) {
                        covers.add(above.rect());
                    }
                }
                Rect rect = window.rect();
                // The window and the shadow beside it.
                Rect area = new Rect(rect.x(), rect.y(), rect.width() + SHADOW, rect.height() + SHADOW);
                g.pushLayer(z);
                Covers.drawUncovered(g, area, covers,
                        () -> renderWindow(FadedGraphics.of(g, theme, alpha), window, window == top));
                g.popLayer();
                z += theme.layerStep;
            }
        }
        if (ctx.popups().isOpen()) {
            g.pushLayer(z);
            ctx.popups().render(g, ctx);
            g.popLayer();
            z += theme.layerStep;
        }
        renderTooltip(g, z, ctx.mouseX(), ctx.mouseY());
    }

    private Window topOpenWindow() {
        for (int i = windows.size() - 1; i >= 0; i--) {
            if (windows.get(i).isOpen()) {
                return windows.get(i);
            }
        }
        return null;
    }

    private void renderWindow(UiGraphics g, Window window, boolean active) {
        Theme theme = ctx.theme();
        TextMeasure text = ctx.text();
        Rect rect = window.rect();
        g.dropShadow(rect, theme.windowShadow);
        Rect title = window.titleBarRect(theme);
        g.fill(title, active ? theme.titleBarActive : theme.titleBar);
        if (!window.isCollapsed()) {
            Rect body = window.bodyRect(theme);
            g.fill(body, theme.windowBackground);
            g.fill(title.x(), title.bottom() - 1, title.width(), 1, theme.windowBorder);
            g.pushClip(body.inset(1));
            window.content().render(g, ctx);
            g.popClip();
            Rect grip = window.resizeGripRect(theme);
            if (!grip.isEmpty()) {
                int color = activeResizeHandle(window) != null ? theme.resizeGripHot : theme.resizeGrip;
                // Three diagonal lines in the corner, just inside the border.
                for (int length = 2; length < grip.width(); length += 3) {
                    for (int t = 0; t <= length; t++) {
                        g.fill(grip.right() - 2 - t, grip.bottom() - 2 - (length - t), 1, 1, color);
                    }
                }
            }
        }
        boolean chromeHover = hoverHit.window() == window && dragging == null && resizing == null;
        Rect close = window.closeButtonRect(theme);
        if (!close.isEmpty()) {
            boolean hot = chromeHover && hoverHit.region() == Region.CLOSE;
            if (hot) {
                g.fill(close, theme.danger);
            }
            drawCross(g, close, hot ? theme.textOnAccent : theme.textDim);
        }
        Rect collapse = window.collapseButtonRect(theme);
        if (!collapse.isEmpty()) {
            boolean hot = chromeHover && hoverHit.region() == Region.COLLAPSE;
            if (hot) {
                g.fill(collapse, theme.controlHover);
            }
            int color = hot ? theme.text : theme.textDim;
            int centerX = collapse.x() + collapse.width() / 2;
            int centerY = collapse.y() + collapse.height() / 2;
            if (window.isCollapsed()) {
                g.triangleRight(centerX - 1, centerY, 3, color);
            } else {
                g.triangleDown(centerX, centerY - 1, 3, color);
            }
        }
        int textRight = !collapse.isEmpty() ? collapse.x() : !close.isEmpty() ? close.x() : title.right();
        int textX = title.x() + theme.padding;
        String name = TextLayout.ellipsize(text, translator.apply(window.spec().titleKey()), textRight - textX - 2);
        int textY = title.y() + (title.height() - text.lineHeight() + 1) / 2;
        g.text(name, textX, textY, theme.titleText, theme.titleShadow);
        g.outline(rect, active ? theme.windowBorderActive : theme.windowBorder);
        ResizeHandle handle = activeResizeHandle(window);
        if (handle != null) {
            // The edges being (or about to be) dragged light up.
            int color = theme.resizeGripHot;
            if (handle.left()) {
                g.fill(rect.x(), rect.y(), 1, rect.height(), color);
            }
            if (handle.right()) {
                g.fill(rect.right() - 1, rect.y(), 1, rect.height(), color);
            }
            if (handle.top()) {
                g.fill(rect.x(), rect.y(), rect.width(), 1, color);
            }
            if (handle.bottom()) {
                g.fill(rect.x(), rect.bottom() - 1, rect.width(), 1, color);
            }
        }
    }

    /** The handle being dragged on {@code window}, or hovered while nothing else is being dragged. */
    private ResizeHandle activeResizeHandle(Window window) {
        if (resizing != null) {
            return resizing == window ? resizeHandle : null;
        }
        boolean hovered = dragging == null && ctx.captured() == null && hoverHit.window() == window
                && hoverHit.region() == Region.RESIZE;
        return hovered ? hoverHit.handle() : null;
    }

    private static void drawCross(UiGraphics g, Rect button, int color) {
        int size = 7;
        int x = button.x() + (button.width() - size) / 2;
        int y = button.y() + (button.height() - size) / 2;
        for (int i = 0; i < size; i++) {
            g.fill(x + i, y + i, 1, 1, color);
            g.fill(x + size - 1 - i, y + i, 1, 1, color);
        }
    }

    private void renderTooltip(UiGraphics g, int z, double mouseX, double mouseY) {
        Node hovered = ctx.hovered();
        if (hovered == null || isInteracting() || ctx.hoverDurationMs() < ctx.theme().tooltipDelayMs) {
            return;
        }
        String text = null;
        for (Node node = hovered; node != null && text == null; node = node.parent()) {
            text = node.tooltip();
        }
        if (text == null || text.isBlank()) {
            return;
        }
        g.pushLayer(z);
        Tooltip.render(g, ctx, text, (int) mouseX, (int) mouseY);
        g.popLayer();
    }

    // ---- Persistence ----

    /** The current layout, bottom to top, including saved states of windows not registered yet. */
    public LayoutState snapshot() {
        List<LayoutState.WindowState> states = new ArrayList<>(pending.values());
        for (Window window : windows) {
            states.add(new LayoutState.WindowState(window.id(), window.isOpen(), window.isCollapsed(),
                    window.anchor(), window.offsetX(), window.offsetY(), window.width(), window.height(),
                    window.isPlaced(), window.isOverReserved()));
        }
        return new LayoutState(states);
    }

    /**
     * Applies a saved layout to the current UI size's arrangement: positions, sizes, open/collapsed flags and z-order.
     * States for windows that aren't registered yet are kept and applied when they register. A window restored
     * unplaced takes its spec's anchor and size (an unplaced window's stored place is never used).
     */
    public void restore(LayoutState state) {
        cancelInteraction();
        ctx.popups().closeAll();
        pending.clear();
        restoredOrder.clear();
        List<LayoutState.WindowState> states = state.windows();
        for (int i = 0; i < states.size(); i++) {
            LayoutState.WindowState saved = states.get(i);
            restoredOrder.put(saved.id(), i);
            Optional<Window> window = window(saved.id());
            if (window.isPresent()) {
                apply(window.get(), saved);
            } else {
                pending.put(saved.id(), saved);
            }
        }
        windows.sort(Comparator.comparingInt(window -> restoredOrder.getOrDefault(window.id(), Integer.MAX_VALUE)));
        relayout();
    }

    private static void apply(Window window, LayoutState.WindowState saved) {
        WindowSpec spec = window.spec();
        if (saved.placed()) {
            window.setAnchor(saved.anchor(), saved.offsetX(), saved.offsetY());
            window.setSize(saved.width(), saved.height());
        } else {
            window.setAnchor(spec.anchor(), spec.offsetX(), spec.offsetY());
            window.setSize(spec.size().width(), spec.size().height());
        }
        window.setOpen(saved.open() || !spec.closable());
        window.setCollapsed(saved.collapsed() && spec.collapsible());
        window.setPlaced(saved.placed());
        window.setOverReserved(saved.placed() && saved.overReserved());
        window.forgetClearPlace();
    }

    // ---- One arrangement per UI size ----

    /** The UI size (percent) the windows are arranged for, or 0 before the first is given. */
    public int uiSize() {
        return uiSize;
    }

    /**
     * Shows UI size {@code percent}'s arrangement: the current one is kept as its size's, then each window goes where
     * the new size's arrangement puts it, or to its default place if that size has none for it (a size never
     * arranged shows the default layout). Which windows are open or collapsed and their order don't change. A drag or
     * resize in progress is cancelled. Nothing changes when the size is the current one; the first size given takes
     * the windows as they are.
     */
    public void arrangeForUiSize(int percent) {
        requireUiSize(percent);
        if (percent == uiSize) {
            return;
        }
        if (uiSize == 0) {
            uiSize = percent;
            return;
        }
        cancelInteraction();
        layouts = layouts.capture(uiSize, snapshot());
        uiSize = percent;
        arrange(layouts.arrangement(percent));
    }

    /**
     * Every UI size's arrangement, the current size's as the windows are now, and which windows are open. Before a UI
     * size is given the windows belong to none: only their flags and order are taken.
     */
    public SizedLayouts layouts() {
        SizedLayouts captured = layouts.capture(uiSize, snapshot());
        return uiSize == 0 ? captured.without(0) : captured;
    }

    /**
     * Replaces the whole layout with {@code saved}, shown at UI size {@code percent}: its open and collapsed flags and
     * order, and that size's arrangement. Windows it doesn't list are as by default (at their default places, expanded,
     * open only if they open at the start); states of windows not registered yet are kept and applied when they
     * register.
     */
    public void restore(SizedLayouts saved, int percent) {
        requireUiSize(percent);
        Objects.requireNonNull(saved);
        resetWindows();
        layouts = saved;
        uiSize = percent;
        restore(saved.at(percent));
        arrange(saved.arrangement(percent));
    }

    private static void requireUiSize(int percent) {
        if (percent <= 0) {
            throw new IllegalArgumentException("Not a UI size: " + percent);
        }
    }

    /**
     * Puts each window (and each saved state of a window not registered yet) where {@code arrangement} places it, and
     * every other one at its default place, unplaced. Open, collapsed and order stay.
     */
    private void arrange(Map<String, WindowPlacement> arrangement) {
        cancelInteraction();
        for (Window window : windows) {
            WindowPlacement placement = arrangement.get(window.id());
            WindowSpec spec = window.spec();
            if (placement == null) {
                window.setAnchor(spec.anchor(), spec.offsetX(), spec.offsetY());
                window.setSize(spec.size().width(), spec.size().height());
                window.setPlaced(false);
                window.setOverReserved(false);
            } else {
                window.setAnchor(placement.anchor(), placement.offsetX(), placement.offsetY());
                window.setSize(placement.width(), placement.height());
                window.setPlaced(true);
                window.setOverReserved(placement.overReserved());
            }
            window.forgetClearPlace();
        }
        pending.replaceAll((id, saved) -> saved.withPlacement(arrangement.get(id)));
        relayout();
    }
}
