package dev.sculptory.mc.client.tinker;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.tinker.SignText;
import dev.sculptory.core.tinker.TinkerKind;
import dev.sculptory.mc.client.editor.world.Ray;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Tinker's reads of the client world, for the editor's tool and builder mode: the
 * entity Tinker changes under the cursor ray, and a sign's text. Render thread only.
 */
public final class McTinker {
    /** How far entities are looked for when the ray meets no block. */
    public static final double REACH = 64;
    /** Half the size of the box a text or item display is picked by (they have no box of their own). */
    static final double DISPLAY_HALF = 0.3;

    private McTinker() {}

    /**
     * The nearest entity Tinker changes whose box the ray meets within {@code maxDistance} blocks of its origin, as a
     * {@link TinkerController.EntityTarget}. Displays have no box of their own: a block display is picked by the block
     * it shows (its scale, turned boxes ignored), an item or text display by a small box around where it stands.
     */
    public static Optional<TinkerController.EntityTarget> entityAt(ClientLevel world, Ray ray, double maxDistance) {
        if (world == null || ray == null || !(maxDistance > 0)) return Optional.empty();
        Vec3 start = new Vec3(ray.originX(), ray.originY(), ray.originZ());
        Vec3 end = new Vec3(ray.pointX(maxDistance), ray.pointY(maxDistance), ray.pointZ(maxDistance));
        AABB around = new AABB(start, end).inflate(2);
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : world.getEntities((Entity) null, around, e -> !e.isRemoved() && kind(e).isPresent())) {
            Optional<Vec3> hit = pickBox(entity).clip(start, end);
            if (hit.isEmpty()) continue;
            double distance = hit.get().distanceTo(start);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best == null ? Optional.empty() : Optional.of(target(best));
    }

    /** The target the controller keeps for an entity. */
    public static TinkerController.EntityTarget target(Entity entity) {
        TinkerKind kind = kind(entity).orElseThrow(() -> new IllegalArgumentException("Not a Tinker entity: " + entity));
        int rotation = entity instanceof ItemFrame frame ? frame.getRotation() : 0;
        String variant = entity instanceof Painting painting
                ? painting.getVariant().unwrapKey().map(key -> key.location().toString()).orElse("") : "";
        AABB box = pickBox(entity);
        return new TinkerController.EntityTarget(entity.getUUID(), kind, entity.getName().getString(), entity.getYRot(),
                rotation, variant, new double[] {box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ});
    }

    /** The kind of an entity, if Tinker changes it. */
    public static Optional<TinkerKind> kind(Entity entity) {
        return TinkerKind.of(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
    }

    /** The box an entity is picked and outlined by. */
    static AABB pickBox(Entity entity) {
        if (entity instanceof Display.BlockDisplay) {
            Vec3 at = entity.position();
            return new AABB(at, at.add(1, 1, 1));
        }
        if (entity instanceof Display) {
            return new AABB(entity.position(), entity.position()).inflate(DISPLAY_HALF);
        }
        return entity.getBoundingBox().inflate(entity.getPickRadius() + 0.02);
    }

    /** The text of the sign at {@code pos} as this client sees it, if the block is a sign or hanging sign. */
    public static Optional<SignText> signText(ClientLevel world, BlockPos pos) {
        if (world == null) return Optional.empty();
        BlockEntity entity = world.getBlockEntity(new net.minecraft.core.BlockPos(pos.x(), pos.y(), pos.z()));
        if (!(entity instanceof SignBlockEntity sign)) return Optional.empty();
        return Optional.of(new SignText(side(sign.getFrontText()), side(sign.getBackText())));
    }

    private static SignText.Side side(net.minecraft.world.level.block.entity.SignText text) {
        List<String> lines = new ArrayList<>(SignText.LINES);
        for (int i = 0; i < SignText.LINES; i++) lines.add(text.getMessage(i, false).getString());
        String color = text.getColor().getName();
        return new SignText.Side(lines, SignText.COLORS.contains(color) ? color : SignText.DEFAULT_COLOR, text.hasGlowingText());
    }
}
