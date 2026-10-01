package dev.sculptory.fabric.client.editor.windows;

import dev.sculptory.fabric.client.editor.EditorContext;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.presets.PresetBar;
import dev.sculptory.fabric.client.editor.presets.Presets;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.form.SettingsForm;
import dev.sculptory.fabric.client.editor.tool.KeyHint;
import dev.sculptory.fabric.client.editor.tool.Tool;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.ui.Align;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.Row;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.ScrollPane;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The docked Tool Settings window, generated from the active tool's schema by {@link SettingsForm}.
 * Settings changed elsewhere (Ctrl+Scroll, a preset) are pushed into the open form. Once {@link #setPresets} is
 * called, a tool with settings gets the {@link PresetBar} under its name. A tool may add a hand-built {@link Panel}
 * above its form (the Scatter tool's variant list). Which sections are open is remembered per tool
 * ({@link SectionStateStore}, across restarts), and the scroll position per tool for the session.
 * {@link #revealSetting} brings one setting of the active tool into view with the keyboard on it. Once
 * {@link #setHelp} is called, a <b>?</b> at the right of the tool's name opens its wiki page.
 */
public final class ToolSettingsWindow {
    /** Extra content for one tool, shown above its generated form. */
    public interface Panel {
        /** A fresh node tree (the window rebuilds its content when the tool changes). */
        Node build();

        /** Called every frame while the window is open and the tool active, to follow its state. */
        default void refresh() {}
    }

    private final EditorContext ctx;
    private final SettingsForm.Services services;
    private final Translator tr;
    private final Supplier<UiContext> popups;
    private final Map<ToolId, Panel> panels = new HashMap<>();
    private Presets presets;
    private SettingsForm form;
    private ToolId formTool;
    private Panel shownPanel;
    private PresetBar presetBar;
    private boolean applying;
    private SectionStateStore sectionStates = SectionStateStore.inMemory();
    /** Scroll positions of tools shown before, for this session. */
    private final Map<ToolId, Integer> scrollOffsets = new HashMap<>();
    private ScrollPane pane;
    private ToolId paneTool;
    /** A setting to reveal once the active tool's form is built, or null. */
    private String pendingReveal;
    /** What the ? beside the tool's name does (opens its wiki page), or null for no ?. */
    private Consumer<ToolId> help;

    /** @param popups where dialogs open (the window manager's context) */
    public ToolSettingsWindow(EditorContext ctx, SettingsForm.Services services, Supplier<UiContext> popups) {
        this.ctx = Objects.requireNonNull(ctx);
        this.services = Objects.requireNonNull(services);
        this.tr = services.translator();
        this.popups = Objects.requireNonNull(popups);
        ctx.onSettingsChanged(this::settingsChanged);
    }

    /** Shows {@code panel} above the form of tool {@code id}. */
    public void addPanel(ToolId id, Panel panel) {
        panels.put(Objects.requireNonNull(id), Objects.requireNonNull(panel));
    }

    /** Remembers which sections are open in {@code store} (from the next rebuild); in memory only until called. */
    public void setSectionStates(SectionStateStore store) {
        this.sectionStates = Objects.requireNonNull(store);
    }

    /** Shows the preset row for tools with settings (from the next rebuild). */
    public void setPresets(Presets presets) {
        this.presets = Objects.requireNonNull(presets);
    }

    /** The content for the active tool; the window manager calls this after each rebuild. */
    public Node build() {
        if (pane != null && paneTool != null) {
            scrollOffsets.put(paneTool, pane.scroll().offset());
        }
        String reveal = pendingReveal;
        pendingReveal = null;
        form = null;
        formTool = null;
        shownPanel = null;
        presetBar = null;
        Optional<Tool> active = ctx.tools().active();
        if (active.isEmpty()) {
            return wrap(Label.dim(tr.translate("sculptory.window.tool_settings.none")).setWrap(true), null);
        }
        Tool tool = active.get();
        ToolId id = tool.descriptor().id();
        Column column = new Column();
        column.setGap(6);
        column.add(header(tool, id));
        if (presets != null && !tool.schema().defs().isEmpty()) {
            presetBar = new PresetBar(presets, id, tr, popups);
            column.add(presetBar.node());
        }
        Panel panel = panels.get(id);
        if (panel != null) {
            shownPanel = panel;
            column.add(panel.build());
        }
        if (tool.schema().defs().isEmpty()) {
            if (panel == null) {
                String text = tool.hints(ctx.contextFor(id)).stream()
                        .map(KeyHint::descriptionKey)
                        .map(tr::translate)
                        .findFirst()
                        .orElse(tr.translate("sculptory.window.tool_settings.empty"));
                column.add(Label.dim(text).setWrap(true));
            }
            return wrap(column, id);
        }
        formTool = id;
        form = SettingsForm.build(ctx.settings(id), values -> {
            applying = true;
            try {
                ctx.updateSettings(id, values);
            } finally {
                applying = false;
            }
        }, services, sectionStates.memoryFor(id));
        column.add(form.node());
        Node content = wrap(column, id);
        if (reveal != null) {
            reveal(reveal);
        }
        return content;
    }

    /**
     * Shows a <b>?</b> beside the tool's name (from the next rebuild) that calls {@code help} with the tool: the editor
     * opens its wiki page.
     */
    public void setHelp(Consumer<ToolId> help) {
        this.help = help;
    }

    /** The tool's name, with the <b>?</b> at the right when there is help. */
    private Node header(Tool tool, ToolId id) {
        Label name = Label.heading(tr.translate(tool.descriptor().nameKey()));
        if (help == null) {
            return name;
        }
        name.setGrow(1);
        Button button = new HelpButton(() -> help.accept(id));
        button.setTooltip(tr.translate("sculptory.wiki.tool_help.tooltip"));
        Row row = Row.of(name, button);
        row.setCrossAlign(Align.CENTER);
        return row;
    }

    /**
     * The <b>?</b>: a click only, so Tab and F6 still go straight to the tool's settings (Ctrl+K finds the page by
     * name, "Wiki: ...").
     */
    private static final class HelpButton extends Button {
        HelpButton(Runnable onClick) {
            super("?", onClick);
            setFixedSize(12, 12);
        }

        @Override
        public boolean isFocusable() {
            return false;
        }
    }

    /** Follows the preset row and the shown panel's tool state; call every frame while the window is open. */
    public void refresh() {
        if (presetBar != null) presetBar.refresh();
        if (shownPanel != null) shownPanel.refresh();
    }

    /** The open form, if the active tool has settings. */
    public Optional<SettingsForm> form() {
        return Optional.ofNullable(form);
    }

    /** The preset row, if presets are set up and the active tool has settings. */
    public Optional<PresetBar> presetBar() {
        return Optional.ofNullable(presetBar);
    }

    /** The presets, once {@link #setPresets} was called (the command search lists the active tool's). */
    public Optional<Presets> presets() {
        return Optional.ofNullable(presets);
    }

    private void settingsChanged(ToolId id) {
        if (!applying && form != null && id.equals(formTool)) {
            form.refresh(ctx.settings(id));
        }
    }

    /**
     * Brings setting {@code key} (a {@link SettingDef#key}) of the active tool into view: opens its section, scrolls to
     * it (at the next layout) and puts the keyboard on its control. Open the window first; if its content for the
     * active tool isn't built yet, this happens when it is. Returns false, doing nothing, when the active tool has no
     * such setting or it is hidden now (it applies only with other values, e.g. Height while Height = diameter is on).
     */
    public boolean revealSetting(String key) {
        Optional<Tool> active = ctx.tools().active();
        if (active.isEmpty()) {
            return false;
        }
        ToolId id = active.get().descriptor().id();
        Optional<SettingDef<?>> def = active.get().schema().def(key);
        if (def.isEmpty() || !ctx.settings(id).isVisible(def.get())) {
            return false;
        }
        if (form != null && pane != null && id.equals(formTool)) {
            reveal(key);
        } else {
            pendingReveal = key;
        }
        return true;
    }

    private void reveal(String key) {
        pendingReveal = null;
        form.revealSetting(key, popups.get()).ifPresent(pane::scrollIntoView);
    }

    /** The scroll pane holding the content built last. */
    public Optional<ScrollPane> pane() {
        return Optional.ofNullable(pane);
    }

    /** Wraps the content in a scroll pane, at the position tool {@code id} was last left at this session. */
    private Node wrap(Node content, ToolId id) {
        pane = new ScrollPane(content);
        paneTool = id;
        if (id != null) {
            pane.restoreOffset(scrollOffsets.getOrDefault(id, 0));
        }
        return pane;
    }
}
