package dev.sculptory.fabric.client.editor.windows;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.clipboard.ClipboardActions;
import dev.sculptory.fabric.client.editor.clipboard.LibraryPaths;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.FlowRow;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.ListView;
import dev.sculptory.fabric.client.editor.ui.widget.ScrollPane;
import dev.sculptory.fabric.client.editor.ui.widget.TextInput;
import dev.sculptory.fabric.client.session.ClipboardCache;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.LibraryChange;
import dev.sculptory.fabric.client.session.LibraryChanges;
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
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/**
 * The Library window (L): breadcrumb navigation through the server's library folders, a search filter over the
 * listed names (on this client), and per entry its size, plus its dimensions once its preview has been downloaded
 * (the listing itself carries no dimensions or tags). Place previews an asset in the Place tool, Load puts it in the
 * clipboard, Add to scatter adds it to the Scatter tool's mix, Refresh lists the folder again, and Save clipboard as…
 * saves into the library. Where the server says the player may change the folder, New folder, Rename, Move… and
 * Delete manage it ({@link LibraryManageBar}, M4). {@code .schem} files dropped on the editor are imported (see
 * {@link ClipboardActions#upload}); their progress shows here. Block palettes ({@code .palette.json}) are listed with a
 * "palette" label and can be renamed, moved and deleted like assets; they are loaded from the tool settings of Paint,
 * Palette Paint and Scatter, so Place, Load and Add to scatter stay off for them. Per-asset access: Access… in the
 * row sets who may load an entry; restricted entries carry a marker; the root's "Shared with me" folder lists what
 * other players granted this one (read-only, with the entries' real paths).
 *
 * <p>The window follows the session's {@link LibraryChanges}: when this player or another one changes the folder shown
 * (or renames or deletes it, or a folder it is in), it lists the folder again, or the renamed one, or the nearest
 * folder still there.
 *
 * <p>The window's content scrolls when the window is short, and the list takes the room of a tall one; the breadcrumb and
 * the button rows wrap onto more lines when it is narrow.
 *
 * <p>The folder is listed the first time the window is shown; call {@link #refresh()} every frame while it is open.
 */
public final class LibraryWindow {
    private final Supplier<Optional<EditorSession>> session;
    private final ClipboardActions actions;
    private final Supplier<UiContext> popups;
    private final Translator tr;
    private final FlowRow breadcrumb = new FlowRow();
    private final TextInput search;
    private final ListView<S2C.LibraryListing.Entry> list;
    private final Label status = Label.dim("");
    private final TransferBars transfers;
    private final Button place;
    private final Button load;
    private final Button addToScatter;
    /** The Scatter tool's Add (path, content hash), once the editor has one. */
    private BiPredicate<String, String> scatterTarget;
    private final Button refreshButton;
    private final LibraryManageBar manage;
    private final Node root;
    private String folder = "";
    private String shownFolder;
    private List<S2C.LibraryListing.Entry> entries = List.of();
    private String filter = "";
    private boolean listed;
    private boolean loading;
    private long cacheBytes = -1;
    /** Whether the server lets the player change the folder shown (its last listing). */
    private boolean writable;
    /** An entry to select once the folder is listed again (after this window renamed or made it), or null. */
    private String selectAfterList;
    /** The change log followed, and how far. */
    private LibraryChanges followed;
    /** Listings in flight, and the folder to list once they are answered (changes coalesce), or null. */
    private int listing;
    private String relistAfter;
    private long followedVersion;

    public LibraryWindow(Supplier<Optional<EditorSession>> session, ClipboardActions actions, Supplier<UiContext> popups,
            Translator translator) {
        this(session, actions, popups, translator, List::of);
    }

