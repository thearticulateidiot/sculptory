package dev.sculptory.fabric.client.editor.tools.brush;

import dev.sculptory.fabric.client.editor.brush.BrushPredictor;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.input.KeyChord;
import dev.sculptory.fabric.client.editor.render.BrushCursor;
import dev.sculptory.fabric.client.editor.world.Ray;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the brush tools need beyond {@code ToolContext}: the client-world prediction target, the brush
 * cursor overlay, a block-change stamp for the cursor's surface cache, window focus, the keymap,
 * stroke seeds and the cursor ray. The game implementation is {@link McBrushServices}; {@link #headless()} runs without
 * Minecraft (no prediction, no overlay) for tests and tool sets built without the game.
 */
public interface BrushServices {
    /** Where predicted cells go, or {@code null} when nothing can be predicted. */
    BrushPredictor.Target predictionTarget();

    /** Shows the brush cursor (every frame while the cursor is on the terrain). */
    void showCursor(BrushCursor cursor);

    void clearCursor();

    /** Changes whenever client blocks may have changed; keys the cursor's surface cache. */
    long changeStamp();

    /** False while the game window does not have focus: a stroke in progress ends. */
    boolean windowFocused();

    /** The keymap action a scroll with {@code modifiers} triggers (tool size or strength), if any. */
    Optional<KeyAction> scrollAction(int modifiers);

    /** How the keymap shows an action's keys, e.g. "Ctrl+Scroll". */
    String keyLabel(KeyAction action);

    /** A seed for one stroke's randomness (Palette Paint's mix). */
    long nextSeed();

    /**
     * The cursor ray of the pick the tool was just handed (the crosshair ray while looking), or empty when there is
     * none (the default, without a game): the Shape brush tells a drag from a click held still by it.
     */
    default Optional<Ray> cursorRay() {
        return Optional.empty();
    }

    /** Services without Minecraft: no prediction or overlay, default keys, counting seeds. */
    static BrushServices headless() {
        return headless(EditorKeymap.defaults());
    }

    static BrushServices headless(EditorKeymap keymap) {
        AtomicLong seeds = new AtomicLong(1);
        return new BrushServices() {
            @Override
            public BrushPredictor.Target predictionTarget() {
                return null;
            }

            @Override
            public void showCursor(BrushCursor cursor) {}

            @Override
            public void clearCursor() {}

            @Override
            public long changeStamp() {
                return 0;
            }

            @Override
            public boolean windowFocused() {
                return true;
            }

            @Override
            public Optional<KeyAction> scrollAction(int modifiers) {
                return keymap.match(KeyChord.scroll(modifiers));
            }

            @Override
            public String keyLabel(KeyAction action) {
                return keymap.display(action);
            }

            @Override
            public long nextSeed() {
                return seeds.getAndIncrement();
            }
        };
    }
}
