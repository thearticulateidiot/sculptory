package dev.sculptory.protocol.v2;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Protocol version negotiation for {@code Hello}/{@code Welcome}. Both sides support the inclusive range
 * {@link #MIN_PROTOCOL}..{@link #MAX_PROTOCOL}; the session uses the highest version in both ranges.
 *
 * <p>The {@code Hello} and {@code Incompatible} bodies must stay decodable by every future version, so that
 * an old client and a new server can still tell each other they don't match: {@code Hello} is
 * {@code min, max, modVersion, features}, {@code Incompatible} is {@code min, max} followed, for clients of protocol 5
 * or newer, by the server's build id.
 *
 * <p>Both sides carry their build id ({@code Hello.modVersion}, {@code Welcome.serverBuild}): builds of one protocol work
 * together, and a different build is only a notice ({@link #differentBuilds}).
 */
public final class Handshake {
    private Handshake() {}

    /** Oldest protocol this build speaks (2 was the first of the v1 rebuild; 3 added {@code ASSET_NOT_LOADED}). */
    public static final int MIN_PROTOCOL = ProtocolV2.VERSION;
    /** Newest protocol this build speaks. */
    public static final int MAX_PROTOCOL = ProtocolV2.VERSION;
    /** The first protocol whose clients decode the server build id at the end of {@code Incompatible}. */
    public static final int BUILD_IN_INCOMPATIBLE_SINCE = 5;
    /** How long a client waits for {@code Welcome} before deciding the server has no Sculptory. */
    public static final long TIMEOUT_NANOS = 5_000_000_000L;

    /** The highest version in both inclusive ranges, or empty when they don't overlap or a range is inverted. */
    public static OptionalInt negotiate(int minA, int maxA, int minB, int maxB) {
        if (minA > maxA || minB > maxB) return OptionalInt.empty();
        int low = Math.max(minA, minB);
        int high = Math.min(maxA, maxB);
        return low <= high ? OptionalInt.of(high) : OptionalInt.empty();
    }

    /** The version this build would use with a peer supporting {@code peerMin..peerMax}. */
    public static OptionalInt negotiate(int peerMin, int peerMax) {
        return negotiate(MIN_PROTOCOL, MAX_PROTOCOL, peerMin, peerMax);
    }

    /** Whether this build speaks {@code protocol} (the client's check of {@code Welcome.protocol}). */
    public static boolean supports(int protocol) {
        return protocol >= MIN_PROTOCOL && protocol <= MAX_PROTOCOL;
    }

    /** This build's {@code Hello}; the mod version is cut to fit its wire cap. */
    public static C2S.Hello hello(String modVersion, Features features) {
        return new C2S.Hello(MIN_PROTOCOL, MAX_PROTOCOL, fit(Objects.requireNonNull(modVersion)), features);
    }

    /**
     * The server's answer to {@code hello}: {@code Welcome} with the negotiated version and the features both
     * sides support, or {@code Incompatible} with this build's range. Without a server build id.
     */
    public static S2C answer(C2S.Hello hello, Features serverFeatures, Limits limits, PermissionMask permissions,
                             long sessionEpoch) {
        return answer(hello, serverFeatures, limits, permissions, sessionEpoch, "");
    }

    /**
     * {@link #answer(C2S.Hello, Features, Limits, PermissionMask, long)} carrying the server's build id (cut to fit its
     * wire cap). {@code Incompatible} carries it only to clients whose newest protocol is at least
     * {@link #BUILD_IN_INCOMPATIBLE_SINCE}: older ones decode {@code Incompatible} as its two protocol numbers only.
     */
    public static S2C answer(C2S.Hello hello, Features serverFeatures, Limits limits, PermissionMask permissions,
                             long sessionEpoch, String serverBuild) {
        String build = fit(Objects.requireNonNull(serverBuild));
        OptionalInt version = negotiate(hello.minProtocol(), hello.maxProtocol());
        if (version.isEmpty()) {
            return new S2C.Incompatible(MIN_PROTOCOL, MAX_PROTOCOL,
                    hello.maxProtocol() >= BUILD_IN_INCOMPATIBLE_SINCE ? build : "");
        }
        return new S2C.Welcome(version.getAsInt(), serverFeatures.intersect(hello.features()), limits, permissions,
                sessionEpoch, build);
    }

    /**
     * Whether two build ids name different builds: both known (not empty or {@code "unknown"}) and not equal. Builds of
     * the same protocol work together; a different build is worth a notice, since it may behave differently.
     */
    public static boolean differentBuilds(String a, String b) {
        return known(a) && known(b) && !a.equals(b);
    }

    /**
     * A build id as the other side sent it, made safe to store, log and show: every character outside
     * {@code [0-9A-Za-z.+_-]} (what the build writes) becomes {@code ?}, so no formatting codes, line breaks or markup
     * from a peer reach a log line, a toast or chat.
     */
    public static String cleanBuild(String build) {
        if (build == null) return "";
        StringBuilder clean = new StringBuilder(build.length());
        build.codePoints().forEach(c -> clean.append(c < 128 && (Character.isLetterOrDigit(c) || c == '.' || c == '+'
                || c == '_' || c == '-') ? (char) c : '?'));
        return clean.toString();
    }

    private static boolean known(String build) {
        return build != null && !build.isEmpty() && !build.equals("unknown");
    }

    private static String fit(String modVersion) {
        String value = modVersion;
        while (value.getBytes(StandardCharsets.UTF_8).length > Codec.MAX_MOD_VERSION_BYTES) {
            value = value.substring(0, value.offsetByCodePoints(value.length(), -1));
        }
        return value;
    }
}
