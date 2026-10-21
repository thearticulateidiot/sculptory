package dev.sculptory.paper.perm;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.mc.perm.McPermissionService;
import dev.sculptory.mc.perm.McPermissions;
import dev.sculptory.mc.perm.Tri;
import dev.sculptory.protocol.v2.PermissionMask;
import dev.sculptory.server.config.SculptoryConfig;
import dev.sculptory.server.engine.ChunkPermit;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.perm.ChunkPermits;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Permissions on Paper: nodes as the common {@link McPermissionService} answers them (through the loader seam: Bukkit
 * permissions with the operator-level fallback), and chunk permits that also ask a region plugin ({@link Protection}).
 *
 * <p>Chunk permits follow the contract on {@code PermissionService.chunk}: right for every cell of the chunk's part of
 * {@code bounds}, at every height in {@code bounds} (consumers ask at each cell's own height, biome work with bounds
 * widened to whole quarts). Regions differ with height, so the region permit is {@code ChunkPermits.rasterize} over the
 * part (every cell asked; the WorldGuard cell test answers from region geometry and one query per region combination),
 * or {@code ChunkPermits.uniform} when the region plugin reports no region touching the part. The vanilla permit (spawn
 * protection, world border; the same at every height, so the column rule) is intersected with it cell by cell.
 */
public final class PaperPermissions implements McPermissions {
    private final McPermissionService nodes;
    private final Protection protection;

    public PaperPermissions(McPermissionService nodes, Protection protection) {
        this.nodes = Objects.requireNonNull(nodes);
        this.protection = Objects.requireNonNull(protection);
    }

    public McPermissionService nodes() {
        return nodes;
    }

    public Protection protection() {
        return protection;
    }

    @Override
    public boolean has(ServerPlayer p, Perm node) {
        return nodes.has(p, node);
    }

    @Override
    public boolean denied(ServerPlayer p, Perm node) {
        return nodes.denied(p, node);
    }

    @Override
    public Tri check(ServerPlayer p, Perm node) {
        return nodes.check(p, node);
    }

    @Override
    public PermissionMask mask(ServerPlayer p) {
        return nodes.mask(p);
    }

    @Override
    public void configure(SculptoryConfig config) {
        nodes.configure(config);
    }

    @Override
    public boolean mayWriteOperatorNbt(ServerPlayer p) {
        return nodes.mayWriteOperatorNbt(p);
    }

    @Override
    public boolean operatorNbtDenied(ServerPlayer p) {
        return nodes.operatorNbtDenied(p);
    }

    /** The vanilla permit (spawn protection, world border) and the region permit, both required, cell by cell. */
    @Override
    public ChunkPermit chunk(ServerPlayer p, ServerLevel w, int cx, int cz, Box bounds) {
        ChunkPermit vanilla = nodes.chunk(p, w, cx, cz, bounds);
        if (vanilla instanceof ChunkPermit.Deny) return vanilla;
        ChunkPermit regions = regionPermit(p, w, cx, cz, bounds);
        return intersect(vanilla, regions, cx, cz, bounds);
    }

    /** What the region plugin allows of the part of {@code bounds} inside chunk (cx, cz), cell by cell. */
    ChunkPermit regionPermit(ServerPlayer p, ServerLevel w, int cx, int cz, Box bounds) {
        int x0 = Math.max(bounds.min().x(), cx << 4), x1 = Math.min(bounds.max().x(), (cx << 4) + 15);
        int z0 = Math.max(bounds.min().z(), cz << 4), z1 = Math.min(bounds.max().z(), (cz << 4) + 15);
        if (x0 > x1 || z0 > z1) return ChunkPermit.ALLOW;
        Box part = new Box(new BlockPos(x0, bounds.min().y(), z0), new BlockPos(x1, bounds.max().y(), z1));
        Protection.CellTest cells = protection.cells(p, w, part);
        // ALLOW or DENY: the region plugin knows no region touches the part, so one cell answers for all of it.
        if (cells == Protection.CellTest.ALLOW || cells == Protection.CellTest.DENY) {
            return ChunkPermits.uniform(cx, cz, bounds, cells);
        }
        return ChunkPermits.rasterize(cx, cz, bounds, cells);
    }

    /**
     * Both permits, cell by cell over the part of {@code bounds} in the chunk: {@code Columns} when neither changes
     * with height (one layer is enough then), else {@code Cells} over the bounds' heights.
     */
    static ChunkPermit intersect(ChunkPermit a, ChunkPermit b, int cx, int cz, Box bounds) {
        if (a instanceof ChunkPermit.Allow) return b;
        if (b instanceof ChunkPermit.Allow) return a;
        if (a instanceof ChunkPermit.Deny || b instanceof ChunkPermit.Deny) return ChunkPermit.DENY;
        boolean flat = !(a instanceof ChunkPermit.Cells) && !(b instanceof ChunkPermit.Cells);
        int y0 = bounds.min().y(), y1 = flat ? y0 : bounds.max().y();
        Box layers = new Box(new BlockPos(bounds.min().x(), y0, bounds.min().z()),
                new BlockPos(bounds.max().x(), y1, bounds.max().z()));
        return ChunkPermits.rasterize(cx, cz, layers, (x, y, z) -> a.allows(x, y, z) && b.allows(x, y, z));
    }
}
