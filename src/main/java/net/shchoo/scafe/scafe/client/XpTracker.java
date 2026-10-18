package net.shchoo.scafe.scafe.client;

import net.shchoo.scafe.scafe.model.Skill;

import java.util.function.LongSupplier;

/**
 * Follows the most recently trained skill and works out xp/hour for the current session. Only time spent
 * doing actions counts, so the rate holds steady while you're idle instead of decaying.
 */
public class XpTracker {
    public static final long IDLE_RESET_MILLIS = 5 * 60 * 1000;
    private static final long MILLIS_PER_HOUR = 60 * 60 * 1000;

    private final LongSupplier clock;
    private Skill skill;
    private long lastGain;
    private long sessionXp;
    private boolean active;
    private long activeSince;
    /** Action time banked this session, not counting the current stretch if active. */
    private long activeMillis;

    public XpTracker() {
        this(System::currentTimeMillis);
    }

    public XpTracker(LongSupplier clock) {
        this.clock = clock;
    }

    public void record(Skill skill, long gained) {
        long now = clock.getAsLong();
        if (!skill.equals(this.skill) || now - lastGain > IDLE_RESET_MILLIS) {
            this.skill = skill;
            sessionXp = 0;
            activeMillis = 0;
            activeSince = now;
        } else {
            // the first drop of a session only starts the clock; counting it would inflate the rate
            sessionXp += gained;
        }
        lastGain = now;
    }

    /** Call when an action starts / stops; the session clock only runs in between. */
    public void setActive(boolean nowActive) {
        long now = clock.getAsLong();
        if (nowActive && !active) {
            activeSince = now;
        } else if (!nowActive && active) {
            activeMillis += now - activeSince;
        }
        active = nowActive;
    }

    private long activeElapsed() {
        return activeMillis + (active ? clock.getAsLong() - activeSince : 0);
    }

    public Skill skill() {
        return skill;
    }

    /** Null until there's enough to go on (a second drop). */
    public Long xpPerHour() {
        long elapsed = activeElapsed();
        if (skill == null || sessionXp == 0 || elapsed <= 0) {
            return null;
        }
        return sessionXp * MILLIS_PER_HOUR / elapsed;
    }
}
