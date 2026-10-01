package dev.sculptory.fabric.client.editor.ui;

/**
 * The single source of colours, spacing and text settings for the editor UI. Colours are ARGB.
 * Sizes are UI units: scaled GUI pixels at 100% editor UI size ({@link UiScale}). Widgets read
 * everything visual from here; nothing hard-codes a colour.
 */
public final class Theme {
    /** The editor's dark theme. */
    public static final Theme DARK = new Theme();

    // Surfaces. Anything drawn over other UI (window bodies and title bars, popups, tooltips, toasts, the help sheet)
    // is opaque: the screen is painted back to front, so a see-through surface shows the text of what lies under it.
    // The player can fade the panels' surfaces (View > Opacity…, see panel surfaces below); popups, tooltips, the key
    // sheet and the quick start and lesson cards always stay opaque.
    public final int windowBackground = 0xFF1A1D23;
    public final int windowBorder = 0xFF3B4150;
    public final int windowBorderActive = 0xFF56607A;
    public final int windowShadow = 0x50000000;
    public final int titleBar = 0xFF242833;
    public final int titleBarActive = 0xFF2E3547;
    public final int titleText = 0xFFE8EBF2;
    public final int popupBackground = 0xFF1E222A;
    public final int popupBorder = 0xFF4A5264;
    public final int tooltipBackground = 0xFF101216;
    public final int tooltipBorder = 0xFF3B4150;
    public final int listBackground = 0xFF16191E;
    public final int sectionHeader = 0xFF222731;
    /**
     * The band of a list's group heading (the Keys window, the key sheet): lighter than a Tool Settings section's
     * header, whose arrow already marks it, so it reads as a heading on a window's and on the key sheet's lighter
     * background...
     */
    public final int headingBand = 0xFF2A313E;
    /** ...with a strip of {@link #accentDim} this wide at its left end. */
    public final int headingStripWidth = 2;
    public final int separator = 0xFF303644;

    // Text
    public final int text = 0xFFD9DEE8;
    public final int textDim = 0xFF8C95A8;
    public final int textDisabled = 0xFF596070;
    public final int textOnAccent = 0xFFFFFFFF;

    // Controls
    public final int control = 0xFF2A2F3A;
    public final int controlHover = 0xFF343A47;
    public final int controlPressed = 0xFF3E4556;
    public final int controlDisabled = 0xFF22262E;
    public final int controlBorder = 0xFF414858;
    public final int inputBackground = 0xFF15181D;
    public final int accent = 0xFF4C8DFF;
    public final int accentHover = 0xFF6B9FFF;
    public final int accentDim = 0xFF2F5594;
    public final int danger = 0xFFD9534B;
    public final int dangerHover = 0xFFEC6A62;
    public final int focusRing = 0xFF7AA7FF;
    public final int selection = 0x804C8DFF;
    public final int rowHover = 0x1EFFFFFF;
    public final int toggleOff = 0xFF3A4050;
    public final int knob = 0xFFE8EBF2;
    public final int disabledOverlay = 0x99101216;
    public final int scrollTrack = 0x40000000;
    public final int scrollThumb = 0xFF4A5264;
    public final int scrollThumbHover = 0xFF66728C;
    public final int progressTrack = 0xFF22262E;
    public final int progressFill = 0xFF4C8DFF;
    /** The bottom-right resize grip's lines. */
    public final int resizeGrip = 0xFFB4BCCC;
    /** The grip and the edge being resized, while hovered or dragged. */
    public final int resizeGripHot = 0xFF6B9FFF;

    // Metrics
    public final int padding = 5;
    public final int gap = 4;
    public final int controlHeight = 16;
    public final int controlPaddingX = 6;
    public final int titleBarHeight = 16;
    public final int titleButtonSize = 12;
    public final int rowHeight = 14;
    public final int rowInset = 3;
    public final int scrollbarWidth = 5;
    public final int minThumbLength = 10;
    public final int iconButtonSize = 20;
    public final int toggleTrackWidth = 18;
    public final int toggleTrackHeight = 10;
    public final int sectionIndent = 6;
    /** Width of the reset button ("↺") at the right of a changed setting in Tool Settings. */
    public final int resetButtonWidth = 14;
    /** The bottom-right grip square; outside the content area it resizes from the corner. */
    public final int resizeGripSize = 10;
    /**
     * Width of the band along a window's border that resizes from that edge; the editor widens it
     * at small UI sizes so it stays this many screen pixels wide.
     */
    public final int resizeBorder = 4;
    /** How far a corner's resize zone reaches along each edge from the corner (widened likewise). */
    public final int resizeCornerSize = 8;
    public final int snapDistance = 8;
    public final int popupMaxRows = 10;
    public final int doubleClickMs = 400;

