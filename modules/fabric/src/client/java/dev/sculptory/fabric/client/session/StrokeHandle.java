package dev.sculptory.fabric.client.session;

import dev.sculptory.core.brush.Dab;

/** One live brush stroke. Dabs are predicted (per {@link StrokeParams}) and sent batched once per tick. */
public interface StrokeHandle {
    int strokeId();

    /** Adds the next dab; ignored once the stroke is no longer active. */
    void dab(Dab d);

    /** Finishes the stroke; the server turns it into one history entry. */
    void end();

    /** Abandons the stroke; like {@link #end()} for history, and stops prediction immediately. */
    void cancel();

    /** False after end, cancel or a server rejection (the client stops predicting until the next press). */
    boolean active();
}
