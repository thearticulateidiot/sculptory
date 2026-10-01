package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.commands.Availability;
import dev.sculptory.fabric.client.editor.commands.Command;
import dev.sculptory.fabric.client.editor.commands.CommandMenu;
import dev.sculptory.fabric.client.editor.commands.CommandRegistry;
import dev.sculptory.fabric.client.editor.commands.EditorCommands;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.hud.HelpSheet;
import dev.sculptory.fabric.client.editor.hud.ToastStack;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.input.KeyChord;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import dev.sculptory.fabric.client.editor.ui.widget.MenuItem;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import dev.sculptory.fabric.client.session.Permissions;
import dev.sculptory.fabric.client.session.StrokeHandle;
import dev.sculptory.fabric.client.session.StrokeParams;
import dev.sculptory.fabric.client.session.ToolAction;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.protocol.v2.Limits;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The editor's command registry: what the menus hold, the keys they show, availability, and running. */
class EditorCommandsTest {
    private final EditorTestRig rig = new EditorTestRig();
    private final UiScale scale = new UiScale();
    private EditorUi ui;
    private CommandRegistry commands;

    @BeforeEach
    void build() {
        ui = new EditorUi(rig.ctx, rig.controller, rig.platform, rig.actions, EditorUiTest.TEXT, new ToastStack(() -> 0L),
                new EditorUi.Services(Translator.KEYS, BlockCatalog.EMPTY, icon -> null,
                        () -> new HelpSheet.VanillaKeys("W A S D", "Space", "Shift", "B", "T", "/", "E"),
                        "config/sculptory/editor-keys.json", keymap -> true, scale));
        rig.controller.setUi(ui);
        rig.confirmer = ui;
        ui.setEditing(true);
        rig.mode.enter();
        ui.layout(640, 360);
        commands = ui.commands();
    }

    private List<String> ids(CommandMenu menu) {
        return commands.menu(menu).stream().map(Command::id).toList();
    }

    private List<String> labels(CommandMenu menu) {
        return commands.menuItems(menu).stream().map(item -> item.isSeparator() ? "---" : item.label()).toList();
    }

    private MenuItem item(CommandMenu menu, String labelKey) {
        return commands.menuItems(menu).stream().filter(item -> item.label().equals(labelKey)).findFirst()
                .orElseThrow(() -> new AssertionError(labelKey + " not in " + labels(menu)));
    }

    private Availability availability(String id) {
        return commands.availability(commands.get(id).orElseThrow());
    }

    private void permissions(Perm... removed) {
        EnumSet<Perm> granted = EnumSet.allOf(Perm.class);
        granted.removeAll(List.of(removed));
        rig.session.setPermissions(new Permissions(Perm.mask(granted), Limits.DEFAULTS));
    }

    private void selectBox() {
        rig.ctx.setSelection(new Box(new BlockPos(0, 0, 0), new BlockPos(3, 3, 3)));
    }

    private void strokeTwice() {
        for (ToolId tool : List.of(ToolId.RAISE, ToolId.LOWER)) {
            StrokeHandle stroke = rig.session.beginStroke(tool, new BrushSpec(BrushTool.RAISE, 4, 1f, Falloff.SMOOTH,
                    Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L), StrokeParams.DEFAULT);
            stroke.dab(new Dab(0, 0, 1024, 0, 255));
            stroke.end();
        }
    }

    // ---- What the menus hold ----

