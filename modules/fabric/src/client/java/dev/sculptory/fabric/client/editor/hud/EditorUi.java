package dev.sculptory.fabric.client.editor.hud;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.mask.EditMask;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.fabric.client.editor.EditorContext;
import dev.sculptory.fabric.client.editor.EditorController;
import dev.sculptory.fabric.client.editor.EditorPlatform;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.blocks.BlockChip;
import dev.sculptory.fabric.client.editor.blocks.BlockPicker;
import dev.sculptory.fabric.client.editor.clipboard.ClipboardActions;
import dev.sculptory.fabric.client.editor.commands.Availability;
import dev.sculptory.fabric.client.editor.commands.CommandMenu;
import dev.sculptory.fabric.client.editor.commands.CommandRegistry;
import dev.sculptory.fabric.client.editor.commands.EditorCommands;
import dev.sculptory.fabric.client.editor.commands.SearchEntry;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.InputRouter;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.mask.EditMaskModel;
import dev.sculptory.fabric.client.editor.mask.MaskChip;
import dev.sculptory.fabric.client.editor.mask.MaskPopup;
import dev.sculptory.fabric.client.editor.mask.MaskWindow;
import dev.sculptory.fabric.client.editor.mock.MockEditorSession;
import dev.sculptory.fabric.client.editor.presets.Presets;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.settings.form.SettingsForm;
import dev.sculptory.fabric.client.editor.tool.HudDraw;
import dev.sculptory.fabric.client.editor.tool.Selection;
import dev.sculptory.fabric.client.editor.tool.Tool;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tools.tinker.TinkerTool;
import dev.sculptory.fabric.client.editor.tool.ToolRegistry;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.tools.select.SelectionActions;
import dev.sculptory.fabric.client.editor.tools.select.SelectionWindow;
import dev.sculptory.fabric.client.editor.tutorial.EditorProbe;
import dev.sculptory.fabric.client.editor.tutorial.EditorTargets;
import dev.sculptory.fabric.client.editor.tutorial.LessonCard;
import dev.sculptory.fabric.client.editor.tutorial.LessonEdits;
import dev.sculptory.fabric.client.editor.tutorial.Lessons;
import dev.sculptory.fabric.client.editor.tutorial.StepTexts;
import dev.sculptory.fabric.client.editor.tutorial.Tutorial;
import dev.sculptory.fabric.client.editor.ui.FadedGraphics;
import dev.sculptory.fabric.client.editor.ui.Insets;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.PanelFade;
import dev.sculptory.fabric.client.editor.ui.PopupLayer;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiCursor;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import dev.sculptory.fabric.client.editor.ui.UiOpacity;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.Padding;
import dev.sculptory.fabric.client.editor.ui.layout.PriorityRow;
import dev.sculptory.fabric.client.editor.ui.layout.Row;
import dev.sculptory.fabric.client.editor.ui.layout.Spacer;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.IconButton;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.Menu;
import dev.sculptory.fabric.client.editor.ui.widget.MenuBar;
import dev.sculptory.fabric.client.editor.ui.widget.ScrollPane;
import dev.sculptory.fabric.client.editor.ui.window.Corner;
import dev.sculptory.fabric.client.editor.ui.window.Window;
import dev.sculptory.fabric.client.editor.ui.window.WindowManager;
import dev.sculptory.fabric.client.editor.ui.window.WindowSpec;
import dev.sculptory.fabric.client.editor.wiki.WikiLibrary;
import dev.sculptory.fabric.client.editor.wiki.WikiPage;
import dev.sculptory.fabric.client.editor.wiki.WikiPages;
import dev.sculptory.fabric.client.editor.wiki.WikiPictures;
import dev.sculptory.fabric.client.editor.wiki.WikiSource;
import dev.sculptory.fabric.client.editor.windows.ClipboardWindow;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import dev.sculptory.fabric.client.editor.windows.ExportDialog;
import dev.sculptory.fabric.client.editor.windows.HistoryWindow;
import dev.sculptory.fabric.client.editor.windows.KeysWindow;
import dev.sculptory.fabric.client.editor.windows.LibraryWindow;
import dev.sculptory.fabric.client.editor.windows.NotificationsWindow;
import dev.sculptory.fabric.client.editor.windows.OpacityPopup;
import dev.sculptory.fabric.client.editor.windows.SaveAssetDialog;
import dev.sculptory.fabric.client.editor.windows.ToolSettingsWindow;
import dev.sculptory.fabric.client.editor.windows.TransferBars;
import dev.sculptory.fabric.client.editor.windows.TinkerWindow;
import dev.sculptory.fabric.client.tinker.TinkerController;
import dev.sculptory.fabric.client.editor.windows.WikiWindow;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.JobTracker;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.SessionState;
import dev.sculptory.fabric.client.session.ToolAction;
import dev.sculptory.protocol.v2.Features;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.server.engine.Perm;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/**
 * Everything the editor draws on screen: the floating windows (Tool Settings, Selection, History,
 * Keys, Wiki), the top bar with its menu bar (the {@link EditorCommands}), the command search (Ctrl+K), the
 * tool palette, the hint line, the cursor readout, the job bars, toasts, the F1 help sheet and
 * confirmation dialogs. Implements the UI side of {@link InputRouter} and the
 * UI commands of {@link EditorController}. Pure apart from the item icons it is given.
 *
 * <p><b>UI size.</b> {@link #layout}, {@link #render} and the input methods take screen coordinates
 * (GUI-scaled pixels). Inside, everything is in UI units on a virtual screen of
 * {@code screen / factor} (see {@link UiScale}): pointer positions are mapped on the way in and
 * drawing is scaled on the way out. Only the active tool's world-anchored HUD (selection edge
 * lengths) is drawn in screen coordinates, since it is placed by projecting world points.
 */
public final class EditorUi implements InputRouter.Ui, EditorController.Ui, SelectionActions.Confirmer {
    /** What the UI needs beyond the editor state. */
    public record Services(Translator translator, BlockCatalog blocks, Function<String, ItemStack> itemIcons,
            Supplier<HelpSheet.VanillaKeys> vanillaKeys, String keysFile, Predicate<EditorKeymap> saveKeys,
            UiScale uiScale, UiOpacity uiOpacity) {
        public Services {
            Objects.requireNonNull(translator);
            Objects.requireNonNull(blocks);
            Objects.requireNonNull(itemIcons);
            Objects.requireNonNull(vanillaKeys);
            Objects.requireNonNull(keysFile);
            Objects.requireNonNull(saveKeys);
            Objects.requireNonNull(uiScale);
            Objects.requireNonNull(uiOpacity);
        }

        /** Services with the editor opaque (a {@link UiOpacity} of its own, at the defaults). */
        public Services(Translator translator, BlockCatalog blocks, Function<String, ItemStack> itemIcons,
                Supplier<HelpSheet.VanillaKeys> vanillaKeys, String keysFile, Predicate<EditorKeymap> saveKeys,
                UiScale uiScale) {
            this(translator, blocks, itemIcons, vanillaKeys, keysFile, saveKeys, uiScale, new UiOpacity());
        }
    }

    private enum Target { NONE, WINDOWS, HUD, OFFER, SHEET }

    /** The fading panel of the top bar, palette and hint line, which fade as one (View > Opacity…). */
    private static final Object HUD_PANEL = new Object();
    /** The fading panel of the toasts and the Undo anyway toast. */
    private static final Object TOAST_PANEL = new Object();
    /** The gap under the palette and between the palette and the hint line. */
    private static final int HUD_GAP = 3;
    /**
     * The top bar's "Aim at water and lava" button shows this item: a lily pad sits on the water's surface, where the
     * tools then aim (the Fluid tool has the water bucket).
     */
    private static final String FLUID_AIM_ICON = "minecraft:lily_pad";

    private final EditorContext ctx;
    private final EditorController controller;
    private final EditorPlatform platform;
    private final EditorKeymap keymap;
    private final Services services;
    private final Translator tr;
    private final TextMeasure text;
    private final Theme theme = Theme.DARK;
    private final UiScale scale;
    /** How opaque the windows, the HUD panels and the toasts are this frame (View > Opacity…). */
    private final PanelFade panelFade;
    /** This frame's opacity of the top bar, palette and hint line, and of the toasts. */
    private float hudAlpha = 1.0F;
    private float toastAlpha = 1.0F;
    private final WindowManager windows;
    private final HudLayer hud;
    private final ToastStack toasts;
    private final Target[] targets = new Target[8];

    private final BlockChip blockChip;
    /** The global mask's chip beside the active block, and its window. */
    private final MaskChip maskChip;
    private final MaskWindow maskWindow;
    private final Button undoButton;
    private final Button redoButton;
    private final Button uiSizeButton;
    /** File · Edit · Selection · Tools · View · Help, from {@link #commands}. */
    private final MenuBar menuBar;
    private final CommandRegistry commands;
    private final CommandSearch search;
    /** "Aim at water and lava": selected (highlighted) while on. */
    private final IconButton fluidAimButton;
    private final Label flyLabel = Label.dim("");
    private final StatusDot status = new StatusDot();
    private final List<PaletteButton> palette = new ArrayList<>();
    private final Panel paletteNode;
    private final HintLine hintLine = new HintLine();
    /** The top bar's items, some of which give way when it is short of room. */
    private final PriorityRow topBarRow;
    /** The top bar and the hint line: the window area lies between them. */
    private final Node topBarNode;
    private final Node hintNode;
    private final JobBars jobs;
    private final TransferBars hudTransfers;
    private final Panel hudTransfersPanel;
    private final ToolSettingsWindow toolSettings;
    private final SelectionWindow selectionWindow;
    private final HistoryWindow historyWindow;
    private final KeysWindow keysWindow;
    /**
     * Undo anyway, at the top of the toast column (the toasts move down under it). Its own layer, drawn with the toasts
     * above the windows (Tool Settings is docked there) and asked first for the mouse.
     */
    private final HistoryOfferToast historyOffer;
    /** The HUD layer above the windows: the Undo anyway toast and the quick start card. */
    private final HudLayer offerLayer;
    private final ClipboardWindow clipboardWindow;
    private final LibraryWindow libraryWindow;
    /** The F1 key sheet. */
    private final KeySheet keySheet;
    /** The quick start card, in {@link #offerLayer} above the windows. */
    private final QuickStart quickStart;
    private final NotificationsWindow notificationsWindow;
    private final WikiWindow wikiWindow;
    /** The Tinker panel, opened by the Tinker tool's click. */
    private final TinkerWindow tinkerWindow;
    // ---- Tutorial ----
    /** Tutorial mode: the lesson card (in {@link #offerLayer}), the Tutorial window and the step highlight. */
    private final Tutorial tutorial;
    /** What the lessons' conditions read, updated each frame. */
    private final EditorProbe tutorialProbe;
    // ---- end Tutorial ----

