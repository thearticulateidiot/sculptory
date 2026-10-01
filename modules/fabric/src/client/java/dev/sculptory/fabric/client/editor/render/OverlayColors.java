package dev.sculptory.fabric.client.editor.render;

/** ARGB colour helpers for overlays (0xAARRGGBB ints, as {@code VertexConsumer.color(int)} takes). */
public final class OverlayColors {
    private OverlayColors() {}

    public static int argb(int alpha, int red, int green, int blue) {
        return (clamp(alpha) << 24) | (clamp(red) << 16) | (clamp(green) << 8) | clamp(blue);
    }

    public static int alpha(int argb) {
        return argb >>> 24;
    }

    /** The same colour with its alpha replaced (0..255). */
    public static int withAlpha(int argb, int alpha) {
        return (argb & 0x00FFFFFF) | (clamp(alpha) << 24);
    }

    /** The same colour with its alpha multiplied by {@code factor}. */
    public static int scaleAlpha(int argb, double factor) {
        return withAlpha(argb, (int) Math.round(alpha(argb) * factor));
    }

    /** Moves the RGB channels a fraction {@code amount} towards white, keeping alpha. */
    public static int lighten(int argb, double amount) {
        int red = (argb >> 16) & 0xFF;
        int green = (argb >> 8) & 0xFF;
        int blue = argb & 0xFF;
        return argb(
                alpha(argb),
                (int) Math.round(red + (255 - red) * amount),
                (int) Math.round(green + (255 - green) * amount),
                (int) Math.round(blue + (255 - blue) * amount));
    }

    static float red(int argb) {
        return ((argb >> 16) & 0xFF) / 255f;
    }

    static float green(int argb) {
        return ((argb >> 8) & 0xFF) / 255f;
    }

    static float blue(int argb) {
        return (argb & 0xFF) / 255f;
    }

    static float alphaFloat(int argb) {
        return alpha(argb) / 255f;
    }

    private static int clamp(int channel) {
        return Math.max(0, Math.min(255, channel));
    }
}
