package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.palette.BlockPalette;
import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.hud.HelpSheet;
import dev.sculptory.fabric.client.editor.hud.ToastStack;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.ListView;
import dev.sculptory.fabric.client.editor.ui.widget.ScrollPane;
import dev.sculptory.fabric.client.editor.ui.widget.TextInput;
import dev.sculptory.fabric.client.editor.ui.window.Corner;
import dev.sculptory.fabric.client.editor.ui.window.LayoutState;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import dev.sculptory.fabric.client.editor.mock.MockEditorSession;
import dev.sculptory.fabric.client.editor.windows.LibraryAccessDialog;
import dev.sculptory.fabric.client.editor.windows.LibraryManageBar;
import dev.sculptory.fabric.client.editor.windows.LibraryWindow;
import dev.sculptory.fabric.client.session.LibraryChange;
import dev.sculptory.protocol.v2.AssetAccess;
import dev.sculptory.protocol.v2.S2C;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/** The Library window's management row (M4), driven like a player would through the editor UI on the mock session. */
class LibraryWindowTest {
    private static final TextMeasure TEXT = new TextMeasure() {
        @Override
        public int width(String text) {
            return text.length() * 6;
        }

        @Override
        public int lineHeight() {
            return 9;
        }

        @Override
        public String trimToWidth(String text, int maxWidth) {
            return text.substring(0, Math.max(0, Math.min(text.length(), maxWidth / 6)));
        }
    };
    private static final String HASH_A = "aa".repeat(32);
    private static final String HASH_B = "bb".repeat(32);

    private final EditorTestRig rig = new EditorTestRig();
    private EditorUi ui;
    private LibraryWindow window;

    @BeforeEach
    void open() {
        rig.session.library().put("trees/oak.schem", HASH_A);
        rig.session.library().put("trees/birch.schem", HASH_B);
        ui = new EditorUi(rig.ctx, rig.controller, rig.platform, rig.actions, TEXT, new ToastStack(() -> 0L),
                new EditorUi.Services(Translator.KEYS, BlockCatalog.EMPTY, icon -> null,
                        () -> new HelpSheet.VanillaKeys("W A S D", "Space", "Shift", "B", "T", "/", "E"),
                        "config/sculptory/editor-keys.json", keymap -> true, new UiScale()));
        rig.controller.setUi(ui);
        ui.setEditing(true);
        rig.mode.enter();
        window = ui.libraryWindow().orElseThrow();
        frame();
        window.list("trees");
        frame();
    }

    /** What a frame does for the window: lay out, then follow the state. */
    private void frame() {
        ui.layout(640, 360);
        window.refresh();
        ui.layout(640, 360);
    }

    private LibraryManageBar bar() {
        return window.manageBar();
    }

    private List<String> shown() {
        return window.shown().stream().map(S2C.LibraryListing.Entry::path).toList();
    }