    private boolean editing;
    private boolean passiveFrame;
    /** The screen in GUI-scaled pixels. */
    private int screenWidth;
    private int screenHeight;
    /** The virtual screen the UI lays out on, in UI units. */
    private int width;
    private int height;
    /** The last "UI size" toast, replaced by the next one. */
    private String uiSizeToast;

    public EditorUi(EditorContext ctx, EditorController controller, EditorPlatform platform, SelectionActions actions,
            TextMeasure text, ToastStack toasts, Services services) {
        this.ctx = Objects.requireNonNull(ctx);
        this.controller = Objects.requireNonNull(controller);
        this.platform = Objects.requireNonNull(platform);
        this.keymap = controller.keymap();
        this.services = Objects.requireNonNull(services);
        this.tr = services.translator();
        this.text = Objects.requireNonNull(text);
        this.toasts = Objects.requireNonNull(toasts);
        this.scale = services.uiScale();
        this.panelFade = new PanelFade(services.uiOpacity());
        this.windows = new WindowManager(text, theme, key -> tr.translate(key));
        windows.setPanelFade(panelFade);
        this.hud = new HudLayer(text, theme);
        Arrays.fill(targets, Target.NONE);

        // Commands, the menu bar and the command search
        commands = EditorCommands.build(ctx, controller, actions, commandHost(), tr);
        List<MenuBar.Entry> menus = new ArrayList<>();
        for (CommandMenu menu : CommandMenu.values()) {
            menus.add(new MenuBar.Entry(tr.translate(menu.titleKey()), () -> commands.menuItems(menu)));
        }
        menuBar = new MenuBar(menus, windows::context);
        search = new CommandSearch(windows::context, this::searchEntries, commands::recent, ctx::notify, tr);

        // Top bar
        blockChip = new BlockChip(services.blocks(), ctx.activeBlock(), null);
        blockChip.setOnClick(() -> BlockPicker.open(windows.context(), blockChip.bounds(), services.blocks(), tr,
                ctx::setActiveBlock));
        blockChip.setTooltip(tr.translate("sculptory.topbar.block.tooltip"));
        EditMaskModel mask = EditMaskModel.global();
        maskChip = new MaskChip(mask, services.blocks(), tr, () -> keymap.display(KeyAction.TOGGLE_MASK),
                () -> toggleWindow(EditorWindows.MASK));
        // An inside rule names the selection as it is when an edit is made.
        mask.setSelectionSource(ctx::selectionRegion);
        ctx.onSelectionChanged(mask::selectionChanged);
        undoButton = flat(tr.translate("sculptory.topbar.undo"), controller::undo);
        redoButton = flat(tr.translate("sculptory.topbar.redo"), controller::redo);
        keepWhole(undoButton);
        keepWhole(redoButton);
        // The chip keeps its icon and caret; its block name gives way.
        blockChip.setMinSize(BlockChip.HEIGHT + 16, 0);
        uiSizeButton = flat(uiSizeLabel(), null);
        uiSizeButton.setOnClick(() -> openUiSizeMenu(uiSizeButton.bounds()));
        refreshKeyTooltips();
        fluidAimButton = new IconButton(services.itemIcons().apply(FLUID_AIM_ICON),
                () -> controller.setAimAtFluids(!controller.aimsAtFluids()));
        // As high as the block chip, so the bar keeps its height.
        fluidAimButton.setFixedSize(BlockChip.HEIGHT, BlockChip.HEIGHT);
        Row undoRedo = Row.of(undoButton, redoButton);
        undoRedo.setGap(6);
        PriorityRow bar = PriorityRow.of(menuBar, blockChip, maskChip, undoRedo, Spacer.flexible(), fluidAimButton, flyLabel,
                status, uiSizeButton);
        bar.setGap(6);
        // Short of room (UI 100% on a small screen) whole items give way, each also in the menus or keys: the UI size,
        // then the fly speed, the water-aim toggle, Undo and Redo, last the Mask chip (Ctrl+M still works; toggling toasts).
        // The menus, the block's name and the connection stay.
        bar.setDropOrder(uiSizeButton, flyLabel, fluidAimButton, undoRedo, maskChip);
        topBarRow = bar;
        Panel topBar = new Panel(bar, new Insets(3, 2, 3, 2), theme.topBarBackground, 0);
        hud.add(topBar, (size, w, h) -> new Rect(0, 0, w, size.height()), this::showsEditorHud, () -> hudAlpha);

        // Palette (in the Tools menu's groups, a wider gap between them) and hint line
        Row slots = new Row();
        slots.setGap(theme.paletteSlotGap);
        for (int slot = 1; slot <= ToolRegistry.PALETTE_SLOTS; slot++) {
            int index = slot;
            if (EditorCommands.TOOL_GROUP_STARTS.contains(slot)) {
                slots.add(new Spacer(Math.max(0, theme.paletteGroupGap - 2 * theme.paletteSlotGap), 0));
            }
            PaletteButton button = new PaletteButton(slot, null, () -> controller.selectSlot(index));
            palette.add(button);
            slots.add(button);
        }
        paletteNode = new Panel(slots, Insets.all(2), theme.paletteBackground, 0);
        hud.add(paletteNode, (size, w, h) -> new Rect((w - size.width()) / 2, h - size.height() - HUD_GAP,
                size.width(), size.height()), this::showsEditorHud, () -> hudAlpha);
        Panel hint = new Panel(hintLine, new Insets(6, 2, 6, 2), theme.hintBackground, 0);
        hud.add(hint, (size, w, h) -> {
            // Measured for the room it gets, 4 in from each edge: as wide as the whole hints it shows there.
            int widthLimit = Math.min(hint.measure(hud.context(), w - 8).width(), w - 8);
            int top = paletteNode.bounds().y() - size.height() - HUD_GAP;
            return new Rect((w - widthLimit) / 2, top, widthLimit, size.height());
        }, () -> showsEditorHud() && !hintLine.isEmpty(), () -> hudAlpha);

        // Job bars
        jobs = new JobBars(tr, this::cancelJob);
        hud.add(jobs.node(), (size, w, h) -> {
            int x = w - size.width() - 4;
            int y = h - size.height() - 4;
            if (showsEditorHud() && x < paletteNode.bounds().right() + 4) {
                y = paletteNode.bounds().y() - size.height() - 4;
            }
            return new Rect(x, y, size.width(), size.height());
        }, () -> !jobs.isEmpty());

        // Transfers (previews, exports, uploads), above the job bars
        hudTransfers = new TransferBars(tr);
        Node transferColumn = hudTransfers.node();
        transferColumn.setFixedWidth(JobBars.WIDTH);
        hudTransfersPanel = new Panel(transferColumn, Insets.all(2), 0xC0101216, 0);
        hud.add(hudTransfersPanel, (size, w, h) -> {
            int x = w - size.width() - 4;
            int bottom = jobs.isEmpty() ? h - 4 : jobs.node().bounds().y() - 2;
            if (jobs.isEmpty() && showsEditorHud() && x < paletteNode.bounds().right() + 4) {
                bottom = paletteNode.bounds().y() - 4;
            }
            return new Rect(x, bottom - size.height(), size.width(), size.height());
        }, () -> !hudTransfers.isEmpty());

        // Undo anyway, above the toasts
        historyOffer = new HistoryOfferToast(tr, ctx::session);
        offerLayer = new HudLayer(text, theme);
        offerLayer.add(historyOffer.node(), (size, w, h) -> {
            int x = toastColumnRight() - size.width();
            int y = ToastStack.clearTop(topBarBottom() + 4, x, size.width(), size.height(), toastAvoid());
            return new Rect(x, y, size.width(), size.height());
        }, () -> showsEditorHud() && historyOffer.isShown(), () -> toastAlpha);

        topBarNode = topBar;
        hintNode = hint;

        // The quick start card, in the middle of the screen above the windows; the F1 key sheet
        quickStart = new QuickStart(text, theme, keymap, tr, services.vanillaKeys());
        offerLayer.add(quickStart.node(), (size, w, h) -> {
            // As wide as its longest line; on a screen narrower than that its lines wrap, so it is measured again.
            int cardWidth = Math.min(size.width(), w - 2 * theme.sheetMargin);
            int height = cardWidth < size.width()
                    ? quickStart.node().measure(offerLayer.context(), cardWidth).height() : size.height();
            int cardHeight = Math.min(height, h - 2 * theme.sheetMargin);
            return new Rect((w - cardWidth) / 2, (h - cardHeight) / 2, cardWidth, cardHeight);
        }, () -> showsEditorHud() && quickStart.isShown());
        keySheet = new KeySheet(text, theme, keymap, tr, services.vanillaKeys(), this::toolName,
                () -> showWindow(EditorWindows.KEYS));

        // Windows
        SettingsForm.Services formServices = formServices();
        windows.setDefaultLayout(new EditorWindowLayout(this::toastColumnArea));
        Optional<ClipboardActions> clipboard = controller.clipboard();
        toolSettings = new ToolSettingsWindow(ctx, formServices, windows::context);
        selectionWindow = new SelectionWindow(actions, selectionHost(), tr, services.blocks(), clipboard.orElse(null));
        historyWindow = new HistoryWindow(ctx::session, controller::undo, controller::redo,
                keymap.display(KeyAction.UNDO), keymap.display(KeyAction.REDO), tr);
        clipboardWindow = clipboard.map(a -> new ClipboardWindow(ctx::session, a, windows::context, tr)).orElse(null);
        libraryWindow = clipboard.map(a -> new LibraryWindow(ctx::session, a, windows::context, tr,
                platform::onlinePlayerNames)).orElse(null);
        windows.register(WindowSpec.builder(EditorWindows.TOOL_SETTINGS, EditorWindows.titleKey(EditorWindows.TOOL_SETTINGS),
                        toolSettings::build)
                .anchor(Corner.TOP_RIGHT, 4, EditorWindowLayout.GAP).size(170, 200).minSize(120, 60).build());
        windows.register(WindowSpec.builder(EditorWindows.SELECTION, EditorWindows.titleKey(EditorWindows.SELECTION),
                        selectionWindow::node)
                .anchor(Corner.TOP_LEFT, 4, EditorWindowLayout.GAP).size(190, 250).minSize(150, 80).build());
        if (clipboardWindow != null) {
            windows.register(WindowSpec.builder(EditorWindows.CLIPBOARD, EditorWindows.titleKey(EditorWindows.CLIPBOARD),
                            clipboardWindow::node)
                    .anchor(Corner.BOTTOM_LEFT, 4, 100).size(180, 150).minSize(140, 70).openByDefault(false).build());
            windows.register(WindowSpec.builder(EditorWindows.LIBRARY, EditorWindows.titleKey(EditorWindows.LIBRARY),
                            libraryWindow::node)
                    .anchor(Corner.TOP_RIGHT, 180, EditorWindowLayout.GAP).size(220, 260).minSize(160, 120)
                    .openByDefault(false).build());
        }
        windows.register(WindowSpec.builder(EditorWindows.HISTORY, EditorWindows.titleKey(EditorWindows.HISTORY),
                        historyWindow::node)
                .anchor(Corner.BOTTOM_LEFT, 4, 4).size(190, 210).minSize(140, 90).openByDefault(false).build());
        keysWindow = new KeysWindow(keymap, tr, services.keysFile(), keysHost());
        windows.register(WindowSpec.builder(EditorWindows.KEYS, EditorWindows.titleKey(EditorWindows.KEYS),
                        keysWindow::node)
                .anchor(Corner.TOP_LEFT, 198, EditorWindowLayout.GAP).size(250, 260).minSize(140, 80)
                .openByDefault(false).build());
        notificationsWindow = new NotificationsWindow(toasts.log(), tr);
        windows.register(WindowSpec.builder(EditorWindows.NOTIFICATIONS, EditorWindows.titleKey(EditorWindows.NOTIFICATIONS),
                        notificationsWindow::node)
                .anchor(Corner.BOTTOM_LEFT, EditorWindowLayout.GAP, EditorWindowLayout.GAP).size(190, 170).minSize(140, 80)
                .openByDefault(false).build());
        // Each UI size has its own window arrangement. The windows as registered are the one of the size in use (the
        // editor then restores the saved ones); a size change, however it is made, switches to that size's.
        windows.arrangeForUiSize(scale.percent());
        scale.addListener(windows::arrangeForUiSize);

        // The commands the menu bar left for this part of the editor: View > Notifications, Help > Quick start
        commands.provide(EditorCommands.NOTIFICATIONS, () -> toggleWindow(EditorWindows.NOTIFICATIONS),
                () -> windows.isOpen(EditorWindows.NOTIFICATIONS) && !windows.isAllHidden());
        commands.provide(EditorCommands.QUICK_START, this::showQuickStart, null);

        // The Mask window: opened by the Mask chip, closed by default.
        maskWindow = new MaskWindow(mask, windows::context, services.blocks(), tr,
                () -> keymap.display(KeyAction.TOGGLE_MASK),
                () -> ctx.session().map(s -> s.capabilities().features().has(Features.EDIT_MASK)).orElse(false));
        windows.register(WindowSpec.builder(EditorWindows.MASK, EditorWindows.titleKey(EditorWindows.MASK),
                        maskWindow::node)
                .anchor(Corner.TOP_LEFT, 198, EditorWindowLayout.GAP).size(MaskWindow.WIDTH, MaskWindow.HEIGHT)
                .minSize(160, 100).openByDefault(false).build());

        // The Tinker panel: opened by the Tinker tool's click, closed by default.
        tinkerWindow = new TinkerWindow(tinkerServices(), windows::context, tr);
        windows.register(WindowSpec.builder(EditorWindows.TINKER, EditorWindows.titleKey(EditorWindows.TINKER),
                        tinkerWindow::node)
                .anchor(Corner.TOP_RIGHT, 180, EditorWindowLayout.GAP).size(TinkerWindow.WIDTH, TinkerWindow.HEIGHT)
                .minSize(150, 100).openByDefault(false).build());

        // The wiki: its window (View > Wiki), Help > Wiki and the ? beside the tool's name in Tool Settings
        wikiWindow = new WikiWindow(tr);
        windows.register(WindowSpec.builder(EditorWindows.WIKI, EditorWindows.titleKey(EditorWindows.WIKI),
                        wikiWindow::node)
                .anchor(Corner.TOP_LEFT, 198, EditorWindowLayout.GAP).size(WikiWindow.WIDTH, WikiWindow.HEIGHT)
                .minSize(200, 120)
                .openByDefault(false).build());
        commands.provide(EditorCommands.VIEW_WIKI, () -> toggleWindow(EditorWindows.WIKI),
                () -> windows.isOpen(EditorWindows.WIKI) && !windows.isAllHidden());
        commands.provide(EditorCommands.WIKI, () -> openWiki(null, null), null);
        toolSettings.setHelp(tool -> {
            WikiPages.Target target = WikiPages.forTool(tool);
            openWiki(target.pageId(), target.anchor());
        });

        // ---- Tutorial ----
        LessonEdits lessonEdits = new LessonEdits();
        tutorialProbe = new EditorProbe(this, ctx, controller, platform, lessonEdits);
        tutorial = new Tutorial(Lessons.all(), tutorialProbe, lessonEdits, new EditorTargets(this, ctx, theme),
                tutorialHost(), new StepTexts(tr, keymap, services.vanillaKeys()), tr, theme);
        // Learn more opens the step's page in the Wiki window.
        tutorial.setWiki(this::openWiki);
        windows.register(WindowSpec.builder(EditorWindows.TUTORIAL, EditorWindows.titleKey(EditorWindows.TUTORIAL),
                        tutorial.window()::node)
                .anchor(Corner.TOP_LEFT, 198, EditorWindowLayout.GAP).size(250, 280).minSize(180, 100)
                .openByDefault(false).build());
        // The lesson card: at the top centre under the top bar, above the windows.
        offerLayer.add(tutorial.card().node(), this::placeLessonCard, () -> showsEditorHud() && tutorial.cardShown());
        commands.provide(EditorCommands.TUTORIAL, tutorial::open, null);
        commands.provide(EditorCommands.TUTORIAL_WINDOW, () -> toggleWindow(EditorWindows.TUTORIAL),
                () -> windows.isOpen(EditorWindows.TUTORIAL) && !windows.isAllHidden());
        quickStart.setOnStartTutorial(tutorial::open);
        // ---- end Tutorial ----
    }

