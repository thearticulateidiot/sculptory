package dev.sculptory.fabric.client.builder;

import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.fabric.SculptoryMod;
import dev.sculptory.fabric.builder.BuilderPlacement;
import dev.sculptory.fabric.builder.BuilderPlacementContext;
import dev.sculptory.fabric.client.editor.EditorClient;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.input.KeyChord;
import dev.sculptory.fabric.client.editor.mc.McTranslator;
import dev.sculptory.fabric.client.editor.mc.McWorldDraw;
import dev.sculptory.fabric.client.editor.ui.render.MinecraftInput;
import dev.sculptory.fabric.client.session.FabricEditorSession;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.SessionState;
import dev.sculptory.fabric.client.session.Subscription;
import dev.sculptory.fabric.client.tinker.McTinkerHost;
import dev.sculptory.fabric.client.tinker.TinkerController;
import dev.sculptory.protocol.v2.BuilderPower;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.server.engine.Perm;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PendingUpdateManager;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.ItemActionResult;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.Util;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

/**
 * Builder mode on the client: Sculptory's editing in normal creative play,
 * outside the editor. Holding the ring key ({@value #RING_KEY}, G) shows the {@link RingMenu} of powers; while at least
 * one power is on, every right-click placement and left-click break goes through the engine ({@code BuilderPlace},
 * {@code BuilderBreak}) instead of vanilla's packets, predicted locally under a vanilla prediction sequence the server
 * acknowledges, so each click is one undoable step of the shared history. With no power on, vanilla plays as usual.
 *
 * <ul>
 *   <li><b>Right-click on a block:</b> the block's own use comes first, as vanilla orders it (a chest opens, a door
 *       swings; not while sneaking), then the held block is placed next to the hit face, or with Replace into the hit
 *       cell. Force place puts it where the game refuses; Keep shape leaves the neighbours alone; Mirror repeats it
 *       under the editor's symmetry ({@link EditorClient#builderSymmetry}).</li>
 *   <li><b>Right-click at nothing</b> with Place in air: the held block goes {@value #PLACE_IN_AIR_DISTANCE} blocks
 *       ahead.</li>
 *   <li><b>Left-click:</b> breaks the block (one step). With Bulldozer, holding the button breaks every block the
 *       crosshair sweeps until it is released: one step. Sneaking while starting a bulldozer drag takes only blocks of
 *       the first block's kind.</li>
 *   <li><b>Undo and redo:</b> the editor's chords (Ctrl+Z, Ctrl+Y by default) work outside the editor too.</li>
 *   <li><b>Refusals</b> from the server ({@code sculptory.notice.builder.*}) show in the action bar.</li>
 * </ul>
 * Client thread only.
 */
public final class BuilderClient {
    public static final String RING_KEY = "key.sculptory.builder_ring";
    /** The keys of the notices builder mode shows in the action bar (the server's refusals among them). */
    public static final String NOTICE_PREFIX = "sculptory.notice.builder.";
    static final String NOT_AVAILABLE = NOTICE_PREFIX + "not_available";
    static final String NO_PERMISSION = NOTICE_PREFIX + "no_permission";
    static final String NOT_CREATIVE = NOTICE_PREFIX + "not_creative";
    static final String MIRROR_NEEDS_CENTRE = NOTICE_PREFIX + "mirror_needs_centre";
    static final String POWER_ON = "sculptory.builder.on";
    static final String POWER_OFF = "sculptory.builder.off";
    static final String NOTHING_TO_UNDO = "sculptory.notice.nothing_to_undo";
    static final String NOTHING_TO_REDO = "sculptory.notice.nothing_to_redo";
    /** How far ahead Place in air puts the block, from the eyes. */
    public static final double PLACE_IN_AIR_DISTANCE = 5.0;
    /** Ticks between breaks while the attack button is held without Bulldozer: vanilla creative's cadence. */
    static final int HELD_BREAK_TICKS = 5;
    /** The outline of what Tinker aims at. */
    static final int TINKER_COLOUR = 0xFFFFD166;
    /** A client-side hint with the same key shows at most once per this long. */
    static final long HINT_NANOS = 1_000_000_000L;

