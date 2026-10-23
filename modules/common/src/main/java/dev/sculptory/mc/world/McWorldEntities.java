package dev.sculptory.mc.world;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.history.EntityState;
import dev.sculptory.core.region.Region;
import dev.sculptory.server.platform.EntityPlacer;
import dev.sculptory.server.platform.WorldEntities;
import dev.sculptory.server.platform.WriteOptions;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/** A {@link ServerLevel}'s entities for the engine: {@link McEntities} and {@link EntityWriter} over one world. */
public final class McWorldEntities implements WorldEntities<Entity> {
    private final ServerLevel world;

    public McWorldEntities(ServerLevel world) {
        this.world = Objects.requireNonNull(world);
    }

    @Override
    public boolean loaded(int cx, int cz) {
        return McEntities.loaded(world, cx, cz);
    }

    @Override
    public String firstUnloaded(long[] columns) {
        return McEntities.firstUnloaded(world, columns);
    }

    @Override
    public List<Entity> inRegion(Region region, EntityFilter filter, long[] columns) {
        return McEntities.inRegion(world, region, filter, columns);
    }

    @Override
    public List<Entity> inRegion(Region region, EntityFilter filter, int cx, int cz) {
        return McEntities.inRegion(world, region, filter, cx, cz);
    }

    @Override
    public Entity find(UUID id) {
        return McEntities.find(world, id);
    }

    @Override
    public UUID id(Entity entity) {
        return entity.getUUID();
    }

    @Override
    public boolean removed(Entity entity) {
        return entity.isRemoved();
    }

    @Override
    public boolean riding(Entity entity) {
        return entity.isPassenger();
    }

    @Override
    public BlockPos cell(Entity entity) {
        net.minecraft.core.BlockPos cell = McEntities.cell(entity);
        return new BlockPos(cell.getX(), cell.getY(), cell.getZ());
    }

    @Override
    public BlockPos blockPos(Entity entity) {
        return new BlockPos(entity.getBlockX(), entity.getBlockY(), entity.getBlockZ());
    }

    @Override
    public boolean hangsUnsupported(Entity entity) {
        return McEntities.hangsUnsupported(entity);
    }

    @Override
    public boolean supportLoaded(Entity entity) {
        return McEntities.supportLoaded(entity);
    }

    @Override
    public dev.sculptory.core.Box supportBox(Entity entity) {
        return McEntities.supportBox(entity);
    }

    @Override
    public int takenCount(Entity entity, EntityFilter filter) {
        return McEntities.takenCount(entity, filter);
    }

    @Override
    public EntitySnapshot snapshot(Entity entity, EntityFilter filter, BlockPos min, boolean trusted) {
        return McEntities.snapshot(entity, filter, min, trusted);
    }

    @Override
    public EntityState state(Entity entity, EntityFilter filter) {
        return McEntities.state(entity, filter);
    }

    @Override
    public EntityState live(Entity entity) {
        return EntityWriter.live(entity);
    }

    @Override
    public String typeId(Entity entity) {
        return McEntities.typeId(entity);
    }

    /** An {@link EntityWriter} with the entity type rules ({@link EntityTypeRules#scan}, learned now if not yet). */
    @Override
    public EntityPlacer<Entity> placer(WriteOptions options) {
        return new EntityWriter(world, options, EntityTypeRules.scan(world));
    }
}
