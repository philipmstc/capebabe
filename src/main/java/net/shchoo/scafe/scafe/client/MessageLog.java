package net.shchoo.scafe.scafe.client;

import net.shchoo.scafe.scafe.protocol.Event;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Player-facing messages, drawn on screen in the chat / log box.
 * System.out goes to the IDE console, not the Lanterna window, so anything the
 * player should see goes through here instead.
 */
public class MessageLog {
    private static final int MAX_HISTORY = 200;
    private final Deque<LogEntry> lines = new ArrayDeque<>();

    public void add(String message) {
        add(LogEntry.of(Event.MessageKind.INFO, message));
    }

    public void add(LogEntry entry) {
        // keep multi-line messages readable, one entry per line
        for (String line : entry.text().split("\n")) {
            lines.addLast(new LogEntry(entry.kind(), line.replace("\t", "  "), entry.speaker(), entry.speakerColor()));
            if (lines.size() > MAX_HISTORY) {
                lines.removeFirst();
            }
        }
    }

    /** The most recent {@code count} lines, oldest first; compact = without the routine ones ("Made x"). */
    public List<LogEntry> latest(int count, boolean compact) {
        List<LogEntry> shown = new ArrayList<>(lines);
        if (compact) {
            shown.removeIf(entry -> entry.kind() == Event.MessageKind.ROUTINE);
        }
        return shown.subList(Math.max(0, shown.size() - count), shown.size());
    }
}
