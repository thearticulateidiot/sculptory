package dev.sculptory.fabric.client.editor.palettes;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.clipboard.LibraryPaths;
import dev.sculptory.fabric.client.editor.ui.Insets;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.PopupLayer;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.Padding;
import dev.sculptory.fabric.client.editor.ui.layout.Row;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.ListView;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.LibraryFolder;
import dev.sculptory.fabric.client.session.Reply;
import dev.sculptory.fabric.library.LibraryPath;
import dev.sculptory.protocol.v2.S2C;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The palette picker (Load palette…): the server library's folders and palettes, one folder at a time. A click on a
 * folder opens it, Up goes to the folder above, and a click on a palette picks it (the popup closes). Schematics are
 * not shown. Folders and palettes are listed as the server lists them to this player (their own player folder
 * included), so it shows only what the player may load.
 */
public final class PalettePicker {
    public static final int WIDTH = 220;

    private final Supplier<Optional<EditorSession>> session;
    private final Translator tr;
    private final Consumer<String> onPick;
    private final Label folderLabel = Label.dim("");
    private final Label status = Label.dim("");
    private final Button up;
    private final ListView<S2C.LibraryListing.Entry> list;
    private final Node root;
    private String folder;
    /** Listings asked for; only the answer to the last one is shown. */
    private int asked;

    /** @param onPick gets the chosen palette's path */
    public PalettePicker(Supplier<Optional<EditorSession>> session, Translator translator, String folder,
                         Consumer<String> onPick) {
        this.session = Objects.requireNonNull(session);
        this.tr = Objects.requireNonNull(translator);
        this.onPick = Objects.requireNonNull(onPick);
        this.folder = Objects.requireNonNull(folder);
        list = new ListView<>(List.of(), (entry, index) -> Label.of(describe(entry)));
        list.setPreferredRows(8);
        list.setActivateOnClick(true);
        list.setOnActivate(index -> activate(list.items().get(index)));
        up = new Button(tr.translate("sculptory.palette.picker.up"), () -> list(LibraryPaths.parent(this.folder)));
        up.setTooltip(tr.translate("sculptory.palette.picker.up.tooltip"));
        folderLabel.setGrow(1);
        status.setWrap(true);
        Label hint = Label.dim(tr.translate("sculptory.palette.picker.hint"));
        hint.setWrap(true);
        Column column = Column.of(Label.heading(tr.translate("sculptory.palette.picker.title")),
                Row.of(folderLabel, up), list, status, hint);
        column.setGap(4);
        column.setFixedWidth(WIDTH - 12);
        root = new Padding(Insets.all(6), column);
        list(folder);
    }

    /** Opens the picker below {@code anchor}, listing {@code folder}; {@code onPick} runs after it closed. */
    public static PopupLayer.Popup open(UiContext ctx, Rect anchor, Supplier<Optional<EditorSession>> session,
                                        Translator translator, String folder, Consumer<String> onPick) {
        PopupLayer.Popup[] popup = new PopupLayer.Popup[1];
        PalettePicker picker = new PalettePicker(session, translator, folder, path -> {
            ctx.popups().close(popup[0]);
            onPick.accept(path);
        });
        popup[0] = ctx.popups().open(null, picker.node(), anchor, WIDTH, null);
        return popup[0];
    }

    public Node node() {
        return root;
    }

    /** The folder shown. */
    public String folder() {
        return folder;
    }

    /** The entries shown: sub-folders, then palettes, each by name. */
    public List<S2C.LibraryListing.Entry> shown() {
        return list.items();
    }

    /** Opens a folder, or picks a palette (as a click does). */
    public void activate(S2C.LibraryListing.Entry entry) {
        if (entry.folder()) {
            list(entry.path());
        } else if (entry.kind() == S2C.LibraryListing.Entry.Kind.PALETTE) {
            onPick.accept(entry.path());
        }
    }

    /** Lists {@code target} ({@code ""} is the root). */
    public void list(String target) {
        Optional<EditorSession> current = session.get();
        if (current.isEmpty()) return;
        int request = ++asked;
        status.setText(tr.translate("sculptory.library.loading"));
        current.get().libraryList(target).thenAccept(reply -> listed(request, target, reply));
    }

    private void listed(int request, String target, Reply<LibraryFolder> reply) {
        if (request != asked) return;
        if (!(reply instanceof Reply.Ok<LibraryFolder> ok)) {
            status.setText(tr.translate("sculptory.library.failed"));
            return;
        }
        folder = ok.value().folder();
        List<S2C.LibraryListing.Entry> entries = palettesAndFolders(ok.value().entries());
        list.setItems(entries);
        list.setSelectedIndex(-1);
        list.setEmptyText(tr.translate("sculptory.palette.picker.empty"));
        folderLabel.setText(folder.isEmpty() ? tr.translate("sculptory.library.root")
                : folder.equals(LibraryPath.SHARED) ? tr.translate("sculptory.library.shared") + "/" : folder + "/");
        up.setEnabled(!folder.isEmpty());
        long palettes = entries.stream().filter(entry -> !entry.folder()).count();
        status.setText(tr.translate("sculptory.palette.picker.count", Long.toString(palettes)));
    }

    /** The folders and palettes of a listing (no schematics), folders first, each sorted by name ignoring case. */
    static List<S2C.LibraryListing.Entry> palettesAndFolders(List<S2C.LibraryListing.Entry> entries) {
        List<S2C.LibraryListing.Entry> shown = new ArrayList<>();
        for (S2C.LibraryListing.Entry entry : entries) {
            if (entry.folder() || entry.kind() == S2C.LibraryListing.Entry.Kind.PALETTE) shown.add(entry);
        }
        // Folders first, Shared with me (per-asset access) last among them, each group by name.
        shown.sort(Comparator.comparing((S2C.LibraryListing.Entry entry) -> !entry.folder())
                .thenComparing(PalettePicker::shared)
                .thenComparing(entry -> LibraryPaths.name(entry.path()).toLowerCase(Locale.ROOT)));
        return List.copyOf(shown);
    }

    /** Whether an entry is the virtual "Shared with me" folder (per-asset access). */
    private static boolean shared(S2C.LibraryListing.Entry entry) {
        return entry.folder() && entry.path().equals(LibraryPath.SHARED);
    }

    /** "trees/" ("Shared with me/" for the virtual folder), or a palette's name without its extension. */
    private String describe(S2C.LibraryListing.Entry entry) {
        if (shared(entry)) return tr.translate("sculptory.library.shared") + "/";
        return entry.folder() ? LibraryPaths.name(entry.path()) + "/" : LibraryPaths.stem(entry.path());
    }
}
