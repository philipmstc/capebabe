package net.shchoo.scafe.scafe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Player-facing messages, drawn on screen under the board.
 * System.out goes to the IDE console, not the Lanterna window, so anything the
 * player should see goes through here instead.
 */
public class MessageLog {
    private static final int MAX_HISTORY = 200;

    private final Deque<String> lines = new ArrayDeque<>();

    public void add(String message) {
        // keep multi-line messages readable, one entry per line
        for (String line : message.split("\n")) {
            lines.addLast(line.replace("\t", "  "));
            if (lines.size() > MAX_HISTORY) {
                lines.removeFirst();
            }
        }
    }

    /** The most recent {@code count} lines, oldest first. */
    public List<String> latest(int count) {
        List<String> all = new ArrayList<>(lines);
        return all.subList(Math.max(0, all.size() - count), all.size());
    }
}
