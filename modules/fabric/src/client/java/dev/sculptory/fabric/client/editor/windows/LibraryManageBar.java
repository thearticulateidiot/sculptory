package dev.sculptory.fabric.client.editor.windows;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.clipboard.LibraryPaths;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.FlowRow;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.TextPrompt;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.LibraryChange;
import dev.sculptory.fabric.client.session.Reply;
import dev.sculptory.protocol.v2.AssetAccess;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.server.library.LibraryPath;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * The Library window's management row (M4): New folder, Rename, Move…, Access… and Delete. It shows only while the
 * server's listing says the player may change the folder ({@code writable}); the server checks every request again
 * and the session toasts its refusals. Names and folders are asked for in a {@link TextPrompt} and checked as they are
 * typed with the server's own path rules ({@link LibraryPaths}). Rename keeps the entry in its folder, and a file its
 * kind (a palette keeps {@code .palette.json}); Move… (files only: assets and palettes) asks for another folder.
 * Access… (files only; per-asset access) opens {@link LibraryAccessDialog} with what the server says now: who may
 * change a folder's entries is exactly who may change their access. Delete asks for confirmation in place of the
 * row; files go to the server's trash, and only an empty folder can be deleted. {@code _players} and the player
 * folders in it are never offered.
 */
public final class LibraryManageBar {
    static final int DIALOG_WIDTH = 220;

    /** What the bar needs from its window. */
    interface Host {
        Optional<EditorSession> session();

        /** The folder shown ({@code ""} is the root). */
        String folder();

        Optional<S2C.LibraryListing.Entry> selected();

        /** A change of this bar's went through: select {@code path} once the folder is listed again. */
        void changed(String path);

        /** The players online now, for the Access dialog's picker. */
        default List<String> onlinePlayers() {
            return List.of();
        }
    }

    private final Host host;
    private final Translator tr;
    private final Supplier<UiContext> popups;
    private final Column root = new Column();
    private final Button newFolder;
    private final Button rename;
    private final Button move;
    private final Button access;
    private final Button delete;
    private final FlowRow actions;
    private Button confirmDelete;
    /** The entry waiting for Delete to be confirmed, or null. */
    private S2C.LibraryListing.Entry confirming;
    private boolean shownConfirming;
    /** The Access dialog open now, or null (tests). */
    private LibraryAccessDialog accessDialog;

    LibraryManageBar(Host host, Translator tr, Supplier<UiContext> popups) {
        this.host = Objects.requireNonNull(host);
        this.tr = Objects.requireNonNull(tr);
        this.popups = Objects.requireNonNull(popups);
        newFolder = button("sculptory.library.new_folder", "sculptory.library.new_folder.tooltip");
        newFolder.setOnClick(this::promptNewFolder);
        rename = button("sculptory.library.rename", "sculptory.library.rename.tooltip");
        rename.setOnClick(this::promptRename);
        move = button("sculptory.library.move", "sculptory.library.move.tooltip");
        move.setOnClick(this::promptMove);
        access = button("sculptory.library.access", "sculptory.library.access.tooltip");
        access.setOnClick(this::openAccess);
        delete = button("sculptory.library.delete", "sculptory.library.delete.tooltip");
        delete.setOnClick(() -> {
            confirming = host.selected().filter(this::changeable).orElse(null);
            rebuild();
        });
        actions = FlowRow.of(newFolder, rename, move, access, delete);
        actions.setGap(0);
        root.setGap(3);
        rebuild();
    }

    Node node() {
        return root;
    }

    /** Follows the listing and the selection; call every frame while the window is shown. */
    void refresh(boolean writable) {
        root.setVisible(writable);
        Optional<S2C.LibraryListing.Entry> selected = host.selected();
        if (confirming != null && (!writable || !selected.equals(Optional.of(confirming)))) confirming = null;
        if ((confirming != null) != shownConfirming) rebuild();
        boolean changeable = selected.filter(this::changeable).isPresent();
        rename.setEnabled(writable && changeable);
        delete.setEnabled(writable && changeable);
        move.setEnabled(writable && changeable && !selected.get().folder());
        access.setEnabled(writable && changeable && !selected.get().folder());
        newFolder.setEnabled(writable);
        if (accessDialog != null && !accessDialog.isOpen()) accessDialog = null;
    }

