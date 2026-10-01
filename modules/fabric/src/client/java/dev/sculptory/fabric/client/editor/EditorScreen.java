package dev.sculptory.fabric.client.editor;

import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * The editor's single overlay screen. It doesn't pause, draws no background (no blur, no dimming)
 * and ignores vanilla's close-on-Esc; being a screen, it frees the cursor and keeps vanilla from
 * attacking, using, picking, switching hotbar slots or pausing. Every input goes to the
 * {@code InputRouter}; drawing goes to the editor UI. Kept thin: all behaviour lives in pure
 * classes behind {@link EditorClient}.
 */
public final class EditorScreen extends Screen {
    private final EditorClient editor;

    EditorScreen(EditorClient editor) {
        super(Text.translatable("sculptory.editor.title"));
        this.editor = editor;
    }

    @Override
    protected void init() {
        editor.layoutScreen(width, height);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        editor.renderScreen(context, mouseX, mouseY);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // The world stays fully visible behind the editor.
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    /** Anything asking this screen to close (rather than Esc, which the router handles) leaves the editor. */
    @Override
    public void close() {
        editor.closeRequested();
    }

    @Override
    public void removed() {
        editor.screenRemoved();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return editor.router().mouseClicked(mouseX, mouseY, button, editor.liveModifiers());
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        editor.liveModifiers();
        return editor.router().mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        editor.liveModifiers();
        return editor.router().mouseDragged(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return editor.router().mouseScrolled(mouseX, mouseY, verticalAmount, editor.liveModifiers());
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (editor.pointerPinned()) {
            return; // the screenshot tour holds the pointer
        }
        editor.liveModifiers();
        editor.router().mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        editor.setModifiers(modifiers);
        return editor.router().keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        editor.liveModifiers();
        return editor.router().keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        return editor.router().charTyped(chr, modifiers);
    }

    /** Files dropped on the game window while the editor is open: schematics (.schem, .litematic, .nbt) are imported. */
    @Override
    public void filesDragged(List<Path> paths) {
        editor.filesDragged(paths);
    }
}
