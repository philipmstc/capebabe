package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.generation.SkillFactory;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.SkillType;
import net.shchoo.scafe.scafe.model.WorldContent;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class AccuracyAndWeaponsTest {

    @Test
    void levelWithEachOther_thePlainAccuracies() {
        assertEquals(0.8, Combat.playerAccuracy(0), 1e-9);
        assertEquals(0.6, Combat.monsterAccuracy(0), 1e-9);
    }

    @Test
    void aboveTowards0_9_belowTowardsTheFloor_neverReachingEither() {
        // (much further apart than this, the doubles can't tell the difference)
        double last = Combat.playerAccuracy(-61);
        for (int lead = -60; lead <= 60; lead++) {
            double now = Combat.playerAccuracy(lead);
            assertTrue(now > last, "more levels, more hits: " + lead);
            assertTrue(now > 0.5 && now < 0.9, lead + ": " + now);
            last = now;
        }
        last = Combat.monsterAccuracy(-61);
        for (int lead = -60; lead <= 60; lead++) {
            double now = Combat.monsterAccuracy(lead);
            assertTrue(now > last, "more levels, more hits: " + lead);
            assertTrue(now > 0.1 && now < 0.9, lead + ": " + now);
            last = now;
        }
        assertTrue(Combat.playerAccuracy(1) - Combat.playerAccuracy(0) > Combat.playerAccuracy(11) - Combat.playerAccuracy(10),
                "diminishing returns");
        double playerDrop = (0.8 - Combat.playerAccuracy(-5)) / (0.8 - 0.5);
        double monsterDrop = (0.6 - Combat.monsterAccuracy(-5)) / (0.6 - 0.1);
        assertTrue(monsterDrop > playerDrop, "a monster below you falls off quicker than you below it");
    }

    @Test
    void secondaryWeaponsHit2Harder_tertiary5() {
        WorldContent content = new WorldContent();
        WorldContent next = content.copy();
        Random rng = new Random(4);
        SkillFactory.createSkill(next, rng, "skill 0", SkillType.PRIMARY);
        SkillFactory.createSkill(next, rng, "skill 1", SkillType.SECONDARY);
        SkillFactory.createSkill(next, rng, "skill 2", SkillType.TERTIARY);
        content.apply(next.diff(content));   // as the world has them: recipes and all
        Skill primary = content.craftingSkills().get(0), secondary = content.craftingSkills().get(1), tertiary = content.craftingSkills().get(2);
        assertEquals(java.util.List.of(SkillType.PRIMARY, SkillType.SECONDARY, SkillType.TERTIARY), java.util.List.of(primary.type, secondary.type, tertiary.type));
        Product p = primary.productLines.get(0).recipes.get(2).product;
        Product s = secondary.productLines.get(0).recipes.get(2).product;
        Product t = tertiary.productLines.get(0).recipes.get(2).product;
        assertEquals(3, Product.weaponBase(content, p), "tier 2: 3");
        assertEquals(3 + Product.SECONDARY_DAMAGE, Product.weaponBase(content, s));
        assertEquals(3 + Product.TERTIARY_DAMAGE, Product.weaponBase(content, t));
        assertEquals(1, Product.weaponBase(content, null), "fists");
    }

    @Test
    void skillNamesGetASpace_savedOnesToo() {
        assertEquals("skill 3", WorldContent.spaced("skill3"));
        assertEquals("skill 12-2", WorldContent.spaced("skill12-2"));
        assertEquals("blessing of skill 4-1", WorldContent.spaced("blessing of skill4-1"));
        assertEquals("skill 3", WorldContent.spaced("skill 3"), "already spaced");
        assertEquals("monster3-drop1", WorldContent.spaced("monster3-drop1"));
        assertEquals("combat", WorldContent.spaced("combat"));
    }
}
