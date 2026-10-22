package net.shchoo.scafe.scafe.model;

import org.junit.jupiter.api.Test;

import static net.shchoo.scafe.scafe.model.DamageColor.*;
import static org.junit.jupiter.api.Assertions.*;

class DamageColorTest {
    private static final int RED = 0, YELLOW = 1, GREEN = 2, CYAN = 3, BLUE = 4, MAGENTA = 5;

    private static void x(double expected, int attacker, int defender) {
        assertEquals(expected, multiplier(attacker, defender), 1e-9, attacker + " -> " + defender);
    }

    @Test
    void eachHueBeatsTheOne120DegreesOnAndLosesToTheOneBack() {
        x(2, RED, GREEN);
        x(2, GREEN, BLUE);
        x(2, BLUE, RED);
        x(2, CYAN, MAGENTA);
        x(2, MAGENTA, YELLOW);
        x(2, YELLOW, CYAN);
        x(0.5, GREEN, RED);
        x(0.5, RED, BLUE);
        x(0.5, MAGENTA, CYAN);
    }

    @Test
    void oppositesAndTheSameHueAreEven() {
        x(1, CYAN, RED);
        x(1, MAGENTA, GREEN);
        x(1, YELLOW, BLUE);
        x(1, RED, CYAN);
        x(1, GREEN, GREEN);
    }

    @Test
    void sixtyDegreesIsPartWay() {
        double up = Math.pow(2, Math.sin(Math.PI / 4));
        x(up, CYAN, BLUE);
        x(1 / up, CYAN, GREEN);
        assertTrue(up > 1.6 && up < 1.65, "x1.63");
    }

    @Test
    void theCurveIsContinuousAndPeaksAt120() {
        assertEquals(1, exponent(2.0 / 3), 1e-9);
        assertEquals(-1, exponent(-2.0 / 3), 1e-9);
        assertEquals(0, exponent(1), 1e-9);
        assertEquals(0, exponent(0), 1e-9);
        assertEquals(exponent(2.0 / 3 - 1e-9), exponent(2.0 / 3 + 1e-9), 1e-6);
        for (double theta = 0; theta <= 1; theta += 0.01) {
            assertTrue(exponent(theta) <= 1 + 1e-9);
            assertEquals(-exponent(theta), exponent(-theta), 1e-9);
        }
    }

    @Test
    void whiteBeatsColourBlackBeatsTheColourless() {
        for (int hue = 0; hue < HUES; hue++) {
            x(2, WHITE, hue);
            x(0.5, BLACK, hue);
            x(1, hue, WHITE);
            x(1, hue, BLACK);
        }
        x(0.5, WHITE, WHITE);
        x(0.5, WHITE, BLACK);
        x(2, BLACK, WHITE);
        x(2, BLACK, BLACK);
        assertEquals(BLACK, FISTS);
    }
}