    private static BuilderClient instance;

    private final MinecraftClient client;
    private final KeyBinding ringKey;
    private final Translator translator;
    private final Supplier<FabricEditorSession> sessions;
    private final Supplier<StateSpace> states;
    private final EditorClient editor;
    private final PowerSet powers = new PowerSet();
    private final DragTracker drags = new DragTracker();
    private final Map<String, Long> hintsShown = new HashMap<>();
    /** Dev only: the ring key counts as held ({@link #pinRingKey}). */
    private volatile boolean ringKeyPinned;
    /** Dev only: keys that count as held ({@link #pinKeys}). */
    private volatile Set<Integer> pinnedKeys = Set.of();
    private int sentPowers = -1;
    private long sentEpoch = -1;
    private boolean undoDown;
    private boolean redoDown;
    /** Whether the attack button was down at the end of the last tick: a click that starts now is a fresh one. */
    private boolean attackHeld;
    private int breakCooldown;
    private FabricEditorSession noticeSession;
    private Subscription noticeSubscription;
    /** Tinker outside the editor: its host over the running client, its controller and the power that drives it. */
    private McTinkerHost tinkerHost;
    private TinkerPower tinkerPower;
    private final McWorldDraw worldDraw = new McWorldDraw();

    private BuilderClient(MinecraftClient client, KeyBinding ringKey, Translator translator,
                          Supplier<FabricEditorSession> sessions, Supplier<StateSpace> states, EditorClient editor) {
        this.client = client;
        this.ringKey = ringKey;
        this.translator = translator;
        this.sessions = sessions;
        this.states = states;
        this.editor = editor;
    }

    /** Registers the ring key and the interaction hooks. Called once from {@code SculptoryClientMod}. */
    public static BuilderClient init(EditorClient editor, Supplier<FabricEditorSession> sessions,
                                     Supplier<StateSpace> states) {
        if (instance != null) return instance;
        KeyBinding ring = KeyBindingHelper.registerKeyBinding(
                new KeyBinding(RING_KEY, InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, EditorClient.CATEGORY));
        instance = new BuilderClient(MinecraftClient.getInstance(), ring, new McTranslator(), sessions, states, editor);
        instance.installTinker();
        instance.registerEvents();
        editor.setBuilderMode(() -> instance.powers.mask() != 0,
                () -> instance.ringKey.getBoundKeyLocalizedText().getString());
        return instance;
    }

    public static Optional<BuilderClient> instance() {
        return Optional.ofNullable(instance);
    }

    /**
     * The Tinker power: Tinker's controller over {@link McTinkerHost}; its ring entry is greyed out with the reason while
     * the session lacks {@code region} or the server has no Tinker ({@link TinkerPower#gate}).
     */
    private void installTinker() {
        tinkerHost = new McTinkerHost(client, editor::toast);
        TinkerController controller = new TinkerController(tinkerHost);
        tinkerPower = new TinkerPower(controller, this::openTinkerPanel);
        controller.addListener(() -> {
            if (client.currentScreen instanceof TinkerScreen panel) panel.refresh();
        });
        powers.register(BuilderPower.TINKER, tinkerPower);
        powers.setGate(BuilderPower.TINKER, () -> TinkerPower.gate(Optional.ofNullable(sessions.get())));
    }

    /** The Tinker power's controller (tests). */
    TinkerPower tinkerPower() {
        return tinkerPower;
    }

    public PowerSet powers() {
        return powers;
    }

