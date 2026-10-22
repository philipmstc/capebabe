package net.shchoo.scafe.scafe.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GoalTest {
    private final Goal goal = new Goal(new Skill(1, "skill0", SkillType.PRIMARY, 0, '#'), 35, new Product(2, "skill0{0}-2"));

    @Test
    void levelsStepBetweenTheTierRequirementsAndTheMax() {
        assertEquals(50, goal.stepped(1));
        assertEquals(20, goal.stepped(-1));
        assertEquals(20, goal.withLevel(20).stepped(-1), "nothing below 20");
        assertEquals(100, goal.withLevel(70).stepped(1));
        assertEquals(100, goal.withLevel(100).stepped(1), "nothing past the max");
        assertEquals(20, goal.withLevel(1).stepped(1), "an off-list level steps onto the list");
        assertTrue(Goal.allowed(100));
        assertFalse(Goal.allowed(0));
    }
}
