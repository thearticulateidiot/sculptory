package dev.sculptory.fabric.client.builder;

import dev.sculptory.protocol.v2.BuilderPower;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The ring of powers shown while the ring key is held: the entries sit on a
 * circle around where the pointer was when the key went down, the first at the top, clockwise. The pointer picks the
 * entry in whose sector it lies once it has left the dead zone in the middle. Releasing the key over an entry toggles
 * it; a short tap (released within {@value #TAP_MILLIS} ms without leaving the dead zone) toggles the power toggled
 * last; releasing elsewhere does nothing. Pure geometry and timing; the screen draws it.
 */
public final class RingMenu {
    /** A release within this many ms of the press, with the pointer still in the middle, is a tap. */
    public static final long TAP_MILLIS = 250;
    /** The middle of the ring, as a share of the radius, picks nothing. */
    public static final double DEAD_ZONE = 0.35;

    /** What a release does. */
    public sealed interface Outcome {
        /** The key was released over {@code power}. */
        record Toggle(BuilderPower power) implements Outcome {}

        /** A tap: the power toggled last goes the other way again. */
        record TapLast() implements Outcome {}

        /** Released elsewhere: nothing. */
        record Nothing() implements Outcome {}
    }

    private final List<BuilderPower> entries;
    private final double centreX;
    private final double centreY;
    private final double radius;
    private final long openedAt;
    /** The ring's half-axes: the radius, widened or heightened by the layout so the entries clear the centre plate. */
    private double radiusX;
    private double radiusY;
    private int hovered = -1;
    private boolean leftMiddle;

    public RingMenu(List<BuilderPower> entries, double centreX, double centreY, double radius, long openedAt) {
        this.entries = List.copyOf(entries);
        if (this.entries.isEmpty()) throw new IllegalArgumentException("An empty ring");
        if (radius <= 0) throw new IllegalArgumentException("Radius " + radius);
        this.centreX = centreX;
        this.centreY = centreY;
        this.radius = radius;
        this.radiusX = radius;
        this.radiusY = radius;
        this.openedAt = openedAt;
    }

    /** The ring's horizontal half-axis (the radius unless the layout widened it). */
    public double radiusX() {
        return radiusX;
    }

    /** The ring's vertical half-axis (the radius unless the layout heightened it). */
    public double radiusY() {
        return radiusY;
    }

    /** Widens or heightens the ring (never below the radius) so its entries clear the centre plate. */
    public void setAxes(double radiusX, double radiusY) {
        this.radiusX = Math.max(radius, radiusX);
        this.radiusY = Math.max(radius, radiusY);
    }

    public List<BuilderPower> entries() {
        return entries;
    }

    public double centreX() {
        return centreX;
    }

    public double centreY() {
        return centreY;
    }

    public double radius() {
        return radius;
    }

    /** The pointer moved (screen units). On a widened ring the offset is scaled back to a circle first. */
    public void pointer(double x, double y) {
        double dx = (x - centreX) * radius / radiusX;
        double dy = (y - centreY) * radius / radiusY;
        if (Math.hypot(dx, dy) < radius * DEAD_ZONE) {
            hovered = -1;
            return;
        }
        leftMiddle = true;
        // Angle clockwise from the top, so entry 0's sector is centred at the top.
        double turn = (Math.toDegrees(Math.atan2(dx, -dy)) + 360) % 360;
        double sector = 360.0 / entries.size();
        hovered = (int) Math.floor(((turn + sector / 2) % 360) / sector);
    }

    /** The entry under the pointer, or -1. */
    public int hovered() {
        return hovered;
    }

    public Optional<BuilderPower> hoveredPower() {
        return hovered < 0 ? Optional.empty() : Optional.of(entries.get(hovered));
    }

    /** The angle of entry {@code index}'s middle, in degrees clockwise from the top. */
    public double angleOf(int index) {
        Objects.checkIndex(index, entries.size());
        return index * 360.0 / entries.size();
    }

    /** Where entry {@code index}'s label is centred: on the ring (its axes) at its angle. */
    public double[] labelCentre(int index) {
        double a = Math.toRadians(angleOf(index));
        return new double[] {centreX + radiusX * Math.sin(a), centreY - radiusY * Math.cos(a)};
    }

    /** The key was released at time {@code now}. */
    public Outcome release(long now) {
        if (hovered >= 0) return new Outcome.Toggle(entries.get(hovered));
        if (!leftMiddle && now - openedAt <= TAP_MILLIS) return new Outcome.TapLast();
        return new Outcome.Nothing();
    }
}