    @Test
    void everyItemOfTheDesignIsRegisteredInMenuOrder() {
        assertEquals(List.of("file.library", "file.save_selection", "file.export_clipboard", "file.close_editor"),
                ids(CommandMenu.FILE));
        assertEquals(List.of("edit.undo", "edit.redo", "edit.undo_anyway", "edit.redo_anyway", "edit.history",
                "edit.copy", "edit.cut", "edit.paste", "edit.rotate_right", "edit.rotate_left", "edit.flip_left_right",
                "edit.flip_front_back", "edit.flip_upside_down", "edit.clear_clipboard"), ids(CommandMenu.EDIT));
        assertEquals(List.of("selection.deselect", "selection.convert_to_box", "selection.fill", "selection.replace",
                "selection.erase", "selection.hollow", "selection.walls", "selection.overlay", "selection.naturalize",
                "selection.update_blocks", "selection.move", "selection.stack", "selection.symmetry_centre"),
                ids(CommandMenu.SELECTION));
        assertEquals(List.of("tool.select", "tool.raise", "tool.lower", "tool.smooth", "tool.flatten", "tool.paint",
                "tool.palette", "tool.place", "tool.scatter", "tool.shape", "tool.generate", "tool.extrude",
                "tool.fluid", "tool.tinker", "tool.weather"), ids(CommandMenu.TOOLS));
        assertEquals(List.of("view.window.tool_settings", "view.window.selection", "view.window.clipboard",
                "view.window.library", "view.window.history", "view.window.keys", "view.notifications",
                "view.tutorial", "view.wiki", "view.hide_windows", "view.reset_layout", "view.ui_size",
                "view.opacity", "view.aim_at_fluids"), ids(CommandMenu.VIEW));
        assertEquals(List.of("help.find_command", "help.key_sheet", "help.quick_start", "help.tutorial", "help.wiki",
                "help.change_keys"), ids(CommandMenu.HELP));
    }

    @Test
    void theMenusShowTheirItemsWithSeparatorsAndKeys() {
        assertEquals(List.of("sculptory.command.file.library", "---", "sculptory.command.file.save_selection",
                "sculptory.command.file.export_clipboard", "---", "sculptory.command.file.close_editor"),
                labels(CommandMenu.FILE));
        assertEquals("L", item(CommandMenu.FILE, "sculptory.command.file.library").keyText());
        assertEquals("B", item(CommandMenu.FILE, "sculptory.command.file.close_editor").keyText(),
                "the player's own editor key");
        assertEquals("Ctrl+Z", item(CommandMenu.EDIT, "sculptory.command.edit.undo").keyText());
        assertEquals("Ctrl+Y", item(CommandMenu.EDIT, "sculptory.command.edit.redo").keyText(), "the first chord");
        assertEquals("", item(CommandMenu.VIEW, "sculptory.command.view.aim_at_fluids").keyText(), "unbound");
        assertEquals("Ctrl+K", item(CommandMenu.HELP, "sculptory.command.help.find_command").keyText());
        assertEquals("F1", item(CommandMenu.HELP, "sculptory.command.help.key_sheet").keyText());
        assertEquals(List.of("sculptory.command.help.find_command", "sculptory.command.help.key_sheet",
                "sculptory.command.help.quick_start", "sculptory.command.help.tutorial",
                "sculptory.command.help.wiki", "sculptory.command.help.change_keys"), labels(CommandMenu.HELP),
                "the editor UI provides Quick start, Tutorial and Wiki");
        assertFalse(labels(CommandMenu.EDIT).contains("sculptory.command.edit.undo_anyway"), "only when offered");
    }

    @Test
    void keysFollowTheKeymapAsRebound() {
        rig.keymap.bind(KeyAction.UNDO, List.of(KeyChord.parse("ctrl+u")));
        rig.keymap.bind(KeyAction.LIBRARY, List.of());
        rig.keymap.bind(KeyAction.AIM_AT_FLUIDS, List.of(KeyChord.parse("g")));
        assertEquals("Ctrl+U", item(CommandMenu.EDIT, "sculptory.command.edit.undo").keyText());
        assertEquals("", item(CommandMenu.FILE, "sculptory.command.file.library").keyText());
        assertEquals("G", item(CommandMenu.VIEW, "sculptory.command.view.aim_at_fluids").keyText());
        rig.keymap.bind(KeyAction.COMMAND_SEARCH, List.of(KeyChord.parse("ctrl+p")));
        assertEquals("Ctrl+P", item(CommandMenu.HELP, "sculptory.command.help.find_command").keyText());
        assertTrue(commands.searchEntries().stream().anyMatch(entry -> entry.id().equals("edit.undo")
                && entry.keyText().equals("Ctrl+U")), "the search shows the same keys");
    }

