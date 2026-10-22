package net.shchoo.scafe.scafe.model;

import com.googlecode.lanterna.Symbols;

/** Which way someone is facing (or stepping). Players are drawn as the arrow. */
public enum Direction {
    UP(0, -1, Symbols.TRIANGLE_UP_POINTING_BLACK),
    DOWN(0, 1, Symbols.TRIANGLE_DOWN_POINTING_BLACK),
    LEFT(-1, 0, Symbols.TRIANGLE_LEFT_POINTING_BLACK),
    RIGHT(1, 0, Symbols.TRIANGLE_RIGHT_POINTING_BLACK);

    public final int dx;
    public final int dy;
    public final char arrow;

    Direction(int dx, int dy, char arrow) {
        this.dx = dx;
        this.dy = dy;
        this.arrow = arrow;
    }

    /** The direction for a one-tile orthogonal step, or null for anything else. */
    public static Direction of(int dx, int dy) {
        for (Direction d : values()) {
            if (d.dx == dx && d.dy == dy) {
                return d;
            }
        }
        return null;
    }

    /** Which way you face after a step of (dx, dy): diagonals face their horizontal part. Null for no step. */
    public static Direction facingFor(int dx, int dy) {
        if (dx != 0) {
            return dx > 0 ? RIGHT : LEFT;
        }
        if (dy != 0) {
            return dy > 0 ? DOWN : UP;
        }
        return null;
    }

    /** The direction from one tile to an orthogonally adjacent one, or null if they aren't. */
    public static Direction toward(Position from, Position to) {
        return of(to.x() - from.x(), to.y() - from.y());
    }
}
