package net.shchoo.scafe.scafe;

import java.util.ArrayDeque;
import java.util.Deque;

/** Moves anything with a position along a path, one tile per {@link #tick()}. */
public class Walker {

    public interface Mobile {
        Board.Position position();
        void moveTo(Board.Position position);
    }

    private final Mobile mobile;
    private Deque<Board.Position> path = new ArrayDeque<>();

    public Walker(Mobile mobile) {
        this.mobile = mobile;
    }

    public void walkTo(Board.Position target) {
        path = Pathfinding.orthogonal(mobile.position(), target);
    }

    public void stop() {
        path.clear();
    }

    public boolean isWalking() {
        return !path.isEmpty();
    }

    public void tick() {
        if (!path.isEmpty()) {
            mobile.moveTo(path.pollFirst());
        }
    }
}