    // ---- Tutorial ----

    /** Tutorial mode: its runner, lesson card and window. */
    public Tutorial tutorial() {
        return tutorial;
    }

    private Tutorial.Host tutorialHost() {
        return new Tutorial.Host() {
            @Override
            public void showWindow() {
                keySheet.close();
                EditorUi.this.showWindow(EditorWindows.TUTORIAL);
            }

            @Override
            public void closeWindow() {
                if (windows.isOpen(EditorWindows.TUTORIAL)) {
                    windows.close(EditorWindows.TUTORIAL);
                }
            }

            @Override
            public Optional<EditorSession> session() {
                return ctx.session();
            }

            @Override
            public void undo() {
                controller.undo();
            }

            @Override
            public void notify(Notice notice) {
                ctx.notify(notice);
            }
        };
    }

    /**
     * Where the lesson card goes: centred, as wide as {@link LessonCard#WIDTH} (less on a narrow screen), under the top
     * bar or just above the hint line, whichever covers less: never the step's highlighted target where one of them
     * leaves it clear, then as little of the windows' title bars and then of the windows as can be (the top when
     * even). A menu, the command search or a dialog opened at one of them leaves the other; the card comes back when
     * the popup closes.
     */
    private Rect placeLessonCard(Size measured, int w, int h) {
        LessonCard card = tutorial.card();
        card.fitWidth(w - 2 * theme.sheetMargin);
        Size size = card.node().measure(offerLayer.context(), w);
        int x = (w - size.width()) / 2;
        Rect top = new Rect(x, topBarBottom() + 4, size.width(), size.height());
        int bottom = (hintLine.isEmpty() ? paletteNode.bounds().y() : hintNode.bounds().y()) - 4;
        Rect low = new Rect(x, Math.max(topBarBottom() + 4, bottom - size.height()), size.width(), size.height());
        if (overlapsPopup(top) != overlapsPopup(low)) {
            return overlapsPopup(top) ? low : top;
        }
        return lessonCardCost(low) < lessonCardCost(top) ? low : top;
    }

    /** How much the lesson card at {@code rect} would cover: the step's target, the windows' title bars, the windows. */
    private long lessonCardCost(Rect rect) {
        long cost = 0;
        Optional<Rect> target = tutorial.highlight();
        if (target.isPresent() && target.get().intersects(rect)) {
            cost += 1L << 40;
        }
        if (windows.isAllHidden()) {
            return cost;
        }
        for (Window window : windows.windows()) {
            if (window.isOpen()) {
                cost += 64L * area(rect.intersect(window.titleBarRect(theme))) + area(rect.intersect(window.rect()));
            }
        }
        return cost;
    }

    private static long area(Rect rect) {
        return rect.isEmpty() ? 0 : (long) rect.width() * rect.height();
    }

    private boolean overlapsPopup(Rect rect) {
        return windows.context().popups().popups().stream().anyMatch(popup -> popup.rect().intersects(rect));
    }

