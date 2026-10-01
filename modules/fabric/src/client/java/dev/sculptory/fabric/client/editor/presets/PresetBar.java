package dev.sculptory.fabric.client.editor.presets;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.settings.Validation;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.FlowRow;
import dev.sculptory.fabric.client.editor.ui.layout.Row;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Dropdown;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.TextPrompt;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The preset row at the top of the Tool Settings window. First line: a dropdown with "Default" (the tool's built-in
 * defaults) and the tool's saved presets, with "(modified)" after the name while the settings differ from it, and Save
 * (overwrite the selected preset; not for Default). Second line: Save as…, Rename and Delete, wrapping onto another
 * line when the window is narrow ({@link FlowRow}). Save as and Rename ask for the name in a {@link TextPrompt};
 * Delete asks for confirmation in place of the second line. When presets are
 * read-only a notice takes that place. A preset is chosen from the list only (the arrow keys open it, so a key press
 * never replaces the settings); picking the selected preset again reloads it. The nodes are rebuilt only when the list
 * itself changes, so a new selection keeps focus.
 */
public final class PresetBar {
    public static final int DIALOG_WIDTH = 200;
    private static final int WARN_COLOR = 0xFFE0B040;

    private final Presets presets;
    private final ToolId tool;
    private final Translator tr;
    private final Supplier<UiContext> popups;
    private final Column root = new Column();
    private Dropdown<String> dropdown;
    private Button save;
    private Button saveAs;
    private Button rename;
    private Button delete;
    private Button confirmDelete;
    private Label notice;
    /** The preset waiting for Delete to be confirmed, or null. */
    private String confirming;
    private int shownVersion = -1;
    /** What the nodes were built for: the names, the access and the preset being confirmed. */
    private List<Object> shownShape = List.of();

    /** @param popups where the name prompts open (the window manager's context) */
    public PresetBar(Presets presets, ToolId tool, Translator tr, Supplier<UiContext> popups) {
        this.presets = Objects.requireNonNull(presets);
        this.tool = Objects.requireNonNull(tool);
        this.tr = Objects.requireNonNull(tr);
        this.popups = Objects.requireNonNull(popups);
        root.setGap(3);
        update();
    }

    public Node node() {
        return root;
    }

    /** Follows the presets and the tool's settings; call every frame while the bar is shown. */
    public void refresh() {
        if (presets.version() != shownVersion) {
            update();
        }
        boolean modified = presets.isModified(tool);
        dropdown.setSuffix(modified ? tr.translate("sculptory.preset.modified") : "");
        save.setStyle(modified && save.isEnabled() ? Button.Style.PRIMARY : Button.Style.DEFAULT);
    }

    // ---- For tests ----

    public Dropdown<String> dropdown() {
        return dropdown;
    }

    public Button saveButton() {
        return save;
    }

    public Button saveAsButton() {
        return saveAs;
    }

    public Button renameButton() {
        return rename;
    }

    public Button deleteButton() {
        return delete;
    }

    /** The confirming Delete button while a delete waits for it. */
    public Optional<Button> confirmDeleteButton() {
        return Optional.ofNullable(confirming == null ? null : confirmDelete);
    }

    /** The read-only notice, or "" when presets can be saved. */
    public String notice() {
        return notice == null ? "" : notice.text();
    }

    // ---- Building ----

    /** Rebuilds the nodes if the list changed, then shows the selection and what can be done with it. */
    private void update() {
        shownVersion = presets.version();
        String selected = presets.selected(tool);
        if (confirming != null && (!confirming.equals(selected) || presets.readOnly())) {
            confirming = null;
        }
        List<Object> shape = List.of(presets.names(tool), presets.access(),
                confirming == null ? List.of() : List.of(confirming));
        if (!shape.equals(shownShape)) {
            shownShape = shape;
            build();
        }
        boolean writable = !presets.readOnly();
        boolean named = !selected.isEmpty();
        boolean full = presets.isFull(tool);
        dropdown.setSelected(selected);
        save.setEnabled(writable && named);
        save.setTooltip(named ? tr.translate("sculptory.preset.save.tooltip", selected)
                : tr.translate("sculptory.preset.save.default"));
        saveAs.setEnabled(writable && !full);
        saveAs.setTooltip(full ? tr.translate("sculptory.preset.full", Integer.toString(PresetNames.MAX_PER_TOOL))
                : tr.translate("sculptory.preset.save_as.tooltip"));
        rename.setEnabled(writable && named);
        delete.setEnabled(writable && named);
    }