    @Test
    void theToolsMenuListsThePaletteInItsGroupsWithTheActiveToolChecked() {
        List<MenuItem> items = commands.menuItems(CommandMenu.TOOLS);
        List<String> shape = items.stream().map(item -> item.isSeparator() ? "---" : item.keyText()).toList();
        assertEquals(List.of("1", "---", "2", "3", "4", "5", "6", "7", "---", "8", "9", "---", "0", "-", "=", "[",
                "]", "---", "\\"), shape);
        assertEquals("sculptory.tool.select", items.get(0).label());
        assertTrue(items.get(0).isChecked(), "Select is active");
        assertEquals("sculptory.tool.select.tooltip", items.get(0).tooltipText());

        permissions(Perm.BRUSH);
        MenuItem raise = commands.menuItems(CommandMenu.TOOLS).get(2);
        assertFalse(raise.isEnabled());
        assertEquals("sculptory.notice.tool_needs_permission[sculptory.tool.raise,sculptory.brush]",
                raise.tooltipText(), "the reason the palette gives");

        permissions();
        commands.run(EditorCommands.toolCommand(ToolId.SMOOTH));
        assertTrue(rig.ctx.tools().isActive(ToolId.SMOOTH));
        List<MenuItem> after = commands.menuItems(CommandMenu.TOOLS);
        assertFalse(after.get(0).isChecked());
        assertTrue(after.get(4).isChecked());
    }

    @Test
    void theUiSizeSubmenuHasSmallerLargerAndEverySize() {
        MenuItem uiSize = item(CommandMenu.VIEW, "sculptory.command.view.ui_size");
        assertTrue(uiSize.hasSubmenu());
        List<MenuItem> sizes = uiSize.submenuItems();
        assertEquals("sculptory.command.view.ui_size.smaller", sizes.get(0).label());
        assertEquals("Ctrl+-", sizes.get(0).keyText());
        assertEquals("Ctrl+=", sizes.get(1).keyText());
        assertTrue(sizes.get(2).isSeparator());
        assertEquals(UiScale.steps().size() + 3, sizes.size());
        MenuItem hundred = sizes.stream().filter(item -> item.label().equals("sculptory.topbar.ui_size.default[100%]"))
                .findFirst().orElseThrow();
        assertTrue(hundred.isChecked());
        assertEquals("Ctrl+0", hundred.keyText());
        commands.run("view.ui_size.75");
        assertEquals(75, scale.percent());
        assertTrue(item(CommandMenu.VIEW, "sculptory.command.view.ui_size").submenuItems().stream()
                .anyMatch(item -> item.label().equals("sculptory.command.view.ui_size.step[75%]") && item.isChecked()));
        commands.run(EditorCommands.UI_LARGER);
        assertEquals(80, scale.percent());
    }

    // ---- Availability ----

    @Test
    void selectionCommandsSayTheyNeedASelectionOrThePermission() {
        MenuItem fill = item(CommandMenu.SELECTION, "sculptory.command.selection.fill");
        assertFalse(fill.isEnabled());
        assertEquals("sculptory.command.reason.needs_selection", fill.tooltipText());
        assertEquals(Availability.no("sculptory.command.reason.needs_selection"), availability(EditorCommands.ERASE));

        selectBox();
        assertTrue(item(CommandMenu.SELECTION, "sculptory.command.selection.fill").isEnabled());
        assertEquals(Availability.no("sculptory.command.reason.already_box"),
                availability(EditorCommands.CONVERT_TO_BOX));

        permissions(Perm.REGION);
        assertEquals("sculptory.command.reason.no_permission[region]",
                item(CommandMenu.SELECTION, "sculptory.command.selection.hollow").tooltipText());
        assertEquals(Availability.no("sculptory.command.reason.no_permission", "region"),
                commands.run(EditorCommands.FILL), "run refuses too");
        assertEquals(List.of(), rig.session.sent());
    }