    /** A top bar item's place while the bar shows it (a step points at it). */
    public Optional<Rect> topBarBounds(dev.sculptory.fabric.client.editor.tutorial.Target.Item item) {
        Node node = switch (item) {
            case ACTIVE_BLOCK -> blockChip;
            case UNDO -> undoButton;
            case REDO -> redoButton;
            case FLUID_AIM -> fluidAimButton;
            case FLY_SPEED -> flyLabel;
            case UI_SIZE -> uiSizeButton;
        };
        return showsEditorHud() && node.isShown() ? Optional.of(node.bounds()) : Optional.empty();
    }

    // ---- end Tutorial ----

    /** The name of the tool in palette slot {@code slot}, if there is one. */
    private Optional<String> toolName(int slot) {
        return ctx.tools().slot(slot).map(tool -> tr.translate(tool.descriptor().nameKey()));
    }

    /** The Keys window saves through the editor's key store, toasts through its toasts and asks with its dialog. */
    private KeysWindow.Host keysHost() {
        return new KeysWindow.Host() {
            @Override
            public boolean save(EditorKeymap keymap) {
                return services.saveKeys().test(keymap);
            }

            @Override
            public void toast(String message) {
                toasts.show(Notice.Level.ERROR, message);
            }

            @Override
            public void confirm(String message, Runnable onConfirm) {
                EditorUi.this.confirm(message, onConfirm);
            }

            @Override
            public void changed() {
                refreshKeyTooltips();
            }

            @Override
            public Optional<String> toolName(int slot) {
                return ctx.tools().slot(slot).map(tool -> tr.translate(tool.descriptor().nameKey()));
            }
        };
    }

    /** The top bar tooltips that name keys, from the live keymap. */
    private void refreshKeyTooltips() {
        flyLabel.setTooltip(tr.translate("sculptory.topbar.fly.tooltip", keymap.display(KeyAction.FLY_SPEED)));
        uiSizeButton.setTooltip(tr.translate("sculptory.topbar.ui_size.tooltip",
                keymap.displayFirst(KeyAction.UI_SMALLER), keymap.displayFirst(KeyAction.UI_LARGER),
                keymap.displayFirst(KeyAction.UI_RESET)));
    }

    public KeysWindow keysWindow() {
        return keysWindow;
    }

    public WindowManager windows() {
        return windows;
    }

    public HudLayer hud() {
        return hud;
    }

    public ToolSettingsWindow toolSettings() {
        return toolSettings;
    }

    public boolean isHelpOpen() {
        return keySheet.isOpen();
    }

    /** The F1 key sheet. */
    public KeySheet keySheet() {
        return keySheet;
    }

    /** The quick start card. */
    public QuickStart quickStart() {
        return quickStart;
    }

    /** Where the quick start card keeps "seen" (the editor UI settings file). */
    public void setQuickStartFlag(QuickStart.Flag flag) {
        quickStart.setFlag(flag);
    }

    /** The editor opened: the quick start card shows unless it was dismissed before. */
    public void showQuickStartIfNew() {
        if (quickStart.showIfNew()) {
            keySheet.close();
        }
    }

    /** Help > Quick start: the card, whether it was dismissed before or not. */
    public void showQuickStart() {
        keySheet.close();
        quickStart.show();
    }

    /** The Notifications window. */
    public NotificationsWindow notificationsWindow() {
        return notificationsWindow;
    }

    /** The hint line above the palette. */
    public HintLine hintLine() {
        return hintLine;
    }

    /** Palette slot {@code slot}'s button (1-based), if the palette has it. */
    public Optional<PaletteButton> paletteButton(int slot) {
        return palette.stream().filter(button -> button.slot() == slot).findFirst();
    }

    /** Full editor UI (true) or only job bars and toasts over normal gameplay (false). */
    public void setEditing(boolean editing) {
        if (this.editing == editing) {
            return;
        }
        this.editing = editing;
        // Tutorial: the lesson waits while the editor is closed, and a step may ask the player to leave and come back.
        tutorial.editorShown(editing);
        if (!editing) {
            keySheet.close();
            windows.context().popups().closeAll();
            windows.context().clearFocus();
            Arrays.fill(targets, Target.NONE);
            wikiWindow.closed();
        }
    }

    public boolean isEditing() {
        return editing;
    }

    /** The top bar, palette and hint line show only in the editor itself, not over chat or gameplay. */
    private boolean showsEditorHud() {
        return editing && !passiveFrame;
    }

    // ---- Layout and drawing ----

    /**
     * Lays the UI out for a screen of the given GUI-scaled size, on a virtual screen of that size
     * divided by the UI size; windows keep their anchors and are clamped on screen. The windows are in the UI size's
     * own arrangement ({@link WindowManager#arrangeForUiSize}; a size change switches at once, this makes sure).
     */
    public void layout(int screenWidth, int screenHeight) {
        setScreen(screenWidth, screenHeight);
        windows.arrangeForUiSize(scale.percent());
        windows.setResizeReach(scale.reach(theme.resizeBorder), scale.reach(theme.resizeCornerSize));
        hud.layout(width, height);
        windows.setReserved(reservedEdges());
        windows.layout(width, height);
        offerLayer.layout(width, height);
    }

    /**
     * The screen edges windows keep clear of: the top bar (its laid-out height), and at the bottom the hint line and
     * palette with the gaps under them. The hint line's height is kept while it is empty too, so windows don't move
     * when a tool without hints is picked.
     */
    private Insets reservedEdges() {
        UiContext hudCtx = hud.context();
        int bottom = HUD_GAP + paletteNode.measure(hudCtx, width).height() + HUD_GAP
                + hintNode.measure(hudCtx, width).height();
        return new Insets(0, topBarBottom(), 0, bottom);
    }

    /**
     * The right edge of the toast column (the Undo anyway offer and the toasts under it), which starts under the top
     * bar: 4 in from the screen's right edge, or left of the open windows it would cover there (Tool Settings at the
     * top right, say), or back at the right edge when there is no room left of them. The column's height is the
     * last frame's.
     */
    private int toastColumnRight() {
        int screenRight = width - 4;
        if (windows.isAllHidden()) {
            return screenRight;
        }
        int top = topBarBottom() + 4;
        int bottom = toastColumnBottom(top);
        int right = screenRight;
        boolean moved = true;
        while (moved) {
            moved = false;
            Rect column = Rect.ofEdges(right - ToastStack.WIDTH, top, right, Math.max(top + 1, bottom));
            for (Window window : windows.windows()) {
                if (window.isOpen() && window.rect().intersects(column)) {
                    right = Math.min(right, window.rect().x() - 4);
                    moved = true;
                }
            }
            if (right - ToastStack.WIDTH < 4) {
                return screenRight;
            }
        }
        return right;
    }

    /**
     * Where the toast column is drawn now (the Undo anyway offer and the toasts under it; empty while it shows
     * nothing), which a window opening clear of the others keeps off where it can.
     */
    private Rect toastColumnArea() {
        int top = topBarBottom() + 4;
        int right = toastColumnRight();
        return Rect.ofEdges(right - ToastStack.WIDTH, top, right, toastColumnBottom(top));
    }

    /** The bottom of the toast column starting at {@code top}: the offer's and the last frame's toasts. */
    private int toastColumnBottom(int top) {
        List<Rect> drawn = toasts.drawnRects();
        int toastsBottom = drawn.isEmpty() ? top : drawn.get(drawn.size() - 1).bottom();
        Rect offer = historyOffer.node().bounds();
        int offerBottom = historyOffer.height() > 0 && !offer.isEmpty() ? offer.bottom() + 3 : top;
        return Math.max(Math.max(top + historyOffer.height(), offerBottom), toastsBottom);
    }

    /** Where the toasts and the Undo anyway offer are this frame (after the toasts are placed). */
    private List<Rect> toastRects() {
        List<Rect> rects = new ArrayList<>(toasts.drawnRects());
        Rect offer = historyOffer.node().bounds();
        if (showsEditorHud() && historyOffer.isShown() && !offer.isEmpty()) {
            rects.add(offer);
        }
        return rects;
    }

    /** Where the first toast goes: under the Undo anyway offer while it shows, else under the top bar. */
    private int toastsTop() {
        Rect offer = historyOffer.node().bounds();
        boolean offerShown = showsEditorHud() && historyOffer.isShown() && historyOffer.height() > 0
                && !offer.isEmpty();
        return offerShown ? offer.bottom() + 3 : topBarBottom() + 4;
    }

    /**
     * What the toast column keeps clear of: the title bars of the open windows (and two units under them), so a window
     * under the toasts can still be moved, collapsed or closed. Nothing while the windows are hidden.
     */
    private List<Rect> toastAvoid() {
        if (windows.isAllHidden()) {
            return List.of();
        }
        List<Rect> avoid = new ArrayList<>();
        for (Window window : windows.windows()) {
            if (window.isOpen()) {
                Rect title = window.titleBarRect(theme);
                avoid.add(new Rect(title.x(), title.y(), title.width(), title.height() + 2));
            }
        }
        return avoid;
    }

    /** The bottom of the top bar, from its layout (measured if it isn't laid out). */
    private int topBarBottom() {
        return topBarNode.isVisible() && !topBarNode.bounds().isEmpty()
                ? topBarNode.bounds().bottom() : topBarNode.measure(hud.context(), width).height();
    }

