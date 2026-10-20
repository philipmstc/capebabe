package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.model.Position;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Whether a new node can go somewhere without walling anything in, e.g. the last square of a checkerboard:
 * <pre>
 *   # . # . #
 *   . # . # .
 *   # . # . #
 * </pre>
 * Nodes block movement and diagonal steps can't squeeze between two nodes, so an open tile is only reachable
 * through its four sides. A spot is fine if, within {@link #RADIUS} of it, the node doesn't create any new
 * <i>pocket</i> (an open tile that can't be reached from the edge of that area) or any new <i>unusable node</i>
 * (one with no reachable open side to stand on and face it), the new node included. Problems that were already
 * there don't count against it. Only the area is checked, so the edge of it is assumed reachable.
 */
public final class NodePlacement {
    private NodePlacement() {}

    public static final int RADIUS = 6;

    /** {@code blocked}: where nodes already are. */
    public static boolean safe(Predicate<Position> blocked, Position spot) {
        if (blocked.test(spot)) {
            return false;
        }
        Set<Position> before = problems(blocked, spot);
        Predicate<Position> after = p -> p.equals(spot) || blocked.test(p);
        for (Position problem : problems(after, spot)) {
            if (!before.contains(problem)) {
                return false;
            }
        }
        return true;
    }

    /** Pockets and unusable nodes in the square of {@link #RADIUS} around {@code center}. */
    static Set<Position> problems(Predicate<Position> blocked, Position center) {
        int minX = center.x() - RADIUS, maxX = center.x() + RADIUS;
        int minY = center.y() - RADIUS, maxY = center.y() + RADIUS;

        // everything open that can be reached from the area's edge
        Set<Position> reached = new HashSet<>();
        Deque<Position> queue = new ArrayDeque<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                Position p = new Position(x, y);
                boolean edge = x == minX || x == maxX || y == minY || y == maxY;
                if (edge && !blocked.test(p) && reached.add(p)) {
                    queue.add(p);
                }
            }
        }
        while (!queue.isEmpty()) {
            Position p = queue.poll();
            for (Position next : sides(p)) {
                if (next.x() >= minX && next.x() <= maxX && next.y() >= minY && next.y() <= maxY
                        && !blocked.test(next) && reached.add(next)) {
                    queue.add(next);
                }
            }
        }

        Set<Position> problems = new HashSet<>();
        for (int x = minX + 1; x < maxX; x++) {
            for (int y = minY + 1; y < maxY; y++) {
                Position p = new Position(x, y);
                if (!blocked.test(p)) {
                    if (!reached.contains(p)) {
                        problems.add(p);
                    }
                } else if (sides(p).stream().noneMatch(reached::contains)) {
                    problems.add(p);
                }
            }
        }
        return problems;
    }

    private static List<Position> sides(Position p) {
        return List.of(new Position(p.x() + 1, p.y()), new Position(p.x() - 1, p.y()),
                new Position(p.x(), p.y() + 1), new Position(p.x(), p.y() - 1));
    }
}
