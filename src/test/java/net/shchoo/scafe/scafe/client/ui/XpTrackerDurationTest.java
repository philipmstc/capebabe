package net.shchoo.scafe.scafe.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XpTrackerDurationTest {
    private static final long MINUTE = 60_000;

    @Test
    void durationsAreRoughAndShort() {
        assertEquals("<1m", XpTrackerView.duration(59_000));
        assertEquals("42m", XpTrackerView.duration(42 * MINUTE));
        assertEquals("3h 05m", XpTrackerView.duration(185 * MINUTE));
        assertEquals("2d 4h", XpTrackerView.duration((2 * 24 + 4) * 60 * MINUTE + 30 * MINUTE));
    }
}