    /** With the players online now ({@code onlinePlayers}) for the Access dialog's picker. */
    public LibraryWindow(Supplier<Optional<EditorSession>> session, ClipboardActions actions, Supplier<UiContext> popups,
            Translator translator, Supplier<List<String>> onlinePlayers) {
        this.session = Objects.requireNonNull(session);
        this.actions = Objects.requireNonNull(actions);
        this.popups = Objects.requireNonNull(popups);
        this.tr = Objects.requireNonNull(translator);
        Objects.requireNonNull(onlinePlayers);
        this.transfers = new TransferBars(tr);
        breadcrumb.setGap(1);
        search = new TextInput("", text -> {
            filter = text.strip().toLowerCase(Locale.ROOT);
            showEntries();
        });
        search.setPlaceholder(tr.translate("sculptory.library.search"));
        list = new ListView<>(List.of(), (entry, index) -> Label.of(describe(entry)));
        list.setPreferredRows(8);
        list.setGrow(1);
        list.setOnActivate(index -> list.selectedItem().ifPresent(this::open));
        status.setWrap(true);
        place = button("sculptory.library.place", "sculptory.library.place.tooltip", this::placeSelected);
        place.setStyle(Button.Style.PRIMARY);
        load = button("sculptory.library.load", "sculptory.library.load.tooltip", this::loadSelected);
        addToScatter = button("sculptory.library.add_to_scatter", "sculptory.library.add_to_scatter.tooltip",
                this::addSelectedToScatter);
        addToScatter.setVisible(false);
        refreshButton = button("sculptory.library.refresh", "sculptory.library.refresh.tooltip", () -> list(folder));
        Button save = button("sculptory.library.save", "sculptory.library.save.tooltip", () -> { });
        save.setOnClick(() -> SaveAssetDialog.open(popups.get(), save.bounds(), tr,
                LibraryPaths.join(folder, "my_build.schem"), actions.format(),
                path -> actions.saveClipboard(path).thenAccept(reply -> {
                    if (reply.isOk() && LibraryPaths.parent(path).equals(folder)) list(folder);
                })));
        manage = new LibraryManageBar(new LibraryManageBar.Host() {
            @Override
            public Optional<EditorSession> session() {
                return LibraryWindow.this.session.get();
            }

            @Override
            public String folder() {
                return folder;
            }

            @Override
            public Optional<S2C.LibraryListing.Entry> selected() {
                return list.selectedItem();
            }

            @Override
            public void changed(String path) {
                selectAfterList = path;
            }

            @Override
            public List<String> onlinePlayers() {
                return onlinePlayers.get();
            }
        }, tr, popups);
        Label drop = Label.dim(tr.translate("sculptory.library.drop"));
        drop.setWrap(true);
        Column column = Column.of(
                breadcrumb,
                search,
                list,
                status,
                transfers.node(),
                FlowRow.of(grow(place), grow(load), grow(refreshButton)),
                FlowRow.of(grow(addToScatter)),
                manage.node(),
                FlowRow.of(grow(save)),
                drop);
        column.setGap(4);
        // A short window scrolls; a tall one gives the list the room.
        root = new ScrollPane(column);
        showBreadcrumb();
    }

    public Node node() {
        return root;
    }

    /** The folder shown ({@code ""} is the root). */
    public String folder() {
        return folder;
    }

    /** The entries shown after the search filter. */
    public List<S2C.LibraryListing.Entry> shown() {
        return list.items();
    }

    public void refresh() {
        if (!listed && session.get().isPresent()) {
            listed = true;
            list(folder);
        }
        Optional<S2C.LibraryListing.Entry> selected = list.selectedItem();
        boolean file = selected.isPresent() && !selected.get().folder();
        place.setEnabled(file && LibraryFolder.asset(selected.get().contentHash()).isPresent());
        // A palette is loaded from the Paint, Palette Paint or Scatter tool settings, never into the clipboard.
        load.setEnabled(file && !palette(selected.get()));
        addToScatter.setEnabled(file && LibraryFolder.asset(selected.get().contentHash()).isPresent());
        refreshButton.setEnabled(!loading);
        followChanges();
        manage.refresh(writable);
        long bytes = session.get().map(s -> s.clipboards().previewBytes()).orElse(0L);
        if (bytes != cacheBytes) {
            // A preview arrived (or was evicted): the dimensions shown may have changed.
            cacheBytes = bytes;
            showEntries();
        }
        transfers.update(session.get().map(EditorSession::transfers).orElse(List.of()));
    }

    /** The management row (M4), for tests. */
    public LibraryManageBar manageBar() {
        return manage;
    }

    /** The Load button (tests: off for palettes). */
    public Button loadButton() {
        return load;
    }

    /** The selected entry, if any. */
    public Optional<S2C.LibraryListing.Entry> selected() {
        return list.selectedItem();
    }

    /** Selects the listed entry with this path, as a click would (tests); false when it is not shown. */
    public boolean select(String path) {
        int index = list.items().stream().map(S2C.LibraryListing.Entry::path).toList().indexOf(path);
        if (index < 0) return false;
        list.setSelectedIndex(index);
        return true;
    }

    /** Whether the folder shown may be changed, as its last listing said. */
    public boolean writable() {
        return writable;
    }