    private void setScreen(int screenWidth, int screenHeight) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.width = scale.uiLength(screenWidth);
        this.height = scale.uiLength(screenHeight);
    }

    /** The virtual screen width in UI units (the screen width at 100%). */
    public int uiWidth() {
        return width;
    }

    /** The virtual screen height in UI units. */
    public int uiHeight() {
        return height;
    }

    public UiScale uiScale() {
        return scale;
    }

    /** View > Opacity…: the Panels and Tool outlines opacity the editor draws with. */
    public UiOpacity uiOpacity() {
        return services.uiOpacity();
    }

    /** Draws the editor UI; call every frame while the editor is active. The mouse is in screen coordinates. */
    public void render(UiGraphics g, double mouseX, double mouseY, long nowMs, boolean looking) {
        // The wiki draws its pages a little larger at small UI sizes, on whole screen pixels (g is not scaled yet).
        wikiWindow.setDisplay(g.pixelScale() * scale.factor(), scale.factor());
        if (scale.uiLength(screenWidth) != width || scale.uiLength(screenHeight) != height
                || scale.percent() != windows.uiSize()) {
            layout(screenWidth, screenHeight);
        }
        refresh();
        // Tutorial: the step's condition is checked once per frame.
        tutorialProbe.frame(looking);
        tutorial.frame(nowMs);
        renderToolHud(g);
        double x = scale.toUi(mouseX);
        double y = scale.toUi(mouseY);
        scale.draw(g, () -> renderScaled(g, x, y, nowMs, looking));
    }

    /** Everything but the tool's world-anchored HUD, in UI units. */
    private void renderScaled(UiGraphics g, double mouseX, double mouseY, long nowMs, boolean looking) {
        hud.layout(width, height);
        windows.setReserved(reservedEdges());
        if (!looking && !overUi(mouseX, mouseY)) {
            renderReadout(g, mouseX, mouseY);
        }
        // View > Opacity…: the top bar, palette and hint line fade as one panel, the toasts as another.
        hudAlpha = panelFade.alpha(HUD_PANEL, isHudEngaged(mouseX, mouseY), nowMs);
        toastAlpha = panelFade.alpha(TOAST_PANEL, isOverToasts(mouseX, mouseY), nowMs);
        windows.prepare(mouseX, mouseY, nowMs);
        // What is drawn over the windows is placed first: a see-through panel hides what is under it within the UI
        // (the world still shows through it), so the HUD and the windows are left out under the faded windows and
        // toasts above them.
        offerLayer.layout(width, height);
        toasts.place(text, theme, toastColumnRight(), toastsTop(), windows.workArea().bottom(), toastAvoid());
        List<Rect> toastCovers = toastAlpha < 1.0F ? toastRects() : List.of();
        List<Rect> hudCovers = new ArrayList<>(windows.fadedRects());
        hudCovers.addAll(toastCovers);
        hud.render(g, nowMs, hudCovers);
        g.pushLayer(200);
        windows.draw(g, toastCovers);
        g.popLayer();
        g.pushLayer(3000);
        offerLayer.render(g, nowMs);
        toasts.draw(FadedGraphics.of(g, theme, toastAlpha), text, theme);
        g.popLayer();
        // Tutorial: the step's highlight, above the windows, menus and the lesson card, under the key sheet.
        g.pushLayer(3100);
        tutorial.renderHighlight(g, nowMs);
        g.popLayer();
        if (keySheet.isOpen()) {
            g.pushLayer(3200);
            keySheet.layout(width, height, topBarBottom());
            keySheet.render(g, nowMs);
            g.popLayer();
        }
        g.pushLayer(3400);
        hud.renderTooltip(g, mouseX, mouseY);
        offerLayer.renderTooltip(g, mouseX, mouseY);
        keySheet.renderTooltip(g, mouseX, mouseY);
        g.popLayer();
    }

    /** Job bars and toasts over normal gameplay (the HUD callback, while the editor is off), at the UI size. */
    public void renderPassive(UiGraphics g, int screenWidth, int screenHeight, long nowMs) {
        setScreen(screenWidth, screenHeight);
        List<JobTracker.Job> list = ctx.session().map(session -> session.jobs().jobs()).orElse(List.of());
        jobs.update(list, false);
        scale.draw(g, () -> {
            passiveFrame = true;
            try {
                hud.layout(width, height);
                hud.render(g, nowMs);
            } finally {
                passiveFrame = false;
            }
            // Over gameplay there is no pointer: the toasts are drawn at the Panels opacity.
            toasts.render(FadedGraphics.of(g, theme, panelFade.opacity().values().panelAlpha()), text, theme,
                    width - 4, 4);
        });
    }

    /**
     * Whether the top bar, palette and hint line count as in use for "Fade only when not hovered": the pointer is over
     * one of them (not over a popup or a window dragged over them), one of their controls has the keyboard or the
     * pointer, or a menu of the menu bar is open.
     */
    private boolean isHudEngaged(double x, double y) {
        if (menuBar.isMenuOpen() || hud.context().captured() != null || hud.context().focused() != null) {
            return true;
        }
        if (windows.context().popups().popupAt(x, y) != null || windows.hitTest(x, y).window() != null) {
            return false;
        }
        for (Node panel : List.of(topBarNode, paletteNode, hintNode)) {
            if (panel.isVisible() && panel.bounds().contains(x, y)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the pointer is over a toast or the Undo anyway toast (drawn above the windows), or that has a control. */
    private boolean isOverToasts(double x, double y) {
        Node offer = historyOffer.node();
        Node held = offerLayer.context().captured();
        if (offer.isVisible() && (offer.bounds().contains(x, y) || held != null && held.root() == offer)) {
            return true;
        }
        return windows.context().popups().popupAt(x, y) == null
                && toasts.drawnRects().stream().anyMatch(rect -> rect.contains(x, y));
    }

    /** How opaque the top bar, palette and hint line were drawn in the last frame (View > Opacity…). */
    public float hudAlpha() {
        return hudAlpha;
    }

    /** How opaque the toasts were drawn in the last frame. */
    public float toastAlpha() {
        return toastAlpha;
    }

    private void refresh() {
        blockChip.setBlock(ctx.activeBlock());
        Optional<EditorSession> session = ctx.session();
        boolean canUndo = session.map(s -> s.history().canUndo()).orElse(false);
        boolean canRedo = session.map(s -> s.history().canRedo()).orElse(false);
        undoButton.setEnabled(canUndo);
        redoButton.setEnabled(canRedo);
        undoButton.setTooltip(canUndo
                ? tr.translate("sculptory.topbar.undo.tooltip", session.get().history().undoLabel(), keymap.display(KeyAction.UNDO))
                : tr.translate("sculptory.history.nothing_to_undo"));
        redoButton.setTooltip(canRedo
                ? tr.translate("sculptory.topbar.redo.tooltip", session.get().history().redoLabel(), keymap.display(KeyAction.REDO))
                : tr.translate("sculptory.history.nothing_to_redo"));
        flyLabel.setText(tr.translate("sculptory.topbar.fly", ctx.flySpeed().display()));
        refreshFluidAim();
        uiSizeButton.setText(uiSizeLabel());
        refreshStatus(session);
        refreshPalette();
        hintLine.set(ctx.tools().active().map(tool -> tr.translate(tool.descriptor().nameKey())).orElse(""),
                HintLine.format(controller.hints(), tr));
        jobs.update(session.map(s -> s.jobs().jobs()).orElse(List.of()), true);
        historyOffer.refresh();
        if (windows.isOpen(EditorWindows.TOOL_SETTINGS)) {
            toolSettings.refresh();
        }
        if (windows.isOpen(EditorWindows.SELECTION)) {
            selectionWindow.refresh();
        }
        if (windows.isOpen(EditorWindows.HISTORY)) {
            historyWindow.refresh();
        }
        if (windows.isOpen(EditorWindows.NOTIFICATIONS)) {
            notificationsWindow.refresh();
        }
        if (windows.isOpen(EditorWindows.TUTORIAL)) {
            tutorial.refreshWindow();
        }
        if (clipboardWindow != null && windows.isOpen(EditorWindows.CLIPBOARD)) {
            clipboardWindow.refresh();
        }
        if (libraryWindow != null && windows.isOpen(EditorWindows.LIBRARY)) {
            libraryWindow.refresh();
        }
        if (windows.isOpen(EditorWindows.TINKER)) {
            tinkerWindow.refresh();
        }
        if (windows.isOpen(EditorWindows.MASK)) {
            maskWindow.refresh();
        }
        if (windows.isOpen(EditorWindows.WIKI)) {
            wikiWindow.refresh();
        } else {
            wikiWindow.closed();
        }
        // The windows that show transfers show them; otherwise the HUD does.
        boolean shownInWindow = !windows.isAllHidden() && (windows.isOpen(EditorWindows.CLIPBOARD)
                || windows.isOpen(EditorWindows.LIBRARY));
        hudTransfers.update(shownInWindow ? List.of() : session.map(EditorSession::transfers).orElse(List.of()));
    }

    /** The History window. */
    public HistoryWindow historyWindow() {
        return historyWindow;
    }

    /** The Undo anyway toast. */
    public HistoryOfferToast historyOffer() {
        return historyOffer;
    }

    /** The Library window, if the editor has clipboard actions. */
    public Optional<LibraryWindow> libraryWindow() {
        return Optional.ofNullable(libraryWindow);
    }

    /** The Clipboard window, if the editor has clipboard actions. */
    public Optional<ClipboardWindow> clipboardWindow() {
        return Optional.ofNullable(clipboardWindow);
    }

    private void refreshFluidAim() {
        boolean on = controller.aimsAtFluids();
        String keys = keymap.display(KeyAction.AIM_AT_FLUIDS);
        String tooltip = tr.translate(on ? "sculptory.topbar.fluid_aim.on" : "sculptory.topbar.fluid_aim.off");
        fluidAimButton.setSelected(on);
        fluidAimButton.setTooltip(keys.isEmpty() ? tooltip : tooltip + "  (" + keys + ")");
    }

    private void refreshStatus(Optional<EditorSession> session) {
        if (session.isEmpty()) {
            status.set(StatusDot.BAD, tr.translate("sculptory.status.offline"), tr.translate("sculptory.status.offline.tooltip"));
        } else if (session.get() instanceof MockEditorSession) {
            status.set(StatusDot.WAIT, tr.translate("sculptory.status.mock"), tr.translate("sculptory.status.mock.tooltip"));
        } else if (session.get().state() == SessionState.READY) {
            if (session.get().permissions().has(Perm.USE)) {
                status.set(StatusDot.GOOD, tr.translate("sculptory.status.connected"),
                        tr.translate("sculptory.status.connected.tooltip"));
            } else {
                status.set(StatusDot.BAD, tr.translate("sculptory.status.no_permission"),
                        tr.translate("sculptory.notice.no_permission"));
            }
        } else if (session.get().state() == SessionState.HANDSHAKING) {
            status.set(StatusDot.WAIT, tr.translate("sculptory.status.connecting"), tr.translate("sculptory.notice.handshaking"));
        } else {
            status.set(StatusDot.BAD, tr.translate("sculptory.status.offline"), tr.translate("sculptory.status.offline.tooltip"));
        }
    }

    private void refreshPalette() {
        for (PaletteButton button : palette) {
            Optional<Tool> tool = ctx.tools().slot(button.slot());
            if (tool.isEmpty()) {
                button.setEnabled(false);
                button.setSelected(false);
                button.setTooltip(null);
                button.setKeyLabel("");
                continue;
            }
            ToolId id = tool.get().descriptor().id();
            if (button.icon() == null) {
                button.setIcon(services.itemIcons().apply(tool.get().descriptor().icon()));
            }
            Optional<Notice> blocked = controller.unavailability(tool.get());
            button.setEnabled(blocked.isEmpty());
            button.setSelected(ctx.tools().isActive(id));
            button.setKeyLabel(PaletteButton.keyLabel(keymap.chords(KeyAction.toolSlot(button.slot()))));
            String name = tr.translate(tool.get().descriptor().nameKey());
            String keys = keymap.display(KeyAction.toolSlot(button.slot()));
            String tooltip = keys.isEmpty() ? name : name + "  (" + keys + ")";
            if (blocked.isPresent()) {
                tooltip += "\n" + tr.translate(blocked.get().key(), blocked.get().args());
            } else {
                tooltip += "\n" + tr.translate(tool.get().descriptor().nameKey() + ".tooltip");
            }
            button.setTooltip(tooltip);
        }
    }

    private void renderToolHud(UiGraphics g) {
        ctx.tools().active().ifPresent(tool ->
                tool.renderHud(ctx.contextFor(tool.descriptor().id()), hudDraw(g)));
    }

    private void renderReadout(UiGraphics g, double mouseX, double mouseY) {
        WorldCursor cursor = ctx.cursor().cursor();
        if (cursor.missed()) {
            return;
        }
        String readout = cursor.pos().x() + ", " + cursor.pos().y() + ", " + cursor.pos().z();
        int x = (int) mouseX + 12;
        int y = (int) mouseY + 12;
        int w = text.width(readout);
        if (x + w + 4 > width) {
            x = (int) mouseX - 12 - w;
        }
        g.fill(x - 2, y - 2, w + 4, text.lineHeight() + 3, 0xA0101216);
        g.text(readout, x, y, theme.text, false);
    }

    /** Tool HUD drawing in screen coordinates: tools place it by projecting world points. */
    private HudDraw hudDraw(UiGraphics g) {
        return new HudDraw() {
            @Override
            public int width() {
                return screenWidth;
            }

            @Override
            public int height() {
                return screenHeight;
            }

            @Override
            public int textWidth(String value) {
                return text.width(value);
            }

            @Override
            public void text(String value, int x, int y, int argb) {
                g.text(value, x, y, argb, false);
            }

            @Override
            public void fill(int x1, int y1, int x2, int y2, int argb) {
                g.fill(Math.min(x1, x2), Math.min(y1, y2), Math.abs(x2 - x1), Math.abs(y2 - y1), argb);
            }
        };
    }

    // ---- Key sheet ----

    /** F1: opens the key sheet (closing any popup), or closes it. */
    @Override
    public void toggleHelp() {
        if (keySheet.isOpen()) {
            keySheet.close();
            return;
        }
        windows.context().popups().closeAll();
        keySheet.open();
        keySheet.layout(width, height, topBarBottom());
    }

    /** F6 (Shift+F6: backwards): the keyboard into the next open window's first control; hidden windows show again. */
    @Override
    public void focusNextWindow(boolean forward) {
        if (windows.isAllHidden()) {
            windows.setAllHidden(false);
        }
        windows.layout(width, height);
        if (!windows.focusNextWindow(forward)) {
            ctx.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.no_window_to_focus"));
        }
    }

    // ---- Menus, the command search, windows, dialogs, pickers ----

    /** Every command, as the menu bar and the command search show them; later parts of the editor provide theirs here. */
    public CommandRegistry commands() {
        return commands;
    }

    public MenuBar menuBar() {
        return menuBar;
    }

    /** The top bar's items (the menus to the UI size button), some of which give way when it is short of room. */
    public PriorityRow topBarRow() {
        return topBarRow;
    }

    public CommandSearch commandSearch() {
        return search;
    }

    /** Ctrl+K: the command search, at the top centre below the top bar. */
    @Override
    public void openCommandSearch() {
        keySheet.close();
        windows.context().popups().closeAll();
        search.open(topBarBottom() + 4);
    }

    /**
     * What the command search finds: every command, every wiki page ("Wiki: Shape brush", opening it in the Wiki
     * window), the active tool's shown settings (running one reveals it in Tool Settings) and its presets (running one
     * loads it).
     */
    private List<SearchEntry> searchEntries() {
        List<SearchEntry> entries = new ArrayList<>(commands.searchEntries());
        entries.addAll(wikiSearchEntries());
        Optional<Tool> active = ctx.tools().active();
        if (active.isEmpty()) {
            return entries;
        }
        ToolId id = active.get().descriptor().id();
        String toolName = tr.translate(active.get().descriptor().nameKey());
        SettingsValues values = ctx.settings(id);
        String settingCategory = tr.translate("sculptory.search.category.setting", toolName);
        for (SettingDef<?> def : active.get().schema().defs()) {
            if (!values.isVisible(def)) {
                continue;
            }
            String entryId = "setting:" + id.value() + ":" + def.key();
            entries.add(new SearchEntry(entryId, tr.translate(def.labelKey()), settingCategory, "", Availability.OK,
                    () -> {
                        commands.noteRun(entryId);
                        revealSetting(def.key());
                    }));
        }
        Optional<Presets> presets = toolSettings.presets();
        if (presets.isPresent() && !active.get().schema().defs().isEmpty()) {
            String presetCategory = tr.translate("sculptory.search.category.preset", toolName);
            for (String name : presets.get().names(id)) {
                String entryId = "preset:" + id.value() + ":" + name;
                entries.add(new SearchEntry(entryId, tr.translate("sculptory.search.preset", name), presetCategory, "",
                        Availability.OK, () -> {
                            commands.noteRun(entryId);
                            presets.get().select(id, name);
                        }));
            }
        }
        return entries;
    }

    /**
     * Shows the active tool's setting {@code key}: opens Tool Settings (and the setting's section), scrolls to the
     * setting and gives its control the keyboard. Returns false when the tool has no such shown setting.
     */
    public boolean revealSetting(String key) {
        showWindow(EditorWindows.TOOL_SETTINGS);
        windows.setCollapsed(EditorWindows.TOOL_SETTINGS, false);
        windows.layout(width, height);
        if (!toolSettings.revealSetting(key)) {
            return false;
        }
        // The section opens now; the row scrolls into view at the next layout.
        windows.layout(width, height);
        windows.layout(width, height);
        return true;
    }

    /** Opens the Tinker panel (or brings it forward, expanded) for what the Tinker tool clicked. */
    public void openTinker() {
        showWindow(EditorWindows.TINKER);
        windows.setCollapsed(EditorWindows.TINKER, false);
        tinkerWindow.refresh();
    }

    /** The Tinker panel (tests, tour). */
    public TinkerWindow tinkerWindow() {
        return tinkerWindow;
    }

    private TinkerWindow.Services tinkerServices() {
        return new TinkerWindow.Services() {
            private Optional<TinkerTool> tool() {
                return ctx.tools().get(ToolId.TINKER).filter(TinkerTool.class::isInstance).map(TinkerTool.class::cast);
            }

            @Override
            public Optional<TinkerController> controller() {
                return tool().map(TinkerTool::controller);
            }

            @Override
            public Optional<TinkerController.Target> panelTarget() {
                return tool().flatMap(TinkerTool::panelTarget);
            }

            @Override
            public boolean applyToSelection(BlockPos pos) {
                return tool().map(tinker -> tinker.applyToSelection(pos)).orElse(false);
            }

            @Override
            public StateSpace states() {
                return ctx.backend().states();
            }

            @Override
            public int activeBlockState() {
                try {
                    return ctx.backend().states().resolve(ctx.activeBlock());
                } catch (RuntimeException noStates) {
                    return -1;
                }
            }

            @Override
            public boolean hasSelection() {
                return ctx.selectionState().isPresent();
            }

            @Override
            public List<String> paintingVariants() {
                return platform.paintingVariants();
            }

            @Override
            public String itemOf(int state) {
                try {
                    return platform.itemOf(ctx.backend().states().describe(state));
                } catch (RuntimeException noStates) {
                    return "";
                }
            }
        };
    }

    /** Opens a window (showing hidden windows again) and brings it to the front; an open one just comes forward. */
    private void showWindow(String id) {
        if (!windows.isOpen(id)) {
            toggleWindow(id);
            return;
        }
        if (windows.isAllHidden()) {
            windows.setAllHidden(false);
        }
        windows.bringToFront(id);
    }

    /** Where dialogs opened from a menu or the search appear: centred below the top bar. */
    private Rect dialogAnchor(int dialogWidth) {
        return new Rect(Math.max(0, (width - dialogWidth) / 2), topBarBottom() + 4, dialogWidth, 0);
    }

    private EditorCommands.Host commandHost() {
        return new EditorCommands.Host() {
            @Override
            public boolean hasWindow(String id) {
                return windows.window(id).isPresent();
            }

            @Override
            public boolean isWindowShown(String id) {
                return windows.isOpen(id) && !windows.isAllHidden();
            }

            @Override
            public void toggleWindow(String id) {
                EditorUi.this.toggleWindow(id);
            }

            @Override
            public void showWindow(String id) {
                EditorUi.this.showWindow(id);
            }

            @Override
            public boolean windowsHidden() {
                return windows.isAllHidden();
            }

            @Override
            public void toggleWindowsHidden() {
                EditorUi.this.toggleWindowsHidden();
            }

            @Override
            public void resetLayout() {
                // Every open window back on screen: hidden ones (Tab) show again too.
                windows.setAllHidden(false);
                windows.resetLayout();
            }

            @Override
            public void toggleHelp() {
                EditorUi.this.toggleHelp();
            }

            @Override
            public void openCommandSearch() {
                EditorUi.this.openCommandSearch();
            }

            @Override
            public void openReplace() {
                selectionWindow.openReplace(dialogAnchor(212));
            }

            @Override
            public void openOverlay() {
                selectionWindow.openOverlay(dialogAnchor(212));
            }

            @Override
            public void openNaturalize() {
                selectionWindow.openNaturalize(dialogAnchor(212));
            }

            @Override
            public void openSaveSelection() {
                controller.clipboard().ifPresent(clipboard -> SaveAssetDialog.open(windows.context(),
                        dialogAnchor(SaveAssetDialog.WIDTH), tr, "my_build.schem", clipboard.format(),
                        clipboard::saveSelection));
            }

            @Override
            public void openExportClipboard() {
                controller.clipboard().ifPresent(clipboard -> ExportDialog.open(windows.context(),
                        dialogAnchor(ExportDialog.WIDTH), tr, clipboard.exportName(), clipboard.format(),
                        (name, format) -> clipboard.exportClipboard(format, name)));
            }

            @Override
            public void openOpacity() {
                EditorUi.this.openOpacity();
            }

            @Override
            public int uiSizePercent() {
                return scale.percent();
            }

            @Override
            public void setUiSize(int percent) {
                EditorUi.this.setUiSize(percent);
            }

            @Override
            public void stepUiSize(int direction) {
                EditorUi.this.stepUiSize(direction);
            }

            @Override
            public String editorToggleKey() {
                return services.vanillaKeys().get().toggle();
            }
        };
    }

    @Override
    public void toggleWindow(String id) {
        if (!windows.isOpen(id) && id.equals(EditorWindows.KEYS)) {
            // The player's reserved keys are read anew each time the editor opens.
            keysWindow.refresh();
        }
        if (windows.isAllHidden()) {
            windows.setAllHidden(false);
        }
        windows.toggle(id);
    }

    @Override
    public void toggleWindowsHidden() {
        boolean hide = !windows.isAllHidden();
        windows.setAllHidden(hide);
        if (hide) {
            ctx.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.windows_hidden", keymap.display(KeyAction.HIDE_WINDOWS)));
        }
    }

    @Override
    public void toolChanged() {
        windows.window(EditorWindows.TOOL_SETTINGS).ifPresent(window -> window.rebuildContent());
    }

    // ---- Wiki ----

    /** The Wiki window. */
    public WikiWindow wikiWindow() {
        return wikiWindow;
    }

    /**
     * Where the wiki's pages and pictures come from, and what a link to a website does (in game: the mod's resources,
     * and Minecraft's "open this link?" screen). Without this the wiki has no pages.
     */
    public void setWiki(WikiSource source, WikiPictures pictures, Consumer<String> openUrl) {
        wikiWindow.setWiki(new WikiLibrary(source), pictures, openUrl);
    }

    /**
     * Opens the Wiki window (showing hidden windows again, bringing it to the front and unfolding it) at page
     * {@code pageId}, scrolled to the heading with {@code anchor} (null: the page's top). A null page shows the page
     * shown last (the home page the first time). A page or anchor the wiki doesn't have shows "not found" or the page's
     * top. Other parts of the editor (the tutorial's "Learn more") open the wiki through this.
     */
    public void openWiki(String pageId, String anchor) {
        keySheet.close();
        showWindow(EditorWindows.WIKI);
        windows.setCollapsed(EditorWindows.WIKI, false);
        if (pageId == null) {
            wikiWindow.showCurrentOrHome();
        } else {
            wikiWindow.open(pageId, anchor);
        }
    }

    /** The command search's wiki pages: "Wiki: title" for each, in the page list's order, found under Help. */
    private List<SearchEntry> wikiSearchEntries() {
        List<SearchEntry> entries = new ArrayList<>();
        String category = tr.translate(CommandMenu.HELP.titleKey());
        for (WikiPage page : wikiWindow.library().pages()) {
            String entryId = "wiki:" + page.id();
            entries.add(new SearchEntry(entryId, tr.translate("sculptory.wiki.search_entry", page.title()), category,
                    "", Availability.OK, () -> {
                        commands.noteRun(entryId);
                        openWiki(page.id(), null);
                    }));
        }
        return entries;
    }

    // ---- Opacity ----

    /**
     * View > Opacity…: the popup with the Panels and Tool outlines sliders, where dialogs open (centred below the top
     * bar), closing the other popups. Its changes apply at once and are saved with the UI size.
     */
    public OpacityPopup openOpacity() {
        UiContext popupCtx = windows.context();
        popupCtx.popups().closeAll();
        keySheet.close();
        return OpacityPopup.open(popupCtx, dialogAnchor(OpacityPopup.WIDTH), services.uiOpacity(), formServices());
    }

    // ---- UI size ----

    @Override
    public void stepUiSize(int direction) {
        setUiSize(scale.stepped(direction));
    }

    @Override
    public void resetUiSize() {
        setUiSize(UiScale.DEFAULT_PERCENT);
    }

    /**
     * Applies a UI size at once: a window drag in progress is cancelled (its grab point was in the
     * old units), the windows take that size's own arrangement, everything is laid out again on the new virtual
     * screen, and a toast says the size (also at the smallest or largest size, so the key press visibly did
     * something).
     */
    private void setUiSize(int percent) {
        if (scale.set(percent)) {
            windows.cancelInteraction();
            layout(screenWidth, screenHeight);
        }
        String message = tr.translate("sculptory.notice.ui_size", scale.percent() + "%");
        if (uiSizeToast != null) {
            toasts.dismiss(uiSizeToast);
        }
        toasts.show(Notice.Level.INFO, message);
        uiSizeToast = message;
    }

    private String uiSizeLabel() {
        return tr.translate("sculptory.topbar.ui_size", scale.percent() + "%");
    }

    /** The top bar's UI size button: the same menu as View > UI size. */
    private void openUiSizeMenu(Rect anchor) {
        commands.get(EditorCommands.UI_SIZE).ifPresent(command ->
                new Menu(commands.menuItems(command.children())).open(windows.context(), anchor, null));
    }

    // ---- Screenshot tour hooks (dev only) ----

    /**
     * {@link #openMenu} ids: each menu of the menu bar ({@link #MENU_FILE} ... {@link #MENU_HELP}, the lower-case
     * {@link CommandMenu} names), the View menu with its UI size submenu open, the top bar's UI size button menu and the
     * block chip's picker.
     */
    public static final String MENU_FILE = "file";
    public static final String MENU_EDIT = "edit";
    public static final String MENU_SELECTION = "selection";
    public static final String MENU_TOOLS = "tools";
    public static final String MENU_VIEW = "view";
    public static final String MENU_HELP = "help";
    public static final String MENU_VIEW_UI_SIZE = "view_ui_size";
    public static final String MENU_UI_SIZE = "ui_size";
    public static final String MENU_BLOCK = "block";

    /**
     * Opens a menu or picker as a click on it does: a menu bar menu by its id ({@link #MENU_FILE} and so on),
     * {@link #MENU_VIEW_UI_SIZE}, {@link #MENU_UI_SIZE} or {@link #MENU_BLOCK}. Used by the screenshot tour.
     *
     * @throws IllegalArgumentException for another id
     */
    public void openMenu(String id) {
        switch (id) {
            case MENU_FILE, MENU_EDIT, MENU_SELECTION, MENU_TOOLS, MENU_VIEW, MENU_HELP ->
                    menuBar.open(CommandMenu.valueOf(id.toUpperCase(Locale.ROOT)).ordinal(), false);
            case MENU_VIEW_UI_SIZE -> {
                menuBar.open(CommandMenu.VIEW.ordinal(), false);
                windows.layout(width, height);
                Menu view = menuBar.openMenu().orElseThrow();
                String label = tr.translate("sculptory.command.view.ui_size");
                for (int i = 0; i < view.items().size(); i++) {
                    if (view.items().get(i).hasSubmenu() && view.items().get(i).label().equals(label)) {
                        view.activate(i);
                    }
                }
            }
            case MENU_UI_SIZE -> openUiSizeMenu(uiSizeButton.bounds());
            case MENU_BLOCK -> BlockPicker.open(windows.context(), blockChip.bounds(), services.blocks(), tr,
                    ctx::setActiveBlock);
            default -> throw new IllegalArgumentException("Unknown menu: " + id);
        }
    }

    /** Where palette slot {@code slot} (1-based) was last laid out, in UI units; empty for a slot without a button. */
    public Optional<Rect> paletteSlotBounds(int slot) {
        return palette.stream().filter(button -> button.slot() == slot).findFirst().map(Node::bounds);
    }

    /** A centred dialog: Enter (or the Confirm button) runs {@code onConfirm}; Esc or a click outside cancels. */
    @Override
    public void confirm(String message, Runnable onConfirm) {
        UiContext popupCtx = windows.context();
        PopupLayer.Popup[] popup = new PopupLayer.Popup[1];
        Label body = Label.of(message);
        body.setWrap(true);
        Button cancel = new Button(tr.translate("sculptory.dialog.cancel"), () -> popupCtx.popups().close(popup[0]));
        Button confirm = new Button(tr.translate("sculptory.dialog.confirm"), () -> {
            popupCtx.popups().close(popup[0]);
            onConfirm.run();
        });
        confirm.setStyle(Button.Style.PRIMARY);
        Column content = Column.of(body, Label.dim(tr.translate("sculptory.dialog.confirm_hint")),
                Row.of(Spacer.flexible(), cancel, confirm));
        content.setGap(6);
        content.setFixedWidth(220);
        int dialogWidth = 232;
        Rect anchor = new Rect(Math.max(0, (width - dialogWidth) / 2), Math.max(0, height / 3), dialogWidth, 0);
        popup[0] = popupCtx.popups().open(null, new Padding(Insets.all(6), content), anchor, dialogWidth, null);
        popupCtx.setFocus(confirm);
    }

    private void cancelJob(UUID jobId) {
        ctx.session().ifPresent(session -> session.send(new ToolAction.Cancel(jobId)));
    }

    /**
     * A top bar button that keeps its whole label when the bar is short of room (other items give way first, whole:
     * see the top bar's drop order), so it never turns into "...".
     */
    private void keepWhole(Button button) {
        button.setMinSize(text.width(button.text()) + 2 * theme.controlPaddingX, 0);
    }

    private Button flat(String label, Runnable onClick) {
        Button button = new Button(label, onClick);
        button.setStyle(Button.Style.FLAT);
        return button;
    }

    private SettingsForm.Services formServices() {
        return new SettingsForm.Services() {
            @Override
            public Translator translator() {
                return tr;
            }

            @Override
            public BlockCatalog blocks() {
                return services.blocks();
            }

            @Override
            public void pickBlock(Rect anchor, BlockDescriptor current, Consumer<BlockDescriptor> onPick) {
                BlockPicker.open(windows.context(), anchor, services.blocks(), tr, onPick);
            }

            @Override
            public void editMask(Rect anchor, EditMask current, Consumer<EditMask> onChange) {
                MaskPopup.open(windows.context(), anchor, services.blocks(), tr, current, onChange);
            }

            @Override
            public StateSpace states() {
                try {
                    return ctx.backend().states();
                } catch (RuntimeException none) {
                    return null;
                }
            }

            @Override
            public Limits limits() {
                return ctx.session().map(session -> session.permissions().limits()).orElse(null);
            }
        };
    }

    private SelectionWindow.Host selectionHost() {
        return new SelectionWindow.Host() {
            @Override
            public Optional<Selection> selectionState() {
                return ctx.selectionState();
            }

            @Override
            public void setSelectionRegion(Region region) {
                ctx.setSelectionRegion(region);
            }

            @Override
            public SettingsValues settings() {
                return ctx.settings(ToolId.SELECT);
            }

            @Override
            public void updateSettings(SettingsValues values) {
                ctx.updateSettings(ToolId.SELECT, values);
            }

            @Override
            public BlockDescriptor activeBlock() {
                return ctx.activeBlock();
            }

            @Override
            public Optional<BlockDescriptor> hoveredBlock() {
                WorldCursor cursor = ctx.cursor().cursor();
                return cursor.missed() ? Optional.empty() : platform.blockAt(cursor.pos());
            }

            @Override
            public Optional<JobTracker.Job> job(UUID jobId) {
                return ctx.session().flatMap(session -> session.jobs().job(jobId));
            }

            @Override
            public void pickBlock(Rect anchor, BlockDescriptor current, Consumer<BlockDescriptor> onPick) {
                BlockPicker.open(windows.context(), anchor, services.blocks(), tr, onPick);
            }

            @Override
            public UiContext popups() {
                return windows.context();
            }
        };
    }

    // ---- InputRouter.Ui (screen coordinates in, UI units inside) ----

    @Override
    public boolean hasPopup() {
        return keySheet.isOpen() || windows.context().popups().isOpen();
    }

    /**
     * A control has the keyboard (typed keys belong to the UI): one in a window or popup, the key sheet's, or the quick
     * start card's Got it button, which answers Enter while the card shows (other keys still reach the editor).
     */
    @Override
    public boolean hasKeyboardFocus() {
        return keySheet.hasKeyboardFocus() || windows.hasKeyboardFocus() || (showsEditorHud() && quickStart.isShown());
    }

    @Override
    public boolean capturesInput() {
        return windows.isCapturingInput();
    }

    @Override
    public void clearFocus() {
        windows.context().clearFocus();
    }

    /** The cursor shape the UI wants: a resize cursor over a window edge or corner, or while resizing. */
    public UiCursor cursor() {
        return editing && !keySheet.isOpen() ? windows.cursor() : UiCursor.DEFAULT;
    }

    @Override
    public boolean isOverUi(double screenX, double screenY) {
        return overUi(scale.toUi(screenX), scale.toUi(screenY));
    }

    private boolean overUi(double x, double y) {
        return keySheet.isOpen() || windows.isMouseOverUi(x, y)
                || (editing && (hud.isOver(x, y) || offerLayer.isOver(x, y) || toasts.toastAt(x, y).isPresent()));
    }

    /** A menu of the menu bar is open and the point is over one of the bar's titles (popups don't get it then). */
    private boolean overOpenMenuBar(double x, double y) {
        return editing && !keySheet.isOpen() && menuBar.isMenuOpen() && menuBar.titleAt(x, y) >= 0;
    }

    /**
     * The layer above the windows (the Undo anyway toast, the quick start card) takes the mouse before the windows under
     * it (no popup open, the editor in use).
     */
    private boolean overOffer(double x, double y) {
        return editing && !keySheet.isOpen() && !windows.context().popups().isOpen() && offerLayer.isOver(x, y);
    }

    @Override
    public boolean mouseDown(double screenX, double screenY, int button, int modifiers) {
        double x = scale.toUi(screenX);
        double y = scale.toUi(screenY);
        int slot = Math.max(0, Math.min(targets.length - 1, button));
        if (windows.isCapturingInput()) {
            windows.mouseDown(x, y, button, modifiers);
            targets[slot] = Target.WINDOWS;
            return true;
        }
        if (keySheet.isOpen()) {
            keySheet.mouseDown(x, y, button, modifiers);
            targets[slot] = Target.SHEET;
            return true;
        }
        if (editing && toasts.dismissAt(x, y)) {
            // A click on a toast takes it away.
            targets[slot] = Target.NONE;
            return true;
        }
        if (overOffer(x, y)) {
            clearFocus();
            offerLayer.mouseDown(x, y, button, modifiers);
            targets[slot] = Target.OFFER;
            return true;
        }
        if (overOpenMenuBar(x, y)) {
            // A second click on the open menu's title closes it; a click on another title opens that one.
            hud.mouseDown(x, y, button, modifiers);
            targets[slot] = Target.HUD;
            return true;
        }
        if (windows.context().popups().isOpen() || windows.isMouseOverUi(x, y)) {
            windows.mouseDown(x, y, button, modifiers);
            targets[slot] = Target.WINDOWS;
            return true;
        }
        if (editing && hud.isOver(x, y)) {
            clearFocus();
            hud.mouseDown(x, y, button, modifiers);
            targets[slot] = Target.HUD;
            return true;
        }
        windows.mouseDown(x, y, button, modifiers);
        targets[slot] = Target.NONE;
        return false;
    }

    @Override
    public boolean mouseDragged(double screenX, double screenY, int button) {
        double x = scale.toUi(screenX);
        double y = scale.toUi(screenY);
        int slot = Math.max(0, Math.min(targets.length - 1, button));
        return switch (targets[slot]) {
            case WINDOWS -> windows.mouseDragged(x, y, button);
            case HUD -> hud.mouseDragged(x, y, button);
            case OFFER -> offerLayer.mouseDragged(x, y, button);
            case SHEET -> keySheet.mouseDragged(x, y, button);
            case NONE -> false;
        };
    }

    @Override
    public boolean mouseUp(double screenX, double screenY, int button) {
        double x = scale.toUi(screenX);
        double y = scale.toUi(screenY);
        int slot = Math.max(0, Math.min(targets.length - 1, button));
        Target target = targets[slot];
        targets[slot] = Target.NONE;
        return switch (target) {
            case WINDOWS -> windows.mouseUp(x, y, button);
            case HUD -> hud.mouseUp(x, y, button);
            case OFFER -> offerLayer.mouseUp(x, y, button);
            case SHEET -> keySheet.mouseUp(x, y, button);
            case NONE -> false;
        };
    }

    @Override
    public boolean mouseScrolled(double screenX, double screenY, double amount, int modifiers) {
        double x = scale.toUi(screenX);
        double y = scale.toUi(screenY);
        if (windows.isCapturingInput()) {
            return windows.mouseScrolled(x, y, amount, modifiers);
        }
        if (keySheet.isOpen()) {
            return keySheet.mouseScrolled(x, y, amount, modifiers);
        }
        if (overOffer(x, y)) {
            return true;
        }
        if (windows.context().popups().isOpen() || windows.isMouseOverUi(x, y)) {
            return windows.mouseScrolled(x, y, amount, modifiers);
        }
        return editing && hud.mouseScrolled(x, y, amount, modifiers);
    }

    @Override
    public void mouseMoved(double screenX, double screenY) {
        double x = scale.toUi(screenX);
        double y = scale.toUi(screenY);
        windows.mouseMoved(x, y);
        if (overOpenMenuBar(x, y)) {
            // While a menu is open, moving over another title opens that one.
            hud.mouseMoved(x, y);
            return;
        }
        if (overOffer(x, y)) {
            offerLayer.mouseMoved(x, y);
            hud.clearHover();
            return;
        }
        offerLayer.clearHover();
        if (keySheet.isOpen()) {
            keySheet.mouseMoved(x, y);
        }
        if (keySheet.isOpen() || windows.context().popups().isOpen() || windows.isMouseOverUi(x, y)) {
            hud.clearHover();
        } else {
            hud.mouseMoved(x, y);
        }
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (keySheet.isOpen()) {
            return keySheet.keyPressed(key, scanCode, modifiers);
        }
        if (windows.keyPressed(key, scanCode, modifiers)) {
            return true;
        }
        // The quick start card: Got it answers Enter; Esc also hides it (after popups and focus, as the ladder goes).
        boolean enter = key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER;
        if (showsEditorHud() && quickStart.isShown() && (enter || key == GLFW.GLFW_KEY_ESCAPE)) {
            quickStart.dismiss();
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (keySheet.isOpen()) {
            return keySheet.charTyped(chr, modifiers);
        }
        return windows.charTyped(chr, modifiers);
    }
}