    @Test
    void overlayNaturalizeAndUpdateBlocksAreInTheSelectionMenuAndTheSearch() {
        assertEquals(Availability.no("sculptory.command.reason.needs_selection"),
                availability(EditorCommands.UPDATE_BLOCKS));
        selectBox();
        for (String id : List.of(EditorCommands.OVERLAY, EditorCommands.NATURALIZE)) {
            ui.windows().context().popups().closeAll();
            assertTrue(commands.run(id).enabled(), id);
            assertTrue(ui.windows().context().popups().isOpen(), id + " opens the Selection window's dialog");
            assertEquals(List.of(), rig.session.sent(), id + " sends nothing before the dialog is confirmed");
        }
        ui.windows().context().popups().closeAll();
        assertTrue(commands.run(EditorCommands.UPDATE_BLOCKS).enabled());
        assertInstanceOf(OpSpec.UpdateBlocks.class,
                assertInstanceOf(ToolAction.RunOp.class, rig.session.sent().get(0)).op(), "the Selection window's button");
        for (String id : List.of(EditorCommands.OVERLAY, EditorCommands.NATURALIZE, EditorCommands.UPDATE_BLOCKS)) {
            assertTrue(commands.searchEntries().stream().anyMatch(entry -> entry.id().equals(id)), id + " in Ctrl+K");
        }
    }

    @Test
    void historyAndClipboardCommandsSayWhyNot() {
        assertEquals(Availability.no("sculptory.history.nothing_to_undo"), availability(EditorCommands.UNDO));
        assertEquals(Availability.no("sculptory.history.nothing_to_redo"), availability(EditorCommands.REDO));
        assertEquals(Availability.no("sculptory.command.reason.nothing_copied"), availability(EditorCommands.PASTE));
        assertEquals(Availability.no("sculptory.command.reason.nothing_copied"),
                availability(EditorCommands.ROTATE_RIGHT));
        assertEquals(Availability.no("sculptory.command.reason.nothing_copied"),
                availability(EditorCommands.CLEAR_CLIPBOARD));
        assertEquals(Availability.no("sculptory.command.reason.needs_selection"), availability(EditorCommands.COPY));
        permissions(Perm.SCHEMATIC_EXPORT);
        assertEquals(Availability.no("sculptory.command.reason.no_permission", "schematic.export"),
                availability(EditorCommands.EXPORT_CLIPBOARD));
        assertTrue(availability(EditorCommands.CLOSE_EDITOR).enabled());
        assertTrue(availability(EditorCommands.HISTORY).enabled());
    }

    // ---- Running goes through the keys' and buttons' code ----

    @Test
    void runningACommandDoesWhatItsKeyOrButtonDoes() {
        selectBox();
        assertTrue(commands.run(EditorCommands.FILL).enabled());
        assertInstanceOf(OpSpec.Fill.class, assertInstanceOf(ToolAction.RunOp.class, rig.session.sent().get(0)).op(),
                "the Selection window's Fill");

        commands.run(EditorCommands.COPY);
        assertTrue(rig.session.clipboards().current().isPresent(), "Ctrl+C copied the selection");
        assertTrue(availability(EditorCommands.PASTE).enabled());

        commands.run(EditorCommands.ROTATE_LEFT);
        commands.run(EditorCommands.FLIP_FRONT_BACK);
        commands.run(EditorCommands.FLIP_UPSIDE_DOWN);
        assertEquals(Transform.rotation(-1).compose(new Transform(0, Mirror.Z)).compose(Transform.UPSIDE_DOWN),
                rig.clipboard.nextTransform(),
                "without a placement they turn the next paste, like the Clipboard window's buttons");

        commands.run(EditorCommands.DESELECT);
        assertTrue(rig.ctx.selection().isEmpty(), "Ctrl+D");

        strokeTwice();
        assertTrue(availability(EditorCommands.UNDO).enabled());
        assertFalse(rig.session.history().canRedo());
        commands.run(EditorCommands.UNDO);
        assertTrue(rig.session.history().canRedo(), "Ctrl+Z undid a stroke");

        commands.run(EditorCommands.HISTORY);
        assertTrue(ui.windows().isOpen(EditorWindows.HISTORY));
        commands.run(EditorCommands.HISTORY);
        assertTrue(ui.windows().isOpen(EditorWindows.HISTORY), "History… shows the window, it doesn't toggle");
        commands.run(EditorCommands.WINDOW_PREFIX + EditorWindows.HISTORY);
        assertFalse(ui.windows().isOpen(EditorWindows.HISTORY), "View > History toggles it, like H");

        commands.run(EditorCommands.AIM_AT_FLUIDS);
        assertTrue(rig.platform.aimAtFluids);
        assertTrue(commands.isChecked(commands.get(EditorCommands.AIM_AT_FLUIDS).orElseThrow()));

        commands.run(EditorCommands.KEY_SHEET);
        assertTrue(ui.isHelpOpen());
        ui.toggleHelp();

        rig.events.clear();
        commands.run(EditorCommands.CLOSE_EDITOR);
        assertTrue(rig.events.contains("hide"), "the editor closed: " + rig.events);
    }

