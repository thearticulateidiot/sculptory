package dev.sculptory.fabric.client.editor.windows;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.ui.Align;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.PopupLayer;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.Row;
import dev.sculptory.fabric.client.editor.ui.widget.AbstractButton;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.ScrollPane;
import dev.sculptory.fabric.client.editor.ui.widget.SectionHeading;
import dev.sculptory.fabric.client.editor.ui.widget.TextInput;
import dev.sculptory.fabric.client.editor.wiki.PageScale;
import dev.sculptory.fabric.client.editor.wiki.WikiContents;
import dev.sculptory.fabric.client.editor.wiki.WikiLibrary;
import dev.sculptory.fabric.client.editor.wiki.WikiLink;
import dev.sculptory.fabric.client.editor.wiki.WikiPage;
import dev.sculptory.fabric.client.editor.wiki.WikiPageView;
import dev.sculptory.fabric.client.editor.wiki.WikiPages;
import dev.sculptory.fabric.client.editor.wiki.WikiPictures;
import dev.sculptory.fabric.client.editor.wiki.WikiSearch;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.lwjgl.glfw.GLFW;

/**
 * The Wiki window (Help > Wiki, View > Wiki): one slim row with Back, Forward, Home, the page's title and Pages, and
 * the page under it. Pages drops down the page list (grouped like the wiki's home page) under a search box that finds
 * page titles and section headings; it has the keyboard, and picking a page (or Enter: the first result) closes it, as
 * do Esc and a click beside it. Links to pages open here, links to a section scroll to it, links to websites go to
 * {@code openUrl} (Minecraft's "open this link?" screen); a click on a picture shows it full size. The mouse's back
 * and forward buttons also go back and forward over the page, as do Backspace and Alt+Left / Alt+Right once a click
 * has put the keyboard on the page. The page shown is kept while the game runs; pictures are freed when the window
 * closes ({@link #closed}).
 */
public final class WikiWindow {
    /**
     * The window's size when it first opens (its spec's, anchored at the top left beside Selection): at UI 50% on the
     * reference screen (854x498 units) it fits between Selection and Tool Settings; smaller screens shrink it to the work
     * area.
     */
    public static final int WIDTH = 470;
    public static final int HEIGHT = 340;
    /** The page list's drop-down: this wide, and at least this high where the list is longer. */
    static final int LIST_WIDTH = 190;
    static final int LIST_MIN_HEIGHT = 140;
    /** How many pages Back remembers. */
    static final int HISTORY = 50;

    private record Visit(String pageId, String anchor, int offset) {}

    private final Translator tr;
    private WikiLibrary library = WikiLibrary.empty();
    private WikiPictures pictures = WikiPictures.NONE;
    private Consumer<String> openUrl = url -> {};

    private final Button back;
    private final Button forward;
    private final Button home;
    private final Label title = Label.heading("");
    private final Button pagesToggle;
    private final TextInput search;
    private final Column rows = new Column();
    private final ScrollPane listPane;
    private final ListPanel listPanel;
    private final WikiPageView view;
    private final PagePane pagePane;
    private final Root root;

    private final Deque<Visit> backStack = new ArrayDeque<>();
    private final Deque<Visit> forwardStack = new ArrayDeque<>();
    private String current;
    private String currentAnchor;
    private String query = "";
    private boolean picturesInUse;
    /** The context of the last layout: the page list drops down in its popups. */
    private UiContext context;
    private PopupLayer.Popup listPopup;

    public WikiWindow(Translator translator) {
        this.tr = Objects.requireNonNull(translator);
        back = new Button(tr.translate("sculptory.wiki.back"), this::back);
        back.setTooltip(tr.translate("sculptory.wiki.back.tooltip"));
        back.setStyle(Button.Style.FLAT);
        forward = new Button(tr.translate("sculptory.wiki.forward"), this::forward);
        forward.setTooltip(tr.translate("sculptory.wiki.forward.tooltip"));
        forward.setStyle(Button.Style.FLAT);
        home = new Button(tr.translate("sculptory.wiki.home"), this::home);
        home.setTooltip(tr.translate("sculptory.wiki.home.tooltip"));
        home.setStyle(Button.Style.FLAT);
        pagesToggle = new Button(tr.translate("sculptory.wiki.pages"), this::toggleList);
        pagesToggle.setTooltip(tr.translate("sculptory.wiki.pages.tooltip"));
        title.setGrow(1);
        Row header = Row.of(back, forward, home, title, pagesToggle);
        header.setGap(3);
        header.setCrossAlign(Align.CENTER);

        search = new TextInput("", this::setQuery);
        search.setPlaceholder(tr.translate("sculptory.wiki.search"));
        search.setOnSubmit(text -> openFirstResult());
        rows.setGap(0);
        listPane = new ScrollPane(rows);
        listPane.setGrow(1);
        listPanel = new ListPanel(search, listPane);

        view = new WikiPageView(pictures, tr, this::follow);
        pagePane = new PagePane(view);
        root = new Root(header, pagePane);
        rebuildList();
        updateButtons();
    }

