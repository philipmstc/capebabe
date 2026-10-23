package net.shchoo.scafe.scafe.client;

import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.protocol.Event;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HitSideTest {
    private static final Position YOU = new Position(5, 5);

    private static int yours(Position monster) {
        return Player.hitSide(new Event.Hit(1, 2, YOU, monster), true);
    }

    private static int theirs(Position monster) {
        return Player.hitSide(new Event.Hit(2, 2, monster, YOU), false);
    }

    @Test
    void eachPopupGoesOnItsOwnFightersSide() {
        assertEquals(-1, yours(new Position(6, 5)), "monster on the right: yours on the left");
        assertEquals(1, theirs(new Position(6, 5)), "...and theirs on the right");
        assertEquals(1, yours(new Position(4, 5)), "monster on the left: yours on the right");
        assertEquals(-1, theirs(new Position(4, 5)), "...and theirs on the left");
        assertEquals(1, yours(new Position(4, 4)), "diagonal counts by its column");
        assertEquals(-1, theirs(new Position(4, 6)));
    }

    @Test
    void straightAboveOrBelowYoursGoesLeft() {
        assertEquals(-1, yours(new Position(5, 4)));
        assertEquals(1, theirs(new Position(5, 4)));
    }
}
