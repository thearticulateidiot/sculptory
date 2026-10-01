package dev.sculptory.fabric.client.builder;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.protocol.v2.BuilderPower;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

/**
 * The ring of powers, shown while the ring key is held. The world stays
 * visible and the game does not pause; the cursor is free. Each power is a chip on the ring (blue when on, dark when
 * off, greyed when unavailable); the one under the pointer is outlined and named in the middle with what it does. A
 * left-click on a chip toggles it and keeps the ring open; releasing the key applies {@link RingMenu#release} and
 * closes the ring; Esc closes it without a change. Thin: the geometry and timing are {@link RingMenu}'s.
 */
public final class RingScreen extends Screen {
    static final String TITLE = "sculptory.builder.title";
    static final String HINT = "sculptory.builder.hint";
    private static final int ON = 0xFF4C8DFF;
    private static final int OFF = 0xE02A2F3A;
    private static final int UNAVAILABLE = 0xC022262E;
    private static final int OUTLINE = 0xFF7AA7FF;
    private static final int TEXT = 0xFFE8EBF2;
    private static final int TEXT_DIM = 0xFF8C95A8;
    private static final int TEXT_DISABLED = 0xFF596070;
    private static final int PLATE = 0xB0101216;

    private final BuilderClient owner;
    private final RingMenu menu;
    private final Translator translator;
    private final KeyBinding ringKey;
    private boolean released;

    RingScreen(BuilderClient owner, RingMenu menu, Translator translator, KeyBinding ringKey) {
        super(Text.translatable(TITLE));
        this.owner = Objects.requireNonNull(owner);
        this.menu = Objects.requireNonNull(menu);
        this.translator = Objects.requireNonNull(translator);
        this.ringKey = Objects.requireNonNull(ringKey);
    }

    RingMenu menu() {
        return menu;
    }

    // ---- Dev only (the scripted demo): where things are, as the last frame laid them out ----

    public double menuCentreX() {
        return menu.centreX();
    }

    public double menuCentreY() {
        return menu.centreY();
    }

    /** The middle of a power's chip (screen units). */
    public double[] chipCentre(BuilderPower power) {
        int index = menu.entries().indexOf(power);
        if (index < 0) throw new IllegalArgumentException("Not on the ring: " + power);
        return menu.labelCentre(index);
    }

    @Override
    public void tick() {
        // A release the screen never heard (a tap shorter than a tick) still closes the ring.
        if (!released && !owner.ringKeyDown()) release();
    }

    private void release() {
        if (released) return;
        released = true;
        owner.ringReleased(menu.release(Util.getMeasuringTimeMs()));
        close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        PowerSet powers = owner.powers();
        List<String> names = new ArrayList<>(menu.entries().size());
        for (BuilderPower power : menu.entries()) names.add(translator.translate(BuilderClient.nameKey(power)));
        // The plate's text: the hovered power's name and what it does, else the title and what a release does.
        String title;
        String detail;
        int detailColour = TEXT_DIM;
        if (menu.hoveredPower().isPresent()) {
            BuilderPower power = menu.hoveredPower().get();
            title = names.get(menu.hovered());
            String unavailable = powers.unavailableKey(power);
            detail = unavailable != null ? translator.translate(unavailable)
                    : translator.translate(BuilderClient.descriptionKey(power));
            if (unavailable != null) detailColour = TEXT_DISABLED;
        } else {
            title = translator.translate(TITLE);
            detail = translator.translate(HINT, translator.translate(BuilderClient.nameKey(powers.last())));
        }
        // Widest plate first, so the ring does not shift while the pointer moves between chips and the middle.
        RingLayout layout = new RingLayout(menu, names, title, detail, textRenderer::getWidth, textRenderer.fontHeight);
        menu.pointer(mouseX, mouseY);
        for (int i = 0; i < names.size(); i++) {
            BuilderPower power = menu.entries().get(i);
            Rect chip = layout.chip(i);
            boolean available = powers.available(power);
            int fill = !available ? UNAVAILABLE : powers.on(power) ? ON : OFF;
            context.fill(chip.x(), chip.y(), chip.right(), chip.bottom(), fill);
            if (i == menu.hovered()) {
                context.fill(chip.x() - 1, chip.y() - 1, chip.right() + 1, chip.y(), OUTLINE);
                context.fill(chip.x() - 1, chip.bottom(), chip.right() + 1, chip.bottom() + 1, OUTLINE);
                context.fill(chip.x() - 1, chip.y(), chip.x(), chip.bottom(), OUTLINE);
                context.fill(chip.right(), chip.y(), chip.right() + 1, chip.bottom(), OUTLINE);
            }
            context.drawText(textRenderer, names.get(i), chip.x() + 4, chip.y() + 3, available ? TEXT : TEXT_DISABLED, false);
        }
        Rect plate = layout.plate();
        context.fill(plate.x(), plate.y(), plate.right(), plate.bottom(), PLATE);
        int cx = (int) Math.round(menu.centreX());
        int y = plate.y() + 5;
        for (int i = 0; i < layout.lines().size(); i++) {
            context.drawCenteredTextWithShadow(textRenderer, layout.lines().get(i), cx, y, i == 0 ? TEXT : detailColour);
            y += textRenderer.fontHeight + RingLayout.LINE_GAP;
        }
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // The world stays fully visible behind the ring.
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        menu.pointer(mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        menu.pointer(mouseX, mouseY);
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            menu.hoveredPower().ifPresent(owner::toggle);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (ringKey.matchesKey(keyCode, scanCode)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (ringKey.matchesKey(keyCode, scanCode)) {
            release();
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