    // ---- For tests ----

    public Button newFolderButton() {
        return newFolder;
    }

    public Button renameButton() {
        return rename;
    }

    public Button moveButton() {
        return move;
    }

    public Button accessButton() {
        return access;
    }

    /** The Access dialog while it is open. */
    public Optional<LibraryAccessDialog> accessDialog() {
        return Optional.ofNullable(accessDialog).filter(LibraryAccessDialog::isOpen);
    }

    public Button deleteButton() {
        return delete;
    }

    /** The confirming Delete button while a delete waits for it. */
    public Optional<Button> confirmDeleteButton() {
        return Optional.ofNullable(confirming == null ? null : confirmDelete);
    }

    public boolean visible() {
        return root.isVisible();
    }

    // ---- Actions ----

    /** Entries the player may rename or delete here: not {@code _players} or a player folder. */
    private boolean changeable(S2C.LibraryListing.Entry entry) {
        return !(entry.folder() && LibraryPaths.reserved(entry.path()));
    }

    private void promptNewFolder() {
        String folder = host.folder();
        prompt(newFolder, "sculptory.library.new_folder.title", "sculptory.library.name.placeholder",
                "sculptory.library.name.hint", "sculptory.library.new_folder.submit", "",
                typed -> nameCheck(LibraryPaths.newName(folder, typed, false, null)),
                typed -> {
                    LibraryPaths.Target target = LibraryPaths.newName(folder, typed, false, null);
                    run(session -> session.libraryCreateFolder(target.path()), target.path());
                });
    }

    private void promptRename() {
        S2C.LibraryListing.Entry entry = host.selected().filter(this::changeable).orElse(null);
        if (entry == null) return;
        String folder = LibraryPaths.parent(entry.path());
        String current = LibraryPaths.name(entry.path());
        prompt(rename, "sculptory.library.rename.title", "sculptory.library.name.placeholder",
                "sculptory.library.name.hint", "sculptory.library.rename.submit", current,
                typed -> nameCheck(LibraryPaths.newName(folder, typed, kind(entry), entry.path())),
                typed -> {
                    LibraryPaths.Target target = LibraryPaths.newName(folder, typed, kind(entry), entry.path());
                    run(session -> session.libraryMove(entry.path(), target.path(), entry.folder()), target.path());
                });
    }

    /** The kind of file an entry is ({@code null} for a folder): a rename keeps it (palettes stay palettes). */
    static LibraryPath.Kind kind(S2C.LibraryListing.Entry entry) {
        return switch (entry.kind()) {
            case FOLDER -> null;
            case SCHEMATIC -> LibraryPath.Kind.SCHEMATIC;
            case PALETTE -> LibraryPath.Kind.PALETTE;
        };
    }

    private void promptMove() {
        S2C.LibraryListing.Entry entry = host.selected().filter(e -> !e.folder()).orElse(null);
        if (entry == null) return;
        prompt(move, "sculptory.library.move.title", "sculptory.library.move.placeholder",
                "sculptory.library.move.hint", "sculptory.library.move.submit", LibraryPaths.parent(entry.path()),
                typed -> moveCheck(LibraryPaths.moveTo(entry.path(), typed)),
                typed -> {
                    LibraryPaths.Target target = LibraryPaths.moveTo(entry.path(), typed);
                    run(session -> session.libraryMove(entry.path(), target.path(), false), null);
                });
    }

    /**
     * Asks the server who may load the selected file and opens the Access dialog with the answer (a refusal is
     * toasted by the session and opens nothing); Save sends the new list as one library write.
     */
    private void openAccess() {
        S2C.LibraryListing.Entry entry = host.selected().filter(e -> !e.folder()).orElse(null);
        Optional<EditorSession> session = host.session();
        if (entry == null || session.isEmpty()) return;
        String path = entry.path();
        session.get().libraryAccess(path).thenAccept(reply -> {
            if (!(reply instanceof Reply.Ok<AssetAccess> ok)) return;
            if (!host.selected().map(S2C.LibraryListing.Entry::path).equals(Optional.of(path))) return;
            accessDialog = LibraryAccessDialog.open(popups.get(), access.bounds(), tr, path, ok.value(),
                    host.onlinePlayers(), chosen -> run(s -> s.setLibraryAccess(path, chosen), path));
        });
    }

