package dev.sculptory.fabric.client.editor.windows;

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
import dev.sculptory.fabric.client.editor.ui.layout.Spacer;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Dropdown;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.ListView;
import dev.sculptory.fabric.client.editor.ui.widget.TextInput;
import dev.sculptory.protocol.v2.AssetAccess;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The Library window's "Access…" popup (per-asset access): "Everyone who can use the library" or "Only these
 * players" (a dropdown); for the latter the granted players with a remove button each, a field to add a player by
 * name (Enter or Add; the server resolves the name), and a picker of the players online now. Cancel closes it; Save
 * sends the whole list at once and closes it. The players a name field adds have no UUID yet: the server resolves
 * them, and refuses the change when it cannot.
 */
public final class LibraryAccessDialog {
    public static final int WIDTH = 260;
    /** A Minecraft player name, as the server looks one up. */
    static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final String ONLINE_PLACEHOLDER = "";

    private final UiContext ctx;
    private final Translator tr;
    private final List<String> online;
    private final Consumer<AssetAccess> onSave;
    private final List<AssetAccess.Grantee> grantees = new ArrayList<>();
    private final Dropdown<AssetAccess.Mode> mode;
    private final Column body = new Column();
    private final ListView<AssetAccess.Grantee> list;
    private final TextInput name;
    private final Button add;
    private final Dropdown<String> picker;
    private final Label status = Label.dim("");
    private final Button save;
    private PopupLayer.Popup popup;
    private AssetAccess.Mode shownMode;

    private LibraryAccessDialog(UiContext ctx, Rect anchor, Translator tr, String path, AssetAccess initial,
                                List<String> online, Consumer<AssetAccess> onSave) {
        this.ctx = Objects.requireNonNull(ctx);
        this.tr = Objects.requireNonNull(tr);
        this.online = List.copyOf(online);
        this.onSave = Objects.requireNonNull(onSave);
        grantees.addAll(initial.players());
        mode = new Dropdown<>(List.of(AssetAccess.Mode.EVERYONE, AssetAccess.Mode.LISTED), initial.mode(),
                m -> tr.translate(m == AssetAccess.Mode.EVERYONE ? "sculptory.library.access.everyone"
                        : "sculptory.library.access.listed"),
                m -> rebuild());
        list = new ListView<>(List.of(), (grantee, index) -> row(grantee));
        list.setPreferredRows(4);
        list.setEmptyText(tr.translate("sculptory.library.access.nobody"));
        name = new TextInput("", typed -> validate());
        name.setPlaceholder(tr.translate("sculptory.library.access.name.placeholder"));
        name.setMaxLength(16);
        name.setOnSubmit(typed -> addTyped());
        add = new Button(tr.translate("sculptory.library.access.add"), this::addTyped);
        picker = new Dropdown<>(pickable(), ONLINE_PLACEHOLDER, this::pickerLabel, this::pickOnline);
        picker.setReselectNotifies(true);
        status.setWrap(true);
        save = new Button(tr.translate("sculptory.library.access.save"), this::submit);
        save.setStyle(Button.Style.PRIMARY);
        Button cancel = new Button(tr.translate("sculptory.dialog.cancel"), this::close);
        body.setGap(4);
        Column content = Column.of(
                Label.heading(tr.translate("sculptory.library.access.title", LibraryPaths.name(path))),
                mode,
                body,
                status,
                Row.of(Spacer.flexible(), cancel, save));
        content.setGap(5);
        content.setFixedWidth(WIDTH - 12);
        rebuild();
        popup = ctx.popups().open(null, new Padding(Insets.all(6), content), anchor, WIDTH, null);
    }

    /** Opens the dialog below {@code anchor} for the entry at {@code path}, showing {@code initial}. */
    public static LibraryAccessDialog open(UiContext ctx, Rect anchor, Translator tr, String path, AssetAccess initial,
                                           List<String> online, Consumer<AssetAccess> onSave) {
        return new LibraryAccessDialog(ctx, anchor, tr, path, initial, online, onSave);
    }

    // ---- For tests ----

    public Dropdown<AssetAccess.Mode> modeDropdown() {
        return mode;
    }

    public TextInput nameInput() {
        return name;
    }

    public Button addButton() {
        return add;
    }

    public Button saveButton() {
        return save;
    }

    public Dropdown<String> onlinePicker() {
        return picker;
    }

    /** The players listed now, in order. */
    public List<AssetAccess.Grantee> grantees() {
        return List.copyOf(grantees);
    }

    public String statusText() {
        return status.text();
    }

    /** Removes the listed player at {@code index}, as its remove button does. */
    public void remove(int index) {
        grantees.remove(index);
        refreshList();
    }

