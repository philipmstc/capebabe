package net.shchoo.scafe.scafe;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Player-facing messages from anywhere in the game. Whoever wants to show them (the on-screen
 * MessageLog, a file, tests...) subscribes; callers just call {@link #say}.
 */
public final class GameLog {
    private static final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    private GameLog() {}

    public static void say(String message) {
        listeners.forEach(listener -> listener.accept(message));
    }

    public static void subscribe(Consumer<String> listener) {
        listeners.add(listener);
    }

    public static void unsubscribe(Consumer<String> listener) {
        listeners.remove(listener);
    }
}
