package dev.sculptory.fabric.library;

/** A library path that breaks one of the {@link LibraryPath} rules. */
public final class LibraryPathException extends Exception {
    public LibraryPathException(String message) {
        super(message);
    }
}
