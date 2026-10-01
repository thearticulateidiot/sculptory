package dev.sculptory.fabric.client.editor.tool;

import java.util.Objects;

/** A stable tool identifier, used for keymaps, presets and palette order. */
public record ToolId(String value) {
    public static final ToolId SELECT = new ToolId("select");
    public static final ToolId RAISE = new ToolId("raise");
    public static final ToolId LOWER = new ToolId("lower");
    public static final ToolId SMOOTH = new ToolId("smooth");
    public static final ToolId FLATTEN = new ToolId("flatten");
    public static final ToolId PAINT = new ToolId("paint");
    public static final ToolId PALETTE = new ToolId("palette");
    public static final ToolId PLACE = new ToolId("place");
    public static final ToolId SCATTER = new ToolId("scatter");
    public static final ToolId SHAPE = new ToolId("shape");
    public static final ToolId GENERATE = new ToolId("generate");
    public static final ToolId EXTRUDE = new ToolId("extrude");
    public static final ToolId FLUID = new ToolId("fluid");
    public static final ToolId TINKER = new ToolId("tinker");
    public static final ToolId WEATHER = new ToolId("weather");

    public ToolId {
        Objects.requireNonNull(value);
        if (!value.matches("[a-z0-9_.-]{1,64}")) throw new IllegalArgumentException("Invalid tool id: " + value);
    }

    @Override
    public String toString() {
        return value;
    }
}
