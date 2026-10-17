package net.shchoo.scafe.scafe;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WorldGeneratorTest {

    private static World grow(long seed) {
        World world = new World(1, "w", seed);
        world.content.apply(WorldGenerator.newSkills(world, 8));
        world.content.apply(WorldGenerator.newProductLines(world, 3));
        world.content.apply(WorldGenerator.newNode(world, new Board.Position(2, 3), 1, 5, 10));
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
        Node node = replay.nodeAt(new Board.Position(2, 3));
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
}
