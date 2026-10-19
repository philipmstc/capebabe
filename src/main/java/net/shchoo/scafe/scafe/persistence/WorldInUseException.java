package net.shchoo.scafe.scafe.persistence;

/** Another game (another window, or a --host / --server) already has this world's file open. */
public class WorldInUseException extends RuntimeException {
    public WorldInUseException(String message) {
        super(message);
    }
}
