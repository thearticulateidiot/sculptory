package dev.sculptory.fabric.client.editor.ui.widget;

import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.lwjgl.glfw.GLFW;

/**
 * A row of menu titles (File, Edit, ...) whose menus open as {@link Menu} popups in another UI context's popup layer
 * (the bar can sit in the HUD while its menus sit above every window). Click a title to open its menu, click it again
 * to close it; while a menu is open, moving the pointer over another title opens that one instead, and Left/Right in a
 * menu go to the neighbouring menu. A menu's items are built each time it opens, so they show the current state.
 *
 * <p>While a menu is open, the popup layer gets the pointer: whoever routes input must send pointer moves and presses
 * over the bar to the bar ({@link #isMenuOpen}) for the hover switch and the second click to work.
 */
public final class MenuBar extends Node {
    /** One menu: its title and the items it shows when opened. */
    public record Entry(String title, Supplier<List<MenuItem>> items) {
        public Entry {
            Objects.requireNonNull(title);
            Objects.requireNonNull(items);
        }
    }

    private final List<Entry> entries;
    private final List<Title> titles = new ArrayList<>();
    private final Supplier<UiContext> popups;
    private Menu open;
    private int openIndex = -1;

    /** @param popups the context whose popup layer holds the menus (the window manager's) */
    public MenuBar(List<Entry> entries, Supplier<UiContext> popups) {
        this.entries = List.copyOf(entries);
        this.popups = Objects.requireNonNull(popups);
        for (int i = 0; i < this.entries.size(); i++) {
            titles.add(adopt(new Title(i)));
        }
    }

    public List<Entry> entries() {
        return entries;
    }

    /** Where title {@code index} was laid out. */
    public Rect titleBounds(int index) {
        return titles.get(index).bounds();
    }

    /** The index of the title under the point, or -1. */
    public int titleAt(double x, double y) {
        for (int i = 0; i < titles.size(); i++) {
            if (titles.get(i).bounds().contains(x, y)) {
                return i;
            }
        }
        return -1;
    }

    /** Whether one of the bar's menus is open. */
    public boolean isMenuOpen() {
        return open != null && open.isOpen();
    }

    /** The open menu's index, or -1. */
    public int openIndex() {
        return isMenuOpen() ? openIndex : -1;
    }

    /** The open top-level menu. */
    public Optional<Menu> openMenu() {
        return isMenuOpen() ? Optional.of(open) : Optional.empty();
    }

    /**
     * Opens menu {@code index} (closing the one open); {@code fromKeyboard} highlights its first item. Out-of-range
     * indexes wrap, so Left on the first menu opens the last.
     */
    public void open(int index, boolean fromKeyboard) {
        int wrapped = Math.floorMod(index, entries.size());
        close();
        Menu menu = new Menu(entries.get(wrapped).items().get());
        menu.setNavigator(direction -> open(wrapped + direction, true));
        open = menu;
        openIndex = wrapped;
        Rect title = titles.get(wrapped).bounds();
        menu.open(popups.get(), new Rect(title.x(), title.y(), title.width(), title.height() + 1), () -> {
            if (open == menu) {
                open = null;
                openIndex = -1;
            }
        });
        if (fromKeyboard) {
            menu.highlightFirst();
        }
    }

    /** Closes the open menu, if any. */
    public void close() {
        Menu menu = open;
        open = null;
        openIndex = -1;
        if (menu != null) {
            menu.close();
        }
    }

    /** A click on title {@code index}: opens its menu, or closes it when it is the one open. */
    public void clickTitle(int index) {
        if (openIndex() == index) {
            close();
        } else {
            open(index, false);
        }
    }

    /** The pointer moved over title {@code index}: while another menu is open, this one opens instead. */
    public void hoverTitle(int index) {
        if (isMenuOpen() && openIndex != index) {
            open(index, false);
        }
    }

    @Override
    public List<Node> children() {
        return List.copyOf(titles);
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        int width = 0;
        for (Title title : titles) {
            width += title.measure(ctx, maxWidth).width();
        }
        // A row short of room shrinks its other children first: every title stays whole and clickable.
        setMinSize(width, 0);
        return new Size(width, ctx.theme().controlHeight);
    }

    @Override
    public void layout(UiContext ctx, Rect bounds) {
        super.layout(ctx, bounds);
        int x = bounds.x();
        for (Title title : titles) {
            int width = title.measure(ctx, Integer.MAX_VALUE).width();
            title.layout(ctx, new Rect(x, bounds.y(), width, bounds.height()));
            x += width;
        }
    }

    /** One menu title. */
    private final class Title extends Node {
        private final int index;

        Title(int index) {
            this.index = index;
        }

        @Override
        protected Size measureContent(UiContext ctx, int maxWidth) {
            Theme theme = ctx.theme();
            return new Size(ctx.text().width(entries.get(index).title()) + 2 * theme.menuTitlePaddingX,
                    theme.controlHeight);
        }

        @Override
        public boolean mouseDown(UiContext ctx, double x, double y, int button) {
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                return false;
            }
            clickTitle(index);
            return true;
        }

        @Override
        public void mouseMove(UiContext ctx, double x, double y) {
            hoverTitle(index);
        }

        @Override
        public void render(UiGraphics g, UiContext ctx) {
            Theme theme = ctx.theme();
            boolean isOpen = openIndex() == index;
            if (isOpen) {
                g.fill(bounds, theme.menuTitleOpen);
            } else if (ctx.isHovered(this)) {
                g.fill(bounds, theme.controlHover);
            }
            String text = entries.get(index).title();
            int x = bounds.x() + (bounds.width() - ctx.text().width(text)) / 2;
            int y = bounds.y() + (bounds.height() - ctx.text().lineHeight() + 1) / 2;
            g.text(text, x, y, theme.text, theme.textShadow);
        }
    }
}
