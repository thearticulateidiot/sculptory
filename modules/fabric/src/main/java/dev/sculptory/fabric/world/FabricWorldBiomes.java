package dev.sculptory.fabric.world;

import dev.sculptory.server.platform.WorldBiomes;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.collection.IndexedIterable;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.ReadableContainer;
import net.minecraft.world.chunk.WorldChunk;

/**
 * One world's biomes on Fabric (protocol 6; {@link WorldBiomes}): quarts read from and written into the chunk sections'
 * biome containers exactly, one quart at a time (no blending, unlike {@code /fillbiome}, which this follows otherwise).
 * One instance per world ({@code EngineRuntime.biomes}), so changed chunks from every job and stroke collect in one
 * set and go out once per tick ({@link #flush}). Server thread only; never loads a chunk.
 *
 * <p><b>Handles</b> are the biome registry's raw ids (what the containers' palettes index), fixed while the server runs:
 * worldgen registries do not change after start. <b>Writes</b> swap the value in the section's
 * {@link PalettedContainer} (the only {@link ReadableContainer} the game makes); a container of another kind (a mod's)
 * is replaced through {@link ChunkSection#populateBiomes}, keeping every other quart. The chunk is flagged to be saved.
 * <b>Sending</b> uses the game's own biome packet ({@code ChunkBiomeDataS2CPacket}, through
 * {@code ServerChunkLoadingManager.sendChunkBiomePackets}), so clients without the mod recolour too.
 */
public final class FabricWorldBiomes implements WorldBiomes {
    private final ServerWorld world;
    private final IndexedIterable<RegistryEntry<Biome>> entries;
    private final List<String> ids;
    private final Object2IntOpenHashMap<String> handles = new Object2IntOpenHashMap<>();
    /** Chunks changed since the last flush ({@link ChunkPos#toLong}), in the order first changed. */
    private final LongLinkedOpenHashSet changed = new LongLinkedOpenHashSet();

    public FabricWorldBiomes(ServerWorld world) {
        this.world = Objects.requireNonNull(world);
        Registry<Biome> registry = world.getRegistryManager().get(RegistryKeys.BIOME);
        this.entries = registry.getIndexedEntries();
        List<String> list = new ArrayList<>(entries.size());
        handles.defaultReturnValue(-1);
        for (int i = 0; i < entries.size(); i++) {
            String id = registry.getId(entries.get(i).value()).toString();
            list.add(id);
            handles.put(id, i);
        }
        this.ids = List.copyOf(list);
    }

    @Override
    public List<String> ids() {
        return ids;
    }

    @Override
    public int handle(String id) {
        return handles.getInt(id);
    }

    @Override
    public String id(int handle) {
        if (handle < 0 || handle >= ids.size()) throw new IllegalArgumentException("No biome has handle " + handle);
        return ids.get(handle);
    }

    @Override
    public boolean loaded(int cx, int cz) {
        return WorldChecks.isChunkLoaded(world, cx, cz);
    }

    @Override
    public int get(int qx, int qy, int qz) {
        ChunkSection section = section(chunk(qx, qz), qy);
        return entries.getRawId(section.getBiome(qx & 3, qy & 3, qz & 3));
    }

    @Override
    public int set(int qx, int qy, int qz, int handle) {
        if (handle < 0 || handle >= ids.size()) throw new IllegalArgumentException("No biome has handle " + handle);
        WorldChunk chunk = chunk(qx, qz);
        ChunkSection section = section(chunk, qy);
        int x = qx & 3, y = qy & 3, z = qz & 3;
        RegistryEntry<Biome> target = entries.get(handle);
        int before = entries.getRawId(section.getBiome(x, y, z));
        // A biome the registry does not index (a mod put one in directly) is never written over: undo could not say what
        // it was.
        if (before == handle || before < 0) return before;
        ReadableContainer<RegistryEntry<Biome>> container = section.getBiomeContainer();
        if (container instanceof PalettedContainer<RegistryEntry<Biome>> palette) {
            palette.swapUnsafe(x, y, z, target);
        } else {
            // A container the game does not make: rebuilt, every other quart kept (the supplier gets quart coordinates).
            int bx = qx & ~3, by = qy & ~3, bz = qz & ~3;
            section.populateBiomes((sx, sy, sz, noise) -> sx == qx && sy == qy && sz == qz ? target
                    : section.getBiome(sx - bx, sy - by, sz - bz), null, bx, by, bz);
        }
        chunk.setNeedsSaving(true);
        changed.add(ChunkPos.toLong(qx >> 2, qz >> 2));
        return before;
    }

    @Override
    public int flush() {
        if (changed.isEmpty()) return 0;
        List<Chunk> chunks = new ArrayList<>(changed.size());
        for (long key : changed) {
            WorldChunk chunk = world.getChunkManager().getWorldChunk(ChunkPos.getPackedX(key), ChunkPos.getPackedZ(key));
            if (chunk != null) chunks.add(chunk);
        }
        changed.clear();
        if (!chunks.isEmpty()) world.getChunkManager().chunkLoadingManager.sendChunkBiomePackets(chunks);
        return chunks.size();
    }

    private WorldChunk chunk(int qx, int qz) {
        WorldChunk chunk = world.getChunkManager().getWorldChunk(qx >> 2, qz >> 2);
        if (chunk == null) throw new IllegalStateException("Chunk " + (qx >> 2) + "," + (qz >> 2) + " is not loaded");
        return chunk;
    }

    private static ChunkSection section(WorldChunk chunk, int qy) {
        int index = chunk.getSectionIndex(qy << 2);
        ChunkSection[] sections = chunk.getSectionArray();
        if (index < 0 || index >= sections.length) throw new IllegalArgumentException("Quart y " + qy + " outside the world");
        return sections[index];
    }
}
