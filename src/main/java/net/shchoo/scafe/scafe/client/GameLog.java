package net.shchoo.scafe.scafe.client;

import net.shchoo.scafe.scafe.protocol.Event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Player-facing messages from anywhere in the game. Whoever wants to show them (the on-screen
 * MessageLog, a file, tests...) subscribes; callers just call {@link #say} (or {@link #chat} for what players say).
 * Plain-text listeners get the line as text; entry listeners also get its kind and speaker.
 */
public final class GameLog {
    private static final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private static final List<Consumer<LogEntry>> entryListeners = new CopyOnWriteArrayList<>();

    private GameLog() {}

    public static void say(String message) {
        say(Event.MessageKind.INFO, message);
    }

    public static void say(Event.MessageKind kind, String message) {
        add(LogEntry.of(kind, message));
    }

    public static void chat(String speaker, int speakerColor, String message) {
        add(LogEntry.chat(speaker, speakerColor, message));
    }

    private static void add(LogEntry entry) {
        listeners.forEach(listener -> listener.accept(entry.display()));
        entryListeners.forEach(listener -> listener.accept(entry));
    }

    public static void subscribe(Consumer<String> listener) {
        listeners.add(listener);
    }

    public static void unsubscribe(Consumer<String> listener) {
        listeners.remove(listener);
    }

    public static void subscribeEntries(Consumer<LogEntry> listener) {
        entryListeners.add(listener);
    }

    public static void unsubscribeEntries(Consumer<LogEntry> listener) {
        entryListeners.remove(listener);
    }
}
