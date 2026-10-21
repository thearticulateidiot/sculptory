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
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Permissions on Paper: nodes as the common {@link McPermissionService} answers them (through the loader seam: Bukkit
 * permissions with the operator-level fallback), and chunk permits that also ask a region plugin ({@link Protection}).
 *
 * <p>Permits are 2D today ({@link ChunkPermit.Columns}: a column is allowed or not). A region is 3D, so the bridge here
 * allows a column only when every cell of the edit box's height in it is allowed: a region covering any of those cells
 * denies the whole column of the box. Unlike {@code ChunkPermits.forChunk}'s corner sampling, every column of the
 * chunk's part is asked, so a region smaller than a chunk inside it is never missed.
 *
 * <p>Where the 3D permits slot in (branch ws/permits-3d: {@code ChunkPermit.allows(x, y, z)}, a {@code Cells} variant
 * for the box's y range, {@code ChunkPermits.forChunk(cx, cz, bounds, CellTest)}): {@link #regionPermit} becomes
 * {@code ChunkPermits.forChunk(cx, cz, part, cells::allowed)} with {@link Protection.CellTest} as that API's cell test,
 * and the intersection with the vanilla permit becomes cell-wise. Nothing else here changes.
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

    /** The vanilla permit (spawn protection, world border) and the region permit, both required. */
    @Override
    public ChunkPermit chunk(ServerPlayer p, ServerLevel w, int cx, int cz, Box bounds) {
        ChunkPermit vanilla = nodes.chunk(p, w, cx, cz, bounds);
        if (vanilla instanceof ChunkPermit.Deny) return vanilla;
        ChunkPermit regions = regionPermit(p, w, cx, cz, bounds);
        return intersect(vanilla, regions, cx, cz);
    }

    /** What the region plugin allows of the part of {@code bounds} inside chunk (cx, cz), column by column. */
    ChunkPermit regionPermit(ServerPlayer p, ServerLevel w, int cx, int cz, Box bounds) {
        int x0 = Math.max(bounds.min().x(), cx << 4), x1 = Math.min(bounds.max().x(), (cx << 4) + 15);
        int z0 = Math.max(bounds.min().z(), cz << 4), z1 = Math.min(bounds.max().z(), (cz << 4) + 15);
        if (x0 > x1 || z0 > z1) return ChunkPermit.ALLOW;
        int y0 = Math.max(bounds.min().y(), w.getMinBuildHeight());
        int y1 = Math.min(bounds.max().y(), w.getMaxBuildHeight() - 1);
        if (y0 > y1) return ChunkPermit.ALLOW;
        Box part = new Box(new BlockPos(x0, y0, z0), new BlockPos(x1, y1, z1));
        Protection.CellTest cells = protection.cells(p, w, part);
        if (cells == Protection.CellTest.ALLOW) return ChunkPermit.ALLOW;
        if (cells == Protection.CellTest.DENY) return ChunkPermit.DENY;
        long[] bits = new long[4];
        boolean any = false, all = true;
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                if (columnAllowed(cells, x, z, y0, y1)) {
                    int bit = ((z & 15) << 4) | (x & 15);
                    bits[bit >>> 6] |= 1L << bit;
                    any = true;
                } else {
                    all = false;
                }
            }
        }
        if (all) return ChunkPermit.ALLOW;
        return any ? new ChunkPermit.Columns(bits) : ChunkPermit.DENY;
    }

    /** The 2D bridge: a column is allowed when every cell of the box's height in it is. */
    private static boolean columnAllowed(Protection.CellTest cells, int x, int z, int y0, int y1) {
        for (int y = y0; y <= y1; y++) {
            if (!cells.allowed(x, y, z)) return false;
        }
        return true;
    }

    private static ChunkPermit intersect(ChunkPermit a, ChunkPermit b, int cx, int cz) {
        if (a instanceof ChunkPermit.Allow) return b;
        if (b instanceof ChunkPermit.Allow) return a;
        if (a instanceof ChunkPermit.Deny || b instanceof ChunkPermit.Deny) return ChunkPermit.DENY;
        long[] bits = new long[4];
        boolean any = false;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int x = (cx << 4) | lx, z = (cz << 4) | lz;
                if (a.allows(x, z) && b.allows(x, z)) {
                    int bit = (lz << 4) | lx;
                    bits[bit >>> 6] |= 1L << bit;
                    any = true;
                }
            }
        }
        return any ? new ChunkPermit.Columns(bits) : ChunkPermit.DENY;
    }
}
