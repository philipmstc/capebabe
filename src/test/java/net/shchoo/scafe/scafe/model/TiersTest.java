package net.shchoo.scafe.scafe.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TiersTest {
    @Test
    void unlockLevels() {
        assertArrayEquals(new int[]{0, 20, 40, 60, 80}, Tiers.LEVEL_NEEDED);
    }

    @Test
    void yieldsGoUpAtTheirLevels() {
        int[][] table = {
                // tier, level, makes
                {0, 0, 1}, {0, 29, 1}, {0, 30, 2}, {0, 64, 2}, {0, 65, 3}, {0, 99, 3},
                {1, 20, 1}, {1, 50, 2}, {1, 75, 3},
                {2, 40, 1}, {2, 70, 2}, {2, 85, 3},
                {3, 60, 1}, {3, 89, 1}, {3, 90, 2}, {3, 95, 3},
                {4, 80, 1}, {4, 99, 1}, {4, 100, 2},
        };
        for (int[] row : table) {
            assertEquals(row[2], Tiers.yield(row[0], row[1]), "tier " + row[0] + " at Lv. " + row[1]);
        }
        assertEquals(65, Tiers.nextYieldLevel(0, 30));
        assertEquals(-1, Tiers.nextYieldLevel(0, 65), "x3 is as good as it gets");
        assertEquals(100, Tiers.nextYieldLevel(4, 1));
    }

    @Test
    void oldWorldsDefaultLevelsMoveToTheNewOnes() {
        assertEquals(40, Tiers.levelNeeded(2, 35));
        assertEquals(60, Tiers.levelNeeded(3, 50));
        assertEquals(80, Tiers.levelNeeded(4, 70));
        assertEquals(80, Tiers.levelNeeded(4, 85), "the in-between default too");
        assertEquals(20, Tiers.levelNeeded(1, 20));
        assertEquals(42, Tiers.levelNeeded(2, 42), "not a default: left alone");
    }

    @Test
    void milestones() {
        assertArrayEquals(new int[]{20, 30, 40, 50, 60, 65, 70, 75, 80, 85, 90, 95, 100}, Tiers.milestones(), "the same steps as before");
    }
}
