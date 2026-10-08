package dev.sculptory.server.platform;

import java.util.List;

/**
 * One world's biomes as the engine reads and writes them (protocol 6; {@link Platform#biomes}), made per job, stroke
 * or request and used on the server thread only. It never loads chunks.
 *
 * <p>Biomes are stored per <i>quart</i>: a 4×4×4 cell whose minimum block is {@code (4 qx, 4 qy, 4 qz)} (quart
 * coordinates are block coordinates shifted right by 2; quart column {@code (qx, qz)} lies in chunk
 * {@code (qx >> 2, qz >> 2)}). A world's quarts run from {@code bottomY >> 2} to {@code (topY >> 2) - 1} in y.
 *
 * <p>A biome is named by its registry id ("minecraft:plains") or, for per-cell work, by a <i>handle</i>: a small
 * non-negative int, the id's index in {@link #ids()}. Handles are stable while the server runs, not across restarts
 * (history and clipboards keep id text).
 */
public interface WorldBiomes {
    /** Every biome id the world's registry knows, in handle order (handle {@code i} is {@code ids().get(i)}). */
    List<String> ids();

    /** The handle of biome {@code id}, or -1 when the registry has no such biome. */
    int handle(String id);

    /**
     * The biome id of {@code handle}.
     *
     * @throws IllegalArgumentException if no biome has that handle
     */
    String id(int handle);

    /** Whether chunk {@code (cx, cz)} is loaded (fully, as edits need it); never loads it. */
    boolean loaded(int cx, int cz);

    /**
     * The biome handle of quart {@code (qx, qy, qz)}.
     *
     * @throws IllegalStateException if its chunk is not loaded
     * @throws IllegalArgumentException if {@code qy} is outside the world's height
     */
    int get(int qx, int qy, int qz);

    /**
     * Sets quart {@code (qx, qy, qz)} to biome {@code handle} exactly (that one quart, no blending), marks its chunk to be
     * saved and to be sent at the next {@link #flush}, and returns the handle it held. Setting the biome it holds
     * changes nothing and marks nothing.
     *
     * @throws IllegalStateException if its chunk is not loaded
     * @throws IllegalArgumentException if {@code qy} is outside the world's height or {@code handle} names no biome
     */
    int set(int qx, int qy, int qz, int handle);

    /**
     * Sends the biomes of every chunk {@link #set} changed since the last flush to the players watching those chunks (as
     * the game's own biome update, so clients without Sculptory recolour too), and forgets them. Returns how many chunks
     * it sent.
     */
    int flush();
}
