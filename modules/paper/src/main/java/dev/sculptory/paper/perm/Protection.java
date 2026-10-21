package dev.sculptory.paper.perm;

import dev.sculptory.core.Box;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * A region plugin's answer to "may this player change these cells": WorldGuard now, others (PlotSquared, GriefPrevention)
 * later. Asked once per chunk of an edit, for the part of the edit box inside that chunk; the answer is per cell, since
 * regions are 3D (a region can cover a few layers of one chunk). Server thread only.
 */
public interface Protection {
    /** Protection that allows everything (no region plugin). */
    Protection NONE = new Protection() {
        @Override
        public CellTest cells(ServerPlayer player, ServerLevel world, Box part) {
            return CellTest.ALLOW;
        }

        @Override
        public String name() {
            return "none";
        }
    };

    /**
     * The cells of {@code part} (inside one chunk) the player may change. {@link CellTest#ALLOW} and
     * {@link CellTest#DENY} when the whole part has one answer (the caller then skips the per-cell work).
     */
    CellTest cells(ServerPlayer player, ServerLevel world, Box part);

    /** For logs and the self-test report. */
    String name();

    /** Whether the player may change cell (x, y, z), world coordinates. */
    @FunctionalInterface
    interface CellTest {
        CellTest ALLOW = (x, y, z) -> true;
        CellTest DENY = (x, y, z) -> false;

        boolean allowed(int x, int y, int z);
    }
}
