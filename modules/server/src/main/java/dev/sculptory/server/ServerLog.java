package dev.sculptory.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The log every platform's Sculptory server writes to: named after the mod id, so its lines read "(sculptory)". */
public final class ServerLog {
    public static final String NAME = "sculptory";
    public static final Logger LOG = LoggerFactory.getLogger(NAME);

    private ServerLog() {}
}