    public Node node() {
        return root;
    }

    /**
     * Where pages, pictures and websites come from (in game: the mod's resources, dynamic textures and Minecraft's link
     * screen). The page shown is shown again from the new pages.
     */
    public void setWiki(WikiLibrary library, WikiPictures pictures, Consumer<String> openUrl) {
        this.pictures.releaseAll();
        this.library = Objects.requireNonNull(library);
        this.pictures = Objects.requireNonNull(pictures);
        this.openUrl = Objects.requireNonNull(openUrl);
        view.setPictures(pictures);
        rebuildList();
        if (current != null) {
            show(current, currentAnchor, pagePane.scroll().offset());
        }
    }

    /**
     * The screen the window is drawn on: {@code pixelsPerUnit} of its pixels per UI unit at the editor UI size
     * {@code uiFactor} (1 at 100%). The page's text is drawn larger at small UI sizes ({@link PageScale}), and pictures
     * are shown at most at their own size. Until this is called the page is drawn at the UI's size.
     */
    public void setDisplay(double pixelsPerUnit, double uiFactor) {
        view.setScale(PageScale.of(pixelsPerUnit, uiFactor));
    }

    public WikiLibrary library() {
        return library;
    }

    // ---- Navigation ----

    /** Opens page {@code pageId} at {@code anchor} (null: its top); Back returns to the page shown before. */
    public void open(String pageId, String anchor) {
        Objects.requireNonNull(pageId);
        if (current != null) {
            push(backStack, new Visit(current, currentAnchor, pagePane.scroll().offset()));
        }
        forwardStack.clear();
        show(pageId, anchor, -1);
    }

    /** Shows the page shown last, or the home page the first time. */
    public void showCurrentOrHome() {
        if (current == null) {
            show(WikiPages.HOME, null, -1);
        }
    }

    public boolean back() {
        if (backStack.isEmpty()) {
            return false;
        }
        push(forwardStack, new Visit(current, currentAnchor, pagePane.scroll().offset()));
        Visit visit = backStack.pop();
        show(visit.pageId(), visit.anchor(), visit.offset());
        return true;
    }

    public boolean forward() {
        if (forwardStack.isEmpty()) {
            return false;
        }
        push(backStack, new Visit(current, currentAnchor, pagePane.scroll().offset()));
        Visit visit = forwardStack.pop();
        show(visit.pageId(), visit.anchor(), visit.offset());
        return true;
    }

    /** Home: the wiki's front page (Back returns); on it already, its top. */
    public void home() {
        if (WikiPages.HOME.equals(current)) {
            pagePane.restoreOffset(0);
            return;
        }
        open(WikiPages.HOME, null);
    }

    private static void push(Deque<Visit> stack, Visit visit) {
        stack.push(visit);
        while (stack.size() > HISTORY) {
            stack.removeLast();
        }
    }

    /** The page shown, if any yet. */
    public Optional<String> currentPage() {
        return Optional.ofNullable(current);
    }

    /** What a link on the page does. */
    private void follow(WikiLink link) {
        switch (link.kind()) {
            case PAGE -> open(link.pageId(), link.anchor());
            case SECTION -> open(current == null ? WikiPages.HOME : current, link.anchor());
            case EXTERNAL -> openUrl.accept(link.url());
            case INVALID -> { }
        }
    }

