package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.model.Position;

import java.util.ArrayDeque;
import java.util.Deque;

public final class Pathfinding {
    private Pathfinding() {}

    /** Steps from {@code from} (exclusive) to {@code to} (inclusive): all horizontal, then all vertical. */
    public static Deque<Position> orthogonal(Position from, Position to) {
        Deque<Position> steps = new ArrayDeque<>();
        int x = from.x(), y = from.y();
        while (x != to.x()) {
            x += Integer.signum(to.x() - x);
            steps.addLast(new Position(x, y));
        }
        while (y != to.y()) {
            y += Integer.signum(to.y() - y);
            steps.addLast(new Position(x, y));
        }
        return steps;
    }
}
