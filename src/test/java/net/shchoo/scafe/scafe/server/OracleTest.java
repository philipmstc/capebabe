package net.shchoo.scafe.scafe.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OracleTest {
    /** {@code minutes} minutes of {@code perMinute} rolls at {@code chance}. */
    private static Oracle rolling(Oracle oracle, int minutes, int perMinute, double chance) {
        for (int m = 0; m < minutes; m++) {
            oracle.advance(m * Oracle.TICKS_PER_MINUTE);
            for (int r = 0; r < perMinute; r++) {
                oracle.rolled(chance);
            }
        }
        return oracle;
    }

    @Test
    void dueAfterAboutOneOverTheChanceRolls_lessTheOnesSoFar_atTheRate() {
        Oracle oracle = rolling(new Oracle(null), 10, 1, 0.03);
        // 1 / 0.03 = 33.3 rolls, 10 rolled, 60 an hour: 23.3 minutes
        Oracle.Prophecy said = oracle.prophecy(0);
        assertTrue(said.known());
        assertEquals(24, said.minutes());
        assertEquals(0, said.fold());
        assertTrue(oracle.prophecy(3).minutes() > said.minutes(), "more skills: rarer");
    }

    @Test
    void lateItCountsBackAndThenHowManyTimesOver() {
        // a roll a minute at 0.1: one's due every 10 rolls (10 minutes)
        Oracle oracle = rolling(new Oracle(null), 30, 1, 0.1);
        Oracle.Prophecy said = oracle.prophecy(0);
        assertEquals(-20, said.minutes(), "30 rolls, 10 due: 20 rolls (20 minutes) late");
        assertEquals(3, said.fold(), "three times over");
        oracle = rolling(new Oracle(null), 15, 1, 0.1);
        assertEquals(-5, oracle.prophecy(0).minutes());
        assertEquals(1, oracle.prophecy(0).fold(), "late, but not twice over");
        oracle.found();
        assertEquals(0, oracle.rollsSince(), 0.0);
        assertTrue(oracle.prophecy(0).minutes() > 0);
    }

    @Test
    void titheslookLikeTheyHelpMoreThanTheyDo() {
        Oracle oracle = rolling(new Oracle(null), 10, 1, 0.03);
        long before = oracle.prophecy(0).minutes();
        oracle.tithed(500);
        // 500 items = 10 rolls' worth to the oracle (their real chance is a third of one)
        assertEquals(before - 10, oracle.prophecy(0).minutes(), 1);
        assertEquals(60, oracle.rollsPerHour(), 0.001, "a tithe isn't a roll: the rate doesn't move");
    }

    @Test
    void nothingToGoOn() {
        Oracle oracle = new Oracle(null);
        assertFalse(oracle.prophecy(0).known());
        oracle.advance(Oracle.TICKS_PER_MINUTE * 3);
        assertFalse(oracle.prophecy(0).known(), "an empty hour: no rate");
    }

    @Test
    void aFreshStartLeansOnWhatWasSaved() {
        Oracle oracle = new Oracle(new Oracle.Saved(5, 120, 0.05));
        oracle.advance(1);
        // 0.05 * 0.6 = 0.03 a roll: 33.3, less 5, at 120 an hour: 14.2 minutes
        assertEquals(15, oracle.prophecy(1).minutes());
        assertEquals(5, oracle.saved().rollsSince(), 0.0);
    }

    @Test
    void theHourMovesOn() {
        Oracle oracle = rolling(new Oracle(null), 60, 10, 0.03);
        assertEquals(600, oracle.rollsPerHour(), 0.001);
        oracle.advance(120 * Oracle.TICKS_PER_MINUTE);
        assertEquals(0, oracle.rollsPerHour(), 0.001, "an hour later with nothing rolled");
    }
}
