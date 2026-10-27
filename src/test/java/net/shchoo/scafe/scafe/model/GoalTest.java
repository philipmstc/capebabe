package net.shchoo.scafe.scafe.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GoalTest {
    private final Goal goal = new Goal(new Skill(1, "skill0", SkillType.PRIMARY, 0, '#'), 35, new Product(2, "skill0{0}-2"));

    @Test
    void levelsStepBetweenItsSkillTypesMilestonesAndTheMax() {
        // primary: 10 20 30 40 45 50 60 65 70 75 80 90 95 100
        assertEquals(40, goal.stepped(1), "35 is off the list: up to tier 2");
        assertEquals(30, goal.stepped(-1));
        assertEquals(10, goal.withLevel(20).stepped(-1), "primary tier 0 makes x2 at 10");
        assertEquals(65, goal.withLevel(60).stepped(1), "tier 1 makes x3");
        assertEquals(100, goal.withLevel(95).stepped(1));
        assertEquals(100, goal.withLevel(100).stepped(1), "nothing past the max");
        assertEquals(10, goal.withLevel(1).stepped(1), "an off-list level steps onto the list");
        assertTrue(goal.allows(100));
        assertTrue(goal.allows(45));
        assertFalse(goal.allows(35));
        assertFalse(goal.allows(0));
        Goal tertiary = new Goal(new Skill(5, "skill5", SkillType.TERTIARY, 0, '%'), 60, new Product(6, "skill5-3"));
        assertFalse(tertiary.allows(45), "not a tertiary skill's");
        assertEquals(65, tertiary.stepped(1));
    }
}
