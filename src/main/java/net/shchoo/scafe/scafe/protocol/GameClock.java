package net.shchoo.scafe.scafe.protocol;

import java.util.function.LongSupplier;

/**
 * Game logic advances in fixed ticks (server time); rendering happens every frame in between.
 * {@link #partialTick()} says how far we are into the current tick, for smooth animation.
 */
public class GameClock {
    public static final long TICK_MILLIS = 600;
    public static final long FRAME_MILLIS = 60;
    /** If we fall further behind than this (debugger, sleep...), skip ahead instead of fast-forwarding. */
    private static final int MAX_CATCH_UP_TICKS = 5;

    private final LongSupplier millis;
    private long tick = 0;
    private long lastTickAt;

    public GameClock() {
        this(System::currentTimeMillis);
    }

    public GameClock(LongSupplier millis) {
        this.millis = millis;
        this.lastTickAt = millis.getAsLong();
    }

    /** How many ticks are due since the last call; the caller runs that many logic ticks. */
    public int ticksDue() {
        long now = millis.getAsLong();
        int due = (int) ((now - lastTickAt) / TICK_MILLIS);
        if (due > MAX_CATCH_UP_TICKS) {
            lastTickAt = now - TICK_MILLIS;
            due = 1;
        } else {
            lastTickAt += due * TICK_MILLIS;
        }
        tick += due;
        return due;
    }

    public long tick() {
        return tick;
    }

    /** 0.0 right after a tick, approaching 1.0 just before the next one. */
    public double partialTick() {
        return Math.min(1.0, Math.max(0.0, (double) (millis.getAsLong() - lastTickAt) / TICK_MILLIS));
    }
}
