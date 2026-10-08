package dev.sculptory.fabric.client.editor.heightmap;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.PopupLayer;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.FlowRow;
import dev.sculptory.fabric.client.editor.ui.widget.AbstractButton;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.ScrollPane;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The height image picker popup: a tile per picture, its thumbnail over its name, the built-ins first and then the
 * builder's own (with a line saying where to drop them, and a button opening that folder). The library looks at its
 * folder again as the picker opens, so new files show at once; files it can't read are named in a toast. Click a tile
 * to choose it; Esc or a click outside closes the picker.
 */
public final class HeightmapPicker {
    public static final int WIDTH = 4 * Tile.WIDTH + 3 * 4 + 12;
    /** More rows than this scroll. */
    private static final int VISIBLE_ROWS = 3;

    private HeightmapPicker() {}

    /** What the picker needs beyond the library. */
    public interface Host {
        /** Says, in one line, that a file in the folder can't be used. */
        void badFile(HeightmapLibrary.BadFile bad);

        /** Opens the folder in the system's file browser. */
        void openFolder(Path folder);
    }

    /** The pictures the picker lists, in its order, after looking at the folder again (bad files go to {@code host}). */
    public static List<HeightmapLibrary.Entry> list(HeightmapLibrary library, Host host) {
        for (HeightmapLibrary.BadFile bad : library.refresh()) host.badFile(bad);
        return library.entries();
    }

    /** Opens the picker below {@code anchor}; {@code onPick} runs with the picture chosen, after it closes. */
    public static PopupLayer.Popup open(UiContext ctx, Rect anchor, HeightmapLibrary library, HeightmapView view,
            Translator translator, HeightmapRef current, Host host, Consumer<HeightmapRef> onPick) {
        Objects.requireNonNull(onPick);
        List<HeightmapLibrary.Entry> entries = list(library, host);
        PopupLayer.Popup[] popup = new PopupLayer.Popup[1];
        FlowRow tiles = new FlowRow();
        tiles.setGap(4);
        List<Node> made = new ArrayList<>();
        for (HeightmapLibrary.Entry entry : entries) {
            Tile tile = new Tile(view, entry.ref(), HeightmapView.label(translator, entry.ref()),
                    entry.ref().equals(current));
            tile.setOnClick(() -> {
                ctx.popups().close(popup[0]);
                onPick.accept(entry.ref());
            });
            tiles.add(tile);
            made.add(tile);
        }
        Node grid = tiles;
        int rows = (entries.size() + 3) / 4;
        if (rows > VISIBLE_ROWS) {
            ScrollPane scroll = new ScrollPane(tiles);
            scroll.setFixedHeight(VISIBLE_ROWS * (Tile.HEIGHT + 4));
            grid = scroll;
        }
        Column content = Column.of(grid);
        library.folder().ifPresent(folder -> {
            Label where = Label.dim(translator.translate("sculptory.heightmap.picker.folder", folder.toString()));
            where.setWrap(true);
            content.add(where);
            content.add(new Button(translator.translate("sculptory.heightmap.picker.open_folder"),
                    () -> host.openFolder(folder)));
        });
        content.setGap(4);
        content.setFixedWidth(WIDTH - 2);
        popup[0] = ctx.popups().open(null, content, anchor, WIDTH, null);
        made.stream().filter(n -> ((Tile) n).chosen).findFirst().ifPresent(ctx::setFocus);
        return popup[0];
    }

    /** One picture: its thumbnail over its name; the chosen one outlined. */
    static final class Tile extends AbstractButton {
        static final int WIDTH = 52;
        static final int HEIGHT = 62;
        private static final int THUMB = 48;
        private final HeightmapView view;
        private final HeightmapRef ref;
        private final String name;
        final boolean chosen;

        Tile(HeightmapView view, HeightmapRef ref, String name, boolean chosen) {
            super(null);
            this.view = view;
            this.ref = ref;
            this.name = name;
            this.chosen = chosen;
            setTooltip(name);
        }

        @Override
        protected Size measureContent(UiContext ctx, int maxWidth) {
            return new Size(WIDTH, HEIGHT);
        }

        @Override
        public void render(UiGraphics g, UiContext ctx) {
            Theme theme = ctx.theme();
            boolean hovered = ctx.isHovered(this);
            g.fill(bounds, hovered ? theme.controlHover : theme.control);
            int x = bounds.x() + (bounds.width() - THUMB) / 2, y = bounds.y() + 2;
            g.fill(x, y, THUMB, THUMB, 0xFF000000);
            view.thumbnail(ref).ifPresent(t -> g.texture(t.texture(), x, y, THUMB, THUMB, t.width(), t.height()));
            String shown = TextLayout.ellipsize(ctx.text(), name, bounds.width() - 2);
            g.text(shown, bounds.x() + (bounds.width() - ctx.text().width(shown)) / 2, y + THUMB + 2,
                    chosen ? theme.accentHover : theme.text, theme.textShadow);
            g.outline(bounds, chosen || ctx.isFocused(this) ? theme.focusRing : theme.controlBorder);
        }
    }
}
