package net.shchoo.scafe.scafe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class XpTrackerTest {
    private long now = 0;
    private final XpTracker tracker = new XpTracker(() -> now);
    private final Skill mining = new Skill(1, "mining", SkillType.PRIMARY, 0, '#');
    private final Skill smithing = new Skill(2, "smithing", SkillType.SECONDARY, 1, '$');

    private void dropsEvery1200ms(Skill skill, int drops) {
        for (int i = 0; i < drops; i++) {
            now += 1200;
            tracker.record(skill, 200);
        }
    }

    @Test
    void rateIsXpPerHourOfActionTime() {
        tracker.setActive(true);
        now += 1200;
        tracker.record(mining, 200);
        assertNull(tracker.xpPerHour(), "first drop only starts the clock");
        dropsEvery1200ms(mining, 10);
        assertEquals(600_000L, tracker.xpPerHour());
    }

    @Test
    void idlingDoesNotDragTheRateDown() {
        tracker.setActive(true);
        now += 1200;
        tracker.record(mining, 200);
        dropsEvery1200ms(mining, 10);
        tracker.setActive(false);
        now += 60_000;
        assertEquals(600_000L, tracker.xpPerHour());

        tracker.setActive(true);
        dropsEvery1200ms(mining, 10);
        assertEquals(600_000L, tracker.xpPerHour());
    }

    @Test
    void slowerWorkStillLowersIt() {
        tracker.setActive(true);
        now += 1200;
        tracker.record(mining, 200);
        dropsEvery1200ms(mining, 10);
        now += 12_000;
        assertEquals(300_000L, tracker.xpPerHour(), "still doing an action, just not getting xp");
    }

    @Test
    void switchingSkillOrALongBreakStartsANewSession() {
        tracker.setActive(true);
        now += 1200;
        tracker.record(mining, 200);
        dropsEvery1200ms(mining, 5);
        tracker.record(smithing, 50);
        assertEquals(smithing, tracker.skill());
        assertNull(tracker.xpPerHour());

        now += XpTracker.IDLE_RESET_MILLIS + 1;
        tracker.record(smithing, 50);
        assertNull(tracker.xpPerHour());
    }
}
