package dev.sculptory.fabric.client.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.tinker.EntityEdit;
import dev.sculptory.core.tinker.EntityView;
import dev.sculptory.core.tinker.SignText;
import dev.sculptory.core.tinker.TinkerKind;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.mock.MockEditorSession;
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.session.Capabilities;
import dev.sculptory.fabric.client.session.ClipboardCache;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.HistoryMirror;
import dev.sculptory.fabric.client.session.JobTracker;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Permissions;
import dev.sculptory.fabric.client.session.Reply;
import dev.sculptory.fabric.client.session.SessionState;
import dev.sculptory.fabric.client.session.StrokeHandle;
import dev.sculptory.fabric.client.session.StrokeParams;
import dev.sculptory.fabric.client.session.Subscription;
import dev.sculptory.fabric.client.session.ToolAction;
import dev.sculptory.fabric.client.session.ToolResult;
import dev.sculptory.fabric.client.tinker.TinkerController;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.server.engine.Perm;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/**
 * The Tinker power's routing: with the power on and Alt held it aims at the crosshair and takes the wheel only over
 * something Scroll changes; Alt released aims at nothing; the power off does nothing; a left click with Alt opens the
 * panel; the gate names why the power cannot be switched on.
 */
class TinkerPowerTest {
    private static final int ALT = Modifiers.ALT;
    private static final int SHIFT = Modifiers.SHIFT;

    private final FakeStateSpace states = new FakeStateSpace();
    private final int stone = states.state("minecraft:stone");
    private final int stairs = states.state("minecraft:oak_stairs[facing=east]");
    private final FakeWorld world = new FakeWorld(states);
    private final FakeSession session = new FakeSession();
    /** The entity the crosshair ray meets, when the test puts one there. */
    private TinkerController.EntityTarget entity;
    private final List<Notice> notices = new ArrayList<>();
    private final List<TinkerController.Target> panels = new ArrayList<>();
    private final TinkerController controller = new TinkerController(new FakeHost());
    private final TinkerPower power = new TinkerPower(controller, panels::add);

    TinkerPowerTest() {
        world.set(0, 64, 0, stairs);
        world.set(2, 64, 0, stone);
    }

    private static WorldCursor on(int x, int y, int z) {
        return new WorldCursor(new BlockPos(x, y, z), WorldCursor.Face.UP, x + 0.5, y + 1, z + 0.5, false);
    }

    private static Permissions without(Perm... missing) {
        EnumSet<Perm> granted = EnumSet.allOf(Perm.class);
        for (Perm perm : missing) granted.remove(perm);
        return new Permissions(Perm.mask(granted), Limits.DEFAULTS);
    }

    /** A session that records Tinker requests. */
    private static final class FakeSession implements EditorSession {
        final MockEditorSession mock = new MockEditorSession();
        final List<BlockPos> blocks = new ArrayList<>();
        boolean offered = true;

        @Override
        public boolean tinkerOffered() {
            return offered;
        }

        @Override
        public CompletionStage<Reply<Boolean>> tinkerBlock(BlockPos pos, int expected, int target, SignText sign) {
            blocks.add(pos);
            return new CompletableFuture<>();
        }

        @Override
        public CompletionStage<Reply<EntityView>> tinkerEntity(UUID id, List<EntityEdit> edits) {
            return new CompletableFuture<>();
        }

        @Override
        public SessionState state() {
            return mock.state();
        }

        @Override
        public Capabilities capabilities() {
            return mock.capabilities();
        }

        @Override
        public Permissions permissions() {
            return mock.permissions();
        }

        @Override
        public CompletionStage<ToolResult> send(ToolAction a) {
            return mock.send(a);
        }

        @Override
        public StrokeHandle beginStroke(ToolId tool, BrushSpec spec, StrokeParams p) {
            return mock.beginStroke(tool, spec, p);
        }

        @Override
        public void undo() {
            mock.undo();
        }