    /** Shows a page: scrolled to {@code offset} when given (Back, Forward), else to {@code anchor}, else the top. */
    private void show(String pageId, String anchor, int offset) {
        current = pageId;
        currentAnchor = anchor;
        Optional<WikiPage> page = library.page(pageId);
        if (page.isPresent()) {
            if (view.page().orElse(null) != page.get()) {
                pictures.keepOnly(page.get().pictures().stream().map(WikiPage.PictureUse::path)
                        .collect(Collectors.toSet()));
                view.setPage(page.get());
            }
            title.setText(page.get().title());
            picturesInUse = !page.get().pictures().isEmpty();
        } else {
            pictures.keepOnly(Set.of());
            view.setMissing(tr.translate("sculptory.wiki.page_not_found", pageId));
            title.setText(pageId);
            picturesInUse = false;
        }
        if (offset >= 0) {
            pagePane.restoreOffset(offset);
        } else {
            pagePane.restoreOffset(0);
            pagePane.jumpTo(anchor);
        }
        updateButtons();
    }

    private void updateButtons() {
        back.setEnabled(!backStack.isEmpty());
        forward.setEnabled(!forwardStack.isEmpty());
        home.setEnabled(!WikiPages.HOME.equals(current));
    }

    // ---- The page list ----

    private void toggleList() {
        if (isListShown()) {
            closeList();
        } else {
            openList();
        }
    }

    /**
     * Drops the page list down under Pages (right-aligned to it), the search box empty and focused and the page shown
     * scrolled into view. Needs a layout first (the window's context).
     */
    private void openList() {
        UiContext ctx = context;
        if (ctx == null || pagesToggle.bounds().isEmpty()) {
            return;
        }
        search.setText("");
        setQuery("");
        Rect button = pagesToggle.bounds();
        int room = Math.max(LIST_MIN_HEIGHT, root.bounds().bottom() - button.bottom() - 2);
        listPanel.setMaxHeight(room);
        Rect anchor = new Rect(button.right() - LIST_WIDTH, button.y(), LIST_WIDTH, button.height());
        listPopup = ctx.popups().open(pagesToggle, listPanel, anchor, LIST_WIDTH, () -> listPopup = null);
        ctx.setFocus(search);
        for (Node row : rows.children()) {
            if (row instanceof PageRow pageRow && pageRow.pageId.equals(current) && pageRow.anchor == null) {
                listPane.scrollIntoView(pageRow);
                break;
            }
        }
    }

    private void closeList() {
        PopupLayer.Popup popup = listPopup;
        listPopup = null;
        if (popup != null && context != null) {
            context.popups().close(popup);
        }
    }

    // ---- Frame and closing ----

    /** Call every frame while the window is open: the first time it shows the home page. */
    public void refresh() {
        if (current == null) {
            showCurrentOrHome();
        }
        // Drawing the page loads its pictures (again, after closed()).
        picturesInUse |= view.page().map(page -> !page.pictures().isEmpty()).orElse(false);
    }

    /** The window closed (or the editor): its pictures are freed, and loaded again when it shows them. */
    public void closed() {
        if (picturesInUse) {
            pictures.releaseAll();
            view.forgetLayout();
            picturesInUse = false;
        }
    }

    // ---- The search ----

    private void setQuery(String text) {
        String normalized = text == null ? "" : text.strip();
        if (normalized.equals(query)) {
            return;
        }
        query = normalized;
        rebuildList();
    }

    /** Enter in the search box: the best result. */
    private void openFirstResult() {
        for (Node row : rows.children()) {
            if (row instanceof PageRow pageRow) {
                pageRow.click();
                return;
            }
        }
    }

    private void rebuildList() {
        rows.clear();
        Theme theme = Theme.DARK;
        if (!query.isEmpty()) {
            List<WikiSearch.Result> results = library.search(query);
            for (WikiSearch.Result result : results) {
                rows.add(result.isPage() ? new PageRow(result.pageId(), null, result.pageTitle(), "")
                        : new PageRow(result.pageId(), result.anchor(), result.heading(), result.pageTitle()));
            }
            if (results.isEmpty()) {
                rows.add(Label.dim(tr.translate("sculptory.wiki.no_matches")).setWrap(true));
            }
            return;
        }
        if (library.isEmpty()) {
            rows.add(Label.dim(tr.translate("sculptory.wiki.no_pages")).setWrap(true));
            return;
        }
        boolean first = true;
        for (WikiContents.Group group : library.contents().groups()) {
            String heading = switch (group.kind()) {
                case TOP -> null;
                case SECTION -> group.title();
                case OTHERS -> tr.translate("sculptory.wiki.more_pages");
            };
            if (heading != null) {
                rows.add(new SectionHeading(heading).setSpaceAbove(first ? 0 : theme.headingSpaceAbove));
            }
            for (String id : group.pageIds()) {
                library.page(id).ifPresent(page -> rows.add(new PageRow(page.id(), null, page.title(), "")));
            }
            first = false;
        }
    }