    /**
     * Lists the folder again when a library change touched it: something in it was made, renamed, moved or deleted,
     * something was made or moved anywhere below it (its way may have made a new folder here), or it (or a folder it
     * is in) was renamed, then its new name is listed, or deleted, then its parent is.
     */
    private void followChanges() {
        Optional<EditorSession> current = session.get();
        if (current.isEmpty()) return;
        LibraryChanges log = current.get().libraryChanges();
        if (log != followed) {
            // A new session: its changes started after this window's last listing, which the first refresh redoes.
            followed = log;
            followedVersion = log.version();
            return;
        }
        if (log.version() == followedVersion) return;
        Optional<List<LibraryChange>> changes = log.since(followedVersion);
        followedVersion = log.version();
        String target = folder;
        boolean again = changes.isEmpty();
        for (LibraryChange change : changes.orElse(List.of())) {
            String from = change.from();
            if (folder.equals(LibraryPath.SHARED)) {
                // Shared with me holds entries from anywhere: any file change (a grant, a revoke, a rename or a
                // deletion in another player's folder) may have changed it.
                again |= !change.folder();
                continue;
            }
            if (change.folder() && !from.isEmpty() && (target.equals(from) || target.startsWith(from + "/"))) {
                target = change.to().isEmpty() ? LibraryPaths.parent(from) : change.to() + target.substring(from.length());
                again = true;
            } else if ((!from.isEmpty() && LibraryPaths.parent(from).equals(target)) || inside(change.to(), target)) {
                // A new path anywhere below the folder may have made the folders on its way (a save into
                // palettes/x.palette.json, or into a player's own folder), so it is listed again.
                again = true;
            }
        }
        if (!again || !listed) return;
        // While a listing is in flight, a burst of changes lists once more afterwards, not once per change.
        if (listing > 0) {
            relistAfter = target;
        } else {
            request(target);
        }
    }

    /** Whether {@code path} lies below {@code folder} (at any depth; {@code ""} lies nowhere). */
    static boolean inside(String path, String folder) {
        if (path.isEmpty()) return false;
        return folder.isEmpty() || path.startsWith(folder + "/");
    }

    /** Lists {@code target} (the root is {@code ""}). */
    public void list(String target) {
        relistAfter = null; // the player's choice wins over a change's
        request(target);
    }

    private void request(String target) {
        Optional<EditorSession> current = session.get();
        if (current.isEmpty()) return;
        listing++;
        loading = true;
        status.setText(tr.translate("sculptory.library.loading"));
        current.get().libraryList(target).thenAccept(this::listed);
    }

    private void listed(Reply<LibraryFolder> reply) {
        listing = Math.max(0, listing - 1);
        loading = listing > 0;
        if (reply instanceof Reply.Ok<LibraryFolder> ok) {
            folder = ok.value().folder();
            List<S2C.LibraryListing.Entry> sorted = new ArrayList<>(ok.value().entries());
            // Folders first, Shared with me last among them, each group by name.
            sorted.sort(Comparator.comparing((S2C.LibraryListing.Entry entry) -> !entry.folder())
                    .thenComparing(LibraryWindow::shared)
                    .thenComparing(entry -> LibraryPaths.name(entry.path()).toLowerCase(Locale.ROOT)));
            entries = List.copyOf(sorted);
            writable = ok.value().writable();
            showEntries();
            if (selectAfterList != null) {
                int index = list.items().stream().map(S2C.LibraryListing.Entry::path).toList().indexOf(selectAfterList);
                if (index >= 0) list.setSelectedIndex(index);
                selectAfterList = null;
            }
            showBreadcrumb();
        } else {
            status.setText(tr.translate("sculptory.library.failed"));
        }
        if (listing == 0 && relistAfter != null) {
            String next = relistAfter;
            relistAfter = null;
            request(next);
        }
    }

    /** Applies the search filter; keeps the selection when it is still listed. */
    private void showEntries() {
        Optional<S2C.LibraryListing.Entry> selected = list.selectedItem();
        List<S2C.LibraryListing.Entry> matching = entries.stream()
                .filter(entry -> filter.isEmpty()
                        || LibraryPaths.name(entry.path()).toLowerCase(Locale.ROOT).contains(filter))
                .toList();
        list.setItems(matching);
        list.setSelectedIndex(selected.map(matching::indexOf).orElse(-1));
        list.setEmptyText(tr.translate(entries.isEmpty() ? "sculptory.library.empty" : "sculptory.library.no_match"));
        if (!loading) {
            status.setText(tr.translate("sculptory.library.count", Integer.toString(matching.size()),
                    Integer.toString(entries.size())));
        }
    }

