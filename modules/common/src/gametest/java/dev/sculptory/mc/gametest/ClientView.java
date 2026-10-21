package dev.sculptory.mc.gametest;

import dev.sculptory.core.Box;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * What a client that received a {@link BenchSupport.Watcher}'s packets would show: whole columns from chunk packets,
 * then block and delta updates on top, in order, and the block-entity data sent along. Used to check that edits reach
 * players exactly as the server has them, whichever packets carried them. Server thread only.
 */
final class ClientView implements Consumer<Packet<?>> {
    private final ServerLevel world;
    private final Registry<Biome> biomes;
    private final int sections;
    private final Long2ObjectOpenHashMap<LevelChunkSection[]> columns = new Long2ObjectOpenHashMap<>();
    private final Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();
    int chunkPackets;
    int deltaPackets;
    int blockPackets;
    int blockEntityPackets;
    int biomePackets;
    int updatesOutsideLoadedChunks;

    ClientView(ServerLevel world) {
        this.world = world;
        this.biomes = world.registryAccess().registryOrThrow(Registries.BIOME);
        this.sections = world.getSectionsCount();
    }

    /** Forgets the packet counts (not the view). */
    void resetCounts() {
        chunkPackets = deltaPackets = blockPackets = blockEntityPackets = updatesOutsideLoadedChunks = lightPackets = 0;
        biomePackets = 0;
    }

    @Override
    public void accept(Packet<?> packet) {
        switch (packet) {
            case ClientboundLevelChunkWithLightPacket chunk -> load(chunk);
            case ClientboundSectionBlocksUpdatePacket delta -> {
                deltaPackets++;
                delta.runUpdates(this::set);
            }
            case ClientboundBlockUpdatePacket block -> {
                blockPackets++;
                set(block.getPos(), block.getBlockState());
            }
            case ClientboundBlockEntityDataPacket entity -> {
                blockEntityPackets++;
                blockEntities.put(entity.getPos().immutable(), entity.getTag());
            }
            case ClientboundChunksBiomesPacket biome -> {
                biomePackets++;
                for (ClientboundChunksBiomesPacket.ChunkBiomeData data : biome.chunkBiomeData()) {
                    LevelChunkSection[] column = columns.get(data.pos().toLong());
                    if (column == null) continue;
                    FriendlyByteBuf buf = data.getReadBuffer();
                    for (LevelChunkSection section : column) section.readBiomes(buf);
                }
            }
            case ClientboundLightUpdatePacket update -> {
                lightPackets++;
                readLight(update.getX(), update.getZ(), update.getLightData());
            }
            case ClientboundForgetLevelChunkPacket unload -> {
                columns.remove(unload.pos().toLong());
                light.remove(unload.pos().toLong());
            }
            default -> {}
        }
    }

    private void load(ClientboundLevelChunkWithLightPacket packet) {
        chunkPackets++;
        int cx = packet.getX(), cz = packet.getZ();
        FriendlyByteBuf buf = packet.getChunkData().getReadBuffer();
        LevelChunkSection[] column = new LevelChunkSection[sections];
        for (int i = 0; i < sections; i++) {
            column[i] = new LevelChunkSection(biomes);
            column[i].read(buf);
        }
        columns.put(ChunkPos.asLong(cx, cz), column);
        readLight(cx, cz, packet.getLightData());
        // A chunk packet replaces the column's block entities.
        blockEntities.keySet().removeIf(pos -> (pos.getX() >> 4) == cx && (pos.getZ() >> 4) == cz);
        packet.getChunkData().getBlockEntitiesTagsConsumer(cx, cz).accept((pos, type, nbt) -> blockEntities.put(pos.immutable(),
                nbt == null ? new CompoundTag() : nbt));
    }

