package dev.sculptory.mc.client.builder;

import com.mojang.blaze3d.platform.InputConstants;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.mc.Sculptory;
import dev.sculptory.mc.builder.BuilderPlacement;
import dev.sculptory.mc.builder.BuilderPlacementContext;
import dev.sculptory.mc.client.editor.EditorClient;
import dev.sculptory.mc.client.editor.Translator;
import dev.sculptory.mc.client.editor.input.KeyAction;
import dev.sculptory.mc.client.editor.input.KeyChord;
import dev.sculptory.mc.client.editor.mc.McTranslator;
import dev.sculptory.mc.client.editor.mc.McWorldDraw;
import dev.sculptory.mc.client.editor.ui.render.MinecraftInput;
import dev.sculptory.mc.client.session.McEditorSession;
import dev.sculptory.mc.client.session.Notice;
import dev.sculptory.mc.client.session.SessionState;
import dev.sculptory.mc.client.session.Subscription;
import dev.sculptory.mc.client.tinker.McTinkerHost;
import dev.sculptory.mc.client.tinker.TinkerController;
import dev.sculptory.mc.loader.ClientLoader;
import dev.sculptory.mc.loader.ClientLoaders;
import dev.sculptory.mc.loader.WorldFrame;
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
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
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

    private final Minecraft client;
    private final KeyMapping ringKey;
    private final Translator translator;
    private final Supplier<McEditorSession> sessions;
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
    private McEditorSession noticeSession;
    private Subscription noticeSubscription;
    /** Tinker outside the editor: its host over the running client, its controller and the power that drives it. */
    private McTinkerHost tinkerHost;
    private TinkerPower tinkerPower;
    private final McWorldDraw worldDraw = new McWorldDraw();

    private BuilderClient(Minecraft client, KeyMapping ringKey, Translator translator,
                          Supplier<McEditorSession> sessions, Supplier<StateSpace> states, EditorClient editor) {
        this.client = client;
        this.ringKey = ringKey;
        this.translator = translator;
        this.sessions = sessions;
        this.states = states;
        this.editor = editor;
    }

    /** Registers the ring key and the interaction hooks. Called once from {@code SculptoryClientMod}. */
    public static BuilderClient init(EditorClient editor, Supplier<McEditorSession> sessions,
                                     Supplier<StateSpace> states) {
        if (instance != null) return instance;
        KeyMapping ring = ClientLoaders.get().registerKey(
                new KeyMapping(RING_KEY, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, EditorClient.CATEGORY));
        instance = new BuilderClient(Minecraft.getInstance(), ring, new McTranslator(), sessions, states, editor);
        instance.installTinker();
        instance.registerEvents();
        editor.setBuilderMode(() -> instance.powers.mask() != 0,
                () -> instance.ringKey.getTranslatedKeyMessage().getString());
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
            if (client.screen instanceof TinkerScreen panel) panel.refresh();
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
        ClientLoader loader = ClientLoaders.get();
        loader.onClientTickEnd(this::tick);
        loader.onUseBlock(this::useBlock);
        loader.onUseItem(this::useItem);
        loader.onAttackBlock(this::attackBlock);
        loader.onAttackEntity(this::attackEntity);
        loader.onHudRender((context, tickCounter) -> {
            tinkerFrame(tickCounter.getGameTimeDeltaPartialTick(true));
            if (client.player != null && !client.options.hideGui && !editor.mode().isEditing() && unavailable() == null) {
                BuilderHud.render(context, client.font, powers.mask(), translator);
                drawTinkerLabel(context);
            }
        });
        loader.onWorldRenderLast(this::renderTinkerOutline);
        loader.onClientStopping(worldDraw::close);
        loader.onDisconnect(this::disconnected);
    }

    // ---- the Tinker power (Alt held) ----

    /**
     * Each frame: with the Tinker power on and Alt held outside the editor, Tinker aims at the crosshair's block within
     * the player's reach (Long reach included); otherwise it aims at nothing.
     */
    private void tinkerFrame(float tickDelta) {
        LocalPlayer player = client.player;
        boolean alt = player != null && client.screen == null && !editor.mode().isEditing()
                && unavailable() == null && (Screen.hasAltDown() || pinnedKeys.contains(GLFW.GLFW_KEY_LEFT_ALT));
        tinkerPower.frame(alt, alt ? tinkerHost.crosshair(player.blockInteractionRange(), tickDelta) : null);
    }

    /** Tinker's label ("Oak Stairs · shape: outer left") on a plate beside the crosshair. */
    private void drawTinkerLabel(GuiGraphics context) {
        String label = tinkerPower.label();
        if (label.isEmpty()) return;
        int width = client.font.width(label) + 6;
        int x = context.guiWidth() / 2 + 12;
        int y = context.guiHeight() / 2 + 12;
        if (x + width > context.guiWidth()) x = context.guiWidth() / 2 - 8 - width;
        context.fill(x, y, x + width, y + 12, 0xD0101216);
        context.drawString(client.font, label, x + 3, y + 2, 0xFFFFFFFF, false);
    }

    /** The twelve edges of what Tinker aims at, drawn in the world. */
    private void renderTinkerOutline(WorldFrame context) {
        Optional<double[]> outline = tinkerPower.outline();
        if (outline.isEmpty()) return;
        double[] b = outline.get();
        Vec3 camera = context.camera().getPosition();
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
        if (client.player == null || editor.mode().isEditing() || client.screen != null || unavailable() != null) {
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
        if (client.screen == null && !editor.mode().isEditing()) {
            while (ringKey.consumeClick()) openRing();
            pollHistoryKeys();
            continueDrag();
        } else if (drags.open() && !(client.screen instanceof RingScreen)) {
            endDrag();
        }
        attackHeld = client.options.keyAttack.isDown();
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
        McEditorSession session = sessions.get();
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
        McEditorSession session = sessions.get();
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
        int width = client.getWindow().getGuiScaledWidth();
        int height = client.getWindow().getGuiScaledHeight();
        RingMenu menu = new RingMenu(List.of(BuilderPower.values()), width / 2.0, height / 2.0,
                Math.max(40, Math.min(width, height) * 0.28), Util.getMillis());
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
        McEditorSession session = sessions.get();
        if (session == null || !session.builderOffered()) return NOT_AVAILABLE;
        if (!session.permissions().has(Perm.USE) || !session.permissions().has(Perm.BUILDER)) return NO_PERMISSION;
        LocalPlayer player = client.player;
        if (player == null || !player.isCreative()) return NOT_CREATIVE;
        return null;
    }

    /** Whether clicks are builder mode's now: a power on, the editor closed, the server and the player allowing it. */
    private boolean active() {
        return powers.mask() != 0 && !editor.mode().isEditing() && client.screen == null && unavailable() == null;
    }

    // ---- placing ----

    private InteractionResult useBlock(Player player, Level world, InteractionHand hand, BlockHitResult hit) {
        if (player != client.player || !(world instanceof ClientLevel clientWorld) || !active()) return InteractionResult.PASS;
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof BlockItem)) return InteractionResult.PASS;
        if (powers.onClick(GLFW.GLFW_MOUSE_BUTTON_RIGHT, MinecraftInput.modifiers())) return InteractionResult.FAIL;
        // The block's own use first, as vanilla orders it; when it takes the click, vanilla's packet carries it.
        if (!player.isSecondaryUseActive()) {
            BlockState state = clientWorld.getBlockState(hit.getBlockPos());
            ItemInteractionResult withItem = state.useItemOn(stack, clientWorld, player, hand, hit);
            if (withItem.consumesAction()) return InteractionResult.SUCCESS;
            if (withItem == ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION && hand == InteractionHand.MAIN_HAND
                    && state.useWithoutItem(clientWorld, player, hit).consumesAction()) {
                return InteractionResult.SUCCESS;
            }
        }
        place(clientWorld, (LocalPlayer) player, hand, hit, false);
        return InteractionResult.FAIL;
    }

    private InteractionResultHolder<ItemStack> useItem(Player player, Level world, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player != client.player || !(world instanceof ClientLevel clientWorld) || !active()
                || !powers.on(BuilderPower.PLACE_IN_AIR) || !(stack.getItem() instanceof BlockItem)) {
            return InteractionResultHolder.pass(stack);
        }
        HitResult target = client.hitResult;
        if (target == null || target.getType() != HitResult.Type.MISS) return InteractionResultHolder.pass(stack);
        Vec3 point = player.getEyePosition().add(player.getViewVector(1.0f).scale(PLACE_IN_AIR_DISTANCE));
        BlockPos cell = BlockPos.containing(point);
        place(clientWorld, (LocalPlayer) player, hand, new BlockHitResult(Vec3.atCenterOf(cell), Direction.UP, cell,
                false), true);
        // Consumed, so the off hand does not fire too (a FAIL is not "accepted" to doItemUse).
        return InteractionResultHolder.consume(stack);
    }

    /** Predicts the placement in the client world and sends it; a refusal the client can see shows in the action bar. */
    private void place(ClientLevel world, LocalPlayer player, InteractionHand hand, BlockHitResult hit, boolean inAir) {
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
        ItemStack stack = decision.context().getItemInHand();
        StateSpace space = states.get();
        int seq;
        BlockStatePredictionHandler pending = world.getBlockStatePredictionHandler().startPredicting();
        try {
            seq = pending.currentSequence();
            BuilderPlacement.place(decision.item(), decision.context(), decision.state(), false);
            for (BuilderPlacement.Copy copy : copies.subList(1, copies.size())) {
                Direction side = direction(Symmetry.imageFacing(copy.image(), facing(hit.getDirection())));
                BlockPlaceContext context = BuilderPlacementContext.at(world, player, hand, stack, copy.pos(), side);
                if (!replace && !world.getBlockState(copy.pos()).canBeReplaced(context)) continue;
                BlockState state = space == null ? decision.state()
                        : BuilderPlacement.imageState(space, copy.image(), decision.state());
                BuilderPlacement.place(decision.item(), context, state, true);
            }
        } catch (RuntimeException e) {
            Sculptory.LOG.warn("Sculptory: predicting a builder placement failed: {}", e.toString());
            seq = pending.currentSequence();
        } finally {
            pending.close();
        }
        player.swing(hand);
        int mask = powers.mask() & (BuilderPower.REPLACE.bit() | BuilderPower.FORCE_PLACE.bit()
                | BuilderPower.KEEP_SHAPE.bit() | BuilderPower.MIRROR.bit());
        if (inAir) mask |= BuilderPower.PLACE_IN_AIR.bit();
        if (inAir) mask &= ~BuilderPower.REPLACE.bit();
        if (symmetry.isOff()) mask &= ~BuilderPower.MIRROR.bit();
        BlockPos pos = hit.getBlockPos();
        Vec3 at = hit.getLocation();
        send(new C2S.BuilderPlace(seq, hand == InteractionHand.OFF_HAND, wire(pos), facing(hit.getDirection()),
                within(at.x - pos.getX()), within(at.y - pos.getY()), within(at.z - pos.getZ()), mask, symmetry));
    }

    // ---- breaking ----

    private InteractionResult attackBlock(Player player, Level world, InteractionHand hand, BlockPos pos, Direction direction) {
        if (player != client.player || !(world instanceof ClientLevel clientWorld) || !active()) return InteractionResult.PASS;
        if (powers.onClick(GLFW.GLFW_MOUSE_BUTTON_LEFT, MinecraftInput.modifiers())) return InteractionResult.FAIL;
        // A bulldozer drag in progress: the tick adds what the crosshair sweeps.
        if (drags.open()) return InteractionResult.FAIL;
        // Held down (vanilla asks every tick in Creative): a break every HELD_BREAK_TICKS, as vanilla breaks.
        if (attackHeld && breakCooldown > 0) return InteractionResult.FAIL;
        BlockState state = clientWorld.getBlockState(pos);
        if (!BuilderPlacement.breakable(clientWorld, player, pos)
                || !player.getMainHandItem().getItem().canAttackBlock(state, clientWorld, pos, player)) {
            return InteractionResult.FAIL;
        }
        boolean dozer = powers.on(BuilderPower.BULLDOZER);
        drags.begin(dozer && player.isShiftKeyDown());
        drags.add(wire(pos));
        sendBreaks(clientWorld, (LocalPlayer) player, !dozer);
        if (!dozer) {
            drags.end();
            breakCooldown = HELD_BREAK_TICKS;
        }
        return InteractionResult.FAIL;
    }

    /**
     * A left click on an entity: vanilla's attack never reaches {@link #attackBlock}, so the powers get it here (Tinker
     * with Alt held over an armor stand, item frame, painting or display opens its panel and keeps vanilla from hitting
     * it); otherwise the attack is vanilla's.
     */
    private InteractionResult attackEntity(Player player, Level world, InteractionHand hand, Entity entity, EntityHitResult hit) {
        if (player != client.player || !active()) return InteractionResult.PASS;
        return powers.onClick(GLFW.GLFW_MOUSE_BUTTON_LEFT, MinecraftInput.modifiers()) ? InteractionResult.FAIL : InteractionResult.PASS;
    }

    /** While the attack button is held with a bulldozer drag open, the block under the crosshair joins the drag. */
    private void continueDrag() {
        if (!drags.open()) return;
        if (!client.options.keyAttack.isDown() || !active() || !powers.on(BuilderPower.BULLDOZER)) {
            endDrag();
            return;
        }
        ClientLevel world = client.level;
        LocalPlayer player = client.player;
        if (world == null || player == null) {
            endDrag();
            return;
        }
        if (client.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            BlockState state = world.getBlockState(pos);
            if (BuilderPlacement.breakable(world, player, pos)
                    && player.getMainHandItem().getItem().canAttackBlock(state, world, pos, player) && drags.add(wire(pos))) {
                sendBreaks(world, player, false);
            }
        }
    }

    private void endDrag() {
        int dragId = drags.dragId();
        if (drags.end()) send(new C2S.BuilderDragEnd(dragId));
    }

    /** Predicts the drag's new cells (and their mirrored copies) as broken and sends them. */
    private void sendBreaks(ClientLevel world, LocalPlayer player, boolean last) {
        Symmetry symmetry = symmetry();
        int mask = powers.mask() & (BuilderPower.BULLDOZER.bit() | BuilderPower.KEEP_SHAPE.bit() | BuilderPower.MIRROR.bit());
        if (symmetry.isOff()) mask &= ~BuilderPower.MIRROR.bit();
        List<List<dev.sculptory.core.BlockPos>> batches = drags.drain();
        for (int i = 0; i < batches.size(); i++) {
            List<dev.sculptory.core.BlockPos> batch = batches.get(i);
            int seq;
            BlockStatePredictionHandler pending = world.getBlockStatePredictionHandler().startPredicting();
            try {
                seq = pending.currentSequence();
                for (dev.sculptory.core.BlockPos cell : batch) {
                    for (BuilderPlacement.Copy copy : BuilderPlacement.copies(symmetry, new BlockPos(cell.x(), cell.y(), cell.z()))) {
                        if (BuilderPlacement.breakable(world, player, copy.pos())) {
                            BuilderPlacement.breakBlock(world, player, copy.pos());
                        }
                    }
                }
            } catch (RuntimeException e) {
                Sculptory.LOG.warn("Sculptory: predicting a builder break failed: {}", e.toString());
                seq = pending.currentSequence();
            } finally {
                pending.close();
            }
            player.swing(InteractionHand.MAIN_HAND);
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
        long window = client.getWindow().getWindow();
        int modifiers = MinecraftInput.modifiers() | pinnedModifiers();
        for (KeyChord chord : chords) {
            if (chord.input() == KeyChord.Input.KEY && chord.modifiers() == modifiers
                    && (pinnedKeys.contains(chord.code()) || InputConstants.isKeyDown(window, chord.code()))) {
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
        McEditorSession session = sessions.get();
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
        McEditorSession session = sessions.get();
        String problem = session == null ? "NOT_READY: no session" : session.sendBuilder(message);
        if (problem != null) Sculptory.LOG.warn("Sculptory: {} not sent: {}", message.type(), problem);
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
        client.gui.setOverlayMessage(Component.literal(text), false);
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
        InputConstants.Key key = ClientLoaders.get().boundKey(ringKey);
        long window = client.getWindow().getWindow();
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
        }
        return InputConstants.isKeyDown(window, key.getValue());
    }
}
