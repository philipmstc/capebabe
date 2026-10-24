package net.shchoo.scafe.scafe.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShortNumbersTest {
    @Test
    void bigCountsShrinkRoundingDown() {
        assertEquals("999", ShortNumbers.of(999));
        assertEquals("1.0k", ShortNumbers.of(1000));
        assertEquals("1.1k", ShortNumbers.of(1113));
        assertEquals("1.9k", ShortNumbers.of(1999), "never rounded up");
        assertEquals("10.2k", ShortNumbers.of(10_250));
        assertEquals("123k", ShortNumbers.of(123_456), "no decimal from 100k");
        assertEquals("999k", ShortNumbers.of(999_999));
        assertEquals("1.2M", ShortNumbers.of(1_234_567));
        assertEquals("10.2M", ShortNumbers.of(10_234_567));
        assertEquals("123M", ShortNumbers.of(123_456_789));
        assertEquals("4.5B", ShortNumbers.of(4_500_000_000L));
    }
}
