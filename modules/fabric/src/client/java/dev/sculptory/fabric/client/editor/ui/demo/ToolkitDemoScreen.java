package dev.sculptory.fabric.client.editor.ui.demo;

import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.Row;
import dev.sculptory.fabric.client.editor.ui.layout.Spacer;
import dev.sculptory.fabric.client.editor.ui.render.DrawContextGraphics;
import dev.sculptory.fabric.client.editor.ui.render.MinecraftInput;
import dev.sculptory.fabric.client.editor.ui.render.MinecraftTextMeasure;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.CollapsibleSection;
import dev.sculptory.fabric.client.editor.ui.widget.Dropdown;
import dev.sculptory.fabric.client.editor.ui.widget.IconButton;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.ListView;
import dev.sculptory.fabric.client.editor.ui.widget.ProgressBar;
import dev.sculptory.fabric.client.editor.ui.widget.ScrollPane;
import dev.sculptory.fabric.client.editor.ui.widget.Slider;
import dev.sculptory.fabric.client.editor.ui.widget.TextInput;
import dev.sculptory.fabric.client.editor.ui.widget.Toggle;
import dev.sculptory.fabric.client.editor.ui.window.Corner;
import dev.sculptory.fabric.client.editor.ui.window.WindowManager;
import dev.sculptory.fabric.client.editor.ui.window.WindowSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

/**
 * Preview of the UI toolkit: two sample windows using most widgets. Not registered anywhere;
 * open it with {@code MinecraftClient.getInstance().setScreen(new ToolkitDemoScreen())}.
 * Tab hides the windows; Esc closes the screen once no popup or focus is left to dismiss.
 */
public final class ToolkitDemoScreen extends Screen {
    private static final Map<String, String> TITLES = Map.of(
            "sculptory.demo.brush", "Brush",
            "sculptory.demo.library", "Library");

    private final List<String> assets = sampleAssets();
    private WindowManager windows;
    private ProgressBar progress;
    private ListView<String> assetList;
    private Label status;
    private long startMs = -1;

    public ToolkitDemoScreen() {
        super(Text.literal("Sculptory UI toolkit demo"));
    }

    @Override
    protected void init() {
        if (windows == null) {
            windows = new WindowManager(new MinecraftTextMeasure(textRenderer), Theme.DARK, ToolkitDemoScreen::title);
            windows.register(WindowSpec.builder("demo_brush", "sculptory.demo.brush", this::brushContent)
                    .anchor(Corner.TOP_LEFT, 8, 8)
                    .size(190, 250)
                    .minSize(140, 80)
                    .build());
            windows.register(WindowSpec.builder("demo_library", "sculptory.demo.library", this::libraryContent)
                    .anchor(Corner.TOP_RIGHT, 8, 8)
                    .size(170, 220)
                    .minSize(120, 90)
                    .build());
        }
        windows.layout(width, height);
    }

    private static String title(String key) {
        String literal = TITLES.get(key);
        return literal != null ? literal : I18n.translate(key);
    }

    private Node brushContent() {
        Item[] items = {Items.GRASS_BLOCK, Items.STONE, Items.OAK_SAPLING, Items.DIAMOND_PICKAXE};
        String[] names = {"Grass block", "Stone", "Oak sapling", "Pickaxe"};
        Row palette = new Row();
        palette.setGap(2);
        List<IconButton> buttons = new ArrayList<>();
        for (int i = 0; i < items.length; i++) {
            IconButton button = new IconButton(new ItemStack(items[i]), null);
            button.setTooltip(names[i]);
            buttons.add(button);
            palette.add(button);
        }
        for (IconButton button : buttons) {
            button.setOnClick(() -> buttons.forEach(other -> other.setSelected(other == button)));
        }
        buttons.get(0).setSelected(true);
        buttons.get(3).setEnabled(false);
        buttons.get(3).setTooltip("Pickaxe: you don't have permission to use this tool.");

        Slider radius = Slider.ofInt("Radius", 1, 64, 8, value -> { });
        radius.setTooltip("Brush radius in blocks. Scroll over the slider, or focus it and use the arrow keys, to step.");
        Slider strength = Slider.ofDecimal("Strength", 0, 1, 0.05, 0.5, value -> { });
        Toggle fluids = new Toggle("Affect fluids", false, value -> { });
        Dropdown<String> shape = new Dropdown<>(List.of("Sphere", "Cylinder", "Cube", "Diamond"), "Sphere",
                name -> name, name -> { });
        shape.setGrow(1);

        TextInput seed = new TextInput("12345", text -> { });
        seed.setPlaceholder("Seed");
        seed.setGrow(1);
        Slider falloff = Slider.ofDecimal("Falloff", 0, 1, 0.1, 0.3, value -> { });
        CollapsibleSection advanced = new CollapsibleSection("Advanced",
                Column.of(Row.of(Label.dim("Seed"), seed), falloff), false);

        progress = new ProgressBar();
        progress.setText("Job 0%");
        Button reset = new Button("Reset", () -> {
            radius.setValue(8);
            strength.setValue(0.5);
            fluids.setValue(false);
        });
        Button apply = new Button("Apply", () -> { }).setStyle(Button.Style.PRIMARY);

        return new ScrollPane(Column.of(
                palette,
                radius,
                strength,
                fluids,
                Row.of(Label.dim("Shape"), shape),
                advanced,
                progress,
                Row.of(Spacer.flexible(), reset, apply)));
    }

    private Node libraryContent() {
        TextInput filter = new TextInput("", this::applyFilter);
        filter.setPlaceholder("Search assets");
        assetList = new ListView<>(assets, (name, index) -> new Label(name));
        assetList.setEmptyText("No matches");
        assetList.setGrow(1);
        status = Label.dim(assets.size() + " assets");
        assetList.setOnActivate(index -> status.setText("Placed " + assetList.items().get(index)));
        return Column.of(filter, assetList, status);
    }

    private void applyFilter(String query) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<String> matches = assets.stream().filter(name -> name.contains(needle)).toList();
        assetList.setItems(matches);
        status.setText(matches.size() + " assets");
    }

    private static List<String> sampleAssets() {
        String[] kinds = {"oak_tree", "birch_tree", "boulder", "bush", "fern_patch", "ruin_wall", "stone_arch",
                "flower_bed"};
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            names.add(kinds[i % kinds.length] + "_" + (i / kinds.length + 1));
        }
        return names;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fillGradient(0, 0, width, height, 0x90101014, 0xB0101014);
        long now = Util.getMeasuringTimeMs();
        if (startMs < 0) {
            startMs = now;
        }
        if (progress != null) {
            double fraction = ((now - startMs) % 6000) / 6000.0;
            progress.setProgress(fraction);
            progress.setText("Job " + (int) (fraction * 100) + "%");
        }
        windows.render(new DrawContextGraphics(context, textRenderer), mouseX, mouseY, now);
        context.drawText(textRenderer, "Tab: hide windows   Esc: close", 8, height - 14, 0xFF8C95A8, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return windows.mouseDown(mouseX, mouseY, button, MinecraftInput.modifiers())
                || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return windows.mouseUp(mouseX, mouseY, button) || super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        return windows.mouseDragged(mouseX, mouseY, button)
                || super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return windows.mouseScrolled(mouseX, mouseY, verticalAmount, MinecraftInput.modifiers())
                || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        windows.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (windows.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            windows.setAllHidden(!windows.isAllHidden());
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        return windows.charTyped(chr, modifiers) || super.charTyped(chr, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