    @Test
    void windowItemsAreCheckedWhileTheWindowShows() {
        Command selection = commands.get(EditorCommands.WINDOW_PREFIX + EditorWindows.SELECTION).orElseThrow();
        Command keys = commands.get(EditorCommands.WINDOW_PREFIX + EditorWindows.KEYS).orElseThrow();
        assertTrue(commands.isChecked(selection));
        assertFalse(commands.isChecked(keys));
        commands.run(EditorCommands.CHANGE_KEYS);
        assertTrue(commands.isChecked(keys));
        commands.run(EditorCommands.HIDE_WINDOWS);
        assertFalse(commands.isChecked(selection), "hidden with the rest");
        assertTrue(commands.isChecked(commands.get(EditorCommands.HIDE_WINDOWS).orElseThrow()));
        commands.run(EditorCommands.RESET_LAYOUT);
        assertTrue(ui.windows().isOpen(EditorWindows.KEYS), "Reset layout leaves which windows are open alone");
        assertFalse(ui.windows().window(EditorWindows.KEYS).orElseThrow().isPlaced(), "at its default place");
        assertTrue(commands.isChecked(selection), "and the hidden windows show again");
    }

    @Test
    void undoAnywayShowsWhileOfferedAndAcceptsTheOffer() {
        strokeTwice();
        rig.session.setStepConflicts(4);
        rig.controller.undo();
        List<String> edit = labels(CommandMenu.EDIT);
        assertTrue(edit.contains("sculptory.command.edit.undo_anyway"), edit.toString());
        assertFalse(edit.contains("sculptory.command.edit.redo_anyway"));
        assertTrue(commands.run(EditorCommands.UNDO_ANYWAY).enabled());
        assertEquals(1, rig.session.overwrites().size(), "the toast's button does the same");
        assertFalse(labels(CommandMenu.EDIT).contains("sculptory.command.edit.undo_anyway"));
    }

    @Test
    void commandsWaitingForAProviderShowOnceProvided() {
        CommandRegistry bare = new CommandRegistry(rig.keymap, Translator.KEYS);
        Command notifications = bare.register(Command.builder(EditorCommands.NOTIFICATIONS, CommandMenu.VIEW,
                "sculptory.command.view.notifications").provided().build());
        assertFalse(bare.isVisible(notifications));
        assertFalse(bare.run(notifications).enabled(), "hidden: it can't run");
        assertTrue(bare.searchEntries().stream().noneMatch(entry -> entry.id().equals(EditorCommands.NOTIFICATIONS)));
        boolean[] open = {false};
        bare.provide(EditorCommands.NOTIFICATIONS, () -> open[0] = !open[0], () -> open[0]);
        assertTrue(bare.isVisible(notifications));
        bare.run(notifications);
        assertTrue(open[0]);
        assertTrue(bare.isChecked(notifications));
    }

