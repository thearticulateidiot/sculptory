package dev.sculptory.fabric.client.session;

import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.PermissionMask;
import dev.sculptory.server.engine.Perm;
import java.util.Objects;

/** The player's granted nodes and the server limits, from Welcome/PermissionsChanged. */
public record Permissions(PermissionMask mask, Limits limits) {
    public static final Permissions NONE = new Permissions(PermissionMask.NONE, Limits.DEFAULTS);

    public Permissions {
        Objects.requireNonNull(mask);
        Objects.requireNonNull(limits);
    }

    public boolean has(Perm perm) {
        return mask.has(perm.bit());
    }
}
