package dev.sculptory.server.engine;

import dev.sculptory.protocol.v2.PermissionMask;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * Permission nodes. {@link #bit()} is the node's bit in {@link PermissionMask}:
 * append new nodes only, never reorder.
 */
public enum Perm {
    USE("sculptory.use"),
    BRUSH("sculptory.brush"),
    REGION("sculptory.region"),
    CLIPBOARD("sculptory.clipboard"),
    SCHEMATIC_IMPORT("sculptory.schematic.import"),
    SCHEMATIC_EXPORT("sculptory.schematic.export"),
    LIBRARY_WRITE("sculptory.library.write"),
    SCATTER("sculptory.scatter"),
    PHYSICS("sculptory.physics"),
    NBT_OPERATOR("sculptory.nbt.operator"),
    LIMIT_BYPASS("sculptory.limit.bypass"),
    ADMIN("sculptory.admin"),
    /** Loading chunks beyond the loaded area for region ops. */
    EDIT_UNLOADED("sculptory.edit.unloaded"),
    /** Builder mode's powers in normal creative play, outside the editor (needs {@link #USE}). */
    BUILDER("sculptory.builder"),
    /**
     * Jump and Through, in the editor and in normal play (needs {@link #USE} and Creative or
     * Spectator); granted like {@link #BRUSH}, by the op-level fallback.
     */
    NAVIGATE("sculptory.navigate");

    private final String node;

    Perm(String node) {
        this.node = node;
    }

    public String node() {
        return node;
    }

    public int bit() {
        return ordinal();
    }

    public static PermissionMask mask(Collection<Perm> granted) {
        long bits = 0;
        for (Perm perm : granted) bits |= 1L << perm.bit();
        return new PermissionMask(bits);
    }

    public static Set<Perm> granted(PermissionMask mask) {
        EnumSet<Perm> granted = EnumSet.noneOf(Perm.class);
        for (Perm perm : values()) {
            if (mask.has(perm.bit())) granted.add(perm);
        }
        return granted;
    }
}