    /** Types {@code text} into the open prompt and presses Enter; returns whether the prompt closed. */
    private boolean typeAndSubmit(String text) {
        assertTrue(ui.hasPopup(), "the prompt is open");
        TextInput input = assertInstanceOf(TextInput.class, ui.windows().context().focused(), "the field has focus");
        input.setText(text);
        assertTrue(ui.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0));
        frame();
        return !ui.hasPopup();
    }

    /** Puts the Library window at {@code width} × {@code height}, open. */
    private void resize(int width, int height) {
        ui.windows().restore(new LayoutState(List.of(new LayoutState.WindowState(EditorWindows.LIBRARY, true, false,
                Corner.TOP_LEFT, 10, 20, width, height))));
        frame();
    }

    @Test
    void aShortLibraryWindowScrollsToItsLastLineAndATallOneGivesTheListTheRoom() {
        resize(220, 130);
        ScrollPane pane = assertInstanceOf(ScrollPane.class, window.node());
        assertTrue(pane.scroll().isScrollable(), "the bottom is reached by scrolling, not cut off");
        pane.scroll().setOffset(pane.scroll().maxOffset());
        frame();
        Node last = pane.content().children().get(pane.content().children().size() - 1);
        assertTrue(last.bounds().bottom() <= pane.bounds().bottom(), "the last line in view: " + last.bounds());

        int list = listHeight(pane);
        // Taller than this test's screen allows a window: lay the content out directly.
        pane.layout(ui.windows().context(), new Rect(0, 0, 220, 2000));
        assertFalse(pane.scroll().isScrollable());
        assertTrue(listHeight(pane) >= list + 1000, "the list grows with the window");
    }

    private static int listHeight(ScrollPane pane) {
        for (Node child : pane.content().children()) {
            if (child instanceof ListView<?> list) {
                return list.bounds().height();
            }
        }
        throw new AssertionError("no list");
    }

    @Test
    void theManagementButtonsWrapInsideANarrowWindow() {
        resize(160, 400);
        List<Button> buttons = List.of(bar().newFolderButton(), bar().renameButton(), bar().moveButton(),
                bar().accessButton(), bar().deleteButton());
        Rect content = window.node().bounds();
        for (Button button : buttons) {
            assertTrue(button.bounds().x() >= content.x() && button.bounds().right() <= content.right(),
                    button.text() + " inside the window: " + button.bounds());
        }
        assertTrue(bar().deleteButton().bounds().y() > bar().newFolderButton().bounds().y(), "on more than one line");
    }

    @Test
    void theRowShowsOnlyWhereTheServerSaysTheFolderMayBeChanged() {
        assertTrue(window.writable());
        assertTrue(bar().visible());
        assertTrue(bar().newFolderButton().isEnabled());
        assertFalse(bar().renameButton().isEnabled(), "nothing selected");
        assertTrue(window.select("trees/oak.schem"));
        frame();
        assertTrue(bar().renameButton().isEnabled() && bar().moveButton().isEnabled() && bar().deleteButton().isEnabled());

        rig.session.setLibraryWritable(false);
        window.list("trees");
        frame();
        assertFalse(window.writable());
        assertFalse(bar().visible(), "a read-only folder shows no management at all");
        assertFalse(bar().renameButton().isEnabled());
    }

    @Test
    void renameKeepsTheAssetInItsFolderAndSelectsItsNewName() {
        window.select("trees/oak.schem");
        frame();
        bar().renameButton().click();
        assertFalse(typeAndSubmit("big oak"), "a space is refused as it is typed: the prompt stays open");
        assertTrue(rig.session.library().containsKey("trees/oak.schem"));
        assertFalse(typeAndSubmit("rocks/oak"), "a folder in the name is refused (that is Move)");
        assertTrue(typeAndSubmit("birch"), "a valid name is sent");
        frame();
        assertTrue(rig.session.library().containsKey("trees/oak.schem") && rig.session.library().containsKey("trees/birch.schem"),
                "an existing name is refused by the server, never replaced");

        window.select("trees/oak.schem");
        frame();
        bar().renameButton().click();
        assertTrue(typeAndSubmit("old_oak"));
        assertEquals(HASH_A, rig.session.library().get("trees/old_oak.schem"), "same content (same hash)");
        assertFalse(rig.session.library().containsKey("trees/oak.schem"));
        frame();
        assertEquals(List.of("trees/birch.schem", "trees/old_oak.schem"), shown(), "listed again after the change");
        assertEquals(java.util.Optional.of("trees/old_oak.schem"), window.selected().map(S2C.LibraryListing.Entry::path),
                "the renamed entry is selected");
    }

    @Test
    void deleteAsksInPlaceAndOnlyAnEmptyFolderGoes() {
        window.select("trees/oak.schem");
        frame();
        bar().deleteButton().click();
        frame();
        assertTrue(bar().confirmDeleteButton().isPresent(), "a confirmation in place of the row, not a modal");
        assertFalse(ui.hasPopup());
        assertTrue(rig.session.library().containsKey("trees/oak.schem"), "nothing deleted before confirming");
        window.select("trees/birch.schem");
        frame();
        assertTrue(bar().confirmDeleteButton().isEmpty(), "another selection drops the confirmation");

        window.select("trees/oak.schem");
        frame();
        bar().deleteButton().click();
        frame();
        bar().confirmDeleteButton().orElseThrow().click();
        frame();
        assertFalse(rig.session.library().containsKey("trees/oak.schem"));
        assertEquals(List.of("trees/birch.schem"), shown());

        // A folder with something in it is refused and stays.
        window.list("");
        frame();
        window.select("trees");
        frame();
        bar().deleteButton().click();
        frame();
        bar().confirmDeleteButton().orElseThrow().click();
        frame();
        assertEquals(List.of("trees", "_shared"), shown());
    }

    @Test
    void newFoldersAreMadeHereAndAssetsMoveIntoThem() {
        bar().newFolderButton().click();
        assertFalse(typeAndSubmit("_bad"), "names start with a letter or digit");
        assertTrue(typeAndSubmit("big"));
        assertTrue(rig.session.libraryFolders().contains("trees/big"));
        assertEquals(List.of("trees/big", "trees/birch.schem", "trees/oak.schem"), shown());

        window.select("trees/oak.schem");
        frame();
        bar().moveButton().click();
        assertFalse(typeAndSubmit("trees"), "the folder it is already in");
        assertFalse(typeAndSubmit("../x"), "not a library folder");
        assertTrue(typeAndSubmit("trees/big"));
        assertEquals(HASH_A, rig.session.library().get("trees/big/oak.schem"));
        assertEquals(List.of("trees/big", "trees/birch.schem"), shown(), "it left the folder shown");

        window.select("trees/big");
        frame();
        assertFalse(bar().moveButton().isEnabled(), "folders are renamed, not moved");
        bar().renameButton().click();
        assertTrue(typeAndSubmit("large"));
        assertEquals(HASH_A, rig.session.library().get("trees/large/oak.schem"));
    }

    @Test
    void changesPushedFromOtherPlayersListTheFolderAgainAndFollowRenames() {
        rig.session.library().put("trees/elm.schem", "cc".repeat(32));
        rig.session.pushLibraryChange(new LibraryChange(false, "", "trees/elm.schem"));
        frame();
        assertEquals(List.of("trees/birch.schem", "trees/elm.schem", "trees/oak.schem"), shown());

        // A change elsewhere does not list again.
        rig.session.library().put("rocks/stone.schem", "dd".repeat(32));
        int lists = listCalls();
        rig.session.pushLibraryChange(new LibraryChange(false, "", "rocks/stone.schem"));
        frame();
        assertEquals(lists, listCalls());

        // The folder shown is renamed: the window follows it.
        for (String name : List.of("birch", "elm", "oak")) {
            rig.session.library().put("forest/" + name + ".schem", rig.session.library().remove("trees/" + name + ".schem"));
        }
        rig.session.pushLibraryChange(new LibraryChange(true, "trees", "forest"));
        frame();
        assertEquals("forest", window.folder());
        assertEquals(List.of("forest/birch.schem", "forest/elm.schem", "forest/oak.schem"), shown());
    }

    @Test
    void thePlayerFoldersAreNeverOfferedForChange() {
        UUID me = new UUID(1, 2);
        rig.session.library().put("_players/" + me + "/mine.schem", HASH_A);
        window.list("");
        frame();
        assertTrue(window.select("_players"));
        frame();
        assertFalse(bar().renameButton().isEnabled());
        assertFalse(bar().deleteButton().isEnabled());
        window.list("_players/" + me);
        frame();
        assertTrue(window.select("_players/" + me + "/mine.schem"));
        frame();
        assertTrue(bar().renameButton().isEnabled(), "what is inside a player folder is theirs to change");
    }

    // ---------------------------------------------------------------- palettes

    @Test
    void palettesAreListedAsPalettesAndManagedLikeAssets() {
        rig.session.palettes().put("trees/moss.palette.json", BlockPalette.of("minecraft:stone", 1));
        window.list("trees");
        frame();
        assertEquals(List.of("trees/birch.schem", "trees/moss.palette.json", "trees/oak.schem"), shown());
        S2C.LibraryListing.Entry moss = window.shown().get(1);
        assertEquals(S2C.LibraryListing.Entry.Kind.PALETTE, moss.kind());
        assertEquals("moss.palette.json  ·  sculptory.library.palette  ·  256 B", window.describe(moss),
                "a palette is labelled as one");
        assertTrue(window.select("trees/moss.palette.json"));
        frame();
        assertFalse(window.loadButton().isEnabled(), "a palette is not loaded into the clipboard");
        assertTrue(bar().renameButton().isEnabled() && bar().moveButton().isEnabled()
                && bar().deleteButton().isEnabled());

        // Rename keeps the kind: the extension is added, and a schematic name is not taken for it.
        bar().renameButton().click();
        assertTrue(typeAndSubmit("mossy"));
        assertTrue(rig.session.palettes().containsKey("trees/mossy.palette.json"));
        frame();
        assertEquals(java.util.Optional.of("trees/mossy.palette.json"),
                window.selected().map(S2C.LibraryListing.Entry::path));

        // Move and delete, as for assets.
        bar().newFolderButton().click();
        assertTrue(typeAndSubmit("palettes"));
        frame();
        window.select("trees/mossy.palette.json");
        frame();
        bar().moveButton().click();
        assertTrue(typeAndSubmit("trees/palettes"));
        assertTrue(rig.session.palettes().containsKey("trees/palettes/mossy.palette.json"));
        window.list("trees/palettes");
        frame();
        assertTrue(window.select("trees/palettes/mossy.palette.json"));
        frame();
        bar().deleteButton().click();
        frame();
        bar().confirmDeleteButton().orElseThrow().click();
        frame();
        assertTrue(rig.session.palettes().isEmpty());
    }

    @Test
    void aChangeThatMakesAFolderBelowTheShownOneListsItAgain() {
        window.list("");
        frame();
        assertEquals(List.of("trees", "_shared"), shown());
        rig.session.savePalette("palettes/moss.palette.json", BlockPalette.of("minecraft:stone", 1));
        frame();
        assertEquals(List.of("palettes", "trees", "_shared"), shown(), "the folder the save made shows without Refresh");

        window.list("trees");
        frame();
        rig.session.library().put("trees/deep/new/oak2.schem", HASH_A);
        rig.session.pushLibraryChange(new LibraryChange(false, "", "trees/deep/new/oak2.schem"));
        frame();
        assertTrue(shown().contains("trees/deep"), "someone else's change deep below: " + shown());
        int calls = listCalls();
        rig.session.pushLibraryChange(new LibraryChange(false, "", "rocks/big.schem"));
        frame();
        assertEquals(calls, listCalls(), "a change elsewhere lists nothing again");
    }

    // ---------------------------------------------------------------- per-asset access

    private static final UUID OTHER = new UUID(7, 8);
    private static final String SHARED_HUT = "_players/" + OTHER + "/huts/hut.schem";

    private LibraryAccessDialog dialog() {
        return bar().accessDialog().orElseThrow(() -> new AssertionError("the Access dialog is open"));
    }

    @Test
    void accessIsOfferedForFilesWhereTheFolderMayBeChanged() {
        assertFalse(bar().accessButton().isEnabled(), "nothing selected");
        assertTrue(window.select("trees/oak.schem"));
        frame();
        assertTrue(bar().accessButton().isEnabled());
        window.list("");
        frame();
        assertTrue(window.select("trees"));
        frame();
        assertFalse(bar().accessButton().isEnabled(), "folders have no access of their own");

        rig.session.setLibraryWritable(false);
        window.list("trees");
        frame();
        window.select("trees/oak.schem");
        frame();
        assertFalse(bar().visible(), "who may not change the folder may not change its entries' access either");
        assertFalse(bar().accessButton().isEnabled());
    }

    @Test
    void theAccessDialogGrantsAndRevokesPlayersAndTheListingShowsTheLock() {
        rig.platform.onlinePlayers.addAll(List.of("Bob", "Carol"));
        window.select("trees/oak.schem");
        frame();
        bar().accessButton().click();
        frame();
        LibraryAccessDialog dialog = dialog();
        assertEquals(AssetAccess.Mode.EVERYONE, dialog.modeDropdown().selected(), "the default");
        assertEquals(List.of(), dialog.grantees());
        assertTrue(dialog.saveButton().isEnabled());

        dialog.modeDropdown().pick(AssetAccess.Mode.LISTED);
        frame();
        assertFalse(dialog.saveButton().isEnabled(), "nobody listed yet");
        assertFalse(dialog.addButton().isEnabled());
        dialog.type("not a name!");
        assertFalse(dialog.addButton().isEnabled());
        dialog.type("Bob");
        assertTrue(dialog.addButton().isEnabled());
        dialog.addButton().click();
        assertEquals(List.of("Bob"), dialog.grantees().stream().map(AssetAccess.Grantee::name).toList());
        assertEquals("", dialog.nameInput().text());
        dialog.type("bob");
        assertFalse(dialog.addButton().isEnabled(), "already listed, whatever the case");
        dialog.onlinePicker().pick("Carol");
        assertEquals(List.of("Bob", "Carol"), dialog.grantees().stream().map(AssetAccess.Grantee::name).toList());
        dialog.remove(0);
        assertEquals(List.of("Carol"), dialog.grantees().stream().map(AssetAccess.Grantee::name).toList());
        assertTrue(dialog.saveButton().isEnabled());
        dialog.saveButton().click();
        frame();
        assertFalse(dialog.isOpen());
        AssetAccess saved = rig.session.access().get("trees/oak.schem");
        assertEquals(AssetAccess.Mode.LISTED, saved.mode());
        assertEquals(List.of("Carol"), saved.players().stream().map(AssetAccess.Grantee::name).toList());
        assertEquals(MockEditorSession.playerId("Carol"), saved.players().get(0).uuid(), "the server resolved the name");
        assertEquals(java.util.Optional.of("trees/oak.schem"), window.selected().map(S2C.LibraryListing.Entry::path),
                "listed again and still selected");
        S2C.LibraryListing.Entry oak = window.selected().orElseThrow();
        assertTrue(oak.restricted());
        assertTrue(window.describe(oak).startsWith("oak.schem sculptory.library.restricted  ·  "), window.describe(oak));
        assertFalse(window.shown().get(0).restricted(), "birch is open");

        // Opening it again shows what the server holds; back to everyone.
        bar().accessButton().click();
        frame();
        LibraryAccessDialog again = dialog();
        assertEquals(AssetAccess.Mode.LISTED, again.modeDropdown().selected());
        assertEquals(saved.players(), again.grantees());
        again.modeDropdown().pick(AssetAccess.Mode.EVERYONE);
        again.saveButton().click();
        frame();
        assertFalse(rig.session.access().containsKey("trees/oak.schem"));
        assertFalse(window.selected().orElseThrow().restricted());
    }

    @Test
    void anUnknownNameIsRefusedByTheServerAndNothingChanges() {
        window.select("trees/oak.schem");
        frame();
        bar().accessButton().click();
        frame();
        LibraryAccessDialog dialog = dialog();
        dialog.modeDropdown().pick(AssetAccess.Mode.LISTED);
        dialog.type("unknown_zed");
        dialog.addButton().click();
        dialog.saveButton().click();
        frame();
        assertFalse(rig.session.access().containsKey("trees/oak.schem"));
        assertFalse(window.selected().orElseThrow().restricted());
    }

    @Test
    void sharedWithMeListsWhatOthersGrantedReadOnlyWithRealPaths() {
        rig.session.library().put(SHARED_HUT, HASH_B);
        rig.session.library().put("_players/" + OTHER + "/secret.schem", HASH_A);
        rig.session.access().put(SHARED_HUT, AssetAccess.listed(List.of(new AssetAccess.Grantee(MockEditorSession.PLAYER, "Me"))));
        rig.session.access().put("_players/" + OTHER + "/secret.schem",
                AssetAccess.listed(List.of(new AssetAccess.Grantee(new UUID(9, 9), "Someone"))));
        window.list("");
        frame();
        assertEquals(List.of("_players", "trees", "_shared"), shown(), "Shared with me is the last folder");
        S2C.LibraryListing.Entry shared = window.shown().get(2);
        assertEquals("sculptory.library.shared/", window.describe(shared));

        window.list("_shared");
        frame();
        assertEquals("_shared", window.folder());
        assertEquals(List.of(SHARED_HUT), shown(), "granted entries only, by real path");
        assertFalse(window.writable());
        assertFalse(bar().visible(), "read-only: no rename, move, delete or access");
        assertTrue(window.select(SHARED_HUT));
        frame();
        assertTrue(window.loadButton().isEnabled(), "but it can be loaded");
        assertTrue(window.describe(window.selected().orElseThrow()).contains("sculptory.library.restricted"));

        // Revoked: the push (the entry vanished for this player) lists the folder again, and it is gone.
        rig.session.access().remove(SHARED_HUT);
        rig.session.pushLibraryChange(new LibraryChange(false, SHARED_HUT, ""));
        frame();
        assertEquals(List.of(), shown());
        assertEquals("_shared", window.folder(), "the folder stays");
    }

    private int listCalls() {
        return (int) rig.session.calls().stream().filter(call -> call.kind().equals("list")).count();
    }
}