        @Override
        public void redo() {
            mock.redo();
        }

        @Override
        public void jumpTo(long historyId) {
            mock.jumpTo(historyId);
        }

        @Override
        public JobTracker jobs() {
            return mock.jobs();
        }

        @Override
        public HistoryMirror history() {
            return mock.history();
        }

        @Override
        public ClipboardCache clipboards() {
            return mock.clipboards();
        }

        @Override
        public Subscription onNotice(Consumer<Notice> l) {
            return mock.onNotice(l);
        }
    }

    private final class FakeHost implements TinkerController.Host {
        @Override
        public Optional<EditorSession> session() {
            return Optional.of(session);
        }

        @Override
        public Optional<TinkerController.EntityTarget> entityAt(WorldCursor cursor) {
            return Optional.ofNullable(entity);
        }

        @Override
        public StateSpace states() {
            return states;
        }

        @Override
        public WorldReader world() {
            return world;
        }

        @Override
        public String blockName(int state) {
            String id = states.blockId(state).value();
            return id.substring(id.indexOf(':') + 1);
        }

        @Override
        public Optional<SignText> signText(BlockPos pos) {
            return Optional.empty();
        }

        @Override
        public String translate(String key, Object... args) {
            return Translator.KEYS.translate(key, args);
        }

        @Override
        public void notify(Notice notice) {
            notices.add(notice);
        }

        @Override
        public long nanoTime() {
            return 0;
        }
    }

    @Test
    void offThePowerDoesNothing() {
        power.frame(true, on(0, 64, 0));
        assertFalse(power.aiming());
        assertEquals("", power.label());
        assertTrue(controller.hovered().isEmpty());
        assertFalse(power.onScroll(1, ALT));
        assertFalse(power.onClick(GLFW.GLFW_MOUSE_BUTTON_LEFT, ALT));
        assertTrue(session.blocks.isEmpty() && panels.isEmpty());
    }

    @Test
    void altHeldAimsAndTakesTheWheelOnlyOverSomethingScrollChanges() {
        power.activate();
        power.frame(false, on(0, 64, 0));
        assertFalse(power.aiming(), "without Alt the crosshair is the game's");
        assertTrue(controller.hovered().isEmpty());

        power.frame(true, on(0, 64, 0));
        assertTrue(power.aiming());
        assertFalse(power.label().isEmpty(), "the stairs' label");
        assertTrue(power.outline().isPresent());
        assertFalse(power.onScroll(1, 0), "a notch without Alt is the hotbar's");
        assertTrue(power.onScroll(1, ALT), "Alt+Scroll changes the shown property");
        assertEquals(List.of(new BlockPos(0, 64, 0)), session.blocks);
        assertTrue(power.onScroll(1, ALT | SHIFT), "Alt+Shift+Scroll picks another property");

        power.frame(true, on(2, 64, 0));
        assertFalse(controller.takesScroll(), "stone has no property");
        assertFalse(power.onScroll(1, ALT), "the notch stays the game's over stone");

        power.frame(true, WorldCursor.miss(5, 70, 5));
        assertTrue(controller.hovered().isEmpty());
        assertFalse(power.onScroll(1, ALT));

        power.frame(true, on(0, 64, 0));
        power.frame(false, on(0, 64, 0));
        assertFalse(power.aiming(), "Alt released");
        assertTrue(controller.hovered().isEmpty(), "aim(null) on release");
        assertEquals("", power.label());
        assertFalse(power.onScroll(1, ALT));
    }