    private void build() {
        root.clear();
        notice = null;
        List<String> options = new ArrayList<>();
        options.add("");
        options.addAll(presets.names(tool));
        dropdown = new Dropdown<>(options, presets.selected(tool), this::label, name -> presets.select(tool, name));
        dropdown.setReselectNotifies(true);
        dropdown.setListOnly(true);
        dropdown.setGrow(1);
        dropdown.setMinSize(40, 0);
        dropdown.setTooltip(tr.translate("sculptory.preset.tooltip"));
        save = new Button(tr.translate("sculptory.preset.save"), () -> presets.save(tool));
        Row top = Row.of(dropdown, save);
        top.setGap(3);
        root.add(top);

        saveAs = new Button(tr.translate("sculptory.preset.save_as"), null);
        saveAs.setOnClick(() -> prompt(saveAs, "sculptory.preset.save_as.title", "sculptory.preset.save", "",
                null, typed -> presets.saveAs(tool, typed)));
        rename = new Button(tr.translate("sculptory.preset.rename"), null);
        rename.setOnClick(() -> {
            String current = presets.selected(tool);
            prompt(rename, "sculptory.preset.rename.title", "sculptory.preset.rename.submit", current, current,
                    typed -> presets.rename(tool, current, typed));
        });
        rename.setTooltip(tr.translate("sculptory.preset.rename.tooltip"));
        delete = new Button(tr.translate("sculptory.preset.delete"), () -> {
            confirming = presets.selected(tool);
            update();
        });
        delete.setTooltip(tr.translate("sculptory.preset.delete.tooltip"));
        for (Button button : List.of(saveAs, rename, delete)) {
            button.setStyle(Button.Style.FLAT);
            button.setGrow(1);
        }

        if (presets.readOnly()) {
            String key = presets.access() == Presets.Access.FILE_UNUSABLE ? "sculptory.preset.read_only"
                    : "sculptory.preset.saving_stopped";
            notice = Label.dim(tr.translate(key, PresetStore.FILE_NAME));
            notice.setWrap(true);
            notice.setColor(WARN_COLOR);
            root.add(notice);
        } else if (confirming != null) {
            root.add(confirmation(confirming));
        } else {
            FlowRow actions = FlowRow.of(saveAs, rename, delete);
            actions.setGap(0);
            root.add(actions);
        }
    }

    /** "Delete “name”?" with Cancel and Delete, in place of the second line. */
    private Node confirmation(String name) {
        Label question = Label.of(tr.translate("sculptory.preset.delete.confirm", name));
        question.setWrap(true);
        Button cancel = new Button(tr.translate("sculptory.dialog.cancel"), () -> {
            confirming = null;
            update();
        });
        confirmDelete = new Button(tr.translate("sculptory.preset.delete"), () -> {
            confirming = null;
            presets.delete(tool, name);
            update();
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

    /** Opens a name prompt below {@code anchor}; {@code except} is the preset being renamed, or null. */
    private void prompt(Button anchor, String titleKey, String submitKey, String initial, String except,
                        Consumer<String> onSubmit) {
        TextPrompt.Texts texts = new TextPrompt.Texts(tr.translate(titleKey),
                tr.translate("sculptory.preset.name.placeholder"), "", tr.translate(submitKey),
                tr.translate("sculptory.dialog.cancel"));
        TextPrompt.open(popups.get(), anchor.bounds(), DIALOG_WIDTH, texts, initial, PresetNames.MAX_LENGTH,
                typed -> check(typed, except), onSubmit);
    }

    private TextPrompt.Check check(String typed, String except) {
        Validation problem = presets.nameProblem(tool, typed, except);
        return problem.isValid()
                ? new TextPrompt.Check(true, tr.translate("sculptory.preset.name.hint"))
                : new TextPrompt.Check(false, tr.translate(problem.messageKey(), problem.args()));
    }

    private String label(String name) {
        return name.isEmpty() ? tr.translate("sculptory.preset.default") : name;
    }
}
