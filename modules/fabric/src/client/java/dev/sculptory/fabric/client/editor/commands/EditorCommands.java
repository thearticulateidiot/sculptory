package dev.sculptory.fabric.client.editor.commands;

import dev.sculptory.core.region.Region;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.fabric.client.editor.EditorContext;
import dev.sculptory.fabric.client.editor.EditorController;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.clipboard.ClipboardActions;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.tool.Selection;
import dev.sculptory.fabric.client.editor.tool.Tool;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.ToolRegistry;
import dev.sculptory.fabric.client.editor.tools.select.SelectionActions;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.HistoryOffer;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.engine.Perm;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The editor's commands: the menu bar's File, Edit, Selection, Tools, View and Help menus,
 * registered in menu order. Each runs through the code its key or button runs: keyed actions through
 * {@link EditorController#runKeyAction}, the selection ops through {@link SelectionActions} (the Selection window's
 * buttons), the clipboard items through {@link ClipboardActions} (the Clipboard window's), windows through the
 * editor UI. The Notifications window ({@link #NOTIFICATIONS}), the Quick start card ({@link #QUICK_START}), the
 * tutorial ({@link #TUTORIAL}, {@link #TUTORIAL_WINDOW}) and the Wiki window ({@link #VIEW_WIKI}, {@link #WIKI}) are
 * registered hidden, for later parts of the editor to
 * {@linkplain CommandRegistry#provide provide}.
 */
public final class EditorCommands {
    public static final String LIBRARY = "file.library";
    public static final String SAVE_SELECTION = "file.save_selection";
    public static final String EXPORT_CLIPBOARD = "file.export_clipboard";
    public static final String CLOSE_EDITOR = "file.close_editor";
    public static final String UNDO = "edit.undo";
    public static final String REDO = "edit.redo";
    public static final String UNDO_ANYWAY = "edit.undo_anyway";
    public static final String REDO_ANYWAY = "edit.redo_anyway";
    public static final String HISTORY = "edit.history";
    public static final String COPY = "edit.copy";
    public static final String CUT = "edit.cut";
    public static final String PASTE = "edit.paste";
    public static final String ROTATE_RIGHT = "edit.rotate_right";
    public static final String ROTATE_LEFT = "edit.rotate_left";
    public static final String FLIP_LEFT_RIGHT = "edit.flip_left_right";
    public static final String FLIP_FRONT_BACK = "edit.flip_front_back";
    public static final String FLIP_UPSIDE_DOWN = "edit.flip_upside_down";
    public static final String CLEAR_CLIPBOARD = "edit.clear_clipboard";
    public static final String DESELECT = "selection.deselect";
    public static final String CONVERT_TO_BOX = "selection.convert_to_box";
    public static final String FILL = "selection.fill";
    public static final String REPLACE = "selection.replace";
    public static final String ERASE = "selection.erase";
    public static final String HOLLOW = "selection.hollow";
    public static final String WALLS = "selection.walls";
    public static final String OVERLAY = "selection.overlay";
    public static final String NATURALIZE = "selection.naturalize";
    public static final String UPDATE_BLOCKS = "selection.update_blocks";
    public static final String MOVE = "selection.move";
    public static final String STACK = "selection.stack";
    public static final String SYMMETRY_CENTRE = "selection.symmetry_centre";
    /** View: one per window, {@code view.window.<window id>}. */
    public static final String WINDOW_PREFIX = "view.window.";
    public static final String NOTIFICATIONS = "view.notifications";
    /** View > Tutorial: the Tutorial window, provided by the tutorial. */
    public static final String TUTORIAL_WINDOW = "view.tutorial";
    /** View > Wiki: opens or closes the Wiki window, provided by the editor UI. */
    public static final String VIEW_WIKI = "view.wiki";
    public static final String HIDE_WINDOWS = "view.hide_windows";
    public static final String RESET_LAYOUT = "view.reset_layout";
    public static final String UI_SIZE = "view.ui_size";
    public static final String UI_SMALLER = "view.ui_size.smaller";
    public static final String UI_LARGER = "view.ui_size.larger";
    /** View > Opacity…: the panels' and the tool outlines' opacity (a popup). */
    public static final String OPACITY = "view.opacity";
    public static final String AIM_AT_FLUIDS = "view.aim_at_fluids";
    public static final String FIND_COMMAND = "help.find_command";
    public static final String KEY_SHEET = "help.key_sheet";
    public static final String QUICK_START = "help.quick_start";
    /** Help > Tutorial: the Tutorial window, provided by the tutorial. */
    public static final String TUTORIAL = "help.tutorial";
    /** Help > Wiki: the Wiki window, provided by the editor UI. */
    public static final String WIKI = "help.wiki";
    public static final String CHANGE_KEYS = "help.change_keys";

    /**
     * The palette slots that start a new tool group after the first (Select · Terrain · Place and scatter · Build ·
     * Weather): the Weather brush, a terrain brush that came after the Build tools, stands in a group of its own.
     */
    public static final Set<Integer> TOOL_GROUP_STARTS = Set.of(2, 8, 10, 15);

    /** What the commands need from the editor UI. */
    public interface Host {
        /** Whether the window is registered (Clipboard and Library need clipboard actions). */
        boolean hasWindow(String id);

        /** Whether the window is open and not hidden with the rest. */
        boolean isWindowShown(String id);

        /** Opens or closes the window (as its key does). */
        void toggleWindow(String id);

        /** Opens the window (showing hidden windows again) and brings it to the front. */
        void showWindow(String id);

        boolean windowsHidden();

        void toggleWindowsHidden();

        void resetLayout();

        void toggleHelp();

        void openCommandSearch();

        /** The Selection window's Replace… dialog. */
        void openReplace();

        /** The Selection window's Overlay… dialog. */
        void openOverlay();

        /** The Selection window's Naturalize… dialog. */
        void openNaturalize();

        /** The Save selection as asset… name dialog. */
        void openSaveSelection();

        /** The Export… dialog for the clipboard: file name and format. */
        void openExportClipboard();

        /** The editor UI size in percent. */
        int uiSizePercent();

        void setUiSize(int percent);

        void stepUiSize(int direction);

        /** View > Opacity…: the popup with the Panels and Tool outlines sliders. */
        void openOpacity();

        /** The player's key that leaves the editor (a vanilla binding), e.g. "B". */
        String editorToggleKey();
    }

    private final EditorContext ctx;
    private final EditorController controller;
    private final SelectionActions selection;
    private final Host host;
    private final CommandRegistry registry;

    private EditorCommands(EditorContext ctx, EditorController controller, SelectionActions selection, Host host,
            Translator tr) {
        this.ctx = Objects.requireNonNull(ctx);
        this.controller = Objects.requireNonNull(controller);
        this.selection = Objects.requireNonNull(selection);
        this.host = Objects.requireNonNull(host);
        EditorKeymap keymap = controller.keymap();
        this.registry = new CommandRegistry(keymap, tr);
    }

    /** The editor's commands in menu order, for the palette's tools as registered now. */
    public static CommandRegistry build(EditorContext ctx, EditorController controller, SelectionActions selection,
            Host host, Translator tr) {
        EditorCommands commands = new EditorCommands(ctx, controller, selection, host, tr);
        commands.file();
        commands.edit();
        commands.selection();
        commands.tools();
        commands.view();
        commands.help();
        return commands.registry;
    }

    private static Command.Builder command(String id, CommandMenu menu) {
        return Command.builder(id, menu, "sculptory.command." + id);
    }

    private void add(Command.Builder builder) {
        registry.register(builder.build());
    }

    // ---- File ----

    private void file() {
        add(command(LIBRARY, CommandMenu.FILE).key(KeyAction.LIBRARY)
                .visible(() -> host.hasWindow(EditorWindows.LIBRARY))
                .action(() -> host.showWindow(EditorWindows.LIBRARY)));
        add(command(SAVE_SELECTION, CommandMenu.FILE).separatorBefore()
                .visible(this::hasClipboardActions)
                .availability(() -> first(needsSelection(), permission(Perm.CLIPBOARD)))
                .action(host::openSaveSelection));
        add(command(EXPORT_CLIPBOARD, CommandMenu.FILE)
                .visible(this::hasClipboardActions)
                .availability(() -> first(permission(Perm.SCHEMATIC_EXPORT), needsClipboard()))
                .action(host::openExportClipboard));
        add(command(CLOSE_EDITOR, CommandMenu.FILE).separatorBefore()
                .keyText(host::editorToggleKey)
                .action(controller::exitEditor));
    }

    // ---- Edit ----

    private void edit() {
        add(command(UNDO, CommandMenu.EDIT).key(KeyAction.UNDO)
                .availability(() -> history(true))
                .action(() -> controller.runKeyAction(KeyAction.UNDO)));
        add(command(REDO, CommandMenu.EDIT).key(KeyAction.REDO)
                .availability(() -> history(false))
                .action(() -> controller.runKeyAction(KeyAction.REDO)));
        add(command(UNDO_ANYWAY, CommandMenu.EDIT).tooltip("sculptory.history.anyway.tooltip")
                .visible(() -> offer().filter(offer -> !offer.redo()).isPresent())
                .availability(this::offerAvailability)
                .action(this::acceptOffer));
        add(command(REDO_ANYWAY, CommandMenu.EDIT).tooltip("sculptory.history.anyway.tooltip")
                .visible(() -> offer().filter(HistoryOffer::redo).isPresent())
                .availability(this::offerAvailability)
                .action(this::acceptOffer));
        add(command(HISTORY, CommandMenu.EDIT).key(KeyAction.HISTORY)
                .action(() -> host.showWindow(EditorWindows.HISTORY)));

        add(command(COPY, CommandMenu.EDIT).key(KeyAction.COPY).separatorBefore()
                .visible(this::hasClipboardActions)
                .availability(() -> first(needsSelection(), permission(Perm.CLIPBOARD)))
                .action(() -> controller.runKeyAction(KeyAction.COPY)));
        add(command(CUT, CommandMenu.EDIT).key(KeyAction.CUT)
                .visible(this::hasClipboardActions)
                .availability(() -> first(needsSelection(), permission(Perm.CLIPBOARD), permission(Perm.REGION)))
                .action(() -> controller.runKeyAction(KeyAction.CUT)));
        add(command(PASTE, CommandMenu.EDIT).key(KeyAction.PASTE)
                .visible(this::hasClipboardActions)
                .availability(() -> first(permission(Perm.CLIPBOARD), needsClipboard()))
                .action(() -> controller.runKeyAction(KeyAction.PASTE)));

        add(command(ROTATE_RIGHT, CommandMenu.EDIT).key(KeyAction.ROTATE_CW).separatorBefore()
                .visible(this::hasClipboardActions)
                .availability(this::needsSomethingToTurn)
                .action(() -> clipboard(actions -> actions.rotate(1))));
        add(command(ROTATE_LEFT, CommandMenu.EDIT).key(KeyAction.ROTATE_CCW)
                .visible(this::hasClipboardActions)
                .availability(this::needsSomethingToTurn)
                .action(() -> clipboard(actions -> actions.rotate(-1))));
        add(command(FLIP_LEFT_RIGHT, CommandMenu.EDIT).key(KeyAction.FLIP_LEFT_RIGHT)
                .visible(this::hasClipboardActions)
                .availability(this::needsSomethingToTurn)
                .action(() -> clipboard(actions -> actions.flip(Mirror.X))));
        add(command(FLIP_FRONT_BACK, CommandMenu.EDIT).key(KeyAction.FLIP_FRONT_BACK)
                .visible(this::hasClipboardActions)
                .availability(this::needsSomethingToTurn)
                .action(() -> clipboard(actions -> actions.flip(Mirror.Z))));
        add(command(FLIP_UPSIDE_DOWN, CommandMenu.EDIT).key(KeyAction.FLIP_UPSIDE_DOWN)
                .visible(this::hasClipboardActions)
                .availability(this::needsSomethingToTurn)
                .action(() -> clipboard(ClipboardActions::flipUpsideDown)));
        add(command(CLEAR_CLIPBOARD, CommandMenu.EDIT).separatorBefore()
                .visible(this::hasClipboardActions)
                .availability(this::needsClipboard)
                .action(() -> clipboard(ClipboardActions::forget)));
    }

    // ---- Selection ----

    private void selection() {
        add(command(DESELECT, CommandMenu.SELECTION).key(KeyAction.DESELECT)
                .availability(this::needsSelection)
                .action(() -> controller.runKeyAction(KeyAction.DESELECT)));
        add(command(CONVERT_TO_BOX, CommandMenu.SELECTION)
                .availability(() -> first(needsSelection(), notABox()))
                .action(selection::convertToBox));
        add(command(FILL, CommandMenu.SELECTION).separatorBefore()
                .availability(this::needsRegionSelection)
                .action(selection::fill));
        add(command(REPLACE, CommandMenu.SELECTION)
                .availability(this::needsRegionSelection)
                .action(host::openReplace));
        add(command(ERASE, CommandMenu.SELECTION).key(KeyAction.ERASE_SELECTION)
                .availability(this::needsRegionSelection)
                .action(selection::erase));
        add(command(HOLLOW, CommandMenu.SELECTION)
                .availability(this::needsRegionSelection)
                .action(selection::hollow));
        add(command(WALLS, CommandMenu.SELECTION)
                .availability(this::needsRegionSelection)
                .action(selection::walls));
        add(command(OVERLAY, CommandMenu.SELECTION)
                .availability(this::needsRegionSelection)
                .action(host::openOverlay));
        add(command(NATURALIZE, CommandMenu.SELECTION)
                .availability(this::needsRegionSelection)
                .action(host::openNaturalize));
        add(command(UPDATE_BLOCKS, CommandMenu.SELECTION)
                .tooltip("sculptory.selection.update_blocks.tooltip")
                .availability(this::needsRegionSelection)
                .action(selection::updateBlocks));
        add(command(MOVE, CommandMenu.SELECTION).separatorBefore()
                .visible(this::hasClipboardActions)
                .availability(this::needsRegionSelection)
                .action(() -> clipboard(ClipboardActions::move)));
        add(command(STACK, CommandMenu.SELECTION)
                .visible(this::hasClipboardActions)
                .availability(this::needsRegionSelection)
                .action(() -> clipboard(ClipboardActions::stack)));
        add(command(SYMMETRY_CENTRE, CommandMenu.SELECTION).key(KeyAction.SET_SYMMETRY_CENTRE).separatorBefore()
                .tooltip("sculptory.command.selection.symmetry_centre.tooltip")
                .availability(() -> ctx.tools().active().isPresent() ? Availability.OK
                        : Availability.no("sculptory.command.reason.no_tool"))
                .action(() -> controller.runKeyAction(KeyAction.SET_SYMMETRY_CENTRE)));
    }

    // ---- Tools ----

    /** The palette's tools in slot order, a line between the groups, a check on the active one. */
    private void tools() {
        List<Tool> palette = ctx.tools().paletteOrder();
        for (int slot = 1; slot <= Math.min(palette.size(), ToolRegistry.PALETTE_SLOTS); slot++) {
            Tool tool = palette.get(slot - 1);
            ToolId id = tool.descriptor().id();
            int index = slot;
            Command.Builder builder = Command.builder(toolCommand(id), CommandMenu.TOOLS, tool.descriptor().nameKey())
                    .key(KeyAction.toolSlot(slot))
                    .tooltip(tool.descriptor().nameKey() + ".tooltip")
                    .checked(() -> ctx.tools().isActive(id))
                    .availability(() -> controller.unavailability(tool)
                            .map(EditorCommands::fromNotice)
                            .orElse(Availability.OK))
                    .action(() -> controller.selectSlot(index));
            if (TOOL_GROUP_STARTS.contains(slot)) {
                builder.separatorBefore();
            }
            add(builder);
        }
    }

    /** The Tools menu command of a tool: {@code tool.<tool id>}. */
    public static String toolCommand(ToolId id) {
        return "tool." + id.value();
    }

    // ---- View ----

    private void view() {
        for (String window : EditorWindows.MENU) {
            Command.Builder builder = Command.builder(WINDOW_PREFIX + window, CommandMenu.VIEW,
                            EditorWindows.titleKey(window))
                    .visible(() -> host.hasWindow(window))
                    .checked(() -> host.isWindowShown(window))
                    .action(() -> host.toggleWindow(window));
            if (window.equals(EditorWindows.LIBRARY)) {
                builder.key(KeyAction.LIBRARY);
            } else if (window.equals(EditorWindows.HISTORY)) {
                builder.key(KeyAction.HISTORY);
            }
            add(builder);
        }
        add(command(NOTIFICATIONS, CommandMenu.VIEW).provided());
        add(Command.builder(TUTORIAL_WINDOW, CommandMenu.VIEW, EditorWindows.titleKey(EditorWindows.TUTORIAL))
                .provided());
        add(command(VIEW_WIKI, CommandMenu.VIEW).provided());
        add(command(HIDE_WINDOWS, CommandMenu.VIEW).key(KeyAction.HIDE_WINDOWS).separatorBefore()
                .checked(host::windowsHidden)
                .action(host::toggleWindowsHidden));
        add(command(RESET_LAYOUT, CommandMenu.VIEW)
                .tooltip("sculptory.command.view.reset_layout.tooltip")
                .action(host::resetLayout));
        add(command(UI_SIZE, CommandMenu.VIEW).separatorBefore()
                .children(uiSizes()));
        add(command(OPACITY, CommandMenu.VIEW)
                .tooltip("sculptory.command.view.opacity.tooltip")
                .action(host::openOpacity));
        add(command(AIM_AT_FLUIDS, CommandMenu.VIEW).key(KeyAction.AIM_AT_FLUIDS)
                .tooltip("sculptory.command.view.aim_at_fluids.tooltip")
                .checked(controller::aimsAtFluids)
                .action(() -> controller.setAimAtFluids(!controller.aimsAtFluids())));
    }

    /** UI size ▸: Smaller, Larger, then every size with a check on the current one. */
    private List<Command> uiSizes() {
        List<Command> sizes = new ArrayList<>();
        sizes.add(command(UI_SMALLER, CommandMenu.VIEW).key(KeyAction.UI_SMALLER)
                .action(() -> host.stepUiSize(-1)).build());
        sizes.add(command(UI_LARGER, CommandMenu.VIEW).key(KeyAction.UI_LARGER)
                .action(() -> host.stepUiSize(1)).build());
        boolean first = true;
        for (int percent : UiScale.steps()) {
            boolean isDefault = percent == UiScale.DEFAULT_PERCENT;
            Command.Builder builder = Command.builder(UI_SIZE + "." + percent, CommandMenu.VIEW,
                            isDefault ? "sculptory.topbar.ui_size.default" : "sculptory.command.view.ui_size.step")
                    .labelArgs(percent + "%")
                    .checked(() -> host.uiSizePercent() == percent)
                    .action(() -> host.setUiSize(percent));
            if (isDefault) {
                builder.key(KeyAction.UI_RESET);
            }
            if (first) {
                builder.separatorBefore();
                first = false;
            }
            sizes.add(builder.build());
        }
        return sizes;
    }

    // ---- Help ----

    private void help() {
        add(command(FIND_COMMAND, CommandMenu.HELP).key(KeyAction.COMMAND_SEARCH)
                .action(host::openCommandSearch));
        add(command(KEY_SHEET, CommandMenu.HELP).key(KeyAction.HELP)
                .action(host::toggleHelp));
        add(command(QUICK_START, CommandMenu.HELP).provided());
        add(command(TUTORIAL, CommandMenu.HELP).provided());
        add(command(WIKI, CommandMenu.HELP).provided());
        add(command(CHANGE_KEYS, CommandMenu.HELP)
                .action(() -> host.showWindow(EditorWindows.KEYS)));
    }

    // ---- Availability ----

    /** The first reason not to run, or OK. */
    private static Availability first(Availability... checks) {
        for (Availability check : checks) {
            if (!check.enabled()) {
                return check;
            }
        }
        return Availability.OK;
    }

    private Optional<EditorSession> session() {
        return ctx.session();
    }

    private Availability permission(Perm perm) {
        Optional<EditorSession> session = session();
        if (session.isEmpty()) {
            return Availability.no("sculptory.command.reason.offline");
        }
        if (!session.get().permissions().has(perm)) {
            String node = perm.node();
            String prefix = "sculptory.";
            return Availability.no("sculptory.command.reason.no_permission",
                    node.startsWith(prefix) ? node.substring(prefix.length()) : node);
        }
        return Availability.OK;
    }

    private Availability needsSelection() {
        return ctx.selectionState().isPresent() ? Availability.OK
                : Availability.no("sculptory.command.reason.needs_selection");
    }

    /** A selection and the region permission (the selection ops, Move and Stack). */
    private Availability needsRegionSelection() {
        return first(needsSelection(), permission(Perm.REGION));
    }

    private Availability notABox() {
        Optional<Selection> state = ctx.selectionState();
        return state.isPresent() && state.get().base() instanceof Region.Cuboid
                ? Availability.no("sculptory.command.reason.already_box") : Availability.OK;
    }

    private Availability needsClipboard() {
        Optional<EditorSession> session = session();
        if (session.isEmpty()) {
            return Availability.no("sculptory.command.reason.offline");
        }
        return session.get().clipboards().current().isPresent() ? Availability.OK
                : Availability.no("sculptory.command.reason.nothing_copied");
    }

    /** Rotate and Flip turn a placement in progress, or else the next paste of the clipboard. */
    private Availability needsSomethingToTurn() {
        return controller.activePlaceTool().isPresent() ? Availability.OK : needsClipboard();
    }

    private Availability history(boolean undo) {
        Optional<EditorSession> session = session();
        if (session.isEmpty()) {
            return Availability.no("sculptory.command.reason.offline");
        }
        boolean can = session.get().historyBusy()
                || (undo ? session.get().history().canUndo() : session.get().history().canRedo());
        return can ? Availability.OK
                : Availability.no(undo ? "sculptory.history.nothing_to_undo" : "sculptory.history.nothing_to_redo");
    }

    private Optional<HistoryOffer> offer() {
        return session().flatMap(EditorSession::historyOffer);
    }

    private Availability offerAvailability() {
        boolean busy = offer().map(HistoryOffer::running).orElse(true)
                || session().map(EditorSession::historyBusy).orElse(true);
        return busy ? Availability.no("sculptory.command.reason.history_busy") : Availability.OK;
    }

    /** What the Undo anyway toast's button does. */
    private void acceptOffer() {
        session().ifPresent(EditorSession::acceptHistoryOffer);
    }

    private boolean hasClipboardActions() {
        return controller.clipboard().isPresent();
    }

    private void clipboard(Consumer<ClipboardActions> action) {
        controller.clipboard().ifPresent(action);
    }

    /** A toast saying why not (a tool that can't be used), as an availability. */
    private static Availability fromNotice(Notice notice) {
        return Availability.no(notice.key(), notice.args().toArray(String[]::new));
    }
}