    @Test
    void altClickOpensThePanelAndTheRestIsBuilderModes() {
        power.activate();
        power.frame(true, on(0, 64, 0));
        assertFalse(power.onClick(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0), "without Alt the click breaks the block");
        assertFalse(power.onClick(GLFW.GLFW_MOUSE_BUTTON_RIGHT, ALT), "a right click places");
        assertTrue(power.onClick(GLFW.GLFW_MOUSE_BUTTON_LEFT, ALT));
        assertEquals(List.of(new TinkerController.BlockTarget(new BlockPos(0, 64, 0))), panels);
        power.frame(true, WorldCursor.miss(5, 70, 5));
        assertFalse(power.onClick(GLFW.GLFW_MOUSE_BUTTON_LEFT, ALT), "nothing aimed at: nothing to open");

        // Without region the click toasts and opens nothing.
        session.mock.setPermissions(without(Perm.REGION));
        power.frame(true, on(0, 64, 0));
        assertTrue(power.onClick(GLFW.GLFW_MOUSE_BUTTON_LEFT, ALT), "still Tinker's click");
        assertEquals(1, panels.size());
        assertEquals("sculptory.notice.needs_permission", notices.get(notices.size() - 1).key());

        power.deactivate();
        assertFalse(power.on());
        assertTrue(controller.hovered().isEmpty(), "switching off aims at nothing");
    }

    /** Vanilla's entity attack reaches the power through BuilderClient.attackEntity: Alt+click opens the entity's panel. */
    @Test
    void altClickOnATinkerableEntityOpensItsPanelAndIsSwallowed() {
        entity = new TinkerController.EntityTarget(new UUID(5, 6), TinkerKind.ARMOR_STAND, "Armor Stand", 90f, 0, "",
                new double[] {1, 64, 1, 1.5, 66, 1.5});
        power.activate();
        power.frame(true, WorldCursor.miss(1.25, 65, 1.25));
        assertEquals(Optional.of(entity), controller.hovered(), "the entity the ray meets, also on a miss");
        assertFalse(power.label().isEmpty());
        assertFalse(power.onClick(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0), "without Alt the hit is vanilla's");
        assertTrue(power.onClick(GLFW.GLFW_MOUSE_BUTTON_LEFT, ALT), "with Alt it is Tinker's: swallowed");
        assertEquals(List.of(entity), panels);
        assertTrue(power.onScroll(1, ALT), "Alt+Scroll turns the armor stand");
        power.frame(false, WorldCursor.miss(1.25, 65, 1.25));
        assertFalse(power.onClick(GLFW.GLFW_MOUSE_BUTTON_LEFT, ALT), "Alt released: vanilla's again");
    }

    @Test
    void theGateNamesWhyThePowerCannotBeSwitchedOn() {
        assertEquals(BuilderClient.NOT_AVAILABLE, TinkerPower.gate(Optional.empty()));
        session.mock.setPermissions(without(Perm.REGION));
        assertEquals(TinkerPower.NEEDS_REGION, TinkerPower.gate(Optional.of(session)));
        session.mock.setPermissions(without());
        session.offered = false;
        assertEquals(TinkerController.NOT_OFFERED, TinkerPower.gate(Optional.of(session)));
        session.offered = true;
        assertNull(TinkerPower.gate(Optional.of(session)));

        PowerSet powers = new PowerSet();
        powers.register(dev.sculptory.protocol.v2.BuilderPower.TINKER, power);
        String[] gate = {TinkerPower.NEEDS_REGION};
        powers.setGate(dev.sculptory.protocol.v2.BuilderPower.TINKER, () -> gate[0]);
        assertFalse(powers.available(dev.sculptory.protocol.v2.BuilderPower.TINKER));
        assertEquals(TinkerPower.NEEDS_REGION, powers.unavailableKey(dev.sculptory.protocol.v2.BuilderPower.TINKER));
        assertFalse(powers.toggle(dev.sculptory.protocol.v2.BuilderPower.TINKER));
        gate[0] = null;
        assertTrue(powers.toggle(dev.sculptory.protocol.v2.BuilderPower.TINKER));
        assertTrue(power.on());
        gate[0] = TinkerPower.NEEDS_REGION;
        assertTrue(powers.set(dev.sculptory.protocol.v2.BuilderPower.TINKER, false), "switching off always works");
        assertFalse(power.on());
    }
}
