package net.shchoo.scafe.scafe.generation;

import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.SkillType;
import net.shchoo.scafe.scafe.model.World;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** New skills lean to using what nothing uses yet. */
class DeadEndsTest {
    private static World world() {
        World world = new World(1, "w", 3);
        world.content.apply(WorldGenerator.newSkill(world, SkillType.PRIMARY));
        return world;
    }

    private static long inputsFrom(Skill made, List<Skill> from) {
        return made.productLines.get(0).recipes.get(0).components.keySet().stream()
                .filter(part -> from.stream().anyMatch(s -> s.productLines.get(0).recipes.get(0).product == part)).count();
    }

    @Test
    void anUnusedPrimary_theNextSkillIsAlmostAlwaysASecondaryUsingIt() {
        Random rng = new Random(9);
        int secondaries = 0, tries = 500;
        for (int seed = 0; seed < tries; seed++) {
            World world = world();
            world.content.apply(WorldGenerator.newSkill(world, SkillType.PRIMARY));
            List<Skill> primaries = List.copyOf(world.content.craftingSkills());
            Skill next = SkillFactory.createRandomSkill(world.content, rng);
            if (next.type == SkillType.SECONDARY) {
                secondaries++;
                assertTrue(inputsFrom(next, primaries) >= 1, "uses one of them");
            }
        }
        assertTrue(secondaries > tries * 0.9, secondaries + " of " + tries);
    }

    private static int tertiariesNext(int unusedSecondaries, Random rng) {
        int tertiaries = 0;
        for (int i = 0; i < 1000; i++) {
            World world = world();
            for (int s = 0; s < unusedSecondaries; s++) {
                world.content.apply(WorldGenerator.newSkill(world, SkillType.SECONDARY));
            }
            tertiaries += SkillFactory.createRandomSkill(world.content, rng).type == SkillType.TERTIARY ? 1 : 0;
        }
        return tertiaries;
    }

    @Test
    void unusedSecondaries_theNextSkillLeansToATertiary_moreTheMoreThereAre() {
        Random rng = new Random(9);
        int one = tertiariesNext(1, rng), two = tertiariesNext(2, rng), four = tertiariesNext(4, rng);
        assertTrue(two > 500, "two: at least the nudge's half, got " + two);
        assertTrue(two > one, one + " with one, " + two + " with two");
        assertTrue(four > 875, "four: at least 87.5%, got " + four);
    }

    @Test
    void twoUnusedSecondaries_theNextTertiaryTakesOneForSure_oftenBoth() {
        Random rng = new Random(9);
        int both = 0, tries = 500;
        for (int seed = 0; seed < tries; seed++) {
            World world = world();
            world.content.apply(WorldGenerator.newSkill(world, SkillType.SECONDARY));
            world.content.apply(WorldGenerator.newSkill(world, SkillType.SECONDARY));
            List<Skill> secondaries = world.content.craftingSkills().stream().filter(s -> s.type == SkillType.SECONDARY).toList();
            Skill tertiary = SkillFactory.createSkill(world.content, rng, "t", SkillType.TERTIARY);
            long used = inputsFrom(tertiary, secondaries);
            assertTrue(used >= 1, "at least one");
            both += used == 2 ? 1 : 0;
        }
        assertTrue(both > tries * 0.6, both + " of " + tries + " took both");
    }
}
