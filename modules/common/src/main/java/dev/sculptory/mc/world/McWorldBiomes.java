package dev.sculptory.mc.world;

import dev.sculptory.server.platform.WorldBiomes;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/**
 * One world's biomes in Minecraft (protocol 6; {@link WorldBiomes}): quarts read from and written into the chunk sections'
 * biome containers exactly, one quart at a time (no blending, unlike {@code /fillbiome}, which this follows otherwise).
 * One instance per world ({@code EngineRuntime.biomes}), so changed chunks from every job and stroke collect in one
 * set and go out once per tick ({@link #flush}). Server thread only; never loads a chunk.
 *
 * <p><b>Handles</b> are the biome registry's raw ids (what the containers' palettes index), fixed while the server runs:
 * worldgen registries do not change after start. <b>Writes</b> swap the value in the section's
 * {@link PalettedContainer} (the only {@link PalettedContainerRO} the game makes); a container of another kind (a mod's)
 * is replaced through {@link LevelChunkSection#fillBiomesFromNoise}, keeping every other quart. The chunk is flagged to be saved.
 * <b>Sending</b> uses the game's own biome packet ({@code ChunkBiomeDataS2CPacket}, through
 * {@code ServerChunkLoadingManager.sendChunkBiomePackets}), so clients without the mod recolour too.
 */
public final class McWorldBiomes implements WorldBiomes {
    private final ServerLevel world;
    private final IdMap<Holder<Biome>> entries;
    private final List<String> ids;
    private final Object2IntOpenHashMap<String> handles = new Object2IntOpenHashMap<>();
    /** Chunks changed since the last flush ({@link ChunkPos#toLong}), in the order first changed. */
    private final LongLinkedOpenHashSet changed = new LongLinkedOpenHashSet();

    public McWorldBiomes(ServerLevel world) {
        this.world = Objects.requireNonNull(world);
        Registry<Biome> registry = world.registryAccess().registryOrThrow(Registries.BIOME);
        this.entries = registry.asHolderIdMap();
        List<String> list = new ArrayList<>(entries.size());
        handles.defaultReturnValue(-1);
        for (int i = 0; i < entries.size(); i++) {
            String id = registry.getKey(entries.byId(i).value()).toString();
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
        LevelChunkSection section = section(chunk(qx, qz), qy);
        return entries.getId(section.getNoiseBiome(qx & 3, qy & 3, qz & 3));
    }

    @Override
    public int set(int qx, int qy, int qz, int handle) {
        if (handle < 0 || handle >= ids.size()) throw new IllegalArgumentException("No biome has handle " + handle);
        LevelChunk chunk = chunk(qx, qz);
        LevelChunkSection section = section(chunk, qy);
        int x = qx & 3, y = qy & 3, z = qz & 3;
        Holder<Biome> target = entries.byId(handle);
        int before = entries.getId(section.getNoiseBiome(x, y, z));
        // A biome the registry does not index (a mod put one in directly) is never written over: undo could not say what
        // it was.
        if (before == handle || before < 0) return before;
        PalettedContainerRO<Holder<Biome>> container = section.getBiomes();
        if (container instanceof PalettedContainer<Holder<Biome>> palette) {
            palette.getAndSetUnchecked(x, y, z, target);
        } else {
            // A container the game does not make: rebuilt, every other quart kept (the supplier gets quart coordinates).
            int bx = qx & ~3, by = qy & ~3, bz = qz & ~3;
            section.fillBiomesFromNoise((sx, sy, sz, noise) -> sx == qx && sy == qy && sz == qz ? target
                    : section.getNoiseBiome(sx - bx, sy - by, sz - bz), null, bx, by, bz);
        }
        chunk.setUnsaved(true);
        changed.add(ChunkPos.asLong(qx >> 2, qz >> 2));
        return before;
    }

    @Override
    public int flush() {
        if (changed.isEmpty()) return 0;
        List<ChunkAccess> chunks = new ArrayList<>(changed.size());
        for (long key : changed) {
            LevelChunk chunk = world.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk != null) chunks.add(chunk);
        }
        changed.clear();
        if (!chunks.isEmpty()) world.getChunkSource().chunkMap.resendBiomesForChunks(chunks);
        return chunks.size();
    }

    private LevelChunk chunk(int qx, int qz) {
        LevelChunk chunk = world.getChunkSource().getChunkNow(qx >> 2, qz >> 2);
        if (chunk == null) throw new IllegalStateException("Chunk " + (qx >> 2) + "," + (qz >> 2) + " is not loaded");
        return chunk;
    }

    private static LevelChunkSection section(LevelChunk chunk, int qy) {
        int index = chunk.getSectionIndex(qy << 2);
        LevelChunkSection[] sections = chunk.getSections();
        if (index < 0 || index >= sections.length) throw new IllegalArgumentException("Quart y " + qy + " outside the world");
        return sections[index];
    }
}
