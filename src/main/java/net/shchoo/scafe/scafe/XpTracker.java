package net.shchoo.scafe.scafe;

import java.util.function.LongSupplier;

/** Follows the most recently trained skill and works out xp/hour for the current session. */
public class XpTracker {
    public static final long IDLE_RESET_MILLIS = 5 * 60 * 1000;
    private static final long MILLIS_PER_HOUR = 60 * 60 * 1000;

    private final LongSupplier clock;
    private Skill skill;
    private long sessionStart;
    private long lastGain;
    private long sessionXp;

    public XpTracker() {
        this(System::currentTimeMillis);
    }

    public XpTracker(LongSupplier clock) {
        this.clock = clock;
    }

    public void record(Skill skill, long gained) {
        long now = clock.getAsLong();
        if (skill != this.skill || now - lastGain > IDLE_RESET_MILLIS) {
            this.skill = skill;
            sessionStart = now;
            sessionXp = 0;
        } else {
            // the first drop of a session only starts the clock; counting it would inflate the rate
            sessionXp += gained;
        }
        lastGain = now;
    }

    public Skill skill() {
        return skill;
    }

    /** Null until there's enough to go on (a second drop). */
    public Long xpPerHour() {
        long elapsed = clock.getAsLong() - sessionStart;
        if (skill == null || sessionXp == 0 || elapsed <= 0) {
            return null;
        }
        return sessionXp * MILLIS_PER_HOUR / elapsed;
    }
}
