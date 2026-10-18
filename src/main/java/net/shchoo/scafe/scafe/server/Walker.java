package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.model.Direction;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.protocol.Intent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Predicate;

/**
 * Moves anything with a position, one step per {@link #tick()}. Manual steps are directions: each one turns you
 * that way and moves you there if the tile is passable (so pressing towards a node just faces it). Walking to a
 * target follows a path around impassable tiles (diagonal steps count as one), and if the target itself is
 * impassable you end up next to it, facing it.
 */
public class Walker {

    public interface Mobile {
        Position position();

        void moveTo(Position position);

        default void face(Direction direction) {}
    }

    public enum Result { IDLE, STEPPED, BLOCKED }

    /** Manual steps queued beyond this are dropped, so holding a key doesn't overshoot. */
    public static final int MAX_QUEUED_STEPS = Intent.MAX_QUEUED_STEPS;

    private final Mobile mobile;
    private Predicate<Position> passable = position -> true;
    private final Deque<Direction> steps = new ArrayDeque<>();
    private Deque<Position> path = new ArrayDeque<>();
    private Direction faceAtEnd;

    public Walker(Mobile mobile) {
        this.mobile = mobile;
    }

    public void setPassable(Predicate<Position> passable) {
        this.passable = passable;
    }

    /** Returns true if this threw away queued manual steps. */
    public boolean walkTo(Position target) {
        boolean discarded = !steps.isEmpty();
        steps.clear();
        path = Pathfinding.path(mobile.position(), target, passable);
        Position end = path.isEmpty() ? mobile.position() : path.peekLast();
        faceAtEnd = passable.test(target) ? null : Direction.toward(end, target);
        return discarded;
    }

    /** Returns false if the step was dropped (queue full). */
    public boolean queueStep(Direction direction) {
        path.clear();
        faceAtEnd = null;
        if (steps.size() >= MAX_QUEUED_STEPS) {
            return false;
        }
        steps.addLast(direction);
        return true;
    }

    /** Returns true if this threw away queued manual steps. */
    public boolean stop() {
        boolean discarded = !steps.isEmpty();
        steps.clear();
        path.clear();
        faceAtEnd = null;
        return discarded;
    }

    public boolean isWalking() {
        return !steps.isEmpty() || !path.isEmpty() || faceAtEnd != null;
    }

    /** One step (or turn). STEPPED = something changed (or a manual step was used up); BLOCKED = the path got cut off. */
    public Result tick() {
        if (!steps.isEmpty()) {
            Direction direction = steps.pollFirst();
            mobile.face(direction);
            Position target = mobile.position().step(direction);
            if (passable.test(target)) {
                mobile.moveTo(target);
            }
            return Result.STEPPED;
        }
        if (!path.isEmpty()) {
            Position here = mobile.position();
            Position next = path.peekFirst();
            if (!Pathfinding.canStep(here, next, passable)) {
                path.clear();
                faceAtEnd = null;
                return Result.BLOCKED;
            }
            path.pollFirst();
            mobile.face(Direction.facingFor(next.x() - here.x(), next.y() - here.y()));
            mobile.moveTo(next);
            if (path.isEmpty() && faceAtEnd != null) {
                mobile.face(faceAtEnd);
                faceAtEnd = null;
            }
            return Result.STEPPED;
        }
        if (faceAtEnd != null) {
            mobile.face(faceAtEnd);
            faceAtEnd = null;
            return Result.STEPPED;
        }
        return Result.IDLE;
    }
}
