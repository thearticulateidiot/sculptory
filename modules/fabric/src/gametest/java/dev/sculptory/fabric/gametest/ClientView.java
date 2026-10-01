package dev.sculptory.fabric.gametest;

import dev.sculptory.core.Box;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.LightData;
import net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.UnloadChunkS2CPacket;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.LightType;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.light.LightingProvider;

/**
 * What a client that received a {@link BenchSupport.Watcher}'s packets would show: whole columns from chunk packets,
 * then block and delta updates on top, in order, and the block-entity data sent along. Used to check that edits reach
 * players exactly as the server has them, whichever packets carried them. Server thread only.
 */
final class ClientView implements Consumer<Packet<?>> {
    private final ServerWorld world;
    private final Registry<Biome> biomes;
    private final int sections;
    private final Long2ObjectOpenHashMap<ChunkSection[]> columns = new Long2ObjectOpenHashMap<>();
    private final Map<BlockPos, NbtCompound> blockEntities = new HashMap<>();
    int chunkPackets;
    int deltaPackets;
    int blockPackets;
    int blockEntityPackets;
    int updatesOutsideLoadedChunks;

    ClientView(ServerWorld world) {
        this.world = world;
        this.biomes = world.getRegistryManager().get(RegistryKeys.BIOME);
        this.sections = world.countVerticalSections();
    }

    /** Forgets the packet counts (not the view). */
    void resetCounts() {
        chunkPackets = deltaPackets = blockPackets = blockEntityPackets = updatesOutsideLoadedChunks = lightPackets = 0;
    }

    @Override
    public void accept(Packet<?> packet) {
        switch (packet) {
            case ChunkDataS2CPacket chunk -> load(chunk);
            case ChunkDeltaUpdateS2CPacket delta -> {
                deltaPackets++;
                delta.visitUpdates(this::set);
            }
            case BlockUpdateS2CPacket block -> {
                blockPackets++;
                set(block.getPos(), block.getState());
            }
            case BlockEntityUpdateS2CPacket entity -> {
                blockEntityPackets++;
                blockEntities.put(entity.getPos().toImmutable(), entity.getNbt());
            }
            case LightUpdateS2CPacket update -> {
                lightPackets++;
                readLight(update.getChunkX(), update.getChunkZ(), update.getData());
            }
            case UnloadChunkS2CPacket unload -> {
                columns.remove(unload.pos().toLong());
                light.remove(unload.pos().toLong());
            }
            default -> {}
        }
    }

    private void load(ChunkDataS2CPacket packet) {
        chunkPackets++;
        int cx = packet.getChunkX(), cz = packet.getChunkZ();
        PacketByteBuf buf = packet.getChunkData().getSectionsDataBuf();
        ChunkSection[] column = new ChunkSection[sections];
        for (int i = 0; i < sections; i++) {
            column[i] = new ChunkSection(biomes);
            column[i].readDataPacket(buf);
        }
        columns.put(ChunkPos.toLong(cx, cz), column);
        readLight(cx, cz, packet.getLightData());
        // A chunk packet replaces the column's block entities.
        blockEntities.keySet().removeIf(pos -> (pos.getX() >> 4) == cx && (pos.getZ() >> 4) == cz);
        packet.getChunkData().getBlockEntities(cx, cz).accept((pos, type, nbt) -> blockEntities.put(pos.toImmutable(),
                nbt == null ? new NbtCompound() : nbt));
    }

    private void set(BlockPos pos, BlockState state) {
        ChunkSection[] column = columns.get(ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4));
        if (column == null) {
            updatesOutsideLoadedChunks++;
            return;
        }
        column[world.getSectionIndex(pos.getY())].setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state,
                false);
    }

    /** The state the client would show, or {@code null} when it has no chunk there. */
    BlockState get(int x, int y, int z) {
        ChunkSection[] column = columns.get(ChunkPos.toLong(x >> 4, z >> 4));
        if (column == null) return null;
        if (y < world.getBottomY() || y >= world.getTopY()) return Blocks.VOID_AIR.getDefaultState();
        return column[world.getSectionIndex(y)].getBlockState(x & 15, y & 15, z & 15);
    }

    /** The block-entity data the client would hold at {@code pos}, or {@code null}. */
    NbtCompound blockEntity(BlockPos pos) {
        return blockEntities.get(pos);
    }

    /**
     * The first difference between this view and the world inside {@code box} (states, and the client data of every
     * block entity whose data is non-empty), or {@code null} when they agree.
     */
    String differenceFrom(Box box) {
        BlockPos.Mutable pos = new BlockPos.Mutable();
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
                    NbtCompound expected = entry.getValue().toInitialChunkDataNbt(world.getRegistryManager());
                    if (expected.isEmpty()) continue;
                    NbtCompound got = blockEntities.get(at);
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

    private void readLight(int cx, int cz, LightData data) {
        int height = world.getLightingProvider().getHeight();
        byte[][][] column = light.computeIfAbsent(ChunkPos.toLong(cx, cz), k -> new byte[2][height][]);
        readLight(column[0], data.getInitedSky(), data.getUninitedSky(), data.getSkyNibbles());
        readLight(column[1], data.getInitedBlock(), data.getUninitedBlock(), data.getBlockNibbles());
    }

    private static void readLight(byte[][] sections, BitSet inited, BitSet uninited, List<byte[]> nibbles) {
        Iterator<byte[]> next = nibbles.iterator();
        for (int i = 0; i < sections.length; i++) {
            if (inited.get(i)) {
                sections[i] = next.next().clone();
            } else if (uninited.get(i)) {
                sections[i] = new byte[ChunkNibbleArray.BYTES_LENGTH];
            }
        }
    }

    /**
     * The server's light in every light section of the chunk columns {@code cx0..cx1 × cz0..cz1}: key
     * {@code "type cx,sy,cz"}, value the section's bytes ("uninitialized" sections as zeros; absent sections left out).
     */
    Map<String, byte[]> serverLight(int cx0, int cz0, int cx1, int cz1) {
        LightingProvider provider = world.getLightingProvider();
        int bottom = provider.getBottomY(), height = provider.getHeight();
        Map<String, byte[]> out = new HashMap<>();
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                for (LightType type : new LightType[] {LightType.SKY, LightType.BLOCK}) {
                    for (int i = 0; i < height; i++) {
                        ChunkNibbleArray server = provider.get(type).getLightSection(ChunkSectionPos.from(cx, bottom + i, cz));
                        if (server == null) continue;
                        out.put(type + " " + cx + "," + (bottom + i) + "," + cz, server.isUninitialized()
                                ? new byte[ChunkNibbleArray.BYTES_LENGTH] : server.copy().asByteArray());
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
        LightingProvider provider = world.getLightingProvider();
        int bottom = provider.getBottomY();
        Map<String, byte[]> now = serverLight(cx0, cz0, cx1, cz1);
        int changed = 0, wrong = 0, unchangedWrong = 0;
        String first = null;
        for (Map.Entry<String, byte[]> entry : now.entrySet()) {
            String key = entry.getKey();
            byte[] expected = entry.getValue();
            String[] parts = key.split("[ ,]");
            int t = parts[0].equals(LightType.SKY.toString()) ? 0 : 1;
            int cx = Integer.parseInt(parts[1]), sy = Integer.parseInt(parts[2]), cz = Integer.parseInt(parts[3]);
            byte[][][] column = light.get(ChunkPos.toLong(cx, cz));
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
