package net.shchoo.scafe.scafe.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ActionBarTest {
    /** 5 frames of 60ms = half a tick. */
    private static final double HOLD = 0.5;

    @Test
    void aNormalTwoTickItemLooksTheSameAsBefore() {
        assertEquals(0.5, Player.barFraction(0.75, 2), 1e-9);
        assertEquals(1.0, Player.barFraction(1.5, 2), 1e-9, "full for its last half tick, as before");
        assertTrue(Player.barFraction(1.49, 2) < 1);
    }

    @Test
    void aLongWaitIsFullForTheSameShortMoment() {
        assertTrue(Player.barFraction(20 - HOLD - 0.01, 20) < 1, "not full early, however long it is");
        assertEquals(1.0, Player.barFraction(20 - HOLD, 20), 1e-9);
        assertTrue(Player.barFraction(15, 20) < 0.8, "15 of 20 ticks: not full (it used to be, from 15 on)");
    }

    @Test
    void aVeryShortOneStillFills() {
        assertEquals(1.0, Player.barFraction(0.5, 1), 1e-9, "1 tick: fills over the first half");
        assertEquals(0.5, Player.barFraction(0.25, 1), 1e-9);
    }
}
