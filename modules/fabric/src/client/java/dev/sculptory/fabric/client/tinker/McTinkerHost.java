package dev.sculptory.fabric.client.tinker;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.tinker.SignText;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.EditorBackend;
import dev.sculptory.fabric.client.editor.SessionProvider;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.mc.McBlockCatalog;
import dev.sculptory.fabric.client.editor.mc.McTranslator;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.world.Ray;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Notice;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;

/**
 * Tinker outside the editor (builder mode, with Alt held): a
 * {@link TinkerController.Host} over the running client, without the editor's tool context. The session, states and
 * world are the client's editor backend ({@link SessionProvider}), block names the catalog's, the entity pick the ray
 * from the player's eyes along the crosshair.
 *
 * <p>Use: {@code TinkerController tinker = new TinkerController(new McTinkerHost(client, toasts))}; each frame while
 * Alt is held, {@code tinker.aim(host.crosshair(reach, tickDelta))}; then the controller's {@code label}, {@code outline},
 * {@code takesScroll}/{@code scroll} and {@code click} (see {@link TinkerController}). Render thread only.
 */
public final class McTinkerHost implements TinkerController.Host {
    private final MinecraftClient client;
    private final Supplier<EditorBackend> backend;
    private final McBlockCatalog blocks = new McBlockCatalog();
    private final Translator translator = new McTranslator();
    private final Consumer<Notice> notices;
    /** The tick delta of the last {@link #crosshair} call, so the entity pick sees the same frame. */
    private float tickDelta = 1f;

    /** @param notices where the controller's toasts go (a missing right, a server without Tinker) */
    public McTinkerHost(MinecraftClient client, Consumer<Notice> notices) {
        this(client, SessionProvider::get, notices);
    }

    McTinkerHost(MinecraftClient client, Supplier<EditorBackend> backend, Consumer<Notice> notices) {
        this.client = Objects.requireNonNull(client);
        this.backend = Objects.requireNonNull(backend);
        this.notices = Objects.requireNonNull(notices);
    }

    /**
     * What the player's crosshair is on within {@code reach} blocks: the block it hits (fluids passed through), else a
     * miss at {@code reach}; {@code null} without a player (the controller then aims at nothing).
     */
    public WorldCursor crosshair(double reach, float tickDelta) {
        ClientPlayerEntity player = client.player;
        this.tickDelta = tickDelta;
        if (player == null || !(reach > 0)) return null;
        HitResult hit = player.raycast(reach, tickDelta, false);
        Vec3d at = hit.getPos();
        if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
            net.minecraft.util.math.BlockPos pos = block.getBlockPos();
            return new WorldCursor(new BlockPos(pos.getX(), pos.getY(), pos.getZ()),
                    WorldCursor.Face.valueOf(block.getSide().name()), at.x, at.y, at.z, false);
        }
        return WorldCursor.miss(at.x, at.y, at.z);
    }

    /** The ray from the player's eyes along the crosshair, if there is a player. */
    public Optional<Ray> crosshairRay(float tickDelta) {
        ClientPlayerEntity player = client.player;
        if (player == null) return Optional.empty();
        Vec3d eyes = player.getCameraPosVec(tickDelta);
        Vec3d look = player.getRotationVec(tickDelta);
        return Optional.of(new Ray(eyes.x, eyes.y, eyes.z, look.x, look.y, look.z));
    }

    @Override
    public Optional<TinkerController.EntityTarget> entityAt(WorldCursor cursor) {
        Optional<Ray> ray = crosshairRay(tickDelta);
        if (ray.isEmpty() || cursor == null) return Optional.empty();
        double reach = McTinker.REACH;
        if (!cursor.missed()) {
            Ray r = ray.get();
            double dx = cursor.hitX() - r.originX(), dy = cursor.hitY() - r.originY(), dz = cursor.hitZ() - r.originZ();
            reach = Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return McTinker.entityAt(client.world, ray.get(), reach);
    }

    @Override
    public Optional<EditorSession> session() {
        return backend.get().session();
    }

    @Override
    public StateSpace states() {
        return backend.get().states();
    }

    @Override
    public WorldReader world() {
        return backend.get().world();
    }

    @Override
    public String blockName(int state) {
        return blocks.name(states().describe(state));
    }

    @Override
    public Optional<SignText> signText(BlockPos pos) {
        return McTinker.signText(client.world, pos);
    }

    @Override
    public String translate(String key, Object... args) {
        return translator.translate(key, args);
    }

    @Override
    public void notify(Notice notice) {
        notices.accept(notice);
    }

    @Override
    public long nanoTime() {
        return System.nanoTime();
    }
}
