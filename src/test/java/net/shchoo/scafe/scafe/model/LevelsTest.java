package net.shchoo.scafe.scafe.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LevelsTest {
    private static final long MAX_XP = Skill.XP_PER_LEVEL[Levels.MAX_LEVEL - 1];

    @Test
    void theVirtualLevelIsTheLevelUpToTheCap() {
        assertEquals(1, Levels.virtualLevelFor(0));
        assertEquals(42, Levels.virtualLevelFor(Skill.XP_PER_LEVEL[41]));
        assertEquals(Levels.MAX_LEVEL, Levels.virtualLevelFor(MAX_XP));
    }

    @Test
    void pastTheCapItCarriesOnWithTheFormula() {
        long to101 = (long) (1047 * Math.pow(2, 10));
        assertEquals(Levels.MAX_LEVEL, Levels.virtualLevelFor(MAX_XP + to101 - 1));
        assertEquals(Levels.MAX_LEVEL + 1, Levels.virtualLevelFor(MAX_XP + to101));
        assertEquals(Levels.MAX_LEVEL, Levels.levelFor(MAX_XP + to101), "the real level stays capped");
        assertTrue(Levels.virtualLevelFor(2 * MAX_XP) > 105, "two prestiges' worth");
    }

    @Test
    void theFormulaMatchesTheTable() {
        for (int level = 2; level < Levels.MAX_LEVEL; level++) {
            long step = Skill.XP_PER_LEVEL[level] - Skill.XP_PER_LEVEL[level - 1];
            assertEquals(1047 * Math.pow(2, level / 10.0), step, 1.5, "Lv. " + level + " to " + (level + 1));
        }
    }
}
