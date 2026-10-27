package net.shchoo.scafe.scafe.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TiersTest {
    @Test
    void unlockLevelsAreTheSameForEveryType() {
        assertArrayEquals(new int[]{0, 20, 40, 60, 80}, Tiers.LEVEL_NEEDED);
    }

    /** Each type's table: {tier, x1, x2, x3} (-1: never). */
    private static void table(SkillType type, int[][] rows) {
        for (int[] row : rows) {
            int tier = row[0];
            assertEquals(row[2], Tiers.yieldLevel(type, tier, 2), type + " T" + tier + " x2");
            assertEquals(row[3], Tiers.yieldLevel(type, tier, 3), type + " T" + tier + " x3");
            int x2 = row[2], x3 = row[3];
            if (x2 > 0) {
                assertEquals(1, Tiers.yield(type, tier, x2 - 1));
                assertEquals(2, Tiers.yield(type, tier, x2));
            }
            if (x3 > 0) {
                assertEquals(2, Tiers.yield(type, tier, x3 - 1));
                assertEquals(3, Tiers.yield(type, tier, x3));
            }
        }
    }

    @Test
    void primary() {
        table(SkillType.PRIMARY, new int[][]{{0, 0, 10, 45}, {1, 20, 30, 65}, {2, 40, 50, 75}, {3, 60, 70, 95}, {4, 80, 90, -1}});
    }

    @Test
    void secondary() {
        table(SkillType.SECONDARY, new int[][]{{0, 0, 20, 55}, {1, 20, 40, 75}, {2, 40, 60, 95}, {3, 60, 80, -1}, {4, 80, 100, -1}});
    }

    @Test
    void tertiary() {
        table(SkillType.TERTIARY, new int[][]{{0, 0, 30, 65}, {1, 20, 50, 95}, {2, 40, 70, -1}, {3, 60, 90, -1}, {4, 80, -1, -1}});
        assertEquals(1, Tiers.yield(SkillType.TERTIARY, 4, 100), "tertiary tier 4 never makes more");
    }

    @Test
    void nextYield() {
        assertEquals(45, Tiers.nextYieldLevel(SkillType.PRIMARY, 0, 10));
        assertEquals(-1, Tiers.nextYieldLevel(SkillType.PRIMARY, 0, 45), "x3 is as good as it gets");
        assertEquals(-1, Tiers.nextYieldLevel(SkillType.TERTIARY, 4, 1), "never");
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
    void milestonesByType() {
        assertArrayEquals(new int[]{10, 20, 30, 40, 45, 50, 60, 65, 70, 75, 80, 90, 95, 100}, Tiers.milestones(SkillType.PRIMARY));
        assertArrayEquals(new int[]{20, 30, 40, 50, 60, 65, 70, 80, 90, 95, 100}, Tiers.milestones(SkillType.TERTIARY));
    }
}