    /** Puts {@code text} in the name field as typing would (the field's check runs). */
    public void type(String text) {
        name.setText(text);
        validate();
    }

    public boolean isOpen() {
        return ctx.popups().popups().contains(popup);
    }

    // ---- Behaviour ----

    /** What the dialog would send now. */
    AssetAccess current() {
        return mode.selected() == AssetAccess.Mode.EVERYONE ? AssetAccess.EVERYONE : AssetAccess.listed(grantees);
    }

    private Node row(AssetAccess.Grantee grantee) {
        Button remove = new Button(tr.translate("sculptory.library.access.remove"), () -> {
            int index = grantees.indexOf(grantee);
            if (index >= 0) remove(index);
        });
        remove.setStyle(Button.Style.FLAT);
        remove.setTooltip(tr.translate("sculptory.library.access.remove.tooltip"));
        Row row = Row.of(Label.of(grantee.name()), Spacer.flexible(), remove);
        row.setGap(3);
        return row;
    }

    /** Shows the list, field and picker for "Only these players", nothing more for "Everyone". */
    private void rebuild() {
        AssetAccess.Mode selected = mode.selected();
        if (selected == shownMode) {
            validate();
            return;
        }
        shownMode = selected;
        body.clear();
        if (selected == AssetAccess.Mode.LISTED) {
            Row adding = Row.of(name, add);
            adding.setGap(3);
            name.setGrow(1);
            body.add(list);
            body.add(adding);
            body.add(picker);
            refreshList();
        }
        validate();
    }

    private void refreshList() {
        list.setItems(List.copyOf(grantees));
        picker.setSelected(ONLINE_PLACEHOLDER);
        validate();
    }

    private List<String> pickable() {
        List<String> options = new ArrayList<>();
        options.add(ONLINE_PLACEHOLDER);
        options.addAll(online);
        return options;
    }

    private String pickerLabel(String option) {
        return option.equals(ONLINE_PLACEHOLDER) ? tr.translate("sculptory.library.access.online") : option;
    }

    private void pickOnline(String option) {
        if (option.equals(ONLINE_PLACEHOLDER)) return;
        addName(option);
        picker.setSelected(ONLINE_PLACEHOLDER);
    }

    private void addTyped() {
        validate();
        if (!add.isEnabled()) return;
        addName(name.text().strip());
        name.setText("");
        validate();
    }

    /** Adds a player by name (without a UUID: the server resolves it) unless already listed. */
    private void addName(String typed) {
        if (!PLAYER_NAME.matcher(typed).matches() || listed(typed) || grantees.size() >= AssetAccess.MAX_PLAYERS) {
            validate();
            return;
        }
        grantees.add(new AssetAccess.Grantee(null, typed));
        refreshList();
    }

    private boolean listed(String playerName) {
        for (AssetAccess.Grantee grantee : grantees) {
            if (grantee.name().equalsIgnoreCase(playerName)) return true;
        }
        return false;
    }

    /** The message under the controls, and whether Add and Save can go. */
    private void validate() {
        boolean listedMode = mode.selected() == AssetAccess.Mode.LISTED;
        String typed = name.text().strip();
        String message;
        boolean canAdd = false;
        boolean canSave = true;
        if (!listedMode) {
            message = tr.translate("sculptory.library.access.everyone.hint");
        } else if (grantees.size() >= AssetAccess.MAX_PLAYERS) {
            message = tr.translate("sculptory.library.access.full", Integer.toString(AssetAccess.MAX_PLAYERS));
        } else if (typed.isEmpty()) {
            canSave = !grantees.isEmpty();
            message = grantees.isEmpty() ? tr.translate("sculptory.library.access.empty")
                    : tr.translate("sculptory.library.access.count", Integer.toString(grantees.size()));
        } else if (!PLAYER_NAME.matcher(typed).matches()) {
            canSave = !grantees.isEmpty();
            message = tr.translate("sculptory.library.access.name.invalid");
        } else if (listed(typed)) {
            canSave = !grantees.isEmpty();
            message = tr.translate("sculptory.library.access.name.listed", typed);
        } else {
            canAdd = true;
            canSave = !grantees.isEmpty();
            message = tr.translate("sculptory.library.access.name.add", typed);
        }
        status.setText(message);
        status.setColor(canSave ? ctx.theme().textDim : ctx.theme().danger);
        add.setEnabled(listedMode && canAdd);
        save.setEnabled(canSave);
    }

    private void submit() {
        if (!save.isEnabled()) return;
        AssetAccess access = current();
        close();
        onSave.accept(access);
    }

    private void close() {
        ctx.popups().close(popup);
    }
}
