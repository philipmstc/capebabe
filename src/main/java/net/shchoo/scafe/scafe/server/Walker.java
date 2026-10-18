package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.protocol.Intent;

import java.util.ArrayDeque;
import java.util.Deque;

/** Moves anything with a position along a path, one tile per {@link #tick()}. */
public class Walker {

    public interface Mobile {
        Position position();
        void moveTo(Position position);
    }

    /** Manual steps queued beyond this are dropped, so holding a key doesn't overshoot. */
    public static final int MAX_QUEUED_STEPS = Intent.MAX_QUEUED_STEPS;

    private final Mobile mobile;
    private Deque<Position> path = new ArrayDeque<>();
    private boolean manual = false;

    public Walker(Mobile mobile) {
        this.mobile = mobile;
    }

    /** Returns true if this threw away queued manual steps. */
    public boolean walkTo(Position target) {
        boolean discarded = hasManualSteps();
        manual = false;
        path = Pathfinding.orthogonal(mobile.position(), target);
        return discarded;
    }

    /** Returns false if the step was dropped (queue full). */
    public boolean queueStep(int dx, int dy) {
        if (!manual) {
            path.clear();
            manual = true;
        }
        if (path.size() >= MAX_QUEUED_STEPS) {
            return false;
        }
        Position from = path.isEmpty() ? mobile.position() : path.peekLast();
        path.addLast(new Position(from.x() + dx, from.y() + dy));
        return true;
    }

    /** Returns true if this threw away queued manual steps. */
    public boolean stop() {
        boolean discarded = hasManualSteps();
        path.clear();
        return discarded;
    }

    private boolean hasManualSteps() {
        return manual && !path.isEmpty();
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
