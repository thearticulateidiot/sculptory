package dev.sculptory.fabric.client.editor.windows;

import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.settings.form.SettingsForm;
import dev.sculptory.fabric.client.editor.ui.Insets;
import dev.sculptory.fabric.client.editor.ui.PopupLayer;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiOpacity;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.Padding;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import java.util.Objects;

/**
 * View > Opacity…: a small popup with the <b>Panels</b> slider (20–100%), the <b>Fade only when not hovered</b> switch
 * and the <b>Tool outlines</b> slider (10–100%) of {@link UiOpacity}. It is built from a settings schema, so it works
 * as Tool Settings does: a slider takes a typed value (double-click, Ctrl+click or Enter), each setting explains itself
 * in its tooltip, and a changed one shows ↺ to go back to its default. Changes apply at once, also while a slider is
 * dragged. The keyboard starts on the Panels slider; Esc or a click outside closes the popup.
 */
public final class OpacityPopup {
    /** The popup's width in UI units. */
    public static final int WIDTH = 200;
    private static final int PADDING = 6;

    static final SettingDef.Int PANELS = new SettingDef.Int("panels", "sculptory.opacity.panels",
            UiOpacity.MAX, UiOpacity.PANELS_MIN, UiOpacity.MAX);
    static final SettingDef.Bool FADE_UNLESS_HOVERED = new SettingDef.Bool("fade_unless_hovered",
            "sculptory.opacity.fade_unless_hovered", false);
    static final SettingDef.Int TOOL_OUTLINES = new SettingDef.Int("tool_outlines", "sculptory.opacity.tool_outlines",
            UiOpacity.MAX, UiOpacity.OUTLINES_MIN, UiOpacity.MAX);
    /** The three settings, in the popup's order. */
    public static final SettingsSchema SCHEMA = SettingsSchema.of(PANELS, FADE_UNLESS_HOVERED, TOOL_OUTLINES);

    private final SettingsForm form;
    private final PopupLayer.Popup popup;

    private OpacityPopup(SettingsForm form, PopupLayer.Popup popup) {
        this.form = form;
        this.popup = popup;
    }

    /** Opens the popup below {@code anchor} in {@code ctx}'s popup layer; every change goes straight to {@code opacity}. */
    public static OpacityPopup open(UiContext ctx, Rect anchor, UiOpacity opacity, SettingsForm.Services services) {
        Objects.requireNonNull(opacity);
        SettingsForm form = SettingsForm.build(values(opacity.values()), next -> opacity.set(opacity(next)), services);
        Column content = Column.of(Label.heading(services.translator().translate("sculptory.opacity.title")),
                form.node(), Label.dim(services.translator().translate("sculptory.opacity.hint")).setWrap(true));
        content.setGap(5);
        content.setFixedWidth(WIDTH - 2 * PADDING);
        PopupLayer.Popup popup = ctx.popups().open(null, new Padding(Insets.all(PADDING), content), anchor, WIDTH, null);
        form.control(PANELS.key()).ifPresent(ctx::setFocus);
        return new OpacityPopup(form, popup);
    }

    /** The settings form inside (its sliders, switch and reset buttons). */
    public SettingsForm form() {
        return form;
    }

    public PopupLayer.Popup popup() {
        return popup;
    }

    /** The popup's settings holding {@code values}: what it shows when it opens. */
    public static SettingsValues values(UiOpacity.Values values) {
        return SettingsValues.defaults(SCHEMA)
                .with(PANELS, values.panels())
                .with(FADE_UNLESS_HOVERED, values.fadeUnlessHovered())
                .with(TOOL_OUTLINES, values.toolOutlines());
    }

    /** The opacity the popup's settings say. */
    static UiOpacity.Values opacity(SettingsValues values) {
        return new UiOpacity.Values(values.get(PANELS), values.get(FADE_UNLESS_HOVERED), values.get(TOOL_OUTLINES));
    }
}
