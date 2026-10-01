package dev.sculptory.fabric.client.editor.ui;

import java.util.Objects;
import java.util.function.Predicate;
import org.lwjgl.glfw.GLFW;

/**
 * Shared per-UI state passed to every node: text metrics, theme, popups, and which node is focused,
 * hovered or capturing the pointer. Pure Java; owned by a {@code WindowManager}.
 */
public final class UiContext {
    private final TextMeasure text;
    private final Theme theme;
    private final PopupLayer popups = new PopupLayer();
    private Node focused;
    private Node hovered;
    private Node captured;
    private int captureButton = -1;
    private long nowMs;
    private long hoverStartMs;
    private double mouseX = -1;
    private double mouseY = -1;
    private int modifiers;
    private int screenWidth;
    private int screenHeight;

    public UiContext(TextMeasure text, Theme theme) {
        this.text = Objects.requireNonNull(text);
        this.theme = Objects.requireNonNull(theme);
    }

    public TextMeasure text() {
        return text;
    }

    public Theme theme() {
        return theme;
    }

    public PopupLayer popups() {
        return popups;
    }

    // ---- Focus ----

    public Node focused() {
        return focused;
    }

    public boolean isFocused(Node node) {
        return node != null && node == focused;
    }

    /** Focuses {@code node}; {@code null} or a node that can't take focus clears focus. */
    public void setFocus(Node node) {
        if (node != null && (!node.isFocusable() || !node.isShown() || !node.isEffectivelyEnabled())) {
            node = null;
        }
        if (node == focused) {
            return;
        }
        Node previous = focused;
        focused = node;
        if (previous != null) {
            previous.notifyFocusChanged(this, false);
        }
        if (node != null) {
            node.notifyFocusChanged(this, true);
        }
    }

    public void clearFocus() {
        setFocus(null);
    }

    /** Moves focus to the next focusable node under {@code scope}, wrapping. */
    public void focusNext(Node scope) {
        setFocus(FocusTraversal.next(scope, focused));
    }

    /** Moves focus to the previous focusable node under {@code scope}, wrapping. */
    public void focusPrevious(Node scope) {
        setFocus(FocusTraversal.previous(scope, focused));
    }

    // ---- Hover ----

    public Node hovered() {
        return hovered;
    }

    public boolean isHovered(Node node) {
        return node != null && node == hovered;
    }

    /** True if the hovered node is {@code node} or inside it. */
    public boolean isHoveredWithin(Node node) {
        return hovered != null && node != null && hovered.isDescendantOf(node);
    }

    public void setHovered(Node node) {
        if (node != hovered) {
            hovered = node;
            hoverStartMs = nowMs;
        }
    }

    /** How long the current hovered node has been hovered, by frame time. */
    public long hoverDurationMs() {
        return hovered == null ? 0 : nowMs - hoverStartMs;
    }

    // ---- Pointer capture ----

    public Node captured() {
        return captured;
    }

    public boolean isCaptured(Node node) {
        return node != null && node == captured;
    }

    public int captureButton() {
        return captureButton;
    }

    public void capture(Node node, int button) {
        captured = node;
        captureButton = node == null ? -1 : button;
    }

    public void releaseCapture() {
        captured = null;
        captureButton = -1;
    }

    // ---- Frame state ----

    public long now() {
        return nowMs;
    }

    public void setNow(long nowMs) {
        this.nowMs = nowMs;
    }

    public double mouseX() {
        return mouseX;
    }

    public double mouseY() {
        return mouseY;
    }

    public void setMouse(double x, double y) {
        this.mouseX = x;
        this.mouseY = y;
    }

    /** GLFW modifier bits of the event being dispatched. */
    public int modifiers() {
        return modifiers;
    }

    public void setModifiers(int modifiers) {
        this.modifiers = modifiers;
    }

    public boolean shiftDown() {
        return (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
    }

    public boolean controlDown() {
        return (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
    }

    public int screenWidth() {
        return screenWidth;
    }

    public int screenHeight() {
        return screenHeight;
    }

    public void setScreenSize(int width, int height) {
        this.screenWidth = width;
        this.screenHeight = height;
    }

    /**
     * Forgets focused, hovered and captured nodes that are no longer in a live tree (their window
     * closed, their list row scrolled away, their popup closed) or are no longer shown.
     */
    public void dropDetached(Predicate<Node> isLiveRoot) {
        if (focused != null && !isLive(focused, isLiveRoot)) {
            setFocus(null);
        }
        if (hovered != null && !isLive(hovered, isLiveRoot)) {
            hovered = null;
        }
        if (captured != null && !isLive(captured, isLiveRoot)) {
            releaseCapture();
        }
    }

    private static boolean isLive(Node node, Predicate<Node> isLiveRoot) {
        return node.isShown() && isLiveRoot.test(node.root());
    }
}
