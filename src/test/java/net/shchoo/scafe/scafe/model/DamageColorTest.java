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
    void everySkillHasAHueOfItsOwn_inTheBiggestGapLeft() {
        double[] expected = {0, 60, 120, 180, 240, 300, 30, 90, 150, 210, 270, 330, 15, 75, 135, 195, 255, 315,
                45, 105, 165, 225, 285, 345, 7.5, 67.5, 127.5, 187.5, 247.5, 307.5, 37.5};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], skillHue(i), 1e-9, "skill colour " + i);
        }
        java.util.Set<Double> seen = new java.util.HashSet<>();
        for (int i = 0; i < 60; i++) {
            assertTrue(seen.add(skillHue(i)), "no two the same: " + i);
        }
    }

    @Test
    void anyTwoHuesFight_bySmoothlyHowFarApartTheyAre() {
        assertEquals(2, hueMultiplier(30, 150), 1e-9, "120 degrees on");
        assertEquals(0.5, hueMultiplier(150, 30), 1e-9);
        assertEquals(1, hueMultiplier(45, 225), 1e-9, "opposite");
        double near = hueMultiplier(0, 100), nearer = hueMultiplier(0, 110), peak = hueMultiplier(0, 120);
        assertTrue(near < nearer && nearer < peak, near + " < " + nearer + " < " + peak);
        assertEquals(hueMultiplier(350, 110), hueMultiplier(0, 120), 1e-9, "round the wheel");
        assertEquals(2, hueMultiplier(hueOf(WHITE), 222), 1e-9);
        assertEquals(1, hueMultiplier(222, hueOf(BLACK)), 1e-9);
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
