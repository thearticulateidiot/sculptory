package dev.sculptory.server.net;

/**
 * The player type the dispatcher tests run with. None is ever made: the fake transports have no player, and the
 * dispatcher only passes players through to the services.
 */
final class FakePlayer {
    private FakePlayer() {}
}
