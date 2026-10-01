package dev.sculptory.fabric.client.editor.windows;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.tinker.DisplayRotation;
import dev.sculptory.core.tinker.EntityEdit;
import dev.sculptory.core.tinker.EntityView;
import dev.sculptory.core.tinker.SignText;
import dev.sculptory.core.tinker.TinkerKind;
import dev.sculptory.core.tinker.TinkerProperties;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.FlowRow;
import dev.sculptory.fabric.client.editor.ui.layout.Row;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.CollapsibleSection;
import dev.sculptory.fabric.client.editor.ui.widget.Dropdown;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.ScrollPane;
import dev.sculptory.fabric.client.editor.ui.widget.Slider;
import dev.sculptory.fabric.client.editor.ui.widget.TextInput;
import dev.sculptory.fabric.client.editor.ui.widget.Toggle;
import dev.sculptory.fabric.client.tinker.TinkerController;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The Tinker panel: what the Tinker tool's click opened.
 * <ul>
 *   <li><b>A block:</b> every property as a drop-down, useful ones first (changing one makes it the property the label
 *       shows); sign text, front and back, four lines each with colour and glow, set with "Set text"; and "Apply to all
 *       like it in the selection", which sets the shown property's value on every block of that type in the
 *       selection.</li>
 *   <li><b>An entity:</b> arrows that move it by 1/16 and by 1 block along x, y and z and turn it by 15° (hanging
 *       entities move by whole blocks and do not turn), and per kind: an armor stand's pose (three angles per part),
 *       heading and flags; an item frame's item rotation, Invisible and Fixed; a painting's picture; a display's
 *       translation, rotation, scale, billboard, light and what it shows (the active block, an item, text).</li>
 * </ul>
 * Every change goes through the {@link TinkerController} the services give (the editor's Tinker tool's; one history
 * step each; sliders on release). The panel is built again when what it shows changes, but not while one of its
 * controls has the keyboard or the mouse. Call {@link #refresh()} every frame while it is open.
 */
public final class TinkerWindow {
    /** Where it opens, in UI units. */
    public static final int WIDTH = 190;
    public static final int HEIGHT = 260;
    /** Slider ranges. */
    static final double MAX_OFFSET = 8;
    static final double MAX_SCALE = 8;
    static final double SIXTEENTH = 1.0 / 16;

    /** What the panel needs from the editor. */
    public interface Services {
        /** The controller the panel's changes go through (the Tinker tool's), when there is one. */
        Optional<TinkerController> controller();

        /** What the panel shows: the block or entity last clicked. */
        Optional<TinkerController.Target> panelTarget();

        /**
         * Apply to all like it in the selection: sets the block's shown property to its value on every block of that type
         * in the selection ({@code TinkerTool.applyToSelection}).
         */
        boolean applyToSelection(BlockPos pos);

        StateSpace states();

        /** The editor's active block as a state, or -1. */
        int activeBlockState();

        /** Whether a selection exists (Apply to all needs one). */
        boolean hasSelection();

        /** The painting variant ids the client knows, sorted. */
        List<String> paintingVariants();

        /** The item id of a state's block, or "" when it has none. */
        String itemOf(int state);
    }

    private final Services services;
    private final Supplier<UiContext> ui;
    private final Translator tr;
    private final Column content = new Column();
    private final Node root;
    private final Map<String, Boolean> expanded = new HashMap<>();
    /** What the content was built for; built again when this changes. */
    private Object builtFor;

    public TinkerWindow(Services services, Supplier<UiContext> ui, Translator translator) {
        this.services = Objects.requireNonNull(services);
        this.ui = Objects.requireNonNull(ui);
        this.tr = Objects.requireNonNull(translator);
        content.setGap(4);
        root = new ScrollPane(content);
        refresh();
    }

    public Node node() {
        return root;
    }

    /** Builds the content again when what it shows changed, unless one of its controls is in use. */
    public void refresh() {
        Object key = key();
        if (Objects.equals(key, builtFor) || inUse()) return;
        builtFor = key;
        content.clear();
        Optional<TinkerController> controller = services.controller();
        Optional<TinkerController.Target> target = services.panelTarget();
        if (controller.isEmpty() || target.isEmpty()) {
            content.add(wrapped(Label.dim(tr.translate("sculptory.tinker.panel.empty"))));
            return;
        }
        switch (target.get()) {
            case TinkerController.BlockTarget block -> buildBlock(controller.get(), block.pos());
            case TinkerController.EntityTarget entity -> buildEntity(controller.get(), entity);
        }
    }

    /** What the content depends on: the target, and the block's state or the entity's view. */
    private Object key() {
        Optional<TinkerController> known = services.controller();
        Optional<TinkerController.Target> target = services.panelTarget();
        if (known.isEmpty() || target.isEmpty()) return null;
        TinkerController controller = known.get();
        return switch (target.get()) {
            case TinkerController.BlockTarget block -> {
                int state = controller.stateAt(block.pos());
                yield List.of(block.pos(), state, state < 0 ? "" : String.valueOf(controller.chosenProperty(state)),
                        controller.signText(block.pos()).map(Object::toString).orElse(""), services.hasSelection());
            }
            case TinkerController.EntityTarget entity -> List.of(entity.id(),
                    controller.view(entity.id()).map(Object.class::cast).orElse(""));
        };
    }

    /** Whether one of the panel's controls has the keyboard or the mouse (a drag, typing). */
    private boolean inUse() {
        UiContext ctx = ui.get();
        if (ctx == null) return false;
        Node focused = ctx.focused(), captured = ctx.captured();
        return (focused != null && focused.isDescendantOf(root)) || (captured != null && captured.isDescendantOf(root))
                || ctx.popups().isOpen();
    }

    // =================================================================== blocks

    private void buildBlock(TinkerController controller, BlockPos pos) {
        StateSpace states = services.states();
        int state = controller.stateAt(pos);
        if (state < 0) {
            content.add(wrapped(Label.dim(tr.translate("sculptory.tinker.panel.empty"))));
            return;
        }
        content.add(wrapped(Label.heading(tr.translate("sculptory.tinker.panel.block", controller.blockLabel(pos)
                .split(" · ")[0], pos.x() + ", " + pos.y() + ", " + pos.z()))));
        List<String> properties = TinkerProperties.of(states, state);
        String chosen = controller.chosenProperty(state);
        if (properties.isEmpty()) content.add(wrapped(Label.dim(tr.translate("sculptory.tinker.no_properties"))));
        for (String property : properties) {
            List<String> values = states.propertyValues(state, property);
            String current = states.describe(state).get(property);
            Label name = Label.of(TinkerProperties.shown(property));
            if (property.equals(chosen)) name.setStyle(Label.Style.HEADING);
            Dropdown<String> choices = new Dropdown<>(values, current, TinkerProperties::shown, value -> {
                controller.chooseProperty(state, property);
                int next = states.withProperty(controller.stateAt(pos), property, value);
                if (next >= 0) controller.setState(pos, next);
            });
            choices.setGrow(1);
            name.setFixedWidth(62);
            content.add(Row.of(name, choices));
        }
        controller.signText(pos).ifPresent(text -> content.add(signSection(controller, pos, text)));
        if (chosen != null) {
            String change = TinkerProperties.shown(chosen, states.describe(state).get(chosen));
            Label what = Label.dim(tr.translate("sculptory.tinker.panel.apply_what", change));
            what.setWrap(true);
            Button apply = new Button(tr.translate("sculptory.tinker.panel.apply_all"),
                    () -> services.applyToSelection(pos));
            apply.setTooltip(tr.translate("sculptory.tinker.panel.apply_all.tooltip", change,
                    controller.blockLabel(pos).split(" · ")[0]));
            apply.setEnabled(services.hasSelection());
            content.add(what, FlowRow.of(grow(apply)));
        }
    }

    private Node signSection(TinkerController controller, BlockPos pos, SignText text) {
        SignText[] edited = {text};
        Column column = new Column();
        column.setGap(3);
        for (boolean front : new boolean[] {true, false}) {
            column.add(Label.dim(tr.translate(front ? "sculptory.tinker.sign.front" : "sculptory.tinker.sign.back")));
            SignText.Side side = text.side(front);
            for (int i = 0; i < SignText.LINES; i++) {
                int line = i;
                TextInput input = new TextInput(side.lines().get(i), typed -> edited[0] = edited[0].withSide(front,
                        edited[0].side(front).withLine(line, typed)));
                input.setMaxLength(SignText.MAX_LINE_CHARS);
                column.add(input);
            }
            Dropdown<String> colour = new Dropdown<>(SignText.COLORS, side.color(),
                    name -> tr.translate("color.minecraft." + name),
                    picked -> edited[0] = edited[0].withSide(front, edited[0].side(front).withColor(picked)));
            colour.setGrow(1);
            Toggle glow = new Toggle(tr.translate("sculptory.tinker.sign.glow"), side.glowing(),
                    on -> edited[0] = edited[0].withSide(front, edited[0].side(front).withGlowing(on)));
            column.add(Row.of(colour, glow));
        }
        Button set = new Button(tr.translate("sculptory.tinker.sign.set"), () -> controller.setSign(pos, edited[0]));
        set.setStyle(Button.Style.PRIMARY);
        column.add(FlowRow.of(grow(set)));
        return section("sign", tr.translate("sculptory.tinker.sign"), column, true);
    }

    // =================================================================== entities

    private void buildEntity(TinkerController controller, TinkerController.EntityTarget entity) {
        content.add(wrapped(Label.heading(entity.name())));
        Optional<EntityView> known = controller.view(entity.id());
        if (known.isEmpty()) {
            content.add(wrapped(Label.dim(tr.translate("sculptory.tinker.panel.loading"))));
            return;
        }
        EntityView view = known.get();
        UUID id = entity.id();
        content.add(section("move", tr.translate("sculptory.tinker.move"), moveArrows(controller, id, view), true));
        switch (view.kind()) {
            case ARMOR_STAND -> buildArmorStand(controller, id, view);
            case ITEM_FRAME, GLOW_ITEM_FRAME -> buildItemFrame(controller, id, view);
            case PAINTING -> buildPainting(controller, id, view);
            case BLOCK_DISPLAY, ITEM_DISPLAY, TEXT_DISPLAY -> buildDisplay(controller, id, view);
        }
    }

    /** x, y and z each by -1, -1/16, +1/16 and +1 (hanging entities: whole blocks), and a turn of ±15°. */
    private Node moveArrows(TinkerController controller, UUID id, EntityView view) {
        Column column = new Column();
        column.setGap(2);
        boolean hanging = view.kind().hanging();
        String[] axes = {"x", "y", "z"};
        for (int axis = 0; axis < 3; axis++) {
            int a = axis;
            Label name = Label.of(axes[axis]);
            name.setFixedWidth(10);
            List<Node> row = new ArrayList<>(List.of(name));
            for (double step : new double[] {-1, -SIXTEENTH, SIXTEENTH, 1}) {
                String text = (step < 0 ? "−" : "+") + (Math.abs(step) == 1 ? "1" : "1/16");
                Button button = new Button(text, () -> {
                    // From the move on its way, if any: two quick clicks are two steps.
                    double[] at = controller.positionOf(id, new double[] {view.x(), view.y(), view.z()});
                    at[a] += step;
                    controller.edit(id, List.of(new EntityEdit.Position(at[0], at[1], at[2])));
                });
                button.setTooltip(tr.translate("sculptory.tinker.move.tooltip", axes[a], text));
                button.setEnabled(!hanging || Math.abs(step) == 1);
                row.add(grow(button));
            }
            column.add(Row.of(row.toArray(Node[]::new)));
        }
        if (view.kind().turns()) {
            Button left = new Button(tr.translate("sculptory.tinker.turn_left"), () -> turn(controller, id, view, -15));
            Button right = new Button(tr.translate("sculptory.tinker.turn_right"), () -> turn(controller, id, view, 15));
            column.add(FlowRow.of(grow(left), grow(right)));
        }
        return column;
    }

    private static void turn(TinkerController controller, UUID id, EntityView view, int degrees) {
        float yaw = controller.yawOf(id, view.yaw());
        controller.edit(id, List.of(new EntityEdit.Yaw(dev.sculptory.core.tinker.EntityEdits.wrapDegrees(yaw + degrees))));
    }

    private void buildArmorStand(TinkerController controller, UUID id, EntityView view) {
        Column pose = new Column();
        pose.setGap(2);
        for (EntityEdit.Part part : EntityEdit.Part.values()) {
            float[] angles = view.pose(part);
            pose.add(Label.dim(tr.translate("sculptory.tinker.pose." + part.name().toLowerCase(java.util.Locale.ROOT))));
            String[] names = {"x", "y", "z"};
            float[] edited = angles.clone();
            for (int i = 0; i < 3; i++) {
                int index = i;
                Slider slider = Slider.ofInt(names[i], -180, 180, Math.round(wrap(angles[i])), v -> edited[index] = v);
                slider.setOnRelease(v -> controller.edit(id, List.of(new EntityEdit.Pose(part, edited[0], edited[1],
                        edited[2]))));
                pose.add(slider);
            }
        }
        content.add(section("pose", tr.translate("sculptory.tinker.pose"), pose, false));
        content.add(yawSlider(controller, id, view));
        for (EntityEdit.Flag flag : List.of(EntityEdit.Flag.SMALL, EntityEdit.Flag.SHOW_ARMS, EntityEdit.Flag.NO_BASE_PLATE,
                EntityEdit.Flag.INVISIBLE, EntityEdit.Flag.NO_GRAVITY)) {
            content.add(flagToggle(controller, id, view, flag));
        }
    }

    private void buildItemFrame(TinkerController controller, UUID id, EntityView view) {
        List<Integer> steps = List.of(0, 1, 2, 3, 4, 5, 6, 7);
        Dropdown<Integer> rotation = new Dropdown<>(steps, view.itemRotation(), step -> (step * 45) + "°",
                step -> controller.edit(id, List.of(new EntityEdit.ItemRotation(step))));
        rotation.setGrow(1);
        Label name = Label.of(tr.translate("sculptory.tinker.item_rotation"));
        content.add(Row.of(name, rotation));
        content.add(flagToggle(controller, id, view, EntityEdit.Flag.INVISIBLE));
        content.add(flagToggle(controller, id, view, EntityEdit.Flag.FIXED));
    }

    private void buildPainting(TinkerController controller, UUID id, EntityView view) {
        List<String> variants = new ArrayList<>(services.paintingVariants());
        if (!view.variant().isEmpty() && !variants.contains(view.variant())) variants.add(0, view.variant());
        if (variants.isEmpty()) return;
        Dropdown<String> picture = new Dropdown<>(variants, view.variant().isEmpty() ? variants.get(0) : view.variant(),
                variant -> TinkerProperties.shown(variant.substring(variant.indexOf(':') + 1)),
                variant -> controller.edit(id, List.of(new EntityEdit.PaintingVariant(variant))));
        picture.setGrow(1);
        content.add(Row.of(Label.of(tr.translate("sculptory.tinker.painting")), picture));
    }

    private void buildDisplay(TinkerController controller, UUID id, EntityView view) {
        float[] translation = view.translation(), scale = view.scale();
        float[] angles = DisplayRotation.toEuler(view.rotation());
        Column transform = new Column();
        transform.setGap(2);
        Runnable send = () -> controller.edit(id, List.of(new EntityEdit.Transformation(translation.clone(),
                DisplayRotation.fromEuler(angles[0], angles[1], angles[2]), scale.clone())));
        String[] axes = {"x", "y", "z"};
        transform.add(Label.dim(tr.translate("sculptory.tinker.translation")));
        for (int i = 0; i < 3; i++) {
            int index = i;
            Slider slider = Slider.ofDecimal(axes[i], -MAX_OFFSET, MAX_OFFSET, SIXTEENTH,
                    clamp(translation[i], MAX_OFFSET), v -> translation[index] = (float) v);
            slider.setOnRelease(v -> send.run());
            transform.add(slider);
        }
        transform.add(Label.dim(tr.translate("sculptory.tinker.rotation")));
        String[] rotations = {tr.translate("sculptory.tinker.yaw"), tr.translate("sculptory.tinker.pitch"),
                tr.translate("sculptory.tinker.roll")};
        for (int i = 0; i < 3; i++) {
            int index = i;
            Slider slider = Slider.ofInt(rotations[i], -180, 180, Math.round(angles[i]), v -> angles[index] = v);
            slider.setOnRelease(v -> send.run());
            transform.add(slider);
        }
        transform.add(Label.dim(tr.translate("sculptory.tinker.scale")));
        for (int i = 0; i < 3; i++) {
            int index = i;
            Slider slider = Slider.ofDecimal(axes[i], -MAX_SCALE, MAX_SCALE, SIXTEENTH, clamp(scale[i], MAX_SCALE),
                    v -> scale[index] = (float) v);
            slider.setOnRelease(v -> send.run());
            transform.add(slider);
        }
        content.add(section("transform", tr.translate("sculptory.tinker.transformation"), transform, true));
        content.add(yawSlider(controller, id, view));
        Dropdown<EntityEdit.Billboard> billboard = new Dropdown<>(List.of(EntityEdit.Billboard.values()),
                view.billboard(), mode -> tr.translate("sculptory.tinker.billboard." + mode.nbtName()),
                mode -> controller.edit(id, List.of(new EntityEdit.BillboardMode(mode))));
        billboard.setGrow(1);
        content.add(Row.of(Label.of(tr.translate("sculptory.tinker.billboard")), billboard));
        content.add(lightControls(controller, id, view));
        switch (view.kind()) {
            case BLOCK_DISPLAY -> {
                content.add(wrapped(Label.dim(tr.translate("sculptory.tinker.shows", view.blockState()))));
                Button active = new Button(tr.translate("sculptory.tinker.use_active_block"), () -> {
                    int state = services.activeBlockState();
                    if (state >= 0) controller.edit(id, List.of(new EntityEdit.DisplayBlock(state)));
                });
                active.setEnabled(services.activeBlockState() >= 0);
                content.add(FlowRow.of(grow(active)));
            }
            case ITEM_DISPLAY -> {
                String[] typed = {view.item()};
                TextInput item = new TextInput(view.item(), text -> typed[0] = text.trim());
                item.setPlaceholder("minecraft:diamond_sword");
                Button set = new Button(tr.translate("sculptory.tinker.set_item"), () -> {
                    try {
                        controller.edit(id, List.of(new EntityEdit.DisplayItem(typed[0])));
                    } catch (IllegalArgumentException notAnId) {
                        // Not an id: nothing is sent.
                    }
                });
                Button active = new Button(tr.translate("sculptory.tinker.use_active_block"), () -> {
                    int state = services.activeBlockState();
                    String itemId = state < 0 ? "" : services.itemOf(state);
                    if (!itemId.isEmpty()) controller.edit(id, List.of(new EntityEdit.DisplayItem(itemId)));
                });
                content.add(item, FlowRow.of(grow(set), grow(active)));
            }
            case TEXT_DISPLAY -> {
                List<String> lines = new ArrayList<>(List.of(view.text().split("\n", -1)));
                while (lines.size() < 4) lines.add("");
                Column text = new Column();
                text.setGap(2);
                for (int i = 0; i < lines.size() && i < EntityEdit.MAX_TEXT_LINES; i++) {
                    int line = i;
                    TextInput input = new TextInput(lines.get(i), typed -> lines.set(line, typed));
                    input.setMaxLength(SignText.MAX_LINE_CHARS);
                    text.add(input);
                }
                Button set = new Button(tr.translate("sculptory.tinker.set_text"), () -> {
                    List<String> kept = new ArrayList<>(lines);
                    while (kept.size() > 1 && kept.get(kept.size() - 1).isEmpty()) kept.remove(kept.size() - 1);
                    controller.edit(id, List.of(new EntityEdit.DisplayText(String.join("\n", kept))));
                });
                set.setStyle(Button.Style.PRIMARY);
                text.add(FlowRow.of(grow(set)));
                content.add(section("text", tr.translate("sculptory.tinker.text"), text, true));
            }
            default -> { }
        }
    }

    private Node lightControls(TinkerController controller, UUID id, EntityView view) {
        EntityEdit.Brightness now = view.brightness();
        int[] light = {now.auto() ? 15 : now.block(), now.auto() ? 15 : now.sky()};
        boolean[] fixed = {!now.auto()};
        Runnable send = () -> controller.edit(id, List.of(fixed[0] ? new EntityEdit.Brightness(light[0], light[1])
                : EntityEdit.Brightness.AUTO));
        Column column = new Column();
        column.setGap(2);
        column.add(new Toggle(tr.translate("sculptory.tinker.fixed_light"), fixed[0], on -> {
            fixed[0] = on;
            send.run();
        }));
        Slider block = Slider.ofInt(tr.translate("sculptory.tinker.block_light"), 0, 15, light[0], v -> light[0] = v);
        block.setOnRelease(v -> {
            if (fixed[0]) send.run();
        });
        Slider sky = Slider.ofInt(tr.translate("sculptory.tinker.sky_light"), 0, 15, light[1], v -> light[1] = v);
        sky.setOnRelease(v -> {
            if (fixed[0]) send.run();
        });
        column.add(block, sky);
        return column;
    }

    private Node yawSlider(TinkerController controller, UUID id, EntityView view) {
        Slider yaw = Slider.ofInt(tr.translate("sculptory.tinker.facing_slider"), -180, 180, Math.round(wrap(view.yaw())),
                null);
        yaw.setOnRelease(v -> controller.edit(id, List.of(new EntityEdit.Yaw((float) v))));
        return yaw;
    }

    private Node flagToggle(TinkerController controller, UUID id, EntityView view, EntityEdit.Flag flag) {
        return new Toggle(tr.translate("sculptory.tinker.flag." + flag.name().toLowerCase(java.util.Locale.ROOT)),
                view.flag(flag), on -> controller.edit(id, List.of(new EntityEdit.Toggle(flag, on))));
    }

    // =================================================================== helpers

    private Node section(String id, String title, Node body, boolean open) {
        CollapsibleSection section = new CollapsibleSection(title, body, expanded.getOrDefault(id, open));
        section.setOnToggle(on -> expanded.put(id, on));
        return section;
    }

    private static Label wrapped(Label label) {
        label.setWrap(true);
        return label;
    }

    private static <T extends Node> T grow(T node) {
        node.setGrow(1);
        return node;
    }

    private static float wrap(float degrees) {
        return dev.sculptory.core.tinker.EntityEdits.wrapDegrees(degrees);
    }

    private static double clamp(double value, double limit) {
        return Math.max(-limit, Math.min(limit, value));
    }

    /** Whether the panel's content is built for a target (tests). */
    boolean showsTarget() {
        return builtFor != null;
    }
}
