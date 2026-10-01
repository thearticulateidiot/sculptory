package dev.sculptory.fabric.client.session;

/** Connection state of the editor session. */
public enum SessionState {
    /** Not in a world. */
    DISCONNECTED,
    /** Hello sent; waiting for Welcome or Incompatible. */
    HANDSHAKING,
    /** The server does not answer on the Sculptory channel. */
    NO_SERVER_SUPPORT,
    /** The server answered Incompatible. */
    INCOMPATIBLE,
    /** Handshake complete; editing may be possible subject to permissions. */
    READY
}
