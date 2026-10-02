package dev.sculptory.fabric.world;

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
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;

/** A {@link ServerWorld}'s entities for the engine: {@link FabricEntities} and {@link EntityWriter} over one world. */
public final class FabricWorldEntities implements WorldEntities<Entity> {
    private final ServerWorld world;

    public FabricWorldEntities(ServerWorld world) {
        this.world = Objects.requireNonNull(world);
    }

    @Override
    public boolean loaded(int cx, int cz) {
        return FabricEntities.loaded(world, cx, cz);
    }

    @Override
    public String firstUnloaded(long[] columns) {
        return FabricEntities.firstUnloaded(world, columns);
    }

    @Override
    public List<Entity> inRegion(Region region, EntityFilter filter, long[] columns) {
        return FabricEntities.inRegion(world, region, filter, columns);
    }

    @Override
    public List<Entity> inRegion(Region region, EntityFilter filter, int cx, int cz) {
        return FabricEntities.inRegion(world, region, filter, cx, cz);
    }

    @Override
    public Entity find(UUID id) {
        return FabricEntities.find(world, id);
    }

    @Override
    public UUID id(Entity entity) {
        return entity.getUuid();
    }

    @Override
    public boolean removed(Entity entity) {
        return entity.isRemoved();
    }

    @Override
    public boolean riding(Entity entity) {
        return entity.hasVehicle();
    }

    @Override
    public BlockPos cell(Entity entity) {
        net.minecraft.util.math.BlockPos cell = FabricEntities.cell(entity);
        return new BlockPos(cell.getX(), cell.getY(), cell.getZ());
    }

    @Override
    public BlockPos blockPos(Entity entity) {
        return new BlockPos(entity.getBlockX(), entity.getBlockY(), entity.getBlockZ());
    }

    @Override
    public int takenCount(Entity entity, EntityFilter filter) {
        return FabricEntities.takenCount(entity, filter);
    }

    @Override
    public EntitySnapshot snapshot(Entity entity, EntityFilter filter, BlockPos min, boolean trusted) {
        return FabricEntities.snapshot(entity, filter, min, trusted);
    }

    @Override
    public EntityState state(Entity entity, EntityFilter filter) {
        return FabricEntities.state(entity, filter);
    }

    @Override
    public EntityState live(Entity entity) {
        return EntityWriter.live(entity);
    }

    @Override
    public String typeId(Entity entity) {
        return FabricEntities.typeId(entity);
    }

    /** An {@link EntityWriter} with the entity type rules ({@link EntityTypeRules#scan}, learned now if not yet). */
    @Override
    public EntityPlacer<Entity> placer(WriteOptions options) {
        return new EntityWriter(world, options, EntityTypeRules.scan(world));
    }
}