    private void registerEvents() {
        ClientTickEvents.END_CLIENT_TICK.register(ignored -> tick());
        UseBlockCallback.EVENT.register(this::useBlock);
        UseItemCallback.EVENT.register(this::useItem);
        AttackBlockCallback.EVENT.register(this::attackBlock);
        AttackEntityCallback.EVENT.register(this::attackEntity);
        HudRenderCallback.EVENT.register((context, tickCounter) -> {
            tinkerFrame(tickCounter.getTickDelta(true));
            if (client.player != null && !client.options.hudHidden && !editor.mode().isEditing() && unavailable() == null) {
                BuilderHud.render(context, client.textRenderer, powers.mask(), translator);
                drawTinkerLabel(context);
            }
        });
        WorldRenderEvents.LAST.register(this::renderTinkerOutline);
        ClientLifecycleEvents.CLIENT_STOPPING.register(ignored -> worldDraw.close());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(this::disconnected));
    }

    // ---- the Tinker power (Alt held) ----

    /**
     * Each frame: with the Tinker power on and Alt held outside the editor, Tinker aims at the crosshair's block within
     * the player's reach (Long reach included); otherwise it aims at nothing.
     */
    private void tinkerFrame(float tickDelta) {
        ClientPlayerEntity player = client.player;
        boolean alt = player != null && client.currentScreen == null && !editor.mode().isEditing()
                && unavailable() == null && (Screen.hasAltDown() || pinnedKeys.contains(GLFW.GLFW_KEY_LEFT_ALT));
        tinkerPower.frame(alt, alt ? tinkerHost.crosshair(player.getBlockInteractionRange(), tickDelta) : null);
    }

    /** Tinker's label ("Oak Stairs · shape: outer left") on a plate beside the crosshair. */
    private void drawTinkerLabel(DrawContext context) {
        String label = tinkerPower.label();
        if (label.isEmpty()) return;
        int width = client.textRenderer.getWidth(label) + 6;
        int x = context.getScaledWindowWidth() / 2 + 12;
        int y = context.getScaledWindowHeight() / 2 + 12;
        if (x + width > context.getScaledWindowWidth()) x = context.getScaledWindowWidth() / 2 - 8 - width;
        context.fill(x, y, x + width, y + 12, 0xD0101216);
        context.drawText(client.textRenderer, label, x + 3, y + 2, 0xFFFFFFFF, false);
    }

    /** The twelve edges of what Tinker aims at, drawn in the world. */
    private void renderTinkerOutline(WorldRenderContext context) {
        Optional<double[]> outline = tinkerPower.outline();
        if (outline.isEmpty()) return;
        double[] b = outline.get();
        Vec3d camera = context.camera().getPos();
        worldDraw.begin(camera.x, camera.y, camera.z, context.positionMatrix(), context.projectionMatrix());
        try {
            worldDraw.seeThrough(true);
            double[] xs = {b[0], b[3]}, ys = {b[1], b[4]}, zs = {b[2], b[5]};
            for (double y : ys) {
                for (double z : zs) worldDraw.line(b[0], y, z, b[3], y, z, TINKER_COLOUR);
                for (double x : xs) worldDraw.line(x, y, b[2], x, y, b[5], TINKER_COLOUR);
            }
            for (double x : xs) {
                for (double z : zs) worldDraw.line(x, b[1], z, x, b[4], z, TINKER_COLOUR);
            }
            worldDraw.seeThrough(false);
        } finally {
            worldDraw.end();
        }
    }

    /**
     * A wheel notch outside the editor ({@code MouseScrollMixin}): offered to the powers that are on (Tinker takes it
     * with Alt held over something Scroll changes); true when one took it, so the hotbar keeps its slot.
     */
    public boolean scrolled(double amount) {
        if (client.player == null || editor.mode().isEditing() || client.currentScreen != null || unavailable() != null) {
            return false;
        }
        return powers.onScroll(amount, MinecraftInput.modifiers());
    }

    /** Opens builder mode's Tinker panel for the block or entity a click chose. */
    private void openTinkerPanel(TinkerController.Target target) {
        StateSpace space = states.get();
        if (space == null) return;
        client.setScreen(new TinkerScreen(tinkerPower.controller(), target, space, translator));
    }

    // ---- ticking ----

    private void tick() {
        syncNotices();
        sendPowersIfNeeded();
        if (breakCooldown > 0) breakCooldown--;
        if (client.player == null) return;
        if (client.currentScreen == null && !editor.mode().isEditing()) {
            while (ringKey.wasPressed()) openRing();
            pollHistoryKeys();
            continueDrag();
        } else if (drags.open() && !(client.currentScreen instanceof RingScreen)) {
            endDrag();
        }
        attackHeld = client.options.attackKey.isPressed();
    }

    private void disconnected() {
        drags.end();
        sentPowers = -1;
        sentEpoch = -1;
        undoDown = false;
        redoDown = false;
    }

    /** Tells the server the powers whenever they change and once per connection. */
    private void sendPowersIfNeeded() {
        FabricEditorSession session = sessions.get();
        if (session == null || !session.builderOffered()) {
            sentEpoch = -1;
            return;
        }
        long epoch = session.capabilities().sessionEpoch();
        if (epoch == sentEpoch && powers.mask() == sentPowers) return;
        if (session.sendBuilder(new C2S.BuilderPowers(powers.mask())) == null) {
            sentEpoch = epoch;
            sentPowers = powers.mask();
        }
    }

    private void syncNotices() {
        FabricEditorSession session = sessions.get();
        if (session == noticeSession) return;
        if (noticeSubscription != null) noticeSubscription.close();
        noticeSession = session;
        noticeSubscription = session == null ? null : session.onNotice(this::notice);
    }

    /** The server's builder notices go to the action bar while the editor is closed (the editor toasts otherwise). */
    private void notice(Notice notice) {
        if (!notice.key().startsWith(NOTICE_PREFIX) || editor.mode().isEditing()) return;
        actionBar(translator.translate(notice.key(), notice.args()));
    }

    // ---- the ring ----

    private void openRing() {
        String why = unavailable();
        if (why != null) {
            actionBar(translator.translate(why));
            return;
        }
        int width = client.getWindow().getScaledWidth();
        int height = client.getWindow().getScaledHeight();
        RingMenu menu = new RingMenu(List.of(BuilderPower.values()), width / 2.0, height / 2.0,
                Math.max(40, Math.min(width, height) * 0.28), Util.getMeasuringTimeMs());
        client.setScreen(new RingScreen(this, menu, translator, ringKey));
    }

    /** The ring key was released with the ring open. */
    void ringReleased(RingMenu.Outcome outcome) {
        switch (outcome) {
            case RingMenu.Outcome.Toggle t -> toggle(t.power());
            case RingMenu.Outcome.TapLast t -> toggle(powers.last());
            case RingMenu.Outcome.Nothing n -> { }
        }
    }

    /** Toggles a power and says so in the action bar; an unavailable one says why. */
    void toggle(BuilderPower power) {
        if (!powers.toggle(power)) {
            actionBar(translator.translate(powers.unavailableKey(power)));
            return;
        }
        actionBar(translator.translate(powers.on(power) ? POWER_ON : POWER_OFF, translator.translate(nameKey(power))));
    }

    static String nameKey(BuilderPower power) {
        return "sculptory.builder.power." + power.name().toLowerCase(Locale.ROOT);
    }

    static String descriptionKey(BuilderPower power) {
        return nameKey(power) + ".desc";
    }

    // ---- who may ----

    /** Why builder mode cannot act right now (a notice key), or {@code null}. */
    private String unavailable() {
        FabricEditorSession session = sessions.get();
        if (session == null || !session.builderOffered()) return NOT_AVAILABLE;
        if (!session.permissions().has(Perm.USE) || !session.permissions().has(Perm.BUILDER)) return NO_PERMISSION;
        ClientPlayerEntity player = client.player;
        if (player == null || !player.isCreative()) return NOT_CREATIVE;
        return null;
    }

    /** Whether clicks are builder mode's now: a power on, the editor closed, the server and the player allowing it. */
    private boolean active() {
        return powers.mask() != 0 && !editor.mode().isEditing() && client.currentScreen == null && unavailable() == null;
    }

    // ---- placing ----

    private ActionResult useBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hit) {
        if (player != client.player || !(world instanceof ClientWorld clientWorld) || !active()) return ActionResult.PASS;
        ItemStack stack = player.getStackInHand(hand);
        if (!(stack.getItem() instanceof BlockItem)) return ActionResult.PASS;
        if (powers.onClick(GLFW.GLFW_MOUSE_BUTTON_RIGHT, MinecraftInput.modifiers())) return ActionResult.FAIL;
        // The block's own use first, as vanilla orders it; when it takes the click, vanilla's packet carries it.
        if (!player.shouldCancelInteraction()) {
            BlockState state = clientWorld.getBlockState(hit.getBlockPos());
            ItemActionResult withItem = state.onUseWithItem(stack, clientWorld, player, hand, hit);
            if (withItem.isAccepted()) return ActionResult.SUCCESS;
            if (withItem == ItemActionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION && hand == Hand.MAIN_HAND
                    && state.onUse(clientWorld, player, hit).isAccepted()) {
                return ActionResult.SUCCESS;
            }
        }
        place(clientWorld, (ClientPlayerEntity) player, hand, hit, false);
        return ActionResult.FAIL;
    }

    private TypedActionResult<ItemStack> useItem(PlayerEntity player, World world, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (player != client.player || !(world instanceof ClientWorld clientWorld) || !active()
                || !powers.on(BuilderPower.PLACE_IN_AIR) || !(stack.getItem() instanceof BlockItem)) {
            return TypedActionResult.pass(stack);
        }
        HitResult target = client.crosshairTarget;
        if (target == null || target.getType() != HitResult.Type.MISS) return TypedActionResult.pass(stack);
        Vec3d point = player.getEyePos().add(player.getRotationVec(1.0f).multiply(PLACE_IN_AIR_DISTANCE));
        BlockPos cell = BlockPos.ofFloored(point);
        place(clientWorld, (ClientPlayerEntity) player, hand, new BlockHitResult(Vec3d.ofCenter(cell), Direction.UP, cell,
                false), true);
        // Consumed, so the off hand does not fire too (a FAIL is not "accepted" to doItemUse).
        return TypedActionResult.consume(stack);
    }

    /** Predicts the placement in the client world and sends it; a refusal the client can see shows in the action bar. */
    private void place(ClientWorld world, ClientPlayerEntity player, Hand hand, BlockHitResult hit, boolean inAir) {
        boolean replace = powers.on(BuilderPower.REPLACE) && !inAir;
        boolean force = powers.on(BuilderPower.FORCE_PLACE);
        BuilderPlacement.Outcome decided = BuilderPlacement.decide(world, player, hand, hit, replace, force);
        if (decided.refusal() != null) {
            hint(NOTICE_PREFIX + decided.refusal().name().toLowerCase(Locale.ROOT));
            return;
        }
        Symmetry symmetry = symmetry();
        BuilderPlacement.Decision decision = decided.decision();
        BlockPos cell = decision.pos();
        List<BuilderPlacement.Copy> copies = BuilderPlacement.copies(symmetry, cell);
        ItemStack stack = decision.context().getStack();
        StateSpace space = states.get();
        int seq;
        PendingUpdateManager pending = world.getPendingUpdateManager().incrementSequence();
        try {
            seq = pending.getSequence();
            BuilderPlacement.place(decision.item(), decision.context(), decision.state(), false);
            for (BuilderPlacement.Copy copy : copies.subList(1, copies.size())) {
                Direction side = direction(Symmetry.imageFacing(copy.image(), facing(hit.getSide())));
                ItemPlacementContext context = BuilderPlacementContext.at(world, player, hand, stack, copy.pos(), side);
                if (!replace && !world.getBlockState(copy.pos()).canReplace(context)) continue;
                BlockState state = space == null ? decision.state()
                        : BuilderPlacement.imageState(space, copy.image(), decision.state());
                BuilderPlacement.place(decision.item(), context, state, true);
            }
        } catch (RuntimeException e) {
            SculptoryMod.LOG.warn("Sculptory: predicting a builder placement failed: {}", e.toString());
            seq = pending.getSequence();
        } finally {
            pending.close();
        }
        player.swingHand(hand);
        int mask = powers.mask() & (BuilderPower.REPLACE.bit() | BuilderPower.FORCE_PLACE.bit()
                | BuilderPower.KEEP_SHAPE.bit() | BuilderPower.MIRROR.bit());
        if (inAir) mask |= BuilderPower.PLACE_IN_AIR.bit();
        if (inAir) mask &= ~BuilderPower.REPLACE.bit();
        if (symmetry.isOff()) mask &= ~BuilderPower.MIRROR.bit();
        BlockPos pos = hit.getBlockPos();
        Vec3d at = hit.getPos();
        send(new C2S.BuilderPlace(seq, hand == Hand.OFF_HAND, wire(pos), facing(hit.getSide()),
                within(at.x - pos.getX()), within(at.y - pos.getY()), within(at.z - pos.getZ()), mask, symmetry));
    }

    // ---- breaking ----

    private ActionResult attackBlock(PlayerEntity player, World world, Hand hand, BlockPos pos, Direction direction) {
        if (player != client.player || !(world instanceof ClientWorld clientWorld) || !active()) return ActionResult.PASS;
        if (powers.onClick(GLFW.GLFW_MOUSE_BUTTON_LEFT, MinecraftInput.modifiers())) return ActionResult.FAIL;
        // A bulldozer drag in progress: the tick adds what the crosshair sweeps.
        if (drags.open()) return ActionResult.FAIL;
        // Held down (vanilla asks every tick in Creative): a break every HELD_BREAK_TICKS, as vanilla breaks.
        if (attackHeld && breakCooldown > 0) return ActionResult.FAIL;
        BlockState state = clientWorld.getBlockState(pos);
        if (!BuilderPlacement.breakable(clientWorld, player, pos)
                || !player.getMainHandStack().getItem().canMine(state, clientWorld, pos, player)) {
            return ActionResult.FAIL;
        }
        boolean dozer = powers.on(BuilderPower.BULLDOZER);
        drags.begin(dozer && player.isSneaking());
        drags.add(wire(pos));
        sendBreaks(clientWorld, (ClientPlayerEntity) player, !dozer);
        if (!dozer) {
            drags.end();
            breakCooldown = HELD_BREAK_TICKS;
        }
        return ActionResult.FAIL;
    }

    /**
     * A left click on an entity: vanilla's attack never reaches {@link #attackBlock}, so the powers get it here (Tinker
     * with Alt held over an armor stand, item frame, painting or display opens its panel and keeps vanilla from hitting
     * it); otherwise the attack is vanilla's.
     */
    private ActionResult attackEntity(PlayerEntity player, World world, Hand hand, Entity entity, EntityHitResult hit) {
        if (player != client.player || !active()) return ActionResult.PASS;
        return powers.onClick(GLFW.GLFW_MOUSE_BUTTON_LEFT, MinecraftInput.modifiers()) ? ActionResult.FAIL : ActionResult.PASS;
    }

    /** While the attack button is held with a bulldozer drag open, the block under the crosshair joins the drag. */
    private void continueDrag() {
        if (!drags.open()) return;
        if (!client.options.attackKey.isPressed() || !active() || !powers.on(BuilderPower.BULLDOZER)) {
            endDrag();
            return;
        }
        ClientWorld world = client.world;
        ClientPlayerEntity player = client.player;
        if (world == null || player == null) {
            endDrag();
            return;
        }
        if (client.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            BlockState state = world.getBlockState(pos);
            if (BuilderPlacement.breakable(world, player, pos)
                    && player.getMainHandStack().getItem().canMine(state, world, pos, player) && drags.add(wire(pos))) {
                sendBreaks(world, player, false);
            }
        }
    }

    private void endDrag() {
        int dragId = drags.dragId();
        if (drags.end()) send(new C2S.BuilderDragEnd(dragId));
    }

    /** Predicts the drag's new cells (and their mirrored copies) as broken and sends them. */
    private void sendBreaks(ClientWorld world, ClientPlayerEntity player, boolean last) {
        Symmetry symmetry = symmetry();
        int mask = powers.mask() & (BuilderPower.BULLDOZER.bit() | BuilderPower.KEEP_SHAPE.bit() | BuilderPower.MIRROR.bit());
        if (symmetry.isOff()) mask &= ~BuilderPower.MIRROR.bit();
        List<List<dev.sculptory.core.BlockPos>> batches = drags.drain();
        for (int i = 0; i < batches.size(); i++) {
            List<dev.sculptory.core.BlockPos> batch = batches.get(i);
            int seq;
            PendingUpdateManager pending = world.getPendingUpdateManager().incrementSequence();
            try {
                seq = pending.getSequence();
                for (dev.sculptory.core.BlockPos cell : batch) {
                    for (BuilderPlacement.Copy copy : BuilderPlacement.copies(symmetry, new BlockPos(cell.x(), cell.y(), cell.z()))) {
                        if (BuilderPlacement.breakable(world, player, copy.pos())) {
                            BuilderPlacement.breakBlock(world, player, copy.pos());
                        }
                    }
                }
            } catch (RuntimeException e) {
                SculptoryMod.LOG.warn("Sculptory: predicting a builder break failed: {}", e.toString());
                seq = pending.getSequence();
            } finally {
                pending.close();
            }
            player.swingHand(Hand.MAIN_HAND);
            send(new C2S.BuilderBreak(seq, drags.dragId(), batch, mask, symmetry, drags.sameKind(),
                    last && i == batches.size() - 1));
        }
    }

    // ---- undo and redo outside the editor ----

    private void pollHistoryKeys() {
        boolean undo = chordDown(editor.keymap().chords(KeyAction.UNDO));
        boolean redo = chordDown(editor.keymap().chords(KeyAction.REDO));
        if (undo && !undoDown) history(true);
        if (redo && !redoDown) history(false);
        undoDown = undo;
        redoDown = redo;
    }

    private boolean chordDown(List<KeyChord> chords) {
        long window = client.getWindow().getHandle();
        int modifiers = MinecraftInput.modifiers() | pinnedModifiers();
        for (KeyChord chord : chords) {
            if (chord.input() == KeyChord.Input.KEY && chord.modifiers() == modifiers
                    && (pinnedKeys.contains(chord.code()) || InputUtil.isKeyPressed(window, chord.code()))) {
                return true;
            }
        }
        return false;
    }

    /** The modifier bits of the pinned keys (dev only, see {@link #pinKeys}). */
    private int pinnedModifiers() {
        int modifiers = 0;
        if (pinnedKeys.contains(GLFW.GLFW_KEY_LEFT_CONTROL)) modifiers |= GLFW.GLFW_MOD_CONTROL;
        if (pinnedKeys.contains(GLFW.GLFW_KEY_LEFT_SHIFT)) modifiers |= GLFW.GLFW_MOD_SHIFT;
        if (pinnedKeys.contains(GLFW.GLFW_KEY_LEFT_ALT)) modifiers |= GLFW.GLFW_MOD_ALT;
        return modifiers;
    }

    /** One undo or redo step of the shared history; an open drag ends first so it is the step undone. */
    private void history(boolean undo) {
        FabricEditorSession session = sessions.get();
        if (session == null || session.state() != SessionState.READY) {
            actionBar(translator.translate(NOT_AVAILABLE));
            return;
        }
        endDrag();
        boolean can = undo ? session.history().canUndo() : session.history().canRedo();
        if (!session.historyBusy() && !can) {
            actionBar(translator.translate(undo ? NOTHING_TO_UNDO : NOTHING_TO_REDO));
            return;
        }
        if (undo) session.undo();
        else session.redo();
    }

    // ---- helpers ----

    /** The Mirror power's symmetry, or none (with a hint when Mirror is on but the editor has no centre or mode). */
    private Symmetry symmetry() {
        if (!powers.on(BuilderPower.MIRROR)) return Symmetry.NONE;
        Optional<Symmetry> symmetry = editor.builderSymmetry();
        if (symmetry.isEmpty()) hint(MIRROR_NEEDS_CENTRE);
        return symmetry.orElse(Symmetry.NONE);
    }

    private void send(C2S message) {
        FabricEditorSession session = sessions.get();
        String problem = session == null ? "NOT_READY: no session" : session.sendBuilder(message);
        if (problem != null) SculptoryMod.LOG.warn("Sculptory: {} not sent: {}", message.type(), problem);
    }

    /** A client-side notice in the action bar, at most once a second per key. */
    private void hint(String key) {
        long now = System.nanoTime();
        Long last = hintsShown.get(key);
        if (last != null && now - last < HINT_NANOS) return;
        hintsShown.put(key, now);
        actionBar(translator.translate(key));
    }

    private void actionBar(String text) {
        client.inGameHud.setOverlayMessage(Text.literal(text), false);
    }

    private static float within(double offset) {
        return (float) Math.max(0, Math.min(1, offset));
    }

    private static dev.sculptory.core.BlockPos wire(BlockPos pos) {
        return new dev.sculptory.core.BlockPos(pos.getX(), pos.getY(), pos.getZ());
    }

    static Direction direction(Facing facing) {
        return switch (facing) {
            case UP -> Direction.UP;
            case DOWN -> Direction.DOWN;
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case EAST -> Direction.EAST;
            case WEST -> Direction.WEST;
        };
    }

    static Facing facing(Direction direction) {
        return switch (direction) {
            case UP -> Facing.UP;
            case DOWN -> Facing.DOWN;
            case NORTH -> Facing.NORTH;
            case SOUTH -> Facing.SOUTH;
            case EAST -> Facing.EAST;
            case WEST -> Facing.WEST;
        };
    }

    /** Whether the ring key is down right now (polled: a release the screen never heard still closes the ring). */
    /**
     * Dev only (the play check): while {@code true}, the ring key counts as held whatever the real keyboard says, so a
     * scripted press without a real key down keeps the ring open for a picture.
     */
    public void pinRingKey(boolean held) {
        ringKeyPinned = held;
    }

    /** Whether {@link #pinRingKey} holds the ring key (dev only). */
    public boolean ringKeyPinned() {
        return ringKeyPinned;
    }

    /**
     * Dev only (the scripted demo): these GLFW keys count as held outside the editor whatever the real keyboard says:
     * {@code GLFW_KEY_LEFT_ALT} for the Tinker power, {@code GLFW_KEY_LEFT_CONTROL} with a letter for the undo and
     * redo chords. An empty set is the real keyboard again.
     */
    public void pinKeys(Set<Integer> keys) {
        pinnedKeys = Set.copyOf(keys);
    }

    boolean ringKeyDown() {
        if (ringKeyPinned) {
            return true;
        }
        InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(ringKey);
        long window = client.getWindow().getHandle();
        if (key.getCategory() == InputUtil.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(window, key.getCode()) == GLFW.GLFW_PRESS;
        }
        return InputUtil.isKeyPressed(window, key.getCode());
    }
}
