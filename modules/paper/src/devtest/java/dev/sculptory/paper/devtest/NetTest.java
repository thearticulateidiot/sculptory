package dev.sculptory.paper.devtest;

import dev.sculptory.mc.Sculptory;
import dev.sculptory.paper.PaperServerLoader;
import dev.sculptory.paper.SculptoryPlugin;
import dev.sculptory.protocol.v2.Codec;
import dev.sculptory.protocol.v2.ProtocolException;
import dev.sculptory.protocol.v2.ProtocolV2;
import dev.sculptory.protocol.v2.StreamChunk;
import dev.sculptory.protocol.v2.StreamKind;
import dev.sculptory.protocol.v2.StreamOpen;
import java.util.Arrays;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Dev only ({@code /sculptory nettest <player> <bytes> <messenger|nms>}): sends one {@code sculptory:s2c} frame of
 * exactly {@code bytes} bytes, to see what the transport and the client take. Up to the protocol's cap
 * ({@link ProtocolV2#MAX_S2C_FRAME}) the frame is a well-formed {@code StreamChunk} for a stream the client never opened,
 * which the client's session ignores; above it, that frame padded with zeros, which the client's payload codec must
 * refuse (the client then drops the connection, naming the frame size in its log).
 */
public final class NetTest {
    /** A stream id no client opens (the server's own streams count up from 1). */
    private static final int STREAM_ID = 0x7FFF_FF01;

    private NetTest() {
    }

    public static int send(CommandSourceStack source, ServerPlayer player, int bytes, String route) {
        byte[] frame;
        try {
            frame = frameOf(bytes);
        } catch (ProtocolException e) {
            source.sendFailure(Component.literal("Could not build the frame: " + e.getMessage()));
            return 0;
        }
        String name = player.getGameProfile().getName();
        try {
            switch (route) {
                case "messenger" -> player.getBukkitEntity().sendPluginMessage(SculptoryPlugin.instance(),
                        PaperServerLoader.S2C_CHANNEL, frame);
                case "nms" -> PaperServerLoader.sendThroughNms(player, frame);
                default -> {
                    source.sendFailure(Component.literal("route is messenger or nms"));
                    return 0;
                }
            }
        } catch (RuntimeException e) {
            Sculptory.LOG.info("Sculptory nettest: {} bytes to {} through {} failed on the server: {}", frame.length,
                    name, route, e.toString());
            source.sendFailure(Component.literal("Send failed: " + e));
            return 0;
        }
        Sculptory.LOG.info("Sculptory nettest: sent a {}-byte sculptory:s2c frame to {} through {} (registered "
                        + "channels of the client: {})", frame.length, name, route,
                player.getBukkitEntity().getListeningPluginChannels());
        source.sendSuccess(() -> Component.literal("Sent " + frame.length + " bytes through " + route), false);
        return 1;
    }

    /**
     * Dev only ({@code /sculptory netprobe <player> <count>}): sends {@code count} offers of a stream the client never
     * asked for ({@link #unwantedOffer}), through the loader as the networking sends. A Sculptory client in a ready
     * session answers each with a {@code StreamAbort}, so this makes a connected editor send {@code count} frames
     * without anyone at its keyboard (the reload check: a connection that predates the plugin instance).
     */
    public static int probe(CommandSourceStack source, ServerPlayer player, int count) {
        PaperServerLoader loader = SculptoryPlugin.instance().loader();
        int sent = 0;
        try {
            for (int i = 0; i < count; i++) {
                if (!loader.canSendS2C(player)) break;
                loader.sendS2C(player, unwantedOffer(i));
                sent++;
            }
        } catch (ProtocolException e) {
            source.sendFailure(Component.literal("Could not build the frame: " + e.getMessage()));
            return 0;
        }
        int offers = sent;
        Sculptory.LOG.info("Sculptory netprobe: offered {} unwanted streams to {} (route {}, frames dropped since a "
                + "reload so far: {})", offers, player.getGameProfile().getName(), loader.route(player),
                loader.droppedSinceReload(player));
        source.sendSuccess(() -> Component.literal("Offered " + offers + " unwanted streams"), false);
        return offers;
    }

    /** An offer of a schematic file for a request id no client uses: the client aborts it ("unwanted"). */
    static byte[] unwantedOffer(int i) throws ProtocolException {
        SortedMap<String, String> meta = new TreeMap<>(Map.of("reqId", "-1"));
        return Codec.encodeS2C(new StreamOpen(STREAM_ID - 1 - i, StreamKind.SCHEM_FILE, 1, meta), null);
    }

    /** A frame of exactly {@code bytes} bytes (see the class comment). */
    static byte[] frameOf(int bytes) throws ProtocolException {
        int cap = ProtocolV2.MAX_S2C_FRAME;
        if (bytes > cap) {
            return Arrays.copyOf(chunkFrame(cap), bytes);
        }
        return chunkFrame(bytes);
    }

    /** A StreamChunk frame of exactly {@code bytes} bytes, or the closest below when the varints make it impossible. */
    private static byte[] chunkFrame(int bytes) throws ProtocolException {
        int payload = Math.max(0, bytes - 16);
        byte[] frame = Codec.encodeS2C(new StreamChunk(STREAM_ID, 0, new byte[payload]), null);
        // The length varint grows with the payload: step the payload until the frame has the size asked for.
        while (frame.length > bytes && payload > 0) {
            payload -= frame.length - bytes;
            frame = Codec.encodeS2C(new StreamChunk(STREAM_ID, 0, new byte[Math.max(0, payload)]), null);
        }
        while (frame.length < bytes) {
            byte[] bigger = Codec.encodeS2C(new StreamChunk(STREAM_ID, 0, new byte[payload + (bytes - frame.length)]),
                    null);
            if (bigger.length > bytes) break;
            payload += bytes - frame.length;
            frame = bigger;
        }
        return frame;
    }
}