    // Menus (the top bar's menu bar, its popups) and the command search
    /** The highlighted menu item or search result. */
    public final int menuHighlight = 0xFF2F5594;
    /** A menu title while its menu is open. */
    public final int menuTitleOpen = 0xFF3E4556;
    /** Space left and right of a menu title's text. */
    public final int menuTitlePaddingX = 6;
    /** One menu item's height. */
    public final int menuRowHeight = 14;
    /** Space above the first and below the last menu item. */
    public final int menuPaddingY = 2;
    /** A separator line's slot between menu items. */
    public final int menuSeparatorHeight = 5;
    /** The column at a menu item's left that holds its check mark. */
    public final int menuCheckWidth = 12;
    /** Space between an item's label and its key text. */
    public final int menuKeyGap = 18;
    /** The column at a menu item's right that holds its submenu arrow. */
    public final int menuArrowWidth = 10;
    /** A menu popup is at least this wide. */
    public final int menuMinWidth = 110;
    /** The command search popup's width at 100% UI size. */
    public final int commandSearchWidth = 320;
    /** How many results the command search shows at most. */
    public final int commandSearchRows = 10;
    /** Space between a search result's name and its menu (dim). */
    public final int commandSearchCategoryGap = 8;

    // HUD: the tool palette, the hint line, toasts, the key sheet, the quick start card
    /** Space between two palette slots of one tool group... */
    public final int paletteSlotGap = 2;
    /** ...and between the groups (Select · Terrain · Place and scatter · Build). */
    public final int paletteGroupGap = 8;
    /** The top bar's plate behind the menus and buttons. */
    public final int topBarBackground = 0xE0181B21;
    /** The palette's plate behind the slots. */
    public final int paletteBackground = 0xC0101216;
    /** The hint line's plate. */
    public final int hintBackground = 0xA0101216;
    /** Toast level colours (the bar at a toast's left, a Notifications row's). INFO uses {@link #accent}. */
    public final int noticeSuccess = 0xFF4CC38A;
    public final int noticeWarning = 0xFFE0B040;
    /** The dimmed screen behind the key sheet. */
    public final int backdrop = 0x80000000;
    /**
     * Space between the key column (of the key sheet and the quick start card, as wide as its widest key name) and
     * what the keys do.
     */
    public final int keyTextGap = 10;
    /**
     * The key sheet's descriptions get this much room (or less when the screen is narrower); longer ones wrap. The
     * key column is as wide as its widest key name: an action's keys that don't fit it go on more lines, split
     * between keys, and a name is never split...
     */
    public final int keySheetTextMaxWidth = 280;
    /** ...and it shows two columns side by side when each column's descriptions can be at least this wide. */
    public final int keySheetTextMinWidth = 160;
    /** Space between the key sheet's two columns. */
    public final int keySheetColumnGap = 20;
    /** Space above a list's section heading (the Keys window's key groups) that follows other rows. */
    public final int headingSpaceAbove = 6;
    /** Space between the key sheet (and the quick start card, as wide as its lines) and the screen's edges. */
    public final int sheetMargin = 8;
    /** A Notifications row's level bar width. */
    public final int noticeBarWidth = 3;

    // Tooltips
    public final int tooltipDelayMs = 500;
    public final int tooltipMaxWidth = 200;
    public final int tooltipOffset = 10;

    // Text
    /** Draw body text with a drop shadow. Off: flat text reads better on the dark surfaces. */
    public final boolean textShadow = false;
    /** Draw window titles and headings with a drop shadow. */
    public final boolean titleShadow = true;
    /** Extra pixels between wrapped lines. */
    public final int lineSpacing = 2;

    // Depth layers. Items drawn in the GUI occupy z 142..158 above their layer.
    /** Z distance between stacked windows, popups and the tooltip. */
    public final int layerStep = 300;
    /** Z offset for overlays drawn on top of item icons inside one layer. */
    public final int itemOverlayZ = 160;

    // Opacity (View > Opacity…, UiOpacity)
    /**
     * The panel surfaces: the backgrounds that fade with the Panels opacity in a window, the top bar, the palette, the
     * hint line and the toasts ({@link FadedGraphics}). Everything else drawn there stays as it is: text, icons, borders
     * and separators, a slider's fill and handle, a switch's knob, an accent (a selected or primary button, a switched
     * on switch), a hover or selection tint. Don't draw content in one of these colours.
     */
    private final int[] panelSurfaces = {windowBackground, titleBar, titleBarActive, windowShadow, topBarBackground,
            paletteBackground, hintBackground, popupBackground, listBackground, sectionHeader, headingBand, control,
            controlHover, controlPressed, controlDisabled, inputBackground, toggleOff, progressTrack, scrollTrack};
    /** Below this panel opacity, text on a faded panel gets a drop shadow, to read over the world behind it. */
    public final float fadedTextShadowBelow = 0.75F;

    private Theme() {
    }

    /** Whether {@code argb} is one of the {@link #panelSurfaces} (alpha included), which fade with the panels. */
    public boolean fadesWithPanels(int argb) {
        for (int surface : panelSurfaces) {
            if (surface == argb) {
                return true;
            }
        }
        return false;
    }
}
