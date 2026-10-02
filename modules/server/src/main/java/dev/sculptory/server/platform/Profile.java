package dev.sculptory.server.platform;

import java.util.UUID;

/** A player's account as the server knows it: its id and name. */
public record Profile(UUID id, String name) {}
