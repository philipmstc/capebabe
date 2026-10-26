package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.protocol.GameClock;

/**
 * The church's guess at when the world's next skill turns up, worked out loosely from the new-skill rolls
 * ({@link Discovery}): how many there are an hour, the chance they've each had (on average, before the backoff for
 * how many skills there are), and how many there have been since the last skill was found. A skill is due after
 * about 1 / chance rolls; take off the ones already rolled and divide by the rate.
 * <p>
 * The rate and average chance are over the last hour the server was up (and saved, with the rolls since the last
 * skill, so a restart doesn't forget them; a fresh hour leans on the saved ones until it has a few minutes in it).
 * <p>
 * Past due, it says how long ago it should have come, and how many times over (rolls since x chance) it's gone.
 * Tithes weigh on it more than they should ({@link #TITHE_ROLLS_PER_ITEM}): it's a church.
 */
public final class Oracle {
    /** What's kept with the world between runs. */
    public record Saved(double rollsSince, double rollsPerHour, double baseChance) {}

    /**
     * What the oracle says: {@code minutes} until the next skill's due (negative: how long ago it was), and {@code
     * fold}, how many skills' worth of rolls there have been since the last (2: twice over). Not {@code known}: no
     * rate to go on.
     */
    public record Prophecy(boolean known, long minutes, int fold) {
        static final Prophecy SILENT = new Prophecy(false, 0, 0);
    }

    /** Each item tithed counts as this many rolls towards the prophecy -- far more than its real chance's worth. */
    static final double TITHE_ROLLS_PER_ITEM = 0.02;

    static final int MINUTES = 60;
    static final long TICKS_PER_MINUTE = 60_000 / GameClock.TICK_MILLIS;
    /** A live hour with less than this many minutes in it trusts the saved rate instead (if there is one). */
    static final int TRUSTED_MINUTES = 5;
    /** Guesses past this are just "this far". */
    public static final long MAX_MINUTES = 99L * 24 * 60;

    private final int[] rolls = new int[MINUTES];
    private final double[] chances = new double[MINUTES];
    private long firstMinute = -1;
    private long minute = -1;
    private double rollsSince;
    private final double savedRate;
    private final double savedBase;

    public Oracle(Saved saved) {
        rollsSince = saved == null ? 0 : saved.rollsSince();
        savedRate = saved == null ? 0 : saved.rollsPerHour();
        savedBase = saved == null ? 0 : saved.baseChance();
    }

    /** Every tick: moves the hour along (a minute that's come round again starts empty). */
    void advance(long tick) {
        long now = tick / TICKS_PER_MINUTE;
        if (firstMinute < 0) {
            firstMinute = now;
            minute = now;
        }
        while (minute < now) {
            minute++;
            int bucket = (int) (minute % MINUTES);
            rolls[bucket] = 0;
            chances[bucket] = 0;
        }
    }

    /** A new-skill roll, with this chance before the backoff. */
    void rolled(double baseChance) {
        int bucket = (int) (Math.max(0, minute) % MINUTES);
        rolls[bucket]++;
        chances[bucket] += baseChance;
        rollsSince++;
    }

    /** A tithe of {@code items}: the prophecy draws nearer (more than the roll it really got). */
    void tithed(int items) {
        rollsSince += items * TITHE_ROLLS_PER_ITEM;
    }

    /** A skill was found: the count starts again. */
    void found() {
        rollsSince = 0;
    }

    double rollsSince() {
        return rollsSince;
    }

    private int minutesCovered() {
        return firstMinute < 0 ? 0 : (int) Math.min(MINUTES, minute - firstMinute + 1);
    }

    private int liveRolls() {
        int sum = 0;
        for (int r : rolls) {
            sum += r;
        }
        return sum;
    }

    double rollsPerHour() {
        int covered = minutesCovered();
        if ((covered < TRUSTED_MINUTES && savedRate > 0) || covered == 0) {
            return savedRate;
        }
        return liveRolls() * (double) MINUTES / covered;
    }

    double baseChance() {
        int live = liveRolls();
        if (live == 0) {
            return savedBase > 0 ? savedBase : Discovery.Trigger.MOVED.skillChance;
        }
        double sum = 0;
        for (double c : chances) {
            sum += c;
        }
        return sum / live;
    }

    /** What the oracle says with {@code skills} crafting skills about. */
    public Prophecy prophecy(int skills) {
        double chance = baseChance() * Math.pow(Discovery.SKILL_BACKOFF, skills);
        double rate = rollsPerHour();
        if (chance <= 0 || rate <= 0) {
            return Prophecy.SILENT;
        }
        double minutes = (1 / chance - rollsSince) / rate * 60;
        // to come: rounded up; late: by the whole minutes gone
        long rounded = (long) Math.max(-MAX_MINUTES, Math.min(MAX_MINUTES, minutes > 0 ? Math.ceil(minutes) : -Math.floor(-minutes)));
        return new Prophecy(true, rounded, (int) Math.min(99, Math.floor(rollsSince * chance)));
    }

    public Saved saved() {
        return new Saved(rollsSince, rollsPerHour(), baseChance());
    }
}
