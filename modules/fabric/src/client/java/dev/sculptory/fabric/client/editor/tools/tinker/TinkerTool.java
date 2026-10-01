package dev.sculptory.fabric.client.editor.tools.tinker;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.tinker.SignText;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.DeactivateReason;
import dev.sculptory.fabric.client.editor.tool.FrameInfo;
import dev.sculptory.fabric.client.editor.tool.HudDraw;
import dev.sculptory.fabric.client.editor.tool.KeyHint;
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.RaycastMode;
import dev.sculptory.fabric.client.editor.tool.ScrollEvent;
import dev.sculptory.fabric.client.editor.tool.Tool;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.ToolDescriptor;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.tool.WorldDraw;
import dev.sculptory.fabric.client.editor.tools.select.SelectionActions;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.tinker.TinkerController;
import dev.sculptory.fabric.engine.Perm;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The Tinker tool (palette slot 14, key ]): changes a block or an entity in place.
 * Pointing at a block shows its name and one property beside the cursor; Scroll changes that property's value,
 * Shift+Scroll picks another property (remembered per block type for the session); a click opens the Tinker panel for
 * the block (every property, sign text, Apply to all like it in the selection) or for an armor stand, item frame,
 * painting or display entity. Scroll over an armor stand or display turns it, over an item frame turns its item. The
 * logic is the shared {@link TinkerController} (builder mode drives one the same way, see its javadoc); this tool
 * feeds it the cursor ({@link TinkerController#aim}), passes the wheel and the click on, and draws the label and the
 * outline. Needs {@code region} (as every change does on the server). Client thread only.
 */
public final class TinkerTool implements Tool {
    /** The outline colour (ARGB). */
    public static final int COLOUR = 0xFFE0A030;
    public static final String CLICK = "LMB";

    /** What the tool needs from the game beyond its context. */
    public interface Services {
        /**
         * The entity Tinker changes that the cursor ray meets before it meets {@code cursor}'s block (or anywhere within
         * reach when the ray missed), if any.
         */
        Optional<TinkerController.EntityTarget> entityAt(WorldCursor cursor);

        /** A state's block name as shown ("Oak Stairs"). */
        String blockName(StateSpace states, int state);

        /** The client's sign text at {@code pos}, if the block is a sign. */
        Optional<SignText> signText(BlockPos pos);

        String translate(String key, Object... args);

        /** Opens (or brings to the front) the Tinker panel. */
        void openPanel();

        String keyLabel(KeyAction action);

        default long nanoTime() {
            return System.nanoTime();
        }

        /** Services without Minecraft: no entities, block ids as names, no signs, no panel. */
        static Services headless() {
            return new Services() {
                @Override
                public Optional<TinkerController.EntityTarget> entityAt(WorldCursor cursor) {
                    return Optional.empty();
                }

                @Override
                public String blockName(StateSpace states, int state) {
                    return states.blockId(state).value();
                }

                @Override
                public Optional<SignText> signText(BlockPos pos) {
                    return Optional.empty();
                }

                @Override
                public String translate(String key, Object... args) {
                    return key;
                }

                @Override
                public void openPanel() {}

                @Override
                public String keyLabel(KeyAction action) {
                    return EditorKeymap.defaults().display(action);
                }
            };
        }
    }

    private final ToolDescriptor descriptor = new ToolDescriptor(ToolId.TINKER, "sculptory.tool.tinker",
            "minecraft:debug_stick", Perm.REGION);
    private final Services services;
    private final SelectionActions actions;
    /** This tool's context, also while another tool is active; {@code null}: the last one it was called with. */
    private final Supplier<ToolContext> context;
    private ToolContext last;
    private final TinkerController controller;
    private TinkerController.Target panelTarget;
    private double mouseX;
    private double mouseY;

    /**
     * @param actions the selection operations (Apply to all like it in the selection runs through them)
     * @param context this tool's context, also while another tool is active (the panel may stay open); {@code null}
     *     for the last context the tool was called with (tests)
     */
    public TinkerTool(Services services, SelectionActions actions, Supplier<ToolContext> context) {
        this.services = Objects.requireNonNull(services);
        this.actions = actions;
        this.context = context;
        this.controller = new TinkerController(new Host());
    }

    /** The shared logic (the panel and builder mode drive it too). */
    public TinkerController controller() {
        return controller;
    }

    /** What the panel shows: the block or entity last clicked. */
    public Optional<TinkerController.Target> panelTarget() {
        return Optional.ofNullable(panelTarget);
    }

    /** Shows {@code target} in the panel (entities: asks the server what it shows) and opens the panel. */
    public void openPanel(TinkerController.Target target) {
        panelTarget = Objects.requireNonNull(target);
        if (target instanceof TinkerController.EntityTarget entity) controller.look(entity.id());
        services.openPanel();
    }

    /**
     * Apply to all like it in the selection: sets the block's shown property to its value on every block of that type
     * in the selection, as one region op (the usual size limits, confirmation and progress). False (after a toast) when
     * there is nothing to apply or no selection.
     */
    public boolean applyToSelection(BlockPos pos) {
        int state = controller.stateAt(pos);
        String property = state < 0 ? null : controller.chosenProperty(state);
        if (property == null) {
            context().notify(Notice.of(Notice.Level.INFO, "sculptory.tinker.nothing_to_apply"));
            return false;
        }
        if (actions == null) return false;
        return actions.applyProperty(state, property);
    }

    @Override
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public SettingsSchema schema() {
        return SettingsSchema.EMPTY;
    }

    @Override
    public RaycastMode raycastMode(SettingsValues s) {
        return RaycastMode.BLOCKS;
    }

    @Override
    public void activate(ToolContext c) {
        last = c;
    }

    @Override
    public void deactivate(ToolContext c, DeactivateReason r) {
        last = c;
        controller.hover(null);
    }

    @Override
    public void frame(ToolContext c, FrameInfo f) {
        last = c;
        mouseX = f.mouseX();
        mouseY = f.mouseY();
        hover(f.cursor());
    }

    private void hover(WorldCursor cursor) {
        controller.aim(cursor);
    }

    @Override
    public boolean onPointer(ToolContext c, PointerEvent e) {
        last = c;
        if (e.kind() == PointerEvent.Kind.MOVE) {
            mouseX = e.mouseX();
            mouseY = e.mouseY();
            hover(e.cursor());
            return false;
        }
        if (e.kind() != PointerEvent.Kind.PRESS || e.button() != PointerEvent.LEFT) return false;
        hover(e.cursor());
        if (controller.hovered().isEmpty()) return false;
        controller.click().ifPresent(this::openPanel);
        return true;
    }

    /** Scroll changes the shown property (Shift: picks another) or turns the entity, when something is hovered. */
    @Override
    public boolean takesScroll(ToolContext c, int modifiers) {
        return !Modifiers.control(modifiers) && !Modifiers.alt(modifiers) && controller.takesScroll();
    }

    @Override
    public boolean onScroll(ToolContext c, ScrollEvent e) {
        last = c;
        if (Modifiers.control(e.modifiers()) || Modifiers.alt(e.modifiers())) return false;
        return controller.scroll(e.amount(), Modifiers.shift(e.modifiers()));
    }

    @Override
    public void renderWorld(ToolContext c, WorldDraw d) {
        switch (controller.hovered().orElse(null)) {
            case null -> {}
            case TinkerController.BlockTarget block -> {
                d.seeThrough(false);
                d.boxOutline(new Box(block.pos(), block.pos()), COLOUR);
            }
            case TinkerController.EntityTarget entity -> controller.outline().ifPresent(box -> outline(d, box));
        }
    }

    /** The twelve edges of an entity's box. */
    private static void outline(WorldDraw d, double[] b) {
        d.seeThrough(true);
        double[] xs = {b[0], b[3]}, ys = {b[1], b[4]}, zs = {b[2], b[5]};
        for (double y : ys) {
            for (double z : zs) d.line(b[0], y, z, b[3], y, z, COLOUR);
            for (double x : xs) d.line(x, y, b[2], x, y, b[5], COLOUR);
        }
        for (double x : xs) {
            for (double z : zs) d.line(x, b[1], z, x, b[4], z, COLOUR);
        }
        d.seeThrough(false);
    }

    /**
     * The label beside the cursor, on a dark plate, kept on the screen. It hangs under the editor's coordinate readout
     * (drawn 12 under the cursor, 12 high), which used to cover it.
     */
    @Override
    public void renderHud(ToolContext c, HudDraw d) {
        String label = controller.label();
        if (label.isEmpty()) return;
        int width = d.textWidth(label) + 6, height = 12;
        int x = (int) Math.round(mouseX) + 12, y = (int) Math.round(mouseY) + 26;
        if (x + width > d.width()) x = (int) Math.round(mouseX) - 8 - width;
        if (y + height > d.height()) y = (int) Math.round(mouseY) - 8 - height;
        x = Math.max(0, x);
        y = Math.max(0, y);
        d.fill(x, y, x + width, y + height, 0xD0101216);
        d.text(label, x + 3, y + 2, 0xFFFFFFFF);
    }

    @Override
    public List<KeyHint> hints(ToolContext c) {
        List<KeyHint> hints = new ArrayList<>();
        Optional<TinkerController.Target> target = controller.hovered();
        if (target.isEmpty()) {
            hints.add(KeyHint.text("sculptory.hint.tinker.aim"));
            return hints;
        }
        if (target.get() instanceof TinkerController.BlockTarget) {
            if (controller.takesScroll()) {
                hints.add(new KeyHint("Scroll", "sculptory.hint.tinker.value"));
                hints.add(new KeyHint("Shift+Scroll", "sculptory.hint.tinker.property"));
            }
            hints.add(new KeyHint(CLICK, "sculptory.hint.tinker.panel"));
        } else {
            if (controller.takesScroll()) hints.add(new KeyHint("Scroll", "sculptory.hint.tinker.turn"));
            hints.add(new KeyHint(CLICK, "sculptory.hint.tinker.panel"));
        }
        return hints;
    }

    /** This tool's context: the supplier's, else the last one the tool was called with. */
    private ToolContext context() {
        ToolContext current = context != null ? context.get() : last;
        if (current == null) throw new IllegalStateException("The Tinker tool has no context yet");
        return current;
    }

    /** The controller's view of the game: this tool's context and services. */
    private final class Host implements TinkerController.Host {
        @Override
        public Optional<EditorSession> session() {
            try {
                return Optional.of(context().session());
            } catch (IllegalStateException noSession) {
                return Optional.empty();
            }
        }

        @Override
        public Optional<TinkerController.EntityTarget> entityAt(WorldCursor cursor) {
            return services.entityAt(cursor);
        }

        @Override
        public StateSpace states() {
            return context().states();
        }

        @Override
        public WorldReader world() {
            return context().world();
        }

        @Override
        public String blockName(int state) {
            return services.blockName(states(), state);
        }

        @Override
        public Optional<SignText> signText(BlockPos pos) {
            return services.signText(pos);
        }

        @Override
        public String translate(String key, Object... args) {
            return services.translate(key, args);
        }

        @Override
        public void notify(Notice notice) {
            context().notify(notice);
        }

        @Override
        public long nanoTime() {
            return services.nanoTime();
        }
    }
}
