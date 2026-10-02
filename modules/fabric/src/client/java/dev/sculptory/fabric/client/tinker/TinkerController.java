package dev.sculptory.fabric.client.tinker;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.tinker.EntityEdit;
import dev.sculptory.core.tinker.EntityEdits;
import dev.sculptory.core.tinker.EntityView;
import dev.sculptory.core.tinker.SignText;
import dev.sculptory.core.tinker.TinkerKind;
import dev.sculptory.core.tinker.TinkerProperties;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Reply;
import dev.sculptory.server.engine.Perm;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Tinker's client logic, shared by the editor's Tinker tool and builder mode (Tinker
 * outside the editor, with Alt held): what the cursor is on, the label shown beside it, what Scroll and Shift+Scroll
 * do, and the requests that change a block or an entity. Minecraft-free: the {@link Host} gives it the session, the
 * world, names and the entity pick. Render thread only.
 *
 * <p><b>Driving it (the editor's tool, builder mode).</b> One controller per host; the editor keeps its own in the Tinker
 * tool. Builder mode makes one over a {@code McTinkerHost} and, while Alt is held:
 * <ol>
 *   <li>each frame, {@link #aim(WorldCursor)} with the crosshair's cursor ({@code McTinkerHost.crosshair(reach, tickDelta)}), then
 *       {@link #label()} for the text beside the crosshair and {@link #outline()} for the box to draw ({@link #hovered()}
 *       says which); {@code aim(null)} when Alt is released;</li>
 *   <li>a wheel notch: {@link #takesScroll()} says whether Tinker wants it (else it is the game's), then
 *       {@link #scroll(double, boolean)} with Shift;</li>
 *   <li>a left click: {@link #click()} gives the block or entity to show in a panel, after the checks that toast
 *       (no session, a server without Tinker, no {@code region}); the panel's changes are {@link #setState},
 *       {@link #setSign}, {@link #look} and {@link #edit}, shown through {@link #stateAt}, {@link #signText} and
 *       {@link #view}, with {@link #addListener} to refresh.</li>
 * </ol>
 * Every change is sent as its own request and is one history step on the server.
 *
 * <p><b>Blocks.</b> The label reads "Oak Stairs · shape: outer left": the block's name and one property, the one last
 * picked for that block type in this session (Shift+Scroll picks the next, in {@link TinkerProperties}' order), or the
 * first useful one. Scroll moves that property to the next value (up) or the one before (down), going round.
 *
 * <p><b>Entities.</b> Scroll turns an armor stand or a display by {@value #TURN_STEP}° (Shift: {@value #FINE_TURN_STEP}°),
 * and an item frame's item by an eighth of a turn; it does nothing over a painting.
 *
 * <p><b>Requests.</b> One block change and one entity change are in flight at a time; what comes meanwhile waits and is
 * merged (the latest value of each property or setting wins), so a quick scroll through five values is one or two
 * steps, not five, and the server's rate limit is never reached. A change is shown (label, panel) as soon as it is
 * made, and for {@value #SHOWN_SECONDS} s after the server carried it out while the world catches up. A refusal drops
 * the changes still waiting for that block or entity (the session toasts why).
 */
public final class TinkerController {
    /** Degrees one scroll notch turns an armor stand or display. */
    public static final int TURN_STEP = 15;
    /** Degrees one notch turns with Shift held. */
    public static final int FINE_TURN_STEP = 1;
    static final int SHOWN_SECONDS = 2;
    static final long SHOWN_NANOS = SHOWN_SECONDS * 1_000_000_000L;
    public static final String NOT_OFFERED = "sculptory.tinker.not_offered";

    /** What the controller needs from the game. */
    public interface Host {
        /** The session, if connected. */
        Optional<EditorSession> session();

        /**
         * The entity Tinker changes that the cursor ray meets before it meets {@code cursor}'s block (or anywhere within
         * reach when the ray missed), if any.
         */
        Optional<EntityTarget> entityAt(WorldCursor cursor);

        StateSpace states();

        /** The client's world. */
        WorldReader world();

        /** A state's block name as shown ("Oak Stairs"). */
        String blockName(int state);

        /** The text of the sign at {@code pos} as this client sees it, if the block is a sign or hanging sign. */
        Optional<SignText> signText(BlockPos pos);

        String translate(String key, Object... args);

        void notify(Notice notice);

        long nanoTime();
    }

    /** What the cursor is on. */
    public sealed interface Target permits BlockTarget, EntityTarget {}

    public record BlockTarget(BlockPos pos) implements Target {
        public BlockTarget {
            Objects.requireNonNull(pos);
        }
    }

    /**
     * An entity Tinker changes, as this client sees it: its kind, shown name, heading, item frame rotation, painting
     * variant ("" when unknown) and bounding box (min x, y, z, max x, y, z), for the outline.
     */
    public record EntityTarget(UUID id, TinkerKind kind, String name, float yaw, int itemRotation, String variant,
                               double[] box) implements Target {
        public EntityTarget {
            Objects.requireNonNull(id);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(name);
            Objects.requireNonNull(variant);
            if (box.length != 6) throw new IllegalArgumentException("A box has 6 coordinates");
            box = box.clone();
        }

        @Override
        public double[] box() {
            return box.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof EntityTarget other && id.equals(other.id) && kind == other.kind
                    && Float.compare(yaw, other.yaw) == 0 && itemRotation == other.itemRotation
                    && variant.equals(other.variant) && name.equals(other.name)
                    && java.util.Arrays.equals(box, other.box);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }

    private record Pending(int target, SignText sign) {}

    private record BlockChange(BlockPos pos, int expected, int target, SignText sign) {}

    private record Shown(int state, long until) {}

    private record EntityChange(UUID id, List<EntityEdit> edits) {}

    private final Host host;
    private Target hovered;
    /** Per block type, the property last picked with Shift+Scroll this session. */
    private final Map<NamespacedId, String> remembered = new HashMap<>();
    private final LinkedHashMap<BlockPos, Pending> queued = new LinkedHashMap<>();
    private BlockChange inFlight;
    private final Map<BlockPos, Shown> shown = new HashMap<>();
    private final LinkedHashMap<UUID, LinkedHashMap<String, EntityEdit>> entityQueued = new LinkedHashMap<>();
    private final Set<UUID> looks = new HashSet<>();
    private EntityChange entityInFlight;
    private final Map<UUID, EntityView> views = new HashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();

    public TinkerController(Host host) {
        this.host = Objects.requireNonNull(host);
    }

    /** {@code listener} runs whenever a change was sent, done or refused, or a view arrived (a panel refreshes). */
    public void addListener(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    // =================================================================== hover and label

    /**
     * Points Tinker at what {@code cursor} is on: the entity the ray meets first ({@link Host#entityAt}), else the
     * cursor's block, else nothing ({@code null} cursor: nothing).
     */
    public void aim(WorldCursor cursor) {
        if (cursor == null) {
            hover(null);
            return;
        }
        Optional<EntityTarget> entity = host.entityAt(cursor);
        if (entity.isPresent()) {
            hover(entity.get());
        } else if (!cursor.missed()) {
            hover(new BlockTarget(cursor.pos()));
        } else {
            hover(null);
        }
    }

    /** What the cursor is on now ({@code null}: nothing Tinker changes). */
    public void hover(Target target) {
        hovered = target;
    }

    /** The box outlining the hovered target (min x, y, z, max x, y, z), or nothing. */
    public Optional<double[]> outline() {
        return switch (hovered) {
            case null -> Optional.empty();
            case BlockTarget block -> Optional.of(new double[] {block.pos().x(), block.pos().y(), block.pos().z(),
                    block.pos().x() + 1, block.pos().y() + 1, block.pos().z() + 1});
            case EntityTarget entity -> Optional.of(entity.box());
        };
    }

    /**
     * A click on the hovered target: the block or entity a panel should show, or nothing (with a toast) without a
     * session, on a server without Tinker or without {@code region}.
     */
    public Optional<Target> click() {
        if (hovered == null || !allowed()) return Optional.empty();
        return Optional.of(hovered);
    }

    /**
     * Whether a change may be sent: a session, the {@code region} right and Tinker offered by the server; toasts why
     * not (quietly nothing without a session).
     */
    public boolean allowed() {
        Optional<EditorSession> session = host.session();
        if (session.isEmpty()) return false;
        if (!session.get().permissions().has(Perm.REGION)) {
            host.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.needs_permission", Perm.REGION.node()));
            return false;
        }
        if (!session.get().tinkerOffered()) {
            host.notify(Notice.of(Notice.Level.WARNING, NOT_OFFERED));
            return false;
        }
        return true;
    }

    public Optional<Target> hovered() {
        return Optional.ofNullable(hovered);
    }

    /** The text beside the cursor, or "" over nothing. */
    public String label() {
        return switch (hovered) {
            case null -> "";
            case BlockTarget block -> blockLabel(block.pos());
            case EntityTarget entity -> entityLabel(entity);
        };
    }

    /** "Oak Stairs · shape: outer left", "Stone · no properties". */
    public String blockLabel(BlockPos pos) {
        int state = stateAt(pos);
        if (state < 0) return "";
        String name = host.blockName(state);
        String property = chosenProperty(state);
        if (property == null) return name + " · " + host.translate("sculptory.tinker.no_properties");
        return name + " · " + TinkerProperties.shown(property, host.states().describe(state).get(property));
    }

    /** "Armor Stand · facing 90°", "Item Frame · item turned 135°", "Painting · kebab". */
    public String entityLabel(EntityTarget entity) {
        String detail = switch (entity.kind()) {
            case ARMOR_STAND, BLOCK_DISPLAY, ITEM_DISPLAY, TEXT_DISPLAY -> host.translate("sculptory.tinker.facing",
                    Integer.toString(Math.round(yawOf(entity))));
            case ITEM_FRAME, GLOW_ITEM_FRAME -> host.translate("sculptory.tinker.item_turned",
                    Integer.toString(rotationOf(entity) * 45));
            case PAINTING -> entity.variant().isEmpty() ? "" : TinkerProperties.shown(path(entity.variant()));
        };
        return detail.isEmpty() ? entity.name() : entity.name() + " · " + detail;
    }

    /** The property the label shows for {@code state}: the one remembered for its block, or the first useful one. */
    public String chosenProperty(int state) {
        return TinkerProperties.chosen(host.states(), state, remembered.get(host.states().blockId(state)));
    }

    /** Remembers {@code property} as the one shown for {@code state}'s block type this session. */
    public void chooseProperty(int state, String property) {
        remembered.put(host.states().blockId(state), Objects.requireNonNull(property));
        changed();
    }

    // =================================================================== scroll

    /** Whether Scroll over the hovered target changes something (else the editor's own Scroll, fly speed, applies). */
    public boolean takesScroll() {
        return switch (hovered) {
            case null -> false;
            case BlockTarget block -> {
                int state = stateAt(block.pos());
                yield state >= 0 && chosenProperty(state) != null;
            }
            case EntityTarget entity -> entity.kind().turns() || entity.kind().itemFrame();
        };
    }

    /**
     * Scroll over the hovered target ({@code amount} positive away from the player): the next value of the shown
     * property, or with {@code shift} the next property; an entity turns. A change needs {@link #allowed()} (a toast
     * says why not; the notch is still taken). Returns whether the notch was Tinker's.
     */
    public boolean scroll(double amount, boolean shift) {
        if (amount == 0 || !takesScroll()) return false;
        int steps = amount > 0 ? 1 : -1;
        switch (hovered) {
            case BlockTarget block -> {
                int state = stateAt(block.pos());
                String property = chosenProperty(state);
                if (shift) {
                    String next = TinkerProperties.stepProperty(host.states(), state, property, steps);
                    if (next != null && !next.equals(property)) chooseProperty(state, next);
                    return true;
                }
                if (!allowed()) return true;
                int next = TinkerProperties.stepValue(host.states(), state, property, steps);
                if (next != state) setState(block.pos(), next);
            }
            case EntityTarget entity -> {
                if (!allowed()) return true;
                if (entity.kind().itemFrame()) {
                    edit(entity.id(), List.of(new EntityEdit.ItemRotation(Math.floorMod(rotationOf(entity) + steps, 8))));
                } else {
                    float yaw = EntityEdits.wrapDegrees(yawOf(entity) + steps * (shift ? FINE_TURN_STEP : TURN_STEP));
                    edit(entity.id(), List.of(new EntityEdit.Yaw(yaw)));
                }
            }
        }
        return true;
    }

    // =================================================================== blocks

    /**
     * The state Tinker shows at {@code pos}: a change waiting or in flight, one just done that the world has not caught
     * up with, else the world's; -1 when the world has none there.
     */
    public int stateAt(BlockPos pos) {
        Pending waiting = queued.get(pos);
        if (waiting != null) return waiting.target();
        if (inFlight != null && inFlight.pos().equals(pos)) return inFlight.target();
        return baseState(pos);
    }

    /** The world's state, or a change just done that it has not caught up with. */
    private int baseState(BlockPos pos) {
        int world = host.world().get(pos.x(), pos.y(), pos.z());
        Shown done = shown.get(pos);
        if (done == null) return world;
        if (done.state() == world || host.nanoTime() - done.until() > 0) {
            shown.remove(pos);
            return world;
        }
        return done.state();
    }

    /** Sets the block at {@code pos} to {@code target} (the same block with other property values). */
    public void setState(BlockPos pos, int target) {
        Objects.requireNonNull(pos);
        Pending waiting = queued.get(pos);
        queued.put(pos, new Pending(target, waiting == null ? null : waiting.sign()));
        pump();
    }

    /** Sets the text of the sign at {@code pos}. */
    public void setSign(BlockPos pos, SignText text) {
        Objects.requireNonNull(pos);
        Objects.requireNonNull(text);
        Pending waiting = queued.get(pos);
        queued.put(pos, new Pending(waiting == null ? stateAt(pos) : waiting.target(), text));
        pump();
    }

    /** The sign text at {@code pos} as this client sees it, if it is a sign. */
    public Optional<SignText> signText(BlockPos pos) {
        return host.signText(pos);
    }

    /** Whether a change to {@code pos} is waiting or in flight. */
    public boolean busy(BlockPos pos) {
        return queued.containsKey(pos) || (inFlight != null && inFlight.pos().equals(pos));
    }

    private void pump() {
        if (inFlight != null) return;
        Optional<EditorSession> session = session();
        if (session.isEmpty()) {
            queued.clear();
            changed();
            return;
        }
        Iterator<Map.Entry<BlockPos, Pending>> next = queued.entrySet().iterator();
        while (next.hasNext()) {
            Map.Entry<BlockPos, Pending> entry = next.next();
            next.remove();
            BlockPos pos = entry.getKey();
            int expected = baseState(pos);
            Pending change = entry.getValue();
            if (expected < 0 || (expected == change.target() && change.sign() == null)) continue;
            BlockChange sent = new BlockChange(pos, expected, change.target(), change.sign());
            inFlight = sent;
            changed();
            session.get().tinkerBlock(pos, expected, change.target(), change.sign())
                    .thenAccept(reply -> blockDone(sent, reply));
            return;
        }
        changed();
    }

    private void blockDone(BlockChange sent, Reply<Boolean> reply) {
        if (inFlight != sent) return;
        inFlight = null;
        if (reply.isOk()) {
            shown.put(sent.pos(), new Shown(sent.target(), host.nanoTime() + SHOWN_NANOS));
        } else {
            // What waited for this block counted on this change: it goes too (the session toasted why).
            shown.remove(sent.pos());
            queued.remove(sent.pos());
        }
        pump();
    }

    // =================================================================== entities

    /** What the server last said the panel shows of entity {@code id}. */
    public Optional<EntityView> view(UUID id) {
        return Optional.ofNullable(views.get(id));
    }

    /** Asks the server what the panel shows of entity {@code id} (its answer arrives in {@link #view}). */
    public void look(UUID id) {
        Objects.requireNonNull(id);
        if (entityQueued.containsKey(id)) return;
        looks.add(id);
        entityQueued.put(id, new LinkedHashMap<>());
        pumpEntities();
    }

    /** Applies {@code edits} to entity {@code id} (merged with changes still waiting: the latest of each wins). */
    public void edit(UUID id, List<EntityEdit> edits) {
        Objects.requireNonNull(id);
        LinkedHashMap<String, EntityEdit> waiting = entityQueued.computeIfAbsent(id, key -> new LinkedHashMap<>());
        for (EntityEdit edit : edits) {
            waiting.remove(key(edit)); // keeps the order of the latest
            waiting.put(key(edit), edit);
        }
        pumpEntities();
    }

    /** The heading Tinker shows for an entity: a turn waiting or in flight, the last view, else the client's. */
    public float yawOf(EntityTarget entity) {
        return yawOf(entity.id(), entity.yaw());
    }

    /** The heading of entity {@code id} as Tinker shows it: a turn waiting or in flight, the last view, else {@code fallback}. */
    public float yawOf(UUID id, float fallback) {
        EntityEdit.Yaw yaw = latest(id, EntityEdit.Yaw.class);
        if (yaw != null) return yaw.degrees();
        EntityView view = views.get(id);
        return view != null ? view.yaw() : fallback;
    }

    /**
     * Where entity {@code id} stands as Tinker shows it (x, y, z): a move waiting or in flight, the last view, else
     * {@code fallback}. A panel arrow steps from here, so two quick clicks are two steps although the second waits for
     * the first (edits merge, the latest wins).
     */
    public double[] positionOf(UUID id, double[] fallback) {
        EntityEdit.Position position = latest(id, EntityEdit.Position.class);
        if (position != null) return new double[] {position.x(), position.y(), position.z()};
        EntityView view = views.get(id);
        return view != null ? new double[] {view.x(), view.y(), view.z()} : fallback.clone();
    }

    /** An item frame's item rotation as Tinker shows it. */
    public int rotationOf(EntityTarget entity) {
        EntityEdit.ItemRotation rotation = latest(entity.id(), EntityEdit.ItemRotation.class);
        if (rotation != null) return rotation.steps();
        EntityView view = views.get(entity.id());
        return view != null ? view.itemRotation() : entity.itemRotation();
    }

    /** Whether a change to entity {@code id} is waiting or in flight. */
    public boolean busy(UUID id) {
        return entityQueued.containsKey(id) || (entityInFlight != null && entityInFlight.id().equals(id));
    }

    private <T extends EntityEdit> T latest(UUID id, Class<T> type) {
        LinkedHashMap<String, EntityEdit> waiting = entityQueued.get(id);
        if (waiting != null) {
            for (EntityEdit edit : waiting.values()) {
                if (type.isInstance(edit)) return type.cast(edit);
            }
        }
        if (entityInFlight != null && entityInFlight.id().equals(id)) {
            for (EntityEdit edit : entityInFlight.edits()) {
                if (type.isInstance(edit)) return type.cast(edit);
            }
        }
        return null;
    }

    private void pumpEntities() {
        if (entityInFlight != null) return;
        Optional<EditorSession> session = session();
        if (session.isEmpty()) {
            entityQueued.clear();
            looks.clear();
            changed();
            return;
        }
        Iterator<Map.Entry<UUID, LinkedHashMap<String, EntityEdit>>> next = entityQueued.entrySet().iterator();
        if (!next.hasNext()) {
            changed();
            return;
        }
        Map.Entry<UUID, LinkedHashMap<String, EntityEdit>> entry = next.next();
        next.remove();
        looks.remove(entry.getKey());
        List<EntityEdit> edits = new ArrayList<>(entry.getValue().values());
        if (edits.size() > EntityEdits.MAX_EDITS) edits = edits.subList(edits.size() - EntityEdits.MAX_EDITS, edits.size());
        EntityChange sent = new EntityChange(entry.getKey(), List.copyOf(edits));
        entityInFlight = sent;
        changed();
        session.get().tinkerEntity(sent.id(), sent.edits()).thenAccept(reply -> entityDone(sent, reply));
    }

    private void entityDone(EntityChange sent, Reply<EntityView> reply) {
        if (entityInFlight != sent) return;
        entityInFlight = null;
        if (reply instanceof Reply.Ok<EntityView> ok) {
            views.put(sent.id(), ok.value());
        } else {
            entityQueued.remove(sent.id());
            if (reply instanceof Reply.Refused<EntityView> refused
                    && refused.detail().startsWith("the entity is gone")) {
                views.remove(sent.id());
            }
        }
        pumpEntities();
    }

    /** The merge key of an edit: one per setting (a pose part, a flag...), so the latest value wins. */
    static String key(EntityEdit edit) {
        return switch (edit) {
            case EntityEdit.Pose pose -> "pose:" + pose.part();
            case EntityEdit.Toggle toggle -> "flag:" + toggle.flag();
            default -> edit.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        };
    }

    // =================================================================== shared

    private Optional<EditorSession> session() {
        Optional<EditorSession> session = host.session();
        if (session.isEmpty() || !session.get().tinkerOffered()) {
            if (!queued.isEmpty() || !entityQueued.isEmpty()) {
                host.notify(Notice.of(Notice.Level.WARNING, NOT_OFFERED));
            }
            return Optional.empty();
        }
        return session;
    }

    private void changed() {
        for (Runnable listener : List.copyOf(listeners)) listener.run();
    }

    /** The path of a namespaced id ("kebab" of "minecraft:kebab"). */
    private static String path(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }
}