    private void showBreadcrumb() {
        if (folder.equals(shownFolder)) return;
        shownFolder = folder;
        breadcrumb.clear();
        breadcrumb.add(crumb(tr.translate("sculptory.library.root"), ""));
        if (folder.equals(LibraryPath.SHARED)) {
            breadcrumb.add(Label.dim("›"));
            breadcrumb.add(crumb(tr.translate("sculptory.library.shared"), LibraryPath.SHARED));
        } else if (!folder.isEmpty()) {
            String path = "";
            for (String segment : folder.split("/")) {
                path = LibraryPaths.join(path, segment);
                breadcrumb.add(Label.dim("›"));
                breadcrumb.add(crumb(segment, path));
            }
        }
    }

    private Button crumb(String text, String target) {
        Button button = new Button(text, () -> list(target));
        button.setStyle(Button.Style.FLAT);
        return button;
    }

    /**
     * An entry's row: "trees/", "oak.schem  ·  12 KB  ·  7×12×7", or (palettes) "moss.palette.json  ·  palette  ·
     * 1 KB"; a restricted entry (per-asset access) carries a "[restricted]" marker after its name, and the virtual
     * {@code _shared} folder reads "Shared with me/".
     */
    public String describe(S2C.LibraryListing.Entry entry) {
        String name = LibraryPaths.name(entry.path());
        if (entry.folder()) return (shared(entry) ? tr.translate("sculptory.library.shared") : name) + "/";
        if (entry.restricted()) name = name + " " + tr.translate("sculptory.library.restricted");
        if (palette(entry)) {
            return name + "  ·  " + tr.translate("sculptory.library.palette") + "  ·  " + size(entry.bytes());
        }
        StringBuilder text = new StringBuilder(name).append("  ·  ").append(size(entry.bytes()));
        Optional<SourceRef> asset = LibraryFolder.asset(entry.contentHash());
        if (asset.isPresent()) {
            Optional<ClipboardCache.Preview> preview = session.get().flatMap(s -> s.clipboards().preview(asset.get()));
            if (preview.isPresent()) {
                BlockPos dims = preview.get().dims();
                text.append("  ·  ").append(dims.x()).append('×').append(dims.y()).append('×').append(dims.z());
            }
        }
        return text.toString();
    }

    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes + 512) / 1024 + " KB";
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** Whether an entry is a block palette (palettes). */
    static boolean palette(S2C.LibraryListing.Entry entry) {
        return entry.kind() == S2C.LibraryListing.Entry.Kind.PALETTE;
    }

    /** Whether an entry is the virtual "Shared with me" folder (per-asset access). */
    static boolean shared(S2C.LibraryListing.Entry entry) {
        return entry.folder() && entry.path().equals(LibraryPath.SHARED);
    }

    /** Opens a folder, or places an asset; a palette is only managed here. */
    private void open(S2C.LibraryListing.Entry entry) {
        if (entry.folder()) {
            list(entry.path());
        } else if (!palette(entry)) {
            actions.placeAsset(entry.path(), entry.contentHash());
        }
    }

    private void placeSelected() {
        list.selectedItem().filter(entry -> !entry.folder())
                .ifPresent(entry -> actions.placeAsset(entry.path(), entry.contentHash()));
    }

    /**
     * Shows the Add to scatter button, which hands the selected asset (path, content hash) to {@code target} (the
     * Scatter tool); {@code target} returns whether it was added.
     */
    public void setAddToScatter(BiPredicate<String, String> target) {
        scatterTarget = Objects.requireNonNull(target);
        addToScatter.setVisible(true);
    }

    private void addSelectedToScatter() {
        if (scatterTarget == null) return;
        list.selectedItem().filter(entry -> !entry.folder())
                .ifPresent(entry -> scatterTarget.test(entry.path(), entry.contentHash()));
    }

    private void loadSelected() {
        list.selectedItem().filter(entry -> !entry.folder() && !palette(entry))
                .ifPresent(entry -> actions.load(entry.path()));
    }

    private Button button(String textKey, String tooltipKey, Runnable action) {
        Button button = new Button(tr.translate(textKey), action);
        button.setTooltip(tr.translate(tooltipKey));
        return button;
    }

    private static <T extends Node> T grow(T node) {
        node.setGrow(1);
        return node;
    }
}
