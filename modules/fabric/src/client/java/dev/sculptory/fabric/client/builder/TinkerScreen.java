package dev.sculptory.fabric.client.builder;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.tinker.EntityEdit;
import dev.sculptory.core.tinker.TinkerProperties;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.tinker.TinkerController;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.text.Text;

/**
 * Builder mode's Tinker panel ("Builder mode"): the editor's {@code TinkerWindow}
 * needs the editor UI, so outside the editor a small vanilla screen offers the same controller calls. A block: one
 * cycling button per property, useful ones first (a change is one history step and makes that the shown property).
 * An entity: arrows that move it by 1/16 and by 1 block along x, y and z (hanging entities by whole blocks only) and
 * turn it by 15° where it turns; the server's view is asked for on opening. Sign text and an armor stand's pose stay
 * with the editor's Tinker tool. Rebuilt ({@link #refresh}) whenever the controller reports a change.
 */
final class TinkerScreen extends Screen {
    static final String TITLE = "sculptory.builder.tinker.title";
    static final String SIGN_HINT = "sculptory.builder.tinker.sign_hint";
    static final int WIDTH = 200;
    static final int ROW = 22;
    private static final double SIXTEENTH = 1.0 / 16;
    private static final float TURN = TinkerController.TURN_STEP;

    private final TinkerController controller;
    private final TinkerController.Target target;
    private final StateSpace states;
    private final Translator tr;
    private String heading = "";

    TinkerScreen(TinkerController controller, TinkerController.Target target, StateSpace states, Translator translator) {
        super(Text.translatable(TITLE));
        this.controller = Objects.requireNonNull(controller);
        this.target = Objects.requireNonNull(target);
        this.states = Objects.requireNonNull(states);
        this.tr = Objects.requireNonNull(translator);
        if (target instanceof TinkerController.EntityTarget entity) controller.look(entity.id());
    }

    /** What the panel shows. */
    TinkerController.Target target() {
        return target;
    }

    /** Builds the controls again (the controller reported a change). */
    void refresh() {
        clearAndInit();
    }

    @Override
    protected void init() {
        int x = (width - WIDTH) / 2;
        int y = 40;
        switch (target) {
            case TinkerController.BlockTarget block -> y = blockControls(block.pos(), x, y);
            case TinkerController.EntityTarget entity -> y = entityControls(entity, x, y);
        }
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), b -> close())
                .dimensions(x, y + 6, WIDTH, 20).build());
    }

    private int blockControls(BlockPos pos, int x, int y) {
        int state = controller.stateAt(pos);
        heading = controller.blockLabel(pos);
        if (state < 0) return y;
        for (String property : TinkerProperties.of(states, state)) {
            String current = states.describe(state).get(property);
            List<String> values = states.propertyValues(state, property);
            if (current == null || values.size() < 2) continue;
            addDrawableChild(CyclingButtonWidget.<String>builder(value -> Text.literal(TinkerProperties.shown(value)))
                    .values(values).initially(current)
                    .build(x, y, WIDTH, 20, Text.literal(TinkerProperties.shown(property)),
                            (button, value) -> change(pos, property, value)));
            y += ROW;
        }
        if (states.describe(state).block().value().endsWith("sign")) {
            addDrawableChild(ButtonWidget.builder(Text.literal(tr.translate(SIGN_HINT)), b -> { })
                    .dimensions(x, y, WIDTH, 20).build()).active = false;
            y += ROW;
        }
        return y;
    }

    private void change(BlockPos pos, String property, String value) {
        int state = controller.stateAt(pos);
        if (state < 0) return;
        int next = states.withProperty(state, property, value);
        if (next < 0 || next == state) return;
        controller.chooseProperty(state, property);
        controller.setState(pos, next);
    }

    private int entityControls(TinkerController.EntityTarget entity, int x, int y) {
        heading = controller.entityLabel(entity);
        boolean whole = entity.kind().hanging();
        int quarter = WIDTH / 4;
        for (int axis = 0; axis < 3; axis++) {
            int a = axis;
            String name = "xyz".substring(axis, axis + 1);
            ButtonWidget minusOne = ButtonWidget.builder(Text.literal(name + " −1"), b -> move(entity, a, -1))
                    .dimensions(x, y, quarter - 2, 20).build();
            ButtonWidget minusSixteenth = ButtonWidget.builder(Text.literal(name + " −¹⁄₁₆"), b -> move(entity, a, -SIXTEENTH))
                    .dimensions(x + quarter, y, quarter - 2, 20).build();
            ButtonWidget plusSixteenth = ButtonWidget.builder(Text.literal(name + " +¹⁄₁₆"), b -> move(entity, a, SIXTEENTH))
                    .dimensions(x + 2 * quarter, y, quarter - 2, 20).build();
            ButtonWidget plusOne = ButtonWidget.builder(Text.literal(name + " +1"), b -> move(entity, a, 1))
                    .dimensions(x + 3 * quarter, y, quarter - 2, 20).build();
            minusSixteenth.active = !whole;
            plusSixteenth.active = !whole;
            addDrawableChild(minusOne);
            addDrawableChild(minusSixteenth);
            addDrawableChild(plusSixteenth);
            addDrawableChild(plusOne);
            y += ROW;
        }
        if (entity.kind().turns()) {
            addDrawableChild(ButtonWidget.builder(Text.literal(tr.translate("sculptory.tinker.turn_left")),
                    b -> turn(entity, -TURN)).dimensions(x, y, WIDTH / 2 - 2, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal(tr.translate("sculptory.tinker.turn_right")),
                    b -> turn(entity, TURN)).dimensions(x + WIDTH / 2, y, WIDTH / 2 - 2, 20).build());
            y += ROW;
        }
        return y;
    }

    private void move(TinkerController.EntityTarget entity, int axis, double by) {
        double[] box = entity.box();
        double[] fallback = {(box[0] + box[3]) / 2, box[1], (box[2] + box[5]) / 2};
        double[] at = controller.positionOf(entity.id(), fallback).clone();
        at[axis] += by;
        controller.edit(entity.id(), List.of(new EntityEdit.Position(at[0], at[1], at[2])));
    }

    private void turn(TinkerController.EntityTarget entity, float by) {
        float yaw = controller.yawOf(entity.id(), entity.yaw()) + by;
        while (yaw > 180) yaw -= 360;
        while (yaw < -180) yaw += 360;
        controller.edit(entity.id(), List.of(new EntityEdit.Yaw(yaw)));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, tr.translate(TITLE), width / 2, 12, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer, heading, width / 2, 24, 0xFFD9DEE8);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
