package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.model.Direction;
import net.shchoo.scafe.scafe.model.Position;

import java.util.*;
import java.util.function.Predicate;

public final class Pathfinding {
    /** How far past the start/target box the search may wander looking for a way around things. */
    static final int SEARCH_MARGIN = 16;

    private Pathfinding() {}

    /**
     * Shortest path from {@code from} (exclusive) through passable tiles, one tile per step including diagonals.
     * A diagonal step needs both tiles beside the corner to be passable (no squeezing between two nodes).
     * If {@code to} is passable the path ends on it; if not (e.g. a node), it ends orthogonally next to it, where
     * you can face it. Empty if already there or there's no way.
     */
    public static Deque<Position> path(Position from, Position to, Predicate<Position> passable) {
        Set<Position> goals = new HashSet<>();
        if (passable.test(to)) {
            goals.add(to);
        } else {
            for (Direction d : Direction.values()) {
                Position next = to.step(d);
                if (passable.test(next) || next.equals(from)) {
                    goals.add(next);
                }
            }
        }
        if (goals.contains(from)) {
            return new ArrayDeque<>();
        }
        int minX = Math.min(from.x(), to.x()) - SEARCH_MARGIN, maxX = Math.max(from.x(), to.x()) + SEARCH_MARGIN;
        int minY = Math.min(from.y(), to.y()) - SEARCH_MARGIN, maxY = Math.max(from.y(), to.y()) + SEARCH_MARGIN;
        Map<Position, Position> cameFrom = new HashMap<>();
        Deque<Position> frontier = new ArrayDeque<>();
        frontier.add(from);
        cameFrom.put(from, from);
        while (!frontier.isEmpty()) {
            Position current = frontier.poll();
            if (goals.contains(current)) {
                Deque<Position> path = new ArrayDeque<>();
                for (Position p = current; !p.equals(from); p = cameFrom.get(p)) {
                    path.addFirst(p);
                }
                return path;
            }
            for (Position next : neighboursTowards(current, to)) {
                if (next.x() < minX || next.x() > maxX || next.y() < minY || next.y() > maxY
                        || cameFrom.containsKey(next) || !canStep(current, next, passable)) {
                    continue;
                }
                cameFrom.put(next, current);
                frontier.add(next);
            }
        }
        return new ArrayDeque<>();
    }

    /**
     * The 8 neighbours, the ones heading most directly at {@code target} first and straight steps before diagonals
     * on ties -- so among equally short paths the search finds the straight-looking one, not a zigzag.
     */
    private static List<Position> neighboursTowards(Position from, Position target) {
        List<Position> neighbours = new ArrayList<>(8);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx != 0 || dy != 0) {
                    neighbours.add(new Position(from.x() + dx, from.y() + dy));
                }
            }
        }
        neighbours.sort(Comparator
                .comparingInt((Position p) -> Math.max(Math.abs(target.x() - p.x()), Math.abs(target.y() - p.y())))
                .thenComparingInt(p -> Math.abs(target.x() - p.x()) + Math.abs(target.y() - p.y()))
                .thenComparingInt(p -> (p.x() != from.x() && p.y() != from.y()) ? 1 : 0));
        return neighbours;
    }

    /** One step (orthogonal or diagonal) onto a passable tile, without cutting a blocked corner. */
    static boolean canStep(Position from, Position to, Predicate<Position> passable) {
        int dx = to.x() - from.x(), dy = to.y() - from.y();
        if (Math.abs(dx) > 1 || Math.abs(dy) > 1 || (dx == 0 && dy == 0) || !passable.test(to)) {
            return false;
        }
        return dx == 0 || dy == 0
                || (passable.test(new Position(from.x() + dx, from.y())) && passable.test(new Position(from.x(), from.y() + dy)));
    }
}