    /** Sends a change; on success the window selects {@code select} (or nothing) after listing again. */
    private void run(java.util.function.Function<EditorSession, CompletionStage<Reply<LibraryChange>>> request,
                     String select) {
        host.session().ifPresent(session -> request.apply(session).thenAccept(reply -> {
            if (reply.isOk() && select != null) host.changed(select);
        }));
    }

    private TextPrompt.Check nameCheck(LibraryPaths.Target target) {
        return switch (target.problem()) {
            case NONE -> new TextPrompt.Check(true, tr.translate("sculptory.library.name.target", target.path()));
            case EMPTY -> new TextPrompt.Check(false, tr.translate("sculptory.library.name.empty"));
            case SLASH -> new TextPrompt.Check(false, tr.translate("sculptory.library.name.slash"));
            case SAME -> new TextPrompt.Check(false, tr.translate("sculptory.library.name.same"));
            case RESERVED -> new TextPrompt.Check(false, tr.translate("sculptory.library.name.reserved"));
            case INVALID -> new TextPrompt.Check(false, tr.translate("sculptory.library.name.invalid", target.detail()));
        };
    }

    private TextPrompt.Check moveCheck(LibraryPaths.Target target) {
        return switch (target.problem()) {
            case NONE -> new TextPrompt.Check(true, tr.translate("sculptory.library.move.target", target.path()));
            case SAME -> new TextPrompt.Check(false, tr.translate("sculptory.library.move.same"));
            case EMPTY, SLASH, RESERVED, INVALID ->
                    new TextPrompt.Check(false, tr.translate("sculptory.library.move.invalid", target.detail()));
        };
    }

    private void prompt(Button anchor, String titleKey, String placeholderKey, String hintKey, String submitKey,
                        String initial, java.util.function.Function<String, TextPrompt.Check> check,
                        java.util.function.Consumer<String> onSubmit) {
        TextPrompt.Texts texts = new TextPrompt.Texts(tr.translate(titleKey), tr.translate(placeholderKey),
                tr.translate(hintKey), tr.translate(submitKey), tr.translate("sculptory.dialog.cancel"));
        TextPrompt.open(popups.get(), anchor.bounds(), DIALOG_WIDTH, texts, initial, LibraryPaths.MAX_TYPED, check,
                onSubmit);
    }

    // ---- Building ----

    private void rebuild() {
        shownConfirming = confirming != null;
        root.clear();
        root.add(shownConfirming ? confirmation(confirming) : actions);
    }

    /** "Delete “name”?" with Cancel and Delete, in place of the row. */
    private Node confirmation(S2C.LibraryListing.Entry entry) {
        String name = LibraryPaths.name(entry.path());
        Label question = Label.of(tr.translate(entry.folder() ? "sculptory.library.delete.confirm_folder"
                : "sculptory.library.delete.confirm_file", name));
        question.setWrap(true);
        Button cancel = new Button(tr.translate("sculptory.dialog.cancel"), () -> {
            confirming = null;
            rebuild();
        });
        confirmDelete = new Button(tr.translate("sculptory.library.delete"), () -> {
            confirming = null;
            rebuild();
            run(session -> session.libraryDelete(entry.path(), entry.folder()), null);
        });
        confirmDelete.setStyle(Button.Style.DANGER);
        cancel.setGrow(1);
        confirmDelete.setGrow(1);
        FlowRow buttons = FlowRow.of(cancel, confirmDelete);
        buttons.setGap(3);
        Column column = Column.of(question, buttons);
        column.setGap(3);
        return column;
    }

    private Button button(String textKey, String tooltipKey) {
        Button button = new Button(tr.translate(textKey), null);
        button.setTooltip(tr.translate(tooltipKey));
        button.setStyle(Button.Style.FLAT);
        button.setGrow(1);
        return button;
    }
}
