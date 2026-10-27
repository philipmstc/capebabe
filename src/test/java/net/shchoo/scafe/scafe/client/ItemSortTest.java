package net.shchoo.scafe.scafe.client;

import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.World;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ItemSortTest {
    @Test
    void bySkillTypeOrTier() {
        World world = new World(1, "w", 9);
        world.content.apply(WorldGenerator.newSkills(world, 4));
        world.content.apply(WorldGenerator.newMonsterType(world));
        world.content.apply(WorldGenerator.newDrop(world, world.content.monsters.get(0).id));
        List<Skill> skills = world.content.craftingSkills();
        Skill a = skills.get(0), b = skills.get(skills.size() - 1);
        Product a0 = a.productLines.get(0).recipes.get(0).product, a2 = a.productLines.get(0).recipes.get(2).product;
        Product b1 = b.productLines.get(0).recipes.get(1).product;
        Product drop = world.content.monsters.get(0).drops.get(0).product();
        List<Product> items = new ArrayList<>(List.of(drop, a2, b1, a0));

        items.sort(ItemSort.SKILL.comparator(world.content));
        assertEquals(List.of(a0, a2, b1, drop), items, "skill, then tier; drops last");
        items.sort(ItemSort.TIER.comparator(world.content));
        assertEquals(List.of(a0, b1, a2, drop), items, "tier, then skill");
        Comparator<Product> byType = ItemSort.TYPE.comparator(world.content);
        items.sort(byType);
        assertTrue(a.type.ordinal() <= b.type.ordinal() ? items.indexOf(a0) < items.indexOf(b1) : items.indexOf(b1) < items.indexOf(a0));
        assertEquals(drop, items.get(3));
        assertNull(ItemSort.AS_GOT.comparator(world.content), "as got: left alone");
        assertEquals(ItemSort.AS_GOT, ItemSort.TIER.step(1), "round and round");
    }
}