    // ---- For tests and the tour ----

    public WikiPageView pageView() {
        return view;
    }

    public ScrollPane pagePane() {
        return pagePane;
    }

    public ScrollPane listPane() {
        return listPane;
    }

    /** The page list's drop-down (the search box over the list), shown in a popup while {@link #isListShown}. */
    public Node listPanel() {
        return listPanel;
    }

    public TextInput searchBox() {
        return search;
    }

    public Button backButton() {
        return back;
    }

    public Button forwardButton() {
        return forward;
    }

    public Button homeButton() {
        return home;
    }

    public Button pagesButton() {
        return pagesToggle;
    }

    /** The title shown above the page. */
    public String title() {
        return title.text();
    }

    /** Types into the search box (as typing does). */
    public void search(String text) {
        search.setText(text);
        setQuery(text);
    }

    /** The list's rows as text: group headings as "## Title", pages by title, sections as "Heading · Page". */
    public List<String> listed() {
        List<String> out = new ArrayList<>();
        for (Node row : rows.children()) {
            if (row instanceof SectionHeading heading) {
                out.add("## " + heading.text());
            } else if (row instanceof PageRow page) {
                out.add(page.detail.isEmpty() ? page.text : page.text + " · " + page.detail);
            } else if (row instanceof Label label) {
                out.add(label.text());
            }
        }
        return out;
    }

    /** The list's row for a page (not a section), from the last layout. */
    public Optional<AbstractButton> row(String pageId) {
        for (Node row : rows.children()) {
            if (row instanceof PageRow page && page.pageId.equals(pageId) && page.anchor == null) {
                return Optional.of(page);
            }
        }
        return Optional.empty();
    }

    /** Whether the page list is dropped down (Pages; hidden at first, and again once a page is picked). */
    public boolean isListShown() {
        return listPopup != null && context != null && context.popups().popups().contains(listPopup);
    }

    // ---- Nodes ----

    /** The header row over the page. Keeps the context of its last layout, for the page list's popup. */
    private final class Root extends Node {
        private final Node header;
        private final Node page;

        Root(Node header, Node page) {
            this.header = adopt(header);
            this.page = adopt(page);
        }

        @Override
        public List<Node> children() {
            return List.of(header, page);
        }

        @Override
        protected Size measureContent(UiContext ctx, int maxWidth) {
            Size top = header.measure(ctx, maxWidth);
            Size below = page.measure(ctx, maxWidth);
            return new Size(Math.max(top.width(), below.width()), top.height() + ctx.theme().gap + below.height());
        }

        @Override
        public void layout(UiContext ctx, Rect bounds) {
            super.layout(ctx, bounds);
            context = ctx;
            int headerHeight = Math.min(bounds.height(), header.measure(ctx, bounds.width()).height());
            header.layout(ctx, new Rect(bounds.x(), bounds.y(), bounds.width(), headerHeight));
            int top = Math.min(bounds.bottom(), bounds.y() + headerHeight + ctx.theme().gap);
            page.layout(ctx, Rect.ofEdges(bounds.x(), top, bounds.right(), bounds.bottom()));
        }
    }

    /** The drop-down's content: the search box over the list, {@link #LIST_WIDTH} wide and at most so high. */
    private static final class ListPanel extends Node {
        private static final int PAD = 4;
        private final Node search;
        private final Node list;
        private int maxHeight = LIST_MIN_HEIGHT;

        ListPanel(Node search, Node list) {
            this.search = adopt(search);
            this.list = adopt(list);
        }

        void setMaxHeight(int maxHeight) {
            this.maxHeight = Math.max(LIST_MIN_HEIGHT, maxHeight);
        }

        @Override
        public List<Node> children() {
            return List.of(search, list);
        }

        @Override
        protected Size measureContent(UiContext ctx, int maxWidth) {
            int width = LIST_WIDTH - 2;
            int inner = width - 2 * PAD;
            int natural = 2 * PAD + search.measure(ctx, inner).height() + ctx.theme().gap
                    + list.measure(ctx, inner).height();
            return new Size(width, Math.min(maxHeight, natural));
        }

