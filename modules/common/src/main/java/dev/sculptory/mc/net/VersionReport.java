package dev.sculptory.mc.net;

import dev.sculptory.mc.Sculptory;
import dev.sculptory.protocol.v2.Handshake;
import dev.sculptory.protocol.v2.ProtocolV2;
import net.minecraft.server.level.ServerPlayer;

/**
 * What {@code /sculptory version} says, the same on every platform: the server's engine build, product build and
 * protocol, the caller's client build, and for admins and the console every other online player's.
 */
public final class VersionReport {
    private VersionReport() {
    }

    /**
     * The report for {@code caller} ({@code null}: the console); {@code everyone} lists the other online players'
     * clients too (admins and the console).
     */
    public static String text(ServerPlayer caller, Iterable<ServerPlayer> online, boolean everyone) {
        // The engine build id is what client and server compare; the product id says which mod this server runs.
        String server = Sculptory.buildId();
        StringBuilder text = new StringBuilder("Sculptory ").append(server).append(" on this server (")
                .append(Sculptory.productBuildId()).append(", protocol ").append(ProtocolV2.VERSION).append(')');
        if (caller != null) text.append("\nYour client: ").append(describe(caller, server));
        if (everyone) {
            for (ServerPlayer player : online) {
                if (player == caller) continue;
                text.append("\n  ").append(player.getGameProfile().getName()).append(": ")
                        .append(describe(player, server));
            }
        }
        return text.toString();
    }

    private static String describe(ServerPlayer player, String serverBuild) {
        return describe(ServerNet.clientBuild(player).orElse(""), ServerNet.isIncompatible(player), serverBuild);
    }

    /** How the report describes a client's build next to the server's. */
    public static String describe(String clientBuild, boolean incompatible, String serverBuild) {
        if (clientBuild.isEmpty()) return "no Sculptory editor connected";
        if (incompatible) return clientBuild + ", another protocol: editing is off (install the server's build)";
        if (clientBuild.equals(serverBuild)) return clientBuild + ", the same build";
        if (Handshake.differentBuilds(clientBuild, serverBuild)) {
            return clientBuild + ", a different build of the same protocol: editing works, but the same build is safer";
        }
        return clientBuild + ", the same protocol (which build isn't known)";
    }
}
