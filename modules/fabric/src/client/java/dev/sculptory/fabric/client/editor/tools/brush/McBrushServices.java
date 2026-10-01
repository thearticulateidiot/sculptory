package dev.sculptory.fabric.client.editor.tools.brush;

import dev.sculptory.fabric.client.world.ClientBlockChanges;
import dev.sculptory.fabric.client.editor.brush.BrushPredictor;
import dev.sculptory.fabric.client.editor.brush.ClientWorldPrediction;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.input.KeyChord;
import dev.sculptory.fabric.client.editor.render.BrushCursor;
import dev.sculptory.fabric.client.editor.render.OverlayRenderer;
import dev.sculptory.fabric.client.editor.world.Ray;
import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.function.Supplier;
import net.minecraft.client.MinecraftClient;

/**
 * {@link BrushServices} in the game: prediction into {@code client.world}, the editor's overlay renderer
 * for the brush cursor, window focus, the editor keymap and the cursor ray. Client thread only.
 */
public final class McBrushServices implements BrushServices {
    private final MinecraftClient client;
    private final OverlayRenderer overlay;
    private final EditorKeymap keymap;
    private final ClientWorldPrediction prediction;
    private final SplittableRandom seeds = new SplittableRandom();
    private final Supplier<Optional<Ray>> cursorRay;

    /** @param cursorRay the ray of the editor's latest cursor pick */
    public McBrushServices(MinecraftClient client, OverlayRenderer overlay, EditorKeymap keymap,
                           Supplier<Optional<Ray>> cursorRay) {
        this.client = Objects.requireNonNull(client);
        this.overlay = Objects.requireNonNull(overlay);
        this.keymap = Objects.requireNonNull(keymap);
        this.cursorRay = Objects.requireNonNull(cursorRay);
        this.prediction = new ClientWorldPrediction(client);
    }

    @Override
    public BrushPredictor.Target predictionTarget() {
        return prediction;
    }

    @Override
    public void showCursor(BrushCursor cursor) {
        overlay.setBrushCursor(cursor);
    }

    @Override
    public void clearCursor() {
        overlay.clearBrushCursor();
    }

    /** Changes whenever client blocks or loaded chunks change, so the cursor's surface is resampled only then. */
    @Override
    public long changeStamp() {
        return ClientBlockChanges.stamp();
    }

    @Override
    public boolean windowFocused() {
        return client.isWindowFocused();
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
        return seeds.nextLong();
    }

    @Override
    public Optional<Ray> cursorRay() {
        return cursorRay.get();
    }
}