    private void set(BlockPos pos, BlockState state) {
        LevelChunkSection[] column = columns.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        if (column == null) {
            updatesOutsideLoadedChunks++;
            return;
        }
        column[world.getSectionIndex(pos.getY())].setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state,
                false);
    }

    /** The state the client would show, or {@code null} when it has no chunk there. */
    BlockState get(int x, int y, int z) {
        LevelChunkSection[] column = columns.get(ChunkPos.asLong(x >> 4, z >> 4));
        if (column == null) return null;
        if (y < world.getMinBuildHeight() || y >= world.getMaxBuildHeight()) return Blocks.VOID_AIR.defaultBlockState();
        return column[world.getSectionIndex(y)].getBlockState(x & 15, y & 15, z & 15);
    }

    /**
     * The biome id the client would show in quart {@code (qx, qy, qz)} (from chunk packets and biome packets), or
     * {@code null} when it has no chunk there.
     */
    String biome(int qx, int qy, int qz) {
        LevelChunkSection[] column = columns.get(ChunkPos.asLong(qx >> 2, qz >> 2));
        if (column == null) return null;
        return column[world.getSectionIndex(qy << 2)].getNoiseBiome(qx & 3, qy & 3, qz & 3).getRegisteredName();
    }

    /** The block-entity data the client would hold at {@code pos}, or {@code null}. */
    CompoundTag blockEntity(BlockPos pos) {
        return blockEntities.get(pos);
    }

    /**
     * The first difference between this view and the world inside {@code box} (states, and the client data of every
     * block entity whose data is non-empty), or {@code null} when they agree.
     */
    String differenceFrom(Box box) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int diffs = 0;
        String first = null;
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) {
                    BlockState server = world.getBlockState(pos.set(x, y, z));
                    BlockState client = get(x, y, z);
                    if (server == client) continue;
                    diffs++;
                    if (first == null) first = x + "," + y + "," + z + ": server " + server + ", client " + client;
                }
            }
        }
        if (first != null) return diffs + " cells differ; first at " + first;
        for (int cx = box.min().x() >> 4; cx <= box.max().x() >> 4; cx++) {
            for (int cz = box.min().z() >> 4; cz <= box.max().z() >> 4; cz++) {
                for (Map.Entry<BlockPos, BlockEntity> entry : world.getChunk(cx, cz).getBlockEntities().entrySet()) {
                    BlockPos at = entry.getKey();
                    if (!box.contains(at.getX(), at.getY(), at.getZ())) continue;
                    CompoundTag expected = entry.getValue().getUpdateTag(world.registryAccess());
                    if (expected.isEmpty()) continue;
                    CompoundTag got = blockEntities.get(at);
                    if (!expected.equals(got)) {
                        return "block entity at " + at.toShortString() + ": server " + expected + ", client " + got;
                    }
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- light

    /**
     * Light as the packets carried it (chunk packets and light updates), per column, light type and light section:
     * {@code null} never received, else 2048 bytes ({@code LightData}: an "uninitialized" section is all zero). The
     * real client also relights cells it gets block updates for; this model does not, so compare light only where no
     * block update was sent (columns resent whole, and their untouched neighbours).
     */
    private final Long2ObjectOpenHashMap<byte[][][]> light = new Long2ObjectOpenHashMap<>();
    int lightPackets;

    private void readLight(int cx, int cz, ClientboundLightUpdatePacketData data) {
        int height = world.getLightEngine().getLightSectionCount();
        byte[][][] column = light.computeIfAbsent(ChunkPos.asLong(cx, cz), k -> new byte[2][height][]);
        readLight(column[0], data.getSkyYMask(), data.getEmptySkyYMask(), data.getSkyUpdates());
        readLight(column[1], data.getBlockYMask(), data.getEmptyBlockYMask(), data.getBlockUpdates());
    }

    private static void readLight(byte[][] sections, BitSet inited, BitSet uninited, List<byte[]> nibbles) {
        Iterator<byte[]> next = nibbles.iterator();
        for (int i = 0; i < sections.length; i++) {
            if (inited.get(i)) {
                sections[i] = next.next().clone();
            } else if (uninited.get(i)) {
                sections[i] = new byte[DataLayer.SIZE];
            }
        }
    }

    /**
     * The server's light in every light section of the chunk columns {@code cx0..cx1 × cz0..cz1}: key
     * {@code "type cx,sy,cz"}, value the section's bytes ("uninitialized" sections as zeros; absent sections left out).
     */
    Map<String, byte[]> serverLight(int cx0, int cz0, int cx1, int cz1) {
        LevelLightEngine provider = world.getLightEngine();
        int bottom = provider.getMinLightSection(), height = provider.getLightSectionCount();
        Map<String, byte[]> out = new HashMap<>();
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                for (LightLayer type : new LightLayer[] {LightLayer.SKY, LightLayer.BLOCK}) {
                    for (int i = 0; i < height; i++) {
                        DataLayer server = provider.getLayerListener(type).getDataLayerData(SectionPos.of(cx, bottom + i, cz));
                        if (server == null) continue;
                        out.put(type + " " + cx + "," + (bottom + i) + "," + cz, server.isEmpty()
                                ? new byte[DataLayer.SIZE] : server.copy().getData());
                    }
                }
            }
        }
        return out;
    }

    /**
     * The first difference between the light this view holds and the server's, among the light sections of the chunk
     * columns {@code cx0..cx1 × cz0..cz1} whose server light is not what it was in {@code before} (a
     * {@link #serverLight} taken before an edit), or {@code null} when they all agree. Sections the edit did not change
     * are not judged unless {@code strict}: vanilla corrects their light on real clients by relighting, which this model
     * does not do; the message counts those that differ anyway. Use {@code strict} where every column judged got light
     * packets after the light settled and no block updates.
     */
    String lightDifferenceFrom(int cx0, int cz0, int cx1, int cz1, Map<String, byte[]> before, boolean strict) {
        LevelLightEngine provider = world.getLightEngine();
        int bottom = provider.getMinLightSection();
        Map<String, byte[]> now = serverLight(cx0, cz0, cx1, cz1);
        int changed = 0, wrong = 0, unchangedWrong = 0;
        String first = null;
        for (Map.Entry<String, byte[]> entry : now.entrySet()) {
            String key = entry.getKey();
            byte[] expected = entry.getValue();
            String[] parts = key.split("[ ,]");
            int t = parts[0].equals(LightLayer.SKY.toString()) ? 0 : 1;
            int cx = Integer.parseInt(parts[1]), sy = Integer.parseInt(parts[2]), cz = Integer.parseInt(parts[3]);
            byte[][][] column = light.get(ChunkPos.asLong(cx, cz));
            byte[] got = column == null ? null : column[t][sy - bottom];
            boolean agrees = got != null && Arrays.equals(expected, got);
            if (Arrays.equals(expected, before.get(key))) {
                if (!agrees) unchangedWrong++;
                continue;
            }
            changed++;
            if (agrees) continue;
            wrong++;
            if (first == null) {
                first = key + ": " + (got == null ? "never received" : differingNibbles(expected, got) + " cells differ");
            }
        }
        if (changed == 0) return "the edit changed no light section (" + unchangedWrong + " unchanged ones differ)";
        if (strict && first == null && unchangedWrong > 0) {
            return unchangedWrong + " light sections the edit did not change differ";
        }
        return first == null ? null : wrong + " of " + changed + " changed light sections differ (and " + unchangedWrong
                + " unchanged ones); first: " + first;
    }
    private static int differingNibbles(byte[] a, byte[] b) {
        int n = 0;
        for (int i = 0; i < a.length; i++) {
            if ((a[i] & 0x0F) != (b[i] & 0x0F)) n++;
            if ((a[i] & 0xF0) != (b[i] & 0xF0)) n++;
        }
        return n;
    }
}