        @Override
        public void layout(UiContext ctx, Rect bounds) {
            super.layout(ctx, bounds);
            Rect inner = bounds.inset(PAD);
            int searchHeight = search.measure(ctx, inner.width()).height();
            search.layout(ctx, new Rect(inner.x(), inner.y(), inner.width(), searchHeight));
            int top = Math.min(inner.bottom(), inner.y() + searchHeight + ctx.theme().gap);
            list.layout(ctx, Rect.ofEdges(inner.x(), top, inner.right(), inner.bottom()));
        }
    }

    /** A page (or a section of one, in search results) in the list; the page shown is highlighted. */
    private final class PageRow extends AbstractButton {
        final String pageId;
        final String anchor;
        final String text;
        final String detail;

        PageRow(String pageId, String anchor, String text, String detail) {
            super(null);
            this.pageId = pageId;
            this.anchor = anchor;
            this.text = text;
            this.detail = detail;
            setOnClick(() -> {
                closeList();
                open(pageId, anchor);
            });
        }

        @Override
        protected Size measureContent(UiContext ctx, int maxWidth) {
            Theme theme = ctx.theme();
            int width = theme.headingStripWidth + theme.rowInset + ctx.text().width(text) + theme.rowInset;
            return new Size(width, theme.rowHeight + 2);
        }

        @Override
        public String tooltip() {
            return detail.isEmpty() ? text : text + " · " + detail;
        }

        @Override
        public void render(UiGraphics g, UiContext ctx) {
            Theme theme = ctx.theme();
            boolean shown = pageId.equals(current) && anchor == null && query.isEmpty();
            if (shown) {
                g.fill(bounds, theme.accentDim);
            } else if (ctx.isHovered(this)) {
                g.fill(bounds, theme.rowHover);
            }
            if (ctx.isFocused(this)) {
                g.outline(bounds, theme.focusRing);
            }
            int x = bounds.x() + theme.headingStripWidth + theme.rowInset;
            int y = bounds.y() + (bounds.height() - ctx.text().lineHeight() + 1) / 2;
            int room = bounds.right() - theme.rowInset - x;
            String shownText = TextLayout.ellipsize(ctx.text(), text, room);
            g.text(shownText, x, y, shown ? theme.textOnAccent : theme.text, theme.textShadow);
            if (!detail.isEmpty()) {
                int detailX = x + ctx.text().width(shownText) + theme.gap;
                String shownDetail = TextLayout.ellipsize(ctx.text(), detail, bounds.right() - theme.rowInset - detailX);
                if (!shownDetail.isEmpty() && !shownDetail.equals(TextLayout.ELLIPSIS)) {
                    g.text(shownDetail, detailX, y, theme.textDim, theme.textShadow);
                }
            }
        }
    }

    /**
     * The page's scroll pane: jumps to a heading once the page is laid out; the mouse's side buttons go back and
     * forward, and so do Backspace and Alt+Left / Alt+Right while the pane has the keyboard (a click on the page).
     */
    private final class PagePane extends ScrollPane {
        private String pendingAnchor;

        PagePane(WikiPageView view) {
            super(view);
            setFocusable(true);
        }

        void jumpTo(String anchor) {
            pendingAnchor = anchor;
        }

        @Override
        public void layout(UiContext ctx, Rect bounds) {
            super.layout(ctx, bounds);
            if (pendingAnchor != null && !bounds.isEmpty()) {
                String anchor = pendingAnchor;
                pendingAnchor = null;
                OptionalInt y = view.anchorY(ctx, view.bounds().width(), anchor);
                if (y.isPresent()) {
                    restoreOffset(y.getAsInt());
                    super.layout(ctx, bounds);
                }
            }
        }

        @Override
        public boolean mouseDown(UiContext ctx, double x, double y, int button) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_4) {
                back();
                return true;
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_5) {
                forward();
                return true;
            }
            return super.mouseDown(ctx, x, y, button);
        }

        @Override
        public boolean keyPressed(UiContext ctx, int keyCode, int scanCode, int modifiers) {
            boolean alt = (modifiers & GLFW.GLFW_MOD_ALT) != 0;
            boolean plain = (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SHIFT)) == 0;
            if ((keyCode == GLFW.GLFW_KEY_BACKSPACE && plain) || (keyCode == GLFW.GLFW_KEY_LEFT && alt)) {
                return back();
            }
            if (keyCode == GLFW.GLFW_KEY_RIGHT && alt) {
                return forward();
            }
            return super.keyPressed(ctx, keyCode, scanCode, modifiers);
        }
    }
}