    @Test
    void theEditorUiProvidesNotificationsAndQuickStart() {
        assertTrue(labels(CommandMenu.VIEW).contains("sculptory.command.view.notifications"));
        assertFalse(item(CommandMenu.VIEW, "sculptory.command.view.notifications").isChecked());
        assertTrue(commands.run(EditorCommands.NOTIFICATIONS).enabled());
        assertTrue(ui.windows().isOpen(EditorWindows.NOTIFICATIONS));
        assertTrue(item(CommandMenu.VIEW, "sculptory.command.view.notifications").isChecked());
        assertTrue(commands.searchEntries().stream().anyMatch(entry -> entry.id().equals(EditorCommands.NOTIFICATIONS)));

        ui.quickStart().dismiss();
        assertTrue(commands.run(EditorCommands.QUICK_START).enabled());
        assertTrue(ui.quickStart().isShown(), "Help > Quick start shows the card even once dismissed");
        assertFalse(item(CommandMenu.HELP, "sculptory.command.help.quick_start").isChecked());
    }

    @Test
    void theLastEightCommandsRunAreRemembered() {
        List<String> run = List.of(EditorCommands.HISTORY, EditorCommands.KEY_SHEET, EditorCommands.KEY_SHEET,
                EditorCommands.RESET_LAYOUT, EditorCommands.AIM_AT_FLUIDS, EditorCommands.CHANGE_KEYS,
                EditorCommands.HIDE_WINDOWS, EditorCommands.HIDE_WINDOWS, EditorCommands.UI_SMALLER,
                EditorCommands.UI_LARGER, "view.ui_size.100", EditorCommands.FIND_COMMAND);
        for (String id : run) {
            commands.run(id);
        }
        assertEquals(List.of(EditorCommands.FIND_COMMAND, "view.ui_size.100", EditorCommands.UI_LARGER,
                EditorCommands.UI_SMALLER, EditorCommands.HIDE_WINDOWS, EditorCommands.CHANGE_KEYS,
                EditorCommands.AIM_AT_FLUIDS, EditorCommands.RESET_LAYOUT), commands.recent());
        commands.run(EditorCommands.FILL);
        assertEquals(EditorCommands.FIND_COMMAND, commands.recent().get(0), "a refused command is not remembered");
    }

    // ---- English ----

    @Test
    void everyCommandMenuReasonAndSearchTextHasEnglish() throws IOException {
        JsonObject lang;
        try (InputStream in = getClass().getResourceAsStream("/assets/sculptory/lang/en_us.json")) {
            assertNotNull(in);
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        List<String> keys = new ArrayList<>();
        for (CommandMenu menu : CommandMenu.values()) {
            keys.add(menu.titleKey());
        }
        for (Command command : commands.commands()) {
            Stream.concat(Stream.of(command), command.children().stream()).forEach(each -> {
                keys.add(each.labelKey());
                each.tooltipKey().ifPresent(keys::add);
            });
        }
        keys.addAll(List.of("sculptory.command.reason.needs_selection", "sculptory.command.reason.no_permission",
                "sculptory.command.reason.nothing_copied", "sculptory.command.reason.offline",
                "sculptory.command.reason.already_box", "sculptory.command.reason.no_tool",
                "sculptory.command.reason.history_busy", "sculptory.command.reason.unavailable",
                "sculptory.history.nothing_to_undo", "sculptory.history.nothing_to_redo",
                "sculptory.search.placeholder", "sculptory.search.no_matches", "sculptory.search.child",
                "sculptory.search.preset", "sculptory.search.category.setting",
                "sculptory.search.category.preset", KeyAction.COMMAND_SEARCH.labelKey()));
        List<String> missing = keys.stream().filter(key -> !lang.has(key)).toList();
        assertEquals(List.of(), missing);
        assertEquals(Optional.empty(), Stream.of("sculptory.topbar.windows", "sculptory.topbar.help.tooltip")
                .filter(lang::has).findFirst(), "the old Windows and ? buttons' texts are gone");
    }

    @Test
    void aShapeSelectionCanBeConvertedToABox() {
        rig.ctx.setSelectionRegion(new Region.Shape(new Box(new BlockPos(0, 0, 0), new BlockPos(4, 4, 4)),
                ShapeKind.ELLIPSOID, Facing.UP));
        assertTrue(availability(EditorCommands.CONVERT_TO_BOX).enabled());
        commands.run(EditorCommands.CONVERT_TO_BOX);
        assertInstanceOf(Region.Cuboid.class, rig.ctx.selectionRegion().orElseThrow());
    }
}
