package net.shchoo.scafe.scafe.model;

import net.shchoo.scafe.scafe.generation.SkillFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class SplitSkillsTest {
    /** A batch the way an old world saved it: skill0 with two product lines. */
    private static ContentBatch oldTwoLineSkill() {
        WorldContent before = new WorldContent();
        WorldContent made = before.copy();
        Random rng = new Random(1);
        Skill skill = SkillFactory.createSkill(made, rng, "skill0", SkillType.PRIMARY);
        SkillFactory.addNewProductLineToSkill(skill, made, rng);
        ContentBatch batch = made.diff(before);
        assertEquals(1, batch.skills().size());
        assertEquals(2, batch.productLines().size());
        return batch;
    }

    @Test
    void anOldWorldsExtraLineBecomesASkillOfItsOwn() {
        ContentBatch batch = oldTwoLineSkill();
        WorldContent content = new WorldContent();
        content.apply(batch);
        assertEquals(List.of("skill0", "skill1"), content.craftingSkills().stream().map(s -> s.name).toList());
        Skill original = content.craftingSkills().get(0), split = content.craftingSkills().get(1);
        assertEquals(1, original.productLines.size());
        assertEquals(1, split.productLines.size());
        assertEquals(batch.productLines().get(1).id(), split.id, "the line's id: stable");
        assertSame(original, content.splitFrom(split));
        assertEquals(original.type, split.type);
        for (Recipe recipe : split.productLines.get(0).recipes) {
            assertSame(split, recipe.skill, "its recipes are its own");
            assertTrue(recipe.product.name.startsWith("skill0{1}"), "the items keep their names");
        }
        WorldContent replay = new WorldContent();
        content.history().forEach(replay::apply);
        assertEquals("skill1", replay.skill(split.id).name, "the same every time (server, clients)");
    }

    @Test
    void aLaterSkillWithItsNameMovesItOn() {
        WorldContent content = new WorldContent();
        content.apply(oldTwoLineSkill());
        Skill split = content.craftingSkills().get(1);
        WorldContent next = content.copy();
        SkillFactory.createSkill(next, new Random(2), "skill1", SkillType.PRIMARY);
        content.apply(next.diff(content));
        assertEquals("skill1", content.craftingSkills().get(2).name, "the saved name stands");
        assertEquals("skill2", split.name, "the split one takes the next free");
    }

    @Test
    void newSkillsHaveOneLineAndTheNextFreeName() {
        WorldContent content = new WorldContent();
        content.apply(oldTwoLineSkill());
        WorldContent next = content.copy();
        Skill made = SkillFactory.createRandomSkill(next, new Random(3));
        assertEquals("skill2", made.name);
        assertEquals(1, made.productLines.size());
    }
}
