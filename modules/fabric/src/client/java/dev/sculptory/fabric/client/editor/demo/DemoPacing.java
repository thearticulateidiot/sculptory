package dev.sculptory.fabric.client.editor.demo;

/**
 * How the demo paces itself with and without the caption bar. With captions, each caption gets {@link #READ_MS} to be
 * read before its action, and the title and closing cards show. Without ({@code -NoCaptions}: for a voice-over
 * instead), no caption shows at all after Enter, nothing waits to be read, and every result is held
 * {@link #EXTRA_HOLD_MS} longer so there is room to talk over it. The "Press Enter" prompt shows either way and goes
 * the moment Enter is pressed.
 *
 * @param captions whether the caption bar is on
 */
public record DemoPacing(boolean captions) {
    /** How long a viewer gets to read a caption before the action starts. */
    public static final long READ_MS = 1_100;
    /** How much longer each result is held without captions. */
    public static final long EXTRA_HOLD_MS = 1_000;

    public static final DemoPacing WITH_CAPTIONS = new DemoPacing(true);
    public static final DemoPacing WITHOUT_CAPTIONS = new DemoPacing(false);

    /** The wait after a caption is shown, before its action. */
    public long readMs() {
        return captions ? READ_MS : 0;
    }

    /** How long a result is held, given the captioned length. */
    public long holdMs(long withCaptionsMs) {
        return withCaptionsMs + (captions ? 0 : EXTRA_HOLD_MS);
    }

    /** Whether a caption (a step's text, the title or the closing card) shows during the run. */
    public boolean showsCaptions() {
        return captions;
    }

    /** Whether the pointer ring (a mark while a button is held) shows: part of the overlay, so off with captions off. */
    public boolean showsPointerRing() {
        return captions;
    }
}
