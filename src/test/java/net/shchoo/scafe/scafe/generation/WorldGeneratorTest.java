package net.shchoo.scafe.scafe.generation;

import net.shchoo.scafe.scafe.model.ContentBatch;
import net.shchoo.scafe.scafe.model.MonsterType;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.ProductLine;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.SkillType;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.model.WorldContent;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WorldGeneratorTest {

    private static World grow(long seed) {
        World world = new World(1, "w", seed);
        world.content.apply(WorldGenerator.newSkills(world, 8));
        world.content.apply(WorldGenerator.newProductLines(world, 3));
        world.content.apply(WorldGenerator.newNode(world, new Position(2, 3), 1, 5, 10));
        return world;
    }

    @Test
    void sameSeedAndStepsGiveTheSameWorld() {
        assertEquals(grow(42).content.history(), grow(42).content.history());
    }

    @Test
    void differentSeedsGiveDifferentWorlds() {
        assertNotEquals(grow(1).content.history(), grow(2).content.history());
    }

    @Test
    void generatingDoesNotTouchTheWorldUntilApplied() {
        World world = grow(7);
        int version = world.content.version();
        int skills = world.content.skills.size();
        ContentBatch batch = WorldGenerator.newSkills(world, 3);
        assertEquals(version, world.content.version());
        assertEquals(skills, world.content.skills.size());
        assertEquals(version + 1, batch.version());
        assertEquals(3, batch.skills().size());

        world.content.apply(batch);
        assertEquals(skills + 3, world.content.skills.size());
    }

    @Test
    void batchesMustBeAppliedInOrder() {
        World world = new World(1, "w", 3);
        ContentBatch first = WorldGenerator.newSkills(world, 1);
        world.content.apply(first);
        ContentBatch second = WorldGenerator.newSkills(world, 1);
        WorldContent fresh = new WorldContent();
        assertThrows(IllegalStateException.class, () -> fresh.apply(second));
        fresh.apply(first);
        fresh.apply(second);
        assertEquals(2, fresh.skills.size());
    }

    @Test
    void replayingHistoryRebuildsTheSameContent() {
        World world = grow(99);
        WorldContent replay = new WorldContent();
        world.content.history().forEach(replay::apply);
        assertEquals(world.content.skills.size(), replay.skills.size());
        for (Skill skill : world.content.skills) {
            Skill copy = replay.skill(skill.id);
            assertEquals(skill.name, copy.name);
            assertEquals(skill.symbol, copy.symbol);
            assertEquals(skill.productLines.size(), copy.productLines.size());
            for (int i = 0; i < skill.productLines.size(); i++) {
                List<Recipe> recipes = skill.productLines.get(i).recipes;
                List<Recipe> copied = copy.productLines.get(i).recipes;
                assertEquals(recipes.stream().map(r -> r.product.name).toList(), copied.stream().map(r -> r.product.name).toList());
                assertEquals(recipes.stream().map(r -> r.levelNeeded).toList(), copied.stream().map(r -> r.levelNeeded).toList());
            }
        }
        Node node = replay.nodeAt(new Position(2, 3));
        assertNotNull(node);
        assertEquals(1, node.capacity);
        assertEquals(5, node.maxCharges);
    }

    @Test
    void idsAreUniqueAcrossAllContent() {
        Set<Integer> ids = new HashSet<>();
        for (ContentBatch batch : grow(5).content.history()) {
            batch.skills().forEach(d -> assertTrue(ids.add(d.id()), "duplicate id " + d.id()));
            batch.products().forEach(d -> assertTrue(ids.add(d.id()), "duplicate id " + d.id()));
            batch.productLines().forEach(d -> assertTrue(ids.add(d.id()), "duplicate id " + d.id()));
            batch.recipes().forEach(d -> assertTrue(ids.add(d.id()), "duplicate id " + d.id()));
            batch.nodes().forEach(d -> assertTrue(ids.add(d.id()), "duplicate id " + d.id()));
        }
    }

    @Test
    void secondarySkillsUseProductsFromEarlierBatches() {
        World world = new World(1, "w", 11);
        world.content.apply(WorldGenerator.newSkills(world, 2));
        world.content.apply(WorldGenerator.newSkills(world, 6));
        Skill secondary = world.content.skills.stream().filter(s -> s.type == SkillType.SECONDARY).findFirst().orElseThrow();
        Product component = secondary.productLines.get(0).recipes.get(0).components.keySet().iterator().next();
        assertSame(component, world.content.product(component.id));
        assertTrue(component.recipesUsedIn > 0);
    }

    @Test
    void theFirstMonsterKindBringsCombatAndLaterOnesGetTougher() {
        World world = new World(1, "w", 4);
        world.content.apply(WorldGenerator.newSkills(world, 2));
        world.content.apply(WorldGenerator.newMonsterType(world));
        world.content.apply(WorldGenerator.newMonsterType(world));
        world.content.apply(WorldGenerator.newMonsterType(world));
        assertEquals(1, world.content.skills.stream().filter(s -> s.type == SkillType.COMBAT).count());
        assertEquals(2, world.content.craftingSkills().size());
        List<MonsterType> monsters = world.content.monsters;
        assertEquals(List.of("monster0", "monster1", "monster2"), monsters.stream().map(m -> m.name).toList());
        assertTrue(monsters.get(0).level < monsters.get(1).level && monsters.get(1).level < monsters.get(2).level);
        assertTrue(monsters.get(0).maxHp < monsters.get(2).maxHp);
        for (MonsterType monster : monsters) {
            assertTrue(monster.roamRadius >= 2 && monster.roamRadius <= 5, "roam " + monster.roamRadius);
            assertTrue(monster.moveTicks >= 2 && monster.moveTicks <= 4, "slower than players: " + monster.moveTicks);
        }
        assertTrue(world.content.combatSkill().productLines.isEmpty());
    }

    @Test
    void dropsAreDiscoveredInOrderWithHalvingChances() {
        World world = new World(1, "w", 4);
        world.content.apply(WorldGenerator.newMonsterType(world));
        MonsterType monster = world.content.monsters.get(0);
        assertEquals(2, monster.nextDropOneIn());
        for (int k = 0; k < 3; k++) {
            ContentBatch batch = WorldGenerator.newDrop(world, monster.id);
            assertEquals(1, batch.drops().size());
            assertEquals(1, batch.products().size(), "each drop is a new neutral product");
            world.content.apply(batch);
        }
        assertEquals(List.of("monster0-drop0", "monster0-drop1", "monster0-drop2"),
                monster.drops.stream().map(d -> d.product().name).toList());
        assertEquals(List.of(2, 4, 8), monster.drops.stream().map(MonsterType.Drop::oneIn).toList());
        assertEquals(16, monster.nextDropOneIn());
    }

    @Test
    void theCombatSkillNeverGetsProductLinesOrShiftsSkillNames() {
        World world = new World(1, "w", 8);
        world.content.apply(WorldGenerator.newSkills(world, 2));
        world.content.apply(WorldGenerator.newMonsterType(world));
        world.content.apply(WorldGenerator.newSkills(world, 1));
        for (int i = 0; i < 10; i++) {
            world.content.apply(WorldGenerator.newProductLines(world, 3));
        }
        assertTrue(world.content.combatSkill().productLines.isEmpty());
        assertEquals(List.of("skill0", "skill1", "skill2"), world.content.craftingSkills().stream().map(s -> s.name).toList());
    }

    @Test
    void monstersAndDropsReplayFromHistory() {
        World world = new World(1, "w", 6);
        world.content.apply(WorldGenerator.newSkills(world, 1));
        world.content.apply(WorldGenerator.newMonsterType(world));
        world.content.apply(WorldGenerator.newDrop(world, world.content.monsters.get(0).id));
        WorldContent replay = new WorldContent();
        world.content.history().forEach(replay::apply);
        MonsterType original = world.content.monsters.get(0);
        MonsterType copy = replay.monster(original.id);
        assertEquals(original.name, copy.name);
        assertEquals(original.maxHp, copy.maxHp);
        assertEquals(original.drops.get(0).product().name, copy.drops.get(0).product().name);
        assertEquals(original.drops.get(0).oneIn(), copy.drops.get(0).oneIn());
    }

    @Test
    void craftedRecipesTakeOneToThreeInputsOfTheSameTier() {
        int multiInput = 0, multiAmount = 0;
        for (long seed = 1; seed <= 20; seed++) {
            World world = new World(1, "w", seed);
            world.content.apply(WorldGenerator.newSkills(world, 10));
            world.content.apply(WorldGenerator.newProductLines(world, 6));
            Map<Product, Skill> skillOf = new HashMap<>();
            world.content.skills.forEach(skill -> skill.productLines.forEach(line -> line.recipes.forEach(recipe -> {
                assertEquals(line.recipes.indexOf(recipe), recipe.product.tier, "a product's tier is its place in its line");
                skillOf.put(recipe.product, skill);
            })));
            for (Skill skill : world.content.skills) {
                for (ProductLine line : skill.productLines) {
                    for (int tier = 0; tier < line.recipes.size(); tier++) {
                        Recipe recipe = line.recipes.get(tier);
                        if (skill.type == SkillType.PRIMARY) {
                            assertTrue(recipe.components.isEmpty());
                            continue;
                        }
                        assertTrue(recipe.components.size() >= 1 && recipe.components.size() <= 3, recipe.describeComponents());
                        multiInput += tier == 0 && recipe.components.size() > 1 ? 1 : 0;
                        boolean madeFromSecondary = false;
                        for (Map.Entry<Product, Integer> c : recipe.components.entrySet()) {
                            Skill maker = skillOf.get(c.getKey());
                            assertNotNull(maker, "components are other skills' products");
                            assertNotSame(skill, maker);
                            assertEquals(recipe.product.tier, c.getKey().tier, "same tier in as out: " + recipe.product.name + " <- " + c.getKey().name);
                            assertTrue(c.getValue() >= 1 && c.getValue() <= 3);
                            multiAmount += tier == 0 && c.getValue() > 1 ? 1 : 0;
                            if (skill.type == SkillType.SECONDARY) {
                                assertEquals(SkillType.PRIMARY, maker.type, "secondaries are made from primaries");
                            }
                            madeFromSecondary |= maker.type == SkillType.SECONDARY;
                        }
                        if (skill.type == SkillType.TERTIARY) {
                            assertTrue(madeFromSecondary, "tertiaries always use a secondary product");
                        }
                    }
                }
            }
        }
        assertTrue(multiInput > 0, "some lines take more than one input");
        assertTrue(multiAmount > 0, "some take more than one of something");
    }
}
