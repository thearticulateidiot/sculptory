package dev.sculptory.core.history.store;

/**
 * Stored history data that decodes but cannot be restored in this game: a block state the running game does not know
 * (a mod was removed) or data recorded under another game data version.
 */
public final class UnrestorableException extends CorruptDataException {
    public UnrestorableException(String message) {
        super(message);
    }
}
